package dev.share.bytecodelens.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full UI state that can be saved to and restored from disk.
 *
 * <p>Plain data class with null-safe defaults. The JSON writer/reader sits in
 * {@link WorkspaceJson} — kept separate so the model stays dependency-free.</p>
 */
public final class WorkspaceState {

    public String jarPath;            // absolute path to the jar
    public String mappingFile;        // absolute path to last-applied mapping (nullable)
    public String mappingFormat;      // enum name (nullable)
    public List<String> openTabs = new ArrayList<>();  // fqns
    public String activeTab;          // fqn (nullable)
    public double mainSplit1 = 0.20;  // first divider (left panel)
    public double mainSplit2 = 0.74;  // second divider (right info panel)
    /** Vertical divider between editor and bottom inspector tabs (0 = all top, 1 = all bottom). */
    public double centerSplit = 0.72;
    public boolean darkTheme = false;
    /** Code editor font size in points. Ctrl+wheel / Ctrl+plus/minus adjust; Ctrl+0 resets. */
    public double codeFontSize = 13.0;
    /** Comment storage, keyed by symbolKey: "CLASS:fqn", "METHOD:fqn:name:desc", "FIELD:fqn:name:desc". */
    public Map<String, String> comments = new LinkedHashMap<>();
    /**
     * Packages the user has chosen to exclude from search / global operations. Entries
     * are plain prefixes ({@code com.google}) or prefix-with-star ({@code com.google.*}).
     * Only affects search — the tree still shows these classes.
     */
    public List<String> excludedPackages = new ArrayList<>();
}
