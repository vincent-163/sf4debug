package com.telethon.sf4debug;

import com.sun.net.httpserver.HttpServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic and hot-patch endpoints for remote debugging.
 *
 * <p>These routes expose internal Minecraft/mod state that is normally
 * invisible over the HTTP API, and allow limited hot-patching when a
 * specific field needs to be nudged without restarting the client.
 */
public final class DebugRoutes {

    private DebugRoutes() {}

    public static void register(HttpServer server) {
        server.createContext("/debug/keyState",     DebugHttpServer.wrap(DebugRoutes::keyState));
        server.createContext("/debug/leftClickCounter", DebugHttpServer.wrap(DebugRoutes::leftClickCounter));
        server.createContext("/debug/tickInput",    DebugHttpServer.wrap(DebugRoutes::tickInput));
        server.createContext("/debug/log",          DebugHttpServer.wrap(DebugRoutes::logTail));
        server.createContext("/debug/forceKey",     DebugHttpServer.wrap(DebugRoutes::forceKey));
        server.createContext("/debug/currentScreen",DebugHttpServer.wrap(DebugRoutes::currentScreen));
    }

    /** {@code GET /debug/keyState?name=attack} */
    private static Object keyState(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        String name = q.get("name");
        if (name == null || name.isEmpty()) {
            out.put("error", "missing name");
            return out;
        }
        KeyBinding kb = ActionRoutes.resolveKey(mc.gameSettings, name);
        if (kb == null) {
            out.put("error", "unknown key: " + name);
            return out;
        }
        out.put("name", name);
        out.put("keyCode", kb.getKeyCode());
        out.put("keyDescription", kb.getKeyDescription());
        out.put("pressed", kb.isKeyDown());
        out.put("pressTime", getPressTime(kb));
        out.put("isKeyDown", kb.isKeyDown());
        out.put("isPressed", kb.isPressed());
        return out;
    }

    /**
     * {@code GET /debug/leftClickCounter} — read the current value.
     * {@code GET /debug/leftClickCounter?set=0} — reset it.
     */
    private static Object leftClickCounter(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        int current = readLeftClickCounter(mc);
        out.put("value", current);
        String setStr = q.get("set");
        if (setStr != null && !setStr.isEmpty()) {
            try {
                int v = Integer.parseInt(setStr);
                PauseRoutes.resetLeftClickCounter(mc);
                // resetLeftClickCounter hard-codes 0; if caller wants a
                // different value we need to write it ourselves.
                if (v != 0) writeLeftClickCounter(mc, v);
                out.put("set", v);
                out.put("valueAfter", readLeftClickCounter(mc));
            } catch (NumberFormatException e) {
                out.put("error", "invalid set value");
            }
        }
        return out;
    }

    /** {@code GET /debug/tickInput} — full TickInput state snapshot. */
    private static Object tickInput(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        TickInput ti = TickInput.get();
        out.put("holds", ti.snapshotHolds());
        out.put("aim", ti.snapshotAim());
        return out;
    }

    /**
     * {@code GET /debug/log?lines=100} — return the last N lines of
     * {@code logs/latest.log} under the Minecraft game directory.
     * The content is returned inline; no filesystem path is exposed.
     */
    private static Object logTail(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        int lines = DebugHttpServer.parseInt(q.get("lines"), 50);
        if (lines < 1) lines = 1;
        if (lines > 5000) lines = 5000;
        Minecraft mc = Minecraft.getMinecraft();
        File logFile = new File(mc.mcDataDir, "logs/latest.log");
        try {
            List<String> tail = readTail(logFile, lines);
            out.put("lines", tail.size());
            out.put("log", tail);
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    /**
     * {@code GET /debug/forceKey?name=attack&pressed=1}
     *
     * Directly calls {@link KeyBinding#setKeyBindState} for the named
     * binding. This bypasses TickInput's hold mechanism and is useful
     * for ad-hoc testing when you want to see whether the game reacts
     * to a specific binding being pressed.
     */
    private static Object forceKey(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        String name = q.get("name");
        if (name == null || name.isEmpty()) {
            out.put("error", "missing name");
            return out;
        }
        KeyBinding kb = ActionRoutes.resolveKey(mc.gameSettings, name);
        if (kb == null) {
            out.put("error", "unknown key: " + name);
            return out;
        }
        boolean pressed = "1".equals(q.get("pressed")) || "true".equalsIgnoreCase(q.get("pressed"));
        KeyBinding.setKeyBindState(kb.getKeyCode(), pressed);
        out.put("name", name);
        out.put("pressed", pressed);
        out.put("keyCode", kb.getKeyCode());
        return out;
    }

    /** {@code GET /debug/currentScreen} */
    private static Object currentScreen(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        out.put("currentScreen", screen == null ? null : screen.getClass().getName());
        out.put("inGameHasFocus", mc.inGameHasFocus);
        out.put("leftClickCounter", readLeftClickCounter(mc));
        out.put("noPauseOnMinimize", PauseRoutes.isNoPauseOnMinimize());
        return out;
    }

    /* ========================= helpers ========================= */

    /** Read {@code pressTime} via reflection (MCP + SRG fallback). */
    private static int getPressTime(KeyBinding kb) {
        if (kb == null) return -1;
        try {
            java.lang.reflect.Field f = KeyBinding.class.getDeclaredField("pressTime");
            f.setAccessible(true);
            return f.getInt(kb);
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Field f = KeyBinding.class.getDeclaredField("field_151474_i");
                f.setAccessible(true);
                return f.getInt(kb);
            } catch (Throwable ignored2) {
                return -1;
            }
        }
    }

    private static int readLeftClickCounter(Minecraft mc) {
        if (mc == null) return -1;
        try {
            java.lang.reflect.Field f = Minecraft.class.getDeclaredField("field_71429_W");
            f.setAccessible(true);
            return f.getInt(mc);
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Field f = Minecraft.class.getDeclaredField("leftClickCounter");
                f.setAccessible(true);
                return f.getInt(mc);
            } catch (Throwable ignored2) {
                return -1;
            }
        }
    }

    private static void writeLeftClickCounter(Minecraft mc, int value) {
        if (mc == null) return;
        try {
            java.lang.reflect.Field f = Minecraft.class.getDeclaredField("field_71429_W");
            f.setAccessible(true);
            f.setInt(mc, value);
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Field f = Minecraft.class.getDeclaredField("leftClickCounter");
                f.setAccessible(true);
                f.setInt(mc, value);
            } catch (Throwable ignored2) {}
        }
    }

    private static List<String> readTail(File file, int n) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
                if (lines.size() > n) {
                    lines.remove(0);
                }
            }
        }
        return lines;
    }
}
