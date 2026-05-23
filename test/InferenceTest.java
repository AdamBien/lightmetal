import java.nio.file.Files;
import java.nio.file.Path;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.GenerationConfig;
import lm.configuration.entity.Token;
import lm.generation.boundary.LightMetal;

void main() {
    ZCfg.load("lightmetal");
    var modelPath = ZCfg.string("model");
    if (modelPath == null || !Files.exists(Path.of(modelPath))) {
        IO.println("[skip] no usable model configured (model=" + modelPath + ")");
        return;
    }

    var cfg = new GenerationConfig(4, 0.0f, 1.0f, 1, 0.0f, 42L);
    var collected = new StringBuilder();
    var count = new int[1];
    var firstId = new int[]{Integer.MIN_VALUE};

    try (var lm = LightMetal.load(Path.of(modelPath));
         var stream = lm.generate("Say hi.", cfg)) {
        stream.forEach(t -> {
            if (count[0] == 0) firstId[0] = t.id();
            count[0]++;
            collected.append(t.text());
        });
    }
    if (count[0] == 0)
        throw new AssertionError("expected at least one generated token, got 0");
    if (count[0] > cfg.maxTokens())
        throw new AssertionError("expected <= %d tokens, got %d".formatted(cfg.maxTokens(), count[0]));
    if (firstId[0] == Integer.MIN_VALUE)
        throw new AssertionError("expected a valid token id, got none");
    if (collected.toString().isEmpty())
        throw new AssertionError("expected non-empty detokenized output");

    IO.println("[ok] %d tokens, firstId=%d, text=%s"
            .formatted(count[0], firstId[0], collected.toString().replace("\n", "\\n")));
}
