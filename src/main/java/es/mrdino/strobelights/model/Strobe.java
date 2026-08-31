package es.mrdino.strobelights.model;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

public final class Strobe {

    public static final int DEFAULT_COLOR = 0xFFFFFF;
    public static final int DEFAULT_REFRESH_TICKS = 5;
    public static final int DEFAULT_LIGHT_LEVEL = 15;
    public static final int DEFAULT_FLASH_POWER = 50;
    public static final BlindnessLevel DEFAULT_BLINDNESS = BlindnessLevel.LOW;
    public static final StrobeMode DEFAULT_MODE = StrobeMode.STROBE;

    private String name;
    private UUID worldId;
    private String worldName;
    private double x;
    private double y;
    private double z;
    private int rgb;
    private int refreshTicks;
    private int lightLevel;
    private int flashPower;
    private BlindnessLevel blindness;
    private StrobeMode mode;
    private boolean enabled;
    private BlockFace face;
    private boolean placed;

    public Strobe(
        String name,
        UUID worldId,
        String worldName,
        double x,
        double y,
        double z,
        int rgb,
        int refreshTicks,
        int lightLevel,
        int flashPower,
        BlindnessLevel blindness,
        boolean enabled,
        BlockFace face,
        boolean placed
    ) {
        this(
            name, worldId, worldName, x, y, z, rgb, refreshTicks, lightLevel,
            flashPower, blindness, enabled, face, placed, DEFAULT_MODE
        );
    }

    public Strobe(
        String name,
        UUID worldId,
        String worldName,
        double x,
        double y,
        double z,
        int rgb,
        int refreshTicks,
        int lightLevel,
        int flashPower,
        BlindnessLevel blindness,
        boolean enabled,
        BlockFace face,
        boolean placed,
        StrobeMode mode
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.worldName = Objects.requireNonNull(worldName, "worldName");
        this.x = x;
        this.y = y;
        this.z = z;
        this.rgb = rgb & 0xFFFFFF;
        this.refreshTicks = Math.max(1, refreshTicks);
        this.lightLevel = Math.max(0, Math.min(15, lightLevel));
        this.flashPower = Math.max(0, Math.min(200, flashPower));
        this.blindness = Objects.requireNonNull(blindness, "blindness");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.enabled = enabled;
        this.face = sanitizeFace(face);
        this.placed = placed;
    }

    public static Strobe create(String name, Location location, BlockFace face, int rgb) {
        World world = Objects.requireNonNull(location.getWorld(), "world");
        boolean inAir = face == BlockFace.SELF;
        return new Strobe(
            name, world.getUID(), world.getName(),
            inAir ? location.getX() : location.getBlockX(),
            inAir ? location.getY() : location.getBlockY(),
            inAir ? location.getZ() : location.getBlockZ(),
            rgb, DEFAULT_REFRESH_TICKS, DEFAULT_LIGHT_LEVEL, DEFAULT_FLASH_POWER,
            DEFAULT_BLINDNESS, false,
            face, true
        );
    }

    public static Strobe draft(String name, World world) {
        return new Strobe(
            name, world.getUID(), world.getName(), 0, world.getMinHeight(), 0,
            DEFAULT_COLOR, DEFAULT_REFRESH_TICKS, DEFAULT_LIGHT_LEVEL, DEFAULT_FLASH_POWER,
            DEFAULT_BLINDNESS, false, BlockFace.UP, false
        );
    }

    public String name() {
        return name;
    }

    public String key() {
        return key(name);
    }

    public UUID worldId() {
        return worldId;
    }

    public String worldName() {
        return worldName;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    public int rgb() {
        return rgb;
    }

    public int refreshTicks() {
        return refreshTicks;
    }

    public int lightLevel() {
        return lightLevel;
    }

    public int flashPower() {
        return flashPower;
    }

    public BlindnessLevel blindness() {
        return blindness;
    }

    public StrobeMode mode() {
        return mode;
    }

    public boolean enabled() {
        return enabled;
    }

    public BlockFace face() {
        return face;
    }

    public boolean placed() {
        return placed;
    }

    public World world() {
        World byId = Bukkit.getWorld(worldId);
        return byId != null ? byId : Bukkit.getWorld(worldName);
    }

    public Location blockLocation() {
        World world = world();
        return world == null ? null : new Location(world, x, y, z);
    }

    public Location centerLocation() {
        Location location = blockLocation();
        if (location == null || face == BlockFace.SELF) {
            return location;
        }
        return location.add(0.5, 0.5, 0.5);
    }

    public Location impactLocation() {
        Location center = centerLocation();
        if (center == null || !placed) {
            return null;
        }
        if (face == BlockFace.SELF) {
            return center;
        }
        return center.subtract(normal().multiply(0.499));
    }

    public Vector normal() {
        return new Vector(face.getModX(), face.getModY(), face.getModZ());
    }

    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public void move(Location location, BlockFace face) {
        World world = Objects.requireNonNull(location.getWorld(), "world");
        boolean inAir = face == BlockFace.SELF;
        this.worldId = world.getUID();
        this.worldName = world.getName();
        this.x = inAir ? location.getX() : location.getBlockX();
        this.y = inAir ? location.getY() : location.getBlockY();
        this.z = inAir ? location.getZ() : location.getBlockZ();
        this.face = sanitizeFace(face);
        this.placed = true;
    }

    public void setRgb(int rgb) {
        this.rgb = rgb & 0xFFFFFF;
    }

    public void setRefreshTicks(int refreshTicks) {
        this.refreshTicks = Math.max(1, refreshTicks);
    }

    public void setLightLevel(int lightLevel) {
        this.lightLevel = Math.max(0, Math.min(15, lightLevel));
    }

    public void setFlashPower(int flashPower) {
        this.flashPower = Math.max(0, Math.min(200, flashPower));
    }

    public void setBlindness(BlindnessLevel blindness) {
        this.blindness = Objects.requireNonNull(blindness, "blindness");
    }

    public void setMode(StrobeMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static BlockFace sanitizeFace(BlockFace face) {
        if (face == BlockFace.SELF) {
            return face;
        }
        if (face == null || !face.isCartesian()) {
            return BlockFace.UP;
        }
        return face;
    }
}
