package net.toshimichi.recorder.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class MixinEntity {

    // through the recorded player's eyes the viewer's own look is the puppet's, which the mouse must not turn
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void keepRecordedLook(double xo, double yo, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player && Replay.possessed() != null) ci.cancel();
    }
}
