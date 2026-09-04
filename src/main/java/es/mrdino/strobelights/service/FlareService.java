package es.mrdino.strobelights.service;

import es.mrdino.strobelights.StrobeLightsPlugin;
import es.mrdino.strobelights.util.StrobeColors;
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
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FireworkExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** Owns the reusable flare launcher, color-cartridge menu and active flare flights. */
public final class FlareService implements Listener {

    private static final int MENU_SIZE = 27;
    private static final int[] COLOR_SLOTS = {
        1, 2, 3, 4, 5, 6, 7, 8,
        10, 11, 12, 13, 14, 15, 16, 17
    };
    private static final FlareColor[] COLORS = {
        new FlareColor("white", Material.WHITE_DYE, 0xFFFFFF),
        new FlareColor("orange", Material.ORANGE_DYE, 0xFF7A00),
        new FlareColor("magenta", Material.MAGENTA_DYE, 0xFF00FF),
        new FlareColor("light-blue", Material.LIGHT_BLUE_DYE, 0x52D9FF),
        new FlareColor("yellow", Material.YELLOW_DYE, 0xFFFF00),
        new FlareColor("lime", Material.LIME_DYE, 0x7FFF00),
        new FlareColor("pink", Material.PINK_DYE, 0xFF69B4),
        new FlareColor("gray", Material.GRAY_DYE, 0x606068),
        new FlareColor("light-gray", Material.LIGHT_GRAY_DYE, 0xC8C8C8),
        new FlareColor("cyan", Material.CYAN_DYE, 0x00FFFF),
        new FlareColor("purple", Material.PURPLE_DYE, 0x9A35FF),
        new FlareColor("blue", Material.BLUE_DYE, 0x0066FF),
        new FlareColor("brown", Material.BROWN_DYE, 0xA65A2E),
        new FlareColor("green", Material.GREEN_DYE, 0x00FF3C),
        new FlareColor("red", Material.RED_DYE, 0xFF0000),
        new FlareColor("black", Material.BLACK_DYE, 0x282838)
    };

    private final StrobeLightsPlugin plugin;
    private final NamespacedKey launcherKey;
    private final NamespacedKey loadedColorKey;
    private final NamespacedKey flareEntityKey;
    private final Map<UUID, LoadingCartridge> loading = new HashMap<>();
    private final Map<UUID, FlareFlight> flights = new HashMap<>();
    private final BukkitTask ticker;

    public FlareService(StrobeLightsPlugin plugin) {
        this.plugin = plugin;
        this.launcherKey = new NamespacedKey(plugin, "flare_launcher");
        this.loadedColorKey = new NamespacedKey(plugin, "flare_loaded_color");
        this.flareEntityKey = new NamespacedKey(plugin, "flare_projectile");
        this.ticker = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            this::tick,
            1L,
            1L
        );
    }

    public ItemStack createLauncher(Player viewer) {
        ItemStack launcher = new ItemStack(Material.CROSSBOW);
        ItemMeta meta = launcher.getItemMeta();
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
            && stack.getType() == Material.CROSSBOW
            && stack.hasItemMeta()
            && stack.getItemMeta().getPersistentDataContainer().has(
                launcherKey,
                PersistentDataType.BYTE
            );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (!isLauncher(event.getItem()) || event.getHand() == null) {
            return;
        }
        event.setCancelled(true);
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlareExplode(FireworkExplodeEvent event) {
        FlareFlight flight = flights.remove(event.getEntity().getUniqueId());
        if (flight != null) {
            plugin.manager().detonateFlare(event.getEntity().getLocation(), flight.color.rgb);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlareDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework
            && firework.getPersistentDataContainer().has(
                flareEntityKey,
                PersistentDataType.INTEGER
            )
            && !plugin.getConfig().getBoolean("flare.damage-enabled", false)) {
            event.setCancelled(true);
        }
    }

    public void shutdown() {
        ticker.cancel();
        loading.clear();
        flights.values().forEach(flight -> {
            if (flight.firework.isValid()) {
                flight.firework.remove();
            }
        });
        flights.clear();
    }

    private void openMenu(Player player, EquipmentSlot hand) {
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
            ItemStack cartridge = menuItem(color.material);
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
        loading.put(player.getUniqueId(), new LoadingCartridge(hand, color, duration));
        player.setCooldown(Material.CROSSBOW, duration);
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
        clearLoadedColor(launcher);
        refreshLauncher(launcher, player);
        setItemInHand(player, hand, launcher);
        int cooldown = Math.max(0, Math.min(
            200,
            plugin.getConfig().getInt("flare.fire-cooldown-ticks", 10)
        ));
        if (cooldown > 0 && player.getGameMode() != GameMode.CREATIVE) {
            player.setCooldown(Material.CROSSBOW, cooldown);
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
        Firework firework = world.spawn(origin, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        FireworkEffect.Builder effect = FireworkEffect.builder()
            .withColor(Color.fromRGB(rgb))
            .with(explosionType())
            .trail(plugin.getConfig().getBoolean("flare.explosion.trail", true))
            .flicker(plugin.getConfig().getBoolean("flare.explosion.flicker", true));
        if (plugin.getConfig().getBoolean("flare.explosion.fade-to-white", true)) {
            effect.withFade(Color.WHITE);
        }
        meta.addEffect(effect.build());
        meta.setPower(127);
        firework.setFireworkMeta(meta);
        firework.setShotAtAngle(true);
        firework.setVelocity(direction.multiply(speed));
        firework.getPersistentDataContainer().set(
            flareEntityKey,
            PersistentDataType.INTEGER,
            rgb & 0xFFFFFF
        );
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
            firework.getUniqueId(),
            new FlareFlight(firework, color, targetY, maximumTicks)
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
            Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,
            SoundCategory.PLAYERS,
            volume,
            pitch
        );
    }

    private void tickFlights() {
        var iterator = flights.entrySet().iterator();
        while (iterator.hasNext()) {
            FlareFlight flight = iterator.next().getValue();
            if (!flight.firework.isValid() || flight.firework.isDead()) {
                iterator.remove();
                continue;
            }
            Location location = flight.firework.getLocation();
            emitTrail(location, flight.color.rgb);
            flight.elapsed++;
            if (location.getY() < flight.targetY && flight.elapsed < flight.maximumTicks) {
                continue;
            }
            iterator.remove();
            flight.firework.detonate();
            plugin.manager().detonateFlare(location, flight.color.rgb);
        }
    }

    private void emitTrail(Location location, int rgb) {
        int count = Math.max(0, Math.min(
            20,
            plugin.getConfig().getInt("flare.trail-particle-count", 3)
        ));
        if (count == 0 || location.getWorld() == null) {
            return;
        }
        float size = (float) Math.max(0.1, Math.min(
            4.0,
            plugin.getConfig().getDouble("flare.trail-particle-size", 1.25)
        ));
        location.getWorld().spawnParticle(
            Particle.DUST,
            location,
            count,
            0.08,
            0.08,
            0.08,
            0.0,
            new Particle.DustOptions(Color.fromRGB(rgb), size),
            true
        );
    }

    private FireworkEffect.Type explosionType() {
        String configured = plugin.getConfig().getString(
            "flare.explosion.type",
            "BALL_LARGE"
        );
        try {
            return FireworkEffect.Type.valueOf(configured.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return FireworkEffect.Type.BALL_LARGE;
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

    private record FlareColor(String key, Material material, int rgb) {
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
        private final Firework firework;
        private final FlareColor color;
        private final double targetY;
        private final int maximumTicks;
        private int elapsed;

        private FlareFlight(
            Firework firework,
            FlareColor color,
            double targetY,
            int maximumTicks
        ) {
            this.firework = firework;
            this.color = color;
            this.targetY = targetY;
            this.maximumTicks = maximumTicks;
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
