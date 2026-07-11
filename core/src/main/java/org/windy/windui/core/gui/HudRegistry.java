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
 * 文件驱动的 HUD 注册表，与 {@link GuiRegistry} 使用相同的覆盖策略。
 * <p>
 * 内置默认 HUD 位于 {@code /windui/huds/<id>.json}。加载时先把内置默认资源读入内存，再用磁盘目录
 * {@code huds/*.json} 中的同名文件覆盖。默认资源不会写入磁盘；服主需要修改时，可从 JAR 中复制一份
 * 到磁盘目录。删除磁盘覆盖文件并重载后，会恢复使用 JAR 内置版本。
 * <p>
 * {@code /windui hud <id>} 按 id 获取 HUD，文件名去扩展名即 id。
 */
public final class HudRegistry {

    private static final String BUNDLED_DIR = "/windui/huds/";
    private static final List<String> DEFAULT_IDS = List.of("demo");

    private final Path hudsDir;
    private final Map<String, String> huds = new HashMap<>();

    public HudRegistry(Path hudsDir) {
        this.hudsDir = hudsDir;
    }

    /**
     * 载入 HUD：先从 JAR 读取内置默认资源，再从磁盘 {@code huds/*.json} 全量加载并覆盖同名默认。
     * 不会向磁盘写入任何默认文件。
     *
     * @return 加载到的 HUD 数量
     */
    public synchronized int load() {
        huds.clear();
        loadBundledDefaults();

        try {
            Files.createDirectories(hudsDir);
            try (Stream<Path> stream = Files.list(hudsDir)) {
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
                        huds.put(id, json);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("加载 HUD 目录失败: " + hudsDir, e);
        }

        return huds.size();
    }

    /** 把所有可用的 JAR 内置默认 HUD 载入内存。 */
    private void loadBundledDefaults() {
        for (String id : DEFAULT_IDS) {
            String json = readBundled(id);
            if (json != null && !json.isBlank()) {
                huds.put(id, json);
            }
        }
    }

    /** 取 HUD 面板数组 JSON；不存在返回 null。 */
    public synchronized String get(String id) {
        return huds.get(id);
    }

    /** 已加载的 HUD id（有序）。 */
    public synchronized Set<String> ids() {
        return new TreeSet<>(huds.keySet());
    }

    /** HUD 磁盘覆盖目录。 */
    public Path directory() {
        return hudsDir;
    }

    /** 读 JAR 内置 HUD 资源 {@code /windui/huds/<id>.json}；缺失返回 null。 */
    private static String readBundled(String id) {
        try (InputStream input =
                     HudRegistry.class.getResourceAsStream(BUNDLED_DIR + id + ".json")) {
            return input == null
                    ? null
                    : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
