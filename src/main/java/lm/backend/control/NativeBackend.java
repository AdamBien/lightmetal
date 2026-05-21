package lm.backend.control;

import static lm.backend.ffm.llama_h.llama_h.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import lm.backend.ffm.llama_h.llama_context_params;
import lm.backend.ffm.llama_h.llama_model_params;
import lm.configuration.entity.GenerationConfig;
import lm.configuration.entity.Token;
import lm.sampling.control.Sampler;
import lm.tokenization.control.Tokenizer;

public final class NativeBackend implements Backend {

    private static final int LAST_LOGITS_IDX = -1;
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private static void ensureBackendInit() {
        if (INITIALIZED.compareAndSet(false, true)) {
            llama_backend_init();
            Runtime.getRuntime().addShutdownHook(new Thread(NativeBackend::shutdown));
        }
    }

    private static void shutdown() {
        llama_backend_free();
    }

    @Override
    public Model loadModel(Path gguf) {
        ensureBackendInit();
        return new NativeModel(gguf);
    }

    static final class NativeModel implements Model {

        private final Arena modelArena = Arena.ofShared();
        private final MemorySegment model;
        private final MemorySegment vocab;

        NativeModel(Path gguf) {
            var pathSeg = modelArena.allocateFrom(gguf.toAbsolutePath().toString());
            var params = llama_model_default_params(modelArena);
            llama_model_params.n_gpu_layers(params, -1);
            this.model = llama_model_load_from_file(pathSeg, params);
            if (MemorySegment.NULL.equals(model)) {
                modelArena.close();
                throw new IllegalStateException("failed to load model: " + gguf);
            }
            this.vocab = llama_model_get_vocab(model);
        }

        @Override
        public Context newContext(ContextParams cfg) {
            return new NativeContext(this, cfg);
        }

        @Override
        public int vocabSize() {
            return llama_vocab_n_tokens(vocab);
        }

        @Override
        public int contextLength() {
            return llama_model_n_ctx_train(model);
        }

        @Override
        public void close() {
            llama_model_free(model);
            modelArena.close();
        }
    }

    static final class NativeContext implements Context {

        private final NativeModel parent;
        private final MemorySegment ctx;
        private final Tokenizer tokenizer;

        NativeContext(NativeModel parent, ContextParams cfg) {
            this.parent = parent;
            try (var arena = Arena.ofConfined()) {
                var params = llama_context_default_params(arena);
                llama_context_params.n_ctx(params, cfg.contextLength());
                llama_context_params.n_batch(params, cfg.batchSize());
                this.ctx = llama_new_context_with_model(parent.model, params);
            }
            if (MemorySegment.NULL.equals(ctx)) {
                throw new IllegalStateException("failed to create context");
            }
            this.tokenizer = new Tokenizer(parent.vocab);
        }

        @Override
        public int[] tokenize(String text, boolean addBos) {
            return tokenizer.tokenize(text, addBos, true);
        }

        @Override
        public String detokenize(int[] tokens) {
            return tokenizer.detokenize(tokens, false);
        }

        @Override
        public Stream<Token> generate(int[] promptTokens, GenerationConfig cfg) {
            decodePrompt(promptTokens);
            return tokenStream(cfg);
        }

        private void decodePrompt(int[] promptTokens) {
            try (var arena = Arena.ofConfined()) {
                var tokSeg = arena.allocateFrom(ValueLayout.JAVA_INT, promptTokens);
                var batch = llama_batch_get_one(arena, tokSeg, promptTokens.length);
                if (llama_decode(ctx, batch) != 0) {
                    throw new IllegalStateException("prompt decode failed");
                }
            }
        }

        private Stream<Token> tokenStream(GenerationConfig cfg) {
            var sampler = Sampler.create(cfg, parent.vocab);
            var state = new GenState(cfg.maxTokens());
            return Stream
                    .generate(() -> nextToken(sampler, state))
                    .takeWhile(t -> t != null)
                    .onClose(sampler::close);
        }

        private Token nextToken(Sampler sampler, GenState state) {
            if (state.done()) return null;
            var id = sampler.sample(ctx, LAST_LOGITS_IDX);
            if (llama_vocab_is_eog(parent.vocab, id)) {
                state.stop();
                return null;
            }
            sampler.accept(id);
            var piece = tokenizer.tokenToPiece(id, false);
            state.advance();
            feedBack(id, state);
            return new Token(id, piece);
        }

        private void feedBack(int id, GenState state) {
            try (var arena = Arena.ofConfined()) {
                var tokSeg = arena.allocateFrom(ValueLayout.JAVA_INT, id);
                var batch = llama_batch_get_one(arena, tokSeg, 1);
                if (llama_decode(ctx, batch) != 0) state.stop();
            }
        }

        @Override
        public void resetKvCache() {
            llama_memory_clear(llama_get_memory(ctx), true);
        }

        @Override
        public void close() {
            llama_free(ctx);
        }
    }

    private static final class GenState {
        private final int max;
        private int produced;
        private boolean stopped;

        GenState(int max) {
            this.max = max;
        }

        boolean done() {
            return stopped || produced >= max;
        }

        void advance() {
            produced++;
        }

        void stop() {
            stopped = true;
        }
    }
}
