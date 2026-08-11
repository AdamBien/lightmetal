package lm.prompting.control;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import lm.inspection.entity.GGUFMetadata;

// n:1 registry — many GGUF model files map to one ChatTemplate. Adding a new model
// that reuses an existing template is a one-line change.
//
// Two passes, architecture first. general.architecture is written by the GGUF converter
// from the actual model graph, so it is exact where general.name is free-form: one
// catalogue file reports its name as the HuggingFace cache directory
// "models--mistralai--Mistral-7B-Instruct-v0.3", and "mistral3" vs "mistral4" is a
// distinction no name fragment can make — both names contain "mistral".
//
// The name pass is the fallback for architectures too generic to route on ("llama" covers
// most of the catalogue). It matches the fragment against general.name after both sides are
// lowercased and spaces/underscores collapsed to hyphens, so "Google_Gemma 4 12B It"
// matches fragment "gemma-4". Order constants most-specific fragment first.
public enum ModelFamily {

    GEMMA_4("", "gemma-4", GemmaChatTemplate::new),
    GEMMA_3("", "gemma-3", GemmaChatTemplate::new),
    // Must precede MISTRAL_NEMO: "nemotron" contains the fragment "nemo", so without
    // this earlier, more-specific entry a Nemotron GGUF would silently match
    // MISTRAL_NEMO and be rendered with the Mistral template.
    NEMOTRON("", "nemotron", NemotronChatTemplate::new),
    MELLUM("", "mellum", MellumChatTemplate::new),
    MISTRAL_4("mistral4", "mistral-small-4", MistralTekkenChatTemplate::new),
    // The v0.1/v0.2/v0.3 Instruct line: arch=llama, and a vocabulary WITHOUT
    // [SYSTEM_PROMPT], [ARGS] or [MODEL_SETTINGS]. Must precede the broad "mistral"
    // fragment, which would otherwise render it in a format it has never seen.
    MISTRAL_INSTRUCT("", "instruct-v0.", MistralInstructChatTemplate::new),
    MISTRAL_NEMO("", "nemo", MistralTekkenChatTemplate::new),
    DEVSTRAL("", "devstral", MistralTekkenChatTemplate::new),
    MISTRAL_3("mistral3", "mistral", MistralTekkenChatTemplate::new);

    private final String architecture;
    private final String nameFragment;
    private final Supplier<ChatTemplate> factory;

    ModelFamily(String architecture, String nameFragment, Supplier<ChatTemplate> factory) {
        this.architecture = architecture;
        this.nameFragment = nameFragment;
        this.factory = factory;
    }

    public ChatTemplate template() {
        return factory.get();
    }

    public static Optional<ModelFamily> from(GGUFMetadata metadata) {
        return byArchitecture(metadata).or(() -> byName(metadata));
    }

    private static Optional<ModelFamily> byArchitecture(GGUFMetadata metadata) {
        return metadata.architecture()
                .map(ModelFamily::normalize)
                .flatMap(arch -> Arrays.stream(values())
                        .filter(m -> !m.architecture.isEmpty() && m.architecture.equals(arch))
                        .findFirst());
    }

    private static Optional<ModelFamily> byName(GGUFMetadata metadata) {
        return metadata.name()
                .map(ModelFamily::normalize)
                .flatMap(name -> Arrays.stream(values())
                        .filter(m -> name.contains(m.nameFragment))
                        .findFirst());
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
    }
}
