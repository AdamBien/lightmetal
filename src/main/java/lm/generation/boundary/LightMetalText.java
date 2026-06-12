package lm.generation.boundary;

import module java.base;
import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.generation.entity.Tps;
import lm.logging.control.Log;

public final class LightMetalText implements BinaryOperator<String> {

    // ZCfg.load is eager so embedders can read config (model.directory, defaults, etc.)
    // immediately after ServiceLoader discovery, before invoking apply().
    public LightMetalText() {
        ZCfg.load("lightmetal");
    }

    @Override
    public String apply(String model, String prompt) {
        return run(model, prompt, GenerationConfig.fromProperties());
    }

    public String run(String model, String prompt, GenerationConfig config) {
        var out = new StringBuilder();
        var count = new AtomicLong();
        var startNanos = new AtomicLong();
        var modelPath = Path.of(model);
        Log.system("[model loaded from %s]".formatted(modelPath.toString()));
        try (var lm = LightMetal.load(modelPath);
             var stream = lm.generate(prompt, config)) {
            stream.forEach(t -> {
                startNanos.compareAndSet(0L, System.nanoTime());
                count.incrementAndGet();
                if ("thought".equals(t.channel())) return;
                out.append(t.text());
            });
        }
        if (count.get() > 1)
            Log.system("[" + Tps.measure(count.get(), startNanos.get()) + "]");
        return out.toString();
    }
}
