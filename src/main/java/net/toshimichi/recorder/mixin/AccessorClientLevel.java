package net.toshimichi.recorder.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

// every map the server has sent, held or framed
@Mixin(ClientLevel.class)
public interface AccessorClientLevel {

    @Invoker("getAllMapData")
    Map<MapId, MapItemSavedData> invokeGetAllMapData();
}
