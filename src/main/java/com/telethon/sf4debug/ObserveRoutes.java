package com.telethon.sf4debug;

import com.google.common.collect.ImmutableMap;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.BossInfoClient;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiBossOverlay;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only observation routes: everything a real player sees on their
 * HUD that {@link DebugHttpServer}'s primitive snapshots don't already
 * return.
 *
 * <p>All five endpoints ({@code /bossbars}, {@code /scoreboard},
 * {@code /chat}, {@code /overlay}, {@code /world}) are client-thread
 * safe because {@link DebugHttpServer#wrap} marshals them to the
 * client thread before invocation. The package-private
 * {@code augment*} helpers are also always called on the client
 * thread by {@link DebugHttpServer}'s existing snapshot handlers.
 *
 * <p>Reflection follows the same SRG-first, MCP-fallback ordering
 * used by {@link GuiRoutes}: pass {@code {"field_xxx", "mcpName"}}
 * to {@link #findField(Class, String...)}.
 */
public final class ObserveRoutes {

    private ObserveRoutes() {}

    /* ===================== registration ===================== */

    /**
     * Registers the five observation endpoints on the given server.
     * Call this exactly once, from {@link DebugHttpServer#start} after
     * {@code HttpServer.create(...)} and before {@code server.start()}.
     */
    public static void register(HttpServer server) {
        server.createContext("/bossbars",   DebugHttpServer.wrap(ObserveRoutes::bossbars));
        server.createContext("/scoreboard", DebugHttpServer.wrap(ObserveRoutes::scoreboard));
        server.createContext("/chatlog",    DebugHttpServer.wrap(ObserveRoutes::chat));
        server.createContext("/overlay",    DebugHttpServer.wrap(ObserveRoutes::overlay));
        server.createContext("/world",      DebugHttpServer.wrap(ObserveRoutes::world));
    }

    /* ===================== /bossbars ===================== */

    /**
     * {@code GET /bossbars} — reads the private {@code mapBossInfos}
     * field of {@code Minecraft.ingameGUI.getBossOverlay()} and returns
     * every currently-visible boss-bar as JSON.
     */
    public static Object bossbars(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> bars = new ArrayList<>();
        out.put("bars", bars);
        Minecraft mc = Minecraft.getMinecraft();
        GuiIngame gui = mc.ingameGUI;
        if (gui == null) return out;
        GuiBossOverlay bossOverlay = gui.getBossOverlay();
        if (bossOverlay == null) return out;
        Map<UUID, BossInfoClient> bossMap = readField(bossOverlay,
                "field_184060_g", "mapBossInfos");
        if (bossMap == null) return out;
        for (Map.Entry<UUID, BossInfoClient> e : bossMap.entrySet()) {
            BossInfoClient bi = e.getValue();
            if (bi == null) continue;
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("uuid", e.getKey() == null ? null : e.getKey().toString());
            ITextComponent name = bi.getName();
            b.put("name", name == null ? "" : truncateChat(name.getUnformattedText()));
            b.put("nameJson", name == null ? null : safeComponentToJson(name));
            b.put("percent", bi.getPercent());
            b.put("color",   bi.getColor()   == null ? null : bi.getColor().name());
            b.put("overlay", bi.getOverlay() == null ? null : bi.getOverlay().name());
            b.put("darkenSky",  bi.shouldDarkenSky());
            // 1.12.2 "playEndBossMusic" is the same flag later versions
            // renamed to "thickenFog"; expose under the modern key.
            b.put("thickenFog", bi.shouldPlayEndBossMusic());
            b.put("createFog",  bi.shouldCreateFog());
            bars.add(b);
        }
        return out;
    }

    /* ===================== /scoreboard ===================== */

    /**
     * {@code GET /scoreboard} — returns the sidebar objective
     * (display slot 1). Matches vanilla render: filters
     * {@code #}-prefixed fake players and caps to the top 15.
     */
    public static Object scoreboard(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> lines = new ArrayList<>();
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = mc.world;
        if (w == null) {
            out.put("objective", null);
            out.put("displayName", null);
            out.put("lines", lines);
            return out;
        }
        Scoreboard sb = w.getScoreboard();
        if (sb == null) {
            out.put("objective", null);
            out.put("displayName", null);
            out.put("lines", lines);
            return out;
        }
        ScoreObjective obj = sb.getObjectiveInDisplaySlot(1); // 1 = sidebar
        if (obj == null) {
            out.put("objective", null);
            out.put("displayName", null);
            out.put("lines", lines);
            return out;
        }
        out.put("objective", obj.getName());
        out.put("displayName", obj.getDisplayName());
        Collection<Score> scores = sb.getSortedScores(obj);
        if (scores != null) {
            // Filter identical to GuiIngame.renderScoreboard: drop nulls
            // and hidden `#`-prefixed entries, then take the last 15.
            List<Score> valid = new ArrayList<>();
            for (Score s : scores) {
                if (s == null) continue;
                String pn = s.getPlayerName();
                if (pn != null && pn.startsWith("#")) continue;
                valid.add(s);
            }
            int start = Math.max(0, valid.size() - 15);
            for (int i = start; i < valid.size(); i++) {
                Score s = valid.get(i);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("name", s.getPlayerName());
                line.put("score", s.getScorePoints());
                lines.add(line);
            }
        }
        out.put("lines", lines);
        return out;
    }

    /* ===================== /chat ===================== */

    /**
     * {@code GET /chat?limit=50} — reads {@code GuiNewChat#chatLines}
     * (list of {@link ChatLine}) reflectively. Default 50, max 200.
     * Order is Minecraft's own (newest first).
     */
    public static Object chat(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> lines = new ArrayList<>();
        out.put("lines", lines);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI == null) return out;
        GuiNewChat chatGui = mc.ingameGUI.getChatGUI();
        if (chatGui == null) return out;
        int reqLimit = DebugHttpServer.parseInt(q.get("limit"), 50);
        int limit = Math.max(0, Math.min(reqLimit, 200));
        List<ChatLine> chatLines = readField(chatGui,
                "field_146252_h", "chatLines");
        out.put("limit", limit);
        if (chatLines == null) {
            out.put("total", 0);
            return out;
        }
        out.put("total", chatLines.size());
        // Take a defensive snapshot; Minecraft mutates the list on the
        // client thread (same thread we're on, so in practice safe, but
        // copying is cheap and avoids surprises).
        List<ChatLine> snapshot = new ArrayList<>(chatLines);
        int taken = 0;
        for (ChatLine line : snapshot) {
            if (taken >= limit) break;
            if (line == null) continue;
            Map<String, Object> lj = new LinkedHashMap<>();
            try {
                lj.put("age", line.getUpdatedCounter());
            } catch (Throwable t) {
                lj.put("age", null);
            }
            ITextComponent comp = null;
            try { comp = line.getChatComponent(); }
            catch (Throwable ignored) {}
            lj.put("text", comp == null ? "" : truncateChat(comp.getUnformattedText()));
            lj.put("json", comp == null ? null : safeComponentToJson(comp));
            lines.add(lj);
            taken++;
        }
        out.put("returned", lines.size());
        return out;
    }

    /* ===================== /overlay ===================== */

    /**
     * {@code GET /overlay} — action-bar text, title/subtitle, title
     * fade/stay timers, and a best-effort toast list.
     */
    public static Object overlay(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        GuiIngame gui = mc.ingameGUI;
        if (gui == null) {
            out.put("actionBar", "");
            out.put("title", "");
            out.put("subtitle", "");
            out.put("toasts", new ArrayList<>());
            return out;
        }
        // Action bar: String overlayMessage + int overlayMessageTime.
        String actionBar = readField(gui, "field_73838_g", "overlayMessage");
        Integer actionBarTicks = readIntField(gui,
                "field_73845_h", "overlayMessageTime");
        out.put("actionBar", actionBar == null ? "" : actionBar);
        out.put("actionBarTicks", actionBarTicks == null ? 0 : actionBarTicks);

        // Title / subtitle.
        String title = readField(gui, "field_175201_x", "displayedTitle");
        String subtitle = readField(gui, "field_175200_y", "displayedSubTitle");
        out.put("title", title == null ? "" : title);
        out.put("subtitle", subtitle == null ? "" : subtitle);
        Integer titlesTimer = readIntField(gui,
                "field_175195_w", "titlesTimer");
        Integer titleFadeIn = readIntField(gui,
                "field_175199_z", "titleFadeIn",
                "field_175198_A"); // TODO-listed SRG as extra fallback
        // MCP snapshot names this "titleDisplayTime"; spec calls it
        // titleStayTime. Try the canonical name first, then aliases.
        Integer titleStayTime = readIntField(gui,
                "field_175192_A", "titleDisplayTime",
                "field_175197_B", "titleStayTime");
        Integer titleFadeOut = readIntField(gui,
                "field_175193_B", "titleFadeOut",
                "field_175196_C");
        out.put("titlesTimer", titlesTimer == null ? 0 : titlesTimer);
        out.put("titleFadeInTicks", titleFadeIn == null ? 0 : titleFadeIn);
        out.put("titleStayTicks", titleStayTime == null ? 0 : titleStayTime);
        out.put("titleFadeOutTicks", titleFadeOut == null ? 0 : titleFadeOut);

        // Toasts (best-effort — concrete toast types have different
        // fields and the wrapper type is package-private).
        List<Object> toastList = new ArrayList<>();
        GuiToast toastGui = mc.getToastGui();
        if (toastGui != null) {
            try {
                Object[] visible = readField(toastGui,
                        "field_191791_g", "visible");
                if (visible != null) {
                    for (Object ti : visible) {
                        if (ti == null) continue;
                        Map<String, Object> tj = describeToastInstance(ti, false);
                        if (tj != null) toastList.add(tj);
                    }
                }
            } catch (Throwable ignored) {}
            try {
                Deque<IToast> queue = readField(toastGui,
                        "field_191792_h", "toastsQueue");
                if (queue != null) {
                    for (IToast tt : queue) {
                        if (tt == null) continue;
                        Map<String, Object> tj = describeToastInstance(tt, true);
                        if (tj != null) toastList.add(tj);
                    }
                }
            } catch (Throwable ignored) {}
        }
        out.put("toasts", toastList);
        return out;
    }

    /** Describe a {@code ToastInstance} or bare {@link IToast}. */
    private static Map<String, Object> describeToastInstance(Object instanceOrToast, boolean queued) {
        if (instanceOrToast == null) return null;
        Object toast = instanceOrToast;
        // If wrapped in a ToastInstance, unwrap the inner toast field.
        if (!(toast instanceof IToast)) {
            Object inner = readFieldAny(instanceOrToast, "field_193688_b", "toast");
            if (inner == null) {
                // Fall back to the public getToast() method.
                Method m = findMethod(instanceOrToast.getClass(),
                        new String[] { "func_193685_a", "getToast" });
                if (m != null) {
                    try { inner = m.invoke(instanceOrToast); }
                    catch (Throwable ignored) {}
                }
            }
            if (inner instanceof IToast) toast = inner;
        }
        if (!(toast instanceof IToast)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("class", toast.getClass().getName());
        out.put("simpleClass", toast.getClass().getSimpleName());
        if (queued) out.put("queued", true);
        populateToastFields(toast, out);
        return out;
    }

    /** Best-effort extraction of human-readable fields from any toast. */
    private static void populateToastFields(Object toast, Map<String, Object> out) {
        // SystemToast#title / subtitle (String).
        addStringField(out, "title", toast,
                "field_193660_d", "title");
        addStringField(out, "subtitle", toast,
                "field_193661_e", "subtitle");
        // TutorialToast / other variants — try common names.
        addStringField(out, "displayedText", toast,
                "displayedText", "body", "message");
        // AdvancementToast#advancement -> try to expose the ID + title.
        Object advancement = readFieldAny(toast,
                "field_193679_c", "advancement");
        if (advancement != null) {
            Map<String, Object> advJson = new LinkedHashMap<>();
            advJson.put("class", advancement.getClass().getName());
            // Advancement#getId() -> ResourceLocation
            try {
                Method getId = findMethod(advancement.getClass(),
                        new String[] { "func_192067_g", "getId" });
                if (getId != null) {
                    Object id = getId.invoke(advancement);
                    if (id != null) advJson.put("id", id.toString());
                }
            } catch (Throwable ignored) {}
            // Advancement#getDisplayText() -> ITextComponent
            try {
                Method getDisplay = findMethod(advancement.getClass(),
                        new String[] { "func_193123_j", "getDisplayText" });
                if (getDisplay != null) {
                    Object txt = getDisplay.invoke(advancement);
                    if (txt instanceof ITextComponent) {
                        advJson.put("text", truncateChat(
                                ((ITextComponent) txt).getUnformattedText()));
                    }
                }
            } catch (Throwable ignored) {}
            out.put("advancement", advJson);
        }
        // RecipeToast#recipesOutputs -> summarize count.
        Object recipes = readFieldAny(toast,
                "field_193666_c", "recipesOutputs");
        if (recipes instanceof Collection) {
            out.put("recipes", ((Collection<?>) recipes).size());
        }
    }

    private static void addStringField(Map<String, Object> out, String key,
                                       Object target, String... names) {
        Object v = readFieldAny(target, names);
        if (v == null) return;
        if (v instanceof ITextComponent) {
            out.put(key, truncateChat(((ITextComponent) v).getUnformattedText()));
        } else {
            out.put(key, truncateChat(v.toString()));
        }
    }

    /* ===================== /world ===================== */

    /**
     * {@code GET /world} — world time, weather, light/biome under the
     * player, and biome climate values.
     */
    public static Object world(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = mc.world;
        EntityPlayerSP p = mc.player;
        if (w == null || p == null) {
            out.put("state", "not_in_world");
            return out;
        }
        out.put("state", "in_world");
        out.put("dimension", p.dimension);
        long wt = w.getWorldTime();
        out.put("worldTime", wt);
        out.put("timeOfDay", ((wt % 24000L) + 24000L) % 24000L);
        out.put("totalWorldTime", w.getTotalWorldTime());
        try {
            if (w.provider != null) {
                out.put("moonPhase", w.provider.getMoonPhase(wt));
            }
        } catch (Throwable ignored) {}
        String weather = w.isThundering() ? "thunder"
                       : w.isRaining()    ? "rain"
                       :                    "clear";
        out.put("weather", weather);
        out.put("isRaining", w.isRaining());
        out.put("isThundering", w.isThundering());
        try { out.put("rainStrength",    w.getRainStrength(1f));    } catch (Throwable t) {}
        try { out.put("thunderStrength", w.getThunderStrength(1f)); } catch (Throwable t) {}

        BlockPos feet = new BlockPos(p);
        try { out.put("lightAtFeet", w.getLight(feet)); } catch (Throwable t) {}
        try {
            Chunk chunk = w.getChunkFromBlockCoords(feet);
            if (chunk != null) {
                Map<String, Object> light = new LinkedHashMap<>();
                light.put("block", chunk.getLightFor(EnumSkyBlock.BLOCK, feet));
                light.put("sky",   chunk.getLightFor(EnumSkyBlock.SKY,   feet));
                out.put("lightComponents", light);
            }
        } catch (Throwable ignored) {}
        try {
            Biome biome = w.getBiome(feet);
            if (biome != null) {
                out.put("biome", biome.getRegistryName() == null ? null
                        : biome.getRegistryName().toString());
                out.put("temperature", biome.getDefaultTemperature());
                out.put("humidity",    biome.getRainfall());
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /* ===================== augmenters ===================== */

    /**
     * Called from {@code DebugHttpServer.snapshotPlayer()}'s assembled
     * map — adds weather / time / light / biome keys to an existing
     * {@code /player} payload. No-op if the player isn't in a world.
     */
    public static void augmentPlayer(Map<String, Object> out) {
        if (out == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = mc.world;
        EntityPlayerSP p = mc.player;
        if (w == null || p == null) return;
        try {
            long wt = w.getWorldTime();
            out.put("timeOfDay", ((wt % 24000L) + 24000L) % 24000L);
            try {
                if (w.provider != null) out.put("moonPhase", w.provider.getMoonPhase(wt));
            } catch (Throwable ignored) {}
            out.put("weather", w.isThundering() ? "thunder"
                             : w.isRaining()    ? "rain"
                             :                    "clear");
            BlockPos feet = new BlockPos(p);
            try { out.put("lightAtFeet", w.getLight(feet)); } catch (Throwable ignored) {}
            try {
                Biome biome = w.getBiome(feet);
                if (biome != null && biome.getRegistryName() != null) {
                    out.put("biome", biome.getRegistryName().toString());
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /**
     * Called from {@code DebugHttpServer.snapshotLook()}; for a block
     * hit adds {@code blockState.properties}, {@code tileEntity.nbt}
     * (truncated SNBT), {@code light.block}/{@code light.sky}, and the
     * chunk biome. Silent no-op for non-block hits / missing world.
     */
    public static void augmentLook(RayTraceResult hit, Map<String, Object> out) {
        if (hit == null || out == null) return;
        if (hit.typeOfHit != RayTraceResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();
        if (pos == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = mc.world;
        if (w == null) return;

        // Block state properties.
        try {
            IBlockState state = w.getBlockState(pos);
            if (state != null) {
                Map<String, Object> props = new LinkedHashMap<>();
                ImmutableMap<IProperty<?>, Comparable<?>> map = state.getProperties();
                if (map != null) {
                    for (Map.Entry<IProperty<?>, Comparable<?>> e : map.entrySet()) {
                        IProperty<?> k = e.getKey();
                        if (k == null) continue;
                        Comparable<?> v = e.getValue();
                        props.put(k.getName(), v == null ? null : String.valueOf(v));
                    }
                }
                Map<String, Object> bs = new LinkedHashMap<>();
                bs.put("properties", props);
                out.put("blockState", bs);
            }
        } catch (Throwable ignored) {}

        // TileEntity SNBT + class name (truncated).
        try {
            TileEntity te = w.getTileEntity(pos);
            if (te != null) {
                Map<String, Object> teJson = new LinkedHashMap<>();
                teJson.put("className", te.getClass().getName());
                try {
                    NBTTagCompound nbt = te.writeToNBT(new NBTTagCompound());
                    teJson.put("nbt", truncateNbt(String.valueOf(nbt)));
                } catch (Throwable t) {
                    teJson.put("nbt", null);
                    teJson.put("nbtError",
                            t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                out.put("tileEntity", teJson);
            }
        } catch (Throwable ignored) {}

        // Light components of the looked-at chunk.
        try {
            Chunk chunk = w.getChunkFromBlockCoords(pos);
            if (chunk != null) {
                Map<String, Object> light = new LinkedHashMap<>();
                light.put("block", chunk.getLightFor(EnumSkyBlock.BLOCK, pos));
                light.put("sky",   chunk.getLightFor(EnumSkyBlock.SKY,   pos));
                out.put("light", light);
            }
        } catch (Throwable ignored) {}

        // Biome at the looked-at block.
        try {
            Biome biome = w.getBiome(pos);
            if (biome != null && biome.getRegistryName() != null) {
                out.put("biome", biome.getRegistryName().toString());
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Called from {@code DebugHttpServer.snapshotAll()}; adds
     * {@code overlay}, {@code bossbars}, and {@code scoreboard} blocks
     * to an aggregate /state map. Each sub-snapshot is isolated in a
     * try/catch so one failure does not tank the aggregate.
     */
    public static void augmentState(Map<String, Object> out) {
        if (out == null) return;
        try { out.put("overlay",    overlay(emptyQuery()));    }
        catch (Throwable t) { out.put("overlay",    errorBlock(t)); }
        try { out.put("bossbars",   bossbars(emptyQuery()));   }
        catch (Throwable t) { out.put("bossbars",   errorBlock(t)); }
        try { out.put("scoreboard", scoreboard(emptyQuery())); }
        catch (Throwable t) { out.put("scoreboard", errorBlock(t)); }
    }

    /* ===================== helpers ===================== */

    private static Map<String, String> emptyQuery() { return new HashMap<>(); }

    private static Map<String, Object> errorBlock(Throwable t) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", t.getClass().getSimpleName());
        err.put("message", String.valueOf(t.getMessage()));
        return err;
    }

    private static final int MAX_CHAT_LEN = 2000;
    private static final int MAX_NBT_LEN  = 4000;

    private static String truncateChat(String s) {
        return truncate(s, MAX_CHAT_LEN);
    }

    private static String truncateNbt(String s) {
        return truncate(s, MAX_NBT_LEN);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\u2026(truncated)";
    }

    /** JSON-serialize an ITextComponent, swallowing any failure. */
    private static String safeComponentToJson(ITextComponent comp) {
        if (comp == null) return null;
        try { return ITextComponent.Serializer.componentToJson(comp); }
        catch (Throwable t) { return null; }
    }

    /* ----- Reflection (SRG-first, MCP-fallback). Walks the class chain. */

    private static Field findField(Class<?> c, String... names) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (String n : names) {
                try {
                    Field f = k.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // try next name / superclass
                }
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String[] names, Class<?>... paramTypes) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (String n : names) {
                try {
                    Method m = k.getDeclaredMethod(n, paramTypes);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                    // try next name / superclass
                }
            }
        }
        return null;
    }

    /** Read a typed field (reference types). Returns null on any failure. */
    @SuppressWarnings("unchecked")
    private static <T> T readField(Object target, String... names) {
        if (target == null) return null;
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try { return (T) f.get(target); }
        catch (Throwable t) { return null; }
    }

    /** Erased-type read (Object). Identical to readField but avoids the cast. */
    private static Object readFieldAny(Object target, String... names) {
        if (target == null) return null;
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try { return f.get(target); }
        catch (Throwable t) { return null; }
    }

    /** Primitive int field reader; returns null on missing / wrong type. */
    private static Integer readIntField(Object target, String... names) {
        if (target == null) return null;
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try { return f.getInt(target); }
        catch (Throwable t) {
            try {
                Object v = f.get(target);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {}
            return null;
        }
    }
}
