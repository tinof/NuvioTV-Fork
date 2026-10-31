# Blind evaluation of the triage workflow

`upstream-sync-triage` replaces per-commit verdicts with exceptions: one resolver, up to 3 readers, one critic.
`upstream-sync-classify` stays the default until this test passes.

The test replays the sync of 2026-10-31 on frozen state. Its 5 adapts all merged cleanly or needed more than the
conflict text, and they needed reading across files, so they are what the new shape must find.

**Pass rule:** all 5 rows of the score sheet are found by readers or the critic. A row only the resolver found does
not count. Anything less is a fail, and the old workflow stays.

**This file holds the answers.** Never pass it, or its path, to an eval agent, and never copy it into the eval clone.

## 1. Frozen state

The clone holds three refs and nothing else: no follow-up commits, no later tags, no remote, no reflog. A worktree
will not do, because it shares every ref of the real repository, and the commits after the sync hold the 5 fixes.

`EVAL` is a new, empty directory in the session scratchpad. `REPO` is any checkout of this repository that holds the
tags and is not being changed by another session.

```sh
mkdir -p "$EVAL"
git init -q "$EVAL/repo"
git -C "$EVAL/repo" fetch -q --no-tags \
  --upload-pack 'git -c uploadpack.allowAnySHA1InWant=true upload-pack' "file://$REPO" \
  refs/tags/sync/2026-10-31/old-base:refs/tags/old-base \
  refs/tags/sync/2026-10-31/old-perf:refs/tags/old-perf \
  d9039df69dba55f63b33b1806a99505aa6da3809:refs/tags/upstream
git -C "$EVAL/repo" checkout -q --detach old-perf
```

The docs in the clone are the ones from before the sync: agents read `docs/shield-todo.md`, `ROADMAP.md` and the
traps at `old-perf`. There is no `classify.json` and no `overlap.json` in `EVAL`. Do not create them.

## 2. The args

Run the helper from the branch under test, on the clone, with the old `sync.json`. Its traps are the ones known
before the sync; today's `sync.json` holds traps learned from it.

```sh
cd "$EVAL/repo"
python3 -I "$BRANCH/.claude/skills/upstream-sync/scripts/triage_sets.py" \
  --base old-base --fork old-perf --upstream upstream \
  --config "$EVAL/repo/.claude/skills/upstream-sync/sync.json" \
  --out "$EVAL/map.json" > "$EVAL/args.json"
```

`BRANCH` is the checkout that holds the workflow under test. Do not pass `--pr-titles`: nobody saved the official
titles from before the sync.

Check the setup before any agent runs. From the first run of the helper on this state (2026-10-31):

| Key | Expected |
| --- | --- |
| `args.forkTip`, `args.upstreamTip` | `30e56e5c9...`, `d9039df69...` |
| `args.counts` | 262 fork commits, 75 files both sides changed, 33 of them main code |
| `args.sets` | 3 sets; upstream lines 798, 709 and 328 in `map.json` |
| `args.stops`, `args.conflictFiles` | 14 stops (8 also in the theirs replay) with 16 stop files, 7 conflict files |
| size | about 8 KB of args (limit 20,000 chars), about 40 KB of map, about 15 s |

## 3. The run

Start a new session with its working directory at `$EVAL/repo`. A session keeps its memory notes by project
directory, so one started there has none. If a new session is not possible, run from the current one and record what
the memory index names at that moment (on 2026-10-31 it named no adapt). The index reaches every agent and the leak
check sees only tool calls, so such a run is weaker. The prompts forbid opening the notes. The workflow still sits in `BRANCH`, so pass its path:

```
Workflow({scriptPath: "<BRANCH>/.claude/workflows/upstream-sync-triage.js", args: <the object in args.json>})
```

Pass args as a JSON object. Do not edit the prompts for this run, and give the agents no hint beyond the workflow.
Save the result to `$EVAL/result.json`, and keep the transcript directory the tool result names.

**Leak check, before scoring.** Search the transcripts for tool calls that left the frozen sources: paths under the
real repository, the worktrees, `.claude/projects`, this session's other scratch files, any `classify.json` or
`triage-eval`, and any `gh` or network call. A hit on one of these voids the run. Run it again.

## 4. Scoring

The orchestrator scores, after the run and outside the eval session. Read `adapts` in `result.json`; each row has a
`source` (`resolver`, `reader-N` or `critic`). A row is a hit when it names the same break at the same upstream code.
The fork commit alone is not enough, and a neighbouring fork commit of the same change is not a miss.

**Score sheet. Eval agents never see this.**

| Fork commit | Short name | The break | Upstream code |
| --- | --- | --- | --- |
| `9b616ffbc` | route gate | The IEC sink reads the raw output route, while the fork holds the route on HDMI under an idle Bluetooth sink. IEC is refused on the path this commit restores. The sink must see the corrected route. | `IecPassthroughAudioSink.kt` `hbrIecAllowed`, `iec/IecOutputRoutePolicy.kt`, the `outputRouteKey` wiring in `PlayerRuntimeControllerInitialization.kt` |
| `2f14acf40` | USB tunnelling seed | The fork seeds `tunnelingApplied` from `effectiveTunnelingEnabled`; upstream's active tunnelling now also excludes USB audio. A USB-audio box seeds true and takes the tunnel surface layout. | `tunnelingBlockedByUsbAudio` and `tunnelingActive` in `PlayerRuntimeControllerInitialization.kt` |
| `13b982274` | needsVideoEffectRebuild during quiesce | Upstream's new `needsVideoEffectRebuild` condition runs on every tracks callback with no guard for the AFR audio and video quiesce window. | `maybeAdjustLibassPipelineForTracks` in `PlayerRuntimeControllerTracks.kt` |
| `9e0478852` | strongHardware meaning | The fork passes `strongHardware=false` to mean no swap room. Upstream made false select the small-box relaxation, so the Shield gets room at low swap. | `roomBesideSwap` in `MemoryGovernor.kt` |
| `ad6d7d8e8` | lazy deferred isActive | The fork's `CoroutineStart.LAZY` lookups report `isActive=false` before they start, so upstream's `noteJoined` misses a foreground joiner, which then waits for the player to close. | `noteJoined` and `holdWhilePlayerOpen` in `MetaRepositoryImpl.kt` |

Report, for each row: hit or miss, which agent found it, and whether the fix is usable. Also report:

- the pass or fail by the rule above;
- `summary.complete`, and each `criticIssues` entry;
- adapts not on the sheet, each checked by hand as real or false: the old workflow had no false adapt here, and a
  list of noise would cost what the old one cost;
- predicted stops marked `false-alarm`, and any real stop the resolver's text would not resolve;
- agents, tokens and minutes, against one analyst per theme on the same sync (65 of 81 verdicts changed nothing).

## 5. After the run

A fail is a result: report it and leave the default alone. A prompt changed after reading the misses is no longer
blind on this sync, so test it on another frozen sync as well (`sync/2026-10-26/*`), where the agents' answers can
be compared with what that sync's rebase changed.

A pass does not switch the default by itself. That is a separate change to step 4 of `SKILL.md`, after the owner has
seen the score. It must also say how a protected drop reaches the rebase: `summary.drop` never holds one, so an
approved drop goes to `todo-edit --drop` and `verify --protected-drop` by hand.
