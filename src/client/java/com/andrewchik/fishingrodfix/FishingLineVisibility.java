package com.andrewchik.fishingrodfix;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;
import static com.andrewchik.fishingrodfix.FishingRodFix.holdsRod;
import static com.andrewchik.fishingrodfix.FishingRodFix.isRod;

/**
 * Hides the line of a hook whose rod has left its owner's hands (MC-310980, MC-211561).
 *
 * <p>Only the server removes a hook once its owner holds no rod ({@code FishingHook.shouldStopFishing},
 * on the hook's next tick), so the client keeps it for the ping, or indefinitely while the tick rate
 * is frozen. Vanilla keeps drawing its line meanwhile, from the off-hand side
 * ({@code FishingHookRenderer.getHoldingArm}'s fallback), where no rod is. The client knows at once
 * that no rod is held, so the line is hidden from then on; the bobber stays until the server removes
 * the hook. Where {@link FishingLineOrigin} placed the line on the first-person rod, that rod stays
 * among the drawn items while it lowers out of view (they lag the inventory), and the line stays on
 * it until it's gone.
 *
 * <p>Only a hook whose owner was seen holding a rod is ever hidden, so a hook cast by anything else
 * (a server's custom item, a mod's rod that isn't a {@code FishingRodItem}) keeps vanilla's line. For
 * the local player a rod among the drawn items counts too: they lag the inventory by a few ticks,
 * which covers a hook that reaches the client (a round trip after the cast) just after a quick
 * switch away from the rod.
 */
public final class FishingLineVisibility {
    /** Carries the decision on {@code FishingHookRenderState} from extraction to submission. */
    public interface State {
        boolean fishingrodfix$isLineHidden();

        void fishingrodfix$setLineHidden(boolean hidden);
    }

    /** Remembers on the {@code FishingHook} whether its owner was ever seen holding a rod. */
    public interface Hook {
        boolean fishingrodfix$wasSeenWithRod();

        void fishingrodfix$markSeenWithRod();
    }

    // Whether FishingLineOrigin placed the line of the hook being extracted on the drawn first-person
    // rod. Cleared at the start of extractRenderState, set by the origin hook inside it and read at
    // its end. A line left at vanilla's value (another mod sent the player down the third-person
    // branch, or the correction fell back) gets the third-person rule: vanilla's first-person
    // fallback would start it on the off-hand side, where no rod is. Render-thread only, as is all
    // state here.
    private static boolean lineOnDrawnRod;

    // The world the check failed in: lines are never hidden there (vanilla's behavior), so an
    // incompatible mod or game update degrades instead of crashing. It retries after the next
    // dimension change or world join (a new ClientLevel). Weak, so a stale level isn't kept alive.
    private static WeakReference<ClientLevel> disabledIn = new WeakReference<>(null);

    private FishingLineVisibility() {}

    /** Called at the start of a hook's {@code extractRenderState}. */
    public static void beginExtraction() {
        lineOnDrawnRod = false;
    }

    /** Called from {@code getPlayerHandPos}'s first-person branch: whether the origin was corrected. */
    public static void onFirstPersonOrigin(boolean onDrawnRod) {
        lineOnDrawnRod = onDrawnRod;
    }

    /** Decides, at the end of a hook's {@code extractRenderState}, whether its line is hidden. */
    public static boolean decideLineHidden(FishingHook hook, @Nullable Player owner) {
        Minecraft mc = Minecraft.getInstance();
        if (owner == null || disabledIn.get() == mc.level) {
            return false;
        }
        try {
            return shouldHideLine(mc, (Hook) hook, owner);
        } catch (RuntimeException | LinkageError e) {
            disabledIn = new WeakReference<>(mc.level);
            LOGGER.error("Fishing line visibility check failed, lines stay visible until the next world or dimension", e);
            return false;
        }
    }

    private static boolean shouldHideLine(Minecraft mc, Hook hook, Player owner) {
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

    /** Whether a rod is among the local player's drawn items (extracted every frame, any camera). */
    private static boolean rodDrawn(Minecraft mc) {
        FirstPersonHandsAndItemsRenderState hands = mc.gameRenderer.gameRenderState().levelRenderState.playerRenderState.firstPersonHandsAndItems;
        return isRod(hands.mainHandItem) || isRod(hands.offHandItem);
    }
}
