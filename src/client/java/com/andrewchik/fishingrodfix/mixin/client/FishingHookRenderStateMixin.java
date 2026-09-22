package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.FishingLineVisibility;
import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Carries, from extraction to submission, {@link FishingLineVisibility}'s decision for the hook this
 * state was extracted from, and the owner whose drawn rod its line moves onto with that owner's
 * partial tick ({@link ThirdPersonLineOrigin}).
 */
@Mixin(FishingHookRenderState.class)
public class FishingHookRenderStateMixin implements FishingLineVisibility.State, ThirdPersonLineOrigin.HookState {
    @Unique
    private boolean fishingrodfix$lineHidden;
    @Unique
    private @Nullable AbstractClientPlayer fishingrodfix$bodyRodOwner;
    @Unique
    private float fishingrodfix$ownerPartialTicks;

    @Override
    public boolean fishingrodfix$isLineHidden() {
        return fishingrodfix$lineHidden;
    }

    @Override
    public void fishingrodfix$setLineHidden(boolean hidden) {
        fishingrodfix$lineHidden = hidden;
    }

    @Override
    public @Nullable AbstractClientPlayer fishingrodfix$bodyRodOwner() {
        return fishingrodfix$bodyRodOwner;
    }

    @Override
    public float fishingrodfix$ownerPartialTicks() {
        return fishingrodfix$ownerPartialTicks;
    }

    @Override
    public void fishingrodfix$setBodyRodOwner(@Nullable AbstractClientPlayer owner, float ownerPartialTicks) {
        fishingrodfix$bodyRodOwner = owner;
        fishingrodfix$ownerPartialTicks = ownerPartialTicks;
    }
}
