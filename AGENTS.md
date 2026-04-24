# AGENTS.md

Client-only Forge 1.12.2 mod (`sf4debug`) that exposes Minecraft
client state **and the ability to drive the player** over a loopback
HTTP port for external debugging. See `README.md` for user-facing
docs.

## What this directory is

A ForgeGradle 3 userdev project pinned to:

- Minecraft 1.12.2
- Forge 14.23.5.2860 (matches `../../2026-04-23-skyfactory/forge-1.12.2-14.23.5.2860.jar`)
- MCP mappings `snapshot_20171003-1.12`
- Java 8 (Temurin 1.8.0_482 in `./jdk8/`, same build as the server's `../2026-04-23-skyfactory/jre8/`)
- Gradle 4.9 (wrapper from the Forge 1.12.2 MDK, do not upgrade)

## Agent-relevant invariants

- **Always use the bundled JDK.** `export JAVA_HOME="$(pwd)/jdk8"`.
  System Java (17/25) will fail on legacy ASM.
- **Do not upgrade Gradle.** The wrapper is 4.9; ForgeGradle 3.x
  rejects Gradle 5+ with cryptic errors.
- **Do not upgrade ForgeGradle.** FG4+ targets 1.14+.
- **Do not move java sources out of `com.telethon.sf4debug`** without
  also updating `@Mod.modid` and `mcmod.info`.
- **Do not remove `clientSideOnly = true`.** The server must never
  load this mod — see the server AGENTS.md rule about client-only
  jars hard-crashing FML.
- **Do not expose the HTTP port on non-loopback.** Default bind is
  `127.0.0.1`; there is no authentication and the port now includes
  routes that move, attack, place blocks, click inventory slots, and
  send chat on behalf of the user.
- **Remote-access consideration.** Callers may reach the port from
  another machine (via SSH tunnel, bind-address override, etc.),
  so **no response may ever include a local filesystem path or
  require same-filesystem access.** `/screenshot` in particular
  returns PNG inline (base64 or raw `image/png` body), never a file
  path, and never writes to disk.
- **Do not touch world/player/GUI state off the client thread.** All
  routes go through `Minecraft.addScheduledTask` via
  `DebugHttpServer#runOnClientThread`. Adding a new route? Follow the
  same pattern.

## File layout

- `SF4Debug.java` — `@Mod` entry. Registers `TickInput` then starts
  the HTTP server. Client-only; all handlers are `@SideOnly(CLIENT)`.
- `DebugHttpServer.java` — HTTP server, route table, thread
  marshalling, and every read-only snapshot route
  (`/player`, `/inventory`, `/chunks`, `/entities`, `/look`, etc.).
  Exposes package-private helpers `runOnClientThread`, `itemStackJson`,
  `parseInt`, `parseDouble` used by the other route classes.
- `BlockRoutes.java` — `/block`, `/blocks`, `/visible` (raytrace-
  sampled visible blocks in the current FOV).
- `ActionRoutes.java` — every mutating endpoint: movement
  (`/walk`, `/jump`, …), aim (`/aim`, `/lookAt`), interact (`/use`,
  `/attack`, `/useItem`, `/attackEntity`, `/useEntity`, `/click`,
  `/drop`, `/selectSlot`, `/swap`, `/swing`), GUI (`/close`,
  `/openInventory`), chat (`/chat`), and `/respawn`.
- `GuiRoutes.java` — drives modded / complex GUIs by dispatching
  `GuiScreen#mouseClicked` / `keyTyped` / `mouseClickMove` via
  reflection (SRG first, MCP fallback): `/guiClick`, `/guiRelease`,
  `/guiDrag`, `/guiKey`, `/guiType`, `/guiScroll`, `/guiButton`. Also
  exports `augmentScreenSnapshot(screen, out)` called from `/screen`
  to add `bounds`, `buttons`, `textFields`, `classHierarchy`, and
  (for AE2 `GuiMEMonitorable` descendants) `aeMeTerminal` — the
  `ItemRepo` state + `GuiScrollbar` + per-`InternalSlotME` screen
  coordinates. AE2 is discovered by class name (`appeng.client.me.ItemRepo`,
  `appeng.client.gui.AEBaseGui`) and accessed entirely reflectively —
  there is **no** compile-time dependency on AE2. The `/me` endpoint
  is the paginated AE2-only entry point. Since 0.4.0 the mouse
  handlers also accept `shift=1`/`ctrl=1`/`alt=1` query params that
  reflectively flip LWJGL's private `Keyboard.keyDownBuffer` for the
  duration of the dispatched click, so modded widgets that branch on
  `GuiScreen.isShiftKeyDown()` / `Keyboard.isKeyDown(...)` see the
  modifier as held.
- `EventStream.java` — 2048-slot ring buffer of monotonic events plus
  a big `@SubscribeEvent` bag: chat sent/received, gui open/close,
  player damage/death/respawn, item/xp pickup, chunk load/unload,
  sound events, and a per-tick differ that emits on changes to
  health/food/xp/weather/time-of-day. `init()` is called once from
  `SF4Debug.init` before the HTTP server starts. Thread-safe; the
  long-poll in `EventRoutes` blocks on a monitor lock, never on the
  client thread.
- `EventRoutes.java` — `/events` HTTP handler. Registered directly on
  the `HttpServer` (not via `DebugHttpServer.wrap`) because long-poll
  must not hold the client thread.
- `ObserveRoutes.java` — HUD readback that a real player _sees_:
  `/bossbars`, `/scoreboard`, `/chatlog` (scrollback), `/overlay`
  (action-bar + title + subtitle + toasts) and `/world` (weather,
  time, moon phase, biome, light at player feet). Also exports
  `augmentPlayer`/`augmentLook`/`augmentState` static helpers wired
  into `DebugHttpServer.snapshotPlayer()` / `.snapshotLook()` /
  `.snapshotAll()` so the existing snapshot routes gain the same
  new keys transparently. (0.5.0 renamed `/chat` reader to `/chatlog`
  so it no longer collides with the `/chat` sender in `ActionRoutes`.)
- `TabCompleteRoutes.java` — `/tabcomplete` endpoint. Sends
  `CPacketTabComplete` and installs an ephemeral Netty handler
  in front of the vanilla net handler to capture the next
  `SPacketTabComplete`, with a 2s timeout.
- `MediaRoutes.java` — `/screenshot`. Reads the GL framebuffer on
  the client thread via `ScreenShotHelper.createScreenshot(w, h, fb)`
  (BufferedImage, no disk I/O) and returns PNG inline either as
  base64 JSON or as a raw `image/png` binary body. **Never writes
  to disk and never returns a local filesystem path** — the port
  may be reached from another machine, so same-filesystem semantics
  are not assumed.
- `HelperRoutes.java` — composite shortcuts that cut 5 round-trips
  down to 1: `/moveItem`, `/findItem`, `/placeItem`, `/dropSlot`,
  `/wait`, `/eatUntilFull`, `/signSet`, `/anvilRename`. `/wait` and
  the binary-screenshot path both bypass `DebugHttpServer.wrap`
  because one blocks the HTTP thread (not the client thread) and
  the other writes raw bytes.
- `TickInput.java` — a `@SubscribeEvent` on
  `TickEvent.ClientTickEvent.START`. Movement / `hold*` endpoints
  register a `(KeyBinding, remainingTicks)` entry and the tick handler
  re-asserts `KeyBinding.setKeyBindState` each tick until the counter
  decrements to zero. This is the only way the player's real
  movement input and anti-cheats see normal key-press input.
  Owns three mutually-exclusive aim plans driven from the HTTP thread
  and walked by the tick handler:
  - `SmoothAimPlan` from `/aim?ticks=N` / `/lookAt?ticks=N` for
    single-target smooth interpolation.
  - `AimPathPlan` from `/aimPath?legs=y:p:t,y:p:t,...` for
    multi-waypoint paths.
  - `EntityTrackPlan` from `/aimAt.entity?id=&ticks=&ease=&eye=` for
    live-follow targeting.
  Current plan state is introspected via `snapshotAim()` (exposed as
  `/aimStatus`), and `cancelAim()` clears every plan.
- `PerceptionRoutes.java` — deep-perception reads (0.5.0): `/particles`
  (radius/limit sample of the currently rendered particle manager),
  `/sounds.recent` (ring buffer populated by a `@SubscribeEvent` on
  `PlaySoundEvent`), `/cooldown` (attack cooldown + per-item use
  cooldowns + swing progress), `/miningStatus` (`PlayerControllerMP`
  mining progress via reflection), `/entity?id=N` (detailed single-
  entity state — health, equipment, potion effects, passengers,
  riding), `/camera` (1st/3rd person, FOV, render distance, view
  bobbing, smooth camera, difficulty). `init()` registers the sound
  subscriber; `register(server)` registers the six contexts.
- `ContainerRoutes.java` — typed container readers and macros (0.5.0):
  `/furnace` (input/fuel/output + burn/cook timers from
  `ContainerFurnace` mirror fields, falling back to
  `IInventory.getField`), `/brewing` (3-bottle + ingredient + fuel +
  brew progress), `/enchant` (3 offered levels + clue enchant ids
  + xpSeed), `/anvil` (cost + renamed string), `/merchant` (villager
  trade list via reflection on the merchant), `/beacon` (levels +
  primary/secondary potion effects via `IInventory.getField`),
  `/book.write?pages=...|...&sign=&title=` (writes `GuiScreenBook`
  `bookPages` NBT list reflectively, optionally signs),
  `/creativeTab?tab=N` (calls `GuiContainerCreative.setCurrentCreativeTab`),
  `/clipboard?op=get|set&text=`, `/fishing.state` (`EntityPlayer.fishEntity`
  bobber pos + motion + `ticksCatchable`).
- `WorkflowRoutes.java` — continuity and workflow endpoints (0.5.0):
  `/batch` (POST body: JSON array of `{path, query}` objects; each
  is re-invoked over loopback HTTP so the handler table stays the
  single source of truth), `/diff?keys=player,look,world&reset=0`
  (diffs the same snapshot sections against the previous /diff
  call's cached snapshot), `/tick` (client-tick counter +
  `Minecraft.timer.renderPartialTicks` via reflection), `/input?dx=&dy=&wheel=`
  (raw mouse delta through `EntityPlayerSP.turn`, the same entry
  point vanilla `MouseHelper` uses), `/mouse?x=&y=&button=&action=`
  (GUI-aware mouse wrapper; in-world maps LMB/RMB to
  attack/use keybinds), `/aimStatus` (`TickInput.snapshotAim()`),
  `/aimPath?legs=y:p:t,y:p:t,...`, `/aimAt.entity?id=&ticks=&ease=&eye=`,
  `/cancel?tag=all|aim|holds|...` (cancels aim/holds and sets
  per-tag flags registered via `registerCancelable(String)`).
  Also owns the client-tick counter via an inner `TickCounter`
  `@SubscribeEvent` registered in `register(server)`. `/batch` and
  `/input` bypass `DebugHttpServer.wrap` — `/batch` runs on the HTTP
  thread because each sub-request hops onto the client thread via its
  own handler, and the 2s client-thread budget would otherwise cap
  the batch size to 2s / 2s-per-request = 1.
- `RecipeRoutes.java` — JEI-backed recipe introspection (0.6.0):
  `/recipes.status`, `/recipes.categories`, `/recipes.list`,
  `/recipes.lookup` (scan-all-categories search by item registry
  name with `mode=input|output|both` and a `maxScan` cap to bound
  SF4's huge recipe table), `/recipes.get`, `/recipes.catalysts`.
  JEI is accessed **entirely reflectively** via
  `mezz.jei.Internal.getRuntime()` → `IJeiRuntime.getRecipeRegistry()`
  → `IRecipeRegistry` — there is no compile-time JEI dep, same
  pattern as the AE2 integration in `GuiRoutes.augmentScreenSnapshot`.
  Ingredients are read via `IRecipeWrapper.getIngredients(IIngredients)`
  where `IIngredients` is instantiated from the concrete
  `mezz.jei.ingredients.Ingredients` no-arg constructor, and both
  `ItemStack` and `FluidStack` slot lists are extracted via the
  deprecated-but-still-present `getInputs(Class)`/`getOutputs(Class)`
  API (compatible across JEI 4.12+). When JEI's runtime is not yet
  available (before `FMLLoadCompleteEvent`) or JEI isn't installed,
  the categories/list endpoints fall back to vanilla
  `CraftingManager.REGISTRY` and `FurnaceRecipes.instance()`. JEI
  recipes are returned as `{index, wrapperClass, inputs, outputs}`
  where each input/output slot is itself a list of alternatives
  (ore-dict / tag rotation). Vanilla recipe category UIDs:
  `minecraft.crafting`, `minecraft.smelting`.
- `PauseRoutes.java` — client-option toggles (0.6.1). Owns the
  `noPauseOnMinimize` flag and exposes `GET /options` +
  `GET /noPauseOnMinimize?enable=0|1` to read/set it. When on, a
  `@SubscribeEvent(priority = HIGHEST)` on `GuiOpenEvent` cancels any
  incoming `GuiIngameMenu` whenever `Display.isActive()` is `false`,
  so alt-tabbing / minimizing the window no longer dumps the player
  into the pause menu (and, transitively, no longer sets
  `Minecraft.isGamePaused` in singleplayer). A matching
  `ClientTickEvent.END` subscriber closes any `GuiIngameMenu` that
  slipped through a non-`displayGuiScreen` code path while the window
  is still inactive. Startup default is read from
  `-Dsf4debug.noPauseOnMinimize=1`. `init()` registers the Forge
  subscribers; `register(server)` registers the two HTTP contexts.
- `TickRateRoutes.java` — runtime tick-rate configuration (0.6.2).
  Owns `AtomicInteger TARGET_TPS` (default 20) and throttles the
  integrated-server thread via a `@SubscribeEvent` on
  `TickEvent.ServerTickEvent`: `Phase.START` captures the tick's
  wall-clock start time, `Phase.END` blocks via `Thread.sleep` until
  `1000 / targetTps` ms have elapsed. Vanilla's `while (i > 50L)`
  catch-up loop inside `MinecraftServer.run()` still fires but each
  catch-up tick itself takes the new period, so long-run effective
  TPS converges to the target (the "Can't keep up!" warning may log
  once per 15 s — expected). To keep the client render loop in
  sync, `applyTickRate(tps)` also reflectively writes
  `Minecraft.timer.tickLength` (SRG `field_194149_e` / MCP
  `tickLength`) to `1000f / tps`; without this step the client
  would still advance at 20 TPS and motion interpolation would
  desync from the throttled server. Exposes `GET /tickrate?rate=N`
  and is the back end of the `/tickrate` command in
  `TickRateCommand.java`. **Single-player only** — `applyTickRate`
  short-circuits when `Minecraft.isIntegratedServerRunning()` is
  `false` because this mod is `clientSideOnly` and has no way to
  reach a remote server's tick loop. `init()` registers the
  server-tick subscriber; `register(server)` registers the HTTP
  context.
- `TickRateCommand.java` — client-side `/tickrate` slash command
  (0.6.2). Registered via
  `ClientCommandHandler.instance.registerCommand(new TickRateCommand())`
  from `SF4Debug.init`. Chat input is intercepted on the client
  before it's forwarded to the server. Usage:
  `/tickrate` (print), `/tickrate <1-100>` (set), `/tickrate reset`
  (back to 20). Overrides `getRequiredPermissionLevel()` to `0` and
  `checkPermission(server, sender)` to `true` so the command works
  in any single-player session, including worlds created with
  "Cheats: OFF" where the default OP-level-4 check in
  `CommandBase.checkPermission` would otherwise reject the call
  (`ClientCommandHandler.getServer()` returns the non-null
  integrated server in SP, so the vanilla `server == null` bypass
  doesn't kick in). Aliased as `/sf4tickrate` in case another mod
  registers `/tickrate` first.

## How movement is implemented

We do **not** touch `motionX/Y/Z` or `movementInput.moveForward`
directly — `MovementInputFromOptions` overwrites those each tick
and raw velocity changes get rejected by server anti-cheats. Instead,
`TickInput` re-asserts the real vanilla keybinds as pressed for the
requested number of client ticks. The server only ever sees normal
player-movement packets. A real user pressing Esc or opening a GUI
will still override us, which is usually the correct behavior.

## Mappings note

The code uses MCP names (e.g. `mc.player`, `p.posX`, `c.inventorySlots`).
At build time `reobfJar` remaps these to runtime SRG names
(`field_71439_g`, etc.). Do not use reflection on MCP names — the
reobfuscator won't touch reflection strings and the remapped jar will
`NoSuchFieldError` at runtime. If you need reflective access, use
`ObfuscationReflectionHelper` with SRG names, or add an access
transformer under `src/main/resources/META-INF/`.

## Build / run commands

```bash
export JAVA_HOME="$(pwd)/jdk8"

# Build release jar -> build/libs/sf4debug-0.6.4.jar
./gradlew --no-daemon build

# Launch dev client with mod loaded in ./run/
./gradlew --no-daemon runClient

# Clean (first build is ~5-15 min; cleans lose that cache)
./gradlew --no-daemon clean
```

Gradle's daemon is explicitly disabled in `gradle.properties` to keep
the cache honest between automated runs.

## First-build timing

Cold `./gradlew build`:

- Gradle wrapper download: ~20s
- ForgeGradle 3 userdev download + MC deobf + Forge patches: ~5-10min
- MCP decompile + recompile: ~2-5min
- Our `compileJava` + `reobfJar`: <10s

Subsequent incremental builds: a few seconds.

## Artifact path

After `./gradlew build`:

```
sf4debug/build/libs/sf4debug-0.6.4.jar
```

That jar is the deployable artifact — drop it into the target
client's `.minecraft/mods/` alongside the SF4 pack jars. Delete any
earlier `sf4debug-*.jar` first; Forge will load whichever it finds.

## Where this couples to other dirs

- `../.minecraft/mods/` — deploy target on this host (once HMCL
  finishes bootstrapping the client install).
- `../../2026-04-23-skyfactory/` — the dedicated server this client
  connects to. The server intentionally does **not** install this
  mod; `clientSideOnly = true` keeps FML happy.

## When you change things, also update

- `README.md` in this directory (user-facing docs).
- The parent `../AGENTS.md` if you change deployment layout.
