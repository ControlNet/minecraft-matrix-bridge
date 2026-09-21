"""Guard the oldest supported loader in each legacy Minecraft family."""

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class LoaderRangeTests(unittest.TestCase):
    def test_legacy_forge_accepts_family_base_loader(self):
        for family, minimum in (("1.18", 38), ("1.19", 41), ("1.20", 46), ("1.21", 51)):
            source = (ROOT / f"forge-{family}" / "build.gradle").read_text()
            for field in ("loader_version_range", "forge_version_range"):
                with self.subTest(family=family, field=field):
                    match = re.search(rf"\b{field}\s*=\s*'([^']+)'", source)
                    self.assertIsNotNone(match)
                    self.assertEqual(match.group(1), f"[{minimum},)")

    def test_neoforge_accepts_1_21_base_loader(self):
        source = (ROOT / "neoforge-1.21" / "build.gradle").read_text()
        match = re.search(r"\bneo_version_range\s*=\s*'([^']+)'", source)
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), "[21.0,)")


if __name__ == "__main__":
    unittest.main()
