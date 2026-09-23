package com.andrewchik.fishingrodfix;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Vanilla's third-person fishing rod: where the line attaches to a rod held in a player's hand, in the
 * space {@code HeldItemFeatureRenderer.renderItem} draws the rod's item in (before the item's own
 * display transform).
 *
 * <p>The attachment point is the rod's tip ({@link RodSprite}) on the model's mid-plane, carried through
 * the {@code handheld_rod} display transform that {@code ItemRenderer.renderItem} applies when it draws
 * the rod. Everything up to the hand is the pose the rod was actually drawn with, which
 * {@link ThirdPersonLineOrigin} reads where it is drawn, so no body pose is modelled here. First
 * person keeps vanilla's own anchor instead ({@link FirstPersonRod}): at the rod's scale and angle
 * there it sits by the tip, but a third-person rod, seen from any side, needs the tip itself.
 */
final class ThirdPersonRod {
    // models/item/handheld_rod.json "thirdperson_righthand", applied as Transformation.apply does:
    // translate (in 1/16 block), rotationXYZ, scale, then -0.5 to centre the model. The left hand gets
    // the same transform: Transformation.apply's left-hand fix turns "thirdperson_lefthand" back into
    // it (the left arm itself puts it on the other side).
    private static final Vector3fc ROD_ROTATION_DEG = new Vector3f(0f, 90f, 55f);
    private static final Vector3fc ROD_TRANSLATION  = new Vector3f(0f, 4f, 2.5f).div(16f);
    private static final float     ROD_SCALE        = 0.85f;
    private static final Matrix4fc ROD_DISPLAY = new Matrix4f()
            .translation(ROD_TRANSLATION)
            .rotateXYZ(MathHelper.RADIANS_PER_DEGREE * ROD_ROTATION_DEG.x(), MathHelper.RADIANS_PER_DEGREE * ROD_ROTATION_DEG.y(),
                    MathHelper.RADIANS_PER_DEGREE * ROD_ROTATION_DEG.z())
            .scale(ROD_SCALE)
            .translate(-0.5f, -0.5f, -0.5f);

    // The attachment point, recomputed once per frame (HandPass frame): the pack's tip only changes
    // on a resource reload, between frames, but finding it costs two atlas lookups. Render-thread only.
    private static final Vector3f cachedAnchor = new Vector3f();
    private static long cachedFrame = Long.MIN_VALUE;

    private ThirdPersonRod() {}

    /**
     * The line's attachment point in the space the rod's item is drawn in by
     * {@code HeldItemFeatureRenderer}; the same for either hand. Only valid until the next call.
     */
    static Vector3fc lineAnchor(MinecraftClient mc) {
        long frame = HandPass.frame();
        if (frame != cachedFrame) {
            Vector2fc tip = RodSprite.tipUv(mc);
            // Texture v points down, model y up; z = 0.5 is the item's mid-plane
            // (ItemModelGenerator's 7.5 and 8.5 of 16).
            ROD_DISPLAY.transformPosition(tip.x(), 1f - tip.y(), 0.5f, cachedAnchor);
            cachedFrame = frame;
        }
        return cachedAnchor;
    }
}
