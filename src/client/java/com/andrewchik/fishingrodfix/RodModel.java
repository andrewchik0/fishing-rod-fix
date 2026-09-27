package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.ItemStackRenderStateAccessor;
import com.andrewchik.fishingrodfix.mixin.client.LayerRenderStateAccessor;
import com.andrewchik.fishingrodfix.mixin.client.SpriteContentsAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;

/**
 * The rod as it is drawn: the model the game resolved for the held rod (a resource pack's own model,
 * 2D or 3D, a server's {@code item_model}, a modded rod's) and the display transform it is drawn
 * with, read from the item's render state instead of assumed.
 *
 * <p>Only what is visible counts: a texel under the item alpha cutout isn't part of the rod, so a
 * transparent placeholder element, or the empty part of a plane a rod's bend is painted on, is ignored.
 *
 * <p>Where the line leaves the rod is the artist's choice, and the artist draws it on the uncast rod:
 * the line wound or hanging from where it attaches, gone once cast. So the uncast model (the same
 * stack resolved without a fishing owner) is compared with the cast one: what the uncast rod draws in
 * any animation frame and the cast rod in none is the line. A generated rod's two ends are the ends of
 * the longest path over its connected pixels (a double sweep, over the largest piece the line
 * touches); the end a pixel of the line touching the rod is nearest to along the rod is taken (the
 * line may also touch the rod lower down, at the reel; on a tie, the end nearer the contacts
 * overall), and without such a contact the pixel farthest from where the hand grips it (the
 * origin of its third-person pose), among the pixels connected to the grip. A 3D rod (the largest
 * connected part of its visible texel cells) is held at one end and cast from the other: of the
 * farthest pair of its cells (a double sweep), the end farther from the grip, or the upper one when
 * the grip is about as far from both (a model held by its centre). The line starts at that
 * end's extreme tip, the centre of its last texel (a generated rod) or the mean of its cells within a
 * texel of the farthest along the rod (3D), as players know the vanilla line; unless the tip isn't
 * ahead of where the drawn line meets the rod (a decoration beside the line), then at that contact.
 *
 * <p>A model generated from sprites ({@code item/generated}: every quad within its 1/16-thick slab, per
 * layer a front and a back face over the whole canvas showing the whole sprite) is measured on its
 * sprites' pixels, which the side faces {@code ItemModelGenerator} bakes trace; layers at different
 * resolutions are measured on their faces instead. A sprite too large to walk within the cost budget
 * is walked on a coarser grid (a sampled texel per cell), and the end cell is then refined to its texel.
 *
 * <p>Measuring runs once per model and grip (cached; a resource reload bakes new models), at most once
 * per frame, and within a fixed number of texel reads; a model it can't finish keeps vanilla's value.
 * The cache is keyed by the first layer's quads and the grip only: two stacks differing only in an
 * overlay layer or in their uncast model (rare) share one tip.
 */
final class RodModel {
    // RenderPipelines' item pipelines set ALPHA_CUTOUT_THRESHOLD_DEFAULT (0.1): core/item discards
    // texture alpha below it, so fainter pixels are not part of the visible rod.
    private static final float ITEM_ALPHA_CUTOUT = 0.1f;
    // Geometry closer than this (1e-4 block, ~1/600 of a 16x texel) touches; baked positions are
    // exact multiples of a texel, so this only absorbs float rounding.
    private static final float TOUCH_EPSILON = 1e-4f;
    // ItemModelGenerator's slab (z 7.5-8.5 sixteenths), and how far a canvas face's normalised UVs may
    // be off its position (atlas float rounding).
    private static final float GENERATED_MIN_Z = 7.5f / 16f;
    private static final float GENERATED_MAX_Z = 8.5f / 16f;
    private static final float UV_EPSILON = 1e-3f;
    // Frames.shown's answers: a texel shown in some animation frame, in most of them.
    private static final int ANY = 1;
    private static final int STEADY = 2;
    // A texel of a 16x model: 3D faces are measured in cells of this size, each shown if any of at most
    // CELL_SAMPLES texels under it is (an HD texture has several per cell), and grouped into buckets of
    // BUCKET a side to find the cells that touch.
    private static final float CELL = 1f / 16f;
    private static final int CELL_SAMPLES = 4 * 4;
    private static final float BUCKET = CELL / 2;
    // Cost caps, not part of the measurement. A measurement runs once per model in a gameplay frame
    // (the first frame it is drawn fishing), rarely enough that its code runs interpreted: a texel read
    // or a 3D cell or bucket entry costs ~35-200 ns. Ordinary rods take a few thousand steps, 3D ones up
    // to ~125k (measured 2026-09-27 in game: 16x sprites ~0.2-0.4 ms, a 512x sprite ~0.6-0.9 ms, 3D models
    // with ~600-700 visible cells ~0.6-1.4 ms; HD custom-model-data staves whose large painted planes are
    // mostly transparent 85-125k, ~3-5 ms, nearly all of it reading their empty cells; the first, cold runs happen at world join and dimension
    // change behind the loading screen, see warmUp: measured 2026-09-27, the pack's uncast rod ~2 ms
    // generated / ~9-13 ms 3D, then cached, and the 3D path on a few faces, once per session, ~0.5-4 ms);
    // SAMPLE_BUDGET only stops a pathological model, letting a very complex one take a few ms
    // once (user decision 2026-09-26: accuracy over a one-off frame). A sprite is walked on a grid of at
    // most GRID_CELLS cells (32x32: exact up to 32x packs, coarser and then refined to the texel beyond;
    // coarser too when its and the uncast sprite's animation frames, all read per cell, wouldn't fit
    // GRID_BUDGET); faces read at most FACE_SAMPLES texels each.
    private static final int SAMPLE_BUDGET = 200_000;
    private static final int GRID_BUDGET = 20_000;
    private static final int GRID_CELLS = 32 * 32;
    private static final int FACE_SAMPLES = 8 * 8;
    // The refinement of a coarse end cell reads its 3x3 neighbourhood on a grid of at most this many
    // texels a side, then every texel around the best one.
    private static final int REFINE_SIDE = 24;
    private static final int MAX_QUADS = 2048;
    // The line starts at the rod's extreme tip unless that tip isn't ahead of where the drawn line meets
    // the rod: at 80deg or more off the rod's direction there it is a decoration beside the line (Tide's
    // crystal rod, Hypixel+'s yeti rod), and the line starts at the contact. Over the 2026-09-26 test
    // set ordinary rods measure 0-69deg, decorated ones 92deg and up.
    // A 3D rod's far end from the grip is its tip when it is at least this much farther than the other
    // end: over the 2026-09-27 test set 1.42x and up with a third-person transform, 1.02-1.28x for packs
    // without one (the grip at the model's centre).
    private static final float GRIP_RATIO = 1.35f;
    private static final float DECORATED_TIP_COS = (float) Math.cos(Math.toRadians(80));

    // What tip() returns for a model it didn't measure this frame (another was): the caller keeps
    // vanilla's value, and the model is measured on a later frame. Compared by identity.
    static final Vector3fc DEFERRED = new Vector3f(Float.NaN);

    // Measured models, keyed by the identity of their baked quads (one ItemQuads per baked model; a
    // resource reload bakes new ones) and the grip transform, the least recently used replaced when
    // full. Weak, so a reloaded model isn't kept alive. Render-thread only, as is everything here.
    private static final int CACHE_SIZE = 128;
    private static final CacheEntry[] cache = new CacheEntry[CACHE_SIZE];
    private static long measuredFrame = Long.MIN_VALUE;
    private static boolean failureLogged;
    // The 3D path's warm-up only loads code: a few faces, once per session.
    private static final int WARM_UP_FACES = 8;
    private static boolean facesWarmedUp;
    private static final ItemStackRenderState uncastScratch = new ItemStackRenderState();
    private static int samplesLeft;

    private RodModel() {}

    /** The layer the rod is drawn from, or null when the item isn't drawn from baked geometry this can read. */
    static @Nullable LayerRenderStateAccessor rodLayer(ItemStackRenderState item) {
        ItemStackRenderStateAccessor accessor = (ItemStackRenderStateAccessor) item;
        int count = accessor.fishingrodfix$getActiveLayerCount();
        if (count <= 0) {
            return null;
        }
        LayerRenderStateAccessor layer = (LayerRenderStateAccessor) accessor.fishingrodfix$getLayers()[0];
        // A special renderer (block entity-like models) draws geometry this can't see.
        if (layer.fishingrodfix$getSpecialRenderer() != null || layer.fishingrodfix$getQuads().all().isEmpty()) {
            return null;
        }
        // Extra layers (overlays, tints) must share the first one's transforms to be read as one model.
        for (int i = 1; i < count; i++) {
            LayerRenderStateAccessor other = (LayerRenderStateAccessor) accessor.fishingrodfix$getLayers()[i];
            if (other.fishingrodfix$getSpecialRenderer() != null
                    || !other.fishingrodfix$getItemTransform().equals(layer.fishingrodfix$getItemTransform())
                    || !other.fishingrodfix$getLocalTransform().equals(layer.fishingrodfix$getLocalTransform())) {
                return null;
            }
        }
        return layer;
    }

    /**
     * The transform {@code LayerRenderState.applyTransform} draws {@code item}'s layer with: model space
     * to the space the item is submitted in.
     */
    static Matrix4f display(ItemStackRenderState item, LayerRenderStateAccessor layer) {
        PoseStack.Pose pose = new PoseStack().last();
        boolean leftHand = ((ItemStackRenderStateAccessor) item).fishingrodfix$getDisplayContext().leftHand();
        layer.fishingrodfix$getItemTransform().apply(leftHand, pose);
        return pose.pose().mul(layer.fishingrodfix$getLocalTransform());
    }

    /**
     * The tip of the rod drawn from {@code item} (the render state of {@code stack}), gripped where
     * {@code grip} (a rod's third-person display transform, see {@link #display}) puts the hand: in
     * model space (blocks, the model's own 0-1 box); null if it can't be read as a rod; {@link #DEFERRED}
     * if it isn't measured yet.
     */
    static @Nullable Vector3fc tip(ItemStackRenderState item, ItemStack stack, Matrix4fc grip) {
        ItemStackRenderStateAccessor accessor = (ItemStackRenderStateAccessor) item;
        ItemQuads quads = ((LayerRenderStateAccessor) accessor.fishingrodfix$getLayers()[0]).fishingrodfix$getQuads();
        long frame = HandPass.frame();
        // Slot for a new measurement: a free one (never used, or its model reloaded away), else the LRU.
        int slot = -1;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < CACHE_SIZE; i++) {
            CacheEntry entry = cache[i];
            ItemQuads cached = entry != null ? entry.quads.get() : null;
            if (cached == null) {
                oldest = Long.MIN_VALUE;
                slot = i;
            } else if (cached == quads && entry.grip.equals(grip)) {
                entry.lastUsed = frame;
                return entry.tip;
            } else if (entry.lastUsed < oldest) {
                oldest = entry.lastUsed;
                slot = i;
            }
        }
        // One measurement per frame: several new models in one frame are spread over the next ones.
        if (measuredFrame == frame) {
            return DEFERRED;
        }
        measuredFrame = frame;
        Vector3fc tip;
        try {
            samplesLeft = SAMPLE_BUDGET;
            tip = measure(accessor, resolveUncast(stack, accessor), grip);
        } catch (OverBudget e) {
            tip = null;
        } catch (RuntimeException | LinkageError e) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("Could not read a fishing rod model, keeping vanilla's line for it until the next resource reload", e);
            }
            tip = null;
        } finally {
            uncastScratch.clear();
        }
        cache[slot] = new CacheEntry(new WeakReference<>(quads), new Matrix4f(grip), tip, frame);
        return tip;
    }

    /**
     * A tip measured for the model drawn from {@code quads}, at whatever grip; null if none is cached.
     * For a drawn model whose own grip isn't known this frame.
     */
    static @Nullable Vector3fc measuredTip(ItemQuads quads) {
        for (CacheEntry entry : cache) {
            if (entry != null && entry.quads.get() == quads) {
                return entry.tip;
            }
        }
        return null;
    }

    /**
     * {@code stack} resolved as nobody's (so not cast) for the same display context; null if that can't
     * be read, or throws (an item model that needs an owner): the rod is then measured without it.
     */
    private static @Nullable ItemStackRenderStateAccessor resolveUncast(ItemStack stack, ItemStackRenderStateAccessor drawn) {
        Minecraft mc = Minecraft.getInstance();
        try {
            mc.getItemModelResolver().updateForTopItem(uncastScratch, stack, drawn.fishingrodfix$getDisplayContext(), mc.level, null, 0);
        } catch (RuntimeException e) {
            uncastScratch.clear();
            return null;
        }
        return rodLayer(uncastScratch) != null ? (ItemStackRenderStateAccessor) uncastScratch : null;
    }

    /**
     * Loads and runs the measuring code at world join and dimension change (a loading screen), so the
     * first cast in game doesn't pay for loading it: the pack's uncast vanilla rod (resolved without an
     * owner, so not the cast model the first cast measures; cached, so measured once per model), and,
     * once per session, the 3D path on a few of that model's faces (result dropped).
     */
    static void warmUp() {
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        ItemStackRenderState state = new ItemStackRenderState();
        Minecraft mc = Minecraft.getInstance();
        mc.getItemModelResolver().updateForTopItem(state, rod, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, mc.level, null, 0);
        LayerRenderStateAccessor layer = rodLayer(state);
        if (layer == null) {
            return;
        }
        measuredFrame = Long.MIN_VALUE;
        Matrix4f grip = display(state, layer);
        tip(state, rod, grip);
        if (facesWarmedUp) {
            return;
        }
        facesWarmedUp = true;
        samplesLeft = SAMPLE_BUDGET;
        try {
            List<BakedQuad> faces = visibleQuads((ItemStackRenderStateAccessor) state);
            if (faces != null && !faces.isEmpty()) {
                faces = faces.subList(0, Math.min(WARM_UP_FACES, faces.size()));
                Cells cells = Cells.of(faces, true);
                Cells line = Cells.of(faces.subList(0, Math.min(2, faces.size())), false);
                line.without(cells);
                cells.tip(line, grip.invert(new Matrix4f()).transformPosition(new Vector3f()));
            }
        } catch (OverBudget e) {
            // Only the warm-up is cut short.
        }
    }

    private static @Nullable Vector3fc measure(ItemStackRenderStateAccessor cast, @Nullable ItemStackRenderStateAccessor uncast, Matrix4fc grip) {
        float determinant = grip.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1e-9f) {
            return null;
        }
        Vector3f hand = grip.invert(new Matrix4f()).transformPosition(new Vector3f());
        List<SpriteContents> castCanvas = canvasSprites(cast);
        List<SpriteContents> uncastCanvas = uncast != null ? canvasSprites(uncast) : null;
        // Layers at different resolutions (or too many animation frames for one cell) can't share a
        // grid: such a model is measured on its faces.
        PixelGrid castPixels = null;
        int frames = 0;
        if (castCanvas != null) {
            // Both grids read every animation frame of their sprites per cell: size them together.
            frames = frameCount(castCanvas) + (uncastCanvas != null ? frameCount(uncastCanvas) : 0);
            castPixels = PixelGrid.of(castCanvas, 1, frames, true);
        }
        if (castPixels != null) {
            // Sized together, the uncast grid fits too; one that can't be built only drops the line.
            PixelGrid uncastPixels = uncastCanvas != null ? PixelGrid.of(uncastCanvas, castPixels.stride, frames, false) : null;
            // Texture v points down, model y up.
            int[] end = castPixels.lineEnd(uncastPixels);
            if (end == null) {
                end = castPixels.farthestFrom(hand.x, 1f - hand.y);
            }
            if (end == null) {
                return null;
            }
            // The centre of the end texel, on the mid-plane.
            return new Vector3f((end[0] + 0.5f) / castPixels.width, 1f - (end[1] + 0.5f) / castPixels.height, 0.5f);
        }
        List<BakedQuad> castQuads = visibleQuads(cast);
        if (castQuads == null || castQuads.isEmpty()) {
            return null;
        }
        // Faces are compared only between models built alike (both generated, or neither).
        List<BakedQuad> uncastQuads = null;
        if (uncast != null && (uncastCanvas != null) == (castCanvas != null)) {
            // Most of an uncast model is the cast one: only its other quads are cut into cells.
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int i = 0; i < cast.fishingrodfix$getActiveLayerCount(); i++) {
                for (BakedQuad quad : ((LayerRenderStateAccessor) cast.fishingrodfix$getLayers()[i]).fishingrodfix$getQuads().all()) {
                    drawn.add(signature(quad));
                }
            }
            uncastQuads = visibleQuads(uncast, drawn);
        }
        Cells rod = Cells.of(castQuads, true);
        Vector3f point = rod.tip(uncastQuads != null ? Cells.of(uncastQuads, false).without(rod) : null, hand);
        return point != null && point.isFinite() ? point : null;
    }

    /**
     * The sprites of a model generated from them ({@code ItemModelGenerator}: per layer a SOUTH and a
     * NORTH face over the whole canvas, showing the whole sprite, with side faces between them); null
     * when the model isn't built that way (it is then measured on its faces).
     */
    private static @Nullable List<SpriteContents> canvasSprites(ItemStackRenderStateAccessor item) {
        List<SpriteContents> south = new ArrayList<>();
        List<SpriteContents> north = new ArrayList<>();
        for (int i = 0; i < item.fishingrodfix$getActiveLayerCount(); i++) {
            for (BakedQuad quad : ((LayerRenderStateAccessor) item.fishingrodfix$getLayers()[i]).fishingrodfix$getQuads().all()) {
                if (!inGeneratedSlab(quad)) {
                    return null;
                }
                if (isCanvasFace(quad)) {
                    (quad.direction() == Direction.SOUTH ? south : north).add(quad.materialInfo().sprite().contents());
                }
            }
        }
        List<SpriteContents> sprites = new ArrayList<>();
        for (SpriteContents sprite : south) {
            if (!north.contains(sprite)) {
                return null;
            }
            if (!sprites.contains(sprite)) {
                sprites.add(sprite);
            }
        }
        for (SpriteContents sprite : north) {
            if (!sprites.contains(sprite)) {
                return null;
            }
        }
        return sprites.isEmpty() ? null : sprites;
    }

    /** Whether the quad lies within the slab {@code ItemModelGenerator} bakes into (z 7.5-8.5 sixteenths). */
    private static boolean inGeneratedSlab(BakedQuad quad) {
        for (int v = 0; v < BakedQuad.VERTEX_COUNT; v++) {
            float z = quad.position(v).z();
            if (z < GENERATED_MIN_Z - TOUCH_EPSILON || z > GENERATED_MAX_Z + TOUCH_EPSILON) {
                return false;
            }
        }
        return true;
    }

    /** The unique animation frames of all {@code sprites}, each read per cell of a grid. */
    private static int frameCount(List<SpriteContents> sprites) {
        int frames = 0;
        for (SpriteContents sprite : sprites) {
            frames += sprite.isAnimated() ? sprite.getUniqueFrames().size() : 1;
        }
        return frames;
    }

    /**
     * A face over a sprite's whole canvas showing the whole sprite, as {@code ItemModelGenerator} bakes
     * the front and back: u rising with x, v falling with y on both (the back's UVs mirror its default
     * ones, so each texel is behind itself).
     */
    private static boolean isCanvasFace(BakedQuad quad) {
        if (quad.direction() != Direction.NORTH && quad.direction() != Direction.SOUTH) {
            return false;
        }
        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
        for (int v = 0; v < BakedQuad.VERTEX_COUNT; v++) {
            Vector3fc p = quad.position(v);
            long uv = quad.packedUV(v);
            float u = (UVPair.unpackU(uv) - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
            float w = (UVPair.unpackV(uv) - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
            if (!(Math.abs(u - p.x()) <= UV_EPSILON && Math.abs(w - (1f - p.y())) <= UV_EPSILON)) {
                return false;
            }
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        return minX <= TOUCH_EPSILON && minY <= TOUCH_EPSILON && maxX >= 1f - TOUCH_EPSILON && maxY >= 1f - TOUCH_EPSILON;
    }

    /** The quads of all layers whose texture shows something in some animation frame; null if too many to measure. */
    private static @Nullable List<BakedQuad> visibleQuads(ItemStackRenderStateAccessor item) {
        return visibleQuads(item, null);
    }

    /** As {@link #visibleQuads(ItemStackRenderStateAccessor)}, leaving out quads whose {@link #signature} is in {@code skip}. */
    private static @Nullable List<BakedQuad> visibleQuads(ItemStackRenderStateAccessor item, @Nullable LongOpenHashSet skip) {
        List<BakedQuad> visible = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < item.fishingrodfix$getActiveLayerCount(); i++) {
            List<BakedQuad> all = ((LayerRenderStateAccessor) item.fishingrodfix$getLayers()[i]).fishingrodfix$getQuads().all();
            total += all.size();
            if (total > MAX_QUADS) {
                return null;
            }
            for (BakedQuad quad : all) {
                if ((skip == null || !skip.contains(signature(quad))) && isVisible(quad)) {
                    visible.add(quad);
                }
            }
        }
        return visible;
    }

    /**
     * A quad's corners (quantised to TOUCH_EPSILON), texture coordinates and sprite, hashed: an uncast
     * quad drawn exactly as a cast one is can't draw anything only the uncast model has.
     */
    private static long signature(BakedQuad quad) {
        long h = System.identityHashCode(quad.materialInfo().sprite().contents());
        for (int v = 0; v < BakedQuad.VERTEX_COUNT; v++) {
            Vector3fc p = quad.position(v);
            h = 31 * h + Math.round(p.x() / TOUCH_EPSILON);
            h = 31 * h + Math.round(p.y() / TOUCH_EPSILON);
            h = 31 * h + Math.round(p.z() / TOUCH_EPSILON);
            h = 31 * h + quad.packedUV(v);
        }
        return h;
    }

    /**
     * Whether a texel under the quad's texture rectangle passes the cutout in some animation frame; a
     * rectangle larger than FACE_SAMPLES texels is read on an even grid of that many.
     */
    private static boolean isVisible(BakedQuad quad) {
        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        SpriteContents contents = sprite.contents();
        float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        for (int v = 0; v < BakedQuad.VERTEX_COUNT; v++) {
            long uv = quad.packedUV(v);
            float x = (UVPair.unpackU(uv) - sprite.getU0()) / (sprite.getU1() - sprite.getU0()) * contents.width();
            float y = (UVPair.unpackV(uv) - sprite.getV0()) / (sprite.getV1() - sprite.getV0()) * contents.height();
            minU = Math.min(minU, x);
            maxU = Math.max(maxU, x);
            minV = Math.min(minV, y);
            maxV = Math.max(maxV, y);
        }
        // A degenerate rectangle (a zero-width strip) still samples the texel it sits on.
        int x0 = clamp((int) Math.floor(minU + TOUCH_EPSILON), contents.width());
        int x1 = Math.max(x0 + 1, Math.min((int) Math.ceil(maxU - TOUCH_EPSILON), contents.width()));
        int y0 = clamp((int) Math.floor(minV + TOUCH_EPSILON), contents.height());
        int y1 = Math.max(y0 + 1, Math.min((int) Math.ceil(maxV - TOUCH_EPSILON), contents.height()));
        int side = (int) Math.sqrt(FACE_SAMPLES);
        int stepX = Math.max(1, (x1 - x0 + side - 1) / side);
        int stepY = Math.max(1, (y1 - y0 + side - 1) / side);
        Frames frames = Frames.of(contents);
        for (int y = y0; y < y1; y += stepY) {
            for (int x = x0; x < x1; x += stepX) {
                if (frames.visible(x, y, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Charges {@code n} steps to the measurement's budget. */
    private static void spend(int n) {
        samplesLeft -= n;
        if (samplesLeft < 0) {
            throw OverBudget.INSTANCE;
        }
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(value, size - 1));
    }

    /** Thrown (without a stack trace) when a measurement runs out of texel reads. */
    private static final class OverBudget extends RuntimeException {
        static final OverBudget INSTANCE = new OverBudget();

        private OverBudget() {
            super(null, null, false, false);
        }
    }

    /** A sprite's pixels as the item pipelines show them: alpha at or above the cutout. */
    private record Frames(NativeImage image, int width, int height, int framesPerRow, IntList frames) {
        static Frames of(SpriteContents sprite) {
            NativeImage image = ((SpriteContentsAccessor) sprite).fishingrodfix$getOriginalImage();
            // Frames are frame-sized tiles laid out row-major (SpriteContents.AnimatedTexture.getFrameX/Y);
            // a static sprite shows tile 0 and its getUniqueFrames() is a dummy [1].
            IntList frames = sprite.isAnimated() ? sprite.getUniqueFrames() : IntList.of(0);
            return new Frames(image, sprite.width(), sprite.height(), Math.max(1, image.getWidth() / sprite.width()), frames);
        }

        /**
         * Whether texel {@code (x, y)} is shown: in most animation frames ({@code steady}: the rod
         * itself, not an effect flickering over it, like FurfSky's phantom rod's wisp flying along the
         * line), else in any frame (the uncast model's drawn line, which may itself be animated).
         */
        boolean visible(int x, int y, boolean steady) {
            float minAlpha = ITEM_ALPHA_CUTOUT * 255f;
            int count = frames.size();
            int need = steady ? count / 2 + 1 : 1;
            int shown = 0;
            for (int i = 0; i < count; i++) {
                if (--samplesLeft < 0) {
                    throw OverBudget.INSTANCE;
                }
                int frame = frames.getInt(i);
                if (ARGB.alpha(image.getPixel((frame % framesPerRow) * width + x, (frame / framesPerRow) * height + y)) >= minAlpha) {
                    if (++shown >= need) {
                        return true;
                    }
                } else if (shown + count - i - 1 < need) {
                    return false;
                }
            }
            return false;
        }

        /**
         * Both of {@link #visible}'s answers for texel {@code (x, y)} in one read: {@link #STEADY}
         * (with {@link #ANY}) when shown in most frames, {@link #ANY} when shown in some, else 0.
         */
        int shown(int x, int y) {
            float minAlpha = ITEM_ALPHA_CUTOUT * 255f;
            int count = frames.size();
            int need = count / 2 + 1;
            int shown = 0;
            for (int i = 0; i < count; i++) {
                if (--samplesLeft < 0) {
                    throw OverBudget.INSTANCE;
                }
                int frame = frames.getInt(i);
                if (ARGB.alpha(image.getPixel((frame % framesPerRow) * width + x, (frame / framesPerRow) * height + y)) >= minAlpha) {
                    if (++shown >= need) {
                        return ANY | STEADY;
                    }
                }
                if (shown > 0 && shown + count - i - 1 < need) {
                    return ANY;
                }
            }
            return shown > 0 ? ANY : 0;
        }
    }

    /**
     * A generated rod's visible pixels, the union of its layers' sprites (which share one canvas), on a
     * grid of {@code stride}-texel cells, each shown by its middle texel (1: every texel):
     * {@code visible} as {@code steady} asks (see {@link Frames#visible}), {@code anyFrame} in any
     * animation frame (the same array unless {@code steady}).
     */
    private record PixelGrid(int width, int height, int stride, int columns, int rows, boolean steady, boolean[] visible, boolean[] anyFrame,
                             List<Frames> sprites, int[] scratchA, int[] scratchB, int[] scratchC) {
        private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
        private static final int[] DY = {-1, -1, -1, 0, 0, 1, 1, 1};

        /**
         * The grid of {@code sprites}, at {@code minStride} or coarser: at most GRID_CELLS cells, and
         * cells times {@code frames} (all animation frames a measurement reads per cell, over both
         * grids) within GRID_BUDGET. Null if the canvases differ or even one cell wouldn't fit.
         */
        static @Nullable PixelGrid of(List<SpriteContents> sprites, int minStride, int frames, boolean steady) {
            int width = sprites.getFirst().width();
            int height = sprites.getFirst().height();
            List<Frames> spriteFrames = new ArrayList<>();
            for (SpriteContents sprite : sprites) {
                if (sprite.width() != width || sprite.height() != height) {
                    return null;
                }
                spriteFrames.add(Frames.of(sprite));
            }
            if (frames > GRID_BUDGET) {
                return null;
            }
            int stride = minStride;
            while ((long) ceilDiv(width, stride) * ceilDiv(height, stride) > GRID_CELLS
                    || (long) ceilDiv(width, stride) * ceilDiv(height, stride) * frames > GRID_BUDGET) {
                stride++;
            }
            int columns = ceilDiv(width, stride), rows = ceilDiv(height, stride);
            int cells = columns * rows;
            boolean[] visible = new boolean[cells];
            boolean[] anyFrame = steady ? new boolean[cells] : visible;
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    int x = Math.min(column * stride + stride / 2, width - 1), y = Math.min(row * stride + stride / 2, height - 1);
                    int i = row * columns + column;
                    if (steady) {
                        int shown = 0;
                        for (Frames f : spriteFrames) {
                            shown |= f.shown(x, y);
                            if ((shown & STEADY) != 0) {
                                break;
                            }
                        }
                        visible[i] = (shown & STEADY) != 0;
                        anyFrame[i] = shown != 0;
                    } else {
                        for (Frames f : spriteFrames) {
                            if (f.visible(x, y, false)) {
                                visible[i] = true;
                                break;
                            }
                        }
                    }
                }
            }
            return new PixelGrid(width, height, stride, columns, rows, steady, visible, anyFrame, spriteFrames,
                    new int[cells], new int[cells], new int[cells]);
        }

        private static int ceilDiv(int a, int b) {
            return (a + b - 1) / b;
        }

        /**
         * The end texel {@code {x, y}} of the rod where the cells only {@code uncast} shows (its line)
         * touch it, or null when they touch nothing (or the grids differ).
         */
        int @Nullable [] lineEnd(@Nullable PixelGrid uncast) {
            if (uncast == null || uncast.width != width || uncast.height != height || uncast.stride != stride) {
                return null;
            }
            IntArrayList contacts = new IntArrayList();
            for (int i = 0; i < visible.length; i++) {
                if (visible[i] && touchesLine(i, uncast)) {
                    contacts.add(i);
                }
            }
            if (contacts.isEmpty()) {
                return null;
            }
            // The rod: the largest piece the line touches (a speck beside the line is not it).
            int seed = -1, seedSize = 0;
            boolean[] seen = new boolean[visible.length];
            for (int i = 0; i < contacts.size(); i++) {
                int c = contacts.getInt(i);
                if (seen[c]) {
                    continue;
                }
                int[] reach = steps(c, scratchA);
                int size = 0;
                for (int j = 0; j < reach.length; j++) {
                    if (reach[j] >= 0) {
                        seen[j] = true;
                        size++;
                    }
                }
                if (size > seedSize) {
                    seed = c;
                    seedSize = size;
                }
            }
            int[] fromContact = steps(seed, scratchA);
            int a = farthest(fromContact);
            int[] fromA = steps(a, scratchB);
            int b = farthest(fromA);
            // The end nearer, along the rod, to the contacts on this piece of it.
            IntArrayList onPiece = new IntArrayList();
            for (int i = 0; i < contacts.size(); i++) {
                if (fromContact[contacts.getInt(i)] >= 0) {
                    onPiece.add(contacts.getInt(i));
                }
            }
            int[] fromB = steps(b, scratchC);
            // The end a contact is nearest to (a line wound down to the reel also touches the rod
            // lower down); if both ends touch the line as closely, the end nearer the contacts overall.
            long nearA = Long.MAX_VALUE, nearB = Long.MAX_VALUE, toA = 0, toB = 0;
            for (int i = 0; i < onPiece.size(); i++) {
                int c = onPiece.getInt(i);
                nearA = Math.min(nearA, fromA[c]);
                nearB = Math.min(nearB, fromB[c]);
                toA += fromA[c];
                toB += fromB[c];
            }
            boolean atA = nearA != nearB ? nearA < nearB : toA <= toB;
            int[] fromEnd = atA ? fromA : fromB;
            int[] fromOther = atA ? fromB : fromA;
            int other = atA ? b : a;
            // Where the drawn line meets the rod at that end: the contact nearest it along the rod
            // (ties: the one farther from the other end).
            int attach = -1;
            for (int i = 0; i < onPiece.size(); i++) {
                int c = onPiece.getInt(i);
                if (attach < 0 || fromEnd[c] < fromEnd[attach]
                        || fromEnd[c] == fromEnd[attach] && cellDistanceSquared(c, other) > cellDistanceSquared(attach, other)) {
                    attach = c;
                }
            }
            // The rod's extreme tip at that end: of the cells most steps from the other end (with
            // diagonal steps a whole corner is equally far), the one farthest in a straight line.
            int steps = fromOther[farthest(fromOther)];
            int tip = -1;
            for (int i = 0; i < visible.length; i++) {
                if (fromOther[i] == steps && (tip < 0 || cellDistanceSquared(i, other) > cellDistanceSquared(tip, other))) {
                    tip = i;
                }
            }
            float tx = tip % columns - attach % columns, ty = tip / columns - attach / columns;
            float ax = attach % columns - other % columns, ay = attach / columns - other / columns;
            boolean decorated = tx * ax + ty * ay < DECORATED_TIP_COS * (float) Math.sqrt((tx * tx + ty * ty) * (ax * ax + ay * ay));
            return refine(decorated ? attach : tip, (other % columns + 0.5f) * stride, (other / columns + 0.5f) * stride, true);
        }

        private float cellDistanceSquared(int i, int j) {
            float dx = i % columns - j % columns, dy = i / columns - j / columns;
            return dx * dx + dy * dy;
        }

        /**
         * The texel of cell {@code cell} (or of its neighbours, whose middle texels were all a coarse
         * cell showed) that is farthest ({@code far}) or nearest from texel point {@code (px, py)}.
         * Exact cells (stride 1) are their own texel.
         */
        private int[] refine(int cell, float px, float py, boolean far) {
            int cx = cell % columns, cy = cell / columns;
            if (stride == 1) {
                return new int[] {cx, cy};
            }
            int[] best = {Math.min(cx * stride + stride / 2, width - 1), Math.min(cy * stride + stride / 2, height - 1)};
            // Coarse pass over the cell and its neighbours, then every texel around its pick.
            int step = Math.max(1, (3 * stride + REFINE_SIDE - 1) / REFINE_SIDE);
            best = pick(best, Math.max(0, (cx - 1) * stride), Math.min(width, (cx + 2) * stride),
                    Math.max(0, (cy - 1) * stride), Math.min(height, (cy + 2) * stride), step, px, py, far);
            return step == 1 ? best : pick(best, Math.max(0, best[0] - step), Math.min(width, best[0] + step + 1),
                    Math.max(0, best[1] - step), Math.min(height, best[1] + step + 1), 1, px, py, far);
        }

        /** Of the visible texels in {@code [x0, x1) x [y0, y1)} on a {@code step} grid, the farthest (or nearest) from {@code (px, py)}; else {@code fallback}. */
        private int[] pick(int[] fallback, int x0, int x1, int y0, int y1, int step, float px, float py, boolean far) {
            int[] best = fallback;
            float bestDistance = far ? -1f : Float.MAX_VALUE;
            for (int y = y0; y < y1; y += step) {
                for (int x = x0; x < x1; x += step) {
                    if (!texelVisible(x, y)) {
                        continue;
                    }
                    float dx = x + 0.5f - px, dy = y + 0.5f - py, d = dx * dx + dy * dy;
                    if (far ? d > bestDistance : d < bestDistance) {
                        bestDistance = d;
                        best = new int[] {x, y};
                    }
                }
            }
            return best;
        }

        private boolean texelVisible(int x, int y) {
            for (Frames f : sprites) {
                if (f.visible(x, y, steady)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether a cell of the drawn line neighbours cell {@code i}: one the uncast rod shows in any
         * animation frame and this (cast) rod in none.
         */
        private boolean touchesLine(int i, PixelGrid uncast) {
            int x = i % columns, y = i / columns;
            for (int d = 0; d < 8; d++) {
                int nx = x + DX[d], ny = y + DY[d];
                if (nx >= 0 && ny >= 0 && nx < columns && ny < rows) {
                    int n = ny * columns + nx;
                    if (uncast.anyFrame[n] && !anyFrame[n]) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The texel farthest from texture point {@code (u, v)} (0-1) among the cells connected to the
         * one nearest it; null if nothing is visible.
         */
        int @Nullable [] farthestFrom(float u, float v) {
            float px = u * width, py = v * height;
            int seed = -1;
            float best = Float.MAX_VALUE;
            for (int i = 0; i < visible.length; i++) {
                if (visible[i] && distanceSquared(i, px, py) < best) {
                    best = distanceSquared(i, px, py);
                    seed = i;
                }
            }
            if (seed < 0) {
                return null;
            }
            int[] reach = steps(seed, scratchA);
            int far = -1;
            best = -1f;
            for (int i = 0; i < visible.length; i++) {
                if (reach[i] >= 0 && distanceSquared(i, px, py) > best) {
                    best = distanceSquared(i, px, py);
                    far = i;
                }
            }
            return refine(far, px, py, true);
        }

        private float distanceSquared(int i, float px, float py) {
            float dx = (i % columns + 0.5f) * stride - px, dy = (i / columns + 0.5f) * stride - py;
            return dx * dx + dy * dy;
        }

        /** Steps from {@code start} to every visible cell connected to it (8-neighbourhood), into {@code dist}; -1 where unreached. */
        private int[] steps(int start, int[] dist) {
            Arrays.fill(dist, -1);
            IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
            dist[start] = 0;
            queue.enqueue(start);
            while (!queue.isEmpty()) {
                int i = queue.dequeueInt();
                int x = i % columns, y = i / columns;
                for (int d = 0; d < 8; d++) {
                    int nx = x + DX[d], ny = y + DY[d];
                    if (nx >= 0 && ny >= 0 && nx < columns && ny < rows) {
                        int n = ny * columns + nx;
                        if (visible[n] && dist[n] < 0) {
                            dist[n] = dist[i] + 1;
                            queue.enqueue(n);
                        }
                    }
                }
            }
            return dist;
        }

        private static int farthest(int[] dist) {
            int far = -1;
            for (int i = 0; i < dist.length; i++) {
                if (dist[i] >= 0 && (far < 0 || dist[i] > dist[far])) {
                    far = i;
                }
            }
            return far;
        }
    }

    /**
     * A 3D model's visible texel cells: every face split into cells a texel of a 16x model a side (a
     * model's elements are laid out in sixteenths of a block), each a box around its part of the
     * (possibly rotated) face, kept where its part of the texture shows a texel. Only what is drawn
     * counts, as on a sprite: a mostly transparent face (a plane a rod's bend or line is painted on,
     * mb3d-items-pack) is only its painted texels.
     */
    private static final class Cells {
        // Per cell: min x, y, z, max x, y, z.
        final FloatArrayList boxes = new FloatArrayList();
        // Of steady cells: the keys of all cells shown in any animation frame (steady or not); else null.
        @Nullable LongOpenHashSet anyFrame;

        int size() {
            return boxes.size() / 6;
        }

        /** The cells of {@code quads} shown as {@code steady} asks (see {@link Frames#visible}). */
        static Cells of(List<BakedQuad> quads, boolean steady) {
            Cells cells = new Cells();
            if (steady) {
                cells.anyFrame = new LongOpenHashSet();
            }
            for (BakedQuad quad : quads) {
                cells.addFace(quad, steady);
            }
            return cells;
        }

        private void addFace(BakedQuad quad, boolean steady) {
            TextureAtlasSprite sprite = quad.materialInfo().sprite();
            SpriteContents contents = sprite.contents();
            Frames frames = Frames.of(contents);
            float[] px = new float[4], py = new float[4], pz = new float[4], tu = new float[4], tv = new float[4];
            for (int v = 0; v < 4; v++) {
                Vector3fc p = quad.position(v);
                px[v] = p.x();
                py[v] = p.y();
                pz[v] = p.z();
                long uv = quad.packedUV(v);
                tu[v] = (UVPair.unpackU(uv) - sprite.getU0()) / (sprite.getU1() - sprite.getU0()) * contents.width();
                tv[v] = (UVPair.unpackV(uv) - sprite.getV0()) / (sprite.getV1() - sprite.getV0()) * contents.height();
            }
            int nu = cellsAlong(distance(px, py, pz, 0, 1), distance(px, py, pz, 3, 2));
            int nv = cellsAlong(distance(px, py, pz, 0, 3), distance(px, py, pz, 1, 2));
            for (int i = 0; i < nu; i++) {
                for (int j = 0; j < nv; j++) {
                    spend(1);
                    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                    float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                    float minU = Float.MAX_VALUE, minV = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
                    for (int k = 0; k < 4; k++) {
                        float s = (float) (i + (k == 1 || k == 2 ? 1 : 0)) / nu;
                        float t = (float) (j + (k >= 2 ? 1 : 0)) / nv;
                        float x = lerp(px, s, t), y = lerp(py, s, t), z = lerp(pz, s, t);
                        float u = lerp(tu, s, t), w = lerp(tv, s, t);
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                        minU = Math.min(minU, u);
                        maxU = Math.max(maxU, u);
                        minV = Math.min(minV, w);
                        maxV = Math.max(maxV, w);
                    }
                    int shown = shows(frames, contents, minU, maxU, minV, maxV, steady);
                    if ((shown & (steady ? STEADY : ANY)) != 0) {
                        boxes.add(minX);
                        boxes.add(minY);
                        boxes.add(minZ);
                        boxes.add(maxX);
                        boxes.add(maxY);
                        boxes.add(maxZ);
                    }
                    if (anyFrame != null && shown != 0) {
                        anyFrame.add(key(minX, minY, minZ, maxX, maxY, maxZ));
                    }
                }
            }
        }

        /**
         * Whether a texel of the texture rectangle passes the cutout, read on at most CELL_SAMPLES
         * texels: {@link #ANY} in some animation frame, with {@link #STEADY} (read only if
         * {@code steady}) in most.
         */
        private static int shows(Frames frames, SpriteContents contents, float minU, float maxU, float minV, float maxV, boolean steady) {
            // A degenerate rectangle (a zero-width strip) still samples the texel it sits on.
            int x0 = clamp((int) Math.floor(minU + TOUCH_EPSILON), contents.width());
            int x1 = Math.max(x0 + 1, Math.min((int) Math.ceil(maxU - TOUCH_EPSILON), contents.width()));
            int y0 = clamp((int) Math.floor(minV + TOUCH_EPSILON), contents.height());
            int y1 = Math.max(y0 + 1, Math.min((int) Math.ceil(maxV - TOUCH_EPSILON), contents.height()));
            int side = (int) Math.sqrt(CELL_SAMPLES);
            int stepX = Math.max(1, (x1 - x0 + side - 1) / side);
            int stepY = Math.max(1, (y1 - y0 + side - 1) / side);
            int shown = 0;
            for (int y = y0; y < y1; y += stepY) {
                for (int x = x0; x < x1; x += stepX) {
                    if (steady) {
                        shown |= frames.shown(x, y);
                        if ((shown & STEADY) != 0) {
                            return shown;
                        }
                    } else if (frames.visible(x, y, false)) {
                        return ANY;
                    }
                }
            }
            return shown;
        }

        private static float distance(float[] x, float[] y, float[] z, int a, int b) {
            float dx = x[b] - x[a], dy = y[b] - y[a], dz = z[b] - z[a];
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private static int cellsAlong(float first, float second) {
            return Math.max(1, (int) Math.ceil(Math.max(first, second) / CELL - 1e-4f));
        }

        /** The corners' values 0, 1, 2, 3 (around the face) at {@code s} along 0-1 and {@code t} along 0-3. */
        private static float lerp(float[] corner, float s, float t) {
            float top = corner[0] + (corner[1] - corner[0]) * s;
            float bottom = corner[3] + (corner[2] - corner[3]) * s;
            return top + (bottom - top) * t;
        }

        /**
         * These cells without any at the place of one of {@code other}'s, of steady {@code other}
         * those shown in any animation frame (the drawn line: the uncast model's cells the cast one
         * shows in none of its frames).
         */
        Cells without(Cells other) {
            LongOpenHashSet taken = other.anyFrame;
            if (taken == null) {
                taken = new LongOpenHashSet(other.size());
                for (int c = 0; c < other.size(); c++) {
                    taken.add(other.key(c));
                }
            }
            Cells rest = new Cells();
            for (int c = 0; c < size(); c++) {
                if (!taken.contains(key(c))) {
                    for (int k = 0; k < 6; k++) {
                        rest.boxes.add(boxes.getFloat(c * 6 + k));
                    }
                }
            }
            return rest;
        }

        private long key(int c) {
            int o = c * 6;
            return key(boxes.getFloat(o), boxes.getFloat(o + 1), boxes.getFloat(o + 2), boxes.getFloat(o + 3), boxes.getFloat(o + 4), boxes.getFloat(o + 5));
        }

        /** A cell's box, quantised to TOUCH_EPSILON, hashed: the same cell in both models has the same key. */
        private static long key(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            long h = 1125899906842597L;
            h = 31 * h + Math.round(minX / TOUCH_EPSILON);
            h = 31 * h + Math.round(minY / TOUCH_EPSILON);
            h = 31 * h + Math.round(minZ / TOUCH_EPSILON);
            h = 31 * h + Math.round(maxX / TOUCH_EPSILON);
            h = 31 * h + Math.round(maxY / TOUCH_EPSILON);
            return 31 * h + Math.round(maxZ / TOUCH_EPSILON);
        }

        /**
         * The rod's tip: over the largest connected part of these cells (the rod), the end farther from
         * {@code hand} of the farthest pair of cells (a double sweep), the mean of the cells within a
         * texel of that end along the rod. Unless it is a decoration beside the drawn {@code line} (see
         * DECORATED_TIP_COS), then the rod's cell nearest the line's cell nearest that end.
         */
        @Nullable Vector3f tip(@Nullable Cells line, Vector3fc hand) {
            int n = size();
            if (n == 0) {
                return null;
            }
            int[] part = parts();
            int[] count = new int[n];
            int rod = part[0];
            for (int c = 0; c < n; c++) {
                if (++count[part[c]] > count[rod] || count[part[c]] == count[rod] && part[c] < rod) {
                    rod = part[c];
                }
            }
            IntArrayList cells = new IntArrayList();
            for (int c = 0; c < n; c++) {
                if (part[c] == rod) {
                    cells.add(c);
                }
            }
            Vector3f a = farthest(cells, centre(cells.getInt(0), new Vector3f()));
            Vector3f b = farthest(cells, a);
            // A rod is held at one end and cast from the other. A grip about as far from both tells
            // neither (a pack with only pre-1.9 display keys is held by its centre), then the upper end:
            // rods are modelled tip up.
            float toA = a.distanceSquared(hand), toB = b.distanceSquared(hand);
            Vector3f end = Math.max(toA, toB) < GRIP_RATIO * GRIP_RATIO * Math.min(toA, toB) ? (a.y >= b.y ? a : b) : toA >= toB ? a : b;
            Vector3f other = end == a ? b : a;
            Vector3f axis = new Vector3f(end).sub(other);
            if (axis.lengthSquared() < TOUCH_EPSILON * TOUCH_EPSILON) {
                return end;
            }
            axis.normalize();
            Vector3f centre = new Vector3f();
            float reach = -Float.MAX_VALUE;
            for (int i = 0; i < cells.size(); i++) {
                reach = Math.max(reach, centre(cells.getInt(i), centre).dot(axis));
            }
            Vector3f tip = new Vector3f();
            int atTip = 0;
            for (int i = 0; i < cells.size(); i++) {
                if (centre(cells.getInt(i), centre).dot(axis) >= reach - CELL) {
                    tip.add(centre);
                    atTip++;
                }
            }
            tip.div(atTip);
            if (line == null || line.size() == 0) {
                return tip;
            }
            IntArrayList all = new IntArrayList(line.size());
            for (int c = 0; c < line.size(); c++) {
                all.add(c);
            }
            Vector3f lineCell = line.centre(line.nearest(all, end), new Vector3f());
            Vector3f attach = centre(nearest(cells, lineCell), new Vector3f());
            Vector3f toTip = new Vector3f(tip).sub(attach);
            Vector3f along = new Vector3f(attach).sub(other);
            boolean decorated = toTip.lengthSquared() > 0f && along.lengthSquared() > 0f
                    && toTip.dot(along) < DECORATED_TIP_COS * toTip.length() * along.length();
            return decorated ? attach : tip;
        }

        /** The connected part of each cell, as a representative cell; cells connect where their boxes touch or overlap. */
        private int[] parts() {
            int n = size();
            int[] parent = new int[n];
            // Cells sharing a bucket are joined, without comparing pairs. Buckets are half a texel a side
            // and offset by half a bucket, so a model's texel-aligned coordinates fall inside one: cells
            // that touch (a box grown by TOUCH_EPSILON reaches the other) share a bucket, cells half a
            // texel or more apart don't, and closer than that counts as touching.
            Long2IntOpenHashMap first = new Long2IntOpenHashMap();
            first.defaultReturnValue(-1);
            for (int c = 0; c < n; c++) {
                parent[c] = c;
                int x0 = bucket(c, 0, -TOUCH_EPSILON), x1 = bucket(c, 3, TOUCH_EPSILON);
                int y0 = bucket(c, 1, -TOUCH_EPSILON), y1 = bucket(c, 4, TOUCH_EPSILON);
                int z0 = bucket(c, 2, -TOUCH_EPSILON), z1 = bucket(c, 5, TOUCH_EPSILON);
                for (int x = x0; x <= x1; x++) {
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            spend(1);
                            long key = ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
                            int other = first.putIfAbsent(key, c);
                            if (other >= 0) {
                                int ra = root(parent, c), rb = root(parent, other);
                                if (ra != rb) {
                                    parent[Math.max(ra, rb)] = Math.min(ra, rb);
                                }
                            }
                        }
                    }
                }
            }
            for (int c = 0; c < n; c++) {
                parent[c] = root(parent, c);
            }
            return parent;
        }

        private int bucket(int c, int k, float grow) {
            return (int) Math.floor((boxes.getFloat(c * 6 + k) + grow) / BUCKET - 0.5f);
        }

        private static int root(int[] parent, int c) {
            while (parent[c] != c) {
                parent[c] = parent[parent[c]];
                c = parent[c];
            }
            return c;
        }


        private Vector3f centre(int c, Vector3f dest) {
            return dest.set(boxes.getFloat(c * 6) + boxes.getFloat(c * 6 + 3),
                    boxes.getFloat(c * 6 + 1) + boxes.getFloat(c * 6 + 4),
                    boxes.getFloat(c * 6 + 2) + boxes.getFloat(c * 6 + 5)).mul(0.5f);
        }

        /** The centre of the cell among {@code cells} farthest from {@code from}. */
        private Vector3f farthest(IntArrayList cells, Vector3fc from) {
            Vector3f far = new Vector3f(), centre = new Vector3f();
            float best = -1f;
            for (int i = 0; i < cells.size(); i++) {
                float d = centre(cells.getInt(i), centre).distanceSquared(from);
                if (d > best) {
                    best = d;
                    far.set(centre);
                }
            }
            return far;
        }

        /** The cell among {@code cells} whose box is nearest {@code p} (the first one on a tie). */
        private int nearest(IntArrayList cells, Vector3fc p) {
            int near = cells.getInt(0);
            float best = Float.MAX_VALUE;
            for (int i = 0; i < cells.size(); i++) {
                int c = cells.getInt(i);
                float dx = Math.max(0f, Math.max(boxes.getFloat(c * 6) - p.x(), p.x() - boxes.getFloat(c * 6 + 3)));
                float dy = Math.max(0f, Math.max(boxes.getFloat(c * 6 + 1) - p.y(), p.y() - boxes.getFloat(c * 6 + 4)));
                float dz = Math.max(0f, Math.max(boxes.getFloat(c * 6 + 2) - p.z(), p.z() - boxes.getFloat(c * 6 + 5)));
                float d = dx * dx + dy * dy + dz * dz;
                if (d < best) {
                    best = d;
                    near = c;
                }
            }
            return near;
        }
    }

    private static final class CacheEntry {
        final WeakReference<ItemQuads> quads;
        final Matrix4fc grip;
        final @Nullable Vector3fc tip;
        long lastUsed;

        CacheEntry(WeakReference<ItemQuads> quads, Matrix4fc grip, @Nullable Vector3fc tip, long lastUsed) {
            this.quads = quads;
            this.grip = grip;
            this.tip = tip;
            this.lastUsed = lastUsed;
        }
    }
}
