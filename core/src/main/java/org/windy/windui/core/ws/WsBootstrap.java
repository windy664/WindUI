package org.windy.windui.core.ws;

/**
 * WebSocket 引导载荷：服务端把 {@code {host, port, token}} 经一条<b>哨兵聊天消息</b>下发给客户端，
 * 客户端用 NeoForge 官方的 {@code ClientChatReceivedEvent} 拦下解析（<b>零 Netty 注入</b>），据此连 WS。
 * <p>
 * 载荷格式：{@code <SENTINEL>host\tport\ttoken}。SENTINEL 用零宽字符，万一泄漏到聊天也几乎不可见。
 * host 允许为空——为空时客户端回退到“它连上的那台服务器地址”。
 * <p>
 * 引导消息来自聊天通道，解析时仍按不可信输入处理：严格限制总长度、host/token 长度、端口范围和控制字符，
 * 防止异常消息触发非法连接请求或在内存中保留超大字段。
 */
public final class WsBootstrap {

    /** 哨兵前缀（零宽空格包裹），客户端据此识别这是引导消息而非普通聊天。 */
    public static final String SENTINEL = "​windui-ws​";

    private static final String SEP = "\t";

    /** 域名、IPv4、IPv6 文本及常规代理地址均远小于此值。 */
    private static final int MAX_HOST_LENGTH = 255;

    /** 当前 token 是 192 位随机十六进制，共 48 字符；预留扩展空间但拒绝任意大凭证。 */
    private static final int MAX_TOKEN_LENGTH = 512;

    /** 完整聊天引导载荷上限。 */
    private static final int MAX_MESSAGE_LENGTH =
            SENTINEL.length() + MAX_HOST_LENGTH + 1 + 5 + 1 + MAX_TOKEN_LENGTH;

    public record Info(String host, int port, String token) {
        public Info {
            validateHost(host);
            validatePort(port);
            validateToken(token);
        }
    }

    private WsBootstrap() {
    }

    /**
     * 编成一条哨兵聊天字符串。
     *
     * @param host  客户端应连接的地址；可为空，表示回退当前代理地址
     * @param port  WebSocket 监听端口，范围 1～65535
     * @param token 会话鉴权 token，不得为空或包含控制字符
     * @return 可通过聊天通道发送的引导消息
     * @throws IllegalArgumentException 字段非法时抛出
     */
    public static String encode(String host, int port, String token) {
        String normalizedHost = host == null ? "" : host;
        validateHost(normalizedHost);
        validatePort(port);
        validateToken(token);
        return SENTINEL + normalizedHost + SEP + port + SEP + token;
    }

    /** 该聊天内容是否是我们的引导消息。 */
    public static boolean isBootstrap(String chatContent) {
        return chatContent != null && chatContent.startsWith(SENTINEL);
    }

    /**
     * 解析引导消息。
     *
     * @return 解析后的连接信息；非引导消息、格式非法或字段越界时返回 {@code null}
     */
    public static Info parse(String chatContent) {
        if (!isBootstrap(chatContent)
                || chatContent.length() > MAX_MESSAGE_LENGTH) {
            return null;
        }

        String body = chatContent.substring(SENTINEL.length());
        int firstSeparator = body.indexOf(SEP);
        if (firstSeparator < 0) {
            return null;
        }

        int secondSeparator = body.indexOf(SEP, firstSeparator + SEP.length());
        if (secondSeparator < 0
                || body.indexOf(SEP, secondSeparator + SEP.length()) >= 0) {
            return null;
        }

        String host = body.substring(0, firstSeparator);
        String portText = body.substring(
                firstSeparator + SEP.length(),
                secondSeparator);
        String token = body.substring(secondSeparator + SEP.length());

        if (portText.isEmpty()
                || portText.length() > 5
                || !isAsciiDigits(portText)) {
            return null;
        }

        try {
            int port = Integer.parseInt(portText);
            return new Info(host, port, token);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void validateHost(String host) {
        if (host == null) {
            throw new IllegalArgumentException("WebSocket host 不能为 null");
        }
        if (host.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException(
                    "WebSocket host 过长: " + host.length());
        }
        if (containsControlCharacter(host)) {
            throw new IllegalArgumentException(
                    "WebSocket host 含非法控制字符");
        }
    }

    private static void validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "非法 WebSocket 端口: " + port);
        }
    }

    private static void validateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "WebSocket token 不能为空");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "WebSocket token 过长: " + token.length());
        }
        if (containsControlCharacter(token)) {
            throw new IllegalArgumentException(
                    "WebSocket token 含非法控制字符");
        }
    }

    /**
     * 拒绝 C0 控制字符和 DEL。它们不应出现在主机名或 token 中，并会破坏基于制表符的线格式。
     */
    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x20 || character == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
