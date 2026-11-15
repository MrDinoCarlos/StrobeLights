package es.mrdino.strobelights.resourcepack;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FramePackContractTest {
    private static final Path PACK = Path.of("resource-pack");

    @Test void advertisesTheStableXmasLightsAbiAndItsIndependentCapacity() throws Exception {
        var contract = JsonParser.parseString(Files.readString(PACK.resolve(
            "assets/strobelights/strobelights-integration.json"))).getAsJsonObject();
        assertEquals("slots3x3_depth_v1", contract.get("stable_source_protocol").getAsString());
        assertEquals("frame_slots_5x7_v1", contract.get("stable_source_transport").getAsString());
        assertEquals(128, contract.get("max_integration_sources").getAsInt());
        assertEquals(192, contract.get("max_frame_packets").getAsInt());
        var chain = JsonParser.parseString(Files.readString(PACK.resolve(
            "assets/minecraft/post_effect/end_of_frame.json"))).getAsJsonObject();
        for (int i = 0; i < 24; i++) {
            var firstSlot = chain.getAsJsonArray("passes").get(i).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray("FrameConfig").get(0)
                .getAsJsonObject().get("value").getAsInt();
            assertEquals(i * 8, firstSlot);
        }
    }

    @Test void alwaysOnChainUsesMainDepthAndDistinctReadWriteTargets() throws Exception {
        var chain = JsonParser.parseString(Files.readString(PACK.resolve("assets/minecraft/post_effect/end_of_frame.json"))).getAsJsonObject();
        assertFalse(Files.exists(PACK.resolve("assets/minecraft/post_effect/transparency.json")));
        assertEquals(26, chain.getAsJsonArray("passes").size());
        for (var entry : chain.getAsJsonArray("passes")) {
            var pass = entry.getAsJsonObject();
            var output = pass.get("output").getAsString();
            for (var input : pass.getAsJsonArray("inputs")) assertNotEquals(output, input.getAsJsonObject().get("target").getAsString());
        }
        for (int i = 0; i < 8; i++) {
            var pass = chain.getAsJsonArray("passes").get(i).getAsJsonObject();
            var depth = pass.getAsJsonArray("inputs").get(1).getAsJsonObject();
            assertEquals("minecraft:main", depth.get("target").getAsString());
            assertTrue(depth.get("use_depth_buffer").getAsBoolean());
        }
    }

    @Test void packUsesThe263ShaderAbiAndKeepsOrdinaryItemAndOitPaths() throws Exception {
        var metadata = JsonParser.parseString(Files.readString(PACK.resolve("pack.mcmeta"))).getAsJsonObject().getAsJsonObject("pack");
        assertEquals("[97,1]", metadata.getAsJsonArray("min_format").toString());
        String vertex = Files.readString(PACK.resolve("assets/minecraft/shaders/core/item.vsh"));
        String fragment = Files.readString(PACK.resolve("assets/minecraft/shaders/core/item.fsh"));
        assertTrue(vertex.contains("layout(location = 0) in vec3 Position"));
        assertTrue(vertex.contains("strobeFrameVertex(Position, Color, UV0, UV2, Sampler0)"));
        assertTrue(fragment.contains("executeAlphaOnlyPhase(gl_FragCoord.z, 1.0)"));
        assertTrue(fragment.contains("sampleColorForAccumulation(packetColor)"));
        assertTrue(fragment.contains("fragColor = calculateFinalColor(color)"));
        try (var paths = Files.walk(PACK.resolve("assets"))) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".glsl") || p.toString().endsWith(".vsh") || p.toString().endsWith(".fsh")).toList()) {
                assertFalse(Files.readString(path).contains("#moj_import"), path.toString());
            }
        }
    }

    @Test void nexoAndTrpOverlaysRetainTheRequiredEndOfFrameCapture() throws Exception {
        var files = new java.util.LinkedHashMap<String, byte[]>();
        for (String name : new String[] {"assets/minecraft/shaders/core/item.vsh", "assets/minecraft/shaders/core/item.fsh", "assets/minecraft/post_effect/end_of_frame.json"}) {
            files.put(name, Files.readAllBytes(PACK.resolve(name)));
            assertTrue(ResourcePackService.isNexoRenderPipelineEntry(name));
        }
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        byte[] overlay = ResourcePackService.prepareTrpOverlay(bytes.toByteArray());
        assertTrue(ResourcePackService.containsExactNexoRenderPipeline(overlay, bytes.toByteArray()));
    }

    @Test void opaqueCarrierKeepsItsSignatureWhenAtlasSpritesAreResized() throws Exception {
        var source = javax.imageio.ImageIO.read(PACK.resolve(
            "assets/strobelights/textures/item/internal/frame_carrier.png").toFile());
        assertEquals(0xFF25D353, source.getRGB(0, 0));
        for (int size : new int[] {16, 32, 64, 128}) {
            var resized = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var graphics = resized.createGraphics();
            try { graphics.drawImage(source, 0, 0, size, size, null); }
            finally { graphics.dispose(); }
            assertEquals(0xFF25D353, resized.getRGB(size / 2, size / 2));
        }
    }

    @Test void carrierSurvivesModelBakingThenCollapsesToOneCpuSourcePosition() throws Exception {
        var model = JsonParser.parseString(Files.readString(PACK.resolve(
            "assets/strobelights/models/item/strobe_rgb_carrier.json"))).getAsJsonObject();
        var element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
        var from = element.getAsJsonArray("from");
        var to = element.getAsJsonArray("to");
        // UnbakedCuboidGeometry discards UP faces with zero X or Z extent.
        assertTrue(to.get(0).getAsFloat() > from.get(0).getAsFloat());
        assertTrue(to.get(2).getAsFloat() > from.get(2).getAsFloat());
        assertTrue(element.getAsJsonObject("faces").has("up"));
        var scale = model.getAsJsonObject("display").getAsJsonObject("head").getAsJsonArray("scale");
        var pose = new org.joml.Matrix4f().translate(3.25f, -6.5f, -9.0f)
            .scale(scale.get(0).getAsFloat(), scale.get(1).getAsFloat(), scale.get(2).getAsFloat())
            .translate(-0.5f, -0.5f, -0.5f);
        for (float x : new float[] {from.get(0).getAsFloat(), to.get(0).getAsFloat()}) {
            for (float z : new float[] {from.get(2).getAsFloat(), to.get(2).getAsFloat()}) {
                var position = pose.transformPosition(x / 16, to.get(1).getAsFloat() / 16, z / 16, new org.joml.Vector3f());
                assertEquals(new org.joml.Vector3f(3.25f, -6.5f, -9.0f), position);
            }
        }
    }
}
