package es.mrdino.strobelights.service;

import es.mrdino.strobelights.StrobeLightsPlugin;
import es.mrdino.strobelights.util.StrobeColors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** Owns the reusable flare launcher, color-cartridge menu and active flare flights. */
public final class FlareService implements Listener {

    private static final int LAUNCHER_MODEL_DATA = 6_910;
    private static final int CARTRIDGE_MODEL_DATA = 6_911;
    private static final int MENU_SIZE = 27;
    private static final int[] COLOR_SLOTS = {
        1, 2, 3, 4, 5, 6, 7, 8,
        10, 11, 12, 13, 14, 15, 16, 17
    };
    private static final FlareColor[] COLORS = {
        new FlareColor("white", 0xFFFFFF),
        new FlareColor("orange", 0xFF7A00),
        new FlareColor("magenta", 0xFF00FF),
        new FlareColor("light-blue", 0x52D9FF),
        new FlareColor("yellow", 0xFFFF00),
        new FlareColor("lime", 0x7FFF00),
        new FlareColor("pink", 0xFF69B4),
        new FlareColor("gray", 0x606068),
        new FlareColor("light-gray", 0xC8C8C8),
        new FlareColor("cyan", 0x00FFFF),
        new FlareColor("purple", 0x9A35FF),
        new FlareColor("blue", 0x0066FF),
        new FlareColor("brown", 0xA65A2E),
        new FlareColor("green", 0x00FF3C),
        new FlareColor("red", 0xFF0000),
        new FlareColor("black", 0x282838)
    };

    private final StrobeLightsPlugin plugin;
    private final NamespacedKey launcherKey;
    private final NamespacedKey loadedColorKey;
    private final Map<UUID, LoadingCartridge> loading = new HashMap<>();
    private final Map<UUID, FlareFlight> flights = new HashMap<>();
    private final Map<UUID, FlareBurn> burns = new HashMap<>();
    private final Map<UUID, Long> menuBlockedUntilNanos = new HashMap<>();
    private final BukkitTask ticker;

    public FlareService(StrobeLightsPlugin plugin) {
        this.plugin = plugin;
        this.launcherKey = new NamespacedKey(plugin, "flare_launcher");
        this.loadedColorKey = new NamespacedKey(plugin, "flare_loaded_color");
        this.ticker = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            this::tick,
            1L,
            1L
        );
    }

    public ItemStack createLauncher(Player viewer) {
        ItemStack launcher = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = launcher.getItemMeta();
        setCustomModelData(meta, LAUNCHER_MODEL_DATA, null);
        meta.getPersistentDataContainer().set(launcherKey, PersistentDataType.BYTE, (byte) 1);
        launcher.setItemMeta(meta);
        refreshLauncher(launcher, viewer);
        return launcher;
    }

    /** Gives one launcher and drops it safely at the target's feet if the inventory is full. */
    public boolean give(Player target) {
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(createLauncher(target));
        overflow.values().forEach(item -> target.getWorld().dropItemNaturally(
            target.getLocation(),
            item
        ));
        return overflow.isEmpty();
    }

    public boolean isLauncher(ItemStack stack) {
        return stack != null
            && stack.getType() == Material.BLAZE_ROD
            && stack.hasItemMeta()
            && stack.getItemMeta().getPersistentDataContainer().has(
                launcherKey,
                PersistentDataType.BYTE
            );
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (!isTaggedLauncher(event.getItem()) || event.getHand() == null) {
            return;
        }
        event.setCancelled(true);
        migrateLegacyLauncher(event.getPlayer(), event.getHand(), event.getItem());
        switch (event.getAction()) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> openMenu(event.getPlayer(), event.getHand());
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> fire(event.getPlayer(), event.getHand());
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof FlareMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        for (int index = 0; index < COLOR_SLOTS.length; index++) {
            if (COLOR_SLOTS[index] == rawSlot) {
                player.closeInventory();
                beginLoading(player, menu.hand, COLORS[index]);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof FlareMenu)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    public void shutdown() {
        ticker.cancel();
        loading.clear();
        flights.clear();
        burns.values().forEach(burn -> plugin.manager().finishFlareLight(burn.lightId));
        burns.clear();
        menuBlockedUntilNanos.clear();
    }

    private void openMenu(Player player, EquipmentSlot hand) {
        Long blockedUntil = menuBlockedUntilNanos.get(player.getUniqueId());
        if (blockedUntil != null) {
            if (System.nanoTime() < blockedUntil) {
                return;
            }
            menuBlockedUntilNanos.remove(player.getUniqueId());
        }
        if (loading.containsKey(player.getUniqueId())) {
            actionBar(player, "item.flare-launcher.loading");
            return;
        }
        FlareMenu menu = new FlareMenu(hand);
        Inventory inventory = Bukkit.createInventory(
            menu,
            MENU_SIZE,
            Component.text(plugin.messages().text(player, "item.flare-cartridge.menu-title"))
        );
        menu.inventory = inventory;
        ItemStack filler = menuItem(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        filler.setItemMeta(fillerMeta);
        for (int slot = 0; slot < MENU_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        for (int index = 0; index < COLORS.length; index++) {
            FlareColor color = COLORS[index];
            ItemStack cartridge = cartridgeItem(color);
            ItemMeta meta = cartridge.getItemMeta();
            String colorName = plugin.messages().text(player, "color." + color.key);
            meta.displayName(Component.text(
                plugin.messages().text(
                    player,
                    "item.flare-cartridge.name",
                    "color",
                    colorName
                ),
                TextColor.color(color.rgb)
            ).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text(StrobeColors.hex(color.rgb), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text(
                    plugin.messages().text(player, "item.flare-cartridge.select"),
                    NamedTextColor.YELLOW
                ).decoration(TextDecoration.ITALIC, false)
            ));
            cartridge.setItemMeta(meta);
            inventory.setItem(COLOR_SLOTS[index], cartridge);
        }
        player.openInventory(inventory);
    }

    private void beginLoading(Player player, EquipmentSlot hand, FlareColor color) {
        ItemStack launcher = itemInHand(player, hand);
        if (!isLauncher(launcher)) {
            return;
        }
        int duration = Math.max(1, Math.min(
            200,
            plugin.getConfig().getInt("flare.load-duration-ticks", 24)
        ));
        menuBlockedUntilNanos.put(
            player.getUniqueId(),
            System.nanoTime() + (duration + 10L) * 50_000_000L
        );
        loading.put(player.getUniqueId(), new LoadingCartridge(hand, color, duration));
        player.setCooldown(Material.BLAZE_ROD, duration);
        swing(player, hand);
        player.playSound(
            player.getLocation(),
            Sound.ITEM_CROSSBOW_LOADING_START,
            SoundCategory.PLAYERS,
            0.8f,
            0.9f
        );
        sendLoadingProgress(player, color, 0, duration);
    }

    private void tick() {
        tickLoading();
        tickFlights();
        tickBurns();
    }

    private void tickLoading() {
        var iterator = loading.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, LoadingCartridge> entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            LoadingCartridge state = entry.getValue();
            if (player == null || !player.isOnline() || !isLauncher(itemInHand(player, state.hand))) {
                iterator.remove();
                continue;
            }
            state.elapsed++;
            if (state.elapsed == Math.max(1, state.duration / 2)) {
                player.playSound(
                    player.getLocation(),
                    Sound.ITEM_CROSSBOW_LOADING_MIDDLE,
                    SoundCategory.PLAYERS,
                    0.75f,
                    1.05f
                );
                swing(player, state.hand);
            }
            if (state.elapsed >= state.duration) {
                ItemStack launcher = itemInHand(player, state.hand);
                loadColor(launcher, state.color.rgb);
                refreshLauncher(launcher, player);
                setItemInHand(player, state.hand, launcher);
                player.playSound(
                    player.getLocation(),
                    Sound.ITEM_CROSSBOW_LOADING_END,
                    SoundCategory.PLAYERS,
                    0.9f,
                    1.15f
                );
                swing(player, state.hand);
                actionBar(
                    player,
                    "item.flare-launcher.loaded-message",
                    "color",
                    plugin.messages().text(player, "color." + state.color.key)
                );
                iterator.remove();
            } else if (state.elapsed % 4 == 0) {
                sendLoadingProgress(player, state.color, state.elapsed, state.duration);
            }
        }
    }

    private void fire(Player player, EquipmentSlot hand) {
        if (loading.containsKey(player.getUniqueId())) {
            actionBar(player, "item.flare-launcher.loading");
            return;
        }
        ItemStack launcher = itemInHand(player, hand);
        Integer rgb = loadedColor(launcher);
        if (rgb == null) {
            actionBar(player, "item.flare-launcher.empty-message");
            return;
        }
        FlareColor color = nearestColor(rgb);
        launch(player, rgb, color);
        if (plugin.getConfig().getBoolean("flare.reload-required", true)) {
            clearLoadedColor(launcher);
        }
        refreshLauncher(launcher, player);
        setItemInHand(player, hand, launcher);
        int cooldown = Math.max(0, Math.min(
            200,
            plugin.getConfig().getInt("flare.fire-cooldown-ticks", 10)
        ));
        if (cooldown > 0) {
            player.setCooldown(Material.BLAZE_ROD, cooldown);
        }
    }

    private void launch(Player player, int rgb, FlareColor color) {
        double speed = Math.max(0.1, Math.min(
            3.0,
            plugin.getConfig().getDouble("flare.launch-speed", 1.15)
        ));
        double verticalBias = Math.max(0.0, Math.min(
            3.0,
            plugin.getConfig().getDouble("flare.vertical-bias", 1.0)
        ));
        double minimumUpward = Math.max(0.05, Math.min(
            0.95,
            plugin.getConfig().getDouble("flare.minimum-upward-direction", 0.35)
        ));
        Vector direction = player.getEyeLocation().getDirection().normalize();
        direction.setY(direction.getY() + verticalBias).normalize();
        if (direction.getY() < minimumUpward) {
            double horizontalLength = Math.sqrt(
                direction.getX() * direction.getX() + direction.getZ() * direction.getZ()
            );
            double horizontalScale = Math.sqrt(1.0 - minimumUpward * minimumUpward)
                / Math.max(1.0e-8, horizontalLength);
            direction.setX(direction.getX() * horizontalScale);
            direction.setY(minimumUpward);
            direction.setZ(direction.getZ() * horizontalScale);
        }
        Location origin = player.getEyeLocation().add(direction.clone().multiply(0.65));
        World world = player.getWorld();
        double configuredHeight = Math.max(2.0, Math.min(
            256.0,
            plugin.getConfig().getDouble("flare.launch-height", 32.0)
        ));
        double targetY = Math.min(world.getMaxHeight() - 1.0, origin.getY() + configuredHeight);
        int maximumTicks = Math.max(10, Math.min(
            1_200,
            plugin.getConfig().getInt("flare.maximum-flight-ticks", 200)
        ));
        flights.put(
            UUID.randomUUID(),
            new FlareFlight(
                origin.clone(),
                direction.multiply(speed),
                color,
                targetY,
                maximumTicks
            )
        );
        float volume = (float) Math.max(0.0, Math.min(
            16.0,
            plugin.getConfig().getDouble("flare.launch-sound-volume", 1.5)
        ));
        float pitch = (float) Math.max(0.5, Math.min(
            2.0,
            plugin.getConfig().getDouble("flare.launch-sound-pitch", 0.9)
        ));
        world.playSound(
            origin,
            Sound.ITEM_FIRECHARGE_USE,
            SoundCategory.PLAYERS,
            volume,
            pitch
        );
    }

    private void tickFlights() {
        var iterator = flights.entrySet().iterator();
        while (iterator.hasNext()) {
            FlareFlight flight = iterator.next().getValue();
            World world = flight.location.getWorld();
            if (world == null) {
                iterator.remove();
                continue;
            }
            Location previous = flight.location.clone();
            flight.location.add(flight.velocity);
            emitTrail(previous, flight.location, flight.color.rgb);
            flight.elapsed++;
            double drag = Math.max(0.8, Math.min(
                1.0,
                plugin.getConfig().getDouble("flare.flight-drag", 0.995)
            ));
            double gravity = Math.max(0.0, Math.min(
                0.1,
                plugin.getConfig().getDouble("flare.flight-gravity", 0.006)
            ));
            flight.velocity.multiply(drag);
            flight.velocity.setY(flight.velocity.getY() - gravity);
            boolean reachedApex = flight.elapsed > 5 && flight.velocity.getY() <= 0.0;
            boolean hitBlock = plugin.getConfig().getBoolean("flare.explode-on-collision", true)
                && flight.location.getBlock().getType().isSolid();
            if (flight.location.getY() < flight.targetY
                && !reachedApex
                && !hitBlock
                && flight.elapsed < flight.maximumTicks) {
                continue;
            }
            iterator.remove();
            igniteFlare(flight.location, flight.color, flight.velocity);
        }
    }

    private void emitTrail(Location start, Location location, int rgb) {
        int count = Math.max(0, Math.min(
            20,
            plugin.getConfig().getInt("flare.trail-particle-count", 2)
        ));
        if (location.getWorld() == null) {
            return;
        }
        float size = (float) Math.max(0.1, Math.min(
            4.0,
            plugin.getConfig().getDouble("flare.trail-particle-size", 1.6)
        ));
        int pointsPerBlock = Math.max(1, Math.min(
            16,
            plugin.getConfig().getInt("flare.trail-points-per-block", 6)
        ));
        Vector segment = location.toVector().subtract(start.toVector());
        int points = Math.max(1, Math.min(40, (int) Math.ceil(
            segment.length() * pointsPerBlock
        )));
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(rgb), size);
        if (count > 0) {
            for (int index = 1; index <= points; index++) {
                Location point = start.clone().add(segment.clone().multiply(index / (double) points));
                location.getWorld().spawnParticle(
                    Particle.REDSTONE,
                    point,
                    count,
                    0.025,
                    0.025,
                    0.025,
                    0.0,
                    dust,
                    true
                );
            }
        }
        int hotCoreCount = Math.max(0, Math.min(
            4,
            plugin.getConfig().getInt("flare.trail-hot-core-count", 1)
        ));
        if (hotCoreCount > 0) {
            location.getWorld().spawnParticle(
                Particle.END_ROD,
                location,
                hotCoreCount,
                0.015,
                0.015,
                0.015,
                0.0,
                null,
                true
            );
        }
        int flameCount = Math.max(0, Math.min(
            8,
            plugin.getConfig().getInt("flare.trail-flame-count", 1)
        ));
        if (flameCount > 0) {
            location.getWorld().spawnParticle(
                Particle.FLAME,
                location,
                flameCount,
                0.04,
                0.04,
                0.04,
                0.005,
                null,
                true
            );
        }
        int smokeCount = Math.max(0, Math.min(
            8,
            plugin.getConfig().getInt("flare.trail-smoke-count", 1)
        ));
        if (smokeCount > 0) {
            location.getWorld().spawnParticle(
                Particle.CAMPFIRE_COSY_SMOKE,
                location,
                smokeCount,
                0.03,
                0.03,
                0.03,
                0.005,
                null,
                true
            );
        }
    }

    private void igniteFlare(Location location, FlareColor color, Vector incomingVelocity) {
        if (location.getWorld() == null) {
            return;
        }
        int burnDuration = Math.max(1, Math.min(
            1_200,
            plugin.getConfig().getInt("flare.explosion.burn-duration-ticks", 600)
        ));
        int burstDuration = Math.max(1, Math.min(
            100,
            plugin.getConfig().getInt("flare.explosion.burst-duration-ticks", 8)
        ));
        int sparkCount = Math.max(0, Math.min(
            200,
            plugin.getConfig().getInt("flare.explosion.burst-particle-count", 14)
        ));
        double sparkSpeed = Math.max(0.01, Math.min(
            2.0,
            plugin.getConfig().getDouble("flare.explosion.burst-speed", 0.12)
        ));
        double driftSpeed = Math.max(0.0, Math.min(
            0.1,
            plugin.getConfig().getDouble("flare.explosion.drift-speed", 0.006)
        ));
        Vector drift = incomingVelocity.clone().setY(0.0);
        if (drift.lengthSquared() > 1.0e-8) {
            drift.normalize().multiply(driftSpeed);
        } else {
            drift.setX(driftSpeed);
        }
        UUID burnId = UUID.randomUUID();
        UUID lightId = plugin.manager().detonateFlare(location, color.rgb);
        burns.put(
            burnId,
            new FlareBurn(
                location.clone(),
                color,
                burnDuration,
                burstDuration,
                createBurstSparks(location, sparkCount, sparkSpeed),
                drift,
                (burnId.getLeastSignificantBits() & 0xFFFF) * Math.PI / 32_768.0,
                lightId
            )
        );
    }

    private List<FlareSpark> createBurstSparks(
        Location center,
        int count,
        double speed
    ) {
        List<FlareSpark> sparks = new ArrayList<>(count);
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int index = 0; index < count; index++) {
            double y = 1.0 - 2.0 * (index + 0.5) / Math.max(1, count);
            double horizontal = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double angle = goldenAngle * index;
            double variation = 0.8 + 0.2 * ((index * 37) % 11) / 10.0;
            Vector velocity = new Vector(
                Math.cos(angle) * horizontal,
                y,
                Math.sin(angle) * horizontal
            ).multiply(speed * variation);
            sparks.add(new FlareSpark(center.clone(), velocity));
        }
        return sparks;
    }

    private void tickBurns() {
        var iterator = burns.entrySet().iterator();
        while (iterator.hasNext()) {
            FlareBurn burn = iterator.next().getValue();
            World world = burn.location.getWorld();
            if (world == null || burn.elapsed >= burn.duration) {
                plugin.manager().finishFlareLight(burn.lightId);
                iterator.remove();
                continue;
            }
            tickBurnPosition(burn);
            plugin.manager().moveFlareLight(burn.lightId, burn.location);
            emitBurnCore(burn);
            if (burn.elapsed < burn.burstDuration) {
                tickBurstSparks(burn);
            }
            burn.elapsed++;
        }
    }

    private void tickBurnPosition(FlareBurn burn) {
        double configuredFallSpeed = Math.max(0.0, Math.min(
            0.3,
            plugin.getConfig().getDouble("flare.explosion.fall-speed", 0.012)
        ));
        burn.fallSpeed = Math.min(configuredFallSpeed, burn.fallSpeed + 0.0005);
        double swayStrength = Math.max(0.0, Math.min(
            0.05,
            plugin.getConfig().getDouble("flare.explosion.sway-strength", 0.0025)
        ));
        double swayFrequency = Math.max(0.001, Math.min(
            1.0,
            plugin.getConfig().getDouble("flare.explosion.sway-frequency", 0.08)
        ));
        double sway = Math.sin(burn.swayPhase + burn.elapsed * swayFrequency) * swayStrength;
        double driftLength = Math.max(1.0e-8, burn.drift.length());
        Location next = burn.location.clone().add(
            burn.drift.getX() - burn.drift.getZ() / driftLength * sway,
            -burn.fallSpeed,
            burn.drift.getZ() + burn.drift.getX() / driftLength * sway
        );
        if (!next.getBlock().getType().isSolid()) {
            burn.location = next;
        }
    }

    private void emitBurnCore(FlareBurn burn) {
        World world = burn.location.getWorld();
        if (world == null) {
            return;
        }
        int count = Math.max(0, Math.min(
            40,
            plugin.getConfig().getInt("flare.explosion.burn-particle-count", 12)
        ));
        float size = (float) Math.max(0.1, Math.min(
            8.0,
            plugin.getConfig().getDouble("flare.explosion.burn-particle-size", 3.5)
        ));
        size *= (float) (0.94 + Math.sin(burn.elapsed * 0.55) * 0.06);
        if (count > 0) {
            world.spawnParticle(
                Particle.REDSTONE,
                burn.location,
                count,
                0.13,
                0.13,
                0.13,
                0.0,
                new Particle.DustOptions(Color.fromRGB(burn.color.rgb), size),
                true
            );
        }
        int hotCoreCount = Math.max(0, Math.min(
            8,
            plugin.getConfig().getInt("flare.explosion.hot-core-particle-count", 2)
        ));
        if (hotCoreCount > 0) {
            world.spawnParticle(
                Particle.END_ROD,
                burn.location,
                hotCoreCount,
                0.04,
                0.04,
                0.04,
                0.0,
                null,
                true
            );
        }
        int flameCount = Math.max(0, Math.min(
            20,
            plugin.getConfig().getInt("flare.explosion.flame-particle-count", 2)
        ));
        if (flameCount > 0) {
            world.spawnParticle(
                Particle.FLAME,
                burn.location,
                flameCount,
                0.1,
                0.1,
                0.1,
                0.01,
                null,
                true
            );
        }
        int smokeCount = Math.max(0, Math.min(
            20,
            plugin.getConfig().getInt("flare.explosion.smoke-particle-count", 1)
        ));
        if (smokeCount > 0 && burn.elapsed % 2 == 0) {
            world.spawnParticle(
                Particle.CAMPFIRE_COSY_SMOKE,
                burn.location,
                smokeCount,
                0.12,
                0.08,
                0.12,
                0.01,
                null,
                true
            );
        }
    }

    private void tickBurstSparks(FlareBurn burn) {
        World world = burn.location.getWorld();
        if (world == null) {
            return;
        }
        float size = (float) Math.max(0.1, Math.min(
            4.0,
            plugin.getConfig().getDouble("flare.explosion.burst-particle-size", 1.25)
        ));
        Particle.DustOptions dust = new Particle.DustOptions(
            Color.fromRGB(burn.color.rgb),
            size
        );
        double gravity = Math.max(0.0, Math.min(
            0.1,
            plugin.getConfig().getDouble("flare.explosion.spark-gravity", 0.012)
        ));
        double drag = Math.max(0.5, Math.min(
            1.0,
            plugin.getConfig().getDouble("flare.explosion.spark-drag", 0.94)
        ));
        for (FlareSpark spark : burn.sparks) {
            spark.location.add(spark.velocity);
            spark.velocity.multiply(drag);
            spark.velocity.setY(spark.velocity.getY() - gravity);
            world.spawnParticle(
                Particle.REDSTONE,
                spark.location,
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                dust,
                true
            );
        }
    }

    private void refreshLauncher(ItemStack launcher, Player viewer) {
        ItemMeta meta = launcher.getItemMeta();
        Integer rgb = meta.getPersistentDataContainer().get(
            loadedColorKey,
            PersistentDataType.INTEGER
        );
        meta.displayName(Component.text(
            plugin.messages().text(viewer, "item.flare-launcher.name"),
            NamedTextColor.GOLD
        ).decoration(TextDecoration.ITALIC, false));
        Component state;
        if (rgb == null) {
            state = Component.text(
                plugin.messages().text(viewer, "item.flare-launcher.empty"),
                NamedTextColor.GRAY
            );
        } else {
            FlareColor color = nearestColor(rgb);
            state = Component.text(
                plugin.messages().text(
                    viewer,
                    "item.flare-launcher.loaded",
                    "color",
                    plugin.messages().text(viewer, "color." + color.key)
                ),
                TextColor.color(rgb)
            );
        }
        meta.lore(List.of(
            state.decoration(TextDecoration.ITALIC, false),
            Component.text(
                plugin.messages().text(viewer, "item.flare-launcher.lore-left"),
                NamedTextColor.YELLOW
            ).decoration(TextDecoration.ITALIC, false),
            Component.text(
                plugin.messages().text(viewer, "item.flare-launcher.lore-right"),
                NamedTextColor.AQUA
            ).decoration(TextDecoration.ITALIC, false)
        ));
        launcher.setItemMeta(meta);
    }

    private void sendLoadingProgress(
        Player player,
        FlareColor color,
        int elapsed,
        int duration
    ) {
        int filled = Math.max(0, Math.min(10, (int) Math.ceil(elapsed * 10.0 / duration)));
        String bar = "■".repeat(filled) + "□".repeat(10 - filled);
        player.sendActionBar(Component.text(
            plugin.messages().text(
                player,
                "item.flare-launcher.loading-progress",
                "color",
                plugin.messages().text(player, "color." + color.key),
                "progress",
                bar
            ),
            TextColor.color(color.rgb)
        ));
    }

    private void actionBar(Player player, String key, Object... replacements) {
        player.sendActionBar(Component.text(plugin.messages().text(player, key, replacements)));
    }

    private Integer loadedColor(ItemStack launcher) {
        if (!isLauncher(launcher)) {
            return null;
        }
        return launcher.getItemMeta().getPersistentDataContainer().get(
            loadedColorKey,
            PersistentDataType.INTEGER
        );
    }

    private void loadColor(ItemStack launcher, int rgb) {
        ItemMeta meta = launcher.getItemMeta();
        meta.getPersistentDataContainer().set(
            loadedColorKey,
            PersistentDataType.INTEGER,
            rgb & 0xFFFFFF
        );
        launcher.setItemMeta(meta);
    }

    private void clearLoadedColor(ItemStack launcher) {
        ItemMeta meta = launcher.getItemMeta();
        meta.getPersistentDataContainer().remove(loadedColorKey);
        launcher.setItemMeta(meta);
    }

    private static ItemStack menuItem(Material material) {
        return new ItemStack(material);
    }

    private boolean isTaggedLauncher(ItemStack stack) {
        return stack != null
            && stack.hasItemMeta()
            && stack.getItemMeta().getPersistentDataContainer().has(
                launcherKey,
                PersistentDataType.BYTE
            );
    }

    private void migrateLegacyLauncher(
        Player player,
        EquipmentSlot hand,
        ItemStack launcher
    ) {
        if (launcher.getType() == Material.BLAZE_ROD) {
            return;
        }
        Integer loaded = launcher.getItemMeta().getPersistentDataContainer().get(
            loadedColorKey,
            PersistentDataType.INTEGER
        );
        ItemStack replacement = createLauncher(player);
        if (loaded != null) {
            loadColor(replacement, loaded);
            refreshLauncher(replacement, player);
        }
        setItemInHand(player, hand, replacement);
    }

    private static ItemStack cartridgeItem(FlareColor color) {
        ItemStack cartridge = new ItemStack(Material.LEATHER_HORSE_ARMOR);
        LeatherArmorMeta meta = (LeatherArmorMeta) cartridge.getItemMeta();
        Color tint = Color.fromRGB(color.rgb);
        meta.setColor(tint);
        setCustomModelData(meta, CARTRIDGE_MODEL_DATA, tint);
        cartridge.setItemMeta(meta);
        return cartridge;
    }

    private static void setCustomModelData(ItemMeta meta, int modelData, Color tint) {
        meta.setCustomModelData(modelData);
    }

    private static ItemStack itemInHand(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
            ? player.getInventory().getItemInOffHand()
            : player.getInventory().getItemInMainHand();
    }

    private static void setItemInHand(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    private static void swing(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }
    }

    private static FlareColor nearestColor(int rgb) {
        FlareColor best = COLORS[0];
        long bestDistance = Long.MAX_VALUE;
        for (FlareColor candidate : COLORS) {
            long red = (rgb >> 16 & 0xFF) - (candidate.rgb >> 16 & 0xFF);
            long green = (rgb >> 8 & 0xFF) - (candidate.rgb >> 8 & 0xFF);
            long blue = (rgb & 0xFF) - (candidate.rgb & 0xFF);
            long distance = red * red + green * green + blue * blue;
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private record FlareColor(String key, int rgb) {
    }

    private static final class LoadingCartridge {
        private final EquipmentSlot hand;
        private final FlareColor color;
        private final int duration;
        private int elapsed;

        private LoadingCartridge(EquipmentSlot hand, FlareColor color, int duration) {
            this.hand = hand;
            this.color = color;
            this.duration = duration;
        }
    }

    private static final class FlareFlight {
        private final Location location;
        private final Vector velocity;
        private final FlareColor color;
        private final double targetY;
        private final int maximumTicks;
        private int elapsed;

        private FlareFlight(
            Location location,
            Vector velocity,
            FlareColor color,
            double targetY,
            int maximumTicks
        ) {
            this.location = location;
            this.velocity = velocity;
            this.color = color;
            this.targetY = targetY;
            this.maximumTicks = maximumTicks;
        }
    }

    private static final class FlareBurn {
        private Location location;
        private final FlareColor color;
        private final int duration;
        private final int burstDuration;
        private final List<FlareSpark> sparks;
        private final Vector drift;
        private final double swayPhase;
        private final UUID lightId;
        private double fallSpeed;
        private int elapsed;

        private FlareBurn(
            Location location,
            FlareColor color,
            int duration,
            int burstDuration,
            List<FlareSpark> sparks,
            Vector drift,
            double swayPhase,
            UUID lightId
        ) {
            this.location = location;
            this.color = color;
            this.duration = duration;
            this.burstDuration = burstDuration;
            this.sparks = sparks;
            this.drift = drift;
            this.swayPhase = swayPhase;
            this.lightId = lightId;
        }
    }

    private static final class FlareSpark {
        private final Location location;
        private final Vector velocity;

        private FlareSpark(Location location, Vector velocity) {
            this.location = location;
            this.velocity = velocity;
        }
    }

    private static final class FlareMenu implements InventoryHolder {
        private final EquipmentSlot hand;
        private Inventory inventory;

        private FlareMenu(EquipmentSlot hand) {
            this.hand = hand;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
