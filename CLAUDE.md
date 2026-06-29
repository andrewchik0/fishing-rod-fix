# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Fabric mod for Minecraft that fixes a vanilla bug where the fishing line renders in the wrong position in first-person view. The fix is applied via a single client-side Mixin that intercepts the fishing line rendering and corrects the offset based on screen aspect ratio, FOV, arm setting, and camera movement.

Known limitation: FOV effects (speed potions, etc.) are not yet corrected — see the TODO comment in `FishingBobberEntityRendererMixin.java`.

## Build Commands

```bash
# Build the mod (produces JARs in build/libs/)
./gradlew build

# Run the Minecraft client with the mod loaded
./gradlew runClient

# Generate sources and set up IDE
./gradlew genSources
```

CI builds with Java 21. Source/target compatibility is Java 17.

## Architecture

This is a purely client-side Fabric mod with a single point of logic:

- **`FishingBobberEntityRendererMixin`** (`src/client/java/.../mixin/client/`) — Mixin on `FishingBobberEntityRenderer` that `@Inject`s into `renderFishingLine` at `HEAD` with `cancellable = true`. It replaces the vanilla vertex call with one that applies a corrected world-space translation vector.

- The translation is recalculated once per 16 segments (each fishing line render call draws 16 segments). It accounts for:
  - Screen aspect ratio deviation from 16:9
  - Current FOV setting
  - Main arm / off-hand fishing rod
  - Camera yaw/pitch interpolation (item sway)
  - Crouching (eye height vs standing eye height)

- **Access widener** (`src/main/resources/fishingrodfix.accesswidener`) exposes `MinecraftClient.renderTickCounter` and its `tickDelta`/`tickDeltaBeforePause` fields, needed for smooth interpolation.

- `FishingRodFixClient` (`onInitializeClient`) is empty — all logic lives in the mixin.

## Versioning

Each Minecraft version lives on its own branch (e.g. `1.21.4`, `1.21.11`). Version numbers in `gradle.properties` (`minecraft_version`, `yarn_mappings`, `fabric_version`) must be updated together when porting to a new MC version. The `mod_version` in `gradle.properties` is independent of MC version.

## Roles

A new project role is available: `/port-version <mc_version>` — ports the mod (the current reference fix from the default branch) to a target Minecraft version, backport or forward-port. See `.claude/skills/port-version/SKILL.md`.