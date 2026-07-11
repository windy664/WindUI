package org.windy.windui.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.windy.windui.core.gui.GuiEventBus;
import org.windy.windui.server.PlatformAdapter;
import org.windy.windui.server.UiHost;
import org.windy.windui.server.ws.WsServer;

import java.util.List;
import java.util.UUID;

/**
 * Velocity 侧的 {@link PlatformAdapter}：把 {@link org.windy.windui.server.WindUiRuntime} 需要的几件
 * 平台特定事（按 UUID 建 host、未处理事件回退到业务总线、日志）落到 Velocity API。
 * <p>
 * {@link WsServer} 由 runtime 启动后经 {@link #bindWs} 注入（构造时 runtime 尚未建好）。
 */
final class VelocityPlatformAdapter implements PlatformAdapter {

    private final ProxyServer server;
    private final Logger logger;
    private final GuiEventBus<Player> eventBus;
    private WsServer ws;

    VelocityPlatformAdapter(ProxyServer server, Logger logger, GuiEventBus<Player> eventBus) {
        this.server = server;
        this.logger = logger;
        this.eventBus = eventBus;
    }

    /** runtime 起来后把实际 WsServer 注入（用于构造下发用的 host）。 */
    void bindWs(WsServer ws) {
        this.ws = ws;
    }

    @Override
    public UiHost hostFor(UUID player) {
        return server.getPlayer(player)
                .map(p -> (UiHost) new VelocityUiHost(p, server, ws))
                .orElse(null);
    }

    @Override
    public void onUnhandledEvent(UUID player, String eventId, List<String> data) {
        server.getPlayer(player).ifPresent(p -> eventBus.dispatch(eventId, p, data));
    }

    @Override
    public void onClientReady(UUID player, int version) {
        server.getPlayer(player).ifPresent(p -> logger.info(
                "[WindUI] WebSocket 客户端就绪: {} ({})，协议 v{}", p.getUsername(), player, version));
    }

    @Override
    public void onClientClosed(UUID player) {
        server.getPlayer(player).ifPresent(p -> logger.info(
                "[WindUI] WebSocket 客户端断开: {} ({})", p.getUsername(), player));
    }

    @Override
    public void logInfo(String message) {
        logger.info(message);
    }

    @Override
    public void logWarn(String message, Throwable error) {
        logger.error(message, error);
    }
}
