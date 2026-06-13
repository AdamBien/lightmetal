package lm.http.control;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.generation.boundary.LightMetal;
import lm.generation.control.TokenAccumulator;
import lm.generation.entity.Response;
import lm.http.entity.AnthropicMessagesRequest;
import lm.prompting.entity.AssistantText;
import lm.prompting.entity.UserText;
import lm.logging.control.Log;
import lm.prompting.control.PromptTemplate;

public final class MessagesHandler implements HttpHandler {

    private final LightMetal lm;
    private final String legacyOverride;

    public MessagesHandler(LightMetal lm) {
        this.lm = lm;
        var override = ZCfg.string("template", "");
        this.legacyOverride = isLegacyBasic(override) ? override : "";
        Log.system("[template=" + (legacyOverride.isEmpty()
                ? lm.template().getClass().getSimpleName() : legacyOverride) + "]");
    }

    private static boolean isLegacyBasic(String override) {
        return "v0.3".equals(override) || "v3".equals(override) || "basic".equals(override);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        var startNanos = System.nanoTime();
        var method = exchange.getRequestMethod();
        var uri = exchange.getRequestURI();
        Log.http("--> " + method + " " + uri + " from " + exchange.getRemoteAddress());
        var status = 0;
        try (exchange) {
            if (!"POST".equalsIgnoreCase(method)) {
                status = 405;
                writeError(exchange, status, "method_not_allowed", "use POST");
                return;
            }
            // The /v1/messages context prefix-matches sub-paths, so an OpenAI client
            // configured with base URL .../v1/messages would POST /v1/messages/chat/completions
            // and silently get Anthropic-format JSON (no "choices"). Reject the mismatch with
            // a pointer to the right configuration instead of a misleading 200.
            var path = uri.getPath();
            if (!"/v1/messages".equals(path)) {
                status = 404;
                writeError(exchange, status, "not_found",
                        "unknown path " + path + "; this is the Anthropic endpoint (POST /v1/messages). "
                                + "OpenAI-compatible clients should set the base URL to .../v1 so requests "
                                + "reach /v1/chat/completions");
                return;
            }
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            AnthropicMessagesRequest req;
            try {
                var root = new JSONObject(new JSONTokener(body));
                req = AnthropicMessagesRequest.from(root, GenerationConfig.defaults());
            } catch (IllegalArgumentException e) {
                status = 400;
                writeError(exchange, status, "invalid_request_error", e.getMessage());
                return;
            }
            try {
                writeOk(exchange, generate(req));
                status = 200;
            } catch (RuntimeException e) {
                status = 500;
                Log.error("request failed", e);
                writeError(exchange, status, "api_error", e.getMessage());
            }
        } finally {
            var ms = (System.nanoTime() - startNanos) / 1_000_000;
            Log.http("<-- " + method + " " + uri + " " + status + " (" + ms + " ms)");
        }
    }

    JSONObject generate(AnthropicMessagesRequest req) {
        var cfg = baseConfig(req);
        var response = legacyOverride.isEmpty()
                ? lm.chat(req.system(), req.tools(), req.turns(), cfg)
                : generateLegacy(req, cfg);
        return toAnthropicJson(response);
    }

    Response generateLegacy(AnthropicMessagesRequest req, GenerationConfig cfg) {
        var prompt = PromptTemplate.mistralChat(req.system(), flattenForBasic(req));
        Log.debug("[prompt template=" + legacyOverride + "]\n" + prompt + "\n[/prompt]");
        var acc = new TokenAccumulator();
        synchronized (lm) {
            lm.reset();
            try (var stream = lm.complete(prompt, cfg)) {
                stream.forEach(acc);
            }
        }
        var reason = acc.count() >= cfg.maxTokens()
                ? Response.StopReason.MAX_TOKENS : Response.StopReason.END_TURN;
        return new Response(acc.text(), List.of(), reason,
                estimateTokens(prompt), (int) acc.count());
    }

    JSONObject toAnthropicJson(Response resp) {
        return toAnthropicJson(resp, lm.metadata().name().orElse("lightmetal"));
    }

    public static JSONObject toAnthropicJson(Response resp, String modelName) {
        var content = new JSONArray();
        String stopReason;
        if (resp.hasToolCalls()) {
            if (!resp.text().isBlank()) {
                content.put(new JSONObject().put("type", "text").put("text", resp.text()));
            }
            for (var call : resp.calls()) {
                content.put(new JSONObject()
                        .put("type", "tool_use")
                        .put("id", call.id())
                        .put("name", call.name())
                        .put("input", call.input()));
            }
            stopReason = "tool_use";
        } else {
            content.put(new JSONObject().put("type", "text").put("text", resp.text()));
            stopReason = switch (resp.reason()) {
                case MAX_TOKENS -> "max_tokens";
                case END_TURN, TOOL_USE -> "end_turn";
            };
        }

        return new JSONObject()
                .put("id", "msg_" + System.nanoTime())
                .put("type", "message")
                .put("role", "assistant")
                .put("model", modelName)
                .put("content", content)
                .put("stop_reason", stopReason)
                .put("stop_sequence", JSONObject.NULL)
                .put("usage", new JSONObject()
                        .put("input_tokens", resp.inputTokens())
                        .put("output_tokens", resp.outputTokens())
                        .put("cache_read_input_tokens", 0)
                        .put("cache_creation_input_tokens", 0));
    }

    static List<String> flattenForBasic(AnthropicMessagesRequest req) {
        var out = new ArrayList<String>(req.turns().size());
        for (var turn : req.turns()) {
            switch (turn) {
                case UserText u -> out.add(u.text());
                case AssistantText a -> out.add(a.text());
                default -> { /* skip tool turns: v0.3 has no tool support */ }
            }
        }
        return out;
    }

    static GenerationConfig baseConfig(AnthropicMessagesRequest req) {
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
