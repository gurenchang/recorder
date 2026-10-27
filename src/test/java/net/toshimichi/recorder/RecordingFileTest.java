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
