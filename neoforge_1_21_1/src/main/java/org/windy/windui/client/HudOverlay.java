package org.windy.windui.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.windy.windui.WindUI;
import org.windy.windui.client.hud.HudCanvas;
import org.windy.windui.client.hud.HudRenderer;
import org.windy.windui.client.hud.HudState;

/**
 * HUD 渲染钩子（<b>平台特定 glue</b>，NeoForge 1.21.1）。每帧把 {@link HudState} 里的面板用共享
 * {@link HudRenderer} 画到 HUD 层——非模态、不开 Screen。
 * <p>1.21.1 用 {@code GuiGraphics}(26.x 换成了 GuiGraphicsExtractor,那边另有一份实现)。
 */
@EventBusSubscriber(modid = WindUI.MODID, value = Dist.CLIENT)
public final class HudOverlay {

    private HudOverlay() {
    }

    @SubscribeEvent
    static void onRenderGui(RenderGuiEvent.Post event) {
        var panels = HudState.get();
        if (panels.isEmpty()) {
            return;
        }
        HudRenderer.render(new GuiGraphicsCanvas(event.getGuiGraphics()), panels);
    }

    /** {@link HudCanvas} 的 1.21.1 实现:把绘制原语落到 {@link GuiGraphics}。 */
    private record GuiGraphicsCanvas(GuiGraphics gg) implements HudCanvas {
        private static Font font() {
            return Minecraft.getInstance().font;
        }

        @Override public int screenWidth() {
            return gg.guiWidth();
        }

        @Override public int screenHeight() {
            return gg.guiHeight();
        }

        @Override public int textWidth(String text) {
            return font().width(text);
        }

        @Override public int lineHeight() {
            return font().lineHeight + 1;
        }

        @Override public void text(String text, int x, int y, int argb) {
            gg.drawString(font(), text, x, y, argb, true); // 带阴影
        }

        @Override public void fill(int x0, int y0, int x1, int y1, int argb) {
            gg.fill(x0, y0, x1, y1, argb);
        }

        @Override public void drawItem(String itemId, int count, int x, int y) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (id == null) return;
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
            var stack = new net.minecraft.world.item.ItemStack(item, Math.max(1, count));
            if (stack.isEmpty()) return;
            gg.renderItem(stack, x, y);
            gg.renderItemDecorations(font(), stack, x, y);
        }
    }
}
