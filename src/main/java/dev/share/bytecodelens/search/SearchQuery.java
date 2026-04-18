package dev.share.bytecodelens.search;

import java.util.List;

/**
 * @param excludedPackages glob-ish list of packages to skip entirely. Entries may be
 *                         plain prefixes ({@code com.google}) or end in {@code *}
 *                         ({@code org.jetbrains.*}). {@code null} is treated as empty.
 */
public record SearchQuery(
        String text,
        SearchMode mode,
        boolean caseSensitive,
        boolean wholeWord,
        String packageFilter,
        boolean searchClasses,
        boolean searchResources,
        List<String> excludedPackages
) {
    /** Back-compat constructor for call sites that don't care about exclusions. */
    public SearchQuery(String text, SearchMode mode, boolean caseSensitive,
                       boolean wholeWord, String packageFilter,
                       boolean searchClasses, boolean searchResources) {
        this(text, mode, caseSensitive, wholeWord, packageFilter,
                searchClasses, searchResources, List.of());
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }

    public List<String> excludedPackagesSafe() {
        return excludedPackages == null ? List.of() : excludedPackages;
    }
}
