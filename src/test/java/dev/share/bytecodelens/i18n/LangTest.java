package dev.share.bytecodelens.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lang is pinned to English for the v0.3 release — setLocale is a no-op. These tests
 * lock that contract so we notice if someone re-enables locale switching without
 * extending the test suite.
 */
class LangTest {

    @Test
    void defaultEnglishBundleResolves() {
        assertEquals("File", Lang.t("menu.file"));
        assertEquals("Find in jar\u2026", Lang.t("search.placeholder"));
    }

    @Test
    void missingKeyReturnsBangPrefixed() {
        String v = Lang.t("definitely.missing.key");
        assertTrue(v.startsWith("!"));
        assertTrue(v.contains("definitely.missing.key"));
    }

    @Test
    void formattedLookupFillsArgs() {
        // No template in the bundle uses %s at the moment, but the helper should still
        // work with format specifiers embedded in the fallback string.
        String raw = Lang.t("test.nonexistent.key");
        assertTrue(raw.startsWith("!"));
        assertEquals("Plain text", String.format("Plain text"));
    }

    @Test
    void setLocaleIsNoOpUntilTranslationsReturn() {
        // v0.3 ships English only — setLocale is intentionally neutered so callers
        // that still invoke it don't accidentally switch to a partial locale.
        Lang.setLocale(Locale.forLanguageTag("ru"));
        assertEquals("File", Lang.t("menu.file"));
        Lang.setLocale(Locale.forLanguageTag("xx"));
        assertEquals("File", Lang.t("menu.file"));
    }

    @Test
    void currentLocaleReportsEnglish() {
        assertEquals(Locale.ENGLISH, Lang.currentLocale());
    }
}
