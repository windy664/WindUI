package org.windy.windui.core.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolFeaturesTest {

    @Test
    void currentClientSupportsPatch() {
        assertTrue(ProtocolFeatures.supports(WindUIProtocol.VERSION, ProtocolFeatures.PATCH));
    }

    @Test
    void baselineClientDoesNotSupportPatch() {
        assertFalse(ProtocolFeatures.supports(0, ProtocolFeatures.PATCH), "v0 基线应不支持 patch");
    }

    @Test
    void unknownFeatureNeverSupported() {
        assertFalse(ProtocolFeatures.supports(Integer.MAX_VALUE, "no_such_feature"));
    }
}
