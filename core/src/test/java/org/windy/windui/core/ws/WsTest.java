package org.windy.windui.core.ws;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
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

        WsConnection client = new WsConnection(s2cIn, c2sOut, true, 1 << 20);
        WsConnection server = new WsConnection(c2sIn, s2cOut, false, 1 << 20);

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

        // 服务端出站不掩码；接收端按客户端角色构造，因此要求入站不掩码。
        WsConnection sender = new WsConnection(new PipedInputStream(), out, false, 1 << 22);
        WsConnection receiver = new WsConnection(in, new PipedOutputStream(), true, 1 << 22);

        byte[] big = new byte[200_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }

        Thread writer = new Thread(() -> {
            try {
                sender.send(big);
            } catch (Exception ignored) {
            }
        });
        writer.start();

        assertArrayEquals(big, receiver.recv());
        writer.join(3000);
    }

    /** 对端 close 帧使 recv 抛 EOFException。 */
    @Test
    void closeSignalsEof() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 1 << 16);
        WsConnection client = new WsConnection(new PipedInputStream(), out, true, 1 << 20);
        WsConnection server = new WsConnection(in, new ByteArrayOutputStream(), false, 1 << 20);

        client.close();
        assertThrows(EOFException.class, server::recv);
    }

    /** 服务端必须拒绝未加掩码的客户端帧。 */
    @Test
    void serverRejectsUnmaskedClientFrame() {
        byte[] unmaskedBinary = new byte[]{
                (byte) 0x82,
                0x01,
                0x55
        };
        WsConnection server = new WsConnection(
                new ByteArrayInputStream(unmaskedBinary),
                new ByteArrayOutputStream(),
                false,
                1024);

        assertThrows(IOException.class, server::recv);
    }

    /** 客户端必须拒绝错误地带掩码的服务端帧。 */
    @Test
    void clientRejectsMaskedServerFrame() {
        byte[] maskedBinary = new byte[]{
                (byte) 0x82,
                (byte) 0x81,
                1, 2, 3, 4,
                (byte) (0x55 ^ 1)
        };
        WsConnection client = new WsConnection(
                new ByteArrayInputStream(maskedBinary),
                new ByteArrayOutputStream(),
                true,
                1024);

        assertThrows(IOException.class, client::recv);
    }

    /** 分片消息的累计大小必须受 maxMessageBytes 限制，而不只是限制每个单帧。 */
    @Test
    void fragmentedMessageTotalSizeIsLimited() {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        // 服务端发往客户端，不加掩码：首个 binary 分片 4 字节，续帧再 4 字节，总计超过上限 6。
        wire.write(0x02);
        wire.write(0x04);
        wire.writeBytes(new byte[]{1, 2, 3, 4});
        wire.write(0x80);
        wire.write(0x04);
        wire.writeBytes(new byte[]{5, 6, 7, 8});

        WsConnection client = new WsConnection(
                new ByteArrayInputStream(wire.toByteArray()),
                new ByteArrayOutputStream(),
                true,
                6);

        assertThrows(IOException.class, client::recv);
    }

    /** 非最短长度编码必须拒绝，避免协议歧义。 */
    @Test
    void rejectsNonMinimalExtendedLengthEncoding() {
        byte[] malformed = new byte[]{
                (byte) 0x82,
                126,
                0,
                1,
                0x55
        };
        WsConnection client = new WsConnection(
                new ByteArrayInputStream(malformed),
                new ByteArrayOutputStream(),
                true,
                1024);

        assertThrows(IOException.class, client::recv);
    }

    /** 端到端穿越真实回环 socket + 完整握手（服务端 accept + 客户端 upgrade）。 */
    @Test
    void handshakeOverLoopbackSocket() throws Exception {
        try (java.net.ServerSocket serverSocket = new java.net.ServerSocket(
                0, 1, java.net.InetAddress.getLoopbackAddress())) {
            int port = serverSocket.getLocalPort();
            ArrayBlockingQueue<byte[]> received = new ArrayBlockingQueue<>(1);

            Thread serverThread = new Thread(() -> {
                try (java.net.Socket socket = serverSocket.accept()) {
                    var headers = WsHandshake.readRequest(socket.getInputStream());
                    WsHandshake.writeAcceptResponse(
                            socket.getOutputStream(), headers.get("sec-websocket-key"));
                    WsConnection connection = new WsConnection(
                            socket.getInputStream(), socket.getOutputStream(), false, 1 << 20);
                    received.offer(connection.recv());
                } catch (Exception ignored) {
                }
            });
            serverThread.start();

            try (java.net.Socket socket = new java.net.Socket(
                    java.net.InetAddress.getLoopbackAddress(), port)) {
                String key = WsHandshake.writeRequest(
                        socket.getOutputStream(), "localhost", port, "/windui");
                WsHandshake.readResponse(socket.getInputStream(), key);
                WsConnection connection = new WsConnection(
                        socket.getInputStream(), socket.getOutputStream(), true, 1 << 20);
                connection.send("hello-loopback".getBytes(StandardCharsets.UTF_8));

                byte[] got = received.poll(3, java.util.concurrent.TimeUnit.SECONDS);
                assertArrayEquals("hello-loopback".getBytes(StandardCharsets.UTF_8), got);
            }

            serverThread.join(3000);
        }
    }
}
