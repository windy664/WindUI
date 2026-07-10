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
 * ⚠️ <b>渲染行为只能真机验</b>：MinecraftSurfaceView 表面 + GeckoLib GUI-3D 渲染（Lighting/PoseStack/buffer）
 * 这套 MC 渲染内部只做了编译校验，定位/缩放/打光多半需真机微调（本类把 scale/spin 做成可调 props）。
 * <p>
 * Model3D 节点字段（type="geckolib"）：{@code geo}（模型资源，如 "ns:geo/xxx.geo.json"）、
 * {@code texture}（贴图，如 "ns:textures/xxx.png"）、{@code animFile}（动画文件，可选）、
 * {@code animation}（循环动画名，可选）、{@code scale}（缩放，默认 24）、{@code spin}（是否自转，默认 true）。
 */
final class GeckoModelBackend {

    private GeckoModelBackend() {
    }

    /** 建 GeckoLib 模型 SurfaceView；缺 geo/texture 或资源非法返 null（优雅降级）。 */
    static View create(Context ctx, WidgetNode node) {
        String geo = node.getString("geo", null);
        String texture = node.getString("texture", null);
        if (geo == null || texture == null) return null; // GeckoLib 后端必须给 geo + texture
        ResourceLocation geoRl = ResourceLocation.tryParse(geo);
        ResourceLocation texRl = ResourceLocation.tryParse(texture);
        if (geoRl == null || texRl == null) return null;
        String animFile = node.getString("animFile", null);
        ResourceLocation animRl = animFile == null ? null : ResourceLocation.tryParse(animFile);

        WindUiGeoObject object = new WindUiGeoObject(node.getString("animation", null));
        WindUiGeoModel model = new WindUiGeoModel(geoRl, texRl, animRl);
        GeoObjectRenderer<WindUiGeoObject> renderer = new GeoObjectRenderer<>(model);

        var surface = new MinecraftSurfaceView(ctx);
        surface.setRenderer(new GeckoSurfaceRenderer(
                renderer, object, node.getFloat("scale", 24f), node.getBool("spin", true)));
        return surface;
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
            if (animation == null || animation.isEmpty()) return;
            reg.add(new AnimationController<>(this, "main", state ->
                    state.setAndContinue(RawAnimation.begin().thenLoop(animation))));
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() {
            return cache;
        }

        @Override
        public double getTick(Object o) {
            return (System.currentTimeMillis() - start) / 50.0; // 20 tps
        }
    }

    /** 由三个资源路径构成的 {@link GeoModel}。 */
    private static final class WindUiGeoModel extends GeoModel<WindUiGeoObject> {
        private final ResourceLocation geo;
        private final ResourceLocation texture;
        private final ResourceLocation anim;

        WindUiGeoModel(ResourceLocation geo, ResourceLocation texture, ResourceLocation anim) {
            this.geo = geo;
            this.texture = texture;
            this.anim = anim;
        }

        @Override
        public ResourceLocation getModelResource(WindUiGeoObject a) {
            return geo;
        }

        @Override
        public ResourceLocation getTextureResource(WindUiGeoObject a) {
            return texture;
        }

        @Override
        public ResourceLocation getAnimationResource(WindUiGeoObject a) {
            return anim;
        }
    }

    /** 在 MinecraftSurfaceView 里做 GUI-3D 渲染（仿 InventoryScreen 的实体渲染变换）。 */
    private record GeckoSurfaceRenderer(GeoObjectRenderer<WindUiGeoObject> renderer,
                                        WindUiGeoObject object, float scale, boolean spin)
            implements MinecraftSurfaceView.Renderer {
        @Override
        public void onSurfaceChanged(int width, int height) {
        }

        @Override
        public void onDraw(GuiGraphics gg, int width, int height, float partialTick, double mouseX, float alpha) {
            PoseStack pose = gg.pose();
            pose.pushPose();
            pose.translate(width / 2.0, height * 0.85, 100.0); // 面板中下部，z 拉深度
            pose.scale(scale, -scale, scale);                  // Y 取负：GUI y-down vs 模型 y-up
            if (spin) {
                float angle = (System.currentTimeMillis() % 3600L) / 10f; // 每 3.6s 一圈
                pose.mulPose(Axis.YP.rotationDegrees(angle));
            }
            MultiBufferSource.BufferSource buffer = gg.bufferSource();
            Lighting.setupForEntityInInventory();
            renderer.render(pose, object, buffer, null, null, LightTexture.FULL_BRIGHT, partialTick);
            buffer.endBatch();
            Lighting.setupFor3DItems();
            pose.popPose();
        }
    }
}
