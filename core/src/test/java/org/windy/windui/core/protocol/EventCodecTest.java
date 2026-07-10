package org.windy.windui.core.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventCodecTest {

    @Test
    void roundTripPreservesEventAndData() {
        EventCodec.Event decoded = EventCodec.decode(
                EventCodec.encode("buy_diamond", List.of("1", "__rb:qty:MjQ")));
        assertEquals("buy_diamond", decoded.eventId());
        assertEquals(List.of("1", "__rb:qty:MjQ"), decoded.data());
    }

    @Test
    void emptyDataOk() {
        EventCodec.Event decoded = EventCodec.decode(EventCodec.encode("confirm", List.of()));
        assertEquals("confirm", decoded.eventId());
        assertEquals(0, decoded.data().size());
    }

    @Test
    void unicodeEventAndArgs() {
        EventCodec.Event decoded = EventCodec.decode(EventCodec.encode("搜索", List.of("关键词 空格")));
        assertEquals("搜索", decoded.eventId());
        assertEquals("关键词 空格", decoded.data().get(0));
    }
}
