package net.toshimichi.recorder.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.toshimichi.recorder.JsonlExport;
import net.toshimichi.recorder.Recording;
import net.toshimichi.recorder.Replay;
import net.toshimichi.recorder.ReplayBuffer;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class RecordingsScreen extends Screen {

    private static final Logger log = LogUtils.getLogger();

    private final Screen parent;
    private RecordingList list;
    private Button replayButton;
    private Button deleteButton;
    private Button convertButton;
    private Button exportButton;
    private Button saveBufferButton;
    private Component status = Component.empty();
    // an export outlives the screen it was started from; reopening shows how it is going
    private static volatile boolean exporting;
    private static volatile Component exportStatus;
    private Component shownExport;

    public RecordingsScreen(Screen parent) {
        super(Component.literal("Recordings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        list = addRenderableWidget(new RecordingList(files()));
        replayButton = addRenderableWidget(Button.builder(Component.literal("Replay"), button -> replay())
                .bounds(width / 2 - 154, height - 52, 100, 20).build());
        convertButton = addRenderableWidget(Button.builder(Component.literal("Convert to World"), button -> convert())
                .bounds(width / 2 - 50, height - 52, 100, 20).build());
        deleteButton = addRenderableWidget(Button.builder(Component.literal("Delete"), button -> delete())
                .bounds(width / 2 + 54, height - 52, 100, 20).build());
        exportButton = addRenderableWidget(Button.builder(Component.literal("Export JSONL"), button -> export())
                .bounds(width / 2 - 154, height - 28, 100, 20).build());
        saveBufferButton = addRenderableWidget(Button.builder(Component.literal("Save Buffer"), button -> saveBuffer())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width / 2 + 54, height - 28, 100, 20).build());
        updateButtons();
    }

    // timestamped names sort chronologically, so reversed is newest first
    private static List<File> files() {
        File[] found = Recording.directory().listFiles((dir, name) -> name.endsWith(".mcrc"));
        List<File> files = found == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(found));
        files.sort(Comparator.comparing(File::getName).reversed());
        return files;
    }

    private File selected() {
        RecordingList.Entry entry = list.getSelected();
        return entry != null ? entry.file : null;
    }

    // an export rebuilds the recording's registries, as a replay does: one at a time
    private void updateButtons() {
        replayButton.active = convertButton.active = deleteButton.active = exportButton.active = selected() != null && !exporting;
        // what the replay buffer held when the last connection closed, a kick included
        saveBufferButton.active = ReplayBuffer.hasClosed();
    }

    private void saveBuffer() {
        ReplayBuffer.saveClosed(minecraft, message -> {
            status = message;
            if (minecraft.screen == this) minecraft.setScreen(this); // re-runs init, which lists the new file
        });
    }

    @Override
    public void tick() {
        updateButtons();
        // only when it changes, so the other buttons' messages are not overwritten by an old export's
        Component progress = exportStatus;
        if (progress != null && progress != shownExport) status = shownExport = progress;
    }

    // recordings/<name>.jsonl, decoded on a worker thread: a long recording takes seconds
    private void export() {
        File file = selected();
        if (file == null || exporting) return;
        File jsonl = JsonlExport.jsonlFor(file);
        exporting = true;
        exportStatus = Component.literal("Exporting " + file.getName() + "...");
        Thread thread = new Thread(() -> {
            try {
                JsonlExport.Result result = JsonlExport.export(file, jsonl,
                        lines -> exportStatus = Component.literal("Exporting " + file.getName() + ": " + lines + " rows"));
                exportStatus = Component.literal("Exported " + jsonl.getName() + ": " + result.lines() + " rows, " + result.errors() + " errors")
                        .withStyle(result.errors() == 0 ? ChatFormatting.GRAY : ChatFormatting.YELLOW);
            } catch (IOException | RuntimeException e) {
                log.error("Failed to export {}", file, e);
                exportStatus = Component.literal(String.valueOf(e.getMessage())).withStyle(ChatFormatting.RED);
            } finally {
                exporting = false;
            }
        }, "Recorder JSONL export");
        thread.setDaemon(true);
        thread.start();
    }

    private void delete() {
        File file = selected();
        if (file == null) return;
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed && !file.delete()) status = Component.literal("Could not delete the file").withStyle(ChatFormatting.RED);
            minecraft.setScreen(this); // re-runs init, which refreshes the list
        }, Component.literal("Delete this recording?"), Component.literal(file.getName())));
    }

    private void replay() {
        File file = selected();
        if (file == null) return;
        try {
            Replay.start(file, this);
        } catch (IOException | RuntimeException e) {
            log.error("Failed to open {}", file, e);
            status = Component.literal(String.valueOf(e.getMessage())).withStyle(ChatFormatting.RED);
            if (minecraft.screen != this) minecraft.setScreen(this);
        }
    }

    private void convert() {
        File file = selected();
        if (file == null) return;
        try {
            status = Component.literal("Saved as world " + Replay.convert(file, this));
        } catch (IOException | RuntimeException e) {
            log.error("Failed to convert {}", file, e);
            status = Component.literal(String.valueOf(e.getMessage())).withStyle(ChatFormatting.RED);
            if (minecraft.screen != this) minecraft.setScreen(this);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        graphics.centeredText(font, status, width / 2, height - 66, 0xFFA0A0A0);
    }

    private class RecordingList extends ObjectSelectionList<RecordingList.Entry> {

        RecordingList(List<File> files) {
            super(RecordingsScreen.this.minecraft, RecordingsScreen.this.width, RecordingsScreen.this.height - 32 - 76, 32, 24);
            for (File file : files) addEntry(new Entry(file));
        }

        private class Entry extends ObjectSelectionList.Entry<Entry> {

            private final File file;

            Entry(File file) {
                this.file = file;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
                graphics.text(font, file.getName(), getContentX() + 2, getContentY() + 1, 0xFFFFFFFF);
                graphics.text(font, String.format("%.1f MB", file.length() / 1048576.0), getContentX() + 2, getContentY() + 12, 0xFF808080);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                RecordingList.this.setSelected(this);
                updateButtons();
                if (doubleClick) replay();
                return super.mouseClicked(event, doubleClick);
            }

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", file.getName());
            }
        }
    }
}
