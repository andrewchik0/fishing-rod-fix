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
import net.minecraft.client.renderer.item.ItemStackRenderState;
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
 * Places the first-person fishing line origin on the rod the hand pass draws (a rod held by a
 * player's body is {@link ThirdPersonLineOrigin}'s).
 *
 * <p>Vanilla's {@code FishingHookRenderer.getPlayerHandPos} guesses from the eye and the options FOV,
 * while the rod is drawn in a separate hand pass with its own FOV and pose. Instead the rod's
 * attachment point ({@link FirstPersonRod}) is put where the hand pass draws it on screen, from the
 * state vanilla extracted for this frame ({@code LevelExtractor.extractPlayerState} and
 * {@code GameRenderer.extractCamera} both run before the level's entities). The world FOV is read from
 * the extracted projection {@code renderLevel} uses, so mods that change the projection itself are
 * followed too. All inputs are this frame's, so the origin is computed once per frame and reused
 * (Iris' shadow pass extracts the local player's hooks a second time).
 * <ol>
 *   <li><b>Rod pose.</b> The drawn rod's hand (or, while a rod is held but none is drawn yet, the
 *       hand it was last drawn in), the attachment point through the display transform
 *       ({@link FirstPersonRod}, {@link RodModel}), the equip dip and the swing, as
 *       {@code FirstPersonHandsAndItemsRenderer.submitArmWithItem} poses an idle or swinging item.</li>
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
 *   <li><b>Camera anchor.</b> The hand pass is drawn relative to the camera (which follows the
 *       smoothed eye height; mods may move it), so the point is placed relative to it too.</li>
 * </ol>
 * Vanilla's value is kept when: no hand was drawn last frame or earlier this frame (spectator, mods
 * hiding the hand; {@link HandPass}), unless only an F1-hidden HUD hides it
 * ({@link HandPass#onlyHudHidesHand}: the line is a world object and keeps starting where the rod
 * would be); a panorama is captured; scoping (the x0.1 world FOV would put the origin inside the scope
 * view, where vanilla's lands off-screen); the drawn rod is posed in a way not modelled (its hand
 * excluded or using an item, riptide, a stab swing); the model can't be read as a rod
 * ({@link RodModel}: no baked geometry, over budget, no grip known, not measured yet); no rod has been
 * drawn for the current hook; the owner isn't the local player, the camera isn't on it or no hand
 * state was extracted; the world projection isn't perspective; the result is degenerate (a FOV out of
 * range from another mod); after an exception, until the next world or dimension. With the camera on
 * the player, such a line only moves onto a body drawn earlier in the same pass
 * ({@link ThirdPersonLineOrigin}). While vanilla draws the player's own body (asleep, detached camera)
 * this isn't asked. Whether the line is drawn is {@link FishingLineVisibility}'s call.
 */
public final class FishingLineOrigin {
    // FirstPersonHandsAndItemsRenderer.submitHandsWithItems' item-sway factor.
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

    // The hand the rod was last drawn in, and its hook. The drawn items lag the inventory, so a rod can
    // be held with none drawn yet (a quick F-swap or re-equip while a hook lingers); the line then
    // stays with that hand instead of vanilla's getHoldingArm hand. Weak, so a stale hook isn't kept
    // alive. Render-thread only, as is all static state here.
    private static @Nullable InteractionHand lastRodHand;
    private static WeakReference<FishingHook> lastRodHook = new WeakReference<>(null);
    // That rod's attachment point (FirstPersonRod.itemAnchor), for the frames it is held but not drawn.
    private static @Nullable Vector3fc lastItemAnchor;

    // The world the correction failed in: vanilla's value there until a new ClientLevel (dimension
    // change or world join). Weak, so a stale level isn't kept alive.
    private static WeakReference<ClientLevel> disabledIn = new WeakReference<>(null);

    // This frame's result (null: vanilla's value), reused by later extractions in the same HandPass
    // frame (Iris' shadow pass). No reference to the player or the world.
    private static long originFrame = Long.MIN_VALUE;
    private static @Nullable Vec3 frameOrigin;

    // The HandPass hooks are optional (require = 0), so a failed one is reported here, once: after
    // this many computed frames of fishing in first person with the HUD shown (~20 s at 60 fps)
    // without a hand pass; until then a hand may simply not have been drawn.
    private static final int NO_HAND_PASS_WARNING_FRAMES = 1200;
    private static int framesWithoutHandPass;
    private static boolean handPassWarningLogged;
    private static boolean warmUpFailureLogged;

    private FishingLineOrigin() {}

    /** Called after a world is joined or the dimension changes: warms {@link RodModel} up. */
    public static void onLevelJoin() {
        try {
            RodModel.warmUp();
        } catch (RuntimeException | LinkageError e) {
            // Only the warm-up is lost: the first cast measures instead.
            if (!warmUpFailureLogged) {
                warmUpFailureLogged = true;
                LOGGER.warn("Could not warm the fishing rod model measurement up; the first cast measures instead", e);
            }
        }
    }

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
            // Only the player's own view is modelled (not spectating, not Freecam-style camera entities).
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
        // No rod can be on screen, unless only the hidden HUD (F1) hides the hand (see HandPass).
        if (!HandPass.ranLastFrame() && !HandPass.onlyHudHidesHand()) {
            warnIfHandPassUntracked();
            return null;
        }
        // Panorama capture is checked on the camera, as HandPass and Iris' hand renderer do: its first
        // face still sees the previous normal frame's hand pass. (A mod that flags only the render
        // state, like Pixelshot's large screenshot, merely skips the hand pass: the line stays as with F1.)
        if (camera.isPanoramicMode() || avatar == null || hands.handRenderSelection == null) {
            return null;
        }
        if (hands.isScoping) {
            return null;
        }

        // --- 1. The rod as submitHandsWithItems / submitArmWithItem pose it this frame ---
        FishingHook hook = player.fishing;
        InteractionHand hand = drawnRodHand(player, hands);
        boolean rodDrawn = hand != null;
        Vector3fc itemAnchor;
        if (rodDrawn) {
            // The drawn model, gripped where the held rod's third-person transform holds it: the same
            // arm's, else the other's (the drawn items lag the inventory through a swap).
            HumanoidArm drawnArm = hand == InteractionHand.MAIN_HAND ? avatar.mainArm : avatar.mainArm.getOpposite();
            boolean right = drawnArm == HumanoidArm.RIGHT;
            ItemStackRenderState grip = isRod(right ? avatar.rightHandItemStack : avatar.leftHandItemStack)
                    ? (right ? avatar.rightHandItemState : avatar.leftHandItemState)
                    : isRod(right ? avatar.leftHandItemStack : avatar.rightHandItemStack)
                    ? (right ? avatar.leftHandItemState : avatar.rightHandItemState)
                    : null;
            itemAnchor = FirstPersonRod.itemAnchor(
                    hand == InteractionHand.MAIN_HAND ? hands.mainHandRenderState : hands.offHandRenderState,
                    hand == InteractionHand.MAIN_HAND ? hands.mainHandItem : hands.offHandItem, grip);
            lastRodHand = hand;
            lastItemAnchor = itemAnchor;
            if (lastRodHook.get() != hook) {
                lastRodHook = new WeakReference<>(hook);
            }
        } else if (lastRodHand != null && hook != null && lastRodHook.get() == hook && holdsRod(player)) {
            hand = lastRodHand;
            itemAnchor = lastItemAnchor;
        } else {
            return null;
        }
        if (itemAnchor == null) {
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
        Vector3f anchor = FirstPersonRod.lineAnchor(arm, swing, inverseArmHeight, itemAnchor);

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
        // distance from the eye, with the world pass's bob and warp undone ---
        // renderLevel projects with cameraState.projectionMatrix (bob and warp go onto a copy), whose
        // m11 is 1 / tan(worldFov / 2). An orthographic one has no FOV to match; mods that swap only
        // renderLevel's copy (Pixelshot's ortho view) aren't seen here.
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
        // Non-finite render-state input from another mod (camera rotation, bob, sway, warp).
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
