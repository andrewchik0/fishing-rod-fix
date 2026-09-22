package com.andrewchik.fishingrodfix;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod's shared logger and helpers. */
final class FishingRodFix {
    static final Logger LOGGER = LoggerFactory.getLogger("fishingrodfix");

    private FishingRodFix() {}

    /** A fishing rod by vanilla's own test ({@code FishingBobberEntityRenderer.getArmHoldingRod}). */
    static boolean isRod(ItemStack stack) {
        return stack.getItem() instanceof FishingRodItem;
    }

    /** Whether {@code player} holds a rod in either hand. */
    static boolean holdsRod(PlayerEntity player) {
        return isRod(player.getMainHandStack()) || isRod(player.getOffHandStack());
    }
}
