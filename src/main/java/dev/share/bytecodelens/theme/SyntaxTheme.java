package dev.share.bytecodelens.theme;

/**
 * A named syntax color scheme. Pure data — the actual CSS variable values live in
 * {@code app.css} under {@code .root.syntax-<id>} selectors. Selecting a theme means
 * swapping the style class on the scene root, not rebuilding stylesheets.
 */
public record SyntaxTheme(String id, String displayName, boolean dark) {

    /** Default Github-style palette (Primer Dark / Primer Light under AtlantaFX). */
    public static final SyntaxTheme PRIMER_DARK = new SyntaxTheme("primer-dark", "Primer Dark", true);
    public static final SyntaxTheme PRIMER_LIGHT = new SyntaxTheme("primer-light", "Primer Light", false);
    public static final SyntaxTheme DRACULA = new SyntaxTheme("dracula", "Dracula", true);
    public static final SyntaxTheme MONOKAI = new SyntaxTheme("monokai", "Monokai", true);
    public static final SyntaxTheme SOLARIZED_DARK = new SyntaxTheme("solarized-dark", "Solarized Dark", true);
    public static final SyntaxTheme SOLARIZED_LIGHT = new SyntaxTheme("solarized-light", "Solarized Light", false);
}
