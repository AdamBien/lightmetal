package lm.http.boundary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

import lm.generation.boundary.LightMetal;
import lm.http.control.ChatCompletionsHandler;
import lm.http.control.MessagesHandler;
import lm.http.control.ModelsHandler;
import lm.logging.control.Log;

public final class HttpAPI implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;

    private HttpAPI(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static void serve(Path modelPath, int port) {
        var lm = LightMetal.load(modelPath);
        var api = HttpAPI.start(lm, port);
        var latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.system("[shutting down]");
            api.close();
            lm.close();
            latch.countDown();
        }));
        var modelName = lm.metadata().name().orElse("unknown model");
        var base = "http://localhost:" + api.port();
        Log.success("[lightmetal serving %s on %s]".formatted(modelName, base));
        // Advertise the base URL OpenAI clients need (e.g. DevoxxGenie / langchain4j append
        // /chat/completions to it): pointing such a client at /v1/messages routes to the
        // Anthropic handler and yields a response with no "choices".
        Log.system("  OpenAI-compatible : POST " + base + "/v1/chat/completions  (base URL: " + base + "/v1)");
        Log.system("  Anthropic Messages: POST " + base + "/v1/messages");
        Log.system("  Models            : GET  " + base + "/v1/models  (also /api/v1/models for LM Studio clients)");
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static HttpAPI start(LightMetal lm, int port) {
        try {
            var server = HttpServer.create(new InetSocketAddress(port), 0);
            var executor = Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "lightmetal-http");
                t.setDaemon(false);
                return t;
            });
            var models = new ModelsHandler(lm);
            server.createContext("/v1/messages", new MessagesHandler(lm));
            server.createContext("/v1/chat/completions", new ChatCompletionsHandler(lm));
            server.createContext("/v1/models", models);
            // LM Studio-compatible clients (e.g. DevoxxGenie's LMStudio provider) fetch the
            // model list from /api/v1/models rather than the OpenAI-standard /v1/models.
            server.createContext("/api/v1/models", models);
            server.setExecutor(executor);
            server.start();
            return new HttpAPI(server, executor);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start HTTP server on port " + port, e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
