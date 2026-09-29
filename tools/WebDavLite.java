import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;

/**
 * Minimal local WebDAV for testing Board sync: GET / HEAD / PUT / MKCOL / PROPFIND.
 * Test only: no auth, no locking. Used so the whole A -> WebDAV -> B flow can be
 * exercised on one machine. Unlike .NET HttpListener, this accepts the MKCOL verb.
 */
public class WebDavLite {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path root = Paths.get(args.length > 1 ? args[1] : ".").toAbsolutePath().normalize();
        Files.createDirectories(root);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> handle(exchange, root));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("webdav-lite(java) on http://127.0.0.1:" + port + " root=" + root);
    }

    private static void handle(HttpExchange ex, Path root) {
        String method = ex.getRequestMethod();
        String rel = "";
        try {
            String raw = ex.getRequestURI().getPath();
            rel = URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).replaceAll("^/+", "");
            Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) {
                send(ex, 403, "forbidden");
                return;
            }
            int code;
            switch (method) {
                case "GET": {
                    if (Files.isRegularFile(target)) {
                        byte[] data = Files.readAllBytes(target);
                        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                        ex.sendResponseHeaders(200, data.length);
                        try (OutputStream out = ex.getResponseBody()) {
                            out.write(data);
                        }
                        code = 200;
                    } else {
                        send(ex, 404, "not found");
                        code = 404;
                    }
                    break;
                }
                case "HEAD": {
                    code = Files.exists(target) ? 200 : 404;
                    ex.sendResponseHeaders(code, -1);
                    break;
                }
                case "PUT": {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    try (InputStream in = ex.getRequestBody()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    ex.sendResponseHeaders(201, -1);
                    code = 201;
                    break;
                }
                case "MKCOL": {
                    Files.createDirectories(target);
                    ex.sendResponseHeaders(201, -1);
                    code = 201;
                    break;
                }
                case "PROPFIND": {
                    ex.sendResponseHeaders(207, -1);
                    code = 207;
                    break;
                }
                default: {
                    send(ex, 405, "method not allowed");
                    code = 405;
                }
            }
            System.out.println(method + " /" + rel + " -> " + code);
        } catch (Throwable t) {
            try {
                send(ex, 500, String.valueOf(t));
            } catch (Throwable ignored) {
                // response already committed
            }
            System.out.println("ERR " + method + " /" + rel + " : " + t);
        } finally {
            try {
                ex.close();
            } catch (Throwable ignored) {
                // already closed
            }
        }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
