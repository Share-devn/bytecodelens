package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.usage.CallSite;
import dev.share.bytecodelens.usage.UsageTarget;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Hierarchical xref viewer. Results group class → method → call site so a 200-hit
 * usage list is still scannable. The filter bar on top applies orthogonal predicates
 * (package substring, read/write mask, invoke kind) with O(N) re-filter on every
 * change — fine for anything short of a whole-JDK index.
 *
 * <p>Panel state is {@link UsageTarget} + full result list + current filter; the
 * displayed tree is rebuilt from those on every filter change. The raw list is kept
 * so toggling filters is a cheap view rebuild, not a re-run of UsageIndex.</p>
 */
public final class UsagePanel extends BorderPane {

    private final Label header = new Label("No usages searched yet");
    private final TreeView<Node> tree = new TreeView<>();
    private final TextField packageFilter = new TextField();
    private final ToggleButton tgReads = new ToggleButton("R");
    private final ToggleButton tgWrites = new ToggleButton("W");
    private final ToggleButton tgInvokes = new ToggleButton("inv");
    private final ToggleButton tgTypeUse = new ToggleButton("type");
    private final CheckBox cbGroupByClass = new CheckBox("Group by class");
    private final Label countBadge = new Label();

    private Consumer<CallSite> onOpen;
    private List<CallSite> rawResults = List.of();
    private UsageTarget currentTarget;
    /**
     * Optional pluggable lookup for code-snippet preview text under each call site row.
     * Returns the trimmed source line (or null to skip rendering). Wired by MainController
     * which has access to the cached decompiled text.
     */
    private java.util.function.Function<CallSite, String> snippetProvider = cs -> null;
    public void setSnippetProvider(java.util.function.Function<CallSite, String> p) {
        this.snippetProvider = p == null ? cs -> null : p;
        // Re-render so existing rows pick up the new provider.
        rebuild();
    }

    public UsagePanel() {
        getStyleClass().add("usage-panel");
        header.getStyleClass().add("usage-header");

        setTop(buildHeader());
        setCenter(buildTree());

        ClipboardUtil.installTreeCopy(tree, this::copyText);
    }

    // ========================================================================
    // Public API — matches the old ListView-based panel so MainController wiring
    // keeps working untouched.
    // ========================================================================

    public void setOnOpen(Consumer<CallSite> handler) {
        this.onOpen = handler;
    }

    public void showResults(UsageTarget target, List<CallSite> results) {
        this.currentTarget = target;
        this.rawResults = results == null ? List.of() : results;
        rebuild();
    }

    public void clear() {
        currentTarget = null;
        rawResults = List.of();
        header.setText("No usages searched yet");
        countBadge.setText("");
        tree.setRoot(null);
    }

    // ========================================================================
    // Header & filter bar
    // ========================================================================

    private VBox buildHeader() {
        HBox titleRow = new HBox(header, countBadge);
        titleRow.setPadding(new Insets(6, 12, 2, 12));
        titleRow.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("usage-header");
        countBadge.getStyleClass().add("usage-count-badge");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        titleRow.getChildren().add(1, spacer);

        packageFilter.setPromptText("Filter by package (e.g. com.foo)");
        packageFilter.getStyleClass().add("usage-filter-field");
        HBox.setHgrow(packageFilter, Priority.ALWAYS);
        packageFilter.textProperty().addListener((o, a, b) -> rebuild());

        // Four kind toggles — all default ON so first render shows everything. Users
        // flip them off to narrow. "R"/"W" are compact because space is tight.
        configureToggle(tgReads, "Field reads (GETFIELD / GETSTATIC)");
        configureToggle(tgWrites, "Field writes (PUTFIELD / PUTSTATIC)");
        configureToggle(tgInvokes, "Method invocations");
        configureToggle(tgTypeUse, "Type usages (NEW, CHECKCAST, etc.)");

        cbGroupByClass.setSelected(true);
        cbGroupByClass.setTooltip(new Tooltip("Group call sites by enclosing class"));
        cbGroupByClass.selectedProperty().addListener((o, a, b) -> rebuild());

        HBox filterRow = new HBox(6, packageFilter,
                tgReads, tgWrites, tgInvokes, tgTypeUse, cbGroupByClass);
        filterRow.setPadding(new Insets(2, 12, 6, 12));
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.getStyleClass().add("usage-filter-row");

        VBox headerBox = new VBox(titleRow, filterRow);
        headerBox.getStyleClass().add("usage-header-bar");
        return headerBox;
    }

    private void configureToggle(ToggleButton tg, String tooltip) {
        tg.setSelected(true);
        tg.setTooltip(new Tooltip(tooltip));
        tg.getStyleClass().add("usage-filter-toggle");
        tg.selectedProperty().addListener((o, a, b) -> rebuild());
    }

    // ========================================================================
    // Tree construction
    // ========================================================================

    private TreeView<Node> buildTree() {
        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>(Node.root()));
        tree.setCellFactory(tv -> new XrefCell());
        tree.setOnMouseClicked(ev -> {
            if (ev.getClickCount() >= 2) openSelected();
        });
        tree.getStyleClass().add("usage-tree");
        // TreeView has no setPlaceholder — empty state is signalled by a stub root with
        // a help-text child (installed inside rebuild()).
        return tree;
    }

    /** Rebuild the visible tree from {@link #rawResults} + current filter state. */
    private void rebuild() {
        if (currentTarget == null) return;
        Predicate<CallSite> filter = buildFilter();
        List<CallSite> kept = new ArrayList<>();
        for (CallSite cs : rawResults) {
            if (filter.test(cs)) kept.add(cs);
        }
        int totalClasses = (int) kept.stream().map(CallSite::inClassFqn).distinct().count();
        header.setText("Usages of " + currentTarget.label());
        countBadge.setText(kept.size() + " in " + totalClasses + " class"
                + (totalClasses == 1 ? "" : "es"));

        TreeItem<Node> root = new TreeItem<>(Node.root());
        if (cbGroupByClass.isSelected()) {
            populateGrouped(root, kept);
        } else {
            populateFlat(root, kept);
        }
        tree.setRoot(root);
    }

    private Predicate<CallSite> buildFilter() {
        String pkg = packageFilter.getText() == null ? "" : packageFilter.getText().trim();
        boolean showReads = tgReads.isSelected();
        boolean showWrites = tgWrites.isSelected();
        boolean showInvokes = tgInvokes.isSelected();
        boolean showTypes = tgTypeUse.isSelected();
        return cs -> {
            if (!pkg.isEmpty()) {
                String fqn = cs.inClassFqn().replace('/', '.');
                if (!fqn.toLowerCase(Locale.ROOT).contains(pkg.toLowerCase(Locale.ROOT))) return false;
            }
            CallSite.Kind k = cs.kind();
            if (k.isFieldRead() && !showReads) return false;
            if (k.isFieldWrite() && !showWrites) return false;
            if (k.isInvoke() && !showInvokes) return false;
            if (k.isTypeUse() && !showTypes) return false;
            return true;
        };
    }

    private static void populateGrouped(TreeItem<Node> root, List<CallSite> kept) {
        // class -> method -> list of CallSite
        Map<String, Map<String, List<CallSite>>> byClass = new LinkedHashMap<>();
        for (CallSite cs : kept) {
            String clsFqn = cs.inClassFqn().replace('/', '.');
            String methodSig = cs.inMethodName() + cs.inMethodDesc();
            byClass.computeIfAbsent(clsFqn, k -> new LinkedHashMap<>())
                    .computeIfAbsent(methodSig, k -> new ArrayList<>())
                    .add(cs);
        }
        byClass.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
                .forEach(classEntry -> {
                    int total = classEntry.getValue().values().stream().mapToInt(List::size).sum();
                    TreeItem<Node> classItem = new TreeItem<>(Node.cls(classEntry.getKey(), total));
                    classItem.setExpanded(true);
                    classEntry.getValue().entrySet().stream()
                            .sorted(Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
                            .forEach(methodEntry -> {
                                TreeItem<Node> methodItem = new TreeItem<>(
                                        Node.method(methodEntry.getKey(), methodEntry.getValue().size()));
                                methodItem.setExpanded(true);
                                for (CallSite cs : methodEntry.getValue()) {
                                    methodItem.getChildren().add(new TreeItem<>(Node.site(cs)));
                                }
                                classItem.getChildren().add(methodItem);
                            });
                    root.getChildren().add(classItem);
                });
    }

    private static void populateFlat(TreeItem<Node> root, List<CallSite> kept) {
        for (CallSite cs : kept) {
            root.getChildren().add(new TreeItem<>(Node.site(cs)));
        }
    }

    private void openSelected() {
        TreeItem<Node> sel = tree.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Node n = sel.getValue();
        if (n != null && n.callSite != null && onOpen != null) {
            onOpen.accept(n.callSite);
        }
    }

    private String copyText(Node n) {
        if (n == null) return "";
        if (n.callSite != null) {
            CallSite cs = n.callSite;
            return cs.inClassFqn().replace('/', '.') + "#" + cs.inMethodName()
                    + (cs.lineNumber() > 0 ? ":L" + cs.lineNumber() : "");
        }
        return n.label;
    }

    // ========================================================================
    // Tree node model — one type for class-row / method-row / call-site-row
    // ========================================================================

    private static final class Node {
        enum Kind { ROOT, CLASS, METHOD, CALL_SITE }
        final Kind kind;
        final String label;
        final int count;
        final CallSite callSite;

        private Node(Kind kind, String label, int count, CallSite cs) {
            this.kind = kind;
            this.label = label;
            this.count = count;
            this.callSite = cs;
        }

        static Node root() { return new Node(Kind.ROOT, "", 0, null); }
        static Node cls(String fqn, int count) { return new Node(Kind.CLASS, fqn, count, null); }
        static Node method(String sig, int count) { return new Node(Kind.METHOD, sig, count, null); }
        static Node site(CallSite cs) { return new Node(Kind.CALL_SITE, "", 0, cs); }
    }

    // ========================================================================
    // Cell renderer — class/method rows get a count badge, call sites get full detail
    // ========================================================================

    private final class XrefCell extends TreeCell<Node> {
        @Override
        protected void updateItem(Node item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            switch (item.kind) {
                case CLASS -> renderClass(item);
                case METHOD -> renderMethod(item);
                case CALL_SITE -> renderCallSite(item.callSite);
                default -> { setText(null); setGraphic(null); }
            }
        }

        private void renderClass(Node n) {
            FontIcon icon = new FontIcon("mdi2c-code-braces");
            icon.setIconSize(14);
            icon.getStyleClass().add("usage-class-icon");
            Label name = new Label(n.label);
            name.getStyleClass().add("usage-class-name");
            Label badge = new Label(String.valueOf(n.count));
            badge.getStyleClass().add("usage-group-badge");
            HBox row = new HBox(8, icon, name, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
        }

        private void renderMethod(Node n) {
            FontIcon icon = new FontIcon("mdi2f-function-variant");
            icon.setIconSize(14);
            icon.getStyleClass().add("usage-method-icon");
            Label name = new Label(n.label);
            name.getStyleClass().add("usage-method-name");
            Label badge = new Label(String.valueOf(n.count));
            badge.getStyleClass().add("usage-group-badge");
            HBox row = new HBox(8, icon, name, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
        }

        private void renderCallSite(CallSite cs) {
            FontIcon icon = iconFor(cs.kind());
            icon.setIconSize(13);
            icon.getStyleClass().add("usage-icon");

            Label kindLabel = new Label(kindText(cs.kind()));
            kindLabel.getStyleClass().add("usage-kind");

            Label target = new Label(formatTarget(cs));
            target.getStyleClass().add("usage-target");

            Label line = new Label(cs.lineNumber() > 0 ? "L" + cs.lineNumber() : "");
            line.getStyleClass().add("usage-line");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            HBox topRow = new HBox(8, icon, kindLabel, target, spacer, line);
            topRow.setAlignment(Pos.CENTER_LEFT);

            // Optional snippet preview under the row — only shown when the provider returns
            // non-null text (i.e. we have decompiled source cached for this class). Renders
            // as a smaller monospace-styled label aligned with the icon column.
            String snippet = snippetProvider.apply(cs);
            if (snippet != null && !snippet.isEmpty()) {
                Label snippetLabel = new Label(snippet);
                snippetLabel.getStyleClass().add("usage-snippet");
                snippetLabel.setMaxWidth(Double.MAX_VALUE);
                snippetLabel.setPadding(new Insets(0, 0, 0, 24));
                VBox col = new VBox(0, topRow, snippetLabel);
                col.setPadding(new Insets(2, 4, 2, 4));
                setGraphic(col);
            } else {
                topRow.setPadding(new Insets(2, 4, 2, 4));
                setGraphic(topRow);
            }
            setText(null);
        }
    }

    private static FontIcon iconFor(CallSite.Kind kind) {
        return new FontIcon(switch (kind) {
            case INVOKE_VIRTUAL, INVOKE_STATIC, INVOKE_SPECIAL, INVOKE_INTERFACE, INVOKE_DYNAMIC ->
                    "mdi2f-function-variant";
            case GETFIELD, GETSTATIC -> "mdi2a-arrow-down-bold-box-outline";
            case PUTFIELD, PUTSTATIC -> "mdi2a-arrow-up-bold-box-outline";
            case NEW -> "mdi2p-plus-box-outline";
            case CHECKCAST, INSTANCEOF -> "mdi2c-cube-outline";
            case ANEWARRAY -> "mdi2a-array";
            case TYPE_IN_SIGNATURE -> "mdi2f-format-letter-case";
        });
    }

    private static String kindText(CallSite.Kind kind) {
        return switch (kind) {
            case INVOKE_VIRTUAL -> "invokevirtual";
            case INVOKE_STATIC -> "invokestatic";
            case INVOKE_SPECIAL -> "invokespecial";
            case INVOKE_INTERFACE -> "invokeinterface";
            case INVOKE_DYNAMIC -> "invokedynamic";
            case GETFIELD -> "read field";
            case PUTFIELD -> "write field";
            case GETSTATIC -> "read static";
            case PUTSTATIC -> "write static";
            case NEW -> "new";
            case CHECKCAST -> "checkcast";
            case INSTANCEOF -> "instanceof";
            case ANEWARRAY -> "anewarray";
            case TYPE_IN_SIGNATURE -> "type-ref";
        };
    }

    private static String formatTarget(CallSite cs) {
        String owner = cs.targetOwner() == null ? "" : cs.targetOwner().replace('/', '.');
        if (cs.targetName() == null || cs.targetName().isEmpty()) {
            return owner;
        }
        return owner + "." + cs.targetName() + (cs.targetDesc() == null ? "" : cs.targetDesc());
    }

    @SuppressWarnings("unused")
    private SimpleBooleanProperty reserved() { return new SimpleBooleanProperty(false); }
}
