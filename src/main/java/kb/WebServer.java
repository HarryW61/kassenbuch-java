// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/** Serves the static single page app and mounts the JSON API under /api. */
public final class WebServer {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("KB_PORT", "8090"));
        Path dataDir = Path.of(System.getenv().getOrDefault("KB_DATA", "data"));
        Path webDir = Path.of(System.getenv().getOrDefault("KB_WEB", "web"));

        Store store = new Store(dataDir);
        Api api = new Api(store);

        HttpServer server = HttpServer.create();
        try {
            server.bind(new InetSocketAddress(port), 0);
        } catch (java.net.BindException e) {
            System.err.println("Port " + port + " ist bereits belegt - es laeuft schon ein Kassenbuch.");
            System.err.println("Beenden mit:  pkill -f kb.WebServer     oder ein anderer Port:  KB_PORT=1234 ./serve.sh");
            System.exit(1);
        }
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) { api.handle(ex); return; }
            serveStatic(ex, webDir, path);
        });
        server.setExecutor(null);
        System.out.println("Kassenbuch (legacy KABU.DBF in " + dataDir + ") -> http://localhost:" + port);
        System.out.println("""
            Hinweis: dieses Programm ist Hobby-Software ohne Garantie jeglicher Art. Buchungs-
            und Steuerdaten für eine Steuererklärung müssen von Ihnen selbst geprüft werden;
            Nutzung geschieht auf eigene Verantwortung. Kopieren Sie KABU.DBF, JABU.DBF und
            STAMM.MEM, bevor das Programm zum ersten Mal in Ihre Dateien schreibt.
            Details: NOTICE.txt""");
        server.start();
    }

    private static void serveStatic(HttpExchange ex, Path webDir, String path) throws IOException {
        if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
        String rel = path.substring(1);
        if (rel.contains("..")) { ex.sendResponseHeaders(400, -1); return; }
        Path file = webDir.resolve(rel);
        if (!Files.isRegularFile(file)) {
            byte[] b = ("Not found: " + rel).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
            return;
        }
        byte[] b = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(rel) + "; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().close();
    }

    private static String contentType(String file) {
        String lower = file.toLowerCase();
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js")) return "text/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "text/plain";
    }
}
