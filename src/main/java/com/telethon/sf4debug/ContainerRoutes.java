package com.telethon.sf4debug;

import com.sun.net.httpserver.HttpServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerBeacon;
import net.minecraft.inventory.ContainerBrewingStand;
import net.minecraft.inventory.ContainerEnchantment;
import net.minecraft.inventory.ContainerFurnace;
import net.minecraft.inventory.ContainerMerchant;
import net.minecraft.inventory.ContainerRepair;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Group 6 — typed containers and high-value macros.
 *
 * <p>Every route reads or writes through the open {@code Container}
 * so the information returned is exactly what the real player sees on
 * screen. All reads/writes hop onto the client thread via
 * {@link DebugHttpServer#runOnClientThread}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code /furnace} — input/fuel/output + burn/cook timers.</li>
 *   <li>{@code /brewing} — 3 potion slots + ingredient + fuel + brew
 *       timer.</li>
 *   <li>{@code /enchant} — 3 offered enchant levels, clue enchant
 *       ids, clue enchantment levels, and current xpSeed.</li>
 *   <li>{@code /anvil} — level cost, material cost, current rename
 *       string, and output slot.</li>
 *   <li>{@code /merchant} — villager trade list and currently
 *       selected recipe index.</li>
 *   <li>{@code /beacon} — beacon levels + primary/secondary effect
 *       selection.</li>
 *   <li>{@code /book.write?pages=&sign=&title=} — overwrite a
 *       book-and-quill's pages and optionally sign.</li>
 *   <li>{@code /creativeTab?tab=N} — switch creative inventory tab
 *       (requires {@link GuiContainerCreative}).</li>
 *   <li>{@code /clipboard?op=get|set&text=} — system clipboard.</li>
 *   <li>{@code /fishing.state} — bobber position / caught entity.</li>
 * </ul>
 */
public final class ContainerRoutes {

    private ContainerRoutes() {}

    public static void register(HttpServer server) {
        server.createContext("/furnace", DebugHttpServer.wrap(ContainerRoutes::furnace));
        server.createContext("/brewing", DebugHttpServer.wrap(ContainerRoutes::brewing));
        server.createContext("/enchant", DebugHttpServer.wrap(ContainerRoutes::enchant));
        server.createContext("/anvil", DebugHttpServer.wrap(ContainerRoutes::anvil));
        server.createContext("/merchant", DebugHttpServer.wrap(ContainerRoutes::merchant));
        server.createContext("/beacon", DebugHttpServer.wrap(ContainerRoutes::beacon));
        server.createContext("/book.write", DebugHttpServer.wrap(ContainerRoutes::bookWrite));
        server.createContext("/creativeTab", DebugHttpServer.wrap(ContainerRoutes::creativeTab));
        server.createContext("/clipboard", DebugHttpServer.wrap(ContainerRoutes::clipboard));
        server.createContext("/fishing.state", DebugHttpServer.wrap(ContainerRoutes::fishingState));
    }

    // ---------- /furnace --------------------------------------------------

    private static Map<String, Object> furnace(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) { out.put("error", "no player"); return out; }
        Container c = p.openContainer;
        if (!(c instanceof ContainerFurnace)) {
            out.put("error", "no furnace open");
            out.put("containerClass", c == null ? null : c.getClass().getName());
            return out;
        }
        ContainerFurnace cf = (ContainerFurnace) c;
        out.put("windowId", cf.windowId);
        IInventory tile = (IInventory) readField(cf, ContainerFurnace.class,
                new String[] { "field_178152_f", "tileFurnace" });
        out.put("input", itemInfo(cf.getSlot(0)));
        out.put("fuel", itemInfo(cf.getSlot(1)));
        out.put("output", itemInfo(cf.getSlot(2)));
        int burnTime      = safeField(cf, "field_178153_h", "furnaceBurnTime", 0);
        int currentBurn   = safeField(cf, "field_178151_i", "currentItemBurnTime", 0);
        int cookTime      = safeField(cf, "field_178150_g", "cookTime", 0);
        int totalCookTime = safeField(cf, "field_178149_j", "totalCookTime", 0);
        // Fall back to IInventory.getField() if Container mirror values
        // are stale (happens during the first tick after opening):
        if (tile != null) {
            if (burnTime == 0 && currentBurn == 0 && cookTime == 0) {
                try {
                    burnTime      = tile.getField(0);
                    currentBurn   = tile.getField(1);
                    cookTime      = tile.getField(2);
                    totalCookTime = tile.getField(3);
                } catch (Throwable ignored) {}
            }
        }
        out.put("furnaceBurnTime", burnTime);
        out.put("currentItemBurnTime", currentBurn);
        out.put("cookTime", cookTime);
        out.put("totalCookTime", totalCookTime);
        out.put("burnProgress", currentBurn <= 0 ? 0.0 : (burnTime / (double) currentBurn));
        out.put("cookProgress", totalCookTime <= 0 ? 0.0 : (cookTime / (double) totalCookTime));
        out.put("isBurning", burnTime > 0);
        return out;
    }

    // ---------- /brewing --------------------------------------------------

    private static Map<String, Object> brewing(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) { out.put("error", "no player"); return out; }
        Container c = p.openContainer;
        if (!(c instanceof ContainerBrewingStand)) {
            out.put("error", "no brewing stand open");
            out.put("containerClass", c == null ? null : c.getClass().getName());
            return out;
        }
        ContainerBrewingStand cb = (ContainerBrewingStand) c;
        out.put("windowId", cb.windowId);
        // Slot layout in 1.12.2 ContainerBrewingStand:
        // 0-2: potion bottles (output), 3: ingredient, 4: blaze powder fuel
        List<Map<String, Object>> bottles = new ArrayList<>();
        for (int i = 0; i < 3; i++) bottles.add(itemInfo(cb.getSlot(i)));
        out.put("bottles", bottles);
        out.put("ingredient", itemInfo(cb.getSlot(3)));
        out.put("fuel", itemInfo(cb.getSlot(4)));
        int brewTime = 0;
        int fuel = 0;
        IInventory tile = (IInventory) readField(cb, ContainerBrewingStand.class,
                new String[] { "field_75181_e", "tileBrewingStand" });
        if (tile != null) {
            try { brewTime = tile.getField(0); } catch (Throwable ignored) {}
            try { fuel = tile.getField(1); } catch (Throwable ignored) {}
        }
        out.put("brewTime", brewTime);
        out.put("fuelRemaining", fuel);
        out.put("brewProgress", brewTime == 0 ? 0.0 : (1.0 - brewTime / 400.0));
        out.put("isBrewing", brewTime > 0);
        return out;
    }

    // ---------- /enchant --------------------------------------------------

    private static Map<String, Object> enchant(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) { out.put("error", "no player"); return out; }
        Container c = p.openContainer;
        if (!(c instanceof ContainerEnchantment)) {
            out.put("error", "no enchantment table open");
            out.put("containerClass", c == null ? null : c.getClass().getName());
            return out;
        }
        ContainerEnchantment ce = (ContainerEnchantment) c;
        out.put("windowId", ce.windowId);
        out.put("item", itemInfo(ce.getSlot(0)));
        out.put("lapis", itemInfo(ce.getSlot(1)));
        // These are public final in vanilla:
        int[] lvl    = readIntArrField(ce, ContainerEnchantment.class, new String[] { "field_75167_g", "enchantLevels" });
        int[] clue   = readIntArrField(ce, ContainerEnchantment.class, new String[] { "field_185001_h", "enchantClue" });
        int[] wclue  = readIntArrField(ce, ContainerEnchantment.class, new String[] { "field_185002_i", "worldClue" });
        int xpSeed   = safeField(ce, "field_75172_h", "xpSeed", 0);
        out.put("enchantLevels", lvl == null ? new int[3] : lvl);
        out.put("enchantClue", clue == null ? new int[] { -1, -1, -1 } : clue);
        out.put("worldClue", wclue == null ? new int[] { -1, -1, -1 } : wclue);
        out.put("xpSeed", xpSeed);
        out.put("playerLevel", p.experienceLevel);
        return out;
    }

    // ---------- /anvil ----------------------------------------------------

    private static Map<String, Object> anvil(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) { out.put("error", "no player"); return out; }
        Container c = p.openContainer;
        if (!(c instanceof ContainerRepair)) {
            out.put("error", "no anvil open");
            out.put("containerClass", c == null ? null : c.getClass().getName());
            return out;
        }
        ContainerRepair cr = (ContainerRepair) c;
        out.put("windowId", cr.windowId);
        out.put("inputLeft", itemInfo(cr.getSlot(0)));
        out.put("inputRight", itemInfo(cr.getSlot(1)));
        out.put("output", itemInfo(cr.getSlot(2)));
        out.put("maximumCost", cr.maximumCost);
        int materialCost = safeField(cr, "field_82856_l", "materialCost", 0);
        String renamed = (String) readField(cr, ContainerRepair.class,
                new String[] { "field_82857_m", "repairedItemName" });
        out.put("materialCost", materialCost);
        out.put("renamedTo", renamed);
        out.put("playerLevel", p.experienceLevel);
        out.put("affordable", p.experienceLevel >= cr.maximumCost || p.capabilities.isCreativeMode);
        return out;
    }

    // ---------- /merchant -------------------------------------------------

    private static Map<String, Object> merchant(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) { out.put("error", "no player"); return out; }
        Container c = p.openContainer;
        if (!(c instanceof ContainerMerchant)) {
            out.put("error", "no merchant open");
            out.put("containerClass", c == null ? null : c.getClass().getName());
            return out;
        }
        ContainerMerchant cm = (ContainerMerchant) c;
        out.put("windowId", cm.windowId);
        out.put("inputA", itemInfo(cm.getSlot(0)));
        out.put("inputB", itemInfo(cm.getSlot(1)));
        out.put("output", itemInfo(cm.getSlot(2)));
        int selected = 0;
        try {
            Method m = findMethod(ContainerMerchant.class, new String[] { "func_75174_d", "getCurrentRecipeIndex" });
            if (m != null) selected = (int) m.invoke(cm);
        } catch (Throwable ignored) {}
        out.put("selectedRecipe", selected);

        MerchantRecipeList recipes = null;
        try {
            Method m = findMethod(ContainerMerchant.class, new String[] { "func_75173_d", "getMerchant" });
            if (m != null) {
                Object merch = m.invoke(cm);
                if (merch != null) {
                    Method rm = findMethod(merch.getClass(), new String[] { "func_70934_b", "getRecipes" }, net.minecraft.entity.player.EntityPlayer.class);
                    if (rm != null) recipes = (MerchantRecipeList) rm.invoke(merch, p);
                }
            }
        } catch (Throwable ignored) {}
        List<Map<String, Object>> outList = new ArrayList<>();
        if (recipes != null) {
            for (int i = 0; i < recipes.size(); i++) {
                MerchantRecipe r = recipes.get(i);
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("index", i);
                rm.put("input1", itemStackJsonSafe(r.getItemToBuy()));
                rm.put("input2", itemStackJsonSafe(r.getSecondItemToBuy()));
                rm.put("output", itemStackJsonSafe(r.getItemToSell()));
                rm.put("uses", r.getToolUses());
                rm.put("maxUses", r.getMaxTradeUses());
                rm.put("disabled", r.isRecipeDisabled());
                rm.put("rewardsExp", r.getRewardsExp());
                outList.add(rm);
            }
        }
        out.put("recipes", outList);
        return out;
    }

    // ---------- /beacon ---------------------------------------------------

    private static Map<String, Object> beacon(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) { out.put("error", "no player"); return out; }
        Container c = p.openContainer;
        if (!(c instanceof ContainerBeacon)) {
            out.put("error", "no beacon open");
            out.put("containerClass", c == null ? null : c.getClass().getName());
            return out;
        }
        ContainerBeacon cb = (ContainerBeacon) c;
        out.put("windowId", cb.windowId);
        out.put("payment", itemInfo(cb.getSlot(0)));
        // IInventory.getField maps on TileEntityBeacon:
        //   0: levels, 1: primary effect id, 2: secondary effect id
        int levels = -1, primary = -1, secondary = -1;
        try {
            Method getInv = findMethod(ContainerBeacon.class, new String[] { "func_180611_e", "getTileEntity" });
            if (getInv != null) {
                Object inv = getInv.invoke(cb);
                if (inv instanceof IInventory) {
                    IInventory iv = (IInventory) inv;
                    levels    = iv.getField(0);
                    primary   = iv.getField(1);
                    secondary = iv.getField(2);
                }
            }
        } catch (Throwable ignored) {}
        out.put("levels", levels);
        out.put("primaryEffectId", primary);
        out.put("secondaryEffectId", secondary);
        out.put("primaryEffectName", potionNameFromId(primary));
        out.put("secondaryEffectName", potionNameFromId(secondary));
        return out;
    }

    // ---------- /book.write -----------------------------------------------

    private static Map<String, Object> bookWrite(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) { out.put("error", "no player"); return out; }
        GuiScreen screen = mc.currentScreen;
        if (!(screen instanceof GuiScreenBook)) {
            out.put("error", "book screen not open");
            out.put("screenClass", screen == null ? null : screen.getClass().getName());
            return out;
        }
        GuiScreenBook book = (GuiScreenBook) screen;
        String pagesParam = q.get("pages");
        if (pagesParam == null || pagesParam.isEmpty()) {
            out.put("error", "missing pages");
            return out;
        }
        // Accept JSON array-ish comma-separated form "page1|page2|..." to
        // stay consistent with existing query-string helpers. For JSON
        // arrays callers can url-encode a '|' separator.
        String[] parts = pagesParam.split("\\|");
        NBTTagList nbtPages = new NBTTagList();
        for (String pg : parts) {
            String trimmed = pg.length() > 255 ? pg.substring(0, 255) : pg;
            nbtPages.appendTag(new NBTTagString(trimmed));
        }
        Field pagesField = findField(GuiScreenBook.class,
                new String[] { "field_146483_y", "bookPages" });
        if (pagesField == null) {
            out.put("error", "bookPages field not found"); return out;
        }
        try { pagesField.set(book, nbtPages); }
        catch (Throwable t) { out.put("error", t.toString()); return out; }

        // Force dirty so GuiScreenBook.updateButtons() rebuilds page count:
        try {
            Field dirty = findField(GuiScreenBook.class,
                    new String[] { "field_146481_r", "bookIsModified", "field_146482_z", "bookTotalPages" });
            if (dirty != null && dirty.getType() == boolean.class) dirty.setBoolean(book, true);
        } catch (Throwable ignored) {}
        try {
            Field pageCount = findField(GuiScreenBook.class,
                    new String[] { "field_146482_z", "bookTotalPages" });
            if (pageCount != null) pageCount.setInt(book, nbtPages.tagCount());
        } catch (Throwable ignored) {}

        boolean sign = "1".equals(q.get("sign")) || "true".equalsIgnoreCase(q.get("sign"));
        String title = q.get("title");
        if (sign) {
            try {
                Field titleF = findField(GuiScreenBook.class,
                        new String[] { "field_146485_F", "bookTitle" });
                if (titleF != null && title != null) {
                    String t = title.length() > 16 ? title.substring(0, 16) : title;
                    titleF.set(book, t);
                }
                Field signingF = findField(GuiScreenBook.class,
                        new String[] { "field_146484_x", "bookGettingSigned" });
                if (signingF != null) signingF.setBoolean(book, true);
                // Trigger actual finalize by invoking the private method
                Method sendBook = findMethod(GuiScreenBook.class,
                        new String[] { "func_146462_a", "sendBookToServer" }, boolean.class);
                if (sendBook != null) {
                    sendBook.setAccessible(true);
                    sendBook.invoke(book, true);
                }
            } catch (Throwable t) { out.put("signError", t.toString()); }
        }
        out.put("ok", true);
        out.put("pages", nbtPages.tagCount());
        out.put("signed", sign);
        return out;
    }

    // ---------- /creativeTab ----------------------------------------------

    private static Map<String, Object> creativeTab(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        int tab = DebugHttpServer.parseInt(q.get("tab"), -1);
        if (tab < 0) {
            // No tab requested -> just report available tabs.
            List<Map<String, Object>> tabs = new ArrayList<>();
            for (int i = 0; i < CreativeTabs.CREATIVE_TAB_ARRAY.length; i++) {
                CreativeTabs t = CreativeTabs.CREATIVE_TAB_ARRAY[i];
                if (t == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i);
                m.put("name", t.getTabLabel());
                m.put("display", t.getTranslatedTabLabel());
                tabs.add(m);
            }
            out.put("tabs", tabs);
            out.put("current", currentCreativeTab());
            return out;
        }
        GuiScreen screen = mc.currentScreen;
        if (!(screen instanceof GuiContainerCreative)) {
            out.put("error", "creative inventory not open");
            out.put("screenClass", screen == null ? null : screen.getClass().getName());
            return out;
        }
        if (tab >= CreativeTabs.CREATIVE_TAB_ARRAY.length || CreativeTabs.CREATIVE_TAB_ARRAY[tab] == null) {
            out.put("error", "invalid tab index"); return out;
        }
        GuiContainerCreative cc = (GuiContainerCreative) screen;
        try {
            Method m = findMethod(GuiContainerCreative.class,
                    new String[] { "func_147050_b", "setCurrentCreativeTab" }, CreativeTabs.class);
            if (m == null) { out.put("error", "setCurrentCreativeTab missing"); return out; }
            m.setAccessible(true);
            m.invoke(cc, CreativeTabs.CREATIVE_TAB_ARRAY[tab]);
        } catch (Throwable t) { out.put("error", t.toString()); return out; }
        out.put("ok", true);
        out.put("tab", tab);
        out.put("tabName", CreativeTabs.CREATIVE_TAB_ARRAY[tab].getTabLabel());
        return out;
    }

    private static Integer currentCreativeTab() {
        try {
            Method sel = findMethod(GuiContainerCreative.class,
                    new String[] { "func_147056_g", "getSelectedTabIndex" });
            if (sel != null) return (Integer) sel.invoke(null);
        } catch (Throwable ignored) {}
        return null;
    }

    // ---------- /clipboard ------------------------------------------------

    private static Map<String, Object> clipboard(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        String op = q.getOrDefault("op", "get");
        if ("set".equalsIgnoreCase(op)) {
            String text = q.getOrDefault("text", "");
            GuiScreen.setClipboardString(text);
            out.put("ok", true);
            out.put("op", "set");
            out.put("length", text.length());
        } else {
            String s = GuiScreen.getClipboardString();
            out.put("op", "get");
            if (s == null) s = "";
            if (s.length() > 4096) {
                out.put("truncated", true);
                s = s.substring(0, 4096);
            }
            out.put("text", s);
            out.put("length", s.length());
        }
        return out;
    }

    // ---------- /fishing.state --------------------------------------------

    private static Map<String, Object> fishingState(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        if (p == null) { out.put("error", "no player"); return out; }
        EntityFishHook hook = (EntityFishHook) readField(p,
                net.minecraft.entity.player.EntityPlayer.class,
                new String[] { "field_71104_cf", "fishEntity" });
        if (hook == null) {
            out.put("fishing", false);
            return out;
        }
        out.put("fishing", true);
        out.put("entityId", hook.getEntityId());
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", hook.posX); pos.put("y", hook.posY); pos.put("z", hook.posZ);
        out.put("pos", pos);
        Map<String, Object> vel = new LinkedHashMap<>();
        vel.put("x", hook.motionX); vel.put("y", hook.motionY); vel.put("z", hook.motionZ);
        out.put("motion", vel);
        out.put("inWater", hook.isInWater());
        // ticksCatchable is non-zero only on integrated server side;
        // client shows bobber pull via motionY spike.
        int ticksCatchable = safeField(hook, "field_146045_ax", "ticksCatchable", 0);
        out.put("ticksCatchable", ticksCatchable);
        int ticksCaughtDelay = safeField(hook, "field_146038_az", "ticksCaughtDelay", 0);
        out.put("ticksCaughtDelay", ticksCaughtDelay);
        return out;
    }

    // ---------- helpers ---------------------------------------------------

    private static Map<String, Object> itemInfo(Slot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slot", s.slotNumber);
        m.put("stack", s.getHasStack() ? DebugHttpServer.itemStackJson(s.getStack()) : null);
        return m;
    }

    private static Object itemStackJsonSafe(ItemStack st) {
        if (st == null || st.isEmpty()) return null;
        return DebugHttpServer.itemStackJson(st);
    }

    private static String potionNameFromId(int id) {
        if (id <= 0) return null;
        try {
            net.minecraft.potion.Potion pot = net.minecraft.potion.Potion.getPotionById(id);
            if (pot == null) return null;
            return pot.getRegistryName() != null ? pot.getRegistryName().toString() : pot.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    // --- reflection -------------------------------------------------------

    private static Field findField(Class<?> clazz, String[] names) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
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
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return null;
    }

    private static Object readField(Object obj, Class<?> owner, String[] names) {
        if (obj == null) return null;
        Field f = findField(owner, names);
        if (f == null) return null;
        try { return f.get(obj); } catch (Throwable ignored) { return null; }
    }

    private static int safeField(Object obj, String srg, String mcp, int defVal) {
        Field f = findField(obj.getClass(), new String[] { srg, mcp });
        if (f == null) return defVal;
        try { return f.getInt(obj); } catch (Throwable ignored) { return defVal; }
    }

    private static int[] readIntArrField(Object obj, Class<?> owner, String[] names) {
        Object v = readField(obj, owner, names);
        if (v instanceof int[]) return (int[]) v;
        return null;
    }
}
