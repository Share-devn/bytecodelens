package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.search.SearchEngine;
import dev.share.bytecodelens.search.SearchIndex;
import dev.share.bytecodelens.search.SearchMode;
import dev.share.bytecodelens.search.SearchQuery;
import dev.share.bytecodelens.search.SearchResult;
import dev.share.bytecodelens.ui.views.ClassEditorTab;
import dev.share.bytecodelens.ui.views.HighlightRequest;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class SearchOverlay extends VBox {

    private final TextField searchField = new TextField();
    private final TextField packageField = new TextField();
    private final ToggleButton caseBtn = new ToggleButton("Aa");
    private final ToggleButton wordBtn = new ToggleButton("W");
    private final CheckBox classesCheck = new CheckBox("Classes");
    private final CheckBox resourcesCheck = new CheckBox("Resources");
    private final Label resultsCount = new Label("");
    private final Label excludedLabel = new Label("");
    private final ChoiceBox<SortOrder> sortChoice = new ChoiceBox<>();
    private final Button cancelBtn = new Button();
    private final ProgressBar progress = new ProgressBar(0);
    private final ListView<SearchResult> resultsList = new ListView<>();
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleButton stringsMode = new ToggleButton("Strings");
    private final ToggleButton namesMode = new ToggleButton("Names");
    private final ToggleButton bytecodeMode = new ToggleButton("Bytecode");
    private final ToggleButton regexMode = new ToggleButton("Regex");
    private final ToggleButton numbersMode = new ToggleButton("Numbers");
    private final ToggleButton commentsMode = new ToggleButton("Comments");

    /**
     * Results sort order. Drives a single comparator applied on the live result list
     * whenever the user picks from the sort dropdown OR when streaming adds a new item.
     */
    public enum SortOrder {
        INSERTION("Match order"),
        PATH("Path A\u2192Z"),
        LINE("Line number"),
        KIND("Result kind");
        private final String label;
        SortOrder(String l) { this.label = l; }
        @Override public String toString() { return label; }
    }

    private final SearchEngine engine = new SearchEngine();
    private SearchIndex index;
    /** Cancelled by Cancel button / new search start — checked by engine between classes. */
    private AtomicBoolean currentCancel = new AtomicBoolean(false);
    /** Thread running the streaming scan, so we can interrupt/ignore its stragglers. */
    private Thread runningThread;
    /** Incremented on every new search; used to ignore stale results from previous runs. */
    private final AtomicInteger searchGen = new AtomicInteger(0);
    /** Collected in-order result snapshot (pre-sort). Kept so re-sorts don't lose items. */
    private final List<SearchResult> liveResults = new ArrayList<>();
    /** Wall-clock start of the current search for ETA / progress labelling. */
    private long currentStartMs;
    private List<String> excludedPackages = List.of();
    private ClassOpener onResultOpenClass;
    private ResourceOpener onResultOpenResource;
    private Runnable onClose;
    private java.util.function.Consumer<List<String>> onExcludedChanged;

    public interface ClassOpener {
        void open(String fqn, HighlightRequest highlight, ClassEditorTab.View preferredView);
    }

    public interface ResourceOpener {
        void open(String path, HighlightRequest highlight);
    }

    public SearchOverlay() {
        getStyleClass().add("search-overlay");
        setPadding(new Insets(10, 12, 10, 12));
        setSpacing(8);

        getChildren().addAll(buildTopBar(), buildFiltersBar(), buildResultsHeader(), resultsList);
        VBox.setVgrow(resultsList, Priority.ALWAYS);

        setupMode();
        setupShortcuts();
        setupResultsList();
        setupSortChoice();
        setupCancelButton();

        searchField.textProperty().addListener((obs, o, n) -> debounceSearch());
        packageField.textProperty().addListener((obs, o, n) -> debounceSearch());
        caseBtn.selectedProperty().addListener((obs, o, n) -> triggerSearch());
        wordBtn.selectedProperty().addListener((obs, o, n) -> triggerSearch());
        classesCheck.selectedProperty().addListener((obs, o, n) -> triggerSearch());
        resourcesCheck.selectedProperty().addListener((obs, o, n) -> triggerSearch());
    }

    /** Populate the sort dropdown and wire re-sort on selection. */
    private void setupSortChoice() {
        sortChoice.getItems().setAll(SortOrder.values());
        sortChoice.setValue(SortOrder.INSERTION);
        sortChoice.setTooltip(new Tooltip("Sort order for results"));
        sortChoice.valueProperty().addListener((obs, o, n) -> resortDisplayedResults());
    }

    private void setupCancelButton() {
        cancelBtn.setGraphic(iconOf("mdi2s-stop"));
        cancelBtn.getStyleClass().add("button-icon");
        cancelBtn.setTooltip(new Tooltip("Cancel current search"));
        cancelBtn.setFocusTraversable(false);
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> cancelCurrent());
    }

    /** Called by the UI's Cancel button and automatically when a new search starts. */
    private void cancelCurrent() {
        currentCancel.set(true);
        if (runningThread != null && runningThread.isAlive()) {
            // Cooperative cancel — the flag check inside the engine loop halts soon.
            runningThread.interrupt();
        }
        progress.setProgress(0);
        cancelBtn.setDisable(true);
    }

    /** Let the hosting controller notify us (+ persist) when the exclusion list changes. */
    public void setOnExcludedChanged(java.util.function.Consumer<List<String>> handler) {
        this.onExcludedChanged = handler;
    }

    /** Push an updated exclusion list into the overlay (from workspace restore or tree menu). */
    public void setExcludedPackages(List<String> excluded) {
        this.excludedPackages = excluded == null ? List.of() : List.copyOf(excluded);
        updateExcludedLabel();
        if (isVisible()) triggerSearch();
    }

    public List<String> excludedPackages() {
        return excludedPackages;
    }

    private void updateExcludedLabel() {
        if (excludedPackages.isEmpty()) {
            excludedLabel.setText("");
            excludedLabel.setTooltip(null);
            return;
        }
        excludedLabel.setText("Excluding " + excludedPackages.size() + " package"
                + (excludedPackages.size() == 1 ? "" : "s"));
        excludedLabel.setTooltip(new Tooltip(String.join("\n", excludedPackages)
                + "\n\nClick to clear all exclusions."));
        excludedLabel.setOnMouseClicked(ev -> {
            excludedPackages = List.of();
            updateExcludedLabel();
            if (onExcludedChanged != null) onExcludedChanged.accept(excludedPackages);
            triggerSearch();
        });
    }

    public void setIndex(SearchIndex index) {
        this.index = index;
        if (isVisible()) triggerSearch();
    }

    public void setOnOpenClass(ClassOpener handler) {
        this.onResultOpenClass = handler;
    }

    public void setOnOpenResource(ResourceOpener handler) {
        this.onResultOpenResource = handler;
    }

    public void setOnClose(Runnable handler) {
        this.onClose = handler;
    }

    public void focusSearchField() {
        Platform.runLater(() -> {
            searchField.requestFocus();
            searchField.selectAll();
        });
    }

    /** Pre-fill the query text before {@link #focusSearchField()} so right-click Search works. */
    public void prefillQuery(String text) {
        if (text == null) return;
        searchField.setText(text);
    }

    private HBox buildTopBar() {
        FontIcon icon = new FontIcon("mdi2m-magnify");
        icon.setIconSize(18);
        icon.getStyleClass().add("search-icon");

        searchField.setPromptText("Find in jar...");
        searchField.getStyleClass().add("search-main-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        caseBtn.setTooltip(new Tooltip("Match case"));
        wordBtn.setTooltip(new Tooltip("Whole word"));
        caseBtn.getStyleClass().add("search-option-btn");
        wordBtn.getStyleClass().add("search-option-btn");

        stringsMode.setToggleGroup(modeGroup);
        namesMode.setToggleGroup(modeGroup);
        bytecodeMode.setToggleGroup(modeGroup);
        regexMode.setToggleGroup(modeGroup);
        numbersMode.setToggleGroup(modeGroup);
        commentsMode.setToggleGroup(modeGroup);
        stringsMode.setSelected(true);
        stringsMode.getStyleClass().add("mode-btn");
        namesMode.getStyleClass().add("mode-btn");
        bytecodeMode.getStyleClass().add("mode-btn");
        bytecodeMode.setTooltip(new Tooltip(
                "Search the textified bytecode. Useful for member refs by descriptor:\n"
                        + "  java/util/HashMap.put  — every invocation of HashMap.put\n"
                        + "  (Ljava/lang/String;)Z  — methods accepting a String, returning boolean"));
        regexMode.getStyleClass().add("mode-btn");
        numbersMode.getStyleClass().add("mode-btn");
        numbersMode.setTooltip(new Tooltip(
                "Find methods that push a numeric literal equal to the query (e.g. 42, 0xDEAD, 3.14f)"));
        commentsMode.getStyleClass().add("mode-btn");
        commentsMode.setTooltip(new Tooltip(
                "Search user-authored comments on classes, methods and fields."));

        Button closeBtn = new Button();
        closeBtn.setGraphic(iconOf("mdi2c-close"));
        closeBtn.getStyleClass().add("button-icon");
        closeBtn.setTooltip(new Tooltip("Close  (Esc)"));
        closeBtn.setOnAction(e -> close());
        closeBtn.setFocusTraversable(false);

        HBox row = new HBox(8, icon, searchField,
                stringsMode, namesMode, bytecodeMode, regexMode, numbersMode, commentsMode,
                caseBtn, wordBtn, closeBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox buildFiltersBar() {
        Label pkgLabel = new Label("Package:");
        pkgLabel.getStyleClass().add("search-filter-label");
        packageField.setPromptText("e.g. com.app.*  (leave empty for all)");
        packageField.setPrefWidth(360);

        classesCheck.setSelected(true);
        resourcesCheck.setSelected(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, pkgLabel, packageField, spacer, classesCheck, resourcesCheck);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox buildResultsHeader() {
        resultsCount.getStyleClass().add("search-results-count");
        excludedLabel.getStyleClass().add("search-excluded-label");
        progress.setPrefWidth(140);
        progress.setVisible(false);
        progress.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label sortLabel = new Label("Sort:");
        sortLabel.getStyleClass().add("search-filter-label");

        HBox row = new HBox(8, resultsCount, excludedLabel, progress, cancelBtn,
                spacer, sortLabel, sortChoice);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 0, 0));
        return row;
    }

    private void setupMode() {
        modeGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                if (old != null) old.setSelected(true);
            } else {
                triggerSearch();
            }
        });
    }

    private void setupShortcuts() {
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                close();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && e.getSource() == searchField) {
                triggerSearch();
                e.consume();
            }
        });
    }

    private void setupResultsList() {
        resultsList.getStyleClass().add("search-results");
        resultsList.setPlaceholder(new Label("Type to search..."));
        resultsList.setCellFactory(lv -> new SearchCell());
        resultsList.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 2) openSelected();
        });
        resultsList.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected();
                e.consume();
            }
        });
    }

    private void openSelected() {
        SearchResult r = resultsList.getSelectionModel().getSelectedItem();
        if (r == null) return;
        String query = searchField.getText();
        SearchMode mode = currentMode();
        HighlightRequest highlight = buildHighlight(r, query, mode);
        ClassEditorTab.View view = preferredViewFor(r);

        switch (r.targetKind()) {
            case CLASS_STRING, CLASS_NAME, CLASS_METHOD, CLASS_FIELD, CLASS_BYTECODE -> {
                if (onResultOpenClass != null) onResultOpenClass.open(r.targetPath(), highlight, view);
            }
            case RESOURCE_TEXT -> {
                if (onResultOpenResource != null) onResultOpenResource.open(r.targetPath(), highlight);
            }
        }
    }

    private static HighlightRequest buildHighlight(SearchResult r, String query, SearchMode mode) {
        if (query == null || query.isBlank()) return HighlightRequest.none();
        return switch (mode) {
            case REGEX -> HighlightRequest.regex(query, -1);
            default -> HighlightRequest.literal(query, -1);
        };
    }

    private static ClassEditorTab.View preferredViewFor(SearchResult r) {
        return switch (r.targetKind()) {
            case CLASS_STRING, CLASS_NAME, CLASS_METHOD, CLASS_FIELD, COMMENT -> ClassEditorTab.View.DECOMPILED;
            case CLASS_BYTECODE -> ClassEditorTab.View.BYTECODE;
            case RESOURCE_TEXT -> ClassEditorTab.View.DECOMPILED;
        };
    }

    private long lastTrigger;

    private void debounceSearch() {
        lastTrigger = System.currentTimeMillis();
        long triggeredAt = lastTrigger;
        Platform.runLater(() -> new Thread(() -> {
            try {
                Thread.sleep(180);
            } catch (InterruptedException ignored) {
            }
            if (triggeredAt == lastTrigger) {
                Platform.runLater(this::triggerSearch);
            }
        }, "search-debounce").start());
    }

    private void triggerSearch() {
        if (index == null) {
            resultsCount.setText("No jar loaded");
            resultsList.getItems().clear();
            liveResults.clear();
            return;
        }
        String text = searchField.getText();
        if (text == null || text.isBlank()) {
            resultsCount.setText("");
            resultsList.getItems().clear();
            liveResults.clear();
            hideProgress();
            return;
        }

        SearchMode mode = currentMode();
        SearchQuery query = new SearchQuery(
                text, mode,
                caseBtn.isSelected(),
                wordBtn.isSelected(),
                packageField.getText(),
                classesCheck.isSelected(),
                resourcesCheck.isSelected(),
                excludedPackages
        );

        // Cancel previous and bump generation so late results drop on the floor.
        cancelCurrent();
        final int gen = searchGen.incrementAndGet();
        final AtomicBoolean cancel = new AtomicBoolean(false);
        currentCancel = cancel;
        liveResults.clear();
        resultsList.getItems().clear();
        resultsCount.setText("Searching...");
        currentStartMs = System.currentTimeMillis();
        showProgress();

        // Streaming consumer: every hit is shipped back to the FX thread in a small batch
        // so we don't fire 5000 Platform.runLater's for a huge search. Batching 50 at a
        // time keeps UI latency low but avoids per-item overhead on big jars.
        List<SearchResult> pendingBatch = new ArrayList<>();
        final int[] totalFound = {0};
        Consumer<SearchResult> consumer = r -> {
            if (gen != searchGen.get()) return;  // superseded by a newer search
            synchronized (pendingBatch) {
                pendingBatch.add(r);
                totalFound[0]++;
                if (pendingBatch.size() >= 50) flushBatch(pendingBatch, gen);
            }
        };

        Thread t = new Thread(() -> {
            try {
                engine.search(index, query, consumer, cancel::get);
            } catch (Throwable ex) {
                Platform.runLater(() -> {
                    if (gen == searchGen.get()) {
                        resultsCount.setText("Search failed: " + ex.getMessage());
                        hideProgress();
                    }
                });
                return;
            }
            synchronized (pendingBatch) {
                flushBatch(pendingBatch, gen);
            }
            Platform.runLater(() -> {
                if (gen != searchGen.get()) return;
                hideProgress();
                boolean cancelled = cancel.get();
                long files = resultsList.getItems().stream()
                        .map(SearchResult::targetPath).distinct().count();
                long elapsed = System.currentTimeMillis() - currentStartMs;
                String tag = cancelled ? " (cancelled)" : "";
                resultsCount.setText(String.format("%d matches in %d locations — %d ms%s",
                        totalFound[0], files, elapsed, tag));
            });
        }, "search-worker");
        t.setDaemon(true);
        runningThread = t;
        t.start();
    }

    /**
     * Move pending items into the visible ListView under the FX thread. Called both
     * periodically (every 50 items) and once at the end with whatever remains. Respects
     * the generation so late batches from a superseded search get dropped.
     */
    private void flushBatch(List<SearchResult> pending, int gen) {
        if (pending.isEmpty()) return;
        List<SearchResult> snapshot = new ArrayList<>(pending);
        pending.clear();
        Platform.runLater(() -> {
            if (gen != searchGen.get()) return;
            liveResults.addAll(snapshot);
            // Apply the user's chosen sort before pushing to the visible list. For
            // INSERTION the comparator is a no-op and we avoid a pointless resort.
            if (sortChoice.getValue() != SortOrder.INSERTION) {
                liveResults.sort(comparatorFor(sortChoice.getValue()));
                resultsList.setItems(FXCollections.observableArrayList(liveResults));
            } else {
                resultsList.getItems().addAll(snapshot);
            }
            resultsCount.setText(liveResults.size() + " matches…");
        });
    }

    /** Re-sort the displayed list in place without re-running the search. */
    private void resortDisplayedResults() {
        if (liveResults.isEmpty()) return;
        SortOrder order = sortChoice.getValue();
        if (order == SortOrder.INSERTION) {
            // Insertion order is the order we collected results — we never reshuffled
            // liveResults so it's already correct. Just re-push verbatim.
            resultsList.setItems(FXCollections.observableArrayList(liveResults));
            return;
        }
        List<SearchResult> sorted = new ArrayList<>(liveResults);
        sorted.sort(comparatorFor(order));
        resultsList.setItems(FXCollections.observableArrayList(sorted));
    }

    private static Comparator<SearchResult> comparatorFor(SortOrder order) {
        return switch (order) {
            case INSERTION -> (a, b) -> 0;
            case PATH -> Comparator.comparing(SearchResult::targetPath,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparingInt(SearchResult::lineNumber);
            case LINE -> Comparator.comparingInt(SearchResult::lineNumber)
                    .thenComparing(SearchResult::targetPath,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case KIND -> Comparator.comparing(r -> r.targetKind().name());
        };
    }

    private void showProgress() {
        progress.setVisible(true);
        progress.setManaged(true);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        cancelBtn.setDisable(false);
    }

    private void hideProgress() {
        progress.setVisible(false);
        progress.setManaged(false);
        progress.setProgress(0);
        cancelBtn.setDisable(true);
    }

    private SearchMode currentMode() {
        var selected = modeGroup.getSelectedToggle();
        if (selected == namesMode) return SearchMode.NAMES;
        if (selected == bytecodeMode) return SearchMode.BYTECODE;
        if (selected == regexMode) return SearchMode.REGEX;
        if (selected == numbersMode) return SearchMode.NUMBERS;
        if (selected == commentsMode) return SearchMode.COMMENTS;
        return SearchMode.STRINGS;
    }

    private static FontIcon iconOf(String literal) {
        FontIcon i = new FontIcon(literal);
        i.setIconSize(14);
        return i;
    }

    public void close() {
        if (onClose != null) onClose.run();
    }

    private static final class SearchCell extends ListCell<SearchResult> {
        @Override
        protected void updateItem(SearchResult r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            FontIcon icon = iconForKind(r.targetKind());
            icon.setIconSize(14);

            Label target = new Label(r.targetLabel());
            target.getStyleClass().add("search-target");

            Label path = new Label(shorten(r.targetPath()));
            path.getStyleClass().add("search-path");

            Label ctx = new Label(r.context());
            ctx.getStyleClass().add("search-context");

            Label line = r.lineNumber() > 0
                    ? new Label("L" + r.lineNumber())
                    : new Label("");
            line.getStyleClass().add("search-line-no");

            HBox header = new HBox(8, icon, target, path, ctx);
            header.setAlignment(Pos.CENTER_LEFT);

            TextFlow snippet = buildSnippet(r);
            snippet.getStyleClass().add("search-snippet");

            HBox snippetRow = new HBox(8, line, snippet);
            snippetRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(snippet, Priority.ALWAYS);

            VBox cell = new VBox(2, header, snippetRow);
            cell.setPadding(new Insets(4, 6, 4, 6));
            setGraphic(cell);
            setText(null);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        private static TextFlow buildSnippet(SearchResult r) {
            String s = r.lineText() == null ? "" : r.lineText();
            int start = Math.max(0, Math.min(r.matchStart(), s.length()));
            int end = Math.max(start, Math.min(r.matchEnd(), s.length()));

            int ctxStart = Math.max(0, start - 40);
            int ctxEnd = Math.min(s.length(), end + 80);

            Text prefix = new Text((ctxStart > 0 ? "…" : "") + s.substring(ctxStart, start));
            prefix.getStyleClass().add("search-snippet-text");

            Text match = new Text(s.substring(start, end));
            match.getStyleClass().add("search-snippet-match");

            Text suffix = new Text(s.substring(end, ctxEnd) + (ctxEnd < s.length() ? "…" : ""));
            suffix.getStyleClass().add("search-snippet-text");

            return new TextFlow(prefix, match, suffix);
        }

        private static FontIcon iconForKind(SearchResult.TargetKind k) {
            FontIcon i = new FontIcon(switch (k) {
                case CLASS_STRING -> "mdi2f-format-quote-close";
                case CLASS_NAME -> "mdi2c-code-braces";
                case CLASS_METHOD -> "mdi2f-function-variant";
                case CLASS_FIELD -> "mdi2a-alpha-f-box-outline";
                case CLASS_BYTECODE -> "mdi2c-chip";
                case RESOURCE_TEXT -> "mdi2f-file-document-outline";
                case COMMENT -> "mdi2c-comment-text-outline";
            });
            i.getStyleClass().add("search-kind-icon");
            return i;
        }

        private static String shorten(String path) {
            return path.length() > 80 ? "…" + path.substring(path.length() - 77) : path;
        }
    }
}
