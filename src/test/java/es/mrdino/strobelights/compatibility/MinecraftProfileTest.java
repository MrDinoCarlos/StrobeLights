package es.mrdino.strobelights.compatibility;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MinecraftProfileTest {
    @Test void selectsEachMaintainedPackWithoutCrossingAnAbiBoundary() {
        for (MinecraftProfile profile : MinecraftProfile.values()) {
            assertSame(profile, MinecraftProfile.forVersion(profile.version()));
            assertEquals("StrobeLights-ResourcePack-" + profile.version() + ".zip", profile.packName());
            assertTrue(profile.supportsRgb());
        }
        assertEquals("1.21.X", MinecraftProfile.forVersion("1.21.11").family());
        assertEquals("26.1.X", MinecraftProfile.forVersion("26.1.2").family());
        assertEquals("26.2.X", MinecraftProfile.forVersion("26.2").family());
        assertEquals("26.3.X", MinecraftProfile.forVersion("26.3").family());
    }

    @Test void rejectsUntestedVersionsInsteadOfSendingTheWrongShaders() {
        for (String version : new String[] {"1.20.6", "1.21", "1.21.5", "26.3-snapshot-1", "26.4"}) {
            assertThrows(IllegalArgumentException.class, () -> MinecraftProfile.forVersion(version));
        }
    }

    @Test void preservesTheFixedSourceCarrierSpecificTo12111() {
        for (MinecraftProfile profile : MinecraftProfile.values()) {
            assertEquals(profile == MinecraftProfile.MC_1_21_11, profile.sourceAnchored());
        }
    }
}
