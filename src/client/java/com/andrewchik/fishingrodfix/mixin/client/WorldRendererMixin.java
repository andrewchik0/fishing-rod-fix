package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;

/**
 * The world's entity pass for {@link ThirdPersonLineOrigin}: the order it draws the entities in, and
 * where the pass ends. Both hooks are optional ({@code require = 0}) and both sit in
 * {@code WorldRenderer.render}, which on 1.20.1 holds the entity loop itself (the method
 * {@code renderEntities} the later branches hook doesn't exist yet).
 *
 * <p>The first opens the pass and settles the order ({@link ThirdPersonLineOrigin#bobbersLast}).
 * Opening it - recording the pass this entity loop draws in - is what ties a rod and its hook together
 * when a mod hands each entity its own buffer source, which Iris with a shader pack does
 * ({@link ThirdPersonLineOrigin}'s Javadoc); without this hook nothing is ever inside a loop and the
 * pass match is the collector's alone, as it was before. Settling the order wraps the loop's iterator
 * so that the
 * fishing bobbers come behind everything else and a fishing player's body - with the rod in its hand -
 * is drawn before the hooks it cast. Vanilla 1.20.1 would not need it: it walks
 * {@code ClientWorld.getEntities()} in the order the client added them, which already puts a body
 * before its hooks, and it never sorts or copies that list. <b>Iris</b> does: its
 * {@code MixinLevelRenderer_EntityListSorting} (priority 999, in
 * {@code iris-batched-entity-rendering.mixins.json}, which is not gated on a shader pack being in use)
 * {@code @WrapOperation}s this very {@code Iterable.iterator()} call and regroups every entity by
 * {@code EntityType} through a {@code HashMap}, and {@code EntityType} inherits
 * {@code Object.hashCode}, so which group comes first is an identity hash - arbitrary and fixed per
 * launch. This modifier reads whatever that call ends up producing: Iris replaces the call itself
 * with a {@code @WrapOperation}, and a {@code @ModifyExpressionValue} takes the value the call
 * leaves however the two mixins are ordered, so it sees and settles whatever order Iris (or anything
 * else that regroups the list) left. Mixin priority would not decide that between two expression
 * modifiers - a handler is inserted right after the instruction, so a <em>higher</em> priority,
 * applied later, lands nearer it and runs first, i.e. innermost - but it does not have to here. The
 * call is the only {@code Iterable.iterator()} in the whole class, so no ordinal is needed.
 *
 * <p>The second ends the world's entity pass: everything drawn up to there belongs to one pass, so a
 * rod and a hook of the same entity loop are matched while the GUI's pictures - drawn later in the
 * frame into the same buffer source - are not. It also closes the first's record, since that only
 * holds while the pass-end count is the one it was taken at. It sits at the first of the method's three
 * {@code checkEmpty(MatrixStack)} calls, the second statement after the loop and five bytes past the
 * {@code Immediate.drawCurrentLayer()} that flushes it. Those five bytes are the point: a mod that
 * draws one more body at the end of the entity loop hangs it on this very {@code checkEmpty} call
 * (Freecam's Show Player, at the default injector priority), and at priority 1500 this callback is
 * inserted nearer the instruction and therefore runs after theirs - so that body stays inside the
 * pass and its rod is remembered, where a mark at the flush would push it into the next pass, which
 * no hook can ever match. Nothing is drawn between the flush and here, so the later mark costs
 * nothing. {@link VertexConsumerProviderImmediateMixin} ends a pass too,
 * but only for a buffer source that really runs vanilla's {@code VertexConsumerProvider.Immediate.draw()}:
 * ImmediatelyFast replaces both of vanilla's with its own {@code BatchableBufferSource}, whose
 * {@code draw()} override never calls super, and Iris' own sources flush their own way. This mark
 * doesn't depend on any of that. With neither mark no pass is ever matched and every body-held line
 * keeps vanilla's value.
 *
 * <p>Priority 1500 also puts the pass end after other mods' {@code @Inject}s of the default injector
 * order at the same call. That holds at an {@code INVOKE} as it does at a head or a return: every
 * injector's target instruction is resolved in one pass over all the mixins, before any of them
 * inserts anything, so a callback applied later - a higher priority - lands nearer that instruction
 * and runs later. A body another mod draws at that same call is therefore still part of the entity
 * pass. What priority can't outrank is {@code @Inject(order = )}: injections are applied by
 * order first and priority second, so an {@code order} above the default 1000 still runs after these.
 * The same priority is what keeps a body other mods draw at this very call inside the window as well as
 * inside the pass: First Person Model hangs its body on this {@code checkEmpty} call at the default
 * priority on versions below 1.21.3, and Freecam's Show Player likewise, so both are drawn before the
 * mark and are matched even where every entity has its own buffer source.
 */
@Mixin(value = WorldRenderer.class, priority = 1500)
public class WorldRendererMixin {
    @ModifyExpressionValue(
        method = "render(Lnet/minecraft/client/util/math/MatrixStack;FJZLnet/minecraft/client/render/Camera;"
                + "Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;"
                + "Lorg/joml/Matrix4f;)V",
        at = @At(value = "INVOKE", target = "Ljava/lang/Iterable;iterator()Ljava/util/Iterator;"),
        require = 0
    )
    private Iterator<Entity> fishingrodfix$bobbersLast(Iterator<Entity> entities) {
        return ThirdPersonLineOrigin.bobbersLast(entities);
    }

    @Inject(
        method = "render(Lnet/minecraft/client/util/math/MatrixStack;FJZLnet/minecraft/client/render/Camera;"
                + "Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;"
                + "Lorg/joml/Matrix4f;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;checkEmpty("
                + "Lnet/minecraft/client/util/math/MatrixStack;)V", ordinal = 0),
        require = 0
    )
    private void fishingrodfix$endEntityPass(CallbackInfo ci) {
        ThirdPersonLineOrigin.onPassEnded();
    }
}
