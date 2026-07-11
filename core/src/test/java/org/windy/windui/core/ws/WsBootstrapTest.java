package org.windy.windui.core.ws;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsBootstrapTest {

    @Test
    void roundTripPreservesHostPortAndToken() {
        String encoded = WsBootstrap.encode(
                "ws.example.com",
                25599,
                "0123456789abcdef");

        assertTrue(WsBootstrap.isBootstrap(encoded));

        WsBootstrap.Info info = WsBootstrap.parse(encoded);
        assertEquals("ws.example.com", info.host());
        assertEquals(25599, info.port());
        assertEquals("0123456789abcdef", info.token());
    }

    @Test
    void emptyHostRemainsValidForClientFallback() {
        String encoded = WsBootstrap.encode(
                "",
                25599,
                "session-token");

        WsBootstrap.Info info = WsBootstrap.parse(encoded);
        assertEquals("", info.host());
        assertEquals(25599, info.port());
        assertEquals("session-token", info.token());
    }

    @Test
    void nullHostIsEncodedAsEmptyHost() {
        String encoded = WsBootstrap.encode(
                null,
                25599,
                "session-token");

        WsBootstrap.Info info = WsBootstrap.parse(encoded);
        assertEquals("", info.host());
    }

    @Test
    void supportsIpv4AndIpv6TextHosts() {
        WsBootstrap.Info ipv4 = WsBootstrap.parse(
                WsBootstrap.encode(
                        "127.0.0.1",
                        25599,
                        "token"));
        WsBootstrap.Info ipv6 = WsBootstrap.parse(
                WsBootstrap.encode(
                        "2001:db8::1",
                        25599,
                        "token"));

        assertEquals("127.0.0.1", ipv4.host());
        assertEquals("2001:db8::1", ipv6.host());
    }

    @Test
    void nonBootstrapMessagesAreRejected() {
        assertFalse(WsBootstrap.isBootstrap(null));
        assertFalse(WsBootstrap.isBootstrap(""));
        assertFalse(WsBootstrap.isBootstrap("普通聊天消息"));
        assertNull(WsBootstrap.parse(null));
        assertNull(WsBootstrap.parse("普通聊天消息"));
    }

    @Test
    void malformedFieldCountsAreRejected() {
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t25599"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t25599\ttoken\textra"));
    }

    @Test
    void invalidPortsAreRejectedDuringParsing() {
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t\t token"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t0\ttoken"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t-1\ttoken"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t65536\ttoken"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t999999\ttoken"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t25x99\ttoken"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t 25599\ttoken"));
    }

    @Test
    void invalidPortsAreRejectedDuringEncoding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode("host", 0, "token"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode("host", -1, "token"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode("host", 65536, "token"));
    }

    @Test
    void emptyBlankAndNullTokensAreRejected() {
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t25599\t"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t25599\t   "));

        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode("host", 25599, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode("host", 25599, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode("host", 25599, "   "));
    }

    @Test
    void controlCharactersAreRejected() {
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "bad\nhost\t25599\ttoken"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t25599\tbad\ntoken"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL + "host\t25599\tbad\u0000token"));

        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode("bad\nhost", 25599, "token"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode("host", 25599, "bad\rtoken"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode("host", 25599, "bad\u007Ftoken"));
    }

    @Test
    void oversizedHostAndTokenAreRejected() {
        String oversizedHost = "h".repeat(256);
        String oversizedToken = "t".repeat(513);

        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode(
                        oversizedHost,
                        25599,
                        "token"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WsBootstrap.encode(
                        "host",
                        25599,
                        oversizedToken));

        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL
                        + oversizedHost
                        + "\t25599\ttoken"));
        assertNull(WsBootstrap.parse(
                WsBootstrap.SENTINEL
                        + "host\t25599\t"
                        + oversizedToken));
    }

    @Test
    void recordConstructorEnforcesSameInvariants() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WsBootstrap.Info(
                        "host",
                        0,
                        "token"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WsBootstrap.Info(
                        "host",
                        25599,
                        ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WsBootstrap.Info(
                        "bad\thost",
                        25599,
                        "token"));
    }
}
