package com.andrewchik.fishingrodfix.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a sprite's source pixels. {@code SpriteContents.isTransparent} only reports alpha == 0,
 * while the item pipelines discard everything below their alpha cutout, so the rod's visible
 * outline needs the real alpha values.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage fishingrodfix$getOriginalImage();
}
