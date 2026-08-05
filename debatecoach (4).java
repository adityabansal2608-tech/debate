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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class debatecoach {

    // Securely read API Key from Environment Variable set in Railway dashboard
    static final String GROQ_API_KEY = System.getenv("GROQ_API_KEY");

    // Multi-agent debate: two different Groq models argue, a third judges.
    // Note: llama-3.3-70b-versatile (used by /critique above) was deprecated
    // by Groq in June 2026. These three are current as of this build.
    static final String DEBATE_AGENT_A_MODEL = "openai/gpt-oss-120b";
    static final String DEBATE_AGENT_B_MODEL = "qwen/qwen3.6-27b";
    static final String DEBATE_JUDGE_MODEL = "openai/gpt-oss-120b";

    // Live human-vs-AI debate: one strong model, kept consistent turn to turn.
    static final String ARGUE_MODEL = "openai/gpt-oss-120b";

    public static void main(String[] args) throws Exception {
        // Read dynamic PORT assigned by Railway (fallback to 8080 for local dev)
        String envPort = System.getenv("PORT");
        int port = (envPort != null) ? Integer.parseInt(envPort) : 8080;

        // Bind to 0.0.0.0 to accept external cloud requests
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/critique", debatecoach::handleCritique);
        server.createContext("/debate", debatecoach::handleDebate);
        server.createContext("/argue", debatecoach::handleArgue);
        server.setExecutor(null);
        server.start();
        System.out.println("Server running on port " + port);
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

    // ---------- Multi-agent debate ----------

    static void handleDebate(HttpExchange exchange) throws IOException {
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

            String resultJson = runDebate(argument);

            byte[] responseBytes = resultJson.getBytes(StandardCharsets.UTF_8);
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

    static String runDebate(String argument) throws Exception {
        if (GROQ_API_KEY == null || GROQ_API_KEY.isEmpty()) {
            throw new RuntimeException("GROQ_API_KEY environment variable is not set!");
        }

        String debaterSystemA =
            "You are a sharp, concise debater. Take a clear position defending the "
          + "given argument/topic and strengthen it with the best reasoning and evidence "
          + "you can. Keep it under 120 words. Plain text, no markdown.";

        String debaterSystemB =
            "You are a sharp, concise debater. Take a clear position challenging or "
          + "arguing against the given argument/topic - poke holes in it and make the "
          + "strongest possible counter-case. Keep it under 120 words. Plain text, no markdown.";

        String judgeSystem =
            "You are an impartial judge. You will be given a topic and a full transcript "
          + "of two AI agents debating it across two rounds (opening + rebuttal). Decide "
          + "which side made the stronger, best-supported case overall - or note if it's a "
          + "genuine draw. Explain your reasoning in 2-3 sentences, then end with a clear "
          + "one-line final verdict prefixed exactly with 'VERDICT:'. Plain text, no markdown.";

        // Round 1: opening positions
        String openingA = callGroqText(DEBATE_AGENT_A_MODEL, debaterSystemA, "Topic: " + argument);
        String openingB = callGroqText(DEBATE_AGENT_B_MODEL, debaterSystemB, "Topic: " + argument);

        // Round 2: rebuttal, each sees the other's opening
        String rebuttalA = callGroqText(DEBATE_AGENT_A_MODEL, debaterSystemA,
            "Topic: " + argument
          + "\nYour opening: " + openingA
          + "\nOpponent's opening: " + openingB
          + "\nRebut their weakest point and defend or refine your position. Under 100 words.");
        String rebuttalB = callGroqText(DEBATE_AGENT_B_MODEL, debaterSystemB,
            "Topic: " + argument
          + "\nYour opening: " + openingB
          + "\nOpponent's opening: " + openingA
          + "\nRebut their weakest point and defend or refine your position. Under 100 words.");

        // Judge reads the full transcript
        String transcript = "TOPIC: " + argument
            + "\n\nAgent A (opening): " + openingA
            + "\n\nAgent B (opening): " + openingB
            + "\n\nAgent A (rebuttal): " + rebuttalA
            + "\n\nAgent B (rebuttal): " + rebuttalB;
        String verdict = callGroqText(DEBATE_JUDGE_MODEL, judgeSystem, transcript);

        return "{"
            + "\"topic\": \"" + escapeJson(argument) + "\","
            + "\"agentA\": {"
                + "\"model\": \"" + escapeJson(DEBATE_AGENT_A_MODEL) + "\","
                + "\"opening\": \"" + escapeJson(openingA) + "\","
                + "\"rebuttal\": \"" + escapeJson(rebuttalA) + "\""
            + "},"
            + "\"agentB\": {"
                + "\"model\": \"" + escapeJson(DEBATE_AGENT_B_MODEL) + "\","
                + "\"opening\": \"" + escapeJson(openingB) + "\","
                + "\"rebuttal\": \"" + escapeJson(rebuttalB) + "\""
            + "},"
            + "\"verdict\": \"" + escapeJson(verdict) + "\""
        + "}";
    }

    // Calls Groq chat completions with a given model/system/user prompt and
    // returns just the plain-text reply (unlike getCritiqueFromGroq, which
    // returns the raw Groq JSON for JSON-mode responses).
    static String callGroqText(String model, String systemPrompt, String userMessage) throws Exception {
        String jsonPayload = """
            {
              "model": "%s",
              "reasoning_format": "hidden",
              "messages": [
                {"role": "system", "content": "%s"},
                {"role": "user", "content": "%s"}
              ],
              "temperature": 0.7
            }
            """.formatted(model, escapeJson(systemPrompt), escapeJson(userMessage));

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + GROQ_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Groq API Error (" + model + "): " + response.body());
        }

        return extractMessageContent(response.body());
    }

    // Hand-rolled extraction of choices[0].message.content from a raw Groq
    // JSON response body, handling standard JSON string escapes.
    private static String extractMessageContent(String rawJson) {
        String marker = "\"content\":\"";
        int start = rawJson.indexOf(marker);
        if (start == -1) return rawJson;
        start += marker.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < rawJson.length(); i++) {
            char c = rawJson.charAt(i);
            if (c == '\\' && i + 1 < rawJson.length()) {
                char next = rawJson.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '/': sb.append('/'); i++; break;
                    case 'u':
                        if (i + 5 < rawJson.length()) {
                            String hex = rawJson.substring(i + 2, i + 6);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 5;
                        }
                        break;
                    default: sb.append(next); i++;
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------- Human-vs-AI live debate ----------

    static void handleArgue(HttpExchange exchange) throws IOException {
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
            List<String[]> history = parseMessages(requestBody);
            if (history.isEmpty()) {
                throw new RuntimeException("No messages provided");
            }

            String reply = getArgueReply(history);
            String resultJson = "{\"reply\": \"" + escapeJson(reply) + "\"}";

            byte[] responseBytes = resultJson.getBytes(StandardCharsets.UTF_8);
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

    static String getArgueReply(List<String[]> history) throws Exception {
        if (GROQ_API_KEY == null || GROQ_API_KEY.isEmpty()) {
            throw new RuntimeException("GROQ_API_KEY environment variable is not set!");
        }

        String systemPrompt =
            "You are a sharp but respectful debate opponent arguing against the user's "
          + "position in a live back-and-forth. Challenge their reasoning directly. "
          + "Whenever the user states a fact that is inaccurate, outdated, or unsupported, "
          + "correct it clearly and give the accurate fact - don't let factual errors slide "
          + "even on minor points, but don't invent corrections either; only correct things "
          + "you're actually confident are wrong. Stay on the topic of the conversation. "
          + "Keep each reply under 130 words. Plain text, no markdown.";

        String messagesJson = buildMessagesArrayJson(systemPrompt, history);

        String jsonPayload = """
            {
              "model": "%s",
              "reasoning_format": "hidden",
              "messages": %s,
              "temperature": 0.6
            }
            """.formatted(ARGUE_MODEL, messagesJson);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + GROQ_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Groq API Error (" + ARGUE_MODEL + "): " + response.body());
        }

        return extractMessageContent(response.body());
    }

    // Builds a Groq "messages" JSON array: a system prompt followed by the
    // running human/AI history, each entry re-escaped for safe embedding.
    private static String buildMessagesArrayJson(String systemPrompt, List<String[]> history) {
        StringBuilder sb = new StringBuilder("[");
        sb.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(systemPrompt)).append("\"}");
        for (String[] msg : history) {
            String role = "assistant".equals(msg[0]) ? "assistant" : "user";
            sb.append(",{\"role\":\"").append(role).append("\",\"content\":\"")
              .append(escapeJson(msg[1])).append("\"}");
        }
        return sb.append("]").toString();
    }

    // Parses a request body of the form {"messages":[{"role":"user","content":"..."},...]}
    // into an ordered list of [role, content] pairs, honoring JSON string escapes.
    private static List<String[]> parseMessages(String requestJson) {
        List<String[]> messages = new ArrayList<>();
        Pattern p = Pattern.compile(
            "\"role\"\\s*:\\s*\"(user|assistant)\"\\s*,\\s*\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
        );
        Matcher m = p.matcher(requestJson);
        while (m.find()) {
            messages.add(new String[]{m.group(1), unescapeJsonString(m.group(2))});
        }
        return messages;
    }

    // Decodes JSON string escapes (\n \t \" \\ \/ and \uXXXX) in an already
    // quote-stripped string fragment.
    private static String unescapeJsonString(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '/': sb.append('/'); i++; break;
                    case 'u':
                        if (i + 5 < raw.length()) {
                            sb.append((char) Integer.parseInt(raw.substring(i + 2, i + 6), 16));
                            i += 5;
                        }
                        break;
                    default: sb.append(next); i++;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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