package es.mrdino.strobelights.service;

import es.mrdino.strobelights.StrobeLightsPlugin;
import java.util.HashMap;
import java.util.HashSet;
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
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** Creates, launches and detonates the plugin's custom flashbang item. */
public final class FlashbangService implements Listener {

    public static final float CUSTOM_MODEL_DATA = 6_900.0f;

    private final StrobeLightsPlugin plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey projectileKey;
    private final Set<UUID> launching = new HashSet<>();
    private final Map<UUID, BukkitTask> flightTimeouts = new HashMap<>();

    public FlashbangService(StrobeLightsPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "flashbang_item");
        this.projectileKey = new NamespacedKey(plugin, "flashbang_projectile");
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onImpact(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball projectile)
            || !projectile.getPersistentDataContainer().has(
                projectileKey,
                PersistentDataType.BYTE
            )) {
            return;
        }
        BukkitTask flightTimeout = flightTimeouts.remove(projectile.getUniqueId());
        if (flightTimeout != null) {
            flightTimeout.cancel();
        }
        var impact = projectile.getLocation().clone();
        if (event.getHitBlockFace() != null) {
            // Keep the detonation just outside the impacted face so the source
            // belongs to the correct side of the wall for RGB occlusion.
            impact.add(event.getHitBlockFace().getDirection().multiply(0.15));
        }
        projectile.remove();
        long delay = Math.max(0L, Math.min(
            1_200L,
            plugin.getConfig().getLong("throwable-flashbang.detonation-delay-ticks", 20L)
        ));
        if (delay == 0L) {
            plugin.manager().detonateFlashbang(impact);
        } else {
            plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> plugin.manager().detonateFlashbang(impact),
                delay
            );
        }
    }

    private void scheduleFlightTimeout(Snowball projectile) {
        long maximumFlightTicks = Math.max(1L, Math.min(
            1_200L,
            plugin.getConfig().getLong("throwable-flashbang.maximum-flight-ticks", 100L)
        ));
        UUID projectileId = projectile.getUniqueId();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> {
                flightTimeouts.remove(projectileId);
                var lastLocation = projectile.getLocation().clone();
                if (projectile.isValid()) {
                    projectile.remove();
                }
                plugin.manager().detonateFlashbang(lastLocation);
            },
            maximumFlightTicks
        );
        BukkitTask previous = flightTimeouts.put(projectileId, task);
        if (previous != null) {
            previous.cancel();
        }
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
}
