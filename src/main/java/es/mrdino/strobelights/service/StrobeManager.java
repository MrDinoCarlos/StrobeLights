package es.mrdino.strobelights.service;

import es.mrdino.strobelights.StrobeLightsPlugin;
import es.mrdino.strobelights.model.BlindnessLevel;
import es.mrdino.strobelights.model.Strobe;
import es.mrdino.strobelights.model.StrobeMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

/**
 * Owns invisible Light Painter markers and their strobe timing.
 *
 * <p>The marker does not draw a fixture or projection. The 26.1.2 resource-pack
 * shader reconstructs its 3D position from the depth buffer and applies RGB
 * illumination to every visible world pixel around that point.</p>
 */
public final class StrobeManager {

    private static final float LIGHT_PAINTER_MODEL_DATA = 6_700.0f;
    private static final float EDITOR_HANDLE_MODEL_DATA = 6_813.0f;
    private static final double MARKER_SURFACE_OFFSET = 0.125;
    private static final int OFFSCREEN_LIGHT_SIGNATURE = 0b110;
    private static final int FLASH_SIGNATURE = 0xD;
    private static final int SOURCE_LIGHT_SIGNATURE = 0xA;
    private static final int SOURCE_LIGHT_TRAILER = 0x5;
    private static final int DEFAULT_PROJECTION_CODE = 11;
    private static final double CAMERA_FLASH_MARKER_DISTANCE = 0.75;
    private static final double CAMERA_FLASH_MARKER_SIDE = 0.22;

    private final StrobeLightsPlugin plugin;
    private final StrobeRepository repository;
    private final Map<String, Strobe> strobes;
    private final Map<String, RuntimeState> runtime = new LinkedHashMap<>();
    private final Map<UUID, CameraFlash> cameraFlashes = new LinkedHashMap<>();
    private final Map<UUID, SceneFlash> sceneFlashes = new LinkedHashMap<>();
    private final Set<UUID> manualDiscovery = new HashSet<>();
    private final Set<UUID> activeDiscoveryPlayers = new HashSet<>();
    private final NamespacedKey entityKey;
    private final NamespacedKey proxyEntityKey;
    private final NamespacedKey flashEntityKey;
    private final NamespacedKey sceneFlashEntityKey;
    private final NamespacedKey discoveryEntityKey;
    private final NamespacedKey editorEntityKey;
    private final NamespacedKey easyArmorStandsToolKey;
    private final NamespacedKey easyArmorStandsElementTypeKey;
    private final NamespacedKey legacyEntityKey;
    private BukkitTask task;
    private int pendingEditorSaveTicks;

    public StrobeManager(
        StrobeLightsPlugin plugin,
        StrobeRepository repository,
        Map<String, Strobe> strobes
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.strobes = new LinkedHashMap<>(strobes);
        this.entityKey = new NamespacedKey(plugin, "strobe_marker");
        this.proxyEntityKey = new NamespacedKey(plugin, "offscreen_light_proxy");
        this.flashEntityKey = new NamespacedKey(plugin, "camera_flash_marker");
        this.sceneFlashEntityKey = new NamespacedKey(plugin, "throwable_flash_light");
        this.discoveryEntityKey = new NamespacedKey(plugin, "discovery_light_proxy");
        this.editorEntityKey = new NamespacedKey(plugin, "editor_handle");
        this.easyArmorStandsToolKey = Objects.requireNonNull(
            NamespacedKey.fromString("easyarmorstands:tool")
        );
        this.easyArmorStandsElementTypeKey = Objects.requireNonNull(
            NamespacedKey.fromString("easyarmorstands:element_type")
        );
        this.legacyEntityKey = new NamespacedKey(plugin, "strobe_display");
    }

    public void start() {
        removeOrphanedDisplays();
        removeLegacyLightBlocks();
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                (Runnable) this::tick,
                1L,
                1L
            );
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        runtime.values().forEach(RuntimeState::remove);
        runtime.clear();
        cameraFlashes.values().forEach(CameraFlash::remove);
        cameraFlashes.clear();
        sceneFlashes.values().forEach(SceneFlash::remove);
        sceneFlashes.clear();
        manualDiscovery.clear();
        activeDiscoveryPlayers.clear();
        repository.save(strobes);
    }

    public int size() {
        return strobes.size();
    }

    public int maximumStrobes() {
        return Math.max(1, plugin.getConfig().getInt("limits.maximum-strobes", 256));
    }

    public int maximumNameLength() {
        return Math.max(1, plugin.getConfig().getInt("limits.maximum-name-length", 32));
    }

    public int maximumRefreshTicks() {
        return Math.max(1, Math.min(
            72_000,
            plugin.getConfig().getInt("timing.maximum-refresh-ticks", 1_200)
        ));
    }

    public double discoveryRange() {
        return Math.max(1.0, Math.min(
            256.0,
            plugin.getConfig().getDouble("discovery.range", 32.0)
        ));
    }

    public Collection<Strobe> all() {
        return strobes.values().stream()
            .sorted(Comparator.comparing(Strobe::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public Optional<Strobe> find(String name) {
        return Optional.ofNullable(strobes.get(Strobe.key(name)));
    }

    public List<String> names() {
        return all().stream().map(Strobe::name).toList();
    }

    public List<String> groups() {
        Map<String, String> canonical = new LinkedHashMap<>();
        for (Strobe strobe : all()) {
            if (strobe.hasGroup()) {
                canonical.putIfAbsent(Strobe.key(strobe.group()), strobe.group());
            }
        }
        return canonical.values().stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public Collection<Strobe> inGroup(String group) {
        String key = Strobe.key(group);
        return all().stream()
            .filter(strobe -> strobe.hasGroup() && Strobe.key(strobe.group()).equals(key))
            .toList();
    }

    public boolean validName(String name) {
        return name != null
            && name.length() <= maximumNameLength()
            && !name.equalsIgnoreCase("all")
            && !name.equalsIgnoreCase("todos")
            && !name.equalsIgnoreCase("tous")
            && !name.equalsIgnoreCase("alle")
            && !name.equalsIgnoreCase("tutti")
            && name.matches("[A-Za-z0-9_-]+");
    }

    public int maximumGroupNameLength() {
        return Math.max(1, plugin.getConfig().getInt(
            "limits.maximum-group-name-length",
            32
        ));
    }

    public boolean validGroupName(String group) {
        if (group == null
            || group.isBlank()
            || group.length() > maximumGroupNameLength()
            || !group.matches("[A-Za-z0-9_-]+")) {
            return false;
        }
        String key = Strobe.key(group);
        return !List.of(
            "all", "todos", "tous", "alle", "tutti",
            "none", "ninguno", "aucun", "keine", "nessuno"
        ).contains(key);
    }

    public boolean locationAvailable(Location location, Strobe ignored) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        Block block = location.getBlock();
        if (!block.getType().isAir()) {
            return false;
        }
        for (Strobe candidate : strobes.values()) {
            if (candidate == ignored || !candidate.placed()) {
                continue;
            }
            if (candidate.worldId().equals(location.getWorld().getUID())
                && candidate.blockX() == location.getBlockX()
                && candidate.blockY() == location.getBlockY()
                && candidate.blockZ() == location.getBlockZ()) {
                return false;
            }
        }
        return true;
    }

    public Strobe create(String name, Location location, BlockFace face, int rgb) {
        Strobe strobe = Strobe.create(name, location, face, rgb);
        strobes.put(strobe.key(), strobe);
        ensureRuntime(strobe);
        save();
        return strobe;
    }

    public Strobe createDraft(String name, World world) {
        Strobe strobe = Strobe.draft(name, world);
        strobes.put(strobe.key(), strobe);
        save();
        return strobe;
    }

    public boolean delete(Strobe strobe) {
        RuntimeState state = runtime.remove(strobe.key());
        if (state != null) {
            state.remove();
        }
        boolean removed = strobes.remove(strobe.key()) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public void rename(Strobe strobe, String newName) {
        String oldKey = strobe.key();
        RuntimeState state = runtime.remove(oldKey);
        strobes.remove(oldKey);
        strobe.rename(newName);
        strobes.put(strobe.key(), strobe);
        if (state != null) {
            runtime.put(strobe.key(), state);
        }
        save();
    }

    public void move(Strobe strobe, Location location, BlockFace face) {
        RuntimeState state = runtime.remove(strobe.key());
        if (state != null) {
            state.remove();
        }
        strobe.move(location, face);
        ensureRuntime(strobe);
        save();
    }

    public void setColor(Strobe strobe, int rgb) {
        strobe.setRgb(rgb);
        refreshMarkerItem(strobe);
        save();
    }

    public void setRefreshTicks(Strobe strobe, int refreshTicks) {
        strobe.setRefreshTicks(Math.min(maximumRefreshTicks(), refreshTicks));
        strobe.setMode(StrobeMode.STROBE);
        RuntimeState state = runtime.get(strobe.key());
        if (state != null) {
            state.ticksUntilToggle = 0;
        }
        save();
    }

    public void setMode(Strobe strobe, StrobeMode mode) {
        strobe.setMode(mode);
        RuntimeState state = runtime.computeIfAbsent(strobe.key(), ignored -> new RuntimeState());
        state.pulseTicks = 0;
        state.ticksUntilToggle = 0;
        if (mode == StrobeMode.STATIC && strobe.enabled()) {
            applyLitState(strobe, state, true);
        }
        save();
    }

    public void setLightLevel(Strobe strobe, int lightLevel) {
        strobe.setLightLevel(lightLevel);
        refreshMarkerItem(strobe);
        save();
    }

    public void setExpansion(Strobe strobe, double expansion) {
        strobe.setExpansion(expansion);
        refreshMarkerItem(strobe);
        save();
    }

    public void setGroup(Strobe strobe, String group) {
        if (group == null || group.isBlank()) {
            strobe.setGroup(Strobe.DEFAULT_GROUP);
        } else {
            String canonical = groups().stream()
                .filter(existing -> existing.equalsIgnoreCase(group))
                .findFirst()
                .orElse(group);
            strobe.setGroup(canonical);
        }
        save();
    }

    public void setBlindness(Strobe strobe, BlindnessLevel blindness) {
        strobe.setBlindness(blindness);
        save();
    }

    public void setFlashPower(Strobe strobe, int flashPower) {
        strobe.setFlashPower(flashPower);
        save();
    }

    public void setEnabled(Strobe strobe, boolean enabled) {
        strobe.setEnabled(enabled);
        RuntimeState state = runtime.computeIfAbsent(strobe.key(), ignored -> new RuntimeState());
        state.pulseTicks = 0;
        state.ticksUntilToggle = 0;
        if (!enabled) {
            applyLitState(strobe, state, false);
        }
        save();
    }

    public int setAllEnabled(boolean enabled) {
        int changed = 0;
        for (Strobe strobe : strobes.values()) {
            if (enabled && !strobe.placed()) {
                continue;
            }
            if (strobe.enabled() != enabled) {
                strobe.setEnabled(enabled);
                changed++;
            }
            RuntimeState state = runtime.computeIfAbsent(strobe.key(), ignored -> new RuntimeState());
            state.pulseTicks = 0;
            state.ticksUntilToggle = 0;
            if (!enabled) {
                applyLitState(strobe, state, false);
            }
        }
        save();
        return changed;
    }

    public int setGroupEnabled(String group, boolean enabled) {
        int changed = 0;
        for (Strobe strobe : inGroup(group)) {
            if (enabled && !strobe.placed()) {
                continue;
            }
            if (strobe.enabled() != enabled) {
                strobe.setEnabled(enabled);
                changed++;
            }
            RuntimeState state = runtime.computeIfAbsent(
                strobe.key(),
                ignored -> new RuntimeState()
            );
            state.pulseTicks = 0;
            state.ticksUntilToggle = 0;
            if (!enabled) {
                applyLitState(strobe, state, false);
            }
        }
        save();
        return changed;
    }

    public boolean toggleGroup(String group) {
        boolean enable = inGroup(group).stream()
            .anyMatch(strobe -> strobe.placed() && !strobe.enabled());
        setGroupEnabled(group, enable);
        return enable;
    }

    public int pulseGroup(String group) {
        int count = 0;
        for (Strobe strobe : inGroup(group)) {
            if (strobe.placed()) {
                pulse(strobe);
                count++;
            }
        }
        return count;
    }

    public void pulse(Strobe strobe) {
        if (!strobe.placed()) {
            return;
        }
        RuntimeState state = ensureRuntime(strobe);
        state.pulseTicks = strobe.refreshTicks();
        state.ticksUntilToggle = strobe.refreshTicks();
        applyLitState(strobe, state, true);
    }

    public boolean previewCameraFlash(Player player, Strobe strobe) {
        if (!strobe.blindness().enabled()
            || strobe.flashPower() <= 0
            || plugin.resourcePack() != null && !plugin.resourcePack().isLoaded(player)) {
            return false;
        }
        startCameraFlash(player, strobe);
        return true;
    }

    /** Detonates one maximum-power white strobe pulse at an arbitrary 3D point. */
    public void detonateFlashbang(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        Location impact = location.clone();
        int configuredDuration = plugin.getConfig().getInt(
            "throwable-flashbang.scene-light-duration-ticks",
            60
        );
        // 30 was the 0.8.5 default and could expire before a joining client's
        // light engine received the block update. Migrate only that old
        // default; explicitly customized values remain configurable.
        if (configuredDuration == 30) {
            configuredDuration = 60;
        }
        int duration = Math.max(1, Math.min(200, configuredDuration));
        Location sceneLocation = sceneLightLocation(impact);

        // The physical detonation is unconditional. Create its shared world
        // source before any per-player sound/camera work so those optional
        // effects can never prevent the environmental pulse.
        emitThrowableDetonation(sceneLocation);
        UUID id = UUID.randomUUID();
        SceneFlash scene = new SceneFlash(sceneLocation, duration);
        scene.source = spawnSceneFlashSource(id, sceneLocation);
        placeSceneVanillaLight(scene);
        sceneFlashes.put(id, scene);
        updateSceneFlash(scene);

        playThrowableFlashbangSound(impact);
        triggerThrowableCameraFlash(impact);
    }

    private void emitThrowableDetonation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        // Force sends the cue well beyond the blindness radius. The scene
        // LIGHT block below is also created even when world.getPlayers() is empty.
        world.spawnParticle(
            Particle.FLASH,
            location,
            1,
            0.0,
            0.0,
            0.0,
            0.0,
            Color.WHITE,
            true
        );
        world.spawnParticle(
            Particle.EXPLOSION,
            location,
            1,
            0.0,
            0.0,
            0.0,
            0.0,
            null,
            true
        );
        float volume = (float) Math.max(0.0, Math.min(
            16.0,
            plugin.getConfig().getDouble(
                "throwable-flashbang.detonation-cue-volume",
                8.0
            )
        ));
        float pitch = (float) Math.max(0.5, Math.min(
            2.0,
            plugin.getConfig().getDouble(
                "throwable-flashbang.detonation-cue-pitch",
                1.6
            )
        ));
        if (volume > 0.0f) {
            world.playSound(
                location,
                Sound.ENTITY_FIREWORK_ROCKET_BLAST,
                SoundCategory.PLAYERS,
                volume,
                pitch
            );
        }
    }

    private void playThrowableFlashbangSound(Location impact) {
        World world = impact.getWorld();
        if (world == null) {
            return;
        }
        double radius = Math.max(1.0, Math.min(
            128.0,
            plugin.getConfig().getDouble("throwable-flashbang.sound-radius", 32.0)
        ));
        double fullVolumeDistance = Math.max(0.0, Math.min(
            radius,
            plugin.getConfig().getDouble(
                "throwable-flashbang.full-volume-distance",
                5.0
            )
        ));
        double exponent = Math.max(0.1, Math.min(
            4.0,
            plugin.getConfig().getDouble(
                "throwable-flashbang.sound-falloff-exponent",
                1.0
            )
        ));
        double maximumVolume = Math.max(0.0, Math.min(
            16.0,
            plugin.getConfig().getDouble("throwable-flashbang.sound-volume", 4.0)
        ));
        float pitch = (float) Math.max(0.5, Math.min(
            2.0,
            plugin.getConfig().getDouble("throwable-flashbang.sound-pitch", 1.0)
        ));
        for (Player player : world.getPlayers()) {
            if (plugin.resourcePack() != null && !plugin.resourcePack().isLoaded(player)) {
                continue;
            }
            double distance = player.getEyeLocation().distance(impact);
            double scale = flashbangDistanceScale(
                distance,
                fullVolumeDistance,
                radius,
                exponent
            );
            float volume = (float) (maximumVolume * scale);
            if (volume <= 0.01f) {
                continue;
            }
            // Play locally so Minecraft does not apply a second distance falloff
            // on top of the explicit radial curve calculated above.
            player.playSound(
                player.getLocation(),
                "strobelights:flashbang",
                SoundCategory.PLAYERS,
                volume,
                pitch
            );
        }
    }

    private void triggerThrowableCameraFlash(Location impact) {
        World world = impact.getWorld();
        if (world == null) {
            return;
        }
        double radius = Math.max(1.0, Math.min(
            128.0,
            plugin.getConfig().getDouble("throwable-flashbang.radius", 24.0)
        ));
        double fullEffectDistance = Math.max(0.0, Math.min(
            radius,
            plugin.getConfig().getDouble(
                "throwable-flashbang.full-effect-distance",
                5.0
            )
        ));
        double exponent = Math.max(0.1, Math.min(
            4.0,
            plugin.getConfig().getDouble(
                "throwable-flashbang.effect-falloff-exponent",
                1.2
            )
        ));
        int maximumDuration = Math.max(1, Math.min(
            1_200,
            plugin.getConfig().getInt(
                "throwable-flashbang.maximum-screen-flash-duration-ticks",
                100
            )
        ));
        boolean requireLooking = plugin.getConfig().getBoolean(
            "throwable-flashbang.require-looking-at-light",
            true
        );
        boolean requireLineOfSight = plugin.getConfig().getBoolean(
            "throwable-flashbang.require-line-of-sight",
            true
        );

        for (Player player : world.getPlayers()) {
            if (plugin.resourcePack() != null && !plugin.resourcePack().isLoaded(player)) {
                continue;
            }
            Location eye = player.getEyeLocation();
            Vector toLight = impact.toVector().subtract(eye.toVector());
            double distance = toLight.length();
            double scale = flashbangDistanceScale(
                distance,
                fullEffectDistance,
                radius,
                exponent
            );
            if (scale <= 0.0) {
                continue;
            }
            if (distance > 1.0e-8) {
                Vector direction = toLight.multiply(1.0 / distance);
                double viewDot = eye.getDirection().normalize().dot(direction);
                if (requireLooking
                    && !meetsFlashViewRequirement(viewDot, BlindnessLevel.EXTREME)) {
                    continue;
                }
                if (requireLineOfSight
                    && blockedByGeometry(eye, direction, distance)) {
                    continue;
                }
            }
            startCameraFlash(
                player,
                0xFFFFFF,
                BlindnessLevel.EXTREME,
                200,
                scale,
                flashbangDurationTicks(scale, maximumDuration)
            );
        }
    }

    static double flashbangDistanceScale(
        double distance,
        double fullEffectDistance,
        double radius,
        double exponent
    ) {
        double safeRadius = Math.max(0.001, radius);
        double safeFullDistance = Math.max(0.0, Math.min(safeRadius, fullEffectDistance));
        double safeDistance = Math.max(0.0, distance);
        if (safeDistance >= safeRadius) {
            return 0.0;
        }
        if (safeDistance <= safeFullDistance) {
            return 1.0;
        }
        if (safeFullDistance >= safeRadius) {
            return 1.0;
        }
        double linear = 1.0
            - (safeDistance - safeFullDistance) / (safeRadius - safeFullDistance);
        return Math.pow(Math.max(0.0, Math.min(1.0, linear)), Math.max(0.1, exponent));
    }

    static int flashbangDurationTicks(double distanceScale, int maximumTicks) {
        double scale = Math.max(0.0, Math.min(1.0, distanceScale));
        int maximum = Math.max(0, maximumTicks);
        if (scale <= 0.0 || maximum == 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(maximum * scale));
    }

    public boolean teleport(Player player, Strobe strobe) {
        Location impact = strobe.impactLocation();
        if (impact == null) {
            return false;
        }
        double outward = plugin.getConfig().getDouble("teleport.outward-offset", 1.25);
        double vertical = plugin.getConfig().getDouble("teleport.vertical-offset", 0.25);
        Location destination = impact.clone()
            .add(strobe.normal().multiply(outward))
            .add(0.0, vertical, 0.0);
        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());
        return player.teleport(destination);
    }

    public boolean isManualDiscovery(Player player) {
        return manualDiscovery.contains(player.getUniqueId());
    }

    public boolean setDiscovery(Player player, boolean enabled) {
        if (enabled) {
            manualDiscovery.add(player.getUniqueId());
        } else {
            manualDiscovery.remove(player.getUniqueId());
        }
        return enabled;
    }

    public boolean toggleDiscovery(Player player) {
        return setDiscovery(player, !isManualDiscovery(player));
    }

    public boolean isDiscoveryActive(Player player) {
        return plugin.getConfig().getBoolean("discovery.enabled", true)
            && (isManualDiscovery(player) || isHoldingEasyArmorStandsTool(player));
    }

    public boolean easyArmorStandsAvailable() {
        return plugin.getServer().getPluginManager().isPluginEnabled("EasyArmorStands");
    }

    public void clearPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        manualDiscovery.remove(playerId);
        activeDiscoveryPlayers.remove(playerId);
        CameraFlash flash = cameraFlashes.remove(playerId);
        if (flash != null) {
            flash.remove();
        }
        sceneFlashes.values().forEach(scene -> {
            scene.sourceViewers.remove(playerId);
        });
        for (RuntimeState state : runtime.values()) {
            state.removeDiscoveryLight(playerId);
            state.editorVisible.remove(playerId);
            state.sourceHidden.remove(playerId);
        }
    }

    public void setMarkersVisible(Player player, boolean visible) {
        for (RuntimeState state : runtime.values()) {
            if (!state.valid()) {
                continue;
            }
            state.sourceHidden.remove(player.getUniqueId());
            if (visible) {
                player.showEntity(plugin, state.marker);
            } else {
                player.hideEntity(plugin, state.marker);
            }
        }
        if (!visible) {
            CameraFlash flash = cameraFlashes.remove(player.getUniqueId());
            if (flash != null) {
                flash.remove();
            }
            sceneFlashes.values().forEach(scene -> {
                scene.hideSource(plugin, player);
            });
        }
    }

    private void tick() {
        refreshDiscoveryPlayers();
        for (Strobe strobe : new ArrayList<>(strobes.values())) {
            try {
                tick(strobe);
            } catch (RuntimeException exception) {
                RuntimeState state = runtime.computeIfAbsent(strobe.key(), ignored -> new RuntimeState());
                if (!state.failureLogged) {
                    state.failureLogged = true;
                    plugin.getLogger().log(
                        Level.WARNING,
                        "Error while updating strobe '" + strobe.name() + "'",
                        exception
                    );
                }
            }
        }
        tickDiscovery();
        tickSceneFlashes();
        tickCameraFlashes();
        if (pendingEditorSaveTicks > 0 && --pendingEditorSaveTicks == 0) {
            save();
        }
    }

    private void tick(Strobe strobe) {
        if (!strobe.placed()) {
            return;
        }
        World world = strobe.world();
        if (world == null || !world.isChunkLoaded(strobe.blockX() >> 4, strobe.blockZ() >> 4)) {
            return;
        }

        RuntimeState state = ensureRuntime(strobe);
        state.failureLogged = false;
        if (!state.valid()) {
            state.remove();
            spawnMarker(strobe, state);
            applyMarkerItem(strobe, state);
        }
        ensureEditorHandle(strobe, state);
        synchronizeEditorHandle(strobe, state);

        if (state.pulseTicks > 0) {
            state.pulseTicks--;
            applyLitState(strobe, state, true);
            if (state.pulseTicks == 0 && !strobe.enabled()) {
                applyLitState(strobe, state, false);
            }
        } else if (strobe.enabled() && strobe.mode() == StrobeMode.STATIC) {
            applyLitState(strobe, state, true);
            state.ticksUntilToggle = strobe.refreshTicks();
        } else if (strobe.enabled()) {
            if (state.ticksUntilToggle <= 0) {
                applyLitState(strobe, state, !state.lit);
                state.ticksUntilToggle = strobe.refreshTicks();
            }
            state.ticksUntilToggle--;
        } else {
            applyLitState(strobe, state, false);
        }

        // The entity itself never leaves the saved source. Display culling is
        // disabled so the core shader can carry an off-screen point without a
        // camera- or player-following entity.
        updateFixedSourceViewers(strobe, state);
        updateSourceVisibility(state);

    }

    private RuntimeState ensureRuntime(Strobe strobe) {
        RuntimeState state = runtime.computeIfAbsent(strobe.key(), ignored -> new RuntimeState());
        if (!strobe.placed()) {
            return state;
        }
        World world = strobe.world();
        if (world != null
            && world.isChunkLoaded(strobe.blockX() >> 4, strobe.blockZ() >> 4)
            && !state.valid()) {
            state.remove();
            spawnMarker(strobe, state);
            applyMarkerItem(strobe, state);
        }
        if (state.valid()) {
            ensureEditorHandle(strobe, state);
        }
        return state;
    }

    private void spawnMarker(Strobe strobe, RuntimeState state) {
        Location markerLocation = fixedSourceLocation(strobe);
        if (markerLocation == null) {
            return;
        }
        state.marker = spawnFixedLightDisplay(markerLocation, display -> {
            display.getPersistentDataContainer().set(
                entityKey,
                PersistentDataType.STRING,
                strobe.key()
            );
        });
        if (plugin.resourcePack() != null) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (plugin.resourcePack().isLoaded(player)) {
                    player.showEntity(plugin, state.marker);
                }
            }
        }
        ensureEditorHandle(strobe, state);
    }

    private static Location fixedSourceLocation(Strobe strobe) {
        Location impact = strobe.impactLocation();
        if (impact == null) {
            return null;
        }
        return impact.add(strobe.normal().multiply(MARKER_SURFACE_OFFSET));
    }

    private ItemDisplay spawnFixedLightDisplay(
        Location source,
        Consumer<ItemDisplay> initializer
    ) {
        FixedRenderCarrier carrier = fixedRenderCarrier(source);
        Location anchor = fixedRenderCarrierAnchor(source, carrier);
        return anchor.getWorld().spawn(anchor, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setVisibleByDefault(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setShadowRadius(0.0f);
            display.setShadowStrength(0.0f);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(0);
            display.setTeleportDuration(0);
            applyFixedRenderCarrier(display, carrier);
            initializer.accept(display);
        });
    }

    private void positionFixedLightDisplay(ItemDisplay display, Location source) {
        FixedRenderCarrier carrier = fixedRenderCarrier(source);
        display.teleport(fixedRenderCarrierAnchor(source, carrier));
        applyFixedRenderCarrier(display, carrier);
    }

    private FixedRenderCarrier fixedRenderCarrier(Location source) {
        World world = source.getWorld();
        return fixedRenderCarrier(
            source.getY(),
            world.getMinHeight(),
            world.getMaxHeight(),
            displayViewRangeBlocks()
        );
    }

    static FixedRenderCarrier fixedRenderCarrier(
        double sourceY,
        int minimumWorldHeight,
        int maximumWorldHeight,
        double renderRange
    ) {
        double range = Math.max(16.0, Math.min(256.0, renderRange));
        // Keep the network-tracked entity and the rendered point at the exact
        // saved source. Moving the entity down by `range` and compensating with
        // a display translation made the complete RGB light disappear on some
        // clients. A zero culling dimension is Minecraft's explicit
        // no-frustum-culling mode for display entities.
        return new FixedRenderCarrier(
            sourceY,
            0.0f,
            0.0f,
            0.0f,
            (float) ((range + 16.0) / 64.0)
        );
    }

    private static Location fixedRenderCarrierAnchor(
        Location source,
        FixedRenderCarrier carrier
    ) {
        Location anchor = source.clone();
        anchor.setY(carrier.anchorY());
        return anchor;
    }

    private static void applyFixedRenderCarrier(
        ItemDisplay display,
        FixedRenderCarrier carrier
    ) {
        display.setViewRange(carrier.viewRange());
        display.setDisplayWidth(carrier.displayWidth());
        display.setDisplayHeight(carrier.displayHeight());
        display.setTransformation(new Transformation(
            new Vector3f(0.0f, carrier.translationY(), 0.0f),
            new Quaternionf(),
            new Vector3f(1.0f, 1.0f, 1.0f),
            new Quaternionf()
        ));
    }

    private void ensureEditorHandle(Strobe strobe, RuntimeState state) {
        if (state.editorHandle != null
            && state.editorHandle.isValid()
            && !state.editorHandle.isDead()) {
            return;
        }
        Location location = strobe.impactLocation();
        if (location == null) {
            return;
        }
        state.editorHandle = location.getWorld().spawn(location, ItemDisplay.class, display -> {
            // EasyArmorStands rejects non-persistent entities by default. This
            // handle is explicitly registered and removed by StrobeLights.
            display.setPersistent(true);
            display.setVisibleByDefault(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            display.setBillboard(Display.Billboard.CENTER);
            display.setBrightness(new Display.Brightness(15, 15));
            // Glowing entities are intentionally visible through solid
            // blocks. Discovery handles must obey normal depth instead.
            display.setGlowing(false);
            display.setShadowRadius(0.0f);
            display.setShadowStrength(0.0f);
            display.setViewRange(2.0f);
            float selectionBox = (float) Math.max(0.5, Math.min(
                3.0,
                plugin.getConfig().getDouble(
                    "easy-armor-stands.selection-box-size",
                    1.0
                )
            ));
            display.setDisplayWidth(selectionBox);
            display.setDisplayHeight(selectionBox);
            display.setItemStack(guiIcon(EDITOR_HANDLE_MODEL_DATA));
            display.getPersistentDataContainer().set(
                editorEntityKey,
                PersistentDataType.STRING,
                strobe.key()
            );
            display.getPersistentDataContainer().set(
                easyArmorStandsElementTypeKey,
                PersistentDataType.STRING,
                "minecraft:item_display"
            );
        });
        state.editorAnchor = location.clone();
    }

    private void synchronizeEditorHandle(Strobe strobe, RuntimeState state) {
        if (state.editorHandle == null || !state.editorHandle.isValid()) {
            return;
        }
        Location current = state.editorHandle.getLocation();
        if (state.editorAnchor == null) {
            state.editorAnchor = current.clone();
            return;
        }
        if (current.getWorld() == state.editorAnchor.getWorld()
            && current.distanceSquared(state.editorAnchor) < 1.0e-8) {
            return;
        }
        strobe.move(current, BlockFace.SELF);
        state.editorAnchor = current.clone();
        state.clearVanillaLight();
        state.clearDiscoveryLights();
        if (state.valid()) {
            positionFixedLightDisplay(state.marker, current);
        }
        pendingEditorSaveTicks = 20;
    }

    private void refreshMarkerItem(Strobe strobe) {
        RuntimeState state = runtime.get(strobe.key());
        if (state != null && state.valid()) {
            applyMarkerItem(strobe, state);
        }
    }

    private void applyLitState(Strobe strobe, RuntimeState state, boolean lit) {
        if (state.lit == lit) {
            return;
        }
        state.lit = lit;
        applyMarkerItem(strobe, state);
        if (lit && strobe.blindness().enabled()) {
            triggerFlashbang(strobe);
        }
    }

    private void applyMarkerItem(Strobe strobe, RuntimeState state) {
        applyVanillaFallback(strobe, state);
        if (!state.valid()) {
            return;
        }
        if (!state.lit || strobe.lightLevel() <= 0) {
            // Keep the technical model present with zero RGB. Replacing it
            // with AIR every phase made the client rebuild the render entry
            // and caused visible hitches at the on/off boundary.
            state.marker.setItemStack(technicalMarker(0));
            return;
        }
        state.marker.setItemStack(lightPainterMarker(
            strobe.rgb(),
            strobe.lightLevel(),
            strobe.expansionCode()
        ));
    }

    private void applyVanillaFallback(Strobe strobe, RuntimeState state) {
        boolean enabled = plugin.getConfig().getBoolean("vanilla-fallback.enabled", true);
        boolean shouldLight = shouldLightVanillaFallback(
            enabled,
            state.lit,
            strobe.lightLevel()
        );
        if (!shouldLight) {
            state.clearVanillaLight();
            return;
        }
        Block target = vanillaLightTarget(strobe);
        if (target == null) {
            state.clearVanillaLight();
            return;
        }
        if (state.vanillaLight != null && !state.vanillaLight.equals(target)) {
            state.clearVanillaLight();
        }
        if (state.vanillaLight == null) {
            state.vanillaLight = target;
            state.originalAir = target.getType().isAir() ? target.getType() : Material.AIR;
        }
        org.bukkit.block.data.type.Light light =
            (org.bukkit.block.data.type.Light) Material.LIGHT.createBlockData();
        light.setLevel(Math.max(1, Math.min(15, strobe.lightLevel())));
        target.setBlockData(light, false);
    }

    static boolean shouldLightVanillaFallback(
        boolean fallbackEnabled,
        boolean lit,
        int lightLevel
    ) {
        return fallbackEnabled && lit && lightLevel > 0;
    }

    private static Block vanillaLightTarget(Strobe strobe) {
        Location impact = strobe.impactLocation();
        if (impact == null || impact.getWorld() == null) {
            return null;
        }
        // Surface strobes are stored in the air block outside the clicked
        // face. Reconstruct that outward point instead of trusting the saved
        // block blindly: an editor can move the marker onto/inside geometry.
        Location preferred = impact.clone().add(strobe.normal().multiply(0.55));
        Block target = preferred.getBlock();
        if (target.getType() == Material.LIGHT || target.getType().isAir()) {
            return target;
        }
        return nearestAirBlock(preferred);
    }

    private static ItemStack lightPainterMarker(
        int rgb,
        int lightLevel,
        int expansionCode
    ) {
        return technicalMarker(packSourceLightColor(rgb, lightLevel, expansionCode));
    }

    static int packSourceLightColor(int rgb, int lightLevel, int expansionCode) {
        double intensity = Math.max(0, Math.min(15, lightLevel)) / 15.0;
        int red4 = (int) Math.round((rgb >> 16 & 0xFF) * intensity * 15.0 / 255.0);
        int green4 = (int) Math.round((rgb >> 8 & 0xFF) * intensity * 15.0 / 255.0);
        int blue4 = (int) Math.round((rgb & 0xFF) * intensity * 15.0 / 255.0);
        return SOURCE_LIGHT_SIGNATURE << 20
            | (Math.max(0, Math.min(15, expansionCode)) << 16)
            | red4 << 12
            | green4 << 8
            | blue4 << 4
            | SOURCE_LIGHT_TRAILER;
    }

    private static ItemStack technicalMarker(int rgb) {
        ItemStack stack = new ItemStack(Material.LIME_STAINED_GLASS);
        ItemMeta meta = stack.getItemMeta();
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setFloats(List.of(LIGHT_PAINTER_MODEL_DATA));
        component.setColors(List.of(Color.fromRGB(rgb)));
        meta.setCustomModelDataComponent(component);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack guiIcon(float customModelData) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setFloats(List.of(customModelData));
        meta.setCustomModelDataComponent(component);
        stack.setItemMeta(meta);
        return stack;
    }

    private void updateFixedSourceViewers(Strobe strobe, RuntimeState state) {
        if (strobe.lightLevel() <= 0) {
            restoreSourceMarkers(state);
            return;
        }
        Location source = fixedSourceLocation(strobe);
        if (source == null) {
            restoreSourceMarkers(state);
            return;
        }
        // The fixed source must reach the shader even when the player cannot
        // see the origin itself. Per-pixel depth rays decide which visible
        // surfaces the light can actually reach; only discovery replaces this
        // shared marker with its private steady preview.
        for (Player player : source.getWorld().getPlayers()) {
            if (plugin.resourcePack() != null && !plugin.resourcePack().isLoaded(player)) {
                continue;
            }
            if (discoveryApplies(player, source)) {
                hideSourceMarker(player, state);
                continue;
            }
            showSourceMarker(player, state);
        }
    }

    private void updateSourceVisibility(RuntimeState state) {
        if (!state.valid()) {
            return;
        }
        // Discovery players keep the shared source hidden to avoid rendering a
        // duplicate underneath their private steady preview.
        restoreUnproxiedSourceMarkers(state);
    }

    private void tickDiscovery() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean active = activeDiscoveryPlayers.contains(player.getUniqueId());
            boolean packLoaded = plugin.resourcePack() == null
                || plugin.resourcePack().isLoaded(player);
            for (Strobe strobe : strobes.values()) {
                RuntimeState state = runtime.get(strobe.key());
                if (state == null || !strobe.placed()) {
                    continue;
                }
                ensureEditorHandle(strobe, state);
                Location source = fixedSourceLocation(strobe);
                boolean nearby = active && source != null && discoveryApplies(player, source);
                boolean visible = nearby && !sourceBlockedForPlayer(player, source);
                setEditorHandleVisible(player, state, visible);
                if (visible && packLoaded) {
                    updateDiscoveryLight(player, strobe, state, source);
                } else {
                    state.removeDiscoveryLight(player.getUniqueId());
                }
            }
        }
    }

    private boolean discoveryApplies(Player player, Location impact) {
        return activeDiscoveryPlayers.contains(player.getUniqueId())
            && impact.getWorld() == player.getWorld()
            && impact.distanceSquared(player.getEyeLocation())
                <= discoveryRange() * discoveryRange();
    }

    private void refreshDiscoveryPlayers() {
        activeDiscoveryPlayers.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isDiscoveryActive(player)) {
                activeDiscoveryPlayers.add(player.getUniqueId());
            }
        }
    }

    private void setEditorHandleVisible(Player player, RuntimeState state, boolean visible) {
        if (state.editorHandle == null || !state.editorHandle.isValid()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (visible && state.editorVisible.add(playerId)) {
            player.showEntity(plugin, state.editorHandle);
        } else if (!visible && state.editorVisible.remove(playerId)) {
            player.hideEntity(plugin, state.editorHandle);
        }
    }

    private void updateDiscoveryLight(
        Player player,
        Strobe strobe,
        RuntimeState state,
        Location impact
    ) {
        UUID playerId = player.getUniqueId();
        ItemDisplay light = state.discoveryLights.get(playerId);
        if (light == null || !light.isValid() || light.getWorld() != player.getWorld()) {
            if (light != null) {
                light.remove();
            }
            light = spawnDiscoveryLight(player, strobe, impact);
            state.discoveryLights.put(playerId, light);
        } else {
            FixedRenderCarrier carrier = fixedRenderCarrier(impact);
            Location expectedAnchor = fixedRenderCarrierAnchor(impact, carrier);
            if (light.getLocation().distanceSquared(expectedAnchor) > 1.0e-8) {
                positionFixedLightDisplay(light, impact);
            }
        }
        int minimumLevel = Math.max(1, Math.min(
            15,
            plugin.getConfig().getInt("discovery.minimum-light-level", 10)
        ));
        light.setItemStack(lightPainterMarker(
            strobe.rgb(),
            Math.max(minimumLevel, strobe.lightLevel()),
            strobe.expansionCode()
        ));
        hideSourceMarker(player, state);
    }

    private ItemDisplay spawnDiscoveryLight(Player player, Strobe strobe, Location location) {
        ItemDisplay light = spawnFixedLightDisplay(location, display -> {
            display.getPersistentDataContainer().set(
                discoveryEntityKey,
                PersistentDataType.STRING,
                player.getUniqueId() + ":" + strobe.key()
            );
        });
        player.showEntity(plugin, light);
        return light;
    }

    private boolean isHoldingEasyArmorStandsTool(Player player) {
        if (!plugin.getConfig().getBoolean("easy-armor-stands.enabled", true)
            || !plugin.getConfig().getBoolean(
                "easy-armor-stands.auto-discovery-with-tool",
                true
            )
            || !easyArmorStandsAvailable()) {
            return false;
        }
        return isEasyArmorStandsTool(player.getInventory().getItemInMainHand())
            || isEasyArmorStandsTool(player.getInventory().getItemInOffHand());
    }

    private boolean isEasyArmorStandsTool(ItemStack item) {
        return item != null
            && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(
                easyArmorStandsToolKey,
                PersistentDataType.BYTE
            );
    }

    private static CameraBasis cameraBasis(Location eye) {
        Vector forward = eye.getDirection().normalize();
        double yaw = Math.toRadians(eye.getYaw());
        Vector right = new Vector(-Math.cos(yaw), 0.0, -Math.sin(yaw)).normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        return new CameraBasis(forward, right, up);
    }

    private double displayViewRangeBlocks() {
        return Math.max(16.0, Math.min(
            256.0,
            plugin.getConfig().getDouble("render.display-view-range", 128.0)
        ));
    }

    private void hideSourceMarker(Player player, RuntimeState state) {
        if (state.valid() && state.sourceHidden.add(player.getUniqueId())) {
            player.hideEntity(plugin, state.marker);
        }
    }

    private void showSourceMarker(Player player, RuntimeState state) {
        if (state.valid() && state.sourceHidden.remove(player.getUniqueId())) {
            player.showEntity(plugin, state.marker);
        }
    }

    private void restoreSourceMarkers(RuntimeState state) {
        for (UUID playerId : new HashSet<>(state.sourceHidden)) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()
                && (plugin.resourcePack() == null || plugin.resourcePack().isLoaded(player))) {
                player.showEntity(plugin, state.marker);
            }
        }
        state.sourceHidden.clear();
    }

    private void restoreUnproxiedSourceMarkers(RuntimeState state) {
        for (UUID playerId : new HashSet<>(state.sourceHidden)) {
            if (state.discoveryLights.containsKey(playerId)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()
                && (plugin.resourcePack() == null || plugin.resourcePack().isLoaded(player))) {
                player.showEntity(plugin, state.marker);
            }
            state.sourceHidden.remove(playerId);
        }
    }

    private void tickSceneFlashes() {
        var iterator = sceneFlashes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SceneFlash> entry = iterator.next();
            SceneFlash scene = entry.getValue();
            if (scene.remainingTicks-- <= 0 || scene.location.getWorld() == null) {
                scene.remove();
                iterator.remove();
                continue;
            }
            updateSceneFlash(scene);
        }
    }

    private void updateSceneFlash(SceneFlash scene) {
        World world = scene.location.getWorld();
        if (world == null) {
            return;
        }
        double radius = Math.max(16.0, Math.min(
            displayViewRangeBlocks(),
            plugin.getConfig().getDouble(
                "throwable-flashbang.scene-view-range",
                displayViewRangeBlocks()
            )
        ));
        Set<UUID> eligibleViewers = new HashSet<>();
        for (Player player : world.getPlayers()) {
            UUID playerId = player.getUniqueId();
            if (plugin.resourcePack() != null && !plugin.resourcePack().isLoaded(player)) {
                scene.hideSource(plugin, player);
                continue;
            }
            Location eye = player.getEyeLocation();
            Vector toLight = scene.location.toVector().subtract(eye.toVector());
            double distance = toLight.length();
            if (distance > radius) {
                scene.hideSource(plugin, player);
                continue;
            }
            eligibleViewers.add(playerId);
            scene.showSource(plugin, player);
        }
        scene.retainSourceViewers(plugin, eligibleViewers);
    }

    private ItemDisplay spawnSceneFlashSource(UUID id, Location location) {
        return spawnFixedLightDisplay(location, display -> {
            display.setItemStack(lightPainterMarker(
                0xFFFFFF,
                15,
                (int) Math.round(Strobe.DEFAULT_EXPANSION / Strobe.EXPANSION_STEP) - 1
            ));
            display.getPersistentDataContainer().set(
                sceneFlashEntityKey,
                PersistentDataType.STRING,
                "source:" + id
            );
        });
    }

    private void placeSceneVanillaLight(SceneFlash scene) {
        if (!plugin.getConfig().getBoolean("vanilla-fallback.enabled", true)) {
            return;
        }
        Block target = nearestAirBlock(scene.location);
        if (target == null) {
            return;
        }
        scene.vanillaLight = target;
        scene.originalAir = target.getType();
        org.bukkit.block.data.type.Light light =
            (org.bukkit.block.data.type.Light) Material.LIGHT.createBlockData();
        light.setLevel(15);
        target.setBlockData(light, false);
    }

    private static Location sceneLightLocation(Location impact) {
        Block air = nearestAirBlock(impact);
        return air == null
            ? impact.clone()
            : air.getLocation().add(0.5, 0.5, 0.5);
    }

    private static Block nearestAirBlock(Location location) {
        Block origin = location.getBlock();
        if (origin.getType().isAir()) {
            return origin;
        }
        Block nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockFace face : List.of(
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
        )) {
            Block candidate = origin.getRelative(face);
            if (!candidate.getType().isAir()) {
                continue;
            }
            double distance = candidate.getLocation().add(0.5, 0.5, 0.5)
                .distanceSquared(location);
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void triggerFlashbang(Strobe strobe) {
        Location source = fixedSourceLocation(strobe);
        if (source == null) {
            return;
        }
        triggerFlashbang(
            source,
            strobe.rgb(),
            strobe.blindness(),
            strobe.flashPower(),
            "flashbang"
        );
    }

    private void triggerFlashbang(
        Location impact,
        int rgb,
        BlindnessLevel level,
        int flashPower,
        String configRoot
    ) {
        World world = impact.getWorld();
        if (world == null) {
            return;
        }
        double radius = Math.max(1.0, Math.min(
            64.0,
            plugin.getConfig().getDouble(
                configRoot + ".radius",
                plugin.getConfig().getDouble("blindness.radius", 16.0)
            )
        ));
        boolean requireLooking = plugin.getConfig().getBoolean(
            configRoot + ".require-looking-at-light",
            plugin.getConfig().getBoolean("blindness.require-looking-at-light", true)
        );
        boolean requireLineOfSight = plugin.getConfig().getBoolean(
            configRoot + ".require-line-of-sight",
            plugin.getConfig().getBoolean("blindness.require-line-of-sight", true)
        );
        for (Player player : world.getPlayers()) {
            if (plugin.resourcePack() != null && !plugin.resourcePack().isLoaded(player)) {
                continue;
            }
            Location eye = player.getEyeLocation();
            Vector toLight = impact.toVector().subtract(eye.toVector());
            double distanceSquared = toLight.lengthSquared();
            if (distanceSquared > radius * radius) {
                continue;
            }
            if (distanceSquared < 1.0e-8) {
                continue;
            }
            double distance = Math.sqrt(distanceSquared);
            Vector direction = toLight.multiply(1.0 / distance);
            double viewDot = eye.getDirection().normalize().dot(direction);
            if (requireLooking && !meetsFlashViewRequirement(viewDot, level)) {
                continue;
            }
            if (requireLineOfSight && blockedByGeometry(eye, direction, distance)) {
                continue;
            }

            startCameraFlash(player, rgb, level, flashPower);
        }
    }

    private void startCameraFlash(Player player, Strobe strobe) {
        startCameraFlash(
            player,
            strobe.rgb(),
            strobe.blindness(),
            strobe.flashPower()
        );
    }

    private void startCameraFlash(
        Player player,
        int rgb,
        BlindnessLevel level,
        int flashPower
    ) {
        startCameraFlash(player, rgb, level, flashPower, 1.0, level.fadeOutTicks());
    }

    private void startCameraFlash(
        Player player,
        int rgb,
        BlindnessLevel level,
        int flashPower,
        double strengthScale,
        int durationTicks
    ) {
        double peakStrength = Math.min(
            1.0,
            level.screenStrength() * flashPower / 100.0
        ) * Math.max(0.0, Math.min(1.0, strengthScale));
        if (peakStrength <= 0.0) {
            return;
        }
        int duration = Math.max(1, Math.min(1_200, durationTicks));
        CameraFlash flash = cameraFlashes.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new CameraFlash()
        );
        flash.rgb = rgb;
        flash.peakStrength = Math.max(flash.peakStrength, peakStrength);
        flash.totalTicks = Math.max(flash.totalTicks, duration);
        flash.remainingTicks = Math.max(flash.remainingTicks, duration);
        updateCameraFlash(player, flash);
    }

    private void tickCameraFlashes() {
        var iterator = cameraFlashes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CameraFlash> entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            CameraFlash flash = entry.getValue();
            if (player == null
                || !player.isOnline()
                || flash.remainingTicks <= 0
                || plugin.resourcePack() != null && !plugin.resourcePack().isLoaded(player)) {
                flash.remove();
                iterator.remove();
                continue;
            }
            updateCameraFlash(player, flash);
            flash.remainingTicks--;
        }
    }

    private void updateCameraFlash(Player player, CameraFlash flash) {
        Location eye = player.getEyeLocation();
        CameraBasis basis = cameraBasis(eye);
        Location markerLocation = eye.clone()
            .add(basis.forward().multiply(CAMERA_FLASH_MARKER_DISTANCE))
            .subtract(basis.right().multiply(CAMERA_FLASH_MARKER_SIDE));
        if (!flash.valid() || flash.marker.getWorld() != player.getWorld()) {
            flash.remove();
            flash.marker = spawnCameraFlashMarker(player, markerLocation);
        } else {
            flash.marker.teleport(markerLocation);
        }
        double progress = flash.totalTicks <= 0
            ? 0.0 : (double) flash.remainingTicks / flash.totalTicks;
        double strength = flash.peakStrength * Math.sqrt(Math.max(0.0, progress));
        flash.marker.setItemStack(cameraFlashMarker(flash.rgb, strength));
    }

    private ItemDisplay spawnCameraFlashMarker(Player player, Location location) {
        ItemDisplay marker = location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setVisibleByDefault(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setShadowRadius(0.0f);
            display.setShadowStrength(0.0f);
            display.setViewRange(1.0f);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(0);
            display.setDisplayWidth(0.5f);
            display.setDisplayHeight(0.5f);
            display.getPersistentDataContainer().set(
                flashEntityKey,
                PersistentDataType.STRING,
                player.getUniqueId().toString()
            );
        });
        player.showEntity(plugin, marker);
        return marker;
    }

    private static ItemStack cameraFlashMarker(int rgb, double strength) {
        return technicalMarker(packCameraFlashColor(rgb, strength));
    }

    static int packCameraFlashColor(int rgb, double strength) {
        int red4 = (int) Math.round((rgb >> 16 & 0xFF) * 15.0 / 255.0);
        int green4 = (int) Math.round((rgb >> 8 & 0xFF) * 15.0 / 255.0);
        int blue4 = (int) Math.round((rgb & 0xFF) * 15.0 / 255.0);
        int power7 = (int) Math.round(Math.max(0.0, Math.min(1.0, strength)) * 127.0);
        return FLASH_SIGNATURE << 20
            | power7 << 13
            | red4 << 9
            | green4 << 5
            | blue4 << 1
            | 1;
    }

    static boolean isPackedCameraFlash(int rgb) {
        return rgb >>> 20 == FLASH_SIGNATURE && (rgb & 1) == 1;
    }

    static int packOffscreenLightColor(int rgb, int lightLevel, int mode) {
        double intensity = Math.max(0, Math.min(15, lightLevel)) / 15.0;
        int red4 = (int) Math.round((rgb >> 16 & 0xFF) * intensity * 15.0 / 255.0);
        int green4 = (int) Math.round((rgb >> 8 & 0xFF) * intensity * 15.0 / 255.0);
        int blue4 = (int) Math.round((rgb & 0xFF) * intensity * 15.0 / 255.0);
        return OFFSCREEN_LIGHT_SIGNATURE << 21
            | (mode & 7) << 18
            | DEFAULT_PROJECTION_CODE << 14
            | red4 << 10
            | green4 << 6
            | blue4 << 2
            | 2;
    }

    static boolean isPackedOffscreenLight(int rgb) {
        return rgb >>> 21 == OFFSCREEN_LIGHT_SIGNATURE && (rgb & 3) == 2;
    }

    static boolean meetsFlashViewRequirement(double viewDot, BlindnessLevel level) {
        return viewDot >= level.viewCosine();
    }

    private boolean sourceBlockedForPlayer(Player player, Location source) {
        Location eye = player.getEyeLocation();
        if (source.getWorld() == null || eye.getWorld() != source.getWorld()) {
            return true;
        }
        Vector toLight = source.toVector().subtract(eye.toVector());
        double distance = toLight.length();
        return distance > 0.75 && blockedByGeometry(
            eye,
            toLight.multiply(1.0 / distance),
            distance
        );
    }

    private boolean blockedByGeometry(Location eye, Vector direction, double distance) {
        World world = eye.getWorld();
        Vector rayDirection = direction.clone().normalize();
        Location rayStart = eye.clone();
        double remaining = Math.max(0.0, distance - 0.05);

        while (remaining > 1.0e-6) {
            RayTraceResult hit = world.rayTraceBlocks(
                rayStart,
                rayDirection,
                remaining,
                FluidCollisionMode.NEVER,
                true
            );
            if (hit == null || hit.getHitBlock() == null) {
                return false;
            }
            Block block = hit.getHitBlock();
            if (!letsLightThrough(block.getType())) {
                return true;
            }

            Vector hitPosition = hit.getHitPosition();
            double distanceToHit = Math.max(
                0.0,
                hitPosition.clone().subtract(rayStart.toVector()).dot(rayDirection)
            );
            double distanceToExit = distanceToExitBlock(
                hitPosition,
                rayDirection,
                block
            );
            double advance = Math.max(0.01, distanceToExit + 0.01);
            remaining -= distanceToHit + advance;
            rayStart = hitPosition.toLocation(world).add(
                rayDirection.clone().multiply(advance)
            );
        }
        return false;
    }

    static boolean letsLightThrough(Material material) {
        String name = material.name();
        return name.equals("GLASS")
            || name.endsWith("_GLASS")
            || name.equals("GLASS_PANE")
            || name.endsWith("_GLASS_PANE");
    }

    private static double distanceToExitBlock(
        Vector position,
        Vector direction,
        Block block
    ) {
        double exit = Double.POSITIVE_INFINITY;
        exit = nearestPositive(exit, axisExitDistance(
            position.getX(), direction.getX(), block.getX()
        ));
        exit = nearestPositive(exit, axisExitDistance(
            position.getY(), direction.getY(), block.getY()
        ));
        exit = nearestPositive(exit, axisExitDistance(
            position.getZ(), direction.getZ(), block.getZ()
        ));
        return Double.isFinite(exit) ? Math.max(0.0, exit) : 0.0;
    }

    private static double axisExitDistance(double position, double direction, int blockAxis) {
        if (direction > 1.0e-9) {
            return (blockAxis + 1.0 - position) / direction;
        }
        if (direction < -1.0e-9) {
            return (blockAxis - position) / direction;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static double nearestPositive(double current, double candidate) {
        return candidate >= 0.0 && candidate < current ? candidate : current;
    }

    private void removeOrphanedDisplays() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(entityKey, PersistentDataType.STRING)) {
                    entity.remove();
                } else if (entity.getPersistentDataContainer().has(
                    proxyEntityKey,
                    PersistentDataType.STRING
                )) {
                    entity.remove();
                } else if (entity.getPersistentDataContainer().has(
                    flashEntityKey,
                    PersistentDataType.STRING
                )) {
                    entity.remove();
                } else if (entity.getPersistentDataContainer().has(
                    sceneFlashEntityKey,
                    PersistentDataType.STRING
                )) {
                    entity.remove();
                } else if (entity.getPersistentDataContainer().has(
                    discoveryEntityKey,
                    PersistentDataType.STRING
                )) {
                    entity.remove();
                } else if (entity.getPersistentDataContainer().has(
                    editorEntityKey,
                    PersistentDataType.STRING
                )) {
                    entity.remove();
                } else if (entity.getPersistentDataContainer().has(
                    legacyEntityKey,
                    PersistentDataType.STRING
                )) {
                    // Cleans the visible cube/star entities from the rejected 0.1 test build.
                    entity.remove();
                }
            }
        }
    }

    private void removeLegacyLightBlocks() {
        for (Strobe strobe : strobes.values()) {
            if (!strobe.placed() || strobe.world() == null) {
                continue;
            }
            Block block = strobe.world().getBlockAt(
                strobe.blockX(), strobe.blockY(), strobe.blockZ()
            );
            if (block.getType() == Material.LIGHT) {
                block.setType(Material.AIR, false);
            }
        }
    }

    private void save() {
        repository.save(strobes);
    }

    private static final class RuntimeState {
        private ItemDisplay marker;
        private ItemDisplay editorHandle;
        private Location editorAnchor;
        private final Map<UUID, ItemDisplay> discoveryLights = new LinkedHashMap<>();
        private final Set<UUID> sourceHidden = new HashSet<>();
        private final Set<UUID> editorVisible = new HashSet<>();
        private Block vanillaLight;
        private Material originalAir = Material.AIR;
        private boolean lit;
        private int ticksUntilToggle;
        private int pulseTicks;
        private boolean failureLogged;

        private boolean valid() {
            return marker != null && marker.isValid() && !marker.isDead();
        }

        private void remove() {
            clearVanillaLight();
            clearDiscoveryLights();
            sourceHidden.clear();
            editorVisible.clear();
            if (marker != null) {
                marker.remove();
                marker = null;
            }
            if (editorHandle != null) {
                editorHandle.remove();
                editorHandle = null;
            }
            editorAnchor = null;
        }

        private void clearVanillaLight() {
            if (vanillaLight != null && vanillaLight.getType() == Material.LIGHT) {
                vanillaLight.setType(originalAir.isAir() ? originalAir : Material.AIR, false);
            }
            vanillaLight = null;
            originalAir = Material.AIR;
        }

        private void removeDiscoveryLight(UUID playerId) {
            ItemDisplay light = discoveryLights.remove(playerId);
            if (light != null) {
                light.remove();
            }
        }

        private void clearDiscoveryLights() {
            discoveryLights.values().forEach(Entity::remove);
            discoveryLights.clear();
        }
    }

    private record CameraBasis(Vector forward, Vector right, Vector up) {
    }

    static record FixedRenderCarrier(
        double anchorY,
        float translationY,
        float displayWidth,
        float displayHeight,
        float viewRange
    ) {
    }

    private static final class SceneFlash {
        private final Location location;
        private final Set<UUID> sourceViewers = new HashSet<>();
        private int remainingTicks;
        private ItemDisplay source;
        private Block vanillaLight;
        private Material originalAir = Material.AIR;

        private SceneFlash(Location location, int remainingTicks) {
            this.location = location.clone();
            this.remainingTicks = remainingTicks;
        }

        private void showSource(StrobeLightsPlugin plugin, Player player) {
            if (source != null && source.isValid() && sourceViewers.add(player.getUniqueId())) {
                player.showEntity(plugin, source);
            }
        }

        private void hideSource(StrobeLightsPlugin plugin, Player player) {
            if (source != null && sourceViewers.remove(player.getUniqueId())) {
                player.hideEntity(plugin, source);
            }
        }

        private void retainSourceViewers(StrobeLightsPlugin plugin, Set<UUID> retained) {
            for (UUID playerId : new HashSet<>(sourceViewers)) {
                if (retained.contains(playerId)) {
                    continue;
                }
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    hideSource(plugin, player);
                } else {
                    sourceViewers.remove(playerId);
                }
            }
        }

        private void remove() {
            sourceViewers.clear();
            if (source != null) {
                source.remove();
                source = null;
            }
            if (vanillaLight != null && vanillaLight.getType() == Material.LIGHT) {
                vanillaLight.setType(originalAir.isAir() ? originalAir : Material.AIR, false);
            }
            vanillaLight = null;
            originalAir = Material.AIR;
        }
    }

    private static final class CameraFlash {
        private ItemDisplay marker;
        private int rgb;
        private double peakStrength;
        private int totalTicks;
        private int remainingTicks;

        private boolean valid() {
            return marker != null && marker.isValid() && !marker.isDead();
        }

        private void remove() {
            if (marker != null) {
                marker.remove();
                marker = null;
            }
        }
    }
}
