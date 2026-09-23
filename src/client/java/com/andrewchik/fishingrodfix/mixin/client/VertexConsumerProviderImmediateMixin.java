package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.SequencedMap;

/**
 * Ends a pass for {@link ThirdPersonLineOrigin} when a buffer source flushes everything it holds, next
 * to {@link WorldRendererMixin}'s mark at the end of the world's entity loop: on 1.21.1 nothing is
 * deferred, and the {@code BufferBuilderStorage}'s one entity {@code VertexConsumerProvider.Immediate}
 * takes the world's entities and block entities, the first-person hand pass, the screen effects and
 * the GUI's pictures (the inventory's player model, banners, signs, books), each ending with a
 * {@code draw()}: {@code OutlineVertexConsumerProvider.draw()} (its own outline source) and the
 * entity-pass flushes inside {@code WorldRenderer.render}, {@code HeldItemRenderer.renderItem} at the
 * end of the hand, and {@code DrawContext.draw()} around the GUI's own drawing (once or twice per
 * drawn item and once per scissor change, so a frame with a container screen open flushes far more
 * often than a world frame; {@code InventoryScreen.drawEntity} brackets the player model with it). So
 * a {@code draw()} ends a pass, as a queue clear does on the deferred versions, and Iris' shadow pass
 * ends its own the same way. Nothing inside the world's entity loop calls it, so a rod and a hook
 * drawn in the same loop stay in the same pass; {@code drawCurrentLayer()} and
 * {@code draw(RenderLayer)} are not counted here (they flush one layer, also from {@code getBuffer},
 * in the middle of a pass — the one that closes the entity loop is marked by
 * {@link WorldRendererMixin} at its call site instead). Hooked at the only read in {@code draw()} (its
 * {@code layerBuffers}), which returns the map unchanged and allocates nothing. Optional
 * ({@code require = 0}): with neither mark, no pass is ever matched and every body-held line keeps
 * vanilla's value.
 */
@Mixin(VertexConsumerProvider.Immediate.class)
public class VertexConsumerProviderImmediateMixin {
    @ModifyExpressionValue(
        method = "draw()V",
        at = @At(value = "FIELD",
                target = "Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;layerBuffers:Ljava/util/SequencedMap;"),
        require = 0
    )
    private SequencedMap<RenderLayer, BufferAllocator> fishingrodfix$endPass(SequencedMap<RenderLayer, BufferAllocator> layerBuffers) {
        ThirdPersonLineOrigin.onPassEnded();
        return layerBuffers;
    }
}
