package net.toshimichi.recorder.mixin;

import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

// the values are ClientClockManager.ClockInstance, which is private: read them through AccessorClockInstance
@Mixin(ClientClockManager.class)
public interface AccessorClientClockManager {

    @SuppressWarnings("rawtypes")
    @Accessor("clocks")
    Map getClocks();
}
