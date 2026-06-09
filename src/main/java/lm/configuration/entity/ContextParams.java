package lm.configuration.entity;

public record ContextParams(int contextLength, int batchSize) {

    public static ContextParams defaults() {
        return new ContextParams(32768, 2048);
    }
}
