package es.mrdino.strobelights.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StrobeTimingTest {

    @Test
    void convertsPhaseTicksToFrequencyAndMilliseconds() {
        assertEquals(10.0, StrobeTiming.flashesPerSecond(1));
        assertEquals(2.0, StrobeTiming.flashesPerSecond(5));
        assertEquals(250, StrobeTiming.millisecondsPerPhase(5));
    }

    @Test
    void clampsInvalidRefreshValues() {
        assertEquals(10.0, StrobeTiming.flashesPerSecond(0));
        assertEquals(50, StrobeTiming.millisecondsPerPhase(-10));
    }
}
