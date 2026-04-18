package dev.share.bytecodelens.settings;

import java.util.Locale;

/**
 * Pure helper used by the Settings dialog to decide whether a given section or
 * field should be visible for a given search query.
 *
 * <p>Case-insensitive substring match. Tokenised — every whitespace-separated
 * token in the query must appear (ANDed) in the candidate text. Empty query
 * matches everything.</p>
 */
public final class SettingsSearchFilter {

    private SettingsSearchFilter() {}

    public static boolean matches(String query, String... haystacks) {
        if (query == null || query.isBlank()) return true;
        String needle = query.toLowerCase(Locale.ROOT).trim();
        // Build a single lowercase concatenation of every haystack separated by spaces.
        StringBuilder bag = new StringBuilder();
        for (String h : haystacks) {
            if (h == null) continue;
            if (bag.length() > 0) bag.append(' ');
            bag.append(h.toLowerCase(Locale.ROOT));
        }
        String bagStr = bag.toString();
        for (String token : needle.split("\\s+")) {
            if (token.isEmpty()) continue;
            if (!bagStr.contains(token)) return false;
        }
        return true;
    }
}
