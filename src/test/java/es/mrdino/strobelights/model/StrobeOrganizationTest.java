package es.mrdino.strobelights.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class StrobeOrganizationTest {

    @Test
    void clampsAndQuantizesExpansionToTheShaderSteps() {
        Strobe strobe = strobe(1.0, "");

        strobe.setExpansion(1.37);
        assertEquals(1.25, strobe.expansion());
        assertEquals(4, strobe.expansionCode());

        strobe.setExpansion(99.0);
        assertEquals(Strobe.MAXIMUM_EXPANSION, strobe.expansion());
        assertEquals(15, strobe.expansionCode());

        strobe.setExpansion(-5.0);
        assertEquals(Strobe.MINIMUM_EXPANSION, strobe.expansion());
        assertEquals(0, strobe.expansionCode());
    }

    @Test
    void storesAnOptionalTrimmedGroup() {
        Strobe strobe = strobe(1.0, "  stage_left  ");
        assertTrue(strobe.hasGroup());
        assertEquals("stage_left", strobe.group());

        strobe.setGroup(null);
        assertFalse(strobe.hasGroup());
        assertEquals(Strobe.DEFAULT_GROUP, strobe.group());
    }

    private static Strobe strobe(double expansion, String group) {
        return new Strobe(
            "test",
            UUID.randomUUID(),
            "world",
            0.0,
            64.0,
            0.0,
            Strobe.DEFAULT_COLOR,
            Strobe.DEFAULT_REFRESH_TICKS,
            Strobe.DEFAULT_LIGHT_LEVEL,
            Strobe.DEFAULT_FLASH_POWER,
            Strobe.DEFAULT_BLINDNESS,
            false,
            BlockFace.UP,
            true,
            Strobe.DEFAULT_MODE,
            expansion,
            group
        );
    }
}
