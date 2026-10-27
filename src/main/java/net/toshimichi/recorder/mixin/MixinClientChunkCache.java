package net.toshimichi.recorder.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.toshimichi.recorder.WorldExport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCache {

    @Shadow
    @Final
    ClientLevel level;

    // a smaller ring copies over only what still fits and drops the rest without unloading it
    @Inject(method = "updateViewRadius", at = @At("HEAD"))
    private void export(int viewRange, CallbackInfo ci) {
        WorldExport.unloadingAll(level);
    }
}
