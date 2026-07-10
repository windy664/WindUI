package org.windy.windui.core.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AssetCodecTest {

    @Test
    void roundTripPreservesHashAndBytes() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        AssetCodec.Asset decoded = AssetCodec.decode(AssetCodec.encode("hash123", png));
        assertEquals("hash123", decoded.hash());
        assertArrayEquals(png, decoded.png());
    }

    @Test
    void emptyPngOk() {
        AssetCodec.Asset decoded = AssetCodec.decode(AssetCodec.encode("h", new byte[0]));
        assertEquals("h", decoded.hash());
        assertEquals(0, decoded.png().length);
    }
}
