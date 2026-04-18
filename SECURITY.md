# Security Policy

## Supported Versions

Security fixes land on the latest released minor (`1.x`). Older minors only receive
fixes for critical vulnerabilities.

| Version | Supported |
| ------- | :-------: |
| 1.0.x   | ✅        |
| < 1.0   | ❌        |

## Reporting a Vulnerability

Please do **not** open a public GitHub issue for security problems.

To report a vulnerability, open a private [security advisory](https://github.com/Share-devn/bytecodelens/security/advisories/new) on this repository. Include:

- The affected version of BytecodeLens.
- Steps to reproduce, or a minimal proof-of-concept.
- The impact (what an attacker can do).
- Any known mitigations.

You will receive a response within **3 working days**. Confirmed issues will be fixed in
the next release; a CVE will be requested where appropriate.

## Scope

BytecodeLens is a desktop tool for inspecting untrusted JVM bytecode. The threat model
assumes the user is intentionally loading potentially hostile `.jar` / `.class` files.

In scope:

- Arbitrary code execution triggered by **opening a crafted jar or class file** for
  viewing / decompiling (i.e. before the user chooses to execute or recompile it).
- Injection / path-traversal during **Export Sources** or **Save As**.
- Agent protocol vulnerabilities in the JVM attach feature.

Out of scope (by design):

- Running user-compiled code after explicit **Edit → Compile** or **Recompile Java**.
  These actions are documented as running arbitrary Java on your machine.
- Running the decompiled output of untrusted code outside BytecodeLens.
- Vulnerabilities in bundled third-party decompilers (CFR, Vineflower, Procyon) —
  please report those upstream, but feel free to CC us if they affect this project.
