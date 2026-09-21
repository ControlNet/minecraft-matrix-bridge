"""Offline tests using synthetic platform catalogs, not live compatibility evidence."""

import json
import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release_metadata as metadata
import system_test_all


class ReleaseMetadataTests(unittest.TestCase):
    def test_family_includes_base_and_all_patches_only(self):
        catalog = [
            {"id": value, "type": "release"}
            for value in ["26.1.10", "26.1.2", "26.1", "26.1.1", "26.2", "26.10"]
        ] + [{"id": "26.1-snapshot-1", "type": "snapshot"}]
        self.assertEqual(metadata.expand_family("26.1", catalog),
                         ["26.1", "26.1.1", "26.1.2", "26.1.10"])

    def test_120_includes_5_and_6(self):
        catalog = [{"id": f"1.20.{patch}", "type": "release"} for patch in range(1, 7)]
        catalog += [{"id": "1.20", "type": "release"}]
        self.assertEqual(len(metadata.expand_family("1.20", catalog)), 7)

    def test_no_matching_versions_fails(self):
        with self.assertRaisesRegex(ValueError, "No stable"):
            metadata.expand_family("26.2", [])

    def test_invalid_family_fails(self):
        for family in ["26", "26.1.x", "26.1.2", "../26.1"]:
            with self.subTest(family=family), self.assertRaises(ValueError):
                metadata.expand_family(family, [])

    def test_modrinth_requires_every_stable_version(self):
        with self.assertRaisesRegex(ValueError, "26.1.1"):
            metadata.validate_modrinth(["26.1", "26.1.1"],
                                      [{"version": "26.1", "version_type": "release"}])

    def test_modrinth_rejects_nonrelease(self):
        with self.assertRaises(ValueError):
            metadata.validate_modrinth(["26.1"],
                                      [{"version": "26.1", "version_type": "snapshot"}])

    def cf_catalog(self):
        # Synthetic IDs intentionally exercise namespace disambiguation.
        types = [{"id": 1, "name": "Minecraft 26.1"}, {"id": 2, "name": "Bukkit"}]
        versions = [
            {"id": 11, "name": "26.1", "gameVersionTypeID": 1},
            {"id": 12, "name": "26.1.1", "gameVersionTypeID": 1},
            {"id": 13, "name": "26.1", "gameVersionTypeID": 2},
            {"id": 14, "name": "Java 25", "gameVersionTypeID": 3},
            {"id": 15, "name": "Forge", "gameVersionTypeID": 4},
            {"id": 16, "name": "NeoForge", "gameVersionTypeID": 4},
            {"id": 17, "name": "Server", "gameVersionTypeID": 5},
            {"id": 18, "name": "Client", "gameVersionTypeID": 5},
        ]
        return versions, types

    def test_curseforge_exact_namespaced_mapping(self):
        versions, types = self.cf_catalog()
        result = metadata.resolve_curseforge("26.1", ["26.1", "26.1.1"],
                                            "neoforge", 25, versions, types)
        self.assertEqual(result, [11, 12, 14, 16, 17])

    def test_curseforge_new_release_uses_existing_minecraft_namespace(self):
        versions, types = self.cf_catalog()
        versions.extend([
            {"id": 19, "name": "26.2", "gameVersionTypeID": 1},
            {"id": 20, "name": "26.2", "gameVersionTypeID": 2},
        ])
        result = metadata.resolve_curseforge("26.2", ["26.2"],
                                            "neoforge", 25, versions, types)
        self.assertEqual(result, [19, 14, 16, 17])

    def test_curseforge_prefers_version_named_type_for_new_release_scheme(self):
        versions, types = self.cf_catalog()
        types.append({"id": 99, "name": "26.2"})
        versions.extend([
            {"id": 19, "name": "26.2", "gameVersionTypeID": 99},
            {"id": 20, "name": "26.2", "gameVersionTypeID": 1},
        ])
        result = metadata.resolve_curseforge("26.2", ["26.2"],
                                            "neoforge", 25, versions, types)
        self.assertEqual(result, [19, 14, 16, 17])

    def test_curseforge_unique_new_version_falls_back_across_unclassified_types(self):
        versions, types = self.cf_catalog()
        types.append({"id": 99, "name": "Minecraft current"})
        versions.append({"id": 19, "name": "26.2", "gameVersionTypeID": 99})
        result = metadata.resolve_curseforge("26.2", ["26.2"],
                                            "forge", 25, versions, types)
        self.assertEqual(result, [19, 14, 15, 17])

    def test_curseforge_unclassified_fallback_rejects_ambiguous_names(self):
        versions, types = self.cf_catalog()
        types.append({"id": 99, "name": "Minecraft current"})
        versions.extend([
            {"id": 19, "name": "26.2", "gameVersionTypeID": 99},
            {"id": 20, "name": "26.2", "gameVersionTypeID": 2},
        ])
        with self.assertRaisesRegex(ValueError, "Minecraft 26.2") as error:
            metadata.resolve_curseforge("26.2", ["26.2"],
                                        "forge", 25, versions, types)
        self.assertIn("Minecraft current", str(error.exception))
        self.assertIn("Bukkit", str(error.exception))

    def test_missing_curseforge_version_fails(self):
        versions, types = self.cf_catalog()
        with self.assertRaisesRegex(ValueError, "26.1.2"):
            metadata.resolve_curseforge("26.1", ["26.1.2"], "forge", 25, versions, types)

    def test_ambiguous_curseforge_version_fails(self):
        versions, types = self.cf_catalog()
        versions.append({"id": 99, "name": "26.1", "gameVersionTypeID": 1})
        with self.assertRaisesRegex(ValueError, "exactly one"):
            metadata.resolve_curseforge("26.1", ["26.1"], "forge", 25, versions, types)

    def test_missing_loader_or_java_fails(self):
        versions, types = self.cf_catalog()
        for missing in ["Java 25", "Forge", "Server"]:
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, missing):
                metadata.resolve_curseforge("26.1", ["26.1"], "forge", 25,
                                            [v for v in versions if v["name"] != missing], types)

    def test_repository_targets_cover_nine_modules(self):
        root = Path(__file__).resolve().parents[1]
        targets = metadata.load_targets(root / "scripts/release-targets.json")
        self.assertEqual(len(targets), 9)
        self.assertEqual(sum(t["group"] == "modern" for t in targets), 4)
        for target in targets:
            self.assertTrue((root / target["module"] / "build.gradle").is_file())

    def test_one_resolved_list_for_both_platforms(self):
        versions, types = self.cf_catalog()
        targets = [{"module": "forge-26.1", "family": "26.1", "loader": "forge",
                    "java": 25, "group": "modern"}]
        result = metadata.resolve_targets(targets,
            [{"id": "26.1", "type": "release"}],
            [{"version": "26.1", "version_type": "release"}], (versions, types))
        row = result["modern"]["include"][0]
        self.assertEqual(json.loads(row["game_versions"]), ["26.1"])
        self.assertEqual(row["curseforge_ids"], "11,14,15,17")
        self.assertEqual(row["environment"], "server_only")
        self.assertEqual(row["jar_suffix"], "forge-26.1.x")

    def test_published_modrinth_metadata_matches_exactly(self):
        actual = {"game_versions": ["26.1.1", "26.1"], "loaders": ["forge"],
                  "version_number": "0.1.4", "version_type": "release",
                  "environment": "server_only",
                  "files": [{"filename": "minecraftmatrixbridge-forge-26.1.x-0.1.4.jar"}]}
        args = (["26.1", "26.1.1"], "forge", "0.1.4", "release",
                "minecraftmatrixbridge-forge-26.1.x-0.1.4.jar")
        metadata.verify_modrinth(actual, *args)
        for field, value in [("game_versions", ["26.1"]), ("loaders", ["neoforge"]),
                             ("version_type", "beta"), ("version_number", "0.1.3"),
                             ("files", []), ("environment", "client_and_server"),
                             ("environment", None)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                metadata.verify_modrinth({**actual, field: value}, *args)

    def test_modrinth_project_environment(self):
        metadata.validate_modrinth_project({"environment": ["server_only"]})
        metadata.validate_modrinth_project({"client_side": "unsupported", "server_side": "required"})
        for project in [{}, {"environment": []}, {"environment": ["unknown"]},
                        {"environment": ["server_only", "client_and_server"]},
                        {"client_side": "required", "server_side": "required"},
                        {"client_side": "unsupported", "server_side": "optional"}]:
            with self.subTest(project=project), self.assertRaisesRegex(ValueError, "environment"):
                metadata.validate_modrinth_project(project)

    def test_project_environment_drift_stops_preflight(self):
        with patch("sys.argv", ["release_metadata.py", "--check-modrinth",
                                "--modrinth-project", "mc-matrix-bridge"]), \
                patch.object(metadata, "fetch_json", return_value={"environment": ["client_and_server"]}) as fetch, \
                self.assertRaisesRegex(ValueError, "environment"):
            metadata.main()
        fetch.assert_called_once_with(f"{metadata.MODRINTH}/project/mc-matrix-bridge")

    def test_preflight_checks_configured_modrinth_project(self):
        # Synthetic catalogs keep this wiring test offline.
        versions = ["1.18", "1.19", "1.20", "1.21", "26.1", "26.2"]
        responses = [{"environment": ["server_only"]},
                     {"versions": [{"id": v, "type": "release"} for v in versions]},
                     [{"version": v, "version_type": "release"} for v in versions]]
        with patch.dict(os.environ, {"MODRINTH_PROJECT_ID": "l1ddICJf"}, clear=True), \
                patch("sys.argv", ["release_metadata.py", "--check-modrinth"]), \
                patch.object(metadata, "fetch_json", side_effect=responses) as fetch, \
                patch("builtins.print"):
            metadata.main()
        self.assertEqual(fetch.call_args_list[0].args, (f"{metadata.MODRINTH}/project/l1ddICJf",))

    def test_ambiguous_server_tag_fails(self):
        versions, types = self.cf_catalog()
        versions.append({"id": 99, "name": "Server", "gameVersionTypeID": 6})
        with self.assertRaisesRegex(ValueError, "Server"):
            metadata.resolve_curseforge("26.1", ["26.1"], "forge", 25, versions, types)

    def test_missing_upload_id_does_not_fall_back_to_resolving(self):
        with patch("sys.argv", ["release_metadata.py", "--verify-modrinth", ""]), \
                patch.object(metadata, "fetch_json") as fetch, self.assertRaisesRegex(ValueError, "version ID"):
            metadata.main()
        fetch.assert_not_called()

    def test_duplicate_targets_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "targets.json"
            target = {"module": "forge-26.1", "family": "26.1", "loader": "forge",
                      "java": 25, "group": "modern"}
            path.write_text(json.dumps([target, target]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                metadata.load_targets(path)

    def test_cli_writes_shared_matrix_and_summary(self):
        # Synthetic catalog contains one real-shaped base version per family.
        catalog = [{"id": family, "type": "release"}
                   for family in ["1.18", "1.19", "1.20", "1.21", "26.1", "26.2"]]
        with tempfile.TemporaryDirectory() as directory:
            output, summary, artifact = [Path(directory) / name for name in ["outputs", "summary", "resolved.json"]]
            with patch.dict(os.environ, {"GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary)}), \
                    patch("sys.argv", ["release_metadata.py", "--output", str(artifact)]), \
                    patch.object(metadata, "fetch_json", return_value={"versions": catalog}), \
                    patch("builtins.print"):
                metadata.main()
            resolved = json.loads(artifact.read_text())
            outputs = dict(line.split("=", 1) for line in output.read_text().splitlines())
            self.assertEqual(json.loads(outputs["modern"]), resolved["modern"])
            self.assertEqual(json.loads(outputs["legacy"]), resolved["legacy"])
            self.assertIn("forge-26.1", summary.read_text())

    def test_workflow_consumes_both_shared_matrices(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/release.yml").read_text()
        for group in ["legacy", "modern"]:
            self.assertEqual(workflow.count(f"matrix: ${{{{ fromJSON(needs.release-metadata.outputs.{group}) }}}}"), 2)
        self.assertNotIn("CURSEFORGE_GAME_VERSIONS_", workflow)
        self.assertEqual(workflow.count("game_versions: ${{ matrix.curseforge_ids }}"), 2)
        self.assertEqual(workflow.count("environment: ${{ matrix.environment }}"), 2)

    def test_packaged_coordinate_is_forwarded(self):
        with patch("sys.argv", ["system_test_all.py", "--packaged", "--modules", "forge-26.1",
                                "--loader-coordinate", "26.1-62.0.9"]), \
                patch.object(system_test_all, "run_one") as run, patch("builtins.print"):
            self.assertEqual(system_test_all.main(), 0)
        run.assert_called_once_with("forge-26.1", 600, packaged=True, loader_coordinate="26.1-62.0.9",
                                    artifact_dir=None)

    def test_packaged_coordinate_rejects_paths_or_multiple_modules(self):
        for args in [
            ["--packaged", "--modules", "forge-26.1", "--loader-coordinate", "../../outside"],
            ["--packaged", "--modules", "forge-26.1", "neoforge-26.1", "--loader-coordinate", "26.1-62.0.9"],
            ["--modules", "forge-26.1", "--loader-coordinate", "26.1-62.0.9"],
        ]:
            with self.subTest(args=args), patch("sys.argv", ["system_test_all.py", *args]), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                system_test_all.main()
            self.assertEqual(error.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
