package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The renderer's own tick count, which {@code GameRenderer.tick} advances and {@code renderWorld} turns
 * into the nausea/portal warp's angle: {@code (ticks + tickDelta) * (nausea ? 7 : 20)} degrees. (1.21.5
 * replaced this counter with a {@code nauseaEffectTime}/{@code nauseaEffectSpeed} pair the effect's own
 * tick advances; here the speed is picked live from the status effect instead.) Also the movement FOV
 * modifier, which {@code getFov} lerps between the last and the current tick's value.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("ticks")
    int fishingrodfix$getTicks();

    @Accessor("fovMultiplier")
    float fishingrodfix$getFovMultiplier();

    @Accessor("lastFovMultiplier")
    float fishingrodfix$getLastFovMultiplier();
}
