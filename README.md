# sf4debug

Client-only Forge mod for **Minecraft 1.12.2** + **Forge 14.23.5.2860**
(the SkyFactory 4 version) that exposes live Minecraft client state
over a loopback HTTP port.

The mod is marked `clientSideOnly = true` with
`acceptableRemoteVersions = "*"`, so the server never sees or loads
it. Drop the built jar into the client's `mods/` folder alongside the
rest of the SF4 pack and join any server as normal.

## Build

The project is self-contained under this directory.

```bash
cd sf4debug
export JAVA_HOME="$(pwd)/jdk8"
./gradlew --no-daemon build
# output: build/libs/sf4debug-0.6.4.jar
```

First build will download Forge userdev + MC assets + decompile MCP
mappings — plan for ~5-15 minutes of cold gradle. Subsequent builds
are a few seconds.

The bundled `jdk8/` is Temurin 1.8.0_482 (same build as the server's
`jre8/`). **Do not** try to build with system Java 17/25 — 1.12.2
Forge's legacy ASM fails on Java 9+ classfiles.

## Dev runClient

```bash
./gradlew --no-daemon runClient
```

Launches a Forge 1.12.2 dev client with just this mod loaded in
`./run/`. Point it at a vanilla 1.12.2 server to verify the mod wires
up without the full 201-mod SF4 pack. Test endpoints with:

```bash
curl -s http://127.0.0.1:25580/
curl -s http://127.0.0.1:25580/player?pretty=1
```

If you want the dev client to see the real SF4 modpack, drop the
modpack's `mods/` and `config/` into `./run/` (symlinks work). Some
SF4 coremods (BNBGamingCore, FoamFix, SmoothFontCore) rewrite
bytecode aggressively and may need to be disabled if the dev client
crashes; your own mod will still build fine regardless.

## Install on the real client

```
sf4debug/build/libs/sf4debug-0.6.4.jar
```

Copy this jar into the `.minecraft/mods/` folder on the client laptop
(where all the SkyFactory 4 mod jars live). Delete any earlier
`sf4debug-*.jar` first. No other changes. Launch the game, join the
SF4 server, then from anywhere on the client box (or another
machine if you've bound to a non-loopback interface):

```bash
curl http://127.0.0.1:25580/state?pretty=1
```

## Endpoints

All endpoints return JSON. Append `?pretty=1` for indented output.
All handlers run on the Minecraft client thread; HTTP handler threads
block for up to 2 seconds per request. Mutating routes use `GET`
(same as reads) so every endpoint is trivially scriptable from `curl`.

### Read-only snapshots

| Route | Description |
| --- | --- |
| `GET /` | Index / route list. |
| `GET /state` | One-shot snapshot: fps, player, hotbar, inventory, screen, look, server. Append `chunks=1` or `entities=1` to include those too. |
| `GET /player` | Position, rotation, health, food, XP, potion effects, gamemode, dimension. |
| `GET /hotbar` | Hotbar slots + currently-held main/offhand items. |
| `GET /inventory` | Full 36-slot main inventory + armor + offhand + open container slots. |
| `GET /screen` | Currently-open `GuiScreen` class (e.g. `GuiInventory`, `GuiCrafting`, modded GUIs like JEI). For any `GuiContainer` (vanilla or modded) also includes `bounds` (`guiLeft`, `guiTop`, `xSize`, `ySize`), all `buttons` with screen positions / enabled / visible, all `textFields` with text / focus / geometry, the full `classHierarchy` up to `GuiScreen`, and (for AE2 ME terminals) an `aeMeTerminal` block — the ME network's visible item repo, scroll-bar geometry, and per-ME-slot screen coordinates. |
| `GET /chunks?radius=N` | Loaded chunks around the player. Without `radius`, returns all loaded chunks in the client-side `ChunkProviderClient`. |
| `GET /entities?radius=32&type=zombie&limit=500` | Nearby entities, optionally filtered by class-name substring. |
| `GET /look` | Block or entity the player is currently looking at (raytrace). Includes side and hit-vector. |
| `GET /server` | Current server name/IP/MOTD/ping and the full online-player list with per-player ping. |
| `GET /fps` | FPS + JVM memory usage. |
| `GET /block?x=&y=&z=` | Detailed info for one block (id, meta, material, light, hardness, bbox, state properties, TileEntity NBT). |
| `GET /blocks?radius=8&dy=8&limit=2000&nonAir=1&name=ore&solid=0` | Every loaded block in a box around the player. `name` is a case-insensitive substring filter on the registry name. `solid=1` filters to materials that block movement. |
| `GET /visible?range=64&hfov=70&vfov=50&hres=32&vres=18&limit=500&fluids=0` | Raycast-sampled blocks visible in the player's current view. Casts `hres*vres` rays across the specified FOV box and returns each unique block hit. Set `fluids=1` to pass through liquids. |
| `GET /holds` | Current keybinds that `/walk`, `/hold*`, `/key` are re-asserting each tick. |

### Movement (all hold keybinds for N client ticks)

| Route | Description |
| --- | --- |
| `GET /walk?dir=forward[,left]&ticks=20&sprint=1&sneak=1&jump=1` | Hold movement keys. `dir` is a comma-separated list of `forward\|back\|left\|right`. Pass `dir=stop` to release everything. Default ticks = 20 (1s). |
| `GET /stop` | Release every held keybind and reset any in-progress block damage. |
| `GET /jump?ticks=2` | Hold the jump key briefly. |
| `GET /sneak?ticks=20` | Hold sneak. |
| `GET /sprint?ticks=20` | Hold sprint. |
| `GET /key?name=forward&ticks=20&click=0` | Generic hold for any named keybind (`forward\|back\|left\|right\|jump\|sneak\|sprint\|attack\|use\|drop\|pickBlock\|swapHands\|inventory\|chat\|command\|playerList`). `click=1` fires a single press event instead of a hold. |
| `GET /releaseKey?name=forward` | Release a specific held keybind immediately. |

### Aim

| Route | Description |
| --- | --- |
| `GET /aim?yaw=&pitch=&relative=0&ticks=0` | Set yaw / pitch. Omitted axis is untouched. `relative=1` adds to current rotation. Pitch is clamped to [-90, 90]. `ticks=N` (N>0) interpolates smoothly over N client ticks instead of snapping instantly — much more anti-cheat-friendly. |
| `GET /lookAt?x=&y=&z=&ticks=0` | Aim at a world coordinate (computes yaw/pitch from the eye to the target). Accepts the same `ticks=N` smooth-interpolation option. |

### Interact

| Route | Description |
| --- | --- |
| `GET /use?x=&y=&z=&side=up&hand=main[&hitx=&hity=&hitz=]` | Right-click the given face of a block (open chest/furnace/machine, place block, activate lever, etc.). Runs `PlayerControllerMP#processRightClickBlock`. |
| `GET /attack?x=&y=&z=&side=up` | One `clickBlock`. Creative: breaks instantly. Survival: starts damaging. Follow with `/holdAttack` (after `/lookAt`) to actually break in survival. |
| `GET /holdAttack?ticks=20` | Hold the attack key for N ticks (survival breaking, continuous attack). |
| `GET /holdUse?ticks=20` | Hold the use-item key (bow charging, eating, etc.). |
| `GET /stopAttack` | Reset block-breaking progress and release the attack key. |
| `GET /useItem?hand=main` | Right-click the air with the held item. |
| `GET /attackEntity?id=N` | Attack the entity with the given entityId (from `/entities`). |
| `GET /useEntity?id=N&hand=main` | Right-click / interact with the given entity. |
| `GET /swing?hand=main` | Play the arm-swing animation for a specific hand. |

### Inventory & GUI

| Route | Description |
| --- | --- |
| `GET /drop?full=0` | Drop the currently-held stack. `full=1` drops the whole stack (Ctrl+Q). |
| `GET /selectSlot?slot=0..8` | Select a hotbar slot (sends `CPacketHeldItemChange`). |
| `GET /swap` | Swap main / off-hand items (F key equivalent). |
| `GET /click?slotId=&button=0&mode=PICKUP` | `PlayerControllerMP#windowClick` on the currently-open container. Modes: `PICKUP\|QUICK_MOVE\|SWAP\|CLONE\|THROW\|QUICK_CRAFT\|PICKUP_ALL`. |
| `GET /close` | Close the currently-open GUI screen. |
| `GET /openInventory` | Open the vanilla player-inventory GUI. |
| `GET /chat?msg=...` | Send a chat message. Messages starting with `/` are commands. Truncated to 256 chars. |

### Complex / modded GUI interaction (AE2 terminals, machine UIs, JEI, ...)

These routes drive the currently-open `GuiScreen` the same way a
real user's mouse and keyboard do — via `mouseClicked` /
`mouseClickMove` / `mouseReleased` / `keyTyped` dispatched through
reflection on the protected vanilla methods. Any modded widget
(AE2's `GuiCustomSlot`, `GuiScrollbar`, `MEGuiTextField`, IC2
/ TE / Mekanism / Thermal machine tabs, config buttons, side-mode
buttons, etc.) runs its own click/keypress handler as if it came
from a human.

Typical flow:

1. `GET /screen?pretty=1` — read `bounds.guiLeft/guiTop`, enumerate
   `buttons` (id, x, y, displayString), `textFields` (text, focus,
   x/y/width/height), and — for AE2 — `aeMeTerminal.meSlots` and
   `aeMeTerminal.scrollBar`.
2. `GET /guiClick?x=&y=` at a widget / ME-slot / machine-slot /
   button centre, or `GET /guiButton?id=N` to click a vanilla
   `GuiButton` by id.
3. `GET /guiType?text=...` to fill a focused text field, or
   `GET /guiKey?name=enter` / `?code=N` for navigation keys.
4. `GET /guiScroll?dwheel=±120` to scroll an AE2 terminal item
   list.

| Route | Description |
| --- | --- |
| `GET /guiClick?x=&y=&button=0&shift=0&ctrl=0&alt=0` | Dispatch `GuiScreen#mouseClicked(x, y, button)` at a raw screen pixel. `button` is 0 left, 1 right, 2 middle. `shift/ctrl/alt=1` temporarily flip LWJGL's `keyDownBuffer` so modded widgets that check `GuiScreen.isShiftKeyDown()` / `Keyboard.isKeyDown(...)` see the modifier as held for the duration of the click. Falls back to an unmodified dispatch and reports `modifierOverride: unsupported` if the LWJGL reflection fails. |
| `GET /guiRelease?x=&y=&state=0&shift=0&ctrl=0&alt=0` | Dispatch `GuiScreen#mouseReleased`. Normally paired with `/guiClick` for drag interactions. Same modifier flags as `/guiClick`. |
| `GET /guiDrag?x=&y=&button=0&time=0&shift=0&ctrl=0&alt=0` | Dispatch `GuiScreen#mouseClickMove` (hold-and-drag). `time` is milliseconds since last click. Same modifier flags as `/guiClick`. |
| `GET /guiKey?code=N&char=X&name=...` | Dispatch `GuiScreen#keyTyped(char, keyCode)`. Accepts raw `code=<int>` (LWJGL keycode), a single character in `char=X`, or a symbolic `name=` shortcut: `enter\|esc\|backspace\|tab\|space\|up\|down\|left\|right\|home\|end\|delete\|pageup\|pagedown\|f1..f12`. |
| `GET /guiType?text=hello+world` | Types each character via repeated `keyTyped`. Maps `\n`, `\r`, `\t`, `\b`, space and alphanumerics to the right LWJGL keycodes so text fields treat them as navigation properly. |
| `GET /guiScroll?dwheel=±120[&x=&y=]` | Scrolls the current screen. Uses AE2's `AEBaseGui#getScrollBar().wheel()` when present; otherwise reflectively calls `mouseWheelEvent(x, y, dwheel)` if the screen has one (AE2, some modded UIs). Vanilla scroll cannot be spoofed from Java — fall back to `/guiClick` on the scroll-bar thumb. |
| `GET /guiButton?id=N` | Finds the `GuiButton` in `buttonList` with the given id and clicks its centre via `mouseClicked`. Fires the screen's `actionPerformed` naturally and plays the vanilla click sound. |
| `GET /me?search=&limit=100&offset=0` | AE2-only. Paginates the ME terminal's `ItemRepo` as the network sees it: one entry per unique AE item with display ItemStack, stack size, requestable/craftable flags. If `search=` is given, also sets the terminal's search filter (as if you typed it into the `MEGuiTextField`) so the returned view matches the UI. Includes `meSlots` (screen-pixel coordinates of the visible row of ME slots) and `scrollBar` geometry so the caller can translate an item hit into a `/guiClick`. |

### Event stream (long-poll)

A real player reacts to stimuli instead of polling every 500ms. The event
stream buffers everything interesting that happened on the client and
lets you long-poll for it with a monotonic cursor.

| Route | Description |
| --- | --- |
| `GET /events?since=<cursor>&wait=20&types=chat,damage&limit=256` | Long-poll for new events. `since=0` starts from the oldest buffered event. `wait` is the max seconds to block (clamped to [0, 60]; default 20). `types` is a CSV allowlist; omit to receive everything. `limit` caps events per call (default 256, max 2048). Response: `{cursor, events: [{cursor, time, type, ...payload}], dropped}`. `dropped` counts events that were evicted from the 2048-slot ring buffer before you could fetch them — bump your `since` cursor to whatever `cursor` the response returned and keep polling. Event types emitted: `chat`, `chat.sent`, `guiOpen`, `guiClose`, `damage`, `death`, `respawn`, `pickup.item`, `pickup.xp`, `chunkLoad`, `chunkUnload`, `sound`, `health`, `food`, `xpLevel`, `weather`, `timeOfDay`, `disconnect`, `potion.add`, `potion.remove`, `potion.expire`, `item.broken`, `hotbar.select`, `dimension.change`, `gamemode.change`, `container.open`, `container.close`, `velocity`. |

### Observation readback (what the player sees on their HUD)

| Route | Description |
| --- | --- |
| `GET /bossbars` | Active boss bars: `{bars: [{uuid, name, percent, color, overlay, darkenSky, thickenFog, createFog}]}`. Read from `GuiIngame#getBossOverlay().mapBossInfos`. |
| `GET /scoreboard` | Current sidebar objective: `{objective, displayName, lines: [{name, score}]}`. Null objective when no sidebar is set. |
| `GET /chatlog?limit=50` | Chat scrollback (same lines a human sees by pressing T). Newest first. `limit` default 50, max 200. Returns text, color-stripped text, the component JSON, and `age` in ticks. |
| `GET /overlay` | Current action-bar / title / subtitle / toast overlay: `{actionBar, title, subtitle, titleFadeIn, titleStayTime, titleFadeOut, toasts: [{title, subtitle, className}]}`. Populated via reflection on `GuiIngame`'s private title state. |
| `GET /world` | World context: `{timeOfDay, totalWorldTime, moonPhase, weather, rainStrength, thunderStrength, lightAtFeet, biome, temperature, humidity}`. |

All of `/state` also gains an `overlay`, `bossbars`, `scoreboard` block.
`/player` gains `weather`, `timeOfDay`, `moonPhase`, `lightAtFeet`,
`biome`. `/look` gains `blockState.properties`, `tileEntity.nbt` (SNBT,
truncated to 4 KiB), `light.block`, `light.sky`, `biome` for block hits.

### Command autocomplete

| Route | Description |
| --- | --- |
| `GET /tabcomplete?text=/gamemode+c&cursor=&hasTargetBlock=0` | Send `CPacketTabComplete` and wait up to 2s for the server's `SPacketTabComplete` reply. Returns `{suggestions: [...]}` or `{timeout: true}`. Works for any command the server offers tab-completion for, so scripted drivers don't need to hard-code per-modpack command names. |

### Screenshot (remote-safe — never writes to disk)

| Route | Description |
| --- | --- |
| `GET /screenshot?scale=1.0&format=png&return=base64` | Reads the framebuffer on the client thread, encodes PNG in-process. `return=base64` (default) returns JSON `{width, height, png_base64}`. `return=binary` returns the raw PNG bytes with `Content-Type: image/png` so `curl -o shot.png http://host:25580/screenshot?return=binary` works even from another machine. `scale` downsamples in Java (0 < s ≤ 1). Never calls `ScreenShotHelper.saveScreenshot` — nothing is ever written to disk and no local paths are returned. Returns `{warning: "framebuffer unavailable"}` if the game window isn't currently rendering. |

### Composite helpers (cut 5 round-trips down to 1)

| Route | Description |
| --- | --- |
| `GET /moveItem?from=&to=&count=&shift=0` | Move an item stack between two container slots. `shift=1` does one `QUICK_MOVE` window click. Otherwise pickup source + deposit on target, with `count>0` splitting via repeated right-clicks. Returns before/after stacks. Requires `mc.player.openContainer != null`. |
| `GET /findItem?name=&nbt=&where=inv|container|both` | Locate an item in the player's inventory and/or open container. `name` is a case-insensitive substring match on the registry name; `nbt` is an optional substring match on the stack's SNBT. Returns `{matches: [{source, index, item}]}`. |
| `GET /placeItem?slot=&x=&y=&z=&side=up&restore=1` | Select hotbar slot → right-click-place at coord → (optionally) restore previous slot. |
| `GET /dropSlot?slot=&full=0&restore=1` | Select hotbar slot → drop held stack (`full=1` drops the whole stack) → restore previous slot. |
| `GET /wait?ticks=<n>&maxMs=<n>` | Blocks the HTTP thread (never the client thread) for `ticks` client ticks or `maxMs` milliseconds, whichever comes first. Capped at 200 ticks / 15s. Useful for "wait for the next tick boundary" idioms in scripts. |
| `GET /eatUntilFull?slot=&maxTicks=200` | Select `slot`, hold the use-item key until `foodLevel>=20` or `maxTicks` expire. Returns `{initialFood, finalFood, ticksUsed, reason}`. |
| `GET /signSet?line0=&line1=&line2=&line3=&confirm=1` | When `GuiEditSign` is open, set the four sign lines and (with `confirm=1`) close the screen so vanilla sends `CPacketUpdateSign` automatically. |
| `GET /anvilRename?name=...` | When `GuiRepair` (anvil) is open, set the rename text field. Triggers the built-in rename listener, which sends `CPacketRenameItem`. |

### Deep perception (v0.5.0)

Everything a real player sees, hears, or feels that the primitive
snapshot routes don't already return.

| Route | Description |
| --- | --- |
| `GET /particles?radius=32&limit=200` | Currently-rendered particles in the player's neighbourhood, pulled from `ParticleManager.fxLayers` reflectively. Each entry has class, position, motion, age, maxAge, alpha. |
| `GET /sounds.recent?windowTicks=40&limit=200` | Sounds played in the last `windowTicks` client ticks (default 40 ≈ 2s). Populated by a `@SubscribeEvent` on Forge's `PlaySoundEvent` that records sound id, category, volume, pitch, XYZ, and the client tick it fired on. |
| `GET /cooldown` | Attack-cooldown progress (`getCooledAttackStrength(1)`), swing progress, active-item use ticks / total, XP bar progress, and per-Item `CooldownTracker` remaining ticks (reflectively enumerated so modded tracked items show up too). |
| `GET /miningStatus` | Whether `PlayerControllerMP` is currently breaking a block, which block, the current damage (0..1), and the block-position being broken. Everything a client-side hook would read to know "am I mid-mine?". |
| `GET /entity?id=N` | Detailed single-entity state: health, maxHealth, armor, potion effects, active hand, riding / passengers, held items, armor slots, child flag, elytra flag, glowing, invisible, custom name. Works for any entity by id, not just the player. |
| `GET /camera` | 1st/3rd person state, FOV, view-bobbing, smooth camera, render distance, particle setting, difficulty, fullscreen, fps. |

`/player` gains proprioception fields: `isInWater`, `isInLava`, `isOnLadder`, `isCollidedH/V`, `isGlowing`, `isInvisible`, `isRiding`, `fallDistance`, `hurtTime`, `portalCounter`, `swingProgress`, `activeHand`, `activeItemStack`, `capabilities.{allowFlying,isFlying,walkSpeed,flySpeed}`.

### Typed containers and macros (v0.5.0)

Each typed-container route reads exactly what the GUI displays and
fails fast with a useful error when the expected container isn't open.

| Route | Description |
| --- | --- |
| `GET /furnace` | Input / fuel / output slots plus `furnaceBurnTime`, `currentItemBurnTime`, `cookTime`, `totalCookTime`, `burnProgress`, `cookProgress`, `isBurning`. Falls back to `IInventory.getField(0..3)` if the `Container*` mirror fields aren't populated yet (first tick after opening). |
| `GET /brewing` | 3 bottle slots, ingredient, blaze-powder fuel, brew timer, brew progress (0..1), fuel remaining, `isBrewing`. |
| `GET /enchant` | `enchantLevels[0..2]`, `enchantClue[0..2]`, `worldClue[0..2]`, `xpSeed`, the item + lapis slots, and the player's current XP level. |
| `GET /anvil` | `maximumCost`, `materialCost`, `renamedTo`, the left/right input and output slots, plus an `affordable` flag comparing the cost to the player's XP level (true in creative). |
| `GET /merchant` | Villager recipe list: each entry has input1 / input2 / output item stacks, `uses`, `maxUses`, `disabled`, `rewardsExp`. Includes `selectedRecipe` (the trade currently showing in the middle). |
| `GET /beacon` | Beacon pyramid levels (0..4) and primary / secondary potion-effect ids + registry names. |
| `GET /book.write?pages=p1\|p2\|...&sign=0&title=Title` | Overwrite a `GuiScreenBook`'s pages reflectively. Page strings separated by `\|` (url-encode as `%7C`). `sign=1` additionally reflects into the screen's signing state, sets the title, and calls its private `sendBookToServer` method so the book is signed server-side. |
| `GET /creativeTab?tab=N` | Switch a creative inventory to tab N (0..11). Call with no `tab` to see the list of tab indices + names and the currently-selected tab. |
| `GET /clipboard?op=get\|set&text=...` | Read or write the system clipboard through `GuiScreen.getClipboardString` / `setClipboardString`. Convenient for driving modded GUIs that paste recipe input. |
| `GET /fishing.state` | `EntityPlayer.fishEntity` bobber snapshot: entity id, position, motion, `inWater`, `ticksCatchable`, `ticksCaughtDelay`. Returns `{fishing: false}` when no line is out. |

### Workflow and continuity (v0.5.0)

| Route | Description |
| --- | --- |
| `POST /batch` | Body is a JSON array of `{path: "/route", query: {...}}` objects. Each is invoked over loopback in order and the array of responses is returned as `{count, responses: [{index, path, ok, response}, ...]}`. Reduces HTTP RTT when the agent wants many snapshots in one reasoning step. Also accepts `GET /batch?cmds=<json-array>` for curl scripting. |
| `GET /diff?keys=player,look,world&reset=0` | Returns only the leaf values that changed since the previous `/diff` call with the same `keys` set. Compares against a per-key cache kept on the HTTP server. `reset=1` clears the cache and returns `{ok:true, reset:true}`. `keys` default: `player,look,hotbar,holds,world,overlay,fps`. |
| `GET /tick` | Client-tick counter incremented by an internal `@SubscribeEvent` on `TickEvent.ClientTickEvent.START`, plus `Minecraft.timer.renderPartialTicks` / `elapsedPartialTicks` / `elapsedTicks` via reflection. |
| `GET /input?dx=&dy=&wheel=` | Inject raw mouse motion through `EntityPlayerSP.turn`, exactly the code path vanilla `MouseHelper` uses on the client tick — honors the user's invert-mouse and sensitivity settings. Non-zero `wheel` does an in-world hotbar scroll. |
| `GET /mouse?x=&y=&button=0&action=click\|down\|up\|drag` | GUI-aware mouse wrapper. In a `GuiScreen`, dispatches through the existing `/guiClick` / `/guiRelease` / `/guiDrag` reflection. Out of a GUI, maps button 0/1 to the attack/use keybinds. |
| `GET /aimStatus` | Current aim plan state — exactly one of `smoothAim`, `aimPath`, `entityTrack` populated (or `active:false` when idle). Introspects `TickInput.snapshotAim()`. |
| `GET /aimPath?legs=yaw:pitch:ticks,yaw:pitch:ticks,...` | Schedule a multi-waypoint aim path. Each leg walks from the end of the previous leg to its `(yaw, pitch)` over its `ticks` client ticks using the same shortest-arc interpolation as `/aim?ticks=N`. Replaces any existing aim plan. |
| `GET /aimAt.entity?id=N&ticks=20&ease=4&eye=1` | Live-follow a moving entity by id for `ticks` client ticks. Each tick, the target yaw/pitch is recomputed from the entity's current position and stepped toward by `delta/ease`. `eye=1` aims at the entity's eye height; `eye=0` aims at its centre. Expires early if the entity disappears. |
| `GET /cancel?tag=all\|aim\|holds\|...` | Cancel an active operation by tag. `aim` clears all three aim plans; `holds` releases every held keybind; `all` does both and fires flags for every registered cancelable (e.g. `/eatUntilFull`, `/wait` when they start consulting the flags). Custom tags are registered by callers via `WorkflowRoutes.registerCancelable(String)`. |

### Recipe introspection (v0.6.0 — JEI-backed)

Exposes the recipe registry that JEI shows when you press `R` ("how is
this made?") or `U` ("what uses this?") in-game. JEI is accessed
entirely reflectively (via `mezz.jei.Internal.getRuntime()`) so there
is no compile-time dependency on JEI — the mod builds with just Forge.
When JEI isn't installed, `/recipes.categories` and `/recipes.list`
fall back to vanilla `CraftingManager.REGISTRY` + `FurnaceRecipes` so
you can still query basic crafting / smelting recipes. The richer
endpoints (`/recipes.lookup`, `/recipes.catalysts`) require JEI.

Recipes are returned as `{index, wrapperClass, inputs, outputs}` where
`inputs` and `outputs` are each a list of slots, and every slot is
itself a list of alternative ingredients (this is how JEI rotates
through ore-dictionary / tag entries in its GUI). Each alternative
is either an item stack JSON (same shape as `/inventory`) or a fluid
stack `{fluid, amount, nbt?, defaultName?}`.

| Route | Description |
| --- | --- |
| `GET /recipes.status` | `{jeiInstalled, jeiRuntimeReady, categoryCount, vanilla: {craftingRecipes, smeltingRecipes}}`. Use this before other recipe endpoints to verify JEI loaded successfully — JEI populates its runtime during `FMLLoadCompleteEvent` so very early requests may report `jeiRuntimeReady:false`. |
| `GET /recipes.categories?limit=200&offset=0` | List every JEI recipe category: `uid` (e.g. `minecraft.crafting`, `appliedenergistics2.inscriber`, `thermalexpansion.sawmill`), `title`, `modName`, `categoryClass`, `recipeCount`, and `catalysts` (the items that craft this category — e.g. the crafting table ItemStack for `minecraft.crafting`). Paginated; `limit` max 2000. |
| `GET /recipes.list?category=<uid>&limit=50&offset=0&ingredients=1` | Paginate recipes in a category. With `ingredients=1` (default) each entry carries its `inputs` and `outputs`. With `ingredients=0` only `{index, wrapperClass}` per entry — fast enough to enumerate thousands of recipes for later cherry-picking via `/recipes.get`. `limit` max 500. |
| `GET /recipes.lookup?item=modid:name&mode=output\|input\|both&limit=100&maxScan=5000&category=<uid>&meta=N` | Scan JEI for every recipe whose outputs (`mode=output`, "how do I make X") or inputs (`mode=input`, "what uses X") include an item by registry name. Optional `meta=N` restricts to a specific metadata value (`32767` / wildcard stacks always match). Optional `category=<uid>` limits the scan to one category. `maxScan` caps the total number of recipes examined across all categories (default 5000, max 200000) to protect against SF4's huge recipe table. Returns `{scanned, returned, truncated, matches: [{category, index, wrapperClass, matchedInput?, matchedOutput?, inputs, outputs}, ...]}`. |
| `GET /recipes.get?category=<uid>&index=N` | Full detail of one recipe by category UID + index (both from `/recipes.list` or `/recipes.lookup`). Returns `{category, categoryTitle, categoryModName, recipe: {index, wrapperClass, inputs, outputs}}`. |
| `GET /recipes.catalysts?category=<uid>` | Just the catalyst items (workstations) for a category — what block / machine crafts recipes of this type. |

### Ingredient registry (v0.6.5 — JEI-backed)

JEI maintains the canonical expanded list of every item subtype and fluid in
 the modpack. These routes expose that list so agents can discover registry
 names from display names (the inverse of `/recipes.lookup`).

| Route | Description |
| --- | --- |
| `GET /items?limit=500&offset=0&name=&mod=&id=` | Every ItemStack JEI knows about, paginated and optionally filtered by display-name substring (`name`), mod-id substring (`mod`), or exact registry name (`id`). Each entry: `{id, meta, displayName, modId}`. |
| `GET /fluids?limit=500&offset=0&name=` | Every FluidStack JEI knows about, paginated and optionally filtered by display-name substring. Each entry: `{fluid, amount, defaultName?, displayName?}`. |

Examples:

```bash
# "What recipes make iron_ingot?" (JEI's R-key)
curl -s 'http://127.0.0.1:25580/recipes.lookup?item=minecraft:iron_ingot&mode=output&limit=10&pretty=1'

# "What uses redstone?" (JEI's U-key, capped at first 50 matches to keep it fast)
curl -s 'http://127.0.0.1:25580/recipes.lookup?item=minecraft:redstone&mode=input&limit=50&pretty=1'

# "Which Thermal Expansion machines exist, and what do their recipes look like?"
curl -s 'http://127.0.0.1:25580/recipes.categories?pretty=1' \
  | jq '.categories[] | select(.uid | startswith("thermalexpansion"))'
curl -s 'http://127.0.0.1:25580/recipes.list?category=thermalexpansion.sawmill&limit=5&pretty=1'

# Page through SF4's entire crafting-table recipe list.
OFFSET=0
while :; do
  BATCH=$(curl -s "http://127.0.0.1:25580/recipes.list?category=minecraft.crafting&limit=100&offset=$OFFSET&ingredients=0")
  COUNT=$(echo "$BATCH" | jq '.returned')
  [ "$COUNT" = 0 ] && break
  echo "$BATCH" | jq -r '.recipes[] | "\(.index)\t\(.wrapperClass)"'
  OFFSET=$((OFFSET + COUNT))
done

# Find the registry name for "Dirt Acorn" when you only know the display name.
curl -s 'http://127.0.0.1:25580/items?name=dirt+acorn&limit=5&pretty=1'

# List every fluid JEI knows about.
curl -s 'http://127.0.0.1:25580/fluids?limit=50&pretty=1'
```

### Player state

| Route | Description |
| --- | --- |
| `GET /respawn` | Send `CPacketClientStatus#PERFORM_RESPAWN`. No-op while alive. |

### Client options (v0.6.1)

Runtime-toggleable client-side behavior flags. No restart required.

| Route | Description |
| --- | --- |
| `GET /options` | Snapshot every toggleable option. Currently: `{noPauseOnMinimize, windowActive}`. |
| `GET /noPauseOnMinimize?enable=0\|1` | Read or toggle the "don't pause on minimize" override. When enabled, the mod cancels any `GuiOpenEvent` that would install a `GuiIngameMenu` while the window is minimized / unfocused, so alt-tabbing away no longer dumps the player into the pause screen. Because `Minecraft.isGamePaused` is only latched when a pausing screen is open, this also keeps singleplayer ticking at full speed in the background. A deliberate `Esc`-press while the window is focused is not affected. Omit `enable` to just read the current state. The startup default can also be preset with `-Dsf4debug.noPauseOnMinimize=1`. |

### Tick-rate configuration (v0.6.2)

Runtime slowdown / speed-up of the integrated server's tick loop. The
client-side `Timer.tickLength` is updated in lock-step so motion
interpolation stays visually in sync with the (throttled) server.
**Single-player only** — this mod is `clientSideOnly` and has no way
to reach a dedicated server's tick loop. Attempting to set the rate
while connected to a remote server returns an error.

Two equivalent interfaces are provided:

- **HTTP endpoint** — `GET /tickrate?rate=N`, for automated drivers.
- **In-game slash command** — `/tickrate <N>`, for manual control
  from the chat box. Registered via `ClientCommandHandler` so it's
  intercepted before reaching any server, and exposed at permission
  level 0 (with `checkPermission` overridden to `true`) so it works
  in any single-player session — including worlds created with
  "Cheats: OFF".

| Route / command | Description |
| --- | --- |
| `GET /tickrate` | Read current target TPS: `{currentTps, defaultTps:20, minTps:1, maxTps:100, integratedServerRunning, clientTickLengthMs}`. |
| `GET /tickrate?rate=N` | Set target TPS (1-100). Applies a `Thread.sleep` on `TickEvent.ServerTickEvent.END` to stretch each integrated-server tick to `1000/N` ms, and reflectively writes `Minecraft.timer.tickLength` to the matching value so client rendering and input stay in sync. Returns the same fields as `GET /tickrate`. |
| `/tickrate` | (Chat) Print the current target TPS. |
| `/tickrate <1..100>` | (Chat) Set target TPS. |
| `/tickrate reset` | (Chat) Restore the vanilla 20 TPS. `/sf4tickrate` works as a namespaced alias in case another mod registers `/tickrate`. |

Notes:

- `N < 20` slows the game below vanilla rate (physics, mobs,
  block-ticks, crop growth, furnace progress, everything scales down
  proportionally). Vanilla's `MinecraftServer.run()` catch-up loop
  still fires, but because each catch-up tick now also takes
  `1000/N` ms, long-run effective TPS converges to the target. The
  vanilla "Can't keep up!" warning may log once every 15 s — that's
  expected when the rate is forced below 20.
- `N == 20` is the vanilla default and fully disables the override
  (no sleep, no timer reflection).
- `N > 20` is accepted, but the server-side sleep mechanism cannot
  shorten a tick below vanilla 50 ms, so the integrated server stays
  at 20 TPS while the client renders / ticks faster. This is useful
  for "zoom past slow animations" locally but is a known asymmetry.
- Changes are client-local and do not propagate across save/quit.
  Rejoining a world resets to the last value (still in memory); a
  fresh game launch starts at 20 TPS.

## Configuration

| Property | Default | |
| --- | --- | --- |
| `-Dsf4debug.port=25580` | `25580` | Port to bind. |
| `-Dsf4debug.host=127.0.0.1` | `127.0.0.1` | Bind address. **Keep on loopback** — the JSON is unauthenticated. |
| `-Dsf4debug.noPauseOnMinimize=1` | unset | Startup default for the `/noPauseOnMinimize` toggle. When set to a truthy value (`1`/`true`/`yes`/`on`), the "don't pause on minimize" override is active from mod load. |

Set these on the MC launcher's JVM args field if the defaults collide.

## Safety notes

- The port exposes live game state **and the ability to drive the
  player** without authentication. Bind to loopback only (default).
  Never expose on LAN.
- Each request schedules work on the client thread; avoid hammering
  the port at high frequency or you'll cut into the tick budget.
  `/chunks`, `/entities`, `/blocks` and `/visible` without a tight
  radius can return large JSON on a loaded modpack world.
- Movement-related routes are implemented by re-asserting the real
  keybinds each tick. They behave exactly like human input: an open
  GUI or the `Esc` menu will freeze them, and the normal anti-cheats
  on the server see normal movement packets. `/stop` releases
  everything.
- `/attack`, `/use`, `/useItem`, `/attackEntity`, `/useEntity`,
  `/click`, and `/chat` go out over the real player-to-server packet
  paths. A server that reach-/distance-checks will still reject
  impossible actions.

## Example scripted movement

```bash
# Turn to face north, then run north for 2 seconds.
curl -s 'http://127.0.0.1:25580/aim?yaw=180&pitch=0'
curl -s 'http://127.0.0.1:25580/walk?dir=forward&ticks=40&sprint=1'

# List every ore block visible in the current FOV.
curl -s 'http://127.0.0.1:25580/visible?range=32&hres=48&vres=32&limit=300&pretty=1' \
  | jq '.blocks[] | select(.id | contains("ore"))'

# Mine the block the player is currently aimed at (survival).
curl -s 'http://127.0.0.1:25580/holdAttack?ticks=60'

# Right-click the block at (123, 64, -45) on its top face to open a chest.
curl -s 'http://127.0.0.1:25580/use?x=123&y=64&z=-45&side=up'
```

## Example: drive an AE2 ME terminal

```bash
HOST=http://127.0.0.1:25580

# 1. Open the terminal as you normally would (/use on the block).
curl -s "$HOST/use?x=100&y=64&z=-200&side=up"

# 2. See what screen opened, including geometry, buttons, text fields
#    and — if it's an ME/crafting/patterns terminal — the ME item list.
curl -s "$HOST/screen?pretty=1" | jq '.aeMeTerminal | {total, returned, searchString, scrollBar, meSlots: (.meSlots[:5])}'

# 3. Focus the search field (first text field of the terminal).
#    guiType sends one keyTyped per character; click the field first so it's focused.
X=$(curl -s "$HOST/screen" | jq -r '.textFields[0].x + .textFields[0].width/2')
Y=$(curl -s "$HOST/screen" | jq -r '.textFields[0].y + .textFields[0].height/2')
curl -s "$HOST/guiClick?x=$X&y=$Y"
curl -s --data-urlencode 'text=iron_ingot' --get "$HOST/guiType"

# 4. Read the (now filtered) item list and click the first ME slot that
#    has the item we want.
curl -s "$HOST/me?search=iron_ingot&limit=1&pretty=1"
X=$(curl -s "$HOST/me" | jq '.meSlots[0].x')
Y=$(curl -s "$HOST/me" | jq '.meSlots[0].y')
curl -s "$HOST/guiClick?x=$X&y=$Y"           # left-click  → extract a stack
curl -s "$HOST/guiClick?x=$X&y=$Y&button=1"  # right-click → extract one

# 5. Scroll the ME grid (AE2 only; vanilla scroll isn't spoofable).
curl -s "$HOST/guiScroll?dwheel=-120"        # scroll down one notch

# 6. Close the terminal.
curl -s "$HOST/close"
```

## Example: click a specific button on a machine GUI

```bash
# Enumerate the buttons the current GUI exposes — id, position, text.
curl -s 'http://127.0.0.1:25580/screen?pretty=1' | jq '.buttons'

# Click button id=7 (e.g. a side-config / redstone-mode button).
curl -s 'http://127.0.0.1:25580/guiButton?id=7'

# Or click at the button's centre manually (same effect).
curl -s 'http://127.0.0.1:25580/guiClick?x=150&y=40'
```

## Layout

```
sf4debug/
├── build.gradle             # ForgeGradle 3.+ build
├── gradle.properties        # bumps heap for decompile step
├── gradlew / gradlew.bat    # Gradle 4.9 wrapper (from Forge MDK)
├── gradle/wrapper/
├── jdk8/                    # bundled Temurin 1.8.0_482 JDK
├── src/main/java/com/telethon/sf4debug/
│   ├── SF4Debug.java        # @Mod entry point, clientSideOnly=true
│   ├── DebugHttpServer.java # HTTP routing + read-only snapshots
│   ├── BlockRoutes.java     # /block, /blocks, /visible
│   ├── ActionRoutes.java    # /walk, /use, /attack, /click, …
│   ├── GuiRoutes.java       # /guiClick, /guiKey, /guiType, /guiScroll, /guiButton, /me (+ modifiers)
│   ├── EventStream.java     # ring buffer + Forge event subscribers for /events
│   ├── EventRoutes.java     # /events long-poll HTTP handler
│   ├── ObserveRoutes.java   # /bossbars, /scoreboard, /chat, /overlay, /world + snapshot augmenters
│   ├── TabCompleteRoutes.java # /tabcomplete (CPacket/SPacketTabComplete round-trip)
│   ├── MediaRoutes.java     # /screenshot (base64 / binary, never writes to disk)
│   ├── HelperRoutes.java    # /moveItem /findItem /placeItem /dropSlot /wait /eatUntilFull /signSet /anvilRename
│   ├── PerceptionRoutes.java  # /particles /sounds.recent /cooldown /miningStatus /entity /camera (v0.5.0)
│   ├── ContainerRoutes.java   # /furnace /brewing /enchant /anvil /merchant /beacon /book.write /creativeTab /clipboard /fishing.state (v0.5.0)
│   ├── WorkflowRoutes.java    # /batch /diff /tick /input /mouse /aimStatus /aimPath /aimAt.entity /cancel (v0.5.0)
│   ├── RecipeRoutes.java      # /recipes.status /recipes.categories /recipes.list /recipes.lookup /recipes.get /recipes.catalysts (v0.6.0)
│   ├── PauseRoutes.java       # /options /noPauseOnMinimize + GuiOpenEvent cancel on minimize (v0.6.1)
│   ├── TickRateRoutes.java    # /tickrate HTTP + ServerTickEvent throttle + client Timer.tickLength sync (v0.6.2)
│   ├── TickRateCommand.java   # /tickrate slash command via ClientCommandHandler (v0.6.2)
│   └── TickInput.java       # client-tick keybind hold simulator + smooth-aim / aim-path / entity-track scheduler
└── src/main/resources/
    ├── mcmod.info
    └── pack.mcmeta
```

## Troubleshooting

- **`NoClassDefFoundError: sun/net/httpserver/...`** — won't happen
  with Temurin 8; all distributions of JDK 8 ship `com.sun.net.httpserver`.
- **Field not found at runtime (e.g. `field_71439_g`)** — means the
  jar wasn't reobfuscated; make sure `./gradlew build` is what ran,
  not a raw `./gradlew jar`. `reobfJar` is wired as a `finalizedBy`.
- **Dev client crashes before your mod loads** — check
  `build/reports/problems/problems-report.html` and the
  `build/tmp/fg_cache/` logs. 99% of early crashes are caused by a
  mismatched SF4 coremod dropped into `run/mods/`.
