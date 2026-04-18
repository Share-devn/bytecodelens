# Windows Package Manager (winget) manifest for BytecodeLens

Three YAML files that become a single winget package entry. After submission users install BytecodeLens with:

```powershell
winget install Share-devn.BytecodeLens
```

## Files

- `Share-devn.BytecodeLens.yaml` — root version manifest
- `Share-devn.BytecodeLens.installer.yaml` — installer URL + SHA256 + product code
- `Share-devn.BytecodeLens.locale.en-US.yaml` — human-readable metadata

## Refresh the manifests for a new release

```powershell
# 1. Download the new .msi
curl -L -o BytecodeLens-windows-x64.msi `
     https://github.com/Share-devn/bytecodelens/releases/download/v<VERSION>/BytecodeLens-windows-x64.msi

# 2. SHA256 (uppercase hex — winget requires this casing)
certutil -hashfile BytecodeLens-windows-x64.msi SHA256

# 3. ProductCode — read it out of the MSI with PowerShell:
(Get-ItemProperty -Path "Registry::HKEY_CLASSES_ROOT\Installer\Products\*" |
  Where-Object { $_.ProductName -match "BytecodeLens" }).PSChildName
# That gives you a GUID like 00000000-0000-0000-0000-000000000000.
# Wrap in braces and uppercase for the ProductCode field.

# 4. Paste SHA256 + ProductCode into Share-devn.BytecodeLens.installer.yaml,
#    and bump PackageVersion in all three files.
```

## Submit to winget-pkgs

```powershell
# One-time setup:
# - Fork https://github.com/microsoft/winget-pkgs
# - Install wingetcreate:
winget install Microsoft.WingetCreate

# Fastest path — wingetcreate regenerates the manifests for you from the MSI:
wingetcreate update Share-devn.BytecodeLens --urls https://github.com/Share-devn/bytecodelens/releases/download/v<VERSION>/BytecodeLens-windows-x64.msi --version <VERSION> --submit

# Or, manual PR:
# 1. Copy the three YAMLs into your winget-pkgs fork under:
#    manifests/s/Share-devn/BytecodeLens/<VERSION>/
# 2. Validate locally:
winget validate --manifest manifests/s/Share-devn/BytecodeLens/<VERSION>/
# 3. Optional: test-install on a clean VM:
winget install --manifest manifests/s/Share-devn/BytecodeLens/<VERSION>/
# 4. Open PR against https://github.com/microsoft/winget-pkgs
#    Title:  "New version: Share-devn.BytecodeLens version <VERSION>"
```

Review typically takes 1–3 days. Once merged, the package is live.

## Automating future submissions

Once the first submission has gone through, every subsequent release can be automated via
[`vedantmgoyal2009/winget-releaser`](https://github.com/vedantmgoyal2009/winget-releaser). Add a GitHub Actions step
that runs on `release: published` and the PR to `winget-pkgs` opens itself.
