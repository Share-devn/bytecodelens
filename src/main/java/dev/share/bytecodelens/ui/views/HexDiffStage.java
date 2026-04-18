package dev.share.bytecodelens.ui.views;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Side-by-side hex diff window. Shows both files as hex+ASCII, with changed bytes
 * painted amber, only-in-left red, only-in-right green. Summary bar at top counts
 * bytes of each category so the user gets the numeric picture before scrolling.
 */
public final class HexDiffStage {

    private static final int BYTES_PER_ROW = 16;

    private final Stage stage = new Stage();
    private final CodeArea leftArea = new CodeArea();
    private final CodeArea rightArea = new CodeArea();

    public HexDiffStage(String leftName, byte[] leftBytes,
                        String rightName, byte[] rightBytes,
                        boolean darkTheme) {

        List<HexDiff.Region> regions = HexDiff.diff(leftBytes, rightBytes);

        configureArea(leftArea);
        configureArea(rightArea);
        renderWithDiff(leftArea, leftBytes, regions, true);
        renderWithDiff(rightArea, rightBytes, regions, false);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("hex-diff-root");

        // Header with filenames.
        Label leftLabel = new Label(leftName);
        leftLabel.getStyleClass().add("hex-diff-filename");
        Label rightLabel = new Label(rightName);
        rightLabel.getStyleClass().add("hex-diff-filename");
        HBox nameRow = new HBox(0, leftLabel, spacer(), rightLabel);
        nameRow.setPadding(new Insets(6, 10, 2, 10));
        nameRow.setAlignment(Pos.CENTER_LEFT);

        // Summary.
        int changed = HexDiff.bytesWith(regions, HexDiff.Side.CHANGED);
        int onlyLeft = HexDiff.bytesWith(regions, HexDiff.Side.ONLY_LEFT);
        int onlyRight = HexDiff.bytesWith(regions, HexDiff.Side.ONLY_RIGHT);
        int same = HexDiff.bytesWith(regions, HexDiff.Side.SAME);
        Label summary = new Label(String.format(
                "%d same  •  %d changed  •  %d only in left  •  %d only in right",
                same, changed, onlyLeft, onlyRight));
        summary.getStyleClass().add("hex-diff-summary");
        VBox header = new VBox(nameRow, summary);
        header.setPadding(new Insets(4, 10, 6, 10));
        header.getStyleClass().add("hex-diff-header");

        HBox splitRow = new HBox(8,
                new VirtualizedScrollPane<>(leftArea),
                new VirtualizedScrollPane<>(rightArea));
        HBox.setHgrow(splitRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(splitRow.getChildren().get(1), Priority.ALWAYS);

        root.setTop(header);
        root.setCenter(splitRow);

        Scene scene = new Scene(root, 1200, 720);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        root.getStyleClass().add(darkTheme ? "dark-theme" : "light-theme");
        stage.setScene(scene);
        stage.setTitle("Hex Diff — " + leftName + " vs " + rightName);
        dev.share.bytecodelens.util.Icons.apply(stage);
    }

    public void show() { stage.show(); }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static void configureArea(CodeArea area) {
        area.setEditable(false);
        area.getStyleClass().add("hex-view");
        area.setStyle("-fx-font-family: 'JetBrains Mono', 'Cascadia Code', 'Consolas', monospace;"
                + "-fx-font-size: 12px; -fx-font-smoothing-type: lcd;");
        area.setParagraphGraphicFactory(LineNumberFactory.get(area,
                i -> String.format("%08x", i * BYTES_PER_ROW)));
    }

    /**
     * Render bytes into the hex area, picking the CSS class for each byte based on its
     * status in the diff regions. {@code leftSide=true} reads the left stream, else right.
     */
    private static void renderWithDiff(CodeArea area, byte[] bytes,
                                       List<HexDiff.Region> regions, boolean leftSide) {
        if (bytes == null) bytes = new byte[0];
        StringBuilder text = new StringBuilder(bytes.length * 5);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();

        // Precompute a per-byte class lookup — O(N) at worst.
        String[] perByte = new String[bytes.length];
        for (HexDiff.Region r : regions) {
            String cls = switch (r.side()) {
                case SAME -> null;  // no extra styling
                case CHANGED -> "hex-diff-changed";
                case ONLY_LEFT -> leftSide ? "hex-diff-only-left" : null;
                case ONLY_RIGHT -> leftSide ? null : "hex-diff-only-right";
            };
            if (cls == null) continue;
            for (int i = r.offset(); i < r.offset() + r.length() && i < bytes.length; i++) {
                perByte[i] = cls;
            }
        }

        for (int rowStart = 0; rowStart < bytes.length; rowStart += BYTES_PER_ROW) {
            int rowEnd = Math.min(rowStart + BYTES_PER_ROW, bytes.length);

            for (int i = rowStart; i < rowEnd; i++) {
                int b = bytes[i] & 0xFF;
                text.append(String.format("%02x", b));
                String extra = perByte[i];
                if (extra != null) {
                    spans.add(java.util.Arrays.asList("hex-byte", extra), 2);
                } else {
                    spans.add(Collections.singleton("hex-byte"), 2);
                }
                if (i < rowEnd - 1) {
                    text.append(' ');
                    spans.add(Collections.emptyList(), 1);
                }
            }
            int missing = BYTES_PER_ROW - (rowEnd - rowStart);
            if (missing > 0) {
                int pad = missing * 3;
                for (int p = 0; p < pad; p++) text.append(' ');
                spans.add(Collections.emptyList(), pad);
            }
            text.append("  |");
            spans.add(Collections.emptyList(), 2);
            spans.add(Collections.singleton("hex-sep"), 1);
            for (int i = rowStart; i < rowEnd; i++) {
                int b = bytes[i] & 0xFF;
                char c = (b >= 0x20 && b < 0x7F) ? (char) b : '.';
                text.append(c);
                String extra = perByte[i];
                if (extra != null) {
                    spans.add(java.util.Arrays.asList("hex-ascii", extra), 1);
                } else {
                    spans.add(Collections.singleton("hex-ascii"), 1);
                }
            }
            text.append('|');
            spans.add(Collections.singleton("hex-sep"), 1);
            if (rowEnd < bytes.length) {
                text.append('\n');
                spans.add(Collections.emptyList(), 1);
            }
        }

        area.replaceText(text.toString());
        try {
            StyleSpans<Collection<String>> built = spans.create();
            area.setStyleSpans(0, built);
        } catch (Exception ignored) {}
        area.moveTo(0);
        area.scrollToPixel(0, 0);
    }
}
