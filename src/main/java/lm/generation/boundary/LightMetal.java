package lm.generation.boundary;

import module java.base;
import lm.backend.control.Context;
import lm.backend.control.Model;
import lm.configuration.control.ZCfg;
import lm.configuration.entity.ContextParams;
import lm.configuration.entity.GenerationConfig;
import lm.configuration.entity.Token;
import lm.http.entity.AnthropicMessagesRequest.UserText;
import lm.inspection.boundary.Inspector;
import lm.inspection.entity.GGUFMetadata;
import lm.logging.control.Log;
import lm.prompting.control.ModelFamily;

public final class LightMetal implements AutoCloseable {

    static final String INSPECTOR_PREFIX = "[inspector]";
    static final int STRING_PREVIEW_LIMIT = 80;

    private final Model model;
    private final Context ctx;
    private final GGUFMetadata metadata;
    private final boolean addBos;

    private LightMetal(Model model, Context ctx, GGUFMetadata metadata) {
        this.model = model;
        this.ctx = ctx;
        this.metadata = metadata;
        this.addBos = metadata.addBosToken().orElse(true);
    }

    public static LightMetal load(Path gguf) {
        var metadata = readMetadata(gguf);
        logMetadata(metadata);
        var model = new Model(gguf);
        var ctx = model.newContext(contextParams());
        return new LightMetal(model, ctx, metadata);
    }

    public GGUFMetadata metadata() {
        return metadata;
    }

    public Stream<Token> generate(String userPrompt, GenerationConfig cfg) {
        var template = ModelFamily.from(metadata)
                .orElseThrow(() -> new IllegalStateException(
                        "no ModelFamily entry for GGUF name=" + metadata.name().orElse("?")
                                + " — add it to lm.prompting.control.ModelFamily"))
                .template();
        var rendered = template.render("", List.of(),
                List.of(new UserText(userPrompt)));
        return complete(rendered, cfg);
    }

    public Stream<Token> complete(String formattedPrompt, GenerationConfig cfg) {
        var promptTokens = ctx.tokenize(formattedPrompt, addBos);
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

    private static GGUFMetadata readMetadata(Path gguf) {
        try {
            return Inspector.inspect(gguf);
        } catch (RuntimeException e) {
            Log.system("%s could not read metadata: %s — using built-in defaults"
                    .formatted(INSPECTOR_PREFIX, e.getMessage()));
            return GGUFMetadata.empty();
        }
    }

    static void logMetadata(GGUFMetadata meta) {
        Log.system("%s arch=%s, name=%s, GGUF v%d, %d kv pairs".formatted(
                INSPECTOR_PREFIX,
                meta.architecture().orElse("?"),
                meta.name().orElse("?"),
                meta.version(),
                meta.kvs().size()));
        for (var key : new TreeSet<>(meta.kvs().keySet())) {
            Log.debug("%s   %s = %s".formatted(INSPECTOR_PREFIX, key, formatValue(meta.kvs().get(key))));
        }
    }

    static String formatValue(Object value) {
        return switch (value) {
            case null -> "null";
            case String s when s.length() > STRING_PREVIEW_LIMIT -> "<string, " + s.length() + " chars>";
            case String s -> s.replace("\n", "\\n");
            case Object[] arr -> "<array of " + arr.length + " entries>";
            default -> String.valueOf(value);
        };
    }

    // context.length is NOT auto-bumped from the GGUF metadata: the GGUF reports
    // what the model SUPPORTS, not what fits the user's RAM. Auto-applying gemma-4's
    // 262144 ctx on a 31B Q8 model swaps to disk and freezes the host.
    private static ContextParams contextParams() {
        var d = ContextParams.defaults();
        return new ContextParams(
                ZCfg.integer("context.length", d.contextLength()),
                ZCfg.integer("context.batch.size", d.batchSize()),
                ZCfg.integer("context.gpu.layers", d.gpuLayers()),
                ZCfg.integer("context.seed", (int) d.seed()));
    }
}
