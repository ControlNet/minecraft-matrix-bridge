# Minecraft Matrix Bridge (Forge/NeoForge, Server-Only)

<div align="center">
    <img src="https://img.shields.io/github/stars/ControlNet/minecraft-matrix-bridge?style=flat-square">
    <img src="https://img.shields.io/github/forks/ControlNet/minecraft-matrix-bridge?style=flat-square">
    <a href="https://github.com/ControlNet/minecraft-matrix-bridge/issues"><img src="https://img.shields.io/github/issues/ControlNet/minecraft-matrix-bridge?style=flat-square"></a>
    <img src="https://img.shields.io/github/license/ControlNet/minecraft-matrix-bridge?style=flat-square">
    <a href="https://modrinth.com/mod/mc-matrix-bridge">
        <img src="https://img.shields.io/badge/Modrinth-mc--matrix--bridge-00AF5C?style=flat-square&logo=modrinth&logoColor=white">
    </a>
    <a href="https://www.curseforge.com/minecraft/mc-mods/mc-matrix-bridge">
        <img src="https://img.shields.io/badge/CurseForge-mc--matrix--bridge-F16436?style=flat-square&logo=curseforge&logoColor=white">
    </a>
</div>

Bridges chat between a Minecraft server and **one Matrix room** (unencrypted only).

## Features
- **MC → Matrix**: forwards player chat into a Matrix room.
- **Matrix → MC**: forwards Matrix `m.text` room messages into Minecraft server chat.
- **Server-only**: vanilla clients can join without installing anything (no client mod).
- Sync state stored in the **world save** (prevents replay spam on restart).
- The "Matrix room connected: …" notice is localized server-side based on each player's reported client language (currently includes `en_us` and `zh_cn`).
- If the bot is **invited** to the configured room but not joined yet, it will **auto-accept the invite** and join on startup.
- **Event Taps**: dynamically subscribe to Forge/NeoForge events and forward them to Matrix for debugging/monitoring.

## Requirements
- Java 17 (Forge 1.18–1.20), Java 21 (Forge/NeoForge 1.21.x), or Java 25 (Forge/NeoForge 26.1 and 26.2)
- Minecraft Forge/NeoForge server matching the versions. I only manually test it in popular minor versions, and hopefully it works in other minor versions.
    - 1.18.2
    - 1.19.2
    - 1.20.1
    - 1.21.1
    - 26.1.2
    - 26.2
- Matrix room must be **unencrypted** (no E2EE)

## Installation
1. Download the jar or build it locally with `./scripts/build_dist.sh` (all supported versions) or `./scripts/build_dist_26.sh` (26.x only). The generated jars are written to `dist/`.
2. Drop the jar into your Forge/NeoForge server's `mods/` folder (pick the jar matching your loader and Minecraft release; 26.1 and 26.2 use separate jars).
3. Start the server once to generate config.

### Build all versions (creates `dist/`)
To build jars for all supported Minecraft versions and collect them into `dist/`:
- `./scripts/build_dist.sh`

`dist/` includes nine jars: Forge for 1.18.x–1.21.x, 26.1.x, and 26.2.x; NeoForge for 1.21.x, 26.1.x, and 26.2.x.

### Build only the 26.x line
The 26.x projects use the dedicated Gradle 9 wrapper and are conditionally included by `settings.gradle`.

- Build and collect all four 26.x jars into `dist/`: `./scripts/build_dist_26.sh`
- Build Forge 26.1 only: `./gradlew-26 -Pomx_modern_26=true :forge-26.1:build`
- Build Forge 26.2 only: `./gradlew-26 -Pomx_modern_26=true :forge-26.2:build`
- Build NeoForge 26.1 only: `./gradlew-26 -Pomx_modern_26=true :neoforge-26.1:build`
- Build NeoForge 26.2 only: `./gradlew-26 -Pomx_modern_26=true :neoforge-26.2:build`
- On Windows, use `gradlew-26.bat` instead of `./gradlew-26`

`./scripts/build_dist_26.sh` produces:
- `dist/minecraftmatrixbridge-forge-26.1.x-<version>.jar`
- `dist/minecraftmatrixbridge-forge-26.2.x-<version>.jar`
- `dist/minecraftmatrixbridge-neoforge-26.1.x-<version>.jar`
- `dist/minecraftmatrixbridge-neoforge-26.2.x-<version>.jar`
- `dist/SHA256SUMS.txt`

The 26.2 modules target Forge 65.1.3 and NeoForge 26.2.0.88 respectively, require Java 25, and exclude Minecraft 26.3.

Run the 26.x server integration tests with:

```bash
python3 scripts/system_test_all.py --packaged --modules forge-26.1 neoforge-26.1 forge-26.2 neoforge-26.2 --timeout-s 600
```

Build the jars first and use Java 25 (`JAVA_HOME` or `PATH`). Packaged tests install each loader and load the actual jar from `mods/`; omit `--packaged` to use Gradle development runs. These tests use a local mock Matrix server and recreate each module's `run-systemtest` directory. Do not store valuable worlds there. They cover startup, Matrix connection/sync, persisted state, command-driven sends, event taps, and shutdown; real-player chat requires separate manual verification.

For cross-patch checks, `--loader-coordinate` selects a different loader for a
single built jar. It recreates only `run-systemtest/<coordinate>`; a default test
still recreates the entire parent directory, so run the default test first.
CI runs these four checks after the default 26.x tests:

```bash
python3 scripts/system_test_all.py --packaged --modules forge-26.1 --loader-coordinate 26.1-62.0.9 --timeout-s 600
python3 scripts/system_test_all.py --packaged --modules forge-26.1 --loader-coordinate 26.1.1-63.0.2 --timeout-s 600
python3 scripts/system_test_all.py --packaged --modules neoforge-26.1 --loader-coordinate 26.1.0.19-beta --timeout-s 600
python3 scripts/system_test_all.py --packaged --modules neoforge-26.1 --loader-coordinate 26.1.1.15-beta --timeout-s 600
```

Expected: each exits successfully with an `OK:` line and at least two outgoing
messages to the mock Matrix service. The 26.1 jar metadata permits Minecraft
`[26.1,26.2)`, with Forge 62.0.9+ or NeoForge 26.1.0.19-beta+; compilation still
targets Minecraft 26.1.2.

### Release metadata

`scripts/release-targets.json` is the shared publication policy for all nine jars.
Each Minecraft family includes its base release and **all published stable patch
versions**: `1.20` includes 1.20 through 1.20.6; `26.1` does not include 26.2.
Snapshots and pre-releases are excluded. This is the intended support policy,
not evidence that every patch has been runtime-tested.

Before any release upload, CI resolves Mojang's version catalog once, validates
every version on each enabled platform, and generates both publisher matrices
from that same list. CurseForge also receives the target's Java and loader tags
and the explicit `Server` tag (not `Client`);
missing or ambiguous IDs fail preflight instead of silently dropping tags.
The former `CURSEFORGE_GAME_VERSIONS_*` repository-variable overrides are no
longer used. Edit the shared target policy instead.

The resolved list is saved in the Actions summary and `release-metadata` artifact,
and attached to the GitHub release as `release-metadata.json`. Modrinth tags,
loader, release channel, version number, filename, and `server_only` environment
are checked after upload. Modrinth uploads explicitly set `environment: server_only`;
preflight also checks the configured project's environment. A mismatched project
setting stops publication rather than silently changing project settings. The
read-only CLI uses `MODRINTH_PROJECT_ID`, `--modrinth-project`, or the default
`mc-matrix-bridge` slug for this check. Older project responses are checked through
`client_side=unsupported` and `server_side=required` when `environment` is absent.
CurseForge's returned file ID is recorded; its post-moderation metadata is not
automatically read back with the upload-only credential. Both publishers use
the same explicit release channel and loader-specific display names.

Run the offline tests (synthetic API catalogs) and a read-only live preflight:

```bash
python3 -m unittest discover -s scripts -p 'test_release_metadata.py'
python3 scripts/release_metadata.py --check-modrinth
```

Expected: all tests pass, and the preflight prints five legacy and four modern
targets with full stable-version lists. For a CurseForge preflight, provide
`CURSEFORGE_TOKEN` through the environment and run:

```bash
python3 scripts/release_metadata.py --check-modrinth --check-curseforge
```

Never put credentials in the target JSON or command arguments. Local `.env`
files are gitignored; the script does not load them automatically. CI continues
to use the existing platform secrets. These commands never upload or edit files
on either platform. Publishing a new mod version re-resolves available Minecraft
patches; this does not retroactively update older uploads or schedule tag edits.

## Configuration
Edit `config/minecraftmatrixbridge.toml`:
- `matrix.homeserver` (required) e.g. `https://matrix.example.com`
- `matrix.roomId` (required) room ID `!abcdef:example.com` or room alias `#myroom:example.com` (the room **should not be encrypted**)
- `matrix.accessToken` (required, or use env variable `MATRIX_ACCESS_TOKEN`)

### Bridge Options
- `bridge.enableMcToMatrix` (default `true`) - Forward MC chat to Matrix
- `bridge.enableMatrixToMc` (default `true`) - Forward Matrix messages to MC
- `bridge.announceConnected` (default `true`) - Announce `Matrix room connected: {roomIdOrAlias}` when bridge starts
- `bridge.enableJoinLeaveToMatrix` (default `false`) - Send `{player} joined/left the game` to Matrix
- `bridge.enableServerLifecycleToMatrix` (default `false`) - Send `Server started/stopping` to Matrix
- `bridge.mcToMatrixPrefix` (default `[MC] `) - Prefix for MC messages in Matrix
- `bridge.matrixToMcPrefix` (default `[Matrix] `) - Prefix for Matrix messages in MC
- `bridge.matrixBotPrefix` (default `!mc`) - Matrix bot command prefix; messages starting with this prefix are treated as bot commands and are not forwarded to Minecraft chat
- `bridge.syncTimeoutMs` (default `30000`) - Matrix sync timeout in milliseconds
- `bridge.timelineLimit` (default `20`) - Maximum timeline events per sync
- `bridge.maxQueueSize` (default `1000`) - Maximum outgoing message queue size
- `bridge.dedupSize` (default `512`) - Event deduplication cache size

### Event Tap Options
- `bridge.enableEventTaps` (default `true`) - Enable or disable event taps feature entirely. When disabled, no event indexing occurs at startup.
- `bridge.maxActiveEventTaps` (default `10`) - Maximum concurrent event subscriptions
- `bridge.defaultEventThrottleMs` (default `1000`) - Minimum interval between forwarded events (prevents spam)
- `bridge.eventCommandMinPowerLevel` (default `50`) - Minimum Matrix power level required to use event commands from Matrix (0=default user, 50=moderator, 100=admin)

## World-save state
Sync state is stored at:
- `<world>/data/minecraftmatrixbridge/state.json`

## Commands

### Minecraft Commands (OP-only)
- `/matrix status` - Show bridge status
- `/matrix reload` - Reload bridge config
- `/matrix test` - Send a test message to Matrix

### Matrix Bot Commands
Matrix room bot commands (requires `bridge.enableMatrixToMc=true`):
- `!mc help` (or replace `!mc` with `bridge.matrixBotPrefix`)
- `!mc list` - Replies with online player names; command messages are not forwarded into Minecraft chat

## Event Taps (Advanced)

Event taps allow server operators to subscribe to Forge/NeoForge events at runtime and forward them to the Matrix room. This is useful for debugging and monitoring.

### Minecraft Commands (OP-only)

- `/matrix event on <eventName> [filter] [duration]` - Enable event tap
  - `eventName`: Simple class name (e.g., `ServerChatEvent`) or fully-qualified class name
  - `filter`: Optional substring filter (case-insensitive)
  - `duration`: `once` (default), `permanent`, or time-based (`30s`, `10m`, `2h`, `1d`)
- `/matrix event off <eventName>` - Disable event tap
- `/matrix event list` - List active event taps
- `/matrix event search <query>` - Search available events
- `/matrix event help` - Show help

### Matrix Bot Commands

Same commands available via Matrix (requires `bridge.enableMatrixToMc=true` and `bridge.enableEventTaps=true`):
- `!mc event on <eventName> [filter] [duration]`
- `!mc event off <eventName>`
- `!mc event list`
- `!mc event search <query>`
- `!mc event help`

Matrix event commands require the sender to have a power level >= `bridge.eventCommandMinPowerLevel` (default 50).

### Safety Features
- Master switch (`enableEventTaps`) - Disable entirely to skip event indexing at startup
- Maximum 10 concurrent event taps (configurable)
- Default 1-second throttle between forwarded events
- Tick events automatically enforce minimum 1-second throttle
- Event output truncated to 2048 characters
- Client-only events are rejected on dedicated servers

### Examples

```
# Monitor all chat events (fire once)
/matrix event on ServerChatEvent

# Monitor player logins for 10 minutes
/matrix event on PlayerLoggedInEvent "" 10m

# Monitor block breaks with "diamond" in the event data, permanently
/matrix event on BlockEvent diamond permanent

# From Matrix
!mc event on ServerChatEvent
!mc event list
!mc event off ServerChatEvent
```

## AI use declaration

Codex and cursor are used to develop this project.

## Acknowledgments

I appreciate the great mod projects (e.g. [Matrix Bridge](https://modrinth.com/mod/matrix-bridge)) that have inspired me to create this project.

## License

The mod is licensed under the AGPL-3.0 license.
