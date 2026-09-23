package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.FishingLineOrigin;
import com.andrewchik.fishingrodfix.FishingLineVisibility;
import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the fishing line origin (the logic lives in {@link FishingLineOrigin} for the first-person rod
 * and in {@link ThirdPersonLineOrigin} for a rod held by a player's body) and hides the line of a hook
 * whose rod has left its owner's hands ({@link FishingLineVisibility}).
 *
 * <p>On 1.20.1 there is no render state, nothing is deferred, and there is no {@code getHandPos}
 * either: one {@code render} call computes the origin inline - in a first-person and a third-person
 * branch - and draws the catenary itself, segment by segment, from the offset between that point and
 * the bobber. The first-person branch builds its offset from the camera's projection and ends with the
 * single {@code Vec3d.rotateX(float)} of the whole method, so that is the value we modify
 * ({@code allow = 1} turns a second match into a load failure). The offset it yields is relative to the
 * owner's lerped <b>feet</b>; {@code render} adds the standing eye height further down, into the line's
 * y offset, so the handler converts to and from the world point {@link FishingLineOrigin} works in.
 *
 * <p>Modifying the branch's result rather than the merged value leaves the players other mods send down
 * the third-person branch (First Person Model, Real Camera, while they draw the local player's body) to
 * the body-held rod, and MixinExtras chains us with other mods that modify the same value. A body-held
 * rod's line goes straight into the offset locals {@code render} stores next, so the value the rest of
 * the method computes for every other mod stays vanilla's.
 *
 * <p>The order inside {@code render} is: the head reset, then one of the two origin branches (the
 * first-person one corrected from the inside), then - at the first read of the bobber's own position,
 * the first call past the branches - the decision on this hook's line, then the three offset
 * stores, then the catenary loop. The method's full descriptor is spelled out at every injection
 * because a synthetic bridge {@code render(Entity, …)} exists.
 */
@Mixin(FishingBobberEntityRenderer.class)
public class FishingBobberEntityRendererMixin {
    /**
     * Corrects the first-person line origin. Vanilla's value here is an offset from the owner's lerped
     * feet, and {@code render} turns it into
     * {@code feet + offset + (0, getStandingEyeHeight(), 0)} minus the bobber's position, so the
     * handler adds those two back to get vanilla's world point and takes them off again to return the
     * corrected one. The eye height is vanilla's own call, made a few instructions later in the same
     * branch on the same entity, so the two agree.
     */
    @ModifyExpressionValue(
        method = "render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/Vec3d;rotateX(F)Lnet/minecraft/util/math/Vec3d;"),
        allow = 1
    )
    private Vec3d fishingrodfix$correctFirstPersonOrigin(Vec3d handOffset, @Local(argsOnly = true, ordinal = 1) float tickDelta,
                                                         @Local(ordinal = 0) PlayerEntity owner) {
        // Asleep (or with a third-person camera) vanilla draws the player's own body, rod in hand: the
        // line belongs on that rod (placed further down in render by ThirdPersonLineOrigin), not the
        // first-person one.
        if (ThirdPersonLineOrigin.bodyDrawnInFirstPerson(owner)) {
            FishingLineVisibility.onFirstPersonOrigin(false);
            return handOffset;
        }
        Vec3d origin = FishingLineOrigin.correct(owner);
        // Null is every fallback: vanilla's own offset, untouched.
        FishingLineVisibility.onFirstPersonOrigin(origin != null);
        if (origin == null) {
            // Where FishingLineOrigin keeps vanilla's value with the camera on the player, only a body
            // drawn earlier in the same pass (a mod's first-person body, Iris' shadow pass) moves the
            // line (ThirdPersonLineOrigin).
            ThirdPersonLineOrigin.onFirstPersonVanilla(owner);
            return handOffset;
        }
        double feetX = MathHelper.lerp(tickDelta, owner.prevX, owner.getX());
        double feetY = MathHelper.lerp(tickDelta, owner.prevY, owner.getY());
        double feetZ = MathHelper.lerp(tickDelta, owner.prevZ, owner.getZ());
        return new Vec3d(origin.x - feetX, origin.y - feetY - owner.getStandingEyeHeight(), origin.z - feetZ);
    }

    /**
     * Starts a hook's drawing with no line origin placed yet ({@link FishingLineVisibility},
     * {@link ThirdPersonLineOrigin}) and back at vanilla's behaviour, so that a hook whose decision
     * below doesn't run keeps its line where vanilla puts it. Vanilla's own entity loop does reach a
     * hook whose player owner has gone - 1.20.1's {@code shouldRender} is a distance test with no
     * owner check, unlike the render-state branches' - and {@code render} returns at its first
     * instruction for one, drawing nothing; this reset is what leaves such a hook alone.
     */
    @Inject(
        method = "render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("HEAD")
    )
    private void fishingrodfix$beginRender(FishingBobberEntity hook, float yaw, float tickDelta, MatrixStack matrices,
                                           VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        FishingLineVisibility.beginRender();
        ThirdPersonLineOrigin.beginRender();
    }

    /**
     * Decides, for every hook and perspective, whether its line is drawn ({@link FishingLineVisibility})
     * and, if it isn't on the first-person rod, whether it moves onto the owner's drawn rod
     * ({@link ThirdPersonLineOrigin}). Sits at the first read of the bobber's own position, the first
     * call after the two origin branches meet and well before the offset locals are stored: the hook
     * above has already run for a first-person origin and its result is known. The
     * bobber's {@code getX} is the one call in the method with that owner - the owner's is
     * {@code PlayerEntity.getX} - so no ordinal is needed and neither branch's bytecode layout matters.
     * The owner is vanilla's own local, which saves asking the hook again; it is never null here,
     * because vanilla returns before this point for a hook without one. Both helpers still take a null
     * owner, as cheap defence against a mod that nulls the local: they then leave the defaults the head
     * set.
     */
    @Inject(
        method = "render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/projectile/FishingBobberEntity;getX()D")
    )
    private void fishingrodfix$decideLine(FishingBobberEntity hook, float yaw, float tickDelta, MatrixStack matrices,
                                          VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci,
                                          @Local(ordinal = 0) PlayerEntity owner) {
        boolean hidden = FishingLineVisibility.decideLineHidden(hook, owner);
        ThirdPersonLineOrigin.onHookDecided(owner, FishingLineVisibility.lineOnFirstPersonRod(), hidden, tickDelta);
    }

    /**
     * {@code render}'s {@code v}, the line offset's x: its seventh float local (LVT slot 38), after the
     * two float parameters {@code f}/{@code g}, the swing pair {@code h}/{@code k}, the body yaw
     * {@code l} and the eye-height term {@code r}. Also where the body-held origin is worked out
     * ({@link ThirdPersonLineOrigin#onHookDrawn}): the pose is the one the catenary is drawn with
     * ({@code render} pushes twice, poses and pops the bobber's copy, so the top of the stack is again
     * what it was at the head), and 1.20.1 has no earlier place that knows both the decision above and
     * that pose. Optional, as are the two below: without them a body-held line keeps vanilla's value.
     */
    @ModifyVariable(
        method = "render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("STORE"),
        ordinal = 6,
        require = 0
    )
    private float fishingrodfix$lineX(float x, @Local(argsOnly = true) FishingBobberEntity hook,
                                      @Local(argsOnly = true, ordinal = 1) float tickDelta,
                                      @Local(argsOnly = true) MatrixStack matrices,
                                      @Local(argsOnly = true) VertexConsumerProvider vertexConsumers,
                                      @Local(ordinal = 0) PlayerEntity owner) {
        ThirdPersonLineOrigin.onHookDrawn(hook, owner, tickDelta, matrices, vertexConsumers);
        return ThirdPersonLineOrigin.lineOffset(0, x);
    }

    /** {@code render}'s {@code w} (its eighth float local, LVT slot 39). */
    @ModifyVariable(
        method = "render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("STORE"),
        ordinal = 7,
        require = 0
    )
    private float fishingrodfix$lineY(float y) {
        return ThirdPersonLineOrigin.lineOffset(1, y);
    }

    /** {@code render}'s {@code x} (its ninth float local, LVT slot 40). */
    @ModifyVariable(
        method = "render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("STORE"),
        ordinal = 8,
        require = 0
    )
    private float fishingrodfix$lineZ(float z) {
        return ThirdPersonLineOrigin.lineOffset(2, z);
    }

    /**
     * Skips the catenary of a hidden line; the bobber is still drawn. 1.20.1 emits the line straight
     * into the {@code line_strip} buffer, so the condition sits on the one {@code renderFishingLine}
     * call site, which {@code render}'s loop reaches once per segment (17 times) for a hook whose line
     * is drawn at all. Optional, unlike on the deferred versions where the same condition sits on
     * a generic {@code submitCustom}: a fishing-specific private method is the natural target for a
     * mod that redraws the line, and hiding it is not what the fix is for - losing this leaves the
     * line visible, i.e. vanilla, where a required injector would mean a startup crash for everyone
     * running both mods. A stale descriptor here is caught by the smoke test's injection counting.
     */
    @WrapWithCondition(
        method = "render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/FishingBobberEntityRenderer;renderFishingLine(FFF"
                + "Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/util/math/MatrixStack$Entry;FF)V"),
        require = 0
    )
    private boolean fishingrodfix$skipHiddenLine(float x, float y, float z, VertexConsumer buffer, MatrixStack.Entry matrices,
                                                 float segmentStart, float segmentEnd) {
        return !FishingLineVisibility.lineHidden();
    }
}
