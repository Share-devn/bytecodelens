package dev.share.bytecodelens.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsJsonTest {

    @Test
    void roundTripsDefaults() throws IOException {
        AppSettings s = AppSettings.defaults();
        String json = AppSettingsJson.serialize(s);
        AppSettings back = AppSettingsJson.parse(json);
        assertEquals(s.appearance.uiTheme, back.appearance.uiTheme);
        assertEquals(s.appearance.syntaxThemeId, back.appearance.syntaxThemeId);
        assertEquals(s.decompiler.cacheCapacity, back.decompiler.cacheCapacity);
        assertEquals(s.xref.recursiveCallersMaxDepth, back.xref.recursiveCallersMaxDepth);
        assertEquals(s.transformations.defaultSelectedPasses, back.transformations.defaultSelectedPasses);
    }

    @Test
    void roundTripsCustomValues() {
        AppSettings s = AppSettings.defaults();
        s.appearance.uiTheme = AppSettings.UiTheme.DARK;
        s.appearance.syntaxThemeId = "dracula";
        s.appearance.uiFontSize = 15.5;
        s.decompiler.cacheCapacity = 512;
        s.decompiler.defaultEngine = AppSettings.DecompilerEngine.CFR;
        s.xref.showCodeSnippetPreview = false;
        s.search.defaultSearchMode = AppSettings.SearchMode.REGEX;
        s.hex.defaultRowWidth = 32;
        s.hex.offsetBase = AppSettings.HexBase.DEC;
        s.transformations.defaultSelectedPasses = new java.util.LinkedHashSet<>(List.of("dead-code", "kotlin"));
        s.paths.recentLimit = 42;

        AppSettings back = AppSettingsJson.parse(AppSettingsJson.serialize(s));
        assertEquals(AppSettings.UiTheme.DARK, back.appearance.uiTheme);
        assertEquals("dracula", back.appearance.syntaxThemeId);
        assertEquals(15.5, back.appearance.uiFontSize);
        assertEquals(512, back.decompiler.cacheCapacity);
        assertEquals(AppSettings.DecompilerEngine.CFR, back.decompiler.defaultEngine);
        assertFalse(back.xref.showCodeSnippetPreview);
        assertEquals(AppSettings.SearchMode.REGEX, back.search.defaultSearchMode);
        assertEquals(32, back.hex.defaultRowWidth);
        assertEquals(AppSettings.HexBase.DEC, back.hex.offsetBase);
        assertEquals(Set.of("dead-code", "kotlin"), back.transformations.defaultSelectedPasses);
        assertEquals(42, back.paths.recentLimit);
    }

    @Test
    void missingFieldsFallBackToDefaults() {
        String minimal = "{ \"version\": 1 }";
        AppSettings s = AppSettingsJson.parse(minimal);
        AppSettings defaults = AppSettings.defaults();
        assertEquals(defaults.appearance.uiTheme, s.appearance.uiTheme);
        assertEquals(defaults.decompiler.cacheCapacity, s.decompiler.cacheCapacity);
        assertEquals(defaults.xref.recursiveCallersMaxDepth, s.xref.recursiveCallersMaxDepth);
    }

    @Test
    void unknownFieldsAreIgnored() {
        String withExtra = "{ \"version\": 1, \"futureFeature\": true, " +
                "\"appearance\": { \"uiTheme\": \"DARK\", \"unknownKey\": 42 } }";
        AppSettings s = AppSettingsJson.parse(withExtra);
        assertEquals(AppSettings.UiTheme.DARK, s.appearance.uiTheme);
    }

    @Test
    void malformedJsonReturnsDefaults() {
        AppSettings s = AppSettingsJson.parse("{ this is not json");
        assertEquals(AppSettings.UiTheme.LIGHT, s.appearance.uiTheme); // default
    }

    @Test
    void invalidEnumValueFallsBack() {
        String bad = "{ \"appearance\": { \"uiTheme\": \"PLAID\" } }";
        AppSettings s = AppSettingsJson.parse(bad);
        assertEquals(AppSettings.UiTheme.LIGHT, s.appearance.uiTheme);
    }

    @Test
    void readOrDefaultsMissingFile(@TempDir Path tmp) {
        AppSettings s = AppSettingsJson.readOrDefaults(tmp.resolve("nope.json"));
        assertNotNull(s);
        assertEquals(AppSettings.UiTheme.LIGHT, s.appearance.uiTheme);
    }

    @Test
    void writeAtomicThenReadRoundTrip(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("settings.json");
        AppSettings src = AppSettings.defaults();
        src.appearance.uiTheme = AppSettings.UiTheme.DARK;
        src.decompiler.cacheCapacity = 999;
        AppSettingsJson.writeAtomic(src, file);
        assertTrue(Files.exists(file));
        AppSettings back = AppSettingsJson.readOrDefaults(file);
        assertEquals(AppSettings.UiTheme.DARK, back.appearance.uiTheme);
        assertEquals(999, back.decompiler.cacheCapacity);
    }

    @Test
    void writeCreatesParentDirs(@TempDir Path tmp) throws IOException {
        Path nested = tmp.resolve("a/b/c/settings.json");
        AppSettingsJson.writeAtomic(AppSettings.defaults(), nested);
        assertTrue(Files.exists(nested));
    }

    @Test
    void existingFileIsUntouchedByParseErrors(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "not valid json", StandardCharsets.UTF_8);
        AppSettings s = AppSettingsJson.readOrDefaults(file);
        // Still got defaults rather than an exception.
        assertEquals(AppSettings.UiTheme.LIGHT, s.appearance.uiTheme);
        // And we didn't overwrite the file.
        assertEquals("not valid json", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void copyIsIndependent() {
        AppSettings a = AppSettings.defaults();
        AppSettings b = a.copy();
        b.appearance.uiTheme = AppSettings.UiTheme.DARK;
        b.transformations.defaultSelectedPasses.add("custom");
        assertEquals(AppSettings.UiTheme.LIGHT, a.appearance.uiTheme);
        assertFalse(a.transformations.defaultSelectedPasses.contains("custom"));
    }

    @Test
    void escapedStringsSurvive() {
        AppSettings s = AppSettings.defaults();
        s.appearance.syntaxThemeId = "theme \"with\" \\quotes\\ and\nnewlines";
        AppSettings back = AppSettingsJson.parse(AppSettingsJson.serialize(s));
        assertEquals("theme \"with\" \\quotes\\ and\nnewlines", back.appearance.syntaxThemeId);
    }

    @Test
    void unicodeInStringsSurvives() {
        AppSettings s = AppSettings.defaults();
        s.appearance.syntaxThemeId = "тест-тема-\u2603";
        AppSettings back = AppSettingsJson.parse(AppSettingsJson.serialize(s));
        assertEquals("тест-тема-\u2603", back.appearance.syntaxThemeId);
    }
}
