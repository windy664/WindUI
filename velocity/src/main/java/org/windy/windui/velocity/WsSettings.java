package org.windy.windui.velocity;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * WindUI WebSocket 服务端配置（{@code plugins/windui/windui.properties}）。首启写默认值。
 * <p>
 * WebSocket 独立于 MC 连接、跑在单独端口——服主需为 {@link #port()} 放行防火墙。
 */
public final class WsSettings {

    private final boolean enabled;
    private final String bind;
    private final int port;
    private final String publicHost;
    private final int maxMessageBytes;

    private WsSettings(boolean enabled, String bind, int port, String publicHost, int maxMessageKb) {
        this.enabled = enabled;
        this.bind = bind;
        this.port = port;
        this.publicHost = publicHost;
        this.maxMessageBytes = Math.max(64, maxMessageKb) * 1024;
    }

    /** WebSocket 服务是否启用。 */
    public boolean enabled() {
        return enabled;
    }

    /** 监听地址（{@code 0.0.0.0} = 所有网卡）。 */
    public String bind() {
        return "0.0.0.0".equals(bind) ? "" : bind;
    }

    /** 监听端口（0 = 自动选空闲端口）。 */
    public int port() {
        return port;
    }

    /**
     * 对客户端广播的公网地址（引导消息里的 host）。留空时客户端回退为"它连上代理的那个地址"，
     * 适用于 WS 与代理同机同域名的常见情形；若 WS 走独立域名/内网映射则在此填写。
     */
    public String publicHost() {
        return publicHost;
    }

    /** 单条消息上限（字节），防 OOM。 */
    public int maxMessageBytes() {
        return maxMessageBytes;
    }

    /** 从数据目录加载（缺文件则写默认后返回默认）。 */
    public static WsSettings load(Path dataDir, Logger logger) {
        Path file = dataDir.resolve("windui.properties");
        Properties p = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
            } else {
                Files.createDirectories(dataDir);
                writeDefault(file);
                logger.info("[WindUI] 已生成默认配置 {}", file);
            }
        } catch (IOException e) {
            logger.warn("[WindUI] 读取配置失败，用默认值", e);
        }
        return new WsSettings(
                Boolean.parseBoolean(p.getProperty("ws-enabled", "true")),
                p.getProperty("ws-bind", "0.0.0.0"),
                parseInt(p.getProperty("ws-port", "25599"), 25599),
                p.getProperty("ws-public-host", "").trim(),
                parseInt(p.getProperty("ws-max-message-kb", "8192"), 8192)
        );
    }

    private static void writeDefault(Path file) throws IOException {
        String content = """
                # WindUI WebSocket 传输配置
                # UI 走独立端口的 WebSocket（不再注入 MC 封包管线）。请为下面的端口放行防火墙。

                # 是否启用 WebSocket 服务端
                ws-enabled=true

                # 监听地址：0.0.0.0=所有网卡；127.0.0.1=仅本机
                ws-bind=0.0.0.0

                # 监听端口（0=自动选空闲端口，引导消息会广播真实端口）
                ws-port=25599

                # 广播给客户端的公网地址（host）。留空=客户端用它连上代理的地址（WS 与代理同机时最省事）。
                # 若 WS 走独立域名/内网映射，在此填写，例如 ws.example.com
                ws-public-host=

                # 单条消息上限（KB），覆盖大图；防 OOM
                ws-max-message-kb=8192
                """;
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
