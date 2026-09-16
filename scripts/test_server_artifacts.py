"""Offline regression tests using synthetic artifact bytes, not Minecraft JARs."""
import hashlib
import json
import io
import queue
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import system_test_all
from system_test_all import verify_artifact, MODULE_EVENT_ALIASES
from release_metadata import validate_server_coverage


class ArtifactTests(unittest.TestCase):
    def test_installer_retries_a_transient_failure(self):
        command = ["java", "-jar", "synthetic-installer.jar"]
        failure = system_test_all.subprocess.CalledProcessError(1, command)
        success = system_test_all.subprocess.CompletedProcess(command, 0)
        log = self.directory / "installer.log"

        with patch.object(
            system_test_all.subprocess, "run", side_effect=[failure, success]
        ) as run, patch.object(system_test_all.time, "sleep") as sleep:
            system_test_all.run_installer_with_retries(
                command, self.directory, log
            )

        self.assertEqual(2, run.call_count)
        sleep.assert_called_once_with(1)
        self.assertIn("Installer attempt 1/3", log.read_text())
        self.assertIn("Installer attempt 2/3", log.read_text())

    def test_installer_preserves_the_final_failure(self):
        command = ["java", "-jar", "synthetic-installer.jar"]
        failure = system_test_all.subprocess.CalledProcessError(1, command)
        with patch.object(
            system_test_all.subprocess,
            "run",
            side_effect=[failure, failure, failure],
        ), patch.object(system_test_all.time, "sleep"):
            with self.assertRaises(system_test_all.subprocess.CalledProcessError):
                system_test_all.run_installer_with_retries(
                    command, self.directory, self.directory / "installer.log"
                )

    def test_installed_server_prefers_platform_argument_file(self):
        for platform, filename in (("posix", "unix_args.txt"), ("nt", "win_args.txt")):
            coordinate = "net/minecraftforge/forge/1.20.3-49.0.2"
            args = self.directory / "libraries" / coordinate / filename
            args.parent.mkdir(parents=True, exist_ok=True)
            args.write_text("synthetic argument file; not executable")
            (self.directory / "forge-1.20.3-49.0.2-shim.jar").write_bytes(b"synthetic shim")
            with patch.object(system_test_all.os, "name", platform):
                command = system_test_all.installed_server_command("java", self.directory, coordinate)
            self.assertEqual(["java", "-Djna.tmpdir=./jna", f"@{args}", "--nogui"], command)

    def test_forge_shim_is_used_when_arguments_are_absent(self):
        shim = self.directory / "forge-1.20.3-49.0.2-shim.jar"
        shim.write_bytes(b"synthetic shim; not a runnable JAR")
        command = system_test_all.installed_server_command(
            "java", self.directory, "net/minecraftforge/forge/1.20.3-49.0.2")
        self.assertEqual(["java", "-Djna.tmpdir=./jna", "-jar", str(shim), "--nogui"], command)

    def test_unrelated_shim_does_not_mask_missing_launcher(self):
        (self.directory / "forge-1.20.3-49.0.2-shim.jar").write_bytes(b"synthetic shim")
        for coordinate in ("net/minecraftforge/forge/1.20.4-49.0.3",
                           "net/neoforged/neoforge/21.1.250"):
            with self.assertRaises(FileNotFoundError):
                system_test_all.installed_server_command("java", self.directory, coordinate)

    def test_early_event_index_message_is_not_lost_by_bridge_readiness(self):
        probe = system_test_all.EventIndexProbe()
        output = queue.Queue()
        summary = "Event index built in 6 ms: scanned 2 jars, 100 classes, found 20 events\n"
        system_test_all._reader_thread(io.StringIO(summary), output, probe)
        output.get_nowait()  # Bridge readiness may consume the log before the event probe.
        system_test_all.wait_for_event_index_ready("forge-1.18", None, output, timeout_s=1, probe=probe)

    def test_empty_event_index_is_rejected(self):
        probe = system_test_all.EventIndexProbe()
        probe.record("Event index built in 5 ms: scanned 0 jars, 0 classes, found 0 events\n")

        with self.assertRaisesRegex(RuntimeError, "event index is empty"):
            system_test_all.wait_for_event_index_ready(
                "neoforge-1.21", None, queue.Queue(), timeout_s=1, probe=probe
            )

    def test_release_cannot_advertise_untested_patch(self):
        result = {"modern": {"include": [{"module": "forge-26.1", "game_versions": '["26.1","26.1.1"]'}]}}
        with self.assertRaisesRegex(ValueError, "missing=.*26.1.1"):
            validate_server_coverage(result, [{"module": "forge-26.1", "minecraft": "26.1"}])

    def test_release_coverage_requires_matching_loader_module(self):
        result = {"modern": {"include": [{"module": "forge-26.1", "game_versions": '["26.1"]'}]}}
        with self.assertRaises(ValueError):
            validate_server_coverage(result, [{"module": "neoforge-26.1", "minecraft": "26.1"}])
        validate_server_coverage(result, [{"module": "forge-26.1", "minecraft": "26.1"}])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.filename = "test-artifact.jar"
        self.jar = self.directory / self.filename
        self.jar.write_bytes(b"synthetic test artifact; not a runnable JAR")
        self.line = hashlib.sha256(self.jar.read_bytes()).hexdigest() + "  " + self.filename + "\n"
        self.manifest = self.directory / "SHA256SUMS.txt"
        self.manifest.write_text(self.line)

    def test_valid_artifact_is_used_without_rebuilding(self):
        self.assertEqual(self.jar, verify_artifact(self.directory, self.filename))

    def test_modified_artifact_is_rejected(self):
        self.jar.write_bytes(b"tampered synthetic artifact")
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            verify_artifact(self.directory, self.filename)

    def test_missing_manifest_is_rejected(self):
        self.manifest.unlink()
        with self.assertRaises(FileNotFoundError):
            verify_artifact(self.directory, self.filename)

    def test_missing_checksum_is_rejected(self):
        self.manifest.write_text("")
        with self.assertRaises(ValueError):
            verify_artifact(self.directory, self.filename)

    def test_duplicate_checksum_is_rejected(self):
        self.manifest.write_text(self.line * 2)
        with self.assertRaises(ValueError):
            verify_artifact(self.directory, self.filename)

    def test_missing_jar_is_rejected(self):
        self.jar.unlink()
        with self.assertRaises(FileNotFoundError):
            verify_artifact(self.directory, self.filename)

    def test_matrix_covers_all_modules_and_patch_families(self):
        root = Path(__file__).resolve().parent
        targets = json.loads((root / "server-test-targets.json").read_text())
        releases = json.loads((root / "release-targets.json").read_text())
        exclusions = json.loads((root / "server-test-exclusions.json").read_text())
        modules = {t["module"] for t in releases}
        self.assertEqual(modules, {t["module"] for t in targets})
        self.assertEqual(modules, set(MODULE_EVENT_ALIASES))
        keys = [(t["module"], t["minecraft"]) for t in targets]
        self.assertEqual(len(keys), len(set(keys)))
        counts = {"1.18": 2, "1.19": 4, "1.20": 6, "1.21": 11, "26.1": 2, "26.2": 0}
        result = {"all": {"include": []}}
        for release in releases:
            family = release["family"]
            expected = [family] + [f"{family}.{i}" for i in range(1, counts[family] + 1)]
            result["all"]["include"].append({"module": release["module"], "game_versions": json.dumps(expected)})
        validate_server_coverage(result, targets, exclusions)
        self.assertEqual(45, len(targets))
        self.assertEqual({("forge-1.20", "1.20.5"), ("forge-1.21", "1.21.2")},
                         {(t["module"], t["minecraft"]) for t in exclusions})
        for target in targets:
            modern = "-26." in target["module"]
            expected_java = 25 if modern else 21 if target["minecraft"].startswith("1.21") or target["minecraft"] == "1.20.6" else 17
            self.assertEqual(expected_java, target["java"])
            self.assertEqual("dist-26" if modern else "dist", target["artifact"])
            self.assertRegex(target["loader"], r"^\d+(?:\.\d+)+(?:-[A-Za-z0-9]+(?:\.\d+)*)?$")
            if target["module"].startswith("forge-"):
                self.assertTrue(target["loader"].startswith(target["minecraft"] + "-"))
            else:
                prefix = target["minecraft"]
                if prefix.startswith("1."):
                    prefix = prefix[2:]
                    if "." not in prefix:
                        prefix += ".0"
                elif prefix.count(".") == 1:
                    prefix += ".0"
                self.assertTrue(target["loader"].startswith(prefix + "."))

    def test_exclusion_preserves_release_tags(self):
        target = {"module": "forge-1.20", "game_versions": '["1.20.5","1.20.6"]'}
        result = {"legacy": {"include": [target]}}
        tests = [{"module": "forge-1.20", "minecraft": "1.20.6"}]
        exclusions = [{"module": "forge-1.20", "minecraft": "1.20.5", "reason": "No published Forge loader"}]
        with self.assertRaises(ValueError):
            validate_server_coverage(result, tests)
        validate_server_coverage(result, tests, exclusions)
        self.assertEqual('["1.20.5","1.20.6"]', target["game_versions"])

    def test_exclusions_cannot_hide_an_existing_test(self):
        result = {"legacy": {"include": [{"module": "forge-1.20", "game_versions": '["1.20.6"]'}]}}
        tests = [{"module": "forge-1.20", "minecraft": "1.20.6"}]
        with self.assertRaises(ValueError):
            validate_server_coverage(result, tests, [{**tests[0], "reason": "Overlap"}])

    def test_legacy_packaged_cli_forwards_release_artifact(self):
        with patch("sys.argv", ["system_test_all.py", "--packaged", "--artifact-dir", "dist",
                                "--modules", "forge-1.18", "--loader-coordinate", "1.18.2-40.3.12"]), \
                patch.object(system_test_all, "run_one") as run, patch("builtins.print"):
            self.assertEqual(0, system_test_all.main())
        run.assert_called_once_with("forge-1.18", 600, packaged=True,
                                    loader_coordinate="1.18.2-40.3.12", artifact_dir=Path("dist"))

    def test_workflows_share_full_matrix_and_build_each_group_once(self):
        root = Path(__file__).resolve().parents[1]
        for filename in ("ci-dev.yml", "release.yml"):
            workflow = (root / ".github/workflows" / filename).read_text()
            self.assertIn("uses: ./.github/workflows/server-tests.yml", workflow)
            self.assertIn("./scripts/build_dist.sh --legacy-only", workflow)
            self.assertNotIn("server-tests-26.yml", workflow)
        matrix = (root / ".github/workflows/server-tests.yml").read_text()
        self.assertIn("name: ${{ matrix.artifact }}", matrix)
        self.assertIn("fail-fast: false", matrix)
        self.assertNotIn("continue-on-error", matrix)
        self.assertNotIn("max-parallel", matrix)
        self.assertNotIn("./gradlew", matrix)


if __name__ == "__main__":
    unittest.main()
