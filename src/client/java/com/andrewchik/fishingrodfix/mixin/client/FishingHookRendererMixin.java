package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.FishingLineOrigin;
import com.andrewchik.fishingrodfix.FishingLineVisibility;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the first-person fishing line origin (the logic lives in {@link FishingLineOrigin}) and hides
 * the line of a hook whose rod has left its owner's hands ({@link FishingLineVisibility}).
 *
 * <p>On 26.x the line is a deferred {@code submitCustomGeometry} whose origin offset
 * {@code extractRenderState} takes from {@code getPlayerHandPos}. We modify the value of that method's only
 * {@code Vec3.add(Vec3)} call, {@code owner.getEyePosition(partialTicks).add(viewVec)}, which exists
 * only in its first-person branch (the third-person branch uses {@code add(double, double, double)};
 * {@code allow = 1} turns a second match into a load failure). Hooking the branch's result rather
 * than the method's return leaves mods that route the player into the third-person branch
 * (First Person Model, Real Camera) alone, and MixinExtras chains us with other mods that modify the
 * same value.
 */
@Mixin(FishingHookRenderer.class)
public class FishingHookRendererMixin {
    @ModifyExpressionValue(
        method = "getPlayerHandPos(Lnet/minecraft/world/entity/player/Player;FF)Lnet/minecraft/world/phys/Vec3;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
        allow = 1
    )
    private Vec3 fishingrodfix$correctFirstPersonOrigin(Vec3 handPos, @Local(argsOnly = true) Player owner) {
        Vec3 origin = FishingLineOrigin.correct(handPos, owner);
        // Every fallback returns handPos itself.
        FishingLineVisibility.onFirstPersonOrigin(origin != handPos);
        return origin;
    }

    /** Starts a hook's extraction with no line origin placed yet ({@link FishingLineVisibility}). */
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/projectile/FishingHook;Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;F)V",
        at = @At("HEAD")
    )
    private void fishingrodfix$beginExtraction(CallbackInfo ci) {
        FishingLineVisibility.beginExtraction();
    }

    /**
     * Decides, for every hook and perspective, whether its line is drawn ({@link FishingLineVisibility}).
     * {@code extractRenderState} calls {@code getPlayerHandPos} before this TAIL, so this also learns
     * whether the line origin was placed on the drawn rod.
     */
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/projectile/FishingHook;Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;F)V",
        at = @At("TAIL")
    )
    private void fishingrodfix$decideLineVisibility(FishingHook hook, FishingHookRenderState state, float partialTicks, CallbackInfo ci) {
        ((FishingLineVisibility.State) state).fishingrodfix$setLineHidden(FishingLineVisibility.decideLineHidden(hook, hook.getPlayerOwner()));
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
