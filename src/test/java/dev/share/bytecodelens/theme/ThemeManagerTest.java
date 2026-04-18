package dev.share.bytecodelens.theme;

import javafx.scene.Group;
import javafx.scene.Parent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeManagerTest {

    @Test
    void applySetsStyleClass() {
        Parent root = new Group();
        ThemeManager.apply(root, SyntaxTheme.DRACULA);
        assertTrue(root.getStyleClass().contains("syntax-dracula"));
    }

    @Test
    void applyReplacesPreviousTheme() {
        Parent root = new Group();
        ThemeManager.apply(root, SyntaxTheme.DRACULA);
        ThemeManager.apply(root, SyntaxTheme.MONOKAI);
        assertTrue(root.getStyleClass().contains("syntax-monokai"));
        assertFalse(root.getStyleClass().contains("syntax-dracula"));
    }

    @Test
    void applyIgnoresNullRootOrTheme() {
        // No NPE for null root.
        ThemeManager.apply(null, SyntaxTheme.DRACULA);
        Parent root = new Group();
        ThemeManager.apply(root, null);
        assertEquals(0, root.getStyleClass().stream()
                .filter(c -> c.startsWith("syntax-")).count());
    }

    @Test
    void byIdFindsKnownTheme() {
        assertEquals(SyntaxTheme.DRACULA, ThemeManager.byId("dracula"));
    }

    @Test
    void byIdReturnsDefaultForUnknown() {
        assertEquals(SyntaxTheme.PRIMER_DARK, ThemeManager.byId("never-heard-of"));
    }

    @Test
    void availableListContainsAllConstants() {
        assertTrue(ThemeManager.AVAILABLE.contains(SyntaxTheme.PRIMER_DARK));
        assertTrue(ThemeManager.AVAILABLE.contains(SyntaxTheme.DRACULA));
        assertTrue(ThemeManager.AVAILABLE.contains(SyntaxTheme.MONOKAI));
        assertTrue(ThemeManager.AVAILABLE.contains(SyntaxTheme.SOLARIZED_DARK));
    }
}
