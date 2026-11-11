package es.mrdino.strobelights.resourcepack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NexoMultipackInstallerTest {

    @Test
    void selectsOnlyDocumentedMultipackCapableNexoVersions() {
        assertFalse(NexoMultipackInstaller.supportsVersion(null));
        assertFalse(NexoMultipackInstaller.supportsVersion("1.26.9"));
        assertTrue(NexoMultipackInstaller.supportsVersion("1.27"));
        assertTrue(NexoMultipackInstaller.supportsVersion("1.28.1-SNAPSHOT"));
        assertTrue(NexoMultipackInstaller.supportsVersion("2.0"));
    }
}
