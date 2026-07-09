/// # Catalog
/// > Discover GGUF model files and resolve model names to paths, so callers
/// > refer to models by file name only.
///
/// ## Boundary
///
/// - `list-models` — enumerate the models directory, one sorted entry per
///   logical model
/// - `resolve-model` — turn a model file name into its path inside the
///   models directory
/// - `search-models` — find listed models by case-insensitive name fragment
///
/// ## Requirements
///
/// ### R1: List models
///
/// - R1.1 — When the models directory is listed, the BC shall return the
///   GGUF file names it contains, sorted.
/// - R1.2 — While a model is sharded (`…-NNNNN-of-NNNNN` name suffix), the
///   BC shall list only its first shard — one entry per logical model.
///   _(why: llama.cpp follows the shard pattern itself at load time;
///   duplicate entries would present one model as several)_
/// - R1.3 — If a listed name carries a malformed shard suffix, then the BC
///   shall treat it as a single-file model.
/// - R1.4 — If the models directory does not exist, then the BC shall return
///   an empty listing. _(why: a fresh install has no models directory yet;
///   listing must not crash the first run)_
/// - R1.5 — The BC shall exclude non-GGUF files from the listing.
/// - R1.6 — If the models directory cannot be read, then the BC shall fail,
///   naming the directory.
///
/// ### R2: Resolve a model name
///
/// - R2.1 — When a file name is resolved, the BC shall return its path
///   inside the models directory.
/// - R2.2 — The BC shall resolve names without requiring the file to exist.
///   _(why: callers decide whether a missing model is an error or a skip)_
/// - R2.3 — The BC shall locate the models directory via the
///   `models.directory` configuration, defaulting to `models`.
/// - R2.4 — While the configured models directory begins with `~`, the BC
///   shall expand the `~` to the user home.
///
/// ### R3: Search by fragment
///
/// - R3.1 — When a fragment is searched, the BC shall return every listed
///   model whose name contains it, case-insensitively. _(why:
///   `lmprompt gemma`-style invocations must hit `Gemma-4-…` file names)_
/// - R3.2 — If no listed model contains the fragment, then the BC shall
///   return an empty result.
///
/// ## Out of scope
///
/// - Enforcing the "exactly one match" rule on fragment search — the
///   caller's call (`lmprompt` and `lmserve` reject ambiguity themselves).
/// - Reading file contents or metadata (inspection BC).
/// - The property resolution order behind `models.directory`
///   (configuration BC).
/// - Downloading or installing models.
///
/// ## Notes
///
/// Multi-file GGUFs are named `…-00001-of-00003.gguf`,
/// `…-00002-of-00003.gguf`, …; llama.cpp loads the remaining shards by
/// following the suffix pattern at load time, so the catalog surfaces only
/// the first. The models directory is centralised in `models.directory`
/// (default `models`, resolved against the current working directory) so
/// users do not repeat absolute paths everywhere.
package lm.catalog;
