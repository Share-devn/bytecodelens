# Flathub manifest for BytecodeLens

Flathub's review cycle is the slowest and strictest of the four packaging systems we target — expect **1 to 4 weeks** between PR open and first release. Budget accordingly.

## Files

| File | Purpose |
|---|---|
| `dev.share.BytecodeLens.yml` | Build manifest (sources, SDK extensions, finish-args) |
| `bytecodelens.sh` | Launcher script installed at `/app/bin/bytecodelens` |
| `dev.share.BytecodeLens.desktop` | Freedesktop menu entry |
| `dev.share.BytecodeLens.metainfo.xml` | AppStream metadata (name, description, screenshots, releases) |

You also need **three icon files** in this directory at build time:

- `icon-128.png` — 128x128
- `icon-256.png` — 256x256
- `icon-512.png` — 512x512

Generate them from the bundled logo:

```bash
cd packaging/flathub
for size in 128 256 512; do
  magick ../../icons/BytecodeLens-C1-avatar-512.png -resize ${size}x${size} icon-${size}.png
done
```

(`magick` is ImageMagick 7; on older systems use `convert`.)

## Local smoke test

```bash
# One-time: install flatpak-builder and the Freedesktop runtime + OpenJDK 21 SDK ext.
sudo dnf install flatpak-builder   # Fedora
# or: sudo apt install flatpak-builder
flatpak install --user flathub \
  org.freedesktop.Platform//23.08 \
  org.freedesktop.Sdk//23.08 \
  org.freedesktop.Sdk.Extension.openjdk21//23.08

# Build locally:
cd packaging/flathub
flatpak-builder --force-clean --user --install-deps-from=flathub --install \
  build-dir dev.share.BytecodeLens.yml

# Run the resulting sandboxed app:
flatpak run dev.share.BytecodeLens
```

If the sandbox can't find the Freedesktop SDK or the OpenJDK extension, the error names the missing piece; install it with `flatpak install flathub …`.

## Validate AppStream metadata

Flathub rejects PRs with broken `metainfo.xml`. Validate before submitting:

```bash
flatpak run org.freedesktop.appstream-glib validate packaging/flathub/dev.share.BytecodeLens.metainfo.xml
# Or if you have appstream-util installed natively:
appstream-util validate-relax packaging/flathub/dev.share.BytecodeLens.metainfo.xml
```

## Submit to Flathub

1. Fork [https://github.com/flathub/flathub](https://github.com/flathub/flathub). This is a "new submission" repo — your PR proposes creating a new per-app repo.

2. From the fork root, branch off `master`:

   ```bash
   git checkout -b new-pr/dev.share.BytecodeLens
   ```

3. Copy the manifest + metadata files into the PR branch (flatpak-builder inputs go in the root of the submission repo):

   ```bash
   mkdir -p new-app/dev.share.BytecodeLens
   cp packaging/flathub/* new-app/dev.share.BytecodeLens/
   cd new-app/dev.share.BytecodeLens
   ```

4. Refresh the jar SHA256 in `dev.share.BytecodeLens.yml`:

   ```bash
   VER=1.0.0
   curl -sL "https://github.com/Share-devn/bytecodelens/releases/download/v$VER/BytecodeLens-$VER-all.jar" | sha256sum
   # Paste into the sources.sha256 field in the yml.
   ```

5. Open a PR against `flathub/flathub` with the title:

   ```
   Add dev.share.BytecodeLens
   ```

   Flathub bot runs automated checks first (usually ~10 minutes). A human reviewer will then request any required changes. Typical requests:

   - Reduce `finish-args` permissions to only what's strictly needed.
   - Clean up the summary/description per AppStream guidelines.
   - Add changelog entries for `<release>` tags.

6. After merge, your app gets its own repo at `https://github.com/flathub/dev.share.BytecodeLens`. All future updates go there — bump the yml + metainfo release entry and open a PR.

## Publishing a new version

```bash
# In the Flathub per-app repo:
cd ~/code/flathub/dev.share.BytecodeLens
VER=1.1.0

# 1. Refresh jar checksum
curl -sL "https://github.com/Share-devn/bytecodelens/releases/download/v$VER/BytecodeLens-$VER-all.jar" | sha256sum

# 2. Bump the yml: sources.url, sources.sha256
# 3. Add a <release version="$VER" date="YYYY-MM-DD"> entry to metainfo.xml
# 4. Validate + local build
flatpak-builder --force-clean build-dir dev.share.BytecodeLens.yml
# 5. PR
git commit -am "BytecodeLens $VER"
git push origin main
```

## Why not include cached `flathub-build` locally?

The manifest uses `openjdk21` SDK extension which fetches ~500 MB on first build. We keep only the source manifest in git; each developer builds locally on demand.
