package org.windy.windui.server;

import java.util.List;

/**
 * 平台无关的「玩家真库存」能力。<b>可选</b>：只有能碰真库存的后端 host（Bukkit/Paper）提供；
 * 纯代理（Velocity）没有世界/库存，{@link UiHost#inventory()} 返回 null。
 * <p>
 * 这是"走 B（Bukkit host）"要解锁的核心能力——脚本能读、能改玩家<b>真实</b>物品栏，而不只是画图标。
 * 具体的 ItemStack 读写在各 Paper 版本 glue 里（见 {@code BukkitInventoryOps} seam）；本接口只用中性类型。
 */
public interface InventoryOps {

    /** 玩家主背包快照（渲染用）。 */
    List<ItemView> snapshot();

    /** 给玩家物品（按 id + 数量）；返回是否全部放进（背包满则 false）。 */
    boolean give(String itemId, int count);

    /** 从玩家背包扣物品；返回实际扣除数量（不足则扣尽有的）。 */
    int take(String itemId, int count);
}
