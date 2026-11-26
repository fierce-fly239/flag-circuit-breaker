import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.concurrent.Executors;

// Demo shop. The flag "new_checkout" (read from Flagsmith) switches /checkout to a new,
// buggy version. /metrics reports how many checkouts failed in the last few seconds.
public class ShopApp {

    static final String FLAG_NAME = "new_checkout";

    static HashMap<String, String> settings = new HashMap<>();

    // Written by the flag-reading thread, read by the request threads.
    // volatile = every thread always sees the newest value.
    static volatile boolean flagOn = false;
    static volatile long flagOnSince = 0; // time (ms) the flag was last seen turning on

    // Times (ms) of recent checkouts and recent failures, oldest at the front.
    static ArrayDeque<Long> requestTimes = new ArrayDeque<>();
    static ArrayDeque<Long> errorTimes = new ArrayDeque<>();

    static long windowMs;
    static double failRate;
    static double threshold; // shown as a line on the page; the breaker uses the same setting

    // Set when the breaker reports a trip, cleared when the flag is switched on again.
    static volatile String lastTrip = null;

    public static void main(String[] args) throws IOException {
        loadEnv(".env");
        windowMs = Long.parseLong(setting("WINDOW_SECONDS", "10")) * 1000;
        failRate = Double.parseDouble(setting("NEW_CHECKOUT_FAIL_RATE", "0.5"));
        threshold = Double.parseDouble(setting("ERROR_THRESHOLD", "0.3"));

        startFlagReader();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/checkout", ShopApp::checkout);
        server.createContext("/metrics", ShopApp::metrics);
        server.createContext("/breaker-event", ShopApp::breakerEvent);
        server.createContext("/", ShopApp::page);
        server.setExecutor(Executors.newFixedThreadPool(4)); // up to 4 requests at the same time
        server.start();
        System.out.println("Shop running on http://localhost:8080  (window " + windowMs / 1000
                + " s, new checkout fails " + (int) (failRate * 100) + "% of the time)");
    }

    // ---------- Settings from .env ----------

    static void loadEnv(String file) throws IOException {
        for (String line : Files.readAllLines(Path.of(file))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
            int eq = line.indexOf('=');
            settings.put(line.substring(0, eq), line.substring(eq + 1));
        }
    }

    static String setting(String name, String fallback) {
        String value = settings.get(name);
        if (value == null || value.isEmpty()) return fallback;
        return value;
    }

    // ---------- Reading the flag from Flagsmith ----------

    // A background thread asks Flagsmith every 2 seconds, so checkouts never wait on Flagsmith.
    static void startFlagReader() {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(setting("FLAGSMITH_URL", "http://localhost:8000") + "/api/v1/flags/"))
                .header("X-Environment-Key", setting("FLAGSMITH_ENV_KEY", ""))
                .build();

        Thread reader = new Thread(() -> {
            while (true) {
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        boolean nowOn = readEnabled(response.body());
                        if (nowOn && !flagOn) {
                            flagOnSince = System.currentTimeMillis();
                            lastTrip = null; // a new release starts with a clear banner
                            System.out.println("Flag " + FLAG_NAME + " is now ON");
                        } else if (!nowOn && flagOn) {
                            System.out.println("Flag " + FLAG_NAME + " is now OFF");
                        }
                        flagOn = nowOn;
                    } else {
                        System.out.println("Flagsmith answered " + response.statusCode() + ", keeping last flag value");
                    }
                } catch (Exception e) {
                    // Flagsmith down: keep the last known value instead of crashing the shop.
                    System.out.println("Could not reach Flagsmith (" + e.getMessage() + "), keeping last flag value");
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        reader.setDaemon(true); // this thread stops when the program stops
        reader.start();
    }

    // Finds "enabled": for our flag in the JSON text.
    // shortcut: plain text search, not a real JSON parser; fine for Flagsmith's fixed format, swap for a JSON library if the format changes.
    static boolean readEnabled(String json) {
        int at = json.indexOf("\"name\":\"" + FLAG_NAME + "\"");
        if (at == -1) return false; // flag missing = treat as off
        int enabledAt = json.indexOf("\"enabled\":", at);
        if (enabledAt == -1) return false;
        return json.startsWith("true", enabledAt + "\"enabled\":".length());
    }

    // ---------- Routes ----------

    static void checkout(HttpExchange exchange) throws IOException {
        boolean failed;
        if (flagOn) {
            // NEW checkout: the planted bug makes it fail often.
            failed = Math.random() < failRate;
        } else {
            // OLD checkout: works every time.
            failed = false;
        }
        record(failed);

        if (failed) {
            send(exchange, 500, "{\"ok\":false,\"checkout\":\"new\",\"error\":\"payment step crashed\"}");
        } else {
            send(exchange, 200, "{\"ok\":true,\"checkout\":\"" + (flagOn ? "new" : "old") + "\"}");
        }
    }

    static void metrics(HttpExchange exchange) throws IOException {
        int requests;
        int errors;
        synchronized (ShopApp.class) {
            dropOld();
            requests = requestTimes.size();
            errors = errorTimes.size();
        }
        double rate = requests == 0 ? 0 : (double) errors / requests;
        long onFor = flagOn ? (System.currentTimeMillis() - flagOnSince) / 1000 : 0;

        String json = "{\"flag\":\"" + FLAG_NAME + "\""
                + ",\"enabled\":" + flagOn
                + ",\"flagOnForSeconds\":" + onFor
                + ",\"windowSeconds\":" + windowMs / 1000
                + ",\"requests\":" + requests
                + ",\"errors\":" + errors
                + ",\"errorRate\":" + String.format("%.2f", rate)
                + ",\"threshold\":" + threshold
                + ",\"lastTrip\":" + (lastTrip == null ? "null" : lastTrip)
                + "}";
        send(exchange, 200, json);
    }

    // The breaker POSTs the error rate it saw (a plain number like 0.43) when it trips.
    static void breakerEvent(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes()).trim();
        double rateBefore;
        try {
            rateBefore = Double.parseDouble(body);
        } catch (NumberFormatException e) {
            send(exchange, 400, "{\"error\":\"body must be a number\"}");
            return;
        }
        String time = java.time.LocalTime.now().withNano(0).toString();
        lastTrip = "{\"time\":\"" + time + "\",\"flag\":\"" + FLAG_NAME + "\",\"rateBefore\":" + rateBefore + "}";
        System.out.println("Breaker reported a trip at " + time);
        send(exchange, 200, "{\"ok\":true}");
    }

    // Serves the live page. The file is read on every request, so edits show on refresh.
    static void page(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            send(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }
        byte[] html = Files.readAllBytes(Path.of("web/index.html"));
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(html);
        }
    }

    // ---------- The error window ----------

    // synchronized = only one thread at a time may change the two queues.
    static synchronized void record(boolean failed) {
        long now = System.currentTimeMillis();
        requestTimes.addLast(now);
        if (failed) errorTimes.addLast(now);
        dropOld();
    }

    // Removes times older than the window from the front of both queues.
    static synchronized void dropOld() {
        long oldestAllowed = System.currentTimeMillis() - windowMs;
        while (!requestTimes.isEmpty() && requestTimes.peekFirst() < oldestAllowed) requestTimes.pollFirst();
        while (!errorTimes.isEmpty() && errorTimes.peekFirst() < oldestAllowed) errorTimes.pollFirst();
    }

    // ---------- Helper ----------

    static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
