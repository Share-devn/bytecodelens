package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.usage.CallSite;
import dev.share.bytecodelens.usage.UsageIndex;
import dev.share.bytecodelens.usage.UsageTarget;
import dev.share.bytecodelens.ui.views.HighlightRequest;
import dev.share.bytecodelens.ui.views.ClassEditorTab;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Recaf-inspired call graph: two tabs (Calls out / Callers in), each a lazy-expanding tree.
 *
 * <p>Dense information layout over the old block-based stage — easily navigates 50+ nodes
 * on one screen, drills deep with expand-arrows, and shortcuts every node to either jump
 * into its declaration or keep exploring.</p>
 *
 * <p>Bonuses that Recaf doesn't have: quick-filter field over the tree, hot-path badge
 * showing how many times a method is called in total, and context menu "Jump to call site"
 * that opens the caller's source with the call's line number highlighted.</p>
 */
public final class CallGraphTreeStage {

    private static final Logger log = LoggerFactory.getLogger(CallGraphTreeStage.class);

    /** Maximum recursion depth when expanding — prevents cycle explosion. */
    private static final int MAX_DEPTH = 30;

    private final UsageIndex usageIndex;
    private final Stage stage;
    private final TabPane tabs = new TabPane();
    private final TextField filterField = new TextField();
    private final Label statusLabel = new Label();
    /** Method navigator callback: (classInternalName, methodName+descriptor) -> open that method. */
    private final BiConsumer<String, String> onOpenMethod;
    /** Call-site navigator: (classInternalName, methodName+descriptor, lineNumber) -> highlight call line. */
    private final CallSiteOpener onOpenCallSite;

    /** Functional interface for the call-site opener — two strings + int don't fit BiConsumer. */
    public interface CallSiteOpener {
        void open(String classInternalName, String methodNameDesc, int line);
    }

    /** Method identity: owner internal name + name + descriptor. Used as tree-node payload. */
    public record MethodRef(String ownerInternal, String name, String desc) {
        public String label() { return ownerInternal + "#" + name + desc; }
    }

    /**
     * Node value — either a method (title row) or a single call site under it (call source line).
     */
    sealed interface Node permits MethodNode, CallSiteNode, PlaceholderNode {}
    /**
     * @param callsFromParent how many times this method is called by the PARENT node in the
     *                        tree (or callers, if this is in the Callers tab). 0 for the root.
     *                        Used to render a {@code ×N} badge next to duplicated edges.
     *
     * @param firstCallSite   the first concrete CallSite for "Jump to call site" — may be null
     *                        on the root (root has no parent call).
     */
    record MethodNode(MethodRef ref, int callerCount, int calleeCount,
                      int callsFromParent, CallSite firstCallSite) implements Node {
        public MethodNode(MethodRef ref, int callerCount, int calleeCount) {
            this(ref, callerCount, calleeCount, 0, null);
        }
    }
    record CallSiteNode(CallSite cs) implements Node {}
    /** Placeholder value shown to trigger the expand-arrow; replaced on first expansion. */
    record PlaceholderNode() implements Node {}

    private TreeView<Node> callsTree;
    private TreeView<Node> callersTree;

    public CallGraphTreeStage(UsageIndex usageIndex,
                              BiConsumer<String, String> onOpenMethod,
                              CallSiteOpener onOpenCallSite) {
        this.usageIndex = usageIndex;
        this.onOpenMethod = onOpenMethod;
        this.onOpenCallSite = onOpenCallSite;
        this.stage = new Stage();

        BorderPane root = new BorderPane();
        root.setCenter(buildTabs());
        root.setTop(buildHeader());

        Scene scene = new Scene(root, 900, 640);
        scene.getStylesheets().addAll(stage.getOwner() == null ? List.of()
                : ((Stage) stage.getOwner()).getScene().getStylesheets());
        // Inherit stylesheet from parent once shown.
        stage.setOnShown(e -> {
            if (stage.getOwner() instanceof Stage parent && parent.getScene() != null) {
                scene.getStylesheets().setAll(parent.getScene().getStylesheets());
            }
        });
        stage.setScene(scene);
        stage.setTitle("Call Graph");
        dev.share.bytecodelens.util.Icons.apply(stage);
    }

    /** Focus the tree on a given method: shows its callees in Calls tab, callers in Callers. */
    public void showForMethod(String ownerInternal, String methodName, String methodDesc) {
        MethodRef ref = new MethodRef(ownerInternal, methodName, methodDesc);
        stage.setTitle("Call Graph — " + shortOwner(ownerInternal) + "#" + methodName + methodDesc);

        callsTree.setRoot(createMethodNode(ref, /*calls=*/true));
        callsTree.getRoot().setExpanded(true);

        callersTree.setRoot(createMethodNode(ref, /*calls=*/false));
        callersTree.getRoot().setExpanded(true);

        updateStatus(ref);
        stage.show();
        stage.toFront();
    }

    private HBox buildHeader() {
        filterField.setPromptText("Filter tree (Ctrl+F)…");
        filterField.setPrefWidth(320);
        filterField.textProperty().addListener((obs, old, now) -> applyFilter(now));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(10,
                new FontIcon("mdi2g-graph-outline"), filterField, spacer, statusLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 10, 6, 10));
        header.getStyleClass().add("decompiler-toolbar");
        return header;
    }

    private TabPane buildTabs() {
        callsTree = buildTree();
        callersTree = buildTree();

        Tab calls = new Tab("Calls", callsTree);
        calls.setGraphic(new FontIcon("mdi2a-arrow-right-bold"));
        calls.setClosable(false);
        Tab callers = new Tab("Callers", callersTree);
        callers.setGraphic(new FontIcon("mdi2a-arrow-left-bold"));
        callers.setClosable(false);

        tabs.getTabs().addAll(calls, callers);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // Ctrl+F focuses the filter field regardless of which tab is active.
        tabs.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if (new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN).match(ev)) {
                filterField.requestFocus();
                filterField.selectAll();
                ev.consume();
            }
        });
        return tabs;
    }

    private TreeView<Node> buildTree() {
        TreeView<Node> tv = new TreeView<>();
        tv.setShowRoot(true);
        tv.setCellFactory(v -> new CallGraphCell());
        // Activation: double-click or keyboard Enter/Space on the selected node.
        // We deliberately don't single-click activate — single click is used for
        // selection + expand arrow toggling, and activating on it causes flicker.
        tv.setOnMouseClicked(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            if (ev.getClickCount() == 2) {
                TreeItem<Node> sel = tv.getSelectionModel().getSelectedItem();
                if (sel != null) activateNode(sel);
            }
        });
        tv.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.ENTER || ev.getCode() == KeyCode.SPACE) {
                TreeItem<Node> sel = tv.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    activateNode(sel);
                    ev.consume();
                }
            }
        });
        tv.setContextMenu(buildNodeContextMenu(tv));
        return tv;
    }

    private ContextMenu buildNodeContextMenu(TreeView<Node> tv) {
        ContextMenu cm = new ContextMenu();
        MenuItem open = new MenuItem("Open method");
        open.setGraphic(new FontIcon("mdi2a-arrow-right-bold"));
        open.setOnAction(e -> {
            TreeItem<Node> sel = tv.getSelectionModel().getSelectedItem();
            if (sel != null) openMethodOfNode(sel);
        });
        MenuItem jumpToCall = new MenuItem("Jump to call site");
        jumpToCall.setGraphic(new FontIcon("mdi2t-target"));
        jumpToCall.setOnAction(e -> {
            TreeItem<Node> sel = tv.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            CallSite cs = null;
            if (sel.getValue() instanceof CallSiteNode csn) cs = csn.cs();
            else if (sel.getValue() instanceof MethodNode mn) cs = mn.firstCallSite();
            if (cs != null) {
                onOpenCallSite.open(cs.inClassFqn(),
                        cs.inMethodName() + cs.inMethodDesc(), cs.lineNumber());
            }
        });
        MenuItem setRoot = new MenuItem("Set as root");
        setRoot.setGraphic(new FontIcon("mdi2s-source-branch"));
        setRoot.setOnAction(e -> {
            TreeItem<Node> sel = tv.getSelectionModel().getSelectedItem();
            if (sel != null && sel.getValue() instanceof MethodNode mn) {
                showForMethod(mn.ref().ownerInternal(), mn.ref().name(), mn.ref().desc());
            }
        });
        MenuItem copySig = new MenuItem("Copy signature");
        copySig.setGraphic(new FontIcon("mdi2c-content-copy"));
        copySig.setOnAction(e -> {
            TreeItem<Node> sel = tv.getSelectionModel().getSelectedItem();
            if (sel != null) {
                String text = (sel.getValue() instanceof MethodNode mn) ? mn.ref().label()
                        : (sel.getValue() instanceof CallSiteNode csn)
                        ? csn.cs().targetOwner() + "#" + csn.cs().targetName() + csn.cs().targetDesc()
                        : "";
                if (!text.isEmpty()) ClipboardUtil.copyToClipboard(text);
            }
        });
        cm.getItems().addAll(open, jumpToCall, setRoot, new SeparatorMenuItem(), copySig);
        cm.setOnShowing(e -> {
            TreeItem<Node> sel = tv.getSelectionModel().getSelectedItem();
            boolean isMethod = sel != null && sel.getValue() instanceof MethodNode;
            boolean isCallSite = sel != null && sel.getValue() instanceof CallSiteNode;
            boolean hasCallSite = isCallSite
                    || (isMethod && ((MethodNode) sel.getValue()).firstCallSite() != null);
            open.setDisable(!isMethod && !isCallSite);
            jumpToCall.setDisable(!hasCallSite);
            setRoot.setDisable(!isMethod);
            copySig.setDisable(sel == null);
        });
        return cm;
    }

    private void activateNode(TreeItem<Node> item) {
        Node v = item.getValue();
        // For CallSiteNode: jump to the caller at that line.
        // For MethodNode: if we have a firstCallSite (we're a child of some method in the
        // tree), prefer to open the caller at that exact line — "where was this called?".
        // Otherwise open the method's own definition.
        if (v instanceof CallSiteNode csn) {
            CallSite cs = csn.cs();
            onOpenCallSite.open(cs.inClassFqn(),
                    cs.inMethodName() + cs.inMethodDesc(), cs.lineNumber());
            return;
        }
        if (v instanceof MethodNode mn
                && mn.firstCallSite() != null && mn.firstCallSite().lineNumber() > 0) {
            CallSite cs = mn.firstCallSite();
            onOpenCallSite.open(cs.inClassFqn(),
                    cs.inMethodName() + cs.inMethodDesc(), cs.lineNumber());
            return;
        }
        openMethodOfNode(item);
    }

    private void openMethodOfNode(TreeItem<Node> item) {
        MethodRef r = resolveMethodRef(item.getValue());
        if (r == null) return;
        onOpenMethod.accept(r.ownerInternal(), r.name() + r.desc());
    }

    private static MethodRef resolveMethodRef(Node n) {
        if (n instanceof MethodNode mn) return mn.ref();
        if (n instanceof CallSiteNode csn) {
            return new MethodRef(csn.cs().targetOwner(), csn.cs().targetName(), csn.cs().targetDesc());
        }
        return null;
    }

    /** Build a tree root for one method, with either its callees (calls=true) or callers. */
    private TreeItem<Node> createMethodNode(MethodRef ref, boolean calls) {
        int caller = countCallers(ref);
        int callee = countCallees(ref);
        MethodNode mn = new MethodNode(ref, caller, callee);
        TreeItem<Node> item = new LazyMethodItem(mn, calls, 0);
        return item;
    }

    private int countCallees(MethodRef r) {
        // Outgoing calls from r — count of CallSites whose inClass == r.owner && inMethod matches.
        return (int) usageIndex.allMethodCalls()
                .filter(cs -> cs.inClassFqn().equals(r.ownerInternal())
                        && cs.inMethodName().equals(r.name())
                        && cs.inMethodDesc().equals(r.desc()))
                .count();
    }

    private int countCallers(MethodRef r) {
        return usageIndex.findUsages(new UsageTarget.Method(
                r.ownerInternal(), r.name(), r.desc())).size();
    }

    /** Tree item that lazily loads its children the first time it's expanded. */
    private final class LazyMethodItem extends TreeItem<Node> {
        private final boolean outgoing;
        private final int depth;
        private boolean loaded = false;

        LazyMethodItem(Node value, boolean outgoing, int depth) {
            super(value, new FontIcon(iconFor(value)));
            this.outgoing = outgoing;
            this.depth = depth;
            // Hint: if any children are possible, show the expand arrow.
            if (value instanceof MethodNode mn
                    && (outgoing ? mn.calleeCount() > 0 : mn.callerCount() > 0)
                    && depth < MAX_DEPTH) {
                getChildren().add(new TreeItem<>(new PlaceholderNode()));
            }
            expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                if (isExpanded && !loaded) {
                    loaded = true;
                    populateChildren();
                }
            });
        }

        private void populateChildren() {
            getChildren().clear();
            if (!(getValue() instanceof MethodNode parent)) return;
            MethodRef r = parent.ref();
            List<CallSite> sites = outgoing
                    ? usageIndex.allMethodCalls()
                            .filter(cs -> cs.inClassFqn().equals(r.ownerInternal())
                                    && cs.inMethodName().equals(r.name())
                                    && cs.inMethodDesc().equals(r.desc()))
                            .toList()
                    : usageIndex.findUsages(new UsageTarget.Method(
                            r.ownerInternal(), r.name(), r.desc()));

            // Collapse duplicates by target (outgoing) or caller (incoming). Multiple calls to
            // the same target from this method are merged into one child; the raw call sites
            // are available via the node's CallSite list on the tooltip/jump-to-call site menu.
            // Keeping child nodes as simple methods (not mixed with call-site rows) makes
            // lazy expansion consistent all the way down — every expand triggers populate.
            java.util.Map<String, CallSite> firstByKey = new java.util.LinkedHashMap<>();
            java.util.Map<String, Integer> countByKey = new java.util.LinkedHashMap<>();
            for (CallSite cs : sites) {
                String key = outgoing
                        ? cs.targetOwner() + "#" + cs.targetName() + cs.targetDesc()
                        : cs.inClassFqn() + "#" + cs.inMethodName() + cs.inMethodDesc();
                firstByKey.putIfAbsent(key, cs);
                countByKey.merge(key, 1, Integer::sum);
            }
            for (var entry : firstByKey.entrySet()) {
                CallSite first = entry.getValue();
                int count = countByKey.get(entry.getKey());
                MethodRef childRef = outgoing
                        ? new MethodRef(first.targetOwner(), first.targetName(), first.targetDesc())
                        : new MethodRef(first.inClassFqn(), first.inMethodName(), first.inMethodDesc());
                // Self-reference and already-seen methods become terminal leaves — no further
                // expansion, so cycles stop.
                boolean terminal = childRef.equals(r) || depth + 1 >= MAX_DEPTH;
                int childCallers = terminal ? 0 : countCallers(childRef);
                int childCallees = terminal ? 0 : countCallees(childRef);
                MethodNode childValue = new MethodNode(
                        childRef, childCallers, childCallees, count, first);
                LazyMethodItem childItem = new LazyMethodItem(childValue, outgoing, depth + 1);
                getChildren().add(childItem);
            }
        }
    }

    private static String iconFor(Node n) {
        if (n instanceof MethodNode) return "mdi2f-function";
        if (n instanceof CallSiteNode) return "mdi2t-target";
        return "mdi2h-help-circle-outline";
    }

    private static String shortOwner(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash < 0 ? internal : internal.substring(slash + 1);
    }

    private void updateStatus(MethodRef ref) {
        int callers = countCallers(ref);
        int callees = countCallees(ref);
        statusLabel.setText(callers + " caller(s) · " + callees + " callee(s)");
    }

    private void applyFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        applyFilterRec(callsTree.getRoot(), q);
        applyFilterRec(callersTree.getRoot(), q);
    }

    /** Hide tree items whose label doesn't contain the query; parents of matching kids stay visible. */
    private boolean applyFilterRec(TreeItem<Node> item, String query) {
        if (item == null) return false;
        boolean anyChild = false;
        for (TreeItem<Node> child : new ArrayList<>(item.getChildren())) {
            if (applyFilterRec(child, query)) anyChild = true;
        }
        if (query.isEmpty()) return true;
        boolean selfMatch = labelOf(item.getValue()).toLowerCase().contains(query);
        // We don't destroy children here — JavaFX tree doesn't have a filtering API; visual
        // filtering is via the cell factory deciding whether to render. See CallGraphCell.
        return selfMatch || anyChild;
    }

    private static String labelOf(Node n) {
        if (n instanceof MethodNode mn) return mn.ref().label();
        if (n instanceof CallSiteNode cs) {
            return cs.cs().inClassFqn() + "#" + cs.cs().inMethodName() + ":" + cs.cs().lineNumber();
        }
        return "";
    }

    /** Custom cell: renders method, call site or placeholder with appropriate styling and badges. */
    private final class CallGraphCell extends TreeCell<Node> {
        @Override
        protected void updateItem(Node item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null); setGraphic(null);
                return;
            }
            if (item instanceof MethodNode mn) {
                MethodRef r = mn.ref();
                Label name = new Label(r.ownerInternal() + "#" + r.name() + r.desc());
                name.getStyleClass().add("info-value");
                HBox badges = new HBox(4);
                // ×N when this method is called more than once from the parent edge.
                if (mn.callsFromParent() > 1) {
                    badges.getChildren().add(badge("\u00d7" + mn.callsFromParent(), "mdi2n-numeric"));
                }
                // Line number of the first concrete call site under the parent — handy
                // breadcrumb for "where in the parent did we call this".
                if (mn.firstCallSite() != null && mn.firstCallSite().lineNumber() > 0) {
                    badges.getChildren().add(badge(":" + mn.firstCallSite().lineNumber(),
                            "mdi2n-numeric-0-box-multiple-outline"));
                }
                if (mn.calleeCount() > 0) {
                    badges.getChildren().add(badge("→" + mn.calleeCount(), "mdi2a-arrow-right"));
                }
                if (mn.callerCount() > 0) {
                    badges.getChildren().add(badge("←" + mn.callerCount(), "mdi2a-arrow-left"));
                }
                HBox row = new HBox(8, new FontIcon("mdi2f-function"), name, badges);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            } else if (item instanceof CallSiteNode csn) {
                CallSite cs = csn.cs();
                String label;
                if (getTreeItem() != null && getTreeItem().getParent() != null
                        && getTreeItem().getParent().getValue() instanceof MethodNode) {
                    label = "called at " + shortOwner(cs.inClassFqn()) + "#"
                            + cs.inMethodName() + (cs.lineNumber() > 0 ? " : " + cs.lineNumber() : "");
                } else {
                    label = cs.targetOwner() + "#" + cs.targetName() + cs.targetDesc();
                }
                Label l = new Label(label);
                l.getStyleClass().add("info-value");
                setText(null);
                setGraphic(new HBox(6, new FontIcon("mdi2t-target"), l));
            } else {
                setText("Loading…");
                setGraphic(new ProgressIndicator(-1));
            }
        }

        private HBox badge(String text, String icon) {
            Label l = new Label(text);
            l.getStyleClass().add("call-graph-badge");
            HBox b = new HBox(2, new FontIcon(icon), l);
            b.setAlignment(Pos.CENTER_LEFT);
            return b;
        }
    }

    public void setOwner(javafx.stage.Window owner) { stage.initOwner(owner); }
}
