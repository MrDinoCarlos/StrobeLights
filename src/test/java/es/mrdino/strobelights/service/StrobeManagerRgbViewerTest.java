package es.mrdino.strobelights.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import es.mrdino.strobelights.StrobeLightsPlugin;
import es.mrdino.strobelights.model.BlindnessLevel;
import es.mrdino.strobelights.model.Strobe;
import es.mrdino.strobelights.resourcepack.ResourcePackService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class StrobeManagerRgbViewerTest {
    @Test
    void switchingBackToNativeRemovesSourceAndShadowCarriersWithoutRespawning() throws Exception {
        Fixture f = new Fixture();
        Object state = construct("RuntimeState");
        ItemDisplay light = f.display(), shadow = f.display();
        map(state, "viewerCarriers").put(f.id, light);
        map(state, "occlusionCarriers").put(f.id, shadow);
        when(f.api.isFabulousRgbEnabled(f.id)).thenReturn(true);
        invoke(f.manager, "updateFixedSourceViewers", f.strobe, state);
        verify(light, never()).remove();
        verify(shadow, never()).remove();
        when(f.api.isFabulousRgbEnabled(f.id)).thenReturn(false);
        invoke(f.manager, "updateFixedSourceViewers", f.strobe, state);
        invoke(f.manager, "updateFixedSourceViewers", f.strobe, state);
        verify(light).remove();
        verify(shadow).remove();
        assertTrue(map(state, "viewerCarriers").isEmpty());
        assertTrue(map(state, "occlusionCarriers").isEmpty());
        assertTrue(mockingDetails(f.world).getInvocations().stream()
            .noneMatch(call -> call.getMethod().getName().equals("spawn")));
    }

    @Test
    void nativeRemovesDiscoverySceneAndCameraDataAndKeepsEditingHandle() throws Exception {
        Fixture f = new Fixture();
        Object state = construct("RuntimeState");
        map(f.manager, "runtime").put("source", state);
        @SuppressWarnings("unchecked") Set<UUID> active = (Set<UUID>) field(f.manager, "activeDiscoveryPlayers").get(f.manager);
        active.add(f.id);
        ItemDisplay discovery = f.display(), token = f.display(), handle = f.display();
        map(state, "discoveryLights").put(f.id, discovery);
        map(state, "discoveryOcclusionLights").put(f.id, token);
        field(state, "editorHandle").set(state, handle);
        invoke(f.manager, "tickDiscovery");
        verify(discovery).remove();
        verify(token).remove();
        verify(handle, never()).remove();
        verify(f.player).showEntity(f.plugin, handle);

        Object scene = construct("SceneFlash", f.location(), 10, 0xFFFFFF, 15, 1, 128.0);
        ItemDisplay sceneMarker = f.display();
        map(scene, "viewerCarriers").put(f.id, sceneMarker);
        invoke(f.manager, "updateSceneFlash", UUID.randomUUID(), scene);
        verify(sceneMarker).remove();

        Object flash = construct("CameraFlash");
        ItemDisplay cameraMarker = f.display();
        field(flash, "marker").set(flash, cameraMarker);
        field(flash, "remainingTicks").set(flash, 10);
        map(f.manager, "cameraFlashes").put(f.id, flash);
        invoke(f.manager, "tickCameraFlashes");
        verify(cameraMarker).remove();
        assertTrue(map(f.manager, "cameraFlashes").isEmpty());
        invoke(f.manager, "startCameraFlash", f.player, 0xFFFFFF, BlindnessLevel.EXTREME, 100, 1.0, 10);
        assertTrue(map(f.manager, "cameraFlashes").isEmpty());
    }

    @Test
    void fabulousStillRequiresTheResourcePack() throws Exception {
        Fixture f = new Fixture();
        when(f.api.isFabulousRgbEnabled(f.id)).thenReturn(true);
        when(f.pack.isLoaded(f.player)).thenReturn(false);
        Object state = construct("RuntimeState");
        ItemDisplay light = f.display();
        map(state, "viewerCarriers").put(f.id, light);
        invoke(f.manager, "updateFixedSourceViewers", f.strobe, state);
        verify(light).remove();
    }

    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final StrobeLightsPlugin plugin = mock(StrobeLightsPlugin.class, call ->
            call.getMethod().getName().equals("namespace") ? "strobelights" : RETURNS_DEFAULTS.answer(call));
        final ResourcePackService pack = mock(ResourcePackService.class);
        final World world = mock(World.class);
        final Player player = mock(Player.class);
        final Strobe strobe = mock(Strobe.class);
        final TrpGraphicsCompatibilityTest.GraphicsApi api;
        final StrobeManager manager;

        Fixture() {
            Server server = mock(Server.class);
            PluginManager plugins = mock(PluginManager.class);
            Plugin trp = mock(Plugin.class, withSettings()
                .extraInterfaces(TrpGraphicsCompatibilityTest.GraphicsApi.class));
            api = (TrpGraphicsCompatibilityTest.GraphicsApi) trp;
            when(trp.isEnabled()).thenReturn(true);
            when(plugins.getPlugin("TRPServerEdition")).thenReturn(trp);
            when(server.getPluginManager()).thenReturn(plugins);
            doReturn(List.of(player)).when(server).getOnlinePlayers();
            when(server.getPlayer(id)).thenReturn(player);
            when(plugin.getName()).thenReturn("StrobeLights");
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getConfig()).thenReturn(new YamlConfiguration());
            when(plugin.resourcePack()).thenReturn(pack);
            when(pack.isLoaded(player)).thenReturn(true);
            when(player.getUniqueId()).thenReturn(id);
            when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenReturn(world);
            when(player.getEyeLocation()).thenAnswer(ignored -> location());
            when(world.getPlayers()).thenReturn(List.of(player));
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(strobe.key()).thenReturn("source");
            when(strobe.placed()).thenReturn(true);
            when(strobe.enabled()).thenReturn(true);
            when(strobe.lightLevel()).thenReturn(15);
            when(strobe.geometryOcclusion()).thenReturn(true);
            when(strobe.impactLocation()).thenAnswer(ignored -> location());
            when(strobe.normal()).thenAnswer(ignored -> new Vector());
            manager = new StrobeManager(plugin, mock(StrobeRepository.class), Map.of("source", strobe));
            clearInvocations(world);
        }

        Location location() { return new Location(world, 0, 1, -10); }

        ItemDisplay display() {
            ItemDisplay display = mock(ItemDisplay.class);
            when(display.isValid()).thenReturn(true);
            when(display.getWorld()).thenReturn(world);
            when(display.getLocation()).thenAnswer(ignored -> location());
            when(display.getTransformation()).thenReturn(new Transformation(
                new Vector3f(), new Quaternionf(), new Vector3f(1), new Quaternionf()));
            return display;
        }
    }

    private static Object construct(String nested, Object... args) throws Exception {
        Class<?> type = Class.forName(StrobeManager.class.getName() + "$" + nested);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == args.length) {
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            }
        }
        throw new AssertionError("Constructor not found: " + nested);
    }

    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object owner, String name) throws Exception {
        return (Map<Object, Object>) field(owner, name).get(owner);
    }

    private static void invoke(Object owner, String name, Object... args) throws Exception {
        for (Method method : owner.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                method.invoke(owner, args);
                return;
            }
        }
        throw new AssertionError("Method not found: " + name);
    }
}
