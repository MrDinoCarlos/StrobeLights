package es.mrdino.strobelights.resourcepack;

import es.mrdino.strobelights.StrobeLightsPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/** File-contract integration for the documented Nexo 1.27+ MultiPack system. */
final class NexoMultipackInstaller {

    private static final Pattern VERSION = Pattern.compile("(?i).*?(\\d+)\\.(\\d+).*");

    private final StrobeLightsPlugin plugin;
    private final Path nexoDataDirectory;
    private final Path templateDirectory;
    private final Path configurationFile;
    private final String templateId;
    private final String packName;

    NexoMultipackInstaller(StrobeLightsPlugin plugin, Plugin nexo) {
        this.plugin = plugin;
        this.nexoDataDirectory = nexo.getDataFolder().toPath().toAbsolutePath().normalize();
        this.templateDirectory = nexoDataDirectory.resolve("pack/template_packs").normalize();
        this.configurationFile = nexoDataDirectory.resolve("multipack.yml").normalize();
        this.templateId = configuredTemplateId(plugin);
        String configuredName = plugin.getConfig().getString(
            "resource-pack.nexo-integration.multipack.pack-name",
            "StrobeLights.zip"
        );
        this.packName = configuredName != null
            && configuredName.matches("[A-Za-z0-9._-]+\\.zip")
            ? configuredName : "StrobeLights.zip";
    }

    static boolean supports(Plugin nexo) {
        if (nexo == null || nexo.getDescription() == null) {
            return false;
        }
        return supportsVersion(nexo.getDescription().getVersion());
    }

    static boolean supportsVersion(String version) {
        if (version == null) {
            return false;
        }
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) {
            return false;
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            return major > 1 || major == 1 && minor >= 27;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    InstallResult install(byte[] packBytes) throws IOException {
        Files.createDirectories(templateDirectory);
        Path target = templateDirectory.resolve(packName).normalize();
        requireDirectChild(templateDirectory, target);
        boolean packChanged = !Files.isRegularFile(target)
            || !Arrays.equals(sha256(packBytes), sha256(Files.readAllBytes(target)));
        if (packChanged) {
            Path temporary = Files.createTempFile(templateDirectory, ".strobelights-", ".tmp");
            try {
                Files.write(temporary, packBytes);
                atomicReplace(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        boolean configurationChanged = registerTemplate();
        return new InstallResult(target, packChanged || configurationChanged);
    }

    boolean removeManaged() throws IOException {
        boolean changed = Files.deleteIfExists(templateDirectory.resolve(packName));
        if (!Files.isRegularFile(configurationFile)) {
            return changed;
        }
        YamlConfiguration configuration = loadConfiguration();
        String root = "templates." + templateId;
        if (packName.equals(configuration.getString(root + ".path"))) {
            configuration.set(root, null);
            saveConfiguration(configuration);
            changed = true;
        }
        return changed;
    }

    private boolean registerTemplate() throws IOException {
        YamlConfiguration configuration = loadConfiguration();
        String root = "templates." + templateId;
        boolean required = plugin.getConfig().getBoolean(
            "resource-pack.nexo-integration.multipack.required", true
        );
        boolean defaultEnabled = plugin.getConfig().getBoolean(
            "resource-pack.nexo-integration.multipack.default", true
        );
        boolean changed = !configuration.isConfigurationSection(root)
            || configuration.getBoolean(root + ".required") != required
            || configuration.getBoolean(root + ".default") != defaultEnabled
            || !packName.equals(configuration.getString(root + ".path"));
        if (!changed) {
            return false;
        }
        configuration.set(root + ".required", required);
        configuration.set(root + ".default", defaultEnabled);
        configuration.set(root + ".conflicts_with", null);
        configuration.set(root + ".path", packName);
        saveConfiguration(configuration);
        return true;
    }

    private YamlConfiguration loadConfiguration() throws IOException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        if (!Files.exists(configurationFile)) {
            return configuration;
        }
        try {
            configuration.load(configurationFile.toFile());
            return configuration;
        } catch (InvalidConfigurationException exception) {
            throw new IOException(
                "Nexo multipack.yml is invalid and was not modified: " + exception.getMessage(),
                exception
            );
        }
    }

    private void saveConfiguration(YamlConfiguration configuration) throws IOException {
        Files.createDirectories(nexoDataDirectory);
        Path temporary = Files.createTempFile(nexoDataDirectory, ".strobelights-multipack-", ".tmp");
        try {
            Files.writeString(
                temporary, configuration.saveToString(), StandardCharsets.UTF_8
            );
            atomicReplace(temporary, configurationFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void atomicReplace(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String configuredTemplateId(StrobeLightsPlugin plugin) {
        String configured = plugin.getConfig().getString(
            "resource-pack.nexo-integration.multipack.template-id", "strobelights"
        );
        String value = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9_-]+")) {
            plugin.getLogger().warning(
                "Invalid StrobeLights Nexo MultiPack template-id; using strobelights."
            );
            return "strobelights";
        }
        return value;
    }

    private static void requireDirectChild(Path directory, Path target) throws IOException {
        if (!target.toAbsolutePath().normalize().getParent().equals(
            directory.toAbsolutePath().normalize()
        )) {
            throw new IOException("Unsafe Nexo template-pack path");
        }
    }

    record InstallResult(Path path, boolean changed) {
    }
}
