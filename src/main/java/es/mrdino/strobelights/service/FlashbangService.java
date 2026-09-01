package es.mrdino.strobelights.service;

import es.mrdino.strobelights.StrobeLightsPlugin;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** Creates, launches and detonates the plugin's custom flashbang item. */
public final class FlashbangService implements Listener {

    public static final float CUSTOM_MODEL_DATA = 6_900.0f;

    private final StrobeLightsPlugin plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey projectileKey;
    private final Set<UUID> launching = new HashSet<>();
    private final Map<UUID, TrackedFlight> flights = new HashMap<>();
    private final Map<UUID, PendingDetonation> pendingDetonations = new HashMap<>();
    private final Map<ChunkTicketKey, Integer> chunkTicketReferences = new HashMap<>();
    private final BukkitTask flightTracker;

    public FlashbangService(StrobeLightsPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "flashbang_item");
        this.projectileKey = new NamespacedKey(plugin, "flashbang_projectile");
        this.flightTracker = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            this::tickFlights,
            1L,
            1L
        );
    }

    public ItemStack createItem(Player viewer, int amount) {
        ItemStack stack = new ItemStack(Material.SNOWBALL, Math.max(1, Math.min(16, amount)));
        ItemMeta meta = stack.getItemMeta();
        CustomModelDataComponent modelData = meta.getCustomModelDataComponent();
        modelData.setFloats(List.of(CUSTOM_MODEL_DATA));
        meta.setCustomModelDataComponent(modelData);
        meta.displayName(Component.text(
            plugin.messages().text(viewer, "item.flashbang.name"),
            NamedTextColor.GOLD
        ).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            lore(viewer, "item.flashbang.lore-1", NamedTextColor.GRAY),
            lore(
                viewer,
                "item.flashbang.lore-2",
                NamedTextColor.YELLOW,
                "seconds",
                fuseSeconds()
            ),
            lore(viewer, "item.flashbang.lore-3", NamedTextColor.WHITE),
            lore(viewer, "item.flashbang.lore-4", NamedTextColor.AQUA)
        ));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Gives one item and drops it safely at the target's feet if the inventory is full. */
    public boolean give(Player target) {
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(createItem(target, 1));
        overflow.values().forEach(item -> target.getWorld().dropItemNaturally(
            target.getLocation(), item
        ));
        return overflow.isEmpty();
    }

    public boolean isFlashbang(ItemStack stack) {
        return stack != null
            && stack.getType() == Material.SNOWBALL
            && stack.hasItemMeta()
            && stack.getItemMeta().getPersistentDataContainer().has(
                itemKey,
                PersistentDataType.BYTE
            );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isFlashbang(event.getItem()) || event.getHand() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!launching.add(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        ItemStack visual = event.getItem().clone();
        visual.setAmount(1);
        double velocity = Math.max(0.2, Math.min(
            3.0,
            plugin.getConfig().getDouble("throwable-flashbang.throw-velocity", 1.35)
        ));
        Snowball projectile = player.launchProjectile(
            Snowball.class,
            player.getEyeLocation().getDirection().normalize().multiply(velocity)
        );
        projectile.setItem(visual);
        projectile.getPersistentDataContainer().set(
            projectileKey,
            PersistentDataType.BYTE,
            (byte) 1
        );
        scheduleFlightTimeout(projectile);
        if (player.getGameMode() != GameMode.CREATIVE) {
            consumeOne(player, event.getHand(), event.getItem());
        }
        plugin.getServer().getScheduler().runTask(
            plugin,
            () -> launching.remove(player.getUniqueId())
        );
    }

    /**
     * Arms custom snowballs launched by dispensers or other plugins as well as
     * player throws. The item PDC is the authority; the shooter is irrelevant.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Snowball projectile)
            || !isFlashbang(projectile.getItem())) {
            return;
        }
        projectile.getPersistentDataContainer().set(
            projectileKey,
            PersistentDataType.BYTE,
            (byte) 1
        );
        scheduleFlightTimeout(projectile);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onImpact(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball projectile)
            || (!projectile.getPersistentDataContainer().has(
                projectileKey,
                PersistentDataType.BYTE
            ) && !isFlashbang(projectile.getItem()))) {
            return;
        }
        finishFlight(projectile.getUniqueId());
        var impact = projectile.getLocation().clone();
        if (event.getHitBlockFace() != null) {
            // Keep the detonation just outside the impacted face so the fixed
            // source remains on the visible side of the impacted surface.
            impact.add(event.getHitBlockFace().getDirection().multiply(0.15));
        }
        projectile.remove();
        long delay = Math.max(0L, Math.min(
            1_200L,
            plugin.getConfig().getLong("throwable-flashbang.detonation-delay-ticks", 20L)
        ));
        scheduleDetonation(impact, delay);
    }

    private void scheduleFlightTimeout(Snowball projectile) {
        UUID projectileId = projectile.getUniqueId();
        if (flights.containsKey(projectileId)) {
            return;
        }
        long configuredFlightTicks = plugin.getConfig().getLong(
            "throwable-flashbang.maximum-flight-ticks",
            1_200L
        );
        // Migrate the former five-second default. It could expire in mid-air
        // after unusual/high launches instead of allowing the grenade to land.
        if (configuredFlightTicks == 100L) {
            configuredFlightTicks = 1_200L;
        }
        long maximumFlightTicks = Math.max(1L, Math.min(
            1_200L,
            configuredFlightTicks
        ));
        TrackedFlight flight = new TrackedFlight(
            projectile,
            projectile.getLocation().clone(),
            maximumFlightTicks
        );
        flights.put(projectileId, flight);
        updateFlightTickets(flight);
    }

    private void tickFlights() {
        Iterator<Map.Entry<UUID, TrackedFlight>> iterator = flights.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TrackedFlight> entry = iterator.next();
            TrackedFlight flight = entry.getValue();
            Snowball projectile = flight.projectile;
            if (!projectile.isValid() || projectile.isDead()) {
                iterator.remove();
                releaseTickets(flight.tickets);
                scheduleDetonation(flight.lastLocation, 0L);
                continue;
            }

            flight.lastLocation = projectile.getLocation().clone();
            updateFlightTickets(flight);
            flight.remainingTicks--;
            if (flight.remainingTicks > 0L) {
                continue;
            }

            iterator.remove();
            releaseTickets(flight.tickets);
            projectile.remove();
            scheduleDetonation(flight.lastLocation, 0L);
        }
    }

    private void updateFlightTickets(TrackedFlight flight) {
        Set<ChunkTicketKey> desired = projectedChunkTickets(
            flight.lastLocation,
            flight.projectile.getVelocity()
        );
        for (ChunkTicketKey current : new HashSet<>(flight.tickets)) {
            if (!desired.contains(current)) {
                releaseTicket(current);
                flight.tickets.remove(current);
            }
        }
        for (ChunkTicketKey next : desired) {
            if (flight.tickets.add(next)) {
                retainTicket(next);
            }
        }
    }

    private Set<ChunkTicketKey> projectedChunkTickets(
        org.bukkit.Location location,
        Vector velocity
    ) {
        Set<ChunkTicketKey> result = new LinkedHashSet<>();
        World world = location.getWorld();
        if (world == null) {
            return result;
        }

        // Load the current chunk and the next two ticks of horizontal travel
        // before the entity crosses their borders. This prevents a projectile
        // from freezing merely because its shooter moved away.
        double dx = Math.max(-64.0, Math.min(64.0, velocity.getX() * 2.0));
        double dz = Math.max(-64.0, Math.min(64.0, velocity.getZ() * 2.0));
        int samples = Math.max(1, Math.min(
            16,
            (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) / 8.0)
        ));
        for (int index = 0; index <= samples; index++) {
            double progress = index / (double) samples;
            int blockX = (int) Math.floor(location.getX() + dx * progress);
            int blockZ = (int) Math.floor(location.getZ() + dz * progress);
            result.add(new ChunkTicketKey(
                world.getUID(),
                Math.floorDiv(blockX, 16),
                Math.floorDiv(blockZ, 16)
            ));
        }
        return result;
    }

    private void scheduleDetonation(org.bukkit.Location impact, long delay) {
        if (impact == null || impact.getWorld() == null) {
            return;
        }
        UUID detonationId = UUID.randomUUID();
        PendingDetonation pending = new PendingDetonation(impact.clone());
        ChunkTicketKey impactTicket = chunkTicketAt(impact);
        pending.tickets.add(impactTicket);
        retainTicket(impactTicket);
        pendingDetonations.put(detonationId, pending);

        if (delay <= 0L) {
            detonate(detonationId);
            return;
        }
        pending.detonationTask = plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> detonate(detonationId),
            delay
        );
    }

    private void detonate(UUID detonationId) {
        PendingDetonation pending = pendingDetonations.get(detonationId);
        if (pending == null || pending.detonated) {
            return;
        }
        pending.detonated = true;
        pending.detonationTask = null;
        try {
            plugin.manager().detonateFlashbang(pending.location);
        } catch (RuntimeException exception) {
            pendingDetonations.remove(detonationId);
            releaseTickets(pending.tickets);
            throw exception;
        }

        pending.releaseTask = plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> releasePendingDetonation(detonationId),
            sceneRetentionTicks()
        );
    }

    private long sceneRetentionTicks() {
        int configured = plugin.getConfig().getInt(
            "throwable-flashbang.scene-light-duration-ticks",
            60
        );
        if (configured == 30) {
            configured = 60;
        }
        return Math.max(1L, Math.min(200L, configured)) + 2L;
    }

    private void releasePendingDetonation(UUID detonationId) {
        PendingDetonation pending = pendingDetonations.remove(detonationId);
        if (pending != null) {
            releaseTickets(pending.tickets);
        }
    }

    private void finishFlight(UUID projectileId) {
        TrackedFlight flight = flights.remove(projectileId);
        if (flight != null) {
            releaseTickets(flight.tickets);
        }
    }

    private ChunkTicketKey chunkTicketAt(org.bukkit.Location location) {
        return new ChunkTicketKey(
            location.getWorld().getUID(),
            Math.floorDiv(location.getBlockX(), 16),
            Math.floorDiv(location.getBlockZ(), 16)
        );
    }

    private void retainTicket(ChunkTicketKey key) {
        int references = chunkTicketReferences.getOrDefault(key, 0);
        if (references == 0) {
            World world = plugin.getServer().getWorld(key.worldId);
            if (world == null) {
                return;
            }
            world.getChunkAt(key.x, key.z).addPluginChunkTicket(plugin);
        }
        chunkTicketReferences.put(key, references + 1);
    }

    private void releaseTicket(ChunkTicketKey key) {
        Integer references = chunkTicketReferences.get(key);
        if (references == null) {
            return;
        }
        if (references > 1) {
            chunkTicketReferences.put(key, references - 1);
            return;
        }
        chunkTicketReferences.remove(key);
        World world = plugin.getServer().getWorld(key.worldId);
        if (world != null && world.isChunkLoaded(key.x, key.z)) {
            world.getChunkAt(key.x, key.z).removePluginChunkTicket(plugin);
        }
    }

    private void releaseTickets(Set<ChunkTicketKey> tickets) {
        for (ChunkTicketKey ticket : new HashSet<>(tickets)) {
            releaseTicket(ticket);
        }
        tickets.clear();
    }

    public void shutdown() {
        flightTracker.cancel();
        for (TrackedFlight flight : flights.values()) {
            releaseTickets(flight.tickets);
        }
        flights.clear();
        for (PendingDetonation pending : pendingDetonations.values()) {
            if (pending.detonationTask != null) {
                pending.detonationTask.cancel();
            }
            if (pending.releaseTask != null) {
                pending.releaseTask.cancel();
            }
            releaseTickets(pending.tickets);
        }
        pendingDetonations.clear();
        chunkTicketReferences.clear();
    }

    private Component lore(
        Player viewer,
        String key,
        NamedTextColor color,
        Object... replacements
    ) {
        return Component.text(plugin.messages().text(viewer, key, replacements), color)
            .decoration(TextDecoration.ITALIC, false);
    }

    private String fuseSeconds() {
        double seconds = Math.max(0L, Math.min(
            1_200L,
            plugin.getConfig().getLong("throwable-flashbang.detonation-delay-ticks", 20L)
        )) / 20.0;
        if (seconds == Math.rint(seconds)) {
            return Long.toString(Math.round(seconds));
        }
        return String.format(Locale.ROOT, "%.2f", seconds)
            .replaceAll("0+$", "")
            .replaceAll("\\.$", "");
    }

    private static void consumeOne(Player player, EquipmentSlot hand, ItemStack used) {
        ItemStack replacement = used.clone();
        replacement.setAmount(used.getAmount() - 1);
        if (replacement.getAmount() <= 0) {
            replacement = new ItemStack(Material.AIR);
        }
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(replacement);
        } else {
            player.getInventory().setItemInMainHand(replacement);
        }
    }

    private record ChunkTicketKey(UUID worldId, int x, int z) {
    }

    private static final class TrackedFlight {
        private final Snowball projectile;
        private final Set<ChunkTicketKey> tickets = new HashSet<>();
        private org.bukkit.Location lastLocation;
        private long remainingTicks;

        private TrackedFlight(
            Snowball projectile,
            org.bukkit.Location lastLocation,
            long remainingTicks
        ) {
            this.projectile = projectile;
            this.lastLocation = lastLocation;
            this.remainingTicks = remainingTicks;
        }
    }

    private static final class PendingDetonation {
        private final org.bukkit.Location location;
        private final Set<ChunkTicketKey> tickets = new HashSet<>();
        private BukkitTask detonationTask;
        private BukkitTask releaseTask;
        private boolean detonated;

        private PendingDetonation(org.bukkit.Location location) {
            this.location = location;
        }
    }
}
