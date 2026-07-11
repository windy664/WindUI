package org.windy.windui.server;

import org.windy.windui.core.gui.GuiRegistry;
import org.windy.windui.core.gui.HudRegistry;
import org.windy.windui.core.protocol.EventCodec;
import org.windy.windui.server.asset.ImgStore;
import org.windy.windui.server.script.UiScriptService;
import org.windy.windui.server.ws.TokenStore;
import org.windy.windui.server.ws.WsServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 跨平台共享的 WindUI 服务端运行时：把文件驱动 registries、{@link GuiDriver}、独立端口 {@link WsServer}、
 * {@link TokenStore} 与 WS 事件路由一次性装配好。平台插件（velocity / bukkit / …）只需提供一个
 * {@link PlatformAdapter}，再挂各自的"进服发引导 + 命令"两处 glue。
 * <p>
 * 装配、鉴权、hello 门控、事件回程分派全在这里（server_core），所以任何新平台零重复。
 */
public final class WindUiRuntime {

    private final GuiDriver driver;
    private final TokenStore tokens;
    private final WsServer ws; // 启动失败为 null（下发静默降级）
    private final PlatformAdapter platform;

    private WindUiRuntime(GuiDriver driver, TokenStore tokens, WsServer ws, PlatformAdapter platform) {
        this.driver = driver;
        this.tokens = tokens;
        this.ws = ws;
        this.platform = platform;
    }

    /**
     * 从数据目录装配全部并启动 WebSocket。
     *
     * @param dataDir     插件数据目录（下有 guis/ scripts/ img/ huds/）
     * @param wsPort      WebSocket 监听端口（0 = 自动选）
     * @param wsMaxBytes  单条消息上限（防 OOM）
     * @param platform    平台适配器
     */
    public static WindUiRuntime start(Path dataDir, int wsPort, int wsMaxBytes, PlatformAdapter platform) {
        GuiRegistry registry = new GuiRegistry(dataDir.resolve("guis"));
        int guis = registry.load();
        UiScriptService scripts = new UiScriptService(dataDir.resolve("scripts"));
        int scriptCount = scripts.load();
        ImgStore images = new ImgStore(dataDir.resolve("img"));
        images.init();
        HudRegistry huds = new HudRegistry(dataDir.resolve("huds"));
        int hudCount = huds.load();

        GuiDriver driver = new GuiDriver(registry, scripts, images, huds);
        TokenStore tokens = new TokenStore();

        // WS 回调只依赖 driver/tokens/platform（不需要 ws 本身），故用独立 handler，装配一次即可。
        WsServer ws = new WsServer("", wsPort, wsMaxBytes, new RuntimeWsHandler(driver, tokens, platform));
        try {
            ws.start();
            platform.logInfo("[WindUI] WebSocket 监听 0.0.0.0:" + ws.port() + "（请放行该入站 TCP 端口）");
        } catch (Exception e) {
            // Kotlin 版 WsServer.start() 不声明受检异常；端口占用等运行时经此兜底。
            platform.logWarn("[WindUI] WebSocket 启动失败（TCP " + wsPort + " 被占用？），UI 下发不可用", e);
            ws = null;
        }

        platform.logInfo("[WindUI] 运行时就绪：" + guis + " 界面 + " + scriptCount + " 脚本 + " + hudCount + " HUD");
        return new WindUiRuntime(driver, tokens, ws, platform);
    }

    /** 平台无关的 GUI 驱动。 */
    public GuiDriver driver() {
        return driver;
    }

    /** WebSocket 服务端（可能为 null）；平台 host 下发时需要它。 */
    public WsServer ws() {
        return ws;
    }

    /** WebSocket 实际监听端口；未启动返回 -1。 */
    public int wsPort() {
        return ws == null ? -1 : ws.port();
    }

    public boolean wsRunning() {
        return ws != null;
    }

    /** 为玩家取/发一次性引导 token（进服时用于拼引导消息）。 */
    public String tokenFor(UUID player) {
        return tokens.tokenFor(player);
    }

    /** 玩家断线：清界面追踪 + 断 WS + 清 token。 */
    public void onPlayerDisconnect(UUID player) {
        driver.clear(player);
        if (ws != null) {
            ws.disconnect(player);
        }
        tokens.remove(player);
    }

    /** 关停：停 WebSocket。 */
    public void stop() {
        if (ws != null) {
            ws.stop();
        }
    }

    /** WS 回调：鉴权 + hello 门控 + 事件回程分派，全部走平台无关逻辑 + {@link PlatformAdapter}。 */
    private record RuntimeWsHandler(GuiDriver driver, TokenStore tokens, PlatformAdapter platform)
            implements WsServer.Handler {
        @Override
        public UUID authenticate(String token) {
            return tokens.resolve(token);
        }

        @Override
        public void onHello(UUID player, int version) {
            driver.clients().noteHello(player, version);
            platform.onClientReady(player, version);
        }

        @Override
        public void onEvent(UUID player, byte[] eventBytes) {
            UiHost host = platform.hostFor(player);
            if (host == null) {
                return; // 玩家已离线
            }
            try {
                EventCodec.Event ev = EventCodec.decode(eventBytes);
                if (!driver.event(host, ev.eventId(), ev.data())) {
                    platform.onUnhandledEvent(player, ev.eventId(), ev.data());
                }
            } catch (Exception ex) {
                platform.logWarn("[WindUI] 事件回程解析失败", ex);
            }
        }

        @Override
        public void onDisconnect(UUID player) {
            driver.clients().clear(player);
            platform.onClientClosed(player);
        }
    }
}
