package org.windy.windui.core.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 事件回程频道（{@link WindUIProtocol#EVENT_CHANNEL}）线格式编解码。两端共用。
 * <p>
 * 载荷 = 事件 id + 参数列表（参数里可含 {@code __rb:<id>:<base64>} 回读项，服务端 {@code GuiDriver.event}
 * 统一解析，与命令回程完全一致，故新旧两条路服务端处理逻辑复用一份）。
 * <p>
 * 格式：{@code [eventIdLen:2][eventId utf8][count:2][ (argLen:2 + arg utf8) ... ]}。所有长度为无符号
 * short。解码严格拒绝截断、尾随数据、非法 UTF-8、空事件 ID 和超量参数。
 */
public final class EventCodec {

    /** 事件 ID 应是短业务键；限制可阻止滥用事件帧承载大块文本。 */
    private static final int MAX_EVENT_ID_BYTES = 1024;

    /** 一次交互最多携带的参数数量。 */
    private static final int MAX_ARGUMENTS = 256;

    /** 线格式为 unsigned short，因此单参数最大 65535 字节。 */
    private static final int MAX_ARGUMENT_BYTES = 0xFFFF;

    private EventCodec() {
    }

    /** 编码事件回程包。 */
    public static byte[] encode(String eventId, List<String> data) {
        byte[] eventBytes = WireCodecSupport.encodeUtf8(
                eventId, "eventId", MAX_EVENT_ID_BYTES, false);
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId 不能为空白");
        }
        if (data == null) {
            throw new IllegalArgumentException("event data 不能为空");
        }
        if (data.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException(
                    "事件参数过多: " + data.size() + "，上限 " + MAX_ARGUMENTS);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(32);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writeBytes(out, eventBytes);
            out.writeShort(data.size());
            for (int i = 0; i < data.size(); i++) {
                byte[] argument = WireCodecSupport.encodeUtf8(
                        data.get(i), "event arg[" + i + "]", MAX_ARGUMENT_BYTES, true);
                writeBytes(out, argument);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    /** 解码为 {@link Event}。非法数据抛异常，由调用方记录并丢弃该事件。 */
    public static Event decode(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("event payload 不能为空");
        }

        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(payload))) {
            String eventId = readString(in, "eventId", MAX_EVENT_ID_BYTES, false);
            if (eventId.isBlank()) {
                throw new IllegalArgumentException("eventId 不能为空白");
            }

            int count = in.readUnsignedShort();
            if (count > MAX_ARGUMENTS) {
                throw new IllegalArgumentException(
                        "事件参数过多: " + count + "，上限 " + MAX_ARGUMENTS);
            }

            List<String> data = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                data.add(readString(in, "event arg[" + i + "]", MAX_ARGUMENT_BYTES, true));
            }

            if (in.available() != 0) {
                throw new IllegalArgumentException(
                        "event payload 含尾随数据: " + in.available() + " 字节");
            }
            return new Event(eventId, data);
        } catch (EOFException e) {
            throw new IllegalArgumentException("event payload 被截断", e);
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
        public Event {
            if (eventId == null || eventId.isBlank()) {
                throw new IllegalArgumentException("eventId 不能为空");
            }
            data = data == null ? List.of() : List.copyOf(data);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(
            DataInputStream in, String field, int maxBytes, boolean allowEmpty) throws IOException {
        int length = in.readUnsignedShort();
        WireCodecSupport.validateLength(length, field, maxBytes, allowEmpty);
        if (length > in.available()) {
            throw new EOFException(
                    field + " 声明 " + length + " 字节，但仅剩 " + in.available() + " 字节");
        }

        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return WireCodecSupport.decodeUtf8(bytes, field);
    }
}
