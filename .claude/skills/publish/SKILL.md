---
name: publish
description: Publish Fishing Rod Fix release jars to CurseForge and Modrinth. Use when asked to publish, release or upload a mod version (one branch, several, or all). Works out what is new since the last published release from the code and commits, shows the user a consumer-facing changelog and does nothing else until they approve it, asks whether to change the mod's main-page description (showing the current and proposed text), checks the jars, then uploads — always CurseForge first, then Modrinth.
argument-hint: "[branches|all] [notes]"
---

# Publish Fishing Rod Fix to CurseForge and Modrinth

> **Maintenance note (keep this skill version-agnostic).** Like `port-version` and `reviewer`,
> this skill is checked into the repo and copied onto every version branch. Don't bake version
> numbers or "the current release is vX.Y" into it: derive them from the branches, the jars and
> what is already published. Propagate edits with
> `git checkout <default_branch> -- .claude/skills/publish`.

This run: `$ARGUMENTS`

- *(nothing)* — every version branch whose `mod_version` isn't published yet for its Minecraft
  versions.
- `all` or a list of branches (`26.3 1.21.11`) — exactly those.
- anything else is notes for the changelog.

Talk to the user in their language; everything that gets published (changelog, description) is
in English, like the existing pages.

## Hard rules

1. **Nothing is published, built, tagged or changed before the user approves.** Step 2 only
   reads and then shows the changelog; wait for an explicit yes (or edits) before anything else.
2. **CurseForge first, then Modrinth.** Every CurseForge upload of this release (and the CurseForge
   description, if it changes) comes before any Modrinth upload or Modrinth description change.
3. **Ask about the main-page description** before uploading, and show both the current and the
   proposed text (step 3). Changing it is the user's call.
4. Tokens are read by `publish.py` from `HKCU\Environment` (`CURSEFORGE_TOKEN`, `MODRINTH_TOKEN`).
   Never print them, never ask for them in chat, never put them on a command line.
5. Every write goes through a dry run first (`publish.py` without `--send`), and the real run
   happens only after the user's yes on the final summary (step 5).
6. Never delete or overwrite jars in `build/libs` (the user curates them there), never push,
   never force anything on the platforms (no deleting or editing already published files).

## The helper

`python .claude/skills/publish/publish.py <command>` (standard library only, `--help` lists it all):

| command | what |
|---|---|
| `published` | what is on Modrinth and CurseForge now (versions, files, game versions, dates) |
| `jar-info JAR` | the jar's `fabric.mod.json`: mod version, Minecraft range, loader floor, `breaks` |
| `game-versions "RANGE"` | a Minecraft range expanded to release versions + CurseForge ids |
| `upload-cf --jar J --changelog F [--incompatible-slug S] [--send]` | one CurseForge file; game versions come from the jar's range, plus Fabric and Client |
| `upload-mr --jar J --changelog F --name N --version-number V [--incompatible-id ID] [--send]` | one Modrinth version (fabric, release) |
| `mr-project get [--out F]` / `mr-project set --body F [--summary S] [--send]` | Modrinth summary and page body |

Project ids: CurseForge `1018847`, Modrinth `x9ISUf1U` (slug `fishing-rod-fix`).

Conventions already used on both platforms (keep them):

- file = the jar as built: `fishingrodfix-<mc-label>-v<mod_version>.jar`, where `<mc-label>` is
  the branch's Minecraft version or range (`26.3`, `1.21-1.21.1`, `26.1-26.1.2`).
- CurseForge display name = the file name; release type `release`; tags: every Minecraft release
  version the jar declares, `Fabric`, `Client`.
- Modrinth: name `Fishing Rod Fix <mod_version>`, version number `<mc-label>-v<mod_version>`,
  loader `fabric`, `release`, game versions = every release the jar declares.
- A mod in the jar's `breaks` is declared on both platforms as an incompatible project, when it
  exists there: on Modrinth by project id (look it up: `api.modrinth.com/v2/project/<slug>`), on
  CurseForge by slug. If the CurseForge slug can't be confirmed, leave the relation out there and
  say so; a wrong slug fails the upload.

## Step 1 — Work out the release (read-only)

1. `publish.py published` — the latest published release per Minecraft version.
2. For each target branch (see Arguments): its `gradle.properties` (`mod_version`,
   `minecraft_version`) and `fabric.mod.json` (`git show <branch>:<path>`, no checkout: the user
   may have a dev client running on the checkout). A branch is due when no published file covers
   its Minecraft versions with its `mod_version`.
3. The jar: `build/libs/fishingrodfix-<mc-label>-v<mod_version>.jar`. Check it with `jar-info`
   against the branch: same mod version, same Minecraft range, same loader floor and `breaks`; and
   it must be newer than the branch's last commit that touches `src/`, `build.gradle`,
   `gradle.properties` or `settings.gradle` (`git log -1 --format=%cI <branch> -- ...`). A missing
   or stale jar is reported now and rebuilt only after approval (step 4).
4. Is the branch pushed? `git rev-list --count origin/<branch>..<branch>`. Unpushed commits don't
   block publishing, but the final summary says so (the jar would ship code that isn't public yet).

## Step 2 — Changelog: dig it out, show it, stop

**Baseline.** The previous release is the newest published mod version (step 1). On a branch, its
last commit is the newest commit dated before that publish date whose `gradle.properties` still
has the old `mod_version`; if the branch has a tag for it (`<mc-label>-v<version>`), use the tag.
The changes are `<baseline>..<branch>`: read the commit messages **and** the diffs of `src/`,
`fabric.mod.json`, `README.md` (the user-facing list of fixed bugs) and the Known limitations in
`CLAUDE.md`. Commit messages alone miss things and name internals; the code is the source of truth
for what changed in game.

**What goes in: only what a player notices.** A dry list of facts, one per line, past tense or
plain statements:

- `Fixed: the fishing line didn't start at the rod tip while crouching in third person.`
- `Fixed: the line jumped to the other hand when switching hotbar slots.`
- `Added support for Minecraft 1.21.9–1.21.10.`
- `No longer requires Fabric API.` / `Now requires Fabric Loader 0.19.0 or newer` (only if it
  changed and a player could hit it).
- `Incompatible with Enchanted Fishing Line: the game won't start with both installed.`
- Works with / known to break with another mod or resource pack, when that changed.

**What stays out:** anything about how the mod is built or written — mixins, class and method
names, injection points, Loom/Gradle/Java toolchains, mappings, refactors, performance internals,
docs and skill edits, review rounds, commit hashes, Mojira ids (unless the user asks for them).
If a fact needs an internal word to be said, say the visible effect instead; if there is no
visible effect, drop it. No marketing, no adjectives, no "improved stability".

**Per jar.** One shared list for the release, plus a line for what differs per jar: a changed
Minecraft range (a version added, or one that no longer gets this release and stays on the
previous one), a fix that exists only on some versions.

**Show it and stop.** Present to the user: the release (mod version), the table of jars (branch →
file → Minecraft versions → pushed / jar fresh), and the changelog text exactly as it will be
published, per jar where it differs. Also list anything you left out on purpose and weren't sure
about, in one line each, so the user can pull it back in. **Do not continue until the user
approves the changelog.** Apply their edits and show it again if they changed more than a word.

## Step 3 — The main-page description

Ask whether they want to change the mod's description on the main page. Show:

- the current Modrinth summary and body (`mr-project get`), and the jar's `fabric.mod.json`
  `description` for reference;
- the current CurseForge description: it has no API; read it from the project page in the browser
  pane (`https://www.curseforge.com/minecraft/mc-mods/fishing-rod-fix`), or ask the user to paste
  it if the page won't load;
- a proposed new text when the current one no longer matches the mod (e.g. the fixed-bug list in
  `README.md` grew). Same rules as the changelog: what a player sees, no internals. The body may
  reuse `README.md`'s list and images (image URLs must be absolute:
  `https://raw.githubusercontent.com/andrewchik0/fishing-rod-fix/<default_branch>/docs/...`,
  and only if that file is pushed).

If they decline, skip the description entirely. If they accept, the final text is part of step 5's
summary.

## Step 4 — Prepare (after approval)

- Write each approved changelog to the scratchpad (`changelog-<mc-label>.md`), the description to
  `description.md`.
- Missing or stale jar: build the branch in a temporary worktree under the scratchpad
  (`git worktree add <scratch>/build-<branch> <branch>`), with the JDK the branch needs (26.x:
  temurin-25; older: `-Dorg.gradle.java.home=C:/Users/vasil/.jdks/corretto-21.0.11`), publish
  that worktree's jar, and remove the worktree afterwards. Don't build in the main checkout, and
  ask before copying the jar into `build/libs` (an older jar of the same name may be there).
- Dry-run every upload: `upload-cf` and `upload-mr` without `--send`, and `mr-project set`
  without `--send` if the description changes. Fix anything they reject.

## Step 5 — Final summary, one yes, publish

One final check for the whole release, not per jar or per platform. Show, in one message:

- per jar: the version name and number exactly as they'll appear (CurseForge display name;
  Modrinth `Fishing Rod Fix <mod_version>` / `<mc-label>-v<mod_version>`), set side by side with
  the previous published release's names so the user can see the conventions held; the file,
  size, Minecraft versions, CurseForge tags, incompatible relations;
- the changelog text exactly as it will be published (per jar where it differs);
- the description change, if any; pushed state; the order (CurseForge, then Modrinth).

Ask for the final approval and wait for an explicit yes. Then:

1. **CurseForge**, every jar: `upload-cf ... --send`. Before each, re-check `published` so a
   resumed run doesn't upload a file twice. If the description changes: the user pastes it on
   CurseForge by hand (project → Description; there is no API for it), give them the text ready
   to paste and wait until they say it's done.
2. **Modrinth**, every jar: `upload-mr ... --send`, same duplicate check. Then, if the
   description changes, `mr-project set --body description.md [--summary ...] --send`.

Any error stops the run where it is: report what was published, what wasn't, and the error. Don't
retry an upload that may have gone through; check `published` first.

## Step 6 — Report

List each published file with its link (the helper prints them), and what is left for the user
(the CurseForge description if they haven't pasted it yet, pushing unpushed branches). Offer, don't
do: local tags `<mc-label>-v<mod_version>` on the published commits, which make the next release's
baseline exact.
