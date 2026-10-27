package net.toshimichi.recorder.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// through the recorded player's eyes the hand is drawn from the viewer's own player, which Replay.afterTick keeps
// looking and swinging as the puppet does; the held item already is theirs, as the inventory is shared
// ponytail: the bare arm only; the arms holding a map still wear the viewer's skin
@Mixin(ItemInHandRenderer.class)
public abstract class MixinItemInHandRenderer {

    // the bare arm: their model (slim or not), sleeves and skin
    @ModifyVariable(method = "renderPlayerArm", at = @At("STORE"))
    private AbstractClientPlayer recordedArm(AbstractClientPlayer player) {
        Replay.Puppet puppet = Replay.possessed();
        return puppet != null ? puppet : player;
    }
}
