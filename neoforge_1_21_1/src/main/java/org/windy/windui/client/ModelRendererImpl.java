package org.windy.windui.client;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MinecraftSurfaceView;
import icyllis.modernui.view.View;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import org.windy.windui.client.render.ModelRenderer;
import org.windy.windui.core.parser.WidgetNode;

/**
 * {@link ModelRenderer} 的 NeoForge 1.21.1 实现（<b>平台特定 glue</b>）。
 * <p>
 * 用 ModernUI-MC 的 {@code MinecraftSurfaceView} 承载 MC 渲染管线，在其 {@code onDraw(GuiGraphics,…)} 里绘制。
 * <b>本轮=vanilla 物品后端（表面证明）</b>：把一个物品画进面板中心，验证 MinecraftSurfaceView 这条表面路
 * 在真机能画 MC 内容（历史黑屏之地，只能真机验）。表面证通后再 drop-in entity/GeckoLib 后端（同一 SurfaceView）。
 */
public final class ModelRendererImpl implements ModelRenderer {

    @Override
    public View createModelView(Context ctx, WidgetNode node) {
        String type = node.getString("type", "item");
        if ("geckolib".equals(type)) {
            // 软依赖门控：GeckoLib 缺席则不 class-load GeckoModelBackend（其内 GeckoLib 符号），优雅降级。
            if (!ModList.get().isLoaded("geckolib")) return null;
            return GeckoModelBackend.create(ctx, node);
        }
        // TODO(表面证通后): entity 后端(renderEntityInInventory)
        if (!"item".equals(type)) {
            return null; // 未实现的后端优雅降级
        }
        ItemStack stack = resolveItem(node.getString("model", "minecraft:diamond"));
        float scale = node.getFloat("scale", 3f);
        var surface = new MinecraftSurfaceView(ctx);
        surface.setRenderer(new ItemSurfaceRenderer(stack, scale));
        return surface;
    }

    private static ItemStack resolveItem(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        var item = loc == null ? Items.DIAMOND : BuiltInRegistries.ITEM.get(loc);
        return new ItemStack(item);
    }

    /** vanilla 物品渲染（1.21.1 {@link GuiGraphics}）：画进 SurfaceView 中心并放大，证明表面可绘 MC 内容。 */
    private record ItemSurfaceRenderer(ItemStack stack, float scale) implements MinecraftSurfaceView.Renderer {
        @Override
        public void onSurfaceChanged(int width, int height) {
        }

        @Override
        public void onDraw(GuiGraphics gg, int width, int height, float partialTick, double mouseX, float alpha) {
            var pose = gg.pose();
            pose.pushPose();
            pose.translate(width / 2.0, height / 2.0, 0);
            pose.scale(scale, scale, 1);
            pose.translate(-8, -8, 0); // 物品默认 16px，居中
            gg.renderItem(stack, 0, 0);
            pose.popPose();
        }
    }
}
