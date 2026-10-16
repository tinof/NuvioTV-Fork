---
name: roadmap-next
description: Pick the next ROADMAP.md step to work on for the Shield branch. Checks readiness of each priority (Shield reachable over adb, a debug APK of HEAD installed, owner decisions, prerequisites, open PRs), drafts a ranking, gets an independent ranking from Codex as second advisor, reconciles both with the advisor tool, and recommends one step plus two alternates. Writes nothing. Run only when the user types /roadmap-next.
argument-hint: "[item ID or theme to focus on]"
disable-model-invocation: true
---

# roadmap-next

The user typed `/roadmap-next $ARGUMENTS`. Recommend the next roadmap **step** by its step ID (e.g.
"R2.1: Skip when the activity is gone"), not just an item. This skill only reads: it never edits repo
files, creates branches, opens PRs, installs APKs or sets device properties. It writes only to the
scratchpad (the draft, the Codex answer, a downloaded APK for hashing).

If `$ARGUMENTS` names an item ID (R1, Q2, S4, …) or a theme (reliability, quality, snappiness,
measurement, engine, upstream, docs), rank only steps under it, but still report what is waiting for the owner.

## 1. Gather state (one Bash call)

- `ROADMAP.md`: the **Priorities** list, the **Waiting for the owner** list, and the item sections the
  priorities name. Read the item sections, not only the priority lines: the steps, their tags, gates and
  `Needs:` lines live there. A step is `- [<ID>][tags] <step>. Gate: <evidence>.`, with an optional
  `Needs: <ID>[, <ID>]`; IDs are `<item>.<n>` (R2.1), and tags are `desk`, `device`, `owner`,
  `new-build`. A step may wrap over several lines.
- `docs/ROADMAP_LOG.md`, to tell which step IDs have shipped: `/ship` heads each closing entry
  `## <date>: <ID> <title> (PR #<n>)`; search the whole log with `rg -wF <ID>`.
- `git log --oneline -15`, `git status --short`, current branch, `git rev-parse HEAD`.
- The fork's repo: `R=$(git remote get-url origin | sed -E 's#.*github.com[:/]##; s#\.git$##')`. Pass
  `-R "$R"` to every `gh` call except `gh api`, which takes the repo in its path: with no default repo
  set, `gh` resolves to the upstream remote.
- `gh pr list -R "$R" --state open --json number,title,headRefName,labels`, and `git branch -a`.
- **Shield.** The address is in the gitignored `docs/nvidia-shield.md`; never print it in the answer.
  Every adb call names the serial with `-s` and runs under `gtimeout 15`:
  `adb connect <addr>:5555`, then `adb -s <serial> shell getprop ro.product.device` must print `mdarcy`.
  A timeout or another device means "Shield unreachable".
- **Debug APK of HEAD.** Two checks, both required:
  1. `gh run list -R "$R" --workflow "PR Full Debug Build" --event workflow_dispatch --limit 20 --json databaseId,headSha,headBranch,conclusion,createdAt`
     (no `--branch` filter): the newest run with `conclusion == "success"` whose commit has the same
     tree as HEAD, `gh api repos/$R/git/commits/<headSha> -q .tree.sha` equal to
     `git rev-parse HEAD^{tree}`. Compare trees, not SHAs: `/ship` dispatches the build on the step
     branch and then rebase-merges, which gives every commit a new SHA but keeps the tree. A
     `pull_request` run builds the merge commit, not HEAD, so it does not count.
     The run's `full-debug-apks` artifact must not be expired
     (`gh api repos/$R/actions/runs/<id>/artifacts -q '.artifacts[] | .name+" "+(.expired|tostring)'`).
  2. The installed APK is that build. Read the path on the device first, then hash it there:
     `p=$(gtimeout 15 adb -s <serial> shell pm path com.nuvio.tv.test.shield | head -1 | sed 's/^package://' | tr -d '\r')`,
     then `gtimeout 15 adb -s <serial> shell sha256sum "$p"`. Compare it with the file hash of the run's
     APK: `gh run download -R "$R" <id> -n full-debug-apks -D <scratchpad>/apk-<id>` (skip when that
     folder exists), then
     `find <scratchpad>/apk-<id> -name app-full-arm64-v8a-debug.apk -exec shasum -a 256 {} \;`.
     When the installed hash matches an older run instead, name that run in the answer. The run log
     prints only the signing-certificate digest, which is the same for every build. versionCode is
     hardcoded and cannot tell builds apart either.

  A missing package, a failed hash or no matching run means "new build needed". Never treat an unknown
  result as fresh.

  A debuggable build (heap dumps, `run-as` backups) never comes from CI: it is a local
  `./gradlew :app:assembleFullDebug -Pdebuggable=true -PnuvioAppIdSuffix=.shielddbg` from a clean tree at
  HEAD. It is signed with the local debug key, so it installs beside the CI build as
  `com.nuvio.tv.test.shielddbg` instead of replacing it (replacing would need an uninstall and lose the
  CI build's data). It counts as fresh when its installed hash equals the hash of that local output.
- Debuggable flag of the installed APK:
  `gtimeout 15 adb -s <serial> shell dumpsys package com.nuvio.tv.test.shield | rg -q DEBUGGABLE && echo yes || echo no`.

## 2. Readiness per step

For each open step of the priority items, in priority order, evaluate all of these together; a step is
startable only when none applies:

- **Blocked on the owner:** tagged `owner`, or its item is on the "Waiting for the owner" list for this
  step. Other steps of the same item stay candidates.
- **Blocked on a prerequisite:** its `Needs:` line names a step ID that is not done. A step is done when
  its line has left `ROADMAP.md` and its ID appears in `docs/ROADMAP_LOG.md`. A line that ends with
  `Landed: PR #<n>; gate pending.` has its code merged; only its device or owner gate is open, so it
  counts as done for a `Needs:` that wants the code, and as open for one that wants the gate evidence.
- **Needs the device:** tagged `device` and the Shield is unreachable.
- **Needs a new build:** tagged `new-build` and the debug-APK checks did not both pass, or the step's
  prerequisite is a desk change not yet in a built APK.
- **Needs a debuggable build:** the step says so (heap dump, `run-as` backup) and the installed APK is
  not debuggable.
- **Already in progress:** an open PR whose title ends with `(<ID>)`, or a branch named
  `<type>/<id>-…` (R2.1 → `r2-1`), covers it. Report the PR's `review:*` label.

Do not spawn agents for this; it is a handful of reads.

## 3. Draft ranking

Up to three startable steps. For each:
- step ID + step, and the priority number it belongs to;
- the **gate** quoted from the step;
- files likely touched, from the item's anchors, and the tests that cover them
  (`./gradlew :app:testFullDebugUnitTest --tests '<pattern>'`);
- whether a device session follows, and that it must follow M2;
- branch name `<type>/<id>-<slug>` off `perf/shield-pass` (`fix/r2-1-skip-restore-when-finishing`;
  types `fix`, `feat`, `perf`, `docs`, `fork`), as `docs/REVIEW.md` sets for `/ship`;
- whether it can share a branch with another candidate. Rules from ROADMAP: AFR changes are one commit
  per finding; a step that changes a default gets its own branch; desk steps of one item may share a
  branch.

Write the draft to `<scratchpad>/roadmap-next-draft.md`.

## 4. Codex second advisor

Run in the background (`run_in_background: true`) through the stall watchdog and wait for the completion
notification. Follow `~/.claude/rules/codex-routing.md`.

```
~/.claude/skills/ship/scripts/codex-watch.sh <scratchpad>/roadmap-next-codex exec --sandbox read-only "Read ROADMAP.md and <scratchpad>/roadmap-next-draft.md in this repo. Rank the startable roadmap steps yourself, independently of the draft. Then: name risks the draft misses, prerequisites it assumed but did not check, and any step that should come first and why. Be specific: step ID, step, file paths."
```

Codex's answer is in `<scratchpad>/roadmap-next-codex.md`. Report the `model` and `effort` from
`<scratchpad>/roadmap-next-codex.result.json`. If the run fails (usage limit, unsupported
model, sandbox error), say so and continue without it. Never replace it with a Claude subagent and call
that the Codex opinion.

## 5. Reconcile

Compare the two rankings. Check every factual claim Codex makes (a missing file, a dependency, a line
reference) against the repo before you accept it. Then call the `advisor` tool once, with both rankings
in context, before you settle on the recommendation.

## 6. Answer

Keep it short:

1. **Recommended:** step ID + step, why now, its gate, the branch name, and the first action (usually
   "enter plan mode for <ID>, then `/ship --step <ID>` when it is built").
2. **Alternates:** two, one line each.
3. **Where Codex disagreed** and what decided it (one or two lines; omit if they agreed).
4. **Waiting for the owner:** the list, one line each, so the owner sees what only they can unblock.
5. **Device state:** Shield reachable or not; debug APK of HEAD installed or not (and whether a new CI
   build is needed). End with: "Run the M2 device-safety protocol (snapshot first, restore after) before
   any device step."
