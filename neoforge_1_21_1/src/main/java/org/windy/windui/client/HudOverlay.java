package org.windy.windui.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.windy.windui.WindUI;
import org.windy.windui.client.hud.HudCanvas;
import org.windy.windui.client.hud.HudImageStore;
import org.windy.windui.client.hud.HudRenderer;
import org.windy.windui.client.hud.HudState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HUD 渲染钩子（<b>平台特定 glue</b>，NeoForge 1.21.1）。每帧把 {@link HudState} 里的面板用共享
 * {@link HudRenderer} 画到 HUD 层——非模态、不开 Screen。
 * <p>1.21.1 用 {@code GuiGraphics}(26.x 换成了 GuiGraphicsExtractor,那边另有一份实现)。
 */
@EventBusSubscriber(modid = WindUI.MODID, value = Dist.CLIENT)
public final class HudOverlay {

    private HudOverlay() {
    }

    /** hash → 已注册的 vanilla 纹理(+像素尺寸,缩放 blit 需要)。{@code id==null} 的 {@link #BAD} 表示解码失败,不再重试。 */
    private record Tex(ResourceLocation id, int w, int h) {
    }

    private static final Tex BAD = new Tex(null, 0, 0);
    private static final Map<String, Tex> TEX = new ConcurrentHashMap<>();

    /**
     * 惰性把某 asset 的原始 PNG 字节建成 vanilla 纹理(本方法在渲染线程内调用,GL 上传安全)。
     * 字节未到返回 {@code null}(下帧再试);解码失败缓存 {@link #BAD}(不再重试)。
     */
    private static Tex texture(String hash) {
        Tex t = TEX.get(hash);
        if (t != null) {
            return t;
        }
        byte[] png = HudImageStore.get(hash);
        if (png == null) {
            return null; // 字节还没到,别缓存,下帧再试
        }
        try {
            NativeImage img = NativeImage.read(png);
            DynamicTexture dyn = new DynamicTexture(img); // 接管 img,不要 close
            ResourceLocation id = Minecraft.getInstance().getTextureManager().register("windui_hud", dyn);
            t = new Tex(id, img.getWidth(), img.getHeight());
        } catch (Exception e) {
            t = BAD;
        }
        TEX.put(hash, t);
        return t;
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

        @Override public void drawImage(String hash, int x, int y, int w, int h) {
            Tex tex = texture(hash);
            if (tex == null || tex.id() == null) return;
            // 11 参缩放 blit:整图(u=0,v=0,uW=vH=texW/H)拉伸进 (x,y,w,h)。
            gg.blit(tex.id(), x, y, w, h, 0f, 0f, tex.w(), tex.h(), tex.w(), tex.h());
        }
    }
}
