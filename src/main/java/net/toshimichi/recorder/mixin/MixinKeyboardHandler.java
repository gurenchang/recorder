package net.toshimichi.recorder.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.toshimichi.recorder.Recording;
import net.toshimichi.recorder.RecordingFile;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class MixinKeyboardHandler {

    // in the world only, and transitions only: key repeat is the OS, not the player
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void record(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (Minecraft.getInstance().screen != null || action == GLFW.GLFW_REPEAT) return;
        Recording.recordInput(RecordingFile.KEYBOARD, event.key(), action == GLFW.GLFW_PRESS);
    }
}
