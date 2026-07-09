/// # Inspection
/// > Read the metadata header of a GGUF model file — without loading tensor
/// > weights and without the native library.
///
/// ## Boundary
///
/// - `inspect-model` — read a GGUF file's metadata header and return it as
///   typed, immutable metadata
///
/// ## Requirements
///
/// ### R1: Parse the header
///
/// - R1.1 — When a GGUF v2 or v3 file is inspected, the BC shall return its
///   version, tensor count, and every typed key-value pair of the header.
///   _(why: startup defaults must come from the model file itself, before any
///   weight load and without per-model entries in `app.properties`)_
/// - R1.2 — If the file does not begin with the GGUF magic, then the BC shall
///   reject it, naming the magic found.
/// - R1.3 — If the file declares a GGUF version other than 2 or 3, then the
///   BC shall reject it, naming the version.
/// - R1.4 — If the header declares an unknown value type or an invalid
///   string or array length, then the BC shall reject the file rather than
///   misread it.
/// - R1.5 — If the file cannot be read, then the BC shall fail, naming the
///   file.
///
/// ### R2: Typed metadata access
///
/// - R2.1 — When a recognised key is present, the BC shall expose it through
///   a typed accessor: architecture, model name, chat template, context
///   length, BOS / EOS token id, add-BOS flag.
/// - R2.2 — If a queried key is absent or carries a different type, then the
///   BC shall report absence instead of failing. _(why: GGUF exporters
///   disagree on which keys they write; absence must be a value, not an
///   error)_
/// - R2.3 — While the metadata declares an architecture, the BC shall resolve
///   the context length under that architecture's `context_length` key.
///
/// ## Entities
///
/// - GGUFMetadata
///
/// ## Out of scope
///
/// - Tensor data — only the header is read.
/// - The model → chat-template binding: `ModelFamily.from(metadata)` in the
///   prompting BC is the single resolution path.
/// - Deriving generation defaults from the metadata (configuration and
///   generation BCs).
///
/// ## Notes
///
/// GGUF v2/v3 per the official
/// [spec](https://github.com/ggerganov/ggml/blob/master/docs/gguf.md); value
/// types u8 / i8 / u16 / i16 / u32 / i32 / f32 / bool / string / u64 / i64 /
/// f64 / array; little-endian; the file head is mmapped up to 256 MB.
/// Anything beyond the recognised keys: `meta.get("any.key")` or
/// `meta.kvs()`.
///
/// Pure `java.base` + `java.nio`, no dependency on other lightmetal BCs, on
/// llama.cpp version drift, or on the jextract-generated FFM bindings —
/// metadata is readable before (or without) loading the model.
///
/// ```java
/// var meta = Inspector.inspect(Path.of("/path/to/model.gguf"));
/// var family = ModelFamily.from(meta).orElseThrow();
/// var ctxLen = meta.contextLength().orElse(32_768L);
/// ```
package lm.inspection;
