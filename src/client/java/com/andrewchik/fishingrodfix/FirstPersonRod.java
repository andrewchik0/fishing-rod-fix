package com.andrewchik.fishingrodfix;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
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
 * {@code HeldItemRenderer.renderFirstPersonItem} uses for an idle or swinging rod: the arm position
 * with the equip/re-equip dip, the swing, then the item's display transform. It is derived from
 * vanilla's own screen anchor, so at rest the vanilla rod keeps vanilla's calibration exactly:
 * {@code getHandPos} anchors the line at screen point {@code (±0.525, -0.1)}, and we take the point
 * on the resting rod's mid-plane that the hand pass draws there. That calibration is for vanilla's
 * base hand FOV (70deg on land, {@code GameRenderer.getFov}) and a 16:9 window. Vanilla builds and
 * projects its anchor at the same options FOV, so only the window shape decides whether its line
 * meets its rod; we assume it was tuned at 16:9.
 *
 * <p>Resource packs that redraw the rod (e.g. Faithful 32x) end its tip at another texel; the
 * attachment point is shifted by the same amount as the pack's tip ({@link RodSprite}).
 */
final class FirstPersonRod {
    // FishingBobberEntityRenderer.getHandPos: getPosition(i * 0.525F, -0.1F), i.e. the screen point
    // (NDC) where vanilla's first-person line starts, at any aspect and options FOV while no FOV
    // modifier, bob, swing or nausea/portal warp applies and the camera sits at the eye (no
    // crouch/swim eye-height transition); the drawn rod passes through it only at the 70deg hand and
    // the calibration aspect below.
    private static final Vector2fc VANILLA_ANCHOR_NDC = new Vector2f(0.525f, -0.1f);
    // Assumed (not in vanilla's source, not tuned): the window aspect that anchor was calibrated at.
    // Only the aspect matters: vanilla builds and projects its anchor at the same FOV (see class doc).
    private static final float CALIBRATION_ASPECT_RATIO = 16f / 9f;
    // GameRenderer.getFov: the hand pass's FOV (changingFov false) before the fluid and death factors.
    private static final float BASE_HAND_FOV = 70f;

    // HeldItemRenderer.applyEquipOffset: EQUIP_OFFSET_TRANSLATE_X/Y/Z (0.56, -0.52, -0.72, right arm)
    // and the equip dip scale (-0.6, times the inverted equip progress).
    private static final Vector3fc ITEM_POS          = new Vector3f(0.56f, -0.52f, -0.72f);
    private static final float     ITEM_HEIGHT_SCALE = -0.6f;
    // HeldItemRenderer.swingArm / applySwingOffset: the swing's translation scales (-0.4, 0.2, -0.2),
    // the 45deg pre-swing turn about Y and the swing rotations (-80 about X, -20 about Y and Z).
    private static final Vector3fc ITEM_SWING_POS_SCALE    = new Vector3f(-0.4f, 0.2f, -0.2f);
    private static final float     ITEM_PRESWING_ROT_Y     = 45f;
    private static final float     ITEM_SWING_X_ROT_AMOUNT = -80f;
    private static final float     ITEM_SWING_Y_ROT_AMOUNT = -20f;
    private static final float     ITEM_SWING_Z_ROT_AMOUNT = -20f;
    // models/item/handheld_rod.json "firstperson_righthand", applied as Transformation.apply does:
    // translate (in 1/16 block), rotationXYZ, scale, then -0.5 to centre the model.
    private static final Vector3fc ROD_ROTATION_DEG = new Vector3f(0f, 90f, 25f);
    private static final Vector3fc ROD_TRANSLATION  = new Vector3f(0f, 1.6f, 0.8f).div(16f);
    private static final float     ROD_SCALE        = 0.68f;
    private static final Matrix4fc ROD_DISPLAY = new Matrix4f()
            .translation(ROD_TRANSLATION)
            .rotateXYZ(MathHelper.RADIANS_PER_DEGREE * ROD_ROTATION_DEG.x(), MathHelper.RADIANS_PER_DEGREE * ROD_ROTATION_DEG.y(),
                    MathHelper.RADIANS_PER_DEGREE * ROD_ROTATION_DEG.z())
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
    static Vector3f lineAnchor(MinecraftClient mc, Arm arm, float swing, float inverseArmHeight) {
        // Vanilla's anchor, shifted with the pack's rod tip.
        Vector2fc tip = RodSprite.tipUv(mc);
        Vector2f uv = new Vector2f(tip).sub(RodSprite.VANILLA_TIP_UV).add(VANILLA_ANCHOR_UV);
        Vector3f anchor = armPose(swing, inverseArmHeight)
                .mul(ROD_DISPLAY)
                // Texture v points down, model y up; z = 0.5 is the item's mid-plane
                // (GeneratedItemModel's 7.5 and 8.5 of 16).
                .transformPosition(new Vector3f(uv.x, 1f - uv.y, 0.5f));
        // Left-hand rod = x-mirror of the right one on the model's mid-plane: Transformation.apply's
        // left-hand fix turns handheld_rod's firstperson_lefthand back into the right-hand rotation
        // (which maps the mid-plane to x = 0), and applyEquipOffset, swingArm and applySwingOffset
        // mirror x by the arm's sign.
        if (arm == Arm.LEFT) {
            anchor.x = -anchor.x;
        }
        return anchor;
    }

    /**
     * The right-arm pose stack before the item transform: HeldItemRenderer.swingArm, which on 1.21.8
     * translates by the swing, then calls applyEquipOffset and applySwingOffset; the two translations
     * commute, so the equip offset goes first here.
     */
    private static Matrix4f armPose(float swing, float inverseArmHeight) {
        Matrix4f pose = new Matrix4f().translation(ITEM_POS.x(), ITEM_POS.y() + inverseArmHeight * ITEM_HEIGHT_SCALE, ITEM_POS.z());
        if (swing > 0f) {
            float sqrtSwing = MathHelper.sqrt(swing);
            pose.translate(
                    ITEM_SWING_POS_SCALE.x() * MathHelper.sin(sqrtSwing * MathHelper.PI),
                    ITEM_SWING_POS_SCALE.y() * MathHelper.sin(sqrtSwing * MathHelper.TAU),
                    ITEM_SWING_POS_SCALE.z() * MathHelper.sin(swing * MathHelper.PI));
            float ySwingRotation  = MathHelper.sin(swing * swing * MathHelper.PI);
            float xzSwingRotation = MathHelper.sin(sqrtSwing * MathHelper.PI);
            pose.rotateY(MathHelper.RADIANS_PER_DEGREE * (ITEM_PRESWING_ROT_Y + ySwingRotation * ITEM_SWING_Y_ROT_AMOUNT))
                .rotateZ(MathHelper.RADIANS_PER_DEGREE * (xzSwingRotation * ITEM_SWING_Z_ROT_AMOUNT))
                .rotateX(MathHelper.RADIANS_PER_DEGREE * (xzSwingRotation * ITEM_SWING_X_ROT_AMOUNT))
                .rotateY(MathHelper.RADIANS_PER_DEGREE * -ITEM_PRESWING_ROT_Y);
        }
        return pose;
    }

    /**
     * Back-projects vanilla's screen anchor onto the resting right-hand rod's mid-plane: solves
     * {@code rest * (u, w, 0.5) = s * ray} for {@code (u, w, s)}, where {@code ray} is the hand-pass
     * view ray through {@code VANILLA_ANCHOR_NDC} at the calibration aspect ratio and FOV.
     */
    private static Vector2f anchorOnRestingRod() {
        float tanHalfFov = tanHalf(BASE_HAND_FOV);
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
        return (float) Math.tan(MathHelper.RADIANS_PER_DEGREE * fovDegrees / 2f);
    }
}
