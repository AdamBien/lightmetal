package lm.backend.control;

import static lm.backend.ffm.llama_h.llama_h.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.stream.Stream;

import lm.backend.ffm.llama_h.llama_context_params;
import lm.configuration.entity.ContextParams;
import lm.configuration.entity.GenerationConfig;
import lm.configuration.entity.Token;
import lm.logging.control.Log;
import lm.sampling.control.Sampler;
import lm.tokenization.control.Tokenizer;

public final class Context implements AutoCloseable {

    private static final int LAST_LOGITS_IDX = -1;

    private final Model model;
    private final MemorySegment ctx;
    private final Tokenizer tokenizer;

    Context(Model model, ContextParams cfg) {
        this.model = model;
        var nCtx = cfg.contextLength() > 0 ? cfg.contextLength() : llama_model_n_ctx_train(model.handle);
        try (var arena = Arena.ofConfined()) {
            var params = llama_context_default_params(arena);
            llama_context_params.n_ctx(params, nCtx);
            llama_context_params.n_batch(params, nCtx);
            llama_context_params.n_ubatch(params, cfg.batchSize());
            this.ctx = llama_new_context_with_model(model.handle, params);
        }
        if (MemorySegment.NULL.equals(ctx)) {
            throw new IllegalStateException("failed to create context");
        }
        this.tokenizer = new Tokenizer(model.vocab);
        Log.system("[ctx] n_ctx=" + llama_n_ctx(ctx) + " n_ubatch=" + cfg.batchSize());
    }

    public int[] tokenize(String text, boolean addBos) {
        return tokenizer.tokenize(text, addBos, true);
    }

    public String detokenize(int[] tokens) {
        return tokenizer.detokenize(tokens, false);
    }

    public Stream<Token> generate(int[] promptTokens, GenerationConfig cfg) {
        decodePrompt(promptTokens);
        return tokenStream(cfg);
    }

    public void resetKvCache() {
        llama_memory_clear(llama_get_memory(ctx), true);
    }

    @Override
    public void close() {
        llama_free(ctx);
    }

    private void decodePrompt(int[] promptTokens) {
        try (var arena = Arena.ofConfined()) {
            var tokSeg = arena.allocate(ValueLayout.JAVA_INT, promptTokens.length);
            MemorySegment.copy(promptTokens, 0, tokSeg, ValueLayout.JAVA_INT, 0, promptTokens.length);
            var batch = llama_batch_get_one(arena, tokSeg, promptTokens.length);
            if (llama_decode(ctx, batch) != 0) {
                throw new IllegalStateException(
                        "prompt decode failed: tokens=" + promptTokens.length
                                + " n_ctx=" + llama_n_ctx(ctx));
            }
        }
    }

    private Stream<Token> tokenStream(GenerationConfig cfg) {
        var sampler = Sampler.create(cfg, model.vocab);
        var state = new GenState(cfg.maxTokens(), cfg.stopSequences());
        return Stream
                .generate(() -> nextToken(sampler, state))
                .takeWhile(t -> t != null)
                .onClose(sampler::close);
    }

    private Token nextToken(Sampler sampler, GenState state) {
        if (state.done()) return null;
        var id = sampler.sample(ctx, LAST_LOGITS_IDX);
        if (llama_vocab_is_eog(model.vocab, id)) {
            state.stop();
            return null;
        }
        var piece = tokenizer.tokenToPiece(id, true);
        state.advance();
        state.observe(piece);
        if (!state.done()) {
            feedBack(id, state);
        }
        return new Token(id, piece);
    }

    private void feedBack(int id, GenState state) {
        try (var arena = Arena.ofConfined()) {
            var tokSeg = arena.allocateFrom(ValueLayout.JAVA_INT, id);
            var batch = llama_batch_get_one(arena, tokSeg, 1);
            if (llama_decode(ctx, batch) != 0) state.stop();
        }
    }

    private static final class GenState {
        private final int max;
        private final List<String> stops;
        private final int tailCap;
        private final StringBuilder tail;
        private int produced;
        private boolean stopped;

        GenState(int max, List<String> stops) {
            this.max = max;
            this.stops = stops == null ? List.of() : stops;
            var longest = 0;
            for (var s : this.stops) longest = Math.max(longest, s.length());
            this.tailCap = longest;
            this.tail = longest == 0 ? null : new StringBuilder(longest * 2);
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

        void observe(String piece) {
            if (tail == null || piece == null || piece.isEmpty()) return;
            tail.append(piece);
            if (tail.length() > tailCap * 2) {
                tail.delete(0, tail.length() - tailCap);
            }
            for (var s : stops) {
                if (endsWith(tail, s)) {
                    Log.debug("[stop] matched " + s + " after " + produced + " tokens");
                    stop();
                    return;
                }
            }
        }

        private static boolean endsWith(StringBuilder buf, String suffix) {
            var n = suffix.length();
            if (buf.length() < n) return false;
            var offset = buf.length() - n;
            for (var i = 0; i < n; i++) {
                if (buf.charAt(offset + i) != suffix.charAt(i)) return false;
            }
            return true;
        }
    }
}
