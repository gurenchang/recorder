package net.toshimichi.recorder;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps the last N minutes saveable after the fact. A new segment, a whole recording of its own with header and
 * snapshot, starts every N/2 minutes and is written until it is no longer needed; saving copies the newest one that
 * started at least N minutes ago, so a save is N to 1.5N minutes long and nothing needs stitching. At most three
 * segments are open at a time, all written to at once.
 */
public final class ReplayBuffer {

    private static final Logger log = LogUtils.getLogger();
    private static final long RETRY_MILLIS = 60_000; // after a segment failed to start

    private static final List<Recording.Sink> segments = new ArrayList<>(); // open, oldest first
    // what a disconnect or switching the buffer off closed, kept saveable until the buffer starts again
    private static final List<Closed> closed = new ArrayList<>();
    private static long closedAt;
    private static long retryAt;
    private static volatile boolean saving;

    private record Closed(File file, long start) {
    }

    // which segments to save from and to throw away, given when each started, oldest first
    public record Plan(int save, List<Integer> delete, boolean startNew) {
    }

    private ReplayBuffer() {
    }

    // save: the newest segment that started at least `length` before now, else the oldest (-1 if there are none).
    // Anything older than that one is no longer needed. A new segment is due every length / 2
    public static Plan plan(List<Long> starts, long now, long length) {
        int anchor = -1;
        for (int i = 0; i < starts.size(); i++) if (starts.get(i) <= now - length) anchor = i;
        List<Integer> delete = new ArrayList<>();
        for (int i = 0; i < anchor; i++) delete.add(i);
        int save = starts.isEmpty() ? -1 : Math.max(anchor, 0);
        boolean startNew = starts.isEmpty() || now - starts.getLast() >= length / 2;
        return new Plan(save, delete, startNew);
    }

    private static File directory() {
        return new File(Recording.directory(), ".buffer"); // the dot keeps it out of the Recordings screen's list
    }

    // what a previous run left behind
    public static void init() {
        File[] files = directory().listFiles();
        if (files != null) for (File file : files) if (!file.delete()) log.warn("Could not delete {}", file);
    }

    private static long length() {
        return Config.get().bufferMinutes * 60_000L;
    }

    private static List<Long> starts(List<Recording.Sink> sinks) {
        return sinks.stream().map(Recording.Sink::start).toList();
    }

    // END_CLIENT_TICK: start, rotate and drop segments
    public static void tick(Minecraft mc) {
        if (!Config.get().bufferEnabled) {
            if (!segments.isEmpty()) closeAll();
            return;
        }
        // in a world on a live connection, not while it loads, reconfigures or is a replay
        if (mc.level == null || mc.player == null || mc.getConnection() == null || Replay.current() != null
                || mc.screen instanceof LevelLoadingScreen) return;
        long now = System.currentTimeMillis();
        Plan plan = plan(starts(segments), now, length());
        for (int i = plan.delete().size() - 1; i >= 0; i--) {
            Recording.Sink sink = segments.remove((int) plan.delete().get(i));
            Recording.close(sink);
            if (!sink.file().delete()) log.warn("Could not delete {}", sink.file());
        }
        if (plan.startNew() && now >= retryAt) start(mc, now);
    }

    private static void start(Minecraft mc, long now) {
        // a new session's buffer replaces the last one's leftovers
        for (Closed old : closed) if (!old.file().delete()) log.warn("Could not delete {}", old.file());
        closed.clear();
        directory().mkdirs();
        File file = new File(directory(), new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS").format(new Date(now)) + ".mcrc");
        try {
            segments.add(Recording.open(mc, file, "Buffer"));
        } catch (IOException | RuntimeException e) {
            log.error("Failed to start a replay buffer segment", e);
            file.delete();
            retryAt = now + RETRY_MILLIS;
        }
    }

    // the connection is gone, or the buffer was switched off: the segments stop, but stay saveable from the title screen
    public static void closeAll() {
        if (segments.isEmpty()) return;
        for (Recording.Sink sink : segments) {
            Recording.close(sink);
            closed.add(new Closed(sink.file(), sink.start()));
        }
        segments.clear();
        closedAt = System.currentTimeMillis();
    }

    // the segment a save would copy right now, for numbering markers the way the saved file will; null if none
    public static Recording.Sink saveTarget() {
        if (segments.isEmpty()) return null;
        return segments.get(plan(starts(segments), System.currentTimeMillis(), length()).save());
    }

    public static boolean hasClosed() {
        return !closed.isEmpty() && !saving;
    }

    // the Save Replay Buffer key
    public static void save(Minecraft mc) {
        if (!Config.get().bufferEnabled) {
            mc.gui.setOverlayMessage(Component.literal("Replay buffer is off"), false);
            return;
        }
        Recording.Sink sink = saveTarget();
        if (sink == null) {
            mc.gui.setOverlayMessage(Component.literal("Replay buffer has nothing yet"), false);
            return;
        }
        if (saving) return;
        long covered = System.currentTimeMillis() - sink.start();
        long size;
        try {
            size = sink.flushedLength();
        } catch (IOException e) {
            log.error("Failed to flush {}", sink.file(), e);
            mc.gui.setOverlayMessage(Component.literal("Failed to save the replay buffer: " + e.getMessage()).withStyle(ChatFormatting.RED), false);
            return;
        }
        copy(mc, sink.file(), sink.start(), size, covered, message -> mc.gui.setOverlayMessage(message, false));
    }

    // the Recordings screen's Save Buffer: what the last disconnect closed, as of that moment
    public static void saveClosed(Minecraft mc, Consumer<Component> done) {
        if (closed.isEmpty() || saving) return;
        List<Long> starts = closed.stream().map(Closed::start).toList();
        Closed target = closed.get(plan(starts, closedAt, length()).save());
        copy(mc, target.file(), target.start(), target.file().length(), closedAt - target.start(), done);
    }

    // the first `size` bytes on a worker thread: the segment keeps growing meanwhile, but nothing past them is read
    private static void copy(Minecraft mc, File source, long start, long size, long covered, Consumer<Component> done) {
        File target = new File(Recording.directory(), new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date(start)) + "-buffer.mcrc");
        saving = true;
        Thread thread = new Thread(() -> {
            Component message;
            try (InputStream in = Files.newInputStream(source.toPath()); OutputStream out = Files.newOutputStream(target.toPath())) {
                byte[] buffer = new byte[1 << 16];
                for (long left = size; left > 0; ) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (read < 0) throw new IOException(source.getName() + " is shorter than " + size + " bytes");
                    out.write(buffer, 0, read);
                    left -= read;
                }
                message = Component.literal("Saved last " + minutes(covered) + " as " + target.getName());
                log.info("Saved the replay buffer as {}: {} bytes, {}", target, size, minutes(covered));
            } catch (IOException e) {
                log.error("Failed to save the replay buffer to {}", target, e);
                message = Component.literal("Failed to save the replay buffer: " + e.getMessage()).withStyle(ChatFormatting.RED);
            }
            Component result = message;
            mc.execute(() -> {
                saving = false;
                done.accept(result);
            });
        }, "Recorder buffer save");
        thread.setDaemon(true);
        thread.start();
    }

    private static String minutes(long millis) {
        long seconds = millis / 1000;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
