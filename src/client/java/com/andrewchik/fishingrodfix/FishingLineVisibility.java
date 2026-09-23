package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.HeldItemRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;
import static com.andrewchik.fishingrodfix.FishingRodFix.holdsRod;
import static com.andrewchik.fishingrodfix.FishingRodFix.isRod;

/**
 * Hides the line of a hook whose rod has left its owner's hands (MC-310980, MC-211561).
 *
 * <p>Only the server removes a hook once its owner holds no rod
 * ({@code FishingBobberEntity.removeIfInvalid}, on the hook's next tick), so the client keeps it for
 * the ping, or indefinitely while the tick rate is frozen. Vanilla keeps drawing its line meanwhile,
 * from the off-hand side ({@link FishingRodFix#armHoldingRod}'s fallback), where no rod is. The
 * client knows at once that no rod is held, so the line is hidden from then on; the bobber stays
 * until the server removes the hook. Where {@link FishingLineOrigin} placed the line on the
 * first-person rod, that rod stays among the drawn items while it lowers out of view (they lag the
 * inventory), and the line stays on it until it's gone.
 *
 * <p>Only a hook whose owner was seen holding a rod is ever hidden, so a hook cast by anything else
 * (a server's custom item, a mod's rod that isn't {@code minecraft:fishing_rod}) keeps vanilla's
 * line. For the local player a rod among the drawn items counts too: they lag the inventory by a few
 * ticks, which covers a hook that reaches the client (a round trip after the cast) just after a quick
 * switch away from the rod.
 *
 * <p>1.21.1 has no render state: a hook's origin is worked out and its catenary drawn in one
 * {@code FishingBobberEntityRenderer.render} call, so the decision is a field the catenary loop of
 * that very call reads, rather than something carried from an extraction pass to a later submission.
 */
public final class FishingLineVisibility {
    /** Remembers on the {@code FishingBobberEntity} whether its owner was ever seen holding a rod. */
    public interface Hook {
        boolean fishingrodfix$wasSeenWithRod();

        void fishingrodfix$markSeenWithRod();
    }

    // Whether FishingLineOrigin placed the line of the hook being drawn on the drawn first-person
    // rod. Cleared at the start of render, set by the first-person origin hook inside it and read
    // once getHandPos has returned. Any other line (on a rod held by the body, which shows the live
    // inventory, or at vanilla's value, whose first-person fallback would start on the off-hand
    // side, where no rod is) gets the third-person rule. Render-thread only, as is all state here.
    private static boolean lineOnDrawnRod;

    // Whether the line of the hook being drawn is hidden, for the catenary loop that follows.
    // Cleared at the start of render, so a hook whose decision never runs keeps vanilla's line.
    private static boolean lineHidden;

    // The world the check failed in: lines are never hidden there (vanilla's behavior), so an
    // incompatible mod or game update degrades instead of crashing. It retries after the next
    // dimension change or world join (a new ClientWorld). Weak, so a stale world isn't kept alive.
    private static WeakReference<ClientWorld> disabledIn = new WeakReference<>(null);

    private FishingLineVisibility() {}

    /** Called at the start of a hook's {@code render}. */
    public static void beginRender() {
        lineOnDrawnRod = false;
        lineHidden = false;
    }

    /** Called from {@code getHandPos}'s first-person branch: whether the origin is on the drawn first-person rod. */
    public static void onFirstPersonOrigin(boolean onDrawnRod) {
        lineOnDrawnRod = onDrawnRod;
    }

    /** Whether the hook being drawn has its line on the drawn first-person rod (valid once {@code getHandPos} returned). */
    public static boolean lineOnFirstPersonRod() {
        return lineOnDrawnRod;
    }

    /** Whether the catenary of the hook being drawn is skipped. */
    public static boolean lineHidden() {
        return lineHidden;
    }

    /** Decides, once {@code getHandPos} has returned, whether the hook's line is hidden. */
    public static boolean decideLineHidden(FishingBobberEntity hook, @Nullable PlayerEntity owner) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (owner == null || disabledIn.get() == mc.world) {
            return lineHidden = false;
        }
        try {
            return lineHidden = shouldHideLine(mc, (Hook) hook, owner);
        } catch (RuntimeException | LinkageError e) {
            disabledIn = new WeakReference<>(mc.world);
            LOGGER.error("Fishing line visibility check failed, lines stay visible until the next world or dimension", e);
            return lineHidden = false;
        }
    }

    private static boolean shouldHideLine(MinecraftClient mc, Hook hook, PlayerEntity owner) {
        if (holdsRod(owner)) {
            hook.fishingrodfix$markSeenWithRod();
            return false;
        }
        if (owner == mc.player && rodDrawn(mc)) {
            hook.fishingrodfix$markSeenWithRod();
            // The line starts at the lowering rod: its origin was placed there (a hand was drawn last
            // frame, or only the hidden HUD hides it).
            if (lineOnDrawnRod) {
                return false;
            }
        }
        return hook.fishingrodfix$wasSeenWithRod();
    }

    /** Whether a rod is among the local player's drawn items (HeldItemRenderer updates them every tick, any camera). */
    private static boolean rodDrawn(MinecraftClient mc) {
        HeldItemRendererAccessor hands = (HeldItemRendererAccessor) mc.getEntityRenderDispatcher().getHeldItemRenderer();
        return isRod(hands.fishingrodfix$getMainHand()) || isRod(hands.fishingrodfix$getOffHand());
    }
}
