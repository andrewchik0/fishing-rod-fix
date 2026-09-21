# Fishing Rod fix
![CurseForge Downloads](https://img.shields.io/curseforge/dt/1018847?logo=curseforge)
![Modrinth Downloads](https://img.shields.io/modrinth/dt/x9ISUf1U?logo=modrinth)

This mod fixes fishing line rendering bugs in first-person view.

### Fixed bugs
- [MC-6579](https://bugs.mojang.com/browse/MC-6579) — Fishing line ignores FOV modifiers
- [MC-253540](https://bugs.mojang.com/browse/MC-253540) — The fishing line is offset from the fishing rod on aspect ratios other than 16:9
- [MC-148088](https://bugs.mojang.com/browse/MC-148088) — Fishing line doesn't adjust to the player's sneaking animation
- [MC-109884](https://bugs.mojang.com/browse/MC-109884) — While the player is moving, the fishing line isn't connected to a fixed spot on the rod
- [MC-116379](https://bugs.mojang.com/browse/MC-116379) — Punching with a cast fishing rod in the off-hand detaches the fishing line from the rod
- [MC-190324](https://bugs.mojang.com/browse/MC-190324) — Fishing line renders incorrectly when casting and retrieving a fishing rod in the left hand
- [MC-211561](https://bugs.mojang.com/browse/MC-211561) — Fishing line appears in the opposite hand when switching slots
- [MC-310980](https://bugs.mojang.com/browse/MC-310980) — The fishing line does not immediately disappear after switching from a fishing rod to another item
- [MC-311645](https://bugs.mojang.com/browse/MC-311645) — Fishing rod's line stutters when tick is frozen

### Before:
<img alt="Vanilla: the fishing line ends in mid-air next to the rod" src="docs/before.png">

### After:
<img alt="With the fix: the fishing line starts at the rod tip" src="docs/after.png">
