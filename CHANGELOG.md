# Changelog

All notable changes to BytecodeLens are documented here.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] — unreleased

Initial public release. Brings BytecodeLens from a private workbench to a feature-complete
Java reverse-engineering tool with first-class robustness, xref depth, deobfuscation breadth,
mapping support, headless CLI, and a full settings window.

### Added

**Robustness**
- `DecompileCache` — bounded LRU cross-tab cache keyed by class + engine + bytes hash, with
  automatic invalidation on hot-reload.
- `BackgroundDecompiler` — single-thread low-priority warmer that pre-decompiles the package
  neighbourhood around the currently open class.
- `DecompileStatusTracker` + tree badges — failed / fallback-only classes get an inline icon
  and tooltip in the project tree, plus a right-click "Recover with Fallback" action.

**Xref depth**
- `StringLiteralIndex` — parallel index of every LDC string, field ConstantValue, and
  invokedynamic bootstrap argument.
- Code snippet preview under each call-site row in the Usages panel.
- Overriders / implementers appended to Find Usages via `HierarchyIndex` traversal.
- `RecursiveCallerSearch` — callers-of-callers tree with cycle detection and configurable
  depth / breadth caps.

**Deobfuscation**
Four new transformation passes, bringing the total to 11 + 2 anti-tamper helpers:
- `CallResultInlining` — replaces calls to side-effect-free static constant getters with the
  constant itself.
- `EnumNameRestoration` — recovers obfuscated enum constant field names from the literal in
  `<clinit>`.
- `StackFrameRemoval` — strips StackMapTable entries; ASM recomputes on write.
- `SourceNameRestoration` — restores the `SourceFile` attribute to `OuterClass.java` for
  classes missing one.
- `KotlinDataClassRestoration` — best-effort Kotlin `Companion` / `INSTANCE` / `componentN`
  recovery without pulling in `kotlinx-metadata-jvm`.

**Mapping**
Six new formats (11 total), both read and write:
- Tiny v1 · TSRG v2 · XSRG · CSRG · JOBF · Recaf simple

Plus `MappingOps.diff` / `compose` / `invert` for stacked refactor workflows, and a
content-sniffing loader that distinguishes e.g. SRG vs XSRG by FD column count.

**Headless CLI**
New entry point at `dev.share.bytecodelens.cli.Cli`. If the first argv is a recognised
subcommand, the JavaFX runtime is never loaded.
- `decompile <jar> -o <dir> [--engine auto|cfr|vineflower|procyon|fallback]`
- `analyze <jar> [--report-json <out>]` — class / resource / usage counts as JSON
- `mappings convert <in> --to <FORMAT> -o <out>`
- `mappings diff <a> <b> [--report-json <out>]`

**Settings**
- Full Settings window (Ctrl+,) with live-apply, replacing the earlier Preferences dialog.
- 13 sections: Appearance, Editor, Decompiler, Xref, Search, Tree & Navigation, Hex Viewer,
  JVM Inspector, Transformations, Keymap, Language, Paths, Advanced, About.
- Search box filters both the sidebar and individual fields by substring across section
  labels and keywords.
- Persistent app-level settings at `~/.bytecodelens/settings.json`, with atomic write,
  tolerant parsing (missing keys → defaults), and listener-based live propagation to the
  main window.

**UI polish**
- Expanded toolbar: 16 grouped icon buttons (File / Navigation / Search / Analyze / Export
  / Settings) with tooltips and shortcut hints.
- Status-bar badges for jar size, active mapping, and decompile cache hit rate.
- Selected-tab accent underline across editor, bottom, and inner tab panes.
- Harmonised hover / focus / disabled states for toolbar and search widgets.

### Changed

- Locale handling pinned to English for the 1.0 release. The Russian bundle ships as a
  reference for translators but is not UI-selectable.
- `Lang.setLocale` is a no-op — callers can still invoke it for API compatibility.
- Transformations dialog now reads its default-selected passes from user settings.

### Removed

- Legacy `PreferencesStage` and `KeymapEditorStage` classes; functionality migrated into
  the new Settings window. The `KeymapStore.parse(...)` helper is preserved.

### Tests

474 unit tests, all passing.

---

[1.0.0]: https://github.com/Share-devn/bytecodelens/releases/tag/v1.0.0
