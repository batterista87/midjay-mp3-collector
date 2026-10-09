package it.andrea.midjaymp3collector;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class Mp3ScanService {

    private static final byte[] LYRICS_BEGIN = "LYRICSBEGIN".getBytes(StandardCharsets.US_ASCII);
    private static final int[] LYRICS_BEGIN_PREFIX = buildPrefixTable();

    String scanAllMp3(Path sourceRoot) throws IOException {
        StringBuilder list = new StringBuilder();
        Set<String> printedDirectories = new HashSet<>();

        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                return isExcludedDirectory(directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!isMp3(file)) {
                    return FileVisitResult.CONTINUE;
                }

                appendFile(list, printedDirectories, sourceRoot.relativize(file), false);
                return FileVisitResult.CONTINUE;
            }
        });

        return list.toString();
    }

    String scanMp3WithLyrics(Path sourceRoot) throws IOException {
        StringBuilder list = new StringBuilder();
        Set<String> printedDirectories = new HashSet<>();

        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                return isExcludedDirectory(directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!isMp3(file) || !containsLyricsBegin(file)) {
                    return FileVisitResult.CONTINUE;
                }

                appendFile(list, printedDirectories, sourceRoot.relativize(file), true);
                return FileVisitResult.CONTINUE;
            }
        });

        return list.toString();
    }

    static boolean containsLyricsBegin(Path file) throws IOException {
        try (FileInputStream input = new FileInputStream(file.toFile())) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            int matched = 0;
            while ((read = input.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    while (matched > 0 && buffer[i] != LYRICS_BEGIN[matched]) {
                        matched = LYRICS_BEGIN_PREFIX[matched - 1];
                    }
                    if (buffer[i] == LYRICS_BEGIN[matched]) {
                        matched++;
                        if (matched == LYRICS_BEGIN.length) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static int[] buildPrefixTable() {
        int[] prefix = new int[LYRICS_BEGIN.length];
        int matched = 0;
        for (int i = 1; i < LYRICS_BEGIN.length; i++) {
            while (matched > 0 && LYRICS_BEGIN[i] != LYRICS_BEGIN[matched]) {
                matched = prefix[matched - 1];
            }
            if (LYRICS_BEGIN[i] == LYRICS_BEGIN[matched]) {
                matched++;
                prefix[i] = matched;
            }
        }
        return prefix;
    }

    private void appendFile(StringBuilder list, Set<String> printedDirectories, Path relativePath,
            boolean uppercaseDirectory) {
        Path parent = relativePath.getParent();
        if (parent != null) {
            String directoryName = parent.toString();
            if (printedDirectories.add(directoryName)) {
                list.append('\n')
                        .append(uppercaseDirectory ? directoryName.toUpperCase(Locale.ROOT) : directoryName)
                        .append('\n');
            }
        }

        String name = relativePath.getFileName().toString();
        list.append("  ").append(name.substring(0, name.length() - ".mp3".length())).append('\n');
    }

    private boolean isExcludedDirectory(Path directory) {
        Path fileName = directory.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return name.equalsIgnoreCase("_OLD") || name.toUpperCase(Locale.ROOT).startsWith("Z_");
    }

    private boolean isMp3(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3");
    }
}
