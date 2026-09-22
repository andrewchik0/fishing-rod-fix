---
name: reviewer
description: Read-only review of the Fishing Rod Fix mod — the branch's unpublished changes by default, or a given range, paths or the whole mod — run as a background agent that returns one report. It has fresh reviewer agents check three dimensions: the default review (correctness, crash safety, fidelity to vanilla's math, docs accuracy, code quality), a performance gate (the mod's cost must stay within noise of zero on the scale of a whole frame's budget) and compatibility with known mods, shader packs, resource packs, servers and loaders. One pass: it verifies the findings and reports them; it never edits, builds or commits. Use when asked to review code, a diff, a branch, a port or a release candidate.
argument-hint: "[scope] [--only <dimensions>] [notes for the reviewers]"
context: fork
agent: general-purpose
---

# Review the Fishing Rod Fix mod (read-only)

> **Maintenance note (keep this skill version-agnostic).** Like `port-version`, this skill is
> checked into the repo and copied onto every version branch. Don't bake version numbers, class
> names of one version family or "the current release is vX.Y" into the procedure: derive them from
> the checked-out branch (its `gradle.properties`, its mixins, its decompiled vanilla). The
> ecosystem matrix in `compatibility.md` is "observed, verify": extend it when a review learns
> something, never trust it over the source. Propagate edits to the branches you maintain with
> `git checkout <default_branch> -- .claude/skills/reviewer`.

## Read-only

The review changes nothing and runs nothing that could: no edits in the repo, no git state
changes, no Gradle runs (a rebuild under the user's running dev client leaves it with a mix of old
and new classes), no game launches, no downloads. Its only write is the decompiled-vanilla cache
outside the repo. Suggested fixes are text in the report.

## What the review checks

The mod is small, runs every frame on thousands of clients, and hooks vanilla with
`defaultRequire: 1`, so a bad change ships a crash, a frame-time cost, or a broken line next to
somebody else's mod. Each of these is reported as a **REAL BUG**:

1. **Default review** — a crash path, math that doesn't match this branch's vanilla, unsound state
   or fallbacks; and as a **WRONG FACT**, any comment, Javadoc, `CLAUDE.md`, `README.md` or skill
   statement the scope touches that isn't true. See `correctness.md`.
2. **Performance** — the mod's added cost leaving the noise around zero on the scale of a whole
   frame's budget (the numbers are in `performance.md`).
3. **Compatibility** — a new crash or conflict with another mod, a regression of a mod, shader pack
   or resource pack that worked before, or a new worse-than-vanilla outcome; an incompatibility
   that changed without `CLAUDE.md`'s Known limitations changing is a WRONG FACT. See
   `compatibility.md`.

The review can't see pixels: it proves the code against vanilla's source, the budget and the
ecosystem. Whether the line sits on the rod in game is the user's check (the report says what to
look at).

## Arguments

This run: `$ARGUMENTS`

`/reviewer [scope] [--only <dimensions>] [notes]`

- *(no scope)* — the branch's unpublished work: commits not on `origin/<branch>` plus uncommitted
  and untracked changes.
- `all` — the whole mod: `src/`, the mixin config, `fabric.mod.json`, the build files and the
  claims in `CLAUDE.md` / `README.md`.
- a commit, a range (`HEAD~3..HEAD`) or paths — exactly that.
- `--only correctness,performance,compatibility` (any subset) — just those reviewers.
- anything else is **notes**: pass them to every reviewer as context.

## Why it's shaped this way (keep it fast)

Wall time is the slowest reviewer plus verification, so each reviewer covers only what the scope
can affect, they run in parallel, and work that can't find anything in this scope is skipped:
reviewers that don't apply, web discovery for touch points the matrix already covers. Anything that
needs the user (jar downloads, load tests, in-game measurements) goes into the report as an open
item instead of being done. None of these cuts drops a check that could find a bug in the scope.

## Step 0 — Prepare

1. **Scope** → the git commands a reviewer runs to see it, e.g. for the default scope:
   ```sh
   git log --oneline origin/<branch>..HEAD
   git diff origin/<branch>             # committed + uncommitted, against the published branch
   git status --porcelain               # untracked files are not in the diff: list them, they are read in full
   ```
   A diff is reviewed in the context of the whole mod: unchanged code the change calls, or that
   calls it, is in scope wherever the change affects it. The working tree may change while you
   review: the review covers it as it is when the reviewers start.
   If there is no scope to review (no scope given and the branch has no remote counterpart, or
   nothing unpublished), stop and return a short report saying so and asking for a scope (`all` is
   one option).
2. **Reviewers** (unless `--only` says otherwise), from what the scope touches:

   | The scope touches | Reviewers |
   |---|---|
   | only docs or comments (`*.md`, comment-only Java changes) | default |
   | build files, `fabric.mod.json` or the mixin config, but no Java | default, compatibility |
   | Java code | default, performance, compatibility |

   For `all`, or a diff over ~300 changed lines, split the default reviewer in two: (a) vanilla
   fidelity and math, (b) crash safety, state, docs and code quality.
3. **Vanilla sources**: `sh ${CLAUDE_SKILL_DIR}/vanilla-sources.sh`. It decompiles this branch's
   Minecraft jars once (cached in the Gradle cache, ~30 s the first time) and prints the source
   directory and the jar paths. If it reports the Loom jars missing, stop and return that the
   branch has to be built once; don't run Gradle yourself.

## Step 1 — Reviewers

Spawn the reviewers in **one message**, each with `subagent_type: general-purpose` and
`run_in_background: true`, always new agents: a clean context is the point. Wait for all of them
(you are notified as each finishes); don't report before the last one is in.

| Reviewer | Checklist |
|---|---|
| Default (one, or two when split) | `${CLAUDE_SKILL_DIR}/correctness.md` |
| Performance | `${CLAUDE_SKILL_DIR}/performance.md` |
| Compatibility | `${CLAUDE_SKILL_DIR}/compatibility.md` |

Prompt template (fill every `<…>`; keep it self-contained, the agent sees nothing else):

```text
You are the <dimension> reviewer of the Fishing Rod Fix mod, a client-side Fabric mod at
<repo path>, branch <branch>, Minecraft <mc_version>.

Read first: CLAUDE.md, then <checklist path>: your checklist, the budget or matrix it defines, and
the report format you must use.

Scope: <scope in words>. See it with:
<git commands>
Review it in the context of the whole mod: read every file the change touches and the code that
calls into it or that it calls. Cover what the scope can affect; skip checklist sections it can't,
and say so in the coverage section.

Vanilla for this version: decompiled sources and assets in <sources dir>; exact descriptors and
bytecode via `javap -c -p -cp <client jar or common jar> <class>`. Every claim about vanilla must be
checked there, not recalled.

Notes (context for the review):
<the notes, or "none">

Rules: strictly read-only. Don't edit files, don't change git state, don't run Gradle, launch the
game or download anything. You may read files, grep, run javap, and read web pages (the Modrinth
API, mod sources on GitHub). List jars you'd need to inspect under NEEDS JAR.

Before you report a REAL BUG, try to disprove it: re-read the code path and the vanilla source it
depends on. Report it only if it survives, with the quotes that prove it.

Report every finding in the checklist's format with one class: REAL BUG, WRONG FACT, IMPROVEMENT
or NIT. Then the coverage section the checklist asks for, so it's clear what was checked and found
clean. Be concrete: file:line, the scenario, the evidence. No finding is better than a guessed one.
```

## Step 2 — Verify

Reviewers are wrong often enough that the report doesn't pass their word on unchecked.

- **Every REAL BUG and WRONG FACT**: re-derive it from the code and the vanilla source (and the
  other mod's source, for compatibility). Confirmed → keep, reclassified if mislabelled (a false
  comment is a WRONG FACT, not a REAL BUG; a budget violation is a REAL BUG, not an IMPROVEMENT).
  Not reproducible or a wrong premise → rejected, with a one-line reason.
- **IMPROVEMENTs and NITs**: a sanity check only (does the line say what the finding claims?).
- Merge duplicates across reviewers.
- **NEEDS JAR**, **NEEDS MEASUREMENT** and suggested load tests are open items for the report; don't
  download or run anything.

## Step 3 — Report

Your final message is the report, in the user's language (as the loaded `CLAUDE.md` files say, else
English), with code, paths and finding classes verbatim. Short and concrete:

- **Verdicts**: default (the number of REAL BUGs and WRONG FACTs); performance PASS / FAIL /
  NEEDS MEASUREMENT with the totals against the budget (per frame, per hook, one-off);
  compatibility: the touched vanilla targets, the matrix rows checked with their outcomes, and the
  Known limitations text to add to `CLAUDE.md`, if any.
- **Findings**, REAL BUGs first, each with its class, `file:line`, the scenario, the evidence and
  the suggested fix.
- **Rejected**: one line each with the reason.
- **Open items for the user**: NEEDS JAR (name, URL, size), NEEDS MEASUREMENT (recipe, threshold),
  suggested load tests.
- **In game**: what to look at, derived from the change (which hand, pose, FOV, pack, mod), since
  the review can't see the line.
- The scope reviewed (commits and files) and that nothing was changed.
