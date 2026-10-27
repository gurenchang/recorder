package net.toshimichi.recorder.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.toshimichi.recorder.Recording;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// RETURN is after the buffer is filled and before compression and encryption, which are handlers of their own
@Mixin(PacketEncoder.class)
public class MixinPacketEncoder {

    @Shadow
    @Final
    private ProtocolInfo<?> protocolInfo;

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("RETURN"))
    private void record(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
        byte direction = Recording.direction(protocolInfo.id(), protocolInfo.flow() == PacketFlow.SERVERBOUND);
        if (direction >= 0) Recording.record(ctx.channel(), direction, output);
    }
}
