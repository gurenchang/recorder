package net.toshimichi.recorder.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Player.class)
public interface AccessorPlayer {

    @Accessor("DATA_PLAYER_ABSORPTION_ID")
    static EntityDataAccessor<Float> getAbsorptionId() {
        throw new AssertionError();
    }

    // the puppet holds the viewer's inventory, which the replayed inventory packets fill
    @Mutable
    @Accessor("inventory")
    void setInventory(Inventory inventory);
}
