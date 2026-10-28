# Recorder `.mcrc` file format

Written by the Recorder mod for Minecraft 26.1.2. Files live in
`<.minecraft>/recordings/<yyyy-MM-dd_HH-mm-ss>.mcrc`, one per recording. Everything is
big-endian. The reference implementation is `net/toshimichi/recorder/RecordingFile.java`
in this jar.

Versions 1 and 2 were written by the Minecraft 1.8.9 build of this mod (protocol 47). Their
container is read the same way, but their packets are 1.8.9 packets, which this build cannot
replay.

## Layout

```
Header
  int32   magic        0x4D435243  ("MCRC")
  int32   version      3
  int64   start        recording start, Unix epoch milliseconds
  int32   protocol     network protocol version of the game that recorded it (version 3 and up)
  string  game_version game version name, e.g. "26.1.2"                       (version 3 and up)
  int64   uuid_msb     recording player's UUID, most significant bits    (version 2 and up)
  int64   uuid_lsb     recording player's UUID, least significant bits   (version 2 and up)
  uint16  name_length  byte count of `name`                              (version 2 and up)
  bytes   name         recording player's name, Java modified UTF-8      (version 2 and up)
  int32   count        number of profile properties                      (version 2 and up)
  Property             `count` times                                     (version 2 and up)

Property
  string  name         e.g. "textures"
  string  value        base64 JSON, as Mojang's session server returns it
  uint8   signed       1 if a signature follows, 0 if not
  string  signature    base64 Yggdrasil signature                        (only if `signed` is 1)

string = uint16 byte count, then that many bytes of Java modified UTF-8

Entries, repeated until end of file
  int32   time         milliseconds since `start`
  uint8   direction    see below
  int32   length       byte count of `data`
  bytes   data         `length` bytes
```

The player fields identify the account that made the recording, which may differ from
the account that later plays it back. Version 1 files stop the header after `start` and
carry no player; a replayer falls back to the viewer's own account. Minecraft names and
property values are ASCII, where modified UTF-8 is plain UTF-8.

The `textures` property is the recording player's skin and cape. It is there because the
server's tab list entry for the player may lack it (offline mode). A replayer should only
fall back to it then: a skin the server did list, such as one set by a nick plugin, is what
other players saw. The count may be 0 if the session server could not be reached.

A recording that ended in a crash may stop in the middle of an entry. Drop the partial
tail; everything before it is intact.

## Direction values

| value | meaning | `data` contents |
|------:|---------|-----------------|
| 0 | clientbound, play | raw packet from server to client |
| 1 | serverbound, play | raw packet from client to server |
| 2 | snapshot, play | synthetic clientbound play packet, see below |
| 3 | input | keyboard or mouse button event, see below |
| 4 | clientbound, configuration | raw packet from server to client |
| 5 | serverbound, configuration | raw packet from client to server |
| 6 | snapshot, configuration | synthetic clientbound configuration packet, see below |
| 7 | marker | a point the player marked, see below |

**Readers must skip rows whose direction they do not know.** New kinds of row are added as
new direction values without changing the header `version`, so an older reader keeps working
on a newer file by ignoring what it does not understand.

Configuration rows appear when the server sends the client back to the configuration phase
mid-session (a proxy switching servers, for one), and again in the snapshot.

## Packet data (direction 0, 1, 2, 4, 5, 6)

The bytes are the packet exactly as it crossed the wire after decryption and
decompression, with the length prefix removed:

```
varint  packet id    (id within the PLAY or CONFIGURATION state of the recorded protocol, for the given direction)
bytes   packet body  (vanilla encoding for that packet)
```

Recording starts while already in the PLAY state, so no HANDSHAKE, STATUS or LOGIN
packets ever appear. Packet ids follow the vanilla tables of the protocol in the header.
Play packets reference synced registries (dimension types, biomes, damage types...) by
numeric id; those ids are the order the configuration snapshot lists the entries in.
Bundles appear as on the wire: a bundle delimiter, the bundled packets, another delimiter.
Mod channels, if any, arrive as custom payload packets like any other.

Entries are in the order the client processed them. Timestamps come from the client's
wall clock when the packet passed the codec, not from any server clock. In singleplayer
the integrated server hands packets over as objects; the recorder encodes them the way the
network codec would have.

## Snapshot entries (direction 6, then 2)

Recording can start in the middle of a session, so the recorder first writes a synthetic
set of clientbound packets describing what the client knew at that instant. They all carry
`time` 0 and are encoded exactly like real packets so a replayer can feed them unchanged.

Direction 6, in the configuration protocol: enabled features, server brand, one Registry
Data per synced registry with every entry in full (no known packs are assumed), Update Tags
for the synced and built-in registries, then Finish Configuration.

Direction 2, in the play protocol: Login, spawn position, the tab list (Player Info Update)
with its header and footer, time and world clocks, chunk cache center, one Chunk Data And
Light per loaded chunk (block entities ride along), map contents, rain and thunder levels,
world border, scoreboard, the player's inventory, held slot, health, experience and
abilities, Player Position, then an Entity Metadata for the player's own entity id holding
its non-default values (flags, pose, skin layers, golden hearts). Then a spawn for every
other entity in view, at the position the server last sent rather than where the client was
gliding it, with its metadata and equipment; then attributes, potion effects, passengers and
leashes for all of them, the player included; and last the Game Event that starts chunk
loading.

NPCs (players with no tab list entry) are listed in the snapshot's tab list so they can be
spawned, then removed again with Player Info Remove. Chat sessions are not included.

They were never sent by the server. Analysis of server behaviour should skip directions 2
and 6. Fields the client did not know are filled with placeholders, e.g. max players is 20.

## Input entries (direction 3)

Keyboard and mouse button transitions made while in the world (not while a GUI screen was
open). Key repeats, mouse motion and scroll are not recorded.

```
uint8   device    0 keyboard, 1 mouse
int32   code      GLFW key code (keyboard) or button index (mouse: 0 left, 1 right, 2 middle)
uint8   pressed   1 pressed, 0 released
```

Key codes are GLFW `GLFW_KEY_*` values (W = 87, A = 65, S = 83, D = 68, Space = 32,
Left Shift = 340, Left Control = 341, Escape = 256). They share the same `time` base as
packets, so a key press can be matched against the movement packets it caused.

## Marker entries (direction 7)

A point the player marked while recording ("here"), with a label if they gave one. Markers
share the same `time` base as packets.

```
uint8   source    0 key (the Add Marker key binding, F7 by default), 1 command (/recmark <text>)
string  label     uint16 byte count, then that many bytes of Java modified UTF-8; empty for a key
```

Markers are numbered from 1 in file order; the recorder shows that number when one is made
("Marker #3 at 1:23.450") and the replay shows it again when playback passes it.

## JSONL export

Rather than decoding packets by hand, a recording can be turned into one JSON object per
line, decoded by the game's own codecs: the **Export JSONL** button in the Recordings
screen, or without starting the game:

```sh
./gradlew exportRecording -Pmcrc="C:/.../recordings/2026-10-28_12-00-00.mcrc"
```

Either writes `<name>.jsonl` next to `<name>.mcrc`. The synced registries are rebuilt from the
recording's configuration rows the way a joining client does, so packets that name items,
entities or sounds decode. The first line is the header, then one line per row, in file order:

```json
{"header":{"version":3,"start":1761620000000,"protocol":775,"game":"26.1.2","player":"name","uuid":"..."}}
{"t":1234,"dir":"s2c","id":93,"type":"minecraft:set_entity_data","p":{...}}
{"t":1250,"dir":"c2s","id":29,"type":"minecraft:move_player_pos_rot","p":{...}}
{"t":1300,"dir":"input","device":"key","code":87,"pressed":true}
{"t":1400,"dir":"mark","source":"key","label":""}
{"t":1500,"dir":"s2c","id":12,"error":"<exception>","hex":"<first 64 bytes>"}
```

- `dir`: `s2c` (0), `c2s` (1), `snap` (2), `input` (3), `cfg_s2c` (4), `cfg_c2s` (5),
  `cfg_snap` (6), `mark` (7); an unknown direction is `"unknown:<n>"` with `hex`.
- `id` is the packet id, `type` the packet type (`packet.type().id()`); bundle delimiters
  are rows too.
- A row that fails to decode becomes an error row with `error` and `hex`; the rest of the
  file is still written. The number of rows and errors, and a count per packet type, go to
  the log.

`p` is the packet's own fields, by name (records by component, other classes by their
instance fields, superclasses included), as the packet class holds them: a sound's `x` is the
fixed-point value on the wire, eight times the block coordinate. Values convert as follows:

| value | JSON |
|---|---|
| text component | `{"text": <plain text>, "json": <component JSON>}`; legacy `§` codes stay in `text` |
| item stack | the item stack codec's JSON (`{}` for an empty one) |
| holder, resource key, registered value (entity type, block, item, sound...) | its id, `"minecraft:zombie"`; a direct holder with no id is its contents |
| tag key | `"#minecraft:logs"` |
| block state | `"minecraft:stone_slab[type=top,waterlogged=false]"` |
| entity metadata value | `{"i": <index>, "s": <serializer id>, "v": <value>}` |
| `Vec3`, `BlockPos`, `SectionPos`, `ChunkPos` | an array of numbers |
| UUID | a string |
| enum | its `name()` |
| `Optional` and the like | its contents, or `null` |
| collection, array | an array; a map with string keys is an object, any other map an array of `[key, value]` |
| `byte[]`, `long[]`, buffers, primitive arrays over 256 long | `{"bytes": <size>}` |
| number | as is; NaN and infinities are `null` |
| NBT | its JSON |
| anything else | `toString()` |

Chunk data (`level_chunk_with_light`, `light_update`, `chunks_biomes`) is only its position and
size in bytes, `{"x":..,"z":..,"bytes":..}`. `section_blocks_update` lists every changed block
as `{"pos":[x,y,z],"block":"..."}`.

## Minimal reader (Python)

```python
import struct
import uuid

def read_string(f):
    return f.read(struct.unpack(">H", f.read(2))[0]).decode("utf-8")

def read_mcrc(path):
    with open(path, "rb") as f:
        magic, version, start = struct.unpack(">iiq", f.read(16))
        assert magic == 0x4D435243 and version in (1, 2, 3)
        if version >= 3:
            protocol = struct.unpack(">i", f.read(4))[0]
            print("game", read_string(f), "protocol", protocol)
        if version >= 2:
            player = uuid.UUID(bytes=f.read(16))
            print("recorded by", read_string(f), player)
        for _ in range(struct.unpack(">i", f.read(4))[0] if version >= 2 else 0):
            name, value = read_string(f), read_string(f)
            if f.read(1)[0]:
                read_string(f)               # signature
            print("property", name, len(value), "chars")
        while True:
            head = f.read(9)
            if len(head) < 9:
                return                      # clean end, or truncated tail
            time, direction, length = struct.unpack(">iBi", head)
            data = f.read(length)
            if len(data) < length:
                return
            yield start, time, direction, data

def read_varint(data):
    value, shift = 0, 0
    for i, b in enumerate(data):
        value |= (b & 0x7F) << shift
        if not b & 0x80:
            return value, data[i + 1:]
        shift += 7

NAMES = {0: "S->C", 1: "C->S", 4: "S->C config", 5: "C->S config"}

for start, t, direction, data in read_mcrc("recording.mcrc"):
    if direction in NAMES:
        packet_id, body = read_varint(data)
        print(t, NAMES[direction], hex(packet_id), len(body))
    elif direction == 3:
        device, code, pressed = struct.unpack(">BiB", data)
        print(t, "key" if device == 0 else "mouse", code, "down" if pressed else "up")
    elif direction == 7:
        source, size = struct.unpack(">BH", data[:3])
        print(t, "marker", "key" if source == 0 else "command", data[3:3 + size].decode("utf-8"))
    # any other direction: skip it
```
