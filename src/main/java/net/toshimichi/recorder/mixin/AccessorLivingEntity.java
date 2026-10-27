package net.toshimichi.recorder.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface AccessorLivingEntity {

    @Accessor("DATA_HEALTH_ID")
    static EntityDataAccessor<Float> getHealthId() {
        throw new AssertionError();
    }

    @Accessor("equipment")
    EntityEquipment getEquipment();

    // the puppet wears the viewer's armor, which the replayed inventory packets fill
    @Mutable
    @Accessor("equipment")
    void setEquipment(EntityEquipment equipment);
}
