# StrobeLights — Paper 1.21.4

StrobeLights provides configurable 3D RGB strobe lighting based on
[Light Painter](https://github.com/bradleyq/light_painter), revision `2364940`,
plus a per-player RGB camera flash. Normal sources do not draw fixtures, beams
or painted surfaces; throwable flashbangs deliberately emit a brief vanilla
detonation cue.

## Requirements and rendering modes

- Paper 1.21.4 and Java 21.
- Players must accept the resource pack sent by the server (3D shaders and GUI icons).
- **Vanilla Fabulous graphics:** full screen-space 3D RGB lighting.
- **Fast/Fancy or modified renderers:** automatic white vanilla light fallback using
  invisible `LIGHT` blocks.

The 0.8 renderer keeps every rendered light vertex at its real, fixed world
position. Its technical display disables client frustum culling so the source
can render above, below and on every side without moving. No normal, discovery or
environmental light marker is attached to a player or derived from camera yaw,
pitch or movement.
The camera flash remains directional: it is not triggered when the player
faces away from the source.

RGB intensity now controls both power and reach. Low levels remain localized,
while `15/15` uses a broad saturated falloff with an 18-block outer radius, so
maximum lights visibly wash large walls and floors instead of forming a small
white-centered spot.

## GUI workflow

1. Run `/strobe` with no arguments.
2. Click **Create a strobe** and type only its name in chat.
3. The new strobe opens with these defaults:
   - color: white (`#FFFFFF`);
   - screen flash: `low`;
   - flash power: `50%`;
   - RGB intensity: `15/15`.
4. Select **Place light point**:
   - right click a block to attach it to that face;
   - left click/swing to place it at the player's exact current position.
5. Configure color, refresh, intensity, screen-flash level and flash power.
6. Start it or send a test pulse.

The GUI also supports moving, teleporting to, renaming and deleting strobes,
global start/stop, exact RGB channels, discovery mode and forced resource-pack
redelivery. Its controls, navigation and complete RGB palette use custom
resource-pack icons through model data on `PAPER`; ordinary paper and every
other vanilla item keep their normal model. The list and strobe editor use the
compact three-row layout; only the full RGB palette expands to five rows. Back
is always in the bottom-left slot and Close in the bottom-right on submenus.

## Static lights and discovery

- Refresh is configurable from `1` to `1200` ticks by default.
- In the GUI, reach the configured maximum and click once more toward slower to
  switch to `STATIC`. A left click on a static light returns to strobe mode.
- `STATIC` is a real continuous mode and also works as decorative RGB lighting.
- `/strobe discover` privately reveals nearby strobes as steady RGB lights and
  shows a temporary editing handle only when no solid block is between the
  player and its point. The saved state of the lights is unchanged.
- Discovery range and its minimum preview intensity are configurable.

## EasyArmorStands integration

[EasyArmorStands](https://github.com/56738/EasyArmorStands) is an optional soft
dependency. For Paper 1.21.4 use its v2 line. While a player holds the tool from
`/eas give`, StrobeLights automatically enables discovery for that player. The
temporary 3-axis handle can then be selected and moved with EasyArmorStands;
the exact invisible light position follows it. Removing the tool hides the
handles again. StrobeLights registers each handle as a persistent
`minecraft:item_display` because EasyArmorStands rejects non-persistent
entities by default, and gives it a configurable 1×1-block selection box.

## Throwable tactical flashbang

Give the custom item to an online player with:

```text
/strobe flash give <player>
```

Right click throws it as a physical projectile. Its configurable fuse starts
on impact and defaults to 20 ticks (one second). Detonation always occurs,
even when no player is inside the blindness radius: it emits a forced vanilla
particle/sound cue, creates a real level-15 `LIGHT` block and starts one white
3D strobe pulse at maximum RGB intensity (`15/15`). A configurable flight
timeout also detonates it at its last position if an impact event never arrives.
The environmental light is independent of the camera effect. Every
pack-enabled player inside `scene-view-range` receives the same fixed world
source, so distance from the blindness radius and looking away do not prevent
the visible detonation area from lighting up.

At close range the directional camera flash reaches `EXTREME`/`200%` and the
sound uses its configured maximum volume. Flash strength, fade duration and
audio volume decrease continuously with distance until their configured
radii. Solid blocks occlude both the directional camera effect and the RGB
environmental pulse; the vanilla light engine also keeps its white fallback
on the detonation side of the wall.

The custom 64×64 model is selected only for flashbangs through reserved custom
model data on `SNOWBALL`; ordinary snowballs keep their vanilla texture. Without
Fabulous graphics, the short pulse falls back to a white vanilla `LIGHT` block.

## Languages

English is the primary and fallback language. The plugin automatically uses the
Minecraft client language for:

- English (`en_*`)
- Spanish (`es_*`)
- French (`fr_*`)
- German (`de_*`)
- Italian (`it_*`)

Editable files are exported to `plugins/StrobeLights/lang/`. On updates, new
keys and revised bundled translations are merged automatically. Values changed
by the server owner are preserved; an internal `lang/.defaults/` snapshot lets
the plugin distinguish custom text from an older bundled value. Run
`/strobe reload` after changing them. Language selection can be configured with:

```yaml
language:
  default: 'en'
  use-client-locale: true
```

## Commands

```text
/strobe
/strobe start <name|all>
/strobe stop <name|all>
/strobe toggle <name>
/strobe pulse <name|all>
/strobe tp <name>
/strobe discover [on|off|toggle]
/strobe flash give <player>
/strobe set <name> color <#RRGGBB|name>
/strobe set <name> refresh <1-1200|static>
/strobe set <name> mode <strobe|static>
/strobe set <name> brightness <0-15>
/strobe set <name> blindness <none|low|medium|high|extreme>
/strobe set <name> flashpower <0-200>
/strobe move <name>
/strobe rename <name> <new-name>
/strobe delete <name>
/strobe info <name>
/strobe list
/strobe reload
```

Permission: `strobelights.admin` (operators by default).

## Resource-pack delivery

The ZIP is embedded in the plugin JAR and is also exported to:

```text
plugins/StrobeLights/resource-pack/StrobeLights-ResourcePack-1.21.4.zip
```

Replace `serverip.com` with the public server address and expose the chosen HTTP
port over TCP:

```yaml
resource-pack:
  public-url: 'http://serverip.com:8250/strobelights/{token}.zip'
  embedded:
    port: 8250

render:
  display-view-range: 128.0

timing:
  maximum-refresh-ticks: 1200

discovery:
  enabled: true
  range: 32.0
  minimum-light-level: 10

easy-armor-stands:
  enabled: true
  auto-discovery-with-tool: true
  selection-box-size: 1.0

vanilla-fallback:
  enabled: true
  stabilize-at-or-below-refresh-ticks: 10

flashbang:
  radius: 16.0
  require-looking-at-light: true
  require-line-of-sight: true

throwable-flashbang:
  throw-velocity: 1.35
  detonation-delay-ticks: 20
  maximum-flight-ticks: 100
  detonation-cue-volume: 8.0
  detonation-cue-pitch: 1.6
  radius: 24.0
  full-effect-distance: 5.0
  effect-falloff-exponent: 1.2
  maximum-screen-flash-duration-ticks: 100
  scene-view-range: 128.0
  scene-light-duration-ticks: 60
  require-looking-at-light: true
  require-line-of-sight: true
  sound-radius: 32.0
  full-volume-distance: 5.0
  sound-falloff-exponent: 1.0
  sound-volume: 4.0
  sound-pitch: 1.0
```

`serverip.com` is only a placeholder. While it remains unchanged, version
0.8.13 prints a red translated setup warning in the console and shows a
translated title/subtitle to joining players with `strobelights.admin`.
Replace it with the server's public IP or hostname before inviting players.

The HTTP port must be open over TCP and differ from the Minecraft port.

## Notes

- RGB lighting does not use scene-color blur or per-surface depth rays, so it
  never projects silhouettes, black bands or screen-space shadows. A server
  line-of-sight check hides the complete fixed source when a solid block is
  between it and the viewer. The white fallback continues to use Minecraft's
  normal block-light engine and its wall propagation rules.
- The invisible vanilla fallback participates in Minecraft's normal light
  engine and is white by design. Clients whose rendering mods bypass the
  vanilla Fabulous post chain still receive this fallback; RGB requires that
  vanilla post chain to run. For fast strobes (10 ticks per phase or less by
  default) the white fallback stays stable because the block-light engine
  cannot reliably propagate changes that quickly; the RGB effect still
  strobes normally.
- OptiFine receives Minecraft's real white `LIGHT` fallback. Its native
  resource-pack formats do not expose a per-instance RGB light source; full RGB
  still requires a compatible post-processing or shader pipeline.
- Other resource packs that replace the transparency shader may conflict.
- Rendering cost grows with the number of active nearby lights and players.
- Fast flashes can affect photosensitive players. Test with slower refresh
  values and warn players before using strong effects.

## Build output

Plugin JARs follow this naming scheme:

```text
StrobeLights-v.<plugin-version>+mc.<minecraft-version>.jar
```

For this build: `StrobeLights-v.0.8.13+mc.1.21.4.jar`.

Light Painter attribution and MIT license are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
