package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.GameRendererAccessor;
import com.andrewchik.fishingrodfix.mixin.client.GameRendererInvoker;
import com.andrewchik.fishingrodfix.mixin.client.HeldItemRendererAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.ref.WeakReference;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;
import static com.andrewchik.fishingrodfix.FishingRodFix.holdsRod;
import static com.andrewchik.fishingrodfix.FishingRodFix.isRod;

/**
 * Places the first-person fishing line origin on the rod that is actually drawn (a rod held by a
 * player's body is {@link ThirdPersonLineOrigin}'s).
 *
 * <p>Vanilla's {@code FishingBobberEntityRenderer.getHandPos} guesses the origin from the eye and the
 * options FOV, while the rod is drawn in a separate hand pass with its own FOV and pose, so the two
 * drift apart. Instead we take the line's attachment point on the drawn rod ({@link FirstPersonRod})
 * and put the world point where the hand pass puts that point on screen. The hand pass
 * ({@code HeldItemRenderer.renderItem}, called from {@code GameRenderer.renderHand} at the end of
 * {@code renderWorld}, after the world's entities are extracted) reads the live player and its
 * renderer's own state at the frame's tick progress; nothing ticks between extraction and the hand
 * pass, so the same reads here give the values it draws with. The bob comes from vanilla's own
 * {@code GameRenderer.tiltViewWhenHurt}/{@code bobView}, called as {@code renderWorld} and
 * {@code renderHand} call them this frame, and both FOVs from vanilla's {@code getFov} (see
 * {@link #onWorldFov}), so zoom mods that hook it are followed; the camera pose comes from the live
 * {@code Camera} that frame was set up from. None of it changes within the frame (the player and the
 * renderer's state only in ticks, the camera once per frame), so the origin is worked out once per
 * frame, at the first extraction of the local player's hook, and reused by further extractions in it
 * (Iris' shadow pass extracts the hooks first, before the world's own extraction and its hand
 * pass).
 * <ol>
 *   <li><b>Rod pose.</b> The drawn rod's hand (or, while a rod is held but none is drawn yet, the
 *       hand it was last drawn in), its equip/re-equip dip (after every cast and reel-in) and its
 *       swing, exactly as {@code HeldItemRenderer.renderFirstPersonItem} poses an idle or swinging
 *       item.</li>
 *   <li><b>Item sway.</b> {@code renderItem} rotates the whole hand pass by
 *       {@code (pitch - renderPitch) * 0.1deg} about X and {@code (yaw - renderYaw) * 0.1deg} about
 *       Y.</li>
 *   <li><b>View bob and hurt tilt</b>, which {@code renderHand} applies before the hand, via
 *       vanilla's own {@code tiltViewWhenHurt}/{@code bobView}.</li>
 *   <li><b>Same pixel.</b> The hand pass projects at the hand FOV ({@code getFov} without the FOV
 *       modifier: 70deg, narrowed underwater, in lava and while dying); the world pass at the world
 *       FOV (options FOV times the movement modifier (sprint/speed/flying/bow), fluid and death)
 *       after the same bob and the nausea/portal warp, both at the window's aspect ratio. A view point
 *       lands on the drawn pixel when its tangents are the drawn ones times
 *       {@code tan(worldFov/2) / tan(handFov/2)}; we take the one as far from the eye as the drawn
 *       point and undo bob and warp. Exact whenever both passes get the same bob (mods that bob only
 *       one pass leave the same mismatch vanilla has).</li>
 *   <li><b>Camera anchor.</b> The hand pass is drawn relative to the camera, so the point is placed
 *       relative to it too (the camera follows the smoothed eye height when crouching, swimming or
 *       gliding; mods may move it).</li>
 * </ol>
 * Vanilla's value is kept when no rod can be on screen: the hand pass didn't draw a hand last frame
 * or earlier this frame (spectator, mods that hide or replace it; see {@link HandPass}) or a panorama
 * is being captured. (While vanilla draws the player's own body, asleep or with a third-person
 * camera, {@link ThirdPersonLineOrigin} places the line on the body's rod and this isn't asked. Where
 * this keeps vanilla's value with the camera on the player, {@link ThirdPersonLineOrigin} only moves
 * the line onto a body drawn earlier in the same pass: a mod's first-person body, or Iris' shadow pass
 * for the shadow's line.) A HUD hidden with F1 is the exception: it hides the hand but not the line, a
 * world object, which keeps starting where the rod would be while the rest of vanilla's hand gate
 * holds and a hand was drawn when only the HUD decided ({@link HandPass#onlyHudHidesHand}).
 * Vanilla's value is also kept while scoping, where nothing is drawn but the x0.1 world FOV would put
 * the corrected origin inside the scope view (vanilla's guess lands off-screen); when the drawn rod
 * is posed in a way this doesn't model (its hand excluded or using an item, riptide spin; a rod
 * carrying a {@code map_id} component, which renderFirstPersonItem draws as a map instead, is the one
 * such pose that isn't detected - see CLAUDE.md's Known limitations); when no
 * rod has been drawn for the current hook; when the owner isn't the local player or the camera isn't
 * on it; if the result is degenerate (a FOV from another mod out of range); and after an exception,
 * until the next world or dimension. The world projection itself isn't read (1.21.4 keeps no copy of
 * it without the bob and warp), so a mod that changes it rather than {@code getFov} (an orthographic
 * camera, a lens) isn't followed.
 * Whether the line is drawn at all is {@link FishingLineVisibility}'s call.
 */
public final class FishingLineOrigin {
    // Vanilla's item-sway factor, from HeldItemRenderer.renderItem().
    // If Mojang changes it, mirror it here.
    private static final float ITEM_SWAY_SCALE = 0.1f;

    // GameRenderer.renderWorld's nausea/portal warp, applied to the world projection after the bob:
    // i = nauseaIntensity * distortionEffectScale^2, skew = (5 / (i^2 + 5) - i * 0.04)^2, and the
    // projection is stretched by 1/skew along x turned by the spin angle
    // a = (ticks + tickDelta) * (nausea effect held ? 7 : 20) degrees about the fixed
    // (0, sqrt(2)/2, sqrt(2)/2) axis: rotate(a, axis) * scale(1/skew, 1, 1) * rotate(-a, axis).
    // ClientPlayerEntity.nauseaIntensity covers the portal and the nausea effect alike here, and the
    // spin speed is picked from the live effect; 1.21.5 split the two into a nauseaEffectTime /
    // nauseaEffectSpeed pair on GameRenderer and maxed the intensity with getEffectFadeFactor(NAUSEA).
    private static final float     WARP_SKEW_BASE       = 5f;
    private static final float     WARP_SKEW_SLOPE      = 0.04f;
    private static final float     WARP_NAUSEA_SPIN_DEG = 7f;
    private static final float     WARP_PORTAL_SPIN_DEG = 20f;
    private static final Vector3fc WARP_AXIS            = new Vector3f(0f, MathHelper.SQUARE_ROOT_OF_TWO / 2f, MathHelper.SQUARE_ROOT_OF_TWO / 2f);

    // Clearviews (2.x, mod id "clearviews") removes the warp from renderWorld while its
    // "Disable Nausea" option is on. Assumed on (its default); with it off the line keeps vanilla's
    // nausea swing. Undoing a warp that isn't applied would swing the line around the rod. With an
    // Iris shader pack in use, Iris applies the warp itself (in the model view), where Clearviews can't
    // remove it: the line then swings (a Known limitation).
    private static final boolean CLEARVIEWS_LOADED = FabricLoader.getInstance().isModLoaded("clearviews");

    // The hand the rod was last drawn in, for the hook it was drawn with. The drawn items lag the
    // inventory, so a rod can be held with none drawn yet (a quick F-swap or re-equip while a hook
    // lingers); the line then stays with that hand instead of vanilla's getArmHoldingRod hand. Only
    // while a rod is held: with none, FishingLineVisibility hides the line of a hook cast from a rod,
    // and any other hook keeps vanilla's. Weak, so a stale hook isn't kept alive. Render-thread only,
    // as is all static state here.
    private static @Nullable Hand lastRodHand;
    private static WeakReference<FishingBobberEntity> lastRodHook = new WeakReference<>(null);

    // The world the correction failed in: vanilla's value is kept there, so an incompatible mod or
    // game update degrades to the vanilla line instead of crashing. It retries after the next
    // dimension change or world join (a new ClientWorld). Weak, so a stale world isn't kept alive.
    private static WeakReference<ClientWorld> disabledIn = new WeakReference<>(null);

    // This frame's result (null: vanilla's value), for every later extraction of the local player's
    // hooks in the same HandPass frame: Iris' shadow pass extracts the hook first, inside
    // WorldRenderer.render before the world's own extraction, from the same frame's state (nothing it
    // reads ticks or changes in between), so the result can't differ. No reference to the player or
    // the world.
    private static long originFrame = Long.MIN_VALUE;
    private static @Nullable Vec3d frameOrigin;

    // The FOVs vanilla projects with, from its own getFov calls (GameRendererMixin), with the
    // HandPass frame they were taken in: the world's from renderWorld's only one, the hand's from
    // renderHand's only one (1.21.4 computes it there; 1.21.6 moved it up into renderWorld). Each getFov call probes the camera's surroundings for fluid
    // (Camera.getSubmersionType: about a dozen world lookups and ~1 KB of garbage), so compute reuses
    // them; where it can't (entering water or lava, dying, a zoom changing, or these optional hooks
    // blocked) it asks getFov twice instead, which makes those the mod's most expensive frames, one
    // pair of calls each. renderWorld asks for the world FOV before the entities are extracted, so it is this
    // frame's; renderHand, which it calls at its end after them, asks for the hand FOV, so that one is
    // last frame's. With the world FOV goes its shared
    // factor: the world FOV over its options-FOV-times-movement-modifier base, i.e. everything getFov
    // applies on top of that base (the death and fluid factors, which the hand FOV shares, and any
    // zoom a mod multiplies in); with the hand FOV goes the shared factor of the same frame.
    private static long worldFovFrame = Long.MIN_VALUE;
    private static float capturedWorldFov;
    private static float capturedSharedFactor;
    private static long handFovFrame = Long.MIN_VALUE;
    private static float capturedHandFov;
    private static float handFovSharedFactor;
    // Relative change of the shared factor below which the hand FOV counts as unchanged: the float
    // rounding of the factor's division (~1e-7) stays below it, while a real step (water or lava, the
    // death zoom, even at 1000 fps) is well above it; a change this small would move the hand FOV by
    // less than it, and only for one frame.
    private static final float SHARED_FACTOR_TOLERANCE = 1e-5f;
    // Set for the session if reading the FOV state throws (only another mod could make it): the world
    // FOV is still captured, the shared factor isn't, so compute asks getFov for the hand FOV.
    private static boolean fovCaptureFailed;

    // The HandPass hooks are optional (require = 0), so a failed one is reported here, once. A hand
    // pass that is never seen only counts as failed after this many frames of fishing in first
    // person with the HUD shown (~20 s at 60 fps): until then a hand may simply not have been drawn.
    // Counted once per frame, as compute runs.
    private static final int NO_HAND_PASS_WARNING_FRAMES = 1200;
    private static int framesWithoutHandPass;
    private static boolean handPassWarningLogged;

    private FishingLineOrigin() {}

    /**
     * Called with the world FOV {@code renderWorld} got from {@code getFov}, before the entities are
     * extracted, at the tick progress it asked with.
     */
    public static void onWorldFov(float fov, float tickDelta) {
        worldFovFrame = HandPass.frame();
        capturedWorldFov = fov;
        // NaN never matches: no reuse of the hand FOV unless the factor is known.
        capturedSharedFactor = Float.NaN;
        if (fovCaptureFailed) {
            return;
        }
        try {
            // GameRenderer.getFov's base with changingFov: the options FOV times the lerped movement
            // modifier, the same float operations, so the factor is exactly 1 with nothing else applied.
            MinecraftClient mc = MinecraftClient.getInstance();
            GameRendererAccessor fovState = (GameRendererAccessor) mc.gameRenderer;
            float base = mc.options.getFov().getValue().intValue()
                    * MathHelper.lerp(tickDelta, fovState.fishingrodfix$getLastFovMultiplier(), fovState.fishingrodfix$getFovMultiplier());
            capturedSharedFactor = fov / base;
        } catch (RuntimeException | LinkageError e) {
            fovCaptureFailed = true;
            LOGGER.error("Reading the FOV state failed; the fishing line asks getFov for the hand FOV for this session", e);
        }
    }

    /**
     * Called with the hand FOV {@code renderHand} got from {@code getFov} - at the end of
     * {@code renderWorld}, after the entities are extracted.
     */
    public static void onHandFov(float fov) {
        long frame = HandPass.frame();
        handFovFrame = frame;
        capturedHandFov = fov;
        handFovSharedFactor = worldFovFrame == frame ? capturedSharedFactor : Float.NaN;
    }

    /**
     * Corrects vanilla's first-person line origin {@code handPos}. Called only on the first-person
     * branch of {@code getHandPos}, whichever mod decides that branch.
     */
    public static Vec3d correct(Vec3d handPos, PlayerEntity owner) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (disabledIn.get() == mc.world || !(owner instanceof ClientPlayerEntity player)) {
            return handPos;
        }
        try {
            // The correction models the player's own view. When the camera sits on another entity
            // (spectating, Freecam-style mods), keep vanilla's value.
            Camera camera = mc.getEntityRenderDispatcher().camera;
            if (camera == null || camera.getFocusedEntity() != player) {
                return handPos;
            }
            // Panorama capture (MinecraftClient.takePanorama) renders the world six times without a
            // frame of its own, so it is checked before the per-frame result, which its faces would
            // otherwise reuse. Checked on the renderer, as HandPass and Iris' hand renderer do: its
            // first face still sees the previous normal frame's hand pass.
            if (mc.gameRenderer.isRenderingPanorama()) {
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
            disabledIn = new WeakReference<>(mc.world);
            LOGGER.error("Fishing line correction failed, falling back to vanilla until the next world or dimension", e);
            return handPos;
        }
    }

    private static @Nullable Vec3d compute(MinecraftClient mc, Camera camera, ClientPlayerEntity player) {
        HeldItemRendererAccessor hands = (HeldItemRendererAccessor) mc.getEntityRenderDispatcher().getHeldItemRenderer();
        // No rod can be on screen: vanilla's value. Except while only the hidden HUD (F1) hides the
        // hand (see HandPass): the line is a world object that vanilla keeps drawing, so it keeps
        // starting where the rod would be.
        if (!HandPass.ranLastFrame() && !HandPass.onlyHudHidesHand()) {
            warnIfHandPassUntracked();
            return null;
        }
        // Scoping draws no hand but narrows the world FOV x0.1: the corrected origin would sit inside
        // the scope view, where vanilla's guess lands off-screen.
        if (player.isUsingSpyglass()) {
            return null;
        }

        // --- 1. The rod as renderItem / renderFirstPersonItem pose it this frame ---
        FishingBobberEntity hook = player.fishHook;
        Hand hand = drawnRodHand(player, hands);
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
        boolean mainHand = hand == Hand.MAIN_HAND;
        Arm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
        // renderWorld hands renderHand, and so the hand pass, the frame's tick progress
        // (RenderTickCounter.getTickDelta(true)); panorama capture, the only other caller, is
        // excluded in correct.
        float tickDelta = mc.getRenderTickCounter().getTickDelta(true);
        float swing = 0f;
        // Only a drawn rod's pose is modelled (the remembered hand still gets its equip dip): with no
        // rod drawn, falling back would jump to vanilla's other hand.
        if (rodDrawn) {
            // renderItem / renderFirstPersonItem's other branches: the hand not drawn, an item in use
            // in that hand (a vanilla rod has no use duration, but a modded rod might), riptide.
            boolean usingItem = player.isUsingItem() && player.getItemUseTimeLeft() > 0 && player.getActiveHand() == hand;
            if (!handRendered(player, hand) || usingItem || player.isUsingRiptide()) {
                return null;
            }
            // renderItem swings only the attacking hand (preferredHand, the main hand until the first
            // swing); the other hand gets swing progress 0. On 1.21.4 every idle item swings the
            // same way (swingArm; no swing animations yet).
            Hand attackHand = player.preferredHand != null ? player.preferredHand : Hand.MAIN_HAND;
            if (attackHand == hand) {
                swing = player.getHandSwingProgress(tickDelta);
            }
        }
        // renderItem's equip dip (1.21.4 has no per-item swap animation scale).
        float inverseArmHeight = 1f - (mainHand
                ? MathHelper.lerp(tickDelta, hands.fishingrodfix$getPrevEquipProgressMainHand(), hands.fishingrodfix$getEquipProgressMainHand())
                : MathHelper.lerp(tickDelta, hands.fishingrodfix$getPrevEquipProgressOffHand(), hands.fishingrodfix$getEquipProgressOffHand()));
        Vector3f anchor = FirstPersonRod.lineAnchor(mc, arm, swing, inverseArmHeight);

        // --- 2. Item sway: the pose stack rotates about X then Y, so the point turns about Y first ---
        float renderPitch = MathHelper.lerp(tickDelta, player.lastRenderPitch, player.renderPitch);
        float renderYaw = MathHelper.lerp(tickDelta, player.lastRenderYaw, player.renderYaw);
        anchor.rotateY(MathHelper.RADIANS_PER_DEGREE * (player.getYaw(tickDelta) - renderYaw) * ITEM_SWAY_SCALE)
              .rotateX(MathHelper.RADIANS_PER_DEGREE * (player.getPitch(tickDelta) - renderPitch) * ITEM_SWAY_SCALE);

        // --- 3. View bob and hurt tilt, applied by both passes ---
        GameRendererInvoker gameRenderer = (GameRendererInvoker) mc.gameRenderer;
        MatrixStack bobStack = new MatrixStack();
        gameRenderer.fishingrodfix$tiltViewWhenHurt(bobStack, tickDelta);
        if (mc.options.getBobView().getValue()) {
            gameRenderer.fishingrodfix$bobView(bobStack, tickDelta);
        }
        Matrix4fc bob = bobStack.peek().getPositionMatrix();
        Vector3f drawn = bob.transformPosition(anchor, new Vector3f());

        // --- 4. Same pixel: world tangents = drawn tangents * FOV ratio, at the drawn point's
        // distance from the eye, with the world pass's bob and warp undone (point is rewritten in
        // place: world view target -> view space -> world offset) ---
        // renderWorld projects the world at getFov(camera, tickDelta, true), and renderHand - which
        // it calls at its end - projects the hand at getFov(camera, tickDelta, false), both at the
        // window's aspect. The world FOV is this frame's own. The hand FOV is asked for after the
        // entities, so the one taken last frame is used while the shared factor (see capturedSharedFactor) is the same as then: the
        // hand FOV is 70deg times that factor's vanilla part, and the movement modifier (sprint, speed,
        // flying, a bow) only moves the world FOV. Otherwise (entering or leaving water or lava, dying,
        // a zoom changing) getFov is asked, as it is without the captures (their hooks are optional).
        long frame = HandPass.frame();
        boolean worldFovCaptured = worldFovFrame == frame;
        float worldFov = worldFovCaptured ? capturedWorldFov : gameRenderer.fishingrodfix$getFov(camera, tickDelta, true);
        float handFov = worldFovCaptured && handFovFrame == frame - 1
                && Math.abs(capturedSharedFactor - handFovSharedFactor) <= SHARED_FACTOR_TOLERANCE * Math.abs(handFovSharedFactor)
                ? capturedHandFov
                : gameRenderer.fishingrodfix$getFov(camera, tickDelta, false);
        float worldPerHand = FirstPersonRod.tanHalf(worldFov) / FirstPersonRod.tanHalf(handFov);
        // A world FOV of 180-360deg (only from another mod) turns the tangent negative: mirrored.
        if (!(worldPerHand > 0f) || !Float.isFinite(worldPerHand)) {
            return null;
        }
        Vector3f point = new Vector3f(drawn.x * worldPerHand, drawn.y * worldPerHand, drawn.z).normalize(drawn.length());
        applyWorldWarp(new Matrix4f(bob), mc, player, tickDelta).invert().transformPosition(point);

        // --- 5. Camera anchor: view space (x right, y up, z back) to world by the camera's
        // rotation, as Camera.moveBy does ---
        point.rotate(camera.getRotation());
        // Defensive: non-finite input from another mod (camera rotation, bob, sway, warp) would make
        // the origin NaN; a FOV ratio that isn't positive and finite (zero or NaN FOV, a lone FOV of
        // 180-360deg) is already rejected above.
        if (!point.isFinite()) {
            return null;
        }
        return camera.getPos().add(point.x, point.y, point.z);
    }

    /**
     * The hand showing a rod among the drawn items, which lag the inventory during swap animations:
     * FishingBobberEntityRenderer.getArmHoldingRod's hand if a rod is drawn there, else the other one,
     * else null.
     */
    private static @Nullable Hand drawnRodHand(ClientPlayerEntity player, HeldItemRendererAccessor hands) {
        boolean holdingMain = FishingBobberEntityRenderer.getArmHoldingRod(player) == player.getMainArm();
        ItemStack holding = holdingMain ? hands.fishingrodfix$getMainHand() : hands.fishingrodfix$getOffHand();
        ItemStack other = holdingMain ? hands.fishingrodfix$getOffHand() : hands.fishingrodfix$getMainHand();
        Hand holdingHand = holdingMain ? Hand.MAIN_HAND : Hand.OFF_HAND;
        Hand otherHand = holdingMain ? Hand.OFF_HAND : Hand.MAIN_HAND;
        return isRod(holding) ? holdingHand : isRod(other) ? otherHand : null;
    }

    /**
     * Whether the hand pass draws {@code hand}: HeldItemRenderer.getHandRenderType, mirrored (it is
     * package-private and returns a package-private enum, which neither an invoker nor a shadow can
     * name). Only a bow or crossbow in either hand leaves one hand out.
     */
    private static boolean handRendered(ClientPlayerEntity player, Hand hand) {
        ItemStack mainHandStack = player.getMainHandStack();
        ItemStack offHandStack = player.getOffHandStack();
        boolean holdsBow = mainHandStack.isOf(Items.BOW) || offHandStack.isOf(Items.BOW);
        boolean holdsCrossbow = mainHandStack.isOf(Items.CROSSBOW) || offHandStack.isOf(Items.CROSSBOW);
        if (!holdsBow && !holdsCrossbow) {
            return true;
        }
        // Vanilla: RENDER_MAIN_HAND_ONLY with a charged crossbow in the main hand, else both.
        if (!player.isUsingItem()) {
            return hand == Hand.MAIN_HAND || !isChargedCrossbow(mainHandStack);
        }
        ItemStack activeItem = player.getActiveItem();
        Hand activeHand = player.getActiveHand();
        // Vanilla (getUsingItemHandRenderType): using something else, RENDER_MAIN_HAND_ONLY with it in
        // the main hand and a charged crossbow in the off hand, else both; drawing a bow or crossbow,
        // only that hand.
        if (!activeItem.isOf(Items.BOW) && !activeItem.isOf(Items.CROSSBOW)) {
            return hand == Hand.MAIN_HAND || !(activeHand == Hand.MAIN_HAND && isChargedCrossbow(offHandStack));
        }
        return hand == activeHand;
    }

    private static boolean isChargedCrossbow(ItemStack item) {
        return item.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(item);
    }

    /** Warns once if a HandPass hook seems not to have applied (see {@code NO_HAND_PASS_WARNING_FRAMES}). */
    private static void warnIfHandPassUntracked() {
        if (handPassWarningLogged) {
            return;
        }
        if (!HandPass.framesCounted()) {
            handPassWarningLogged = true;
            LOGGER.warn("Hand pass tracking isn't active (its GameRenderer.render hook didn't apply); the fishing line stays vanilla");
            return;
        }
        // Only frames where the sampled gate would draw a hand count.
        if (HandPass.handPassSeen() || !HandPass.vanillaDrawsHand()) {
            return;
        }
        if (++framesWithoutHandPass >= NO_HAND_PASS_WARNING_FRAMES) {
            handPassWarningLogged = true;
            LOGGER.warn("No first-person hand pass seen while fishing (its HeldItemRenderer hook didn't apply, "
                    + "or another mod replaces the hand pass); the fishing line stays vanilla");
        }
    }

    /** Applies GameRenderer.renderWorld's nausea/portal warp to {@code matrix} in place (after the bob). */
    private static Matrix4f applyWorldWarp(Matrix4f matrix, MinecraftClient mc, ClientPlayerEntity player, float tickDelta) {
        // renderWorld's own tick delta for it: the frame's (see compute).
        float distortionEffectScale = mc.options.getDistortionEffectScale().getValue().floatValue();
        float intensity = MathHelper.lerp(tickDelta, player.prevNauseaIntensity, player.nauseaIntensity)
                * (distortionEffectScale * distortionEffectScale);
        if (intensity > 0f && !CLEARVIEWS_LOADED) {
            float skew = WARP_SKEW_BASE / (intensity * intensity + WARP_SKEW_BASE) - intensity * WARP_SKEW_SLOPE;
            skew *= skew;
            GameRendererAccessor spin = (GameRendererAccessor) mc.gameRenderer;
            float degreesPerTick = player.hasStatusEffect(StatusEffects.NAUSEA) ? WARP_NAUSEA_SPIN_DEG : WARP_PORTAL_SPIN_DEG;
            float angle = (spin.fishingrodfix$getTicks() + tickDelta) * degreesPerTick * MathHelper.RADIANS_PER_DEGREE;
            matrix.rotate(angle, WARP_AXIS).scale(1f / skew, 1f, 1f).rotate(-angle, WARP_AXIS);
        }
        return matrix;
    }
}
