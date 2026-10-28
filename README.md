# Recorder

A Fabric mod for Minecraft 26.1.2 that records the raw packets a server sends you, then replays them later or turns them into a single player world.

This is a port of [Toshimichi0915/recorder](https://github.com/Toshimichi0915/recorder), originally a Forge mod for Minecraft 1.8.9.

**[Download the latest build](https://github.com/gurenchang/recorder/releases/latest/download/recorder.jar)** and drop it into `.minecraft/mods`. Requires [Fabric Loader](https://fabricmc.net/use/) 0.20.1 or later and [Fabric API](https://modrinth.com/mod/fabric-api).

## Usage

- **Record:** in-game, open the pause menu and press **Start Recording** (under Disconnect). It stops on **Stop Recording** or when you disconnect. Files go to `.minecraft/recordings/<date>.mcrc`. Works on servers and in singleplayer.
- **Replay buffer:** turn on **Replay Buffer** next to Start Recording in the pause menu, and the last few minutes are always kept. Press **F8** (Save Replay Buffer) to save them as `recordings/<date>-buffer.mcrc`, which is N to 1.5N minutes long. After a disconnect or kick, **Save Buffer** in the Recordings screen saves what led up to it. N is `bufferMinutes` in `config/recorder.json` (1 to 30, default 5). The buffer and Start Recording run independently.
- **Mark:** while recording or buffering, press **F7** (Add Marker) to mark the moment, or type `/recmark <text>` to mark it with a label. The action bar shows the marker's number and time; replays show it again when they pass it. The keys can be changed under **Recorder** in Controls.
- **Replay:** on the title screen, press **R** (left of Multiplayer), pick a recording and press **Replay**. You watch as a spectator; left-click an entity to see through its eyes, sneak to let go. The pause menu then has Pause/Resume, a seek slider and a game mode switch, and Disconnect leaves the replay.
- **Convert to World:** in the same screen, saves every chunk the recording loaded into a new single player world (creative, with void everywhere else).
- **Export JSONL:** in the same screen, writes every row of the recording as one JSON object per line to `recordings/<name>.jsonl`, decoded by the game's own codecs. Without starting the game: `./gradlew exportRecording -Pmcrc=<path/to/recording.mcrc>`.

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
