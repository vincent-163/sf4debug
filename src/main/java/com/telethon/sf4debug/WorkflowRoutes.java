package com.telethon.sf4debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Group 7 — workflow and continuity.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code /batch} — POST body of JSON array of
 *       {@code {"path":"/player"}} objects; each is invoked in order
 *       over loopback and the responses are returned in the same order.
 *       Reduces RTT overhead when the agent needs many snapshots in a
 *       single reasoning step.</li>
 *   <li>{@code /diff?keys=player,look,...&reset=0} — returns only the
 *       leaf values that changed since the previous /diff call with
 *       the same set of keys. Pass {@code reset=1} to clear history
 *       without returning a diff.</li>
 *   <li>{@code /tick} — current client-tick counter + partial-tick.
 *       Useful to correlate event-stream cursor progress with
 *       wall-clock action bandwidth.</li>
 *   <li>{@code /input?dx=&dy=&wheel=} — inject raw mouse motion
 *       through {@code EntityPlayerSP.turn}, exactly the way vanilla
 *       {@code MouseHelper} does on the client tick. Honors the
 *       player's invert-mouse and sensitivity settings.</li>
 *   <li>{@code /mouse?x=&y=&button=&action=click|down|up|move} —
 *       generic GUI mouse injection. In a GUI, dispatches via
 *       {@link GuiRoutes} helpers; out of GUI, delegates to the
 *       attack/use keybinds.</li>
 *   <li>{@code /aimStatus} — current aim-plan state (smooth, path,
 *       or entity-track) from {@link TickInput#snapshotAim()}.</li>
 *   <li>{@code /aimPath?legs=yaw:pitch:ticks,yaw:pitch:ticks,...} —
 *       schedule a multi-waypoint aim path.</li>
 *   <li>{@code /aimAt.entity?id=N&ticks=T&ease=E&eye=1} — live-track
 *       entity {@code id} for {@code T} client ticks.</li>
 *   <li>{@code /cancel?tag=all|aim|holds|eat|wait|trades|pending} —
 *       cancel an active operation by tag. Operations registered via
 *       {@link #registerCancelable(String)} check their flag each
 *       tick / each HTTP-thread loop iteration and bail.</li>
 * </ul>
 */
public final class WorkflowRoutes {

    private WorkflowRoutes() {}

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Gson GSON_PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    /** Client-tick counter; incremented on every CLIENT START tick. */
    private static final AtomicLong TICK_COUNTER = new AtomicLong();

    /** Per-key snapshot cache for /diff. */
    private static final Map<String, Object> DIFF_CACHE = new ConcurrentHashMap<>();

    /** Cancelable operations registry: tag -> flag. Setting flag=true
     *  asks every consumer that checks it to abort at the next safe
     *  checkpoint. Consumers register by calling {@link #registerCancelable}. */
    private static final Map<String, AtomicBoolean> CANCEL_FLAGS = new ConcurrentHashMap<>();

    public static void register(HttpServer server) {
        // Long-running / raw-body endpoints bypass DebugHttpServer.wrap
        // because wrap() always hops onto the client thread with a 2s
        // timeout. /batch and /input should run on the HTTP executor.
        server.createContext("/batch", WorkflowRoutes::handleBatch);
        server.createContext("/diff", DebugHttpServer.wrap(WorkflowRoutes::diff));
        server.createContext("/tick", DebugHttpServer.wrap(WorkflowRoutes::tick));
        server.createContext("/input", DebugHttpServer.wrap(WorkflowRoutes::input));
        server.createContext("/mouse", DebugHttpServer.wrap(WorkflowRoutes::mouse));
        server.createContext("/aimStatus", DebugHttpServer.wrap(WorkflowRoutes::aimStatus));
        server.createContext("/aimPath", DebugHttpServer.wrap(WorkflowRoutes::aimPath));
        server.createContext("/aimAt.entity", DebugHttpServer.wrap(WorkflowRoutes::aimAtEntity));
        server.createContext("/cancel", DebugHttpServer.wrap(WorkflowRoutes::cancel));

        MinecraftForge.EVENT_BUS.register(new TickCounter());
    }

    /** Simple {@code @SubscribeEvent} holder so we don't pollute this
     *  class's public surface. */
    public static final class TickCounter {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent ev) {
            if (ev.phase == TickEvent.Phase.START) TICK_COUNTER.incrementAndGet();
        }
    }

    /* ============================ /batch ============================ */

    private static void handleBatch(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> q = DebugHttpServer.parseQuery(exchange.getRequestURI());
            boolean pretty = q.containsKey("pretty");
            // Read request body: accept either GET ?cmds=JSON or POST body.
            String body;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                    body = sb.toString();
                }
            } else {
                body = q.get("cmds");
            }
            if (body == null || body.isEmpty()) {
                write(exchange, 400, errJson("missing body (POST JSON array or ?cmds=)"));
                return;
            }
            JsonElement root = new JsonParser().parse(body);
            if (!root.isJsonArray()) {
                write(exchange, 400, errJson("body must be a JSON array"));
                return;
            }
            JsonArray arr = root.getAsJsonArray();
            int port = exchange.getLocalAddress().getPort();
            String host = exchange.getLocalAddress().getAddress().getHostAddress();
            Map<String, Object> result = new LinkedHashMap<>();
            List<Map<String, Object>> responses = new ArrayList<>();
            int idx = 0;
            for (JsonElement el : arr) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("index", idx++);
                if (!el.isJsonObject()) {
                    one.put("error", "entry is not an object");
                    responses.add(one);
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                String path = obj.has("path") ? obj.get("path").getAsString() : null;
                if (path == null || path.isEmpty() || !path.startsWith("/")) {
                    one.put("error", "missing or invalid 'path'");
                    responses.add(one);
                    continue;
                }
                // Build query string from "query" object OR "q" shorthand.
                StringBuilder url = new StringBuilder("http://").append(host).append(':').append(port).append(path);
                JsonObject qobj = obj.has("query") && obj.get("query").isJsonObject()
                        ? obj.getAsJsonObject("query")
                        : (obj.has("q") && obj.get("q").isJsonObject() ? obj.getAsJsonObject("q") : null);
                if (qobj != null && qobj.size() > 0) {
                    url.append('?');
                    boolean first = true;
                    for (Map.Entry<String, JsonElement> e : qobj.entrySet()) {
                        if (!first) url.append('&');
                        first = false;
                        url.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                           .append('=')
                           .append(URLEncoder.encode(
                                   e.getValue().isJsonPrimitive() ? e.getValue().getAsString()
                                                                  : e.getValue().toString(),
                                   "UTF-8"));
                    }
                }
                one.put("path", path);
                try {
                    String sub = callLoopback(url.toString());
                    JsonElement parsed;
                    try { parsed = new JsonParser().parse(sub); }
                    catch (Throwable t) { parsed = null; }
                    one.put("ok", true);
                    if (parsed != null) one.put("response", jsonToObject(parsed));
                    else one.put("rawResponse", sub);
                } catch (Throwable t) {
                    one.put("ok", false);
                    one.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                responses.add(one);
            }
            result.put("count", responses.size());
            result.put("responses", responses);
            String jsonOut = (pretty ? GSON_PRETTY : GSON).toJson(result);
            write(exchange, 200, jsonOut);
        } catch (Throwable t) {
            write(exchange, 500, errJson(t.getClass().getSimpleName() + ": " + t.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private static String callLoopback(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(2000);
        con.setReadTimeout(10_000);
        con.setRequestMethod("GET");
        int code = con.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(code < 400 ? con.getInputStream() : con.getErrorStream(),
                        StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object jsonToObject(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isString()) return p.getAsString();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < Long.MAX_VALUE) {
                    return (long) d;
                }
                return d;
            }
            return p.getAsString();
        }
        if (e.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement c : e.getAsJsonArray()) list.add(jsonToObject(c));
            return list;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
            m.put(en.getKey(), jsonToObject(en.getValue()));
        }
        return m;
    }

    /* ============================ /diff ============================ */

    private static final List<String> DIFF_DEFAULT = Arrays.asList(
            "player", "look", "hotbar", "holds", "world", "overlay", "fps");

    private static Object diff(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        String keys = q.get("keys");
        if ("1".equals(q.get("reset"))) {
            DIFF_CACHE.clear();
            out.put("ok", true);
            out.put("reset", true);
            return out;
        }
        List<String> sections = keys == null || keys.isEmpty() ? DIFF_DEFAULT
                : Arrays.asList(keys.split(","));
        Map<String, Object> current = new LinkedHashMap<>();
        for (String s : sections) {
            try {
                current.put(s, snapshotFor(s));
            } catch (Throwable t) {
                current.put(s, Collections.singletonMap("error", t.toString()));
            }
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        for (String s : sections) {
            Object prev = DIFF_CACHE.get(s);
            Object now = current.get(s);
            if (prev == null) {
                changed.put(s, now);
            } else {
                Object delta = diffValue(prev, now);
                if (delta != null) changed.put(s, delta);
            }
            if (now != null) DIFF_CACHE.put(s, now);
        }
        out.put("keys", sections);
        out.put("changed", changed);
        out.put("tick", TICK_COUNTER.get());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object snapshotFor(String key) throws Exception {
        // Use the root HTTP handler paths via reflection on DebugHttpServer
        // snapshot methods? Simpler: mirror the logic directly.
        switch (key) {
            case "player":    return invokeSnap("snapshotPlayer");
            case "look":      return invokeSnap("snapshotLook");
            case "hotbar":    return invokeSnap("snapshotHotbar");
            case "inventory": return invokeSnap("snapshotInventory");
            case "holds":     return invokeSnap("snapshotHolds");
            case "fps":       return invokeSnap("snapshotFps");
            case "world": {
                Map<String, Object> m = new LinkedHashMap<>();
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.world != null) {
                    m.put("dimension", mc.world.provider.getDimension());
                    m.put("totalWorldTime", mc.world.getTotalWorldTime());
                    m.put("worldTime", mc.world.getWorldTime() % 24000);
                    m.put("isRaining", mc.world.isRaining());
                    m.put("isThundering", mc.world.isThundering());
                }
                return m;
            }
            case "overlay": {
                Map<String, Object> m = new LinkedHashMap<>();
                try { ObserveRoutes.augmentState(m); } catch (Throwable ignored) {}
                return m;
            }
            default: return Collections.singletonMap("error", "unknown section: " + key);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object invokeSnap(String method) throws Exception {
        java.lang.reflect.Method m = DebugHttpServer.class.getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(null);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object diffValue(Object prev, Object now) {
        if (prev == null && now == null) return null;
        if (prev == null || now == null) return now;
        if (prev instanceof Map && now instanceof Map) {
            Map<String, Object> p = (Map<String, Object>) prev;
            Map<String, Object> c = (Map<String, Object>) now;
            Map<String, Object> out = new LinkedHashMap<>();
            Set<String> keys = new java.util.LinkedHashSet<>();
            keys.addAll(p.keySet());
            keys.addAll(c.keySet());
            for (String k : keys) {
                Object pv = p.get(k);
                Object cv = c.get(k);
                Object d = diffValue(pv, cv);
                if (d != null) out.put(k, d);
                else if (cv == null && p.containsKey(k) && !c.containsKey(k)) out.put(k, null);
            }
            return out.isEmpty() ? null : out;
        }
        if (prev instanceof List && now instanceof List) {
            List<?> pl = (List<?>) prev;
            List<?> cl = (List<?>) now;
            if (pl.size() != cl.size()) return cl;
            for (int i = 0; i < pl.size(); i++) {
                if (!safeEquals(pl.get(i), cl.get(i))) return cl;
            }
            return null;
        }
        return safeEquals(prev, now) ? null : now;
    }

    private static boolean safeEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    /* ============================ /tick ============================ */

    private static Object tick(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        out.put("clientTick", TICK_COUNTER.get());
        out.put("fps", Minecraft.getDebugFPS());
        if (mc.world != null) {
            out.put("totalWorldTime", mc.world.getTotalWorldTime());
            out.put("worldTime", mc.world.getWorldTime());
        }
        // Minecraft.timer.renderPartialTicks reflectively.
        try {
            Object timer = readPrivate(mc, new String[] { "field_71428_T", "timer" });
            if (timer != null) {
                java.lang.reflect.Field pt = findFieldAny(timer.getClass(),
                        new String[] { "field_74281_c", "renderPartialTicks" });
                if (pt != null) out.put("partialTicks", pt.getFloat(timer));
                java.lang.reflect.Field ee = findFieldAny(timer.getClass(),
                        new String[] { "field_74280_b", "elapsedPartialTicks" });
                if (ee != null) out.put("elapsedPartialTicks", ee.getFloat(timer));
                java.lang.reflect.Field et = findFieldAny(timer.getClass(),
                        new String[] { "field_74285_i", "elapsedTicks" });
                if (et != null) out.put("elapsedTicks", et.getInt(timer));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static Object readPrivate(Object target, String[] names) {
        for (String n : names) {
            try {
                java.lang.reflect.Field f = target.getClass().getDeclaredField(n);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static java.lang.reflect.Field findFieldAny(Class<?> owner, String[] names) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
        }
        return null;
    }

    /* ============================ /input ============================ */

    private static Object input(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) { out.put("error", "no player"); return out; }
        double dx = DebugHttpServer.parseDouble(q.get("dx"), 0);
        double dy = DebugHttpServer.parseDouble(q.get("dy"), 0);
        double wheel = DebugHttpServer.parseDouble(q.get("wheel"), 0);
        // EntityPlayerSP.turn(float,float) applies sensitivity + invert-mouse
        // the same way vanilla MouseHelper does on the client tick:
        //     this.turn(f * mouseSensitivity * 0.15f, f1 * -sens * 0.15f * (invert ? -1 : 1))
        // so callers should pass *raw* pixel deltas; vanilla handles scale.
        try {
            p.turn((float) dx, (float) dy);
            out.put("ok", true);
            out.put("dx", dx);
            out.put("dy", dy);
            out.put("yaw", p.rotationYaw);
            out.put("pitch", p.rotationPitch);
        } catch (Throwable t) {
            out.put("error", t.toString());
        }
        if (wheel != 0 && mc.player != null && mc.currentScreen == null) {
            // In-world mouse wheel = hotbar scroll in vanilla.
            int cur = p.inventory.currentItem;
            int dir = wheel > 0 ? -1 : 1;
            int target = ((cur + dir) % 9 + 9) % 9;
            p.inventory.currentItem = target;
            out.put("hotbarSlot", target);
        }
        return out;
    }

    /* ============================ /mouse ============================ */

    private static Object mouse(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        String action = q.getOrDefault("action", "click");
        int x = DebugHttpServer.parseInt(q.get("x"), 0);
        int y = DebugHttpServer.parseInt(q.get("y"), 0);
        int button = DebugHttpServer.parseInt(q.get("button"), 0);
        if (mc.currentScreen != null) {
            // Delegate to GuiRoutes' existing dispatch via reflection.
            Map<String, String> guiQ = new java.util.HashMap<>(q);
            guiQ.put("x", Integer.toString(x));
            guiQ.put("y", Integer.toString(y));
            guiQ.put("button", Integer.toString(button));
            switch (action.toLowerCase()) {
                case "down":
                case "click": return GuiRoutes.guiClick(guiQ);
                case "up":
                case "release": {
                    guiQ.put("state", Integer.toString(button));
                    return GuiRoutes.guiRelease(guiQ);
                }
                case "drag":
                case "move": return GuiRoutes.guiDrag(guiQ);
                default:
                    out.put("error", "unknown action " + action + " for gui");
                    return out;
            }
        }
        // In-world: map LMB/RMB to attack/use keybinds.
        switch (action.toLowerCase()) {
            case "down": {
                if (button == 0) TickInput.get().hold(mc.gameSettings.keyBindAttack, 2);
                if (button == 1) TickInput.get().hold(mc.gameSettings.keyBindUseItem, 2);
                out.put("ok", true);
                break;
            }
            case "click": {
                if (button == 0) TickInput.get().click(mc.gameSettings.keyBindAttack);
                if (button == 1) TickInput.get().click(mc.gameSettings.keyBindUseItem);
                out.put("ok", true);
                break;
            }
            case "up": {
                if (button == 0) TickInput.get().release(mc.gameSettings.keyBindAttack);
                if (button == 1) TickInput.get().release(mc.gameSettings.keyBindUseItem);
                out.put("ok", true);
                break;
            }
            default:
                out.put("error", "unknown action " + action + " in-world");
        }
        return out;
    }

    /* ============================ /aimStatus ============================ */

    private static Object aimStatus(Map<String, String> q) {
        return TickInput.get().snapshotAim();
    }

    /* ============================ /aimPath ============================ */

    /** Parse "y:p:t,y:p:t,..." into three arrays. */
    private static Object aimPath(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        String legs = q.get("legs");
        if (legs == null || legs.isEmpty()) {
            out.put("error", "missing legs=yaw:pitch:ticks,yaw:pitch:ticks,...");
            return out;
        }
        String[] parts = legs.split(",");
        float[] yaws = new float[parts.length];
        float[] pitches = new float[parts.length];
        int[] ticks = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                String[] sub = parts[i].split(":");
                if (sub.length != 3) { out.put("error", "leg " + i + " not y:p:t"); return out; }
                yaws[i] = Float.parseFloat(sub[0]);
                pitches[i] = Float.parseFloat(sub[1]);
                ticks[i] = Math.max(1, Integer.parseInt(sub[2]));
            }
        } catch (NumberFormatException e) {
            out.put("error", "parse: " + e.getMessage());
            return out;
        }
        TickInput.get().scheduleAimPath(yaws, pitches, ticks);
        out.put("ok", true);
        out.put("legCount", parts.length);
        out.put("totalTicks", Arrays.stream(ticks).sum());
        return out;
    }

    /* ============================ /aimAt.entity ============================ */

    private static Object aimAtEntity(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Integer id = DebugHttpServer.parseInt(q.get("id"), null);
        if (id == null) { out.put("error", "missing id"); return out; }
        int ticks = Math.max(1, DebugHttpServer.parseInt(q.get("ticks"), 20));
        int ease = Math.max(1, DebugHttpServer.parseInt(q.get("ease"), 4));
        boolean eye = "1".equals(q.get("eye")) || "true".equalsIgnoreCase(q.get("eye"));
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) { out.put("error", "no world"); return out; }
        net.minecraft.entity.Entity e = mc.world.getEntityByID(id);
        if (e == null) { out.put("error", "entity id not found"); return out; }
        TickInput.get().scheduleEntityTrack(id, ticks, ease, eye);
        out.put("ok", true);
        out.put("entity", e.getClass().getSimpleName());
        out.put("entityName", e.getName());
        out.put("ticks", ticks);
        out.put("ease", ease);
        return out;
    }

    /* ============================ /cancel ============================ */

    /** Marker a long-running operation registers so callers can abort it. */
    public static AtomicBoolean registerCancelable(String tag) {
        return CANCEL_FLAGS.computeIfAbsent(tag, k -> new AtomicBoolean(false));
    }

    private static Object cancel(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        String tag = q.getOrDefault("tag", "all");
        List<String> fired = new ArrayList<>();
        Minecraft mc = Minecraft.getMinecraft();
        if ("all".equals(tag) || "aim".equals(tag)) {
            TickInput.get().cancelAim();
            fired.add("aim");
        }
        if ("all".equals(tag) || "holds".equals(tag)) {
            // cancelAim already inside releaseAll; just drop holds:
            for (Map.Entry<String, Integer> e : TickInput.get().snapshotHolds().entrySet()) {
                // no-op marker — releaseAll is a stronger "all"
            }
            if ("all".equals(tag)) TickInput.get().releaseAll();
            fired.add("holds");
        }
        // Per-tag flag-based cancelation for HelperRoutes /wait, /eatUntilFull.
        for (Map.Entry<String, AtomicBoolean> e : CANCEL_FLAGS.entrySet()) {
            if ("all".equals(tag) || tag.equals(e.getKey())) {
                e.getValue().set(true);
                fired.add(e.getKey());
            }
        }
        out.put("ok", true);
        out.put("tag", tag);
        out.put("fired", fired);
        return out;
    }

    /* ============================ helpers ============================ */

    private static void write(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String errJson(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "BadRequest");
        m.put("message", message);
        return GSON.toJson(m);
    }

    public static long currentTick() { return TICK_COUNTER.get(); }
}
