package lm.http.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.ContextParams;
import lm.generation.boundary.LightMetal;
import lm.logging.control.Log;

public final class ModelsHandler implements HttpHandler {

    private final LightMetal lm;

    public ModelsHandler(LightMetal lm) {
        this.lm = lm;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        var startNanos = System.nanoTime();
        var method = exchange.getRequestMethod();
        var uri = exchange.getRequestURI();
        Log.http("--> " + method + " " + uri + " from " + exchange.getRemoteAddress());
        var status = 0;
        try (exchange) {
            if (!"GET".equalsIgnoreCase(method)) {
                status = 405;
                writeError(exchange, status, "method_not_allowed", "use GET");
                return;
            }
            // The effective served context window — ZCfg "context.length" overrides the GGUF's
            // advertised maximum (see LightMetal.contextParams), so report that, not the GGUF value.
            var ctxLen = ZCfg.integer("context.length", ContextParams.defaults().contextLength());
            var id = lm.metadata().name().orElse("lightmetal");
            var body = new JSONObject()
                    .put("object", "list")
                    // Standard OpenAI fields plus LM Studio rich-metadata fields
                    // (display_name / max_context_length) so the LM Studio provider, which
                    // fetches /api/v1/models, can populate its model list and context window.
                    .put("data", new JSONArray().put(new JSONObject()
                            .put("id", id)
                            .put("object", "model")
                            .put("created", 0)
                            .put("owned_by", "local")
                            .put("display_name", id)
                            .put("max_context_length", ctxLen)
                            .put("context_length", ctxLen)))
                    .toString();
            write(exchange, 200, body);
            status = 200;
        } finally {
            var ms = (System.nanoTime() - startNanos) / 1_000_000;
            Log.http("<-- " + method + " " + uri + " " + status + " (" + ms + " ms)");
        }
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
