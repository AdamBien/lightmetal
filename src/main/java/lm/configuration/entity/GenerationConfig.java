package lm.configuration.entity;

public record GenerationConfig(
        int maxTokens,
        float temperature,
        float topP,
        int topK,
        float minP,
        long seed,
        String grammar) {

    public GenerationConfig(int maxTokens, float temperature, float topP, int topK, float minP, long seed) {
        this(maxTokens, temperature, topP, topK, minP, seed, null);
    }

    public static GenerationConfig defaults() {
        return new GenerationConfig(256, 0.7f, 0.9f, 40, 0.05f, System.nanoTime(), null);
    }

    public GenerationConfig withGrammar(String grammar) {
        return new GenerationConfig(maxTokens, temperature, topP, topK, minP, seed, grammar);
    }
}
