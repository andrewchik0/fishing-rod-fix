package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.ThirdPersonLineOrigin;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads a fishing player's rod where it is drawn ({@link ThirdPersonLineOrigin}): at the call that
 * draws a held item, with the pose everything before it built (the body, the arm, the layer's hand
 * offset and item animations, other mods' changes to them) and before the item's own display
 * transform, which {@code ItemRenderer.renderItem} applies two calls further in. Runs past its empty check, so once per non-empty held item of an armed entity,
 * so the handler starts with the item test and a type check that inline there;
 * 1.21.1 hands the call both the stack and the entity, so nothing more is needed to tell a player's
 * rod from anything else (the render-state branches, whose call carries neither, have to key on the
 * hooks extracted this frame instead). {@code PlayerHeldItemFeatureRenderer} overrides this method but
 * delegates to it for everything except a raised spyglass, so a player's rod comes through. Priority
 * 1500 puts it after other mods' {@code @Inject}s at the same call at default priority (animation and
 * held-item position mods); a wrap, redirect or argument change of the call itself applies after it.
 * Optional ({@code require = 0}): without it a body-held rod's line keeps vanilla's value.
 */
@Mixin(value = HeldItemFeatureRenderer.class, priority = 1500)
public class HeldItemFeatureRendererMixin {
    @Inject(
        method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"
                + "Lnet/minecraft/client/render/model/json/ModelTransformationMode;Lnet/minecraft/util/Arm;"
                + "Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem("
                + "Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"
                + "Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V"),
        require = 0
    )
    private void fishingrodfix$readDrawnRod(LivingEntity entity, ItemStack stack, ModelTransformationMode transformationMode, Arm arm,
                                            MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        ThirdPersonLineOrigin.onItemDrawn(entity, stack, arm, matrices, vertexConsumers);
    }
}
