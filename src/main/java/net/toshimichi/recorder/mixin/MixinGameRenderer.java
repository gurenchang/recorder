package net.toshimichi.recorder.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Unique
    private GameType viewerGameType; // set only while a frame is gathered or drawn as the recorded player's

    // through the recorded player's eyes, draw the frame for their game mode: hand, hotbar, bars and crosshair
    // where the spectating viewer would get none. Only for the frame, so the viewer's input keeps their own
    @Inject(method = {"extract", "render"}, at = @At("HEAD"))
    private void asRecorded(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        Replay.Puppet puppet = Replay.possessed();
        Minecraft mc = Minecraft.getInstance();
        if (puppet == null || mc.gameMode == null) return;
        viewerGameType = mc.gameMode.getPlayerMode();
        ((AccessorMultiPlayerGameMode) mc.gameMode).setLocalPlayerMode(puppet.getGameType());
    }

    @Inject(method = {"extract", "render"}, at = @At("RETURN"))
    private void asViewer(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (viewerGameType == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) ((AccessorMultiPlayerGameMode) mc.gameMode).setLocalPlayerMode(viewerGameType);
        viewerGameType = null;
    }

    // the hand is lit where the recorded player stands, not wherever the viewer's own body was left
    @ModifyArg(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;getPackedLightCoords(Lnet/minecraft/world/entity/Entity;F)I"))
    private Entity recordedLight(Entity entity) {
        Replay.Puppet puppet = Replay.possessed();
        return puppet != null ? puppet : entity;
    }
}
