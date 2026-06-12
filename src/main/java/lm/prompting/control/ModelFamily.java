package lm.prompting.control;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import lm.inspection.entity.GGUFMetadata;

// n:1 registry — many GGUF model files map to one ChatTemplate. Adding a new model
// that reuses an existing template is a one-line change. The fragment is matched
// against GGUF general.name after both sides are lowercased and spaces/underscores
// collapsed to hyphens, so "Google_Gemma 4 12B It" matches fragment "gemma-4".
// Order constants most-specific first.
public enum ModelFamily {

    GEMMA_4("gemma-4", GemmaChatTemplate::new),
    GEMMA_3("gemma-3", GemmaChatTemplate::new),
    MISTRAL_NEMO("nemo", Mistral4ChatTemplate::new),
    DEVSTRAL("devstral", Mistral4ChatTemplate::new),
    MISTRAL_4("mistral", Mistral4ChatTemplate::new);

    private final String nameFragment;
    private final Supplier<ChatTemplate> factory;

    ModelFamily(String nameFragment, Supplier<ChatTemplate> factory) {
        this.nameFragment = nameFragment;
        this.factory = factory;
    }

    public ChatTemplate template() {
        return factory.get();
    }

    public static Optional<ModelFamily> from(GGUFMetadata metadata) {
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
