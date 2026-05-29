package lm.generation.boundary;

import module java.base;
import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.generation.entity.Tps;
import lm.logging.control.Log;

public final class LightMetalProvider implements BinaryOperator<String> {

    @Override
    public String apply(String model, String prompt) {
        ZCfg.load("lightmetal");
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
                out.append(t.text());
            });
        }
        if (count.get() > 1)
            Log.system("[" + Tps.measure(count.get(), startNanos.get()) + "]");
        return out.toString();
    }
}
