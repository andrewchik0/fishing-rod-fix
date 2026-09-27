package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.ItemStackRenderStateAccessor;
import com.andrewchik.fishingrodfix.mixin.client.LayerRenderStateAccessor;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;

/**
 * What decides where a drawn item's point lands in the space it is submitted in: its baked model (by
 * identity), its display transform (by identity: one per model and context), the hand side and the
 * item definition's transform. A cache key that compares without allocating. Render-thread only.
 */
final class DrawnItem {
    private WeakReference<ItemQuads> quads = new WeakReference<>(null);
    private @Nullable ItemTransform transform;
    private boolean leftHand;
    private final Matrix4f local = new Matrix4f();

    boolean matches(ItemStackRenderState item, LayerRenderStateAccessor layer) {
        return quads.get() == layer.fishingrodfix$getQuads()
                && transform == layer.fishingrodfix$getItemTransform()
                && leftHand == ((ItemStackRenderStateAccessor) item).fishingrodfix$getDisplayContext().leftHand()
                && local.equals(layer.fishingrodfix$getLocalTransform());
    }

    void set(ItemStackRenderState item, LayerRenderStateAccessor layer) {
        quads = new WeakReference<>(layer.fishingrodfix$getQuads());
        transform = layer.fishingrodfix$getItemTransform();
        leftHand = ((ItemStackRenderStateAccessor) item).fishingrodfix$getDisplayContext().leftHand();
        local.set(layer.fishingrodfix$getLocalTransform());
    }
}
