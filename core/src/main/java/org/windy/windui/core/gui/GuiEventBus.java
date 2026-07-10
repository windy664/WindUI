package org.windy.windui.core.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * SDUI 按钮回调事件总线。平台无关（泛型化玩家类型 {@code P}）。
 * <p>
 * 客户端按钮的 {@code callback} 动作把 {@code eventId + data} 回传服务端，
 * 平台侧收到后调 {@link #dispatch} 派发给注册的处理器。
 * <p>
 * Velocity 用 {@code GuiEventBus<Player>}；未来 Bukkit 用 {@code GuiEventBus<org.bukkit.entity.Player>}——
 * 业务逻辑（买卖/翻页）注册到同一总线，与平台解耦。
 *
 * @param <P> 平台玩家类型
 */
public final class GuiEventBus<P> {

    private final Map<String, BiConsumer<P, List<String>>> handlers = new HashMap<>();

    /** 注册某事件 id 的处理器。 */
    public void register(String eventId, BiConsumer<P, List<String>> handler) {
        handlers.put(eventId, handler);
    }

    /**
     * 派发事件。
     *
     * @return 是否存在对应处理器（用于回显未处理事件）
     */
    public boolean dispatch(String eventId, P player, List<String> data) {
        BiConsumer<P, List<String>> handler = handlers.get(eventId);
        if (handler == null) return false;
        handler.accept(player, data);
        return true;
    }

    public Set<String> ids() {
        return new TreeSet<>(handlers.keySet());
    }
}
