package org.windy.windui.core.protocol;

import org.junit.jupiter.api.Test;
import org.windy.windui.core.parser.UIJsonParser;
import org.windy.windui.core.protocol.ControlCodec.CtrlAction;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlCodecTest {

    @Test
    void roundTripThroughParseActions() {
        byte[] wire = ControlCodec.encode(List.of(
                CtrlAction.of("executeCommand", "/spawn"),
                CtrlAction.of("showToast", "已传送"),
                CtrlAction.of("closeScreen")));

        List<Map<String, Object>> actions = UIJsonParser.parseActions(ControlCodec.decodeJson(wire));

        assertEquals(3, actions.size());
        assertEquals("executeCommand", actions.get(0).get("action"));
        assertEquals(List.of("/spawn"), actions.get(0).get("args"));
        assertEquals("showToast", actions.get(1).get("action"));
        assertEquals(List.of("已传送"), actions.get(1).get("args"));
        assertEquals("closeScreen", actions.get(2).get("action"));
        assertEquals(List.of(), actions.get(2).get("args"));
    }

    @Test
    void emptyBatchDecodesToEmptyArray() {
        List<Map<String, Object>> actions =
                UIJsonParser.parseActions(ControlCodec.decodeJson(ControlCodec.encode(List.of())));
        assertEquals(0, actions.size());
    }
}
