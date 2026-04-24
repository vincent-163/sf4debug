package com.telethon.sf4debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.CPacketTabComplete;
import net.minecraft.network.play.server.SPacketTabComplete;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code /tabcomplete} — round-trips a {@code /tabcomplete?text=&cursor=}
 * query through the currently-connected server.
 *
 * <p>Vanilla tab-completion on 1.12.2 works like this:
 *
 * <ol>
 *   <li>Client sends {@link CPacketTabComplete} with the current chat
 *       text, optional raytraced-block position, and a {@code
 *       hasTargetBlock} flag.
 *   <li>Server runs its command-completion logic and replies with
 *       {@link SPacketTabComplete} carrying a {@code String[]} of
 *       matches.
 *   <li>The vanilla {@code NetHandlerPlayClient} hands the matches off
 *       to a pending {@code GuiChat#ChatTabCompleter}.
 * </ol>
 *
 * <p>We want to intercept that reply from code, not from a chat UI. The
 * cleanest way is to insert an ephemeral {@link ChannelInboundHandlerAdapter}
 * into the netty pipeline <em>before</em> the {@code packet_handler}
 * (NetworkManager) handler, so we see the decoded
 * {@code SPacketTabComplete}, complete a {@link CompletableFuture}, then
 * still propagate the packet down the pipeline so vanilla/mods see it
 * too. The handler removes itself in {@code finally} regardless of
 * success / timeout.
 *
 * <p>We <strong>bypass</strong> {@link DebugHttpServer#wrap} for this
 * route because {@code wrap} runs the route on the Minecraft client
 * thread with a 2 s timeout. If we blocked the client thread for our
 * own 2 s waiting for the server reply, the game would freeze and the
 * two 2 s timers would race. Instead, this handler runs on the shared
 * {@code sf4debug-http} pool and only dips into the client thread
 * briefly (via {@link DebugHttpServer#runOnClientThread}) to resolve
 * the {@link NetworkManager} reference. Everything after that —
 * pipeline mutation, packet send, blocking on the future — is
 * thread-safe off the client thread.
 */
public final class TabCompleteRoutes {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Gson GSON_PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    /** Max time to wait for the server's {@link SPacketTabComplete} reply. */
    private static final long WAIT_TIMEOUT_MS = 2000L;

    /** Counter used to generate unique pipeline handler names (addBefore
     *  would complain about duplicates on back-to-back tab completes). */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private TabCompleteRoutes() {}

    public static void register(HttpServer server) {
        server.createContext("/tabcomplete", wrapCustom(TabCompleteRoutes::tabcomplete));
    }

    /* -------------------------- HTTP plumbing -------------------------- */

    /**
     * Mirrors {@link DebugHttpServer#wrap} but does <em>not</em> marshal
     * to the client thread — this route blocks waiting for a netty
     * response and must not freeze the game.
     */
    private static HttpHandler wrapCustom(CustomRoute route) {
        return exchange -> {
            try {
                Map<String, String> query = DebugHttpServer.parseQuery(exchange.getRequestURI());
                boolean pretty = query.containsKey("pretty");
                Object result = route.handle(query);
                String body = (pretty ? GSON_PRETTY : GSON).toJson(result);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Throwable t) {
                SF4Debug.LOG.warn("sf4debug /tabcomplete failed", t);
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", t.getClass().getSimpleName());
                err.put("message", String.valueOf(t.getMessage()));
                byte[] bytes = GSON.toJson(err).getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(500, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } catch (IOException ignored) {}
            } finally {
                exchange.close();
            }
        };
    }

    @FunctionalInterface
    private interface CustomRoute {
        Object handle(Map<String, String> query) throws Exception;
    }

    /* -------------------------- Route -------------------------- */

    /**
     * {@code GET /tabcomplete?text=<s>&cursor=<n>&hasTargetBlock=0}
     *
     * <p>Sends {@link CPacketTabComplete} to the current server, waits
     * up to {@value WAIT_TIMEOUT_MS} ms for the {@link SPacketTabComplete}
     * reply, and returns {@code {suggestions: [...]}} (or
     * {@code {timeout: true, suggestions: []}} if the server did not
     * reply in time).
     */
    public static Object tabcomplete(Map<String, String> q) throws Exception {
        String text = q.getOrDefault("text", "");
        // Cursor is reported to the server but vanilla 1.12.2 doesn't
        // actually use it in the packet — we still accept it to keep the
        // API shape forward-compatible. Default: end of text.
        int cursor = DebugHttpServer.parseInt(q.get("cursor"), text.length());
        boolean hasTargetBlock = truthy(q.get("hasTargetBlock"));

        // Resolve the NetworkManager on the client thread so we observe
        // mc.player/mc.getConnection() atomically with the rest of the
        // client. Off-thread reads would see a half-torn connection
        // during login/logout transitions.
        NetworkManager nm = DebugHttpServer.runOnClientThread(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            EntityPlayerSP p = mc.player;
            NetHandlerPlayClient c = mc.getConnection();
            if (p == null || c == null) return null;
            return c.getNetworkManager();
        });

        Map<String, Object> out = new LinkedHashMap<>();
        if (nm == null) {
            out.put("error", "not_in_world");
            out.put("suggestions", Collections.emptyList());
            return out;
        }
        Channel channel = nm.channel();
        if (channel == null || !channel.isActive()) {
            out.put("error", "channel_inactive");
            out.put("suggestions", Collections.emptyList());
            return out;
        }

        final String handlerName = "sf4debug_tabcomplete_" + SEQ.incrementAndGet();
        final CompletableFuture<String[]> fut = new CompletableFuture<>();

        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof SPacketTabComplete) {
                    String[] matches = readMatches((SPacketTabComplete) msg);
                    // complete() is a no-op on an already-completed future,
                    // so repeat SPacketTabComplete packets (unlikely but
                    // possible with modded servers) don't explode here.
                    fut.complete(matches == null ? new String[0] : matches);
                }
                // Propagate to NetHandlerPlayClient so vanilla / mods can
                // also react (e.g. GuiChat's own tab completer, if open).
                super.channelRead(ctx, msg);
            }
        };

        ChannelPipeline pipe = channel.pipeline();
        boolean added = false;
        try {
            // Insert right before the NetworkManager handler
            // ("packet_handler") so we see a fully-decoded Packet object
            // BEFORE NetworkManager consumes it. NetworkManager is a
            // SimpleChannelInboundHandler and does not fireChannelRead,
            // which is why adding AFTER it would never see the packet.
            try {
                pipe.addBefore("packet_handler", handlerName, handler);
                added = true;
            } catch (Throwable t) {
                // packet_handler missing (shouldn't happen on 1.12.2 but
                // some coremods rename it). Bail cleanly rather than
                // adding in the wrong spot and silently dropping matches.
                out.put("error", "pipeline_missing_packet_handler");
                out.put("suggestions", Collections.emptyList());
                return out;
            }

            // Send the query. NetworkManager.sendPacket marshals itself
            // onto the netty event loop if we're not already on it, so
            // this is thread-safe to call from the HTTP thread.
            nm.sendPacket(new CPacketTabComplete(text, null, hasTargetBlock));

            String[] matches;
            try {
                matches = fut.get(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                out.put("timeout", true);
                out.put("suggestions", Collections.emptyList());
                out.put("text", text);
                out.put("cursor", cursor);
                return out;
            }

            out.put("suggestions", Arrays.asList(matches == null ? new String[0] : matches));
            out.put("count", matches == null ? 0 : matches.length);
            out.put("text", text);
            out.put("cursor", cursor);
            return out;
        } finally {
            if (added) {
                // Always remove the handler, even on timeout/exception.
                // Pipeline mutations happen on the netty event loop, so
                // remove() is a no-op/ignored if we're already detached.
                try { pipe.remove(handlerName); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Read the matches array. The MCP name is {@code getMatches}; the
     * SRG name is {@code func_186949_a}. When this mod is compiled
     * against MCP mappings and then reobfuscated via ForgeGradle's
     * {@code reobfJar}, the bytecode ends up referencing the SRG name,
     * which matches the runtime jar. The reflection fallback only kicks
     * in if somehow neither resolves (e.g. a coremod replaced the
     * packet class entirely).
     */
    private static String[] readMatches(SPacketTabComplete pkt) {
        try {
            return pkt.getMatches();
        } catch (Throwable ignored) {
            // try reflection both ways.
        }
        for (String name : new String[] { "func_186949_a", "getMatches" }) {
            try {
                Method m = SPacketTabComplete.class.getDeclaredMethod(name);
                m.setAccessible(true);
                Object v = m.invoke(pkt);
                if (v instanceof String[]) return (String[]) v;
            } catch (Throwable ignored) {
                // try next name
            }
        }
        return new String[0];
    }

    private static boolean truthy(String s) {
        if (s == null) return false;
        switch (s.toLowerCase()) {
            case "1": case "true": case "yes": case "on":
                return true;
            default:
                return false;
        }
    }
}
