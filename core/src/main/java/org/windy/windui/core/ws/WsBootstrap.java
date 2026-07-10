package org.windy.windui.core.ws;

/**
 * WebSocket 引导载荷：服务端把 {@code {host, port, token}} 经一条<b>哨兵聊天消息</b>下发给客户端，
 * 客户端用 NeoForge 官方的 {@code ClientChatReceivedEvent} 拦下解析（<b>零 Netty 注入</b>），据此连 WS。
 * <p>
 * 载荷格式：{@code <SENTINEL>host\tport\ttoken}。SENTINEL 用零宽字符，万一泄漏到聊天也几乎不可见。
 * host 允许为空——为空时客户端回退到"它连上的那台服务器地址"。
 */
public final class WsBootstrap {

    /** 哨兵前缀（零宽空格包裹），客户端据此识别这是引导消息而非普通聊天。 */
    public static final String SENTINEL = "​windui-ws​";

    private static final String SEP = "\t";

    public record Info(String host, int port, String token) {
    }

    private WsBootstrap() {
    }

    /** 编成一条哨兵聊天字符串（host 可为空串）。 */
    public static String encode(String host, int port, String token) {
        return SENTINEL + (host == null ? "" : host) + SEP + port + SEP + token;
    }

    /** 该聊天内容是否是我们的引导消息。 */
    public static boolean isBootstrap(String chatContent) {
        return chatContent != null && chatContent.startsWith(SENTINEL);
    }

    /** 解析引导消息；非引导消息或格式非法返 {@code null}。 */
    public static Info parse(String chatContent) {
        if (!isBootstrap(chatContent)) return null;
        String body = chatContent.substring(SENTINEL.length());
        String[] parts = body.split(SEP, 3);
        if (parts.length != 3) return null;
        try {
            return new Info(parts[0], Integer.parseInt(parts[1]), parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
