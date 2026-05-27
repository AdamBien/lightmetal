package lm.generation.boundary;

import module java.base;
import lm.configuration.entity.GenerationConfig;
import lm.generation.entity.Tps;
import lm.logging.control.Log;

public interface OneShot {

    static void run(String model, String prompt) {
        run(model, prompt, GenerationConfig.defaults());
    }

    static void run(String model, String prompt, GenerationConfig cfg) {
        var count = new AtomicLong();
        var startNanos = new AtomicLong();
        try (var lm = LightMetal.load(Path.of(model));
             var stream = lm.generate(prompt, cfg)) {
            stream.forEach(t -> {
                startNanos.compareAndSet(0L, System.nanoTime());
                count.incrementAndGet();
                System.out.print(t.text());
                System.out.flush();
            });
        }
        System.out.println();
        if (count.get() > 1)
            Log.system("[" + Tps.measure(count.get(), startNanos.get()) + "]");
    }
}
