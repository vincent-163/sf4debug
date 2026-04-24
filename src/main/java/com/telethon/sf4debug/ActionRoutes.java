package com.telethon.sf4debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketClientStatus;
import net.minecraft.network.play.client.CPacketHeldItemChange;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player-side action routes: move, aim, interact, inventory click,
 * chat. Everything here runs on the client thread via
 * {@link DebugHttpServer#runOnClientThread}. Network side-effects go
 * out through {@code mc.playerController} / {@code mc.player.connection}
 * exactly the way a user's keyboard/mouse input would.
 */
public final class ActionRoutes {

    private ActionRoutes() {}

    /* ========================= movement ========================= */

    /**
     * /walk?dir=forward[,left]&ticks=20&sprint=1&sneak=1&jump=1
     *
     * Multiple directions can be combined with commas. {@code dir=stop}
     * or {@code ticks=0} releases all held keys. Default ticks = 20
     * (one second at 20 TPS).
     */
    public static Object walk(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("state", "not_in_world"); return out; }

        String dir = q.getOrDefault("dir", "");
        int ticks = DebugHttpServer.parseInt(q.get("ticks"), 20);
        boolean sprint = truthy(q.get("sprint"));
        boolean sneak = truthy(q.get("sneak"));
        boolean jump = truthy(q.get("jump"));

        if ("stop".equalsIgnoreCase(dir) || ticks <= 0) {
            TickInput.get().releaseAll();
            out.put("stopped", true);
            return out;
        }

        GameSettings gs = mc.gameSettings;
        TickInput t = TickInput.get();
        List<String> applied = new ArrayList<>();
        for (String d : dir.split(",")) {
            String s = d.trim().toLowerCase();
            if (s.isEmpty()) continue;
            KeyBinding kb = null;
            switch (s) {
                case "forward": case "fwd": case "f":
                    kb = gs.keyBindForward; break;
                case "back": case "backward": case "b":
                    kb = gs.keyBindBack; break;
                case "left": case "l":
                    kb = gs.keyBindLeft; break;
                case "right": case "r":
                    kb = gs.keyBindRight; break;
                default:
                    break;
            }
            if (kb != null) {
                t.hold(kb, ticks);
                applied.add(s);
            }
        }
        if (sprint) { t.hold(gs.keyBindSprint, ticks); applied.add("sprint"); }
        if (sneak)  { t.hold(gs.keyBindSneak,  ticks); applied.add("sneak"); }
        if (jump)   { t.hold(gs.keyBindJump,   ticks); applied.add("jump"); }
        out.put("ticks", ticks);
        out.put("applied", applied);
        out.put("holds", t.snapshotHolds());
        return out;
    }

    /** /stop — release all held movement keys and any hold-attack/use. */
    public static Object stop(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        PlayerControllerMP pc = mc.playerController;
        if (pc != null) {
            try { pc.resetBlockRemoving(); } catch (Throwable ignored) {}
        }
        TickInput.get().releaseAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stopped", true);
        return out;
    }

    /** /jump?ticks=2 — hold the jump key for N ticks (default 2). */
    public static Object jump(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        int ticks = DebugHttpServer.parseInt(q.get("ticks"), 2);
        TickInput.get().hold(mc.gameSettings.keyBindJump, ticks);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticks", ticks);
        return out;
    }

    /** /sneak?ticks=20 — hold sneak. */
    public static Object sneak(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        int ticks = DebugHttpServer.parseInt(q.get("ticks"), 20);
        TickInput.get().hold(mc.gameSettings.keyBindSneak, ticks);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticks", ticks);
        return out;
    }

    /** /sprint?ticks=20 — hold sprint. */
    public static Object sprint(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        int ticks = DebugHttpServer.parseInt(q.get("ticks"), 20);
        TickInput.get().hold(mc.gameSettings.keyBindSprint, ticks);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticks", ticks);
        return out;
    }

    /**
     * /key?name=forward&ticks=20 — generic hold of any named keybind.
     * {@code name} is one of: forward, back, left, right, jump, sneak,
     * sprint, attack, use, drop, pickBlock, swapHands, inventory, chat,
     * command, playerList. Case-insensitive.
     */
    public static Object key(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        String name = q.get("name");
        Map<String, Object> out = new LinkedHashMap<>();
        if (name == null) { out.put("error", "missing name"); return out; }
        KeyBinding kb = resolveKey(mc.gameSettings, name);
        if (kb == null) { out.put("error", "unknown key: " + name); return out; }
        int ticks = DebugHttpServer.parseInt(q.get("ticks"), 1);
        boolean click = "1".equals(q.get("click"));
        if (click) {
            TickInput.get().click(kb);
        } else {
            TickInput.get().hold(kb, ticks);
        }
        out.put("name", name);
        out.put("ticks", ticks);
        out.put("click", click);
        return out;
    }

    /** /releaseKey?name=forward — release a specific hold immediately. */
    public static Object releaseKey(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        String name = q.get("name");
        Map<String, Object> out = new LinkedHashMap<>();
        if (name == null) { out.put("error", "missing name"); return out; }
        KeyBinding kb = resolveKey(mc.gameSettings, name);
        if (kb == null) { out.put("error", "unknown key: " + name); return out; }
        TickInput.get().release(kb);
        out.put("released", name);
        return out;
    }

    /* ========================= aim ========================= */

    /**
     * /aim?yaw=0&pitch=0&relative=0&ticks=0
     *
     * Sets the player's rotation. {@code relative=1} adds the yaw/pitch
     * deltas to the current rotation; otherwise absolute. Pitch is
     * clamped to [-90, 90]. Both yaw and pitch are optional; omitted
     * values leave that axis unchanged. {@code ticks=0} (default)
     * snaps instantly. {@code ticks>0} schedules a linear interpolation
     * over N client ticks via {@link TickInput#scheduleSmoothAim}
     * using shortest-arc yaw. N is clamped to [0, 100]. A subsequent
     * call overwrites the in-progress plan.
     */
    public static Object aim(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("state", "not_in_world"); return out; }
        Float yaw = parseFloat(q.get("yaw"));
        Float pitch = parseFloat(q.get("pitch"));
        boolean rel = truthy(q.get("relative"));
        int ticks = DebugHttpServer.parseInt(q.get("ticks"), 0);
        if (ticks < 0) ticks = 0;
        if (ticks > 100) ticks = 100;
        float newYaw = yaw == null ? p.rotationYaw
                                   : (rel ? p.rotationYaw + yaw : yaw);
        float newPitch = pitch == null ? p.rotationPitch
                                       : (rel ? p.rotationPitch + pitch : pitch);
        newPitch = MathHelper.clamp(newPitch, -90f, 90f);
        if (ticks > 0) {
            float fromYaw = p.rotationYaw;
            float fromPitch = p.rotationPitch;
            TickInput.get().scheduleSmoothAim(newYaw, newPitch, ticks);
            out.put("scheduled", true);
            out.put("ticks", ticks);
            out.put("fromYaw", fromYaw);
            out.put("fromPitch", fromPitch);
            out.put("toYaw", newYaw);
            out.put("toPitch", newPitch);
            return out;
        }
        applyRotation(p, newYaw, newPitch);
        out.put("yaw", p.rotationYaw);
        out.put("pitch", p.rotationPitch);
        return out;
    }

    /**
     * /lookAt?x=&y=&z=&ticks=0 — point the player at a world-space
     * coordinate. Same {@code ticks} semantics as {@link #aim}: 0
     * (default) snaps, N&gt;0 schedules a linear interpolation over
     * N client ticks (clamped to [0, 100]).
     */
    public static Object lookAt(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("state", "not_in_world"); return out; }
        Double tx = parseDoubleObj(q.get("x"));
        Double ty = parseDoubleObj(q.get("y"));
        Double tz = parseDoubleObj(q.get("z"));
        if (tx == null || ty == null || tz == null) {
            out.put("error", "missing x/y/z"); return out;
        }
        int ticks = DebugHttpServer.parseInt(q.get("ticks"), 0);
        if (ticks < 0) ticks = 0;
        if (ticks > 100) ticks = 100;
        Vec3d eye = p.getPositionEyes(1f);
        double dx = tx - eye.x;
        double dy = ty - eye.y;
        double dz = tz - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        pitch = MathHelper.clamp(pitch, -90f, 90f);
        if (ticks > 0) {
            float fromYaw = p.rotationYaw;
            float fromPitch = p.rotationPitch;
            TickInput.get().scheduleSmoothAim(yaw, pitch, ticks);
            out.put("scheduled", true);
            out.put("ticks", ticks);
            out.put("fromYaw", fromYaw);
            out.put("fromPitch", fromPitch);
            out.put("toYaw", yaw);
            out.put("toPitch", pitch);
            out.put("target", Arrays.asList(tx, ty, tz));
            return out;
        }
        applyRotation(p, yaw, pitch);
        out.put("yaw", p.rotationYaw);
        out.put("pitch", p.rotationPitch);
        out.put("target", Arrays.asList(tx, ty, tz));
        return out;
    }

    /* ========================= interact ========================= */

    /**
     * /use?x=&y=&z=&side=up&hand=main[&hitx=0.5&hity=0.5&hitz=0.5]
     *
     * processRightClickBlock — equivalent to right-clicking the given
     * face of a block. Opens chests/machines, places blocks, activates
     * levers, etc. Returns the {@link EnumActionResult}.
     */
    public static Object useBlock(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        WorldClient w = mc.world;
        PlayerControllerMP pc = mc.playerController;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || w == null || pc == null) {
            out.put("state", "not_in_world"); return out;
        }
        Integer x = DebugHttpServer.parseInt(q.get("x"), null);
        Integer y = DebugHttpServer.parseInt(q.get("y"), null);
        Integer z = DebugHttpServer.parseInt(q.get("z"), null);
        if (x == null || y == null || z == null) {
            out.put("error", "missing x/y/z"); return out;
        }
        EnumFacing side = parseFacing(q.get("side"), EnumFacing.UP);
        EnumHand hand = parseHand(q.get("hand"));
        BlockPos bp = new BlockPos(x, y, z);
        // Default the hit vector to the center of the face we clicked.
        double hx = DebugHttpServer.parseDouble(q.get("hitx"),
                x + 0.5 + side.getFrontOffsetX() * 0.5);
        double hy = DebugHttpServer.parseDouble(q.get("hity"),
                y + 0.5 + side.getFrontOffsetY() * 0.5);
        double hz = DebugHttpServer.parseDouble(q.get("hitz"),
                z + 0.5 + side.getFrontOffsetZ() * 0.5);
        Vec3d hit = new Vec3d(hx, hy, hz);
        EnumActionResult r = pc.processRightClickBlock(p, w, bp, side, hit, hand);
        out.put("result", r == null ? null : r.name());
        out.put("pos", Arrays.asList(x, y, z));
        out.put("side", side.name());
        out.put("hand", hand.name());
        return out;
    }

    /**
     * /attack?x=&y=&z=&side=up
     *
     * Issues one {@code clickBlock} call — creative mode breaks the
     * block instantly, survival starts damaging it. For a survival
     * break: aim at the target first with {@code /lookAt} then call
     * {@code /holdAttack?ticks=N} (N = estimated ticks-to-break).
     */
    public static Object attackBlock(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        PlayerControllerMP pc = mc.playerController;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || pc == null) { out.put("state", "not_in_world"); return out; }
        Integer x = DebugHttpServer.parseInt(q.get("x"), null);
        Integer y = DebugHttpServer.parseInt(q.get("y"), null);
        Integer z = DebugHttpServer.parseInt(q.get("z"), null);
        if (x == null || y == null || z == null) {
            out.put("error", "missing x/y/z"); return out;
        }
        EnumFacing side = parseFacing(q.get("side"), EnumFacing.UP);
        BlockPos bp = new BlockPos(x, y, z);
        boolean ok = pc.clickBlock(bp, side);
        p.swingArm(EnumHand.MAIN_HAND);
        out.put("ok", ok);
        out.put("pos", Arrays.asList(x, y, z));
        out.put("side", side.name());
        return out;
    }

    /** /holdAttack?ticks=N — hold the attack key for N client ticks. */
    public static Object holdAttack(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        int ticks = DebugHttpServer.parseInt(q.get("ticks"), 20);
        TickInput.get().hold(mc.gameSettings.keyBindAttack, ticks);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticks", ticks);
        return out;
    }

    /** /holdUse?ticks=N — hold the use-item key for N ticks. */
    public static Object holdUse(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        int ticks = DebugHttpServer.parseInt(q.get("ticks"), 20);
        TickInput.get().hold(mc.gameSettings.keyBindUseItem, ticks);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticks", ticks);
        return out;
    }

    /** /stopAttack — resets block-breaking progress + releases attack key. */
    public static Object stopAttack(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        PlayerControllerMP pc = mc.playerController;
        if (pc != null) {
            try { pc.resetBlockRemoving(); } catch (Throwable ignored) {}
        }
        TickInput.get().release(mc.gameSettings.keyBindAttack);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stopped", true);
        return out;
    }

    /** /useItem?hand=main — right-click the air with the held item. */
    public static Object useItem(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        WorldClient w = mc.world;
        PlayerControllerMP pc = mc.playerController;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || w == null || pc == null) {
            out.put("state", "not_in_world"); return out;
        }
        EnumHand hand = parseHand(q.get("hand"));
        EnumActionResult r = pc.processRightClick(p, w, hand);
        out.put("result", r == null ? null : r.name());
        out.put("hand", hand.name());
        return out;
    }

    /** /attackEntity?id=N — attack the entity with the given entityId. */
    public static Object attackEntity(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        WorldClient w = mc.world;
        PlayerControllerMP pc = mc.playerController;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || w == null || pc == null) {
            out.put("state", "not_in_world"); return out;
        }
        Integer id = DebugHttpServer.parseInt(q.get("id"), null);
        if (id == null) { out.put("error", "missing id"); return out; }
        Entity e = w.getEntityByID(id);
        if (e == null) { out.put("error", "no such entity"); return out; }
        pc.attackEntity(p, e);
        p.swingArm(EnumHand.MAIN_HAND);
        out.put("attackedId", id);
        out.put("class", e.getClass().getName());
        out.put("name", e.getName());
        return out;
    }

    /** /useEntity?id=N&hand=main — right-click the given entity. */
    public static Object useEntity(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        WorldClient w = mc.world;
        PlayerControllerMP pc = mc.playerController;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || w == null || pc == null) {
            out.put("state", "not_in_world"); return out;
        }
        Integer id = DebugHttpServer.parseInt(q.get("id"), null);
        if (id == null) { out.put("error", "missing id"); return out; }
        Entity e = w.getEntityByID(id);
        if (e == null) { out.put("error", "no such entity"); return out; }
        EnumHand hand = parseHand(q.get("hand"));
        EnumActionResult r = pc.interactWithEntity(p, e, hand);
        out.put("result", r == null ? null : r.name());
        out.put("entityId", id);
        out.put("hand", hand.name());
        return out;
    }

    /* ========================= inventory / gui ========================= */

    /** /drop?full=1 — drop the held main-hand stack. {@code full=0} drops one. */
    public static Object drop(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("state", "not_in_world"); return out; }
        boolean full = truthy(q.get("full"));
        ItemStack before = p.getHeldItemMainhand().copy();
        EntityItem ei = p.dropItem(full);
        out.put("ok", true);
        out.put("full", full);
        out.put("before", DebugHttpServer.itemStackJson(before));
        out.put("entityItemId", ei == null ? null : ei.getEntityId());
        return out;
    }

    /** /selectSlot?slot=0 — switch hotbar slot 0..8. */
    public static Object selectSlot(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || p.connection == null) { out.put("state", "not_in_world"); return out; }
        Integer slot = DebugHttpServer.parseInt(q.get("slot"), null);
        if (slot == null || slot < 0 || slot > 8) {
            out.put("error", "slot must be 0..8"); return out;
        }
        if (slot != p.inventory.currentItem) {
            p.inventory.currentItem = slot;
            p.connection.sendPacket(new CPacketHeldItemChange(slot));
        }
        out.put("slot", slot);
        out.put("held", DebugHttpServer.itemStackJson(p.getHeldItemMainhand()));
        return out;
    }

    /** /swap — swap main/off-hand (F key equivalent). */
    public static Object swap(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || p.connection == null) { out.put("state", "not_in_world"); return out; }
        p.connection.sendPacket(new CPacketPlayerDigging(
            CPacketPlayerDigging.Action.SWAP_HELD_ITEMS, BlockPos.ORIGIN, EnumFacing.DOWN));
        out.put("ok", true);
        return out;
    }

    /** /close — close the currently-open {@code GuiScreen}. */
    public static Object close(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        Map<String, Object> out = new LinkedHashMap<>();
        String prev = mc.currentScreen == null ? null : mc.currentScreen.getClass().getName();
        // displayGuiScreen(null) triggers GuiContainer#onGuiClosed which
        // sends the CPacketCloseWindow for us.
        mc.displayGuiScreen(null);
        out.put("closed", prev);
        return out;
    }

    /** /openInventory — pops up the vanilla player inventory GUI. */
    public static Object openInventory(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("state", "not_in_world"); return out; }
        mc.displayGuiScreen(new GuiInventory(p));
        out.put("open", mc.currentScreen == null ? null : mc.currentScreen.getClass().getName());
        return out;
    }

    /** /chat?msg=hello — send chat or a command (when msg starts with /). */
    public static Object chat(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("state", "not_in_world"); return out; }
        String msg = q.get("msg");
        if (msg == null) { out.put("error", "missing msg"); return out; }
        // Vanilla chat limit is 256 characters — sendChatMessage will
        // throw on anything longer. Truncate to be forgiving.
        if (msg.length() > 256) msg = msg.substring(0, 256);
        p.sendChatMessage(msg);
        out.put("sent", msg);
        return out;
    }

    /**
     * /click?slotId=N&button=0&mode=PICKUP
     *
     * Clicks a slot in whatever container is currently open. Modes:
     * PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL.
     * button is the mouse button index (0 = left, 1 = right, ...).
     */
    public static Object click(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        PlayerControllerMP pc = mc.playerController;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || pc == null) { out.put("state", "not_in_world"); return out; }
        Container c = p.openContainer;
        if (c == null) { out.put("error", "no open container"); return out; }
        Integer slotId = DebugHttpServer.parseInt(q.get("slotId"), null);
        if (slotId == null) { out.put("error", "missing slotId"); return out; }
        int button = DebugHttpServer.parseInt(q.get("button"), 0);
        ClickType mode = parseClickType(q.get("mode"), ClickType.PICKUP);
        ItemStack result = pc.windowClick(c.windowId, slotId, button, mode, p);
        out.put("windowId", c.windowId);
        out.put("slotId", slotId);
        out.put("button", button);
        out.put("mode", mode.name());
        out.put("resultStack", DebugHttpServer.itemStackJson(result));
        out.put("heldOnMouse", DebugHttpServer.itemStackJson(p.inventory.getItemStack()));
        return out;
    }

    /** /swing?hand=main — play the arm-swing animation. */
    public static Object swing(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("state", "not_in_world"); return out; }
        EnumHand hand = parseHand(q.get("hand"));
        p.swingArm(hand);
        out.put("hand", hand.name());
        return out;
    }

    /** /respawn — send PERFORM_RESPAWN (useful after dying). */
    public static Object respawn(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || p.connection == null) { out.put("state", "not_in_world"); return out; }
        p.connection.sendPacket(new CPacketClientStatus(CPacketClientStatus.State.PERFORM_RESPAWN));
        out.put("ok", true);
        return out;
    }

    /* ========================= helpers ========================= */

    private static void applyRotation(EntityPlayerSP p, float yaw, float pitch) {
        p.rotationYaw = yaw;
        p.rotationPitch = pitch;
        p.prevRotationYaw = yaw;
        p.prevRotationPitch = pitch;
        p.rotationYawHead = yaw;
        p.prevRotationYawHead = yaw;
        p.renderYawOffset = yaw;
        p.prevRenderYawOffset = yaw;
    }

    private static boolean truthy(String s) {
        if (s == null) return false;
        switch (s.toLowerCase()) {
            case "1": case "true": case "yes": case "on":
                return true;
            default: return false;
        }
    }

    private static EnumFacing parseFacing(String s, EnumFacing fallback) {
        if (s == null) return fallback;
        EnumFacing f = EnumFacing.byName(s.toLowerCase());
        return f == null ? fallback : f;
    }

    private static EnumHand parseHand(String s) {
        if (s == null) return EnumHand.MAIN_HAND;
        String ls = s.toLowerCase();
        if (ls.equals("off") || ls.equals("offhand") || ls.equals("off_hand")) {
            return EnumHand.OFF_HAND;
        }
        return EnumHand.MAIN_HAND;
    }

    private static ClickType parseClickType(String s, ClickType fallback) {
        if (s == null) return fallback;
        try { return ClickType.valueOf(s.toUpperCase()); }
        catch (Throwable t) { return fallback; }
    }

    private static Float parseFloat(String s) {
        if (s == null) return null;
        try { return Float.parseFloat(s); } catch (NumberFormatException e) { return null; }
    }

    private static Double parseDoubleObj(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    static KeyBinding resolveKey(GameSettings gs, String name) {
        if (name == null) return null;
        switch (name.toLowerCase()) {
            case "forward":    return gs.keyBindForward;
            case "back":       return gs.keyBindBack;
            case "left":       return gs.keyBindLeft;
            case "right":      return gs.keyBindRight;
            case "jump":       return gs.keyBindJump;
            case "sneak":      return gs.keyBindSneak;
            case "sprint":     return gs.keyBindSprint;
            case "attack":     return gs.keyBindAttack;
            case "use":        return gs.keyBindUseItem;
            case "drop":       return gs.keyBindDrop;
            case "pickblock": case "pick_block":
                return gs.keyBindPickBlock;
            case "swaphands": case "swap_hands": case "swap":
                return gs.keyBindSwapHands;
            case "inventory":  return gs.keyBindInventory;
            case "chat":       return gs.keyBindChat;
            case "command":    return gs.keyBindCommand;
            case "playerlist": case "player_list":
                return gs.keyBindPlayerList;
            default: return null;
        }
    }
}
