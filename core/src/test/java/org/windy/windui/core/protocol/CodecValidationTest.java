package org.windy.windui.core.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CodecValidationTest {

    @Test
    void framedCodecsRejectTruncatedPayloads() {
        byte[] control = ControlCodec.encode(List.of(
                ControlCodec.CtrlAction.of("closeScreen")));
        byte[] patch = PatchCodec.encode("{\"id\":\"x\",\"props\":{}}");
        byte[] hud = HudCodec.encode("[]");

        assertThrows(IllegalArgumentException.class,
                () -> ControlCodec.decodeJson(Arrays.copyOf(control, control.length - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> PatchCodec.decode(Arrays.copyOf(patch, patch.length - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> HudCodec.decode(Arrays.copyOf(hud, hud.length - 1)));
    }

    @Test
    void framedCodecsRejectTrailingBytes() {
        byte[] control = ControlCodec.encode(List.of(
                ControlCodec.CtrlAction.of("closeScreen")));
        byte[] patch = PatchCodec.encode("{\"id\":\"x\",\"props\":{}}");
        byte[] hud = HudCodec.encode("[]");

        assertThrows(IllegalArgumentException.class,
                () -> ControlCodec.decodeJson(withTrailingByte(control)));
        assertThrows(IllegalArgumentException.class,
                () -> PatchCodec.decode(withTrailingByte(patch)));
        assertThrows(IllegalArgumentException.class,
                () -> HudCodec.decode(withTrailingByte(hud)));
    }

    @Test
    void framedCodecsRejectInvalidUtf8() {
        byte[] invalid = ByteBuffer.allocate(6)
                .putInt(2)
                .put((byte) 0xC3)
                .put((byte) 0x28)
                .array();

        assertThrows(IllegalArgumentException.class,
                () -> ControlCodec.decodeJson(invalid));
        assertThrows(IllegalArgumentException.class,
                () -> PatchCodec.decode(invalid));
        assertThrows(IllegalArgumentException.class,
                () -> HudCodec.decode(invalid));
    }

    @Test
    void assetRejectsHashLengthBeyondRemainingData() {
        byte[] malformed = ByteBuffer.allocate(6)
                .putInt(10)
                .put((byte) 'a')
                .put((byte) 'b')
                .array();

        assertThrows(IllegalArgumentException.class,
                () -> AssetCodec.decode(malformed));
    }

    @Test
    void assetRejectsEmptyAndOversizedHash() {
        assertThrows(IllegalArgumentException.class,
                () -> AssetCodec.encode("", new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> AssetCodec.encode("x".repeat(257), new byte[0]));
    }

    @Test
    void eventRejectsEmptyIdTooManyArgumentsAndOversizedArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> EventCodec.encode("", List.of()));

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 257; i++) {
            tooMany.add("x");
        }
        assertThrows(IllegalArgumentException.class,
                () -> EventCodec.encode("event", tooMany));

        assertThrows(IllegalArgumentException.class,
                () -> EventCodec.encode("event", List.of("x".repeat(65536))));
    }

    @Test
    void eventRejectsTruncationTrailingBytesAndInvalidUtf8() {
        byte[] valid = EventCodec.encode("event", List.of("arg"));

        assertThrows(IllegalArgumentException.class,
                () -> EventCodec.decode(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> EventCodec.decode(withTrailingByte(valid)));

        byte[] invalidUtf8 = new byte[]{
                0, 2, (byte) 0xC3, 0x28,
                0, 0
        };
        assertThrows(IllegalArgumentException.class,
                () -> EventCodec.decode(invalidUtf8));
    }

    @Test
    void eventRejectsDeclaredArgumentCountAboveBusinessLimit() {
        byte[] eventId = "event".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = ByteBuffer.allocate(2 + eventId.length + 2)
                .putShort((short) eventId.length)
                .put(eventId)
                .putShort((short) 257)
                .array();

        assertThrows(IllegalArgumentException.class,
                () -> EventCodec.decode(malformed));
    }

    private static byte[] withTrailingByte(byte[] input) {
        byte[] output = Arrays.copyOf(input, input.length + 1);
        output[output.length - 1] = 0x55;
        return output;
    }
}
