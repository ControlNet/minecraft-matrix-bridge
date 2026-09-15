"""Offline regression tests using synthetic artifact bytes, not Minecraft JARs."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from system_test_all import verify_artifact
from release_metadata import validate_server_coverage


class ArtifactTests(unittest.TestCase):
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

    def test_matrix_covers_modern_modules_and_patch_families(self):
        root = Path(__file__).resolve().parent
        targets = json.loads((root / "server-test-targets.json").read_text())
        releases = json.loads((root / "release-targets.json").read_text())
        modules = {t["module"] for t in releases if t["group"] == "modern"}
        self.assertEqual(modules, {t["module"] for t in targets})
        keys = [(t["module"], t["minecraft"]) for t in targets]
        self.assertEqual(len(keys), len(set(keys)))
        for module in modules:
            expected = {"26.1", "26.1.1", "26.1.2"} if module.endswith("26.1") else {"26.2"}
            self.assertEqual(expected, {t["minecraft"] for t in targets if t["module"] == module})
        for target in targets:
            self.assertEqual(25, target["java"])
            self.assertRegex(target["loader"], r"^\d+(?:\.\d+)+(?:-[A-Za-z0-9]+(?:\.\d+)*)?$")
            if target["module"].startswith("forge-"):
                self.assertTrue(target["loader"].startswith(target["minecraft"] + "-"))
            else:
                prefix = target["minecraft"]
                if prefix.count(".") == 1:
                    prefix += ".0"
                self.assertTrue(target["loader"].startswith(prefix + "."))


if __name__ == "__main__":
    unittest.main()
