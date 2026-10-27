package net.toshimichi.recorder.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.toshimichi.recorder.WorldExport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {

    // every way a chunk leaves the client's ring ends here, the server's forget packet and a newer chunk in its slot alike
    @Inject(method = "unload", at = @At("HEAD"))
    private void export(LevelChunk chunk, CallbackInfo ci) {
        WorldExport.unloading(chunk);
    }
}
