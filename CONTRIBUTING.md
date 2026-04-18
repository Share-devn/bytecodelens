# Contributing to BytecodeLens

Thanks for considering a contribution. This guide covers the quick essentials —
file an issue for anything it doesn't answer.

## Ways to contribute

- **Bug reports.** Include the version, platform, a minimal reproducing jar when possible,
  and relevant console output. Screenshots help for UI issues.
- **Feature requests.** State the use case first, the proposed UI second. Problem-first
  framing gets answered fastest.
- **Pull requests.** See below.

## Development

### Requirements

- JDK 21 or newer (tested up to 25)
- Git

### Build & run

```bash
git clone https://github.com/Share-devn/bytecodelens.git
cd bytecodelens
./gradlew compileJava   # fast sanity check
./gradlew test          # full unit suite — 474 tests
./gradlew run           # launch the GUI
./gradlew shadowJar     # build bytecodelens-<version>-all.jar
```

### Project layout

```
src/main/java/dev/share/bytecodelens/
├── agent/          — Java agent for JVM attach
├── asm/            — ASM-based assembler DSL
├── cli/            — headless command-line entry point
├── comments/       — persistent user comments
├── compile/        — javac + phantom classpath
├── decompile/      — CFR/Vineflower/Procyon/Fallback + chain + cache
├── detector/       — obfuscator detection (12 engines)
├── graph/          — call graph
├── hierarchy/      — class inheritance index + overrider search
├── i18n/           — ResourceBundle façade
├── jvminspect/     — JVM state parsers
├── keymap/         — customisable keybindings
├── mapping/        — 11 mapping formats
├── model/          — ClassEntry, LoadedJar, JarResource
├── nativelibs/     — ELF/PE/Mach-O parser, JNI demangler
├── pattern/        — BLPL pattern language
├── search/         — streaming search engine
├── service/        — jar loading, analyzing, export
├── settings/       — AppSettings + store + JSON persistence
├── structure/      — hex viewer structure overlay parsers
├── theme/          — syntax themes
├── transform/      — deobfuscation passes
├── ui/             — JavaFX controllers / stages / views
├── usage/          — UsageIndex, StringLiteralIndex, recursive callers
└── workspace/      — per-jar state save/restore
```

### Code style

- Keep changes **surgical**. Don't reformat adjacent code you didn't change.
- **No speculative abstractions.** If a second caller doesn't exist yet, inline the logic.
- **Locale-safe formatting.** Always use `Locale.ROOT` when formatting numbers or CSS
  inline styles — the codebase has been bitten by the `16.0` → `"16,0"` bug on ru_RU hosts.
- **Idempotent transformations.** Any new `Transformation` subclass must be a no-op on a
  second run over the same input.
- **No emojis** in source files or documentation unless the user (or an existing style)
  explicitly uses them.

### Tests

- New pure helpers must come with unit tests. JavaFX-dependent code is exempt (no TestFX
  in the build).
- The suite runs in under 10 seconds — keep it that way. No sleep-based timing.
- Run `./gradlew test` before opening a PR.

### Commit messages

Short imperative subject, body only if the "why" isn't obvious. Reference the issue number
when applicable.

## Pull request checklist

- [ ] `./gradlew test` passes locally
- [ ] `./gradlew compileJava` has no new warnings
- [ ] New tests added for non-trivial pure code
- [ ] `CHANGELOG.md` updated under `[unreleased]` for user-visible changes
- [ ] No emojis or stray formatting in touched files

## License

By submitting a contribution you agree it will be released under the
[Apache License 2.0](LICENSE).
