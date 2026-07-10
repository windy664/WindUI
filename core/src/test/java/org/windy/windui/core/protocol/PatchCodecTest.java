package org.windy.windui.core.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchCodecTest {

    @Test
    void decodesArrayOfPatchesWithTypedProps() {
        byte[] wire = PatchCodec.encode(
                "[{\"id\":\"bal\",\"props\":{\"text\":\"200\",\"visible\":true,\"alpha\":0.5}},"
                        + "{\"id\":\"status\",\"props\":{\"text\":\"ok\"}}]");

        List<PatchCodec.Patch> patches = PatchCodec.decode(wire);

        assertEquals(2, patches.size());
        assertEquals("bal", patches.get(0).id());
        assertEquals("200", patches.get(0).props().get("text"));
        assertEquals(Boolean.TRUE, patches.get(0).props().get("visible"));
        assertTrue(patches.get(0).props().get("alpha") instanceof Number);
        assertEquals("status", patches.get(1).id());
    }

    @Test
    void decodesSingleObject() {
        List<PatchCodec.Patch> patches =
                PatchCodec.decode(PatchCodec.encode("{\"id\":\"x\",\"props\":{\"enable\":false}}"));
        assertEquals(1, patches.size());
        assertEquals(Boolean.FALSE, patches.get(0).props().get("enable"));
    }

    @Test
    void skipsEntriesWithoutId() {
        List<PatchCodec.Patch> patches =
                PatchCodec.decode(PatchCodec.encode("[{\"props\":{\"text\":\"orphan\"}}]"));
        assertFalse(patches.stream().anyMatch(p -> p.props().containsKey("text")));
        assertTrue(patches.isEmpty());
    }
}
