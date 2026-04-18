package dev.share.bytecodelens.ui.views;

import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Industrial-grade hex viewer, Session 1 (Core UX):
 * <ul>
 *   <li>Configurable bytes-per-row (8 / 16 / 24 / 32)</li>
 *   <li>Offset base toggle (hex / decimal)</li>
 *   <li>Find bar: hex bytes ({@code DE AD BE EF}) or ASCII text, next/prev, match count</li>
 *   <li>Goto offset (Ctrl+G), supports {@code 0x...} / decimal / negative = from end</li>
 *   <li>Selection drag + copy as hex / C-array / base64 / raw ASCII</li>
 * </ul>
 *
 * <p>Rendering is still CodeArea-based — it works fine up to a few MB, which covers
 * every jar resource we'd open. Session 2 will add a Data Inspector; later sessions
 * swap to Canvas when we need to scale past 10 MB.</p>
 */
public final class HexView extends BorderPane {

    /** Allowed column widths in the UI dropdown. */
    public static final int[] BYTES_PER_ROW_OPTIONS = {8, 16, 24, 32};

    public enum OffsetBase { HEX, DEC }

    // ---- State ----
    private final SimpleIntegerProperty bytesPerRow = new SimpleIntegerProperty(16);
    private final SimpleObjectProperty<OffsetBase> offsetBase =
            new SimpleObjectProperty<>(OffsetBase.HEX);
    private byte[] bytes = new byte[0];

    // ---- Widgets ----
    private final CodeArea area = new CodeArea();
    private final TextField findField = new TextField();
    private final ChoiceBox<String> findMode = new ChoiceBox<>();
    private final Label findStatus = new Label();
    private final HBox findBar;
    private final Label statusLabel = new Label();
    private final DataInspectorPanel inspectorPanel = new DataInspectorPanel();
    private final StructurePanel structurePanel = new StructurePanel();
    private final HexToolsPanel toolsPanel = new HexToolsPanel();
    /** A small header label the UI may show on the current resource — passed into diff. */
    private String displayName = "resource";
    /** Byte range currently highlighted by a structure-node click (or -1 if none). */
    private int structureHighlightStart = -1;
    private int structureHighlightEnd = -1;

    // ---- Matches for the current find ----
    private final List<int[]> matches = new ArrayList<>();  // each = {byteStart, byteEndExclusive}
    private int currentMatchIdx = -1;

    public HexView() {
        getStyleClass().add("hex-view-root");
        area.setEditable(false);
        area.getStyleClass().add("hex-view");
        area.setStyle("-fx-font-family: 'JetBrains Mono', 'Cascadia Code', 'Consolas', monospace;"
                + "-fx-font-size: 12.5px;"
                + "-fx-font-smoothing-type: lcd;");
        updateParagraphGraphic();

        setTop(buildToolbar());
        // Right sidebar is a TabPane — Data Inspector for type-interpretation, Structure
        // for format-parsed tree (.class / ZIP / PNG). Both panels share the same width.
        javafx.scene.control.TabPane sidebar = new javafx.scene.control.TabPane();
        sidebar.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE);
        javafx.scene.control.Tab inspectorTab = new javafx.scene.control.Tab("Inspector", inspectorPanel);
        inspectorTab.setGraphic(new org.kordamp.ikonli.javafx.FontIcon("mdi2m-magnify-scan"));
        javafx.scene.control.Tab structureTab = new javafx.scene.control.Tab("Structure", structurePanel);
        structureTab.setGraphic(new org.kordamp.ikonli.javafx.FontIcon("mdi2f-file-tree"));
        javafx.scene.control.Tab toolsTab = new javafx.scene.control.Tab("Tools", toolsPanel);
        toolsTab.setGraphic(new org.kordamp.ikonli.javafx.FontIcon("mdi2w-wrench-outline"));
        sidebar.getTabs().addAll(inspectorTab, structureTab, toolsTab);

        // Tools panel needs live access to the current bytes + a handle to open the diff
        // dialog against a user-picked file. We wire both here so HexView stays the single
        // place that knows about the current byte array.
        toolsPanel.setBytesSupplier(() -> this.bytes);
        toolsPanel.setOnDiffRequested(this::openDiffDialog);

        // Clicking a structure node scrolls the hex to the matching range + highlights it.
        structurePanel.setOnSelection(node -> {
            if (node == null) return;
            structureHighlightStart = node.offset();
            structureHighlightEnd = node.offset() + node.length();
            rerender();
            goToByte(node.offset());
        });

        javafx.scene.control.SplitPane centerSplit = new javafx.scene.control.SplitPane();
        centerSplit.getItems().addAll(
                new VirtualizedScrollPane<>(area),
                sidebar);
        centerSplit.setDividerPositions(0.68);
        javafx.scene.control.SplitPane.setResizableWithParent(sidebar, false);
        setCenter(centerSplit);
        findBar = buildFindBar();
        // Find bar collapses when idle; setVisible/setManaged flip in showFindBar / hideFindBar.
        findBar.setVisible(false);
        findBar.setManaged(false);
        VBox bottom = new VBox(findBar, buildStatusBar());
        setBottom(bottom);

        installShortcuts();
        installSelectionTracking();

        // Re-render whenever configuration changes.
        bytesPerRow.addListener((o, a, b) -> rerender());
        offsetBase.addListener((o, a, b) -> updateParagraphGraphic());
    }

    // ========================================================================
    // Public API
    // ========================================================================

    public void setBytes(byte[] bytes) {
        this.bytes = bytes == null ? new byte[0] : bytes;
        matches.clear();
        currentMatchIdx = -1;
        structureHighlightStart = -1;
        structureHighlightEnd = -1;
        rerender();
        updateStatus();
        inspectorPanel.update(this.bytes, this.bytes.length > 0 ? 0 : -1);
        structurePanel.update(this.bytes);
        toolsPanel.onBytesChanged();
    }

    /** Set a display name shown in the diff window header; defaults to "resource". */
    public void setDisplayName(String name) {
        if (name != null && !name.isEmpty()) this.displayName = name;
    }

    /** Prompt the user to pick a second file and pop the side-by-side diff window. */
    private void openDiffDialog() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Pick a file to diff against");
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        java.io.File f = chooser.showOpenDialog(owner);
        if (f == null) return;
        byte[] other;
        try {
            other = java.nio.file.Files.readAllBytes(f.toPath());
        } catch (java.io.IOException ex) {
            toolsPanel.showDiffSummary("Read failed: " + ex.getMessage());
            return;
        }
        boolean dark = getScene() != null && getScene().getRoot() != null
                && getScene().getRoot().getStyleClass().contains("dark-theme");
        HexDiffStage diff = new HexDiffStage(displayName, bytes, f.getName(), other, dark);
        diff.show();
        toolsPanel.showDiffSummary("Opened diff: " + displayName + " vs " + f.getName());
    }

    /** Scroll and move caret to the given byte offset. */
    public void goToByte(int byteOffset) {
        if (byteOffset < 0 || byteOffset >= Math.max(1, bytes.length)) return;
        int textIdx = byteOffsetToTextIndex(byteOffset);
        if (textIdx < 0) return;
        area.moveTo(textIdx);
        area.requestFollowCaret();
    }

    public byte[] currentBytes() { return bytes; }

    public int bytesPerRow() { return bytesPerRow.get(); }

    public void setBytesPerRow(int n) {
        for (int candidate : BYTES_PER_ROW_OPTIONS) {
            if (candidate == n) { bytesPerRow.set(n); return; }
        }
        // Silently ignore unsupported values — keeps callers from poisoning the view.
    }

    public OffsetBase offsetBase() { return offsetBase.get(); }

    public void setOffsetBase(OffsetBase base) {
        if (base != null) offsetBase.set(base);
    }

    // ========================================================================
    // Toolbar: bytes-per-row + offset base + copy menu
    // ========================================================================

    private HBox buildToolbar() {
        Label rowLabel = new Label("Row:");
        rowLabel.getStyleClass().add("hex-toolbar-label");
        ChoiceBox<Integer> rowChoice = new ChoiceBox<>();
        for (int n : BYTES_PER_ROW_OPTIONS) rowChoice.getItems().add(n);
        rowChoice.setValue(16);
        rowChoice.setTooltip(new Tooltip("Bytes per row"));
        rowChoice.valueProperty().addListener((o, a, n) -> { if (n != null) bytesPerRow.set(n); });

        Label baseLabel = new Label("Offset:");
        baseLabel.getStyleClass().add("hex-toolbar-label");
        ChoiceBox<OffsetBase> baseChoice = new ChoiceBox<>();
        baseChoice.getItems().addAll(OffsetBase.HEX, OffsetBase.DEC);
        baseChoice.setValue(OffsetBase.HEX);
        baseChoice.valueProperty().addListener((o, a, n) -> { if (n != null) offsetBase.set(n); });

        Button gotoBtn = new Button("Go to...");
        gotoBtn.setOnAction(e -> showGotoDialog());
        gotoBtn.setTooltip(new Tooltip("Jump to offset (Ctrl+G)"));

        Button findBtn = new Button("Find");
        findBtn.setOnAction(e -> showFindBar());
        findBtn.setTooltip(new Tooltip("Search hex / ASCII (Ctrl+F)"));

        Button copyHexBtn = new Button("Copy hex");
        copyHexBtn.setOnAction(e -> copySelection(CopyFormat.HEX));
        Button copyCBtn = new Button("Copy as C");
        copyCBtn.setOnAction(e -> copySelection(CopyFormat.C_ARRAY));
        Button copyB64Btn = new Button("Copy base64");
        copyB64Btn.setOnAction(e -> copySelection(CopyFormat.BASE64));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(6,
                rowLabel, rowChoice, baseLabel, baseChoice,
                new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL),
                gotoBtn, findBtn,
                new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL),
                copyHexBtn, copyCBtn, copyB64Btn,
                spacer);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.getStyleClass().add("hex-toolbar");
        return bar;
    }

    // ========================================================================
    // Find bar
    // ========================================================================

    private HBox buildFindBar() {
        findField.setPromptText("Find: type ASCII or hex bytes (DE AD BE EF) — Enter = next, Esc = close");
        HBox.setHgrow(findField, Priority.ALWAYS);
        findField.setOnAction(e -> runFind(true));

        findMode.getItems().addAll("Auto", "Hex", "ASCII", "UTF-16");
        findMode.setValue("Auto");
        findMode.setTooltip(new Tooltip(
                "Auto = detect hex if input looks like hex bytes, else ASCII.\n"
                        + "Hex = strict hex parse (spaces optional). ASCII = literal text."));

        Button prev = new Button("\u25b2");
        prev.setTooltip(new Tooltip("Previous match (Shift+F3)"));
        prev.setOnAction(e -> runFind(false));

        Button next = new Button("\u25bc");
        next.setTooltip(new Tooltip("Next match (F3)"));
        next.setOnAction(e -> runFind(true));

        Button close = new Button("\u00d7");
        close.setTooltip(new Tooltip("Close (Esc)"));
        close.setOnAction(e -> hideFindBar());

        findStatus.getStyleClass().add("hex-find-status");

        HBox bar = new HBox(6, findField, findMode, prev, next, findStatus, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.getStyleClass().add("hex-find-bar");
        return bar;
    }

    private HBox buildStatusBar() {
        statusLabel.getStyleClass().add("hex-status-label");
        HBox row = new HBox(statusLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 8, 2, 8));
        row.getStyleClass().add("hex-status-bar");
        return row;
    }

    private void showFindBar() {
        findBar.setVisible(true);
        findBar.setManaged(true);
        findField.requestFocus();
        findField.selectAll();
    }

    private void hideFindBar() {
        findBar.setVisible(false);
        findBar.setManaged(false);
        // Clear visual highlights but keep match list so F3 from outside the bar resumes.
        rerender();
        area.requestFocus();
    }

    /**
     * Parse the user's find input and scan for matches. Advance to next/prev match
     * depending on {@code forward}. Highlights every match + focuses the current one.
     */
    private void runFind(boolean forward) {
        String query = findField.getText();
        if (query == null || query.isEmpty()) {
            matches.clear();
            currentMatchIdx = -1;
            findStatus.setText("");
            rerender();
            return;
        }
        byte[] needle = parseNeedle(query, findMode.getValue());
        if (needle == null || needle.length == 0) {
            findStatus.setText("bad pattern");
            matches.clear();
            currentMatchIdx = -1;
            rerender();
            return;
        }
        matches.clear();
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) { ok = false; break; }
            }
            if (ok) matches.add(new int[]{i, i + needle.length});
            if (matches.size() >= 10_000) break;  // UX sanity cap
        }
        if (matches.isEmpty()) {
            currentMatchIdx = -1;
            findStatus.setText("0 matches");
            rerender();
            return;
        }
        // Step to next/prev from current index; wrap around.
        if (currentMatchIdx < 0) {
            currentMatchIdx = forward ? 0 : matches.size() - 1;
        } else {
            currentMatchIdx = forward
                    ? (currentMatchIdx + 1) % matches.size()
                    : (currentMatchIdx - 1 + matches.size()) % matches.size();
        }
        findStatus.setText((currentMatchIdx + 1) + " / " + matches.size());
        rerender();
        goToByte(matches.get(currentMatchIdx)[0]);
    }

    /**
     * Parse needle respecting the mode. Auto mode checks if the string looks like hex
     * ({@code [0-9a-f\s]+} with even digit count after whitespace strip) and treats it
     * accordingly; otherwise falls back to ASCII.
     */
    static byte[] parseNeedle(String input, String mode) {
        if (input == null || input.isEmpty()) return null;
        String m = mode == null ? "Auto" : mode;
        if ("ASCII".equals(m)) return input.getBytes(StandardCharsets.US_ASCII);
        if ("UTF-16".equals(m)) return input.getBytes(StandardCharsets.UTF_16LE);
        if ("Hex".equals(m)) return tryParseHex(input);
        // Auto
        byte[] hex = tryParseHex(input);
        if (hex != null) return hex;
        return input.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] tryParseHex(String input) {
        String cleaned = input.replaceAll("[\\s,]+", "").replace("0x", "").replace("0X", "");
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() % 2 != 0) return null;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = Character.toLowerCase(cleaned.charAt(i));
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return null;
        }
        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(cleaned.charAt(i * 2), 16);
            int lo = Character.digit(cleaned.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    // ========================================================================
    // Goto offset
    // ========================================================================

    private void showGotoDialog() {
        javafx.scene.control.TextInputDialog dlg = new javafx.scene.control.TextInputDialog();
        dlg.setTitle("Go to Offset");
        dlg.setHeaderText("Enter offset (0x prefix for hex, negative for from end)");
        dlg.setContentText("Offset:");
        dlg.showAndWait().ifPresent(s -> {
            Integer off = parseOffset(s, bytes.length);
            if (off != null) goToByte(off);
        });
    }

    /**
     * Parse {@code 0xDEADBEEF}, {@code 1024}, {@code -16} (last 16 bytes). Returns
     * absolute offset in [0, total) or null if the string is unparseable.
     */
    static Integer parseOffset(String s, int total) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            long v;
            if (t.startsWith("0x") || t.startsWith("0X")) {
                v = Long.parseLong(t.substring(2), 16);
            } else if (t.startsWith("-0x") || t.startsWith("-0X")) {
                v = -Long.parseLong(t.substring(3), 16);
            } else {
                v = Long.parseLong(t);
            }
            if (v < 0) v = total + v;  // -16 == total-16
            if (v < 0 || v >= total) return null;
            return (int) v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ========================================================================
    // Selection / copy
    // ========================================================================

    public enum CopyFormat { HEX, C_ARRAY, BASE64, ASCII }

    private void copySelection(CopyFormat fmt) {
        int[] range = selectedByteRange();
        if (range == null) {
            // Fall back to copying the whole file when nothing is selected.
            range = new int[]{0, bytes.length};
        }
        byte[] slice = Arrays.copyOfRange(bytes, range[0], range[1]);
        String payload = switch (fmt) {
            case HEX -> formatHex(slice);
            case C_ARRAY -> formatC(slice);
            case BASE64 -> java.util.Base64.getEncoder().encodeToString(slice);
            case ASCII -> new String(slice, StandardCharsets.US_ASCII);
        };
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, payload));
        statusLabel.setText("Copied " + slice.length + " bytes as " + fmt.name());
    }

    static String formatHex(byte[] slice) {
        StringBuilder sb = new StringBuilder(slice.length * 3);
        for (int i = 0; i < slice.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", slice[i] & 0xff));
        }
        return sb.toString();
    }

    static String formatC(byte[] slice) {
        StringBuilder sb = new StringBuilder(slice.length * 6);
        sb.append("{");
        for (int i = 0; i < slice.length; i++) {
            if (i > 0) sb.append(i % 16 == 0 ? ",\n  " : ", ");
            else sb.append("\n  ");
            sb.append(String.format("0x%02X", slice[i] & 0xff));
        }
        sb.append("\n}");
        return sb.toString();
    }

    /**
     * Compute the byte range covered by the current CodeArea text selection. Maps text
     * positions back to byte offsets, handling both the hex and ASCII columns.
     * Returns null if nothing is selected or selection is empty.
     */
    int[] selectedByteRange() {
        var sel = area.getSelection();
        if (sel == null || sel.getLength() == 0) return null;
        int anchor = sel.getStart();
        int caret = sel.getEnd();
        int aByte = textIndexToByteOffset(anchor);
        int cByte = textIndexToByteOffset(caret);
        if (aByte < 0 || cByte < 0) return null;
        int lo = Math.min(aByte, cByte);
        int hi = Math.max(aByte, cByte);
        // If caret lands past the last byte of the selection (exclusive boundary), keep it;
        // else bump by 1 so the range is inclusive of the highlighted byte.
        if (hi < bytes.length) hi = Math.min(bytes.length, hi + 1);
        return new int[]{lo, hi};
    }

    // ========================================================================
    // Key shortcuts
    // ========================================================================

    private void installShortcuts() {
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN).match(e)) {
                showFindBar();
                e.consume();
            } else if (new KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN).match(e)) {
                showGotoDialog();
                e.consume();
            } else if (e.getCode() == KeyCode.F3) {
                runFind(!e.isShiftDown());
                e.consume();
            }
        });
        findField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hideFindBar();
                e.consume();
            }
        });
    }

    private void installSelectionTracking() {
        area.caretPositionProperty().addListener((o, a, b) -> updateStatus());
        area.selectionProperty().addListener((o, a, b) -> updateStatus());
    }

    private void updateStatus() {
        if (bytes.length == 0) {
            statusLabel.setText("");
            inspectorPanel.update(null, -1);
            return;
        }
        int caretOffset = Math.max(0, textIndexToByteOffset(area.getCaretPosition()));
        StringBuilder sb = new StringBuilder();
        sb.append("Size: ").append(bytes.length).append(" bytes");
        sb.append("   |   Cursor: ").append(formatOffset(caretOffset));
        int[] sel = selectedByteRange();
        if (sel != null) {
            sb.append("   |   Selected: ").append(sel[1] - sel[0]).append(" bytes ")
                    .append("[").append(formatOffset(sel[0])).append(" \u2013 ")
                    .append(formatOffset(sel[1] - 1)).append("]");
        }
        statusLabel.setText(sb.toString());
        // Data Inspector always mirrors the caret position — even if there's a selection.
        // The selection drives status bar math; inspector stays focused on the cursor byte.
        inspectorPanel.update(bytes, caretOffset);
    }

    private String formatOffset(int off) {
        return offsetBase.get() == OffsetBase.HEX
                ? "0x" + Integer.toHexString(off).toUpperCase()
                : Integer.toString(off);
    }

    private void updateParagraphGraphic() {
        int bpr = bytesPerRow.get();
        area.setParagraphGraphicFactory(LineNumberFactory.get(area, i ->
                offsetBase.get() == OffsetBase.HEX
                        ? String.format("%08x", i * bpr)
                        : String.format("%8d", i * bpr)));
    }

    // ========================================================================
    // Rendering (CodeArea-based, same shape as before but parameterised)
    // ========================================================================

    private void rerender() {
        int bpr = bytesPerRow.get();
        if (bytes.length == 0) {
            area.replaceText("");
            return;
        }
        StringBuilder text = new StringBuilder(bytes.length * 5);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();

        // Precompute which byte offsets are part of the current match set so we can
        // style them without repeating the scan per row.
        java.util.BitSet matchMask = new java.util.BitSet(bytes.length);
        int currentMatchStart = -1;
        int currentMatchEnd = -1;
        for (int i = 0; i < matches.size(); i++) {
            int[] m = matches.get(i);
            for (int b = m[0]; b < m[1]; b++) matchMask.set(b);
            if (i == currentMatchIdx) {
                currentMatchStart = m[0];
                currentMatchEnd = m[1];
            }
        }

        for (int rowStart = 0; rowStart < bytes.length; rowStart += bpr) {
            int rowEnd = Math.min(rowStart + bpr, bytes.length);
            int rowLen = rowEnd - rowStart;

            // hex bytes column
            for (int i = rowStart; i < rowEnd; i++) {
                int b = bytes[i] & 0xff;
                text.append(String.format("%02x", b));
                String cls = hexStyle(b);
                if (i >= currentMatchStart && i < currentMatchEnd) {
                    spans.add(java.util.Arrays.asList(cls, "hex-match-current"), 2);
                } else if (matchMask.get(i)) {
                    spans.add(java.util.Arrays.asList(cls, "hex-match"), 2);
                } else if (i >= structureHighlightStart && i < structureHighlightEnd) {
                    spans.add(java.util.Arrays.asList(cls, "hex-struct"), 2);
                } else {
                    spans.add(Collections.singleton(cls), 2);
                }
                boolean last = (i == rowEnd - 1);
                if (!last) {
                    text.append(' ');
                    spans.add(Collections.emptyList(), 1);
                }
            }
            int missing = bpr - rowLen;
            if (missing > 0) {
                int pad = missing * 3;
                for (int p = 0; p < pad; p++) text.append(' ');
                spans.add(Collections.emptyList(), pad);
            }

            text.append("  ");
            spans.add(Collections.emptyList(), 2);

            text.append('|');
            spans.add(Collections.singleton("hex-sep"), 1);
            for (int i = rowStart; i < rowEnd; i++) {
                int b = bytes[i] & 0xff;
                char c = (b >= 0x20 && b < 0x7f) ? (char) b : '.';
                text.append(c);
                String cls = asciiStyle(b);
                if (i >= currentMatchStart && i < currentMatchEnd) {
                    spans.add(java.util.Arrays.asList(cls, "hex-match-current"), 1);
                } else if (matchMask.get(i)) {
                    spans.add(java.util.Arrays.asList(cls, "hex-match"), 1);
                } else if (i >= structureHighlightStart && i < structureHighlightEnd) {
                    spans.add(java.util.Arrays.asList(cls, "hex-struct"), 1);
                } else {
                    spans.add(Collections.singleton(cls), 1);
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
        } catch (Exception ignored) {
        }
        area.moveTo(0);
        area.scrollToPixel(0, 0);
        updateParagraphGraphic();
    }

    // ========================================================================
    // Text-index ↔ byte-offset conversion
    //
    // Row format (per bpr=16, for example):
    //   hex column = 16 * 2 + 15 spaces        = 47 chars
    //   separator  = "  "                      = 2 chars
    //   ascii col  = "|" + 16 chars + "|"      = 18 chars
    //   newline                                = 1 char (not for last row)
    //
    // Total per full row = 47 + 2 + 18 + 1 = 68
    //
    // For arbitrary bpr N:
    //   hex column = N * 2 + (N - 1)            = 3N - 1
    //   separator  = 2
    //   ascii col  = N + 2
    //   newline    = 1 (absent on last row, but we account for it)
    // ========================================================================

    private int rowStride() {
        int bpr = bytesPerRow.get();
        return (3 * bpr - 1) + 2 + (bpr + 2) + 1;
    }

    /** Return the text column indices for hex (start, end-exclusive) and ASCII areas on a row. */
    int byteOffsetToTextIndex(int byteOffset) {
        if (bytes.length == 0) return -1;
        int bpr = bytesPerRow.get();
        int row = byteOffset / bpr;
        int col = byteOffset % bpr;
        int rowBase = row * rowStride();
        // Point at the first hex digit of the byte.
        return rowBase + col * 3;
    }

    /** Map a text-area cursor position back to a byte offset (prefer hex column). */
    int textIndexToByteOffset(int textIdx) {
        if (bytes.length == 0) return -1;
        int bpr = bytesPerRow.get();
        int stride = rowStride();
        int row = textIdx / stride;
        int colInRow = textIdx % stride;
        int hexWidth = 3 * bpr - 1;     // columns covered by hex bytes + spaces
        int gap = 2;
        int asciiStart = hexWidth + gap + 1;  // +1 for '|'
        int byteInRow;
        if (colInRow < hexWidth) {
            // We're inside the hex column. Each byte takes 3 columns (2 hex + 1 space) except last.
            byteInRow = colInRow / 3;
        } else if (colInRow >= asciiStart && colInRow < asciiStart + bpr) {
            byteInRow = colInRow - asciiStart;
        } else {
            // Separators, pipes, padding — approximate to end of row.
            byteInRow = bpr - 1;
        }
        int off = row * bpr + byteInRow;
        if (off >= bytes.length) off = bytes.length - 1;
        if (off < 0) off = 0;
        return off;
    }

    // ========================================================================
    // Styling helpers
    // ========================================================================

    private static String hexStyle(int b) {
        if (b == 0) return "hex-zero";
        if (b >= 0x20 && b < 0x7f) return "hex-printable";
        if (b == 0xff) return "hex-ff";
        return "hex-other";
    }

    private static String asciiStyle(int b) {
        if (b == 0) return "hex-ascii-zero";
        if (b >= 0x20 && b < 0x7f) return "hex-ascii-printable";
        return "hex-ascii-other";
    }
}
