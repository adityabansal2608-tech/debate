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

public class debatecoach {

    static final String GROQ_API_KEY = System.getenv("GROQ_API_KEY") != null ? 
            System.getenv("GROQ_API_KEY") : "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";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/critique", debatecoach::handleCritique);
        server.setExecutor(null);
        server.start();
        System.out.println("Server running at http://localhost:8080/critique");
    }

    static void handleCritique(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
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
            e.printStackTrace();
            String error = "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
            byte[] errorBytes = error.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, errorBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(errorBytes);
            os.close();
        }
    }

    static String getCritiqueFromGroq(String argument) throws Exception {
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

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + GROQ_API_KEY)
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