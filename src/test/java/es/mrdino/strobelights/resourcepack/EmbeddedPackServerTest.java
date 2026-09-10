package es.mrdino.strobelights.resourcepack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class EmbeddedPackServerTest {

    @Test
    void recognizesOnlyExplicitOptiFineClientBrands() {
        assertTrue(ResourcePackService.isOptiFineBrand("OptiFine"));
        assertTrue(ResourcePackService.isOptiFineBrand("fabric/OptiFabric"));
        assertFalse(ResourcePackService.isOptiFineBrand("vanilla"));
        assertFalse(ResourcePackService.isOptiFineBrand("fabric"));
        assertFalse(ResourcePackService.isOptiFineBrand(null));
    }

    @Test
    void detectsOnlyTheBundledPlaceholderHostAsUnconfigured() {
        assertTrue(ResourcePackService.isDefaultPublicUrl(
            "http://serverip.com:8250/strobelights/{token}.zip"
        ));
        assertTrue(ResourcePackService.isDefaultPublicUrl(
            "https://SERVERIP.COM/custom.zip"
        ));
        assertFalse(ResourcePackService.isDefaultPublicUrl(
            "http://108.181.58.76:8250/strobelights/{token}.zip"
        ));
        assertFalse(ResourcePackService.isDefaultPublicUrl(""));
    }

    @Test
    void expandsTheTokenBeforeValidatingAnExternalPackUrl() {
        assertEquals(
            "http://108.181.58.76:8250/strobelights/01ab.zip",
            ResourcePackService.expandPublicUrl(
                "http://108.181.58.76:8250/strobelights/{token}.zip",
                new byte[] {0x01, (byte) 0xAB}
            )
        );
    }

    @Test
    void keepsRgbRenderingEligibleAcrossStandaloneAndExternalPackDelivery() {
        assertFalse(ResourcePackService.canRender(true, false, false, false));
        assertFalse(ResourcePackService.canRender(true, false, true, false));
        assertTrue(ResourcePackService.canRender(true, false, false, true));
        assertFalse(ResourcePackService.canRender(true, true, false, false));
        assertTrue(ResourcePackService.canRender(false, false, false, false));
    }

    @Test
    void identifiesEveryCriticalFileThatMustSurviveTheNexoMerge() {
        assertTrue(ResourcePackService.isNexoRenderPipelineEntry(
            "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
        ));
        assertTrue(ResourcePackService.isNexoRenderPipelineEntry(
            "assets/minecraft/shaders/post/light.fsh"
        ));
        assertTrue(ResourcePackService.isNexoRenderPipelineEntry(
            "assets/minecraft/post_effect/transparency.json"
        ));
        assertTrue(ResourcePackService.isNexoRenderPipelineEntry(
            "assets/strobelights/strobelights-integration.json"
        ));
        assertFalse(ResourcePackService.isNexoRenderPipelineEntry(
            "assets/strobelights/models/item/flare_launcher.json"
        ));
        assertFalse(ResourcePackService.isNexoRenderPipelineEntry("pack.mcmeta"));
    }

    @Test
    void restoresNexoMetadataAfterImportingTheStrobeLightsZip() throws Exception {
        FakeResourcePack resourcePack = new FakeResourcePack("nexo-metadata");
        Object original = resourcePack.packMeta();
        resourcePack.packMeta("strobelights-metadata");

        ResourcePackService.restoreNexoPackMeta(resourcePack, original);

        assertEquals("nexo-metadata", resourcePack.packMeta());
    }

    @Test
    void repairsTheFinalNexoZipWithoutReplacingNexoMetadataOrAssets() throws Exception {
        byte[] source = zip(Map.of(
            "pack.mcmeta", utf8("strobelights-meta"),
            "assets/minecraft/post_effect/transparency.json", utf8("strobe-pipeline"),
            "assets/minecraft/shaders/post/light.fsh", utf8("strobe-light"),
            "assets/minecraft/shaders/core/item.vsh",
            utf8("trpMarkerFlag trpStrobeMarker hybrid-core"),
            "assets/strobelights/strobelights-integration.json", utf8("0.10.17"),
            "assets/strobelights/models/item/flare_launcher.json", utf8("flare-model")
        ));
        byte[] generated = zip(Map.of(
            "pack.mcmeta", utf8("nexo-meta"),
            "assets/minecraft/post_effect/transparency.json", utf8("other-pipeline"),
            "assets/minecraft/shaders/core/item.vsh", utf8("strobe-only-core"),
            "assets/nexo/textures/item/example.png", new byte[] {1, 2, 3}
        ));

        assertFalse(ResourcePackService.containsExactNexoRenderPipeline(generated, source));
        byte[] repaired = ResourcePackService.overlayNexoRenderPipeline(generated, source);
        assertTrue(ResourcePackService.containsExactNexoRenderPipeline(repaired, source));

        Map<String, byte[]> entries = unzip(repaired);
        assertArrayEquals(utf8("nexo-meta"), entries.get("pack.mcmeta"));
        assertArrayEquals(
            new byte[] {1, 2, 3},
            entries.get("assets/nexo/textures/item/example.png")
        );
        assertArrayEquals(
            utf8("trpMarkerFlag trpStrobeMarker hybrid-core"),
            entries.get("assets/minecraft/shaders/core/item.vsh")
        );
        assertFalse(entries.containsKey(
            "assets/strobelights/models/item/flare_launcher.json"
        ));
    }

    @Test
    void invalidatesNexoSelfHostBytesAfterPackRegeneration() throws Exception {
        FakeNexoSelfHost server = new FakeNexoSelfHost(new byte[] {9, 8, 7});

        ResourcePackService.clearNexoPackServerCache(server);

        assertNull(server.cachedBytes());
    }

    @Test
    void servesOnlyTheConfiguredImmutableZip() throws Exception {
        byte[] pack = {0x50, 0x4B, 0x03, 0x04};
        try (EmbeddedPackServer server = new EmbeddedPackServer(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            "/strobelights/test.zip",
            pack,
            Logger.getAnonymousLogger()
        )) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + server.port());

            HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder(base.resolve("/strobelights/test.zip")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertEquals(200, response.statusCode());
            assertArrayEquals(pack, response.body());
            assertEquals("application/zip", response.headers()
                .firstValue("Content-Type").orElseThrow());
            assertEquals("bytes", response.headers()
                .firstValue("Accept-Ranges").orElseThrow());

            HttpResponse<byte[]> range = client.send(
                HttpRequest.newBuilder(base.resolve("/strobelights/test.zip"))
                    .header("Range", "bytes=1-2")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertEquals(206, range.statusCode());
            assertArrayEquals(new byte[] {0x4B, 0x03}, range.body());
            assertEquals("bytes 1-2/4", range.headers()
                .firstValue("Content-Range").orElseThrow());

            HttpResponse<byte[]> missing = client.send(
                HttpRequest.newBuilder(base.resolve("/wrong.zip")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertEquals(404, missing.statusCode());
        }
    }

    static final class FakeResourcePack {

        private Object metadata;

        FakeResourcePack(Object metadata) {
            this.metadata = metadata;
        }

        public Object packMeta() {
            return metadata;
        }

        public void packMeta(Object metadata) {
            this.metadata = metadata;
        }
    }

    static final class FakeNexoSelfHost {

        @SuppressWarnings("unused")
        private byte[] builtPackArray;

        FakeNexoSelfHost(byte[] builtPackArray) {
            this.builtPackArray = builtPackArray;
        }

        byte[] cachedBytes() {
            return builtPackArray;
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
                input.closeEntry();
            }
        }
        return entries;
    }
}
