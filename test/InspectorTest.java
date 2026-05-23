import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import lm.configuration.control.ZCfg;
import lm.inspection.boundary.Inspector;
import lm.inspection.control.GGUFReader;

void main() {
    testParsesCraftedV3Header();
    testRejectsBadMagic();
    testRejectsUnsupportedVersion();
    testRealModel();
    IO.println("[ok] Inspector tests");
}

void testParsesCraftedV3Header() {
    var buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(0x46554747);            // "GGUF"
    buf.putInt(3);                      // version
    buf.putLong(0L);                    // tensor_count
    buf.putLong(5L);                    // kv_count

    putStringKV(buf, "general.architecture", "gemma");
    putStringKV(buf, "general.name", "test-model");
    putStringKV(buf, "tokenizer.chat_template", "<|turn>user\n{{ msg }}<turn|>");
    putKey(buf, "gemma.context_length");
    buf.putInt(10);                     // type U64
    buf.putLong(8192L);
    putKey(buf, "tokenizer.ggml.bos_token_id");
    buf.putInt(4);                      // type U32
    buf.putInt(2);

    buf.flip();
    var meta = GGUFReader.parse(buf);

    if (meta.version() != 3)
        throw new AssertionError("version: " + meta.version());
    if (meta.tensorCount() != 0L)
        throw new AssertionError("tensorCount: " + meta.tensorCount());
    if (meta.kvs().size() != 5)
        throw new AssertionError("kv count: " + meta.kvs().size());
    if (!"gemma".equals(meta.architecture().orElseThrow()))
        throw new AssertionError("arch: " + meta.architecture());
    if (!"test-model".equals(meta.name().orElseThrow()))
        throw new AssertionError("name: " + meta.name());
    if (meta.contextLength().orElse(-1) != 8192L)
        throw new AssertionError("ctx: " + meta.contextLength());
    if (meta.bosTokenId().orElse(-1) != 2)
        throw new AssertionError("bos: " + meta.bosTokenId());

    var detected = meta.detectTemplate().orElseThrow();
    if (!"gemma4".equals(detected))
        throw new AssertionError("template fingerprint: " + detected);
}

void testRejectsBadMagic() {
    var buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(0xDEADBEEF);
    buf.flip();
    try {
        GGUFReader.parse(buf);
        throw new AssertionError("expected IllegalArgumentException for bad magic");
    } catch (IllegalArgumentException expected) {
        if (!expected.getMessage().contains("not a GGUF"))
            throw new AssertionError("wrong message: " + expected.getMessage());
    }
}

void testRejectsUnsupportedVersion() {
    var buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(0x46554747);
    buf.putInt(1);
    buf.flip();
    try {
        GGUFReader.parse(buf);
        throw new AssertionError("expected IllegalArgumentException for v1");
    } catch (IllegalArgumentException expected) {
        if (!expected.getMessage().contains("version"))
            throw new AssertionError("wrong message: " + expected.getMessage());
    }
}

void testRealModel() {
    ZCfg.load("lightmetal");
    var modelPath = ZCfg.string("model");
    if (modelPath == null || !Files.exists(Path.of(modelPath))) {
        IO.println("[skip] no real model configured for Inspector integration");
        return;
    }
    var meta = Inspector.inspect(Path.of(modelPath));

    if (meta.kvs().isEmpty())
        throw new AssertionError("real model returned no kv pairs");
    var arch = meta.architecture()
            .orElseThrow(() -> new AssertionError("real model has no general.architecture"));
    var tmpl = meta.chatTemplate()
            .orElseThrow(() -> new AssertionError("real model has no tokenizer.chat_template"));
    var detected = meta.detectTemplate().orElseThrow();

    IO.println("[ok] real model: arch=%s, kv=%d, template=%s, template_chars=%d"
            .formatted(arch, meta.kvs().size(), detected, tmpl.length()));
    meta.contextLength().ifPresent(c -> IO.println("       context_length=" + c));
    meta.bosTokenId().ifPresent(id -> IO.println("       bos_token_id=" + id));
    meta.eosTokenId().ifPresent(id -> IO.println("       eos_token_id=" + id));
    meta.addBosToken().ifPresent(b -> IO.println("       add_bos_token=" + b));
}

void putKey(ByteBuffer buf, String key) {
    var bytes = key.getBytes(StandardCharsets.UTF_8);
    buf.putLong(bytes.length);
    buf.put(bytes);
}

void putStringKV(ByteBuffer buf, String key, String value) {
    putKey(buf, key);
    buf.putInt(8);                       // type STRING
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    buf.putLong(bytes.length);
    buf.put(bytes);
}
