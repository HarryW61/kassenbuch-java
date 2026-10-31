// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny REST API over the legacy Kassenbuch files. No framework, no dependencies. */
public final class Api implements com.sun.net.httpserver.HttpHandler {

    private final Store store;

    public Api(Store store) { this.store = store; }

    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        Map<String, String> q = query(ex.getRequestURI());
        byte[] raw = ex.getRequestBody().readAllBytes();
        String body = new String(raw, StandardCharsets.UTF_8);

        try {
            if (path.equals("/api/meta")) {
                json(ex, 200, store.meta());
                return;
            }

            if (path.equals("/api/bookings") && method.equals("GET")) {
                json(ex, 200, store.bookingMaps(q.get("period")));
                return;
            }

            if (path.equals("/api/bookings") && method.equals("POST")) {
                json(ex, 201, store.add(parseBody(body)));
                return;
            }

            if (path.startsWith("/api/bookings/")) {
                int nr = Integer.parseInt(path.substring("/api/bookings/".length()));
                if (method.equals("PUT")) {
                    json(ex, 200, store.update(nr, parseBody(body)));
                    return;
                }
                if (method.equals("DELETE")) {
                    boolean ok = store.delete(nr);
                    json(ex, ok ? 200 : 404, Map.of("ok", ok, "nr", nr));
                    return;
                }
                json(ex, 405, error("Methode nicht erlaubt"));
                return;
            }

            switch (path) {
                case "/api/summary" -> { json(ex, 200, store.summary(q.get("period"))); return; }
                case "/api/ust"     -> { json(ex, 200, store.ust(q.get("period"))); return; }
                case "/api/monthly" -> { json(ex, 200, store.monthly()); return; }
                case "/api/jabu"    -> { json(ex, 200, store.jabu()); return; }
                case "/api/yearend" -> {
                    if (!method.equals("POST")) { json(ex, 405, error("POST erforderlich")); return; }
                    json(ex, 200, store.yearEnd());
                    return;
                }
                case "/api/backup" -> {
                    if (!method.equals("POST")) { json(ex, 405, error("POST erforderlich")); return; }
                    json(ex, 200, store.createBackup());
                    return;
                }
                case "/api/stamm" -> {
                    if (!method.equals("PUT")) { json(ex, 405, error("PUT erforderlich")); return; }
                    json(ex, 200, store.updateStamm(parseBody(body)));
                    return;
                }
                case "/api/export" -> {
                    String fmt = q.getOrDefault("fmt", "json");
                    String period = q.get("period");
                    switch (fmt) {
                        case "csv" -> download(ex, 200, "text/csv; charset=utf-8",
                                Store.exportFileName("csv", period), store.exportCsv(period).getBytes(StandardCharsets.UTF_8));
                        case "dbf" -> download(ex, 200, "application/x-dbase", "KABU.dbf", store.exportDbf());
                        default -> download(ex, 200, "application/json; charset=utf-8",
                                Store.exportFileName("json", period), store.exportJson(period).getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }
                case "/api/import" -> {
                    if (!method.equals("POST")) { json(ex, 405, error("POST erforderlich")); return; }
                    String fmt = q.get("fmt") == null ? detectFormat(raw) : q.get("fmt");
                    String mode = q.getOrDefault("mode", "append");
                    json(ex, 200, store.importBookings(fmt, raw, mode));
                    return;
                }
                default -> {}
            }

            if (path.startsWith("/api/report/")) {
                String kind = path.substring("/api/report/".length());
                String period = q.get("period");
                String fmt = q.getOrDefault("fmt", "txt");
                // an empty period means "alle Perioden": the report names the booking year
                Reports r = new Reports(store.filter(period), store.stamm(), store.bookingYear());
                switch (fmt) {
                    case "pdf"  -> download(ex, 200, "application/pdf",
                            kind + slug(period) + ".pdf", r.pdfReport(kind, period));
                    case "dos", "cp437" -> download(ex, 200, "text/plain; charset=IBM437",
                            kind + slug(period) + ".txt", r.dosReport(kind, period));
                    default -> text(ex, 200, r.report(kind, period));
                }
                return;
            }

            json(ex, 404, error("Unbekannte Route: " + path));
        } catch (Store.ValidationException e) {
            json(ex, 400, error(e.getMessage()));
        } catch (NumberFormatException e) {
            json(ex, 400, error("Ungültige Nummer: " + e.getMessage()));
        } catch (Exception e) {
            json(ex, 500, error("Fehler: " + e.getMessage()));
        }
    }

    private static String slug(String period) {
        return period == null || period.isEmpty() ? "" : "-" + period;
    }

    /** Recognise an uploaded file by its content (JSON braces, else CSV). */
    static String detectFormat(byte[] raw) {
        String s = new String(raw, StandardCharsets.UTF_8).stripLeading();
        if (s.startsWith("\uFEFF")) s = s.substring(1).stripLeading();
        if (s.startsWith("[") || s.startsWith("{")) return "json";
        return "csv";
    }

    private static void download(HttpExchange ex, int status, String contentType, String filename, byte[] payload)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=" + filename);
        ex.sendResponseHeaders(status, payload.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(payload); }
    }

    private static Map<String, Object> parseBody(String body) {
        if (body == null || body.isBlank()) return new LinkedHashMap<>();
        Object o = Json.parse(body);
        if (!(o instanceof Map<?, ?> m)) throw new Store.ValidationException("JSON-Objekt erwartet");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = uri.getQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) out.put(pair.substring(0, i), java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static void json(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] b = Json.encode(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static void text(HttpExchange ex, int status, String payload) throws IOException {
        byte[] b = payload.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
