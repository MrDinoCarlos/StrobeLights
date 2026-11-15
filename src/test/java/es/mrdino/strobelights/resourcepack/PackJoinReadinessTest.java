package es.mrdino.strobelights.resourcepack;

import es.mrdino.strobelights.StrobeLightsPlugin;
import es.mrdino.strobelights.service.StrobeManager;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PackJoinReadinessTest {
    @Test void joinPreservesAlreadyConfirmedSharedPackUntilQuit() throws Exception {
        var plugin = mock(StrobeLightsPlugin.class);
        var manager = mock(StrobeManager.class);
        when(plugin.manager()).thenReturn(manager);
        var player = mock(Player.class); var id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        var service = new ResourcePackService(plugin);
        for (String name : new String[] {"active", "nexoManaged"}) {
            var field = ResourcePackService.class.getDeclaredField(name); field.setAccessible(true); field.set(service, true);
        }
        var field = ResourcePackService.class.getDeclaredField("packLoads"); field.setAccessible(true);
        var tracker = (PackLoadTracker)field.get(service); var pack = UUID.randomUUID();
        tracker.update(id, pack, Status.SUCCESSFULLY_LOADED, pack, false);
        assertTrue(service.isLoaded(player));
        service.onJoin(mockJoin(player));
        assertTrue(service.isLoaded(player), "Join must not erase a pack already accepted during login/reconfiguration");
        verify(manager).setMarkersVisible(player, true);
        var quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(player);
        service.onQuit(quit);
        assertFalse(service.isLoaded(player), "A later connection must confirm its own pack");
    }
    private static PlayerJoinEvent mockJoin(Player player) {
        var event = mock(PlayerJoinEvent.class); when(event.getPlayer()).thenReturn(player); return event;
    }
}
