package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of the level pass's entity loop ({@link LevelRendererMixin}). Priority 1500 puts it after other
 * mods' injects at the same return, which still belong to the loop: Real Camera submits the camera
 * entity there. Optional ({@code require = 0}).
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public class LevelRendererEndMixin {
    @Inject(
        method = "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At("TAIL"),
        require = 0
    )
    private void fishingrodfix$endEntities(CallbackInfo ci) {
        ThirdPersonLineOrigin.onEntitiesEnd();
    }
}
