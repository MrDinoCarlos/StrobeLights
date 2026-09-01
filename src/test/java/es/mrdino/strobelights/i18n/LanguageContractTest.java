package es.mrdino.strobelights.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LanguageContractTest {

    private static final Path LANGUAGES = Path.of("src/main/resources/lang");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z-]+}");
    private static final Pattern SOURCE_KEY = Pattern.compile(
        "\"((?:(?:gui|item|level|message|state|position|command)\\.[a-z0-9.-]+"
            + "|resource-pack\\.(?:loaded|prompt|compatibility\\.[a-z0-9.-]+"
            + "|unconfigured\\.[a-z0-9.-]+)))\""
    );

    @Test
    void everyBundledLanguageContainsTheCompleteEnglishKeySet() {
        Set<String> english = leafKeys(load("en"));
        assertFalse(english.isEmpty());

        for (String language : new String[] {"es", "fr", "de", "it"}) {
            YamlConfiguration translated = load(language);
            assertEquals(english, leafKeys(translated), language + " has mismatched keys");
            for (String key : english) {
                assertEquals(
                    placeholders(load("en").getString(key, "")),
                    placeholders(translated.getString(key, "")),
                    language + " has mismatched placeholders at " + key
                );
            }
        }
    }

    @Test
    void everyStaticSourceTranslationKeyExistsInEnglish() throws IOException {
        YamlConfiguration english = load("en");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = SOURCE_KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    assertTrue(english.contains(matcher.group(1)),
                        () -> "Missing English key " + matcher.group(1) + " referenced by " + file);
                }
            }
        }
    }

    @Test
    void upgradesOldLanguageFilesWithoutOverwritingCustomText() {
        YamlConfiguration installed = new YamlConfiguration();
        installed.set("item.flashbang.name", "My custom name");
        YamlConfiguration bundled = load("en");

        assertTrue(Messages.mergeMissingLeaves(installed, bundled));
        assertEquals("My custom name", installed.getString("item.flashbang.name"));
        assertEquals(
            bundled.getString("item.flashbang.lore-4"),
            installed.getString("item.flashbang.lore-4")
        );
        assertEquals(leafKeys(bundled), leafKeys(installed));
        assertFalse(Messages.mergeMissingLeaves(installed, bundled));
    }

    @Test
    void appliesChangedBundledTranslationsButPreservesCustomizedValues() {
        YamlConfiguration previous = new YamlConfiguration();
        previous.set("message.updated", "Old bundled text");
        previous.set("message.custom", "Old customizable text");

        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("message.updated", "New bundled text");
        bundled.set("message.custom", "New customizable text");
        bundled.set("message.added", "New key");

        YamlConfiguration installed = new YamlConfiguration();
        installed.set("message.updated", "Old bundled text");
        installed.set("message.custom", "Server owner's custom text");

        assertTrue(Messages.mergeBundledChanges(installed, bundled, previous));
        assertEquals("New bundled text", installed.getString("message.updated"));
        assertEquals(
            "Server owner's custom text",
            installed.getString("message.custom")
        );
        assertEquals("New key", installed.getString("message.added"));
        assertFalse(Messages.mergeBundledChanges(installed, bundled, bundled));
    }

    private static YamlConfiguration load(String language) {
        File file = LANGUAGES.resolve(language + ".yml").toFile();
        return YamlConfiguration.loadConfiguration(file);
    }

    private static Set<String> leafKeys(YamlConfiguration yaml) {
        Set<String> result = new LinkedHashSet<>();
        for (String key : yaml.getKeys(true)) {
            if (!(yaml.get(key) instanceof ConfigurationSection)) {
                result.add(key);
            }
        }
        return result;
    }

    private static Set<String> placeholders(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }
}
