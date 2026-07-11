package org.windy.windui.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.windy.windui.core.placeholder.PlaceholderResolver;

/**
 * Bukkit/Paper 侧占位符提供者（M1 基础版）。命名遵循 PlaceholderAPI 规范 {@code %<expansion>_<param>%}。
 * <p>
 * M1 只解析服务端本地可直接拿到的字段；余额/库存等富数据留给 M2 接真 PlaceholderAPI（缺席时本类
 * 解析不到的 key 返回 null，{@code Placeholders.apply} 会原样保留，交给后续 resolver / PAPI 续解析）。
 */
public final class BukkitPlaceholders {

    private BukkitPlaceholders() {
    }

    public static PlaceholderResolver forPlayer(Player player) {
        return key -> switch (key) {
            case "player_name" -> player.getName();
            case "player_uuid" -> player.getUniqueId().toString();
            case "player_ping" -> String.valueOf(player.getPing());
            case "player_world", "player_server" -> player.getWorld().getName();
            case "server_online" -> String.valueOf(Bukkit.getOnlinePlayers().size());
            default -> null;
        };
    }
}
