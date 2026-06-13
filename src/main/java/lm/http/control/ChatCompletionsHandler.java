package lm.http.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.configuration.entity.Token;
import lm.generation.boundary.LightMetal;
import lm.generation.control.TokenAccumulator;
import lm.generation.entity.Response;
import lm.http.entity.AnthropicMessagesRequest;
import lm.prompting.entity.AssistantText;
import lm.prompting.entity.UserText;
import lm.http.entity.OpenAIChatRequest;
import lm.logging.control.Log;
import lm.prompting.control.ChatTemplate;
import lm.prompting.control.PromptTemplate;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.ToolCall;

public final class ChatCompletionsHandler implements HttpHandler {

    private final LightMetal lm;
    private final String legacyOverride;

    public ChatCompletionsHandler(LightMetal lm) {
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
            OpenAIChatRequest req;
            try {
                req = OpenAIChatRequest.from(root, GenerationConfig.defaults());
            } catch (IllegalArgumentException e) {
                status = 400;
                writeError(exchange, status, "invalid_request_error", e.getMessage());
                return;
            }
            var stream = root.optBoolean("stream", false);
            var includeUsage = stream && includeUsage(root);
            try {
                if (stream) {
                    streamGenerate(exchange, req, includeUsage);
                } else {
                    writeOk(exchange, generate(req));
                }
                status = 200;
            } catch (HeadersSentException e) {
                // Generation failed after the SSE response was committed; the client
                // received a truncated stream. We can no longer change the status code.
                status = 200;
                Log.error("stream interrupted", e);
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
        var cfg = baseConfig(req);
        var response = legacyOverride.isEmpty()
                ? lm.chat(msgReq.system(), msgReq.tools(), msgReq.turns(), cfg)
                : generateLegacy(msgReq, cfg);
        return toOpenAIJson(response);
    }

    // Number of trailing characters held back from each content delta. The parsed
    // text grows token by token; a trailing run could still turn out to be the start
    // of a tool-call marker (e.g. "[TOOL_CALLS]", "<|tool_call>") or a stop sequence
    // that template.parse() will strip once it completes. Holding back a margin wider
    // than the longest such marker guarantees we never stream bytes we'd have to retract.
    private static final int STREAM_TAIL_MARGIN = 24;

    // OpenAI streaming: emit `chat.completion.chunk` SSE events. The non-streaming path
    // derives its answer from template.parse(rawText) (NOT tagChannels), so we replicate
    // that exactly — accumulate raw token text, re-parse each step, and stream the stable
    // growing prefix of the parsed text. Output is byte-for-byte what generate() would
    // return, with identical thinking-strip and tool-call handling, for any template.
    void streamGenerate(HttpExchange exchange, OpenAIChatRequest req, boolean includeUsage)
            throws IOException {
        var msgReq = req.toAnthropicMessagesRequest();
        var cfg = baseConfig(req);
        if (!legacyOverride.isEmpty()) {
            // Legacy templates have no streaming primitive; generate fully, then frame
            // the single result as a one-chunk SSE stream so the wire format stays valid.
            streamBuffered(exchange, generateLegacy(msgReq, cfg), includeUsage);
            return;
        }
        var template = lm.template();
        var prompt = template.render(msgReq.system(), msgReq.tools(), msgReq.turns());
        var effective = cfg.withStopSequences(template.stopSequences());
        Log.debug("[prompt template=" + template.getClass().getSimpleName() + "]\n" + prompt + "\n[/prompt]");

        var meta = new ChunkMeta();
        meta.prompt = prompt;
        var headers = exchange.getResponseHeaders();
        headers.add("content-type", "text/event-stream; charset=utf-8");
        headers.add("cache-control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (var out = exchange.getResponseBody()) {
            try {
                sendChunk(out, meta, new JSONObject().put("role", "assistant"), null);
                var emitted = streamTokens(out, meta, template, prompt, effective);
                finishStream(out, meta, template.parse(emitted.raw()), emitted, includeUsage);
            } catch (RuntimeException e) {
                // Headers are already committed; surface to handle() so it logs without
                // attempting an HTTP error response on a half-written stream.
                throw new HeadersSentException(e);
            }
            sendDone(out);
        }
    }

    // Runs generation under the model lock, streaming content deltas as the parsed text
    // grows. Returns the full raw generated text plus the token count for usage.
    private Emitted streamTokens(OutputStream out, ChunkMeta meta, ChatTemplate template,
            String prompt, GenerationConfig cfg) {
        var raw = new StringBuilder();
        var count = new long[1];
        var emitted = new int[1];
        var openMarkers = template.toolCallOpenMarkers();
        synchronized (lm) {
            lm.reset();
            try (Stream<Token> stream = lm.complete(prompt, cfg)) {
                stream.forEach(tok -> {
                    count[0]++;
                    raw.append(tok.text());
                    var parsed = template.parse(raw.toString());
                    if (parsed instanceof ToolCallParser.Text t) {
                        // parse() reports a call only once its closing marker arrives; until
                        // then the in-progress call sits in t.text() as plain text. Cut at the
                        // open marker so the call body never leaks into the content stream.
                        var visible = truncateAtMarker(t.text(), openMarkers);
                        emitted[0] = pushContent(out, meta, visible, emitted[0], STREAM_TAIL_MARGIN);
                    }
                    // ToolCallParser.Calls: stop streaming content; flushed in finishStream().
                });
            }
        }
        return new Emitted(raw.toString(), emitted[0], count[0], cfg.maxTokens(), openMarkers);
    }

    private void finishStream(OutputStream out, ChunkMeta meta, ToolCallParser.Parsed parsed,
            Emitted emitted, boolean includeUsage) throws IOException {
        String finishReason;
        if (parsed instanceof ToolCallParser.Calls c) {
            var lead = c.leadingText();
            if (lead.length() > emitted.emitted()) {
                writeSse(out, chunk(meta, new JSONObject().put("content", lead.substring(emitted.emitted())), null));
            }
            writeSse(out, chunk(meta, new JSONObject().put("tool_calls", toolCallDeltas(c.calls())), null));
            finishReason = "tool_calls";
        } else {
            var full = truncateAtMarker(((ToolCallParser.Text) parsed).text(), emitted.openMarkers());
            if (full.length() > emitted.emitted()) {
                writeSse(out, chunk(meta, new JSONObject().put("content", full.substring(emitted.emitted())), null));
            }
            finishReason = emitted.count() >= emitted.maxTokens() ? "length" : "stop";
        }
        writeSse(out, chunk(meta, new JSONObject(), finishReason));
        if (includeUsage) {
            writeSse(out, usageChunk(meta, estimateTokens(meta.prompt), (int) emitted.count()));
        }
    }

    void streamBuffered(HttpExchange exchange, Response response, boolean includeUsage) throws IOException {
        var meta = new ChunkMeta();
        var headers = exchange.getResponseHeaders();
        headers.add("content-type", "text/event-stream; charset=utf-8");
        headers.add("cache-control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (var out = exchange.getResponseBody()) {
            sendChunk(out, meta, new JSONObject().put("role", "assistant"), null);
            String finishReason;
            if (response.hasToolCalls()) {
                if (!response.text().isBlank()) {
                    sendChunk(out, meta, new JSONObject().put("content", response.text()), null);
                }
                sendChunk(out, meta, new JSONObject().put("tool_calls", toolCallDeltas(response.calls())), null);
                finishReason = "tool_calls";
            } else {
                sendChunk(out, meta, new JSONObject().put("content", response.text()), null);
                finishReason = switch (response.reason()) {
                    case MAX_TOKENS -> "length";
                    case END_TURN, TOOL_USE -> "stop";
                };
            }
            sendChunk(out, meta, new JSONObject(), finishReason);
            if (includeUsage) {
                writeSse(out, usageChunk(meta, response.inputTokens(), response.outputTokens()));
            }
            sendDone(out);
        }
    }

    // Emits the stable, retraction-safe prefix of `full` that has not been sent yet:
    // everything except the last `margin` chars (which may still be rewritten/stripped).
    // Returns the new emitted length. Never splits a UTF-16 surrogate pair.
    static int pushContent(OutputStream out, ChunkMeta meta, String full, int emitted, int margin) {
        var safeEnd = Math.max(emitted, full.length() - margin);
        if (safeEnd > 0 && Character.isHighSurrogate(full.charAt(safeEnd - 1))) {
            safeEnd--;
        }
        if (safeEnd <= emitted) return emitted;
        sendChunk(out, meta, new JSONObject().put("content", full.substring(emitted, safeEnd)), null);
        return safeEnd;
    }

    static JSONArray toolCallDeltas(List<ToolCall> calls) {
        var arr = new JSONArray();
        for (var i = 0; i < calls.size(); i++) {
            var call = calls.get(i);
            arr.put(new JSONObject()
                    .put("index", i)
                    .put("id", call.id())
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", call.name())
                            .put("arguments", call.input().toString())));
        }
        return arr;
    }

    static void sendChunk(OutputStream out, ChunkMeta meta, JSONObject delta, String finishReason) {
        writeSse(out, chunk(meta, delta, finishReason));
    }

    static JSONObject chunk(ChunkMeta meta, JSONObject delta, String finishReason) {
        var choice = new JSONObject()
                .put("index", 0)
                .put("delta", delta)
                .put("finish_reason", finishReason == null ? JSONObject.NULL : finishReason);
        return new JSONObject()
                .put("id", meta.id)
                .put("object", "chat.completion.chunk")
                .put("created", meta.created)
                .put("model", meta.model)
                .put("choices", new JSONArray().put(choice));
    }

    static JSONObject usageChunk(ChunkMeta meta, int inputTokens, int outputTokens) {
        return new JSONObject()
                .put("id", meta.id)
                .put("object", "chat.completion.chunk")
                .put("created", meta.created)
                .put("model", meta.model)
                .put("choices", new JSONArray())
                .put("usage", new JSONObject()
                        .put("prompt_tokens", inputTokens)
                        .put("completion_tokens", outputTokens)
                        .put("total_tokens", inputTokens + outputTokens));
    }

    static void writeSse(OutputStream out, JSONObject event) {
        write(out, "data: " + event + "\n\n");
    }

    static void sendDone(OutputStream out) {
        write(out, "data: [DONE]\n\n");
    }

    static void write(OutputStream out, String s) {
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean includeUsage(JSONObject root) {
        var opts = root.optJSONObject("stream_options");
        return opts != null && opts.optBoolean("include_usage", false);
    }

    // Carries the per-response identity shared across every chunk in one SSE stream.
    final class ChunkMeta {
        final String id = "chatcmpl-" + System.nanoTime();
        final long created = System.currentTimeMillis() / 1000L;
        final String model = lm.metadata().name().orElse("lightmetal");
        String prompt = "";
    }

    record Emitted(String raw, int emitted, long count, int maxTokens, List<String> openMarkers) {}

    // Returns the prefix of `text` preceding the earliest tool-call open marker, or the
    // whole string if none is present.
    static String truncateAtMarker(String text, List<String> openMarkers) {
        var cut = text.length();
        for (var marker : openMarkers) {
            var i = text.indexOf(marker);
            if (i >= 0 && i < cut) cut = i;
        }
        return cut == text.length() ? text : text.substring(0, cut);
    }

    // Signals that generation failed after SSE headers were already flushed, so the
    // handler must not attempt a normal HTTP error response.
    static final class HeadersSentException extends RuntimeException {
        HeadersSentException(Throwable cause) {
            super(cause);
        }
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

    JSONObject toOpenAIJson(Response resp) {
        var message = new JSONObject().put("role", "assistant");
        String finishReason;
        if (resp.hasToolCalls()) {
            var leading = resp.text();
            message.put("content", leading.isBlank() ? JSONObject.NULL : leading);
            var toolCalls = new JSONArray();
            for (var call : resp.calls()) {
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
            message.put("content", resp.text());
            finishReason = switch (resp.reason()) {
                case MAX_TOKENS -> "length";
                case END_TURN, TOOL_USE -> "stop";
            };
        }

        var choice = new JSONObject()
                .put("index", 0)
                .put("message", message)
                .put("finish_reason", finishReason);

        return new JSONObject()
                .put("id", "chatcmpl-" + System.nanoTime())
                .put("object", "chat.completion")
                .put("created", System.currentTimeMillis() / 1000L)
                .put("model", lm.metadata().name().orElse("lightmetal"))
                .put("choices", new JSONArray().put(choice))
                .put("usage", new JSONObject()
                        .put("prompt_tokens", resp.inputTokens())
                        .put("completion_tokens", resp.outputTokens())
                        .put("total_tokens", resp.inputTokens() + resp.outputTokens()));
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
