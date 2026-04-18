package dev.share.bytecodelens.ui.views;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Sidebar that decodes the bytes at the current cursor offset into every primitive
 * type RE workflows need. Updated by {@link HexView} on each caret move.
 */
public final class DataInspectorPanel extends VBox {

    private final Label headerLabel = new Label("Data Inspector");
    private final Label offsetLabel = new Label("");
    private final TableView<Row> table = new TableView<>();

    public DataInspectorPanel() {
        getStyleClass().add("hex-inspector-panel");
        setSpacing(4);
        setPadding(new Insets(6, 8, 6, 8));
        setPrefWidth(260);

        headerLabel.getStyleClass().add("hex-inspector-header");
        offsetLabel.getStyleClass().add("hex-inspector-offset");

        TableColumn<Row, String> nameCol = new TableColumn<>("Type");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label));
        nameCol.setPrefWidth(78);
        TableColumn<Row, String> leCol = new TableColumn<>("LE");
        leCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().le));
        leCol.setPrefWidth(170);
        TableColumn<Row, String> beCol = new TableColumn<>("BE");
        beCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().be));
        beCol.setPrefWidth(170);
        table.getColumns().setAll(java.util.List.of(nameCol, leCol, beCol));
        table.setPlaceholder(new Label("Cursor outside of data"));
        table.getStyleClass().add("hex-inspector-table");
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);

        getChildren().addAll(headerLabel, offsetLabel, table);
    }

    /**
     * Refresh the table to show the byte at {@code offset} inside {@code bytes}. Pass
     * {@code offset < 0} or {@code bytes == null} to clear the panel.
     */
    public void update(byte[] bytes, int offset) {
        if (bytes == null || offset < 0 || offset >= bytes.length) {
            offsetLabel.setText("—");
            table.setItems(FXCollections.observableArrayList());
            return;
        }
        offsetLabel.setText("Offset 0x" + Integer.toHexString(offset).toUpperCase()
                + " (" + offset + ")");
        List<DataInterpreter.Field> fields = DataInterpreter.interpret(bytes, offset);
        List<Row> rows = new java.util.ArrayList<>(fields.size());
        for (var f : fields) rows.add(new Row(f.label(), f.littleEndian(), f.bigEndian()));
        table.setItems(FXCollections.observableArrayList(rows));
    }

    private record Row(String label, String le, String be) {}
}
