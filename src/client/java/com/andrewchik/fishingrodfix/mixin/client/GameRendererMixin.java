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
 * pass where {@code render} sets the frame's global shader settings ({@code GlobalSettings.set}, once
 * per rendered frame: the first call in its {@code !skipGameRender} block, which Dynamic FPS skips
 * frames, and before the world and its camera update), samples the hidden HUD and the hand gate right
 * after {@code renderWorld} updates the camera (1.21.8 has no separate {@code updateCamera}: after
 * the tick and the camera update, before the world's entities and the hand pass), and hands
 * {@link FishingLineOrigin} the two FOVs {@code renderWorld} projects with (its only two
 * {@code getFov} calls: the world's, before the entities, then the hand's). All optional
 * ({@code require = 0}): if the counter can't apply,
 * {@link HandPass} reports no hand pass and the line stays vanilla instead of the game crashing;
 * without the sampling, F1 leaves vanilla's line; without a FOV, {@link FishingLineOrigin} asks
 * {@code getFov} itself.
 *
 * <p>Mixin priority 900 is for the two FOV captures. A {@code @ModifyExpressionValue} handler is
 * inserted right after the instruction that produced the value, and {@code INJECT_APPLY} goes in
 * ascending priority, so each later application lands nearer the instruction and runs <em>earlier</em>
 * - the reverse of a callback that goes before its anchor. A priority below the default 1000
 * therefore makes these the outermost modifiers of the two {@code getFov} calls, i.e. the ones that
 * see the FOV the pass is really projected with after every other mod has had its say. That is not
 * hypothetical: <b>Zoomify</b>'s {@code keepHandFov}, which puts the zoom back for the hand when
 * "Affect Hand FOV" is off, is a {@code @ModifyExpressionValue} at the default priority on this very
 * instruction (on {@code renderWorld}'s second {@code getFov} from 1.21.6, on
 * {@code renderItemInHand}'s only one before it), so at equal priority which of the two ran last was
 * undefined. The other two injects are ordinary {@code @Inject}s and go the other way: at 900 they
 * run before, not after, other mods' callbacks of the default order at the same points, where the
 * tie used to be undefined. Nothing depends on that order. The frame counter is bookkeeping and
 * cannot care; the gate sample reads the camera, so a mod that moved it at this very call -
 * rather than inside {@code Camera.update}, which is before this point at any priority - would
 * now be seen a frame late, which no mod in the compatibility matrix does and which would cost
 * the F1 latch, i.e. vanilla's line.
 */
@Mixin(value = GameRenderer.class, priority = 900)
public class GameRendererMixin {
    @Inject(
        method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gl/GlobalSettings;set(IIDJLnet/minecraft/client/render/RenderTickCounter;I)V"),
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
