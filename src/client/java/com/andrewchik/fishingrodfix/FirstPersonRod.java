package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.LayerRenderStateAccessor;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * The first-person fishing rod: where the line attaches to the rod that is drawn, in the hand pass's
 * view space (x right, y up, z back).
 *
 * <p>The attachment point is the drawn rod model's tip ({@link RodModel}: its tip, or where the drawn
 * line meets it past a decorated tip), carried through the same pose stack
 * {@code FirstPersonHandsAndItemsRenderer.submitArmWithItem} uses for an idle or swinging rod: the arm
 * position with the equip/re-equip dip, the swing, then the display transform the item is drawn with.
 * Every rod, vanilla's included, takes the line from that point, as the body-held rod does.
 */
final class FirstPersonRod {
    // FirstPersonHandsAndItemsRenderer.applyItemArmTransform: ITEM_POS_X/Y/Z (right arm; x mirrored
    // for the left) and ITEM_HEIGHT_SCALE (the equip dip, times inverseArmHeight).
    private static final Vector3fc ITEM_POS          = new Vector3f(0.56f, -0.52f, -0.72f);
    private static final float     ITEM_HEIGHT_SCALE = -0.6f;
    // FirstPersonHandsAndItemsRenderer.swingArm / applyItemArmAttackTransform:
    // ITEM_SWING_X/Y/Z_POS_SCALE, ITEM_PRESWING_ROT_Y, ITEM_SWING_X/Y/Z_ROT_AMOUNT.
    private static final Vector3fc ITEM_SWING_POS_SCALE    = new Vector3f(-0.4f, 0.2f, -0.2f);
    private static final float     ITEM_PRESWING_ROT_Y     = 45f;
    private static final float     ITEM_SWING_X_ROT_AMOUNT = -80f;
    private static final float     ITEM_SWING_Y_ROT_AMOUNT = -20f;
    private static final float     ITEM_SWING_Z_ROT_AMOUNT = -20f;

    // The last drawn rod read, its grip and its attachment point (render thread only).
    private static final DrawnItem cached = new DrawnItem();
    private static final DrawnItem cachedGrip = new DrawnItem();
    private static @Nullable Vector3f cachedAnchor;

    private FirstPersonRod() {}

    /**
     * The line's attachment point on the rod {@code drawn} (the hand pass's item state of
     * {@code stack}), in the space that item is submitted in. The hand grips the drawn model where a
     * rod's third-person display transform puts the hand: {@code grip}'s, a third-person item state of
     * a held rod (null if none). Null when the drawn item can't be read as a rod (yet), or no grip is
     * known and none was measured for it before.
     */
    static @Nullable Vector3fc itemAnchor(ItemStackRenderState drawn, ItemStack stack, @Nullable ItemStackRenderState grip) {
        LayerRenderStateAccessor drawnLayer = RodModel.rodLayer(drawn);
        if (drawnLayer == null) {
            return null;
        }
        LayerRenderStateAccessor gripLayer = grip != null ? RodModel.rodLayer(grip) : null;
        if (gripLayer == null) {
            Vector3fc tip = RodModel.measuredTip(drawnLayer.fishingrodfix$getQuads());
            return tip != null ? RodModel.display(drawn, drawnLayer).transformPosition(tip, new Vector3f()) : null;
        }
        // The same drawn model, transforms and grip as last frame: the same point (the inputs only
        // change on a reload, a swap or a new rod).
        if (cached.matches(drawn, drawnLayer) && cachedGrip.matches(grip, gripLayer)) {
            return cachedAnchor;
        }
        Vector3fc tip = RodModel.tip(drawn, stack, RodModel.display(grip, gripLayer));
        if (tip == RodModel.DEFERRED) {
            return null;
        }
        cachedAnchor = tip != null ? RodModel.display(drawn, drawnLayer).transformPosition(tip, new Vector3f()) : null;
        cached.set(drawn, drawnLayer);
        cachedGrip.set(grip, gripLayer);
        return cachedAnchor;
    }

    /**
     * {@code itemAnchor} in hand-pass view space, for the rod held in {@code arm} at swing progress
     * {@code swing} (0 = none) and equip dip {@code inverseArmHeight} (0 = fully raised). Item sway
     * is not included; it rotates the whole hand pass.
     */
    static Vector3f lineAnchor(HumanoidArm arm, float swing, float inverseArmHeight, Vector3fc itemAnchor) {
        return armPose(arm, swing, inverseArmHeight).transformPosition(itemAnchor, new Vector3f());
    }

    /** The arm's pose stack before the item transform: applyItemArmTransform, then swingArm. */
    private static Matrix4f armPose(HumanoidArm arm, float swing, float inverseArmHeight) {
        float invert = arm == HumanoidArm.RIGHT ? 1f : -1f;
        Matrix4f pose = new Matrix4f().translation(invert * ITEM_POS.x(), ITEM_POS.y() + inverseArmHeight * ITEM_HEIGHT_SCALE, ITEM_POS.z());
        if (swing > 0f) {
            float sqrtSwing = Mth.sqrt(swing);
            pose.translate(
                    invert * ITEM_SWING_POS_SCALE.x() * Mth.sin(sqrtSwing * Mth.PI),
                    ITEM_SWING_POS_SCALE.y() * Mth.sin(sqrtSwing * Mth.TWO_PI),
                    ITEM_SWING_POS_SCALE.z() * Mth.sin(swing * Mth.PI));
            float ySwingRotation  = Mth.sin(swing * swing * Mth.PI);
            float xzSwingRotation = Mth.sin(sqrtSwing * Mth.PI);
            pose.rotateY(Mth.DEG_TO_RAD * invert * (ITEM_PRESWING_ROT_Y + ySwingRotation * ITEM_SWING_Y_ROT_AMOUNT))
                .rotateZ(Mth.DEG_TO_RAD * invert * (xzSwingRotation * ITEM_SWING_Z_ROT_AMOUNT))
                .rotateX(Mth.DEG_TO_RAD * (xzSwingRotation * ITEM_SWING_X_ROT_AMOUNT))
                .rotateY(Mth.DEG_TO_RAD * invert * -ITEM_PRESWING_ROT_Y);
        }
        return pose;
    }

    /** {@code tan(fov / 2)}: the view-space tangent at the screen edge for a vertical FOV. */
    static float tanHalf(float fovDegrees) {
        return (float) Math.tan(Mth.DEG_TO_RAD * fovDegrees / 2f);
    }
}
