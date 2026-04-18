package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.keymap.Action;
import dev.share.bytecodelens.keymap.Actions;
import dev.share.bytecodelens.keymap.KeymapStore;
import dev.share.bytecodelens.settings.AppSettings;
import dev.share.bytecodelens.settings.AppSettingsStore;
import dev.share.bytecodelens.settings.SettingsSearchFilter;
import dev.share.bytecodelens.theme.SyntaxTheme;
import dev.share.bytecodelens.theme.ThemeManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Full-featured Settings window, replacing the older Preferences dialog.
 *
 * <p>Layout: search box at the top, sidebar of sections on the left, a per-section
 * content pane on the right, and an action bar (Restore / Cancel / Apply) at the
 * bottom. Every setting is backed by a field in the working {@link AppSettings}
 * draft; {@code Apply} commits the draft to {@link AppSettingsStore} and fires
 * listeners. {@code Cancel} (or window close) discards pending changes.</p>
 *
 * <p>Sections: Appearance, Editor, Decompiler, Xref, Search, Tree &amp; Nav, Hex Viewer,
 * JVM, Transformations, Keymap, Language, Paths, Advanced, About.</p>
 */
public final class SettingsStage {

    private final Stage stage = new Stage();
    private final AppSettingsStore store;
    /** Working copy — every widget binds to this. Apply writes it back to the store. */
    private AppSettings draft;
    private final KeymapStore keymapStore;

    /** One-shot notifier invoked on every Apply so MainController can refresh stuff the store itself doesn't touch (menus, accelerators). */
    private Runnable onApplied = () -> {};

    /**
     * Every section exposes itself through this descriptor so we can filter the sidebar
     * and page chrome from one data source.
     */
    private enum Section {
        APPEARANCE("Appearance", "mdi2p-palette-outline",
                "ui theme dark light syntax color font highlight caret"),
        EDITOR("Editor", "mdi2f-file-document-outline",
                "editor code font size tab whitespace wrap"),
        DECOMPILER("Decompiler", "mdi2c-code-braces",
                "decompile engine cfr vineflower procyon fallback cache capacity warmup timeout"),
        XREF("Xref & Usages", "mdi2t-target",
                "xref usages snippet preview overriders recursive callers string literal"),
        SEARCH("Search", "mdi2m-magnify",
                "search mode strings names bytecode regex numbers comments case"),
        TREE("Tree & Navigation", "mdi2f-file-tree",
                "tree preview double click badge sync editor"),
        HEX("Hex Viewer", "mdi2p-pound",
                "hex viewer row width offset base inspector endianness structure"),
        JVM("JVM Inspector", "mdi2c-cog-outline",
                "jvm attach refresh interval import classes"),
        TRANSFORMS("Transformations", "mdi2w-wrench-outline",
                "transformations passes deobfuscation defaults"),
        KEYMAP("Keymap", "mdi2k-keyboard-outline",
                "keymap shortcuts accelerator action preset"),
        LANGUAGE("Language", "mdi2t-translate",
                "language locale interface translation i18n"),
        PATHS("Paths & Limits", "mdi2f-folder-cog-outline",
                "agent dir recent pinned limit"),
        ADVANCED("Advanced", "mdi2c-cog-outline",
                "advanced log level java home threads parse"),
        ABOUT("About", "mdi2i-information-outline",
                "about version credits");

        final String label;
        final String iconLiteral;
        final String keywords;
        Section(String l, String i, String kw) { label = l; iconLiteral = i; keywords = kw; }
    }

    /** Registered when a section builds a filterable widget — used by the search box. */
    private final List<FilterableField> filterable = new ArrayList<>();
    private record FilterableField(Section section, Node row, String... keywords) {}

    private ListView<Section> sidebar;
    private BorderPane content;

    public SettingsStage(AppSettingsStore store, KeymapStore keymapStore) {
        this.store = store;
        this.keymapStore = keymapStore;
        this.draft = store.get();

        BorderPane root = new BorderPane();
        root.getStyleClass().addAll("preferences-root", "settings-root");

        // --- top: search -----------------------------------------------------
        TextField search = new TextField();
        search.setPromptText("Search settings (e.g. cache, snippet, theme)");
        search.getStyleClass().add("settings-search-field");
        search.textProperty().addListener((obs, o, n) -> applyFilter(n));

        HBox topBar = new HBox(8, new FontIcon("mdi2m-magnify"), search);
        HBox.setHgrow(search, Priority.ALWAYS);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10, 14, 8, 14));
        topBar.getStyleClass().add("settings-top-bar");

        // --- left: sidebar ---------------------------------------------------
        sidebar = new ListView<>(FXCollections.observableArrayList(Section.values()));
        sidebar.setCellFactory(v -> new SectionCell());
        sidebar.setPrefWidth(220);
        sidebar.getStyleClass().addAll("preferences-sidebar", "settings-sidebar");

        // --- center: section content -----------------------------------------
        content = new BorderPane();
        content.setPadding(new Insets(18, 22, 12, 22));
        content.getStyleClass().addAll("preferences-content", "settings-content");

        sidebar.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            filterable.clear();
            content.setCenter(buildSection(n));
            applyFilter(search.getText());
        });
        sidebar.getSelectionModel().selectFirst();

        // --- bottom: actions -------------------------------------------------
        Button restore = new Button("Restore All Defaults");
        restore.getStyleClass().add("settings-restore-btn");
        restore.setOnAction(e -> confirmRestore());
        Button apply = new Button("Apply");
        apply.getStyleClass().add("button-primary");
        apply.setOnAction(e -> commit());
        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(8, restore, spacer, close, apply);
        bottom.setPadding(new Insets(10, 16, 12, 16));
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.getStyleClass().add("preferences-footer");

        root.setTop(topBar);
        root.setLeft(sidebar);
        root.setCenter(content);
        root.setBottom(bottom);

        Scene scene = new Scene(root, 980, 640);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());

        // Inherit theme classes from current settings so secondary window matches host.
        boolean dark = draft.appearance.uiTheme == AppSettings.UiTheme.DARK;
        root.getStyleClass().add(dark ? "dark-theme" : "light-theme");
        if (draft.appearance.syntaxThemeId != null) {
            root.getStyleClass().add("syntax-" + draft.appearance.syntaxThemeId);
        }
        stage.setTitle("Settings");
        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(560);
        dev.share.bytecodelens.util.Icons.apply(stage);
    }

    public void show() { stage.show(); }
    public void showAndWait() { stage.showAndWait(); }
    public void setOnApplied(Runnable h) { this.onApplied = h == null ? () -> {} : h; }

    // ========================================================================
    // Commit / restore
    // ========================================================================

    private void commit() {
        store.update(draft);
        onApplied.run();
        // Refresh draft reference so subsequent edits build on persisted state.
        this.draft = store.get();
        rebuildCurrent();
    }

    private void confirmRestore() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Reset every setting to its default value? Keymap customisations are kept.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("Restore Defaults");
        a.setHeaderText("Restore all settings?");
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                store.restoreDefaults();
                onApplied.run();
                this.draft = store.get();
                rebuildCurrent();
            }
        });
    }

    private void rebuildCurrent() {
        Section s = sidebar.getSelectionModel().getSelectedItem();
        if (s != null) {
            filterable.clear();
            content.setCenter(buildSection(s));
        }
    }

    // ========================================================================
    // Section builders
    // ========================================================================

    private Region buildSection(Section s) {
        return switch (s) {
            case APPEARANCE -> buildAppearance();
            case EDITOR -> buildEditor();
            case DECOMPILER -> buildDecompiler();
            case XREF -> buildXref();
            case SEARCH -> buildSearch();
            case TREE -> buildTree();
            case HEX -> buildHex();
            case JVM -> buildJvm();
            case TRANSFORMS -> buildTransforms();
            case KEYMAP -> buildKeymap();
            case LANGUAGE -> buildLanguage();
            case PATHS -> buildPaths();
            case ADVANCED -> buildAdvanced();
            case ABOUT -> buildAbout();
        };
    }

    // ---- Appearance ---------------------------------------------------------
    private Region buildAppearance() {
        VBox box = sectionBox("Appearance");

        ChoiceBox<AppSettings.UiTheme> theme = enumChoice(AppSettings.UiTheme.class, draft.appearance.uiTheme);
        theme.valueProperty().addListener((o, a, b) -> draft.appearance.uiTheme = b);

        ChoiceBox<SyntaxTheme> syntax = new ChoiceBox<>(FXCollections.observableArrayList(ThemeManager.AVAILABLE));
        syntax.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(SyntaxTheme t) { return t == null ? "" : t.displayName(); }
            @Override public SyntaxTheme fromString(String s) { return null; }
        });
        for (SyntaxTheme st : ThemeManager.AVAILABLE) {
            if (st.id().equals(draft.appearance.syntaxThemeId)) { syntax.setValue(st); break; }
        }
        if (syntax.getValue() == null && !ThemeManager.AVAILABLE.isEmpty()) {
            syntax.setValue(ThemeManager.AVAILABLE.get(0));
        }
        syntax.valueProperty().addListener((o, a, b) -> {
            if (b != null) draft.appearance.syntaxThemeId = b.id();
        });

        TextField fontFamily = new TextField(draft.appearance.uiFontFamily);
        fontFamily.textProperty().addListener((o, a, b) -> draft.appearance.uiFontFamily = b);

        Slider uiFont = slider(8, 24, draft.appearance.uiFontSize, 2, 1);
        Label uiFontLabel = new Label(String.format(Locale.ROOT, "%.0f pt", draft.appearance.uiFontSize));
        uiFont.valueProperty().addListener((o, a, b) -> {
            draft.appearance.uiFontSize = b.doubleValue();
            uiFontLabel.setText(String.format(Locale.ROOT, "%.0f pt", b.doubleValue()));
        });

        CheckBox lineNums = checkbox("Show line numbers (not implemented yet)", draft.appearance.showLineNumbers,
                v -> draft.appearance.showLineNumbers = v);
        CheckBox caretLine = checkbox("Highlight the caret line", draft.appearance.showCaretLineHighlight,
                v -> draft.appearance.showCaretLineHighlight = v);
        CheckBox focusPulse = checkbox("Pulse the focused line after navigation jumps",
                draft.appearance.showFocusedLinePulse, v -> draft.appearance.showFocusedLinePulse = v);
        CheckBox hoverId = checkbox("Highlight identifier under mouse hover",
                draft.appearance.hoverHighlightIdentifiers, v -> draft.appearance.hoverHighlightIdentifiers = v);
        CheckBox ctrlHover = checkbox("Underline identifiers while Ctrl is held",
                draft.appearance.ctrlUnderlineOnHover, v -> draft.appearance.ctrlUnderlineOnHover = v);

        box.getChildren().addAll(
                register(Section.APPEARANCE, field("UI theme", theme), "ui theme dark light"),
                register(Section.APPEARANCE, field("Syntax color scheme", syntax), "syntax color scheme theme"),
                register(Section.APPEARANCE, field("UI font family", fontFamily), "font family"),
                register(Section.APPEARANCE, field("UI font size", new HBox(10, uiFont, uiFontLabel) {{
                    HBox.setHgrow(uiFont, Priority.ALWAYS);
                    setAlignment(Pos.CENTER_LEFT);
                }}), "font size"),
                new Separator(),
                register(Section.APPEARANCE, lineNums, "line numbers"),
                register(Section.APPEARANCE, caretLine, "caret line highlight"),
                register(Section.APPEARANCE, focusPulse, "focused line pulse jump"),
                register(Section.APPEARANCE, hoverId, "hover highlight identifier"),
                register(Section.APPEARANCE, ctrlHover, "ctrl underline hover"),
                note("UI theme swaps the whole application chrome (AtlantaFX Primer Light/Dark). "
                   + "Syntax color scheme affects only the code editor."));
        return wrap(box);
    }

    // ---- Editor -------------------------------------------------------------
    private Region buildEditor() {
        VBox box = sectionBox("Editor");

        Slider fontSlider = slider(8, 28, draft.editor.codeFontSize, 2, 1);
        Label fontValue = new Label(String.format(Locale.ROOT, "%.0f pt", draft.editor.codeFontSize));
        fontSlider.valueProperty().addListener((o, a, b) -> {
            draft.editor.codeFontSize = b.doubleValue();
            fontValue.setText(String.format(Locale.ROOT, "%.0f pt", b.doubleValue()));
        });

        Spinner<Integer> tabSize = intSpinner(1, 12, draft.editor.tabSize);
        tabSize.valueProperty().addListener((o, a, b) -> draft.editor.tabSize = b);

        CheckBox ws = checkbox("Show whitespace (spaces/tabs)", draft.editor.showWhitespace,
                v -> draft.editor.showWhitespace = v);
        CheckBox collapse = checkbox("Auto-collapse large comment blocks", draft.editor.autoCollapseComments,
                v -> draft.editor.autoCollapseComments = v);
        CheckBox wrap = checkbox("Wrap long lines", draft.editor.wrapLongLines,
                v -> draft.editor.wrapLongLines = v);

        HBox fontRow = new HBox(10, fontSlider, fontValue);
        HBox.setHgrow(fontSlider, Priority.ALWAYS);
        fontRow.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(
                register(Section.EDITOR, field("Code font size", fontRow), "code font size"),
                register(Section.EDITOR, field("Tab size", tabSize), "tab size indent"),
                new Separator(),
                register(Section.EDITOR, ws, "whitespace show"),
                register(Section.EDITOR, collapse, "collapse comments"),
                register(Section.EDITOR, wrap, "wrap long lines"),
                note("Ctrl+wheel / Ctrl+Plus / Ctrl+Minus adjust font size inline. Ctrl+0 resets."));
        return wrap(box);
    }

    // ---- Decompiler ---------------------------------------------------------
    private Region buildDecompiler() {
        VBox box = sectionBox("Decompiler");

        ChoiceBox<AppSettings.DecompilerEngine> engine = enumChoice(
                AppSettings.DecompilerEngine.class, draft.decompiler.defaultEngine);
        engine.valueProperty().addListener((o, a, b) -> draft.decompiler.defaultEngine = b);

        Spinner<Integer> timeout = intSpinner(1000, 120_000, draft.decompiler.perEngineTimeoutMs);
        timeout.valueProperty().addListener((o, a, b) -> draft.decompiler.perEngineTimeoutMs = b);

        CheckBox autoFallback = checkbox("Automatically fall back to ASM skeleton if every engine fails",
                draft.decompiler.autoFallbackOnFail, v -> draft.decompiler.autoFallbackOnFail = v);

        CheckBox cacheOn = checkbox("Enable decompile cache (cross-tab, cleared on jar close)",
                draft.decompiler.cacheEnabled, v -> draft.decompiler.cacheEnabled = v);
        Spinner<Integer> capacity = intSpinner(16, 4096, draft.decompiler.cacheCapacity);
        capacity.valueProperty().addListener((o, a, b) -> draft.decompiler.cacheCapacity = b);

        CheckBox warmup = checkbox("Pre-decompile neighbour classes in the background",
                draft.decompiler.backgroundWarmupEnabled, v -> draft.decompiler.backgroundWarmupEnabled = v);
        Spinner<Integer> warmupSize = intSpinner(1, 256, draft.decompiler.warmupNeighborhoodSize);
        warmupSize.valueProperty().addListener((o, a, b) -> draft.decompiler.warmupNeighborhoodSize = b);

        ChoiceBox<AppSettings.WarmupPriority> warmupPri = enumChoice(
                AppSettings.WarmupPriority.class, draft.decompiler.warmupThreadPriority);
        warmupPri.valueProperty().addListener((o, a, b) -> draft.decompiler.warmupThreadPriority = b);

        box.getChildren().addAll(
                register(Section.DECOMPILER, field("Default engine", engine), "default engine cfr vineflower procyon fallback auto"),
                register(Section.DECOMPILER, field("Per-engine timeout (ms)", timeout), "timeout ms"),
                register(Section.DECOMPILER, autoFallback, "fallback asm skeleton"),
                new Separator(),
                register(Section.DECOMPILER, cacheOn, "decompile cache enabled"),
                register(Section.DECOMPILER, field("Cache capacity (entries)", capacity), "cache capacity entries"),
                new Separator(),
                register(Section.DECOMPILER, warmup, "background warmup neighbour package"),
                register(Section.DECOMPILER, field("Warmup neighbourhood size", warmupSize), "warmup neighbourhood size"),
                register(Section.DECOMPILER, field("Warmup thread priority", warmupPri), "warmup thread priority"),
                note("Cache stores decompiled text keyed by (class, engine, bytes hash). Hot-reload invalidates automatically."));
        return wrap(box);
    }

    // ---- Xref ---------------------------------------------------------------
    private Region buildXref() {
        VBox box = sectionBox("Xref & Usages");

        CheckBox snippet = checkbox("Show code snippet preview under each call site",
                draft.xref.showCodeSnippetPreview, v -> draft.xref.showCodeSnippetPreview = v);
        CheckBox overriders = checkbox("Include overriders/implementers in Find Usages results",
                draft.xref.includeOverridersInUsages, v -> draft.xref.includeOverridersInUsages = v);
        Spinner<Integer> depth = intSpinner(1, 20, draft.xref.recursiveCallersMaxDepth);
        depth.valueProperty().addListener((o, a, b) -> draft.xref.recursiveCallersMaxDepth = b);
        Spinner<Integer> perNode = intSpinner(5, 500, draft.xref.recursiveCallersMaxPerNode);
        perNode.valueProperty().addListener((o, a, b) -> draft.xref.recursiveCallersMaxPerNode = b);
        CheckBox strIdx = checkbox("Build string literal index on jar load",
                draft.xref.stringLiteralIndexEnabled, v -> draft.xref.stringLiteralIndexEnabled = v);

        box.getChildren().addAll(
                register(Section.XREF, snippet, "snippet preview code call site"),
                register(Section.XREF, overriders, "overriders implementers find usages"),
                register(Section.XREF, field("Recursive callers max depth", depth), "recursive callers depth"),
                register(Section.XREF, field("Recursive callers max per node", perNode), "recursive callers per node"),
                new Separator(),
                register(Section.XREF, strIdx, "string literal index"));
        return wrap(box);
    }

    // ---- Search -------------------------------------------------------------
    private Region buildSearch() {
        VBox box = sectionBox("Search");

        ChoiceBox<AppSettings.SearchMode> mode = enumChoice(AppSettings.SearchMode.class, draft.search.defaultSearchMode);
        mode.valueProperty().addListener((o, a, b) -> draft.search.defaultSearchMode = b);
        Spinner<Integer> threshold = intSpinner(0, 100_000, draft.search.streamingThreshold);
        threshold.valueProperty().addListener((o, a, b) -> draft.search.streamingThreshold = b);
        CheckBox caseCb = checkbox("Case-sensitive search by default",
                draft.search.caseSensitiveDefault, v -> draft.search.caseSensitiveDefault = v);
        CheckBox persistExcl = checkbox("Persist excluded packages across jars",
                draft.search.persistExcludedPackagesAcrossJars, v -> draft.search.persistExcludedPackagesAcrossJars = v);

        box.getChildren().addAll(
                register(Section.SEARCH, field("Default search mode", mode), "default search mode"),
                register(Section.SEARCH, field("Streaming threshold (0 = all streaming)", threshold),
                        "streaming threshold"),
                register(Section.SEARCH, caseCb, "case sensitive"),
                register(Section.SEARCH, persistExcl, "persist excluded packages"));
        return wrap(box);
    }

    // ---- Tree ---------------------------------------------------------------
    private Region buildTree() {
        VBox box = sectionBox("Tree & Navigation");
        CheckBox badges = checkbox("Show decompile status badges on classes",
                draft.tree.showDecompileStatusBadges, v -> draft.tree.showDecompileStatusBadges = v);
        CheckBox expandable = checkbox("Expand classes to show methods/fields by default",
                draft.tree.expandableClassTreeDefault, v -> draft.tree.expandableClassTreeDefault = v);
        CheckBox preview = checkbox("Open preview tab on single click",
                draft.tree.openPreviewOnSingleClick, v -> draft.tree.openPreviewOnSingleClick = v);
        CheckBox promote = checkbox("Promote preview to pinned tab on double click",
                draft.tree.promoteOnDoubleClick, v -> draft.tree.promoteOnDoubleClick = v);
        CheckBox sync = checkbox("Sync tree selection with active editor automatically",
                draft.tree.syncWithEditorOnOpen, v -> draft.tree.syncWithEditorOnOpen = v);

        box.getChildren().addAll(
                register(Section.TREE, badges, "decompile status badges"),
                register(Section.TREE, expandable, "expandable class tree methods fields"),
                register(Section.TREE, preview, "preview single click"),
                register(Section.TREE, promote, "promote double click"),
                register(Section.TREE, sync, "sync tree editor"));
        return wrap(box);
    }

    // ---- Hex ----------------------------------------------------------------
    private Region buildHex() {
        VBox box = sectionBox("Hex Viewer");
        ChoiceBox<Integer> rowWidth = new ChoiceBox<>(FXCollections.observableArrayList(8, 16, 24, 32));
        rowWidth.setValue(draft.hex.defaultRowWidth);
        rowWidth.valueProperty().addListener((o, a, b) -> { if (b != null) draft.hex.defaultRowWidth = b; });
        ChoiceBox<AppSettings.HexBase> base = enumChoice(AppSettings.HexBase.class, draft.hex.offsetBase);
        base.valueProperty().addListener((o, a, b) -> draft.hex.offsetBase = b);
        CheckBox structTab = checkbox("Show Structure tab by default",
                draft.hex.showStructureTabByDefault, v -> draft.hex.showStructureTabByDefault = v);
        ChoiceBox<AppSettings.Endianness> endian = enumChoice(
                AppSettings.Endianness.class, draft.hex.defaultInspectorEndianness);
        endian.valueProperty().addListener((o, a, b) -> draft.hex.defaultInspectorEndianness = b);

        box.getChildren().addAll(
                register(Section.HEX, field("Default row width", rowWidth), "row width bytes"),
                register(Section.HEX, field("Offset base", base), "offset base hex dec decimal"),
                register(Section.HEX, field("Default inspector endianness", endian), "endianness little big"),
                register(Section.HEX, structTab, "structure tab"));
        return wrap(box);
    }

    // ---- JVM ----------------------------------------------------------------
    private Region buildJvm() {
        VBox box = sectionBox("JVM Inspector");
        Spinner<Integer> refresh = intSpinner(250, 60_000, draft.jvm.autoRefreshIntervalMs);
        refresh.valueProperty().addListener((o, a, b) -> draft.jvm.autoRefreshIntervalMs = b);
        CheckBox importCls = checkbox("Import classes for editing when attaching by default",
                draft.jvm.importClassesOnAttachByDefault,
                v -> draft.jvm.importClassesOnAttachByDefault = v);

        box.getChildren().addAll(
                register(Section.JVM, field("Auto-refresh interval (ms)", refresh), "auto refresh interval"),
                register(Section.JVM, importCls, "import classes attach"));
        return wrap(box);
    }

    // ---- Transformations ----------------------------------------------------
    private Region buildTransforms() {
        VBox box = sectionBox("Transformations");
        Label sub = new Label("Passes enabled by default when you open Run Transformations:");
        sub.getStyleClass().add("preferences-note");

        // All known pass IDs (keep in sync with MainController.onRunTransformations).
        String[] ids = {
                "illegal-name-mapping", "static-value-inlining",
                "dead-code-removal", "unreachable-after-terminator",
                "opaque-predicate-simplification", "kotlin-name-restoration",
                "call-result-inlining", "enum-name-restoration",
                "stack-frame-removal", "source-name-restoration",
                "kotlin-data-class-restoration",
                "strip-code-on-field", "remove-illegal-annotations"
        };
        VBox checks = new VBox(6);
        for (String id : ids) {
            CheckBox cb = new CheckBox(id);
            cb.setSelected(draft.transformations.defaultSelectedPasses.contains(id));
            cb.selectedProperty().addListener((o, a, b) -> {
                if (b) draft.transformations.defaultSelectedPasses.add(id);
                else draft.transformations.defaultSelectedPasses.remove(id);
            });
            checks.getChildren().add(register(Section.TRANSFORMS, cb, id));
        }
        box.getChildren().addAll(sub, checks);
        return wrap(box);
    }

    // ---- Keymap -------------------------------------------------------------
    private TableView<KeymapRow> keymapTable;

    private Region buildKeymap() {
        VBox box = sectionBox("Keymap");

        ChoiceBox<KeymapStore.Preset> preset = new ChoiceBox<>(
                FXCollections.observableArrayList(KeymapStore.Preset.values()));
        preset.setValue(KeymapStore.Preset.DEFAULT);
        Button applyPreset = new Button("Apply Preset");
        applyPreset.setOnAction(e -> {
            KeymapStore.Preset p = preset.getValue();
            if (p != null) {
                keymapStore.applyPreset(p);
                refreshKeymapTable();
            }
        });
        HBox presetRow = new HBox(8, preset, applyPreset);
        presetRow.setAlignment(Pos.CENTER_LEFT);

        keymapTable = new TableView<>();
        TableColumn<KeymapRow, String> action = new TableColumn<>("Action");
        action.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().action.label()));
        action.setPrefWidth(220);
        TableColumn<KeymapRow, String> cat = new TableColumn<>("Category");
        cat.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().action.category()));
        cat.setPrefWidth(100);
        TableColumn<KeymapRow, String> accel = new TableColumn<>("Shortcut");
        accel.setCellValueFactory(c -> c.getValue().accel);
        accel.setPrefWidth(170);
        TableColumn<KeymapRow, Void> actions = new TableColumn<>("");
        actions.setCellFactory(col -> new KeymapEditCell());
        actions.setPrefWidth(90);
        keymapTable.getColumns().setAll(List.of(action, cat, accel, actions));
        refreshKeymapTable();
        VBox.setVgrow(keymapTable, Priority.ALWAYS);

        box.getChildren().addAll(
                register(Section.KEYMAP, field("Preset", presetRow), "keymap preset"),
                keymapTable);
        return wrap(box);
    }

    private void refreshKeymapTable() {
        if (keymapTable == null) return;
        List<KeymapRow> rows = new ArrayList<>();
        for (Action a : Actions.ALL) rows.add(new KeymapRow(a, keymapStore.get(a)));
        keymapTable.setItems(FXCollections.observableArrayList(rows));
    }

    private static final class KeymapRow {
        final Action action;
        final SimpleStringProperty accel;
        KeymapRow(Action a, String s) {
            this.action = a;
            this.accel = new SimpleStringProperty(s == null ? "" : s);
        }
    }

    private final class KeymapEditCell extends javafx.scene.control.TableCell<KeymapRow, Void> {
        private final Button edit = new Button("Edit");
        private final Button clear = new Button("\u00d7");
        private final HBox bar = new HBox(4, edit, clear);

        KeymapEditCell() {
            edit.getStyleClass().add("button-small");
            clear.getStyleClass().add("button-small");
            edit.setOnAction(e -> {
                KeymapRow r = getTableView().getItems().get(getIndex());
                new KeymapRecorder(r.action, keymapStore, SettingsStage.this::refreshKeymapTable, stage).showAndWait();
            });
            clear.setOnAction(e -> {
                KeymapRow r = getTableView().getItems().get(getIndex());
                keymapStore.clear(r.action);
                refreshKeymapTable();
            });
        }
        @Override
        protected void updateItem(Void v, boolean empty) {
            super.updateItem(v, empty);
            setGraphic(empty ? null : bar);
            setText(null);
        }
    }

    // ---- Language -----------------------------------------------------------
    private Region buildLanguage() {
        VBox box = sectionBox("Language");
        // v0.3 release ships English only. The dropdown is kept as a read-only field so
        // users see the current state and know the feature is scoped — other locales
        // will return once translation bundles are complete.
        TextField languageLabel = new TextField("English");
        languageLabel.setEditable(false);
        languageLabel.setFocusTraversable(false);
        languageLabel.getStyleClass().add("settings-readonly-field");

        box.getChildren().addAll(
                register(Section.LANGUAGE, field("Interface language", languageLabel),
                        "locale language interface english"),
                note("Additional languages are planned for a future release. The RU bundle "
                   + "that ships with the jar is kept as a reference for translators but is "
                   + "not selectable yet."));
        return wrap(box);
    }

    // ---- Paths --------------------------------------------------------------
    private Region buildPaths() {
        VBox box = sectionBox("Paths & Limits");
        TextField agentDir = new TextField(draft.paths.agentDir);
        agentDir.setPromptText("Leave empty for ~/.bytecodelens/agents/");
        agentDir.textProperty().addListener((o, a, b) -> draft.paths.agentDir = b);
        Spinner<Integer> recent = intSpinner(1, 100, draft.paths.recentLimit);
        recent.valueProperty().addListener((o, a, b) -> draft.paths.recentLimit = b);
        Spinner<Integer> pinned = intSpinner(1, 50, draft.paths.pinnedLimit);
        pinned.valueProperty().addListener((o, a, b) -> draft.paths.pinnedLimit = b);

        box.getChildren().addAll(
                register(Section.PATHS, field("Agent directory override", agentDir), "agent directory"),
                register(Section.PATHS, field("Recent files limit", recent), "recent files limit"),
                register(Section.PATHS, field("Pinned projects limit", pinned), "pinned projects limit"));
        return wrap(box);
    }

    // ---- Advanced -----------------------------------------------------------
    private Region buildAdvanced() {
        VBox box = sectionBox("Advanced");
        ChoiceBox<AppSettings.LogLevel> lvl = enumChoice(AppSettings.LogLevel.class, draft.advanced.gcLogLevel);
        lvl.valueProperty().addListener((o, a, b) -> draft.advanced.gcLogLevel = b);
        TextField javaHome = new TextField(draft.advanced.javaHomeOverride);
        javaHome.setPromptText("Leave empty to use JAVA_HOME");
        javaHome.textProperty().addListener((o, a, b) -> draft.advanced.javaHomeOverride = b);
        Spinner<Integer> threads = intSpinner(1, 128, draft.advanced.maxClassParseThreads);
        threads.valueProperty().addListener((o, a, b) -> draft.advanced.maxClassParseThreads = b);

        box.getChildren().addAll(
                register(Section.ADVANCED, field("Log level", lvl), "log level"),
                register(Section.ADVANCED, field("Java home override", javaHome), "java home override"),
                register(Section.ADVANCED, field("Max class parse threads", threads), "parse threads"));
        return wrap(box);
    }

    // ---- About --------------------------------------------------------------
    private Region buildAbout() {
        VBox box = sectionBox("About");
        // Bundle logo — same artwork as the window icon. Matches the start-page style.
        javafx.scene.Node logo;
        var stream = getClass().getResourceAsStream("/icons/app-512.png");
        if (stream != null) {
            javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(
                    new javafx.scene.image.Image(stream, 72, 72, true, true));
            iv.setSmooth(true);
            logo = iv;
        } else {
            logo = new FontIcon("mdi2c-code-braces");
        }

        Label title = new Label("BytecodeLens");
        title.getStyleClass().add("preferences-about-title");
        Label version = new Label("Version 1.0.0");
        version.getStyleClass().add("preferences-note");
        Label tagline = new Label("The Java RE cockpit \u2014 decompile, attach, diff, patch.");
        tagline.setWrapText(true);
        Label license = new Label("Licensed under Apache 2.0.");
        license.getStyleClass().add("preferences-note");
        Label settings = new Label("Settings file: " + store.path());
        settings.setWrapText(true);
        settings.getStyleClass().add("preferences-note");

        VBox text = new VBox(4, title, version, tagline, license);
        HBox hero = new HBox(16, logo, text);
        hero.setAlignment(Pos.CENTER_LEFT);

        Label credits = new Label("Built on CFR \u00b7 Vineflower \u00b7 Procyon \u00b7 ASM \u00b7 "
                + "AtlantaFX \u00b7 RichTextFX \u00b7 Ikonli.");
        credits.setWrapText(true);
        credits.getStyleClass().add("preferences-note");

        box.getChildren().addAll(hero, new Separator(), credits, settings);
        return box;
    }

    // ========================================================================
    // Filter
    // ========================================================================

    private void applyFilter(String q) {
        // Sidebar filtering — hide sections with no matching keywords.
        sidebar.setCellFactory(v -> new SectionCell() {
            @Override
            protected void updateItem(Section s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) return;
                boolean hit = SettingsSearchFilter.matches(q, s.label, s.keywords);
                setVisible(hit);
                setManaged(hit);
            }
        });
        // Content pane: fade out non-matching rows.
        for (FilterableField f : filterable) {
            boolean hit = SettingsSearchFilter.matches(q, f.keywords);
            hit = hit || SettingsSearchFilter.matches(q, f.section.label, f.section.keywords);
            f.row.setVisible(hit);
            f.row.setManaged(hit);
        }
    }

    private <T extends Node> T register(Section s, T row, String... keywords) {
        filterable.add(new FilterableField(s, row, keywords));
        return row;
    }

    // ========================================================================
    // Tiny widget helpers
    // ========================================================================

    private static VBox sectionBox(String title) {
        Label header = new Label(title);
        header.getStyleClass().add("preferences-section-title");
        VBox box = new VBox(10, header);
        return box;
    }

    private static ScrollPane wrap(Region content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("preferences-scroll");
        return sp;
    }

    private static HBox field(String labelText, Node widget) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("preferences-field-label");
        lbl.setMinWidth(200);
        HBox row = new HBox(12, lbl, widget);
        row.setAlignment(Pos.CENTER_LEFT);
        if (widget instanceof Region r) HBox.setHgrow(r, Priority.ALWAYS);
        return row;
    }

    private static Label note(String t) {
        Label n = new Label(t);
        n.setWrapText(true);
        n.getStyleClass().add("preferences-note");
        return n;
    }

    private static CheckBox checkbox(String text, boolean initial, Consumer<Boolean> onChange) {
        CheckBox cb = new CheckBox(text);
        cb.setSelected(initial);
        cb.selectedProperty().addListener((o, a, b) -> onChange.accept(b));
        return cb;
    }

    private static Slider slider(double min, double max, double value, int major, int minor) {
        Slider s = new Slider(min, max, value);
        s.setMajorTickUnit(major);
        s.setMinorTickCount(minor);
        s.setShowTickLabels(true);
        s.setShowTickMarks(true);
        return s;
    }

    private static Spinner<Integer> intSpinner(int min, int max, int value) {
        Spinner<Integer> sp = new Spinner<>(min, max, value);
        sp.setEditable(true);
        sp.setPrefWidth(120);
        return sp;
    }

    private static <E extends Enum<E>> ChoiceBox<E> enumChoice(Class<E> cls, E current) {
        ChoiceBox<E> cb = new ChoiceBox<>(FXCollections.observableArrayList(cls.getEnumConstants()));
        cb.setValue(current);
        return cb;
    }

    private static class SectionCell extends ListCell<Section> {
        @Override
        protected void updateItem(Section s, boolean empty) {
            super.updateItem(s, empty);
            if (empty || s == null) {
                setText(null); setGraphic(null);
                setVisible(true); setManaged(true);
                return;
            }
            FontIcon icon = new FontIcon(s.iconLiteral);
            icon.setIconSize(16);
            setGraphic(icon);
            setText(s.label);
        }
    }

    // ========================================================================
    // Keymap recorder popup (reused from the old Preferences UI)
    // ========================================================================

    private static final class KeymapRecorder {
        private final Stage popup = new Stage();
        private final TextField field = new TextField();
        private final Action action;
        private final KeymapStore store;
        private final Runnable onSaved;

        KeymapRecorder(Action a, KeymapStore s, Runnable onSaved, Stage owner) {
            this.action = a;
            this.store = s;
            this.onSaved = onSaved;
            String existing = store.get(a);
            if (existing != null) field.setText(existing);
            field.setPromptText("Press the desired shortcut, or type it");
            field.addEventFilter(KeyEvent.KEY_PRESSED, this::captureKey);

            Button save = new Button("Save");
            save.setDefaultButton(true);
            save.setOnAction(e -> commit());
            Button cancel = new Button("Cancel");
            cancel.setCancelButton(true);
            cancel.setOnAction(e -> popup.close());
            HBox buttons = new HBox(6, save, cancel);
            buttons.setAlignment(Pos.CENTER_RIGHT);

            VBox root = new VBox(10, new Label("Shortcut for: " + a.label()), field, buttons);
            root.setPadding(new Insets(14));
            Scene scene = new Scene(root, 360, 140);
            if (owner != null && owner.getScene() != null) {
                scene.getStylesheets().addAll(owner.getScene().getStylesheets());
                if (owner.getScene().getRoot() != null) {
                    root.getStyleClass().addAll(owner.getScene().getRoot().getStyleClass());
                }
            }
            popup.setScene(scene);
            popup.setTitle("Edit Shortcut");
            popup.initModality(javafx.stage.Modality.WINDOW_MODAL);
            if (owner != null) popup.initOwner(owner);
            dev.share.bytecodelens.util.Icons.apply(popup);
        }

        void showAndWait() { popup.showAndWait(); }

        private void captureKey(KeyEvent ev) {
            var code = ev.getCode();
            if (code == javafx.scene.input.KeyCode.ESCAPE) { popup.close(); ev.consume(); return; }
            if (code.isModifierKey()) return;
            StringBuilder sb = new StringBuilder();
            if (ev.isControlDown()) sb.append("Ctrl+");
            if (ev.isMetaDown()) sb.append("Meta+");
            if (ev.isAltDown()) sb.append("Alt+");
            if (ev.isShiftDown()) sb.append("Shift+");
            sb.append(code.getName());
            field.setText(sb.toString());
            ev.consume();
        }

        private void commit() {
            String v = field.getText() == null ? "" : field.getText().trim();
            if (!v.isEmpty()) {
                try { javafx.scene.input.KeyCombination.valueOf(v); }
                catch (Exception ex) { return; }
            }
            store.set(action, v.isEmpty() ? null : v);
            onSaved.run();
            popup.close();
        }
    }

    /** Suppress Tooltip import warning — scheduled for use in subsequent polish pass. */
    @SuppressWarnings("unused")
    private static Tooltip tooltipPlaceholder() { return new Tooltip(); }
}
