package org.windy.windui.bukkit;

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
 * Bukkit/Paper 侧的 {@link UiHost} 实现（平台薄 glue），与 {@code VelocityUiHost} 对称：把
 * {@link org.windy.windui.server.GuiDriver} 的平台无关请求落到具体 Bukkit {@link Player}。
 * <p>
 * 下发通道全走独立端口的 {@link WsServer}（按玩家 UUID 找 WS 连接写帧）；聊天消息走 Paper 原生
 * Adventure {@code player.sendMessage}。只碰 1.21↔26.x 稳定的 API，故本类落 bukkit_common。
 */
public final class BukkitUiHost implements UiHost {

    private final Player player;
    private final WsServer ws;
    private final BukkitInventoryOps inventoryOps; // 可空：无真库存能力时不提供

    public BukkitUiHost(Player player, WsServer ws, BukkitInventoryOps inventoryOps) {
        this.player = player;
        this.ws = ws;
        this.inventoryOps = inventoryOps;
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
        ctx.put("server", player.getWorld().getName()); // 单机 Paper 无子服，用世界名
        ctx.put("ping", player.getPing());
        ctx.put("online", Bukkit.getOnlinePlayers().size());
        // 真库存快照以 JSON 字符串注入（沙箱不能直传 Java List）；脚本读 ctx.inventory（PRELUDE 已 JSON.parse）。
        if (inventoryOps != null) {
            ctx.put("inventory", ItemView.toJsonArray(inventoryOps.snapshot(player)));
        }
        return ctx;
    }

    @Override
    public InventoryOps inventory() {
        return inventoryOps == null ? null : new BukkitInventoryAdapter(player, inventoryOps);
    }

    @Override
    public String applyPlaceholders(String json) {
        return Placeholders.apply(json, BukkitPlaceholders.forPlayer(player));
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
