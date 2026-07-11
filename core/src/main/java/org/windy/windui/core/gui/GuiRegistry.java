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
 * 内置默认界面是打进 JAR 的真实资源文件（{@code /windui/guis/<id>.json}，见
 * {@code core/src/main/resources}）。加载时先把内置默认资源读入内存，再用磁盘目录
 * {@code guis/*.json} 中的同名文件覆盖。默认资源不会写入磁盘，因此：
 * <ul>
 *     <li>开箱即用，无需生成默认文件；</li>
 *     <li>服主可从 JAR 复制默认文件到磁盘后定制；</li>
 *     <li>删除磁盘覆盖文件后，重载会恢复使用内置版本；</li>
 *     <li>升级 JAR 后，未被磁盘覆盖的默认界面自动使用新版本。</li>
 * </ul>
 * 文件名去扩展名即界面 id。支持热重载。
 */
public final class GuiRegistry {

    /** 内置默认界面的 classpath 目录。 */
    private static final String BUNDLED_DIR = "/windui/guis/";

    /** 随 JAR 内置的默认界面 id。 */
    private static final List<String> DEFAULT_IDS = List.of(
            "menu_main", "shop_main", "confirm", "welcome");

    private final Path guisDir;
    private final Map<String, String> guis = new HashMap<>();

    public GuiRegistry(Path guisDir) {
        this.guisDir = guisDir;
    }

    /**
     * 载入界面：先从 JAR 读取内置默认资源，再从磁盘 {@code guis/*.json} 全量加载并覆盖同名默认。
     * 不会向磁盘写入任何默认文件。
     *
     * @return 加载到的界面数量
     */
    public synchronized int load() {
        guis.clear();
        loadBundledDefaults();

        try {
            Files.createDirectories(guisDir);
            try (Stream<Path> stream = Files.list(guisDir)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName()
                                .toString()
                                .toLowerCase()
                                .endsWith(".json"))
                        .sorted()
                        .toList();

                for (Path path : files) {
                    String id = stripExtension(path.getFileName().toString());
                    String json = Files.readString(path, StandardCharsets.UTF_8);
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

    /** 把所有可用的 JAR 内置默认界面载入内存。 */
    private void loadBundledDefaults() {
        for (String id : DEFAULT_IDS) {
            String json = readBundled(id);
            if (json != null && !json.isBlank()) {
                guis.put(id, json);
            }
        }
    }

    /** 读 JAR 内置界面资源 {@code /windui/guis/<id>.json}；缺失返回 null。 */
    private static String readBundled(String id) {
        try (InputStream input =
                     GuiRegistry.class.getResourceAsStream(BUNDLED_DIR + id + ".json")) {
            return input == null
                    ? null
                    : new String(input.readAllBytes(), StandardCharsets.UTF_8);
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
