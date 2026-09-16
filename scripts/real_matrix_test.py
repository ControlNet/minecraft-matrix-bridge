#!/usr/bin/env python3
"""Run a packaged mod JAR against a real, ephemeral Synapse homeserver."""

from __future__ import annotations

import argparse
import json
import os
import queue
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

import system_test_all


DEFAULT_SYNAPSE_IMAGE = (
    "docker.io/matrixdotorg/synapse:v1.160.0@"
    "sha256:78de1d10bef02e375f861d1cc99f8bedd9381d4f9083ea8b2c22a053477b205f"
)


def validate_synapse_image(image: str) -> None:
    if not image or "@sha256:" not in image:
        raise ValueError("Synapse image must be digest-pinned")
    digest = image.rsplit("@sha256:", 1)[1]
    if len(digest) != 64 or any(ch not in "0123456789abcdef" for ch in digest):
        raise ValueError("Synapse image must contain a valid sha256 digest")


def redact_text(text: str, secrets_to_redact: list[str]) -> str:
    redacted = text
    for value in sorted((v for v in secrets_to_redact if v), key=len, reverse=True):
        redacted = redacted.replace(value, "<redacted>")
    return redacted


def extract_text_events(response: dict, room_id: str) -> list[tuple[str, str]]:
    rooms = response.get("rooms")
    joined = rooms.get("join") if isinstance(rooms, dict) else None
    room = joined.get(room_id) if isinstance(joined, dict) else None
    timeline = room.get("timeline") if isinstance(room, dict) else None
    events = timeline.get("events") if isinstance(timeline, dict) else None
    if not isinstance(events, list):
        return []

    result: list[tuple[str, str]] = []
    for event in events:
        if not isinstance(event, dict) or event.get("type") != "m.room.message":
            continue
        content = event.get("content")
        if not isinstance(content, dict) or content.get("msgtype") != "m.text":
            continue
        sender = event.get("sender")
        body = content.get("body")
        if isinstance(sender, str) and isinstance(body, str):
            result.append((sender, body))
    return result


class MatrixHttpClient:
    def __init__(self, homeserver: str, secrets_to_redact: list[str]) -> None:
        self.homeserver = homeserver.rstrip("/")
        self.secrets_to_redact = secrets_to_redact

    def _request(
        self,
        method: str,
        path: str,
        *,
        token: str | None = None,
        payload: dict | None = None,
        timeout_s: float = 30,
    ) -> dict:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = Request(
            self.homeserver + path,
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with urlopen(request, timeout=timeout_s) as response:
                raw = response.read()
        except HTTPError as exc:
            raw = exc.read()
            errcode = ""
            try:
                parsed = json.loads(raw.decode("utf-8"))
                if isinstance(parsed, dict) and isinstance(parsed.get("errcode"), str):
                    errcode = " " + parsed["errcode"]
            except (UnicodeDecodeError, json.JSONDecodeError):
                pass
            raise RuntimeError(
                f"Matrix {method} {path.split('?', 1)[0]} failed: HTTP {exc.code}{errcode}"
            ) from exc
        except URLError as exc:
            raise RuntimeError(
                f"Matrix {method} {path.split('?', 1)[0]} failed: {exc.reason}"
            ) from exc

        if not raw:
            return {}
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise RuntimeError(
                f"Matrix {method} {path.split('?', 1)[0]} returned invalid JSON"
            ) from exc
        if not isinstance(parsed, dict):
            raise RuntimeError(
                f"Matrix {method} {path.split('?', 1)[0]} returned a non-object response"
            )
        return parsed

    def login(self, localpart: str, password: str) -> tuple[str, str]:
        response = self._request(
            "POST",
            "/_matrix/client/v3/login",
            payload={
                "type": "m.login.password",
                "identifier": {"type": "m.id.user", "user": localpart},
                "password": password,
                "device_id": "MATRIXBRIDGE_CI_" + localpart.upper(),
            },
        )
        token = response.get("access_token")
        user_id = response.get("user_id")
        if not isinstance(token, str) or not token:
            raise RuntimeError("Matrix login response did not contain an access token")
        if not isinstance(user_id, str) or not user_id:
            raise RuntimeError("Matrix login response did not contain a user ID")
        self.secrets_to_redact.append(token)
        return token, user_id

    def create_room(self, token: str, bridge_user_id: str) -> tuple[str, str]:
        alias_localpart = "minecraft-bridge-ci"
        response = self._request(
            "POST",
            "/_matrix/client/v3/createRoom",
            token=token,
            payload={
                "preset": "private_chat",
                "room_alias_name": alias_localpart,
                "name": "Minecraft Matrix Bridge CI",
                "invite": [bridge_user_id],
                "creation_content": {"m.federate": False},
                # Keep the permission probe independent of room-version-specific
                # creator representation while still exercising power-level state.
                "power_level_content_override": {"users_default": 100},
            },
        )
        room_id = response.get("room_id")
        if not isinstance(room_id, str) or not room_id:
            raise RuntimeError("Matrix createRoom response did not contain a room ID")
        return room_id, f"#{alias_localpart}:test.invalid"

    def joined_rooms(self, token: str) -> list[str]:
        response = self._request(
            "GET", "/_matrix/client/v3/joined_rooms", token=token
        )
        rooms = response.get("joined_rooms")
        if not isinstance(rooms, list) or not all(isinstance(v, str) for v in rooms):
            raise RuntimeError("Matrix joined_rooms response was malformed")
        return rooms

    def sync(self, token: str, since: str, timeout_ms: int = 1000) -> dict:
        query = {"timeout": str(timeout_ms)}
        if since:
            query["since"] = since
        return self._request(
            "GET",
            "/_matrix/client/v3/sync?" + urlencode(query),
            token=token,
            timeout_s=max(15, timeout_ms / 1000 + 10),
        )

    def send_text(self, token: str, room_id: str, body: str) -> None:
        transaction_id = "ci-" + uuid.uuid4().hex
        self._request(
            "PUT",
            "/_matrix/client/v3/rooms/"
            + quote(room_id, safe="")
            + "/send/m.room.message/"
            + transaction_id,
            token=token,
            payload={"msgtype": "m.text", "body": body},
        )

    def wait_for_text(
        self,
        token: str,
        room_id: str,
        since: str,
        predicate: Callable[[str, str], bool],
        timeout_s: int,
    ) -> tuple[str, str]:
        cursor = since
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            remaining_ms = max(0, int((deadline - time.time()) * 1000))
            response = self.sync(token, cursor, min(1000, remaining_ms))
            next_batch = response.get("next_batch")
            if isinstance(next_batch, str) and next_batch:
                cursor = next_batch
            for sender, body in extract_text_events(response, room_id):
                if predicate(sender, body):
                    return cursor, body
        raise RuntimeError("Expected Matrix room message did not arrive before timeout")


@dataclass(frozen=True)
class SynapseFixture:
    homeserver: str
    bridge_token: str
    bridge_user_id: str
    alice_token: str
    alice_user_id: str
    room_id: str
    room_alias: str
    alice_since: str


class SynapseTestServer:
    def __init__(self, run_dir: Path, image: str) -> None:
        validate_synapse_image(image)
        self.run_dir = run_dir
        self.image = image
        self.data_dir = run_dir / "synapse-data"
        self.log_path = run_dir / "synapse.log"
        self.container_name = "matrixbridge-synapse-" + uuid.uuid4().hex[:12]
        self.port = system_test_all.pick_free_port()
        self.homeserver = f"http://127.0.0.1:{self.port}"
        self.secrets_to_redact: list[str] = []
        self.started = False

    def _run(self, command: list[str], timeout_s: int = 120) -> str:
        result = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout_s,
        )
        if result.returncode != 0:
            output = redact_text(result.stdout, self.secrets_to_redact).strip()
            raise RuntimeError(
                f"Command failed with exit code {result.returncode}: {command[0]}"
                + (f"\n{output}" if output else "")
            )
        return result.stdout.strip()

    def _wait_for_health(self, timeout_s: int = 60) -> None:
        deadline = time.time() + timeout_s
        last_error = "not ready"
        while time.time() < deadline:
            try:
                with urlopen(self.homeserver + "/health", timeout=2) as response:
                    if response.status == 200:
                        return
            except Exception as exc:  # Readiness retries intentionally accept transient failures.
                last_error = type(exc).__name__
            time.sleep(0.5)
        raise RuntimeError(f"Synapse did not become healthy: {last_error}")

    def _register_user(self, localpart: str, password: str) -> None:
        password_file = self.data_dir / f"{localpart}.password"
        password_file.write_text(password, encoding="utf-8")
        password_file.chmod(0o600)
        try:
            self._run(
                [
                    "docker",
                    "exec",
                    self.container_name,
                    "register_new_matrix_user",
                    "-u",
                    localpart,
                    "--password-file",
                    f"/data/{password_file.name}",
                    "--no-admin",
                    "-c",
                    "/data/homeserver.yaml",
                    "http://localhost:8008",
                ]
            )
        finally:
            password_file.unlink(missing_ok=True)

    def _register_generated_secrets_for_redaction(self) -> None:
        config = (self.data_dir / "homeserver.yaml").read_text(encoding="utf-8")
        for line in config.splitlines():
            match = re.match(
                r"^(?:registration_shared_secret|macaroon_secret_key|form_secret):\s*(.+)$",
                line,
            )
            if match is None:
                continue
            value = match.group(1).strip().strip('"\'')
            if value:
                self.secrets_to_redact.append(value)

    def start(self) -> SynapseFixture:
        if shutil.which("docker") is None:
            raise RuntimeError("Docker is required for the real Matrix test")

        self.data_dir.mkdir(parents=True, mode=0o700)
        uid = str(getattr(os, "getuid", lambda: 991)())
        gid = str(getattr(os, "getgid", lambda: 991)())
        mount = f"type=bind,source={self.data_dir.resolve()},target=/data"

        self._run(
            [
                "docker",
                "run",
                "--rm",
                "--mount",
                mount,
                "-e",
                f"UID={uid}",
                "-e",
                f"GID={gid}",
                "-e",
                "SYNAPSE_SERVER_NAME=test.invalid",
                "-e",
                "SYNAPSE_REPORT_STATS=no",
                "-e",
                "SYNAPSE_LOG_LEVEL=WARNING",
                self.image,
                "generate",
            ],
            timeout_s=180,
        )
        self._register_generated_secrets_for_redaction()
        self._run(
            [
                "docker",
                "run",
                "--detach",
                "--name",
                self.container_name,
                "--mount",
                mount,
                "-e",
                f"UID={uid}",
                "-e",
                f"GID={gid}",
                "-p",
                f"127.0.0.1:{self.port}:8008",
                self.image,
            ],
            timeout_s=180,
        )
        self.started = True
        self._wait_for_health()

        bridge_password = secrets.token_urlsafe(32)
        alice_password = secrets.token_urlsafe(32)
        self.secrets_to_redact.extend([bridge_password, alice_password])
        self._register_user("bridge", bridge_password)
        self._register_user("alice", alice_password)

        api = MatrixHttpClient(self.homeserver, self.secrets_to_redact)
        bridge_token, bridge_user_id = api.login("bridge", bridge_password)
        alice_token, alice_user_id = api.login("alice", alice_password)
        room_id, room_alias = api.create_room(alice_token, bridge_user_id)
        initial_sync = api.sync(alice_token, "", 0)
        alice_since = initial_sync.get("next_batch")
        if not isinstance(alice_since, str) or not alice_since:
            raise RuntimeError("Initial Matrix sync did not return next_batch")

        return SynapseFixture(
            homeserver=self.homeserver,
            bridge_token=bridge_token,
            bridge_user_id=bridge_user_id,
            alice_token=alice_token,
            alice_user_id=alice_user_id,
            room_id=room_id,
            room_alias=room_alias,
            alice_since=alice_since,
        )

    def stop(self) -> None:
        log_text = ""
        if self.started:
            result = subprocess.run(
                ["docker", "logs", self.container_name],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            log_text = redact_text(result.stdout, self.secrets_to_redact)
            subprocess.run(
                ["docker", "rm", "--force", self.container_name],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            self.started = False
        self.log_path.write_text(log_text, encoding="utf-8")
        if self.data_dir.exists():
            shutil.rmtree(self.data_dir)


def _reader_thread(
    stream,
    out_queue: "queue.Queue[str]",
    event_index_ready: threading.Event,
    bridge_ready: threading.Event,
    redactions: list[str],
) -> None:
    for raw_line in iter(stream.readline, ""):
        line = redact_text(raw_line, redactions)
        if "Event index built in" in line:
            event_index_ready.set()
        if "MatrixBridge started as" in line:
            bridge_ready.set()
        out_queue.put(line)
    stream.close()


def _drain_output(module: str, output: "queue.Queue[str]") -> None:
    try:
        while True:
            line = output.get_nowait()
            if not line.startswith("Downloading: "):
                sys.stdout.write(f"[{module}] {line}")
    except queue.Empty:
        return


def _wait_for_minecraft(
    module: str,
    proc: subprocess.Popen[str],
    output: "queue.Queue[str]",
    timeout_s: int,
) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            line = output.get(timeout=0.5)
        except queue.Empty:
            if proc.poll() is not None:
                break
            continue
        if not line.startswith("Downloading: "):
            sys.stdout.write(f"[{module}] {line}")
        if "Done (" in line or 'For help, type "help"' in line:
            return
    raise RuntimeError(
        f"{module}: Minecraft did not reach 'Done' before timeout; exit={proc.poll()}"
    )


def _wait_for_bridge(
    module: str,
    run_dir: Path,
    proc: subprocess.Popen[str],
    output: "queue.Queue[str]",
    bridge_ready: threading.Event,
    api: MatrixHttpClient,
    fixture: SynapseFixture,
    timeout_s: int,
) -> Path:
    deadline = time.time() + timeout_s
    joined = False
    state: Optional[Path] = None
    last_api_error = "none"
    while time.time() < deadline:
        _drain_output(module, output)
        state = system_test_all.find_state_file(run_dir)
        try:
            joined = fixture.room_id in api.joined_rooms(fixture.bridge_token)
            last_api_error = "none"
        except RuntimeError as exc:
            last_api_error = str(exc)
        if bridge_ready.is_set() and joined and state is not None:
            return state
        if proc.poll() is not None:
            break
        time.sleep(0.5)
    raise RuntimeError(
        f"{module}: real Matrix bridge did not become ready; "
        f"log_ready={bridge_ready.is_set()} joined={joined} state={state} "
        f"api_error={last_api_error}"
    )


def run_one(
    *,
    module: str,
    loader_coordinate: str,
    artifact_dir: Path,
    timeout_s: int,
    synapse_image: str,
) -> None:
    if module not in system_test_all.MODULE_EVENT_ALIASES:
        raise ValueError(f"Unknown real Matrix test module: {module}")
    if not loader_coordinate:
        raise ValueError("A loader coordinate is required for packaged real Matrix tests")
    validate_synapse_image(synapse_image)

    root = Path(__file__).resolve().parents[1]
    run_dir = root / module / "run-realmatrixtest" / loader_coordinate
    if run_dir.exists():
        shutil.rmtree(run_dir)
    system_test_all.ensure_eula(run_dir)
    system_test_all.write_server_properties(run_dir, system_test_all.pick_free_port())
    (run_dir / "jna").mkdir(parents=True, exist_ok=True)
    command = system_test_all.packaged_server_command(
        root,
        module,
        run_dir,
        loader_coordinate=loader_coordinate,
        artifact_dir=artifact_dir,
    )

    synapse = SynapseTestServer(run_dir, synapse_image)
    proc: Optional[subprocess.Popen[str]] = None
    try:
        fixture = synapse.start()
        api = MatrixHttpClient(fixture.homeserver, synapse.secrets_to_redact)
        system_test_all.write_systemtest_config(
            run_dir, fixture.homeserver, fixture.room_alias
        )

        env = dict(os.environ)
        env["MATRIX_ACCESS_TOKEN"] = fixture.bridge_token
        proc = subprocess.Popen(
            command,
            cwd=run_dir,
            env=env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert proc.stdout is not None
        assert proc.stdin is not None

        output: "queue.Queue[str]" = queue.Queue()
        event_index_ready = threading.Event()
        bridge_ready = threading.Event()
        reader = threading.Thread(
            target=_reader_thread,
            args=(
                proc.stdout,
                output,
                event_index_ready,
                bridge_ready,
                synapse.secrets_to_redact,
            ),
            daemon=True,
        )
        reader.start()

        _wait_for_minecraft(module, proc, output, timeout_s)
        state = _wait_for_bridge(
            module,
            run_dir,
            proc,
            output,
            bridge_ready,
            api,
            fixture,
            timeout_s=60,
        )

        alice_since = fixture.alice_since
        proc.stdin.write("matrix test\n")
        proc.stdin.flush()
        alice_since, _ = api.wait_for_text(
            fixture.alice_token,
            fixture.room_id,
            alice_since,
            lambda sender, body: sender == fixture.bridge_user_id and "[TEST]" in body,
            timeout_s=30,
        )

        api.send_text(fixture.alice_token, fixture.room_id, "!mc list")
        alice_since, list_reply = api.wait_for_text(
            fixture.alice_token,
            fixture.room_id,
            alice_since,
            lambda sender, body: sender == fixture.bridge_user_id
            and body == "No players online.",
            timeout_s=30,
        )

        system_test_all.wait_for_event_index_ready(
            module,
            proc,
            output,
            timeout_s=60,
            ready=event_index_ready,
        )
        api.send_text(fixture.alice_token, fixture.room_id, "!mc event list")
        _, event_reply = api.wait_for_text(
            fixture.alice_token,
            fixture.room_id,
            alice_since,
            lambda sender, body: sender == fixture.bridge_user_id
            and body == "No active event taps.",
            timeout_s=30,
        )

        proc.stdin.write("stop\n")
        proc.stdin.flush()
        try:
            proc.wait(timeout=180)
        except subprocess.TimeoutExpired as exc:
            proc.kill()
            raise RuntimeError(f"{module}: Minecraft did not stop after command") from exc
        _drain_output(module, output)
        if proc.returncode != 0:
            raise RuntimeError(
                f"{module}: packaged real Matrix run exited with {proc.returncode}"
            )

        print(
            f"[{module}] REAL MATRIX OK: loader={loader_coordinate} "
            f"state={state.relative_to(run_dir)} list={list_reply!r} event={event_reply!r}",
            flush=True,
        )
    finally:
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=15)
        synapse.stop()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact-dir", type=Path, required=True)
    parser.add_argument("--module", required=True)
    parser.add_argument("--loader-coordinate", required=True)
    parser.add_argument("--timeout-s", type=int, default=600)
    parser.add_argument(
        "--synapse-image",
        default=os.environ.get("SYNAPSE_IMAGE", DEFAULT_SYNAPSE_IMAGE),
    )
    args = parser.parse_args()
    run_one(
        module=args.module,
        loader_coordinate=args.loader_coordinate,
        artifact_dir=args.artifact_dir,
        timeout_s=args.timeout_s,
        synapse_image=args.synapse_image,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
