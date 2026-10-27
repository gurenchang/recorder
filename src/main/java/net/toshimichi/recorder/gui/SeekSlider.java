package net.toshimichi.recorder.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.toshimichi.recorder.Replay;

// follows the replay while left alone; jumps on release so dragging does not reload the world per pixel
public class SeekSlider extends AbstractSliderButton {

    private final Replay replay;
    private boolean dragging;

    public SeekSlider(int x, int y, int width, Replay replay) {
        super(x, y, width, 20, Component.empty(), 0);
        this.replay = replay;
        follow();
    }

    private void follow() {
        value = replay.duration() == 0 ? 0 : (double) replay.elapsed() / replay.duration();
        updateMessage();
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!dragging) follow();
        super.extractWidgetRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(format(target()) + " / " + format(replay.duration())));
    }

    @Override
    protected void applyValue() {
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        super.onClick(event, doubleClick);
        dragging = true;
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        if (!dragging) return;
        dragging = false;
        replay.jump(target());
    }

    private long target() {
        return (long) (value * replay.duration());
    }

    private static String format(long millis) {
        long seconds = millis / 1000;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
