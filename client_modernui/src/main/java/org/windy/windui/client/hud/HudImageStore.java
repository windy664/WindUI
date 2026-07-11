package org.windy.windui.client.hud;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端下发图片(windui:asset 频道)的<b>原始 PNG 字节</b>缓存：hash → png。
 * <p>
 * <b>跨 MC 版本共享</b>：只存字节,不碰任何渲染类。为什么和 {@code AssetCache} 分开：
 * {@code AssetCache} 把字节解码成 <b>ModernUI Image</b>(供 Screen 里的 ViewFactory 用);而 HUD 走
 * <b>vanilla GuiGraphics</b>,需要 <b>vanilla 纹理</b>。各版 HUD glue 从这里取字节,在渲染线程惰性
 * 建 {@code DynamicTexture} 并 blit(见各版 {@code HudOverlay.drawImage})。
 * <p>字节小(图标级),与 {@code AssetCache} 一样仅内存缓存,断线时一并清。
 */
public final class HudImageStore {

    private static final Map<String, byte[]> BYTES = new ConcurrentHashMap<>();

    private HudImageStore() {
    }

    /** 记录一份 asset 原始字节(asset 通道到达时调用)。已存在则忽略。 */
    public static void put(String hash, byte[] png) {
        if (hash != null && png != null) {
            BYTES.putIfAbsent(hash, png);
        }
    }

    /** 取原始字节;未命中返回 null(HUD 该图优雅缺失)。 */
    public static byte[] get(String hash) {
        return BYTES.get(hash);
    }

    public static void clear() {
        BYTES.clear();
    }
}
