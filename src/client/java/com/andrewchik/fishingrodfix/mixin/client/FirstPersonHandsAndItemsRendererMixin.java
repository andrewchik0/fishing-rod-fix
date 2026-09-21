package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.HandPass;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the frames in which the first-person hand pass drew a hand, for {@link HandPass}: at the
 * {@code submitArmWithItem} calls, which are only reached past vanilla's own guard and past any mod
 * that cancels {@code submitHandsWithItems} at its head. Optional ({@code require = 0}): if it can't
 * apply, {@link HandPass} reports no hand pass and the line stays vanilla instead of the game crashing.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class FirstPersonHandsAndItemsRendererMixin {
    @Inject(
        method = "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                + "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitArmWithItem("
                + "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;"
                + "FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"),
        require = 0
    )
    private void fishingrodfix$markHandPass(CallbackInfo ci) {
        HandPass.onHandPass();
    }
}
