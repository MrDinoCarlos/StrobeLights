# StrobeLights — Paper 1.21.11

StrobeLights provides configurable 3D RGB strobe lighting based on
[Light Painter](https://github.com/bradleyq/light_painter), revision `2364940`,
plus a per-player RGB camera flash. Normal sources do not draw fixtures, beams
or painted surfaces; throwable flashbangs and colored sky flares deliberately
emit visible vanilla effects.

## Requirements and rendering modes

- Paper 1.21.11 and Java 21.
- Players must accept the resource pack sent by the server (3D shaders and GUI icons).
- **Vanilla or OptiFine with Fabulous graphics:** full screen-space 3D RGB lighting.
- **OptiFine:** external shaderpacks must remain disabled so Minecraft can run the
  Fabulous transparency pipeline supplied by StrobeLights.
- **Fast/Fancy or renderers that replace that pipeline:** automatic white vanilla
  light fallback using invisible `LIGHT` blocks.

The 0.9 renderer reconstructs every light at its real, fixed world position.
Its technical display disables client frustum culling and always transports its
invisible marker through a stable, near-screen carrier, so the source can render
from far away, above, below and on every side without moving. No normal,
discovery or environmental light marker is attached to a player or derived from
camera yaw, pitch or movement.
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
   - RGB intensity: `15/15`;
   - RGB light size: `1.00x`;
   - group: none.
4. Select **Place light point**:
   - right click a block to attach it to that face;
   - left click/swing to place it at the player's exact current position.
5. Configure color, refresh, intensity, RGB light size, group, screen-flash
   level and flash power. Size is adjustable from `0.25x` to `4.00x` in
   `0.25x` steps.
6. Start it or send a test pulse.

The GUI also supports moving, teleporting to, renaming and deleting strobes,
global start/stop, exact RGB channels, discovery mode and forced resource-pack
redelivery. Its controls, navigation and complete RGB palette use custom
resource-pack icons through model data on `PAPER`; ordinary paper and every
other vanilla item keep their normal model. The list and strobe editor use the
compact three-row layout; only the full RGB palette expands to five rows. Back
is always in the bottom-left slot and Close in the bottom-right on submenus.
The editor groups light controls in the middle row and position, organization
and destructive controls in the bottom row. RGB size and strobe groups use
their own dedicated icons instead of reusing intensity or strobe.

Strobes can be assigned to a named group from their editor. The group screen
can start or stop every member and send a test pulse to the whole group. Group
names are reused case-insensitively and are stored with each strobe.

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
dependency. For Paper 1.21.11 use its v2 line. While a player holds the tool from
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
Each armed projectile keeps its current and immediately projected chunks loaded
until impact; the impact chunk then remains loaded through the fuse and scene
pulse. The custom item is recognized independently of its shooter, including
compatible dispenser or plugin-launched snowballs. The failsafe allows up to
60 seconds so unusual high throws can land instead of expiring in mid-air.
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
Its semitransparent outline is excluded from the technical marker signature, so
holding or throwing the item cannot create a false yellow RGB light.

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
/strobe flare give <player>
/strobe set <name> color <#RRGGBB|name>
/strobe set <name> refresh <1-1200|static>
/strobe set <name> mode <strobe|static>
/strobe set <name> brightness <0-15>
/strobe set <name> expansion <0.25-4.00|25%-400%>
/strobe set <name> group <name|none>
/strobe set <name> blindness <none|low|medium|high|extreme>
/strobe set <name> flashpower <0-200>
/strobe group list
/strobe group <name> <start|stop|toggle|pulse>
/strobe move <name>
/strobe rename <name> <new-name>
/strobe delete <name>
/strobe info <name>
/strobe list
/strobe reload
```

Permission: `strobelights.admin` (operators by default).

The flare command gives a reusable launcher. Left click it to open the menu of
16 colored cartridges. Selecting one plays a three-stage mechanical reload;
right click then launches that color with a short flare-pistol report. The projectile and burning
core form one smooth emissive flare with a white-hot center and custom halo,
without Minecraft particle clouds. At the apex it expands into a 40-second flare that
keeps its horizontal momentum, curves under gravity and wind, and continues lighting
nearby terrain while descending or resting on the ground. Its aerial level-15 RGB
source is accompanied by a moving light pool projected onto the first solid surface
below it. The launcher model itself is excluded from the technical light signature,
so holding the pistol cannot illuminate the world. Looking directly at the core refreshes a controlled
glare; looking away leaves an afterimage that can fade for up to 2.5 seconds. A new cartridge must be selected before
every shot. The launcher uses a neutral blaze rod carrier with its own
resource-pack model, so creative mode cannot load or shoot vanilla arrows.
Launchers created by 0.10.0 upgrade on their next click.

## Resource-pack delivery

The ZIP is embedded in the plugin JAR and is also exported to:

```text
plugins/StrobeLights/resource-pack/StrobeLights-ResourcePack-1.21.11.zip
```

Replace `serverip.com` with the public server address and expose the chosen HTTP
port over TCP:

```yaml
resource-pack:
  nexo-integration:
    enabled: true
    regeneration-delay-ticks: 40
    fallback-delay-ticks: 600
  public-url: 'http://serverip.com:8250/strobelights/{token}.zip'
  embedded:
    port: 8250

render:
  display-view-range: 192.0

timing:
  maximum-refresh-ticks: 1200

limits:
  maximum-strobes: 256
  maximum-name-length: 32
  maximum-group-name-length: 32

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

flashbang:
  radius: 16.0
  require-looking-at-light: true

throwable-flashbang:
  throw-velocity: 1.35
  detonation-delay-ticks: 20
  maximum-flight-ticks: 1200
  detonation-cue-volume: 8.0
  detonation-cue-pitch: 1.6
  radius: 24.0
  full-effect-distance: 5.0
  effect-falloff-exponent: 1.2
  maximum-screen-flash-duration-ticks: 100
  scene-view-range: 128.0
  scene-light-duration-ticks: 60
  require-looking-at-light: true
  sound-radius: 32.0
  full-volume-distance: 5.0
  sound-falloff-exponent: 1.0
  sound-volume: 4.0
  sound-pitch: 1.0

flare:
  reload-required: true
  load-duration-ticks: 34
  launch-speed: 1.7
  vertical-bias: 0.65
  launch-height: 28.0
  maximum-flight-ticks: 200
  flight-light-level: 15
  flight-light-expansion: 2.0
  visual:
    flight-size: 0.8
    burn-size: 3.2
    view-range: 192.0
  explosion:
    burn-duration-ticks: 800
    ignition-velocity-retention: 0.45
    minimum-horizontal-speed: 0.035
    horizontal-drag: 0.992
    gravity: 0.0035
    terminal-fall-speed: 0.06
    wind-acceleration: 0.00018
    scene-light-duration-ticks: 800
    scene-light-level: 15
    scene-light-expansion: 4.0
    scene-view-range: 192.0
    ground-projection:
      enabled: true
      maximum-drop-distance: 128.0
      light-level: 15
      expansion: 2.5
    screen-flash:
      enabled: true
      radius: 72.0
      minimum-view-dot: 0.72
      maximum-duration-ticks: 50
      strength-percent: 85
```

When Nexo is enabled, StrobeLights adds this ZIP at the final priority of Nexo's
post-generation event and restores Nexo's original pack metadata. Immediately
before Nexo uploads or hosts the result, StrobeLights checks every RGB shader in
the actual client ZIP and restores any file changed by another pack,
obfuscation or PackSquash. It also invalidates Nexo SELFHOST's in-memory ZIP
cache after regeneration. The verified combined ZIP is exported beside the
standalone pack. Nexo remains responsible for delivery, and `/strobe pack` asks
Nexo to resend that verified pack.

On a proxy network, configure every backend to generate the same combined pack.
For Velocity, Nexo recommends NexoProxy so changing backend does not dispatch a
duplicate pack. See Nexo's
[resource-pack configuration](https://github.com/Nexo-MC/Nexo-Documentation/blob/master/configuration/resourcepack/README.md).

`serverip.com` is only a placeholder. While it remains unchanged, version
0.10.12 prints a red translated setup warning in the console and shows a
translated title/subtitle to joining players with `strobelights.admin`.
Replace it with the server's public IP or hostname before inviting players.

The HTTP port must be open over TCP and differ from the Minecraft port.
`{token}` is replaced with the ZIP's SHA-1 in both embedded and external modes.
Backends running the same StrobeLights build therefore resolve the same immutable
URL; when `embedded.enabled` is `false`, the external host must serve that path.

## Notes

- RGB sources remain active for every player inside render distance regardless
  of camera position or direct line of sight. The shader smoothly accumulates
  opaque geometry between a visible surface and the source so RGB does not leak
  through walls; glass follows vanilla transparent behavior.
- Per-strobe RGB size scales the physical light radius from `0.25x` to `4.00x`
  in Fabulous mode. Fast/Fancy uses Minecraft's white `LIGHT` fallback, whose
  propagation radius is controlled by the vanilla light engine and therefore
  cannot reproduce the custom RGB size.
- The invisible vanilla fallback participates in Minecraft's normal light
  engine and is white by design. Clients whose rendering mods bypass the
  vanilla Fabulous post chain still receive this fallback; RGB requires that
  vanilla post chain to run. The fallback is created and removed on every
  strobe phase, including rapid white strobes.
- OptiFine can render the full RGB effect when graphics are set to Fabulous,
  no external OptiFine shaderpack is active and the StrobeLights resource pack
  is loaded. Its zoom keeps the same physical light radius. An external
  shaderpack can replace the transparency pipeline;
  affected clients then retain Minecraft's real white `LIGHT` fallback.
- The OptiFine carrier contract is protected by automated tests: marker
  detection uses the dedicated neutral texture with an alpha-relative RGB
  check, never an absolute near-white threshold or fog/hand heuristics. The
  complete RGB, size and zoom payload travels through the color attachment
  preserved by OptiFine instead of relying on depth-buffer metadata. Its
  low-intensity micro-carrier avoids solid colored dots when Fast or Fancy
  graphics render that attachment directly.
- After the pack loads, clients whose reported brand explicitly identifies
  OptiFine can receive a short translated reminder of those settings.
  Standalone OptiFine normally reports itself as vanilla, so it receives the
  generic Fabulous/shaderpack hint instead.
- Minecraft does not send its Fast/Fancy/Fabulous option to the server. Other
  clients therefore receive a short conditional chat hint (`Missing full RGB?`)
  rather than an inaccurate claim that their graphics mode was detected. Both
  notices appear only once per connection. They are disabled by default; set
  `client-compatibility-notices.enabled: true` to enable them. Their text is
  translated through the bundled `lang/*.yml` files.
- Other resource packs that replace the transparency shader may conflict.
- Rendering cost grows with the number of active nearby lights and players.
- Fast flashes can affect photosensitive players. Test with slower refresh
  values and warn players before using strong effects.

## Build output

Plugin JARs follow this naming scheme:

```text
StrobeLights-v.<plugin-version>+mc.<minecraft-version>.jar
```

For this build: `StrobeLights-v.0.10.12+mc.1.21.11.jar`.

Light Painter attribution and MIT license are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
