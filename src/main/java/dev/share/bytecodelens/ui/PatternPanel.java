package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.pattern.ast.Pattern;
import dev.share.bytecodelens.pattern.eval.Evaluator;
import dev.share.bytecodelens.pattern.eval.PatternResult;
import dev.share.bytecodelens.pattern.parser.Parser;
import dev.share.bytecodelens.pattern.parser.PatternParseException;
import dev.share.bytecodelens.ui.highlight.BlplHighlighter;
import dev.share.bytecodelens.ui.views.CodeView;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;
import java.util.function.Consumer;

public final class PatternPanel extends BorderPane {

    private final CodeView editor = new CodeView(BlplHighlighter::compute);
    private final ListView<PatternResult> results = new ListView<>();
    private final Label statusLabel = new Label("Ready");
    private final javafx.scene.control.ProgressIndicator progressIndicator = new javafx.scene.control.ProgressIndicator();
    private final Button runButton = new Button();
    private final MenuButton examplesButton = new MenuButton("Examples");

    private final Evaluator evaluator = new Evaluator();
    private LoadedJar jar;
    private Consumer<PatternResult> onOpen;

    public PatternPanel() {
        getStyleClass().add("pattern-panel");

        editor.setEditable(true);
        editor.setText(PatternExamples.DEFAULT);

        FontIcon runIcon = new FontIcon("mdi2p-play");
        runIcon.setIconSize(14);
        runButton.setGraphic(runIcon);
        runButton.setText("Run");
        runButton.setContentDisplay(ContentDisplay.LEFT);
        runButton.getStyleClass().addAll("button-outlined", "pattern-run-btn");
        runButton.setOnAction(e -> runPattern());

        FontIcon exIcon = new FontIcon("mdi2b-book-open-variant");
        exIcon.setIconSize(14);
        examplesButton.setGraphic(exIcon);
        examplesButton.getStyleClass().addAll("button-outlined");
        buildExamplesMenu();

        statusLabel.getStyleClass().add("pattern-status");
        progressIndicator.setVisible(false);
        progressIndicator.setPrefSize(18, 18);
        progressIndicator.setMaxSize(18, 18);
        progressIndicator.getStyleClass().add("pattern-progress");

        HBox toolbar = new HBox(8, runButton, examplesButton, new spacer(), progressIndicator, statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.getStyleClass().add("pattern-toolbar");

        results.setCellFactory(lv -> new ResultCell());
        results.setPlaceholder(Placeholders.build("mdi2p-play-circle-outline",
                "No results yet",
                "Write a pattern on the left and press Run to search the jar."));
        results.getStyleClass().add("pattern-results");
        results.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 2) openSelected();
        });
        ClipboardUtil.installListCopy(results, r -> r == null ? "" : r.label());

        VBox left = new VBox(toolbar, editor);
        VBox.setVgrow(editor, Priority.ALWAYS);
        left.getStyleClass().add("pattern-left");

        Label resultsHeader = new Label("MATCHES");
        resultsHeader.getStyleClass().add("panel-title");
        VBox right = new VBox(resultsHeader, results);
        VBox.setVgrow(results, Priority.ALWAYS);
        right.getStyleClass().add("pattern-right");

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.60);
        setCenter(split);
    }

    public void setJar(LoadedJar jar) {
        this.jar = jar;
        if (jar == null) {
            results.getItems().clear();
            statusLabel.setText("No jar loaded");
        } else {
            statusLabel.setText("Ready - " + jar.classCount() + " classes");
        }
    }

    public void setOnOpen(Consumer<PatternResult> handler) {
        this.onOpen = handler;
    }

    private void runPattern() {
        if (jar == null) {
            statusLabel.setText("No jar loaded");
            return;
        }
        String source = editor.getText();
        if (source == null || source.isBlank()) {
            statusLabel.setText("Empty pattern");
            return;
        }

        Pattern pattern;
        try {
            pattern = Parser.parse(source);
        } catch (PatternParseException ex) {
            statusLabel.setText("Parse error: " + ex.getMessage());
            results.getItems().clear();
            return;
        } catch (Exception ex) {
            statusLabel.setText("Error: " + ex.getMessage());
            return;
        }

        runButton.setDisable(true);
        progressIndicator.setVisible(true);
        statusLabel.setText("Evaluating...");
        long start = System.currentTimeMillis();

        Task<List<PatternResult>> task = new Task<>() {
            @Override
            protected List<PatternResult> call() {
                return evaluator.evaluate(jar, pattern);
            }
        };
        task.setOnSucceeded(e -> {
            runButton.setDisable(false);
            progressIndicator.setVisible(false);
            List<PatternResult> list = task.getValue();
            results.setItems(FXCollections.observableArrayList(list));
            statusLabel.setText(String.format("%d matches - %dms",
                    list.size(), System.currentTimeMillis() - start));
        });
        task.setOnFailed(e -> {
            runButton.setDisable(false);
            progressIndicator.setVisible(false);
            Throwable ex = task.getException();
            statusLabel.setText("Evaluation failed: " + (ex == null ? "unknown" : ex.getMessage()));
        });

        Thread t = new Thread(task, "pattern-evaluator");
        t.setDaemon(true);
        t.start();
    }

    private void openSelected() {
        PatternResult r = results.getSelectionModel().getSelectedItem();
        if (r != null && onOpen != null) onOpen.accept(r);
    }

    private void buildExamplesMenu() {
        for (var ex : PatternExamples.all()) {
            MenuItem mi = new MenuItem(ex.title());
            mi.setOnAction(e -> editor.setText(ex.source()));
            examplesButton.getItems().add(mi);
        }
    }

    private static final class spacer extends Region {
        spacer() {
            HBox.setHgrow(this, Priority.ALWAYS);
        }
    }

    private static final class ResultCell extends ListCell<PatternResult> {
        @Override
        protected void updateItem(PatternResult r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            FontIcon icon = new FontIcon(switch (r.kind()) {
                case CLASS -> "mdi2c-code-braces";
                case METHOD -> "mdi2f-function-variant";
                case FIELD -> "mdi2a-alpha-f-box-outline";
            });
            icon.setIconSize(14);
            icon.getStyleClass().add("pattern-result-icon");

            Label label = new Label(r.label());
            label.getStyleClass().add("pattern-result-label");

            HBox row = new HBox(8, icon, label);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(3, 6, 3, 6));
            setGraphic(row);
            setText(null);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }
    }
}
