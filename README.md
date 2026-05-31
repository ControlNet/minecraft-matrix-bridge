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
- Java 17 (Forge 1.18–1.20), Java 21 (Forge/NeoForge 1.21.x), or Java 25 (Forge/NeoForge 26.1)
- Minecraft Forge/NeoForge server matching the versions. I only manually test it in popular minor versions, and hopefully it works in other minor versions.
    - 1.18.2
    - 1.19.2
    - 1.20.1
    - 1.21.1
    - 26.1
- Matrix room must be **unencrypted** (no E2EE)

## Installation
1. Download the jar or build it locally with `./scripts/build_dist.sh` (all supported versions) or `./scripts/build_dist_26.sh` (26.x only). The generated jars are written to `dist/`.
2. Drop the jar into your Forge/NeoForge server's `mods/` folder (pick the jar matching your loader + Minecraft major).
3. Start the server once to generate config.

### Build all versions (creates `dist/`)
To build jars for all supported Minecraft versions and collect them into `dist/`:
- `./scripts/build_dist.sh`

`dist/` includes Forge jars for 1.18.x–1.21.x and 26.x, plus NeoForge jars for 1.21.x and 26.x.

### Build only the 26.x line
The 26.x projects use the dedicated Gradle 9 wrapper and are conditionally included by `settings.gradle`.

- Build and collect both 26.x jars into `dist/`: `./scripts/build_dist_26.sh`
- Build Forge 26 only: `./gradlew-26 -Pomx_modern_26=true :forge-26:build`
- Build NeoForge 26 only: `./gradlew-26 -Pomx_modern_26=true :neoforge-26:build`
- On Windows, use `gradlew-26.bat` instead of `./gradlew-26`

`./scripts/build_dist_26.sh` produces:
- `dist/minecraftmatrixbridge-forge-26.x-<version>.jar`
- `dist/minecraftmatrixbridge-neoforge-26.x-<version>.jar`
- `dist/SHA256SUMS.txt`

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
