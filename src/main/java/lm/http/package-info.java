/// HTTP BC — Anthropic- and OpenAI-compatible HTTP front-ends for a loaded
/// LightMetal instance.
///
/// ## Responsibility
///
/// Lets existing Anthropic and OpenAI clients (zsmith, vibe, Continue,
/// Aider, Open WebUI, LangChain defaults, …) talk to a local GGUF with only
/// a base URL switch. The `model` field on incoming requests is accepted
/// and ignored: the loaded GGUF wins.
///
/// ## Endpoints
///
/// - `POST /v1/messages` — Anthropic Messages API
/// - `POST /v1/chat/completions` — OpenAI Chat Completions API
/// - `GET  /v1/models` — OpenAI model listing
///
/// ## Threading
///
/// The server is bound to a single-threaded executor and each handler
/// synchronises on the loaded LightMetal instance because llama.cpp
/// contexts are not thread-safe — concurrent requests are queued, not
/// interleaved.
///
/// ## Streaming
///
/// `stream: true` is rejected with HTTP 400 on both protocols. Adding SSE
/// means draining the token stream incrementally instead of accumulating
/// it.
///
/// ## Tool calls
///
/// Tool definitions on inbound requests are mapped into the Mistral tool
/// pipeline; parsed `[TOOL_CALLS]…[ARGS]` blocks are re-serialised into
/// each protocol's native shape (Anthropic `content[].tool_use` vs OpenAI
/// `tool_calls[].function`).
package lm.http;
