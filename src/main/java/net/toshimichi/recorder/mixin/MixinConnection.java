package net.toshimichi.recorder.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.toshimichi.recorder.Recording;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The integrated server talks over an in-memory channel, which carries packet objects and never runs the codecs, so
// singleplayer is captured here at the packet level instead. Remote connections are left to the codec mixins, which
// see the bytes after compression and encryption are peeled off.
@Mixin(Connection.class)
public abstract class MixinConnection {

    @Shadow
    @Final
    private PacketFlow receiving;

    @Shadow
    private Channel channel;

    @Shadow
    private volatile PacketListener packetListener;

    @Shadow
    public abstract boolean isMemoryConnection();

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void recordSent(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (receiving == PacketFlow.CLIENTBOUND && channel != null && isMemoryConnection()) Recording.record(channel, true, packet, listening());
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void recordReceived(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (receiving == PacketFlow.CLIENTBOUND && isMemoryConnection()) Recording.record(ctx.channel(), false, packet, listening());
    }

    private ConnectionProtocol listening() {
        PacketListener listener = packetListener;
        return listener != null ? listener.protocol() : ConnectionProtocol.PLAY;
    }
}
