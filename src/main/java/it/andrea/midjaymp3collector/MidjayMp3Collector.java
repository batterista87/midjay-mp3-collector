package it.andrea.midjaymp3collector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

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
    private Button browseSourceButton;
    private Button browseTargetButton;
    private Button printListButton;
    private Button printLyricsButton;
    private ProgressBar progressBar;
    private Label statusLabel;
    Label progressText;
    private CheckBox lyricsOnlyCheck;
    private CheckBox noLyricsOnlyCheck;
    private Label currentFileLabel;

    private Task<?> worker;
    private final DirectoryPreferences directoryPreferences = new DirectoryPreferences();
    private final DirectoryService directoryService = new DirectoryService();
    private final Mp3CopyService copyService = new Mp3CopyService();
    private final Mp3ScanService scanService = new Mp3ScanService();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void stop() {
        saveLastUsedPaths();
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Midjay MP3 Collector");

        sourceField = new TextField(directoryPreferences.sourceDirectory());
        targetField = new TextField(directoryPreferences.destinationDirectory());
        cleanCheck = new CheckBox("PULISCI DESTINAZIONE (rimuove .mp3 extra)");
        lyricsOnlyCheck = new CheckBox("COPIA SOLO FILE CON LYRICSBEGIN");
        noLyricsOnlyCheck = new CheckBox("COPIA SOLO FILE SENZA LYRICSBEGIN");
        lyricsOnlyCheck.setOnAction(event -> {
            if (lyricsOnlyCheck.isSelected()) {
                noLyricsOnlyCheck.setSelected(false);
            }
        });
        noLyricsOnlyCheck.setOnAction(event -> {
            if (noLyricsOnlyCheck.isSelected()) {
                lyricsOnlyCheck.setSelected(false);
            }
        });

        browseSourceButton = new Button("[...]");
        browseSourceButton.setOnAction(e -> chooseDirectory(sourceField, stage));

        browseTargetButton = new Button("[...]");
        browseTargetButton.setOnAction(e -> chooseDirectory(targetField, stage));

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
        grid.add(browseSourceButton, 2, 0);

        grid.add(new Label("DESTINAZIONE:"), 0, 1);
        grid.add(targetField, 1, 1);
        grid.add(browseTargetButton, 2, 1);

        HBox progressBox = new HBox(12, statusLabel, progressBar, progressText);
        progressBox.setAlignment(Pos.CENTER_LEFT);
        currentFileLabel = new Label("");
        
        printListButton = new Button("STAMPA LISTA");
        printListButton.setOnAction(e -> onPrintList());
        
        printLyricsButton = new Button("STAMPA SOLO LYRICSBEGIN");
        printLyricsButton.setOnAction(e -> onPrintLyricsOnly());

        HBox buttons = new HBox(12, startButton, cancelButton, printListButton, printLyricsButton);

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
        if (dir != null) {
            field.setText(dir.getAbsolutePath());
            saveLastUsedPaths();
        }
    }

    private void onStart() {
        DirectoryService.Directories directories;
        try {
            directories = directoryService.prepare(sourceField.getText(), targetField.getText());
        } catch (IllegalArgumentException ex) {
            alert(Alert.AlertType.WARNING, ex.getMessage());
            return;
        } catch (IOException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
            return;
        }

        saveLastUsedPaths();
        startProcess(directories.source(), directories.destination());
    }

    private void saveLastUsedPaths() {
        if (sourceField != null && targetField != null) {
            directoryPreferences.save(sourceField.getText(), targetField.getText());
        }
    }

    private void startProcess(Path src, Path dst) {
        setBusy(true);
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        progressText.textProperty().unbind();
        progressBar.setProgress(0);
        progressText.setText("");
        Mp3CopyService.Options options = new Mp3CopyService.Options(
                lyricsOnlyCheck.isSelected(),
                noLyricsOnlyCheck.isSelected(),
                cleanCheck.isSelected());

        worker = new Task<>() {
            private Mp3CopyService.Result result;

            @Override
            protected Void call() throws Exception {
                result = copyService.copy(src, dst, options, this::isCancelled,
                        new Mp3CopyService.ProgressListener() {
                    @Override
                    public void onStatus(String message) {
                        updateMessage(message);
                    }

                    @Override
                    public void onCurrentFile(Path file) {
                        Platform.runLater(() -> currentFileLabel.setText(file.toString()));
                    }

                    @Override
                    public void onCopyProgress(int copiedCount, int totalCount) {
                        updateProgress(copiedCount, totalCount);
                        updateMessage("Copia in corso…");
                        int percentage = totalCount == 0 ? 100 : (int) Math.round(copiedCount * 100.0 / totalCount);
                        updateTitle(String.format("%d / %d (%d%%)", copiedCount, totalCount, percentage));
                    }

                    @Override
                    public void onRemovedCount(int removedCount) {
                        updateMessage("Rimossi: " + removedCount);
                    }
                });
                return null;
            }

            @Override
            protected void succeeded() {
                statusLabel.textProperty().unbind();
                progressBar.progressProperty().unbind();
                progressBar.setProgress(1.0);
                progressText.textProperty().unbind();
                progressText.setText("Completato");

                String finalSummary = result.summary().isEmpty()
                        ? "Nessun MP3 copiato."
                        : "=== FILE COPIATI ===\n" + result.summary();

                if (!result.skippedSummary().isEmpty()) {
                    finalSummary += "\n\n=== FILE ESCLUSI DAL FILTRO ("
                            + result.filteredCount() + ") ===\n" + result.skippedSummary();
                } else if (result.filteredCount() > 0) {
                    finalSummary += "\n\nFile esclusi dal filtro: " + result.filteredCount();
                }

                showSummaryWindow(finalSummary);
                finished();
            }

            @Override
            protected void cancelled() {
                statusLabel.textProperty().unbind();
                progressBar.progressProperty().unbind();
                progressBar.setProgress(0);
                progressText.textProperty().unbind();
                progressText.setText("Annullato");

                setBusy(false);
                statusLabel.setText("Annullata; modifiche già eseguite mantenute.");
                alert(Alert.AlertType.WARNING,
                        "Operazione annullata dall’utente.\n"
                                + "I file già copiati o rimossi non sono stati ripristinati.");
            }

            @Override
            protected void failed() {
                statusLabel.textProperty().unbind();
                progressBar.progressProperty().unbind();
                progressBar.setProgress(0);
                progressText.textProperty().unbind();
                progressText.setText("Non completato");

                setBusy(false);
                Throwable error = getException();
                String detail = error == null || error.getMessage() == null
                        ? "Errore non specificato."
                        : error.getMessage();
                statusLabel.setText("Errore durante l’operazione.");
                alert(Alert.AlertType.ERROR, "Errore durante l’operazione: " + detail);
            }

            private void finished() {
                setBusy(false);
                statusLabel.setText("Completato.");
                alert(Alert.AlertType.INFORMATION,
                        "Operazione completata.\n" +
                                "Copiati: " + result.copiedCount() + "\n" +
                                "Esclusi: " + result.skippedDirectories() + "\n" +
                                "Esclusi dal filtro LyricsBegin: " + result.filteredCount() + "\n" +
                                (cleanCheck.isSelected() ? ("Rimossi: " + result.removedCount()) : "")
                );
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
        browseSourceButton.setDisable(busy);
        browseTargetButton.setDisable(busy);
        cleanCheck.setDisable(busy);
        lyricsOnlyCheck.setDisable(busy);
        noLyricsOnlyCheck.setDisable(busy);
        printListButton.setDisable(busy);
        printLyricsButton.setDisable(busy);
    }

    private void alert(Alert.AlertType type, String msg) {
        Platform.runLater(() -> new Alert(type, msg).showAndWait());
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
    
    private void onPrintList() {
        scanAndShowList(false);
    }

    private void scanAndShowList(boolean lyricsOnly) {
        Path source;
        try {
            source = directoryService.source(sourceField.getText());
        } catch (IllegalArgumentException ex) {
            alert(Alert.AlertType.WARNING, ex.getMessage());
            return;
        } catch (IOException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
            return;
        }

        Task<String> task = new Task<>() {

            @Override
            protected String call() throws Exception {
                return lyricsOnly
                        ? scanService.scanMp3WithLyrics(source, this::isCancelled)
                        : scanService.scanAllMp3(source, this::isCancelled);
            }

            @Override
            protected void succeeded() {
                setBusy(false);
                worker = null;
                statusLabel.setText("Pronto.");
                progressBar.setProgress(1.0);
                progressText.setText("Completato");
                showSummaryWindow(getValue());
            }

            @Override
            protected void failed() {
                setBusy(false);
                worker = null;
                statusLabel.setText("Errore durante la scansione.");
                progressBar.setProgress(0);
                progressText.setText("Non completato");
                Throwable error = getException();
                String detail = error == null || error.getMessage() == null
                        ? "Errore non specificato."
                        : error.getMessage();
                alert(Alert.AlertType.ERROR, "Errore durante la scansione: " + detail);
            }

            @Override
            protected void cancelled() {
                setBusy(false);
                worker = null;
                statusLabel.setText("Scansione annullata.");
                progressBar.setProgress(0);
                progressText.setText("Annullato");
            }
        };

        setBusy(true);
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        progressText.textProperty().unbind();
        progressBar.setProgress(-1);
        progressText.setText("Scansione…");
        statusLabel.setText("Scansione in corso…");
        worker = task;
        new Thread(task).start();
    }
    
    private void onPrintLyricsOnly() {
        scanAndShowList(true);
    }

}