"""Offline tests for the release decision; tags and commits are synthetic."""

import unittest

import release_plan as rp


HEAD = "a" * 40
OTHER = "b" * 40
MASTER = rp.RELEASE_REF


class SemverTests(unittest.TestCase):
    def test_final_release_outranks_its_prereleases(self):
        # `sort -V` ranks 0.1.6-beta.1 above 0.1.6; SemVer does not.
        self.assertGreater(rp.precedence("0.1.6"), rp.precedence("0.1.6-beta.1"))
        self.assertGreater(rp.precedence("0.1.6"), rp.precedence("0.1.6-rc1"))

    def test_spec_precedence_example(self):
        ordered = ["1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                   "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"]
        self.assertEqual(sorted(reversed(ordered), key=rp.precedence), ordered)

    def test_numeric_components_compare_numerically(self):
        self.assertGreater(rp.precedence("0.1.10"), rp.precedence("0.1.9"))

    def test_invalid_versions_are_rejected(self):
        for value in ["", "1.0", "01.0.0", "1.0.0-", "v1.0.0", "1.0.0 ", "1.0.0-a..b"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                rp.parse_semver(value)

    def test_latest_ignores_non_semver_tags(self):
        self.assertEqual(rp.latest_version(["v0.1.5", "v0.1.6-beta.1", "vnext", "v1.0", "release"]), "0.1.6-beta.1")

    def test_channels(self):
        self.assertEqual(rp.channel_for("0.1.6"), "release")
        self.assertEqual(rp.channel_for("0.1.6-alpha.2"), "alpha")
        for value in ["0.1.6-beta.1", "0.1.6-rc1", "0.1.6-pre3"]:
            self.assertEqual(rp.channel_for(value), "beta")


class PlanTests(unittest.TestCase):
    def test_final_release_after_prerelease_is_published(self):
        result = rp.plan("0.1.6", ["v0.1.5", "v0.1.6-beta.1"], HEAD, "", MASTER)
        self.assertEqual(result["should_release"], "true")
        self.assertEqual(result["prerelease"], "false")
        self.assertEqual(result["make_latest"], "true")

    def test_prerelease_is_not_latest(self):
        result = rp.plan("0.1.6-beta.1", ["v0.1.5"], HEAD, "", MASTER)
        self.assertEqual((result["should_release"], result["prerelease"], result["make_latest"]),
                         ("true", "true", "false"))

    def test_existing_tag_at_head_resumes(self):
        result = rp.plan("0.1.6", ["v0.1.5", "v0.1.6"], HEAD, HEAD, MASTER)
        self.assertEqual((result["should_release"], result["resume"]), ("true", "true"))

    def test_existing_tag_elsewhere_is_not_republished(self):
        result = rp.plan("0.1.6", ["v0.1.6"], HEAD, OTHER, MASTER)
        self.assertEqual((result["should_release"], result["build"]), ("false", "false"))

    def test_older_or_equal_version_is_skipped(self):
        for version in ["0.1.5", "0.1.4", "0.1.5-rc.1"]:
            with self.subTest(version=version):
                self.assertEqual(rp.plan(version, ["v0.1.5"], HEAD, "", MASTER)["should_release"], "false")

    def test_first_release(self):
        self.assertEqual(rp.plan("0.1.0", [], HEAD, "", MASTER)["should_release"], "true")

    def test_other_branches_never_publish(self):
        for ref in ["refs/heads/dev", "refs/tags/v0.1.6", ""]:
            with self.subTest(ref=ref):
                result = rp.plan("0.1.6", ["v0.1.5"], HEAD, "", ref)
                self.assertEqual((result["should_release"], result["build"]), ("false", "false"))

    def test_dry_run_builds_without_publishing_from_any_branch(self):
        for ref in [MASTER, "refs/heads/dev"]:
            with self.subTest(ref=ref):
                result = rp.plan("0.1.6", ["v0.1.5"], HEAD, "", ref, dry_run=True)
                self.assertEqual((result["should_release"], result["build"]), ("false", "true"))

    def test_invalid_mod_version_fails(self):
        with self.assertRaises(ValueError):
            rp.plan("0.1.6 beta", [], HEAD, "", MASTER)


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.workflow = (rp.ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")

    def test_workflow_uses_semver_plan_not_sort_v(self):
        self.assertIn("python3 scripts/release_plan.py", self.workflow)
        self.assertNotIn("sort -V", self.workflow)

    def test_releases_are_serialized(self):
        self.assertRegex(self.workflow, r"(?m)^concurrency:\n  group: release\n  cancel-in-progress: false$")

    def test_default_token_is_read_only(self):
        self.assertRegex(self.workflow, r"(?m)^permissions:\n  contents: read$")


if __name__ == "__main__":
    unittest.main()
