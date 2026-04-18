package dev.share.bytecodelens.theme;

import javafx.scene.Parent;

import java.util.List;

/**
 * Applies {@link SyntaxTheme} choices to a JavaFX scene root by toggling CSS style
 * classes. We don't swap stylesheets — everything is one {@code app.css} where each
 * theme is a {@code .root.syntax-<id>} namespace.
 *
 * <p>Order of style classes matters for the theme/base-theme combo:
 * {@code root} has either {@code dark-theme} or {@code light-theme} (the AtlantaFX-level
 * toggle) plus exactly one {@code syntax-<id>} to pick the palette.</p>
 */
public final class ThemeManager {

    /** All currently registered themes — order matters for the View→Theme menu. */
    public static final List<SyntaxTheme> AVAILABLE = List.of(
            SyntaxTheme.PRIMER_DARK,
            SyntaxTheme.PRIMER_LIGHT,
            SyntaxTheme.DRACULA,
            SyntaxTheme.MONOKAI,
            SyntaxTheme.SOLARIZED_DARK,
            SyntaxTheme.SOLARIZED_LIGHT);

    /** Apply the given theme to the root, removing any previous syntax-* class. */
    public static void apply(Parent root, SyntaxTheme theme) {
        if (root == null || theme == null) return;
        root.getStyleClass().removeIf(c -> c.startsWith("syntax-"));
        root.getStyleClass().add("syntax-" + theme.id());
    }

    public static SyntaxTheme byId(String id) {
        for (SyntaxTheme t : AVAILABLE) {
            if (t.id().equals(id)) return t;
        }
        return SyntaxTheme.PRIMER_DARK;
    }

    private ThemeManager() {}
}
