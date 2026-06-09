/// Generation BC — the user-facing facade that composes every other BC into
/// a single load-prompt-stream API.
///
/// ## Responsibility
///
/// Hides the backend / prompting / sampling / tokenization machinery behind
/// one type so that callers — the CLI, the HTTP handlers, the embedding
/// SPI, the PATH scripts — work against a minimal surface: load, generate,
/// metadata, close. Picks the chat template from GGUF metadata, drives the
/// stream, tags channels (`thought` vs answer).
///
/// ## Embedding contract
///
/// The embedding entry point is registered as a
/// `java.util.function.BinaryOperator<String>` via `META-INF/services` so
/// hosts can `ServiceLoader.load(...)` it without compiling against any
/// lightmetal type. The JDK-owned descriptor name means hosts running
/// multiple unrelated `BinaryOperator` providers on the same classpath
/// should filter by `instanceof` rather than rely on iteration order.
///
/// ## Per-call lifecycle
///
/// The SPI path loads and closes the GGUF on every invocation — suited to
/// sporadic, one-shot use. Long-lived hosts run the HTTP front-end against
/// a single loaded instance instead.
package lm.generation;
