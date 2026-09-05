package es.mrdino.strobelights.resourcepack;

import es.mrdino.strobelights.StrobeLightsPlugin;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

/** Loads, hosts and sends the Light Painter shader and GUI icon resource pack. */
public final class ResourcePackService implements Listener {

    private static final UUID PACK_ID = UUID.fromString("e9a7e606-b52f-4a18-b4dc-cb1919210411");
    private static final String PACK_REVISION = "0.10.8";
    private static final String DEFAULT_PUBLIC_URL =
        "http://serverip.com:8250/strobelights/{token}.zip";
    private static final String EMBEDDED_PACK =
        "embedded/StrobeLights-ResourcePack-1.21.10.zip";

    private final StrobeLightsPlugin plugin;
    private final Listener nexoPackListener = new Listener() { };
    private final Set<UUID> loadedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> compatibilityNotifiedPlayers = ConcurrentHashMap.newKeySet();
    private EmbeddedPackServer httpServer;
    private byte[] sha1;
    private UUID packId;
    private String publicUrl;
    private boolean required;
    private long sendDelayTicks;
    private boolean active;
    private boolean defaultUrlConfigured;
    private boolean nexoManaged;
    private volatile boolean nexoMerged;
    private boolean nexoListenerRegistered;
    private Plugin nexoPlugin;
    private Path nexoPackPath;

    public ResourcePackService(StrobeLightsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        defaultUrlConfigured = false;
        nexoManaged = false;
        nexoMerged = false;
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) {
            plugin.getLogger().warning(
                "Automatic shader delivery is disabled; an external installation is assumed."
            );
            return;
        }

        byte[] packBytes = readEmbeddedPack();
        sha1 = sha1(packBytes);
        packId = PACK_ID;
        Path exportedPack = exportPack(packBytes);
        required = plugin.getConfig().getBoolean("resource-pack.required", true);
        sendDelayTicks = Math.max(
            0L,
            plugin.getConfig().getLong("resource-pack.send-delay-ticks", 10L)
        );

        if (startNexoIntegration(exportedPack)) {
            active = true;
            plugin.getServer().getOnlinePlayers().forEach(
                player -> plugin.manager().setMarkersVisible(player, true)
            );
            plugin.getLogger().info(
                "Nexo detected: StrobeLights will be merged into Nexo's resource pack."
            );
            return;
        }

        boolean embedded = plugin.getConfig().getBoolean(
            "resource-pack.embedded.enabled",
            true
        );
        String configuredUrl = plugin.getConfig().getString(
            "resource-pack.public-url",
            ""
        ).trim();
        defaultUrlConfigured = isDefaultPublicUrl(configuredUrl);
        if (defaultUrlConfigured) {
            plugin.getServer().getConsoleSender().sendMessage(
                Component.text(
                    plugin.messages().text(
                        plugin.getServer().getConsoleSender(),
                        "resource-pack.unconfigured.console",
                        "url",
                        DEFAULT_PUBLIC_URL
                    ),
                    NamedTextColor.RED
                ).decorate(TextDecoration.BOLD)
            );
        }
        if (embedded) {
            startEmbeddedServer(packBytes, configuredUrl);
        } else {
            if (configuredUrl.isBlank()) {
                throw new IllegalStateException(
                    "resource-pack.public-url is required when embedded.enabled=false"
                );
            }
            publicUrl = configuredUrl;
            validateUrl(publicUrl);
        }

        active = true;
        plugin.getServer().getOnlinePlayers().forEach(this::sendLater);
        plugin.getLogger().info("3D RGB shader available at " + publicUrl);
    }

    public void stop() {
        active = false;
        defaultUrlConfigured = false;
        nexoManaged = false;
        nexoMerged = false;
        nexoPlugin = null;
        nexoPackPath = null;
        loadedPlayers.clear();
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
    }

    public boolean isLoaded(Player player) {
        return nexoManaged || !active || loadedPlayers.contains(player.getUniqueId());
    }

    public String revision() {
        return PACK_REVISION;
    }

    public void resend(Player player) {
        if (!active) {
            return;
        }
        if (nexoManaged) {
            sendNexoPack(player);
            return;
        }
        loadedPlayers.remove(player.getUniqueId());
        plugin.manager().setMarkersVisible(player, false);
        sendLater(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        loadedPlayers.remove(player.getUniqueId());
        compatibilityNotifiedPlayers.remove(player.getUniqueId());
        if (nexoManaged) {
            plugin.manager().setMarkersVisible(player, true);
            return;
        }
        plugin.manager().setMarkersVisible(player, false);
        sendLater(player);
        warnAdministratorLater(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        loadedPlayers.remove(playerId);
        compatibilityNotifiedPlayers.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!active || nexoManaged || !event.getID().equals(packId)) {
            return;
        }
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                loadedPlayers.add(player.getUniqueId());
                plugin.manager().setMarkersVisible(player, true);
                player.sendMessage(Component.text(
                    plugin.messages().text(player, "resource-pack.loaded", "revision", PACK_REVISION),
                    NamedTextColor.GREEN
                ));
                sendCompatibilityNoticeLater(player);
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                loadedPlayers.remove(player.getUniqueId());
                plugin.manager().setMarkersVisible(player, false);
                plugin.getLogger().warning(
                    "Resource pack " + event.getStatus().name().toLowerCase()
                        + " for " + player.getName()
                );
            }
            default -> {
                // ACCEPTED and DOWNLOADED are intermediate states.
            }
        }
    }

    private void sendLater(Player player) {
        if (!active || nexoManaged) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!active || !player.isOnline()) {
                return;
            }
            Component prompt = Component.text(
                plugin.messages().text(player, "resource-pack.prompt", "revision", PACK_REVISION),
                NamedTextColor.AQUA
            );
            player.setResourcePack(packId, publicUrl, sha1, prompt, required);
        }, sendDelayTicks);
    }

    private void sendCompatibilityNoticeLater(Player player) {
        if (!plugin.getConfig().getBoolean("client-compatibility-notices.enabled", false)) {
            return;
        }
        long delayTicks = Math.max(0L, Math.min(
            1_200L,
            plugin.getConfig().getLong("client-compatibility-notices.delay-ticks", 20L)
        ));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            UUID playerId = player.getUniqueId();
            if (!active
                || !player.isOnline()
                || !loadedPlayers.contains(playerId)
                || !compatibilityNotifiedPlayers.add(playerId)) {
                return;
            }

            boolean optiFine = isOptiFineBrand(player.getClientBrandName());
            String key = optiFine
                ? "resource-pack.compatibility.optifine"
                : "resource-pack.compatibility.fabulous-hint";
            NamedTextColor accent = optiFine ? NamedTextColor.RED : NamedTextColor.LIGHT_PURPLE;
            NamedTextColor messageColor = optiFine ? NamedTextColor.YELLOW : NamedTextColor.AQUA;
            player.sendMessage(
                Component.text("⚡ STROBELIGHTS", accent)
                    .decorate(TextDecoration.BOLD)
                    .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(plugin.messages().text(player, key), messageColor))
            );
        }, delayTicks);
    }

    static boolean isOptiFineBrand(String brand) {
        if (brand == null || brand.isBlank()) {
            return false;
        }
        String normalized = brand.toLowerCase(Locale.ROOT);
        return normalized.contains("optifine") || normalized.contains("optifabric");
    }

    private void warnAdministratorLater(Player player) {
        if (!defaultUrlConfigured || !player.hasPermission("strobelights.admin")) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!active
                || !defaultUrlConfigured
                || !player.isOnline()
                || !player.hasPermission("strobelights.admin")) {
                return;
            }
            player.showTitle(Title.title(
                Component.text(
                    plugin.messages().text(
                        player,
                        "resource-pack.unconfigured.title"
                    ),
                    NamedTextColor.RED
                ).decorate(TextDecoration.BOLD),
                Component.text(
                    plugin.messages().text(
                        player,
                        "resource-pack.unconfigured.subtitle"
                    ),
                    NamedTextColor.YELLOW
                ),
                Title.Times.times(
                    Duration.ofMillis(400),
                    Duration.ofSeconds(6),
                    Duration.ofMillis(800)
                )
            ));
        }, 1L);
    }

    private void startEmbeddedServer(byte[] packBytes, String configuredUrl) {
        int port = Math.max(
            1,
            Math.min(65_535, plugin.getConfig().getInt("resource-pack.embedded.port", 8124))
        );
        if (port == plugin.getServer().getPort()) {
            throw new IllegalStateException(
                "resource-pack.embedded.port must differ from the Minecraft port"
            );
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        String path = "/strobelights/" + token + ".zip";
        if (configuredUrl.isBlank()) {
            publicUrl = "http://" + urlHost(automaticHost()) + ":" + port + path;
        } else {
            publicUrl = configuredUrl.replace("{token}", token);
            URI configured = validateUrl(publicUrl);
            path = configured.getRawPath();
            if (path == null || path.isBlank() || path.equals("/")) {
                throw new IllegalStateException("resource-pack.public-url requires a ZIP path");
            }
        }

        String bindValue = plugin.getConfig().getString(
            "resource-pack.embedded.bind-address",
            "0.0.0.0"
        ).trim();
        try {
            InetAddress bind = InetAddress.getByName(bindValue.isBlank() ? "0.0.0.0" : bindValue);
            httpServer = new EmbeddedPackServer(
                new InetSocketAddress(bind, port),
                path,
                packBytes,
                plugin.getLogger()
            );
            httpServer.start();
        } catch (BindException exception) {
            throw new IllegalStateException(
                "HTTP port " + port + " is already in use; change resource-pack.embedded.port",
                exception
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start the shader server", exception);
        }
    }

    private byte[] readEmbeddedPack() {
        try (InputStream input = plugin.getResource(EMBEDDED_PACK)) {
            if (input == null) {
                throw new IllegalStateException("Missing " + EMBEDDED_PACK + " inside the JAR");
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the embedded shader", exception);
        }
    }

    private Path exportPack(byte[] bytes) {
        try {
            Path directory = plugin.getDataFolder().toPath().resolve("resource-pack");
            Files.createDirectories(directory);
            return Files.write(
                directory.resolve("StrobeLights-ResourcePack-1.21.10.zip"),
                bytes
            );
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not export a copy of the resource pack: "
                + exception.getMessage());
            return null;
        }
    }

    private boolean startNexoIntegration(Path exportedPack) {
        if (!plugin.getConfig().getBoolean("resource-pack.nexo-integration.enabled", true)) {
            return false;
        }
        Plugin detected = plugin.getServer().getPluginManager().getPlugin("Nexo");
        if (detected == null || !detected.isEnabled()) {
            return false;
        }
        if (exportedPack == null) {
            plugin.getLogger().warning(
                "Nexo integration needs the exported StrobeLights resource-pack ZIP; "
                    + "using standalone delivery instead."
            );
            return false;
        }

        try {
            ClassLoader loader = detected.getClass().getClassLoader();
            Class<? extends Event> eventType = Class.forName(
                "com.nexomc.nexo.api.events.resourcepack.NexoPostPackGenerateEvent",
                true,
                loader
            ).asSubclass(Event.class);

            nexoPlugin = detected;
            nexoPackPath = exportedPack;
            nexoManaged = true;
            if (!nexoListenerRegistered) {
                plugin.getServer().getPluginManager().registerEvent(
                    eventType,
                    nexoPackListener,
                    EventPriority.HIGHEST,
                    (listener, event) -> mergeIntoNexo(event),
                    plugin,
                    true
                );
                nexoListenerRegistered = true;
            }
            scheduleNexoRegenerationFallback();
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            nexoManaged = false;
            nexoPlugin = null;
            nexoPackPath = null;
            plugin.getLogger().warning(
                "Nexo was detected but its pack integration API is unavailable ("
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage()
                    + "). StrobeLights will use standalone delivery."
            );
            return false;
        }
    }

    private void mergeIntoNexo(Event event) {
        Path packPath = nexoPackPath;
        if (!active || !nexoManaged || packPath == null) {
            return;
        }
        try {
            Method addResourcePack = event.getClass().getMethod("addResourcePack", File.class);
            Object result = addResourcePack.invoke(event, packPath.toFile());
            if (Boolean.FALSE.equals(result)) {
                plugin.getLogger().warning(
                    "Nexo rejected the StrobeLights resource-pack ZIP: " + packPath
                );
                return;
            }
            nexoMerged = true;
            plugin.getLogger().info(
                "StrobeLights shaders and models were added to Nexo's generated pack."
            );
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning(
                "Could not add StrobeLights to Nexo's generated pack: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }
    }

    private void scheduleNexoRegenerationFallback() {
        long delay = Math.max(1L, Math.min(
            1_200L,
            plugin.getConfig().getLong(
                "resource-pack.nexo-integration.regeneration-delay-ticks",
                40L
            )
        ));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!active || !nexoManaged || nexoMerged || nexoPlugin == null) {
                return;
            }
            try {
                Object generator = nexoPlugin.getClass()
                    .getMethod("packGenerator")
                    .invoke(nexoPlugin);
                generator.getClass().getMethod("regeneratePack").invoke(generator);
                plugin.getLogger().info(
                    "Requested a Nexo pack regeneration so StrobeLights can be merged."
                );
            } catch (ReflectiveOperationException | LinkageError exception) {
                plugin.getLogger().warning(
                    "Could not request Nexo pack regeneration: "
                        + exception.getClass().getSimpleName() + ": " + exception.getMessage()
                );
            }
        }, delay);
    }

    private void sendNexoPack(Player player) {
        Plugin detected = nexoPlugin;
        if (detected == null || !detected.isEnabled()) {
            plugin.getLogger().warning(
                "Cannot resend the resource pack because Nexo is not enabled."
            );
            return;
        }
        try {
            Class<?> nexoPack = Class.forName(
                "com.nexomc.nexo.api.NexoPack",
                true,
                detected.getClass().getClassLoader()
            );
            nexoPack.getMethod("sendPack", Player.class).invoke(null, player);
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning(
                "Could not ask Nexo to resend its pack to " + player.getName() + ": "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }
    }

    private String automaticHost() {
        String configuredServerIp = plugin.getServer().getIp();
        if (configuredServerIp != null
            && !configuredServerIp.isBlank()
            && !configuredServerIp.equals("0.0.0.0")) {
            return configuredServerIp;
        }
        try {
            List<InetAddress> addresses = NetworkInterface.networkInterfaces()
                .filter(network -> {
                    try {
                        return network.isUp() && !network.isLoopback() && !network.isVirtual();
                    } catch (IOException ignored) {
                        return false;
                    }
                })
                .flatMap(NetworkInterface::inetAddresses)
                .filter(address -> address instanceof Inet4Address)
                .filter(InetAddress::isSiteLocalAddress)
                .sorted(Comparator.comparing(InetAddress::getHostAddress))
                .toList();
            if (!addresses.isEmpty()) {
                return addresses.getFirst().getHostAddress();
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not detect the LAN IP: " + exception.getMessage());
        }
        return "127.0.0.1";
    }

    private static URI validateUrl(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getHost() == null
                || !(uri.getScheme().equalsIgnoreCase("http")
                    || uri.getScheme().equalsIgnoreCase("https"))) {
                throw new IllegalStateException("The resource-pack URL must use HTTP(S)");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid resource-pack URL", exception);
        }
    }

    static boolean isDefaultPublicUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(value.trim());
            return uri.getHost() != null
                && uri.getHost().equalsIgnoreCase("serverip.com");
        } catch (URISyntaxException ignored) {
            return value.toLowerCase(java.util.Locale.ROOT).contains("serverip.com");
        }
    }

    private static byte[] sha1(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private static String urlHost(String host) {
        return host.contains(":") ? "[" + host + "]" : host;
    }
}
