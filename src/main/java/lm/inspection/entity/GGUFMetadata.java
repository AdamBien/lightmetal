package lm.inspection.entity;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

public record GGUFMetadata(int version, long tensorCount, Map<String, Object> kvs) {

    public static GGUFMetadata empty() {
        return new GGUFMetadata(0, 0L, Map.of());
    }

    public Optional<Object> get(String key) {
        return Optional.ofNullable(kvs.get(key));
    }

    public Optional<String> string(String key) {
        return kvs.get(key) instanceof String s ? Optional.of(s) : Optional.empty();
    }

    public OptionalLong longValue(String key) {
        return kvs.get(key) instanceof Number n ? OptionalLong.of(n.longValue()) : OptionalLong.empty();
    }

    public OptionalInt integer(String key) {
        return kvs.get(key) instanceof Number n ? OptionalInt.of(n.intValue()) : OptionalInt.empty();
    }

    public Optional<Boolean> bool(String key) {
        return kvs.get(key) instanceof Boolean b ? Optional.of(b) : Optional.empty();
    }

    public Optional<String> architecture() {
        return string("general.architecture");
    }

    public Optional<String> name() {
        return string("general.name");
    }

    public Optional<String> chatTemplate() {
        return string("tokenizer.chat_template");
    }

    public OptionalLong contextLength() {
        return architecture().map(a -> a + ".context_length")
                .map(this::longValue)
                .orElse(OptionalLong.empty());
    }

    public OptionalInt bosTokenId() {
        return integer("tokenizer.ggml.bos_token_id");
    }

    public OptionalInt eosTokenId() {
        return integer("tokenizer.ggml.eos_token_id");
    }

    public Optional<Boolean> addBosToken() {
        return bool("tokenizer.ggml.add_bos_token");
    }
}
