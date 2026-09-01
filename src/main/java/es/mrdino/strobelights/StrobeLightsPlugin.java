package es.mrdino.strobelights;

import es.mrdino.strobelights.command.StrobeCommand;
import es.mrdino.strobelights.i18n.Messages;
import es.mrdino.strobelights.model.Strobe;
import es.mrdino.strobelights.resourcepack.ResourcePackService;
import es.mrdino.strobelights.service.FlashbangService;
import es.mrdino.strobelights.service.StrobeManager;
import es.mrdino.strobelights.service.StrobeRepository;
import es.mrdino.strobelights.ui.StrobeGui;
import java.util.Map;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class StrobeLightsPlugin extends JavaPlugin {

    private StrobeRepository repository;
    private Messages messages;
    private StrobeManager manager;
    private FlashbangService flashbangs;
    private StrobeGui gui;
    private ResourcePackService resourcePack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        messages = new Messages(this);
        messages.load();
        initializeServices();
        resourcePack = new ResourcePackService(this);
        flashbangs = new FlashbangService(this);

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
        resourcePack.start();

        getLogger().info("StrobeLights ready: " + manager.size()
            + " strobe(s) loaded. Light Painter RGB 3D is active for Fabulous graphics.");
    }

    @Override
    public void onDisable() {
        if (flashbangs != null) {
            flashbangs.shutdown();
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
}
