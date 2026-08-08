/// # Sampling
/// > Assemble llama.cpp's sampler chain from the generation settings and own
/// > its lifetime.
///
/// ## Boundary
///
/// - `assemble-sampler` — generation settings → a ready sampler chain
/// - `sample-token` — draw the next token id from a decoding context
/// - `release-sampler` — free the chain
///
/// ## Requirements
///
/// ### R1: Chain assembly and lifetime
///
/// - R1.1 — When a sampler is assembled, the BC shall chain the knobs in the
///   fixed order top-k → top-p → min-p → temperature → seeded distribution.
///   _(why: reordering changes outputs; the order is deliberately not
///   user-configurable)_
/// - R1.2 — While the same seed and settings drive the same context, the BC
///   shall sample reproducibly.
/// - R1.3 — When released, the BC shall free the native chain.
///
/// ## Out of scope
///
/// - The meaning and defaults of the sampling knobs — configuration BC.
/// - When to sample and how to feed tokens back — backend BC.
package lm.sampling;
