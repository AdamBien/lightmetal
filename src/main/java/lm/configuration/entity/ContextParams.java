package lm.configuration.entity;

public record ContextParams(int contextLength, int batchSize, int gpuLayers, long seed) {

    public static ContextParams defaults() {
        return new ContextParams(32768, 2048, -1, 0);
    }
}
