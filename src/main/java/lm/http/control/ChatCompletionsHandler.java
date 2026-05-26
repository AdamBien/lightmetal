package lm.http.control;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.generation.boundary.LightMetal;
import lm.http.entity.AnthropicMessagesRequest;
import lm.http.entity.AnthropicMessagesRequest.AssistantText;
import lm.http.entity.AnthropicMessagesRequest.UserText;
import lm.http.entity.OpenAIChatRequest;
import lm.logging.control.Log;
import lm.inspection.entity.GGUFMetadata;
import lm.prompting.control.ChatTemplate;
import lm.prompting.control.Mistral4ChatTemplate;
import lm.prompting.control.ModelFamily;
import lm.prompting.control.PromptTemplate;
import lm.tools.control.ToolCallParser;

public final class ChatCompletionsHandler implements HttpHandler {

    private final LightMetal lm;
    private final String template;
    private final ChatTemplate chatTemplate;

    public ChatCompletionsHandler(LightMetal lm) {
        this.lm = lm;
        var override = ZCfg.string("template", "");
        if (isLegacyBasic(override)) {
            this.template = override;
            this.chatTemplate = new Mistral4ChatTemplate();
        } else {
            var family = resolveFamily(lm.metadata(), override);
            this.template = family.name();
            this.chatTemplate = family.template();
        }
        Log.system("[template=" + this.template + "]");
    }

    private static boolean isLegacyBasic(String override) {
        return "v0.3".equals(override) || "v3".equals(override) || "basic".equals(override);
    }

    private static ModelFamily resolveFamily(GGUFMetadata metadata, String override) {
        if (!override.isBlank()) {
            return ModelFamily.valueOf(override);
        }
        return ModelFamily.from(metadata)
                .orElseThrow(() -> new IllegalStateException(
                        "no ModelFamily entry for GGUF name=" + metadata.name().orElse("?")
                                + " — add it to lm.prompting.control.ModelFamily"));
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
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JSONObject root;
            try {
                root = new JSONObject(new JSONTokener(body));
            } catch (IllegalArgumentException | IllegalStateException e) {
                status = 400;
                writeError(exchange, status, "invalid_request_error", "malformed JSON: " + e.getMessage());
                return;
            }
            if (root.optBoolean("stream", false)) {
                status = 400;
                writeError(exchange, status, "invalid_request_error",
                        "streaming is not supported yet; set stream:false or omit it");
                return;
            }
            OpenAIChatRequest req;
            try {
                req = OpenAIChatRequest.from(root, GenerationConfig.defaults());
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

    JSONObject generate(OpenAIChatRequest req) {
        var msgReq = req.toAnthropicMessagesRequest();
        var prompt = buildPrompt(msgReq);
        var cfg = baseConfig(req).withStopSequences(chatTemplate.stopSequences());
        Log.debug("[prompt template=" + template + "]\n" + prompt + "\n[/prompt]");

        var raw = new StringBuilder();
        var emitted = new long[1];
        synchronized (lm) {
            lm.reset();
            try (var stream = lm.complete(prompt, cfg)) {
                stream.forEach(t -> {
                    raw.append(t.text());
                    emitted[0]++;
                });
            }
        }

        var parsed = chatTemplate.parse(raw.toString());
        var message = new JSONObject().put("role", "assistant");
        String finishReason;
        if (parsed instanceof ToolCallParser.Calls calls) {
            var leading = calls.leadingText();
            message.put("content", leading.isBlank() ? JSONObject.NULL : leading);
            var toolCalls = new JSONArray();
            for (var call : calls.calls()) {
                toolCalls.put(new JSONObject()
                        .put("id", call.id())
                        .put("type", "function")
                        .put("function", new JSONObject()
                                .put("name", call.name())
                                .put("arguments", call.input().toString())));
            }
            message.put("tool_calls", toolCalls);
            finishReason = "tool_calls";
        } else {
            var text = parsed instanceof ToolCallParser.Text txt ? txt.text() : raw.toString();
            message.put("content", text);
            finishReason = emitted[0] >= req.maxTokens() ? "length" : "stop";
        }

        var choice = new JSONObject()
                .put("index", 0)
                .put("message", message)
                .put("finish_reason", finishReason);

        var promptTokens = estimateTokens(prompt);
        var completionTokens = (int) emitted[0];
        return new JSONObject()
                .put("id", "chatcmpl-" + System.nanoTime())
                .put("object", "chat.completion")
                .put("created", System.currentTimeMillis() / 1000L)
                .put("model", lm.metadata().name().orElse("lightmetal"))
                .put("choices", new JSONArray().put(choice))
                .put("usage", new JSONObject()
                        .put("prompt_tokens", promptTokens)
                        .put("completion_tokens", completionTokens)
                        .put("total_tokens", promptTokens + completionTokens));
    }

    String buildPrompt(AnthropicMessagesRequest req) {
        return switch (template) {
            case "v0.3", "v3", "basic" -> PromptTemplate.mistralChat(req.system(), flattenForBasic(req));
            default -> chatTemplate.render(req.system(), req.tools(), req.turns());
        };
    }

    static java.util.List<String> flattenForBasic(AnthropicMessagesRequest req) {
        var out = new java.util.ArrayList<String>(req.turns().size());
        for (var turn : req.turns()) {
            switch (turn) {
                case UserText u -> out.add(u.text());
                case AssistantText a -> out.add(a.text());
                default -> { /* skip tool turns: v0.3 has no tool support */ }
            }
        }
        return out;
    }

    static GenerationConfig baseConfig(OpenAIChatRequest req) {
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
                .put("error", new JSONObject()
                        .put("type", type)
                        .put("message", message)
                        .put("code", JSONObject.NULL)
                        .put("param", JSONObject.NULL))
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
