package es.mrdino.strobelights.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class StrobeColorsTest {

    @Test
    void parsesHexAndNamedColors() {
        assertEquals(0x12ABEF, StrobeColors.parse("#12abef").orElseThrow());
        assertEquals(0x12ABEF, StrobeColors.parse("0x12ABEF").orElseThrow());
        assertEquals(0xFF0000, StrobeColors.parse("rojo").orElseThrow());
        assertEquals(0x00FFFF, StrobeColors.parse("cyan").orElseThrow());
        assertEquals(0xFFFFFF, StrobeColors.parse("weiß").orElseThrow());
        assertEquals(0x0066FF, StrobeColors.parse("bleu").orElseThrow());
        assertEquals(0xFF7A00, StrobeColors.parse("arancione").orElseThrow());
    }

    @Test
    void rejectsMalformedColors() {
        assertTrue(StrobeColors.parse("#12345").isEmpty());
        assertTrue(StrobeColors.parse("not-a-color").isEmpty());
    }

    @Test
    void selectsAUsefulVanillaGlassApproximation() {
        assertEquals(Material.RED_STAINED_GLASS, StrobeColors.nearestGlass(0xFF0000));
        assertEquals(Material.BLUE_STAINED_GLASS, StrobeColors.nearestGlass(0x0040FF));
    }
}
