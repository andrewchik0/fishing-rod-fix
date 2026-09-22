package com.andrewchik.fishingrodfix;

import com.andrewchik.fishingrodfix.mixin.client.CameraAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;

/**
 * Whether vanilla's first-person hand pass reached a hand in the previous frame, i.e. whether any rod
 * could be on screen. Asking the renderer beats re-deriving its conditions: mods decide them in many
 * ways (Better F1 keeps the hand with the HUD hidden, Player Animation Library and freecam mods cancel
 * the pass).
 *
 * <p>Frames are counted where {@code GameRenderer.render} calls {@code updateCamera}: once per rendered
 * frame, before the camera update and the world, and past {@code render}'s {@code skipGameRender}
 * check, through which Dynamic FPS skips most frames while it throttles (a skipped frame counted as
 * one without a hand would put the line at vanilla's value); the hand pass marks the frame when it
 * submits a hand ({@code HeldItemRenderer.renderFirstPersonItem}, reached from vanilla and from Iris's
 * hand renderer). That call is also reached while scoping, where it draws nothing, so
 * {@link FishingLineOrigin} checks scoping itself. The line origin is computed while the world's
 * entities are extracted, before this frame's hand pass, so it reads the previous frame's result: one
 * frame late when the hand appears or disappears (a mod hiding the hand, waking up). The origin is
 * worked out once per frame, at the first extraction of the local player's hook in the frame; later
 * extractions reuse that result.
 *
 * <p>The hidden HUD (F1) needs more: vanilla skips the hand pass then but keeps drawing the line, a
 * world object, so {@link FishingLineOrigin} keeps correcting it where the rod would be. At the start
 * of each frame's {@code renderWorld} (after the tick and the camera update, so for this frame's hand
 * pass) this samples the HUD and the rest of {@code renderHand}'s gate (first person, not rendering a
 * panorama, awake, not spectating; it draws the local player), plus the camera on that player at its
 * eye, not in third person (freecam mods may keep the player as camera entity without moving the
 * camera into third person). Whether a hand is drawn when only the HUD decides is latched, at the next
 * frame's start, from frames that prove it: a drawn hand, or a missing one while the HUD and that gate
 * were open (then a mod hid it). A frame with the gate closed, or one that didn't render the world,
 * proves nothing and restores the default (drawn), so F5, sleep or a freecam before F1 leave no stale
 * answer. So a hidden HUD doesn't move the line: F1 keeps what the line did before, and the gate still
 * applies live. What changes unseen while the HUD is hidden (a mod starting or stopping to hide the
 * hand) only shows once the HUD is back. Render-thread only.
 */
public final class HandPass {
    // Squared distance (blocks) within which the camera counts as at the player's eye. Vanilla's
    // first-person camera sits exactly there (Camera.update), except on an experimental-movement
    // minecart, where it follows the cart's lerp; the slack covers that at normal speeds and mods that
    // nudge the camera.
    private static final double CAMERA_AT_EYE_SQ = 0.5 * 0.5;

    private static long frame;
    // Below frame - 1 until a hand pass has been seen.
    private static long lastHandPassFrame = Long.MIN_VALUE;

    // The latest samples: the HUD hidden, and the rest of the hand gate open. Taken at renderWorld's
    // start; at the next frame's start the latch pairs them with that frame's hand pass, then they are
    // closed until renderWorld samples again (a frame that doesn't render the world draws no hand).
    private static boolean hudHidden;
    private static boolean handGateOpen;
    private static long lastHudHiddenFrame = Long.MIN_VALUE;
    // Whether the hand pass draws a hand when only the HUD decides; assumed until a frame proves
    // otherwise, and restored by any frame with the gate closed.
    private static boolean handDrawnWithHudShown = true;
    // Set for the rest of the session if sampling throws (only another mod could make it).
    private static boolean samplingFailed;

    private HandPass() {}

    /** Called where {@code GameRenderer.render} updates the camera, once per rendered frame. */
    public static void onFrameStart() {
        frame++;
        boolean drew = lastHandPassFrame >= frame - 1;
        if (drew || !handGateOpen) {
            handDrawnWithHudShown = true;
        } else if (!hudHidden) {
            handDrawnWithHudShown = false;
        }
        hudHidden = false;
        handGateOpen = false;
    }

    /** Called at the start of {@code GameRenderer.renderWorld}, after this frame's camera update. */
    public static void onWorldRender() {
        sample();
        if (hudHidden) {
            lastHudHiddenFrame = frame;
        }
    }

    /** Called when the hand pass submits a hand. */
    public static void onHandPass() {
        lastHandPassFrame = frame;
    }

    /** Whether a hand was submitted last frame (or already this frame); false while frames aren't counted. */
    static boolean ranLastFrame() {
        return frame > 0 && lastHandPassFrame >= frame - 1;
    }

    /**
     * Whether only the hidden HUD keeps the hand pass from drawing a hand: the HUD is hidden this frame
     * or was last frame (right after F1 is turned off, this frame's hand pass hasn't run yet), the rest
     * of the hand gate is open this frame, and a hand is drawn when only the HUD decides.
     */
    static boolean onlyHudHidesHand() {
        // Without frame counting the samples can't be told apart by frame: vanilla's value.
        return framesCounted() && lastHudHiddenFrame >= frame - 1 && handGateOpen && handDrawnWithHudShown;
    }

    /** Whether the hand pass would draw a hand this frame, as far as the sampled gate decides. */
    static boolean vanillaDrawsHand() {
        return !hudHidden && handGateOpen;
    }

    /** The current frame's number; 0 until the frame-counting hook has run. */
    static long frame() {
        return frame;
    }

    /** False only if the frame-counting hook never ran, i.e. it didn't apply. */
    static boolean framesCounted() {
        return frame > 0;
    }

    /** False until a hand pass has been seen: its hook didn't apply, or no hand was drawn yet. */
    static boolean handPassSeen() {
        return lastHandPassFrame != Long.MIN_VALUE;
    }

    // Runs in a GameRenderer hook, outside FishingLineOrigin's guard: a failure only turns the sampling
    // off (the HUD then counts as shown and the gate as open, so F1 gives vanilla's value again).
    private static void sample() {
        if (samplingFailed) {
            hudHidden = false;
            handGateOpen = true;
            return;
        }
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            // GameOptions.hudHidden (F1), which renderHand reads later in this frame.
            hudHidden = mc.options.hudHidden;
            handGateOpen = sampleHandGate(mc);
        } catch (RuntimeException | LinkageError e) {
            samplingFailed = true;
            hudHidden = false;
            handGateOpen = true;
            LOGGER.error("Sampling the hidden HUD failed; with F1 the fishing line stays vanilla for this session", e);
        }
    }

    /** renderHand's gate apart from the HUD, plus the camera on the player at its eye, not in third person. */
    private static boolean sampleHandGate(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        Camera camera = mc.gameRenderer.getCamera();
        if (player == null || mc.world == null || camera.getFocusedEntity() != player || camera.isThirdPerson()
                || mc.gameRenderer.isRenderingPanorama() || !mc.options.getPerspective().isFirstPerson() || player.isSleeping()
                || (mc.interactionManager != null && mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR)) {
            return false;
        }
        // Where Camera.update puts a first-person camera, at the partial tick it was updated with.
        float tickProgress = camera.getLastTickProgress();
        CameraAccessor eye = (CameraAccessor) camera;
        Vec3d position = camera.getCameraPos();
        double dx = position.x - MathHelper.lerp(tickProgress, player.lastX, player.getX());
        double dy = position.y - (MathHelper.lerp(tickProgress, player.lastY, player.getY())
                + MathHelper.lerp(tickProgress, eye.fishingrodfix$getLastCameraY(), eye.fishingrodfix$getCameraY()));
        double dz = position.z - MathHelper.lerp(tickProgress, player.lastZ, player.getZ());
        return dx * dx + dy * dy + dz * dz < CAMERA_AT_EYE_SQ;
    }
}
