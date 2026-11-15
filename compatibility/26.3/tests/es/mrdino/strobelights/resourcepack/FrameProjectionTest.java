package es.mrdino.strobelights.resourcepack;

import static org.junit.jupiter.api.Assertions.*;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class FrameProjectionTest {
    @Test void reconstructsViewSpaceAcrossBothDepthConventionsFovAndFarPlanes() {
        for (boolean zeroToOne : new boolean[] {false, true}) {
            for (int fov : new int[] {30, 70, 110}) {
                for (int far : new int[] {512, 1024, 2048}) {
                    // Minecraft 26.3 reverses Z: near depth is one, far/sky is zero.
                    Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fov), 16f / 9f, far, 0.05f, zeroToOne);
                    // Include a shifted frustum, as used by jittered projections.
                    projection.m20(0.001f).m21(-0.002f);
                    for (float distance : new float[] {0.2f, 2f, 18f, 128f, 400f}) {
                        var expected = new Vector4f(distance * 0.1f, distance * -0.2f, -distance, 1f);
                        var clip = projection.transform(new Vector4f(expected));
                        float ndcX = clip.x / clip.w;
                        float ndcY = clip.y / clip.w;
                        float ndcZ = clip.z / clip.w;
                        float depth = zeroToOne ? ndcZ : ndcZ * 0.5f + 0.5f;
                        assertTrue(depth > 0f && depth < 1f);
                        float device = zeroToOne ? depth : depth * 2f - 1f;
                        float z = -projection.m32() / (device + projection.m22());
                        float x = (ndcX + projection.m20()) * (-z) / projection.m00();
                        float y = (ndcY + projection.m21()) * (-z) / projection.m11();
                        float tolerance = Math.max(0.0001f, distance * 0.001f);
                        assertEquals(expected.x, x, tolerance);
                        assertEquals(expected.y, y, tolerance);
                        assertEquals(expected.z, z, tolerance);
                    }
                }
            }
        }
    }
}
