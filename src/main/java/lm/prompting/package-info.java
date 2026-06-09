/// Prompting BC — chat-template rendering and the model → template binding.
///
/// ## Responsibility
///
/// Turns a system prompt, a tool list, and a conversation turn list into
/// the exact token stream the active model expects, and centralises the n:1
/// mapping of GGUF model names to templates so adding a new model is a
/// one-line change.
///
/// ## Family resolution
///
/// Matching is fragment-substring on the lowercased `general.name` from the
/// GGUF metadata, walked in declaration order. Most-specific fragments must
/// come first; unknown models fail loud rather than silently fall back to a
/// generic template.
///
/// ## Channels
///
/// Gemma emits a `thought` / `final` channel split inside its output; the
/// stream is tagged so callers can route reasoning tokens differently from
/// the final answer (the HTTP handlers drop `thought`; the CLI shows them
/// inline; scripts count them for tps).
///
/// ## Mistral tool-call format
///
/// Mistral encodes tool calls as `[TOOL_CALLS]<name>[ARGS]{json}` blocks
/// inline in the generated stream; parsing is delegated to the tools BC so
/// the wire format stays in one place.
package lm.prompting;
