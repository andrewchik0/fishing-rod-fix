package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes the vanilla first-person fishing-line offset bug on Minecraft 1.21 and 1.21.1.
 *
 * <p>On 1.21 / 1.21.1 the world-space line origin is the value returned by {@code getHandPos}:
 * {@link FishingBobberEntityRenderer}{@code .render} calls it for the local player and then draws
 * the catenary from that hand point to the bobber via {@code renderFishingLine} (the renderer is
 * still entity-based here &mdash; there is no render state yet, so the bobber&rarr;hand delta is
 * computed inline). Correcting {@code getHandPos}'s returned point therefore shifts the rod-tip end
 * of the line by exactly that amount &mdash; the same approach as the 1.21.5, 1.21.11 and 26.x
 * builds (where the method is {@code getHandPos} / {@code getPlayerHandPos}). The catenary itself is
 * left untouched.
 *
 * <p>The whole correction is derived from vanilla's own quantities and from projection geometry
 * &mdash; no tuned magic numbers &mdash; so it stays robust across renderer changes:
 * <ol>
 *   <li><b>Aspect ratio + FOV (exact).</b> The visible rod is drawn in a separate hand pass at the
 *       <em>options</em> FOV (no sprint/speed multiplier) and the real window aspect ratio, while
 *       the line (a world point) is projected by the world's <em>actual</em> FOV (options FOV times
 *       the sprint/speed multiplier). {@code getHandPos} builds the origin at the options FOV and
 *       real aspect ratio. We re-project: decompose the eye&rarr;rod-tip vector along the camera
 *       axes and rescale its horizontal component by
 *       {@code (16:9 / realAspect) * tan(actualFov/2)/tan(baseFov/2)} and its vertical component by
 *       {@code tan(actualFov/2)/tan(baseFov/2)}. The hand-calibration constants ({@code 0.525},
 *       {@code -0.1}) and the {@code 960} scale cancel out; only the projection laws and the rod's
 *       16:9 calibration aspect remain. This also keeps the correct hand side (main/off hand,
 *       left-handed), since that sign is already in vanilla's vector.</li>
 *   <li><b>Item sway.</b> {@code HeldItemRenderer} rotates the whole first-person hand about the
 *       view axes by {@code (getPitch-renderPitch)*0.1deg} and {@code (getYaw-renderYaw)*0.1deg}.
 *       A rotation about the eye by angle {@code a} shifts a point's on-screen position by exactly
 *       {@code a}, independent of distance/FOV, so we rotate the eye&rarr;rod-tip vector by the same
 *       angles about the same camera axes; the line origin then tracks the swaying rod tip by
 *       construction.</li>
 *   <li><b>Crouch sag-jump.</b> {@code getCameraPosVec} anchors the line using the <em>stepped</em>
 *       {@code getStandingEyeHeight()} (it jumps on the pose change) while the camera sits at a
 *       <em>smoothed</em> eye height ({@code Camera.updateEyeHeight}); their difference
 *       {@code cameraY-eyePosY} is 0 once settled and non-zero only during the crouch animation,
 *       exactly cancelling the jump.</li>
 * </ol>
 *
 * <p>Like the 1.21.11 build, 1.21.1 has no {@code Camera.getFov()}: the actual world FOV is the
 * options FOV times the smoothed {@code GameRenderer} fov multiplier (exposed via the access
 * widener), which is what the world &mdash; and therefore the line &mdash; is projected with.
 * (The only API difference from 1.21.11 is that the camera position getter is {@code Camera.getPos()}
 * here, not {@code getCameraPos()}.)
 */
@Mixin(FishingBobberEntityRenderer.class)
public class FishingBobberEntityRendererMixin {
    // The aspect ratio at which the first-person rod model is calibrated (its rod tip sits
    // at NDC x 0.525 there). Fundamental to the hand model, not a tuning knob.
    @Unique private static final float REFERENCE_ASPECT_RATIO    = 16f / 9f;

    // Vanilla's own item-sway factor, from HeldItemRenderer (the first-person hand rotation).
    // Not a tuning constant: if Mojang changes the sway, mirror their value here.
    @Unique private static final float VANILLA_ITEM_SWAY_DEGREES = 0.1f;

    @Inject(
        method = "getHandPos(Lnet/minecraft/entity/player/PlayerEntity;FF)Lnet/minecraft/util/math/Vec3d;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void fishingrodfix$correctHandPos(PlayerEntity owner, float handRotation, float tickProgress, CallbackInfoReturnable<Vec3d> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        // Only the local player's first-person line origin is mis-placed; the third-person
        // branch (and other players) already match their visible rod, so leave them alone.
        // This mirrors getHandPos's own first-person condition exactly.
        if (player == null || owner != player || !mc.options.getPerspective().isFirstPerson()) {
            return;
        }

        // The world-render camera (same one getHandPos used). Never null here, but be defensive.
        Camera camera = mc.gameRenderer.getCamera();
        if (camera == null) {
            return;
        }

        cir.setReturnValue(fishingrodfix$correct(mc, camera, player, tickProgress, cir.getReturnValue()));
    }

    @Unique
    private static Vec3d fishingrodfix$correct(MinecraftClient mc, Camera camera, ClientPlayerEntity player, float tickProgress, Vec3d handPos) {
        Vec3d eyePos = player.getCameraPosVec(tickProgress);

        // Orthonormal camera basis (world space). +right == -left.
        Vector3f fwd   = new Vector3f(camera.getHorizontalPlane());
        Vector3f up    = new Vector3f(camera.getVerticalPlane());
        Vector3f right = new Vector3f(camera.getDiagonalPlane()).negate();

        // Vanilla's eye->rod-tip vector (already includes the correct hand side and swing).
        Vector3f viewVec = new Vector3f(
                (float)(handPos.x - eyePos.x),
                (float)(handPos.y - eyePos.y),
                (float)(handPos.z - eyePos.z));

        // --- 1. Aspect-ratio / FOV re-projection (exact) ---
        float baseFov   = mc.options.getFov().getValue().intValue();   // hand model + getHandPos use this
        // 1.21.5 has no Camera.getFov(): the world/line FOV is the options FOV times the smoothed
        // sprint/speed multiplier (the hand pass uses baseFov only).
        float actualFov = baseFov * MathHelper.lerp(tickProgress, mc.gameRenderer.lastFovMultiplier, mc.gameRenderer.fovMultiplier);
        float realAR    = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        float tanRatio  = (float)(Math.tan(Math.toRadians(actualFov / 2.0)) / Math.tan(Math.toRadians(baseFov / 2.0)));

        float compFwd   = viewVec.dot(fwd);
        float compUp    = viewVec.dot(up)    * tanRatio;
        float compRight = viewVec.dot(right) * tanRatio * (REFERENCE_ASPECT_RATIO / realAR);

        Vector3f corrected = new Vector3f(fwd).mul(compFwd)
                .add(new Vector3f(up).mul(compUp))
                .add(new Vector3f(right).mul(compRight));

        // --- 2. Item-sway lag, mirrored from vanilla's hand rotation (no tuned factor) ---
        float ax = (player.getPitch(tickProgress) - MathHelper.lerp(tickProgress, player.lastRenderPitch, player.renderPitch)) * VANILLA_ITEM_SWAY_DEGREES;
        float ay = (player.getYaw(tickProgress)   - MathHelper.lerp(tickProgress, player.lastRenderYaw,   player.renderYaw))   * VANILLA_ITEM_SWAY_DEGREES;
        corrected.rotateAxis((float) Math.toRadians(ay), up.x, up.y, up.z)
                 .rotateAxis((float) Math.toRadians(ax), right.x, right.y, right.z);

        // --- 3. Crouch sag-jump fix (0 when settled, non-zero only during the pose change) ---
        float crouchOffset = player.isOnGround()
                ? (float)(camera.getPos().y - eyePos.y)
                : 0f;

        return new Vec3d(
                eyePos.x + corrected.x,
                eyePos.y + corrected.y + crouchOffset,
                eyePos.z + corrected.z);
    }
}
