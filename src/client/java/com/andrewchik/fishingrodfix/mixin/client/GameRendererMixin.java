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
 * pass at {@code render}'s only {@code MinecraftClient.isFinishedLoading()} call (once per rendered
 * frame, inside its {@code !skipGameRender} block, through which Dynamic FPS skips frames, and before
 * the world and its camera update), samples the hidden HUD and the hand gate
 * right after {@code renderWorld} updates the camera (1.21.5 has no separate {@code updateCamera}:
 * after the tick and the camera update, before the world's entities and the hand pass), and hands
 * {@link FishingLineOrigin} the two FOVs vanilla projects with: the world's from {@code renderWorld}'s
 * only {@code getFov} call, before the entities, and the hand's from {@code renderHand}'s only one,
 * which runs at the end of {@code renderWorld} after them (1.21.5 computes it inside {@code renderHand}
 * itself, where 1.21.6+ moved it up into {@code renderWorld}; either way it is the value the next
 * frame's origin reuses). All optional ({@code require = 0}): if the counter can't apply,
 * {@link HandPass} reports no hand pass and the line stays vanilla instead of the game crashing;
 * without the sampling, F1 leaves vanilla's line; without a FOV, {@link FishingLineOrigin} asks
 * {@code getFov} itself.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(
        method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;isFinishedLoading()Z"),
        require = 0
    )
    private void fishingrodfix$countFrame(CallbackInfo ci) {
        HandPass.onFrameStart();
        ThirdPersonLineOrigin.onFrameStart();
    }

    @Inject(
        method = "renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V",
                shift = At.Shift.AFTER),
        require = 0
    )
    private void fishingrodfix$sampleHandGate(CallbackInfo ci) {
        HandPass.onWorldRender();
    }

    /** {@code renderWorld}'s world FOV: its only {@code getFov} call, {@code getFov(camera, tickProgress, true)}. */
    @ModifyExpressionValue(
        method = "renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;getFov(Lnet/minecraft/client/render/Camera;FZ)F", ordinal = 0),
        require = 0
    )
    private float fishingrodfix$captureWorldFov(float fov, @Local(argsOnly = true) RenderTickCounter tickCounter) {
        // Panorama capture (90deg) never reaches the correction, and must not stand in for a frame's FOV.
        // It calls renderWorld directly, without a frame of its own.
        if (!((GameRenderer) (Object) this).isRenderingPanorama()) {
            // renderWorld's own tick progress (its getFov call's argument).
            FishingLineOrigin.onWorldFov(fov, tickCounter.getTickProgress(true));
        }
        return fov;
    }

    /**
     * The hand pass's FOV: {@code renderHand}'s only {@code getFov} call,
     * {@code getFov(camera, tickProgress, false)}, which projects the hand it is about to draw.
     * {@code renderHand} runs at the end of {@code renderWorld}, after the world's entities, so the
     * origin computed during the next frame's extraction reuses this value.
     */
    @ModifyExpressionValue(
        method = "renderHand(Lnet/minecraft/client/render/Camera;FLorg/joml/Matrix4f;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;getFov(Lnet/minecraft/client/render/Camera;FZ)F", ordinal = 0),
        require = 0
    )
    private float fishingrodfix$captureHandFov(float fov) {
        // renderHand returns early while a panorama is captured, so this is belt and braces against a
        // mod that removes that guard. Another mod may sit on this same call: Zoomify's "Affect Hand
        // FOV = off" hooks it here on <1.21.6 (it moved to renderWorld's second getFov in 1.21.6).
        // Both are @ModifyExpressionValue, so they chain; the order between them is undefined.
        if (!((GameRenderer) (Object) this).isRenderingPanorama()) {
            FishingLineOrigin.onHandFov(fov);
        }
        return fov;
    }
}
