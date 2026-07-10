package org.windy.windui.server;

import java.util.Map;
import java.util.UUID;

/**
 * 平台无关的「一个玩家会话」抽象，由各服务端平台（velocity/未来 bukkit）实现。
 * <p>
 * {@link GuiDriver} 只依赖此接口驱动界面：取上下文数据、套占位符、发 UI、发消息，
 * 从而 open/event/效果分派的逻辑全在 server_core 共享，平台只写这层薄 glue。
 */
public interface UiHost {

    /** 玩家唯一标识（用于追踪当前打开的界面）。 */
    UUID id();

    /** 注入 JS 脚本的上下文数据（player/uuid/server/online/ping 等）。 */
    Map<String, Object> ctx();

    /** 对 UI JSON 套用平台占位符（%player_name% 等）。 */
    String applyPlaceholders(String json);

    /** 把渲染好的 UI JSON 下发给该玩家客户端。 */
    void sendUi(String json);

    /** 在 asset 频道下发一张图片（{@link org.windy.windui.core.protocol.AssetCodec} 编码后的字节）。 */
    void sendAsset(byte[] payload);

    /** 给该玩家发聊天消息（支持 § 颜色码）。 */
    void sendMessage(String legacyText);

    /**
     * 在控制频道下发一批客户端动作（{@link org.windy.windui.core.protocol.ControlCodec} 编码后的字节）。
     * 用于服务端主动触发的效果：关屏 / 执行命令 / toast / 音效。
     */
    void sendControl(byte[] payload);

    /**
     * 在增量更新频道下发一批组件属性变更（{@link org.windy.windui.core.protocol.PatchCodec} 编码后的字节）。
     * 客户端按 id 局部改属性，不重推整树。
     */
    void sendPatch(byte[] payload);

    /**
     * 在 HUD 频道下发一组锚定面板（{@link org.windy.windui.core.protocol.HudCodec} 编码后的字节）。
     * 客户端用 vanilla GuiGraphics 直绘非模态 HUD;空面板 = 清空。
     */
    void sendHud(byte[] payload);
}
