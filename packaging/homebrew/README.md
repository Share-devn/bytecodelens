# Homebrew formula for BytecodeLens

## Why a tap instead of upstream homebrew-core?

The main `homebrew-core` repository [requires](https://docs.brew.sh/Acceptable-Formulae) notable popularity (usually ≥30 GitHub stars plus recent, non-trivial activity) before it accepts a new formula. During the bootstrap phase we ship through a personal **tap** — a separate git repo that users can register with one command. No external review, no waiting.

Same jar, same Java dependency. The only extra step for the end-user is one-time `brew tap`.

## End-user install

Once the tap is published:

```bash
brew tap Share-devn/bytecodelens
brew install bytecodelens
bytecodelens help          # CLI
bytecodelens               # GUI (opens a window)
```

## Publishing the tap (one-time)

1. Create an **empty, public** GitHub repo at `Share-devn/homebrew-bytecodelens`.
   Homebrew requires the repository name to start with `homebrew-`.

2. Clone it and drop `bytecodelens.rb` into `Formula/`:

   ```bash
   git clone https://github.com/Share-devn/homebrew-bytecodelens.git
   cd homebrew-bytecodelens
   mkdir -p Formula
   cp ../bytecodelens/packaging/homebrew/bytecodelens.rb Formula/
   ```

3. Refresh the SHA256 for the current jar:

   ```bash
   VER=1.0.0
   curl -sL "https://github.com/Share-devn/bytecodelens/releases/download/v$VER/BytecodeLens-$VER-all.jar" \
     | shasum -a 256
   # Paste the hash into the sha256 field in Formula/bytecodelens.rb.
   ```

4. Local smoke test before publishing:

   ```bash
   brew install --build-from-source ./Formula/bytecodelens.rb
   brew test bytecodelens
   brew uninstall bytecodelens
   ```

5. Commit and push:

   ```bash
   git add Formula/bytecodelens.rb
   git commit -m "bytecodelens 1.0.0"
   git push
   ```

6. Announce the tap in the BytecodeLens README install table:

   ```
   | macOS / Linux (Homebrew) | `brew tap Share-devn/bytecodelens && brew install bytecodelens` |
   ```

## Publishing a new version

```bash
cd homebrew-bytecodelens
VER=1.1.0
curl -sL "https://github.com/Share-devn/bytecodelens/releases/download/v$VER/BytecodeLens-$VER-all.jar" | shasum -a 256
# Edit Formula/bytecodelens.rb: bump version, paste new sha256.
git commit -am "bytecodelens $VER"
git push
```

Automating this is a one-liner with [`mislav/bump-homebrew-formula-action`](https://github.com/mislav/bump-homebrew-formula-action) on `release: published` — worth adding once the tap is in place.

## Future: official Homebrew cask for `.app` install

Moving from the CLI formula to a `.app`-dropping cask requires:

- macOS `.dmg` that has been **code-signed** and **notarized** by Apple (Apple Developer Program, $99/year).
- Cask manifest added to `homebrew/homebrew-cask`.

The cask gives `/Applications/BytecodeLens.app` instead of a terminal command. Useful when the project has audience; skippable until then.
