package org.windy.windui.bukkit;

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
 * WindUI 的 Paper 服务端 host 入口（薄 glue，跨 Paper 版本共享）。装配全在共享的 {@link WindUiRuntime}
 * （server_core）；本类只做：读端口配置、建 {@link BukkitPlatformAdapter}、起 runtime、挂进退服引导 +
 * 注册 /windui。命令走 Brigadier（{@link WindUiBrigadier}），因为 paper-plugin.yml 无 commands 块。
 * <p>
 * <b>为什么落 bukkit_common（编 Java 21 / paper-api 1.21.1）</b>：入口用的 JavaPlugin/事件/Brigadier/
 * LifecycleEvents 在 Paper 1.21↔26.x 一致，一份 Java 21 字节码被两版运行时都能加载（Java 21 服 & Java 25 服）。
 * 各版本模块只是打包壳（paper-plugin.yml + 打包）。若某版本入口 API 真漂移，再在该版本 glue 覆盖。
 */
public final class WindUiPaperPlugin extends JavaPlugin implements Listener {

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

        // 业务回调（结构化 JSON 界面按钮的演示处理器）
        GuiEventBus<Player> eventBus = new GuiEventBus<>();
        eventBus.register("buy_diamond", (player, data) -> player.sendMessage(Component.text(
                "[WindUI] 你购买了 " + (data.isEmpty() ? "1" : data.get(0)) + " 个钻石（演示）", NamedTextColor.GREEN)));
        eventBus.register("buy_gold", (player, data) -> player.sendMessage(Component.text(
                "[WindUI] 你购买了 " + (data.isEmpty() ? "1" : data.get(0)) + " 个金锭（演示）", NamedTextColor.GOLD)));
        eventBus.register("confirm_yes", (player, data) -> player.sendMessage(Component.text(
                "[WindUI] 已确认（演示）", NamedTextColor.AQUA)));

        // 共享运行时装配 + 真库存 seam（Paper 实现）→ 脚本 ctx.inventory / ctx.give / ctx.take 生效。
        BukkitPlatformAdapter adapter =
                new BukkitPlatformAdapter(getLogger(), eventBus, new PaperInventoryOps());
        runtime = WindUiRuntime.start(getDataFolder().toPath(), port, WS_MAX_MESSAGE_BYTES, adapter);
        adapter.bindWs(runtime.ws());

        // 命令：/windui 经 Brigadier 注册（paper-plugin.yml 无 commands 块）
        WindUiCommands commands = new WindUiCommands(runtime, adapter);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(WindUiBrigadier.node(commands), "WindUI 界面命令", List.of()));

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("WindUI Paper host loaded");
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    /**
     * 玩家进服后下发 WebSocket 引导：{@code {publicHost, port, token}} 经一条哨兵系统消息发出，
     * 客户端用官方 {@code ClientChatReceivedEvent} 拦下解析并连 WS。host 留空 → 客户端复用当前
     * MC 连接地址（即本服地址）。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (runtime == null || !runtime.wsRunning()) {
            return;
        }
        Player player = event.getPlayer();
        String token = runtime.tokenFor(player.getUniqueId());
        player.sendMessage(Component.text(WsBootstrap.encode("", runtime.wsPort(), token)));
    }

    /** 玩家退服：清界面追踪、WS 连接与 token。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (runtime != null) {
            runtime.onPlayerDisconnect(event.getPlayer().getUniqueId());
        }
    }
}
