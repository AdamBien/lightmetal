/// # Backend
/// > Own the native llama.cpp binding — model and decoding-session lifecycle
/// > behind plain Java types.
///
/// ## Boundary
///
/// - `load-model` — load a GGUF's weights and vocabulary, fully GPU-offloaded
/// - `open-session` — create a decoding session (KV cache, batch size,
///   context window) on a loaded model
/// - `generate-tokens` — decode a prompt and stream sampled tokens
/// - `reset-session` — clear the session's KV cache for the next conversation
/// - `unload-model` — release session and model resources deterministically
///
/// ## Requirements
///
/// ### R1: Load a model
///
/// - R1.1 — When a GGUF is loaded, the BC shall offload every layer to the
///   GPU and expose the model's vocabulary and training context length.
/// - R1.2 — If the model file cannot be loaded, then the BC shall fail,
///   naming the file.
/// - R1.3 — The BC shall initialise the native backend exactly once per
///   process and release it at shutdown.
///
/// ### R2: Open a decoding session
///
/// - R2.1 — When a session is opened, the BC shall size the context window
///   from the caller's settings, falling back to the model's training
///   context length.
/// - R2.2 — If the native context cannot be created, then the BC shall fail.
///
/// ### R3: Generate tokens
///
/// - R3.1 — When a prompt is decoded, the BC shall feed it in batch-size
///   chunks, so prompts longer than one batch decode correctly.
/// - R3.2 — If a prompt chunk fails to decode, then the BC shall fail,
///   reporting the sizes involved (tokens, offset, chunk, batch, context).
/// - R3.3 — While generating, the BC shall emit sampled tokens until
///   end-of-generation, the token budget, or a stop sequence is reached.
/// - R3.4 — The BC shall not emit the end-of-generation token itself.
/// - R3.5 — While a stop sequence spans several emitted pieces, the BC shall
///   still detect it. _(why: multi-token stop markers are the norm; matching
///   only whole pieces would let stops slip through)_
/// - R3.6 — When the token stream closes, the BC shall release the sampler
///   chain.
///
/// ### R4: Reset a session
///
/// - R4.1 — When a session is reset, the BC shall clear its KV cache so the
///   next prompt starts fresh.
///
/// ## Out of scope
///
/// - Text ↔ token-id conversion — the tokenization BC's contract, surfaced
///   on the session only as a convenience.
/// - Sampler-chain assembly and ordering — sampling BC.
/// - Prompt formats and chat templates — prompting BC.
/// - The vendored jextract FFM bindings (`lm.backend.ffm`) — generated
///   output of `src/main/scripts/jextract.sh`, no business logic ever lives
///   there.
///
/// ## Notes
///
/// The only place in the application that talks to `libllama.dylib`. Every
/// other BC works against the Java types exposed here and never allocates a
/// `MemorySegment` or opens an `Arena`.
///
/// llama.cpp contexts are not thread-safe. No internal locking — callers
/// serialize access; the HTTP front-end runs on a single-threaded executor
/// for exactly this reason.
package lm.backend;
