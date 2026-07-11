package org.windy.windui.server;

import java.util.List;
import java.util.UUID;

/**
 * 平台适配器：{@link WindUiRuntime} 装配了所有平台无关的东西（registries/driver/WS/token/事件路由），
 * 只把这几件真正平台特定的事委托给具体平台（velocity / bukkit / 未来其它）。
 * <p>
 * 这样"共享装配"只写一遍在 server_core，各平台插件主类退化成薄壳：建一个 adapter、
 * {@link WindUiRuntime#start} 起来、再挂各自的进服引导 + 命令即可。
 */
public interface PlatformAdapter {

    /**
     * 为在线玩家构造平台 {@link UiHost}（下发通道 + ctx + 占位符的落点）；玩家离线返回 {@code null}。
     * 事件回程时 runtime 用它拿到 host 交给 {@link GuiDriver}。
     */
    UiHost hostFor(UUID player);

    /**
     * {@link GuiDriver#event} 没处理该事件时的业务回退分派（平台自己的 GuiEventBus / 业务处理器）。
     * 例如 JSON 界面的结构化按钮回调。
     */
    void onUnhandledEvent(UUID player, String eventId, List<String> data);

    /** 客户端握手就绪（上报协议版本）通知；平台可据此打日志。默认空实现。 */
    default void onClientReady(UUID player, int version) {
    }

    /** 该玩家 WS 连接断开通知；平台可据此打日志。默认空实现。 */
    default void onClientClosed(UUID player) {
    }

    /** 信息日志（不绑定具体 logger 实现）。 */
    void logInfo(String message);

    /** 警告日志（可带异常）。 */
    void logWarn(String message, Throwable error);
}
