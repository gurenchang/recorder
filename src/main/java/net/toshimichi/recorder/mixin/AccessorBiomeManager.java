package net.toshimichi.recorder.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// the hashed seed the join packet carries, which the client keeps only here
@Mixin(BiomeManager.class)
public interface AccessorBiomeManager {

    @Accessor("biomeZoomSeed")
    long getBiomeZoomSeed();
}
