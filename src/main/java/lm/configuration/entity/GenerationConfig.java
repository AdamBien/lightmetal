package lm.configuration.entity;

import java.util.List;

public record GenerationConfig(
        int maxTokens,
        float temperature,
        float topP,
        int topK,
        float minP,
        long seed,
        String grammar,
        List<String> stopSequences) {

    public GenerationConfig {
        stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
    }

    public GenerationConfig(int maxTokens, float temperature, float topP, int topK, float minP, long seed) {
        this(maxTokens, temperature, topP, topK, minP, seed, null, List.of());
    }

    public GenerationConfig(int maxTokens, float temperature, float topP, int topK, float minP, long seed, String grammar) {
        this(maxTokens, temperature, topP, topK, minP, seed, grammar, List.of());
    }

    public static GenerationConfig defaults() {
        return new GenerationConfig(2048, 0.7f, 0.9f, 40, 0.05f, System.nanoTime(), null, List.of());
    }

    public GenerationConfig withGrammar(String grammar) {
        return new GenerationConfig(maxTokens, temperature, topP, topK, minP, seed, grammar, stopSequences);
    }

    public GenerationConfig withStopSequences(List<String> stops) {
        return new GenerationConfig(maxTokens, temperature, topP, topK, minP, seed, grammar, stops);
    }
}
