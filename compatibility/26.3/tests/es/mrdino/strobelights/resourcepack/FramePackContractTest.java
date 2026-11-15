package es.mrdino.strobelights.resourcepack;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FramePackContractTest {
    private static final Path PACK = Path.of("resource-pack");

    @Test void alwaysOnChainUsesMainDepthAndDistinctReadWriteTargets() throws Exception {
        var chain = JsonParser.parseString(Files.readString(PACK.resolve("assets/minecraft/post_effect/end_of_frame.json"))).getAsJsonObject();
        assertFalse(Files.exists(PACK.resolve("assets/minecraft/post_effect/transparency.json")));
        assertEquals(10, chain.getAsJsonArray("passes").size());
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
}
