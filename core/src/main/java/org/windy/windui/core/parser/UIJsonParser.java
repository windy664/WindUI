package org.windy.windui.core.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 AI 下发的 JSON 字符串解析为 {@link WidgetNode} 树。
 * 纯数据解析，不依赖任何 UI 框架。
 * <p>
 * 解析器会限制组件树深度、节点数量、单容器子节点数量和动作参数嵌套深度，避免畸形或恶意 UI JSON
 * 在客户端 UI 线程造成栈溢出、超大对象树或长时间阻塞。
 */
public final class UIJsonParser {

    private static final Gson GSON = new Gson();

    /** 单棵 UI 树允许的最大嵌套深度。根节点深度为 0。 */
    private static final int MAX_TREE_DEPTH = 64;

    /** 单棵 UI 树允许的最大组件节点数。 */
    private static final int MAX_TREE_NODES = 4096;

    /** 单个容器允许的最大直接子节点数。 */
    private static final int MAX_CHILDREN_PER_NODE = 1024;

    /** 单个组件 props 或动作对象允许的最大字段数。 */
    private static final int MAX_OBJECT_ENTRIES = 512;

    /** 单个事件允许绑定的最大动作数量。 */
    private static final int MAX_ACTIONS_PER_EVENT = 256;

    /** 动作参数中 List/Map 允许的最大递归深度。 */
    private static final int MAX_VALUE_DEPTH = 32;

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

    private UIJsonParser() {
    }

    /**
     * 解析 JSON 字符串为 WidgetNode 树。
     *
     * @param json JSON 布局字符串
     * @return 根节点
     * @throws JsonParseException JSON 格式错误、节点结构非法或超过资源边界
     */
    public static WidgetNode parse(String json) {
        if (json == null || json.isBlank()) {
            throw new JsonParseException("UI JSON 不能为空");
        }

        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject()) {
            throw new JsonParseException("UI JSON 根节点必须是对象");
        }

        return parseNode(element.getAsJsonObject(), 0, new ParseBudget());
    }

    /**
     * 解析一段「动作数组」JSON（控制频道载荷）为 {@code ActionExecutor} 能吃的 Map 列表。
     * 复用与 onClick 相同的动作解析逻辑。非数组、空字符串、解析失败或超过资源边界时返回空列表。
     *
     * @param json JSON 数组字符串，如 {@code [{"action":"closeScreen"}]}
     */
    public static List<Map<String, Object>> parseActions(String json) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }

        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return out;
            }

            JsonArray array = root.getAsJsonArray();
            if (array.size() > MAX_ACTIONS_PER_EVENT) {
                return out;
            }

            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    out.add(toActionMap(element.getAsJsonObject()));
                }
            }
        } catch (RuntimeException ignored) {
            out.clear();
        }
        return out;
    }

    private static WidgetNode parseNode(JsonObject object, int depth, ParseBudget budget) {
        if (depth > MAX_TREE_DEPTH) {
            throw new JsonParseException(
                    "UI 组件树嵌套过深，最大允许深度 " + MAX_TREE_DEPTH);
        }

        budget.nodes++;
        if (budget.nodes > MAX_TREE_NODES) {
            throw new JsonParseException(
                    "UI 组件节点过多，最大允许 " + MAX_TREE_NODES + " 个");
        }

        String type = requiredString(object, "type", "UI 组件");
        String id = optionalString(object, "id");

        Map<String, Object> props = new LinkedHashMap<>();
        if (object.has("props") && !object.get("props").isJsonNull()) {
            JsonElement propsElement = object.get("props");
            if (!propsElement.isJsonObject()) {
                throw new JsonParseException("组件 " + type + " 的 props 必须是对象");
            }

            JsonObject propsObject = propsElement.getAsJsonObject();
            guardObjectSize(propsObject, "组件 " + type + " 的 props");
            for (Map.Entry<String, JsonElement> entry : propsObject.entrySet()) {
                props.put(entry.getKey(), toPlainValue(entry.getValue(), 0));
            }
        }

        List<WidgetNode> children = new ArrayList<>();
        if (object.has("children") && !object.get("children").isJsonNull()) {
            JsonElement childrenElement = object.get("children");
            if (!childrenElement.isJsonArray()) {
                throw new JsonParseException("组件 " + type + " 的 children 必须是数组");
            }

            JsonArray array = childrenElement.getAsJsonArray();
            if (array.size() > MAX_CHILDREN_PER_NODE) {
                throw new JsonParseException(
                        "组件 " + type + " 的直接子节点过多，最大允许 "
                                + MAX_CHILDREN_PER_NODE + " 个");
            }

            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    throw new JsonParseException(
                            "组件 " + type + " 的 children 只能包含组件对象");
                }
                children.add(parseNode(element.getAsJsonObject(), depth + 1, budget));
            }
        }

        Map<String, List<Map<String, Object>>> events = new LinkedHashMap<>();
        for (Map.Entry<String, String> event : EVENT_KEYS.entrySet()) {
            List<Map<String, Object>> actions =
                    parseActionField(object, event.getKey(), type);
            if (!actions.isEmpty()) {
                events.put(event.getValue(), actions);
            }
        }

        return new WidgetNode(type, id, props, children, events);
    }

    /** 读一个动作字段（单对象或数组）为动作列表；字段缺失或为 null 返回空列表。 */
    private static List<Map<String, Object>> parseActionField(
            JsonObject object, String field, String componentType) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return out;
        }

        JsonElement element = object.get(field);
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() > MAX_ACTIONS_PER_EVENT) {
                throw new JsonParseException(
                        "组件 " + componentType + " 的 " + field + " 动作过多，最大允许 "
                                + MAX_ACTIONS_PER_EVENT + " 个");
            }

            for (JsonElement action : array) {
                if (!action.isJsonObject()) {
                    throw new JsonParseException(
                            "组件 " + componentType + " 的 " + field
                                    + " 动作数组只能包含对象");
                }
                out.add(toActionMap(action.getAsJsonObject()));
            }
            return out;
        }

        if (element.isJsonObject()) {
            out.add(toActionMap(element.getAsJsonObject()));
            return out;
        }

        throw new JsonParseException(
                "组件 " + componentType + " 的 " + field + " 必须是动作对象或动作数组");
    }

    private static Map<String, Object> toActionMap(JsonObject object) {
        guardObjectSize(object, "动作对象");
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            map.put(entry.getKey(), toPlainValue(entry.getValue(), 0));
        }
        return map;
    }

    /** JsonElement → Java 原生类型（String/Number/Boolean/List/Map），递归。 */
    private static Object toPlainValue(JsonElement element, int depth) {
        if (depth > MAX_VALUE_DEPTH) {
            throw new JsonParseException(
                    "组件属性或动作参数嵌套过深，最大允许深度 " + MAX_VALUE_DEPTH);
        }

        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                return primitive.getAsString();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
        }

        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() > MAX_CHILDREN_PER_NODE) {
                throw new JsonParseException(
                        "组件属性或动作参数数组过大，最大允许 "
                                + MAX_CHILDREN_PER_NODE + " 项");
            }

            List<Object> list = new ArrayList<>(array.size());
            for (JsonElement child : array) {
                list.add(toPlainValue(child, depth + 1));
            }
            return list;
        }

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            guardObjectSize(object, "组件属性或动作参数对象");

            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                map.put(entry.getKey(), toPlainValue(entry.getValue(), depth + 1));
            }
            return map;
        }

        if (element.isJsonNull()) {
            return null;
        }

        return GSON.toJson(element);
    }

    private static String requiredString(
            JsonObject object, String field, String owner) {
        String value = optionalString(object, field);
        if (value == null || value.isBlank()) {
            throw new JsonParseException(owner + "缺少非空字符串字段 " + field);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }

        JsonElement element = object.get(field);
        if (!element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(field + " 必须是字符串");
        }
        return element.getAsString();
    }

    private static void guardObjectSize(JsonObject object, String field) {
        if (object.size() > MAX_OBJECT_ENTRIES) {
            throw new JsonParseException(
                    field + "字段过多，最大允许 " + MAX_OBJECT_ENTRIES + " 个");
        }
    }

    private static final class ParseBudget {
        private int nodes;
    }
}
