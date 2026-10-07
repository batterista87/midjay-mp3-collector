package it.andrea.midjaymp3collector;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

final class Mp3CopyService {

    Result copy(Path sourceRoot, Path destinationRoot, Options options,
            BooleanSupplier cancelled, ProgressListener listener) throws IOException {
        validateRoots(sourceRoot, destinationRoot);

        listener.onStatus("Conteggio file .mp3…");
        int totalMp3 = countEligibleMp3(sourceRoot, options, cancelled);
        if (cancelled.getAsBoolean()) {
            return new Result(0, 0, 0, "", "");
        }

        listener.onCopyProgress(0, totalMp3);

        AtomicInteger copiedCount = new AtomicInteger();
        AtomicInteger skippedDirectories = new AtomicInteger();
        StringBuilder summary = new StringBuilder();
        StringBuilder skippedSummary = new StringBuilder();
        Set<Path> createdDirectories = new HashSet<>();
        Path[] lastDirectory = { null };

        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }

                if (isExcludedDirectory(directory)) {
                    skippedDirectories.incrementAndGet();
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                if (!isMp3(file)) {
                    return FileVisitResult.CONTINUE;
                }

                boolean hasLyrics = Mp3ScanService.containsLyricsBegin(file);
                if (isFiltered(options, hasLyrics)) {
                    skippedSummary.append(baseName(file)).append('\n');
                    return FileVisitResult.CONTINUE;
                }

                listener.onCurrentFile(file);
                Path relativePath = sourceRoot.relativize(file);
                Path target = destinationRoot.resolve(relativePath);
                Path parent = target.getParent();
                Files.createDirectories(parent);
                createdDirectories.add(parent);
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);

                int copied = copiedCount.incrementAndGet();
                Path relativeParent = relativePath.getParent();
                if (relativeParent != null && !relativeParent.equals(lastDirectory[0])) {
                    summary.append('\n').append(relativeParent.toString().toUpperCase(Locale.ROOT)).append('\n');
                    lastDirectory[0] = relativeParent;
                }
                summary.append(baseName(file)).append('\n');
                listener.onCopyProgress(copied, totalMp3);
                return FileVisitResult.CONTINUE;
            }
        });

        int removedCount = 0;
        if (!cancelled.getAsBoolean() && options.cleanDestination()) {
            listener.onStatus("Pulizia DESTINAZIONE…");
            removedCount = cleanDestination(sourceRoot, destinationRoot, cancelled, listener);
        }
        if (!cancelled.getAsBoolean()) {
            cleanupEmptyDirectories(createdDirectories);
        }

        return new Result(copiedCount.get(), skippedDirectories.get(), removedCount,
                summary.toString(), skippedSummary.toString());
    }

    private int countEligibleMp3(Path root, Options options, BooleanSupplier cancelled) throws IOException {
        AtomicInteger count = new AtomicInteger();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                return isExcludedDirectory(directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                if (isMp3(file) && !isFiltered(options, Mp3ScanService.containsLyricsBegin(file))) {
                    count.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return count.get();
    }

    private int cleanDestination(Path sourceRoot, Path destinationRoot, BooleanSupplier cancelled,
            ProgressListener listener) throws IOException {
        AtomicInteger removed = new AtomicInteger();

        Files.walkFileTree(destinationRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                return cancelled.getAsBoolean() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                if (isMp3(file)) {
                    Path relativePath = destinationRoot.relativize(file);
                    Path sourceFile = sourceRoot.resolve(relativePath);
                    if (!Files.exists(sourceFile)) {
                        Files.delete(file);
                        listener.onRemovedCount(removed.incrementAndGet());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return removed.get();
    }

    private void cleanupEmptyDirectories(Set<Path> directories) throws IOException {
        for (Path directory : directories.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                .toList()) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            boolean empty;
            try (Stream<Path> entries = Files.list(directory)) {
                empty = !entries.findAny().isPresent();
            }
            if (empty) {
                try {
                    Files.delete(directory);
                } catch (DirectoryNotEmptyException ignored) {
                    // Another process added an entry after the empty-directory check.
                }
            }
        }
    }

    private void validateRoots(Path sourceRoot, Path destinationRoot) throws IOException {
        Path source = sourceRoot.toRealPath();
        Path destination = destinationRoot.toRealPath();
        if (source.startsWith(destination) || destination.startsWith(source)) {
            throw new IOException("ORIGINE e DESTINAZIONE non possono coincidere o contenersi.");
        }
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

    private boolean isFiltered(Options options, boolean hasLyrics) {
        return (options.lyricsOnly() && !hasLyrics) || (options.noLyricsOnly() && hasLyrics);
    }

    private String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".mp3".length());
    }

    record Options(boolean lyricsOnly, boolean noLyricsOnly, boolean cleanDestination) {}

    record Result(int copiedCount, int skippedDirectories, int removedCount,
            String summary, String skippedSummary) {}

    interface ProgressListener {
        void onStatus(String message);

        void onCurrentFile(Path file);

        void onCopyProgress(int copiedCount, int totalCount);

        void onRemovedCount(int removedCount);
    }
}
