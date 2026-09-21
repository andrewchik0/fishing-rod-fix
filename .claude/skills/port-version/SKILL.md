---
name: port-version
description: Port the Fishing Rod Fix mod to a target Minecraft version — backport to an older release or forward-port to a new one. Use when asked to port/backport the mod to a specific MC version (e.g. "port to 26.2", "backport v0.5 to 1.21.5"). Resolves Fabric coordinates, adapts the mixin to the target's real decompiled API, builds, smoke-tests that the mixin applies without crashing, then hands off for manual in-game verification before any commit.
---

# Port Fishing Rod Fix to a Minecraft version

> **Maintenance note (keep this skill version-agnostic).** This file is checked
> into the repo and therefore copied onto every version branch — that is how it
> is backed up and how it loads when you check out a branch to port. To avoid
> per-port edits and cross-branch drift, **do not bake current version numbers or
> "the reference is vX.Y" into the prose** — detect those at runtime (see
> "Canonical source"). Only edit this skill when you learn something genuinely
> new about the porting process, then propagate the same content to the branches
> you maintain (e.g. `git checkout <default_branch> -- .claude/skills/port-version`).
> The per-version table at the bottom (with its notes) and the "Known inflection
> points" overview are the main places version specifics live, and they are
> explicitly "observed, verify against source" — extend them, don't trust them.

## What this mod is (why correctness matters)

A purely client-side Fabric mod. A few small mixins hook the first-person
fishing-line origin, the line's visibility and the hand pass, and delegate to plain
helper classes that correct the origin so the line meets the rod tip, and hide the
line of a hook whose rod was put away (list the reference branch's `src/` for the
current set; the table's "Source layout" row shows one). The mixins are declared
`required: true` with `defaultRequire: 1`, so **if an injection fails to apply,
the game crashes at client startup** (the two hand-pass injectors are the
deliberate exception, see pitfall 8). This mod has thousands of players
on CurseForge/Modrinth — a bad port ships a crash. The prime directive of this
skill: **never finalize a port that hasn't been proven to load without crashing,
and never auto-commit/push correctness the user hasn't visually confirmed.**

There are two integration points the fix can use depending on the target's
renderer (see "Two injection strategies"): correcting the first-person result
inside a world-space hand-position method (modern, preferred), or cancelling and
redrawing `renderFishingLine` (legacy). Pick from the target's decompiled source, not from
the version number.

Scope: maintain the versions with the **largest player populations**, judged by
CurseForge/Modrinth download share — NOT merely the newest. By that measure the
heavy targets are the **1.20.x line** (the `1.20–1.20.4` jar has by far the most
downloads), the **1.21 / 1.21.1** and **1.21.4 / 1.21.5** lines, plus the
**current latest line** (26.x) for new adopters. Re-check the download numbers
when deciding scope; popularity, not recency, drives which versions we ship.

This explicitly means **older versions are in scope** — do not skip them. The
render API drifts **incrementally**; treat it as a gradient, not two clean
buckets. Always confirm against the target's decompiled source (Step 4). Known
inflection points:
- **≤1.20.x (e.g. 1.20.4):** `VertexConsumer` + `MatrixStack.Entry`, but a
  trailing **`.next()`**, `.normal(matrices.getNormalMatrix(), …)`,
  `.color(int,int,int,int)`; `@Inject` matched by method **name only**; AW uses
  `pausedTickDelta` and exposes `GameRenderer.getFov`.
- **1.21.0+ (and 26.x):** the world-space line origin comes from a hand-position
  method (`getHandPos` on 1.21.x, `getPlayerHandPos` on 26.x) with a single
  first-person `add(Vec3)` — so the **preferred** fix hooks that result with
  `@ModifyExpressionValue` (strategy A) on every 1.21.x target, not just 1.21.11.
  `renderFishingLine` still exists on 1.21.x (no `.next()`, `.normal(matrices, …)`,
  `.color(int)`; full descriptor `…FF)V` up to 1.21.10; plus `.lineWidth(...)` and
  `…FFF)V` on 1.21.11, where the line moved to a deferred render-command `submit`), but cancelling it drops other mods' line
  tweaks and misses the mods that redirect the hand-position branch. On 26.x there
  is no `renderFishingLine` to cancel at all.
- **Pre-1.20 (1.19/1.18):** older still (`BufferBuilder`-era); only relevant if a
  1.19-era target is explicitly requested.

## Canonical source of the fix logic — the default branch

The reference implementation is **whatever fix the repository's default branch
currently ships**. Do not assume a version number or a mixin shape — detect the
default branch AND read what it carries, every run.

Detect the default branch (queries the **live** remote; needs network):

```sh
git remote show origin | sed -n '/HEAD branch/s/.*: //p'
```

Caveats (both matter — verified the hard way):
- The locally cached `git symbolic-ref --short refs/remotes/origin/HEAD` can be
  **stale** (it may report an old default long after GitHub moved it). Prefer the
  live command; if offline, first refresh with `git remote set-head origin -a`,
  then read the symbolic-ref.
- **Sanity-check** the result: `<default_branch>` must be the newest `v`-version
  (the highest `mod_version` across branches). If detection yields an older line,
  stop and resolve it — basing a port off an older reference silently drops the
  latest logic.

Then read the reference (don't hardcode its version or filename):
- `git show <default_branch>:gradle.properties` → the current `mod_version`. That
  is the version you are shipping; bump the target's `mod_version` to match.
- The reference mixin is under
  `src/client/java/com/andrewchik/fishingrodfix/mixin/client/`, but its **class
  name tracks the target renderer** and differs by version family:
  `FishingBobberEntityRendererMixin` (1.21.x, renderer `FishingBobberEntityRenderer`)
  vs `FishingHookRendererMixin` (26.x, renderer `FishingHookRenderer`). List the
  directory on the default branch to find it; don't assume the filename:
  `git ls-tree --name-only <default_branch> -- src/client/java/com/andrewchik/fishingrodfix/mixin/client/`.
- The logic may also live in plain classes next to the mixin package
  (`src/client/java/com/andrewchik/fishingrodfix/`) and in extra mixins (e.g. an
  `@Accessor`/`@Invoker`; see the table's "Source layout" row). List them the same way
  (`git ls-tree -r --name-only <default_branch> -- src/`) and port all of them.
- Read the mixin(s) and helpers to learn the **current** fix logic and which
  injection strategy it uses. Every port re-expresses *that logic* in the target's
  API. Do not invent new logic — port the existing one.

## Two injection strategies — pick by the target's decompiled source

The fix always corrects the first-person line origin; *how* depends on what the
target renderer exposes. Decide from the decompiled source (Step 4), not the
version number. The corrective **math is identical** in both: find where the hand
pass draws the rod's line attachment point this frame, then place the world point
on the same pixel (Step 4.4). Only the integration point and the coordinate frame
differ.

**A. World-space hand-position correction (preferred where available).** Modern
renderers compute the line origin as a world-space point returned by a method —
`getHandPos` (1.21.x, including 1.21.11) or `getPlayerHandPos` (26.x) — and,
from 1.21.11, emit the line through a deferred render-command `submit` (1.21.0–1.21.10
still draw it directly in `render`). The method has a first-person
branch (`eyePos.add(viewVec)`, i.e. `Vec3.add(Vec3)`/`Vec3d.add(Vec3d)`) and a
third-person branch (`add(double, double, double)`). Hook **only the first-person
branch's result** with MixinExtras `@ModifyExpressionValue` on that single
`add(Vec3)` INVOKE, with `allow = 1`; the handler is `(Vec3 handPos,
@Local(argsOnly = true) Player owner)` and returns the corrected world position
(or `handPos` whenever the fix doesn't apply). Do **not** inject at `RETURN`: it
also sees the third-person branch, and popular mods (First Person Model, Real
Camera) redirect vanilla's camera-type check to force that branch, so a mod-side
"is first person" check disagrees with the branch vanilla actually took. Never
select returns by ordinal either — bytecode order differs from decompiled source
order. Verify with `javap -c` that the target method contains exactly one
`add(Vec3)`. The catenary is left untouched. The fix does not reuse vanilla's
eye-based vector at all (mods such as Animatium rewrite the eye height inside this
method); it rebuilds the point from the hand pass and the camera.

**B. `renderFishingLine` cancel + redraw (legacy).** Older renderers draw the
catenary inside a cancellable `renderFishingLine`. `@Inject` at `HEAD`
(cancellable), cancel, and redraw each segment with a corrected additive
translate — compute the correction once per frame (e.g. on the first segment), not
per segment. Cancelling drops other mods' line tweaks; where the target computes
the first-person point with a single expression (see the table's "Strategy B
hook" row), prefer `@ModifyExpressionValue` on it — and return the value in the
frame that expression is in (it may be relative to the feet, not the eye). Use
this only when the target has no world-space hand-position method.

## Invocation

`/port-version <mc_version>` — e.g. `/port-version 26.2`, `/port-version 1.21.5`.

If no version is given, ask which one. One run = one target version.

---

## Procedure

### Step 0 — Preconditions
- `git status` must be clean. If not, stop and ask the user to stash/commit.
- Confirm the Gradle JVM in use satisfies the **target's runtime Java** (Step 1;
  the table's "Loom / Java" row). If `runClient` would launch on an older JDK, the
  smoke test will fail for the wrong reason.
- **Regenerate the IntelliJ run config after switching version families.** Loom
  writes `.idea/runConfigurations/Minecraft_Client.xml` only when it is missing,
  and `.idea` is shared by all branches, so a config from another family carries
  the wrong JVM flags (see the table's "Run-config JVM flags" row) — a native crash
  or a JVM that won't start. Delete it and run `./gradlew ideaSyncTask`.
- **Make sure this skill is present on the branch you will work on.** It is a
  project skill loaded from the checked-out branch's `.claude/skills/`. Older
  version branches may not carry it. If you are about to port on a branch that
  lacks it, copy it from the default branch first (this also backs it up there):
  `git checkout <default_branch> -- .claude/skills/port-version`.

### Step 1 — Resolve Fabric coordinates for the target
Look these up; do not guess. Sources (use WebFetch):
- **Game / yarn / loader:** `https://meta.fabricmc.net/v2/versions/yarn/<mc_version>`
  (gives the latest `version` like `1.21.5+build.N`), and
  `https://meta.fabricmc.net/v2/versions/loader` (latest stable loader). Note:
  26.1+ is **unobfuscated** (no yarn/intermediary) — there is no `yarn_mappings`
  for those; the build uses Mojang names directly.
- **No Fabric API.** The mod uses nothing from Fabric API (its only mixin extra,
  MixinExtras, ships with Fabric Loader ≥ 0.15). Do not add it to `build.gradle`
  or `fabric.mod.json`; if the base branch still has `fabric_version` /
  `fabric_api_version` and the `fabric-api` dependency, remove them — keeping it off
  the classpath makes compile and `runClient` prove the mod runs without it.
- **Loom plugin version + recommended values (cross-check):**
  `https://fabricmc.net/develop` — the page renders the exact recommended
  `gradle.properties` block and the `fabric-loom` plugin version for a selected
  MC version. Newer MC ⇒ newer Loom (the table's "Loom / Java" row lists what was
  observed). Use the Loom version the develop page recommends.
- **Runtime Java:** confirm from the develop page / the version JSON's
  `javaVersion` (the table's "Loom / Java" row lists what was observed).

Record the resolved set: `minecraft_version`, `yarn_mappings` (if applicable),
`loader_version`, `loom_version`, `runtime_java`.

### Step 2 — Branch
Per repo convention, **each MC version lives on its own branch**.
- If a branch named exactly `<mc_version>` exists: check it out. (It already has
  correct build config; you are bringing its mixin up to the current reference
  logic.) Remember Step 0 — ensure this skill is on that branch.
- Else: create `<mc_version>` from the **nearest existing branch of the same API
  family** (modern vs legacy):
  - Modern target (1.21+/26.x): base off `<default_branch>` (the current
    reference source — detect it, see "Canonical source").
    `git checkout <default_branch> && git checkout -b <mc_version>`. (This also
    brings the current skill along.)
  - Legacy target (pre-1.21): base off the nearest existing legacy branch
    (`1.20`, `1.20.5`, `1.21` boundary, `1.19`, `1.19.3` on `origin`). Its mixin
    is the structural/API template; you still port the reference *logic* deltas
    onto it (see Step 4).

### Step 3 — Update build configuration
Edit on the target branch:
- **`gradle.properties`**: set `minecraft_version`, `yarn_mappings` (if the target
  uses yarn), `loader_version` to the resolved set; drop any Fabric API version.
  Bump `mod_version` to the reference's current value (Step "Canonical source").
- **`build.gradle`**: set the `fabric-loom` plugin version to `loom_version` and
  the Loom variant (remapping for yarn targets, the non-remap
  `net.fabricmc.fabric-loom` for unobfuscated ones). Set `it.options.release` /
  `sourceCompatibility` / `targetCompatibility` to the target's bytecode level (the
  table's "Loom / Java" row) — when backporting from a newer default branch that
  means *lowering* them, together with `compatibilityLevel` and `depends.java`.
  Ensure the **jar-naming block** is present (it makes `build` emit the correctly
  named artifact — see Step 6). It is set on the task that produces the
  distributed jar — `remapJar` on yarn targets, plain `jar` on unobfuscated targets
  (no remapping) — so `fabric.mod.json`'s `version` stays clean (= `mod_version`),
  and it reads the gradle.properties values so it auto-adjusts per port:
  ```groovy
  tasks.named('remapJar') {   // yarn targets; unobfuscated: see below
      archiveFileName = "${project.archives_base_name}-${project.minecraft_version}-v${project.mod_version}.jar"
  }
  ```
  (On an unobfuscated target, where `jar` is the distributed jar, set
  `archiveFileName` inside its existing `jar { }` block instead; on yarn targets
  the template's `jar { }` block builds only the dev jar.) For a range build
  (Step 8) keep the branch's range label instead of `${project.minecraft_version}`,
  e.g. `-1.21-1.21.1-v${project.mod_version}` (branch `1.21.1`); Step 6's file name
  then carries that label. If a base branch predates this block, add it. (Do NOT instead set
  `version = "${minecraft_version}-${mod_version}"` — that older approach leaks
  the MC version into `fabric.mod.json`.)
- **`src/client/resources/fishingrodfix.client.mixins.json`**: set
  `compatibilityLevel` to the target's bytecode level (e.g. `JAVA_17`).
- **`src/main/resources/fabric.mod.json`**: set `depends.minecraft` (see Step 8
  for single-build ranges) and `depends.java` to match; `depends.fabricloader` is
  the lowest loader the target supports that bundles MixinExtras (the table's
  "Loader floor" row) — never the dev loader version, which would lock out older
  launchers for no reason. No `fabric-api` dependency, no
  entrypoint (the mod has no initializer: delete `FishingRodFixClient.java` and the
  `entrypoints` block if the base branch still has them), `"environment": "client"`.

### Step 4 — Adapt the mixin to the target's REAL API (the hard part)
Mappings and signatures drift between versions. Do not text-replace blindly.
Combine two sources: the **reference logic** comes from the `<default_branch>`
reference mixin; the **API shape** (injection point, signature, vertex-emit style,
names) comes from the target's own decompiled source — and for a legacy target,
the existing legacy-family branch's mixin (`1.20`, `1.19`, …) is your structural
template for its older `.next()`-style API.
1. Run `./gradlew genSources` so the decompiled Minecraft sources for the target
   version are available. (Loom 1.14+ caches decompiled `.java` by hash under
   `~/.gradle/caches/fabric-loom/decompile/`; if no IDE-attached sources jar is
   produced, extract/grep that cache or `javap` the mapped jar under
   `~/.gradle/caches/fabric-loom/<ver>/.../*-unpicked.jar` for exact descriptors.)
2. Open the decompiled `FishingBobberEntityRenderer`/`FishingHookRenderer` for the
   target and decide the **injection strategy** (see "Two injection strategies"):
   does it expose a world-space hand-position method (`getHandPos`/
   `getPlayerHandPos`) — prefer strategy A — or only a cancellable
   `renderFishingLine` — strategy B? Then verify, against the reference:
   - **The exact descriptor of the method you inject.** This goes verbatim into
     the injector's `method = "..."` (`@ModifyExpressionValue` for A, `@Inject`
     for B), and for A the `add(Vec3)` INVOKE `target` too. Examples:
     `getHandPos(Lnet/minecraft/entity/player/PlayerEntity;FF)Lnet/minecraft/util/math/Vec3d;`
     (1.21.11, strategy A); `renderFishingLine(…FF)V` (1.21.4) →
     `(…FFF)V` (1.21.11, +`getMinimumLineWidth`, strategy B). **A wrong descriptor
     with `required:true` = startup crash** — copy it from `javap`/decompiled
     source, never from memory.
   - **For strategy B, the vertex-emit call shape**: `.color(int)` vs
     `.color(int,int,int,int)`, presence of `.lineWidth(...)`, `.normal(...)`, and
     (pre-1.21) a trailing `.next()`. Match the target's `VertexConsumer` API.
   - **Every API the fix reads**, each of which has renamed or moved across
     versions — find the target's equivalent in its decompiled source (the table
     at the bottom lists what was observed per version):
     - the camera: its entity (the spectator/Freecam guard), position, world FOV,
       and rotation (the quaternion vanilla's camera `move` rotates view offsets with
       — check its local axes: they are not the same in every version, see the table's
       "Camera rotation" row).
     - the hand pass's per-frame state: the hand FOV; the items it draws in each
       hand; for each hand the equip height (current + previous, plus the
       swap-animation scale), which hands are rendered, scoping, riptide spin,
       item-use state; the sway inputs (view rotation and `xBob`/`yBob`); the
       partial tick the hand pass lerps with; the call where the hand pass submits
       a hand and a method called once per frame before entity extraction (to tell
       whether a hand was drawn last frame).
     - the player's current hook (to key the remembered rod hand to it).
     - for line hiding: the hook entity class (a field-only mixin carries the
       seen-with-rod flag), the hook's owner, the owner's hand items, the local
       player's drawn items, the hook render state (if the target has one), and the
       line's render type (the table's "Line hiding site" row).
     - for the F1 exception (Step 4.4): the live HUD-hidden flag and the rest of
       the target's hand-pass gate — first person, camera entity, camera detached,
       panorama, sleeping, spectator, plus the camera's distance from where the
       camera code puts the player's eye (lerped position + the camera's smoothed
       eye height, private → `@Accessor`) — all sampled once per frame (the table's
       "HUD hidden (F1) + hand gate" row); the same samples gate the
       missing-hand-pass warning. On 26.x the frame counter (`extract` HEAD) runs
       after the camera update; on pre-26 targets the counter (`render` HEAD) runs
       before it, so take the camera-dependent samples after the camera update
       (e.g. `Camera.update` TAIL) and keep the latch at the counter.
     - the swing: progress, which hand, and the animation type (whack vs stab/none).
     - the view bob and hurt tilt methods both passes call, and the world pass's
       nausea/portal warp (its inputs and formula).
     - the rod sprite: the atlas holding item sprites, the sprite's source-image
       field (for the `@Accessor`), its frame size, its unique frames, and the
       missing-sprite id. Offset frames only where vanilla's own pixel test
       (`isTransparent`) does — for animated sprites; a static sprite shows tile 0
       and its frame list is a dummy `[1]`.
   - **The vanilla constants** the math mirrors (their values live in the code's
     comments): the first-person anchor NDC in the hand-position method
     (back-projected onto the rod, see 4.4), `BASE_HUD_FOV`, the item-sway factor,
     the first-person item pose `ITEM_POS`, `ITEM_HEIGHT_SCALE` and the swing
     constants (`ITEM_SWING_*`, `ITEM_PRESWING_ROT_Y`), the `handheld_rod.json`
     `firstperson_righthand`/`lefthand` transforms, the item pipelines'
     `ALPHA_CUTOUT`, the nausea warp's skew formula and axis, and (strategy B) the
     segment count: confirm each still holds in the target's source. The vanilla `fishing_rod_cast.png` alpha mask and
     `handheld_rod.json` have been identical in every version checked so far (see
     the table).
3. Re-express the reference logic with the corrected injection point/signature/API.
4. **Verify the MATH against the target's real render source — do not assume any
   formula or constant transfers.** The rendering internals churn between versions
   and a formula that was correct on the reference branch can be silently wrong on
   the target even though it compiles and the mixin applies (Step 7 only proves it
   loads, never that the line is in the right place). For every quantity the fix
   depends on, open the target's decompiled source and re-derive:
   - **Where the line origin actually comes from.** Strategy A: the world-space
     point returned by `getHandPos`/`getPlayerHandPos` (`eye + handOffset`), which
     the fix replaces. Strategy B: the `renderFishingLine` catenary built from the
     bobber→rod-tip delta.
   - **Which FOV / aspect each thing is projected with.** The visible rod is drawn
     in a *separate hand pass* at the **hand FOV** (70° narrowed underwater, in lava
     and while dying). The line is a world entity projected at the **world FOV**
     (options FOV × sprint/speed modifier × the same fluid/death factors). Read both
     from what vanilla projects with (the table's "Hand FOV" and "World FOV" rows)
     — don't rebuild them: rebuilding the world FOV from a multiplier field
     misses the fluid/death factors and zoom mods that hook the FOV method. A zoom
     mod that only restores the hand FOV at vanilla's own hand call makes your own
     call return the zoomed value — record the FOV the hand projection actually
     used if that matters.
   - **The model: put the world point where the hand pass draws the rod's line
     attachment point.** Derive everything from vanilla's own quantities —
     projection laws + mirrored vanilla constants survive renderer churn, tuned
     constants don't (old `YAW_SWAY_FACTOR`-style numbers were simply wrong):
     *Attachment point.* Back-project vanilla's anchor NDC onto the resting
     right-hand rod's model mid-plane. Its calibration is the 70° base hand FOV and
     an assumed 16:9 window (only the aspect matters: vanilla builds and projects its
     anchor at the same options FOV, so the FOV drops out). That fixed
     model point keeps vanilla's calibration exactly at rest and moves rigidly with
     the rod. A resource pack's redrawn rod shifts it by the measured tip delta.
     Then, per frame:
     1. *Rod pose.* Take the rod's hand from the items the hand pass draws (they
        lag the inventory during swap animations): vanilla's holding-arm rule's hand
        if a rod is drawn there, else the other drawn rod, else — while a rod is held
        but none is drawn yet (a quick F-swap or re-equip with a hook out) — the hand
        the rod was last drawn in, for the same hook. Carry the point through
        the hand pass's pose for an idle or
        swinging item: `applyItemArmTransform` with the equip dip
        (`inverseArmHeight × ITEM_HEIGHT_SCALE` — it plays after every cast and
        reel-in), `swingArm`/`applyItemArmAttackTransform` (only for the swinging
        hand with the whack animation), then the `handheld_rod` display transform.
        The left hand is the x-mirror on the mid-plane (the left-hand fix restores
        the right-hand rotation; the arm transforms mirror x). Read the per-frame
        inputs from the state the hand pass itself uses this frame, not from
        re-derived player fields.
     2. *Sway.* Vanilla's item sway `(viewXRot − xBob)·k` / `(viewYRot − yBob)·k`:
        the pose stack calls `rotate(X)` then `rotate(Y)`, so the point is rotated
        about Y first, then X.
     3. *Bob and tilt.* Apply the view bob and hurt tilt by calling vanilla's own
        methods (an `@Invoker`), which both passes apply before everything else:
        `drawn = bob · point`.
     4. *Same pixel.* The world pass projects `P_world · bob · warp · view` (warp =
        the nausea/portal skew, world pass only), the hand pass `P_hand · drawn`. A
        point `t` lands on the drawn pixel when its tangents are the drawn ones times
        `tan(worldFov/2)/tan(handFov/2)` (both passes share the window aspect); take
        the one at the drawn point's distance from the eye (keeps it close at low
        FOV and off the near plane at high FOV), then `view = (bob · warp)⁻¹ · t`
        (on a copy of the bob matrix). Exact whenever both passes get the same bob.
        Skip the warp when a mod removes it from the world pass: Clearviews 2.x (mod
        id `clearviews`), assuming its default "Disable Nausea" = on. The older
        Clearview 1.x (`clearview`) only removes the effect client-side and needs
        no special case.
     5. *Camera anchor.* Rotate `view` into the world by the camera's rotation (as
        vanilla's camera `move` does) and add the camera position — not the eye:
        the hand pass is camera-relative, and the camera follows the smoothed eye
        height on pose changes (and mods move it).
     Vanilla's own vector is not used (it swings about *world* axes and bakes in the
     eye). Fall back to vanilla's value when:
     - no rod can be on screen: no hand was drawn last frame (ask the renderer —
       mark the frame where the hand pass submits a hand, which is only reached
       past other mods' cancels, and count frames at a method that runs once per
       frame before the hooks are extracted — rather
       than re-deriving its conditions: mods decide them differently, e.g. Better F1
       keeps the hand with the HUD hidden, Player Animation Library cancels the
       pass, and the HUD flag may be extracted after the level; these two hooks are
       `require = 0`, so if another mod blocks them the line just stays vanilla), or
       a panorama is being captured (its first face still sees the previous frame's
       hand pass). The exception is a HUD hidden with F1: vanilla then skips the hand
       but still draws the line, a world object, so keep correcting it to where the
       rod would be. Sample the HUD flag and the rest of the target's hand gate
       once per frame, after the tick and the camera update (so for that frame's
       hand pass; see Step 4.2 for pre-26 ordering), including "camera at the
       player's eye" in 3D (some freecam mods keep the player as camera entity and
       don't detach). Latch whether a hand is drawn when only the HUD decides, from
       frames that prove it only: a drawn hand, or a missing one while the HUD and
       the gate were open (a mod hid it). A frame with the gate closed (F5, sleep,
       spectator, a freecam) proves nothing and restores the default (drawn) —
       otherwise the latch sticks until F1 is released (a freecam's first frames sit
       at the eye with the hand already hidden). Correct under F1 while the latch
       holds and the gate is open this frame. A hidden HUD then never
       moves the line: a mod that hid the hand before (Camerapture's photo frame, a
       freecam) keeps vanilla's value. The previous frame's hidden HUD counts too, so
       turning F1 off doesn't flash one vanilla frame;
     - scoping: nothing is drawn, but the ×0.1 world FOV would put the corrected
       origin inside the scope view, while vanilla's guess lands off-screen;
     - a drawn rod is posed in a way the model doesn't cover: its hand excluded or
       using an item, riptide, a stab swing (on the remembered-hand path no pose
       checks apply, or the line would jump to vanilla's other hand; that path needs
       a rod still held);
     - no rod is drawn and either none is held or none was drawn for the current
       hook, the camera isn't on the player, or the result is degenerate (FOV ratio
       not positive, not finite), or the world projection is orthographic
       (`m33 != 0`);
     - after an exception, until the next world or dimension (logged once).
     Separately, **hide the line** (not the bobber) of any hook whose rod has left
     its owner's hands, for every player and perspective (MC-310980, MC-211561):
     the server removes such a hook only on its next tick — after the ping, or never
     under a frozen tick rate, where hooks don't tick but players do — and vanilla
     keeps drawing its line from the off-hand side (`getHoldingArm`'s fallback),
     where no rod is, meanwhile. Where the origin
     hook actually placed this hook's line on the drawn first-person rod, keep it
     while the lowering rod is still drawn. Take that from the hook's result (reset
     at the start of each extraction), not from the camera setting (mods like First
     Person Model override it) or from the branch alone (a fallback there leaves
     vanilla's value, which starts on the off-hand side, where no rod is). Hide only
     hooks whose owner was seen holding a rod (a flag on the hook entity), so a hook
     cast by another item (a server's custom item, a mod's rod that isn't a
     `FishingRodItem`) keeps vanilla's line; for the local player a rod among the
     drawn items also counts, since the own hook arrives a round trip after the
     cast. Decide it when the hook's render state is extracted, carry it on the state, and skip only the line's geometry
     at submit (MixinExtras' `injector.v2.WrapWithCondition`; the old
     `injector.WrapWithCondition` is deprecated). Guard it like the origin: an
     exception leaves lines visible until the next world or dimension.
   - **Know when to stop.** What the model can't see — mods that re-pose the hand
     in the renderer without touching its state (viewmodel mods), mods that bob the
     two passes differently or remove
     the warp without a known mod id (hacked clients' anti-nausea), shaderpack hand
     sway, per-item rod models, portal-view mods that move the camera (Immersive
     Portals), load-order-dependent combinations (Clearviews + Iris shaders both
     wrapping the warp), mods that hide one arm by cancelling its per-hand call
     (Hide Hands, Exposure) or only its item (Player Animation Library during a
     first-person transition) while the frame is still marked, mods that hide the
     hand only after F1 was pressed (the latch still holds) — is documented rather
     than chased.
   Re-derive each formula from the target source, then let Step 9 manual testing
   confirm placement; if the line is off, fix the derivation, do not just re-tune a
   constant.

### Step 5 — Update the access widener (if the target needs one)
If the target needs an access widener at all (see the table's "Access widener" row),
`src/main/resources/fishingrodfix.accesswidener` must list exactly the fields the
mixin accesses via AW, with owners/descriptors that **exist in the target
mappings** (the table's "Access widener" row lists what each observed version
needed). Add an entry only for a field the mixin truly reads via AW, and verify
the field + owner exist in the decompiled target (a stale AW entry breaks the build or AW
load). **Remove entries the ported mixin no longer needs.** Prefer an
`@Accessor`/`@Invoker` mixin for a single private member.

### Step 6 — Build
`./gradlew build`. This compiles against the target mappings (and, on remapping
targets, makes Loom generate the mixin refmap; unobfuscated targets have none). Fix
compile errors (these usually mean a name from
Step 4 is still wrong). A green build proves names/signatures *compile*, but
**not** that the injection *applies at runtime* — that's Step 7.

**Deliverable artifact:** thanks to the Step 3 jar-naming block, `build` writes
the player-facing jar to `build/libs/` as
`fishingrodfix-<minecraft_version>-v<mod_version>.jar`. After the build, confirm
that exact file exists with the expected name. (`build/libs` also contains a
default-named `fishingrodfix-<mod_version>-sources.jar` — that's the sources jar,
not distributed; leave it. The user keeps the version-named publication jars in
`build/libs` — do **not** delete them, and do not `clean` them away.) If manual
verification (Step 9) leads to changing the code, **re-run `./gradlew build`** so
the jar matches the final committed code; the deliverable is the post-verification
build.

### Step 7 — Smoke test (automated gate: "does it load without crashing")
Entity renderers are constructed during the first resource reload at client
startup, so the target renderer loads early and the mixin applies then — a bad
descriptor crashes the client as it reaches the menu. Getting past every mixin's
application (item 2 below) therefore proves the mixins applied — **not** that
the helper classes link or work: the fix catches its own exceptions, so a
missing field or method in the helpers only shows up once a line is drawn, as one
logged "Fishing line correction failed" or "Fishing line visibility check failed"
error and a vanilla line. Step 9 covers it. The hand-pass injectors are `require = 0` (pitfall 8), so a stale descriptor
there doesn't crash either — run the smoke test with Mixin's injection counting on,
which fails any injector that finds fewer targets than its `expect` (default 1).

Procedure (the agent runs this adaptively; do not rely on a brittle kill script):
1. Launch `./gradlew runClient` in the background with
   `JAVA_TOOL_OPTIONS=-Dmixin.debug.countInjections=true` in its environment (the
   game JVM inherits it; the log then starts with `Picked up JAVA_TOOL_OPTIONS`),
   capturing stdout+stderr to a log file in the scratchpad, and record the PID you
   started. If the user's dev client is already running, don't share its `run/`
   directory: start the smoke client with a scratch working directory instead
   (e.g. the IDE's run-config command line — Loom's `launch.cfg` plus the classpath
   argfile — after `./gradlew clientClasses`, which also processes the mixin
   config; `compileClientJava` alone leaves a stale one) and read that directory's
   `logs/`.
2. Poll until either:
   - **Success** — `Preparing fishingrodfix.client.mixins.json (N)` in
     `run/logs/debug.log` (DEBUG level, not on the console) names as many mixins
     as the config lists, every one has a
     `Mixing <Name> from fishingrodfix.client.mixins.json` line there, and the
     client is still running a few seconds after the last one, with no failure
     marker. All of them apply before the menu (the hook entity class loads at
     bootstrap: the entity-type registry references its constructor). The
     console's menu lines (`Backend library: LWJGL`, `Sound engine started`) are
     NOT enough: they come before the first resource reload builds the entity
     renderers, so the hook renderer's mixin hasn't applied yet (observed on 26.3:
     `FishingHookRendererMixin` applies after the atlases are created).
   - **Failure markers** — anywhere: `Mixin apply` … `failed`,
     `InvalidInjectionException`, `Injection validation failed`,
     `MixinApplyError`, `MixinTransformerError`, `A mod crashed on startup`,
     `Failed to start`, or the process exits early. On the console only (a passing
     `debug.log` has normal `org.spongepowered.asm.mixin…` lines):
     `org.spongepowered.asm.mixin` errors and `Caused by:`. Ignore the dev
     account's HTTP 401 traces (user properties, Realms) — they are expected
     offline.
3. Terminate only the process tree you started (on Windows,
   `taskkill /F /T /PID <pid>` with the recorded PID). Never select the process
   by the project path: the user's own dev client matches it too.
4. Report: PASS (mixin applied, reached menu) or FAIL with the offending log
   excerpt. On FAIL, return to Step 4 — the descriptor or a mapping is wrong.

If the environment cannot run a GUI client, say so and downgrade to Step 6 only,
flagging that runtime application was **not** verified.

### Step 8 — Decide single-build vs per-version (range optimization)
Mojang ships a breaking version, then patch releases; players settle on the last
patch of a line. One jar can cover a **range** when the target API is identical
across it. Before splitting work into multiple branches:
- Compare every API the fix touches across the candidate versions: the
  injection targets, the `@Accessor`/`@Invoker` targets, and every render-state
  field and method the helpers read (Step 4.2) — the helpers fail silently (see
  Step 7), so a descriptor match alone proves nothing. Then fish once on each
  covered version. If everything is identical, ship
  **one** build and widen `depends.minecraft` to a range (this repo already did
  `>=1.21 <1.21.2` covering 1.21–1.21.1, `>=1.20 <=1.20.4` covering 1.20–1.20.4, etc.). If they differ, they need
  separate branches/builds.
- Prefer targeting the **latest patch** of the current line and covering earlier
  patches via the range when the API matches.
- State clearly which versions a given build actually covers — never silently
  imply broader coverage than tested.

### Step 9 — Manual verification handoff (REQUIRED before commit)
Stop and hand the user the walkthrough below. It is written so someone who hasn't
touched the project in months can follow it top to bottom, in about 15 minutes, and
exercise every branch of the mod. Present it in the user's language, keep the order,
and adapt it to the target: drop an item whose command or feature doesn't exist there
(say which), and drop the line-hiding and F1 stages if the port doesn't carry those
features. Nothing here is covered by the smoke test: it only proves the mixins apply.

**"Attached"** below means: the line starts exactly at the rod's tip (the top end
of the drawn rod), with no gap, and doesn't jump when anything changes.

**0. Setup** (dev client via `./gradlew runClient` or the IDE run config; after
switching version families, regenerate the IDE run config first, see CLAUDE.md).
Singleplayer, new world, cheats on, stand next to water deep enough to swim in.
Default video settings (FOV 70, view bobbing on), 16:9 window, main hand right. Run:
```
/gamemode survival
/effect give @s minecraft:resistance infinite 3 true
/effect give @s minecraft:saturation infinite 0 true
/give @s minecraft:fishing_rod
/give @s minecraft:spyglass
/give @s minecraft:oak_boat
/give @s minecraft:elytra
/give @s minecraft:firework_rocket 16
```
The items land in hotbar slots 1–5 in that order (rod in 1); slot 9 stays empty.

**1. Rest, swing, sway, bob** (the pose model)
- [ ] Cast. Line attached. Look straight down, straight up, and cast once facing
      each of north / east / south / west: attached every time.
- [ ] Reel in and cast again, watching the rod dip and rise: the line follows it.
      Left-click the air a few times with the hook out: it follows the swing.
- [ ] Turn the camera fast in circles: the line stays on the rod while it sways.
- [ ] Walk, then sprint, then `/effect give @s minecraft:speed 20 3`: attached
      through the bobbing and the FOV change.

**2. Screen and FOV**
- [ ] FOV 30, then 110 (Options → FOV), casting at each: attached.
- [ ] Drag the window to very wide, then very tall (or toggle fullscreen on an
      ultrawide / 4:3 screen): attached. Restore FOV 70.

**3. Hands**
- [ ] Press F to move the rod to the off hand: attached on the left. Press F twice
      quickly with the hook out: the line never jumps to the empty side.
- [ ] Options → Skin Customization → Main Hand: Left. Repeat the cast in both hands:
      attached. Set it back to Right.

**4. Body, water, vehicles**
- [ ] Crouch and stand up with the hook out: no jump.
- [ ] Jump in the water and cast while swimming and while floating (the hand FOV
      narrows underwater): attached, no jump when diving in or climbing out.
- [ ] Place the boat, get in, cast from it: attached, also while rowing.
- [ ] Equip the elytra, `/tp @s ~ ~40 ~`, glide, cast mid-air (rockets to keep
      flying): attached, no drop when the glide starts.

**5. Camera effects**
- [ ] `/damage @s 1` a few times with the hook out: the view shakes, the line stays
      on the rod.
- [ ] `/effect give @s minecraft:nausea 15`: the view warps, the line stays on the
      rod (no swinging around it). `/effect clear @s minecraft:nausea`.

**6. Scoping**
- [ ] Rod in the off hand (F), select the empty slot 9 and cast, then select the
      spyglass and hold right-click to scope: no line end left hanging inside the
      scope view.

**7. F5 and F1**
- [ ] F5 (third person): the line starts at the body model's hand, as in vanilla.
      Back to first person: attached at once.
- [ ] F1 (HUD and hand hidden): the line stays exactly where the rod tip was.
      Toggle F1 a few times: no jump either way.
- [ ] With F1 on: F5, then back to first person: the line is at the hidden rod tip
      at once. Turn F1 off.

**8. Put the rod away with the hook out** (line hiding)
- [ ] `/tick freeze`, cast, switch to the empty slot 9: the line follows the rod
      down and disappears; the bobber stays in the water. Switch back to the rod:
      the line returns to it.
- [ ] Same in F5: the line disappears at once when switching.
- [ ] Same with F1 on (first person): it follows the invisible lowering rod, then
      disappears.
- [ ] `/tick unfreeze` with the rod put away: the old bobber disappears.

**9. Resource packs**
- [ ] Enable Faithful 32x (or any pack that redraws the rod) in-world: the line is
      at that rod's tip. F3+T: still right. Disable it: back at the vanilla tip.
      No crash at any point.

**10. World changes**
- [ ] `/kill`, respawn, cast: attached (the effects are gone now; that's fine).
      Save and quit to the title screen, rejoin, cast: attached.

**11. Optional, if available**
- [ ] Iris with a shader pack: attached (Iris draws the hand itself).
- [ ] A second client on the same LAN world: the other player's line starts at
      their hand; when they scroll off the rod with the hook out, their line
      disappears at once and the bobber stays.
- [ ] First Person Model: the line starts at its body-model hand.
- [ ] F3 frame-time graph: no change when the rod is taken out and cast.

**12. Log**
- [ ] `run/logs/latest.log` contains none of: "Fishing line correction failed",
      "Fishing line visibility check failed", "Could not read the … sprite",
      "Hand pass tracking isn't active", "No first-person hand pass seen",
      "Sampling the hidden HUD failed". Any of them means part of the fix silently
      fell back to vanilla.

Only proceed to commit after the user confirms every stage. If the line is offset
somewhere, note which stage: it points at the branch of the math (Step 4.4) to
re-derive from the target source — do not just re-tune a constant.

### Step 10 — Git
After the user's OK:
- Commit **locally** on the target branch. Match the repo's terse message style,
  e.g. `Port to <mc_version>, v<mod_version>` or, for a backport,
  `Backport v<mod_version> to <mc_version>`. Include the conventional trailers from
  the project commit guidance.
- **Do not push** unless the user explicitly asks. Pushing publishes to a repo
  that feeds player-facing releases — treat it as outward-facing and gated on an
  explicit "push" / "release" from the user. (Note: pushing is also what actually
  *backs up* the work, including this skill — mention it, but still don't push
  unprompted.)

---

## Pitfalls reference (the things that break players' games)
1. **Injection descriptor / target mismatch** — #1 crash cause. The injected
   method's parameter list changes between versions (`renderFishingLine` `…FF)V` →
   `…FFF)V`; `getHandPos`/`getPlayerHandPos` differ in package/return mapping), and
   so does the `add(Vec3)` INVOKE target owner (`Vec3` vs yarn `Vec3d`).
   `required:true` turns a mismatch into a startup crash. Always copy descriptors
   from the target's decompiled source / `javap` (Step 4).
2. **VertexConsumer API drift** (strategy B) — `.lineWidth()` added in 1.21.11;
   `.next()` and `.normal(Matrix3f, …)` pre-1.21. `.color(int)` and
   `.color(int,int,int,int)` both exist 1.20.4–1.21.11 (vanilla's line uses the
   4-int form on 1.20.4, `color(int)` from 1.21). Match exactly.
3. **Name renames** — e.g. `getRotation()`/`rotation()` (with different local
   axes, see "Camera rotation"), `getFocusedEntity()`/`entity()`,
   `prevEquipProgress*`/`lastEquipProgress*`, `getColor`/`getColorArgb`/`getPixel`,
   `tiltViewWhenHurt`/`bobHurt`. Compile catches missing names, but only if you
   actually rebuild against target mappings — and not changed semantics.
4. **Stale access widener** — an AW entry whose field/owner doesn't exist in the
   target mappings breaks the build or AW load. Sync AW to what the ported mixin
   uses; remove entries it no longer needs (Step 5).
5. **Loom version mismatch** — too-old Loom can't remap/decompile a newer MC.
   Use the develop-page-recommended Loom for the target.
6. **Wrong runtime Java for `runClient`** — use the target's runtime Java (table
   "Loom / Java"); a smoke test on the wrong JDK fails for the wrong reason (Step 0).
7. **`compatibilityLevel` / `options.release`** — must match the target's bytecode
   level (table "Loom / Java"); backporting from a newer default branch means
   lowering them, or the jar won't load on the target's Java.
8. **Refmap / mixin not applying silently** — the config is `required: true`
   with `defaultRequire: 1`, so a missing target crashes at startup (loud, caught
   by the smoke test). Keep it that way. The two hand-pass injectors are the
   exception: `require = 0` by design, so another mod blocking them only leaves
   the line vanilla. A stale descriptor there is just as silent — the smoke test's
   `countInjections` run (Step 7) catches it, and in game a frame counter that
   never runs logs "Hand pass tracking isn't active", a hand mark never seen
   while fishing in first person logs "No first-person hand pass seen" (at most
   one of the two, once).
9. **Constants/derivation wrong even when it compiles** — the anchor NDC
   `(0.525, -0.1)`, `ITEM_POS`/`ITEM_HEIGHT_SCALE`/swing constants, the
   `handheld_rod` transform and (strategy B) the segment count 16 are vanilla
   internals; if they changed, or the math wasn't re-derived for this version, the
   line is off even though it compiles and loads. Step 9 manual testing catches
   this — a passing smoke test does NOT prove the line is in the right place. A
   resource-pack path that silently falls back to vanilla (wrong atlas id, frame
   handling) only shows up in Step 9's resource-pack stage.
10. **Over-claiming version coverage** — only set `depends.minecraft` to a range
    you actually validated the API matches across (Step 8).
11. **Hand-pass hook sites** — mark the frame at the call that submits one hand
    (table row "Frame counter / hand mark"), with its full descriptor:
    - pre-26 yarn has two `HeldItemRenderer.renderItem` overloads, so a name-only
      selector matches the wrong one or both;
    - don't mark in `GameRenderer.renderHand`: Iris (1.21.1) redirects its inner
      call to its own hand renderer, which still reaches the per-hand method;
    - count frames once per frame *before* the hooks are extracted/rendered (and
      sample the F1 inputs after the camera update — on pre-26 targets that is
      after the counter, see Step 4.2).
      `ranLastFrame` accepts a mark from the previous or the current frame, so
      the origin works whether it is computed before the hand pass (one frame
      late when the hand appears or disappears) or after it; a counter that runs
      after the hooks adds another frame of lag. Check where the target handles
      entities relative to the counter.

## Known per-version API deltas (OBSERVED — verify against the target source, not authoritative)
Extend this as you learn more; never trust it over the decompiled source.

| Concern | 1.20.4 (legacy) | 1.21.4 | 1.21.11 | 26.x |
|---|---|---|---|---|
| Injection point (current fix) | `renderFishingLine` HEAD, cancel | `renderFishingLine` HEAD, cancel | `getHandPos` RETURN (strategy A; move to MEV on `Vec3d.add(Vec3d)` when porting) | `getPlayerHandPos`: `@ModifyExpressionValue` on its only `Vec3.add(Vec3)` (first-person branch) |
| Hand FOV (rod) | `GameRenderer.getFov(camera, δ, false)` (private: AW or `@Invoker`; `(Lnet/minecraft/client/render/Camera;FZ)D`) | same (1.21–1.21.1 `…FZ)D`, 1.21.5+ `…FZ)F`) | same (`…FZ)F`) | `cameraRenderState.hudFov` (`Camera.calculateHudFov`) |
| Hand-pass state (equip height, sway, scoping, rendered hands) | `HeldItemRenderer` fields (equip progress private → `@Accessor`; verify names) + player fields | same | same | 26.1–26.2 `ItemInHandRenderer` fields (private → `@Accessor`); 26.3 `levelRenderState.playerRenderState.firstPersonHandsAndItems` + `.avatarRenderState` (public) |
| HUD hidden (F1) + hand gate | `options.hudHidden` (public, toggled between frames); `GameRenderer.renderHand(MatrixStack, Camera, F)` also needs not `renderingPanorama` (early return), first person, a camera entity not `isSleeping()`, game mode not spectator; detached: `camera.isThirdPerson()`; eye: private `Camera.cameraY`/`lastCameraY` (`@Accessor`, smoothed in `updateEyeHeight`), lerped `prevX/Y/Z`, `Camera.update`'s own tick-delta argument; panorama via public `isRenderingPanorama()` | `options.hudHidden`; `renderHand(Camera, F, Matrix4f)` (1.21–1.21.5; verify to 1.21.10), same gate (`!renderingPanorama`); detached: `camera.isThirdPerson()`; eye: as 1.20.4 (`lastX/Y/Z` from 1.21.5) | `options.hudHidden`; `renderHand(F, boolean sleeping, Matrix4f)`, same gate (`!isRenderingPanorama()`); detached: `camera.isThirdPerson()`; eye: as 1.21.5. Camera update after the `render` HEAD counter on all pre-26 targets | 26.1.2: `Options.hideGui` (copied to `OptionsRenderState.hideGui`, no `Hud`); 26.2–26.3: `Minecraft.gui.hud.isHidden()` (copied to `GuiRenderState.isHudHidden` after the level); `renderItemInHand` also needs `!isPanoramicMode`, `hasPlayer` (26.3), first person, `!entityRenderState.isSleeping`, `gameMode.getPlayerMode() != SPECTATOR`; detached: `Camera.isDetached()`; eye: private `Camera.eyeHeight`/`eyeHeightOld` (`@Accessor`); camera update before `extract` |
| Frame counter / hand mark (`HandPass`) | counter `GameRenderer.render(FJZ)V` HEAD; mark at the `renderFirstPersonItem` INVOKE inside `HeldItemRenderer.renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/network/ClientPlayerEntity;I)V` (the 5-arg overload) | 1.21–1.21.10: counter `render(Lnet/minecraft/client/render/RenderTickCounter;Z)V` HEAD; mark as 1.20.4 (verified 1.21, 1.21.1, 1.21.5) | counter as 1.21.x; mark in the 5-arg `renderItem(F, MatrixStack, OrderedRenderCommandQueue, ClientPlayerEntity, I)` | counter `GameRenderer.extract(Lnet/minecraft/client/DeltaTracker;Z)V` HEAD; mark at the per-hand call — 26.1.2: `renderArmWithItem` inside `ItemInHandRenderer.renderHandsWithItems`; 26.2: `submitArmWithItem` inside `ItemInHandRenderer.submitHandsWithItems` (both `(AbstractClientPlayer, FF, InteractionHand, F, ItemStack, F, PoseStack, SubmitNodeCollector, I)`); 26.3: `submitArmWithItem(PlayerRenderState, FirstPersonHandsAndItemsRenderState, FF, …)` inside `FirstPersonHandsAndItemsRenderer.submitHandsWithItems` |
| Owner's hook field (remembered rod hand) | yarn `PlayerEntity.fishHook` | same | same | `Player.fishing` |
| Line hiding site | hook entity `net.minecraft.entity.projectile.FishingBobberEntity` (owner `getPlayerOwner()`; line on the drawn rod: flagged by the origin hook's result (the strategy B `rotateX(F)` MEV here, the `getHandPos` MEV on 1.21–1.21.1), never from `getPerspective()`; line layer `RenderLayer.getLineStrip()`, also on 1.21.x until 1.21.11). No render state (drop `FishingHookRenderStateMixin`; reset the flag at `render` HEAD): decide inline in `render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V` (full descriptor: a bridge `render(Entity,…)` exists) and wrap its `renderFishingLine` calls; 1.21–1.21.1 the same | 1.21.5 (1.21.2+, verify): reset the flag at `updateRenderState` HEAD, decide at its TAIL, carry it on `FishingBobberEntityState`, wrap the `renderFishingLine` calls in `render(FishingBobberEntityState, …)` | reset the flag at `updateRenderState` HEAD, decide at its TAIL; the line is a deferred `submitCustom(…, RenderLayers.lines(), …)` in `render` whose lambda calls `renderFishingLine` without the state — wrap that `submitCustom`, filtered on the `lines()` layer | hook entity `FishingHook`; reset the flag at `extractRenderState` HEAD, decide at its TAIL; wrap the `RenderTypes.lines()` `submitCustomGeometry` in `submit` |
| Swing state | `getHandSwingProgress(t)` + `preferredHand`; no animation type (always whack) | same | + `item.getSwingAnimation().type()` | 26.2 `getAttackAnim(t)` + `swingingArm` + `stack.getSwingAnimation().type()`; 26.3 `avatarRenderState.currentSwing` (`SwingDescription`) + `.swingAnimation` (the hand pass's own state) |
| Rod-holding arm | none — inline `isOf(Items.FISHING_ROD)` | verify (1.21–1.21.1 have none; 1.21.5+ checks `instanceof FishingRodItem`) | `getArmHoldingRod` | `FishingHookRenderer.getHoldingArm` |
| Rod sprite | block atlas (`getSpriteAtlas(BLOCK_ATLAS_TEXTURE)`, by texture id) | block atlas | items atlas `getAtlasTexture(Atlases.ITEMS)` (atlas definition id, not the texture path) | items atlas `getAtlasOrThrow(AtlasIds.ITEMS)` |
| Sprite frames / pixels | no `isAnimated` (null-check the `animation` field via `@Accessor`); `getDistinctFrameCount()` (`IntStream`, `[1]` for a static sprite); image field `image`; `NativeImage.getColor` (ABGR, alpha still the top byte) | 1.21.4: as 1.20.4 (verify); 1.21.5: `getColorArgb` (ARGB; `getColor` is private) | `isAnimated()`; `getDistinctFrameCount()`; field `image`; `getColorArgb` | `isAnimated()`; `getUniqueFrames()` (`IntList.of(1)` for a static sprite); field `originalImage`; `getPixel` (ARGB) |
| Vanilla rod assets | `fishing_rod_cast.png` alpha mask + `handheld_rod.json` identical to 26.3 (verified 1.20.4, 1.21, 1.21.1, 1.21.5, 1.21.11, 26.1.2, 26.2, 26.3) | same | same | same |
| Injected descriptor | name only: `"renderFishingLine"` | `renderFishingLine(…FF)V` | `getHandPos(L…PlayerEntity;FF)L…Vec3d;` | `getPlayerHandPos(L…Player;FF)L…Vec3;` |
| Line emit path | direct vertex | direct vertex | deferred `submit` | deferred `submit` |
| Vertex emit (strategy B only) | `.color(0,0,0,255).normal(getNormalMatrix(),…).next()` | `.color(int).normal(matrices,…)` (1.21, 1.21.5 checked) | `.color(0xFF000000).normal(matrices,…).lineWidth(w)` | n/a (no `renderFishingLine`) |
| World FOV | `GameRenderer.getFov` (AW) | `baseFov*lerp(fovMultiplier)` (AW; → the private `getFov(camera, δ, true)` via AW or `@Invoker` when porting; it returns `double` on 1.21–1.21.1, `float` from 1.21.5) | `baseFov*lerp(fovMultiplier)` (AW; → `getFov(camera, δ, true)` when porting) | `1 / cameraRenderState.projectionMatrix.m11()` = tan(fov/2) of the projection `renderLevel` uses (bob and warp go onto a copy); in vanilla built from `Camera.getFov()` (`Camera.update` → `setupPerspective(…, fov, …)`), which also follows `calculateFov` hooks (Ok Zoomer, Zoomify) but misses mods that change the projection itself (Snapmatica's lens). Skip an orthographic projection (`m33 != 0`) |
| Tick delta | `renderTickCounter`/`pausedTickDelta` (AW) | `renderTickCounter.tickDelta`/`…BeforePause`+`isPaused()` | method `tickProgress` param | 26.1–26.2: method `partialTicks` param; 26.3: the hand pass's `cameraRenderState.cameraEntityPartialTicks` |
| Eye / camera / player pos | `getPos()` | `getPos()` | `getCameraPosVec(t)` / `getCameraPos()` / `getEntityPos()` | `camera.position()` only (the fix anchors at the camera) |
| Camera rotation (view → world) | `getRotation()` from `rotationYXZ(-yaw, pitch, 0)`: local +Z forward, +X **left** — rotate `(-x, y, -z)` of a view vector (x right, y up, z back); `moveBy(forward, up, left)` | `rotationYXZ(π − yaw, −pitch, 0)` from 1.21.0: rotate `(x, y, z)` directly (verify) | as 1.21.x | `rotation()`: rotate `(x, y, z)` directly, as `move(forwards, up, right)` builds `(right, up, -forwards)` |
| Camera entity (spectator guard) | `getFocusedEntity()` | same | same | `entity()` |
| Bob / hurt tilt (both passes) | `GameRenderer` private `bobView`/`tiltViewWhenHurt(MatrixStack, float)` (verify) | same (verify) | same (verify) | 26.3: `GameRenderer` private `bobView`/`bobHurt(CameraRenderState, PoseStack)` → `@Invoker` (26.1–26.2: same) |
| Nausea / portal warp (world pass only) | inline in `renderWorld`: intensity `lerp(prevNauseaIntensity, nauseaIntensity)`; angle `(ticks + tickDelta)·(NAUSEA ? 7 : 20)°` (private `GameRenderer.ticks`) | 1.21–1.21.1 as 1.20.4; 1.21.5: private `nauseaEffectTime`/`nauseaEffectSpeed`, `getEffectFadeFactor`, `lastNauseaIntensity` | as 1.21.5 | 26.1–26.2: private `spinningEffectTime`/`spinningEffectSpeed`, angle `(time + worldPartialTicks·speed)°`, intensity `max(lerp(portal), getEffectBlendFactor(NAUSEA))`; 26.3: `playerRenderState.spinningEffectAngle`/`portalEffectIntensity`/`nauseaEffectIntensity`. All: `rotate(a, (0, √2/2, √2/2)) · scale(1/skew, 1, 1) · rotate(−a, …)`, `skew = (5/(i²+5) − 0.04i)²` |
| Strategy B hook (first-person point expression) | `render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V` (full descriptor: a synthetic bridge `render(Entity,…)` also exists): the single `Vec3d.rotateX(F)` on the first-person path; its value is relative to the lerped **feet** (`getStandingEyeHeight()` is added after) | n/a (strategy A) | n/a | n/a |
| Source layout (reference fix) | — | — | — | 26.3: mixins `FishingHookRendererMixin` (origin MEV + visibility inject/wrap), `GameRendererInvoker`, `GameRendererMixin` (frame count + F1/gate sampling), `FirstPersonHandsAndItemsRendererMixin` (hand drawn), `FishingHookRenderStateMixin` (line-hidden flag), `FishingHookMixin` (seen-with-rod flag), `SpriteContentsAccessor`, `CameraAccessor` (eye height); logic `FishingLineOrigin`, `FirstPersonRod`, `FishingLineVisibility`, `HandPass`, `FishingRodFix` (logger, `isRod`, `holdsRod`) |
| Run-config JVM flags (Loom-generated) | none special | none special | none special | 26.1–26.2: `--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED`; 26.3 (Loom 1.17.21): `-XX:StackShadowPages=32 --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED`. `--sun-misc-unsafe-memory-access` (from Mojang's 26.1–26.2 JSON; on 26.3 Loom adds it, and Mojang's JSON has `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED` instead) stops JDK 21 from starting; without `StackShadowPages` 26.3 crashes natively on in-world reloads |
| Loader floor (`depends.fabricloader`) | for a port of the current reference: `>=0.15.0` (MixinExtras bundled; pre-rework branches ship `>=0.12.0`) | same | same | `>=0.19.0` as shipped (verified to bundle what the mod uses: sponge-mixin 0.17.1 with `JAVA_25`, MixinExtras 0.5.3 with v2 `WrapWithCondition`, MEV and `@Local`; lower 26.x loaders unchecked); the floor also sets the Mixin compatibility level the loader applies to the mod (`FabricMixinVersions`: floor `>=0.19.0` → 0.17.1, `>=0.15.0` → 0.10.0), so re-run the smoke test after changing it |
| Sway source | item-renderer `*0.1°` | item-renderer `*0.1°` | `HeldItemRenderer` `(getPitch-renderPitch)*0.1°`/`(getYaw-renderYaw)*0.1°` | `ItemInHandRenderer.renderHandsWithItems` (26.1.2) / `submitHandsWithItems` (26.2) → `FirstPersonHandsAndItemsRenderer.submitHandsWithItems` fed by `FirstPersonHandsAndItems.extractRenderState` (26.3); both `(getViewXRot-xBob)*0.1°`/`(getViewYRot-yBob)*0.1°` |
| Access widener | `renderTickCounter`+`pausedTickDelta`+`getFov` (a port of the current reference adds a `Camera` `cameraY`/`lastCameraY` `@Accessor`) | `tickDelta`/`tickDeltaBeforePause` | `GameRenderer.fovMultiplier`/`lastFovMultiplier` | none (26.3: `@Accessor` for `SpriteContents.originalImage` and `Camera.eyeHeight`/`eyeHeightOld`, `@Invoker` for `GameRenderer.bobHurt`/`bobView`; 26.1–26.2 need accessors for `ItemInHandRenderer`'s equip fields) |
| Loom / Java | 1.6-SNAPSHOT / 17 | 1.9-SNAPSHOT / 17 | 1.14-SNAPSHOT / 17 (run JDK 21) | 1.17-SNAPSHOT / release 25 (run JDK 25); Gradle 9.5.1 (26.1–26.2) → 9.7.1 (26.3) |

**Maintained 1.21 branches vs the 1.21.4 column:** the table's 1.21.4 column predates the maintained `1.21.1` and `1.21.5` branches (both already use strategy A). Observed there: the swap-animation scale exists only from 1.21.11 (treat it as 1 earlier); the equip fields are `prevEquipProgress*` up to 1.21.1 and `lastEquipProgress*` from 1.21.5; `getArmHoldingRod` already exists in 1.21.5; `NativeImage.getColorArgb` from 1.21.5.

**1.20.4 coordinates (most-played target, from `origin/1.20`):**
`minecraft_version=1.20.4`, `yarn_mappings=1.20.4+build.3`,
`loader_version=0.15.11`, loom `1.6-SNAPSHOT` (the branch still carries
`fabric_version=0.97.0+1.20.4`; drop it when porting — see Step 1).
The `1.20–1.20.4` jar covers that whole range via `depends.minecraft`.
