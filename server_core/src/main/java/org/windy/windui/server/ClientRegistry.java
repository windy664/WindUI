package org.windy.windui.server;

import org.windy.windui.core.protocol.ProtocolFeatures;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录每个玩家客户端上报的 WindUI 协议版本（经 {@code /windui hello <version>}），
 * 供服务端按 {@link ProtocolFeatures} 门控特性、对老客户端优雅降级。平台无关。
 * <p>
 * 未上报过的玩家视为**基线 v0**：只保证最朴素能力（整树 UI + click + 命令回程），
 * 新特性（如 patch）一律不发，走降级。玩家断开时 {@link #clear} 清除。
 */
public final class ClientRegistry {

    private final Map<UUID, Integer> versions = new ConcurrentHashMap<>();

    /** 记录客户端上报的协议版本。 */
    public void noteHello(UUID id, int version) {
        versions.put(id, Math.max(0, version));
    }

    /** 客户端协议版本；未上报过返回 0（基线）。 */
    public int versionOf(UUID id) {
        return versions.getOrDefault(id, 0);
    }

    /** 该玩家的客户端是否支持某特性。 */
    public boolean supports(UUID id, String feature) {
        return ProtocolFeatures.supports(versionOf(id), feature);
    }

    /** 玩家断开时清除。 */
    public void clear(UUID id) {
        versions.remove(id);
    }
}
