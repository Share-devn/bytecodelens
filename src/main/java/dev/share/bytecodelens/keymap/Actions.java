package dev.share.bytecodelens.keymap;

import java.util.List;

/**
 * Central catalogue of every user-invokable action. Adding a new hotkey means:
 * <ol>
 *   <li>Add a constant here with a stable id + default accelerator.</li>
 *   <li>Register a {@link Runnable} for it in {@code MainController} via
 *       {@code KeymapStore.bind(Action, Runnable)}.</li>
 *   <li>Optionally surface it in the menu — FXML accelerators still work, the keymap
 *       UI just lets the user override them.</li>
 * </ol>
 *
 * <p>IDs use dotted namespacing so future plugins can contribute their own actions
 * without colliding with core ones.</p>
 */
public final class Actions {

    // --- File --------------------------------------------------------------
    public static final Action OPEN_FILE = Action.of(
            "file.open", "Open File\u2026", "File", "Ctrl+O");
    public static final Action OPEN_FROM_URL = Action.of(
            "file.open.url", "Open from URL\u2026", "File", null);
    public static final Action CLOSE_JAR = Action.of(
            "file.close", "Close Jar", "File", null);
    public static final Action SAVE_AS = Action.of(
            "file.save.as", "Save As\u2026", "File", null);
    public static final Action EXIT = Action.of(
            "file.exit", "Exit", "File", "Ctrl+Q");

    // --- Edit / Search -----------------------------------------------------
    public static final Action FIND_IN_JAR = Action.of(
            "edit.find.jar", "Find in Jar\u2026", "Edit", "Ctrl+Shift+F");
    public static final Action CLEAR_HIGHLIGHTS = Action.of(
            "edit.clear.highlights", "Clear Highlights", "Edit", "Ctrl+K");
    public static final Action GOTO_CLASS = Action.of(
            "navigate.class", "Go to Class\u2026", "Navigate", "Ctrl+N");
    public static final Action COMPARE_WITH = Action.of(
            "edit.compare", "Compare with\u2026", "Edit", "Ctrl+D");

    // --- Navigate ----------------------------------------------------------
    public static final Action NAV_BACK = Action.of(
            "navigate.back", "Back", "Navigate", "Alt+Left");
    public static final Action NAV_FORWARD = Action.of(
            "navigate.forward", "Forward", "Navigate", "Alt+Right");
    public static final Action SYNC_TREE = Action.of(
            "navigate.sync.tree", "Sync Tree with Editor", "Navigate", "Ctrl+L");
    /**
     * JADX-style "X" to find usages of the identifier under the caret. IntelliJ users
     * can remap to Alt+F7 via Preferences → Keymap.
     */
    public static final Action FIND_USAGES = Action.of(
            "navigate.find.usages", "Find Usages", "Navigate", "X");
    public static final Action CLOSE_TAB = Action.of(
            "editor.close.tab", "Close Tab", "Editor", "Ctrl+W");

    // --- View --------------------------------------------------------------
    public static final Action TOGGLE_THEME = Action.of(
            "view.toggle.theme", "Toggle Theme", "View", "Ctrl+T");
    public static final Action FONT_LARGER = Action.of(
            "view.font.larger", "Increase Font Size", "View", "Ctrl+Plus");
    public static final Action FONT_SMALLER = Action.of(
            "view.font.smaller", "Decrease Font Size", "View", "Ctrl+Minus");
    public static final Action FONT_RESET = Action.of(
            "view.font.reset", "Reset Font Size", "View", "Ctrl+0");

    // --- Analyze -----------------------------------------------------------
    public static final Action DETECT_OBFUSCATOR = Action.of(
            "analyze.detect.obfuscator", "Detect Obfuscator", "Analyze", null);
    public static final Action APPLY_MAPPING = Action.of(
            "analyze.apply.mapping", "Apply Mapping\u2026", "Analyze", null);
    public static final Action EXPORT_MAPPING = Action.of(
            "analyze.export.mapping", "Export Mapping\u2026", "Analyze", null);
    public static final Action MASS_RENAME = Action.of(
            "analyze.mass.rename", "Mass Rename\u2026", "Analyze", null);

    /** Immutable list of every registered action. */
    public static final List<Action> ALL = List.of(
            OPEN_FILE, OPEN_FROM_URL, CLOSE_JAR, SAVE_AS, EXIT,
            FIND_IN_JAR, CLEAR_HIGHLIGHTS, COMPARE_WITH, GOTO_CLASS,
            NAV_BACK, NAV_FORWARD, SYNC_TREE, CLOSE_TAB, FIND_USAGES,
            TOGGLE_THEME, FONT_LARGER, FONT_SMALLER, FONT_RESET,
            DETECT_OBFUSCATOR, APPLY_MAPPING, EXPORT_MAPPING, MASS_RENAME);

    private Actions() {}
}
