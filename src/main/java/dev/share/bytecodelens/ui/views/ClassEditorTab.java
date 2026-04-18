package dev.share.bytecodelens.ui.views;

import dev.share.bytecodelens.decompile.CfrDecompiler;
import dev.share.bytecodelens.decompile.ClassDecompiler;
import dev.share.bytecodelens.decompile.DecompileCache;
import dev.share.bytecodelens.decompile.ProcyonDecompiler;
import dev.share.bytecodelens.decompile.VineflowerDecompiler;
import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.service.BytecodePrinter;
import dev.share.bytecodelens.service.Decompiler;
import dev.share.bytecodelens.ui.highlight.BytecodeHighlighter;
import dev.share.bytecodelens.ui.highlight.JavaHighlighter;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClassEditorTab {

    public enum View { DECOMPILED, BYTECODE, HEX }

    /** Real engines in the order the Auto chain tries them: fastest, then best, then bulletproof. */
    private static final ClassDecompiler[] REAL_DECOMPILERS = {
            new CfrDecompiler(),
            new VineflowerDecompiler(),
            new ProcyonDecompiler(),
            new dev.share.bytecodelens.decompile.FallbackDecompiler()
    };
    /**
     * Selector entries including the "Auto" pseudo-engine at index 0. "Auto" runs the
     * full chain; individual entries still let power-users pin a specific engine.
     */
    private static final ClassDecompiler[] DECOMPILERS;
    static {
        DECOMPILERS = new ClassDecompiler[REAL_DECOMPILERS.length + 1];
        DECOMPILERS[0] = new dev.share.bytecodelens.decompile.AutoDecompiler(
                java.util.Arrays.asList(REAL_DECOMPILERS));
        System.arraycopy(REAL_DECOMPILERS, 0, DECOMPILERS, 1, REAL_DECOMPILERS.length);
    }

    private final Tab tab;
    private final ClassEntry entry;
    private final CodeView decompiledView;
    private final CodeView bytecodeView;
    private final HexView hexView;
    private final Tab decompiledTab;
    private final Tab bytecodeTab;
    private final Tab hexTab;
    private final Tab compareTab;
    private final TabPane innerTabs;
    private final FindBar decompiledFind;
    private final FindBar bytecodeFind;
    private final ComboBox<ClassDecompiler> decompilerSelector;
    private final Label decompileStatus = new Label();

    /**
     * Shared cross-tab decompile cache. Hot-reload invalidates entries automatically
     * because the cache key includes a hash of the class bytes — re-opening a tab
     * for the same class+engine is instant when bytes are unchanged. Capacity 256
     * bounds memory on browse-heavy sessions; eviction is LRU.
     */
    static final DecompileCache SHARED_CACHE = new DecompileCache();
    public static DecompileCache sharedCache() { return SHARED_CACHE; }

    /**
     * Shared per-class decompile outcome tracker. Lets the class tree paint a status
     * badge ("⚠" for fallback-only, "✗" for failed) so the user can see at a glance
     * which classes the decompiler couldn't render cleanly.
     */
    static final dev.share.bytecodelens.decompile.DecompileStatusTracker STATUS_TRACKER =
            new dev.share.bytecodelens.decompile.DecompileStatusTracker();
    public static dev.share.bytecodelens.decompile.DecompileStatusTracker statusTracker() {
        return STATUS_TRACKER;
    }

    private final Map<String, String> decompileCache = new ConcurrentHashMap<>();
    private ClassDecompiler activeDecompiler = DECOMPILERS[0];
    private boolean decompiledLoaded = false;
    private boolean bytecodeLoaded = false;
    private boolean compareBuilt = false;
    private boolean editMode = false;
    private javafx.scene.control.ToggleButton editToggle;
    private javafx.scene.control.Button compileButton;

    /** Expose the decompiled-view so the controller can install a Ctrl+Click handler. */
    public CodeView decompiledView() { return decompiledView; }
    public ClassEntry entry() { return entry; }

    /**
     * The controller provides a function that actually performs the compile (has access to
     * the current LoadedJar to use as classpath) and returns the result. The tab then handles
     * post-compile UI (status line, error list).
     */
    public interface CompileFn {
        dev.share.bytecodelens.compile.JavaSourceCompiler.CompileResult run(String fileName, String source);
    }

    private CompileFn compileFn;

    public void setCompileFn(CompileFn fn) { this.compileFn = fn; }

    public ClassEditorTab(ClassEntry entry, BytecodePrinter printer, Decompiler unusedLegacy) {
        this.entry = entry;
        this.tab = new Tab(entry.simpleName());
        tab.setTooltip(new Tooltip(entry.name()));
        tab.setGraphic(icon("mdi2l-language-java"));

        bytecodeView = new CodeView(BytecodeHighlighter::compute);
        bytecodeView.setText("// Loading bytecode...");

        decompiledView = new CodeView(JavaHighlighter::compute);
        decompiledView.setText("// Loading decompiled source...");

        hexView = new HexView();

        decompilerSelector = buildDecompilerSelector();
        decompiledFind = new FindBar(decompiledView);
        bytecodeFind = new FindBar(bytecodeView);

        editToggle = new javafx.scene.control.ToggleButton("Edit");
        editToggle.setGraphic(icon("mdi2p-pencil-outline"));
        editToggle.getStyleClass().add("button-icon");
        editToggle.setOnAction(e -> toggleEditMode(editToggle.isSelected()));

        compileButton = new javafx.scene.control.Button("Compile");
        compileButton.setGraphic(icon("mdi2h-hammer"));
        compileButton.getStyleClass().add("button-icon");
        compileButton.setVisible(false);
        compileButton.setManaged(false);
        compileButton.setOnAction(e -> runCompile());

        HBox decompiledToolbar = new HBox(8,
                new Label("Decompiler:"), decompilerSelector, editToggle, compileButton,
                spacer(), decompileStatus);
        decompiledToolbar.setPadding(new Insets(4, 10, 4, 10));
        decompiledToolbar.setAlignment(Pos.CENTER_LEFT);
        decompiledToolbar.getStyleClass().add("decompiler-toolbar");
        decompileStatus.getStyleClass().add("decompiler-status");

        VBox decompiledPane = new VBox(decompiledToolbar, decompiledFind, decompiledView);
        VBox.setVgrow(decompiledView, Priority.ALWAYS);
        VBox bytecodePane = new VBox(bytecodeFind, bytecodeView);
        VBox.setVgrow(bytecodeView, Priority.ALWAYS);

        innerTabs = new TabPane();
        innerTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        innerTabs.getStyleClass().add("editor-views");

        decompiledTab = new Tab("Decompiled", decompiledPane);
        bytecodeTab = new Tab("Bytecode", bytecodePane);
        hexTab = new Tab("Hex", hexView);
        compareTab = new Tab("Compare", new Label("Opening..."));
        decompiledTab.setGraphic(icon("mdi2c-code-tags"));
        bytecodeTab.setGraphic(icon("mdi2c-chip"));
        hexTab.setGraphic(icon("mdi2p-pound"));
        compareTab.setGraphic(icon("mdi2c-compare"));
        decompiledTab.setClosable(false);
        bytecodeTab.setClosable(false);
        hexTab.setClosable(false);
        compareTab.setClosable(false);

        innerTabs.getTabs().addAll(decompiledTab, bytecodeTab, hexTab, compareTab);

        // Lazy-build Compare view when tab is first selected
        compareTab.selectedProperty().addListener((obs, old, now) -> {
            if (now && !compareBuilt) {
                compareBuilt = true;
                compareTab.setContent(buildCompareView());
            }
        });

        BorderPane root = new BorderPane(innerTabs);
        tab.setContent(root);

        installFindShortcut(decompiledView, decompiledFind);
        installFindShortcut(bytecodeView, bytecodeFind);

        loadBytecodeAsync(entry, printer, bytecodeView);
        loadDecompiledWith(activeDecompiler);
        hexView.setBytes(entry.bytes());
    }

    public Tab tab() {
        return tab;
    }

    private void toggleEditMode(boolean on) {
        editMode = on;
        decompiledView.setEditable(on);
        compileButton.setVisible(on);
        compileButton.setManaged(on);
        decompileStatus.setText(on ? "Edit mode — Ctrl+Enter to compile" : activeDecompiler.name());
        if (on) {
            // Make sure we jump to the decompiled tab in case user hit Edit from elsewhere.
            innerTabs.getSelectionModel().select(decompiledTab);
        }
    }

    private void runCompile() {
        if (compileFn == null) {
            decompileStatus.setText("No compile function wired — save not available");
            return;
        }
        String source = decompiledView.getText();
        String fileName = entry.simpleName() + ".java";
        decompileStatus.setText("Compiling...");
        decompiledView.clearErrorLines();
        Thread t = new Thread(() -> {
            var result = compileFn.run(fileName, source);
            javafx.application.Platform.runLater(() -> {
                if (result.success()) {
                    decompileStatus.setText("Compiled — " + result.outputClasses().size() + " class(es) emitted");
                    decompiledView.clearErrorLines();
                } else {
                    long errs = result.diagnostics().stream()
                            .filter(d -> d.level() == dev.share.bytecodelens.compile.JavaSourceCompiler.Level.ERROR)
                            .count();
                    decompileStatus.setText("Compile failed — " + errs + " error(s) (first: "
                            + firstErrorLine(result) + ")");
                    java.util.Set<Integer> lines = new java.util.HashSet<>();
                    for (var d : result.diagnostics()) {
                        if (d.level() == dev.share.bytecodelens.compile.JavaSourceCompiler.Level.ERROR
                                && d.line() > 0) {
                            lines.add((int) d.line());
                        }
                    }
                    decompiledView.markErrorLines(lines);
                    // Jump to and highlight the first error so the user can see it immediately.
                    result.diagnostics().stream()
                            .filter(d -> d.level() == dev.share.bytecodelens.compile.JavaSourceCompiler.Level.ERROR)
                            .findFirst()
                            .ifPresent(d -> {
                                if (d.line() > 0) decompiledView.goToLine((int) d.line());
                            });
                    offerDecompilerSwitch(result);
                }
            });
        }, "compile-" + entry.simpleName());
        t.setDaemon(true);
        t.start();
    }

    /**
     * When CFR's output has issues (missing casts, raw generics), often Vineflower or Procyon
     * produce cleaner source for the same bytes. On first compile failure we pop a one-shot
     * dialog asking whether to switch — users can dismiss to keep editing their current source.
     */
    private void offerDecompilerSwitch(dev.share.bytecodelens.compile.JavaSourceCompiler.CompileResult r) {
        // Only offer if there's a "real" decompiler-output mismatch — cast/conversion issues
        // are the classic sign. Simple user typos shouldn't trigger a nag.
        boolean looksDecompilerFault = r.diagnostics().stream().anyMatch(d ->
                d.message() != null && (
                        d.message().contains("incompatible types")
                                || d.message().contains("cannot be converted")
                                || d.message().contains("cannot find symbol")
                                || d.message().contains("unchecked cast")));
        if (!looksDecompilerFault) return;

        // Find the next decompiler in the list that isn't the current one and isn't
        // the Fallback — Fallback's output isn't real Java so there's no point suggesting
        // a compile retry against it.
        ClassDecompiler next = null;
        for (ClassDecompiler d : DECOMPILERS) {
            if (d == activeDecompiler) continue;
            if (d instanceof dev.share.bytecodelens.decompile.FallbackDecompiler) continue;
            next = d;
            break;
        }
        if (next == null) return;

        final ClassDecompiler target = next;
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle("Decompiler mismatch");
        alert.setHeaderText("The current decompiler (" + activeDecompiler.name()
                + ") produced source that doesn't type-check.");
        alert.setContentText("Try " + target.name() + " instead? Your edits will be replaced.");
        alert.getButtonTypes().setAll(
                new javafx.scene.control.ButtonType("Switch to " + target.name(),
                        javafx.scene.control.ButtonBar.ButtonData.OK_DONE),
                javafx.scene.control.ButtonType.CANCEL);
        alert.showAndWait().ifPresent(bt -> {
            if (bt.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                decompilerSelector.setValue(target);
            }
        });
    }

    private static String firstErrorLine(dev.share.bytecodelens.compile.JavaSourceCompiler.CompileResult r) {
        return r.diagnostics().stream()
                .filter(d -> d.level() == dev.share.bytecodelens.compile.JavaSourceCompiler.Level.ERROR)
                .findFirst()
                .map(d -> d.line() + ":" + d.message())
                .orElse("unknown");
    }

    public void clearHighlights() {
        decompiledView.clearHighlight();
        bytecodeView.clearHighlight();
    }

    public void applyHighlight(HighlightRequest request, View preferredView) {
        if (request == null) return;
        switch (preferredView) {
            case DECOMPILED -> {
                innerTabs.getSelectionModel().select(decompiledTab);
                applyWhenReady(decompiledView, request, () -> decompiledLoaded);
            }
            case BYTECODE -> {
                innerTabs.getSelectionModel().select(bytecodeTab);
                applyWhenReady(bytecodeView, request, () -> bytecodeLoaded);
            }
            case HEX -> {
                innerTabs.getSelectionModel().select(hexTab);
                if (request.line() > 0) hexView.goToByte((request.line() - 1) * 16);
            }
        }
    }

    private ComboBox<ClassDecompiler> buildDecompilerSelector() {
        ComboBox<ClassDecompiler> cb = new ComboBox<>();
        cb.getItems().addAll(DECOMPILERS);
        cb.setValue(DECOMPILERS[0]);
        cb.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ClassDecompiler d) { return d == null ? "" : d.name(); }
            @Override public ClassDecompiler fromString(String s) { return null; }
        });
        cb.setOnAction(e -> {
            ClassDecompiler chosen = cb.getValue();
            if (chosen != null) {
                activeDecompiler = chosen;
                loadDecompiledWith(chosen);
            }
        });
        cb.getStyleClass().add("decompiler-selector");
        return cb;
    }

    /**
     * Map a single-engine result to a tracker status. For "Auto" we can't tell from one
     * result alone (the chain absorbed individual failures into a header comment) — heuristic:
     * if the text starts with "// Auto: every engine failed" mark FALLBACK_ONLY, else SUCCESS.
     */
    private void recordStatus(dev.share.bytecodelens.decompile.DecompileResult r) {
        dev.share.bytecodelens.decompile.DecompileStatusTracker.Status s;
        if (!r.success() || r.timedOut()) {
            s = dev.share.bytecodelens.decompile.DecompileStatusTracker.Status.FAILED;
        } else if ("Fallback".equals(r.decompilerName())) {
            s = dev.share.bytecodelens.decompile.DecompileStatusTracker.Status.FALLBACK_ONLY;
        } else if ("Auto".equals(r.decompilerName()) && r.text() != null
                && r.text().startsWith("// Auto: every engine failed")) {
            s = dev.share.bytecodelens.decompile.DecompileStatusTracker.Status.FALLBACK_ONLY;
        } else {
            s = dev.share.bytecodelens.decompile.DecompileStatusTracker.Status.SUCCESS;
        }
        STATUS_TRACKER.update(entry.internalName(),
                new dev.share.bytecodelens.decompile.DecompileStatusTracker.Entry(
                        s, r.decompilerName(), r.reason(), r.elapsedMs()));
    }

    private void loadDecompiledWith(ClassDecompiler dec) {
        // Prefer the cross-tab shared cache (survives tab close/reopen, all classes share
        // capacity). Local map is kept as a tiny per-tab fast-path with no hashing cost.
        String cached = decompileCache.get(dec.name());
        if (cached == null) {
            cached = SHARED_CACHE.get(entry.internalName(), dec.name(), entry.bytes());
            if (cached != null) decompileCache.put(dec.name(), cached);
        }
        if (cached != null) {
            decompiledView.setText(cached);
            decompiledLoaded = true;
            decompileStatus.setText(dec.name() + " - cached");
            return;
        }
        decompiledView.setText("// Decompiling with " + dec.name() + "...");
        decompiledLoaded = false;
        decompileStatus.setText("Running " + dec.name() + "...");

        // Wrap the single-engine call in a one-item DecompileChain to inherit its
        // timeout + exception-catch semantics. Users who explicitly pick an engine
        // get the same 15s cap as the auto-chain — no infinite hang on SC2-style
        // opaque-dead-code classes.
        dev.share.bytecodelens.decompile.DecompileChain singleChain =
                new dev.share.bytecodelens.decompile.DecompileChain(java.util.List.of(dec));
        Task<dev.share.bytecodelens.decompile.DecompileResult> t = new Task<>() {
            @Override
            protected dev.share.bytecodelens.decompile.DecompileResult call() {
                var list = singleChain.run(entry.internalName(), entry.bytes());
                return list.get(0);
            }
        };
        t.setOnSucceeded(e -> {
            var r = t.getValue();
            decompileCache.put(dec.name(), r.text());
            SHARED_CACHE.put(entry.internalName(), dec.name(), entry.bytes(), r.text());
            decompiledView.setText(r.text());
            decompiledLoaded = true;
            String statusSuffix;
            if (r.timedOut()) statusSuffix = " - timed out";
            else if (!r.success()) statusSuffix = " - failed";
            else statusSuffix = " - " + r.elapsedMs() + "ms";
            decompileStatus.setText(dec.name() + statusSuffix);
            recordStatus(r);
        });
        t.setOnFailed(e -> {
            String msg = "// " + dec.name() + " error: " + describe(t.getException());
            // Don't poison the SHARED cache with errors — only the per-tab map, so a
            // second tab gets a fresh attempt.
            decompileCache.put(dec.name(), msg);
            decompiledView.setText(msg);
            decompiledLoaded = true;
            decompileStatus.setText(dec.name() + " - failed");
            STATUS_TRACKER.update(entry.internalName(),
                    new dev.share.bytecodelens.decompile.DecompileStatusTracker.Entry(
                            dev.share.bytecodelens.decompile.DecompileStatusTracker.Status.FAILED,
                            dec.name(), describe(t.getException()), 0));
        });
        runDaemon(t);
    }

    /**
     * Dynamic compare view: starts with two columns the user can swap engines in via
     * dropdowns, plus a {@code +} button to append another column and a {@code ×} to
     * drop one. Users rarely want to stare at all four engines side-by-side — two is
     * the common A/B comparison, three occasionally for tie-breaking.
     */
    private javafx.scene.Node buildCompareView() {
        SplitPane split = new SplitPane();
        split.getStyleClass().add("compare-split");
        // Bootstrap with CFR + Vineflower side-by-side (skip "Auto" pseudo-engine at
        // index 0 — in compare mode we want to see engine-specific output).
        ClassDecompiler first = REAL_DECOMPILERS.length > 0 ? REAL_DECOMPILERS[0] : DECOMPILERS[0];
        ClassDecompiler second = REAL_DECOMPILERS.length > 1 ? REAL_DECOMPILERS[1] : first;
        addCompareColumn(split, first);
        addCompareColumn(split, second);
        rebalanceCompareDividers(split);

        // "+" button floats at the bottom right — appends another column when clicked.
        Button addCol = new Button("+ Add column");
        addCol.getStyleClass().add("compare-add-btn");
        addCol.setOnAction(e -> {
            // Default to an engine not already present — otherwise fall back to first.
            ClassDecompiler pick = REAL_DECOMPILERS[0];
            for (ClassDecompiler d : REAL_DECOMPILERS) {
                boolean used = false;
                for (javafx.scene.Node item : split.getItems()) {
                    if (item instanceof VBox v && v.getUserData() instanceof CompareColumn cc
                            && cc.current() == d) {
                        used = true;
                        break;
                    }
                }
                if (!used) { pick = d; break; }
            }
            addCompareColumn(split, pick);
            rebalanceCompareDividers(split);
        });

        BorderPane root = new BorderPane();
        root.setCenter(split);
        HBox addBar = new HBox(addCol);
        addBar.setAlignment(Pos.CENTER_RIGHT);
        addBar.setPadding(new Insets(4, 8, 4, 8));
        addBar.getStyleClass().add("compare-add-bar");
        root.setBottom(addBar);
        return root;
    }

    /** Per-column state kept on VBox.userData so we can rebuild a column in-place. */
    private static final class CompareColumn {
        final ComboBox<ClassDecompiler> selector;
        final CodeView view;
        final Label status;
        CompareColumn(ComboBox<ClassDecompiler> s, CodeView v, Label st) {
            this.selector = s; this.view = v; this.status = st;
        }
        ClassDecompiler current() { return selector.getValue(); }
    }

    private void addCompareColumn(SplitPane split, ClassDecompiler initial) {
        ComboBox<ClassDecompiler> selector = new ComboBox<>();
        selector.getItems().addAll(REAL_DECOMPILERS);
        selector.setValue(initial);
        selector.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ClassDecompiler d) { return d == null ? "" : d.name(); }
            @Override public ClassDecompiler fromString(String s) { return null; }
        });
        selector.getStyleClass().add("compare-column-selector");

        CodeView view = new CodeView(JavaHighlighter::compute);
        Label status = new Label("Running...");
        status.getStyleClass().add("compare-column-status");

        Button closeBtn = new Button("\u00d7");
        closeBtn.getStyleClass().add("compare-column-close");
        closeBtn.setTooltip(new javafx.scene.control.Tooltip("Remove this column"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, selector, spacer, status, closeBtn);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(4, 8, 4, 8));
        head.getStyleClass().add("compare-column-head");

        VBox col = new VBox(head, view);
        VBox.setVgrow(view, Priority.ALWAYS);
        CompareColumn state = new CompareColumn(selector, view, status);
        col.setUserData(state);

        // Selector change re-runs decompile for this column only, keeping siblings intact.
        selector.setOnAction(e -> {
            ClassDecompiler chosen = selector.getValue();
            if (chosen != null) runCompareFor(state, chosen);
        });

        closeBtn.setOnAction(e -> {
            // Always keep at least one column on screen — that's the minimum useful state.
            if (split.getItems().size() <= 1) return;
            split.getItems().remove(col);
            rebalanceCompareDividers(split);
        });

        split.getItems().add(col);
        runCompareFor(state, initial);
    }

    private void runCompareFor(CompareColumn state, ClassDecompiler dec) {
        state.view.setText("// Decompiling with " + dec.name() + "...");
        state.status.setText("Running " + dec.name() + "...");
        long start = System.currentTimeMillis();
        Task<String> t = new Task<>() {
            @Override
            protected String call() {
                String cached = decompileCache.get(dec.name());
                if (cached == null) {
                    cached = SHARED_CACHE.get(entry.internalName(), dec.name(), entry.bytes());
                    if (cached != null) decompileCache.put(dec.name(), cached);
                }
                if (cached != null) return cached;
                try {
                    String out = dec.decompile(entry.internalName(), entry.bytes());
                    decompileCache.put(dec.name(), out);
                    SHARED_CACHE.put(entry.internalName(), dec.name(), entry.bytes(), out);
                    return out;
                } catch (Throwable ex) {
                    return "// " + dec.name() + " failed: " + describe(ex);
                }
            }
        };
        t.setOnSucceeded(e -> {
            state.view.setText(t.getValue());
            state.status.setText(dec.name() + " - " + (System.currentTimeMillis() - start) + "ms");
        });
        runDaemon(t);
    }

    /** Distribute divider positions evenly across the current column count. */
    private void rebalanceCompareDividers(SplitPane split) {
        int n = split.getItems().size();
        if (n <= 1) return;
        double even = 1.0 / n;
        double[] dividers = new double[n - 1];
        for (int i = 0; i < dividers.length; i++) dividers[i] = even * (i + 1);
        split.setDividerPositions(dividers);
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private void applyWhenReady(CodeView view, HighlightRequest req, java.util.function.BooleanSupplier ready) {
        if (ready.getAsBoolean()) {
            applyNow(view, req);
        } else {
            // Large classes (Minecraft launcher etc.) can take 5-10 seconds to decompile.
            // 100 × 150ms = 15 seconds of patience before we give up. If the user's still
            // waiting after that, they'd have noticed something is wrong anyway.
            applyWithPolling(view, req, ready, 100);
        }
    }

    private void applyWithPolling(CodeView view, HighlightRequest req, java.util.function.BooleanSupplier ready, int attemptsLeft) {
        if (attemptsLeft <= 0) return;
        if (ready.getAsBoolean()) {
            applyNow(view, req);
            return;
        }
        javafx.application.Platform.runLater(() -> {
            javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));
            pause.setOnFinished(e -> applyWithPolling(view, req, ready, attemptsLeft - 1));
            pause.play();
        });
    }

    private void applyNow(CodeView view, HighlightRequest req) {
        view.applyHighlight(req);
        if (req.line() > 0) {
            view.goToLine(req.line());
        } else {
            view.goToFirstMatch();
        }
    }

    private static FontIcon icon(String literal) {
        FontIcon i = new FontIcon(literal);
        i.setIconSize(13);
        return i;
    }

    private static void installFindShortcut(CodeView view, FindBar bar) {
        view.area().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F && e.isShortcutDown()) {
                bar.show(view.area().getSelectedText());
                e.consume();
            } else if (e.getCode() == KeyCode.F3) {
                if (!bar.isVisible()) bar.show(view.area().getSelectedText());
                else if (e.isShiftDown()) bar.prevMatch();
                else bar.nextMatch();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE && view.matchCount() > 0) {
                view.clearHighlight();
                e.consume();
            }
        });
    }

    private void loadBytecodeAsync(ClassEntry entry, BytecodePrinter printer, CodeView target) {
        Task<String> t = new Task<>() {
            @Override
            protected String call() {
                try {
                    return printer.print(entry.bytes());
                } catch (Throwable ex) {
                    return "// Bytecode generation failed: " + describe(ex);
                }
            }
        };
        t.setOnSucceeded(e -> {
            target.setText(t.getValue());
            bytecodeLoaded = true;
        });
        t.setOnFailed(e -> {
            target.setText("// Error: " + describe(t.getException()));
            bytecodeLoaded = true;
        });
        runDaemon(t);
    }

    private static String describe(Throwable ex) {
        if (ex == null) return "unknown error";
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) return ex.getClass().getSimpleName();
        return ex.getClass().getSimpleName() + ": " + msg;
    }

    private static void runDaemon(Task<?> t) {
        Thread th = new Thread(t, "class-editor-loader");
        th.setDaemon(true);
        th.start();
    }
}
