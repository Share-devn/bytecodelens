package dev.share.bytecodelens.keymap;

import javafx.scene.input.KeyCombination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent map of action-id → keyboard accelerator string. Loaded at startup from
 * {@code ~/.bytecodelens/keymap.json}, saved on every change.
 *
 * <p>Accelerator strings are the JavaFX {@link KeyCombination#valueOf} grammar —
 * {@code "Ctrl+Shift+F"}, {@code "Alt+Left"}. Platform-dependent tokens ({@code Shortcut}
 * → {@code Cmd} on macOS, {@code Ctrl} elsewhere) work verbatim.</p>
 *
 * <p>Two additional features sit atop the raw mapping:</p>
 * <ul>
 *   <li>{@link #conflicts()} — detect actions bound to the same accelerator, surfaced
 *       in the keymap editor UI.</li>
 *   <li>{@link #applyPreset(Preset)} — replace the map with a known IDE preset
 *       ({@link Preset#INTELLIJ}, {@link Preset#VSCODE}, {@link Preset#RECAF}).</li>
 * </ul>
 */
public final class KeymapStore {

    private static final Logger log = LoggerFactory.getLogger(KeymapStore.class);

    /** Safe parse: returns null on blank / malformed accelerator instead of throwing. */
    public static KeyCombination parse(String accel) {
        if (accel == null || accel.isBlank()) return null;
        try { return KeyCombination.valueOf(accel); }
        catch (Exception ex) { return null; }
    }

    /** action-id → accelerator string. Null/absent = "no binding". */
    private final Map<String, String> bindings = new LinkedHashMap<>();
    private final Path storage;

    public KeymapStore() { this(defaultStorage()); }

    KeymapStore(Path storage) {
        this.storage = storage;
        load();
        // Fill in defaults for any action the user's file didn't mention.
        for (Action a : Actions.ALL) {
            if (!bindings.containsKey(a.id()) && a.defaultAccelerator() != null) {
                bindings.put(a.id(), a.defaultAccelerator());
            }
        }
    }

    public String get(Action action) {
        if (action == null) return null;
        return bindings.get(action.id());
    }

    /** Parse an action's binding into a JavaFX KeyCombination, or null if unbound/invalid. */
    public KeyCombination combinationFor(Action action) {
        String s = get(action);
        if (s == null || s.isBlank()) return null;
        try {
            return KeyCombination.valueOf(s);
        } catch (Exception ex) {
            log.debug("Invalid key combination for {}: {}", action.id(), s);
            return null;
        }
    }

    public void set(Action action, String accelerator) {
        Objects.requireNonNull(action);
        if (accelerator == null || accelerator.isBlank()) {
            bindings.remove(action.id());
        } else {
            bindings.put(action.id(), accelerator);
        }
        save();
    }

    public void clear(Action action) {
        bindings.remove(action.id());
        save();
    }

    /** Return a defensive copy of the current bindings, ordered by insertion. */
    public Map<String, String> all() { return new LinkedHashMap<>(bindings); }

    /**
     * Find actions that share a single accelerator. Returns a list where each entry is
     * the list of clashing action ids (2+ entries per group). Ordering inside a group
     * is stable (insertion order).
     */
    public List<List<String>> conflicts() {
        Map<String, List<String>> by = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            by.computeIfAbsent(e.getValue().toLowerCase(), k -> new ArrayList<>()).add(e.getKey());
        }
        List<List<String>> out = new ArrayList<>();
        for (List<String> group : by.values()) {
            if (group.size() > 1) out.add(Collections.unmodifiableList(group));
        }
        return out;
    }

    public enum Preset {
        DEFAULT, INTELLIJ, VSCODE, RECAF;
    }

    /** Replace current bindings with the chosen preset's map. Persists to disk. */
    public void applyPreset(Preset preset) {
        bindings.clear();
        Map<String, String> p = switch (preset) {
            case DEFAULT -> defaultBindings();
            case INTELLIJ -> intellijBindings();
            case VSCODE -> vsCodeBindings();
            case RECAF -> recafBindings();
        };
        bindings.putAll(p);
        save();
    }

    private static Map<String, String> defaultBindings() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Action a : Actions.ALL) {
            if (a.defaultAccelerator() != null) m.put(a.id(), a.defaultAccelerator());
        }
        return m;
    }

    /**
     * IntelliJ keymap — matches the well-known IntelliJ defaults. Differences from
     * ours: Ctrl+F12 for structure (n/a), Alt+Insert (n/a), but the navigation &
     * search keys line up well so overriding them feels familiar.
     */
    private static Map<String, String> intellijBindings() {
        Map<String, String> m = defaultBindings();
        m.put(Actions.FIND_IN_JAR.id(), "Ctrl+Shift+F");       // same
        m.put(Actions.GOTO_CLASS.id(), "Ctrl+N");               // same
        m.put(Actions.NAV_BACK.id(), "Ctrl+Alt+Left");
        m.put(Actions.NAV_FORWARD.id(), "Ctrl+Alt+Right");
        m.put(Actions.SYNC_TREE.id(), "Alt+F1");
        m.put(Actions.CLOSE_TAB.id(), "Ctrl+F4");
        return m;
    }

    /**
     * VS Code keymap — their "Go to File" and "Go back" patterns.
     */
    private static Map<String, String> vsCodeBindings() {
        Map<String, String> m = defaultBindings();
        m.put(Actions.FIND_IN_JAR.id(), "Ctrl+Shift+F");       // same
        m.put(Actions.GOTO_CLASS.id(), "Ctrl+P");
        m.put(Actions.NAV_BACK.id(), "Ctrl+Alt+Minus");        // Windows VS Code
        m.put(Actions.NAV_FORWARD.id(), "Ctrl+Shift+Minus");
        m.put(Actions.CLOSE_TAB.id(), "Ctrl+W");                // same
        m.put(Actions.TOGGLE_THEME.id(), "Ctrl+K Ctrl+T");      // chord — JavaFX won't parse, will be rejected gracefully
        return m;
    }

    /**
     * Recaf keymap — approximates their defaults for users switching over.
     */
    private static Map<String, String> recafBindings() {
        Map<String, String> m = defaultBindings();
        m.put(Actions.FIND_IN_JAR.id(), "Ctrl+F");              // Recaf uses plain Ctrl+F globally
        m.put(Actions.GOTO_CLASS.id(), "Ctrl+G");
        return m;
    }

    // --- Persistence -----------------------------------------------------

    private void load() {
        if (!Files.exists(storage)) return;
        try {
            String json = Files.readString(storage, StandardCharsets.UTF_8);
            Map<String, String> parsed = parseJson(json);
            bindings.putAll(parsed);
        } catch (Exception ex) {
            log.debug("Failed to load keymap: {}", ex.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(storage.getParent());
            Files.writeString(storage, serializeJson(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.debug("Failed to save keymap: {}", ex.getMessage());
        }
    }

    String serializeJson() {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            if (i > 0) sb.append(",\n");
            sb.append("  ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
            i++;
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    /**
     * Tiny top-level JSON object parser — keymap files are flat {id: accelerator}
     * maps so full JSON isn't needed. Accepts the output of {@link #serializeJson}
     * plus whatever a user might hand-edit (extra whitespace, trailing commas OK).
     */
    static Map<String, String> parseJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) return out;
        int n = json.length();
        int i = json.indexOf('{');
        if (i < 0) return out;
        i++;
        while (i < n) {
            i = skipWs(json, i);
            if (i >= n) break;
            char c = json.charAt(i);
            if (c == '}') break;
            if (c == ',') { i++; continue; }
            if (c != '"') { i++; continue; }
            int[] keyEnd = new int[1];
            String key = readJsonString(json, i, keyEnd);
            if (key == null) break;
            i = skipWs(json, keyEnd[0]);
            if (i >= n || json.charAt(i) != ':') break;
            i = skipWs(json, i + 1);
            if (i >= n) break;
            // Value: either a string or null.
            if (json.startsWith("null", i)) {
                out.put(key, "");
                i += 4;
                continue;
            }
            if (json.charAt(i) != '"') break;
            int[] valEnd = new int[1];
            String val = readJsonString(json, i, valEnd);
            if (val == null) break;
            out.put(key, val);
            i = valEnd[0];
        }
        return out;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String readJsonString(String s, int i, int[] outEnd) {
        if (i >= s.length() || s.charAt(i) != '"') return null;
        StringBuilder sb = new StringBuilder();
        int j = i + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '"') {
                outEnd[0] = j + 1;
                return sb.toString();
            }
            if (c == '\\' && j + 1 < s.length()) {
                char n = s.charAt(j + 1);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default -> sb.append(n);
                }
                j += 2;
                continue;
            }
            sb.append(c);
            j++;
        }
        return null;
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static Path defaultStorage() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".bytecodelens", "keymap.json");
    }
}
