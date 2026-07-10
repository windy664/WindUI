package org.windy.windui.core.ws;

/**
 * WindUI over WebSocket 的应用层协议常量。
 * <p>
 * 每条 WebSocket 消息的负载 = {@code [1 字节 channel][data]}。channel 决定 data 的含义，
 * 复用现有的各 Codec（{@link org.windy.windui.core.protocol.AssetCodec} 等）产出/解析 data。
 * <p>
 * WebSocket 本身给出消息边界，故这里不再需要长度前缀 —— 一条 WS 消息就是一条应用消息。
 * 这套通道字节取代了旧的裸 Netty 频道字符串（{@code windui:ui} 等），传输层彻底脱离 MC 封包管线。
 */
public final class WsProtocol {

    // ===== 服务端 → 客户端（下行） =====

    /** 完整界面 JSON（UTF-8 文本）。对应旧 {@code windui:ui}。 */
    public static final byte UI = 0x01;

    /** 一张图片（{@link org.windy.windui.core.protocol.AssetCodec} 帧字节）。对应旧 {@code windui:asset}。 */
    public static final byte ASSET = 0x02;

    /** 一批客户端动作（{@link org.windy.windui.core.protocol.ControlCodec} 字节）。对应旧 {@code windui:ctrl}。 */
    public static final byte CTRL = 0x03;

    /** 一批增量更新（{@link org.windy.windui.core.protocol.PatchCodec} 字节）。对应旧 {@code windui:patch}。 */
    public static final byte PATCH = 0x04;

    /** 一组 HUD 面板（{@link org.windy.windui.core.protocol.HudCodec} 字节）。对应旧 {@code windui:hud}。 */
    public static final byte HUD = 0x05;

    // ===== 客户端 → 服务端（上行） =====

    /**
     * 鉴权。连接后的<b>第一条</b>消息，负载 = token 的 UTF-8 字节。服务端据此把连接绑定到玩家 UUID；
     * 校验失败即关闭连接。取代旧的裸 netty 无鉴权 + UUID 猜测。
     */
    public static final byte AUTH = 0x10;

    /** 协议版本上报，负载 = 4 字节大端 int（{@link org.windy.windui.core.protocol.WindUIProtocol#VERSION}）。 */
    public static final byte HELLO = 0x11;

    /** 界面事件回程（按钮回调/输入回读），负载 = {@link org.windy.windui.core.protocol.EventCodec} 字节。 */
    public static final byte EVENT = 0x12;

    private WsProtocol() {
    }
}
