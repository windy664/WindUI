package org.windy.windui.core.parser;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 AI 下发的 JSON 字符串解析为 {@link WidgetNode} 树。
 * 纯数据解析，不依赖任何 UI 框架。
 */
public final class UIJsonParser {

    private static final Gson GSON = new Gson();

    private UIJsonParser() {
    }

    /**
     * 解析 JSON 字符串为 WidgetNode 树。
     *
     * @param json JSON 布局字符串
     * @return 根节点
     * @throws JsonParseException JSON 格式错误
     */
    public static WidgetNode parse(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        return parseNode(root);
    }

    /**
     * 解析一段「动作数组」JSON（控制频道载荷）为 {@code ActionExecutor} 能吃的 Map 列表。
     * 复用与 onClick 相同的动作解析逻辑。非数组 / 空 / 解析失败返回空列表。
     *
     * @param json JSON 数组字符串，如 {@code [{"action":"closeScreen"}]}
     */
    public static List<Map<String, Object>> parseActions(String json) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonArray arr = GSON.fromJson(json, JsonArray.class);
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (el.isJsonObject()) out.add(toActionMap(el.getAsJsonObject()));
        }
        return out;
    }

    private static WidgetNode parseNode(JsonObject obj) {
        String type = obj.get("type").getAsString();
        String id = obj.has("id") ? obj.get("id").getAsString() : null;

        Map<String, Object> props = new LinkedHashMap<>();
        if (obj.has("props")) {
            JsonObject propsObj = obj.getAsJsonObject("props");
            for (Map.Entry<String, JsonElement> entry : propsObj.entrySet()) {
                props.put(entry.getKey(), toPlainValue(entry.getValue()));
            }
        }

        List<WidgetNode> children = new ArrayList<>();
        if (obj.has("children")) {
            JsonArray arr = obj.getAsJsonArray("children");
            for (JsonElement el : arr) {
                children.add(parseNode(el.getAsJsonObject()));
            }
        }

        // 解析各事件绑定：JSON 的 onXxx 字段 → events map。值可为单个动作对象或动作数组。
        Map<String, List<Map<String, Object>>> events = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : EVENT_KEYS.entrySet()) {
            List<Map<String, Object>> actions = parseActionField(obj, e.getKey());
            if (!actions.isEmpty()) events.put(e.getValue(), actions);
        }

        return new WidgetNode(type, id, props, children, events);
    }

    /** JSON 事件字段名 → 内部事件名。 */
    private static final Map<String, String> EVENT_KEYS = Map.ofEntries(
            Map.entry("onClick", "click"),
            Map.entry("onRightClick", "rightClick"),
            Map.entry("onMiddleClick", "middleClick"),
            Map.entry("onHoverEnter", "hoverEnter"),
            Map.entry("onHoverLeave", "hoverLeave"),
            Map.entry("onTextChange", "textChange"),
            Map.entry("onChange", "change"),
            Map.entry("onScroll", "scroll"),
            Map.entry("onRelease", "release"));

    /** 读一个动作字段（单对象或数组）为动作列表；字段缺失返回空列表。 */
    private static List<Map<String, Object>> parseActionField(JsonObject obj, String field) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!obj.has(field)) return out;
        JsonElement el = obj.get(field);
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonObject()) out.add(toActionMap(e.getAsJsonObject()));
            }
        } else if (el.isJsonObject()) {
            out.add(toActionMap(el.getAsJsonObject()));
        }
        return out;
    }

    private static Map<String, Object> toActionMap(JsonObject obj) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            map.put(entry.getKey(), toPlainValue(entry.getValue()));
        }
        return map;
    }

    /** JsonElement → Java 原生类型（String/Number/Boolean/List/Map），递归。 */
    private static Object toPlainValue(JsonElement el) {
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isNumber()) return p.getAsNumber();
            if (p.isBoolean()) return p.getAsBoolean();
        }
        // JSON 数组 → List（如动作的 args / chain 的 actions）
        if (el.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement e : el.getAsJsonArray()) list.add(toPlainValue(e));
            return list;
        }
        // JSON 对象 → Map（如 chain 里嵌套的动作对象）
        if (el.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : el.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), toPlainValue(entry.getValue()));
            }
            return map;
        }
        if (el.isJsonNull()) return null;
        return el.toString();
    }
}
