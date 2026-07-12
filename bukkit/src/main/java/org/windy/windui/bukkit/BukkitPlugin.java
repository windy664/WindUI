package org.windy.windui.bukkit;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.windui.core.gui.GuiEventBus;
import org.windy.windui.core.ws.WsBootstrap;
import org.windy.windui.server.WindUiRuntime;

/**
 * WindUI 的 legacy Bukkit 服务端 host 入口（全版本通用）。装配全在共享的 {@link WindUiRuntime}（server_core）；
 * 本类只做：读端口配置、建 {@link BukkitPlatformAdapter}、起 runtime、挂进退服引导 + 注册 /windui（plugin.yml）。
 */
public final class BukkitPlugin extends JavaPlugin implements Listener {

    private static final int WS_MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    private static final int DEFAULT_WS_PORT = 25599;

    private WindUiRuntime runtime;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int port = getConfig().getInt("websocket-port", DEFAULT_WS_PORT);
        if (port < 1 || port > 65535) {
            getLogger().warning("[WindUI] websocket-port 非法(" + port + ")，回退 " + DEFAULT_WS_PORT);
            port = DEFAULT_WS_PORT;
        }

        GuiEventBus<Player> eventBus = new GuiEventBus<>();
        eventBus.register("buy_diamond", (player, data) -> player.sendMessage(ChatColor.GREEN
                + "[WindUI] 你购买了 " + (data.isEmpty() ? "1" : data.get(0)) + " 个钻石（演示）"));
        eventBus.register("buy_gold", (player, data) -> player.sendMessage(ChatColor.GOLD
                + "[WindUI] 你购买了 " + (data.isEmpty() ? "1" : data.get(0)) + " 个金锭（演示）"));
        eventBus.register("confirm_yes", (player, data) -> player.sendMessage(ChatColor.AQUA
                + "[WindUI] 已确认（演示）"));

        BukkitPlatformAdapter adapter = new BukkitPlatformAdapter(getLogger(), eventBus);
        runtime = WindUiRuntime.start(getDataFolder().toPath(), port, WS_MAX_MESSAGE_BYTES, adapter);
        adapter.bindWs(runtime.ws());

        BukkitCommand command = new BukkitCommand(runtime, adapter);
        if (getCommand("windui") != null) {
            getCommand("windui").setExecutor(command);
            getCommand("windui").setTabCompleter(command);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("WindUI Bukkit host loaded");
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (runtime == null || !runtime.wsRunning()) {
            return;
        }
        Player player = event.getPlayer();
        String token = runtime.tokenFor(player.getUniqueId());
        player.sendMessage(WsBootstrap.encode("", runtime.wsPort(), token));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (runtime != null) {
            runtime.onPlayerDisconnect(event.getPlayer().getUniqueId());
        }
    }
}
