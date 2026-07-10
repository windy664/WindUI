package org.windy.windui.core.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构化动作执行器——替代客户端 JS 引擎。
 * <p>
 * 按钮行为通过 JSON 定义，例如：
 * <pre>
 * { "action": "executeCommand", "args": ["/spawn"] }
 * { "action": "closeScreen" }
 * { "action": "showToast", "args": ["正在传送..."] }
 * { "action": "callback", "id": "buy_diamond", "data": ["1"] }
 * { "action": "chain", "actions": [...] }
 * </pre>
 * 安全、无反射、无脚本引擎依赖。
 * <p>
 * 执行器把所有入站动作视为不可信数据：字段类型错误时安全忽略，并为嵌套深度和动作总数设置独立预算，
 * 避免超深 {@code chain} 导致栈溢出，或单次控制消息执行过多客户端操作。
 */
public final class ActionExecutor {

    /** 一棵动作树允许的最大嵌套深度。根动作深度为 0。 */
    private static final int MAX_CHAIN_DEPTH = 32;

    /** 一次 {@link #execute} 调用最多执行的动作对象数量。 */
    private static final int MAX_ACTIONS_PER_EXECUTION = 256;

    /** callback 最多允许携带的数据项数量，与 EventCodec 的业务上限保持一致。 */
    private static final int MAX_CALLBACK_DATA = 256;

    /** UI 音效音量的安全范围。 */
    private static final float MIN_VOLUME = 0f;
    private static final float MAX_VOLUME = 4f;

    /** UI 音效音高的安全范围。 */
    private static final float MIN_PITCH = 0.01f;
    private static final float MAX_PITCH = 4f;

    private final SafeClientAPI api;

    public ActionExecutor(SafeClientAPI api) {
        if (api == null) {
            throw new IllegalArgumentException("SafeClientAPI 不能为空");
        }
        this.api = api;
    }

    /**
     * 执行一个结构化动作。
     * <p>
     * 每次调用都会创建独立预算；动作类型或字段类型非法时忽略该动作，不把异常传播到 UI 事件线程。
     *
     * @param action 动作 Map（从 JSON 解析而来）
     */
    public void execute(Map<String, Object> action) {
        execute(action, 0, new ExecutionBudget());
    }

    private void execute(
            Map<?, ?> action,
            int depth,
            ExecutionBudget budget) {
        if (action == null || depth > MAX_CHAIN_DEPTH) {
            return;
        }
        if (++budget.actions > MAX_ACTIONS_PER_EXECUTION) {
            return;
        }

        Object rawType = action.get("action");
        if (!(rawType instanceof String type) || type.isBlank()) {
            return;
        }

        try {
            switch (type) {
                case "executeCommand" -> executeCommand(action);
                case "closeScreen" -> api.closeScreen();
                case "showToast" -> showToast(action);
                case "playSound" -> playSound(action);
                case "callback" -> callback(action);
                case "chain" -> executeChain(action, depth, budget);
                default -> System.err.println("[WindUI] Unknown action: " + type);
            }
        } catch (RuntimeException e) {
            // 平台实现或单条畸形动作失败时隔离本条动作，不能让整个控制包或 UI 事件处理崩溃。
            System.err.println(
                    "[WindUI] Action execution failed: "
                            + type
                            + " ("
                            + e.getClass().getSimpleName()
                            + ": "
                            + safeMessage(e)
                            + ")");
        }
    }

    private void executeCommand(Map<?, ?> action) {
        String command = firstStringArgument(action);
        if (command == null || command.isBlank()) {
            return;
        }
        api.executeCommand(command);
    }

    private void showToast(Map<?, ?> action) {
        String text = firstStringArgument(action);
        if (text == null) {
            return;
        }
        api.showToast(text);
    }

    private void playSound(Map<?, ?> action) {
        List<?> args = listField(action, "args");
        if (args == null || args.isEmpty()) {
            return;
        }

        String id = stringValue(args.get(0));
        if (id == null || id.isBlank()) {
            return;
        }

        float volume = args.size() > 1
                ? finiteFloat(args.get(1), 1f)
                : 1f;
        float pitch = args.size() > 2
                ? finiteFloat(args.get(2), 1f)
                : 1f;

        volume = clamp(volume, MIN_VOLUME, MAX_VOLUME);
        pitch = clamp(pitch, MIN_PITCH, MAX_PITCH);
        api.playSound(id, volume, pitch);
    }

    private void callback(Map<?, ?> action) {
        String eventId = stringValue(action.get("id"));
        if (eventId == null || eventId.isBlank()) {
            return;
        }

        List<String> data = new ArrayList<>();
        List<?> rawData = listField(action, "data");
        if (rawData != null) {
            int limit = Math.min(rawData.size(), MAX_CALLBACK_DATA);
            for (int i = 0; i < limit; i++) {
                Object value = rawData.get(i);
                if (value != null) {
                    data.add(String.valueOf(value));
                }
            }
        }

        api.sendServerEvent(eventId, data);
    }

    private void executeChain(
            Map<?, ?> action,
            int depth,
            ExecutionBudget budget) {
        if (depth >= MAX_CHAIN_DEPTH
                || budget.actions >= MAX_ACTIONS_PER_EXECUTION) {
            return;
        }

        List<?> actions = listField(action, "actions");
        if (actions == null) {
            return;
        }

        for (Object item : actions) {
            if (budget.actions >= MAX_ACTIONS_PER_EXECUTION) {
                return;
            }
            if (item instanceof Map<?, ?> child) {
                execute(child, depth + 1, budget);
            }
        }
    }

    private static String firstStringArgument(Map<?, ?> action) {
        List<?> args = listField(action, "args");
        if (args == null || args.isEmpty()) {
            return null;
        }
        return stringValue(args.get(0));
    }

    private static List<?> listField(Map<?, ?> action, String field) {
        Object value = action.get(field);
        return value instanceof List<?> list ? list : null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static float finiteFloat(Object value, float fallback) {
        float parsed;
        if (value instanceof Number number) {
            parsed = number.floatValue();
        } else {
            try {
                parsed = Float.parseFloat(String.valueOf(value));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return Float.isFinite(parsed) ? parsed : fallback;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "无详细信息" : message;
    }

    /** 一次顶层 execute 调用共享的动作数量预算。 */
    private static final class ExecutionBudget {
        private int actions;
    }
}
