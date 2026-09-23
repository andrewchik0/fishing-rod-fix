package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The nausea/portal warp's spin, which {@code GameRenderer.tick} advances and {@code renderWorld} turns
 * into the warp's angle: {@code (nauseaEffectTime + tickProgress * nauseaEffectSpeed)} degrees; and the
 * movement FOV modifier {@code getFov} lerps between the last and the current tick's value.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("nauseaEffectTime")
    float fishingrodfix$getNauseaEffectTime();

    @Accessor("nauseaEffectSpeed")
    float fishingrodfix$getNauseaEffectSpeed();

    @Accessor("fovMultiplier")
    float fishingrodfix$getFovMultiplier();

    @Accessor("lastFovMultiplier")
    float fishingrodfix$getLastFovMultiplier();
}
