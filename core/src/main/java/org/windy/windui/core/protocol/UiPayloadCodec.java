package org.windy.windui.core.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * WindUI 数据包线格式编解码。平台无关——服务端各平台（Velocity / 未来 Bukkit）共用，
 * 保证与客户端解码约定一致。
 * <p>
 * 格式：{@code [jsonLen: 4字节大端][json: UTF-8 字节]}
 */
public final class UiPayloadCodec {

    private UiPayloadCodec() {
    }

    /** 把 payload 编码为可通过 Plugin Message 发送的字节。 */
    public static byte[] encode(UiPayload payload) {
        byte[] json = payload.uiJson().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(json.length + 4);
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeInt(json.length);
            out.write(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.toByteArray();
    }
}
