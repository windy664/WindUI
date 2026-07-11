package org.windy.windui.client.hud;

import org.junit.jupiter.api.Test;
import org.windy.windui.core.parser.WidgetNode;
import org.windy.windui.core.protocol.HudCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link HudRenderer} 对 {@code ImageView} 的分派逻辑（版本无关，不碰 ModernUI/MC）。
 * 用一个记录调用的 {@link HudCanvas} 假实现断言 {@code drawImage} 是否被正确触发。
 */
class HudRendererTest {

    /** 记录 drawImage 调用；其余原语空实现。 */
    private static final class RecordingCanvas implements HudCanvas {
        record Img(String hash, int x, int y, int w, int h) {
        }

        final List<Img> images = new ArrayList<>();

        @Override public int screenWidth() {
            return 400;
        }

        @Override public int screenHeight() {
            return 300;
        }

        @Override public int textWidth(String text) {
            return text.length() * 6;
        }

        @Override public int lineHeight() {
            return 10;
        }

        @Override public void text(String text, int x, int y, int argb) {
        }

        @Override public void fill(int x0, int y0, int x1, int y1, int argb) {
        }

        @Override public void drawItem(String itemId, int count, int x, int y) {
        }

        @Override public void drawImage(String hash, int x, int y, int w, int h) {
            images.add(new Img(hash, x, y, w, h));
        }
    }

    private static WidgetNode image(String src, int w, int h) {
        return new WidgetNode("ImageView", null,
                Map.of("src", src, "width", w, "height", h), List.of(), Map.of());
    }

    private static List<HudCodec.Panel> panel(WidgetNode root) {
        return List.of(new HudCodec.Panel(HudCodec.Anchor.TOP_LEFT, 0, 0, root));
    }

    @Test
    void assetSrcTriggersDrawImageWithHashAndSize() {
        RecordingCanvas c = new RecordingCanvas();
        HudRenderer.render(c, panel(image("asset:deadbeef", 128, 96)));

        assertEquals(1, c.images.size(), "asset: 的 ImageView 应触发一次 drawImage");
        RecordingCanvas.Img img = c.images.get(0);
        assertEquals("deadbeef", img.hash(), "hash 应去掉 asset: 前缀");
        assertEquals(128, img.w());
        assertEquals(96, img.h());
    }

    @Test
    void unrewrittenImgSrcIsSkipped() {
        RecordingCanvas c = new RecordingCanvas();
        // 服务端没解析到的 img:（图不存在时原样留），HUD 层应优雅跳过、不画。
        HudRenderer.render(c, panel(image("img:missing.png", 64, 64)));
        assertTrue(c.images.isEmpty(), "未重写的 img: 不应触发 drawImage");
    }

    @Test
    void missingSrcIsSkipped() {
        RecordingCanvas c = new RecordingCanvas();
        WidgetNode noSrc = new WidgetNode("ImageView", null, Map.of("width", 32, "height", 32), List.of(), Map.of());
        HudRenderer.render(c, panel(noSrc));
        assertTrue(c.images.isEmpty(), "无 src 的 ImageView 不应触发 drawImage");
    }
}
