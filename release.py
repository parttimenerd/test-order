#!/usr/bin/env python3
"""
Bump test-order version and prepare a release.

This script:
1. Reads current version from root pom.xml
2. Bumps major/minor/patch version (release version, without -SNAPSHOT)
3. Rolls CHANGELOG.md Unreleased into a versioned entry
4. Updates version references in root/module/sample POMs and README.md
5. Runs checks/builds
6. Optionally deploys artifacts
7. Commits, tags, and optionally pushes
8. Optionally creates a GitHub release

It also supports --github-release-only to create a release for the current
version without editing files.
"""

from __future__ import annotations

import argparse
from datetime import date
import re
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Tuple


class ReleaseManager:
    EMPTY_CHANGELOG_HEADINGS = {
        "### Added",
        "### Changed",
        "### Deprecated",
        "### Removed",
        "### Fixed",
        "### Security",
    }

    def __init__(self, project_root: Path):
        self.project_root = project_root
        self.root_pom = project_root / "pom.xml"
        self.readme = project_root / "README.md"
        self.changelog = project_root / "CHANGELOG.md"

        self.module_poms = self._discover_module_poms()
        self.sample_poms = self._discover_sample_poms()
        self._release_files: List[Path] = [
            self.root_pom,
            self.readme,
            self.changelog,
            *self.module_poms,
            *self.sample_poms,
        ]

    def _discover_module_poms(self) -> List[Path]:
        content = self.root_pom.read_text(encoding="utf-8")
        modules = re.findall(r"<module>([^<]+)</module>", content)
        poms: List[Path] = []
        for module in modules:
            pom = self.project_root / module / "pom.xml"
            if pom.exists():
                poms.append(pom)
        return poms

    def _discover_sample_poms(self) -> List[Path]:
        """Find sample and fixture POMs that reference test-order artifacts."""
        poms: List[Path] = []
        for search_dir in (self.project_root / "samples", self.project_root / "test-fixtures"):
            if search_dir.is_dir():
                for pom in search_dir.rglob("pom.xml"):
                    content = pom.read_text(encoding="utf-8")
                    if "me.bechberger" in content:
                        poms.append(pom)
        return poms

    def get_current_version(self) -> str:
        content = self.root_pom.read_text(encoding="utf-8")
        match = re.search(r"<version>([^<]+)</version>", content)
        if not match:
            raise ValueError("Could not find version in root pom.xml")
        return match.group(1)

    @staticmethod
    def to_base_version(version: str) -> str:
        return version[:-9] if version.endswith("-SNAPSHOT") else version

    @staticmethod
    def parse_version(version: str) -> Tuple[int, int, int]:
        parts = version.split(".")
        if len(parts) == 2:
            return int(parts[0]), int(parts[1]), 0
        if len(parts) == 3:
            return int(parts[0]), int(parts[1]), int(parts[2])
        raise ValueError(f"Unsupported version format: {version}")

    def bump_version(self, current: str, bump: str) -> str:
        major, minor, patch = self.parse_version(self.to_base_version(current))
        if bump == "major":
            return f"{major + 1}.0.0"
        if bump == "minor":
            return f"{major}.{minor + 1}.0"
        if bump == "patch":
            return f"{major}.{minor}.{patch + 1}"
        raise ValueError(f"Unknown bump kind: {bump}")

    def update_root_pom_version(self, old: str, new: str) -> None:
        content = self.root_pom.read_text(encoding="utf-8")
        updated = content.replace(f"<version>{old}</version>", f"<version>{new}</version>", 1)
        self.root_pom.write_text(updated, encoding="utf-8")

    def update_module_parent_versions(self, old: str, new: str) -> None:
        pattern = (
            r"(<parent>\s*"
            r"<groupId>me\.bechberger</groupId>\s*"
            r"<artifactId>test-order-parent</artifactId>\s*"
            r"<version>)" + re.escape(old) + r"(</version>)"
        )
        for pom in self.module_poms:
            content = pom.read_text(encoding="utf-8")
            updated = re.sub(pattern, r"\g<1>" + new + r"\g<2>", content, flags=re.DOTALL)
            pom.write_text(updated, encoding="utf-8")

    def update_readme_versions(self, old: str, new: str) -> None:
        if not self.readme.exists():
            return
        content = self.readme.read_text(encoding="utf-8")
        content = content.replace(f"<version>{old}</version>", f"<version>{new}</version>")
        base_old = self.to_base_version(old)
        if base_old != old:
            content = content.replace(f"<version>{base_old}</version>", f"<version>{new}</version>")
        self.readme.write_text(content, encoding="utf-8")

    def update_sample_versions(self, old: str, new: str) -> None:
        """Update test-order version references in sample/fixture POMs (scoped to me.bechberger)."""
        base_old = self.to_base_version(old)
        versions_to_replace = [old]
        if base_old != old:
            versions_to_replace.append(base_old)
        for pom in self.sample_poms:
            content = pom.read_text(encoding="utf-8")
            for ver in versions_to_replace:
                # Replace version in dependency/plugin blocks with me.bechberger groupId
                pattern = (
                    r"(<groupId>me\.bechberger</groupId>\s*"
                    r"<artifactId>[^<]+</artifactId>\s*"
                    r"<version>)" + re.escape(ver) + r"(</version>)"
                )
                content = re.sub(pattern, r"\g<1>" + new + r"\g<2>", content, flags=re.DOTALL)
                # Also replace parent version referencing test-order-parent
                parent_pattern = (
                    r"(<parent>\s*"
                    r"<groupId>me\.bechberger</groupId>\s*"
                    r"<artifactId>test-order-parent</artifactId>\s*"
                    r"<version>)" + re.escape(ver) + r"(</version>)"
                )
                content = re.sub(parent_pattern, r"\g<1>" + new + r"\g<2>", content, flags=re.DOTALL)
            pom.write_text(content, encoding="utf-8")

    def update_changelog_for_release(self, new_version: str) -> None:
        if not self.changelog.exists():
            raise FileNotFoundError("CHANGELOG.md not found")

        content = self.changelog.read_text(encoding="utf-8")

        if "## [Unreleased]" not in content:
            raise ValueError("CHANGELOG.md must contain a '## [Unreleased]' section")

        lines = content.splitlines()
        unreleased_idx = next((i for i, line in enumerate(lines) if line.strip() == "## [Unreleased]"), None)
        if unreleased_idx is None:
            raise ValueError("CHANGELOG.md must contain a '## [Unreleased]' section")

        next_section_idx = len(lines)
        for idx in range(unreleased_idx + 1, len(lines)):
            if lines[idx].startswith("## "):
                next_section_idx = idx
                break

        unreleased_body = "\n".join(lines[unreleased_idx + 1:next_section_idx]).strip()
        if self._is_unreleased_body_empty(unreleased_body):
            raise ValueError("CHANGELOG.md Unreleased section is empty; add release notes before releasing")

        release_header = f"## [{new_version}] - {date.today().isoformat()}"
        unreleased_template = "\n".join([
            "### Added",
            "### Changed",
            "### Deprecated",
            "### Removed",
            "### Fixed",
            "### Security",
            "",
        ])

        replacement = f"## [Unreleased]\n\n{unreleased_template}\n{release_header}"
        updated = content.replace("## [Unreleased]", replacement, 1)
        updated = self._update_changelog_compare_links(updated, new_version)
        self.changelog.write_text(updated, encoding="utf-8")

    @staticmethod
    def _update_changelog_compare_links(content: str, new_version: str) -> str:
        unreleased_link_re = re.compile(
            r"^\[Unreleased\]:\s+(?P<url>.+)/compare/v(?P<old>[\d.]+)\.\.\.HEAD\s*$",
            re.MULTILINE,
        )
        match = unreleased_link_re.search(content)
        if not match:
            return content

        base_url = match.group("url")
        old_version = match.group("old")
        new_links = (
            f"[Unreleased]: {base_url}/compare/v{new_version}...HEAD\n"
            f"[{new_version}]: {base_url}/compare/v{old_version}...v{new_version}"
        )
        return unreleased_link_re.sub(new_links, content, count=1)

    def preview_changelog_release(self, new_version: str) -> str:
        if not self.changelog.exists():
            return "- CHANGELOG.md not found"

        lines = self.changelog.read_text(encoding="utf-8").splitlines()
        unreleased_idx = next((i for i, line in enumerate(lines) if line.strip() == "## [Unreleased]"), None)
        if unreleased_idx is None:
            return "- CHANGELOG.md missing '## [Unreleased]' section"

        next_section_idx = len(lines)
        for idx in range(unreleased_idx + 1, len(lines)):
            if lines[idx].startswith("## "):
                next_section_idx = idx
                break

        body = "\n".join(lines[unreleased_idx + 1:next_section_idx]).strip()
        if self._is_unreleased_body_empty(body):
            return "- CHANGELOG.md Unreleased is empty"

        return (
            f"- CHANGELOG.md: create ## [{new_version}] - {date.today().isoformat()} from Unreleased notes\n"
            "- CHANGELOG.md: reset Unreleased headings"
        )

    def _is_unreleased_body_empty(self, body: str) -> bool:
        if not body:
            return True

        meaningful = []
        for line in body.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            if stripped in self.EMPTY_CHANGELOG_HEADINGS:
                continue
            meaningful.append(stripped)
        return len(meaningful) == 0

    def snapshot_files(self) -> Dict[Path, str]:
        snapshots: Dict[Path, str] = {}
        for file in self._release_files:
            if file.exists():
                snapshots[file] = file.read_text(encoding="utf-8")
        return snapshots

    @staticmethod
    def restore_snapshots(snapshots: Dict[Path, str]) -> None:
        for path, content in snapshots.items():
            path.write_text(content, encoding="utf-8")

    def run_command(self, cmd: List[str], desc: str) -> None:
        print(f"\n-> {desc}")
        print("   $ " + " ".join(cmd))
        result = subprocess.run(cmd, cwd=self.project_root)
        if result.returncode != 0:
            raise RuntimeError(f"Command failed: {' '.join(cmd)}")

    def run_checks(self, include_its: bool) -> None:
        self.run_command(["mvn", "test"], "Running unit tests")
        if include_its:
            self.run_command(
                ["mvn", "verify", "-pl", "test-order-maven-plugin",
                 "-Dtestorder.it=true"],
                "Running integration tests",
            )
        self.run_command(["mvn", "-P", "release", "package", "-DskipTests"], "Building release artifacts")

    def deploy(self) -> None:
        self.run_command(["mvn", "clean", "deploy", "-P", "release"], "Deploying release")

    def deploy_snapshot(self) -> None:
        self.run_command(["mvn", "clean", "deploy", "-DskipTests"], "Deploying SNAPSHOT")

    def update_gradle_plugin_version(self, old: str, new: str) -> None:
        """Update version in the Gradle plugin build file and init script."""
        gradle_build = self.project_root / "test-order-gradle-plugin" / "build.gradle.kts"
        if gradle_build.exists():
            content = gradle_build.read_text(encoding="utf-8")
            updated = re.sub(
                r'(version\s*=\s*")' + re.escape(old) + r'(")',
                r'\g<1>' + new + r'\g<2>',
                content,
            )
            gradle_build.write_text(updated, encoding="utf-8")

        init_gradle = self.project_root / "test-order-gradle-plugin" / "test-order-init.gradle"
        if init_gradle.exists():
            content = init_gradle.read_text(encoding="utf-8")
            updated = content.replace(
                f"test-order-gradle-plugin:{old}",
                f"test-order-gradle-plugin:{new}",
            )
            init_gradle.write_text(updated, encoding="utf-8")

    def _gradle_plugin_files(self) -> List[str]:
        """Return relative paths of Gradle plugin files managed by release."""
        files: List[str] = []
        for name in ("build.gradle.kts", "test-order-init.gradle"):
            path = self.project_root / "test-order-gradle-plugin" / name
            if path.exists():
                files.append(str(path.relative_to(self.project_root)))
        return files

    def git_commit_tag(self, version: str) -> None:
        files = [
            "pom.xml",
            "README.md",
            "CHANGELOG.md",
            *[str(p.relative_to(self.project_root)) for p in self.module_poms],
            *[str(p.relative_to(self.project_root)) for p in self.sample_poms],
            *self._gradle_plugin_files(),
        ]
        self.run_command(["git", "add", *files], "Staging release files")
        self.run_command(["git", "commit", "-m", f"Release {version}"], "Creating release commit")
        self.run_command(["git", "tag", "-a", f"v{version}", "-m", f"Release {version}"], "Tagging release")

    def bump_to_next_snapshot(self, release_version: str) -> None:
        """Bump version to next SNAPSHOT after release."""
        major, minor, patch = self.parse_version(release_version)
        next_snapshot = f"{major}.{minor}.{patch + 1}-SNAPSHOT"
        print(f"\n-> Bumping to next development version: {next_snapshot}")
        self.update_root_pom_version(release_version, next_snapshot)
        self.update_module_parent_versions(release_version, next_snapshot)
        self.update_sample_versions(release_version, next_snapshot)
        self.update_gradle_plugin_version(release_version, next_snapshot)
        files = [
            "pom.xml",
            *[str(p.relative_to(self.project_root)) for p in self.module_poms],
            *[str(p.relative_to(self.project_root)) for p in self.sample_poms],
            *self._gradle_plugin_files(),
        ]
        self.run_command(["git", "add", *files], "Staging SNAPSHOT bump")
        self.run_command(["git", "commit", "-m", f"Prepare next development iteration {next_snapshot}"],
                        "Committing SNAPSHOT bump")

    def git_push(self) -> None:
        self.run_command(["git", "push"], "Pushing commits")
        self.run_command(["git", "push", "--tags"], "Pushing tags")

    def cleanup_git_release_state(self, version: str) -> None:
        tag = f"v{version}"
        print("\n-> Cleaning up local git release state")
        subprocess.run(["git", "tag", "-d", tag], cwd=self.project_root,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # Undo up to 2 commits: snapshot bump + release commit
        for expected_msg in (f"Prepare next development iteration", f"Release {version}"):
            head = subprocess.run(
                ["git", "log", "-1", "--pretty=%s"],
                cwd=self.project_root,
                capture_output=True,
                text=True,
            )
            if head.returncode == 0 and head.stdout.strip().startswith(expected_msg):
                subprocess.run(
                    ["git", "reset", "--mixed", "HEAD~1"],
                    cwd=self.project_root,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )

    def get_version_changelog_entry(self, version: str) -> str:
        if not self.changelog.exists():
            return ""

        content = self.changelog.read_text(encoding="utf-8")
        match = re.search(
            rf"## \[{re.escape(version)}\][^\n]*\n(.*?)(?=\n## \[|$)",
            content,
            re.DOTALL,
        )
        if match:
            return match.group(1).strip()
        return ""

    def create_github_release(self, version: str) -> None:
        tag = f"v{version}"
        changelog_entry = self.get_version_changelog_entry(version)
        if not changelog_entry:
            changelog_entry = (
                f"Release {version}\n\n"
                "See CHANGELOG.md for details."
            )

        release_notes = f"""{changelog_entry}

## Maven

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>{version}</version>
  <executions>
    <execution>
      <goals>
        <goal>prepare</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```
"""

        notes_file = self.project_root / ".release-notes.md"
        notes_file.write_text(release_notes, encoding="utf-8")

        try:
            create_cmd = [
                "gh", "release", "create", tag,
                "--title", f"Release {version}",
                "--notes-file", str(notes_file),
            ]
            self.run_command(create_cmd, "Creating GitHub release")
        except Exception as exc:
            print(f"Warning: Failed to create GitHub release: {exc}")
            print("You can create it manually via GitHub Releases.")
        finally:
            if notes_file.exists():
                notes_file.unlink()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Release test-order")
    parser.add_argument("--major", action="store_true", help="Bump major version")
    parser.add_argument("--minor", action="store_true", help="Bump minor version (default)")
    parser.add_argument("--patch", action="store_true", help="Bump patch version")
    parser.add_argument("--no-its", action="store_true", help="Skip integration tests")
    parser.add_argument("--no-push", action="store_true", help="Skip git push")
    parser.add_argument("--no-github-release", action="store_true", help="Skip GitHub release creation")
    parser.add_argument("--no-deploy", action="store_true", help="Skip deploy")
    parser.add_argument("--github-release-only", action="store_true",
                        help="Create GitHub release for current version only")
    parser.add_argument("--snapshot", action="store_true",
                        help="Deploy current SNAPSHOT version without bumping or releasing")
    parser.add_argument("--dry-run", action="store_true", help="Show planned changes only")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    root = Path(__file__).resolve().parent
    manager = ReleaseManager(root)

    current = manager.get_current_version()
    current_base = manager.to_base_version(current)

    if args.github_release_only:
        print(f"Creating GitHub release for current version: {current_base}")
        manager.create_github_release(current_base)
        print(f"GitHub release created for version {current_base}")
        return

    if args.snapshot:
        if not current.endswith("-SNAPSHOT"):
            print(f"Error: current version '{current}' is not a SNAPSHOT version.")
            sys.exit(1)
        print(f"Deploying SNAPSHOT: {current}")
        manager.deploy_snapshot()
        print(f"\nSNAPSHOT {current} deployed successfully.")
        return

    bump = "minor"
    if args.major:
        bump = "major"
    elif args.patch:
        bump = "patch"

    new = manager.bump_version(current, bump)

    print(f"Current version: {current}")
    print(f"Next version:    {new}")

    if args.dry_run:
        print("\nDry run only. Files that would be updated:")
        print("- pom.xml")
        for pom in manager.module_poms:
            print(f"- {pom.relative_to(root)}")
        print("- README.md")
        print("- CHANGELOG.md")
        print(manager.preview_changelog_release(new))
        return

    snapshots = manager.snapshot_files()
    created_git_release_state = False
    try:
        manager.update_changelog_for_release(new)
        manager.update_root_pom_version(current, new)
        manager.update_module_parent_versions(current, new)
        manager.update_sample_versions(current, new)
        manager.update_gradle_plugin_version(current, new)
        manager.update_readme_versions(current, new)

        manager.run_checks(include_its=not args.no_its)

        manager.git_commit_tag(new)
        created_git_release_state = True

        if not args.no_deploy:
            manager.deploy()

        manager.bump_to_next_snapshot(new)

        if not args.no_push:
            manager.git_push()
        if not args.no_github_release:
            manager.create_github_release(new)

        print("\nRelease completed successfully.")
        print(f"Version: {new}")
    except Exception as exc:
        manager.restore_snapshots(snapshots)
        if created_git_release_state:
            manager.cleanup_git_release_state(new)
        print(f"\nRelease failed: {exc}")
        print("Local release edits were reverted automatically.")
        sys.exit(1)


if __name__ == "__main__":
    main()
