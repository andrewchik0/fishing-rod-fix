package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ends a pass for {@link ThirdPersonLineOrigin} when its submissions are cleared: on 1.21.11 one queue
 * (the {@code GameRenderer}'s) serves the world's main pass and its particles, the first-person hand
 * pass, the screen effects and the GUI's pictures (the inventory's player model, banners, oversized
 * items) and item atlas, each drawn and then cleared by {@code RenderDispatcher.render} (which
 * {@code renderHand} also calls before the hand): four clears per frame in a world, five with the hand
 * drawn, plus one per such picture in the GUI and one per item {@code GuiRenderer.prepareItemInitially}
 * draws into the atlas (each newly shown item, and every animated or glinting one each frame); Iris'
 * shadow pass clears its own queue the same way. Hooked at the only read in {@code clear()} (its
 * {@code batchingQueues}), which returns the map unchanged and allocates nothing. Optional
 * ({@code require = 0}): without it a rod drawn after its hook in the same frame isn't remembered.
 */
@Mixin(OrderedRenderCommandQueueImpl.class)
public class OrderedRenderCommandQueueImplMixin {
    @ModifyExpressionValue(
        method = "clear()V",
        at = @At(value = "FIELD",
                target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueueImpl;batchingQueues:Lit/unimi/dsi/fastutil/ints/Int2ObjectAVLTreeMap;"),
        require = 0
    )
    private Int2ObjectAVLTreeMap<BatchingRenderCommandQueue> fishingrodfix$endPass(Int2ObjectAVLTreeMap<BatchingRenderCommandQueue> batchingQueues) {
        ThirdPersonLineOrigin.onSubmitsCleared();
        return batchingQueues;
    }
}
