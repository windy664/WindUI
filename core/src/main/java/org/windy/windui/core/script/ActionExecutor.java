package org.windy.windui.core.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构化动作执行器——替代 JS 引擎。
 * <p>
 * 按钮行为通过 JSON 定义，例如：
 * <pre>
 * { "action": "executeCommand", "args": ["/spawn"] }
 * { "action": "closeScreen" }
 * { "action": "showToast", "args": ["正在传送..."] }
 * { "action": "callback", "id": "buy_diamond", "data": ["1"] }   // 回调服务端事件
 * { "action": "chain", "actions": [...] }
 * </pre>
 * 安全、无反射、无脚本引擎依赖。
 */
public final class ActionExecutor {

    private final SafeClientAPI api;

    public ActionExecutor(SafeClientAPI api) {
        this.api = api;
    }

    /**
     * 执行一个结构化动作。
     *
     * @param action 动作 Map（从 JSON 解析而来）
     */
    @SuppressWarnings("unchecked")
    public void execute(Map<String, Object> action) {
        if (action == null) return;

        String type = (String) action.get("action");
        if (type == null) return;

        switch (type) {
            case "executeCommand" -> {
                List<?> args = (List<?>) action.get("args");
                if (args != null && !args.isEmpty()) {
                    api.executeCommand(args.get(0).toString());
                }
            }
            case "closeScreen" -> api.closeScreen();
            case "showToast" -> {
                List<?> args = (List<?>) action.get("args");
                if (args != null && !args.isEmpty()) {
                    api.showToast(args.get(0).toString());
                }
            }
            case "playSound" -> {
                List<?> args = (List<?>) action.get("args");
                if (args != null && !args.isEmpty()) {
                    float vol = args.size() > 1 ? toFloat(args.get(1), 1f) : 1f;
                    float pitch = args.size() > 2 ? toFloat(args.get(2), 1f) : 1f;
                    api.playSound(args.get(0).toString(), vol, pitch);
                }
            }
            case "callback" -> {
                Object id = action.get("id");
                if (id != null) {
                    List<String> data = new ArrayList<>();
                    if (action.get("data") instanceof List<?> raw) {
                        for (Object o : raw) data.add(String.valueOf(o));
                    }
                    api.sendServerEvent(id.toString(), data);
                }
            }
            case "chain" -> {
                List<?> actions = (List<?>) action.get("actions");
                if (actions != null) {
                    for (Object item : actions) {
                        if (item instanceof Map<?, ?> m) {
                            execute((Map<String, Object>) m);
                        }
                    }
                }
            }
            default -> System.err.println("[WindUI] Unknown action: " + type);
        }
    }

    private static float toFloat(Object o, float fallback) {
        if (o instanceof Number n) return n.floatValue();
        try { return Float.parseFloat(String.valueOf(o)); } catch (NumberFormatException e) { return fallback; }
    }
}
