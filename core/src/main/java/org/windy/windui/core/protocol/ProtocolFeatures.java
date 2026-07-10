package org.windy.windui.core.protocol;

import java.util.Map;

/**
 * 协议特性 → 引入时的协议版本。两端共识的**前向兼容契约**。
 * <p>
 * 服务端拿客户端上报的协议版本（见 {@link WindUIProtocol#VERSION}）问 {@link #supports}，
 * 只对「版本够高、认得该特性」的客户端使用它，否则走降级路径（如 patch 退回整树重推）。
 * <p>
 * <b>加新特性的规矩</b>：①{@link WindUIProtocol#VERSION} +1；②在此登记 {@code 特性名 → 该版本号}；
 * ③服务端用到该特性处先 {@code supports(clientVersion, 特性)} 门控。这样老客户端永远不会收到它不认得的东西。
 */
public final class ProtocolFeatures {

    private ProtocolFeatures() {
    }

    /** 组件级增量更新（{@code windui:patch} 频道）——协议 v1 引入。 */
    public static final String PATCH = "patch";

    /** 事件回程走 serverbound 插件消息频道（{@code windui:event}）而非命令——协议 v2 引入。 */
    public static final String EVENT_CHANNEL = "event_channel";

    /** 非模态 HUD（{@code windui:hud} 频道）——协议 v3 引入。 */
    public static final String HUD = "hud";

    /** 特性 → 最低要求的客户端协议版本。 */
    private static final Map<String, Integer> MIN_VERSION = Map.of(
            PATCH, 1,
            EVENT_CHANNEL, 2,
            HUD, 3
    );

    /**
     * 版本为 {@code clientVersion} 的客户端是否支持 {@code feature}。
     * 未登记的特性一律视为「不支持」（安全默认——宁可降级也不发未知包）。
     */
    public static boolean supports(int clientVersion, String feature) {
        Integer min = MIN_VERSION.get(feature);
        return min != null && clientVersion >= min;
    }
}
