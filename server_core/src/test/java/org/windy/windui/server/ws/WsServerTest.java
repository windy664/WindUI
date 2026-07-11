package org.windy.windui.server.ws;

import org.junit.jupiter.api.Test;
import org.windy.windui.core.protocol.EventCodec;
import org.windy.windui.core.ws.WsConnection;
import org.windy.windui.core.ws.WsHandshake;
import org.windy.windui.core.ws.WsProtocol;

import java.io.EOFException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsServerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** 只放行 token "good"，映射到固定玩家；记录 hello/event/disconnect。 */
    private static class RecordingHandler implements WsServer.Handler {
        final BlockingQueue<Integer> hellos = new ArrayBlockingQueue<>(4);
        final BlockingQueue<EventCodec.Event> events = new ArrayBlockingQueue<>(4);
        final BlockingQueue<UUID> disconnects = new ArrayBlockingQueue<>(4);

        @Override public UUID authenticate(String token) {
            return "good".equals(token) ? PLAYER : null;
        }
        @Override public void onHello(UUID player, int version) {
            hellos.offer(version);
        }
        @Override public void onEvent(UUID player, byte[] eventBytes) {
            events.offer(EventCodec.decode(eventBytes));
        }
        @Override public void onDisconnect(UUID player) {
            disconnects.offer(player);
        }
    }

    /** 帮客户端做握手 + 鉴权，返回一条已就绪、加掩码的连接。 */
    private static WsConnection connectAndAuth(int port, String token) throws Exception {
        return connectAndAuthBytes(port, token.getBytes(StandardCharsets.UTF_8));
    }

    /** 帮客户端做握手并发送原始 AUTH 字节，用于验证协议边界。 */
    private static WsConnection connectAndAuthBytes(int port, byte[] token) throws Exception {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
        s.setTcpNoDelay(true);
        String key = WsHandshake.writeRequest(s.getOutputStream(), "localhost", port, "/windui");
        WsHandshake.readResponse(s.getInputStream(), key);
        WsConnection conn = new WsConnection(s.getInputStream(), s.getOutputStream(), true, 1 << 20);
        byte[] auth = new byte[token.length + 1];
        auth[0] = WsProtocol.AUTH;
        System.arraycopy(token, 0, auth, 1, token.length);
        conn.send(auth);
        return conn;
    }

    private static void sendHello(WsConnection connection, int version) throws Exception {
        connection.send(new byte[]{
                WsProtocol.HELLO,
                (byte) (version >>> 24),
                (byte) (version >>> 16),
                (byte) (version >>> 8),
                (byte) version});
    }

    @Test
    void authThenHelloEventAndDownstream() throws Exception {
        RecordingHandler h = new RecordingHandler();
        WsServer server = new WsServer("127.0.0.1", 0, 1 << 20, h);
        server.start();
        try {
            int port = server.port();
            WsConnection conn = connectAndAuth(port, "good");

            // hello（4 字节大端 version=3）
            sendHello(conn, 3);
            assertEquals(3, h.hellos.poll(3, TimeUnit.SECONDS));

            // 等会话登记完成
            for (int i = 0; i < 50 && !server.isConnected(PLAYER); i++) Thread.sleep(20);
            assertTrue(server.isConnected(PLAYER));

            // event 回程
            byte[] ev = EventCodec.encode("buy_diamond", List.of("1"));
            byte[] evMsg = new byte[ev.length + 1];
            evMsg[0] = WsProtocol.EVENT;
            System.arraycopy(ev, 0, evMsg, 1, ev.length);
            conn.send(evMsg);
            EventCodec.Event got = h.events.poll(3, TimeUnit.SECONDS);
            assertEquals("buy_diamond", got.eventId());
            assertEquals(List.of("1"), got.data());

            // 下行：服务端推 UI JSON
            byte[] json = "{\"type\":\"TextView\"}".getBytes(StandardCharsets.UTF_8);
            server.send(PLAYER, WsProtocol.UI, json);
            byte[] down = conn.recv();
            assertEquals(WsProtocol.UI, down[0]);
            byte[] body = new byte[down.length - 1];
            System.arraycopy(down, 1, body, 0, body.length);
            assertArrayEquals(json, body);

            conn.close();
        } finally {
            server.stop();
        }
    }

    @Test
    void badTokenIsRejected() throws Exception {
        RecordingHandler h = new RecordingHandler();
        WsServer server = new WsServer("127.0.0.1", 0, 1 << 20, h);
        server.start();
        try {
            WsConnection conn = connectAndAuth(server.port(), "bad");
            // 服务端应关闭连接 → 客户端下一次 recv 抛 EOF
            assertThrows(EOFException.class, conn::recv);
            assertTrue(!server.isConnected(PLAYER));
        } finally {
            server.stop();
        }
    }

    @Test
    void malformedUtf8AuthIsRejectedBeforePlatformAuthentication() throws Exception {
        RecordingHandler handler = new RecordingHandler() {
            @Override public UUID authenticate(String token) {
                return PLAYER;
            }
        };
        WsServer server = new WsServer("127.0.0.1", 0, 1 << 20, handler);
        server.start();
        try {
            WsConnection connection = connectAndAuthBytes(
                    server.port(),
                    new byte[]{(byte) 0xC3, 0x28});
            assertThrows(EOFException.class, connection::recv);
            assertTrue(!server.isConnected(PLAYER));
        } finally {
            server.stop();
        }
    }

    @Test
    void oversizedAuthIsRejectedBeforePlatformAuthentication() throws Exception {
        RecordingHandler handler = new RecordingHandler() {
            @Override public UUID authenticate(String token) {
                return PLAYER;
            }
        };
        WsServer server = new WsServer("127.0.0.1", 0, 1 << 20, handler);
        server.start();
        try {
            WsConnection connection = connectAndAuthBytes(
                    server.port(),
                    new byte[513]);
            assertThrows(EOFException.class, connection::recv);
            assertTrue(!server.isConnected(PLAYER));
        } finally {
            server.stop();
        }
    }

    @Test
    void helloIsRequiredBeforeSessionBecomesReady() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WsServer server = new WsServer("127.0.0.1", 0, 1 << 20, handler);
        server.start();
        try {
            WsConnection connection = connectAndAuth(server.port(), "good");
            connection.send(new byte[]{WsProtocol.EVENT, 1});

            assertThrows(EOFException.class, connection::recv);
            assertTrue(!server.isConnected(PLAYER));
            assertTrue(handler.hellos.isEmpty());
            assertTrue(handler.disconnects.isEmpty());
        } finally {
            server.stop();
        }
    }

    @Test
    void explicitDisconnectClosesCurrentPlayerSession() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WsServer server = new WsServer("127.0.0.1", 0, 1 << 20, handler);
        server.start();
        try {
            WsConnection connection = connectAndAuth(server.port(), "good");
            sendHello(connection, 3);
            for (int i = 0; i < 50 && !server.isConnected(PLAYER); i++) Thread.sleep(20);
            assertTrue(server.isConnected(PLAYER));

            server.disconnect(PLAYER);

            assertThrows(EOFException.class, connection::recv);
            assertTrue(!server.isConnected(PLAYER));
            assertEquals(PLAYER, handler.disconnects.poll(3, TimeUnit.SECONDS));
        } finally {
            server.stop();
        }
    }
}
