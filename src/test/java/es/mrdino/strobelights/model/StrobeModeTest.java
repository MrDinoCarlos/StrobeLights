package es.mrdino.strobelights.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StrobeModeTest {

    @Test
    void parsesStrobeAndStaticAliases() {
        assertEquals(StrobeMode.STROBE, StrobeMode.parse("blink").orElseThrow());
        assertEquals(StrobeMode.STATIC, StrobeMode.parse("static").orElseThrow());
        assertEquals(StrobeMode.STATIC, StrobeMode.parse("estático").orElseThrow());
        assertTrue(StrobeMode.parse("unknown").isEmpty());
    }
}
