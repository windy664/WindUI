package org.windy.windui.bukkit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.windy.windui.server.InventoryOps;
import org.windy.windui.server.ItemView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 真库存读写（绑定单个玩家）。用自 1.13 稳定的 Bukkit 库存 API，全版本通用。 */
public final class BukkitInventoryOps implements InventoryOps {

    private final Player player;

    public BukkitInventoryOps(Player player) {
        this.player = player;
    }

    @Override
    public List<ItemView> snapshot() {
        List<ItemView> out = new ArrayList<>();
        for (ItemStack st : player.getInventory().getStorageContents()) {
            if (st == null || st.getType() == Material.AIR) {
                continue;
            }
            out.add(new ItemView(st.getType().getKey().toString(), st.getAmount(), null, List.of()));
        }
        return out;
    }

    @Override
    public boolean give(String itemId, int count) {
        Material material = Material.matchMaterial(itemId);
        if (material == null || material == Material.AIR) {
            return false;
        }
        return player.getInventory().addItem(new ItemStack(material, Math.max(1, count))).isEmpty();
    }

    @Override
    public int take(String itemId, int count) {
        Material material = Material.matchMaterial(itemId);
        if (material == null || material == Material.AIR) {
            return 0;
        }
        int want = Math.max(1, count);
        Map<Integer, ItemStack> notRemoved = player.getInventory().removeItem(new ItemStack(material, want));
        int remaining = 0;
        for (ItemStack st : notRemoved.values()) {
            if (st != null) {
                remaining += st.getAmount();
            }
        }
        return want - remaining;
    }
}
