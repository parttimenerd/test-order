# Security Policy

## Supported Versions

Only the latest released version receives security updates. Older versions are not patched.

| Version | Supported          |
| ------- | ------------------ |
| Latest  | :white_check_mark: |
| Older   | :x:                |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, report them privately via GitHub's [Security Advisories](https://github.com/parttimenerd/test-order/security/advisories/new). This opens a private channel between you and the maintainers.

Include as much of the following as you can:

- A description of the issue and its impact
- Steps to reproduce (a minimal sample project is ideal)
- Affected version(s) of the plugin
- Any known mitigations or workarounds

You should receive an acknowledgement within one week. If the issue is confirmed, we will work on a fix and coordinate disclosure with you.

## Scope

`test-order` is a build-time Maven/Gradle plugin. The threat model focuses on:

- **Path traversal / arbitrary file writes** in `.test-order/` state directories or generated reports
- **Code execution via crafted state files** (the plugin reads JSON/text from `.test-order/` and from cached commit history)
- **Dashboard server** (`mvn test-order:serve`) — the embedded HTTP server binds to localhost by default; report any issue allowing remote access or unauthenticated state modification

Issues in third-party dependencies should be reported to those projects directly, but please also let us know so we can pin a patched version.
