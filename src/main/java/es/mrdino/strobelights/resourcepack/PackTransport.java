package es.mrdino.strobelights.resourcepack;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/** API boundary replaced by the legacy implementation when building 1.20.X. */
final class PackTransport {
    private PackTransport() { }

    static UUID id(PlayerResourcePackStatusEvent event, UUID expected) {
        return event.getID();
    }

    static UUID nexoId(Object info) {
        return info instanceof net.kyori.adventure.resource.ResourcePackInfo pack ? pack.id() : null;
    }

    static void send(Player player, UUID id, String url, byte[] sha1, Component prompt, boolean required) {
        player.setResourcePack(id, url, sha1, prompt, required);
    }
}
