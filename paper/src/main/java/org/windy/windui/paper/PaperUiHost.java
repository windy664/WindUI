package org.windy.windui.paper;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.windy.windui.core.placeholder.Placeholders;
import org.windy.windui.core.ws.WsProtocol;
import org.windy.windui.server.InventoryOps;
import org.windy.windui.server.ItemView;
import org.windy.windui.server.UiHost;
import org.windy.windui.server.ws.WsServer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Paper 侧的 {@link UiHost}：下发走独立端口的 {@link WsServer}；聊天消息走 Paper 原生 Adventure。
 */
public final class PaperUiHost implements UiHost {

    private final Player player;
    private final WsServer ws;

    public PaperUiHost(Player player, WsServer ws) {
        this.player = player;
        this.ws = ws;
    }

    @Override
    public UUID id() {
        return player.getUniqueId();
    }

    @Override
    public Map<String, Object> ctx() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("player", player.getName());
        ctx.put("uuid", player.getUniqueId().toString());
        ctx.put("server", player.getWorld().getName());
        ctx.put("ping", player.getPing());
        ctx.put("online", Bukkit.getOnlinePlayers().size());
        ctx.put("inventory", ItemView.toJsonArray(new PaperInventoryOps(player).snapshot()));
        return ctx;
    }

    @Override
    public String applyPlaceholders(String json) {
        return Placeholders.apply(json, PaperPlaceholders.forPlayer(player));
    }

    private void send(byte channel, byte[] payload) {
        if (ws != null) {
            ws.send(player.getUniqueId(), channel, payload);
        }
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

    @Override
    public InventoryOps inventory() {
        return new PaperInventoryOps(player);
    }
}
