package es.mrdino.strobelights.resourcepack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Logger;
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

            HttpResponse<byte[]> missing = client.send(
                HttpRequest.newBuilder(base.resolve("/wrong.zip")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertEquals(404, missing.statusCode());
        }
    }
}
