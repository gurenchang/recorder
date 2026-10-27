package net.toshimichi.recorder;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.toshimichi.recorder.mixin.AccessorBiomeManager;
import net.toshimichi.recorder.mixin.AccessorClientClockManager;
import net.toshimichi.recorder.mixin.AccessorClientLevel;
import net.toshimichi.recorder.mixin.AccessorClientLevelData;
import net.toshimichi.recorder.mixin.AccessorClockInstance;
import net.toshimichi.recorder.mixin.AccessorConnection;
import net.toshimichi.recorder.mixin.AccessorPlayerInfoUpdatePacket;
import net.toshimichi.recorder.mixin.AccessorPlayerTabOverlay;
import net.toshimichi.recorder.mixin.InvokerTagNetworkSerialization;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Writes the live connection to disk. record() is called from the netty thread, the rest from the client thread. */
public final class Recording {

    private static final Logger log = LogUtils.getLogger();
    // ClientChunkCache's storage ring is wider than the view by 3, and the view is at most 32
    private static final int CHUNK_SEARCH_RADIUS = 40;

    private static volatile DataOutputStream out;
    // the connection being recorded: the codecs also run for server list pings and LAN guests in this JVM
    private static volatile Channel channel;
    private static long start;
    private static File file;

    private Recording() {
    }

    public static File directory() {
        return new File(Minecraft.getInstance().gameDirectory, "recordings");
    }

    public static boolean isRecording() {
        return out != null;
    }

    public static void toggle(Minecraft mc) {
        if (isRecording()) {
            stop();
            chat(mc, Component.literal("Recording saved as " + file.getName()));
            return;
        }

        try {
            start(mc);
            chat(mc, Component.literal("Recording to " + file.getName()));
        } catch (IOException | RuntimeException e) {
            log.error("Failed to start recording", e);
            chat(mc, Component.literal("Failed to start recording: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    public static synchronized void start(Minecraft mc) throws IOException {
        directory().mkdirs();
        file = new File(directory(), new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".mcrc");
        start = System.currentTimeMillis();

        DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
        // listen first: whatever arrives from here on is applied after the snapshot, so it must be recorded;
        // record() waits on this lock until the header and snapshot are written, which keeps them in front
        channel = ((AccessorConnection) mc.getConnection().getConnection()).getChannel();
        out = stream;
        try {
            // and what arrived before is applied before it, or it is in neither
            mc.packetProcessor().processQueuedPackets();
            // getGameProfile() may block on Mojang's session server for a while
            RecordingFile.writeHeader(stream, start, SharedConstants.getProtocolVersion(), SharedConstants.getCurrentVersion().name(), mc.getGameProfile());
            RegistryAccess registries = mc.getConnection().registryAccess();
            // the registries and tags the server synced, so a replay can decode what follows
            for (Packet<?> packet : configurationSnapshot(mc)) {
                RecordingFile.writeEntry(stream, 0, RecordingFile.CONFIGURATION_SNAPSHOT, Protocols.encode(Protocols.configuration(false), packet));
            }
            // the world state the server sent before we started listening, so a replay has something to show
            for (Packet<?> packet : snapshot(mc)) {
                RecordingFile.writeEntry(stream, 0, RecordingFile.SNAPSHOT, Protocols.encode(Protocols.clientboundPlay(registries), packet));
            }
        } catch (IOException | RuntimeException e) {
            stop();
            throw e;
        }
    }

    public static synchronized void stop() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException e) {
            log.error("Failed to close {}", file, e);
        }
        out = null;
    }

    // the states a replay understands, else -1; login and status never reach a recording
    public static byte direction(ConnectionProtocol protocol, boolean serverbound) {
        return switch (protocol) {
            case PLAY -> serverbound ? RecordingFile.SERVERBOUND : RecordingFile.CLIENTBOUND;
            case CONFIGURATION -> serverbound ? RecordingFile.CONFIGURATION_SERVERBOUND : RecordingFile.CONFIGURATION_CLIENTBOUND;
            default -> -1;
        };
    }

    // a frame off a real connection's codecs: varint id + payload, already decrypted and decompressed
    public static void record(Channel source, byte direction, ByteBuf buf) {
        if (out == null || source != channel) return;

        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        record(data, direction);
    }

    // singleplayer: the in-memory channel carries objects, so the frame is built the way the codec would have
    public static void record(Channel source, boolean serverbound, Packet<?> packet, ConnectionProtocol listening) {
        if (out == null || source != channel) return;
        try {
            if (packet instanceof BundlePacket<?>) {
                // on a real connection a bundle crosses the wire as its parts between two delimiters
                ClientPacketListener connection = Minecraft.getInstance().getConnection();
                if (connection == null) return;
                Protocols.clientboundPlay(connection.registryAccess()).bundlerInfo()
                        .unbundlePacket(packet, part -> record(source, serverbound, part, listening));
                return;
            }
            ProtocolInfo<?> protocol;
            byte direction;
            if (Protocols.protocolOf(packet, listening) == ConnectionProtocol.CONFIGURATION) {
                protocol = Protocols.configuration(serverbound);
                direction = serverbound ? RecordingFile.CONFIGURATION_SERVERBOUND : RecordingFile.CONFIGURATION_CLIENTBOUND;
            } else {
                ClientPacketListener connection = Minecraft.getInstance().getConnection();
                if (connection == null) return; // the play listener is not up yet; nothing in this state is replayed
                protocol = serverbound ? Protocols.serverboundPlay(connection.registryAccess()) : Protocols.clientboundPlay(connection.registryAccess());
                direction = serverbound ? RecordingFile.SERVERBOUND : RecordingFile.CLIENTBOUND;
            }
            record(Protocols.encode(protocol, packet), direction);
        } catch (RuntimeException e) {
            // anything thrown here escapes into netty, which closes the connection: losing a row beats losing the world
            log.error("Failed to serialize {}", packet.type(), e);
        }
    }

    private static void record(byte[] data, byte direction) {
        synchronized (Recording.class) {
            if (out == null) return;
            try {
                RecordingFile.writeEntry(out, (int) (System.currentTimeMillis() - start), direction, data);
            } catch (IOException e) {
                log.error("Failed to write {}, stopping", file, e);
                stop();
            }
        }
    }

    // in-world key and mouse button presses, as GLFW reports them; GUI input and mouse motion are not recorded
    public static synchronized void recordInput(byte device, int code, boolean pressed) {
        if (out == null) return;
        try {
            RecordingFile.writeEntry(out, (int) (System.currentTimeMillis() - start), RecordingFile.INPUT,
                    new byte[]{device, (byte) (code >> 24), (byte) (code >> 16), (byte) (code >> 8), (byte) code, (byte) (pressed ? 1 : 0)});
        } catch (IOException e) {
            log.error("Failed to write {}, stopping", file, e);
            stop();
        }
    }

    // what the configuration phase left behind; a replay's client rebuilds its registries from exactly this
    private static List<Packet<?>> configurationSnapshot(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        RegistryAccess.Frozen registries = connection.registryAccess();
        List<Packet<?>> packets = new ArrayList<>();

        packets.add(new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.REGISTRY.toNames(mc.level.enabledFeatures())));
        // F3's server brand
        if (connection.serverBrand() != null) packets.add(new ClientboundCustomPayloadPacket(new BrandPayload(connection.serverBrand())));
        // every entry in full: the replaying client may not know the packs the server named
        RegistrySynchronization.packRegistries(registries.createSerializationContext(NbtOps.INSTANCE), registries, Set.of(),
                (key, entries) -> packets.add(new ClientboundRegistryDataPacket(key, entries)));
        // the server sends tags for the synced registries and the built-in ones alike
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags = new HashMap<>();
        registries.registries().forEach(entry -> {
            if (!RegistrySynchronization.isNetworkable(entry.key()) && !BuiltInRegistries.REGISTRY.containsKey(entry.key().identifier())) return;
            TagNetworkSerialization.NetworkPayload payload = InvokerTagNetworkSerialization.invokeSerializeToNetwork(entry.value());
            if (!payload.isEmpty()) tags.put(entry.key(), payload);
        });
        packets.add(new ClientboundUpdateTagsPacket(tags));
        packets.add(ClientboundFinishConfigurationPacket.INSTANCE);
        return packets;
    }

    // ponytail: no open containers or boss bars; add them if the replay needs them
    private static List<Packet<?>> snapshot(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        ClientPacketListener connection = mc.getConnection();
        List<Packet<?>> packets = new ArrayList<>();

        int simulationDistance = level.getServerSimulationDistance();
        packets.add(new ClientboundLoginPacket(player.getId(), level.getLevelData().isHardcore(), connection.levels(), 20,
                mc.options.getEffectiveRenderDistance(), simulationDistance, player.isReducedDebugInfo(), player.shouldShowDeathScreen(),
                player.getDoLimitedCrafting(),
                new CommonPlayerSpawnInfo(level.dimensionTypeRegistration(), level.dimension(),
                        ((AccessorBiomeManager) level.getBiomeManager()).getBiomeZoomSeed(), mc.gameMode.getPlayerMode(),
                        mc.gameMode.getPreviousPlayerMode(), level.isDebug(), ((AccessorClientLevelData) level.getLevelData()).isFlat(), player.getLastDeathLocation(),
                        player.getPortalCooldown(), level.getSeaLevel()),
                false));
        packets.add(new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));

        // the tab list, then NPCs: servers list them only long enough to spawn them, and the client needs the entry first
        List<ClientboundPlayerInfoUpdatePacket.Entry> listed = new ArrayList<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            listed.add(new ClientboundPlayerInfoUpdatePacket.Entry(info.getProfile().id(), info.getProfile(),
                    connection.getListedOnlinePlayers().contains(info), info.getLatency(), info.getGameMode(),
                    info.getTabListDisplayName(), info.showHat(), info.getTabListOrder(), null));
        }
        List<UUID> unlisted = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof RemotePlayer other) || connection.getPlayerInfo(other.getUUID()) != null) continue;
            listed.add(new ClientboundPlayerInfoUpdatePacket.Entry(other.getUUID(), other.getGameProfile(), false, 0,
                    net.minecraft.world.level.GameType.SURVIVAL, null, true, 0, null));
            unlisted.add(other.getUUID());
        }
        // chat sessions are left out: the signature chain they start could not be continued mid-session anyway
        ClientboundPlayerInfoUpdatePacket tabList = new ClientboundPlayerInfoUpdatePacket(EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER), List.of());
        ((AccessorPlayerInfoUpdatePacket) tabList).setEntries(listed);
        packets.add(tabList);
        AccessorPlayerTabOverlay tabOverlay = (AccessorPlayerTabOverlay) mc.gui.getTabList();
        if (tabOverlay.getHeader() != null || tabOverlay.getFooter() != null) {
            // null means none, which the wire spells as empty text
            packets.add(new ClientboundTabListPacket(tabOverlay.getHeader() != null ? tabOverlay.getHeader() : Component.empty(),
                    tabOverlay.getFooter() != null ? tabOverlay.getFooter() : Component.empty()));
        }

        // the game time, and where each clock (day, moon...) stands and how fast it runs
        Map<Holder<WorldClock>, ClockNetworkState> clocks = new HashMap<>();
        ((AccessorClientClockManager) connection.clockManager()).getClocks().forEach((clock, instance) -> {
            AccessorClockInstance state = (AccessorClockInstance) instance;
            clocks.put((Holder<WorldClock>) clock, new ClockNetworkState(state.getTotalTicks(), state.getPartialTick(), state.getRate()));
        });
        packets.add(new ClientboundSetTimePacket(level.getGameTime(), clocks));

        // the chunk ring follows its center: a chunk the center does not cover is thrown away on arrival
        ChunkPos center = player.chunkPosition();
        packets.add(new ClientboundSetChunkCacheCenterPacket(center.x(), center.z()));
        for (int x = center.x() - CHUNK_SEARCH_RADIUS; x <= center.x() + CHUNK_SEARCH_RADIUS; x++) {
            for (int z = center.z() - CHUNK_SEARCH_RADIUS; z <= center.z() + CHUNK_SEARCH_RADIUS; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                try {
                    // block entities (sign text, skulls, banners) ride along, as their update tags
                    packets.add(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                } catch (RuntimeException e) {
                    log.warn("Leaving chunk {} out of the snapshot", chunk.getPos(), e); // a modded block entity that only expects to be asked on the server
                }
            }
        }

        // what is drawn on every map the server has sent, held or framed
        for (Map.Entry<MapId, MapItemSavedData> map : ((AccessorClientLevel) level).invokeGetAllMapData().entrySet()) {
            MapItemSavedData data = map.getValue();
            List<MapDecoration> decorations = new ArrayList<>();
            data.getDecorations().forEach(decorations::add);
            packets.add(new ClientboundMapItemDataPacket(map.getKey(), data.scale, data.locked, decorations,
                    new MapItemSavedData.MapPatch(0, 0, 128, 128, data.colors)));
        }

        // the client keeps no rain flag of its own, only the strengths
        float rain = level.getRainLevel(1);
        packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rain));
        // getThunderLevel is already scaled by the rain; the packet carries the raw value
        packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, rain == 0 ? 0 : level.getThunderLevel(1) / rain));
        packets.add(new ClientboundInitializeBorderPacket(level.getWorldBorder()));

        // teams and objectives before the scores and display slots that name them
        Scoreboard scoreboard = level.getScoreboard();
        for (PlayerTeam team : scoreboard.getPlayerTeams()) packets.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        for (Objective objective : scoreboard.getObjectives()) {
            packets.add(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
            for (PlayerScoreEntry score : scoreboard.listPlayerScores(objective)) {
                packets.add(new ClientboundSetScorePacket(score.owner(), objective.getName(), score.value(),
                        Optional.ofNullable(score.display()), Optional.ofNullable(score.numberFormatOverride())));
            }
        }
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective objective = scoreboard.getDisplayObjective(slot);
            if (objective != null) packets.add(new ClientboundSetDisplayObjectivePacket(slot, objective));
        }

        // before the position, so the puppet picks up the held slot when it spawns
        packets.add(new ClientboundContainerSetContentPacket(player.inventoryMenu.containerId, player.inventoryMenu.getStateId(),
                List.copyOf(player.inventoryMenu.getItems()), player.inventoryMenu.getCarried()));
        packets.add(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
        packets.add(new ClientboundSetHealthPacket(player.getHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel()));
        packets.add(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
        packets.add(new ClientboundPlayerAbilitiesPacket(player.getAbilities()));

        // after the chunks: this is what the replay spawns the recorded player's body on
        packets.add(new ClientboundPlayerPositionPacket(0, new PositionMoveRotation(player.position(), Vec3.ZERO, player.getYRot(), player.getXRot()), Set.of()));
        // the flags, pose, skin layers and golden hearts; the server only sends them when they change
        List<SynchedEntityData.DataValue<?>> own = player.getEntityData().getNonDefaultValues();
        if (own != null) packets.add(new ClientboundSetEntityDataPacket(player.getId(), own));

        for (Entity entity : level.entitiesForRendering()) {
            if (entity == player || entity instanceof EnderDragonPart || entity.isRemoved()) continue;
            packets.add(spawn(entity));
            // unlike the old spawn packets this one carries no metadata: the dropped item's stack, the framed item
            List<SynchedEntityData.DataValue<?>> data = entity.getEntityData().getNonDefaultValues();
            if (data != null) packets.add(new ClientboundSetEntityDataPacket(entity.getId(), data));
            if (entity instanceof LivingEntity living) equipment(living, packets);
        }

        if (!unlisted.isEmpty()) packets.add(new ClientboundPlayerInfoRemovePacket(unlisted));

        // after every spawn, so both ends of each link exist on replay; the player is in here too
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof EnderDragonPart || entity.isRemoved()) continue;
            if (entity instanceof LivingEntity living) {
                // max health, speed, horse jump strength; the client only holds what the server sent or defaulted
                packets.add(new ClientboundUpdateAttributesPacket(entity.getId(), living.getAttributes().getSyncableAttributes()));
                for (MobEffectInstance effect : living.getActiveEffects()) packets.add(new ClientboundUpdateMobEffectPacket(entity.getId(), effect, false));
            }
            if (entity.isVehicle()) packets.add(new ClientboundSetPassengersPacket(entity));
            if (entity instanceof Leashable leashable && leashable.getLeashHolder() != null) {
                packets.add(new ClientboundSetEntityLinkPacket(entity, leashable.getLeashHolder()));
            }
        }

        // what lets the loading screen go once the chunk under the player is drawn
        packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0));
        return packets;
    }

    // the server's own spawn packet choice, Entity.getAddEntityPacket, rebuilt without its ServerEntity. The position is
    // where the server last put it, not where it is gliding: the server's relative moves build on the former
    private static ClientboundAddEntityPacket spawn(Entity entity) {
        if (entity instanceof BlockAttachedEntity attached) {
            int data = attached instanceof HangingEntity hanging ? hanging.getDirection().get3DDataValue() : 0;
            return new ClientboundAddEntityPacket(entity, data, attached.getPos());
        }
        int data = 0;
        if (entity instanceof FallingBlockEntity falling) data = Block.getId(falling.getBlockState());
        else if (entity instanceof FishingHook hook) data = hook.getOwner() == null ? hook.getId() : hook.getOwner().getId();
        else if (entity instanceof Projectile projectile) data = projectile.getOwner() == null ? 0 : projectile.getOwner().getId();
        else if (entity instanceof Warden warden) data = warden.hasPose(Pose.EMERGING) ? 1 : 0;
        Vec3 base = entity.getPositionCodec().getBase();
        return new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), base.x, base.y, base.z, entity.getXRot(), entity.getYRot(),
                entity.getType(), data, entity.getDeltaMovement(), entity.getYHeadRot());
    }

    // held items and armor, as ServerEntity sends them after a spawn
    private static void equipment(LivingEntity entity, List<Packet<?>> packets) {
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) slots.add(Pair.of(slot, stack.copy()));
        }
        if (!slots.isEmpty()) packets.add(new ClientboundSetEquipmentPacket(entity.getId(), slots));
    }

    private static void chat(Minecraft mc, Component message) {
        mc.gui.getChat().addClientSystemMessage(message);
    }
}
