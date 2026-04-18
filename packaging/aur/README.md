# AUR package for BytecodeLens

This folder holds the AUR (Arch User Repository) package definition. Publishing is a one-time setup; subsequent releases are a two-line bump.

## Files

- `PKGBUILD` — the Arch package recipe (source of truth).
- `bytecodelens.sh` — tiny wrapper that becomes `/usr/bin/bytecodelens` on install.
- `bytecodelens.desktop` — Freedesktop menu entry.

## First-time upload

You need:

- An Arch Linux machine (or a WSL Arch instance, or an `archlinux/archlinux` Docker container for testing).
- An [AUR account](https://aur.archlinux.org/) with an SSH key registered.

### 1. Test the build locally

```bash
cd packaging/aur
# Resolve the real SHA256 for the released jar:
curl -sL "https://github.com/Share-devn/bytecodelens/releases/download/v1.0.0/BytecodeLens-1.0.0-all.jar" | sha256sum
# Put that value into PKGBUILD (first entry in sha256sums=()), and for the other four
# local/Github-raw sources you can leave 'SKIP' during bootstrapping; replace with real
# sha256 sums before pushing to AUR.
makepkg -sf
# Installs a .pkg.tar.zst in the current directory — sanity-check it:
sudo pacman -U ./bytecodelens-1.0.0-1-any.pkg.tar.zst
bytecodelens --help   # should print the CLI help
```

### 2. Create the AUR repo

```bash
git clone ssh://aur@aur.archlinux.org/bytecodelens.git aur-bytecodelens
cd aur-bytecodelens
cp ../packaging/aur/PKGBUILD .
cp ../packaging/aur/bytecodelens.sh .
cp ../packaging/aur/bytecodelens.desktop .
makepkg --printsrcinfo > .SRCINFO
git add PKGBUILD .SRCINFO bytecodelens.sh bytecodelens.desktop
git commit -m "bytecodelens 1.0.0-1"
git push
```

Five minutes later the package appears at `https://aur.archlinux.org/packages/bytecodelens`, installable via any AUR helper (`yay -S bytecodelens`, `paru -S bytecodelens`, etc.).

## Updating on a new release

```bash
# In the aur-bytecodelens clone:
sed -i "s/^pkgver=.*/pkgver=NEW_VERSION/" PKGBUILD
sed -i "s/^pkgrel=.*/pkgrel=1/" PKGBUILD
# Refresh the jar checksum:
curl -sL "https://github.com/Share-devn/bytecodelens/releases/download/vNEW_VERSION/BytecodeLens-NEW_VERSION-all.jar" | sha256sum
# Paste that into sha256sums=('...' 'SKIP' ...).
makepkg --printsrcinfo > .SRCINFO
makepkg -sf   # verify the clean build
git commit -am "bytecodelens NEW_VERSION-1"
git push
```

## Notes

- The jar is the only architecture-independent runtime; `arch=('any')` is correct.
- Java 21 is declared via the meta-dep `java-runtime>=21`, which matches any OpenJDK 21+ from the official repos.
- Users who prefer `bytecodelens-bin` (native installer jar without needing a JDK) can be accommodated later with a second AUR package — most users benefit from sharing the system JDK.
