package com.andrewchik.fishingrodfix;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Arm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod's shared logger and helpers. */
final class FishingRodFix {
    static final Logger LOGGER = LoggerFactory.getLogger("fishingrodfix");

    private FishingRodFix() {}

    /**
     * A fishing rod by vanilla's own test. 1.21.1's {@code FishingBobberEntityRenderer.getHandPos}
     * picks the rod's side with {@code getMainHandStack().isOf(Items.FISHING_ROD)} - the item itself,
     * not {@code instanceof FishingRodItem} as 1.21.5+ do - so a modded rod that isn't
     * {@code minecraft:fishing_rod} is not vanilla's rod here and keeps vanilla's line throughout.
     */
    static boolean isRod(ItemStack stack) {
        return stack.isOf(Items.FISHING_ROD);
    }

    /**
     * The arm vanilla starts the line at, mirrored from {@code getHandPos}: it negates its side
     * factor when the main hand holds no rod, i.e. the main arm with a rod in the main hand and the
     * off arm otherwise (even for a player holding no rod at all, which a lingering hook allows).
     * 1.21.5+ expose the same rule as {@code getArmHoldingRod}; 1.21.1 computes it inline.
     */
    static Arm armHoldingRod(PlayerEntity player) {
        return isRod(player.getMainHandStack()) ? player.getMainArm() : player.getMainArm().getOpposite();
    }

    /** The stack drawn in {@code arm} (1.21.1 has no {@code LivingEntity.getStackInArm}). */
    static ItemStack stackInArm(PlayerEntity player, Arm arm) {
        return arm == player.getMainArm() ? player.getMainHandStack() : player.getOffHandStack();
    }

    /** Whether {@code player} holds a rod in either hand. */
    static boolean holdsRod(PlayerEntity player) {
        return isRod(player.getMainHandStack()) || isRod(player.getOffHandStack());
    }
}
