package com.andrewchik.fishingrodfix;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static com.andrewchik.fishingrodfix.FishingRodFix.LOGGER;
import static com.andrewchik.fishingrodfix.FishingRodFix.armHoldingRod;
import static com.andrewchik.fishingrodfix.FishingRodFix.isRod;
import static com.andrewchik.fishingrodfix.FishingRodFix.stackInArm;

/**
 * Places the fishing line origin on the rod a player's body is drawn holding: for every line not on
 * the first-person rod, i.e. every player {@code FishingBobberEntityRenderer.render} sends down its
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
 * {@code HeldItemFeatureRenderer.renderItem} draws a rod in the hand of a player who is fishing, the
 * pose it draws with (whatever vanilla and other mods made of the body, arm and item up to there)
 * places {@link ThirdPersonRod}'s attachment point, in the drawing space, tagged with the pass
 * it was drawn in: the {@link HandPass} frame, the number of passes that have ended, and either the
 * buffer source or - for a drawing inside the world's entity loop - that loop (see the next
 * paragraph). On 1.20.1 nothing is deferred and one {@code VertexConsumerProvider.Immediate} (the
 * {@code BufferBuilderStorage}'s entity one) takes every vanilla pass of a frame (the world's entities
 * and block entities, the first-person hand, the screen effects and the GUI's pictures such as the
 * inventory's player model), so a pass ends where the world's entity loop is flushed and wherever a
 * buffer source flushes everything it holds.
 * When the hook is drawn after the rod in the same pass, its line starts at that point, taken through
 * the inverse of the hook's own pose (so a pass that turns its root, like a shadow pass, cancels out).
 * The line's offset goes straight into {@code render}'s locals, so nothing is allocated and the value
 * the rest of the method computes for every other mod stays vanilla's.
 *
 * <p>The collector identity alone would not do, because a mod may hand each entity its own.
 * <b>Iris with a shader pack does</b>: its {@code entity_render_context/MixinEntityRenderDispatcher}
 * {@code @ModifyVariable}s the buffer-source argument of every {@code EntityRenderDispatcher.render}
 * into a freshly allocated {@code BufferSourceWrapper} as soon as a pack has set
 * {@code WorldRenderingSettings.entityIds} - which nothing resets, so it outlives unloading the pack
 * until the game restarts. A rod and its hook then never share one, and with the collector as the
 * only tie the whole body-held path would be off for every shader user. What the two do share is the
 * loop that drew them: the hook {@link com.andrewchik.fishingrodfix.mixin.client.WorldRendererMixin}
 * puts on the entity loop's iterator runs once, before the first entity, and records the pass the
 * loop opens in ({@link HandPass#frame()} and the pass-end count); every drawing notes whether it
 * happened while that record still held, and the match takes two such drawings of one pass as the
 * same pass even when their collectors differ. The pose is the loop's own {@code MatrixStack} either
 * way, so the two readings are in the same space - which is what the collector was ever a proxy for.
 * Three things stay out of that window by construction: the <b>GUI's</b> pictures, drawn past the
 * loop's end mark and past every buffer-source flush, so their pass-end count differs; <b>Iris'
 * shadow pass</b>, which never goes through the world's entity loop and is drawn before that loop's
 * iterator records anything (Iris renders it from a {@code renderLevel} inject at the {@code renderSky}
 * call, well before the loop), so its drawings see the previous frame's record and note themselves as
 * outside (it keeps pairing by collector, as it did before: its own one buffer source without a pack,
 * per-entity wrappers with one); and anything in a later frame, since the record carries the frame. A
 * body drawn inside the loop but into another collector is now matched where it used to miss: a
 * glowing player or a spectator's outlines ({@code OutlineVertexConsumerProvider}), and a mod's own
 * buffer source used inside the loop. For one drawn <em>after</em> the hooks that means the
 * remembered spot is refreshed in the same pass instead of never; for one drawn before them the line
 * sits on this frame's rod. The hook is optional: without it nothing is ever noted as inside a loop
 * and the match is the collector's alone, exactly as before. What the window does not cover is a body
 * drawn before the record is taken, i.e. before the loop's {@code iterator()} call; nothing checked
 * draws one there on this version (First Person Model and Freecam's Show Player both hang theirs on
 * the {@code checkEmpty} call the pass end is marked at, which at the default priority runs before
 * that mark, so both are inside). The other limit is the assumption itself: the window says two
 * drawings share the loop's own {@code MatrixStack} space, which every mod checked does, but a mod
 * drawing a body through a stack rooted elsewhere would now be matched where the collector tag used
 * to refuse it.
 *
 * <p>The remembered offset carries one thing of the frame it was read in: the render offset
 * {@code EntityRenderer.getPositionOffset(T, float)} adds on top of the entity's position, which the pose
 * includes and the body position it is measured against does not. It cancels while the offset is
 * the same in both frames, and on 1.20.1 a player's is only the crouch shift, a constant that moves
 * with the pose the remembered path already gates on (1.21.1 scales it by the entity's scale and
 * 1.21.2 adds the experimental minecart's lerp; neither exists here), so it cancels exactly.
 * Re-deriving it would mean
 * re-deriving a vanilla method any mod may override, at both the reading and the replay, which is
 * the modelling this class exists to avoid; see {@link #remember}.
 *
 * <p>1.20.1 extracts nothing: {@code FishingBobberEntityRenderer.render} works the origin out and
 * draws the catenary in one call, so unlike the render-state versions there is no window between the
 * two and the whole decision is made inside that call.
 *
 * <p>Vanilla 1.20.1 draws the world's entities in the order the client added them
 * ({@code WorldRenderer.render} iterates {@code ClientWorld.getEntities()}, which {@code EntityIndex}
 * keeps in an {@code Int2ObjectLinkedOpenHashMap}, and vanilla never sorts or copies the list), so a
 * player would be drawn before the hooks it cast. That order isn't guaranteed to survive other mods,
 * though: <b>Iris</b> regroups the entities by {@code EntityType} through a {@code HashMap} for its
 * entity batching, which makes the order an identity hash - arbitrary and fixed per launch.
 * {@link #bobbersLast} takes the choice away: it wraps the loop's iterator so the bobbers come behind
 * everything else, and a body-held line is placed on the rod of this very frame, in every launch and
 * with any mod alike. Where the wrapper doesn't apply - it is optional, and a mod may draw the
 * entities without it or re-order them after it - both orders still work; the one that puts the hooks
 * first falls back to the remembered spot below, one frame late, while the rod, drawn later in the
 * same pass, refreshes that spot at the hook's own world position. The same is true for a hook the
 * server sent before its owner, or a body another mod draws after the hooks.
 *
 * <p>Where the rod isn't drawn in this pass before the hook - the owner out of view, a hook that
 * arrived before its owner, a body drawn after the hooks, or the wrapper
 * not applying - the line starts where the rod was last drawn, relative to the body's position
 * and turned with its yaw, while the owner still holds a rod in that arm. A pose or mount changed
 * since then invalidates the offset, and a reading from the frame before is kept anyway, because a
 * one-frame lag is far less visible than a one-frame teleport - not because it is the closer of the
 * two candidates. Usually it isn't: vanilla's value is nearer on most transition frames (leaving
 * gliding or a riptide spin 2.15 blocks or more for the kept reading against 0.12, waking 0.8-2.1
 * against 0.12, a crouch 0.54 against 0.07), and the kept reading only wins leaving swimming and
 * lying down; a mount change or a lean that ramps up is small either way. What it buys is the frame
 * the origin would otherwise jump to a different anchor and back, which is the visible artifact. An
 * older reading isn't kept: it has no lag to preserve, only an unrelated position. An owner's rod
 * that was never seen drawn isn't used at all (that one fails the arm check above, pose change or
 * not).
 *
 * <p>The local player's line on the first-person branch that {@link FishingLineOrigin}
 * left at vanilla's value (see its Javadoc) while the camera is on that player (and vanilla, with the
 * camera in first person and the player awake, doesn't draw the body) only moves onto a body drawn
 * earlier in the same pass: one a mod draws in first person without changing the branch (Player
 * Animation Library's first-person model mode). Not Iris' shadow pass: it exists only with a shader
 * pack, and with one every entity render gets its own buffer source, so its rod and its hook never
 * pair (it never goes through the world's entity loop either, so the loop tie doesn't reach it).
 * Where the rod was last drawn doesn't count for it: no body is on
 * screen, and an F5 view just left or the shadow pass's body would start the visible line at a rod
 * that isn't drawn. It keeps vanilla's first-person value. With the camera on another entity vanilla
 * never draws the local player, so a body on screen is a mod's and may come after the hooks (Freecam's
 * Show Player draws it at the end of the entity loop, at the very call {@link
 * com.andrewchik.fishingrodfix.mixin.client.WorldRendererMixin} marks the pass end at, so it is still
 * inside the pass): that line keeps the remembered spot. Real Camera's classic mode is the same case
 * on 1.20.1 - it draws the body at {@code WorldRenderer.render}'s first
 * {@code Immediate.drawCurrentLayer()}, i.e. after the entity loop and five bytes before the pass
 * end, so its line takes the remembered spot too.
 */
public final class ThirdPersonLineOrigin {
    /** Mixed into {@code AbstractClientPlayerEntity}: the player's rod as last drawn, created on first use. */
    public interface Owner {
        @Nullable BodyRod fishingrodfix$bodyRod();

        void fishingrodfix$setBodyRod(BodyRod rod);
    }

    /** Where a player's rod was drawn. Render-thread only, as is all state here. */
    public static final class BodyRod {
        // The latest drawing: the tip in the buffer source's space and the pass it was drawn in, with
        // the arm, and the body's position, yaw, pose and riding it was drawn with.
        private final Vector3f tip = new Vector3f();
        // Compared by identity only, never dereferenced, and useless once frame is stale: a buffer
        // source a mod replaces (Iris on a shader reload) is held until the player's next drawn rod,
        // no longer.
        private @Nullable VertexConsumerProvider collector;
        // Whether it was drawn inside the world's entity loop, which ties it to every other drawing
        // of that loop however the collectors differ (see the class Javadoc).
        private boolean inEntityLoop;
        private long frame;
        private long passEnds;
        private Arm arm = Arm.RIGHT;
        private double bodyX;
        private double bodyY;
        private double bodyZ;
        private float bodyYaw;
        private EntityPose pose = EntityPose.STANDING;
        private boolean riding;
        // The tip relative to the body's position at the yaw it was drawn at, from the latest
        // drawing whose pass a hook gave the world position of; no arm until then.
        private final Vector3f offset = new Vector3f();
        private float offsetYaw;
        private @Nullable Arm offsetArm;
        private EntityPose offsetPose = EntityPose.STANDING;
        private boolean offsetRiding;
        // The frame that offset was taken in, so a pose or mount change can tell a one-frame-old
        // reading from an older one (see remembered).
        private long offsetFrame = Long.MIN_VALUE;
    }

    // renderFishingLine lifts every catenary vertex 0.25 above the pose origin, and render turns the
    // origin into the offset relative to the bobber's lerped position + (0, 0.25, 0).
    private static final float LINE_Y_OFFSET = 0.25f;

    // LivingEntityRenderer.render: a rider's body turns with its head, within these limits of the
    // mount's body (vanilla writes the threshold as headDiff^2 > 2500).
    private static final float RIDER_MAX_HEAD_DIFF  = 85f;
    private static final float RIDER_TURN_THRESHOLD = 50f;
    private static final float RIDER_TURN_FACTOR    = 0.2f;

    // Whether the hook being drawn took render's first-person branch with the camera on its owner
    // and FishingLineOrigin kept vanilla's value there (see the class Javadoc). Cleared at the start
    // of render, set inside it and read once the branch has run.
    private static boolean firstPersonVanilla;

    // The decision for the hook being drawn, from the moment the origin branch ran until its line
    // offset is written: whether the line moves onto the owner's drawn rod at all, the owner's
    // partial tick, and whether only a rod drawn earlier in the same pass counts (see
    // onFirstPersonVanilla). No reference to the owner - the hook's own getPlayerOwner gives it back
    // where it is needed - so nothing here can keep an entity or its world alive.
    private static boolean pendingBodyHeld;
    private static float pendingOwnerPartialTicks;
    private static boolean pendingSamePassOnly;

    // Passes that have ended so far, from two optional hooks: the end of WorldRenderer.render's
    // entity loop, and any VertexConsumerProvider.Immediate.draw(), which ends whatever was drawn
    // into that buffer source (on 1.20.1 one of them serves every vanilla pass of a frame). The first
    // is what separates the world's entities from the GUI's pictures when a mod replaces the buffer
    // sources and vanilla's draw() never runs (ImmediatelyFast's BatchableBufferSource overrides it),
    // the second what separates passes that share one entity loop with a mod's own buffer source.
    // 0 while neither hook applies, and then no pass is matched at all.
    private static long passEnds;

    // The pass the world's entity loop is running in, from the optional hook on its iterator: the
    // frame it started in and the pass-end count it started at. Everything drawn while both still
    // hold is one of that loop's own drawings, whatever buffer source it went into - which is what
    // keeps the body-held path working where a mod gives each entity its own collector (Iris with a
    // shader pack; see the class Javadoc). The loop's end mark raises passEnds, and so does every
    // flush between the loop and the GUI, so the window closes on its own; a frame that never
    // reaches the hook leaves the previous frame's record, which the frame check rejects.
    private static long entityLoopFrame = Long.MIN_VALUE;
    private static long entityLoopPassEnds = -1;

    // The pass of the latest hook drawn (its buffer source, whether it was inside the entity loop,
    // frame and pass ends), with that hook's world
    // position and the inverse of its pose: together they turn a point in the pass's space into the
    // world, for a rod drawn after its hook. Forgotten at the next frame's start: the buffer source may
    // be a shadow pass's, which Iris replaces on a dimension change or shader reload.
    private static @Nullable VertexConsumerProvider passCollector;
    private static boolean passInEntityLoop;
    private static long passFrame;
    private static long passEndsAtHook;
    private static double passHookX;
    private static double passHookY;
    private static double passHookZ;
    private static final Matrix4f passInverse = new Matrix4f();
    private static final Vector3f scratch = new Vector3f();

    // The line offset for the hook being drawn, written into render's v/w/x locals. Valid only from
    // the x local's store, where it is worked out, to the z local's, and cleared at the start of
    // every hook's render and of every frame in case another mod cut render short. nextOffsetAxis
    // takes each axis only after the one before it, so if a mod keeps one of the three optional
    // @ModifyVariables from applying, the correction stops there instead of a later axis picking up
    // an offset that was never started. Losing the first one leaves the whole offset at vanilla's
    // value, since onHookDrawn never runs; losing a later one leaves the axes before it corrected
    // and the rest vanilla, which takes a mod owning exactly one of the three identical sites.
    private static boolean pendingOffsetValid;
    private static int nextOffsetAxis;
    private static final Vector3f pendingOffset = new Vector3f();

    // The world the correction failed in: vanilla's value is kept there, so an incompatible mod or
    // game update degrades to the vanilla line instead of crashing. It retries after the next
    // dimension change or world join (a new ClientWorld). Weak, so a stale world isn't kept alive.
    private static WeakReference<ClientWorld> disabledIn = new WeakReference<>(null);

    private ThirdPersonLineOrigin() {}

    /**
     * Called at the start of every rendered frame ({@code GameRenderer.render}): forgets the last
     * frame's pass and the last hook's pending offset.
     */
    public static void onFrameStart() {
        passCollector = null;
        passInEntityLoop = false;
        pendingOffsetValid = false;
    }

    /**
     * Wraps the iterator {@code WorldRenderer.render} draws the world's entities with, so that the
     * fishing bobbers come behind every other entity: a fishing player's body - and the rod in its
     * hand - is then always drawn before the hooks it cast, and the line starts on the rod of this
     * very frame rather than on the spot remembered from the last one. Both parts keep their order,
     * so the client's add order (and any order another mod sorted into) survives inside them.
     *
     * <p>Vanilla 1.20.1 does not need this: it iterates {@code ClientWorld.getEntities()} in the
     * order the client added them (see the class Javadoc), which already puts a body before the hooks
     * it cast. <b>Iris does.</b> Its {@code MixinLevelRenderer_EntityListSorting} (mixin priority 999,
     * in {@code iris-batched-entity-rendering.mixins.json}, which is not gated on a shader pack being
     * in use) wraps this very {@code Iterable.iterator()} call and regroups every entity by
     * {@code EntityType} through a {@code HashMap}, so that entity batching sees one type at a time.
     * {@code EntityType} inherits {@code Object.hashCode}, so which of the player and fishing-bobber
     * groups lands first is an identity hash - arbitrary, fixed for the session, and different from
     * one launch to the next. Without this wrapper Iris would have the last word and, in about half
     * of all launches with Iris installed, every hook would be drawn before every body for the whole
     * session. This wrapper takes whatever iterator that call ends up producing - Iris replaces the
     * call itself with a {@code @WrapOperation}, and a {@code @ModifyExpressionValue} reads the value
     * the call leaves however the two mixins are ordered - so it settles the order after Iris and
     * after anything else that regroups the list. (Mixin priority would not decide it: an expression
     * modifier is inserted right after the instruction, so a <em>higher</em> priority, applied later,
     * lands nearer the instruction and runs first, i.e. innermost.)
     *
     * <p>Creating the wrapper is also where the pass that loop draws in is recorded
     * ({@link #inEntityLoop}): the call it sits on is the loop's own, once per loop and before its
     * first entity. That record is what lets a rod and its hook be matched when a mod hands each
     * entity its own collector, which Iris with a shader pack does (see the class Javadoc). It is
     * two field writes, taken before the wrapper is even allocated.
     *
     * <p>It is lazy: it pulls from the source only as the loop asks, hands every non-bobber straight
     * on and keeps the bobbers in a list it only starts allocating when it meets one. By the time the
     * source is exhausted every bobber is in that list, so the tail is complete. With no bobber in
     * the world it is a type check and two reference field writes per *iterated* entity - every entity
     * {@code ClientWorld.getEntities()} yields, i.e. every loaded one, before the frustum cull drops
     * most of them - and no allocation beyond the wrapper itself, against the frustum test, the
     * block-position lookup and the whole entity render that vanilla does for each of them in the
     * same loop. The list lives on the
     * wrapper, which the loop drops with the frame, so no entity is held past it.
     *
     * <p>Reordering is as close to free of consequence as the renderer allows. Of the bobber's two
     * layers, {@code entity_cutout} on its own texture is opaque, depth-tested and depth-writing, and
     * {@code line_strip} blends but writes depth as well and carries alpha 255; neither is a
     * {@code translucent} layer, so neither is quad-sorted at draw time, and the order among the
     * entities' own translucent layers is unchanged, since a bobber contributes none. A glowing
     * bobber's outline copy ({@code entity_cutout} does affect the outline; {@code line_strip} does
     * not) carries the colour the entity loop sets for that entity right before it draws, so it is
     * order-independent too. What is left is the one pairing a reorder can decide: a bobber behind
     * an entity layer that blends and writes depth. {@code entity_translucent} does both, and every
     * player body is drawn with it ({@code PlayerEntityModel}), not just the see-through skins, so
     * that pairing is the ordinary one in a fishing scene - a bobber behind another player is now
     * always drawn after them and depth-rejected. What makes it a non-event is the layer's own alpha
     * cutout of 0.1 together with skins being alpha 0 or 255: a pixel that would hide the bobber hides
     * it under either order, and a pixel that wouldn't is discarded before it writes depth.
     *
     * <p>Nothing of the cost depends on it: neither bobber layer has a dedicated allocator in
     * {@code BufferBuilderStorage}, so each one flushes the fallback buffer as it is asked for, and
     * the line strip, whose draw mode shares vertices, is flushed again the moment the next line asks
     * for it - two draw calls per bobber wherever it sits. Moving one contiguous run only changes
     * which layers meet at the seam it leaves and at the end of the list, worth at most one draw call,
     * and only ever one fewer. With Iris' batching in front of it, a contiguous run moved to the end
     * keeps every other type's grouping intact.
     *
     * <p>Optional and defensive. What takes it out is a mod that keeps
     * {@link com.andrewchik.fishingrodfix.mixin.client.WorldRendererMixin} from applying, draws the
     * entities without going through that loop, or wraps the same call at a higher mixin priority.
     * Then the order is whatever that mod leaves and the remembered spot covers the losing side,
     * exactly as before this hook existed. Nothing here can throw on its own - it only delegates to
     * the source iterator, tests a type and appends to a list - so unlike the render-state branches'
     * walk, which asked vanilla for each bobber's owner, there is nothing to guard against.
     */
    public static Iterator<Entity> bobbersLast(Iterator<Entity> entities) {
        // The world's entity pass opens here: this is what ties the loop's drawings together when
        // their collectors differ (see the class Javadoc). Two field writes, and nothing that can
        // throw. Without frame counting the record would be indistinguishable from a later frame's,
        // so it isn't taken at all.
        if (HandPass.framesCounted()) {
            entityLoopFrame = HandPass.frame();
            entityLoopPassEnds = passEnds;
        }
        return new BobbersLast(entities);
    }

    /** Yields every non-bobber in the source's order, then the bobbers in theirs. */
    private static final class BobbersLast implements Iterator<Entity> {
        private final Iterator<Entity> source;
        private @Nullable ObjectArrayList<Entity> bobbers;
        private @Nullable Entity next;
        private int bobberIndex;

        BobbersLast(Iterator<Entity> source) {
            this.source = source;
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            while (source.hasNext()) {
                Entity entity = source.next();
                if (entity instanceof FishingBobberEntity) {
                    if (bobbers == null) {
                        bobbers = new ObjectArrayList<>();
                    }
                    bobbers.add(entity);
                } else {
                    next = entity;
                    return true;
                }
            }
            // The source is exhausted, so every bobber it had is in the list.
            if (bobbers != null && bobberIndex < bobbers.size()) {
                next = bobbers.get(bobberIndex++);
                return true;
            }
            return false;
        }

        @Override
        public Entity next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Entity entity = next;
            next = null;
            return entity;
        }
    }

    /**
     * Whether vanilla draws the local player's own body while it is the first-person camera (as
     * {@code WorldRenderer}'s entity loop decides): asleep, or with the camera moved into
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

    /** Called at the start of a hook's {@code render}. */
    public static void beginRender() {
        firstPersonVanilla = false;
        pendingBodyHeld = false;
        pendingOffsetValid = false;
    }

    /**
     * Called from {@code render}'s first-person branch when {@link FishingLineOrigin} kept vanilla's
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
     * Called once a hook's origin branch has run: whether its line moves onto the owner's
     * drawn rod, unless it is on the first-person rod or hidden.
     */
    public static void onHookDecided(@Nullable PlayerEntity owner, boolean onFirstPersonRod, boolean hidden, float partialTicks) {
        MinecraftClient mc = MinecraftClient.getInstance();
        pendingBodyHeld = false;
        try {
            if (onFirstPersonRod || hidden || !(owner instanceof AbstractClientPlayerEntity player) || disabledIn.get() == mc.world) {
                return;
            }
            // The owner's partial tick, as the entity loop draws it with: 1.20.1 has no tick manager,
            // so WorldRenderer.renderEntity lerps every entity at the frame's own progress, which is
            // the hook's too.
            pendingOwnerPartialTicks = partialTicks;
            pendingSamePassOnly = firstPersonVanilla;
            pendingBodyHeld = true;
        } catch (RuntimeException | LinkageError e) {
            // As every other entry from vanilla: vanilla's line until the next world.
            pendingBodyHeld = false;
            disable(mc, e);
        }
    }

    /**
     * Called where {@code HeldItemFeatureRenderer.renderItem} draws a held item, past its empty check,
     * so once per non-empty held item of an armed entity: small, so it inlines there; only a player's
     * fishing rod goes on. 1.20.1 hands that call
     * the stack and the entity itself, so the gate is the item test vanilla's own rule uses and a
     * type check, with no frame or owner bookkeeping behind it (the render-state branches, whose call
     * carries neither, have to key on the hooks extracted this frame or the previous one instead).
     */
    public static void onItemDrawn(LivingEntity entity, @Nullable ItemStack stack, @Nullable Arm arm, MatrixStack matrices, VertexConsumerProvider vertexConsumers) {
        // The gate dereferences nothing vanilla hands it: neither is ever null from vanilla, and a
        // mod passing one must get a vanilla line, not an exception out of the item layer.
        if (arm != null && stack != null && isRod(stack) && entity instanceof AbstractClientPlayerEntity player) {
            readDrawnRod(player, arm, matrices, vertexConsumers);
        }
    }

    /**
     * Whether {@code player}'s line belongs to a rod in {@code arm} right now: vanilla picks the side
     * with {@link FishingRodFix#armHoldingRod}, whose fallback is the off hand even for a player
     * holding no rod at all - which a lingering hook allows - so the item there is checked too. Both
     * the reading and the remembered offset key on this, and they have to stay in step.
     */
    private static boolean rodInArm(PlayerEntity player, Arm arm) {
        return arm == armHoldingRod(player) && isRod(stackInArm(player, arm));
    }

    /** Records a fishing player's rod as drawn, if the item drawn in {@code arm} is the one the line starts at. */
    private static void readDrawnRod(AbstractClientPlayerEntity player, Arm arm, MatrixStack matrices, VertexConsumerProvider vertexConsumers) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        // Without frame counting a reading couldn't be told from an older one.
        if (world == null || disabledIn.get() == world || player.fishHook == null || !HandPass.framesCounted()) {
            return;
        }
        try {
            if (!rodInArm(player, arm)) {
                return;
            }
            Owner owner = (Owner) player;
            BodyRod rod = owner.fishingrodfix$bodyRod();
            if (rod == null) {
                rod = new BodyRod();
                owner.fishingrodfix$setBodyRod(rod);
            }
            // The body as the entity loop drew it: WorldRenderer.renderEntity lerps its position at
            // the frame's tick progress and LivingEntityRenderer turns it by the body yaw below.
            float partialTicks = FishingRodFix.tickDelta(mc);
            matrices.peek().getPositionMatrix().transformPosition(ThirdPersonRod.lineAnchor(mc), rod.tip);
            rod.collector = vertexConsumers;
            rod.inEntityLoop = inEntityLoop();
            rod.frame = HandPass.frame();
            rod.passEnds = passEnds;
            rod.arm = arm;
            rod.bodyX = MathHelper.lerp(partialTicks, player.lastRenderX, player.getX());
            rod.bodyY = MathHelper.lerp(partialTicks, player.lastRenderY, player.getY());
            rod.bodyZ = MathHelper.lerp(partialTicks, player.lastRenderZ, player.getZ());
            rod.bodyYaw = bodyYaw(player, partialTicks);
            rod.pose = player.getPose();
            rod.riding = player.hasVehicle();
            // Drawn after a hook of this pass: that hook gives the world position to remember it at.
            // Only while pass ends are counted: without, a GUI picture drawn after the world in the
            // same frame (the inventory's player model) would pass for it.
            if (passEnds > 0 && drawnInLastHookPass(rod)) {
                Vector3f local = passInverse.transformPosition(rod.tip, scratch);
                // Only a finite reading: a mod that leaves the hook's pose singular makes passInverse
                // NaN, and a NaN offset would keep failing on the remembered path long after that frame.
                if (local.isFinite()) {
                    remember(rod, passHookX + local.x, passHookY + local.y, passHookZ + local.z);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            disable(mc, e);
        }
    }

    /**
     * Called where {@code render} stores the line offset's first component: works out where the line
     * starts, on the owner's rod drawn earlier in this pass or (not for a line flagged by
     * {@link #onFirstPersonVanilla}) where it was last drawn, for {@link #lineOffset}. The pose here
     * is the one the catenary is drawn with: {@code render} pushes twice, poses and pops the bobber's
     * copy, so the top of the stack is again what it was at the method's head.
     */
    public static void onHookDrawn(FishingBobberEntity hook, @Nullable PlayerEntity hookOwner, float tickDelta,
                                   MatrixStack matrices, VertexConsumerProvider vertexConsumers) {
        pendingOffsetValid = false;
        if (!pendingBodyHeld) {
            return;
        }
        // Only this drawing needs the decision.
        pendingBodyHeld = false;
        float ownerPartialTicks = pendingOwnerPartialTicks;
        boolean samePassOnly = pendingSamePassOnly;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (disabledIn.get() == mc.world || !(hookOwner instanceof AbstractClientPlayerEntity owner)) {
            return;
        }
        try {
            // The hook's pose origin, as WorldRenderer.renderEntity placed it: lerp(tickDelta,
            // lastRenderX, getX()), which EntityRenderDispatcher.render then translates the pose by, so
            // it is what passInverse is the inverse of. Deliberately not prevX/Y/Z: render uses those
            // for the bobber end of the line, and the two disagree whenever prev* and lastRender* do,
            // which is part of what this fix corrects.
            double hookX = MathHelper.lerp(tickDelta, hook.lastRenderX, hook.getX());
            double hookY = MathHelper.lerp(tickDelta, hook.lastRenderY, hook.getY());
            double hookZ = MathHelper.lerp(tickDelta, hook.lastRenderZ, hook.getZ());
            // The hook's pose maps its local frame (world axes, origin at the hook) into the pass's
            // space; the inverse brings a drawn rod back into it.
            matrices.peek().getPositionMatrix().invertAffine(passInverse);
            passCollector = vertexConsumers;
            passInEntityLoop = inEntityLoop();
            passFrame = HandPass.frame();
            passEndsAtHook = passEnds;
            passHookX = hookX;
            passHookY = hookY;
            passHookZ = hookZ;
            BodyRod rod = ((Owner) owner).fishingrodfix$bodyRod();
            if (rod == null) {
                return;
            }
            Vector3f local;
            if (passEnds > 0 && drawnInLastHookPass(rod)) {
                local = passInverse.transformPosition(rod.tip, scratch);
                // As in readDrawnRod: never remember a non-finite reading (see there).
                if (local.isFinite()) {
                    remember(rod, hookX + local.x, hookY + local.y, hookZ + local.z);
                }
            } else if (samePassOnly) {
                // A first-person line FishingLineOrigin left at vanilla's value, with no body drawn
                // before it in this pass: vanilla's value (see the class Javadoc).
                return;
            } else {
                local = remembered(owner, rod, ownerPartialTicks, hookX, hookY, hookZ);
                if (local == null) {
                    return;
                }
            }
            if (local.isFinite()) {
                pendingOffset.set(local.x, local.y - LINE_Y_OFFSET, local.z);
                nextOffsetAxis = 0;
                pendingOffsetValid = true;
            }
        } catch (RuntimeException | LinkageError e) {
            disable(mc, e);
        }
    }

    /** Called where a pass ends: the world's entity loop at its flush, a buffer source's at its full flush. */
    public static void onPassEnded() {
        passEnds++;
    }

    /**
     * Whether the drawing happening right now belongs to the world's entity loop: the hook on its
     * iterator recorded the pass it opened, and nothing has ended a pass since. Read when a rod or a
     * hook is drawn, never later - Iris' shadow pass runs before that record is taken, so its
     * drawings come out false even though the loop that follows them will carry the same frame and
     * pass-end count.
     */
    private static boolean inEntityLoop() {
        return HandPass.frame() == entityLoopFrame && passEnds == entityLoopPassEnds;
    }

    /**
     * Whether {@code rod} was last drawn in the pass of the latest hook drawn: the same frame and the
     * same number of ended passes, and either the same collector or both inside the world's entity
     * loop, which ties its drawings together where a mod gives each entity its own collector (see the
     * class Javadoc).
     */
    private static boolean drawnInLastHookPass(BodyRod rod) {
        return rod.frame == passFrame && rod.passEnds == passEndsAtHook
                && (rod.collector == passCollector || (rod.inEntityLoop && passInEntityLoop));
    }

    /**
     * {@code render}'s line offset component {@code axis} (0 = x, 1 = y, 2 = z): the one
     * {@link #onHookDrawn} worked out, or vanilla's {@code value}. An axis is only taken when the one
     * before it was, so a mod that keeps one of the three optional {@code @ModifyVariable}s from
     * applying stops the correction there instead of letting a later axis pick up an offset that was
     * never started. Losing the first one leaves the whole offset at vanilla's value ({@link
     * #onHookDrawn} never runs); losing a later one leaves the axes before it corrected.
     */
    public static float lineOffset(int axis, float value) {
        if (!pendingOffsetValid || axis != nextOffsetAxis) {
            return value;
        }
        // x (z) is render's last offset local.
        if (axis == 2) {
            pendingOffsetValid = false;
        } else {
            nextOffsetAxis = axis + 1;
        }
        return pendingOffset.get(axis);
    }

    /**
     * Where the owner's rod was last drawn, relative to the body now, relative to the hook; null when
     * nothing usable was drawn (another arm, no rod held, or a pose or mount change with nothing but
     * an old reading behind it).
     */
    private static @Nullable Vector3f remembered(PlayerEntity owner, BodyRod rod, float partialTicks,
                                                 double hookX, double hookY, double hookZ) {
        Arm arm = rod.offsetArm;
        if (arm == null || !rodInArm(owner, arm)) {
            return null;
        }
        if (owner.getPose() != rod.offsetPose || owner.hasVehicle() != rod.offsetRiding) {
            // The pose or the mount changed since the rod was last drawn, so the offset no longer
            // holds - and it is kept anyway, because a one-frame lag is far less visible than a
            // one-frame teleport, not because it is the closer of the two candidates. Usually it
            // isn't: measured against the tip as drawn on the transition frame, vanilla's value is
            // nearer for most of them - leaving GLIDING or SPIN_ATTACK 2.15 blocks or more for the
            // kept reading against 0.12 (the renderer's 90 degree body rotation about the feet goes
            // in one step), waking 0.8-2.1 against 0.12 (setupTransforms tips the body onto its
            // side by the bed's direction instead of turning it by 180 - bodyYaw), a crouch either
            // way 0.54 against 0.07 (getStandingEyeHeight follows the pose and render adds
            // -0.1875 while sneaking) - and the kept reading only wins leaving SWIMMING (1.04,
            // PlayerEntityRenderer's dropped (0, -1, 0.3) translate, against 2.12, since the body
            // is still leaning that frame) and lying down (0.81 against 1.39). A mount change or a
            // lean that ramps up is small either way. What the leniency buys is the frame the
            // origin would otherwise jump to a different anchor and back, which is the visible
            // artifact. An older reading is not kept: it has no lag to preserve, only an unrelated
            // position. With bobbersLast in place this whole path is a fallback and not the
            // usual one: the rod is normally drawn before the hook and this frame's reading is
            // used. What is left for it are the passes the wrapper doesn't order, a hook the server
            // sent before its owner, a body another mod draws after the hooks, and the frames where
            // the owner's body wasn't drawn at all - out of view, or into another buffer source -
            // which no draw order can fix.
            if (rod.offsetFrame < HandPass.frame() - 1) {
                return null;
            }
        }
        // The body turns about its position (LivingEntityRenderer.setupTransforms: 180 - yaw; the extra
        // +-1.26 degree wobble it adds to a frozen body is not reproduced, so a player shaking in
        // powder snow is off by up to a couple of centimetres on this path), except
        // asleep, where the same method turns it by the bed's direction instead (only falling back to
        // the body yaw if the bed is gone) and the body yaw doesn't move it. The rule follows the
        // pose the reading was taken in, not the owner's now, since that is the convention the offset
        // is in: reproducing it any other way would rotate a bed-space offset by a meaningless yaw
        // delta, or skip a rotation an awake one needs. On the one frame the two can differ the
        // difference is nil in practice - a sleeping reading is applied unrotated, and an awake one
        // against a body that has just fallen asleep is rotated by a yaw delta of about zero, because
        // LivingEntity.sleep doesn't touch the body yaw.
        Vector3f offset = scratch.set(rod.offset);
        if (rod.offsetPose != EntityPose.SLEEPING) {
            offset.rotateY(MathHelper.RADIANS_PER_DEGREE * (rod.offsetYaw - bodyYaw(owner, partialTicks)));
        }
        return offset.set(
                (float) (MathHelper.lerp(partialTicks, owner.lastRenderX, owner.getX()) - hookX + offset.x),
                (float) (MathHelper.lerp(partialTicks, owner.lastRenderY, owner.getY()) - hookY + offset.y),
                (float) (MathHelper.lerp(partialTicks, owner.lastRenderZ, owner.getZ()) - hookZ + offset.z));
    }

    /**
     * Remembers the drawn tip, at world position {@code (x, y, z)}, relative to the body it was drawn
     * with.
     *
     * <p>The body position is the lerped entity position, while the tip came through a pose
     * {@code EntityRenderDispatcher.render} had translated by that position <em>plus</em>
     * {@code EntityRenderer.getPositionOffset}. The remembered offset therefore carries the reading
     * frame's position offset, and {@link #remembered} adds it to a body position that has none -
     * which cancels exactly as long as the offset is the same in both frames, and that is all of it
     * here. A player's is only the sneaking shift, and on 1.20.1 a constant one
     * ({@code PlayerEntityRenderer}: {@code -0.125}, where 1.21.1 scales it by the entity's scale and
     * 1.21.2 adds the experimental minecart's lerp on top), so it is a pure function of the pose the
     * remembered path already checks and cancels exactly. Re-deriving it here would be modelling the
     * pose, which is the one thing this class does not do.
     */
    private static void remember(BodyRod rod, double x, double y, double z) {
        rod.offset.set((float) (x - rod.bodyX), (float) (y - rod.bodyY), (float) (z - rod.bodyZ));
        rod.offsetYaw = rod.bodyYaw;
        rod.offsetArm = rod.arm;
        rod.offsetPose = rod.pose;
        rod.offsetRiding = rod.riding;
        rod.offsetFrame = rod.frame;
    }

    /**
     * The body's yaw as drawn: {@code LivingEntityRenderer.render}'s own computation (a rider's turns
     * towards its head, within the mount's limits), mirrored. 1.20.1 keeps no render state to read it
     * off, so both the reading and the remembered offset use this.
     */
    private static float bodyYaw(PlayerEntity owner, float partialTicks) {
        if (owner.getVehicle() instanceof LivingEntity riding) {
            float headYaw = MathHelper.lerpAngleDegrees(partialTicks, owner.prevHeadYaw, owner.headYaw);
            float bodyYaw = MathHelper.lerpAngleDegrees(partialTicks, riding.prevBodyYaw, riding.bodyYaw);
            float headDiff = MathHelper.clamp(MathHelper.wrapDegrees(headYaw - bodyYaw), -RIDER_MAX_HEAD_DIFF, RIDER_MAX_HEAD_DIFF);
            bodyYaw = headYaw - headDiff;
            if (Math.abs(headDiff) > RIDER_TURN_THRESHOLD) {
                bodyYaw += headDiff * RIDER_TURN_FACTOR;
            }
            return bodyYaw;
        }
        return MathHelper.lerpAngleDegrees(partialTicks, owner.prevBodyYaw, owner.bodyYaw);
    }

    private static void disable(MinecraftClient mc, Throwable e) {
        disabledIn = new WeakReference<>(mc.world);
        LOGGER.error("Third-person fishing line correction failed, falling back to vanilla until the next world or dimension", e);
    }
}
