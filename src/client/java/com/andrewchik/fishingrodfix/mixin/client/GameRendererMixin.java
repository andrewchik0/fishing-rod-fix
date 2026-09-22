package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.HandPass;
import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts frames and samples the hidden HUD and the hand gate for {@link HandPass}, and lets
 * {@link ThirdPersonLineOrigin} forget the last frame's pass: {@code extract} runs once per frame,
 * after the tick and the camera update and before the level's entities. Optional
 * ({@code require = 0}): if it can't apply, {@link HandPass} reports no hand pass and the line stays
 * vanilla instead of the game crashing.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), require = 0)
    private void fishingrodfix$countFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        HandPass.onFrameExtract(deltaTracker);
        ThirdPersonLineOrigin.onFrameStart();
    }
}
