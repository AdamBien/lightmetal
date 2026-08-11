import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import lm.configuration.control.ZCfg;
import lm.inspection.entity.GGUFMetadata;
import lm.prompting.control.GemmaChatTemplate;
import lm.prompting.control.MellumChatTemplate;
import lm.prompting.control.MistralInstructChatTemplate;
import lm.prompting.control.MistralTekkenChatTemplate;
import lm.prompting.control.ModelFamily;
import lm.prompting.control.NemotronChatTemplate;

// Every arch/name pair below is copied verbatim from a real GGUF in the catalogue, so a
// change to the registry that would misroute a shipped model fails here.
record Case(String req, String arch, String name, ModelFamily expected) {}

void main() {
    ZCfg.load("lightmetal-tests-no-such-file");

    testResolution();
    testTemplateWiring();

    IO.println("[ok] model family resolution");
}

void testResolution() {
    var cases = List.of(
            // R1.4 — architecture wins over the name fragment. Both files below contain
            // "mistral", so only general.architecture can separate them.
            new Case("R1.4", "mistral4", "Mistral-Small-4-119B-2603", ModelFamily.MISTRAL_4),
            new Case("R1.4", "mistral4", "Mistral Small 4 119B 2603", ModelFamily.MISTRAL_4),
            new Case("R1.4", "mistral3", "Mistral-Medium-3.5-128B", ModelFamily.MISTRAL_3),
            // architecture alone resolves a GGUF whose name says nothing useful
            new Case("R1.4", "mistral4", "Models", ModelFamily.MISTRAL_4),

            // R1.5 — a generic architecture falls through to the name fragment.
            // The name here is the HuggingFace cache directory, which is why matching on
            // general.name alone is not enough and why the fragment must be specific.
            new Case("R1.5", "llama", "models--mistralai--Mistral-7B-Instruct-v0.3",
                    ModelFamily.MISTRAL_INSTRUCT),
            new Case("R1.5", "llama", "Mistral-7B-Instruct-v0.2", ModelFamily.MISTRAL_INSTRUCT),
            // Mistral Small 3.x is NOT the legacy line — it ships the tekken vocabulary
            new Case("R1.5", "llama", "Mistral-Small-3.2-24B-Instruct-2506", ModelFamily.MISTRAL_3),
            new Case("R1.5", "llama", "Devstral-Small-2507", ModelFamily.DEVSTRAL),
            new Case("R1.5", "llama", "Mistral-Nemo-Instruct-2407", ModelFamily.MISTRAL_NEMO),

            // R1.1 — case and separator folding
            new Case("R1.1", "gemma3", "Google_Gemma 4 12B It", ModelFamily.GEMMA_4),
            new Case("R1.1", "mellum", "Mellum2 12B A2.5B Thinking", ModelFamily.MELLUM),
            // R1.2 — "nemotron" contains "nemo"; ordering must keep them apart
            new Case("R1.2", "nemotron_h", "Nemotron-3-Nano-30B-A3B", ModelFamily.NEMOTRON),

            // R1.3 — unknown architecture AND unknown name resolves to nothing
            new Case("R1.3", "qwen3", "Qwen3-Coder-30B", null),
            new Case("R1.3", "", "", null));

    for (var c : cases) {
        var kvs = new HashMap<String, Object>();
        if (!c.arch().isEmpty()) kvs.put("general.architecture", c.arch());
        if (!c.name().isEmpty()) kvs.put("general.name", c.name());
        var actual = ModelFamily.from(new GGUFMetadata(3, 0L, kvs));
        var expected = Optional.ofNullable(c.expected());
        if (!expected.equals(actual))
            throw new AssertionError("%s — arch=%s name=%s: expected %s, got %s"
                    .formatted(c.req(), c.arch(), c.name(), expected, actual));
    }
}

void testTemplateWiring() {
    if (!(ModelFamily.MISTRAL_4.template() instanceof MistralTekkenChatTemplate))
        throw new AssertionError("MISTRAL_4 must render the tekken format, got "
                + ModelFamily.MISTRAL_4.template().getClass());
    if (!(ModelFamily.MISTRAL_3.template() instanceof MistralTekkenChatTemplate))
        throw new AssertionError("MISTRAL_3 must render the tekken format, got "
                + ModelFamily.MISTRAL_3.template().getClass());
    if (!(ModelFamily.MISTRAL_INSTRUCT.template() instanceof MistralInstructChatTemplate))
        throw new AssertionError("MISTRAL_INSTRUCT must render the v3 instruct format, got "
                + ModelFamily.MISTRAL_INSTRUCT.template().getClass());
    if (!(ModelFamily.GEMMA_4.template() instanceof GemmaChatTemplate))
        throw new AssertionError("GEMMA_4 wiring changed");
    if (!(ModelFamily.NEMOTRON.template() instanceof NemotronChatTemplate))
        throw new AssertionError("NEMOTRON wiring changed");
    if (!(ModelFamily.MELLUM.template() instanceof MellumChatTemplate))
        throw new AssertionError("MELLUM wiring changed");
}
