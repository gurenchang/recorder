package net.toshimichi.recorder;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.server.ServerLinks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.toshimichi.recorder.mixin.AccessorLivingEntity;
import net.toshimichi.recorder.mixin.AccessorPlayer;
import net.toshimichi.recorder.mixin.AccessorPlayerInfoUpdatePacket;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Feeds a recording into a fresh client connection over a channel that goes nowhere, starting from the configuration
 * phase like a real join. Clientbound packets drive the world. The viewer is a spectator; the recorded player is a
 * puppet entity driven by its own serverbound packets (the viewer's mode can be switched from the pause menu).
 * Left-click an entity to see through its eyes, sneak to let go.
 */
public final class Replay {

    private static final Logger log = LogUtils.getLogger();
    private static Replay current;
    public static final int PUPPET_ID = Integer.MIN_VALUE + 7; // nothing a server hands out
    private static final int LOGIN = Protocols.clientboundPlayId(GamePacketTypes.CLIENTBOUND_LOGIN);
    private static final int POSITION = Protocols.clientboundPlayId(GamePacketTypes.CLIENTBOUND_PLAYER_POSITION);
    private static final int PASSENGERS = Protocols.clientboundPlayId(GamePacketTypes.CLIENTBOUND_SET_PASSENGERS);
    private static final int PLAYER_ACTION = Protocols.serverboundPlayId(GamePacketTypes.SERVERBOUND_PLAYER_ACTION);
    private static final List<Integer> MOVES = List.of(
            Protocols.serverboundPlayId(GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS),
            Protocols.serverboundPlayId(GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS_ROT),
            Protocols.serverboundPlayId(GamePacketTypes.SERVERBOUND_MOVE_PLAYER_ROT),
            Protocols.serverboundPlayId(GamePacketTypes.SERVERBOUND_MOVE_PLAYER_STATUS_ONLY));

    private final Minecraft mc = Minecraft.getInstance();
    private final Screen parent;
    private final List<RecordingFile.Entry> entries;
    private final List<Move> moves; // where the recorded player went, read ahead of the replay
    private final GameProfile profile; // who recorded it, not who is watching
    // the viewer gets a UUID of their own: the recorded player's, often the same account, is the puppet's
    private final GameProfile viewer;
    private Connection connection;
    private Puppet puppet;
    // the recorded player's own metadata, which is the puppet's: flags, pose, skin layers. Outlives the puppet across respawns
    private final Map<Integer, SynchedEntityData.DataValue<?>> ownData = new LinkedHashMap<>();
    private int markers; // passed so far, to number them as the recorder did
    private boolean clickWasDown;
    private BlockPos digPos; // the puppet's cracks: the server shows them to everyone but the digger
    private long digStart;
    private long digDuration;
    private float health; // as the server sent it, before the client clamped it to a max health not yet raised
    private int index;
    private boolean paused;
    private long base;       // replay millis at the last resume or jump
    private long resumedAt;  // wall clock at the last resume or jump
    private WorldExport export; // only while converting
    private GameType gameType = GameType.SPECTATOR; // the viewer's, outlives seeks

    private Replay(Screen parent, RecordingFile.Contents contents) throws IOException {
        if (contents.protocol != net.minecraft.SharedConstants.getProtocolVersion()) {
            throw new IOException("Recorded on Minecraft " + contents.gameVersion + ", which this version cannot replay");
        }
        this.parent = parent;
        this.entries = contents.entries;
        this.moves = moves(entries);
        // version 1 files never stored it; the viewer is the best guess
        this.profile = contents.profile != null ? contents.profile : mc.getGameProfile();
        this.viewer = new GameProfile(UUID.randomUUID(), mc.getUser().getName());
    }

    public static Replay current() {
        return current;
    }

    public static void start(File file, Screen parent) throws IOException {
        current = new Replay(parent, RecordingFile.readAll(file));
        current.connect();
        current.resumedAt = System.currentTimeMillis();
        current.tick();
    }

    // the whole recording in one go, nothing drawn in between; returns the new save's folder name
    public static String convert(File file, Screen parent) throws IOException {
        Replay replay = new Replay(parent, RecordingFile.readAll(file));
        replay.export = new WorldExport(file.getName().replaceFirst("\\.mcrc$", ""));
        WorldExport.current = replay.export;
        replay.connect();
        try {
            while (replay.index < replay.entries.size()) replay.feed(replay.entries.get(replay.index++));
            // nothing ticked, so the puppet never walked: put it where the recorded player last was
            if (replay.puppet != null && !replay.moves.isEmpty()) {
                Move last = replay.moves.getLast();
                replay.puppet.snapTo(last.x(), last.y(), last.z(), last.yaw(), last.pitch());
            }
            if (replay.mc.level != null) replay.export.saveAll(replay.mc.level);
        } finally {
            WorldExport.current = null;
            replay.export.finish(replay.puppet != null ? replay.puppet : replay.mc.player);
            replay.mc.disconnect(parent, false);
        }
        return replay.export.directory;
    }

    public boolean isPaused() {
        return paused;
    }

    // the wall clock keeps running past the last entry; the replay does not
    public long elapsed() {
        return Math.min(duration(), paused ? base : base + System.currentTimeMillis() - resumedAt);
    }

    public long duration() {
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).time;
    }

    public GameType getGameType() {
        return gameType;
    }

    // the recorded player's body, else null
    public static Puppet recorded() {
        return current != null ? current.puppet : null;
    }

    // the recorded player while the viewer looks through their eyes, else null
    public static Puppet possessed() {
        Puppet puppet = recorded();
        return puppet != null && Minecraft.getInstance().getCameraEntity() == puppet ? puppet : null;
    }

    public void cycleGameType() {
        gameType = switch (gameType) {
            case SPECTATOR -> GameType.CREATIVE;
            case CREATIVE -> GameType.SURVIVAL;
            default -> GameType.SPECTATOR;
        };
    }

    public void togglePause() {
        base = elapsed();
        resumedAt = System.currentTimeMillis();
        paused = !paused;
    }

    // backwards means a new world fast-forwarded from the start: packets cannot be undone
    public void jump(long millis) {
        LocalPlayer before = null;
        if (millis < elapsed()) {
            before = mc.player;
            Screen screen = mc.screen;
            mc.disconnect(screen != null ? screen : parent, false, false);
            connect();
        }
        base = millis;
        resumedAt = System.currentTimeMillis();
        tick();
        // the new world puts the viewer back at the snapshot; keep them where they were, unless that was another dimension
        LocalPlayer player = mc.player;
        if (before != null && player != null && player.level().dimension() == before.level().dimension()) {
            player.snapTo(before.getX(), before.getY(), before.getZ(), before.getYRot(), before.getXRot());
        }
    }

    public void exit() {
        current = null;
        mc.disconnect(parent, false);
    }

    // called every client tick; the connection is swapped out from under us when the world is left any other way
    public void tick() {
        if (index > 0 && (!connection.isConnected() || mc.getConnection() != null && mc.getConnection().getConnection() != connection)) {
            current = null;
            return;
        }

        long elapsed = elapsed();
        while (index < entries.size() && entries.get(index).time <= elapsed) {
            feed(entries.get(index++));
        }

        if (puppet == null || mc.player == null) return;
        move(elapsed);
        if (digPos != null) {
            mc.level.destroyBlockProgress(PUPPET_ID, digPos, (int) Math.min(9, (elapsed - digStart) * 10 / digDuration) - 1);
        }
        // the recording may carry the server's own game mode changes; the viewer keeps the one picked
        if (mc.gameMode.getPlayerMode() != gameType) mc.gameMode.setLocalMode(gameType);

        boolean down = mc.options.keyAttack.isDown();
        if (down && !clickWasDown && mc.screen == null && mc.hitResult instanceof EntityHitResult hit) {
            mc.setCameraEntity(hit.getEntity());
        }
        clickWasDown = down;

        Entity view = mc.getCameraEntity();
        if (view != null && view != mc.player && (mc.options.keyShift.isDown() || view.isRemoved())) {
            // let go where they were looking from, as vanilla spectating leaves you
            mc.player.snapTo(view.getX(), view.getY(), view.getZ(), view.getYRot(), view.getXRot());
            mc.setCameraEntity(mc.player);
        }
    }

    // after the entities have ticked: through the recorded player's eyes, the first person hand is drawn from the
    // viewer's own player, so it takes on the puppet's look (which its sway lags behind) and its swing
    public void afterTick() {
        Puppet puppet = possessed();
        LocalPlayer player = mc.player;
        if (puppet == null || player == null) return;
        player.setXRot(puppet.getXRot());
        player.setYRot(puppet.getYRot());
        player.xRotO = puppet.xRotO;
        player.yRotO = puppet.yRotO;
        player.swinging = puppet.swinging;
        player.swingingArm = puppet.swingingArm;
        player.swingTime = puppet.swingTime;
        player.attackAnim = puppet.attackAnim;
        player.oAttackAnim = puppet.oAttackAnim;
    }

    // once the snapshot has placed the viewer: a body for the recorded player, and freedom for the viewer
    private void spawnPuppet() {
        LocalPlayer player = mc.player;
        // the name and skin the server listed at this point, as a nick plugin may have changed them; the skin is looked up by UUID
        PlayerInfo info = mc.getConnection().getPlayerInfo(profile.id());
        puppet = new Puppet(mc.level, info != null ? info.getProfile() : profile);
        puppet.setId(PUPPET_ID);
        puppet.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        // share the inventory: the replayed inventory packets fill the viewer's, the puppet shows it
        ((AccessorPlayer) (Object) puppet).setInventory(player.getInventory());
        ((AccessorLivingEntity) (Object) puppet).setEquipment(((AccessorLivingEntity) player).getEquipment());
        puppet.getAbilities().instabuild = player.getAbilities().instabuild; // before the viewer turns spectator
        if (!ownData.isEmpty()) puppet.getEntityData().assignValues(new ArrayList<>(ownData.values()));
        mc.level.addEntity(puppet);
        mc.gameMode.setLocalMode(gameType);
        // step back so the first thing on screen is not the inside of the puppet's head
        Vec3 look = player.getLookAngle();
        player.setPos(player.getX() - look.x * 3, player.getY() + 1, player.getZ() - look.z * 3);
    }

    private void connect() {
        connection = new Connection(PacketFlow.CLIENTBOUND) {
            @Override
            public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            }
        };
        new EmbeddedChannel(connection); // channelActive gives the connection its channel
        ClientConfigurationPacketListenerImpl listener = new ClientConfigurationPacketListenerImpl(mc, connection, new CommonListenerCookie(
                new LevelLoadTracker(), viewer, mc.getTelemetryManager().createWorldSessionManager(false, null, null),
                ClientRegistryLayer.createRegistryAccess().compositeAccess(), FeatureFlags.DEFAULT_FLAGS, null, null, parent,
                new HashMap<>(), null, Map.of(), ServerLinks.EMPTY, new HashMap<>(), false));
        connection.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, listener);
        connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
        puppet = null;
        digPos = null;
        ownData.clear();
        health = 0;
        markers = 0;
        index = 0;
    }

    private void feed(RecordingFile.Entry entry) {
        if (entry.direction == RecordingFile.MARKER) {
            marker(entry);
            return;
        }
        if (!entry.isPacket()) return; // key presses, and whatever rows a newer writer adds
        try {
            PacketListener listener = connection.getPacketListener();
            if (entry.isConfiguration()) {
                if (entry.isServerbound() || !(listener instanceof ClientConfigurationPacketListenerImpl)) return;
                Packet<?> packet = Protocols.decode(Protocols.configuration(false), entry.data);
                if (!skipped(packet)) handle(packet, listener);
                return;
            }
            if (!(listener instanceof ClientPacketListener play)) return;
            if (entry.isServerbound()) {
                if (puppet != null) serverbound(Protocols.decode(Protocols.serverboundPlay(play.registryAccess()), entry.data), entry.time);
            } else {
                clientbound(Protocols.decode(Protocols.clientboundPlay(play.registryAccess()), entry.data), play);
            }
        } catch (Exception e) {
            log.warn("Skipping a packet at {}ms", entry.time, e);
        }
    }

    // shown as it is passed; a jump past several shows the last
    private void marker(RecordingFile.Entry entry) {
        markers++;
        if (export != null) return; // converting draws nothing
        String label = "";
        try {
            label = RecordingFile.readMarker(entry.data).label();
        } catch (IOException e) {
            log.warn("Unreadable marker at {}ms", entry.time, e);
        }
        mc.gui.setOverlayMessage(Component.literal("Marker #" + markers + (label.isEmpty() ? "" : ": " + label)), false);
    }

    // nothing in a replay may leave it: no disconnects, transfers, resource pack downloads or screens to click through
    private static boolean skipped(Packet<?> packet) {
        return packet instanceof ClientboundDisconnectPacket || packet instanceof ClientboundTransferPacket
                || packet instanceof ClientboundResourcePackPushPacket || packet instanceof ClientboundResourcePackPopPacket
                || packet instanceof ClientboundShowDialogPacket || packet instanceof ClientboundCodeOfConductPacket
                || packet instanceof ClientboundBundleDelimiterPacket; // the pipeline's bundler eats these; handled one by one is the same
    }

    private void clientbound(Packet<?> packet, ClientPacketListener listener) {
        if (skipped(packet)) return;
        if (packet instanceof ClientboundPlayerChatPacket chat) {
            // the chat index and signature cache start where the recording started, not at zero; show it unsigned
            Component content = chat.unsignedContent() != null ? chat.unsignedContent() : Component.literal(chat.body().content());
            handle(new ClientboundDisguisedChatPacket(content, chat.chatType()), listener);
            return;
        }
        if (packet instanceof ClientboundPlayerInfoUpdatePacket info) ownSkin(info);
        if (packet instanceof ClientboundPlayerInfoRemovePacket remove) keepSkins(remove);
        LocalPlayer player = mc.player;
        int self = player != null ? player.getId() : Integer.MIN_VALUE;
        if (packet instanceof ClientboundSetEntityDataPacket data && data.id() == self) {
            ownData(data);
            return;
        }

        if (export != null) export.before(packet, mc.level);
        // a new world and a new player: like the snapshot, its position must reach the handler to place the viewer
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
            puppet = null;
            digPos = null;
            health = 0;
        }
        if (packet instanceof ClientboundSetHealthPacket set) health = set.getHealth();
        if (puppet == null) {
            handle(packet, listener);
            if (packet instanceof ClientboundPlayerPositionPacket) spawnPuppet();
        } else if (packet instanceof ClientboundPlayerPositionPacket) {
            // the puppet's teleport is move()'s; the handler would move the viewer
        } else if (packet instanceof ClientboundPlayerAbilitiesPacket abilities) {
            // the puppet keeps the creative flag for placement; the rest would shove the spectator around
            puppet.getAbilities().instabuild = abilities.canInstabuild();
        } else if (packet instanceof ClientboundSetPassengersPacket passengers) {
            // the recorded player's ride is the puppet's; the handler would seat the viewer
            handle(withPuppet(passengers, self), listener);
        } else if (packet instanceof ClientboundHurtAnimationPacket hurt && hurt.id() == self) {
            // the recorded player's hurt is the puppet's: its red flash, its sound and the shake through its eyes
            handle(new ClientboundHurtAnimationPacket(PUPPET_ID, hurt.yaw()), listener);
        } else if (packet instanceof ClientboundDamageEventPacket damage && damage.entityId() == self) {
            handle(new ClientboundDamageEventPacket(PUPPET_ID, damage.sourceType(), damage.sourceCauseId(), damage.sourceDirectId(), damage.sourcePosition()), listener);
        } else if (!(packet instanceof ClientboundSetEntityMotionPacket motion && motion.id() == self)) {
            // self velocity would shove the spectator around
            handle(packet, listener);
        }
        // a max health above 20 may arrive after the health it allows, as in the snapshot
        if (packet instanceof ClientboundUpdateAttributesPacket attributes && attributes.getEntityId() == self
                && mc.player != null && health > mc.player.getHealth()) {
            mc.player.setHealth(health);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends PacketListener> void handle(Packet<T> packet, PacketListener listener) {
        packet.handle((T) listener);
    }

    // the recorded player's own metadata is the puppet's, all but the health that would kill it off; the golden hearts
    // also go to the viewer, whose player the hud reads
    private void ownData(ClientboundSetEntityDataPacket packet) {
        int healthId = AccessorLivingEntity.getHealthId().id();
        int absorptionId = AccessorPlayer.getAbsorptionId().id();
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
        for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            if (value.id() == healthId) continue;
            if (value.id() == absorptionId) mc.player.getEntityData().assignValues(List.of(value));
            ownData.put(value.id(), value);
            values.add(value);
        }
        if (puppet != null) puppet.getEntityData().assignValues(values);
    }

    private static ClientboundSetPassengersPacket withPuppet(ClientboundSetPassengersPacket packet, int self) {
        int[] passengers = packet.getPassengers().clone();
        for (int i = 0; i < passengers.length; i++) if (passengers[i] == self) passengers[i] = PUPPET_ID;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(packet.getVehicle());
            buf.writeVarIntArray(passengers);
            return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    private void serverbound(Packet<?> packet, long time) throws IOException {
        LocalPlayer player = mc.player;
        if (packet instanceof ServerboundMovePlayerPacket move) {
            // where it goes is move()'s; break speed drops fivefold in the air
            puppet.setOnGround(move.isOnGround());
        } else if (packet instanceof ServerboundPlayerActionPacket action) {
            dig(action, time);
        } else if (packet instanceof ServerboundUseItemOnPacket use) {
            place(use);
        } else if (packet instanceof ServerboundSwingPacket swing) {
            puppet.swing(swing.getHand());
        } else if (packet instanceof ServerboundAttackPacket) {
            player.resetAttackStrengthTicker(); // the first person hand dips with the cooldown
        } else if (packet instanceof ServerboundSetCarriedItemPacket carried) {
            // the inventory is shared, so the hotbar and the first person hand follow
            puppet.getInventory().setSelectedSlot(carried.getSlot());
        } else if (packet instanceof ServerboundContainerClosePacket) {
            // the server never echoes a close: the client shuts its own window and only tells the server
            if (mc.screen instanceof AbstractContainerScreen<?>) player.clientSideCloseContainer();
        } else if (packet instanceof ServerboundContainerClickPacket click) {
            // like MultiPlayerGameMode.handleContainerInput: the server never echoes a click it agrees with, the
            // client has already moved the stacks; a refusal resends the whole window
            AbstractContainerMenu container = player.containerMenu;
            if (container.containerId == click.containerId()) {
                container.clicked(click.slotNum(), click.buttonNum(), click.containerInput(), player);
            }
        } else if (packet instanceof ServerboundPlayerInputPacket input) {
            puppet.setShiftKeyDown(input.input().shift());
        } else if (packet instanceof ServerboundPlayerCommandPacket command) {
            switch (command.getAction()) {
                case START_SPRINTING -> puppet.setSprinting(true);
                case STOP_SPRINTING -> puppet.setSprinting(false);
                default -> {
                }
            }
        }
    }

    // the recorded player's client drew them between where they were last tick and where they are now, so a tick
    // ahead, interpolated between ticks, lands on the replay's present; the vanilla glide trails by several ticks
    private void move(long elapsed) {
        int from = movesUpTo(elapsed), to = movesUpTo(elapsed + 50);
        if (to == 0) return; // no move recorded yet
        Move a = moves.get(to - 1);
        Move m = a;
        if (to < moves.size() && !moves.get(to).snap) {
            Move b = moves.get(to);
            double f = (double) (elapsed + 50 - a.time) / (b.time - a.time);
            m = new Move(0, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f,
                    (float) (a.yaw + (b.yaw - a.yaw) * f), (float) (a.pitch + (b.pitch - a.pitch) * f), false);
        }
        boolean snap = false;
        // a teleport or a ride: there in no time at all, as the recorder's client set it down
        for (int i = from; i < to; i++) snap |= moves.get(i).snap;
        puppet.target(m.x, m.y, m.z, m.yaw, m.pitch, snap);
    }

    // how many moves were sent by then
    private int movesUpTo(long time) {
        int lo = 0, hi = moves.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (moves.get(mid).time <= time) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    // every move packet, with what it leaves out carried forward: a standing player sends position once a second.
    // Only packets that need no registries are read, so this runs before any configuration has been replayed
    private static List<Move> moves(List<RecordingFile.Entry> entries) {
        ProtocolInfo<?> clientbound = Protocols.clientboundPlay(RegistryAccess.EMPTY);
        ProtocolInfo<?> serverbound = Protocols.serverboundPlay(RegistryAccess.EMPTY);
        List<Move> moves = new ArrayList<>();
        Move last = null;
        boolean snap = true;
        int self = Integer.MIN_VALUE;
        for (RecordingFile.Entry entry : entries) {
            if (!entry.isPacket() || entry.isConfiguration()) continue;
            int id = Protocols.packetId(entry.data);
            try {
                if (!entry.isServerbound()) {
                    if (id == LOGIN) {
                        // the entity id leads the packet; the rest names dimension types, which need the registries
                        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(entry.data));
                        buf.readVarInt();
                        self = buf.readInt();
                    } else if (id == POSITION) {
                        ClientboundPlayerPositionPacket pos = (ClientboundPlayerPositionPacket) Protocols.decode(clientbound, entry.data);
                        PositionMoveRotation from = last != null
                                ? new PositionMoveRotation(new Vec3(last.x, last.y, last.z), Vec3.ZERO, last.yaw, last.pitch)
                                : new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0, 0);
                        PositionMoveRotation to = PositionMoveRotation.calculateAbsolute(from, pos.change(), pos.relatives());
                        last = new Move(entry.time, to.position().x, to.position().y, to.position().z, to.yRot(), to.xRot(), true);
                        snap = true;
                    } else if (id == PASSENGERS) {
                        ClientboundSetPassengersPacket passengers = (ClientboundSetPassengersPacket) Protocols.decode(clientbound, entry.data);
                        for (int passenger : passengers.getPassengers()) if (passenger == self) snap = true;
                    }
                } else if (MOVES.contains(id) && last != null) {
                    ServerboundMovePlayerPacket move = (ServerboundMovePlayerPacket) Protocols.decode(serverbound, entry.data);
                    last = new Move(entry.time, move.getX(last.x), move.getY(last.y), move.getZ(last.z),
                            move.getYRot(last.yaw), move.getXRot(last.pitch), snap);
                    moves.add(last);
                    snap = false;
                }
            } catch (Exception e) {
                log.warn("Skipping a packet at {}ms", entry.time, e);
            }
        }
        return moves;
    }

    private record Move(long time, double x, double y, double z, float yaw, float pitch,
                        boolean snap /* a teleport or a ride came before it: jump to it, do not glide */) {
    }

    // offline mode servers list the player with no skin; the header has it. A skin the server did send stays,
    // since that is what everyone saw, nick plugins included
    private void ownSkin(ClientboundPlayerInfoUpdatePacket packet) {
        if (!packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) || profile.properties().isEmpty()) return;
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(packet.entries());
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            ClientboundPlayerInfoUpdatePacket.Entry e = entries.get(i);
            if (e.profileId().equals(profile.id()) && e.profile() != null && !e.profile().properties().containsKey("textures")) {
                entries.set(i, new ClientboundPlayerInfoUpdatePacket.Entry(e.profileId(), new GameProfile(e.profileId(), e.profile().name(), profile.properties()),
                        e.listed(), e.latency(), e.gameMode(), e.displayName(), e.showHat(), e.listOrder(), e.chatSession()));
                changed = true;
            }
        }
        if (changed) ((AccessorPlayerInfoUpdatePacket) packet).setEntries(entries);
    }

    // NPCs are listed only long enough to spawn: a player looks up its skin on first render, which a seek or
    // the snapshot never gets to before the removal, so make it look now while the entry is still there
    private void keepSkins(ClientboundPlayerInfoRemovePacket packet) {
        if (mc.level == null) return;
        for (UUID id : packet.profileIds()) {
            if (mc.level.getPlayerByUUID(id) instanceof RemotePlayer player) player.getSkin();
        }
    }

    // the digger's client draws its own cracks and break effect; replay them for the puppet
    private void dig(ServerboundPlayerActionPacket dig, long time) throws IOException {
        BlockPos pos = dig.getPos();
        switch (dig.getAction()) {
            case START_DESTROY_BLOCK -> {
                RecordingFile.Entry end = digEnd(pos);
                if (end == null) {
                    destroy(pos); // creative or instant mining: the client sends no STOP and breaks it on the spot
                    return;
                }
                digPos = pos;
                digStart = time;
                if (((ServerboundPlayerActionPacket) decodeServerbound(end)).getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
                    digDuration = Math.max(1, end.time - time);
                } else {
                    // ponytail: no haste or fatigue on the puppet; aborted digs under potions crack at the wrong pace
                    float perTick = mc.level.getBlockState(pos).getDestroyProgress(puppet, mc.level, pos);
                    digDuration = perTick <= 0 ? Long.MAX_VALUE : Math.max(1, (long) Math.ceil(1 / perTick) * 50);
                }
            }
            case STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> {
                if (dig.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) destroy(pos);
                mc.level.destroyBlockProgress(PUPPET_ID, pos, -1);
                digPos = null;
            }
            default -> {
            }
        }
    }

    // like MultiPlayerGameMode.performUseItemOn: the client places at once, the server's block change undoes a refusal
    private void place(ServerboundUseItemOnPacket use) {
        InteractionHand hand = use.getHand();
        BlockHitResult hit = use.getHitResult();
        ClientLevel level = mc.level;
        // the stack is the viewer's own; the server only resends it when its count disagrees with the client's
        ItemStack held = puppet.getItemInHand(hand);
        boolean hasItem = !puppet.getMainHandItem().isEmpty() || !puppet.getOffhandItem().isEmpty();
        if (!(puppet.isSecondaryUseActive() && hasItem)) {
            BlockState state = level.getBlockState(hit.getBlockPos());
            InteractionResult result = state.useItemOn(held, level, puppet, hand, hit);
            if (result.consumesAction()) return;
            if (result instanceof InteractionResult.TryEmptyHandInteraction && hand == InteractionHand.MAIN_HAND
                    && state.useWithoutItem(level, puppet, hit).consumesAction()) return;
        }
        if (held.isEmpty() || puppet.getCooldowns().isOnCooldown(held)) return;
        int count = held.getCount();
        InteractionResult result = held.useOn(new UseOnContext(puppet, hand, hit));
        if (puppet.hasInfiniteMaterials()) held.setCount(count);
        if (result.consumesAction()) mc.getEntityRenderDispatcher().getItemInHandRenderer().itemUsed(hand); // the hand dips
    }

    // like MultiPlayerGameMode.destroyBlock: gone at once, the server's block change undoes a refusal
    private void destroy(BlockPos pos) {
        ClientLevel level = mc.level;
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (state.isAir() || block instanceof GameMasterBlock && !puppet.canUseGameMasterBlocks()) return;
        if (!puppet.getMainHandItem().canDestroyBlock(state, level, pos, puppet)) return;
        block.playerWillDestroy(level, pos, state, puppet); // the break particles and sound
        FluidState fluid = level.getFluidState(pos);
        if (level.setBlock(pos, fluid.createLegacyBlock(), Block.UPDATE_ALL_IMMEDIATE)) block.destroy(level, pos, state);
    }

    // a dig in progress always ends in a STOP or ABORT for its own block; anything else means it broke instantly
    private RecordingFile.Entry digEnd(BlockPos pos) throws IOException {
        for (int i = index; i < entries.size(); i++) {
            RecordingFile.Entry e = entries.get(i);
            if (e.direction != RecordingFile.SERVERBOUND || Protocols.packetId(e.data) != PLAYER_ACTION) continue;
            ServerboundPlayerActionPacket next = (ServerboundPlayerActionPacket) decodeServerbound(e);
            ServerboundPlayerActionPacket.Action action = next.getAction();
            if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) return null;
            if (action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                    || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
                return next.getPos().equals(pos) ? e : null;
            }
        }
        return null;
    }

    private Packet<?> decodeServerbound(RecordingFile.Entry entry) throws IOException {
        return Protocols.decode(Protocols.serverboundPlay(mc.getConnection().registryAccess()), entry.data);
    }

    // the server leaves a player's own sounds to their client, so the recording never has their footsteps;
    // this plays them the way the recorder's client did
    public static final class Puppet extends RemotePlayer {

        private double targetX, targetY, targetZ;
        private float targetYaw, targetPitch;
        private boolean hasTarget;
        private float walked;
        private int nextStep;

        Puppet(ClientLevel level, GameProfile profile) {
            super(level, profile);
        }

        // reached within the next tick, as the recorder's own client moved; a snap is there already
        void target(double x, double y, double z, float yaw, float pitch, boolean snap) {
            targetX = x;
            targetY = y;
            targetZ = z;
            targetYaw = yaw;
            targetPitch = pitch;
            hasTarget = true;
            if (snap && !isPassenger()) snapTo(x, y, z, yaw, pitch);
        }

        // Player's goes to everyone near but this player, which a client never sends anywhere
        @Override
        public void playSound(SoundEvent sound, float volume, float pitch) {
            if (!isSilent()) level().playLocalSound(getX(), getY(), getZ(), sound, getSoundSource(), volume, pitch, false);
        }

        // what the recorded player's own screen was drawn for
        public GameType getGameType() {
            GameType mode = gameMode();
            return mode != null ? mode : GameType.SURVIVAL;
        }

        // ponytail: no swim splashes, no amethyst chimes; add from Entity.move if missed
        @Override
        public void aiStep() {
            double x = getX(), y = getY(), z = getZ();
            super.aiStep();
            if (!hasTarget) return;
            // on a mount, the mount puts it back where it sits
            if (!isPassenger()) setPos(targetX, targetY, targetZ);
            setYRot(targetYaw);
            setXRot(targetPitch);
            setYHeadRot(targetYaw); // a player's head is where they look
            if (isPassenger() || onGround() && isShiftKeyDown()) return;
            BlockPos pos = BlockPos.containing(getX(), getY() - 0.2, getZ());
            BlockState state = level().getBlockState(pos);
            double dy = state.is(BlockTags.CLIMBABLE) ? getY() - y : 0;
            walked += (float) (Mth.length(getX() - x, dy, getZ() - z) * 0.6);
            if (walked > nextStep && !state.isAir()) {
                nextStep = (int) walked + 1;
                playStepSound(pos, state);
            }
        }

        // the puppet is no one's to push or be pushed
        @Override
        public boolean isPushable() {
            return false;
        }
    }
}
