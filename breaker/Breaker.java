import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;

// Flag Circuit Breaker. Every few seconds it reads the shop's /metrics.
// If the flag was switched on recently and errors jump over the threshold,
// it switches the flag OFF through the Flagsmith admin API and prints an alert.
public class Breaker {

    static HashMap<String, String> settings = new HashMap<>();
    static HttpClient client = HttpClient.newHttpClient();

    static String flagsmithUrl;
    static String envKey;
    static String adminKey;
    static String flagName;

    public static void main(String[] args) throws Exception {
        loadEnv(".env");
        flagsmithUrl = setting("FLAGSMITH_URL", "http://localhost:8000");
        envKey = setting("FLAGSMITH_ENV_KEY", "");
        adminKey = setting("FLAGSMITH_ADMIN_KEY", "");
        flagName = setting("FLAG_NAME", "new_checkout");
        String shopUrl = setting("SHOP_URL", "http://localhost:8080");
        int checkEvery = Integer.parseInt(setting("CHECK_EVERY_SECONDS", "2"));
        double threshold = Double.parseDouble(setting("ERROR_THRESHOLD", "0.3"));
        int minRequests = Integer.parseInt(setting("MIN_REQUESTS", "10"));
        int watchSeconds = Integer.parseInt(setting("WATCH_SECONDS", "120"));
        int windowSeconds = Integer.parseInt(setting("WINDOW_SECONDS", "10"));

        if (envKey.isEmpty() || adminKey.isEmpty()) {
            System.out.println("Missing FLAGSMITH_ENV_KEY or FLAGSMITH_ADMIN_KEY in .env. See SETUP.md.");
            return;
        }

        // Find which Flagsmith record to switch off, once, before watching.
        int featureStateId = findFeatureStateId();
        System.out.println("Breaker watching flag '" + flagName + "' (feature state " + featureStateId + ")");
        System.out.println("Trips if: flag on for at most " + watchSeconds + " s, at least " + minRequests
                + " requests, error rate over " + percent(threshold) + ". Checking every " + checkEvery + " s.");

        while (true) {
            try {
                String metrics = get(shopUrl + "/metrics", "", "");
                boolean enabled = metrics.contains("\"enabled\":true");
                double onFor = numberAfter(metrics, "flagOnForSeconds");
                double requests = numberAfter(metrics, "requests");
                double rate = numberAfter(metrics, "errorRate");

                System.out.println(now() + "  flag " + (enabled ? "ON " : "OFF") + "  requests " + (int) requests
                        + "  error rate " + percent(rate));

                boolean recentlyOn = enabled && onFor <= watchSeconds;
                if (recentlyOn && requests >= minRequests && rate > threshold) {
                    trip(featureStateId, rate, threshold, shopUrl, windowSeconds);
                }
            } catch (IOException e) {
                System.out.println(now() + "  could not reach the shop (" + e.getMessage() + ")");
            }
            Thread.sleep(checkEvery * 1000L);
        }
    }

    // ---------- Tripping the breaker ----------

    static void trip(int featureStateId, double rateBefore, double threshold, String shopUrl, int windowSeconds)
            throws Exception {
        String tripTime = now();
        int status = switchFlagOff(featureStateId);
        if (status != 200) {
            System.out.println(tripTime + "  TRIED to switch the flag off but Flagsmith answered " + status);
            return;
        }

        System.out.println();
        System.out.println("==================== BREAKER TRIPPED ====================");
        System.out.println("  Time:        " + tripTime);
        System.out.println("  Flag:        " + flagName + "  ->  switched OFF in Flagsmith");
        System.out.println("  Error rate:  " + percent(rateBefore) + "  (threshold " + percent(threshold) + ")");
        System.out.println("=========================================================");

        // Wait one full error window (plus the shop's 2 s flag check), then measure again.
        Thread.sleep((windowSeconds + 3) * 1000L);
        String after = get(shopUrl + "/metrics", "", "");
        System.out.println("  Error rate " + (windowSeconds + 3) + " s later: " + percent(numberAfter(after, "errorRate"))
                + "  (" + (int) numberAfter(after, "requests") + " requests in the last " + windowSeconds + " s)");
        System.out.println();
    }

    // PATCH .../featurestates/{id}/ with {"enabled": false}, using the ADMIN key.
    static int switchFlagOff(int featureStateId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(flagsmithUrl + "/api/v1/environments/" + envKey + "/featurestates/" + featureStateId + "/"))
                .header("Authorization", "Api-Key " + adminKey)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"enabled\": false}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    // ---------- Finding the flag in Flagsmith ----------

    // Step 1: the flags list (environment key) gives the flag's feature id.
    // Step 2: the admin API turns the feature id into the feature-state id we can PATCH.
    // shortcut: plain text search, not a real JSON parser; fine for Flagsmith's fixed format, swap for a JSON library if the format changes.
    static int findFeatureStateId() throws Exception {
        String flags = get(flagsmithUrl + "/api/v1/flags/", "X-Environment-Key", envKey);
        int nameAt = flags.indexOf("\"name\":\"" + flagName + "\"");
        if (nameAt == -1) throw new IllegalStateException("Flag '" + flagName + "' not found in Flagsmith");
        int featureAt = flags.lastIndexOf("\"feature\":{", nameAt);
        int featureId = (int) numberAfter(flags.substring(featureAt), "id");

        String states = get(flagsmithUrl + "/api/v1/environments/" + envKey + "/featurestates/?feature=" + featureId,
                "Authorization", "Api-Key " + adminKey);
        int resultsAt = states.indexOf("\"results\":[");
        if (resultsAt == -1) throw new IllegalStateException("Admin API did not answer as expected. Check FLAGSMITH_ADMIN_KEY.");
        return (int) numberAfter(states.substring(resultsAt), "id");
    }

    // ---------- Small helpers ----------

    // GET a URL and return the body. Pass an empty header name for no header.
    static String get(String url, String headerName, String headerValue) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url));
        if (!headerName.isEmpty()) builder.header(headerName, headerValue);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException(url + " answered " + response.statusCode());
        return response.body();
    }

    // Reads the number written after "key": in a JSON text. Returns 0 if the key is missing.
    static double numberAfter(String json, String key) {
        String marker = "\"" + key + "\":";
        int at = json.indexOf(marker);
        if (at == -1) return 0;
        int start = at + marker.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) {
            end++;
        }
        return Double.parseDouble(json.substring(start, end));
    }

    static String percent(double rate) {
        return Math.round(rate * 100) + "%";
    }

    static String now() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

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
}
