package es.mrdino.strobelights.service;

import es.mrdino.strobelights.model.BlindnessLevel;
import es.mrdino.strobelights.model.StrobeMode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CameraFlashCodecTest {

    @Test
    void packsRgbAndIndependentScreenStrengthIntoMarkerColor() {
        int packed = StrobeManager.packCameraFlashColor(0x2A80F4, 0.75);

        assertTrue(StrobeManager.isPackedCameraFlash(packed));
        assertEquals(13, packed >>> 20);
        assertEquals(95, packed >> 13 & 127);
        assertEquals(2, packed >> 9 & 15);
        assertEquals(8, packed >> 5 & 15);
        assertEquals(14, packed >> 1 & 15);
    }

    @Test
    void clampsStrengthToTheSevenBitShaderRange() {
        int packed = StrobeManager.packCameraFlashColor(0xFF0000, 5.0);
        assertEquals(127, packed >> 13 & 127);
    }

    @Test
    void packsPrivateOffscreenLightWithRgbIntensityAndCameraMode() {
        int packed = StrobeManager.packOffscreenLightColor(0x2A80F4, 12, 5);

        assertTrue(StrobeManager.isPackedOffscreenLight(packed));
        assertEquals(6, packed >>> 21);
        assertEquals(5, packed >> 18 & 7);
        assertEquals(12, packed >> 14 & 15);
        assertEquals(2, packed >> 10 & 15);
        assertEquals(8, packed >> 6 & 15);
        assertEquals(14, packed >> 2 & 15);
        assertEquals(2, packed & 3);
    }

    @Test
    void fixedCarrierDisablesFrustumCullingWithoutMovingTheLightSource() {
        double sourceY = 96.0;
        double range = 128.0;
        StrobeManager.FixedRenderCarrier carrier = StrobeManager.fixedRenderCarrier(
            sourceY,
            -64,
            320,
            range
        );

        assertEquals(sourceY, carrier.anchorY(), 1.0e-6);
        assertEquals(0.0, carrier.translationY(), 1.0e-6);
        assertEquals(0.0, carrier.displayWidth(), 1.0e-6);
        assertEquals(0.0, carrier.displayHeight(), 1.0e-6);
        assertTrue(carrier.viewRange() * 64.0 >= range);

        for (double edgeSourceY : new double[] {-63.5, 319.5}) {
            StrobeManager.FixedRenderCarrier edge = StrobeManager.fixedRenderCarrier(
                edgeSourceY,
                -64,
                320,
                range
            );
            assertEquals(edgeSourceY, edge.anchorY(), 1.0e-6);
            assertEquals(0.0, edge.translationY(), 1.0e-6);
        }
    }

    @Test
    void keepsTheWhiteCompatibilityLightStableOnlyForFastEnabledStrobes() {
        assertTrue(StrobeManager.needsStableVanillaFallback(
            StrobeMode.STROBE, true, 5, 10
        ));
        assertEquals(false, StrobeManager.needsStableVanillaFallback(
            StrobeMode.STROBE, true, 20, 10
        ));
        assertEquals(false, StrobeManager.needsStableVanillaFallback(
            StrobeMode.STATIC, true, 5, 10
        ));
        assertEquals(false, StrobeManager.needsStableVanillaFallback(
            StrobeMode.STROBE, false, 5, 10
        ));
    }

    @Test
    void screenFlashAlwaysRequiresTheSourceInsideItsConfiguredViewCone() {
        assertTrue(StrobeManager.meetsFlashViewRequirement(0.80, BlindnessLevel.LOW));
        assertTrue(StrobeManager.meetsFlashViewRequirement(0.15, BlindnessLevel.EXTREME));
        assertEquals(false,
            StrobeManager.meetsFlashViewRequirement(0.79, BlindnessLevel.LOW));
        assertEquals(false,
            StrobeManager.meetsFlashViewRequirement(-1.0, BlindnessLevel.EXTREME));
    }

    @Test
    void throwableFlashStrengthFallsContinuouslyWithDistance() {
        double close = StrobeManager.flashbangDistanceScale(1.0, 2.0, 24.0, 1.2);
        double middle = StrobeManager.flashbangDistanceScale(12.0, 2.0, 24.0, 1.2);
        double far = StrobeManager.flashbangDistanceScale(22.0, 2.0, 24.0, 1.2);

        assertEquals(1.0, close);
        assertTrue(close > middle);
        assertTrue(middle > far);
        assertTrue(far > 0.0);
        assertEquals(0.0,
            StrobeManager.flashbangDistanceScale(24.0, 2.0, 24.0, 1.2));
    }

    @Test
    void throwableFlashDurationIsLongestNearbyAndFallsWithDistance() {
        double closeScale = StrobeManager.flashbangDistanceScale(1.0, 5.0, 24.0, 1.2);
        double middleScale = StrobeManager.flashbangDistanceScale(12.0, 5.0, 24.0, 1.2);
        double farScale = StrobeManager.flashbangDistanceScale(22.0, 5.0, 24.0, 1.2);

        int close = StrobeManager.flashbangDurationTicks(closeScale, 100);
        int middle = StrobeManager.flashbangDurationTicks(middleScale, 100);
        int far = StrobeManager.flashbangDurationTicks(farScale, 100);

        assertEquals(100, close);
        assertTrue(close > middle);
        assertTrue(middle > far);
        assertTrue(far > 0);
        assertEquals(0, StrobeManager.flashbangDurationTicks(0.0, 100));
    }
}
