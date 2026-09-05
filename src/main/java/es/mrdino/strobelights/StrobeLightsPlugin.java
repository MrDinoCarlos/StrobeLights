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

    private static final int CONFIG_VERSION = 2;

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
        getConfig().set("config-version", CONFIG_VERSION);
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
