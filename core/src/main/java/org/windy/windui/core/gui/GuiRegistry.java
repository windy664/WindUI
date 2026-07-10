package org.windy.windui.core.gui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 文件驱动的 GUI 注册表。平台无关（纯 java.nio + Map）。
 * <p>
 * 内置默认界面是打进 jar 的真实资源文件（{@code /windui/guis/<id>.json}，见 {@code core/src/main/resources}）。
 * <b>首次运行(目录不存在)时把内置默认释放到磁盘</b>,服主能直接看到/编辑;之后一律从磁盘 {@code guis/*.json}
 * 加载(用户的编辑/删除都保留,不覆盖、不再生成)。文件名去扩展名即界面 id。支持热重载。
 */
public final class GuiRegistry {

    /** 内置默认界面的 classpath 目录（打进 jar，可直接查看）。 */
    private static final String BUNDLED_DIR = "/windui/guis/";

    /** 随 jar 内置的默认界面 id（从 jar 资源载入内存，不写盘）。 */
    private static final List<String> DEFAULT_IDS = List.of(
            "menu_main", "shop_main", "confirm", "welcome");

    private final Path guisDir;
    private final Map<String, String> guis = new HashMap<>(); // id -> uiJson

    public GuiRegistry(Path guisDir) {
        this.guisDir = guisDir;
    }

    /**
     * 载入界面：把 jar 内置默认（{@link #DEFAULT_IDS}）中磁盘上缺失的释放到磁盘(已存在的不覆盖),
     * 然后从磁盘 {@code guis/*.json} 全量加载。文件名去扩展名即界面 id。
     *
     * @return 加载到的界面数量
     */
    public synchronized int load() {
        guis.clear();
        try {
            Files.createDirectories(guisDir);
            // 内置默认:磁盘上缺哪个就释放哪个(已存在的不覆盖,保留用户改动)
            for (String id : DEFAULT_IDS) {
                Path f = guisDir.resolve(id + ".json");
                if (!Files.exists(f)) {
                    String json = readBundled(id);
                    if (json != null && !json.isBlank()) {
                        Files.writeString(f, json, StandardCharsets.UTF_8);
                    }
                }
            }
            // 从磁盘全量载入
            try (Stream<Path> stream = Files.list(guisDir)) {
                List<Path> files = stream
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                        .toList();
                for (Path p : files) {
                    String id = stripExtension(p.getFileName().toString());
                    String json = Files.readString(p, StandardCharsets.UTF_8);
                    if (!json.isBlank()) {
                        guis.put(id, json);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("加载 GUI 目录失败: " + guisDir, e);
        }
        return guis.size();
    }

    /** 读 jar 内置界面资源 {@code /windui/guis/<id>.json}；缺失返回 null。 */
    private static String readBundled(String id) {
        try (InputStream in = GuiRegistry.class.getResourceAsStream(BUNDLED_DIR + id + ".json")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** 取界面原始 JSON（未套占位符），不存在返回 null。 */
    public synchronized String get(String id) {
        return guis.get(id);
    }

    /** 已加载的界面 id（有序）。 */
    public synchronized Set<String> ids() {
        return new TreeSet<>(guis.keySet());
    }

    public Path directory() {
        return guisDir;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
