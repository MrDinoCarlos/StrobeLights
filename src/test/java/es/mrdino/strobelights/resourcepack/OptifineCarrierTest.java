package es.mrdino.strobelights.resourcepack;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OptifineCarrierTest {
    @Test void stableCarrierAcceptsBothOriginalAndClientResizedTexture() throws Exception {
        Path pack = Path.of("resource-pack/assets/strobelights");
        var source = ImageIO.read(pack.resolve("textures/item/internal/strobe_rgb_carrier.png").toFile());
        String vertex = Files.readString(pack.resolve("shaders/include/stable_slot_vertex.glsl"));
        assertEquals(0x0525D353, source.getRGB(0, 0));
        assertTrue(vertex.contains("ivec4(37, 211, 83, 5)"));
        // Same Java2D SRC_OVER preparation used by OptiFine J3 TextureUtils.scaleImage.
        // A low-alpha solid texture changes RGB even under nearest-neighbour resizing.
        for (int size : new int[] {16, 32, 64, 128}) {
            var scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            var graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                graphics.drawImage(source, 0, 0, size, size, null);
            } finally { graphics.dispose(); }
            assertEquals(0x0533CC66, scaled.getRGB(size / 2, size / 2));
            assertTrue(vertex.contains("ivec4(51, 204, 102, 5)"), "Resized carrier must still be recognized");
        }
        assertTrue(vertex.contains("!strobeStableValue(value)"), "Ordinary items must remain excluded");
    }
}
