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
- **MC → Matrix**: forwards player chat into a Matrix room as it appears in game, after other mods such as chat filters have rewritten or cancelled it.
- **Matrix → MC**: forwards Matrix `m.text` room messages into Minecraft server chat. Edits are shown as the new text with ` (edited)`, replies without the quoted original, and messages longer than 512 characters are cut off. After a disconnect, messages the homeserver left out of a sync are fetched and shown in order (up to 1000).
- Sync state stored in the **world save** (prevents replay spam on restart). The position is saved after its messages reach Minecraft, so a restart replays the batch the bridge was handling. Old room history is skipped on the very first start.
- The "Matrix room connected: …" notice appears once the bridge has caught up with the room; messages sent after it reach Minecraft.
- **Server-only**: vanilla clients can join without installing anything (no client mod).
- The "Matrix room connected: …" notice is localized server-side based on each player's reported client language (currently includes `en_us` and `zh_cn`).
- If the bot is **invited** to the configured room but not joined yet, it will **auto-accept the invite** and join on startup.
- **Event Taps**: dynamically subscribe to Forge/NeoForge events and forward them to Matrix for debugging/monitoring.

## Requirements
- Java 17 (Forge 1.18–1.20.4), Java 21 (Forge 1.20.5–1.20.6, Forge/NeoForge 1.21.x), or Java 25 (Forge/NeoForge 26.1 and 26.2)
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

## Configuration
Edit `config/minecraftmatrixbridge.toml`. Changes made while the server is running take effect after `/matrix reload` or a restart.
- `matrix.homeserver` (required) the client API base URL, e.g. `https://matrix.example.com`. If it returns 404 (for example when you entered the server name `https://example.com`), the bridge looks up `/.well-known/matrix/client` once (following redirects) and uses the delegated URL. If that also fails, it logs an error and retries until the homeserver answers.
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
- `bridge.maxQueueSize` (default `1000`) - Maximum outgoing message queue size. Event tap output can fill at most half of it, which leaves the rest for player chat.
- `bridge.dedupSize` (default `512`) - Event deduplication cache size

### Event Tap Options
- `bridge.enableEventTaps` (default `true`) - Enable or disable event taps feature entirely. When disabled, no event indexing occurs at startup.
- `bridge.maxActiveEventTaps` (default `10`) - Maximum concurrent event subscriptions
- `bridge.defaultEventThrottleMs` (default `1000`) - Minimum interval between forwarded events (prevents spam)
- `bridge.eventCommandMinPowerLevel` (default `100`) - Minimum Matrix power level required to use event commands from Matrix (0=default user, 50=moderator, 100=admin). The default is room admins because tap output can include player names and coordinates. Existing config files keep their saved value (older versions defaulted to `50`).

### Matrix rate limits
The bridge sends everything through one Matrix account. Lines that pile up while a message is being sent (a chat burst, event tap output) go out together as one Matrix message of up to 20 lines. Homeservers still rate-limit the account: Synapse's default `rc_message` allows about one message every 5 seconds with a burst of 10, which a busy server or an event tap can exceed. Exempt the bridge account from rate limits, for example with the Synapse admin API (needs a server admin's access token):

```bash
curl -X POST -H "Authorization: Bearer $SYNAPSE_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"messages_per_second": 0, "burst_count": 0}' \
  "https://matrix.example.com/_synapse/admin/v1/users/@minecraft-bot:example.com/override_ratelimit"
```

Load `SYNAPSE_ADMIN_TOKEN` from an untracked file such as `.env.local`.

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

Matrix event commands require the sender to have a power level >= `bridge.eventCommandMinPowerLevel` (default 100). In room version 12 and later, room creators always qualify.

Arguments may be quoted in both places: `"two words"` is one filter and `""` means no filter. Event taps also need `bridge.enableMcToMatrix=true`, because their output is sent to the Matrix room.

### Safety Features
- Master switch (`enableEventTaps`) - Disable entirely to skip event indexing at startup
- Maximum 10 concurrent event taps (configurable)
- Default 1-second throttle between forwarded events
- Tick events automatically enforce minimum 1-second throttle
- Each tap inspects at most 50 events per second (tick events: one per second); further events in that second are skipped, including ones that match the filter
- Tap output may fill at most half of the outgoing queue
- Event output truncated to 2048 characters
- Client-only events are rejected on dedicated servers

On NeoForge, parent events such as `BlockEvent`, `PlayerEvent` and `LivingEvent` are abstract and cannot be listened to; tap a subtype such as `BlockEvent.BreakEvent` instead. The bridge lists some subtypes when you try. Forge allows tapping these parents, but they also fire for many unrelated subtypes.

### Examples

```
# Monitor all chat events (fire once)
/matrix event on ServerChatEvent

# Monitor player logins for 10 minutes
/matrix event on PlayerLoggedInEvent "" 10m

# Monitor block breaks with "diamond" in the event data, permanently
/matrix event on BlockEvent.BreakEvent diamond permanent

# From Matrix
!mc event on ServerChatEvent
!mc event on PlayerLoggedInEvent "" 10m
!mc event list
!mc event off ServerChatEvent
```

## AI use declaration

Codex and cursor are used to develop this project.

## Acknowledgments

I appreciate the great mod projects (e.g. [Matrix Bridge](https://modrinth.com/mod/matrix-bridge)) that have inspired me to create this project.

## License

The mod is licensed under the AGPL-3.0 license.
