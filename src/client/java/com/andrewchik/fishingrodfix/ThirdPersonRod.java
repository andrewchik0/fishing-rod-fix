package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.LayerRenderStateAccessor;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * The rod held in a player's hand: where the line attaches to it, in the space
 * {@code ItemInHandLayer.submitArmWithItem} submits the rod's item in (before the item's own display
 * transform).
 *
 * <p>The attachment point is the drawn rod model's tip ({@link RodModel}), carried through the display
 * transform the item is drawn with, both read from the item's render state. The body pose is the
 * submitted one ({@link ThirdPersonLineOrigin}), not modelled here.
 */
final class ThirdPersonRod {
    // The last few items read and their anchors, one per model, hand side and transform in view (two
    // players fishing with rods in different hands, or different rods, don't evict each other); the
    // least recently read is replaced. Render-thread only.
    private static final int CACHE_SIZE = 32;
    private static final DrawnItem[] keys = new DrawnItem[CACHE_SIZE];
    private static final @Nullable Vector3f[] anchors = new Vector3f[CACHE_SIZE];
    private static final long[] lastUsed = new long[CACHE_SIZE];
    private static long uses;

    static {
        for (int i = 0; i < CACHE_SIZE; i++) {
            keys[i] = new DrawnItem();
            lastUsed[i] = Long.MIN_VALUE;
        }
    }

    private ThirdPersonRod() {}

    /**
     * The line's attachment point in the space {@code item} (the render state of {@code stack}) is
     * submitted in; null when it can't be read as a rod, {@link RodModel#DEFERRED} when it isn't
     * measured yet (then the line isn't taken from this drawing).
     */
    static @Nullable Vector3fc lineAnchor(ItemStackRenderState item, ItemStack stack) {
        LayerRenderStateAccessor layer = RodModel.rodLayer(item);
        if (layer == null) {
            return null;
        }
        int slot = 0;
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (lastUsed[i] != Long.MIN_VALUE && keys[i].matches(item, layer)) {
                lastUsed[i] = ++uses;
                return anchors[i];
            }
            if (lastUsed[i] < lastUsed[slot]) {
                slot = i;
            }
        }
        Matrix4f display = RodModel.display(item, layer);
        Vector3fc tip = RodModel.tip(item, stack, display);
        if (tip == RodModel.DEFERRED) {
            return tip;
        }
        keys[slot].set(item, layer);
        anchors[slot] = tip != null ? display.transformPosition(tip, new Vector3f()) : null;
        lastUsed[slot] = ++uses;
        return anchors[slot];
    }
}
