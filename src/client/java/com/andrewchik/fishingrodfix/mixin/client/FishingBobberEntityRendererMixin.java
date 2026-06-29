package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Items;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes the vanilla first-person fishing-line offset bug on Minecraft 1.20&ndash;1.20.4.
 *
 * <p>This is the 1.20.x backport of the v0.5 fix. The correction math is <em>identical</em> to the
 * 1.21 / 1.21.1 / 1.21.5 / 1.21.11 / 26.x builds; only the injection plumbing differs because 1.20.4
 * has no {@code getHandPos} method to inject. On 1.21+ the world-space line origin is the value
 * returned by {@code getHandPos}, so those builds simply correct that returned point at {@code RETURN}
 * (strategy A). 1.20.4's {@link FishingBobberEntityRenderer} still computes the hand point inline and
 * draws the catenary segment-by-segment in a cancellable {@code renderFishingLine}, so here we inject
 * {@code renderFishingLine} at {@code HEAD}, cancel it, and redraw each segment with a corrected
 * bobber&rarr;hand vector (strategy B).
 *
 * <p>The two are mathematically equivalent. Vanilla's first-person line anchor is
 * {@code handPos = getCameraPosVec(t) + vec3d}, where {@code vec3d} is the camera-projection hand
 * offset {@code render} builds (the exact value 1.21+ later exposed as {@code getHandPos}); the
 * bobber&rarr;hand vector passed to {@code renderFishingLine} is therefore {@code handPos - bobberPos}.
 * Adding our correction {@code d} to that vector shifts the hand (segment t=1) end by {@code d} while
 * leaving the bobber (t=0) end anchored &mdash; producing exactly the same geometry the 1.21+
 * {@code getHandPos} fix would, since {@code (handPos + d) - bobberPos == correctedHand - bobberPos}.
 * The catenary itself is left untouched.
 *
 * <p>The correction is derived entirely from vanilla's own quantities and from projection geometry
 * &mdash; no tuned magic numbers (unlike the older 1.20 build) &mdash; so it stays robust across
 * renderer changes:
 * <ol>
 *   <li><b>Aspect ratio + FOV (exact).</b> The visible rod is drawn in a separate hand pass at the
 *       <em>options</em> FOV (no sprint/speed multiplier) and the real window aspect ratio, while the
 *       line (a world point) is projected by the world's <em>actual</em> FOV (options FOV times the
 *       sprint/speed multiplier). We re-project: decompose the eye&rarr;rod-tip vector along the
 *       camera axes and rescale its vertical component by {@code tan(actualFov/2)/tan(baseFov/2)} and
 *       its horizontal component by that ratio times {@code 16:9 / realAspect}. The hand-calibration
 *       constants ({@code 0.525}, {@code -0.1}) and the {@code 960} scale cancel out; only the
 *       projection laws and the rod's 16:9 calibration aspect remain. This also keeps the correct
 *       hand side (main/off hand, left-handed), since that sign is already in vanilla's vector.
 *       Unlike the 1.21.5+ builds (where {@code Camera.getFov} was removed), 1.20.4 still exposes
 *       {@code GameRenderer.getFov}, which returns the actual world FOV in degrees directly.</li>
 *   <li><b>Item sway.</b> {@code HeldItemRenderer} rotates the whole first-person hand about the view
 *       axes by {@code (getPitch-renderPitch)*0.1deg} and {@code (getYaw-renderYaw)*0.1deg}. A
 *       rotation about the eye by angle {@code a} shifts a point's on-screen position by exactly
 *       {@code a}, independent of distance/FOV, so we rotate the eye&rarr;rod-tip vector by the same
 *       angles about the same camera axes; the line origin then tracks the swaying rod tip by
 *       construction.</li>
 *   <li><b>Crouch sag-jump.</b> The line anchors using the <em>stepped</em>
 *       {@code getStandingEyeHeight()} (it jumps on the pose change) while the camera sits at a
 *       <em>smoothed</em> eye height; their difference {@code cameraY - getCameraPosVec(t).y} is 0
 *       once settled and non-zero only during the crouch animation, exactly cancelling the jump.</li>
 * </ol>
 */
@Mixin(FishingBobberEntityRenderer.class)
public class FishingBobberEntityRendererMixin {
    // The aspect ratio at which the first-person rod model is calibrated (its rod tip sits at NDC
    // x 0.525 there). Fundamental to the hand model, not a tuning knob.
    @Unique private static final float REFERENCE_ASPECT_RATIO    = 16f / 9f;

    // Vanilla's own item-sway factor, from HeldItemRenderer (the first-person hand rotation).
    // Not a tuning constant: if Mojang changes the sway, mirror their value here.
    @Unique private static final float VANILLA_ITEM_SWAY_DEGREES = 0.1f;

    // Vanilla hand-offset constants (FishingBobberEntityRenderer#render, first-person branch):
    // the camera-projection sample point and the 960/fov scale that build vec3d.
    @Unique private static final float  HAND_NDC_X = 0.525f;
    @Unique private static final float  HAND_DEPTH = -0.1f;
    @Unique private static final double FOV_SCALE  = 960.0;

    @Inject(method = "renderFishingLine", at = @At("HEAD"), cancellable = true)
    private static void fishingrodfix$correctLine(float x, float y, float z, VertexConsumer buffer,
                                                  MatrixStack.Entry matrices, float segmentStart,
                                                  float segmentEnd, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        Camera camera = mc.gameRenderer.getCamera();

        // Only the local player's first-person line origin is mis-placed; the third-person branch
        // (and other players' bobbers) already match their visible rod, so let vanilla's
        // renderFishingLine run unmodified for them.
        if (player == null || camera == null || camera.isThirdPerson()
                || !mc.options.getPerspective().isFirstPerson()) {
            return;
        }

        Vector3f d = fishingrodfix$correction(mc, camera, player);

        // Redraw this segment with the corrected bobber->hand vector. Adding d to (x, y, z) moves
        // only the hand (t=1) end of the line; the bobber (t=0) end stays put. This mirrors vanilla's
        // own catenary (FishingBobberEntityRenderer#renderFishingLine) exactly, only with the
        // corrected delta.
        float cx = x + d.x;
        float cy = y + d.y;
        float cz = z + d.z;
        float f = cx * segmentStart;
        float g = cy * (segmentStart * segmentStart + segmentStart) * 0.5F + 0.25F;
        float h = cz * segmentStart;
        float i = cx * segmentEnd - f;
        float j = cy * (segmentEnd * segmentEnd + segmentEnd) * 0.5F + 0.25F - g;
        float k = cz * segmentEnd - h;
        float l = MathHelper.sqrt(i * i + j * j + k * k);
        i /= l;
        j /= l;
        k /= l;
        buffer.vertex(matrices.getPositionMatrix(), f, g, h)
              .color(0, 0, 0, 255)
              .normal(matrices.getNormalMatrix(), i, j, k)
              .next();
        ci.cancel();
    }

    /**
     * Computes the world-space correction {@code d} added to the bobber&rarr;hand vector so the hand
     * end of the line meets the rendered rod tip. {@code d = correct(viewVec) - viewVec} (plus the
     * crouch offset on Y), where {@code viewVec} is vanilla's own eye&rarr;rod-tip offset (the
     * {@code vec3d} that {@link FishingBobberEntityRenderer}{@code .render} builds from the camera
     * projection &mdash; identical here to the value 1.21+ exposes as {@code getHandPos}).
     */
    @Unique
    private static Vector3f fishingrodfix$correction(MinecraftClient mc, Camera camera, ClientPlayerEntity player) {
        float tickDelta = mc.getTickDelta();

        // --- Reconstruct vanilla's eye->rod-tip vector (render()'s first-person vec3d) ---
        int handSign = player.getMainArm() == Arm.RIGHT ? 1 : -1;
        if (!player.getMainHandStack().isOf(Items.FISHING_ROD)) {
            handSign = -handSign; // rod held in the off-hand: vanilla draws from the other side
        }
        float baseFov = mc.options.getFov().getValue().intValue(); // hand model + vec3d use this
        float swing = MathHelper.sin(MathHelper.sqrt(player.getHandSwingProgress(tickDelta)) * (float) Math.PI);
        Vec3d hand = camera.getProjection().getPosition(handSign * HAND_NDC_X, HAND_DEPTH)
                .multiply(FOV_SCALE / baseFov)
                .rotateY(swing * 0.5F)
                .rotateX(-swing * 0.7F);
        Vector3f viewVec = new Vector3f((float) hand.x, (float) hand.y, (float) hand.z);

        // Orthonormal camera basis (world space). +right == -left(diagonal).
        Vector3f fwd   = new Vector3f(camera.getHorizontalPlane());
        Vector3f up    = new Vector3f(camera.getVerticalPlane());
        Vector3f right = new Vector3f(camera.getDiagonalPlane()).negate();

        // --- 1. Aspect-ratio / FOV re-projection (exact) ---
        float actualFov = (float) mc.gameRenderer.getFov(camera, tickDelta, true);
        float realAR    = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        float tanRatio  = (float) (Math.tan(Math.toRadians(actualFov / 2.0)) / Math.tan(Math.toRadians(baseFov / 2.0)));

        float compFwd   = viewVec.dot(fwd);
        float compUp    = viewVec.dot(up)    * tanRatio;
        float compRight = viewVec.dot(right) * tanRatio * (REFERENCE_ASPECT_RATIO / realAR);

        Vector3f corrected = new Vector3f(fwd).mul(compFwd)
                .add(new Vector3f(up).mul(compUp))
                .add(new Vector3f(right).mul(compRight));

        // --- 2. Item-sway lag, mirrored from vanilla's hand rotation (no tuned factor) ---
        float ax = (player.getPitch(tickDelta) - MathHelper.lerp(tickDelta, player.lastRenderPitch, player.renderPitch)) * VANILLA_ITEM_SWAY_DEGREES;
        float ay = (player.getYaw(tickDelta)   - MathHelper.lerp(tickDelta, player.lastRenderYaw,   player.renderYaw))   * VANILLA_ITEM_SWAY_DEGREES;
        corrected.rotateAxis((float) Math.toRadians(ay), up.x, up.y, up.z)
                 .rotateAxis((float) Math.toRadians(ax), right.x, right.y, right.z);

        // --- 3. Crouch sag-jump fix (0 when settled, non-zero only during the pose change) ---
        float crouchOffset = player.isOnGround()
                ? (float) (camera.getPos().y - player.getCameraPosVec(tickDelta).y)
                : 0f;

        // d = correct(viewVec) - viewVec, plus the crouch offset on Y.
        return new Vector3f(
                corrected.x - viewVec.x,
                corrected.y - viewVec.y + crouchOffset,
                corrected.z - viewVec.z);
    }
}
