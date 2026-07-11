package org.windy.windui.client;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MinecraftSurfaceView;
import icyllis.modernui.view.View;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import org.windy.windui.client.render.ModelRenderer;
import org.windy.windui.core.parser.WidgetNode;

/**
 * {@link ModelRenderer} 的 NeoForge 1.21.1 实现（<b>平台特定 glue</b>）。
 * <p>
 * 用 ModernUI-MC 的 {@code MinecraftSurfaceView} 承载 MC 渲染管线，在其
 * {@code onDraw(GuiGraphics,…)} 中绘制 MC 内容。
 * <p>
 * 当前提供 vanilla 物品/实体后端和 GeckoLib 软依赖后端。{@code modelScale} 只控制 Surface 内的
 * 3D 内容尺寸；通用 {@code scale} 仍由共享 {@code ViewFactory} 作为 View 变换处理。
 */
public final class ModelRendererImpl implements ModelRenderer {

    /** item 后端在未声明 modelScale 时使用的内容缩放。 */
    private static final float DEFAULT_ITEM_MODEL_SCALE = 3f;
    /** entity 后端传给原版物品栏实体渲染器的默认像素尺寸。 */
    private static final float DEFAULT_ENTITY_MODEL_SCALE = 30f;

    @Override
    public View createModelView(Context ctx, WidgetNode node) {
        String type = node.getString("type", "item");
        if ("geckolib".equals(type)) {
            // 软依赖门控：GeckoLib 缺席则不 class-load GeckoModelBackend（其内含 GeckoLib 符号）。
            if (!ModList.get().isLoaded("geckolib")) {
                return null;
            }
            return GeckoModelBackend.create(ctx, node);
        }

        if ("entity".equals(type)) {
            LivingEntity entity = resolveEntity(node.getString("model", "minecraft:zombie"));
            if (entity == null) {
                return null;
            }
            float modelScale = modelScale(node, DEFAULT_ENTITY_MODEL_SCALE, false);
            var surface = new MinecraftSurfaceView(ctx);
            surface.setRenderer(new EntitySurfaceRenderer(entity, modelScale));
            return surface;
        }

        if (!"item".equals(type)) {
            return null;
        }

        ItemStack stack = resolveItem(node.getString("model", "minecraft:diamond"));
        float modelScale = modelScale(node, DEFAULT_ITEM_MODEL_SCALE, true);

        var surface = new MinecraftSurfaceView(ctx);
        surface.setRenderer(new ItemSurfaceRenderer(stack, modelScale));
        return surface;
    }

    private static ItemStack resolveItem(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        var item = loc == null ? Items.DIAMOND : BuiltInRegistries.ITEM.get(loc);
        return new ItemStack(item);
    }

    private static LivingEntity resolveEntity(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        var level = Minecraft.getInstance().level;
        if (loc == null || level == null) {
            return null;
        }
        var entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(loc).orElse(null);
        if (entityType == null) {
            return null;
        }
        return entityType.create(level) instanceof LivingEntity living ? living : null;
    }

    /**
     * 读取内容缩放。
     * <p>
     * 新契约使用 {@code modelScale}。若节点仍只声明旧 {@code scale}，内部缩放保持 1，
     * 让共享 ViewFactory 独自应用旧的 View 缩放，避免历史实现中同一个 scale 被应用两次。
     */
    private static float modelScale(WidgetNode node, float fallback, boolean preserveLegacyScale) {
        if (!node.props().containsKey("modelScale")) {
            return preserveLegacyScale && node.props().containsKey("scale") ? 1f : fallback;
        }

        float value = node.getFloat("modelScale", fallback);
        return Float.isFinite(value) && value > 0f ? value : fallback;
    }

    /** vanilla 物品渲染：画进 SurfaceView 中心，并以 modelScale 缩放物品内容。 */
    private record ItemSurfaceRenderer(ItemStack stack, float modelScale)
            implements MinecraftSurfaceView.Renderer {

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
            var pose = gg.pose();
            pose.pushPose();
            try {
                pose.translate(width / 2.0, height / 2.0, 0);
                pose.scale(modelScale, modelScale, 1);
                pose.translate(-8, -8, 0);
                gg.renderItem(stack, 0, 0);
            } finally {
                // 即使物品渲染抛异常，也必须恢复共享 PoseStack，避免污染后续 UI 渲染。
                pose.popPose();
            }
        }
    }

    /** vanilla 生物渲染：复用原版物品栏 helper，包含裁剪、打光、姿态保存恢复和实体 dispatcher。 */
    private record EntitySurfaceRenderer(LivingEntity entity, float modelScale)
            implements MinecraftSurfaceView.Renderer {

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
            int size = Math.max(1, Math.round(modelScale));
            InventoryScreen.renderEntityInInventoryFollowsAngle(
                    gg, 0, 0, width, height, size, 0f, 0f, 0f, entity);
        }
    }
}
