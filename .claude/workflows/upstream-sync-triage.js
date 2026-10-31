export const meta = {
  name: 'upstream-sync-triage',
  description: 'In evaluation: classify by exception before an upstream sync, with one conflict resolver, up to 3 attachment readers and one critic (args: triage_sets.py output)',
  whenToUse: 'Only for the blind evaluation in .claude/skills/upstream-sync/reference/triage-eval.md until it passes. Step 4 of /upstream-sync still uses upstream-sync-classify',
  phases: [
    { title: 'Read', detail: 'one resolver for the predicted conflicts, up to 3 readers for broken attachments' },
    { title: 'Critique', detail: 'reader coverage, contradictions, added findings' },
  ],
}

// args is the small JSON that .claude/skills/upstream-sync/scripts/triage_sets.py prints: paths and SHAs. The map
// and the diffs stay on disk, and the agents read them there. Nothing about a particular sync lives in this file.
const MAX_ARGS_CHARS = 20000
const MAX_READERS = 3
const a = args
for (const k of ['repo', 'mapPath', 'upstreamRef', 'forkRef', 'mb', 'upstreamTip', 'forkTip', 'counts', 'sets', 'stops', 'conflictFiles', 'protected', 'caps']) {
  if (a == null || a[k] === undefined) throw new Error(`args.${k} is missing: pass the JSON that triage_sets.py prints`)
}
for (const s of a.stops) {
  if (!s?.sha || !s.files?.length) throw new Error('args.stops needs a sha and its files in each row: pass the JSON that triage_sets.py prints')
}
const argsChars = JSON.stringify(a).length
if (argsChars > MAX_ARGS_CHARS) throw new Error(`args has ${argsChars} chars, the limit is ${MAX_ARGS_CHARS}: pass paths and SHAs, the map stays on disk`)
if (a.sets.length > MAX_READERS) throw new Error(`args.sets has ${a.sets.length} sets, the limit is ${MAX_READERS}: dropping one would leave its files unread`)
for (const c of a.caps) log(`Cap: ${c}`)
log(`Triage: ${a.stops.length} predicted stop(s), ${a.conflictFiles.length} conflict file(s), ${a.sets.length} reader(s) on ${a.sets.flat().length} of ${a.counts.bothChanged} files both sides changed`)

const emptySummary = { complete: true, failedGroups: [], unclassified: [], uncovered: [], unresolvedFiles: [], criticIssues: [], drop: [], resolve: [], adapt: [], keepClean: [], protectedAsk: [], defaults: [] }
if (a.sets.length === 0 && a.stops.length === 0 && a.conflictFiles.length === 0) {
  log('Nothing to triage: no main-code file changed on both sides and no predicted conflict.')
  return { groups: [], adapts: [], resolutions: [], critic: null, summary: emptySummary }
}

const git = `git -C ${a.repo}`
const PRE = `Repository: ${a.repo} (an Android TV app in Kotlin; a fork tuned for the Nvidia Shield TV Pro 2019).
The fork ${a.forkRef} (${a.forkTip}) will be REBASED onto ${a.upstreamRef} (${a.upstreamTip}). Merge base: ${a.mb}. Fork commits: ${a.counts.fork}. During the rebase "ours" = upstream, "theirs" = the fork commit.

The map is ${a.mapPath} (JSON). Read it first. Keys: rules (the owner rule and known traps, apply them), trialTree (a tree with the merge of both tips; conflicted files hold markers), conflictFiles, stops, excluded, sets.

STRICTLY READ-ONLY. Never checkout, switch, stash, reset, commit, rebase or edit anything. Read only these sources:
- the map, and ${a.prTitlesPath || 'no other file'}${a.prTitlesPath ? ' (official PR titles)' : ' on disk'};
- git objects, through \`${git} ...\`: \`show <rev>:<path>\` (with ${a.forkRef}, ${a.upstreamRef} or the trialTree), \`show <sha>\`, \`diff ${a.mb} ${a.upstreamRef} -- <path>\`, \`log ${a.mb}..${a.upstreamRef} -- <path>\`, \`grep <pattern> <rev>\`, \`merge-tree --write-tree\`.
Never read a working tree, another checkout, memory notes, or anything else outside these sources: docs and plans count only as they are at ${a.forkRef}. No network and no gh.

Commits marked protected in the map belong to a feature the owner protects (the AAC-to-DTS transcode, the HDR-to-Dolby-Vision conversion). Every line they add must survive. No agent drops one: report what upstream supersedes, and the owner decides.
Cite ${a.upstreamRef} file:line or an upstream sha for every claim, and read the code before you claim. The rebase is done by hand from your output.`

const CHECKLIST = `Attachment checklist. An attachment is a place where fork code depends on upstream code staying as it was. Ask each question of every fork change in your files:
1. Callers: did upstream add a caller of a function the fork patched, or a path that goes around it? Does the new caller get the fork's behaviour?
2. Settings propagation: does a setting or switch the fork adds or reads still reach every new place upstream decides from?
3. Factory installation: did upstream add or change a factory or the wiring that builds an object, so the fork's object is not installed or misses an input?
4. Binary selection: does a changed condition now select another binary, library or code path than the one the fork patched?
5. Changed meaning: did upstream change what a value means that the fork passes or tests, so the fork's code still compiles and now asks for something else?
6. Transient state: did upstream add a new consumer of state that the fork changes for a short time, with no guard for that time?`

const ADAPT = {
  type: 'object',
  properties: {
    fork_sha: { type: 'string' },
    file: { type: 'string' },
    line: { type: 'integer' },
    attachment: { type: 'string' },
    fix: { type: 'string' },
    evidence: { type: 'array', items: { type: 'string' } },
  },
  required: ['fork_sha', 'file', 'line', 'attachment', 'fix', 'evidence'],
}
const SETTING = {
  type: 'object',
  properties: {
    upstream_sha: { type: 'string' },
    description: { type: 'string' },
    owner_rule: { type: 'string', enum: ['keep-fork-gated', 'take-upstream'] },
    shield_todo_note: { type: 'string' },
  },
  required: ['upstream_sha', 'description', 'owner_rule'],
}
const RESOLVER_SCHEMA = {
  type: 'object',
  properties: {
    stops: { type: 'array', items: { type: 'object', properties: {
      sha: { type: 'string' },
      status: { type: 'string', enum: ['resolved', 'false-alarm', 'ask-owner'] },
      resolutions: { type: 'array', items: { type: 'object', properties: {
        path: { type: 'string' }, block: { type: 'integer' },
        upstream_side: { type: 'string' }, fork_side: { type: 'string' }, merged: { type: 'string' },
      }, required: ['path', 'block', 'upstream_side', 'fork_side', 'merged'] } },
      note: { type: 'string' },
    }, required: ['sha', 'status', 'resolutions', 'note'] } },
    adapts: { type: 'array', items: ADAPT },
    open_questions: { type: 'array', items: { type: 'string' } },
  },
  required: ['stops', 'adapts', 'open_questions'],
}
const READER_SCHEMA = {
  type: 'object',
  properties: {
    covered_files: { type: 'array', items: { type: 'string' } },
    not_covered: { type: 'array', items: { type: 'object', properties: { path: { type: 'string' }, reason: { type: 'string' } }, required: ['path', 'reason'] } },
    adapts: { type: 'array', items: ADAPT },
    superseded: { type: 'array', items: { type: 'object', properties: {
      fork_sha: { type: 'string' }, upstream_sha: { type: 'string' },
      scope: { type: 'string', enum: ['whole', 'part'] },
      evidence: { type: 'array', items: { type: 'string' } },
    }, required: ['fork_sha', 'upstream_sha', 'scope', 'evidence'] } },
    settings: { type: 'array', items: SETTING },
    open_questions: { type: 'array', items: { type: 'string' } },
  },
  required: ['covered_files', 'not_covered', 'adapts', 'superseded', 'settings', 'open_questions'],
}
const CRITIC_SCHEMA = {
  type: 'object',
  properties: {
    coverage_ok: { type: 'boolean' },
    gaps: { type: 'array', items: { type: 'string' } },
    contradictions: { type: 'array', items: { type: 'string' } },
    added_adapts: { type: 'array', items: ADAPT },
    added_settings: { type: 'array', items: SETTING },
  },
  required: ['coverage_ok', 'gaps', 'contradictions', 'added_adapts', 'added_settings'],
}

const resolverPrompt = `${PRE}

You are the resolver. You own every predicted conflict: ${a.stops.length} stop(s) in map.stops and the files in map.conflictFiles. Another agent reads the semantics of the same files; your job is the text of each stop.

A stop comes from a forward replay. Its \`head\` is the replayed commit it applies to, so \`${git} merge-tree --write-tree --merge-base=<sha>^ <head> <sha>\` gives the tree with the conflict markers of that stop. The replay kept upstream's side at earlier stops, so the real rebase can differ a little. alsoInTheirsReplay=false means a second replay did not stop there: it is more likely a false alarm, but check it.

For each stop, return one row with the map's sha:
- resolved: one row per conflict block: what upstream's side does, what the fork's side does, and the exact merged text. Read the block's exact extent first; a block can end before shared closing lines. Keep every line of a protected commit. Every file in the stop's \`files\` needs at least one row. A file with no markers (a delete or a binary) still gets one: say in merged what to keep. A stop with a file left out counts as unresolved.
- false-alarm: the conflict comes only from the replay's choice at an earlier stop; say why in the note.
- ask-owner: only when the map's rules do not settle it; say what the owner must decide in the note.
Add a row for any file in map.conflictFiles that no stop names, under the fork commit that causes it.
A resolution can compile and still break the fork. When it does, report an adapt too, under that fork commit: fork_sha, the file:line at ${a.upstreamRef}, the attachment broken and the fix.

${CHECKLIST}`

const readerPrompt = (files, i) => `${PRE}

You are reader ${i + 1} of ${a.sets.length}. You answer for these ${files.length} files, which both sides changed; no other reader has them. map.sets[${i}] lists the fork commits of each:
${files.map(f => `- ${f}`).join('\n')}

Return exceptions only. Most fork commits are fine and need no word from you; there is no per-commit verdict. Git cannot see what you look for: such a problem merges cleanly and shows only when you read across files. A resolver handles text conflicts; a file can be in conflict and still hold a broken attachment, so check those too.

How to work:
1. Learn upstream's themes: \`${git} log --first-parent --format='%h %s' ${a.mb}..${a.upstreamRef}\`, and \`${git} log --no-merges --format='%h %s' ${a.mb}..${a.upstreamRef} -- <file>\` for your files${a.prTitlesPath ? `, and the official PR titles in ${a.prTitlesPath}` : ''}. A theme says what upstream was building.
2. Read upstream's change in each of your files: \`${git} diff ${a.mb} ${a.upstreamRef} -- <file>\`.
3. Read what the fork does there: \`${git} show <sha> -- <file>\` for its commits, and the merged file in the trialTree.
4. Follow callers and consumers into any file at ${a.upstreamRef}. You answer for your files alone, but your reading is free. \`${git} grep\` finds the callers, and an attachment often breaks in a file the fork never touched.

${CHECKLIST}

Return:
- adapts: one per broken attachment: the fork commit (fork_sha), the file and line at ${a.upstreamRef} where it breaks, the attachment broken, the exact fix, and the evidence. Check a claim by reading both ends. Say nothing about something that is merely different.
- superseded: a fork commit that upstream already does in whole or in part (or a fork commit and its own revert, both). Evidence with file:line. A protected commit may be listed here too; the owner decides.
- settings: upstream changes that interact with a value the fork sets: a changed default, a renamed setting key. owner_rule = keep-fork-gated when the Shield plan (docs/shield-todo.md, ROADMAP.md at ${a.forkRef}) already sets that value, take-upstream when it does not.
- covered_files: exactly the files of your list that you read to the end, spelled as listed, nothing else. Cross-file reads do not go here. not_covered: any file of your list you could not finish, with the reason.`

phase('Read')
const resolverWanted = a.stops.length > 0 || a.conflictFiles.length > 0
const replies = await parallel([
  ...(resolverWanted ? [() => agent(resolverPrompt, { label: 'resolver', phase: 'Read', schema: RESOLVER_SCHEMA, model: 'opus', effort: 'medium' })] : []),
  ...a.sets.map((files, i) => () => agent(readerPrompt(files, i), { label: `reader-${i + 1}`, phase: 'Read', schema: READER_SCHEMA, model: 'opus', effort: 'high' })),
])
const resolver = resolverWanted ? replies[0] : null
const readers = resolverWanted ? replies.slice(1) : replies
const failedGroups = [
  ...(resolverWanted && !resolver ? ['resolver'] : []),
  ...a.sets.flatMap((_, i) => (readers[i] ? [] : [`reader-${i + 1}`])),
]

// What the code can check without an agent: every assigned file came back covered, every stop has an answer.
const uncovered = a.sets.flatMap((files, i) => {
  const r = readers[i]
  const skipped = new Set((r?.not_covered || []).map(n => n.path))
  const covered = new Set((r?.covered_files || []).filter(f => !skipped.has(f)))
  return files.filter(f => !covered.has(f))
})
const sameSha = (x, y) => {
  const n = Math.min(x.length, y.length)
  return n >= 7 && x.slice(0, n).toLowerCase() === y.slice(0, n).toLowerCase()
}
const rows = resolver?.stops || []
const rowOf = sha => rows.find(r => sameSha(r.sha, sha))
// A resolved stop needs text for each of its own files: a row for another stop on the same file does not answer it.
const stopGap = s => {
  const r = rowOf(s.sha)
  if (r?.status === 'false-alarm') return null
  if (r?.status !== 'resolved') return r?.note || 'predicted stop with no resolution'
  const missing = s.files.filter(f => !r.resolutions.some(x => x.path === f))
  return missing.length ? `resolved with no text for ${missing.join(', ')}` : null
}
const unclassified = a.stops.filter(stopGap).map(s => ({ sha: s.sha, subject: stopGap(s) }))
const namedFiles = new Set(rows.filter(r => r.status === 'resolved').flatMap(r => r.resolutions.map(x => x.path)))
const unresolvedFiles = a.conflictFiles.filter(f => !namedFiles.has(f))
log(`${readers.filter(Boolean).length}/${a.sets.length} readers and ${resolver ? 1 : 0}/${resolverWanted ? 1 : 0} resolver returned`)
if (failedGroups.length) log(`FAILED (rerun them): ${failedGroups.join(', ')}`)
if (uncovered.length) log(`${uncovered.length} assigned file(s) not covered: ${uncovered.join(', ')}`)
if (unclassified.length) log(`${unclassified.length} stop(s) with no resolution: ${unclassified.map(u => u.sha).join(', ')}`)
if (unresolvedFiles.length) log(`${unresolvedFiles.length} conflict file(s) with no resolution: ${unresolvedFiles.join(', ')}`)

const brief = readers.map((r, i) => ({ reader: i + 1, files: a.sets[i], result: r }))
phase('Critique')
const critic = await agent(`${PRE}

You are the critic of this triage. Be short: your job is what the others left out, not a second pass over everything. Your findings count: a gap or contradiction you report keeps the triage from being complete, and every adapt you add goes on the list.

1. Coverage: list a gap for each assigned file a reader did not really read, for each upstream theme of \`${git} log --first-parent --format='%h %s' ${a.mb}..${a.upstreamRef}\` that touches code the fork patches and no reader's answer shows it considered, and for each failed agent.
2. Files outside the sets: \`${git} diff --stat ${a.mb} ${a.upstreamRef}\` shows what upstream changed that no reader answers for, and fork commits that touch only such files were not read at all. If such a change reaches code the fork patches (a renamed API, a moved call site, a changed default), add the finding.
3. Contradictions: a resolver text and a reader fix that disagree, or two agents that disagree about the same code. Spot-check the two riskiest adapts by reading their evidence; one that does not hold is a contradiction.
4. Owner rule: check each settings row against docs/shield-todo.md and ROADMAP.md at ${a.forkRef}. A wrong rule is a contradiction.

coverage_ok is true only when you found no gap. added_adapts and added_settings take the same fields as the rows below, and are for findings nobody else has. You cannot propose a drop.

${CHECKLIST}

Failed agents: ${failedGroups.join(', ') || 'none'}. Files with no coverage: ${uncovered.join(', ') || 'none'}.
Stops and resolutions:
${JSON.stringify(rows)}
Resolver adapts:
${JSON.stringify(resolver?.adapts || [])}
Readers:
${JSON.stringify(brief)}`, { label: 'critic', phase: 'Critique', schema: CRITIC_SCHEMA, model: 'opus', effort: 'medium' })
if (!critic) {
  failedGroups.push('critic')
  log('FAILED (rerun it): critic')
}

// Plain-code summary, with the keys the skill and `sync.py decisions` read. A commit named twice keeps the more
// cautious verdict. There is no per-commit verdict by exception, so keepClean stays empty.
const key = sha => sha.slice(0, 9).toLowerCase()
const usableSha = sha => /^[0-9a-f]{9,}$/i.test(sha)
const isProtected = sha => a.protected.some(p => sameSha(p, sha))
const rank = { 'drop-superseded': 0, 'keep-resolve': 1, 'partial-superseded': 2, 'keep-adapt': 3 }
const commits = new Map()
const rowKey = sha => [...commits.keys()].find(k => sameSha(k, sha)) ?? key(sha)
const note = (sha, verdict, fields) => {
  const found = rowKey(sha)
  const row = commits.get(found) || { sha: sha.slice(0, 12), verdict, conflict_files: [], rationale: [], evidence: [], resolution_guidance: [] }
  if (rank[verdict] > rank[row.verdict]) {
    if (row.verdict === 'drop-superseded') log(`${key(sha)}: superseded claim overruled by ${verdict}`)
    row.verdict = verdict
  } else if (verdict === 'drop-superseded' && row.verdict !== verdict) {
    log(`${key(sha)}: superseded claim overruled by ${row.verdict}`)
  }
  row.conflict_files.push(...(fields.files || []))
  if (fields.why) row.rationale.push(fields.why)
  row.evidence.push(...(fields.evidence || []))
  if (fields.guidance) row.resolution_guidance.push(fields.guidance)
  if (sha.length > row.sha.length) row.sha = sha.slice(0, 12)
  if (found !== key(row.sha)) commits.delete(found)
  commits.set(key(row.sha), row)
}

const openQuestions = [...(resolver?.open_questions || []), ...readers.flatMap(r => r?.open_questions || [])]
const resolved = rows.filter(r => r.status === 'resolved')
const answered = r => r.resolutions.length > 0 && !unclassified.some(u => sameSha(u.sha, r.sha))
for (const r of resolved) {
  note(r.sha, 'keep-resolve', {
    files: [...new Set(r.resolutions.map(x => x.path))],
    why: r.note,
    guidance: r.resolutions.map(x => `${x.path} block ${x.block}:\n${x.merged}`).join('\n\n'),
  })
}
const adapts = [
  ...(resolver?.adapts || []).map(x => ({ ...x, source: 'resolver' })),
  ...readers.flatMap((r, i) => (r?.adapts || []).map(x => ({ ...x, source: `reader-${i + 1}` }))),
  ...(critic?.added_adapts || []).map(x => ({ ...x, source: 'critic' })),
]
for (const x of adapts) {
  const why = `${x.file}:${x.line}: ${x.attachment}`
  if (usableSha(x.fork_sha)) note(x.fork_sha, 'keep-adapt', { files: [x.file], why, evidence: x.evidence, guidance: x.fix })
  else openQuestions.push(`An adapt names no usable fork sha (${x.fork_sha}): ${why}`)
}
// An adapt without a usable sha gets no row. It stays in adapts, and it keeps every commit it could name from a drop.
const vagueAdapts = adapts.map(x => x.fork_sha.toLowerCase()).filter(sha => !usableSha(sha))
const held = sha => vagueAdapts.some(v => !/^[0-9a-f]+$/.test(v) || sha.toLowerCase().startsWith(v))
// The protected rule, in code: an agent's claim on a protected commit is the owner's question and never a drop.
const protectedAsk = []
for (const s of readers.flatMap(r => r?.superseded || [])) {
  const why = `upstream ${s.upstream_sha} supersedes ${s.scope === 'whole' ? 'it' : 'part of it'}`
  if (!usableSha(s.fork_sha)) {
    openQuestions.push(`A superseded claim names no usable fork sha (${s.fork_sha}): ${why}`)
  } else if (isProtected(s.fork_sha)) {
    protectedAsk.push(key(s.fork_sha))
    openQuestions.push(`Protected ${key(s.fork_sha)}: ${why}. The owner decides.`)
    note(s.fork_sha, 'keep-adapt', { why: `${why}; protected, the owner decides`, evidence: s.evidence })
  } else if (held(s.fork_sha)) {
    note(s.fork_sha, 'keep-adapt', { why: `${why}; an adapt with no usable sha may name this commit`, evidence: s.evidence })
  } else {
    note(s.fork_sha, s.scope === 'whole' ? 'drop-superseded' : 'partial-superseded', { why, evidence: s.evidence })
  }
}
const groupCommits = [...commits.values()].map(c => ({
  ...c,
  conflict_files: [...new Set(c.conflict_files)],
  rationale: c.rationale.join('\n'),
  resolution_guidance: c.resolution_guidance.join('\n\n'),
}))
const pick = v => [...commits].filter(([, c]) => c.verdict === v).map(([k]) => k)
const settings = [...readers.flatMap(r => r?.settings || []), ...(critic?.added_settings || [])]
const criticIssues = [
  ...(critic && !critic.coverage_ok && !critic.gaps.length ? ['critic: coverage not confirmed'] : []),
  ...(critic?.gaps || []).map(g => `gap: ${g}`),
  ...(critic?.contradictions || []).map(c => `contradiction: ${c}`),
]
for (const i of criticIssues) log(`Critic: ${i}`)
const summary = {
  complete: failedGroups.length === 0 && unclassified.length === 0 && unresolvedFiles.length === 0
    && uncovered.length === 0 && criticIssues.length === 0,
  failedGroups,
  unclassified,
  uncovered,
  unresolvedFiles,
  criticIssues,
  drop: pick('drop-superseded'),
  resolve: resolved.filter(answered).map(r => rowKey(r.sha)),
  adapt: [...pick('keep-adapt'), ...pick('partial-superseded')],
  keepClean: [],
  protectedAsk: [...new Set(protectedAsk)],
  defaults: settings.map(u => ({ upstream: u.upstream_sha, rule: u.owner_rule, what: u.description, note: u.shield_todo_note || '' })),
}
log(`complete ${summary.complete}; drop ${summary.drop.length}, resolve ${summary.resolve.length}, adapt ${summary.adapt.length} (critic added ${critic?.added_adapts.length ?? 0}), setting decisions ${summary.defaults.length}, protected asks ${summary.protectedAsk.length}, open questions ${openQuestions.length}`)
return {
  groups: [{ group: 'triage', commits: groupCommits, upstream_interactions: settings.map(u => ({ ...u, effect_on_fork: u.description, action: u.owner_rule })), open_questions: openQuestions }],
  adapts,
  resolutions: rows,
  critic,
  summary,
}
