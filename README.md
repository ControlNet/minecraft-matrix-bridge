# Minecraft Matrix Bridge (Forge, Server-Only)

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
- The “Matrix room connected: …” notice is localized server-side based on each player’s reported client language (currently includes `en_us` and `zh_cn`).
- If the bot is **invited** to the configured room but not joined yet, it will **auto-accept the invite** and join on startup.

## Requirements
- Java 17 (Forge 1.18–1.20) or Java 21 (Forge 1.21+)
- Minecraft Forge server matching the versions. I only manually test it in popular minor versions, and hopefully it works in other minor versions.
    - 1.18.2
    - 1.19.2
    - 1.20.1
    - 1.21.1
- Matrix room must be **unencrypted** (no E2EE)

## Installation
1. Download the jar or build the jar `./scripts/build_dist.sh` and you can find it in `dist/`.
2. Drop the jar into your Forge server’s `mods/` folder.
3. Start the server once to generate config.

### Build all versions (creates `dist/`)
To build jars for all supported Minecraft versions and collect them into `dist/`:
- `./scripts/build_dist.sh`

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
