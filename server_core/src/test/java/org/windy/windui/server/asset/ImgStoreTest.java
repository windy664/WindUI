package org.windy.windui.server.asset;

import org.junit.jupiter.api.Test;
import org.windy.windui.core.protocol.AssetCodec;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImgStoreTest {

    @Test
    void resolveReadsHashesAndCaches() throws Exception {
        Path dir = Files.createTempDirectory("img");
        byte[] png = {9, 8, 7, 6, 5};
        Files.write(dir.resolve("a.png"), png);
        ImgStore store = new ImgStore(dir);
        store.init();

        AssetCodec.Asset asset = store.resolve("a.png");
        assertNotNull(asset);
        assertArrayEquals(png, asset.png());
        assertEquals(16, asset.hash().length(), "短哈希应为 16 hex 字符");
        assertNotNull(store.byHash(asset.hash()), "解析后应能按 hash 取回");
    }

    @Test
    void pathTraversalBlocked() throws Exception {
        ImgStore store = new ImgStore(Files.createTempDirectory("img"));
        store.init();
        assertNull(store.resolve("../secret"));
        assertNull(store.resolve("../../etc/passwd"));
    }

    @Test
    void missingFileReturnsNull() throws Exception {
        ImgStore store = new ImgStore(Files.createTempDirectory("img"));
        store.init();
        assertNull(store.resolve("nope.png"));
    }
}
