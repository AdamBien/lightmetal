/// Tokenization BC — bridges `String` / `int[]` and llama.cpp's token ids.
///
/// ## Responsibility
///
/// Wraps llama.cpp's tokenization primitives behind a JVM-friendly API so
/// the rest of the application works with `String` and `int[]` and never
/// allocates a `MemorySegment`.
///
/// ## Buffer-sizing protocol
///
/// llama.cpp returns the negative required size when the caller's buffer
/// is too small. Calls retry exactly once on `written < 0`, re-allocating
/// to the required size — no growth loop, because the second attempt is
/// guaranteed to fit.
package lm.tokenization;
