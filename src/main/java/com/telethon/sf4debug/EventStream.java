package com.telethon.sf4debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.chunk.Chunk;

import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.PotionEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.event.entity.player.PlayerPickupXpEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-process event broker for sf4debug's long-poll {@code /events}
 * endpoint. Forge event subscribers (chat, damage, pickups, chunk
 * load/unload, sound, etc.) and a per-tick diff (health, food, xp,
 * weather, time-of-day phase) funnel into a single bounded ring
 * buffer. HTTP callers pull from the buffer via {@link #poll} with a
 * monotonic {@code since} cursor.
 *
 * <p>Thread-safety: {@link #push} and {@link #poll} synchronize on a
 * private {@code LOCK}; either is safe from any thread. Event-bus
 * subscribers run on the client thread, which matters only for code
 * that reads {@code mc.player} / {@code mc.world}. The ring buffer
 * itself does not care what thread pushed into it.
 */
public final class EventStream {

    /** Max events retained in the ring buffer. */
    private static final int CAP = 2048;

    /** Per-response limit ceiling (also clamps any user-supplied limit). */
    private static final int MAX_LIMIT = 2048;
    /** Default per-response limit if the caller did not pass one. */
    private static final int DEFAULT_LIMIT = 256;

    /** Default long-poll wait in seconds. */
    private static final int DEFAULT_WAIT_SECONDS = 20;
    /** Max long-poll wait in seconds. Clamp to prevent absurd blocking. */
    private static final int MAX_WAIT_SECONDS = 60;

    /** Max characters retained for free-form string payloads (chat, sound name). */
    private static final int MAX_STR_LEN = 2000;

    /** Monitor guarding {@link #BUFFER}, {@link #cursor}, {@link #totalEvicted}. */
    private static final Object LOCK = new Object();

    /** Events, oldest at head, newest at tail. Access under {@link #LOCK}. */
    private static final ArrayDeque<Map<String, Object>> BUFFER = new ArrayDeque<>(CAP);

    /** Monotonic counter assigned to each pushed event. Access under {@link #LOCK}. */
    private static long cursor = 0L;

    /** Total number of events evicted from the head of {@link #BUFFER}. Access under {@link #LOCK}. */
    private static long totalEvicted = 0L;

    /* ------------------------- tick-diff state ------------------------- */

    /** Becomes {@code true} on the first tick that {@code mc.player != null}. */
    private static volatile boolean seeded = false;
    /** Previous tick's {@code mc.player.getHealth()} snapshot. {@code NaN} when unseeded. */
    private static volatile float prevHealth = Float.NaN;
    /** Previous tick's {@code foodLevel} snapshot. {@code MIN_VALUE} when unseeded. */
    private static volatile int prevFood = Integer.MIN_VALUE;
    /** Previous tick's {@code experienceLevel} snapshot. {@code MIN_VALUE} when unseeded. */
    private static volatile int prevXpLevel = Integer.MIN_VALUE;
    /** Previous tick's raining flag. {@code null} when unseeded. */
    private static volatile Boolean prevRain = null;
    /** Previous tick's thundering flag. {@code null} when unseeded. */
    private static volatile Boolean prevThunder = null;
    /** Previous tick's time-of-day phase. {@code null} when unseeded. */
    private static volatile String prevPhase = null;
    /** Previous tick's hotbar slot (inventory.currentItem). {@code MIN_VALUE} when unseeded. */
    private static volatile int prevHotbarSlot = Integer.MIN_VALUE;
    /** Previous tick's dimension id. {@code MIN_VALUE} when unseeded. */
    private static volatile int prevDimension = Integer.MIN_VALUE;
    /** Previous tick's gamemode name ({@code null} when unseeded). */
    private static volatile String prevGameMode = null;
    /** Previous tick's openContainer class name ({@code null} when unseeded / plain inventory). */
    private static volatile String prevOpenContainerClass = null;
    /** Previous tick's openContainer windowId ({@code null} when unseeded / plain inventory). */
    private static volatile Integer prevOpenContainerWindowId = null;
    /** Previous tick's sleep flag. {@code null} when unseeded. */
    private static volatile Boolean prevSleeping = null;
    /**
     * Last tick we emitted a {@code velocity} event. We throttle to at
     * most once per 10 ticks (500 ms) to avoid spamming callers when the
     * player is falling or being launched continuously.
     */
    private static volatile long prevVelocityTick = Long.MIN_VALUE;
    /** Tick counter driven by {@code onClientTick}. Used by the velocity throttle. */
    private static volatile long tickCount = 0L;

    /** Last-opened GUI class name, for {@code guiClose.previousClassName}. */
    private static volatile String lastGuiClass = null;

    private EventStream() {}

    /** Registers all event-bus subscribers. Called once from {@code SF4Debug.init()}. */
    public static void init() {
        MinecraftForge.EVENT_BUS.register(EventStream.class);
    }

    /* -------------------------- push / poll -------------------------- */

    /**
     * Append an event to the ring buffer. Safe from any thread. The
     * {@code payload} map is copied defensively so the caller can
     * mutate or reuse it after this returns. Any collision with the
     * reserved keys {@code cursor}/{@code time}/{@code type} is
     * resolved in favor of the reserved value.
     */
    public static void push(String type, Map<String, Object> payload) {
        if (type == null) return;
        Map<String, Object> event = new LinkedHashMap<>();
        if (payload != null) {
            // Defensive copy — safe even if caller mutates after push.
            event.putAll(payload);
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            cursor++;
            // Reserved keys are written last so they win over any
            // collision in caller's payload.
            event.put("cursor", cursor);
            event.put("time", now);
            event.put("type", type);
            if (BUFFER.size() >= CAP) {
                BUFFER.pollFirst();
                totalEvicted++;
            }
            BUFFER.addLast(event);
            LOCK.notifyAll();
        }
    }

    /**
     * Poll the buffer, optionally blocking until a new event arrives
     * or {@code waitMs} elapses. Returns a JSON-serializable map:
     * <pre>
     *   { "cursor": &lt;newest cursor after this call&gt;,
     *     "events": [ ...matching events... ],
     *     "dropped": &lt;events with cursor &gt; since that were evicted&gt; }
     * </pre>
     *
     * @param since   monotonic cursor from a previous call; 0 = start.
     * @param waitMs  max milliseconds to block; 0 = non-blocking.
     * @param types   allowlist of event types; {@code null} = accept all.
     * @param limit   max events to return in this call; clamped to [1, {@value #MAX_LIMIT}].
     */
    public static Map<String, Object> poll(long since, int waitMs, Set<String> types, int limit)
            throws InterruptedException {
        if (limit <= 0) limit = 1;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        if (waitMs < 0) waitMs = 0;
        long deadline = System.currentTimeMillis() + waitMs;

        synchronized (LOCK) {
            // Long-poll: block until a new event arrives past `since`
            // or the deadline expires. Handles spurious wakeups.
            while (cursor <= since) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) break;
                LOCK.wait(remaining);
            }

            // Count events with cursor > since that were evicted
            // before the caller could see them.
            long dropped = Math.max(0L, totalEvicted - Math.max(0L, since));

            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> ev : BUFFER) {
                Long c = (Long) ev.get("cursor");
                if (c == null || c <= since) continue;
                if (types != null) {
                    Object tp = ev.get("type");
                    if (!(tp instanceof String) || !types.contains(tp)) continue;
                }
                out.add(ev);
                if (out.size() >= limit) break;
            }

            long newCursor;
            if (!out.isEmpty()) {
                Long last = (Long) out.get(out.size() - 1).get("cursor");
                newCursor = last != null ? last : cursor;
            } else {
                newCursor = cursor;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("cursor", newCursor);
            result.put("events", out);
            result.put("dropped", dropped);
            return result;
        }
    }

    /* -------------------------- helpers -------------------------- */

    /** Log an event-handler failure at DEBUG without rethrowing — never stall the bus. */
    private static void logHandlerFailure(String where, Throwable t) {
        try {
            if (SF4Debug.LOG != null) SF4Debug.LOG.debug("sf4debug event handler {} failed", where, t);
        } catch (Throwable ignored) {}
    }

    /** Truncate free-form strings to {@link #MAX_STR_LEN} to bound payload size. */
    private static String trunc(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_STR_LEN) return s;
        return s.substring(0, MAX_STR_LEN);
    }

    /** Map a vanilla {@link ChatType} to the bot-friendly {@code "chat"/"system"/"action"} bucket. */
    private static String chatTypeName(ChatType ct) {
        if (ct == null) return "chat";
        // Keep switch by name so we don't depend on enum ordinal stability.
        String n = ct.name();
        if ("CHAT".equals(n))      return "chat";
        if ("SYSTEM".equals(n))    return "system";
        if ("GAME_INFO".equals(n)) return "action"; // action-bar overlay
        return n.toLowerCase(Locale.ROOT);
    }

    /**
     * Bucket a Minecraft world time (in ticks, mod 24000) into one of
     * {@code "day"}, {@code "dusk"}, {@code "night"}, {@code "dawn"}.
     * Boundaries match the spec in {@code TODO.md}.
     */
    private static String phaseOf(long worldTime) {
        long m = ((worldTime % 24000L) + 24000L) % 24000L;
        if (m < 12000L) return "day";
        if (m < 13000L) return "dusk";
        if (m < 23000L) return "night";
        return "dawn";
    }

    /**
     * Reset tick-diff seed state so the next tick re-establishes
     * baselines without emitting a spurious delta.
     */
    private static void resetSeed() {
        seeded = false;
        prevHealth = Float.NaN;
        prevFood = Integer.MIN_VALUE;
        prevXpLevel = Integer.MIN_VALUE;
        prevRain = null;
        prevThunder = null;
        prevPhase = null;
        prevHotbarSlot = Integer.MIN_VALUE;
        prevDimension = Integer.MIN_VALUE;
        prevGameMode = null;
        prevOpenContainerClass = null;
        prevOpenContainerWindowId = null;
        prevSleeping = null;
    }

    /* -------------------------- tick diff -------------------------- */

    /**
     * Client tick (START phase): sample player/world state, emit an
     * event for every field that changed since last tick. On the first
     * tick that {@code mc.player != null} we only seed, we don't emit
     * any deltas.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent ev) {
        if (ev.phase != TickEvent.Phase.START) return;
        try {
            tickCount++;
            Minecraft mc = Minecraft.getMinecraft();
            EntityPlayerSP p = mc.player;
            WorldClient w = mc.world;
            if (p == null || w == null) {
                // Leaving the world: drop baselines so next join reseeds.
                if (seeded) resetSeed();
                return;
            }

            float hp = p.getHealth();
            int food = p.getFoodStats().getFoodLevel();
            int xpLevel = p.experienceLevel;
            boolean rain = w.isRaining();
            boolean thunder = w.isThundering();
            long time = w.getWorldTime();
            String phase = phaseOf(time);
            int hotbarSlot = p.inventory.currentItem;
            int dimension = p.dimension;
            PlayerControllerMP pc = mc.playerController;
            String gameMode = (pc == null || pc.getCurrentGameType() == null)
                    ? null : pc.getCurrentGameType().getName();
            Container openCont = p.openContainer;
            boolean isCustomGui = openCont != null && openCont != p.inventoryContainer;
            String openContClass = isCustomGui ? openCont.getClass().getName() : null;
            Integer openContWindowId = isCustomGui ? openCont.windowId : null;
            boolean sleeping = p.isPlayerSleeping();

            if (!seeded) {
                prevHealth = hp;
                prevFood = food;
                prevXpLevel = xpLevel;
                prevRain = rain;
                prevThunder = thunder;
                prevPhase = phase;
                prevHotbarSlot = hotbarSlot;
                prevDimension = dimension;
                prevGameMode = gameMode;
                prevOpenContainerClass = openContClass;
                prevOpenContainerWindowId = openContWindowId;
                prevSleeping = sleeping;
                seeded = true;
                return;
            }

            if (!Float.isNaN(prevHealth) && hp != prevHealth) {
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("value", hp);
                pl.put("delta", hp - prevHealth);
                push("health", pl);
            }
            prevHealth = hp;

            if (food != prevFood) {
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("value", food);
                pl.put("delta", food - prevFood);
                push("food", pl);
            }
            prevFood = food;

            if (xpLevel != prevXpLevel) {
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("value", xpLevel);
                pl.put("delta", xpLevel - prevXpLevel);
                push("xpLevel", pl);
            }
            prevXpLevel = xpLevel;

            if (!Objects.equals(prevRain, rain) || !Objects.equals(prevThunder, thunder)) {
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("rain", rain);
                pl.put("thunder", thunder);
                push("weather", pl);
            }
            prevRain = rain;
            prevThunder = thunder;

            if (!phase.equals(prevPhase)) {
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("phase", phase);
                pl.put("time", time);
                push("timeOfDay", pl);
            }
            prevPhase = phase;

            if (hotbarSlot != prevHotbarSlot) {
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("slot", hotbarSlot);
                pl.put("previousSlot", prevHotbarSlot);
                ItemStack held = p.getHeldItemMainhand();
                pl.put("held", DebugHttpServer.itemStackJson(held));
                push("hotbar.select", pl);
            }
            prevHotbarSlot = hotbarSlot;

            if (dimension != prevDimension) {
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("from", prevDimension);
                pl.put("to", dimension);
                push("dimension.change", pl);
            }
            prevDimension = dimension;

            if (!Objects.equals(prevGameMode, gameMode)) {
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("from", prevGameMode);
                pl.put("to", gameMode);
                push("gamemode.change", pl);
            }
            prevGameMode = gameMode;

            if (!Objects.equals(prevOpenContainerClass, openContClass)
                    || !Objects.equals(prevOpenContainerWindowId, openContWindowId)) {
                if (openContClass != null) {
                    Map<String, Object> pl = new LinkedHashMap<>();
                    pl.put("class", openContClass);
                    pl.put("windowId", openContWindowId);
                    pl.put("slotCount", openCont.inventorySlots == null
                            ? 0 : openCont.inventorySlots.size());
                    push("container.open", pl);
                }
                if (prevOpenContainerClass != null
                        && (openContClass == null
                            || !Objects.equals(prevOpenContainerWindowId, openContWindowId))) {
                    Map<String, Object> pl = new LinkedHashMap<>();
                    pl.put("class", prevOpenContainerClass);
                    pl.put("windowId", prevOpenContainerWindowId);
                    push("container.close", pl);
                }
            }
            prevOpenContainerClass = openContClass;
            prevOpenContainerWindowId = openContWindowId;

            if (!Objects.equals(prevSleeping, sleeping)) {
                push(sleeping ? "sleep.enter" : "sleep.exit",
                        new LinkedHashMap<String, Object>());
            }
            prevSleeping = sleeping;

            // Velocity: emit when significant motion is happening, at
            // most once per 10 ticks so free-fall doesn't flood.
            double speedSq = p.motionX * p.motionX + p.motionY * p.motionY + p.motionZ * p.motionZ;
            if (speedSq > 0.16 /* ~0.4 blocks/tick */ && tickCount - prevVelocityTick >= 10) {
                prevVelocityTick = tickCount;
                Map<String, Object> pl = new LinkedHashMap<>();
                pl.put("x", p.motionX);
                pl.put("y", p.motionY);
                pl.put("z", p.motionZ);
                pl.put("speed", Math.sqrt(speedSq));
                pl.put("fallDistance", p.fallDistance);
                push("velocity", pl);
            }
        } catch (Throwable t) {
            logHandlerFailure("clientTick", t);
        }
    }

    /* -------------------------- subscribers -------------------------- */

    @SubscribeEvent
    public static void onChatReceived(ClientChatReceivedEvent ev) {
        try {
            ITextComponent msg = ev.getMessage();
            String text = msg == null ? "" : msg.getUnformattedText();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("text", trunc(text));
            p.put("chatType", chatTypeName(ev.getType()));
            push("chat", p);
        } catch (Throwable t) {
            logHandlerFailure("chat", t);
        }
    }

    @SubscribeEvent
    public static void onChatSent(ClientChatEvent ev) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("text", trunc(ev.getMessage()));
            push("chat.sent", p);
        } catch (Throwable t) {
            logHandlerFailure("chatSent", t);
        }
    }

    @SubscribeEvent
    public static void onGuiOpen(GuiOpenEvent ev) {
        try {
            GuiScreen newGui = ev.getGui();
            String prev = lastGuiClass;
            if (newGui == null) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("previousClassName", prev);
                push("guiClose", p);
                lastGuiClass = null;
            } else {
                String cls = newGui.getClass().getName();
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("className", cls);
                if (prev != null) p.put("previous", prev);
                push("guiOpen", p);
                lastGuiClass = cls;
            }
        } catch (Throwable t) {
            logHandlerFailure("guiOpen", t);
        }
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || ev.getEntity() != mc.player) return;
            DamageSource src = ev.getSource();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("source", src == null ? null : src.getDamageType());
            p.put("amount", ev.getAmount());
            p.put("newHealth", mc.player.getHealth());
            push("damage", p);
        } catch (Throwable t) {
            logHandlerFailure("damage", t);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || ev.getEntity() != mc.player) return;
            EntityPlayerSP pl = mc.player;
            DamageSource src = ev.getSource();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("source", src == null ? null : src.getDamageType());
            p.put("x", pl.posX);
            p.put("y", pl.posY);
            p.put("z", pl.posZ);
            p.put("dimension", pl.dimension);
            push("death", p);
        } catch (Throwable t) {
            logHandlerFailure("death", t);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            // FMLClientHandler fires this for the local player; ignore
            // hypothetical server-side firings if they ever leak through.
            if (mc.player == null || ev.player != mc.player) return;
            EntityPlayerSP pl = mc.player;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", pl.posX);
            p.put("y", pl.posY);
            p.put("z", pl.posZ);
            p.put("dimension", pl.dimension);
            push("respawn", p);
            // Health/food/xp reset to defaults on respawn — reseed so we
            // don't immediately emit deltas comparing pre-death vs post.
            resetSeed();
        } catch (Throwable t) {
            logHandlerFailure("respawn", t);
        }
    }

    @SubscribeEvent
    public static void onItemPickup(EntityItemPickupEvent ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || ev.getEntityPlayer() != mc.player) return;
            EntityItem ei = ev.getItem();
            ItemStack st = ei == null ? null : ei.getItem();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("item", DebugHttpServer.itemStackJson(st));
            p.put("amount", (st == null || st.isEmpty()) ? 0 : st.getCount());
            push("pickup.item", p);
        } catch (Throwable t) {
            logHandlerFailure("pickup.item", t);
        }
    }

    @SubscribeEvent
    public static void onXpPickup(PlayerPickupXpEvent ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || ev.getEntityPlayer() != mc.player) return;
            EntityXPOrb orb = ev.getOrb();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("amount", orb == null ? 0 : orb.getXpValue());
            push("pickup.xp", p);
        } catch (Throwable t) {
            logHandlerFailure("pickup.xp", t);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            // Only emit for the client world. Forge may also fire this
            // on the integrated server; we want just the client view.
            if (mc.world == null || ev.getWorld() != mc.world) return;
            Chunk c = ev.getChunk();
            if (c == null) return;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", c.x);
            p.put("z", c.z);
            push("chunkLoad", p);
        } catch (Throwable t) {
            logHandlerFailure("chunkLoad", t);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world == null || ev.getWorld() != mc.world) return;
            Chunk c = ev.getChunk();
            if (c == null) return;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", c.x);
            p.put("z", c.z);
            push("chunkUnload", p);
        } catch (Throwable t) {
            logHandlerFailure("chunkUnload", t);
        }
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent ev) {
        try {
            ISound sound = ev.getSound();
            if (sound == null) return;
            ResourceLocation rl = sound.getSoundLocation();
            if (rl == null) return; // not all sounds have a resource location; skip silently
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", trunc(rl.toString()));
            p.put("x", (double) sound.getXPosF());
            p.put("y", (double) sound.getYPosF());
            p.put("z", (double) sound.getZPosF());
            p.put("volume", sound.getVolume());
            p.put("pitch", sound.getPitch());
            push("sound", p);
        } catch (Throwable t) {
            logHandlerFailure("sound", t);
        }
    }

    @SubscribeEvent
    public static void onPotionAdded(PotionEvent.PotionAddedEvent ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || ev.getEntity() != mc.player) return;
            PotionEffect eff = ev.getPotionEffect();
            if (eff == null) return;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", eff.getEffectName());
            p.put("amplifier", eff.getAmplifier());
            p.put("durationTicks", eff.getDuration());
            push("potion.add", p);
        } catch (Throwable t) {
            logHandlerFailure("potion.add", t);
        }
    }

    @SubscribeEvent
    public static void onPotionRemoved(PotionEvent.PotionRemoveEvent ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || ev.getEntity() != mc.player) return;
            PotionEffect eff = ev.getPotionEffect();
            Map<String, Object> p = new LinkedHashMap<>();
            if (eff != null) {
                p.put("id", eff.getEffectName());
                p.put("amplifier", eff.getAmplifier());
                p.put("durationTicks", eff.getDuration());
            } else if (ev.getPotion() != null && ev.getPotion().getRegistryName() != null) {
                p.put("id", ev.getPotion().getRegistryName().toString());
            }
            push("potion.remove", p);
        } catch (Throwable t) {
            logHandlerFailure("potion.remove", t);
        }
    }

    @SubscribeEvent
    public static void onPotionExpired(PotionEvent.PotionExpiryEvent ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || ev.getEntity() != mc.player) return;
            PotionEffect eff = ev.getPotionEffect();
            Map<String, Object> p = new LinkedHashMap<>();
            if (eff != null) {
                p.put("id", eff.getEffectName());
                p.put("amplifier", eff.getAmplifier());
            }
            push("potion.expire", p);
        } catch (Throwable t) {
            logHandlerFailure("potion.expire", t);
        }
    }

    @SubscribeEvent
    public static void onItemBroken(PlayerDestroyItemEvent ev) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || ev.getEntityPlayer() != mc.player) return;
            ItemStack st = ev.getOriginal();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("item", DebugHttpServer.itemStackJson(st));
            p.put("hand", ev.getHand() == null ? null : ev.getHand().name());
            push("item.broken", p);
        } catch (Throwable t) {
            logHandlerFailure("item.broken", t);
        }
    }

    @SubscribeEvent
    public static void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent ev) {
        try {
            push("disconnect", new LinkedHashMap<String, Object>());
        } catch (Throwable t) {
            logHandlerFailure("disconnect", t);
        } finally {
            // Drop baselines and GUI tracking so the next join reseeds
            // cleanly rather than emitting a spurious delta or a stale
            // guiClose {previousClassName} string.
            try { resetSeed(); } catch (Throwable ignored) {}
            lastGuiClass = null;
        }
    }

    /* ---------------------- defaults exposed for EventRoutes ---------------------- */

    /** Default long-poll wait in seconds (clamped to {@link #MAX_WAIT_SECONDS}). */
    public static int defaultWaitSeconds() { return DEFAULT_WAIT_SECONDS; }
    /** Max long-poll wait in seconds. */
    public static int maxWaitSeconds()     { return MAX_WAIT_SECONDS; }
    /** Default per-response limit. */
    public static int defaultLimit()       { return DEFAULT_LIMIT; }
    /** Max per-response limit. */
    public static int maxLimit()           { return MAX_LIMIT; }
}
