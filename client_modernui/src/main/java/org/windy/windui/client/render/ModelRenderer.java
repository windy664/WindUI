package org.windy.windui.client.render;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.View;
import org.windy.windui.core.parser.WidgetNode;

/**
 * 3D 模型/实体渲染抽象。由各 MC 版本的平台模块注入实现。
 * <p>
 * 存在的原因：屏内 3D 渲染必须用 ModernUI-MC 的 {@code MinecraftSurfaceView}（在 {@code icyllis.modernui.mc}
 * 包）承载 MC 的渲染管线，且渲染 API（GuiGraphicsExtractor / GuiGraphics / GeckoLib）在不同版本漂移。
 * 把它抽成注入接口后，共享渲染层（{@link ViewFactory}/{@link SDUIScreen}）保持 mc-free，只见 ModernUI 的
 * {@link View}，各版本各写一份实现（vanilla 物品/实体后端 + GeckoLib 软依赖后端）。
 * <p>
 * 对应 {@code Model3D} 组件节点，字段约定：{@code type}（后端：item/entity/geckolib，默认 item）、
 * {@code model}（物品/实体 id 或 GeckoLib 模型 id）、{@code animation}（GeckoLib 动画名）、
 * {@code width}/{@code height}（面板尺寸）、{@code rotation}/{@code scale}（可选）。
 */
@FunctionalInterface
public interface ModelRenderer {

    /**
     * 为一个 {@code Model3D} 节点创建承载 3D 渲染的 View（各版本用 {@code MinecraftSurfaceView}）。
     * 无法渲染（后端不支持/资源缺失）时返回 {@code null}，该节点被跳过（优雅降级）。
     *
     * @param ctx  ModernUI 上下文（建 View 用）
     * @param node Model3D 节点（读 type/model/animation 等字段）
     * @return 承载渲染的 View；无法渲染返 null
     */
    View createModelView(Context ctx, WidgetNode node);
}
