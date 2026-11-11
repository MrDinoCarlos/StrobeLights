package es.mrdino.strobelights.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

public class TrpGraphicsCompatibilityTest {
    public interface GraphicsApi {
        boolean isFabulousRgbEnabled(UUID playerId);
    }

    @Test
    void standaloneAndOlderTrpKeepTheirExistingLighting() {
        UUID id = UUID.randomUUID();
        assertTrue(new TrpGraphicsCompatibility(() -> null).allowsRgb(id));
        Plugin oldTrp = plugin(false, new AtomicBoolean(true), new HashSet<>(), false);
        assertTrue(new TrpGraphicsCompatibility(() -> oldTrp).allowsRgb(id));
    }

    @Test
    void nativeFabulousNativeAndIndependentViewersAreReadOnEveryTick() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        Set<UUID> fabulous = new HashSet<>();
        Plugin trp = plugin(true, new AtomicBoolean(true), fabulous, false);
        TrpGraphicsCompatibility policy = new TrpGraphicsCompatibility(() -> trp);
        assertFalse(policy.allowsRgb(first));
        fabulous.add(first);
        assertTrue(policy.allowsRgb(first));
        assertFalse(policy.allowsRgb(second));
        fabulous.remove(first);
        assertFalse(policy.allowsRgb(first));
    }

    @Test
    void pluginReplacementAndEnableDisableDoNotKeepStaleProfiles() {
        UUID id = UUID.randomUUID();
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicReference<Plugin> current = new AtomicReference<>(
            plugin(true, enabled, new HashSet<>(), false));
        TrpGraphicsCompatibility policy = new TrpGraphicsCompatibility(current::get);
        assertFalse(policy.allowsRgb(id));
        enabled.set(false);
        assertTrue(policy.allowsRgb(id));
        enabled.set(true);
        assertFalse(policy.allowsRgb(id));
        current.set(plugin(true, enabled, new HashSet<>(Set.of(id)), false));
        assertTrue(policy.allowsRgb(id));
        current.set(plugin(false, enabled, new HashSet<>(), false));
        assertTrue(policy.allowsRgb(id));
        current.set(plugin(true, enabled, new HashSet<>(), false));
        assertFalse(policy.allowsRgb(id));
    }

    @Test
    void failingNewApiDoesNotLeakEncodedPixelsOrThrowIntoTheTick() {
        Plugin trp = plugin(true, new AtomicBoolean(true), new HashSet<>(), true);
        TrpGraphicsCompatibility policy = new TrpGraphicsCompatibility(() -> trp);
        assertFalse(policy.allowsRgb(UUID.randomUUID()));
        assertFalse(policy.allowsRgb(UUID.randomUUID()));
    }

    private static Plugin plugin(boolean modern, AtomicBoolean enabled,
                                 Set<UUID> fabulous, boolean broken) {
        Class<?>[] interfaces = modern
            ? new Class<?>[] {Plugin.class, GraphicsApi.class}
            : new Class<?>[] {Plugin.class};
        return (Plugin) Proxy.newProxyInstance(TrpGraphicsCompatibilityTest.class.getClassLoader(),
            interfaces, (proxy, method, arguments) -> switch (method.getName()) {
                case "isEnabled" -> enabled.get();
                case "isFabulousRgbEnabled" -> {
                    if (broken) throw new IllegalStateException("Policy temporarily unavailable");
                    yield fabulous.contains(arguments[0]);
                }
                case "toString" -> "TRP graphics API fixture";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> null;
            });
    }
}
