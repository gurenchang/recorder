package net.toshimichi.recorder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.KnownPacksManager;
import net.minecraft.client.multiplayer.RegistryDataCollector;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntConsumer;

/**
 * Every row of a recording as one JSON object per line, decoded by the game's own codecs. Rebuilds the synced
 * registries from the recording's configuration rows the way a joining client does, so play packets that name items,
 * entities or sounds decode. Uses no mixin accessors: it also runs without the game, from ./gradlew exportRecording.
 */
public final class JsonlExport {

    private static final Logger log = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final int HEX_BYTES = 64;

    public record Result(int lines, int errors, Map<String, Integer> types) {
    }

    private final Map<String, Integer> types = new TreeMap<>();
    private int errors;
    // configuration being replayed: the next Finish Configuration turns it into the registries play rows decode with
    private RegistryDataCollector collector;
    private KnownPacksManager knownPacks;
    private RegistryAccess.Frozen registries;
    private String registriesUnavailable = "no configuration before it";
    private JsonValues values = new JsonValues(base());

    private JsonlExport() {
    }

    // progress gets the number of rows written so far
    public static Result export(File recording, File jsonl, IntConsumer progress) throws IOException {
        RecordingFile.Contents contents = RecordingFile.readAll(recording);
        if (contents.protocol != SharedConstants.getProtocolVersion()) {
            throw new IOException("Recorded on Minecraft " + contents.gameVersion + ", which this version cannot decode");
        }
        JsonlExport export = new JsonlExport();
        int lines = 0;
        try (Writer out = new BufferedWriter(Files.newBufferedWriter(jsonl.toPath(), StandardCharsets.UTF_8))) {
            out.write(GSON.toJson(header(contents)));
            out.write('\n');
            for (RecordingFile.Entry entry : contents.entries) {
                out.write(GSON.toJson(export.row(entry)));
                out.write('\n');
                if (++lines % 500 == 0) progress.accept(lines);
            }
        }
        int errors = export.errors + export.values.errors();
        log.info("Exported {} rows of {} to {}, {} errors", lines, recording.getName(), jsonl.getName(), errors);
        export.types.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(type -> log.info("  {} {}", type.getValue(), type.getKey()));
        return new Result(lines, errors, export.types);
    }

    // <name>.jsonl next to <name>.mcrc
    public static File jsonlFor(File recording) {
        return new File(recording.getParentFile(), recording.getName().replaceFirst("\\.mcrc$", "") + ".jsonl");
    }

    // the client's built-in registries, before any server has said anything
    private static RegistryAccess.Frozen base() {
        return ClientRegistryLayer.createRegistryAccess().compositeAccess();
    }

    private static JsonObject header(RecordingFile.Contents contents) {
        JsonObject header = new JsonObject();
        header.addProperty("version", contents.version);
        header.addProperty("start", contents.start);
        header.addProperty("protocol", contents.protocol);
        header.addProperty("game", contents.gameVersion);
        header.addProperty("player", contents.profile != null ? contents.profile.name() : null);
        header.addProperty("uuid", contents.profile != null ? contents.profile.id().toString() : null);
        JsonObject root = new JsonObject();
        root.add("header", header);
        return root;
    }

    private JsonObject row(RecordingFile.Entry entry) {
        JsonObject row = new JsonObject();
        row.addProperty("t", entry.time);
        row.addProperty("dir", direction(entry.direction));
        try {
            switch (entry.direction) {
                case RecordingFile.INPUT -> {
                    row.addProperty("device", entry.data[0] == RecordingFile.KEYBOARD ? "key" : "mouse");
                    row.addProperty("code", ((entry.data[1] & 0xFF) << 24) | ((entry.data[2] & 0xFF) << 16) | ((entry.data[3] & 0xFF) << 8) | (entry.data[4] & 0xFF));
                    row.addProperty("pressed", entry.data[5] != 0);
                    count("input");
                }
                case RecordingFile.MARKER -> {
                    RecordingFile.Marker marker = RecordingFile.readMarker(entry.data);
                    row.addProperty("source", marker.source() == RecordingFile.MARKER_KEY ? "key" : "command");
                    row.addProperty("label", marker.label());
                    count("mark");
                }
                default -> {
                    if (!entry.isPacket()) {
                        row.addProperty("hex", hex(entry.data));
                        count("unknown");
                    } else {
                        packet(entry, row);
                    }
                }
            }
        } catch (Exception e) {
            // one bad row must not cost the rest of the file
            errors++;
            count("error");
            row.remove("p");
            row.addProperty("error", e.toString());
            row.addProperty("hex", hex(entry.data));
        }
        return row;
    }

    private void packet(RecordingFile.Entry entry, JsonObject row) throws IOException {
        row.addProperty("id", Protocols.packetId(entry.data));
        Packet<?> packet;
        if (entry.isConfiguration()) {
            packet = Protocols.decode(Protocols.configuration(entry.isServerbound()), entry.data);
            if (!entry.isServerbound()) configure(packet);
        } else {
            if (registries == null) throw new IOException("Registries unavailable: " + registriesUnavailable);
            packet = Protocols.decode(entry.isServerbound() ? Protocols.serverboundPlay(registries) : Protocols.clientboundPlay(registries), entry.data);
        }
        String type = packet.type().id().toString();
        row.addProperty("type", type);
        row.add("p", values.packet(packet, entry.data.length));
        count(type);
    }

    // like ClientConfigurationPacketListenerImpl: collect, then freeze on Finish Configuration
    private void configure(Packet<?> packet) {
        if (collector == null) {
            collector = new RegistryDataCollector();
            knownPacks = null;
        }
        if (packet instanceof ClientboundRegistryDataPacket data) {
            collector.appendContents(data.registry(), data.entries());
        } else if (packet instanceof ClientboundUpdateTagsPacket tags) {
            collector.appendTags(tags.getTags());
        } else if (packet instanceof ClientboundSelectKnownPacks select) {
            // a real reconfiguration may leave entries out that the client is to read from its own vanilla data
            if (knownPacks == null) knownPacks = new KnownPacksManager();
            knownPacks.trySelectingPacks(select.knownPacks());
        } else if (packet instanceof ClientboundFinishConfigurationPacket) {
            try {
                // the built-in registries' tags too, as a remote join does: synced entries name them (an enchantment's
                // #minecraft:enchantable/mace). They land on this JVM's built-in registries, as a replay's would
                if (knownPacks == null) {
                    registries = collector.collectGameRegistries(ResourceProvider.EMPTY, base(), false);
                } else {
                    try (CloseableResourceManager resources = knownPacks.createResourceManager()) {
                        registries = collector.collectGameRegistries(resources, base(), false);
                    }
                }
                errors += values.errors();
                values = new JsonValues(registries);
            } catch (RuntimeException e) {
                log.warn("Could not rebuild the registries; the play rows after this will be errors", e);
                registries = null;
                registriesUnavailable = e.toString();
            }
            collector = null;
        }
    }

    private void count(String type) {
        types.merge(type, 1, Integer::sum);
    }

    private static String direction(byte direction) {
        return switch (direction) {
            case RecordingFile.CLIENTBOUND -> "s2c";
            case RecordingFile.SERVERBOUND -> "c2s";
            case RecordingFile.SNAPSHOT -> "snap";
            case RecordingFile.INPUT -> "input";
            case RecordingFile.CONFIGURATION_CLIENTBOUND -> "cfg_s2c";
            case RecordingFile.CONFIGURATION_SERVERBOUND -> "cfg_c2s";
            case RecordingFile.CONFIGURATION_SNAPSHOT -> "cfg_snap";
            case RecordingFile.MARKER -> "mark";
            default -> "unknown:" + (direction & 0xFF);
        };
    }

    private static String hex(byte[] data) {
        return HexFormat.of().formatHex(data, 0, Math.min(data.length, HEX_BYTES));
    }

    // ./gradlew exportRecording -Pmcrc=<file>: no game, no mixins, just the codecs
    public static void main(String[] args) throws IOException {
        if (args.length != 1 || args[0].isEmpty()) {
            System.err.println("usage: ./gradlew exportRecording -Pmcrc=<recording.mcrc>");
            System.exit(2);
        }
        SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        File recording = new File(args[0]);
        File jsonl = jsonlFor(recording);
        Result result = export(recording, jsonl, lines -> {
        });
        System.out.println("Wrote " + jsonl + ": " + result.lines() + " rows, " + result.errors() + " errors");
        System.exit(0); // the bootstrap leaves worker threads behind
    }
}
