/// # Http
/// > Anthropic- and OpenAI-compatible front-ends over one loaded model, so
/// > existing clients switch by base URL alone.
///
/// ## Boundary
///
/// - `serve-model` — load a GGUF and expose it over HTTP until shutdown
/// - `start-api` — attach the front-end to an already-loaded model,
///   reporting the bound port
/// - `stop-api` — stop the server and its request executor
///
/// ## Requirements
///
/// ### R1: Lifecycle and threading
///
/// - R1.1 — When the front-end starts, the BC shall serve the Anthropic
///   messages, OpenAI chat-completions, and model-listing resources and
///   report the bound port.
/// - R1.2 — The BC shall queue concurrent requests, never interleave them.
///   _(why: llama.cpp contexts are not thread-safe)_
/// - R1.3 — If the port cannot be bound, then the BC shall fail, naming the
///   port.
///
/// ### R2: Anthropic messages
///
/// - R2.1 — When a valid messages request arrives, the BC shall respond in
///   the Anthropic Messages shape, with tool calls re-serialised as
///   tool-use content blocks.
/// - R2.2 — The BC shall accept and ignore the request's model field — the
///   loaded GGUF wins.
/// - R2.3 — If the request is not a POST, then the BC shall reject it as
///   method-not-allowed.
/// - R2.4 — If the path extends beyond the messages resource, then the BC
///   shall reject it as not-found, pointing OpenAI clients at the right
///   base URL. _(why: an OpenAI client based at `/v1/messages` would
///   otherwise get Anthropic JSON with no "choices" and a misleading 200)_
/// - R2.5 — If the body is not a valid messages payload, then the BC shall
///   reject it as an invalid request in the protocol's error shape.
/// - R2.6 — If generation fails, then the BC shall report an API error in
///   the protocol's error shape.
///
/// ### R3: OpenAI chat completions
///
/// - R3.1 — When a valid chat request arrives, the BC shall respond in the
///   Chat Completions shape, with tool calls re-serialised as function
///   tool-calls.
/// - R3.2 — Where streaming is requested, the BC shall emit the response as
///   chat-completion chunks over server-sent events, appending usage when
///   the request asks for it.
/// - R3.3 — While a tool call opens mid-stream, the BC shall stop emitting
///   visible content. _(why: a call is only parseable once its closing
///   marker arrives; already-streamed bytes cannot be retracted)_
/// - R3.4 — If the request is invalid (wrong method, malformed body, empty
///   messages), then the BC shall reject it in the protocol's error shape.
///
/// ### R4: Model listing
///
/// - R4.1 — When models are listed, the BC shall return the loaded model as
///   the single entry, under both the standard and the LM Studio-compatible
///   paths.
/// - R4.2 — The BC shall report the effective served context window, not
///   the GGUF's advertised maximum.
/// - R4.3 — If the listing is requested with a method other than GET, then
///   the BC shall reject it as method-not-allowed.
///
/// ## Entities
///
/// - AnthropicMessagesRequest, OpenAIChatRequest
///
/// ## Out of scope
///
/// - Anthropic-side streaming — `stream` on `/v1/messages` is ignored; the
///   response is always a single JSON document.
/// - Authentication, TLS, rate limiting.
/// - Multi-model routing — the `model` field is ignored on every endpoint;
///   one process serves one loaded GGUF.
package lm.http;
