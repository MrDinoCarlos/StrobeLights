package es.mrdino.strobelights.resourcepack;

import java.util.*;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;

/** Readiness belongs to a pack UUID, not to the last event from any plugin. */
final class PackLoadTracker {
    private final Map<UUID, Set<UUID>> pending = new HashMap<>();
    private final Map<UUID, Set<UUID>> loaded = new HashMap<>();
    private final Map<UUID, String> last = new HashMap<>();

    boolean update(UUID player, UUID pack, Status status, UUID expected, boolean legacyShared) {
        boolean known = pending.getOrDefault(player, Set.of()).contains(pack)
            || loaded.getOrDefault(player, Set.of()).contains(pack);
        if (pack == null) return false;
        if (!known && !pack.equals(expected) && !legacyShared) return false;
        last.put(player, status.name() + " (" + pack + ")");
        switch (status.name()) {
            case "ACCEPTED", "DOWNLOADED" -> pending.computeIfAbsent(player, ignored -> new HashSet<>()).add(pack);
            case "SUCCESSFULLY_LOADED" -> {
                remove(pending, player, pack);
                loaded.computeIfAbsent(player, ignored -> new HashSet<>()).add(pack);
            }
            case "DECLINED", "FAILED_DOWNLOAD", "INVALID_URL", "FAILED_RELOAD", "DISCARDED" -> {
                remove(pending, player, pack);
                remove(loaded, player, pack);
            }
            default -> { }
        }
        return true;
    }
    private static void remove(Map<UUID, Set<UUID>> map, UUID player, UUID pack) {
        var entries = map.get(player);
        if (entries != null) { entries.remove(pack); if (entries.isEmpty()) map.remove(player); }
    }
    boolean isLoaded(UUID player) { return !loaded.getOrDefault(player, Set.of()).isEmpty(); }
    String status(UUID player) { return last.getOrDefault(player, "NO_STATUS"); }
    void forget(UUID player) { pending.remove(player); loaded.remove(player); last.remove(player); }
    void clear() { pending.clear(); loaded.clear(); last.clear(); }
}
