package lm.backend.control;

/// Stops a run that has collapsed onto a single token. A model whose forward pass has
/// broken down — a quantization the backend miscomputes, an architecture the GPU kernels
/// get wrong — emits the same id from the first sampled token and never reaches an
/// end-of-generation token, so without this the whole budget is spent on one repeated
/// piece. Healthy output never repeats one id 64 times in a row.
public final class RepeatGuard {

    private final int limit;
    private int lastId = Integer.MIN_VALUE;
    private int run;

    /// `limit` of 0 or less disables the guard.
    public RepeatGuard(int limit) {
        this.limit = limit;
    }

    public boolean degenerate(int tokenId) {
        if (limit <= 0) return false;
        run = tokenId == lastId ? run + 1 : 1;
        lastId = tokenId;
        return run >= limit;
    }

    public int run() {
        return run;
    }
}
