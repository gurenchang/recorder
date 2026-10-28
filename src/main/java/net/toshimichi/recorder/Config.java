package net.toshimichi.recorder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/recorder.json. Written with the defaults when missing, and put back to them when it cannot be read. */
public final class Config {

    private static final Logger log = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int MIN_BUFFER_MINUTES = 1;
    public static final int MAX_BUFFER_MINUTES = 30;
    private static Config current;

    public boolean bufferEnabled = false;
    public int bufferMinutes = 5;

    public static Config get() {
        if (current == null) load();
        return current;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("recorder.json");
    }

    private static void load() {
        Path path = path();
        if (!Files.exists(path)) {
            current = new Config();
            save();
            return;
        }
        try {
            Parsed parsed = parse(Files.readString(path, StandardCharsets.UTF_8));
            current = parsed.config();
            if (parsed.problem() == null) return;
            log.warn("{} is broken ({}), resetting it to the defaults", path, parsed.problem());
        } catch (IOException e) {
            log.warn("Could not read {}, resetting it to the defaults", path, e);
            current = new Config();
        }
        save();
    }

    public static void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(get().toJson()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Could not write {}", path, e);
        }
    }

    // problem is null when the text was a good config; otherwise config holds the defaults
    public record Parsed(Config config, String problem) {
    }

    public static Parsed parse(String text) {
        Config config = new Config();
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) return new Parsed(new Config(), "not a JSON object");
            JsonObject o = root.getAsJsonObject();
            if (o.has("bufferEnabled")) {
                if (!(o.get("bufferEnabled") instanceof JsonPrimitive p) || !p.isBoolean()) return new Parsed(new Config(), "bufferEnabled is not true or false");
                config.bufferEnabled = p.getAsBoolean();
            }
            if (o.has("bufferMinutes")) {
                if (!(o.get("bufferMinutes") instanceof JsonPrimitive p) || !p.isNumber()) return new Parsed(new Config(), "bufferMinutes is not a number");
                config.bufferMinutes = (int) Math.max(MIN_BUFFER_MINUTES, Math.min(MAX_BUFFER_MINUTES, Math.round(p.getAsDouble())));
            }
        } catch (RuntimeException e) {
            return new Parsed(new Config(), e.getMessage());
        }
        return new Parsed(config, null);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("bufferEnabled", bufferEnabled);
        o.addProperty("bufferMinutes", bufferMinutes);
        return o;
    }
}
