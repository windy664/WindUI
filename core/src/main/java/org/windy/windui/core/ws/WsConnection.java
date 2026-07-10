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
 * 故客户端侧传 {@code true}、服务端侧传 {@code false}。收帧按实际 MASK 位解码。
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
    private final SecureRandom rnd = new SecureRandom();

    private volatile boolean closed;

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
                    sendCloseReply();
                    throw new EOFException("对端关闭 WebSocket");
                }
                case OP_TEXT, OP_BINARY -> {
                    if (message != null) {
                        throw new IOException("上一条分片消息尚未结束时收到新的数据帧");
                    }
                    if (frame.fin) {
                        return frame.payload;
                    }
                    message = new ByteArrayOutputStream(Math.min(frame.payload.length + 32, maxMessageBytes));
                    message.write(frame.payload);
                    guardSize(message.size());
                }
                case OP_CONT -> {
                    if (message == null) {
                        throw new IOException("意外的 WebSocket 续帧");
                    }
                    message.write(frame.payload);
                    guardSize(message.size());
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
     * 发起规范关闭并释放流。Close 帧必须在 {@code closed=true} 之前写出；
     * 旧实现先置 closed 再调发送，导致 Close 帧永远不会真正发送。
     */
    @Override
    public void close() {
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            try {
                writeFrameLocked(OP_CLOSE, new byte[0]);
            } catch (IOException ignored) {
                // 对端可能已经断开；仍继续关闭本地资源
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

        int b1 = readByte();
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;

        if (len == 126) {
            len = ((long) readByte() << 8) | readByte();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte();
            }
            if (len < 0) {
                throw new IOException("非法 WebSocket 64 位负载长度");
            }
        }

        if (control) {
            if (!fin) {
                throw new IOException("WebSocket 控制帧不得分片");
            }
            if (len > 125) {
                throw new IOException("WebSocket 控制帧负载过大: " + len);
            }
        }

        if (len > maxMessageBytes) {
            throw new IOException("WebSocket 帧过大: " + len);
        }

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(mask);
        }

        byte[] payload = new byte[(int) len];
        readFully(payload);
        if (mask != null) {
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
        } finally {
            writeLock.unlock();
        }
    }

    /** 调用者必须持有 {@link #writeLock}。 */
    private void writeFrameLocked(int opcode, byte[] payload) throws IOException {
        int len = payload.length;
        out.write(0x80 | opcode);

        int maskBit = maskOutbound ? 0x80 : 0x00;
        if (len < 126) {
            out.write(maskBit | len);
        } else if (len <= 0xFFFF) {
            out.write(maskBit | 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(maskBit | 127);
            long longLen = len;
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((longLen >>> (8 * i)) & 0xFF));
            }
        }

        if (maskOutbound) {
            byte[] key = new byte[4];
            rnd.nextBytes(key);
            out.write(key);

            // 避免为大图再分配一份完整 masked 数组，按块异或写出，降低峰值内存。
            byte[] chunk = new byte[Math.min(8192, Math.max(1, len))];
            int offset = 0;
            while (offset < len) {
                int count = Math.min(chunk.length, len - offset);
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
    private void sendCloseReply() {
        try {
            writeFrame(OP_CLOSE, new byte[0]);
        } catch (IOException ignored) {
        }
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

    private int readByte() throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("WebSocket 流结束");
        }
        return b;
    }

    private void readFully(byte[] dst) throws IOException {
        int offset = 0;
        while (offset < dst.length) {
            int count = in.read(dst, offset, dst.length - offset);
            if (count == -1) {
                throw new EOFException("WebSocket 流结束（读负载）");
            }
            offset += count;
        }
    }
}
