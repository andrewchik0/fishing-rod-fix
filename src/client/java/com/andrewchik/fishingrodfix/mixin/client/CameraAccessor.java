package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The camera's smoothed eye height, which {@code Camera.alignWithEntity} adds to the lerped entity position. */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("eyeHeight")
    float fishingrodfix$getEyeHeight();

    @Accessor("eyeHeightOld")
    float fishingrodfix$getEyeHeightOld();
}
