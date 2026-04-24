package com.telethon.sf4debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GUI-level interaction: simulate mouse clicks, keyboard input, scroll,
 * and AE2 ME-terminal specific queries. Everything here is invoked on
 * the client thread by {@link DebugHttpServer} and operates against
 * {@code Minecraft#currentScreen}.
 *
 * <p>These routes go through {@link GuiScreen}'s protected
 * {@code mouseClicked} / {@code mouseReleased} / {@code mouseClickMove} /
 * {@code keyTyped} methods via reflection, which is the exact path
 * Minecraft itself uses when the user clicks / types. That means the
 * modded GUI's own {@code actionPerformed}, slot-click handlers,
 * {@code GuiTextField#textboxKeyTyped}, and widget-specific click
 * code all run the same as if a human did it.
 *
 * <p>For AE2 ME terminals the {@link #me(Map)} route reflectively reads
 * the {@code ItemRepo} field on {@code GuiMEMonitorable} (or any
 * subclass) to paginate the ME network's visible items. Clicking a
 * specific ME slot is done with {@code /guiClick?x=&y=} once the
 * caller knows the slot's screen pixel coordinates.
 */
public final class GuiRoutes {

    private GuiRoutes() {}

    /* ===================== reflection helpers ===================== */

    /**
     * Try to find a declared method by SRG name first, then MCP name.
     * Walks up the class hierarchy. Returns null if none match.
     */
    private static Method findMethod(Class<?> clazz, String[] names, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    Method m = c.getDeclaredMethod(name, paramTypes);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                    // try next name / superclass
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String[] names) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // try next name / superclass
                }
            }
        }
        return null;
    }

    private static final String[] MC_MOUSE_CLICKED =
        new String[] { "func_73864_a", "mouseClicked" };
    private static final String[] MC_MOUSE_RELEASED =
        new String[] { "func_146286_b", "mouseReleased" };
    private static final String[] MC_MOUSE_CLICK_MOVE =
        new String[] { "func_146273_a", "mouseClickMove" };
    private static final String[] MC_KEY_TYPED =
        new String[] { "func_73869_a", "keyTyped" };
    private static final String[] MC_ACTION_PERFORMED =
        new String[] { "func_146284_a", "actionPerformed" };

    /* ===================== LWJGL keyboard modifier injection ===================== */

    // LWJGL 2.9 keycode constants used to flip the private keyDownBuffer
    // so GuiScreen.isShiftKeyDown()/isCtrlKeyDown()/isAltKeyDown() see a
    // modifier held for the duration of a dispatched click.
    private static final int KEY_LSHIFT   = 42;
    private static final int KEY_RSHIFT   = 54;
    private static final int KEY_LCONTROL = 29;
    private static final int KEY_RCONTROL = 157;
    private static final int KEY_LMENU    = 56;   // Alt
    private static final int KEY_RMENU    = 184;

    /**
     * Scope object returned by {@link #applyModifiers}: stores the bytes
     * we overwrote in {@code Keyboard.keyDownBuffer} so {@link
     * #restoreModifiers} can put them back in a {@code finally}.
     *
     * <p>If reflection on {@code keyDownBuffer} fails we still return a
     * scope (so callers can keep the normal try/finally shape) but with
     * {@code unsupported=true} and an empty restore list — restore is
     * a no-op. The response then advertises {@code modifierOverride:
     * "unsupported"} so the caller knows the click went out unmodified.
     */
    private static final class ModifierScope {
        boolean shift, ctrl, alt;
        boolean requested;
        boolean unsupported;
        /** (index, previousByte) pairs, applied in LIFO order on restore. */
        final java.util.List<int[]> restore = new java.util.ArrayList<>();
    }

    private static ModifierScope applyModifiers(Map<String, String> q) {
        boolean shift = truthy(q.get("shift"));
        boolean ctrl  = truthy(q.get("ctrl"));
        boolean alt   = truthy(q.get("alt"));
        ModifierScope s = new ModifierScope();
        s.shift = shift; s.ctrl = ctrl; s.alt = alt;
        s.requested = shift || ctrl || alt;
        if (!s.requested) return s;
        java.nio.ByteBuffer buf = getKeyDownBuffer();
        if (buf == null) {
            s.unsupported = true;
            return s;
        }
        // Only the L* variants matter for GuiScreen.isShiftKeyDown()
        // etc. (it short-circuits on either L or R). Using the L*
        // variants keeps the touched surface minimal.
        if (shift) setKeyByte(buf, KEY_LSHIFT, s);
        if (ctrl)  setKeyByte(buf, KEY_LCONTROL, s);
        if (alt)   setKeyByte(buf, KEY_LMENU, s);
        return s;
    }

    private static void setKeyByte(java.nio.ByteBuffer buf, int idx, ModifierScope s) {
        try {
            byte prev = buf.get(idx);
            s.restore.add(new int[] { idx, prev });
            buf.put(idx, (byte) 1);
        } catch (Throwable ignored) {
            // Out-of-range or read-only — skip this modifier silently.
        }
    }

    private static void restoreModifiers(ModifierScope s) {
        if (s == null || !s.requested || s.unsupported) return;
        java.nio.ByteBuffer buf = getKeyDownBuffer();
        if (buf == null) return;
        // LIFO restore so nested flips would compose correctly. Here
        // they don't nest, but the pattern is cheap and correct.
        for (int i = s.restore.size() - 1; i >= 0; i--) {
            int[] pair = s.restore.get(i);
            try { buf.put(pair[0], (byte) pair[1]); } catch (Throwable ignored) {}
        }
        s.restore.clear();
    }

    private static java.nio.ByteBuffer getKeyDownBuffer() {
        try {
            // Field name is `keyDownBuffer` on LWJGL 2.9.x; some
            // obfuscated builds may expose it as `c`. Try both.
            Field f = findField(Keyboard.class, new String[] { "keyDownBuffer", "c" });
            if (f == null) return null;
            Object v = f.get(null);
            return v instanceof java.nio.ByteBuffer ? (java.nio.ByteBuffer) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void putModifierInfo(Map<String, Object> out, ModifierScope s) {
        if (s == null || !s.requested) return;
        Map<String, Object> mods = new LinkedHashMap<>();
        mods.put("shift", s.shift);
        mods.put("ctrl",  s.ctrl);
        mods.put("alt",   s.alt);
        out.put("modifiers", mods);
        if (s.unsupported) out.put("modifierOverride", "unsupported");
    }

    private static boolean truthy(String v) {
        if (v == null) return false;
        switch (v.toLowerCase()) {
            case "1": case "true": case "yes": case "on":
                return true;
            default:
                return false;
        }
    }

    /* ===================== mouse ===================== */

    /**
     * /guiClick?x=&y=&button=0&shift=0&ctrl=0&alt=0
     *
     * Dispatches {@code GuiScreen.mouseClicked(x, y, button)} on the
     * currently-open screen. x/y are raw screen pixel coordinates (same
     * as returned by {@code /screen} bounds + widget positions). button
     * is 0 (left), 1 (right), or 2 (middle). {@code shift/ctrl/alt=1}
     * temporarily flip LWJGL's keyDownBuffer for the duration of the
     * dispatched call so modded widgets that check
     * {@code GuiScreen.isShiftKeyDown()} (shift-click quick-move,
     * ctrl-drop-one, alt-modifier pickups) see the modifier held.
     */
    public static Object guiClick(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (screen == null) { out.put("error", "no screen"); return out; }
        Integer x = DebugHttpServer.parseInt(q.get("x"), null);
        Integer y = DebugHttpServer.parseInt(q.get("y"), null);
        if (x == null || y == null) {
            out.put("error", "missing x/y"); return out;
        }
        int button = DebugHttpServer.parseInt(q.get("button"), 0);
        Method m = findMethod(screen.getClass(), MC_MOUSE_CLICKED,
                int.class, int.class, int.class);
        if (m == null) { out.put("error", "mouseClicked not found"); return out; }
        ModifierScope mods = applyModifiers(q);
        try {
            m.invoke(screen, x, y, button);
            out.put("ok", true);
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            restoreModifiers(mods);
        }
        out.put("screen", screen.getClass().getName());
        out.put("x", x); out.put("y", y); out.put("button", button);
        putModifierInfo(out, mods);
        return out;
    }

    /**
     * /guiRelease?x=&y=&state=0&shift=0&ctrl=0&alt=0 — dispatches
     * mouseReleased. {@code shift/ctrl/alt=1} temporarily flip
     * LWJGL's keyDownBuffer for the duration of the dispatched call
     * (see {@link #guiClick}).
     */
    public static Object guiRelease(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (screen == null) { out.put("error", "no screen"); return out; }
        Integer x = DebugHttpServer.parseInt(q.get("x"), null);
        Integer y = DebugHttpServer.parseInt(q.get("y"), null);
        if (x == null || y == null) {
            out.put("error", "missing x/y"); return out;
        }
        int state = DebugHttpServer.parseInt(q.get("state"), 0);
        Method m = findMethod(screen.getClass(), MC_MOUSE_RELEASED,
                int.class, int.class, int.class);
        if (m == null) { out.put("error", "mouseReleased not found"); return out; }
        ModifierScope mods = applyModifiers(q);
        try {
            m.invoke(screen, x, y, state);
            out.put("ok", true);
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            restoreModifiers(mods);
        }
        putModifierInfo(out, mods);
        return out;
    }

    /**
     * /guiDrag?x=&y=&button=0&time=0&shift=0&ctrl=0&alt=0 — dispatches
     * mouseClickMove. {@code shift/ctrl/alt=1} temporarily flip
     * LWJGL's keyDownBuffer for the duration of the dispatched call
     * (see {@link #guiClick}).
     */
    public static Object guiDrag(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (screen == null) { out.put("error", "no screen"); return out; }
        Integer x = DebugHttpServer.parseInt(q.get("x"), null);
        Integer y = DebugHttpServer.parseInt(q.get("y"), null);
        if (x == null || y == null) {
            out.put("error", "missing x/y"); return out;
        }
        int button = DebugHttpServer.parseInt(q.get("button"), 0);
        long time = DebugHttpServer.parseInt(q.get("time"), 0);
        Method m = findMethod(screen.getClass(), MC_MOUSE_CLICK_MOVE,
                int.class, int.class, int.class, long.class);
        if (m == null) { out.put("error", "mouseClickMove not found"); return out; }
        ModifierScope mods = applyModifiers(q);
        try {
            m.invoke(screen, x, y, button, time);
            out.put("ok", true);
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            restoreModifiers(mods);
        }
        putModifierInfo(out, mods);
        return out;
    }

    /* ===================== keyboard ===================== */

    /**
     * /guiKey?code=N&char=X&name=...
     *
     * Dispatches {@code GuiScreen.keyTyped(char, keyCode)}. Either
     * {@code code=<int>} (LWJGL Keyboard.KEY_*) or {@code name=...}
     * (symbolic shortcut: enter, escape, backspace, tab, up, down,
     * left, right, home, end, delete, pageup, pagedown, f1..f12).
     * {@code char=X} overrides the typed character; otherwise it
     * defaults to 0 for symbolic keys.
     */
    public static Object guiKey(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (screen == null) { out.put("error", "no screen"); return out; }

        Integer code = DebugHttpServer.parseInt(q.get("code"), null);
        String name = q.get("name");
        if (code == null && name != null) code = resolveKeyCode(name);
        if (code == null) code = 0;

        char ch = 0;
        String cs = q.get("char");
        if (cs != null && !cs.isEmpty()) ch = cs.charAt(0);

        try {
            typeOne(screen, ch, code);
            out.put("ok", true);
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        out.put("code", code);
        out.put("char", ch == 0 ? "" : String.valueOf(ch));
        return out;
    }

    /**
     * /guiType?text=hello+world
     *
     * Types a string character-by-character, one {@code keyTyped} per
     * char. Non-printable chars (tab, return, backspace) are mapped to
     * their LWJGL keycode so text fields treat them as navigation.
     */
    public static Object guiType(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (screen == null) { out.put("error", "no screen"); return out; }
        String text = q.get("text");
        if (text == null) { out.put("error", "missing text"); return out; }

        int typed = 0;
        try {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int code = charToKeyCode(c);
                typeOne(screen, c, code);
                typed++;
            }
            out.put("ok", true);
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        out.put("typed", typed);
        out.put("total", text.length());
        return out;
    }

    private static void typeOne(GuiScreen screen, char ch, int code) throws Throwable {
        Method m = findMethod(screen.getClass(), MC_KEY_TYPED, char.class, int.class);
        if (m == null) throw new NoSuchMethodException("keyTyped not found");
        m.invoke(screen, ch, code);
    }

    /** Best-effort LWJGL keycode for a character. */
    private static int charToKeyCode(char c) {
        if (c == '\n' || c == '\r') return Keyboard.KEY_RETURN;
        if (c == '\t') return Keyboard.KEY_TAB;
        if (c == '\b') return Keyboard.KEY_BACK;
        if (c == ' ')  return Keyboard.KEY_SPACE;
        if (c >= 'a' && c <= 'z') return Keyboard.KEY_A + (c - 'a');
        if (c >= 'A' && c <= 'Z') return Keyboard.KEY_A + (c - 'A');
        if (c >= '0' && c <= '9') return c == '0' ? Keyboard.KEY_0 : Keyboard.KEY_1 + (c - '1');
        // Fall back to 0 — GuiTextField only looks at the char for
        // printable characters, so 0 is safe for punctuation.
        return 0;
    }

    private static Integer resolveKeyCode(String name) {
        switch (name.toLowerCase()) {
            case "return": case "enter":   return Keyboard.KEY_RETURN;
            case "escape": case "esc":     return Keyboard.KEY_ESCAPE;
            case "backspace": case "back": return Keyboard.KEY_BACK;
            case "tab":                    return Keyboard.KEY_TAB;
            case "space":                  return Keyboard.KEY_SPACE;
            case "up":                     return Keyboard.KEY_UP;
            case "down":                   return Keyboard.KEY_DOWN;
            case "left":                   return Keyboard.KEY_LEFT;
            case "right":                  return Keyboard.KEY_RIGHT;
            case "home":                   return Keyboard.KEY_HOME;
            case "end":                    return Keyboard.KEY_END;
            case "delete": case "del":     return Keyboard.KEY_DELETE;
            case "insert":                 return Keyboard.KEY_INSERT;
            case "pageup":                 return Keyboard.KEY_PRIOR;
            case "pagedown":               return Keyboard.KEY_NEXT;
            case "f1":  return Keyboard.KEY_F1;  case "f2":  return Keyboard.KEY_F2;
            case "f3":  return Keyboard.KEY_F3;  case "f4":  return Keyboard.KEY_F4;
            case "f5":  return Keyboard.KEY_F5;  case "f6":  return Keyboard.KEY_F6;
            case "f7":  return Keyboard.KEY_F7;  case "f8":  return Keyboard.KEY_F8;
            case "f9":  return Keyboard.KEY_F9;  case "f10": return Keyboard.KEY_F10;
            case "f11": return Keyboard.KEY_F11; case "f12": return Keyboard.KEY_F12;
            default: return null;
        }
    }

    /* ===================== scroll ===================== */

    /**
     * /guiScroll?dwheel=120
     *
     * Scrolls the current screen by {@code dwheel}. Positive = up,
     * negative = down (LWJGL mouse-wheel convention). Each vanilla
     * notch is ±120.
     *
     * <p>Implementation priority:
     * <ol>
     *   <li>If the screen has an AE2 {@code GuiScrollbar} field
     *       (e.g. AEBaseGui#myScrollBar), call {@code wheel(dwheel)}
     *       directly. Works for every AE2 terminal.
     *   <li>Otherwise attempt {@code mouseWheelEvent(x, y, dwheel)}
     *       (AEBaseGui) or {@code handleMouseInput()} (vanilla). The
     *       vanilla path reads the actual {@code Mouse.getEventDWheel()}
     *       which we can't spoof, so vanilla scroll is best-effort.
     * </ol>
     */
    public static Object guiScroll(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (screen == null) { out.put("error", "no screen"); return out; }
        int dwheel = DebugHttpServer.parseInt(q.get("dwheel"), 120);

        // 1) AE2 GuiScrollbar#wheel(int) — preferred.
        if (tryAeScroll(screen, dwheel, out)) {
            return out;
        }

        // 2) AE2 AEBaseGui#mouseWheelEvent(int x, int y, int wheel).
        Integer x = DebugHttpServer.parseInt(q.get("x"), null);
        Integer y = DebugHttpServer.parseInt(q.get("y"), null);
        if (x == null || y == null) {
            // Default to screen center if not given.
            x = screen.width / 2; y = screen.height / 2;
        }
        Method mwe = findMethod(screen.getClass(),
                new String[] { "mouseWheelEvent" }, int.class, int.class, int.class);
        if (mwe != null) {
            try {
                mwe.invoke(screen, x, y, dwheel);
                out.put("via", "mouseWheelEvent");
                out.put("ok", true);
                return out;
            } catch (Throwable t) {
                out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        out.put("via", "unsupported");
        out.put("ok", false);
        out.put("note",
            "current screen has no AE2 GuiScrollbar nor mouseWheelEvent; " +
            "vanilla handleMouseInput reads Mouse.getEventDWheel() which " +
            "cannot be spoofed from Java. Use /guiClick on the scroll bar " +
            "thumb for vanilla scrolling.");
        return out;
    }

    private static boolean tryAeScroll(GuiScreen screen, int dwheel, Map<String, Object> out) {
        try {
            Method getScrollBar = findMethod(screen.getClass(),
                    new String[] { "getScrollBar" });
            if (getScrollBar == null) return false;
            Object bar = getScrollBar.invoke(screen);
            if (bar == null) return false;
            Method wheel = bar.getClass().getMethod("wheel", int.class);
            wheel.invoke(bar, dwheel);
            out.put("via", "AEBaseGui.getScrollBar().wheel");
            out.put("ok", true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ===================== buttons ===================== */

    /**
     * /guiButton?id=N
     *
     * Finds the {@link GuiButton} in {@code buttonList} with the given
     * id and clicks its centre. This both plays the click-sound via
     * {@code GuiButton#playPressSound} and calls the screen's
     * {@code actionPerformed} through the normal {@code mouseClicked}
     * dispatch, so anything the button's listener does runs as normal.
     */
    public static Object guiButton(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (screen == null) { out.put("error", "no screen"); return out; }
        Integer id = DebugHttpServer.parseInt(q.get("id"), null);
        if (id == null) { out.put("error", "missing id"); return out; }
        List<GuiButton> buttons = buttonList(screen);
        if (buttons == null) { out.put("error", "no buttonList"); return out; }
        GuiButton match = null;
        for (GuiButton b : buttons) {
            if (b != null && b.id == id) { match = b; break; }
        }
        if (match == null) { out.put("error", "no button with id " + id); return out; }
        int cx = match.x + match.width / 2;
        int cy = match.y + match.height / 2;
        Method m = findMethod(screen.getClass(), MC_MOUSE_CLICKED,
                int.class, int.class, int.class);
        if (m == null) { out.put("error", "mouseClicked not found"); return out; }
        try {
            m.invoke(screen, cx, cy, 0);
            out.put("ok", true);
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        out.put("id", id);
        out.put("x", cx); out.put("y", cy);
        out.put("displayString", match.displayString);
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<GuiButton> buttonList(GuiScreen screen) {
        Field f = findField(GuiScreen.class, new String[] { "field_146292_n", "buttonList" });
        if (f == null) return null;
        try { return (List<GuiButton>) f.get(screen); }
        catch (Throwable t) { return null; }
    }

    /* ===================== screen snapshot additions ===================== */

    /**
     * Extra fields to merge into {@code /screen}: GUI bounds, buttons,
     * text-field scan, class hierarchy, AE2 ME-terminal state.
     */
    static void augmentScreenSnapshot(GuiScreen screen, Map<String, Object> out) {
        // Class hierarchy (leaf first, up to GuiScreen).
        List<String> chain = new ArrayList<>();
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            chain.add(c.getName());
            if (c == GuiScreen.class) break;
        }
        out.put("classHierarchy", chain);

        if (screen instanceof GuiContainer) {
            GuiContainer gc = (GuiContainer) screen;
            // GuiContainer.xSize/ySize/guiLeft/guiTop are protected — use
            // reflection through the MCP/SRG field names.
            Map<String, Object> bounds = new LinkedHashMap<>();
            bounds.put("guiLeft", readInt(gc, "field_147003_i", "guiLeft"));
            bounds.put("guiTop",  readInt(gc, "field_147009_r", "guiTop"));
            bounds.put("xSize",   readInt(gc, "field_146999_f", "xSize"));
            bounds.put("ySize",   readInt(gc, "field_147000_g", "ySize"));
            out.put("bounds", bounds);
        }

        // Buttons (public field).
        List<GuiButton> buttons = buttonList(screen);
        if (buttons != null) {
            List<Map<String, Object>> bl = new ArrayList<>(buttons.size());
            for (GuiButton b : buttons) {
                if (b == null) continue;
                Map<String, Object> bj = new LinkedHashMap<>();
                bj.put("id", b.id);
                bj.put("x", b.x);
                bj.put("y", b.y);
                bj.put("width", b.width);
                bj.put("height", b.height);
                bj.put("displayString", b.displayString);
                bj.put("enabled", b.enabled);
                bj.put("visible", b.visible);
                bj.put("class", b.getClass().getName());
                bl.add(bj);
            }
            out.put("buttons", bl);
        }

        // Text fields — scan all fields on the screen (and superclasses)
        // for declared GuiTextField-typed fields.
        List<Map<String, Object>> tfs = new ArrayList<>();
        scanTextFields(screen, tfs);
        out.put("textFields", tfs);

        // AE2 ME terminal repo snapshot, if applicable.
        Object me = aeMeSnapshot(screen, /*maxItems=*/200, /*search=*/null);
        if (me != null) out.put("aeMeTerminal", me);
    }

    private static Integer readInt(Object target, String... names) {
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try { return f.getInt(target); }
        catch (Throwable t) { return null; }
    }

    private static void scanTextFields(GuiScreen screen, List<Map<String, Object>> out) {
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!GuiTextField.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                GuiTextField tf;
                try { tf = (GuiTextField) f.get(screen); }
                catch (Throwable t) { continue; }
                if (tf == null) continue;
                Map<String, Object> tj = new LinkedHashMap<>();
                tj.put("field", c.getSimpleName() + "#" + f.getName());
                tj.put("class", tf.getClass().getName());
                tj.put("text", tf.getText());
                tj.put("focused", tf.isFocused());
                tj.put("visible", tf.getVisible());
                // xPos/yPos/width/height are SRG field_146*_* — read via reflection.
                tj.put("x",       readInt(tf, "field_146209_f", "x"));
                tj.put("y",       readInt(tf, "field_146210_g", "y"));
                tj.put("width",   readInt(tf, "field_146218_h", "width"));
                tj.put("height",  readInt(tf, "field_146219_i", "height"));
                out.add(tj);
            }
            if (c == GuiScreen.class) break;
        }
    }

    /* ===================== AE2 ME terminal ===================== */

    private static final String REPO_CLASS = "appeng.client.me.ItemRepo";
    private static final String AE_BASE_GUI = "appeng.client.gui.AEBaseGui";

    private static boolean classExists(String name) {
        try { Class.forName(name, false, GuiRoutes.class.getClassLoader()); return true; }
        catch (Throwable t) { return false; }
    }

    /**
     * /me?search=iron&limit=100&offset=0
     *
     * Paginated snapshot of the current ME terminal's item repo. If
     * {@code search=...} is given, the search string is pushed into the
     * repo and {@code updateView()} is called, so the returned view
     * matches what the terminal UI would show. Use an empty
     * {@code search=} to clear the filter. Returns null (HTTP 200,
     * JSON null) if the current screen isn't an AE2 terminal.
     */
    public static Object me(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (screen == null) { out.put("error", "no screen"); return out; }
        int limit = DebugHttpServer.parseInt(q.get("limit"), 100);
        int offset = DebugHttpServer.parseInt(q.get("offset"), 0);
        String search = q.get("search");
        Object snap = aeMeSnapshot(screen, limit, search);
        if (snap == null) { out.put("error", "not an AE2 terminal"); out.put("class", screen.getClass().getName()); return out; }
        if (snap instanceof Map) ((Map<String, Object>) snap).put("offset", offset);
        return snap;
    }

    @SuppressWarnings("unchecked")
    static Object aeMeSnapshot(GuiScreen screen, int limit, String search) {
        if (!classExists(REPO_CLASS)) return null;
        // Find the ItemRepo field on the screen (and superclasses).
        Field repoField = null;
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals(REPO_CLASS)) {
                    repoField = f;
                    break;
                }
            }
            if (repoField != null) break;
        }
        if (repoField == null) return null;

        try {
            repoField.setAccessible(true);
            Object repo = repoField.get(screen);
            if (repo == null) return null;

            if (search != null) {
                Method setSearchString = repo.getClass().getMethod("setSearchString", String.class);
                setSearchString.invoke(repo, search);
                // Also push into searchField widget if present so the UI matches.
                Field searchFieldF = findAnyFieldByTypeName(
                    screen.getClass(), "appeng.client.gui.widgets.MEGuiTextField");
                if (searchFieldF != null) {
                    try {
                        searchFieldF.setAccessible(true);
                        Object sf = searchFieldF.get(screen);
                        if (sf != null) {
                            Method setText = sf.getClass().getMethod("setText", String.class);
                            setText.invoke(sf, search);
                        }
                    } catch (Throwable ignored) {}
                }
                Method updateView = repo.getClass().getMethod("updateView");
                updateView.invoke(repo);
            }

            Method size = repo.getClass().getMethod("size");
            Method getRef = repo.getClass().getMethod("getReferenceItem", int.class);
            int total = ((Number) size.invoke(repo)).intValue();

            List<Map<String, Object>> items = new ArrayList<>();
            int cap = Math.max(0, Math.min(limit, 500));
            for (int i = 0; i < total && items.size() < cap; i++) {
                Object iae = getRef.invoke(repo, i);
                if (iae == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                try {
                    ItemStack disp = (ItemStack) iae.getClass().getMethod("createItemStack").invoke(iae);
                    item.put("item", DebugHttpServer.itemStackJson(disp));
                } catch (Throwable t) { item.put("item", null); }
                try { item.put("count", iae.getClass().getMethod("getStackSize").invoke(iae)); }
                catch (Throwable t) { item.put("count", null); }
                try { item.put("requestable", iae.getClass().getMethod("getCountRequestable").invoke(iae)); }
                catch (Throwable t) { item.put("requestable", null); }
                try { item.put("craftable", iae.getClass().getMethod("isCraftable").invoke(iae)); }
                catch (Throwable t) { item.put("craftable", null); }
                item.put("index", i);
                items.add(item);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("screen", screen.getClass().getName());
            out.put("total", total);
            out.put("returned", items.size());
            if (search != null) out.put("search", search);
            // Current scroll and row size (for click math on the client).
            try {
                Method getCurrentScroll = repo.getClass().getMethod("getRowSize");
                out.put("rowSize", getCurrentScroll.invoke(repo));
            } catch (Throwable ignored) {}
            try {
                Method getSearchString = repo.getClass().getMethod("getSearchString");
                out.put("searchString", getSearchString.invoke(repo));
            } catch (Throwable ignored) {}
            // Visible ME slots' screen coordinates (for /guiClick).
            List<Map<String, Object>> slots = meSlotPositions(screen);
            if (slots != null) out.put("meSlots", slots);
            // Scroll bar geometry.
            Object bar = tryInvoke(screen, "getScrollBar");
            if (bar != null) {
                Map<String, Object> sb = new LinkedHashMap<>();
                sb.put("x",             tryInvokeInt(bar, "getLeft"));
                sb.put("y",             tryInvokeInt(bar, "getTop"));
                sb.put("width",         tryInvokeInt(bar, "getWidth"));
                sb.put("height",        tryInvokeInt(bar, "getHeight"));
                sb.put("currentScroll", tryInvokeInt(bar, "getCurrentScroll"));
                out.put("scrollBar", sb);
            }
            out.put("items", items);
            return out;
        } catch (Throwable t) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            err.put("screen", screen.getClass().getName());
            return err;
        }
    }

    private static Field findAnyFieldByTypeName(Class<?> start, String typeName) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals(typeName)) return f;
            }
        }
        return null;
    }

    private static Object tryInvoke(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // try superclass
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Integer tryInvokeInt(Object target, String name) {
        Object v = tryInvoke(target, name);
        if (v instanceof Number) return ((Number) v).intValue();
        return null;
    }

    /** Returns screen-pixel positions of the AE2 ME custom slots. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> meSlotPositions(GuiScreen screen) {
        if (!classExists(AE_BASE_GUI)) return null;
        try {
            Method getMeSlots = findMethod(screen.getClass(),
                new String[] { "getMeSlots" });
            if (getMeSlots == null) return null;
            List<Object> list = (List<Object>) getMeSlots.invoke(screen);
            if (list == null) return null;
            int guiLeft = readIntOr(screen, 0, "field_147003_i", "guiLeft");
            int guiTop  = readIntOr(screen, 0, "field_147009_r", "guiTop");
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (Object s : list) {
                if (s == null) continue;
                Map<String, Object> sj = new LinkedHashMap<>();
                int xp = tryInt(s, "getxPosition");
                int yp = tryInt(s, "getyPosition");
                sj.put("relX", xp);
                sj.put("relY", yp);
                // InternalSlotME positions are relative to the gui origin.
                sj.put("x", guiLeft + xp + 8);
                sj.put("y", guiTop + yp + 8);
                Object aes = tryInvoke(s, "getAEStack");
                if (aes != null) {
                    try {
                        ItemStack stack = (ItemStack) aes.getClass()
                            .getMethod("createItemStack").invoke(aes);
                        sj.put("item", DebugHttpServer.itemStackJson(stack));
                        sj.put("count", aes.getClass().getMethod("getStackSize").invoke(aes));
                        sj.put("craftable", aes.getClass().getMethod("isCraftable").invoke(aes));
                    } catch (Throwable ignored) {}
                }
                out.add(sj);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int tryInt(Object target, String name) {
        Integer v = tryInvokeInt(target, name);
        return v == null ? 0 : v;
    }

    private static int readIntOr(Object target, int fallback, String... names) {
        Integer v = readInt(target, names);
        return v == null ? fallback : v;
    }
}
