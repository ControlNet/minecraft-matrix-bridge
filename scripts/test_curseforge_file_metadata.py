import json
import unittest
from pathlib import Path

import curseforge_file_metadata as metadata


class CurseForgeFileMetadataTests(unittest.TestCase):
    def test_parse_file_ids_accepts_comma_separated_ids_and_removes_duplicates(self):
        self.assertEqual(metadata.parse_file_ids("8938207, 8938208,8938207"), [8938207, 8938208])

    def test_parse_file_ids_rejects_non_numeric_values(self):
        with self.assertRaisesRegex(ValueError, "positive integer"):
            metadata.parse_file_ids("8938207,not-an-id")

    def test_clear_display_name_request_uses_an_empty_string(self):
        request = metadata.build_update_request(
            base_url="https://minecraft.curseforge.com",
            project_id="1424288",
            file_id=8938207,
            token="test-token",
            boundary="test-boundary",
        )

        self.assertEqual(request.full_url, "https://minecraft.curseforge.com/api/projects/1424288/update-file")
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.get_header("X-api-token"), "test-token")
        self.assertEqual(request.get_header("Content-type"), "multipart/form-data; boundary=test-boundary")
        body = request.data.decode("utf-8")
        payload = body.split("\r\n\r\n", 1)[1].split("\r\n--test-boundary--", 1)[0]
        self.assertEqual(json.loads(payload), {"fileID": 8938207, "displayName": ""})

    def test_maintenance_workflow_uses_repository_secrets(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/curseforge-file-metadata.yml").read_text()
        self.assertIn("CURSEFORGE_TOKEN: ${{ secrets.CURSEFORGE_TOKEN }}", workflow)
        self.assertIn("CURSEFORGE_PROJECT_ID: ${{ secrets.CURSEFORGE_PROJECT_ID }}", workflow)
        self.assertNotIn("token:\n", workflow)


if __name__ == "__main__":
    unittest.main()
