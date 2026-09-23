---
name: port-version
description: Port the Fishing Rod Fix mod to a target Minecraft version — backport to an older release or forward-port to a new one. Use when asked to port/backport the mod to a specific MC version (e.g. "port to 26.2", "backport v0.5 to 1.21.5"). First brings the project skills (itself included) onto the target branch from the default branch, then resolves Fabric coordinates, adapts the mixin to the target's real decompiled API, builds, reviews the port in rounds with /reviewer (compatibility once, in parallel) until no real bug is left, and only then launches the game: smoke-tests that the mixins apply without crashing and hands off for manual in-game verification before any commit.
---

# Port Fishing Rod Fix to a Minecraft version

> **Maintenance note (keep this skill version-agnostic).** This file is checked
> into the repo and therefore copied onto every version branch — that is how it
> is backed up and how it loads when you check out a branch to port. To avoid
> per-port edits and cross-branch drift, **do not bake current version numbers or
> "the reference is vX.Y" into the prose** — detect those at runtime (see
> "Canonical source"). Only edit this skill when you learn something genuinely
> new about the porting process, then propagate the same content to the branches
> you maintain — every port does that first for all project skills (Step 0:
> `git restore --source=<default_branch> --staged --worktree -- .claude/skills`).
> The per-version table at the bottom (with its notes) and the "Known inflection
> points" overview are the main places version specifics live, and they are
> explicitly "observed, verify against source" — extend them, don't trust them.

## What this mod is (why correctness matters)

A purely client-side Fabric mod. A few small mixins hook the fishing-line origin
(the first-person rod and the rod a player's body holds), the line's visibility and
the hand pass, and delegate to plain helper classes that correct the origin so the
line meets the rod tip, and hide the line of a hook whose rod was put away (list the reference branch's `src/` for the
current set; the table's "Source layout" row shows one). The mixins are declared
`required: true` with `defaultRequire: 1`, so **if an injection fails to apply,
the game crashes at client startup** (the optional injectors — the two
hand-pass ones and the body-held rod's — are the deliberate exception, see
pitfall 8). This mod has thousands of players
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
(or `handPos` whenever the fix doesn't apply, and while vanilla draws the player's
own body instead — asleep, or a mod-detached camera — whose rod the body-held path
takes; where the fix doesn't apply with the camera on the player, the body-held
path may only use a body drawn earlier in the same pass, see Step 4.4). The third-person branch is not hooked: a body-held rod's line is placed
where the hook is submitted (see Step 4.4). Do **not** inject at `RETURN`: popular
mods (First Person Model, Real Camera) redirect vanilla's camera-type check to send
the local player down the third-person branch while they draw its body, so a
mod-side "is first person" check disagrees with the branch vanilla actually took.
Never select returns by ordinal either — bytecode order differs from decompiled
source order (26.3 compiles the third-person branch first). Verify with `javap -c`
that the target method contains exactly one `add(Vec3)`. The catenary is left untouched. The fix does not reuse vanilla's
eye-based vector at all (mods such as Animatium rewrite the eye height inside this
method); it rebuilds the point from the hand pass and the camera. Work it out once
per frame and give later extractions in the same frame that result (Iris' shadow pass
extracts the local player's hook again from the same frame's state).

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

### Step 0 — First: bring the target branch's skills up to date
Project skills (`.claude/skills/`: this one, `/reviewer` and any others) are checked
into the repo and load from whatever branch is checked out, so an older version
branch carries older copies of them — possibly an older copy of this very
procedure. Before anything else, a port puts the current skills on the target
branch:
1. `git status` must be clean; if not, stop and ask the user to stash or commit.
   Skills travel only as the default branch has them committed: a skill the user is
   still writing (untracked or uncommitted there) doesn't come along — say so.
2. Detect `<default_branch>` (see "Canonical source").
3. Switch to the target branch: check it out, or create it from the base Step 2
   names. Note the commit you are on now (`git rev-parse HEAD`): it is the port's
   base, the start of Step 6b's review scope.
4. Mirror the skills from the default branch:
   `git restore --source=<default_branch> --staged --worktree -- .claude/skills`.
   It brings every skill file over and, in its default no-overlay mode, removes the
   ones the default branch no longer has. Update the "Roles" list in the branch's
   `CLAUDE.md` to match the synced skills.
5. If `.claude/skills/port-version/SKILL.md` changed in step 4, the procedure you
   are following is outdated: re-read that file now and continue with the new
   version from here.
6. Port the synced skills' version-specific parts to the target, as part of the
   port. Ideally a skill has none: its procedure detects versions at runtime and
   keeps version facts only in its "observed" tables. What remains — code that runs
   against Minecraft (a test harness, scripts naming classes or descriptors), a
   table with no entry for the target yet — is adapted like the mod itself and
   verified against the target's decompiled source. Tables cover every version:
   extend them, never replace another version's entries. List what you adapted in
   the final report.
The skill sync is committed on its own in Step 10 and stays out of the port's
review scope, except for the parts adapted in step 6.

### Step 0b — Preconditions
- Confirm the Gradle JVM in use satisfies the **target's runtime Java** (Step 1;
  the table's "Loom / Java" row). If `runClient` would launch on an older JDK, the
  smoke test will fail for the wrong reason.
- **Regenerate the IntelliJ run config after switching version families.** Loom
  writes `.idea/runConfigurations/Minecraft_Client.xml` only when it is missing,
  and `.idea` is shared by all branches, so a config from another family carries
  the wrong JVM flags (see the table's "Run-config JVM flags" row) — a native crash
  or a JVM that won't start. Delete it and run `./gradlew ideaSyncTask`.

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
Per repo convention, **each MC version lives on its own branch**. Step 0 already
switched to it; these are the rules it follows for which branch that is and what a
new one is created from.
- If a branch named exactly `<mc_version>` exists: check it out. (It already has
  correct build config; you are bringing its mixin up to the current reference
  logic.)
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
- **`gradle/gradle-daemon-jvm.properties`**: `toolchainVersion=<runtime Java>` (the
  table's "Loom / Java" row: 25 on 26.x, 21 on older targets). Gradle then runs its
  daemon on a matching installed JDK (IntelliJ's `~/.jdks` included) whatever
  `JAVA_HOME` or `PATH` point to, so the branch builds from any shell and IntelliJ
  needs no per-branch Gradle JVM switch (its "Daemon JVM criteria" setting). Write
  the file by hand: Gradle 9's `updateDaemonJvm` fails without toolchain download
  repositories, and none are wanted (a missing JDK should fail, not be fetched).
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
     - for the body-held rod (third person): the call where the held-item layer
       submits a held item (its pose, the render state, the arm, the stack, the
       collector), the player's entity id on its render state (to find the entity),
       the owner's hook field on the client, the hook renderer's submit (its pose and
       the collector), `submit`'s offset locals (their float ordinals; the line reads
       them, not the state, once they are stored), the line's 0.25 lift, the owner's body
       yaw as drawn (`solveBodyRot`, private → mirror it) and lerped position.
       Targets that draw directly (no deferred submit, pre-1.21.11) read the rod at
       the same layer call during `render`, so the owner must be rendered before the
       hook's line is drawn; check the entity order and whether the line is emitted
       immediately.
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
     `firstperson_righthand`/`lefthand` and `thirdperson_righthand`/`lefthand`
     transforms, the line's 0.25 lift in `stringVertex`, `solveBodyRot`'s rider limits
     (85°, 50°, 0.2), the item pipelines'
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
     **The body-held rod** (every line not on the first-person rod: the third-person
     branch for every player, and the first-person branch while vanilla draws the
     local player's own body: camera on the player and detached, or the player
     asleep — the target's own entity-extraction rule). Vanilla's third-person value
     is a fixed offset from the eye turned by the entity's body yaw; the drawn rod
     follows the model's pose (crouch lean MC-4490, riding MC-176514 incl. the
     mount-clamped body yaw, boats MC-198777, the arm swing MC-247425,
     swimming/crawling MC-270173, sleeping MC-270174, walking, gliding, riptide,
     death). Don't model that pose: read the rod where it is drawn. At the held-item
     layer's call that submits the item, for a rod in the holding arm of a player who
     is fishing, transform the attachment point (the measured sprite tip on the
     model mid-plane through the `thirdperson_righthand` display transform — not the
     first-person anchor, which sits by the tip only at first-person scale and angle)
     by the submitted pose and store it on the player, tagged with the pass
     (collector + frame; valid while a collector serves one pass per frame). That
     call runs for every armed entity: gate it on "a body-held hook was extracted
     this frame", then the type check, in a method small enough to inline, and do
     the rest out of line. Inject there after other mods' same-call `@Inject`s (a
     higher priority; Player Animation Library moves the item there). At the hook's
     submit HEAD, when the owner's rod was drawn earlier in the same pass, take that
     point through the inverse of the hook's pose (a pass that turns its root cancels
     out) as the line offset, minus the line's lift, and write it into `submit`'s
     offset locals (`@ModifyVariable` at their STORE) — no allocation, and the render
     state's origin keeps vanilla's value. Otherwise use the tip where the rod was
     last drawn, relative to the body's position and turned with its drawn yaw,
     while the owner still holds a rod in that arm and is in the same pose and
     riding state as then; else vanilla's value. Remember that offset whenever a hook
     of the pass gives the world position, before or after the rod (a mod may draw
     the body after the hook). A line on the first-person branch that the origin hook
     left at vanilla's value (any of its fallbacks) while the camera is on the owner
     (vanilla, the camera attached and the player awake, doesn't draw the body)
     takes only the same-pass rod, never the remembered one: set a flag from the
     first-person hook's fallback when the camera entity is the owner (reset at the
     extraction's start, read at its end) and carry it on the hook's render state. Without it the frame after leaving F5
     (the F5 spot is still remembered) and, with Iris entity shadows, every such
     frame (Iris' shadow-pass entity extraction doesn't skip the camera entity, so it
     draws the local body with its rod, then the hook, and remembers that spot) start
     the visible line at a rod that isn't on screen. The same-pass read still carries
     a mod's first-person body drawn earlier in the level pass (Player Animation
     Library's model mode, which fakes `isDetached` in the entity extraction and
     cancels the hand pass) and the shadow's own line. Don't re-derive the flag from
     the camera type: mods that send the local player down the third-person branch
     (First Person Model, Real Camera, whose classic mode draws the body after the
     hooks) need the remembered spot. So does a camera on another entity: vanilla then
     never draws the local player, so a drawn body is a mod's and may come after the
     hooks (Freecam keeps the camera type first person, makes its own entity the
     camera and, with Show Player, its default, adds the local player at the entity
     extraction's RETURN). Verified on 26.3 with a scratch test mod against the
     pose the rod is drawn with: exact to float precision in every pose listed, and
     with a same-call item shift. Hold nothing past its use: the owner rides on the
     hook's render state only until its submission (other mods keep render states
     past their frame — Iris' shadow pass keeps its last frame's, also after a
     disconnect — and the owner would keep the old world alive), the pending offset
     until the last offset local is written, the last hook's pass (collector) until
     the next frame starts.
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
**not** that the injection *applies at runtime* — that's Step 7, which runs only
after the review rounds (Step 6b) have accepted the code.

**Deliverable artifact:** thanks to the Step 3 jar-naming block, `build` writes
the player-facing jar to `build/libs/` as
`fishingrodfix-<minecraft_version>-v<mod_version>.jar`. After the build, confirm
that exact file exists with the expected name. (`build/libs` also contains a
default-named `fishingrodfix-<mod_version>-sources.jar` — that's the sources jar,
not distributed; leave it. The user keeps the version-named publication jars in
`build/libs` — do **not** delete them, and do not `clean` them away.) If manual
verification (Step 9) leads to changing the code, run one correctness/performance
round on the change (Step 6b.2), then **re-run `./gradlew build`** so the jar
matches the final committed code; the deliverable is the post-verification build.

### Step 6b — Review rounds (no game launch yet)
**Don't launch Minecraft until these rounds have accepted the code**: no smoke
test, no `runClient`, no in-game test stand. Every round can still change the code,
each launch costs minutes and competes with the user's dev client, and a launch
proves nothing about code that is about to change. The first launch is Step 7, on
the final code.

Scope: the port's changes, i.e. the diff against the base noted in Step 0,
uncommitted work included, without the skill sync (`git diff <base> -- .
':!.claude/skills'` plus untracked files outside it), but with the skill parts you
adapted to this version in Step 0.6. Pass it to `/reviewer` explicitly: a new
branch has no remote counterpart for the reviewer's default scope.

1. **Compatibility review: once, in parallel.** With the first green build
   (Step 6), start `/reviewer <scope> --only compatibility` in the background and
   don't wait for it. It is by far the slowest reviewer (it reads other mods'
   sources), and its answer depends on the port's touch points (injection targets
   and points, the vanilla methods the fix calls), which a round's fixes rarely
   move. So it runs once per port, never per round, alongside the rounds below.
   Merge its report into the round in progress when it arrives: a confirmed REAL
   BUG joins that round's fix list, a limitation goes into `CLAUDE.md`'s Known
   limitations. If a later fix adds or moves a touch point (a new injector target
   or injection point, a new call into vanilla code other mods hook), don't rerun
   it: list those touch points in the final report as not compatibility-checked.
2. **Rounds.** Run `/reviewer <scope> --only correctness,performance`. Verify
   every finding (if the reviewers' own reports arrive but no consolidated one,
   verify them yourself as the reviewer skill's Step 2 describes). Then:
   - fix every confirmed REAL BUG (a crash path, math that doesn't match the
     target's vanilla, unsound state, a performance budget violation), together
     with whatever the fix touches;
   - keep the WRONG FACTs, IMPROVEMENTs and NITs on a running list without fixing
     them yet, and pass that list to the next round as "known, deferred — don't
     re-report";
   - rebuild (`./gradlew clientClasses`; no game launch) and start the next round
     right away, with fresh reviewers on the whole scope (a fix can break its
     neighbours).
   Stop and ask the user (in their language) instead of fixing when a fix would
   change intended behaviour, trade one documented limitation for another, add a
   dependency or configuration, or touch something the user decided before; keep
   going with the other findings meanwhile. If two rounds disagree on the same
   point (fix A brings back B), stop and put both sides to the user.
3. **Accept.** The first round whose verified findings contain no REAL BUG (and
   whose performance verdict isn't FAIL) ends the loop. If the compatibility report
   hasn't arrived yet, wait for it now; if it has a REAL BUG, fix it and run one
   more round. Then fix the deferred list (docs, comments, improvements, nits)
   without another round: a change that only touches docs or comments, or a small
   local cleanup, doesn't need one. Rebuild, and take the result as final: only now
   go on to Step 7.

### Step 7 — Smoke test (automated gate: "does it load without crashing")
Entity renderers are constructed during the first resource reload at client
startup, so the target renderer loads early and the mixin applies then — a bad
descriptor crashes the client as it reaches the menu. Getting past every mixin's
application (item 2 below) therefore proves the mixins applied — **not** that
the helper classes link or work: the fix catches its own exceptions, so a
missing field or method in the helpers only shows up once a line is drawn, as one
logged "Fishing line correction failed" or "Fishing line visibility check failed"
error and a vanilla line. Step 9 covers it. The optional injectors (the hand-pass ones and the body-held rod's) are
`require = 0` (pitfall 8), so a stale descriptor there doesn't crash either — run the smoke test with Mixin's injection counting on,
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
   excerpt. On FAIL, return to Step 4 — the descriptor or a mapping is wrong. Fix
   it and rebuild; if the fix changes more than that descriptor or target, run one
   correctness/performance round (Step 6b.2) before launching again.

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
  **one** build and widen `depends.minecraft` to a range with a **closed** upper
  bound — never `~X.Y` or `<Y`, which admit the next line's snapshots (pitfall
  10); a single version gets `>=X <=X` (this repo already did
  `>=1.20 <=1.20.4` covering 1.20–1.20.4, etc.). If they differ, they need
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

**7. F5 (the body's rod)** — "attached" here means at the tip of the rod in the
body's hand. Each check in F5 (back), and a few also in front view (F5 twice):
- [ ] Standing, walking and sprinting: attached. Left-click the air a few times:
      the line follows the swinging arm.
- [ ] Crouch and stand up: attached, no jump.
- [ ] Rod in the off hand (F): attached on the left; swing it too.
- [ ] In the boat, and on a pig or horse (`/summon pig`, `/ride @s mount @n[type=pig]`,
      turn the head far to the side): attached.
- [ ] Swim in the water, and crawl (stand under a trapdoor at head height and close
      it): attached.
- [ ] Glide with the elytra: attached.
- [ ] Sleep in a bed with the hook out (`/time set night`), in F5 and in first
      person (the lying body's rod is visible): attached in both. With the rod in the
      off hand too (MC-188326: the line stays on it).
- [ ] Turn away so your body leaves the view (F5 front, look far to a side) and back:
      no jump when the body reappears.
- [ ] Back to first person: attached at once to the first-person rod.

**8. F1**
- [ ] F1 (HUD and hand hidden): the line stays exactly where the rod tip was.
      Toggle F1 a few times: no jump either way.
- [ ] With F1 on: F5, then back to first person: the line is at the hidden rod tip
      at once. Turn F1 off.

**9. Put the rod away with the hook out** (line hiding)
- [ ] `/tick freeze`, cast, switch to the empty slot 9: the line follows the rod
      down and disappears; the bobber stays in the water. Switch back to the rod:
      the line returns to it.
- [ ] Same in F5: the line disappears at once when switching.
- [ ] Same with F1 on (first person): it follows the invisible lowering rod, then
      disappears.
- [ ] `/tick unfreeze` with the rod put away: the old bobber disappears.

**10. Resource packs**
- [ ] Enable Faithful 32x (or any pack that redraws the rod) in-world: the line is
      at that rod's tip, in first person and in F5. F3+T: still right. Disable it:
      back at the vanilla tip.
      No crash at any point.

**11. World changes**
- [ ] `/kill`, respawn, cast: attached (the effects are gone now; that's fine).
      Save and quit to the title screen, rejoin, cast: attached.

**12. Optional, if available**
- [ ] Iris with a shader pack: attached (Iris draws the hand itself).
- [ ] A second client on the same LAN world: the other player's line starts at
      their rod's tip while they stand, crouch, swing, swim and sleep; when they
      scroll off the rod with the hook out, their line disappears at once and the
      bobber stays.
- [ ] First Person Model (arms shown on the body) and Real Camera: the line starts
      at the tip of the body's rod.
- [ ] Emotecraft or Better Combat (Player Animation Library): during an emote or an
      attack animation the line stays on the rod.
- [ ] F3 frame-time graph: no change when the rod is taken out and cast.

**13. Log**
- [ ] `run/logs/latest.log` contains none of: "Fishing line correction failed",
      "Fishing line visibility check failed", "Could not read the … sprite",
      "Hand pass tracking isn't active", "No first-person hand pass seen",
      "Sampling the hidden HUD failed", "Third-person fishing line correction
      failed". Any of them means part of the fix silently
      fell back to vanilla.

Only proceed to commit after the user confirms every stage. If the line is offset
somewhere, note which stage: it points at the branch of the math (Step 4.4) to
re-derive from the target source — do not just re-tune a constant.

### Step 10 — Git
After the user's OK:
- Commit the skill sync from Step 0 first, on its own:
  `Sync project skills from <default_branch>` (with "adapted to <mc_version>" when
  Step 0.6 changed anything), so the port's own commit holds only the port.
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
   "Loom / Java"); a smoke test on the wrong JDK fails for the wrong reason (Step 0b).
7. **`compatibilityLevel` / `options.release`** — must match the target's bytecode
   level (table "Loom / Java"); backporting from a newer default branch means
   lowering them, or the jar won't load on the target's Java.
8. **Refmap / mixin not applying silently** — the config is `required: true`
   with `defaultRequire: 1`, so a missing target crashes at startup (loud, caught
   by the smoke test). Keep it that way. The optional injectors are the
   exception: `require = 0` by design, so another mod blocking them only leaves
   a line vanilla. They are the two hand-pass ones and the body-held rod's five:
   the held-item layer's read, the hook `submit` HEAD, and the three
   `@ModifyVariable`s on `submit`'s offset locals (without the read or the HEAD
   no body-held line moves; without a local that axis stays vanilla). A stale
   descriptor there is just as silent — the smoke test's `countInjections` run
   (Step 7) catches it. In game only the hand-pass ones log: a frame counter that
   never runs logs "Hand pass tracking isn't active", a hand mark never seen
   while fishing in first person logs "No first-person hand pass seen" (at most
   one of the two, once).
9. **Constants/derivation wrong even when it compiles** — the anchor NDC
   `(0.525, -0.1)`, `ITEM_POS`/`ITEM_HEIGHT_SCALE`/swing constants, the
   `handheld_rod` transforms, the line's 0.25 lift, `solveBodyRot`'s rider limits
   and (strategy B) the segment count 16 are vanilla internals; if they changed, or the math wasn't re-derived for this version, the
   line is off even though it compiles and loads. Step 9 manual testing catches
   this — a passing smoke test does NOT prove the line is in the right place. A
   resource-pack path that silently falls back to vanilla (wrong atlas id, frame
   handling) only shows up in Step 9's resource-pack stage.
10. **Over-claiming version coverage** — only set `depends.minecraft` to a range
    you actually validated the API matches across (Step 8), and always write it
    with a **closed** upper bound. `~X.Y`, `~X.Y.Z` and `<Y` all admit the next
    line's snapshots and pre-releases: Fabric normalises a snapshot as a
    pre-release of the release it leads to (`26w38a` → `26.3-alpha.26.38.a`,
    which sorts below `26.3`), so `~26.3` and `<26.4` both match it. The mod's
    required injectors then crash the player's client on a version nobody
    validated, where a closed bound would have had the loader refuse the mod.
    Closed 2026-09-23 on `1.21.11` (was `~1.21.11`), `26.2` (`~26.2`) and `26.3`
    (`~26.3`); `1.21.1` (`>=1.21 <1.21.2`) and `1.21.4` (unbounded `>=1.21`) are
    still open and should be closed when those branches are ported.
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
| Injection point (current fix) | `renderFishingLine` HEAD, cancel | `renderFishingLine` HEAD, cancel | `getHandPos` RETURN (strategy A; move to MEV on `Vec3d.add(Vec3d)` when porting) | `getPlayerHandPos`: `@ModifyExpressionValue` on its only `Vec3.add(Vec3)` (first-person branch; 26.3 bytecode has the third-person branch first); body-held rod: `FishingHookRenderer.submit` HEAD + `@ModifyVariable` STORE on float ordinals 0/1/2 (`xa`/`ya`/`za`, LVT slots 5–7), and `ItemInHandLayer.submitArmWithItem` at its `ItemStackRenderState.submit` INVOKE |
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
| Source layout (reference fix) | — | — | — | 26.3: mixins `FishingHookRendererMixin` (first-person origin MEV, visibility inject/wrap, body-held line at `submit`: HEAD + 3 `@ModifyVariable`), `GameRendererInvoker`, `GameRendererMixin` (frame count + F1/gate sampling, frame start for the body-held path), `FirstPersonHandsAndItemsRendererMixin` (hand drawn), `FishingHookRenderStateMixin` (line-hidden flag, body-rod owner + its partial tick + same-pass-only flag), `FishingHookMixin` (seen-with-rod flag), `SpriteContentsAccessor`, `CameraAccessor` (eye height), `ItemInHandLayerMixin` (drawn rod), `AbstractClientPlayerMixin` (drawn-rod record); logic `FishingLineOrigin` + `FirstPersonRod` (first person), `ThirdPersonLineOrigin` + `ThirdPersonRod` (body-held rod), `RodSprite` (pack tip), `FishingLineVisibility`, `HandPass`, `FishingRodFix` (logger, `isRod`, `holdsRod`) |
| Body-held rod (third person) | no deferred submit: the item layer renders during `render`; re-derive where the rod and the line are drawn and in which order (unverified) | as 1.20.4 (verify) | deferred `submit` from 1.21.11: verify the layer call and when the line's geometry is built | 26.3: read at `ItemInHandLayer.submitArmWithItem`'s `ItemStackRenderState.submit(PoseStack, SubmitNodeCollector, III)V` INVOKE (priority 1500, after PAL's item bones); `AvatarRenderState.id` → `ClientLevel.getEntity`; `Player.fishing` is set on the client (`FishingHook.setOwner`); the hook's line is finished at `FishingHookRenderer.submit` HEAD (`lineOriginOffset`, lifted 0.25 in `stringVertex`); `FeatureRenderDispatcher.prepareFrame` runs the custom geometry right after `submitFeatures` (Iris' shadow pass: own `SubmitNodeStorage`, own prepare); `LivingEntityRenderer.solveBodyRot` mirrored; body drawn in first person: `LevelExtractor.extractVisibleEntities` (camera entity, and detached or sleeping); Iris' `ShadowRenderer.extractVisibleEntities` (its 26.3 branch) skips only spectators, so its shadow pass draws the local body in first person too |
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
