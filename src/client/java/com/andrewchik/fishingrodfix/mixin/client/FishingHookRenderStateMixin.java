package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.FishingLineVisibility;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Stores {@link FishingLineVisibility}'s decision for the hook this state was extracted from. */
@Mixin(FishingHookRenderState.class)
public class FishingHookRenderStateMixin implements FishingLineVisibility.State {
    @Unique
    private boolean fishingrodfix$lineHidden;

    @Override
    public boolean fishingrodfix$isLineHidden() {
        return fishingrodfix$lineHidden;
    }

    @Override
    public void fishingrodfix$setLineHidden(boolean hidden) {
        fishingrodfix$lineHidden = hidden;
    }
}
