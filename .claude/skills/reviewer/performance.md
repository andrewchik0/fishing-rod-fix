# Performance review: within noise of zero on a whole frame's budget

The mod runs inside every frame of thousands of clients, many of them on high-refresh monitors.
Its cost must be indistinguishable from zero in any frame-time graph: a budget violation is a
**REAL BUG**, not an improvement. Judge the whole mod's cost with the change applied, not only the
delta: a cheap change on top of an expensive path still fails.

## The budget

Reference frame: **2 ms** (500 fps; players on Sodium run 240–1000 fps). Frame-to-frame jitter on
a quiet system is already tens of µs, so 0.1% of the reference frame (2 µs) cannot be seen; at
60 fps the same cost is 0.01%.

| What | Budget in the worst realistic case |
|---|---|
| A frame with no hook in view (only the every-frame hooks run) | ≤ 0.1 µs per frame: a handful of field reads and compares, no allocation of the mod's own |
| A frame with the local player fishing in first person (every-frame hooks + the origin + that hook's visibility, every pass that extracts it) | ≤ 2 µs per frame in total; at most ~1 KB of short-lived allocation |
| Each further hook in view (other players' hooks) | ≤ 0.1 µs per hook per extraction, O(1), no allocation of the mod's own, small next to vanilla's own work for that hook |
| One-off work that lands in a gameplay frame (e.g. measuring a resource pack's sprite on the first corrected frame after a reload) | ≤ 1 ms for the worst realistic input (a visible hitch otherwise); O(1) on every later frame |
| One-off work during a loading screen (resource reload, world join) | bounded, ≤ ~10 ms |
| Startup and class initialization | nothing measurable: no scanning, no I/O, no eager work beyond reading constants |
| Memory | bounded; nothing grows over a session; nothing from a previous world or resource reload stays strongly reachable |
| GPU | no new draw calls, render types, buffers or state changes (skipping vanilla's geometry is fine) |

"Allocation of the mod's own" excludes the `CallbackInfo` Mixin allocates for each `@Inject` call:
accepted on paths that run a few times per frame, not on anything hotter.

## Method

### 1. Build the call-frequency map from this branch's mixins

List every entry point from vanilla into the mod (each injector and what it calls) and put it in a
frequency class. Derive it from the checked-out branch, don't copy the example.

| Class | Runs | Tolerance |
|---|---|---|
| F0 | once (class init, mixin application) | anything cheap |
| F1 | per resource reload, world join or first use | bounded, see one-off budgets |
| F2 | per client tick (20/s) | cheap |
| F3 | every frame, even with no hook in the world | trivial: field reads, no allocation, no calls into other mods' hooks |
| F4 | per frame while the local player's hook is extracted in first person, per extraction | the per-frame budget |
| F5 | per hook per extraction or submission (every player's hook, every pass) | the per-hook budget |
| F6 | per vertex, per block, per entity of any type, per item render | **forbidden**: the mod must not hook anything this hot |

Example — the shape on 26.x when this skill was written (verify, it drifts):

| Entry point → mod code | Class | Note |
|---|---|---|
| `GameRenderer.extract` HEAD → `HandPass.onFrameExtract` | F3 | samples the HUD and the hand gate every frame, fishing or not |
| `submitArmWithItem` INVOKE → `HandPass.onHandPass` | F3 | ≤ 2 per frame, one static write |
| `FishingHookRenderer.extractRenderState` HEAD / TAIL → `FishingLineVisibility`, `ThirdPersonLineOrigin` | F5 | every player's hook; Iris' shadow pass extracts hooks a second time |
| `getPlayerHandPos` first-person `add(Vec3)` → `FishingLineOrigin.correct` | F4 | the heavy path: pose, sway, bob, projection; worked out once per frame, later extractions (Iris' shadow pass) reuse it. Measured 2026-09-22 with a scratch harness on the real JOML/PoseStack classes: ~120 ns and 576 B per computation without escape analysis (144 B with it) |
| `getPlayerHandPos` third-person `add(DDD)` → `ThirdPersonLineOrigin` (if the branch has it) | F5 | every player's hook; watch for render-state extraction or allocation per hook |
| `FishingHookRenderer.submit` `submitCustomGeometry` → visibility condition | F5 | |
| `FirstPersonRod.anchorUv` → sprite measurement | F1 | cached per sprite identity; runs inside the first corrected frame after a reload |

### 2. Inventory each entry point's per-call cost

For every entry point the scope touches (for `all`: every one), list what one call does and
estimate it. Rough orders for JIT-compiled HotSpot code on a desktop CPU — use them to find what
dominates, not as measurements:

| Operation | Cost |
|---|---|
| field read, compare, arithmetic | < 1 ns |
| inlined or monomorphic call | ~0–1 ns; interface / megamorphic call ~2–5 ns |
| small object allocation | ~5–20 ns + GC pressure (escape analysis may remove it if nothing escapes and all is inlined — don't count on it) |
| `HashMap.get` (atlas sprite lookup) | ~10–30 ns |
| `Math.sin/cos/tan` | ~15–40 ns each (`Mth.sin/cos` are table lookups, a few ns) |
| JOML `Matrix4f` multiply / invert / rotate | ~10–40 ns |
| `NativeImage.getPixel` | ~2–5 ns |
| `System.nanoTime` | ~20–25 ns on Windows |
| an enabled log call | µs (formatting, appenders, I/O) |
| creating an exception with a stack trace | µs to tens of µs |

Also note every call into vanilla code that **other mods can hook** (e.g. invoking vanilla's
`bobView`/`bobHurt`): each call runs their injections one more time per frame. Its cost is unknown
and not the mod's to control, so keep such calls to what the fix needs, once per frame at most.

### 3. Apply the worst-case multipliers

- **Passes per frame**: Iris' shadow pass extracts hooks again; portal and mirror mods (Immersive
  Portals) render extra views; replay exporters render many frames.
- **Hooks in view**: tens on busy servers and AFK fish farms.
- **Resource pack inputs**: sprite size (HD packs of 128x, 256x, 512x and more) × unique animation
  frames, for anything that scans pixels.
- **Failure states**: a disabled or fallback state must be cheaper than the normal path, never
  re-throw or re-log every frame.

### 4. Check the anti-patterns

On any F3–F5 path:

- logging without a once-guard; exceptions as control flow (a `try` block itself is free);
- `FabricLoader.isModLoaded`, `Identifier` construction, `String.format` or concatenation at
  runtime — they belong in `static final`s;
- streams, iterators over collections, capturing lambdas, varargs, autoboxing;
- a new `WeakReference` (or any cache entry) per frame instead of only when the identity changes;
- work that gives the same result every frame and could be cached by identity;
- `synchronized`, locks, `ThreadLocal`, `volatile` (the mod is render-thread only);
- reflection, `Class.forName`, `MethodHandle` lookups;
- I/O or resource-manager access; reading pixels outside the cached one-off measurement;
- work for hooks the result can't affect (e.g. computing a first-person origin for another player's
  hook), scanning all entities or players;
- allocation that scales with anything (sprite size, hook count);
- a strong reference from static state or a render state to an entity, a level or another mod's
  per-pipeline object that outlives its use: other mods keep render states past their frame (Iris'
  shadow pass keeps its last frame's, also after a disconnect).

JIT hazards:

- An injector adds bytecode to its target. In a small hot method that can push it past HotSpot's
  inlining limits (`MaxInlineSize` 35 bytes, `FreqInlineSize` 325 bytes) and slow every call, even
  when the mod does nothing: another reason F6 targets are forbidden.
- `@Inject` allocates a `CallbackInfo` per call and `@WrapOperation` an `Operation` per call: fine at
  F3–F5, not hotter. `@ModifyExpressionValue` and `@WrapWithCondition` allocate nothing.

## Measure (when static analysis can't settle it)

If the estimate for any row is within ~2× of its budget, depends on input of unknown size, or rests
on escape analysis, the verdict is **NEEDS MEASUREMENT**: say what to measure and the threshold.
The review doesn't measure (it needs the user in game, and a reviewer can't cast a rod); the report
hands the user one of these recipes.

- **Temporary probe** (never committed): accumulate `System.nanoTime()` deltas around the entry
  points over ~10 s of fishing in first person, then log the mean and max per call and per frame
  once; subtract the probe's own ~50 ns per measured call.
- **JFR** on a client started with
  `-XX:StartFlightRecording=duration=120s,settings=profile,filename=<scratchpad>/fishing.jfr`: fish
  in first person for two minutes (with a shader pack if the change touches passes), then
  `jfr print --events jdk.ExecutionSample <file>` and count the render thread's samples whose stack
  contains `com.andrewchik.fishingrodfix`. The share must stay ≤ 0.1%.
- The spark profiler, if the user has it installed.

## Report format

1. **The call-frequency table** for this branch: entry point, class, per-call estimate with its
   dominant operations, calls per frame in the worst realistic case, total.
2. **Totals against the budget**: frame without a hook, frame while fishing, per extra hook,
   one-off (with the worst input you assumed), memory.
3. **Verdict**: PASS (every row within budget with margin), FAIL (a budget violated — reported as
   a REAL BUG) or NEEDS MEASUREMENT (what to measure, how, and the threshold).
4. **Findings**, each:

```text
### [REAL BUG | WRONG FACT | IMPROVEMENT | NIT] <one-line title>
- Where: <file:line>; frequency class: <F0–F6>
- Scenario: <the worst realistic case> → <cost per call × calls per frame = total vs budget>
- Evidence: <the operations that cost; quote briefly>
- Suggested fix: <what to change, and the cost after it>
- Confidence: high | medium | low
```

A cheaper design that is still within budget is an IMPROVEMENT at most, and only worth raising if
it is also simpler; correctness and compatibility outrank shaving nanoseconds that are already noise.
