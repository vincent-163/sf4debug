package com.telethon.sf4debug;

import com.sun.net.httpserver.HttpServer;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime configurable tick rate. Slows or speeds up the world by
 * matching <em>both</em> the integrated-server tick period and the
 * client-side {@link net.minecraft.util.Timer}'s {@code tickLength}
 * field so visual motion stays in sync with server-side physics.
 *
 * <p>Vanilla 1.12.2 hard-codes both loops to 50 ms/tick (20 TPS):
 *
 * <ul>
 *   <li>{@code MinecraftServer.run()} has an inner
 *       {@code while (i > 50L)} catch-up loop that runs
 *       {@code tick()} back-to-back whenever real time gets ahead of
 *       virtual time. The {@code 50L} is baked into bytecode.</li>
 *   <li>{@link net.minecraft.util.Timer#updateTimer()} advances
 *       {@code elapsedPartialTicks} by
 *       {@code (now - lastSyncSysClock) / tickLength} each frame,
 *       where {@code tickLength} defaults to {@code 1000/20 = 50}.
 *       This field is private-but-not-final in 1.12.2.</li>
 * </ul>
 *
 * <p>Strategy:
 *
 * <ol>
 *   <li><b>Server side.</b> A {@code @SubscribeEvent} on
 *       {@link TickEvent.ServerTickEvent} captures the tick start time
 *       at {@code Phase.START} and at {@code Phase.END} blocks the
 *       integrated-server thread until the tick has taken
 *       {@code 1000/targetTps} ms of wall time. The vanilla catch-up
 *       loop still fires but each catch-up tick itself takes the new
 *       period, so the effective long-run rate converges to the
 *       target TPS (the vanilla "Can't keep up!" warning may fire
 *       once per 15 s — that's fine).</li>
 *   <li><b>Client side.</b> We write {@code Timer.tickLength} with
 *       reflection (SRG {@code field_194149_e}, MCP {@code tickLength})
 *       so the client's tick accumulator advances at the same rate as
 *       the server is producing ticks. Without this step the client
 *       would still run at 20 TPS, sending two input packets per
 *       server tick and visually desynchronizing motion.</li>
 * </ol>
 *
 * <p>Only single-player (integrated server running) is supported.
 * Attempting to change the rate in multiplayer is rejected — the
 * server process is on the other side of the network and this mod is
 * {@code clientSideOnly}.</p>
 *
 * <p>Bounds: {@code 1 <= tps <= 100}. {@code tps=20} is the vanilla
 * default and disables both the server sleep and the timer override.
 * {@code tps<20} slows down the world; {@code tps>20} speeds it up
 * (client accepts values &gt;20 freely; the server throttle is a
 * sleep-only mechanism and cannot exceed vanilla 20 TPS, so the
 * server stays at vanilla rate while the client renders faster —
 * this is documented as a known asymmetry).</p>
 */
public final class TickRateRoutes {

    /** Vanilla server tick rate (also the "no override" sentinel). */
    public static final int DEFAULT_TPS = 20;
    /** Lower bound for callers; below this the server falls over. */
    public static final int MIN_TPS = 1;
    /** Upper bound; above this the client renders but server can't keep up. */
    public static final int MAX_TPS = 100;

    /** Current configured tick rate. Read-only from the HTTP thread. */
    private static final AtomicInteger TARGET_TPS = new AtomicInteger(DEFAULT_TPS);

    /** Wall-clock millis captured at the start of the current server tick. */
    private static final AtomicLong TICK_START_MS = new AtomicLong(0L);

    private TickRateRoutes() {}

    /** Registers the server-tick subscriber. Call once from SF4Debug.init. */
    public static void init() {
        MinecraftForge.EVENT_BUS.register(TickRateRoutes.class);
    }

    /** Registers the HTTP context. Call from DebugHttpServer.start. */
    public static void register(HttpServer server) {
        server.createContext("/tickrate",
            DebugHttpServer.wrap(TickRateRoutes::tickrate));
    }

    /** Current target TPS (20 when no override is active). */
    public static int getTargetTps() {
        return TARGET_TPS.get();
    }

    /**
     * Applies a new target TPS. In single-player, also reflects the
     * matching {@code tickLength} value into
     * {@link net.minecraft.util.Timer}. Returns an error string on
     * failure, or {@code null} on success.
     */
    public static String applyTickRate(int tps) {
        if (tps < MIN_TPS || tps > MAX_TPS) {
            return "tick rate must be between " + MIN_TPS + " and " + MAX_TPS;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isIntegratedServerRunning()) {
            return "tick rate can only be changed in single-player (integrated server must be running)";
        }
        TARGET_TPS.set(tps);
        // Sync client Timer so visual motion matches server tick rate.
        try {
            setClientTickLength(1000f / (float) tps);
        } catch (Throwable t) {
            SF4Debug.LOG.warn("sf4debug: failed to update client Timer.tickLength", t);
            // Non-fatal — server side still throttles via sleep, but
            // client motion may look jittery until reset.
        }
        return null;
    }

    /**
     * Resets to the vanilla 20 TPS. Always succeeds.
     */
    public static void reset() {
        TARGET_TPS.set(DEFAULT_TPS);
        try { setClientTickLength(1000f / (float) DEFAULT_TPS); }
        catch (Throwable ignored) {}
    }

    /**
     * {@code GET /tickrate?rate=N} — read or set the current tick
     * rate. Omitting {@code rate} just returns the current state.
     */
    public static Object tickrate(Map<String, String> q) {
        String s = q.get("rate");
        Map<String, Object> out = new LinkedHashMap<>();
        String err = null;
        if (s != null && !s.isEmpty()) {
            int tps;
            try { tps = Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) {
                out.put("error", "invalid rate: " + s);
                out.put("currentTps", TARGET_TPS.get());
                return out;
            }
            err = applyTickRate(tps);
        }
        if (err != null) out.put("error", err);
        out.put("currentTps", TARGET_TPS.get());
        out.put("defaultTps", DEFAULT_TPS);
        out.put("minTps", MIN_TPS);
        out.put("maxTps", MAX_TPS);
        Minecraft mc = Minecraft.getMinecraft();
        out.put("integratedServerRunning", mc.isIntegratedServerRunning());
        out.put("clientTickLengthMs", readClientTickLength());
        return out;
    }

    /**
     * Integrated-server tick throttle. Fires on the server thread —
     * sleeping here slows that thread down without affecting the
     * client render loop.
     *
     * <p>{@code Phase.START} records the wall-clock tick start.
     * {@code Phase.END} blocks until {@code 1000/target} ms have
     * elapsed since start. Vanilla's post-tick {@code Thread.sleep}
     * in {@code MinecraftServer.run()} then covers the remaining
     * inter-iteration idle.</p>
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent ev) {
        int tps = TARGET_TPS.get();
        if (tps <= 0 || tps >= DEFAULT_TPS) {
            // No override (>=20) or invalid — let vanilla run normally.
            return;
        }
        if (ev.phase == TickEvent.Phase.START) {
            TICK_START_MS.set(System.currentTimeMillis());
            return;
        }
        if (ev.phase != TickEvent.Phase.END) return;
        long targetMs = 1000L / (long) tps;
        long elapsed = System.currentTimeMillis() - TICK_START_MS.get();
        long remaining = targetMs - elapsed;
        if (remaining <= 0) return;
        try {
            Thread.sleep(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Safety net for the "slowed SP → joined MP" transition: when the
     * client disconnects from the current session, reset the override
     * so a subsequent connection to a remote server (or a fresh SP
     * load) starts at the vanilla 20 TPS without the client's
     * {@code Timer.tickLength} still lagging behind the 20-TPS server.
     * Users who want the override again can re-issue {@code /tickrate}
     * in the next SP session.
     */
    @SubscribeEvent
    public static void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent ev) {
        reset();
    }

    /* -------------------- client Timer reflection -------------------- */

    /**
     * Writes {@link net.minecraft.util.Timer#tickLength} (SRG
     * {@code field_194149_e}) on {@code Minecraft.timer} so the
     * client's tick accumulator steps at the same rate as the
     * (throttled) server is producing ticks.
     */
    private static void setClientTickLength(float tickLengthMs) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        Object timer = getMinecraftTimer(mc);
        if (timer == null) return;
        Field f = findField(timer.getClass(),
                "field_194149_e",  // SRG
                "tickLength");     // MCP
        if (f == null) return;
        f.setAccessible(true);
        f.setFloat(timer, tickLengthMs);
    }

    /** Reads the current client {@code Timer.tickLength} for the JSON response. */
    private static Object readClientTickLength() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            Object timer = getMinecraftTimer(mc);
            if (timer == null) return null;
            Field f = findField(timer.getClass(),
                    "field_194149_e", "tickLength");
            if (f == null) return null;
            f.setAccessible(true);
            return f.getFloat(timer);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code Minecraft.timer} — SRG {@code field_71428_T}, MCP {@code timer}. */
    private static Object getMinecraftTimer(Minecraft mc) throws Exception {
        Field f = findField(Minecraft.class, "field_71428_T", "timer");
        if (f == null) return null;
        f.setAccessible(true);
        return f.get(mc);
    }

    /**
     * Walks the class hierarchy looking for the first field whose name
     * matches any of {@code names}. Returns {@code null} when none
     * match — caller must handle the missing-field case.
     */
    private static Field findField(Class<?> owner, String... names) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
        }
        return null;
    }
}
