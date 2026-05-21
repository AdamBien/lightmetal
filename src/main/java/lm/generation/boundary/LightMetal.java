package lm.generation.boundary;

import java.nio.file.Path;
import java.util.stream.Stream;

import lm.backend.control.Backend;
import lm.configuration.entity.GenerationConfig;
import lm.configuration.entity.Token;
import lm.prompting.control.PromptTemplate;

public final class LightMetal implements AutoCloseable {

    private static final boolean ADD_BOS = true;

    private final Backend.Model model;
    private final Backend.Context ctx;

    private LightMetal(Backend.Model model, Backend.Context ctx) {
        this.model = model;
        this.ctx = ctx;
    }

    public static LightMetal load(Path gguf, Backend backend) {
        var model = backend.loadModel(gguf);
        var ctx = model.newContext(Backend.ContextParams.defaults());
        return new LightMetal(model, ctx);
    }

    public Stream<Token> generate(String userPrompt, GenerationConfig cfg) {
        return complete(PromptTemplate.mistralInstruct(userPrompt), cfg);
    }

    public Stream<Token> complete(String formattedPrompt, GenerationConfig cfg) {
        var promptTokens = ctx.tokenize(formattedPrompt, ADD_BOS);
        return ctx.generate(promptTokens, cfg);
    }

    @Override
    public void close() {
        ctx.close();
        model.close();
    }
}
