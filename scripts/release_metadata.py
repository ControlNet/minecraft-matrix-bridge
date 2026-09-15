#!/usr/bin/env python3
"""Resolve shared release metadata with standard-library, read-only API calls."""

import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
MOJANG = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
MODRINTH = "https://api.modrinth.com/v2"
CURSEFORGE = "https://minecraft.curseforge.com/api/game"
LOADERS = {"forge": "Forge", "neoforge": "NeoForge"}
# All artifacts in this project run on the server without a client installation.
ENVIRONMENT = "server_only"


def fetch_json(url, headers=None):
    request = Request(url, headers={"User-Agent": "ControlNet/minecraft-matrix-bridge release-metadata",
                                    **(headers or {})})
    for attempt in range(3):
        try:
            with urlopen(request, timeout=45) as response:
                return json.load(response)
        except HTTPError as error:
            if attempt == 2 or (error.code != 429 and error.code < 500):
                # Never print request headers, credentials, or server response bodies.
                raise ValueError(f"Metadata request failed: {url} (HTTP {error.code})") from None
        except URLError:
            if attempt == 2:
                raise ValueError(f"Metadata request failed: {url} (network error)") from None
        time.sleep(2 ** attempt)


def expand_family(family, catalog):
    if not re.fullmatch(r"\d+\.\d+", family):
        raise ValueError(f"Invalid Minecraft family: {family}")
    pattern = re.compile(re.escape(family) + r"(?:\.\d+)?")
    versions = {v["id"] for v in catalog
                if v["type"] == "release" and pattern.fullmatch(v["id"])}
    if not versions:
        raise ValueError(f"No stable Minecraft versions for {family}.x")
    return sorted(versions, key=lambda version: tuple(map(int, version.split("."))))


def validate_modrinth(versions, catalog):
    available = {v["version"] for v in catalog if v["version_type"] == "release"}
    missing = set(versions) - available
    if missing:
        raise ValueError(f"Modrinth is missing stable version tags: {', '.join(sorted(missing))}")


def validate_modrinth_project(project):
    if "environment" in project:
        valid = project["environment"] == [ENVIRONMENT]
    else:
        # Compatibility with older project responses; new version responses
        # must always expose an explicit environment for post-upload checks.
        valid = project.get("client_side") == "unsupported" and project.get("server_side") == "required"
    if not valid:
        raise ValueError("Modrinth project environment must be server_only; check the project's environment settings")


def resolve_curseforge(family, versions, loader, java, catalog, types):
    def unique_id(label, matches):
        ids = {row["id"] for row in matches}
        if len(ids) != 1:
            raise ValueError(f"CurseForge tag {label}: expected exactly one ID, found {len(ids)}")
        value = ids.pop()
        if type(value) is not int or value <= 0:
            raise ValueError(f"Invalid CurseForge ID for {label}")
        return value

    namespace = f"Minecraft {family}"
    type_id = unique_id(namespace, [row for row in types if row["name"] == namespace])
    ids = [unique_id(f"{namespace}:{version}", [row for row in catalog
           if row["name"] == version and row["gameVersionTypeID"] == type_id])
           for version in versions]
    for name in [f"Java {java}", LOADERS[loader], "Server"]:
        ids.append(unique_id(name, [row for row in catalog if row["name"] == name]))
    return ids


def load_targets(path):
    targets = json.loads(path.read_text(encoding="utf-8"))
    seen = set()
    if not isinstance(targets, list) or not targets:
        raise ValueError("Release targets must be a nonempty list")
    for target in targets:
        if (set(target) != {"module", "family", "loader", "java", "group"}
                or target["loader"] not in LOADERS
                or target["group"] not in {"legacy", "modern"}
                or type(target["java"]) is not int or target["java"] < 17
                or not re.fullmatch(r"\d+\.\d+", target["family"])
                or target["module"] != f"{target['loader']}-{target['family']}"):
            raise ValueError(f"Invalid release target: {target}")
        if target["module"] in seen:
            raise ValueError(f"Duplicate release target: {target['module']}")
        seen.add(target["module"])
    return targets


def resolve_targets(targets, minecraft, modrinth=None, curseforge=None):
    result = {group: {"include": []} for group in ["legacy", "modern"]}
    families = {family: expand_family(family, minecraft) for family in {t["family"] for t in targets}}
    for target in targets:
        versions = families[target["family"]]
        if modrinth is not None:
            validate_modrinth(versions, modrinth)
        ids = resolve_curseforge(target["family"], versions, target["loader"], target["java"],
                                 *curseforge) if curseforge is not None else []
        result[target["group"]]["include"].append({
            **target,
            "mc": target["family"] + ".x",
            "jar_suffix": target["module"] + ".x",
            "environment": ENVIRONMENT,
            "game_versions": json.dumps(versions, separators=(",", ":")),
            "curseforge_ids": ",".join(map(str, ids)),
        })
    return result


def validate_server_coverage(result, server_targets):
    for target in result["modern"]["include"]:
        tested = {row["minecraft"] for row in server_targets if row["module"] == target["module"]}
        required = set(json.loads(target["game_versions"]))
        if required != tested:
            raise ValueError(f"Server test coverage mismatch for {target['module']}: "
                             f"missing={sorted(required - tested)}, extra={sorted(tested - required)}")


def verify_modrinth(actual, expected_versions, loader, version, channel, filename):
    expected = {"game_versions": sorted(expected_versions), "loaders": [loader],
                "version_number": version, "version_type": channel, "environment": ENVIRONMENT}
    for key, value in expected.items():
        observed = actual.get(key)
        if key in {"game_versions", "loaders"} and isinstance(observed, list):
            observed = sorted(observed)
        if observed != value:
            raise ValueError(f"Published Modrinth {key} mismatch: expected {value}, got {observed}")
    if filename not in [file["filename"] for file in actual.get("files", [])]:
        raise ValueError(f"Published Modrinth file missing: {filename}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-targets", type=Path,
                        help="Require modern release tags to match the successful server-test matrix")
    parser.add_argument("--targets", type=Path, default=ROOT / "scripts/release-targets.json")
    parser.add_argument("--output", type=Path, help="Save resolved metadata JSON")
    parser.add_argument("--check-modrinth", action="store_true")
    parser.add_argument("--modrinth-project", default=os.environ.get("MODRINTH_PROJECT_ID") or "mc-matrix-bridge",
                        help="Project ID or slug for environment preflight (defaults to MODRINTH_PROJECT_ID or mc-matrix-bridge)")
    parser.add_argument("--check-curseforge", action="store_true",
                        help="Resolve all CurseForge IDs using CURSEFORGE_TOKEN from the environment")
    parser.add_argument("--verify-modrinth", metavar="VERSION_ID")
    args = parser.parse_args()
    if args.verify_modrinth is not None:
        if not re.fullmatch(r"[A-Za-z0-9]+", args.verify_modrinth):
            raise ValueError("Invalid Modrinth version ID")
        actual = fetch_json(f"{MODRINTH}/version/{args.verify_modrinth}")
        verify_modrinth(actual, json.loads(os.environ["EXPECTED_GAME_VERSIONS"]),
                        os.environ["EXPECTED_LOADER"], os.environ["EXPECTED_VERSION"],
                        os.environ["EXPECTED_CHANNEL"], os.environ["EXPECTED_FILENAME"])
        print(f"Verified Modrinth version {args.verify_modrinth}")
        return

    targets = load_targets(args.targets)
    if args.check_modrinth:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", args.modrinth_project):
            raise ValueError("Invalid Modrinth project ID or slug")
        validate_modrinth_project(fetch_json(f"{MODRINTH}/project/{args.modrinth_project}"))
    minecraft = fetch_json(MOJANG)["versions"]
    modrinth = fetch_json(f"{MODRINTH}/tag/game_version") if args.check_modrinth else None
    curseforge = None
    if args.check_curseforge:
        token = os.environ.get("CURSEFORGE_TOKEN")
        if not token:
            raise ValueError("CURSEFORGE_TOKEN is required for CurseForge preflight")
        headers = {"X-Api-Token": token}
        curseforge = (fetch_json(f"{CURSEFORGE}/versions", headers),
                      fetch_json(f"{CURSEFORGE}/version-types", headers))
    result = resolve_targets(targets, minecraft, modrinth, curseforge)
    if args.server_targets:
        validate_server_coverage(result, json.loads(args.server_targets.read_text(encoding="utf-8")))
    serialized = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(serialized, encoding="utf-8")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for group in ["legacy", "modern"]:
                output.write(f"{group}={json.dumps(result[group], separators=(',', ':'))}\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write("## Resolved release tags\n\n| Module | Minecraft | Java | Environment | CurseForge IDs |\n"
                          "| --- | --- | --- | --- | --- |\n")
            for matrix in result.values():
                for row in matrix["include"]:
                    summary.write(f"| {row['module']} | {', '.join(json.loads(row['game_versions']))} | "
                                  f"{row['java']} | {row['environment']} | {row['curseforge_ids'] or 'Not checked'} |\n")
    print(serialized, end="")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
