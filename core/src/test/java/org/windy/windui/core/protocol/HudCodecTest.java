package org.windy.windui.core.protocol;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudCodecTest {

    @Test
    void decodesAnchoredPanelWithSubtree() {
        String json = "[{\"anchor\":\"top_right\",\"x\":6,\"y\":8,\"root\":{"
                + "\"type\":\"LinearLayout\",\"props\":{\"orientation\":1},"
                + "\"children\":[{\"type\":\"TextView\",\"props\":{\"text\":\"金币 1280\"}}]}}]";

        List<HudCodec.Panel> panels =
                HudCodec.decode(HudCodec.encode(json));

        assertEquals(1, panels.size());
        HudCodec.Panel panel = panels.get(0);
        assertEquals(HudCodec.Anchor.TOP_RIGHT, panel.anchor());
        assertEquals(6, panel.x());
        assertEquals(8, panel.y());
        assertEquals("LinearLayout", panel.root().type());
        assertEquals(
                "金币 1280",
                panel.root().children().get(0).getString("text", ""));
    }

    @Test
    void emptyArrayMeansClear() {
        assertTrue(HudCodec.decode(HudCodec.encode("[]")).isEmpty());
    }

    @Test
    void unknownAnchorFallsBackTopLeft() {
        String json =
                "[{\"anchor\":\"nonsense\",\"root\":{\"type\":\"TextView\"}}]";

        assertEquals(
                HudCodec.Anchor.TOP_LEFT,
                HudCodec.decode(HudCodec.encode(json)).get(0).anchor());
    }

    @Test
    void missingOptionalFieldsUseDefaults() {
        String json =
                "[{\"root\":{\"type\":\"TextView\",\"props\":{\"text\":\"ok\"}}}]";

        HudCodec.Panel panel =
                HudCodec.decode(HudCodec.encode(json)).get(0);

        assertEquals(HudCodec.Anchor.TOP_LEFT, panel.anchor());
        assertEquals(0, panel.x());
        assertEquals(0, panel.y());
    }

    @Test
    void rejectsNonArrayRoot() {
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode(
                        "{\"root\":{\"type\":\"TextView\"}}")));
    }

    @Test
    void rejectsNonObjectPanel() {
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode("[null]")));
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode("[1]")));
    }

    @Test
    void rejectsMissingOrInvalidRoot() {
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode("[{}]")));
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode(
                        "[{\"root\":[]}]")));
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode(
                        "[{\"root\":{\"props\":{}}}]")));
    }

    @Test
    void rejectsInvalidAnchorType() {
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode(
                        "[{\"anchor\":123,\"root\":{\"type\":\"TextView\"}}]")));
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode(
                        "[{\"anchor\":{},\"root\":{\"type\":\"TextView\"}}]")));
    }

    @Test
    void rejectsInvalidCoordinates() {
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode(
                        "[{\"x\":\"6\",\"root\":{\"type\":\"TextView\"}}]")));
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode(
                        "[{\"y\":1.5,\"root\":{\"type\":\"TextView\"}}]")));
        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode(
                        "[{\"x\":2147483648,\"root\":{\"type\":\"TextView\"}}]")));
    }

    @Test
    void rejectsTooManyPanels() {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < 65; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"root\":{\"type\":\"TextView\",")
                    .append("\"props\":{\"text\":\"")
                    .append(index)
                    .append("\"}}}");
        }
        json.append(']');

        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode(json.toString())));
    }

    @Test
    void panelTreeUsesUiParserResourceLimits() {
        StringBuilder json = new StringBuilder(
                "[{\"root\":");
        for (int index = 0; index < 66; index++) {
            json.append(
                    "{\"type\":\"LinearLayout\",\"children\":[");
        }
        json.append("{\"type\":\"TextView\"}");
        for (int index = 0; index < 66; index++) {
            json.append("]}");
        }
        json.append("}]");

        assertThrows(
                JsonParseException.class,
                () -> HudCodec.decode(HudCodec.encode(json.toString())));
    }
}
