package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.decompile.DecompileStatusTracker;
import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.JarResource;
import dev.share.bytecodelens.ui.views.ClassEditorTab;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.TreeCell;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.function.Function;

public final class ClassTreeCell extends TreeCell<MainController.TreeNode> {

    private final Function<String, ClassEntry> resolver;

    public ClassTreeCell(Function<String, ClassEntry> resolver) {
        this.resolver = resolver;
        getStyleClass().add("class-tree-cell");
    }

    @Override
    protected void updateItem(MainController.TreeNode item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            setTooltip(null);
            return;
        }
        setText(item.label());
        Node graphic = iconFor(item);
        // Class nodes get a possible decompile-status badge — non-class items unchanged.
        if (item.kind() == MainController.NodeKind.CLASS) {
            graphic = withDecompileBadge(graphic, item.fqn());
        }
        setGraphic(graphic);
        String tip = item.fqn();
        // Augment tooltip with decompile status when non-clean.
        String statusTip = decompileTooltipFor(item);
        if (statusTip != null) {
            tip = (tip == null || tip.isEmpty() ? "" : tip + "\n") + statusTip;
        }
        if (tip != null && !tip.isEmpty() && !tip.equals(item.label())) {
            javafx.scene.control.Tooltip t = new javafx.scene.control.Tooltip(tip);
            t.setShowDelay(javafx.util.Duration.millis(400));
            setTooltip(t);
        } else {
            setTooltip(null);
        }
    }

    /**
     * If the class is in {@code FALLBACK_ONLY} or {@code FAILED} state, wrap the icon
     * in an HBox with a small badge so users can see at a glance which classes the
     * decompiler couldn't render cleanly. Gated by {@code tree.showDecompileStatusBadges}
     * in the app settings so users who find it noisy can switch it off.
     */
    private Node withDecompileBadge(Node baseIcon, String fqn) {
        if (fqn == null) return baseIcon;
        var settings = dev.share.bytecodelens.settings.AppSettingsStore.getInstance().get();
        if (!settings.tree.showDecompileStatusBadges) return baseIcon;
        ClassEntry e = resolver.apply(fqn);
        if (e == null) return baseIcon;
        DecompileStatusTracker.Status s = ClassEditorTab.statusTracker().statusOf(e.internalName());
        if (s == DecompileStatusTracker.Status.SUCCESS || s == DecompileStatusTracker.Status.UNKNOWN) {
            return baseIcon;
        }
        FontIcon badge;
        if (s == DecompileStatusTracker.Status.FAILED) {
            badge = new FontIcon("mdi2c-close-circle");
            badge.getStyleClass().addAll("tree-icon", "icon-decompile-failed");
        } else {
            badge = new FontIcon("mdi2a-alert-circle-outline");
            badge.getStyleClass().addAll("tree-icon", "icon-decompile-fallback");
        }
        badge.setIconSize(11);
        HBox box = new HBox(2, baseIcon, badge);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private String decompileTooltipFor(MainController.TreeNode item) {
        if (item.kind() != MainController.NodeKind.CLASS) return null;
        ClassEntry e = resolver.apply(item.fqn());
        if (e == null) return null;
        DecompileStatusTracker.Entry entry = ClassEditorTab.statusTracker().get(e.internalName());
        if (entry == null) return null;
        return switch (entry.status()) {
            case FAILED -> "Decompile failed (" + entry.engineUsed() + ")"
                    + (entry.reason() != null ? ": " + entry.reason() : "");
            case FALLBACK_ONLY -> "Decompile fell back to ASM skeleton — real engines could not render";
            default -> null;
        };
    }

    private Node iconFor(MainController.TreeNode item) {
        FontIcon icon;
        String colorClass;
        switch (item.kind()) {
            case ROOT -> {
                icon = new FontIcon("mdi2p-package-variant-closed");
                colorClass = "icon-root";
            }
            case PACKAGE -> {
                icon = new FontIcon("mdi2p-package-variant");
                colorClass = "icon-package";
            }
            case CLASS -> {
                return iconForClass(item.fqn());
            }
            case RESOURCE_FOLDER -> {
                icon = new FontIcon("mdi2f-folder-outline");
                colorClass = "icon-folder";
            }
            case RESOURCE -> {
                return iconForResource(item.resourceKind(), item.label());
            }
            case MULTI_RELEASE_ROOT -> {
                icon = new FontIcon("mdi2l-layers-outline");
                colorClass = "icon-multi-release";
            }
            case MULTI_RELEASE_VERSION -> {
                icon = new FontIcon("mdi2l-layers-triple-outline");
                colorClass = "icon-multi-release";
            }
            case METHOD -> {
                // Small "m" disc to visually echo IntelliJ's method icon without pulling in
                // the full set. Constructors look the same — distinguishing them would need
                // extra state on TreeNode and adds little value.
                icon = new FontIcon("mdi2a-alpha-m-circle-outline");
                colorClass = "icon-method";
            }
            case FIELD -> {
                icon = new FontIcon("mdi2a-alpha-f-circle-outline");
                colorClass = "icon-field";
            }
            case MEMBERS_PLACEHOLDER -> {
                icon = new FontIcon("mdi2d-dots-horizontal");
                colorClass = "icon-other";
            }
            default -> {
                icon = new FontIcon("mdi2f-file-outline");
                colorClass = "icon-other";
            }
        }
        icon.setIconSize(15);
        icon.getStyleClass().addAll("tree-icon", colorClass);
        return icon;
    }

    private FontIcon iconForClass(String fqn) {
        ClassEntry e = resolver.apply(fqn);
        FontIcon icon;
        String colorClass;
        if (e == null) {
            icon = new FontIcon("mdi2l-language-java");
            colorClass = "icon-class";
        } else if (e.isModule()) {
            icon = new FontIcon("mdi2c-cube-scan");
            colorClass = "icon-module";
        } else if (e.isAnnotation()) {
            icon = new FontIcon("mdi2a-at");
            colorClass = "icon-annotation";
        } else if (e.isInterface()) {
            icon = new FontIcon("mdi2c-cube-outline");
            colorClass = "icon-interface";
        } else if (e.isEnum()) {
            icon = new FontIcon("mdi2f-format-list-bulleted-type");
            colorClass = "icon-enum";
        } else if (e.isRecord()) {
            icon = new FontIcon("mdi2d-database-outline");
            colorClass = "icon-record";
        } else {
            icon = new FontIcon("mdi2c-code-braces");
            colorClass = "icon-class";
        }
        icon.setIconSize(15);
        icon.getStyleClass().addAll("tree-icon", colorClass);
        return icon;
    }

    private FontIcon iconForResource(JarResource.ResourceKind kind, String name) {
        String literal;
        String colorClass;
        if (kind == null) {
            literal = "mdi2f-file-outline";
            colorClass = "icon-other";
        } else {
            switch (kind) {
                case JAVA_CLASS -> {
                    literal = "mdi2l-language-java";
                    colorClass = "icon-class";
                }
                case MODULE_INFO -> {
                    literal = "mdi2c-cube-scan";
                    colorClass = "icon-module";
                }
                case NATIVE_DLL -> {
                    literal = "mdi2m-microsoft-windows";
                    colorClass = "icon-native-dll";
                }
                case NATIVE_SO -> {
                    literal = "mdi2l-linux";
                    colorClass = "icon-native-so";
                }
                case NATIVE_DYLIB -> {
                    literal = "mdi2a-apple";
                    colorClass = "icon-native-dylib";
                }
                case NESTED_JAR -> {
                    literal = "mdi2a-archive-outline";
                    colorClass = "icon-jar";
                }
                case NESTED_WAR -> {
                    literal = "mdi2w-web";
                    colorClass = "icon-war";
                }
                case NESTED_ZIP -> {
                    literal = "mdi2z-zip-box-outline";
                    colorClass = "icon-zip";
                }
                case MANIFEST -> {
                    literal = "mdi2f-file-document-outline";
                    colorClass = "icon-manifest";
                }
                case SERVICE -> {
                    literal = "mdi2c-cog-outline";
                    colorClass = "icon-service";
                }
                case PROPERTIES -> {
                    literal = "mdi2f-file-cog-outline";
                    colorClass = "icon-properties";
                }
                case XML -> {
                    literal = "mdi2x-xml";
                    colorClass = "icon-xml";
                }
                case JSON -> {
                    literal = "mdi2c-code-json";
                    colorClass = "icon-json";
                }
                case YAML -> {
                    literal = "mdi2f-file-code-outline";
                    colorClass = "icon-yaml";
                }
                case TEXT -> {
                    literal = "mdi2f-file-document-outline";
                    colorClass = "icon-text";
                }
                case IMAGE -> {
                    literal = "mdi2i-image-outline";
                    colorClass = "icon-image";
                }
                case FONT -> {
                    literal = "mdi2f-format-font";
                    colorClass = "icon-font";
                }
                case SQL -> {
                    literal = "mdi2d-database";
                    colorClass = "icon-sql";
                }
                case SCRIPT -> {
                    literal = "mdi2s-script-text-outline";
                    colorClass = "icon-script";
                }
                case BINARY -> {
                    literal = "mdi2b-binary";
                    colorClass = "icon-binary";
                }
                default -> {
                    literal = "mdi2f-file-outline";
                    colorClass = "icon-other";
                }
            }
        }
        FontIcon icon = new FontIcon(literal);
        icon.setIconSize(15);
        icon.getStyleClass().addAll("tree-icon", colorClass);
        return icon;
    }
}
