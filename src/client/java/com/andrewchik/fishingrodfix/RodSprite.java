package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.SpriteContentsAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.util.Atlases;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector2fc;

import java.lang.ref.WeakReference;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;

/**
 * The cast fishing rod's sprite, which the rod model is generated from in every view: where the
 * rod's tip is on it.
 *
 * <p>Resource packs that redraw the rod (e.g. Faithful 32x) end its tip at another texel. The pack's
 * tip is measured from the block-atlas sprite (1.21.10 keeps item textures there). Covers
 * texture-only packs; packs that also replace the rod's model geometry or its display transforms, or
 * swap the model per item, are not measured.
 */
final class RodSprite {
    private static final Identifier CAST_ROD_SPRITE = Identifier.ofVanilla("item/fishing_rod_cast");

    // Tip of vanilla's 16x16 fishing_rod_cast.png as findTip measures it: the top-right corner of
    // texel (14, 1). The alpha mask is identical in every MC version checked so far (see the
    // port-version skill's table).
    static final Vector2fc VANILLA_TIP_UV = new Vector2f(15f / 16f, 1f / 16f);

    // The one tuned constant: a plausibility gate, never a scale (it only decides whether a
    // measurement is used). 2x the largest shift component observed (Faithful 32x: (0.25, 0.75)/16);
    // a larger shift means findTip misread the texture (mirrored rod, decorations, a 3D model's UV
    // sheet).
    private static final float MAX_TIP_SHIFT = 1.5f / 16f;

    // A cost cap, not part of the measurement: measuring runs once per resource reload, but in a
    // gameplay frame right after it, so it gets a millisecond at most. findTip reads at most about a
    // twentieth of a frame's pixels, in each frame of an animation, which leaves every plausible pack
    // -- any static sprite up to 2896x2896 (a 181x pack), 512x512 over 32 animation frames -- well
    // inside that. A sheet past this isn't a rod texture that can be measured in time: vanilla's tip.
    private static final long MAX_MEASURED_SAMPLES = 512L * 512L * 32L;

    // Held items (block atlas) draw with the item_entity_translucent_cull pipeline, whose
    // fragment shader (core/rendertype_item_entity_translucent_cull.fsh) discards alpha below 0.1, as
    // the entity_cutout pipeline's ALPHA_CUTOUT does: fainter pixels are not part of the visible rod.
    private static final float ITEM_ALPHA_CUTOUT = 0.1f;

    // SpriteContents are recreated on every resource reload, so their identity is the cache key.
    // A failed measurement is cached as vanilla's tip and retried on the next reload. Weak, so the
    // previous reload's closed sprite isn't kept alive; a cleared reference never matches.
    // Render-thread only.
    private static WeakReference<SpriteContents> cachedSprite = new WeakReference<>(null);
    private static Vector2fc cachedTipUv = VANILLA_TIP_UV;

    private RodSprite() {}

    /**
     * The rod's tip as texture {@code (u, v)} in {@code [0, 1]}, v down: the pack's, or vanilla's
     * when it can't be measured or isn't plausible.
     */
    static Vector2fc tipUv(MinecraftClient mc) {
        SpriteContents sprite = mc.getAtlasManager().getAtlasTexture(Atlases.BLOCKS).getSprite(CAST_ROD_SPRITE).getContents();
        if (sprite != cachedSprite.get()) {
            cachedTipUv = measureTip(sprite);
            cachedSprite = new WeakReference<>(sprite);
        }
        return cachedTipUv;
    }

    private static Vector2fc measureTip(SpriteContents sprite) {
        if (sprite.getId().equals(MissingSprite.getMissingSpriteId())) {
            return VANILLA_TIP_UV;
        }
        Vector2f tip;
        try {
            // Frames are frame-sized tiles laid out row-major (SpriteContents.Animation.getFrameX/Y),
            // offset only for animated sprites, as in SpriteContents.isPixelTransparent (a sprite has
            // an animation only with more than one frame); a static sprite shows tile 0 and its
            // getDistinctFrameCount() is a dummy [1].
            int[] frames = sprite.isAnimated() ? sprite.getDistinctFrameCount().toArray() : new int[] {0};
            if ((long) sprite.getWidth() * sprite.getHeight() * frames.length > MAX_MEASURED_SAMPLES) {
                LOGGER.info("Fishing rod sprite {} is too large to measure ({}x{}, {} frames), assuming the vanilla rod",
                        sprite.getId(), sprite.getWidth(), sprite.getHeight(), frames.length);
                return VANILLA_TIP_UV;
            }
            tip = findTip(sprite.getWidth(), sprite.getHeight(), visiblePixels(sprite, frames));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Could not read the {} sprite, assuming the vanilla rod until the next resource reload", sprite.getId(), e);
            return VANILLA_TIP_UV;
        }
        // A vanilla-shaped tip needs no measurement log line.
        if (tip == null || tip.equals(VANILLA_TIP_UV)) {
            return VANILLA_TIP_UV;
        }
        if (Math.abs(tip.x - VANILLA_TIP_UV.x()) > MAX_TIP_SHIFT || Math.abs(tip.y - VANILLA_TIP_UV.y()) > MAX_TIP_SHIFT) {
            LOGGER.info("Fishing rod tip of {} at ({}, {})/16 is too far from vanilla's, ignoring it",
                    sprite.getId(), tip.x * 16f, tip.y * 16f);
            return VANILLA_TIP_UV;
        }
        LOGGER.info("Fishing rod tip of {} measured at ({}, {})/16 (vanilla: {}, {})", sprite.getId(),
                tip.x * 16f, tip.y * 16f, VANILLA_TIP_UV.x() * 16f, VANILLA_TIP_UV.y() * 16f);
        return tip;
    }

    /**
     * Pixels of the sprite that are visible in game: alpha at or above the item cutout in any of its
     * {@code frames} (GeneratedItemModel's outline also unions the frames).
     */
    private static PixelMask visiblePixels(SpriteContents sprite, int[] frames) {
        NativeImage image = ((SpriteContentsAccessor) sprite).fishingrodfix$getImage();
        int width = sprite.getWidth();
        int height = sprite.getHeight();
        int framesPerRow = image.getWidth() / width;
        float minAlpha = ITEM_ALPHA_CUTOUT * 255f;
        return (x, y) -> {
            for (int frame : frames) {
                int pixel = image.getColorArgb((frame % framesPerRow) * width + x, (frame / framesPerRow) * height + y);
                if (ColorHelper.getAlpha(pixel) >= minAlpha) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * The far end of the rod: the visible texel corner that lies furthest along the rod's
     * handle-to-tip direction (towards the texture's top-right). Corners tied for the extreme
     * (a chamfered tip) are averaged. Returns texture {@code (u, v)} in {@code [0, 1]}, v down, or
     * null when no corner reaches far enough for the measurement to be used at all.
     */
    private static @Nullable Vector2f findTip(int width, int height, PixelMask visible) {
        // A pixel's top-right corner is its extreme point along (+u, -v). Score u - v, scaled by
        // width * height so the comparison stays exact in integers.
        //
        // The search starts at the plausibility gate's own floor rather than at nothing. The score is
        // affine in the corner, so the averaged tip scores exactly what its tied corners score, and
        // measureTip only uses a tip within MAX_TIP_SHIFT of vanilla's: no corner scoring below
        // (VANILLA_U - MAX_TIP_SHIFT) - (VANILLA_V + MAX_TIP_SHIFT) can be part of one. Skipping those
        // leaves every measurement that is used exactly as it was, turns one that isn't into no
        // measurement (vanilla's tip either way) and bounds the scan to the sprite's top-right corner,
        // about a twentieth of its pixels, however the pack draws the rod.
        long floor = (long) Math.ceil(
                (double) (VANILLA_TIP_UV.x() - VANILLA_TIP_UV.y() - 2f * MAX_TIP_SHIFT) * width * height);
        long best = floor - 1;
        long sumX = 0;
        long sumY = 0;
        int count = 0;
        for (int y = 0; y < height; y++) {
            // No pixel in this row or below can reach the best score any more.
            if ((long) width * height - (long) y * width < best) {
                break;
            }
            // Nor can one left of minX: the first column whose corner can still reach it.
            int minX = (int) Math.max(0L, ((best + (long) y * width + height - 1) / height) - 1);
            // Scan from the right: the row's rightmost visible pixel is its extreme.
            for (int x = width - 1; x >= minX; x--) {
                if (!visible.isVisible(x, y)) {
                    continue;
                }
                long score = (long) (x + 1) * height - (long) y * width;
                if (score > best) {
                    best = score;
                    sumX = x + 1;
                    sumY = y;
                    count = 1;
                } else if (score == best && count > 0) {
                    // count > 0: the floor best starts at is a threshold, not a corner to tie with.
                    sumX += x + 1;
                    sumY += y;
                    count++;
                }
                break;
            }
        }
        return count == 0 ? null : new Vector2f((float) sumX / count / width, (float) sumY / count / height);
    }

    @FunctionalInterface
    private interface PixelMask {
        boolean isVisible(int x, int y);
    }
}
