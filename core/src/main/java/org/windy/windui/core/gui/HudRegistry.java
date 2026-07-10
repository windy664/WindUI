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
 * 文件驱动的 HUD 注册表（与 {@link GuiRegistry} 同套路,只是内容是「锚定面板数组」JSON）。
 * <p>
 * 内置默认打进 jar（{@code /windui/huds/<id>.json}）。首次运行释放到磁盘 {@code huds/},服主能看/改;
 * 之后从磁盘全量加载。{@code /windui hud <id>} 按 id 取。文件名去扩展名即 id。
 */
public final class HudRegistry {

    private static final String BUNDLED_DIR = "/windui/huds/";
    private static final List<String> DEFAULT_IDS = List.of("demo");

    private final Path hudsDir;
    private final Map<String, String> huds = new HashMap<>(); // id -> 面板数组 JSON

    public HudRegistry(Path hudsDir) {
        this.hudsDir = hudsDir;
    }

    public synchronized int load() {
        huds.clear();
        try {
            Files.createDirectories(hudsDir);
            // 内置默认:磁盘上缺哪个就释放哪个(已存在的不覆盖)
            for (String id : DEFAULT_IDS) {
                Path f = hudsDir.resolve(id + ".json");
                if (!Files.exists(f)) {
                    String json = readBundled(id);
                    if (json != null && !json.isBlank()) {
                        Files.writeString(f, json, StandardCharsets.UTF_8);
                    }
                }
            }
            try (Stream<Path> stream = Files.list(hudsDir)) {
                List<Path> files = stream
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                        .toList();
                for (Path p : files) {
                    String id = stripExtension(p.getFileName().toString());
                    String json = Files.readString(p, StandardCharsets.UTF_8);
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

    /** 取 HUD 面板数组 JSON;不存在返回 null。 */
    public synchronized String get(String id) {
        return huds.get(id);
    }

    public synchronized Set<String> ids() {
        return new TreeSet<>(huds.keySet());
    }

    private static String readBundled(String id) {
        try (InputStream in = HudRegistry.class.getResourceAsStream(BUNDLED_DIR + id + ".json")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
