package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads a fishing player's rod where it is drawn ({@link ThirdPersonLineOrigin}): at the call that
 * submits a held item, with the pose everything before it built (body, arm, item animations, other
 * mods' changes). The call sits past {@code submitArmWithItem}'s empty check, so it runs once per
 * non-empty held item of an armed entity. Priority 1500 puts it after other mods' default-priority
 * {@code @Inject}s at the same call (Player Animation Library's and Animatium's item transforms); a
 * wrap, redirect or argument change of the call itself applies after it. Optional: without it a
 * body-held rod's line keeps vanilla's value.
 */
@Mixin(value = ItemInHandLayer.class, priority = 1500)
public class ItemInHandLayerMixin {
    @Inject(
        method = "submitArmWithItem(Lnet/minecraft/client/renderer/entity/state/ArmedEntityRenderState;Lnet/minecraft/client/renderer/item/ItemStackRenderState;"
                + "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"),
        require = 0
    )
    private void fishingrodfix$readDrawnRod(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack itemStack, HumanoidArm arm,
                                            PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
        ThirdPersonLineOrigin.onItemSubmitted(state, item, itemStack, arm, poseStack, collector);
    }
}
