/// # Generation
/// > Compose backend, prompting, sampling, and tokenization into one
/// > load-prompt-stream facade for the CLI, the HTTP front-end, and
/// > zero-coupling embedders.
///
/// ## Boundary
///
/// - `load-model` — inspect and load a GGUF, ready for generation
/// - `generate-stream` — one prompt in, a channel-tagged token stream out
/// - `chat` — system + tools + conversation turns in, a parsed response out
/// - `embed-text` — zero-coupling one-shot completion: (model, prompt) → text
/// - `embed-chat` — zero-coupling chat: Anthropic Messages JSON in, JSON
///   out, over a warm single-model session
/// - `unload-model` — release the loaded model
///
/// ## Requirements
///
/// ### R1: Load
///
/// - R1.1 — When a model is loaded, the BC shall read the GGUF metadata and
///   derive generation defaults (BOS policy) from it.
/// - R1.2 — If the metadata cannot be read, then the BC shall fall back to
///   built-in defaults and continue loading.
/// - R1.3 — The BC shall not widen the context window to the GGUF's
///   advertised maximum — the configured length wins. _(why: the GGUF
///   reports what the model supports, not what fits the host's RAM;
///   auto-applying gemma-4's 262K context froze a 64 GB machine)_
/// - R1.4 — If the model's family is unknown at generation time, then the BC
///   shall fail, naming the model and the registry to extend.
///
/// ### R2: Generate and chat
///
/// - R2.1 — When a prompt is generated, the BC shall render it through the
///   model family's template and tag each emitted token with its channel
///   (thought vs final).
/// - R2.2 — When a chat completes, the BC shall parse the output into text
///   or tool calls, with a stop reason (end-turn, token budget, tool-use)
///   and token usage.
/// - R2.3 — The BC shall add the template's stop sequences to the caller's
///   generation settings for chat.
/// - R2.4 — When a chat starts, the BC shall reset the session so turns do
///   not leak across requests.
///
/// ### R3: Text embedding
///
/// - R3.1 — The BC shall be discoverable by hosts through the JDK service
///   loader under a JDK-owned descriptor, driveable with strings alone.
///   _(why: hosts must not compile against lightmetal types; the JDK-owned
///   descriptor name means hosts filter providers by `instanceof`, not
///   iteration order)_
/// - R3.2 — When a text completion is invoked, the BC shall load the model,
///   stream the completion, drop reasoning-channel tokens from the result,
///   and close the model again. _(why: per-call lifecycle suits sporadic
///   one-shot use; long-lived hosts run the chat session or the HTTP
///   front-end instead)_
///
/// ### R4: Chat embedding
///
/// - R4.1 — When a chat request arrives, the BC shall take the model from
///   the request, falling back to configuration.
/// - R4.2 — If no model is named in the request or the configuration, then
///   the BC shall fail, naming both places to set it.
/// - R4.3 — While the requested model matches the loaded one, the BC shall
///   reuse the warm session; a different model swaps it (close old, load
///   new).
/// - R4.4 — The BC shall serialize chat invocations — one native context,
///   one in-flight request.
///
/// ## Entities
///
/// - Response, Tps
///
/// ## Out of scope
///
/// - HTTP transport and protocol shapes — http BC.
/// - Template wire formats and family resolution rules — prompting BC.
/// - Sampler-chain assembly — sampling BC.
package lm.generation;
