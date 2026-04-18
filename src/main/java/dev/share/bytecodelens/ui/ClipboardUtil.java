package dev.share.bytecodelens.ui;

import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/** Shared helpers for "copy to clipboard" across tables, labels and other widgets. */
public final class ClipboardUtil {

    private ClipboardUtil() {}

    public static void copyToClipboard(String text) {
        if (text == null) return;
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    /**
     * Installs Ctrl+C handler and a context menu with Copy-cell / Copy-row / Copy-column actions
     * on the given TableView. Cells and rows use toString() by default.
     */
    public static <S> void installTableCopy(TableView<S> table) {
        table.setEditable(false);
        table.getSelectionModel().setCellSelectionEnabled(true);

        table.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(e)) {
                String value = cellValueOrRow(table);
                if (value != null && !value.isEmpty()) {
                    copyToClipboard(value);
                    e.consume();
                }
            }
        });

        javafx.scene.control.ContextMenu menu = table.getContextMenu();
        if (menu == null) {
            menu = new javafx.scene.control.ContextMenu();
            table.setContextMenu(menu);
        }

        MenuItem copyCell = createItem("Copy cell", "mdi2c-content-copy",
                e -> {
                    String v = cellValue(table);
                    if (v != null) copyToClipboard(v);
                });
        MenuItem copyRow = createItem("Copy row", "mdi2t-table-row",
                e -> {
                    String v = rowValue(table);
                    if (v != null) copyToClipboard(v);
                });
        MenuItem copyColumn = createItem("Copy column", "mdi2t-table-column",
                e -> {
                    String v = columnValue(table);
                    if (v != null) copyToClipboard(v);
                });

        if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }
        menu.getItems().addAll(copyCell, copyRow, copyColumn);
    }

    private static MenuItem createItem(String text, String icon, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        MenuItem mi = new MenuItem(text);
        FontIcon i = new FontIcon(icon);
        i.setIconSize(14);
        mi.setGraphic(i);
        mi.setOnAction(action);
        return mi;
    }

    private static <S> String cellValueOrRow(TableView<S> table) {
        @SuppressWarnings("unchecked")
        var positions = (List<TablePosition<S, ?>>) (List<?>) table.getSelectionModel().getSelectedCells();
        if (!positions.isEmpty()) {
            TablePosition<S, ?> pos = positions.get(0);
            return renderCell(table, pos);
        }
        return rowValue(table);
    }

    private static <S> String cellValue(TableView<S> table) {
        @SuppressWarnings("unchecked")
        var positions = (List<TablePosition<S, ?>>) (List<?>) table.getSelectionModel().getSelectedCells();
        if (positions.isEmpty()) return null;
        return renderCell(table, positions.get(0));
    }

    private static <S> String rowValue(TableView<S> table) {
        S item = table.getSelectionModel().getSelectedItem();
        if (item == null) return null;
        StringBuilder sb = new StringBuilder();
        List<TableColumn<S, ?>> cols = table.getVisibleLeafColumns();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append('\t');
            Object v = cols.get(i).getCellData(item);
            sb.append(v == null ? "" : v.toString());
        }
        return sb.toString();
    }

    private static <S> String columnValue(TableView<S> table) {
        @SuppressWarnings("unchecked")
        var positions = (List<TablePosition<S, ?>>) (List<?>) table.getSelectionModel().getSelectedCells();
        TableColumn<S, ?> column;
        if (!positions.isEmpty()) {
            column = positions.get(0).getTableColumn();
        } else {
            var visible = table.getVisibleLeafColumns();
            if (visible.isEmpty()) return null;
            column = visible.get(0);
        }
        StringBuilder sb = new StringBuilder();
        for (S item : table.getItems()) {
            Object v = column.getCellData(item);
            sb.append(v == null ? "" : v.toString()).append('\n');
        }
        return sb.toString();
    }

    /** Installs Ctrl+C / context menu Copy on a ListView, using the cell's toString or provided formatter. */
    public static <S> void installListCopy(javafx.scene.control.ListView<S> list,
                                           java.util.function.Function<S, String> formatter) {
        list.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(e)) {
                S item = list.getSelectionModel().getSelectedItem();
                if (item != null) {
                    String s = formatter == null ? String.valueOf(item) : formatter.apply(item);
                    if (s != null && !s.isEmpty()) copyToClipboard(s);
                    e.consume();
                }
            }
        });
        javafx.scene.control.ContextMenu menu = list.getContextMenu();
        if (menu == null) {
            menu = new javafx.scene.control.ContextMenu();
            list.setContextMenu(menu);
        }
        MenuItem copy = createItem("Copy", "mdi2c-content-copy", e -> {
            S item = list.getSelectionModel().getSelectedItem();
            if (item != null) {
                String s = formatter == null ? String.valueOf(item) : formatter.apply(item);
                if (s != null) copyToClipboard(s);
            }
        });
        MenuItem copyAll = createItem("Copy all", "mdi2t-text-box-multiple-outline", e -> {
            StringBuilder sb = new StringBuilder();
            for (S it : list.getItems()) {
                String s = formatter == null ? String.valueOf(it) : formatter.apply(it);
                if (s != null) sb.append(s).append('\n');
            }
            copyToClipboard(sb.toString());
        });
        if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }
        menu.getItems().addAll(copy, copyAll);
    }

    /** Installs Ctrl+C / context menu Copy on a TreeView. Formatter runs on the selected node's value. */
    public static <S> void installTreeCopy(javafx.scene.control.TreeView<S> tree,
                                           java.util.function.Function<S, String> formatter) {
        tree.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(e)) {
                javafx.scene.control.TreeItem<S> item = tree.getSelectionModel().getSelectedItem();
                if (item != null) {
                    String s = formatter == null ? String.valueOf(item.getValue()) : formatter.apply(item.getValue());
                    if (s != null && !s.isEmpty()) copyToClipboard(s);
                    e.consume();
                }
            }
        });
        javafx.scene.control.ContextMenu menu = tree.getContextMenu();
        if (menu == null) {
            menu = new javafx.scene.control.ContextMenu();
            tree.setContextMenu(menu);
        }
        MenuItem copy = createItem("Copy", "mdi2c-content-copy", e -> {
            javafx.scene.control.TreeItem<S> item = tree.getSelectionModel().getSelectedItem();
            if (item != null) {
                String s = formatter == null ? String.valueOf(item.getValue()) : formatter.apply(item.getValue());
                if (s != null) copyToClipboard(s);
            }
        });
        if (!menu.getItems().isEmpty()) menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        menu.getItems().add(copy);
    }

    /** Attaches a right-click "Copy value" context menu to any Label. */
    public static void installLabelCopy(javafx.scene.control.Label label) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        MenuItem copy = createItem("Copy value", "mdi2c-content-copy",
                e -> copyToClipboard(label.getText()));
        menu.getItems().add(copy);
        label.setOnContextMenuRequested(e -> menu.show(label, e.getScreenX(), e.getScreenY()));
    }

    private static <S> String renderCell(TableView<S> table, TablePosition<S, ?> pos) {
        S row = table.getItems().get(pos.getRow());
        TableColumn<S, ?> col = pos.getTableColumn();
        if (col == null) return null;
        Object v = col.getCellData(row);
        return v == null ? "" : v.toString();
    }
}
