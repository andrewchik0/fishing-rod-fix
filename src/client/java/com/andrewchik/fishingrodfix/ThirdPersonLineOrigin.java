package com.andrewchik.fishingrodfix;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.client.render.entity.state.FishingBobberEntityState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.ref.WeakReference;
import java.util.List;

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
 * {@code HeldItemFeatureRenderer.renderItem} draws a rod in the hand of a player who is fishing, the
 * pose it draws with (whatever vanilla and other mods made of the body, arm and item up to there)
 * places {@link ThirdPersonRod}'s attachment point, in the drawing space, tagged with the pass
 * it was drawn in: the {@link HandPass} frame, the number of passes that have ended, and either the
 * buffer source or - for a drawing inside the world's entity loop - that loop (see the next
 * paragraph). On 1.21.8 nothing is deferred and one {@code VertexConsumerProvider.Immediate} (the
 * {@code GameRenderer}'s) takes every vanilla pass of a frame (the world's entities and block
 * entities, the first-person hand, the screen effects and the GUI's pictures such as the inventory's
 * player model), so a pass ends where {@code WorldRenderer.renderEntities} returns and wherever a
 * buffer source flushes everything it holds.
 * When the hook is drawn after the rod in the same pass, its line starts at that point, taken through
 * the inverse of the hook's own pose (so a pass that turns its root, like a shadow pass, cancels out).
 * The line's offset goes straight into {@code render}'s locals; the render state's {@code pos} keeps
 * vanilla's value.
 *
 * <p>The collector identity alone would not do, because a mod may hand each entity its own.
 * <b>Iris with a shader pack does</b>: its {@code entity_render_context/MixinEntityRenderDispatcher}
 * {@code @ModifyVariable}s the buffer-source argument of every {@code EntityRenderDispatcher.render}
 * into a freshly allocated {@code BufferSourceWrapper} as soon as a pack has set
 * {@code WorldRenderingSettings.entityIds} - which nothing resets, so it outlives unloading the pack
 * until the game restarts. A rod and its hook then never share one, and with the collector as the
 * only tie the whole body-held path would be off for every shader user. What the two do share is the
 * loop that drew them: the head hook of {@link com.andrewchik.fishingrodfix.mixin.client.WorldRendererMixin}
 * runs once, before the first entity, and records the pass the loop opens in
 * ({@link HandPass#frame()} and the pass-end count); every drawing notes whether it happened while
 * that record still held, and the match takes two such drawings of one pass as the same pass even
 * when their collectors differ. The pose is the loop's own {@code MatrixStack} either way, so the two
 * readings are in the same space - which is what the collector was ever a proxy for. Three things
 * stay out of that window by construction: the <b>GUI's</b> pictures, drawn past the loop's end mark
 * and past every buffer-source flush, so their pass-end count differs; <b>Iris' shadow pass</b>,
 * which never goes through {@code renderEntities} and is drawn before the loop's head records
 * anything, so its drawings see the previous frame's record and note themselves as outside (it keeps
 * pairing by collector, as it did before: its own one buffer source without a pack, per-entity
 * wrappers with one); and anything in a later frame, since the record carries the frame. A body drawn
 * inside the loop but into another collector is now matched where it used to miss: a glowing player
 * or a spectator's outlines ({@code OutlineVertexConsumerProvider}), and a mod's own buffer source
 * used inside the loop. For one drawn <em>after</em> the hooks (Real Camera's binding mode, its
 * default, at the loop's very return; its classic mode already shared the pass's collector) that
 * means the remembered spot is refreshed in the same pass instead of never; for one drawn before
 * them the line sits on this frame's rod. The head hook is optional: without it nothing is ever
 * noted as inside a loop and the match is the collector's alone, exactly as before. Two things the
 * window does not cover: a body drawn at the loop's very head, before the record is taken (First
 * Person Model injects there at the default priority, below this hook's), which with an Iris pack
 * is then the one body inside the loop that isn't matched; and the assumption itself - the window
 * says two drawings share the loop's own {@code MatrixStack} space, which every mod checked does,
 * but a mod drawing a body through a stack rooted elsewhere would now be matched where the
 * collector tag used to refuse it.
 *
 * <p>The remembered offset carries one thing of the frame it was read in: the render offset
 * {@code EntityRenderer.getPositionOffset(S)} adds on top of the entity's position, which the pose
 * includes and the body position it is measured against does not. It cancels while the offset is
 * the same in both frames, and a player's has only two parts - the crouch shift, which moves with
 * the pose the remembered path already gates on, and the experimental minecart's lerp, which does
 * not, and is then a frame of that lerp out on this fallback path. Re-deriving it would mean
 * re-deriving a vanilla method any mod may override, at both the reading and the replay, which is
 * the modelling this class exists to avoid; see {@link #remember}.
 *
 * <p>On 1.21.6-1.21.8 the order the bodies and the hooks are drawn in would otherwise be a coin toss:
 * {@code WorldRenderer} sorts the entities it is about to draw by {@code entity.getType().hashCode()}
 * ({@code ENTITY_COMPARATOR}, added in 1.21.6 and gone again in 1.21.9), and {@code EntityType}
 * inherits {@code Object.hashCode}, so which of the player and fishing-bobber groups comes first is an
 * identity hash - arbitrary, and different from one launch to the next. {@link #sortBobbersLast} takes
 * that choice away: from the first frame a hook is on screen it moves the bobbers behind everything
 * else just before the loop, so a body-held line is placed on the rod of this very frame, in every
 * launch alike. Where the sort doesn't apply - it is optional, and a mod may draw the entities
 * without it or re-order them after it - both orders still work; the one that puts the hooks first
 * falls back to the
 * remembered spot below, one frame late, while the rod, drawn later in the same pass, refreshes that
 * spot at the hook's own world position.
 *
 * <p>1.21.8 also extracts and draws each entity in turn ({@code EntityRenderDispatcher.render}), so
 * the owners of a pass's hooks are not all known when the first rods are drawn - where 1.21.9 and
 * 26.x extract every entity before submitting any, and can gate on this frame alone. The gate at the
 * held-item call therefore takes the hooks of this frame <em>and</em> the previous one, and every
 * player's hook counts for it, not only one whose line is body-held: a rod drawn before its hook is
 * read on the strength of the hook the frame before. For the world's own pass the missing half is
 * supplied a step earlier instead: {@link #sortBobbersLast} walks the draw list before the loop and
 * stamps the owners of the bobbers in it, so a hook is read in the very frame it appears, as on the
 * later branches. What still falls back for a frame is a hook outside that list - one whose pass the
 * walk doesn't see, such as Iris' shadow pass, or a body another mod draws at {@code renderEntities}'
 * own head, before this walk gets there - and then only until the next frame opens the window.
 * A hook drawn before its owner - a pass the sort doesn't order - is read in its first frame, later
 * in the same pass.
 *
 * <p>Where the rod isn't drawn in this pass before the hook - the owner out of view, a hook that
 * arrived before its owner, a body drawn after the hooks (Real Camera's classic mode), or the sort
 * hook not applying - the line starts where the rod was last drawn, relative to the body's position
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
 * <p>The local player's line on {@code getHandPos}' first-person branch that {@link FishingLineOrigin}
 * left at vanilla's value (see its Javadoc) while the camera is on that player (and vanilla, with the
 * camera in first person and the player awake, doesn't draw the body) only moves onto a body drawn
 * earlier in the same pass: one a mod draws in first person without changing the branch (Player
 * Animation Library's first-person model mode). Not Iris' shadow pass: it exists only with a shader
 * pack, and with one every entity render gets its own buffer source, so its rod and its hook never
 * pair (it never calls {@code renderEntities} either - it sorts its own list by the same
 * {@code EntityType} identity hash - so neither the sort hook nor the loop tie reaches it).
 * Where the rod was last drawn doesn't count for it: no body is on screen, and an
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
     * Mixed into {@code FishingBobberEntityState}: the owner whose drawn rod the line moves onto when
     * the hook is drawn (null for a line on the first-person rod, or with no player owner), the owner's
     * partial tick, and whether only a rod drawn earlier in the same pass counts (see
     * {@link #onFirstPersonVanilla}). The owner is dropped when the state is drawn: on 1.21.8 one
     * {@code EntityRenderer} keeps one render state for every entity of its type, for the client's
     * whole life, so the last hook of a world would otherwise keep its owner — and its world — alive
     * until the next hook is extracted. Other mods keep render states past their frame too (Iris'
     * shadow pass keeps its last frame's until its next one, also after a disconnect).
     */
    public interface HookState {
        @Nullable AbstractClientPlayerEntity fishingrodfix$bodyRodOwner();

        float fishingrodfix$ownerPartialTicks();

        boolean fishingrodfix$samePassOnly();

        void fishingrodfix$setBodyRodOwner(@Nullable AbstractClientPlayerEntity owner, float ownerPartialTicks, boolean samePassOnly);
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

    // renderFishingLine draws the line 0.25 above the hook's position, and getHandPos' result is
    // turned into pos relative to that point (updateRenderState: hookPos + (0, 0.25, 0)).
    private static final float LINE_Y_OFFSET = 0.25f;

    // LivingEntityRenderer.clampBodyYaw: a rider's body turns with its head, within these limits of
    // the mount's body.
    private static final float RIDER_MAX_HEAD_DIFF  = 85f;
    private static final float RIDER_TURN_THRESHOLD = 50f;
    private static final float RIDER_TURN_FACTOR    = 0.2f;

    // The frame a player's hook was last extracted in, and the owners of this frame's and the previous
    // frame's hooks with the arm each draws its rod in (ownerKey): rods are only read in those frames,
    // only for those players and only in that arm, so every other frame costs the held-item call one
    // compare per drawn item, and every other drawn item (another player's, an armour stand's, the
    // fishing player's other hand) one or two set lookups. 1.21.8's held-item call has no stack to
    // tell a rod by, so this stands in for a rod check; what is drawn in that arm is still checked
    // out of line. Both sets are bounded by the hooks extracted in one frame; the frame's own is
    // cleared and they are swapped at the frame's start. The previous frame's is needed for the
    // passes the bobbers-last walk doesn't see (Iris' shadow pass, a mod's own entity loop, a body
    // drawn at renderEntities' head before the walk gets there): for the world's own pass the walk
    // stamps this frame's owners before any entity is drawn (see the class Javadoc).
    private static long bodyHookFrame = Long.MIN_VALUE;
    private static IntOpenHashSet bodyHookOwners = new IntOpenHashSet();
    private static IntOpenHashSet lastBodyHookOwners = new IntOpenHashSet();

    // Whether the hook being extracted took getHandPos' first-person branch with the camera on its
    // owner and FishingLineOrigin kept vanilla's value there (see the class Javadoc). Cleared at the
    // start of updateRenderState, set inside it and read at its end.
    private static boolean firstPersonVanilla;

    // Passes that have ended so far, from two optional hooks: WorldRenderer.renderEntities' return,
    // and any VertexConsumerProvider.Immediate.draw(), which ends whatever was drawn into that buffer
    // source (on 1.21.8 one of them serves every vanilla pass of a frame). The first is what separates
    // the world's entities from the GUI's pictures when a mod replaces the buffer sources and
    // vanilla's draw() never runs (ImmediatelyFast's BatchableBufferSource overrides it), the second
    // what separates passes that share one entity loop with a mod's own buffer source. 0 while
    // neither hook applies, and then no pass is matched at all.
    private static long passEnds;

    // The pass the world's entity loop is running in, from the optional head hook: the frame it
    // started in and the pass-end count it started at. Everything drawn while both still hold is one
    // of that loop's own drawings, whatever buffer source it went into - which is what keeps the
    // body-held path working where a mod gives each entity its own collector (Iris with a shader
    // pack; see the class Javadoc). The loop's end mark raises passEnds, and so does every flush
    // between the loop and the GUI, so the window closes on its own; a frame that never reaches the
    // head hook leaves the previous frame's record, which the frame check rejects.
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

    // The line offset for the hook being drawn, written into render's f/g/h locals. Cleared once
    // h is written, and at the next frame's start in case another mod cut render short, so no render
    // state outlives its drawing here.
    private static @Nullable FishingBobberEntityState pendingState;
    private static final Vector3f pendingOffset = new Vector3f();

    // The world the correction failed in: vanilla's value is kept there, so an incompatible mod or
    // game update degrades to the vanilla line instead of crashing. It retries after the next
    // dimension change or world join (a new ClientWorld). Weak, so a stale world isn't kept alive.
    private static WeakReference<ClientWorld> disabledIn = new WeakReference<>(null);

    // The bobbers taken out of the entity list while sortBobbersLast compacts it. Reused every frame
    // and always emptied in the same call, so it never holds an entity, or its world, past the call.
    private static final ObjectArrayList<Entity> heldBackBobbers = new ObjectArrayList<>();

    // Set once if the reordering ever throws: only that part stops, for the rest of the run.
    private static boolean sortFailed;

    private ThirdPersonLineOrigin() {}

    /**
     * Called at the start of every rendered frame ({@code GameRenderer.render}): forgets the last
     * frame's pass and lets this frame's hooks start a new owner set, keeping the last frame's.
     */
    public static void onFrameStart() {
        passCollector = null;
        passInEntityLoop = false;
        pendingState = null;
        IntOpenHashSet spent = lastBodyHookOwners;
        lastBodyHookOwners = bodyHookOwners;
        bodyHookOwners = spent;
        bodyHookOwners.clear();
    }

    /**
     * Moves the fishing bobbers of {@code entities} behind every other entity, just before
     * {@code WorldRenderer} draws them, so that a fishing player's body - and the rod in its hand -
     * is always drawn before the hooks it cast and the line starts on the rod of this very frame
     * rather than on the spot remembered from the last one. Both parts keep their order, so vanilla's
     * grouping by entity type (and any order another mod sorted into) survives inside them.
     *
     * <p>Running once, before the loop's first entity, it is also where the pass that loop draws in
     * is recorded ({@link #inEntityLoop}): that record is what lets a rod and its hook be matched
     * when a mod hands each entity its own collector, which Iris with a shader pack does (see the
     * class Javadoc). The record is taken first, before anything below can decline to reorder the
     * list, and it is two field writes.
     *
     * <p>The same walk opens the rod-reading gate for the hooks it passes, which is what makes the
     * first frame of a cast exact here. From 1.21.9 on, and on 26.x, vanilla extracts every entity's
     * render state before it submits any of them, so a hook's extraction - where the gate is stamped -
     * always comes before its owner's rod is submitted in that frame, and those branches gate on this
     * frame alone. 1.21.6-1.21.8 interleaves the two per entity, so in vanilla's own flow no hook of
     * this frame has been extracted by the head of the loop and the gate would only hold the previous
     * frame's; a hook's
     * first frame would then read no rod at all and its line would fall back for that frame. The draw
     * list is that missing knowledge, available one step early: every bobber in it is extracted a
     * moment later in the very loop this precedes, so stamping its owner here says nothing that the
     * extraction won't repeat. The two-frame window stays for the passes this walk doesn't see (Iris'
     * shadow pass, a mod drawing the entities itself).
     *
     * <p>That is why the walk is unconditional rather than gated on a hook having been seen: a gate
     * would skip the one frame the walk is needed for. With no bobber in the list it is a type check
     * per drawn entity and nothing else - no write, since the two indices never part, and no
     * allocation; each bobber then adds the {@code Optional} {@code LazyEntityReference.resolve}
     * makes inside {@code getPlayerOwner}, which escape analysis may well drop and which there are
     * only ever a handful of. The statement right before the injection point is
     * {@code renderedEntities.sort(ENTITY_COMPARATOR)} over the same list, an O(n log n) sort whose
     * key extractor boxes two {@code Integer}s per comparison, so this linear pass is a small
     * fraction of the work vanilla does with the list in the same frame.
     *
     * <p>Reordering is as close to free of consequence as the renderer allows. Of the bobber's two
     * layers, {@code entity_cutout} on its own texture is opaque, depth-tested and depth-writing, and
     * {@code line_strip} blends but writes depth as well and carries alpha 255; neither is a
     * {@code translucent} layer, so neither is quad-sorted at draw time, and the order among the
     * entities' own translucent layers is unchanged, since a bobber contributes none. A glowing
     * bobber's outline copy ({@code entity_cutout} does affect the outline; {@code line_strip} does
     * not) carries the colour {@code renderEntities} sets for that entity right before it draws, so it
     * is order-independent too. What is left is the one pairing a reorder can decide: a bobber behind
     * an entity layer that blends and writes depth. {@code entity_translucent} does both, and every
     * player body is drawn with it ({@code PlayerEntityModel}), not just the see-through skins, so
     * that pairing is the ordinary one in a fishing scene - a bobber behind another player is now
     * always drawn after them and depth-rejected, where before it was a coin toss whether it showed
     * through. What makes it a non-event is the layer's own alpha cutout of 0.1 together with skins
     * being alpha 0 or 255: a pixel that would hide the bobber hides it under either order, and a
     * pixel that wouldn't is discarded before it writes depth. Vanilla's own order, an identity hash
     * of the {@code EntityType} objects, threw that coin afresh in every launch in any case.
     *
     * <p>Nothing of the cost depends on it: neither bobber layer has a dedicated allocator in
     * {@code BufferBuilderStorage}, so each one flushes the fallback buffer as it is asked for, and
     * the line strip, whose draw mode shares vertices, is flushed again the moment the next line asks
     * for it - two draw calls per bobber wherever it sits. Moving one contiguous run only changes
     * which layers meet at the seam it leaves and at the end of the list, worth at most one draw call,
     * and only ever one fewer.
     *
     * <p>Optional and defensive. It sits after vanilla's own sort, so a mod that replaces or cancels
     * {@code ENTITY_COMPARATOR} changes nothing here; what does take it out is a mod that keeps
     * {@link com.andrewchik.fishingrodfix.mixin.client.WorldRendererMixin} from applying, draws the
     * entities without going through {@code renderEntities}, or re-orders the list from a head inject
     * that applies after this one (a higher mixin priority, or an {@code @Inject(order = )} above the
     * default 1000, which is sorted before priority is). Then the order is vanilla's coin toss again
     * and the remembered spot covers the losing side, exactly as before this hook existed. A failure
     * here disables only the reordering, logged once, and leaves the rest of the correction running,
     * since the remembered spot is built for an order that isn't ours.
     */
    public static void sortBobbersLast(List<Entity> entities) {
        MinecraftClient mc = MinecraftClient.getInstance();
        // The world's entity pass opens here, before anything below can decline to reorder it: this
        // is what ties the loop's drawings together when their collectors differ (see the class
        // Javadoc). Two field writes, and nothing that can throw. Without frame counting the record
        // would be indistinguishable from a later frame's, so it isn't taken at all.
        if (HandPass.framesCounted()) {
            entityLoopFrame = HandPass.frame();
            entityLoopPassEnds = passEnds;
        }
        if (sortFailed || disabledIn.get() == mc.world) {
            return;
        }
        // Without frame counting a reading couldn't be told from an older one, and the owners would
        // pile up over the session: then the gate stays shut, exactly as at the hook's extraction.
        boolean counted = HandPass.framesCounted();
        try {
            int size = entities.size();
            int write = 0;
            int held = 0;
            for (int read = 0; read < size; read++) {
                Entity entity = entities.get(read);
                if (entity instanceof FishingBobberEntity) {
                    heldBackBobbers.add(entity);
                    held++;
                } else {
                    // Without a bobber the two indices never part and nothing is written at all.
                    if (write != read) {
                        entities.set(write, entity);
                    }
                    write++;
                }
            }
            for (int i = 0; i < held; i++) {
                entities.set(write + i, heldBackBobbers.get(i));
            }
            // Only once the list is whole again: the two calls below are vanilla's, and a throw from
            // either of them must not be able to leave a half-compacted list behind.
            if (counted) {
                for (int i = 0; i < held; i++) {
                    // The same key onHookExtracted stores a moment later, from the same owner and the
                    // same arm; adding it twice is what an IntOpenHashSet is for.
                    FishingBobberEntity hook = (FishingBobberEntity) heldBackBobbers.get(i);
                    if (hook.getPlayerOwner() instanceof PlayerEntity owner) {
                        bodyHookFrame = HandPass.frame();
                        bodyHookOwners.add(ownerKey(owner.getId(), FishingBobberEntityRenderer.getArmHoldingRod(owner)));
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // The list is left whole. Its own compaction only calls List.get and List.set: a list that
            // refuses to be written throws on the first write, which is also the first change, and
            // ArrayList.set cannot throw here, while the two vanilla calls that could throw a
            // LinkageError run after the last of those writes. Only the reordering stops - everything
            // else falls back the way it always did.
            sortFailed = true;
            LOGGER.error("Ordering the fishing bobbers last failed, leaving the draw order to vanilla", e);
        } finally {
            heldBackBobbers.clear();
        }
    }

    /**
     * Whether vanilla draws the local player's own body while it is the first-person camera (as
     * {@code WorldRenderer}'s entity collection decides): asleep, or with the camera moved into
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
     * moves onto when the hook is drawn, unless the line is on the first-person rod or hidden.
     */
    public static void onHookExtracted(FishingBobberEntityState state, @Nullable PlayerEntity owner, boolean onFirstPersonRod, boolean hidden,
                                       float partialTicks) {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            HookState hookState = (HookState) state;
            if (!(owner instanceof AbstractClientPlayerEntity player) || disabledIn.get() == mc.world) {
                hookState.fishingrodfix$setBodyRodOwner(null, 0f, false);
                return;
            }
            // Every hook of a player opens the gate at the held-item call, whether or not its own line
            // moves onto the body's rod: the gate reads the previous frame's hooks as well (a rod may be
            // drawn before its hook in a frame), so a line that only now becomes body-held (F5, lying
            // down, a mod drawing the body) would otherwise lose the frame it changes in. Without
            // frame counting nothing is read (and the owners would pile up over the session).
            if (HandPass.framesCounted()) {
                bodyHookFrame = HandPass.frame();
                bodyHookOwners.add(ownerKey(player.getId(), FishingBobberEntityRenderer.getArmHoldingRod(player)));
            }
            if (onFirstPersonRod || hidden) {
                hookState.fishingrodfix$setBodyRodOwner(null, 0f, false);
                return;
            }
            // The owner's partial tick, as WorldRenderer.renderEntities draws it with: players never
            // skip a tick (TickManager.shouldSkipTick), so it is the hook's unless the tick rate is
            // frozen.
            float ownerPartialTicks = player.getWorld().getTickManager().shouldTick()
                    ? partialTicks
                    : mc.getRenderTickCounter().getTickProgress(true);
            hookState.fishingrodfix$setBodyRodOwner(player, ownerPartialTicks, firstPersonVanilla);
        } catch (RuntimeException | LinkageError e) {
            // As every other entry from vanilla: vanilla's line until the next world. A line left with
            // an owner here drops it unused when it is drawn, which runs before anything reads it.
            disable(mc, e);
        }
    }

    /**
     * Called where {@code HeldItemFeatureRenderer.renderItem} draws a held item, past its empty check,
     * so once per non-empty held item of an armed entity: small, so it inlines there; only an arm a
     * player whose hook was extracted this frame or the previous one had its rod in goes on (1.21.8's call has no stack: whether the item drawn
     * there is that player's rod <em>now</em>, and in <em>this</em> arm, is decided out of line, since
     * a key from the previous frame carries that frame's arm).
     */
    public static void onItemDrawn(ArmedEntityRenderState state, @Nullable Arm arm, MatrixStack matrices, VertexConsumerProvider vertexConsumers) {
        // arm is never null from vanilla; a mod passing null must not throw out of the item drawing.
        if (arm != null && bodyHookFrame >= HandPass.frame() - 1 && state instanceof PlayerEntityRenderState player) {
            int key = ownerKey(player.id, arm);
            if (lastBodyHookOwners.contains(key) || bodyHookOwners.contains(key)) {
                readDrawnRod(player, arm, matrices, vertexConsumers);
            }
        }
    }

    /** A hook owner and the arm its rod is drawn in, as one {@code bodyHookOwners} key. */
    private static int ownerKey(int entityId, Arm arm) {
        return entityId * 2 + arm.ordinal();
    }

    /**
     * Whether {@code player}'s line belongs to a rod in {@code arm} right now: vanilla picks the side
     * with {@code getArmHoldingRod}, whose fallback is the off hand even for a player holding no rod
     * at all - which a lingering hook allows - so the item there is checked too. Both the reading and
     * the remembered offset key on this, and they have to stay in step.
     */
    private static boolean rodInArm(PlayerEntity player, Arm arm) {
        return arm == FishingBobberEntityRenderer.getArmHoldingRod(player) && isRod(player.getStackInArm(arm));
    }

    /** Records a fishing player's rod as drawn, if the item drawn in {@code arm} is it. */
    private static void readDrawnRod(PlayerEntityRenderState state, Arm arm, MatrixStack matrices, VertexConsumerProvider vertexConsumers) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        // Without frame counting a reading couldn't be told from an older one.
        if (world == null || disabledIn.get() == world || !HandPass.framesCounted()) {
            return;
        }
        try {
            // The rod itself (rodInArm). The arm has to be re-checked live: the gate also admits the
            // previous frame's key, whose arm is the one getArmHoldingRod named then, and that is the
            // other arm on the frame a rod arrives in the main hand while the off hand already holds
            // one (only the main hand's rod-ness moves getArmHoldingRod, so nothing else flips it).
            // Both arms are drawn that frame, and with the default right main hand the stale one is
            // the left, which the layer draws second, so it would overwrite the right reading. The
            // item state was extracted from getStackInArm just before this drawing, and nothing ticks
            // in between, so the live stack is the one drawn.
            if (!(world.getEntityById(state.id) instanceof AbstractClientPlayerEntity player) || player.fishHook == null
                    || !rodInArm(player, arm)) {
                return;
            }
            Owner owner = (Owner) player;
            BodyRod rod = owner.fishingrodfix$bodyRod();
            if (rod == null) {
                rod = new BodyRod();
                owner.fishingrodfix$setBodyRod(rod);
            }
            matrices.peek().getPositionMatrix().transformPosition(ThirdPersonRod.lineAnchor(mc), rod.tip);
            rod.collector = vertexConsumers;
            rod.inEntityLoop = inEntityLoop();
            rod.frame = HandPass.frame();
            rod.passEnds = passEnds;
            rod.arm = arm;
            rod.bodyX = state.x;
            rod.bodyY = state.y;
            rod.bodyZ = state.z;
            rod.bodyYaw = state.bodyYaw;
            rod.pose = state.pose;
            // 1.21.8's LivingEntityRenderState carries no riding flag; the entity's is the one it was
            // extracted with (nothing ticks between this frame's extraction and this drawing).
            rod.riding = player.hasVehicle();
            // Drawn after a hook of this pass: that hook gives the world position to remember it at.
            // Only while pass ends are counted: without, a GUI picture drawn after the world in the
            // same frame (the inventory's player model) would pass for it.
            if (passEnds > 0 && drawnInLastHookPass(rod)) {
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
    public static void onHookDrawn(FishingBobberEntityState state, MatrixStack matrices, VertexConsumerProvider vertexConsumers) {
        pendingState = null;
        HookState hookState = (HookState) state;
        AbstractClientPlayerEntity owner = hookState.fishingrodfix$bodyRodOwner();
        if (owner == null) {
            return;
        }
        float ownerPartialTicks = hookState.fishingrodfix$ownerPartialTicks();
        boolean samePassOnly = hookState.fishingrodfix$samePassOnly();
        // Only this drawing needs the owner (see HookState).
        hookState.fishingrodfix$setBodyRodOwner(null, 0f, false);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (disabledIn.get() == mc.world) {
            return;
        }
        try {
            // The hook's pose maps its local frame (world axes, origin at the hook) into the pass's
            // space; the inverse brings a drawn rod back into it.
            matrices.peek().getPositionMatrix().invertAffine(passInverse);
            passCollector = vertexConsumers;
            passInEntityLoop = inEntityLoop();
            passFrame = HandPass.frame();
            passEndsAtHook = passEnds;
            passHookX = state.x;
            passHookY = state.y;
            passHookZ = state.z;
            BodyRod rod = ((Owner) owner).fishingrodfix$bodyRod();
            if (rod == null) {
                return;
            }
            Vector3f local;
            if (passEnds > 0 && drawnInLastHookPass(rod)) {
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

    /** Called where a pass ends: the world's entity pass at {@code renderEntities}' return, a buffer source's at its full flush. */
    public static void onPassEnded() {
        passEnds++;
    }

    /**
     * Whether the drawing happening right now belongs to the world's entity loop: the loop's head
     * recorded the pass it opened, and nothing has ended a pass since. Read when a rod or a hook is
     * drawn, never later - Iris' shadow pass runs before the head records anything, so its drawings
     * see the previous frame's record and come out false even though the loop that follows them will
     * carry the same frame and pass-end count.
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
     * {@code render}'s line offset component {@code axis} (0 = x, 1 = y, 2 = z) for {@code state}:
     * the one {@link #onHookDrawn} worked out, or vanilla's {@code value}.
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
     * nothing usable was drawn (another arm, no rod held, or a pose or mount change with nothing but
     * an old reading behind it).
     */
    private static @Nullable Vector3f remembered(PlayerEntity owner, BodyRod rod, float partialTicks, FishingBobberEntityState state) {
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
            // way 0.54 against 0.07 (getStandingEyeHeight follows the pose and getHandPos adds
            // -0.1875 while sneaking) - and the kept reading only wins leaving SWIMMING (1.04,
            // PlayerEntityRenderer's dropped (0, -1, 0.3) translate, against 2.12, since the body
            // is still leaning that frame) and lying down (0.81 against 1.39). A mount change or a
            // lean that ramps up is small either way. What the leniency buys is the frame the
            // origin would otherwise jump to a different anchor and back, which is the visible
            // artifact. An older reading is not kept: it has no lag to preserve, only an unrelated
            // position. With sortBobbersLast in place this whole path is a fallback and not the
            // usual one: the rod is normally drawn before the hook and this frame's reading is
            // used. What is left for it are the passes the sort doesn't order and the frames where
            // the owner's body wasn't drawn at all - out of view, or into another buffer source -
            // which no draw order can fix.
            if (rod.offsetFrame < HandPass.frame() - 1) {
                return null;
            }
        }
        // The body turns about its position (LivingEntityRenderer.setupTransforms: 180 - yaw), except
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
                (float) (MathHelper.lerp(partialTicks, owner.lastRenderX, owner.getX()) - state.x + offset.x),
                (float) (MathHelper.lerp(partialTicks, owner.lastRenderY, owner.getY()) - state.y + offset.y),
                (float) (MathHelper.lerp(partialTicks, owner.lastRenderZ, owner.getZ()) - state.z + offset.z));
    }

    /**
     * Remembers the drawn tip, at world position {@code (x, y, z)}, relative to the body it was drawn
     * with.
     *
     * <p>The body position is the render state's, the lerped entity position, while the tip came
     * through a pose {@code EntityRenderDispatcher.render} had translated by that position
     * <em>plus</em> {@code EntityRenderer.getPositionOffset}. The remembered offset therefore carries
     * the reading frame's position offset, and {@link #remembered} adds it to a body position that
     * has none - which cancels exactly as long as the offset is the same in both frames, and that is
     * all of it in practice. A player's has two parts: the sneaking shift
     * ({@code PlayerEntityRenderer}: {@code -2/16} times the base scale), which moves only with the
     * pose the remembered path already checks (a scale attribute changing mid-crouch moves it too, by
     * at most {@code 2/16} of that change), and the experimental minecart's lerp, which needs the
     * game rule, a minecart to ride and this fallback path at once and is then a frame of that lerp
     * out. Re-deriving either here would be modelling the pose, which is the one thing this class
     * does not do.
     */
    private static void remember(BodyRod rod, double x, double y, double z) {
        rod.offset.set((float) (x - rod.bodyX), (float) (y - rod.bodyY), (float) (z - rod.bodyZ));
        rod.offsetYaw = rod.bodyYaw;
        rod.offsetArm = rod.arm;
        rod.offsetPose = rod.pose;
        rod.offsetRiding = rod.riding;
        rod.offsetFrame = rod.frame;
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
