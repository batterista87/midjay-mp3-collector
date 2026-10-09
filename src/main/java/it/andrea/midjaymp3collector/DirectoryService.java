package it.andrea.midjaymp3collector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class DirectoryService {

    Directories prepare(String sourceText, String destinationText) throws IOException {
        if (sourceText.isBlank() && destinationText.isBlank()) {
            throw new IllegalArgumentException("Inserisci sia ORIGINE sia DESTINAZIONE.");
        }
        if (destinationText.isBlank()) {
            throw new IllegalArgumentException("Inserisci DESTINAZIONE.");
        }

        Path source = source(sourceText);
        Path destination = Paths.get(destinationText.trim()).toAbsolutePath().normalize();

        Path realSource = source.toRealPath();
        Path realDestination = resolveDestination(destination);
        validateRoots(realSource, realDestination);

        Files.createDirectories(destination);
        return new Directories(source, destination);
    }

    Path source(String sourceText) throws IOException {
        if (sourceText.isBlank()) {
            throw new IllegalArgumentException("Inserisci la cartella ORIGINE.");
        }

        Path source = Paths.get(sourceText.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IOException("La cartella ORIGINE non esiste.");
        }
        return source;
    }

    private Path resolveDestination(Path destination) throws IOException {
        Path existing = destination;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("Impossibile determinare il percorso DESTINAZIONE.");
        }
        Path realExisting = existing.toRealPath();
        return realExisting.resolve(existing.relativize(destination)).normalize();
    }

    private void validateRoots(Path source, Path destination) throws IOException {
        if (source.startsWith(destination) || destination.startsWith(source)) {
            throw new IOException("ORIGINE e DESTINAZIONE non possono coincidere o contenersi.");
        }
    }

    record Directories(Path source, Path destination) {}
}
