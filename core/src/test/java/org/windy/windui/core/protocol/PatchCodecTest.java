package org.windy.windui.core.protocol;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                PatchCodec.decode(PatchCodec.encode(
                        "{\"id\":\"x\",\"props\":{\"enable\":false}}"));

        assertEquals(1, patches.size());
        assertEquals(Boolean.FALSE, patches.get(0).props().get("enable"));
    }

    @Test
    void missingPropsDefaultsToEmptyMap() {
        List<PatchCodec.Patch> patches =
                PatchCodec.decode(PatchCodec.encode("{\"id\":\"x\"}"));

        assertEquals(1, patches.size());
        assertTrue(patches.get(0).props().isEmpty());
    }

    @Test
    void nullScalarPropertyIsPreserved() {
        List<PatchCodec.Patch> patches =
                PatchCodec.decode(PatchCodec.encode(
                        "{\"id\":\"x\",\"props\":{\"tip\":null}}"));

        assertTrue(patches.get(0).props().containsKey("tip"));
        assertEquals(null, patches.get(0).props().get("tip"));
    }

    @Test
    void rejectsEntriesWithoutStringId() {
        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode(
                        "[{\"props\":{\"text\":\"orphan\"}}]")));
        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode(
                        "[{\"id\":123,\"props\":{}}]")));
        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode(
                        "[{\"id\":\"   \",\"props\":{}}]")));
    }

    @Test
    void rejectsNonObjectEntriesAndProps() {
        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode("[null]")));
        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode(
                        "{\"id\":\"x\",\"props\":[]}")));
        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode(
                        "{\"id\":\"x\",\"props\":\"bad\"}")));
    }

    @Test
    void rejectsNestedPropertyValues() {
        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode(
                        "{\"id\":\"x\",\"props\":{\"nested\":{\"value\":1}}}")));
        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode(
                        "{\"id\":\"x\",\"props\":{\"items\":[1,2,3]}}")));
    }

    @Test
    void rejectsTooManyPatches() {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < 257; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"p")
                    .append(index)
                    .append("\",\"props\":{}}");
        }
        json.append(']');

        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode(json.toString())));
    }

    @Test
    void rejectsOversizedId() {
        String id = "界".repeat(86);

        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode(
                        "{\"id\":\"" + id + "\",\"props\":{}}")));
    }

    @Test
    void rejectsTooManyProperties() {
        StringBuilder json = new StringBuilder(
                "{\"id\":\"x\",\"props\":{");
        for (int index = 0; index < 129; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("\"p")
                    .append(index)
                    .append("\":")
                    .append(index);
        }
        json.append("}}");

        assertThrows(
                JsonParseException.class,
                () -> PatchCodec.decode(PatchCodec.encode(json.toString())));
    }

    @Test
    void patchRecordDefensivelyCopiesProperties() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("text", "before");

        PatchCodec.Patch patch = new PatchCodec.Patch("status", source);
        source.put("text", "after");

        assertEquals("before", patch.props().get("text"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> patch.props().put("visible", true));
    }

    @Test
    void patchRecordRejectsNestedValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PatchCodec.Patch(
                        "status",
                        Map.of("nested", Map.of("value", 1))));
    }
}
