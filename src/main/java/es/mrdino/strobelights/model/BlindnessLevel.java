package es.mrdino.strobelights.model;

import java.util.Locale;
import java.util.Optional;

public enum BlindnessLevel {
    NONE(0.0, 0, 1.0),
    LOW(0.20, 5, 0.80),
    MEDIUM(0.45, 10, 0.65),
    HIGH(0.72, 18, 0.45),
    EXTREME(1.0, 36, 0.15);

    private final double screenStrength;
    private final int fadeOutTicks;
    private final double viewCosine;

    BlindnessLevel(double screenStrength, int fadeOutTicks, double viewCosine) {
        this.screenStrength = screenStrength;
        this.fadeOutTicks = fadeOutTicks;
        this.viewCosine = viewCosine;
    }

    public double screenStrength() {
        return screenStrength;
    }

    public int fadeOutTicks() {
        return fadeOutTicks;
    }

    public double viewCosine() {
        return viewCosine;
    }

    public boolean enabled() {
        return this != NONE;
    }

    public static Optional<BlindnessLevel> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "0", "NONE", "NO", "OFF", "NINGUNA", "NINGUNO", "AUCUN", "KEINER",
                "NESSUNO" -> Optional.of(NONE);
            case "1", "LOW", "BAJA", "BAJO", "SUAVE", "FAIBLE", "NIEDRIG", "BASSO" ->
                Optional.of(LOW);
            case "2", "MEDIUM", "MEDIA", "MEDIO", "MOYEN", "MITTEL" -> Optional.of(MEDIUM);
            case "3", "HIGH", "ALTA", "ALTO", "FUERTE", "ÉLEVÉ", "ELEVE", "HOCH" ->
                Optional.of(HIGH);
            case "4", "EXTREME", "EXTREMA", "EXTREMO", "EXTRÊME", "EXTREM", "ESTREMO" ->
                Optional.of(EXTREME);
            default -> Optional.empty();
        };
    }
}
