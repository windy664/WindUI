package org.windy.windui.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.windy.windui.core.placeholder.PlaceholderResolver;

/**
 * Paper 侧占位符（PlaceholderAPI 规范 {@code %<expansion>_<param>%}）。M1 基础版；余额/背包等富数据留 PAPI。
 */
public final class PaperPlaceholders {

    private PaperPlaceholders() {
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
