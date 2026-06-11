package it.andrea.midjaymp3collector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

public class MidjayMp3Collector extends Application {

    private TextField sourceField;
    private TextField targetField;
    private CheckBox cleanCheck;
    private Button startButton;
    private Button cancelButton;
    private ProgressBar progressBar;
    private Label statusLabel;
    Label progressText;
    private CheckBox lyricsOnlyCheck;
    private CheckBox noLyricsOnlyCheck;
    private Label currentFileLabel;

    private Task<Void> worker;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Midjay MP3 Collector");

        sourceField = new TextField();
        targetField = new TextField();
        cleanCheck = new CheckBox("PULISCI DESTINAZIONE (rimuove .mp3 extra)");
        lyricsOnlyCheck = new CheckBox("COPIA SOLO FILE CON LYRICSBEGIN");
        noLyricsOnlyCheck = new CheckBox("COPIA SOLO FILE SENZA LYRICSBEGIN");

        Button browseSource = new Button("[...]");
        browseSource.setOnAction(e -> chooseDirectory(sourceField, stage));

        Button browseTarget = new Button("[...]");
        browseTarget.setOnAction(e -> chooseDirectory(targetField, stage));

        startButton = new Button("Avvia");
        startButton.setOnAction(e -> onStart());

        cancelButton = new Button("Annulla");
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> {
            if (worker != null) worker.cancel();
            statusLabel.setText("Annullamento in corso…");
        });

        statusLabel = new Label("Pronto.");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(350);

        progressText = new Label("");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        ColumnConstraints c1 = new ColumnConstraints();
        ColumnConstraints c2 = new ColumnConstraints();
        ColumnConstraints c3 = new ColumnConstraints();

        c1.setHgrow(Priority.NEVER);
        c2.setHgrow(Priority.ALWAYS);
        c3.setHgrow(Priority.NEVER);

        grid.getColumnConstraints().addAll(c1, c2, c3);

        grid.add(new Label("ORIGINE:"), 0, 0);
        grid.add(sourceField, 1, 0);
        grid.add(browseSource, 2, 0);

        grid.add(new Label("DESTINAZIONE:"), 0, 1);
        grid.add(targetField, 1, 1);
        grid.add(browseTarget, 2, 1);

        HBox progressBox = new HBox(12, statusLabel, progressBar, progressText);
        progressBox.setAlignment(Pos.CENTER_LEFT);
        currentFileLabel = new Label("");

        HBox buttons = new HBox(12, startButton, cancelButton);
        buttons.setAlignment(Pos.CENTER);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        VBox progressSection = new VBox(4, progressBox, currentFileLabel);

    	VBox root = new VBox(18,
    	    grid,
    	    cleanCheck,
    	    lyricsOnlyCheck,
    	    noLyricsOnlyCheck,
    	    progressSection,
    	    buttons
    	);
        root.setPadding(new Insets(20));
        root.setPrefWidth(700);

        Scene scene = new Scene(root);
        stage.setScene(scene);

        stage.sizeToScene();
        stage.show();

        Platform.runLater(() -> {
            double w = stage.getWidth();
            double h = stage.getHeight();

            stage.setMinWidth(w);
            stage.setMaxWidth(w);
            stage.setMinHeight(h);
            stage.setMaxHeight(h);

            stage.setResizable(false);
        });
    }

    private void chooseDirectory(TextField field, Stage stage) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Seleziona cartella");

        File existing = new File(field.getText());
        if (existing.isDirectory()) dc.setInitialDirectory(existing);

        File dir = dc.showDialog(stage);
        if (dir != null) field.setText(dir.getAbsolutePath());
    }

    private void onStart() {
        String origineText = sourceField.getText().trim();
        String destinazioneText = targetField.getText().trim();

        if (origineText.isEmpty() || destinazioneText.isEmpty()) {
            alert(Alert.AlertType.WARNING, "Inserisci sia ORIGINE sia DESTINAZIONE.");
            return;
        }

        Path src = Paths.get(origineText);
        Path dst = Paths.get(destinazioneText);

        if (!Files.isDirectory(src)) {
            alert(Alert.AlertType.ERROR, "La cartella ORIGINE non esiste.");
            return;
        }

        if (src.equals(dst) || dst.normalize().startsWith(src.normalize())) {
            alert(Alert.AlertType.ERROR, "DESTINAZIONE non può essere interna a ORIGINE.");
            return;
        }

        try {
            Files.createDirectories(dst);
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Errore creazione DESTINAZIONE: " + ex.getMessage());
            return;
        }

        startProcess(src, dst);
    }

    private void startProcess(Path src, Path dst) {
        setBusy(true);

        worker = new Task<>() {

            private int totalMp3 = 0;
            private int copiedCount = 0;
            private int skippedDirs = 0;
            private int removedCount = 0;

            private final StringBuilder summary = new StringBuilder();
            private final StringBuilder skippedSummary = new StringBuilder();
            private Path lastDir = null;

            private final Set<Path> createdDirs = new HashSet<>();

            @Override
            protected Void call() throws Exception {

                updateMessage("Conteggio file .mp3…");
                updateProgress(-1, 1);

                totalMp3 = countEligibleMp3(src);
                if (isCancelled()) return null;

                updateProgress(0, 100);

                Files.walkFileTree(src, new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (isCancelled()) return FileVisitResult.TERMINATE;

                        String name = dir.getFileName().toString();
                        if (name.equalsIgnoreCase("_OLD") || name.toUpperCase().startsWith("Z_")) {
                            skippedDirs++;
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        // NON creare qui la cartella
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (isCancelled()) return FileVisitResult.TERMINATE;

                        String name = file.getFileName().toString().toLowerCase();

                        // 🔍 IGNORA SUBITO TUTTO CIÒ CHE NON È MP3
                        if (!name.endsWith(".mp3")) {
                            return FileVisitResult.CONTINUE;
                        }

                        boolean hasLyrics = containsLyricsBegin(file);

                        // 🔍 FILTRO: SOLO CON LYRICSBEGIN
                        if (lyricsOnlyCheck.isSelected() && !hasLyrics) {
                            skippedSummary.append(file.getFileName().toString().replace(".mp3", "").replace(".MP3", "")).append("\n");
                            return FileVisitResult.CONTINUE;
                        }

                        // 🔍 FILTRO: SOLO SENZA LYRICSBEGIN
                        if (noLyricsOnlyCheck.isSelected() && hasLyrics) {
                            skippedSummary.append(file.getFileName().toString().replace(".mp3", "").replace(".MP3", "")).append("\n");
                            return FileVisitResult.CONTINUE;
                        }

                        // 🔍 AGGIORNA LABEL FILE CORRENTE
                        Platform.runLater(() -> currentFileLabel.setText(file.toString()));

                        // 🔍 COPIA FILE
                        Path rel = src.relativize(file);
                        Path target = dst.resolve(rel);

                        Path parent = target.getParent();
                        Files.createDirectories(parent);
                        createdDirs.add(parent);

                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);

                        copiedCount++;

                        // === RIEPILOGO ===
                        Path relParent = rel.getParent();
                        if (relParent != null && !relParent.equals(lastDir)) {
                            summary.append("\n").append(relParent.toString().toUpperCase()).append("\n");
                            lastDir = relParent;
                        }

                        String baseName = file.getFileName().toString().replace(".mp3", "").replace(".MP3", "");
                        summary.append(baseName).append("\n");

                        double pct = (copiedCount * 100.0) / totalMp3;
                        String indicator = String.format("%d / %d (%.0f%%)", copiedCount, totalMp3, pct);

                        updateProgress(copiedCount, totalMp3);
                        updateMessage("Copia in corso…");
                        updateTitle(indicator);

                        return FileVisitResult.CONTINUE;
                    }
                });

                if (!isCancelled() && cleanCheck.isSelected()) {
                    updateMessage("Pulizia DESTINAZIONE…");
                    updateProgress(-1, 1);

                    removedCount = cleanDestination(src, dst);
                }

                return null;
            }

            @Override
            protected void succeeded() {
                statusLabel.textProperty().unbind();
                progressBar.progressProperty().unbind();
                progressBar.setProgress(1.0);
                progressText.textProperty().unbind();
                progressText.setText("Completato");

                cleanupEmptyDirectories(createdDirs);

                if (lyricsOnlyCheck.isSelected()) {
                	String finalSummary = summary.toString();

                	if (skippedSummary.length() > 0) {
                	    finalSummary += "\n\n=== FILE SCARTATI ===\n" + skippedSummary;
                	}

                	showSummaryWindow(finalSummary);
                }

                finished(true);
            }

            @Override
            protected void cancelled() {
                statusLabel.textProperty().unbind();
                progressBar.progressProperty().unbind();
                progressBar.setProgress(1.0);
                progressText.textProperty().unbind();
                progressText.setText("Annullato");

                finished(false);
            }

            @Override
            protected void failed() {
                statusLabel.textProperty().unbind();
                progressBar.progressProperty().unbind();
                progressBar.setProgress(1.0);
                progressText.textProperty().unbind();
                progressText.setText("Errore");

                finished(false);
            }

            private void finished(boolean ok) {
                setBusy(false);

                if (!ok) {
                    statusLabel.setText("Operazione annullata.");
                    alert(Alert.AlertType.WARNING, "Operazione annullata dall’utente.");
                    return;
                }

                statusLabel.setText("Completato.");
                alert(Alert.AlertType.INFORMATION,
                        "Operazione completata.\n" +
                                "Copiati: " + copiedCount + "\n" +
                                "Esclusi: " + skippedDirs + "\n" +
                                (cleanCheck.isSelected() ? ("Rimossi: " + removedCount) : "")
                );
            }

            private int countEligibleMp3(Path root) throws IOException {
                AtomicInteger count = new AtomicInteger(0);

                Files.walkFileTree(root, new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName().toString();
                        if (name.equalsIgnoreCase("_OLD") || name.toUpperCase().startsWith("Z_"))
                            return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.toString().toLowerCase();

                        if (!name.endsWith(".mp3"))
                            return FileVisitResult.CONTINUE;

                        boolean hasLyrics = containsLyricsBegin(file);

                        // FILTRO: solo con LyricsBegin
                        if (lyricsOnlyCheck.isSelected() && !hasLyrics)
                            return FileVisitResult.CONTINUE;

                        // FILTRO: solo senza LyricsBegin
                        if (noLyricsOnlyCheck.isSelected() && hasLyrics)
                            return FileVisitResult.CONTINUE;

                        // Se arriva qui → è un file valido
                        count.incrementAndGet();
                        return FileVisitResult.CONTINUE;
                    }
                });

                return count.get();
            }

            private int cleanDestination(Path sourceRoot, Path destRoot) throws IOException {
                AtomicInteger removed = new AtomicInteger(0);

                Files.walkFileTree(destRoot, new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().toLowerCase().endsWith(".mp3")) {
                            Path rel = destRoot.relativize(file);
                            Path originCandidate = sourceRoot.resolve(rel);

                            if (!Files.exists(originCandidate)) {
                                try {
                                    Files.delete(file);
                                    removed.incrementAndGet();
                                    updateMessage("Rimossi: " + removed.get());
                                } catch (IOException ignored) {}
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });

                return removed.get();
            }

            private void cleanupEmptyDirectories(Set<Path> dirs) {
                for (Path dir : dirs) {
                    try {
                        if (Files.isDirectory(dir) && isDirectoryEmpty(dir)) {
                            Files.delete(dir);
                        }
                    } catch (Exception ignored) {}
                }
            }

            private boolean isDirectoryEmpty(Path dir) throws IOException {
                try (var stream = Files.list(dir)) {
                    return !stream.findAny().isPresent();
                }
            }
        };

        progressBar.progressProperty().bind(worker.progressProperty());
        statusLabel.textProperty().bind(worker.messageProperty());
        progressText.textProperty().bind(worker.titleProperty());

        new Thread(worker).start();
    }

    private void setBusy(boolean busy) {
        startButton.setDisable(busy);
        cancelButton.setDisable(!busy);
        sourceField.setDisable(busy);
        targetField.setDisable(busy);
        cleanCheck.setDisable(busy);
        lyricsOnlyCheck.setDisable(busy);
        noLyricsOnlyCheck.setDisable(busy);
    }

    private void alert(Alert.AlertType type, String msg) {
        Platform.runLater(() -> new Alert(type, msg).showAndWait());
    }

    private boolean containsLyricsBegin(Path file) {
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            byte[] buffer = new byte[64 * 1024];
            int read;

            while ((read = fis.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, read);
                if (chunk.contains("LYRICSBEGIN")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    private void showSummaryWindow(String text) {
        Platform.runLater(() -> {
            Stage dialog = new Stage();
            dialog.setTitle("Riepilogo file copiati");

            TextArea area = new TextArea(text.trim());
            area.setEditable(false);
            area.setWrapText(false);

            area.setPrefWidth(500);
            area.setPrefHeight(400);

            VBox box = new VBox(area);
            box.setPadding(new Insets(10));

            Scene scene = new Scene(box);
            dialog.setScene(scene);
            dialog.show();
        });
    }
}