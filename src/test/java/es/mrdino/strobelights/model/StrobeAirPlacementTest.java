package es.mrdino.strobelights.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class StrobeAirPlacementTest {

    @Test
    void preservesExactAirCoordinatesAndSelfFace() {
        Strobe strobe = new Strobe(
            "air",
            UUID.randomUUID(),
            "world",
            12.375,
            70.625,
            -3.25,
            0xFFFFFF,
            5,
            15,
            100,
            BlindnessLevel.NONE,
            false,
            BlockFace.SELF,
            true
        );

        assertEquals(12.375, strobe.x());
        assertEquals(70.625, strobe.y());
        assertEquals(-3.25, strobe.z());
        assertEquals(12, strobe.blockX());
        assertEquals(-4, strobe.blockZ());
        assertEquals(BlockFace.SELF, strobe.face());

        strobe.setFlashPower(500);
        assertEquals(200, strobe.flashPower());
        strobe.setFlashPower(-10);
        assertEquals(0, strobe.flashPower());
    }

    @Test
    void newStrobesDefaultToWhiteLowFlashAtFiftyPercent() {
        assertEquals(0xFFFFFF, Strobe.DEFAULT_COLOR);
        assertEquals(BlindnessLevel.LOW, Strobe.DEFAULT_BLINDNESS);
        assertEquals(50, Strobe.DEFAULT_FLASH_POWER);
        assertEquals(StrobeMode.STROBE, Strobe.DEFAULT_MODE);
    }
}
