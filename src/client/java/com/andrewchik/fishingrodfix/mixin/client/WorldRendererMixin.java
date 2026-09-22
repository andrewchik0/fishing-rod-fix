package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The world's entity pass for {@link ThirdPersonLineOrigin}: the order its entities are drawn in, and
 * where the pass ends. Both injects are optional ({@code require = 0}) and both sit on
 * {@code renderEntities}, the last named method before the loop that draws the entities.
 *
 * <p>The head one opens the pass and settles the order. Opening it — recording the pass this entity
 * loop draws in — is what ties a rod and its hook together when a mod hands each entity its own
 * buffer source, which Iris with a shader pack does ({@link ThirdPersonLineOrigin}'s Javadoc); without
 * the inject nothing is ever inside a loop and the pass match is the collector's alone, as it was
 * before. Settling the order moves the fishing bobbers behind everything else
 * ({@link ThirdPersonLineOrigin#sortBobbersLast}), so a fishing player's body — and the rod in its
 * hand — is drawn before the hooks it cast. It takes the list rather than wrapping the
 * {@code ENTITY_COMPARATOR} read, whose {@code List.sort} sits in an unnamed lambda of the frame
 * graph's main pass: the list is what matters, and taking it here runs after any sort, vanilla's or
 * another mod's. Without this the order is an identity hash of the {@code EntityType} objects, a coin
 * toss thrown afresh in every launch; without the inject the line just falls back the way it did
 * before, to the spot the rod was last drawn at.
 *
 * <p>The tail one ends the pass: everything drawn up to there belongs to one pass, so a rod and a hook
 * of the same entity loop are matched while the GUI's pictures — drawn later in the frame into the
 * same buffer source, from the same shared render state — are not. It also closes the head's record,
 * since that only holds while the pass-end count is the one it was taken at.
 * {@link VertexConsumerProviderImmediateMixin} ends a pass too, but only for a buffer source that
 * really runs vanilla's {@code VertexConsumerProvider.Immediate.draw()}: ImmediatelyFast replaces both
 * of vanilla's with its own {@code BatchableBufferSource}, whose {@code draw()} override never calls
 * super, and Iris' and Real Camera's own sources flush their own way. This mark doesn't depend on any
 * of that. With neither mark no pass is ever matched and every body-held line keeps vanilla's value.
 *
 * <p>Priority 1500 puts both after other mods' {@code @Inject}s of the default injector order at the
 * same points. That holds at a head as well as at a return: every injector's target instruction is
 * resolved in one pass over all the mixins, before any of them inserts anything, so a callback
 * applied later — a higher priority — lands nearer that instruction and runs later. For the head that
 * means the last word on the order; for the return it means a body other mods draw there is still
 * part of the entity pass (Freecam's Show Player and Real Camera's classic mode draw the player's
 * body at this very return, after the hooks). What priority can't outrank is
 * {@code @Inject(order = )}: injections are applied by order first and priority second, so an
 * {@code order} above the default 1000 still runs after these. Nothing marks the head as a pass end,
 * so a body drawn there (First Person Model, at the default priority) belongs to the pass that
 * follows it - and, since the head callback applies after theirs, it is also drawn before the pass
 * is recorded, so it is never inside the window; without an Iris shader pack the shared buffer
 * source still ties it to the hooks.
 */
@Mixin(value = WorldRenderer.class, priority = 1500)
public class WorldRendererMixin {
    @Inject(
        method = "renderEntities(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;"
                + "Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/RenderTickCounter;Ljava/util/List;)V",
        at = @At("HEAD"),
        require = 0
    )
    private void fishingrodfix$bobbersLast(MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers, Camera camera,
                                           RenderTickCounter tickCounter, List<Entity> entities, CallbackInfo ci) {
        ThirdPersonLineOrigin.sortBobbersLast(entities);
    }

    @Inject(
        method = "renderEntities(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;"
                + "Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/RenderTickCounter;Ljava/util/List;)V",
        at = @At("TAIL"),
        require = 0
    )
    private void fishingrodfix$endEntityPass(CallbackInfo ci) {
        ThirdPersonLineOrigin.onPassEnded();
    }
}
