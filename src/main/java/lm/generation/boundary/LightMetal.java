package lm.generation.boundary;

import java.nio.file.Path;
import java.util.stream.Stream;

import lm.backend.control.Context;
import lm.backend.control.Model;
import lm.configuration.control.ZCfg;
import lm.configuration.entity.ContextParams;
import lm.configuration.entity.GenerationConfig;
import lm.configuration.entity.Token;
import lm.prompting.control.PromptTemplate;

public final class LightMetal implements AutoCloseable {

    private static final boolean ADD_BOS = true;

    private final Model model;
    private final Context ctx;

    private LightMetal(Model model, Context ctx) {
        this.model = model;
        this.ctx = ctx;
    }

    public static LightMetal load(Path gguf) {
        var model = new Model(gguf);
        var ctx = model.newContext(contextParams());
        return new LightMetal(model, ctx);
    }

    private static ContextParams contextParams() {
        var d = ContextParams.defaults();
        return new ContextParams(
                ZCfg.integer("context.length", d.contextLength()),
                ZCfg.integer("context.batch.size", d.batchSize()),
                ZCfg.integer("context.gpu.layers", d.gpuLayers()),
                ZCfg.integer("context.seed", (int) d.seed()));
    }

    public Stream<Token> generate(String userPrompt, GenerationConfig cfg) {
        return complete(PromptTemplate.mistralInstruct(userPrompt), cfg);
    }

    public Stream<Token> complete(String formattedPrompt, GenerationConfig cfg) {
        var promptTokens = ctx.tokenize(formattedPrompt, ADD_BOS);
        return ctx.generate(promptTokens, cfg);
    }

    public void reset() {
        ctx.resetKvCache();
    }

    @Override
    public void close() {
        ctx.close();
        model.close();
    }
}
