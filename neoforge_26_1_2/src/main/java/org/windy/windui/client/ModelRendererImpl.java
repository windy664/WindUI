package org.windy.windui.client;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MinecraftSurfaceView;
import icyllis.modernui.view.View;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.windy.windui.client.render.ModelRenderer;
import org.windy.windui.core.parser.WidgetNode;

/**
 * {@link ModelRenderer} 的 NeoForge 26.1.2 实现（<b>平台特定 glue</b>）。
 * <p>
 * 用 ModernUI-MC 的 {@code MinecraftSurfaceView} 承载 MC 渲染管线，在其 {@code onDraw(GuiGraphicsExtractor,…)}
 * 里绘制（26.x 没有 GuiGraphics 了，换成 render-state 的 {@link GuiGraphicsExtractor}）。
 * <b>本轮=vanilla 物品后端（表面证明）</b>，只用已验证的 {@code gg.item(stack,x,y)}（与 HudOverlay 同）。
 * GeckoLib 后端在 26.x（5.x API + render-state churn）风险更高，留待 1.21.1 表面/后端证通后再移。
 */
public final class ModelRendererImpl implements ModelRenderer {

    @Override
    public View createModelView(Context ctx, WidgetNode node) {
        String type = node.getString("type", "item");
        if (!"item".equals(type)) {
            return null; // 未实现的后端优雅降级
        }
        ItemStack stack = resolveItem(node.getString("model", "minecraft:diamond"));
        var surface = new MinecraftSurfaceView(ctx);
        surface.setRenderer(new ItemSurfaceRenderer(stack));
        return surface;
    }

    private static ItemStack resolveItem(String id) {
        Identifier loc = Identifier.tryParse(id);
        var item = loc == null ? Items.DIAMOND : BuiltInRegistries.ITEM.getValue(loc);
        return new ItemStack(item);
    }

    /** vanilla 物品渲染（26.x {@link GuiGraphicsExtractor}）：画进 SurfaceView 中心，证明表面可绘 MC 内容。 */
    private record ItemSurfaceRenderer(ItemStack stack) implements MinecraftSurfaceView.Renderer {
        @Override
        public void onSurfaceChanged(int width, int height) {
        }

        @Override
        public void onDraw(GuiGraphicsExtractor gg, int width, int height, float partialTick, double mouseX, float alpha) {
            gg.item(stack, width / 2 - 8, height / 2 - 8); // 物品默认 16px，居中
        }
    }
}
