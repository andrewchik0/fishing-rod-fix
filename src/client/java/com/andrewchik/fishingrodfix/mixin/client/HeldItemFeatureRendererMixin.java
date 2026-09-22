package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads a fishing player's rod where it is drawn ({@link ThirdPersonLineOrigin}): at the call that
 * submits a held item, with the pose everything before it built (the body, the arm, the layer's hand
 * offset and item animations, other mods' changes to them). Runs for every armed entity's held item,
 * so the handler starts with a compare and a type check that inline there. Priority 1500 puts it
 * after other mods' {@code @Inject}s at the same call at default priority (Player Animation
 * Library's and Animatium's item transforms); a wrap, redirect or argument change of the call itself
 * applies after it. Optional ({@code require = 0}): without it a body-held rod's line keeps vanilla's
 * value.
 */
@Mixin(value = HeldItemFeatureRenderer.class, priority = 1500)
public class HeldItemFeatureRendererMixin {
    @Inject(
        method = "renderItem(Lnet/minecraft/client/render/entity/state/ArmedEntityRenderState;Lnet/minecraft/client/render/item/ItemRenderState;"
                + "Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Arm;Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/ItemRenderState;render(Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;III)V"),
        require = 0
    )
    private void fishingrodfix$readDrawnRod(ArmedEntityRenderState state, ItemRenderState item, ItemStack stack, Arm arm,
                                            MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        ThirdPersonLineOrigin.onItemSubmitted(state, stack, arm, matrices, queue);
    }
}
