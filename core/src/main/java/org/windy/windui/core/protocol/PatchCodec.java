package org.windy.windui.core.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 增量更新频道（{@link WindUIProtocol#PATCH_CHANNEL}）线格式编解码。两端共用。
 * <p>
 * 载荷是 JSON：单个 {@code {"id":"...","props":{...}}} 或其数组。服务端脚本用 {@code ctx.patch(id, props)}
 * 产出（arg 即 {@code JSON.stringify} 后的对象），服务端把一次事件里的多个 patch 合成数组一次下发；
 * 客户端按 id 找到已渲染的 View，只改 props 里声明的属性（text/textColor/textSize/visible/alpha/enable…），
 * 不重渲染整树。
 * <p>
 * 帧：{@code [jsonLen: 4字节大端][json: UTF-8]}（与 {@link UiPayloadCodec} 同结构，独立频道）。
 */
public final class PatchCodec {

    private PatchCodec() {
    }

    /**
     * 一条增量更新：把 {@code id} 组件的若干属性改成 {@code props} 里的值。
     *
     * @param id    目标组件的 id（渲染时登记）
     * @param props 要覆盖的属性（仅这些键被改，其余保持不变）
     */
    public record Patch(String id, Map<String, Object> props) {
        public Patch {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("patch id 不能为空");
            }
            props = props == null ? Map.of() : Map.copyOf(props);
        }
    }

    /** 把已是 JSON（对象或数组）的 patch 串编码为可发送字节。 */
    public static byte[] encode(String json) {
        return WireCodecSupport.frameUtf8(json, "patch json");
    }

    /**
     * 解出 patch 列表（客户端用）。
     * 线格式损坏、非法 UTF-8 或非法 JSON 会抛异常，由 WebSocket 回调隔离层记录并跳过本条消息。
     */
    public static List<Patch> decode(byte[] data) {
        List<Patch> out = new ArrayList<>();
        String json = WireCodecSupport.unframeUtf8(data, "patch json");
        JsonElement root = JsonParser.parseString(json);
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                addPatch(out, element);
            }
        } else {
            addPatch(out, root);
        }
        return out;
    }

    private static void addPatch(List<Patch> out, JsonElement element) {
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        if (!object.has("id") || !object.get("id").isJsonPrimitive()) {
            return;
        }

        String id = object.get("id").getAsString();
        if (id.isBlank()) {
            return;
        }

        Map<String, Object> props = new LinkedHashMap<>();
        if (object.has("props") && object.get("props").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("props").entrySet()) {
                props.put(entry.getKey(), toPlain(entry.getValue()));
            }
        }
        out.add(new Patch(id, props));
    }

    /** patch 属性值均为标量（text/color=String, size/alpha/value=Number, visible/enable=Boolean）。 */
    private static Object toPlain(JsonElement element) {
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            return primitive.getAsString();
        }
        return element.isJsonNull() ? null : element.toString();
    }
}
