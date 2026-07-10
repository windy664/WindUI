package org.windy.windui.core.protocol;

/**
 * WindUI 网络协议常量。两端（NeoForge / Velocity）共用。
 */
public final class WindUIProtocol {

    /**
     * 协议版本。<b>每次线格式/能力有增量都 +1</b>，并在 {@link ProtocolFeatures} 里登记新特性的引入版本。
     * 客户端登录后经 {@code /windui hello <version>} 上报本值，服务端据此按 {@link ProtocolFeatures} 门控，
     * 对老客户端优雅降级（不发它不认得的特性）。这是 WindUI 跨 MC 版本 / 协议演进的前向兼容基石。
     */
    public static final int VERSION = 3;

    /** UI 界面 JSON 下发频道。 */
    public static final String CHANNEL = "windui:ui";

    /**
     * 图片资源频道（服务端→客户端发 PNG 字节）。独立频道，UI 帧格式一字节不动、零风险。
     * 线格式见 {@link AssetCodec}。
     */
    public static final String ASSET_CHANNEL = "windui:asset";

    /**
     * 控制频道（服务端→客户端下发一批「客户端动作」）。用于服务端主动触发的效果：
     * 脚本 {@code onEvent} 产出的 close/command/toast/sound 等，服务端据此推一批
     * {@link org.windy.windui.core.script.ActionExecutor} 能识别的结构化动作，客户端照单执行。
     * 独立频道，与 UI/asset 帧互不影响。线格式见 {@link ControlCodec}。
     */
    public static final String CTRL_CHANNEL = "windui:ctrl";

    /**
     * 增量更新频道（服务端→客户端按组件 {@code id} 局部改属性，不重推整树）。用于余额/状态/进度等
     * 高频小改动，避免整界面重渲染的闪烁与开销。独立频道。线格式见 {@link PatchCodec}。
     */
    public static final String PATCH_CHANNEL = "windui:patch";

    /**
     * 事件回程频道（客户端→服务端,serverbound）。按钮回调/输入回读等经此上报,替代早期滥用的
     * {@code /windui event} 命令(仍保留作兜底)。客户端走裸 Netty 出站写线格式,服务端(Velocity)
     * 注册此频道收 PluginMessage。线格式见 {@link EventCodec}。
     */
    public static final String EVENT_CHANNEL = "windui:event";

    /**
     * HUD 频道(服务端→客户端推非模态常驻 HUD)。客户端用 vanilla GuiGraphics 在 HUD 渲染事件里直绘
     * (不开 Screen、不碰 ModernUI 离屏),内容是锚定面板 + SDUI 子树。线格式见 {@link HudCodec}。
     */
    public static final String HUD_CHANNEL = "windui:hud";

    private WindUIProtocol() {
    }
}
