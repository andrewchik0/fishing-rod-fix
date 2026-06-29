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
> The per-version table at the bottom is the one place version specifics live, and
> it is explicitly "observed, verify against source" — extend it, don't trust it.

## What this mod is (why correctness matters)

A purely client-side Fabric mod. A single `@Inject` mixin corrects the
first-person fishing-line origin so the line meets the rod tip. The mixin is
declared `required: true` with `defaultRequire: 1`, so **if the injection fails
to apply, the game crashes at client startup**. This mod has thousands of players
on CurseForge/Modrinth — a bad port ships a crash. The prime directive of this
skill: **never finalize a port that hasn't been proven to load without crashing,
and never auto-commit/push correctness the user hasn't visually confirmed.**

There are two integration points the fix can use depending on the target's
renderer (see "Two injection strategies"): correcting a world-space hand-position
method at `RETURN` (modern, preferred), or cancelling and redrawing
`renderFishingLine` (legacy). Pick from the target's decompiled source, not from
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
- **1.21–1.21.10:** no `.next()`, `.normal(matrices, …)`, still
  `.color(int,int,int,int)`; if using the `renderFishingLine` path, `@Inject`
  uses the **full descriptor** (`…FF)V`).
- **1.21.11+ (and 26.x):** the line is emitted through a deferred render-command
  `submit`, and the world-space line origin is a hand-position method
  (`getHandPos` on 1.21.x, `getPlayerHandPos` on 26.x) — so the **preferred** fix
  injects that method at `RETURN` (strategy A). `renderFishingLine` still exists
  on 1.21.11 (`.color(int)` + `.lineWidth(...)`, descriptor `…FFF)V`) but the
  hand-position approach is cleaner and more robust. On 26.x there is no
  `renderFishingLine` to cancel at all.
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
- Read that mixin to learn the **current** fix logic and which injection strategy
  it uses. Every port re-expresses *that logic* in the target's API. Do not invent
  new logic — port the existing one.

## Two injection strategies — pick by the target's decompiled source

The fix always corrects the first-person line origin; *how* depends on what the
target renderer exposes. Decide from the decompiled source (Step 4), not the
version number. The corrective **math is identical** in both; only the integration
point and the coordinate frame differ.

**A. World-space hand-position correction (preferred where available).** Modern
renderers compute the line origin as a world-space point returned by a method —
`getHandPos` (1.21.x, including 1.21.11) or `getPlayerHandPos` (26.x) — and emit
the line through a deferred render-command `submit`. `@Inject` at that method's
`RETURN` (cancellable, `CallbackInfoReturnable<Vec3d>`/`<Vec3>`), and replace the
returned point with the corrected world position: `eyePos + correction(eye→rod-tip
vector)`. The catenary is left untouched. This is the robust, math-derived
approach the current reference uses. The method returns world space (it is built
as `player.getCameraPosVec(t)/getEyePosition(t) + handOffset`), so reconstruct in
the same frame. The hand-side sign (main/off hand, left-handed) is already baked
into vanilla's vector — don't re-apply it.

**B. `renderFishingLine` cancel + redraw (legacy).** Older renderers draw the
catenary inside a cancellable `renderFishingLine`. `@Inject` at `HEAD`
(cancellable), cancel, and redraw each segment with a corrected additive
translate (the translate is recomputed once per 16-segment cycle). Use this only
when the target has no world-space hand-position method to inject.

## Invocation

`/port-version <mc_version>` — e.g. `/port-version 26.2`, `/port-version 1.21.5`.

If no version is given, ask which one. One run = one target version.

---

## Procedure

### Step 0 — Preconditions
- `git status` must be clean. If not, stop and ask the user to stash/commit.
- Confirm the Gradle JVM in use satisfies the **target's runtime Java** (see
  Step 1). MC 1.20.5+ (incl. all 1.21.x and 26.x) needs **Java 21** to run the
  client, even though mod bytecode targets Java 17. If `runClient` would launch
  on an older JDK, the smoke test will fail for the wrong reason.
- **Make sure this skill is present on the branch you will work on.** It is a
  project skill loaded from the checked-out branch's `.claude/skills/`. Older
  version branches may not carry it. If you are about to port on a branch that
  lacks it, copy it from the default branch first (this also backs it up there):
  `git checkout <default_branch> -- .claude/skills/port-version`.

### Step 1 — Resolve Fabric coordinates for the target
Look these up; do not guess. Sources (use WebFetch):
- **Game / yarn / loader:** `https://meta.fabricmc.net/v2/versions/yarn/<mc_version>`
  (gives the latest `version` like `26.2+build.N`), and
  `https://meta.fabricmc.net/v2/versions/loader` (latest stable loader). Note:
  26.1+ is **unobfuscated** (no yarn/intermediary) — there is no `yarn_mappings`
  for those; the build uses Mojang names directly.
- **Fabric API** for the MC version: latest matching version of the
  `fabric-api` project on Modrinth —
  `https://api.modrinth.com/v2/project/fabric-api/version` (pick the entry whose
  `game_versions` contains `<mc_version>`; its `version_number` is the
  `fabric_version` string, e.g. `0.141.1+1.21.11`).
- **Loom plugin version + recommended values (cross-check):**
  `https://fabricmc.net/develop` — the page renders the exact recommended
  `gradle.properties` block and the `fabric-loom` plugin version for a selected
  MC version. Newer MC ⇒ newer Loom (observed: 1.21.4→loom 1.9, 1.21.5→1.10,
  1.21.11→1.14, 26.x→1.17). Use the Loom version the develop page recommends.
- **Runtime Java:** confirm from the develop page / Mojang notes. Assume Java 21
  unless the target explicitly requires newer (26.x toolchains may want 25).

Record the resolved set: `minecraft_version`, `yarn_mappings` (if applicable),
`loader_version`, `fabric_version`, `loom_version`, `runtime_java`.

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
  uses yarn), `loader_version`, `fabric_version` to the resolved set. Bump
  `mod_version` to the reference's current value (Step "Canonical source").
- **`build.gradle`**: set the `fabric-loom` plugin version to `loom_version`.
  Bump `it.options.release` / `sourceCompatibility` / `targetCompatibility` only
  if the target requires a higher Java bytecode level (usually leave at 17).
  Ensure the **jar-naming block** is present (it makes `build` emit the correctly
  named artifact — see Step 6). It is set on `remapJar` so `fabric.mod.json`'s
  `version` stays clean (= `mod_version`), and it reads the gradle.properties
  values so it auto-adjusts per port:
  ```groovy
  tasks.named('remapJar') {
      archiveFileName = "${project.archives_base_name}-${project.minecraft_version}-v${project.mod_version}.jar"
  }
  ```
  If a base branch predates this block, add it. (Do NOT instead set
  `version = "${minecraft_version}-${mod_version}"` — that older approach leaks
  the MC version into `fabric.mod.json`.)
- **`src/client/resources/fishingrodfix.client.mixins.json`**: set
  `compatibilityLevel` to match (e.g. `JAVA_17`); raise only if required.
- **`src/main/resources/fabric.mod.json`**: set `depends.minecraft` (see Step 8
  for single-build ranges) and `depends.java` to match.

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
     `@Inject(method = "...")`. Examples:
     `getHandPos(Lnet/minecraft/entity/player/PlayerEntity;FF)Lnet/minecraft/util/math/Vec3d;`
     (1.21.11, strategy A); `renderFishingLine(…FF)V` (1.21.4) →
     `(…FFF)V` (1.21.11, +`getMinimumLineWidth`, strategy B). **A wrong descriptor
     with `required:true` = startup crash** — copy it from `javap`/decompiled
     source, never from memory.
   - **For strategy B, the vertex-emit call shape**: `.color(int)` vs
     `.color(int,int,int,int)`, presence of `.lineWidth(...)`, `.normal(...)`, and
     (pre-1.21) a trailing `.next()`. Match the target's `VertexConsumer` API.
   - **Method/field names the math uses**, each of which has renamed across
     versions — confirm the current name in the target mappings:
     - tick delta: the render partial-tick. With strategy A you usually already
       have it as the method's `tickProgress`/`partialTicks` parameter; otherwise
       `renderTickCounter.getDynamicDeltaTicks()` (newer) vs the
       `tickDelta`/`tickDeltaBeforePause` fields + `isPaused()` (older).
     - eye position: `player.getCameraPosVec(t)` (yarn) / `getEyePosition(t)`
       (Mojang).
     - camera basis: yarn `Camera.getHorizontalPlane()`/`getVerticalPlane()`/
       `getDiagonalPlane()` = forward/up/**left** (local `(0,0,-1)/(0,1,0)/(-1,0,0)`
       rotated) ↔ Mojang `forwardVector()`/`upVector()`/`leftVector()`.
     - camera / player pos: `Camera.getCameraPos()` (newer) vs `getPos()`;
       `player.getEntityPos()` (newer) vs `getPos()`.
     - `getStandingEyeHeight()`, `getYaw/getPitch(t)`, `lastRenderYaw/renderYaw`,
       `lastRenderPitch/renderPitch`, `getMainArm()`,
       `getStackInHand(Hand.OFF_HAND)`, `FishingRodItem`.
   - **The vanilla constants** referenced in comments (`field_33632` = 960f FOV
     scale, NDC_X `0.525`, hand depth `-0.1`, near plane `0.05`, segment count
     `16`): confirm they still hold in the target's source.
3. Re-express the reference logic with the corrected injection point/signature/API.
4. **Verify the MATH against the target's real render source — do not assume any
   formula or constant transfers.** The rendering internals churn between versions
   and a formula that was correct on the reference branch can be silently wrong on
   the target even though it compiles and the mixin applies (Step 7 only proves it
   loads, never that the line is in the right place). For every quantity the fix
   depends on, open the target's decompiled source and re-derive:
   - **Where the line origin actually comes from.** Strategy A: the world-space
     point returned by `getHandPos`/`getPlayerHandPos` (`eye + handOffset`).
     Strategy B: the `renderFishingLine` catenary built from the bobber→rod-tip
     delta.
   - **Which FOV / aspect each thing is projected with.** The visible rod is drawn
     in a *separate hand pass* at the options FOV (no sprint/speed multiplier; on
     26.1 a fixed `hudFov` 70°, see `Camera.calculateHudFov`), while the line is a
     world entity projected at the **actual** FOV (options FOV × the sprint/speed
     modifier). Get the actual FOV per version: **26.x** `Camera.getFov()`;
     **1.21.x** has no `Camera.getFov()` → `baseFov * lerp(tickProgress,
     GameRenderer.lastFovMultiplier, fovMultiplier)` (both fields AW-exposed).
   - **Prefer deriving from vanilla's own quantities over empirical magic numbers.**
     Old `YAW_SWAY_FACTOR`/`PITCH_SWAY_FACTOR` (0.0001-ish) had no physical basis
     and were simply *wrong* (overshoot). The robust fixes mirror what vanilla
     actually does: the item sway is vanilla's own
     `(getViewXRot/​getPitch − xBob/​renderPitch)*0.1°` and
     `(getViewYRot/​getYaw − yBob/​renderYaw)*0.1°` hand rotation
     (`ItemInHandRenderer.renderHandsWithItems` on Mojang; `HeldItemRenderer` on
     yarn — rotates about camera X then Y) applied to the eye→rod-tip vector; the
     aspect/FOV correction is an exact re-projection (rescale the view-space
     components by `tan(actualFov/2)/tan(baseFov/2)` and `refAR/realAR`) in which
     the hand-calibration constants (`0.525`, `960`, near plane) cancel out. A
     correction built from projection laws + vanilla constants survives renderer
     churn; a tuned constant does not.
   - **Crouch sag-jump** is real but its form changes: the eye-position method
     (`getCameraPosVec`/`getEyePosition`) uses the *stepped* eye height
     (`getStandingEyeHeight()`, which jumps on the pose change) while the camera
     uses a *smoothed* eye height (`Camera.updateEyeHeight`), so the fix is simply
     `cameraY − eyePosY` (0 when settled, non-zero only mid-animation). Confirm the
     two sources still differ on the target.
   - **Know when to stop.** A fully-correct fix for some effects (e.g. walk
     `bobView`/`bobHurt`) would depend on unstable, version-churning
     render-state/avatar internals — that would make the fix the *least* robust
     part of the mod. Prefer leaving such an effect as a documented minor
     limitation over coupling to internals that break next version.
   Re-derive each formula from the target source, then let Step 9 manual testing
   confirm placement; if the line is off, fix the derivation, do not just re-tune a
   constant.

### Step 5 — Update the access widener
`src/main/resources/fishingrodfix.accesswidener` must list exactly the fields the
mixin accesses via AW, with owners/descriptors that **exist in the target
mappings**. Known drift:
- 1.21.4 exposed `RenderTickCounter$Dynamic.tickDelta` / `.tickDeltaBeforePause`.
- 1.21.11 (strategy A): only `GameRenderer.fovMultiplier` / `.lastFovMultiplier`
  (the actual FOV); `renderTickCounter` is **not** needed when the injected
  method already provides `tickProgress`.
- 26.x: `Camera.getFov()` is public — no AW needed for FOV.
Add an entry only for a field the mixin truly reads via AW, and verify the field +
owner exist in the decompiled target (a stale AW entry breaks the build or AW
load). **Remove entries the ported mixin no longer needs.**

### Step 6 — Build
`./gradlew build`. This compiles against the target mappings and makes Loom
generate the mixin refmap. Fix compile errors (these usually mean a name from
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
Entity renderers are constructed at client startup, so the target renderer loads
early and the mixin applies during startup — a bad descriptor crashes before the
title screen. Reaching the menu therefore proves the mixin applied.

Procedure (the agent runs this adaptively; do not rely on a brittle kill script):
1. Launch `./gradlew runClient` in the background, capturing stdout+stderr to a
   log file in the scratchpad.
2. Poll the log until either:
   - **Success marker** — the client reached the menu, e.g. lines containing
     `Sound engine started`, `OpenAL initialized`, or `Backend library: LWJGL`
     after full mod load with no fatal error.
   - **Failure markers** — `Mixin apply` … `failed`,
     `InvalidInjectionException`, `org.spongepowered.asm.mixin`,
     `Failed to start`, `A mod crashed on startup`, `Caused by:`, or the process
     exits non-zero.
3. Terminate the client process tree (on Windows, `taskkill /F /T /PID <pid>` of
   the Gradle/JVM process; find it via `Get-CimInstance Win32_Process` filtered on
   the project path).
4. Report: PASS (mixin applied, reached menu) or FAIL with the offending log
   excerpt. On FAIL, return to Step 4 — the descriptor or a mapping is wrong.

If the environment cannot run a GUI client, say so and downgrade to Step 6 only,
flagging that runtime application was **not** verified.

### Step 8 — Decide single-build vs per-version (range optimization)
Mojang ships a breaking version, then patch releases; players settle on the last
patch of a line. One jar can cover a **range** when the target API is identical
across it. Before splitting work into multiple branches:
- Compare the resolved injection-point descriptor and the `VertexConsumer`/mapping
  names across the candidate versions (e.g. 26.1 vs 26.2). If identical, ship
  **one** build and widen `depends.minecraft` to a range (this repo already did
  `>=1.21` covering 1.21–1.21.1, `1.20–1.20.4`, etc.). If they differ, they need
  separate branches/builds.
- Prefer targeting the **latest patch** of the current line and covering earlier
  patches via the range when the API matches.
- State clearly which versions a given build actually covers — never silently
  imply broader coverage than tested.

### Step 9 — Manual verification handoff (REQUIRED before commit)
Stop and ask the user to verify in-game with a fishing rod. Give them this
checklist (each item exercises a different branch of the math):
- [ ] Line attaches to the rod tip while fishing — right hand, default 16:9.
- [ ] **Crouch while fishing** (the sag-jump fix) — line stays attached, no jump.
- [ ] Sprint / speed-potion FOV change — the aspect/FOV re-projection should keep
      it attached; confirm it's not visibly worse.
- [ ] Fishing rod in the **off-hand**.
- [ ] **Left-handed** main arm (Options → Skin Customization → Main Hand: Left).
- [ ] **Non-16:9** aspect ratio — resize window / ultrawide / 4:3.
- [ ] Third person — falls back to vanilla (no correction artifacts).
- [ ] Fast camera movement — line sway looks natural.

Only proceed to commit after the user confirms. If the line is offset, re-derive
the formula from the target source (Step 4.4) — do not just re-tune a constant.

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
1. **`@Inject` descriptor mismatch** — #1 crash cause. The injected method's
   parameter list changes between versions (`renderFishingLine` `…FF)V` → `…FFF)V`;
   `getHandPos`/`getPlayerHandPos` differ in package/return mapping). `required:true`
   turns a mismatch into a startup crash. Always copy the descriptor from the
   target's decompiled source / `javap` (Step 4).
2. **VertexConsumer API drift** (strategy B) — `.color(0,0,0,255)` vs
   `.color(0xFF000000)`, `.lineWidth()` added in newer, `.next()` required in
   pre-1.21. Match exactly.
3. **Name renames** — `getDynamicDeltaTicks` vs `tickDelta`/`isPaused`;
   `getCameraPos` vs `getPos`; `getEntityPos` vs `getPos`; yarn
   `getHorizontalPlane/getVerticalPlane/getDiagonalPlane` vs Mojang
   `forwardVector/upVector/leftVector`. Compile catches these, but only if you
   actually rebuild against target mappings.
4. **Stale access widener** — an AW entry whose field/owner doesn't exist in the
   target mappings breaks the build or AW load. Sync AW to what the ported mixin
   uses; remove entries it no longer needs (Step 5).
5. **Loom version mismatch** — too-old Loom can't remap/decompile a newer MC.
   Use the develop-page-recommended Loom for the target.
6. **Wrong runtime Java for `runClient`** — 1.20.5+ needs Java 21 (26.x may want
   25); a smoke test on the wrong JDK fails for the wrong reason (Step 0).
7. **`compatibilityLevel` / `options.release`** — bump only if the target
   requires it; needless bumps can break on older targets.
8. **Refmap / mixin not applying silently** — with `required:true` it crashes
   (loud, caught by smoke test); if anyone lowers `defaultRequire`, the fix would
   silently no-op. Keep it required so failures are loud.
9. **Constants/derivation wrong even when it compiles** — `field_33632` (960f),
   segment count (16), NDC_X (0.525) are vanilla internals; if they changed, or
   the math wasn't re-derived for this version, the line is off even though it
   compiles and loads. Step 9 manual testing catches this — a passing smoke test
   does NOT prove the line is in the right place.
10. **Over-claiming version coverage** — only set `depends.minecraft` to a range
    you actually validated the API matches across (Step 8).

## Known per-version API deltas (OBSERVED — verify against the target source, not authoritative)
Extend this as you learn more; never trust it over the decompiled source.

| Concern | 1.20.4 (legacy) | 1.21.4 | 1.21.11 | 26.x |
|---|---|---|---|---|
| Injection point (current fix) | `renderFishingLine` HEAD, cancel | `renderFishingLine` HEAD, cancel | `getHandPos` RETURN (strategy A) | `getPlayerHandPos` RETURN (strategy A) |
| Injected descriptor | name only: `"renderFishingLine"` | `renderFishingLine(…FF)V` | `getHandPos(L…PlayerEntity;FF)L…Vec3d;` | `getPlayerHandPos(L…Player;FF)L…Vec3;` |
| Line emit path | direct vertex | direct vertex | deferred `submit` | deferred `submit` |
| Vertex emit (strategy B only) | `.color(0,0,0,255).normal(getNormalMatrix(),…).next()` | `.color(0,0,0,255).normal(matrices,…)` | `.color(0xFF000000).normal(matrices,…).lineWidth(w)` | n/a (no `renderFishingLine`) |
| Actual FOV | `GameRenderer.getFov` (AW) | `baseFov*lerp(fovMultiplier)` (AW) | `baseFov*lerp(fovMultiplier)` (AW) | `Camera.getFov()` (public) |
| Tick delta | `renderTickCounter`/`pausedTickDelta` (AW) | `renderTickCounter.tickDelta`/`…BeforePause`+`isPaused()` | method `tickProgress` param | method `partialTicks` param |
| Eye / camera / player pos | `getPos()` | `getPos()` | `getCameraPosVec(t)` / `getCameraPos()` / `getEntityPos()` | `getEyePosition(t)` / `camera.position()` / `getEntityPos()` |
| Camera basis | `forwardVector/upVector/leftVector` (Mojang-ish) | yarn `getHorizontalPlane/getVerticalPlane/getDiagonalPlane` | yarn `getHorizontalPlane/getVerticalPlane/getDiagonalPlane` | Mojang `forwardVector/upVector/leftVector` |
| Sway source | item-renderer `*0.1°` | item-renderer `*0.1°` | `HeldItemRenderer` `(getPitch-renderPitch)*0.1°`/`(getYaw-renderYaw)*0.1°` | `ItemInHandRenderer` `(getViewXRot-xBob)*0.1°`/`(getViewYRot-yBob)*0.1°` |
| Access widener | `renderTickCounter`+`pausedTickDelta`+`getFov` | `tickDelta`/`tickDeltaBeforePause` | `GameRenderer.fovMultiplier`/`lastFovMultiplier` | none |
| Loom / Java | 1.6-SNAPSHOT / 17 | 1.9-SNAPSHOT / 17 | 1.14-SNAPSHOT / 17 (run JDK 21) | 1.17-SNAPSHOT / 17 (run JDK 21+) |

**1.20.4 coordinates (most-played target, from `origin/1.20`):**
`minecraft_version=1.20.4`, `yarn_mappings=1.20.4+build.3`,
`loader_version=0.15.11`, `fabric_version=0.97.0+1.20.4`, loom `1.6-SNAPSHOT`.
The `1.20–1.20.4` jar covers that whole range via `depends.minecraft`.
