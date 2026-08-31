package es.mrdino.strobelights.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlindnessLevelTest {

    @Test
    void acceptsEnglishSpanishAndNumericLevels() {
        assertEquals(BlindnessLevel.NONE, BlindnessLevel.parse("none").orElseThrow());
        assertEquals(BlindnessLevel.LOW, BlindnessLevel.parse("baja").orElseThrow());
        assertEquals(BlindnessLevel.MEDIUM, BlindnessLevel.parse("2").orElseThrow());
        assertEquals(BlindnessLevel.HIGH, BlindnessLevel.parse("fuerte").orElseThrow());
        assertEquals(BlindnessLevel.EXTREME, BlindnessLevel.parse("EXTREMA").orElseThrow());
    }

    @Test
    void rejectsUnknownLevels() {
        assertTrue(BlindnessLevel.parse("imposible").isEmpty());
    }

    @Test
    void flashbangLevelsIncreaseOverlayAndFade() {
        assertEquals(0.0, BlindnessLevel.NONE.screenStrength());
        assertTrue(BlindnessLevel.LOW.screenStrength() < BlindnessLevel.MEDIUM.screenStrength());
        assertTrue(BlindnessLevel.MEDIUM.screenStrength() < BlindnessLevel.HIGH.screenStrength());
        assertTrue(BlindnessLevel.HIGH.screenStrength() < BlindnessLevel.EXTREME.screenStrength());
        assertTrue(BlindnessLevel.EXTREME.fadeOutTicks() > BlindnessLevel.HIGH.fadeOutTicks());
    }
}
