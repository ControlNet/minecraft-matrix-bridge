"""ForgeGradle 7 needs a JDK 8 for `modifyAccess`; CI must provide it instead of downloading."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]


class Forge26Jdk8Tests(unittest.TestCase):
    def test_every_26_build_job_provides_jdk8(self):
        for name in ("ci-dev.yml", "release.yml"):
            workflow = (ROOT / ".github/workflows" / name).read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                # Java 8 is installed alongside 25 (25 last, so it stays the default JAVA_HOME) ...
                self.assertRegex(workflow, r'java-version: \|\n\s+8\n\s+25\n')
                # ... and exported as JDK8, which ForgeGradle's GradleLocator reads (JDK\d+).
                self.assertRegex(workflow, re.compile(
                    r"- name: Build 26\.x dist\n\s+env:\n\s+JDK8: \$\{\{ env\.JAVA_HOME_8_X64 \}\}\n\s+run: \./scripts/build_dist_26\.sh"))


if __name__ == "__main__":
    unittest.main()
