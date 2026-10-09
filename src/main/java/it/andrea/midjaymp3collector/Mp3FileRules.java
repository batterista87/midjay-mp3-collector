package it.andrea.midjaymp3collector;

import java.nio.file.Path;
import java.util.Locale;

final class Mp3FileRules {

    private Mp3FileRules() {}

    static boolean isExcludedDirectory(Path directory) {
        Path fileName = directory.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return name.equalsIgnoreCase("_OLD") || name.toUpperCase(Locale.ROOT).startsWith("Z_");
    }

    static boolean isMp3(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3");
    }
}
