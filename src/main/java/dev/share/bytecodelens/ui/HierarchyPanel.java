package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.hierarchy.HierarchyIndex;
import dev.share.bytecodelens.hierarchy.HierarchyNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.function.Consumer;

public final class HierarchyPanel extends BorderPane {

    private final TreeView<HierarchyNode> supertypeTree = new TreeView<>();
    private final TreeView<HierarchyNode> subtypeTree = new TreeView<>();
    private final Label header = new Label("Right-click a class \u2192 Show Hierarchy");
    private HierarchyIndex index;
    private Consumer<String> onOpen;

    public HierarchyPanel() {
        getStyleClass().add("hierarchy-panel");

        header.getStyleClass().add("hierarchy-header");
        HBox headerBar = new HBox(header);
        headerBar.setPadding(new Insets(6, 12, 6, 12));
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.getStyleClass().add("hierarchy-header-bar");

        setupTree(supertypeTree, "mdi2a-arrow-up");
        setupTree(subtypeTree, "mdi2a-arrow-down");

        VBox leftBox = buildSide("SUPERTYPES", "mdi2a-arrow-up", supertypeTree);
        VBox rightBox = buildSide("SUBTYPES", "mdi2a-arrow-down", subtypeTree);
        SplitPane split = new SplitPane(leftBox, rightBox);
        split.setDividerPositions(0.5);
        split.getStyleClass().add("hierarchy-split");

        setTop(headerBar);
        setCenter(split);
    }

    public void setIndex(HierarchyIndex index) {
        this.index = index;
        if (index == null) {
            clear();
        }
    }

    public void setOnOpen(Consumer<String> handler) {
        this.onOpen = handler;
    }

    public void showHierarchyOf(String internalName) {
        if (index == null) {
            header.setText("Hierarchy index not ready");
            return;
        }
        header.setText("Hierarchy of  " + internalName.replace('/', '.'));

        HierarchyNode ancestors = index.buildAncestorChain(internalName);
        HierarchyNode subs = index.buildSubtypeTree(internalName);

        supertypeTree.setRoot(toTreeItem(ancestors, internalName, true));
        subtypeTree.setRoot(toTreeItem(subs, internalName, true));
        supertypeTree.setShowRoot(true);
        subtypeTree.setShowRoot(true);
    }

    public void clear() {
        header.setText("Right-click a class \u2192 Show Hierarchy");
        supertypeTree.setRoot(null);
        subtypeTree.setRoot(null);
    }

    private static void setupTree(TreeView<HierarchyNode> tree, String arrowIcon) {
        tree.getStyleClass().add("hierarchy-tree");
    }

    private VBox buildSide(String title, String iconLiteral, TreeView<HierarchyNode> tree) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(13);
        icon.getStyleClass().add("hierarchy-side-icon");
        Label label = new Label(title);
        label.getStyleClass().add("panel-title");
        HBox head = new HBox(6, icon, label);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(6, 12, 4, 12));

        VBox box = new VBox(head, tree);
        javafx.scene.layout.VBox.setVgrow(tree, javafx.scene.layout.Priority.ALWAYS);
        box.getStyleClass().add("hierarchy-side");

        tree.setCellFactory(tv -> new HierarchyCell(fqn -> {
            if (onOpen != null && index != null && index.isInJar(fqn)) {
                onOpen.accept(fqn);
            }
        }));
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 2) {
                var sel = tree.getSelectionModel().getSelectedItem();
                if (sel != null && onOpen != null && index != null
                        && index.isInJar(sel.getValue().internalName())) {
                    onOpen.accept(sel.getValue().internalName());
                }
            }
        });
        return box;
    }

    private TreeItem<HierarchyNode> toTreeItem(HierarchyNode node, String highlightName, boolean expanded) {
        TreeItem<HierarchyNode> item = new TreeItem<>(node);
        item.setExpanded(expanded);
        for (HierarchyNode child : node.children()) {
            item.getChildren().add(toTreeItem(child, highlightName, true));
        }
        return item;
    }

    private static final class HierarchyCell extends TreeCell<HierarchyNode> {
        private final Consumer<String> onOpen;

        HierarchyCell(Consumer<String> onOpen) {
            this.onOpen = onOpen;
            getStyleClass().add("hierarchy-cell");
        }

        @Override
        protected void updateItem(HierarchyNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            FontIcon icon = iconFor(item.kind());
            icon.setIconSize(14);
            icon.getStyleClass().add("hierarchy-icon");
            icon.getStyleClass().add("hierarchy-icon-" + item.kind().name().toLowerCase());
            setGraphic(icon);
            setText(item.displayName());

            if (item.kind() == HierarchyNode.Kind.EXTERNAL) {
                getStyleClass().removeAll("hierarchy-cell-internal");
                if (!getStyleClass().contains("hierarchy-cell-external")) {
                    getStyleClass().add("hierarchy-cell-external");
                }
            } else {
                getStyleClass().removeAll("hierarchy-cell-external");
                if (!getStyleClass().contains("hierarchy-cell-internal")) {
                    getStyleClass().add("hierarchy-cell-internal");
                }
            }
        }

        private static FontIcon iconFor(HierarchyNode.Kind kind) {
            return new FontIcon(switch (kind) {
                case INTERFACE -> "mdi2c-cube-outline";
                case ABSTRACT_CLASS -> "mdi2a-alpha-a-circle-outline";
                case ENUM -> "mdi2f-format-list-bulleted-type";
                case ANNOTATION -> "mdi2a-at";
                case RECORD -> "mdi2d-database-outline";
                case EXTERNAL -> "mdi2h-help-circle-outline";
                default -> "mdi2c-code-braces";
            });
        }
    }
}
