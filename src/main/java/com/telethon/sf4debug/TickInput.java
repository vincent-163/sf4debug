package com.telethon.sf4debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simulates a human holding a {@link KeyBinding} for a fixed number of
 * client ticks. The HTTP thread calls {@link #hold(KeyBinding, int)};
 * the client tick handler re-asserts the binding as pressed each tick
 * and releases it when the counter reaches zero.
 *
 * <p>Also owns three mutually-exclusive aim plans that the client-tick
 * handler walks once per tick:
 *
 * <ul>
 *   <li>{@link SmoothAimPlan} — linear interpolation to a single
 *       (yaw, pitch) target. Driven by
 *       {@link #scheduleSmoothAim(float, float, int)}.
 *   <li>{@link AimPathPlan} — a sequence of waypoints executed
 *       back-to-back. Driven by
 *       {@link #scheduleAimPath(float[], float[], int[])}.
 *   <li>{@link EntityTrackPlan} — re-target each tick to the current
 *       position of an entity by id. Driven by
 *       {@link #scheduleEntityTrack(int, int, int, boolean)}.
 * </ul>
 *
 * <p>Scheduling a new plan replaces any existing plan of any type.
 *
 * <p>Why this class exists:
 *
 * <ul>
 *   <li>Setting {@code mc.player.movementInput.moveForward = 1.0f}
 *       directly doesn't work — {@code MovementInputFromOptions} overwrites
 *       those fields each tick from the actual keybinds.
 *   <li>Changing velocity via {@code motionX/Y/Z} desyncs from the server
 *       and anti-cheats reject it.
 *   <li>Pressing a keybind once via {@link KeyBinding#setKeyBindState}
 *       gets clobbered the moment the user moves their real mouse or
 *       the GUI opens. Re-asserting each tick keeps it held for as long
 *       as we asked.
 * </ul>
 *
 * <p>Thread-safety: {@link #hold}/{@link #release}/{@link #click} are
 * safe to call from any thread (the maps are concurrent). Aim plan
 * writes go through {@link AtomicReference}s. State mutations on the
 * binding itself and the player's rotation happen on the client tick,
 * on the client thread, so there is no race with Minecraft's own input
 * code.
 */
public final class TickInput {

    private static final TickInput INSTANCE = new TickInput();

    /** binding -> remaining client ticks during which we re-assert pressed. */
    private final Map<KeyBinding, Integer> holds = new ConcurrentHashMap<>();

    /** One-shot "click" queue: bindings that get one pressTime++ on next tick. */
    private final Map<KeyBinding, Integer> clicks = new ConcurrentHashMap<>();

    /** Active single-target smooth-aim plan; null when idle. */
    private final AtomicReference<SmoothAimPlan> smoothAim = new AtomicReference<>();
    /** Active multi-waypoint aim-path plan; null when idle. */
    private final AtomicReference<AimPathPlan> aimPath = new AtomicReference<>();
    /** Active entity-tracking plan; null when idle. */
    private final AtomicReference<EntityTrackPlan> entityTrack = new AtomicReference<>();

    /**
     * Set a {@link KeyBinding} as pressed/unpressed via the public
     * {@link KeyBinding#setKeyBindState} API.  We used to reflect into the
     * private {@code pressed} field directly, but that breaks at runtime
     * because the reobfuscator remaps the field name (e.g. to
     * {@code field_74513_e}) and leaves literal reflection strings untouched.
     *
     * <p>Forge's {@code setKeyBindState} uses {@code KeyBindingMap} which
     * correctly handles mouse-button key codes (negative values) and
     * key-modifiers, so this works for every binding including attack/use.
     */
    private static void setPressed(KeyBinding kb, boolean value) {
        if (kb == null) return;
        KeyBinding.setKeyBindState(kb.getKeyCode(), value);
    }

    /**
     * Increment {@code pressTime} via the public {@link KeyBinding#onTick}
     * API so the next {@link KeyBinding#isPressed()} call returns {@code true}.
     * Same remapping-safe rationale as {@link #setPressed}.
     */
    private static void incrementPressTime(KeyBinding kb) {
        if (kb == null) return;
        KeyBinding.onTick(kb.getKeyCode());
    }

    /** Linear-interpolation plan for rotation across N client ticks. */
    private static final class SmoothAimPlan {
        float targetYaw;
        float targetPitch;
        int   ticksRemaining;
        int   ticksTotal;
        float fromYaw;
        float fromPitch;
    }

    /** Ordered list of (yaw, pitch, ticks) legs. */
    private static final class AimPathPlan {
        float[] yaws;
        float[] pitches;
        int[]   ticksPerLeg;
        int     currentLeg;
        int     legTicksRemaining;
        float   legFromYaw;
        float   legFromPitch;
    }

    /** Live-follow an entity by id for {@code ticks} ticks, easing in {@code easeTicks}. */
    private static final class EntityTrackPlan {
        int     entityId;
        int     ticksRemaining;
        int     easeTicks;
        /** When true, aim at {@code Entity.getPositionEyes(1f)} instead of feet+height/2. */
        boolean eyeLevel;
    }

    private TickInput() {}

    public static TickInput get() { return INSTANCE; }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    /**
     * Keeps {@code kb} pressed for {@code ticks} client ticks.
     * {@code ticks <= 0} releases it immediately. Repeated calls take
     * the max remaining duration — overlapping holds extend naturally
     * instead of racing each other.
     */
    public void hold(KeyBinding kb, int ticks) {
        if (kb == null) return;
        if (ticks <= 0) {
            release(kb);
            return;
        }
        Integer prev = holds.putIfAbsent(kb, ticks);
        if (prev == null) {
            // Fresh hold: also nudge pressTime so sprint-double-tap and
            // other isPressed() consumers see a proper press event.
            incrementPressTime(kb);
        } else if (ticks > prev) {
            holds.replace(kb, prev, ticks);
        }
    }

    /** Release any active hold on {@code kb} right now. */
    public void release(KeyBinding kb) {
        if (kb == null) return;
        holds.remove(kb);
        setPressed(kb, false);
    }

    /** Queue a single pressTime++ "click" to be applied on the next tick. */
    public void click(KeyBinding kb) {
        if (kb == null) return;
        clicks.merge(kb, 1, Integer::sum);
    }

    /**
     * Schedule a linear interpolation of the player's rotation toward
     * ({@code targetYaw}, {@code targetPitch}) across {@code ticks}
     * client ticks. Shortest-arc yaw: the delta is normalized to
     * [-180, 180] before stepping so a 350° target from 10° steps
     * backward by 20° rather than forward by 340°.
     *
     * <p>Scheduling any aim plan clears every other aim plan.
     */
    public void scheduleSmoothAim(float targetYaw, float targetPitch, int ticks) {
        if (ticks <= 0) return;
        SmoothAimPlan p = new SmoothAimPlan();
        p.targetYaw = targetYaw;
        p.targetPitch = targetPitch;
        p.ticksRemaining = ticks;
        p.ticksTotal = ticks;
        EntityPlayerSP pl = Minecraft.getMinecraft().player;
        p.fromYaw   = pl == null ? targetYaw   : pl.rotationYaw;
        p.fromPitch = pl == null ? targetPitch : pl.rotationPitch;
        aimPath.set(null);
        entityTrack.set(null);
        smoothAim.set(p);
    }

    /**
     * Schedule a multi-waypoint aim path. Each leg walks
     * {@code ticksPerLeg[i]} ticks from the end of the previous leg to
     * ({@code yaws[i]}, {@code pitches[i]}) using the same
     * shortest-arc interpolation as {@link #scheduleSmoothAim}. All
     * three arrays must be the same non-zero length.
     */
    public void scheduleAimPath(float[] yaws, float[] pitches, int[] ticksPerLeg) {
        if (yaws == null || pitches == null || ticksPerLeg == null) return;
        if (yaws.length == 0) return;
        if (pitches.length != yaws.length || ticksPerLeg.length != yaws.length) return;
        AimPathPlan p = new AimPathPlan();
        p.yaws = yaws.clone();
        p.pitches = pitches.clone();
        p.ticksPerLeg = ticksPerLeg.clone();
        p.currentLeg = 0;
        p.legTicksRemaining = Math.max(1, p.ticksPerLeg[0]);
        EntityPlayerSP pl = Minecraft.getMinecraft().player;
        p.legFromYaw   = pl == null ? yaws[0]    : pl.rotationYaw;
        p.legFromPitch = pl == null ? pitches[0] : pl.rotationPitch;
        smoothAim.set(null);
        entityTrack.set(null);
        aimPath.set(p);
    }

    /**
     * Schedule an entity-tracking aim: each tick, compute yaw/pitch
     * toward entity {@code entityId}'s current position and step the
     * player's rotation toward it by {@code delta / easeTicks}. Expires
     * after {@code ticks} ticks or when the entity disappears.
     *
     * @param eyeLevel if true, aim at {@link Entity#getPositionEyes(float)}
     *                 (shoulder/eye height); if false, aim at
     *                 {@code entity.posY + entity.height/2}.
     */
    public void scheduleEntityTrack(int entityId, int ticks, int easeTicks, boolean eyeLevel) {
        if (ticks <= 0) return;
        EntityTrackPlan p = new EntityTrackPlan();
        p.entityId = entityId;
        p.ticksRemaining = ticks;
        p.easeTicks = Math.max(1, easeTicks);
        p.eyeLevel = eyeLevel;
        smoothAim.set(null);
        aimPath.set(null);
        entityTrack.set(p);
    }

    /** Clears all aim plans (smooth/path/track). Leaves held keys alone. */
    public void cancelAim() {
        smoothAim.set(null);
        aimPath.set(null);
        entityTrack.set(null);
    }

    /** Release everything. Useful as a panic-stop from {@code /stop}. */
    public void releaseAll() {
        for (KeyBinding kb : holds.keySet()) {
            setPressed(kb, false);
        }
        holds.clear();
        clicks.clear();
        cancelAim();
    }

    /** Snapshot of currently-held bindings for debug output. */
    public Map<String, Integer> snapshotHolds() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<KeyBinding, Integer> e : holds.entrySet()) {
            out.put(e.getKey().getKeyDescription(), e.getValue());
        }
        return out;
    }

    /**
     * Snapshot of the currently-active aim plan (if any). Exactly one
     * of {@code smoothAim}/{@code aimPath}/{@code entityTrack} will be
     * populated, or none when idle. Always safe to call off-thread.
     */
    public Map<String, Object> snapshotAim() {
        Map<String, Object> out = new LinkedHashMap<>();
        SmoothAimPlan   sa = smoothAim.get();
        AimPathPlan     ap = aimPath.get();
        EntityTrackPlan et = entityTrack.get();
        out.put("active", sa != null || ap != null || et != null);
        if (sa != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticksRemaining", sa.ticksRemaining);
            m.put("ticksTotal", sa.ticksTotal);
            m.put("fromYaw", sa.fromYaw);
            m.put("fromPitch", sa.fromPitch);
            m.put("targetYaw", sa.targetYaw);
            m.put("targetPitch", sa.targetPitch);
            out.put("smoothAim", m);
        }
        if (ap != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("currentLeg", ap.currentLeg);
            m.put("totalLegs", ap.yaws == null ? 0 : ap.yaws.length);
            m.put("legTicksRemaining", ap.legTicksRemaining);
            m.put("legFromYaw", ap.legFromYaw);
            m.put("legFromPitch", ap.legFromPitch);
            if (ap.yaws != null && ap.currentLeg < ap.yaws.length) {
                m.put("legTargetYaw", ap.yaws[ap.currentLeg]);
                m.put("legTargetPitch", ap.pitches[ap.currentLeg]);
            }
            if (ap.yaws != null) {
                List<Map<String, Object>> legs = new ArrayList<>();
                for (int i = 0; i < ap.yaws.length; i++) {
                    Map<String, Object> leg = new LinkedHashMap<>();
                    leg.put("yaw", ap.yaws[i]);
                    leg.put("pitch", ap.pitches[i]);
                    leg.put("ticks", ap.ticksPerLeg[i]);
                    legs.add(leg);
                }
                m.put("legs", legs);
            }
            out.put("aimPath", m);
        }
        if (et != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entityId", et.entityId);
            m.put("ticksRemaining", et.ticksRemaining);
            m.put("easeTicks", et.easeTicks);
            m.put("eyeLevel", et.eyeLevel);
            out.put("entityTrack", m);
        }
        return out;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent ev) {
        // Process on START so that simulated key presses are visible to
        // Minecraft's own input processing (movement, attack, use, etc.)
        // which happens during the body of the client tick.
        if (ev.phase != TickEvent.Phase.START) return;

        // Aim handling — plans are mutually exclusive but we check all
        // three refs in order so a stale plan left over after a server
        // swap can't block a new one.
        tickSmoothAim();
        tickAimPath();
        tickEntityTrack();

        // Held bindings: re-assert pressed, decrement, release at zero.
        for (Iterator<Map.Entry<KeyBinding, Integer>> it = holds.entrySet().iterator(); it.hasNext();) {
            Map.Entry<KeyBinding, Integer> e = it.next();
            KeyBinding kb = e.getKey();
            int remaining = e.getValue();
            if (remaining <= 0) {
                setPressed(kb, false);
                it.remove();
                continue;
            }
            setPressed(kb, true);
            e.setValue(remaining - 1);
        }

        // One-shot clicks.
        for (Iterator<Map.Entry<KeyBinding, Integer>> it = clicks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<KeyBinding, Integer> e = it.next();
            int n = e.getValue();
            KeyBinding kb = e.getKey();
            for (int i = 0; i < n; i++) {
                incrementPressTime(kb);
            }
            it.remove();
        }

        // If attack is actively held, clear Minecraft.leftClickCounter so a
        // stale counter (spiked to 10000 while any GUI was open) can't block
        // sendClickBlockToController / clickMouse for thousands of ticks.
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && holds.containsKey(mc.gameSettings.keyBindAttack)) {
            PauseRoutes.resetLeftClickCounter(mc);
        }
    }

    /* ============================ aim tickers ============================ */

    private void tickSmoothAim() {
        SmoothAimPlan plan = smoothAim.get();
        if (plan == null) return;
        EntityPlayerSP p = Minecraft.getMinecraft().player;
        if (p == null || plan.ticksRemaining <= 0) {
            smoothAim.set(null);
            return;
        }
        float dYaw = MathHelper.wrapDegrees(plan.targetYaw - p.rotationYaw);
        float dPitch = plan.targetPitch - p.rotationPitch;
        float stepY = dYaw / plan.ticksRemaining;
        float stepP = dPitch / plan.ticksRemaining;
        p.rotationYaw += stepY;
        p.rotationPitch = MathHelper.clamp(p.rotationPitch + stepP, -90f, 90f);
        p.prevRotationYaw = p.rotationYaw;
        p.prevRotationPitch = p.rotationPitch;
        plan.ticksRemaining--;
        if (plan.ticksRemaining <= 0) {
            p.rotationYaw = plan.targetYaw;
            p.rotationPitch = MathHelper.clamp(plan.targetPitch, -90f, 90f);
            p.prevRotationYaw = p.rotationYaw;
            p.prevRotationPitch = p.rotationPitch;
            smoothAim.set(null);
        }
    }

    private void tickAimPath() {
        AimPathPlan plan = aimPath.get();
        if (plan == null) return;
        EntityPlayerSP p = Minecraft.getMinecraft().player;
        if (p == null || plan.yaws == null || plan.currentLeg >= plan.yaws.length) {
            aimPath.set(null);
            return;
        }
        float legYaw = plan.yaws[plan.currentLeg];
        float legPitch = plan.pitches[plan.currentLeg];
        if (plan.legTicksRemaining <= 0) {
            // Shouldn't happen, but snap and advance defensively.
            p.rotationYaw = legYaw;
            p.rotationPitch = MathHelper.clamp(legPitch, -90f, 90f);
            p.prevRotationYaw = p.rotationYaw;
            p.prevRotationPitch = p.rotationPitch;
            plan.currentLeg++;
            if (plan.currentLeg < plan.yaws.length) {
                plan.legTicksRemaining = Math.max(1, plan.ticksPerLeg[plan.currentLeg]);
                plan.legFromYaw = p.rotationYaw;
                plan.legFromPitch = p.rotationPitch;
            } else {
                aimPath.set(null);
            }
            return;
        }
        float dYaw = MathHelper.wrapDegrees(legYaw - p.rotationYaw);
        float dPitch = legPitch - p.rotationPitch;
        float stepY = dYaw / plan.legTicksRemaining;
        float stepP = dPitch / plan.legTicksRemaining;
        p.rotationYaw += stepY;
        p.rotationPitch = MathHelper.clamp(p.rotationPitch + stepP, -90f, 90f);
        p.prevRotationYaw = p.rotationYaw;
        p.prevRotationPitch = p.rotationPitch;
        plan.legTicksRemaining--;
        if (plan.legTicksRemaining <= 0) {
            p.rotationYaw = legYaw;
            p.rotationPitch = MathHelper.clamp(legPitch, -90f, 90f);
            p.prevRotationYaw = p.rotationYaw;
            p.prevRotationPitch = p.rotationPitch;
            plan.currentLeg++;
            if (plan.currentLeg < plan.yaws.length) {
                plan.legTicksRemaining = Math.max(1, plan.ticksPerLeg[plan.currentLeg]);
                plan.legFromYaw = p.rotationYaw;
                plan.legFromPitch = p.rotationPitch;
            } else {
                aimPath.set(null);
            }
        }
    }

    private void tickEntityTrack() {
        EntityTrackPlan plan = entityTrack.get();
        if (plan == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        WorldClient w = mc.world;
        if (p == null || w == null || plan.ticksRemaining <= 0) {
            entityTrack.set(null);
            return;
        }
        Entity target = w.getEntityByID(plan.entityId);
        if (target == null) {
            entityTrack.set(null);
            return;
        }
        Vec3d eye = p.getPositionEyes(1f);
        double tx, ty, tz;
        if (plan.eyeLevel) {
            Vec3d te = target.getPositionEyes(1f);
            tx = te.x; ty = te.y; tz = te.z;
        } else {
            tx = target.posX;
            ty = target.posY + target.height * 0.5;
            tz = target.posZ;
        }
        double dx = tx - eye.x;
        double dy = ty - eye.y;
        double dz = tz - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float tYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float tPitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        tPitch = MathHelper.clamp(tPitch, -90f, 90f);
        float dYaw = MathHelper.wrapDegrees(tYaw - p.rotationYaw);
        float dPitch = tPitch - p.rotationPitch;
        int ease = Math.max(1, plan.easeTicks);
        p.rotationYaw += dYaw / ease;
        p.rotationPitch = MathHelper.clamp(p.rotationPitch + dPitch / ease, -90f, 90f);
        p.prevRotationYaw = p.rotationYaw;
        p.prevRotationPitch = p.rotationPitch;
        plan.ticksRemaining--;
        if (plan.ticksRemaining <= 0) {
            entityTrack.set(null);
        }
    }
}
