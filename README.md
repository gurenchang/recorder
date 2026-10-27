# Recorder

A Fabric mod for Minecraft 26.1.2 that records the raw packets a server sends you, then replays them later or turns them into a single player world.

This is a port of [Toshimichi0915/recorder](https://github.com/Toshimichi0915/recorder), originally a Forge mod for Minecraft 1.8.9.

**[Download the latest build](https://github.com/gurenchang/recorder/releases/latest/download/recorder.jar)** and drop it into `.minecraft/mods`. Requires [Fabric Loader](https://fabricmc.net/use/) 0.20.1 or later and [Fabric API](https://modrinth.com/mod/fabric-api).

## Usage

- **Record:** in-game, open the pause menu and press **Start Recording** (under Disconnect). It stops on **Stop Recording** or when you disconnect. Files go to `.minecraft/recordings/<date>.mcrc`. Works on servers and in singleplayer.
- **Replay:** on the title screen, press **R** (left of Multiplayer), pick a recording and press **Replay**. You watch as a spectator; left-click an entity to see through its eyes, sneak to let go. The pause menu then has Pause/Resume, a seek slider and a game mode switch, and Disconnect leaves the replay.
- **Convert to World:** in the same screen, saves every chunk the recording loaded into a new single player world (creative, with void everywhere else).

Recordings made by the 1.8.9 version cannot be replayed by this one: the packets are a different protocol.

The file format is documented in [`RECORDING_FORMAT.md`](src/main/resources/RECORDING_FORMAT.md).

## Building

Needs JDK 25.

```sh
./gradlew build
```

The mod jar is written to `build/libs/recorder.jar`.

## License

[MIT](LICENSE)
