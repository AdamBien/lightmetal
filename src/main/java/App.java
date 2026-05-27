import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.generation.boundary.LightMetal;
import lm.generation.entity.Tps;
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

void runOneShot(Args parsed){
     var cfg = new GenerationConfig(
            parsed.maxTokens(),
            parsed.temperature(),
            parsed.topP(),
            parsed.topK(),
            parsed.minP(),
            parsed.seed());
    var model = parsed.model();
    var prompt = parsed.prompt();
    this.runOneShot(model,prompt,cfg);
}

void runOneShot(String model,String prompt,GenerationConfig cfg) {
   
    var count = new AtomicLong();
    var startNanos = new AtomicLong();
    try (var lm = LightMetal.load(Path.of(model));
         var stream = lm.generate(prompt, cfg)) {
        stream.forEach(t -> {
            startNanos.compareAndSet(0L, System.nanoTime());
            count.incrementAndGet();
            IO.print(t.text());
            System.out.flush();
        });
    }
    IO.println("");
    if (count.get() > 1)
        Log.system("[" + Tps.measure(count.get(), startNanos.get()) + "]");
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
    var model = ZCfg.string("model");
    var prompt = ZCfg.string("prompt");
    var maxTokens = ZCfg.integer("max-tokens", 256);
    var temperature = Float.parseFloat(ZCfg.string("temperature", "0.7"));
    var topP = Float.parseFloat(ZCfg.string("top-p", "0.9"));
    var topK = ZCfg.integer("top-k", 40);
    var minP = Float.parseFloat(ZCfg.string("min-p", "0.05"));
    var seedCfg = ZCfg.string("seed");
    var seed = seedCfg != null ? Long.parseLong(seedCfg) : System.nanoTime();
    var serve = ZCfg.bool("serve", false);
    var port = ZCfg.integer("port", 8080);
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
    HELP("-help", "show this help", true),
    MODEL("-model", "path to GGUF model file", false),
    PROMPT("-prompt", "user prompt text (omit with -serve)", false),
    MAX_TOKENS("-max-tokens", "max tokens to generate (default: 256)", true),
    TEMPERATURE("-temperature", "sampling temperature (default: 0.7)", true),
    TOP_P("-top-p", "top-p nucleus sampling (default: 0.9)", true),
    TOP_K("-top-k", "top-k sampling (default: 40)", true),
    MIN_P("-min-p", "min-p sampling (default: 0.05)", true),
    SEED("-seed", "RNG seed (default: nanoTime)", true),
    SERVE("-serve", "start HTTP server at /v1/messages instead of one-shot", true),
    PORT("-port", "HTTP port (default: 8080)", true);

    final String option;
    final String description;
    final boolean optional;

    Arg(String option, String description, boolean optional) {
        this.option = option;
        this.description = description;
        this.optional = optional;
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
                .map(a -> "  %-14s %s".formatted(a.option, a.description))
                .collect(Collectors.joining("\n"));
    }
}
