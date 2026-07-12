package org.windy.windui.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.windy.windui.core.placeholder.PlaceholderResolver;

/**
 * Bukkit 侧占位符（PlaceholderAPI 规范 {@code %<expansion>_<param>%}）。
 * 老版 Spigot 无 {@code Player.getPing()}，故 legacy 版不提供 player_ping。
 */
public final class BukkitPlaceholders {

    private BukkitPlaceholders() {
    }

    public static PlaceholderResolver forPlayer(Player player) {
        return key -> switch (key) {
            case "player_name" -> player.getName();
            case "player_uuid" -> player.getUniqueId().toString();
            case "player_world", "player_server" -> player.getWorld().getName();
            case "server_online" -> String.valueOf(Bukkit.getOnlinePlayers().size());
            default -> null;
        };
    }
}
