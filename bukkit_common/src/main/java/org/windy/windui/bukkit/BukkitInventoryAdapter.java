package org.windy.windui.bukkit;

import org.bukkit.entity.Player;
import org.windy.windui.server.InventoryOps;
import org.windy.windui.server.ItemView;

import java.util.List;

/**
 * 把版本无关的 {@link InventoryOps}（server_core 用）落到具体玩家 + 版本 seam {@link BukkitInventoryOps}。
 * 每个 {@link BukkitUiHost} 持一个（绑定该玩家）。
 */
final class BukkitInventoryAdapter implements InventoryOps {

    private final Player player;
    private final BukkitInventoryOps ops;

    BukkitInventoryAdapter(Player player, BukkitInventoryOps ops) {
        this.player = player;
        this.ops = ops;
    }

    @Override
    public List<ItemView> snapshot() {
        return ops.snapshot(player);
    }

    @Override
    public boolean give(String itemId, int count) {
        return ops.give(player, itemId, count);
    }

    @Override
    public int take(String itemId, int count) {
        return ops.take(player, itemId, count);
    }
}
