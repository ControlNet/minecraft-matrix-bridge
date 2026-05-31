#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Optional
import socket
from urllib.parse import urlparse, unquote


@dataclass
class MatrixCounters:
    whoami_calls: int = 0
    resolve_calls: int = 0
    sync_calls: int = 0
    send_calls: int = 0
    last_send_body: str = ""


class MockMatrix:
    def __init__(
        self, token: str, self_user_id: str, room_id: str, room_alias: str
    ) -> None:
        self._token = token
        self._self_user_id = self_user_id
        self._room_id = room_id
        self._room_alias = room_alias
        self._counters = MatrixCounters()
        self._sync_next_batch = 0

        self._server: Optional[HTTPServer] = None
        self._thread: Optional[threading.Thread] = None

    @property
    def counters(self) -> MatrixCounters:
        return self._counters

    def start(self) -> str:
        token = self._token
        self_user_id = self._self_user_id
        room_id = self._room_id
        room_alias = self._room_alias
        counters = self._counters
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format: str, *args) -> None:
                return

            def _auth_ok(self) -> bool:
                auth = self.headers.get("Authorization", "")
                return auth == f"Bearer {token}"

            def _send_json(self, status: int, obj: object) -> None:
                data = json.dumps(obj).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except BrokenPipeError:
                    return

            def do_GET(self) -> None:  # noqa: N802
                parsed = urlparse(self.path)
                if parsed.path == "/_matrix/client/v3/account/whoami":
                    counters.whoami_calls += 1
                    if not self._auth_ok():
                        self.send_response(401)
                        self.end_headers()
                        return
                    self._send_json(200, {"user_id": self_user_id})
                    return

                if parsed.path.startswith("/_matrix/client/v3/directory/room/"):
                    counters.resolve_calls += 1
                    # Per spec, room alias directory lookup does not require auth.
                    raw_alias = parsed.path[len("/_matrix/client/v3/directory/room/") :]
                    alias = unquote(raw_alias)
                    if alias != room_alias:
                        self.send_response(404)
                        self.end_headers()
                        return
                    self._send_json(
                        200, {"room_id": room_id, "servers": ["example.com"]}
                    )
                    return

                if parsed.path == "/_matrix/client/v3/sync":
                    counters.sync_calls += 1
                    if not self._auth_ok():
                        self.send_response(401)
                        self.end_headers()
                        return
                    # Prevent tight loops: simulate a tiny long-poll delay.
                    time.sleep(0.05)

                    outer._sync_next_batch += 1
                    next_batch = f"s{outer._sync_next_batch}"

                    events = []
                    if outer._sync_next_batch == 1:
                        events = [
                            {
                                "type": "m.room.message",
                                "event_id": f"$e{outer._sync_next_batch}",
                                "sender": "@alice:example.com",
                                "content": {
                                    "msgtype": "m.text",
                                    "body": "hello from mock",
                                },
                            }
                        ]

                    resp = {
                        "next_batch": next_batch,
                        "rooms": {"join": {room_id: {"timeline": {"events": events}}}},
                    }
                    self._send_json(200, resp)
                    return

                if parsed.path == "/_matrix/client/v3/joined_rooms":
                    if not self._auth_ok():
                        self.send_response(401)
                        self.end_headers()
                        return
                    self._send_json(200, {"joined_rooms": [room_id]})
                    return

                self.send_response(404)
                self.end_headers()

            def do_PUT(self) -> None:  # noqa: N802
                parsed = urlparse(self.path)
                if "/send/m.room.message/" in parsed.path and parsed.path.startswith(
                    "/_matrix/client/v3/rooms/"
                ):
                    counters.send_calls += 1
                    if not self._auth_ok():
                        self.send_response(401)
                        self.end_headers()
                        return
                    length = int(self.headers.get("Content-Length", "0") or "0")
                    body = self.rfile.read(length).decode("utf-8", errors="replace")
                    counters.last_send_body = body
                    self._send_json(200, {"event_id": "$mock"})
                    return

                if parsed.path.startswith("/_matrix/client/v3/join/"):
                    if not self._auth_ok():
                        self.send_response(401)
                        self.end_headers()
                        return
                    self._send_json(200, {"room_id": room_id})
                    return

                self.send_response(404)
                self.end_headers()

        self._server = HTTPServer(("127.0.0.1", 0), Handler)
        port = self._server.server_address[1]
        self._thread = threading.Thread(
            target=self._server.serve_forever, name="MockMatrix", daemon=True
        )
        self._thread.start()
        return f"http://127.0.0.1:{port}"

    def stop(self) -> None:
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            self._server = None


def write_systemtest_config(
    run_dir: Path, homeserver: str, room_id_or_alias: str
) -> None:
    config_dir = run_dir / "config"
    config_dir.mkdir(parents=True, exist_ok=True)
    cfg = config_dir / "minecraftmatrixbridge.toml"
    cfg.write_text(
        "\n".join(
            [
                "[matrix]",
                f'homeserver="{homeserver}"',
                f'roomId="{room_id_or_alias}"',
                'accessToken=""',
                "",
                "[bridge]",
                "enableMcToMatrix=true",
                "enableMatrixToMc=true",
                "announceConnected=true",
                "enableJoinLeaveToMatrix=false",
                "enableServerLifecycleToMatrix=false",
                'mcToMatrixPrefix="[MC] "',
                'matrixToMcPrefix="[Matrix] "',
                "syncTimeoutMs=1000",
                "timelineLimit=5",
                "maxQueueSize=100",
                "dedupSize=64",
                "",
            ]
        ),
        encoding="utf-8",
    )


def ensure_eula(run_dir: Path) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")


def pick_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def write_server_properties(run_dir: Path, server_port: int) -> None:
    # Keep minimal; avoid binding conflicts in CI by forcing a free port.
    (run_dir / "server.properties").write_text(
        "\n".join(
            [
                "online-mode=false",
                f"server-port={server_port}",
                "max-players=4",
                "",
            ]
        ),
        encoding="utf-8",
    )


def find_state_file(run_dir: Path) -> Optional[Path]:
    candidates = [
        run_dir / "world" / "data" / "minecraftmatrixbridge" / "state.json",
        run_dir / "data" / "minecraftmatrixbridge" / "state.json",
    ]
    for p in candidates:
        if p.exists():
            return p
    return None


def wait_for_bridge_ready(
    module: str,
    run_dir: Path,
    mock: MockMatrix,
    proc: subprocess.Popen[str],
    out_queue: "queue.Queue[str]",
    timeout_s: int,
) -> Path:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            while True:
                line = out_queue.get_nowait()
                if not line.startswith("Downloading: "):
                    sys.stdout.write(f"[{module}] {line}")
        except queue.Empty:
            pass

        state = find_state_file(run_dir)
        counters = mock.counters
        if (
            counters.whoami_calls >= 1
            and counters.resolve_calls >= 1
            and counters.sync_calls >= 1
            and state is not None
        ):
            return state

        if proc.poll() is not None:
            break
        time.sleep(0.2)

    counters = mock.counters
    state = find_state_file(run_dir)
    raise RuntimeError(
        f"{module}: bridge did not become ready before timeout; "
        f"whoami={counters.whoami_calls} resolve={counters.resolve_calls} "
        f"sync={counters.sync_calls} state={state}"
    )


def event_tap_alias_for_module(module: str) -> str:
    if module == "forge-26":
        return "TickEvent.ServerTickEvent.Post"
    if module == "neoforge-26":
        return "ServerTickEvent.Post"
    raise ValueError(f"No event tap probe configured for module: {module}")


def wait_for_send_count(
    module: str,
    mock: MockMatrix,
    proc: subprocess.Popen[str],
    out_queue: "queue.Queue[str]",
    expected_count: int,
    timeout_s: int,
) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            while True:
                line = out_queue.get_nowait()
                if not line.startswith("Downloading: "):
                    sys.stdout.write(f"[{module}] {line}")
        except queue.Empty:
            pass

        if mock.counters.send_calls >= expected_count:
            return

        if proc.poll() is not None:
            break
        time.sleep(0.2)

    raise RuntimeError(
        f"{module}: expected at least {expected_count} Matrix send(s), got {mock.counters.send_calls}"
    )


def wait_for_event_index_ready(
    module: str,
    proc: subprocess.Popen[str],
    out_queue: "queue.Queue[str]",
    timeout_s: int,
) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            line = out_queue.get(timeout=0.5)
        except queue.Empty:
            if proc.poll() is not None:
                break
            continue

        if not line.startswith("Downloading: "):
            sys.stdout.write(f"[{module}] {line}")
        if "Event index built in" in line:
            return

    raise RuntimeError(f"{module}: event index did not finish building before timeout")


def _reader_thread(stream, out_queue: "queue.Queue[str]") -> None:
    for line in iter(stream.readline, ""):
        out_queue.put(line)
    stream.close()


def run_one(module: str, timeout_s: int) -> None:
    root = Path(__file__).resolve().parents[1]
    # ForgeGradle 6.x (legacy lines) and the 26.x line require different Gradle
    # launchers, so pick the wrapper that matches the module under test.
    modern_26 = module in {"forge-26", "neoforge-26"}
    if os.name == "nt":
        gradlew_name = "gradlew-26.bat" if modern_26 else "gradlew.bat"
    else:
        gradlew_name = "gradlew-26" if modern_26 else "gradlew"
    gradlew = root / gradlew_name

    run_dir = root / module / "run-systemtest"

    # Make each run clean and deterministic.
    if run_dir.exists():
        shutil.rmtree(run_dir)
    ensure_eula(run_dir)
    write_server_properties(run_dir, pick_free_port())
    (run_dir / "jna").mkdir(parents=True, exist_ok=True)

    token = "token"
    room_id = "!roomid:example.com"
    room_alias = "#room:example.com"
    self_user_id = "@bot:example.com"

    mock = MockMatrix(
        token=token, self_user_id=self_user_id, room_id=room_id, room_alias=room_alias
    )
    homeserver = mock.start()
    try:
        write_systemtest_config(run_dir, homeserver, room_alias)

        env = dict(os.environ)
        env["MATRIX_ACCESS_TOKEN"] = token
        env["GRADLE_USER_HOME"] = str(root / ".gradle-user-home")

        cmd = [str(gradlew), f":{module}:runServer", "--console=plain"]
        if modern_26:
            cmd.insert(1, "-Pomx_modern_26=true")
        proc = subprocess.Popen(
            cmd,
            cwd=str(root),
            env=env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )

        assert proc.stdout is not None
        assert proc.stdin is not None

        q: "queue.Queue[str]" = queue.Queue()
        t = threading.Thread(target=_reader_thread, args=(proc.stdout, q), daemon=True)
        t.start()

        started = False
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            try:
                line = q.get(timeout=0.5)
            except queue.Empty:
                if proc.poll() is not None:
                    break
                continue
            if line.startswith("Downloading: "):
                continue
            sys.stdout.write(f"[{module}] {line}")
            if "Done (" in line or 'For help, type "help"' in line:
                started = True
                break

        if not started:
            proc.terminate()
            raise RuntimeError(
                f"{module}: server did not reach 'Done' before timeout; exit={proc.poll()}"
            )

        state = wait_for_bridge_ready(module, run_dir, mock, proc, q, timeout_s=30)

        if module in {"forge-26", "neoforge-26"}:
            wait_for_event_index_ready(module, proc, q, timeout_s=30)
            event_alias = event_tap_alias_for_module(module)
            proc.stdin.write(f"matrix event on {event_alias}\n")
            proc.stdin.flush()
            wait_for_send_count(module, mock, proc, q, expected_count=1, timeout_s=15)
            proc.stdin.write(f"matrix event off {event_alias}\n")
            proc.stdin.flush()

        # Trigger MC -> Matrix via the built-in command.
        proc.stdin.write("matrix test\n")
        proc.stdin.flush()
        wait_for_send_count(
            module,
            mock,
            proc,
            q,
            expected_count=2 if module in {"forge-26", "neoforge-26"} else 1,
            timeout_s=15,
        )
        proc.stdin.write("stop\n")
        proc.stdin.flush()

        try:
            proc.wait(timeout=180)
        except subprocess.TimeoutExpired:
            proc.kill()
            raise RuntimeError(f"{module}: server did not stop after command timeout")

        if proc.returncode != 0:
            raise RuntimeError(
                f"{module}: run task failed with exit code {proc.returncode}"
            )

        counters = mock.counters
        if counters.whoami_calls < 1:
            raise RuntimeError(
                f"{module}: expected whoami call(s), got {counters.whoami_calls}"
            )
        if counters.resolve_calls < 1:
            raise RuntimeError(
                f"{module}: expected resolveRoomAlias call(s), got {counters.resolve_calls}"
            )
        if counters.sync_calls < 1:
            raise RuntimeError(
                f"{module}: expected sync call(s), got {counters.sync_calls}"
            )
        if counters.send_calls < 1:
            raise RuntimeError(
                f"{module}: expected send call(s) from /matrix test, got {counters.send_calls}"
            )

        print(
            f"[{module}] OK: whoami={counters.whoami_calls} resolve={counters.resolve_calls} sync={counters.sync_calls} send={counters.send_calls} state={state}"
        )
    finally:
        mock.stop()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--modules",
        nargs="*",
        default=[
            "forge-1.18",
            "forge-1.19",
            "forge-1.20",
            "forge-1.21",
            "forge-26",
            "neoforge-1.21",
            "neoforge-26",
        ],
        help="Gradle subprojects to system-test",
    )
    parser.add_argument(
        "--timeout-s",
        type=int,
        default=600,
        help="Per-module startup timeout in seconds",
    )
    args = parser.parse_args()

    for module in args.modules:
        print(f"=== System test: {module} ===")
        run_one(module, args.timeout_s)
        print()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
