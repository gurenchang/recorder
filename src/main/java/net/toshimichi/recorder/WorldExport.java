package net.toshimichi.recorder;

import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Writes every chunk a replay loads into a single player world, each as it last stood before it was unloaded.
 * Only the last world counts: a lobby or dimension change throws away what came before it.
 */
public final class WorldExport {

    static WorldExport current; // only while converting

    private final LevelStorageSource.LevelStorageAccess access;
    private final Map<Path, RegionFile> regions = new HashMap<>();
    final String directory;

    WorldExport(String name) throws IOException {
        LevelStorageSource source = Minecraft.getInstance().getLevelSource();
        directory = FileUtil.findAvailableName(source.getBaseDir(), name, "");
        access = source.createAccess(directory);
    }

    // the chunk cache mixins hand chunks here before dropping them
    public static void unloading(LevelChunk chunk) {
        if (current != null) current.save(chunk);
    }

    public static void unloadingAll(ClientLevel level) {
        if (current != null) current.saveAll(level);
    }

    // before the handler gets it: these packets throw the world away
    void before(Packet<?> packet, ClientLevel level) {
        if (level == null) return;
        // a respawn into the same dimension keeps the client's world; any other is a new one, maybe a new lobby
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket respawn
                && !respawn.commonPlayerSpawnInfo().dimension().equals(level.dimension())) {
            discard();
        }
    }

    private void discard() {
        closeRegions(); // the open region files would survive the delete
        try (Stream<Path> files = Files.exists(dimensions()) ? Files.walk(dimensions()) : Stream.empty()) {
            for (Path path : files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.delete(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear " + dimensions(), e);
        }
    }

    // ClientChunkCache's storage ring is at most 35 chunks either side of its center, which follows the player
    void saveAll(ClientLevel level) {
        ChunkPos center = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.chunkPosition() : ChunkPos.ZERO;
        for (int x = center.x() - SEARCH_RADIUS; x <= center.x() + SEARCH_RADIUS; x++) {
            for (int z = center.z() - SEARCH_RADIUS; z <= center.z() + SEARCH_RADIUS; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) save(chunk);
            }
        }
    }

    private static final int SEARCH_RADIUS = 40;

    // written on the spot, so a later save of the same chunk always wins
    void save(LevelChunk chunk) {
        if (chunk.isEmpty()) return; // the client's stand-in for a chunk it does not have
        ClientLevel level = (ClientLevel) chunk.getLevel();
        Path dimension = access.getDimensionPath(level.dimension());
        ChunkPos pos = chunk.getPos();
        try {
            write(dimension.resolve("region"), level.dimension(), "chunk", pos, chunkTag(level, chunk));
            write(dimension.resolve("entities"), level.dimension(), "entities", pos, entitiesTag(level, pos));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save chunk " + pos, e);
        }
    }

    // like SerializableChunkData.copyOf, which wants a server level: no light, so the game relights it on load
    private static CompoundTag chunkTag(ClientLevel level, LevelChunk chunk) {
        List<SerializableChunkData.SectionData> sections = new ArrayList<>();
        LevelChunkSection[] chunkSections = chunk.getSections();
        for (int i = 0; i < chunkSections.length; i++) {
            sections.add(new SerializableChunkData.SectionData(chunk.getSectionYFromSectionIndex(i), chunkSections[i].copy(), null, null));
        }
        List<CompoundTag> blockEntities = new ArrayList<>();
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            CompoundTag tag = chunk.getBlockEntityNbtForSaving(pos, level.registryAccess());
            if (tag != null) blockEntities.add(tag);
        }
        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (ChunkStatus.FULL.heightmapsAfter().contains(entry.getKey())) heightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
        }
        CompoundTag structures = new CompoundTag();
        structures.put("starts", new CompoundTag());
        structures.put("References", new CompoundTag());
        return new SerializableChunkData(level.palettedContainerFactory(), chunk.getPos(), chunk.getMinSectionY(), level.getGameTime(),
                chunk.getInhabitedTime(), ChunkStatus.FULL, null, null, UpgradeData.EMPTY, null, heightmaps,
                chunk.getTicksForSerialization(level.getGameTime()), new ShortList[chunkSections.length], false, sections, List.of(),
                blockEntities, structures).write();
    }

    // as EntityStorage writes them; players are left out, the recorded one included
    private static CompoundTag entitiesTag(ClientLevel level, ChunkPos pos) {
        ListTag list = new ListTag();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Player || !entity.chunkPosition().equals(pos) || entity.isPassenger()) continue;
            try {
                TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                // with its passengers, as the server saves a vehicle
                if (entity.save(output)) list.add(output.buildResult());
            } catch (RuntimeException e) {
                // a modded entity that only expects to be saved on the server
            }
        }
        CompoundTag tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
        tag.put("Entities", list);
        tag.store("Position", ChunkPos.CODEC, pos);
        return tag;
    }

    private void write(Path folder, ResourceKey<Level> dimension, String type, ChunkPos pos, CompoundTag tag) throws IOException {
        Path file = folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
        RegionFile region = regions.get(file);
        if (region == null) {
            Files.createDirectories(folder);
            region = new RegionFile(new RegionStorageInfo(directory, dimension, type), file, folder, false);
            regions.put(file, region);
        }
        try (DataOutputStream out = region.getChunkDataOutputStream(pos)) {
            NbtIo.write(tag, out);
        }
    }

    private Path dimensions() {
        return access.getLevelPath(LevelResource.ROOT).resolve("dimensions");
    }

    private void closeRegions() {
        for (RegionFile region : regions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                throw new RuntimeException("Failed to close " + region, e);
            }
        }
        regions.clear();
    }

    // the player joins where the recording ended, in that dimension, carrying what the recorded player carried
    void finish(Player player) throws IOException {
        try {
            closeRegions(); // flushes the region files
            // a superflat of one layer of air everywhere: no made-up terrain next to the recorded chunks
            PrimaryLevelData data = new PrimaryLevelData(new LevelSettings(directory, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT,
                    true, WorldDataConfiguration.DEFAULT), PrimaryLevelData.SpecialWorldProperty.FLAT, Lifecycle.stable());
            data.setInitialized(true); // or the server would look for a spawn point in the void
            if (player != null) {
                data.setSpawn(new LevelData.RespawnData(GlobalPos.of(player.level().dimension(), player.blockPosition()),
                        Mth.wrapDegrees(player.getYRot()), Mth.clamp(player.getXRot(), -90, 90))); // the codec wants them in range
            }
            // whoever opens the world is its singleplayer owner, and the owner loads this file whatever their UUID
            access.saveDataTag(data, Util.NIL_UUID);
            writeCompressed(access.getLevelPath(LevelResource.DATA).resolve("minecraft").resolve("world_gen_settings.dat"), worldGenSettings());
            if (player != null) {
                writeCompressed(access.getLevelPath(LevelResource.PLAYER_DATA_DIR).resolve(Util.NIL_UUID + ".dat"), playerTag(player));
            }
        } finally {
            access.close();
        }
    }

    private static CompoundTag playerTag(Player player) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(output);
        CompoundTag tag = NbtUtils.addCurrentDataVersion(output.buildResult());
        tag.putString("Dimension", player.level().dimension().identifier().toString()); // a client player never saves its own
        tag.remove("UUID"); // the recorded player's, not whoever opens the world
        return tag;
    }

    // as the world_gen_settings saved data: every vanilla dimension a flat of air, generating nothing
    private static CompoundTag worldGenSettings() {
        CompoundTag settings = new CompoundTag();
        settings.putLong("seed", 0);
        settings.putBoolean("generate_structures", false);
        settings.putBoolean("bonus_chest", false);
        CompoundTag dimensions = new CompoundTag();
        for (ResourceKey<Level> dimension : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            CompoundTag layer = new CompoundTag();
            layer.putString("block", "minecraft:air");
            layer.putInt("height", 1);
            ListTag layers = new ListTag();
            layers.add(layer);
            CompoundTag flat = new CompoundTag();
            flat.put("layers", layers);
            flat.putString("biome", "minecraft:the_void");
            flat.putBoolean("lakes", false);
            flat.putBoolean("features", false);
            CompoundTag generator = new CompoundTag();
            generator.putString("type", "minecraft:flat");
            generator.put("settings", flat);
            CompoundTag stem = new CompoundTag();
            stem.putString("type", dimension.identifier().toString()); // vanilla names each dimension type after its level
            stem.put("generator", generator);
            dimensions.put(dimension.identifier().toString(), stem);
        }
        settings.put("dimensions", dimensions);
        CompoundTag root = NbtUtils.addCurrentDataVersion(new CompoundTag());
        root.put("data", settings);
        return root;
    }

    private static void writeCompressed(Path path, CompoundTag tag) throws IOException {
        Files.createDirectories(path.getParent());
        NbtIo.writeCompressed(tag, path);
    }
}
