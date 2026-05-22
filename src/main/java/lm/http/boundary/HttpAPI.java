package lm.http.boundary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

import lm.generation.boundary.LightMetal;
import lm.http.control.ChatCompletionsHandler;
import lm.http.control.MessagesHandler;
import lm.http.control.ModelsHandler;

public final class HttpAPI implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;

    private HttpAPI(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static HttpAPI start(LightMetal lm, int port) {
        try {
            var server = HttpServer.create(new InetSocketAddress(port), 0);
            var executor = Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "lightmetal-http");
                t.setDaemon(false);
                return t;
            });
            server.createContext("/v1/messages", new MessagesHandler(lm));
            server.createContext("/v1/chat/completions", new ChatCompletionsHandler(lm));
            server.createContext("/v1/models", new ModelsHandler());
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
