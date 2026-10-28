---
name: upstream-sync
description: Bring this fork up to date with ysosrs123/NuvioTV-Fork. Rebases perf/shield-pass (upstream/main + the fork's CI and test commits + the Shield work) onto upstream/main through preflight, an overlap map, a sized classification workflow, a hand rebase, scripted verification, CI on a pushed candidate and an atomic leased push that also moves the main mirror, then a follow-up PR for the settings decisions. Run only when the user types /upstream-sync.
argument-hint: "[preflight|map|classify|rebase|verify|publish|followups|cleanup]"
disable-model-invocation: true
---

# upstream-sync

The user typed `/upstream-sync $ARGUMENTS`. With no argument, run every step in order. With one, start at
that step, after checking that the earlier steps' outputs exist in the scratchpad. The open "Upstream drift"
issue (label `upstream-drift`, kept current by `.github/workflows/upstream-drift.yml` on the default branch
`perf/shield-pass`) says how far behind the fork is.

`S=.claude/skills/upstream-sync/scripts`, `<scratch>` is the session scratchpad, `<date>` is today
(`YYYY-MM-DD`). Run scripts from the repository root as `python3 -I $S/<script>`. Every `gh` call takes
`-R tinof/NuvioTV-Fork`. Settings, traps and the theme table are in `sync.json` next to this file.

## Rules

- **One rebased branch, never merged.**
  - `perf/shield-pass` (the default branch) = `upstream/main` + the fork's CI, test and fix commits and the
    drift watcher at the bottom + the Shield work.
  - `main` is a mirror: upstream's commit that `perf/shield-pass` was last rebased onto, with no fork commit. It
    marks where the fork's commits start (`OLD_BASE..OLD_PERF`). Only the publish push in step 8 moves it.
  - Each sync runs `git rebase --onto upstream/main OLD_BASE perf/shield-pass`.
- **Sides during a rebase:** "ours" is the new base (upstream), "theirs" is the fork commit being replayed.
- **Toolchain:** JDK, Gradle, AGP, Kotlin and the JVM target always follow upstream.
- **Owner rule (`owner_rule` in `sync.json`):**
  - If upstream changes a setting the Shield plan also sets, keep the fork's value. Gate it on Nvidia, give it a `debug.nuvio.*` revert switch, and add a row to `docs/shield-todo.md`.
  - If the plan has no such setting, take upstream's value and list it under "Upstream defaults taken with the sync of <date>".
- **Protected features (`protected_features` in `sync.json`):** the AAC-to-DTS / DTS-HD MA transcode and the
  HLG-to-Dolby-Vision conversion are what the owner keeps the fork for. A commit of either is never dropped or
  partly superseded without the owner, even where the owner rule would settle it, and every line it adds must
  survive the rebase (`verify` checks both).
- **Gradle runs on JDK 17:** prefix every `./gradlew` with `JAVA_HOME=${JAVA17_HOME:-$(/usr/libexec/java_home -v 17)}`;
  the system Java is newer than upstream's Gradle can run on.
- **Stop and ask the owner** before:
  - dropping a commit;
  - any setting decision the owner rule does not settle;
  - the push in step 8.
- **Never work around a block.** Never force-push without a lease, never push a SHA that CI has not built, and never edit the drift issue by hand.

## 1. Preflight

`python3 -I $S/sync.py preflight`. Exit codes: 0 = go, 1 = blocked, 2 = a `git` or `gh` call failed (report it and stop), 3 = nothing to do.

It blocks when:
- the tree is dirty;
- local `main` or `perf/shield-pass` differs from origin;
- `origin/main` is not in `upstream/main` (upstream rewrote its history, or `main` carries a commit of its own:
  see the trap in `sync.json`);
- a PR is open into `perf/shield-pass` or `main` (found through REST, which also works in cloud sessions);
- any other local or origin branch has commits that are not in `perf/shield-pass`.

On a block, report it and stop. The owner merges, rebases or deletes such branches before a sync. `--allow-branch B` is only for a branch the owner explicitly wants carried over; it is rebased in step 8. Read the warnings too: an enabled issue workflow, rerere off, `origin/main` not at the base of `perf/shield-pass`, or a leftover `sync/`, `backup/` or `claude/` branch. Such a branch only warns while all its commits are in `perf/shield-pass` by subject; otherwise it blocks.

## 2. Workspace

1. Fetch both remotes. Then `git config rerere.enabled true` and `git config rerere.autoupdate true`.
2. Create the backup branch `backup/perf-pre-sync-<date>` from `origin/perf/shield-pass`. Record the full old SHAs
   `OLD_PERF` (`origin/perf/shield-pass`), `OLD_MAIN` (`origin/main`, the lease for `main` in step 8) and `OLD_BASE`
   (`git merge-base origin/perf/shield-pass upstream/main`, where the fork's commits start). Add local tags
   `sync/<date>/old-perf`, `sync/<date>/old-main` and `sync/<date>/old-base`. `OLD_BASE` equals `OLD_MAIN` unless
   preflight warned that `origin/main` is not at the base of `perf/shield-pass`; the lease stays `OLD_MAIN` either way.
3. Create the worktree: `git worktree add -b sync/<date>-perf ../NuvioTV-Fork-wt/upstream-sync OLD_PERF`, and copy `local.properties` and `app/src/main/jniLibs/arm64-v8a/libnuvio_rpu.so` into it (both are gitignored; without the library an APK built there has Dolby Vision conversion off).
4. Run the baseline gate on plain `upstream/main` in a second worktree. Use the contract's `gate` command (JDK 17 and `ANDROID_HOME` per `.claude/workflow.md`; the first run takes about 6 minutes). Save the failures to `<scratch>/baseline-failures.txt`: from the repository root, run `python3 -I $S/sync.py test-failures <baseline worktree>/app/build/test-results/testFullDebugUnitTest`. Use the absolute path; the baseline worktree has no skill, and a relative path would read this checkout's results. Upstream's own failures are not the sync's fault; `sync.json` lists the known ones.

## 3. Map

`python3 -I $S/sync.py map-overlap --scratch <scratch>/classify > <scratch>/overlap.json`. Exit 3 means
the fork is not behind: stop.

Report to the owner: the counts, the scale tier and its reasons, the light blockers, the groups with their commit counts, the trial-merge conflict list, and any truncation.

Scale tiers:
- `skip`: no fork commit touches a file upstream changed, and the trial merge is clean. No agents are needed.
- `light`: at most `light.max_conflicts` (3) files conflict and `light.blockers` is empty. No agents are needed.
- `single`: one analyst.
- `fanout`: one analyst per group, at most 9, plus a critic.

`light.blockers` names everything that keeps a sync off the light path:
- more conflict files than the limit;
- a conflict in a protected file, or a protected commit that touches a conflicting file;
- an upstream change under `light.blocked_paths` in `sync.json` (workflows, `app/libs`, settings stores,
  `DeviceProfile`);
- a Tier 2 path both sides changed, unless upstream only changed lines that `light.benign_tier2_lines` allows
  (a version bump in `app/build.gradle.kts`).

`light.eligible` (no blockers, tier `skip` or `light`) is the only kind of sync a nightly run takes.

## 4. Classify (skip for tiers `skip` and `light`)

1. Run `Workflow({name: 'upstream-sync-classify', args: <the overlap.json object>})`. Pass args as a JSON object, not a string.
2. Save the result to `<scratch>/classify.json`.
3. If `summary.complete` is false, rerun the failed groups (`summary.failedGroups`) until every commit has a verdict: `summary.unclassified` lists the commits without one.
4. Check the `file:line` claims behind every drop, adapt and partial verdict yourself before you use them.
5. Show the owner the drop list (with the reason, e.g. "net-zero pair" or "upstream does it in <sha>"), the adapt list and every setting decision. Wait for answers where the owner rule does not settle a decision.
6. `summary.protectedAsk` lists drop and partial verdicts on protected-feature commits. Each one is the owner's decision; an approved drop is passed to `verify` as `--protected-drop <sha>`.

## 5. Rebase

1. Start the rebase in the worktree. The drops come from `python3 -I $S/sync.py drops --decisions <scratch>/classify.json --range OLD_BASE..OLD_PERF`:
   ```
   GIT_SEQUENCE_EDITOR="python3 -I $PWD/$S/sync.py todo-edit --drop <sha,sha>" \
     git rebase -i --onto upstream/main OLD_BASE sync/<date>-perf
   ```
   With no drops, use a plain `git rebase --onto upstream/main OLD_BASE sync/<date>-perf`. `todo-edit` exits 1 and aborts the rebase if a SHA is not in the todo.
2. At each stop:
   0. On the light path, run `python3 -I $S/sync.py stop-check` first. Exit 1 means the stop needs more than the
      light path allows (a protected commit or file, a blocked path, or more conflict files than the limit across
      the rebase): `git rebase --abort`, then classify (step 4, tier `single`) before you rebase again.
   1. Run `git status -sb`.
   2. Run `git diff --cached --stat`: rerere autoupdate may already have staged an old resolution, so read it, don't trust it.
   3. Run `python3 -I $S/both.py --list <file>`.
3. Resolve each conflict from the classify guidance (on the light path, keep both sides unless one side
   already holds the other):
   - Use `both.py <file> ours|theirs|ours-theirs|theirs-ours [block]` for the mechanical cases (`SEP=1` puts a blank line between the two sides).
   - Edit by hand otherwise.
   - For a binary file, use `git checkout --theirs|--ours`.
4. When a resolution changes what the commit does, commit with an amended message before `git rebase --continue` (`GIT_EDITOR=true`).
5. Note each resolution in `<scratch>/resolutions.md`: commit, file, what you kept, and why.

The result is `NEW_PERF`; `NEW_BASE` is the `upstream/main` SHA it was rebased onto.

## 6. Verify

1. Run:
   ```
   python3 -I $S/sync.py verify --base-old OLD_BASE --old OLD_PERF --base-new NEW_BASE --new NEW_PERF \
     --upstream NEW_BASE --decisions <scratch>/classify.json
   ```
2. A failure blocks. Two flags waive a single path, and only for a decision on that path:
   - `--allow-same <path>`: a fork change to the path was knowingly superseded.
   - `--allow-changed <path>`: a fork-only file was knowingly adapted.

   `--decisions` turns the decided drops in `OLD_BASE..OLD_PERF` into expected drops (a decision that names
   another commit is only a note) and prints hints for adapt verdicts; it never waives a path itself.

   Three more flags:
   - `--protected-drop <sha,...>` accepts owner-approved drops of protected-feature commits.
   - `--allow-new <sha,...>` accepts commits the sync adds on purpose, such as a fix for an upstream test that
     fails on CI. Each one goes into the step 8 publish question for the owner.
   - `--creation-factor N` (default 100) is passed to `git range-diff`. At git's own 60, a commit whose diff
     changed a lot (a binary next to a rewritten README) shows as one dropped and one new commit.

   **Protected features** (report section of that name): for each feature, the commits it owns (by path anchor or
   subject) and every line they added that the old tip still has. A line missing from the new tip, in any file,
   fails, and so does a changed protected binary. This is the check the line-blind path checks lack.

   **What verify cannot see:** outside the protected features, a file upstream also changed passes as long as it
   still differs from upstream, so losing one of two fork hunks in it is invisible to the script. The `!` hunks in
   the report and the review in step 6.5 are the only check for that; read every `!` hunk of a commit with more
   than one hunk in such a file.
3. Run the full gate in the worktree. Compare `sync.py test-failures` with the baseline: any new failure blocks.
4. Build the APK in the worktree (`./gradlew :app:assembleFullDebug`, JDK 17): it is the only check that runs aapt
   (the locale trap) and the native builds. Then `python3 -I $S/sync.py protected-apk
   app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk` must pass: the DTS-HD MA writer in
   `libffmpegJNI.so` and `libnuvio_rpu.so` for the Dolby Vision conversion.
5. Have one independent Opus `medium` reviewer read the `!` hunks from the verify report together with `resolutions.md`. Check its claims before acting on them.

## 7. CI on the pushed candidate

`Checks` (unit tests; the binary workflows are disabled since 2026-10-22) runs on the dispatched remote ref, so
publish the candidate first:
1. `git push origin NEW_PERF:refs/heads/sync/<date>-perf`
2. Dispatch `gh workflow run Checks --ref sync/<date>-perf`.
3. Find the run with `gh run list --workflow Checks --json databaseId,headSha`, and wait for green on the exact SHA.

## 8. Publish (ask the owner first)

0. `git fetch upstream` and check `git merge-base --is-ancestor NEW_BASE upstream/main`.
   Upstream has rewritten already-synced history (2026-10-26: same trees, new SHAs). If only the SHAs changed,
   rebase onto the new tip with `--onto`, check that the trees are identical, and run step 7 again.
1. Push both refs in one atomic, leased push. `main` moves to the new base as a fast-forward:
   ```
   git push --atomic origin --force-with-lease=refs/heads/main:<OLD_MAIN> \
     --force-with-lease=refs/heads/perf/shield-pass:<OLD_PERF> \
     NEW_BASE:refs/heads/main NEW_PERF:refs/heads/perf/shield-pass
   ```
2. `git fetch origin`, then move the local branches to the pushed tips: `git branch -f main origin/main` and
   `git reset --hard origin/perf/shield-pass` where `perf/shield-pass` is checked out. Otherwise the next
   preflight blocks on "local ... differs".
3. Delete the `sync/<date>-perf` branch on origin.
4. Rebase each carried-over local branch: `git rebase --onto origin/perf/shield-pass <backup> <branch>`.
5. Dispatch `Upstream drift` (`gh workflow run "Upstream drift"`); the issue closes once the fork is level.

## 9. Follow-ups (a normal PR through /ship)

Create a branch `fork/u1-5-sync-<date>-followups` from `origin/perf/shield-pass`. It carries:
- the Nvidia-gated keeps the owner rule asked for, one finding per commit, each with a revert switch and a test;
- `docs/shield-todo.md`: new rows, and the "Upstream defaults taken with the sync of <date>" section;
- `ROADMAP.md`: a progress-log row for U1.5 that cites the PR;
- `docs/ROADMAP_LOG.md`: an entry with the counts, the verify report summary, the gate and CI evidence, the drops with their reasons, and the setting decisions.

## 10. Cleanup (after the follow-up PR merges)

Remove the worktrees and the `backup/*` branch. Keep the `sync/<date>/*` tags: they are the next sync's test oracle. Update the memory note `upstream-sync.md` if a new trap turned up. If the trap is repo knowledge, add it to `sync.json` instead.

## Known traps

Read `traps` in `sync.json`; add new ones there in the follow-up PR. Locale files have their own trap: a fork hunk that carries an upstream-moved string as context re-adds it. `verify` catches the duplicate, and the fix is to drop the fork-side line.
