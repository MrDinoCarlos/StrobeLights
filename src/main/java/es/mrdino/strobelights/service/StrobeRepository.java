package es.mrdino.strobelights.service;

import es.mrdino.strobelights.StrobeLightsPlugin;
import es.mrdino.strobelights.model.BlindnessLevel;
import es.mrdino.strobelights.model.Strobe;
import es.mrdino.strobelights.model.StrobeMode;
import es.mrdino.strobelights.util.StrobeColors;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.block.BlockFace;

public final class StrobeRepository {

    private final StrobeLightsPlugin plugin;
    private final File file;

    public StrobeRepository(StrobeLightsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "strobes.yml");
    }

    public Map<String, Strobe> load() {
        Map<String, Strobe> result = new LinkedHashMap<>();
        if (!file.isFile()) {
            return result;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("strobes");
        if (root == null) {
            return result;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                String name = section.getString("name", key);
                UUID worldId = UUID.fromString(require(section.getString("world.uuid"), "world.uuid"));
                String worldName = require(section.getString("world.name"), "world.name");
                int rgb = StrobeColors.parse(section.getString("color", "#FFFFFF"))
                    .orElse(Strobe.DEFAULT_COLOR);
                BlindnessLevel blindness = BlindnessLevel.parse(
                    section.getString("blindness", Strobe.DEFAULT_BLINDNESS.name())
                ).orElse(Strobe.DEFAULT_BLINDNESS);
                BlockFace face = parseFace(section.getString("surface-face", "UP"));
                StrobeMode mode = StrobeMode.parse(section.getString("mode", "STROBE"))
                    .orElse(Strobe.DEFAULT_MODE);
                Strobe strobe = new Strobe(
                    name,
                    worldId,
                    worldName,
                    section.getDouble("location.x"),
                    section.getDouble("location.y"),
                    section.getDouble("location.z"),
                    rgb,
                    section.getInt("refresh-ticks", Strobe.DEFAULT_REFRESH_TICKS),
                    section.getInt("light-level", Strobe.DEFAULT_LIGHT_LEVEL),
                    section.getInt("flash-power", Strobe.DEFAULT_FLASH_POWER),
                    blindness,
                    section.getBoolean("enabled", false),
                    face,
                    section.getBoolean("placed", true),
                    mode
                );
                result.put(strobe.key(), strobe);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                    Level.WARNING,
                    "Ignored invalid strobe '" + key + "' from strobes.yml",
                    exception
                );
            }
        }
        return result;
    }

    public void save(Map<String, Strobe> strobes) {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("strobes");
        strobes.values().stream()
            .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
            .forEach(strobe -> write(root.createSection(strobe.key()), strobe));

        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create " + parent);
            return;
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + file, exception);
        }
    }

    private static void write(ConfigurationSection section, Strobe strobe) {
        section.set("name", strobe.name());
        section.set("world.uuid", strobe.worldId().toString());
        section.set("world.name", strobe.worldName());
        section.set("location.x", strobe.x());
        section.set("location.y", strobe.y());
        section.set("location.z", strobe.z());
        section.set("color", StrobeColors.hex(strobe.rgb()));
        section.set("refresh-ticks", strobe.refreshTicks());
        section.set("light-level", strobe.lightLevel());
        section.set("flash-power", strobe.flashPower());
        section.set("blindness", strobe.blindness().name());
        section.set("mode", strobe.mode().name());
        section.set("enabled", strobe.enabled());
        section.set("surface-face", strobe.face().name());
        section.set("placed", strobe.placed());
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value;
    }

    private static BlockFace parseFace(String value) {
        try {
            BlockFace face = BlockFace.valueOf(value.toUpperCase(java.util.Locale.ROOT));
            return face == BlockFace.SELF || face.isCartesian() ? face : BlockFace.UP;
        } catch (IllegalArgumentException exception) {
            return BlockFace.UP;
        }
    }
}
