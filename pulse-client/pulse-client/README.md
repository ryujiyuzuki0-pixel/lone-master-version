# Lone Client (formerly Pulse Client)

An original Fabric PvP HUD client for **Minecraft 1.21.11** (Java 21). Press **Right Shift** in game.

## Build
```
./gradlew build
```
The jar is `build/libs/pulse-client-1.0.0.jar` (ignore the `-sources` jar).
No local setup? Push this folder to GitHub: the included workflow builds the jar and attaches it as an artifact.

## Install
Put the jar in `.minecraft/mods/` with Fabric Loader 0.19.2+ and Fabric API 0.141.3+1.21.11.

## What's new: Lone Client UI

* **Title screen**: snowy Minecraft backdrop (slow pan + falling snow), the Lone Client logo, and buttons
  SinglePlayer, Multiplayer, Cosmetic, Mods, Settings and Lone Mods. **Settings** opens Minecraft's own options
  (video, controls, sound...); the Lone Client settings are under Lone Mods > Settings. Your own skin stands on the
  right, read from the account you are signed in with (a Wardrobe skin you "wear locally" shows instead; "Use my
  account skin" switches back), with a Wardrobe button (capes). **Mods** lists the mods installed in your game (search, details). **Lone Mods** opens the
  client's built-in modules. Up/Down/Enter work too. Turn it off in Lone Mods > Settings > Title screen.
* **Lone Mods menu** (title screen "Lone Mods", or Right Shift in game): sidebar with Favorites / All / HUD / Visual /
  Gameplay / Utility, Settings, Friends, Cosmetics, HUD Editor and profiles. Search with Ctrl+F.
  Click a card for its settings, the switch to toggle it, the star to favorite it.
* **Every module has its own settings**: sliders (mouse wheel works, right click resets), choices,
  full color pickers (hue / saturation / brightness / opacity, presets, follow-theme and rainbow modes),
  keybinds, and per-module "Reset". Every HUD element also gets scale, background, corner radius,
  accent line, text / label colors, text shadow and exact X / Y position.
* **Skins (Wardrobe)**: 8 built-in skins, or add your own 64x64 PNG (paste a path, drag a file onto the window, or drop it in
  the `lone-skins` folder in your game directory). "Apply to my account" uploads it to Mojang's official skin API,
  so every player sees it, with or without this client (they see it after reloading you, e.g. rejoining). Needs a
  Microsoft-signed-in account. "Wear locally" only changes what you see. Capes stay local (Mojang controls real capes).
* **Profiles**: save separate setups (sidebar bottom-left). Friends: simple local list.

## Modules (36)
HUD: FPS, Ping, Coordinates, Clock, Keystrokes, Armor, Potion Effects, Totem Count, Item Counters,
Cooldowns, Inventory, Watermark. Gameplay: CPS, Combo, Reach, Target HUD, Toggle Sprint, Jump Reset Counter.
Visual: Custom Crosshair, Low Health Vignette, Client Theme, Cape. Utility: Zoom, Waypoints,
FPS Boost, Now Playing (Spotify), Death Info, Drop Prevention, Horse Stats. Visual also has Custom Fog, Custom Brightness and Item Physics. Gameplay also has Stun Detector. Utility also has Shulker Tooltip. HUD also has Custom F3 and UHC Overlay.

The in-game cape hook is wired through a mixin; textures are in `assets/pulse/textures/cape/`.
"Edit HUD" lets you drag elements. Settings save to `config/pulse.json` (profiles: `config/pulse-profile-NAME.json`).

## Spotify
1. Create an app at https://developer.spotify.com/dashboard
2. Add the redirect URI `http://127.0.0.1:43019/callback`
3. In game: `/pulse spotify id <client id>` then `/pulse spotify login`
Play/pause/next need Spotify Premium. The refresh token is stored in `config/pulse-spotify.json`.

## Multiple Minecraft versions

One source tree (`src/`) builds every version listed in `settings.gradle`, using
[Stonecutter](https://stonecutter.kikugie.dev/). Per-version values live in
`versions/<mc>/gradle.properties`.

Build all versions: `./gradlew build` (jars land in `versions/<mc>/build/libs`).

Currently builds: 1.21.9, 1.21.10, 1.21.11. Versions before 1.21.9 need extra branches
(the `KeyEvent`/`MouseButtonEvent` input API doesn't exist there).

To add a version:
1. Create `versions/<mc>/gradle.properties` with `minecraft_version`, `minecraft_dep`,
   `fabric_version` and `java_version` (copy `versions/1.21.11` and edit).
2. Add the version to `versions '...'` in `settings.gradle`.
3. Where the game API differs, wrap the code in Stonecutter conditionals, e.g.
   `//? if >=1.21.9 { ... //?} else { ... }`.

## Viewmodel, Free Look and FOV Changer

* **Viewmodel** (Visual, off by default): hand / held item position (X, Y, Z), rotation (X, Y, Z) and scale, for both hands, the
  main hand or the off hand. Swing animation (Vanilla, Custom speed, No swing) and an Equip animation switch. Live, saved in the
  config. Purely visual. Hook: `ItemInHandRendererMixin` (`require = 0`).
* **Free Look** (Gameplay, off by default): hold (or toggle) the key (default Left Alt) and the mouse turns the camera only. The
  player's real rotation is never written, so movement and attacks stay where you aim. Releasing swings the camera back over the
  Return time. Hooks: `EntityMixin` (`Entity#turn`) and `CameraMixin` (`Camera#setRotation`), both `require = 0`; if either does not
  apply the module never activates and says so once.
* **FOV Changer** (Visual, off by default): sets the FOV (30-120) the game renders with. Your own FOV setting and options.txt are
  never changed. Sprint / speed / bow effects still apply on top (scaled by your vanilla "FOV Effects" slider). Zoom wins while it
  is zooming; the Video Settings screen shows your real value.

Custom Brightness and the FOV Changer answer reads of the vanilla options through `OptionInstanceMixin`; `OptionsMixin` makes
sure `Options#save` never writes their values into options.txt.

## Extra modules

Inventory HUD, Cooldowns (pearls/gapples/chorus/wind charges), Item Counters, Waypoints and
Watermark. All are off by default; enable them in the menu (Right Shift).
Waypoints: `/pulse waypoint add <name>`, `remove <name>`, `list`, `clear`.

## FPS Boost

Performance tab -> **FPS Boost** (on by default). It lowers the vanilla video settings that cost the most frames and
puts yours back when you turn it off or close the game. Pick a preset (Balanced, Max FPS, Potato) or tune each row:
uncapped framerate, VSync, particles, clouds, entity shadows, entity distance, biome blend and a render distance cap.

- It only lowers values. A setting you already run lower is never raised.
- If you change a setting yourself in the vanilla menu while it is on, that setting is left alone when you turn it off.
- Your originals are kept in `config/pulse-fpsboost.json` while it is active, so a crash can't leave you on boosted values.
- It only changes video settings (no mixins). For faster chunk and entity rendering as well, run it next to Sodium and Lithium.

## Capes

Eight bundled capes (Pulse, Japan, Heartbeat, Nightfall, Breaker, Aurora, Ember, Frost). Pick one in the
Cosmetics tab; it shows on your own player in game (other players don't see it). A mixin
(`AbstractClientPlayerMixin`) swaps the cape into your skin. Textures are 64x32 PNGs in
`assets/pulse/textures/cape/`; add your own by dropping a PNG there and adding its name to `CapeArt`.

## Death Info and Custom F3

**Death Info** (Utility, on by default) records each death: XYZ, dimension, time, cause (best effort: the client does not
always know the damage source), last health and server. History is kept in `config/pulse-deaths.json`. The HUD shows the
latest death; open the full list from the module's settings (**Open**) or bind **Open Death Info key**. From there you can
copy coordinates, create a Waypoint (named `Death-MMdd-HHmm`, shown by the Waypoints module) or delete entries.

**Custom F3** (HUD, off by default) replaces the F3 text. Tap F3 to toggle it (or set Visible to Always). Every line has
its own toggle; Compact/Detailed modes; four layouts; text size, colours, background and position like any HUD element.
Values refresh every N ticks (default 5), not every frame. TPS is an estimate from the server's time corrections
(a few seconds to warm up). F3+key debug shortcuts still work and do not toggle it.

## Drop Prevention and Custom Fog

**Drop Prevention** (Utility, on by default) guards the drop key (Q / Ctrl+Q) and drops inside inventory screens
(Q over a slot, clicking outside the window with an item on the cursor). Moving, swapping and shift-clicking are never touched.
An item is protected if it is on the **Protected** list; otherwise, unless it is on the **Allowed** list, if it matches an enabled
category (enchanted, weapons, tools, armor, valuables, totems/elytra/shields, shulker boxes, renamed items).
List entries are an id (`minecraft:elytra`), a short name (`elytra`) or a wildcard (`netherite*`, `*_shulker_box`), which doubles as a
custom category. Edit them in the module's **Open** screen or with **Protect held item** / **Always allow held item**
(stored in `config/pulse-dropprotect.json`). Modes: **Confirm** (prompt screen), **Hold to drop** (hold Q for N seconds; mouse drops
fall back to the prompt) and **Block**. Hold the **Override key** (default Left Alt) to drop anything.

**Custom Fog** (Visual, off by default) sets fog distance (0 = render distance), density, and color, with presets, three user
slots (`config/pulse-fogpresets.json`), optional per-dimension settings (Overworld / Nether / End) and smooth transitions.
Water, lava, blindness and darkness fog are left alone unless you turn that off. The fog color changes the fog blend, not the
sky clear color.

## Horse Stats and Jump Reset Counter

**Horse Stats** (Utility, off by default) shows the horse, donkey, mule, skeleton horse or zombie horse you are riding or
looking at (within your interaction range). Speed, jump strength and max health are the entity's real attributes (base values,
so potions do not skew them); current health is live. Speed is shown in blocks/s (attribute x 42.1578) or as the raw attribute.
**Jump height is an estimate** for a full-charge jump on flat ground without Jump Boost: pick the *Community fit* polynomial or a
*Physics sim* (gravity 0.08, drag 0.98) in the settings; they can differ by up to about half a block. Rating bars use the vanilla
horse ranges and only appear for horses. **Compare with** best seen, previous horse or a pinned horse (Pin key or the
**Pin** button; it pins the last horse you inspected); green/red deltas show who is better. Horses you keep in view for half a second are
remembered in `config/pulse-horses.json` (history size, optional history rows on the HUD, Clear).

**Jump Reset Counter** (Gameplay, off by default) is a session tracker built around each hit you take, measured in ticks.
A hit counts when your hurt timer rises, you were on the ground the tick before, and (by default) a player caused it.
You score a **success** if you actually jumped within the *Success window* (the hit tick plus 1 by default). A **failed attempt** is
a jump key press in the *Attempt window* without a jump in time ("Late +Nt") or a jump the game did not register (jump cooldown after landing).
Hitting the ground-hit without pressing jump is "No jump": a hit taken, not a fail. Jumps away from hits never count, and hits taken
in the air are ignored. Success rate is of attempts or of hits taken (setting). Results are **confirmed** when the jump was observed in
the game's own jump routine (`LivingEntityMixin`) and the damage source named a player; otherwise they are **estimated** and shown
with `~`. The HUD flashes green/red and a short result text appears under the crosshair. Statistics are per session; reset them with the
button, a key, or on world change.

## Stun Detector and Custom Brightness

**Stun Detector** (Gameplay, off by default) tells you whether your stun worked: **SUCCESS**, **FAILED** or **UNKNOWN**, with
counts, accuracy (decided stuns only) and a HUD card plus a result popup near the crosshair. "Stun" differs between servers, so
pick a **Stun type**:
- *Shield disable (axe)*: you hit a blocking player. **Confirmed** success is the game's own shield-disabled event for that player.
  FAILED means the shield stayed up the whole window with no event. A shield that dropped without the event is UNKNOWN, or an
  estimated "~" success if *Accept estimated results* is on. Hits on players who are not blocking are ignored, not counted as fails.
- *Movement (knockback)*: needs a confirmed hit, then measures how far the target travels after knockback settles. Frozen = success
  (estimated), clearly moving = failed, in between = UNKNOWN.
Sensitivity (Strict / Normal / Lenient), detection window, settle time and ping grace are adjustable. Stats reset with the Reset button or key.

**Custom Brightness** (Visual, off by default) has three modes: **Off** (vanilla), **Full Bright**, and **Custom** (a slider from
0% "Moody" through 100% vanilla "Bright" up to 1000%). Presets, optional per-dimension settings, smooth fade time and a
Quick toggle key. Your vanilla Brightness slider and options.txt are never changed. Turning the module off returns to your own setting.

## Shulker Tooltip and Item Physics

**Shulker Tooltip** (Utility): hover a shulker box in any inventory or container screen to see its contents as an
inventory-style grid with item icons, stack amounts, enchant glints and custom names. Works for every shulker color
(the border and slots pick up the box color). Settings: scale, columns, slot gap, padding, title / slots-used line,
custom-named item list, colors, pop-in animation, and an optional "only while key held" peek key. Empty boxes keep the
vanilla tooltip. It only shows what the server already sent to your client.

**Item Physics** (Visual): dropped items get adjustable rotation (Spin with speed and direction, Sway, or Still),
bobbing (on/off, height, speed), tilt and a ground offset. Visual only: the item entity, pickup and despawn are
never changed. Rotation uses the frame-interpolated age, so it stays smooth at any FPS.

Both use `require = 0` mixins: if a Minecraft version moves the hooked code the client still starts and the vanilla
visuals are used.

## UHC Overlay

**UHC Overlay** (HUD, off by default) is one card with the numbers a UHC player keeps checking: **health**, **absorption**,
**armor**, **food** (and optionally saturation), **coordinates**, **FPS**, **ping**, a **timer**, **biome** and **nearby players**.
Every row has its own toggle, plus the usual HUD options (scale, background, colors, text shadow, exact X / Y position).
- *Display*: layout (Classic, Split, Two columns), Compact or Detailed labels, health in **Hearts** or **HP**, dynamic colors
  (health, food, FPS, ping and nearby count turn yellow / red), and the update interval (default 4 ticks, not every frame).
- *Absorption* is shown in gold (its color is editable) and hidden while it is zero, unless you turn that off.
- *Timer*: **Since joining** (restarts when you join a server or world, not when you change dimension) or a manual
  **Stopwatch** with a Start / pause key, a Reset key and buttons in the module's settings.
- *Nearby*: counts players inside a radius (default 64 blocks) and shows the nearest one with distance, compass direction and health.
  It can also count hostile mobs and ignore people on your Friends list (teammates). The entity scan runs on its own slower
  interval (default 10 ticks) and only looks at entities your client already has loaded.
- It only reads what the server sent to your client. Other players' health is whatever the server reports, so on servers that hide it
  you will see what the server shows. Place it in the HUD editor; it shows sample values there even outside a world.
