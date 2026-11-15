package es.mrdino.strobelights.resourcepack;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class PackLoadTrackerTest {
    @Test void unrelatedPackFailureCannotDisableLoadedRenderer() {
        var tracker = new PackLoadTracker(); var player = UUID.randomUUID(); var pack = UUID.randomUUID();
        assertTrue(tracker.update(player, pack, SUCCESSFULLY_LOADED, pack, false));
        for (var status : org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.values()) {
            if (!java.util.Set.of("FAILED_DOWNLOAD", "INVALID_URL", "FAILED_RELOAD", "DISCARDED").contains(status.name())) continue;
            assertFalse(tracker.update(player, UUID.randomUUID(), status, pack, false));
            assertTrue(tracker.isLoaded(player));
        }
        tracker.update(player, pack, FAILED_DOWNLOAD, pack, false);
        assertFalse(tracker.isLoaded(player));
    }
    @Test void discardingPreviousGenerationDoesNotDiscardTheNewPack() {
        var tracker = new PackLoadTracker(); var player = UUID.randomUUID();
        var previous = UUID.randomUUID(); var current = UUID.randomUUID();
        tracker.update(player, previous, ACCEPTED, previous, false);
        tracker.update(player, current, SUCCESSFULLY_LOADED, current, false);
        // The previous generation can finish after the provider has published a new UUID.
        assertTrue(tracker.update(player, previous, SUCCESSFULLY_LOADED, current, false));
        tracker.update(player, previous, FAILED_DOWNLOAD, current, false);
        assertTrue(tracker.isLoaded(player));
        tracker.update(player, current, DECLINED, current, false);
        assertFalse(tracker.isLoaded(player));
    }
    @Test void unrelatedSuccessAndUnavailableProviderDoNotEnableRgb() {
        var tracker = new PackLoadTracker(); var player = UUID.randomUUID();
        assertFalse(tracker.update(player, UUID.randomUUID(), SUCCESSFULLY_LOADED, null, false));
        assertFalse(tracker.update(player, UUID.randomUUID(), SUCCESSFULLY_LOADED, UUID.randomUUID(), false));
        assertFalse(tracker.isLoaded(player));
    }
    @Test void embeddedSharedHostsAlsoKeepSeparatePackStateAndClearOnQuit() {
        var tracker = new PackLoadTracker(); var player = UUID.randomUUID(); var pack = UUID.randomUUID();
        tracker.update(player, pack, SUCCESSFULLY_LOADED, null, true);
        tracker.update(player, UUID.randomUUID(), FAILED_DOWNLOAD, null, true);
        assertTrue(tracker.isLoaded(player));
        assertFalse(tracker.isLoaded(UUID.randomUUID()));
        tracker.forget(player);
        assertFalse(tracker.isLoaded(player));
        assertEquals("NO_STATUS", tracker.status(player));
    }
}
