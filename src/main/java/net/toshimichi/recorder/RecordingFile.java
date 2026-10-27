package net.toshimichi.recorder;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * On-disk layout, all big-endian:
 * <pre>
 * int   magic "MCRC"
 * int   version (3)
 * long  recording start, epoch millis
 * int   network protocol version                         (version 3 and up)
 * UTF   game version name, e.g. "26.1.2"                  (version 3 and up)
 * long  recording player's UUID, most significant bits   (version 2 and up)
 * long  recording player's UUID, least significant bits
 * UTF   recording player's name
 * int   property count, then per property: UTF name, UTF value, bool signed, [UTF signature]   (version 2 and up)
 * then until EOF:
 *   int   time since start, millis
 *   byte  direction: 0 clientbound, 1 serverbound, 2 synthetic clientbound (world snapshot taken at start),
 *                    3 input event, 4 clientbound configuration, 5 serverbound configuration,
 *                    6 synthetic clientbound configuration (registry snapshot taken at start)
 *   int   length
 *   bytes packet exactly as it crossed the wire after decryption and decompression: varint id + payload;
 *         for an input event: byte device (0 keyboard, 1 mouse), int GLFW key or button code, byte pressed
 * </pre>
 */
public final class RecordingFile {

    public static final int MAGIC = 0x4D435243;
    public static final int VERSION = 3;
    public static final int LEGACY_PROTOCOL = 47; // versions 1 and 2 were all written by the 1.8.9 mod
    public static final byte CLIENTBOUND = 0;
    public static final byte SERVERBOUND = 1;
    public static final byte SNAPSHOT = 2;
    public static final byte INPUT = 3;
    public static final byte CONFIGURATION_CLIENTBOUND = 4;
    public static final byte CONFIGURATION_SERVERBOUND = 5;
    public static final byte CONFIGURATION_SNAPSHOT = 6;
    public static final byte KEYBOARD = 0;
    public static final byte MOUSE = 1;

    public static final class Entry {

        public final int time;
        public final byte direction;
        public final byte[] data;

        public Entry(int time, byte direction, byte[] data) {
            this.time = time;
            this.direction = direction;
            this.data = data;
        }

        public boolean isConfiguration() {
            return direction == CONFIGURATION_CLIENTBOUND || direction == CONFIGURATION_SERVERBOUND || direction == CONFIGURATION_SNAPSHOT;
        }

        public boolean isServerbound() {
            return direction == SERVERBOUND || direction == CONFIGURATION_SERVERBOUND;
        }
    }

    public static final class Contents {

        public final int protocol;
        public final String gameVersion; // "1.8.9" before version 3
        public final GameProfile profile; // null for a version 1 file
        public final List<Entry> entries;

        public Contents(int protocol, String gameVersion, GameProfile profile, List<Entry> entries) {
            this.protocol = protocol;
            this.gameVersion = gameVersion;
            this.profile = profile;
            this.entries = entries;
        }
    }

    private RecordingFile() {
    }

    public static void writeHeader(DataOutput out, long startMillis, int protocol, String gameVersion, GameProfile profile) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(startMillis);
        out.writeInt(protocol);
        out.writeUTF(gameVersion);
        out.writeLong(profile.id().getMostSignificantBits());
        out.writeLong(profile.id().getLeastSignificantBits());
        out.writeUTF(profile.name());
        // the textures property is the skin and cape, signed by Mojang
        out.writeInt(profile.properties().size());
        for (Property property : profile.properties().values()) {
            out.writeUTF(property.name());
            out.writeUTF(property.value());
            out.writeBoolean(property.hasSignature());
            if (property.hasSignature()) out.writeUTF(property.signature());
        }
    }

    public static void writeEntry(DataOutput out, int time, byte direction, byte[] data) throws IOException {
        out.writeInt(time);
        out.writeByte(direction);
        out.writeInt(data.length);
        out.write(data);
    }

    // ponytail: whole file in memory; stream from disk if recordings outgrow the heap
    public static Contents readAll(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a recording: " + file.getName());
            int version = in.readInt();
            in.readLong();
            int protocol = version >= 3 ? in.readInt() : LEGACY_PROTOCOL;
            String gameVersion = version >= 3 ? in.readUTF() : "1.8.9";
            GameProfile profile = null;
            if (version >= 2) {
                UUID id = new UUID(in.readLong(), in.readLong());
                String name = in.readUTF();
                Multimap<String, Property> properties = LinkedHashMultimap.create();
                for (int i = in.readInt(); i > 0; i--) {
                    String key = in.readUTF();
                    String value = in.readUTF();
                    properties.put(key, new Property(key, value, in.readBoolean() ? in.readUTF() : null));
                }
                profile = new GameProfile(id, name, new PropertyMap(properties));
            }

            List<Entry> entries = new ArrayList<>();
            try {
                while (true) {
                    int time = in.readInt();
                    byte direction = in.readByte();
                    byte[] data = new byte[in.readInt()];
                    in.readFully(data);
                    entries.add(new Entry(time, direction, data));
                }
            } catch (EOFException e) {
                // a crash mid-write leaves a partial tail; everything before it is still good
                return new Contents(protocol, gameVersion, profile, entries);
            }
        }
    }
}
