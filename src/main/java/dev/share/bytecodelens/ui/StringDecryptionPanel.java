package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.crypto.DecryptedString;
import dev.share.bytecodelens.crypto.DecryptionResult;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BiConsumer;

public final class StringDecryptionPanel extends BorderPane {

    private final Label headerLabel = new Label("No decryption run yet");
    private final TextField filterField = new TextField();
    private final TableView<DecryptedString> table = new TableView<>();
    private final Button exportBtn = new Button();
    private final Button runBtn = new Button();
    private final Button runReflectionBtn = new Button();

    private Runnable onRunSimulation;
    private Runnable onRunReflection;
    private BiConsumer<String, Integer> onOpenClass;

    public StringDecryptionPanel() {
        getStyleClass().add("decrypt-panel");
        setTop(buildToolbar());
        setCenter(buildTable());
    }

    public void setOnRunSimulation(Runnable r) { this.onRunSimulation = r; }
    public void setOnRunReflection(Runnable r) { this.onRunReflection = r; }
    public void setOnOpenClass(BiConsumer<String, Integer> handler) { this.onOpenClass = handler; }

    public void showResult(DecryptionResult result) {
        if (result == null) {
            headerLabel.setText("No decryption run yet");
            table.setItems(FXCollections.observableArrayList());
            return;
        }
        headerLabel.setText(String.format(
                "Decrypted %d strings \u2022 %d sim \u2022 %d reflection \u2022 %d failed \u2022 %d candidates \u2022 %dms",
                result.decrypted().size(), result.simulationHits(), result.reflectionHits(),
                result.failures(), result.candidates().size(), result.durationMs()));
        var observable = FXCollections.observableArrayList(result.decrypted());
        FilteredList<DecryptedString> filtered = new FilteredList<>(observable, d -> true);
        table.setItems(filtered);
        filterField.textProperty().addListener((obs, o, n) -> {
            String q = n == null ? "" : n.trim().toLowerCase();
            filtered.setPredicate(d -> {
                if (q.isEmpty()) return true;
                return (d.decrypted() != null && d.decrypted().toLowerCase().contains(q))
                        || (d.encrypted() != null && d.encrypted().toLowerCase().contains(q))
                        || (d.inClassFqn() != null && d.inClassFqn().toLowerCase().contains(q))
                        || (d.decryptOwner() != null && d.decryptOwner().toLowerCase().contains(q));
            });
        });
    }

    public void clear() {
        showResult(null);
    }

    private HBox buildToolbar() {
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("decrypt-toolbar");

        FontIcon runIcon = new FontIcon("mdi2p-play");
        runIcon.setIconSize(14);
        runBtn.setGraphic(runIcon);
        runBtn.setText("Decrypt (simulation)");
        runBtn.setContentDisplay(ContentDisplay.LEFT);
        runBtn.getStyleClass().addAll("button-outlined", "decrypt-run-btn");
        runBtn.setOnAction(e -> { if (onRunSimulation != null) onRunSimulation.run(); });

        FontIcon reflIcon = new FontIcon("mdi2s-shield-alert-outline");
        reflIcon.setIconSize(14);
        runReflectionBtn.setGraphic(reflIcon);
        runReflectionBtn.setText("+ reflection");
        runReflectionBtn.setContentDisplay(ContentDisplay.LEFT);
        runReflectionBtn.getStyleClass().addAll("button-outlined", "decrypt-reflect-btn");
        runReflectionBtn.setTooltip(new Tooltip(
                "Also run unsupported decryptors through reflection.\n"
                        + "WARNING: this executes code from the jar."));
        runReflectionBtn.setOnAction(e -> { if (onRunReflection != null) onRunReflection.run(); });

        filterField.setPromptText("Filter by string / class / decryptor");
        filterField.setPrefWidth(280);

        FontIcon expIcon = new FontIcon("mdi2e-export-variant");
        expIcon.setIconSize(14);
        exportBtn.setGraphic(expIcon);
        exportBtn.setText("Export");
        exportBtn.setContentDisplay(ContentDisplay.LEFT);
        exportBtn.getStyleClass().add("button-outlined");
        exportBtn.setOnAction(e -> exportToFile());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        headerLabel.getStyleClass().add("decrypt-header");

        bar.getChildren().addAll(runBtn, runReflectionBtn, filterField, spacer, exportBtn);
        BorderPane.setMargin(headerLabel, new Insets(4, 12, 0, 12));
        javafx.scene.layout.VBox top = new javafx.scene.layout.VBox(bar, headerLabel);
        top.getStyleClass().add("decrypt-top");
        setTop(top);
        return bar;
    }

    private TableView<DecryptedString> buildTable() {
        TableColumn<DecryptedString, String> classCol = new TableColumn<>("Used in class");
        classCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().inClassFqn() == null ? "" : c.getValue().inClassFqn().replace('/', '.')));
        classCol.setPrefWidth(260);

        TableColumn<DecryptedString, String> methodCol = new TableColumn<>("Method");
        methodCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().inMethodName() == null ? "" : c.getValue().inMethodName()));
        methodCol.setPrefWidth(160);

        TableColumn<DecryptedString, String> encCol = new TableColumn<>("Encrypted");
        encCol.setCellValueFactory(c -> new SimpleStringProperty(escape(c.getValue().encrypted())));
        encCol.setPrefWidth(200);

        TableColumn<DecryptedString, String> decCol = new TableColumn<>("Decrypted");
        decCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().decrypted()));
        decCol.setPrefWidth(360);

        TableColumn<DecryptedString, String> decryptorCol = new TableColumn<>("Decryptor");
        decryptorCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().decryptOwner().replace('/', '.') + "." + c.getValue().decryptName()));
        decryptorCol.setPrefWidth(240);

        TableColumn<DecryptedString, String> modeCol = new TableColumn<>("Mode");
        modeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().mode().name().toLowerCase()));
        modeCol.setPrefWidth(90);

        TableColumn<DecryptedString, String> lineCol = new TableColumn<>("Line");
        lineCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().lineNumber() > 0 ? String.valueOf(c.getValue().lineNumber()) : ""));
        lineCol.setPrefWidth(60);

        table.getColumns().addAll(classCol, methodCol, encCol, decCol, decryptorCol, modeCol, lineCol);
        table.setPlaceholder(Placeholders.build("mdi2l-lock-open-variant-outline",
                "No decryption run yet",
                "Click 'Decrypt (simulation)' to find and decrypt obfuscated strings."));
        table.getStyleClass().add("decrypt-table");
        ClipboardUtil.installTableCopy(table);
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<DecryptedString> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() >= 2 && !row.isEmpty() && onOpenClass != null) {
                    DecryptedString d = row.getItem();
                    onOpenClass.accept(d.inClassFqn().replace('/', '.'), d.lineNumber());
                }
            });
            return row;
        });
        return table;
    }

    private void exportToFile() {
        List<DecryptedString> items = table.getItems();
        if (items.isEmpty()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export decrypted strings");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text", "*.txt"));
        chooser.setInitialFileName("decrypted-strings.txt");
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;
        try (BufferedWriter w = Files.newBufferedWriter(file.toPath())) {
            for (DecryptedString d : items) {
                w.write(d.inClassFqn());
                w.write(" \t");
                w.write(d.inMethodName());
                w.write(" \t");
                w.write("\"" + escape(d.encrypted()) + "\"");
                w.write(" -> ");
                w.write("\"" + d.decrypted() + "\"");
                w.write(" \t[");
                w.write(d.decryptOwner().replace('/', '.') + "." + d.decryptName());
                w.write("]\n");
            }
        } catch (Exception ex) {
            // silently ignore
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
