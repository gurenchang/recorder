package net.toshimichi.recorder;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RecordingFileTest {

    @Test
    public void roundTripDropsTruncatedTail() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        Multimap<String, Property> properties = LinkedHashMultimap.create();
        properties.put("textures", new Property("textures", "skin", "signature"));
        properties.put("unsigned", new Property("unsigned", "value"));
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Steve", new PropertyMap(properties));
        RecordingFile.writeHeader(out, 1234L, 775, "26.1.2", profile);
        RecordingFile.writeEntry(out, 0, RecordingFile.CONFIGURATION_SNAPSHOT, new byte[]{4});
        RecordingFile.writeEntry(out, 0, RecordingFile.SNAPSHOT, new byte[]{1});
        RecordingFile.writeEntry(out, 50, RecordingFile.CLIENTBOUND, new byte[]{2, 3});
        RecordingFile.writeEntry(out, 70, RecordingFile.SERVERBOUND, new byte[0]);
        out.writeInt(99); // a crash mid-entry

        File file = File.createTempFile("recording", ".mcrc");
        file.deleteOnExit();
        Files.write(file.toPath(), bytes.toByteArray());

        RecordingFile.Contents contents = RecordingFile.readAll(file);
        assertEquals(775, contents.protocol);
        assertEquals("26.1.2", contents.gameVersion);
        assertEquals(profile.id(), contents.profile.id());
        assertEquals("Steve", contents.profile.name());
        assertEquals("signature", contents.profile.properties().get("textures").iterator().next().signature());
        assertNull(contents.profile.properties().get("unsigned").iterator().next().signature());
        List<RecordingFile.Entry> entries = contents.entries;
        assertEquals(4, entries.size());
        assertTrue(entries.get(0).isConfiguration());
        assertEquals(50, entries.get(2).time);
        assertEquals(RecordingFile.CLIENTBOUND, entries.get(2).direction);
        assertArrayEquals(new byte[]{2, 3}, entries.get(2).data);
        assertEquals(0, entries.get(3).data.length);
    }

    @Test
    public void markersReadBackAsWritten() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        RecordingFile.writeHeader(out, 1234L, 775, "26.1.2", new GameProfile(UUID.randomUUID(), "Steve"));
        RecordingFile.writeEntry(out, 10, RecordingFile.MARKER, RecordingFile.marker(RecordingFile.MARKER_KEY, ""));
        RecordingFile.writeEntry(out, 20, RecordingFile.MARKER, RecordingFile.marker(RecordingFile.MARKER_COMMAND, "ボス出現 phase 2"));
        RecordingFile.writeEntry(out, 30, (byte) 99, new byte[]{1, 2}); // a row from some newer writer

        File file = File.createTempFile("recording", ".mcrc");
        file.deleteOnExit();
        Files.write(file.toPath(), bytes.toByteArray());

        List<RecordingFile.Entry> entries = RecordingFile.readAll(file).entries;
        assertEquals(3, entries.size());
        assertEquals(new RecordingFile.Marker(RecordingFile.MARKER_KEY, ""), RecordingFile.readMarker(entries.get(0).data));
        assertEquals(new RecordingFile.Marker(RecordingFile.MARKER_COMMAND, "ボス出現 phase 2"), RecordingFile.readMarker(entries.get(1).data));
        assertEquals(20, entries.get(1).time);
        // neither markers nor unknown rows are packets, so a replay skips them
        assertFalse(entries.get(0).isPacket());
        assertFalse(entries.get(2).isPacket());
    }

    @Test
    public void packetRowsAreTheCodecDirections() {
        byte[][] cases = {
                {RecordingFile.CLIENTBOUND, 1}, {RecordingFile.SERVERBOUND, 1}, {RecordingFile.SNAPSHOT, 1},
                {RecordingFile.INPUT, 0}, {RecordingFile.CONFIGURATION_CLIENTBOUND, 1}, {RecordingFile.CONFIGURATION_SERVERBOUND, 1},
                {RecordingFile.CONFIGURATION_SNAPSHOT, 1}, {RecordingFile.MARKER, 0}, {8, 0}, {-1, 0},
        };
        for (byte[] c : cases) {
            assertEquals(c[1] == 1, new RecordingFile.Entry(0, c[0], new byte[0]).isPacket(), "direction " + c[0]);
        }
    }

    @Test
    public void formatsTimeToTheMillisecond() {
        Object[][] cases = {
                {0L, "0:00.000"}, {7L, "0:00.007"}, {83450L, "1:23.450"}, {59999L, "0:59.999"}, {600000L, "10:00.000"},
        };
        for (Object[] c : cases) assertEquals(c[1], RecordingFile.formatTime((Long) c[0]));
    }

    @Test
    public void readsVersion2AsLegacyProtocol() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(RecordingFile.MAGIC);
        out.writeInt(2);
        out.writeLong(1234L);
        out.writeLong(1);
        out.writeLong(2);
        out.writeUTF("Alex");
        out.writeInt(0);
        RecordingFile.writeEntry(out, 10, RecordingFile.CLIENTBOUND, new byte[]{1});

        File file = File.createTempFile("recording", ".mcrc");
        file.deleteOnExit();
        Files.write(file.toPath(), bytes.toByteArray());

        RecordingFile.Contents contents = RecordingFile.readAll(file);
        assertEquals(RecordingFile.LEGACY_PROTOCOL, contents.protocol);
        assertEquals("Alex", contents.profile.name());
        assertEquals(1, contents.entries.size());
    }
}
