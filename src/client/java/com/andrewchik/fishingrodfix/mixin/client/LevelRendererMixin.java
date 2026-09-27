package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes the collector the level pass submits its entities to, for the span of that loop: Customizable
 * Player Models hands every player's layers a wrapper of it, which would never match the hook's
 * ({@link ThirdPersonLineOrigin} tells passes apart by collector). Once per pass, not per entity.
 * Optional ({@code require = 0}): without it such a reading keeps the wrapper's tag. The loop's end is
 * {@link LevelRendererEndMixin}.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Inject(
        method = "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At("HEAD"),
        require = 0
    )
    private void fishingrodfix$beginEntities(PoseStack poseStack, LevelRenderState state, SubmitNodeCollector output, CallbackInfo ci) {
        ThirdPersonLineOrigin.onEntitiesStart(output);
    }
}
