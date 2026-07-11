package org.windy.windui.bukkit;

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

/**
 * Bukkit/Paper 侧的 {@link PlatformAdapter}，与 {@code VelocityPlatformAdapter} 对称。
 * {@link WsServer} 由 {@link org.windy.windui.server.WindUiRuntime} 启动后经 {@link #bindWs} 注入。
 */
public final class BukkitPlatformAdapter implements PlatformAdapter {

    private final Logger logger;
    private final GuiEventBus<Player> eventBus;
    private final BukkitInventoryOps inventoryOps; // 可空：无真库存能力（如未接后端）时为 null
    private WsServer ws;

    public BukkitPlatformAdapter(Logger logger, GuiEventBus<Player> eventBus, BukkitInventoryOps inventoryOps) {
        this.logger = logger;
        this.eventBus = eventBus;
        this.inventoryOps = inventoryOps;
    }

    /** runtime 起来后把实际 WsServer 注入（用于构造下发用的 host）。 */
    public void bindWs(WsServer ws) {
        this.ws = ws;
    }

    /** 直接给一个在线玩家建 host（命令侧下发用）。离线返回 null。 */
    public UiHost hostFor(Player player) {
        return player == null ? null : new BukkitUiHost(player, ws, inventoryOps);
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
        logger.info("[WindUI] WebSocket 客户端就绪: "
                + (p != null ? p.getName() : player) + "，协议 v" + version);
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
