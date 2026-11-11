package es.mrdino.strobelights.service;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;

/** Optional TRP graphics policy; does not change resource-pack availability. */
final class TrpGraphicsCompatibility {
    private final Supplier<Plugin> lookup;
    private Plugin resolvedPlugin;
    private Method fabulousRgb;

    TrpGraphicsCompatibility(Supplier<Plugin> lookup) {
        this.lookup = lookup;
    }

    boolean allowsRgb(UUID playerId) {
        Plugin trp = lookup.get();
        if (trp == null || !trp.isEnabled()) {
            // Standalone StrobeLights and older TRP retain their existing behavior.
            return true;
        }
        if (trp != resolvedPlugin) {
            resolvedPlugin = trp;
            fabulousRgb = null;
            try {
                Method method = trp.getClass().getMethod("isFabulousRgbEnabled", UUID.class);
                if (method.getReturnType() == boolean.class) {
                    fabulousRgb = method;
                }
            } catch (NoSuchMethodException ignored) {
                // The graphics policy API was introduced in TRP 0.4.18.
            }
        }
        if (fabulousRgb == null) {
            return true;
        }
        try {
            // Cache the API method, never the viewer's mutable preference.
            return Boolean.TRUE.equals(fabulousRgb.invoke(trp, playerId));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // An unavailable policy must not expose encoded pixels in the scene.
            return false;
        }
    }
}
