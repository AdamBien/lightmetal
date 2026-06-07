package lm.catalog.boundary;

import module java.base;

import lm.configuration.control.ZCfg;

public interface ModelCatalog {

    String DIRECTORY_KEY = "models.directory";
    String DEFAULT_DIRECTORY = "~/models";
    String EXTENSION = ".gguf";

    static List<String> list() {
        var dir = resolveDirectory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(ModelCatalog::isModel)
                    .sorted()
                    .toList();
        } catch (IOException problem) {
            throw new IllegalStateException("cannot list models in " + dir, problem);
        }
    }

    static List<String> search(String fragment) {
        var lowerFragment = fragment.toLowerCase();
        return list().stream()
                .filter(name -> name.toLowerCase().contains(lowerFragment))
                .toList();
    }

    static boolean isModel(String name) {
        return name.toLowerCase().endsWith(EXTENSION);
    }

    static Path resolveDirectory() {
        var configured = ZCfg.string(DIRECTORY_KEY, DEFAULT_DIRECTORY);
        var home = System.getProperty("user.home");
        if (configured.equals("~")) {
            return Path.of(home);
        }
        if (configured.startsWith("~/")) {
            return Path.of(home, configured.substring(2));
        }
        return Path.of(configured);
    }
}
