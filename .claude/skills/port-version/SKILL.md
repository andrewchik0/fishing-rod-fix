---
name: port-version
description: Port the Fishing Rod Fix mod (the v0.4 mixin) to a target Minecraft version — backport to a recent release or forward-port to a new one. Use when asked to port/backport the mod to a specific MC version (e.g. "port to 26.2", "bring 1.21.5 up to v0.4"). Resolves Fabric coordinates, adapts the mixin to the target's real decompiled API, builds, smoke-tests that the mixin applies without crashing, then hands off for manual in-game verification before any commit.
---

# Port Fishing Rod Fix to a Minecraft version

## What this mod is (why correctness matters)

A purely client-side Fabric mod. A single `@Inject` mixin
(`FishingBobberEntityRendererMixin`) cancels vanilla `renderFishingLine` and
re-draws the fishing line at a corrected offset. The mixin is declared
`required: true` with `defaultRequire: 1`, so **if the injection fails to apply,
the game crashes at client startup**. This mod has thousands of players on
CurseForge/Modrinth — a bad port ships a crash. The prime directive of this
skill: **never finalize a port that hasn't been proven to load without crashing,
and never auto-commit/push correctness the user hasn't visually confirmed.**

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
  `.color(int,int,int,int)`; `@Inject` uses the **full descriptor** (`…FF)V`).
- **1.21.11+ (and 26.x — verify):** `.color(int)` + `.lineWidth(...)`, the
  descriptor gains a third float (`…FFF)V`), translate applied additively.
- **Pre-1.20 (1.19/1.18):** older still (`BufferBuilder`-era); only relevant if a
  1.19-era target is explicitly requested.

## Canonical source of the fix logic — the default branch

The reference implementation lives on the repository's **default branch**, which
tracks the latest shipped version (currently `1.21.11`, v0.4). **Do not hardcode
a version** — detect the default branch at the start of every run and use it as
`<default_branch>` throughout:

```sh
git remote show origin | sed -n '/HEAD branch/s/.*: //p'
```

Caveats (both matter — verified the hard way):
- This queries the **live** remote (needs network). The locally cached
  `git symbolic-ref --short refs/remotes/origin/HEAD` can be **stale** — e.g. it
  may still report `1.21.4` long after the GitHub default was moved to `1.21.11`.
  Prefer the live command; if offline, first refresh the cache with
  `git remote set-head origin -a`, then read the symbolic-ref.
- **Sanity-check** the result: `<default_branch>` must be the newest `v`-version
  (the v0.4+ reference). If detection yields an older line (e.g. a v0.3 branch),
  stop and resolve it — basing a port off a v0.3 branch silently drops the v0.4
  logic.

The v0.4 reference mixin is therefore
`git show <default_branch>:src/client/java/com/andrewchik/fishingrodfix/mixin/client/FishingBobberEntityRendererMixin.java`.
Every port re-expresses *that logic* in the target version's API. Do not invent
new logic — port the existing one. The headline v0.4 change over v0.3 is the
crouch-sag-jump fix (`crouchOffset` folded into the catenary `y`).

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

### Step 1 — Resolve Fabric coordinates for the target
Look these up; do not guess. Sources (use WebFetch):
- **Game / yarn / loader:** `https://meta.fabricmc.net/v2/versions/yarn/<mc_version>`
  (gives the latest `version` like `26.2+build.N`), and
  `https://meta.fabricmc.net/v2/versions/loader` (latest stable loader).
- **Fabric API** for the MC version: latest matching version of the
  `fabric-api` project on Modrinth —
  `https://api.modrinth.com/v2/project/fabric-api/version` (pick the entry whose
  `game_versions` contains `<mc_version>`; its `version_number` is the
  `fabric_version` string, e.g. `0.141.1+1.21.11`).
- **Loom plugin version + recommended values (cross-check):**
  `https://fabricmc.net/develop` — the page renders the exact recommended
  `gradle.properties` block and the `fabric-loom` plugin version for a selected
  MC version. Newer MC ⇒ newer Loom (observed: 1.21.4→loom 1.9, 1.21.5→1.10,
  1.21.11→1.14). Use the Loom version the develop page recommends for the target.
- **Runtime Java:** confirm from the develop page / Mojang notes. Assume Java 21
  unless the target explicitly requires newer.

Record the resolved set: `minecraft_version`, `yarn_mappings`, `loader_version`,
`fabric_version`, `loom_version`, `runtime_java`.

### Step 2 — Branch
Per repo convention, **each MC version lives on its own branch**.
- If a branch named exactly `<mc_version>` exists: check it out. (It already has
  correct build config; you are bringing its mixin up to v0.4 logic.)
- Else: create `<mc_version>` from the **nearest existing branch of the same API
  family** (Step 0 reminder: modern vs legacy):
  - Modern target (1.21+/26.x): base off `<default_branch>` (the current v0.4
    source — detect it, see "Canonical source").
    `git checkout <default_branch> && git checkout -b <mc_version>`.
  - Legacy target (pre-1.21): base off the nearest existing legacy branch
    (`1.20`, `1.20.5`, `1.21` boundary, `1.19`, `1.19.3` on `origin`). Its mixin
    is the structural/API template; you still port the v0.4 *logic* deltas onto
    it (see Step 4).

### Step 3 — Update build configuration
Edit on the target branch:
- **`gradle.properties`**: set `minecraft_version`, `yarn_mappings`,
  `loader_version`, `fabric_version` to the resolved set. Bump `mod_version` to
  the version being shipped (e.g. `0.4`).
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
Combine two sources: the **v0.4 logic** comes from the `<default_branch>`
reference mixin; the **API shape** (signature, vertex-emit style, names) comes
from the target's own decompiled source — and for a legacy target, the existing
legacy-family branch's mixin (`1.20`, `1.19`, …) is your structural template for
its older `.next()`-style API. So: take the legacy branch's API skeleton and
graft the v0.4 logic deltas (the crouch-sag-jump fix, the FOV/aspect correction)
onto it.
1. Run `./gradlew genSources` so the decompiled Minecraft sources for the target
   version are available.
2. Open the decompiled `FishingBobberEntityRenderer` for the target and read the
   actual `renderFishingLine` method. Verify, against the v0.4 reference:
   - **The exact descriptor** of `renderFishingLine` (parameter count and types).
     This goes verbatim into `@Inject(method = "...")`. Known drift: 1.21.4 was
     `(FFFL…VertexConsumer;L…MatrixStack$Entry;FF)V` (2 trailing floats);
     1.21.11 added a third float (`getMinimumLineWidth`) →
     `(…FFF)V`. **A wrong descriptor with `required:true` = startup crash.**
   - **The vertex-emit call shape**: `.color(int)` vs `.color(int,int,int,int)`,
     presence of `.lineWidth(...)`, `.normal(...)`, and (pre-1.21) a trailing
     `.next()`. Match the target's `VertexConsumer` API exactly.
   - **Method/field names used by `calculateTranslate()`**, each of which has
     renamed across versions — confirm the current name in the target mappings:
     - tick delta: `renderTickCounter.getDynamicDeltaTicks()` (newer) vs the
       `tickDelta`/`tickDeltaBeforePause` fields + `isPaused()` (older).
     - camera position: `Camera.getCameraPos()` (newer) vs `getPos()` (older).
     - player position: `player.getEntityPos()` (newer) vs `getPos()` (older).
     - `getStandingEyeHeight()`, `getYaw/getPitch(tickDelta)`,
       `lastRenderYaw/renderYaw`, `lastRenderPitch/renderPitch`,
       `getMainArm()`, `getStackInHand(Hand.OFF_HAND)`, `FishingRodItem`.
   - **The vanilla constants** referenced in comments (`field_33632` = 960f FOV
     scale, NDC_X `0.525`, near plane `0.05`, segment count `16`): confirm the
     segment count and FOV-scale constant still hold in the target's source.
3. Re-express the v0.4 logic with the corrected signature/API.
4. **Verify the MATH against the target's real render source — do not assume any
   formula or constant transfers.** The rendering internals churn between versions
   and a formula that was correct on the reference branch can be silently wrong on
   the target even though it compiles and the mixin applies (Step 7 only proves it
   loads, never that the line is in the right place). For every quantity the fix
   depends on, open the target's decompiled source and re-derive:
   - **Where the line origin actually comes from.** On 26.1+ there is no
     `renderFishingLine`; the origin is `FishingHookRenderer.getPlayerHandPos`
     (world-space), so the fix became an `@Inject` at its `RETURN`, not a cancel.
   - **Which FOV / aspect each thing is projected with.** The visible rod is drawn
     in a *separate hand pass* at a **fixed `hudFov` (70° on 26.1, see
     `Camera.calculateHudFov`)**, while the line is a world entity projected at the
     **actual** FOV (`Camera.getFov()`, includes the sprint/speed modifier). Confirm
     these per version — the old `fovMultiplier`/`lastFovMultiplier` fields were
     replaced by `getFov()`.
   - **Prefer deriving from vanilla's own quantities over empirical magic numbers.**
     The v0.3/v0.4 `YAW_SWAY_FACTOR`/`PITCH_SWAY_FACTOR` (0.0001-ish) had no physical
     basis and were simply *wrong* on 26.1 (overshoot). The robust fixes mirror what
     vanilla actually does: the item sway is vanilla's own `(getViewXRot-xBob)*0.1°` /
     `(getViewYRot-yBob)*0.1°` hand rotation (`ItemInHandRenderer.renderHandsWithItems`)
     applied to the eye→rod-tip vector; the aspect/FOV correction is an exact
     re-projection (rescale the view-space components by `tan(actualFov/2)/tan(baseFov/2)`
     and `refAR/realAR`) in which the hand-calibration constants (`0.525`, `960`, near
     plane) cancel out. A correction built from projection laws + vanilla constants
     survives renderer churn; a tuned constant does not.
   - **Crouch sag-jump** is real but its form changes: on 26.1 `getEyePosition` uses
     the *stepped* `getEyeHeight()` while the camera uses a *smoothed* eye height, so
     the fix is simply `cameraY - eyePosY` (0 when settled, non-zero only mid-animation).
   - **Know when to stop.** A fully-correct fix for some effects (e.g. walk `bobView`/
     `bobHurt`) would depend on unstable, version-churning render-state/avatar internals
     — that would make the fix the *least* robust part of the mod. Prefer leaving such
     an effect as a documented minor limitation over coupling to internals that break
     next version.
   Re-derive each formula from the target source, then let Step 9 manual testing
   confirm placement; if the line is off, fix the derivation, do not just re-tune a
   constant.

### Step 5 — Update the access widener
`src/main/resources/fishingrodfix.accesswidener` must list exactly the fields the
mixin accesses, with owners/descriptors that **exist in the target mappings**.
Known drift:
- 1.21.4 exposed `RenderTickCounter$Dynamic.tickDelta` / `.tickDeltaBeforePause`.
- 1.21.11 exposes `MinecraftClient.renderTickCounter` plus
  `GameRenderer.fovMultiplier` / `.lastFovMultiplier`.
Add an entry only for a field the mixin truly reads via AW, and verify the field
+ owner exist in the decompiled target (a stale AW entry breaks the build or AW
load). Remove entries the ported mixin no longer needs.

### Step 6 — Build
`./gradlew build`. This compiles against the target mappings and makes Loom
generate the mixin refmap. Fix compile errors (these usually mean a name from
Step 4 is still wrong). A green build proves names/signatures *compile*, but
**not** that the injection *applies at runtime* — that's Step 7.

**Deliverable artifact:** thanks to the Step 3 jar-naming block, `build` writes
the player-facing jar to `build/libs/` as
`fishingrodfix-<minecraft_version>-v<mod_version>.jar`
(e.g. `fishingrodfix-1.21.11-v0.4.jar`). After the build, confirm that exact file
exists with the expected name. (`build/libs` also contains a default-named
`fishingrodfix-<mod_version>-sources.jar` — that's the sources jar, not
distributed; leave it.) If manual verification (Step 9) leads to re-tuning the
constants, **re-run `./gradlew build`** so the jar matches the final committed
code; the deliverable is the post-verification build.

### Step 7 — Smoke test (automated gate: "does it load without crashing")
Entity renderers are constructed at client startup, so `FishingBobberEntityRenderer`
loads early and the mixin applies during startup — a bad descriptor crashes
before the title screen. Reaching the menu therefore proves the mixin applied.

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
   the Gradle/JVM process).
4. Report: PASS (mixin applied, reached menu) or FAIL with the offending log
   excerpt. On FAIL, return to Step 4 — the descriptor or a mapping is wrong.

If the environment cannot run a GUI client, say so and downgrade to Step 6 only,
flagging that runtime application was **not** verified.

### Step 8 — Decide single-build vs per-version (range optimization)
Mojang ships a breaking version, then patch releases; players settle on the last
patch of a line. One jar can cover a **range** when the target API is identical
across it. Before splitting work into multiple branches:
- Compare the resolved `renderFishingLine` descriptor and the
  `VertexConsumer`/mapping names across the candidate versions (e.g. 26.1 vs
  26.2). If identical, ship **one** build and widen `depends.minecraft` to a
  range (this repo already did `>=1.21` covering 1.21–1.21.1, `1.20–1.20.4`,
  etc.). If they differ, they need separate branches/builds.
- Prefer targeting the **latest patch** of the current line (per the user: 26.2
  over 26.1) and covering earlier patches via the range when the API matches.
- `log()`/state clearly which versions a given build actually covers — never
  silently imply broader coverage than tested.

### Step 9 — Manual verification handoff (REQUIRED before commit)
Stop and ask the user to verify in-game with a fishing rod. Give them this
checklist (each item exercises a different branch of the math):
- [ ] Line attaches to the rod tip while fishing — right hand, default 16:9.
- [ ] **Crouch while fishing** (the v0.4 sag-jump fix) — line stays attached, no jump.
- [ ] Sprint / speed-potion FOV change — note: FOV correction is only partial
      (known limitation), confirm it's not visibly worse.
- [ ] Fishing rod in the **off-hand**.
- [ ] **Left-handed** main arm (Options → Skin Customization → Main Hand: Left).
- [ ] **Non-16:9** aspect ratio — resize window / ultrawide / 4:3.
- [ ] Third person — falls back to vanilla (no correction artifacts).
- [ ] Fast camera movement — line sway looks natural.

Only proceed to commit after the user confirms. If the line is offset, the
empirical constants (Step 4.3) likely need re-tuning for this version.

### Step 10 — Git
After the user's OK:
- Commit **locally** on the target branch. Match the repo's terse message style,
  e.g. `Port to <mc_version>, v<mod_version>` or, for a backport,
  `<mc_version>` / `Backport v0.4 to <mc_version>`. Include the conventional
  trailers from the project commit guidance.
- **Do not push** unless the user explicitly asks. Pushing publishes to a repo
  that feeds player-facing releases — treat it as outward-facing and gated on an
  explicit "push" / "release" from the user.

---

## Pitfalls reference (the things that break players' games)
1. **`@Inject` descriptor mismatch** — #1 crash cause. `renderFishingLine`'s
   parameter list changes between versions (`…FF)V` → `…FFF)V`). `required:true`
   turns a mismatch into a startup crash. Always copy the descriptor from the
   target's decompiled source (Step 4).
2. **VertexConsumer API drift** — `.color(0,0,0,255)` vs `.color(0xFF000000)`,
   `.lineWidth()` added in newer, `.next()` required in pre-1.21. Match exactly.
3. **Yarn name renames** — `getDynamicDeltaTicks` vs `tickDelta`/`isPaused`;
   `getCameraPos` vs `getPos`; `getEntityPos` vs `getPos`. Compile catches these,
   but only if you actually rebuild against target mappings.
4. **Stale access widener** — an AW entry whose field/owner doesn't exist in the
   target mappings breaks the build or AW load. Sync AW to what the ported mixin
   uses (Step 5).
5. **Loom version mismatch** — too-old Loom can't remap/decompile a newer MC.
   Use the develop-page-recommended Loom for the target.
6. **Wrong runtime Java for `runClient`** — 1.20.5+ needs Java 21; a smoke test on
   Java 17 fails for the wrong reason. Verify the Gradle JVM (Step 0).
7. **`compatibilityLevel` / `options.release`** — bump only if the target
   requires it; needless bumps can break on older targets.
8. **Refmap / mixin not applying silently** — with `required:true` it crashes
   (loud, caught by smoke test); if anyone lowers `defaultRequire`, the fix would
   silently no-op. Keep it required so failures are loud.
9. **Empirical constants need re-tuning** — `field_33632` (960f), segment count
   (16), NDC_X (0.525) are vanilla internals; if they changed, the math is off
   even when it compiles and loads. This is exactly what Step 9 manual testing
   catches — a passing smoke test does NOT prove the line is in the right place.
10. **Over-claiming version coverage** — only set `depends.minecraft` to a range
    you actually validated the API matches across (Step 8).

## Known per-version API deltas (reference, extend as you learn more)
| Concern | 1.20.4 (v0.3, legacy) | 1.21.4 (v0.3) | 1.21.11 (v0.4) |
|---|---|---|---|
| `@Inject` match | name only: `"renderFishingLine"` | full descriptor `…FF)V` | full descriptor `…FFF)V` |
| trailing params | 2 floats | 2 floats | 3 floats (+`getMinimumLineWidth`) |
| Vertex emit | `.color(0,0,0,255).normal(getNormalMatrix(),…).next()` | `.color(0,0,0,255).normal(matrices,…)` | `.color(0xFF000000).normal(matrices,…).lineWidth(w)` |
| Translate application | `getPositionMatrix().translate(t)` | `getPositionMatrix().translate(t)` | additive `t.{x,y,z}*segment` in math |
| Tick delta | `renderTickCounter`/`pausedTickDelta` (AW) | `renderTickCounter.tickDelta`/`tickDeltaBeforePause`+`isPaused()` | `renderTickCounter.getDynamicDeltaTicks()` |
| Camera / player pos | `getPos()` / `getPos()` | `getPos()` / `getPos()` | `getCameraPos()` / `getEntityPos()` |
| Access widener | `renderTickCounter` + `pausedTickDelta` + `getFov` method | `tickDelta`/`tickDeltaBeforePause` fields | `renderTickCounter` + `fovMultiplier`/`lastFovMultiplier` |
| Loom / Java | 1.6-SNAPSHOT / 17 | 1.9-SNAPSHOT / 17 | 1.14-SNAPSHOT / 17 |

**1.20.4 coordinates (most-played target, from `origin/1.20`):**
`minecraft_version=1.20.4`, `yarn_mappings=1.20.4+build.3`,
`loader_version=0.15.11`, `fabric_version=0.97.0+1.20.4`, loom `1.6-SNAPSHOT`.
The `1.20–1.20.4` jar covers that whole range via `depends.minecraft`.
