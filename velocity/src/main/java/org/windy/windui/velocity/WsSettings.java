package org.windy.windui.velocity;

import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** WindUI Velocity 配置，只暴露部署时必须可调的 WebSocket 端口。 */
public final class WsSettings {

    static final int DEFAULT_PORT = 25599;
    private static final String CONFIG_FILE = "config.yml";

    private final int port;

    private WsSettings(int port) {
        this.port = port;
    }

    public int port() {
        return port;
    }

    /** 加载 {@code plugins/windui/config.yml}；首启复制 JAR 内默认配置。 */
    public static WsSettings load(Path dataDirectory, Logger logger) {
        Path configFile = dataDirectory.resolve(CONFIG_FILE);
        try {
            if (ensureDefaultConfig(dataDirectory, configFile)) {
                logger.info("[WindUI] 已生成默认配置 {}", configFile);
            }
            ConfigurationNode root = YamlConfigurationLoader.builder()
                    .path(configFile)
                    .build()
                    .load();
            int configuredPort = root.node("websocket-port").getInt(DEFAULT_PORT);
            if (configuredPort < 1 || configuredPort > 65535) {
                logger.warn("[WindUI] config.yml 中 websocket-port={} 非法，改用默认端口 {}",
                        configuredPort, DEFAULT_PORT);
                return new WsSettings(DEFAULT_PORT);
            }
            return new WsSettings(configuredPort);
        } catch (IOException | RuntimeException e) {
            logger.warn("[WindUI] 读取 config.yml 失败，改用默认端口 {}", DEFAULT_PORT, e);
            return new WsSettings(DEFAULT_PORT);
        }
    }

    private static boolean ensureDefaultConfig(Path dataDirectory, Path configFile) throws IOException {
        if (Files.exists(configFile)) {
            return false;
        }
        Files.createDirectories(dataDirectory);
        try (InputStream input = WsSettings.class.getResourceAsStream("/config.yml")) {
            if (input == null) {
                throw new IOException("JAR 中缺少默认 config.yml");
            }
            Files.copy(input, configFile);
        }
        return true;
    }
}