package org.windy.windui.bukkit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.windy.windui.server.ItemView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 真库存 seam 的 Paper 实现。所有 {@code org.bukkit.inventory.ItemStack} / {@link Material} 操作只在本类。
 * <p>用的都是自 1.13 稳定的 Bukkit 库存 API（getStorageContents/matchMaterial/addItem/removeItem/getKey），
 * 在 Paper 1.21 与 26.x 上一致，故落 bukkit_common（编 paper-api 1.21.1）。若某版本 ItemStack 真漂移，
 * 再在该版本 glue 里覆盖一份。
 */
public final class PaperInventoryOps implements BukkitInventoryOps {

    @Override
    public List<ItemView> snapshot(Player player) {
        List<ItemView> out = new ArrayList<>();
        for (ItemStack st : player.getInventory().getStorageContents()) {
            if (st == null || st.getType().isAir()) {
                continue;
            }
            out.add(new ItemView(st.getType().getKey().toString(), st.getAmount(), null, List.of()));
        }
        return out;
    }

    @Override
    public boolean give(Player player, String itemId, int count) {
        Material material = Material.matchMaterial(itemId);
        if (material == null || material.isAir()) {
            return false;
        }
        Map<Integer, ItemStack> leftover =
                player.getInventory().addItem(new ItemStack(material, Math.max(1, count)));
        return leftover.isEmpty();
    }

    @Override
    public int take(Player player, String itemId, int count) {
        Material material = Material.matchMaterial(itemId);
        if (material == null || material.isAir()) {
            return 0;
        }
        int want = Math.max(1, count);
        Map<Integer, ItemStack> notRemoved =
                player.getInventory().removeItem(new ItemStack(material, want));
        int remaining = 0;
        for (ItemStack st : notRemoved.values()) {
            if (st != null) {
                remaining += st.getAmount();
            }
        }
        return want - remaining;
    }
}
