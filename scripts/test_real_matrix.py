"""Offline tests with synthetic Matrix payloads; no real accounts or servers are used."""

import json
from pathlib import Path
from unittest import TestCase
from unittest.mock import patch

import real_matrix_test


class RealMatrixHelpersTest(TestCase):
    def test_extract_text_events_only_returns_room_text_messages(self):
        response = {
            "next_batch": "s2",
            "rooms": {
                "join": {
                    "!wanted:test.invalid": {
                        "timeline": {
                            "events": [
                                {
                                    "type": "m.room.message",
                                    "sender": "@bridge:test.invalid",
                                    "content": {"msgtype": "m.text", "body": "expected"},
                                },
                                {
                                    "type": "m.room.message",
                                    "sender": "@bridge:test.invalid",
                                    "content": {"msgtype": "m.image", "body": "ignored"},
                                },
                            ]
                        }
                    },
                    "!other:test.invalid": {
                        "timeline": {
                            "events": [
                                {
                                    "type": "m.room.message",
                                    "sender": "@other:test.invalid",
                                    "content": {"msgtype": "m.text", "body": "wrong room"},
                                }
                            ]
                        }
                    },
                }
            },
        }

        self.assertEqual(
            [("@bridge:test.invalid", "expected")],
            real_matrix_test.extract_text_events(response, "!wanted:test.invalid"),
        )

    def test_redaction_removes_every_runtime_credential(self):
        text = "password=synthetic-password token=synthetic-token"
        self.assertEqual(
            "password=<redacted> token=<redacted>",
            real_matrix_test.redact_text(
                text, ["synthetic-password", "synthetic-token"]
            ),
        )

    def test_synapse_image_must_be_digest_pinned(self):
        with self.assertRaisesRegex(ValueError, "digest-pinned"):
            real_matrix_test.validate_synapse_image("matrixdotorg/synapse:latest")
        real_matrix_test.validate_synapse_image(
            "matrixdotorg/synapse:v1.160.0@sha256:" + "a" * 64
        )

    def test_cli_forwards_the_verified_artifact_without_building(self):
        with patch(
            "sys.argv",
            [
                "real_matrix_test.py",
                "--artifact-dir",
                "dist",
                "--module",
                "forge-1.21",
                "--loader-coordinate",
                "1.21.11-61.2.1",
            ],
        ), patch.object(real_matrix_test, "run_one") as run:
            self.assertEqual(0, real_matrix_test.main())

        run.assert_called_once_with(
            module="forge-1.21",
            loader_coordinate="1.21.11-61.2.1",
            artifact_dir=Path("dist"),
            timeout_s=600,
            synapse_image=real_matrix_test.DEFAULT_SYNAPSE_IMAGE,
        )

    def test_workflow_uses_all_targets_after_mock_matrix_and_never_builds(self):
        root = Path(__file__).resolve().parents[1]
        targets = json.loads((root / "scripts/server-test-targets.json").read_text())
        workflow = (root / ".github/workflows/real-matrix-tests.yml").read_text()
        dev = (root / ".github/workflows/ci-dev.yml").read_text()

        self.assertEqual(45, len(targets))
        self.assertIn("scripts/server-test-targets.json", workflow)
        self.assertIn("fail-fast: false", workflow)
        self.assertIn("python3 scripts/real_matrix_test.py", workflow)
        self.assertNotIn("gradlew", workflow)
        self.assertNotIn("continue-on-error", workflow)
        self.assertNotIn("max-parallel", workflow)
        self.assertIn("needs: test-servers", dev)
        self.assertIn("uses: ./.github/workflows/real-matrix-tests.yml", dev)


if __name__ == "__main__":
    import unittest

    unittest.main()
