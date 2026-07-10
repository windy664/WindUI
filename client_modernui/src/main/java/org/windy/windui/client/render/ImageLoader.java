package org.windy.windui.client.render;

import icyllis.modernui.graphics.Image;

/**
 * 图片加载抽象。由各 MC 版本的平台模块注入实现。
 * <p>
 * 存在的原因：ModernUI 加载 MC 纹理的 {@code ImageStore} 在不同 ModernUI 版本可能位于不同包
 * （3.13 在 {@code icyllis.modernui.mc}，3.11 在 {@code icyllis.modernui.graphics}）。
 * 把它抽成注入接口后，共享渲染层（{@link ViewFactory}/{@link SDUIScreen}）就完全不引用 mc 层，
 * 只依赖纯 ModernUI 的 view/graphics 类，从而可跨版本编译一次、多版本复用。
 */
@FunctionalInterface
public interface ImageLoader {

    /**
     * 从 MC 资源加载纹理为 ModernUI Image。须在 ModernUI UI 线程调用。
     *
     * @param namespace 资源命名空间，如 "minecraft"
     * @param path      资源路径，如 "textures/item/diamond.png"
     * @return 加载到的 Image；失败返回 null
     */
    Image load(String namespace, String path);
}
