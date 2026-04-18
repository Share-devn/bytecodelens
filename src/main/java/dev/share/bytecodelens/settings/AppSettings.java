package dev.share.bytecodelens.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * App-wide settings persisted to {@code ~/.bytecodelens/settings.json}.
 *
 * <p>Distinct from {@link dev.share.bytecodelens.workspace.WorkspaceState}: workspace state
 * is per-jar (tabs, mapping, divider positions, excluded packages) and is saved explicitly
 * by the user. These settings are global defaults that follow the user across jars.</p>
 *
 * <p>Everything has a safe default — missing keys in the JSON fall back to these values
 * at read time. {@link #version} lets us add migrators later if we need to rename fields.</p>
 */
public final class AppSettings {

    /** Bumped whenever the schema changes in an incompatible way. Read path tolerates older versions. */
    public int version = 1;

    public final Appearance appearance = new Appearance();
    public final Editor editor = new Editor();
    public final Decompiler decompiler = new Decompiler();
    public final Xref xref = new Xref();
    public final Search search = new Search();
    public final Tree tree = new Tree();
    public final Hex hex = new Hex();
    public final Jvm jvm = new Jvm();
    public final Transformations transformations = new Transformations();
    public final Language language = new Language();
    public final Paths paths = new Paths();
    public final Advanced advanced = new Advanced();

    public enum UiTheme { LIGHT, DARK, SYSTEM }
    public enum DecompilerEngine { AUTO, CFR, VINEFLOWER, PROCYON, FALLBACK }
    public enum SearchMode { STRINGS, NAMES, BYTECODE, REGEX, NUMBERS, COMMENTS }
    public enum HexBase { HEX, DEC }
    public enum Endianness { LITTLE, BIG }
    public enum WarmupPriority { LOW, NORMAL }
    public enum LogLevel { ERROR, WARN, INFO, DEBUG }

    public static final class Appearance {
        public UiTheme uiTheme = UiTheme.LIGHT;
        public String syntaxThemeId = "primer-light";
        public String uiFontFamily = "JetBrains Mono";
        public double uiFontSize = 13.0;
        public boolean showLineNumbers = true;
        public boolean showCaretLineHighlight = true;
        public boolean showFocusedLinePulse = true;
        public boolean hoverHighlightIdentifiers = true;
        public boolean ctrlUnderlineOnHover = true;
    }

    public static final class Editor {
        public double codeFontSize = 13.0;
        public int tabSize = 4;
        public boolean showWhitespace = false;
        public boolean autoCollapseComments = false;
        public boolean wrapLongLines = false;
    }

    public static final class Decompiler {
        public DecompilerEngine defaultEngine = DecompilerEngine.AUTO;
        public int perEngineTimeoutMs = 15_000;
        public boolean autoFallbackOnFail = true;
        public boolean cacheEnabled = true;
        public int cacheCapacity = 256;
        public boolean backgroundWarmupEnabled = true;
        public int warmupNeighborhoodSize = 24;
        public WarmupPriority warmupThreadPriority = WarmupPriority.LOW;
    }

    public static final class Xref {
        public boolean showCodeSnippetPreview = true;
        public boolean includeOverridersInUsages = true;
        public int recursiveCallersMaxDepth = 5;
        public int recursiveCallersMaxPerNode = 50;
        public boolean stringLiteralIndexEnabled = true;
    }

    public static final class Search {
        public SearchMode defaultSearchMode = SearchMode.STRINGS;
        public int streamingThreshold = 0; // 0 = stream everything
        public boolean caseSensitiveDefault = false;
        public boolean persistExcludedPackagesAcrossJars = false;
    }

    public static final class Tree {
        public boolean showDecompileStatusBadges = true;
        public boolean expandableClassTreeDefault = true;
        public boolean openPreviewOnSingleClick = true;
        public boolean promoteOnDoubleClick = true;
        public boolean syncWithEditorOnOpen = false;
    }

    public static final class Hex {
        public int defaultRowWidth = 16;
        public HexBase offsetBase = HexBase.HEX;
        public boolean showStructureTabByDefault = true;
        public Endianness defaultInspectorEndianness = Endianness.LITTLE;
    }

    public static final class Jvm {
        public int autoRefreshIntervalMs = 2000;
        public boolean importClassesOnAttachByDefault = false;
    }

    public static final class Transformations {
        /** IDs of transformation passes selected by default in the Run Transformations dialog. */
        public Set<String> defaultSelectedPasses = new LinkedHashSet<>(List.of(
                "illegal-name-mapping",
                "static-value-inlining",
                "dead-code-removal",
                "unreachable-after-terminator",
                "opaque-predicate-simplification"));
    }

    public static final class Language {
        public String locale = "en"; // two-letter ISO — resolved via Locale(locale) at apply time
        public boolean fallbackToEnglish = true;
    }

    public static final class Paths {
        public String agentDir = ""; // empty = use default ~/.bytecodelens/agents
        public int recentLimit = 15;
        public int pinnedLimit = 10;
    }

    public static final class Advanced {
        public LogLevel gcLogLevel = LogLevel.INFO;
        public String javaHomeOverride = ""; // empty = use JAVA_HOME
        public int maxClassParseThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
    }

    /** Dumb deep copy (used by the settings dialog as a working draft). */
    public AppSettings copy() {
        AppSettings c = new AppSettings();
        c.version = version;

        c.appearance.uiTheme = appearance.uiTheme;
        c.appearance.syntaxThemeId = appearance.syntaxThemeId;
        c.appearance.uiFontFamily = appearance.uiFontFamily;
        c.appearance.uiFontSize = appearance.uiFontSize;
        c.appearance.showLineNumbers = appearance.showLineNumbers;
        c.appearance.showCaretLineHighlight = appearance.showCaretLineHighlight;
        c.appearance.showFocusedLinePulse = appearance.showFocusedLinePulse;
        c.appearance.hoverHighlightIdentifiers = appearance.hoverHighlightIdentifiers;
        c.appearance.ctrlUnderlineOnHover = appearance.ctrlUnderlineOnHover;

        c.editor.codeFontSize = editor.codeFontSize;
        c.editor.tabSize = editor.tabSize;
        c.editor.showWhitespace = editor.showWhitespace;
        c.editor.autoCollapseComments = editor.autoCollapseComments;
        c.editor.wrapLongLines = editor.wrapLongLines;

        c.decompiler.defaultEngine = decompiler.defaultEngine;
        c.decompiler.perEngineTimeoutMs = decompiler.perEngineTimeoutMs;
        c.decompiler.autoFallbackOnFail = decompiler.autoFallbackOnFail;
        c.decompiler.cacheEnabled = decompiler.cacheEnabled;
        c.decompiler.cacheCapacity = decompiler.cacheCapacity;
        c.decompiler.backgroundWarmupEnabled = decompiler.backgroundWarmupEnabled;
        c.decompiler.warmupNeighborhoodSize = decompiler.warmupNeighborhoodSize;
        c.decompiler.warmupThreadPriority = decompiler.warmupThreadPriority;

        c.xref.showCodeSnippetPreview = xref.showCodeSnippetPreview;
        c.xref.includeOverridersInUsages = xref.includeOverridersInUsages;
        c.xref.recursiveCallersMaxDepth = xref.recursiveCallersMaxDepth;
        c.xref.recursiveCallersMaxPerNode = xref.recursiveCallersMaxPerNode;
        c.xref.stringLiteralIndexEnabled = xref.stringLiteralIndexEnabled;

        c.search.defaultSearchMode = search.defaultSearchMode;
        c.search.streamingThreshold = search.streamingThreshold;
        c.search.caseSensitiveDefault = search.caseSensitiveDefault;
        c.search.persistExcludedPackagesAcrossJars = search.persistExcludedPackagesAcrossJars;

        c.tree.showDecompileStatusBadges = tree.showDecompileStatusBadges;
        c.tree.expandableClassTreeDefault = tree.expandableClassTreeDefault;
        c.tree.openPreviewOnSingleClick = tree.openPreviewOnSingleClick;
        c.tree.promoteOnDoubleClick = tree.promoteOnDoubleClick;
        c.tree.syncWithEditorOnOpen = tree.syncWithEditorOnOpen;

        c.hex.defaultRowWidth = hex.defaultRowWidth;
        c.hex.offsetBase = hex.offsetBase;
        c.hex.showStructureTabByDefault = hex.showStructureTabByDefault;
        c.hex.defaultInspectorEndianness = hex.defaultInspectorEndianness;

        c.jvm.autoRefreshIntervalMs = jvm.autoRefreshIntervalMs;
        c.jvm.importClassesOnAttachByDefault = jvm.importClassesOnAttachByDefault;

        c.transformations.defaultSelectedPasses =
                new LinkedHashSet<>(transformations.defaultSelectedPasses);

        c.language.locale = language.locale;
        c.language.fallbackToEnglish = language.fallbackToEnglish;

        c.paths.agentDir = paths.agentDir;
        c.paths.recentLimit = paths.recentLimit;
        c.paths.pinnedLimit = paths.pinnedLimit;

        c.advanced.gcLogLevel = advanced.gcLogLevel;
        c.advanced.javaHomeOverride = advanced.javaHomeOverride;
        c.advanced.maxClassParseThreads = advanced.maxClassParseThreads;

        return c;
    }

    /** Used by tests / restore-defaults — expose a fresh defaulted instance. */
    public static AppSettings defaults() { return new AppSettings(); }

    /** Prevent AppSettings from carrying mutable List-literals after copy. */
    @SuppressWarnings("unused")
    private static <T> List<T> freshList() { return new ArrayList<>(); }
}
