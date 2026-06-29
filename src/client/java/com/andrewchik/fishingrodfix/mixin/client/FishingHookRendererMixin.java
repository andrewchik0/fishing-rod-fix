package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes the vanilla first-person fishing-line offset bug on Minecraft 26.1+.
 *
 * <p>Minecraft 26.1 is the first unobfuscated release (Yarn/Intermediary were
 * discontinued after 1.21.11) and the fishing-line render path was restructured: there
 * is no longer a {@code renderFishingLine} method to cancel. The line is emitted through
 * a deferred render-graph {@code submit}, and the world-space line origin is the value
 * returned by {@link FishingHookRenderer} {@code getPlayerHandPos}. So instead of
 * redrawing the line, we correct that returned hand position in first person.
 *
 * <p>The whole correction is derived from vanilla's own quantities and from projection
 * geometry &mdash; no tuned magic numbers &mdash; so it stays robust across Mojang's
 * renderer changes:
 * <ol>
 *   <li><b>Aspect ratio + FOV (exact).</b> The visible rod is drawn in a separate hand
 *       pass at a <em>fixed</em> {@code 70deg} FOV ({@code Camera.calculateHudFov}) and the
 *       real window aspect ratio, while the line (a world point) is projected by the
 *       world's <em>actual</em> FOV (options FOV times the sprint/speed modifier).
 *       {@code getPlayerHandPos} instead builds the origin at the options FOV and real
 *       aspect ratio. We re-project: decompose the eye&rarr;rod-tip vector along the camera
 *       axes and rescale its horizontal component by
 *       {@code (16:9 / realAspect) * tan(actualFov/2)/tan(baseFov/2)} and its vertical
 *       component by {@code tan(actualFov/2)/tan(baseFov/2)}. The hand-calibration
 *       constants ({@code 0.525}, {@code -0.1}) and the {@code 960} scale cancel out; only
 *       the projection laws and the rod's 16:9 calibration aspect remain. This also keeps
 *       the correct hand side (main/off hand, left-handed), since that sign is already in
 *       vanilla's vector.</li>
 *   <li><b>Item sway.</b> {@code ItemInHandRenderer.renderHandsWithItems} rotates the whole
 *       first-person hand about the view axes by {@code (getViewXRot-xBob)*0.1deg} and
 *       {@code (getViewYRot-yBob)*0.1deg}. A rotation about the eye by angle {@code a} shifts
 *       a point's on-screen position by exactly {@code a}, independent of distance/FOV, so we
 *       rotate the eye&rarr;rod-tip vector by the same angles about the same camera axes; the
 *       line origin then tracks the swaying rod tip by construction.</li>
 *   <li><b>Crouch sag-jump.</b> {@code getEyePosition} anchors the line using the
 *       <em>stepped</em> {@code getEyeHeight()} (it jumps on the pose change) while the camera
 *       sits at a <em>smoothed</em> eye height; their difference {@code cameraY-eyePosY} is 0
 *       once settled and non-zero only during the crouch animation, exactly cancelling the
 *       jump.</li>
 * </ol>
 */
@Mixin(FishingHookRenderer.class)
public class FishingHookRendererMixin {
    // The aspect ratio at which the first-person rod model is calibrated (its rod tip sits
    // at NDC x 0.525 there). Fundamental to the hand model, not a tuning knob.
    @Unique private static final float REFERENCE_ASPECT_RATIO    = 16f / 9f;

    // Vanilla's own item-sway factor, from ItemInHandRenderer.renderHandsWithItems().
    // Not a tuning constant: if Mojang changes the sway, mirror their value here.
    @Unique private static final float VANILLA_ITEM_SWAY_DEGREES = 0.1f;

    @Inject(
        method = "getPlayerHandPos(Lnet/minecraft/world/entity/player/Player;FF)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void fishingrodfix$correctHandPos(Player owner, float swing, float partialTicks, CallbackInfoReturnable<Vec3> cir) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        // Only the local player's first-person line origin is mis-placed; the third-person
        // branch (and other players) already match their visible rod, so leave them alone.
        if (player == null || owner != player || !mc.options.getCameraType().isFirstPerson()) {
            return;
        }

        // The world-render camera. This is the same Camera vanilla's getPlayerHandPos uses
        // (entityRenderDispatcher.camera); on 26.2 GameRenderer.getMainCamera() was removed,
        // so we read it through the dispatcher. It is @Nullable but always set during the
        // first-person world render where the line is drawn; bail out if it is somehow null.
        Camera camera = mc.getEntityRenderDispatcher().camera;
        if (camera == null) {
            return;
        }

        cir.setReturnValue(fishingrodfix$correct(mc, camera, player, partialTicks, cir.getReturnValue()));
    }

    @Unique
    private static Vec3 fishingrodfix$correct(Minecraft mc, Camera camera, LocalPlayer player, float partialTicks, Vec3 handPos) {
        Vec3 eyePos = player.getEyePosition(partialTicks);

        // Orthonormal camera basis (world space). +right == -left.
        Vector3f fwd   = new Vector3f(camera.forwardVector());
        Vector3f up    = new Vector3f(camera.upVector());
        Vector3f right = new Vector3f(camera.leftVector()).negate();

        // Vanilla's eye->rod-tip vector (already includes the correct hand side and swing).
        Vector3f viewVec = new Vector3f(
                (float)(handPos.x - eyePos.x),
                (float)(handPos.y - eyePos.y),
                (float)(handPos.z - eyePos.z));

        // --- 1. Aspect-ratio / FOV re-projection (exact) ---
        float baseFov   = mc.options.fov().get().intValue();   // hand model + getPlayerHandPos use this
        float actualFov = camera.getFov();                     // world/line FOV incl. sprint/speed modifier
        float realAR    = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        float tanRatio  = (float)(Math.tan(Math.toRadians(actualFov / 2.0)) / Math.tan(Math.toRadians(baseFov / 2.0)));

        float compFwd   = viewVec.dot(fwd);
        float compUp    = viewVec.dot(up)    * tanRatio;
        float compRight = viewVec.dot(right) * tanRatio * (REFERENCE_ASPECT_RATIO / realAR);

        Vector3f corrected = new Vector3f(fwd).mul(compFwd)
                .add(new Vector3f(up).mul(compUp))
                .add(new Vector3f(right).mul(compRight));

        // --- 2. Item-sway lag, mirrored from vanilla's hand rotation (no tuned factor) ---
        float ax = (player.getViewXRot(partialTicks) - Mth.lerp(partialTicks, player.xBobO, player.xBob)) * VANILLA_ITEM_SWAY_DEGREES;
        float ay = (player.getViewYRot(partialTicks) - Mth.lerp(partialTicks, player.yBobO, player.yBob)) * VANILLA_ITEM_SWAY_DEGREES;
        corrected.rotateAxis((float) Math.toRadians(ay), up.x, up.y, up.z)
                 .rotateAxis((float) Math.toRadians(ax), right.x, right.y, right.z);

        // --- 3. Crouch sag-jump fix (0 when settled, non-zero only during the pose change) ---
        float crouchOffset = player.onGround()
                ? (float)(camera.position().y - eyePos.y)
                : 0f;

        return new Vec3(
                eyePos.x + corrected.x,
                eyePos.y + corrected.y + crouchOffset,
                eyePos.z + corrected.z);
    }
}
