package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a sprite's source pixels. {@code SpriteContents.isPixelTransparent} only reports
 * alpha == 0, while held items discard everything below their alpha cutout, so the rod's visible
 * outline needs the real alpha values.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("image")
    NativeImage fishingrodfix$getImage();
}
