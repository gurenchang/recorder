package net.toshimichi.recorder.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    // the pause menu's Disconnect: vanilla would hand a replay over to the multiplayer screen
    @Inject(method = "disconnectFromWorld", at = @At("HEAD"), cancellable = true)
    private void exitReplay(Component message, CallbackInfo ci) {
        Replay replay = Replay.current();
        if (replay == null) return;
        replay.exit();
        ci.cancel();
    }
}
