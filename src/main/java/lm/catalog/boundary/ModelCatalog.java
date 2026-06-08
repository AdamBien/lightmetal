package lm.catalog.boundary;

import module java.base;

import lm.catalog.control.ModelsDirectory;

public interface ModelCatalog {

    String EXTENSION = ".gguf";
    String SHARD_INFIX = "-of-";

    static List<String> list() {
        var dir = ModelsDirectory.path();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(ModelCatalog::isModel)
                    .filter(ModelCatalog::isPrimary)
                    .sorted()
                    .toList();
        } catch (IOException problem) {
            throw new IllegalStateException("cannot list models in " + dir, problem);
        }
    }

    static boolean isPrimary(String name) {
        var stem = name.substring(0, name.length() - EXTENSION.length());
        var ofIdx = stem.lastIndexOf(SHARD_INFIX);
        if (ofIdx < 0) {
            return true;
        }
        var total = stem.substring(ofIdx + SHARD_INFIX.length());
        var dashIdx = stem.lastIndexOf('-', ofIdx - 1);
        if (dashIdx < 0) {
            return true;
        }
        var shard = stem.substring(dashIdx + 1, ofIdx);
        if (!allDigits(shard) || !allDigits(total)) {
            return true;
        }
        return Integer.parseInt(shard) == 1;
    }

    static boolean allDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    static Path resolve(String fileName) {
        return ModelsDirectory.path().resolve(fileName);
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
}
