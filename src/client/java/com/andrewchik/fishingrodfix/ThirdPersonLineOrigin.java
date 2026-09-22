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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;
import static com.andrewchik.fishingrodfix.FishingRodFix.isRod;

/**
 * Places the fishing line origin on the rod a player's body is drawn holding: for every line not on
 * the first-person rod, i.e. every player {@code FishingHookRenderer.getPlayerHandPos} sends down its
 * third-person branch (other players in any view, the local one in third person or when a mod draws
 * its body in first person) and the local player whose own body vanilla draws in first person
 * ({@link #bodyDrawnInFirstPerson}).
 *
 * <p>Vanilla's third-person origin is a guess from the eye: a fixed offset turned by the entity's body
 * yaw (not the one drawn while riding a mob), lowered a little while crouching. The drawn rod moves
 * with the model's pose, which the guess ignores: the crouch's lean (MC-4490), riding (MC-176514) and
 * boats (MC-198777), the arm's swing (MC-247425), swimming and crawling (MC-270173), sleeping
 * (MC-270174), and walking, gliding, riptide or dying too.
 *
 * <p>Instead of modelling that pose, the rod is read where it is drawn: where
 * {@code ItemInHandLayer.submitArmWithItem} submits a rod in the hand of a player who is fishing, the
 * pose it submits with (whatever vanilla and other mods made of the body, arm and item up to there)
 * places {@link ThirdPersonRod}'s attachment point, in the collector's space, tagged with the pass it
 * was drawn in: the collector and the {@link HandPass} frame. That tag holds as long as a collector
 * serves one pass per frame, as vanilla's level pass, its picture-in-picture renderers and Iris'
 * shadow pass each do with their own storage. Entities are submitted in the order the client added
 * them, which puts an owner before the hooks it casts; when the hook is submitted in the same pass,
 * its line starts at that point, taken through the inverse of the hook's own pose (so a pass that
 * turns its root, like a shadow pass, cancels out). The line's offset goes straight into
 * {@code submit}'s locals; the render state's {@code lineOriginOffset} keeps vanilla's value.
 *
 * <p>Where the rod isn't drawn in this pass before the hook (its owner out of view, a hook that arrived
 * before its owner, or a body drawn after the hooks, as Real Camera's classic mode does), the line
 * starts where the rod was last drawn, relative to the body's position and turned with its yaw, while
 * the owner still holds a rod in that arm and is in the same pose (and riding or not) as then. A pose
 * changed since then shows once the rod is drawn again; otherwise, and for an owner whose rod was
 * never seen drawn, the line keeps vanilla's value.
 */
public final class ThirdPersonLineOrigin {
    /** Mixed into {@code AbstractClientPlayer}: the player's rod as last drawn, created on first use. */
    public interface Owner {
        @Nullable BodyRod fishingrodfix$bodyRod();

        void fishingrodfix$setBodyRod(BodyRod rod);
    }

    /**
     * Mixed into {@code FishingHookRenderState}: the owner whose drawn rod the line moves onto at
     * submission (null for a line on the first-person rod, or with no player owner) and the owner's
     * partial tick.
     */
    public interface HookState {
        @Nullable AbstractClientPlayer fishingrodfix$bodyRodOwner();

        float fishingrodfix$ownerPartialTicks();

        void fishingrodfix$setBodyRodOwner(@Nullable AbstractClientPlayer owner, float ownerPartialTicks);
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

    // The pass of the latest hook submitted (its collector and frame), with that hook's world
    // position and the inverse of its pose: together they turn a point in the pass's space into the
    // world, for a rod drawn after its hook.
    private static @Nullable SubmitNodeCollector passCollector;
    private static long passFrame;
    private static double passHookX;
    private static double passHookY;
    private static double passHookZ;
    private static final Matrix4f passInverse = new Matrix4f();
    private static final Vector3f scratch = new Vector3f();

    // The line offset for the hook being submitted, written into submit's xa/ya/za locals.
    private static @Nullable FishingHookRenderState pendingState;
    private static final Vector3f pendingOffset = new Vector3f();

    // The world the correction failed in: vanilla's value is kept there, so an incompatible mod or
    // game update degrades to the vanilla line instead of crashing. It retries after the next
    // dimension change or world join (a new ClientLevel). Weak, so a stale level isn't kept alive.
    private static WeakReference<ClientLevel> disabledIn = new WeakReference<>(null);

    private ThirdPersonLineOrigin() {}

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

    /**
     * Called at the end of a hook's {@code extractRenderState}: the owner whose drawn rod the line
     * moves onto at submission, unless the line is on the first-person rod or hidden.
     */
    public static void onHookExtracted(FishingHookRenderState state, @Nullable Player owner, boolean onFirstPersonRod, boolean hidden,
                                       float partialTicks) {
        HookState hookState = (HookState) state;
        if (onFirstPersonRod || hidden || !(owner instanceof AbstractClientPlayer player) || disabledIn.get() == Minecraft.getInstance().level) {
            hookState.fishingrodfix$setBodyRodOwner(null, 0f);
            return;
        }
        // The owner's partial tick, as LevelExtractor extracts it: players never freeze, so it is the
        // hook's unless the tick rate is frozen.
        float ownerPartialTicks = player.level().tickRateManager().runsNormally()
                ? partialTicks
                : Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        hookState.fishingrodfix$setBodyRodOwner(player, ownerPartialTicks);
        bodyHookFrame = HandPass.frame();
    }

    /**
     * Called where {@code ItemInHandLayer.submitArmWithItem} submits a held item, for every armed
     * entity: small, so it inlines there; only a rod in an avatar's hand in a frame with a body-held
     * line goes on.
     */
    public static void onItemSubmitted(ArmedEntityRenderState state, ItemStack stack, HumanoidArm arm, PoseStack poseStack, SubmitNodeCollector collector) {
        if (bodyHookFrame == HandPass.frame() && state instanceof AvatarRenderState avatar && isRod(stack)) {
            readDrawnRod(avatar, arm, poseStack, collector);
        }
    }

    /** Records a fishing player's rod as drawn. */
    private static void readDrawnRod(AvatarRenderState avatar, HumanoidArm arm, PoseStack poseStack, SubmitNodeCollector collector) {
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
            Owner owner = (Owner) player;
            BodyRod rod = owner.fishingrodfix$bodyRod();
            if (rod == null) {
                rod = new BodyRod();
                owner.fishingrodfix$setBodyRod(rod);
            }
            poseStack.last().pose().transformPosition(ThirdPersonRod.lineAnchor(mc), rod.tip);
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
     * rod drawn earlier in this pass or where it was last drawn, for {@link #lineOffset}.
     */
    public static void onHookSubmitted(FishingHookRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        pendingState = null;
        HookState hookState = (HookState) state;
        AbstractClientPlayer owner = hookState.fishingrodfix$bodyRodOwner();
        Minecraft mc = Minecraft.getInstance();
        if (owner == null || disabledIn.get() == mc.level) {
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
            } else {
                local = remembered(owner, rod, hookState.fishingrodfix$ownerPartialTicks(), state);
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
        return state == pendingState ? pendingOffset.get(axis) : value;
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
        // The body turns about its position (LivingEntityRenderer.setupRotations: 180 - yaw).
        Vector3f offset = scratch.set(rod.offset).rotateY(Mth.DEG_TO_RAD * (rod.offsetYaw - bodyYaw(owner, partialTicks)));
        return offset.set(
                (float) (Mth.lerp(partialTicks, owner.xOld, owner.getX()) - state.x + offset.x),
                (float) (Mth.lerp(partialTicks, owner.yOld, owner.getY()) - state.y + offset.y),
                (float) (Mth.lerp(partialTicks, owner.zOld, owner.getZ()) - state.z + offset.z));
    }

    /** Remembers the drawn tip, at world position {@code (x, y, z)}, relative to the body it was drawn with. */
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
