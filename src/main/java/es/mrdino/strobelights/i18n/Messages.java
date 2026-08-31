package es.mrdino.strobelights.i18n;

import es.mrdino.strobelights.StrobeLightsPlugin;
import es.mrdino.strobelights.model.BlindnessLevel;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Player-locale translations with English as the complete fallback language. */
public final class Messages {

    private static final Set<String> SUPPORTED = Set.of("en", "es", "fr", "de", "it");

    private final StrobeLightsPlugin plugin;
    private final Map<String, YamlConfiguration> languages = new LinkedHashMap<>();
    private String defaultLanguage = "en";
    private boolean useClientLocale = true;

    public Messages(StrobeLightsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        defaultLanguage = normalize(plugin.getConfig().getString("language.default", "en"));
        useClientLocale = plugin.getConfig().getBoolean("language.use-client-locale", true);
        languages.clear();
        for (String language : SUPPORTED) {
            String resource = "lang/" + language + ".yml";
            File target = new File(plugin.getDataFolder(), resource);
            if (!target.isFile()) {
                plugin.saveResource(resource, false);
            }
            YamlConfiguration installed = YamlConfiguration.loadConfiguration(target);
            YamlConfiguration bundled = loadBundled(resource);
            File baselineFile = new File(
                plugin.getDataFolder(),
                "lang/.defaults/" + language + ".yml"
            );
            YamlConfiguration previousBundled = baselineFile.isFile()
                ? YamlConfiguration.loadConfiguration(baselineFile)
                : null;
            boolean changed = mergeBundledChanges(
                installed,
                bundled,
                previousBundled
            );
            if (changed) {
                try {
                    installed.save(target);
                    plugin.getLogger().info(
                        "Updated bundled translation changes in " + resource
                    );
                } catch (IOException exception) {
                    plugin.getLogger().warning(
                        "Could not update " + resource + ": " + exception.getMessage()
                    );
                }
            }
            updateBundledBaseline(resource, baselineFile, bundled, previousBundled);
            languages.put(language, installed);
        }
    }

    private void updateBundledBaseline(
        String resource,
        File baselineFile,
        YamlConfiguration bundled,
        YamlConfiguration previousBundled
    ) {
        if (previousBundled != null
            && previousBundled.saveToString().equals(bundled.saveToString())) {
            return;
        }
        File parent = baselineFile.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            plugin.getLogger().warning(
                "Could not create translation baseline directory for " + resource
            );
            return;
        }
        try {
            bundled.save(baselineFile);
        } catch (IOException exception) {
            plugin.getLogger().warning(
                "Could not store translation baseline for " + resource + ": "
                    + exception.getMessage()
            );
        }
    }

    private YamlConfiguration loadBundled(String resource) {
        InputStream stream = plugin.getResource(resource);
        if (stream == null) {
            throw new IllegalStateException("Missing bundled language resource " + resource);
        }
        try (InputStreamReader reader = new InputStreamReader(
            stream,
            StandardCharsets.UTF_8
        )) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Could not read bundled language resource " + resource,
                exception
            );
        }
    }

    static boolean mergeMissingLeaves(
        YamlConfiguration installed,
        YamlConfiguration bundled
    ) {
        return mergeBundledChanges(installed, bundled, null);
    }

    static boolean mergeBundledChanges(
        YamlConfiguration installed,
        YamlConfiguration bundled,
        YamlConfiguration previousBundled
    ) {
        boolean changed = false;
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key)) {
                continue;
            }
            Object bundledValue = bundled.get(key);
            if (!installed.contains(key)) {
                installed.set(key, bundledValue);
                changed = true;
                continue;
            }
            if (previousBundled != null
                && previousBundled.contains(key)
                && Objects.equals(installed.get(key), previousBundled.get(key))
                && !Objects.equals(installed.get(key), bundledValue)) {
                installed.set(key, bundledValue);
                changed = true;
            }
        }
        return changed;
    }

    public String text(CommandSender sender, String key, Object... replacements) {
        return text(language(sender), key, replacements);
    }

    public String text(String language, String key, Object... replacements) {
        YamlConfiguration selected = languages.getOrDefault(normalize(language), languages.get("en"));
        String value = selected == null ? null : selected.getString(key);
        if (value == null) {
            YamlConfiguration english = languages.get("en");
            value = english == null ? null : english.getString(key);
        }
        if (value == null) {
            plugin.getLogger().warning("Missing language key: " + key);
            value = key;
        }
        if (replacements.length % 2 != 0) {
            throw new IllegalArgumentException("Translation replacements must be key/value pairs");
        }
        for (int index = 0; index < replacements.length; index += 2) {
            value = value.replace(
                "{" + replacements[index] + "}",
                String.valueOf(replacements[index + 1])
            );
        }
        return value;
    }

    public String blindness(CommandSender sender, BlindnessLevel level) {
        return text(sender, "level." + level.name().toLowerCase(Locale.ROOT));
    }

    public String language(CommandSender sender) {
        if (useClientLocale && sender instanceof Player player) {
            return normalize(player.getLocale());
        }
        return defaultLanguage;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "en";
        }
        String language = value.toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        return SUPPORTED.contains(language) ? language : "en";
    }
}
