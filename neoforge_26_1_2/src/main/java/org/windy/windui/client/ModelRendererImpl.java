package org.windy.windui.client;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MinecraftSurfaceView;
import icyllis.modernui.view.View;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.windy.windui.client.render.ModelRenderer;
import org.windy.windui.core.parser.WidgetNode;

/**
 * {@link ModelRenderer} 的 NeoForge 26.1.2 实现（<b>平台特定 glue</b>）。
 * <p>
 * 用 ModernUI-MC 的 {@code MinecraftSurfaceView} 承载 MC 渲染管线，在其
 * {@code onDraw(GuiGraphicsExtractor,…)} 中绘制。26.x 已使用 render-state
 * {@link GuiGraphicsExtractor}，不再暴露 1.21.1 的 GuiGraphics PoseStack。
 * <p>
 * 当前提供 vanilla 物品/实体后端。{@code modelScale} 表示 Model3D 专属内容缩放；由于当前
 * GuiGraphicsExtractor 的 item 提取接口不暴露局部矩阵，本版本把该缩放应用到专属 SurfaceView。
 * 通用 {@code scale} 仍由共享 ViewFactory 处理。
 */
public final class ModelRendererImpl implements ModelRenderer {

    private static final float DEFAULT_ITEM_MODEL_SCALE = 1f;
    private static final float DEFAULT_ENTITY_MODEL_SCALE = 30f;

    @Override
    public View createModelView(Context ctx, WidgetNode node) {
        String type = node.getString("type", "item");
        if ("entity".equals(type)) {
            LivingEntity entity = resolveEntity(node.getString("model", "minecraft:zombie"));
            if (entity == null) {
                return null;
            }
            float modelScale = modelScale(node, DEFAULT_ENTITY_MODEL_SCALE);
            var surface = new MinecraftSurfaceView(ctx);
            surface.setRenderer(new EntitySurfaceRenderer(entity, modelScale));
            return surface;
        }

        if (!"item".equals(type)) {
            return null;
        }

        ItemStack stack = resolveItem(node.getString("model", "minecraft:diamond"));
        var surface = new MinecraftSurfaceView(ctx);
        surface.setRenderer(new ItemSurfaceRenderer(stack));

        // 26.x 的提取式 GUI API 暂无局部 PoseStack；modelScale 只作用于本 Model3D 的 SurfaceView。
        float modelScale = modelScale(node, DEFAULT_ITEM_MODEL_SCALE);
        if (modelScale != 1f) {
            surface.setScaleX(modelScale);
            surface.setScaleY(modelScale);
        }

        return surface;
    }

    private static ItemStack resolveItem(String id) {
        Identifier loc = Identifier.tryParse(id);
        var item = loc == null ? Items.DIAMOND : BuiltInRegistries.ITEM.getValue(loc);
        return new ItemStack(item);
    }

    private static LivingEntity resolveEntity(String id) {
        Identifier loc = Identifier.tryParse(id);
        var level = Minecraft.getInstance().level;
        if (loc == null || level == null) {
            return null;
        }
        var entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(loc).orElse(null);
        if (entityType == null) {
            return null;
        }
        return entityType.create(level, EntitySpawnReason.COMMAND) instanceof LivingEntity living ? living : null;
    }

    /**
     * 读取新契约的 modelScale。
     * <p>
     * 旧节点只声明 scale 时不在此重复处理，由共享 ViewFactory 保持旧行为。
     */
    private static float modelScale(WidgetNode node, float fallback) {
        if (!node.props().containsKey("modelScale")) {
            return fallback;
        }

        float value = node.getFloat("modelScale", fallback);
        return Float.isFinite(value) && value > 0f ? value : fallback;
    }

    /** vanilla 物品渲染：把默认 16×16 物品图标放到 SurfaceView 中心。 */
    private record ItemSurfaceRenderer(ItemStack stack)
            implements MinecraftSurfaceView.Renderer {

        @Override
        public void onSurfaceChanged(int width, int height) {
        }

        @Override
        public void onDraw(
                GuiGraphicsExtractor gg,
                int width,
                int height,
                float partialTick,
                double mouseX,
                float alpha) {
            gg.item(stack, width / 2 - 8, height / 2 - 8);
        }
    }

    /** vanilla 生物渲染：复用 26.x 原版提取式物品栏实体 helper。 */
    private record EntitySurfaceRenderer(LivingEntity entity, float modelScale)
            implements MinecraftSurfaceView.Renderer {

        @Override
        public void onSurfaceChanged(int width, int height) {
        }

        @Override
        public void onDraw(
                GuiGraphicsExtractor gg,
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
