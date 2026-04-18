package dev.share.bytecodelens.i18n;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Translation façade. Wrap a {@link ResourceBundle} lookup with safe-defaulting so
 * callers never get a {@code MissingResourceException} at runtime: a missing key
 * returns the key itself (prefixed with {@code !}) so it's spottable during testing.
 *
 * <p>The actual bundle is loaded from {@code /i18n/strings_<lang>_<COUNTRY>.properties}.
 * English is the fallback ({@code strings.properties}); the project ships RU out of
 * the box, other languages can be plugged in by dropping additional property files
 * into {@code ~/.bytecodelens/i18n/} — those are scanned first if present.</p>
 */
public final class Lang {

    public static final String BUNDLE_BASE = "i18n.strings";

    /**
     * Active bundle. Pinned to English for the v0.3 release — other locale files
     * (e.g. {@code strings_ru.properties}) are kept in resources as reference for
     * future translation work but cannot be selected from the UI yet.
     *
     * <p>We use {@link Locale#ROOT} so {@link ResourceBundle#getBundle} does not
     * walk the system default chain and accidentally pick up
     * {@code strings_ru.properties} on a ru_RU host.</p>
     */
    private static ResourceBundle bundle = load(Locale.ROOT);

    /**
     * No-op in v0.3 — locale is pinned to English. Kept for API compatibility so
     * callers that used to switch on user preference don't have to change.
     * Future releases will re-enable this once full translations exist.
     */
    public static void setLocale(Locale locale) {
        // intentionally empty: locked to English until translations are complete.
    }

    /** Look up a string by key. Returns {@code "!" + key} if the key is missing. */
    public static String t(String key) {
        if (bundle == null) return "!" + key;
        try {
            return bundle.getString(key);
        } catch (MissingResourceException ex) {
            return "!" + key;
        }
    }

    /** {@code String.format}-style interpolation over a translated template. */
    public static String t(String key, Object... args) {
        return String.format(t(key), args);
    }

    /**
     * Current locale the façade is serving. Always {@link Locale#ENGLISH} in v0.3
     * (the bundle itself is loaded from {@link Locale#ROOT} to avoid the system
     * locale chain pulling in a non-English fallback).
     */
    public static Locale currentLocale() {
        return Locale.ENGLISH;
    }

    private static ResourceBundle load(Locale locale) {
        try {
            return ResourceBundle.getBundle(BUNDLE_BASE, locale);
        } catch (MissingResourceException ex) {
            // Fallback to an empty bundle so callers don't NPE — every lookup returns
            // the key itself per the MissingResourceException contract in #t.
            return null;
        }
    }

    private Lang() {}
}
