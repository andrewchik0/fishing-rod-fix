package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.FishingLineOrigin;
import com.andrewchik.fishingrodfix.FishingLineVisibility;
import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;
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
 * <p>On 26.x the line is a deferred {@code submitCustomGeometry} whose origin offset
 * {@code extractRenderState} takes from {@code getPlayerHandPos}. For the first-person rod we modify
 * the value of that method's only {@code Vec3.add(Vec3)} call,
 * {@code owner.getEyePosition(partialTicks).add(viewVec)}, which exists only in its first-person branch
 * ({@code allow = 1} turns a second match into a load failure). Hooking the branch's result rather than
 * the method's return leaves the players other mods send down the third-person branch (First Person
 * Model and Real Camera, while they draw the local player's body) to the body-held rod, and MixinExtras
 * chains us with other mods that modify the same value. A body-held rod's line is placed at
 * {@code submit}, where the rod drawn earlier in the same pass is known: straight into the offset
 * locals {@code submit} reads, so {@code lineOriginOffset} keeps its extracted value.
 */
@Mixin(FishingHookRenderer.class)
public class FishingHookRendererMixin {
    @ModifyExpressionValue(
        method = "getPlayerHandPos(Lnet/minecraft/world/entity/player/Player;FF)Lnet/minecraft/world/phys/Vec3;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
        allow = 1
    )
    private Vec3 fishingrodfix$correctFirstPersonOrigin(Vec3 handPos, @Local(argsOnly = true) Player owner) {
        // Asleep (or with a detached camera) vanilla draws the player's own body, rod in hand: the line
        // belongs on that rod (placed at submit by ThirdPersonLineOrigin), not the first-person one.
        if (ThirdPersonLineOrigin.bodyDrawnInFirstPerson(owner)) {
            FishingLineVisibility.onFirstPersonOrigin(false);
            return handPos;
        }
        Vec3 origin = FishingLineOrigin.correct(handPos, owner);
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
        method = "extractRenderState(Lnet/minecraft/world/entity/projectile/FishingHook;Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;F)V",
        at = @At("HEAD")
    )
    private void fishingrodfix$beginExtraction(CallbackInfo ci) {
        FishingLineVisibility.beginExtraction();
        ThirdPersonLineOrigin.beginExtraction();
    }

    /**
     * Decides, for every hook and perspective, whether its line is drawn ({@link FishingLineVisibility})
     * and, if it isn't on the first-person rod, whose drawn rod it moves onto at submission
     * ({@link ThirdPersonLineOrigin}). {@code extractRenderState} calls {@code getPlayerHandPos} before
     * this TAIL, so this also learns whether the line origin was placed on the drawn first-person rod.
     */
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/projectile/FishingHook;Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;F)V",
        at = @At("TAIL")
    )
    private void fishingrodfix$decideLine(FishingHook hook, FishingHookRenderState state, float partialTicks, CallbackInfo ci) {
        Player owner = hook.getPlayerOwner();
        boolean hidden = FishingLineVisibility.decideLineHidden(hook, owner);
        ((FishingLineVisibility.State) state).fishingrodfix$setLineHidden(hidden);
        ThirdPersonLineOrigin.onHookExtracted(state, owner, FishingLineVisibility.lineOnFirstPersonRod(), hidden, partialTicks);
    }

    /**
     * Works out where a body-held rod's line starts ({@link ThirdPersonLineOrigin}), before
     * {@code submit} reads the origin. Optional, as are the three below: without them that line keeps
     * vanilla's value.
     */
    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"),
        require = 0
    )
    private void fishingrodfix$placeOnDrawnRod(FishingHookRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                               CameraRenderState camera, CallbackInfo ci) {
        ThirdPersonLineOrigin.onHookSubmitted(state, poseStack, collector);
    }

    /** {@code submit}'s {@code xa}, the line offset's x read from {@code lineOriginOffset} (its first float local). */
    @ModifyVariable(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("STORE"),
        ordinal = 0,
        require = 0
    )
    private float fishingrodfix$lineX(float xa, @Local(argsOnly = true) FishingHookRenderState state) {
        return ThirdPersonLineOrigin.lineOffset(state, 0, xa);
    }

    /** {@code submit}'s {@code ya} (its second float local). */
    @ModifyVariable(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("STORE"),
        ordinal = 1,
        require = 0
    )
    private float fishingrodfix$lineY(float ya, @Local(argsOnly = true) FishingHookRenderState state) {
        return ThirdPersonLineOrigin.lineOffset(state, 1, ya);
    }

    /** {@code submit}'s {@code za} (its third float local). */
    @ModifyVariable(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("STORE"),
        ordinal = 2,
        require = 0
    )
    private float fishingrodfix$lineZ(float za, @Local(argsOnly = true) FishingHookRenderState state) {
        return ThirdPersonLineOrigin.lineOffset(state, 2, za);
    }

    /** Skips the line's geometry (the {@code lines} render type) of a hidden line; the bobber is still drawn. */
    @WrapWithCondition(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V")
    )
    private boolean fishingrodfix$skipHiddenLine(SubmitNodeCollector collector, PoseStack poseStack, RenderType renderType,
                                                 SubmitNodeCollector.CustomGeometryRenderer geometry,
                                                 @Local(argsOnly = true) FishingHookRenderState state) {
        return renderType != RenderTypes.lines() || !((FishingLineVisibility.State) state).fishingrodfix$isLineHidden();
    }
}
