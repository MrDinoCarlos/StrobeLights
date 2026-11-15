package es.mrdino.strobelights.compatibility;

/** Explicit resource-pack ABIs. A family JAR is not a promise of future compatibility. */
public enum MinecraftProfile {
    MC_1_20_1("1.20.1", "1.20.X", true, false),
    MC_1_21_4("1.21.4", "1.21.X", true, false),
    MC_1_21_10("1.21.10", "1.21.X", true, false),
    MC_1_21_11("1.21.11", "1.21.X", true, true),
    MC_26_1_2("26.1.2", "26.1.X", true, false),
    MC_26_2("26.2", "26.2.X", true, false),
    MC_26_3("26.3", "26.3.X", true, false);

    private final String version;
    private final String family;
    private final boolean rgb;
    private final boolean sourceAnchored;

    MinecraftProfile(String version, String family, boolean rgb, boolean sourceAnchored) {
        this.version = version;
        this.family = family;
        this.rgb = rgb;
        this.sourceAnchored = sourceAnchored;
    }

    public String version() { return version; }
    public String family() { return family; }
    public boolean supportsRgb() { return rgb; }
    public boolean sourceAnchored() { return sourceAnchored; }
    public String packName() { return "StrobeLights-ResourcePack-" + version + ".zip"; }

    public static MinecraftProfile forVersion(String version) {
        for (MinecraftProfile profile : values()) {
            if (profile.version.equals(version)) return profile;
        }
        throw new IllegalArgumentException("Unsupported Minecraft version " + version
            + ". Supported: 1.20.1, 1.21.4, 1.21.10, 1.21.11, 26.1.2, 26.2, 26.3.");
    }
}
