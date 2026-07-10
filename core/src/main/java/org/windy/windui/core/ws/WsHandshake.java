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

    private WsHandshake() {
    }

    /** 由客户端的 {@code Sec-WebSocket-Key} 计算服务端应答的 {@code Sec-WebSocket-Accept}。 */
    public static String acceptKey(String secWebSocketKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((secWebSocketKey + MAGIC).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e); // JVM 必有 SHA-1
        }
    }

    /** 生成客户端用的随机 16 字节 base64 nonce（{@code Sec-WebSocket-Key}）。 */
    public static String newKey() {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    // ===== 服务端侧 =====

    /**
     * 读 HTTP Upgrade 请求：返回小写化的请求头 map（含 request-line 存于键 {@code ""}）。
     * 不是合法 WebSocket 升级（缺 Upgrade: websocket 或 Sec-WebSocket-Key）时返 {@code null}。
     */
    public static Map<String, String> readRequest(InputStream in) throws IOException {
        Map<String, String> headers = readHeaders(in);
        if (headers == null) return null;
        String upgrade = headers.getOrDefault("upgrade", "");
        if (!upgrade.toLowerCase(Locale.ROOT).contains("websocket")) return null;
        if (!headers.containsKey("sec-websocket-key")) return null;
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

    /** 读并校验服务端 101 应答；accept 值与预期不符抛 {@link IOException}。 */
    public static void readResponse(InputStream in, String sentKey) throws IOException {
        Map<String, String> headers = readHeaders(in);
        if (headers == null) throw new IOException("空的握手应答");
        String line = headers.getOrDefault("", "");
        if (!line.contains("101")) throw new IOException("握手未升级: " + line);
        String accept = headers.getOrDefault("sec-websocket-accept", "");
        if (!acceptKey(sentKey).equals(accept)) throw new IOException("Sec-WebSocket-Accept 不匹配");
    }

    // ===== 内部：读 HTTP 头 =====

    /** 读到空行为止的 HTTP 头；键小写，request/status line 放键 {@code ""}。EOF 返 {@code null}。 */
    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String first = readLine(in);
        if (first == null) return null;
        headers.put("", first);
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                headers.put(name, value);
            }
        }
        return headers;
    }

    /** 读一行（以 CRLF 或 LF 结尾，剥掉行尾符）；流结束且无内容返 {@code null}。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (!any && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.US_ASCII);
    }
}
