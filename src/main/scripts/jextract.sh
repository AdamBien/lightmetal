#!/usr/bin/env bash
# Regenerate FFM bindings for libllama via jextract (https://jdk.java.net/jextract/).
# Only includes the symbols lightmetal actually uses — keeps the binding small
# and avoids broken references to ggml internals jextract can't model.
set -euo pipefail

JEXTRACT="${JEXTRACT:-jextract}"
LLAMA_PREFIX="$(brew --prefix llama.cpp)"
GGML_INCLUDE="$(brew --prefix ggml)/include"
INCLUDE="$LLAMA_PREFIX/include"
HEADER="$INCLUDE/llama.h"
OUT="src/main/java"
PKG="lm.backend.ffm.llama_h"
PKG_PATH="${PKG//.//}"

cd "$(dirname "$0")/../../.."

rm -rf "$OUT/$PKG_PATH"
mkdir -p "$OUT/$PKG_PATH"

FUNCTIONS=(
  llama_backend_init llama_backend_free
  llama_model_default_params llama_model_load_from_file llama_model_free
  llama_model_get_vocab llama_model_n_ctx_train llama_model_n_embd
  llama_context_default_params llama_new_context_with_model llama_free
  llama_n_ctx llama_get_model llama_get_memory
  llama_set_n_threads
  llama_vocab_n_tokens llama_vocab_get_text
  llama_vocab_bos llama_vocab_eos llama_vocab_is_eog
  llama_vocab_get_add_bos llama_vocab_get_add_eos
  llama_tokenize llama_detokenize llama_token_to_piece
  llama_batch_init llama_batch_free llama_batch_get_one
  llama_decode
  llama_get_logits llama_get_logits_ith
  llama_sampler_chain_default_params llama_sampler_chain_init
  llama_sampler_chain_add llama_sampler_free
  llama_sampler_init_temp llama_sampler_init_top_k llama_sampler_init_top_p
  llama_sampler_init_min_p llama_sampler_init_dist llama_sampler_init_greedy
  llama_sampler_init_grammar
  llama_sampler_sample llama_sampler_accept llama_sampler_reset
  llama_memory_clear llama_memory_seq_rm
  llama_chat_apply_template
)

STRUCTS=(
  llama_model_params llama_context_params llama_batch
  llama_sampler_chain_params llama_token_data llama_token_data_array
  llama_chat_message
)

TYPEDEFS=( llama_token llama_pos llama_seq_id llama_memory_t )

INCLUDE_ARGS=()
for f in "${FUNCTIONS[@]}"; do INCLUDE_ARGS+=(--include-function "$f"); done
for s in "${STRUCTS[@]}"; do INCLUDE_ARGS+=(--include-struct "$s"); done
for t in "${TYPEDEFS[@]}"; do INCLUDE_ARGS+=(--include-typedef "$t"); done

"$JEXTRACT" \
  --output "$OUT" \
  --target-package "$PKG" \
  --header-class-name llama_h \
  --library ":$LLAMA_PREFIX/lib/libllama.dylib" \
  -I "$INCLUDE" \
  -I "$GGML_INCLUDE" \
  "${INCLUDE_ARGS[@]}" \
  "$HEADER"

echo "wrote bindings to $OUT/$PKG_PATH"
