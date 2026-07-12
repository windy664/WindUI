package org.windy.windui.bukkit;

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
 * Bukkit 侧的 {@link UiHost}（legacy）：下发走独立端口 {@link WsServer}；聊天消息走老版 {@code sendMessage(String)}
 * （效果文本本就是 § 传统色码，无需 Adventure）。故本模块全版本通用，不依赖 Paper 特性。
 */
public final class BukkitUiHost implements UiHost {

    private final Player player;
    private final WsServer ws;

    public BukkitUiHost(Player player, WsServer ws) {
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
        ctx.put("ping", 0); // 老版 Spigot 无 getPing()；保留字段占位
        ctx.put("online", Bukkit.getOnlinePlayers().size());
        ctx.put("inventory", ItemView.toJsonArray(new BukkitInventoryOps(player).snapshot()));
        return ctx;
    }

    @Override
    public String applyPlaceholders(String json) {
        return Placeholders.apply(json, BukkitPlaceholders.forPlayer(player));
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
        player.sendMessage(legacyText); // § 传统色码，老版 API 直接支持
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
        return new BukkitInventoryOps(player);
    }
}
