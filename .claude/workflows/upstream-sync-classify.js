export const meta = {
  name: 'upstream-sync-classify',
  description: 'Classify each fork commit against new upstream commits before an upstream sync (args: sync.py map-overlap output)',
  whenToUse: 'Step 4 of /upstream-sync, with the JSON that `sync.py map-overlap` printed as args',
  phases: [
    { title: 'Analyze', detail: 'one Opus analyst per overlap group' },
    { title: 'Critique', detail: 'coverage, contradictions, spot checks, owner rule' },
  ],
}

// args is the JSON that .claude/skills/upstream-sync/scripts/sync.py map-overlap prints. Nothing about a
// particular sync lives in this file: the groups, commits, conflicts and rules all come from args.
const a = args
for (const k of ['repo', 'scratch', 'upstreamRef', 'forkRef', 'mb', 'groups', 'mergeConflicts', 'rules', 'scale', 'autoClean', 'counts']) {
  if (a == null || a[k] === undefined) throw new Error(`args.${k} is missing: pass the JSON from sync.py map-overlap`)
}
if (a.scale.tier === 'skip' || a.groups.length === 0) {
  log('Scale tier skip: no fork commit touches a file upstream changed and the trial merge is clean. Nothing to classify.')
  return { groups: [], critic: null, summary: { drop: [], resolve: [], adapt: [], keepClean: [], protectedAsk: [], defaults: [] } }
}
for (const t of a.truncated || []) log(`Truncated: ${t}`)
log(`Tier ${a.scale.tier}: ${a.groups.length} group(s); ${a.scale.reasons.join('; ')}`)

const PRE = `Repository: ${a.repo} (an Android TV app in Kotlin; a fork tuned for the Nvidia Shield TV Pro 2019).
The fork will be REBASED onto ${a.upstreamRef} (${a.upstreamTip}). Merge base: ${a.mb}. One branch, ${a.forkRef}: upstream, then the fork's CI, test and fix commits at the bottom, then the Shield work. Upstream added ${a.counts.upstream} commits (${a.counts.upstreamFirstParent} first-parent).

Rules for this fork:
${a.rules.map(r => `- ${r}`).join('\n')}

STRICTLY READ-ONLY: never checkout, switch, stash, reset, commit, rebase or edit anything in the repository or its working tree. Read files with \`git show <rev>:<path>\`, diffs with \`git show <sha>\`, \`git diff ${a.mb} ${a.upstreamRef} -- <path>\`, \`git log ${a.mb}..${a.upstreamRef} -- <path>\`. \`git merge-tree --write-tree\` is allowed (it does not touch the worktree). Scratch files only under ${a.scratch}/<your group>/.

For EVERY fork commit listed, decide a verdict:
- keep-clean: applies without a textual conflict and nothing upstream changes its meaning
- keep-resolve: a textual conflict is expected; give hunk-level guidance (what upstream's side does, what the fork's side does, how the merged code must read)
- keep-adapt: may apply cleanly, but upstream changed behaviour around it, so the fork change must be adjusted (say exactly how, and which paths change)
- drop-superseded: upstream already does the same thing (cite the upstream commit and file:line at ${a.upstreamRef}); also use it for a fork commit and its own revert (a net-zero pair: both, never one)
- partial-superseded: part of it is now upstream; say which part to drop
List upstream changes that interact with the fork's Nvidia defaults (a changed default, a renamed setting key, moved code the fork patches, a new path the fork's gating should cover), and for each apply the owner rule: owner_rule = keep-fork-gated when the Shield plan (docs/shield-todo.md, ROADMAP.md) already sets that value, take-upstream when it does not, n/a when it is not a setting. Cite ${a.upstreamRef} file:line or an upstream sha for every claim; the rebase is done by hand from your guidance. During the rebase "ours" = the new base (upstream), "theirs" = the fork commit.

Commits marked [PROTECTED: <feature>] belong to a feature the owner protects (the AAC-to-DTS transcode, the HLG-to-Dolby-Vision conversion). Never give one drop-superseded or partial-superseded on your own judgement: if upstream really does the same, give keep-adapt and say so in open_questions, so the owner decides. Their resolution guidance must keep every line those commits add.

Files that conflict in a trial 3-way merge of the two tips: ${a.mergeConflicts.join(', ') || 'none'}. Files changed on both sides that merged cleanly can still interact.`

const COMMIT = {
  type: 'object',
  properties: {
    sha: { type: 'string' }, subject: { type: 'string' },
    verdict: { type: 'string', enum: ['keep-clean', 'keep-resolve', 'keep-adapt', 'drop-superseded', 'partial-superseded'] },
    conflict_files: { type: 'array', items: { type: 'string' } },
    rationale: { type: 'string' },
    evidence: { type: 'array', items: { type: 'string' } },
    resolution_guidance: { type: 'string' },
  },
  required: ['sha', 'verdict', 'rationale', 'evidence', 'resolution_guidance'],
}
const SCHEMA = {
  type: 'object',
  properties: {
    group: { type: 'string' },
    commits: { type: 'array', items: COMMIT },
    upstream_interactions: { type: 'array', items: { type: 'object', properties: {
      upstream_sha: { type: 'string' }, description: { type: 'string' },
      effect_on_fork: { type: 'string' }, action: { type: 'string' },
      owner_rule: { type: 'string', enum: ['keep-fork-gated', 'take-upstream', 'n/a'] },
      shield_todo_note: { type: 'string' },
    }, required: ['upstream_sha', 'description', 'effect_on_fork', 'action', 'owner_rule'] } },
    open_questions: { type: 'array', items: { type: 'string' } },
  },
  required: ['group', 'commits', 'upstream_interactions', 'open_questions'],
}

const groupPrompt = g => `${PRE}

Group: ${g.key}. Focus: ${g.focus}

Fork commits in this group (classify every one):
${g.commits.map(c => `- ${c.sha}${c.protected ? ` [PROTECTED: ${c.protected}]` : ''} ${c.subject}\n  files upstream also changed: ${c.overlapFiles.join(', ')}`).join('\n')}

Upstream commits that touch these files (read them; ${g.upstreamCommits.length} listed):
${g.upstreamCommits.map(u => `- ${u.sha} ${u.subject} (${u.files.join(', ')})`).join('\n') || '- none'}

Trial-merge conflicts in this group: ${g.conflictFiles.join(', ') || 'none'}; give exact resolutions for them.`

phase('Analyze')
const raw = await parallel(a.groups.map(g => () =>
  agent(groupPrompt(g), { label: `analyze:${g.key}`, phase: 'Analyze', schema: SCHEMA, model: 'opus', effort: 'medium' })
))
const results = raw.filter(Boolean)
// A group whose analyst failed must not vanish: its commits come back unclassified, and step 4 stops on them.
const failedGroups = a.groups.filter((g, i) => !raw[i]).map(g => g.key)
const classified = new Set(results.flatMap(r => (r.commits || []).map(c => c.sha.slice(0, 9))))
const unclassified = a.groups.flatMap(g => g.commits).filter(c => !classified.has(c.sha.slice(0, 9)))
  .map(c => ({ sha: c.sha, subject: c.subject }))
log(`${results.length}/${a.groups.length} group analyses returned`)
if (failedGroups.length) log(`FAILED groups (rerun them): ${failedGroups.join(', ')}`)
if (unclassified.length) log(`${unclassified.length} fork commit(s) have no verdict: ${unclassified.map(c => c.sha.slice(0, 9)).join(', ')}`)

let critic = null
if (a.groups.length >= 2) {
  phase('Critique')
  critic = await agent(`${PRE}

You are the critic for this classification. Tasks:
1. Coverage: every fork commit in ${a.mb}..${a.forkRef} must have a verdict. The commits below touched no file upstream changed and were not sent to an analyst ("autoClean"); flag any that upstream still affects semantically (a renamed API, a moved call site, a changed default), with evidence.
${a.autoClean.map(c => `- ${c.sha.slice(0, 12)}${c.protected ? ` [PROTECTED: ${c.protected}]` : ''} ${c.subject}`).join('\n')}
2. Contradictions: the same commit with different verdicts, or two groups whose guidance disagrees for the same file.
3. Spot-check the 3 riskiest drop-superseded, partial-superseded or keep-adapt verdicts by reading the cited evidence yourself; say whether each holds.
4. Upstream changes no group read that touch code the fork patches.
5. Owner rule: check each upstream_interactions owner_rule against docs/shield-todo.md and ROADMAP.md at ${a.forkRef}.

Analyses:
${JSON.stringify(results)}`, {
    label: 'critic', phase: 'Critique', model: 'opus', effort: 'medium',
    schema: { type: 'object', properties: {
      unclassified: { type: 'array', items: { type: 'object', properties: { sha: { type: 'string' }, verdict: { type: 'string' }, rationale: { type: 'string' } }, required: ['sha', 'verdict', 'rationale'] } },
      contradictions: { type: 'array', items: { type: 'string' } },
      spot_checks: { type: 'array', items: { type: 'object', properties: { claim: { type: 'string' }, holds: { type: 'boolean' }, note: { type: 'string' } }, required: ['claim', 'holds', 'note'] } },
      missed_upstream: { type: 'array', items: { type: 'string' } },
      owner_rule_issues: { type: 'array', items: { type: 'string' } },
    }, required: ['unclassified', 'contradictions', 'spot_checks', 'missed_upstream', 'owner_rule_issues'] },
  })
}

// Plain-code summary; a commit classified by two groups keeps the more cautious verdict.
const rank = { 'drop-superseded': 0, 'keep-clean': 1, 'partial-superseded': 2, 'keep-resolve': 3, 'keep-adapt': 4 }
const verdicts = {}
for (const r of results) for (const c of r.commits || []) {
  const key = c.sha.slice(0, 9)
  if (!verdicts[key] || rank[c.verdict] > rank[verdicts[key]]) verdicts[key] = c.verdict
}
const pick = v => Object.keys(verdicts).filter(k => verdicts[k] === v)
const protectedShas = new Set(a.groups.flatMap(g => g.commits).filter(c => c.protected).map(c => c.sha.slice(0, 9)))
const summary = {
  complete: failedGroups.length === 0 && unclassified.length === 0,
  failedGroups,
  unclassified,
  drop: pick('drop-superseded'),
  resolve: pick('keep-resolve'),
  adapt: [...pick('keep-adapt'), ...pick('partial-superseded')],
  keepClean: pick('keep-clean'),
  // Owner decisions: a drop or partial verdict on a protected-feature commit, whatever the analyst said.
  protectedAsk: [...pick('drop-superseded'), ...pick('partial-superseded')].filter(k => protectedShas.has(k)),
  defaults: results.flatMap(r => (r.upstream_interactions || []).filter(u => u.owner_rule && u.owner_rule !== 'n/a')
    .map(u => ({ upstream: u.upstream_sha, rule: u.owner_rule, what: u.description, note: u.shield_todo_note || '' }))),
}
log(`complete ${summary.complete}; drop ${summary.drop.length}, resolve ${summary.resolve.length}, adapt ${summary.adapt.length}, clean ${summary.keepClean.length}, setting decisions ${summary.defaults.length}, protected asks ${summary.protectedAsk.length}`)
return { groups: results, critic, summary }
