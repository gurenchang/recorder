package net.toshimichi.recorder.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// setLocalMode also rewrites the player's abilities, which a swap for one frame must not do
@Mixin(MultiPlayerGameMode.class)
public interface AccessorMultiPlayerGameMode {

    @Accessor("localPlayerMode")
    void setLocalPlayerMode(GameType mode);
}
