/// # Configuration
/// > Resolve every tunable knob from the property sources once, and carry it
/// > through the pipeline as immutable records.
///
/// ## Boundary
///
/// - `load-configuration` — read the property sources once at startup,
///   optionally republishing them as system properties
/// - `read-setting` — typed lookup (string, integer, boolean, string list)
///   with caller-supplied defaults
///
/// ## Requirements
///
/// ### R1: Load and resolve
///
/// - R1.1 — When configuration is loaded, the BC shall merge, later wins:
///   the global file (`~/.lightmetal/app.properties`), the project-local
///   `./app.properties`, and JVM system properties.
/// - R1.2 — If a setting is read before loading, then the BC shall fail,
///   pointing at the load step.
/// - R1.3 — Where publication is requested, the BC shall republish every
///   resolved setting as a system property. _(why: zero-coupling embedders
///   read config via `System.getProperty` without `lm.*` imports)_
/// - R1.4 — If a property file exists but cannot be read, then the BC shall
///   fail, naming the file.
///
/// ### R2: Typed reads
///
/// - R2.1 — When a setting is read with a default, the BC shall return the
///   resolved value, or the default when the key is absent.
/// - R2.2 — When a comma-separated setting is read as a list, the BC shall
///   trim each element and return an empty list when the key is absent.
///
/// ### R3: Carrier records
///
/// - R3.1 — The BC shall carry generation, context, and token data as
///   immutable records — resolved once, passed downward by value, never
///   re-read mid-request.
/// - R3.2 — When generation settings are read from properties (`max-tokens`,
///   `temperature`, `top-p`, `top-k`, `min-p`, `seed`), the BC shall fall
///   back per knob to the built-in defaults.
/// - R3.3 — If no stop sequences are supplied, then the generation carrier
///   shall report an empty list, never null.
///
/// ## Entities
///
/// - GenerationConfig, ContextParams, Token
///
/// ## Out of scope
///
/// - CLI flag parsing — the application shell maps flags onto system
///   properties before load.
/// - GGUF-metadata-derived defaults — generation BC; the full precedence
///   story (defaults < metadata < files < system properties < flags) spans
///   BCs and lives at the system level.
package lm.configuration;
