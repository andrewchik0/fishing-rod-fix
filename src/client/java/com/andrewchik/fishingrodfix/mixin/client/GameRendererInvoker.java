package com.andrewchik.fishingrodfix.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's own view bob and hurt tilt. Both the world pass ({@code renderLevel}) and the hand pass
 * ({@code renderItemInHand}) apply them; calling the same methods keeps any change to them (by
 * Mojang or another mod) in sync with the line origin.
 */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
    @Invoker("bobHurt")
    void fishingrodfix$bobHurt(CameraRenderState cameraState, PoseStack poseStack);

    @Invoker("bobView")
    void fishingrodfix$bobView(CameraRenderState cameraState, PoseStack poseStack);
}
