# Minecraft Matrix Bridge (Forge, Server-Only)

Bridges chat between a Minecraft server and **one Matrix room** (unencrypted only).

## Features
- **MC → Matrix**: forwards player chat into a Matrix room.
- **Matrix → MC**: forwards Matrix `m.text` room messages into Minecraft server chat.
- **Server-only**: vanilla clients can join without installing anything (no client mod).
- Sync state stored in the **world save** (prevents replay spam on restart).
- If the bot is **invited** to the configured room but not joined yet, it will **auto-accept the invite** and join on startup.

## Requirements
- Java 17 (Forge 1.18–1.20) or Java 21 (Forge 1.21+)
- Minecraft Forge server matching one of the built jars (see below)
- Matrix room must be **unencrypted** (no E2EE)

## Installation
1. Download the jar or build the jar for your Minecraft major version (example: Forge 1.20.x):
   - `GRADLE_USER_HOME=.gradle-user-home ./gradlew :forge-1.20:build`
   - Output: `forge-1.20/build/libs/minecraftmatrixbridge-forge-1.20.x-0.1.0.jar`
2. Drop the jar into your Forge server’s `mods/` folder.
3. Start the server once to generate config.

## Configuration
Edit `config/minecraftmatrixbridge.toml`:
- `matrix.homeserver` (required) e.g. `https://matrix.example.com`
- `matrix.roomId` (required) room ID `!abcdef:example.com` or room alias `#myroom:example.com` (the room **should not be encrypted**)
- `matrix.accessToken` (required, or use env variable `MATRIX_ACCESS_TOKEN`)

Optional:
- `bridge.enableMcToMatrix` (default `true`)
- `bridge.enableMatrixToMc` (default `true`)
- `bridge.announceConnected` (default `true`) announces `Matrix room connected: {roomIdOrAlias}`
- `bridge.enableJoinLeaveToMatrix` (default `false`) sends `{player} joined/left the game` to Matrix
- `bridge.enableServerLifecycleToMatrix` (default `false`) sends `Server started/stopping` to Matrix
- `bridge.mcToMatrixPrefix` (default `[MC] `)
- `bridge.matrixToMcPrefix` (default `[Matrix] `)
- `bridge.syncTimeoutMs` (default `30000`)
- `bridge.timelineLimit` (default `20`)
- `bridge.maxQueueSize` (default `1000`)
- `bridge.dedupSize` (default `512`)

## World-save state
Sync state is stored at:
- `<world>/data/minecraftmatrixbridge/state.json`

## Commands
OP-only:
- `/matrix status`: show bridge status
- `/matrix reload`: reload bridge config
- `/matrix test`: send a test message to Matrix

## AI use declaration

Codex and cursor are used to develop this project.

## Acknowledgments

I appreciate the great mod projects (e.g. [Matrix Bridge](https://modrinth.com/mod/matrix-bridge)) that have inspired me to create this project.

## License

The mod is licensed under the AGPL-3.0 license.
