package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import net.minecraft.client.player.AbstractClientPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Remembers where this player's rod was last drawn ({@link ThirdPersonLineOrigin}). */
@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin implements ThirdPersonLineOrigin.Owner {
    @Unique
    private ThirdPersonLineOrigin.@Nullable BodyRod fishingrodfix$bodyRod;

    @Override
    public ThirdPersonLineOrigin.@Nullable BodyRod fishingrodfix$bodyRod() {
        return fishingrodfix$bodyRod;
    }

    @Override
    public void fishingrodfix$setBodyRod(ThirdPersonLineOrigin.BodyRod rod) {
        fishingrodfix$bodyRod = rod;
    }
}
