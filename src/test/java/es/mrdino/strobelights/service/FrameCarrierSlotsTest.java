package es.mrdino.strobelights.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.HashSet;
import java.util.UUID;
import org.bukkit.entity.ItemDisplay;
import org.junit.jupiter.api.Test;

class FrameCarrierSlotsTest {
    private static ItemDisplay carrier() {
        ItemDisplay display = mock(ItemDisplay.class);
        when(display.isValid()).thenReturn(true);
        return display;
    }

    @Test void assignsDistinctStableLanesPerViewerAndReusesRemovedCarriers() {
        var pool = new FrameCarrierSlots();
        var viewer = UUID.randomUUID();
        var first = carrier();
        assertEquals(0, pool.assign(viewer, first));
        assertEquals(0, pool.assign(viewer, first));
        assertEquals(1, pool.assign(viewer, carrier()));
        assertEquals(0, pool.assign(UUID.randomUUID(), carrier()));
        when(first.isValid()).thenReturn(false);
        assertEquals(0, pool.assign(viewer, carrier()));
        pool.forget(viewer);
        assertEquals(0, pool.assign(viewer, carrier()));
        pool.clear();
        assertEquals(0, pool.assign(viewer, carrier()));
    }

    @Test void capacityNeverOverwritesAnotherLightPacket() {
        var pool = new FrameCarrierSlots();
        var viewer = UUID.randomUUID();
        var allocated = new HashSet<Integer>();
        for (int i = 0; i < FrameCarrierSlots.CAPACITY; i++) assertTrue(allocated.add(pool.assign(viewer, carrier())));
        assertEquals(-1, pool.assign(viewer, carrier()));
        assertEquals(FrameCarrierSlots.CAPACITY, allocated.size());
    }
}
