package es.mrdino.strobelights.resourcepack;

import java.util.HexFormat;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/** Before UUID packs, Paper identifies the one active pack by its SHA-1. */
final class PackTransport {
    private PackTransport() { }

    static UUID id(PlayerResourcePackStatusEvent event, UUID expected) {
        String hash = event.getHash();
        if (hash == null || hash.isBlank()) return expected;
        try {
            return UUID.nameUUIDFromBytes(HexFormat.of().parseHex(hash));
        } catch (IllegalArgumentException invalidHash) {
            return null;
        }
    }

    static void send(Player player, UUID id, String url, byte[] sha1, Component prompt, boolean required) {
        player.setResourcePack(url, sha1, prompt, required);
    }

    static UUID nexoId(Object info) {
        return null; // 1.20.1 has no resource-pack UUID API; events carry the SHA-1 instead.
    }
}
