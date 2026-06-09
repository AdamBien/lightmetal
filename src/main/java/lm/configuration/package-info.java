/// Configuration BC — property/CLI resolution plus the immutable carrier
/// records that flow through the generation pipeline.
///
/// ## Responsibility
///
/// Centralises every tunable knob (sampling parameters, context sizing,
/// file paths, server port, debug flag) behind one lookup mechanism and one
/// set of immutable records, so the rest of the application never reads
/// properties or parses CLI flags directly.
///
/// ## Resolution order
///
/// Later wins:
///
/// 1. Hardcoded defaults in the entity records
/// 2. GGUF metadata (`template`, `tokenizer.ggml.add_bos_token`)
/// 3. `~/.lightmetal/app.properties` (global)
/// 4. `./app.properties` (project-local)
/// 5. `-Dproperty=value` JVM system properties
/// 6. CLI flags
///
/// ## Immutability
///
/// All carrier records are immutable; configuration is resolved once at
/// startup or at request-entry and then passed downward by value. No
/// component reaches back to re-read a property mid-request.
package lm.configuration;
