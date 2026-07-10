package org.windy.windui.client.render;

import icyllis.modernui.graphics.Bitmap;
import icyllis.modernui.graphics.BitmapFactory;
import icyllis.modernui.graphics.Image;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端下发图片(windui:asset 频道)的客户端缓存：hash → 解码后的 {@link Image}。
 * <p>
 * <b>跨 MC 版本共享</b>：只用纯 ModernUI 的 graphics 类。界面里 {@code "src":"asset:<hash>"} 由
 * {@link ViewFactory} 经 {@link #get} 取图；未命中则该图优雅缺失（不崩）。当前仅内存缓存。
 */
public final class AssetCache {

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private AssetCache() {
    }

    /**
     * 解码 PNG 字节为 {@link Image} 并按 hash 缓存。
     * <p><b>须在 ModernUI UI 线程调用</b>（纹理创建）。已缓存或解码失败则跳过。
     */
    public static void decodeAndCache(String hash, byte[] png) {
        if (hash == null || png == null || CACHE.containsKey(hash)) {
            return;
        }
        try {
            Bitmap bmp = BitmapFactory.decodeStream(new ByteArrayInputStream(png));
            Image img = Image.createTextureFromBitmap(bmp);
            bmp.close();
            if (img != null) {
                CACHE.put(hash, img);
            }
        } catch (Exception e) {
            // 解码失败：不缓存，界面该图优雅缺失
        }
    }

    /** 取缓存图；未命中返回 null。 */
    public static Image get(String hash) {
        return CACHE.get(hash);
    }

    public static boolean has(String hash) {
        return CACHE.containsKey(hash);
    }

    public static void clear() {
        CACHE.clear();
    }
}
