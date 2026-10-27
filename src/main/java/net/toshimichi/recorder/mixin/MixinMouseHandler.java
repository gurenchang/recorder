package net.toshimichi.recorder.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.toshimichi.recorder.Recording;
import net.toshimichi.recorder.RecordingFile;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// buttons only: motion and scroll fire far too often to be worth a row
@Mixin(MouseHandler.class)
public class MixinMouseHandler {

    @Inject(method = "onButton", at = @At("HEAD"))
    private void record(long handle, MouseButtonInfo rawButtonInfo, int action, CallbackInfo ci) {
        if (Minecraft.getInstance().screen != null) return;
        Recording.recordInput(RecordingFile.MOUSE, rawButtonInfo.button(), action == GLFW.GLFW_PRESS);
    }
}
