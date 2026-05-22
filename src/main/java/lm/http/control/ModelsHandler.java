package lm.http.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import lm.logging.control.Log;

public final class ModelsHandler implements HttpHandler {

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
            var body = new JSONObject()
                    .put("object", "list")
                    .put("data", new JSONArray().put(new JSONObject()
                            .put("id", "lightmetal")
                            .put("object", "model")
                            .put("created", 0)
                            .put("owned_by", "local")))
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
