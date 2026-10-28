package net.toshimichi.recorder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReplayBufferTest {

    @Test
    public void plansWhatToKeepSaveAndStart() {
        long n = 10;
        // starts, now -> save, delete, start a new one
        Object[][] cases = {
                {List.of(), 0L, -1, List.of(), true},
                {List.of(0L), 3L, 0, List.of(), false},
                {List.of(0L), 5L, 0, List.of(), true},                  // N/2 since the newest
                {List.of(0L, 5L), 9L, 0, List.of(), false},             // none N old yet: the oldest
                {List.of(0L, 5L), 10L, 0, List.of(), true},             // exactly N old counts
                {List.of(0L, 5L, 10L), 14L, 0, List.of(), false},
                {List.of(0L, 5L, 10L), 15L, 1, List.of(0), true},        // 5 is now N old, 0 is not needed
                {List.of(0L, 5L, 10L, 15L), 26L, 3, List.of(0, 1, 2), true},
        };
        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            ReplayBuffer.Plan plan = ReplayBuffer.plan((List<Long>) c[0], (Long) c[1], n);
            String name = c[0] + " at " + c[1];
            assertEquals(c[2], plan.save(), name);
            assertEquals(c[3], plan.delete(), name);
            assertEquals(c[4], plan.startNew(), name);
        }
    }

    // run the buffer as the game does, once a tick: never more than 3 open, and a save is N to 1.5N long
    @Test
    public void rotationKeepsBetweenNAndOneAndAHalfN() {
        long n = 300_000;
        List<Long> starts = new ArrayList<>();
        for (long now = 0; now <= 10 * n; now += 50) {
            ReplayBuffer.Plan plan = ReplayBuffer.plan(starts, now, n);
            for (int i = plan.delete().size() - 1; i >= 0; i--) starts.remove((int) plan.delete().get(i));
            if (plan.startNew()) starts.add(now);
            assertTrue(starts.size() <= 3, "open at " + now + ": " + starts);
            long saved = now - starts.get(ReplayBuffer.plan(starts, now, n).save());
            if (now >= n) assertTrue(saved >= n && saved <= n * 3 / 2, "saved " + saved + " at " + now);
        }
    }

    @Test
    public void readsTheConfigOrFallsBack() {
        // text -> enabled, minutes, broken
        Object[][] cases = {
                {"{\"bufferEnabled\":true,\"bufferMinutes\":7}", true, 7, false},
                {"{}", false, 5, false},
                {"{\"bufferMinutes\":0}", false, 1, false},
                {"{\"bufferMinutes\":99}", false, 30, false},
                {"{\"bufferMinutes\":2.6}", false, 3, false},
                {"{\"bufferEnabled\":\"yes\"}", false, 5, true},
                {"{\"bufferMinutes\":\"five\"}", false, 5, true},
                {"[1,2]", false, 5, true},
                {"{\"bufferEnabled\":", false, 5, true},
        };
        for (Object[] c : cases) {
            Config.Parsed parsed = Config.parse((String) c[0]);
            assertEquals(c[1], parsed.config().bufferEnabled, (String) c[0]);
            assertEquals(c[2], parsed.config().bufferMinutes, (String) c[0]);
            assertEquals(c[3], parsed.problem() != null, (String) c[0]);
        }
    }
}
