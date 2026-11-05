#!/usr/bin/env python3
"""Decide whether the current commit should be released, using SemVer precedence.

Inputs come from the environment (never from interpolated workflow expressions):
GITHUB_REF, DRY_RUN, MODRINTH_*/CURSEFORGE_* (presence only). Results are written to
GITHUB_OUTPUT when set, and always printed as JSON.

A release is resumable: when the version tag already exists *at HEAD*, the plan is
"resume" so a rerun finishes targets that a previous run did not publish. A tag on a
different commit means the version was already released, so nothing is published.
"""

import json
import os
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
RELEASE_REF = "refs/heads/master"
SEMVER = re.compile(
    r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
)


def parse_semver(version):
    match = SEMVER.fullmatch(version or "")
    if match is None:
        raise ValueError(f"Not a SemVer version: {version!r}")
    major, minor, patch, pre = match.groups()
    return int(major), int(minor), int(patch), tuple(pre.split(".")) if pre else ()


def _identifier_key(identifier):
    # Numeric identifiers sort before alphanumeric ones (SemVer 2.0.0 section 11.4).
    return (0, int(identifier), "") if identifier.isdigit() else (1, 0, identifier)


def precedence(version):
    major, minor, patch, pre = parse_semver(version)
    # A version without prerelease identifiers has higher precedence than one with them.
    return (major, minor, patch, 1 if not pre else 0, tuple(_identifier_key(i) for i in pre))


def channel_for(version):
    pre = ".".join(parse_semver(version)[3]).lower()
    if not pre:
        return "release"
    if "alpha" in pre:
        return "alpha"
    return "beta"


def latest_version(tags):
    versions = []
    for tag in tags:
        if tag.startswith("v"):
            try:
                versions.append((precedence(tag[1:]), tag[1:]))
            except ValueError:
                continue
    return max(versions)[1] if versions else None


def plan(version, tags, head, tag_commit, ref, dry_run=False):
    """Return the release decision for ``version`` at commit ``head``."""
    channel = channel_for(version)
    tag = f"v{version}"
    result = {
        "version": version,
        "tag": tag,
        "channel": channel,
        "prerelease": "true" if channel != "release" else "false",
        "make_latest": "true" if channel == "release" else "false",
        "resume": "false",
        "should_release": "false",
    }
    latest = latest_version(tags)

    if tag_commit:
        if tag_commit == head:
            decision, reason = True, f"{tag} already points at HEAD; resuming unfinished targets"
            result["resume"] = "true"
        else:
            decision, reason = False, f"{tag} was already released from {tag_commit[:12]}"
    elif latest is None:
        decision, reason = True, "no previous release tag"
    elif precedence(version) > precedence(latest):
        decision, reason = True, f"{version} is newer than {latest}"
    else:
        decision, reason = False, f"{version} is not newer than {latest}"

    if decision and ref != RELEASE_REF:
        decision, reason = False, f"releases only run from {RELEASE_REF}, not {ref or '<unknown>'}"
    if decision and dry_run:
        decision, reason = False, "dry run: building and testing without publishing"

    result["should_release"] = "true" if decision else "false"
    # Dry runs still build and test so a dispatch from any branch can validate a release.
    result["build"] = "true" if decision or dry_run else "false"
    result["reason"] = reason
    return result


def read_property(name, path=ROOT / "gradle.properties"):
    for line in path.read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == name:
            return value.strip()
    raise ValueError(f"{name} not found in {path}")


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def tag_commit(tag):
    try:
        return git("rev-list", "-n", "1", f"refs/tags/{tag}")
    except subprocess.CalledProcessError:
        return ""


def main():
    mod_id = read_property("mod_id")
    version = read_property("mod_version")
    tags = git("tag", "-l", "v*").splitlines()
    result = plan(version, tags, git("rev-parse", "HEAD"), tag_commit(f"v{version}"),
                  os.environ.get("GITHUB_REF", ""), os.environ.get("DRY_RUN", "false") == "true")
    result["mod_id"] = mod_id
    result["has_modrinth"] = "true" if os.environ.get("MODRINTH_TOKEN") and os.environ.get("MODRINTH_PROJECT_ID") else "false"
    result["has_curseforge"] = "true" if os.environ.get("CURSEFORGE_TOKEN") and os.environ.get("CURSEFORGE_PROJECT_ID") else "false"
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for key, value in result.items():
                if key != "reason":
                    output.write(f"{key}={value}\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(f"Release plan for `{result['tag']}`: publish={result['should_release']} "
                          f"build={result['build']} ({result['reason']})\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
