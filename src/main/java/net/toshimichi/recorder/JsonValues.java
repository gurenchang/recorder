package net.toshimichi.recorder;

import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Any decoded packet as JSON, without a hand-written case per packet: records and plain classes by reflection, with
 * the types worth reading as themselves special-cased (see RECORDING_FORMAT.md). Uses no mixin accessors, so it runs
 * outside the game too. Not thread-safe; one per export.
 */
public final class JsonValues {

    private static final int MAX_DEPTH = 16;
    private static final int MAX_SMALL_ARRAY = 256; // primitive arrays longer than this are data, not values
    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    private final DynamicOps<JsonElement> ops;
    // every value of every registry, so an entity type or a sound reads as its id
    private final Map<Object, String> registered = new IdentityHashMap<>();
    private final Set<Object> path = Collections.newSetFromMap(new IdentityHashMap<>()); // cycle guard
    private int errors;

    public JsonValues(RegistryAccess registries) {
        ops = registries.createSerializationContext(JsonOps.INSTANCE);
        registries.registries().forEach(entry -> entry.value().listElements()
                .forEach(holder -> registered.putIfAbsent(holder.value(), holder.key().identifier().toString())));
    }

    // values that could not be converted, each also written in place as {"error": message}
    public int errors() {
        return errors;
    }

    public JsonElement packet(Packet<?> packet, int size) {
        // the chunk data itself would make the file huge; where and how much is what an analysis needs
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) return chunk(chunk.getX(), chunk.getZ(), size);
        if (packet instanceof ClientboundLightUpdatePacket light) return chunk(light.getX(), light.getZ(), size);
        if (packet instanceof ClientboundChunksBiomesPacket biomes) {
            JsonArray chunks = new JsonArray();
            for (ClientboundChunksBiomesPacket.ChunkBiomeData data : biomes.chunkBiomeData()) {
                chunks.add(chunk(data.pos().x(), data.pos().z(), data.buffer().length));
            }
            JsonObject o = new JsonObject();
            o.add("chunks", chunks);
            return o;
        }
        // every block, for following what was mined
        if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            JsonArray blocks = new JsonArray();
            section.runUpdates((pos, state) -> {
                JsonObject block = new JsonObject();
                block.add("pos", convert(pos.immutable()));
                block.add("block", convert(state));
                blocks.add(block);
            });
            JsonObject o = new JsonObject();
            o.add("blocks", blocks);
            return o;
        }
        return convert(packet);
    }

    private static JsonObject chunk(int x, int z, int size) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("bytes", size);
        return o;
    }

    public JsonElement convert(Object value) {
        return convert(value, 0);
    }

    private JsonElement convert(Object v, int depth) {
        try {
            return convertOrThrow(v, depth);
        } catch (RuntimeException | ReflectiveOperationException e) {
            errors++;
            JsonObject o = new JsonObject();
            o.addProperty("error", v.getClass().getName() + ": " + e);
            return o;
        }
    }

    private JsonElement convertOrThrow(Object v, int depth) throws ReflectiveOperationException {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Double d) return d.isNaN() || d.isInfinite() ? JsonNull.INSTANCE : new JsonPrimitive(d);
        if (v instanceof Float f) return f.isNaN() || f.isInfinite() ? JsonNull.INSTANCE : new JsonPrimitive(f);
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof CharSequence || v instanceof Character || v instanceof UUID) return new JsonPrimitive(v.toString());
        if (v instanceof Enum<?> e) return new JsonPrimitive(e.name());

        String id = registered.get(v);
        if (id != null) return new JsonPrimitive(id);
        if (v instanceof Holder<?> holder) {
            // a direct holder has no id: what it holds instead
            Optional<? extends ResourceKey<?>> key = holder.unwrapKey();
            return key.isPresent() ? new JsonPrimitive(key.get().identifier().toString()) : convert(holder.value(), depth + 1);
        }
        if (v instanceof ResourceKey<?> key) return new JsonPrimitive(key.identifier().toString());
        if (v instanceof Identifier) return new JsonPrimitive(v.toString());
        if (v instanceof TagKey<?> tag) return new JsonPrimitive("#" + tag.location());
        if (v instanceof BlockState state) return new JsonPrimitive(BlockStateParser.serialize(state));
        if (v instanceof Component component) {
            JsonObject o = new JsonObject();
            o.addProperty("text", component.getString()); // legacy § codes stay in, as the server sent them
            o.add("json", ComponentSerialization.CODEC.encodeStart(ops, component).getOrThrow());
            return o;
        }
        if (v instanceof ItemStack stack) return ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow();
        if (v instanceof Tag tag) return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
        if (v instanceof SynchedEntityData.DataValue<?> data) {
            JsonObject o = new JsonObject();
            o.addProperty("i", data.id());
            o.addProperty("s", EntityDataSerializers.getSerializedId(data.serializer()));
            o.add("v", convert(data.value(), depth + 1));
            return o;
        }
        if (v instanceof Vec3 p) return numbers(p.x, p.y, p.z);
        if (v instanceof SectionPos p) return numbers(p.x(), p.y(), p.z());
        if (v instanceof Vec3i p) return numbers(p.getX(), p.getY(), p.getZ()); // BlockPos too
        if (v instanceof ChunkPos p) return numbers(p.x(), p.z());

        if (v instanceof Optional<?> o) return o.isPresent() ? convert(o.get(), depth + 1) : JsonNull.INSTANCE;
        if (v instanceof OptionalInt o) return o.isPresent() ? new JsonPrimitive(o.getAsInt()) : JsonNull.INSTANCE;
        if (v instanceof OptionalLong o) return o.isPresent() ? new JsonPrimitive(o.getAsLong()) : JsonNull.INSTANCE;
        if (v instanceof OptionalDouble o) return o.isPresent() ? convert(o.getAsDouble(), depth + 1) : JsonNull.INSTANCE;

        if (v instanceof byte[] bytes) return size(bytes.length);
        if (v instanceof long[] longs) return size(longs.length * 8L); // packed bit storage
        if (v instanceof ByteBuf buf) return size(buf.readableBytes());
        if (v.getClass().isArray()) {
            int length = Array.getLength(v);
            if (v.getClass().getComponentType().isPrimitive() && length > MAX_SMALL_ARRAY) {
                return size((long) length * elementSize(v.getClass().getComponentType()));
            }
            JsonArray a = new JsonArray();
            for (int i = 0; i < length; i++) a.add(convert(Array.get(v, i), depth + 1));
            return a;
        }
        if (v instanceof BitSet bits) {
            JsonArray a = new JsonArray();
            bits.stream().forEach(a::add);
            return a;
        }
        if (v instanceof Multimap<?, ?> multimap) return convert(multimap.asMap(), depth);
        if (v instanceof Map<?, ?> map) return map(map, depth);
        if (v instanceof Collection<?> collection) {
            JsonArray a = new JsonArray();
            for (Object o : collection) a.add(convert(o, depth + 1));
            return a;
        }

        if (depth >= MAX_DEPTH || !reflectable(v.getClass())) return new JsonPrimitive(v.toString());
        if (!path.add(v)) return new JsonPrimitive("<cycle>");
        try {
            return object(v, depth);
        } finally {
            path.remove(v);
        }
    }

    private JsonElement map(Map<?, ?> map, int depth) {
        List<JsonElement[]> pairs = new ArrayList<>();
        boolean stringKeys = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            JsonElement key = convert(entry.getKey(), depth + 1);
            stringKeys &= key.isJsonPrimitive() && key.getAsJsonPrimitive().isString();
            pairs.add(new JsonElement[]{key, convert(entry.getValue(), depth + 1)});
        }
        if (stringKeys) {
            JsonObject o = new JsonObject();
            for (JsonElement[] pair : pairs) o.add(pair[0].getAsString(), pair[1]);
            return o;
        }
        JsonArray a = new JsonArray();
        for (JsonElement[] pair : pairs) {
            JsonArray p = new JsonArray();
            p.add(pair[0]);
            p.add(pair[1]);
            a.add(p);
        }
        return a;
    }

    // records by component, anything else by its instance fields, superclasses included
    private JsonElement object(Object v, int depth) throws ReflectiveOperationException {
        JsonObject o = new JsonObject();
        Class<?> type = v.getClass();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                o.add(component.getName(), convert(accessor.invoke(v), depth + 1));
            }
            return o;
        }
        for (Field field : fields(type)) o.add(field.getName(), convert(field.get(v), depth + 1));
        return o;
    }

    private static List<Field> fields(Class<?> type) {
        return FIELDS.computeIfAbsent(type, t -> {
            List<Field> fields = new ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class && reflectable(c); c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            return fields;
        });
    }

    // the JDK's own classes are closed to reflection and say what they are in toString; lambdas have no fields worth reading
    private static boolean reflectable(Class<?> type) {
        String name = type.getName();
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")
                || type.isSynthetic() || type.isHidden() || name.contains("$$Lambda"));
    }

    private static JsonArray numbers(Number... values) {
        JsonArray a = new JsonArray();
        for (Number n : values) a.add(n);
        return a;
    }

    private static JsonObject size(long bytes) {
        JsonObject o = new JsonObject();
        o.addProperty("bytes", bytes);
        return o;
    }

    private static int elementSize(Class<?> type) {
        if (type == long.class || type == double.class) return 8;
        if (type == int.class || type == float.class) return 4;
        if (type == short.class || type == char.class) return 2;
        return 1;
    }
}
