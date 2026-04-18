package dev.share.bytecodelens.ui.views;

import dev.share.bytecodelens.service.PinnedFiles;
import dev.share.bytecodelens.service.RecentFiles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * First-launch screen shown when no jar is loaded. Two sections:
 * <ul>
 *     <li>Pinned projects — user-curated list, persists across sessions.</li>
 *     <li>Recent jars — auto-maintained, capped at 8.</li>
 * </ul>
 * A fuzzy-search box filters both lists simultaneously. Clicking an entry calls
 * {@link #setOnOpen}; right-click toggles pin/unpin.
 */
public final class StartPage extends VBox {

    public interface OpenHandler {
        void open(Path path);
    }

    private final RecentFiles recentFiles;
    private final PinnedFiles pinnedFiles;
    private final TextField search = new TextField();
    private final ListView<Entry> pinnedList = new ListView<>();
    private final ListView<Entry> recentList = new ListView<>();
    private OpenHandler onOpen;
    private Runnable onBrowseButton;

    public StartPage(RecentFiles recentFiles, PinnedFiles pinnedFiles) {
        this.recentFiles = recentFiles;
        this.pinnedFiles = pinnedFiles;

        getStyleClass().add("start-page");
        // Smaller padding — the host editor area can be narrow on 1280px displays when the
        // tree + bottom panels are generous. Top-left alignment guarantees the title isn't
        // centered into clipping on narrow widths.
        setPadding(new Insets(20, 24, 20, 24));
        setSpacing(14);
        setAlignment(Pos.TOP_LEFT);
        setFillWidth(true);
        // Claim all available space from the host StackPane so the ListViews have room.
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);

        getChildren().addAll(buildTitle(), buildSearchBox(), buildPinnedSection(), buildRecentSection());
        VBox.setVgrow(recentList, Priority.ALWAYS);
        refresh();
    }

    public void setOnOpenHandler(OpenHandler handler) { this.onOpen = handler; }

    public void setOnBrowse(Runnable handler) { this.onBrowseButton = handler; }

    /** Reload recent/pinned lists — called after the user opens a new jar. */
    public void refresh() {
        List<Entry> pinnedEntries = new ArrayList<>();
        for (Path p : pinnedFiles.load()) pinnedEntries.add(new Entry(p, true));
        List<Entry> recentEntries = new ArrayList<>();
        // Avoid duplicates: a path that's pinned shouldn't also show in Recent.
        var pinnedSet = new java.util.HashSet<String>();
        for (Entry e : pinnedEntries) pinnedSet.add(e.path().toAbsolutePath().toString());
        for (Path p : recentFiles.load()) {
            if (!pinnedSet.contains(p.toAbsolutePath().toString())) {
                recentEntries.add(new Entry(p, false));
            }
        }
        applyFilter(pinnedEntries, recentEntries, search.getText());
    }

    private HBox buildTitle() {
        // Prefer the bundled CAFEBABE logo over a generic Material icon — it's the same
        // artwork used for the window icon, so the start page feels like "the same product".
        javafx.scene.Node logo;
        var logoStream = getClass().getResourceAsStream("/icons/app-512.png");
        if (logoStream != null) {
            javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(
                    new javafx.scene.image.Image(logoStream, 64, 64, true, true));
            iv.setSmooth(true);
            iv.getStyleClass().add("start-page-logo-image");
            logo = iv;
        } else {
            FontIcon fallback = new FontIcon("mdi2c-code-braces");
            fallback.setIconSize(44);
            fallback.getStyleClass().add("start-page-logo");
            logo = fallback;
        }
        Label title = new Label("BytecodeLens");
        title.getStyleClass().add("start-page-title");
        Label subtitle = new Label("The Java RE cockpit \u2014 decompile, attach, diff, patch.");
        subtitle.getStyleClass().add("start-page-subtitle");
        Label hint = new Label("Open a .jar, .war, .class, .zip or .jmod to begin.");
        hint.getStyleClass().add("start-page-subtitle");
        VBox text = new VBox(2, title, subtitle, hint);
        HBox row = new HBox(18, logo, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 0, 6, 0));
        return row;
    }

    private HBox buildSearchBox() {
        search.setPromptText("Fuzzy search recent + pinned (Ctrl+F)");
        search.getStyleClass().add("start-page-search");
        search.textProperty().addListener((obs, o, n) -> refresh());

        javafx.scene.control.Button browse = new javafx.scene.control.Button("Open File...");
        browse.setGraphic(new FontIcon("mdi2f-folder-open-outline"));
        browse.getStyleClass().add("start-page-browse");
        browse.setOnAction(e -> { if (onBrowseButton != null) onBrowseButton.run(); });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, search, spacer, browse);
        HBox.setHgrow(search, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox buildPinnedSection() {
        Label header = sectionHeader("Pinned", "mdi2p-pin-outline");
        pinnedList.getStyleClass().add("start-page-list");
        pinnedList.setCellFactory(v -> new EntryCell());
        pinnedList.setPrefHeight(160);
        pinnedList.setPlaceholder(new Label("No pinned projects yet — right-click a recent entry to pin it."));
        pinnedList.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) openSelected(pinnedList);
        });
        return new VBox(6, header, pinnedList);
    }

    private VBox buildRecentSection() {
        Label header = sectionHeader("Recent", "mdi2h-history");
        recentList.getStyleClass().add("start-page-list");
        recentList.setCellFactory(v -> new EntryCell());
        recentList.setPlaceholder(new Label("No recent jars yet."));
        recentList.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) openSelected(recentList);
        });
        VBox box = new VBox(6, header, recentList);
        VBox.setVgrow(recentList, Priority.ALWAYS);
        return box;
    }

    private Label sectionHeader(String text, String iconLiteral) {
        Label lbl = new Label(text);
        FontIcon icon = new FontIcon(iconLiteral);
        lbl.setGraphic(icon);
        lbl.getStyleClass().add("start-page-section-header");
        return lbl;
    }

    private void openSelected(ListView<Entry> list) {
        Entry e = list.getSelectionModel().getSelectedItem();
        if (e == null || onOpen == null) return;
        onOpen.open(e.path());
    }

    /**
     * Fuzzy match: the query matches if every character of the needle appears in the
     * haystack in order. Identical to VS Code / IntelliJ Ctrl+P semantics. Rejected
     * entries get nothing; accepted entries remember their match score (# of gaps)
     * for sorting.
     */
    static int fuzzyScore(String haystack, String needle) {
        if (needle == null || needle.isBlank()) return 0;
        String h = haystack.toLowerCase();
        String n = needle.toLowerCase();
        int hi = 0, ni = 0;
        int gaps = 0;
        int lastMatch = -1;
        while (hi < h.length() && ni < n.length()) {
            if (h.charAt(hi) == n.charAt(ni)) {
                if (lastMatch >= 0 && hi - lastMatch > 1) gaps += hi - lastMatch;
                lastMatch = hi;
                ni++;
            }
            hi++;
        }
        return ni == n.length() ? gaps + 1 : -1;  // +1 so "exact" (gaps=0) scores 1 (truthy)
    }

    private void applyFilter(List<Entry> pinned, List<Entry> recent, String needle) {
        if (needle == null) needle = "";
        String q = needle.trim();
        if (q.isEmpty()) {
            pinnedList.getItems().setAll(pinned);
            recentList.getItems().setAll(recent);
            return;
        }
        pinnedList.getItems().setAll(scoreAndSort(pinned, q));
        recentList.getItems().setAll(scoreAndSort(recent, q));
    }

    private static List<Entry> scoreAndSort(List<Entry> items, String needle) {
        Map<Entry, Integer> scores = new LinkedHashMap<>();
        for (Entry e : items) {
            int score = fuzzyScore(e.path().getFileName().toString(), needle);
            if (score < 0) {
                // Also try matching full path, not just filename.
                score = fuzzyScore(e.path().toString(), needle);
            }
            if (score >= 0) scores.put(e, score);
        }
        List<Entry> out = new ArrayList<>(scores.keySet());
        out.sort((a, b) -> Integer.compare(scores.get(a), scores.get(b)));
        return out;
    }

    /** Entry record stored in the ListViews. */
    record Entry(Path path, boolean pinned) {}

    /**
     * Two-line cell: filename on top, parent path greyed out below. Right-click
     * toggles pin status via {@link #pinnedFiles}.
     */
    private final class EntryCell extends ListCell<Entry> {
        @Override
        protected void updateItem(Entry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            Path p = item.path();
            Label name = new Label(p.getFileName().toString());
            name.getStyleClass().add("start-page-entry-name");
            Label dir = new Label(p.getParent() == null ? "" : p.getParent().toString());
            dir.getStyleClass().add("start-page-entry-dir");
            Label size = new Label(formatSize(p));
            size.getStyleClass().add("start-page-entry-size");

            VBox text = new VBox(1, name, dir);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(8, text, spacer, size);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);

            javafx.scene.control.ContextMenu ctx = new javafx.scene.control.ContextMenu();
            javafx.scene.control.MenuItem open = new javafx.scene.control.MenuItem("Open");
            open.setOnAction(e -> {
                if (onOpen != null) onOpen.open(p);
            });
            javafx.scene.control.MenuItem pinToggle = new javafx.scene.control.MenuItem(
                    item.pinned() ? "Unpin" : "Pin to top");
            pinToggle.setOnAction(e -> {
                if (item.pinned()) pinnedFiles.unpin(p);
                else pinnedFiles.pin(p);
                refresh();
            });
            javafx.scene.control.MenuItem copy = new javafx.scene.control.MenuItem("Copy path");
            copy.setOnAction(e -> javafx.scene.input.Clipboard.getSystemClipboard()
                    .setContent(Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, p.toString())));
            ctx.getItems().addAll(open, pinToggle, new javafx.scene.control.SeparatorMenuItem(), copy);
            setContextMenu(ctx);
        }
    }

    private static String formatSize(Path p) {
        try {
            long bytes = Files.size(p);
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } catch (Exception ex) {
            return "";
        }
    }

    /** Consumer-based convenience for callers that don't want to implement OpenHandler. */
    public void setOnOpen(Consumer<Path> handler) {
        this.onOpen = handler == null ? null : handler::accept;
    }
}
