import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.generation.boundary.LightMetal;
import lm.generation.boundary.OneShot;
import lm.http.boundary.HttpAPI;
import lm.logging.control.Log;
import lm.version.control.Version;

void main(String... args) {
    ZCfg.load("lightmetal");
    Log.system("[lightmetal " + Version.VALUE + "]");
    var parsed = parseArgs(args);
    if (parsed.help() || parsed.model() == null || (!parsed.serve() && parsed.prompt() == null)) {
        printUsage();
        return;
    }
    if (parsed.serve()) {
        runServer(parsed);
        return;
    }
    runOneShot(parsed);
}

void runOneShot(Args parsed) {
    var cfg = new GenerationConfig(
            parsed.maxTokens(),
            parsed.temperature(),
            parsed.topP(),
            parsed.topK(),
            parsed.minP(),
            parsed.seed());
    OneShot.run(parsed.model(), parsed.prompt(), cfg);
}

void runServer(Args parsed) {
    var lm = LightMetal.load(Path.of(parsed.model()));
    var api = HttpAPI.start(lm, parsed.port());
    var latch = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        Log.system("[shutting down]");
        api.close();
        lm.close();
        latch.countDown();
    }));
    Log.success("[lightmetal serving %s on http://localhost:%d/v1/messages]"
            .formatted(lm.metadata().name().orElse("model"), api.port()));
    try {
        latch.await();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}

void printUsage() {
    IO.println("""
            Usage: lightmetal -model <gguf> -prompt <text> [options]

            Required:
            %s

            Options:
            %s
            """.formatted(Arg.requiredUsage(), Arg.optionalUsage()));
}

Args parseArgs(String[] args) {
    var d = GenerationConfig.defaults();
    var model = ZCfg.string("model");
    var prompt = ZCfg.string("prompt");
    var maxTokens = ZCfg.integer("max-tokens", d.maxTokens());
    var temperature = Float.parseFloat(ZCfg.string("temperature", String.valueOf(d.temperature())));
    var topP = Float.parseFloat(ZCfg.string("top-p", String.valueOf(d.topP())));
    var topK = ZCfg.integer("top-k", d.topK());
    var minP = Float.parseFloat(ZCfg.string("min-p", String.valueOf(d.minP())));
    var seedCfg = ZCfg.string("seed");
    var seed = seedCfg != null ? Long.parseLong(seedCfg) : System.nanoTime();
    var serve = ZCfg.bool("serve", (boolean) Arg.SERVE.defaultValue);
    var port = ZCfg.integer("port", (int) Arg.PORT.defaultValue);
    var help = false;
    for (var i = 0; i < args.length; i++) {
        var raw = args[i];
        switch (Arg.from(raw)) {
            case HELP -> help = true;
            case MODEL -> { model = valueOf(raw, args, i); if (!raw.contains(":")) i++; }
            case PROMPT -> { prompt = valueOf(raw, args, i); if (!raw.contains(":")) i++; }
            case MAX_TOKENS -> { maxTokens = Integer.parseInt(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case TEMPERATURE -> { temperature = Float.parseFloat(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case TOP_P -> { topP = Float.parseFloat(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case TOP_K -> { topK = Integer.parseInt(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case MIN_P -> { minP = Float.parseFloat(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case SEED -> { seed = Long.parseLong(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case SERVE -> serve = true;
            case PORT -> { port = Integer.parseInt(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case null -> {
                IO.println("unknown option: " + raw);
                printUsage();
                System.exit(1);
            }
        }
    }
    return new Args(model, prompt, maxTokens, temperature, topP, topK, minP, seed, serve, port, help);
}

String valueOf(String raw, String[] args, int index) {
    if (raw.contains(":")) return raw.substring(raw.indexOf(':') + 1);
    if (index + 1 >= args.length) {
        IO.println("missing value for " + raw);
        System.exit(1);
    }
    return args[index + 1];
}

record Args(
        String model,
        String prompt,
        int maxTokens,
        float temperature,
        float topP,
        int topK,
        float minP,
        long seed,
        boolean serve,
        int port,
        boolean help) {}

enum Arg {
    HELP("-help", "show this help", true, null),
    MODEL("-model", "path to GGUF model file", false, null),
    PROMPT("-prompt", "user prompt text (omit with -serve)", false, null),
    MAX_TOKENS("-max-tokens", "max tokens to generate", true,
               GenerationConfig.defaults().maxTokens()),
    TEMPERATURE("-temperature", "sampling temperature", true,
                GenerationConfig.defaults().temperature()),
    TOP_P("-top-p", "top-p nucleus sampling", true,
          GenerationConfig.defaults().topP()),
    TOP_K("-top-k", "top-k sampling", true,
          GenerationConfig.defaults().topK()),
    MIN_P("-min-p", "min-p sampling", true,
          GenerationConfig.defaults().minP()),
    SEED("-seed", "RNG seed (default: nanoTime)", true, null),
    SERVE("-serve", "start HTTP server at /v1/messages instead of one-shot", true, false),
    PORT("-port", "HTTP port", true, 8080);

    final String option;
    final String description;
    final boolean optional;
    final Object defaultValue;

    Arg(String option, String description, boolean optional, Object defaultValue) {
        this.option = option;
        this.description = description;
        this.optional = optional;
        this.defaultValue = defaultValue;
    }

    boolean matches(String value) {
        return option.equals(value) || value.startsWith(option + ":");
    }

    static Arg from(String value) {
        return Stream.of(values()).filter(a -> a.matches(value)).findFirst().orElse(null);
    }

    static String requiredUsage() {
        return Stream.of(values()).filter(a -> !a.optional)
                .map(a -> "  %-14s %s".formatted(a.option, a.description))
                .collect(Collectors.joining("\n"));
    }

    static String optionalUsage() {
        return Stream.of(values()).filter(a -> a.optional)
                .map(a -> {
                    var suffix = a.defaultValue != null ? " (default: %s)".formatted(a.defaultValue) : "";
                    return "  %-14s %s%s".formatted(a.option, a.description, suffix);
                })
                .collect(Collectors.joining("\n"));
    }
}
