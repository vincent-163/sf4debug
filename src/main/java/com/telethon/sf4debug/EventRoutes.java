package com.telethon.sf4debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * HTTP routing for the long-poll {@code /events} endpoint.
 *
 * <p>Unlike the snapshot routes in {@link DebugHttpServer}, this
 * handler runs entirely on the HTTP executor thread. It must NOT
 * marshal the long-poll wait through {@code runOnClientThread}, as
 * that would block the Minecraft client thread for up to 60s and
 * freeze the game. The event queue itself is thread-safe (see
 * {@link EventStream}), so reading it off-thread is fine.
 */
public final class EventRoutes {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Gson GSON_PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private EventRoutes() {}

    /** Wire up the {@code /events} context on {@code server}. */
    public static void register(HttpServer server) {
        server.createContext("/events", new EventsHandler());
    }

    /**
     * Handler for {@code GET /events?since=&wait=&types=&limit=&pretty=}.
     * Blocks the HTTP thread for up to the clamped wait duration.
     */
    private static final class EventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Map<String, String> query = DebugHttpServer.parseQuery(exchange.getRequestURI());
                boolean pretty = query.containsKey("pretty");

                long since = parseLong(query.get("since"), 0L);
                if (since < 0L) since = 0L;

                int waitSeconds = parseIntLocal(query.get("wait"), EventStream.defaultWaitSeconds());
                if (waitSeconds < 0) waitSeconds = 0;
                if (waitSeconds > EventStream.maxWaitSeconds()) waitSeconds = EventStream.maxWaitSeconds();
                int waitMs = waitSeconds * 1000;

                int limit = parseIntLocal(query.get("limit"), EventStream.defaultLimit());
                if (limit <= 0) limit = 1;
                if (limit > EventStream.maxLimit()) limit = EventStream.maxLimit();

                Set<String> types = null;
                String typesCsv = query.get("types");
                if (typesCsv != null && !typesCsv.isEmpty()) {
                    types = new HashSet<>();
                    for (String t : typesCsv.split(",")) {
                        String trimmed = t.trim();
                        if (!trimmed.isEmpty()) types.add(trimmed);
                    }
                    if (types.isEmpty()) types = null;
                }

                Map<String, Object> result = EventStream.poll(since, waitMs, types, limit);
                writeJson(exchange, 200, result, pretty);
            } catch (InterruptedException ie) {
                // Preserve the interrupt so the HTTP executor thread can
                // respond to shutdown; still send a clean 500 to the caller.
                Thread.currentThread().interrupt();
                writeError(exchange, ie);
            } catch (Throwable t) {
                try {
                    if (SF4Debug.LOG != null) SF4Debug.LOG.warn("sf4debug /events failed", t);
                } catch (Throwable ignored) {}
                writeError(exchange, t);
            } finally {
                exchange.close();
            }
        }
    }

    /* ----------------------- JSON helpers (mirror DebugHttpServer.wrap) ----------------------- */

    private static void writeJson(HttpExchange exchange, int status, Object body, boolean pretty) throws IOException {
        byte[] bytes = (pretty ? GSON_PRETTY : GSON).toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void writeError(HttpExchange exchange, Throwable t) {
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
        } catch (IOException ignored) {
            // Nothing sensible to do if the connection is already gone.
        }
    }

    /* ----------------------- query parsers ----------------------- */

    private static int parseIntLocal(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
    }
}
