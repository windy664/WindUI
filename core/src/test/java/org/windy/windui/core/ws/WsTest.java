package org.windy.windui.core.ws;

import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WsTest {

    /** RFC 6455 §1.3 的标准握手向量。 */
    @Test
    void acceptKeyMatchesRfcVector() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                WsHandshake.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    /** 客户端加掩码发、服务端不加掩码发；一发一收往返，两侧都能正确解帧。 */
    @Test
    void framesRoundTripThroughPipes() throws Exception {
        // 客户端 --clientToServer--> 服务端；服务端 --serverToClient--> 客户端
        PipedOutputStream c2sOut = new PipedOutputStream();
        PipedInputStream c2sIn = new PipedInputStream(c2sOut, 1 << 16);
        PipedOutputStream s2cOut = new PipedOutputStream();
        PipedInputStream s2cIn = new PipedInputStream(s2cOut, 1 << 16);

        WsConnection client = new WsConnection(s2cIn, c2sOut, true, 1 << 20);   // 加掩码
        WsConnection server = new WsConnection(c2sIn, s2cOut, false, 1 << 20);  // 不加掩码

        byte[] up = "上行：你好服务端".getBytes(StandardCharsets.UTF_8);
        client.send(up);
        assertArrayEquals(up, server.recv());

        byte[] down = new byte[]{0x01, 'U', 'I', 0, 1, 2, 3};
        server.send(down);
        assertArrayEquals(down, client.recv());
    }

    /** 大于单字节长度编码阈值的负载（走 16 位/64 位扩展长度）也能往返。 */
    @Test
    void largePayloadRoundTrips() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 1 << 20);
        WsConnection sender = new WsConnection(new PipedInputStream(), out, false, 1 << 22);
        WsConnection receiver = new WsConnection(in, new PipedOutputStream(), false, 1 << 22);

        byte[] big = new byte[200_000]; // > 0xFFFF，触发 64 位长度
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        // 在独立线程发，避免管线缓冲区写满阻塞
        new Thread(() -> {
            try {
                sender.send(big);
            } catch (Exception ignored) {
            }
        }).start();
        assertArrayEquals(big, receiver.recv());
    }

    /** 对端 close 帧使 recv 抛 EOFException。 */
    @Test
    void closeSignalsEof() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 1 << 16);
        WsConnection a = new WsConnection(new PipedInputStream(), out, true, 1 << 20);
        WsConnection b = new WsConnection(in, new PipedOutputStream(), false, 1 << 20);
        a.close(); // 发 close 帧
        assertThrows(EOFException.class, b::recv);
    }

    /** 端到端穿越真实回环 socket + 完整握手（服务端 accept + 客户端 upgrade）。 */
    @Test
    void handshakeOverLoopbackSocket() throws Exception {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            int port = ss.getLocalPort();
            ArrayBlockingQueue<byte[]> received = new ArrayBlockingQueue<>(1);

            Thread serverThread = new Thread(() -> {
                try (java.net.Socket s = ss.accept()) {
                    var hdrs = WsHandshake.readRequest(s.getInputStream());
                    WsHandshake.writeAcceptResponse(s.getOutputStream(), hdrs.get("sec-websocket-key"));
                    WsConnection conn = new WsConnection(s.getInputStream(), s.getOutputStream(), false, 1 << 20);
                    received.offer(conn.recv());
                } catch (Exception ignored) {
                }
            });
            serverThread.start();

            try (java.net.Socket s = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
                String key = WsHandshake.writeRequest(s.getOutputStream(), "localhost", port, "/windui");
                WsHandshake.readResponse(s.getInputStream(), key);
                WsConnection conn = new WsConnection(s.getInputStream(), s.getOutputStream(), true, 1 << 20);
                conn.send("hello-loopback".getBytes(StandardCharsets.UTF_8));
                byte[] got = received.poll(3, java.util.concurrent.TimeUnit.SECONDS);
                assertArrayEquals("hello-loopback".getBytes(StandardCharsets.UTF_8), got);
            }
        }
    }
}
