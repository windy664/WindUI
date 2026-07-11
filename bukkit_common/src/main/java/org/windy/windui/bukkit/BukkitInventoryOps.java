package org.windy.windui.bukkit;

import org.bukkit.entity.Player;
import org.windy.windui.server.ItemView;

import java.util.List;

/**
 * 真库存读写的<b>版本漂移收口 seam</b>。签名只用稳定的 {@link Player} + 中性 {@link ItemView}/String/int，
 * <b>不暴露 org.bukkit.inventory.ItemStack</b>——26.x 的 ItemStack/Inventory 大改全被关进各 Paper 版本的
 * 实现里（{@code bukkit_paper_26/BukkitInventoryOps26} 等），bukkit_common 与 server_core 不受影响。
 * <p>
 * 这正是"走 B"要做对的那个抽象点：以后加 Paper 版本只补一份本接口实现。
 */
public interface BukkitInventoryOps {

    /** 玩家主背包快照（渲染用）。 */
    List<ItemView> snapshot(Player player);

    /** 给玩家物品（按 id + 数量）；返回是否全部放进。 */
    boolean give(Player player, String itemId, int count);

    /** 从玩家背包扣物品；返回实际扣除数量。 */
    int take(Player player, String itemId, int count);
}
