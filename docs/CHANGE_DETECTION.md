# Change Detection Strategies

Comprehensive guide to test-order's change detection mechanisms.

## Overview

test-order supports **5 change detection strategies** to identify which classes changed and need test coverage.

| Strategy | Mode | Speed | Accuracy | Best For |
|----------|------|-------|----------|----------|
| Hash-based | `since-last-run` | Very Fast | High | Local development |
| Git-based | `since-last-commit` | Fast | Very High | CI/CD, branches |
| Uncommitted | `uncommitted` | Fast | High | Rapid iteration |
| Explicit | `explicit` | Fastest | Perfect | CI, known changes |
| Auto | `auto` | Variable | High | Default, recommended |

---

## Strategy 1: Hash-Based (since-last-run)

### Overview
Compares SHA-256 hashes of source files between current state and last recorded state.

### How It Works
```
Last run:  .test-order-hashes.lz4
           {
             "src/main/java/Service.java": "abc123...",
             "src/main/java/Util.java": "def456..."
           }
           
Current:   Compute SHA-256 of all .java files
           {
             "src/main/java/Service.java": "abc123...",    ✓ unchanged
             "src/main/java/Util.java": "xyz789..."        ✗ changed!
           }
           
Changed:   {Util}
```

### Configuration
```bash
mvn test-order:combined test -Dtestorder.change-mode=since-last-run
```

### Pros
✓ Fast (no external tools required)
✓ Works in any environment
✓ Perfect for local development
✓ Stores only hashes (small overhead)

### Cons
✗ Requires valid previous state
✗ Doesn't understand git history
✗ Resets on new branch

### When to Use
- Local development (primary workflow)
- Projects without git
- Fallback mechanism

### Implementation Details
```
1. On first run: Store SHA-256 of all source files
2. On subsequent runs:
   - Compute current SHA-256
   - Compare with stored hashes
   - Return changed files → classes
3. After test run: Update state file
```

### State File Format
```json
{
  "lastRunTimestamp": 1713700000000,
  "lastRunHashes": {
    "src/main/java/com/example/Service.java": "abc123def456...",
    "src/main/java/com/example/Util.java": "xyz789abc123...",
    "src/test/java/com/example/ServiceTest.java": "..."
  }
}
```

---

## Strategy 2: Git-Based (since-last-commit)

### Overview
Uses git history to determine what changed since a reference commit.

### How It Works
```
$ git diff <BASE>...HEAD
M src/main/java/Service.java
A src/main/java/NewClass.java
D src/main/java/OldClass.java

→ Changed classes: {Service, NewClass} (deleted ignored)
```

### Configuration
```bash
mvn test-order:combined test -Dtestorder.change-mode=since-last-commit

# Custom base commit
mvn test-order:combined test \
  -Dtestorder.change-mode=since-last-commit \
  -Dtestorder.git-base-commit=main
```

### Git Scenarios

#### Scenario 1: Feature Branch
```
main ──┬─ feature-branch
       │  │
       │  ├─ Commit A: Add PaymentService
       │  ├─ Commit B: Refactor OrderProcessor
       │  └─ (HEAD)
       │
       └─ (BASE = main)

git diff main...HEAD  → [PaymentService, OrderProcessor]
```

#### Scenario 2: Pull Request
```
main ──┬─ PR #123
       │  │
       │  ├─ Commit 1: UserServiceTest fix
       │  ├─ Commit 2: Add validation
       │  └─ (HEAD)
       │
       └─ (BASE = main from before PR)

git diff main...HEAD  → [UserService, ValidationUtil]
```

#### Scenario 3: Multiple Commits
```
main ──┬─ branch
       │  ├─ Touch UserService (5 times)
       │  ├─ Touch OrderService (2 times)
       │  └─ (HEAD)
       │
git diff main...HEAD  → [UserService, OrderService]
(merged = only unique classes, not change count)
```

### Pros
✓ Accurate (uses version control source of truth)
✓ Works with any git workflow
✓ Branch-aware
✓ Handles merges correctly
✓ No state file needed

### Cons
✗ Requires git repository
✗ Slower than hash-based
✗ Depends on correct BASE branch

### When to Use
- CI/CD (recommended)
- Pull request testing
- Branch-based workflows
- Multi-feature projects

### Implementation Details
```java
ProcessBuilder pb = new ProcessBuilder("git", "diff", baseCommit + "...HEAD", 
                                      "--name-only");
List<String> changedFiles = readProcessOutput(pb);

// Map files to classes
Set<String> changedClasses = changedFiles.stream()
    .filter(f -> f.endsWith(".java"))
    .map(f -> filePathToClassName(f))
    .collect(toSet());

return changedClasses;
```

---

## Strategy 3: Uncommitted (uncommitted)

### Overview
Detects files with unstaged changes in git (perfect for rapid iteration).

### How It Works
```
$ git diff --name-only
M src/main/java/Service.java    (modified, not staged)
M src/test/java/ServiceTest.java

→ Changed classes: {Service, ServiceTest}
```

### Configuration
```bash
mvn test-order:combined test -Dtestorder.change-mode=uncommitted
```

### Workflow Integration
```
1. Edit Service.java
2. Run: mvn test-order:combined test
   - Detects change
   - Runs tests for Service
   - Reports pass/fail
3. If fail:
   - Fix Service.java
   - Run again (immediate feedback)
4. git add . && git commit
   - Move to next change
```

### Pros
✓ Fastest iteration (catch errors immediately)
✓ Only includes YOUR current work
✓ Perfect for TDD workflow
✓ No commit needed

### Cons
✗ Requires git repository
✗ Lost if files not tracked
✗ Only works with unstaged changes

### When to Use
- Local TDD workflow
- Rapid prototyping
- "Test as you code" style

---

## Strategy 4: Explicit (explicit)

### Overview
User provides exact list of changed classes (manual specification).

### How It Works
```
mvn test-order:combined test \
  -Dtestorder.change-mode=explicit \
  -Dtestorder.changed-classes=com.example.PaymentService,com.example.OrderProcessor

→ Tests for {PaymentService, OrderProcessor} run
```

### Configuration
```bash
# Single class
-Dtestorder.changed-classes=com.example.Service

# Multiple classes (comma-separated, no spaces)
-Dtestorder.changed-classes=com.example.Service,com.example.Util

# Via pom.xml property
<properties>
    <testorder.changed-classes>com.example.Service</testorder.changed-classes>
</properties>
```

### CI Pipeline Example
```yaml
# .github/workflows/test.yml
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      
      - name: Detect changes
        id: changed-files
        uses: tj-actions/changed-files@v35
        
      - name: Run selective tests
        run: |
          CLASSES=$(echo "${{ steps.changed-files.outputs.all_changed_files }}" \
            | grep '\.java$' \
            | sed 's|src/main/java/||' \
            | sed 's|\.java||' \
            | sed 's|/|.|g' \
            | paste -sd, -)
          
          mvn test-order:combined test \
            -Dtestorder.change-mode=explicit \
            -Dtestorder.changed-classes=$CLASSES
```

### Pros
✓ Perfectly accurate (you know what changed)
✓ Works in any environment
✓ CI-friendly (explicit contracts)
✓ No git required
✓ Reproducible

### Cons
✗ Manual specification (error-prone)
✗ Requires parsing upstream tool output
✗ More configuration

### When to Use
- CI/CD with custom change detection
- Monorepos with custom tooling
- Explicit change tracking systems
- Testing specific changes

---

## Strategy 5: Auto (auto)

### Overview
Automatically selects best strategy based on environment.

### Algorithm
```
if (isPullRequestEnvironment()) {
    // GitHub Actions, GitLab CI, etc.
    return GitChangeDetector(baseCommit="main");
} else if (isGitRepository()) {
    if (hasUncommittedChanges()) {
        return UncommittedChangeDetector();
    } else {
        return GitChangeDetector();
    }
} else {
    return HashBasedChangeDetector(); // Fallback
}
```

### Environment Detection
```
CI Systems Detected:
  ✓ GitHub Actions (GITHUB_ACTIONS=true)
  ✓ GitLab CI (GITLAB_CI=true)
  ✓ Jenkins (JENKINS_HOME)
  ✓ Travis CI (TRAVIS=true)
  ✓ CircleCI (CIRCLECI=true)
  
Git Detection:
  ✓ `.git` directory exists
  ✓ `git rev-parse --git-dir` succeeds
```

### Configuration
```bash
# Default (auto-detect)
mvn test-order:combined test

# With custom fallback
mvn test-order:combined test \
  -Dtestorder.change-mode=auto \
  -Dtestorder.git-fallback=since-last-run
```

### Pros
✓ Works in all environments
✓ Optimal strategy selection
✓ Zero configuration needed
✓ Smart fallbacks

### Cons
✗ Implicit behavior (harder to debug)
✗ Different runs may use different strategies

### When to Use
- Recommended for all projects
- Default mode (best balance)
- Hybrid local/CI workflows

---

## Comparison Examples

### Example 1: Local Development
```
Developer edits Service.java
Runs: mvn test-order:combined test

Auto detection:
  ✓ Git repository detected
  ✓ Uncommitted changes found
  → Uses UncommittedChangeDetector
  → Only ServiceTest runs (fast feedback)
```

### Example 2: Pull Request (CI)
```
GitHub Actions triggers on PR
Runs: mvn test-order:combined test

Auto detection:
  ✓ GitHub Actions detected (GITHUB_ACTIONS=true)
  ✓ PR environment (GITHUB_EVENT_NAME=pull_request)
  → Uses GitChangeDetector (main...HEAD)
  → Tests changed code + dependents
```

### Example 3: Release Build
```
Jenkins triggers release build
Explicit specification:
  mvn test-order:combined test \
    -Dtestorder.change-mode=explicit \
    -Dtestorder.changed-classes=com.example.ReleaseService

→ Only ReleaseService tests run
→ Validates release-critical path
```

### Example 4: Local No-Git
```
Developer in non-git environment
Runs: mvn test-order:combined test

Auto detection:
  ✗ No .git directory
  ✗ Git command fails
  → Falls back to HashBasedChangeDetector
  → Compares to last run hashes
  → Works, but slow (needs full file scan)
```

---

## Troubleshooting

### Git Detection Issues

**Problem**: "No git repository found"
```
Solution:
  1. Ensure .git directory exists
  2. Use explicit mode with -Dtestorder.changed-classes=...
  3. Or use hash-based with -Dtestorder.change-mode=since-last-run
```

**Problem**: "Base commit not found"
```
Solution:
  1. Check git-base-commit parameter
  2. Verify main branch exists
  3. Use explicit mode for custom scenarios
```

### Performance Issues

**Problem**: "Hash detection is slow"
```
Reason: Full file scan on every run
Solution:
  1. Use git-based detection (faster)
  2. Or use uncommitted mode
  3. Or cache hashes aggressively
```

**Problem**: "Git diff takes too long"
```
Reason: Large diff between branches
Solution:
  1. Rebase frequently
  2. Use shallow clones in CI
  3. Or use explicit mode with filtered list
```

---

## Best Practices

### For Development
```
change-mode: auto
→ Local: fast hash/uncommitted
→ CI: accurate git-based
```

### For CI/CD
```
change-mode: explicit (most reliable)
OR
change-mode: since-last-commit (if using git)
```

### For Large Projects
```
change-mode: since-last-commit
→ Git is source of truth
→ Fast enough for CI
```

### For Multi-Module
```
change-mode: auto (works well)
→ Auto handles module dependencies
→ Each module gets correct changes
```

---

## Summary

test-order provides **5 flexible change detection strategies**:
1. **Hash**: Fast, local-focused, no git needed
2. **Git**: Accurate, branch-aware, CI-friendly
3. **Uncommitted**: Rapid iteration, perfect for TDD
4. **Explicit**: Perfect control, CI-friendly, manual
5. **Auto**: Smart defaults, recommended for most

Choose based on your workflow, or use **Auto** for best results.
