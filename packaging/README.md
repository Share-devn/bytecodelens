# Packaging templates

Drop-in manifests for the major package managers. None of these are *active* yet — this directory is a parking space for maintainers (you, future contributors, or volunteers) to copy from when they upload BytecodeLens to a specific ecosystem.

Each subdirectory has its own README with exact step-by-step upload instructions.

| Directory | Target | Difficulty | Status |
|---|---|---|---|
| [`aur/`](aur/) | Arch Linux (AUR) | Easy — any Arch user can upload | Ready to publish |
| [`winget/`](winget/) | Windows Package Manager | Easy — PR to `microsoft/winget-pkgs` | Ready to publish |
| [`homebrew/`](homebrew/) | macOS / Linux Homebrew | Medium — needs own tap repo, or Apple Developer cert for official cask | Ready for personal tap |
| [`flathub/`](flathub/) | Linux (Flathub) | Hard — Flathub review takes 1–4 weeks | Ready, needs submission |

## Versioning contract

Every template expects **GitHub Releases** at `Share-devn/bytecodelens` to carry these asset names:

- `BytecodeLens-<version>-all.jar` — portable fat jar (input for AUR, Homebrew, Flathub)
- `BytecodeLens-windows-x64.msi` — Windows installer (input for Winget)
- `BytecodeLens-macos-aarch64.dmg` — Apple Silicon macOS
- `BytecodeLens-macos-x64.dmg` — Intel macOS
- `BytecodeLens-linux-x64.deb` — Debian/Ubuntu

When you bump the project version, update the `version=` / `Version:` fields in each manifest and refresh the SHA256 hashes. Every template has a "how to refresh hashes" comment at the top.

## Why are these not wired up automatically?

Each ecosystem has its own submission/review process that is fundamentally **human-gated**:

- **AUR**: requires an Arch user account with an SSH key; the upload is a `git push` to `aur.archlinux.org`.
- **Winget**: requires a PR against `microsoft/winget-pkgs`; the reviewer team checks installer behaviour.
- **Homebrew cask (official)**: requires macOS code signing + notarization (Apple Developer Program, $99/year) and some traction metric (downloads, stars).
- **Flathub**: requires a PR against `flathub/flathub` and a ~1–4 week manual review cycle.

Automating them from CI is possible for Winget (via `vedantmgoyal2009/winget-releaser` action) and Homebrew-tap (via `mislav/bump-homebrew-formula-action`), but we deliberately keep it manual for 1.0.x until the project has the audience to justify it.
