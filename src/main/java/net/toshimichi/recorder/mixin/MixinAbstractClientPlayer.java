package net.toshimichi.recorder.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.GameType;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayer {

    // vanilla asks the tab list, which lists the recorded session, not the viewer; noclip and
    // scroll-to-change-fly-speed both hang off this
    @Inject(method = "gameMode", at = @At("HEAD"), cancellable = true)
    private void viewerGameMode(CallbackInfoReturnable<GameType> cir) {
        Replay replay = Replay.current();
        if (replay != null && (Object) this instanceof LocalPlayer) cir.setReturnValue(replay.getGameType());
    }
}
