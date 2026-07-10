package org.windy.windui.server;

import org.windy.windui.core.gui.GuiRegistry;
import org.windy.windui.core.gui.HudRegistry;
import org.windy.windui.core.protocol.AssetCodec;
import org.windy.windui.core.protocol.ControlCodec;
import org.windy.windui.core.protocol.ControlCodec.CtrlAction;
import org.windy.windui.core.protocol.HudCodec;
import org.windy.windui.core.protocol.PatchCodec;
import org.windy.windui.core.protocol.ProtocolFeatures;
import org.windy.windui.server.asset.ImgStore;
import org.windy.windui.server.script.Effect;
import org.windy.windui.server.script.UiScriptService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台无关的 GUI 驱动：统一 JSON 界面（{@link GuiRegistry}）与 JS 脚本界面（{@link UiScriptService}）的
 * 打开、事件路由与效果分派。平台只需实现 {@link UiHost}。
 * <p>
 * 打开时脚本优先（有 {@code scripts/<id>.js} 走 JS，否则 {@code guis/<id>.json}）；
 * 事件路由：玩家当前界面是脚本→派发到脚本 {@code onEvent} 并执行效果，否则返回 false 交调用方
 * （平台的 JSON 事件总线）处理。
 */
public final class GuiDriver {

    private final GuiRegistry registry;
    private final UiScriptService scripts;
    private final ImgStore images;
    private final HudRegistry huds;

    /** 界面里的图片引用 {@code img:<path>}。 */
    private static final Pattern IMG_REF = Pattern.compile("img:([^\"\\s]+)");

    /** 每个玩家当前打开的界面 id，用于把回调事件路由到对应脚本。 */
    private final Map<UUID, String> openScreens = new ConcurrentHashMap<>();

    /** 每个玩家本会话已下发过的图片 hash，避免重复推送（客户端已缓存）。 */
    private final Map<UUID, Set<String>> sentAssets = new ConcurrentHashMap<>();

    /** 客户端协议版本登记（前向兼容门控）。 */
    private final ClientRegistry clients = new ClientRegistry();

    public GuiDriver(GuiRegistry registry, UiScriptService scripts, ImgStore images, HudRegistry huds) {
        this.registry = registry;
        this.scripts = scripts;
        this.images = images;
        this.huds = huds;
    }

    public GuiRegistry registry() {
        return registry;
    }

    /** 文件驱动的 HUD 注册表({@code /windui hud <id>} 按 id 取)。 */
    public HudRegistry huds() {
        return huds;
    }

    /** 客户端协议版本登记（平台的 {@code /windui hello} 写入、特性门控读取）。 */
    public ClientRegistry clients() {
        return clients;
    }

    /**
     * 推 HUD 面板给玩家（前向兼容门控:客户端不支持 HUD 则静默忽略）。
     *
     * @param panelsJson 面板 JSON 数组;空数组 {@code "[]"} = 清空 HUD
     */
    public void hud(UiHost host, String panelsJson) {
        if (!clients.supports(host.id(), ProtocolFeatures.HUD)) {
            return;
        }
        host.sendHud(HudCodec.encode(panelsJson));
    }

    public UiScriptService scripts() {
        return scripts;
    }

    /** 图片仓库（Phase 3 asset 客户端接收半接线后，界面里的 {@code img:} 引用由此解析下发）。 */
    public ImgStore images() {
        return images;
    }

    /** JSON 界面 + JS 脚本的全部 id（有序去重）。 */
    public Set<String> ids() {
        Set<String> all = new TreeSet<>(registry.ids());
        all.addAll(scripts.ids());
        return all;
    }

    /**
     * 渲染并推送一个界面给玩家（JS 脚本优先，否则 JSON），记录为该玩家当前界面。
     *
     * @return 界面类型 {@code "JS"}/{@code "JSON"}；不存在或脚本失败返回 {@code null}
     */
    public String open(UiHost host, String guiId) {
        String json;
        String kind;
        if (scripts.has(guiId)) {
            json = scripts.render(guiId, host.ctx());
            if (json == null) return null;
            kind = "JS";
        } else {
            json = registry.get(guiId);
            if (json == null) return null;
            kind = "JSON";
        }
        json = host.applyPlaceholders(json);
        json = pushAssetsAndRewrite(host, json);
        host.sendUi(json);
        openScreens.put(host.id(), guiId);
        return kind;
    }

    /**
     * 扫描界面 JSON 里的 {@code img:<path>} 引用：经 {@link ImgStore} 解析为图片，在 asset 频道下发字节
     * （本会话未发过的才推），并把 {@code img:path} 重写为 {@code asset:<hash>}（客户端据 hash 取缓存图）。
     * 找不到文件的引用原样保留。
     */
    private String pushAssetsAndRewrite(UiHost host, String json) {
        if (images == null || !json.contains("img:")) {
            return json;
        }
        Set<String> sent = sentAssets.computeIfAbsent(host.id(), k -> ConcurrentHashMap.newKeySet());
        Matcher m = IMG_REF.matcher(json);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            AssetCodec.Asset asset = images.resolve(m.group(1));
            if (asset != null) {
                if (sent.add(asset.hash())) {
                    host.sendAsset(AssetCodec.encode(asset.hash(), asset.png()));
                }
                m.appendReplacement(sb, Matcher.quoteReplacement("asset:" + asset.hash()));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0))); // 找不到，原样留
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 路由按钮回调事件。
     *
     * @return {@code true} 表示已由脚本处理；{@code false} 表示当前界面非脚本，调用方应自行处理（JSON 事件总线）
     */
    public boolean event(UiHost host, String eventId, List<String> rawData) {
        String current = openScreens.get(host.id());
        if (current == null || !scripts.has(current)) {
            return false;
        }
        // 拆出 readback 值（客户端以 __rb:<id>:<url-base64> 编码追加进 data，避免命令参数含空格）
        List<String> data = new ArrayList<>();
        Map<String, String> values = new HashMap<>();
        for (String d : rawData) {
            if (d.startsWith("__rb:")) {
                String body = d.substring(5);
                int colon = body.indexOf(':');
                if (colon > 0) {
                    try {
                        String value = new String(
                                Base64.getUrlDecoder().decode(body.substring(colon + 1)), StandardCharsets.UTF_8);
                        values.put(body.substring(0, colon), value);
                    } catch (IllegalArgumentException ignored) {
                        // 非法编码，忽略该项
                    }
                }
            } else {
                data.add(d);
            }
        }
        List<Effect> effects = scripts.dispatchEvent(current, host.ctx(), eventId, data, values);
        if (effects == null) {
            host.sendMessage("§c[WindUI] 脚本事件处理失败: " + current);
            return true;
        }
        applyEffects(host, current, effects);
        return true;
    }

    /** 玩家断开时清理追踪（当前界面 + 已下发图片记录 + 客户端版本）。 */
    public void clear(UUID id) {
        openScreens.remove(id);
        sentAssets.remove(id);
        clients.clear(id);
    }

    /**
     * 执行脚本 onEvent 产出的效果列表。
     * <p>
     * message 走聊天；rerender/open 直接重推 UI；close/toast/command/sound 攒成一批经控制频道下发；
     * patch 攒成一批经增量频道下发（按 id 局部改属性，不重推整树）。同类效果各自保持先后顺序。
     */
    private void applyEffects(UiHost host, String currentGui, List<Effect> effects) {
        List<CtrlAction> control = new ArrayList<>();
        List<String> patches = new ArrayList<>(); // 每项是一段 {id,props} 的 JSON（脚本 JSON.stringify 而来）
        for (Effect fx : effects) {
            switch (fx.type()) {
                // message：发聊天（§ 颜色码由平台解析）
                case "message" -> {
                    if (fx.arg() != null) host.sendMessage(fx.arg());
                }
                case "rerender" -> open(host, currentGui);
                case "open" -> {
                    if (fx.arg() != null) open(host, fx.arg());
                }
                // 以下为服务端主动效果 → 控制频道下发结构化客户端动作
                case "close" -> control.add(CtrlAction.of("closeScreen"));
                case "toast" -> {
                    if (fx.arg() != null) control.add(CtrlAction.of("showToast", fx.arg()));
                }
                case "command" -> {
                    if (fx.arg() != null) control.add(CtrlAction.of("executeCommand", fx.arg()));
                }
                case "sound" -> {
                    if (fx.arg() != null) control.add(CtrlAction.of("playSound", fx.arg()));
                }
                // patch → 增量更新频道
                case "patch" -> {
                    if (fx.arg() != null) patches.add(fx.arg());
                }
                // hud → HUD 频道(one-off,内部已门控)
                case "hud" -> {
                    if (fx.arg() != null) hud(host, fx.arg());
                }
                case "hudClear" -> hud(host, "[]");
                default -> { /* 未知/暂不支持的效果，忽略 */ }
            }
        }
        if (!control.isEmpty()) {
            host.sendControl(ControlCodec.encode(control));
        }
        if (!patches.isEmpty()) {
            // 前向兼容门控：客户端支持增量更新才发 patch，否则退回整树重推（老客户端优雅降级）
            if (clients.supports(host.id(), ProtocolFeatures.PATCH)) {
                host.sendPatch(PatchCodec.encode("[" + String.join(",", patches) + "]"));
            } else {
                open(host, currentGui);
            }
        }
    }
}
