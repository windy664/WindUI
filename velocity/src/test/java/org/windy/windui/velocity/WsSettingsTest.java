package org.windy.windui.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsSettingsTest {

    @TempDir
    Path dataDirectory;

    @Test
    void createsDefaultConfigOnFirstLoad() throws Exception {
        WsSettings settings = WsSettings.load(dataDirectory, NOPLogger.NOP_LOGGER);

        assertEquals(WsSettings.DEFAULT_PORT, settings.port());
        Path configFile = dataDirectory.resolve("config.yml");
        assertTrue(Files.exists(configFile));
        assertTrue(Files.readString(configFile).contains("websocket-port: 25599"));
    }

    @Test
    void loadsConfiguredPort() throws Exception {
        Files.writeString(dataDirectory.resolve("config.yml"), "websocket-port: 30123\n");

        WsSettings settings = WsSettings.load(dataDirectory, NOPLogger.NOP_LOGGER);

        assertEquals(30123, settings.port());
    }

    @Test
    void invalidPortFallsBackToDefault() throws Exception {
        Files.writeString(dataDirectory.resolve("config.yml"), "websocket-port: 65536\n");

        WsSettings settings = WsSettings.load(dataDirectory, NOPLogger.NOP_LOGGER);

        assertEquals(WsSettings.DEFAULT_PORT, settings.port());
    }

    @Test
    void malformedYamlFallsBackToDefault() throws Exception {
        Files.writeString(dataDirectory.resolve("config.yml"), "websocket-port: [\n");

        WsSettings settings = WsSettings.load(dataDirectory, NOPLogger.NOP_LOGGER);

        assertEquals(WsSettings.DEFAULT_PORT, settings.port());
    }
}