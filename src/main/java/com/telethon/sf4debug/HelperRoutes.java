package com.telethon.sf4debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.client.gui.GuiRepair;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerRepair;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketHeldItemChange;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Composite convenience endpoints — each of these saves an external bot
 * from sequencing 3-5 primitive calls for a common intent.
 *
 * <p>Most routes run on the Minecraft client thread via
 * {@link DebugHttpServer#wrap}. The two exceptions are {@code /wait}
 * (which must NOT block the client thread — that would freeze the game)
 * and {@code /eatUntilFull} (which needs longer than the 2-second
 * client-thread timeout). Those two install their own {@link HttpHandler}.
 *
 * <p>Per integration contract, this class does not import from
 * {@code ActionRoutes} / {@code GuiRoutes}. The small reflection helpers
 * and the few packet-sending snippets we need are duplicated locally;
 * this avoids cross-agent merge collisions.
 */
public final class HelperRoutes {

    private static final Gson GSON        = new GsonBuilder().serializeNulls().create();
    private static final Gson GSON_PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private HelperRoutes() {}

    public static void register(HttpServer server) {
        server.createContext("/moveItem",      DebugHttpServer.wrap(HelperRoutes::moveItem));
        server.createContext("/findItem",      DebugHttpServer.wrap(HelperRoutes::findItem));
        server.createContext("/placeItem",     DebugHttpServer.wrap(HelperRoutes::placeItem));
        server.createContext("/dropSlot",      DebugHttpServer.wrap(HelperRoutes::dropSlot));
        server.createContext("/signSet",       DebugHttpServer.wrap(HelperRoutes::signSet));
        server.createContext("/anvilRename",   DebugHttpServer.wrap(HelperRoutes::anvilRename));
        // Custom handlers — these must NOT marshal the HTTP thread onto
        // the Minecraft client thread for the duration of the wait.
        server.createContext("/wait",          new WaitHandler());
        server.createContext("/eatUntilFull",  new EatUntilFullHandler());
    }

    /* ========================= /moveItem ========================= */

    /**
     * {@code /moveItem?from=<slotId>&to=<slotId>&count=<n>&shift=0}
     *
     * <p>Moves items between two container slots in the currently-open
     * container. {@code shift=1} issues one {@code QUICK_MOVE} click on
     * the source slot (same as shift-clicking in vanilla). Otherwise the
     * default path is pickup-source then pickup-target; if
     * {@code count > 0 && count < sourceStackSize} the deposit becomes
     * {@code count} right-clicks on target, then a final left-click
     * back on the source to restore the leftover.
     */
    public static Object moveItem(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        PlayerControllerMP pc = mc.playerController;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || pc == null) { out.put("state", "not_in_world"); return out; }
        Container c = p.openContainer;
        if (c == null) { out.put("error", "no open container"); return out; }

        Integer from = DebugHttpServer.parseInt(q.get("from"), null);
        Integer to   = DebugHttpServer.parseInt(q.get("to"),   null);
        if (from == null || to == null) {
            out.put("error", "missing from/to"); return out;
        }
        if (from < 0 || from >= c.inventorySlots.size()
                || to < 0 || to >= c.inventorySlots.size()) {
            out.put("error", "from/to out of range (container has "
                    + c.inventorySlots.size() + " slots)");
            return out;
        }
        int count  = DebugHttpServer.parseInt(q.get("count"), 0);
        boolean shift = truthy(q.get("shift"));

        ItemStack beforeFrom = c.getSlot(from).getStack().copy();
        ItemStack beforeTo   = c.getSlot(to).getStack().copy();

        if (shift) {
            pc.windowClick(c.windowId, from, 0, ClickType.QUICK_MOVE, p);
        } else {
            int srcCount = beforeFrom.isEmpty() ? 0 : beforeFrom.getCount();
            if (count > 0 && count < srcCount) {
                // Partial move: pickup all, right-click target N times,
                // return remainder to source.
                pc.windowClick(c.windowId, from, 0, ClickType.PICKUP, p);
                for (int i = 0; i < count; i++) {
                    pc.windowClick(c.windowId, to, 1, ClickType.PICKUP, p);
                }
                pc.windowClick(c.windowId, from, 0, ClickType.PICKUP, p);
            } else {
                // Full move: pickup source, drop onto target.
                pc.windowClick(c.windowId, from, 0, ClickType.PICKUP, p);
                pc.windowClick(c.windowId, to,   0, ClickType.PICKUP, p);
            }
        }

        Map<String, Object> fromInfo = new LinkedHashMap<>();
        fromInfo.put("slot", from);
        fromInfo.put("before", DebugHttpServer.itemStackJson(beforeFrom));
        fromInfo.put("after",  DebugHttpServer.itemStackJson(c.getSlot(from).getStack()));
        Map<String, Object> toInfo = new LinkedHashMap<>();
        toInfo.put("slot", to);
        toInfo.put("before", DebugHttpServer.itemStackJson(beforeTo));
        toInfo.put("after",  DebugHttpServer.itemStackJson(c.getSlot(to).getStack()));

        out.put("ok", true);
        out.put("windowId", c.windowId);
        out.put("shift", shift);
        out.put("count", count);
        out.put("from", fromInfo);
        out.put("to",   toInfo);
        out.put("heldOnMouse", DebugHttpServer.itemStackJson(p.inventory.getItemStack()));
        return out;
    }

    /* ========================= /findItem ========================= */

    /**
     * {@code /findItem?name=<registryName>&nbt=<substr>&where=inv|container|both}
     *
     * <p>Substring match on {@code item.getRegistryName().toString()}
     * (case-insensitive). If {@code nbt} is also given, the item's NBT
     * (via {@code toString()}) must contain that substring as well. The
     * {@code where} filter defaults to {@code both}. Indexing convention:
     * {@code inv} uses the vanilla {@link InventoryPlayer#getStackInSlot}
     * layout (0..35 main incl. 0..8 hotbar, 36..39 armor, 40 offhand);
     * {@code container} uses the raw {@link Container#inventorySlots}
     * indices.
     */
    public static Object findItem(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("state", "not_in_world"); return out; }

        String name = q.get("name");
        if (name == null || name.isEmpty()) {
            out.put("error", "missing name"); return out;
        }
        String nameLc = name.toLowerCase();
        String nbt = q.get("nbt");
        String nbtLc = nbt == null || nbt.isEmpty() ? null : nbt.toLowerCase();
        String where = q.getOrDefault("where", "both").toLowerCase();
        boolean scanInv = "inv".equals(where) || "both".equals(where);
        boolean scanCon = "container".equals(where) || "both".equals(where);

        List<Map<String, Object>> matches = new ArrayList<>();
        if (scanInv) {
            InventoryPlayer inv = p.inventory;
            // 0..35 main (0..8 hotbar)
            for (int i = 0; i < inv.mainInventory.size(); i++) {
                addIfMatch(matches, "inv", i, -1, inv.mainInventory.get(i), nameLc, nbtLc);
            }
            // 36..39 armor
            int base = inv.mainInventory.size();
            for (int i = 0; i < inv.armorInventory.size(); i++) {
                addIfMatch(matches, "inv", base + i, -1, inv.armorInventory.get(i), nameLc, nbtLc);
            }
            // 40 offhand
            base += inv.armorInventory.size();
            for (int i = 0; i < inv.offHandInventory.size(); i++) {
                addIfMatch(matches, "inv", base + i, -1, inv.offHandInventory.get(i), nameLc, nbtLc);
            }
        }
        if (scanCon) {
            Container c = p.openContainer;
            if (c != null) {
                for (int i = 0; i < c.inventorySlots.size(); i++) {
                    Slot s = c.inventorySlots.get(i);
                    ItemStack st = s == null ? ItemStack.EMPTY : s.getStack();
                    addIfMatch(matches, "container", i, s == null ? -1 : s.slotNumber,
                               st, nameLc, nbtLc);
                }
            }
        }

        out.put("name", name);
        if (nbt != null) out.put("nbt", nbt);
        out.put("where", where);
        out.put("count", matches.size());
        out.put("matches", matches);
        return out;
    }

    private static void addIfMatch(List<Map<String, Object>> matches,
                                   String source, int index, int windowSlot,
                                   ItemStack stack, String nameLc, String nbtLc) {
        if (stack == null || stack.isEmpty()) return;
        Item item = stack.getItem();
        ResourceLocation rn = item == null ? null : item.getRegistryName();
        String reg = rn == null ? "" : rn.toString().toLowerCase();
        if (!reg.contains(nameLc)) return;
        if (nbtLc != null) {
            if (!stack.hasTagCompound()) return;
            String snbt = String.valueOf(stack.getTagCompound()).toLowerCase();
            if (!snbt.contains(nbtLc)) return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("index", index);
        if (windowSlot >= 0) m.put("slot", windowSlot);
        m.put("item", DebugHttpServer.itemStackJson(stack));
        matches.add(m);
    }

    /* ========================= /placeItem ========================= */

    /**
     * {@code /placeItem?slot=<0..8>&x=&y=&z=&side=up&restore=1}
     *
     * <p>Temporarily selects hotbar slot {@code slot}, issues a
     * {@code processRightClickBlock} at the given coordinate + face, and
     * (by default) restores the previously-selected hotbar slot.
     */
    public static Object placeItem(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        WorldClient w = mc.world;
        PlayerControllerMP pc = mc.playerController;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || w == null || pc == null || p.connection == null) {
            out.put("state", "not_in_world"); return out;
        }
        Integer slot = DebugHttpServer.parseInt(q.get("slot"), null);
        if (slot == null || slot < 0 || slot > 8) {
            out.put("error", "slot must be 0..8"); return out;
        }
        Integer x = DebugHttpServer.parseInt(q.get("x"), null);
        Integer y = DebugHttpServer.parseInt(q.get("y"), null);
        Integer z = DebugHttpServer.parseInt(q.get("z"), null);
        if (x == null || y == null || z == null) {
            out.put("error", "missing x/y/z"); return out;
        }
        EnumFacing side = parseFacing(q.get("side"), EnumFacing.UP);
        boolean restore = !"0".equals(q.getOrDefault("restore", "1"));

        int prevSlot = p.inventory.currentItem;
        if (slot != prevSlot) {
            p.inventory.currentItem = slot;
            p.connection.sendPacket(new CPacketHeldItemChange(slot));
        }

        BlockPos bp = new BlockPos(x, y, z);
        // Hit vector at the centre of the clicked face.
        Vec3d hit = new Vec3d(
                x + 0.5 + side.getFrontOffsetX() * 0.5,
                y + 0.5 + side.getFrontOffsetY() * 0.5,
                z + 0.5 + side.getFrontOffsetZ() * 0.5);
        EnumActionResult r = pc.processRightClickBlock(p, w, bp, side, hit, EnumHand.MAIN_HAND);

        if (restore && prevSlot != slot) {
            p.inventory.currentItem = prevSlot;
            p.connection.sendPacket(new CPacketHeldItemChange(prevSlot));
        }

        out.put("placed", r == EnumActionResult.SUCCESS);
        out.put("result", r == null ? null : r.name());
        out.put("prevSlot", prevSlot);
        out.put("slot", slot);
        out.put("pos", Arrays.asList(x, y, z));
        out.put("side", side.name());
        out.put("restored", restore && prevSlot != slot);
        return out;
    }

    /* ========================= /dropSlot ========================= */

    /**
     * {@code /dropSlot?slot=<0..8>&full=0&restore=1}
     *
     * <p>Temporarily selects hotbar slot {@code slot}, drops the held
     * stack (single item unless {@code full=1}), then (by default)
     * restores the previous hotbar slot. Uses {@code player.dropItem}
     * — the same code path as the D key in vanilla.
     */
    public static Object dropSlot(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || p.connection == null) {
            out.put("state", "not_in_world"); return out;
        }
        Integer slot = DebugHttpServer.parseInt(q.get("slot"), null);
        if (slot == null || slot < 0 || slot > 8) {
            out.put("error", "slot must be 0..8"); return out;
        }
        boolean full = truthy(q.get("full"));
        boolean restore = !"0".equals(q.getOrDefault("restore", "1"));

        int prevSlot = p.inventory.currentItem;
        if (slot != prevSlot) {
            p.inventory.currentItem = slot;
            p.connection.sendPacket(new CPacketHeldItemChange(slot));
        }

        ItemStack before = p.getHeldItemMainhand().copy();
        EntityItem ei = p.dropItem(full);

        if (restore && prevSlot != slot) {
            p.inventory.currentItem = prevSlot;
            p.connection.sendPacket(new CPacketHeldItemChange(prevSlot));
        }

        out.put("ok", true);
        out.put("full", full);
        out.put("prevSlot", prevSlot);
        out.put("slot", slot);
        out.put("before", DebugHttpServer.itemStackJson(before));
        out.put("entityItemId", ei == null ? null : ei.getEntityId());
        out.put("restored", restore && prevSlot != slot);
        return out;
    }

    /* ========================= /signSet ========================= */

    /**
     * {@code /signSet?line0=&line1=&line2=&line3=&confirm=1}
     *
     * <p>Requires {@code mc.currentScreen instanceof GuiEditSign}. Sets
     * any supplied line on the underlying {@code TileEntitySign#signText}
     * (it's a {@code public final ITextComponent[4]}, so individual
     * elements can be written without reflecting the whole field). If
     * {@code confirm=1} (default), the screen is closed via
     * {@code mc.displayGuiScreen(null)} which triggers
     * {@code GuiEditSign#onGuiClosed()} and sends {@code CPacketUpdateSign}.
     */
    public static Object signSet(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(screen instanceof GuiEditSign)) {
            out.put("error", "not a GuiEditSign");
            out.put("screen", screen == null ? null : screen.getClass().getName());
            return out;
        }
        GuiEditSign edit = (GuiEditSign) screen;

        // tileSign is private final — reflection required (SRG first).
        Field tileSignF = findField(GuiEditSign.class,
                new String[] { "field_146848_f", "tileSign" });
        if (tileSignF == null) {
            out.put("error", "GuiEditSign.tileSign field not found");
            return out;
        }
        TileEntitySign te;
        try {
            te = (TileEntitySign) tileSignF.get(edit);
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            return out;
        }
        if (te == null) { out.put("error", "tileSign is null"); return out; }

        ITextComponent[] signText = te.signText;
        if (signText == null || signText.length < 4) {
            out.put("error", "unexpected signText length");
            return out;
        }
        boolean confirm = !"0".equals(q.getOrDefault("confirm", "1"));
        List<String> applied = new ArrayList<>(4);
        List<String> linesOut = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            String v = q.get("line" + i);
            if (v != null) {
                signText[i] = new TextComponentString(v);
                applied.add("line" + i);
            }
            linesOut.add(signText[i] == null ? "" : signText[i].getUnformattedText());
        }

        if (confirm) {
            // Closing triggers GuiEditSign.onGuiClosed → CPacketUpdateSign.
            mc.displayGuiScreen(null);
        }

        out.put("signSet", true);
        out.put("applied", applied);
        out.put("lines", linesOut);
        out.put("confirmed", confirm);
        return out;
    }

    /* ========================= /anvilRename ========================= */

    /**
     * {@code /anvilRename?name=...}
     *
     * <p>Requires {@code mc.currentScreen instanceof GuiRepair}. Pushes
     * {@code name} into the anvil's {@code nameField}; the vanilla
     * {@code GuiTextField} listener forwards it to
     * {@code ContainerRepair.updateItemName(name)} which in turn sends
     * {@code CPacketRenameItem}. We also call {@code updateItemName}
     * directly as a belt-and-braces fallback.
     */
    public static Object anvilRename(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(screen instanceof GuiRepair)) {
            out.put("error", "not a GuiRepair");
            out.put("screen", screen == null ? null : screen.getClass().getName());
            return out;
        }
        String name = q.get("name");
        if (name == null) { out.put("error", "missing name"); return out; }

        GuiRepair gui = (GuiRepair) screen;
        // nameField is private.
        Field nameFieldF = findField(GuiRepair.class,
                new String[] { "field_147091_w", "nameField" });
        GuiTextField tf = null;
        if (nameFieldF != null) {
            try { tf = (GuiTextField) nameFieldF.get(gui); } catch (Throwable ignored) {}
        }
        boolean setViaField = false;
        if (tf != null) {
            tf.setText(name);
            setViaField = true;
        }

        // Fallback / belt-and-braces: call the container's updateItemName
        // directly. ContainerRepair is a public class, no reflection
        // needed here — but keep SRG-tolerant in case of further remaps.
        boolean containerCalled = false;
        try {
            if (mc.player != null && mc.player.openContainer instanceof ContainerRepair) {
                ((ContainerRepair) mc.player.openContainer).updateItemName(name);
                containerCalled = true;
            } else {
                // Try SRG-first via reflection in case a forge patch renamed it.
                Container c = mc.player == null ? null : mc.player.openContainer;
                if (c != null) {
                    Method m = findMethod(c.getClass(),
                            new String[] { "func_82850_a", "updateItemName" }, String.class);
                    if (m != null) {
                        m.invoke(c, name);
                        containerCalled = true;
                    }
                }
            }
        } catch (Throwable t) {
            out.put("containerError", t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        out.put("renamed", setViaField || containerCalled);
        out.put("name", name);
        out.put("viaField", setViaField);
        out.put("viaContainer", containerCalled);
        return out;
    }

    /* ========================= /wait ========================= */

    /**
     * {@code /wait?ticks=<n>&maxMs=<n>}
     *
     * <p>Blocks the HTTP handler thread — NOT the client thread — for
     * up to {@code ticks} client ticks (50 ms each) or {@code maxMs}
     * wall-clock milliseconds, whichever comes first. At least one of
     * the two must be set. Caps: 200 ticks / 15000 ms.
     */
    private static final class WaitHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI());
                boolean pretty = query.containsKey("pretty");

                Integer ticks = DebugHttpServer.parseInt(query.get("ticks"), null);
                Integer maxMs = DebugHttpServer.parseInt(query.get("maxMs"), null);
                if (ticks == null && maxMs == null) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", "missing ticks or maxMs");
                    sendJson(exchange, 400, pretty, err);
                    return;
                }
                int tClamped = ticks == null ? 0 : Math.max(0, Math.min(ticks, 200));
                int msClamped = maxMs == null ? 0 : Math.max(0, Math.min(maxMs, 15000));

                long targetMs;
                if (tClamped > 0 && msClamped > 0) targetMs = Math.min(tClamped * 50L, msClamped);
                else if (tClamped > 0)             targetMs = tClamped * 50L;
                else                               targetMs = msClamped;

                long start = System.nanoTime();
                if (targetMs > 0) {
                    try {
                        Thread.sleep(targetMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                long actualMs = (System.nanoTime() - start) / 1_000_000L;
                long actualTicks = actualMs / 50L;

                Map<String, Object> waited = new LinkedHashMap<>();
                waited.put("ticks", actualTicks);
                waited.put("ms", actualMs);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("waited", waited);
                out.put("requestedTicks", tClamped);
                out.put("requestedMs", msClamped);
                sendJson(exchange, 200, pretty, out);
            } catch (Throwable t) {
                SF4Debug.LOG.warn("sf4debug /wait failed", t);
                try { sendErrorJson(exchange, t); } catch (IOException ignored) {}
            } finally {
                exchange.close();
            }
        }
    }

    /* ========================= /eatUntilFull ========================= */

    /**
     * {@code /eatUntilFull?slot=<0..8>&maxTicks=200}
     *
     * <p>Selects a food slot and holds the use-item key until
     * {@code foodLevel >= 20} or {@code maxTicks} ticks elapse. Works
     * by installing a temporary {@link TickEvent.ClientTickEvent}
     * subscriber that re-asserts {@code keyBindUseItem} each tick; the
     * subscriber unregisters itself and completes the future when done.
     * Runs off the client thread — the HTTP handler uses a custom
     * handler because the worst-case wait of {@code maxTicks * 50 ms}
     * would exceed {@link DebugHttpServer#wrap}'s 2-second client-thread
     * timeout.
     */
    private static final class EatUntilFullHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI());
                boolean pretty = query.containsKey("pretty");

                Integer slot = DebugHttpServer.parseInt(query.get("slot"), null);
                if (slot == null || slot < 0 || slot > 8) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", "slot must be 0..8");
                    sendJson(exchange, 400, pretty, err);
                    return;
                }
                int maxTicks = DebugHttpServer.parseInt(query.get("maxTicks"), 200);
                maxTicks = Math.max(1, Math.min(maxTicks, 600)); // hard cap 30s

                // Run everything that touches mc.player on the client thread.
                final int slotF = slot;
                final int maxTicksF = maxTicks;
                CompletableFuture<Map<String, Object>> done = new CompletableFuture<>();

                try {
                    DebugHttpServer.runOnClientThread(() -> {
                        Minecraft mc = Minecraft.getMinecraft();
                        EntityPlayerSP p = mc.player;
                        if (p == null || p.connection == null) {
                            Map<String, Object> err = new LinkedHashMap<>();
                            err.put("error", "not_in_world");
                            done.complete(err);
                            return null;
                        }
                        GameSettings gs = mc.gameSettings;
                        KeyBinding useKb = gs.keyBindUseItem;
                        int prevSlot = p.inventory.currentItem;
                        if (slotF != prevSlot) {
                            p.inventory.currentItem = slotF;
                            p.connection.sendPacket(new CPacketHeldItemChange(slotF));
                        }
                        int initialFood = p.getFoodStats().getFoodLevel();

                        EatSubscriber sub = new EatSubscriber(useKb, maxTicksF,
                                initialFood, prevSlot, slotF, done);
                        MinecraftForge.EVENT_BUS.register(sub);
                        return null;
                    });
                } catch (Throwable t) {
                    SF4Debug.LOG.warn("sf4debug /eatUntilFull setup failed", t);
                    sendErrorJson(exchange, t);
                    return;
                }

                // Wait off-thread. maxTicks * 50ms + 2s safety buffer.
                long waitMs = maxTicks * 50L + 2000L;
                Map<String, Object> result;
                try {
                    result = done.get(waitMs, TimeUnit.MILLISECONDS);
                } catch (Throwable t) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", t.getClass().getSimpleName());
                    err.put("message", String.valueOf(t.getMessage()));
                    sendJson(exchange, 500, pretty, err);
                    return;
                }
                sendJson(exchange, 200, pretty, result);
            } catch (Throwable t) {
                SF4Debug.LOG.warn("sf4debug /eatUntilFull failed", t);
                try { sendErrorJson(exchange, t); } catch (IOException ignored) {}
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * Per-request tick subscriber that re-asserts the use-item key each
     * tick until {@code foodLevel>=20} or the max budget is spent, then
     * unregisters itself and completes the caller's future. Lives
     * entirely on the client thread (that's where ClientTickEvent fires).
     */
    private static final class EatSubscriber {
        private final KeyBinding useKb;
        private final int maxTicks;
        private final int initialFood;
        private final int prevSlot;
        private final int slot;
        private final CompletableFuture<Map<String, Object>> done;
        private int ticksUsed = 0;
        private boolean finished = false;

        EatSubscriber(KeyBinding useKb, int maxTicks, int initialFood,
                      int prevSlot, int slot,
                      CompletableFuture<Map<String, Object>> done) {
            this.useKb = useKb;
            this.maxTicks = maxTicks;
            this.initialFood = initialFood;
            this.prevSlot = prevSlot;
            this.slot = slot;
            this.done = done;
        }

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent ev) {
            if (ev.phase != TickEvent.Phase.START) return;
            if (finished) return;

            Minecraft mc = Minecraft.getMinecraft();
            EntityPlayerSP p = mc.player;
            if (p == null) {
                finish("not_in_world", 0);
                return;
            }
            int food = p.getFoodStats().getFoodLevel();
            if (food >= 20) {
                finish("full", food);
                return;
            }
            if (ticksUsed >= maxTicks) {
                finish("timeout", food);
                return;
            }

            // Keep the slot selected in case something else changed it.
            if (p.inventory.currentItem != slot && p.connection != null) {
                p.inventory.currentItem = slot;
                p.connection.sendPacket(new CPacketHeldItemChange(slot));
            }

            try { KeyBinding.setKeyBindState(useKb.getKeyCode(), true); }
            catch (Throwable ignored) {}
            ticksUsed++;
        }

        private void finish(String reason, int finalFoodMaybe) {
            finished = true;
            try { KeyBinding.setKeyBindState(useKb.getKeyCode(), false); }
            catch (Throwable ignored) {}
            try { MinecraftForge.EVENT_BUS.unregister(this); } catch (Throwable ignored) {}

            Minecraft mc = Minecraft.getMinecraft();
            EntityPlayerSP p = mc.player;
            int finalFood;
            if (p != null) {
                finalFood = p.getFoodStats().getFoodLevel();
                // Restore previous hotbar slot.
                if (p.connection != null && p.inventory.currentItem != prevSlot) {
                    p.inventory.currentItem = prevSlot;
                    p.connection.sendPacket(new CPacketHeldItemChange(prevSlot));
                }
            } else {
                finalFood = finalFoodMaybe;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("initialFood", initialFood);
            out.put("finalFood", finalFood);
            out.put("ticksUsed", ticksUsed);
            out.put("reason", reason);
            out.put("slot", slot);
            out.put("prevSlot", prevSlot);
            done.complete(out);
        }
    }

    /* ========================= local helpers ========================= */

    // Reflection helpers — same SRG-first / MCP-fallback pattern as
    // GuiRoutes#findField / findMethod. Duplicated here so this class
    // does not depend on GuiRoutes (per integration contract).

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

    /* -------- HTTP/JSON plumbing for the two custom-handler routes -------- */

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return out;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) out.put(urlDecode(kv), "");
            else        out.put(urlDecode(kv.substring(0, eq)),
                                urlDecode(kv.substring(eq + 1)));
        }
        return out;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static void sendJson(HttpExchange exchange, int status, boolean pretty, Object payload)
            throws IOException {
        byte[] bytes = (pretty ? GSON_PRETTY : GSON).toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendErrorJson(HttpExchange exchange, Throwable t) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", t.getClass().getSimpleName());
        err.put("message", String.valueOf(t.getMessage()));
        byte[] bytes = GSON.toJson(err).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(500, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
