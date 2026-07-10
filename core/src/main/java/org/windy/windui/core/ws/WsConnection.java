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
 * 只暴露"发一条二进制消息 / 收一条数据消息"，内部处理分片重组、ping→pong、close。控制帧对上层透明。
 * {@code maskOutbound} 决定出站是否加掩码：按 RFC，<b>客户端→服务端必须加掩码，服务端→客户端不得加</b>，
 * 故客户端侧传 {@code true}、服务端侧传 {@code false}。收帧无论哪侧都按 MASK 位处理。
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
    private volatile boolean closed = false;

    public WsConnection(InputStream in, OutputStream out, boolean maskOutbound, int maxMessageBytes) {
        this.in = in;
        this.out = out;
        this.maskOutbound = maskOutbound;
        this.maxMessageBytes = maxMessageBytes;
    }

    /** 发一条应用消息（二进制帧，单帧不分片）。 */
    public void send(byte[] payload) throws IOException {
        writeFrame(OP_BINARY, payload);
    }

    /**
     * 收下一条数据消息（文本/二进制，透明重组分片）；期间自动回应 ping、忽略 pong。
     * 对端 close 或流结束抛 {@link EOFException}。
     */
    public byte[] recv() throws IOException {
        ByteArrayOutputStream message = null; // 仅分片时分配
        int messageOpcode = -1;
        while (true) {
            Frame f = readFrame();
            switch (f.opcode) {
                case OP_PING -> writeFrame(OP_PONG, f.payload);
                case OP_PONG -> { /* 忽略 */ }
                case OP_CLOSE -> {
                    safeSendClose();
                    throw new EOFException("对端关闭 WebSocket");
                }
                case OP_TEXT, OP_BINARY -> {
                    if (f.fin) {
                        if (message == null) return f.payload; // 常态：单帧
                        message.write(f.payload);               // 极少：此前已有分片
                        return message.toByteArray();
                    }
                    message = new ByteArrayOutputStream();
                    message.write(f.payload);
                    messageOpcode = f.opcode;
                }
                case OP_CONT -> {
                    if (message == null) throw new IOException("意外的续帧");
                    message.write(f.payload);
                    guardSize(message.size());
                    if (f.fin) {
                        byte[] done = message.toByteArray();
                        message = null;
                        messageOpcode = -1;
                        return done;
                    }
                }
                default -> throw new IOException("未知 WS opcode: " + f.opcode);
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        safeSendClose();
        try {
            out.close();
        } catch (IOException ignored) {
        }
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    // ===== 帧编解码 =====

    private record Frame(boolean fin, int opcode, byte[] payload) {
    }

    private Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 == -1) throw new EOFException("WS 流结束");
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;

        int b1 = readByte();
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByte() << 8) | readByte();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
        }
        if (len < 0 || len > maxMessageBytes) throw new IOException("WS 帧过大: " + len);

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(mask);
        }
        byte[] payload = new byte[(int) len];
        readFully(payload);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        }
        return new Frame(fin, opcode, payload);
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        if (closed) throw new IOException("WS 已关闭");
        int len = payload.length;
        writeLock.lock();
        try {
            out.write(0x80 | opcode); // FIN + opcode
            int maskBit = maskOutbound ? 0x80 : 0x00;
            if (len < 126) {
                out.write(maskBit | len);
            } else if (len <= 0xFFFF) {
                out.write(maskBit | 126);
                out.write((len >>> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(maskBit | 127);
                for (int i = 7; i >= 0; i--) out.write((int) (((long) len >>> (8 * i)) & 0xFF));
            }
            if (maskOutbound) {
                byte[] key = new byte[4];
                rnd.nextBytes(key);
                out.write(key);
                byte[] masked = new byte[len];
                for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ key[i & 3]);
                out.write(masked);
            } else {
                out.write(payload);
            }
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    private void safeSendClose() {
        try {
            if (!closed) writeFrame(OP_CLOSE, new byte[0]);
        } catch (IOException ignored) {
        }
    }

    private void guardSize(int size) throws IOException {
        if (size > maxMessageBytes) throw new IOException("WS 消息过大: " + size);
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b == -1) throw new EOFException("WS 流结束");
        return b;
    }

    private void readFully(byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n == -1) throw new EOFException("WS 流结束（读负载）");
            off += n;
        }
    }
}
