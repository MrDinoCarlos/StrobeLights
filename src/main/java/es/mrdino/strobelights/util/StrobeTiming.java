package es.mrdino.strobelights.util;

public final class StrobeTiming {

    private StrobeTiming() {
    }

    public static double flashesPerSecond(int refreshTicks) {
        return 10.0 / Math.max(1, refreshTicks);
    }

    public static int millisecondsPerPhase(int refreshTicks) {
        return Math.max(1, refreshTicks) * 50;
    }
}
