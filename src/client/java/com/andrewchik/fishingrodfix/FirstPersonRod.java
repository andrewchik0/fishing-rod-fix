package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.SpriteContentsAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;

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
 * <p>Resource packs that redraw the rod (e.g. Faithful 32x) end its tip at another texel. The pack's
 * tip is measured from the items-atlas sprite and the attachment point is shifted by the same
 * amount. Covers texture-only packs; packs that also replace the rod's model geometry or its
 * first-person display transform, or swap the model per item, are not measured.
 */
final class FirstPersonRod {
    private static final Identifier CAST_ROD_SPRITE = Identifier.withDefaultNamespace("item/fishing_rod_cast");

    // FishingHookRenderer.getPlayerHandPos: getPointOnPlane(invert * 0.525F, -0.1F), i.e. the screen
    // point (NDC) where vanilla's first-person line starts, at any aspect and options FOV while no FOV
    // modifier, bob, swing or nausea/portal warp applies and the camera sits at the eye (no
    // crouch/swim eye-height transition); the drawn rod passes through it only at the 70deg hand and
    // the calibration aspect below.
    private static final Vector2fc VANILLA_ANCHOR_NDC = new Vector2f(0.525f, -0.1f);
    // Assumed (not in vanilla's source, not tuned): the window aspect that anchor was calibrated at.
    // Only the aspect matters: vanilla builds and projects its anchor at the same FOV (see class doc).
    private static final float CALIBRATION_ASPECT_RATIO = 16f / 9f;

    // Tip of vanilla's 16x16 fishing_rod_cast.png as findTip measures it: the top-right corner of
    // texel (14, 1). The alpha mask is identical in every MC version checked so far (see the
    // port-version skill's table).
    private static final Vector2fc VANILLA_TIP_UV = new Vector2f(15f / 16f, 1f / 16f);

    // The one tuned constant: a plausibility gate, never a scale (it only decides whether a
    // measurement is used). 2x the largest shift observed (Faithful 32x: (0.25, 0.75)/16); a larger
    // shift means findTip misread the texture (mirrored rod, decorations, a 3D model's UV sheet).
    private static final float MAX_TIP_SHIFT = 1.5f / 16f;

    // RenderPipelines' item pipelines (ITEM_CUTOUT, ITEM_TRANSLUCENT, OIT_ITEM_SNIPPET) set
    // ALPHA_CUTOUT = ALPHA_CUTOUT_THRESHOLD_DEFAULT (0.1): core/item discards texture alpha below
    // it, so fainter pixels are not part of the visible rod.
    private static final float ITEM_ALPHA_CUTOUT = 0.1f;

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

    // SpriteContents are recreated on every resource reload, so their identity is the cache key.
    // A failed measurement is cached as the vanilla anchor and retried on the next reload. Weak, so
    // the previous reload's closed sprite isn't kept alive; a cleared reference never matches.
    private static WeakReference<SpriteContents> cachedSprite = new WeakReference<>(null);
    private static Vector2fc cachedAnchorUv = VANILLA_ANCHOR_UV;

    private FirstPersonRod() {}

    /**
     * The line's attachment point in hand-pass view space, for the rod held in {@code arm} at swing
     * progress {@code swing} (0 = none) and equip dip {@code inverseArmHeight} (0 = fully raised).
     * Item sway is not included; it rotates the whole hand pass.
     */
    static Vector3f lineAnchor(Minecraft mc, HumanoidArm arm, float swing, float inverseArmHeight) {
        Vector2fc uv = anchorUv(mc);
        Vector3f anchor = armPose(swing, inverseArmHeight)
                .mul(ROD_DISPLAY)
                // Texture v points down, model y up; z = 0.5 is the item's mid-plane
                // (ItemModelGenerator's MIN_Z/MAX_Z = 7.5/8.5 of 16).
                .transformPosition(new Vector3f(uv.x(), 1f - uv.y(), 0.5f));
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

    private static Vector2fc anchorUv(Minecraft mc) {
        SpriteContents sprite = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.ITEMS).getSprite(CAST_ROD_SPRITE).contents();
        if (sprite != cachedSprite.get()) {
            Vector2fc tipUv = measureTip(sprite);
            cachedAnchorUv = new Vector2f(tipUv).sub(VANILLA_TIP_UV).add(VANILLA_ANCHOR_UV);
            cachedSprite = new WeakReference<>(sprite);
        }
        return cachedAnchorUv;
    }

    /** The pack's rod tip, or vanilla's when it can't be measured or isn't plausible. */
    private static Vector2fc measureTip(SpriteContents sprite) {
        if (sprite.name().equals(MissingTextureAtlasSprite.getLocation())) {
            return VANILLA_TIP_UV;
        }
        Vector2f tip;
        try {
            tip = findTip(sprite.width(), sprite.height(), visiblePixels(sprite));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Could not read the {} sprite, assuming the vanilla rod until the next resource reload", sprite.name(), e);
            return VANILLA_TIP_UV;
        }
        // A vanilla-shaped tip needs no measurement log line.
        if (tip == null || tip.equals(VANILLA_TIP_UV)) {
            return VANILLA_TIP_UV;
        }
        if (Math.abs(tip.x - VANILLA_TIP_UV.x()) > MAX_TIP_SHIFT || Math.abs(tip.y - VANILLA_TIP_UV.y()) > MAX_TIP_SHIFT) {
            LOGGER.info("Fishing rod tip of {} at ({}, {})/16 is too far from vanilla's, ignoring it",
                    sprite.name(), tip.x * 16f, tip.y * 16f);
            return VANILLA_TIP_UV;
        }
        LOGGER.info("Fishing rod tip of {} measured at ({}, {})/16 (vanilla: {}, {})", sprite.name(),
                tip.x * 16f, tip.y * 16f, VANILLA_TIP_UV.x() * 16f, VANILLA_TIP_UV.y() * 16f);
        return tip;
    }

    /**
     * Pixels of the sprite that are visible in game: alpha at or above the item cutout in any
     * animation frame (ItemModelGenerator's outline also unions the frames).
     */
    private static PixelMask visiblePixels(SpriteContents sprite) {
        NativeImage image = ((SpriteContentsAccessor) sprite).fishingrodfix$getOriginalImage();
        int width = sprite.width();
        int height = sprite.height();
        // Frames are frame-sized tiles laid out row-major (SpriteContents.AnimatedTexture.getFrameX/Y),
        // offset only for animated sprites, as in SpriteContents.isTransparent; a static sprite
        // shows tile 0 and its getUniqueFrames() is a dummy [1].
        IntList frames = sprite.isAnimated() ? sprite.getUniqueFrames() : IntList.of(0);
        int framesPerRow = image.getWidth() / width;
        float minAlpha = ITEM_ALPHA_CUTOUT * 255f;
        return (x, y) -> {
            for (int i = 0; i < frames.size(); i++) {
                int frame = frames.getInt(i);
                int pixel = image.getPixel((frame % framesPerRow) * width + x, (frame / framesPerRow) * height + y);
                if (ARGB.alpha(pixel) >= minAlpha) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * The far end of the rod: the visible texel corner that lies furthest along the rod's
     * handle-to-tip direction (towards the texture's top-right). Corners tied for the extreme
     * (a chamfered tip) are averaged. Returns texture {@code (u, v)} in {@code [0, 1]}, v down.
     */
    private static @Nullable Vector2f findTip(int width, int height, PixelMask visible) {
        // A pixel's top-right corner is its extreme point along (+u, -v). Score u - v, scaled by
        // width * height so the comparison stays exact in integers.
        long best = Long.MIN_VALUE;
        long sumX = 0;
        long sumY = 0;
        int count = 0;
        for (int y = 0; y < height; y++) {
            // No pixel in this row or below can reach the best score any more.
            if ((long) width * height - (long) y * width < best) {
                break;
            }
            // Scan from the right: the row's rightmost visible pixel is its extreme.
            for (int x = width - 1; x >= 0; x--) {
                if (!visible.isVisible(x, y)) {
                    continue;
                }
                long score = (long) (x + 1) * height - (long) y * width;
                if (score > best) {
                    best = score;
                    sumX = x + 1;
                    sumY = y;
                    count = 1;
                } else if (score == best) {
                    sumX += x + 1;
                    sumY += y;
                    count++;
                }
                break;
            }
        }
        return count == 0 ? null : new Vector2f((float) sumX / count / width, (float) sumY / count / height);
    }

    /** {@code tan(fov / 2)}: the view-space tangent at the screen edge for a vertical FOV. */
    static float tanHalf(float fovDegrees) {
        return (float) Math.tan(Mth.DEG_TO_RAD * fovDegrees / 2f);
    }

    @FunctionalInterface
    private interface PixelMask {
        boolean isVisible(int x, int y);
    }
}
