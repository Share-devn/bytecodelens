package dev.share.bytecodelens.ui.views;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Map;
import java.util.function.Supplier;

/**
 * "Tools" sidebar tab — groups on-demand analyses that don't need to track the caret:
 * <ul>
 *     <li>Checksums (CRC32, MD5, SHA-1, SHA-256, SHA-512) over the whole file</li>
 *     <li>Entropy area-chart showing where the compressed / encrypted regions sit</li>
 *     <li>"Diff with…" button that opens the binary-diff window against another file</li>
 * </ul>
 *
 * <p>Computations happen on-demand when the user presses the Compute button rather
 * than on every byte change — SHA-512 over 10 MB is quick but still not free.</p>
 */
public final class HexToolsPanel extends VBox {

    private final Label[] hashValueLabels = new Label[5];   // CRC32, MD5, SHA-1, SHA-256, SHA-512
    private static final String[] ALGO_LABELS = {"CRC32", "MD5", "SHA-1", "SHA-256", "SHA-512"};

    private final AreaChart<Number, Number> entropyChart;
    private final XYChart.Series<Number, Number> entropySeries = new XYChart.Series<>();
    private final Label entropySummary = new Label();

    private final Label sizeLabel = new Label();
    private final Label diffStatus = new Label();

    private Supplier<byte[]> bytesSupplier = () -> new byte[0];
    private Runnable onDiffRequested;

    public HexToolsPanel() {
        getStyleClass().add("hex-tools-panel");
        setSpacing(10);
        setPadding(new Insets(8));
        setPrefWidth(280);

        getChildren().add(buildSizeRow());
        getChildren().add(new Separator());
        getChildren().add(buildChecksumsSection());
        getChildren().add(new Separator());

        NumberAxis xAxis = new NumberAxis();
        xAxis.setForceZeroInRange(true);
        xAxis.setLabel("window #");
        NumberAxis yAxis = new NumberAxis(0, 8, 1);
        yAxis.setLabel("bits / byte");
        entropyChart = new AreaChart<>(xAxis, yAxis);
        entropyChart.setAnimated(false);
        entropyChart.setCreateSymbols(false);
        entropyChart.setLegendVisible(false);
        entropyChart.setTitle("Entropy (Shannon, windowed)");
        entropyChart.getData().add(entropySeries);
        entropyChart.setPrefHeight(180);
        getChildren().add(buildEntropySection());

        getChildren().add(new Separator());
        getChildren().add(buildDiffSection());
    }

    // ========================================================================
    // Public API
    // ========================================================================

    public void setBytesSupplier(Supplier<byte[]> supplier) {
        this.bytesSupplier = supplier == null ? () -> new byte[0] : supplier;
        refreshBase();
    }

    public void setOnDiffRequested(Runnable handler) {
        this.onDiffRequested = handler;
    }

    /** Called by HexView when bytes change — updates the size display and clears computations. */
    public void onBytesChanged() {
        refreshBase();
        clearHashes();
        entropySeries.getData().clear();
        entropySummary.setText("");
    }

    // ========================================================================
    // Size row
    // ========================================================================

    private HBox buildSizeRow() {
        Label title = new Label("File size:");
        title.getStyleClass().add("hex-tools-label");
        sizeLabel.getStyleClass().add("hex-tools-value");
        HBox row = new HBox(6, title, sizeLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void refreshBase() {
        byte[] b = bytesSupplier.get();
        sizeLabel.setText(b == null ? "—" : formatBytes(b.length));
    }

    // ========================================================================
    // Checksums
    // ========================================================================

    private VBox buildChecksumsSection() {
        Label title = new Label("Checksums");
        title.getStyleClass().add("hex-tools-section-title");

        Button compute = new Button("Compute hashes");
        compute.setGraphic(new FontIcon("mdi2s-shield-check-outline"));
        compute.setOnAction(e -> computeChecksums());

        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(4);
        for (int i = 0; i < ALGO_LABELS.length; i++) {
            Label name = new Label(ALGO_LABELS[i]);
            name.getStyleClass().add("hex-tools-hash-label");
            name.setMinWidth(60);

            TextField value = new TextField();
            value.setEditable(false);
            value.getStyleClass().add("hex-tools-hash-value");
            HBox.setHgrow(value, Priority.ALWAYS);
            hashValueLabels[i] = new Label("");  // retained for compatibility
            // Use a TextField so the user can triple-click to copy.
            value.setTooltip(new Tooltip("Select + Ctrl+C to copy, or click the button."));

            Button copy = new Button("Copy");
            copy.getStyleClass().add("hex-tools-copy-btn");
            final int idx = i;
            copy.setOnAction(e -> {
                String v = value.getText();
                if (v != null && !v.isEmpty()) {
                    Clipboard.getSystemClipboard().setContent(
                            Map.of(DataFormat.PLAIN_TEXT, v));
                }
            });

            grid.add(name, 0, i);
            grid.add(value, 1, i);
            grid.add(copy, 2, i);
            // Stash the TextField into the label field — simpler than tracking two arrays.
            hashValueLabels[i].setUserData(value);
        }

        return new VBox(6, title, compute, grid);
    }

    private void computeChecksums() {
        byte[] b = bytesSupplier.get();
        if (b == null) b = new byte[0];
        // Run on a background thread — SHA-512 of a large file can take a second.
        final byte[] bytes = b;
        Thread t = new Thread(() -> {
            Map<String, String> hashes = HexChecksums.compute(bytes);
            javafx.application.Platform.runLater(() -> {
                for (int i = 0; i < ALGO_LABELS.length; i++) {
                    TextField tf = (TextField) hashValueLabels[i].getUserData();
                    tf.setText(hashes.getOrDefault(ALGO_LABELS[i], ""));
                }
            });
        }, "hex-hashes");
        t.setDaemon(true);
        t.start();
    }

    private void clearHashes() {
        for (Label lbl : hashValueLabels) {
            TextField tf = (TextField) lbl.getUserData();
            if (tf != null) tf.setText("");
        }
    }

    // ========================================================================
    // Entropy
    // ========================================================================

    private VBox buildEntropySection() {
        Label title = new Label("Entropy analysis");
        title.getStyleClass().add("hex-tools-section-title");

        Button compute = new Button("Compute entropy");
        compute.setGraphic(new FontIcon("mdi2c-chart-areaspline"));
        compute.setOnAction(e -> computeEntropy());

        entropySummary.getStyleClass().add("hex-tools-value");

        VBox box = new VBox(6, title, compute, entropyChart, entropySummary);
        VBox.setVgrow(entropyChart, Priority.ALWAYS);
        return box;
    }

    private void computeEntropy() {
        byte[] b = bytesSupplier.get();
        if (b == null || b.length == 0) {
            entropySeries.getData().clear();
            entropySummary.setText("No bytes.");
            return;
        }
        // Window chosen so we end up with ~200 data points — dense enough to see
        // compressed/encrypted stretches but not so dense it freezes the chart.
        int window = HexEntropy.suggestedWindowSize(b.length);
        double[] samples = HexEntropy.windowed(b, window);
        double total = HexEntropy.entropy(b);
        entropySeries.getData().clear();
        for (int i = 0; i < samples.length; i++) {
            entropySeries.getData().add(new XYChart.Data<>(i, samples[i]));
        }
        entropySummary.setText(String.format(java.util.Locale.ROOT,
                "Overall: %.2f bits/byte • window %d B • %d samples",
                total, window, samples.length));
    }

    // ========================================================================
    // Diff button
    // ========================================================================

    private VBox buildDiffSection() {
        Label title = new Label("Binary diff");
        title.getStyleClass().add("hex-tools-section-title");

        Button open = new Button("Diff with file...");
        open.setGraphic(new FontIcon("mdi2c-compare-horizontal"));
        open.setOnAction(e -> {
            if (onDiffRequested != null) onDiffRequested.run();
        });

        diffStatus.getStyleClass().add("hex-tools-value");
        diffStatus.setWrapText(true);

        ScrollPane sp = new ScrollPane(diffStatus);
        sp.setFitToWidth(true);
        sp.setPrefHeight(40);

        VBox box = new VBox(6, title, open, sp);
        return box;
    }

    /** Called by HexView after it completed a diff, to show a mini-summary. */
    public void showDiffSummary(String text) {
        diffStatus.setText(text == null ? "" : text);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.2f MB", n / (1024.0 * 1024.0));
    }

    @SuppressWarnings("unused")
    private static Region spacer() { return new Region(); }
}
