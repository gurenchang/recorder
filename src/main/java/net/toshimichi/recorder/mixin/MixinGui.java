package net.toshimichi.recorder.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.player.Player;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Gui.class)
public abstract class MixinGui {

    // the bars read the viewing entity, but the recorded health, food, experience and effects all land on the
    // viewer's own player, as they did on the recorder's
    // ponytail: air and mount health read the viewer's own position and ride; forward them if missed
    @Inject(method = "getCameraPlayer", at = @At("HEAD"), cancellable = true)
    private void viewerBars(CallbackInfoReturnable<Player> cir) {
        if (Replay.possessed() != null) cir.setReturnValue(Minecraft.getInstance().player);
    }
}
