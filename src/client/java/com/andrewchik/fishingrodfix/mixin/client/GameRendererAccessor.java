package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The nausea/portal warp's spin, which {@code GameRenderer.tick} advances and {@code renderLevel} turns
 * into the warp's angle: {@code (spinningEffectTime + worldPartialTicks * spinningEffectSpeed)} degrees.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("spinningEffectTime")
    float fishingrodfix$getSpinningEffectTime();

    @Accessor("spinningEffectSpeed")
    float fishingrodfix$getSpinningEffectSpeed();
}
