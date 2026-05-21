package lm.http.control;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import lm.configuration.entity.GenerationConfig;
import lm.generation.boundary.LightMetal;
import lm.http.entity.MessagesRequest;
import lm.prompting.control.PromptTemplate;
import lm.tools.control.SchemaToGbnf;
import lm.tools.control.ToolCallParser;
import lm.tools.control.ToolCatalogue;

public final class MessagesHandler implements HttpHandler {

    private final LightMetal lm;

    public MessagesHandler(LightMetal lm) {
        this.lm = lm;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeError(exchange, 405, "method_not_allowed", "use POST");
                return;
            }
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JSONObject root;
            MessagesRequest req;
            try {
                root = new JSONObject(new JSONTokener(body));
                req = MessagesRequest.from(root, GenerationConfig.defaults());
            } catch (IllegalArgumentException e) {
                writeError(exchange, 400, "invalid_request_error", e.getMessage());
                return;
            }
            try {
                writeOk(exchange, generate(req));
            } catch (RuntimeException e) {
                writeError(exchange, 500, "api_error", e.getMessage());
            }
        }
    }

    JSONObject generate(MessagesRequest req) {
        var alternating = new ArrayList<String>(req.turns().size());
        for (var t : req.turns()) {
            alternating.add(t.text());
        }
        var system = ToolCatalogue.renderSystemPrelude(req.system(), req.tools());
        var prompt = PromptTemplate.mistralChat(system, alternating);
        var cfg = baseConfig(req);
        if (!req.tools().isEmpty()) {
            cfg = cfg.withGrammar(SchemaToGbnf.compile(req.tools()));
        }

        var text = new StringBuilder();
        var emitted = new long[1];
        synchronized (lm) {
            try (var stream = lm.generate(prompt, cfg)) {
                stream.forEach(t -> {
                    text.append(t.text());
                    emitted[0]++;
                });
            }
        }

        var parsed = ToolCallParser.parse(text.toString());
        var content = new JSONArray();
        String stopReason;
        if (parsed instanceof ToolCallParser.Call call) {
            content.put(new JSONObject()
                    .put("type", "tool_use")
                    .put("id", call.toolCall().id())
                    .put("name", call.toolCall().name())
                    .put("input", call.toolCall().input()));
            stopReason = "tool_use";
        } else {
            var textValue = parsed instanceof ToolCallParser.Text txt ? txt.text() : text.toString();
            content.put(new JSONObject().put("type", "text").put("text", textValue));
            stopReason = emitted[0] >= req.maxTokens() ? "max_tokens" : "end_turn";
        }

        return new JSONObject()
                .put("id", "msg_" + System.nanoTime())
                .put("type", "message")
                .put("role", "assistant")
                .put("model", "lightmetal")
                .put("content", content)
                .put("stop_reason", stopReason)
                .put("stop_sequence", JSONObject.NULL)
                .put("usage", new JSONObject()
                        .put("input_tokens", estimateTokens(prompt))
                        .put("output_tokens", (int) emitted[0])
                        .put("cache_read_input_tokens", 0)
                        .put("cache_creation_input_tokens", 0));
    }

    static GenerationConfig baseConfig(MessagesRequest req) {
        var d = GenerationConfig.defaults();
        return new GenerationConfig(
                req.maxTokens(),
                req.temperature(),
                d.topP(),
                d.topK(),
                d.minP(),
                System.nanoTime());
    }

    static int estimateTokens(String prompt) {
        if (prompt == null || prompt.isEmpty()) return 0;
        return Math.max(1, prompt.length() / 4);
    }

    static void writeOk(HttpExchange exchange, JSONObject body) throws IOException {
        write(exchange, 200, body.toString());
    }

    static void writeError(HttpExchange exchange, int status, String type, String message) throws IOException {
        var body = new JSONObject()
                .put("type", "error")
                .put("error", new JSONObject().put("type", type).put("message", message))
                .toString();
        write(exchange, status, body);
    }

    static void write(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
