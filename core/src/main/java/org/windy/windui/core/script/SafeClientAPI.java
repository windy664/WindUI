package org.windy.windui.core.script;

import java.util.List;

/**
 * 暴露给动作执行器的安全客户端 API 接口。
 * <p>
 * 核心模块只定义接口；NeoForge 端提供实现（{@code SafeClientImpl}）。
 * 每个方法对应一种白名单动作，无任意代码执行。
 */
public interface SafeClientAPI {

    /** 关闭当前 SDUI 界面 */
    void closeScreen();

    /** 在客户端显示一个 toast 提示 */
    void showToast(String text);

    /** 执行一条客户端命令（如 "/spawn"） */
    void executeCommand(String command);

    /** 在客户端播放一个 UI 音效（如 "minecraft:ui.button.click"），volume/pitch 默认 1.0 */
    void playSound(String id, float volume, float pitch);

    /**
     * 触发一个服务端事件（按钮回调），把 eventId + data 回传服务端由其处理。
     * 这是 SDUI 的"回程"通道——服务端据此做买卖、翻页等权威操作。
     */
    void sendServerEvent(String eventId, List<String> data);
}
