package es.mrdino.strobelights.resourcepack;

import es.mrdino.strobelights.StrobeLightsPlugin;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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

    private static final String PACK_REVISION = "0.10.23";
    private static final String DEFAULT_PUBLIC_URL =
        "http://serverip.com:8250/strobelights/{token}.zip";
    private static final String EMBEDDED_PACK =
        "embedded/StrobeLights-ResourcePack-26.1.2.zip";

    private final StrobeLightsPlugin plugin;
    private final Listener nexoPackListener = new Listener() { };
    private final Set<UUID> loadedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> deliveryAttemptedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> compatibilityNotifiedPlayers = ConcurrentHashMap.newKeySet();
    private EmbeddedPackServer httpServer;
    private Plugin trpResourcePackHost;
    private boolean trpManaged;
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
    private boolean nexoRegenerationRequested;
    private Plugin nexoPlugin;
    private Path nexoPackPath;
    private byte[] nexoSourcePackBytes;
    private Object nexoPackServerDelegate;
    private Object nexoPackServerProxy;

    public ResourcePackService(StrobeLightsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        defaultUrlConfigured = false;
        trpManaged = false;
        nexoManaged = false;
        nexoMerged = false;
        nexoRegenerationRequested = false;
        nexoSourcePackBytes = null;
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) {
            plugin.getLogger().warning(
                "Automatic shader delivery is disabled; an external installation is assumed."
            );
            return;
        }

        byte[] packBytes = readEmbeddedPack();
        sha1 = sha1(packBytes);
        packId = UUID.nameUUIDFromBytes(sha1);
        Path exportedPack = exportPack(packBytes);
        required = plugin.getConfig().getBoolean("resource-pack.required", true);
        sendDelayTicks = Math.max(
            0L,
            plugin.getConfig().getLong("resource-pack.send-delay-ticks", 10L)
        );

        byte[] trpCompatiblePack = hasEnabledNexoIntegration()
            ? combineWithTrp(packBytes) : null;
        byte[] nexoSourcePack = trpCompatiblePack == null ? packBytes : trpCompatiblePack;
        Path nexoSourcePath = trpCompatiblePack == null
            ? exportedPack
            : exportPack(
                trpCompatiblePack,
                "StrobeLights-TRP-Combined-" + PACK_REVISION + ".zip"
            );
        if (startNexoIntegration(nexoSourcePath, nexoSourcePack)) {
            active = true;
            plugin.getLogger().info(
                trpCompatiblePack == null
                    ? "Nexo detected: StrobeLights will be merged into Nexo's resource pack."
                    : "Nexo detected: the verified TRP + StrobeLights pack will be merged "
                        + "into Nexo's final client ZIP."
            );
            return;
        }

        startStandaloneDelivery(packBytes);
    }

    private void startStandaloneDelivery(byte[] packBytes) {
        nexoManaged = false;
        closeEmbeddedServer();
        if (plugin.getConfig().getBoolean(
            "resource-pack.embedded.reuse-trp-server", true
        ) && hostWithTrp(packBytes)) {
            defaultUrlConfigured = false;
            active = true;
            plugin.getLogger().info("3D RGB shader is active in TRP's combined resource pack.");
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
        if (embedded) {
            byte[] combined = combineWithTrp(packBytes);
            if (combined != null) {
                packBytes = combined;
                sha1 = sha1(packBytes);
                packId = UUID.nameUUIDFromBytes(sha1);
                plugin.getLogger().info(
                    "TRP uses external hosting; StrobeLights will serve one locally combined "
                        + "TRP + StrobeLights resource pack."
                );
            }
            try {
                startEmbeddedServer(packBytes, configuredUrl);
            } catch (IllegalStateException exception) {
                disableAutomaticDelivery(exception.getMessage());
                return;
            }
        } else {
            if (configuredUrl.isBlank()) {
                disableAutomaticDelivery(
                    "resource-pack.public-url is required when embedded.enabled=false"
                );
                return;
            }
            publicUrl = expandPublicUrl(configuredUrl, sha1);
            try {
                validateUrl(publicUrl);
            } catch (IllegalStateException exception) {
                disableAutomaticDelivery(exception.getMessage());
                return;
            }
        }

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

        active = true;
        plugin.getServer().getOnlinePlayers().forEach(this::sendLater);
        plugin.getLogger().info("3D RGB shader available at " + publicUrl);
    }

    private void disableAutomaticDelivery(String reason) {
        active = false;
        closeEmbeddedServer();
        plugin.getLogger().severe(
            "Automatic resource-pack delivery is unavailable: " + reason
                + ". StrobeLights will remain enabled; correct resource-pack.public-url "
                + "or enable the embedded server, then run /strobe reload."
        );
    }

    public void stop() {
        active = false;
        restoreNexoPackServer();
        defaultUrlConfigured = false;
        nexoManaged = false;
        nexoMerged = false;
        nexoRegenerationRequested = false;
        nexoPlugin = null;
        nexoPackPath = null;
        nexoSourcePackBytes = null;
        nexoPackServerDelegate = null;
        nexoPackServerProxy = null;
        loadedPlayers.clear();
        deliveryAttemptedPlayers.clear();
        closeEmbeddedServer();
    }

    public boolean isLoaded(Player player) {
        UUID playerId = player.getUniqueId();
        return canRender(
            active,
            nexoManaged,
            deliveryAttemptedPlayers.contains(playerId),
            loadedPlayers.contains(playerId)
        );
    }

    static boolean canRender(
        boolean active,
        boolean nexoManaged,
        boolean deliveryAttempted,
        boolean loaded
    ) {
        return !active || loaded;
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
        if (trpManaged) {
            resendWithTrp(player);
            return;
        }
        loadedPlayers.remove(player.getUniqueId());
        sendLater(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        loadedPlayers.remove(playerId);
        deliveryAttemptedPlayers.remove(playerId);
        compatibilityNotifiedPlayers.remove(playerId);
        if (nexoManaged || trpManaged || !active) {
            plugin.manager().setMarkersVisible(player, !active);
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
        deliveryAttemptedPlayers.remove(playerId);
        compatibilityNotifiedPlayers.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!active) {
            return;
        }
        boolean sharedPack = nexoManaged || trpManaged;
        if (!sharedPack && !event.getID().equals(packId)) {
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
                        + "; RGB markers remain available for a previously loaded or "
                        + "proxy-managed combined pack."
                );
            }
            default -> {
                // ACCEPTED and DOWNLOADED are intermediate states.
            }
        }
    }

    private void sendLater(Player player) {
        if (!active || nexoManaged || trpManaged) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!active || nexoManaged || trpManaged || !player.isOnline()) {
                return;
            }
            Component prompt = Component.text(
                plugin.messages().text(player, "resource-pack.prompt", "revision", PACK_REVISION),
                NamedTextColor.AQUA
            );
            deliveryAttemptedPlayers.add(player.getUniqueId());
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
            Math.min(65_535, plugin.getConfig().getInt("resource-pack.embedded.port", 8250))
        );
        if (port == plugin.getServer().getPort()) {
            throw new IllegalStateException(
                "resource-pack.embedded.port must differ from the Minecraft port"
            );
        }
        String token = HexFormat.of().formatHex(sha1);
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
                "HTTP port " + port + " is already in use. If TRP Server Edition is installed, "
                    + "update both plugins so StrobeLights can reuse its listener; otherwise "
                    + "change resource-pack.embedded.port",
                exception
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start the shader server", exception);
        }
    }

    private boolean hostWithTrp(byte[] packBytes) {
        Plugin trp = plugin.getServer().getPluginManager().getPlugin("TRPServerEdition");
        if (trp == null || !trp.isEnabled()) {
            return false;
        }
        try {
            Method register = trp.getClass().getMethod(
                "registerResourcePackOverlay", Plugin.class, String.class, byte[].class
            );
            Object result = register.invoke(
                trp, plugin, "StrobeLights-ResourcePack-" + PACK_REVISION + ".zip", packBytes
            );
            if (result instanceof String combinedUrl && !combinedUrl.isBlank()) {
                validateUrl(combinedUrl);
                publicUrl = combinedUrl;
                trpResourcePackHost = trp;
                trpManaged = true;
                plugin.getLogger().info(
                    "StrobeLights resources merged into TRP's single resource pack."
                );
                return true;
            }
        } catch (NoSuchMethodException ignored) {
            // Older TRP builds can still share the listener without combining packs.
        } catch (IllegalAccessException | InvocationTargetException | IllegalStateException exception) {
            plugin.getLogger().warning(
                "Could not merge StrobeLights resources into TRP: " + exception.getMessage()
            );
        }
        try {
            Method host = trp.getClass().getMethod(
                "hostResourcePack", Plugin.class, String.class, byte[].class
            );
            Object result = host.invoke(
                trp, plugin, "StrobeLights-ResourcePack-" + PACK_REVISION + ".zip", packBytes
            );
            if (!(result instanceof String hostedUrl) || hostedUrl.isBlank()) {
                return false;
            }
            validateUrl(hostedUrl);
            publicUrl = hostedUrl;
            trpResourcePackHost = trp;
            plugin.getLogger().info(
                "Resource-pack downloads reuse TRP Server Edition's HTTP port."
            );
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        } catch (IllegalAccessException | InvocationTargetException | IllegalStateException exception) {
            plugin.getLogger().warning(
                "Could not reuse TRP Server Edition's resource-pack server: "
                    + exception.getMessage()
            );
            return false;
        }
    }

    private void resendWithTrp(Player player) {
        if (trpResourcePackHost == null) {
            return;
        }
        try {
            Method resend = trpResourcePackHost.getClass().getMethod(
                "resendResourcePack", Plugin.class, Player.class
            );
            resend.invoke(trpResourcePackHost, plugin, player);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning(
                "Could not resend TRP's combined resource pack: " + exception.getMessage()
            );
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
        return exportPack(bytes, "StrobeLights-ResourcePack-26.1.2.zip");
    }

    private Path exportPack(byte[] bytes, String fileName) {
        try {
            Path directory = plugin.getDataFolder().toPath().resolve("resource-pack");
            Files.createDirectories(directory);
            return Files.write(directory.resolve(fileName), bytes);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not export a copy of the resource pack: "
                + exception.getMessage());
            return null;
        }
    }

    private byte[] combineWithTrp(byte[] packBytes) {
        Plugin trp = plugin.getServer().getPluginManager().getPlugin("TRPServerEdition");
        if (trp == null || !trp.isEnabled()) {
            return null;
        }
        try {
            Method combine = trp.getClass().getMethod(
                "combineResourcePackOverlay", Plugin.class, String.class, byte[].class
            );
            Object result = combine.invoke(
                trp, plugin, "StrobeLights-ResourcePack-" + PACK_REVISION + ".zip", packBytes
            );
            if (result instanceof byte[] combined && combined.length > 0) {
                return combined;
            }
        } catch (NoSuchMethodException exception) {
            plugin.getLogger().warning(
                "TRP is installed but cannot prepare a pack for Nexo. Update TRP Server "
                    + "Edition and StrobeLights together."
            );
        } catch (IllegalAccessException | InvocationTargetException exception) {
            plugin.getLogger().warning(
                "Could not prepare the TRP-compatible resource pack: " + exception.getMessage()
            );
        }
        return null;
    }

    private boolean hasEnabledNexoIntegration() {
        if (!plugin.getConfig().getBoolean("resource-pack.nexo-integration.enabled", true)) {
            return false;
        }
        Plugin nexo = plugin.getServer().getPluginManager().getPlugin("Nexo");
        return nexo != null && nexo.isEnabled();
    }

    private boolean startNexoIntegration(Path exportedPack, byte[] packBytes) {
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
            nexoSourcePackBytes = packBytes.clone();
            nexoManaged = true;
            installNexoPackServerInterceptor(detected, loader);
            if (!nexoListenerRegistered) {
                plugin.getServer().getPluginManager().registerEvent(
                    eventType,
                    nexoPackListener,
                    EventPriority.MONITOR,
                    (listener, event) -> mergeIntoNexo(event),
                    plugin,
                    true
                );
                nexoListenerRegistered = true;
            }
            scheduleNexoRecovery(packBytes);
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            restoreNexoPackServer();
            nexoManaged = false;
            nexoPlugin = null;
            nexoPackPath = null;
            nexoSourcePackBytes = null;
            nexoPackServerDelegate = null;
            nexoPackServerProxy = null;
            plugin.getLogger().warning(
                "Nexo was detected but its pack integration API is unavailable ("
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage()
                    + "). StrobeLights will use standalone delivery."
            );
            return false;
        }
    }

    private synchronized void installNexoPackServerInterceptor(
        Plugin detected,
        ClassLoader loader
    ) throws ReflectiveOperationException {
        Method getter = detected.getClass().getMethod("packServer");
        Object currentServer = getter.invoke(detected);
        if (currentServer == null) {
            throw new ReflectiveOperationException("Nexo has no active resource-pack server");
        }
        if (currentServer == nexoPackServerProxy) {
            return;
        }

        Class<?> serverType = Class.forName(
            "com.nexomc.nexo.pack.server.NexoPackServer",
            true,
            loader
        );
        Object delegate = currentServer;
        Object interceptor = Proxy.newProxyInstance(
            loader,
            new Class<?>[] {serverType},
            (proxy, method, arguments) -> {
                if (method.getName().equals("uploadPack")
                    && method.getParameterCount() == 0) {
                    finalizeNexoBuiltPack(delegate, loader);
                }
                try {
                    return method.invoke(delegate, arguments);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            }
        );
        detected.getClass().getMethod("packServer", serverType).invoke(detected, interceptor);
        nexoPackServerDelegate = delegate;
        nexoPackServerProxy = interceptor;
    }

    private synchronized void restoreNexoPackServer() {
        Plugin detected = nexoPlugin;
        Object delegate = nexoPackServerDelegate;
        Object interceptor = nexoPackServerProxy;
        if (detected == null || delegate == null || interceptor == null) {
            return;
        }
        try {
            Method getter = detected.getClass().getMethod("packServer");
            if (getter.invoke(detected) != interceptor) {
                return;
            }
            detected.getClass()
                .getMethod("packServer", interceptor.getClass().getInterfaces()[0])
                .invoke(detected, delegate);
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning(
                "Could not restore Nexo's original pack server: " + exception.getMessage()
            );
        }
    }

    private void finalizeNexoBuiltPack(Object packServer, ClassLoader loader) {
        byte[] sourcePack = nexoSourcePackBytes;
        Plugin detected = nexoPlugin;
        if (sourcePack == null || detected == null) {
            return;
        }
        try {
            Object generator = detected.getClass().getMethod("packGenerator").invoke(detected);
            Object builtPack = generator.getClass().getMethod("builtPack").invoke(generator);
            if (builtPack == null) {
                throw new IllegalStateException("Nexo has not built a resource pack");
            }
            Class<?> builtPackType = Class.forName(
                "team.unnamed.creative.BuiltResourcePack",
                true,
                loader
            );
            Object writable = builtPackType.getMethod("data").invoke(builtPack);
            Class<?> writableType = Class.forName(
                "team.unnamed.creative.base.Writable",
                true,
                loader
            );
            byte[] generatedPack = (byte[]) writableType
                .getMethod("toByteArray")
                .invoke(writable);

            boolean alreadyExact = containsExactNexoRenderPipeline(generatedPack, sourcePack);
            byte[] verifiedPack = alreadyExact
                ? generatedPack
                : overlayNexoRenderPipeline(generatedPack, sourcePack);
            if (!containsExactNexoRenderPipeline(verifiedPack, sourcePack)) {
                throw new IOException("the final ZIP still does not contain the RGB pipeline");
            }
            if (!alreadyExact) {
                replaceNexoBuiltPack(generator, verifiedPack, loader);
            }
            clearNexoPackServerCache(packServer);
            exportVerifiedNexoPack(verifiedPack);
            scheduleNexoOutputRepair(verifiedPack, loader);

            nexoMerged = true;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!active || !nexoManaged) {
                    return;
                }
                plugin.getServer().getOnlinePlayers().forEach(
                    player -> plugin.manager().setMarkersVisible(player, true)
                );
            });
            String action = alreadyExact ? "verified" : "repaired after Nexo processing";
            plugin.getLogger().info(
                "Nexo's final client ZIP was " + action + "; all StrobeLights RGB "
                    + "shader files match revision " + PACK_REVISION + "."
            );
        } catch (IOException | ReflectiveOperationException | LinkageError exception) {
            nexoMerged = false;
            plugin.getLogger().severe(
                "Could not verify Nexo's final client ZIP ("
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage()
                    + "). Standalone recovery remains armed."
            );
        }
    }

    private static void replaceNexoBuiltPack(
        Object generator,
        byte[] packBytes,
        ClassLoader loader
    ) throws ReflectiveOperationException {
        Class<?> writableType = Class.forName(
            "team.unnamed.creative.base.Writable",
            true,
            loader
        );
        Object writable = writableType.getMethod("bytes", byte[].class)
            .invoke(null, (Object) packBytes);
        Class<?> builtPackType = Class.forName(
            "team.unnamed.creative.BuiltResourcePack",
            true,
            loader
        );
        String hash = HexFormat.of().formatHex(sha1(packBytes));
        Object replacement = builtPackType
            .getMethod("of", writableType, String.class)
            .invoke(null, writable, hash);
        Field builtPackField = generator.getClass().getDeclaredField("builtPack");
        builtPackField.setAccessible(true);
        builtPackField.set(generator, replacement);
    }

    static void clearNexoPackServerCache(Object packServer)
        throws ReflectiveOperationException {
        Class<?> type = packServer.getClass();
        while (type != null) {
            try {
                Field cache = type.getDeclaredField("builtPackArray");
                if (cache.getType() == byte[].class) {
                    cache.setAccessible(true);
                    cache.set(packServer, null);
                }
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
    }

    private void exportVerifiedNexoPack(byte[] packBytes) throws IOException {
        Path directory = plugin.getDataFolder().toPath().resolve("resource-pack");
        Files.createDirectories(directory);
        String sourceName = nexoPackPath == null
            ? "StrobeLights-Nexo-Combined.zip"
            : nexoPackPath.getFileName().toString().replace(
                "StrobeLights-ResourcePack",
                "StrobeLights-Nexo-Combined"
            );
        Files.write(directory.resolve(sourceName), packBytes);
    }

    private void scheduleNexoOutputRepair(byte[] packBytes, ClassLoader loader) {
        byte[] verifiedPack = packBytes.clone();
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!active || !nexoManaged) {
                return;
            }
            try {
                Class<?> settings = Class.forName(
                    "com.nexomc.nexo.configs.Settings",
                    true,
                    loader
                );
                Object outputSetting = settings.getField("PACK_OUTPUT_PATH").get(null);
                Object configured = outputSetting.getClass()
                    .getMethod("toStringListOrSingle")
                    .invoke(outputSetting);
                if (!(configured instanceof Iterable<?> paths)) {
                    return;
                }
                for (Object value : paths) {
                    if (!(value instanceof String pathValue)
                        || pathValue.isBlank()
                        || !pathValue.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        continue;
                    }
                    Path output = Path.of(pathValue);
                    if (!output.isAbsolute()) {
                        output = Path.of(System.getProperty("user.dir")).resolve(output);
                    }
                    Files.createDirectories(output.toAbsolutePath().normalize().getParent());
                    Files.write(output, verifiedPack);
                }
            } catch (IOException | ReflectiveOperationException | LinkageError exception) {
                plugin.getLogger().warning(
                    "Could not update Nexo's configured external ZIP with the verified "
                        + "RGB pack: " + exception.getMessage()
                );
            }
        }, 20L);
    }

    private void mergeIntoNexo(Event event) {
        Path packPath = nexoPackPath;
        if (!active || nexoPlugin == null || packPath == null) {
            return;
        }
        Object resourcePack = null;
        Object originalPackMeta = null;
        try {
            resourcePack = event.getClass().getMethod("getResourcePack").invoke(event);
            originalPackMeta = resourcePack.getClass().getMethod("packMeta").invoke(resourcePack);
            Method addResourcePack = event.getClass().getMethod("addResourcePack", File.class);
            Object result = addResourcePack.invoke(event, packPath.toFile());
            restoreNexoPackMeta(resourcePack, originalPackMeta);
            if (Boolean.FALSE.equals(result)) {
                plugin.getLogger().warning(
                    "Nexo rejected the StrobeLights resource-pack ZIP: " + packPath
                );
                return;
            }
            int rawPipelineFiles = injectNexoRenderPipeline(event, packPath);
            installNexoPackServerInterceptor(
                nexoPlugin,
                nexoPlugin.getClass().getClassLoader()
            );
            nexoManaged = true;
            closeEmbeddedServer();
            plugin.getLogger().info(
                "StrobeLights shaders and models were added at the end of Nexo's "
                    + "post-generation event without replacing Nexo's pack metadata; "
                    + rawPipelineFiles + " critical render files are awaiting final ZIP "
                    + "verification."
            );
        } catch (IOException | ReflectiveOperationException | LinkageError exception) {
            if (resourcePack != null && originalPackMeta != null) {
                try {
                    restoreNexoPackMeta(resourcePack, originalPackMeta);
                } catch (ReflectiveOperationException restoreException) {
                    plugin.getLogger().warning(
                        "Could not restore Nexo's resource-pack metadata: "
                            + restoreException.getMessage()
                    );
                }
            }
            plugin.getLogger().warning(
                "Could not add StrobeLights to Nexo's generated pack: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }
    }

    private static int injectNexoRenderPipeline(Event event, Path packPath)
        throws IOException, ReflectiveOperationException {
        Method addUnknownFile = event.getClass().getMethod(
            "addUnknownFile",
            String.class,
            byte[].class
        );
        int imported = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(packPath))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String path = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && isNexoRenderPipelineEntry(path)) {
                    Object result = addUnknownFile.invoke(event, path, input.readAllBytes());
                    if (Boolean.FALSE.equals(result)) {
                        throw new IOException("Nexo rejected critical render file " + path);
                    }
                    imported++;
                }
                input.closeEntry();
            }
        }
        if (imported == 0) {
            throw new IOException("The StrobeLights ZIP contains no Nexo render pipeline files");
        }
        return imported;
    }

    static boolean containsExactNexoRenderPipeline(byte[] generatedPack, byte[] sourcePack)
        throws IOException {
        Map<String, byte[]> generated = readZipEntries(generatedPack, false);
        Map<String, byte[]> expected = readZipEntries(sourcePack, true);
        if (expected.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            byte[] actual = generated.get(entry.getKey());
            if (actual == null || !MessageDigest.isEqual(entry.getValue(), actual)) {
                return false;
            }
        }
        return true;
    }

    static byte[] overlayNexoRenderPipeline(byte[] generatedPack, byte[] sourcePack)
        throws IOException {
        Map<String, byte[]> merged = readZipEntries(generatedPack, false);
        Map<String, byte[]> pipeline = readZipEntries(sourcePack, true);
        if (pipeline.isEmpty()) {
            throw new IOException("The StrobeLights ZIP contains no RGB render pipeline");
        }
        pipeline.forEach((path, bytes) -> {
            merged.remove(path);
            merged.put(path, bytes);
        });

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : merged.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                output.putNextEntry(zipEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> readZipEntries(byte[] zip, boolean pipelineOnly)
        throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String path = entry.getName().replace('\\', '/');
                if (!entry.isDirectory()
                    && (!pipelineOnly || isNexoRenderPipelineEntry(path))) {
                    entries.remove(path);
                    entries.put(path, input.readAllBytes());
                }
                input.closeEntry();
            }
        }
        return entries;
    }

    static boolean isNexoRenderPipelineEntry(String path) {
        return path.equals("assets/minecraft/post_effect/transparency.json")
            || path.equals("assets/strobelights/strobelights-integration.json")
            || path.startsWith("assets/minecraft/shaders/");
    }

    static void restoreNexoPackMeta(Object resourcePack, Object packMeta)
        throws ReflectiveOperationException {
        if (packMeta == null) {
            return;
        }
        for (Method method : resourcePack.getClass().getMethods()) {
            if (method.getName().equals("packMeta")
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0].isInstance(packMeta)) {
                method.invoke(resourcePack, packMeta);
                return;
            }
        }
        throw new NoSuchMethodException("ResourcePack.packMeta(PackMeta)");
    }

    private void scheduleNexoRecovery(byte[] packBytes) {
        long regenerationDelay = Math.max(1L, Math.min(
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
            requestNexoRegenerationIfIdle();
        }, regenerationDelay);

        long fallbackDelay = Math.max(regenerationDelay + 1L, Math.min(
            2_400L,
            plugin.getConfig().getLong(
                "resource-pack.nexo-integration.fallback-delay-ticks",
                600L
            )
        ));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!active || nexoMerged || nexoPlugin == null) {
                return;
            }
            plugin.getLogger().warning(
                "Nexo did not confirm the StrobeLights pack merge after " + fallbackDelay
                    + " ticks. Falling back to StrobeLights' standalone delivery."
            );
            startStandaloneDelivery(packBytes);
        }, fallbackDelay);
    }

    private void requestNexoRegenerationIfIdle() {
        if (!active || !nexoManaged || nexoMerged
            || nexoRegenerationRequested || nexoPlugin == null) {
            return;
        }
        try {
            Object generator = nexoPlugin.getClass()
                .getMethod("packGenerator")
                .invoke(nexoPlugin);
            Object generation = generator.getClass()
                .getMethod("getPackGenFuture")
                .invoke(generator);
            if (generation instanceof CompletableFuture<?> future && !future.isDone()) {
                plugin.getLogger().info(
                    "Nexo is still generating its initial pack; StrobeLights will wait "
                        + "instead of interrupting it."
                );
                plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    this::requestNexoRegenerationIfIdle,
                    20L
                );
                return;
            }
            generator.getClass().getMethod("regeneratePack").invoke(generator);
            nexoRegenerationRequested = true;
            plugin.getLogger().info(
                "Requested a Nexo pack regeneration so StrobeLights can be merged."
            );
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning(
                "Could not inspect or request Nexo pack generation: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }
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

    static String expandPublicUrl(String value, byte[] digest) {
        return value.replace("{token}", HexFormat.of().formatHex(digest));
    }

    private static byte[] sha1(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private void closeEmbeddedServer() {
        if (trpResourcePackHost != null) {
            try {
                Method unhost = trpResourcePackHost.getClass().getMethod(
                    trpManaged ? "unregisterResourcePackOverlay" : "unhostResourcePacks",
                    Plugin.class
                );
                unhost.invoke(trpResourcePackHost, plugin);
            } catch (ReflectiveOperationException exception) {
                plugin.getLogger().fine(
                    "TRP shared resource-pack route was already unavailable: "
                        + exception.getMessage()
                );
            }
            trpResourcePackHost = null;
        }
        trpManaged = false;
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
    }

    private static String urlHost(String host) {
        return host.contains(":") ? "[" + host + "]" : host;
    }
}
