package org.windy.windui.core.ws;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * RFC 6455 WebSocket 打开握手（纯逻辑，无网络策略，可离线单测）。
 * <p>
 * 服务端：{@link #readRequest} 读 HTTP Upgrade 请求头 → {@link #acceptKey} 算 {@code Sec-WebSocket-Accept}
 * → {@link #writeAcceptResponse} 回 101。客户端：{@link #newKey}+{@link #writeRequest} 发起 →
 * {@link #readResponse} 校验回应的 accept 值。
 */
public final class WsHandshake {

    /** RFC 6455 §1.3 固定 GUID。 */
    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** 防慢请求和恶意超长请求头。 */
    private static final int MAX_LINE_BYTES = 8192;
    private static final int MAX_HEADER_LINES = 100;
    private static final int MAX_TOTAL_HEADER_BYTES = 32768;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WsHandshake() {
    }

    /** 由客户端的 {@code Sec-WebSocket-Key} 计算服务端应答的 {@code Sec-WebSocket-Accept}。 */
    public static String acceptKey(String secWebSocketKey) {
        if (secWebSocketKey == null || secWebSocketKey.isBlank()) {
            throw new IllegalArgumentException("Sec-WebSocket-Key 不能为空");
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((secWebSocketKey + MAGIC).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /** 生成客户端用的随机 16 字节 base64 nonce（{@code Sec-WebSocket-Key}）。 */
    public static String newKey() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    // ===== 服务端侧 =====

    /**
     * 读 HTTP Upgrade 请求：返回小写化的请求头 map（含 request-line 存于键 {@code ""}）。
     * 不是合法 WebSocket 13 升级时返 {@code null}。
     */
    public static Map<String, String> readRequest(InputStream in) throws IOException {
        Map<String, String> headers = readHeaders(in);
        if (headers == null) {
            return null;
        }

        String requestLine = headers.getOrDefault("", "");
        if (!requestLine.startsWith("GET ") || !requestLine.endsWith(" HTTP/1.1")) {
            return null;
        }

        String upgrade = headers.getOrDefault("upgrade", "");
        if (!containsToken(upgrade, "websocket")) {
            return null;
        }

        String connection = headers.getOrDefault("connection", "");
        if (!containsToken(connection, "upgrade")) {
            return null;
        }

        String version = headers.getOrDefault("sec-websocket-version", "");
        if (!"13".equals(version)) {
            return null;
        }

        String key = headers.get("sec-websocket-key");
        if (!isValidWebSocketKey(key)) {
            return null;
        }

        return headers;
    }

    /** 写 101 Switching Protocols 应答，完成服务端握手。 */
    public static void writeAcceptResponse(OutputStream out, String secWebSocketKey) throws IOException {
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey(secWebSocketKey) + "\r\n"
                + "\r\n";
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    // ===== 客户端侧 =====

    /** 发起 HTTP Upgrade 请求；返回本次用的 {@code Sec-WebSocket-Key}（供 {@link #readResponse} 校验）。 */
    public static String writeRequest(OutputStream out, String host, int port, String path) throws IOException {
        requireHeaderSafe(host, "host");
        requireHeaderSafe(path, "path");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host 不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("非法端口: " + port);
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("WebSocket path 必须以 / 开头");
        }

        String key = newKey();
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return key;
    }

    /** 读并校验服务端 101 应答；响应不合法或 accept 值与预期不符时抛 {@link IOException}。 */
    public static void readResponse(InputStream in, String sentKey) throws IOException {
        Map<String, String> headers = readHeaders(in);
        if (headers == null) {
            throw new IOException("空的 WebSocket 握手应答");
        }

        String line = headers.getOrDefault("", "");
        if (!line.startsWith("HTTP/1.1 101 ") && !line.equals("HTTP/1.1 101")) {
            throw new IOException("WebSocket 握手未升级: " + line);
        }

        if (!containsToken(headers.getOrDefault("upgrade", ""), "websocket")) {
            throw new IOException("WebSocket 握手应答缺少 Upgrade: websocket");
        }

        if (!containsToken(headers.getOrDefault("connection", ""), "upgrade")) {
            throw new IOException("WebSocket 握手应答缺少 Connection: Upgrade");
        }

        String accept = headers.getOrDefault("sec-websocket-accept", "");
        if (!acceptKey(sentKey).equals(accept)) {
            throw new IOException("Sec-WebSocket-Accept 不匹配");
        }
    }

    // ===== 内部：读 HTTP 头 =====

    /** 读到空行为止的 HTTP 头；键小写，request/status line 放键 {@code ""}。EOF 返 {@code null}。 */
    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String first = readLine(in);
        if (first == null) {
            return null;
        }

        int totalBytes = first.length();
        headers.put("", first);

        for (int lineCount = 0; lineCount < MAX_HEADER_LINES; lineCount++) {
            String line = readLine(in);
            if (line == null) {
                throw new IOException("HTTP 头在空行前意外结束");
            }
            if (line.isEmpty()) {
                return headers;
            }

            totalBytes += line.length();
            if (totalBytes > MAX_TOTAL_HEADER_BYTES) {
                throw new IOException("HTTP 请求头过大");
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("非法 HTTP 请求头行: " + line);
            }

            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.isEmpty()) {
                throw new IOException("HTTP 请求头名称为空");
            }

            // 合并重复头，保留逗号 token 语义。
            headers.merge(name, value, (oldValue, newValue) -> oldValue + "," + newValue);
        }

        throw new IOException("HTTP 请求头行数过多");
    }

    /** 读一行（以 CRLF 或 LF 结尾，剥掉行尾符）；流结束且无内容返 {@code null}。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean any = false;

        while (true) {
            int c = in.read();
            if (c == -1) {
                if (!any && buf.size() == 0) {
                    return null;
                }
                break;
            }

            any = true;
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                if (buf.size() >= MAX_LINE_BYTES) {
                    throw new IOException("HTTP 请求头单行过长");
                }
                buf.write(c);
            }
        }

        return buf.toString(StandardCharsets.US_ASCII);
    }

    private static boolean containsToken(String headerValue, String expected) {
        if (headerValue == null || headerValue.isEmpty()) {
            return false;
        }
        for (String token : headerValue.split(",")) {
            if (expected.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidWebSocketKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(key.trim()).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void requireHeaderSafe(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " 含非法换行符");
        }
    }
}
