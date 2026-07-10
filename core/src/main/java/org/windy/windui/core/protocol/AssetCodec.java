package org.windy.windui.core.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 图片资源包（{@link WindUIProtocol#ASSET_CHANNEL}）线格式编解码。两端共用。
 * <p>
 * 格式：{@code [hashLen: 4字节大端][hash: UTF-8][png: 余下全部字节]}。
 * hash = 图片内容哈希，客户端据此缓存去重（同一图全网只下一次）；JSON 里图片用 {@code "img:<path>"}
 * 引用，服务端解析为该图的 hash 并按需在此频道推送字节。
 */
public final class AssetCodec {

    private AssetCodec() {
    }

    /** 编码为可通过 Plugin Message 发送的字节：{@code [hashLen][hash][png]}。 */
    public static byte[] encode(String hash, byte[] png) {
        byte[] h = hash.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(h.length + png.length + 4);
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeInt(h.length);
            out.write(h);
            out.write(png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.toByteArray();
    }

    /** 解码 {@link #encode} 的字节为 {@link Asset}。 */
    public static Asset decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data))) {
            int hashLen = in.readInt();
            if (hashLen < 0 || hashLen > data.length) {
                throw new IllegalArgumentException("bad hashLen " + hashLen);
            }
            byte[] h = new byte[hashLen];
            in.readFully(h);
            byte[] png = in.readAllBytes();
            return new Asset(new String(h, StandardCharsets.UTF_8), png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 一张图片资源。
     *
     * @param hash 内容哈希（缓存键）
     * @param png  PNG 字节
     */
    public record Asset(String hash, byte[] png) {
    }
}
