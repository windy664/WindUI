package org.windy.windui.core.ws;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一条已完成握手的 WebSocket 连接（RFC 6455 帧编解码，纯 java.io 阻塞流）。
 * <p>
 * 只暴露“发一条二进制消息 / 收一条数据消息”，内部处理分片重组、ping→pong、close。控制帧对上层透明。
 * {@code maskOutbound} 决定出站是否加掩码：按 RFC，<b>客户端→服务端必须加掩码，服务端→客户端不得加</b>，
 * 故客户端侧传 {@code true}、服务端侧传 {@code false}。入站方向会据此严格校验：
 * 服务端必须收到带掩码帧，客户端必须收到不带掩码帧。
 * <p>
 * 线程模型：单读线程调 {@link #recv()}；{@link #send} 可被任意线程调（服务端渲染线程推 UI）。写加锁互斥。
 */
public final class WsConnection implements AutoCloseable {

    private static final int OP_CONT = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final InputStream in;
    private final OutputStream out;
    private final boolean maskOutbound;
    private final int maxMessageBytes;

    // 用 ReentrantLock 而非 synchronized：虚拟线程（Loom）下若写操作阻塞（慢客户端 send 缓冲满），
    // synchronized 会钉住载体线程；ReentrantLock 则挂起虚拟线程、释放载体。
    private final ReentrantLock writeLock = new ReentrantLock();
    private final SecureRandom random = new SecureRandom();

    private volatile boolean closed;
    private volatile boolean closeSent;

    public WsConnection(InputStream in, OutputStream out, boolean maskOutbound, int maxMessageBytes) {
        if (in == null || out == null) {
            throw new IllegalArgumentException("WebSocket 输入输出流不能为空");
        }
        if (maxMessageBytes < 1) {
            throw new IllegalArgumentException("maxMessageBytes 必须大于 0");
        }
        this.in = in;
        this.out = out;
        this.maskOutbound = maskOutbound;
        this.maxMessageBytes = maxMessageBytes;
    }

    /** 发一条应用消息（二进制帧，单帧不分片）。 */
    public void send(byte[] payload) throws IOException {
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        guardSize(payload.length);
        writeFrame(OP_BINARY, payload);
    }

    /**
     * 收下一条数据消息（文本/二进制，透明重组分片）；期间自动回应 ping、忽略 pong。
     * 对端 close 或流结束抛 {@link EOFException}。
     */
    public byte[] recv() throws IOException {
        if (closed) {
            throw new EOFException("WebSocket 已关闭");
        }

        ByteArrayOutputStream message = null;
        while (true) {
            Frame frame = readFrame();
            switch (frame.opcode) {
                case OP_PING -> writeFrame(OP_PONG, frame.payload);
                case OP_PONG -> {
                    // pong 对应用层透明
                }
                case OP_CLOSE -> {
                    sendCloseReply(frame.payload);
                    closed = true;
                    throw new EOFException("对端关闭 WebSocket");
                }
                case OP_TEXT, OP_BINARY -> {
                    if (message != null) {
                        throw new IOException("上一条分片消息尚未结束时收到新的数据帧");
                    }
                    if (frame.fin) {
                        return frame.payload;
                    }

                    int initialCapacity = Math.min(
                            maxMessageBytes, Math.max(32, frame.payload.length));
                    message = new ByteArrayOutputStream(initialCapacity);
                    appendFragment(message, frame.payload);
                }
                case OP_CONT -> {
                    if (message == null) {
                        throw new IOException("意外的 WebSocket 续帧");
                    }
                    appendFragment(message, frame.payload);
                    if (frame.fin) {
                        return message.toByteArray();
                    }
                }
                default -> throw new IOException("未知 WebSocket opcode: " + frame.opcode);
            }
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * 发起规范关闭并释放流。Close 帧必须在 {@code closed=true} 之前写出。
     * 并发或重复调用是幂等的，不会重复发送 Close 帧。
     */
    @Override
    public void close() {
        writeLock.lock();
        try {
            if (!closed && !closeSent) {
                try {
                    writeFrameLocked(OP_CLOSE, new byte[0]);
                    closeSent = true;
                } catch (IOException ignored) {
                    // 对端可能已经断开；仍继续关闭本地资源
                }
            }
            closed = true;
        } finally {
            writeLock.unlock();
        }

        closeStreams();
    }

    // ===== 帧编解码 =====

    private record Frame(boolean fin, int opcode, byte[] payload) {
    }

    private Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            throw new EOFException("WebSocket 流结束");
        }

        if ((b0 & 0x70) != 0) {
            throw new IOException("不支持带 RSV 扩展位的 WebSocket 帧");
        }

        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean control = opcode >= 0x8;

        if (!isKnownOpcode(opcode)) {
            throw new IOException("未知 WebSocket opcode: " + opcode);
        }

        int b1 = readByte();
        boolean masked = (b1 & 0x80) != 0;

        // 本端出站加掩码说明本端是客户端，因此入站服务端帧不得掩码；
        // 本端出站不加掩码说明本端是服务端，因此入站客户端帧必须掩码。
        boolean expectedMasked = !maskOutbound;
        if (masked != expectedMasked) {
            throw new IOException(expectedMasked
                    ? "客户端发往服务端的 WebSocket 帧必须带掩码"
                    : "服务端发往客户端的 WebSocket 帧不得带掩码");
        }

        int lengthCode = b1 & 0x7F;
        long length;
        if (lengthCode < 126) {
            length = lengthCode;
        } else if (lengthCode == 126) {
            length = ((long) readByte() << 8) | readByte();
            if (length < 126) {
                throw new IOException("WebSocket 负载使用了非最短 16 位长度编码");
            }
        } else {
            int firstLengthByte = readByte();
            if ((firstLengthByte & 0x80) != 0) {
                throw new IOException("WebSocket 64 位负载长度最高位不得为 1");
            }
            length = firstLengthByte;
            for (int i = 1; i < 8; i++) {
                length = (length << 8) | readByte();
            }
            if (length <= 0xFFFFL) {
                throw new IOException("WebSocket 负载使用了非最短 64 位长度编码");
            }
        }

        if (control) {
            if (!fin) {
                throw new IOException("WebSocket 控制帧不得分片");
            }
            if (length > 125) {
                throw new IOException("WebSocket 控制帧负载过大: " + length);
            }
            if (opcode == OP_CLOSE && length == 1) {
                throw new IOException("WebSocket Close 帧负载长度不得为 1");
            }
        }

        if (length > maxMessageBytes) {
            throw new IOException("WebSocket 帧过大: " + length);
        }

        byte[] mask = new byte[4];
        if (masked) {
            readFully(mask);
        }

        byte[] payload = new byte[(int) length];
        readFully(payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }

        return new Frame(fin, opcode, payload);
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        writeLock.lock();
        try {
            if (closed) {
                throw new IOException("WebSocket 已关闭");
            }
            writeFrameLocked(opcode, payload);
            if (opcode == OP_CLOSE) {
                closeSent = true;
            }
        } finally {
            writeLock.unlock();
        }
    }

    /** 调用者必须持有 {@link #writeLock}。 */
    private void writeFrameLocked(int opcode, byte[] payload) throws IOException {
        int length = payload.length;
        out.write(0x80 | opcode);

        int maskBit = maskOutbound ? 0x80 : 0x00;
        if (length < 126) {
            out.write(maskBit | length);
        } else if (length <= 0xFFFF) {
            out.write(maskBit | 126);
            out.write((length >>> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(maskBit | 127);
            long longLength = length;
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((longLength >>> (8 * i)) & 0xFF));
            }
        }

        if (maskOutbound) {
            byte[] key = new byte[4];
            random.nextBytes(key);
            out.write(key);

            // 避免为大图再分配一份完整 masked 数组，按块异或写出，降低峰值内存。
            byte[] chunk = new byte[Math.min(8192, Math.max(1, length))];
            int offset = 0;
            while (offset < length) {
                int count = Math.min(chunk.length, length - offset);
                for (int i = 0; i < count; i++) {
                    int sourceIndex = offset + i;
                    chunk[i] = (byte) (payload[sourceIndex] ^ key[sourceIndex & 3]);
                }
                out.write(chunk, 0, count);
                offset += count;
            }
        } else {
            out.write(payload);
        }

        out.flush();
    }

    /** 回应对端 Close；不立即关闭输入流，让上层 finally 统一释放。 */
    private void sendCloseReply(byte[] payload) {
        writeLock.lock();
        try {
            if (closeSent) {
                return;
            }
            try {
                writeFrameLocked(OP_CLOSE, payload);
                closeSent = true;
            } catch (IOException ignored) {
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 在写入 ByteArrayOutputStream 前检查累计消息大小，避免先扩容到超限尺寸、随后才报错。
     */
    private void appendFragment(ByteArrayOutputStream message, byte[] fragment) throws IOException {
        if (fragment.length > maxMessageBytes - message.size()) {
            throw new IOException(
                    "WebSocket 分片消息过大: " + ((long) message.size() + fragment.length));
        }
        message.write(fragment);
    }

    private void closeStreams() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    private void guardSize(int size) throws IOException {
        if (size < 0 || size > maxMessageBytes) {
            throw new IOException("WebSocket 消息过大: " + size);
        }
    }

    private static boolean isKnownOpcode(int opcode) {
        return opcode == OP_CONT
                || opcode == OP_TEXT
                || opcode == OP_BINARY
                || opcode == OP_CLOSE
                || opcode == OP_PING
                || opcode == OP_PONG;
    }

    private int readByte() throws IOException {
        int value = in.read();
        if (value == -1) {
            throw new EOFException("WebSocket 流结束");
        }
        return value;
    }

    private void readFully(byte[] destination) throws IOException {
        int offset = 0;
        while (offset < destination.length) {
            int count = in.read(destination, offset, destination.length - offset);
            if (count == -1) {
                throw new EOFException("WebSocket 流结束（读负载）");
            }
            if (count == 0) {
                continue;
            }
            offset += count;
        }
    }
}
