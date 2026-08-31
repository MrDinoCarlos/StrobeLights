package es.mrdino.strobelights.model;

import java.util.Locale;
import java.util.Optional;

/** Controls whether a light blinks or remains continuously illuminated. */
public enum StrobeMode {
    STROBE,
    STATIC;

    public static Optional<StrobeMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "strobe", "blink", "blinking", "parpadeo", "intermitente",
                "clignotant", "blinkend", "lampeggiante" ->
                Optional.of(STROBE);
            case "static", "steady", "continuous", "estatico", "estático", "estática",
                "statique", "statisch", "statico" ->
                Optional.of(STATIC);
            default -> Optional.empty();
        };
    }
}
