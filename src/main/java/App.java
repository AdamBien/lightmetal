import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lm.backend.control.Backend;
import lm.backend.control.NativeBackend;
import lm.backend.control.PureJavaCpuBackend;
import lm.backend.control.VulkanBackend;
import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.generation.boundary.LightMetal;

void main(String... args) {
    ZCfg.load("lightmetal");
    var parsed = parseArgs(args);
    if (parsed.help() || parsed.model() == null || parsed.prompt() == null) {
        printUsage();
        return;
    }
    var backend = backendFor(parsed.backend());
    var cfg = new GenerationConfig(
            parsed.maxTokens(),
            parsed.temperature(),
            parsed.topP(),
            parsed.topK(),
            parsed.minP(),
            parsed.seed());
    var count = new long[1];
    var startNanos = new long[1];
    try (var lm = LightMetal.load(Path.of(parsed.model()), backend)) {
        try (var stream = lm.generate(parsed.prompt(), cfg)) {
            stream.forEach(t -> {
                if (startNanos[0] == 0L) startNanos[0] = System.nanoTime();
                count[0]++;
                IO.print(t.text());
                System.out.flush();
            });
        }
    }
    IO.println("");
    if (count[0] > 1) {
        var seconds = (System.nanoTime() - startNanos[0]) / 1_000_000_000.0;
        System.err.printf("%n[%d tokens, %.1f s, %.1f tok/s]%n",
                count[0], seconds, (count[0] - 1) / seconds);
    }
}

Backend backendFor(String name) {
    return switch (name) {
        case "native" -> new NativeBackend();
        case "cpu" -> new PureJavaCpuBackend();
        case "vulkan" -> new VulkanBackend();
        case null, default -> throw new IllegalArgumentException("unknown backend: " + name);
    };
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
    var backend = ZCfg.string("backend", "native");
    var maxTokens = ZCfg.integer("max-tokens", 256);
    var temperature = Float.parseFloat(ZCfg.string("temperature", "0.7"));
    var topP = Float.parseFloat(ZCfg.string("top-p", "0.9"));
    var topK = ZCfg.integer("top-k", 40);
    var minP = Float.parseFloat(ZCfg.string("min-p", "0.05"));
    var seedCfg = ZCfg.string("seed");
    var seed = seedCfg != null ? Long.parseLong(seedCfg) : System.nanoTime();
    var help = false;
    for (var i = 0; i < args.length; i++) {
        var raw = args[i];
        switch (Arg.from(raw)) {
            case HELP -> help = true;
            case MODEL -> { model = valueOf(raw, args, i); if (!raw.contains(":")) i++; }
            case PROMPT -> { prompt = valueOf(raw, args, i); if (!raw.contains(":")) i++; }
            case BACKEND -> { backend = valueOf(raw, args, i); if (!raw.contains(":")) i++; }
            case MAX_TOKENS -> { maxTokens = Integer.parseInt(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case TEMPERATURE -> { temperature = Float.parseFloat(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case TOP_P -> { topP = Float.parseFloat(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case TOP_K -> { topK = Integer.parseInt(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case MIN_P -> { minP = Float.parseFloat(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case SEED -> { seed = Long.parseLong(valueOf(raw, args, i)); if (!raw.contains(":")) i++; }
            case null -> {
                IO.println("unknown option: " + raw);
                printUsage();
                System.exit(1);
            }
        }
    }
    return new Args(model, prompt, backend, maxTokens, temperature, topP, topK, minP, seed, help);
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
        String backend,
        int maxTokens,
        float temperature,
        float topP,
        int topK,
        float minP,
        long seed,
        boolean help) {}

enum Arg {
    HELP("-help", "show this help", true),
    MODEL("-model", "path to GGUF model file", false),
    PROMPT("-prompt", "user prompt text", false),
    BACKEND("-backend", "native | cpu | vulkan (default: native)", true),
    MAX_TOKENS("-max-tokens", "max tokens to generate (default: 256)", true),
    TEMPERATURE("-temperature", "sampling temperature (default: 0.7)", true),
    TOP_P("-top-p", "top-p nucleus sampling (default: 0.9)", true),
    TOP_K("-top-k", "top-k sampling (default: 40)", true),
    MIN_P("-min-p", "min-p sampling (default: 0.05)", true),
    SEED("-seed", "RNG seed (default: nanoTime)", true);

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
