package org.windy.windui.client.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.windy.windui.client.hud.HudState;
import org.windy.windui.core.protocol.ControlCodec;
import org.windy.windui.core.protocol.EventCodec;
import org.windy.windui.core.protocol.WindUIProtocol;
import org.windy.windui.core.ws.WsConnection;
import org.windy.windui.core.ws.WsHandshake;
import org.windy.windui.core.ws.WsProtocol;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("WindUI");

    /** 单条消息上限（覆盖大图 asset），防 OOM。 */
    private static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

    /** TCP 建连超时。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    /** WebSocket HTTP 握手阶段读超时。鉴权完成并进入长期读循环后会清除。 */
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10000;

    /** 平台注入的入站回调（与旧 {@code WindUITransport.Callbacks} 同签名，glue 几乎不用改）。 */
    public interface Callbacks {
        void onUi(String json);

        void onAsset(byte[] framed);

        void onControl(String json);

        void onPatch(byte[] framed);

        void onHud(byte[] framed);
    }

    private static final Object LOCK = new Object();

    /** 当前已登记的有效连接。只允许其所属代次清理。 */
    private static volatile WsConnection conn;

    /** 当前连接线程。受 {@link #LOCK} 保护。 */
    private static Thread thread;

    /**
     * 连接代次。每次 connect/disconnect 都递增；旧线程即使在阻塞调用结束后继续运行，也无权登记或清理新连接。
     */
    private static long generation;

    private WsClient() {
    }

    /**
     * 连接服务端并开始读循环（非阻塞，另起 daemon 线程）。会先使任何已有连接失效。
     *
     * @param host  服务端地址（引导消息给出；为空由调用方回退为当前服务器地址）
     * @param port  WebSocket 端口
     * @param token 会话鉴权 token（引导消息给出）
     * @param cb    入站分派回调
     */
    public static void connect(String host, int port, String token, Callbacks cb) {
        Objects.requireNonNull(cb, "callbacks");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("WebSocket host 不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("非法 WebSocket 端口: " + port);
        }
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("WebSocket token 不能为空");
        }

        final long myGeneration;
        final Thread newThread;
        final Detached previous;
        synchronized (LOCK) {
            previous = detachCurrentLocked();
            myGeneration = generation;
            newThread = new Thread(
                    () -> run(myGeneration, host, port, token, cb),
                    "windui-ws-client-" + myGeneration);
            newThread.setDaemon(true);
            thread = newThread;
        }

        // 网络关闭可能阻塞，必须在全局状态锁外执行，避免卡住新连接登记和事件发送。
        stopDetached(previous);
        clearSessionState();
        newThread.start();
    }

    /** 发一次事件回程（按钮回调/输入回读）；已连接且发送成功返 true，否则 false（调用方走命令兜底）。 */
    public static boolean sendEvent(String eventId, List<String> data) {
        WsConnection current = conn;
        if (current == null) {
            return false;
        }
        try {
            current.send(prepend(WsProtocol.EVENT, EventCodec.encode(eventId, data)));
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[WindUI] WebSocket 事件发送失败，改走命令兜底: {}", e.getMessage());
            removeIfCurrent(current);
            return false;
        }
    }

    public static boolean isConnected() {
        WsConnection current = conn;
        return current != null && !current.isClosed();
    }

    /** 断开连接（登出/切服时调），并清理当前服务端所属的会话级客户端状态。 */
    public static void disconnect() {
        final Detached detached;
        synchronized (LOCK) {
            detached = detachCurrentLocked();
        }
        stopDetached(detached);
        clearSessionState();
    }

    // ===== 内部 =====

    /**
     * 使当前代次和连接失效，并把待关闭对象从共享状态中摘除。调用者必须持有 {@link #LOCK}。
     * generation 先递增，确保正在阻塞 connect/handshake 的旧线程恢复后无法重新登记。
     * 网络关闭由调用者在锁外执行。
     */
    private static Detached detachCurrentLocked() {
        generation++;

        WsConnection current = conn;
        Thread currentThread = thread;
        conn = null;
        thread = null;
        return new Detached(current, currentThread);
    }

    /** 在全局状态锁外停止被替换的连接和线程。 */
    private static void stopDetached(Detached detached) {
        if (detached == null) {
            return;
        }

        Thread oldThread = detached.thread();
        if (oldThread != null && oldThread != Thread.currentThread()) {
            oldThread.interrupt();
        }

        WsConnection oldConnection = detached.connection();
        if (oldConnection != null) {
            oldConnection.close();
        }
    }

    private static void run(long myGeneration, String host, int port, String token, Callbacks cb) {
        if (!isCurrentGeneration(myGeneration)) {
            return;
        }

        WsConnection created = null;
        boolean registered = false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);

            if (!isCurrentGeneration(myGeneration)) {
                return;
            }

            var in = socket.getInputStream();
            var out = socket.getOutputStream();

            String key = WsHandshake.writeRequest(out, host, port, "/windui");
            WsHandshake.readResponse(in, key);

            created = new WsConnection(in, out, true, MAX_MESSAGE_BYTES);

            // 鉴权 + 上报协议版本。登记为当前连接之前先完成协议初始化，避免半初始化连接被业务线程使用。
            created.send(prepend(WsProtocol.AUTH, token.getBytes(StandardCharsets.UTF_8)));
            created.send(helloBytes());

            synchronized (LOCK) {
                if (generation != myGeneration || thread != Thread.currentThread()) {
                    return;
                }
                conn = created;
                registered = true;
            }

            socket.setSoTimeout(0);
            LOGGER.info("[WindUI] WebSocket 已连接 ws://{}:{}", host, port);

            // 读循环：按频道字节分派。单个业务回调异常由 dispatch 隔离，不会杀死连接。
            while (!Thread.currentThread().isInterrupted() && isCurrentConnection(myGeneration, created)) {
                byte[] msg = created.recv();
                dispatch(msg, cb);
            }
        } catch (EOFException e) {
            if (isCurrentGeneration(myGeneration)) {
                LOGGER.info("[WindUI] WebSocket 已由远端关闭");
            }
        } catch (IOException e) {
            if (isCurrentGeneration(myGeneration) && !Thread.currentThread().isInterrupted()) {
                LOGGER.warn("[WindUI] WebSocket 连接/读写失败 ws://{}:{}: {}", host, port, e.getMessage());
            }
        } catch (RuntimeException e) {
            if (isCurrentGeneration(myGeneration)) {
                LOGGER.warn("[WindUI] WebSocket 协议处理失败 ws://{}:{}", host, port, e);
            }
        } finally {
            if (created != null) {
                created.close();
            }

            boolean endedCurrentSession = false;
            synchronized (LOCK) {
                // 只能清理自己登记的连接，绝不能把后续代次的新连接置空。
                if (generation == myGeneration) {
                    if (registered && conn == created) {
                        conn = null;
                        endedCurrentSession = true;
                    }
                    if (thread == Thread.currentThread()) {
                        thread = null;
                    }
                }
            }

            // 当前权威连接自然断开时清掉会话级 HUD；旧代次线程退出不得影响新会话。
            if (endedCurrentSession) {
                clearSessionState();
            }
        }
    }

    private static boolean isCurrentGeneration(long expected) {
        synchronized (LOCK) {
            return generation == expected;
        }
    }

    private static boolean isCurrentConnection(long expectedGeneration, WsConnection expectedConnection) {
        synchronized (LOCK) {
            return generation == expectedGeneration
                    && thread == Thread.currentThread()
                    && conn == expectedConnection;
        }
    }

    private static void removeIfCurrent(WsConnection failed) {
        final Detached detached;
        synchronized (LOCK) {
            if (conn != failed) {
                return;
            }
            detached = detachCurrentLocked();
        }
        stopDetached(detached);
        clearSessionState();
    }

    /** 清理仅属于当前服务器连接的客户端状态；内容哈希图片缓存允许跨会话复用，暂不在此清空。 */
    private static void clearSessionState() {
        HudState.clear();
    }

    private static void dispatch(byte[] msg, Callbacks cb) {
        if (msg.length < 1) {
            return;
        }

        byte channel = msg[0];
        byte[] data = new byte[msg.length - 1];
        System.arraycopy(msg, 1, data, 0, data.length);

        switch (channel) {
            case WsProtocol.UI -> invokeCallback(
                    "UI", () -> cb.onUi(new String(data, StandardCharsets.UTF_8)));
            case WsProtocol.ASSET -> invokeCallback(
                    "ASSET", () -> cb.onAsset(data));
            case WsProtocol.CTRL -> invokeCallback(
                    "CTRL", () -> cb.onControl(ControlCodec.decodeJson(data)));
            case WsProtocol.PATCH -> invokeCallback(
                    "PATCH", () -> cb.onPatch(data));
            case WsProtocol.HUD -> invokeCallback(
                    "HUD", () -> cb.onHud(data));
            default -> LOGGER.debug("[WindUI] 忽略未知 WebSocket 下行频道: {}", channel & 0xFF);
        }
    }

    /**
     * 隔离平台回调异常。坏资源或单次 UI 解析错误不应终止网络读循环，否则后续控制包和重推也无法到达。
     */
    private static void invokeCallback(String channel, Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            LOGGER.warn("[WindUI] WebSocket {} 回调处理失败，已跳过本条消息", channel, e);
        }
    }

    private static byte[] helloBytes() {
        int v = WindUIProtocol.VERSION;
        return new byte[]{
                WsProtocol.HELLO,
                (byte) (v >>> 24),
                (byte) (v >>> 16),
                (byte) (v >>> 8),
                (byte) v
        };
    }

    private static byte[] prepend(byte channel, byte[] data) {
        byte[] out = new byte[data.length + 1];
        out[0] = channel;
        System.arraycopy(data, 0, out, 1, data.length);
        return out;
    }

    /** 从共享状态摘下、等待在锁外停止的一组连接资源。 */
    private record Detached(WsConnection connection, Thread thread) {
    }
}
