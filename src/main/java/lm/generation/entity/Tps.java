package lm.generation.entity;

/// Decode throughput, measured after the first token to exclude prompt-eval time.
/// With N tokens there are N-1 decode intervals, so tps = (tokens - 1) / seconds.
public record Tps(long tokens, double seconds, double tokensPerSecond) {

    public static Tps measure(long tokens, long firstTokenNanos) {
        if (tokens < 2)
            throw new IllegalArgumentException("need at least 2 tokens to measure tps, got " + tokens);
        var seconds = (System.nanoTime() - firstTokenNanos) / 1_000_000_000.0;
        return new Tps(tokens, seconds, (tokens - 1) / seconds);
    }

    @Override
    public String toString() {
        return "%d tokens, %.1f s, %.1f tok/s".formatted(tokens, seconds, tokensPerSecond);
    }
}
