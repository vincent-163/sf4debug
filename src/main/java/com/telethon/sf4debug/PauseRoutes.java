package com.telethon.sf4debug;

import com.sun.net.httpserver.HttpServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.lwjgl.opengl.Display;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-option routes. Currently owns a single toggle:
 *
 * <p>{@code GET /noPauseOnMinimize?enable=1} — when on, suppresses the
 * "open the pause menu as soon as the window loses focus / is
 * minimized" behavior. Vanilla 1.12.2 does not do this directly, but
 * several modpack mods (and some OSes + fullscreen combinations) do.
 * The net effect: after toggling this on, alt-tabbing / minimizing the
 * Minecraft window will no longer dump the player into
 * {@link GuiIngameMenu} (and, because {@code isGamePaused} is only
 * set in singleplayer when a pausing screen is open, the game also
 * keeps ticking at full speed in singleplayer in the background).
 *
 * <p>The mechanism is a high-priority subscriber on
 * {@link GuiOpenEvent}: when the event is about to install a
 * {@link GuiIngameMenu} and {@link Display#isActive()} is {@code false}
 * (i.e. the window is currently minimized / unfocused), the event is
 * cancelled. A belt-and-suspenders {@link TickEvent.ClientTickEvent}
 * subscriber additionally closes any pause menu that slipped through
 * via a non-{@code displayGuiScreen} code path (some coremods swap
 * {@code currentScreen} directly) so long as the window is inactive.
 *
 * <p>Intentional non-goals: this does NOT close a pause menu the user
 * opened deliberately with Esc while the window was still focused
 * (the tick filter only fires while {@code !Display.isActive()}, so a
 * focused-then-minimized manual pause is preserved).
 *
 * <p>The initial state can be set at launch with
 * {@code -Dsf4debug.noPauseOnMinimize=1}; the runtime state is then
 * flipped by the HTTP route.
 */
public final class PauseRoutes {

    /** True when the "don't pause on minimize" override is active. */
    private static final AtomicBoolean NO_PAUSE = new AtomicBoolean(false);

    /** Cached reflection for {@code Minecraft.leftClickCounter} (SRG first, MCP fallback). */
    private static final Field LEFT_CLICK_COUNTER_FIELD;
    static {
        Field f = null;
        try {
            f = Minecraft.class.getDeclaredField("field_71429_W"); // SRG
            f.setAccessible(true);
        } catch (Throwable ignored) {
            try {
                f = Minecraft.class.getDeclaredField("leftClickCounter"); // MCP dev
                f.setAccessible(true);
            } catch (Throwable ignored2) {}
        }
        LEFT_CLICK_COUNTER_FIELD = f;
    }

    private PauseRoutes() {}

    /** Registers the Forge event subscribers. Call once from SF4Debug.init. */
    public static void init() {
        // Pick up the startup default from -Dsf4debug.noPauseOnMinimize=1.
        String prop = System.getProperty("sf4debug.noPauseOnMinimize");
        if (prop != null) NO_PAUSE.set(truthy(prop));
        MinecraftForge.EVENT_BUS.register(PauseRoutes.class);
    }

    /** Registers HTTP contexts. Call from DebugHttpServer.start. */
    public static void register(HttpServer server) {
        server.createContext("/noPauseOnMinimize",
            DebugHttpServer.wrap(PauseRoutes::noPauseOnMinimize));
        server.createContext("/options",
            DebugHttpServer.wrap(q -> options()));
    }

    /**
     * {@code GET /noPauseOnMinimize?enable=1|0} — read or toggle the
     * flag. Omitting {@code enable} just returns the current state.
     */
    public static Object noPauseOnMinimize(Map<String, String> q) {
        String s = q.get("enable");
        if (s != null && !s.isEmpty()) {
            NO_PAUSE.set(truthy(s));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("option", "noPauseOnMinimize");
        out.put("enabled", NO_PAUSE.get());
        out.put("windowActive", isWindowActive());
        return out;
    }

    /** {@code GET /options} — snapshot of every toggleable option. */
    public static Object options() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("noPauseOnMinimize", NO_PAUSE.get());
        out.put("windowActive", isWindowActive());
        return out;
    }

    /** True while the "don't pause on minimize" override is active. */
    public static boolean isNoPauseOnMinimize() {
        return NO_PAUSE.get();
    }

    /** {@link Display#isActive()} wrapped so a missing LWJGL at boot can't NPE us. */
    private static boolean isWindowActive() {
        try { return Display.isActive(); }
        catch (Throwable t) { return true; }
    }

    /**
     * Runs at HIGHEST priority so the event is cancelled before
     * {@link EventStream#onGuiOpen} (and any other subscriber) ever
     * sees it. The event is @Cancelable, so cancelled events won't
     * propagate to non-{@code receiveCanceled} listeners.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onGuiOpen(GuiOpenEvent event) {
        if (!NO_PAUSE.get()) return;
        if (!(event.getGui() instanceof GuiIngameMenu)) return;
        if (isWindowActive()) return;
        // Window is minimized/unfocused AND a pause menu is being
        // opened → suppress it. The player stays in-world, the game
        // keeps ticking in singleplayer.
        event.setCanceled(true);
    }

    /**
     * Belt and suspenders: if a coremod / mixin opens the pause menu
     * by writing {@code Minecraft#currentScreen} directly (bypassing
     * {@code displayGuiScreen} and therefore {@link GuiOpenEvent}),
     * close it on the next client tick whenever the window is still
     * inactive. Only fires while the window is unfocused, so a
     * focused-then-minimized manual pause is preserved.
     *
     * <p>Additionally, when the window is inactive and no GUI is open,
     * force {@code inGameHasFocus} to {@code true}. Vanilla's
     * {@code setIngameFocus()} only sets this when
     * {@code Display.isActive()} is true, so closing a GUI (or loading
     * into the world) while minimized leaves the game in a broken state
     * where block-breaking, attacking, and other focus-gated
     * interactions silently fail.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        if (!NO_PAUSE.get()) return;
        if (isWindowActive()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        // Close any pause menu that slipped through.
        if (mc.currentScreen instanceof GuiIngameMenu) {
            mc.displayGuiScreen(null);
        }

        // If no GUI is open but inGameHasFocus is false (because
        // displayGuiScreen(null) -> setIngameFocus() bailed out when
        // it saw Display.isActive() == false), force it back to true
        // so mining, attacking, and placing work while minimized.
        if (mc.currentScreen == null && !mc.inGameHasFocus) {
            mc.inGameHasFocus = true;

            // Also reset the left-click delay counter so attacks don't
            // get eaten for thousands of ticks after a GUI closes.
            resetLeftClickCounter(mc);
        }
    }

    /** Reset {@code Minecraft.leftClickCounter} to 0 via reflection. */
    public static void resetLeftClickCounter(Minecraft mc) {
        if (LEFT_CLICK_COUNTER_FIELD == null || mc == null) return;
        try {
            LEFT_CLICK_COUNTER_FIELD.setInt(mc, 0);
        } catch (Throwable ignored) {}
    }

    /** Accept {@code 1/true/yes/on/y/t} as "enable". Everything else disables. */
    private static boolean truthy(String s) {
        if (s == null) return false;
        String v = s.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes")
            || v.equals("on") || v.equals("y") || v.equals("t");
    }
}
