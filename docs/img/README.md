# Screenshots

This folder holds the images referenced from the top-level `README.md`.

## Required

### `hero.png`

The single image shown at the top of the README. One good screenshot carries the
whole README — spend two minutes setting the scene properly.

**How to capture it**

1. Launch the app in **light theme** (first-run default) and **maximise** the window on
   a **1920×1080** display (or 1600×900 minimum). Anything smaller and the grid looks
   cramped in the GitHub preview.
2. Open a jar with a visible package structure — `spring-core`, `guava`, or any
   100-class+ library works. Avoid empty-looking tiny jars.
3. In the project tree on the left, **expand two or three packages** so the hierarchy is
   obvious.
4. Click a non-trivial class (one with methods and fields, 50+ lines of decompiled code)
   to open it in the preview tab. Leave the **Decompiled** view active — not Bytecode or
   Hex; readable Java sells the product.
5. Make sure the **toolbar** shows all icon groups (File / Nav / Search / Analyze /
   Export / Settings / Theme).
6. Verify the **status bar** at the bottom shows the jar badge (`N classes · X.Y MB`)
   and, after a few seconds of browsing, the cache badge (`Cache N% · …`).
7. Hide any personal paths from the status bar — either close the jar and reopen from
   a neutral location, or crop the image.
8. Save as `docs/img/hero.png` (PNG, not JPG — crisp text matters).

**Target width in README**: 900 px. GitHub handles retina downscaling fine, so shooting
at 1920 and letting markdown render smaller is preferred.

## Optional — add later

If you want a feature showcase section in the README, drop additional images here and
link them with descriptive alt text. Candidates:

- `jvm-inspector.png` — JVM Inspector with live line charts
- `hex-viewer.png` — hex view with structure overlay sidebar
- `usages.png` — hierarchical Find Usages with snippet previews
- `compare.png` — Compare tab with multiple decompiler columns
- `settings.png` — Settings window with a section open
- `dark-theme.png` — same scene as hero but in dark theme

Keep each image under 500 KB; run through `oxipng -o 4` or `pngquant` if needed.
