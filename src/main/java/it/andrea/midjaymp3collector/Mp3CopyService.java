package it.andrea.midjaymp3collector;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
        if (options.lyricsOnly() && options.noLyricsOnly()) {
            throw new IllegalArgumentException("Seleziona al massimo un filtro LyricsBegin.");
        }
        validateRoots(sourceRoot, destinationRoot);

        listener.onCountingStarted();
        int totalMp3 = countMp3(sourceRoot, cancelled);
        if (cancelled.getAsBoolean()) {
            return new Result(0, 0, 0, 0, "", "");
        }
        listener.onFileProgress(0, totalMp3);

        AtomicInteger copiedCount = new AtomicInteger();
        AtomicInteger processedCount = new AtomicInteger();
        AtomicInteger skippedDirectories = new AtomicInteger();
        AtomicInteger filteredCount = new AtomicInteger();
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

                if (Mp3FileRules.isExcludedDirectory(directory)) {
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
                if (!Mp3FileRules.isMp3(file)) {
                    return FileVisitResult.CONTINUE;
                }

                listener.onCurrentFile(file);
                boolean hasLyrics = requiresLyricsCheck(options) && containsLyrics(file, cancelled);
                if (cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                if (isFiltered(options, hasLyrics)) {
                    filteredCount.incrementAndGet();
                    skippedSummary.append(baseName(file)).append('\n');
                    listener.onFileProgress(processedCount.incrementAndGet(), totalMp3);
                    return FileVisitResult.CONTINUE;
                }

                Path relativePath = sourceRoot.relativize(file);
                Path target = destinationRoot.resolve(relativePath);
                Path parent = target.getParent();
                try {
                    Files.createDirectories(parent);
                } catch (IOException ex) {
                    throw new IOException("Impossibile preparare la destinazione per il file '" + file
                            + "' in '" + target + "': " + ex.getMessage(), ex);
                }
                createdDirectories.add(parent);
                try {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    throw new IOException("Impossibile copiare il file '" + file + "' in '" + target
                            + "': " + ex.getMessage(), ex);
                }

                copiedCount.incrementAndGet();
                Path relativeParent = relativePath.getParent();
                if (relativeParent != null && !relativeParent.equals(lastDirectory[0])) {
                    summary.append('\n').append(relativeParent.toString().toUpperCase(Locale.ROOT)).append('\n');
                    lastDirectory[0] = relativeParent;
                }
                summary.append(baseName(file)).append('\n');
                listener.onFileProgress(processedCount.incrementAndGet(), totalMp3);
                return FileVisitResult.CONTINUE;
            }
        });

        int removedCount = 0;
        if (!cancelled.getAsBoolean() && options.cleanDestination()) {
            listener.onStatus("Pulizia DESTINAZIONE…");
            removedCount = cleanDestination(sourceRoot, destinationRoot, cancelled, listener);
        }
        if (!cancelled.getAsBoolean()) {
            cleanupEmptyDirectories(createdDirectories, cancelled);
        }

        return new Result(copiedCount.get(), skippedDirectories.get(), removedCount,
                filteredCount.get(), summary.toString(), skippedSummary.toString());
    }

    private int countMp3(Path root, BooleanSupplier cancelled) throws IOException {
        AtomicInteger count = new AtomicInteger();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                return Mp3FileRules.isExcludedDirectory(directory)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                if (Mp3FileRules.isMp3(file)) {
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
        Set<Path> affectedDirectories = new HashSet<>();
        Path normalizedDestination = destinationRoot.toAbsolutePath().normalize();

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
                if (Mp3FileRules.isMp3(file)) {
                    Path relativePath = destinationRoot.relativize(file);
                    Path sourceFile = sourceRoot.resolve(relativePath);
                    if (isDefinitelyMissing(sourceFile)) {
                        try {
                            Files.delete(file);
                        } catch (IOException ex) {
                            throw new IOException("Impossibile rimuovere il file extra '" + file
                                    + "': " + ex.getMessage(), ex);
                        }
                        addParentDirectories(file.getParent(), normalizedDestination, affectedDirectories);
                        listener.onRemovedCount(removed.incrementAndGet());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (!cancelled.getAsBoolean()) {
            cleanupEmptyDirectories(affectedDirectories, cancelled);
        }
        return removed.get();
    }

    private void addParentDirectories(Path directory, Path root, Set<Path> directories) {
        Path current = directory == null ? null : directory.toAbsolutePath().normalize();
        while (current != null && !current.equals(root) && current.startsWith(root)) {
            directories.add(current);
            current = current.getParent();
        }
    }

    private boolean isDefinitelyMissing(Path sourceFile) throws IOException {
        try {
            Files.readAttributes(sourceFile, BasicFileAttributes.class);
            return false;
        } catch (NoSuchFileException ex) {
            return true;
        } catch (IOException ex) {
            throw new IOException("Impossibile verificare il file sorgente '" + sourceFile
                    + "': " + ex.getMessage(), ex);
        }
    }

    private boolean containsLyrics(Path file, BooleanSupplier cancelled) throws IOException {
        try {
            return Mp3ScanService.containsLyricsBegin(file, cancelled);
        } catch (IOException ex) {
            throw new IOException("Impossibile leggere il file MP3 '" + file
                    + "' per verificare LYRICSBEGIN: " + ex.getMessage(), ex);
        }
    }

    private void cleanupEmptyDirectories(Set<Path> directories, BooleanSupplier cancelled) throws IOException {
        for (Path directory : directories.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                .toList()) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                if (!entries.findAny().isPresent()) {
                    try {
                        Files.delete(directory);
                    } catch (DirectoryNotEmptyException ignored) {
                        // Another process added an entry after the empty-directory check.
                    }
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

    private boolean isFiltered(Options options, boolean hasLyrics) {
        return (options.lyricsOnly() && !hasLyrics) || (options.noLyricsOnly() && hasLyrics);
    }

    private boolean requiresLyricsCheck(Options options) {
        return options.lyricsOnly() || options.noLyricsOnly();
    }

    private String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".mp3".length());
    }

    record Options(boolean lyricsOnly, boolean noLyricsOnly, boolean cleanDestination) {}

    record Result(int copiedCount, int skippedDirectories, int removedCount, int filteredCount,
            String summary, String skippedSummary) {}

    interface ProgressListener {
        void onCountingStarted();

        void onStatus(String message);

        void onCurrentFile(Path file);

        void onFileProgress(int processedCount, int totalCount);

        void onRemovedCount(int removedCount);
    }
}
