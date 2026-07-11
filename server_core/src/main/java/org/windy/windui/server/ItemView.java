package org.windy.windui.server;

import java.util.List;

/**
 * 平台无关的「一件物品」快照——{@link InventoryOps} 读真库存产出、注入脚本 {@code ctx.inventory} 供渲染。
 * <p>
 * 刻意用中性类型（不暴露 {@code org.bukkit.inventory.ItemStack}），故 seam 签名不随 MC 版本漂移：
 * 各 Paper 版本 glue 把自己的 ItemStack 翻成本记录，26.x 的 ItemStack 大改被收在 glue 里。
 *
 * @param itemId 物品 id（如 {@code "minecraft:diamond"}）
 * @param count  数量
 * @param name   显示名（可空；null=用物品自身默认名）
 * @param lore   描述行（可空/空列表）
 */
public record ItemView(String itemId, int count, String name, List<String> lore) {

    /**
     * 把一组物品序列化成 JSON 数组字符串。
     * <p>为什么手写而非 gson：本记录经 {@code ctx.inventory} 注入 Rhino，而沙箱 {@code ClassShutter(false)}
     * 屏蔽所有 Java 类 → 不能直接传 Java List/Map 给 JS。故序列化成字符串，PRELUDE 里 {@code JSON.parse}
     * 成原生 JS 数组。server_core 主源集不依赖 gson，这里零依赖手写。
     */
    public static String toJsonArray(List<ItemView> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            ItemView v = items.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(quote(v.itemId))
                    .append(",\"count\":").append(v.count)
                    .append(",\"name\":").append(v.name == null ? "null" : quote(v.name))
                    .append(",\"lore\":[");
            List<String> lore = v.lore == null ? List.of() : v.lore;
            for (int j = 0; j < lore.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(quote(lore.get(j)));
            }
            sb.append("]}");
        }
        return sb.append(']').toString();
    }

    /** 最小 JSON 字符串转义（引号/反斜杠/控制字符）。 */
    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
