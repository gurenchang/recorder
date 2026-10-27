package net.toshimichi.recorder.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import net.toshimichi.recorder.Recording;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// after decrypt and decompress, before the packet object exists: the buffer is one whole frame, id + payload
@Mixin(PacketDecoder.class)
public class MixinPacketDecoder {

    @Shadow
    @Final
    private ProtocolInfo<?> protocolInfo;

    @Inject(method = "decode", at = @At("HEAD"))
    private void record(ChannelHandlerContext ctx, ByteBuf input, List<Object> out, CallbackInfo ci) {
        byte direction = Recording.direction(protocolInfo.id(), protocolInfo.flow() == PacketFlow.SERVERBOUND);
        if (direction >= 0) Recording.record(ctx.channel(), direction, input);
    }
}
