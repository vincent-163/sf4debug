package com.telethon.sf4debug;

import com.sun.net.httpserver.HttpServer;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.CooldownTracker;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.EnumDifficulty;

import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep-perception read-only routes: everything a real player sees /
 * hears / feels that the primitive snapshots don't already return.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code /particles?radius=N&limit=M} — enumerates currently
 *       rendered particles (beacon beams, portals, smoke, redstone,
 *       enchant runes, ...).
 *   <li>{@code /sounds.recent?windowTicks=40} — returns sounds played
 *       in the last N client ticks, pulled from an in-process ring
 *       buffer populated by {@link #onPlaySound}.
 *   <li>{@code /cooldown} — attack cooldown, per-item use cooldowns,
 *       swing progress, item-in-use ticks, XP bar progress.
 *   <li>{@code /miningStatus} — whether the player is currently
 *       breaking a block, which block, and the current progress
 *       (0..1) reflected from {@link PlayerControllerMP}.
 *   <li>{@code /entity?id=N} — detailed single-entity state: health,
 *       equipment, potion effects, passengers, riding, etc.
 *   <li>{@code /camera} — 1st/3rd person view, FOV, render distance,
 *       mouse sensitivity, GUI scale and related game settings.
 * </ul>
 *
 * <p>Also exports two augmenters wired into existing snapshot routes:
 * {@link #augmentPlayer(Map)} adds proprioception fields to
 * {@code /player}, and {@link #augmentLook(RayTraceResult, Map)}
 * adds crosshair-entity health/armor/effects to {@code /look} when
 * the hit target is a {@link EntityLivingBase}.
 */
public final class PerceptionRoutes {

    /* ======================== sound ring buffer ======================== */

    /** Max sounds retained in the ring. Tuned to cover ~4s of dense combat. */
    private static final int SOUND_CAP = 512;
    /** Default window size in ticks when the caller doesn't pass one. */
    private static final int DEFAULT_SOUND_WINDOW = 40;
    /** Lock guarding {@link #SOUNDS}. */
    private static final Object SOUND_LOCK = new Object();
    /** Ring of recent sounds; bounded by {@link #SOUND_CAP}. */
    private static final ArrayDeque<SoundRecord> SOUNDS = new ArrayDeque<>(SOUND_CAP);

    private static final class SoundRecord {
        final long wallMs;
        final String name;
        final double x, y, z;
        final float volume, pitch;
        SoundRecord(String name, double x, double y, double z, float v, float p) {
            this.wallMs = System.currentTimeMillis();
            this.name = name;
            this.x = x; this.y = y; this.z = z;
            this.volume = v; this.pitch = p;
        }
    }

    private PerceptionRoutes() {}

    /** Registers this class as a Forge event subscriber. Call from SF4Debug.init. */
    public static void init() {
        MinecraftForge.EVENT_BUS.register(PerceptionRoutes.class);
    }

    /** Registers all perception endpoints on the given server. */
    public static void register(HttpServer server) {
        server.createContext("/particles",     DebugHttpServer.wrap(PerceptionRoutes::particles));
        server.createContext("/sounds.recent", DebugHttpServer.wrap(PerceptionRoutes::soundsRecent));
        server.createContext("/cooldown",      DebugHttpServer.wrap(PerceptionRoutes::cooldown));
        server.createContext("/miningStatus",  DebugHttpServer.wrap(PerceptionRoutes::miningStatus));
        server.createContext("/entity",        DebugHttpServer.wrap(PerceptionRoutes::entity));
        server.createContext("/camera",        DebugHttpServer.wrap(PerceptionRoutes::camera));
    }

    /* ===================== sound subscriber ===================== */

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent ev) {
        try {
            ISound s = ev.getSound();
            if (s == null) return;
            ResourceLocation rl = s.getSoundLocation();
            if (rl == null) return;
            SoundRecord rec = new SoundRecord(
                    rl.toString(),
                    s.getXPosF(), s.getYPosF(), s.getZPosF(),
                    s.getVolume(), s.getPitch());
            synchronized (SOUND_LOCK) {
                if (SOUNDS.size() >= SOUND_CAP) SOUNDS.pollFirst();
                SOUNDS.addLast(rec);
            }
        } catch (Throwable ignored) {}
    }

    /* ========================= /particles ========================= */

    /**
     * {@code GET /particles?radius=N&limit=M}
     *
     * <p>Walks {@code Minecraft.effectRenderer.fxLayers} (a 4&times;2
     * grid of {@link ArrayDeque}{@code <Particle>}) and returns every
     * particle within {@code radius} blocks of the player. Default
     * radius = 32, max 128. Default limit = 500, max 5000. Each record
     * exposes {@code class/simpleClass/x/y/z/dist/age/maxAge/alpha}
     * plus whichever of {@code red/green/blue/scale} we can reflectively
     * read.
     */
    public static Object particles(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null || mc.effectRenderer == null) {
            out.put("state", "not_in_world");
            return out;
        }
        double radius = clamp(DebugHttpServer.parseDouble(q.get("radius"), 32.0), 0.0, 128.0);
        int limit = Math.max(1, Math.min(DebugHttpServer.parseInt(q.get("limit"), 500), 5000));
        double r2 = radius * radius;

        Object[][] layers = readFxLayers(mc.effectRenderer);
        List<Map<String, Object>> list = new ArrayList<>();
        int scanned = 0;
        int total = 0;
        if (layers != null) {
            for (Object[] sub : layers) {
                if (sub == null) continue;
                for (Object deque : sub) {
                    if (!(deque instanceof Deque)) continue;
                    // Snapshot to avoid CME: the renderer mutates on the
                    // client thread but we run on the client thread too,
                    // so this is mostly belt-and-braces.
                    Object[] particles = ((Deque<?>) deque).toArray();
                    total += particles.length;
                    for (Object o : particles) {
                        if (!(o instanceof Particle)) continue;
                        scanned++;
                        Particle pt = (Particle) o;
                        double px = readParticleDouble(pt, "field_187128_a", "posX");
                        double py = readParticleDouble(pt, "field_187129_b", "posY");
                        double pz = readParticleDouble(pt, "field_187130_c", "posZ");
                        double dx = px - p.posX;
                        double dy = py - p.posY;
                        double dz = pz - p.posZ;
                        double d2 = dx * dx + dy * dy + dz * dz;
                        if (d2 > r2) continue;
                        if (list.size() >= limit) continue;
                        list.add(particleJson(pt, px, py, pz, Math.sqrt(d2)));
                    }
                }
            }
        }
        out.put("radius", radius);
        out.put("scanned", scanned);
        out.put("totalLoaded", total);
        out.put("returned", list.size());
        out.put("particles", list);
        return out;
    }

    private static Map<String, Object> particleJson(Particle pt, double px, double py, double pz, double dist) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", pt.getClass().getName());
        m.put("simpleClass", pt.getClass().getSimpleName());
        m.put("x", px);
        m.put("y", py);
        m.put("z", pz);
        m.put("dist", dist);
        m.put("motionX", readParticleDouble(pt, "field_187131_d", "motionX"));
        m.put("motionY", readParticleDouble(pt, "field_187132_e", "motionY"));
        m.put("motionZ", readParticleDouble(pt, "field_187133_f", "motionZ"));
        // Protected animation fields — best-effort reflection. The
        // SRG/MCP pair is the same across the 1.12.2 MCP snapshot;
        // if a mod-added subclass renames them, they'll be dropped.
        Integer age = readIntField(pt, "field_70546_d", "particleAge");
        if (age != null) m.put("age", age);
        Integer maxAge = readIntField(pt, "field_70547_e", "particleMaxAge");
        if (maxAge != null) m.put("maxAge", maxAge);
        Float alpha = readFloatField(pt, "field_82339_as", "particleAlpha");
        if (alpha != null) m.put("alpha", alpha);
        return m;
    }

    private static double readParticleDouble(Particle pt, String srg, String mcp) {
        Field f = findField(Particle.class, srg, mcp);
        if (f == null) return 0.0;
        try { return f.getDouble(pt); } catch (Throwable ignored) { return 0.0; }
    }

    /**
     * Reflectively pull the {@code fxLayers} two-dimensional array out
     * of the renderer. The field is package-private in MCP ({@code
     * field_178932_g}). Returns {@code null} on reflection failure.
     */
    private static Object[][] readFxLayers(ParticleManager pm) {
        Field f = findField(ParticleManager.class, "field_178932_g", "fxLayers");
        if (f == null) return null;
        try {
            Object v = f.get(pm);
            if (v instanceof Object[][]) return (Object[][]) v;
            if (v instanceof Object[]) {
                // Forge has historically reshaped this; treat single-
                // dim as a one-row grid.
                return new Object[][] { (Object[]) v };
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /* ===================== /sounds.recent ===================== */

    /**
     * {@code GET /sounds.recent?windowTicks=40&windowMs=2000}
     *
     * <p>Returns all sounds recorded in the last {@code windowTicks}
     * client ticks (50 ms each). Alternatively {@code windowMs}
     * overrides with a wall-clock window. Each record has
     * {@code ageMs/name/x/y/z/volume/pitch}.
     */
    public static Object soundsRecent(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        int windowTicks = Math.max(1, Math.min(DebugHttpServer.parseInt(q.get("windowTicks"),
                DEFAULT_SOUND_WINDOW), 2000));
        int windowMsParam = DebugHttpServer.parseInt(q.get("windowMs"), windowTicks * 50);
        int windowMs = Math.max(1, Math.min(windowMsParam, 120_000));
        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;
        List<Map<String, Object>> list = new ArrayList<>();
        synchronized (SOUND_LOCK) {
            // Evict records older than the cutoff proactively so the
            // buffer can't grow stale when the game is idle.
            while (!SOUNDS.isEmpty() && SOUNDS.peekFirst().wallMs < cutoff - 60_000L) {
                SOUNDS.pollFirst();
            }
            for (SoundRecord rec : SOUNDS) {
                if (rec.wallMs < cutoff) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ageMs", now - rec.wallMs);
                m.put("name", rec.name);
                m.put("x", rec.x);
                m.put("y", rec.y);
                m.put("z", rec.z);
                m.put("volume", rec.volume);
                m.put("pitch", rec.pitch);
                list.add(m);
            }
        }
        out.put("windowTicks", windowTicks);
        out.put("windowMs", windowMs);
        out.put("returned", list.size());
        out.put("sounds", list);
        return out;
    }

    /* ========================= /cooldown ========================= */

    /**
     * {@code GET /cooldown} — attack cooldown (0..1), per-item use
     * cooldowns reflected out of {@link CooldownTracker}'s private
     * map, current swing progress, held-item in-use ticks, and the
     * XP progress to the next level.
     */
    public static Object cooldown(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) {
            out.put("state", "not_in_world");
            return out;
        }
        // Attack cooldown: 1.0 = ready / full-power, <1 = recovering.
        out.put("attackStrength", p.getCooledAttackStrength(0f));
        out.put("attackCooldownPeriod", p.getCooldownPeriod());
        // Swing animation state.
        out.put("swingProgress", p.swingProgress);
        out.put("isSwingInProgress", p.isSwingInProgress);
        out.put("swingingHand", p.swingingHand == null ? null : p.swingingHand.name());
        // Item-in-use (eating / drinking / bow-draw / shield).
        ItemStack activeStack = p.getActiveItemStack();
        out.put("isHandActive", p.isHandActive());
        out.put("activeHand", p.getActiveHand() == null ? null : p.getActiveHand().name());
        out.put("activeItemStack", DebugHttpServer.itemStackJson(activeStack));
        out.put("itemInUseCount", p.getItemInUseCount());
        out.put("itemInUseMaxCount", p.getItemInUseMaxCount());
        // XP progress.
        out.put("xpProgress", p.experience);
        out.put("xpLevel", p.experienceLevel);
        out.put("xpTotal", p.experienceTotal);
        try { out.put("xpBarCap", p.xpBarCap()); } catch (Throwable ignored) {}

        // Per-item cooldown tracker. 1.12.2 has getCooldown(Item, float)
        // but no public enumerator, so reflect the internal map. We
        // locate the Map field by type rather than by name so this
        // survives MCP vs. SRG name differences across reobf.
        List<Map<String, Object>> cooldowns = new ArrayList<>();
        try {
            CooldownTracker tracker = p.getCooldownTracker();
            if (tracker != null) {
                Field mapField = findFieldByType(CooldownTracker.class, Map.class);
                if (mapField != null) {
                    Object raw = mapField.get(tracker);
                    if (raw instanceof Map) {
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                            Object key = e.getKey();
                            if (!(key instanceof Item)) continue;
                            Item item = (Item) key;
                            Map<String, Object> cd = new LinkedHashMap<>();
                            cd.put("id", item.getRegistryName() == null
                                    ? null : item.getRegistryName().toString());
                            cd.put("progress", tracker.getCooldown(item, 0f));
                            Object cdObj = e.getValue();
                            if (cdObj != null) {
                                // CooldownTracker$Cooldown has two ints:
                                // createTicks / expireTicks. Grab them
                                // by type to dodge SRG name drift.
                                Integer[] ints = readAllIntFields(cdObj);
                                if (ints != null && ints.length >= 2) {
                                    cd.put("createTick", ints[0]);
                                    cd.put("expireTick", ints[1]);
                                }
                            }
                            cooldowns.add(cd);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        out.put("itemCooldowns", cooldowns);
        return out;
    }

    /* ========================= /miningStatus ========================= */

    /**
     * {@code GET /miningStatus} — reflects
     * {@link PlayerControllerMP#isHittingBlock},
     * {@code currentBlock} (the {@link BlockPos} being mined) and
     * {@code curBlockDamageMP} (0..1 progress). Also estimates ticks
     * until break via {@code IBlockState#getPlayerRelativeBlockHardness}
     * so callers can decide how long to hold attack.
     */
    public static Object miningStatus(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        WorldClient w = mc.world;
        PlayerControllerMP pc = mc.playerController;
        if (p == null || w == null || pc == null) {
            out.put("state", "not_in_world");
            return out;
        }
        Boolean isHitting = readBoolField(pc, "field_78778_j", "isHittingBlock");
        Object currentBlock = readRefField(pc, "field_178895_c", "currentBlock");
        Float damage = readFloatField(pc, "field_78770_f", "curBlockDamageMP");
        Integer blockHitDelay = readIntField(pc, "field_78781_i", "blockHitDelay");
        Integer stepSoundTimer = readIntField(pc, "field_78780_g", "stepSoundTickCounter");
        out.put("isHitting", isHitting == null ? false : isHitting);
        out.put("progress", damage == null ? 0f : damage);
        out.put("blockHitDelay", blockHitDelay == null ? 0 : blockHitDelay);
        out.put("stepSoundTicks", stepSoundTimer == null ? 0 : stepSoundTimer);
        if (currentBlock instanceof BlockPos) {
            BlockPos bp = (BlockPos) currentBlock;
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("x", bp.getX());
            pos.put("y", bp.getY());
            pos.put("z", bp.getZ());
            out.put("pos", pos);
            try {
                IBlockState state = w.getBlockState(bp);
                if (state != null) {
                    out.put("block", state.getBlock().getRegistryName() == null
                            ? null : state.getBlock().getRegistryName().toString());
                    out.put("blockMeta", state.getBlock().getMetaFromState(state));
                    try {
                        float perTick = state.getPlayerRelativeBlockHardness(p, w, bp);
                        out.put("hardnessPerTick", perTick);
                        if (perTick > 0f) {
                            // Progress 1.0 = break ready; damage grows
                            // by perTick each tick while the key is held.
                            float remaining = Math.max(0f, 1f - (damage == null ? 0f : damage));
                            out.put("ticksUntilBreak", (int) Math.ceil(remaining / perTick));
                        }
                        out.put("canHarvest", state.getMaterial().isToolNotRequired()
                                || p.getHeldItemMainhand().canHarvestBlock(state));
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /* ========================= /entity ========================= */

    /**
     * {@code GET /entity?id=N} — detailed state for a single entity
     * by id. Returns class, position, motion, health (for living
     * entities), armor, equipment (main/off/armor), active potion
     * effects, passengers, riding, misc flags (invisible, glowing,
     * silent, sneaking, sprinting) and age.
     */
    public static Object entity(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = mc.world;
        if (w == null) { out.put("state", "not_in_world"); return out; }
        Integer id = DebugHttpServer.parseInt(q.get("id"), null);
        if (id == null) { out.put("error", "missing id"); return out; }
        Entity e = w.getEntityByID(id);
        if (e == null) { out.put("error", "no such entity"); return out; }
        fillEntityDetail(e, out, mc.player);
        return out;
    }

    /**
     * Fills a pre-existing map with the full entity-detail payload.
     * Used by both {@code /entity} and {@link #augmentLook} when the
     * crosshair target is an entity.
     */
    static void fillEntityDetail(Entity e, Map<String, Object> out, EntityPlayerSP observer) {
        out.put("id", e.getEntityId());
        out.put("uuid", e.getUniqueID().toString());
        out.put("class", e.getClass().getName());
        out.put("simpleClass", e.getClass().getSimpleName());
        out.put("name", e.getName());
        try { out.put("customNameTag", e.getCustomNameTag()); } catch (Throwable ignored) {}
        try { out.put("hasCustomName", e.hasCustomName()); } catch (Throwable ignored) {}
        out.put("x", e.posX);
        out.put("y", e.posY);
        out.put("z", e.posZ);
        out.put("yaw", e.rotationYaw);
        out.put("pitch", e.rotationPitch);
        out.put("motionX", e.motionX);
        out.put("motionY", e.motionY);
        out.put("motionZ", e.motionZ);
        out.put("width", e.width);
        out.put("height", e.height);
        out.put("onGround", e.onGround);
        out.put("inWater", e.isInWater());
        out.put("inLava", e.isInLava());
        out.put("isBurning", e.isBurning());
        out.put("isSneaking", e.isSneaking());
        out.put("isSprinting", e.isSprinting());
        out.put("isGlowing", e.isGlowing());
        out.put("isInvisible", e.isInvisible());
        out.put("isSilent", e.isSilent());
        out.put("isDead", e.isDead);
        out.put("ticksExisted", e.ticksExisted);
        out.put("fallDistance", e.fallDistance);
        if (observer != null) {
            double d2 = e.getDistanceSq(observer);
            out.put("dist", Math.sqrt(d2));
        }
        // Riding / passengers.
        try {
            Entity rider = e.getRidingEntity();
            if (rider != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", rider.getEntityId());
                r.put("class", rider.getClass().getName());
                out.put("ridingEntity", r);
            }
        } catch (Throwable ignored) {}
        try {
            List<Entity> passengers = e.getPassengers();
            if (passengers != null && !passengers.isEmpty()) {
                List<Map<String, Object>> pl = new ArrayList<>();
                for (Entity px : passengers) {
                    if (px == null) continue;
                    Map<String, Object> pe = new LinkedHashMap<>();
                    pe.put("id", px.getEntityId());
                    pe.put("class", px.getClass().getName());
                    pl.add(pe);
                }
                out.put("passengers", pl);
            }
        } catch (Throwable ignored) {}

        // Living-specific state.
        if (e instanceof EntityLivingBase) {
            EntityLivingBase lb = (EntityLivingBase) e;
            out.put("health", lb.getHealth());
            out.put("maxHealth", lb.getMaxHealth());
            out.put("absorption", lb.getAbsorptionAmount());
            out.put("armorValue", lb.getTotalArmorValue());
            out.put("hurtTime", lb.hurtTime);
            out.put("maxHurtTime", lb.maxHurtTime);
            out.put("deathTime", lb.deathTime);
            out.put("isChild", lb.isChild());
            out.put("isElytraFlying", lb.isElytraFlying());
            // Equipment.
            Map<String, Object> equip = new LinkedHashMap<>();
            for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
                try {
                    ItemStack st = lb.getItemStackFromSlot(slot);
                    if (st != null && !st.isEmpty()) {
                        equip.put(slot.getName(), DebugHttpServer.itemStackJson(st));
                    }
                } catch (Throwable ignored) {}
            }
            out.put("equipment", equip);
            // Active potion effects.
            List<Map<String, Object>> effects = new ArrayList<>();
            try {
                for (PotionEffect pe : lb.getActivePotionEffects()) {
                    if (pe == null) continue;
                    Map<String, Object> ej = new LinkedHashMap<>();
                    ej.put("id", pe.getEffectName());
                    ej.put("amplifier", pe.getAmplifier());
                    ej.put("durationTicks", pe.getDuration());
                    effects.add(ej);
                }
            } catch (Throwable ignored) {}
            out.put("potionEffects", effects);
        }
        // Item-entity special: expose stack.
        if (e instanceof EntityItem) {
            ItemStack st = ((EntityItem) e).getItem();
            out.put("item", DebugHttpServer.itemStackJson(st));
        }
        // Player-specific.
        if (e instanceof EntityPlayer) {
            EntityPlayer ep = (EntityPlayer) e;
            out.put("xpLevel", ep.experienceLevel);
            out.put("gameProfileName", ep.getGameProfile() == null
                    ? null : ep.getGameProfile().getName());
        }
    }

    /* ========================= /camera ========================= */

    /**
     * {@code GET /camera} — current view settings: 1st/3rd person,
     * base FOV vs. rendered FOV modifier (sprint/bow), mouse
     * sensitivity, render distance, GUI scale, view-bobbing,
     * smooth camera, entity-shadows, fancy graphics, difficulty.
     * Also exposes the engine's current frame timing from
     * {@link Minecraft#getDebugFPS()}.
     */
    public static Object camera(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        GameSettings gs = mc.gameSettings;
        if (gs == null) {
            out.put("error", "no game settings");
            return out;
        }
        out.put("thirdPersonView", gs.thirdPersonView);
        out.put("fov", gs.fovSetting);
        EntityPlayerSP p = mc.player;
        if (p != null) {
            try {
                // Rendered FOV factors in sprint / bow-draw / slowness.
                out.put("renderedFovModifier", p.getFovModifier());
            } catch (Throwable ignored) {}
        }
        out.put("mouseSensitivity", gs.mouseSensitivity);
        out.put("renderDistanceChunks", gs.renderDistanceChunks);
        out.put("guiScale", gs.guiScale);
        out.put("viewBobbing", gs.viewBobbing);
        out.put("smoothCamera", gs.smoothCamera);
        out.put("fancyGraphics", gs.fancyGraphics);
        out.put("entityShadows", gs.entityShadows);
        out.put("particlesSetting", gs.particleSetting);
        out.put("chatVisibility", gs.chatVisibility == null ? null : gs.chatVisibility.name());
        out.put("showDebugInfo", gs.showDebugInfo);
        out.put("fullScreen", gs.fullScreen);
        try {
            if (mc.world != null) {
                EnumDifficulty diff = mc.world.getDifficulty();
                out.put("difficulty", diff == null ? null : diff.name());
            }
        } catch (Throwable ignored) {}
        out.put("fps", Minecraft.getDebugFPS());
        return out;
    }

    /* ====================== augmentPlayer / augmentLook ====================== */

    /**
     * Adds proprioception fields to a {@code /player} map — things a
     * real player feels: am I in water / on fire / stuck in a wall /
     * falling / being ridden / can I fly right now. No-op if the
     * player isn't in the world.
     */
    public static void augmentPlayer(Map<String, Object> out) {
        if (out == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) return;
        try {
            out.put("isInWater", p.isInWater());
            out.put("isInLava", p.isInLava());
            out.put("isWet", p.isWet());
            out.put("isOnLadder", p.isOnLadder());
            out.put("isCollidedHorizontally", p.collidedHorizontally);
            out.put("isCollidedVertically", p.collidedVertically);
            out.put("isGlowing", p.isGlowing());
            out.put("isInvisible", p.isInvisible());
            out.put("isSilent", p.isSilent());
            out.put("isOnFire", p.isBurning());
            out.put("isDead", p.isDead);
            out.put("isSleeping", p.isPlayerSleeping());
            out.put("fallDistance", p.fallDistance);
            out.put("hurtTime", p.hurtTime);
            out.put("maxHurtTime", p.maxHurtTime);
            out.put("deathTime", p.deathTime);
            out.put("ticksExisted", p.ticksExisted);
            out.put("portalCounter", readIntFieldOrDefault(p,
                    new String[] {"field_71088_bW", "timeUntilPortal"}, 0));
            out.put("inPortal", readBoolFieldOrDefault(p,
                    new String[] {"field_71087_bX", "inPortal"}, false));
            out.put("isRiding", p.isRiding());
            out.put("isBeingRidden", p.isBeingRidden());
            if (p.getRidingEntity() != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", p.getRidingEntity().getEntityId());
                r.put("class", p.getRidingEntity().getClass().getName());
                out.put("ridingEntity", r);
            }
            // Capabilities.
            if (p.capabilities != null) {
                Map<String, Object> caps = new LinkedHashMap<>();
                caps.put("allowFlying", p.capabilities.allowFlying);
                caps.put("isFlying", p.capabilities.isFlying);
                caps.put("allowEdit", p.capabilities.allowEdit);
                caps.put("isCreativeMode", p.capabilities.isCreativeMode);
                caps.put("disableDamage", p.capabilities.disableDamage);
                caps.put("walkSpeed", p.capabilities.getWalkSpeed());
                caps.put("flySpeed", p.capabilities.getFlySpeed());
                out.put("capabilities", caps);
            }
            out.put("isCreative", p.isCreative());
            out.put("isSpectator", p.isSpectator());
            // Swing / in-use state.
            out.put("swingProgress", p.swingProgress);
            out.put("isSwingInProgress", p.isSwingInProgress);
            out.put("swingingHand", p.swingingHand == null ? null : p.swingingHand.name());
            out.put("isHandActive", p.isHandActive());
            out.put("activeHand", p.getActiveHand() == null ? null : p.getActiveHand().name());
            out.put("itemInUseCount", p.getItemInUseCount());
            out.put("itemInUseMaxCount", p.getItemInUseMaxCount());
            out.put("attackStrength", p.getCooledAttackStrength(0f));
            out.put("armorValue", p.getTotalArmorValue());
        } catch (Throwable ignored) {}
    }

    /**
     * For entity hits in {@code /look}, adds health/armor/equipment/
     * active-potion-effects of the target entity so an agent can
     * decide whether the mob it's pointing at is worth attacking.
     * Silent no-op for block hits / null entities.
     */
    public static void augmentLook(RayTraceResult hit, Map<String, Object> out) {
        if (hit == null || out == null) return;
        if (hit.typeOfHit != RayTraceResult.Type.ENTITY) return;
        Entity e = hit.entityHit;
        if (e == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        Map<String, Object> target = new LinkedHashMap<>();
        try {
            fillEntityDetail(e, target, mc.player);
        } catch (Throwable t) {
            target.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        out.put("entity", target);
    }

    /* ============================ helpers ============================ */

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static Field findField(Class<?> c, String... names) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (String n : names) {
                try {
                    Field f = k.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // next name / superclass
                }
            }
        }
        return null;
    }

    private static Integer readIntField(Object target, String... names) {
        if (target == null) return null;
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try {
            if (f.getType() == int.class) return f.getInt(target);
            Object v = f.get(target);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {}
        return null;
    }

    private static int readIntFieldOrDefault(Object target, String[] names, int def) {
        Integer v = readIntField(target, names);
        return v == null ? def : v;
    }

    private static Float readFloatField(Object target, String... names) {
        if (target == null) return null;
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try {
            if (f.getType() == float.class) return f.getFloat(target);
            Object v = f.get(target);
            if (v instanceof Number) return ((Number) v).floatValue();
        } catch (Throwable ignored) {}
        return null;
    }

    private static Double readDoubleField(Object target, String... names) {
        if (target == null) return null;
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try {
            if (f.getType() == double.class) return f.getDouble(target);
            Object v = f.get(target);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}
        return null;
    }

    private static Boolean readBoolField(Object target, String... names) {
        if (target == null) return null;
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try {
            if (f.getType() == boolean.class) return f.getBoolean(target);
            Object v = f.get(target);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean readBoolFieldOrDefault(Object target, String[] names, boolean def) {
        Boolean v = readBoolField(target, names);
        return v == null ? def : v;
    }

    private static Object readRefField(Object target, String... names) {
        if (target == null) return null;
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try { return f.get(target); } catch (Throwable ignored) { return null; }
    }

    /** First declared field whose type is assignable-from {@code type}. */
    private static Field findFieldByType(Class<?> owner, Class<?> type) {
        for (Class<?> k = owner; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    /** Reads every {@code int} declared field on {@code target} in declared order. */
    private static Integer[] readAllIntFields(Object target) {
        if (target == null) return new Integer[0];
        List<Integer> ints = new ArrayList<>();
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                try {
                    f.setAccessible(true);
                    ints.add(f.getInt(target));
                } catch (Throwable ignored) {}
            }
        }
        return ints.toArray(new Integer[0]);
    }
}
