---
name: upstream-sync
description: Bring this fork up to date with ysosrs123/NuvioTV-Fork. Rebases main (upstream/main + the fork's base layer) and perf/shield-pass (main + the Shield work) onto upstream/main through preflight, an overlap map, a sized classification workflow, a hand rebase, scripted verification, CI on pushed candidates and an atomic leased push, then a follow-up PR for the settings decisions. Run only when the user types /upstream-sync.
argument-hint: "[preflight|map|classify|rebase|verify|publish|followups|cleanup]"
disable-model-invocation: true
---

# upstream-sync

The user typed `/upstream-sync $ARGUMENTS`. With no argument, run every step in order. With one, start at
that step, after checking that the earlier steps' outputs exist in the scratchpad. The open "Upstream drift"
issue (label `upstream-drift`, kept current by `.github/workflows/upstream-drift.yml` on `main`) says how far
behind the fork is.

`S=.claude/skills/upstream-sync/scripts`, `<scratch>` is the session scratchpad, `<date>` is today
(`YYYY-MM-DD`). Run scripts from the repository root as `python3 -I $S/<script>`. Every `gh` call takes
`-R tinof/NuvioTV-Fork`. Settings, traps and the theme table are in `sync.json` next to this file.

## Rules

- **Two layers, both rebased, never merged.**
  - `main` = `upstream/main` + the base layer (the fork's CI, test and fix commits and the drift watcher).
  - `perf/shield-pass` = `main` + the Shield work.
  - Each sync rebases `main` onto `upstream/main`, then `git rebase --onto <new main> <old main> perf/shield-pass`.
  - `main` must stay an ancestor of `perf/shield-pass`.
- **Sides during a rebase:** "ours" is the new base (upstream), "theirs" is the fork commit being replayed.
- **Toolchain:** JDK, Gradle, AGP, Kotlin and the JVM target always follow upstream.
- **Owner rule (`owner_rule` in `sync.json`):**
  - If upstream changes a setting the Shield plan also sets, keep the fork's value. Gate it on Nvidia, give it a `debug.nuvio.*` revert switch, and add a row to `docs/shield-todo.md`.
  - If the plan has no such setting, take upstream's value and list it under "Upstream defaults taken with the sync of <date>".
- **Stop and ask the owner** before:
  - dropping a commit;
  - any setting decision the owner rule does not settle;
  - the push in step 9.
- **Never work around a block.** Never force-push without a lease, never push a SHA that CI has not built, and never edit the drift issue by hand.

## 1. Preflight

`python3 -I $S/sync.py preflight`. Exit codes: 0 = go, 1 = blocked, 2 = a `git` or `gh` call failed (report it and stop), 3 = nothing to do.

It blocks when:
- the tree is dirty;
- a local layer branch differs from origin;
- `main` is not an ancestor of `perf/shield-pass`;
- a PR is open into either layer;
- any other local or origin branch has commits that are not in `perf/shield-pass`.

On a block, report it and stop. The owner merges, rebases or deletes such branches before a sync. `--allow-branch B` is only for a branch the owner explicitly wants carried over; it is rebased in step 9. Read the warnings too: an enabled issue workflow, rerere off, or a leftover `sync/` or `backup/` branch. Such a branch only warns while all its commits are in `perf/shield-pass` by subject; otherwise it blocks.

## 2. Workspace

1. Fetch both remotes. Then `git config rerere.enabled true` and `git config rerere.autoupdate true`.
2. Create backup branches `backup/main-pre-sync-<date>` and `backup/perf-pre-sync-<date>` from the origin tips. Record the full old SHAs `OLD_MAIN` and `OLD_PERF`. Add local tags `sync/<date>/old-main` and `sync/<date>/old-perf`.
3. Create the worktree: `git worktree add -b sync/<date>-perf ../NuvioTV-Fork-wt/upstream-sync OLD_PERF`, and copy `local.properties` into it (it is gitignored).
4. Run the baseline gate on plain `upstream/main` in a second worktree. Use the contract's `gate` command (JDK 17 and `ANDROID_HOME` per `.claude/workflow.md`; the first run takes about 6 minutes). Save the failures to `<scratch>/baseline-failures.txt`: from the repository root, run `python3 -I $S/sync.py test-failures <baseline worktree>/app/build/test-results/testFullDebugUnitTest`. Use the absolute path; the baseline worktree has no skill, and a relative path would read this checkout's results. Upstream's own failures are not the sync's fault; `sync.json` lists the known ones.

## 3. Map

`python3 -I $S/sync.py map-overlap --scratch <scratch>/classify > <scratch>/overlap.json`. Exit 3 means
the fork is not behind: stop.

Report to the owner: the counts, the scale tier and its reasons, the groups with their commit counts, the trial-merge conflict list, and any truncation.

Scale tiers:
- `skip`: no fork commit touches a file upstream changed, and the trial merge is clean. No agents are needed.
- `single`: one analyst.
- `fanout`: one analyst per group, at most 9, plus a critic.

## 4. Classify (skip for tier `skip`)

1. Run `Workflow({name: 'upstream-sync-classify', args: <the overlap.json object>})`. Pass args as a JSON object, not a string.
2. Save the result to `<scratch>/classify.json`.
3. If `summary.complete` is false, rerun the failed groups (`summary.failedGroups`) until every commit has a verdict: `summary.unclassified` lists the commits without one.
4. Check the `file:line` claims behind every drop, adapt and partial verdict yourself before you use them.
5. Show the owner the drop list (with the reason, e.g. "net-zero pair" or "upstream does it in <sha>"), the adapt list and every setting decision. Wait for answers where the owner rule does not settle a decision.

## 5. Rebase the base layer

In the worktree, rebase a copy of `OLD_MAIN` onto `upstream/main`, giving `NEW_MAIN`. If the classification drops
base-layer commits, get them with `python3 -I $S/sync.py drops --decisions <scratch>/classify.json --range <merge base>..OLD_MAIN` and pass them
to `todo-edit` as in step 6. Resolve conflicts as in step 6.

## 6. Rebase the Shield layer

1. Start the rebase. The drops for this layer come from `python3 -I $S/sync.py drops --decisions <scratch>/classify.json --range OLD_MAIN..OLD_PERF`:
   ```
   GIT_SEQUENCE_EDITOR="python3 -I $PWD/$S/sync.py todo-edit --drop <sha,sha>" \
     git rebase -i --onto NEW_MAIN OLD_MAIN sync/<date>-perf
   ```
   With no drops, use a plain `git rebase --onto`. `todo-edit` exits 1 and aborts the rebase if a SHA is not in the todo.
2. At each stop:
   1. Run `git status -sb`.
   2. Run `git diff --cached --stat`: rerere autoupdate may already have staged an old resolution, so read it, don't trust it.
   3. Run `python3 -I $S/both.py --list <file>`.
3. Resolve each conflict from the classify guidance:
   - Use `both.py <file> ours|theirs|ours-theirs|theirs-ours [block]` for the mechanical cases (`SEP=1` puts a blank line between the two sides).
   - Edit by hand otherwise.
   - For a binary file, use `git checkout --theirs|--ours`.
4. When a resolution changes what the commit does, commit with an amended message before `git rebase --continue` (`GIT_EDITOR=true`).
5. Note each resolution in `<scratch>/resolutions.md`: commit, file, what you kept, and why.

## 7. Verify

1. Layer 1:
   ```
   python3 -I $S/sync.py verify --base-old <merge base of OLD_MAIN> --old OLD_MAIN \
     --base-new upstream/main --new NEW_MAIN --upstream upstream/main --decisions <scratch>/classify.json
   ```
2. Layer 2:
   ```
   python3 -I $S/sync.py verify --base-old OLD_MAIN --old OLD_PERF --base-new NEW_MAIN --new NEW_PERF \
     --upstream upstream/main --main-new NEW_MAIN --decisions <scratch>/classify.json
   ```
3. A failure blocks. Two flags waive a single path, and only for a decision on that path:
   - `--allow-same <path>`: a fork change to the path was knowingly superseded.
   - `--allow-changed <path>`: a fork-only file was knowingly adapted.

   `--decisions` turns this layer's decided drops into expected drops (a drop that belongs to the other layer is
   only a note) and prints hints for adapt verdicts; it never waives a path itself.

   **What verify cannot see:** a file upstream also changed passes as long as it still differs from upstream, so
   losing one of two fork hunks in it is invisible to the script. The `!` hunks in the report and the review in
   step 7.5 are the only check for that; read every `!` hunk of a commit with more than one hunk in such a file.
4. Run the full gate in the worktree. Compare `sync.py test-failures` with the baseline: any new failure blocks.
5. Have one independent Opus `medium` reviewer read the `!` hunks from the verify report together with `resolutions.md`. Check its claims before acting on them.

## 8. CI on pushed candidates

`PR Full Debug Build` builds the dispatched remote ref, so publish the candidates first:
1. `git push origin NEW_MAIN:refs/heads/sync/<date>-main NEW_PERF:refs/heads/sync/<date>-perf`
2. Dispatch `gh workflow run "PR Full Debug Build" --ref <branch>` on both branches.
3. Find each run with `gh run list --workflow "PR Full Debug Build" --json databaseId,headSha`, and wait for green on the exact SHAs.

## 9. Publish (ask the owner first)

1. Push both layers in one atomic, leased push:
   ```
   git push --atomic origin --force-with-lease=refs/heads/main:<OLD_MAIN> \
     --force-with-lease=refs/heads/perf/shield-pass:<OLD_PERF> \
     NEW_MAIN:refs/heads/main NEW_PERF:refs/heads/perf/shield-pass
   ```
2. `git fetch origin`, then move the local layers to the pushed tips: `git branch -f main origin/main` and
   `git branch -f perf/shield-pass origin/perf/shield-pass` (`git reset --hard origin/<layer>` for the one that
   is checked out). Otherwise the next preflight blocks on "local main differs".
3. Delete the `sync/<date>-*` branches on origin.
4. Rebase each carried-over local branch: `git rebase --onto origin/perf/shield-pass <backup> <branch>`.
5. Dispatch `Upstream drift` (`gh workflow run "Upstream drift"`); the issue closes once the fork is level.

## 10. Follow-ups (a normal PR through /ship)

Create a branch `fork/u1-5-sync-<date>-followups` from `origin/perf/shield-pass`. It carries:
- the Nvidia-gated keeps the owner rule asked for, one finding per commit, each with a revert switch and a test;
- `docs/shield-todo.md`: new rows, and the "Upstream defaults taken with the sync of <date>" section;
- `ROADMAP.md`: a progress-log row for U1.5 that cites the PR;
- `docs/ROADMAP_LOG.md`: an entry with the counts, the verify report summary, the gate and CI evidence, the drops with their reasons, and the setting decisions.

## 11. Cleanup (after the follow-up PR merges)

Remove the worktrees and the `backup/*` branches. Keep the `sync/<date>/*` tags: they are the next sync's test oracle. Update the memory note `upstream-sync.md` if a new trap turned up. If the trap is repo knowledge, add it to `sync.json` instead.

## Known traps

Read `traps` in `sync.json`; add new ones there in the follow-up PR. Locale files have their own trap: a fork hunk that carries an upstream-moved string as context re-adds it. `verify` catches the duplicate, and the fix is to drop the fork-side line.
