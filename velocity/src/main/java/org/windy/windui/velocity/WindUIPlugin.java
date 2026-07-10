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
import org.windy.windui.core.gui.GuiRegistry;
import org.windy.windui.core.gui.HudRegistry;
import org.windy.windui.core.protocol.EventCodec;
import org.windy.windui.core.ws.WsBootstrap;
import org.windy.windui.server.GuiDriver;
import org.windy.windui.server.asset.ImgStore;
import org.windy.windui.server.script.UiScriptService;
import org.windy.windui.server.ws.TokenStore;
import org.windy.windui.server.ws.WsServer;

import java.io.IOException;
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

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private GuiDriver driver;
    private GuiEventBus<Player> eventBus;

    // WebSocket 传输（独立端口，取代旧的裸 Netty 插件消息通道）
    private WsSettings settings;
    private TokenStore tokens;
    private WsServer ws;

    @Inject
    public WindUIPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // 文件驱动 GUI：plugins/windui/guis/*.json + JS 脚本界面：plugins/windui/scripts/*.js
        GuiRegistry registry = new GuiRegistry(dataDirectory.resolve("guis"));
        int loaded = registry.load();
        UiScriptService scriptService = new UiScriptService(dataDirectory.resolve("scripts"));
        int scriptsLoaded = scriptService.load();

        // 自定义图片仓库：plugins/windui/img/
        ImgStore imgStore = new ImgStore(dataDirectory.resolve("img"));
        imgStore.init();

        // 文件驱动 HUD：plugins/windui/huds/*.json
        HudRegistry hudRegistry = new HudRegistry(dataDirectory.resolve("huds"));
        int hudsLoaded = hudRegistry.load();

        // 平台无关的 GUI 驱动（open/event/效果分派全在 server_core）
        this.driver = new GuiDriver(registry, scriptService, imgStore, hudRegistry);

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

        // ===== WebSocket 传输 =====
        settings = WsSettings.load(dataDirectory, logger);
        tokens = new TokenStore();
        if (settings.enabled()) {
            ws = new WsServer(settings.bind(), settings.port(), settings.maxMessageBytes(), new WsHandler());
            try {
                ws.start();
                logger.info("[WindUI] WebSocket 监听 {}:{}（记得放行防火墙）",
                        settings.bind().isEmpty() ? "0.0.0.0" : settings.bind(), ws.port());
            } catch (IOException e) {
                logger.error("[WindUI] WebSocket 启动失败（端口占用？），UI 下发不可用", e);
                ws = null;
            }
        } else {
            logger.info("[WindUI] WebSocket 服务已在配置中禁用（ws-enabled=false）");
        }

        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("windui").build(),
                new UiCommand(server, driver, eventBus, ws)
        );

        logger.info("WindUI Velocity plugin loaded, {} JSON GUI(s) + {} JS script(s) + {} HUD(s)",
                loaded, scriptsLoaded, hudsLoaded);
    }

    /**
     * 玩家进入子服后下发 WebSocket 引导：{@code {publicHost, port, token}} 经一条哨兵系统消息发出，
     * 客户端用官方 {@code ClientChatReceivedEvent} 拦下解析并连 WS（切服会重发，客户端重连，无害）。
     */
    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        if (ws == null) return;
        Player player = event.getPlayer();
        String token = tokens.tokenFor(player.getUniqueId());
        player.sendMessage(Component.text(
                WsBootstrap.encode(settings.publicHost(), ws.port(), token)));
    }

    /** 玩家断开：清理其界面追踪、图片记录与 token。 */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (driver != null) driver.clear(uuid);
        if (tokens != null) tokens.remove(uuid);
    }

    /** 代理关闭：停 WebSocket。 */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (ws != null) ws.stop();
    }

    /** WebSocket 服务端回调：鉴权 + 上行 hello/event 分派。 */
    private final class WsHandler implements WsServer.Handler {
        @Override
        public UUID authenticate(String token) {
            return tokens.resolve(token);
        }

        @Override
        public void onHello(UUID player, int version) {
            driver.clients().noteHello(player, version);
        }

        @Override
        public void onEvent(UUID player, byte[] eventBytes) {
            Player p = server.getPlayer(player).orElse(null);
            if (p == null) return;
            try {
                EventCodec.Event ev = EventCodec.decode(eventBytes);
                if (!driver.event(new VelocityUiHost(p, server, ws), ev.eventId(), ev.data())) {
                    eventBus.dispatch(ev.eventId(), p, ev.data());
                }
            } catch (Exception ex) {
                logger.warn("[WindUI] 事件回程解析失败", ex);
            }
        }

        @Override
        public void onDisconnect(UUID player) {
            // WS 连接断开（玩家可能仍在线，只是 UI 通道断了）；MC 断线由 DisconnectEvent 清理。
        }
    }
}
