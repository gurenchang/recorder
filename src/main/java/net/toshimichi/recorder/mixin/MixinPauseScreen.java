package net.toshimichi.recorder.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.toshimichi.recorder.Config;
import net.toshimichi.recorder.Recording;
import net.toshimichi.recorder.Replay;
import net.toshimichi.recorder.gui.SeekSlider;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class MixinPauseScreen extends Screen {

    @Shadow
    private @Nullable Button disconnectButton;

    protected MixinPauseScreen(Component title) {
        super(title);
    }

    // the row under Disconnect; a replay's Disconnect leaves it, see MixinMinecraft
    @Inject(method = "init", at = @At("RETURN"))
    private void addRecorderButtons(CallbackInfo ci) {
        if (disconnectButton == null) return; // the menu is hidden (F3 + Esc)
        int x = disconnectButton.getX(), y = disconnectButton.getY() + 24, w = disconnectButton.getWidth();
        Replay replay = Replay.current();
        int half = (w - 4) / 2;
        if (replay == null) {
            // the two run independently: starting or stopping one leaves the other as it was
            addRenderableWidget(Button.builder(recordLabel(), button -> {
                Recording.toggle(minecraft);
                button.setMessage(recordLabel());
            }).bounds(x, y, half, 20).build());
            addRenderableWidget(Button.builder(bufferLabel(), button -> {
                Config.get().bufferEnabled = !Config.get().bufferEnabled;
                Config.save();
                button.setMessage(bufferLabel());
            }).bounds(x + w - half, y, half, 20).build());
        } else {
            addRenderableWidget(Button.builder(pauseLabel(replay), button -> {
                replay.togglePause();
                button.setMessage(pauseLabel(replay));
            }).bounds(x, y, half, 20).build());
            addRenderableWidget(new SeekSlider(x + w - half, y, half, replay));
            addRenderableWidget(Button.builder(gameModeLabel(replay), button -> {
                replay.cycleGameType();
                button.setMessage(gameModeLabel(replay));
            }).bounds(x, y + 24, w, 20).build());
        }
    }

    private static Component recordLabel() {
        return Component.literal(Recording.isRecording() ? "Stop Recording" : "Start Recording");
    }

    private static Component bufferLabel() {
        return Component.literal("Replay Buffer: " + (Config.get().bufferEnabled ? "ON" : "OFF"));
    }

    private static Component gameModeLabel(Replay replay) {
        return Component.translatable("selectWorld.gameMode").append(": ").append(replay.getGameType().getShortDisplayName());
    }

    private static Component pauseLabel(Replay replay) {
        return Component.literal(replay.isPaused() ? "Resume" : "Pause");
    }
}
