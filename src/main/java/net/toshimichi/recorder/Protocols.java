package net.toshimichi.recorder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The vanilla codecs, bound the way the client binds them, for packets that never touch a real pipeline. */
final class Protocols {

    private static final GameProtocols.Context CLIENT_CONTEXT = () -> true; // as ClientConfigurationPacketListenerImpl binds it
    private static final Map<PacketType<?>, Integer> CLIENTBOUND_PLAY_IDS = ids(GameProtocols.CLIENTBOUND_TEMPLATE.details());
    private static final Map<PacketType<?>, Integer> SERVERBOUND_PLAY_IDS = ids(GameProtocols.SERVERBOUND_TEMPLATE.details());
    private static final Set<PacketType<?>> CONFIGURATION_TYPES = new HashSet<>();

    static {
        CONFIGURATION_TYPES.addAll(ids(ConfigurationProtocols.CLIENTBOUND_TEMPLATE.details()).keySet());
        CONFIGURATION_TYPES.addAll(ids(ConfigurationProtocols.SERVERBOUND_TEMPLATE.details()).keySet());
    }

    // binding builds the whole id table, so keep the last one per registry set; both threads may ask
    private static RegistryAccess boundFor;
    private static ProtocolInfo<?> clientboundPlay, serverboundPlay;

    private Protocols() {
    }

    static synchronized ProtocolInfo<?> clientboundPlay(RegistryAccess registries) {
        bind(registries);
        return clientboundPlay;
    }

    static synchronized ProtocolInfo<?> serverboundPlay(RegistryAccess registries) {
        bind(registries);
        return serverboundPlay;
    }

    private static void bind(RegistryAccess registries) {
        if (boundFor == registries) return;
        clientboundPlay = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries));
        serverboundPlay = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), CLIENT_CONTEXT);
        boundFor = registries;
    }

    static ProtocolInfo<?> configuration(boolean serverbound) {
        return serverbound ? ConfigurationProtocols.SERVERBOUND : ConfigurationProtocols.CLIENTBOUND;
    }

    // packets like keep alive and custom payload exist in both; the listener's state decides those
    static ConnectionProtocol protocolOf(Packet<?> packet, ConnectionProtocol listening) {
        boolean configuration = CONFIGURATION_TYPES.contains(packet.type());
        boolean play = CLIENTBOUND_PLAY_IDS.containsKey(packet.type()) || SERVERBOUND_PLAY_IDS.containsKey(packet.type());
        if (configuration && play) return listening;
        return configuration ? ConnectionProtocol.CONFIGURATION : ConnectionProtocol.PLAY;
    }

    static int clientboundPlayId(PacketType<?> type) {
        return CLIENTBOUND_PLAY_IDS.get(type);
    }

    static int serverboundPlayId(PacketType<?> type) {
        return SERVERBOUND_PLAY_IDS.get(type);
    }

    // the varint id the frame starts with
    static int packetId(byte[] data) {
        return VarInt.read(Unpooled.wrappedBuffer(data));
    }

    @SuppressWarnings("unchecked")
    static byte[] encode(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            ((ProtocolInfo<net.minecraft.network.PacketListener>) protocol).codec().encode(buf, (Packet<? super net.minecraft.network.PacketListener>) packet);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
    }

    static Packet<?> decode(ProtocolInfo<?> protocol, byte[] data) throws IOException {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            Packet<?> packet = protocol.codec().decode(buf);
            if (buf.isReadable()) throw new IOException(packet.type() + " left " + buf.readableBytes() + " bytes unread");
            return packet;
        } finally {
            buf.release();
        }
    }

    private static Map<PacketType<?>, Integer> ids(ProtocolInfo.Details details) {
        Map<PacketType<?>, Integer> ids = new HashMap<>();
        details.listPackets(ids::put);
        return ids;
    }
}
