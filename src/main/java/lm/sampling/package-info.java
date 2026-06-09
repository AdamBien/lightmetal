/// Sampling BC — assembles llama.cpp's sampler chain from a generation
/// configuration.
///
/// ## Responsibility
///
/// Translates the user-facing sampling knobs (`top-k`, `top-p`, `min-p`,
/// `temperature`, `seed`) into a chain of `llama_sampler_*` instances in
/// the order llama.cpp expects, and owns the lifetime of the chain.
///
/// ## Chain order
///
/// `top-k` → `top-p` → `min-p` → `temperature` → distribution. Reordering
/// changes outputs; the chain is intentionally not user-configurable.
package lm.sampling;
