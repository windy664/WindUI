package org.windy.windui.core.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * WindUI 业务协议线格式的共享校验工具。
 * <p>
 * 统一处理 UTF-8 编解码和 {@code [length:int][payload]} 帧，避免各 Codec 分别实现时出现
 * {@code len <= data.length} 而不是 {@code len == data.length - 4} 之类的边界差异。
 * <p>
 * WebSocket 的 UI、AUTH 等不带业务长度头的文本载荷也应复用 {@link #decodeUtf8(byte[], String)}，
 * 确保所有网络文本均严格拒绝畸形 UTF-8，而不是由 {@link String} 构造器静默替换为替换字符。
 */
public final class WireCodecSupport {

    private WireCodecSupport() {
    }

    /**
     * 把 UTF-8 字符串编码为 {@code [length:int][payload]}。
     *
     * @param value 字符串
     * @param field 错误消息中的字段名
     * @return 带 4 字节长度前缀的 UTF-8 帧
     */
    public static byte[] frameUtf8(String value, String field) {
        byte[] payload = encodeUtf8(
                value,
                field,
                Integer.MAX_VALUE - Integer.BYTES,
                true);
        ByteBuffer buffer = ByteBuffer.allocate(
                Integer.BYTES + payload.length);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    /**
     * 严格解出 {@code [length:int][UTF-8 payload]}。
     * 声明长度必须恰好等于帧中剩余字节数，截断和尾随数据均拒绝。
     *
     * @param data  带长度前缀的帧
     * @param field 错误消息中的字段名
     * @return 解码后的字符串
     */
    public static String unframeUtf8(byte[] data, String field) {
        if (data == null) {
            throw new IllegalArgumentException(
                    field + " frame 不能为空");
        }
        if (data.length < Integer.BYTES) {
            throw new IllegalArgumentException(
                    field + " frame 过短: " + data.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        int length = buffer.getInt();
        int remaining = buffer.remaining();
        if (length < 0 || length != remaining) {
            throw new IllegalArgumentException(
                    "bad " + field + " length "
                            + length
                            + ", remaining "
                            + remaining);
        }

        byte[] payload = new byte[length];
        buffer.get(payload);
        return decodeUtf8(payload, field);
    }

    /**
     * 严格编码 UTF-8，并检查字节长度。
     *
     * @param value      要编码的字符串
     * @param field      错误消息中的字段名
     * @param maxBytes   最大 UTF-8 字节数
     * @param allowEmpty 是否允许空字符串
     * @return UTF-8 字节
     */
    public static byte[] encodeUtf8(
            String value,
            String field,
            int maxBytes,
            boolean allowEmpty) {
        if (value == null) {
            throw new IllegalArgumentException(
                    field + " 不能为空");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        validateLength(
                bytes.length,
                field,
                maxBytes,
                allowEmpty);
        return bytes;
    }

    /**
     * 严格解码 UTF-8；畸形输入直接拒绝，不以替换字符静默吞掉协议损坏。
     *
     * @param bytes UTF-8 字节
     * @param field 错误消息中的字段名
     * @return 解码后的字符串
     */
    public static String decodeUtf8(byte[] bytes, String field) {
        if (bytes == null) {
            throw new IllegalArgumentException(
                    field + " 不能为空");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    field + " 不是合法 UTF-8",
                    e);
        }
    }

    /**
     * 校验字节长度。
     *
     * @param length     实际字节长度
     * @param field      错误消息中的字段名
     * @param maxBytes   最大允许字节数
     * @param allowEmpty 是否允许零长度
     */
    public static void validateLength(
            int length,
            String field,
            int maxBytes,
            boolean allowEmpty) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException(
                    "maxBytes 不能为负数");
        }
        if (length < 0
                || (!allowEmpty && length == 0)
                || length > maxBytes) {
            String range = allowEmpty
                    ? "0.." + maxBytes
                    : "1.." + maxBytes;
            throw new IllegalArgumentException(
                    field
                            + " 字节长度非法: "
                            + length
                            + "，允许范围 "
                            + range);
        }
    }
}
