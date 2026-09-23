package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The first-person hand pass's own state: the items it draws in each hand (they lag the inventory
 * during swap animations) and their equip progress, which {@code HeldItemRenderer.updateHeldItems}
 * updates every tick and {@code renderItem} lerps into the equip dip.
 */
@Mixin(HeldItemRenderer.class)
public interface HeldItemRendererAccessor {
    @Accessor("mainHand")
    ItemStack fishingrodfix$getMainHand();

    @Accessor("offHand")
    ItemStack fishingrodfix$getOffHand();

    @Accessor("equipProgressMainHand")
    float fishingrodfix$getEquipProgressMainHand();

    @Accessor("lastEquipProgressMainHand")
    float fishingrodfix$getLastEquipProgressMainHand();

    @Accessor("equipProgressOffHand")
    float fishingrodfix$getEquipProgressOffHand();

    @Accessor("lastEquipProgressOffHand")
    float fishingrodfix$getLastEquipProgressOffHand();
}
