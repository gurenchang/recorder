package net.toshimichi.recorder.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.player.Player;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRenderer {

    // invisible to whoever recorded it, not see-through as to the spectating viewer; teammates stay see-through
    @ModifyArg(method = {"extractRenderState", "shouldShowName"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;isInvisibleTo(Lnet/minecraft/world/entity/player/Player;)Z"))
    private Player recordedEyes(Player player) {
        Replay.Puppet puppet = Replay.recorded();
        return puppet != null ? puppet : player;
    }
}
