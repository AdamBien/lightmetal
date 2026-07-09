import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import lm.catalog.boundary.ModelCatalog;
import lm.configuration.control.ZCfg;
import lm.inspection.boundary.Inspector;
import lm.inspection.entity.GGUFMetadata;
import lm.prompting.control.ModelFamily;

static final int MAGIC = 0x46554747;

void main() throws IOException {
    r1ParseHeader();
    r2TypedAccess();
    modelFamilySmoke();
    realModel();
    IO.println("[ok] Inspector tests");
}

/// R1: Parse the header — one row per statement, all through the boundary.
void r1ParseHeader() throws IOException {
    record Case(String req, Path gguf, String expectedError) {}
    var cases = List.of(
            new Case("R1.1", craftedV3(), null),
            new Case("R1.2", write("bad-magic", header(b -> b.putInt(0xDEADBEEF))), "not a GGUF"),
            new Case("R1.3", write("v1", header(b -> { b.putInt(MAGIC); b.putInt(1); })), "version"),
            new Case("R1.4", write("bad-type", header(b -> {
                b.putInt(MAGIC);
                b.putInt(3);
                b.putLong(0L);              // tensor_count
                b.putLong(1L);              // kv_count
                putKey(b, "some.key");
                b.putInt(99);               // unknown value type tag
            })), "unknown GGUF value type"),
            new Case("R1.5", Path.of(System.getProperty("java.io.tmpdir"), "does-not-exist.gguf"),
                    "does-not-exist.gguf")
    );
    for (var c : cases) {
        try {
            var meta = Inspector.inspect(c.gguf());
            if (c.expectedError() != null)
                throw new AssertionError("%s — expected error containing '%s' but inspect succeeded"
                        .formatted(c.req(), c.expectedError()));
            if (meta.version() != 3)
                throw new AssertionError("%s — version: %d".formatted(c.req(), meta.version()));
            if (meta.tensorCount() != 0L)
                throw new AssertionError("%s — tensorCount: %d".formatted(c.req(), meta.tensorCount()));
            if (meta.kvs().size() != 5)
                throw new AssertionError("%s — kv count: %d".formatted(c.req(), meta.kvs().size()));
        } catch (RuntimeException e) {
            if (c.expectedError() == null || !e.getMessage().contains(c.expectedError()))
                throw new AssertionError("%s — unexpected error: %s".formatted(c.req(), e), e);
        }
    }
}

/// R2: Typed metadata access — one labeled check row per statement.
void r2TypedAccess() throws IOException {
    var meta = Inspector.inspect(craftedV3());
    var empty = GGUFMetadata.empty();
    record Check(String req, String what, boolean ok) {}
    var checks = List.of(
            new Check("R2.1", "architecture", "gemma".equals(meta.architecture().orElse(null))),
            new Check("R2.1", "name", "gemma-3-test".equals(meta.name().orElse(null))),
            new Check("R2.1", "chatTemplate", meta.chatTemplate().orElse("").contains("{{ msg }}")),
            new Check("R2.1", "bosTokenId", meta.bosTokenId().orElse(-1) == 2),
            new Check("R2.2", "absent key reports absence", empty.architecture().isEmpty()
                    && empty.addBosToken().isEmpty()
                    && empty.eosTokenId().isEmpty()
                    && empty.kvs().isEmpty()),
            new Check("R2.2", "wrong-typed key reports absence",
                    meta.bool("general.architecture").isEmpty()
                    && meta.integer("general.name").isEmpty()),
            new Check("R2.3", "context length under <arch>.context_length",
                    meta.contextLength().orElse(-1) == 8192L),
            new Check("R2.3", "no architecture, no context length",
                    empty.contextLength().isEmpty())
    );
    for (var c : checks)
        if (!c.ok())
            throw new AssertionError(c.req() + " — " + c.what());
}

/// Model → template binding belongs to the prompting BC (see its future
/// spec); kept here as smoke so the coverage is not lost in the meantime.
void modelFamilySmoke() throws IOException {
    var meta = Inspector.inspect(craftedV3());
    var family = ModelFamily.from(meta).orElseThrow();
    if (family != ModelFamily.GEMMA_3)
        throw new AssertionError("expected GEMMA_3 for name=gemma-3-test, got: " + family);
    if (ModelFamily.from(GGUFMetadata.empty()).isPresent())
        throw new AssertionError("empty() should not match any ModelFamily");
}

/// R1.1 against a real GGUF when one is configured; skips otherwise.
void realModel() {
    ZCfg.load("lightmetal");
    var fileName = ZCfg.string("model");
    if (fileName == null) {
        IO.println("[skip] no real model configured for Inspector integration");
        return;
    }
    var modelPath = ModelCatalog.resolve(fileName);
    if (!Files.exists(modelPath)) {
        IO.println("[skip] model not found at " + modelPath);
        return;
    }
    var meta = Inspector.inspect(modelPath);

    if (meta.kvs().isEmpty())
        throw new AssertionError("R1.1 — real model returned no kv pairs");
    var arch = meta.architecture()
            .orElseThrow(() -> new AssertionError("R1.1 — real model has no general.architecture"));
    var tmpl = meta.chatTemplate()
            .orElseThrow(() -> new AssertionError("R1.1 — real model has no tokenizer.chat_template"));
    var family = ModelFamily.from(meta).map(ModelFamily::name).orElse("<unmapped>");

    IO.println("[ok] real model: arch=%s, kv=%d, family=%s, template_chars=%d"
            .formatted(arch, meta.kvs().size(), family, tmpl.length()));
    meta.contextLength().ifPresent(c -> IO.println("       context_length=" + c));
    meta.bosTokenId().ifPresent(id -> IO.println("       bos_token_id=" + id));
    meta.eosTokenId().ifPresent(id -> IO.println("       eos_token_id=" + id));
    meta.addBosToken().ifPresent(b -> IO.println("       add_bos_token=" + b));
}

Path craftedV3() throws IOException {
    return write("crafted-v3", header(b -> {
        b.putInt(MAGIC);
        b.putInt(3);                        // version
        b.putLong(0L);                      // tensor_count
        b.putLong(5L);                      // kv_count
        putStringKV(b, "general.architecture", "gemma");
        putStringKV(b, "general.name", "gemma-3-test");
        putStringKV(b, "tokenizer.chat_template", "<|turn>user\n{{ msg }}<turn|>");
        putKey(b, "gemma.context_length");
        b.putInt(10);                       // type U64
        b.putLong(8192L);
        putKey(b, "tokenizer.ggml.bos_token_id");
        b.putInt(4);                        // type U32
        b.putInt(2);
    }));
}

ByteBuffer header(java.util.function.Consumer<ByteBuffer> content) {
    var buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
    content.accept(buf);
    return buf.flip();
}

Path write(String name, ByteBuffer buf) throws IOException {
    var file = Files.createTempFile(name, ".gguf");
    file.toFile().deleteOnExit();
    Files.write(file, Arrays.copyOf(buf.array(), buf.limit()));
    return file;
}

void putKey(ByteBuffer buf, String key) {
    var bytes = key.getBytes(StandardCharsets.UTF_8);
    buf.putLong(bytes.length);
    buf.put(bytes);
}

void putStringKV(ByteBuffer buf, String key, String value) {
    putKey(buf, key);
    buf.putInt(8);                          // type STRING
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    buf.putLong(bytes.length);
    buf.put(bytes);
}
