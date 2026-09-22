package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ends a pass for {@link ThirdPersonLineOrigin} when its submissions are cleared: on 26.1.x one storage
 * serves the level pass, the first-person hand pass, the screen effects and the GUI's pictures (the
 * inventory's player model, animated items), each cleared once drawn: three or four clears per frame
 * in a world, plus one per animated item shown in the GUI. Hooked at the only read in {@code clear()}
 * (its {@code submitsPerOrder}), which returns the map unchanged and allocates nothing. Optional
 * ({@code require = 0}): without it a rod drawn after its hook in the same frame isn't remembered.
 */
@Mixin(SubmitNodeStorage.class)
public class SubmitNodeStorageMixin {
    @ModifyExpressionValue(
        method = "clear()V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SubmitNodeStorage;submitsPerOrder:Lit/unimi/dsi/fastutil/ints/Int2ObjectAVLTreeMap;"),
        require = 0
    )
    private Int2ObjectAVLTreeMap<SubmitNodeCollection> fishingrodfix$endPass(Int2ObjectAVLTreeMap<SubmitNodeCollection> submitsPerOrder) {
        ThirdPersonLineOrigin.onSubmitsCleared();
        return submitsPerOrder;
    }
}
