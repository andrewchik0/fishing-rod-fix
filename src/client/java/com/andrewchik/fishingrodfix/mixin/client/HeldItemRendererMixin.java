package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.HandPass;
import net.minecraft.client.render.item.HeldItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the frames in which the first-person hand pass drew a hand, for {@link HandPass}: at the
 * {@code renderFirstPersonItem} calls of the five-argument {@code renderItem} (the hand pass; the other
 * overload draws a single item for any entity), which are only reached past vanilla's own guard and
 * past any mod that cancels that {@code renderItem} at its head. Optional ({@code require = 0}): if it
 * can't apply, {@link HandPass} reports no hand pass and the line stays vanilla instead of the game
 * crashing.
 */
@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {
    @Inject(
        method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;"
                + "Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderFirstPersonItem("
                + "Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;"
                + "FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V"),
        require = 0
    )
    private void fishingrodfix$markHandPass(CallbackInfo ci) {
        HandPass.onHandPass();
    }
}
