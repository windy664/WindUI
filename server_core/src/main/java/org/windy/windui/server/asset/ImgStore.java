package org.windy.windui.server.asset;

import org.windy.windui.core.protocol.AssetCodec.Asset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端图片仓库：从 {@code plugins/windui/img/} 读取自定义 PNG，按内容哈希缓存并对外提供字节。
 * <p>
 * 平台无关（server_core）。JSON 里图片用 {@code "img:<相对路径>"} 引用（如 {@code "img:shop/banner.png"}），
 * 服务端 {@link #resolve} 得到该图的 hash+字节；客户端据 hash 缓存去重，缺的图经
 * {@link org.windy.windui.core.protocol.WindUIProtocol#ASSET_CHANNEL} 频道推送。
 * <p>
 * <b>注</b>：Velocity 自身无 MC 资源，自定义图的像素只能来自磁盘文件（本仓库），原版/物品/模组图标
 * 仍由客户端本地纹理直接解析、不走此路。
 */
public final class ImgStore {

    private final Path imgDir;
    private final Map<String, Asset> byPath = new ConcurrentHashMap<>();
    private final Map<String, Asset> byHash = new ConcurrentHashMap<>();

    public ImgStore(Path imgDir) {
        this.imgDir = imgDir.toAbsolutePath().normalize();
    }

    /** 确保 img/ 目录存在（供用户放自定义 PNG）。不生成任何默认文件。 */
    public void init() {
        try {
            Files.createDirectories(imgDir);
        } catch (IOException ignored) {
            // 目录建不出来时 resolve 会返回 null，不致命
        }
    }

    /**
     * 解析 {@code img:<path>} 的相对路径为图片资源（含内容哈希）。缓存结果。
     *
     * @return 资源；文件不存在、越权路径或读取失败返回 {@code null}
     */
    public Asset resolve(String relativePath) {
        Asset cached = byPath.get(relativePath);
        if (cached != null) {
            return cached;
        }
        Path f = imgDir.resolve(relativePath).normalize();
        // 防目录穿越：解析后必须仍在 imgDir 内
        if (!f.startsWith(imgDir) || !Files.isRegularFile(f)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(f);
            Asset a = new Asset(shortHash(bytes), bytes);
            byPath.put(relativePath, a);
            byHash.put(a.hash(), a);
            return a;
        } catch (IOException e) {
            return null;
        }
    }

    /** 按哈希取已解析过的资源（客户端 ASSET_REQ 补图时用）。 */
    public Asset byHash(String hash) {
        return byHash.get(hash);
    }

    /** 清空缓存（reload 时；文件可能已改）。 */
    public void clear() {
        byPath.clear();
        byHash.clear();
    }

    public Path directory() {
        return imgDir;
    }

    /** 内容哈希：SHA-256 前 8 字节的 hex（16 字符），够一个服务器的图片集去重。 */
    private static String shortHash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e); // 标准 JDK 必有
        }
    }
}
