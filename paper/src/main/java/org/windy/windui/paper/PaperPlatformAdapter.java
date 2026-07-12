package org.windy.windui.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.windy.windui.core.gui.GuiEventBus;
import org.windy.windui.server.PlatformAdapter;
import org.windy.windui.server.UiHost;
import org.windy.windui.server.ws.WsServer;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Paper 侧的 {@link PlatformAdapter}。{@link WsServer} 由 runtime 起来后经 {@link #bindWs} 注入。 */
public final class PaperPlatformAdapter implements PlatformAdapter {

    private final Logger logger;
    private final GuiEventBus<Player> eventBus;
    private WsServer ws;

    public PaperPlatformAdapter(Logger logger, GuiEventBus<Player> eventBus) {
        this.logger = logger;
        this.eventBus = eventBus;
    }

    public void bindWs(WsServer ws) {
        this.ws = ws;
    }

    public UiHost hostFor(Player player) {
        return player == null ? null : new PaperUiHost(player, ws);
    }

    @Override
    public UiHost hostFor(UUID player) {
        return hostFor(Bukkit.getPlayer(player));
    }

    @Override
    public void onUnhandledEvent(UUID player, String eventId, List<String> data) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            eventBus.dispatch(eventId, p, data);
        }
    }

    @Override
    public void onClientReady(UUID player, int version) {
        Player p = Bukkit.getPlayer(player);
        logger.info("[WindUI] WebSocket 客户端就绪: " + (p != null ? p.getName() : player) + "，协议 v" + version);
    }

    @Override
    public void onClientClosed(UUID player) {
        Player p = Bukkit.getPlayer(player);
        logger.info("[WindUI] WebSocket 客户端断开: " + (p != null ? p.getName() : player));
    }

    @Override
    public void logInfo(String message) {
        logger.info(message);
    }

    @Override
    public void logWarn(String message, Throwable error) {
        logger.log(Level.WARNING, message, error);
    }
}
