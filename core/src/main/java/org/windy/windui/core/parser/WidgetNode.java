package org.windy.windui.core.parser;

import java.util.List;
import java.util.Map;

/**
 * 解析后的抽象 UI 节点。纯数据，不依赖任何 UI 框架。
 *
 * @param type     组件类型（如 "TextView", "Button", "LinearLayout"）
 * @param id       可选的控件 ID
 * @param props    属性键值对
 * @param children 子节点列表
 * @param events   事件名 → 动作列表。事件名如 {@code "click"}/{@code "rightClick"}/
 *                 {@code "hoverEnter"}/{@code "hoverLeave"}/{@code "textChange"}，
 *                 由 {@link UIJsonParser} 从 JSON 的 {@code onClick}/{@code onRightClick}/… 字段解析。
 */
public record WidgetNode(
        String type,
        String id,
        Map<String, Object> props,
        List<WidgetNode> children,
        Map<String, List<Map<String, Object>>> events
) {
    public WidgetNode {
        props = props == null ? Map.of() : Map.copyOf(props);
        children = children == null ? List.of() : List.copyOf(children);
        events = events == null ? Map.of() : Map.copyOf(events);
    }

    /** 某事件绑定的动作列表；无绑定返回空列表。 */
    public List<Map<String, Object>> on(String event) {
        return events.getOrDefault(event, List.of());
    }

    /** 兼容旧调用：{@code click} 事件的动作列表。 */
    public List<Map<String, Object>> onClick() {
        return on("click");
    }

    public String getString(String key, String fallback) {
        Object v = props.get(key);
        return v instanceof String s ? s : fallback;
    }

    public int getInt(String key, int fallback) {
        Object v = props.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public float getFloat(String key, float fallback) {
        Object v = props.get(key);
        if (v instanceof Number n) return n.floatValue();
        if (v instanceof String s) {
            try { return Float.parseFloat(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public boolean getBool(String key, boolean fallback) {
        Object v = props.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }
}
