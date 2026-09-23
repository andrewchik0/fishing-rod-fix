package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
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
     * A fishing rod by vanilla's own test. 1.20.1's {@code FishingBobberEntityRenderer.render}
     * picks the rod's side with {@code getMainHandStack().isOf(Items.FISHING_ROD)} - the item itself,
     * not {@code instanceof FishingRodItem} as 1.21.5+ do - so a modded rod that isn't
     * {@code minecraft:fishing_rod} is not vanilla's rod here and keeps vanilla's line throughout.
     */
    static boolean isRod(ItemStack stack) {
        return stack.isOf(Items.FISHING_ROD);
    }

    /**
     * The arm vanilla starts the line at, mirrored from {@code render}'s own side factor: it negates
     * that factor when the main hand holds no rod, i.e. the main arm with a rod in the main hand and
     * the off arm otherwise (even for a player holding no rod at all, which a lingering hook allows).
     * 1.21.5+ expose the same rule as {@code getArmHoldingRod}; 1.20.1 computes it inline.
     */
    static Arm armHoldingRod(PlayerEntity player) {
        return isRod(player.getMainHandStack()) ? player.getMainArm() : player.getMainArm().getOpposite();
    }

    /** The stack drawn in {@code arm} (1.20.1 has no {@code LivingEntity.getStackInArm}). */
    static ItemStack stackInArm(PlayerEntity player, Arm arm) {
        return arm == player.getMainArm() ? player.getMainHandStack() : player.getOffHandStack();
    }

    /** Whether {@code player} holds a rod in either hand. */
    static boolean holdsRod(PlayerEntity player) {
        return isRod(player.getMainHandStack()) || isRod(player.getOffHandStack());
    }

    /**
     * The tick progress this frame is drawn at, exactly as {@code MinecraftClient.render} hands it to
     * {@code GameRenderer.render} and through it to {@code renderWorld}, the entity pass and the hand
     * pass: the render tick counter's, or the frozen one while the integrated server is paused. 1.20.1
     * has no {@code getRenderTickCounter()} and its public {@code getTickDelta()} is the counter's
     * field alone, which keeps running while paused, so both fields are read through
     * {@link MinecraftClientAccessor}. There is no tick manager on 1.20.1, so every entity is drawn at
     * this same progress.
     */
    static float tickDelta(MinecraftClient mc) {
        MinecraftClientAccessor accessor = (MinecraftClientAccessor) mc;
        return mc.isPaused()
                ? accessor.fishingrodfix$getPausedTickDelta()
                : accessor.fishingrodfix$getRenderTickCounter().tickDelta;
    }
}
