package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.FishingLineVisibility;
import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.state.FishingBobberEntityState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Carries, from extraction to submission, {@link FishingLineVisibility}'s decision for the hook this
 * state was extracted from, and the owner whose drawn rod its line moves onto with that owner's
 * partial tick and whether only a rod drawn in the same pass counts ({@link ThirdPersonLineOrigin}).
 */
@Mixin(FishingBobberEntityState.class)
public class FishingBobberEntityStateMixin implements FishingLineVisibility.State, ThirdPersonLineOrigin.HookState {
    @Unique
    private boolean fishingrodfix$lineHidden;
    @Unique
    private @Nullable AbstractClientPlayerEntity fishingrodfix$bodyRodOwner;
    @Unique
    private float fishingrodfix$ownerPartialTicks;
    @Unique
    private boolean fishingrodfix$samePassOnly;

    @Override
    public boolean fishingrodfix$isLineHidden() {
        return fishingrodfix$lineHidden;
    }

    @Override
    public void fishingrodfix$setLineHidden(boolean hidden) {
        fishingrodfix$lineHidden = hidden;
    }

    @Override
    public @Nullable AbstractClientPlayerEntity fishingrodfix$bodyRodOwner() {
        return fishingrodfix$bodyRodOwner;
    }

    @Override
    public float fishingrodfix$ownerPartialTicks() {
        return fishingrodfix$ownerPartialTicks;
    }

    @Override
    public boolean fishingrodfix$samePassOnly() {
        return fishingrodfix$samePassOnly;
    }

    @Override
    public void fishingrodfix$setBodyRodOwner(@Nullable AbstractClientPlayerEntity owner, float ownerPartialTicks, boolean samePassOnly) {
        fishingrodfix$bodyRodOwner = owner;
        fishingrodfix$ownerPartialTicks = ownerPartialTicks;
        fishingrodfix$samePassOnly = samePassOnly;
    }
}
