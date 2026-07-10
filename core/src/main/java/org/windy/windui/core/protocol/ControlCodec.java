package org.windy.windui.core.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 控制频道（{@link WindUIProtocol#CTRL_CHANNEL}）线格式编解码。两端共用。
 * <p>
 * 载荷是一段 JSON 数组，每个元素是一个 {@link org.windy.windui.core.script.ActionExecutor} 能识别的
 * 结构化动作，如 {@code {"action":"executeCommand","args":["/spawn"]}}。服务端把脚本产出的
 * close/command/toast/sound 等主动效果转成此数组下发，客户端把每个动作丢给 {@code ActionExecutor} 执行。
 * <p>
 * 帧：{@code [jsonLen: 4字节大端][json: UTF-8]}（与 {@link UiPayloadCodec} 同结构，独立频道）。
 */
public final class ControlCodec {

    private ControlCodec() {
    }

    /**
     * 一个下发给客户端的动作。{@code args} 与 {@code ActionExecutor} 中同名动作的参数序列一致：
     * {@code executeCommand:[命令] / showToast:[文本] / playSound:[id,音量?,音高?] / closeScreen:[]}。
     *
     * @param action 动作名（须为 {@code ActionExecutor} 白名单内）
     * @param args   参数列表（可空）
     */
    public record CtrlAction(String action, List<String> args) {
        public static CtrlAction of(String action, String... args) {
            return new CtrlAction(action, List.of(args));
        }
    }

    /** 把动作列表编码为可通过 Plugin Message 发送的字节。 */
    public static byte[] encode(List<CtrlAction> actions) {
        JsonArray arr = new JsonArray();
        for (CtrlAction a : actions) {
            JsonObject o = new JsonObject();
            o.addProperty("action", a.action());
            JsonArray args = new JsonArray();
            if (a.args() != null) {
                for (String s : a.args()) args.add(s);
            }
            o.add("args", args);
            arr.add(o);
        }
        return frame(arr.toString());
    }

    /** 解出控制帧里的 JSON 字符串（客户端拿去 {@code UIJsonParser.parseActions} 解析）。 */
    public static String decodeJson(byte[] data) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data))) {
            int len = in.readInt();
            if (len < 0 || len > data.length) {
                throw new IllegalArgumentException("bad jsonLen " + len);
            }
            byte[] json = new byte[len];
            in.readFully(json);
            return new String(json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] frame(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(bytes.length + 4);
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeInt(bytes.length);
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.toByteArray();
    }
}
