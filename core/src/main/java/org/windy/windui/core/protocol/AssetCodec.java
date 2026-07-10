package org.windy.windui.core.protocol;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 图片资源包（{@link WindUIProtocol#ASSET_CHANNEL}）线格式编解码。两端共用。
 * <p>
 * 格式：{@code [hashLen: 4字节大端][hash: UTF-8][png: 余下全部字节]}。
 * hash = 图片内容哈希，客户端据此缓存去重（同一图全网只下一次）；JSON 里图片用 {@code "img:<path>"}
 * 引用，服务端解析为该图的 hash 并按需在此频道推送字节。
 */
public final class AssetCodec {

    /** hash 是缓存键，不应承载任意大字符串。当前短 SHA-256 为 16 字节，预留到 256 字节便于演进。 */
    private static final int MAX_HASH_BYTES = 256;

    private AssetCodec() {
    }

    /** 编码为可发送的字节：{@code [hashLen][hash][png]}。PNG 允许为空，由图片解码层决定是否可用。 */
    public static byte[] encode(String hash, byte[] png) {
        byte[] h = WireCodecSupport.encodeUtf8(hash, "asset hash", MAX_HASH_BYTES, false);
        if (png == null) {
            throw new IllegalArgumentException("png 不能为空");
        }
        if (png.length > Integer.MAX_VALUE - Integer.BYTES - h.length) {
            throw new IllegalArgumentException("asset payload 过大");
        }

        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + h.length + png.length);
        buffer.putInt(h.length);
        buffer.put(h);
        buffer.put(png);
        return buffer.array();
    }

    /** 解码 {@link #encode} 的字节为 {@link Asset}。 */
    public static Asset decode(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("asset frame 不能为空");
        }
        if (data.length < Integer.BYTES) {
            throw new IllegalArgumentException("asset frame 过短: " + data.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        int hashLen = buffer.getInt();
        WireCodecSupport.validateLength(hashLen, "asset hash", MAX_HASH_BYTES, false);
        if (hashLen > buffer.remaining()) {
            throw new IllegalArgumentException(
                    "bad hashLen " + hashLen + ", remaining " + buffer.remaining());
        }

        byte[] hashBytes = new byte[hashLen];
        buffer.get(hashBytes);
        String hash = WireCodecSupport.decodeUtf8(hashBytes, "asset hash");

        byte[] png = Arrays.copyOfRange(data, buffer.position(), data.length);
        return new Asset(hash, png);
    }

    /**
     * 一张图片资源。
     *
     * @param hash 内容哈希（缓存键）
     * @param png  PNG 字节
     */
    public record Asset(String hash, byte[] png) {
        public Asset {
            if (hash == null || hash.isEmpty()) {
                throw new IllegalArgumentException("asset hash 不能为空");
            }
            if (png == null) {
                throw new IllegalArgumentException("png 不能为空");
            }
            png = png.clone();
        }

        @Override
        public byte[] png() {
            return png.clone();
        }
    }
}
