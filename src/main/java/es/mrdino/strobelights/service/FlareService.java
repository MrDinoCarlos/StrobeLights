package es.mrdino.strobelights.service;

import es.mrdino.strobelights.StrobeLightsPlugin;
import es.mrdino.strobelights.util.StrobeColors;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
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
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.bukkit.util.RayTraceResult;

/** Owns the reusable flare launcher, color-cartridge menu and active flare flights. */
public final class FlareService implements Listener {

    private static final int LAUNCHER_MODEL_DATA = 6_910;
    private static final int CARTRIDGE_MODEL_DATA = 6_911;
    private static final int FLARE_CORE_MODEL_DATA = 6_912;
    private static final int FLARE_HOT_CORE_MODEL_DATA = 6_913;
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
        flights.values().forEach(flight -> {
            flight.visual.remove();
            plugin.manager().finishFlareLight(flight.lightId);
        });
        flights.clear();
        burns.values().forEach(burn -> {
            burn.visual.remove();
            plugin.manager().finishFlareLight(burn.lightId);
        });
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
            plugin.getConfig().getInt("flare.load-duration-ticks", 34)
        ));
        menuBlockedUntilNanos.put(
            player.getUniqueId(),
            System.nanoTime() + (duration + 10L) * 50_000_000L
        );
        loading.put(player.getUniqueId(), new LoadingCartridge(hand, color, duration));
        player.setCooldown(Material.BLAZE_ROD, duration);
        swing(player, hand);
        playSpatialSound(
            player.getLocation(),
            "strobelights:flare_reload_open",
            Sound.ITEM_CROSSBOW_LOADING_START,
            1.0f,
            1.0f
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
                playSpatialSound(
                    player.getLocation(),
                    "strobelights:flare_reload_insert",
                    Sound.ITEM_CROSSBOW_LOADING_MIDDLE,
                    1.0f,
                    1.0f
                );
                swing(player, state.hand);
            }
            if (state.elapsed >= state.duration) {
                ItemStack launcher = itemInHand(player, state.hand);
                loadColor(launcher, state.color.rgb);
                refreshLauncher(launcher, player);
                setItemInHand(player, state.hand, launcher);
                playSpatialSound(
                    player.getLocation(),
                    "strobelights:flare_reload_close",
                    Sound.ITEM_CROSSBOW_LOADING_END,
                    1.0f,
                    1.0f
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
            plugin.getConfig().getInt("flare.fire-cooldown-ticks", 12)
        ));
        if (cooldown > 0) {
            player.setCooldown(Material.BLAZE_ROD, cooldown);
        }
    }

    private void launch(Player player, int rgb, FlareColor color) {
        double speed = Math.max(0.1, Math.min(
            3.0,
            plugin.getConfig().getDouble("flare.launch-speed", 1.7)
        ));
        double verticalBias = Math.max(0.0, Math.min(
            3.0,
            plugin.getConfig().getDouble("flare.vertical-bias", 0.65)
        ));
        double minimumUpward = Math.max(0.05, Math.min(
            0.95,
            plugin.getConfig().getDouble("flare.minimum-upward-direction", 0.25)
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
            plugin.getConfig().getDouble("flare.launch-height", 28.0)
        ));
        double targetY = Math.min(world.getMaxHeight() - 1.0, origin.getY() + configuredHeight);
        int maximumTicks = Math.max(10, Math.min(
            1_200,
            plugin.getConfig().getInt("flare.maximum-flight-ticks", 200)
        ));
        FlareVisual visual = spawnFlareVisual(
            origin,
            rgb,
            configuredVisualSize("flare.visual.flight-size", 0.8)
        );
        UUID lightId = plugin.manager().beginFlareFlightLight(origin, rgb);
        flights.put(
            UUID.randomUUID(),
            new FlareFlight(
                origin.clone(),
                direction.multiply(speed),
                color,
                targetY,
                maximumTicks,
                visual,
                lightId
            )
        );
        float volume = (float) Math.max(0.0, Math.min(
            16.0,
            plugin.getConfig().getDouble("flare.launch-sound-volume", 3.0)
        ));
        float pitch = (float) Math.max(0.5, Math.min(
            2.0,
            plugin.getConfig().getDouble("flare.launch-sound-pitch", 1.0)
        ));
        playSpatialSound(
            origin,
            "strobelights:flare_fire",
            Sound.ITEM_FIRECHARGE_USE,
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
                flight.visual.remove();
                plugin.manager().finishFlareLight(flight.lightId);
                iterator.remove();
                continue;
            }
            Location previous = flight.location.clone();
            Vector step = flight.velocity.clone();
            flight.location.add(step);
            RayTraceResult collision = null;
            if (plugin.getConfig().getBoolean("flare.explode-on-collision", true)
                && step.lengthSquared() > 1.0e-8) {
                collision = StrobeManager.rayTraceBlocksIgnoringTechnicalBlocks(
                    world,
                    previous,
                    step.clone().normalize(),
                    step.length(),
                    FluidCollisionMode.NEVER,
                    true
                );
                if (collision != null && collision.getHitPosition() != null) {
                    Vector impact = collision.getHitPosition().subtract(
                        step.clone().normalize().multiply(0.04)
                    );
                    flight.location.set(
                        impact.getX(),
                        impact.getY(),
                        impact.getZ()
                    );
                }
            }
            flight.visual.moveTo(flight.location, configuredVisualSize(
                "flare.visual.flight-size",
                0.8
            ));
            plugin.manager().moveFlareLight(flight.lightId, flight.location);
            flight.elapsed++;
            if (flight.elapsed % 30 == 1) {
                playSpatialSound(
                    flight.location,
                    "strobelights:flare_flight",
                    Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,
                    0.7f,
                    1.15f
                );
            }
            double drag = Math.max(0.8, Math.min(
                1.0,
                plugin.getConfig().getDouble("flare.flight-drag", 0.99)
            ));
            double gravity = Math.max(0.0, Math.min(
                0.1,
                plugin.getConfig().getDouble("flare.flight-gravity", 0.012)
            ));
            flight.velocity.multiply(drag);
            flight.velocity.setY(flight.velocity.getY() - gravity);
            boolean reachedApex = flight.elapsed > 5 && flight.velocity.getY() <= 0.0;
            boolean hitBlock = collision != null;
            if (flight.location.getY() < flight.targetY
                && !reachedApex
                && !hitBlock
                && flight.elapsed < flight.maximumTicks) {
                continue;
            }
            iterator.remove();
            igniteFlare(
                flight.location,
                flight.color,
                flight.velocity,
                flight.visual,
                flight.lightId
            );
        }
    }

    private void igniteFlare(
        Location location,
        FlareColor color,
        Vector incomingVelocity,
        FlareVisual visual,
        UUID flightLightId
    ) {
        if (location.getWorld() == null) {
            visual.remove();
            plugin.manager().finishFlareLight(flightLightId);
            return;
        }
        int burnDuration = Math.max(1, Math.min(
            1_200,
            plugin.getConfig().getInt("flare.explosion.burn-duration-ticks", 800)
        ));
        double velocityRetention = Math.max(0.0, Math.min(
            1.0,
            plugin.getConfig().getDouble(
                "flare.explosion.ignition-velocity-retention",
                0.45
            )
        ));
        UUID burnId = UUID.randomUUID();
        double swayPhase = (burnId.getLeastSignificantBits() & 0xFFFF)
            * Math.PI / 32_768.0;
        Vector burnVelocity = incomingVelocity.clone().multiply(velocityRetention);
        double minimumHorizontalSpeed = Math.max(0.0, Math.min(
            0.25,
            plugin.getConfig().getDouble(
                "flare.explosion.minimum-horizontal-speed",
                0.035
            )
        ));
        double horizontalSpeed = Math.hypot(burnVelocity.getX(), burnVelocity.getZ());
        if (horizontalSpeed < minimumHorizontalSpeed) {
            burnVelocity.setX(Math.cos(swayPhase) * minimumHorizontalSpeed);
            burnVelocity.setZ(Math.sin(swayPhase) * minimumHorizontalSpeed);
        }
        plugin.manager().finishFlareLight(flightLightId);
        UUID lightId = plugin.manager().detonateFlare(location, color.rgb);
        burns.put(
            burnId,
            new FlareBurn(
                location.clone(),
                color,
                burnDuration,
                burnVelocity,
                swayPhase,
                lightId,
                visual
            )
        );
    }

    private void tickBurns() {
        var iterator = burns.entrySet().iterator();
        while (iterator.hasNext()) {
            FlareBurn burn = iterator.next().getValue();
            World world = burn.location.getWorld();
            if (world == null || burn.elapsed >= burn.duration) {
                burn.visual.remove();
                plugin.manager().finishFlareLight(burn.lightId);
                iterator.remove();
                continue;
            }
            tickBurnPosition(burn);
            plugin.manager().moveFlareLight(burn.lightId, burn.location);
            plugin.manager().refreshFlareCameraGlare(burn.location, burn.color.rgb);
            double remainingScale = Math.min(
                1.0,
                Math.max(0.0, (burn.duration - burn.elapsed) / 40.0)
            );
            double ignitionBloom = burn.elapsed < 10
                ? 1.0 + (10 - burn.elapsed) * 0.05
                : 1.0;
            double flicker = 0.965
                + Math.sin(burn.swayPhase + burn.elapsed * 0.73) * 0.025
                + Math.sin(burn.elapsed * 1.91) * 0.01;
            burn.visual.moveTo(
                burn.location,
                configuredVisualSize("flare.visual.burn-size", 3.2)
                    * ignitionBloom
                    * flicker
                    * remainingScale
            );
            if (burn.elapsed % 36 == 0) {
                playSpatialSound(
                    burn.location,
                    "strobelights:flare_burn",
                    Sound.BLOCK_FIRE_AMBIENT,
                    1.1f,
                    0.9f
                );
            }
            burn.elapsed++;
        }
    }

    private void tickBurnPosition(FlareBurn burn) {
        if (burn.grounded) {
            return;
        }
        double horizontalDrag = Math.max(0.8, Math.min(
            1.0,
            plugin.getConfig().getDouble("flare.explosion.horizontal-drag", 0.992)
        ));
        double gravity = Math.max(0.0, Math.min(
            0.1,
            plugin.getConfig().getDouble("flare.explosion.gravity", 0.0035)
        ));
        double terminalFallSpeed = Math.max(0.01, Math.min(
            0.3,
            plugin.getConfig().getDouble("flare.explosion.terminal-fall-speed", 0.06)
        ));
        double windAcceleration = Math.max(0.0, Math.min(
            0.01,
            plugin.getConfig().getDouble("flare.explosion.wind-acceleration", 0.00018)
        ));
        double swayStrength = Math.max(0.0, Math.min(
            0.05,
            plugin.getConfig().getDouble("flare.explosion.sway-strength", 0.0018)
        ));
        double swayFrequency = Math.max(0.001, Math.min(
            1.0,
            plugin.getConfig().getDouble("flare.explosion.sway-frequency", 0.08)
        ));
        double windAngle = burn.swayPhase + burn.elapsed * swayFrequency * 0.18;
        Vector wind = new Vector(
            Math.cos(windAngle) * windAcceleration,
            0.0,
            Math.sin(windAngle) * windAcceleration
        );
        burn.velocity = nextBurnVelocity(
            burn.velocity,
            horizontalDrag,
            gravity,
            terminalFallSpeed,
            wind
        );
        Vector movement = burn.velocity.clone();
        double horizontalSpeed = Math.hypot(movement.getX(), movement.getZ());
        if (horizontalSpeed > 1.0e-8 && swayStrength > 0.0) {
            double sway = Math.sin(burn.swayPhase + burn.elapsed * swayFrequency)
                * swayStrength;
            movement.add(new Vector(
                -movement.getZ() / horizontalSpeed * sway,
                0.0,
                movement.getX() / horizontalSpeed * sway
            ));
        }
        if (movement.lengthSquared() <= 1.0e-10) {
            return;
        }
        World world = burn.location.getWorld();
        RayTraceResult collision = StrobeManager.rayTraceBlocksIgnoringTechnicalBlocks(
            world,
            burn.location,
            movement.clone().normalize(),
            movement.length(),
            FluidCollisionMode.NEVER,
            true
        );
        if (collision != null && collision.getHitPosition() != null) {
            Vector impact = collision.getHitPosition().subtract(
                movement.clone().normalize().multiply(0.025)
            );
            burn.location.set(impact.getX(), impact.getY(), impact.getZ());
            burn.velocity.zero();
            burn.grounded = true;
            return;
        }
        burn.location.add(movement);
    }

    static Vector nextBurnVelocity(
        Vector velocity,
        double horizontalDrag,
        double gravity,
        double terminalFallSpeed,
        Vector wind
    ) {
        return new Vector(
            velocity.getX() * horizontalDrag + wind.getX(),
            Math.max(-terminalFallSpeed, velocity.getY() - gravity),
            velocity.getZ() * horizontalDrag + wind.getZ()
        );
    }

    private FlareVisual spawnFlareVisual(Location location, int rgb, double size) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("A flare visual requires a world");
        }
        double viewRange = Math.max(16.0, Math.min(
            256.0,
            plugin.getConfig().getDouble("flare.visual.view-range", 192.0)
        ));
        ItemDisplay halo = spawnFlareDisplay(
            world,
            location,
            flareCoreItem(rgb),
            viewRange
        );
        ItemDisplay hotCore = spawnFlareDisplay(
            world,
            location,
            flareHotCoreItem(),
            viewRange
        );
        FlareVisual visual = new FlareVisual(halo, hotCore, viewRange);
        visual.moveTo(location, size);
        return visual;
    }

    private ItemDisplay spawnFlareDisplay(
        World world,
        Location location,
        ItemStack item,
        double viewRange
    ) {
        return world.spawn(location, ItemDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setVisibleByDefault(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setShadowRadius(0.0f);
            entity.setShadowStrength(0.0f);
            entity.setViewRange((float) (viewRange / 64.0));
            entity.setInterpolationDelay(0);
            entity.setInterpolationDuration(2);
            entity.setItemStack(item);
        });
    }

    private static ItemStack flareCoreItem(int rgb) {
        ItemStack core = new ItemStack(Material.LEATHER_HORSE_ARMOR);
        LeatherArmorMeta meta = (LeatherArmorMeta) core.getItemMeta();
        Color tint = Color.fromRGB(rgb & 0xFFFFFF);
        meta.setColor(tint);
        setCustomModelData(meta, FLARE_CORE_MODEL_DATA, tint);
        core.setItemMeta(meta);
        return core;
    }

    private static ItemStack flareHotCoreItem() {
        ItemStack core = new ItemStack(Material.LEATHER_HORSE_ARMOR);
        LeatherArmorMeta meta = (LeatherArmorMeta) core.getItemMeta();
        meta.setColor(Color.fromRGB(0xFFFFFF));
        setCustomModelData(meta, FLARE_HOT_CORE_MODEL_DATA, null);
        core.setItemMeta(meta);
        return core;
    }

    private double configuredVisualSize(String path, double fallback) {
        return Math.max(0.1, Math.min(
            12.0,
            plugin.getConfig().getDouble(path, fallback)
        ));
    }

    private void updateFlareViewers(FlareVisual visual, Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Set<UUID> eligible = new HashSet<>();
        double rangeSquared = visual.viewRange * visual.viewRange;
        for (Player player : world.getPlayers()) {
            if (player.getEyeLocation().distanceSquared(location) > rangeSquared
                || plugin.resourcePack() != null && !plugin.resourcePack().isLoaded(player)) {
                continue;
            }
            eligible.add(player.getUniqueId());
            if (visual.viewers.add(player.getUniqueId())) {
                player.showEntity(plugin, visual.halo);
                player.showEntity(plugin, visual.hotCore);
        }
        }
        var viewerIterator = visual.viewers.iterator();
        while (viewerIterator.hasNext()) {
            UUID playerId = viewerIterator.next();
            if (eligible.contains(playerId)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.hideEntity(plugin, visual.halo);
                player.hideEntity(plugin, visual.hotCore);
            }
            viewerIterator.remove();
        }
    }

    private void playSpatialSound(
        Location source,
        String customSound,
        Sound vanillaFallback,
        float volume,
        float pitch
    ) {
        World world = source.getWorld();
        if (world == null || volume <= 0.0f) {
            return;
        }
        for (Player player : world.getPlayers()) {
            if (plugin.resourcePack() == null || plugin.resourcePack().isLoaded(player)) {
                player.playSound(
                    source,
                    customSound,
                    SoundCategory.PLAYERS,
                    volume,
                    pitch
                );
            } else {
                player.playSound(
                    source,
                    vanillaFallback,
                    SoundCategory.PLAYERS,
                    volume,
                    pitch
                );
        }
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
        private final FlareVisual visual;
        private final UUID lightId;
        private int elapsed;

        private FlareFlight(
            Location location,
            Vector velocity,
            FlareColor color,
            double targetY,
            int maximumTicks,
            FlareVisual visual,
            UUID lightId
        ) {
            this.location = location;
            this.velocity = velocity;
            this.color = color;
            this.targetY = targetY;
            this.maximumTicks = maximumTicks;
            this.visual = visual;
            this.lightId = lightId;
        }
    }

    private static final class FlareBurn {
        private Location location;
        private final FlareColor color;
        private final int duration;
        private Vector velocity;
        private final double swayPhase;
        private final UUID lightId;
        private final FlareVisual visual;
        private boolean grounded;
        private int elapsed;

        private FlareBurn(
            Location location,
            FlareColor color,
            int duration,
            Vector velocity,
            double swayPhase,
            UUID lightId,
            FlareVisual visual
        ) {
            this.location = location;
            this.color = color;
            this.duration = duration;
            this.velocity = velocity;
            this.swayPhase = swayPhase;
            this.lightId = lightId;
            this.visual = visual;
        }
    }

    private final class FlareVisual {
        private final ItemDisplay halo;
        private final ItemDisplay hotCore;
        private final double viewRange;
        private final Set<UUID> viewers = new HashSet<>();

        private FlareVisual(ItemDisplay halo, ItemDisplay hotCore, double viewRange) {
            this.halo = halo;
            this.hotCore = hotCore;
            this.viewRange = viewRange;
        }

        private void moveTo(Location location, double size) {
            moveDisplay(halo, location, size);
            moveDisplay(hotCore, location, Math.max(0.14, size * 0.18));
            updateFlareViewers(this, location);
        }

        private void moveDisplay(ItemDisplay display, Location location, double size) {
            if (!display.isValid() || display.isDead()) {
                return;
            }
            display.teleport(location);
            float scale = (float) Math.max(0.01, size);
            display.setDisplayWidth(scale * 1.25f);
            display.setDisplayHeight(scale * 1.25f);
            display.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
            ));
        }

        private void remove() {
            viewers.clear();
            if (halo.isValid() && !halo.isDead()) {
                halo.remove();
            }
            if (hotCore.isValid() && !hotCore.isDead()) {
                hotCore.remove();
            }
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
