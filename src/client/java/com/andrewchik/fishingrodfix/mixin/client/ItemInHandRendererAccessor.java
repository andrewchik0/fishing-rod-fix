package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The first-person hand pass's own state: the items it draws in each hand (they lag the inventory
 * during swap animations) and their equip heights, which {@code ItemInHandRenderer.tick} updates and
 * {@code submitHandsWithItems} lerps into the equip dip.
 */
@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccessor {
    @Accessor("mainHandItem")
    ItemStack fishingrodfix$getMainHandItem();

    @Accessor("offHandItem")
    ItemStack fishingrodfix$getOffHandItem();

    @Accessor("mainHandHeight")
    float fishingrodfix$getMainHandHeight();

    @Accessor("oMainHandHeight")
    float fishingrodfix$getOldMainHandHeight();

    @Accessor("offHandHeight")
    float fishingrodfix$getOffHandHeight();

    @Accessor("oOffHandHeight")
    float fishingrodfix$getOldOffHandHeight();
}
