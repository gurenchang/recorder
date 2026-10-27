package net.toshimichi.recorder.mixin;

import net.minecraft.core.Registry;
import net.minecraft.tags.TagNetworkSerialization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// the server's tag sync for one registry; its public entry point wants the server's layered registries
@Mixin(TagNetworkSerialization.class)
public interface InvokerTagNetworkSerialization {

    @Invoker("serializeToNetwork")
    static <T> TagNetworkSerialization.NetworkPayload invokeSerializeToNetwork(Registry<T> registry) {
        throw new AssertionError();
    }
}
