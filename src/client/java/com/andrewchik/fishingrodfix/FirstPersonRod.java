package com.andrewchik.fishingrodfix;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Vanilla's first-person fishing rod: where the line attaches to the rod that is drawn, in the hand
 * pass's view space (x right, y up, z back).
 *
 * <p>The attachment point is a fixed point on the rod model, carried through the same pose stack
 * {@code FirstPersonHandsAndItemsRenderer.submitArmWithItem} uses for an idle or swinging rod: the arm position
 * with the equip/re-equip dip, the swing, then the item's display transform. It is derived from
 * vanilla's own screen anchor, so at rest the vanilla rod keeps vanilla's calibration exactly:
 * {@code getPlayerHandPos} anchors the line at screen point {@code (±0.525, -0.1)}, and we take the
 * point on the resting rod's mid-plane that the hand pass draws there. That calibration is for
 * vanilla's base hand FOV ({@code Camera.BASE_HUD_FOV}, 70deg on land) and a 16:9 window. Vanilla
 * builds and projects its anchor at the same options FOV, so only the window shape decides whether
 * its line meets its rod; we assume it was tuned at 16:9.
 *
 * <p>Resource packs that redraw the rod (e.g. Faithful 32x) end its tip at another texel; the
 * attachment point is shifted by the same amount as the pack's tip ({@link RodSprite}).
 */
final class FirstPersonRod {
    // FishingHookRenderer.getPlayerHandPos: getPointOnPlane(invert * 0.525F, -0.1F), i.e. the screen
    // point (NDC) where vanilla's first-person line starts, at any aspect and options FOV while no FOV
    // modifier, bob, swing or nausea/portal warp applies and the camera sits at the eye (no
    // crouch/swim eye-height transition); the drawn rod passes through it only at the 70deg hand and
    // the calibration aspect below.
    private static final Vector2fc VANILLA_ANCHOR_NDC = new Vector2f(0.525f, -0.1f);
    // Assumed (not in vanilla's source, not tuned): the window aspect that anchor was calibrated at.
    // Only the aspect matters: vanilla builds and projects its anchor at the same FOV (see class doc).
    private static final float CALIBRATION_ASPECT_RATIO = 16f / 9f;

    // FirstPersonHandsAndItemsRenderer.applyItemArmTransform: ITEM_POS_X/Y/Z (right arm) and
    // ITEM_HEIGHT_SCALE (the equip dip, times inverseArmHeight).
    private static final Vector3fc ITEM_POS          = new Vector3f(0.56f, -0.52f, -0.72f);
    private static final float     ITEM_HEIGHT_SCALE = -0.6f;
    // FirstPersonHandsAndItemsRenderer.swingArm / applyItemArmAttackTransform:
    // ITEM_SWING_X/Y/Z_POS_SCALE, ITEM_PRESWING_ROT_Y, ITEM_SWING_X/Y/Z_ROT_AMOUNT.
    private static final Vector3fc ITEM_SWING_POS_SCALE    = new Vector3f(-0.4f, 0.2f, -0.2f);
    private static final float     ITEM_PRESWING_ROT_Y     = 45f;
    private static final float     ITEM_SWING_X_ROT_AMOUNT = -80f;
    private static final float     ITEM_SWING_Y_ROT_AMOUNT = -20f;
    private static final float     ITEM_SWING_Z_ROT_AMOUNT = -20f;
    // models/item/handheld_rod.json "firstperson_righthand", applied as ItemTransform.apply does:
    // translate (in 1/16 block), rotationXYZ, scale, then -0.5 to centre the model.
    private static final Vector3fc ROD_ROTATION_DEG = new Vector3f(0f, 90f, 25f);
    private static final Vector3fc ROD_TRANSLATION  = new Vector3f(0f, 1.6f, 0.8f).div(16f);
    private static final float     ROD_SCALE        = 0.68f;
    private static final Matrix4fc ROD_DISPLAY = new Matrix4f()
            .translation(ROD_TRANSLATION)
            .rotateXYZ(Mth.DEG_TO_RAD * ROD_ROTATION_DEG.x(), Mth.DEG_TO_RAD * ROD_ROTATION_DEG.y(), Mth.DEG_TO_RAD * ROD_ROTATION_DEG.z())
            .scale(ROD_SCALE)
            .translate(-0.5f, -0.5f, -0.5f);

    // Where vanilla's anchor sits on the vanilla rod, as texture (u, v) on the model's mid-plane.
    // Must stay below every constant anchorOnRestingRod() reads (static initialization order).
    private static final Vector2fc VANILLA_ANCHOR_UV = anchorOnRestingRod();

    private FirstPersonRod() {}

    /**
     * The line's attachment point in hand-pass view space, for the rod held in {@code arm} at swing
     * progress {@code swing} (0 = none) and equip dip {@code inverseArmHeight} (0 = fully raised).
     * Item sway is not included; it rotates the whole hand pass.
     */
    static Vector3f lineAnchor(Minecraft mc, HumanoidArm arm, float swing, float inverseArmHeight) {
        // Vanilla's anchor, shifted with the pack's rod tip.
        Vector2fc tip = RodSprite.tipUv(mc);
        Vector2f uv = new Vector2f(tip).sub(RodSprite.VANILLA_TIP_UV).add(VANILLA_ANCHOR_UV);
        Vector3f anchor = armPose(swing, inverseArmHeight)
                .mul(ROD_DISPLAY)
                // Texture v points down, model y up; z = 0.5 is the item's mid-plane
                // (ItemModelGenerator's MIN_Z/MAX_Z = 7.5/8.5 of 16).
                .transformPosition(new Vector3f(uv.x, 1f - uv.y, 0.5f));
        // Left-hand rod = x-mirror of the right one on the model's mid-plane: ItemTransform.apply's
        // left-hand fix turns handheld_rod's firstperson_lefthand back into the right-hand rotation
        // (which maps the mid-plane to x = 0), and applyItemArmTransform, swingArm and
        // applyItemArmAttackTransform mirror x via `invert`.
        if (arm == HumanoidArm.LEFT) {
            anchor.x = -anchor.x;
        }
        return anchor;
    }

    /** The right-arm pose stack before the item transform: applyItemArmTransform, then swingArm. */
    private static Matrix4f armPose(float swing, float inverseArmHeight) {
        Matrix4f pose = new Matrix4f().translation(ITEM_POS.x(), ITEM_POS.y() + inverseArmHeight * ITEM_HEIGHT_SCALE, ITEM_POS.z());
        if (swing > 0f) {
            float sqrtSwing = Mth.sqrt(swing);
            pose.translate(
                    ITEM_SWING_POS_SCALE.x() * Mth.sin(sqrtSwing * Mth.PI),
                    ITEM_SWING_POS_SCALE.y() * Mth.sin(sqrtSwing * Mth.TWO_PI),
                    ITEM_SWING_POS_SCALE.z() * Mth.sin(swing * Mth.PI));
            float ySwingRotation  = Mth.sin(swing * swing * Mth.PI);
            float xzSwingRotation = Mth.sin(sqrtSwing * Mth.PI);
            pose.rotateY(Mth.DEG_TO_RAD * (ITEM_PRESWING_ROT_Y + ySwingRotation * ITEM_SWING_Y_ROT_AMOUNT))
                .rotateZ(Mth.DEG_TO_RAD * (xzSwingRotation * ITEM_SWING_Z_ROT_AMOUNT))
                .rotateX(Mth.DEG_TO_RAD * (xzSwingRotation * ITEM_SWING_X_ROT_AMOUNT))
                .rotateY(Mth.DEG_TO_RAD * -ITEM_PRESWING_ROT_Y);
        }
        return pose;
    }

    /**
     * Back-projects vanilla's screen anchor onto the resting right-hand rod's mid-plane: solves
     * {@code rest * (u, w, 0.5) = s * ray} for {@code (u, w, s)}, where {@code ray} is the hand-pass
     * view ray through {@code VANILLA_ANCHOR_NDC} at the calibration aspect ratio and FOV.
     */
    private static Vector2f anchorOnRestingRod() {
        float tanHalfFov = tanHalf(Camera.BASE_HUD_FOV);
        Vector3f ray = new Vector3f(
                VANILLA_ANCHOR_NDC.x() * tanHalfFov * CALIBRATION_ASPECT_RATIO,
                VANILLA_ANCHOR_NDC.y() * tanHalfFov,
                -1f);
        Matrix4f rest = armPose(0f, 0f).mul(ROD_DISPLAY);
        Vector3f midPlaneOrigin = rest.transformPosition(new Vector3f(0f, 0f, 0.5f));
        Matrix3f system = new Matrix3f(
                rest.getColumn(0, new Vector3f()),
                rest.getColumn(1, new Vector3f()),
                ray.negate());
        Vector3f solution = system.invert().transform(midPlaneOrigin.negate());
        return new Vector2f(solution.x, 1f - solution.y);
    }

    /** {@code tan(fov / 2)}: the view-space tangent at the screen edge for a vertical FOV. */
    static float tanHalf(float fovDegrees) {
        return (float) Math.tan(Mth.DEG_TO_RAD * fovDegrees / 2f);
    }
}
