package org.windy.windui.server.ws;

import org.windy.windui.core.ws.WsConnection;
import org.windy.windui.core.ws.WsHandshake;
import org.windy.windui.core.ws.WsProtocol;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
 * 线程模型：一条 accept 线程 + 每连接一条虚拟线程（daemon 语义的 Loom 阻塞 IO）。
 * token→UUID 的校验由平台经 {@link Handler#authenticate} 提供。
 */
public final class WsServer {

    /** HTTP Upgrade + 首帧 AUTH 的总等待上限，防慢连接长期占用文件描述符。 */
    private static final int AUTH_TIMEOUT_MILLIS = 10000;

    /** WindUI 固定 WebSocket 路径。 */
    private static final String WS_PATH = "/windui";

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
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("非法 WebSocket 端口: " + port);
        }
        if (maxMessageBytes < 64) {
            throw new IllegalArgumentException("maxMessageBytes 过小: " + maxMessageBytes);
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为空");
        }
        this.bindHost = bindHost;
        this.port = port;
        this.maxMessageBytes = maxMessageBytes;
        this.handler = handler;
    }

    /** 启动监听（非阻塞，另起 accept 线程）。 */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        InetAddress address = (bindHost == null || bindHost.isEmpty())
                ? null
                : InetAddress.getByName(bindHost);

        ServerSocket created = new ServerSocket(port, 128, address);
        serverSocket = created;
        boundPort = created.getLocalPort();
        running = true;

        Thread acceptThread = new Thread(this::acceptLoop, "windui-ws-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** 关闭监听并断开所有会话。 */
    public synchronized void stop() {
        running = false;

        ServerSocket currentServerSocket = serverSocket;
        serverSocket = null;
        if (currentServerSocket != null) {
            try {
                currentServerSocket.close();
            } catch (IOException ignored) {
            }
        }

        for (WsConnection connection : sessions.values()) {
            connection.close();
        }
        sessions.clear();
        boundPort = 0;
    }

    public boolean isConnected(UUID player) {
        WsConnection connection = sessions.get(player);
        return connection != null && !connection.isClosed();
    }

    /** 实际绑定端口（配置为 0 自动选端口时返回真实端口；未启动前返回配置值）。 */
    public int port() {
        return boundPort > 0 ? boundPort : port;
    }

    /** 给某玩家下发一条应用消息（channel 字节 + data）。无连接/发送失败则静默丢弃。 */
    public void send(UUID player, byte channel, byte[] data) {
        if (player == null || data == null) {
            return;
        }

        WsConnection connection = sessions.get(player);
        if (connection == null) {
            return;
        }

        byte[] message = new byte[data.length + 1];
        message[0] = channel;
        System.arraycopy(data, 0, message, 1, data.length);

        try {
            connection.send(message);
        } catch (IOException | RuntimeException e) {
            dropSession(player, connection);
        }
    }

    // ===== 内部 =====

    private void acceptLoop() {
        while (running) {
            ServerSocket current = serverSocket;
            if (current == null) {
                break;
            }

            final Socket socket;
            try {
                socket = current.accept();
            } catch (IOException e) {
                if (running) {
                    continue;
                }
                break;
            }

            // 每连接一条虚拟线程（Java 21 Loom）：保留极简阻塞式 IO，却能承载大量连接。
            Thread.ofVirtual()
                    .name("windui-ws-conn-" + threadSeq.incrementAndGet())
                    .start(() -> handleConnection(socket));
        }
    }

    private void handleConnection(Socket socket) {
        UUID player = null;
        WsConnection connection = null;

        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(AUTH_TIMEOUT_MILLIS);

            var input = socket.getInputStream();
            var output = socket.getOutputStream();

            Map<String, String> headers = WsHandshake.readRequest(input);
            if (headers == null || !isExpectedPath(headers.get(""))) {
                socket.close();
                return;
            }

            WsHandshake.writeAcceptResponse(output, headers.get("sec-websocket-key"));
            connection = new WsConnection(input, output, false, maxMessageBytes);

            // 首帧必须是 AUTH，且 token 不能为空。
            byte[] first = connection.recv();
            if (first.length < 2 || first[0] != WsProtocol.AUTH) {
                connection.close();
                return;
            }

            String token = new String(first, 1, first.length - 1, StandardCharsets.UTF_8);
            if (token.isBlank()) {
                connection.close();
                return;
            }

            player = handler.authenticate(token);
            if (player == null) {
                connection.close();
                return;
            }

            // 鉴权完成，切回长期阻塞读。心跳/存活检测可在后续独立实现。
            socket.setSoTimeout(0);

            // 同玩家重连：先把新连接登记为权威会话，再关闭旧连接。
            // 旧连接线程 finally 使用 remove(player, old)，不会误删这条新连接。
            WsConnection old = sessions.put(player, connection);
            if (old != null && old != connection) {
                old.close();
            }

            while (running && sessions.get(player) == connection) {
                byte[] message = connection.recv();
                if (message.length < 1) {
                    continue;
                }
                dispatch(player, message);
            }
        } catch (SocketTimeoutException e) {
            // HTTP Upgrade 或 AUTH 超时：直接清理未鉴权连接。
        } catch (IOException | RuntimeException e) {
            // EOF、协议错误、网络断开或平台回调异常均在 finally 统一清理。
        } finally {
            if (player != null && connection != null) {
                dropSession(player, connection);
            } else if (connection != null) {
                connection.close();
            } else {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void dispatch(UUID player, byte[] message) {
        byte channel = message[0];
        int payloadLength = message.length - 1;

        switch (channel) {
            case WsProtocol.HELLO -> {
                // HELLO 必须恰好携带 4 字节大端版本号；畸形包忽略，不把错误版本登记为 v0。
                if (payloadLength != 4) {
                    return;
                }
                int version = ((message[1] & 0xFF) << 24)
                        | ((message[2] & 0xFF) << 16)
                        | ((message[3] & 0xFF) << 8)
                        | (message[4] & 0xFF);
                handler.onHello(player, version);
            }
            case WsProtocol.EVENT -> {
                if (payloadLength <= 0) {
                    return;
                }
                byte[] data = new byte[payloadLength];
                System.arraycopy(message, 1, data, 0, payloadLength);
                handler.onEvent(player, data);
            }
            default -> {
                // 未知上行频道：忽略，保持前向兼容。
            }
        }
    }

    /** 断开并清理某玩家会话（仅当当前登记的正是这条连接时）。 */
    private void dropSession(UUID player, WsConnection connection) {
        boolean removed = sessions.remove(player, connection);
        connection.close();
        if (removed) {
            handler.onDisconnect(player);
        }
    }

    private static boolean isExpectedPath(String requestLine) {
        if (requestLine == null) {
            return false;
        }
        String prefix = "GET " + WS_PATH + " ";
        return requestLine.startsWith(prefix);
    }
}
