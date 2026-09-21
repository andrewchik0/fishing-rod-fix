package com.andrewchik.fishingrodfix;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod's shared logger and helpers. */
final class FishingRodFix {
    static final Logger LOGGER = LoggerFactory.getLogger("fishingrodfix");

    private FishingRodFix() {}

    /** A fishing rod by vanilla's own test ({@code FishingHookRenderer.getHoldingArm}). */
    static boolean isRod(ItemStack stack) {
        return stack.getItem() instanceof FishingRodItem;
    }

    /** Whether {@code player} holds a rod in either hand. */
    static boolean holdsRod(Player player) {
        return isRod(player.getMainHandItem()) || isRod(player.getOffhandItem());
    }
}
