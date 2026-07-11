package org.windy.windui.client.hud;

import org.windy.windui.core.parser.WidgetNode;
import org.windy.windui.core.protocol.HudCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本无关的 HUD 布局/绘制引擎。把一组锚定面板(SDUI 子树)用 {@link HudCanvas} 原语画到 HUD 层。
 * <p>
 * 支持子集:{@code LinearLayout}(orientation 0/1 = 行/列)+ {@code TextView} / {@code Markdown}(多行) /
 * {@code Bar}(进度条) / {@code Divider} / {@code ItemSlot}(真实物品) / {@code ImageView}(服务端下发的
 * 自定义 PNG,{@code src:"asset:<hash>"};{@code img:} 由服务端重写而来)。容器支持
 * {@code background}/{@code border}/{@code radius(忽略)}/{@code padding}。
 * <p>用 vanilla GuiGraphics 直绘,不碰 ModernUI 离屏,几乎零开销(文字仍走 ModernUI 引擎的字体 mixin)。
 */
public final class HudRenderer {

    private HudRenderer() {
    }

    /** 画一帧全部面板。{@code panels} 为空即什么都不画(HUD 已清空)。 */
    public static void render(HudCanvas c, List<HudCodec.Panel> panels) {
        for (HudCodec.Panel p : panels) {
            if (p.root() == null) continue;
            Size s = measure(c, p.root());
            int[] origin = anchor(c, p.anchor(), p.x(), p.y(), s.w, s.h);
            draw(c, p.root(), origin[0], origin[1], s.w);
        }
    }

    // ===== 锚定 =====

    private static int[] anchor(HudCanvas c, HudCodec.Anchor a, int dx, int dy, int w, int h) {
        int sw = c.screenWidth(), sh = c.screenHeight();
        int x, y;
        switch (a) {
            case TOP_LEFT, CENTER_LEFT, BOTTOM_LEFT -> x = dx;
            case TOP_CENTER, CENTER, BOTTOM_CENTER -> x = (sw - w) / 2 + dx;
            default -> x = sw - w - dx; // 右侧:dx 表示离右边的距离
        }
        switch (a) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> y = dy;
            case CENTER_LEFT, CENTER, CENTER_RIGHT -> y = (sh - h) / 2 + dy;
            default -> y = sh - h - dy; // 底部:dy 表示离底边的距离
        }
        return new int[]{x, y};
    }

    // ===== 测量 =====

    private record Size(int w, int h) {
    }

    private static Size measure(HudCanvas c, WidgetNode n) {
        int pad = n.getInt("padding", 0);
        Size inner = switch (n.type()) {
            case "LinearLayout" -> measureLinear(c, n);
            case "TextView" -> new Size(c.textWidth(n.getString("text", "")), c.lineHeight());
            case "Markdown" -> measureLines(c, n.getString("text", ""));
            case "Bar" -> new Size(n.getInt("width", 120), n.getInt("height", 6));
            case "Divider" -> new Size(n.getInt("width", 80), n.getInt("height", 1) + n.getInt("margin", 0) * 2);
            case "ItemSlot" -> new Size(n.getInt("size", 16), n.getInt("size", 16));
            case "ImageView" -> new Size(n.getInt("width", 16), n.getInt("height", 16));
            default -> new Size(0, 0);
        };
        return new Size(inner.w + pad * 2, inner.h + pad * 2);
    }

    private static Size measureLinear(HudCanvas c, WidgetNode n) {
        boolean vertical = n.getInt("orientation", 1) != 0;
        int w = 0, h = 0;
        for (WidgetNode child : n.children()) {
            Size cs = measure(c, child);
            int m = child.getInt("margin", 0);
            if (vertical) {
                w = Math.max(w, cs.w + m * 2);
                h += cs.h + m * 2;
            } else {
                w += cs.w + m * 2;
                h = Math.max(h, cs.h + m * 2);
            }
        }
        return new Size(w, h);
    }

    private static Size measureLines(HudCanvas c, String text) {
        String[] lines = text.split("\n", -1);
        int w = 0;
        for (String l : lines) w = Math.max(w, c.textWidth(l));
        return new Size(w, lines.length * c.lineHeight());
    }

    // ===== 绘制 =====

    private static void draw(HudCanvas c, WidgetNode n, int x, int y, int width) {
        Size full = measure(c, n);
        drawBackground(c, n, x, y, full.w, full.h);
        int pad = n.getInt("padding", 0);
        int cx = x + pad, cy = y + pad;
        switch (n.type()) {
            case "LinearLayout" -> drawLinear(c, n, cx, cy);
            case "TextView" -> c.text(n.getString("text", ""), cx, cy, color(n.getString("textColor", "#FFFFFF")));
            case "Markdown" -> drawLines(c, n.getString("text", ""), cx, cy, color(n.getString("textColor", "#EAF2FF")));
            case "Bar" -> drawBar(c, n, cx, cy);
            case "Divider" -> {
                int m = n.getInt("margin", 0);
                int w = n.getInt("width", 80), h = n.getInt("height", 1);
                c.fill(cx, cy + m, cx + w, cy + m + h, color(n.getString("color", "#26FFFFFF")));
            }
            case "ItemSlot" -> c.drawItem(n.getString("item", ""), n.getInt("count", 1), cx, cy);
            case "ImageView" -> {
                // 服务端已把 img:path 重写成 asset:hash;只有 asset: 前缀才有下发字节可画,
                // 其余(未解析的 img: / MC 资源路径 / 缺失)在 HUD 层优雅跳过。
                String src = n.getString("src", "");
                if (src.startsWith("asset:")) {
                    c.drawImage(src.substring("asset:".length()),
                            cx, cy, n.getInt("width", 16), n.getInt("height", 16));
                }
            }
            default -> { /* 未知类型忽略 */ }
        }
    }

    private static void drawLinear(HudCanvas c, WidgetNode n, int x, int y) {
        boolean vertical = n.getInt("orientation", 1) != 0;
        int cursor = vertical ? y : x;
        for (WidgetNode child : n.children()) {
            Size cs = measure(c, child);
            int m = child.getInt("margin", 0);
            if (vertical) {
                draw(c, child, x + m, cursor + m, cs.w);
                cursor += cs.h + m * 2;
            } else {
                draw(c, child, cursor + m, y + m, cs.w);
                cursor += cs.w + m * 2;
            }
        }
    }

    private static void drawLines(HudCanvas c, String text, int x, int y, int argb) {
        int lh = c.lineHeight();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            c.text(lines[i], x, y + i * lh, argb);
        }
    }

    private static void drawBar(HudCanvas c, WidgetNode n, int x, int y) {
        int w = n.getInt("width", 120), h = n.getInt("height", 6);
        float v = Math.max(0f, Math.min(1f, n.getFloat("value", 0f)));
        c.fill(x, y, x + w, y + h, color(n.getString("track", "#40FFFFFF")));
        c.fill(x, y, x + Math.round(w * v), y + h, color(n.getString("color", "#66E0A3")));
    }

    private static void drawBackground(HudCanvas c, WidgetNode n, int x, int y, int w, int h) {
        String bg = n.getString("background", null);
        if (bg != null) c.fill(x, y, x + w, y + h, color(bg));
        String border = n.getString("border", null);
        if (border != null) {
            int col = color(border);
            c.fill(x, y, x + w, y + 1, col);          // 上
            c.fill(x, y + h - 1, x + w, y + h, col);  // 下
            c.fill(x, y, x + 1, y + h, col);          // 左
            c.fill(x + w - 1, y, x + w, y + h, col);  // 右
        }
    }

    // ===== 工具 =====

    /** "#RRGGBB" / "#AARRGGBB" → ARGB int;失败返回不透明白。 */
    static int color(String hex) {
        try {
            if (hex.startsWith("#")) hex = hex.substring(1);
            if (hex.length() == 6) hex = "FF" + hex;
            return (int) Long.parseLong(hex, 16);
        } catch (RuntimeException e) {
            return 0xFFFFFFFF;
        }
    }

    /** 备用:把面板列表转可变副本(供未来 diff)。 */
    static List<HudCodec.Panel> copy(List<HudCodec.Panel> in) {
        return new ArrayList<>(in);
    }
}
