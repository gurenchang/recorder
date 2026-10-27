package net.toshimichi.recorder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class Recorder implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            Replay replay = Replay.current();
            if (replay != null) replay.tick();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Replay replay = Replay.current();
            if (replay != null) replay.afterTick();
        });
        // a recording must not outlive its connection, or the next server's packets land in the same file
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> Recording.stop());
    }
}
