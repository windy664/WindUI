package org.windy.windui.core.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 事件回程频道（{@link WindUIProtocol#EVENT_CHANNEL}）线格式编解码。两端共用。
 * <p>
 * 载荷 = 事件 id + 参数列表(参数里可含 {@code __rb:<id>:<base64>} 回读项，服务端 {@code GuiDriver.event}
 * 统一解析，与命令回程完全一致，故新旧两条路服务端处理逻辑复用一份)。
 * <p>
 * 格式：{@code [eventIdLen:2][eventId utf8][count:2][ (argLen:2 + arg utf8) ... ]}（长度用无符号短整型，
 * 事件/参数都很短，够用且紧凑）。
 */
public final class EventCodec {

    private EventCodec() {
    }

    /** 编码事件回程包。 */
    public static byte[] encode(String eventId, List<String> data) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(32);
        try (DataOutputStream out = new DataOutputStream(buf)) {
            writeStr(out, eventId);
            out.writeShort(data.size());
            for (String d : data) writeStr(out, d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.toByteArray();
    }

    /** 解码为 {@link Event}。非法数据抛异常，由调用方兜底。 */
    public static Event decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(payload))) {
            String eventId = readStr(in);
            int count = in.readUnsignedShort();
            List<String> data = new ArrayList<>(Math.min(count, 64));
            for (int i = 0; i < count; i++) data.add(readStr(in));
            return new Event(eventId, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 一次事件回程。
     *
     * @param eventId 事件 id（如 {@code "buy_diamond"}）
     * @param data    参数（含 {@code __rb:} 回读项）
     */
    public record Event(String eventId, List<String> data) {
    }

    private static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.write(b);
    }

    private static String readStr(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
