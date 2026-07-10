package org.windy.windui.core.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudCodecTest {

    @Test
    void decodesAnchoredPanelWithSubtree() {
        String json = "[{\"anchor\":\"top_right\",\"x\":6,\"y\":8,\"root\":{"
                + "\"type\":\"LinearLayout\",\"props\":{\"orientation\":1},"
                + "\"children\":[{\"type\":\"TextView\",\"props\":{\"text\":\"金币 1280\"}}]}}]";

        List<HudCodec.Panel> panels = HudCodec.decode(HudCodec.encode(json));

        assertEquals(1, panels.size());
        HudCodec.Panel p = panels.get(0);
        assertEquals(HudCodec.Anchor.TOP_RIGHT, p.anchor());
        assertEquals(6, p.x());
        assertEquals(8, p.y());
        assertEquals("LinearLayout", p.root().type());
        assertEquals("金币 1280", p.root().children().get(0).getString("text", ""));
    }

    @Test
    void emptyArrayMeansClear() {
        assertTrue(HudCodec.decode(HudCodec.encode("[]")).isEmpty());
    }

    @Test
    void unknownAnchorFallsBackTopLeft() {
        String json = "[{\"anchor\":\"nonsense\",\"root\":{\"type\":\"TextView\"}}]";
        assertEquals(HudCodec.Anchor.TOP_LEFT, HudCodec.decode(HudCodec.encode(json)).get(0).anchor());
    }
}
