/// Inspection BC — pure-Java GGUF metadata reader.
///
/// ## Purpose
///
/// Extracts the metadata header of a GGUF file (architecture, chat template,
/// BOS/EOS token ids, context length, tokenizer settings, …) without loading
/// the tensor weights and without going through `libllama.dylib` via the FFM
/// bindings in `lm.backend.ffm.llama_h`. Lets the rest of the application
/// derive sane defaults from the model file itself instead of requiring
/// per-model entries in `~/.lightmetal/app.properties`.
///
/// ## BCE layout
///
/// - **boundary** — [lm.inspection.boundary.Inspector]: single public facade.
///   `Inspector.inspect(Path)` returns a [lm.inspection.entity.GGUFMetadata].
/// - **control** — [lm.inspection.control.GGUFReader]: binary parser. Mmaps
///   the file head (up to 256 MB) and walks the typed key-value section.
///   Little-endian. Supports GGUF v2 and v3; rejects v1 and unknown versions
///   with a clear error.
/// - **entity** — [lm.inspection.entity.GGUFMetadata]: immutable record
///   wrapping the raw `Map<String,Object>` plus typed accessors (`string`,
///   `integer`, `longValue`, `bool`) and convenience getters for the common
///   keys.
///
/// ## Dependencies
///
/// None on other lightmetal BCs. Pure `java.base` + `java.nio`. Independent
/// of llama.cpp version drift and of the jextract-generated FFM bindings.
///
/// ## Supported GGUF format
///
/// GGUF v2 and v3 per the official
/// [spec](https://github.com/ggerganov/ggml/blob/master/docs/gguf.md). Value
/// types: u8 / i8 / u16 / i16 / u32 / i32 / f32 / bool / string / u64 / i64 /
/// f64 / array.
///
/// ## Recognised metadata keys
///
/// [lm.inspection.entity.GGUFMetadata] exposes convenience accessors for:
///
/// | Key                              | Accessor                                                  |
/// |----------------------------------|-----------------------------------------------------------|
/// | `general.architecture`           | [lm.inspection.entity.GGUFMetadata#architecture()]        |
/// | `general.name`                   | [lm.inspection.entity.GGUFMetadata#name()]                |
/// | `tokenizer.chat_template`        | [lm.inspection.entity.GGUFMetadata#chatTemplate()]        |
/// | `<arch>.context_length`          | [lm.inspection.entity.GGUFMetadata#contextLength()]       |
/// | `tokenizer.ggml.bos_token_id`    | [lm.inspection.entity.GGUFMetadata#bosTokenId()]          |
/// | `tokenizer.ggml.eos_token_id`    | [lm.inspection.entity.GGUFMetadata#eosTokenId()]          |
/// | `tokenizer.ggml.add_bos_token`   | [lm.inspection.entity.GGUFMetadata#addBosToken()]         |
///
/// Anything else: `meta.get("any.key")` or `meta.kvs()`.
///
/// ## Template fingerprinting
///
/// [lm.inspection.entity.GGUFMetadata#detectTemplate()] inspects the Jinja
/// string and returns a lightmetal template name (`"gemma4"` when the
/// template contains `<|turn>`, `"mistral4"` when it contains `[INST]`,
/// falling back to `"mistral4"`). Callers can use it as the default for
/// `ZCfg.string("template", …)` so the right
/// [lm.prompting.control.ChatTemplate] wins automatically.
///
/// ## Usage
///
/// ```java
/// var meta = Inspector.inspect(Path.of("/path/to/model.gguf"));
/// var template = meta.detectTemplate().orElse("mistral4");
/// var ctxLen   = meta.contextLength().orElse(32_768L);
/// ```
///
/// ## Why pure Java?
///
/// - Fits the project's zero-dependency, single-JAR ethos.
/// - Reads metadata *before* loading the model — useful for startup-time
///   defaults and for inspecting GGUFs without the native library present.
/// - Unlocks arbitrary GGUF keys without a `jextract` regeneration (the
///   `llama_model_meta_val_str` family is not currently bound in
///   `lm.backend.ffm.llama_h`).
/// - Independent of llama.cpp version drift; the GGUF format spec is small
///   and stable.
///
/// @see <a href="https://github.com/ggerganov/ggml/blob/master/docs/gguf.md">GGUF format specification</a>
/// @see lm.inspection.boundary.Inspector
/// @see lm.inspection.entity.GGUFMetadata
/// @see lm.inspection.control.GGUFReader
package lm.inspection;
