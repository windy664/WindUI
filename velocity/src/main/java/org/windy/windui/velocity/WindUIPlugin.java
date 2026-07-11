package org.windy.windui.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.windy.windui.core.gui.GuiEventBus;
import org.windy.windui.core.ws.WsBootstrap;
import org.windy.windui.server.WindUiRuntime;

import java.nio.file.Path;
import java.util.UUID;

@Plugin(
        id = "windui",
        name = "WindUI",
        version = "1.0-SNAPSHOT",
        description = "Server-Driven UI for Minecraft",
        authors = {"windy"}
)
public class WindUIPlugin {

    private static final int WS_MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    // 共享运行时（registries+driver+WS+token+事件路由，全在 server_core）
    private WindUiRuntime runtime;
    // 业务回调（结构化 JSON 界面按钮的演示处理器；平台特定，留在插件里）
    private GuiEventBus<Player> eventBus;

    @Inject
    public WindUIPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // 按钮回调事件总线；业务逻辑注册到此（演示处理器验证回程）
        eventBus = new GuiEventBus<>();
        eventBus.register("buy_diamond", (player, data) -> {
            String amount = data.isEmpty() ? "1" : data.get(0);
            player.sendMessage(Component.text(
                    "[WindUI] 你购买了 " + amount + " 个钻石（演示，服务端已收到回调）", NamedTextColor.GREEN));
        });
        eventBus.register("buy_gold", (player, data) -> {
            String amount = data.isEmpty() ? "1" : data.get(0);
            player.sendMessage(Component.text(
                    "[WindUI] 你购买了 " + amount + " 个金锭（演示，服务端已收到回调）", NamedTextColor.GOLD));
        });
        eventBus.register("confirm_yes", (player, data) ->
                player.sendMessage(Component.text(
                        "[WindUI] 已确认（演示，服务端已收到回调）", NamedTextColor.AQUA)));

        // 共享运行时装配：文件驱动 registries + driver + WebSocket + token + 事件路由
        WsSettings settings = WsSettings.load(dataDirectory, logger);
        VelocityPlatformAdapter adapter = new VelocityPlatformAdapter(server, logger, eventBus);
        runtime = WindUiRuntime.start(dataDirectory, settings.port(), WS_MAX_MESSAGE_BYTES, adapter);
        adapter.bindWs(runtime.ws());

        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("windui").build(),
                new UiCommand(server, runtime.driver(), eventBus, runtime.ws())
        );

        logger.info("WindUI Velocity plugin loaded");
    }

    /**
     * 玩家进入子服后下发 WebSocket 引导：{@code {publicHost, port, token}} 经一条哨兵系统消息发出，
     * 客户端用官方 {@code ClientChatReceivedEvent} 拦下解析并连 WS（切服会重发，客户端重连，无害）。
     */
    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        if (runtime == null || !runtime.wsRunning()) return;
        Player player = event.getPlayer();
        String token = runtime.tokenFor(player.getUniqueId());
        player.sendMessage(Component.text(
                WsBootstrap.encode("", runtime.wsPort(), token)));
    }

    /** 玩家断开：清理其界面追踪、WS 连接与 token。 */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (runtime == null) return;
        UUID uuid = event.getPlayer().getUniqueId();
        runtime.onPlayerDisconnect(uuid);
    }

    /** 代理关闭：停 WebSocket。 */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (runtime != null) runtime.stop();
    }
}
