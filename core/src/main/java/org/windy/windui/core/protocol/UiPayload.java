package org.windy.windui.core.protocol;

/**
 * 服务端下发给客户端的 UI 数据包。
 *
 * @param uiJson AI 生成的 JSON 布局树（内含 onClick 动作定义）
 */
public record UiPayload(String uiJson) {
}
