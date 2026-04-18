package dev.share.bytecodelens.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.ConstantPoolEntry;
import dev.share.bytecodelens.model.FieldEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.model.MethodEntry;
import dev.share.bytecodelens.model.JarResource;
import dev.share.bytecodelens.pattern.eval.PatternResult;
import dev.share.bytecodelens.search.SearchIndex;
import dev.share.bytecodelens.crypto.DecryptionResult;
import dev.share.bytecodelens.crypto.StringDecryptor;
import dev.share.bytecodelens.diff.JarDiffResult;
import dev.share.bytecodelens.diff.JarDiffer;
import dev.share.bytecodelens.hierarchy.HierarchyIndex;
import dev.share.bytecodelens.usage.CallSite;
import dev.share.bytecodelens.usage.UsageIndex;
import dev.share.bytecodelens.usage.UsageTarget;
import dev.share.bytecodelens.decompile.CfrDecompiler;
import dev.share.bytecodelens.decompile.ClassDecompiler;
import dev.share.bytecodelens.decompile.ProcyonDecompiler;
import dev.share.bytecodelens.decompile.VineflowerDecompiler;
import dev.share.bytecodelens.service.BytecodePrinter;
import dev.share.bytecodelens.service.ClassAnalyzer;
import dev.share.bytecodelens.service.ConstantPoolReader;
import dev.share.bytecodelens.service.Decompiler;
import dev.share.bytecodelens.service.ExportService;
import dev.share.bytecodelens.service.JarLoader;
import dev.share.bytecodelens.service.NestedJarExtractor;
import dev.share.bytecodelens.service.ObfuscatorDetector;
import dev.share.bytecodelens.service.ResourceReader;
import dev.share.bytecodelens.ui.views.ClassEditorTab;
import dev.share.bytecodelens.ui.views.HighlightRequest;
import dev.share.bytecodelens.ui.views.ResourceEditorTab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import dev.share.bytecodelens.util.AccessFlags;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @FXML private TreeView<TreeNode> classTree;
    @FXML private TabPane editorTabs;
    @FXML private TabPane bottomTabs;
    @FXML private VBox classInfoBox;
    @FXML private VBox dataInspectorBox;
    @FXML private TextField searchField;
    @FXML private Label statusLeft;
    @FXML private Label statusRight;
    @FXML private ProgressBar progressBar;
    // Status bar badges — wrapped HBox + inner label for each. Toggled via
    // setVisible / setManaged so the status bar reflows when a badge disappears.
    @FXML private javafx.scene.layout.HBox jarBadge;
    @FXML private Label jarBadgeLabel;
    @FXML private javafx.scene.layout.HBox mappingBadge;
    @FXML private Label mappingBadgeLabel;
    @FXML private javafx.scene.layout.HBox cacheBadge;
    @FXML private Label cacheBadgeLabel;
    @FXML private ToggleButton themeToggle;
    @FXML private FontIcon themeIcon;
    @FXML private SplitPane mainSplit;
    /** Vertical SplitPane between editor tabs and bottom inspector tabs. */
    @FXML private SplitPane centerSplit;
    @FXML private Button openButton;
    @FXML private Button backButton;

    @FXML private TableView<ConstantPoolEntry> constantPoolTable;
    @FXML private TableColumn<ConstantPoolEntry, Number> cpIndexCol;
    @FXML private TableColumn<ConstantPoolEntry, String> cpTypeCol;
    @FXML private TableColumn<ConstantPoolEntry, String> cpValueCol;

    @FXML private TableView<MethodEntry> methodsTable;
    @FXML private TableColumn<MethodEntry, String> mNameCol;
    @FXML private TableColumn<MethodEntry, String> mDescCol;
    @FXML private TableColumn<MethodEntry, String> mAccessCol;
    @FXML private TableColumn<MethodEntry, Number> mInsnCol;

    @FXML private TableView<FieldEntry> fieldsTable;
    @FXML private TableColumn<FieldEntry, String> fNameCol;
    @FXML private TableColumn<FieldEntry, String> fDescCol;
    @FXML private TableColumn<FieldEntry, String> fAccessCol;
    @FXML private TableColumn<FieldEntry, String> fValueCol;

    @FXML private javafx.scene.control.Tab patternsTab;
    @FXML private javafx.scene.control.Tab usagesTab;
    @FXML private javafx.scene.control.Tab hierarchyTab;
    @FXML private javafx.scene.control.Tab decryptTab;
    private PatternPanel patternPanel;
    private UsagePanel usagePanel;
    private UsageIndex usageIndex;
    private dev.share.bytecodelens.usage.StringLiteralIndex stringLiteralIndex;
    private HierarchyPanel hierarchyPanel;
    private HierarchyIndex hierarchyIndex;
    private StringDecryptionPanel decryptPanel;
    private DecryptionResult lastDecryptionResult;

    private final JarLoader jarLoader = new JarLoader();
    private final ClassAnalyzer analyzer = new ClassAnalyzer();
    private final BytecodePrinter bytecodePrinter = new BytecodePrinter();
    private final Decompiler decompiler = new Decompiler();
    private final ConstantPoolReader cpReader = new ConstantPoolReader();
    private final ObfuscatorDetector obfuscatorDetector = new ObfuscatorDetector();
    private final ResourceReader resourceReader = new ResourceReader();
    private final NestedJarExtractor nestedExtractor = new NestedJarExtractor();
    private final ExportService exportService = new ExportService();
    private final dev.share.bytecodelens.service.HookSnippetGenerator hookSnippetGenerator
            = new dev.share.bytecodelens.service.HookSnippetGenerator();
    private final dev.share.bytecodelens.comments.CommentStore commentStore
            = new dev.share.bytecodelens.comments.CommentStore();
    private final dev.share.bytecodelens.service.RecentFiles recentFiles = new dev.share.bytecodelens.service.RecentFiles();
    private final dev.share.bytecodelens.service.PinnedFiles pinnedFiles = new dev.share.bytecodelens.service.PinnedFiles();
    /** User-customisable keybindings, loaded on startup and live-editable via Keymap dialog. */
    private final dev.share.bytecodelens.keymap.KeymapStore keymap = new dev.share.bytecodelens.keymap.KeymapStore();
    /** Start page shown over the editor tab pane when no jar is loaded. */
    private dev.share.bytecodelens.ui.views.StartPage startPage;
    /**
     * User-chosen packages to exclude from search. Mutated via the tree context menu and
     * through the overlay's clear-all. Persisted in {@link WorkspaceState#excludedPackages}.
     */
    private final java.util.List<String> excludedPackages = new java.util.ArrayList<>();
    /**
     * Shared populator for the tree's context menu. Built once in setupTree() and reused
     * by both the MOUSE_PRESSED pre-show path and the setOnShowing fallback.
     */
    private java.util.function.Consumer<TreeNode> treeMenuPopulator = n -> {};

    @FXML private javafx.scene.control.Menu recentFilesMenu;
    private dev.share.bytecodelens.theme.SyntaxTheme activeSyntaxTheme = dev.share.bytecodelens.theme.SyntaxTheme.PRIMER_LIGHT;

    private final java.util.Deque<String> navBackStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<String> navForwardStack = new java.util.ArrayDeque<>();
    private String navCurrent;
    private boolean navSuppress;

    public record JarStackEntry(java.nio.file.Path path, String displayLabel) {
    }

    private final java.util.Deque<JarStackEntry> jarStack = new java.util.ArrayDeque<>();

    private LoadedJar currentJar;
    /**
     * Background warmer for {@link ClassEditorTab#sharedCache()} — pre-decompiles
     * neighbours of the most recently opened class on a single low-priority daemon
     * thread. Re-created per jar (old one is shutdown in {@link #onJarLoaded}).
     */
    private dev.share.bytecodelens.decompile.BackgroundDecompiler bgDecompiler;
    /** Snapshot of the jar before mapping was applied; null if no mapping is active. */
    private LoadedJar originalJarBeforeMapping;
    private String activeMappingLabel;
    /** Last applied mapping model — kept so it can be exported to a different format. */
    private dev.share.bytecodelens.mapping.MappingModel activeMappingModel;
    private final Map<String, ClassEditorTab> openTabs = new HashMap<>();
    private final Map<String, ResourceEditorTab> openResourceTabs = new HashMap<>();
    private SearchOverlay searchOverlay;
    private SearchIndex searchIndex;
    private final Map<String, ClassEntry> classByFqn = new HashMap<>();
    /** Key format: "__v<N>__/fqn" — resolves MR-jar versioned classes without colliding with root. */
    private final Map<String, ClassEntry> versionedClassByKey = new HashMap<>();

    /**
     * The single reusable "preview" tab: opened on tree selection (including keyboard arrows)
     * and overwritten when another class is previewed. Double-click in the tree promotes it
     * to a regular pinned tab. Null when no preview is active.
     */
    private ClassEditorTab previewTab;
    private String previewTabFqn;
    private TreeItem<TreeNode> allClassesRoot;
    private boolean darkTheme = false;

    @FXML
    public void initialize() {
        setupTree();
        setupTables();
        setupSearch();
        setupDragAndDrop();
        themeToggle.setSelected(true);
        updateThemeIcon();
        Platform.runLater(this::applyThemeClass);
        Platform.runLater(this::registerGlobalShortcuts);
        setupPatternPanel();
        setupUsagePanel();
        setupHierarchyPanel();
        setupDecryptPanel();
        setupEditorTabsContextMenu();
        rebuildRecentMenu();
        rebuildSyntaxThemeMenu();
        updateStatus("Open a .jar, .war, .class or .zip file to begin", "");
        fillDataInspectorEmpty();
        // Subscribe to settings changes so any Apply in the Settings window ripples
        // through the live UI (font size, snippet provider, tree badges, etc.) without
        // requiring a restart.
        dev.share.bytecodelens.settings.AppSettingsStore.getInstance()
                .addListener(this::onAppSettingsChanged);
        // Apply the persisted settings once at startup so the first paint matches the
        // user's saved preferences.
        Platform.runLater(() -> onAppSettingsChanged(
                dev.share.bytecodelens.settings.AppSettingsStore.getInstance().get()));
        // Show the Start Page once the scene has been attached — editorTabs.getScene()
        // returns null during initialize(), so we defer.
        javafx.application.Platform.runLater(this::installStartPage);
        // Initial paint of status bar badges + kick off a lightweight periodic refresh
        // for cache stats (runs on JavaFX thread via Timeline; stops when the window closes).
        javafx.application.Platform.runLater(() -> {
            refreshJarBadge();
            refreshMappingBadge();
            refreshCacheBadge();
        });
        javafx.animation.Timeline cacheTicker = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(3),
                        e -> refreshCacheBadge()));
        cacheTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
        cacheTicker.play();
    }

    /**
     * Single entry point that reconciles the live UI with a fresh {@link dev.share.bytecodelens.settings.AppSettings}
     * snapshot. Called at startup and whenever the Settings window fires an Apply.
     * Keep work here idempotent — repeated calls with the same snapshot must be no-ops.
     */
    private void onAppSettingsChanged(dev.share.bytecodelens.settings.AppSettings s) {
        try {
            // Code font size — shared across every CodeView.
            double fs = s.editor.codeFontSize;
            if (fs > 0) dev.share.bytecodelens.ui.views.CodeView.setSharedFontSize(fs);

            // UI theme (dark / light). SYSTEM falls back to current darkTheme flag.
            boolean wantDark = switch (s.appearance.uiTheme) {
                case DARK -> true;
                case LIGHT -> false;
                case SYSTEM -> darkTheme;
            };
            if (wantDark != darkTheme) onToggleTheme();

            // Syntax theme by id — no-op when already active.
            if (s.appearance.syntaxThemeId != null && activeSyntaxTheme != null
                    && !s.appearance.syntaxThemeId.equals(activeSyntaxTheme.id())) {
                var byId = dev.share.bytecodelens.theme.ThemeManager.byId(s.appearance.syntaxThemeId);
                if (byId != null) applySyntaxTheme(byId);
            }

            // Locale switching is disabled in v0.3 — the app ships English only.
            // {@link dev.share.bytecodelens.i18n.Lang#setLocale} is a no-op; we don't
            // bother calling it here.

            // Tree badge rendering + xref snippet provider toggle — these are cheap
            // read-on-render, so repaint the tree and rebuild the snippet lambda.
            if (classTree != null) classTree.refresh();
            if (usagePanel != null) {
                if (s.xref.showCodeSnippetPreview) {
                    usagePanel.setSnippetProvider(cs -> {
                        String internal = cs.inClassFqn();
                        int line = cs.lineNumber();
                        if (line <= 0 || internal == null) return null;
                        ClassEntry ce = classByFqn.get(internal.replace('/', '.'));
                        if (ce == null) return null;
                        String src = ClassEditorTab.sharedCache().get(ce.internalName(), "Auto", ce.bytes());
                        if (src == null) src = ClassEditorTab.sharedCache().get(ce.internalName(), "CFR", ce.bytes());
                        if (src == null) return null;
                        return dev.share.bytecodelens.usage.XrefSnippetExtractor.extract(src, line);
                    });
                } else {
                    usagePanel.setSnippetProvider(cs -> null);
                }
            }

            // Background warmup: shut down if disabled; otherwise re-create on next openClass.
            if (!s.decompiler.backgroundWarmupEnabled && bgDecompiler != null) {
                bgDecompiler.shutdown();
                bgDecompiler = null;
            }
        } catch (Throwable t) {
            log.warn("onAppSettingsChanged failed: {}", t.toString());
        }
    }

    /**
     * Mount the Start Page as a closable "Welcome" tab in the editor TabPane when no
     * jar is loaded. Living inside the TabPane avoids any Parent-wrap surgery and
     * guarantees full width/height — the TabPane already gives its children the entire
     * available region.
     */
    private void installStartPage() {
        if (startPage != null) return;
        startPage = new dev.share.bytecodelens.ui.views.StartPage(recentFiles, pinnedFiles);
        startPage.setOnOpen((java.util.function.Consumer<java.nio.file.Path>) p -> openJarFromPath(p));
        startPage.setOnBrowse(() -> {
            try { onOpen(); } catch (Exception ignored) {}
        });
        updateStartPageVisibility();
    }

    private javafx.scene.control.Tab startPageTab;

    /** Show the Start Page tab when no jar is loaded; remove it otherwise. */
    private void updateStartPageVisibility() {
        if (startPage == null) return;
        // editorTabs.getTabs().clear() on jar-close disposes our Tab node — detect that
        // so we recreate instead of trying to re-select a dead Tab reference.
        if (startPageTab != null && !editorTabs.getTabs().contains(startPageTab)) {
            startPageTab = null;
            // Detach startPage's parent too — a Node can't be re-parented if it still
            // has an ex-parent.
            if (startPage.getParent() != null) {
                startPage.setVisible(false);  // force parent detach (content is reparented by new Tab)
            }
        }
        if (currentJar == null) {
            if (startPageTab == null) {
                startPage.setVisible(true);
                startPageTab = new javafx.scene.control.Tab("Welcome", startPage);
                startPageTab.setClosable(false);
                startPageTab.setGraphic(
                        new org.kordamp.ikonli.javafx.FontIcon("mdi2h-home-outline"));
                editorTabs.getTabs().add(0, startPageTab);
            }
            editorTabs.getSelectionModel().select(startPageTab);
            startPage.refresh();
        } else {
            if (startPageTab != null) {
                editorTabs.getTabs().remove(startPageTab);
                startPageTab = null;
            }
        }
    }

    /** Bridge for the Start Page and Recent menu — open a jar given its path. */
    private void openJarFromPath(java.nio.file.Path path) {
        if (path == null) return;
        try {
            javafx.concurrent.Task<dev.share.bytecodelens.model.LoadedJar> task = new javafx.concurrent.Task<>() {
                @Override
                protected dev.share.bytecodelens.model.LoadedJar call() throws Exception {
                    return new dev.share.bytecodelens.service.JarLoader().load(path, p -> {});
                }
            };
            task.setOnSucceeded(e -> {
                onJarLoaded(task.getValue());
                recentFiles.add(path);
                rebuildRecentMenu();
            });
            task.setOnFailed(e -> showError("Open failed",
                    task.getException() == null ? "unknown" : task.getException().getMessage()));
            Thread t = new Thread(task, "jar-opener");
            t.setDaemon(true);
            t.start();
        } catch (Exception ex) {
            showError("Open failed", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private void setupTree() {
        allClassesRoot = new TreeItem<>(new TreeNode("Classes", null, NodeKind.ROOT));
        allClassesRoot.setExpanded(true);
        classTree.setRoot(allClassesRoot);
        classTree.setShowRoot(false);
        // Single shared ContextMenu instance — set up in detail further below; declared up
        // here so the cell factory closure can see it. Each cell's setContextMenu points
        // at this same object, avoiding per-cell menu rebuild cost.
        final javafx.scene.control.ContextMenu sharedTreeMenu = new javafx.scene.control.ContextMenu();
        classTree.setCellFactory(tv -> new ClassTreeCell(this::resolveEntry));
        // Re-render the tree whenever a class's decompile status flips so the badge appears
        // (or disappears) without requiring the user to click around. Coalesce on the FX
        // thread — Platform.runLater is enough; refresh() is cheap for a virtualized tree.
        ClassEditorTab.statusTracker().addListener(internalName ->
                javafx.application.Platform.runLater(classTree::refresh));
        // Selection change (including keyboard arrow navigation) opens the class in the preview slot.
        classTree.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (now == null) return;
            NodeKind k = now.getValue().kind();
            if (k == NodeKind.CLASS) {
                openClassPreview(now.getValue().fqn());
            } else if (k == NodeKind.METHOD || k == NodeKind.FIELD) {
                openMemberFromTreeNode(now.getValue(), /*pinned=*/false);
            } else if (k == NodeKind.RESOURCE) {
                openResource(now.getValue().fqn(), now.getValue().resourceKind(), now.getValue().label());
            }
        });
        // Double-click promotes the preview tab to a pinned regular tab (class or member).
        classTree.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2 && ev.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                var sel = classTree.getSelectionModel().getSelectedItem();
                if (sel == null) return;
                NodeKind k = sel.getValue().kind();
                if (k == NodeKind.CLASS) {
                    promotePreviewToPinned(sel.getValue().fqn());
                } else if (k == NodeKind.METHOD || k == NodeKind.FIELD) {
                    openMemberFromTreeNode(sel.getValue(), /*pinned=*/true);
                }
            }
        });

        // Re-use the instance declared at the top of setupTree() so cells and the
        // controller share a single ContextMenu.
        final javafx.scene.control.ContextMenu treeMenu = sharedTreeMenu;
        javafx.scene.control.MenuItem findUsages = menuItem("Find Usages", "mdi2t-target");
        findUsages.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel == null || sel.getValue().kind() != NodeKind.CLASS) return;
            String fqn = sel.getValue().fqn();
            ClassEntry ce = resolveEntry(fqn);
            if (ce != null) {
                showUsages(new UsageTarget.Class(ce.internalName()));
            }
        });
        javafx.scene.control.MenuItem showHierarchy = menuItem("Show Hierarchy", "mdi2f-file-tree-outline");
        showHierarchy.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel == null || sel.getValue().kind() != NodeKind.CLASS) return;
            String fqn = sel.getValue().fqn();
            ClassEntry ce = resolveEntry(fqn);
            if (ce != null) {
                showHierarchy(ce.internalName());
            }
        });
        javafx.scene.control.MenuItem showCallGraph = menuItem("Show in Call Graph", "mdi2g-graph-outline");
        showCallGraph.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel == null || sel.getValue().kind() != NodeKind.CLASS) return;
            String fqn = sel.getValue().fqn();
            ClassEntry ce = resolveEntry(fqn);
            if (ce != null) {
                showCallGraphForClass(ce.internalName());
            }
        });
        javafx.scene.control.MenuItem copyName = menuItem("Copy name", "mdi2c-content-copy");
        copyName.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel != null) ClipboardUtil.copyToClipboard(sel.getValue().label());
        });
        javafx.scene.control.MenuItem copyFqn = menuItem("Copy FQN", "mdi2c-content-copy");
        copyFqn.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel != null && sel.getValue().fqn() != null) ClipboardUtil.copyToClipboard(sel.getValue().fqn());
        });
        javafx.scene.control.MenuItem editClassComment = menuItem("Add / Edit Comment...", "mdi2c-comment-text-outline");
        editClassComment.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel == null || sel.getValue().kind() != NodeKind.CLASS) return;
            String fqn = sel.getValue().fqn();
            ClassEntry ce = resolveEntry(fqn);
            if (ce != null) {
                editComment(dev.share.bytecodelens.comments.CommentStore.classKey(ce.name()),
                        "Class " + ce.name());
                classTree.refresh(); // update tree-cell indicator
                if (currentClassInternalName != null
                        && currentClassInternalName.equals(ce.internalName())) {
                    showClassDetails(ce);
                }
            }
        });
        // Method/field specific items.
        javafx.scene.control.MenuItem memberFindUsages = menuItem("Find references", "mdi2m-magnify-scan");
        memberFindUsages.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            MemberRef r = decodeMemberFqn(sel.getValue().fqn(), sel.getValue().kind());
            if (r == null) return;
            ClassEntry ce = resolveEntry(r.ownerKey());
            if (ce == null) return;
            if (r.kind() == NodeKind.METHOD) {
                showUsages(new UsageTarget.Method(ce.internalName(), r.name(), r.desc()));
            } else {
                showUsages(new UsageTarget.Field(ce.internalName(), r.name(), r.desc()));
            }
        });
        javafx.scene.control.MenuItem memberCopySig = menuItem("Copy signature", "mdi2c-content-copy");
        memberCopySig.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            MemberRef r = decodeMemberFqn(sel.getValue().fqn(), sel.getValue().kind());
            if (r == null) return;
            String text = r.kind() == NodeKind.METHOD
                    ? r.name() + r.desc()
                    : r.name() + " : " + r.desc();
            ClipboardUtil.copyToClipboard(text);
        });
        javafx.scene.control.MenuItem memberCallGraph = menuItem("Show in call graph", "mdi2g-graph-outline");
        memberCallGraph.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel == null || sel.getValue().kind() != NodeKind.METHOD) return;
            MemberRef r = decodeMemberFqn(sel.getValue().fqn(), sel.getValue().kind());
            if (r == null) return;
            ClassEntry ce = resolveEntry(r.ownerKey());
            if (ce == null) return;
            showCallGraphForMethod(ce.internalName(), r.name(), r.desc());
        });
        javafx.scene.control.MenuItem memberFrida = menuItem("Copy as Frida hook", "mdi2l-language-javascript");
        memberFrida.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel == null || sel.getValue().kind() != NodeKind.METHOD) return;
            MemberRef r = decodeMemberFqn(sel.getValue().fqn(), sel.getValue().kind());
            if (r == null) return;
            ClassEntry ce = resolveEntry(r.ownerKey());
            if (ce == null) return;
            // Access flags aren't in the tree node; fetch from the analyzer.
            int access = analyzer.methods(ce.bytes()).stream()
                    .filter(m -> m.name().equals(r.name()) && m.descriptor().equals(r.desc()))
                    .mapToInt(m -> m.access()).findFirst().orElse(0);
            ClipboardUtil.copyToClipboard(
                    hookSnippetGenerator.frida(ce.internalName(), r.name(), r.desc(), access));
        });

        // Exclude package/class from search — adds a glob to excludedPackages and the
        // overlay follows suit on the next search. Visible on package and class nodes.
        javafx.scene.control.MenuItem excludeFromSearch = menuItem(
                "Exclude from search", "mdi2e-eye-off-outline");
        excludeFromSearch.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            String fqn = sel.getValue().fqn();
            if (fqn == null || fqn.isEmpty()) return;
            // Package node fqn is already a dotted name. Class node fqn is also dotted
            // (e.g. com.foo.Bar). For packages we append ".*" to make intent explicit
            // (users reading the saved workspace JSON see a glob, not a bare prefix).
            String pattern = sel.getValue().kind() == NodeKind.PACKAGE ? fqn + ".*" : fqn;
            if (!excludedPackages.contains(pattern)) {
                excludedPackages.add(pattern);
                if (searchOverlay != null) searchOverlay.setExcludedPackages(excludedPackages);
            }
        });

        // "Recover with Fallback" — for classes the chain failed on, force the ASM-skeleton
        // engine. Always shown but only meaningful when the class has a real failure;
        // tooltip clarifies. The action just opens the class with FallbackDecompiler set
        // as the active one (selector side-effect populates the cache too).
        javafx.scene.control.MenuItem recoverFallback = menuItem(
                "Recover with Fallback (ASM skeleton)", "mdi2l-lifebuoy");
        recoverFallback.setOnAction(e -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            if (sel == null || sel.getValue().kind() != NodeKind.CLASS) return;
            String fqn = sel.getValue().fqn();
            ClassEntry ce = resolveEntry(fqn);
            if (ce == null) return;
            // Drop the cached entry under whichever engine name was last used so the
            // freshly-opened tab actually re-decompiles instead of showing the old failure.
            // Cache is keyed by engine name + bytes hash; clearing the whole cache for
            // this class via fall-through into "any engine" is overkill — just rerun the
            // tab open which will pick the active engine and try again.
            openClass(fqn, null, ClassEditorTab.View.DECOMPILED);
        });

        // Right-click selects the row under the pointer and manually opens the context
        // menu. The JavaFX built-in path (TreeView.contextMenu + CONTEXT_MENU_REQUESTED)
        // short-circuits when the menu's item list is empty — and we populate items on
        // setOnShowing, creating a chicken-and-egg that left rclicks showing nothing at
        // all. We fix it by pre-populating items BEFORE show(), using the same helper
        // setOnShowing does so we don't duplicate the per-kind logic.
        classTree.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, ev -> {
            if (ev.getButton() != javafx.scene.input.MouseButton.SECONDARY) return;
            javafx.scene.Node picked = ev.getPickResult().getIntersectedNode();
            while (picked != null && !(picked instanceof javafx.scene.control.TreeCell)) {
                picked = picked.getParent();
            }
            if (!(picked instanceof javafx.scene.control.TreeCell<?> cell)) return;
            if (cell.isEmpty() || cell.getTreeItem() == null) return;
            classTree.getSelectionModel().select(cell.getIndex());
            // Close any currently-visible menu before opening a new one, otherwise rapid
            // right-clicks stack popups.
            if (sharedTreeMenu.isShowing()) sharedTreeMenu.hide();
            Object value = cell.getTreeItem().getValue();
            if (!(value instanceof TreeNode node)) return;
            treeMenuPopulator.accept(node);
            if (sharedTreeMenu.getItems().isEmpty()) return;
            sharedTreeMenu.show(classTree, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });

        // Single helper that populates items based on the selected tree node's kind —
        // called both from the MOUSE_PRESSED path (pre-show) and from setOnShowing
        // (fallback when the keyboard triggers a context menu).
        this.treeMenuPopulator = node -> {
            treeMenu.getItems().clear();
            if (node == null) return;
            NodeKind k = node.kind();
            if (k == NodeKind.CLASS) {
                treeMenu.getItems().addAll(findUsages, showHierarchy, showCallGraph,
                        new javafx.scene.control.SeparatorMenuItem(),
                        copyName, copyFqn,
                        new javafx.scene.control.SeparatorMenuItem(),
                        editClassComment,
                        new javafx.scene.control.SeparatorMenuItem(),
                        excludeFromSearch);
                // Add the recovery action only for classes whose decompile is known to be
                // bad — keeps the menu uncluttered on healthy classes.
                ClassEntry ce = resolveEntry(node.fqn());
                if (ce != null) {
                    var st = ClassEditorTab.statusTracker().statusOf(ce.internalName());
                    if (st == dev.share.bytecodelens.decompile.DecompileStatusTracker.Status.FAILED
                            || st == dev.share.bytecodelens.decompile.DecompileStatusTracker.Status.FALLBACK_ONLY) {
                        treeMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
                        treeMenu.getItems().add(recoverFallback);
                    }
                }
            } else if (k == NodeKind.PACKAGE) {
                treeMenu.getItems().addAll(copyName, excludeFromSearch);
            } else if (k == NodeKind.METHOD) {
                treeMenu.getItems().addAll(memberFindUsages, memberCallGraph,
                        new javafx.scene.control.SeparatorMenuItem(),
                        memberCopySig, memberFrida);
            } else if (k == NodeKind.FIELD) {
                treeMenu.getItems().addAll(memberFindUsages,
                        new javafx.scene.control.SeparatorMenuItem(),
                        memberCopySig);
            } else {
                treeMenu.getItems().add(copyName);
            }
        };
        treeMenu.setOnShowing(evt -> {
            var sel = classTree.getSelectionModel().getSelectedItem();
            treeMenuPopulator.accept(sel == null ? null : sel.getValue());
        });
        classTree.setContextMenu(treeMenu);

        classTree.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (new javafx.scene.input.KeyCodeCombination(
                    javafx.scene.input.KeyCode.C, javafx.scene.input.KeyCombination.SHORTCUT_DOWN).match(e)) {
                var sel = classTree.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    String text = sel.getValue().fqn() != null ? sel.getValue().fqn() : sel.getValue().label();
                    ClipboardUtil.copyToClipboard(text);
                    e.consume();
                }
            }
        });
    }

    private void setupTables() {
        cpIndexCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().index()));
        cpTypeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().type()));
        cpValueCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().value()));

        mNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        mDescCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().descriptor()));
        mAccessCol.setCellValueFactory(c -> new SimpleStringProperty(
                String.join(" ", AccessFlags.forMethod(c.getValue().access()))));
        mInsnCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().instructionCount()));

        fNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        fDescCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().descriptor()));
        fAccessCol.setCellValueFactory(c -> new SimpleStringProperty(
                String.join(" ", AccessFlags.forField(c.getValue().access()))));
        fValueCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().constantValue() == null ? "" : String.valueOf(c.getValue().constantValue())));

        installTableContextMenus();
        ClipboardUtil.installTableCopy(constantPoolTable);
        ClipboardUtil.installTableCopy(methodsTable);
        ClipboardUtil.installTableCopy(fieldsTable);
    }

    private String currentClassInternalName;

    private void installTableContextMenus() {
        javafx.scene.control.ContextMenu methodMenu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem methodFindUsages = menuItem("Find Usages", "mdi2t-target");
        methodFindUsages.setOnAction(e -> {
            var sel = methodsTable.getSelectionModel().getSelectedItem();
            if (sel != null && currentClassInternalName != null) {
                showUsages(new UsageTarget.Method(currentClassInternalName, sel.name(), sel.descriptor()));
            }
        });
        javafx.scene.control.MenuItem methodCallGraph = menuItem("Show in Call Graph", "mdi2g-graph-outline");
        methodCallGraph.setOnAction(e -> {
            var sel = methodsTable.getSelectionModel().getSelectedItem();
            if (sel != null && currentClassInternalName != null) {
                showCallGraphForMethod(currentClassInternalName, sel.name(), sel.descriptor());
            }
        });
        javafx.scene.control.MenuItem copyFridaHook = menuItem("Copy as Frida hook", "mdi2l-language-javascript");
        copyFridaHook.setOnAction(e -> {
            var sel = methodsTable.getSelectionModel().getSelectedItem();
            if (sel != null && currentClassInternalName != null) {
                String snippet = hookSnippetGenerator.frida(currentClassInternalName,
                        sel.name(), sel.descriptor(), sel.access());
                ClipboardUtil.copyToClipboard(snippet);
                updateStatus("Copied Frida hook for " + sel.name() + sel.descriptor(), "");
            }
        });
        javafx.scene.control.MenuItem copyXposedHook = menuItem("Copy as Xposed hook", "mdi2a-android");
        copyXposedHook.setOnAction(e -> {
            var sel = methodsTable.getSelectionModel().getSelectedItem();
            if (sel != null && currentClassInternalName != null) {
                String snippet = hookSnippetGenerator.xposed(currentClassInternalName,
                        sel.name(), sel.descriptor(), sel.access());
                ClipboardUtil.copyToClipboard(snippet);
                updateStatus("Copied Xposed hook for " + sel.name() + sel.descriptor(), "");
            }
        });
        javafx.scene.control.MenuItem editMethodComment = menuItem("Add / Edit Comment...", "mdi2c-comment-text-outline");
        editMethodComment.setOnAction(e -> {
            var sel = methodsTable.getSelectionModel().getSelectedItem();
            if (sel == null || currentClassInternalName == null) return;
            String fqn = currentClassInternalName.replace('/', '.');
            editComment(
                    dev.share.bytecodelens.comments.CommentStore.methodKey(fqn, sel.name(), sel.descriptor()),
                    "Method " + fqn + "." + sel.name() + sel.descriptor());
            // Refresh the methods table so the comment icon appears.
            methodsTable.refresh();
            ClassEntry ce = resolveEntry(fqn);
            if (ce != null) showClassDetails(ce);
        });
        methodMenu.getItems().addAll(methodFindUsages, methodCallGraph,
                new javafx.scene.control.SeparatorMenuItem(), copyFridaHook, copyXposedHook,
                new javafx.scene.control.SeparatorMenuItem(), editMethodComment);
        methodsTable.setContextMenu(methodMenu);
        methodsTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<MethodEntry> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && currentClassInternalName != null) {
                    MethodEntry m = row.getItem();
                    String fqn = currentClassInternalName.replace('/', '.');
                    openClass(fqn, HighlightRequest.literal(m.name(), -1), ClassEditorTab.View.DECOMPILED);
                }
            });
            return row;
        });

        javafx.scene.control.ContextMenu fieldMenu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem fieldFindUsages = menuItem("Find Usages", "mdi2t-target");
        fieldFindUsages.setOnAction(e -> {
            var sel = fieldsTable.getSelectionModel().getSelectedItem();
            if (sel != null && currentClassInternalName != null) {
                showUsages(new UsageTarget.Field(currentClassInternalName, sel.name(), sel.descriptor()));
            }
        });
        javafx.scene.control.MenuItem editFieldComment = menuItem("Add / Edit Comment...", "mdi2c-comment-text-outline");
        editFieldComment.setOnAction(e -> {
            var sel = fieldsTable.getSelectionModel().getSelectedItem();
            if (sel == null || currentClassInternalName == null) return;
            String fqn = currentClassInternalName.replace('/', '.');
            editComment(
                    dev.share.bytecodelens.comments.CommentStore.fieldKey(fqn, sel.name(), sel.descriptor()),
                    "Field " + fqn + "." + sel.name() + ":" + sel.descriptor());
            fieldsTable.refresh();
            ClassEntry ce = resolveEntry(fqn);
            if (ce != null) showClassDetails(ce);
        });
        fieldMenu.getItems().addAll(fieldFindUsages,
                new javafx.scene.control.SeparatorMenuItem(), editFieldComment);
        fieldsTable.setContextMenu(fieldMenu);
        fieldsTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<FieldEntry> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && currentClassInternalName != null) {
                    FieldEntry f = row.getItem();
                    String fqn = currentClassInternalName.replace('/', '.');
                    openClass(fqn, HighlightRequest.literal(f.name(), -1), ClassEditorTab.View.DECOMPILED);
                }
            });
            return row;
        });
    }

    private void setupSearch() {
        searchField.textProperty().addListener((obs, old, now) -> applyFilter(now == null ? "" : now));
    }

    private void setupDragAndDrop() {
        // Drag-and-drop registered on scene after show; guarded against null here
        javafx.application.Platform.runLater(() -> {
            if (classTree.getScene() == null) return;
            var scene = classTree.getScene();
            scene.setOnDragOver(e -> {
                if (e.getDragboard().hasFiles()) {
                    e.acceptTransferModes(TransferMode.COPY);
                }
                e.consume();
            });
            scene.setOnDragDropped(e -> {
                var db = e.getDragboard();
                boolean ok = false;
                if (db.hasFiles() && !db.getFiles().isEmpty()) {
                    File f = db.getFiles().get(0);
                    jarStack.clear();
                    if (backButton != null) backButton.setDisable(true);
                    loadJarFile(f.toPath());
                    ok = true;
                }
                e.setDropCompleted(ok);
                e.consume();
            });
        });
    }

    @FXML
    public void onOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open jar, war, class, jmod or zip");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Java archives & classes",
                        "*.jar", "*.war", "*.class", "*.zip", "*.jmod"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        Stage stage = (Stage) classTree.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            jarStack.clear();
            if (backButton != null) backButton.setDisable(true);
            loadJarFile(file.toPath());
        }
    }

    @FXML
    public void onCloseJar() {
        currentJar = null;
        // Clear then re-show the Start Page so the user has a landing screen to work from.
        // The visibility helper is a no-op if installStartPage() hasn't been called yet.
        javafx.application.Platform.runLater(this::updateStartPageVisibility);
        originalJarBeforeMapping = null;
        activeMappingLabel = null;
        activeMappingModel = null;
        commentStore.clear();
        closeLiveAgentQuietly();
        searchIndex = null;
        usageIndex = null;
        hierarchyIndex = null;
        jarStack.clear();
        if (backButton != null) backButton.setDisable(true);
        if (searchOverlay != null) searchOverlay.setIndex(null);
        if (patternPanel != null) patternPanel.setJar(null);
        if (usagePanel != null) usagePanel.clear();
        if (hierarchyPanel != null) hierarchyPanel.setIndex(null);
        if (decryptPanel != null) decryptPanel.clear();
        lastDecryptionResult = null;
        updateWindowTitle(null);
        classByFqn.clear();
        versionedClassByKey.clear();
        openTabs.clear();
        openResourceTabs.clear();
        previewTab = null;
        previewTabFqn = null;
        allClassesRoot.getChildren().clear();
        editorTabs.getTabs().clear();
        constantPoolTable.getItems().clear();
        methodsTable.getItems().clear();
        fieldsTable.getItems().clear();
        classInfoBox.getChildren().clear();
        updateStatus("Closed", "");
        fillDataInspectorEmpty();
    }

    @FXML
    public void onOpenFromUrl() {
        javafx.scene.control.TextInputDialog dlg = new javafx.scene.control.TextInputDialog();
        dlg.setTitle("Open from URL");
        dlg.setHeaderText("Download a jar and open it");
        dlg.setContentText("URL:");
        dlg.getEditor().setPromptText("https://repo1.maven.org/maven2/...");
        var result = dlg.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) return;

        String urlText = result.get().trim();
        java.net.URI uri;
        try {
            uri = java.net.URI.create(urlText);
        } catch (IllegalArgumentException ex) {
            showError("Open from URL", "Invalid URL: " + ex.getMessage());
            return;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            showError("Open from URL", "Only http/https URLs are supported.");
            return;
        }
        // Extract a reasonable filename from the path (so saved temp file has a nice name).
        String lastSegment = "download.jar";
        String path = uri.getPath();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            String tail = slash < 0 ? path : path.substring(slash + 1);
            if (!tail.isBlank()) lastSegment = tail;
        }
        final String fileName = lastSegment;

        updateStatus("Downloading " + fileName + "...", urlText);
        Task<Path> task = new Task<>() {
            @Override protected Path call() throws Exception {
                Path dir = Path.of(System.getProperty("user.home"), ".bytecodelens", "downloads");
                java.nio.file.Files.createDirectories(dir);
                Path dest = dir.resolve(fileName);
                java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .connectTimeout(java.time.Duration.ofSeconds(15))
                        .build();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri)
                        .header("User-Agent", "BytecodeLens")
                        .GET().build();
                var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofFile(dest));
                if (resp.statusCode() / 100 != 2) {
                    throw new java.io.IOException("HTTP " + resp.statusCode());
                }
                return dest;
            }
        };
        progressBar.visibleProperty().bind(task.runningProperty());
        task.setOnSucceeded(e -> {
            progressBar.visibleProperty().unbind();
            progressBar.setVisible(false);
            Path downloaded = task.getValue();
            long size = 0;
            try { size = java.nio.file.Files.size(downloaded); } catch (java.io.IOException ignored) {}
            updateStatus("Downloaded " + fileName + " (" + (size / 1024) + " KB)", downloaded.toString());
            loadJarFile(downloaded);
        });
        task.setOnFailed(e -> {
            progressBar.visibleProperty().unbind();
            progressBar.setVisible(false);
            Throwable ex = task.getException();
            log.error("download failed", ex);
            showError("Download failed", ex == null ? "Unknown" : ex.getMessage());
        });
        Thread t = new Thread(task, "url-download");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onSaveAs() {
        if (currentJar == null) {
            showInfo("Save As", "No jar is currently loaded.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save jar as...");
        String suggested = currentJar.source().getFileName().toString();
        chooser.setInitialFileName(suggested);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Java archive", "*.jar"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        Stage stage = (Stage) classTree.getScene().getWindow();
        File dest = chooser.showSaveDialog(stage);
        if (dest == null) return;

        try {
            exportService.exportJar(currentJar, dest.toPath());
            updateStatus("Saved jar to " + dest.getName(), dest.getAbsolutePath());
        } catch (java.io.IOException ex) {
            log.error("Save as failed", ex);
            showError("Save failed", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    @FXML
    public void onExportSources() {
        if (currentJar == null) {
            showInfo("Export Sources", "No jar is currently loaded.");
            return;
        }
        ClassDecompiler decompilerChoice = pickDecompiler("Export Sources");
        if (decompilerChoice == null) return;

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose output directory for decompiled sources");
        Stage stage = (Stage) classTree.getScene().getWindow();
        File outDir = chooser.showDialog(stage);
        if (outDir == null) return;

        runBatchExport(
                "Export Sources (" + decompilerChoice.name() + ")",
                (progress, cancel) ->
                        exportService.exportSources(currentJar, decompilerChoice, outDir.toPath(),
                                progress, cancel),
                outDir.toPath());
    }

    @FXML
    public void onExportBytecode() {
        if (currentJar == null) {
            showInfo("Export Bytecode", "No jar is currently loaded.");
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose output directory for bytecode listings");
        Stage stage = (Stage) classTree.getScene().getWindow();
        File outDir = chooser.showDialog(stage);
        if (outDir == null) return;

        runBatchExport(
                "Export Bytecode",
                (progress, cancel) ->
                        exportService.exportBytecode(currentJar, bytecodePrinter, outDir.toPath(),
                                progress, cancel),
                outDir.toPath());
    }

    /** Shows a small dialog letting the user pick one of the three decompilers for batch export. */
    private ClassDecompiler pickDecompiler(String title) {
        ClassDecompiler[] options = {
                new CfrDecompiler(),
                new VineflowerDecompiler(),
                new ProcyonDecompiler(),
                new dev.share.bytecodelens.decompile.FallbackDecompiler()
        };
        javafx.scene.control.ChoiceDialog<ClassDecompiler> dlg =
                new javafx.scene.control.ChoiceDialog<>(options[0], options);
        dlg.setTitle(title);
        dlg.setHeaderText("Choose decompiler");
        dlg.setContentText("Decompiler:");
        // Render by name() so the user sees "CFR" / "Vineflower" / "Procyon".
        dlg.getItems().clear();
        dlg.getItems().addAll(options);
        // The default toString() on the ClassDecompiler interface is the class name, which is ugly.
        // ChoiceDialog uses StringConverter on its internal ComboBox — reach through:
        javafx.scene.Node combo = dlg.getDialogPane().lookup(".combo-box");
        if (combo instanceof javafx.scene.control.ComboBox<?>) {
            @SuppressWarnings("unchecked")
            javafx.scene.control.ComboBox<ClassDecompiler> cb =
                    (javafx.scene.control.ComboBox<ClassDecompiler>) combo;
            cb.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(ClassDecompiler d) { return d == null ? "" : d.name(); }
                @Override public ClassDecompiler fromString(String s) { return null; }
            });
        }
        return dlg.showAndWait().orElse(null);
    }

    @FunctionalInterface
    private interface BatchExportOp {
        ExportService.ExportSummary run(ExportService.ProgressListener progress,
                                        java.util.concurrent.atomic.AtomicBoolean cancel)
                throws Exception;
    }

    private void runBatchExport(String title, BatchExportOp op, Path outDir) {
        int total = currentJar.classCount() + currentJar.versionedClassCount();
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();

        // Progress dialog: its own small Stage with a progress bar, current class label and Cancel.
        Stage owner = (Stage) classTree.getScene().getWindow();
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(javafx.stage.Modality.WINDOW_MODAL);
        dlg.setTitle(title);
        dlg.setResizable(false);

        Label header = new Label(title);
        header.getStyleClass().add("info-key");
        Label currentLabel = new Label("Starting...");
        currentLabel.getStyleClass().add("info-value");
        currentLabel.setMaxWidth(420);
        currentLabel.setWrapText(false);
        currentLabel.setStyle("-fx-text-overrun: center-ellipsis;");
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(420);
        Label count = new Label("0 / " + total);
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setOnAction(e -> {
            cancel.set(true);
            cancelBtn.setDisable(true);
            cancelBtn.setText("Cancelling...");
        });
        HBox buttons = new HBox(10, cancelBtn);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        VBox root = new VBox(10, header, currentLabel, bar, count, buttons);
        root.setPadding(new javafx.geometry.Insets(16));
        dlg.setScene(new javafx.scene.Scene(root));
        dlg.show();

        Task<ExportService.ExportSummary> task = new Task<>() {
            @Override
            protected ExportService.ExportSummary call() throws Exception {
                return op.run(
                        (current, tot, message) -> Platform.runLater(() -> {
                            double frac = tot == 0 ? 0 : current / (double) tot;
                            bar.setProgress(frac);
                            currentLabel.setText(message);
                            count.setText(current + " / " + tot);
                        }),
                        cancel);
            }
        };
        task.setOnSucceeded(e -> {
            dlg.close();
            ExportService.ExportSummary s = task.getValue();
            String msg;
            if (s.cancelled() > 0) {
                msg = String.format("Cancelled: %d exported, %d failed, %d remaining.%nOutput: %s",
                        s.exported(), s.failed(), s.cancelled(), outDir);
            } else {
                msg = String.format("%d exported, %d failed.%nOutput: %s",
                        s.exported(), s.failed(), outDir);
            }
            showInfo(title + " — done", msg);
        });
        task.setOnFailed(e -> {
            dlg.close();
            Throwable ex = task.getException();
            log.error("Batch export failed", ex);
            showError(title + " failed", ex == null ? "Unknown error" : ex.getMessage());
        });
        Thread t = new Thread(task, "batch-export");
        t.setDaemon(true);
        t.start();
    }

    /** Currently-attached agent client (null when not in live-session mode). Owned by the controller. */
    private dev.share.bytecodelens.agent.AttachClient liveAgent;
    private long liveAgentPid;

    private void closeLiveAgentQuietly() {
        if (liveAgent != null) {
            try { liveAgent.close(); } catch (java.io.IOException ignored) {}
            liveAgent = null;
            liveAgentPid = 0;
        }
    }

    @FXML
    public void onAttachToJvm() {
        dev.share.bytecodelens.agent.AttachController ac = new dev.share.bytecodelens.agent.AttachController();
        java.util.List<dev.share.bytecodelens.agent.AttachController.TargetProcess> procs;
        try {
            procs = ac.listProcesses();
        } catch (Throwable ex) {
            log.error("list JVMs failed", ex);
            showError("Attach to JVM", "Cannot enumerate Java processes: " + ex.getMessage()
                    + "\n\nBytecodeLens must be running on a JDK (not a JRE) to attach.");
            return;
        }
        if (procs.isEmpty()) {
            showInfo("Attach to JVM", "No Java processes found.");
            return;
        }
        // Filter out ourselves (same pid) so the user doesn't accidentally attach to BytecodeLens.
        long ownPid = ProcessHandle.current().pid();
        procs.removeIf(p -> p.pid() == ownPid);

        javafx.scene.control.ListView<dev.share.bytecodelens.agent.AttachController.TargetProcess> lv =
                new javafx.scene.control.ListView<>();
        lv.setCellFactory(param -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(dev.share.bytecodelens.agent.AttachController.TargetProcess p,
                                                boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) { setText(null); }
                else { setText(p.pid() + "  " + p.displayName()); }
            }
        });
        lv.getItems().addAll(procs);
        lv.setPrefHeight(320);

        Alert dlg = new Alert(Alert.AlertType.NONE);
        dlg.setTitle("Attach to JVM");
        dlg.setHeaderText("Pick a Java process to attach BytecodeLens to");
        dlg.getDialogPane().setContent(lv);
        dlg.getButtonTypes().setAll(new ButtonType("Attach",
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        var result = dlg.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) return;

        var selected = lv.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        // Attach on a background thread — loadAgent can block for a couple seconds.
        updateStatus("Attaching to pid " + selected.pid() + "...", selected.displayName());
        Task<dev.share.bytecodelens.agent.AttachClient> task = new Task<>() {
            @Override protected dev.share.bytecodelens.agent.AttachClient call() throws Exception {
                return ac.attach(Long.toString(selected.pid()));
            }
        };
        task.setOnSucceeded(e -> {
            liveAgent = task.getValue();
            liveAgentPid = selected.pid();
            updateStatus("Attached to pid " + selected.pid(), selected.displayName());
            // Inspector opens IMMEDIATELY on successful attach — no waiting for DUMP_ALL.
            // The user may never care about class editing, just JVM introspection. An
            // "Import classes for editing" button inside the Inspector fires the optional
            // dump on demand.
            try {
                dev.share.bytecodelens.ui.JvmInspectorStage inspector =
                        new dev.share.bytecodelens.ui.JvmInspectorStage(
                                liveAgent, selected.pid(), selected.displayName(), darkTheme);
                inspector.setOnImportClasses(() -> enterLiveSession(selected));
                inspector.show();
            } catch (Exception inspectorEx) {
                log.error("Failed to open JVM inspector", inspectorEx);
                showError("Inspector", inspectorEx.getMessage());
            }
        });
        task.setOnFailed(e -> {
            log.error("attach failed", task.getException());
            showError("Attach failed",
                    task.getException() == null ? "Unknown" : task.getException().getMessage());
        });
        Thread t = new Thread(task, "jvm-attach");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Fetch every class from the target JVM via streaming DUMP_ALL (single retransform pass
     * on the agent side). Way faster than per-class LIST+GET and doesn't deadlock against
     * custom classloaders like LaunchClassLoader/Forge.
     */
    private void enterLiveSession(dev.share.bytecodelens.agent.AttachController.TargetProcess target) {
        if (liveAgent == null) return;
        updateStatus("Starting live-class dump from JVM...", target.displayName());

        Task<LoadedJar> task = new Task<>() {
            @Override protected LoadedJar call() throws Exception {
                java.util.List<ClassEntry> entries = new java.util.ArrayList<>(8192);
                dev.share.bytecodelens.service.ClassAnalyzer an = new dev.share.bytecodelens.service.ClassAnalyzer();
                long[] totalBytes = {0};
                int[] counts = {0, 0}; // [received, failed-parse]
                long[] lastUpdate = {System.currentTimeMillis()};

                liveAgent.dumpAll((dottedName, bytes) -> {
                    counts[0]++;
                    try {
                        entries.add(an.analyze(bytes, 0));
                        totalBytes[0] += bytes.length;
                    } catch (Throwable ex) {
                        counts[1]++;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate[0] > 200) {
                        updateMessage("Received " + counts[0] + " classes"
                                + (counts[1] > 0 ? " (" + counts[1] + " unparseable)" : "")
                                + "  \u2014 " + dottedName);
                        lastUpdate[0] = now;
                    }
                    return !isCancelled();
                });

                entries.sort((a, b) -> a.name().compareTo(b.name()));
                log.info("Live dump: {} classes received, {} unparseable", counts[0], counts[1]);
                return new LoadedJar(java.nio.file.Path.of("jvm-pid" + target.pid() + ".jar"),
                        java.util.List.copyOf(entries), java.util.List.of(), java.util.List.of(),
                        totalBytes[0], 0);
            }
        };
        progressBar.visibleProperty().bind(task.runningProperty());
        progressBar.progressProperty().bind(task.progressProperty());
        task.messageProperty().addListener((obs, old, msg) -> {
            if (msg != null && !msg.isEmpty()) updateStatus(msg, target.displayName());
        });
        task.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            progressBar.visibleProperty().unbind();
            progressBar.setVisible(false);
            LoadedJar jar = task.getValue();
            onJarLoaded(jar);
            updateWindowTitle("Live JVM pid " + target.pid() + " \u2014 " + jar.classCount() + " classes");
            showInfo("Attached",
                    "Loaded " + jar.classCount() + " classes from pid " + target.pid() + ".\n\n"
                            + "Edit \u2192 Compile will hotswap classes into the running process.");
        });
        task.setOnFailed(e -> {
            progressBar.progressProperty().unbind();
            progressBar.visibleProperty().unbind();
            progressBar.setVisible(false);
            log.error("live session fetch failed", task.getException());
            showError("Live session",
                    task.getException() == null ? "Unknown" : task.getException().getMessage());
            if (liveAgent != null) {
                try { liveAgent.close(); } catch (java.io.IOException ignored) {}
                liveAgent = null;
            }
        });
        Thread t = new Thread(task, "live-session-loader");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onSaveWorkspace() {
        if (currentJar == null) {
            showInfo("Save Workspace", "Open a jar first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save workspace as...");
        chooser.setInitialFileName(currentJar.source().getFileName() + ".bclens.json");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("BytecodeLens workspace", "*.bclens.json", "*.json"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        Stage stage = (Stage) classTree.getScene().getWindow();
        File dest = chooser.showSaveDialog(stage);
        if (dest == null) return;

        dev.share.bytecodelens.workspace.WorkspaceState state = captureWorkspaceState();
        try {
            dev.share.bytecodelens.workspace.WorkspaceJson.write(state, dest.toPath());
            updateStatus("Workspace saved to " + dest.getName(), dest.getAbsolutePath());
        } catch (java.io.IOException ex) {
            log.error("Save workspace failed", ex);
            showError("Save workspace failed",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    @FXML
    public void onOpenWorkspace() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open workspace");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("BytecodeLens workspace", "*.bclens.json", "*.json"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        Stage stage = (Stage) classTree.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        dev.share.bytecodelens.workspace.WorkspaceState state;
        try {
            state = dev.share.bytecodelens.workspace.WorkspaceJson.read(file.toPath());
        } catch (Exception ex) {
            log.error("Open workspace failed", ex);
            showError("Open workspace failed",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return;
        }
        if (state.jarPath == null) {
            showError("Open workspace", "Workspace has no jar path.");
            return;
        }
        Path jarPath = Path.of(state.jarPath);
        if (!java.nio.file.Files.isRegularFile(jarPath)) {
            showError("Open workspace", "Jar no longer exists: " + state.jarPath);
            return;
        }
        // Stash the state for restore-after-load. onJarLoaded will pick it up.
        pendingWorkspaceRestore = state;
        loadJarFile(jarPath);
    }

    private dev.share.bytecodelens.workspace.WorkspaceState pendingWorkspaceRestore;

    private dev.share.bytecodelens.workspace.WorkspaceState captureWorkspaceState() {
        dev.share.bytecodelens.workspace.WorkspaceState s = new dev.share.bytecodelens.workspace.WorkspaceState();
        s.jarPath = currentJar.source().toString();
        s.darkTheme = darkTheme;
        if (mainSplit != null && mainSplit.getDividerPositions().length >= 2) {
            s.mainSplit1 = mainSplit.getDividerPositions()[0];
            s.mainSplit2 = mainSplit.getDividerPositions()[1];
        }
        if (centerSplit != null && centerSplit.getDividerPositions().length >= 1) {
            s.centerSplit = centerSplit.getDividerPositions()[0];
        }
        // User's chosen code font size (Ctrl+wheel / Ctrl+plus/minus). Shared across all
        // open CodeView tabs via CodeView#getSharedFontSize — one value, one restore.
        s.codeFontSize = dev.share.bytecodelens.ui.views.CodeView.getSharedFontSize();
        // Preserve mapping reference if one is applied (path + format).
        if (activeMappingModel != null) {
            s.mappingFormat = activeMappingModel.sourceFormat().name();
        }
        // Open tabs (class tabs only — resource tabs not persisted in MVP).
        for (var e : openTabs.entrySet()) {
            s.openTabs.add(e.getKey());
        }
        // Active tab
        var sel = editorTabs.getSelectionModel().getSelectedItem();
        if (sel != null) {
            for (var e : openTabs.entrySet()) {
                if (e.getValue().tab() == sel) { s.activeTab = e.getKey(); break; }
            }
        }
        // Comments — copy entire store into state.
        s.comments.putAll(commentStore.all());
        // Packages the user excluded from search — preserved across sessions so you
        // don't have to rebuild the noise filter for a big project every time.
        s.excludedPackages.clear();
        s.excludedPackages.addAll(excludedPackages);
        return s;
    }

    private void applyWorkspaceState(dev.share.bytecodelens.workspace.WorkspaceState s) {
        if (s == null) return;
        if (s.darkTheme != darkTheme) {
            onToggleTheme();
        }
        if (mainSplit != null) {
            mainSplit.setDividerPositions(s.mainSplit1, s.mainSplit2);
        }
        if (centerSplit != null) {
            centerSplit.setDividerPositions(s.centerSplit);
        }
        // Broadcast the persisted font size to CodeView so all tabs (existing + ones
        // opened below) render at the user's preferred zoom level.
        if (s.codeFontSize > 0) {
            dev.share.bytecodelens.ui.views.CodeView.setSharedFontSize(s.codeFontSize);
        }
        // Restore comments — before opening tabs so ClassInfo can render with comments.
        commentStore.replaceAll(s.comments);
        // Restore excluded packages — must reach the overlay if it's already been init'd.
        excludedPackages.clear();
        if (s.excludedPackages != null) excludedPackages.addAll(s.excludedPackages);
        if (searchOverlay != null) searchOverlay.setExcludedPackages(excludedPackages);
        // Re-open tabs
        for (String fqn : s.openTabs) {
            openClass(fqn, null, ClassEditorTab.View.DECOMPILED);
        }
        if (s.activeTab != null) {
            ClassEditorTab t = openTabs.get(s.activeTab);
            if (t != null) editorTabs.getSelectionModel().select(t.tab());
        }
    }

    /** Show an edit dialog for a comment on a specific symbol. */
    private void editComment(String key, String targetLabel) {
        String existing = commentStore.get(key);
        javafx.scene.control.TextArea ta = new javafx.scene.control.TextArea(existing == null ? "" : existing);
        ta.setPrefRowCount(6);
        ta.setPrefColumnCount(60);
        ta.setWrapText(true);

        Alert dlg = new Alert(Alert.AlertType.NONE);
        dlg.setTitle("Comment");
        dlg.setHeaderText("Comment on " + targetLabel);
        dlg.getDialogPane().setContent(ta);
        ButtonType saveBtn = new ButtonType("Save", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType removeBtn = new ButtonType("Remove", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        dlg.getButtonTypes().setAll(saveBtn, removeBtn, ButtonType.CANCEL);
        var result = dlg.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) return;
        if (result.get() == removeBtn) {
            commentStore.remove(key);
            updateStatus("Comment removed", targetLabel);
        } else {
            String text = ta.getText();
            commentStore.set(key, text);
            updateStatus(text.isBlank() ? "Comment removed" : "Comment saved", targetLabel);
        }
    }

    @FXML
    public void onExit() {
        Platform.exit();
    }

    @FXML
    public void onToggleTheme() {
        darkTheme = !darkTheme;
        Application.setUserAgentStylesheet(darkTheme
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
        themeToggle.setSelected(darkTheme);
        updateThemeIcon();
        applyThemeClass();
    }

    private void updateThemeIcon() {
        if (themeIcon == null) return;
        themeIcon.setIconLiteral(darkTheme ? "fth-moon" : "fth-sun");
    }

    private void applyThemeClass() {
        if (classTree == null || classTree.getScene() == null) return;
        var root = classTree.getScene().getRoot();
        root.getStyleClass().removeAll("dark-theme", "light-theme");
        root.getStyleClass().add(darkTheme ? "dark-theme" : "light-theme");
        // Preserve the active syntax theme — toggling the UI theme above shouldn't wipe
        // our .syntax-<id> marker (they live together on the root).
        if (activeSyntaxTheme != null) {
            dev.share.bytecodelens.theme.ThemeManager.apply(root, activeSyntaxTheme);
        }
    }

    @FXML
    public void onAbout() {
        showInfo("About BytecodeLens",
                "BytecodeLens v1.0.0" + System.lineSeparator()
                        + "The Java RE cockpit \u2014 decompile, attach, diff, patch." + System.lineSeparator()
                        + System.lineSeparator()
                        + "Licensed under Apache 2.0." + System.lineSeparator()
                        + "Built on CFR, Vineflower, Procyon, ASM, JavaFX, AtlantaFX.");
    }

    private static javafx.scene.control.MenuItem menuItem(String text, String iconLiteral) {
        javafx.scene.control.MenuItem mi = new javafx.scene.control.MenuItem(text);
        if (iconLiteral != null) {
            org.kordamp.ikonli.javafx.FontIcon icon = new org.kordamp.ikonli.javafx.FontIcon(iconLiteral);
            icon.setIconSize(14);
            mi.setGraphic(icon);
        }
        return mi;
    }

    /** Three-arg variant — also attach an {@code onAction} handler. */
    private static javafx.scene.control.MenuItem menuItem(String text, String iconLiteral,
                                                          javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        javafx.scene.control.MenuItem mi = menuItem(text, iconLiteral);
        mi.setOnAction(handler);
        return mi;
    }

    private void setupEditorTabsContextMenu() {
        // Enable native tab reordering — drag-and-drop within the same TabPane. JADX
        // has had this since v1.5.0; it's a one-liner in JavaFX 10+.
        editorTabs.setTabDragPolicy(javafx.scene.control.TabPane.TabDragPolicy.REORDER);

        editorTabs.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (javafx.scene.control.Tab t : change.getAddedSubList()) {
                        attachTabContextMenu(t);
                    }
                }
            }
        });
    }

    private void attachTabContextMenu(javafx.scene.control.Tab tab) {
        if (tab.getContextMenu() != null) return;
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem closeThis = menuItem("Close", "mdi2c-close");
        closeThis.setOnAction(e -> {
            editorTabs.getTabs().remove(tab);
            if (tab.getOnClosed() != null) tab.getOnClosed().handle(null);
        });
        javafx.scene.control.MenuItem closeOthers = menuItem("Close Others", "mdi2c-close-box-multiple-outline");
        closeOthers.setOnAction(e -> {
            java.util.List<javafx.scene.control.Tab> toRemove = new java.util.ArrayList<>(editorTabs.getTabs());
            toRemove.remove(tab);
            for (javafx.scene.control.Tab t : toRemove) {
                editorTabs.getTabs().remove(t);
                if (t.getOnClosed() != null) t.getOnClosed().handle(null);
            }
        });
        javafx.scene.control.MenuItem closeAll = menuItem("Close All", "mdi2t-trash-can-outline");
        closeAll.setOnAction(e -> {
            java.util.List<javafx.scene.control.Tab> toRemove = new java.util.ArrayList<>(editorTabs.getTabs());
            for (javafx.scene.control.Tab t : toRemove) {
                editorTabs.getTabs().remove(t);
                if (t.getOnClosed() != null) t.getOnClosed().handle(null);
            }
        });
        // Detach tab into its own window. We reparent the tab's content (Node) from the
        // Tab into a new Stage's scene, then remove the tab. Closing the window does NOT
        // auto-reopen the tab — the user can just reopen the class from the tree.
        javafx.scene.control.MenuItem detach = menuItem("Detach Window", "mdi2o-open-in-new");
        detach.setOnAction(e -> detachTabIntoWindow(tab));
        menu.getItems().addAll(closeThis, closeOthers, closeAll,
                new javafx.scene.control.SeparatorMenuItem(), detach);
        tab.setContextMenu(menu);
    }

    /**
     * Move a tab's content into a standalone Stage. The content Node is reparented
     * (removed from the Tab, set as Scene root), which implicitly removes the tab from
     * the TabPane. When the detached window closes, we don't re-insert — the user can
     * reopen the class from the tree, same as after an ordinary close.
     */
    private void detachTabIntoWindow(javafx.scene.control.Tab tab) {
        if (tab == null) return;
        javafx.scene.Node content = tab.getContent();
        if (content == null) return;
        String title = tab.getText() == null ? "Detached" : tab.getText();
        // Drop content from the tab so Scene can claim it (Nodes can't have two parents).
        tab.setContent(null);
        // Remove the (now empty) tab from the TabPane and fire its close hook, keeping
        // the rest of the workspace bookkeeping consistent.
        editorTabs.getTabs().remove(tab);
        if (tab.getOnClosed() != null) tab.getOnClosed().handle(null);

        javafx.stage.Stage stage = new javafx.stage.Stage();
        javafx.scene.layout.BorderPane root = new javafx.scene.layout.BorderPane(content);
        javafx.scene.Scene scene = new javafx.scene.Scene(root, 900, 680);
        // Inherit the main window's stylesheet so themes and fonts match.
        var mainScene = editorTabs.getScene();
        if (mainScene != null) {
            scene.getStylesheets().addAll(mainScene.getStylesheets());
            javafx.scene.Parent mainRoot = mainScene.getRoot();
            if (mainRoot != null) {
                root.getStyleClass().addAll(mainRoot.getStyleClass());
            }
        }
        stage.setTitle("BytecodeLens \u2014 " + title);
        stage.setScene(scene);
        dev.share.bytecodelens.util.Icons.apply(stage);
        stage.show();
    }

    private void rebuildRecentMenu() {
        if (recentFilesMenu == null) return;
        recentFilesMenu.getItems().clear();
        java.util.List<java.nio.file.Path> paths = recentFiles.load();
        if (paths.isEmpty()) {
            javafx.scene.control.MenuItem empty = new javafx.scene.control.MenuItem("(empty)");
            empty.setDisable(true);
            recentFilesMenu.getItems().add(empty);
            return;
        }
        for (java.nio.file.Path p : paths) {
            javafx.scene.control.MenuItem mi = new javafx.scene.control.MenuItem(p.getFileName().toString());
            mi.setOnAction(e -> {
                jarStack.clear();
                if (backButton != null) backButton.setDisable(true);
                loadJarFile(p);
            });
            javafx.scene.control.Tooltip.install(mi.getGraphic() != null ? mi.getGraphic()
                            : new javafx.scene.layout.Region(),
                    new javafx.scene.control.Tooltip(p.toString()));
            recentFilesMenu.getItems().add(mi);
        }
        recentFilesMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        javafx.scene.control.MenuItem clear = new javafx.scene.control.MenuItem("Clear recent files");
        clear.setOnAction(e -> { recentFiles.clear(); rebuildRecentMenu(); });
        recentFilesMenu.getItems().add(clear);
    }

    /**
     * Used to populate a View → Syntax Theme submenu. The submenu was removed in favour
     * of Preferences → Appearance, but call sites remain as a safety net — no-op is
     * cheap and the method is kept for when we re-add a visible shortcut later.
     */
    private void rebuildSyntaxThemeMenu() {
        // intentional no-op
    }

    private void applySyntaxTheme(dev.share.bytecodelens.theme.SyntaxTheme theme) {
        if (theme == null) return;
        activeSyntaxTheme = theme;
        var root = classTree == null || classTree.getScene() == null
                ? null : classTree.getScene().getRoot();
        if (root != null) dev.share.bytecodelens.theme.ThemeManager.apply(root, theme);
    }

    private void loadJarFile(Path path) {
        recentFiles.add(path);
        rebuildRecentMenu();
        // Fresh file load — any previously-applied mapping is for the old jar and cannot revert to this one.
        originalJarBeforeMapping = null;
        activeMappingLabel = null;
        activeMappingModel = null;
        closeLiveAgentQuietly();
        // Drop comments unless a workspace restore is pending (restore brings its own set).
        if (pendingWorkspaceRestore == null) {
            commentStore.clear();
        }
        Task<LoadedJar> task = new Task<>() {
            @Override
            protected LoadedJar call() throws Exception {
                updateProgress(-1, 1);
                return jarLoader.load(path, this::updateProgress);
            }

            private void updateProgress(double p) {
                updateProgress(p, 1.0);
            }
        };

        progressBar.visibleProperty().bind(task.runningProperty());
        progressBar.progressProperty().bind(task.progressProperty());
        updateStatus("Loading " + path.getFileName() + "...", path.toString());

        task.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            progressBar.visibleProperty().unbind();
            progressBar.setVisible(false);
            LoadedJar jar = task.getValue();
            onJarLoaded(jar);
        });

        task.setOnFailed(e -> {
            progressBar.progressProperty().unbind();
            progressBar.visibleProperty().unbind();
            progressBar.setVisible(false);
            Throwable ex = task.getException();
            log.error("Failed to load jar", ex);
            updateStatus("Load failed: " + (ex == null ? "unknown" : ex.getMessage()), path.toString());
            // Offer the anti-tamper fallback before giving up.
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Standard load failed:\n" + (ex == null ? "unknown" : ex.getMessage())
                            + "\n\nThis often happens with anti-tamper jars (corrupt CD, "
                            + "duplicate entries, polyglot files). Try the resilient loader?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Failed to load");
            confirm.setHeaderText("Use resilient loader?");
            var ans = confirm.showAndWait();
            if (ans.isPresent() && ans.get() == ButtonType.YES) {
                loadJarFileResilient(path);
            } else {
                showError("Failed to load", ex == null ? "Unknown error" : ex.getMessage());
            }
        });

        Thread t = new Thread(task, "jar-loader");
        t.setDaemon(true);
        t.start();
    }

    /** Second-chance loader: invokes JarLoader#loadResilient after a standard load failure. */
    private void loadJarFileResilient(Path path) {
        Task<LoadedJar> task = new Task<>() {
            @Override protected LoadedJar call() throws Exception {
                updateProgress(-1, 1);
                return jarLoader.loadResilient(path, p -> updateProgress(p, 1.0));
            }
        };
        progressBar.visibleProperty().bind(task.runningProperty());
        progressBar.progressProperty().bind(task.progressProperty());
        updateStatus("Resilient load: " + path.getFileName() + "...", path.toString());
        task.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            progressBar.visibleProperty().unbind();
            progressBar.setVisible(false);
            onJarLoaded(task.getValue());
            showInfo("Resilient loader",
                    "Loaded via anti-tamper fallback. Some entries may have been skipped or deduplicated.\n"
                            + "See the log for diagnostics.");
        });
        task.setOnFailed(e -> {
            progressBar.progressProperty().unbind();
            progressBar.visibleProperty().unbind();
            progressBar.setVisible(false);
            Throwable ex = task.getException();
            log.error("Resilient load also failed", ex);
            showError("Resilient load failed",
                    ex == null ? "Unknown error" : ex.getMessage());
        });
        Thread th = new Thread(task, "jar-loader-resilient");
        th.setDaemon(true);
        th.start();
    }

    private void onJarLoaded(LoadedJar jar) {
        currentJar = jar;
        // Tear down the previous jar's background warmer — the new jar gets a fresh one
        // on first openClass() call (lazy init keeps cold starts cheap).
        if (bgDecompiler != null) {
            bgDecompiler.shutdown();
            bgDecompiler = null;
        }
        updateStartPageVisibility();
        classByFqn.clear();
        versionedClassByKey.clear();
        openTabs.clear();
        openResourceTabs.clear();
        previewTab = null;
        previewTabFqn = null;
        editorTabs.getTabs().clear();
        navBackStack.clear();
        navForwardStack.clear();
        navCurrent = null;
        for (ClassEntry c : jar.classes()) {
            classByFqn.put(c.name(), c);
        }
        for (ClassEntry c : jar.versionedClasses()) {
            versionedClassByKey.put(versionedKey(c.runtimeVersion(), c.name()), c);
        }
        populateTree(jar.classes(), jar.versionedClasses(), jar.resources());
        updateWindowTitle(jar.source().getFileName().toString());

        // Obfuscator auto-detection removed — user requested to drop it from the UI.
        // The detector code still exists for on-demand use via Analyze → Decrypt Strings
        // and related flows but is no longer invoked on every jar load.

        String sizeMb = String.format("%.1f MB", jar.totalBytes() / 1024.0 / 1024.0);
        updateStatus(String.format("Loaded %d classes + %d resources - %s - %dms",
                jar.classCount(), jar.resourceCount(), sizeMb, jar.loadTimeMs()), jar.source().toString());

        rebuildSearchIndex(jar);
        rebuildUsageIndex(jar);
        rebuildHierarchyIndex(jar);
        if (patternPanel != null) patternPanel.setJar(jar);
        refreshJarBadge();
        refreshMappingBadge();

        // Apply any pending workspace restore (set by onOpenWorkspace).
        if (pendingWorkspaceRestore != null) {
            var state = pendingWorkspaceRestore;
            pendingWorkspaceRestore = null;
            applyWorkspaceState(state);
        }
    }

    private void populateTree(List<ClassEntry> classes,
                              List<ClassEntry> versionedClasses,
                              List<dev.share.bytecodelens.model.JarResource> resources) {
        allClassesRoot.getChildren().clear();
        Map<String, TreeItem<TreeNode>> packageNodes = new TreeMap<>();
        packageNodes.put("", allClassesRoot);

        for (ClassEntry c : classes) {
            TreeItem<TreeNode> parent = ensurePackage(packageNodes, c.packageName());
            parent.getChildren().add(buildExpandableClassItem(c, c.name()));
        }

        if (versionedClasses != null && !versionedClasses.isEmpty()) {
            TreeItem<TreeNode> mrRoot = new TreeItem<>(
                    new TreeNode("[Multi-Release]", "__mr__", NodeKind.MULTI_RELEASE_ROOT));
            // Group by runtime version
            java.util.Map<Integer, List<ClassEntry>> byVersion = new java.util.TreeMap<>();
            for (ClassEntry c : versionedClasses) {
                byVersion.computeIfAbsent(c.runtimeVersion(), k -> new java.util.ArrayList<>()).add(c);
            }
            for (var entry : byVersion.entrySet()) {
                int version = entry.getKey();
                TreeItem<TreeNode> versionNode = new TreeItem<>(
                        new TreeNode("Java " + version, "__mr_v" + version + "__",
                                NodeKind.MULTI_RELEASE_VERSION));
                Map<String, TreeItem<TreeNode>> vPackageNodes = new TreeMap<>();
                vPackageNodes.put("", versionNode);
                for (ClassEntry c : entry.getValue()) {
                    TreeItem<TreeNode> parent = ensurePackageUnder(vPackageNodes, c.packageName(), versionNode);
                    parent.getChildren().add(buildExpandableClassItem(c, versionedKey(version, c.name())));
                }
                mrRoot.getChildren().add(versionNode);
            }
            allClassesRoot.getChildren().add(mrRoot);
        }

        if (resources != null && !resources.isEmpty()) {
            TreeItem<TreeNode> resourcesRoot = new TreeItem<>(
                    new TreeNode("resources", "__resources__", NodeKind.RESOURCE_FOLDER));
            Map<String, TreeItem<TreeNode>> folderNodes = new java.util.HashMap<>();
            folderNodes.put("", resourcesRoot);
            for (var r : resources) {
                TreeItem<TreeNode> parent = ensureResourceFolder(folderNodes, r.parentPath(), resourcesRoot);
                TreeItem<TreeNode> leaf = new TreeItem<>(
                        new TreeNode(r.simpleName(), r.path(), NodeKind.RESOURCE, r.kind()));
                parent.getChildren().add(leaf);
            }
            allClassesRoot.getChildren().add(resourcesRoot);
        }

        sortTree(allClassesRoot);
    }

    private TreeItem<TreeNode> ensurePackage(Map<String, TreeItem<TreeNode>> cache, String pkg) {
        return ensurePackageUnder(cache, pkg, allClassesRoot);
    }

    private TreeItem<TreeNode> ensurePackageUnder(Map<String, TreeItem<TreeNode>> cache,
                                                  String pkg, TreeItem<TreeNode> rootRef) {
        if (pkg == null || pkg.isEmpty()) return rootRef;
        TreeItem<TreeNode> existing = cache.get(pkg);
        if (existing != null) return existing;

        int dot = pkg.lastIndexOf('.');
        String parentPkg = dot < 0 ? "" : pkg.substring(0, dot);
        String leaf = dot < 0 ? pkg : pkg.substring(dot + 1);
        TreeItem<TreeNode> parent = ensurePackageUnder(cache, parentPkg, rootRef);
        TreeItem<TreeNode> node = new TreeItem<>(new TreeNode(leaf, pkg, NodeKind.PACKAGE));
        parent.getChildren().add(node);
        cache.put(pkg, node);
        return node;
    }

    private TreeItem<TreeNode> ensureResourceFolder(Map<String, TreeItem<TreeNode>> cache,
                                                    String folder, TreeItem<TreeNode> rootRef) {
        if (folder == null || folder.isEmpty()) return rootRef;
        TreeItem<TreeNode> existing = cache.get(folder);
        if (existing != null) return existing;

        int slash = folder.lastIndexOf('/');
        String parentFolder = slash < 0 ? "" : folder.substring(0, slash);
        String leaf = slash < 0 ? folder : folder.substring(slash + 1);
        TreeItem<TreeNode> parent = ensureResourceFolder(cache, parentFolder, rootRef);
        TreeItem<TreeNode> node = new TreeItem<>(new TreeNode(leaf, folder, NodeKind.RESOURCE_FOLDER));
        parent.getChildren().add(node);
        cache.put(folder, node);
        return node;
    }

    private void sortTree(TreeItem<TreeNode> node) {
        node.getChildren().sort((a, b) -> {
            int oa = kindOrder(a.getValue().kind());
            int ob = kindOrder(b.getValue().kind());
            if (oa != ob) return Integer.compare(oa, ob);
            return a.getValue().label().compareToIgnoreCase(b.getValue().label());
        });
        for (TreeItem<TreeNode> child : node.getChildren()) {
            sortTree(child);
        }
    }

    private static int kindOrder(NodeKind k) {
        return switch (k) {
            case PACKAGE -> 0;
            case RESOURCE_FOLDER -> 1;
            case CLASS -> 2;
            case RESOURCE -> 3;
            case MULTI_RELEASE_ROOT -> 4;
            case MULTI_RELEASE_VERSION -> 5;
            case ROOT -> 6;
            // Members are only ever children of CLASS, never peers of other kinds, so order
            // doesn't actually matter — return a tail value to keep the switch exhaustive.
            case FIELD, METHOD, MEMBERS_PLACEHOLDER -> 7;
        };
    }

    private void applyFilter(String query) {
        if (currentJar == null) return;
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) {
            populateTree(currentJar.classes(), currentJar.versionedClasses(), currentJar.resources());
            return;
        }
        List<ClassEntry> filtered = currentJar.classes().stream()
                .filter(c -> c.name().toLowerCase().contains(q))
                .toList();
        List<ClassEntry> filteredVersioned = currentJar.versionedClasses().stream()
                .filter(c -> c.name().toLowerCase().contains(q))
                .toList();
        var filteredResources = currentJar.resources().stream()
                .filter(r -> r.path().toLowerCase().contains(q))
                .toList();
        populateTree(filtered, filteredVersioned, filteredResources);
        expandAll(allClassesRoot);
    }

    private void expandAll(TreeItem<?> item) {
        item.setExpanded(true);
        for (TreeItem<?> ch : item.getChildren()) expandAll(ch);
    }

    /**
     * Install the Ctrl+Click-to-navigate handler on the tab's decompiled view.
     * Resolution order: class by simple name → method in current class → field in current class.
     */
    private void installSymbolNavigation(ClassEditorTab tab, ClassEntry owner) {
        tab.decompiledView().setOnSymbolClick(word -> resolveSymbol(word, owner));
        // JADX parity: plain double-click on any identifier also jumps to its declaration.
        tab.decompiledView().setOnSymbolDoubleClick(word -> resolveSymbol(word, owner));
        // JADX-style "X" find-usages hotkey — binding comes from the keymap so users can
        // remap it via Preferences → Keymap just like every other action.
        tab.decompiledView().setOnFindUsages(word -> findUsagesOfWord(word, owner));
        javafx.scene.input.KeyCombination xCombo = keymap.combinationFor(
                dev.share.bytecodelens.keymap.Actions.FIND_USAGES);
        if (xCombo != null) tab.decompiledView().setFindUsagesCombination(xCombo);
        // Smart right-click menu — shape depends on whether the word is a class, a method
        // on the current class, or a field.
        tab.decompiledView().setContextMenuBuilder(word ->
                buildSmartCodeContextMenu(tab.decompiledView(), owner, word));
        tab.setCompileFn((fileName, source) -> {
            var compiler = new dev.share.bytecodelens.compile.JavaSourceCompiler();
            var result = compiler.compile(fileName, source,
                    dev.share.bytecodelens.compile.JavaSourceCompiler.DEFAULT_RELEASE, currentJar);
            javafx.application.Platform.runLater(() -> handleCompileResult(owner, result));
            return result;
        });
    }

    private void handleCompileResult(ClassEntry owner,
                                     dev.share.bytecodelens.compile.JavaSourceCompiler.CompileResult result) {
        if (!result.success()) {
            log.info("Compile of {} failed: {} diagnostics", owner.name(), result.diagnostics().size());
            return;
        }
        byte[] newBytes = result.outputClasses().get(owner.internalName());
        if (newBytes == null && result.outputClasses().size() == 1) {
            newBytes = result.outputClasses().values().iterator().next();
        }
        if (newBytes == null) {
            showError("Compile", "Compiled " + result.outputClasses().size()
                    + " classes but none match " + owner.name() + ". Hot-reload aborted.");
            return;
        }
        if (currentJar == null) return;

        // 9d — if we're in a live-session, push the new bytes into the target JVM first.
        // The JVM rejects incompatible redefines (schema changes, signature changes); we
        // surface those to the user but still keep the in-memory workspace consistent.
        String hotswapStatus = "";
        if (liveAgent != null) {
            try {
                boolean ok = liveAgent.redefine(owner.name(), newBytes);
                hotswapStatus = ok ? " \u2192 hotswapped in pid " + liveAgentPid
                                   : " (hotswap returned false)";
            } catch (java.io.IOException ex) {
                // Typical failure: "class redefinition failed: attempted to change the schema"
                showError("Hotswap failed",
                        "The target JVM rejected the redefine:\n" + ex.getMessage()
                                + "\n\nThis usually means you changed method/field signatures "
                                + "(only method bodies are hot-swappable). The in-memory workspace "
                                + "will still show your change.");
                hotswapStatus = " (hotswap REJECTED — JVM only accepts body-only edits)";
            }
        }

        LoadedJar updated = replaceClassBytes(currentJar, owner.internalName(), newBytes);
        if (updated == null) {
            showError("Compile", "Failed to swap bytes for " + owner.name());
            return;
        }
        Map<String, ClassEditorTab> tabsBefore = new HashMap<>(openTabs);
        onJarLoaded(updated);
        for (String fqn : tabsBefore.keySet()) {
            openClass(fqn, null, ClassEditorTab.View.DECOMPILED);
        }
        updateStatus("Hot-reloaded " + owner.name() + " (" + newBytes.length + " bytes)"
                + hotswapStatus, "");
    }

    /** Produce a LoadedJar identical to {@code jar} except that one class has new bytes. */
    private LoadedJar replaceClassBytes(LoadedJar jar, String internalName, byte[] newBytes) {
        java.util.List<ClassEntry> newClasses = new java.util.ArrayList<>(jar.classes().size());
        boolean replaced = false;
        for (ClassEntry c : jar.classes()) {
            if (c.internalName().equals(internalName)) {
                newClasses.add(new dev.share.bytecodelens.service.ClassAnalyzer()
                        .analyze(newBytes, c.runtimeVersion()));
                replaced = true;
            } else {
                newClasses.add(c);
            }
        }
        if (!replaced) return null;
        return new LoadedJar(jar.source(), java.util.List.copyOf(newClasses),
                jar.versionedClasses(), jar.resources(), jar.totalBytes(), jar.loadTimeMs());
    }

    /**
     * Build a right-click menu whose items match the <em>kind</em> of the symbol under the
     * caret: a class name gets "Go to class / Find references / Export" etc, a method name
     * gets "Go to method / Call graph / Copy Frida hook", and so on. Falls back to plain
     * Copy/Select-all when the word isn't a recognisable symbol.
     */
    private javafx.scene.control.ContextMenu buildSmartCodeContextMenu(
            dev.share.bytecodelens.ui.views.CodeView view, ClassEntry owner, String word) {

        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();

        // Classify the word.
        ClassEntry classHit = null;
        int classMatches = 0;
        if (currentJar != null) {
            for (ClassEntry c : currentJar.classes()) {
                if (word.equals(c.simpleName())) {
                    classMatches++;
                    if (classHit == null) classHit = c;
                }
            }
        }
        boolean methodHit = false;
        MethodEntry methodEntry = null;
        FieldEntry fieldEntry = null;
        try {
            for (var m : analyzer.methods(owner.bytes())) {
                if (m.name().equals(word)) { methodHit = true; methodEntry = m; break; }
            }
            if (!methodHit) {
                for (var f : analyzer.fields(owner.bytes())) {
                    if (f.name().equals(word)) { fieldEntry = f; break; }
                }
            }
        } catch (Exception ignored) {}

        // Header: icon + symbol name (disabled, just for orientation).
        String iconLiteral;
        String headerLabel = word;
        if (classHit != null) {
            iconLiteral = "mdi2c-cube-outline";
        } else if (methodHit) {
            iconLiteral = "mdi2f-function";
            headerLabel = word + methodEntry.descriptor();
        } else if (fieldEntry != null) {
            iconLiteral = "mdi2a-alpha-f-box-outline";
            headerLabel = word + ":" + fieldEntry.descriptor();
        } else {
            iconLiteral = null;
        }
        if (iconLiteral != null) {
            javafx.scene.control.MenuItem header = new javafx.scene.control.MenuItem(headerLabel);
            header.setGraphic(new org.kordamp.ikonli.javafx.FontIcon(iconLiteral));
            header.setDisable(true);
            menu.getItems().addAll(header, new javafx.scene.control.SeparatorMenuItem());
        }

        // Kind-specific actions.
        if (classHit != null) {
            final ClassEntry target = classHit;
            final int hits = classMatches;
            menu.getItems().addAll(
                    menuItem("Go to class", "mdi2a-arrow-right-bold", ev -> {
                        if (hits > 1) showAmbiguousClassPicker(word);
                        else openClass(target.name(), null, ClassEditorTab.View.DECOMPILED);
                    }),
                    menuItem("Find references", "mdi2m-magnify-scan", ev ->
                            showUsages(new UsageTarget.Class(target.internalName()))),
                    menuItem("Show hierarchy", "mdi2f-file-tree-outline", ev ->
                            showHierarchy(target.internalName())),
                    menuItem("Show in call graph", "mdi2g-graph-outline", ev ->
                            showCallGraphForClass(target.internalName())),
                    menuItem("Copy FQN", "mdi2c-content-copy", ev ->
                            ClipboardUtil.copyToClipboard(target.name())));
        } else if (methodHit) {
            final MethodEntry m = methodEntry;
            final String ownerInternal = owner.internalName();
            menu.getItems().addAll(
                    menuItem("Go to method", "mdi2a-arrow-right-bold", ev ->
                            openClass(owner.name(),
                                    HighlightRequest.literal(m.name(), -1),
                                    ClassEditorTab.View.DECOMPILED)),
                    menuItem("Find references", "mdi2m-magnify-scan", ev ->
                            showUsages(new UsageTarget.Method(ownerInternal, m.name(), m.descriptor()))),
                    menuItem("Show in call graph", "mdi2g-graph-outline", ev ->
                            showCallGraphForMethod(ownerInternal, m.name(), m.descriptor())),
                    menuItem("Copy signature", "mdi2c-content-copy", ev ->
                            ClipboardUtil.copyToClipboard(m.name() + m.descriptor())),
                    menuItem("Copy as Frida hook", "mdi2l-language-javascript", ev ->
                            ClipboardUtil.copyToClipboard(hookSnippetGenerator.frida(
                                    ownerInternal, m.name(), m.descriptor(), m.access()))),
                    menuItem("Copy as Xposed hook", "mdi2a-android", ev ->
                            ClipboardUtil.copyToClipboard(hookSnippetGenerator.xposed(
                                    ownerInternal, m.name(), m.descriptor(), m.access()))));
        } else if (fieldEntry != null) {
            final FieldEntry f = fieldEntry;
            final String ownerInternal = owner.internalName();
            menu.getItems().addAll(
                    menuItem("Go to field", "mdi2a-arrow-right-bold", ev ->
                            openClass(owner.name(),
                                    HighlightRequest.literal(f.name(), -1),
                                    ClassEditorTab.View.DECOMPILED)),
                    menuItem("Find references", "mdi2m-magnify-scan", ev ->
                            showUsages(new UsageTarget.Field(ownerInternal, f.name(), f.descriptor()))),
                    menuItem("Copy signature", "mdi2c-content-copy", ev ->
                            ClipboardUtil.copyToClipboard(f.name() + ":" + f.descriptor())));
        }

        // Search submenu available regardless of kind.
        javafx.scene.control.Menu searchMenu = new javafx.scene.control.Menu("Search");
        searchMenu.setGraphic(new org.kordamp.ikonli.javafx.FontIcon("mdi2m-magnify"));
        searchMenu.getItems().addAll(
                menuItem("Names (classes/methods/fields)", null, ev ->
                        openGlobalSearch(word, dev.share.bytecodelens.search.SearchMode.NAMES)),
                menuItem("Strings", null, ev ->
                        openGlobalSearch(word, dev.share.bytecodelens.search.SearchMode.STRINGS)),
                menuItem("Bytecode", null, ev ->
                        openGlobalSearch(word, dev.share.bytecodelens.search.SearchMode.BYTECODE)));

        if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }
        menu.getItems().add(searchMenu);
        menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

        javafx.scene.control.MenuItem copy = menuItem("Copy", "mdi2c-content-copy",
                ev -> view.copySelectedToClipboard());
        copy.setDisable(view.selectedText() == null || view.selectedText().isEmpty());
        javafx.scene.control.MenuItem selectAll = menuItem("Select all", null,
                ev -> view.selectAllText());
        menu.getItems().addAll(copy, selectAll);

        return menu;
    }

    private void openGlobalSearch(String word, dev.share.bytecodelens.search.SearchMode mode) {
        if (searchOverlay == null) initSearchOverlay();
        if (searchOverlay == null) return;
        searchOverlay.setVisible(true);
        searchOverlay.setManaged(true);
        searchOverlay.prefillQuery(word);
        searchOverlay.focusSearchField();
        // Mode switching is beyond current SearchOverlay API; the prefill is still useful.
    }

    private void resolveSymbol(String word, ClassEntry owner) {
        if (word == null || word.isEmpty() || currentJar == null) return;
        // 1) Class by simple name — unique match preferred; fall back to first if ambiguous.
        ClassEntry hitClass = null;
        int classHits = 0;
        for (ClassEntry c : currentJar.classes()) {
            if (word.equals(c.simpleName())) {
                classHits++;
                if (hitClass == null) hitClass = c;
            }
        }
        if (hitClass != null && classHits == 1) {
            openClass(hitClass.name(), null, ClassEditorTab.View.DECOMPILED);
            return;
        }
        // 2) Method in current class
        try {
            var methods = analyzer.methods(owner.bytes());
            boolean hasMethod = methods.stream().anyMatch(m -> m.name().equals(word));
            if (hasMethod) {
                openClass(owner.name(), HighlightRequest.literal(word, -1), ClassEditorTab.View.DECOMPILED);
                return;
            }
            // 3) Field in current class
            var fields = analyzer.fields(owner.bytes());
            boolean hasField = fields.stream().anyMatch(f -> f.name().equals(word));
            if (hasField) {
                openClass(owner.name(), HighlightRequest.literal(word, -1), ClassEditorTab.View.DECOMPILED);
                return;
            }
        } catch (Exception ignored) {
        }
        // 4) Ambiguous class match — offer a picker if we found anything.
        if (classHits > 1) {
            showAmbiguousClassPicker(word);
        }
    }

    /** When Ctrl+Click matches multiple classes by simple name, let the user choose. */
    private void showAmbiguousClassPicker(String simpleName) {
        if (currentJar == null) return;
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (ClassEntry c : currentJar.classes()) {
            if (simpleName.equals(c.simpleName())) candidates.add(c.name());
        }
        if (candidates.isEmpty()) return;
        javafx.scene.control.ChoiceDialog<String> dlg =
                new javafx.scene.control.ChoiceDialog<>(candidates.get(0), candidates);
        dlg.setTitle("Go to declaration");
        dlg.setHeaderText("Multiple classes named " + simpleName);
        dlg.setContentText("Class:");
        dlg.showAndWait().ifPresent(fqn -> openClass(fqn, null, ClassEditorTab.View.DECOMPILED));
    }

    private void openClass(String fqn) {
        openClass(fqn, null, ClassEditorTab.View.DECOMPILED);
    }

    private void openClass(String fqn, HighlightRequest highlight, ClassEditorTab.View view) {
        openClassInternal(fqn, highlight, view, /*preview=*/false);
    }

    /**
     * Open a class in the reusable preview tab (single-click / keyboard arrow in tree).
     * If the class is already open as a pinned tab, just activate that tab — preview is
     * not created on top of a pinned one.
     */
    private void openClassPreview(String fqn) {
        if (fqn == null) return;
        // If already pinned — just switch. Don't spawn a preview tab.
        ClassEditorTab pinned = openTabs.get(fqn);
        if (pinned != null) {
            editorTabs.getSelectionModel().select(pinned.tab());
            ClassEntry entry = resolveEntry(fqn);
            if (entry != null) showClassDetails(entry);
            return;
        }
        openClassInternal(fqn, null, null, /*preview=*/true);
    }

    /** Detach the preview tab (if it shows this fqn) and re-register it as a pinned regular tab. */
    private void promotePreviewToPinned(String fqn) {
        if (fqn == null) return;
        // Already pinned — nothing to do.
        if (openTabs.containsKey(fqn)) {
            editorTabs.getSelectionModel().select(openTabs.get(fqn).tab());
            return;
        }
        if (previewTab != null && fqn.equals(previewTabFqn)) {
            ClassEditorTab promoted = previewTab;
            String key = previewTabFqn;
            previewTab = null;
            previewTabFqn = null;
            promoted.tab().getStyleClass().remove("preview-tab");
            String plainTitle = promoted.tab().getText();
            if (plainTitle != null && plainTitle.endsWith(" *")) {
                promoted.tab().setText(plainTitle.substring(0, plainTitle.length() - 2));
            }
            promoted.tab().setOnClosed(e -> openTabs.remove(key));
            openTabs.put(key, promoted);
        } else {
            // Not currently in preview — open as a fresh pinned tab.
            openClassInternal(fqn, null, null, /*preview=*/false);
        }
    }

    private void openClassInternal(String fqn, HighlightRequest highlight,
                                   ClassEditorTab.View view, boolean preview) {
        if (fqn == null) return;
        ClassEntry entry = resolveEntry(fqn);
        if (entry == null) return;

        // Navigation history — only for real navigation, not passive preview
        if (!preview) {
            if (!navSuppress && navCurrent != null && !navCurrent.equals(fqn)) {
                navBackStack.push(navCurrent);
                navForwardStack.clear();
            }
            navCurrent = fqn;
        }

        ClassEditorTab tab;
        if (preview) {
            // Reuse existing preview slot if any; otherwise create one.
            if (previewTab != null) {
                // Replace target: close old preview tab cleanly.
                editorTabs.getTabs().remove(previewTab.tab());
            }
            tab = new ClassEditorTab(entry, bytecodePrinter, decompiler);
            installSymbolNavigation(tab, entry);
            tab.tab().getStyleClass().add("preview-tab");
            tab.tab().setText(tab.tab().getText() + " *");
            final String key = fqn;
            tab.tab().setOnClosed(e -> {
                if (previewTab != null && previewTab.tab() == tab.tab()) {
                    previewTab = null;
                    previewTabFqn = null;
                }
                openTabs.remove(key);
            });
            editorTabs.getTabs().add(tab.tab());
            editorTabs.getSelectionModel().select(tab.tab());
            previewTab = tab;
            previewTabFqn = fqn;
            showClassDetails(entry);
        } else {
            ClassEditorTab existing = openTabs.get(fqn);
            if (existing != null) {
                editorTabs.getSelectionModel().select(existing.tab());
                showClassDetails(entry);
                tab = existing;
            } else if (previewTab != null && fqn.equals(previewTabFqn)) {
                // Promote the current preview to pinned without rebuilding the tab.
                tab = previewTab;
                previewTab = null;
                previewTabFqn = null;
                tab.tab().getStyleClass().remove("preview-tab");
                String plainTitle = tab.tab().getText();
                if (plainTitle != null && plainTitle.endsWith(" *")) {
                    tab.tab().setText(plainTitle.substring(0, plainTitle.length() - 2));
                }
                final String key = fqn;
                tab.tab().setOnClosed(e -> openTabs.remove(key));
                openTabs.put(key, tab);
                editorTabs.getSelectionModel().select(tab.tab());
                showClassDetails(entry);
            } else {
                tab = new ClassEditorTab(entry, bytecodePrinter, decompiler);
                installSymbolNavigation(tab, entry);
                final String key = fqn;
                tab.tab().setOnClosed(e -> openTabs.remove(key));
                editorTabs.getTabs().add(tab.tab());
                editorTabs.getSelectionModel().select(tab.tab());
                openTabs.put(fqn, tab);
                showClassDetails(entry);
            }
        }

        if (highlight != null && !highlight.isEmpty()) {
            tab.applyHighlight(highlight, view == null ? ClassEditorTab.View.DECOMPILED : view);
        }

        // Background warmup: after a class opens, pre-decompile a handful of nearest
        // siblings in the same package so the next click on the tree is instant.
        // Cap is small (~24) — fully warming a 50k-class jar would saturate I/O for
        // little gain (LRU cache is bounded anyway).
        warmNeighbourhood(entry);
    }

    /**
     * Submit the other classes in the same package as {@code anchor} to the
     * background decompiler. Neighbourhood cap + enable flag come from app settings.
     */
    private void warmNeighbourhood(ClassEntry anchor) {
        if (anchor == null || currentJar == null) return;
        var settings = dev.share.bytecodelens.settings.AppSettingsStore.getInstance().get();
        if (!settings.decompiler.backgroundWarmupEnabled) return;
        if (bgDecompiler == null) {
            // Use the same Auto chain the UI uses by default — keeps cache keys aligned.
            dev.share.bytecodelens.decompile.ClassDecompiler engine =
                    new dev.share.bytecodelens.decompile.AutoDecompiler(java.util.List.of(
                            new dev.share.bytecodelens.decompile.CfrDecompiler(),
                            new dev.share.bytecodelens.decompile.VineflowerDecompiler(),
                            new dev.share.bytecodelens.decompile.ProcyonDecompiler(),
                            new dev.share.bytecodelens.decompile.FallbackDecompiler()));
            bgDecompiler = new dev.share.bytecodelens.decompile.BackgroundDecompiler(
                    ClassEditorTab.sharedCache(), engine);
        }
        String pkg = anchor.packageName();
        int cap = Math.max(1, settings.decompiler.warmupNeighborhoodSize);
        java.util.List<ClassEntry> neighbours = new java.util.ArrayList<>();
        for (ClassEntry c : currentJar.classes()) {
            if (c == anchor) continue;
            if (!java.util.Objects.equals(pkg, c.packageName())) continue;
            neighbours.add(c);
            if (neighbours.size() >= cap) break;
        }
        if (!neighbours.isEmpty()) bgDecompiler.warm(neighbours);
    }

    private void updateWindowTitle(String jarName) {
        if (classTree == null || classTree.getScene() == null) return;
        var window = classTree.getScene().getWindow();
        if (window instanceof javafx.stage.Stage st) {
            st.setTitle(jarName == null ? "BytecodeLens" : "BytecodeLens \u2014 " + jarName);
        }
    }

    private void navigateBack() {
        if (navBackStack.isEmpty()) return;
        if (navCurrent != null) navForwardStack.push(navCurrent);
        String target = navBackStack.pop();
        navSuppress = true;
        try {
            openClass(target);
        } finally {
            navSuppress = false;
        }
        navCurrent = target;
    }

    private void navigateForward() {
        if (navForwardStack.isEmpty()) return;
        if (navCurrent != null) navBackStack.push(navCurrent);
        String target = navForwardStack.pop();
        navSuppress = true;
        try {
            openClass(target);
        } finally {
            navSuppress = false;
        }
        navCurrent = target;
    }

    private void showGotoClassDialog() {
        if (currentJar == null) return;
        javafx.scene.control.TextInputDialog dlg = new javafx.scene.control.TextInputDialog();
        dlg.setTitle("Go to Class");
        dlg.setHeaderText("Type a class name (fuzzy match)");
        dlg.setContentText("Class:");
        dlg.showAndWait().ifPresent(query -> {
            if (query == null || query.isBlank()) return;
            String q = query.toLowerCase();
            String bestMatch = null;
            int bestScore = Integer.MAX_VALUE;
            for (String fqn : classByFqn.keySet()) {
                String lower = fqn.toLowerCase();
                if (!lower.contains(q)) continue;
                int score = lower.length() - q.length();
                if (score < bestScore) {
                    bestScore = score;
                    bestMatch = fqn;
                }
            }
            if (bestMatch != null) openClass(bestMatch);
        });
    }

    private void openResource(String path, JarResource.ResourceKind kind, String simpleName) {
        openResource(path, kind, simpleName, null);
    }

    private void openResource(String path, JarResource.ResourceKind kind, String simpleName, HighlightRequest highlight) {
        if (path == null || currentJar == null) return;

        if (kind == JarResource.ResourceKind.NESTED_JAR
                || kind == JarResource.ResourceKind.NESTED_WAR
                || kind == JarResource.ResourceKind.NESTED_ZIP) {
            openNestedJar(path, simpleName);
            return;
        }

        ResourceEditorTab existing = openResourceTabs.get(path);
        if (existing != null) {
            editorTabs.getSelectionModel().select(existing.tab());
            if (highlight != null && !highlight.isEmpty()) existing.applyHighlight(highlight);
            return;
        }

        try {
            byte[] bytes = resourceReader.read(currentJar.source(), path);
            JarResource res = new JarResource(path, simpleName, bytes.length,
                    kind == null ? JarResource.detect(path) : kind);
            ResourceEditorTab tab = new ResourceEditorTab(res, bytes);
            tab.tab().setOnClosed(e -> openResourceTabs.remove(path));
            editorTabs.getTabs().add(tab.tab());
            editorTabs.getSelectionModel().select(tab.tab());
            openResourceTabs.put(path, tab);
            classInfoBox.getChildren().clear();
            addInfoRow("Resource", simpleName);
            addInfoRow("Path", path);
            addInfoRow("Kind", kind == null ? "Unknown" : kind.name());
            addInfoRow("Size", bytes.length + " bytes");

            if (highlight != null && !highlight.isEmpty()) {
                tab.applyHighlight(highlight);
            }
        } catch (Exception ex) {
            log.warn("Failed to read resource {}: {}", path, ex.getMessage());
            showError("Failed to open resource", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private void showClassDetails(ClassEntry entry) {
        currentClassInternalName = entry.internalName();
        classInfoBox.getChildren().clear();
        // If the class has a user comment, show it at the very top.
        String classComment = commentStore.get(
                dev.share.bytecodelens.comments.CommentStore.classKey(entry.name()));
        if (classComment != null && !classComment.isBlank()) {
            addCommentRow("Comment", classComment);
        }
        if (entry.isModule()) {
            showModuleDetails(entry);
        } else {
            showRegularClassDetails(entry);
        }

        try {
            List<ConstantPoolEntry> cp = cpReader.read(entry.bytes());
            constantPoolTable.setItems(FXCollections.observableArrayList(cp));
        } catch (Exception ex) {
            log.warn("Failed to read constant pool: {}", ex.getMessage());
            constantPoolTable.getItems().clear();
        }

        methodsTable.setItems(FXCollections.observableArrayList(analyzer.methods(entry.bytes())));
        fieldsTable.setItems(FXCollections.observableArrayList(analyzer.fields(entry.bytes())));
        fillDataInspector(entry.bytes());

        // Show member-comments for this class at the bottom of the info panel.
        showMemberCommentsFor(entry);
    }

    private void showMemberCommentsFor(ClassEntry entry) {
        String fqn = entry.name();
        java.util.List<String> matchingComments = new java.util.ArrayList<>();
        String methodPrefix = "METHOD:" + fqn + ":";
        String fieldPrefix = "FIELD:" + fqn + ":";
        for (var e : commentStore.all().entrySet()) {
            if (e.getKey().startsWith(methodPrefix) || e.getKey().startsWith(fieldPrefix)) {
                String rest = e.getKey().substring(e.getKey().indexOf(':') + 1);
                // rest = fqn:name:desc — show just name:desc portion
                int firstColon = rest.indexOf(':');
                String nameDesc = firstColon < 0 ? rest : rest.substring(firstColon + 1);
                String kind = e.getKey().startsWith("METHOD:") ? "method" : "field";
                matchingComments.add(kind + " " + nameDesc + ":\n" + e.getValue());
            }
        }
        if (matchingComments.isEmpty()) return;
        Label header = new Label("Member comments (" + matchingComments.size() + ")");
        header.getStyleClass().add("info-key");
        classInfoBox.getChildren().add(header);
        for (String cm : matchingComments) {
            Label lbl = new Label(cm);
            lbl.getStyleClass().addAll("info-value", "user-comment");
            lbl.setWrapText(true);
            ClipboardUtil.installLabelCopy(lbl);
            classInfoBox.getChildren().add(lbl);
        }
    }

    private void showRegularClassDetails(ClassEntry entry) {
        addInfoRow("Name", entry.simpleName());
        addInfoRow("Package", entry.packageName().isEmpty() ? "(default)" : entry.packageName());
        addInfoRow("Full name", entry.name());
        addLinkedInfoRow("Super",
                entry.superName() == null ? null : entry.superName().replace('/', '.'));
        addLinkedInterfaceRow("Interfaces", entry.interfaces());
        addInfoRow("Version", AccessFlags.javaVersion(entry.majorVersion())
                + "  (" + entry.majorVersion() + "." + entry.minorVersion() + ")");
        addInfoRow("Access", String.join(" ", AccessFlags.forClass(entry.access())));
        addInfoRow("Methods", String.valueOf(entry.methodCount()));
        addInfoRow("Fields", String.valueOf(entry.fieldCount()));
        addInfoRow("CP size", String.valueOf(entry.constantPoolSize()));
        addInfoRow("Size", entry.size() + " bytes");
        if (entry.sourceFile() != null) {
            addInfoRow("Source file", entry.sourceFile());
        }
    }

    private void showModuleDetails(ClassEntry entry) {
        dev.share.bytecodelens.model.ModuleInfo m = entry.moduleInfo();
        addInfoRow("Kind", "Module descriptor");
        addInfoRow("Module", m.name());
        if (m.version() != null && !m.version().isEmpty()) {
            addInfoRow("Version", m.version());
        }
        String modFlags = String.join(" ", AccessFlags.forModule(m.access()));
        addInfoRow("Access", modFlags.isEmpty() ? "(none)" : modFlags);
        addInfoRow("Class version", AccessFlags.javaVersion(entry.majorVersion())
                + "  (" + entry.majorVersion() + "." + entry.minorVersion() + ")");
        addInfoRow("Size", entry.size() + " bytes");
        if (entry.sourceFile() != null) {
            addInfoRow("Source file", entry.sourceFile());
        }

        addModuleListRow("Requires", m.requires().stream()
                .map(r -> {
                    String flags = String.join(" ", AccessFlags.forModuleRequires(r.access()));
                    String base = flags.isEmpty() ? r.module() : flags + " " + r.module();
                    return r.version() == null ? base : base + " @" + r.version();
                })
                .toList());
        addModuleListRow("Exports", m.exports().stream()
                .map(e -> {
                    String flags = String.join(" ", AccessFlags.forModuleExports(e.access()));
                    String base = flags.isEmpty() ? e.packageName() : flags + " " + e.packageName();
                    return e.modules().isEmpty() ? base : base + " to " + String.join(", ", e.modules());
                })
                .toList());
        addModuleListRow("Opens", m.opens().stream()
                .map(o -> {
                    String flags = String.join(" ", AccessFlags.forModuleExports(o.access()));
                    String base = flags.isEmpty() ? o.packageName() : flags + " " + o.packageName();
                    return o.modules().isEmpty() ? base : base + " to " + String.join(", ", o.modules());
                })
                .toList());
        addModuleListRow("Uses", m.uses().stream()
                .map(u -> u.replace('/', '.'))
                .toList());
        addModuleListRow("Provides", m.provides().stream()
                .map(p -> p.service().replace('/', '.') + " with "
                        + String.join(", ", p.providers().stream().map(s -> s.replace('/', '.')).toList()))
                .toList());
    }

    private void addModuleListRow(String key, java.util.List<String> items) {
        Label k = new Label(key + "  (" + items.size() + ")");
        k.getStyleClass().add("info-key");
        VBox container = new VBox(2);
        if (items.isEmpty()) {
            Label dash = new Label("-");
            dash.getStyleClass().add("info-value");
            container.getChildren().add(dash);
        } else {
            for (String it : items) {
                Label lbl = new Label(it);
                lbl.getStyleClass().add("info-value");
                lbl.setWrapText(true);
                ClipboardUtil.installLabelCopy(lbl);
                container.getChildren().add(lbl);
            }
        }
        VBox row = new VBox(2, k, container);
        classInfoBox.getChildren().add(row);
    }

    /** A special info row styled as a user note — muted background, wrap, italic-free. */
    private void addCommentRow(String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("info-key");
        Label v = new Label(value);
        v.getStyleClass().addAll("info-value", "user-comment");
        v.setWrapText(true);
        ClipboardUtil.installLabelCopy(v);
        VBox row = new VBox(2, k, v);
        classInfoBox.getChildren().add(row);
    }

    private void addInfoRow(String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("info-key");
        Label v = new Label(value);
        v.getStyleClass().add("info-value");
        v.setWrapText(true);
        ClipboardUtil.installLabelCopy(v);
        VBox row = new VBox(2, k, v);
        classInfoBox.getChildren().add(row);
    }

    private void addLinkedInfoRow(String key, String fqn) {
        Label k = new Label(key);
        k.getStyleClass().add("info-key");
        Label v;
        if (fqn == null) {
            v = new Label("-");
            v.getStyleClass().add("info-value");
        } else if (classByFqn.containsKey(fqn)) {
            v = new Label(fqn);
            v.getStyleClass().addAll("info-value", "info-value-link");
            v.setOnMouseClicked(e -> { if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) openClass(fqn); });
        } else {
            v = new Label(fqn);
            v.getStyleClass().add("info-value");
        }
        ClipboardUtil.installLabelCopy(v);
        VBox row = new VBox(2, k, v);
        classInfoBox.getChildren().add(row);
    }

    private void addLinkedInterfaceRow(String key, java.util.List<String> interfaces) {
        Label k = new Label(key);
        k.getStyleClass().add("info-key");
        javafx.scene.layout.FlowPane pane = new javafx.scene.layout.FlowPane(6, 4);
        if (interfaces.isEmpty()) {
            Label dash = new Label("-");
            dash.getStyleClass().add("info-value");
            pane.getChildren().add(dash);
        } else {
            for (String iface : interfaces) {
                String fqn = iface.replace('/', '.');
                Label lbl;
                if (classByFqn.containsKey(fqn)) {
                    lbl = new Label(fqn);
                    lbl.getStyleClass().addAll("info-value", "info-value-link");
                    lbl.setOnMouseClicked(e -> { if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) openClass(fqn); });
                } else {
                    lbl = new Label(fqn);
                    lbl.getStyleClass().add("info-value");
                }
                ClipboardUtil.installLabelCopy(lbl);
                pane.getChildren().add(lbl);
            }
        }
        VBox row = new VBox(2, k, pane);
        classInfoBox.getChildren().add(row);
    }

    private void fillDataInspectorEmpty() {
        dataInspectorBox.getChildren().clear();
        Label l = new Label("Open a class to see byte-level info");
        l.getStyleClass().add("info-key");
        dataInspectorBox.getChildren().add(l);
    }

    private void fillDataInspector(byte[] bytes) {
        dataInspectorBox.getChildren().clear();
        addInspectorRow("Magic", String.format("0x%02X%02X%02X%02X",
                bytes[0] & 0xff, bytes[1] & 0xff, bytes[2] & 0xff, bytes[3] & 0xff));
        int minor = ((bytes[4] & 0xff) << 8) | (bytes[5] & 0xff);
        int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        addInspectorRow("Minor", String.valueOf(minor));
        addInspectorRow("Major", major + " (" + AccessFlags.javaVersion(major) + ")");
        int cpCount = ((bytes[8] & 0xff) << 8) | (bytes[9] & 0xff);
        addInspectorRow("CP count", String.valueOf(cpCount));
        addInspectorRow("Total bytes", String.valueOf(bytes.length));
    }

    private void addInspectorRow(String key, String value) {
        HBox row = new HBox(8);
        Label k = new Label(key);
        k.getStyleClass().add("info-key");
        k.setMinWidth(90);
        Label v = new Label(value);
        v.getStyleClass().add("info-value");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(k, spacer, v);
        dataInspectorBox.getChildren().add(row);
    }

    private void updateStatus(String left, String right) {
        statusLeft.setText(left);
        statusRight.setText(right);
    }

    // ---- Status bar badges ------------------------------------------------

    /** Show/hide and fill the jar badge from the currently loaded jar. */
    private void refreshJarBadge() {
        if (jarBadge == null) return;
        if (currentJar == null) {
            jarBadge.setVisible(false); jarBadge.setManaged(false);
            return;
        }
        int classes = currentJar.classCount() + currentJar.versionedClassCount();
        double mb = currentJar.totalBytes() / 1024.0 / 1024.0;
        jarBadgeLabel.setText(String.format(java.util.Locale.ROOT, "%d classes · %.1f MB", classes, mb));
        jarBadge.setVisible(true); jarBadge.setManaged(true);
    }

    /** Show/hide the active-mapping badge. */
    private void refreshMappingBadge() {
        if (mappingBadge == null) return;
        if (activeMappingLabel == null || activeMappingLabel.isEmpty()) {
            mappingBadge.setVisible(false); mappingBadge.setManaged(false);
            return;
        }
        mappingBadgeLabel.setText(activeMappingLabel);
        mappingBadge.setVisible(true); mappingBadge.setManaged(true);
    }

    /** Refresh decompile cache stats — called periodically and after relevant events. */
    private void refreshCacheBadge() {
        if (cacheBadge == null) return;
        var cache = dev.share.bytecodelens.ui.views.ClassEditorTab.sharedCache();
        long hits = cache.hits();
        long misses = cache.misses();
        if (hits + misses == 0) {
            cacheBadge.setVisible(false); cacheBadge.setManaged(false);
            return;
        }
        int pct = (int) Math.round(100.0 * hits / Math.max(1, hits + misses));
        cacheBadgeLabel.setText("Cache " + pct + "% · " + cache.size() + "/" + cache.capacity());
        cacheBadge.setVisible(true); cacheBadge.setManaged(true);
    }

    private void showError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(title);
        a.showAndWait();
    }

    private void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(title);
        a.showAndWait();
    }

    @FXML
    public void onFindInJar() {
        showSearchOverlay();
    }

    @FXML
    public void onCompareWith() {
        if (currentJar == null) {
            showInfo("No jar loaded", "Open a jar first, then Compare with another.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select second jar for comparison");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Java archives", "*.jar", "*.war", "*.zip"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        Stage stage = (Stage) classTree.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        LoadedJar jarA = currentJar;
        updateStatus("Loading second jar for diff...", file.toString());

        javafx.concurrent.Task<JarDiffResult> task = new javafx.concurrent.Task<>() {
            @Override
            protected JarDiffResult call() throws Exception {
                LoadedJar jarB = jarLoader.load(file.toPath(), p -> {});
                return new JarDiffer().diff(jarA, jarB);
            }
        };
        task.setOnSucceeded(e -> {
            updateStatus("Diff ready", file.toString());
            JarDiffStage diffStage = new JarDiffStage(task.getValue(), darkTheme);
            diffStage.show();
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            updateStatus("Diff failed: " + (ex == null ? "unknown" : ex.getMessage()), file.toString());
            showError("Failed to compare", ex == null ? "Unknown error" : ex.getMessage());
        });

        Thread t = new Thread(task, "jar-differ");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onClearHighlights() {
        for (ClassEditorTab t : openTabs.values()) t.clearHighlights();
        for (ResourceEditorTab t : openResourceTabs.values()) t.clearHighlights();
    }

    private void openNestedJar(String entryPath, String simpleName) {
        if (currentJar == null) return;
        try {
            java.nio.file.Path extracted = nestedExtractor.extract(currentJar.source(), entryPath);

            JarStackEntry parentEntry = new JarStackEntry(currentJar.source(),
                    currentJar.source().getFileName().toString());
            jarStack.push(parentEntry);
            if (backButton != null) backButton.setDisable(false);

            loadJarFile(extracted);
            updateBreadcrumb(simpleName);
        } catch (Exception ex) {
            log.error("Failed to open nested jar {}: {}", entryPath, ex.getMessage());
            showError("Failed to open nested archive",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    /** Toolbar wrapper for the tree-history back navigation. */
    @FXML
    public void onNavBack() { navigateBack(); }

    /** Toolbar wrapper for the tree-history forward navigation. */
    @FXML
    public void onNavForward() { navigateForward(); }

    /** Toolbar wrapper for the "Go to Class" (Ctrl+N) fuzzy dialog. */
    @FXML
    public void onGotoClass() { showGotoClassDialog(); }

    @FXML
    public void onGoBackToParentJar() {
        if (jarStack.isEmpty()) {
            showInfo("No parent archive", "This is the top-level archive.");
            return;
        }
        JarStackEntry parent = jarStack.pop();
        if (backButton != null) backButton.setDisable(jarStack.isEmpty());
        loadJarFile(parent.path());
        if (jarStack.isEmpty()) {
            statusRight.setText(parent.path().toString());
        } else {
            updateBreadcrumb(parent.displayLabel());
        }
    }

    private void updateBreadcrumb(String nestedLabel) {
        if (nestedLabel == null || jarStack.isEmpty()) {
            // top-level, no crumb needed
            return;
        }
        StringBuilder crumb = new StringBuilder();
        var iter = jarStack.descendingIterator();
        while (iter.hasNext()) {
            crumb.append(iter.next().displayLabel()).append(" / ");
        }
        crumb.append(nestedLabel);
        statusRight.setText(crumb.toString());
    }

    private void setupPatternPanel() {
        patternPanel = new PatternPanel();
        patternPanel.setOnOpen(r -> {
            HighlightRequest hl = switch (r.kind()) {
                case METHOD, FIELD -> r.memberName() == null
                        ? HighlightRequest.none()
                        : HighlightRequest.literal(r.memberName(), -1);
                case CLASS -> HighlightRequest.none();
            };
            openClass(r.classFqn(), hl, ClassEditorTab.View.DECOMPILED);
        });
        if (patternsTab != null) {
            patternsTab.setContent(patternPanel);
        }
    }

    private void setupUsagePanel() {
        usagePanel = new UsagePanel();
        usagePanel.setOnOpen(cs -> {
            HighlightRequest hl = cs.inMethodName() != null && !cs.inMethodName().isBlank()
                    && !"<class>".equals(cs.inMethodName())
                    ? HighlightRequest.literal(cs.inMethodName(), -1)
                    : HighlightRequest.none();
            String fqn = cs.inClassFqn().replace('/', '.');
            openClass(fqn, hl, ClassEditorTab.View.DECOMPILED);
        });
        // Wire snippet preview — pull cached decompiled text for the call site's class and
        // extract the line. No source = no snippet (rendered without preview row). The cache
        // already carries every engine variant; we ask under "Auto" since that's what the
        // tab shows by default. Cheap miss when class hasn't been opened.
        usagePanel.setSnippetProvider(cs -> {
            String internal = cs.inClassFqn();
            int line = cs.lineNumber();
            if (line <= 0 || internal == null) return null;
            ClassEntry e = classByFqn.get(internal.replace('/', '.'));
            if (e == null) return null;
            // Try Auto first (most common), then CFR (next-most-cached).
            String src = ClassEditorTab.sharedCache().get(e.internalName(), "Auto", e.bytes());
            if (src == null) src = ClassEditorTab.sharedCache().get(e.internalName(), "CFR", e.bytes());
            if (src == null) return null;
            return dev.share.bytecodelens.usage.XrefSnippetExtractor.extract(src, line);
        });
        if (usagesTab != null) {
            usagesTab.setContent(usagePanel);
        }
    }

    public void showUsages(UsageTarget target) {
        if (usageIndex == null || usagePanel == null) {
            showInfo("Usage index not ready", "Please wait until the jar is fully indexed.");
            return;
        }
        var results = new java.util.ArrayList<>(usageIndex.findUsages(target));
        // For methods, append synthetic CallSite entries representing virtual overriders /
        // interface implementations so the user sees "this method is also defined in N
        // subtypes" alongside actual call sites. Gated by the user's setting.
        var settings = dev.share.bytecodelens.settings.AppSettingsStore.getInstance().get();
        if (settings.xref.includeOverridersInUsages
                && target instanceof UsageTarget.Method m && hierarchyIndex != null) {
            var overriders = dev.share.bytecodelens.hierarchy.OverriderSearch.findOverriders(
                    hierarchyIndex, m.ownerInternal(), m.name(), m.desc());
            for (var ov : overriders) {
                results.add(new dev.share.bytecodelens.usage.CallSite(
                        ov.ownerInternal(), m.name(), m.desc(),
                        dev.share.bytecodelens.usage.CallSite.Kind.INVOKE_SPECIAL,
                        m.ownerInternal(), "(override)", m.desc(), 0));
            }
        }
        usagePanel.showResults(target, results);
        bottomTabs.getSelectionModel().select(usagesTab);
    }

    public void showCallGraphForClass(String internalName) {
        if (usageIndex == null) {
            showInfo("Index not ready", "Please wait until the jar is fully indexed.");
            return;
        }
        // Recaf-style tree graph is method-scoped. For a whole class, pick the first non-<init>
        // declared method as the starting point — user can Set-as-root to any other method from
        // the context menu.
        String fqn = internalName.replace('/', '.');
        ClassEntry entry = classByFqn.get(fqn);
        if (entry == null) {
            showInfo("Call Graph", "Class not found: " + fqn);
            return;
        }
        var methods = analyzer.methods(entry.bytes());
        var target = methods.stream()
                .filter(m -> !m.name().startsWith("<"))
                .findFirst()
                .or(() -> methods.stream().findFirst())
                .orElse(null);
        if (target == null) {
            showInfo("Call Graph", "Class " + fqn + " has no methods to graph.");
            return;
        }
        showCallGraphForMethod(internalName, target.name(), target.descriptor());
    }

    /** Raise the main BytecodeLens window above any popup stages (Call Graph etc). */
    private void bringMainWindowToFront() {
        if (classTree != null && classTree.getScene() != null
                && classTree.getScene().getWindow() instanceof javafx.stage.Stage main) {
            main.toFront();
            main.requestFocus();
        }
    }

    public void showCallGraphForMethod(String internalOwner, String name, String desc) {
        if (usageIndex == null) {
            showInfo("Index not ready", "Please wait until the jar is fully indexed.");
            return;
        }
        CallGraphTreeStage st = new CallGraphTreeStage(
                usageIndex,
                (ownerInternal, nameDesc) -> {
                    int paren = nameDesc.indexOf('(');
                    String mname = paren < 0 ? nameDesc : nameDesc.substring(0, paren);
                    String fqn = ownerInternal.replace('/', '.');
                    openClass(fqn, HighlightRequest.literal(mname, -1),
                            ClassEditorTab.View.DECOMPILED);
                    bringMainWindowToFront();
                },
                (ownerInternal, nameDesc, line) -> {
                    int paren = nameDesc.indexOf('(');
                    String mname = paren < 0 ? nameDesc : nameDesc.substring(0, paren);
                    String fqn = ownerInternal.replace('/', '.');
                    HighlightRequest req = line > 0
                            ? HighlightRequest.literal(mname, line)
                            : HighlightRequest.literal(mname, -1);
                    openClass(fqn, req, ClassEditorTab.View.DECOMPILED);
                    bringMainWindowToFront();
                });
        if (classTree.getScene() != null) {
            st.setOwner(classTree.getScene().getWindow());
        }
        st.showForMethod(internalOwner, name, desc);
    }

    private void setupDecryptPanel() {
        decryptPanel = new StringDecryptionPanel();
        decryptPanel.setOnRunSimulation(() -> runDecryption(false));
        decryptPanel.setOnRunReflection(() -> confirmAndRunReflection());
        decryptPanel.setOnOpenClass((fqn, line) -> {
            HighlightRequest hl = lastDecryptionResult == null
                    ? HighlightRequest.none()
                    : HighlightRequest.none();
            openClass(fqn, hl, ClassEditorTab.View.DECOMPILED);
        });
        if (decryptTab != null) decryptTab.setContent(decryptPanel);
    }

    @FXML
    public void onDecryptStrings() {
        if (currentJar == null) {
            showInfo("No jar loaded", "Open a jar first.");
            return;
        }
        bottomTabs.getSelectionModel().select(decryptTab);
        runDecryption(false);
    }

    @FXML
    public void onApplyMapping() {
        if (currentJar == null) {
            showInfo("Apply Mapping", "Open a jar first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose mapping file");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Mapping files",
                        "*.txt", "*.tiny", "*.srg", "*.tsrg", "*.mapping", "*.mappings"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        Stage stage = (Stage) classTree.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        dev.share.bytecodelens.mapping.MappingModel model;
        try {
            model = dev.share.bytecodelens.mapping.MappingLoader.load(file.toPath());
        } catch (Exception ex) {
            log.error("Failed to parse mapping", ex);
            showError("Mapping parse failed",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return;
        }

        // Preview summary
        String summary = String.format(
                "Format: %s%n%d classes, %d fields, %d methods will be renamed.%n%nApply?",
                model.sourceFormat(), model.classCount(), model.fieldCount(), model.methodCount());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, summary, ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Apply Mapping");
        confirm.setHeaderText("Loaded " + file.getName());
        var result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        // Snapshot and apply off the FX thread (large jars can take a couple of seconds).
        LoadedJar snapshot = currentJar;
        Task<LoadedJar> task = new Task<>() {
            @Override protected LoadedJar call() {
                return new dev.share.bytecodelens.mapping.MappingApplier().apply(snapshot, model);
            }
        };
        task.setOnSucceeded(e -> {
            originalJarBeforeMapping = snapshot;
            activeMappingLabel = file.getName() + " (" + model.sourceFormat() + ")";
            activeMappingModel = model;
            onJarLoaded(task.getValue());
            updateStatus("Mapping applied: " + activeMappingLabel, file.getAbsolutePath());
        });
        task.setOnFailed(e -> {
            log.error("Apply mapping failed", task.getException());
            showError("Apply mapping failed",
                    task.getException() == null ? "Unknown error" : task.getException().getMessage());
        });
        Thread t = new Thread(task, "mapping-applier");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onRevertMapping() {
        if (originalJarBeforeMapping == null) {
            showInfo("Revert Mapping", "No mapping is currently applied.");
            return;
        }
        LoadedJar orig = originalJarBeforeMapping;
        originalJarBeforeMapping = null;
        activeMappingLabel = null;
        activeMappingModel = null;
        activeMappingModel = null;
        onJarLoaded(orig);
        updateStatus("Mapping reverted", orig.source().toString());
    }

    @FXML
    public void onExportMapping() {
        if (activeMappingModel == null) {
            showInfo("Export Mapping",
                    "No active mapping. Apply a mapping first (Analyze \u2192 Apply Mapping...).");
            return;
        }
        // Ask user which format to write.
        dev.share.bytecodelens.mapping.MappingFormat[] options =
                dev.share.bytecodelens.mapping.MappingFormat.values();
        javafx.scene.control.ChoiceDialog<dev.share.bytecodelens.mapping.MappingFormat> dlg =
                new javafx.scene.control.ChoiceDialog<>(activeMappingModel.sourceFormat(), options);
        dlg.setTitle("Export Mapping");
        dlg.setHeaderText("Choose output format");
        dlg.setContentText("Format:");
        var chosen = dlg.showAndWait();
        if (chosen.isEmpty()) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save mapping as...");
        chooser.setInitialFileName("mapping" + suggestExtension(chosen.get()));
        Stage stage = (Stage) classTree.getScene().getWindow();
        File dest = chooser.showSaveDialog(stage);
        if (dest == null) return;

        try {
            dev.share.bytecodelens.mapping.MappingWriter.write(
                    activeMappingModel, chosen.get(), dest.toPath());
            updateStatus("Mapping exported to " + dest.getName(), dest.getAbsolutePath());
        } catch (Exception ex) {
            log.error("Export mapping failed", ex);
            showError("Export mapping failed",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private static String suggestExtension(dev.share.bytecodelens.mapping.MappingFormat f) {
        return switch (f) {
            case PROGUARD -> ".txt";
            case TINY_V1, TINY_V2 -> ".tiny";
            case SRG, XSRG -> ".srg";
            case TSRG, TSRG_V2 -> ".tsrg";
            case CSRG -> ".csrg";
            case JOBF -> ".jobf";
            case RECAF -> ".mapping";
            case ENIGMA -> ".mappings";
        };
    }

    @FXML
    public void onFindMemberRefs() {
        if (currentJar == null || usageIndex == null) {
            showInfo("Find Member References", "Open a jar first.");
            return;
        }
        javafx.scene.control.TextField ownerField = new javafx.scene.control.TextField();
        ownerField.setPromptText("owner internal name, e.g. java/util/HashMap");
        javafx.scene.control.TextField nameField = new javafx.scene.control.TextField();
        nameField.setPromptText("member name, e.g. put");
        javafx.scene.control.TextField descField = new javafx.scene.control.TextField();
        descField.setPromptText("descriptor, e.g. (Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        javafx.scene.control.ChoiceBox<String> kindCB = new javafx.scene.control.ChoiceBox<>();
        kindCB.getItems().addAll("Method", "Field");
        kindCB.setValue("Method");

        VBox body = new VBox(6,
                new Label("Kind:"), kindCB,
                new Label("Owner (internal name with slashes):"), ownerField,
                new Label("Member name:"), nameField,
                new Label("Descriptor:"), descField);
        body.setPadding(new javafx.geometry.Insets(10));

        Alert dlg = new Alert(Alert.AlertType.CONFIRMATION);
        dlg.setTitle("Find Member References");
        dlg.setHeaderText("Match by exact owner + name + descriptor");
        dlg.getDialogPane().setContent(body);
        dlg.getButtonTypes().setAll(
                new ButtonType("Find", javafx.scene.control.ButtonBar.ButtonData.OK_DONE),
                ButtonType.CANCEL);
        var result = dlg.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) return;

        String owner = ownerField.getText() == null ? "" : ownerField.getText().trim();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String desc = descField.getText() == null ? "" : descField.getText().trim();
        if (owner.isEmpty() || name.isEmpty() || desc.isEmpty()) {
            showError("Find Member References", "Owner, name and descriptor are all required.");
            return;
        }

        UsageTarget target = kindCB.getValue().equals("Field")
                ? new UsageTarget.Field(owner, name, desc)
                : new UsageTarget.Method(owner, name, desc);
        showUsages(target);
    }

    @FXML
    public void onMassRename() {
        if (currentJar == null) {
            showInfo("Mass Rename", "Open a jar first.");
            return;
        }
        javafx.scene.control.TextField patternField = new javafx.scene.control.TextField();
        patternField.setPromptText("e.g.  ^C_(\\d+)$");
        javafx.scene.control.TextField replacementField = new javafx.scene.control.TextField();
        replacementField.setPromptText("e.g.  Class$1");
        javafx.scene.control.CheckBox cbClasses = new javafx.scene.control.CheckBox("Rename classes");
        cbClasses.setSelected(true);
        javafx.scene.control.CheckBox cbMethods = new javafx.scene.control.CheckBox("Rename methods");
        javafx.scene.control.CheckBox cbFields = new javafx.scene.control.CheckBox("Rename fields");

        VBox body = new VBox(8,
                new Label("Regex pattern (Java syntax):"), patternField,
                new Label("Replacement (supports $1, $2, ...):"), replacementField,
                new javafx.scene.control.Separator(),
                cbClasses, cbMethods, cbFields);
        body.setPadding(new javafx.geometry.Insets(10));

        Alert dlg = new Alert(Alert.AlertType.CONFIRMATION);
        dlg.setTitle("Mass Rename");
        dlg.setHeaderText("Build a mapping from a regex and apply it");
        dlg.getDialogPane().setContent(body);
        dlg.getButtonTypes().setAll(
                new ButtonType("Preview", javafx.scene.control.ButtonBar.ButtonData.OK_DONE),
                ButtonType.CANCEL);
        var result = dlg.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) return;

        String patternText = patternField.getText();
        String replacement = replacementField.getText();
        if (patternText == null || patternText.isBlank()) {
            showError("Mass Rename", "Pattern is required.");
            return;
        }
        java.util.regex.Pattern pattern;
        try {
            pattern = java.util.regex.Pattern.compile(patternText);
        } catch (java.util.regex.PatternSyntaxException ex) {
            showError("Bad regex", ex.getDescription() + " at index " + ex.getIndex());
            return;
        }
        dev.share.bytecodelens.mapping.MassRenameGenerator.Rules rules =
                new dev.share.bytecodelens.mapping.MassRenameGenerator.Rules(
                        pattern, replacement == null ? "" : replacement,
                        cbClasses.isSelected(), cbMethods.isSelected(), cbFields.isSelected());

        dev.share.bytecodelens.mapping.MappingModel model =
                new dev.share.bytecodelens.mapping.MassRenameGenerator().generate(currentJar, rules);

        if (model.classCount() == 0 && model.methodCount() == 0 && model.fieldCount() == 0) {
            showInfo("Mass Rename", "Pattern matched nothing. Mapping is empty.");
            return;
        }
        String preview = String.format(
                "%d classes, %d methods, %d fields will be renamed.%n%nApply?",
                model.classCount(), model.methodCount(), model.fieldCount());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, preview, ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Mass Rename");
        confirm.setHeaderText("Preview");
        var ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.OK) return;

        LoadedJar snapshot = currentJar;
        Task<LoadedJar> task = new Task<>() {
            @Override protected LoadedJar call() {
                return new dev.share.bytecodelens.mapping.MappingApplier().apply(snapshot, model);
            }
        };
        task.setOnSucceeded(e -> {
            originalJarBeforeMapping = snapshot;
            activeMappingLabel = "Mass Rename";
            activeMappingModel = model;
            onJarLoaded(task.getValue());
            updateStatus("Mass rename applied: " + model.classCount() + " classes, "
                    + model.methodCount() + " methods, " + model.fieldCount() + " fields", "");
        });
        task.setOnFailed(e -> {
            log.error("Mass rename failed", task.getException());
            showError("Mass Rename",
                    task.getException() == null ? "Unknown" : task.getException().getMessage());
        });
        Thread t = new Thread(task, "mass-rename");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onRunTransformations() {
        if (currentJar == null) {
            showInfo("Transformations", "Open a jar first.");
            return;
        }
        // 5 passes: 2 jar-level (illegal names, static inlining) + 3 per-class.
        dev.share.bytecodelens.transform.JarLevelTransformation illegalNames =
                new dev.share.bytecodelens.transform.transforms.IllegalNameMapping();
        dev.share.bytecodelens.transform.JarLevelTransformation staticInline =
                new dev.share.bytecodelens.transform.transforms.StaticValueInlining();
        dev.share.bytecodelens.transform.Transformation deadCode =
                new dev.share.bytecodelens.transform.transforms.DeadCodeRemoval();
        dev.share.bytecodelens.transform.Transformation opaque =
                new dev.share.bytecodelens.transform.transforms.OpaquePredicateSimplification();
        dev.share.bytecodelens.transform.Transformation kotlin =
                new dev.share.bytecodelens.transform.transforms.KotlinNameRestoration();
        dev.share.bytecodelens.transform.Transformation stripCode =
                new dev.share.bytecodelens.transform.transforms.StripCodeOnField();
        dev.share.bytecodelens.transform.Transformation removeAnn =
                new dev.share.bytecodelens.transform.transforms.RemoveIllegalAnnotations();
        // Anti-disassembly stripper — targets SC2-era push/pop/ret/throw patterns that
        // confuse decompilers relying on fall-through after RETURN. DeadCodeRemoval's
        // control-flow analysis treats that garbage as reachable; this one strips it
        // structurally.
        dev.share.bytecodelens.transform.Transformation unreachable =
                new dev.share.bytecodelens.transform.transforms.UnreachableAfterTerminator();
        // Four new low-risk passes added in this pack — all per-class, none mutate other classes:
        dev.share.bytecodelens.transform.Transformation callInline =
                new dev.share.bytecodelens.transform.transforms.CallResultInlining();
        dev.share.bytecodelens.transform.Transformation enumNames =
                new dev.share.bytecodelens.transform.transforms.EnumNameRestoration();
        dev.share.bytecodelens.transform.Transformation stackFrames =
                new dev.share.bytecodelens.transform.transforms.StackFrameRemoval();
        dev.share.bytecodelens.transform.Transformation sourceNames =
                new dev.share.bytecodelens.transform.transforms.SourceNameRestoration();
        dev.share.bytecodelens.transform.Transformation kotlinData =
                new dev.share.bytecodelens.transform.transforms.KotlinDataClassRestoration();

        // Default-selected passes come from settings so the user can pick their own
        // baseline in Settings > Transformations and have it applied here.
        java.util.Set<String> defaults = dev.share.bytecodelens.settings.AppSettingsStore
                .getInstance().get().transformations.defaultSelectedPasses;
        javafx.scene.control.CheckBox cbIllegal = new javafx.scene.control.CheckBox(illegalNames.name());
        cbIllegal.setTooltip(new Tooltip(illegalNames.description()));
        cbIllegal.setSelected(defaults.contains(illegalNames.id()));
        javafx.scene.control.CheckBox cbStatic = new javafx.scene.control.CheckBox(staticInline.name());
        cbStatic.setTooltip(new Tooltip(staticInline.description()));
        cbStatic.setSelected(defaults.contains(staticInline.id()));
        javafx.scene.control.CheckBox cbDead = new javafx.scene.control.CheckBox(deadCode.name());
        cbDead.setTooltip(new Tooltip(deadCode.description()));
        cbDead.setSelected(defaults.contains(deadCode.id()));
        javafx.scene.control.CheckBox cbOpaque = new javafx.scene.control.CheckBox(opaque.name());
        cbOpaque.setTooltip(new Tooltip(opaque.description()));
        cbOpaque.setSelected(defaults.contains(opaque.id()));
        javafx.scene.control.CheckBox cbKotlin = new javafx.scene.control.CheckBox(kotlin.name());
        cbKotlin.setTooltip(new Tooltip(kotlin.description()));
        cbKotlin.setSelected(defaults.contains(kotlin.id()));
        javafx.scene.control.CheckBox cbStripCode = new javafx.scene.control.CheckBox(stripCode.name());
        cbStripCode.setTooltip(new Tooltip(stripCode.description()));
        cbStripCode.setSelected(defaults.contains(stripCode.id()));
        javafx.scene.control.CheckBox cbRemoveAnn = new javafx.scene.control.CheckBox(removeAnn.name());
        cbRemoveAnn.setTooltip(new Tooltip(removeAnn.description()));
        cbRemoveAnn.setSelected(defaults.contains(removeAnn.id()));
        javafx.scene.control.CheckBox cbUnreach = new javafx.scene.control.CheckBox(unreachable.name());
        cbUnreach.setTooltip(new Tooltip(unreachable.description()));
        cbUnreach.setSelected(defaults.contains(unreachable.id()));
        javafx.scene.control.CheckBox cbCallInline = new javafx.scene.control.CheckBox(callInline.name());
        cbCallInline.setTooltip(new Tooltip(callInline.description()));
        cbCallInline.setSelected(defaults.contains(callInline.id()));
        javafx.scene.control.CheckBox cbEnumNames = new javafx.scene.control.CheckBox(enumNames.name());
        cbEnumNames.setTooltip(new Tooltip(enumNames.description()));
        cbEnumNames.setSelected(defaults.contains(enumNames.id()));
        javafx.scene.control.CheckBox cbStackFrames = new javafx.scene.control.CheckBox(stackFrames.name());
        cbStackFrames.setTooltip(new Tooltip(stackFrames.description()));
        cbStackFrames.setSelected(defaults.contains(stackFrames.id()));
        javafx.scene.control.CheckBox cbSourceNames = new javafx.scene.control.CheckBox(sourceNames.name());
        cbSourceNames.setTooltip(new Tooltip(sourceNames.description()));
        cbSourceNames.setSelected(defaults.contains(sourceNames.id()));
        javafx.scene.control.CheckBox cbKotlinData = new javafx.scene.control.CheckBox(kotlinData.name());
        cbKotlinData.setTooltip(new Tooltip(kotlinData.description()));
        cbKotlinData.setSelected(defaults.contains(kotlinData.id()));

        Label antiTamperHeader = new Label("Anti-tamper:");
        antiTamperHeader.getStyleClass().add("info-key");
        VBox box = new VBox(8, cbIllegal, cbStatic, cbDead, cbUnreach, cbOpaque, cbKotlin,
                cbCallInline, cbEnumNames, cbStackFrames, cbSourceNames, cbKotlinData,
                new javafx.scene.control.Separator(), antiTamperHeader, cbStripCode, cbRemoveAnn);
        box.setPadding(new javafx.geometry.Insets(10));

        Alert dlg = new Alert(Alert.AlertType.CONFIRMATION);
        dlg.setTitle("Transformations");
        dlg.setHeaderText("Select deobfuscation passes to apply");
        dlg.getDialogPane().setContent(box);
        dlg.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        var result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        java.util.List<dev.share.bytecodelens.transform.JarLevelTransformation> jarLevel = new java.util.ArrayList<>();
        if (cbIllegal.isSelected()) jarLevel.add(illegalNames);
        if (cbStatic.isSelected()) jarLevel.add(staticInline);
        java.util.List<dev.share.bytecodelens.transform.Transformation> perClass = new java.util.ArrayList<>();
        if (cbDead.isSelected()) perClass.add(deadCode);
        if (cbUnreach.isSelected()) perClass.add(unreachable);
        if (cbOpaque.isSelected()) perClass.add(opaque);
        if (cbKotlin.isSelected()) perClass.add(kotlin);
        if (cbCallInline.isSelected()) perClass.add(callInline);
        if (cbEnumNames.isSelected()) perClass.add(enumNames);
        if (cbStackFrames.isSelected()) perClass.add(stackFrames);
        if (cbSourceNames.isSelected()) perClass.add(sourceNames);
        if (cbKotlinData.isSelected()) perClass.add(kotlinData);
        if (cbStripCode.isSelected()) perClass.add(stripCode);
        if (cbRemoveAnn.isSelected()) perClass.add(removeAnn);

        if (jarLevel.isEmpty() && perClass.isEmpty()) {
            showInfo("Transformations", "No passes selected.");
            return;
        }

        LoadedJar snapshot = currentJar;
        Task<dev.share.bytecodelens.transform.TransformationResult> task = new Task<>() {
            @Override protected dev.share.bytecodelens.transform.TransformationResult call() {
                return new dev.share.bytecodelens.transform.TransformationRunner()
                        .run(snapshot, jarLevel, perClass);
            }
        };
        task.setOnSucceeded(e -> {
            var res = task.getValue();
            originalJarBeforeMapping = snapshot; // reuse the mapping revert slot
            activeMappingLabel = "Transformations";
            onJarLoaded(res.transformedJar());
            StringBuilder summary = new StringBuilder();
            summary.append(res.classesChanged()).append(" classes changed, ")
                    .append(res.classesFailed()).append(" failed.\n\n");
            var counters = res.context().counters();
            if (counters.isEmpty()) {
                summary.append("No changes made.");
            } else {
                for (var passEntry : counters.entrySet()) {
                    summary.append(passEntry.getKey()).append(":\n");
                    for (var counterEntry : passEntry.getValue().entrySet()) {
                        summary.append("  ").append(counterEntry.getKey()).append(": ")
                                .append(counterEntry.getValue()).append("\n");
                    }
                }
            }
            showInfo("Transformations — done", summary.toString());
            updateStatus("Transformations applied", "");
        });
        task.setOnFailed(e -> {
            log.error("Transformations failed", task.getException());
            showError("Transformations failed",
                    task.getException() == null ? "Unknown error" : task.getException().getMessage());
        });
        Thread t = new Thread(task, "transformations");
        t.setDaemon(true);
        t.start();
    }

    private void confirmAndRunReflection() {
        if (currentJar == null) {
            showInfo("No jar loaded", "Open a jar first.");
            return;
        }
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "Reflection mode will load classes from this jar into the JVM and invoke "
                        + "decryptor methods directly. This means code from the jar will execute. "
                        + "Only do this if you trust the jar.\n\nContinue?",
                javafx.scene.control.ButtonType.YES,
                javafx.scene.control.ButtonType.CANCEL);
        a.setTitle("Enable reflection mode");
        a.setHeaderText("Execute code from the jar?");
        var result = a.showAndWait();
        if (result.isPresent() && result.get() == javafx.scene.control.ButtonType.YES) {
            runDecryption(true);
        }
    }

    private void runDecryption(boolean enableReflection) {
        if (currentJar == null) return;
        StringDecryptor decryptor = new StringDecryptor();
        javafx.concurrent.Task<DecryptionResult> task = new javafx.concurrent.Task<>() {
            @Override
            protected DecryptionResult call() {
                return decryptor.decrypt(currentJar, enableReflection);
            }
        };
        updateStatus("Decrypting strings...", enableReflection ? "reflection mode" : "simulation mode");
        task.setOnSucceeded(e -> {
            lastDecryptionResult = task.getValue();
            decryptPanel.showResult(lastDecryptionResult);
            updateStatus(String.format("Decrypted %d strings from %d call-sites",
                    lastDecryptionResult.decrypted().size(), lastDecryptionResult.callSitesFound()),
                    enableReflection ? "reflection mode" : "simulation mode");
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            updateStatus("Decryption failed", ex == null ? "" : ex.getMessage());
        });
        Thread t = new Thread(task, "string-decryptor");
        t.setDaemon(true);
        t.start();
    }

    private void setupHierarchyPanel() {
        hierarchyPanel = new HierarchyPanel();
        hierarchyPanel.setOnOpen(internalName -> {
            String fqn = internalName.replace('/', '.');
            openClass(fqn);
        });
        if (hierarchyTab != null) {
            hierarchyTab.setContent(hierarchyPanel);
        }
    }

    public void showHierarchy(String internalName) {
        if (hierarchyIndex == null || hierarchyPanel == null) {
            showInfo("Hierarchy index not ready", "Please wait until the jar is fully indexed.");
            return;
        }
        hierarchyPanel.showHierarchyOf(internalName);
        bottomTabs.getSelectionModel().select(hierarchyTab);
    }

    private void rebuildHierarchyIndex(LoadedJar jar) {
        hierarchyIndex = null;
        if (jar == null) {
            if (hierarchyPanel != null) hierarchyPanel.setIndex(null);
            return;
        }
        HierarchyIndex idx = new HierarchyIndex(jar);
        javafx.concurrent.Task<HierarchyIndex> task = new javafx.concurrent.Task<>() {
            @Override
            protected HierarchyIndex call() {
                idx.build();
                return idx;
            }
        };
        task.setOnSucceeded(e -> {
            hierarchyIndex = task.getValue();
            if (hierarchyPanel != null) hierarchyPanel.setIndex(hierarchyIndex);
        });
        Thread t = new Thread(task, "hierarchy-index-builder");
        t.setDaemon(true);
        t.start();
    }

    private void rebuildUsageIndex(LoadedJar jar) {
        usageIndex = null;
        stringLiteralIndex = null;
        if (jar == null) {
            if (usagePanel != null) usagePanel.clear();
            return;
        }
        UsageIndex idx = new UsageIndex(jar);
        // String literal index — small enough to build alongside UsageIndex on the same
        // background thread. Both are built in the order most users hit them: usages first
        // (driven by Alt+F7 / X), strings second (driven by search overlay's "Strings" mode).
        dev.share.bytecodelens.usage.StringLiteralIndex strIdx =
                new dev.share.bytecodelens.usage.StringLiteralIndex(jar);
        javafx.concurrent.Task<UsageIndex> task = new javafx.concurrent.Task<>() {
            @Override
            protected UsageIndex call() {
                idx.build();
                strIdx.build();
                return idx;
            }
        };
        task.setOnSucceeded(e -> {
            usageIndex = task.getValue();
            stringLiteralIndex = strIdx;
        });
        Thread t = new Thread(task, "usage-index-builder");
        t.setDaemon(true);
        t.start();
    }

    private void registerGlobalShortcuts() {
        if (classTree == null || classTree.getScene() == null) return;
        // Map each action to its implementation; the accelerator itself comes from
        // KeymapStore so user-customised keys work without touching this wiring.
        java.util.Map<dev.share.bytecodelens.keymap.Action, Runnable> impl = new java.util.LinkedHashMap<>();
        impl.put(dev.share.bytecodelens.keymap.Actions.FIND_IN_JAR, this::showSearchOverlay);
        impl.put(dev.share.bytecodelens.keymap.Actions.CLOSE_TAB, this::closeCurrentEditorTab);
        impl.put(dev.share.bytecodelens.keymap.Actions.GOTO_CLASS, this::showGotoClassDialog);
        impl.put(dev.share.bytecodelens.keymap.Actions.NAV_BACK, this::navigateBack);
        impl.put(dev.share.bytecodelens.keymap.Actions.NAV_FORWARD, this::navigateForward);
        impl.put(dev.share.bytecodelens.keymap.Actions.SYNC_TREE, this::syncTreeWithActiveEditor);
        // NOTE: FIND_USAGES is intentionally NOT registered as a scene accelerator — the
        // default is plain "X" which would fire in every text field otherwise. It's
        // handled inside CodeView (installSymbolNavigation) with the same keymap combo.
        applyAcceleratorsFromKeymap(impl);
    }

    /**
     * Install the given action→runnable map onto the scene as accelerators, using the
     * current KeymapStore for the keyboard combinations. Called again whenever the
     * user saves changes from the keymap editor so bindings update live.
     */
    private java.util.Map<dev.share.bytecodelens.keymap.Action, Runnable> activeAccelerators = java.util.Map.of();

    private void applyAcceleratorsFromKeymap(
            java.util.Map<dev.share.bytecodelens.keymap.Action, Runnable> impl) {
        if (classTree == null || classTree.getScene() == null) return;
        this.activeAccelerators = impl;
        var scene = classTree.getScene();
        // Clear any previous bindings we installed so stale ones don't linger.
        // (We can't just clearAll — FXML menu accelerators live in the same map.)
        for (dev.share.bytecodelens.keymap.Action a : dev.share.bytecodelens.keymap.Actions.ALL) {
            KeyCombination prev = dev.share.bytecodelens.keymap.KeymapStore.parse(a.defaultAccelerator());
            if (prev != null) scene.getAccelerators().remove(prev);
        }
        for (var e : impl.entrySet()) {
            KeyCombination combo = keymap.combinationFor(e.getKey());
            if (combo != null) scene.getAccelerators().put(combo, e.getValue());
        }
    }

    /** Opens the Settings window (full-featured replacement for the old Preferences dialog). */
    @FXML
    public void onOpenSettings() {
        dev.share.bytecodelens.ui.SettingsStage dlg = new dev.share.bytecodelens.ui.SettingsStage(
                dev.share.bytecodelens.settings.AppSettingsStore.getInstance(), keymap);
        // Apply callback — runs after every Apply (not just on close). Push keymap /
        // accelerator changes that the store itself doesn't know about.
        dlg.setOnApplied(() -> {
            applyAcceleratorsFromKeymap(activeAccelerators);
            rebuildSyntaxThemeMenu();
            javafx.scene.input.KeyCombination fuCombo = keymap.combinationFor(
                    dev.share.bytecodelens.keymap.Actions.FIND_USAGES);
            if (fuCombo != null) {
                for (var editorTab : openTabs.values()) {
                    editorTab.decompiledView().setFindUsagesCombination(fuCombo);
                }
            }
        });
        dlg.show();
    }

    /** Legacy handler kept for FXML accelerator binding. Routes to Settings. */
    @FXML
    public void onEditKeymap() { onOpenSettings(); }

    /**
     * Locate the currently-displayed class in the project tree on the left. Expands the
     * enclosing packages, selects the node and scrolls it into view.
     */
    /**
     * JADX-style X-hotkey handler: the user hovered/clicked an identifier and pressed X
     * (or whatever's bound to navigate.find.usages) without leaving the editor. We
     * resolve the word against the current class's declared methods and fields first
     * (covers the common case of an in-method local reference), then fall back to
     * a simple-name class lookup across the jar.
     */
    private void findUsagesOfWord(String word, ClassEntry owner) {
        if (word == null || word.isEmpty() || currentJar == null || owner == null) return;
        // Try methods of the owning class.
        try {
            var methods = analyzer.methods(owner.bytes());
            var m = methods.stream().filter(x -> x.name().equals(word)).findFirst();
            if (m.isPresent()) {
                showUsages(new UsageTarget.Method(owner.internalName(), m.get().name(), m.get().descriptor()));
                return;
            }
            var fields = analyzer.fields(owner.bytes());
            var f = fields.stream().filter(x -> x.name().equals(word)).findFirst();
            if (f.isPresent()) {
                showUsages(new UsageTarget.Field(owner.internalName(), f.get().name(), f.get().descriptor()));
                return;
            }
        } catch (Exception ignored) {}
        // Fall back to a class by simple name — pick the unique match if any.
        ClassEntry hit = null;
        int hits = 0;
        for (ClassEntry c : currentJar.classes()) {
            if (word.equals(c.simpleName())) {
                hits++;
                if (hit == null) hit = c;
            }
        }
        if (hits == 1) {
            showUsages(new UsageTarget.Class(hit.internalName()));
        }
    }

    /**
     * Alt+F7 handler. Pick the best target from context and call {@link #showUsages}:
     * <ol>
     *   <li>If the tree has a selected class/method/field — use that.</li>
     *   <li>Else if a class tab is open, read the word at the caret and try to match it
     *       against a method or field of the displayed class.</li>
     * </ol>
     */
    private void findUsagesOfActiveTarget() {
        if (currentJar == null) return;
        // 1) Tree selection.
        var treeSel = classTree.getSelectionModel().getSelectedItem();
        if (treeSel != null) {
            NodeKind k = treeSel.getValue().kind();
            if (k == NodeKind.CLASS) {
                ClassEntry ce = resolveEntry(treeSel.getValue().fqn());
                if (ce != null) { showUsages(new UsageTarget.Class(ce.internalName())); return; }
            } else if (k == NodeKind.METHOD) {
                MemberRef r = decodeMemberFqn(treeSel.getValue().fqn(), k);
                ClassEntry ce = r == null ? null : resolveEntry(r.ownerKey());
                if (ce != null && r != null) {
                    showUsages(new UsageTarget.Method(ce.internalName(), r.name(), r.desc()));
                    return;
                }
            } else if (k == NodeKind.FIELD) {
                MemberRef r = decodeMemberFqn(treeSel.getValue().fqn(), k);
                ClassEntry ce = r == null ? null : resolveEntry(r.ownerKey());
                if (ce != null && r != null) {
                    showUsages(new UsageTarget.Field(ce.internalName(), r.name(), r.desc()));
                    return;
                }
            }
        }
        // 2) Word under caret in active editor.
        if (currentClassInternalName == null) return;
        ClassEntry ce = classByFqn.get(currentClassInternalName.replace('/', '.'));
        if (ce == null) return;
        var activeTab = editorTabs.getSelectionModel().getSelectedItem();
        if (activeTab == null) return;
        ClassEditorTab editorTab = null;
        for (var e : openTabs.entrySet()) {
            if (e.getValue().tab() == activeTab) { editorTab = e.getValue(); break; }
        }
        if (editorTab == null) return;
        String word = editorTab.decompiledView().wordUnderCaret();
        if (word == null || word.isEmpty()) return;
        resolveSymbol(word, ce);  // existing Ctrl+Click path — fine default for F7
    }

    private void syncTreeWithActiveEditor() {
        if (currentJar == null || currentClassInternalName == null) return;
        String fqn = currentClassInternalName.replace('/', '.');
        TreeItem<TreeNode> target = findClassTreeItem(allClassesRoot, fqn);
        if (target == null) return;
        TreeItem<TreeNode> p = target.getParent();
        while (p != null) {
            p.setExpanded(true);
            p = p.getParent();
        }
        classTree.getSelectionModel().select(target);
        int idx = classTree.getRow(target);
        if (idx >= 0) classTree.scrollTo(Math.max(0, idx - 3));
    }

    private TreeItem<TreeNode> findClassTreeItem(TreeItem<TreeNode> node, String fqn) {
        if (node == null) return null;
        TreeNode v = node.getValue();
        if (v != null && v.kind() == NodeKind.CLASS && fqn.equals(v.fqn())) return node;
        for (TreeItem<TreeNode> child : node.getChildren()) {
            TreeItem<TreeNode> hit = findClassTreeItem(child, fqn);
            if (hit != null) return hit;
        }
        return null;
    }

    private void closeCurrentEditorTab() {
        javafx.scene.control.Tab sel = editorTabs.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        editorTabs.getTabs().remove(sel);
        if (sel.getOnClosed() != null) sel.getOnClosed().handle(null);
    }

    private void showSearchOverlay() {
        if (searchOverlay == null) {
            initSearchOverlay();
        }
        if (searchOverlay == null) return;
        if (currentJar == null) {
            showInfo("No jar loaded", "Open a jar first.");
            return;
        }
        searchOverlay.setVisible(true);
        searchOverlay.setManaged(true);
        searchOverlay.focusSearchField();
    }

    private void hideSearchOverlay() {
        if (searchOverlay == null) return;
        searchOverlay.setVisible(false);
        searchOverlay.setManaged(false);
    }

    private void initSearchOverlay() {
        if (classTree == null || classTree.getScene() == null) return;
        var root = (javafx.scene.layout.BorderPane) classTree.getScene().getRoot();
        var center = root.getCenter();
        if (!(center instanceof javafx.scene.layout.StackPane)) {
            javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane();
            stack.getChildren().add(center);

            searchOverlay = new SearchOverlay();
            searchOverlay.setVisible(false);
            searchOverlay.setManaged(false);
            searchOverlay.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            javafx.scene.layout.StackPane.setAlignment(searchOverlay, javafx.geometry.Pos.TOP_CENTER);
            searchOverlay.setMaxWidth(960);

            searchOverlay.setOnClose(this::hideSearchOverlay);
            searchOverlay.setOnOpenClass((fqn, highlight, view) -> {
                openClass(fqn, highlight, view);
                hideSearchOverlay();
            });
            searchOverlay.setOnOpenResource((path, highlight) -> {
                JarResource.ResourceKind kind = JarResource.detect(path);
                int slash = path.lastIndexOf('/');
                String simple = slash < 0 ? path : path.substring(slash + 1);
                openResource(path, kind, simple, highlight);
                hideSearchOverlay();
            });
            // Seed the overlay with any persisted exclusions, and keep the controller's
            // copy in sync when the user clears them from the overlay (the clear-all
            // action lives on the overlay's excluded label).
            searchOverlay.setExcludedPackages(excludedPackages);
            searchOverlay.setOnExcludedChanged(newList -> {
                excludedPackages.clear();
                excludedPackages.addAll(newList);
            });
            if (searchIndex != null) searchOverlay.setIndex(searchIndex);

            stack.getChildren().add(searchOverlay);
            root.setCenter(stack);
        }
    }

    private void rebuildSearchIndex(LoadedJar jar) {
        searchIndex = null;
        if (jar == null) {
            if (searchOverlay != null) searchOverlay.setIndex(null);
            return;
        }
        SearchIndex idx = new SearchIndex(jar);
        // Wire the user's CommentStore into the index so SearchMode.COMMENTS has data
        // to walk. Passing the live store (not a snapshot) means freshly-added comments
        // become searchable immediately without re-indexing.
        idx.setCommentStore(commentStore);
        javafx.concurrent.Task<SearchIndex> task = new javafx.concurrent.Task<>() {
            @Override
            protected SearchIndex call() {
                idx.build();
                return idx;
            }
        };
        task.setOnSucceeded(e -> {
            searchIndex = task.getValue();
            if (searchOverlay != null) searchOverlay.setIndex(searchIndex);
        });
        Thread t = new Thread(task, "search-index-builder");
        t.setDaemon(true);
        t.start();
    }

    public enum NodeKind {
        ROOT, PACKAGE, CLASS, RESOURCE, RESOURCE_FOLDER,
        MULTI_RELEASE_ROOT, MULTI_RELEASE_VERSION,
        /** Lazy-loaded method under a class. fqn format: "ownerFqn#methodName#descriptor". */
        METHOD,
        /** Lazy-loaded field under a class. fqn format: "ownerFqn|fieldName|descriptor". */
        FIELD,
        /** Single placeholder child under an unexpanded class — triggers the expand arrow. */
        MEMBERS_PLACEHOLDER
    }

    private static final String METHOD_SEP = "#";
    private static final String FIELD_SEP = "|";

    /** Build the fqn-encoded key for a method tree node. */
    private static String methodKey(String ownerFqn, String name, String desc) {
        return ownerFqn + METHOD_SEP + name + METHOD_SEP + desc;
    }

    /** Build the fqn-encoded key for a field tree node. */
    private static String fieldKeyForTree(String ownerFqn, String name, String desc) {
        return ownerFqn + FIELD_SEP + name + FIELD_SEP + desc;
    }

    private static final String VERSIONED_PREFIX = "__v";
    private static final String VERSIONED_SEP = "__/";

    private static String versionedKey(int version, String fqn) {
        return VERSIONED_PREFIX + version + VERSIONED_SEP + fqn;
    }

    /**
     * Build a class tree item with a placeholder child so it shows the expand arrow.
     * Methods/fields load on first expansion — avoids parsing every ClassEntry on startup.
     *
     * @param fqnKey the tree-fqn (dotted for root classes, versioned-key for MR ones)
     */
    private TreeItem<TreeNode> buildExpandableClassItem(ClassEntry entry, String fqnKey) {
        TreeItem<TreeNode> classItem = new TreeItem<>(
                new TreeNode(entry.simpleName(), fqnKey, NodeKind.CLASS));
        // Placeholder is replaced on first expansion.
        classItem.getChildren().add(new TreeItem<>(
                new TreeNode("…", fqnKey, NodeKind.MEMBERS_PLACEHOLDER)));
        classItem.expandedProperty().addListener((obs, was, is) -> {
            if (is && classItem.getChildren().size() == 1
                    && classItem.getChildren().get(0).getValue().kind() == NodeKind.MEMBERS_PLACEHOLDER) {
                loadMembersInto(classItem, entry, fqnKey);
            }
        });
        return classItem;
    }

    private void loadMembersInto(TreeItem<TreeNode> classItem, ClassEntry entry, String fqnKey) {
        java.util.List<TreeItem<TreeNode>> children = new java.util.ArrayList<>();
        try {
            // Fields first (IntelliJ convention: state before behaviour).
            for (var f : analyzer.fields(entry.bytes())) {
                TreeNode node = new TreeNode(f.name() + " : " + prettyDescriptor(f.descriptor()),
                        fieldKeyForTree(fqnKey, f.name(), f.descriptor()), NodeKind.FIELD);
                children.add(new TreeItem<>(node));
            }
            // Methods — constructors go to the top per IntelliJ convention.
            var methods = new java.util.ArrayList<>(analyzer.methods(entry.bytes()));
            methods.sort((a, b) -> {
                boolean ai = a.name().startsWith("<"), bi = b.name().startsWith("<");
                if (ai != bi) return ai ? -1 : 1;
                return a.name().compareTo(b.name());
            });
            for (var m : methods) {
                String label = m.name() + prettyMethodSig(m.descriptor());
                TreeNode node = new TreeNode(label,
                        methodKey(fqnKey, m.name(), m.descriptor()), NodeKind.METHOD);
                children.add(new TreeItem<>(node));
            }
        } catch (Throwable ex) {
            log.warn("Failed to load members for {}: {}", entry.name(), ex.getMessage());
        }
        classItem.getChildren().setAll(children);
    }

    /** "I" -> "int", "Ljava/lang/String;" -> "String", "[I" -> "int[]" — short, for tree labels. */
    private static String prettyDescriptor(String desc) {
        int arr = 0;
        while (arr < desc.length() && desc.charAt(arr) == '[') arr++;
        String base = switch (desc.charAt(arr)) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'V' -> "void";
            case 'L' -> {
                String full = desc.substring(arr + 1, desc.length() - 1);
                int slash = full.lastIndexOf('/');
                yield slash < 0 ? full : full.substring(slash + 1);
            }
            default -> desc.substring(arr);
        };
        return base + "[]".repeat(arr);
    }

    /** "(II)V" -> "(int, int) : void". */
    private static String prettyMethodSig(String desc) {
        org.objectweb.asm.Type mt = org.objectweb.asm.Type.getMethodType(desc);
        org.objectweb.asm.Type[] args = mt.getArgumentTypes();
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(prettyDescriptor(args[i].getDescriptor()));
        }
        sb.append(") : ").append(prettyDescriptor(mt.getReturnType().getDescriptor()));
        return sb.toString();
    }

    /**
     * Parsed member-node fqn. First element is always the owning class key (plain or versioned
     * fqn suitable for {@link #resolveEntry}); remaining elements are the member name and desc.
     */
    private record MemberRef(String ownerKey, String name, String desc, NodeKind kind) {}

    /** Decode a METHOD/FIELD tree-node fqn back into its parts. */
    private static MemberRef decodeMemberFqn(String encoded, NodeKind kind) {
        if (encoded == null) return null;
        String sep = kind == NodeKind.METHOD ? METHOD_SEP : FIELD_SEP;
        // Methods encode "ownerFqn#name#desc". Descriptor contains '(' and ')' but no '#'.
        // Fields encode "ownerFqn|name|desc". Descriptors also don't contain '|'.
        int second = encoded.lastIndexOf(sep);
        if (second < 0) return null;
        int first = encoded.lastIndexOf(sep, second - 1);
        if (first < 0) return null;
        return new MemberRef(encoded.substring(0, first),
                encoded.substring(first + 1, second),
                encoded.substring(second + 1), kind);
    }

    /** Open a class tab for a tree member node, optionally promoting preview -> pinned. */
    private void openMemberFromTreeNode(TreeNode n, boolean pinned) {
        MemberRef ref = decodeMemberFqn(n.fqn(), n.kind());
        if (ref == null) return;
        ClassEntry entry = resolveEntry(ref.ownerKey());
        if (entry == null) return;
        HighlightRequest req = highlightForMember(entry, ref);
        // Pinned and preview currently share the code path — no separate preview for members.
        openClass(entry.name(), req, ClassEditorTab.View.DECOMPILED);
    }

    /**
     * Build a search query that will actually hit the member declaration in the decompiled
     * source. Simple names ({@code foo}, {@code bar}) work with a plain literal search —
     * every decompiler emits them verbatim. The two synthetic bytecode names are the
     * catch: decompilers translate them into syntactic forms.
     *
     * <ul>
     *   <li>{@code <clinit>} → rendered as the keyword {@code static} followed by a block.
     *       Regex {@code \bstatic\s*\{} nails the declaration without false-positives on
     *       {@code static} modifiers of other members.</li>
     *   <li>{@code <init>} → rendered as the simple class name followed by {@code (}.
     *       Regex {@code \b<SimpleName>\s*\(} matches the constructor declaration as well
     *       as call sites, which is the behaviour the user expects.</li>
     * </ul>
     */
    private HighlightRequest highlightForMember(ClassEntry owner, MemberRef ref) {
        return highlightForMember(ref.name(), owner.simpleName(), ref.kind());
    }

    /**
     * Pure-string variant exposed package-private for unit testing. Decides what to search
     * for given a member's bytecode name, the class's simple name, and whether the member
     * is a METHOD or a FIELD.
     *
     * <ul>
     *   <li>Methods: regex {@code \bname\s*(?:<...>)?\s*\(} — matches declaration AND call
     *       sites. Won't match inside {@code mainActivity} / {@code domain}, won't match
     *       package-qualified references without a call.</li>
     *   <li>Fields: {@link HighlightRequest.Mode#LITERAL_WORD} — plain whole-word scan.
     *       A field has no reliable follow-up punctuation (can be read bare: {@code return
     *       log;}), so we just need whole-word semantics.</li>
     *   <li>{@code <clinit>} → regex {@code \bstatic\s*\{}.</li>
     *   <li>{@code <init>} → regex {@code \b<SimpleName>\s*\(}.</li>
     * </ul>
     */
    static HighlightRequest highlightForMember(String memberName, String simpleClassName, NodeKind kind) {
        if ("<clinit>".equals(memberName)) {
            return HighlightRequest.regex("\\bstatic\\s*\\{", -1);
        }
        if ("<init>".equals(memberName)) {
            if (simpleClassName != null && !simpleClassName.isEmpty()) {
                String safe = java.util.regex.Pattern.quote(simpleClassName);
                return HighlightRequest.regex("\\b" + safe + "\\s*\\(", -1);
            }
            return HighlightRequest.none();
        }
        if (kind == NodeKind.FIELD) {
            // Whole-word literal for fields — they don't have a reliable follow-up like '('.
            return HighlightRequest.literalWord(memberName, -1);
        }
        // METHOD: name followed by optional generics and "(" — declaration or call site.
        String safe = java.util.regex.Pattern.quote(memberName);
        return HighlightRequest.regex("\\b" + safe + "\\s*(?:<[^>]*>)?\\s*\\(", -1);
    }

    /** Legacy 2-arg overload kept for the existing tests. Defaults to METHOD semantics. */
    static HighlightRequest highlightForMember(String memberName, String simpleClassName) {
        return highlightForMember(memberName, simpleClassName, NodeKind.METHOD);
    }

    /** Resolve a tree-fqn to a ClassEntry. Understands both plain fqns and versioned keys. */
    private ClassEntry resolveEntry(String fqn) {
        if (fqn == null) return null;
        if (fqn.startsWith(VERSIONED_PREFIX)) {
            return versionedClassByKey.get(fqn);
        }
        return classByFqn.get(fqn);
    }

    public record TreeNode(String label, String fqn, NodeKind kind,
                           dev.share.bytecodelens.model.JarResource.ResourceKind resourceKind) {
        public TreeNode(String label, String fqn, NodeKind kind) {
            this(label, fqn, kind, null);
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
