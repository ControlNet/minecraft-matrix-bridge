#!/usr/bin/env python3
"""Maintain metadata for existing CurseForge project files."""

import argparse
import json
import os
import secrets
import urllib.error
import urllib.request


DEFAULT_BASE_URL = "https://minecraft.curseforge.com"


def parse_file_ids(value):
    result = []
    seen = set()
    for item in value.split(","):
        item = item.strip()
        if not item.isdigit() or int(item) <= 0:
            raise ValueError(f"CurseForge file IDs must be positive integers: {item!r}")
        file_id = int(item)
        if file_id not in seen:
            seen.add(file_id)
            result.append(file_id)
    if not result:
        raise ValueError("At least one CurseForge file ID is required")
    return result


def build_update_request(base_url, project_id, file_id, token, boundary=None):
    if not str(project_id).isdigit() or int(project_id) <= 0:
        raise ValueError("CurseForge project ID must be a positive integer")
    if not token:
        raise ValueError("CURSEFORGE_TOKEN is required")

    boundary = boundary or f"curseforge-{secrets.token_hex(16)}"
    payload = json.dumps({"fileID": file_id, "displayName": ""}, separators=(",", ":"))
    body = (
        f"--{boundary}\r\n"
        'Content-Disposition: form-data; name="metadata"\r\n'
        "Content-Type: application/json\r\n\r\n"
        f"{payload}\r\n"
        f"--{boundary}--\r\n"
    ).encode("utf-8")
    url = f"{base_url.rstrip('/')}/api/projects/{project_id}/update-file"
    return urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "X-Api-Token": token,
        },
        method="POST",
    )


def clear_display_name(project_id, file_id, token, opener=urllib.request.urlopen):
    request = build_update_request(DEFAULT_BASE_URL, project_id, file_id, token)
    try:
        with opener(request, timeout=30) as response:
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        details = error.read().decode("utf-8", errors="replace")[:1000]
        raise RuntimeError(f"CurseForge rejected file {file_id} with HTTP {error.code}: {details}") from error

    result = json.loads(response_body)
    if result.get("id") != file_id:
        raise RuntimeError(f"CurseForge returned an unexpected file ID for {file_id}: {result!r}")


def main():
    parser = argparse.ArgumentParser(description="Clear display names for existing CurseForge files")
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--file-ids", required=True, help="Comma-separated CurseForge file IDs")
    args = parser.parse_args()

    token = os.environ.get("CURSEFORGE_TOKEN", "")
    try:
        file_ids = parse_file_ids(args.file_ids)
        for file_id in file_ids:
            clear_display_name(args.project_id, file_id, token)
            print(f"Cleared display name for CurseForge file {file_id}")
    except (ValueError, RuntimeError, urllib.error.URLError, json.JSONDecodeError) as error:
        parser.exit(1, f"ERROR: {error}\n")


if __name__ == "__main__":
    main()
