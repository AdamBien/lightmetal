/// # Tokenization
/// > Bridge strings and llama.cpp token ids so no other component touches
/// > native memory.
///
/// ## Boundary
///
/// - `tokenize` — text → token ids, honoring BOS and special-token parsing
/// - `detokenize` — token ids → text
///
/// ## Requirements
///
/// ### R1: Convert between text and token ids
///
/// - R1.1 — When text is tokenized and detokenized against the same
///   vocabulary, the BC shall reproduce the text.
/// - R1.2 — When tokenizing, the BC shall honor the BOS flag.
/// - R1.3 — If the native size negotiation fails twice, then the BC shall
///   fail, naming the text length. _(why: llama.cpp returns the negative
///   required size, so the second attempt is guaranteed to fit — a growth
///   loop would mask a real bug)_
///
/// ## Out of scope
///
/// - Vocabulary ownership — the backend BC loads it; this BC only borrows
///   it per call.
/// - Prompt formatting — prompting BC.
package lm.tokenization;
