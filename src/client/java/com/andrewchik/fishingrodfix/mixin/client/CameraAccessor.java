package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The camera's smoothed eye height, which {@code Camera.update} adds to the lerped entity position. */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("cameraY")
    float fishingrodfix$getCameraY();

    @Accessor("lastCameraY")
    float fishingrodfix$getLastCameraY();
}
