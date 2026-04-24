package com.telethon.sf4debug;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only block queries: single block, box of blocks around the
 * player, and raycast-sampled visible blocks in the current FOV.
 *
 * <p>All methods must be called on the client thread via
 * {@link DebugHttpServer#runOnClientThread}; this class does not do
 * its own marshalling. See the wiring in {@link DebugHttpServer}.
 */
public final class BlockRoutes {

    private BlockRoutes() {}

    /** /block?x=&y=&z= — detailed info about one block. */
    public static Object block(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = mc.world;
        Map<String, Object> out = new LinkedHashMap<>();
        if (w == null) { out.put("state", "not_in_world"); return out; }
        Integer x = DebugHttpServer.parseInt(q.get("x"), null);
        Integer y = DebugHttpServer.parseInt(q.get("y"), null);
        Integer z = DebugHttpServer.parseInt(q.get("z"), null);
        if (x == null || y == null || z == null) {
            out.put("error", "missing x/y/z");
            return out;
        }
        return blockJson(w, new BlockPos(x, y, z), /*detailed=*/true);
    }

    /**
     * /blocks?radius=8&dy=8&limit=2000&nonAir=1&name=ore&solid=0
     *
     * Enumerates every loaded block in a box around the player's eye.
     * Default radius 8 (17x17x17 = ~4900 cells). The {@code name}
     * filter is a case-insensitive substring match on the registry
     * name ({@code minecraft:stone}, {@code minecraft:iron_ore}, ...).
     * {@code nonAir=0} returns air blocks too (useful for a dense slice
     * visualization). {@code solid=1} keeps only blocks whose material
     * blocks movement (rules out grass, vines, water, etc.).
     */
    public static Object blocks(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = mc.world;
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (w == null || p == null) { out.put("state", "not_in_world"); return out; }

        int radius = DebugHttpServer.parseInt(q.get("radius"), 8);
        radius = Math.max(0, Math.min(radius, 32));
        int dy = DebugHttpServer.parseInt(q.get("dy"), radius);
        dy = Math.max(0, Math.min(dy, 64));
        int limit = DebugHttpServer.parseInt(q.get("limit"), 2000);
        boolean nonAir = !"0".equals(q.get("nonAir"));
        boolean solidOnly = "1".equals(q.get("solid"));
        String nameFilter = q.get("name");

        int cx = (int) Math.floor(p.posX);
        int cy = (int) Math.floor(p.posY + p.getEyeHeight());
        int cz = (int) Math.floor(p.posZ);

        List<Map<String, Object>> blocks = new ArrayList<>();
        int scanned = 0;
        int matched = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int ddy = -dy; ddy <= dy; ddy++) {
            int by = cy + ddy;
            if (by < 0 || by > 255) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                int bx = cx + dx;
                for (int dz = -radius; dz <= radius; dz++) {
                    int bz = cz + dz;
                    m.setPos(bx, by, bz);
                    IBlockState st = w.getBlockState(m);
                    scanned++;
                    Material mat = st.getMaterial();
                    if (nonAir && mat == Material.AIR) continue;
                    if (solidOnly && !mat.blocksMovement()) continue;
                    Block b = st.getBlock();
                    String id = b.getRegistryName() == null ? "" : b.getRegistryName().toString();
                    if (nameFilter != null && !id.toLowerCase().contains(nameFilter.toLowerCase())) continue;
                    matched++;
                    if (blocks.size() >= limit) continue;
                    Map<String, Object> mj = new LinkedHashMap<>();
                    mj.put("x", bx);
                    mj.put("y", by);
                    mj.put("z", bz);
                    mj.put("dx", dx);
                    mj.put("dy", ddy);
                    mj.put("dz", dz);
                    mj.put("id", id);
                    mj.put("meta", b.getMetaFromState(st));
                    blocks.add(mj);
                }
            }
        }
        out.put("center", Arrays.asList(cx, cy, cz));
        out.put("radius", radius);
        out.put("dy", dy);
        out.put("scanned", scanned);
        out.put("matched", matched);
        out.put("returned", blocks.size());
        out.put("blocks", blocks);
        return out;
    }

    /**
     * /visible?range=64&hfov=70&vfov=50&hres=32&vres=18&limit=500&fluids=0
     *
     * Casts {@code hres*vres} rays from the player's eye over the
     * configured FOV box and returns each unique block a ray hits,
     * along with the ray's hit vector and face. This is the
     * "tell me what the player can see on screen" endpoint.
     *
     * <p>Defaults cover ~70°x50° (vanilla FOV), 32x18 = 576 rays, which
     * runs well under a tick even with modded rendering. Bump
     * {@code hres}/{@code vres} for finer sampling.
     */
    public static Object visible(Map<String, String> q) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = mc.world;
        EntityPlayerSP p = mc.player;
        Map<String, Object> out = new LinkedHashMap<>();
        if (w == null || p == null) { out.put("state", "not_in_world"); return out; }

        double range = DebugHttpServer.parseDouble(q.get("range"), 64.0);
        double hfov = DebugHttpServer.parseDouble(q.get("hfov"), 70.0);
        double vfov = DebugHttpServer.parseDouble(q.get("vfov"), 50.0);
        int hres = DebugHttpServer.parseInt(q.get("hres"), 32);
        int vres = DebugHttpServer.parseInt(q.get("vres"), 18);
        int limit = DebugHttpServer.parseInt(q.get("limit"), 500);
        boolean stopOnLiquid = "1".equals(q.get("fluids")) ? false : true;

        // Reasonable caps so an overzealous caller can't wedge the client thread.
        hres = Math.max(1, Math.min(hres, 128));
        vres = Math.max(1, Math.min(vres, 96));
        range = Math.max(1.0, Math.min(range, 256.0));

        Vec3d eye = p.getPositionEyes(1.0f);
        float baseYaw = p.rotationYaw;
        float basePitch = p.rotationPitch;

        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> blocks = new ArrayList<>();
        int rays = 0;
        for (int iv = 0; iv < vres; iv++) {
            double pitchOffset = ((iv + 0.5) / vres - 0.5) * vfov;
            for (int ih = 0; ih < hres; ih++) {
                double yawOffset = ((ih + 0.5) / hres - 0.5) * hfov;
                rays++;
                float yaw = (float) (baseYaw + yawOffset);
                float pitch = (float) (basePitch + pitchOffset);
                Vec3d dir = Vec3d.fromPitchYaw(pitch, yaw);
                Vec3d to = eye.addVector(dir.x * range, dir.y * range, dir.z * range);
                RayTraceResult rtr = w.rayTraceBlocks(eye, to, stopOnLiquid, false, false);
                if (rtr == null || rtr.typeOfHit != RayTraceResult.Type.BLOCK) continue;
                BlockPos bp = rtr.getBlockPos();
                long key = bp.toLong();
                if (!seen.add(key)) continue;
                if (blocks.size() >= limit) continue;
                IBlockState st = w.getBlockState(bp);
                Block b = st.getBlock();
                Map<String, Object> mj = new LinkedHashMap<>();
                mj.put("x", bp.getX());
                mj.put("y", bp.getY());
                mj.put("z", bp.getZ());
                mj.put("id", b.getRegistryName() == null ? null : b.getRegistryName().toString());
                mj.put("meta", b.getMetaFromState(st));
                mj.put("side", rtr.sideHit == null ? null : rtr.sideHit.name());
                mj.put("dist", eye.distanceTo(rtr.hitVec));
                Map<String, Object> hv = new LinkedHashMap<>();
                hv.put("x", rtr.hitVec.x);
                hv.put("y", rtr.hitVec.y);
                hv.put("z", rtr.hitVec.z);
                mj.put("hit", hv);
                mj.put("yawOff", yawOffset);
                mj.put("pitchOff", pitchOffset);
                blocks.add(mj);
            }
        }
        out.put("range", range);
        out.put("hfov", hfov);
        out.put("vfov", vfov);
        out.put("hres", hres);
        out.put("vres", vres);
        out.put("stopOnLiquid", stopOnLiquid);
        out.put("raysCast", rays);
        out.put("uniqueBlocks", blocks.size());
        out.put("blocks", blocks);
        return out;
    }

    /** Internal helper used by both /block and /look. */
    static Map<String, Object> blockJson(WorldClient w, BlockPos bp, boolean detailed) {
        Map<String, Object> out = new LinkedHashMap<>();
        IBlockState st = w.getBlockState(bp);
        Block b = st.getBlock();
        out.put("x", bp.getX());
        out.put("y", bp.getY());
        out.put("z", bp.getZ());
        out.put("id", b.getRegistryName() == null ? null : b.getRegistryName().toString());
        out.put("meta", b.getMetaFromState(st));
        out.put("material", st.getMaterial() == null ? null : st.getMaterial().getClass().getSimpleName());
        out.put("lightOpacity", st.getLightOpacity(w, bp));
        out.put("lightValue", st.getLightValue(w, bp));
        try { out.put("hardness", st.getBlockHardness(w, bp)); } catch (Throwable t) { out.put("hardness", null); }
        out.put("translucent", st.isTranslucent());
        out.put("fullBlock", st.isFullBlock());
        out.put("opaqueCube", st.isOpaqueCube());
        if (detailed) {
            try {
                AxisAlignedBB bb = st.getBoundingBox(w, bp);
                Map<String, Object> box = new LinkedHashMap<>();
                box.put("minX", bb.minX); box.put("minY", bb.minY); box.put("minZ", bb.minZ);
                box.put("maxX", bb.maxX); box.put("maxY", bb.maxY); box.put("maxZ", bb.maxZ);
                out.put("bbox", box);
            } catch (Throwable ignored) {}
            // Block-state properties (age, facing, color, ...).
            Map<String, Object> props = new LinkedHashMap<>();
            try {
                for (Map.Entry<net.minecraft.block.properties.IProperty<?>, Comparable<?>> e
                        : st.getProperties().entrySet()) {
                    props.put(e.getKey().getName(), String.valueOf(e.getValue()));
                }
            } catch (Throwable ignored) {}
            out.put("properties", props);
            TileEntity te = w.getTileEntity(bp);
            if (te != null) {
                Map<String, Object> tj = new LinkedHashMap<>();
                tj.put("class", te.getClass().getName());
                try { tj.put("nbt", String.valueOf(te.serializeNBT())); }
                catch (Throwable t) { tj.put("nbt", null); }
                out.put("tileEntity", tj);
            }
        }
        return out;
    }
}
