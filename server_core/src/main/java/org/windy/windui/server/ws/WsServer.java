package org.windy.windui.server.ws;

import org.windy.windui.core.ws.WsConnection;
import org.windy.windui.core.ws.WsHandshake;
import org.windy.windui.core.ws.WsProtocol;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WindUI 的 WebSocket 服务端（<b>平台无关</b>，放 server_core 供 velocity / 未来 bukkit 复用）。
 * <p>
 * 取代旧的裸 Netty 频道：在独立端口上跑，客户端直连、首帧发 token 鉴权 → 绑定玩家 UUID → 之后
 * UI/asset/ctrl/patch/hud 全走这条连接下发，事件/hello 经此上行。完全脱离 MC 封包管线，杜绝那类断线。
 * <p>
 * 线程模型：一条 accept 线程 + 每连接一条读线程（daemon 阻塞 IO）。私服体量足够；海量在线可后续换 NIO。
 * token→UUID 的校验由平台经 {@link Handler#authenticate} 提供（Velocity 在玩家上线时签发 token）。
 */
public final class WsServer {

    /** 平台注入的回调：鉴权 + 上行事件分派。 */
    public interface Handler {
        /** 校验 token；通过返玩家 UUID，失败返 {@code null}（连接将被关闭）。 */
        UUID authenticate(String token);

        /** 客户端上报协议版本。 */
        void onHello(UUID player, int version);

        /** 界面事件回程（{@link org.windy.windui.core.protocol.EventCodec} 字节）。 */
        void onEvent(UUID player, byte[] eventBytes);

        /** 该玩家的 WS 连接断开。 */
        void onDisconnect(UUID player);
    }

    private final String bindHost;
    private final int port;
    private final int maxMessageBytes;
    private final Handler handler;

    private final Map<UUID, WsConnection> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();
    private volatile ServerSocket serverSocket;
    private volatile int boundPort;
    private volatile boolean running;

    /**
     * @param bindHost        监听地址（{@code ""}/null = 所有网卡；{@code "127.0.0.1"} = 仅本机）
     * @param port            监听端口
     * @param maxMessageBytes 单条消息上限（防 OOM，如 8MB 覆盖大图）
     */
    public WsServer(String bindHost, int port, int maxMessageBytes, Handler handler) {
        this.bindHost = bindHost;
        this.port = port;
        this.maxMessageBytes = maxMessageBytes;
        this.handler = handler;
    }

    /** 启动监听（非阻塞，另起 accept 线程）。 */
    public synchronized void start() throws IOException {
        if (running) return;
        InetAddress addr = (bindHost == null || bindHost.isEmpty())
                ? null : InetAddress.getByName(bindHost);
        serverSocket = new ServerSocket(port, 128, addr);
        boundPort = serverSocket.getLocalPort();
        running = true;
        Thread accept = new Thread(this::acceptLoop, "windui-ws-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** 关闭监听并断开所有会话。 */
    public synchronized void stop() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        for (WsConnection c : sessions.values()) c.close();
        sessions.clear();
    }

    public boolean isConnected(UUID player) {
        return sessions.containsKey(player);
    }

    /** 实际绑定端口（配置为 0 自动选端口时返回真实端口；未启动前返回配置值）。 */
    public int port() {
        return boundPort > 0 ? boundPort : port;
    }

    /** 给某玩家下发一条应用消息（channel 字节 + data）。无连接/发送失败则静默丢弃。 */
    public void send(UUID player, byte channel, byte[] data) {
        WsConnection conn = sessions.get(player);
        if (conn == null) return;
        byte[] msg = new byte[data.length + 1];
        msg[0] = channel;
        System.arraycopy(data, 0, msg, 1, data.length);
        try {
            conn.send(msg);
        } catch (IOException e) {
            dropSession(player, conn);
        }
    }

    // ===== 内部 =====

    private void acceptLoop() {
        while (running) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running) continue;
                break; // stop() 关了 socket
            }
            // 每连接一条虚拟线程（Java 21 Loom）：保留极简阻塞式 IO，却能扛上万并发连接，
            // 内存/调度开销远低于平台线程。虚拟线程本就不阻止 JVM 退出，无需 setDaemon。
            Thread.ofVirtual()
                    .name("windui-ws-conn-" + threadSeq.incrementAndGet())
                    .start(() -> handleConnection(socket));
        }
    }

    private void handleConnection(Socket socket) {
        UUID player = null;
        WsConnection conn = null;
        try {
            socket.setTcpNoDelay(true);
            var in = socket.getInputStream();
            var out = socket.getOutputStream();

            var headers = WsHandshake.readRequest(in);
            if (headers == null) {
                socket.close();
                return; // 非合法 WebSocket 升级
            }
            WsHandshake.writeAcceptResponse(out, headers.get("sec-websocket-key"));
            conn = new WsConnection(in, out, false, maxMessageBytes); // 服务端出站不加掩码

            // 首帧必须是 AUTH
            byte[] first = conn.recv();
            if (first.length < 1 || first[0] != WsProtocol.AUTH) {
                conn.close();
                return;
            }
            String token = new String(first, 1, first.length - 1, StandardCharsets.UTF_8);
            player = handler.authenticate(token);
            if (player == null) {
                conn.close();
                return; // 鉴权失败
            }

            // 绑定会话（同玩家重连：踢掉旧连接）
            WsConnection old = sessions.put(player, conn);
            if (old != null && old != conn) old.close();

            // 读循环
            while (running) {
                byte[] msg = conn.recv();
                if (msg.length < 1) continue;
                dispatch(player, msg);
            }
        } catch (IOException e) {
            // EOF / 断线 —— 正常收尾
        } finally {
            if (player != null && conn != null) dropSession(player, conn);
            else if (conn != null) conn.close();
        }
    }

    private void dispatch(UUID player, byte[] msg) {
        byte channel = msg[0];
        int len = msg.length - 1;
        switch (channel) {
            case WsProtocol.HELLO -> {
                int version = 0;
                if (len >= 4) {
                    version = ((msg[1] & 0xFF) << 24) | ((msg[2] & 0xFF) << 16)
                            | ((msg[3] & 0xFF) << 8) | (msg[4] & 0xFF);
                }
                handler.onHello(player, version);
            }
            case WsProtocol.EVENT -> {
                byte[] data = new byte[len];
                System.arraycopy(msg, 1, data, 0, len);
                handler.onEvent(player, data);
            }
            default -> { /* 未知上行频道：忽略 */ }
        }
    }

    /** 断开并清理某玩家会话（仅当当前登记的正是这条连接时）。 */
    private void dropSession(UUID player, WsConnection conn) {
        boolean removed = sessions.remove(player, conn);
        conn.close();
        if (removed) handler.onDisconnect(player);
    }
}
