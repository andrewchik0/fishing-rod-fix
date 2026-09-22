package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads a fishing player's rod where it is drawn ({@link ThirdPersonLineOrigin}): at the call that
 * submits a held item, with the pose everything before it built (the body, the arm, the layer's hand
 * offset and item animations, other mods' changes to them). The call sits past
 * {@code renderItem}'s empty check, so it runs once per non-empty held item of an armed entity and
 * the handler starts with a null check, a compare, a type check and an owner lookup that inline
 * there (on
 * 1.21.10 the method gets no stack: only the players whose body-held hook was extracted this frame
 * go on, and whether the item is their rod is decided out of line). Priority 1500
 * puts it after other mods' {@code @Inject}s at the same call at default priority (Player Animation
 * Library's and Animatium's item transforms); a wrap, redirect or argument change of the call itself
 * applies after it. Optional ({@code require = 0}): without it a body-held rod's line keeps vanilla's
 * value.
 */
@Mixin(value = HeldItemFeatureRenderer.class, priority = 1500)
public class HeldItemFeatureRendererMixin {
    @Inject(
        method = "renderItem(Lnet/minecraft/client/render/entity/state/ArmedEntityRenderState;Lnet/minecraft/client/render/item/ItemRenderState;"
                + "Lnet/minecraft/util/Arm;Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/ItemRenderState;render(Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;III)V"),
        require = 0
    )
    private void fishingrodfix$readDrawnRod(ArmedEntityRenderState state, ItemRenderState item, Arm arm,
                                            MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        ThirdPersonLineOrigin.onItemSubmitted(state, arm, matrices, queue);
    }
}
