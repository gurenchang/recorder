package net.toshimichi.recorder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsonValuesTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private static RegistryAccess.Frozen registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // a non-empty ItemStack needs item components, which need the synced registries (damage type tags and the
        // like) that only a recording carries: those are checked by exporting real recordings instead
        registries = ClientRegistryLayer.createRegistryAccess().compositeAccess();
    }

    record Pair(int a, String b) {
    }

    static class Node {
        final int n = 1;
        Node self = this;
    }

    static class Child extends Node {
        final String name = "child";
    }

    @Test
    public void convertsEachTypeByItsRule() {
        JsonValues values = new JsonValues(registries);
        Map<String, Integer> stringKeys = new LinkedHashMap<>();
        stringKeys.put("a", 1);
        Map<Integer, Integer> intKeys = new LinkedHashMap<>();
        intKeys.put(1, 2);
        Object[][] cases = {
                {null, "null"},
                {true, "true"},
                {3, "3"},
                {2.5, "2.5"},
                {Double.NaN, "null"},
                {Float.POSITIVE_INFINITY, "null"},
                {"§aGreen", "\"§aGreen\""},
                {new UUID(1, 2), "\"00000000-0000-0001-0000-000000000002\""},
                {Direction.NORTH, "\"NORTH\""},
                {Optional.empty(), "null"},
                {Optional.of(4), "4"},
                {OptionalInt.empty(), "null"},
                {OptionalInt.of(5), "5"},
                {List.of(1, 2), "[1,2]"},
                {new int[]{1, 2}, "[1,2]"},
                {new int[300], "{\"bytes\":1200}"},
                {new byte[10], "{\"bytes\":10}"},
                {new long[3], "{\"bytes\":24}"},
                {stringKeys, "{\"a\":1}"},
                {intKeys, "[[1,2]]"},
                {new Vec3(1, 2, 3), "[1.0,2.0,3.0]"},
                {new BlockPos(1, -2, 3), "[1,-2,3]"},
                {SectionPos.of(1, 2, 3), "[1,2,3]"},
                {new ChunkPos(4, 5), "[4,5]"},
                {new Pair(1, "x"), "{\"a\":1,\"b\":\"x\"}"},
                {new Node(), "{\"n\":1,\"self\":\"<cycle>\"}"},
                {new Child(), "{\"name\":\"child\",\"n\":1,\"self\":\"<cycle>\"}"},
                {Items.DIAMOND, "\"minecraft:diamond\""},
                {EntityType.ZOMBIE, "\"minecraft:zombie\""},
                {BuiltInRegistries.ITEM.wrapAsHolder(Items.DIAMOND), "\"minecraft:diamond\""},
                {ResourceKey.create(Registries.ITEM, Identifier.withDefaultNamespace("stick")), "\"minecraft:stick\""},
                {Identifier.withDefaultNamespace("stick"), "\"minecraft:stick\""},
                {Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP), "\"minecraft:stone_slab[type=top,waterlogged=false]\""},
                {Component.literal("§ahi"), "{\"text\":\"§ahi\",\"json\":\"§ahi\"}"},
                {ItemStack.EMPTY, "{}"},
                {new SynchedEntityData.DataValue<>(5, EntityDataSerializers.INT, 7),
                        "{\"i\":5,\"s\":" + EntityDataSerializers.getSerializedId(EntityDataSerializers.INT) + ",\"v\":7}"},
        };
        for (Object[] c : cases) {
            assertEquals(c[1], GSON.toJson(values.convert(c[0])), String.valueOf(c[0]));
        }
        assertEquals(0, values.errors());
    }
}
