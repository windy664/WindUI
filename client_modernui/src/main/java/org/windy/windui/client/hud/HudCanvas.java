package org.windy.windui.client.hud;

/**
 * HUD 绘制原语（<b>平台特定</b>，各 MC 版本各实现一份）。共享的 {@link HudRenderer} 只依赖这几个原语,
 * 故布局/绘制逻辑版本无关;版本差异(26.x 的 {@code GuiGraphicsExtractor} vs 1.21.1 的 {@code GuiGraphics})
 * 被收进各自的实现里。
 * <p>颜色一律 ARGB int(0xAARRGGBB)。坐标单位为像素(已按 GUI 缩放)。
 */
public interface HudCanvas {

    /** GUI 逻辑宽度(用于锚定)。 */
    int screenWidth();

    /** GUI 逻辑高度。 */
    int screenHeight();

    /** 文本像素宽度(用于布局/居中)。 */
    int textWidth(String text);

    /** 一行文字的高度(含行距)。 */
    int lineHeight();

    /** 画一行文字(支持 § 颜色/格式码);颜色为默认色,§ 码可覆盖。 */
    void text(String text, int x, int y, int argb);

    /** 填充矩形 [x0,y0,x1,y1)。 */
    void fill(int x0, int y0, int x1, int y1, int argb);

    /**
     * 画一个真实物品图标(16x16,含 vanilla 模型/附魔光效),右下角带数量角标(count>1 时)。
     *
     * @param itemId 物品 id,如 {@code "minecraft:diamond"};非法/未知则不画
     * @param count  数量(>1 显示角标)
     */
    void drawItem(String itemId, int count, int x, int y);

    /**
     * 把服务端下发的自定义 PNG(经 asset 通道)画进矩形 [x,y,x+w,y+h)。
     * <p>每版实现惰性把该 hash 的原始字节(见 {@code HudImageStore})解码成 vanilla 纹理并 blit;
     * 字节未到 / 解码失败则不画(优雅缺失,不崩)。
     *
     * @param hash asset 内容哈希(界面里 {@code asset:<hash>} 引用去掉前缀)
     */
    void drawImage(String hash, int x, int y, int w, int h);
}
