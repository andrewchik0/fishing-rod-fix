package com.andrewchik.fishingrodfix;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;
import static com.andrewchik.fishingrodfix.FishingRodFix.isRod;

/**
 * Places the fishing line origin on the rod a player's body is drawn holding: every line not on the
 * first-person rod, i.e. every player {@code FishingHookRenderer.getPlayerHandPos} sends down its
 * third-person branch, the local player whose own body vanilla draws in first person
 * ({@link #bodyDrawnInFirstPerson}), and, on the first-person branch, a body a mod draws (see the
 * last paragraph).
 *
 * <p>Vanilla's guess is a fixed offset from the eye turned by the body yaw, which ignores the model's
 * pose (MC-4490, MC-176514, MC-198777, MC-247425, MC-270173, MC-270174). Instead the rod is read where
 * {@code ItemInHandLayer.submitArmWithItem} submits it for a fishing player: the pose it submits with
 * (whatever vanilla and other mods made of body, arm and item) places {@link ThirdPersonRod}'s
 * attachment point in the collector's space, tagged with the pass (the level pass's own collector,
 * noted around its entity loop since a renderer may hand its layers a wrapped one, plus the
 * {@link HandPass} frame). The tag holds while a collector serves one pass per frame, as vanilla's level
 * pass, its picture-in-picture renderers and Iris' shadow pass do. Entities submit in the client's add
 * order, owner before its hooks; a hook submitted in the same pass takes that point through the
 * inverse of its own pose (so a pass that turns its root cancels out). The offset goes straight into
 * {@code submit}'s locals; {@code lineOriginOffset} keeps vanilla's value.
 *
 * <p>Where the rod isn't drawn in this pass before the hook (owner out of view, a hook that arrived
 * before its owner, a body drawn after the hooks as in Real Camera's classic mode), the line starts
 * where the rod was last drawn, relative to the body's position and turned with its yaw, while the
 * owner still holds a rod in that arm in the same pose and riding state; else vanilla's value. Being
 * relative to the body's position, that spot also carries the reading frame's
 * {@code AvatarRenderer.getRenderOffset}: the crouch shift (kept in step by the pose gate) and, for a
 * passenger of an experimental-movement minecart, a fraction of a block of cart lerp correction.
 *
 * <p>A first-person line {@link FishingLineOrigin} left at vanilla's value with the camera on the
 * player only moves onto a body drawn earlier in the same pass (Player Animation Library's
 * first-person model, Iris' shadow body), never the remembered spot: no body is on screen, and an F5
 * view just left or the shadow pass's body would start the visible line at a rod that isn't drawn.
 * With the camera on another entity a drawn local body is a mod's and may come after the hooks
 * (Freecam's Show Player): that line keeps the remembered spot. The same same-pass-only rule holds for
 * a line whose {@code getPlayerHandPos} another mod replaced at HEAD ({@link #onHandPosBody} not
 * reached: Vivecraft's VR hands, Mine Little Pony's ponies): that mod's value stands otherwise.
 */
public final class ThirdPersonLineOrigin {
    /** Mixed into {@code AbstractClientPlayer}: the player's rod as last drawn, created on first use. */
    public interface Owner {
        @Nullable BodyRod fishingrodfix$bodyRod();

        void fishingrodfix$setBodyRod(BodyRod rod);
    }

    /**
     * Mixed into {@code FishingHookRenderState}: the owner whose drawn rod the line moves onto at
     * submission (null for a line on the first-person rod, or with no player owner), the owner's
     * partial tick, and whether only a rod drawn earlier in the same pass counts (see
     * {@link #onFirstPersonVanilla} and {@link #onHandPosBody}). The owner is dropped when the state is submitted: other mods
     * may keep render states past their frame (Iris' shadow pass keeps its last frame's until its next
     * one, also after a disconnect), and the owner would keep its world alive.
     */
    public interface HookState {
        @Nullable AbstractClientPlayer fishingrodfix$bodyRodOwner();

        float fishingrodfix$ownerPartialTicks();

        boolean fishingrodfix$samePassOnly();

        void fishingrodfix$setBodyRodOwner(@Nullable AbstractClientPlayer owner, float ownerPartialTicks, boolean samePassOnly);
    }

    /** Where a player's rod was drawn. Render-thread only, as is all state here. */
    public static final class BodyRod {
        // The latest submission: the tip in the collector's space and the pass it was drawn in, with
        // the arm, and the body's position, yaw, pose and riding it was drawn with.
        private final Vector3f tip = new Vector3f();
        private @Nullable SubmitNodeCollector collector;
        private long frame;
        private HumanoidArm arm = HumanoidArm.RIGHT;
        private double bodyX;
        private double bodyY;
        private double bodyZ;
        private float bodyYaw;
        private Pose pose = Pose.STANDING;
        private boolean riding;
        // The tip relative to the body's position at the yaw it was drawn at, from the latest
        // submission whose pass a hook gave the world position of; no arm until then.
        private final Vector3f offset = new Vector3f();
        private float offsetYaw;
        private @Nullable HumanoidArm offsetArm;
        private Pose offsetPose = Pose.STANDING;
        private boolean offsetRiding;
    }

    // stringVertex draws the line 0.25 above the hook's position, and getPlayerHandPos' result is
    // turned into lineOriginOffset relative to that point (extractRenderState: hookPos + (0, 0.25, 0)).
    private static final float LINE_Y_OFFSET = 0.25f;

    // LivingEntityRenderer.solveBodyRot: a rider's body turns with its head, within these limits of
    // the mount's body.
    private static final float RIDER_MAX_HEAD_DIFF  = 85f;
    private static final float RIDER_TURN_THRESHOLD = 50f;
    private static final float RIDER_TURN_FACTOR    = 0.2f;

    // The frame in which a hook with a body-held line was last extracted: rods are only read in such
    // frames, so every other frame costs the item layer one compare per held item.
    private static long bodyHookFrame = Long.MIN_VALUE;

    // Whether the hook being extracted took getPlayerHandPos' first-person branch with the camera on its
    // owner and FishingLineOrigin kept vanilla's value there. Valid from inside extractRenderState to
    // its end.
    private static boolean firstPersonVanilla;

    // Whether the hook being extracted got its getPlayerHandPos value from vanilla's method body. A
    // mod that replaces the method at HEAD (Vivecraft: a VR controller or tracked hand; Mine Little
    // Pony: every pony) places the line itself: no remembered spot may override it, only a body drawn
    // earlier in the same pass.
    private static boolean handPosBodyRan;

    // The pass of the latest hook submitted (its collector and frame), with that hook's world
    // position and the inverse of its pose: together they turn a point in the pass's space into the
    // world, for a rod drawn after its hook. Forgotten at the next frame's start: the collector may
    // be a shadow pass's, whose pipeline Iris replaces on a dimension change or shader reload.
    private static @Nullable SubmitNodeCollector passCollector;
    private static long passFrame;
    private static double passHookX;
    private static double passHookY;
    private static double passHookZ;
    private static final Matrix4f passInverse = new Matrix4f();
    private static final Vector3f scratch = new Vector3f();

    // The line offset for the hook being submitted, written into submit's xa/ya/za locals. Cleared
    // once za is written, and at the next frame's start in case another mod cut submit short, so no
    // render state outlives its submission here.
    private static @Nullable FishingHookRenderState pendingState;
    private static final Vector3f pendingOffset = new Vector3f();

    // The world the correction failed in: vanilla's value there until a new ClientLevel (dimension
    // change or world join). Weak, so a stale level isn't kept alive.
    private static WeakReference<ClientLevel> disabledIn = new WeakReference<>(null);

    // The collector the level pass submits its entities to, while its entity loop runs
    // (LevelRenderer.submitEntities, incl. other mods' injects at its return); null outside it. A
    // reading through a known forwarding wrapper of it is tagged with this instead: Customizable Player
    // Models' per-player wrapper, and Real Camera's binding-mode router (it forwards items to the level
    // collector unchanged). Any other collector keeps its own tag.
    private static @Nullable SubmitNodeCollector entityCollector;
    private static @Nullable Class<?> checkedCollectorClass;
    private static boolean checkedCollectorWraps;

    private ThirdPersonLineOrigin() {}

    /** Called as the level pass's entity loop starts. */
    public static void onEntitiesStart(SubmitNodeCollector collector) {
        entityCollector = collector;
    }

    /** Called as the level pass's entity loop ends. */
    public static void onEntitiesEnd() {
        entityCollector = null;
    }

    /** Whether {@code collector} is a known forwarding wrapper (its class is checked once per class seen). */
    private static boolean isWrapper(SubmitNodeCollector collector) {
        if (collector.getClass() != checkedCollectorClass) {
            checkedCollectorClass = collector.getClass();
            String name = checkedCollectorClass.getName();
            checkedCollectorWraps = name.startsWith("com.tom.cpm.")
                    || name.startsWith("com.xtracr.realcamera.") && name.endsWith(".RoutingSubmitCollector");
        }
        return checkedCollectorWraps;
    }

    /** Called at the start of every frame ({@code GameRenderer.extract}): forgets the last frame's pass. */
    public static void onFrameStart() {
        passCollector = null;
        pendingState = null;
        entityCollector = null;
    }

    /**
     * Whether vanilla draws the local player's own body while it is the first-person camera (as
     * {@code LevelExtractor.extractVisibleEntities} decides): asleep, or with the camera detached by a
     * mod. The line then belongs on the rod in the body's hand, the one in the world (with a detached
     * camera vanilla also draws the hand pass, at the camera).
     */
    public static boolean bodyDrawnInFirstPerson(Player owner) {
        Minecraft mc = Minecraft.getInstance();
        if (disabledIn.get() == mc.level) {
            return false;
        }
        try {
            Camera camera = mc.getEntityRenderDispatcher().camera;
            return owner == mc.player && camera != null && camera.entity() == owner && (camera.isDetached() || owner.isSleeping());
        } catch (RuntimeException | LinkageError e) {
            disable(mc, e);
            return false;
        }
    }

    /** Called at the start of a hook's {@code extractRenderState}. */
    public static void beginExtraction() {
        firstPersonVanilla = false;
        handPosBodyRan = false;
    }

    /** Called as vanilla's {@code getPlayerHandPos} body starts. */
    public static void onHandPosBody() {
        handPosBodyRan = true;
    }

    /**
     * Called from {@code getPlayerHandPos}' first-person branch when {@link FishingLineOrigin} kept
     * vanilla's value and vanilla doesn't draw the body: with the camera on the owner, the line only
     * moves onto a body drawn earlier in the same pass (see the class Javadoc).
     */
    public static void onFirstPersonVanilla(Player owner) {
        Minecraft mc = Minecraft.getInstance();
        if (disabledIn.get() == mc.level) {
            return;
        }
        try {
            Camera camera = mc.getEntityRenderDispatcher().camera;
            firstPersonVanilla = camera != null && camera.entity() == owner;
        } catch (RuntimeException | LinkageError e) {
            disable(mc, e);
        }
    }

    /**
     * Called at the end of a hook's {@code extractRenderState}: the owner whose drawn rod the line
     * moves onto at submission, unless the line is on the first-person rod or hidden.
     */
    public static void onHookExtracted(FishingHookRenderState state, @Nullable Player owner, boolean onFirstPersonRod, boolean hidden,
                                       float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        try {
            HookState hookState = (HookState) state;
            if (onFirstPersonRod || hidden || !(owner instanceof AbstractClientPlayer player) || disabledIn.get() == mc.level) {
                hookState.fishingrodfix$setBodyRodOwner(null, 0f, false);
                return;
            }
            // The owner's partial tick, as LevelExtractor extracts it: players never freeze, so it is
            // the hook's unless the tick rate is frozen.
            float ownerPartialTicks = player.level().tickRateManager().runsNormally()
                    ? partialTicks
                    : mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            // An origin another mod placed before vanilla's method body ran (Vivecraft's controller or
            // tracked hand) is only moved onto a rod drawn earlier in the same pass.
            hookState.fishingrodfix$setBodyRodOwner(player, ownerPartialTicks, firstPersonVanilla || !handPosBodyRan);
            bodyHookFrame = HandPass.frame();
        } catch (RuntimeException | LinkageError e) {
            // This one sits on a require = 1 inject, so a throw would crash. A line left with an owner
            // here drops it unused at its submission.
            disable(mc, e);
        }
    }

    /**
     * Called where {@code ItemInHandLayer.submitArmWithItem} submits a held item, once per non-empty
     * held item of an armed entity: small, so it inlines there; only a rod in an avatar's hand in a
     * frame with a body-held line goes on.
     */
    public static void onItemSubmitted(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm,
                                       PoseStack poseStack, SubmitNodeCollector collector) {
        // stack != null: vanilla always passes one, but isRod reads it and this gate runs outside the
        // exception boundary, so a mod that passed null would throw into the item layer.
        if (bodyHookFrame == HandPass.frame() && state instanceof AvatarRenderState avatar && stack != null && isRod(stack)) {
            readDrawnRod(avatar, item, stack, arm, poseStack, collector);
        }
    }

    /** Records a fishing player's rod as drawn. */
    private static void readDrawnRod(AvatarRenderState avatar, ItemStackRenderState item, ItemStack stack, HumanoidArm arm, PoseStack poseStack,
                                     SubmitNodeCollector collector) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        // Without frame counting a capture couldn't be told from an older one.
        if (level == null || disabledIn.get() == level || !HandPass.framesCounted()) {
            return;
        }
        try {
            // The hook's rod: the arm vanilla draws the cast rod in (FishingHookRenderer.getHoldingArm).
            if (!(level.getEntity(avatar.id) instanceof AbstractClientPlayer player) || player.fishing == null
                    || arm != FishingHookRenderer.getHoldingArm(player)) {
                return;
            }
            // A drawing not measured yet, or a pose that collapses the item (a mod hiding it by scaling
            // it to nothing), leaves the line to an earlier reading or vanilla. One that can't be read
            // as a rod drops the earlier reading too: it was of another model (a mid-cast swap).
            Vector3fc anchor = ThirdPersonRod.lineAnchor(item, stack);
            if (anchor == null) {
                if (((Owner) player).fishingrodfix$bodyRod() instanceof BodyRod rod) {
                    rod.collector = null;
                    rod.offsetArm = null;
                }
                return;
            }
            if (anchor == RodModel.DEFERRED || Math.abs(poseStack.last().pose().determinant3x3()) < 1e-9f) {
                return;
            }
            // The pass's own collector, not one the renderer wrapped for its layers.
            if (entityCollector != null && collector != entityCollector && isWrapper(collector)) {
                collector = entityCollector;
            }
            Owner owner = (Owner) player;
            BodyRod rod = owner.fishingrodfix$bodyRod();
            if (rod == null) {
                rod = new BodyRod();
                owner.fishingrodfix$setBodyRod(rod);
            }
            poseStack.last().pose().transformPosition(anchor, rod.tip);
            rod.collector = collector;
            rod.frame = HandPass.frame();
            rod.arm = arm;
            rod.bodyX = avatar.x;
            rod.bodyY = avatar.y;
            rod.bodyZ = avatar.z;
            rod.bodyYaw = avatar.bodyRot;
            rod.pose = avatar.pose;
            rod.riding = avatar.isPassenger;
            // Drawn after a hook of this pass: that hook gives the world position to remember it at.
            if (collector == passCollector && rod.frame == passFrame) {
                Vector3f local = passInverse.transformPosition(rod.tip, scratch);
                remember(rod, passHookX + local.x, passHookY + local.y, passHookZ + local.z);
            }
        } catch (RuntimeException | LinkageError e) {
            disable(mc, e);
        }
    }

    /**
     * Called at the start of a hook's {@code submit}: works out where its line starts, on the owner's
     * rod drawn earlier in this pass or (not for a same-pass-only line: {@link #onFirstPersonVanilla},
     * {@link #onHandPosBody} not reached) where it was last drawn, for {@link #lineOffset}.
     */
    public static void onHookSubmitted(FishingHookRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        pendingState = null;
        HookState hookState = (HookState) state;
        AbstractClientPlayer owner = hookState.fishingrodfix$bodyRodOwner();
        if (owner == null) {
            return;
        }
        float ownerPartialTicks = hookState.fishingrodfix$ownerPartialTicks();
        boolean samePassOnly = hookState.fishingrodfix$samePassOnly();
        // Only this submission needs the owner (see HookState).
        hookState.fishingrodfix$setBodyRodOwner(null, 0f, false);
        Minecraft mc = Minecraft.getInstance();
        if (disabledIn.get() == mc.level) {
            return;
        }
        try {
            // The hook's pose maps its local frame (world axes, origin at the hook) into the pass's
            // space; the inverse brings a drawn rod back into it.
            poseStack.last().pose().invertAffine(passInverse);
            passCollector = collector;
            passFrame = HandPass.frame();
            passHookX = state.x;
            passHookY = state.y;
            passHookZ = state.z;
            BodyRod rod = ((Owner) owner).fishingrodfix$bodyRod();
            if (rod == null) {
                return;
            }
            Vector3f local;
            if (rod.collector == collector && rod.frame == passFrame) {
                local = passInverse.transformPosition(rod.tip, scratch);
                remember(rod, state.x + local.x, state.y + local.y, state.z + local.z);
            } else if (samePassOnly) {
                return;
            } else {
                local = remembered(owner, rod, ownerPartialTicks, state);
                if (local == null) {
                    return;
                }
            }
            if (local.isFinite()) {
                pendingOffset.set(local.x, local.y - LINE_Y_OFFSET, local.z);
                pendingState = state;
            }
        } catch (RuntimeException | LinkageError e) {
            disable(mc, e);
        }
    }

    /**
     * {@code submit}'s line offset component {@code axis} (0 = x, 1 = y, 2 = z) for {@code state}:
     * the one {@link #onHookSubmitted} worked out, or vanilla's {@code value}.
     */
    public static float lineOffset(FishingHookRenderState state, int axis, float value) {
        if (state != pendingState) {
            return value;
        }
        // za is submit's last offset local.
        if (axis == 2) {
            pendingState = null;
        }
        return pendingOffset.get(axis);
    }

    /**
     * Where the owner's rod was last drawn, relative to the body now, relative to the hook; null when
     * nothing usable was drawn (another arm, pose or mount since, or no rod held).
     */
    private static @Nullable Vector3f remembered(Player owner, BodyRod rod, float partialTicks, FishingHookRenderState state) {
        HumanoidArm arm = rod.offsetArm;
        if (arm == null || arm != FishingHookRenderer.getHoldingArm(owner) || !isRod(owner.getItemHeldByArm(arm))
                || owner.getPose() != rod.offsetPose || owner.isPassenger() != rod.offsetRiding) {
            return null;
        }
        // The body turns about its position by 180 - yaw (LivingEntityRenderer.setupRotations), except
        // asleep, where it turns by the bed's direction: a reading taken asleep is in bed space and
        // isn't turned (the gate above matched the pose).
        Vector3f offset = scratch.set(rod.offset);
        if (rod.offsetPose != Pose.SLEEPING) {
            offset.rotateY(Mth.DEG_TO_RAD * (rod.offsetYaw - bodyYaw(owner, partialTicks)));
        }
        return offset.set(
                (float) (Mth.lerp(partialTicks, owner.xOld, owner.getX()) - state.x + offset.x),
                (float) (Mth.lerp(partialTicks, owner.yOld, owner.getY()) - state.y + offset.y),
                (float) (Mth.lerp(partialTicks, owner.zOld, owner.getZ()) - state.z + offset.z));
    }

    /**
     * Remembers the drawn tip, at world position {@code (x, y, z)}, relative to the body it was drawn
     * with: its position, without the offset the renderer drew it at (see the class Javadoc).
     */
    private static void remember(BodyRod rod, double x, double y, double z) {
        rod.offset.set((float) (x - rod.bodyX), (float) (y - rod.bodyY), (float) (z - rod.bodyZ));
        rod.offsetYaw = rod.bodyYaw;
        rod.offsetArm = rod.arm;
        rod.offsetPose = rod.pose;
        rod.offsetRiding = rod.riding;
    }

    /** The body's yaw as drawn: LivingEntityRenderer.solveBodyRot (a rider's turns towards its head). */
    private static float bodyYaw(Player owner, float partialTicks) {
        if (owner.getVehicle() instanceof LivingEntity riding) {
            float headRot = Mth.rotLerp(partialTicks, owner.yHeadRotO, owner.yHeadRot);
            float bodyRot = Mth.rotLerp(partialTicks, riding.yBodyRotO, riding.yBodyRot);
            float headDiff = Mth.clamp(Mth.wrapDegrees(headRot - bodyRot), -RIDER_MAX_HEAD_DIFF, RIDER_MAX_HEAD_DIFF);
            bodyRot = headRot - headDiff;
            if (Math.abs(headDiff) > RIDER_TURN_THRESHOLD) {
                bodyRot += headDiff * RIDER_TURN_FACTOR;
            }
            return bodyRot;
        }
        return Mth.rotLerp(partialTicks, owner.yBodyRotO, owner.yBodyRot);
    }

    private static void disable(Minecraft mc, Throwable e) {
        disabledIn = new WeakReference<>(mc.level);
        LOGGER.error("Third-person fishing line correction failed, falling back to vanilla until the next world or dimension", e);
    }
}
