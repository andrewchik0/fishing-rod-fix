package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.FishingLineOrigin;
import com.andrewchik.fishingrodfix.FishingLineVisibility;
import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.client.render.entity.state.FishingBobberEntityState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
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
 * <p>On 1.21.10 the line is a deferred {@code submitCustom} whose origin offset
 * {@code updateRenderState} takes from {@code getHandPos}. For the first-person rod we modify the
 * value of that method's only {@code Vec3d.add(Vec3d)} call,
 * {@code player.getCameraPosVec(tickProgress).add(vec3d)}, which exists only in its first-person
 * branch ({@code allow = 1} turns a second match into a load failure). Hooking the branch's result
 * rather than the method's return leaves the players other mods send down the third-person branch
 * (First Person Model and Real Camera, while they draw the local player's body) to the body-held rod,
 * and MixinExtras chains us with other mods that modify the same value. A body-held rod's line is
 * placed at {@code render}, where the rod drawn earlier in the same pass is known: straight into the
 * offset locals {@code render} reads, so the state's {@code pos} keeps its extracted value.
 */
@Mixin(FishingBobberEntityRenderer.class)
public class FishingBobberEntityRendererMixin {
    @ModifyExpressionValue(
        method = "getHandPos(Lnet/minecraft/entity/player/PlayerEntity;FF)Lnet/minecraft/util/math/Vec3d;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/Vec3d;add(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;"),
        allow = 1
    )
    private Vec3d fishingrodfix$correctFirstPersonOrigin(Vec3d handPos, @Local(argsOnly = true) PlayerEntity owner) {
        // Asleep (or with a third-person camera) vanilla draws the player's own body, rod in hand: the
        // line belongs on that rod (placed at render by ThirdPersonLineOrigin), not the first-person one.
        if (ThirdPersonLineOrigin.bodyDrawnInFirstPerson(owner)) {
            FishingLineVisibility.onFirstPersonOrigin(false);
            return handPos;
        }
        Vec3d origin = FishingLineOrigin.correct(handPos, owner);
        // Every fallback returns handPos itself. Where FishingLineOrigin keeps vanilla's value with the
        // camera on the player, only a body drawn earlier in the same pass (a mod's first-person body,
        // Iris' shadow pass) moves the line (ThirdPersonLineOrigin).
        boolean onDrawnRod = origin != handPos;
        FishingLineVisibility.onFirstPersonOrigin(onDrawnRod);
        if (!onDrawnRod) {
            ThirdPersonLineOrigin.onFirstPersonVanilla(owner);
        }
        return origin;
    }

    /**
     * Starts a hook's extraction with no line origin placed yet ({@link FishingLineVisibility},
     * {@link ThirdPersonLineOrigin}).
     */
    @Inject(
        method = "updateRenderState(Lnet/minecraft/entity/projectile/FishingBobberEntity;Lnet/minecraft/client/render/entity/state/FishingBobberEntityState;F)V",
        at = @At("HEAD")
    )
    private void fishingrodfix$beginExtraction(CallbackInfo ci) {
        FishingLineVisibility.beginExtraction();
        ThirdPersonLineOrigin.beginExtraction();
    }

    /**
     * Decides, for every hook and perspective, whether its line is drawn ({@link FishingLineVisibility})
     * and, if it isn't on the first-person rod, whose drawn rod it moves onto at submission
     * ({@link ThirdPersonLineOrigin}). {@code updateRenderState} calls {@code getHandPos} before this
     * TAIL, so this also learns whether the line origin was placed on the drawn first-person rod.
     */
    @Inject(
        method = "updateRenderState(Lnet/minecraft/entity/projectile/FishingBobberEntity;Lnet/minecraft/client/render/entity/state/FishingBobberEntityState;F)V",
        at = @At("TAIL")
    )
    private void fishingrodfix$decideLine(FishingBobberEntity hook, FishingBobberEntityState state, float tickProgress, CallbackInfo ci) {
        PlayerEntity owner = hook.getPlayerOwner();
        boolean hidden = FishingLineVisibility.decideLineHidden(hook, owner);
        ((FishingLineVisibility.State) state).fishingrodfix$setLineHidden(hidden);
        ThirdPersonLineOrigin.onHookExtracted(state, owner, FishingLineVisibility.lineOnFirstPersonRod(), hidden, tickProgress);
    }

    /**
     * Works out where a body-held rod's line starts ({@link ThirdPersonLineOrigin}), before
     * {@code render} reads the origin. Optional, as are the three below: without them that line keeps
     * vanilla's value.
     */
    @Inject(
        method = "render(Lnet/minecraft/client/render/entity/state/FishingBobberEntityState;Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
        at = @At("HEAD"),
        require = 0
    )
    private void fishingrodfix$placeOnDrawnRod(FishingBobberEntityState state, MatrixStack matrices, OrderedRenderCommandQueue queue,
                                               CameraRenderState camera, CallbackInfo ci) {
        ThirdPersonLineOrigin.onHookSubmitted(state, matrices, queue);
    }

    /** {@code render}'s {@code f}, the line offset's x read from {@code pos} (its first float local). */
    @ModifyVariable(
        method = "render(Lnet/minecraft/client/render/entity/state/FishingBobberEntityState;Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
        at = @At("STORE"),
        ordinal = 0,
        require = 0
    )
    private float fishingrodfix$lineX(float x, @Local(argsOnly = true) FishingBobberEntityState state) {
        return ThirdPersonLineOrigin.lineOffset(state, 0, x);
    }

    /** {@code render}'s {@code g} (its second float local). */
    @ModifyVariable(
        method = "render(Lnet/minecraft/client/render/entity/state/FishingBobberEntityState;Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
        at = @At("STORE"),
        ordinal = 1,
        require = 0
    )
    private float fishingrodfix$lineY(float y, @Local(argsOnly = true) FishingBobberEntityState state) {
        return ThirdPersonLineOrigin.lineOffset(state, 1, y);
    }

    /** {@code render}'s {@code h} (its third float local). */
    @ModifyVariable(
        method = "render(Lnet/minecraft/client/render/entity/state/FishingBobberEntityState;Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
        at = @At("STORE"),
        ordinal = 2,
        require = 0
    )
    private float fishingrodfix$lineZ(float z, @Local(argsOnly = true) FishingBobberEntityState state) {
        return ThirdPersonLineOrigin.lineOffset(state, 2, z);
    }

    /** Skips the line's geometry (the {@code lines} render layer) of a hidden line; the bobber is still drawn. */
    @WrapWithCondition(
        method = "render(Lnet/minecraft/client/render/entity/state/FishingBobberEntityState;Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitCustom(Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue$Custom;)V")
    )
    private boolean fishingrodfix$skipHiddenLine(OrderedRenderCommandQueue queue, MatrixStack matrices, RenderLayer renderLayer,
                                                 OrderedRenderCommandQueue.Custom geometry,
                                                 @Local(argsOnly = true) FishingBobberEntityState state) {
        return renderLayer != RenderLayer.getLines() || !((FishingLineVisibility.State) state).fishingrodfix$isLineHidden();
    }
}
