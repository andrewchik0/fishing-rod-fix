package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.FishingLineOrigin;
import com.andrewchik.fishingrodfix.HandPass;
import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts frames for {@link HandPass} and lets {@link ThirdPersonLineOrigin} forget the last frame's
 * pass where {@code render} calls {@code updateCamera} (once per rendered frame: past its
 * {@code skipGameRender} check, which Dynamic FPS uses to skip frames, and before the camera update
 * and the world), samples the hidden HUD and the hand gate at {@code renderWorld}'s start (after the
 * tick and the camera update, before the world's entities and the hand pass), and hands
 * {@link FishingLineOrigin} the two FOVs {@code renderWorld} projects with (its only two
 * {@code getFov} calls: the world's, before the entities, then the hand's). All optional
 * ({@code require = 0}): if the counter can't apply,
 * {@link HandPass} reports no hand pass and the line stays vanilla instead of the game crashing;
 * without the sampling, F1 leaves vanilla's line; without a FOV, {@link FishingLineOrigin} asks
 * {@code getFov} itself. Priority 900 fixes the order of the FOV captures against other mods'
 * {@code @ModifyExpressionValue}s on the same two calls: applied first, they sit outermost in the
 * chain, so they read the FOV vanilla goes on to project with (Zoomify's "affect hand FOV" undo, an
 * MEV on the same hand-FOV call, is one). It puts the two {@code @Inject}s of this class first at
 * their own points too, which nothing here depends on: they only count the frame and sample the gate.
 */
@Mixin(value = GameRenderer.class, priority = 900)
public class GameRendererMixin {
    @Inject(
        method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;updateCamera(Lnet/minecraft/client/render/RenderTickCounter;)V"),
        require = 0
    )
    private void fishingrodfix$countFrame(CallbackInfo ci) {
        HandPass.onFrameStart();
        ThirdPersonLineOrigin.onFrameStart();
    }

    @Inject(method = "renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V", at = @At("HEAD"), require = 0)
    private void fishingrodfix$sampleHandGate(CallbackInfo ci) {
        HandPass.onWorldRender();
    }

    /** {@code renderWorld}'s world FOV: its first {@code getFov} call, {@code getFov(camera, tickProgress, true)}. */
    @ModifyExpressionValue(
        method = "renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;getFov(Lnet/minecraft/client/render/Camera;FZ)F", ordinal = 0),
        require = 0
    )
    private float fishingrodfix$captureWorldFov(float fov, @Local(argsOnly = true) RenderTickCounter tickCounter) {
        // Panorama capture (90deg) never reaches the correction, and must not stand in for a frame's FOV.
        if (!((GameRenderer) (Object) this).isRenderingPanorama()) {
            // renderWorld's own tick progress (its getFov call's argument).
            FishingLineOrigin.onWorldFov(fov, tickCounter.getTickProgress(true));
        }
        return fov;
    }

    /** {@code renderWorld}'s hand FOV: its second {@code getFov} call, {@code getFov(camera, tickProgress, false)}. */
    @ModifyExpressionValue(
        method = "renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;getFov(Lnet/minecraft/client/render/Camera;FZ)F", ordinal = 1),
        require = 0
    )
    private float fishingrodfix$captureHandFov(float fov) {
        if (!((GameRenderer) (Object) this).isRenderingPanorama()) {
            FishingLineOrigin.onHandFov(fov);
        }
        return fov;
    }
}
