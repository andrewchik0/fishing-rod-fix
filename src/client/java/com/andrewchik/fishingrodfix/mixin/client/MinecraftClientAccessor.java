package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The frame's tick progress. 1.20.1 has no accessor for it: {@code MinecraftClient.render} hands
 * {@code GameRenderer.render} - and through it {@code renderWorld}, the entity pass and the hand pass -
 * {@code this.paused ? this.pausedTickDelta : this.renderTickCounter.tickDelta}, both of them private.
 * (The public {@code getTickDelta()} is the counter's field alone, which keeps running while the
 * integrated server is paused, so it is the wrong value on a paused frame.) 1.21.1 reads the same thing
 * through {@code getRenderTickCounter().getTickDelta(true)}.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Accessor("renderTickCounter")
    RenderTickCounter fishingrodfix$getRenderTickCounter();

    @Accessor("pausedTickDelta")
    float fishingrodfix$getPausedTickDelta();
}
