/// # Lightmetal
/// > GPU LLM inference on Apple Silicon from a single zero-dependency Java
/// > executable JAR, over a Metal-enabled `libllama.dylib`.
///
/// ## Components
///
/// - `generation` is the composition root: it may call `backend`,
///   `prompting`, `catalog`, `inspection`, `configuration`, and `tools`.
/// - `http` may call `generation` and `tools`; `generation`'s `embed-chat`
///   reuses `http`'s Anthropic JSON mapping — the one deliberate cycle.
/// - `backend` may call `sampling` and `tokenization`; `sampling` and
///   `tokenization` reach native code only through `backend`'s vendored FFM
///   bindings.
/// - `prompting` may call `tools` for the tool-call wire format.
/// - `inspection` depends on no other BC; `catalog` reads only
///   `configuration`.
/// - `configuration` and `logging` are infrastructure: callable from every
///   BC, calling none.
///
/// ## System invariants
///
/// - S1 — While a model is loaded, the system shall serialize all generation
///   against it — concurrent callers queue, never interleave. _(why:
///   llama.cpp contexts are not thread-safe)_
/// - S2 — The system shall confine native memory access (`MemorySegment`,
///   `Arena`) to `backend`, `sampling`, and `tokenization`.
///
/// ## Ubiquitous language
///
/// - GGUF — the single-file model format llama.cpp loads; header metadata +
///   tensor weights. Read by `inspection`, loaded by `backend`.
/// - Shard — one file of a multi-file GGUF (`…-00001-of-00003.gguf`); only
///   the first is addressed, llama.cpp follows the rest.
/// - Model family — the n:1 binding of a GGUF's architecture, or failing that
///   its model name, to a chat template. Owned by `prompting`.
/// - Turn — one conversation step: user text, assistant text, assistant
///   tool calls, or tool results. Owned by `prompting`.
/// - Channel — the tag on a generated token: `thought` (reasoning) vs
///   `final` (visible answer).
/// - Tool call — a model-requested function invocation with JSON arguments.
///   Owned by `tools`.
/// - Models directory — the one configured folder (`models.directory`)
///   model file names resolve against. Owned by `catalog`.
///
/// ## Stack
///
/// - java-cli-app · base package `lm` · built with `zb`, verified by the
///   `zunit` post-build hook
package lm;
