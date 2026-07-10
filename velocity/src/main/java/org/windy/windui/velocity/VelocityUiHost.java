package org.windy.windui.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.windy.windui.core.placeholder.Placeholders;
import org.windy.windui.core.ws.WsProtocol;
import org.windy.windui.server.UiHost;
import org.windy.windui.server.ws.WsServer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Velocity 侧的 {@link UiHost} 实现（平台薄 glue）：把 {@link org.windy.windui.server.GuiDriver} 的
 * 平台无关请求落到具体的 Velocity Player。
 * <p>
 * 下发通道全走独立端口的 {@link WsServer}（按玩家 UUID 找 WS 连接写帧），不再用 MC 插件消息；聊天消息
 * 仍走 Velocity 的 {@code player.sendMessage}。未来 Bukkit 版照写一份 BukkitUiHost 即可。
 */
public final class VelocityUiHost implements UiHost {

    private final Player player;
    private final ProxyServer server;
    private final WsServer ws;

    public VelocityUiHost(Player player, ProxyServer server, WsServer ws) {
        this.player = player;
        this.server = server;
        this.ws = ws;
    }

    @Override
    public UUID id() {
        return player.getUniqueId();
    }

    @Override
    public Map<String, Object> ctx() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("player", player.getUsername());
        ctx.put("uuid", player.getUniqueId().toString());
        ctx.put("server", player.getCurrentServer()
                .map(s -> s.getServerInfo().getName()).orElse(""));
        ctx.put("ping", player.getPing());
        ctx.put("online", server.getPlayerCount());
        return ctx;
    }

    @Override
    public String applyPlaceholders(String json) {
        return Placeholders.apply(json, VelocityPlaceholders.forPlayer(player, server));
    }

    /** 统一下发：WS 未启用（null）时静默跳过，避免 NPE。 */
    private void send(byte channel, byte[] payload) {
        if (ws == null) return;
        ws.send(player.getUniqueId(), channel, payload);
    }

    @Override
    public void sendUi(String json) {
        send(WsProtocol.UI, json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void sendAsset(byte[] payload) {
        send(WsProtocol.ASSET, payload);
    }

    @Override
    public void sendMessage(String legacyText) {
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(legacyText));
    }

    @Override
    public void sendControl(byte[] payload) {
        send(WsProtocol.CTRL, payload);
    }

    @Override
    public void sendPatch(byte[] payload) {
        send(WsProtocol.PATCH, payload);
    }

    @Override
    public void sendHud(byte[] payload) {
        send(WsProtocol.HUD, payload);
    }
}
