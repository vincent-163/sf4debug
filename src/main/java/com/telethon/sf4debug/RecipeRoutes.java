package com.telethon.sf4debug;

import com.sun.net.httpserver.HttpServer;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Group 9 — recipe introspection routes (v0.6.0).
 *
 * <p>Exposes the JEI recipe registry over HTTP — the same data the "R"
 * and "U" keys show in-game. JEI is accessed entirely reflectively
 * through {@code mezz.jei.Internal.getRuntime()} so there is no
 * compile-time dependency on JEI (same pattern as {@link GuiRoutes}'s
 * AE2 integration). When JEI is missing, {@code /recipes.categories}
 * and {@code /recipes.list} fall back to vanilla
 * {@link CraftingManager#REGISTRY} + {@link FurnaceRecipes} so basic
 * crafting / smelting recipes are still queryable.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code /recipes.status} — JEI availability + counts.</li>
 *   <li>{@code /recipes.categories?limit=&offset=} — all recipe
 *       categories: {@code uid}, {@code title}, {@code modName},
 *       {@code recipeCount}, and the catalyst items (workstations)
 *       that craft this category.</li>
 *   <li>{@code /recipes.list?category=&limit=&offset=&ingredients=1}
 *       — paginate recipes within a category. With
 *       {@code ingredients=1} (default) each entry carries its
 *       {@code inputs} and {@code outputs} (list of slot-lists of
 *       item/fluid JSON, matching JEI's rotate-through semantics).
 *       Without it, only the index + wrapper class are returned,
 *       which is fast enough to enumerate thousands of recipes.</li>
 *   <li>{@code /recipes.lookup?item=&mode=output|input|both&limit=
 *       &maxScan=&category=} — scan JEI for recipes whose outputs
 *       (mode=output) or inputs (mode=input) include an item by
 *       registry name. {@code maxScan} caps the total number of
 *       recipes inspected across all categories to avoid runaway
 *       scans in SF4's enormous recipe table.</li>
 *   <li>{@code /recipes.get?category=&index=} — full detail of one
 *       recipe by category UID + index (as returned by
 *       {@code /recipes.list}).</li>
 *   <li>{@code /recipes.catalysts?category=} — just the catalyst
 *       items for a category (e.g. the crafting table ItemStack for
 *       {@code minecraft.crafting}).</li>
 * </ul>
 *
 * <p>All routes hop onto the client thread via
 * {@link DebugHttpServer#wrap}: JEI populates its registry during
 * {@code FMLLoadCompleteEvent} on the client thread, and several
 * {@code IRecipeWrapper#getIngredients} implementations call into
 * {@code Minecraft.getMinecraft()}.
 */
public final class RecipeRoutes {

    private RecipeRoutes() {}

    public static void register(HttpServer server) {
        server.createContext("/recipes.status",     DebugHttpServer.wrap(RecipeRoutes::status));
        server.createContext("/recipes.categories", DebugHttpServer.wrap(RecipeRoutes::categories));
        server.createContext("/recipes.list",       DebugHttpServer.wrap(RecipeRoutes::list));
        server.createContext("/recipes.lookup",     DebugHttpServer.wrap(RecipeRoutes::lookup));
        server.createContext("/recipes.get",        DebugHttpServer.wrap(RecipeRoutes::get));
        server.createContext("/recipes.catalysts",  DebugHttpServer.wrap(RecipeRoutes::catalysts));
        server.createContext("/items",              DebugHttpServer.wrap(RecipeRoutes::items));
        server.createContext("/fluids",             DebugHttpServer.wrap(RecipeRoutes::fluids));
    }

    /* ======================= JEI reflection ======================= */

    /** Fully-qualified JEI class / interface names we reflect against. */
    private static final String JEI_INTERNAL = "mezz.jei.Internal";
    private static final String JEI_API_IRECIPE_CATEGORY = "mezz.jei.api.recipe.IRecipeCategory";
    private static final String JEI_API_IINGREDIENTS = "mezz.jei.api.ingredients.IIngredients";
    private static final String JEI_IMPL_INGREDIENTS = "mezz.jei.ingredients.Ingredients";

    private static boolean classExists(String name) {
        try { Class.forName(name, false, RecipeRoutes.class.getClassLoader()); return true; }
        catch (Throwable t) { return false; }
    }

    /**
     * Returns the current JEI {@code IRecipeRegistry}, or null if JEI
     * is missing or its runtime has not been initialised yet
     * (JEI's runtime is set during {@code FMLLoadCompleteEvent}, so a
     * request that arrives before JEI's load-complete will get null).
     */
    private static Object getRecipeRegistry() {
        try {
            Class<?> internal = Class.forName(JEI_INTERNAL, false, RecipeRoutes.class.getClassLoader());
            Method getRuntime = internal.getMethod("getRuntime");
            Object runtime = getRuntime.invoke(null);
            if (runtime == null) return null;
            Method getReg = runtime.getClass().getMethod("getRecipeRegistry");
            return getReg.invoke(runtime);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Returns JEI's {@code IIngredientRegistry} directly via
     * {@code Internal.getIngredientRegistry()}, or null if JEI is missing.
     */
    private static Object getIngredientRegistry() {
        try {
            Class<?> internal = Class.forName(JEI_INTERNAL, false, RecipeRoutes.class.getClassLoader());
            Method getReg = internal.getMethod("getIngredientRegistry");
            return getReg.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ======================= /recipes.status ======================= */

    private static Map<String, Object> status(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean jeiInstalled = classExists(JEI_INTERNAL);
        out.put("jeiInstalled", jeiInstalled);
        Object reg = jeiInstalled ? getRecipeRegistry() : null;
        out.put("jeiRuntimeReady", reg != null);
        if (reg != null) {
            try {
                @SuppressWarnings("unchecked")
                List<Object> cats = (List<Object>) reg.getClass()
                        .getMethod("getRecipeCategories").invoke(reg);
                out.put("categoryCount", cats == null ? 0 : cats.size());
            } catch (Throwable t) {
                out.put("categoryCount", null);
                out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        // Vanilla always available.
        Map<String, Object> vanilla = new LinkedHashMap<>();
        int craftCount = 0;
        for (IRecipe ignored : CraftingManager.REGISTRY) craftCount++;
        vanilla.put("craftingRecipes", craftCount);
        vanilla.put("smeltingRecipes", FurnaceRecipes.instance().getSmeltingList().size());
        out.put("vanilla", vanilla);
        return out;
    }

    /* ======================= /recipes.categories ======================= */

    private static Map<String, Object> categories(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        int offset = clamp(DebugHttpServer.parseInt(q.get("offset"), 0), 0, Integer.MAX_VALUE);
        int limit  = clamp(DebugHttpServer.parseInt(q.get("limit"), 200), 1, 2000);

        Object reg = getRecipeRegistry();
        List<Map<String, Object>> cats = new ArrayList<>();
        int total;
        if (reg != null) {
            try {
                @SuppressWarnings("unchecked")
                List<Object> jeiCats = (List<Object>) reg.getClass()
                        .getMethod("getRecipeCategories").invoke(reg);
                total = jeiCats == null ? 0 : jeiCats.size();
                if (jeiCats != null) {
                    for (int i = offset; i < jeiCats.size() && cats.size() < limit; i++) {
                        cats.add(describeCategory(reg, jeiCats.get(i), /*withCatalysts*/ true));
                    }
                }
                out.put("source", "jei");
            } catch (Throwable t) {
                out.put("source", "jei");
                out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                total = 0;
            }
        } else {
            // Vanilla fallback — expose just the two bootstrap categories.
            out.put("source", "vanilla");
            out.put("warning", "JEI not available; showing vanilla crafting/smelting only");
            List<Map<String, Object>> v = vanillaCategorySummaries();
            total = v.size();
            for (int i = offset; i < v.size() && cats.size() < limit; i++) cats.add(v.get(i));
        }
        out.put("total", total);
        out.put("offset", offset);
        out.put("limit", limit);
        out.put("returned", cats.size());
        out.put("categories", cats);
        return out;
    }

    /** Build the JSON description of a single JEI category. */
    private static Map<String, Object> describeCategory(Object reg, Object cat, boolean withCatalysts) {
        Map<String, Object> m = new LinkedHashMap<>();
        String uid = callStringMethod(cat, "getUid");
        m.put("uid", uid);
        m.put("title", callStringMethod(cat, "getTitle"));
        m.put("modName", callStringMethod(cat, "getModName"));
        m.put("categoryClass", cat.getClass().getName());
        // Recipe count.
        Integer rc = null;
        try {
            @SuppressWarnings("unchecked")
            List<Object> rs = (List<Object>) reg.getClass()
                    .getMethod("getRecipeWrappers", Class.forName(JEI_API_IRECIPE_CATEGORY))
                    .invoke(reg, cat);
            rc = rs == null ? 0 : rs.size();
        } catch (Throwable ignored) {}
        m.put("recipeCount", rc);
        if (withCatalysts) {
            m.put("catalysts", catalystJson(reg, cat));
        }
        return m;
    }

    /** Pull catalyst items (workstations) for a category as item/fluid JSON. */
    private static List<Object> catalystJson(Object reg, Object cat) {
        List<Object> out = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) reg.getClass()
                    .getMethod("getRecipeCatalysts",
                               Class.forName(JEI_API_IRECIPE_CATEGORY))
                    .invoke(reg, cat);
            if (raw != null) {
                for (Object o : raw) {
                    Object j = ingredientJson(o);
                    if (j != null) out.add(j);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /* ======================= /recipes.list ======================= */

    private static Map<String, Object> list(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        String catUid = q.get("category");
        if (catUid == null || catUid.isEmpty()) {
            out.put("error", "missing category");
            return out;
        }
        boolean ingredients = !"0".equals(q.getOrDefault("ingredients", "1"));
        int offset = clamp(DebugHttpServer.parseInt(q.get("offset"), 0), 0, Integer.MAX_VALUE);
        int limit  = clamp(DebugHttpServer.parseInt(q.get("limit"), 50), 1, 500);

        Object reg = getRecipeRegistry();
        if (reg == null) {
            // Vanilla fallback: minecraft.crafting / minecraft.smelting.
            out.put("source", "vanilla");
            if ("minecraft.crafting".equals(catUid)) return vanillaCraftingList(out, offset, limit, ingredients);
            if ("minecraft.smelting".equals(catUid)) return vanillaSmeltingList(out, offset, limit, ingredients);
            out.put("error", "JEI not available and no vanilla fallback for this category");
            return out;
        }

        try {
            Object cat = reg.getClass().getMethod("getRecipeCategory", String.class).invoke(reg, catUid);
            if (cat == null) { out.put("error", "unknown category"); out.put("category", catUid); return out; }
            @SuppressWarnings("unchecked")
            List<Object> wrappers = (List<Object>) reg.getClass()
                    .getMethod("getRecipeWrappers", Class.forName(JEI_API_IRECIPE_CATEGORY))
                    .invoke(reg, cat);
            int total = wrappers == null ? 0 : wrappers.size();
            List<Map<String, Object>> recipes = new ArrayList<>();
            if (wrappers != null) {
                for (int i = offset; i < wrappers.size() && recipes.size() < limit; i++) {
                    recipes.add(describeRecipe(wrappers.get(i), i, ingredients));
                }
            }
            out.put("source", "jei");
            out.put("category", catUid);
            out.put("categoryTitle", callStringMethod(cat, "getTitle"));
            out.put("categoryModName", callStringMethod(cat, "getModName"));
            out.put("total", total);
            out.put("offset", offset);
            out.put("limit", limit);
            out.put("returned", recipes.size());
            out.put("recipes", recipes);
        } catch (Throwable t) {
            out.put("source", "jei");
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    /** Build the JSON description of a single JEI recipe wrapper. */
    private static Map<String, Object> describeRecipe(Object wrapper, int index, boolean withIngredients) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("wrapperClass", wrapper == null ? null : wrapper.getClass().getName());
        if (!withIngredients || wrapper == null) return m;
        try {
            Object ing = newIngredients();
            Method getI = findInterfaceMethod(wrapper.getClass(), "getIngredients",
                    Class.forName(JEI_API_IINGREDIENTS));
            if (getI != null && ing != null) {
                getI.invoke(wrapper, ing);
                m.put("inputs", readSlotLists(ing, /*inputs*/ true));
                m.put("outputs", readSlotLists(ing, /*inputs*/ false));
            } else {
                m.put("ingredientsError", "getIngredients not found");
            }
        } catch (Throwable t) {
            m.put("ingredientsError", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return m;
    }

    /**
     * Instantiate JEI's default {@code Ingredients} holder so we can
     * pass it to {@code IRecipeWrapper.getIngredients}. JEI's concrete
     * class name is {@code mezz.jei.ingredients.Ingredients}.
     */
    private static Object newIngredients() {
        try {
            Class<?> c = Class.forName(JEI_IMPL_INGREDIENTS);
            return c.getConstructor().newInstance();
        } catch (Throwable t) { return null; }
    }

    /**
     * Read either inputs or outputs from an {@code IIngredients}
     * instance. Combines item inputs and fluid inputs into a single
     * ordered list, each entry being a slot (itself a list of
     * alternative ingredients — JEI rotates through them).
     */
    private static List<Object> readSlotLists(Object ingredients, boolean wantInputs) {
        List<Object> out = new ArrayList<>();
        // Items.
        List<?> itemSlots = callGetInputsOrOutputs(ingredients, wantInputs, ItemStack.class);
        if (itemSlots != null) {
            for (Object slot : itemSlots) {
                out.add(mapSlotToJson(slot, /*isItem*/ true));
            }
        }
        // Fluids.
        List<?> fluidSlots = callGetInputsOrOutputs(ingredients, wantInputs, FluidStack.class);
        if (fluidSlots != null && !fluidSlots.isEmpty()) {
            for (Object slot : fluidSlots) {
                out.add(mapSlotToJson(slot, /*isItem*/ false));
            }
        }
        return out;
    }

    private static List<?> callGetInputsOrOutputs(Object ingredients, boolean wantInputs, Class<?> type) {
        try {
            Method m = ingredients.getClass().getMethod(
                    wantInputs ? "getInputs" : "getOutputs", Class.class);
            return (List<?>) m.invoke(ingredients, type);
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Object> mapSlotToJson(Object slot, boolean isItem) {
        List<Object> alts = new ArrayList<>();
        if (!(slot instanceof List)) return alts;
        for (Object alt : (List<?>) slot) {
            if (alt == null) { alts.add(null); continue; }
            if (isItem && alt instanceof ItemStack) {
                alts.add(DebugHttpServer.itemStackJson((ItemStack) alt));
            } else if (!isItem && alt instanceof FluidStack) {
                alts.add(fluidStackJson((FluidStack) alt));
            } else {
                // Unknown ingredient type — stringify.
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("class", alt.getClass().getName());
                m.put("toString", String.valueOf(alt));
                alts.add(m);
            }
        }
        return alts;
    }

    /* ======================= /recipes.lookup ======================= */

    /** Default cap on the number of recipes scanned before giving up. */
    private static final int DEFAULT_MAX_SCAN = 5000;

    private static Map<String, Object> lookup(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        String itemId = q.get("item");
        if (itemId == null || itemId.isEmpty()) { out.put("error", "missing item"); return out; }
        String mode = q.getOrDefault("mode", "both");
        int limit    = clamp(DebugHttpServer.parseInt(q.get("limit"), 100), 1, 1000);
        int maxScan  = clamp(DebugHttpServer.parseInt(q.get("maxScan"), DEFAULT_MAX_SCAN), 1, 200_000);
        String catFilter = q.get("category");
        Integer metaFilter = DebugHttpServer.parseInt(q.get("meta"), null);
        boolean checkIn  = "input".equals(mode) || "both".equals(mode);
        boolean checkOut = "output".equals(mode) || "both".equals(mode);

        Object reg = getRecipeRegistry();
        if (reg == null) { out.put("error", "JEI not available"); return out; }

        List<Map<String, Object>> matches = new ArrayList<>();
        int scanned = 0;
        boolean truncated = false;
        try {
            @SuppressWarnings("unchecked")
            List<Object> cats = (List<Object>) reg.getClass()
                    .getMethod("getRecipeCategories").invoke(reg);
            if (cats == null) cats = java.util.Collections.emptyList();
            Class<?> categoryCls = Class.forName(JEI_API_IRECIPE_CATEGORY);
            Method getWrappers = reg.getClass().getMethod("getRecipeWrappers", categoryCls);

            Object ingredientsHolder = newIngredients();

            outer:
            for (Object cat : cats) {
                String uid = callStringMethod(cat, "getUid");
                if (catFilter != null && !catFilter.isEmpty() && !catFilter.equals(uid)) continue;
                @SuppressWarnings("unchecked")
                List<Object> wrappers = (List<Object>) getWrappers.invoke(reg, cat);
                if (wrappers == null) continue;
                for (int i = 0; i < wrappers.size(); i++) {
                    if (scanned >= maxScan) { truncated = true; break outer; }
                    scanned++;
                    Object w = wrappers.get(i);
                    if (w == null) continue;
                    // Fresh ingredient holder per recipe (JEI plugins
                    // expect an empty one).
                    if (ingredientsHolder == null) ingredientsHolder = newIngredients();
                    if (ingredientsHolder == null) break outer;
                    try {
                        Method getI = findInterfaceMethod(w.getClass(), "getIngredients",
                                Class.forName(JEI_API_IINGREDIENTS));
                        if (getI == null) continue;
                        getI.invoke(w, ingredientsHolder);
                        boolean hit = false;
                        boolean hitIn = false, hitOut = false;
                        if (checkIn)  hitIn  = slotListContains(callGetInputsOrOutputs(ingredientsHolder, true,  ItemStack.class), itemId, metaFilter);
                        if (checkOut) hitOut = slotListContains(callGetInputsOrOutputs(ingredientsHolder, false, ItemStack.class), itemId, metaFilter);
                        hit = hitIn || hitOut;
                        if (!hit) {
                            ingredientsHolder = newIngredients();
                            continue;
                        }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("category", uid);
                        m.put("index", i);
                        m.put("wrapperClass", w.getClass().getName());
                        if (checkIn)  m.put("matchedInput", hitIn);
                        if (checkOut) m.put("matchedOutput", hitOut);
                        m.put("inputs",  readSlotLists(ingredientsHolder, true));
                        m.put("outputs", readSlotLists(ingredientsHolder, false));
                        matches.add(m);
                        if (matches.size() >= limit) { truncated = truncated || (scanned < totalAcross(cats, reg)); break outer; }
                    } catch (Throwable ignored) {
                        // Move on — one bad wrapper shouldn't kill the whole scan.
                    } finally {
                        ingredientsHolder = newIngredients();
                    }
                }
            }
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            return out;
        }
        out.put("item", itemId);
        if (metaFilter != null) out.put("meta", metaFilter);
        out.put("mode", mode);
        if (catFilter != null) out.put("category", catFilter);
        out.put("scanned", scanned);
        out.put("maxScan", maxScan);
        out.put("limit", limit);
        out.put("returned", matches.size());
        out.put("truncated", truncated);
        out.put("matches", matches);
        return out;
    }

    /** Sums wrapper counts across all categories; used for truncated flag. */
    private static int totalAcross(List<Object> cats, Object reg) {
        int total = 0;
        try {
            Class<?> categoryCls = Class.forName(JEI_API_IRECIPE_CATEGORY);
            Method getWrappers = reg.getClass().getMethod("getRecipeWrappers", categoryCls);
            for (Object cat : cats) {
                @SuppressWarnings("unchecked")
                List<Object> w = (List<Object>) getWrappers.invoke(reg, cat);
                if (w != null) total += w.size();
            }
        } catch (Throwable ignored) {}
        return total;
    }

    /** Returns true if any slot's any alternative ItemStack matches id (and optional meta). */
    private static boolean slotListContains(List<?> slots, String id, Integer meta) {
        if (slots == null) return false;
        for (Object slot : slots) {
            if (!(slot instanceof List)) continue;
            for (Object alt : (List<?>) slot) {
                if (!(alt instanceof ItemStack)) continue;
                ItemStack st = (ItemStack) alt;
                if (st.isEmpty()) continue;
                Item it = st.getItem();
                if (it == null) continue;
                ResourceLocation rl = it.getRegistryName();
                if (rl == null) continue;
                if (!id.equals(rl.toString())) continue;
                if (meta != null && st.getMetadata() != meta && st.getMetadata() != 32767) continue;
                return true;
            }
        }
        return false;
    }

    /* ======================= /recipes.get ======================= */

    private static Map<String, Object> get(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        String catUid = q.get("category");
        Integer idx = DebugHttpServer.parseInt(q.get("index"), null);
        if (catUid == null || catUid.isEmpty() || idx == null) {
            out.put("error", "missing category or index");
            return out;
        }
        Object reg = getRecipeRegistry();
        if (reg == null) {
            if ("minecraft.crafting".equals(catUid)) return vanillaGetCrafting(out, idx);
            if ("minecraft.smelting".equals(catUid)) return vanillaGetSmelting(out, idx);
            out.put("error", "JEI not available and no vanilla fallback for this category");
            return out;
        }
        try {
            Object cat = reg.getClass().getMethod("getRecipeCategory", String.class).invoke(reg, catUid);
            if (cat == null) { out.put("error", "unknown category"); out.put("category", catUid); return out; }
            @SuppressWarnings("unchecked")
            List<Object> wrappers = (List<Object>) reg.getClass()
                    .getMethod("getRecipeWrappers", Class.forName(JEI_API_IRECIPE_CATEGORY))
                    .invoke(reg, cat);
            if (wrappers == null || idx < 0 || idx >= wrappers.size()) {
                out.put("error", "index out of range");
                out.put("total", wrappers == null ? 0 : wrappers.size());
                return out;
            }
            out.put("source", "jei");
            out.put("category", catUid);
            out.put("categoryTitle", callStringMethod(cat, "getTitle"));
            out.put("categoryModName", callStringMethod(cat, "getModName"));
            out.put("recipe", describeRecipe(wrappers.get(idx), idx, true));
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    /* ======================= /recipes.catalysts ======================= */

    private static Map<String, Object> catalysts(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        String catUid = q.get("category");
        if (catUid == null || catUid.isEmpty()) { out.put("error", "missing category"); return out; }
        Object reg = getRecipeRegistry();
        if (reg == null) { out.put("error", "JEI not available"); return out; }
        try {
            Object cat = reg.getClass().getMethod("getRecipeCategory", String.class).invoke(reg, catUid);
            if (cat == null) { out.put("error", "unknown category"); out.put("category", catUid); return out; }
            out.put("category", catUid);
            out.put("categoryTitle", callStringMethod(cat, "getTitle"));
            out.put("catalysts", catalystJson(reg, cat));
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    /* ======================= /items & /fluids ======================= */

    private static Map<String, Object> items(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        int offset = clamp(DebugHttpServer.parseInt(q.get("offset"), 0), 0, Integer.MAX_VALUE);
        int limit  = clamp(DebugHttpServer.parseInt(q.get("limit"), 500), 1, 5000);
        String nameFilter = q.get("name");
        String modFilter = q.get("mod");
        String idFilter = q.get("id");
        if (nameFilter != null) nameFilter = nameFilter.toLowerCase();
        if (modFilter != null) modFilter = modFilter.toLowerCase();

        Object reg = getIngredientRegistry();
        if (reg == null) { out.put("error", "JEI not available"); return out; }

        try {
            Collection<?> all = null;
            try {
                Method m = reg.getClass().getMethod("getAllIngredients", Class.class);
                all = (Collection<?>) m.invoke(reg, ItemStack.class);
            } catch (NoSuchMethodException e) {
                Method m = reg.getClass().getMethod("getIngredients", Class.class);
                all = (Collection<?>) m.invoke(reg, ItemStack.class);
            }
            if (all == null) all = java.util.Collections.emptyList();

            List<Map<String, Object>> items = new ArrayList<>();
            int totalMatches = 0;
            for (Object o : all) {
                if (!(o instanceof ItemStack)) continue;
                ItemStack stack = (ItemStack) o;
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (item == null) continue;
                ResourceLocation rl = item.getRegistryName();
                if (rl == null) continue;

                String id = rl.toString();
                String modId = rl.getResourceDomain();

                if (modFilter != null && !modFilter.isEmpty() && !modId.toLowerCase().contains(modFilter)) continue;
                if (idFilter != null && !idFilter.isEmpty() && !id.equals(idFilter)) continue;

                if (nameFilter != null && !nameFilter.isEmpty()) {
                    String displayName;
                    try { displayName = stack.getDisplayName(); } catch (Throwable t) { displayName = id; }
                    if (displayName == null || !displayName.toLowerCase().contains(nameFilter)) continue;
                }

                totalMatches++;
                if (totalMatches <= offset) continue;
                if (items.size() >= limit) continue;

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("meta", stack.getMetadata());
                try { m.put("displayName", stack.getDisplayName()); } catch (Throwable t) { m.put("displayName", id); }
                m.put("modId", modId);
                items.add(m);
            }

            out.put("total", totalMatches);
            out.put("offset", offset);
            out.put("limit", limit);
            out.put("returned", items.size());
            out.put("items", items);
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    private static Map<String, Object> fluids(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        int offset = clamp(DebugHttpServer.parseInt(q.get("offset"), 0), 0, Integer.MAX_VALUE);
        int limit  = clamp(DebugHttpServer.parseInt(q.get("limit"), 500), 1, 5000);
        String nameFilter = q.get("name");
        if (nameFilter != null) nameFilter = nameFilter.toLowerCase();

        Object reg = getIngredientRegistry();
        if (reg == null) { out.put("error", "JEI not available"); return out; }

        try {
            Collection<?> all = null;
            try {
                Method m = reg.getClass().getMethod("getAllIngredients", Class.class);
                all = (Collection<?>) m.invoke(reg, FluidStack.class);
            } catch (NoSuchMethodException e) {
                Method m = reg.getClass().getMethod("getIngredients", Class.class);
                all = (Collection<?>) m.invoke(reg, FluidStack.class);
            }
            if (all == null) all = java.util.Collections.emptyList();

            List<Map<String, Object>> fluids = new ArrayList<>();
            int totalMatches = 0;
            for (Object o : all) {
                if (!(o instanceof FluidStack)) continue;
                FluidStack fs = (FluidStack) o;
                if (fs.getFluid() == null) continue;

                String fluidName = fs.getFluid().getName();

                if (nameFilter != null && !nameFilter.isEmpty()) {
                    String displayName;
                    try { displayName = fs.getLocalizedName(); } catch (Throwable t) { displayName = fluidName; }
                    if (displayName == null || !displayName.toLowerCase().contains(nameFilter)) continue;
                }

                totalMatches++;
                if (totalMatches <= offset) continue;
                if (fluids.size() >= limit) continue;

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fluid", fluidName);
                m.put("amount", fs.amount);
                try {
                    String defaultName = net.minecraftforge.fluids.FluidRegistry.getDefaultFluidName(fs.getFluid());
                    if (defaultName != null) m.put("defaultName", defaultName);
                } catch (Throwable ignored) {}
                try { m.put("displayName", fs.getLocalizedName()); } catch (Throwable t) {}
                fluids.add(m);
            }

            out.put("total", totalMatches);
            out.put("offset", offset);
            out.put("limit", limit);
            out.put("returned", fluids.size());
            out.put("fluids", fluids);
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    /* ======================= Vanilla fallbacks ======================= */

    private static List<Map<String, Object>> vanillaCategorySummaries() {
        List<Map<String, Object>> cats = new ArrayList<>();
        int craftCount = 0;
        for (IRecipe ignored : CraftingManager.REGISTRY) craftCount++;
        Map<String, Object> c1 = new LinkedHashMap<>();
        c1.put("uid", "minecraft.crafting");
        c1.put("title", "Crafting");
        c1.put("modName", "Minecraft");
        c1.put("categoryClass", null);
        c1.put("recipeCount", craftCount);
        cats.add(c1);
        Map<String, Object> c2 = new LinkedHashMap<>();
        c2.put("uid", "minecraft.smelting");
        c2.put("title", "Smelting");
        c2.put("modName", "Minecraft");
        c2.put("categoryClass", null);
        c2.put("recipeCount", FurnaceRecipes.instance().getSmeltingList().size());
        cats.add(c2);
        return cats;
    }

    private static Map<String, Object> vanillaCraftingList(Map<String, Object> out, int offset, int limit, boolean withIngredients) {
        List<IRecipe> all = new ArrayList<>();
        for (IRecipe r : CraftingManager.REGISTRY) all.add(r);
        List<Map<String, Object>> recipes = new ArrayList<>();
        for (int i = offset; i < all.size() && recipes.size() < limit; i++) {
            IRecipe r = all.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("wrapperClass", r.getClass().getName());
            m.put("registryName", r.getRegistryName() == null ? null : r.getRegistryName().toString());
            if (withIngredients) {
                List<Object> inputs = new ArrayList<>();
                try {
                    NonNullList<Ingredient> ings = r.getIngredients();
                    for (Ingredient ing : ings) {
                        List<Object> alts = new ArrayList<>();
                        for (ItemStack st : ing.getMatchingStacks()) alts.add(DebugHttpServer.itemStackJson(st));
                        inputs.add(alts);
                    }
                } catch (Throwable ignored) {}
                m.put("inputs", inputs);
                List<Object> outputs = new ArrayList<>();
                List<Object> outAlts = new ArrayList<>();
                outAlts.add(DebugHttpServer.itemStackJson(r.getRecipeOutput()));
                outputs.add(outAlts);
                m.put("outputs", outputs);
            }
            recipes.add(m);
        }
        out.put("category", "minecraft.crafting");
        out.put("total", all.size());
        out.put("offset", offset);
        out.put("limit", limit);
        out.put("returned", recipes.size());
        out.put("recipes", recipes);
        return out;
    }

    private static Map<String, Object> vanillaSmeltingList(Map<String, Object> out, int offset, int limit, boolean withIngredients) {
        List<Map<String, Object>> recipes = new ArrayList<>();
        int i = 0;
        int picked = 0;
        for (Map.Entry<ItemStack, ItemStack> e : FurnaceRecipes.instance().getSmeltingList().entrySet()) {
            if (i >= offset && picked < limit) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i);
                m.put("wrapperClass", "minecraft.furnace");
                if (withIngredients) {
                    List<Object> inputs = new ArrayList<>();
                    List<Object> alts = new ArrayList<>();
                    alts.add(DebugHttpServer.itemStackJson(e.getKey()));
                    inputs.add(alts);
                    m.put("inputs", inputs);
                    List<Object> outputs = new ArrayList<>();
                    List<Object> outAlts = new ArrayList<>();
                    outAlts.add(DebugHttpServer.itemStackJson(e.getValue()));
                    outputs.add(outAlts);
                    m.put("outputs", outputs);
                    float xp = FurnaceRecipes.instance().getSmeltingExperience(e.getValue());
                    m.put("experience", xp);
                }
                recipes.add(m);
                picked++;
            }
            i++;
        }
        out.put("category", "minecraft.smelting");
        out.put("total", i);
        out.put("offset", offset);
        out.put("limit", limit);
        out.put("returned", recipes.size());
        out.put("recipes", recipes);
        return out;
    }

    private static Map<String, Object> vanillaGetCrafting(Map<String, Object> out, int idx) {
        List<IRecipe> all = new ArrayList<>();
        for (IRecipe r : CraftingManager.REGISTRY) all.add(r);
        if (idx < 0 || idx >= all.size()) { out.put("error", "index out of range"); out.put("total", all.size()); return out; }
        IRecipe r = all.get(idx);
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("index", idx);
        recipe.put("wrapperClass", r.getClass().getName());
        recipe.put("registryName", r.getRegistryName() == null ? null : r.getRegistryName().toString());
        List<Object> inputs = new ArrayList<>();
        try {
            NonNullList<Ingredient> ings = r.getIngredients();
            for (Ingredient ing : ings) {
                List<Object> alts = new ArrayList<>();
                for (ItemStack st : ing.getMatchingStacks()) alts.add(DebugHttpServer.itemStackJson(st));
                inputs.add(alts);
            }
        } catch (Throwable ignored) {}
        recipe.put("inputs", inputs);
        List<Object> outputs = new ArrayList<>();
        List<Object> outAlts = new ArrayList<>();
        outAlts.add(DebugHttpServer.itemStackJson(r.getRecipeOutput()));
        outputs.add(outAlts);
        recipe.put("outputs", outputs);
        out.put("source", "vanilla");
        out.put("category", "minecraft.crafting");
        out.put("recipe", recipe);
        return out;
    }

    private static Map<String, Object> vanillaGetSmelting(Map<String, Object> out, int idx) {
        List<Map.Entry<ItemStack, ItemStack>> all = new ArrayList<>(FurnaceRecipes.instance().getSmeltingList().entrySet());
        if (idx < 0 || idx >= all.size()) { out.put("error", "index out of range"); out.put("total", all.size()); return out; }
        Map.Entry<ItemStack, ItemStack> e = all.get(idx);
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("index", idx);
        recipe.put("wrapperClass", "minecraft.furnace");
        List<Object> inputs = new ArrayList<>();
        List<Object> alts = new ArrayList<>();
        alts.add(DebugHttpServer.itemStackJson(e.getKey()));
        inputs.add(alts);
        recipe.put("inputs", inputs);
        List<Object> outputs = new ArrayList<>();
        List<Object> outAlts = new ArrayList<>();
        outAlts.add(DebugHttpServer.itemStackJson(e.getValue()));
        outputs.add(outAlts);
        recipe.put("outputs", outputs);
        recipe.put("experience", FurnaceRecipes.instance().getSmeltingExperience(e.getValue()));
        out.put("source", "vanilla");
        out.put("category", "minecraft.smelting");
        out.put("recipe", recipe);
        return out;
    }

    /* ======================= Helpers ======================= */

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String callStringMethod(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            Object r = m.invoke(target);
            return r == null ? null : r.toString();
        } catch (Throwable t) { return null; }
    }

    /**
     * Look up a method by name + param types by searching the class
     * hierarchy and implemented interfaces. Needed because
     * {@code getIngredients(IIngredients)} is declared on the
     * {@code IRecipeWrapper} interface, not directly on most plugin
     * wrapper classes, and {@code Class#getMethod} should find it —
     * but some plugin wrappers use package-private methods so we fall
     * back to declared-method walk for robustness.
     */
    private static Method findInterfaceMethod(Class<?> start, String name, Class<?>... params) {
        try {
            Method m = start.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {}
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            for (Class<?> iface : c.getInterfaces()) {
                try {
                    Method m = iface.getDeclaredMethod(name, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return null;
    }

    /** Compact JSON for a Forge FluidStack; matches itemStackJson's style. */
    private static Object fluidStackJson(FluidStack fs) {
        if (fs == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fluid", fs.getFluid() == null ? null : fs.getFluid().getName());
        m.put("amount", fs.amount);
        try {
            String loc = fs.getFluid() == null ? null
                    : net.minecraftforge.fluids.FluidRegistry.getDefaultFluidName(fs.getFluid());
            if (loc != null) m.put("defaultName", loc);
        } catch (Throwable ignored) {}
        if (fs.tag != null) m.put("nbt", String.valueOf(fs.tag));
        return m;
    }

    /** Dispatch for an opaque JEI ingredient (ItemStack, FluidStack, or plugin-defined type). */
    private static Object ingredientJson(Object o) {
        if (o == null) return null;
        if (o instanceof ItemStack) return DebugHttpServer.itemStackJson((ItemStack) o);
        if (o instanceof FluidStack) return fluidStackJson((FluidStack) o);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", o.getClass().getName());
        m.put("toString", String.valueOf(o));
        return m;
    }
}
