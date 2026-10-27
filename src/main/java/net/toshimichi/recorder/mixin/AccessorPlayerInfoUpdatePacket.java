package net.toshimichi.recorder.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

// the public constructors take ServerPlayers; the client has PlayerInfo
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface AccessorPlayerInfoUpdatePacket {

    @Mutable
    @Accessor("entries")
    void setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
