package com.telethon.sf4debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Media routes: currently just {@code /screenshot}. Remote-safe — nothing
 * here writes anything to disk or leaks a filesystem path. The PNG
 * payload is always returned inline in the HTTP response (either as raw
 * bytes with {@code Content-Type: image/png}, or as a base64 string
 * embedded in the JSON body).
 *
 * <p>This route deliberately bypasses {@link DebugHttpServer#wrap}: the
 * {@code return=binary} mode needs to write raw bytes rather than JSON,
 * and we also want to keep the client-thread work minimal (framebuffer
 * readback only) so that Java-side scaling and PNG encoding happen off
 * the Minecraft tick thread.
 */
public final class MediaRoutes {

    private static final Gson GSON        = new GsonBuilder().serializeNulls().create();
    private static final Gson GSON_PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private MediaRoutes() {}

    public static void register(HttpServer server) {
        server.createContext("/screenshot", new ScreenshotHandler());
    }

    /* ========================= /screenshot ========================= */

    private static final class ScreenshotHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI());
                boolean pretty = query.containsKey("pretty");

                // --- validate format (png only) ---
                String format = query.getOrDefault("format", "png").toLowerCase();
                if (!"png".equals(format)) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", "unsupported format");
                    err.put("message", "only 'png' is supported (got '" + format + "')");
                    sendJson(exchange, 400, pretty, err);
                    return;
                }

                // --- parse scale (0 < s <= 1.0) ---
                double scale = parseDouble(query.get("scale"), 1.0);
                if (!(scale > 0.0)) scale = 1.0; // NaN / <=0 -> 1
                if (scale > 1.0)    scale = 1.0;
                final double scaleF = scale;

                // --- parse return mode ---
                String mode = query.getOrDefault("return", "base64").toLowerCase();
                if (!"base64".equals(mode) && !"binary".equals(mode)) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", "unsupported return mode");
                    err.put("message", "only 'base64' or 'binary' (got '" + mode + "')");
                    sendJson(exchange, 400, pretty, err);
                    return;
                }
                boolean binary = "binary".equals(mode);

                // --- framebuffer read on the client thread ---
                BufferedImage img;
                try {
                    img = DebugHttpServer.runOnClientThread(() -> {
                        Minecraft mc = Minecraft.getMinecraft();
                        if (mc == null) return null;
                        Framebuffer fb = mc.getFramebuffer();
                        int w = mc.displayWidth;
                        int h = mc.displayHeight;
                        if (fb == null || w <= 0 || h <= 0) return null;
                        return ScreenShotHelper.createScreenshot(w, h, fb);
                    });
                } catch (Throwable t) {
                    SF4Debug.LOG.warn("sf4debug /screenshot client-thread read failed", t);
                    sendErrorJson(exchange, t);
                    return;
                }

                if (img == null) {
                    // framebuffer unavailable (minimised, pre-init, headless) — 200 + warning.
                    Map<String, Object> warn = new LinkedHashMap<>();
                    warn.put("warning", "framebuffer unavailable");
                    sendJson(exchange, 200, pretty, warn);
                    return;
                }

                // --- scale down in Java (off the client thread) ---
                if (scaleF < 1.0) {
                    int newW = Math.max(1, (int) Math.round(img.getWidth()  * scaleF));
                    int newH = Math.max(1, (int) Math.round(img.getHeight() * scaleF));
                    BufferedImage dst = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = dst.createGraphics();
                    try {
                        g.drawImage(img, 0, 0, newW, newH, null);
                    } finally {
                        g.dispose();
                    }
                    img = dst;
                }

                // --- encode as PNG ---
                ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
                ImageIO.write(img, "png", baos);
                byte[] png = baos.toByteArray();

                if (binary) {
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    exchange.getResponseHeaders().set("Content-Disposition",
                            "inline; filename=\"sf4debug.png\"");
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(200, png.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(png);
                    }
                } else {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("width",       img.getWidth());
                    out.put("height",      img.getHeight());
                    out.put("png_base64",  Base64.getEncoder().encodeToString(png));
                    sendJson(exchange, 200, pretty, out);
                }
            } catch (Throwable t) {
                SF4Debug.LOG.warn("sf4debug /screenshot failed", t);
                try { sendErrorJson(exchange, t); } catch (IOException ignored) {}
            } finally {
                exchange.close();
            }
        }
    }

    /* ========================= helpers ========================= */

    /** Duplicate of {@link DebugHttpServer#parseQuery} — kept local to
     *  avoid depending on package-private helpers more than necessary. */
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

    private static double parseDouble(String s, double fallback) {
        if (s == null) return fallback;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; }
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

    /** Mirrors {@link DebugHttpServer#wrap}'s 500-error path. */
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
