package net.toshimichi.recorder.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.toshimichi.recorder.gui.RecordingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {

    protected MixinTitleScreen(Component title) {
        super(title);
    }

    // left of the Multiplayer button
    @Inject(method = "init", at = @At("RETURN"))
    private void addRecordingsButton(CallbackInfo ci) {
        addRenderableWidget(Button.builder(Component.literal("R"), button -> minecraft.setScreen(new RecordingsScreen(this)))
                .bounds(width / 2 - 124, height / 4 + 72, 20, 20).build());
    }
}
