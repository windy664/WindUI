package org.windy.windui.core.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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
 * 解码时严格限制 Patch 数量、组件 ID 长度和属性数量，并且只接受标量属性值，避免单条增量消息构造
 * 超大对象树或把不受支持的嵌套 JSON 注入客户端属性更新路径。
 */
public final class PatchCodec {

    /** 一条 Patch 消息最多包含的组件更新数量。 */
    private static final int MAX_PATCHES = 256;

    /** 组件 ID 的 UTF-8 字节上限。 */
    private static final int MAX_ID_BYTES = 256;

    /** 单条 Patch 最多包含的属性数量。 */
    private static final int MAX_PROPS = 128;

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
            validateId(id);
            if (props == null) {
                props = Map.of();
            }
            if (props.size() > MAX_PROPS) {
                throw new IllegalArgumentException(
                        "patch 属性过多: " + props.size() + "，上限 " + MAX_PROPS);
            }

            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : props.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("patch 属性名不能为空");
                }
                Object value = entry.getValue();
                if (!isScalar(value)) {
                    throw new IllegalArgumentException(
                            "patch 属性 " + key + " 只允许字符串、数字、布尔值或 null");
                }
                copy.put(key, value);
            }
            props = Collections.unmodifiableMap(copy);
        }
    }

    /** 把已是 JSON（对象或数组）的 patch 串编码为可发送字节。 */
    public static byte[] encode(String json) {
        return WireCodecSupport.frameUtf8(json, "patch json");
    }

    /**
     * 解出 patch 列表（客户端用）。
     * 线格式损坏、非法 UTF-8、非法 JSON、错误字段类型或超过业务边界时抛异常，
     * 由 WebSocket 回调隔离层记录并跳过整条消息。
     */
    public static List<Patch> decode(byte[] data) {
        String json = WireCodecSupport.unframeUtf8(data, "patch json");
        JsonElement root = JsonParser.parseString(json);

        List<Patch> out = new ArrayList<>();
        if (root.isJsonArray()) {
            JsonArray array = root.getAsJsonArray();
            if (array.size() > MAX_PATCHES) {
                throw new JsonParseException(
                        "patch 数量过多: " + array.size() + "，上限 " + MAX_PATCHES);
            }
            for (int index = 0; index < array.size(); index++) {
                out.add(parsePatch(array.get(index), index));
            }
        } else {
            out.add(parsePatch(root, 0));
        }
        return List.copyOf(out);
    }

    private static Patch parsePatch(JsonElement element, int index) {
        if (!element.isJsonObject()) {
            throw new JsonParseException("patch[" + index + "] 必须是对象");
        }

        JsonObject object = element.getAsJsonObject();
        JsonElement idElement = object.get("id");
        if (idElement == null
                || !idElement.isJsonPrimitive()
                || !idElement.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("patch[" + index + "].id 必须是非空字符串");
        }

        String id = idElement.getAsString();
        try {
            validateId(id);
        } catch (IllegalArgumentException e) {
            throw new JsonParseException("patch[" + index + "].id 非法: " + e.getMessage(), e);
        }

        Map<String, Object> props = new LinkedHashMap<>();
        JsonElement propsElement = object.get("props");
        if (propsElement != null && !propsElement.isJsonNull()) {
            if (!propsElement.isJsonObject()) {
                throw new JsonParseException("patch[" + index + "].props 必须是对象");
            }

            JsonObject propsObject = propsElement.getAsJsonObject();
            if (propsObject.size() > MAX_PROPS) {
                throw new JsonParseException(
                        "patch[" + index + "] 属性过多: "
                                + propsObject.size() + "，上限 " + MAX_PROPS);
            }

            for (Map.Entry<String, JsonElement> entry : propsObject.entrySet()) {
                if (entry.getKey().isBlank()) {
                    throw new JsonParseException(
                            "patch[" + index + "] 含空属性名");
                }
                props.put(
                        entry.getKey(),
                        toScalar(entry.getValue(), index, entry.getKey()));
            }
        }

        return new Patch(id, props);
    }

    /** Patch 属性只允许标量或 null；复合属性应通过整树重渲染表达。 */
    private static Object toScalar(
            JsonElement element,
            int patchIndex,
            String propertyName) {
        if (element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive()) {
            throw new JsonParseException(
                    "patch[" + patchIndex + "].props." + propertyName
                            + " 只允许字符串、数字、布尔值或 null");
        }

        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber();
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        throw new JsonParseException(
                "patch[" + patchIndex + "].props." + propertyName
                        + " 含不支持的值类型");
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("patch id 不能为空");
        }
        int bytes = id.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_ID_BYTES) {
            throw new IllegalArgumentException(
                    "patch id 过长: " + bytes + " 字节，上限 " + MAX_ID_BYTES);
        }
    }

    private static boolean isScalar(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean;
    }
}
