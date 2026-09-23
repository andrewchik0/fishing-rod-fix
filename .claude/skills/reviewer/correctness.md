# Default review: correctness, crash safety, fidelity, docs, quality

The checklist of the default reviewer. Work through every section that the scope touches; say in
the coverage section which ones you checked and found clean.

## 1. Crash safety (the mod must never take the game down)

- **Every injector** the scope adds or changes: its `method` and `target` descriptors exist in this
  branch's vanilla exactly (check with `javap -c -p`, not the decompiled text: synthetic and bridge
  methods, overloads and argument order only show there). `@At` selects what the comment claims:
  count the matching instructions in the bytecode; `allow` guards single-match assumptions; never an
  `ordinal` that wasn't counted in the bytecode.
- **`require` semantics**: the config's `defaultRequire: 1` turns a missing target into a startup
  crash. Hooks the fix can live without are `require = 0` and must *degrade to vanilla's line* when
  absent (check what happens if one never runs, and that the warning path fires once, not per frame).
- **Handler signatures**: MixinExtras types match the target (`@ModifyExpressionValue` returns the
  expression's type; `@WrapWithCondition`/`@WrapOperation` take the call's receiver and arguments in
  order; `@Local(argsOnly = true)` resolves to exactly one argument of that type). `@Accessor` /
  `@Invoker` names match the real field or method.
- **Config**: every mixin class is listed in the mixin config and nothing extra; `package`,
  `compatibilityLevel` matching the build's `release`; `fabric.mod.json`'s `depends` (the loader
  floor is the highest of what the compatibility level, MixinExtras and `breaks` need — not
  "whatever bundles MixinExtras", which is what shipped in v0.6 and locked the 1.20 branch out
  of Fabulously Optimized. On a Java-21 target it is `>=0.15.10`: 0.15.10 is the first loader
  bundling a sponge-mixin whose `CompatibilityLevel` has `JAVA_21` (0.13.3), and the same loader
  bundles MixinExtras 0.3.5, the oldest usable release with `injector.v2`. On the `JAVA_17`
  branch it is `>=0.14.25`, the floor the bundled `mixinextras-fabric` declares for itself.
  Don't re-derive these,
  `minecraft` range only what was validated **with a closed upper bound**,
  `java`), `environment: client`, no Fabric API.
- **Exception boundary**: every entry from vanilla into the mod's logic catches
  `RuntimeException | LinkageError`, falls back to vanilla's value, logs once (not per frame) and
  disables itself per the documented policy (until the next world or dimension). Nothing can throw
  out of a handler: casts to mixin interfaces, `@Local` values, render-state fields.
- **Nulls and absent state**: render-state fields that can be null (avatar state, hand selection,
  current swing, camera entity, level, player, the hook's owner), a frame before the first extract,
  the first frame after joining a world, a dimension change, a resource reload, a disconnect.
- **Non-finite math**: every division, `invert`, `normalize`, `tan` and FOV ratio either can't
  degenerate or is caught before it reaches the returned position (NaN in a line vertex is not a
  crash, but it is a garbage line).
- **Resource lifetimes**: nothing native (a `NativeImage`, a sprite's pixels) is read after its
  owner can be closed (a resource reload closes the previous sprites); nothing holds one past the
  call that reads it.

## 2. Fidelity to vanilla's math

The fix mirrors vanilla's renderer; a formula that compiles can still put the line in the wrong
place. For every constant and formula the scope touches, find the vanilla line it mirrors in the
decompiled source of **this branch** and compare:

- **Constants**: the first-person anchor, the base hand FOV, item sway, `ITEM_POS`, the equip dip
  scale, the swing constants, the `handheld_rod` first-person display transform (read
  `assets/minecraft/models/item/handheld_rod.json` in the sources dir), the item alpha cutout, the
  nausea/portal warp, the line's segment count where relevant. The comment next to each names its
  source: check that the source still says that.
- **Transform order**: a pose stack applies its operations to a point in reverse; a point carried
  through `translate → rotate → scale` is transformed by `scale` first. Check each chain against the
  order vanilla pushes it, and the left hand's mirroring (`invert`, the left-hand display fix).
- **Units and frames**: degrees vs radians, blocks vs 1/16 block, texture v down vs model y up,
  view space (x right, y up, z back), world vs camera-relative positions, the camera rotation's
  local axes.
- **Timing**: which partial tick each pass uses; which frame's state is read (the origin is computed
  during extraction, before this frame's hand pass); per-tick vs per-frame fields and their lerp
  endpoints (`old…` vs current); the hand pass's view of the items (they lag the inventory).
- **Which pass sees what**: the hand pass (hand FOV, bob, hurt tilt, sway, no warp) vs the world pass
  (world projection, bob, hurt tilt, nausea/portal warp). Anything applied to one pass and not the
  other must be applied or undone on the right side.
- **Fallbacks**: every early return gives vanilla's value (the same `Vec3` instance where the caller
  tests identity), and each fallback case listed in the class Javadoc really falls back.

## 3. State and lifecycle

- Static state is render-thread only and says so; nothing is touched from the network or server
  threads.
- Each piece of state is keyed to the identity it belongs to (the hook, the level, the sprite
  contents, the frame), is weak where it points at world objects, and is reset or ignored when that
  identity changes (world join, dimension change, respawn, resource reload, a new hook).
- Walk the scenarios the scope can affect, both hands and a left-handed main arm each time:
  - cast, reel in, the equip dip after each; swap hands (F) and switch slots with a hook out; drop or
    break the rod; a quick F-swap before the drawn items catch up;
  - a swing (whack) with the rod, a stab item in the other hand, using an item in either hand, riptide;
  - sneak, crawl, swim, glide (the camera's smoothed eye height), riding a boat, minecart or horse;
  - F5 (both), F1, spectator, sleeping, death, respawn, a dimension change mid-cast; the first frame
    after leaving F5 with a hook out, and first person with no rod on screen while another pass
    (Iris' shadow pass) draws the local body: the visible line keeps vanilla's value; a freecam on
    its own camera entity that draws the player's body after the hooks (Freecam's Show Player):
    the body's rod, from the remembered spot;
  - underwater and in lava (hand FOV), sprint/speed/slowness/bow and flying FOV, the FOV slider's
    extremes, nausea and portal with the screen-effect scale from 0 to 1, view bobbing on and off,
    hurt tilt;
  - aspect ratios (16:9, 21:9, 32:9, 4:3, a tall or tiny window);
  - `/tick freeze` and `/tick step` (where the game has them: 1.20.3+), high ping, other players'
    hooks, hooks with no or a non-player
    owner, several hooks at once, a resource reload (F3+T) mid-cast.
- Counters and flags: overflow, the value before the first frame, and what a skipped hook leaves
  behind.

## 4. Docs are part of the code (WRONG FACT)

- Comments and Javadoc say what the code does *now*, including the cases they enumerate (fallbacks,
  mods handled, limitations). A comment that names a vanilla method, field or constant: check that it
  exists on this branch and does what the comment says.
- `CLAUDE.md`: the architecture section matches the classes and hooks; Known limitations matches
  behavior (a case the change fixes is removed, a case it breaks is added).
- `README.md`: the fixed-bugs list only claims what the branch fixes.
- `port-version`'s procedure and per-version table, if the change alters what a port must do.

## 5. Code quality

- Reads like the surrounding code: naming, the `fishingrodfix$` prefix on everything added to
  vanilla classes, comment density (comments explain why and name vanilla's source), minimal
  visibility, `final` utility classes.
- No dead code, no duplicated logic, no second way of doing what a helper already does.
- The simplest design that fixes the most cases: values derived from vanilla's own quantities, not
  tuned constants (the one tuned constant is documented as such; a new one needs a very good reason).
- Build config: versions in `gradle.properties` coherent with each other; the jar-naming block.

## Report format

For each finding:

```text
### [REAL BUG | WRONG FACT | IMPROVEMENT | NIT] <one-line title>
- Where: <file:line>; vanilla: <file:line in the sources dir, or javap excerpt>
- Scenario: <concrete inputs/state> → <wrong output, crash, stale state>
- Evidence: <the lines that prove it; quote briefly>
- Suggested fix: <what to change>
- Confidence: high | medium | low
```

Then **Coverage**: the sections above you checked, and for each what you verified and found clean
(e.g. "descriptors of all 4 injectors checked with javap", "warp formula matches GameRenderer:412").
