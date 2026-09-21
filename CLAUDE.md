# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A client-side Fabric mod for Minecraft that fixes a vanilla bug where the fishing line starts in the wrong place in first-person view. The line origin is placed on the rod the hand pass actually draws, at vanilla's own attachment point by the tip: across aspect ratios, world and hand FOV, the rod's equip dip, swing and sway, view bob, hurt tilt and nausea warp, the camera's smoothed eye height, and resource packs that redraw the rod.

Each Minecraft version lives on its own branch and the code differs per version family (see Versioning). This branch targets **MC 26.3** (unobfuscated, official Mojang names).

## Build Commands

```bash
# Build the mod (produces JARs in build/libs/)
./gradlew build

# Run the Minecraft client with the mod loaded
./gradlew runClient

# Generate sources and set up IDE
./gradlew genSources
```

CI builds with Java 25 (`release 25`); run Gradle on a JDK 25 for 26.x branches.

## Architecture (26.3)

Purely client-side; no access widener and no Fabric API dependency (MixinExtras ships with Fabric Loader).

- **`FishingHookRendererMixin`** (`src/client/java/.../mixin/client/`) — `@ModifyExpressionValue` (`allow = 1`) on the only `Vec3.add(Vec3)` in `FishingHookRenderer.getPlayerHandPos`, i.e. the result of vanilla's first-person branch. Hooking the branch rather than the method's return leaves mods that route the player into the third-person branch (First Person Model, Real Camera) alone. Delegates to `FishingLineOrigin`. It also holds the line-visibility injectors (`extractRenderState` HEAD and TAIL, `@WrapWithCondition` in `submit`).
- **`FishingLineOrigin`** — replaces vanilla's guess (it doesn't reuse vanilla's eye-based vector): reads the state vanilla extracted for this frame's hand pass, takes the rod's line attachment point from `FirstPersonRod`, applies vanilla's item sway and own view bob/hurt tilt (`GameRendererInvoker`), and places the world point, relative to the camera and with the world-only nausea/portal warp undone, on the pixel where the hand pass draws that point. The world FOV comes from the extracted world projection's `m11` (in vanilla the camera's FOV), so mods that change the projection itself are followed too; an orthographic projection keeps vanilla's value. The rod's hand comes from the drawn items; while a rod is held but none is drawn yet (a quick F-swap with a hook out) the line stays with the hand it was last drawn in. Its class Javadoc lists the steps and every case that keeps vanilla's value.
- **`FirstPersonRod`** — vanilla's first-person rod geometry. The attachment point is vanilla's screen anchor `(±0.525, -0.1)` back-projected, at the 70° hand and an assumed 16:9 calibration aspect, onto the resting rod's model mid-plane, then carried through `applyItemArmTransform` (incl. the equip dip), `swingArm` and the `handheld_rod` display transform. A resource pack's redrawn `item/fishing_rod_cast` sprite shifts the point by the measured tip delta (visible pixels at/above the item alpha cutout, unioned over animation frames; a shift beyond `MAX_TIP_SHIFT`, the one tuned constant, is ignored as a misread), cached per `SpriteContents` identity, i.e. per resource reload.
- **`FishingLineVisibility`** + `FishingHookRenderStateMixin` / `FishingHookMixin` — hides the line (not the bobber) of a hook whose rod has left its owner's hands, in every perspective and for every player (MC-310980, MC-211561): the server removes such a hook only a tick plus the ping later, or never while the tick rate is frozen. Where `FishingLineOrigin` placed the line on the drawn first-person rod, it stays until the lowering rod is gone from the drawn items (a first-person line left at vanilla's value hides at once: vanilla would start it at the off-hand side, where no rod is). Only hooks whose owner was seen holding a rod (a flag on the hook) are hidden, so hooks cast by other items keep vanilla's line; for the local player a rod among the drawn items counts too (the own hook arrives a round trip after the cast). Decided at the end of `extractRenderState`, carried on the render state, applied by a `@WrapWithCondition` (MixinExtras v2) that skips the `lines` geometry in `submit`; an exception leaves lines visible until the next world or dimension.
- **`HandPass`** + `GameRendererMixin` / `FirstPersonHandsAndItemsRendererMixin` — whether the hand pass reached a hand last frame (frames counted at `GameRenderer.extract`, the frame marked where `submitHandsWithItems` calls `submitArmWithItem`, past any cancel; also reached while scoping, where nothing is drawn, so `FishingLineOrigin` checks scoping itself). No rod on screen → vanilla's value, whatever hid the hand (sleeping, spectator, Player Animation Library). The HUD hidden with F1 is the exception: it hides the hand but not the line, a world object, so the line stays corrected where the rod would be. At each frame's `extract` (after the tick and the camera update) `HandPass` samples the live `Hud.isHidden()` and the rest of `renderItemInHand`'s gate (a player, first person, not panoramic, awake, not spectating), plus the camera on the player, not detached and at the player's eye where `Camera.alignWithEntity` puts it (some freecam mods keep the player as camera entity and don't detach; the smoothed eye height comes through `CameraAccessor`). It latches whether a hand is drawn when only the HUD decides, from frames that prove it (a drawn hand; a missing one with the HUD and the gate open means a mod hid it); a frame with the gate closed restores the default (drawn). While the HUD is hidden (this or the previous frame) the line stays corrected if that latch holds and the gate is open this frame. So a hidden HUD never moves the line (Camerapture's photo frame and freecams keep vanilla's value; F5, sleep or spectator before F1 don't stick). Mods that keep the hand while hiding the HUD (Better F1) stay corrected through the normal path. Both injects are `require = 0`: if blocked, the line just stays vanilla, and `FishingLineOrigin` logs one warning (frame counter never ran, or no hand mark seen while fishing in first person).
- **`SpriteContentsAccessor`** — `@Accessor` for `SpriteContents.originalImage`. **`CameraAccessor`** — `@Accessor`s for `Camera.eyeHeight`/`eyeHeightOld`. **`GameRendererInvoker`** — `@Invoker`s for `GameRenderer.bobHurt`/`bobView`. **`FishingRodFix`** — the shared logger, `isRod` and `holdsRod`.

Known limitations: per-item model swaps (server `custom_model_data`/`item_model`, CIT), 3D rod models and changed display transforms are not measured; mods that re-pose the hand in the renderer without touching its extracted state (viewmodel mods such as Punchy, ViewModel, ScaleMe, Smallhands, Animatium's legacy item positions, CameraTweaks' first-person freelook, Inspect Animations), shaderpack hand sway, portal-view mods that move the camera (Immersive Portals), Polytone line offsets and Tide's own hook renderer are not covered; mods that bob or sway the hand and world passes differently (BetterHandBobbing, View Bobbing Options, No Screen Bobbing, Tweakeroo's world view-bob toggle, ShakeTweaks' bob toggles and "Disable Hand View Sway"), and Clearviews with "Disable Nausea" turned off, leave the same mismatch vanilla has; mods that drop the nausea warp without Clearviews' mod id (anti-nausea in Meteor, Wurst, LiquidBounce), and Clearviews + Iris shaders when Iris' warp wrapper wins the load order, make the line swing around the rod under nausea/portal; mods that extract a frame without rendering it (sbsshot's stereo screenshots) leave that shot, and the next frame, with vanilla's line; mods that hide one arm by cancelling its `submitArmWithItem` (Hide Hands, Exposure), or only its item (Player Animation Library during a first-person transition), leave the line at the hidden rod's tip, as do mods that hide the hand only after F1 was pressed (Camerapture's camera, Snapmatica's viewfinder, Picture Mode); conversely a mod that stops hiding the hand while F1 is on (Snapmatica's viewfinder or a freecam parked at the eye) leaves vanilla's line until F1 is released; camera-smoothing mods (SmoothCamera) drop the F1 line to vanilla's value during their F5/teleport glide; a local cast followed by a slot switch before the hook reaches the client, on a ping longer than the drawn items' few-tick lag, keeps vanilla's line until the server removes the hook.

## Dev environment gotcha

Loom writes `.idea/runConfigurations/Minecraft_Client.xml` only when it is missing and never updates it, and `.idea` is shared by every branch of the checkout. A stale config on the 26.3 branch lacks `-XX:StackShadowPages=32` (from Mojang's version JSON), and the client then dies natively on an in-world resource reload (access violation in `jvm.dll`, no `hs_err`). A 26.x config on an old branch fails to start on JDK 21 (`--sun-misc-unsafe-memory-access`). After switching version families, delete the XML and run `./gradlew ideaSyncTask`.

## Versioning

Each Minecraft version lives on its own branch (e.g. `1.21.4`, `1.21.11`, `26.3`). The version numbers in `gradle.properties` (`minecraft_version`, `loader_version`, `loom_version` — on pre-26 branches the Loom version sits in `build.gradle`'s `plugins` block instead — and `yarn_mappings` on pre-26 branches) must be updated together when porting to a new MC version. The `mod_version` in `gradle.properties` is independent of MC version.

## Roles

A project role is available: `/port-version <mc_version>` — ports the mod (the current reference fix from the default branch) to a target Minecraft version, backport or forward-port. See `.claude/skills/port-version/SKILL.md`.
