# Compatibility review: mods, shader packs, resource packs, servers, loaders

Players run this mod next to dozens of others. The rules, in order:

1. **Never crash** with another mod: no mixin conflict at load, no exception escaping at runtime.
   Any path to a crash is a **REAL BUG**.
2. **Degrade to vanilla**: where the fix can't model what another mod does, the line keeps
   vanilla's value (or vanilla's visibility), not a worse one. A newly introduced worse-than-vanilla
   outcome is a REAL BUG unless the user accepts it as a documented Known limitation.
3. **Don't break the other mod**: chain with it instead of replacing or cancelling what it hooks.
4. **No regressions**: a mod, shader pack or resource pack handled before must still be handled. A
   Known limitation that changed without `CLAUDE.md` changing is a **WRONG FACT**.

## A. Mixin mechanics (every injector the scope adds or changes)

- **Chain, don't replace.** Use MixinExtras injectors that compose with other mods
  (`@ModifyExpressionValue`, `@ModifyReturnValue`, `@WrapOperation`, `@WrapWithCondition`) and
  non-cancelling `@Inject`. No `@Redirect` (only one mod can redirect a call; the next one fails),
  no `@Overwrite`, no cancelling vanilla work other mods hook (the old cancel-and-redraw of the line
  dropped other mods' line tweaks).
- **Contested targets.** For each target, find other mods that hook the same method. A `@Redirect`
  or `@Overwrite` there can remove our injection point, and with `require ≥ 1` that is a startup
  crash for everyone with both mods. Keep required hooks to what the fix can't work without, on the
  narrowest target; everything else `require = 0` with a vanilla fallback.
- **Cancels upstream.** Another mod cancelling at a method's HEAD skips everything after it. Place
  hooks where that is seen and harmless (the hand-pass mark sits past such cancels by design).
- **Names.** Everything added to a vanilla class is `@Unique` and prefixed `fishingrodfix$` —
  fields, methods, interface methods, `@Accessor`/`@Invoker` methods.
- **Priority.** The default, unless a documented conflict requires otherwise.
- **Mod-id special cases.** An `isModLoaded` check uses the exact id from that mod's
  `fabric.mod.json`, is cached in a `static final`, and documents what it assumes about the mod's
  configuration (e.g. Clearviews' default "Disable Nausea").
- **Calling hooked vanilla methods.** Invoking vanilla methods that other mods inject into (the
  view-bob and hurt-tilt invokers) runs their injections once more per frame. A stateful injection
  (smoothing over time, counters, side effects) would misbehave: check the known bob and camera
  mods' injections into those methods.
- **Read, don't re-derive.** Reading the value vanilla already computed (the extracted projection,
  camera, hand state) follows other mods' changes automatically; recomputing it from options or
  player fields silently ignores them. A re-derived value is a compatibility smell.
- **No dependencies.** No Fabric API or libraries; MixinExtras comes with the loader floor.

## B. What other mods can change, and what the fix reads

Use this to spot interactions the matrix doesn't list. The fix reads:

| Input | Examples of mods that change it |
|---|---|
| which branch of the hand-position method vanilla takes | full-body first person, third-person camera mods |
| the camera: entity, position, rotation (incl. roll), detached, panoramic | freecam, replay, camera roll/tilt, portals, VR |
| the world projection (world FOV) | zoom, FOV mods, orthographic cameras, screenshot mods |
| the hand FOV | hand-FOV options in viewmodel mods, shader packs |
| the hand pass's extracted state (items, equip heights, swing, sway inputs, use state) | animation libraries, swap-animation mods |
| the hand pass's pose applied in the renderer, not in the state | viewmodel mods, first-person animation mods |
| the body's render state and model pose (where the branch corrects the third-person origin) | player animation mods, model mods, scale mods |
| view bob and hurt tilt, on both passes or one | bobbing and shake mods |
| the nausea / portal warp in the world pass | anti-nausea mods, clients, Iris |
| whether and when the hand pass submits a hand; frames that extract without rendering | hand-hiding mods, stereo/screenshot mods |
| the HUD-hidden flag and the rest of the hand gate | F1 mods, photo mods |
| the rod's sprite, model and display transform | resource packs, CIT, server item models |
| the item class of the held and drawn items (`FishingRodItem`) | modded rods, server custom items |
| how many times a hook is extracted per frame; its owner | shader shadow passes, portal views, replay |
| the line's geometry submission (`lines` render type) | line-rendering mods, Polytone |

## C. Known ecosystem (observed — verify against the source, extend it)

Status: **H** handled by design (see where), **L** documented Known limitation in `CLAUDE.md`,
**U** unverified (check it when the change touches what it changes). A row is only as good as the
branch it was checked on: the mod may not exist for this version, or may have changed.

### Renderers and performance mods

| Mod | What it changes that matters | Status |
|---|---|---|
| Sodium | replaces terrain rendering; entities still go through vanilla's renderers | U: check that hook extraction and `submit` still run through `FishingHookRenderer`. 26.1.2 (`26.1.2/stable`, checked 2026-09-22): no mixins on `FishingHookRenderer`, `ItemInHand*` or `SubmitNodeStorage`; its `GameRendererMixin` only copies `renderLevel`'s projection |
| Iris (+ any shader pack) | draws the hand with its own hand renderer; a shadow pass extracts hooks a second time, into its own `LevelRenderState` and `SubmitNodeStorage`, and keeps that frame's render states until its next shadow frame (also after a disconnect: the pipeline survives it); wraps the nausea warp | H: its shader context rides on the submit nodes, not on the collector (`entity_render_context/MixinEntityRenderDispatcher` only `@Inject`s at `EntityRenderDispatcher.submit`'s `pushPose`/`popPose` to set `CapturedRenderingState`'s entity id, and the node classes capture it in their constructors, e.g. `MixinCustomGeometrySubmit`); nothing in Iris implements, wraps or `@ModifyVariable`s a `SubmitNodeCollector`, and the `BufferSourceWrapper` it puts around every entity's buffer source on immediate-mode versions survives only as an unused import there (Iris 26.1 branch, checked 2026-09-23), so the pass tag is the same collector object with a shader pack as without one and the body-held correction keeps working (the first-person origin never uses the tag). Its hand renderer reaches the hand-pass mark; frames are counted per frame, not per call (`HandPass`), and the first-person origin is worked out once per frame and reused by the shadow extraction (`FishingLineOrigin`); a body-held hook's render state drops its owner at `submit` (`ThirdPersonLineOrigin`); its shadow pass's `ShadowRenderer.extractVisibleEntities` skips only spectators, so with entity shadows it draws the local body with its rod, then the hook, also in first person: a first-person line left at vanilla's value with the camera on the player takes only a same-pass rod, so the shadow's line sits on the shadow body's rod and the visible one keeps vanilla's value (Iris 26.3 branch, checked 2026-09-22; its 26.2 and 26.1 branches skip only spectators too, checked 2026-09-22). L: Clearviews + Iris' warp wrapper when Iris wins the load order: `MixinModelViewBobbing` (not `MixinGameRenderer`) `@WrapOperation`s `renderLevel`'s warp `Matrix4f.rotate`/`scale`, the calls Clearviews wraps, and with a pack in use moves bob and warp into the model view without calling the original (Iris 26.2 and 26.3 branches, checked 2026-09-22). 26.2 (1.11.4, observed 2026-09-22): `HandRenderer` calls `ItemInHandRenderer.submitHandsWithItems` (the mark fires); the shadow pass runs at `LevelRenderer.render`'s `addMainPass` call, before Iris' hand pass (`renderSolid` inside the main pass, `renderTranslucent` at the end of `render`), so the origin cached at the main extraction still holds there (on 26.2 it reads the live player and renderer, which change only in ticks); pipelines are kept per dimension and destroyed only on a dimension change or shader reload (`Iris.getCurrentDimension()` returns the last dimension while no level is loaded). 26.1 (1.11.4+26.1, branch `26.1` bff1e69, checked 2026-09-22): `HandRenderer` → `iris$renderHandsWithCustomRenderer` → `renderHandsWithItems` (the mark fires); with a pack, vanilla's `renderItemInHand` call is `@Redirect`ed to a no-op and the hand's closing `renderAllFeatures` is replaced by `HandRenderer.endRender()`, which clears Iris' own storage inside the main pass after `submitEntities`; the shadow pass runs at `renderLevel`'s `addMainPass` INVOKE with its own `LevelRenderState`, `SubmitNodeStorage` and `FeatureRenderDispatcher`, cleared by its own `renderAllFeatures` before the main pass (so 26.1.x's global clear count still separates it); `MixinModelViewBobbing` as on 26.2, so the Clearviews L holds |
| Sodium Extra, Reese's Sodium Options | option toggles | U: check whether any toggle touches view bob, FOV or the hand |
| ImmediatelyFast | batches immediate-mode rendering | U: the line is a deferred custom-geometry submit. 26.1 (checked 2026-09-22): `FeatureRenderDispatcher` injects only call `endLastBatch`; nothing touches `clear` |
| Anything hooking `SubmitNodeStorage.clear` / `FeatureRenderDispatcher.clearSubmitNodes` (26.1.x) | 26.1.x' pass tag counts the storage's clears (`SubmitNodeStorageMixin`, MEV on `clear()`'s only `submitsPerOrder` read) | H: no known mod hooks it (checked 2026-09-22: the mods in this matrix); the count stops only if `clear()` is overwritten or cancelled at HEAD, and then a rod drawn after its hook just isn't remembered |
| Entity Culling, MoreCulling | skip entities that aren't visible | U: a culled hook isn't extracted, like vanilla; the visibility decision rides on the render state |
| Lithium, FerriteCore, ModernFix, C2ME, BadOptimizations | logic, memory, loading, render caches | U: low risk; check if the change touches sprites/atlases (ModernFix) or cached render state (BadOptimizations) |
| Dynamic FPS | throttles or skips frames while unfocused | H: 26.1 (3.11.5, branch `26.1`, checked 2026-09-23): a `@WrapMethod` on `Minecraft.renderFrame` drops the whole frame while it throttles (unfocused: 1 fps target, idle after 5 min: 10 fps), so `GameRenderer.extract`, and with it the frame counter, never runs on a skipped frame. Residual: the counter sits in `extract`, a sibling call of `render` in `renderFrame`, so a mod that skipped only `render` or the level pass would leave counted frames with no hand pass (vanilla's line, not a wrong one) |
| Distant Horizons | extra LOD terrain passes | U: check that its passes don't extract entities |
| VulkanMod | replaces the render backend | U |

### Camera, FOV, view

| Mod | What it changes that matters | Status |
|---|---|---|
| Zoom mods (Just Zoom, Ok Zoomer, Zoomify, …) | narrow the world FOV | H by design: the world FOV is read from the extracted projection. U per mod: check it zooms through the camera or projection and leaves the hand FOV and pose alone. Zoomify 2.16.2 (branch `main`, checked 2026-09-23): `@ModifyReturnValue` on `Camera.calculateFov` and `Camera.calculateHudFov`, both upstream of the `CameraRenderState` the fix reads (its `hudFov`, and the projection `m11` the world FOV comes from), plus an MEV on the view bob's `getInterpolatedBob`: H by construction, whatever the load order |
| Better F1 Reborn | keeps the hand with the HUD hidden | H: normal path |
| Freecam mods | camera on another entity, or detached / away from the eye | H: the first-person origin keeps vanilla's value (`FishingLineOrigin`, `HandPass`). Freecam (MinecraftFreecam; main branch, 26.3 build 1.5.0-alpha.1; checked 2026-09-22) keeps the camera type first person, makes its own `FreeCamera` the camera entity and with Show Player (default on) adds the local player at `LevelExtractor.extractVisibleEntities` RETURN (on 26.1.x at `LevelRenderer.extractVisibleEntities` RETURN: the same main-branch source builds 1.5.0-alpha.1+mc26.2 and +mc26.1.2), after the hooks: H, the line takes the body's rod from the remembered spot, one frame late (`ThirdPersonLineOrigin`; the same-pass-only rule applies only with the camera on the player). L: with Show Player off, a remembered spot (an F5 view, Iris' shadow pass) can still place the line where the hidden body's rod was |
| First Person Model, Real Camera | send the local player into the hand-position method's third-person branch while they draw its body (First Person Model adds its body offset at the method's return) | H: the first-person hook doesn't see them. Where the branch also corrects the third-person origin, check that it follows the body they draw. Real Camera (26.3/dev source; no 26.3 build yet, 26.2 is 0.7.8-beta; checked 2026-09-22): `@WrapOperation` on `getPlayerHandPos`' `CameraType.isFirstPerson` while `RealCameraCore.isRendering()`; at `LevelRenderer.submitEntities` RETURN its classic mode submits the camera entity into the level collector (after the hooks: the remembered spot), its binding mode into its own `RoutingSubmitCollector` (L: the line starts at the spot remembered from another pass: Iris' shadow pass every frame with entity shadows, else an earlier F5 view; vanilla's value until then). 26.1 (checked 2026-09-22): First Person Model 2.7.2 (`main`): `@Redirect` of `getCameraType` and a RETURN offset in `getPlayerHandPos`, `submit` HEAD, `submitArmWithItem` HEAD cancel while items are hidden, `LevelRenderer.extractVisibleEntities` HEAD adds the body first (H: its rod matches before the hook). Real Camera 0.7.8-beta (`26.1/dev`): `isFirstPerson` wrap, `renderHandsWithItems` HEAD cancel; classic mode submits the body into the level storage at `submitEntities` RETURN, after the hooks (remembered, a frame late), binding mode through its own `RoutingSubmitCollector` (the L above: the spot remembered from another pass). With the inventory open (26.1.x' shared storage) the GUI model's rod falls after a clear and isn't remembered |
| Animatium | rewrites the eye height inside the hand-position method; legacy item positions; "Minimal View Bobbing" | H: vanilla's vector isn't reused. L: legacy item positions; its equip animation `@ModifyArg`s the main-hand `submitArmWithItem` call's stack with its own copy (26.3/development, 4.4.1; checked 2026-09-22), which isn't followed (the extracted `mainHandItem` is read); on 26.1–26.2 the same `@ModifyArg` sits on `renderHandsWithItems`' `renderArmWithItem` (26.1) / `submitHandsWithItems`' `submitArmWithItem` (26.2) main-hand call (26.1/development and 26.2/development, checked 2026-09-22), and the renderer's `mainHandItem` field is read. L: Minimal View Bobbing drops `renderLevel`'s `bobView` only (world-only bob; observed on 26.2). 26.1 (4.4.1, `26.1/development`, checked 2026-09-22): `getPlayerHandPos` `@ModifyArgs`/`@WrapOperation`/MEVs, `@ModifyArg`s in `extractRenderState` and `lambda$submit$1`, no `Vec3.add` redirect; Minimal View Bobbing and legacy positions not rechecked |
| Shoulder Surfing Reloaded, Better Third Person | third-person cameras | U: where the branch corrects the third-person origin, check it with their camera and body; line hiding applies in every perspective |
| Do a Barrel Roll, Camera Overhaul | camera roll and tilt | U: if only the world pass gets it, same class as bobbing only one pass (a candidate L) |
| CameraTweaks (first-person freelook), SmoothCamera (F1 glide), Immersive Portals | re-pose the camera or hand | L |
| Camerapture, Snapmatica, Picture Mode | hide the hand around F1 | L |
| Replay Mod, Flashback | camera on another entity in replays; offscreen export renders | U: expect vanilla's value in replays; exports multiply passes (performance) |
| Vivecraft | VR: the rod follows a tracked controller and it has its own line origin | U, high risk: check its changes to `FishingHookRenderer` against our required injector. 26.2 (1.3.15, observed 2026-09-22): `getPlayerHandPos` HEAD cancel in VR passes plus a `Mth.lerp` wrap, `Vec3.add` untouched: no load conflict; the VR body path stays U. 26.1.2 (1.3.15, `Multiloader-26.1`, checked 2026-09-22): as 26.2 |
| Tweakeroo | world-only view-bob toggle; freecam; "Disable Offhand Rendering" | L: the toggle. U: its freecam (26.2: cancels the hand pass → vanilla's value). L: Disable Offhand Rendering cancels the off hand's `submitArmWithItem` (as Hide Hands; observed on 26.2). 26.1 (0.28.10, sakura-ryoko `LTS/26.1`, checked 2026-09-22): the off hand's `renderArmWithItem` HEAD cancel (L); its `tick` `getItemSwapScale` wrap is followed through the renderer fields |
| BetterHandBobbing, View Bobbing Options, No Screen Bobbing, ShakeTweaks | bob one pass differently from the other | L |
| Clearviews 2.x (`clearviews`) | removes the nausea warp from the world pass | H: the warp isn't undone (assumes "Disable Nausea" on, its default). L: with it off. 26.2 (2.1.7, observed 2026-09-22): `@WrapOperation` on `renderLevel`'s warp `Matrix4f.rotate`/`scale`, `disableNausea` defaults to true; no 26.3 build yet (2026-09-22). 26.1.2 (2.1.7+26.1.2, branch `clearviews-fabric-26.1.2-custom-config`, checked 2026-09-22): the same wraps (`rotate` ordinals 0/1, `scale`), `disableNausea` defaults to true |
| Clearview 1.x (`clearview`) | removes the nausea effect client-side | H: nothing to special-case |
| Anti-nausea in Meteor, Wurst, LiquidBounce | drop the warp without Clearviews' id | L |
| Pixelshot | large screenshots flag only the render state; its orthographic view replaces only the world pass's copy of the projection | H: a large screenshot behaves like F1. Not handled: the ortho view isn't seen (noted in `FishingLineOrigin`, not in `CLAUDE.md`) |
| sbsshot | extracts a frame without rendering it | L |

### Hand, viewmodel, animation

| Mod | What it changes that matters | Status |
|---|---|---|
| Punchy, ViewModel, ScaleMe, Smallhands, Inspect Animations | re-pose the hand in the renderer without touching its state | L. Punchy's 26.2 build (2.8a, closed source) is unchecked: check it doesn't redirect `getPlayerHandPos`' `Vec3.add` (our required injector) |
| Mods that change `ItemInHandRenderer.submitHandsWithItems`' (26.2; `renderHandsWithItems` on 26.1.x) locals or arguments, or `evaluateWhichHandsToRender` (26.1–26.2) | swing, sway, equip dip or hand selection as drawn | L: on these versions the hand pass reads the live player and its renderer's fields and the hand selection is mirrored, so changes there aren't followed; changes to the renderer's fields (Player Animation Library's and Animatium's `tick` hooks) are |
| ShieldDisruptor, InteractiveStuff (26.2) | cancel `renderItem` for configured items; scripted re-posing | L: the item-only hide and re-pose classes |
| Player Animation Library (Emotecraft, Better Combat, …) | cancels the hand pass; hides only the item during a first-person transition | H: no hand pass → vanilla; its first-person model mode fakes `Camera.isDetached` in `LevelExtractor.extractVisibleEntities` (main branch, 26.3), so the body is drawn earlier in the level pass and the line sits on its rod (same-pass read). L: the item-only hide. 26.1 (1.2.6, branch `26.1`, checked 2026-09-22): first-person model mode by an MEV on `Camera.isDetached` in `LevelRenderer.extractVisibleEntities` (the body is extracted with the other entities, before the hooks: same-pass read); `renderHandsWithItems` HEAD cancel; item-only cancel in `renderItem`; `ItemInHandLayer` injects at default priority (ours, 1500, runs after) |
| Hide Hands, Exposure | cancel one arm's `submitArmWithItem` (`renderArmWithItem` on 26.1.x) | L. Hide Hands 26.1.2-4.6 unchecked (only a 26.2 source branch) |
| Not Enough Animations | mostly third-person animations | U: check its first-person options |
| 3D Skin Layers | 3D outer skin layer on the first-person arm | U: shouldn't move the item |
| Entity Model Features / Entity Texture Features, Fresh Animations (pack) | entity models and textures | U: the bobber and third-person players; the first-person hand is not expected to change |

### Fishing mods and modded rods

| Mod | What it changes that matters | Status |
|---|---|---|
| Tide | its own hook renderer | L |
| Enchanted Fishing Line (`enchanted_fishing_line`) | reads the line origin for its rope simulation, and ships its own copy of an older version of this fix as a cancellable `@Inject(RETURN)` on the hand-position method: with both loaded the corrections stack | **Declared incompatible**: `"breaks": { "enchanted_fishing_line": "*" }` in `fabric.mod.json` on every branch, no EFL-specific code (user decision 2026-09-22); check the entry is there. Why (26.2 1.0.0, checked 2026-09-22; builds also exist for 26.1–26.1.2 and 1.21.11, none for 26.3): the RETURN inject is a verbatim copy of v0.5's correction with no `fishingrodfix` check. With v0.5 both RETURN injects cancel and the first wins (one correction); with v0.6's first-person MEV it applies on top (off the rod away from 16:9, with FOV modifiers, turning, crouching). Its rope: `lambda$submit$1` HEAD cancel plus its own `submitCustomGeometry` at `submit` TAIL reading `lineOriginOffset`, so the body-held offset and the line hiding don't reach it |
| Better Fishing (`better_fishing`) | doesn't touch the origin | H (observed 2026-09-21) |
| Polytone | line offsets | L. 26.1 (checked 2026-09-22): `stringVertex` HEAD argument change (the L) and a colour `@ModifyArg`; its `SubmitNodeStorageMixin` targets only `submitModel` HEAD |
| Rods from other mods | a `FishingRodItem` subclass counts as a rod; anything else casts hooks that keep vanilla's line and are never hidden | H. L: a modded rod with its own texture or model isn't measured (only `item/fishing_rod_cast` is) |

### Resource packs

| Pack | What it changes that matters | Status |
|---|---|---|
| Vanilla | the calibration itself | H |
| Programmer Art | older rod texture | U: check its cast-rod tip against vanilla's |
| Faithful 32x | rod tip at (14.75, 1.75)/16 | H: measured tip shift |
| Faithful 64x, Default HD 128x, other texture-only redraws | redrawn rod, higher resolution | H by design while the tip moves less than `MAX_TIP_SHIFT`. U per pack; high resolutions: the measurement's one-off cost (`performance.md`) |
| Animated cast-rod textures | several frames | H: unique frames are unioned |
| 3D rod models; packs that change `handheld_rod`'s display transform; per-item model swaps (CIT Resewn, Polytone, server `custom_model_data` / `item_model`) | a different model or pose | L |
| Popular packs that don't touch the rod (Low on Fire, Low Shield, …) | — | no check needed |

### Shader packs (through Iris)

Complementary Reimagined / Unbound, BSL, Bliss, Mellow, Photon, MakeUp and the rest share Iris'
pipeline, so the Iris row applies to all of them. Pack-specific:

| Behavior | Status |
|---|---|
| hand sway or other hand transforms in the pack's hand program | L |
| TAA jitter of the projection | U: jitter should only touch off-diagonal terms, not the `m11` the world FOV is read from; verify |
| hand FOV or "hand depth" changed in the pack | U |

### Servers and gameplay

| Situation | Status |
|---|---|
| high ping, `/tick freeze` | H: line hiding, the remembered rod hand |
| a cast, then a slot switch before the hook reaches the client, on a ping longer than the drawn items' lag | L |
| server custom rods (`custom_model_data`, `item_model`) | L: the model isn't measured. H: hooks cast by non-rod items keep vanilla's line |
| other players' hooks | H: line hiding; the first-person origin is only for the local player's own view; where the branch corrects the third-person origin, it applies to every player's body |

### Loaders and platforms

| Loader | Status |
|---|---|
| Fabric Loader | the declared floor must ship every Mixin / MixinExtras feature used (`correctness.md`) |
| Quilt | U: check nothing loader-internal is used beyond `FabricLoader` |
| Sinytra Connector (Fabric mods on NeoForge) | U |

## D. Procedure

1. **Touch points.** From the scope, list the vanilla targets (injectors), the vanilla state and
   methods read or invoked, and the behaviors that changed (what now falls back, is newly corrected,
   or is hidden).
2. **Matrix pass.** For every row in section C whose "what it changes" overlaps a touch point (and
   every input in section B the change reads), re-derive the outcome under the change: crash or
   conflict at load · wrong line (worse than vanilla) · vanilla's line (fallback) · corrected ·
   unaffected. Compare with the row's status: an H row must still hold; an L row's text must still
   be accurate; a U row the change makes relevant gets a verdict with evidence, or stays U with what
   would settle it.
3. **Discovery** — only for touch points sections B and C don't cover yet (a new injector target, a
   vanilla method or piece of state the mod didn't read or invoke before), or when the scope is
   `all`. Otherwise skip it and say so: the matrix already stands for those targets. Find the mods
   the matrix doesn't know that touch the new points:
   - Modrinth, by downloads, for this branch's MC version:
     ```sh
     curl -s -G https://api.modrinth.com/v2/search \
       --data-urlencode 'query=<term>' \
       --data-urlencode 'facets=[["versions:<mc>"],["categories:fabric"],["project_type:mod"]]' \
       --data-urlencode 'index=downloads' --data-urlencode 'limit=20'
     ```
     Terms: fishing, rod, zoom, fov, camera, bobbing, hand, first person, viewmodel, animation,
     nausea, freecam. For packs use `project_type:shader` or `project_type:resourcepack` (fishing
     rod, 3d, items, hd).
   - Mixin sources on GitHub that target the same members (search the vanilla method or class name
     together with `@Mixin`); read the source where the project is open.
   - Closed-source candidates with many downloads: list them under NEEDS JAR.

   **Researching a mod** (from discovery, or a row whose hooks section C doesn't record yet) — keep
   it cheap; each step can end the research:
   - *Which build?* One request:
     `curl -s -G "https://api.modrinth.com/v2/project/<slug>/version" --data-urlencode 'game_versions=["<mc>"]' --data-urlencode 'loaders=["fabric"]'`.
     If it's empty, drop `game_versions` and take the Fabric build for the nearest MC version: the
     fix is ported to other versions and the mod may catch up, so its hooks still count (say "no
     build for <mc> yet, checked <version>"). Skip a mod only if it has no Fabric build for any MC
     version this mod ships for (its version branches). A mod that is only on CurseForge or GitHub:
     check its releases there.
   - *What does it hook?* Only the mod's mixin config(s) (named in its `fabric.mod.json`), on the
     branch or tag of that build, and then only the mixin classes that target the same vanilla
     classes as the scope. Don't read the rest of the repo.
   - *Already known?* A section C row that records the mod's hooks for this MC version counts as
     researched: don't research it again unless the scope touches something the row doesn't cover.
4. **Beyond the review** — list these as open items, don't do them (they need the user's OK):
   - *Jar inspection* (NEEDS JAR): download the jar, then read its `fabric.mod.json` (mod id, mixin
     configs), the mixin configs, and `javap -c -p` the mixins that target the same classes:
     injector type, target, priority, `require`, cancels. Say what you would check in it.
   - *Load test*, when loading together is the open question: the smoke-test client
     (`port-version` Step 7, scratch game dir) with the other mod and its dependencies in
     `<game dir>/mods/`. Pass: a `Mixing` line for each of our mixins and no mixin error or conflict
     in the log. It runs third-party code and proves loading only.
5. **Outcomes.** A crash or conflict: REAL BUG. A regressed H row: REAL BUG. A new worse-than-vanilla
   line: REAL BUG unless the user accepts it as a Known limitation. A limitation that changed without
   `CLAUDE.md` changing: WRONG FACT. A matrix row found wrong: propose the edit to this file
   (IMPROVEMENT).

## Report format

1. **Touch points**: targets, inputs read, behaviors changed.
2. **Rows checked**: each matrix row or input you evaluated, its outcome under the change, and the
   evidence (code line, the other mod's source); rows you left U, with what would settle them.
3. **Discovered**: mods found in step 3, with what they hook.
4. **Findings**, each:

```text
### [REAL BUG | WRONG FACT | IMPROVEMENT | NIT] <one-line title>
- Where: <file:line>; other side: <mod / pack, version, its source file:line or mixin>
- Scenario: <which mods, packs or settings> → <crash, conflict, wrong line, doc mismatch>
- Evidence: <the lines that prove it; quote briefly>
- Suggested fix: <what to change, or the limitation text to add>
- Confidence: high | medium | low
```

5. **NEEDS JAR**: name, Modrinth URL, size, and what you would check in it.
6. **Matrix updates**: for every mod you researched, a ready-to-paste section C row: the mod (its
   id), the MC version and mod version checked, what it hooks (class, method, injector; whether it
   redirects, overwrites or cancels), the source link, and the status. Once added to this file, the
   next review doesn't research that mod again.
