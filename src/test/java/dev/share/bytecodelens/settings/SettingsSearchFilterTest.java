package dev.share.bytecodelens.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsSearchFilterTest {

    @Test
    void emptyQueryMatchesEverything() {
        assertTrue(SettingsSearchFilter.matches("", "Appearance", "UI theme"));
        assertTrue(SettingsSearchFilter.matches(null, "Appearance"));
        assertTrue(SettingsSearchFilter.matches("   ", "Appearance"));
    }

    @Test
    void simpleSubstringMatch() {
        assertTrue(SettingsSearchFilter.matches("theme", "Appearance", "UI theme picker"));
        assertFalse(SettingsSearchFilter.matches("font", "Appearance", "UI theme picker"));
    }

    @Test
    void caseInsensitive() {
        assertTrue(SettingsSearchFilter.matches("THEME", "Appearance", "ui theme"));
        assertTrue(SettingsSearchFilter.matches("ui", "APPEARANCE", "UI THEME"));
    }

    @Test
    void multipleTokensAndedAcrossHaystacks() {
        // Both tokens must appear somewhere in the combined bag.
        assertTrue(SettingsSearchFilter.matches("cache capacity",
                "Decompiler", "Cache capacity", "Size of the LRU"));
        assertFalse(SettingsSearchFilter.matches("cache frobnicate",
                "Decompiler", "Cache capacity"));
    }

    @Test
    void nullsInHaystackAreIgnored() {
        assertTrue(SettingsSearchFilter.matches("theme", null, "UI theme"));
    }

    @Test
    void whitespaceTokensIgnored() {
        assertTrue(SettingsSearchFilter.matches("  theme   ", "UI theme"));
    }
}
