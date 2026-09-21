"""Offline guards for CI runtime and project-owned deprecation regressions."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class CiMaintenanceTests(unittest.TestCase):
    def test_oldest_forge_sources_do_not_require_mojang_logging(self):
        for directory in ("forge-shared/common/src/main/java", "forge-1.18/src/main/java"):
            for path in (ROOT / directory).rglob("*.java"):
                self.assertNotIn("com.mojang.logging.LogUtils", path.read_text(), str(path))

    def test_first_party_actions_use_node24_releases(self):
        expected = {
            "actions/checkout": "v7",
            "actions/setup-java": "v6",
            "actions/upload-artifact": "v7",
            "actions/download-artifact": "v8",
            "gradle/actions/setup-gradle": "v6",
            "softprops/action-gh-release": "v3",
        }
        for workflow in (ROOT / ".github/workflows").glob("*.yml"):
            for line in workflow.read_text().splitlines():
                if "uses:" not in line:
                    continue
                action = line.split("uses:", 1)[1].strip()
                name, _, version = action.partition("@")
                if name in expected:
                    self.assertEqual(expected[name], version, f"{workflow.name}: {action}")

    def test_forge_config_registration_avoids_deprecated_context(self):
        source = (ROOT / "forge-shared/common/src/main/java/space/controlnet/minecraftmatrixbridge/MatrixBridgeMod.java").read_text()
        self.assertNotIn("ModLoadingContext.get()", source)
        self.assertNotIn("SuppressWarnings", source)

    def test_gradle_native_access_is_explicit_without_silencing_warnings(self):
        for filename in ("gradlew-26", "gradlew-26.bat", "gradle.properties"):
            source = (ROOT / filename).read_text()
            self.assertIn("--enable-native-access=ALL-UNNAMED", source)
            self.assertNotIn("--warning-mode=none", source)
        for workflow in (ROOT / ".github/workflows").glob("*.yml"):
            self.assertNotIn("NODE_NO_WARNINGS", workflow.read_text())


if __name__ == "__main__":
    unittest.main()
