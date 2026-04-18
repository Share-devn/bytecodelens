package dev.share.bytecodelens.keymap;

import javafx.scene.input.KeyCombination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeymapStoreTest {

    private static KeymapStore newStore(Path storage) throws Exception {
        Constructor<KeymapStore> c = KeymapStore.class.getDeclaredConstructor(Path.class);
        c.setAccessible(true);
        return c.newInstance(storage);
    }

    @Test
    void defaultsAreLoadedWhenFileAbsent(@TempDir Path dir) throws Exception {
        KeymapStore store = newStore(dir.resolve("keymap.json"));
        assertEquals("Ctrl+Shift+F", store.get(Actions.FIND_IN_JAR));
        assertEquals("Ctrl+N", store.get(Actions.GOTO_CLASS));
    }

    @Test
    void setAndGetRoundTrips(@TempDir Path dir) throws Exception {
        KeymapStore store = newStore(dir.resolve("keymap.json"));
        store.set(Actions.FIND_IN_JAR, "Ctrl+Alt+F");
        assertEquals("Ctrl+Alt+F", store.get(Actions.FIND_IN_JAR));
    }

    @Test
    void emptyAcceleratorRemovesBinding(@TempDir Path dir) throws Exception {
        KeymapStore store = newStore(dir.resolve("keymap.json"));
        store.set(Actions.FIND_IN_JAR, "");
        assertNull(store.get(Actions.FIND_IN_JAR));
    }

    @Test
    void persistAcrossInstances(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keymap.json");
        {
            KeymapStore store = newStore(file);
            store.set(Actions.TOGGLE_THEME, "F9");
        }
        {
            KeymapStore store2 = newStore(file);
            assertEquals("F9", store2.get(Actions.TOGGLE_THEME));
        }
    }

    @Test
    void conflictsDetectDuplicateBindings(@TempDir Path dir) throws Exception {
        KeymapStore store = newStore(dir.resolve("keymap.json"));
        store.set(Actions.FIND_IN_JAR, "Ctrl+X");
        store.set(Actions.TOGGLE_THEME, "Ctrl+X");
        List<List<String>> conflicts = store.conflicts();
        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("edit.find.jar"));
        assertTrue(conflicts.get(0).contains("view.toggle.theme"));
    }

    @Test
    void conflictsCaseInsensitive(@TempDir Path dir) throws Exception {
        KeymapStore store = newStore(dir.resolve("keymap.json"));
        store.set(Actions.FIND_IN_JAR, "Ctrl+X");
        store.set(Actions.TOGGLE_THEME, "ctrl+x");
        assertFalse(store.conflicts().isEmpty());
    }

    @Test
    void applyPresetIntellijRemapsNavigation(@TempDir Path dir) throws Exception {
        KeymapStore store = newStore(dir.resolve("keymap.json"));
        store.applyPreset(KeymapStore.Preset.INTELLIJ);
        assertEquals("Ctrl+Alt+Left", store.get(Actions.NAV_BACK));
        assertEquals("Ctrl+F4", store.get(Actions.CLOSE_TAB));
    }

    @Test
    void applyPresetVSCodeSetsGotoFileToCtrlP(@TempDir Path dir) throws Exception {
        KeymapStore store = newStore(dir.resolve("keymap.json"));
        store.applyPreset(KeymapStore.Preset.VSCODE);
        assertEquals("Ctrl+P", store.get(Actions.GOTO_CLASS));
    }

    @Test
    void combinationForInvalidReturnsNull(@TempDir Path dir) throws Exception {
        KeymapStore store = newStore(dir.resolve("keymap.json"));
        // KeyCombination.valueOf accepts a surprising range of garbage; "+++" doesn't parse.
        store.set(Actions.FIND_IN_JAR, "+++");
        assertNull(store.combinationFor(Actions.FIND_IN_JAR));
    }

    @Test
    void combinationForValidReturnsKeyCombo(@TempDir Path dir) throws Exception {
        KeymapStore store = newStore(dir.resolve("keymap.json"));
        KeyCombination k = store.combinationFor(Actions.FIND_IN_JAR);
        assertNotNull(k);
    }

    @Test
    void jsonParserHandlesSerializeOutput() {
        // Use reflection to exercise parseJson directly on known output.
        try {
            var m = KeymapStore.class.getDeclaredMethod("parseJson", String.class);
            m.setAccessible(true);
            String input = "{\n  \"a.b\": \"Ctrl+X\",\n  \"c.d\": \"Ctrl+Y\"\n}\n";
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = (Map<String, String>) m.invoke(null, input);
            assertEquals("Ctrl+X", parsed.get("a.b"));
            assertEquals("Ctrl+Y", parsed.get("c.d"));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void jsonParserIgnoresTrailingWhitespaceAndNulls() throws Exception {
        var m = KeymapStore.class.getDeclaredMethod("parseJson", String.class);
        m.setAccessible(true);
        String input = "{ \"a\" : null , \"b\" : \"Ctrl+Z\"  }";
        @SuppressWarnings("unchecked")
        Map<String, String> parsed = (Map<String, String>) m.invoke(null, input);
        assertEquals("", parsed.get("a"));
        assertEquals("Ctrl+Z", parsed.get("b"));
    }
}
