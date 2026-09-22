package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.GameRendererInvoker;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;
import static com.andrewchik.fishingrodfix.FishingRodFix.holdsRod;
import static com.andrewchik.fishingrodfix.FishingRodFix.isRod;

/**
 * Places the first-person fishing line origin on the rod that is actually drawn (a rod held by a
 * player's body is {@link ThirdPersonLineOrigin}'s).
 *
 * <p>Vanilla's {@code FishingHookRenderer.getPlayerHandPos} guesses the origin from the eye and the
 * options FOV, while the rod is drawn in a separate hand pass with its own FOV and pose, so the two
 * drift apart. Instead we take the line's attachment point on the drawn rod ({@link FirstPersonRod})
 * and put the world point where the hand pass puts that point on screen. The hand-pass inputs are
 * the state vanilla extracted for this frame ({@code LevelExtractor.extractPlayerState} and
 * {@code GameRenderer.extractCamera} both run before the level's entities). The world FOV is read from
 * the extracted projection {@code renderLevel} draws the world with (in vanilla the camera's FOV), so
 * mods that change the projection itself, not only the camera's FOV, are followed too; the camera
 * pose comes from the live {@code Camera} that frame was set up from. All of it is this frame's, so
 * the origin is worked out once per frame and reused by further extractions of the local player's
 * hooks in it (Iris' shadow pass extracts them a second time).
 * <ol>
 *   <li><b>Rod pose.</b> The drawn rod's hand (or, while a rod is held but none is drawn yet, the
 *       hand it was last drawn in), its equip/re-equip dip (after every cast and reel-in) and its
 *       swing, exactly as {@code FirstPersonHandsAndItemsRenderer.submitArmWithItem} poses an idle or
 *       swinging item.</li>
 *   <li><b>Item sway.</b> {@code submitHandsWithItems} rotates the whole hand pass by
 *       {@code (viewXRot - xBob) * 0.1deg} about X and {@code (viewYRot - yBob) * 0.1deg} about Y.</li>
 *   <li><b>View bob and hurt tilt</b>, which {@code renderItemInHand} applies before the hand, via
 *       vanilla's own {@code bobHurt}/{@code bobView}.</li>
 *   <li><b>Same pixel.</b> The hand pass projects at the hand FOV ({@code hudFov}: 70deg, narrowed
 *       underwater, in lava and while dying); the world pass at the world FOV (options FOV times the
 *       movement modifier (sprint/speed/flying/bow), fluid and death) after the same bob and the
 *       nausea/portal warp, both at the window's aspect ratio. A view point lands on the drawn pixel
 *       when its tangents are the drawn ones times {@code tan(worldFov/2) / tan(handFov/2)}; we take
 *       the one as far from the eye as the drawn point and undo bob and warp. Exact whenever both
 *       passes get the same bob (mods that bob only one pass leave the same mismatch vanilla has).</li>
 *   <li><b>Camera anchor.</b> The hand pass is drawn relative to the camera, so the point is placed
 *       relative to it too (the camera follows the smoothed eye height when crouching, swimming or
 *       gliding; mods may move it).</li>
 * </ol>
 * Vanilla's value is kept when no rod can be on screen: the hand pass didn't draw a hand last frame
 * or earlier this frame (spectator, mods that hide or replace it; see {@link HandPass}) or a panorama
 * is being captured. (While vanilla draws the player's own body, asleep or with a detached camera,
 * {@link ThirdPersonLineOrigin} places the line on the body's rod and this isn't asked.) A HUD
 * hidden with F1 is the exception: it hides the hand but not the line, a world object, which keeps
 * starting where the rod would be while the rest of vanilla's hand gate holds and a hand was drawn
 * when only the HUD decided ({@link HandPass#onlyHudHidesHand}).
 * Vanilla's value is also kept while scoping, where nothing is drawn but the x0.1 world FOV would put
 * the corrected origin inside the scope view (vanilla's guess lands off-screen); when the drawn rod
 * is posed in a way this doesn't model (its hand excluded or using an item, riptide spin, a
 * spear-style stab swing); when no rod has been drawn for the current hook; when the owner isn't the
 * local player, the camera isn't on it or no hand state was extracted; when the world projection
 * isn't a perspective one (an orthographic camera mod); if the result is degenerate (a FOV from
 * another mod out of range); and after an exception, until the next world or dimension.
 * Whether the line is drawn at all is {@link FishingLineVisibility}'s call.
 */
public final class FishingLineOrigin {
    // Vanilla's item-sway factor, from FirstPersonHandsAndItemsRenderer.submitHandsWithItems().
    // If Mojang changes it, mirror it here.
    private static final float ITEM_SWAY_SCALE = 0.1f;

    // GameRenderer.renderLevel's nausea/portal warp, applied to the world projection after the bob:
    // i = max(portal, nausea) * screenEffectScale^2, skew = (5 / (i^2 + 5) - i * 0.04)^2, and the
    // projection is stretched by 1/skew along x turned by spinningEffectAngle about the fixed
    // (0, sqrt(2)/2, sqrt(2)/2) axis: rotate(a, axis) * scale(1/skew, 1, 1) * rotate(-a, axis).
    private static final float     WARP_SKEW_BASE  = 5f;
    private static final float     WARP_SKEW_SLOPE = 0.04f;
    private static final Vector3fc WARP_AXIS       = new Vector3f(0f, Mth.SQRT_OF_TWO / 2f, Mth.SQRT_OF_TWO / 2f);

    // Clearviews (2.x, mod id "clearviews") removes the warp from renderLevel while its
    // "Disable Nausea" option is on. Assumed on (its default); with it off the line keeps vanilla's
    // nausea swing. Undoing a warp that isn't applied would swing the line around the rod.
    private static final boolean CLEARVIEWS_LOADED = FabricLoader.getInstance().isModLoaded("clearviews");

    // The hand the rod was last drawn in, for the hook it was drawn with. The drawn items lag the
    // inventory, so a rod can be held with none drawn yet (a quick F-swap or re-equip while a hook
    // lingers); the line then stays with that hand instead of vanilla's getHoldingArm hand. Only while
    // a rod is held: with none, FishingLineVisibility hides the line of a hook cast from a rod, and
    // any other hook keeps vanilla's. Weak, so a stale hook isn't kept alive. Render-thread only, as
    // is all static state here.
    private static @Nullable InteractionHand lastRodHand;
    private static WeakReference<FishingHook> lastRodHook = new WeakReference<>(null);

    // The world the correction failed in: vanilla's value is kept there, so an incompatible mod or
    // game update degrades to the vanilla line instead of crashing. It retries after the next
    // dimension change or world join (a new ClientLevel). Weak, so a stale level isn't kept alive.
    private static WeakReference<ClientLevel> disabledIn = new WeakReference<>(null);

    // This frame's result (null: vanilla's value), for every later extraction of the local player's
    // hooks in the same HandPass frame: Iris' shadow pass extracts the hook a second time, from the
    // same frame's state, so the result can't differ. No reference to the player or the world.
    private static long originFrame = Long.MIN_VALUE;
    private static @Nullable Vec3 frameOrigin;

    // The HandPass hooks are optional (require = 0), so a failed one is reported here, once. A hand
    // pass that is never seen only counts as failed after this many frames of fishing in first
    // person with the HUD shown (~20 s at 60 fps): until then a hand may simply not have been drawn.
    // Counted once per frame, as compute runs.
    private static final int NO_HAND_PASS_WARNING_FRAMES = 1200;
    private static int framesWithoutHandPass;
    private static boolean handPassWarningLogged;

    private FishingLineOrigin() {}

    /**
     * Corrects vanilla's first-person line origin {@code handPos}. Called only on the first-person
     * branch of {@code getPlayerHandPos}, whichever mod decides that branch.
     */
    public static Vec3 correct(Vec3 handPos, Player owner) {
        Minecraft mc = Minecraft.getInstance();
        if (disabledIn.get() == mc.level || !(owner instanceof LocalPlayer player)) {
            return handPos;
        }
        try {
            // The correction models the player's own view. When the camera sits on another entity
            // (spectating, Freecam-style mods), keep vanilla's value.
            Camera camera = mc.getEntityRenderDispatcher().camera;
            if (camera == null || camera.entity() != player) {
                return handPos;
            }
            // Once per frame (while frames are counted; without, compute keeps vanilla's value).
            long frame = HandPass.frame();
            if (frame != originFrame || !HandPass.framesCounted()) {
                frameOrigin = compute(mc, camera, player);
                originFrame = frame;
            }
            return frameOrigin != null ? frameOrigin : handPos;
        } catch (RuntimeException | LinkageError e) {
            disabledIn = new WeakReference<>(mc.level);
            LOGGER.error("Fishing line correction failed, falling back to vanilla until the next world or dimension", e);
            return handPos;
        }
    }

    private static @Nullable Vec3 compute(Minecraft mc, Camera camera, LocalPlayer player) {
        GameRenderState game = mc.gameRenderer.gameRenderState();
        PlayerRenderState playerState = game.levelRenderState.playerRenderState;
        CameraRenderState cameraState = game.levelRenderState.cameraRenderState;
        AvatarRenderState avatar = playerState.avatarRenderState;
        FirstPersonHandsAndItemsRenderState hands = playerState.firstPersonHandsAndItems;
        // No rod can be on screen: vanilla's value. Except while only the hidden HUD (F1) hides the
        // hand (see HandPass): the line is a world object that vanilla keeps drawing, so it keeps
        // starting where the rod would be.
        if (!HandPass.ranLastFrame() && !HandPass.onlyHudHidesHand()) {
            warnIfHandPassUntracked();
            return null;
        }
        // No hand state was extracted to compute from. Panorama capture is checked directly, on the
        // camera as HandPass and Iris' hand renderer do: its first face still sees the previous normal
        // frame's hand pass. (A mod that flags only the render state, like Pixelshot's large
        // screenshot, merely skips vanilla's hand pass; the line then stays as with F1.)
        if (camera.isPanoramicMode() || avatar == null || hands.handRenderSelection == null) {
            return null;
        }
        // Scoping draws no hand but narrows the world FOV x0.1: the corrected origin would sit inside
        // the scope view, where vanilla's guess lands off-screen.
        if (hands.isScoping) {
            return null;
        }

        // --- 1. The rod as submitHandsWithItems / submitArmWithItem pose it this frame ---
        FishingHook hook = player.fishing;
        InteractionHand hand = drawnRodHand(player, hands);
        boolean rodDrawn = hand != null;
        if (rodDrawn) {
            lastRodHand = hand;
            if (lastRodHook.get() != hook) {
                lastRodHook = new WeakReference<>(hook);
            }
        } else if (lastRodHand != null && hook != null && lastRodHook.get() == hook && holdsRod(player)) {
            hand = lastRodHand;
        } else {
            return null;
        }
        boolean mainHand = hand == InteractionHand.MAIN_HAND;
        HumanoidArm arm = mainHand ? avatar.mainArm : avatar.mainArm.getOpposite();
        float swing = 0f;
        // Only a drawn rod's pose is modelled (the remembered hand still gets its equip dip): with no
        // rod drawn, falling back would jump to vanilla's other hand.
        if (rodDrawn) {
            // submitHandsWithItems / submitArmWithItem's other branches: the hand not drawn, an item in
            // use in that hand (a vanilla rod has no use duration, but a modded rod might), riptide.
            boolean handRendered = mainHand ? hands.handRenderSelection.renderMainHand : hands.handRenderSelection.renderOffHand;
            boolean usingItem = avatar.isUsingItem && hands.useItemRemainingTicks > 0 && avatar.useItemHand == hand;
            if (!handRendered || usingItem || avatar.isAutoSpinAttack) {
                return null;
            }
            LivingEntity.SwingDescription currentSwing = avatar.currentSwing;
            if (currentSwing != null && currentSwing.hand() == hand) {
                switch (currentSwing.animation().type()) {
                    case WHACK -> swing = avatar.swingAnimation;
                    case STAB -> {
                        return null;
                    }
                    default -> {}
                }
            }
        }
        float partialTicks = cameraState.cameraEntityPartialTicks;
        float inverseArmHeight = mainHand
                ? hands.mainHandSwapScale * (1f - Mth.lerp(partialTicks, hands.oldMainHandHeight, hands.mainHandHeight))
                : hands.offHandSwapScale * (1f - Mth.lerp(partialTicks, hands.oldOffHandHeight, hands.offHandHeight));
        Vector3f anchor = FirstPersonRod.lineAnchor(mc, arm, swing, inverseArmHeight);

        // --- 2. Item sway: the pose stack rotates about X then Y, so the point turns about Y first ---
        anchor.rotateY(Mth.DEG_TO_RAD * (hands.viewYRot - hands.yBob) * ITEM_SWAY_SCALE)
              .rotateX(Mth.DEG_TO_RAD * (hands.viewXRot - hands.xBob) * ITEM_SWAY_SCALE);

        // --- 3. View bob and hurt tilt, applied by both passes ---
        GameRendererInvoker gameRenderer = (GameRendererInvoker) mc.gameRenderer;
        PoseStack bobStack = new PoseStack();
        gameRenderer.fishingrodfix$bobHurt(cameraState, bobStack);
        if (game.optionsRenderState.bobView) {
            gameRenderer.fishingrodfix$bobView(cameraState, bobStack);
        }
        Matrix4fc bob = bobStack.last().pose();
        Vector3f drawn = bob.transformPosition(anchor, new Vector3f());

        // --- 4. Same pixel: world tangents = drawn tangents * FOV ratio, at the drawn point's
        // distance from the eye, with the world pass's bob and warp undone (point is rewritten in
        // place: world view target -> view space -> world offset) ---
        // renderLevel projects the world with cameraState.projectionMatrix (bob and warp go onto a
        // copy), a perspective whose m11 is 1 / tan(worldFov / 2). An orthographic one (a camera mod
        // replacing the camera's projection) has no FOV to match; mods that swap only renderLevel's
        // copy (Pixelshot's ortho view) aren't seen here.
        if (cameraState.projectionMatrix.m33() != 0f) {
            return null;
        }
        float worldPerHand = 1f / (cameraState.projectionMatrix.m11() * FirstPersonRod.tanHalf(cameraState.hudFov));
        // A world FOV of 180-360deg (only from another mod) turns the tangent negative: mirrored.
        if (!(worldPerHand > 0f) || !Float.isFinite(worldPerHand)) {
            return null;
        }
        Vector3f point = new Vector3f(drawn.x * worldPerHand, drawn.y * worldPerHand, drawn.z).normalize(drawn.length());
        applyWorldWarp(new Matrix4f(bob), game, playerState).invert().transformPosition(point);

        // --- 5. Camera anchor: view space (x right, y up, z back) to world by the camera's
        // rotation, as Camera.move does ---
        point.rotate(camera.rotation());
        // Defensive: non-finite render-state input from another mod (camera rotation, bob, sway, warp)
        // would make the origin NaN; a FOV ratio that isn't positive and finite (zero or NaN FOV or
        // projection, a lone FOV of 180-360deg) is already rejected above.
        if (!point.isFinite()) {
            return null;
        }
        return camera.position().add(point.x, point.y, point.z);
    }

    /**
     * The hand showing a rod among the drawn items, which lag the inventory during swap animations:
     * FishingHookRenderer.getHoldingArm's hand if a rod is drawn there, else the other one, else null.
     */
    private static @Nullable InteractionHand drawnRodHand(LocalPlayer player, FirstPersonHandsAndItemsRenderState hands) {
        boolean holdingMain = FishingHookRenderer.getHoldingArm(player) == player.getMainArm();
        ItemStack holding = holdingMain ? hands.mainHandItem : hands.offHandItem;
        ItemStack other = holdingMain ? hands.offHandItem : hands.mainHandItem;
        InteractionHand holdingHand = holdingMain ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        InteractionHand otherHand = holdingMain ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        return isRod(holding) ? holdingHand : isRod(other) ? otherHand : null;
    }

    /** Warns once if a HandPass hook seems not to have applied (see {@code NO_HAND_PASS_WARNING_FRAMES}). */
    private static void warnIfHandPassUntracked() {
        if (handPassWarningLogged) {
            return;
        }
        if (!HandPass.framesCounted()) {
            handPassWarningLogged = true;
            LOGGER.warn("Hand pass tracking isn't active (its GameRenderer.extract hook didn't apply); the fishing line stays vanilla");
            return;
        }
        // Only frames where the sampled gate would draw a hand count.
        if (HandPass.handPassSeen() || !HandPass.vanillaDrawsHand()) {
            return;
        }
        if (++framesWithoutHandPass >= NO_HAND_PASS_WARNING_FRAMES) {
            handPassWarningLogged = true;
            LOGGER.warn("No first-person hand pass seen while fishing (its FirstPersonHandsAndItemsRenderer hook didn't apply, "
                    + "or another mod replaces the hand pass); the fishing line stays vanilla");
        }
    }

    /** Applies GameRenderer.renderLevel's nausea/portal warp to {@code matrix} in place (after the bob). */
    private static Matrix4f applyWorldWarp(Matrix4f matrix, GameRenderState game, PlayerRenderState playerState) {
        float screenEffectScale = game.optionsRenderState.screenEffectScale;
        float intensity = Math.max(playerState.portalEffectIntensity, playerState.nauseaEffectIntensity) * (screenEffectScale * screenEffectScale);
        if (intensity > 0f && !CLEARVIEWS_LOADED) {
            float skew = WARP_SKEW_BASE / (intensity * intensity + WARP_SKEW_BASE) - intensity * WARP_SKEW_SLOPE;
            skew *= skew;
            float angle = playerState.spinningEffectAngle * Mth.DEG_TO_RAD;
            matrix.rotate(angle, WARP_AXIS).scale(1f / skew, 1f, 1f).rotate(-angle, WARP_AXIS);
        }
        return matrix;
    }
}
