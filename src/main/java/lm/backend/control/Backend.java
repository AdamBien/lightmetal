package lm.backend.control;

import java.nio.file.Path;
import java.util.stream.Stream;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.configuration.entity.Token;

public sealed interface Backend
        permits NativeBackend, PureJavaCpuBackend, VulkanBackend {

    Model loadModel(Path gguf);

    interface Model extends AutoCloseable {
        Context newContext(ContextParams params);
        int vocabSize();
        int contextLength();
        @Override void close();
    }

    interface Context extends AutoCloseable {
        int[] tokenize(String text, boolean addBos);
        String detokenize(int[] tokens);
        Stream<Token> generate(int[] promptTokens, GenerationConfig cfg);
        void resetKvCache();
        @Override void close();
    }

    record ContextParams(int contextLength, int batchSize, int gpuLayers, long seed) {
        public static ContextParams defaults() {
            return new ContextParams(
                    ZCfg.integer("context.length", 32768),
                    ZCfg.integer("context.batch.size", 2048),
                    ZCfg.integer("context.gpu.layers", -1),
                    ZCfg.integer("context.seed", 0));
        }
    }
}
