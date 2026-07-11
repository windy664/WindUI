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
 * 文件驱动的 HUD 注册表，与 {@link GuiRegistry} 使用相同的示例释放策略。
 * <p>
 * 内置默认 HUD 是打进 JAR 的示例模板，位于 {@code /windui/huds/<id>.json}。
 * 加载时会把磁盘目录中缺失的内置示例释放到 {@code huds/}，供服主直接查看和修改；
 * 已存在的磁盘文件绝不覆盖，用户改动会完整保留。
 * <p>
 * 随后从磁盘 {@code huds/*.json} 全量加载。删除某个内置示例后，下一次加载会重新释放该示例。
 * {@code /windui hud <id>} 按文件名去扩展名后的 id 获取 HUD。
 */
public final class HudRegistry {

    /** 内置默认 HUD 的 classpath 目录。 */
    private static final String BUNDLED_DIR = "/windui/huds/";

    /** 随 JAR 提供、会在磁盘缺失时释放的示例 HUD id。 */
    private static final List<String> DEFAULT_IDS = List.of("demo");

    private final Path hudsDir;
    private final Map<String, String> huds = new HashMap<>();

    public HudRegistry(Path hudsDir) {
        this.hudsDir = hudsDir;
    }

    /**
     * 载入 HUD：
     * <ol>
     *     <li>创建磁盘 {@code huds/} 目录；</li>
     *     <li>把缺失的 JAR 内置示例释放到磁盘，已存在文件不覆盖；</li>
     *     <li>从磁盘 {@code huds/*.json} 全量加载。</li>
     * </ol>
     *
     * @return 加载到的 HUD 数量
     */
    public synchronized int load() {
        huds.clear();

        try {
            Files.createDirectories(hudsDir);
            extractMissingDefaults();

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

    /**
     * 把磁盘上缺失的内置示例释放出来。
     * 已存在的文件绝不覆盖，确保服主修改不会在 reload 或升级后丢失。
     */
    private void extractMissingDefaults() throws IOException {
        for (String id : DEFAULT_IDS) {
            Path target = hudsDir.resolve(id + ".json");
            if (Files.exists(target)) {
                continue;
            }

            String json = readBundled(id);
            if (json != null && !json.isBlank()) {
                Files.writeString(target, json, StandardCharsets.UTF_8);
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

    /** HUD 示例及用户自定义文件所在的磁盘目录。 */
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
