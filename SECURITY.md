# Security Policy

## Supported versions

Security fixes are applied on the active development line in this repository.

## Reporting a vulnerability

Please report suspected vulnerabilities privately to the maintainers instead of opening a public issue.

Include:

- affected module (`test-order-core`, `test-order-agent`, `test-order-maven-plugin`, or `test-order-gradle-plugin`)
- reproduction steps
- expected impact
- whether the issue depends on a specific agent, JUnit version, or build tool

## Security notes

`test-order` instruments bytecode during learn mode and reads project source, test metadata, and git history for change detection. When reviewing security-sensitive changes, pay extra attention to:

- Java agent attachment and class transformation boundaries
- file loading and persistence paths
- decompression and binary index parsing
- shell/process execution for git and build tooling
- interactions with other Java agents such as coverage tools
