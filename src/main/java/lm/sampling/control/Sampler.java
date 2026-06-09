package lm.sampling.control;

import static lm.backend.ffm.llama_h.llama_h.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import lm.configuration.entity.GenerationConfig;

public final class Sampler implements AutoCloseable {

    private static final long DEFAULT_MIN_KEEP = 1L;

    private final MemorySegment chain;

    private Sampler(MemorySegment chain) {
        this.chain = chain;
    }

    public static Sampler create(GenerationConfig cfg, MemorySegment vocab) {
        try (var arena = Arena.ofConfined()) {
            var params = llama_sampler_chain_default_params(arena);
            var chain = llama_sampler_chain_init(params);
            llama_sampler_chain_add(chain, llama_sampler_init_top_k(cfg.topK()));
            llama_sampler_chain_add(chain, llama_sampler_init_top_p(cfg.topP(), DEFAULT_MIN_KEEP));
            llama_sampler_chain_add(chain, llama_sampler_init_min_p(cfg.minP(), DEFAULT_MIN_KEEP));
            llama_sampler_chain_add(chain, llama_sampler_init_temp(cfg.temperature()));
            llama_sampler_chain_add(chain, llama_sampler_init_dist((int) cfg.seed()));
            return new Sampler(chain);
        }
    }

    public int sample(MemorySegment ctx, int idx) {
        return llama_sampler_sample(chain, ctx, idx);
    }

    @Override
    public void close() {
        llama_sampler_free(chain);
    }
}
