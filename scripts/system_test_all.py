#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import queue
import re
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
from urllib.request import Request, urlopen


MODERN_MODULE_EVENT_ALIASES = {
    "forge-26.1": "TickEvent.ServerTickEvent.Post",
    "forge-26.2": "TickEvent.ServerTickEvent.Post",
    "neoforge-26.1": "ServerTickEvent.Post",
    "neoforge-26.2": "ServerTickEvent.Post",
}

MODULE_EVENT_ALIASES = {
    **MODERN_MODULE_EVENT_ALIASES,
    **{f"forge-{family}": "net.minecraftforge.event.TickEvent$ServerTickEvent"
       for family in ("1.18", "1.19", "1.20", "1.21")},
    "neoforge-1.21": "net.neoforged.neoforge.event.tick.ServerTickEvent$Post",
}


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
                "server-ip=127.0.0.1",
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
    try:
        return MODULE_EVENT_ALIASES[module]
    except KeyError:
        raise ValueError(f"No event tap probe configured for module: {module}") from None


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
    ready: threading.Event | None = None,
) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if ready is not None and ready.is_set():
            return
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


def _reader_thread(stream, out_queue: "queue.Queue[str]", event_index_ready: threading.Event | None = None) -> None:
    for line in iter(stream.readline, ""):
        if event_index_ready is not None and "Event index built in" in line:
            event_index_ready.set()
        out_queue.put(line)
    stream.close()


def verify_artifact(directory: Path, filename: str) -> Path:
    """Fail closed before starting a server with an unverified release artifact."""
    matches = []
    for line in (directory / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        digest, name = line.split(maxsplit=1)
        if name.removeprefix("*") == filename:
            matches.append(digest)
    if len(matches) != 1 or not re.fullmatch(r"[0-9a-f]{64}", matches[0]):
        raise ValueError(f"Missing or ambiguous checksum for {filename}")
    jar = directory / filename
    actual = hashlib.sha256(jar.read_bytes()).hexdigest()
    if actual != matches[0]:
        raise ValueError(f"SHA256 mismatch for {filename}")
    print(f"Verified artifact: {filename} SHA256={actual}", flush=True)
    return jar


def run_installer_with_retries(
    command: list[str], run_dir: Path, log_path: Path, attempts: int = 3
) -> None:
    """Retry transient loader dependency downloads without hiding final failure."""
    if attempts < 1:
        raise ValueError("Installer attempts must be positive")
    for attempt in range(1, attempts + 1):
        mode = "w" if attempt == 1 else "a"
        try:
            with log_path.open(mode, encoding="utf-8") as log:
                log.write(f"\n=== Installer attempt {attempt}/{attempts} ===\n")
                log.flush()
                subprocess.run(
                    command,
                    cwd=run_dir,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    check=True,
                    timeout=600,
                )
            return
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            if attempt == attempts:
                raise
            time.sleep(2 ** (attempt - 1))


def packaged_server_command(root: Path, module: str, run_dir: Path,
                            loader_coordinate: str | None = None,
                            artifact_dir: Path | None = None) -> list[str]:
    """Install the matching loader and load the built JAR as an ordinary mod."""
    build = (root / module / "build.gradle").read_text(encoding="utf-8")

    def version(name: str) -> str:
        match = re.search(rf"^\s*{name}\s*=\s*'([^']+)'", build, re.MULTILINE)
        if match is None:
            raise ValueError(f"Missing {name} in {module}/build.gradle")
        return match.group(1)

    properties = dict(
        line.split("=", 1)
        for line in (root / "gradle.properties").read_text(encoding="utf-8").splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )
    jar = root / module / "build" / "libs" / (
        f"{properties['mod_id']}-{module}.x-{properties['mod_version']}.jar"
    )
    if artifact_dir is not None:
        jar = verify_artifact(artifact_dir, jar.name)
    if not jar.is_file():
        raise FileNotFoundError(f"Build {module} before testing its packaged JAR: {jar}")

    if module.startswith("neoforge-"):
        loader_version = loader_coordinate or version('neo_version')
        coordinate = f"net/neoforged/neoforge/{loader_version}"
        filename = f"neoforge-{loader_version}-installer.jar"
        base = "https://maven.neoforged.net/releases"
    else:
        loader_version = loader_coordinate or f"{version('minecraft_version')}-{version('forge_version')}"
        coordinate = f"net/minecraftforge/forge/{loader_version}"
        filename = f"forge-{loader_version}-installer.jar"
        base = "https://maven.minecraftforge.net"

    installer = run_dir / filename
    request = Request(
        f"{base}/{coordinate}/{filename}",
        headers={"User-Agent": "MinecraftMatrixBridge-SystemTest"},
    )
    with urlopen(request, timeout=120) as response, installer.open("wb") as output:
        shutil.copyfileobj(response, output)
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else "java"
    run_installer_with_retries(
        [java, "-jar", str(installer), "--installServer", str(run_dir)],
        run_dir,
        run_dir / "installer.log",
    )
    mods = run_dir / "mods"
    mods.mkdir(exist_ok=True)
    shutil.copy2(jar, mods / jar.name)
    return installed_server_command(java, run_dir, coordinate)


def installed_server_command(java: str, run_dir: Path, coordinate: str) -> list[str]:
    """Use the installed argument file, or Forge's executable server shim."""
    args_file = run_dir / "libraries" / coordinate / (
        "win_args.txt" if os.name == "nt" else "unix_args.txt"
    )
    command = [java, "-Djna.tmpdir=./jna"]
    if args_file.is_file():
        return [*command, f"@{args_file}", "--nogui"]
    # Forge 1.20.3 installs a runnable shim instead of platform argument files.
    # Match the exact installed coordinate; never choose an arbitrary server JAR.
    if coordinate.startswith("net/minecraftforge/forge/"):
        loader_version = coordinate.rsplit("/", 1)[1]
        shim = run_dir / f"forge-{loader_version}-shim.jar"
        if shim.is_file():
            return [*command, "-jar", str(shim), "--nogui"]
    raise FileNotFoundError(f"Installer did not generate {args_file} or a matching Forge shim JAR")


def run_one(module: str, timeout_s: int, packaged: bool = False,
            loader_coordinate: str | None = None, artifact_dir: Path | None = None) -> None:
    root = Path(__file__).resolve().parents[1]
    # ForgeGradle 6.x (legacy lines) and the 26.x line require different Gradle
    # launchers, so pick the wrapper that matches the module under test.
    modern_26 = module in MODERN_MODULE_EVENT_ALIASES
    if module not in MODULE_EVENT_ALIASES:
        raise ValueError(f"Unknown server test module: {module}")
    if os.name == "nt":
        gradlew_name = "gradlew-26.bat" if modern_26 else "gradlew.bat"
    else:
        gradlew_name = "gradlew-26" if modern_26 else "gradlew"
    gradlew = root / gradlew_name

    run_dir = root / module / "run-systemtest"
    if loader_coordinate:
        # Keep cross-patch test logs separate from the default server test.
        run_dir = run_dir / loader_coordinate

    # Make each run clean and deterministic.
    if run_dir.exists():
        shutil.rmtree(run_dir)
    ensure_eula(run_dir)
    write_server_properties(run_dir, pick_free_port())
    (run_dir / "jna").mkdir(parents=True, exist_ok=True)
    packaged_cmd = packaged_server_command(root, module, run_dir, loader_coordinate, artifact_dir) if packaged else None

    token = "token"
    room_id = "!roomid:example.com"
    room_alias = "#room:example.com"
    self_user_id = "@bot:example.com"

    mock = MockMatrix(
        token=token, self_user_id=self_user_id, room_id=room_id, room_alias=room_alias
    )
    homeserver = mock.start()
    proc: Optional[subprocess.Popen[str]] = None
    try:
        write_systemtest_config(run_dir, homeserver, room_alias)

        env = dict(os.environ)
        env["MATRIX_ACCESS_TOKEN"] = token
        env.setdefault("GRADLE_USER_HOME", str(root / ".gradle-user-home"))

        cmd = [str(gradlew), f":{module}:runServer", "--console=plain"]
        if modern_26:
            cmd.insert(1, "-Pomx_modern_26=true")
        if packaged_cmd:
            cmd = packaged_cmd
        proc = subprocess.Popen(
            cmd,
            cwd=str(run_dir if packaged else root),
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
        event_index_ready = threading.Event()
        t = threading.Thread(target=_reader_thread, args=(proc.stdout, q, event_index_ready), daemon=True)
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

        if module in MODULE_EVENT_ALIASES:
            wait_for_event_index_ready(module, proc, q, timeout_s=60, ready=event_index_ready)
            # Brigadier requires quotes around nested class names containing '$'.
            event_alias = json.dumps(event_tap_alias_for_module(module))
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
            expected_count=2,
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
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=15)
        mock.stop()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact-dir", type=Path,
                        help="Use built release JARs from this directory; require SHA256SUMS.txt")
    parser.add_argument(
        "--packaged",
        action="store_true",
        help="Install real loaders and test built JARs (use the target Minecraft version's Java)",
    )
    parser.add_argument(
        "--modules",
        nargs="*",
        default=None,
        help="Gradle subprojects to system-test",
    )
    parser.add_argument(
        "--loader-coordinate",
        help="Test one packaged module against another loader (Forge: MC-Forge, NeoForge: version)",
    )
    parser.add_argument(
        "--timeout-s",
        type=int,
        default=600,
        help="Per-module startup timeout in seconds",
    )
    args = parser.parse_args()
    if args.artifact_dir is not None and not args.packaged:
        parser.error("--artifact-dir requires --packaged")
    if args.modules is None:
        args.modules = list(MODULE_EVENT_ALIASES)
    if any(m not in MODULE_EVENT_ALIASES for m in args.modules):
        parser.error("Unknown server test module")
    if args.loader_coordinate:
        if not args.packaged or len(args.modules) != 1:
            parser.error("--loader-coordinate requires --packaged and exactly one module")
        if not re.fullmatch(r"\d+(?:\.\d+)+(?:-[A-Za-z0-9]+(?:\.\d+)*)?", args.loader_coordinate):
            parser.error("Invalid loader coordinate")

    for module in args.modules:
        print(f"=== System test: {module} ===")
        run_one(module, args.timeout_s, packaged=args.packaged, loader_coordinate=args.loader_coordinate,
                artifact_dir=args.artifact_dir)
        print()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
