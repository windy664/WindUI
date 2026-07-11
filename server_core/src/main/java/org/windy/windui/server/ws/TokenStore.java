package org.windy.windui.server.ws;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 一次性鉴权 token 的登记表（平台无关）。玩家上线时由平台签发 token 随引导消息下发，
 * 客户端连 WS 时首帧回传，{@link WsServer.Handler#authenticate} 经 {@link #resolve} 校验绑定玩家。
 * <p>
 * 每玩家保持一个有效 token（重连/切服复用同一个），玩家断开时 {@link #remove} 清除。token 为
 * 192 位随机十六进制，不可猜测。
 */
public final class TokenStore {

    private final Map<String, UUID> byToken = new ConcurrentHashMap<>();
    private final Map<UUID, String> byPlayer = new ConcurrentHashMap<>();
    private final SecureRandom rnd = new SecureRandom();

    /** 取该玩家当前 token（不存在则新签发）。 */
    public String tokenFor(UUID player) {
        return byPlayer.computeIfAbsent(player, p -> {
            String token = newToken();
            byToken.put(token, p);
            return token;
        });
    }

    /** 校验 token；通过返玩家 UUID，无效返 {@code null}。 */
    public UUID resolve(String token) {
        return token == null ? null : byToken.get(token);
    }

    /** 清除某玩家的 token（断线时调）。 */
    public void remove(UUID player) {
        String token = byPlayer.remove(player);
        if (token != null) byToken.remove(token);
    }

    private String newToken() {
        byte[] b = new byte[24];
        rnd.nextBytes(b);
        // 手写十六进制（不用 Java 17 的 HexFormat，因 server_core 编到 Java 16 地板）。
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
