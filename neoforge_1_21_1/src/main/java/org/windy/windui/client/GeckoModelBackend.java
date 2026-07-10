package org.windy.windui.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MinecraftSurfaceView;
import icyllis.modernui.view.View;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.windy.windui.core.parser.WidgetNode;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * GeckoLib 3D 模型后端（NeoForge 1.21.1）。<b>软依赖隔离类</b>：所有 GeckoLib 符号只出现在本类，
 * 仅当 {@code ModList.isLoaded("geckolib")} 为真时才被 {@link ModelRendererImpl} 主动 class-load，
 * 故 GeckoLib 缺席时本类永不加载、不会 {@code NoClassDefFoundError}。<b>不打包 GeckoLib、无反射</b>。
 * <p>
 * 渲染行为仍需真机验证：MinecraftSurfaceView 表面、GeckoLib GUI 3D 渲染、Lighting、PoseStack 和
 * buffer 的组合只能在真实客户端环境确认。
 * <p>
 * Model3D 节点字段（type="geckolib"）：
 * <ul>
 *     <li>{@code geo}：模型资源，如 {@code ns:geo/xxx.geo.json}</li>
 *     <li>{@code texture}：贴图资源，如 {@code ns:textures/xxx.png}</li>
 *     <li>{@code animFile}：动画文件，可选</li>
 *     <li>{@code animation}：循环动画名，可选</li>
 *     <li>{@code modelScale}：Surface 内的模型缩放，默认 24</li>
 *     <li>{@code spin}：是否自转，默认 true</li>
 * </ul>
 * 通用 {@code scale} 由共享 ViewFactory 作为 View 变换处理，不再同时用于模型矩阵。
 */
final class GeckoModelBackend {

    private static final float DEFAULT_MODEL_SCALE = 24f;

    private GeckoModelBackend() {
    }

    /** 建 GeckoLib 模型 SurfaceView；缺 geo/texture 或资源非法返 null（优雅降级）。 */
    static View create(Context ctx, WidgetNode node) {
        String geo = node.getString("geo", null);
        String texture = node.getString("texture", null);
        if (geo == null || texture == null) {
            return null;
        }

        ResourceLocation geoRl = ResourceLocation.tryParse(geo);
        ResourceLocation texRl = ResourceLocation.tryParse(texture);
        if (geoRl == null || texRl == null) {
            return null;
        }

        String animFile = node.getString("animFile", null);
        ResourceLocation animRl =
                animFile == null || animFile.isBlank()
                        ? null
                        : ResourceLocation.tryParse(animFile);
        if (animFile != null && !animFile.isBlank() && animRl == null) {
            return null;
        }

        WindUiGeoObject object = new WindUiGeoObject(node.getString("animation", null));
        WindUiGeoModel model = new WindUiGeoModel(geoRl, texRl, animRl);
        GeoObjectRenderer<WindUiGeoObject> renderer = new GeoObjectRenderer<>(model);

        float modelScale = modelScale(node);
        boolean spin = node.getBool("spin", true);

        var surface = new MinecraftSurfaceView(ctx);
        surface.setRenderer(new GeckoSurfaceRenderer(renderer, object, modelScale, spin));
        return surface;
    }

    /**
     * 新契约读取 modelScale。旧节点只有 scale 时，让共享 ViewFactory 独自处理该 View 缩放，
     * GeckoLib 内部保持 1，避免历史上的双重缩放。
     */
    private static float modelScale(WidgetNode node) {
        if (!node.props().containsKey("modelScale")) {
            return node.props().containsKey("scale") ? 1f : DEFAULT_MODEL_SCALE;
        }

        float value = node.getFloat("modelScale", DEFAULT_MODEL_SCALE);
        return Float.isFinite(value) && value > 0f ? value : DEFAULT_MODEL_SCALE;
    }

    /** 最小 {@link GeoAnimatable}：一个循环动画的独立对象（无世界，tick 用系统时间推进）。 */
    private static final class WindUiGeoObject implements GeoAnimatable {
        private final String animation;
        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
        private final long start = System.currentTimeMillis();

        WindUiGeoObject(String animation) {
            this.animation = animation;
        }

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar reg) {
            if (animation == null || animation.isBlank()) {
                return;
            }
            reg.add(new AnimationController<>(
                    this,
                    "main",
                    state -> state.setAndContinue(
                            RawAnimation.begin().thenLoop(animation))));
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() {
            return cache;
        }

        @Override
        public double getTick(Object object) {
            return (System.currentTimeMillis() - start) / 50.0;
        }
    }

    /** 由三个资源路径构成的 {@link GeoModel}。 */
    private static final class WindUiGeoModel extends GeoModel<WindUiGeoObject> {
        private final ResourceLocation geo;
        private final ResourceLocation texture;
        private final ResourceLocation animation;

        WindUiGeoModel(
                ResourceLocation geo,
                ResourceLocation texture,
                ResourceLocation animation) {
            this.geo = geo;
            this.texture = texture;
            this.animation = animation;
        }

        @Override
        public ResourceLocation getModelResource(WindUiGeoObject animatable) {
            return geo;
        }

        @Override
        public ResourceLocation getTextureResource(WindUiGeoObject animatable) {
            return texture;
        }

        @Override
        public ResourceLocation getAnimationResource(WindUiGeoObject animatable) {
            return animation;
        }
    }

    /** 在 MinecraftSurfaceView 中执行 GUI 3D 渲染。 */
    private record GeckoSurfaceRenderer(
            GeoObjectRenderer<WindUiGeoObject> renderer,
            WindUiGeoObject object,
            float modelScale,
            boolean spin) implements MinecraftSurfaceView.Renderer {

        @Override
        public void onSurfaceChanged(int width, int height) {
        }

        @Override
        public void onDraw(
                GuiGraphics gg,
                int width,
                int height,
                float partialTick,
                double mouseX,
                float alpha) {
            PoseStack pose = gg.pose();
            pose.pushPose();
            try {
                pose.translate(width / 2.0, height * 0.85, 100.0);
                pose.scale(modelScale, -modelScale, modelScale);

                if (spin) {
                    float angle = (System.currentTimeMillis() % 3600L) / 10f;
                    pose.mulPose(Axis.YP.rotationDegrees(angle));
                }

                MultiBufferSource.BufferSource buffer = gg.bufferSource();
                Lighting.setupForEntityInInventory();
                try {
                    renderer.render(
                            pose,
                            object,
                            buffer,
                            null,
                            null,
                            LightTexture.FULL_BRIGHT,
                            partialTick);
                    buffer.endBatch();
                } finally {
                    Lighting.setupFor3DItems();
                }
            } finally {
                pose.popPose();
            }
        }
    }
}
