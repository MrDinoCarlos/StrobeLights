package es.mrdino.strobelights;

import es.mrdino.strobelights.command.StrobeCommand;
import es.mrdino.strobelights.i18n.Messages;
import es.mrdino.strobelights.model.Strobe;
import es.mrdino.strobelights.resourcepack.ResourcePackService;
import es.mrdino.strobelights.service.FlashbangService;
import es.mrdino.strobelights.service.FlareService;
import es.mrdino.strobelights.service.StrobeManager;
import es.mrdino.strobelights.service.StrobeRepository;
import es.mrdino.strobelights.ui.StrobeGui;
import java.util.Map;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class StrobeLightsPlugin extends JavaPlugin {

    private static final int CONFIG_VERSION = 8;

    private StrobeRepository repository;
    private Messages messages;
    private StrobeManager manager;
    private FlashbangService flashbangs;
    private FlareService flares;
    private StrobeGui gui;
    private ResourcePackService resourcePack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfiguration();
        getConfig().options().copyDefaults(true);
        saveConfig();
        messages = new Messages(this);
        messages.load();
        initializeServices();
        resourcePack = new ResourcePackService(this);
        flashbangs = new FlashbangService(this);
        flares = new FlareService(this);

        StrobeCommand commandHandler = new StrobeCommand(this);
        PluginCommand command = Objects.requireNonNull(
            getCommand("strobe"), "The strobe command is missing from plugin.yml"
        );
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        gui = new StrobeGui(this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(resourcePack, this);
        getServer().getPluginManager().registerEvents(flashbangs, this);
        getServer().getPluginManager().registerEvents(flares, this);
        resourcePack.start();

        getLogger().info("StrobeLights ready: " + manager.size()
            + " strobe(s) loaded. Light Painter RGB 3D is active for Fabulous graphics.");
    }

    @Override
    public void onDisable() {
        if (flashbangs != null) {
            flashbangs.shutdown();
        }
        if (flares != null) {
            flares.shutdown();
        }
        if (gui != null) {
            gui.closeAll();
        }
        if (resourcePack != null) {
            resourcePack.stop();
        }
        if (manager != null) {
            manager.shutdown();
        }
    }

    public StrobeManager manager() {
        return manager;
    }

    public StrobeGui gui() {
        return gui;
    }

    public ResourcePackService resourcePack() {
        return resourcePack;
    }

    public FlashbangService flashbangs() {
        return flashbangs;
    }

    public FlareService flares() {
        return flares;
    }

    public Messages messages() {
        return messages;
    }

    public void reloadPlugin() {
        if (resourcePack != null) {
            resourcePack.stop();
        }
        if (manager != null) {
            manager.shutdown();
        }
        reloadConfig();
        migrateConfiguration();
        getConfig().options().copyDefaults(true);
        saveConfig();
        messages.load();
        initializeServices();
        if (resourcePack != null) {
            resourcePack.start();
        }
    }

    private void initializeServices() {
        repository = new StrobeRepository(this);
        Map<String, Strobe> strobes = repository.load();
        manager = new StrobeManager(this, repository, strobes);
        manager.start();
    }

    private void migrateConfiguration() {
        if (getConfig().contains("config-version", true)
            && getConfig().getInt("config-version") >= CONFIG_VERSION) {
            return;
        }
        replaceLegacyInt("flare.trail-particle-count", 3, 2);
        replaceLegacyDouble("flare.trail-particle-size", 1.25, 1.6);
        replaceLegacyInt("flare.explosion.burst-particle-count", 48, 14);
        replaceLegacyDouble("flare.explosion.burst-particle-size", 1.25, 1.1);
        replaceLegacyInt("flare.explosion.burst-duration-ticks", 18, 8);
        replaceLegacyDouble("flare.explosion.burst-speed", 0.32, 0.12);
        replaceLegacyInt("flare.explosion.burn-duration-ticks", 160, 600);
        replaceLegacyInt("flare.explosion.burn-particle-count", 7, 12);
        replaceLegacyDouble("flare.explosion.burn-particle-size", 2.4, 3.5);
        replaceLegacyDouble("flare.explosion.fall-speed", 0.035, 0.012);
        replaceLegacyInt("flare.explosion.scene-light-duration-ticks", 160, 600);

        replaceLegacyInt("flare.load-duration-ticks", 24, 34);
        replaceLegacyInt("flare.fire-cooldown-ticks", 10, 12);
        replaceLegacyDouble("flare.launch-speed", 1.15, 1.7);
        replaceLegacyDouble("flare.vertical-bias", 1.0, 0.65);
        replaceLegacyDouble("flare.minimum-upward-direction", 0.35, 0.25);
        replaceLegacyDouble("flare.launch-height", 32.0, 28.0);
        replaceLegacyDouble("flare.flight-drag", 0.995, 0.99);
        replaceLegacyDouble("flare.flight-gravity", 0.006, 0.012);
        replaceLegacyDouble("flare.launch-sound-volume", 1.5, 4.0);
        replaceLegacyDouble("flare.launch-sound-pitch", 0.9, 1.0);
        replaceLegacyInt("flare.explosion.burn-duration-ticks", 600, 800);
        replaceLegacyDouble("flare.explosion.fall-speed", 0.012, 0.035);
        replaceLegacyDouble("flare.explosion.drift-speed", 0.006, 0.012);
        replaceLegacyDouble("flare.explosion.sway-strength", 0.0025, 0.005);
        replaceLegacyDouble("flare.explosion.sway-frequency", 0.08, 0.09);
        replaceLegacyDouble("flare.explosion.sound-volume", 4.0, 6.0);
        replaceLegacyInt("flare.explosion.scene-light-duration-ticks", 600, 800);
        replaceLegacyDouble("flare.explosion.scene-light-expansion", 2.0, 4.0);
        replaceLegacyDouble("flare.explosion.screen-flash.radius", 64.0, 96.0);
        replaceLegacyDouble("flare.explosion.screen-flash.full-effect-distance", 8.0, 12.0);
        replaceLegacyDouble("flare.explosion.screen-flash.falloff-exponent", 1.1, 0.85);
        replaceLegacyInt("flare.explosion.screen-flash.maximum-duration-ticks", 16, 80);
        replaceLegacyInt("flare.explosion.screen-flash.strength-percent", 55, 135);
        replaceLegacyDouble("flare.launch-sound-volume", 4.0, 3.0);
        replaceLegacyDouble("flare.explosion.sound-volume", 6.0, 3.0);
        replaceLegacyDouble("flare.explosion.screen-flash.radius", 96.0, 72.0);
        replaceLegacyDouble("flare.explosion.screen-flash.full-effect-distance", 12.0, 8.0);
        replaceLegacyDouble("flare.explosion.screen-flash.falloff-exponent", 0.85, 1.05);
        replaceLegacyInt("flare.explosion.screen-flash.maximum-duration-ticks", 80, 50);
        replaceLegacyInt("flare.explosion.screen-flash.strength-percent", 135, 85);
        replaceLegacyDouble("render.display-view-range", 128.0, 192.0);
        replaceLegacyDouble("flare.explosion.scene-view-range", 128.0, 192.0);
        replaceLegacyDouble("flare.visual.view-range", 192.0, 256.0);
        removeRetiredFlareParticleSettings();
        migrateRetiredFlareMotionSettings();
        getConfig().set("config-version", CONFIG_VERSION);
    }

    private void removeRetiredFlareParticleSettings() {
        for (String path : new String[] {
            "flare.trail-points-per-block",
            "flare.trail-particle-count",
            "flare.trail-particle-size",
            "flare.trail-hot-core-count",
            "flare.trail-flame-count",
            "flare.trail-smoke-count",
            "flare.explosion.burst-particle-count",
            "flare.explosion.burst-particle-size",
            "flare.explosion.burst-duration-ticks",
            "flare.explosion.burst-speed",
            "flare.explosion.spark-drag",
            "flare.explosion.spark-gravity",
            "flare.explosion.burn-particle-count",
            "flare.explosion.burn-particle-size",
            "flare.explosion.hot-core-particle-count",
            "flare.explosion.flame-particle-count",
            "flare.explosion.smoke-particle-count"
        }) {
            getConfig().set(path, null);
        }
    }

    private void migrateRetiredFlareMotionSettings() {
        if (getConfig().contains("flare.explosion.fall-speed", true)
            && !getConfig().contains("flare.explosion.terminal-fall-speed", true)) {
            double previous = getConfig().getDouble("flare.explosion.fall-speed");
            getConfig().set(
                "flare.explosion.terminal-fall-speed",
                Math.abs(previous - 0.035) < 1.0e-9 ? 0.06 : previous
            );
        }
        if (getConfig().contains("flare.explosion.drift-speed", true)
            && !getConfig().contains("flare.explosion.minimum-horizontal-speed", true)) {
            double previous = getConfig().getDouble("flare.explosion.drift-speed");
            getConfig().set(
                "flare.explosion.minimum-horizontal-speed",
                Math.abs(previous - 0.012) < 1.0e-9 ? 0.035 : previous
            );
        }
        getConfig().set("flare.explosion.fall-speed", null);
        getConfig().set("flare.explosion.drift-speed", null);
    }

    private void replaceLegacyInt(String path, int previousDefault, int replacement) {
        if (getConfig().contains(path, true) && getConfig().getInt(path) == previousDefault) {
            getConfig().set(path, replacement);
        }
    }

    private void replaceLegacyDouble(
        String path,
        double previousDefault,
        double replacement
    ) {
        if (getConfig().contains(path, true)
            && Math.abs(getConfig().getDouble(path) - previousDefault) < 1.0e-9) {
            getConfig().set(path, replacement);
        }
    }
}
