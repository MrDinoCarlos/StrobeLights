package es.mrdino.strobelights.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import org.bukkit.Material;

public final class StrobeColors {

    private static final Map<String, Integer> NAMED = namedColors();
    private static final List<GlassColor> GLASS_COLORS = List.of(
        new GlassColor(Material.WHITE_STAINED_GLASS, 0xF9FFFE),
        new GlassColor(Material.ORANGE_STAINED_GLASS, 0xF9801D),
        new GlassColor(Material.MAGENTA_STAINED_GLASS, 0xC74EBD),
        new GlassColor(Material.LIGHT_BLUE_STAINED_GLASS, 0x3AB3DA),
        new GlassColor(Material.YELLOW_STAINED_GLASS, 0xFED83D),
        new GlassColor(Material.LIME_STAINED_GLASS, 0x80C71F),
        new GlassColor(Material.PINK_STAINED_GLASS, 0xF38BAA),
        new GlassColor(Material.GRAY_STAINED_GLASS, 0x474F52),
        new GlassColor(Material.LIGHT_GRAY_STAINED_GLASS, 0x9D9D97),
        new GlassColor(Material.CYAN_STAINED_GLASS, 0x169C9C),
        new GlassColor(Material.PURPLE_STAINED_GLASS, 0x8932B8),
        new GlassColor(Material.BLUE_STAINED_GLASS, 0x3C44AA),
        new GlassColor(Material.BROWN_STAINED_GLASS, 0x835432),
        new GlassColor(Material.GREEN_STAINED_GLASS, 0x5E7C16),
        new GlassColor(Material.RED_STAINED_GLASS, 0xB02E26),
        new GlassColor(Material.BLACK_STAINED_GLASS, 0x1D1D21)
    );

    private StrobeColors() {
    }

    public static OptionalInt parse(String input) {
        if (input == null || input.isBlank()) {
            return OptionalInt.empty();
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMED.get(normalized);
        if (named != null) {
            return OptionalInt.of(named);
        }

        String hex = normalized.startsWith("#") ? normalized.substring(1) : normalized;
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        if (!hex.matches("[0-9a-f]{6}")) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(hex, 16));
    }

    public static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    public static List<String> suggestions() {
        return List.of(
            "#FF0000", "#00FF00", "#0000FF", "white", "red", "orange",
            "yellow", "lime", "green", "cyan", "blue", "purple", "magenta", "pink"
        );
    }

    public static Material nearestGlass(int rgb) {
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        GlassColor best = GLASS_COLORS.getFirst();
        long bestDistance = Long.MAX_VALUE;
        for (GlassColor candidate : GLASS_COLORS) {
            int candidateRed = candidate.rgb >> 16 & 0xFF;
            int candidateGreen = candidate.rgb >> 8 & 0xFF;
            int candidateBlue = candidate.rgb & 0xFF;
            long redDelta = red - candidateRed;
            long greenDelta = green - candidateGreen;
            long blueDelta = blue - candidateBlue;
            long distance = redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best.material;
    }

    private static Map<String, Integer> namedColors() {
        Map<String, Integer> colors = new LinkedHashMap<>();
        colors.put("white", 0xFFFFFF);
        colors.put("blanco", 0xFFFFFF);
        colors.put("blanc", 0xFFFFFF);
        colors.put("weiß", 0xFFFFFF);
        colors.put("weiss", 0xFFFFFF);
        colors.put("bianco", 0xFFFFFF);
        colors.put("red", 0xFF0000);
        colors.put("rojo", 0xFF0000);
        colors.put("rouge", 0xFF0000);
        colors.put("rot", 0xFF0000);
        colors.put("rosso", 0xFF0000);
        colors.put("orange", 0xFF7A00);
        colors.put("naranja", 0xFF7A00);
        colors.put("arancione", 0xFF7A00);
        colors.put("yellow", 0xFFFF00);
        colors.put("amarillo", 0xFFFF00);
        colors.put("jaune", 0xFFFF00);
        colors.put("gelb", 0xFFFF00);
        colors.put("giallo", 0xFFFF00);
        colors.put("lime", 0x7FFF00);
        colors.put("green", 0x00FF3C);
        colors.put("verde", 0x00FF3C);
        colors.put("vert", 0x00FF3C);
        colors.put("grün", 0x00FF3C);
        colors.put("gruen", 0x00FF3C);
        colors.put("cyan", 0x00FFFF);
        colors.put("cian", 0x00FFFF);
        colors.put("ciano", 0x00FFFF);
        colors.put("blue", 0x0066FF);
        colors.put("azul", 0x0066FF);
        colors.put("bleu", 0x0066FF);
        colors.put("blau", 0x0066FF);
        colors.put("blu", 0x0066FF);
        colors.put("purple", 0x8A2BE2);
        colors.put("morado", 0x8A2BE2);
        colors.put("violet", 0x8A2BE2);
        colors.put("violett", 0x8A2BE2);
        colors.put("viola", 0x8A2BE2);
        colors.put("magenta", 0xFF00FF);
        colors.put("pink", 0xFF69B4);
        colors.put("rosa", 0xFF69B4);
        colors.put("rose", 0xFF69B4);
        return Map.copyOf(colors);
    }

    private record GlassColor(Material material, int rgb) {
    }
}
