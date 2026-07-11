package org.windy.windui.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import icyllis.modernui.core.Core;
import icyllis.modernui.mc.MuiModApi;
import org.slf4j.Logger;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.windy.windui.Config;
import org.windy.windui.WindUI;
import org.windy.windui.client.net.WsClient;
import org.windy.windui.client.render.AssetCache;
import org.windy.windui.client.render.ImageLoader;
import org.windy.windui.client.render.SDUIScreen;
import org.windy.windui.core.parser.UIJsonParser;
import org.windy.windui.core.protocol.AssetCodec;
import org.windy.windui.core.protocol.PatchCodec;
import org.windy.windui.core.script.ActionExecutor;
import org.windy.windui.core.ws.WsBootstrap;

import java.net.InetSocketAddress;

/**
 * 客户端入口：引导握手 + WebSocket 连接（NeoForge 26.1.2）。
 * <p>
 * <b>平台特定 glue（每个 MC 版本各写一份）</b>：依赖 NeoForge 事件、InputConstants、Minecraft 连接等
 * 版本特定 API。传输本身走 {@link WsClient}（共享层，独立端口、脱离 MC 封包管线）；引导消息经官方
 * {@link ClientChatReceivedEvent} 拦截（零 Netty 注入）。{@link SDUIScreen} 与共享渲染层无需改动。
 */
@EventBusSubscriber(modid = WindUI.MODID, value = Dist.CLIENT)
public final class WindUIClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 26.1.2 的图片加载器：ModernUI 3.13 的 mc.ImageStore。注入给共享渲染层。 */
    private static final ImageLoader IMAGE_LOADER =
            (ns, path) -> icyllis.modernui.mc.ImageStore.getInstance().apply(ns, path);

    /** 26.1.2 的 3D 模型渲染器：MinecraftSurfaceView + vanilla 物品后端。注入给共享渲染层。 */
    private static final org.windy.windui.client.render.ModelRenderer MODEL_RENDERER = new ModelRendererImpl();

    private static boolean wasPressed = false;
    private static SDUIScreen currentScreen;
    private static boolean sduiOpen = false;

    private WindUIClient() {
    }

    // ===== 引导：拦截哨兵聊天消息 → 连 WebSocket =====

    @SubscribeEvent
    static void onChatReceived(ClientChatReceivedEvent event) {
        String content = event.getMessage().getString();
        if (!WsBootstrap.isBootstrap(content)) return;
        event.setCanceled(true); // 隐藏引导消息，不显示在聊天框

        WsBootstrap.Info info = WsBootstrap.parse(content);
        if (info == null) return;
        String host = (info.host() == null || info.host().isEmpty()) ? currentServerHost() : info.host();
        if (host == null) {
            LOGGER.warn("[WindUI] 收到引导但无法确定服务端地址，跳过");
            return;
        }
        LOGGER.info("[WindUI] 收到引导 → 连接 ws://{}:{}", host, info.port());
        WsClient.connect(host, info.port(), info.token(), newCallbacks());
    }

    /** 登出/切服：断开 WebSocket。 */
    @SubscribeEvent
    static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WsClient.disconnect();
        onScreenClosed();
    }

    /** 客户端连上代理的地址（WS 服务端与代理同机），作为引导未给 host 时的回退。 */
    private static String currentServerHost() {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return null;
        var addr = conn.getConnection().channel().remoteAddress();
        return (addr instanceof InetSocketAddress isa) ? isa.getHostString() : null;
    }

    /**
     * 建入站分派回调：把 {@link WsClient} 的频道分派接到本版本的主线程投递 + MC/ModernUI 调用。
     * 各版本只差这几行 glue，收发线格式的逻辑全在共享层。
     */
    private static WsClient.Callbacks newCallbacks() {
        return new WsClient.Callbacks() {
            @Override public void onUi(String json) {
                Minecraft.getInstance().execute(() -> handleUiPacket(json));
            }
            @Override public void onAsset(byte[] framed) {
                AssetCodec.Asset asset = AssetCodec.decode(framed);
                // 留一份原始字节给 HUD(vanilla 纹理惰性建);Screen 路仍解码成 ModernUI Image(须 UI 线程)。
                org.windy.windui.client.hud.HudImageStore.put(asset.hash(), asset.png());
                Minecraft.getInstance().execute(() ->
                        Core.executeOnUiThread(() -> AssetCache.decodeAndCache(asset.hash(), asset.png())));
            }
            @Override public void onControl(String json) {
                Minecraft.getInstance().execute(() -> handleControlPacket(json));
            }
            @Override public void onPatch(byte[] framed) {
                var patches = PatchCodec.decode(framed);
                Minecraft.getInstance().execute(() -> {
                    if (sduiOpen && currentScreen != null) currentScreen.applyPatch(patches);
                });
            }
            @Override public void onHud(byte[] framed) {
                org.windy.windui.client.hud.HudState.set(
                        org.windy.windui.core.protocol.HudCodec.decode(framed));
            }
        };
    }

    // ===== UI 处理 =====

    private static void handleUiPacket(String uiJson) {
        var mc = Minecraft.getInstance();
        var api = new SafeClientImpl();

        if (!sduiOpen || currentScreen == null) {
            LOGGER.info("[WindUI] 打开 SDUI 界面 (openScreen)");
            currentScreen = new SDUIScreen(api, WindUIClient::onScreenClosed, IMAGE_LOADER, MODEL_RENDERER);
            MuiModApi.openScreen(currentScreen);
            sduiOpen = true;
        } else {
            LOGGER.info("[WindUI] 热更新已开界面 (reload)");
        }
        mc.execute(() -> currentScreen.reload(uiJson));
    }

    public static void onScreenClosed() {
        sduiOpen = false;
        currentScreen = null;
    }

    /**
     * 处理控制包：把服务端下发的一批结构化动作交给 {@link ActionExecutor} 执行
     * （closeScreen/executeCommand/showToast/playSound）。客户端仍是傻执行器，无任何脚本。
     */
    private static void handleControlPacket(String json) {
        var exec = new ActionExecutor(new SafeClientImpl());
        for (var action : UIJsonParser.parseActions(json)) {
            exec.execute(action);
        }
    }

    // ===== 按键检测（本地测试界面，不依赖服务端） =====

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        var window = mc.getWindow();
        int key = Config.shopKeyCode;
        boolean pressed = key > 0 && InputConstants.isKeyDown(window, key);

        if (pressed && !wasPressed) {
            LOGGER.info("[WindUI] {} 键按下 -> 打开本地测试界面", key);
            var api = new SafeClientImpl();
            currentScreen = new SDUIScreen(api, WindUIClient::onScreenClosed, IMAGE_LOADER, MODEL_RENDERER);
            MuiModApi.openScreen(currentScreen);
            sduiOpen = true;
            currentScreen.reload(TEST_UI_JSON);
        }
        wasPressed = pressed;
    }

    // ===== 测试数据（纯 JSON，无 JS） =====

    private static final String TEST_UI_JSON = """
            {
              "type": "LinearLayout",
              "props": { "orientation": 1 },
              "children": [
                { "type": "TextView", "props": { "text": "WindUI SDUI 测试", "textSize": 24, "textColor": "#FFD700" } },
                { "type": "TextView", "props": { "text": "由服务端 JSON 驱动渲染", "textSize": 14, "textColor": "#8B97A6" } },
                { "type": "ImageView", "props": { "src": "minecraft:textures/item/diamond.png", "width": 48, "height": 48, "padding": 6 } },
                {
                  "type": "Button",
                  "props": { "text": "点击传送", "textSize": 16, "textColor": "#4FA3FF" },
                  "onClick": [
                    { "action": "executeCommand", "args": ["/spawn"] },
                    { "action": "showToast", "args": ["正在传送..."] }
                  ]
                },
                {
                  "type": "Button",
                  "props": { "text": "关闭界面", "textSize": 16, "textColor": "#FF6B6B" },
                  "onClick": { "action": "closeScreen" }
                }
              ]
            }
            """;
}
