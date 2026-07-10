package org.windy.windui.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.windy.windui.core.placeholder.PlaceholderResolver;

/**
 * 代理级占位符提供者。
 * <p>
 * 只解析 Velocity 代理已知的数据（玩家名/UUID/所在子服/延迟/在线数）。
 * 经济、库存等富数据需后端 Paper 侧解析（后期做）——此处不认识的 key 返回 null，原样保留。
 * <p>
 * <b>命名遵循 PlaceholderAPI 规范</b>：{@code %<expansion>_<param>%}，第一段下划线前为扩展 id
 * （{@code player} / {@code server}），与将来 Paper 侧真 PAPI 无缝对齐。
 */
public final class VelocityPlaceholders {

    private VelocityPlaceholders() {
    }

    public static PlaceholderResolver forPlayer(Player player, ProxyServer server) {
        return key -> switch (key) {
            case "player_name" -> player.getUsername();
            case "player_uuid" -> player.getUniqueId().toString();
            case "player_ping" -> String.valueOf(player.getPing());
            case "player_server" -> player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName()).orElse("");
            case "server_online" -> String.valueOf(server.getPlayerCount());
            default -> null;
        };
    }
}
