/// Catalog BC — model file discovery and name → `Path` resolution.
///
/// ## Responsibility
///
/// Lets the CLI, the scripts, and the embed-via-SPI path refer to models by
/// their GGUF file name only. The containing directory is centralised in
/// `models.directory` (default `~/models`) so users do not have to repeat
/// absolute paths everywhere.
///
/// ## Sharded GGUFs
///
/// Multi-file GGUFs are named `…-00001-of-00003.gguf`,
/// `…-00002-of-00003.gguf`, …. Only the first shard surfaces in listings so
/// the catalog presents one entry per logical model; llama.cpp loads the
/// rest by following the suffix pattern at load time. Names without a
/// `-NNN-of-NNN` suffix are returned as-is.
///
/// ## Fragment matching
///
/// Lookup-by-fragment (`lmprompt gemma`-style invocations) is a
/// case-insensitive substring match against the file name — deliberately
/// permissive, with the caller resolving the "exactly one match" rule.
package lm.catalog;
