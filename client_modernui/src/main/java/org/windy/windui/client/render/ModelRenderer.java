package org.windy.windui.client.render;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.View;
import org.windy.windui.core.parser.WidgetNode;

/**
 * 3D 模型/实体渲染抽象。由各 MC 版本的平台模块注入实现。
 * <p>
 * 存在的原因：屏内 3D 渲染必须用 ModernUI-MC 的 {@code MinecraftSurfaceView} 承载 MC 渲染管线，
 * 且渲染 API（GuiGraphicsExtractor、GuiGraphics、GeckoLib）在不同版本间变化较大。
 * 抽成注入接口后，共享渲染层保持 mc-free，只依赖 ModernUI 的 {@link View}。
 * <p>
 * 对应 {@code Model3D} 组件节点，字段约定：
 * <ul>
 *     <li>{@code type}：后端，item/entity/geckolib，默认 item</li>
 *     <li>{@code model}：物品或实体 id</li>
 *     <li>{@code animation}：GeckoLib 动画名</li>
 *     <li>{@code width}/{@code height}：SurfaceView 布局尺寸</li>
 *     <li>{@code modelScale}：Surface 内 3D 内容缩放</li>
 *     <li>{@code scale}：通用 ModernUI View 缩放，不等同于 modelScale</li>
 * </ul>
 * 新界面应使用 {@code modelScale} 控制模型大小。旧界面中的 {@code scale} 继续保持 View 变换语义，
 * 平台后端不得再把同一个旧 scale 同时应用到模型矩阵。
 */
@FunctionalInterface
public interface ModelRenderer {

    /**
     * 为一个 {@code Model3D} 节点创建承载 3D 渲染的 View。
     * 无法渲染（后端不支持、软依赖缺失或资源非法）时返回 {@code null}，由调用方优雅跳过该节点。
     *
     * @param ctx  ModernUI 上下文
     * @param node Model3D 节点
     * @return 承载渲染的 View；无法渲染时返回 null
     */
    View createModelView(Context ctx, WidgetNode node);
}
