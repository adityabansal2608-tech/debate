import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class debatecoach {

    // Securely read API Key from Environment Variable set in Railway dashboard
    static final String GROQ_API_KEY = System.getenv("GROQ_API_KEY");

    // --- Simple in-memory rate limiter: max 10 requests/minute per IP ---
    static final int MAX_REQUESTS_PER_WINDOW = 10;
    static final long WINDOW_MILLIS = 60_000;
    static final Map<String, long[]> requestLog = new ConcurrentHashMap<>(); // ip -> [windowStart, count]

    static boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        long[] entry = requestLog.computeIfAbsent(ip, k -> new long[]{now, 0});
        synchronized (entry) {
            if (now - entry[0] > WINDOW_MILLIS) {
                entry[0] = now;
                entry[1] = 0;
            }
            entry[1]++;
            return entry[1] > MAX_REQUESTS_PER_WINDOW;
        }
    }

    public static void main(String[] args) throws Exception {
        // Read dynamic PORT assigned by Railway (fallback to 8080 for local dev)
        String envPort = System.getenv("PORT");
        int port = (envPort != null) ? Integer.parseInt(envPort) : 8080;

        // Bind to 0.0.0.0 to accept external cloud requests
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/critique", debatecoach::handleCritique);
        server.setExecutor(null);
        server.start();
        System.out.println("Server running on port " + port);
    }

    static void handleCritique(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "https://logiclensproject.netlify.app");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (isRateLimited(clientIp)) {
            String error = "{\"error\": \"Too many requests. Please wait a minute and try again.\"}";
            byte[] errorBytes = error.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, errorBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(errorBytes);
            os.close();
            return;
        }

        try {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String argument = extractArgument(requestBody);

            String groqResponse = getCritiqueFromGroq(argument);

            byte[] responseBytes = groqResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);

            OutputStream os = exchange.getResponseBody();
            os.write(responseBytes);
            os.close();
        } catch (Exception e) {
            // Log full detail server-side only; never leak internals to the client
            e.printStackTrace();
            String error = "{\"error\": \"Something went wrong processing your request. Please try again.\"}";
            byte[] errorBytes = error.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, errorBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(errorBytes);
            os.close();
        }
    }

    static String getCritiqueFromGroq(String argument) throws Exception {
        if (GROQ_API_KEY == null || GROQ_API_KEY.isEmpty()) {
            throw new RuntimeException("GROQ_API_KEY environment variable is not set!");
        }

        String systemPrompt = "You are an expert debate coach. Analyze the argument and output JSON with keys: 'score' (1-10), 'fallacies' (array of {quote, type, explanation}), 'weak_points' (array), and 'strong_points' (array).";

        String jsonPayload = """
            {
              "model": "llama-3.3-70b-versatile",
              "response_format": { "type": "json_object" },
              "messages": [
                {"role": "system", "content": "%s"},
                {"role": "user", "content": "%s"}
              ],
              "temperature": 0.2
            }
            """.formatted(escapeJson(systemPrompt), escapeJson(argument));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + GROQ_API_KEY)
                .timeout(java.time.Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Groq API Error: " + response.body());
        }

        return response.body();
    }

    private static String extractArgument(String json) {
        int idx = json.indexOf("\"argument\":");
        if (idx != -1) {
            String val = json.substring(idx + 11).trim();
            if (val.startsWith("\"")) {
                val = val.substring(1);
                int end = val.lastIndexOf("\"");
                if (end > 0) val = val.substring(0, end);
            }
            return val;
        }
        return json;
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
