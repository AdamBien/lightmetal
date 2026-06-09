package lm.configuration.entity;

import java.util.List;

import lm.configuration.control.ZCfg;

public record GenerationConfig(
        int maxTokens,
        float temperature,
        float topP,
        int topK,
        float minP,
        long seed,
        List<String> stopSequences) {

    public GenerationConfig {
        stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
    }

    public GenerationConfig(int maxTokens, float temperature, float topP, int topK, float minP, long seed) {
        this(maxTokens, temperature, topP, topK, minP, seed, List.of());
    }

    public static GenerationConfig defaults() {
        return new GenerationConfig(2048, 0.7f, 0.9f, 40, 0.05f, System.nanoTime(), List.of());
    }

    public static GenerationConfig fromProperties() {
        var d = defaults();
        var seed = ZCfg.string("seed");
        return new GenerationConfig(
                ZCfg.integer("max-tokens", d.maxTokens()),
                Float.parseFloat(ZCfg.string("temperature", String.valueOf(d.temperature()))),
                Float.parseFloat(ZCfg.string("top-p", String.valueOf(d.topP()))),
                ZCfg.integer("top-k", d.topK()),
                Float.parseFloat(ZCfg.string("min-p", String.valueOf(d.minP()))),
                seed != null ? Long.parseLong(seed) : d.seed());
    }

    public GenerationConfig withStopSequences(List<String> stops) {
        return new GenerationConfig(maxTokens, temperature, topP, topK, minP, seed, stops);
    }
}
