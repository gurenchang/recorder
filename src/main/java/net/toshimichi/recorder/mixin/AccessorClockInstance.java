package net.toshimichi.recorder.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.ClientClockManager$ClockInstance")
public interface AccessorClockInstance {

    @Accessor("totalTicks")
    long getTotalTicks();

    @Accessor("partialTick")
    float getPartialTick();

    @Accessor("rate")
    float getRate();
}
