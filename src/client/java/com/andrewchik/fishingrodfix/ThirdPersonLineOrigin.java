package com.andrewchik.fishingrodfix;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.client.render.entity.state.FishingBobberEntityState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.ref.WeakReference;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;
import static com.andrewchik.fishingrodfix.FishingRodFix.isRod;

/**
 * Places the fishing line origin on the rod a player's body is drawn holding: for every line not on
 * the first-person rod, i.e. every player {@code FishingBobberEntityRenderer.getHandPos} sends down its
 * third-person branch (other players in any view, the local one in third person or when a mod draws
 * its body in first person) and the local player whose own body vanilla draws in first person
 * ({@link #bodyDrawnInFirstPerson}) or, on the first-person branch, a mod does (see the last
 * paragraph).
 *
 * <p>Vanilla's third-person origin is a guess from the eye: a fixed offset turned by the entity's body
 * yaw (not the one drawn while riding a mob), lowered a little while crouching. The drawn rod moves
 * with the model's pose, which the guess ignores: the crouch's lean (MC-4490), riding (MC-176514) and
 * boats (MC-198777), the arm's swing (MC-247425), swimming and crawling (MC-270173), sleeping
 * (MC-270174), and walking, gliding, riptide or dying too.
 *
 * <p>Instead of modelling that pose, the rod is read where it is drawn: where
 * {@code HeldItemFeatureRenderer.renderItem} submits a rod in the hand of a player who is fishing, the
 * pose it submits with (whatever vanilla and other mods made of the body, arm and item up to there)
 * places {@link ThirdPersonRod}'s attachment point, in the queue's space, tagged with the pass it was
 * drawn in: the queue, the {@link HandPass} frame and the count of queue clears so far. On 1.21.10 one
 * {@code OrderedRenderCommandQueueImpl} (the {@code GameRenderer}'s) serves every vanilla pass of a
 * frame (the world and its particles, the first-person hand, the screen effects, the GUI's pictures
 * such as the inventory's player model, and its item atlas), each submitted, drawn and cleared in
 * turn, so a clear ends a pass.
 * Entities are submitted in the order the client added them, which puts an owner before the hooks it
 * casts; when the hook is submitted in the same pass, its line starts at that point, taken through the
 * inverse of the hook's own pose (so a pass that turns its root, like a shadow pass, cancels out). The
 * line's offset goes straight into {@code render}'s locals; the render state's {@code pos} keeps
 * vanilla's value.
 *
 * <p>Where the rod isn't drawn in this pass before the hook (its owner out of view, a hook that arrived
 * before its owner, or a body drawn after the hooks, as Real Camera's classic mode does), the line
 * starts where the rod was last drawn, relative to the body's position and turned with its yaw, while
 * the owner still holds a rod in that arm and is in the same pose (and riding or not) as then. A pose
 * changed since then shows once the rod is drawn again; otherwise, and for an owner whose rod was
 * never seen drawn, the line keeps vanilla's value. Being relative to the body's position, that spot
 * also carries the offset the reading frame drew the body at ({@code PlayerEntityRenderer.getPositionOffset}):
 * the crouch shift, which the pose gate keeps in step, and, only for a passenger of an
 * experimental-movement minecart, that frame's cart lerp correction, a fraction of a block until the
 * rod is drawn again.
 *
 * <p>The local player's line on {@code getHandPos}' first-person branch that {@link FishingLineOrigin}
 * left at vanilla's value (see its Javadoc) while the camera is on that player (and vanilla, with the
 * camera in first person and the player awake, doesn't draw the body) only moves onto a body drawn
 * earlier in the same pass: one a mod draws in first person without changing the branch (Player
 * Animation Library's first-person model mode), or the one Iris' shadow pass draws, rod and then hook,
 * into the shadow map. Where the rod was last drawn doesn't count for it: no body is on screen, and an
 * F5 view just left or the shadow pass's body would start the visible line at a rod that isn't drawn.
 * It keeps vanilla's first-person value. With the camera on another entity vanilla never draws the
 * local player, so a body on screen is a mod's and may come after the hooks (Freecam's Show Player
 * adds it after the world's entity extraction): that line keeps the remembered spot, like Real
 * Camera's classic mode.
 */
public final class ThirdPersonLineOrigin {
    /** Mixed into {@code AbstractClientPlayerEntity}: the player's rod as last drawn, created on first use. */
    public interface Owner {
        @Nullable BodyRod fishingrodfix$bodyRod();

        void fishingrodfix$setBodyRod(BodyRod rod);
    }

    /**
     * Mixed into {@code FishingBobberEntityState}: the owner whose drawn rod the line moves onto at
     * submission (null for a line on the first-person rod, or with no player owner), the owner's
     * partial tick, and whether only a rod drawn earlier in the same pass counts (see
     * {@link #onFirstPersonVanilla}). The owner is dropped when the state is submitted: other mods
     * may keep render states past their frame (Iris' shadow pass keeps its last frame's until its next
     * one, also after a disconnect), and the owner would keep its world alive.
     */
    public interface HookState {
        @Nullable AbstractClientPlayerEntity fishingrodfix$bodyRodOwner();

        float fishingrodfix$ownerPartialTicks();

        boolean fishingrodfix$samePassOnly();

        void fishingrodfix$setBodyRodOwner(@Nullable AbstractClientPlayerEntity owner, float ownerPartialTicks, boolean samePassOnly);
    }

    /** Where a player's rod was drawn. Render-thread only, as is all state here. */
    public static final class BodyRod {
        // The latest submission: the tip in the queue's space and the pass it was drawn in, with the
        // arm, and the body's position, yaw, pose and riding it was drawn with.
        private final Vector3f tip = new Vector3f();
        // Compared by identity only, never dereferenced, and useless once frame is stale: a queue a
        // mod replaces (Iris on a shader reload) is held until the player's next drawn rod, no longer.
        private @Nullable OrderedRenderCommandQueue collector;
        private long frame;
        private long clears;
        private Arm arm = Arm.RIGHT;
        private double bodyX;
        private double bodyY;
        private double bodyZ;
        private float bodyYaw;
        private EntityPose pose = EntityPose.STANDING;
        private boolean riding;
        // The tip relative to the body's position at the yaw it was drawn at, from the latest
        // submission whose pass a hook gave the world position of; no arm until then.
        private final Vector3f offset = new Vector3f();
        private float offsetYaw;
        private @Nullable Arm offsetArm;
        private EntityPose offsetPose = EntityPose.STANDING;
        private boolean offsetRiding;
    }

    // renderFishingLine draws the line 0.25 above the hook's position, and getHandPos' result is
    // turned into pos relative to that point (updateRenderState: hookPos + (0, 0.25, 0)).
    private static final float LINE_Y_OFFSET = 0.25f;

    // LivingEntityRenderer.clampBodyYaw: a rider's body turns with its head, within these limits of
    // the mount's body.
    private static final float RIDER_MAX_HEAD_DIFF  = 85f;
    private static final float RIDER_TURN_THRESHOLD = 50f;
    private static final float RIDER_TURN_FACTOR    = 0.2f;

    // The frame in which a hook with a body-held line was last extracted, and in it the owners of
    // those hooks with the arm each draws its rod in (ownerKey): rods are only read in such frames,
    // only for those players and only in that arm, so every other frame costs the item layer one
    // compare per held item, and every other drawn item (another player's, a mannequin's, the
    // fishing player's other hand) one set lookup. 1.21.10's held-item call has no stack to tell a
    // rod by, so this stands in for 1.21.11's rod check; what is drawn in that arm is still checked
    // out of line. Bounded by the hooks extracted in one frame.
    private static long bodyHookFrame = Long.MIN_VALUE;
    private static final IntOpenHashSet bodyHookOwners = new IntOpenHashSet();

    // Whether the hook being extracted took getHandPos' first-person branch with the camera on its
    // owner and FishingLineOrigin kept vanilla's value there (see the class Javadoc). Cleared at the
    // start of updateRenderState, set inside it and read at its end.
    private static boolean firstPersonVanilla;

    // OrderedRenderCommandQueueImpl.clear calls so far, on any queue: on 1.21.10 one queue serves
    // every vanilla pass of a frame, and a clear ends one. 0 while that hook doesn't apply.
    private static long clears;

    // The pass of the latest hook submitted (its queue, frame and clears), with that hook's world
    // position and the inverse of its pose: together they turn a point in the pass's space into the
    // world, for a rod drawn after its hook. Forgotten at the next frame's start: the queue may be a
    // shadow pass's, whose pipeline Iris replaces on a dimension change or shader reload.
    private static @Nullable OrderedRenderCommandQueue passCollector;
    private static long passFrame;
    private static long passClears;
    private static double passHookX;
    private static double passHookY;
    private static double passHookZ;
    private static final Matrix4f passInverse = new Matrix4f();
    private static final Vector3f scratch = new Vector3f();

    // The line offset for the hook being submitted, written into render's f/g/h locals. Cleared once
    // h is written, and at the next frame's start in case another mod cut render short, so no render
    // state outlives its submission here.
    private static @Nullable FishingBobberEntityState pendingState;
    private static final Vector3f pendingOffset = new Vector3f();

    // The world the correction failed in: vanilla's value is kept there, so an incompatible mod or
    // game update degrades to the vanilla line instead of crashing. It retries after the next
    // dimension change or world join (a new ClientWorld). Weak, so a stale world isn't kept alive.
    private static WeakReference<ClientWorld> disabledIn = new WeakReference<>(null);

    private ThirdPersonLineOrigin() {}

    /** Called at the start of every rendered frame ({@code GameRenderer.render}): forgets the last frame's pass. */
    public static void onFrameStart() {
        passCollector = null;
        pendingState = null;
    }

    /**
     * Whether vanilla draws the local player's own body while it is the first-person camera (as
     * {@code WorldRenderer.fillEntityRenderStates} decides): asleep, or with the camera moved into
     * third person by a mod. The line then belongs on the rod in the body's hand, the one in the world
     * (with such a camera vanilla also draws the hand pass, at the camera).
     */
    public static boolean bodyDrawnInFirstPerson(PlayerEntity owner) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (disabledIn.get() == mc.world) {
            return false;
        }
        try {
            Camera camera = mc.getEntityRenderDispatcher().camera;
            return owner == mc.player && camera != null && camera.getFocusedEntity() == owner && (camera.isThirdPerson() || owner.isSleeping());
        } catch (RuntimeException | LinkageError e) {
            disable(mc, e);
            return false;
        }
    }

    /** Called at the start of a hook's {@code updateRenderState}. */
    public static void beginExtraction() {
        firstPersonVanilla = false;
    }

    /**
     * Called from {@code getHandPos}' first-person branch when {@link FishingLineOrigin} kept vanilla's
     * value and vanilla doesn't draw the body: with the camera on the owner, the line only moves onto a
     * body drawn earlier in the same pass (see the class Javadoc).
     */
    public static void onFirstPersonVanilla(PlayerEntity owner) {
        MinecraftClient mc = MinecraftClient.getInstance();
        // As every other entry: once this world's correction has failed, nothing here runs (and the
        // failure isn't logged again every frame).
        if (disabledIn.get() == mc.world) {
            return;
        }
        try {
            Camera camera = mc.getEntityRenderDispatcher().camera;
            firstPersonVanilla = camera != null && camera.getFocusedEntity() == owner;
        } catch (RuntimeException | LinkageError e) {
            disable(mc, e);
        }
    }

    /**
     * Called at the end of a hook's {@code updateRenderState}: the owner whose drawn rod the line
     * moves onto at submission, unless the line is on the first-person rod or hidden.
     */
    public static void onHookExtracted(FishingBobberEntityState state, @Nullable PlayerEntity owner, boolean onFirstPersonRod, boolean hidden,
                                       float partialTicks) {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            HookState hookState = (HookState) state;
            if (onFirstPersonRod || hidden || !(owner instanceof AbstractClientPlayerEntity player) || disabledIn.get() == mc.world) {
                hookState.fishingrodfix$setBodyRodOwner(null, 0f, false);
                return;
            }
            // The owner's partial tick, as WorldRenderer.fillEntityRenderStates extracts it: players
            // never skip a tick (TickManager.shouldSkipTick), so it is the hook's unless the tick rate
            // is frozen.
            float ownerPartialTicks = player.getEntityWorld().getTickManager().shouldTick()
                    ? partialTicks
                    : mc.getRenderTickCounter().getTickProgress(true);
            hookState.fishingrodfix$setBodyRodOwner(player, ownerPartialTicks, firstPersonVanilla);
            // Without frame counting nothing is read (and the owners would pile up over the session).
            if (HandPass.framesCounted()) {
                long frame = HandPass.frame();
                if (bodyHookFrame != frame) {
                    bodyHookOwners.clear();
                    bodyHookFrame = frame;
                }
                bodyHookOwners.add(ownerKey(player.getId(), FishingBobberEntityRenderer.getArmHoldingRod(player)));
            }
        } catch (RuntimeException | LinkageError e) {
            // As every other entry from vanilla: vanilla's line until the next world. A line left with
            // an owner here drops it unused at its submission, which runs before anything reads it.
            disable(mc, e);
        }
    }

    /**
     * Called where {@code HeldItemFeatureRenderer.renderItem} submits a held item, once per non-empty
     * held item of an armed entity: small, so it inlines there; only the rod arm of a player whose
     * body-held hook was extracted this frame goes on (1.21.10's call has no stack: whether the item
     * drawn there is the rod is decided out of line). Every pass extracts its entities before it
     * submits any, so the owners of the pass's hooks are known when their rods are drawn, before or
     * after the hooks.
     */
    public static void onItemSubmitted(ArmedEntityRenderState state, Arm arm, MatrixStack matrices, OrderedRenderCommandQueue queue) {
        // arm != null: vanilla always passes one, but ownerKey reads it and this gate runs outside the
        // exception boundary, so a mod that passed null would throw into the item layer.
        if (arm != null && bodyHookFrame == HandPass.frame() && state instanceof PlayerEntityRenderState player
                && bodyHookOwners.contains(ownerKey(player.id, arm))) {
            readDrawnRod(player, arm, matrices, queue);
        }
    }

    /** A body-held hook owner and the arm its rod is drawn in, as one {@code bodyHookOwners} key. */
    private static int ownerKey(int entityId, Arm arm) {
        return entityId * 2 + arm.ordinal();
    }

    /** Records a fishing player's rod as drawn, if the item submitted in {@code arm} is it. */
    private static void readDrawnRod(PlayerEntityRenderState state, Arm arm, MatrixStack matrices, OrderedRenderCommandQueue queue) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        // Without frame counting a capture couldn't be told from an older one.
        if (world == null || disabledIn.get() == world || !HandPass.framesCounted()) {
            return;
        }
        try {
            // The rod itself: arm is already the one vanilla draws the cast rod in (the key it came
            // through holds FishingBobberEntityRenderer.getArmHoldingRod's arm), so only the item in
            // it is left. The item state was extracted from getStackInArm this frame, and nothing
            // ticks between extraction and submission, so the live stack is the one drawn.
            if (!(world.getEntityById(state.id) instanceof AbstractClientPlayerEntity player) || player.fishHook == null
                    || !isRod(player.getStackInArm(arm))) {
                return;
            }
            Owner owner = (Owner) player;
            BodyRod rod = owner.fishingrodfix$bodyRod();
            if (rod == null) {
                rod = new BodyRod();
                owner.fishingrodfix$setBodyRod(rod);
            }
            matrices.peek().getPositionMatrix().transformPosition(ThirdPersonRod.lineAnchor(mc), rod.tip);
            rod.collector = queue;
            rod.frame = HandPass.frame();
            rod.clears = clears;
            rod.arm = arm;
            rod.bodyX = state.x;
            rod.bodyY = state.y;
            rod.bodyZ = state.z;
            rod.bodyYaw = state.bodyYaw;
            rod.pose = state.pose;
            rod.riding = state.hasVehicle;
            // Drawn after a hook of this pass: that hook gives the world position to remember it at.
            // Only while clears are counted: without, a GUI picture drawn after the world in the same
            // frame (the inventory's player model) would pass for it.
            if (clears > 0 && drawnInLastHookPass(rod)) {
                Vector3f local = passInverse.transformPosition(rod.tip, scratch);
                remember(rod, passHookX + local.x, passHookY + local.y, passHookZ + local.z);
            }
        } catch (RuntimeException | LinkageError e) {
            disable(mc, e);
        }
    }

    /**
     * Called at the start of a hook's {@code render}: works out where its line starts, on the owner's
     * rod drawn earlier in this pass or (not for a line flagged by {@link #onFirstPersonVanilla}) where
     * it was last drawn, for {@link #lineOffset}.
     */
    public static void onHookSubmitted(FishingBobberEntityState state, MatrixStack matrices, OrderedRenderCommandQueue queue) {
        pendingState = null;
        HookState hookState = (HookState) state;
        AbstractClientPlayerEntity owner = hookState.fishingrodfix$bodyRodOwner();
        if (owner == null) {
            return;
        }
        float ownerPartialTicks = hookState.fishingrodfix$ownerPartialTicks();
        boolean samePassOnly = hookState.fishingrodfix$samePassOnly();
        // Only this submission needs the owner (see HookState).
        hookState.fishingrodfix$setBodyRodOwner(null, 0f, false);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (disabledIn.get() == mc.world) {
            return;
        }
        try {
            // The hook's pose maps its local frame (world axes, origin at the hook) into the pass's
            // space; the inverse brings a drawn rod back into it.
            matrices.peek().getPositionMatrix().invertAffine(passInverse);
            passCollector = queue;
            passFrame = HandPass.frame();
            passClears = clears;
            passHookX = state.x;
            passHookY = state.y;
            passHookZ = state.z;
            BodyRod rod = ((Owner) owner).fishingrodfix$bodyRod();
            if (rod == null) {
                return;
            }
            Vector3f local;
            if (drawnInLastHookPass(rod)) {
                local = passInverse.transformPosition(rod.tip, scratch);
                remember(rod, state.x + local.x, state.y + local.y, state.z + local.z);
            } else if (samePassOnly) {
                // A first-person line FishingLineOrigin left at vanilla's value, with no body drawn
                // before it in this pass: vanilla's value (see the class Javadoc).
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

    /** Called when an {@code OrderedRenderCommandQueueImpl} is cleared: the pass drawn from it has ended. */
    public static void onSubmitsCleared() {
        clears++;
    }

    /** Whether {@code rod} was last drawn in the pass of the latest hook submitted. */
    private static boolean drawnInLastHookPass(BodyRod rod) {
        return rod.collector == passCollector && rod.frame == passFrame && rod.clears == passClears;
    }

    /**
     * {@code render}'s line offset component {@code axis} (0 = x, 1 = y, 2 = z) for {@code state}:
     * the one {@link #onHookSubmitted} worked out, or vanilla's {@code value}.
     */
    public static float lineOffset(FishingBobberEntityState state, int axis, float value) {
        if (state != pendingState) {
            return value;
        }
        // h (z) is render's last offset local.
        if (axis == 2) {
            pendingState = null;
        }
        return pendingOffset.get(axis);
    }

    /**
     * Where the owner's rod was last drawn, relative to the body now, relative to the hook; null when
     * nothing usable was drawn (another arm, pose or mount since, or no rod held).
     */
    private static @Nullable Vector3f remembered(PlayerEntity owner, BodyRod rod, float partialTicks, FishingBobberEntityState state) {
        Arm arm = rod.offsetArm;
        if (arm == null || arm != FishingBobberEntityRenderer.getArmHoldingRod(owner) || !isRod(owner.getStackInArm(arm))
                || owner.getPose() != rod.offsetPose || owner.hasVehicle() != rod.offsetRiding) {
            return null;
        }
        // The body turns about its position (LivingEntityRenderer.setupTransforms: 180 - yaw), except
        // asleep, where the same method turns it by the bed's direction instead (only falling back to
        // the body yaw if the bed is gone) and the body yaw doesn't move it: a reading taken asleep is
        // in bed space, and rotating it by a yaw delta would be meaningless. The rule follows the pose
        // the reading was taken in, which the gate above has already matched against the owner's now.
        Vector3f offset = scratch.set(rod.offset);
        if (rod.offsetPose != EntityPose.SLEEPING) {
            offset.rotateY(MathHelper.RADIANS_PER_DEGREE * (rod.offsetYaw - bodyYaw(owner, partialTicks)));
        }
        return offset.set(
                (float) (MathHelper.lerp(partialTicks, owner.lastRenderX, owner.getX()) - state.x + offset.x),
                (float) (MathHelper.lerp(partialTicks, owner.lastRenderY, owner.getY()) - state.y + offset.y),
                (float) (MathHelper.lerp(partialTicks, owner.lastRenderZ, owner.getZ()) - state.z + offset.z));
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

    /** The body's yaw as drawn: LivingEntityRenderer.clampBodyYaw (a rider's turns towards its head). */
    private static float bodyYaw(PlayerEntity owner, float partialTicks) {
        if (owner.getVehicle() instanceof LivingEntity riding) {
            float headYaw = MathHelper.lerpAngleDegrees(partialTicks, owner.lastHeadYaw, owner.headYaw);
            float bodyYaw = MathHelper.lerpAngleDegrees(partialTicks, riding.lastBodyYaw, riding.bodyYaw);
            float headDiff = MathHelper.clamp(MathHelper.wrapDegrees(headYaw - bodyYaw), -RIDER_MAX_HEAD_DIFF, RIDER_MAX_HEAD_DIFF);
            bodyYaw = headYaw - headDiff;
            if (Math.abs(headDiff) > RIDER_TURN_THRESHOLD) {
                bodyYaw += headDiff * RIDER_TURN_FACTOR;
            }
            return bodyYaw;
        }
        return MathHelper.lerpAngleDegrees(partialTicks, owner.lastBodyYaw, owner.bodyYaw);
    }

    private static void disable(MinecraftClient mc, Throwable e) {
        disabledIn = new WeakReference<>(mc.world);
        LOGGER.error("Third-person fishing line correction failed, falling back to vanilla until the next world or dimension", e);
    }
}
