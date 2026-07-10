package org.windy.windui.core.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
        public CtrlAction {
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("control action 不能为空");
            }
            args = args == null ? List.of() : List.copyOf(args);
        }

        public static CtrlAction of(String action, String... args) {
            return new CtrlAction(action, args == null ? List.of() : List.of(args));
        }
    }

    /** 把动作列表编码为可发送字节。 */
    public static byte[] encode(List<CtrlAction> actions) {
        if (actions == null) {
            throw new IllegalArgumentException("actions 不能为空");
        }

        JsonArray arr = new JsonArray();
        for (CtrlAction action : actions) {
            if (action == null) {
                throw new IllegalArgumentException("actions 不得包含 null");
            }

            JsonObject object = new JsonObject();
            object.addProperty("action", action.action());

            JsonArray args = new JsonArray();
            for (String value : action.args()) {
                if (value == null) {
                    throw new IllegalArgumentException("control action 参数不得为 null");
                }
                args.add(value);
            }
            object.add("args", args);
            arr.add(object);
        }
        return WireCodecSupport.frameUtf8(arr.toString(), "control json");
    }

    /**
     * 解出控制帧里的 JSON 字符串（客户端拿去 {@code UIJsonParser.parseActions} 解析）。
     * 长度必须精确匹配，拒绝截断、尾随数据和非法 UTF-8。
     */
    public static String decodeJson(byte[] data) {
        return WireCodecSupport.unframeUtf8(data, "control json");
    }
}
