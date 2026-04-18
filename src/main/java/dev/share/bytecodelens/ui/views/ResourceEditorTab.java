package dev.share.bytecodelens.ui.views;

import dev.share.bytecodelens.model.JarResource;
import dev.share.bytecodelens.ui.highlight.JavaHighlighter;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ResourceEditorTab {

    private final Tab tab;
    private final TabPane views;
    private CodeView textView;
    private Tab textTab;
    private HexView hexView;
    private Tab hexTab;

    public ResourceEditorTab(JarResource resource, byte[] bytes) {
        this.tab = new Tab(resource.simpleName());
        tab.setTooltip(new Tooltip(resource.path()));
        tab.setGraphic(iconFor(resource.kind()));

        views = new TabPane();
        views.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        views.getStyleClass().add("editor-views");

        Kind showMode = modeFor(resource.kind());

        if (showMode == Kind.TEXT_HEX) {
            buildTextTab(bytes);
            buildHexTab(bytes);
            views.getTabs().addAll(textTab, hexTab);
        } else if (showMode == Kind.IMAGE_HEX) {
            Tab imageTab = buildImageTab(bytes);
            if (imageTab != null) views.getTabs().add(imageTab);
            buildHexTab(bytes);
            views.getTabs().add(hexTab);
        } else if (showMode == Kind.NATIVE) {
            // Parse first so we can show the metadata as the first tab the user sees —
            // even when parsing fails, we render the "unknown" page rather than a bare
            // hex dump, so the UI always has a readable summary on top.
            Tab infoTab = buildNativeInfoTab(bytes);
            views.getTabs().add(infoTab);
            buildHexTab(bytes);
            views.getTabs().add(hexTab);
        } else {
            buildHexTab(bytes);
            views.getTabs().add(hexTab);
            if (probablyText(bytes)) {
                buildTextTab(bytes);
                views.getTabs().add(textTab);
            }
        }

        BorderPane root = new BorderPane(views);
        tab.setContent(root);
    }

    public Tab tab() {
        return tab;
    }

    public void clearHighlights() {
        if (textView != null) textView.clearHighlight();
    }

    public void applyHighlight(HighlightRequest request) {
        if (request == null) return;
        if (textView != null && textTab != null) {
            views.getSelectionModel().select(textTab);
            textView.applyHighlight(request);
            if (request.line() > 0) {
                textView.goToLine(request.line());
            } else {
                textView.goToFirstMatch();
            }
        } else if (hexView != null && hexTab != null && request.line() > 0) {
            views.getSelectionModel().select(hexTab);
            hexView.goToByte((request.line() - 1) * 16);
        }
    }

    private enum Kind { TEXT_HEX, IMAGE_HEX, HEX_ONLY, NATIVE }

    private static Kind modeFor(JarResource.ResourceKind kind) {
        if (kind == null) return Kind.HEX_ONLY;
        return switch (kind) {
            case MANIFEST, SERVICE, PROPERTIES, XML, JSON, YAML, TEXT, SQL, SCRIPT, MODULE_INFO -> Kind.TEXT_HEX;
            case IMAGE -> Kind.IMAGE_HEX;
            case NATIVE_DLL, NATIVE_SO, NATIVE_DYLIB -> Kind.NATIVE;
            case NESTED_JAR, NESTED_WAR, NESTED_ZIP, FONT, BINARY -> Kind.HEX_ONLY;
            default -> Kind.TEXT_HEX;
        };
    }

    private void buildTextTab(byte[] bytes) {
        textView = new CodeView(JavaHighlighter::compute);
        String text = new String(bytes, StandardCharsets.UTF_8);
        textView.setText(text);
        FindBar find = new FindBar(textView);
        VBox pane = new VBox(find, textView);
        VBox.setVgrow(textView, Priority.ALWAYS);
        textView.area().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F && e.isShortcutDown()) {
                find.show(textView.area().getSelectedText());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE && textView.matchCount() > 0) {
                textView.clearHighlight();
                e.consume();
            }
        });
        textTab = new Tab("Text", pane);
        textTab.setGraphic(icon("mdi2f-file-document-outline"));
        textTab.setClosable(false);
    }

    private void buildHexTab(byte[] bytes) {
        hexView = new HexView();
        hexView.setBytes(bytes);
        hexTab = new Tab("Hex", hexView);
        hexTab.setGraphic(icon("mdi2p-pound"));
        hexTab.setClosable(false);
    }

    private Tab buildImageTab(byte[] bytes) {
        try {
            Image img = new Image(new ByteArrayInputStream(bytes));
            if (img.isError() || img.getWidth() <= 0) return null;
            ImageView view = new ImageView(img);
            view.setPreserveRatio(true);
            view.setSmooth(true);

            StackPane pane = new StackPane(view);
            pane.getStyleClass().add("image-preview");
            pane.setAlignment(Pos.CENTER);

            view.fitWidthProperty().bind(pane.widthProperty().subtract(40));
            view.fitHeightProperty().bind(pane.heightProperty().subtract(40));

            ScrollPane scroll = new ScrollPane(pane);
            scroll.setFitToWidth(true);
            scroll.setFitToHeight(true);
            scroll.getStyleClass().add("image-scroll");

            Label info = new Label(String.format("%d x %d  -  %d bytes",
                    (int) img.getWidth(), (int) img.getHeight(), bytes.length));
            info.getStyleClass().add("image-info");

            BorderPane root = new BorderPane(scroll);
            root.setBottom(info);
            BorderPane.setAlignment(info, Pos.CENTER);

            Tab t = new Tab("Image", root);
            t.setGraphic(icon("mdi2i-image-outline"));
            t.setClosable(false);
            return t;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Render a summary panel for a native library: format/arch/bitness/endianness,
     * exported symbols (searchable), and JNI-matched natives highlighted. Parsing
     * failures still produce a readable "unknown format" tab, not a crash.
     */
    private Tab buildNativeInfoTab(byte[] bytes) {
        dev.share.bytecodelens.nativelibs.NativeLibInfo info =
                new dev.share.bytecodelens.nativelibs.NativeLibParser().parse(bytes);

        VBox header = new VBox(4);
        header.getStyleClass().add("native-info-header");
        header.setPadding(new javafx.geometry.Insets(10, 12, 10, 12));
        header.getChildren().add(kvRow("Format", info.format().name()));
        header.getChildren().add(kvRow("Architecture", info.architecture()));
        header.getChildren().add(kvRow("Bitness", info.bitness() == 0 ? "?" : info.bitness() + "-bit"));
        header.getChildren().add(kvRow("Endianness", info.endianness()));
        header.getChildren().add(kvRow("OS / ABI", info.osAbi()));
        header.getChildren().add(kvRow("Size", bytes.length + " bytes"));
        header.getChildren().add(kvRow("Exports", info.symbols().size() + " symbol"
                + (info.symbols().size() == 1 ? "" : "s")));

        if (!info.diagnostics().isEmpty()) {
            Label diag = new Label(String.join("\n", info.diagnostics()));
            diag.getStyleClass().add("native-info-diagnostic");
            diag.setWrapText(true);
            header.getChildren().add(diag);
        }

        // Filter + JNI-highlight list. We render JNI-parseable names bold so users can
        // see at a glance which natives belong to which Java class — that's the main
        // thing you want from an RE viewpoint.
        javafx.scene.control.TextField filter = new javafx.scene.control.TextField();
        filter.setPromptText("Filter symbols...");
        javafx.scene.control.ListView<SymbolRow> list = new javafx.scene.control.ListView<>();
        list.getStyleClass().add("native-symbol-list");
        list.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(SymbolRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                if (item.jniParsed() != null) {
                    // Two-line rendering: mangled on top, decoded class::method below.
                    Label top = new Label(item.name());
                    top.getStyleClass().add("native-symbol-jni");
                    Label bottom = new Label("  \u2192 " + item.jniParsed().classFqn()
                            + "." + item.jniParsed().methodName()
                            + (item.jniParsed().methodDescriptor() != null
                                    ? " " + item.jniParsed().methodDescriptor() : ""));
                    bottom.getStyleClass().add("native-symbol-jni-decoded");
                    VBox cell = new VBox(top, bottom);
                    setGraphic(cell);
                    setText(null);
                } else {
                    setText(item.name());
                    setGraphic(null);
                }
            }
        });

        // Parse JNI pieces once for every symbol.
        List<SymbolRow> all = new ArrayList<>(info.symbols().size());
        for (String s : info.symbols()) {
            var parsed = dev.share.bytecodelens.nativelibs.JniSignatureMatcher.parse(s);
            all.add(new SymbolRow(s, parsed));
        }
        // Sort JNI symbols to top; within each group, alphabetical.
        all.sort((a, b) -> {
            if ((a.jniParsed() != null) != (b.jniParsed() != null)) {
                return a.jniParsed() != null ? -1 : 1;
            }
            return a.name().compareTo(b.name());
        });
        javafx.collections.ObservableList<SymbolRow> backing = javafx.collections.FXCollections.observableArrayList(all);
        javafx.collections.transformation.FilteredList<SymbolRow> filtered =
                new javafx.collections.transformation.FilteredList<>(backing);
        list.setItems(filtered);
        filter.textProperty().addListener((obs, o, n) -> {
            String needle = n == null ? "" : n.trim().toLowerCase();
            if (needle.isEmpty()) {
                filtered.setPredicate(x -> true);
            } else {
                filtered.setPredicate(r -> r.name().toLowerCase().contains(needle)
                        || (r.jniParsed() != null
                            && (r.jniParsed().classFqn().toLowerCase().contains(needle)
                             || r.jniParsed().methodName().toLowerCase().contains(needle))));
            }
        });

        VBox body = new VBox(6);
        body.setPadding(new javafx.geometry.Insets(6, 12, 8, 12));
        Label jniHint = new Label("JNI natives (bold) resolve to decoded Java method");
        jniHint.getStyleClass().add("native-info-section-hint");
        body.getChildren().addAll(filter, jniHint, list);
        VBox.setVgrow(list, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(body);
        Tab t = new Tab("Info", root);
        t.setGraphic(icon("mdi2i-information-outline"));
        t.setClosable(false);
        return t;
    }

    private record SymbolRow(String name,
                             dev.share.bytecodelens.nativelibs.JniSignatureMatcher.Parsed jniParsed) {}

    private static javafx.scene.layout.HBox kvRow(String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("native-info-key");
        Label v = new Label(value);
        v.getStyleClass().add("native-info-value");
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(10, k, v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static boolean probablyText(byte[] bytes) {
        if (bytes.length == 0) return false;
        int sample = Math.min(bytes.length, 512);
        int printable = 0;
        for (int i = 0; i < sample; i++) {
            int b = bytes[i] & 0xff;
            if (b == 0) return false;
            if (b >= 0x20 && b < 0x7f) printable++;
            else if (b == '\r' || b == '\n' || b == '\t') printable++;
        }
        return (double) printable / sample > 0.85;
    }

    private static FontIcon icon(String literal) {
        FontIcon i = new FontIcon(literal);
        i.setIconSize(13);
        return i;
    }

    private static FontIcon iconFor(JarResource.ResourceKind kind) {
        String literal = kind == null ? "mdi2f-file-outline" : switch (kind) {
            case NATIVE_DLL -> "mdi2m-microsoft-windows";
            case NATIVE_SO -> "mdi2l-linux";
            case NATIVE_DYLIB -> "mdi2a-apple";
            case NESTED_JAR -> "mdi2a-archive-outline";
            case NESTED_WAR -> "mdi2w-web";
            case NESTED_ZIP -> "mdi2z-zip-box-outline";
            case MANIFEST -> "mdi2f-file-document-outline";
            case SERVICE -> "mdi2c-cog-outline";
            case PROPERTIES -> "mdi2f-file-cog-outline";
            case XML -> "mdi2x-xml";
            case JSON -> "mdi2c-code-json";
            case YAML -> "mdi2f-file-code-outline";
            case TEXT -> "mdi2f-file-document-outline";
            case IMAGE -> "mdi2i-image-outline";
            case FONT -> "mdi2f-format-font";
            case SQL -> "mdi2d-database";
            case SCRIPT -> "mdi2s-script-text-outline";
            case BINARY -> "mdi2b-binary";
            case MODULE_INFO -> "mdi2c-cube-scan";
            default -> "mdi2f-file-outline";
        };
        FontIcon i = new FontIcon(literal);
        i.setIconSize(13);
        return i;
    }
}
