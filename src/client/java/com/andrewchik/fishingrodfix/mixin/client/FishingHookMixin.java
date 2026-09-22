package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.FishingLineVisibility;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Remembers whether this hook's owner was ever seen holding a rod ({@link FishingLineVisibility}). */
@Mixin(FishingHook.class)
public class FishingHookMixin implements FishingLineVisibility.Hook {
    @Unique
    private boolean fishingrodfix$seenWithRod;

    @Override
    public boolean fishingrodfix$wasSeenWithRod() {
        return fishingrodfix$seenWithRod;
    }

    @Override
    public void fishingrodfix$markSeenWithRod() {
        fishingrodfix$seenWithRod = true;
    }
}
