package org.windy.windui.paper;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.windui.core.gui.GuiEventBus;
import org.windy.windui.core.ws.WsBootstrap;
import org.windy.windui.server.WindUiRuntime;

import java.util.List;

/**
 * WindUI 的 Paper 26.x+ 服务端 host 入口。装配全在共享的 {@link WindUiRuntime}（server_core）；
 * 本类只做：读端口配置、建 {@link PaperPlatformAdapter}、起 runtime、挂进退服引导 + Brigadier 注册 /windui。
 */
public final class PaperPlugin extends JavaPlugin implements Listener {

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
        eventBus.register("buy_diamond", (player, data) -> player.sendMessage(Component.text(
                "[WindUI] 你购买了 " + (data.isEmpty() ? "1" : data.get(0)) + " 个钻石（演示）", NamedTextColor.GREEN)));
        eventBus.register("buy_gold", (player, data) -> player.sendMessage(Component.text(
                "[WindUI] 你购买了 " + (data.isEmpty() ? "1" : data.get(0)) + " 个金锭（演示）", NamedTextColor.GOLD)));
        eventBus.register("confirm_yes", (player, data) -> player.sendMessage(Component.text(
                "[WindUI] 已确认（演示）", NamedTextColor.AQUA)));

        PaperPlatformAdapter adapter = new PaperPlatformAdapter(getLogger(), eventBus);
        runtime = WindUiRuntime.start(getDataFolder().toPath(), port, WS_MAX_MESSAGE_BYTES, adapter);
        adapter.bindWs(runtime.ws());

        PaperCommands commands = new PaperCommands(runtime, adapter);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(PaperBrigadier.node(commands), "WindUI 界面命令", List.of()));

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("WindUI Paper host loaded");
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
        player.sendMessage(Component.text(WsBootstrap.encode("", runtime.wsPort(), token)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (runtime != null) {
            runtime.onPlayerDisconnect(event.getPlayer().getUniqueId());
        }
    }
}
