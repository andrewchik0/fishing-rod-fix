package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes a sprite's source pixels. {@code SpriteContents.isPixelTransparent} only reports
 * alpha == 0, while held items discard everything below their alpha cutout, so the rod's visible
 * outline needs the real alpha values.
 *
 * <p>{@code getFrameCount} stands in for 1.21.1's missing {@code isAnimated()}: it is
 * {@code animation != null ? animation.frames.size() : 1}, and {@code createAnimation} returns null
 * for a frame list of one or fewer, so {@code > 1} is exactly {@code animation != null} — the test
 * {@code isPixelTransparent} makes before it offsets a pixel into an animation frame.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("image")
    NativeImage fishingrodfix$getImage();

    @Invoker("getFrameCount")
    int fishingrodfix$getFrameCount();
}
