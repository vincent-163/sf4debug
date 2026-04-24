package com.telethon.sf4debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.RayTraceResult.Type;
import net.minecraft.world.chunk.Chunk;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Loopback HTTP server that exposes Minecraft client state as JSON.
 *
 * <p>All snapshot code runs on the Minecraft client thread via
 * {@link Minecraft#addScheduledTask(Runnable)}; HTTP handler threads only
 * marshal results to JSON. This is non-negotiable: the client thread owns
 * all world/player/GUI state and touching it from another thread causes
 * intermittent ConcurrentModificationException / NPE crashes.
 */
public final class DebugHttpServer {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Gson GSON_PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final long SNAPSHOT_TIMEOUT_MS = 2000L;

    private DebugHttpServer() {}

    public static void start(String host, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 16);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "sf4debug-http");
            t.setDaemon(true);
            return t;
        }));

        server.createContext("/", wrap(DebugHttpServer::routeIndex));
        // Snapshot routes (read-only).
        server.createContext("/state",     wrap(q -> snapshotAll(q)));
        server.createContext("/player",    wrap(q -> snapshotPlayer()));
        server.createContext("/inventory", wrap(q -> snapshotInventory()));
        server.createContext("/hotbar",    wrap(q -> snapshotHotbar()));
        server.createContext("/screen",    wrap(q -> snapshotScreen()));
        server.createContext("/chunks",    wrap(q -> snapshotChunks(q)));
        server.createContext("/entities",  wrap(q -> snapshotEntities(q)));
        server.createContext("/look",      wrap(q -> snapshotLook()));
        server.createContext("/server",    wrap(q -> snapshotServer()));
        server.createContext("/fps",       wrap(q -> snapshotFps()));
        server.createContext("/block",     wrap(BlockRoutes::block));
        server.createContext("/blocks",    wrap(BlockRoutes::blocks));
        server.createContext("/visible",   wrap(BlockRoutes::visible));
        server.createContext("/holds",     wrap(q -> snapshotHolds()));

        // GUI interaction routes — click, type, scroll in modded GUIs.
        server.createContext("/guiClick",   wrap(GuiRoutes::guiClick));
        server.createContext("/guiRelease", wrap(GuiRoutes::guiRelease));
        server.createContext("/guiDrag",    wrap(GuiRoutes::guiDrag));
        server.createContext("/guiKey",     wrap(GuiRoutes::guiKey));
        server.createContext("/guiType",    wrap(GuiRoutes::guiType));
        server.createContext("/guiScroll",  wrap(GuiRoutes::guiScroll));
        server.createContext("/guiButton",  wrap(GuiRoutes::guiButton));
        server.createContext("/me",         wrap(GuiRoutes::me));

        // Action routes — movement.
        server.createContext("/walk",       wrap(ActionRoutes::walk));
        server.createContext("/stop",       wrap(ActionRoutes::stop));
        server.createContext("/jump",       wrap(ActionRoutes::jump));
        server.createContext("/sneak",      wrap(ActionRoutes::sneak));
        server.createContext("/sprint",     wrap(ActionRoutes::sprint));
        server.createContext("/key",        wrap(ActionRoutes::key));
        server.createContext("/releaseKey", wrap(ActionRoutes::releaseKey));
        // Action routes — aim.
        server.createContext("/aim",        wrap(ActionRoutes::aim));
        server.createContext("/lookAt",     wrap(ActionRoutes::lookAt));
        // Action routes — interact.
        server.createContext("/use",          wrap(ActionRoutes::useBlock));
        server.createContext("/attack",       wrap(ActionRoutes::attackBlock));
        server.createContext("/holdAttack",   wrap(ActionRoutes::holdAttack));
        server.createContext("/holdUse",      wrap(ActionRoutes::holdUse));
        server.createContext("/stopAttack",   wrap(ActionRoutes::stopAttack));
        server.createContext("/useItem",      wrap(ActionRoutes::useItem));
        server.createContext("/attackEntity", wrap(ActionRoutes::attackEntity));
        server.createContext("/useEntity",    wrap(ActionRoutes::useEntity));
        server.createContext("/swing",        wrap(ActionRoutes::swing));
        // Action routes — inventory / GUI.
        server.createContext("/drop",          wrap(ActionRoutes::drop));
        server.createContext("/selectSlot",    wrap(ActionRoutes::selectSlot));
        server.createContext("/swap",          wrap(ActionRoutes::swap));
        server.createContext("/close",         wrap(ActionRoutes::close));
        server.createContext("/openInventory", wrap(ActionRoutes::openInventory));
        server.createContext("/click",         wrap(ActionRoutes::click));
        server.createContext("/chat",          wrap(ActionRoutes::chat));
        server.createContext("/respawn",       wrap(ActionRoutes::respawn));

        // v0.4.0 groups — each registers its own contexts on the same
        // HttpServer. See TODO.md for per-group scope.
        EventRoutes.register(server);
        ObserveRoutes.register(server);
        TabCompleteRoutes.register(server);
        MediaRoutes.register(server);
        HelperRoutes.register(server);
        // v0.5.0 groups — deeper perception, typed containers, workflow.
        PerceptionRoutes.register(server);
        ContainerRoutes.register(server);
        WorkflowRoutes.register(server);
        // v0.6.0 group — recipe introspection via JEI (reflection only).
        RecipeRoutes.register(server);
        // v0.6.1 group — client-option toggles (noPauseOnMinimize).
        PauseRoutes.register(server);
        // v0.6.2 group — runtime tick-rate configuration.
        TickRateRoutes.register(server);
        // v0.6.4 group — debug / diagnostic endpoints.
        DebugRoutes.register(server);

        server.start();
    }

    /* -------------------------- HTTP plumbing -------------------------- */

    static HttpHandler wrap(Route route) {
        return exchange -> {
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI());
                boolean pretty = query.containsKey("pretty");
                Object result = runOnClientThread(() -> route.handle(query));
                String body = (pretty ? GSON_PRETTY : GSON).toJson(result);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Throwable t) {
                SF4Debug.LOG.warn("sf4debug {} failed", exchange.getRequestURI(), t);
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", t.getClass().getSimpleName());
                err.put("message", String.valueOf(t.getMessage()));
                byte[] bytes = GSON.toJson(err).getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(500, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } catch (IOException ignored) {}
            } finally {
                exchange.close();
            }
        };
    }

    /** Runs callable on the MC client thread, blocks at most SNAPSHOT_TIMEOUT_MS. */
    static <T> T runOnClientThread(Callable<T> callable) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        CompletableFuture<T> fut = new CompletableFuture<>();
        mc.addScheduledTask(() -> {
            try { fut.complete(callable.call()); }
            catch (Throwable t) { fut.completeExceptionally(t); }
        });
        try {
            return fut.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new RuntimeException("client-thread task timed out after " + SNAPSHOT_TIMEOUT_MS + "ms");
        }
    }

    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return out;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) out.put(urlDecode(kv), "");
            else        out.put(urlDecode(kv.substring(0, eq)), urlDecode(kv.substring(eq + 1)));
        }
        return out;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    @FunctionalInterface
    interface Route {
        Object handle(Map<String, String> query) throws Exception;
    }

    /* -------------------------- Snapshots -------------------------- */

    private static Object routeIndex(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mod", "sf4debug");
        out.put("version", "0.6.4");
        List<String> routes = new ArrayList<>();
        // Snapshots (GET-only reads).
        routes.add("GET /state");
        routes.add("GET /player");
        routes.add("GET /inventory");
        routes.add("GET /hotbar");
        routes.add("GET /screen");
        routes.add("GET /chunks?radius=N");
        routes.add("GET /entities?radius=N&type=<substring>");
        routes.add("GET /look");
        routes.add("GET /server");
        routes.add("GET /fps");
        routes.add("GET /block?x=&y=&z=");
        routes.add("GET /blocks?radius=8&dy=8&limit=2000&nonAir=1&name=&solid=0");
        routes.add("GET /visible?range=64&hfov=70&vfov=50&hres=32&vres=18&limit=500");
        routes.add("GET /holds");
        // Actions (mutations — invoked over GET for curl-friendliness).
        routes.add("GET /walk?dir=forward,left&ticks=20&sprint=1&sneak=1&jump=1");
        routes.add("GET /stop");
        routes.add("GET /jump?ticks=2");
        routes.add("GET /sneak?ticks=20");
        routes.add("GET /sprint?ticks=20");
        routes.add("GET /key?name=forward&ticks=N&click=0");
        routes.add("GET /releaseKey?name=forward");
        routes.add("GET /aim?yaw=&pitch=&relative=0");
        routes.add("GET /lookAt?x=&y=&z=");
        routes.add("GET /use?x=&y=&z=&side=up&hand=main");
        routes.add("GET /attack?x=&y=&z=&side=up");
        routes.add("GET /holdAttack?ticks=20");
        routes.add("GET /holdUse?ticks=20");
        routes.add("GET /stopAttack");
        routes.add("GET /useItem?hand=main");
        routes.add("GET /attackEntity?id=N");
        routes.add("GET /useEntity?id=N&hand=main");
        routes.add("GET /swing?hand=main");
        routes.add("GET /drop?full=0");
        routes.add("GET /selectSlot?slot=0..8");
        routes.add("GET /swap");
        routes.add("GET /close");
        routes.add("GET /openInventory");
        routes.add("GET /click?slotId=&button=0&mode=PICKUP");
        routes.add("GET /chat?msg=...");
        routes.add("GET /respawn");
        // GUI interaction (works with modded GUIs: AE2 terminals, machines, JEI, ...).
        routes.add("GET /guiClick?x=&y=&button=0");
        routes.add("GET /guiRelease?x=&y=&state=0");
        routes.add("GET /guiDrag?x=&y=&button=0&time=0");
        routes.add("GET /guiKey?code=N&char=X&name=...");
        routes.add("GET /guiType?text=hello");
        routes.add("GET /guiScroll?dwheel=120[&x=&y=]");
        routes.add("GET /guiButton?id=N");
        routes.add("GET /me?search=&limit=100&offset=0");
        // v0.4.0 — events, observation, input refinement, media, helpers.
        routes.add("GET /events?since=&wait=20&types=&limit=256");
        routes.add("GET /bossbars");
        routes.add("GET /scoreboard");
        routes.add("GET /chatlog?limit=50");
        routes.add("GET /overlay");
        routes.add("GET /world");
        routes.add("GET /tabcomplete?text=&cursor=&hasTargetBlock=0");
        routes.add("GET /screenshot?scale=1.0&format=png&return=base64|binary");
        routes.add("GET /moveItem?from=&to=&count=&shift=0");
        routes.add("GET /findItem?name=&nbt=&where=inv|container|both");
        routes.add("GET /placeItem?slot=&x=&y=&z=&side=up&restore=1");
        routes.add("GET /dropSlot?slot=&full=0&restore=1");
        routes.add("GET /wait?ticks=&maxMs=");
        routes.add("GET /eatUntilFull?slot=&maxTicks=200");
        routes.add("GET /signSet?line0=&line1=&line2=&line3=&confirm=1");
        routes.add("GET /anvilRename?name=...");
        // v0.5.0 — deeper perception.
        routes.add("GET /particles?radius=32&limit=200");
        routes.add("GET /sounds.recent?windowTicks=40&limit=200");
        routes.add("GET /cooldown");
        routes.add("GET /miningStatus");
        routes.add("GET /entity?id=N");
        routes.add("GET /camera");
        // v0.5.0 — typed containers and macros.
        routes.add("GET /furnace");
        routes.add("GET /brewing");
        routes.add("GET /enchant");
        routes.add("GET /anvil");
        routes.add("GET /merchant");
        routes.add("GET /beacon");
        routes.add("GET /book.write?pages=p1|p2|...&sign=0&title=...");
        routes.add("GET /creativeTab?tab=N");
        routes.add("GET /clipboard?op=get|set&text=...");
        routes.add("GET /fishing.state");
        // v0.5.0 — workflow and continuity.
        routes.add("POST /batch  [ { path, query? } ... ]");
        routes.add("GET /diff?keys=player,look,world&reset=0");
        routes.add("GET /tick");
        routes.add("GET /input?dx=&dy=&wheel=");
        routes.add("GET /mouse?x=&y=&button=0&action=click|down|up|drag");
        routes.add("GET /aimStatus");
        routes.add("GET /aimPath?legs=yaw:pitch:ticks,yaw:pitch:ticks,...");
        routes.add("GET /aimAt.entity?id=N&ticks=20&ease=4&eye=1");
        routes.add("GET /cancel?tag=all|aim|holds|...");
        // v0.6.0 — recipe introspection (JEI).
        routes.add("GET /recipes.status");
        routes.add("GET /recipes.categories?limit=200&offset=0");
        routes.add("GET /recipes.list?category=<uid>&limit=50&offset=0&ingredients=1");
        routes.add("GET /recipes.lookup?item=modid:name&mode=output|input|both&limit=100&maxScan=5000&category=<uid>&meta=N");
        routes.add("GET /recipes.get?category=<uid>&index=N");
        routes.add("GET /recipes.catalysts?category=<uid>");
        // v0.6.1 — client-option toggles.
        routes.add("GET /options");
        routes.add("GET /noPauseOnMinimize?enable=0|1");
        // v0.6.2 — runtime tick-rate configuration.
        routes.add("GET /tickrate?rate=N  (also /tickrate command in single-player chat)");
        // v0.6.4 — debug / diagnostic endpoints.
        routes.add("GET /debug/keyState?name=attack");
        routes.add("GET /debug/leftClickCounter?set=0");
        routes.add("GET /debug/tickInput");
        routes.add("GET /debug/log?lines=100");
        routes.add("GET /debug/forceKey?name=attack&pressed=1");
        routes.add("GET /debug/currentScreen");
        routes.add("Note: /aim and /lookAt now accept ticks=N for smooth interpolation.");
        routes.add("Note: /guiClick, /guiRelease, /guiDrag now accept shift=1, ctrl=1, alt=1.");
        routes.add("Append ?pretty=1 to any route for indented JSON.");
        out.put("routes", routes);
        return out;
    }

    private static Object snapshotAll(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fps", snapshotFps());
        out.put("player", snapshotPlayer());
        out.put("hotbar", snapshotHotbar());
        out.put("inventory", snapshotInventory());
        out.put("screen", snapshotScreen());
        out.put("look", snapshotLook());
        out.put("server", snapshotServer());
        try { ObserveRoutes.augmentState(out); }
        catch (Throwable t) { out.put("augmentStateError", t.getClass().getSimpleName() + ": " + t.getMessage()); }
        // /state doesn't include /chunks or /entities by default — they are
        // big. Append ?chunks=1 / ?entities=1 to include them.
        if ("1".equals(q.get("chunks")))   out.put("chunks",   snapshotChunks(q));
        if ("1".equals(q.get("entities"))) out.put("entities", snapshotEntities(q));
        return out;
    }

    private static Object snapshotFps() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fps", Minecraft.getDebugFPS());
        Runtime rt = Runtime.getRuntime();
        out.put("memUsed", rt.totalMemory() - rt.freeMemory());
        out.put("memTotal", rt.totalMemory());
        out.put("memMax", rt.maxMemory());
        return out;
    }

    private static Object snapshotPlayer() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        WorldClient w = mc.world;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || w == null) {
            out.put("state", "not_in_world");
            return out;
        }
        out.put("state", "in_world");
        out.put("uuid", p.getUniqueID().toString());
        out.put("name", p.getName());
        out.put("dimension", p.dimension);
        out.put("x", p.posX);
        out.put("y", p.posY);
        out.put("z", p.posZ);
        out.put("yaw", p.rotationYaw);
        out.put("pitch", p.rotationPitch);
        out.put("onGround", p.onGround);
        out.put("isSneaking", p.isSneaking());
        out.put("isSprinting", p.isSprinting());
        out.put("isElytraFlying", p.isElytraFlying());
        out.put("motionX", p.motionX);
        out.put("motionY", p.motionY);
        out.put("motionZ", p.motionZ);
        out.put("health", p.getHealth());
        out.put("maxHealth", p.getMaxHealth());
        out.put("absorption", p.getAbsorptionAmount());
        out.put("foodLevel", p.getFoodStats().getFoodLevel());
        out.put("saturation", p.getFoodStats().getSaturationLevel());
        out.put("xp", p.experience);
        out.put("xpLevel", p.experienceLevel);
        out.put("xpTotal", p.experienceTotal);
        out.put("air", p.getAir());
        out.put("isBurning", p.isBurning());
        if (mc.playerController != null) {
            out.put("gameMode", mc.playerController.getCurrentGameType().getName());
        }
        // Active potion effects.
        List<Map<String, Object>> effects = new ArrayList<>();
        for (net.minecraft.potion.PotionEffect pe : p.getActivePotionEffects()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", pe.getEffectName());
            e.put("amplifier", pe.getAmplifier());
            e.put("durationTicks", pe.getDuration());
            effects.add(e);
        }
        out.put("potionEffects", effects);
        try { ObserveRoutes.augmentPlayer(out); }
        catch (Throwable t) { out.put("augmentPlayerError", t.getClass().getSimpleName() + ": " + t.getMessage()); }
        return out;
    }

    private static Object snapshotHotbar() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("state", "not_in_world"); return out; }
        InventoryPlayer inv = p.inventory;
        out.put("currentSlot", inv.currentItem);
        List<Object> bar = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            bar.add(itemStackJson(inv.mainInventory.get(i)));
        }
        out.put("slots", bar);
        out.put("held", itemStackJson(p.getHeldItemMainhand()));
        out.put("offhand", itemStackJson(p.getHeldItemOffhand()));
        return out;
    }

    private static Object snapshotInventory() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("state", "not_in_world"); return out; }
        InventoryPlayer inv = p.inventory;

        // Full player inventory, regardless of open GUI.
        List<Object> main = new ArrayList<>();
        for (int i = 0; i < inv.mainInventory.size(); i++) main.add(itemStackJson(inv.mainInventory.get(i)));
        List<Object> armor = new ArrayList<>();
        for (int i = 0; i < inv.armorInventory.size(); i++) armor.add(itemStackJson(inv.armorInventory.get(i)));
        List<Object> offhand = new ArrayList<>();
        for (int i = 0; i < inv.offHandInventory.size(); i++) offhand.add(itemStackJson(inv.offHandInventory.get(i)));

        Map<String, Object> player = new LinkedHashMap<>();
        player.put("main", main);
        player.put("armor", armor);
        player.put("offhand", offhand);
        player.put("currentHotbarSlot", inv.currentItem);
        out.put("player", player);

        // Whatever container is open on top. `openContainer` is always
        // non-null; when no GUI is open it points at `inventoryContainer`.
        Container c = p.openContainer;
        Map<String, Object> container = new LinkedHashMap<>();
        if (c == null) {
            container.put("present", false);
        } else {
            container.put("present", true);
            container.put("class", c.getClass().getName());
            container.put("windowId", c.windowId);
            container.put("isPlayerInventory", c == p.inventoryContainer);
            List<Object> slots = new ArrayList<>();
            for (int i = 0; i < c.inventorySlots.size(); i++) {
                Slot s = c.inventorySlots.get(i);
                Map<String, Object> slotJson = new LinkedHashMap<>();
                slotJson.put("index", i);
                slotJson.put("slotNumber", s.slotNumber);
                slotJson.put("inventoryClass", s.inventory == null ? null : s.inventory.getClass().getName());
                slotJson.put("x", s.xPos);
                slotJson.put("y", s.yPos);
                slotJson.put("stack", itemStackJson(s.getStack()));
                slots.add(slotJson);
            }
            container.put("slots", slots);
            // Player also holds a "mouse" itemstack when dragging.
            container.put("heldOnMouse", itemStackJson(inv.getItemStack()));
        }
        out.put("container", container);
        return out;
    }

    private static Object snapshotScreen() {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (screen == null) {
            out.put("open", false);
            return out;
        }
        out.put("open", true);
        out.put("class", screen.getClass().getName());
        out.put("width", screen.width);
        out.put("height", screen.height);
        if (screen instanceof GuiContainer) {
            GuiContainer gc = (GuiContainer) screen;
            Map<String, Object> gui = new LinkedHashMap<>();
            gui.put("containerClass", gc.inventorySlots.getClass().getName());
            gui.put("slotCount", gc.inventorySlots.inventorySlots.size());
            out.put("guiContainer", gui);
        }
        // Extra: bounds, buttons, text fields, class hierarchy, AE2 ME repo.
        try {
            GuiRoutes.augmentScreenSnapshot(screen, out);
        } catch (Throwable t) {
            out.put("augmentError", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    private static Object snapshotChunks(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = mc.world;
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (w == null || p == null) { out.put("state", "not_in_world"); return out; }

        Integer radius = parseInt(q.get("radius"), null);
        int px = ((int) Math.floor(p.posX)) >> 4;
        int pz = ((int) Math.floor(p.posZ)) >> 4;

        ChunkProviderClient cp = (ChunkProviderClient) w.getChunkProvider();
        // 1.12.2 has no public accessor; read the private Long2ObjectMap<Chunk>
        // via ObfuscationReflectionHelper (handles MCP <-> SRG transparently).
        Long2ObjectMap<Chunk> loaded = loadedChunkMap(cp);
        List<Map<String, Object>> chunks = new ArrayList<>();
        int total = 0;
        for (Chunk c : loaded.values()) {
            total++;
            int dx = c.x - px;
            int dz = c.z - pz;
            if (radius != null && (Math.abs(dx) > radius || Math.abs(dz) > radius)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", c.x);
            m.put("z", c.z);
            m.put("dx", dx);
            m.put("dz", dz);
            m.put("loaded", c.isLoaded());
            int entityCount = 0;
            for (int i = 0; i < c.getEntityLists().length; i++) entityCount += c.getEntityLists()[i].size();
            m.put("entities", entityCount);
            m.put("tileEntities", c.getTileEntityMap().size());
            m.put("isPopulated", c.isPopulated());
            chunks.add(m);
        }
        out.put("playerChunkX", px);
        out.put("playerChunkZ", pz);
        out.put("renderDistance", mc.gameSettings.renderDistanceChunks);
        out.put("loadedTotal", total);
        out.put("returned", chunks.size());
        out.put("chunks", chunks);
        return out;
    }

    private static Object snapshotEntities(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = mc.world;
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (w == null || p == null) { out.put("state", "not_in_world"); return out; }

        double radius = parseDouble(q.get("radius"), 32.0);
        double r2 = radius * radius;
        String typeFilter = q.get("type"); // substring match on class name
        int maxOut = parseInt(q.get("limit"), 500);

        List<Map<String, Object>> entities = new ArrayList<>();
        int totalWithin = 0;
        // loadedEntityList is a List<Entity>; snapshot it to avoid CME.
        List<Entity> snapshot = new ArrayList<>(w.loadedEntityList);
        for (Entity e : snapshot) {
            if (e == p) continue;
            double d2 = e.getDistanceSq(p);
            if (d2 > r2) continue;
            String cls = e.getClass().getSimpleName();
            if (typeFilter != null && !cls.toLowerCase().contains(typeFilter.toLowerCase())) continue;
            totalWithin++;
            if (entities.size() >= maxOut) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getEntityId());
            m.put("uuid", e.getUniqueID().toString());
            m.put("class", e.getClass().getName());
            m.put("simpleClass", cls);
            m.put("name", e.getName());
            m.put("x", e.posX);
            m.put("y", e.posY);
            m.put("z", e.posZ);
            m.put("dist", Math.sqrt(d2));
            m.put("yaw", e.rotationYaw);
            m.put("pitch", e.rotationPitch);
            m.put("motionX", e.motionX);
            m.put("motionY", e.motionY);
            m.put("motionZ", e.motionZ);
            if (e instanceof EntityItem) {
                m.put("item", itemStackJson(((EntityItem) e).getItem()));
            }
            entities.add(m);
        }
        out.put("radius", radius);
        out.put("totalLoaded", snapshot.size());
        out.put("totalWithin", totalWithin);
        out.put("returned", entities.size());
        out.put("entities", entities);
        return out;
    }

    private static Object snapshotLook() {
        Minecraft mc = Minecraft.getMinecraft();
        RayTraceResult hit = mc.objectMouseOver;
        Map<String, Object> out = new LinkedHashMap<>();
        if (hit == null) { out.put("type", "none"); return out; }
        out.put("type", hit.typeOfHit == null ? "none" : hit.typeOfHit.name());
        if (hit.typeOfHit == Type.BLOCK && hit.getBlockPos() != null) {
            BlockPos bp = hit.getBlockPos();
            out.put("x", bp.getX());
            out.put("y", bp.getY());
            out.put("z", bp.getZ());
            out.put("side", hit.sideHit == null ? null : hit.sideHit.name());
            if (hit.hitVec != null) {
                Map<String, Object> hv = new LinkedHashMap<>();
                hv.put("x", hit.hitVec.x);
                hv.put("y", hit.hitVec.y);
                hv.put("z", hit.hitVec.z);
                out.put("hit", hv);
            }
            if (mc.world != null) {
                net.minecraft.block.state.IBlockState state = mc.world.getBlockState(bp);
                out.put("block", state.getBlock().getRegistryName().toString());
                out.put("blockMeta", state.getBlock().getMetaFromState(state));
            }
        } else if (hit.typeOfHit == Type.ENTITY && hit.entityHit != null) {
            Entity e = hit.entityHit;
            out.put("entityId", e.getEntityId());
            out.put("entityUuid", e.getUniqueID().toString());
            out.put("entityClass", e.getClass().getName());
            out.put("entityName", e.getName());
        }
        try { ObserveRoutes.augmentLook(hit, out); }
        catch (Throwable t) { out.put("augmentLookError", t.getClass().getSimpleName() + ": " + t.getMessage()); }
        return out;
    }

    /** /holds — list currently-held keybinds from the HTTP-driven tick input. */
    private static Object snapshotHolds() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("holds", TickInput.get().snapshotHolds());
        return out;
    }

    private static Object snapshotServer() {
        Minecraft mc = Minecraft.getMinecraft();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("isIntegratedServerRunning", mc.isIntegratedServerRunning());
        ServerData sd = mc.getCurrentServerData();
        if (sd != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", sd.serverName);
            s.put("ip", sd.serverIP);
            s.put("motd", sd.serverMOTD == null ? null : sd.serverMOTD);
            s.put("gameVersion", sd.gameVersion);
            s.put("populationInfo", sd.populationInfo);
            s.put("pingMs", sd.pingToServer);
            out.put("currentServer", s);
        }
        if (mc.getConnection() != null && mc.player != null) {
            UUID uuid = mc.player.getUniqueID();
            NetworkPlayerInfo npi = mc.getConnection().getPlayerInfo(uuid);
            if (npi != null) {
                out.put("selfPingMs", npi.getResponseTime());
            }
            Map<String, Object> players = new LinkedHashMap<>();
            List<Map<String, Object>> list = new ArrayList<>();
            for (NetworkPlayerInfo info : mc.getConnection().getPlayerInfoMap()) {
                Map<String, Object> pi = new LinkedHashMap<>();
                pi.put("name", info.getGameProfile().getName());
                pi.put("uuid", info.getGameProfile().getId().toString());
                pi.put("pingMs", info.getResponseTime());
                pi.put("gameMode", info.getGameType() == null ? null : info.getGameType().getName());
                list.add(pi);
            }
            players.put("count", list.size());
            players.put("list", list);
            out.put("players", players);
        }
        return out;
    }

    /* -------------------------- Helpers -------------------------- */

    @SuppressWarnings("unchecked")
    private static Long2ObjectMap<Chunk> loadedChunkMap(ChunkProviderClient cp) {
        // Field names across 1.12.2 MCP revisions: `chunkMapping` (SRG
        // `field_73236_b`). ObfuscationReflectionHelper accepts either.
        try {
            return ObfuscationReflectionHelper.getPrivateValue(
                ChunkProviderClient.class, cp, "field_73236_b", "chunkMapping");
        } catch (Throwable t) {
            SF4Debug.LOG.warn("sf4debug: failed to read ChunkProviderClient#chunkMapping", t);
            return it.unimi.dsi.fastutil.longs.Long2ObjectMaps.EMPTY_MAP;
        }
    }

    static Object itemStackJson(ItemStack st) {
        if (st == null || st.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        Item item = st.getItem();
        m.put("id", item == null || item.getRegistryName() == null ? null : item.getRegistryName().toString());
        m.put("count", st.getCount());
        m.put("meta", st.getMetadata());
        m.put("displayName", st.getDisplayName());
        if (st.isItemDamaged()) {
            m.put("damage", st.getItemDamage());
            m.put("maxDamage", st.getMaxDamage());
        }
        if (st.hasTagCompound()) {
            // NBT as SNBT (Mojang textual NBT form).
            m.put("nbt", String.valueOf(st.getTagCompound()));
        }
        return m;
    }

    static Integer parseInt(String s, Integer fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    static double parseDouble(String s, double fallback) {
        if (s == null) return fallback;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; }
    }
}
