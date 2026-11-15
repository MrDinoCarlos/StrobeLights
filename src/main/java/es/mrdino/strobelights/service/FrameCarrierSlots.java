package es.mrdino.strobelights.service;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.ItemDisplay;

/** Unique, reusable packet lanes per viewer for the 26.3 end-of-frame renderer. */
final class FrameCarrierSlots {
    static final int CAPACITY = 64;
    private final Map<UUID, Map<ItemDisplay, Integer>> viewers = new HashMap<>();

    int assign(UUID viewer, ItemDisplay display) {
        Map<ItemDisplay, Integer> slots = viewers.computeIfAbsent(viewer, ignored -> new HashMap<>());
        slots.keySet().removeIf(entity -> !entity.isValid());
        Integer existing = slots.get(display);
        if (existing != null) return existing;
        BitSet used = new BitSet(CAPACITY);
        slots.values().forEach(used::set);
        int slot = used.nextClearBit(0);
        if (slot >= CAPACITY) return -1;
        slots.put(display, slot);
        return slot;
    }

    void forget(UUID viewer) { viewers.remove(viewer); }
    void clear() { viewers.clear(); }
}
