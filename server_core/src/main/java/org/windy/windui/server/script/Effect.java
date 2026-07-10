package org.windy.windui.server.script;

/**
 * 脚本 {@code onEvent} 产出的一个副作用指令，由平台侧执行。
 * <p>
 * 平台无关的纯数据；平台 glue（velocity/bukkit）按 {@link #type} 分派执行（发消息/重推/打开等）。
 *
 * @param type 效果类型：{@code "message"}（聊天）/ {@code "open"} / {@code "rerender"}（重推 UI）/
 *             {@code "toast"} / {@code "close"} / {@code "command"} / {@code "sound"}（经控制频道让客户端执行）/
 *             {@code "patch"}（经增量频道按 id 局部改属性）
 * @param arg  参数（消息文本 / 目标界面 id / 命令 / 音效 id / patch 的 {@code {id,props}} JSON）；无参效果为 {@code null}
 */
public record Effect(String type, String arg) {
}
