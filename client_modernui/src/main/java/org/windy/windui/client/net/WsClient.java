package org.windy.windui.client.net;

import org.windy.windui.core.protocol.ControlCodec;
import org.windy.windui.core.protocol.EventCodec;
import org.windy.windui.core.protocol.WindUIProtocol;
import org.windy.windui.core.ws.WsConnection;
import org.windy.windui.core.ws.WsHandshake;
import org.windy.windui.core.ws.WsProtocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * WindUI 客户端 WebSocket 连接（<b>版本无关</b>，跨 MC 版本共享）。取代旧的裸 Netty 入站/出站
 * （{@code WindUITransport}/{@code OutboundPayload}/{@code ServerboundSender}）。
 * <p>
 * 独立 socket 直连服务端独立端口 → 握手 → 首帧发 token 鉴权 → 上报协议版本 → 读循环把入站帧按
 * {@link WsProtocol} 频道字节分派到平台注入的 {@link Callbacks}。完全不碰 MC 封包管线，杜绝那类断线。
 * <p>
 * {@link Callbacks} 的实现（各 neoforge 模块）负责把回调投递到主线程 + 调 MC/ModernUI；本类保持 MC-free。
 */
public final class WsClient {

    /** 单条消息上限（覆盖大图 asset），防 OOM。 */
    private static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

    /** 平台注入的入站回调（与旧 {@code WindUITransport.Callbacks} 同签名，glue 几乎不用改）。 */
    public interface Callbacks {
        void onUi(String json);

        void onAsset(byte[] framed);

        void onControl(String json);

        void onPatch(byte[] framed);

        void onHud(byte[] framed);
    }

    private static final Object LOCK = new Object();
    private static volatile WsConnection conn;
    private static Thread thread;

    private WsClient() {
    }

    /**
     * 连接服务端并开始读循环（非阻塞，另起 daemon 线程）。会先断开任何已有连接。
     *
     * @param host  服务端地址（引导消息给出；为空由调用方回退为当前服务器地址）
     * @param port  WebSocket 端口
     * @param token 一次性鉴权 token（引导消息给出）
     * @param cb    入站分派回调
     */
    public static void connect(String host, int port, String token, Callbacks cb) {
        disconnect();
        Thread t = new Thread(() -> run(host, port, token, cb), "windui-ws-client");
        t.setDaemon(true);
        synchronized (LOCK) {
            thread = t;
        }
        t.start();
    }

    /** 发一次事件回程（按钮回调/输入回读）；已连接且发送成功返 true，否则 false（调用方走命令兜底）。 */
    public static boolean sendEvent(String eventId, List<String> data) {
        WsConnection c = conn;
        if (c == null) return false;
        try {
            c.send(prepend(WsProtocol.EVENT, EventCodec.encode(eventId, data)));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isConnected() {
        return conn != null;
    }

    /** 断开连接（登出/切服时调）。 */
    public static void disconnect() {
        synchronized (LOCK) {
            WsConnection c = conn;
            if (c != null) {
                c.close();
                conn = null;
            }
            if (thread != null) {
                thread.interrupt();
                thread = null;
            }
        }
    }

    // ===== 内部 =====

    private static void run(String host, int port, String token, Callbacks cb) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            var in = socket.getInputStream();
            var out = socket.getOutputStream();

            String key = WsHandshake.writeRequest(out, host, port, "/windui");
            WsHandshake.readResponse(in, key);
            WsConnection c = new WsConnection(in, out, true, MAX_MESSAGE_BYTES); // 客户端出站加掩码

            // 鉴权 + 上报协议版本
            c.send(prepend(WsProtocol.AUTH, token.getBytes(StandardCharsets.UTF_8)));
            c.send(helloBytes());
            synchronized (LOCK) {
                conn = c;
            }

            // 读循环：按频道字节分派
            while (!Thread.currentThread().isInterrupted()) {
                byte[] msg = c.recv();
                dispatch(msg, cb);
            }
        } catch (Exception e) {
            // 连接失败 / 断线 / 被 disconnect 打断 —— 都在此收尾
        } finally {
            synchronized (LOCK) {
                conn = null;
            }
        }
    }

    private static void dispatch(byte[] msg, Callbacks cb) {
        if (msg.length < 1) return;
        byte channel = msg[0];
        byte[] data = new byte[msg.length - 1];
        System.arraycopy(msg, 1, data, 0, data.length);
        switch (channel) {
            case WsProtocol.UI -> cb.onUi(new String(data, StandardCharsets.UTF_8));
            case WsProtocol.ASSET -> cb.onAsset(data);
            case WsProtocol.CTRL -> cb.onControl(ControlCodec.decodeJson(data));
            case WsProtocol.PATCH -> cb.onPatch(data);
            case WsProtocol.HUD -> cb.onHud(data);
            default -> { /* 未知下行频道：忽略 */ }
        }
    }

    private static byte[] helloBytes() {
        int v = WindUIProtocol.VERSION;
        return new byte[]{WsProtocol.HELLO,
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] prepend(byte channel, byte[] data) {
        byte[] out = new byte[data.length + 1];
        out[0] = channel;
        System.arraycopy(data, 0, out, 1, data.length);
        return out;
    }
}
