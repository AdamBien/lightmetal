package lm.inspection.boundary;

import java.nio.file.Path;

import lm.inspection.control.GGUFReader;
import lm.inspection.entity.GGUFMetadata;

public final class Inspector {

    private Inspector() {}

    public static GGUFMetadata inspect(Path gguf) {
        return GGUFReader.read(gguf);
    }
}
