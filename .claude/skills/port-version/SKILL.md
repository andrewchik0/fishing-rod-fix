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
heavy targets are the **1.20.x line** (the v0.5 `1.20–1.20.4` jar has by far the most
downloads; v0.6's `1.20` branch narrows to `1.20–1.20.1`, where ~99% of that line is), the **1.21 / 1.21.1** and **1.21.4 / 1.21.5** lines, plus the
**current latest line** (26.x) for new adopters. Re-check the download numbers
when deciding scope; popularity, not recency, drives which versions we ship.

This explicitly means **older versions are in scope** — do not skip them. The
render API drifts **incrementally**; treat it as a gradient, not two clean
buckets. Always confirm against the target's decompiled source (Step 4). Known
inflection points:
- **≤1.20.x (1.20–1.20.4):** `VertexConsumer` + `MatrixStack.Entry`, but a
  trailing **`.next()`**, `.normal(matrices.getNormalMatrix(), …)`,
  `.color(int,int,int,int)`. No `getHandPos`: `FishingBobberEntityRenderer.render`
  computes both origin branches inline, so strategy A's target is the first-person
  branch's own last expression, the method's single `Vec3d.rotateX(F)`, whose value
  is relative to the lerped **feet** (see the table's 1.20–1.20.1 column). v0.5 used a
  name-only `@Inject` selector and an access widener for `GameRenderer.getFov`; v0.6
  spells every descriptor out and replaces the AW with `@Accessor`/`@Invoker`s.
  `MinecraftClient` has no `getRenderTickCounter()` and `Camera` no
  `getLastTickDelta()`; the camera quaternion's +X is **left** here, unlike 1.21+.
- **1.21.0+ (and 26.x):** the world-space line origin comes from a hand-position
  method (`getHandPos` on 1.21.x, `getPlayerHandPos` on 26.x) with a single
  first-person `add(Vec3)` — so the **preferred** fix hooks that result with
  `@ModifyExpressionValue` (strategy A) on every 1.21.x target, not just 1.21.11.
  `renderFishingLine` still exists on 1.21.x (no `.next()`, `.normal(matrices, …)`,
  `.color(int)`; full descriptor `…FF)V` up to 1.21.10; plus `.lineWidth(...)` and
  `…FFF)V` on 1.21.11; the line moved to a deferred render-command `submit` in 1.21.9,
  verified on 1.21.9–1.21.11, while 1.21.5 still draws directly), but cancelling it drops other mods' line
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
from 1.21.9, emit the line through a deferred render-command `submit` (1.21.0–1.21.8
draw it directly in `render`; verified on 1.21.4, 1.21.5 and 1.21.6–1.21.8). The method has a first-person
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
extracts the local player's hook again from the same frame's state). Check a panorama
capture before reusing it: where the capture renders the world without passing the frame
counter (1.21.9–1.21.11's `takePanorama` calls `renderWorld` directly), its faces would reuse
the normal frame's origin.

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
- **No Fabric API.** The mod uses nothing from Fabric API. Do not add it to
  `build.gradle` or `fabric.mod.json`; if the base branch still has `fabric_version` /
  `fabric_api_version` and the `fabric-api` dependency, remove them — keeping it off
  the classpath makes compile and `runClient` prove the mod runs without it.
- **MixinExtras: loader's copy or bundled?** It is the mod's only mixin extra, and
  Fabric Loader has shipped a copy since **0.15.0**. Take the loader's copy when the
  branch's floor is already at or above the loader that bundles a new enough one
  (`>=0.15.7`, the first with `injector.v2.WrapWithCondition`); **bundle it**
  (`include "io.github.llamalad7:mixinextras-fabric:<ver>"`, Loom's Jar-in-Jar) only
  where that actually lowers the floor — i.e. on a branch whose other metadata would
  otherwise let it run on a pre-0.15.7 loader. Bundling is not free and is not a
  default: the nested `mixinextras-fabric` is a full mod (id `mixinextras`) whose own
  `depends.fabricloader >=0.14.25` is enforced, so it sets a floor of its own, and on
  any loader with a newer copy the resolver picks the loader's and the nested one is
  dropped (verified, 1.20 on loader 0.19.5). See Step 3 and the table's
  "Loader floor" row.
- **Loom plugin version + recommended values (cross-check):**
  `https://fabricmc.net/develop` — the page renders the exact recommended
  `gradle.properties` block and the `fabric-loom` plugin version for a selected
  MC version. Newer MC ⇒ newer Loom (the table's "Loom / Java" row lists what was
  observed). Use the Loom version the develop page recommends, unless it needs a
  newer JDK for Gradle than the branch's daemon JDK (`gradle/gradle-daemon-jvm.properties`,
  Step 3; a Loom version's Gradle module metadata names it as `org.gradle.jvm.version`):
  then the newest Loom that runs on that JDK (Loom 1.18 needs 25, so pre-26 branches on
  21 stay on 1.17; see the table).
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
  the Loom variant (the remapping `net.fabricmc.fabric-loom-remap` for yarn targets,
  the develop page's id (older branches use the legacy `fabric-loom` id, v0.5's 1.21.11
  with Loom 1.14 among them); the non-remap `net.fabricmc.fabric-loom` for
  unobfuscated ones). Set `it.options.release` /
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
  for single-build ranges) and `depends.java` to match. **`depends.fabricloader` is
  the floor the rest of the metadata actually needs** — never the dev loader version,
  and never "whatever bundles MixinExtras" reflexively: both lock out older launchers
  for no reason. Derive it as the highest of
  (a) the mixin config's `compatibilityLevel`: an unknown constant is a hard
  `MixinInitialisationError` at startup (`MixinConfig.initCompatibilityLevel`), so
  `JAVA_21` needs sponge-mixin 0.13+ = loader **`>=0.15.10`** and `JAVA_25` needs
  sponge-mixin 0.16+ = loader **`>=0.17.0`**, while `JAVA_17` is in every fork from
  0.12.0 on and costs nothing; a level merely *above* the running service's maximum
  is only a WARN, so this is the enum, not the cap;
  (b) MixinExtras: **`>=0.15.7`** when taking the loader's copy (0.15.0 is the first
  loader that bundles one, but 0.15.0–0.15.6 bundle 0.3.0–0.3.3, which have no
  `injector.v2`), or **`>=0.14.25`** when bundling it, which is the nested
  `mixinextras-fabric`'s own declared floor;
  (c) **`>=0.12.0`**, the loader from which `depends`/`breaks` are enforced properly (`breaks` is
  parsed earlier, just not honoured).
  One thing the floor is *not* free of: it also selects the **Fabric Mixin behaviour set** applied
  to this mod's config. `FabricMixinBootstrap$MixinConfigDecorator` takes the minimum of the mod's
  `fabricloader` interval and walks a table — in loader 0.19.5, `>=0.19.4` → 0.17.4, `>=0.19.0` →
  0.17.1, `>=0.18.4` → 0.17.0, `>=0.17.3` → 0.16.5, `>=0.16.0` → 0.14.0, `>=0.12.0` → 0.10.0,
  below that → 0.9.2 (older loaders carry fewer rows; the class is only called
  `FabricMixinVersions` from 0.19.x). Below Fabric compat 0.17.1 injectors are prepared through
  `INJECT_PREPARE_LEGACY`, and 0.17.0/0.10.0 gate `LocalVariableDiscriminator` and `BeforeLoadLocal`
  behaviour. **Any floor from `>=0.12.0` up to just under `>=0.16.0` lands in the same 0.10.0
  bucket**, so on the 1.20 and 1.21.x branches moving the number inside that range costs nothing
  (1.20's `>=0.15.7` → `>=0.14.25` did not move it); on a 26.x branch, where the floor sits above
  `>=0.16.0`, lowering it *does* change behaviour and a green smoke test is not enough to clear it.
  Then **check the number against reality**: the oldest loader a popular pack for that
  MC line actually ships — Fabulously Optimized's
  `Packwiz/<mc_version>/pack.toml`, key `[versions] fabric`. Observed 2026-09-24: **1.20 0.14.21**,
  **1.20.1 0.14.23**; **1.21 0.15.11**, 1.21.1 0.19.3, 1.21.2 0.16.7, 1.21.3 0.16.10,
  1.21.4/1.21.5 0.19.3, 1.21.6/1.21.7 0.16.14, 1.21.8 0.19.3, 1.21.9 0.17.2,
  1.21.10/1.21.11 0.19.3; **26.1 0.18.5**, **26.1.1 0.18.6**, 26.1.2/26.2/26.3 0.19.5. A pack for an
  older point release on the same line routinely pins an *older* loader than the newest one does, so
  check every MC version the branch's `depends.minecraft` range covers, not just its newest — the
  1.20 branch covers MC 1.20 (0.14.21, not 1.20.1's 0.14.23) and the 1.21.1 branch covers MC 1.21
  (0.15.11, the lowest on the whole 1.21 line). Then smoke-test the built jar on exactly that loader
  (Step 7b). If the derived floor is above what the pack ships, say so and stop:
  closing that gap means a real trade-off (an older MixinExtras with only v1
  `WrapWithCondition`, or shading a relocated copy), not a metadata tweak.
  No `fabric-api` dependency, no
  entrypoint (the mod has no initializer: delete `FishingRodFixClient.java` and the
  `entrypoints` block if the base branch still has them), `"environment": "client"`.
  Keep `"breaks": { "enchanted_fishing_line": "*" }` on every branch (user decision
  2026-09-22): Enchanted Fishing Line ships a copy of the v0.5 correction that stacks on
  the current one (see the compatibility matrix); the entry is harmless where it has no
  build.

### Step 4 — Adapt the mixin to the target's REAL API (the hard part)
Mappings and signatures drift between versions. Do not text-replace blindly.
Combine two sources: the **reference logic** comes from the `<default_branch>`
reference mixin; the **API shape** (injection point, signature, vertex-emit style,
names) comes from the target's own decompiled source — and for a legacy target,
the existing legacy-family branch's mixin (`1.20`, `1.19`, …) is your structural
template for its older `.next()`-style API.
1. Run `./gradlew genSources` so the decompiled Minecraft sources for the target
   version are available. (Loom 1.14+ caches decompiled `.java` by hash under
   `~/.gradle/caches/fabric-loom/decompile/`; the readable per-version sources land in
   `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-{clientonly,common}/<key>/*-sources.jar`
   — if no sources jar is produced, extract or grep the decompile cache instead —
   and exact descriptors come from `javap` on the mapped jar under
   `~/.gradle/caches/fabric-loom/<ver>/.../*-unpicked.jar`.) Loom asks its decompiler
   worker for a 4 GB heap; on a machine whose commit charge is already high the worker
   JVM dies before it starts (`insufficient memory … G1 virtual space`, exit code 1, no
   stack). Lower it for that one run with an init script rather than editing the build
   file: `gradle.projectsEvaluated { rootProject.loom.decompilerOptions.each { it.memory.set(1536L); it.maxThreads.set(4) } }`
   passed with `-I`. (`memory` is a `Property<Long>` in MB.) The reviewer skill's
   `vanilla-sources.sh` takes `FRF_DECOMPILE_XMX` for the same reason.
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
       after the camera update; on pre-26 targets the counter (`render` HEAD, or past
       its frame-skip check: pitfall 11) runs before it, so take the camera-dependent
       samples after the camera update
       (1.21.11 as ported: `renderWorld` HEAD, which runs only in frames that can
       draw the hand; 1.21.4, 1.21.5 and 1.21.9–1.21.10, which update the camera inside
       `renderWorld`: right after its `Camera.update` call; reset the samples to "gate closed" at the
       counter so a frame that doesn't sample proves nothing) and keep the latch at
       the counter.
     - the swing: progress, which hand, and the animation type (whack vs stab/none).
     - the view bob and hurt tilt methods both passes call, and the world pass's
       nausea/portal warp (its inputs and formula).
     - for the body-held rod (third person): the call where the held-item layer
       submits a held item (its pose, the render state, the arm, the stack, the
       collector), the player's entity id on its render state (to find the entity),
       the owner's hook field on the client, the hook renderer's submit (its pose and
       the collector), `submit`'s offset locals (their float ordinals; the line reads
       them, not the state, once they are stored), the line's 0.25 lift, the owner's body
       yaw as drawn (`solveBodyRot`, private → mirror it) and lerped position; and
       which collector each pass of a frame submits into (level, hand, GUI entity
       pictures, other mods' passes) and where it is cleared: if one storage serves
       several passes, the pass tag needs its clear count (26.1.x, see the table),
       and where a mod can give each entity its own buffer source (Iris with a pack,
       on every immediate-mode target) it also needs the call that opens the world's
       entity loop, so a drawing can be tagged as inside it (see the pass tag below).
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
     (collector + frame; valid while a collector serves one pass per frame, else add
     the storage's clear count). Where a mod can hand each entity its own buffer
     source — Iris with a shader pack does, on every immediate-mode target — the
     collector alone loses every rod-to-hook pair, so the tag also carries **whether
     the drawing was inside the world's entity loop**, recorded at the loop's own
     call before its first entity, and two drawings match on
     `same frame && same pass-end count && (same collector || both inside the loop)`.
     Recording it at draw time is what keeps Iris' shadow pass out: it never goes
     through that loop and runs before the record is taken, so its drawings note
     themselves as outside and keep pairing by collector, as they did before. That
     call runs once per non-empty held item of an armed entity (vanilla returns
     before it for an empty stack): gate it on "a body-held hook was extracted
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
targets, makes Loom remap the mixins' targets to intermediary: Loom 1.17 rewrites the
annotation strings in the jar itself, so the jar has no refmap; older Loom generated
one; unobfuscated targets need neither). Fix
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

### Step 7b — Production smoke test at the declared loader floor
`runClient` proves nothing about `depends.fabricloader`: dev mode runs the loader
from `gradle.properties`, which is always the newest one, and Loom puts the mod on
the classpath rather than resolving its metadata the way a launcher does. A floor
that is wrong — too high, so a pack refuses to start; or too low, so the mod is
admitted onto a loader whose Mixin or MixinExtras cannot apply it — is invisible
until a user hits it. So run the **built jar** twice more, outside Gradle:

1. On **exactly the loader the branch declares** as its floor,
2. on a **current** loader, to prove the normal path still works, and
3. on a branch that **bundles** MixinExtras, on the loaders where the selection flips — the newest
   loader whose own copy is *older* than the pin (ours must win there) and the oldest whose copy is
   *newer* (the loader's must win and ours be dropped). On 1.20 that is 0.15.0 and 0.16.0.
4. on a branch that **shades** MixinExtras, on a loader old enough to have no copy of its own *and*
   on one that has one, so both the alone case and the two-copies case are seen. On 1.20 that is
   0.14.21 and 0.19.5.

Assemble each client by hand in a scratch directory — never the user's `run/`:
the vanilla client jar and asset objects come from Loom's cache
(`~/.gradle/caches/fabric-loom/<mc>/minecraft-client.jar`,
`~/.gradle/caches/fabric-loom/assets/`), the Mojang libraries from that version's
`mojang_minecraft_info.json`, and the loader's own libraries plus `mainClass` from
`https://meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/profile/json`. Launch
`net.fabricmc.loader.impl.launch.knot.KnotClient` with `--gameDir` pointing at the
scratch dir (the jar in its `mods/`), `--assetsDir`/`--assetIndex`, `--accessToken 0`,
and `-Dmixin.debug=true -Dmixin.debug.countInjections=true`; `cd` into the game dir
before launching. Pass when the log shows the mod in the `Loading N mods:` list, a
`Mixing <Name> from fishingrodfix.client.mixins.json` line for every mixin the config
lists, and the menu reached. Where MixinExtras is bundled or shaded, read *which* copy won
rather than merely that one is present: the tree under `fishingrodfix` in that list, and the
`Initializing MixinExtras via … MixinExtrasServiceImpl(version=…)` line, must name the
pinned version wherever the loader's own copy is older. For a shaded copy the line naming the
**relocated** class is the proof that the bootstrap in the mixin config plugin ran — but only
where ours is the newest copy present: `shouldReplace` is strictly-greater, so at equal
versions the copy that bootstraps second concedes and the line may legitimately name the
loader's class. What must always hold is that the line appears at all and that every mixin
applies. Neither failure is loud: a shaded copy that never registers leaves its injectors as
non-injectors and the handlers vanish without a word, and a bootstrap that *throws* is caught
by `MixinProcessor.selectConfigs`, which drops the whole config with one
`Failed to select mixin config` WARN — so check the `Mixing` count, never the absence of an
error. A `Compatibility level JAVA_xx
… is higher than the maximum level supported by this version of mixin` WARN is benign
(Mixin caps and continues); the fatal one is `MixinInitialisationError: … specifies
compatibility level … which is not recognised`.

**Kill each client the moment its evidence is complete.** Poll the log on a ~1 s tick and
`taskkill /F /T /PID <pid>` as soon as the `Preparing … (N)` count is matched by N `Mixing`
lines or a failure marker appears — don't wait for the title screen, don't leave it up while
writing anything, and put the kill on a `finally` path so a slip in the polling still stops it.
Cap every launch at ~120 s and treat an expiry as a failure to read from the log. A client
reaches the menu here in well under a minute; anything longer is a leaked window the user has to
close. Never kill a PID you did not start — the user's own dev client and Gradle daemons match
the same image name.

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
  `>=1.20 <=1.20.4` covering 1.20–1.20.4, `>=26.1 <=26.1.2` covering 26.1–26.1.2,
  `>=1.21.9 <=1.21.10` covering 1.21.9–1.21.10, `>=1.21.6 <=1.21.8` covering 1.21.6–1.21.8,
  `>=1.21.4 <=1.21.4` and `>=1.21.5 <=1.21.5` for the single-version lines, `>=1.21 <=1.21.1`
  covering 1.21–1.21.1 — closed 2026-09-23, the last open bound in the project).
  If they differ, they need separate branches/builds.
- Unobfuscated targets (26.1+) allow a stronger check than descriptors: fetch each
  version's client jar from Mojang's version manifest and diff the jars entry by entry
  (SHA-1 per class). If no class the mod references, none of their supertypes and none
  of the rod assets differ, the whole API is identical by construction. 26.1 → 26.1.2
  (checked 2026-09-22): only `SharedConstants`, `DetectedVersion`, `Checkbox` (+ inner
  classes), `AbstractReportScreen` (+ inner), `PlayerEntry` and
  `ServerGamePacketListenerImpl` changed, plus `version.json` and structure NBTs; same
  107 libraries, Java 25 and launcher JVM arguments.
- Yarn targets allow the same check in intermediary names, which are stable across
  versions: point a scratch copy of the project at the other version so Loom builds its
  jars, then diff the `minecraft-clientonly-intermediary` and `minecraft-common-intermediary`
  jars under `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/` entry by entry
  (map changed `class_NNNN` names to yarn with the branch's `mappings.tiny`; for a changed
  class the mod uses, `javap -c -p` both and diff method by method), and compare the two
  version JSONs (`mojang_minecraft_info.json`: libraries, `javaVersion`, JVM arguments).
  1.21.9 → 1.21.10 (checked 2026-09-22): the client-only jar is identical; 48 common classes
  changed (`Entity` only in `move` and its block-collision helpers, some blocks and entities,
  `MinecraftServer`, `SharedConstants`, `MinecraftVersion`), plus structure NBTs and
  `version.json`; same 115 libraries, Java 21 and JVM arguments. The yarn builds differ
  (`1.21.9+build.1`, `1.21.10+build.3`), which doesn't matter at runtime: the jar is remapped
  to intermediary. 1.21 → 1.21.1 (checked 2026-09-23, the same way): the client-only jar changes
  exactly one class, `ReporterEnvironment`, and `version.json`; the common jar changes 29 classes
  (`SharedConstants`, `MinecraftVersion`, the command argument types and `EntitySelectorReader`, the
  `Score`/`Selector`/`EntityNbt` text sources, `BlockEntity`, `WorldChunk`, and the chiseled
  bookshelf, lectern, sculk sensor and shulker box blocks) plus structure NBTs — none of them a class
  the mod mixes into, reads, or inherits from. Same 97 libraries except brigadier (1.2.9 → 1.3.10),
  same Java 21 and the same JVM and game arguments; `handheld_rod.json`, `fishing_rod_cast.png` and
  `rendertype_item_entity_translucent_cull.fsh` byte-identical. If the Loom cache already holds both
  versions (a branch built against each), no scratch project is needed: diff the two
  `minecraftMaven` intermediary jars directly.
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
   **Which injectors are optional is a per-family judgement, not a fixed list.** On
   1.21.6–1.21.8 the line-hiding `@WrapWithCondition` is `require = 0` too: there it wraps
   `FishingBobberEntityRenderer.renderFishingLine`, a fishing-specific private method and the
   natural target for a mod that redraws the line, where the deferred versions put the same
   condition on a generic `submitCustom`/`submitCustomGeometry`. Line hiding is not what the fix is
   for and degrades cleanly to a visible line, so a conflict there must not be a startup crash for
   everyone running both mods; `countInjections` still catches a stale descriptor. Apply the same
   reasoning wherever an injection point is fishing-specific and the feature behind it is optional.
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
    Closed 2026-09-23 on `1.21.11` (was `~1.21.11`), `26.2` (`~26.2`), `26.3`
    (`~26.3`), `1.21.4` (was an unbounded `>=1.21`) and `1.21.1` (was
    `>=1.21 <1.21.2`, now `>=1.21 <=1.21.1`). No branch carries an open bound any more.
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
      entities relative to the counter;
    - count only frames that render: Dynamic FPS skips rendering inside the render
      method while it throttles (on 1.21.11 an MEV on `GameRenderer.render`'s
      `skipGameRender` read, 15 loop iterations a second, most of them skipped), so a
      counter placed before that check counts skipped frames as frames without a hand
      and leaves vanilla's line; put it past the check (1.21.11: at `render`'s
      `updateCamera` call; 1.21.9–1.21.10, which have no `updateCamera`: at `render`'s
      `GlobalSettings.set` call, the first one in the `!skipGameRender` block; 1.21.4 and 1.21.5, which have
      neither `updateCamera` nor `GlobalSettings`: at `render`'s **only**
      `MinecraftClient.isFinishedLoading` call, which is the second *call* in that block, after
      `Profilers.get()`; 1.21–1.21.1: the same **only** `isFinishedLoading` call, which is the
      *first* call in that block there, since the profiler is reached through
      `client.getProfiler()` later — so no `ordinal` belongs on it on any of them;
      1.20–1.20.1, where `render(FJZ)V` has no `isFinishedLoading` call at all on
      1.20.1 (1.20.4 has one): at its only `Mouse.getX()D` INVOKE, the first call
      inside the `!skipGameRender` block, which is an early `return` there rather
      than a wrapping `if`) and check where
      the target's skip sits relative to it.
12. **Mixin priority at `@At("HEAD")` works the same way as anywhere else**
    — worth knowing why, because it looks as if it shouldn't.
    `MixinApplicatorStandard` runs its passes over *all* the mixins of a target
    class in turn (`MAIN`, then `INJECT_PREPARE` — or `INJECT_PREPARE_LEGACY`
    for a mixin below Fabric compat 0.17.1, still before the next pass — then
    `INJECT_APPLY`), so every
    injector resolves its target instruction — for a head, the original first
    instruction — before any of them inserts a thing. `INJECT_APPLY` then goes in
    ascending priority and each callback is a `Target.insertBefore(node, …)` on
    that same anchor, so a callback applied later lands *nearer* the anchor and
    runs *later*. A high priority therefore gives the last word at a head as it
    does at a return, and one mixin class can carry both (the immediate-mode
    branches, 1.20–1.21.8: `WorldRendererMixin` at 1500, the bobbers-last order at
    its head and the pass end at its return). Don't "fix" this by splitting the class and dropping the
    priority — that inverts the very thing it was meant to guarantee. What
    priority does *not* outrank is the injector's `order`: `INJECT_APPLY`'s outer
    loop is over `@Inject(order = )` (default 1000) and only its inner loop over
    mixin priority, so a mod's `order = 1100` still lands after a priority-1500
    callback. Claim "after other mods' injects" only for the default order.

## Known per-version API deltas (OBSERVED — verify against the target source, not authoritative)
Extend this as you learn more; never trust it over the decompiled source.

| Concern | 1.20–1.20.1 | 1.20.2–1.20.4 (v0.5 era, mostly unverified) | 1.21–1.21.1 | 1.21.4 | 1.21.5 | 1.21.6–1.21.8 | 1.21.9–1.21.10 | 1.21.11 | 26.x |
|---|---|---| --- |---|---|---|---|---|---|
| Injection point (current fix) | as ported: **no `getHandPos`** — `render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V` (full descriptor: a bridge `render(Entity, …)` exists) computes both origin branches inline and draws the catenary, so everything lives there: MEV (`allow = 1`) on the method's **only** `Vec3d.rotateX(F)`, the first-person branch's last step; `@Inject` HEAD (reset); `@Inject` at the only `FishingBobberEntity.getX()D` INVOKE — the first instruction past both branches, unambiguous because the owner's is `PlayerEntity.getX` — (decide); `@ModifyVariable` STORE on float **ordinals 6/7/8** (`v`/`w`/`x`, LVT slots 38–40; the method's float locals in slot order are the two args `f`/`g`, the swing pair `h`/`k`, the body yaw `l`, the eye-height term `r`, then these); `@WrapWithCondition` on its only `renderFishingLine`. The read sits at `HeldItemFeatureRenderer.renderItem(LivingEntity, ItemStack, ModelTransformationMode, Arm, MatrixStack, VertexConsumerProvider, I)`'s only `HeldItemRenderer.renderItem(LivingEntity, …)V` INVOKE, as on 1.21–1.21.1 | `renderFishingLine` HEAD, cancel | as ported: `getHandPos(L…PlayerEntity;FF)L…Vec3d;` MEV on its only `Vec3d.add(Vec3d)` (third-person branch first in the bytecode, as everywhere). **No render state and no deferred submit**: one `render(FishingBobberEntity;FFL…MatrixStack;L…VertexConsumerProvider;I)V` (full descriptor: a bridge `render(Entity, …)` exists) asks `getHandPos` and draws the catenary, so everything else lives there — `@Inject` HEAD (reset), `@Inject` at its only `getHandPos` INVOKE with `shift = AFTER` (decide), `@ModifyVariable` STORE on float **ordinals 4/5/6** (`k`/`l`/`m`, LVT slots 14–16; the method's five float stores are the swing pair `h`/`j` and then these, after the two float args `f`/`g`), `@WrapWithCondition` on its only `renderFishingLine`. The read sits at `HeldItemFeatureRenderer.renderItem(LivingEntity, ItemStack, ModelTransformationMode, Arm, MatrixStack, VertexConsumerProvider, I)`'s only `HeldItemRenderer.renderItem(LivingEntity, …)V` INVOKE | as ported: `getHandPos(L…PlayerEntity;FF)L…Vec3d;` MEV on its only `Vec3d.add(Vec3d)` (the bytecode has the third-person branch first, as everywhere); nothing is deferred, so the body-held rod goes into `FishingBobberEntityRenderer.render(FishingBobberEntityState, MatrixStack, VertexConsumerProvider, I)` (full descriptor: a bridge `render(EntityRenderState, …)` exists) — HEAD + `@ModifyVariable` STORE on float ordinals 0/1/2 (`f`/`g`/`h`, LVT slots 7–9; the only float stores in the method) — and the read sits at `HeldItemFeatureRenderer.renderItem(ArmedEntityRenderState, ItemRenderState, Arm, MatrixStack, VertexConsumerProvider, I)`'s `ItemRenderState.render(MatrixStack, VertexConsumerProvider, II)V` INVOKE. Every one of these is byte-for-byte the 1.21.5 target (verified by javap) | as ported: `getHandPos(L…PlayerEntity;FF)L…Vec3d;` MEV on its only `Vec3d.add(Vec3d)` (the bytecode has the third-person branch first, as everywhere); nothing is deferred, so the body-held rod goes into `FishingBobberEntityRenderer.render(FishingBobberEntityState, MatrixStack, VertexConsumerProvider, I)` (full descriptor: a bridge `render(EntityRenderState, …)` exists) — HEAD + `@ModifyVariable` STORE on float ordinals 0/1/2 (`f`/`g`/`h`, LVT slots 7–9; the only float stores in the method) — and the read sits at `HeldItemFeatureRenderer.renderItem(ArmedEntityRenderState, ItemRenderState, Arm, MatrixStack, VertexConsumerProvider, I)`'s `ItemRenderState.render(MatrixStack, VertexConsumerProvider, II)V` INVOKE. All identical to 1.21.6–1.21.8 (those classes decompile byte for byte the same) | as ported: `getHandPos(L…PlayerEntity;FF)L…Vec3d;` MEV on its only `Vec3d.add(Vec3d)` (the bytecode has the third-person branch first, as everywhere); nothing is deferred here, so the body-held rod goes into `FishingBobberEntityRenderer.render(FishingBobberEntityState, MatrixStack, VertexConsumerProvider, I)` (full descriptor: a bridge `render(EntityRenderState, …)` exists) — HEAD + `@ModifyVariable` STORE on float ordinals 0/1/2 (`f`/`g`/`h`, LVT slots 7–9; the only float stores in the method) — and the read sits at `HeldItemFeatureRenderer.renderItem(ArmedEntityRenderState, ItemRenderState, Arm, MatrixStack, VertexConsumerProvider, I)`'s `ItemRenderState.render(MatrixStack, VertexConsumerProvider, II)V` INVOKE (no `ItemStack` argument, as 1.21.9–1.21.10) | as ported: as 1.21.11 (`getHandPos(L…PlayerEntity;FF)L…Vec3d;` MEV on its only `Vec3d.add(Vec3d)`, third-person branch first in the bytecode; `FishingBobberEntityRenderer.render(FishingBobberEntityState, MatrixStack, OrderedRenderCommandQueue, CameraRenderState)` HEAD + `@ModifyVariable` STORE on float ordinals 0/1/2 (`f`/`g`/`h`, LVT slots 5–7; no line-width local)), except the held-item call: `HeldItemFeatureRenderer.renderItem(ArmedEntityRenderState, ItemRenderState, Arm, MatrixStack, OrderedRenderCommandQueue, I)` gets no `ItemStack` (it came in 1.21.11), so the read at its `ItemRenderState.render(MatrixStack, OrderedRenderCommandQueue, III)V` INVOKE gates on the entity ids of this frame's body-held hook owners (an `IntOpenHashSet` filled at `updateRenderState` TAIL; without it every drawn player's and mannequin's item, `PlayerEntityRenderState` since 1.21.9's mannequins, went to `getEntityById`: over the per-hook budget on a crowded server) and recognises the rod out of line by the entity's live `getStackInArm(arm)` (the item state is extracted from it in the same frame) | as ported: `getHandPos(L…PlayerEntity;FF)L…Vec3d;` `@ModifyExpressionValue` on its only `Vec3d.add(Vec3d)` (the bytecode has the third-person branch first, as on 26.x); body-held rod: `FishingBobberEntityRenderer.render(FishingBobberEntityState, MatrixStack, OrderedRenderCommandQueue, CameraRenderState)` HEAD + `@ModifyVariable` STORE on float ordinals 0/1/2 (`f`/`g`/`h`, LVT slots 5–7; slot 8 `i` is the line width), and `HeldItemFeatureRenderer.renderItem(ArmedEntityRenderState, ItemRenderState, ItemStack, Arm, MatrixStack, OrderedRenderCommandQueue, I)` at its `ItemRenderState.render(MatrixStack, OrderedRenderCommandQueue, III)V` INVOKE (`PlayerHeldItemFeatureRenderer` overrides it and calls super) | `getPlayerHandPos`: `@ModifyExpressionValue` on its only `Vec3.add(Vec3)` (first-person branch; 26.1.2, 26.2 and 26.3 bytecode have the third-person branch first); body-held rod: `FishingHookRenderer.submit` HEAD + `@ModifyVariable` STORE on float ordinals 0/1/2 (`xa`/`ya`/`za`, LVT slots 5–7; same on 26.1.2 and 26.2), and `ItemInHandLayer.submitArmWithItem` at its `ItemStackRenderState.submit` INVOKE |
| Hand FOV (rod) | `getFov(Camera, F, Z)`**`D`** (private → `@Invoker`), and `renderHand(MatrixStack, Camera, F)` computes it itself, with **one** `getFov` INVOKE in it and one in `renderWorld` (verified by javap). As ported: an optional MEV on `renderHand`'s. `renderHand` runs at the end of `renderWorld` after the entities, so it is last frame's value, with the same shared-factor reuse | `GameRenderer.getFov(camera, δ, false)` (private: AW or `@Invoker`; `(Lnet/minecraft/client/render/Camera;FZ)D`) | `getFov(Camera, F, Z)`**`D`** (a double here; 1.21.5 narrowed it to float), and `renderHand(Camera, F, Matrix4f)` computes it itself, with **one** `GameRenderer.getFov` INVOKE in it and one in `renderWorld` (verified by javap). As ported: an optional MEV on `renderHand`'s (ordinal 0). `renderHand` runs at the end of `renderWorld` after the entities, so it is last frame's value, with the same shared-factor reuse | as 1.21.5: `getFov(Camera, F, Z)F`, and **`renderWorld` has only one `getFov` call** — 1.21.4 computes the hand FOV inside `renderHand(Camera, F, Matrix4f)` (1.21.6 moved it up into `renderWorld`'s `GlobalSettings.set`). As ported: an optional MEV on `renderHand`'s only `getFov` INVOKE (ordinal 0), verified the only one by javap. `renderHand` runs at the end of `renderWorld` after the entities, so it is last frame's value, with the same shared-factor reuse. (1.21–1.21.1's `getFov` returns `double`; 1.21.4's already returns `float`, so the older column's `…FZ)D` does **not** apply here) | `getFov(Camera, F, Z)F`, but **`renderWorld` has only one `getFov` call**: 1.21.5 computes the hand FOV inside `renderHand(Camera, F, Matrix4f)` (1.21.6 moved it up into `renderWorld`'s `GlobalSettings.set`). As ported: an optional MEV on `renderHand`'s only `getFov` INVOKE (ordinal 0). Same timing as 1.21.6+: `renderHand` runs at the end of `renderWorld`, after the entities, so it is last frame's value, with the same shared-factor reuse | as 1.21.9–1.21.10 (`getFov(Camera, F, Z)F`, `renderWorld`'s second call captured at ordinal 1, the same shared-factor reuse) | as 1.21.11 (`getFov(Camera, F, Z)F`; `renderWorld`'s second call captured, the same shared-factor reuse) | same (`…FZ)F`); as ported: the value `renderWorld` gives the hand projection, captured by an optional MEV on its second `getFov` INVOKE (ordinal 1, `changingFov` false); it comes after the entity extraction, so last frame's value, used while the factor `getFov` applies to both FOVs (world FOV ÷ (options FOV × lerped `fovMultiplier`): the death and fluid factors, which the hand FOV shares, and a mod's zoom; `@Accessor`s for the multipliers) is unchanged within float noise, else `@Invoker` `getFov(camera, δ, false)`; comparing the world FOVs themselves would call it through every sprint/speed transition (~1 s each). Every `getFov` call runs `Camera.getSubmersionType` (uncached: `getProjection`, ~11 world lookups, ~1 KB): two invoker calls per frame broke the allocation budget | `cameraRenderState.hudFov` (`Camera.calculateHudFov`) |
| Hand-pass state (equip height, sway, scoping, rendered hands) | `HeldItemRenderer` is 1.21–1.21.1's field for field and branch for branch: `mainHand`/`offHand`/`equipProgress*`/`prevEquipProgress*` via `@Accessor`, the mirrored package-private `getHandRenderType`, no swap-animation scale, dip `1 − lerp(prevEquipProgress, equipProgress)`; the hand pass is `renderItem(F, MatrixStack, VertexConsumerProvider$Immediate, ClientPlayerEntity, I)`, called from `renderHand` at the end of `renderWorld`. `renderFirstPersonItem`'s map branch is `item.isOf(Items.FILLED_MAP)`, an item test, and its `isUsingSpyglass()` guard wraps the whole body | `HeldItemRenderer` fields (equip progress private → `@Accessor`; verify names) + player fields | as 1.21.4: `HeldItemRenderer` fields `mainHand`/`offHand`/`equipProgress*`/`prevEquipProgress*` via `@Accessor`, the mirrored package-private `getHandRenderType` (same body), no swap-animation scale, dip `1 − lerp(prevEquipProgress, equipProgress)`; the hand pass is `renderItem(F, MatrixStack, VertexConsumerProvider$Immediate, ClientPlayerEntity, I)`, called from `renderHand` at the end of `renderWorld` after the world's entities. `renderFirstPersonItem`'s map branch is `item.isOf(Items.FILLED_MAP)`, an item test, so a rod is never drawn as a map (1.21.4's `map_id` limitation doesn't apply) | as 1.21.5 apart from one yarn rename: `HeldItemRenderer` fields `mainHand`/`offHand`/`equipProgress*`/**`prevEquipProgress*`** (1.21.5: `lastEquipProgress*`) via `@Accessor`, the mirrored package-private `getHandRenderType` (same body), no swap-animation scale, dip `1 − lerp(prevEquipProgress, equipProgress)`; the hand pass is `renderItem(F, MatrixStack, VertexConsumerProvider$Immediate, ClientPlayerEntity, I)`, called from `renderHand` at the end of `renderWorld` after the world's entities | as 1.21.6–1.21.8 (`HeldItemRenderer` byte-identical to 1.21.8's: fields `mainHand`/`offHand`/`equipProgress*`/`lastEquipProgress*` via `@Accessor`, the mirrored package-private `getHandRenderType`, no swap-animation scale, dip `1 − lerp(lastEquipProgress, equipProgress)`); the hand pass is `renderItem(F, MatrixStack, VertexConsumerProvider$Immediate, ClientPlayerEntity, I)`, called from `renderHand` at the end of `renderWorld` after the world's entities | as 1.21.9–1.21.10 (the same `HeldItemRenderer` fields `mainHand`/`offHand`/`equipProgress*`/`lastEquipProgress*` via `@Accessor`, the mirrored package-private `getHandRenderType`, no swap-animation scale: `renderItem`'s dip is `1 − lerp(lastEquipProgress, equipProgress)`); the hand pass is `renderItem(F, MatrixStack, VertexConsumerProvider$Immediate, ClientPlayerEntity, I)`, called from `renderHand` at the end of `renderWorld` after the world's entities are drawn | as 1.21.11 (the same renderer fields, `@Accessor`s and mirrored `getHandRenderType`), but no swap-animation scale: `renderItem`'s dip is `1 − lerp(lastEquipProgress, equipProgress)` | as ported: the hand pass (`HeldItemRenderer.renderItem(F, MatrixStack, OrderedRenderCommandQueue, ClientPlayerEntity, I)`, from `GameRenderer.renderHand` at the end of `renderWorld`) reads the live player (sway, spyglass, item use, riptide, swing) and the renderer's `mainHand`/`offHand`/`equipProgressMainHand`/`lastEquipProgressMainHand`/`equipProgressOffHand`/`lastEquipProgressOffHand` (`@Accessor`; the renderer is `EntityRenderManager.getHeldItemRenderer()`), updated only in `updateHeldItems` (tick); swap scale `MinecraftClient.getItemModelManager().getSwapAnimationScale(item)` (the renderer's manager is the same instance); `getHandRenderType` is package-private with a package-private enum: mirrored | 26.1–26.2 `ItemInHandRenderer` fields (private → `@Accessor`); 26.3 `levelRenderState.playerRenderState.firstPersonHandsAndItems` + `.avatarRenderState` (public). 26.2 as ported: the hand pass (`submitHandsWithItems`, from `renderLevel` after extraction) reads the live player (sway, scoping, item use, riptide, swing) and the renderer's `mainHandItem`/`offHandItem`/`mainHandHeight`/`oMainHandHeight`/`offHandHeight`/`oOffHandHeight` (`@Accessor`); nothing ticks between extract and render, so the same reads at extraction match. Swap scale: `Minecraft.getItemModelResolver().swapAnimationScale(item)` (the renderer's resolver is the same instance). `evaluateWhichHandsToRender` is package-private and returns a package-private enum: no `@Invoker` (Mixin resolves an invoker by its own descriptor), so it is mirrored. 26.1.2 as ported: the same reads as 26.2, the pass being `renderHandsWithItems` (from `GameRenderer.renderItemInHand` at the end of `renderLevel`); its body and constants match 26.2's except a trailing `renderAllFeatures()`/`endBatch()` (a `SubmitNodeStorage.clear()`) |
| HUD hidden (F1) + hand gate | `options.hudHidden`; `renderHand(MatrixStack, Camera, F)` with `!renderingPanorama` (the whole body), first person, the camera entity not `isSleeping()`, `!options.hudHidden`, `interactionManager.getCurrentGameMode() != SPECTATOR` — and `renderWorld` only calls it `if (this.renderHand)` (a private field with a public `setRenderHand`; the latch handles that). Detached: `camera.isThirdPerson()`; eye `Camera.cameraY`/`lastCameraY` (`@Accessor`) + `prevX/Y/Z`. **`Camera` has no `getLastTickDelta()` here**, so that lerp uses the frame's own progress (see Tick delta). No `updateCamera`: `renderWorld` calls `Camera.update(BlockView, Entity, ZZF)` itself (once), so as ported the gate is sampled right after that INVOKE (`shift = AFTER`), which is after the frame counter. `MinecraftClient.takePanorama` calls `renderWorld(1.0F, 0L, new MatrixStack())` directly; `renderWithZoom` does too, and nothing in vanilla calls it | `options.hudHidden` (public, toggled between frames); `GameRenderer.renderHand(MatrixStack, Camera, F)` also needs not `renderingPanorama` (early return), first person, a camera entity not `isSleeping()`, game mode not spectator; detached: `camera.isThirdPerson()`; eye: private `Camera.cameraY`/`lastCameraY` (`@Accessor`, smoothed in `updateEyeHeight`), lerped `prevX/Y/Z`, `Camera.update`'s own tick-delta argument; panorama via public `isRenderingPanorama()` | as 1.21.4: `options.hudHidden`; `renderHand(Camera, F, Matrix4f)` with `!renderingPanorama` (early return), first person, the camera entity not `isSleeping()`, `interactionManager.getCurrentGameMode() != SPECTATOR` — and `renderWorld` only calls it `if (this.renderHand)` (a private field with a public `setRenderHand`; the latch handles that). Detached: `camera.isThirdPerson()`; eye `Camera.cameraY`/`lastCameraY` (`@Accessor`) + `prevX/Y/Z` at `camera.getLastTickDelta()`. No `updateCamera`: `renderWorld` calls `Camera.update(BlockView, Entity, ZZF)` itself (once), so as ported the gate is sampled right after that INVOKE (`shift = AFTER`), which is after the frame counter. `MinecraftClient.takePanorama` calls `renderWorld(RenderTickCounter.ONE)` directly; `renderWithZoom` does too, with `RenderTickCounter.ZERO`, and nothing in vanilla calls it | as 1.21.5: `options.hudHidden`; `renderHand(Camera, F, Matrix4f)` with `!renderingPanorama` (early return), first person, the camera entity not `isSleeping()`, `interactionManager.getCurrentGameMode() != SPECTATOR` — and `renderWorld` only calls it `if (this.renderHand)` (a private field with a public `setRenderHand`; the latch handles that). Detached: `camera.isThirdPerson()`; eye `Camera.cameraY`/`lastCameraY` (`@Accessor`) + **`prevX/Y/Z`** (1.21.5: `lastX/Y/Z`) at **`camera.getLastTickDelta()`** (1.21.5: `getLastTickProgress()`). No `updateCamera`: `renderWorld` calls `Camera.update(BlockView, Entity, ZZF)` itself, so as ported the gate is sampled right after that INVOKE (`shift = AFTER`), which is after the frame counter. `MinecraftClient.takePanorama` calls `renderWorld(RenderTickCounter.ONE)` directly (no `render`, no frame of its own); `renderWithZoom` does too, with `RenderTickCounter.ZERO`, and nothing in vanilla calls it | `options.hudHidden`; `renderHand(Camera, F, Matrix4f)` with `!renderingPanorama` (early return), first person, the camera entity not `isSleeping()`, `interactionManager.getCurrentGameMode() != SPECTATOR` — and, unlike 1.21.6+, `renderWorld` only calls it `if (this.renderHand)` (a private field with a public `setRenderHand`, so a mod can suppress the pass; the latch handles that). Detached: `camera.isThirdPerson()`; eye `Camera.cameraY`/`lastCameraY` (`@Accessor`) + `lastX/Y/Z` at `camera.getLastTickProgress()`. No `updateCamera`: `renderWorld` calls `Camera.update(BlockView, Entity, ZZF)` itself, so as ported the gate is sampled right after that INVOKE (`shift = AFTER`), which is after the frame counter. `MinecraftClient.takePanorama` calls `renderWorld(RenderTickCounter.ONE)` directly (no `render`, no frame of its own); `renderWithZoom` does too, and nothing in vanilla calls it | as 1.21.9–1.21.10: `options.hudHidden`; `renderHand(F, boolean sleeping, Matrix4f)` with `!renderingPanorama`, first person, not sleeping, `interactionManager.getCurrentGameMode() != SPECTATOR`; detached `camera.isThirdPerson()`; eye `Camera.cameraY`/`lastCameraY` (`@Accessor`) + `lastX/Y/Z` at `camera.getLastTickProgress()`. No `updateCamera`: `renderWorld` calls `Camera.update(BlockView, Entity, ZZF)` itself, so the gate is sampled right after that INVOKE (`shift = AFTER`), which is after the frame counter | as 1.21.11 (`options.hudHidden`; `renderHand(F, boolean sleeping, Matrix4f)` with `!renderingPanorama`; spectator `interactionManager.getCurrentGameMode()`; eye `cameraY`/`lastCameraY`, `camera.getLastTickProgress()`), but no `updateCamera`: `renderWorld` updates the camera itself (`Camera.update(BlockView, Entity, ZZF)V`, after `updateCrosshairTarget`), so as ported the samples are taken right after that INVOKE (shift AFTER), not at `renderWorld` HEAD; panorama capture sets `setRenderingPanorama(true)` and calls `renderWorld` directly (no `render`, no frame of its own) | `options.hudHidden`; `renderHand(F, boolean sleeping, Matrix4f)`, same gate (`!isRenderingPanorama()`); detached: `camera.isThirdPerson()`; eye: as 1.21.5. Camera update after the counter on all pre-26 targets (`render` HEAD, or past its frame-skip check: pitfall 11). As ported: counter at `render`'s `updateCamera` call, samples at `renderWorld` HEAD (after `render`'s `updateCamera`, before the entities; reset to "gate closed" at each frame's start, so a frame without `renderWorld` proves nothing); eye partial tick `camera.getLastTickProgress()` (what `Camera.update` used); spectator `interactionManager.getCurrentGameMode()`; panorama capture (`MinecraftClient.takePanorama`) calls `updateCamera` + `renderWorld` directly, without `render`: no frame of its own, so check `isRenderingPanorama()` before reusing the per-frame origin | 26.1.2: `Options.hideGui` (copied to `OptionsRenderState.hideGui`, no `Hud`); 26.2–26.3: `Minecraft.gui.hud.isHidden()` (copied to `GuiRenderState.isHudHidden` after the level); `renderItemInHand` also needs `!isPanoramicMode`, `hasPlayer` (26.3), first person, `!entityRenderState.isSleeping`, `gameMode.getPlayerMode() != SPECTATOR`; detached: `Camera.isDetached()`; eye: private `Camera.eyeHeight`/`eyeHeightOld` (`@Accessor`); camera update before `extract`. 26.1.2 as ported: `HandPass` samples `options.hideGui` at `extract` HEAD; `extractOptions`, early in `extract` (after `extractWindow`, before the camera and the level), copies it for `renderItemInHand`, which reads `optionsRenderState.hideGui`, applies the bob before its gate and draws with `getSubmitNodeStorage()`; F1 toggles it in `handleKeybinds` during the tick |
| Frame counter / hand mark (`HandPass`) | **1.20.1's `GameRenderer.render(FJZ)V` has no `MinecraftClient.isFinishedLoading()` call at all** (1.20.4 does, once). As ported: the counter goes at the only `Mouse.getX()D` INVOKE in `render(FJZ)V` — the first call inside its `!skipGameRender` block, which is an early `return` here rather than a wrapping `if`. HEAD would count Dynamic FPS' skipped frames (pitfall 11). Mark at the two `renderFirstPersonItem(AbstractClientPlayerEntity, FF, Hand, F, ItemStack, F, MatrixStack, VertexConsumerProvider, I)` INVOKEs in the 5-arg `renderItem(F, MatrixStack, VertexConsumerProvider$Immediate, ClientPlayerEntity, I)`. All counts confirmed by javap | v0.5 didn't count frames. For a v0.6 port: **not HEAD** (pitfall 11); 1.20.4's `render(FJZ)V` has exactly one `MinecraftClient.isFinishedLoading()` **and** one `Mouse.getX()D` inside its `!skipGameRender` block (checked 2026-09-23 with javap), unlike 1.20.1 which has only the latter. Mark at the `renderFirstPersonItem` INVOKE inside `HeldItemRenderer.renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/network/ClientPlayerEntity;I)V` (the 5-arg overload) | as ported: counter at the only `MinecraftClient.isFinishedLoading()Z` INVOKE in `render(Lnet/minecraft/client/render/RenderTickCounter;Z)V` — here the **first** call in its `!skipGameRender` block (1.21.4/1.21.5 have a `Profilers.get()` before it; 1.21.1 uses `client.getProfiler()` later). HEAD would count Dynamic FPS' skipped frames (pitfall 11). Mark at the two `renderFirstPersonItem(AbstractClientPlayerEntity, FF, Hand, F, ItemStack, F, MatrixStack, VertexConsumerProvider, I)` INVOKEs in the 5-arg `renderItem(F, MatrixStack, VertexConsumerProvider$Immediate, ClientPlayerEntity, I)`. All counts confirmed by javap | as ported, exactly as 1.21.5: counter at the only `MinecraftClient.isFinishedLoading()Z` INVOKE in `render(Lnet/minecraft/client/render/RenderTickCounter;Z)V` — the second call in its `!skipGameRender` block, after `Profilers.get()` (no `GlobalSettings`, no `updateCamera` here either, and HEAD would count Dynamic FPS' skipped frames: pitfall 11). Mark at the two `renderFirstPersonItem(AbstractClientPlayerEntity, FF, Hand, F, ItemStack, F, MatrixStack, VertexConsumerProvider, I)` INVOKEs in the 5-arg `renderItem(F, MatrixStack, VertexConsumerProvider$Immediate, ClientPlayerEntity, I)`. All counts confirmed by javap | as ported: counter at the only `MinecraftClient.isFinishedLoading()Z` INVOKE in `render(Lnet/minecraft/client/render/RenderTickCounter;Z)V` — the second call in its `!skipGameRender` block, after `Profilers.get()` (there is no `GlobalSettings` and no `updateCamera` on 1.21.5, and HEAD would count Dynamic FPS' skipped frames: pitfall 11). Mark at the two `renderFirstPersonItem(AbstractClientPlayerEntity, FF, Hand, F, ItemStack, F, MatrixStack, VertexConsumerProvider, I)` INVOKEs in the 5-arg `renderItem(F, MatrixStack, VertexConsumerProvider$Immediate, ClientPlayerEntity, I)` | as ported: counter at the only `GlobalSettings.set(IIDJLnet/minecraft/client/render/RenderTickCounter;I)V` INVOKE in `render(Lnet/minecraft/client/render/RenderTickCounter;Z)V`, the first call in its `!skipGameRender` block (no `updateCamera` to hook); mark at the `renderFirstPersonItem(AbstractClientPlayerEntity, FF, Hand, F, ItemStack, F, MatrixStack, VertexConsumerProvider, I)` INVOKE inside the 5-arg `renderItem(F, MatrixStack, VertexConsumerProvider$Immediate, ClientPlayerEntity, I)` | as ported: counter at the only `GlobalSettings.set(IIDJLnet/minecraft/client/render/RenderTickCounter;I)V` INVOKE in `render(Lnet/minecraft/client/render/RenderTickCounter;Z)V`, the first call in its `!skipGameRender` block (no `updateCamera` to hook; Dynamic FPS' skip sits before it); mark as 1.21.11 (the two `renderFirstPersonItem` INVOKEs in the 5-arg `renderItem`, same descriptors) | as ported: counter at the only `updateCamera(RenderTickCounter)` INVOKE in `render(Lnet/minecraft/client/render/RenderTickCounter;Z)V`, inside its `!skipGameRender` block: not at HEAD, since Dynamic FPS skips frames there (an MEV on `render`'s `skipGameRender` read) and a counted frame without a hand pass leaves vanilla's line; mark at the two `renderFirstPersonItem(AbstractClientPlayerEntity, FF, Hand, F, ItemStack, F, MatrixStack, OrderedRenderCommandQueue, I)` INVOKEs in the 5-arg `renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V` | counter `GameRenderer.extract(Lnet/minecraft/client/DeltaTracker;Z)V` HEAD; mark at the per-hand call — 26.1.2: `renderArmWithItem` inside `ItemInHandRenderer.renderHandsWithItems`; 26.2: `submitArmWithItem` inside `ItemInHandRenderer.submitHandsWithItems` (both `(AbstractClientPlayer, FF, InteractionHand, F, ItemStack, F, PoseStack, SubmitNodeCollector, I)`); 26.3: `submitArmWithItem(PlayerRenderState, FirstPersonHandsAndItemsRenderState, FF, …)` inside `FirstPersonHandsAndItemsRenderer.submitHandsWithItems` |
| Owner's hook field (remembered rod hand) | same (`fishHook`) | yarn `PlayerEntity.fishHook` | same (`fishHook`) | same (`fishHook`) | same (`fishHook`) | same (`fishHook`) | same (`fishHook`) | same (set on the client by `FishingBobberEntity.setOwner`) | `Player.fishing` |
| Line hiding site | as ported: **no render state**. Reset at `render` HEAD, decide at the `@Inject` on the only `FishingBobberEntity.getX()D` INVOKE, carry it in a static field the same call's loop reads, and wrap the one `renderFishingLine(FFFLnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/util/math/MatrixStack$Entry;FF)V` INVOKE, which the loop reaches 17 times per drawn line. That method is `private static` (as on 1.21–1.21.1), so the `@WrapWithCondition` handler takes the seven arguments and no receiver. Line layer `RenderLayer.getLineStrip()` | hook entity `net.minecraft.entity.projectile.FishingBobberEntity` (owner `getPlayerOwner()`; line on the drawn rod: flagged by the origin hook's result (the strategy B `rotateX(F)` MEV here, the `getHandPos` MEV on 1.21–1.21.1), never from `getPerspective()`; line layer `RenderLayer.getLineStrip()`, also on 1.21.x until 1.21.11). No render state (drop `FishingHookRenderStateMixin`; reset the flag at `render` HEAD): decide inline in `render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V` (full descriptor: a bridge `render(Entity,…)` exists) and wrap its `renderFishingLine` calls; 1.21–1.21.1 the same | as ported: **no render state** (no `FishingBobberEntityStateMixin`). Reset at `render` HEAD, decide at an `@Inject` right after `render`'s only `getHandPos` INVOKE (`shift = AFTER`, which Mixin allows on a non-empty stack: `CallbackInjector` calls `target.extendStack()`), carry it in a static field the same call's loop reads, and wrap the one `renderFishingLine(FFFLnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/util/math/MatrixStack$Entry;FF)V` INVOKE, which the loop reaches 17 times per drawn line. Line layer `RenderLayer.getLineStrip()` | as ported, as 1.21.5: reset the flag at `updateRenderState(FishingBobberEntity, FishingBobberEntityState, F)V` HEAD, decide at its TAIL (full descriptor: a bridge exists), carry it on `FishingBobberEntityState`; the line is drawn straight into `RenderLayer.getLineStrip()`, so a `@WrapWithCondition` sits on `render`'s one `renderFishingLine(FFFLnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/util/math/MatrixStack$Entry;FF)V` INVOKE, which the loop reaches 17 times per drawn line. `updateRenderState` reads as an `if/else` for the null-owner case and javac emits a **return in that branch** (javap: returns at 25 and 85), and Mixin's `TAIL` is `BeforeFinalReturn` — the *last* RETURN only — so the TAIL does **not** run for an owner-less hook and the HEAD reset is what leaves it at vanilla's behaviour | as ported: reset the flag at `updateRenderState(FishingBobberEntity, FishingBobberEntityState, F)V` HEAD, decide at its TAIL (full descriptor: a bridge exists), carry it on `FishingBobberEntityState`; the line is drawn straight into `RenderLayer.getLineStrip()`, so a `@WrapWithCondition` sits on `render`'s one `renderFishingLine(FFFLnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/util/math/MatrixStack$Entry;FF)V` INVOKE, which the loop reaches 17 times per drawn line. `updateRenderState` looks like an `if/else` in the decompiled text but javac emits a **return in the null-owner branch**, and Mixin's `TAIL` is `BeforeFinalReturn` — the *last* RETURN only — so the TAIL does **not** run for a hook with no player owner and its `@Local` owner is never null there; the HEAD reset is what leaves such a hook at vanilla's behaviour (1.21.6+ has a source-level early return, so the outcome is the same). Check the bytecode, not the decompiled `if/else`, before claiming either | as ported: reset the flag at `updateRenderState(FishingBobberEntity, FishingBobberEntityState, F)V` HEAD, decide at its TAIL (full descriptor: a bridge exists), carry it on `FishingBobberEntityState`; the line is drawn straight into `RenderLayer.getLineStrip()`, so a `@WrapWithCondition` sits on `render`'s one `renderFishingLine(FFFLnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/util/math/MatrixStack$Entry;FF)V` INVOKE, which the loop reaches 17 times per drawn line | as 1.21.11, with the line layer `RenderLayer.getLines()` (a constant; 1.21.11 moved the layer factories from `RenderLayer` into `RenderLayers` as `lines()`, while 1.21.10's `RenderLayers` is only the block/item layer chooser): wrap `render`'s `submitCustom(MatrixStack, RenderLayer, Custom)` (two in `render`) filtered on it | as ported: reset the flag at `updateRenderState(FishingBobberEntity, FishingBobberEntityState, F)V` HEAD, decide at its TAIL (full descriptor: a bridge `updateRenderState(Entity, EntityRenderState, F)` exists); the line is a deferred `submitCustom(MatrixStack, RenderLayers.lines(), Custom)` in `render` whose lambda calls `renderFishingLine` without the state — wrap that `submitCustom` (an interface call on `OrderedRenderCommandQueue`, two in `render`), filtered on `lines()` (a constant); the state's line origin is `FishingBobberEntityState.pos` | hook entity `FishingHook`; reset the flag at `extractRenderState` HEAD, decide at its TAIL; wrap the `RenderTypes.lines()` `submitCustomGeometry` in `submit` |
| Swing state | as 1.21–1.21.1: `getHandSwingProgress(t)` + `preferredHand`, no swing animation type; the idle branch inlines the swing translate (there is no `swingArm` method), then `applyEquipOffset` and `applySwingOffset` (45/−20/−20/−80/−45 verified) | `getHandSwingProgress(t)` + `preferredHand`; no animation type (always whack) | as 1.21.4: `getHandSwingProgress(t)` + `preferredHand`, no swing animation type; the idle branch inlines the swing translate (there is no `swingArm` method), then `applyEquipOffset` and `applySwingOffset` (45/−20/−20/−80/−45 verified) | as 1.21.5: `getHandSwingProgress(t)` + `preferredHand`, no swing animation type; the idle branch always calls `swingArm(swing, equip, matrices, armX, arm)`, which translates by the swing, then `applyEquipOffset` and `applySwingOffset` (45/−20/−20/−80/−45 verified) | as 1.21.6–1.21.8: `getHandSwingProgress(t)` + `preferredHand`, no swing animation type; the idle branch always calls `swingArm(swing, equip, matrices, armX, arm)`, which translates by the swing, then `applyEquipOffset` and `applySwingOffset` (45/−20/−20/−80/−45 verified) | as 1.21.9–1.21.10: `getHandSwingProgress(t)` + `preferredHand`, no swing animation type; the idle branch always calls `swingArm(swing, equip, matrices, armX, arm)`, which translates by the swing, then `applyEquipOffset` and `applySwingOffset` (45/−20/−20/−80/−45 verified) | `getHandSwingProgress(t)` + `preferredHand`; no swing animation type (spears came in 1.21.11): the idle branch always calls `swingArm(swing, equip, matrices, armX, arm)`, which translates by the swing, then calls `applyEquipOffset` and `applySwingOffset` (the translations commute with 1.21.11's order) | `getHandSwingProgress(t)` + `preferredHand` + `item.getSwingAnimation().type()` (`SwingAnimationType` `WHACK`/`STAB`/`NONE`; `STAB` = `Lancing.method_75391`, identity only at 0) | 26.2 `getAttackAnim(t)` + `swingingArm` + `stack.getSwingAnimation().type()`; 26.3 `avatarRenderState.currentSwing` (`SwingDescription`) + `.swingAnimation` (the hand pass's own state) |
| Rod-holding arm | **none** — `render` computes it inline and by the *item*: `j = mainArm == RIGHT ? 1 : -1; if (!getMainHandStack().isOf(Items.FISHING_ROD)) j = -j;`. As ported: `FishingRodFix.armHoldingRod` mirrors it and `isRod` is `isOf(Items.FISHING_ROD)`, not `instanceof FishingRodItem` (1.21.5+). `LivingEntity.getStackInArm` doesn't exist either (`FishingRodFix.stackInArm`) | none — inline `isOf(Items.FISHING_ROD)` | **none** — `getHandPos` computes it inline and by the *item*: `i = mainArm == RIGHT ? 1 : -1; if (!getMainHandStack().isOf(Items.FISHING_ROD)) i = -i;`. As ported: `FishingRodFix.armHoldingRod` mirrors it and `isRod` is `isOf(Items.FISHING_ROD)`, not `instanceof FishingRodItem` (1.21.5+). `LivingEntity.getStackInArm` doesn't exist either (`FishingRodFix.stackInArm`) | `FishingBobberEntityRenderer.getArmHoldingRod` (public static, `instanceof FishingRodItem`) | `FishingBobberEntityRenderer.getArmHoldingRod` (public static, `instanceof FishingRodItem`) | `FishingBobberEntityRenderer.getArmHoldingRod` (public static, `instanceof FishingRodItem`) | `FishingBobberEntityRenderer.getArmHoldingRod` (public static, `instanceof FishingRodItem`) | `FishingBobberEntityRenderer.getArmHoldingRod` (public static, `instanceof FishingRodItem`) | `FishingHookRenderer.getHoldingArm` |
| Rod sprite | as 1.21–1.21.1: block atlas, keyed by the texture id — `MinecraftClient.getBakedModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)` (`@Deprecated` here too, still the registration key). Missing sprite `MissingSprite.getMissingSpriteId()`; held items draw with `entity_translucent_cull`, whose 1.20.1 `.fsh` discards `color.a < 0.1` (byte-identical to 1.20's). **`Identifier.ofVanilla` doesn't exist**: `new Identifier("item/fishing_rod_cast")` | block atlas (`getSpriteAtlas(BLOCK_ATLAS_TEXTURE)`, by texture id) | as 1.21.4: block atlas, keyed by the texture id — `MinecraftClient.getBakedModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)` (`@Deprecated` here too, still the registration key). Missing sprite `MissingSprite.getMissingSpriteId()`; held items draw with `item_entity_translucent_cull`, whose 1.21.1 `.fsh` discards `color.a < 0.1` (read from the client jar; byte-identical across 1.21 and 1.21.1) | as 1.21.5: block atlas, keyed by the texture id — `MinecraftClient.getBakedModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)` (deprecated but still the key the atlases are registered under). No items atlas; missing sprite `MissingSprite.getMissingSpriteId()`; held items draw with `item_entity_translucent_cull`, whose 1.21.4 `.fsh` discards `color.a < 0.1` (read from the client jar) | as 1.21.6–1.21.8: block atlas, keyed by the texture id — `MinecraftClient.getBakedModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)` (deprecated but still the key the atlases are registered under). No items atlas; missing sprite `MissingSprite.getMissingSpriteId()`; held items draw with `item_entity_translucent_cull` (alpha < 0.1 discarded) | block atlas, keyed by the texture id: `MinecraftClient.getBakedModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)` (deprecated but still the key `SpriteAtlasManager` registers the atlases under, and what `JsonUnbakedModel` resolves item textures against; the `Atlases` ids name the atlas definitions and do **not** work with `getAtlas`). `MinecraftClient.getSpriteAtlas(id)` is the same lookup but allocates a method reference per call. No items atlas; missing sprite `MissingSprite.getMissingSpriteId()`; held items draw with `item_entity_translucent_cull` (alpha < 0.1 discarded) | block atlas: `MinecraftClient.getAtlasManager().getAtlasTexture(Atlases.BLOCKS)` (no items atlas; `atlases/blocks.json` sources `item/`); held items draw with `TexturedRenderLayers.getItemEntityTranslucentCull()` (block atlas, `item_entity_translucent_cull`, discards alpha < 0.1); missing sprite `MissingSprite.getMissingSpriteId()` | items atlas `MinecraftClient.getAtlasManager().getAtlasTexture(Atlases.ITEMS)` (atlas definition id, not the texture path); missing sprite `MissingSprite.getMissingSpriteId()`; held items draw with `item_entity_translucent_cull`, whose fragment shader discards alpha < 0.1 | items atlas `getAtlasOrThrow(AtlasIds.ITEMS)` |
| Sprite frames / pixels | identical to 1.21–1.21.1: no `isAnimated()`, so an `@Invoker` for the private `getFrameCount()` stands in (`animation != null ? animation.frames.size() : 1`, and `createAnimation` returns null for a frame list of ≤ 1); `getDistinctFrameCount()` (`IntStream`, dummy `IntStream.of(1)` without an animation); image field `image`; **`NativeImage.getColor` is public and returns ABGR** (no `getColorArgb` yet), so `ColorHelper.Abgr.getAlpha` — `Argb`/`Abgr` are nested classes of `ColorHelper` here; frames row-major (`getFrameX = frame % frameCount`) | no `isAnimated` (null-check the `animation` field via `@Accessor`); `getDistinctFrameCount()` (`IntStream`, `[1]` for a static sprite); image field `image`; `NativeImage.getColor` (ABGR, alpha still the top byte) | no `isAnimated()`, so an `@Invoker` for the private `getFrameCount()` stands in (`animation != null ? animation.frames.size() : 1`, and `createAnimation` returns null for a frame list of ≤ 1); `getDistinctFrameCount()` (`IntStream`, dummy `[1]` without an animation); image field `image`; **`NativeImage.getColor` is public and returns ABGR** (no `getColorArgb` yet), so `ColorHelper.Abgr.getAlpha` — note `Argb`/`Abgr` are nested classes of `ColorHelper` here, not top-level statics; frames row-major (`getFrameX = frame % frameCount`) | as ported, as 1.21.5 (`SpriteContents` differs only in its `upload` signatures, a GPU-API change): no `isAnimated()`, so an `@Invoker` for the private `getFrameCount()` stands in; `getDistinctFrameCount()` (`IntStream`, dummy `[1]` without an animation); field `image`; **`NativeImage.getColorArgb` is already public here** (ARGB) — the old column's “as 1.20.4, `getColor` ABGR” was wrong; `ColorHelper.getAlpha` is the static top-level one; frames row-major (`getFrameX = frame % frameCount`) | as 1.21.6–1.21.8 (`SpriteContents` decompiles the same except an unrelated `writeToTexture` argument): no `isAnimated()`, so an `@Invoker` for the private `getFrameCount()` stands in; `getDistinctFrameCount()` (dummy `[1]` without an animation); field `image`; `NativeImage.getColorArgb` (ARGB); frames row-major | no `isAnimated()` (it came in 1.21.9): the `animation` field is private and its type package-private, so use an `@Invoker` for the private `getFrameCount()` instead — it is `animation != null ? frames.size() : 1` and `createAnimation` returns null for one frame or fewer, so `> 1` is exactly `animation != null`. The tile grid is **not** a substitute: `SpriteOpener` takes the sprite size from the image only when there is no animation metadata, so a pack whose frame list comes out with one entry gets a null animation over a multi-tile sheet and the dummy `[1]` would be read off a tile vanilla never draws. `getDistinctFrameCount()` (`IntStream`, dummy `[1]` without an animation); field `image`; `NativeImage.getColorArgb` (ARGB); frames row-major | as 1.21.11 (`isAnimated()`, `getDistinctFrameCount()`, field `image`, `getColorArgb`, frames row-major) | `isAnimated()` (an `animation` exists only with more than one frame, so it is `isPixelTransparent`'s offset test); `getDistinctFrameCount()` (`IntStream`, `[1]` for a static sprite); field `image`; `getColorArgb`; frames row-major, `imageWidth / width` per row | `isAnimated()`; `getUniqueFrames()` (`IntList.of(1)` for a static sprite); field `originalImage`; `getPixel` (ARGB) |
| Vanilla rod assets | same (`handheld_rod.json` **and** `fishing_rod_cast.png` byte-identical to 1.21.1's, and identical between 1.20 and 1.20.1; decoded alpha mask identical, tip `(15, 1)/16`) | `fishing_rod_cast.png` alpha mask + `handheld_rod.json` identical to 26.3 (verified 1.20.4, 1.21, 1.21.1, 1.21.5, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, 26.3) | same (`handheld_rod.json` **and** `fishing_rod_cast.png` byte-identical to 1.21.4's, and identical between 1.21 and 1.21.1; decoded alpha mask identical, tip `(15, 1)/16`) | same (`handheld_rod.json` **and** `fishing_rod_cast.png` byte-identical to 1.21.5's; decoded alpha mask identical, tip `(15, 1)/16`) | same (`handheld_rod.json` byte-identical to 1.21.8's; `fishing_rod_cast.png` differs only in PNG encoding — its decoded RGBA is identical, tip `(15, 1)/16`) | same (`handheld_rod.json` firstperson_righthand `[0,90,25]`/`[0,1.6,0.8]`/0.68 and thirdperson_righthand `[0,90,55]`/`[0,4,2.5]`/0.85 read from the 1.21.8 client jar) | same | same | same |
| Injected descriptor | as ported: the full `render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V` everywhere (v0.5 used a name-only `"renderFishingLine"` selector) | name only: `"renderFishingLine"` | as ported: `getHandPos(L…PlayerEntity;FF)L…Vec3d;` (v0.5 used the same method, at RETURN) | as ported: `getHandPos(L…PlayerEntity;FF)L…Vec3d;` (the v0.3 build used `renderFishingLine(…FF)V`) | `getHandPos(L…PlayerEntity;FF)L…Vec3d;` | `getHandPos(L…PlayerEntity;FF)L…Vec3d;` | `getHandPos(L…PlayerEntity;FF)L…Vec3d;` | `getHandPos(L…PlayerEntity;FF)L…Vec3d;` | `getPlayerHandPos(L…Player;FF)L…Vec3;` |
| Line emit path | direct vertex (`RenderLayer.getLineStrip()`, 17 `renderFishingLine` calls in `render`) | direct vertex | direct vertex (`RenderLayer.getLineStrip()`, 17 `renderFishingLine` calls in `render`) | direct vertex (`RenderLayer.getLineStrip()`, 17 `renderFishingLine` calls in `render`) | direct vertex (`RenderLayer.getLineStrip()`, 17 `renderFishingLine` calls in `render`) | direct vertex (`RenderLayer.getLineStrip()`, 17 `renderFishingLine` calls in `render`) | deferred `submit` (the command queue exists from 1.21.9) | deferred `submit` | deferred `submit` |
| Vertex emit (strategy B only) | `.color(0, 0, 0, 255).normal(matrices.getNormalMatrix(), …).next()`, no `.lineWidth`; `renderFishingLine(FFF…FF)V` called directly from `render`'s loop (not cancelled: the port modifies the first-person expression and only wraps the call for line hiding) | `.color(0,0,0,255).normal(getNormalMatrix(),…).next()` | `.color(Colors.BLACK).normal(matrices,…)`, no `.lineWidth`; `renderFishingLine(FFF…FF)V` called directly from `render`'s loop (not cancelled: the port uses strategy A and only wraps the call for line hiding) | `.color(-16777216).normal(matrices,…)`, no `.lineWidth`; `renderFishingLine(FFF…FF)V` called directly from `render`'s loop (not cancelled: the port uses strategy A and only wraps the call for line hiding) | `.color(-16777216).normal(matrices,…)`, no `.lineWidth`; `renderFishingLine(FFF…FF)V` called directly from `render`'s loop (not cancelled: the fix uses strategy A and only wraps the call for line hiding) | `.color(-16777216).normal(matrices,…)`, no `.lineWidth`; `renderFishingLine(FFF…FF)V` called directly from `render`'s loop (not cancelled: the fix uses strategy A and only wraps the call for line hiding) | `.color(-16777216).normal(matrices,…)`, no `.lineWidth`; `renderFishingLine(…FF)V` inside `render`'s `submitCustom` lambda | `.color(0xFF000000).normal(matrices,…).lineWidth(w)` | n/a (no `renderFishingLine`) |
| World FOV | as ported: `renderWorld`'s only `GameRenderer.getFov` INVOKE (`changingFov` true, returns `double`), **before** its own camera update and before the entities, captured by an optional MEV; `@Invoker` `getFov(camera, δ, true)` without it. The world projection is `getBasicProjectionMatrix(fov) · bob · warp`, uploaded with `RenderSystem.setProjectionMatrix` and kept nowhere bob-free, so there is no projection to read (no `m33` orthographic check). The other `getFov` in the method is `GameOptions.getFov`, a different owner, so the MEV target can't match it | `GameRenderer.getFov` (AW) | as ported: `renderWorld`'s only `GameRenderer.getFov` INVOKE (`changingFov` true, returns `double`), after its own camera update and before the entities, captured by an optional MEV; `@Invoker` `getFov(camera, δ, true)` without it. The world projection is `getBasicProjectionMatrix(fov) · bob · warp`, uploaded with `RenderSystem.setProjectionMatrix` and kept nowhere bob-free, so there is no projection to read (no `m33` orthographic check). The other `getFov` in the method is `GameOptions.getFov`, a different owner, so the MEV target can't match it | as ported, as 1.21.5: `renderWorld`'s only `GameRenderer.getFov` INVOKE (ordinal 0, `changingFov` true), after its own camera update and before the entities, captured by an optional MEV; `@Invoker` `getFov(camera, δ, true)` without it. The world projection is `getBasicProjectionMatrix(fov) · bob · warp`, uploaded with `RenderSystem.setProjectionMatrix` and kept nowhere bob-free, so there is no projection to read (no `m33` orthographic check). The other `getFov` in the method is `GameOptions.getFov`, a different owner, so the MEV target can't match it | as 1.21.6–1.21.8: `renderWorld`'s only `getFov` INVOKE (ordinal 0, `changingFov` true), after its own camera update and before the entities, captured by an optional MEV; `@Invoker` `getFov(camera, δ, true)` without it. The world projection is `getBasicProjectionMatrix(fov) · bob · warp` | as 1.21.9–1.21.10: `renderWorld`'s first `getFov` INVOKE (ordinal 0, `changingFov` true), after its own camera update and before the entities, captured by an optional MEV; `@Invoker` `getFov(camera, δ, true)` without it. The world projection is `getBasicProjectionMatrix(fov) · bob · warp` | as 1.21.11 (`renderWorld`'s first `getFov` call, after its own camera update and before the entities, captured; `@Invoker` without it) | as ported: the value of `renderWorld`'s first `getFov` INVOKE (ordinal 0, `changingFov` true, before the entity extraction), captured by an optional MEV; `@Invoker` `getFov(camera, δ, true)` without it. The world projection is `getBasicProjectionMatrix(fov) · bob · warp`, uploaded with `RenderSystem.setProjectionMatrix`, and no bob-free copy is kept, so there is no projection to read: zoom mods that hook `getFov` are followed, projection-level mods and an orthographic camera aren't (no `m33` check) | `1 / cameraRenderState.projectionMatrix.m11()` = tan(fov/2) of the projection `renderLevel` uses (bob and warp go onto a copy); in vanilla built from `Camera.getFov()` (`Camera.update` → `setupPerspective(…, fov, …)`), which also follows `calculateFov` hooks (Ok Zoomer, Zoomify) but misses mods that change the projection itself (Snapmatica's lens). Skip an orthographic projection (`m33 != 0`) |
| Tick delta | **no `MinecraftClient.getRenderTickCounter()` and no `RenderTickCounter.getTickDelta(boolean)`**: `MinecraftClient.render` passes `this.paused ? this.pausedTickDelta : this.renderTickCounter.tickDelta` (both private) to `GameRenderer.render`, and from there to `renderWorld`, the warp, the entity loop and the hand pass alike. The public `getTickDelta()` is the counter's field only, which keeps running while the integrated server is paused. As ported: a `MinecraftClientAccessor` for both fields behind `FishingRodFix.tickDelta`. **No `TickManager`** (`/tick freeze` arrived in 1.20.3), so every entity is drawn at that same progress and `WorldRenderer.renderEntity` takes no per-entity delta | `renderTickCounter`/`pausedTickDelta` (AW) | as 1.21.4: `renderWorld` hands the hand pass (`renderHand` → `renderItem`) and the warp `renderTickCounter.getTickDelta(true)`; the world bob uses `camera.getLastTickDelta()`; each entity is drawn with `tickCounter.getTickDelta(!tickManager.shouldSkipTick(entity))` | as 1.21.5 but renamed: `renderWorld` hands the hand pass (`renderHand` → `renderItem`) and the warp **`renderTickCounter.getTickDelta(true)`** (1.21.5: `getTickProgress(true)`); the world bob uses **`camera.getLastTickDelta()`**, which equals it whenever the camera entity is a player | as 1.21.6–1.21.8: `renderWorld` hands the hand pass (`renderHand` → `renderItem`) and the warp `renderTickCounter.getTickProgress(true)`; the world bob uses `camera.getLastTickProgress()`, which equals it whenever the camera entity is a player | as 1.21.9–1.21.10: `renderWorld` hands the hand pass and the warp `renderTickCounter.getTickProgress(true)`; the world bob uses `camera.getLastTickProgress()`, which equals it whenever the camera entity is a player (`TickManager.shouldSkipTick` is false for players) | as 1.21.11 (`renderWorld` hands the hand pass and the warp `getTickProgress(true)`; the world bob uses `camera.getLastTickProgress()`) | method `tickProgress` param (the hook's: `getTickProgress(!shouldSkipTick(entity))`; players never skip). As ported: `renderWorld` hands the hand pass (`renderHand` → `renderItem`) and the warp its `renderTickCounter.getTickProgress(true)`, read through `MinecraftClient.getRenderTickCounter()` (the frame's counter; panorama capture passes `RenderTickCounter.ONE` and is excluded); the world bob uses `camera.getLastTickProgress()`, the same value for a player camera | 26.1–26.2: method `partialTicks` param (the hook's); the hand pass gets `Camera.getCameraEntityPartialTicks(deltaTracker)` and the warp `deltaTracker.getGameTimeDeltaPartialTick(false)` in `renderLevel` (26.2 port: read with `Minecraft.getDeltaTracker()`, the frame's tracker outside panorama capture, which passes `DeltaTracker.ONE`); 26.3: the hand pass's `cameraRenderState.cameraEntityPartialTicks`. 26.1.2: as 26.2 (`renderLevel` computes both the same way) |
| Eye / camera / player pos | `camera.getPos()`; there is no render state, so the body and the hook are lerped from `lastRenderX/Y/Z` and the camera from `prevX/Y/Z` | `getPos()` | `camera.getPos()`; there is no render state, so the body and the hook are lerped from `lastRenderX/Y/Z` (not renamed) and the camera from `prevX/Y/Z` | `camera.getPos()`; render state position `lerp(lastRenderX, getX())` (`lastRenderX` is **not** renamed), camera `lerp(prevX, getX())` (1.21.5 renamed `prevX` → `lastX`) | `camera.getPos()`; render state position `lerp(lastRenderX, getX())`, camera `lerp(lastX, getX())` (1.21.5 renamed `prevX` → `lastX`) | `camera.getPos()`; render state position `lerp(lastRenderX, getX())`, camera `lerp(lastX, getX())` | `camera.getPos()` (`getCameraPos()` is a `YawProvider` override delegating to it, so either name compiles; as ported `getPos()`) | `getCameraPosVec(t)` / `getCameraPos()` / `getEntityPos()`; as ported only `camera.getCameraPos()` (the fix anchors at the camera); render state position `lerp(lastRenderX, getX())` (Mojang `xOld`), camera `lerp(lastX, getX())` (Mojang `xo`) | `camera.position()` only (the fix anchors at the camera) |
| Camera rotation (view → world) | verified on 1.20.1 **and** 1.20.4: `setRotation` does `rotation.rotationYXZ(-yaw·π/180, pitch·π/180, 0)` and `moveBy(x, y, z) = x·horizontalPlane + y·verticalPlane + z·diagonalPlane`, with the three planes set from `(0,0,1)`, `(0,1,0)`, `(1,0,0)` — local +Z forward, +Y up, **+X left** (`moveBy(forward, up, left)`; `update` backs a third-person camera off with `moveBy(-clip, 0, 0)`). So a view vector `(x right, y up, z back)` goes in as **`(-x, y, -z)`**. 1.21.0 changed the quaternion to `rotationYXZ(π − yaw, −pitch, 0)`, where the same vector goes in unchanged — the one sign difference in the whole port that no build error can catch | `getRotation()` from `rotationYXZ(-yaw, pitch, 0)`: local +Z forward, +X **left** — rotate `(-x, y, -z)` of a view vector (x right, y up, z back); `moveBy(forward, up, left)` | verified: `rotationYXZ(π − yaw, −pitch, 0)`, `moveBy(f, g, h)` builds `(h, g, −f)` — rotate `(x, y, z)` directly | verified: `rotationYXZ(π − yaw, −pitch, 0)`, `moveBy(f, g, h)` builds `(h, g, −f)` — rotate `(x, y, z)` directly | verified: `rotationYXZ(π − yaw, −pitch, 0)`, `moveBy(f, g, h)` builds `(h, g, −f)` — rotate `(x, y, z)` directly | verified: `rotationYXZ(π − yaw, −pitch, 0)`, `moveBy(f, g, h)` builds `(h, g, −f)` — rotate `(x, y, z)` directly | verified as 1.21.11 (`moveBy(f, g, h)` builds `(h, g, −f)`): rotate `(x, y, z)` directly | verified: `rotationYXZ(π − yaw, −pitch, 0)`; `moveBy(surge, heave, sway)` builds `(sway, heave, −surge)`: rotate `(x, y, z)` directly | `rotation()`: rotate `(x, y, z)` directly, as `move(forwards, up, right)` builds `(right, up, -forwards)` |
| Camera entity (spectator guard) | same (`getFocusedEntity()`) | `getFocusedEntity()` | same (`getFocusedEntity()`) | same (`getFocusedEntity()`) | same (`getFocusedEntity()`) | same | same | same (`EntityRenderManager.camera` is the `GameRenderer`'s camera, configured in `WorldRenderer.render`) | `entity()` |
| Bob / hurt tilt (both passes) | verified: private `tiltViewWhenHurt`/`bobView(MatrixStack, float)` → `@Invoker`; **one call each in `renderWorld` and two each in `renderHand`** (the first pair before the hand, the second after it for the overlays — the hand gets the first). `renderWorld` multiplies them into the world projection, `renderHand` into the hand's pose stack | `GameRenderer` private `bobView`/`tiltViewWhenHurt(MatrixStack, float)` (verify) | verified: private `tiltViewWhenHurt`/`bobView(MatrixStack, float)` → `@Invoker`, one call each in `renderWorld` and in `renderHand`; `renderWorld` multiplies them into the world projection, `renderHand` into the hand's pose stack | verified: private `tiltViewWhenHurt`/`bobView(MatrixStack, float)` → `@Invoker`; `renderWorld` multiplies them into the world projection, `renderHand` into the hand's pose stack | verified: private `tiltViewWhenHurt`/`bobView(MatrixStack, float)` → `@Invoker`; `renderWorld` multiplies them into the world projection, `renderHand` into the hand's pose stack | verified: private `tiltViewWhenHurt`/`bobView(MatrixStack, float)` → `@Invoker`; `renderWorld` multiplies them into the world projection, `renderHand` into the hand's pose stack | verified as 1.21.11 (private `tiltViewWhenHurt`/`bobView(MatrixStack, float)` → `@Invoker`) | verified: private `bobView`/`tiltViewWhenHurt(MatrixStack, float)` → `@Invoker`; `renderWorld` multiplies them into the world projection, `renderHand` into the hand's pose stack | 26.3: `GameRenderer` private `bobView`/`bobHurt(CameraRenderState, PoseStack)` → `@Invoker` (26.1–26.2: same) |
| Nausea / portal warp (world pass only) | identical to 1.21–1.21.1, inline in `renderWorld`: intensity `lerp(tickDelta, player.prevNauseaIntensity, player.nauseaIntensity) × distortionEffectScale²` (no `max(…, getEffectFadeFactor(NAUSEA))`); angle `(ticks + tickDelta) · (hasStatusEffect(NAUSEA) ? 7 : 20)°` from the private **`int ticks`** (`@Accessor`). Same skew formula and axis | inline in `renderWorld`: intensity `lerp(prevNauseaIntensity, nauseaIntensity)`; angle `(ticks + tickDelta)·(NAUSEA ? 7 : 20)°` (private `GameRenderer.ticks`) | identical to 1.21.4, inline in `renderWorld`: intensity `lerp(tickDelta, player.prevNauseaIntensity, player.nauseaIntensity) × distortionEffectScale²` (no `max(…, getEffectFadeFactor(NAUSEA))`); angle `(ticks + tickDelta) · (hasStatusEffect(NAUSEA) ? 7 : 20)°` from the private **`int ticks`** (`@Accessor`). Same skew formula and axis | **this is the one behavioural difference from 1.21.5.** Inline in `renderWorld`: intensity `lerp(tickDelta, player.prevNauseaIntensity, player.nauseaIntensity) × distortionEffectScale²` — no `max(…, getEffectFadeFactor(NAUSEA))`, because `ClientPlayerEntity.tickNausea` already feeds `nauseaIntensity` from the portal *and* the effect; angle `(ticks + tickDelta) · (hasStatusEffect(NAUSEA) ? 7 : 20)°` from the private **`int ticks`** (`@Accessor`; 1.21.5 replaced it with `nauseaEffectTime`/`nauseaEffectSpeed`). Same skew formula and axis. As ported: `GameRendererAccessor` exposes `ticks` instead of the 1.21.5 pair | verified as 1.21.6–1.21.8: private `nauseaEffectTime`/`nauseaEffectSpeed` (`@Accessor`), angle `(time + tickProgress·speed)°`, intensity `max(lerp(lastNauseaIntensity, nauseaIntensity), getEffectFadeFactor(NAUSEA))` × `getDistortionEffectScale()²`. **These two fields are what 1.21.4 doesn't have** (it still has the `ticks` int and the older `(ticks + tickDelta)·(NAUSEA ? 7 : 20)°` formula), so the jar cannot cover 1.21.4 and below | verified as 1.21.9–1.21.10 (private `nauseaEffectTime`/`nauseaEffectSpeed`, angle `(time + tickProgress·speed)°`, intensity `max(lerp(lastNauseaIntensity, nauseaIntensity), getEffectFadeFactor(NAUSEA))` × `getDistortionEffectScale()²`) | verified as 1.21.11 | as 1.21.5, verified: private `nauseaEffectTime`/`nauseaEffectSpeed` (`@Accessor`), angle `(time + tickProgress·speed)°`, intensity `max(lerp(lastNauseaIntensity, nauseaIntensity), getEffectFadeFactor(NAUSEA))` × `getDistortionEffectScale()²` | 26.1–26.2: private `spinningEffectTime`/`spinningEffectSpeed`, angle `(time + worldPartialTicks·speed)°`, intensity `max(lerp(portal), getEffectBlendFactor(NAUSEA))`; 26.3: `playerRenderState.spinningEffectAngle`/`portalEffectIntensity`/`nauseaEffectIntensity`. All: `rotate(a, (0, √2/2, √2/2)) · scale(1/skew, 1, 1) · rotate(−a, …)`, `skew = (5/(i²+5) − 0.04i)²` |
| Strategy B hook (first-person point expression) | the single `Vec3d.rotateX(F)` on the first-person path (the whole method has exactly one), which the port modifies instead of cancelling `renderFishingLine`; its value is relative to the lerped **feet** — `render` adds the lerped feet to it and `getStandingEyeHeight()` into the line's y offset (`w`) afterwards, so the handler converts both ways around a world point | `render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V` (full descriptor: a synthetic bridge `render(Entity,…)` also exists): the single `Vec3d.rotateX(F)` on the first-person path; its value is relative to the lerped **feet** (`getStandingEyeHeight()` is added after) | n/a (strategy A; v0.5 on this branch already used `getHandPos`, at RETURN) | n/a since v0.6 (strategy A). The v0.3 build cancelled `renderFishingLine` at HEAD | n/a (strategy A) | n/a (strategy A) | n/a | n/a | n/a |
| Source layout (reference fix) | as ported: 1.21–1.21.1's set **plus** `MinecraftClientAccessor` — `FishingBobberEntityRendererMixin`, `GameRendererInvoker`, `GameRendererAccessor` (`ticks` + `fovMultiplier`/`lastFovMultiplier`), `GameRendererMixin`, `HeldItemRendererMixin`, `HeldItemRendererAccessor`, `MinecraftClientAccessor`, `FishingBobberEntityMixin`, `SpriteContentsAccessor`, `CameraAccessor`, `HeldItemFeatureRendererMixin`, `AbstractClientPlayerEntityMixin`, `VertexConsumerProviderImmediateMixin`, `WorldRendererMixin`; logic classes as on 26.x. v0.5 was one mixin plus an entrypoint and an access widener, all removed | — | as ported: 1.21.4's set **minus** `FishingBobberEntityStateMixin` (no render state) — `FishingBobberEntityRendererMixin`, `GameRendererInvoker`, `GameRendererAccessor` (`ticks` + `fovMultiplier`/`lastFovMultiplier`), `GameRendererMixin`, `HeldItemRendererMixin`, `HeldItemRendererAccessor`, `FishingBobberEntityMixin`, `SpriteContentsAccessor`, `CameraAccessor`, `HeldItemFeatureRendererMixin`, `AbstractClientPlayerEntityMixin`, `VertexConsumerProviderImmediateMixin`, `WorldRendererMixin`; logic classes as on 26.x. v0.5 was one mixin plus an entrypoint and an access widener, all removed | as ported: exactly 1.21.5's set — `FishingBobberEntityRendererMixin`, `GameRendererInvoker`, `GameRendererAccessor` (here `ticks` + `fovMultiplier`/`lastFovMultiplier`), `GameRendererMixin`, `HeldItemRendererMixin`, `HeldItemRendererAccessor`, `FishingBobberEntityStateMixin`, `FishingBobberEntityMixin`, `SpriteContentsAccessor`, `CameraAccessor`, `HeldItemFeatureRendererMixin`, `AbstractClientPlayerEntityMixin`, `VertexConsumerProviderImmediateMixin`, `WorldRendererMixin` (head `sortBobbersLast` + tail pass end, priority 1500); logic classes as on 26.x. The v0.3 build was one mixin plus an entrypoint and an access widener, all removed | as ported: as 1.21.6–1.21.8 (`FishingBobberEntityRendererMixin`, `GameRendererInvoker`, `GameRendererAccessor`, `GameRendererMixin`, `HeldItemRendererMixin`, `HeldItemRendererAccessor`, `FishingBobberEntityStateMixin`, `FishingBobberEntityMixin`, `SpriteContentsAccessor`, `CameraAccessor`, `HeldItemFeatureRendererMixin`, `AbstractClientPlayerEntityMixin`, `VertexConsumerProviderImmediateMixin`, `WorldRendererMixin`; logic classes as on 26.x), with `WorldRendererMixin`'s head inject keeping 1.21.8's `ThirdPersonLineOrigin.sortBobbersLast` (partition + owner stamping): vanilla 1.21.5 needs no reordering, but Iris does — see the Body-held rod row | as ported: as 1.21.9–1.21.10, except `OrderedRenderCommandQueueImplMixin` → `VertexConsumerProviderImmediateMixin` (counts `VertexConsumerProvider.Immediate.draw()`, MEV on its only `layerBuffers` read) plus a new `WorldRendererMixin` (`@Inject` at `renderEntities`' TAIL, mixin priority 1500) with a second `@Inject` at the same method's HEAD calling `ThirdPersonLineOrigin.sortBobbersLast`, which moves the fishing bobbers behind every other entity — all `require = 0`. One class carries both, priority 1500 giving the last word at the head as at the return (pitfall 12); both pass ends are needed because a mod that replaces the buffer sources (ImmediatelyFast) never runs vanilla's `draw()`, and the head sort is what makes the bodies draw before the hooks in a family where the order is otherwise an identity hash | as 1.21.11 (the same mixins and logic classes) | as ported: mixins `FishingBobberEntityRendererMixin`, `GameRendererInvoker` (+ `getFov`), `GameRendererAccessor` (`nauseaEffectTime`/`nauseaEffectSpeed`, `fovMultiplier`/`lastFovMultiplier`), `GameRendererMixin` (counter at `render`'s `updateCamera` INVOKE, past `skipGameRender`, F1/gate samples at `renderWorld` HEAD, MEVs capturing `renderWorld`'s two `getFov` results; all `require = 0`), `HeldItemRendererMixin` (hand drawn), `HeldItemRendererAccessor`, `FishingBobberEntityStateMixin`, `FishingBobberEntityMixin`, `SpriteContentsAccessor`, `CameraAccessor` (`cameraY`/`lastCameraY`), `HeldItemFeatureRendererMixin` (drawn rod), `AbstractClientPlayerEntityMixin`, `OrderedRenderCommandQueueImplMixin` (MEV on `clear()V`'s only `batchingQueues` read: the pass tag's clear count, as 26.1.2's `SubmitNodeStorageMixin`); logic classes as on 26.x | 26.3: mixins `FishingHookRendererMixin` (first-person origin MEV, visibility inject/wrap, body-held line at `submit`: HEAD + 3 `@ModifyVariable`), `GameRendererInvoker`, `GameRendererMixin` (frame count + F1/gate sampling, frame start for the body-held path), `FirstPersonHandsAndItemsRendererMixin` (hand drawn), `FishingHookRenderStateMixin` (line-hidden flag, body-rod owner + its partial tick + same-pass-only flag), `FishingHookMixin` (seen-with-rod flag), `SpriteContentsAccessor`, `CameraAccessor` (eye height), `ItemInHandLayerMixin` (drawn rod), `AbstractClientPlayerMixin` (drawn-rod record); logic `FishingLineOrigin` + `FirstPersonRod` (first person), `ThirdPersonLineOrigin` + `ThirdPersonRod` (body-held rod), `RodSprite` (pack tip), `FishingLineVisibility`, `HandPass`, `FishingRodFix` (logger, `isRod`, `holdsRod`). 26.2: the same, with `ItemInHandRendererMixin` (hand drawn) in place of `FirstPersonHandsAndItemsRendererMixin`, plus `ItemInHandRendererAccessor` (drawn items, equip heights) and `GameRendererAccessor` (`spinningEffectTime`/`spinningEffectSpeed`). 26.1.2: as 26.2, plus `SubmitNodeStorageMixin` (`@ModifyExpressionValue` on `SubmitNodeStorage.clear()V`'s only `submitsPerOrder` read, `require = 0`, no allocation: the pass tag's clear count, see the Body-held rod row); names `renderHandsWithItems`/`renderArmWithItem`, `GameRenderer.getGameRenderState()`/`getMainCamera()` (26.2: `gameRenderState()`/`mainCamera()`) |
| Body-held rod (third person) | as ported, the same shape as 1.21–1.21.1: no render state **and** no `WorldRenderer.renderEntities` — the entity loop is inside `WorldRenderer.render(MatrixStack, F, J, Z, Camera, GameRenderer, LightmapTextureManager, Matrix4f)` and walks `ClientWorld.getEntities()`, an `Iterable`, so the bobbers-last order is a `@ModifyExpressionValue` on the class's **only** `Iterable.iterator()` INVOKE (mixin priority 1500) returning a lazy partitioning `Iterator`; **Iris' `MixinLevelRenderer_EntityListSorting` `@WrapOperation`s that same instruction at priority 999 on its own `1.20.1` branch too** (checked at commit f8d7bea2, in `iris-batched-entity-rendering.mixins.json`, not gated on a pack), so the higher priority makes ours the outer one. That same MEV records the pass the loop runs in — the "inside the entity loop" half of the pass tag, taken once before the first entity, which matches a rod and a hook of one loop however their collectors differ (Iris with a pack gives every entity its own) and leaves its shadow pass, drawn outside the loop, pairing by collector. The pass end is the first `WorldRenderer.checkEmpty(MatrixStack)V` INVOKE in `render` (three in the method; ordinal 0 sits five bytes past the `drawCurrentLayer()` that closes the entity loop) plus `Immediate.draw()`'s `layerBuffers` read (a `Map<RenderLayer, BufferBuilder>` here, not a `SequencedMap`). `OutlineVertexConsumerProvider` is not an `Immediate` here **and does not delegate**: its `draw()` flushes a private `plainDrawer` `Immediate` of its own, so it still marks a pass — it runs after the block entities, well past the entity loop. Extraction and drawing are one call, so the per-hook decision is three static fields, and the held-item call carries the **stack and the entity**, so the read's gate is `isRod(stack) && entity instanceof AbstractClientPlayerEntity`. `PlayerHeldItemFeatureRenderer` overrides `renderItem` but delegates to `super` for everything but a raised spyglass. `LivingEntityRenderer` has no `clampBodyYaw`: the 85 / 2500 / 0.2 clamp is inline in its `render`, mirrored by `ThirdPersonLineOrigin.bodyYaw`. `ItemRenderer.renderItem` applies `model.getTransformation().getTransformation(mode).apply(leftHand, matrices)` then `translate(-0.5, -0.5, -0.5)` two calls after the injection point | no deferred submit: the item layer renders during `render`; re-derive where the rod and the line are drawn and in which order (unverified) | as ported: no render state **and** no `WorldRenderer.renderEntities` — the entity loop is inside `WorldRenderer.render(RenderTickCounter, Z, Camera, GameRenderer, LightmapTextureManager, Matrix4f, Matrix4f)` and walks `ClientWorld.getEntities()`, an `Iterable`, so there is no list to sort or compact. The bobbers-last order is therefore a `@ModifyExpressionValue` on the class's **only** `Iterable.iterator()` INVOKE (mixin priority 1500) returning a lazy partitioning `Iterator`; **Iris' `MixinLevelRenderer_EntityListSorting` `@WrapOperation`s that same instruction at priority 999 on its own `1.21.1` branch**, so the higher priority makes ours the outer one. That same MEV records the pass the loop runs in — the "inside the entity loop" half of the pass tag, as on 1.20–1.20.1. The pass end is the first `WorldRenderer.checkEmpty(MatrixStack)V` INVOKE in `render` (three in the method; ordinal 0 sits six bytes past the `drawCurrentLayer()` that closes the entity loop) plus `Immediate.draw()`'s `layerBuffers` read — `checkEmpty` rather than the flush because mods that draw one more body at the end of the entity loop hang it on that very call (Freecam's Show Player, default injector priority), and at mixin priority 1500 the mark runs after theirs, keeping that body inside the pass; `OutlineVertexConsumerProvider` is *not* an `Immediate` here — it holds one and its `draw()` delegates, so the hook still fires. Extraction and drawing are one call, so the per-hook decision is three static fields, not a render state, and the held-item call carries the **stack and the entity**, so the read's gate is `isRod(stack) && entity instanceof AbstractClientPlayerEntity` — no `(id, arm)` sets, no owner stamping, no two-frame window, and no lost first frame. `PlayerHeldItemFeatureRenderer` overrides `renderItem` but delegates to `super` for everything but a raised spyglass. `LivingEntityRenderer` has no `clampBodyYaw`: the 85 / 2500 / 0.2 clamp is inline in its `render`, mirrored by `ThirdPersonLineOrigin.bodyYaw`. `ItemRenderer.renderItem` applies `model.getTransformation().getTransformation(mode).apply(leftHand, matrices)` then `translate(-0.5, -0.5, -0.5)` two calls after the injection point, exactly as `ThirdPersonRod` models it | as ported, as 1.21.5: no deferred submit — `WorldRenderer.renderEntities(MatrixStack, VertexConsumerProvider$Immediate, Camera, RenderTickCounter, List)` extracts **and** draws each entity in turn (`EntityRenderDispatcher.render`), so the gate at the held-item call takes the previous frame's hook owners too and every player's hook counts for it, and the head walk stamps the owners of the bobbers in the draw list so a hook is read in the frame it appears. **Vanilla's own draw order needs no forcing, but Iris' does**: 1.21.4 has no `ENTITY_COMPARATOR` (`WorldRenderer` contains no `List.sort`; the sort existed only 1.21.6–1.21.8), `getEntitiesToRender` walks `ClientWorld.getEntities()` in insertion order, and a player is added before the hooks it casts — but Iris' `MixinLevelRenderer_EntityListSorting` (priority 999, in the always-loaded `iris-batched-entity-rendering.mixins.json`, verified on Iris' own `1.21.4` branch, releases 1.8.2–1.8.8) `@WrapOperation`s the `Iterable.iterator()` inside `getEntitiesToRender` and regroups by `EntityType` through a `HashMap`, so the port keeps 1.21.5's bobbers-last partition at `renderEntities`' head (priority 1500). Iris' `ShadowRenderer` iterates `entitiesForRendering()` itself, skips only spectators and sorts by `EntityType.hashCode()`, so the sort hook never reaches it. One `EntityRenderer` holds **one** shared render state for all its entities; the pass tag is the `VertexConsumerProvider` + `HandPass` frame + a count of pass ends (`WorldRenderer.renderEntities`' return at priority 1500 **and** `VertexConsumerProvider.Immediate.draw()`, whose only `layerBuffers` read is the MEV site, because ImmediatelyFast replaces the buffer sources and never runs vanilla's `draw()`) **plus whether the drawing was inside the world's entity loop**, recorded at that same `renderEntities` HEAD before the first entity: two drawings of one loop then match however their collectors differ, which is what carries Iris' per-entity buffer sources, while its shadow pass (outside the loop, drawn before the record) keeps pairing by collector. `LivingEntityRenderState` has no `hasVehicle`: read `hasVehicle()` off the entity. `EntityRenderState` has no `id`; `PlayerEntityRenderState.id` does exist (that class is byte-identical to 1.21.5's). `LivingEntityRenderer.clampBodyYaw` limits 85/50/0.2 and the 0.25 lift in `renderFishingLine` verified. `ItemRenderState.LayerRenderState.render` applies `getTransformation().apply(leftHand, matrices)` then `translate(-0.5, -0.5, -0.5)`, i.e. the display transform runs *after* the injection point, exactly as `ThirdPersonRod` models it; `Transformation.apply`'s left-hand fix (negate rotation y/z and translation x) is unchanged | no deferred submit: `WorldRenderer.renderEntities` extracts **and** draws each entity in turn (`EntityRenderDispatcher.render`), exactly as 1.21.6–1.21.8, so the gate at the held-item call takes the previous frame's hook owners too and every player's hook counts for it, and the head walk stamps the owners of the bobbers in the draw list so a hook is read in the frame it appears. **Vanilla's own draw order needs no forcing, but Iris' does**: 1.21.5 has no `ENTITY_COMPARATOR` (`WorldRenderer` contains no `List.sort` at all; the sort existed only 1.21.6–1.21.8), `getEntitiesToRender` walks `ClientWorld.getEntities()` → `EntityIndex`'s `Int2ObjectLinkedOpenHashMap`, i.e. insertion order, and a player is added before the hooks it casts — the same assumption 1.21.9+ and 26.x rely on. **Iris breaks it whenever it is installed**, pack or no pack: `batchedentityrendering/mixin/MixinLevelRenderer_EntityListSorting` (priority 999, in the always-loaded `iris-batched-entity-rendering.mixins.json`) `@WrapOperation`s the `Iterable.iterator()` inside `getEntitiesToRender` and regroups the entities by `EntityType` through a `HashMap`, so the group order is an identity hash — arbitrary per launch. On 1.21.6–1.21.8 vanilla's own sort ran after it; here nothing does, so the port keeps 1.21.8's bobbers-last partition at `renderEntities`' head (priority 1500, after everything that fills or reorders the list) and the remembered spot still covers a hook the server sent before its owner or a body a mod draws after the hooks. **Check this on any version with no vanilla sort** — 1.21.9+ and 26.x rely on the same add order and Iris' mixin is still there. One `EntityRenderer` holds **one** shared render state for all its entities; the pass tag is the `VertexConsumerProvider` + `HandPass` frame + a count of pass ends (`WorldRenderer.renderEntities`' return at priority 1500 **and** `VertexConsumerProvider.Immediate.draw()`, because ImmediatelyFast replaces the buffer sources and never runs vanilla's `draw()`) **plus whether the drawing was inside the world's entity loop**, recorded at that same `renderEntities` HEAD before the first entity (as on 1.21.4: two drawings of one loop match however their collectors differ, which carries Iris' per-entity buffer sources; its shadow pass stays outside). `LivingEntityRenderState` has no `hasVehicle`: read `hasVehicle()` off the entity. `LivingEntityRenderer.clampBodyYaw` limits 85/50/0.2 and the 0.25 lift in `renderFishingLine` verified | no deferred submit: `WorldRenderer.renderEntities` extracts **and** draws each entity in turn (`EntityRenderDispatcher.render`), so an owner drawn before its hook has no extraction behind it yet — the gate at the held-item call must take the previous frame's hook owners too, and every player's hook must count for it. **And the draw order is not the add order**: `WorldRenderer.ENTITY_COMPARATOR` (`Comparator.comparing(e -> e.getType().hashCode())`, a field of `WorldRenderer`, added in 1.21.6 and gone again in 1.21.9 — 1.21.5 and 1.21.9+ have no `List.sort` in that class at all) sorts `renderedEntities` by `EntityType`'s **identity** hash (`EntityType` overrides neither `hashCode` nor `equals`), so whether the player group or the fishing-bobber group comes first is arbitrary but fixed per process; `List.sort` is stable, so the add order only decides within a group. **Take the choice away**: an optional (`require = 0`) `@Inject` at the head of `renderEntities` stably partitions the list vanilla is about to iterate so every `FishingBobberEntity` goes behind every other entity, and the bodies are then always drawn first. Hook the list at that last named method rather than wrapping the `ENTITY_COMPARATOR` GETSTATIC: the `List.sort` call sits in an unnamed lambda of the frame graph's main pass (no yarn name to select), while the head inject needs no synthetic name and runs after any sort, vanilla's or a mod's. Keep it at the same high mixin priority as the pass-end mark on the method's return (1500): a head callback of a higher priority runs later too, so it has the last word (pitfall 12). **Have the same walk stamp the rod-reading gate with the owners of the bobbers it passes** — that is what buys back the parity this family would otherwise lose. On 1.21.9+ and 26.x every entity is extracted before any is submitted, so a hook's extraction always precedes its owner's rod and those branches gate on this frame alone (`bodyHookFrame == HandPass.frame()`); here the two are interleaved, the gate has to accept the previous frame as well, and a hook's **first** frame would read no rod at all. The draw list is exactly the missing knowledge one step early: every bobber in it is extracted a moment later in the loop the head inject precedes, so stamping its owner there asserts nothing the extraction won't repeat. For the same reason the walk must be **unconditional** rather than gated on a hook having been seen — a gate would skip the one frame it exists for. The cost with no bobber in the list is a type check per drawn entity and nothing else — no write, no allocation; each bobber then adds the `Optional` `LazyEntityReference.resolve` makes inside `getPlayerOwner` (few, and escape analysis may drop them). Next to it, one statement earlier, sits `renderedEntities.sort(ENTITY_COMPARATOR)` over the same list, an O(n log n) sort that boxes two `Integer`s per comparison. Reordering is free of consequence: `entity_cutout` on the bobber's texture is opaque and depth-writing, `RenderLayer.getLineStrip()` blends but writes depth and carries alpha 255, neither is a `translucent` layer (so neither is quad-sorted at draw time), no translucent layer moves relative to another, a glowing bobber's outline copy carries the colour set for that entity right before it draws (`entity_cutout` **does** affect the outline — `.build(true)`; only `LINE_STRIP` is `.build(false)`), neither bobber layer has a dedicated allocator in `BufferBuilderStorage` (so a bobber costs its two flushes wherever it sits and moving one contiguous run is worth at most one draw call at the seams, only ever one fewer), and vanilla's own order, an identity hash, already differs in every launch — which also answers the one thing that does change, a bobber being depth-tested against entities it used to precede. Still design for both orders, because the inject is optional and a mod may keep the mixin from applying, draw the entities without going through `renderEntities`, or re-order the list from a head inject of a still higher mixin priority (replacing or cancelling vanilla's own sort does not — the inject runs after it): hooks-first means the same-pass *line placement* never fires and every body-held line falls to the remembered spot (one frame late; a `samePassOnly` line, which refuses the remembered spot, keeps vanilla's value). For that case this branch also keeps a **one-frame-old** remembered offset across every pose and mount change instead of dropping to vanilla's value. The criterion is perceptual, not metric: in that order the line trails the body by a frame at all times, so a pose change just keeps that lag, where the drop would move the origin to a different anchor for one frame and back — and a one-frame teleport reads as a flicker while a one-frame lag does not. Know the sizes before copying it — measured against the tip as drawn on the transition frame, vanilla's value is usually the **closer** of the two: leaving GLIDING or SPIN_ATTACK 2.15 blocks or more for the kept reading against 0.12 (the 90° body rotation about the feet goes in one step), waking 0.8–2.1 against 0.12, a crouch 0.54 against 0.07 (`getStandingEyeHeight` follows the pose and `getHandPos` adds `-0.1875F` while sneaking); the kept reading only wins leaving SWIMMING (1.04, `PlayerEntityRenderer`'s dropped `(0, -1, 0.3)` translate, against 2.12) and lying down (0.81 against 1.39). The yaw re-rotation keys on the pose the **reading** was taken in, which is the convention its offset is in. An older offset is still dropped — while the same-pass *rod* read is exactly what fires there, refreshing that spot at the hook's own world position every frame. One `EntityRenderer` instance holds **one** shared render state for all its entities, which is safe because extraction and drawing are back-to-back — but every per-hook decision the mod puts on that state has to be reset at `updateRenderState` HEAD, because vanilla returns early there for a hook with no player owner (so `@At("TAIL")` is skipped) and the next hook would inherit it. The pass tag is the `VertexConsumerProvider` + `HandPass` frame + a count of pass ends **plus whether the drawing was inside the world's entity loop** (recorded at `renderEntities` HEAD, before the first entity: two drawings of one loop match however their collectors differ — Iris with a pack gives every entity its own buffer source — while its shadow pass, outside the loop, keeps pairing by collector): the `GameRenderer`'s one `Immediate` takes the world's entities, the hand pass, the screen effects and every GUI picture (`EntityGuiElementRenderer` draws the inventory's player model from the same shared state), each ending in `draw()` — but **do not rely on that alone**: ImmediatelyFast (124M downloads) `@Overwrite`s `VertexConsumerProvider.immediate` to return its own `BatchableBufferSource`, whose `draw()` never calls super, so vanilla's is never executed and the counter would stay 0 for the session, silently dropping the "rod drawn after its hook" path. Mark `WorldRenderer.renderEntities`' **return** as well — at a mixin priority above 1000, so a body other mods draw at that return (Freecam's Show Player, Real Camera's classic mode) is still inside the pass, and **not** at the head, so a body drawn there (First Person Model) belongs to the pass that follows. Nothing inside `renderEntities` flushes, so a rod and a hook of that loop still share the tag. A glowing entity (or a spectator's outlines) is drawn into `OutlineVertexConsumerProvider` instead, so its rod and its hook land in different passes. Body drawn in first person: `WorldRenderer`'s entity collection skips the camera entity unless `camera.isThirdPerson()` or it is sleeping. `LivingEntityRenderState` has no `hasVehicle` (it came in 1.21.9): read `hasVehicle()` off the entity. `LivingEntityRenderer.clampBodyYaw` limits 85/50/0.2 and the 0.25 lift in `renderFishingLine` verified | verified as 1.21.11 (the same `fillEntityRenderStates`/`pushEntityRenders` order and extraction rule, one shared `OrderedRenderCommandQueueImpl` whose passes each end in `RenderDispatcher.render()` → `clear()`, `clampBodyYaw` limits, the 0.25 lift), except the held-item call's missing stack (see Injection point) | verified: deferred `render` (the renderer's submit), structured as 26.1.x: `WorldRenderer.fillEntityRenderStates` extracts in `world.getEntities()` order (the body drawn in first person when the camera entity is `isThirdPerson()` or sleeping), `pushEntityRenders` submits in the same order, and one `OrderedRenderCommandQueueImpl` (the `GameRenderer`'s; `WorldRenderer` gets it through the shared `RenderDispatcher`) serves the world's main pass and particles, `renderHand`'s flush, the hand pass, the overlays and the GUI's pictures (`EntityGuiElementRenderer`: the inventory's player model; banners, oversized items) and item atlas (`GuiRenderer.prepareItemInitially`: each newly shown item, animated or glinting ones every frame), each ended by `RenderDispatcher.render()` → `queue.clear()`: the port needs the clear count (`OrderedRenderCommandQueueImplMixin`). Features get the same queue object (`LivingEntityRenderer.render`); `PlayerEntityRenderState.id` → `ClientWorld.getEntityById`; `LivingEntityRenderer.clampBodyYaw` mirrored (85°, 50°, 0.2); the line's 0.25 lift in `renderFishingLine` | 26.3: read at `ItemInHandLayer.submitArmWithItem`'s `ItemStackRenderState.submit(PoseStack, SubmitNodeCollector, III)V` INVOKE (priority 1500, after PAL's item bones); `AvatarRenderState.id` → `ClientLevel.getEntity`; `Player.fishing` is set on the client (`FishingHook.setOwner`); the hook's line is finished at `FishingHookRenderer.submit` HEAD (`lineOriginOffset`, lifted 0.25 in `stringVertex`); `FeatureRenderDispatcher.prepareFrame` runs the custom geometry right after `submitFeatures` (Iris' shadow pass: own `SubmitNodeStorage`, own prepare); `LivingEntityRenderer.solveBodyRot` mirrored; body drawn in first person: `LevelExtractor.extractVisibleEntities` (camera entity, and detached or sleeping); Iris' `ShadowRenderer.extractVisibleEntities` (its 26.3 branch; also its 26.2 and 26.1 branches) skips only spectators, so its shadow pass draws the local body in first person too. 26.2: the same (identical layer call and descriptor, entity order, extraction rule, `solveBodyRot` limits, and the level pass's `prepareFrame` right after `submitFeatures`). 26.1.2: the same layer call and descriptor, entity order, `solveBodyRot` limits and extraction rule (in `LevelRenderer.extractVisibleEntities`: no `LevelExtractor`), but one `SubmitNodeStorage` (the `GameRenderer`'s, handed out by `FeatureRenderDispatcher.getSubmitNodeStorage()`) serves the level pass (`LevelRenderer.renderLevel`: submit, `renderSolidFeatures`/`renderTranslucentFeatures`, `clearSubmitNodes`), the first-person hand pass, the screen effects and the GUI's pictures (`GuiEntityRenderer`: the inventory's player model, id = the local player's; `GuiItemAtlas`, oversized items, banners), each ended by `renderAllFeatures`/`clearSubmitNodes` → `SubmitNodeStorage.clear()`. Collector + frame alone then let the inventory model's rod, drawn after the level's hooks in the same frame, be remembered through the last hook's pose; the port adds the storage's clear count to the pass tag |
| Run-config JVM flags (Loom-generated) | as ported (Loom 1.17.21): nothing special; the client starts on JDK 21 (and on 17, its own level) | none special | as ported (Loom 1.17.21): `-XX:StackShadowPages=32` (Loom's), no `--sun-misc-unsafe-memory-access`: starts on JDK 21, as 1.21.4–1.21.11 | as ported (Loom 1.17.21): `-XX:StackShadowPages=32` (Loom's), no `--sun-misc-unsafe-memory-access`: starts on JDK 21, as 1.21.5–1.21.11 | as ported (Loom 1.17.21): `-XX:StackShadowPages=32` (Loom's), no `--sun-misc-unsafe-memory-access`: starts on JDK 21, as 1.21.6–1.21.11 | as ported (Loom 1.17.21): `-XX:StackShadowPages=32` (Loom's), no `--sun-misc-unsafe-memory-access`: starts on JDK 21, as 1.21.9–1.21.11 | as ported (Loom 1.17.21): `-XX:StackShadowPages=32` (Loom's), no `--sun-misc-unsafe-memory-access`: starts on JDK 21, as 1.21.11 | v0.5 (Loom 1.14): none special. As ported (Loom 1.17.21): `-XX:StackShadowPages=32` (Loom's; not in Mojang's 1.21.11 JSON), no `--sun-misc-unsafe-memory-access`: starts on JDK 21 | 26.1–26.2: `--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED`; 26.3 (Loom 1.17.21): `-XX:StackShadowPages=32 --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED`. `--sun-misc-unsafe-memory-access` (from Mojang's 26.1–26.2 JSON; on 26.3 Loom adds it, and Mojang's JSON has `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED` instead) stops JDK 21 from starting; without `StackShadowPages` 26.3 crashes natively on in-world reloads |
| Loader floor (`depends.fabricloader`) | **`>=0.12.0`** at `JAVA_17`, with MixinExtras **shaded and relocated** into the jar (`io.github.llamalad7:mixinextras-common:0.5.5` → `com.andrewchik.fishingrodfix.shadow.mixinextras`, build.gradle's `shadeJar`, started from the config's `plugin`, `MixinExtrasBootstrapPlugin`). This is the one branch where *how* MixinExtras is obtained decides the floor, and all three routes are measured on 1.20.1. **(a) The loader's own copy** → `>=0.15.7`, the first loader whose MixinExtras (0.3.5) has `injector.v2`; 0.15.0–0.15.6 ship 0.3.0–0.3.3 and nothing before 0.15.0 ships one at all. **(b) Nested `mixinextras-fabric` (Jar-in-Jar)** → `>=0.14.25`: every release from 0.3.0 on declares that for itself and the loader enforces it on nested mods (measured on 0.14.23: *“Reason: [HARD_DEP fishingrodfix 0.6 {depends fabricloader @ [>=0.14.25]}, HARD_DEP mixinextras 0.3.5 {depends fabricloader @ [>=0.14.25]}]”*). A nested **0.2.2** with the deprecated v1 `WrapWithCondition` would reach `>=0.14.11` (measured to load and apply 14/14 on loader 0.14.22) at the price of running MixinExtras 0.2.2 under every loader below 0.15.0. Pinning 0.3.4, the first release with `injector.v2`, is not an option either: that artefact reverts the mod id to `com_github_llamalad7_mixinextras` with no `provides` (0.3.0–0.3.3 and 0.3.5+ all declare `mixinextras` providing the old id) and its GitHub release 404s though the tag exists, so a nested 0.3.4 would share no id with the loader's copy and two copies of `com.llamalad7.mixinextras.*` would reach the classpath — 0.3.5 is the oldest usable pin for (b). **(c) Shaded and relocated** → no MixinExtras term at all, and the floor falls to what the rest of the metadata needs: `breaks` (`>=0.12.0`, where Fabric Loader first enforces `depends`/`breaks`), with `JAVA_17` free (in `CompatibilityLevel` back to sponge-mixin 0.10.2, which loader 0.12.0 pins) and MixinExtras 0.5.5 covering Mixin 0.8–0.8.7. (c) is what this branch ships, because **Fabulously Optimized pins 0.14.23 for 1.20.1 (pack 5.4.1, stable) and 0.14.21 for 1.20 (pack 5.0.0-alpha.1)** — under both (a) and (b). **Shading has one hard ordering rule wherever the branch ships no refmap** (`Fabric-Loom-Mixin-Remap-Type: static` in the jar manifest): relocate **after** `remapJar`, never before. tiny-remapper recognises MixinExtras' annotations by their real names — `net.fabricmc.tinyremapper.extension.mixin.common.data.Annotation` hardcodes `Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;`, `…/injector/v2/WrapWithCondition;` and the rest — and with no refmap those annotations' `method`/`at.target` strings are the only place the intermediary names live, so relocating first leaves every one of them on a yarn name and the injectors silently never apply. Measured for (c): 14/14 mixins and the title screen on loaders **0.14.11, 0.14.21, 0.14.23, 0.14.25** (no loader copy — ours is the only MixinExtras) and **0.19.5** (the loader's own 0.5.5 loaded too, no duplicate-class or bootstrap complaint, same WARN set as (b); the `Initializing MixinExtras via …` line names our relocated `MixinExtrasServiceImpl(version=0.5.5)`, the copies arbitrating over one shared blackboard key). The classes Mixin exports for every target under (c) are **byte-identical** to (b)'s, on 0.19.5 and on 0.14.25, so `@Local`, the `@ModifyVariable` ordinals and every injection point bind exactly as before. Below the floor the *loader* stops first, not the mod: 0.12.0 and 0.13.3 cannot start 1.20.1 with or without mods (their tiny-remapper's ASM rejects LWJGL 3.3.1's `META-INF/versions/19/` entry — *“Unsupported class file major version 63”*) and 0.14.0 dies in its own version parser, which makes 0.14.11 the oldest loader reachable in practice. The low floor costs no behaviour either: the Fabric Mixin bucket is 0.10.0 for any floor from `>=0.12.0` to just under `>=0.16.0`, so `>=0.12.0`, `>=0.14.25` and `>=0.15.7` are one bucket. v0.5 shipped `>=0.12.0` and used no MixinExtras at all | for a port of the current reference: `>=0.14.25` at `JAVA_17` with MixinExtras bundled, `>=0.15.7` at `JAVA_17` without, `>=0.15.10` at `JAVA_21` (the first loader bundling a sponge-mixin whose `CompatibilityLevel` has `JAVA_21`); pre-rework branches ship `>=0.12.0` | `>=0.15.10` (release 21 / `JAVA_21`), as 1.21.4–1.21.11. **Settled, do not re-derive** — see the 1.21.4 column | `>=0.15.10` (release 21 / `JAVA_21`), as 1.21.5–1.21.11. **Settled, do not re-derive.** 0.15.10 is the first *loader* bundling a sponge-mixin whose `CompatibilityLevel` enum has `JAVA_21` at all — 0.13.3; the constant arrived in sponge-mixin 0.13.0, but no loader ever shipped 0.13.0–0.13.2, and 0.15.9 bundles 0.12.5, which stops at `JAVA_18`. An unknown constant is a startup `MixinInitialisationError`, not a warning (measured: the 1.21.1 jar with its floor hand-lowered dies on loader 0.15.9 with *“specifies compatibility level JAVA_21 which is not recognised”*). The same loader bundles MixinExtras **0.3.5**, the oldest usable release with `injector.v2`, so **both halves of the floor land on the same number** and bundling MixinExtras here would lower nothing — don't. Nor is `>=0.15.10` restrictive, though the margin is thin: it predates MC 1.21 by two months, and the lowest loader any Fabulously Optimized pack on the 1.21 line pins is **0.15.11** (its MC 1.21 pack, which the 1.21.1 branch's range covers) — one patch above this floor; the rest are 0.16.7 or newer | `>=0.15.10` (release 21 / `JAVA_21`), as 1.21.6–1.21.11 | `>=0.15.10` (release 21 / `JAVA_21`), as 1.21.9–1.21.11 | `>=0.15.10` (release 21 / `JAVA_21`), as 1.21.11 | as ported: `>=0.15.10` (release 21 / `JAVA_21`) | `>=0.19.0` as shipped, and deliberately **above** the minimum that merely loads. Both measurable halves are far below it: `JAVA_25` needs sponge-mixin 0.16.0 (the first whose `CompatibilityLevel` has it; 0.15.5, in loader 0.16.14, stops at `JAVA_22`), i.e. loader **0.17.0**, and MixinExtras is covered from 0.15.7. Measured on 26.3 with the floor hand-lowered to `>=0.17.0`: the jar loads and applies all ten mixins on loaders 0.18.6 and 0.17.2. It stays at `>=0.19.0` because the floor also picks the Mixin behaviour set (Step 3's note (c)): `>=0.19.0` → Fabric compat 0.17.1, `>=0.18.4` → 0.17.0, and below 0.17.1 a mixin's injectors are prepared through `INJECT_PREPARE_LEGACY` — so lowering it is a behaviour change and a green smoke test is not enough to clear it. Nor should it go *up* to `>=0.19.4` (compat 0.17.4): that turns a missing target into a hard error rather than a warning under this config's `"required": true`, which is the less forgiving side to be on. **Known gap:** the `26.1.2` branch covers MC 26.1, which predates loader 0.19.0 (2026-03-24 against 2026-04-07), and Fabulously Optimized pins 0.18.5 for its 26.1 pack and 0.18.6 for 26.1.1 — both under this floor (measured: the 26.1.2 jar with its floor hand-lowered loads and applies 13/13 on loader 0.18.5). Weigh it against the 1.20 gap, though: those two FO packs are alphas (13.0.0-alpha.3, 13.1.0-alpha.2) and its 26.1.2/26.2/26.3 packs are all on 0.19.5, whereas 1.20.1's 5.4.1 is stable |
| Sway source | `HeldItemRenderer.renderItem` `(getPitch(δ) − lerp(lastRenderPitch, renderPitch))·0.1°` about X then the same for yaw about Y, as 1.21–1.21.1 (the four fields live on `ClientPlayerEntity`) | item-renderer `*0.1°` | `HeldItemRenderer.renderItem` `(getPitch(δ) − lerp(lastRenderPitch, renderPitch))*0.1°` about X then the same for yaw about Y, as 1.21.4 (the four fields live on `ClientPlayerEntity`) | `HeldItemRenderer.renderItem` `(getPitch−renderPitch)*0.1°` about X then `(getYaw−renderYaw)*0.1°` about Y, as 1.21.5 (`lastRenderPitch`/`renderPitch`, `lastRenderYaw`/`renderYaw` are **not** renamed) | `HeldItemRenderer.renderItem` `(getPitch−renderPitch)*0.1°` about X then `(getYaw−renderYaw)*0.1°` about Y, as 1.21.6–1.21.8 | `HeldItemRenderer.renderItem` `(getPitch−renderPitch)*0.1°` about X then `(getYaw−renderYaw)*0.1°` about Y | as 1.21.11 | `HeldItemRenderer` `(getPitch-renderPitch)*0.1°`/`(getYaw-renderYaw)*0.1°` | `ItemInHandRenderer.renderHandsWithItems` (26.1.2) / `submitHandsWithItems` (26.2) → `FirstPersonHandsAndItemsRenderer.submitHandsWithItems` fed by `FirstPersonHandsAndItems.extractRenderState` (26.3); both `(getViewXRot-xBob)*0.1°`/`(getViewYRot-yBob)*0.1°` |
| Access widener | as ported: none (`@Accessor`s for `HeldItemRenderer`'s drawn items and `equipProgress*`/`prevEquipProgress*`, `GameRenderer.ticks` and `fovMultiplier`/`lastFovMultiplier`, `Camera.cameraY`/`lastCameraY`, `SpriteContents.image`, **`MinecraftClient.renderTickCounter`/`pausedTickDelta`**; `@Invoker`s for `GameRenderer.tiltViewWhenHurt`/`bobView`/`getFov` and `SpriteContents.getFrameCount`). v0.5 shipped an AW with exactly one line, `accessible method net/minecraft/client/render/GameRenderer getFov (Lnet/minecraft/client/render/Camera;FZ)D`: delete it and its `fabric.mod.json` key. **Beware**: Loom bakes the AW into the cached *named* Minecraft jar, so a jar produced by the v0.5 build shows `getFov` as `public final` — check a clean build before concluding a member is accessible | v0.5's `fishingrodfix.accesswidener` on `origin/1.20` has exactly **one** line, `accessible method net/minecraft/client/render/GameRenderer getFov (Lnet/minecraft/client/render/Camera;FZ)D` (checked 2026-09-23); an earlier reading of this row also listed `renderTickCounter`/`pausedTickDelta`, which are not in it. A v0.6 port replaces all of it with `@Accessor`/`@Invoker`s — see the 1.20–1.20.1 column | as ported: none (`@Accessor`s for `HeldItemRenderer`'s drawn items and `equipProgress*`/`prevEquipProgress*`, `GameRenderer.ticks` and `fovMultiplier`/`lastFovMultiplier`, `Camera.cameraY`/`lastCameraY`, `SpriteContents.image`; `@Invoker`s for `GameRenderer.tiltViewWhenHurt`/`bobView`/`getFov` and `SpriteContents.getFrameCount`). v0.5 shipped an AW with `fovMultiplier`/`lastFovMultiplier`: delete it and its `fabric.mod.json` key | as ported: none (`@Accessor`s for `HeldItemRenderer`'s drawn items and `equipProgress*`/`prevEquipProgress*`, `GameRenderer.ticks` and `fovMultiplier`/`lastFovMultiplier`, `Camera.cameraY`/`lastCameraY`, `SpriteContents.image`; `@Invoker`s for `GameRenderer.tiltViewWhenHurt`/`bobView`/`getFov` and `SpriteContents.getFrameCount`). v0.3 shipped an AW with `tickDelta`/`tickDeltaBeforePause`: delete it and its `fabric.mod.json` key | as ported: none (`@Accessor`s for `HeldItemRenderer`'s drawn items and equip progress, `GameRenderer.nauseaEffectTime`/`nauseaEffectSpeed` and `fovMultiplier`/`lastFovMultiplier`, `Camera.cameraY`/`lastCameraY`, `SpriteContents.image`; `@Invoker`s for `GameRenderer.tiltViewWhenHurt`/`bobView`/`getFov` and `SpriteContents.getFrameCount`). v0.5 shipped an AW with `fovMultiplier`/`lastFovMultiplier`: delete it and its `fabric.mod.json` key | as ported: none (`@Accessor`s for `HeldItemRenderer`'s drawn items and equip progress, `GameRenderer.nauseaEffectTime`/`nauseaEffectSpeed` and `fovMultiplier`/`lastFovMultiplier`, `Camera.cameraY`/`lastCameraY`, `SpriteContents.image`; `@Invoker`s for `GameRenderer.tiltViewWhenHurt`/`bobView`/`getFov`) | none, as 1.21.11 | as ported: none (`@Accessor`s for `HeldItemRenderer`'s drawn items and equip progress, `GameRenderer.nauseaEffectTime`/`nauseaEffectSpeed`, `Camera.cameraY`/`lastCameraY`, `SpriteContents.image`; `@Invoker`s for `GameRenderer.tiltViewWhenHurt`/`bobView`/`getFov`; `@Accessor`s for `fovMultiplier`/`lastFovMultiplier`) | none (26.3: `@Accessor` for `SpriteContents.originalImage` and `Camera.eyeHeight`/`eyeHeightOld`, `@Invoker` for `GameRenderer.bobHurt`/`bobView`; 26.1–26.2: the 26.3 set plus `@Accessor`s for `ItemInHandRenderer`'s drawn items and equip heights and `GameRenderer.spinningEffectTime`/`spinningEffectSpeed`) |
| Nullable annotation (mod code) | `org.jetbrains.annotations.Nullable` (compile-only; not in 1.20.1's own library list, but on Loom's compile classpath — the build is green with it) | unchecked | `org.jetbrains.annotations.Nullable` (compile-only) | `org.jetbrains.annotations.Nullable` (compile-only; Mojang ships no jspecify before 1.21.11) | `org.jetbrains.annotations.Nullable` (compile-only; Mojang ships no jspecify before 1.21.11) | `org.jetbrains.annotations.Nullable` (compile-only; Mojang ships no jspecify before 1.21.11) | `org.jetbrains.annotations.Nullable` (compile-only, CLASS retention: not needed at runtime; Mojang ships no jspecify before 1.21.11) | `org.jspecify.annotations.Nullable` (in Mojang's libraries from 1.21.11) | `org.jspecify.annotations.Nullable` |
| Loom / Java | as ported: Loom 1.17-SNAPSHOT (1.17.21), `net.fabricmc.fabric-loom-remap`, Gradle 9.7.1, **release 17** / `JAVA_17` / `depends.java >=17`, with the **Gradle daemon on JDK 21** (`gradle/gradle-daemon-jvm.properties`; Loom 1.17 needs it, the shipped bytecode is still 17), loader 0.19.5, yarn `1.20.1+build.10`. v0.5 shipped Loom 1.6-SNAPSHOT with the legacy `fabric-loom` id, Gradle 8.7, Fabric API and release 17 | 1.6-SNAPSHOT / 17 | as ported: Loom 1.17-SNAPSHOT (1.17.21), `net.fabricmc.fabric-loom-remap`, Gradle 9.7.1, release 21, loader 0.19.5, daemon JDK 21, yarn `1.21.1+build.3`. v0.5 shipped Loom 1.10-SNAPSHOT with the legacy `fabric-loom` id, Gradle 8.12.1 and release 17 | as ported: Loom 1.17-SNAPSHOT (1.17.21), `net.fabricmc.fabric-loom-remap`, Gradle 9.7.1, release 21, loader 0.19.5, daemon JDK 21, yarn `1.21.4+build.8`. v0.3 shipped Loom 1.9-SNAPSHOT with the legacy `fabric-loom` id, Gradle 8.11 and release 17 | as ported: Loom 1.17-SNAPSHOT (1.17.21), `net.fabricmc.fabric-loom-remap`, Gradle 9.7.1, release 21, loader 0.19.5, daemon JDK 21, yarn `1.21.5+build.1` (the only build). v0.5 shipped Loom 1.10-SNAPSHOT with the legacy `fabric-loom` id, Gradle 8.12.1 and release 17 | as ported: Loom 1.17-SNAPSHOT (1.17.21), `net.fabricmc.fabric-loom-remap`, Gradle 9.7.1, release 21, loader 0.19.5, daemon JDK 21, yarn `1.21.8+build.1` | as ported: as 1.21.11 (Loom 1.17-SNAPSHOT (1.17.21), `net.fabricmc.fabric-loom-remap`, Gradle 9.7.1, release 21, loader 0.19.5, daemon JDK 21), yarn `1.21.10+build.3` (1.21.9 checked with `1.21.9+build.1`) | v0.5: 1.14-SNAPSHOT / 17 (run JDK 21). As ported: 1.17-SNAPSHOT (1.17.21), plugin `net.fabricmc.fabric-loom-remap`, Gradle 9.7.1, release 21 (the develop page's level for 1.20.5+; 1.21.11 runs on Java 21), yarn `1.21.11+build.6`, loader 0.19.5, daemon JDK 21. The develop page recommended Loom 1.18 (2026-09-22), which needs Gradle on JDK 25 (its Gradle module metadata: `org.gradle.jvm.version` 25; 1.14–1.17: 21). Loom 1.17 remaps the mixin annotations in the jar itself: no refmap | 1.17-SNAPSHOT / release 25 (run JDK 25); Gradle 9.5.1 (26.1–26.2; 26.2 builds on it with Loom 1.17.21, loader 0.19.5) → 9.7.1 (26.3); 26.1.2 builds on Gradle 9.5.1 with Loom 1.17.21, loader 0.19.5 (the example mod's 26.1.2 values) |

**Maintained 1.21 branches:** `1.21.1`, `1.21.4` and `1.21.5` all use strategy A and have their own columns (1.21.4's was rewritten from its own source on 2026-09-23; the earlier v0.5-era reading of it was wrong in places — `NativeImage.getColorArgb` is public there, `getFov` already returns `float`, and `getArmHoldingRod` already exists). The swap-animation scale exists only from 1.21.11 (treat it as 1 earlier) and the equip fields are `prevEquipProgress*` on 1.21–1.21.5.

**1.21.4 vs 1.21.5** (the branch `1.21.4` was ported from `1.21.5`, 2026-09-23): the same renderer under different yarn names, plus one behavioural change. Every injection target, descriptor, INVOKE count and bytecode ordinal the fix uses is identical (verified with `javap`): `getHandPos(PlayerEntity, F, F)Vec3d` with its one `Vec3d.add(Vec3d)` and the third-person branch first; `render(FishingBobberEntityState, MatrixStack, VertexConsumerProvider, I)` with `f`/`g`/`h` as float ordinals 0–2 (LVT slots 7–9) and one `renderFishingLine`; `updateRenderState`'s own return in the owner-less branch (returns at 25 and 85, so TAIL is the last one only); `renderWorld`'s single `GameRenderer.getFov` and its `Camera.update(BlockView, Entity, ZZF)`; `renderHand(Camera, F, Matrix4f)`'s single `getFov`; `render`'s single `MinecraftClient.isFinishedLoading()`; the 5-arg `renderItem`'s two `renderFirstPersonItem` calls; `HeldItemFeatureRenderer.renderItem`'s single `ItemRenderState.render`; `VertexConsumerProvider$Immediate.draw()`'s single `layerBuffers` read. `handheld_rod.json` and `fishing_rod_cast.png` are byte-identical. What differs:
- **The nausea/portal warp**, the only behavioural change. 1.21.4 spins it by `(ticks + tickDelta) · (hasStatusEffect(NAUSEA) ? 7 : 20)°` from a private `int ticks` on `GameRenderer`, and takes its intensity straight from `lerp(tickDelta, player.prevNauseaIntensity, player.nauseaIntensity) × distortionEffectScale²`; `ClientPlayerEntity.tickNausea` already feeds `nauseaIntensity` from the portal *and* the effect, so there is no `max(…, getEffectFadeFactor(NAUSEA))`. 1.21.5 replaced the counter with `nauseaEffectTime`/`nauseaEffectSpeed` and added that `max`. The skew formula, the axis and the place the warp is applied (after the bob, onto the world projection) are unchanged. `GameRendererAccessor` exposes `ticks` here.
- **Yarn renames only** (same code): `Camera.getLastTickDelta` (1.21.5 `getLastTickProgress`), `RenderTickCounter.getTickDelta` (`getTickProgress`), `Entity.prevX/prevY/prevZ` (`lastX/lastY/lastZ`), `LivingEntity.prevBodyYaw`/`prevHeadYaw` (`lastBodyYaw`/`lastHeadYaw`), `HeldItemRenderer.prevEquipProgressMainHand`/`prevEquipProgressOffHand` (`lastEquipProgress*`), `ClientPlayerEntity.prevNauseaIntensity` (`lastNauseaIntensity`), `ModelTransformationMode` (`ItemDisplayContext`). **Not** renamed, despite looking like candidates: `Entity.lastRenderX/Y/Z`, `AbstractClientPlayerEntity.lastRenderPitch`/`lastRenderYaw`, `Camera.cameraY`/`lastCameraY`, `GameRenderer.lastFovMultiplier`.
- Nothing else. `SpriteContents` differs only in its `upload` signatures (a GPU-API change 1.21.5 made), `ItemRenderState` only in members the mod doesn't touch plus the `leftHand` flag moving into `ItemDisplayContext`, `Camera`/`ArmedEntityRenderState`/`FishingBobberEntityRenderer` only in the renames above. `FishingBobberEntityState`, `VertexConsumerProvider`, `HeldItemFeatureRenderer` and `PlayerEntityRenderState` decompile byte for byte the same.

**1.21–1.21.1 vs 1.21.4** (the branch `1.21.1` was ported from `1.21.4`, 2026-09-23): this is the
oldest renderer the current fix runs on, and the only maintained one with **no entity render states
at all** (they arrived in 1.21.2). The math, the constants and every helper class carry over
unchanged; what moved is where the hooks sit. `FishingBobberEntityRenderer.render(FishingBobberEntity,
F, F, MatrixStack, VertexConsumerProvider, I)` asks `getHandPos` and draws the catenary in one call,
so `updateRenderState` HEAD/TAIL and the separate `render` hooks collapse into that one method (reset
at HEAD, decide at an `@Inject` right after its only `getHandPos` INVOKE with `shift = AFTER`), the
per-hook decision and the hidden-line flag become static fields instead of render-state fields, and
`FishingBobberEntityStateMixin` disappears. The line offset locals are float **ordinals 4/5/6**, not
0/1/2: `render` takes two float parameters and computes the swing pair before them. `WorldRenderer`
has no `renderEntities`, so the bobbers-last order is an MEV on the entity loop's own
`Iterable.iterator()` (priority 1500, outside Iris' `@WrapOperation` at 999) and the pass end moves to
the `drawCurrentLayer()` that closes the loop. `HeldItemFeatureRenderer.renderItem` carries the
`ItemStack` and the `LivingEntity`, which makes the rod-reading gate exact and removes the
`(id, arm)` owner sets, the draw-list stamping and the two-frame window — and with them the lost
first frame of a cast. `getFov` returns a `double`, `NativeImage.getColor` is ABGR, there is no
`getArmHoldingRod`, no `getStackInArm` and no `LivingEntityRenderer.clampBodyYaw` (all three mirrored
in the mod). The nausea warp, the equip/swing constants, the display transforms and the sprite are
1.21.4's exactly.

**1.21.4 is its own jar** (checked 2026-09-23): it cannot reach down to 1.21.2–1.21.3. 1.21.4's item-model rework introduced `ItemRenderState`, `ItemModelManager` and `ArmedEntityRenderState`; none of the three exists in the 1.21.3 or 1.21.2 client jar (both still carry `ModelOverrideList`), so `HeldItemFeatureRendererMixin`'s injection target and `ThirdPersonLineOrigin`'s render-state type would not resolve at all. By the intermediary jar diff of Step 8: 1.21.3 → 1.21.4 changes 641 client-only classes, drops 901 and adds 1775 (common: 2217 changed, 192 dropped, 121 added), while 1.21.2 → 1.21.3 changes only 3 client-only classes — so a future 1.21.2–1.21.3 target would be one jar of its own, on the pre-1.21.4 item path. Upwards, 1.21.5 renames the fields above and reworks the warp. Use the closed upper bound `<=1.21.4`, not `<1.21.5`: Fabric normalises a snapshot or pre-release into a semver pre-release of the *next* release (`1.21.5-alpha.…`), which sorts below `1.21.5` and would satisfy `<1.21.5`.

**1.21.9–1.21.10 vs 1.21.11** (the branch `1.21.10` was ported from `1.21.11`): the render pipeline is already 1.21.11's (render states, `OrderedRenderCommandQueue`, `RenderDispatcher`, `EntityRenderManager`, `AtlasManager`), so the port is small. What 1.21.11 added and 1.21.10 lacks: `GameRenderer.updateCamera` (the camera is updated inside `renderWorld`: move the counter and the F1 samples, see the table), `ItemStack.getSwingAnimation` / `SwingAnimationType` and the spears' `Lancing` poses (every idle first-person item whacks), `ItemModelManager.getSwapAnimationScale`, the `ItemStack` parameter of `HeldItemFeatureRenderer.renderItem` (and `ArmedEntityRenderState.rightHandItem`/`leftHandItem`), the items atlas (`Atlases.ITEMS`; items are on the block atlas), the layer factories moved from `RenderLayer` into `RenderLayers` (`RenderLayers.lines()`; 1.21.10 has `RenderLayer.getLines()` and a `RenderLayers` that only chooses block and item layers), `Camera.getCameraPos` as the primary accessor (1.21.10: `getPos()`, with `getCameraPos()` an override delegating to it, so either name compiles), the line width in `renderFishingLine`, and jspecify (use `org.jetbrains.annotations.Nullable`).

**1.21.5 vs 1.21.6–1.21.8** (the branch `1.21.5` was ported from `1.21.8`): almost nothing. The classes the fix mixes into or reads decompile byte for byte the same in both — `FishingBobberEntityRenderer`, `FishingBobberEntityState`, `VertexConsumerProvider` (and its `Immediate`), `HeldItemFeatureRenderer`, `PlayerHeldItemFeatureRenderer`, `ArmedEntityRenderState`, `PlayerEntityRenderState`, `HeldItemRenderer`, `LivingEntityRenderer`, `PlayerEntityRenderer` — and `Camera`, `SpriteContents`, `ItemRenderState`, `FishingBobberEntity` and `AbstractClientPlayerEntity` differ only outside what the mod touches. Three things did change, and each moved one hook:
- **`GameRenderer.renderHand`** is `(Camera, float, Matrix4f)` on 1.21.5 and computes the hand FOV itself; 1.21.6 dropped the camera argument and moved that `getFov` call up into `renderWorld`'s `GlobalSettings.set(…)`. So `renderWorld` has exactly **one** `getFov` INVOKE here (the world's) and the hand-FOV MEV goes on `renderHand`'s own. The timing is unchanged (`renderHand` runs at the end of `renderWorld`, after the entities).
- **`GameRenderer.render` has no `GlobalSettings`** to hang the frame counter on and no `updateCamera`. The first two calls in its `!skipGameRender` block are `Profilers.get()` and `MinecraftClient.isFinishedLoading()`, each exactly once in the method; the port uses `isFinishedLoading`. HEAD would count the frames Dynamic FPS skips (pitfall 11).
- **`FishingBobberEntityRenderer.updateRenderState` reads as an `if/else`** for the null-owner case where 1.21.6+ has a source-level early return — but javac emits a return in that branch either way, and Mixin's `TAIL` is the *last* RETURN, so the TAIL never fires for an owner-less hook on either family. Don't take the decompiled text for a behavioural difference: `javap` the method.

And one thing that nearly changed the design: **1.21.5 has no `WorldRenderer.ENTITY_COMPARATOR`** — that `EntityType`-identity-hash sort was added in 1.21.6 and removed again in 1.21.9, and `WorldRenderer` here contains no `List.sort` at all. `getEntitiesToRender` walks `ClientWorld.getEntities()`, i.e. `EntityIndex`'s `Int2ObjectLinkedOpenHashMap` in insertion order, so vanilla draws a player before the hooks it casts, as 1.21.9+ and 26.x assume. The port dropped the 1.21.8 branch's bobbers-last partition on that reading — and the compatibility review put it straight back: **Iris' `MixinLevelRenderer_EntityListSorting` regroups the entity list by type through a `HashMap` inside `getEntitiesToRender` whenever Iris is installed, pack or no pack**, so with the partition gone Iris alone would decide the order, by an identity hash, for the whole session. Vanilla's order being fine is not the same as the order being settled. The head inject therefore keeps both jobs: the stable partition and the owner stamping the interleaved extract-and-draw needs (as on 1.21.6–1.21.8, a hook's first frame would otherwise read no rod). Everything the 1.21.8 branch says about the losing draw order still applies to what no partition can fix — a hook the server sent before its owner, a body a mod draws after the hooks, Iris' own shadow pass — including the lenient one-frame-old remembered offset.

**1.21.5 is its own jar** (checked 2026-09-23): it cannot reach down to 1.21.4. 1.21.4's `GameRenderer` has `field_47130 ticks` (an `int`) and neither `nauseaEffectTime` nor `nauseaEffectSpeed`, so `GameRendererAccessor` would not resolve and the required mixin would crash at startup; the nausea/portal warp formula differs with it. 1.21.4 also still has `prevEquipProgress*` / `prevX` where 1.21.5 has `lastEquipProgress*` / `lastX` (yarn renames, but they come with the 1.21.5 item-model generation). Upwards, 1.21.6 moves `renderHand`'s FOV and adds the entity sort (see above), so `>=1.21.5 <=1.21.5` is the whole range. Use the closed upper bound, not `<1.21.6`: Fabric normalises a snapshot or pre-release into a semver pre-release of the *next* release (`1.21.6-alpha.…`), which sorts below `1.21.6` and would satisfy `<1.21.6`.

**1.21.6–1.21.8 vs 1.21.9–1.21.10** (the branch `1.21.8` was ported from `1.21.10`): this is the boundary of the deferred renderer. 1.21.9 added the render-command queue (`OrderedRenderCommandQueue`, `RenderDispatcher`), `EntityRenderManager`/`AtlasManager`, `SpriteContents.isAnimated()`, `LivingEntityRenderState.hasVehicle` and `PlayerEntityRenderState` for mannequins. On 1.21.6–1.21.8 everything is immediate: `WorldRenderer.renderEntities` extracts **and** draws each entity in turn (`EntityRenderDispatcher.render`) instead of extracting all of them first, and it draws them in `ENTITY_COMPARATOR` order (`EntityType`'s identity hash — see the table's "Body-held rod" row: the port forces the bobbers last so the bodies come first, and still has to work both ways round for when that hook doesn't apply), one `EntityRenderer` keeps one shared render state for all its entities, the hook's line is 17 `renderFishingLine` calls into `RenderLayer.getLineStrip()` inside `render`, held items go through `HeldItemFeatureRenderer.renderItem(ArmedEntityRenderState, ItemRenderState, Arm, MatrixStack, VertexConsumerProvider, I)` (no `ItemStack`, as 1.21.9–1.21.10), and every vanilla pass of a frame draws into the `GameRenderer`'s single `VertexConsumerProvider.Immediate`, each ending with a `draw()` — the pass tag and the "drawn after its hook" path use that flush count in place of the queue clears. The interleaved extraction is the one real design change: the gate that decides whether to read a drawn rod must accept the **previous** frame's hook owners (an owner is drawn before its hook is extracted), and every player's hook must feed it, not only a body-held one.

**1.21.6–1.21.8 range check (2026-09-22, yarn/intermediary method of Step 8):** every class the fix mixes into or reads is byte-identical across the three (`FishingBobberEntityRenderer`, `FishingBobberEntity`, `FishingBobberEntityState`, `GameRenderer`, `Camera`, `WorldRenderer`, `EntityRenderDispatcher`, `HeldItemRenderer`, `HeldItemFeatureRenderer`, `VertexConsumerProvider$Immediate`, `SpriteContents`, `BakedModelManager`, `AbstractClientPlayerEntity`, `PlayerEntityRenderState`, `ArmedEntityRenderState`), so every injection point and every bytecode ordinal holds by construction. 1.21.6 → 1.21.8 changes 47 client-only classes (+6 added) and 30 common ones (mostly GUI/text, blaze3d, the happy ghast and the lava chicken disc); of those only `ItemRenderState` and `SpriteAtlasTexture` are touched by the mod, and both kept every member it uses (1.21.6's `ItemRenderState` has three extra members that 1.21.7 dropped; 1.21.8's `SpriteAtlasTexture` has one extra private method). 1.21.7 → 1.21.8 changes only 10 client classes. All 136 Minecraft/Mojang members the built jar references resolve in all three versions (checked by javap-walking the supertypes). Same 113 libraries, `javaVersion` 21 and launcher JVM arguments; `handheld_rod.json` and `fishing_rod_cast.png` identical.

**1.20–1.20.1 vs 1.21–1.21.1** (the branch `1.20` was ported from `1.21.1`, 2026-09-23): the oldest
renderer the current fix runs on. Everything 1.21.1's column says about having no render states, no
deferred submit, one `VertexConsumerProvider.Immediate` per frame and an `Iterable` entity loop holds
here too, and `HeldItemRenderer`, `HeldItemFeatureRenderer`, `SpriteContents`, `Transformation`,
`ItemRenderer.renderItem`, `LivingEntityRenderer`'s inline body-yaw clamp, the nausea warp and the rod
assets are the same, so `FirstPersonRod`, `ThirdPersonRod`, `RodSprite`, `FishingLineVisibility` and the
hand-pass half of `FishingLineOrigin` carry over unchanged. Four things moved, and one of them is silent:
- **There is no `getHandPos`** (1.21 extracted it). `FishingBobberEntityRenderer.render` builds both
  origin branches inline, so the first-person hook is an MEV on the method's only `Vec3d.rotateX(F)`,
  whose value is relative to the lerped **feet**, and the per-hook decision moves to the only
  `FishingBobberEntity.getX()D` INVOKE — the first instruction past the branches. The offset locals are
  float ordinals **6/7/8** (`v`/`w`/`x`, LVT slots 38–40), two further along than 1.21.1's, because of
  the body-yaw local `l` and the eye-height local `r`. 1.20.4's `render` has the identical LVT layout
  (checked with `javap -l`), so a 1.20.2–1.20.4 port would keep those ordinals.
- **The camera's local axes.** `rotationYXZ(-yaw, pitch, 0)` with +X **left**, so the view vector goes
  in as `(-x, y, -z)`; 1.21.0 changed it. Nothing in a build or a smoke test catches this — only the
  line's place in game does.
- **No tick manager and no `getRenderTickCounter()`.** `/tick freeze` arrives in 1.20.3, so every
  entity is drawn at the frame's own progress, and that progress has to be read from
  `MinecraftClient.paused`/`pausedTickDelta`/`renderTickCounter.tickDelta` through an accessor (the
  branch's one new mixin, `MinecraftClientAccessor`). `Camera.getLastTickDelta()` doesn't exist either.
- **The frame counter's anchor**: `Mouse.getX()D`, since 1.20.1 has no `isFinishedLoading()`.
Also: Java 17 everywhere (`release 17`, `JAVA_17`, `depends.java >=17`) with the Gradle daemon still on
21 for Loom 1.17, a loader floor of `>=0.15.7`, `new Identifier(...)` instead of `Identifier.ofVanilla`,
and `Immediate.layerBuffers` typed `Map<RenderLayer, BufferBuilder>`.

**1.20–1.20.1 is one jar, and 1.20.2–1.20.4 is not in it** (checked 2026-09-23, the yarn/intermediary
method of Step 8). 1.20 → 1.20.1: the two intermediary jars hold the same 7436 classes and **27** of them
differ — `ClickableWidget`, `PressableWidget`, `ConnectScreen`, `ReporterEnvironment`, four Realms
classes, `SharedConstants`, `MinecraftVersion`, `ClientConnection` and `ChunkStatus` (with inner
classes) — none of them a class the mod mixes into, reads or inherits from; same 88 libraries, same
`javaVersion` 17, same JVM/game arguments and main class; `handheld_rod.json`, `fishing_rod_cast.png`
and `rendertype_entity_translucent_cull.fsh` byte-identical. 1.20.1 → 1.20.4, by contrast, changes
**almost every class the fix touches** (`FishingBobberEntity`, `GameRenderer`, `Camera`,
`WorldRenderer`, `HeldItemRenderer`, `SpriteContents`, `MinecraftClient`, `RenderTickCounter`,
`LivingEntityRenderer`, `NativeImage`, `RenderLayer`, `RenderLayers`, `Entity`, `LivingEntity`,
`PlayerEntity`, `ClientWorld`, `EntityRenderDispatcher`, `BufferBuilderStorage`, `GameOptions`,
`OutlineVertexConsumerProvider`, `AbstractClientPlayerEntity`, `ClientPlayerEntity`), so no single build
can cover both. The user's decision (2026-09-23) is to build the `1.20` branch on **1.20.1**, where ~99%
of that line's players are, with a closed `>=1.20 <=1.20.1`, and to leave 1.20.2–1.20.4 on the published
`fishingrodfix-1.20-1.20.4-v0.5.jar`. A future 1.20.2–1.20.4 target would be a small delta from this
branch, not a new design: the method shapes it injects into are the same there (`render(FJZ)V`,
`renderWorld(FJLMatrixStack;)V`, `renderHand(MatrixStack, Camera, F)V`, `getFov(Camera,F,Z)D`,
`WorldRenderer.render(MatrixStack,FJZ,…)V`, `HeldItemFeatureRenderer.renderItem(LivingEntity, ItemStack,
…)`, one `Vec3d.rotateX` in the bobber renderer, `layerBuffers` a `Map`, the same camera rotation and
the same float LVT — all checked with javap on 1.20.4, 2026-09-23).

**1.20.4 coordinates (from `origin/1.20`, v0.5):**
`minecraft_version=1.20.4`, `yarn_mappings=1.20.4+build.3`,
`loader_version=0.15.11`, loom `1.6-SNAPSHOT` (the branch still carried
`fabric_version=0.97.0+1.20.4`; the v0.6 port dropped it — see Step 1).
The published `1.20–1.20.4` v0.5 jar covers that whole range via `depends.minecraft`; the branch itself
now builds 1.20–1.20.1 (see above).
