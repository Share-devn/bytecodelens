package dev.share.bytecodelens.ui.views;

import dev.share.bytecodelens.structure.StructureDetector;
import dev.share.bytecodelens.structure.StructureNode;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Second tab of the hex sidebar — renders the parsed structure tree of the current
 * file (if any parser matches). Clicking a node fires {@link #onSelection} so the
 * hex viewer can scroll to the corresponding byte range and highlight it.
 */
public final class StructurePanel extends BorderPane {

    private final TreeView<StructureNode> tree = new TreeView<>();
    private final Label header = new Label("Structure");
    private final Label emptyLabel = new Label("No structure parser matched.\nHex-only view.");
    private Consumer<StructureNode> onSelection;

    public StructurePanel() {
        getStyleClass().add("hex-structure-panel");

        header.getStyleClass().add("hex-inspector-header");
        HBox top = new HBox(header);
        top.setPadding(new Insets(6, 8, 2, 8));
        setTop(top);

        tree.setShowRoot(true);
        tree.setCellFactory(tv -> new NodeCell());
        tree.getStyleClass().add("hex-structure-tree");
        tree.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null && onSelection != null) onSelection.accept(n.getValue());
        });

        emptyLabel.getStyleClass().add("hex-structure-empty");
        emptyLabel.setWrapText(true);
        emptyLabel.setPadding(new Insets(16));

        setCenter(emptyLabel);
    }

    public void setOnSelection(Consumer<StructureNode> handler) {
        this.onSelection = handler;
    }

    /** Build the tree from the given bytes. Clears if no parser matches. */
    public void update(byte[] bytes) {
        StructureNode root = StructureDetector.detect(bytes);
        if (root == null) {
            tree.setRoot(null);
            header.setText("Structure");
            setCenter(emptyLabel);
            return;
        }
        header.setText("Structure — " + root.label());
        TreeItem<StructureNode> rootItem = buildItem(root);
        rootItem.setExpanded(true);
        tree.setRoot(rootItem);
        setCenter(tree);
    }

    private static TreeItem<StructureNode> buildItem(StructureNode n) {
        TreeItem<StructureNode> item = new TreeItem<>(n);
        for (StructureNode c : n.children()) {
            item.getChildren().add(buildItem(c));
        }
        return item;
    }

    /** Cell with offset/length as small muted suffix so the tree stays skimmable. */
    private static final class NodeCell extends TreeCell<StructureNode> {
        @Override
        protected void updateItem(StructureNode n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) { setText(null); setGraphic(null); return; }
            String detailBit = n.detail() == null || n.detail().isEmpty() ? "" : " — " + n.detail();
            String rangeBit = " @ 0x" + Integer.toHexString(n.offset()).toUpperCase()
                    + " +" + n.length();
            Label primary = new Label(n.label() + detailBit);
            primary.getStyleClass().add("hex-structure-label");
            Label secondary = new Label(rangeBit);
            secondary.getStyleClass().add("hex-structure-range");
            HBox row = new HBox(8, primary, secondary);
            setGraphic(row);
            setText(null);
        }
    }

    @SuppressWarnings("unused")
    private static VBox unused() { return new VBox(); }  // keep the import resolver honest
}
