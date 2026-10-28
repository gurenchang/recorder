package net.toshimichi.recorder;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

public class Recorder implements ClientModInitializer {

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("recorder", "recorder"));
    private static final KeyMapping MARKER = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.recorder.marker", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY));
    private static final KeyMapping SAVE_BUFFER = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.recorder.save_buffer", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY));

    @Override
    public void onInitializeClient() {
        Config.get();
        ReplayBuffer.init();
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            Replay replay = Replay.current();
            if (replay != null) replay.tick();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Replay replay = Replay.current();
            if (replay != null) replay.afterTick();
            ReplayBuffer.tick(mc);
            while (MARKER.consumeClick()) {
                if (mc.screen == null && mc.level != null) mark(mc, RecordingFile.MARKER_KEY, "");
            }
            while (SAVE_BUFFER.consumeClick()) {
                if (mc.screen == null && mc.level != null) ReplayBuffer.save(mc);
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(ClientCommands.literal("recmark")
                .executes(c -> mark(Minecraft.getInstance(), RecordingFile.MARKER_COMMAND, ""))
                .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> mark(Minecraft.getInstance(), RecordingFile.MARKER_COMMAND, StringArgumentType.getString(c, "text"))))));
        // a recording must not outlive its connection, or the next server's packets land in the same file
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            ReplayBuffer.closeAll();
            Recording.stopAll();
        });
    }

    // the action bar, not the chat: the chat is part of what is being recorded. The number and time are the manual
    // recording's if there is one, else those of the buffer segment a save would keep
    private static int mark(Minecraft mc, byte source, String label) {
        Map<Recording.Sink, Recording.Mark> marks = Recording.mark(source, label);
        Recording.Mark mark = marks.get(Recording.manual());
        if (mark == null) mark = marks.get(ReplayBuffer.saveTarget());
        if (mark == null && !marks.isEmpty()) mark = marks.values().iterator().next();
        mc.gui.setOverlayMessage(Component.literal(mark == null ? "Not recording"
                : "Marker #" + mark.number() + " at " + RecordingFile.formatTime(mark.elapsed())), false);
        return 1;
    }
}
