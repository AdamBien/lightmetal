package lm.generation.boundary;

import module java.base;

import org.json.JSONObject;
import org.json.JSONTokener;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.http.control.MessagesHandler;
import lm.http.entity.AnthropicMessagesRequest;
import lm.logging.control.Log;

public final class LightMetalChat implements UnaryOperator<String>, AutoCloseable {

    LightMetal lm;
    Path loadedPath;

    // Eager so embedders can read config (model.directory, defaults, etc.) the moment
    // ServiceLoader discovers this provider. loadAndPublish also republishes the values
    // as system properties so zero-coupling hosts use System.getProperty(...) — no lm.* imports.
    public LightMetalChat() {
        ZCfg.loadAndPublish("lightmetal");
    }

    @Override
    public synchronized String apply(String requestJson) {
        var root = new JSONObject(new JSONTokener(requestJson));
        var modelPath = Path.of(root.getString("model"));
        ensureLoaded(modelPath);
        var req = AnthropicMessagesRequest.from(root, GenerationConfig.fromProperties());
        var resp = lm.chat(req.system(), req.tools(), req.turns(), config(req));
        return MessagesHandler.toAnthropicJson(resp,
                lm.metadata().name().orElse("lightmetal")).toString();
    }

    @Override
    public synchronized void close() {
        if (lm == null) return;
        lm.close();
        lm = null;
        loadedPath = null;
    }

    // Single-model session: the first apply() decides the path; a later apply()
    // with a different path swaps (closes old, loads new). zsmith-style agents
    // keep the same path forever and hit the fast path on every turn.
    void ensureLoaded(Path modelPath) {
        if (lm != null && modelPath.equals(loadedPath)) return;
        if (lm != null) {
            Log.system("[swapping model %s -> %s]".formatted(loadedPath, modelPath));
            lm.close();
        }
        lm = LightMetal.load(modelPath);
        loadedPath = modelPath;
    }

    static GenerationConfig config(AnthropicMessagesRequest req) {
        var d = GenerationConfig.defaults();
        return new GenerationConfig(
                req.maxTokens(),
                req.temperature(),
                d.topP(),
                d.topK(),
                d.minP(),
                System.nanoTime());
    }
}
