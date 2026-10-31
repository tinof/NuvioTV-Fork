"""Helpers for /upstream-sync. Run from the repository root as `python3 -I <this file> <command> ...`.

Commands:
  preflight [--allow-branch B ...] [--no-fetch] [--json]
  map-overlap [--upstream R] [--perf R] [--scratch DIR]
  dry-run [--base R] [--fork R] [--onto R] [--json]
  due [--upstream R] [--fork R] [--now EPOCH] [--json]
  official-preview [--url U] [--upstream R] [--fork R]
  judgement [--old R] [--new-base R] [--new R] [--declared FILE] [--diff FILE]
  docs-entry --verify-report FILE [--classify FILE] [--date D] [--pr N]
  todo-edit --drop SHA[,SHA...] <git-rebase-todo>
  stop-check
  drops --decisions classify.json --range BASE..TIP
  verify --base-old R --old R --base-new R --new R --upstream R
         [--decisions classify.json] [--expect-drop SHA,...] [--allow-new SHA,...] [--allow-same PATH ...]
         [--allow-changed PATH ...]
         [--protected-drop SHA,...] [--creation-factor N]
  protected-apk <apk> [--stamp FILE]
  test-failures <junit-xml-dir>
  gate-skip <base> <head>
  results-complete <dir> --sha S
  compare-results --baseline DIR --baseline-sha S --candidate DIR --candidate-sha S

Exit codes: 0 ok, 1 a check failed or the sync is blocked, 2 usage or environment error, 3 nothing to do,
4 protected-apk found only an origin mismatch (shield_build_install.sh can keep that build, and only that).
"""
import argparse
import collections
import glob
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python3 -I leaves the script's directory off sys.path.
import sync_tools
from sync_tools import (FAIL, NOTHING, OK, USAGE, commits_with_files, feature_of, gh_json, git, lines, out,
                        parse_merge_tree, protected_path)

CONFIG_PATH = Path(__file__).resolve().parents[1] / "sync.json"
ORIGIN_MISMATCH = 4  # protected-apk: the libraries are right, their origin is not
# How far a run's JUnit classes may fall short before the run counts as incomplete. A file can hold
# several classes, so a whole run has more classes than test files (919 for 909 on 2026-10-31).
CLASS_MARGIN = 0.05
# Where the protected native libraries come from (protected-apk --stamp).
RPU_LIB, RPU_SOURCE = "lib/arm64-v8a/libnuvio_rpu.so", "native/rpu_builder"
FFMPEG_LIB, FFMPEG_AAR = "lib/arm64-v8a/libffmpegJNI.so", "app/libs/lib-decoder-ffmpeg-release.aar"
FFMPEG_AAR_LIB, FFMPEG_JNI = "jni/arm64-v8a/libffmpegJNI.so", "ffmpeg-decoder-downmix/src/main/jni"
FFMPEG_STATIC_LIBS = ("libavcodec.a", "libavutil.a", "libswresample.a")


# ---------------------------------------------------------------- pure helpers (unit-tested)

def load_config(path=CONFIG_PATH):
    return json.loads(Path(path).read_text())


def gate_skip_specs(workflow_md):
    """The pathspecs on the contract's `gate-skip:` line in .claude/workflow.md."""
    found = re.search(r"^gate-skip:\s*(.+)$", workflow_md, re.M)
    if not found or not shlex.split(found.group(1)):
        raise RuntimeError("no gate-skip line in .claude/workflow.md")
    return shlex.split(found.group(1))


def cherry_plus(text):
    """`git cherry -v <upstream> <head>` output -> [(sha, subject)] of commits not in upstream."""
    out = []
    for line in text.splitlines():
        if line.startswith("+ "):
            parts = line[2:].split(" ", 1)
            out.append((parts[0], parts[1] if len(parts) > 1 else ""))
    return out


def tier2_globs(workflow_md):
    """The globs in the first fenced code block under `## Tier 2 paths` of .claude/workflow.md."""
    lines, inside, fences, out = workflow_md.splitlines(), False, 0, []
    for line in lines:
        if line.startswith("## "):
            if inside:
                break
            inside = line.strip() == "## Tier 2 paths"
            continue
        if not inside:
            continue
        if line.startswith("```"):
            fences += 1
            if fences == 2:
                break
            continue
        if fences == 1 and line.strip():
            out.append(line.strip())
    return out


def theme_of(path, themes):
    for theme in themes:
        if any(re.search(p, path) for p in theme["paths"]):
            return theme["key"]
    return None


def commit_theme(files, themes, subject=""):
    """The theme whose `subjects` pattern matches the commit subject (its conventional-commit scope), else the
    theme most of [files] belong to; ties go to the earlier theme in the table."""
    for theme in themes:
        if theme.get("subjects") and re.search(theme["subjects"], subject):
            return theme["key"]
    order = [t["key"] for t in themes]
    counts = collections.Counter(k for k in (theme_of(f, themes) for f in files) if k)
    if not counts:
        return order[-1]
    best = max(counts.values())
    return next(k for k in order if counts.get(k) == best)


def scale_tier(overlap_commits, conflicts, scale, light=None, blockers=()):
    """skip: nothing overlaps. light: few conflicts and no light blocker, so no analyst is needed. single and
    fanout: one analyst, or one per group."""
    if overlap_commits == 0 and conflicts == 0:
        return "skip"
    if light is not None and not blockers and conflicts <= light["max_conflicts"]:
        return "light"
    if overlap_commits <= scale["single_max_commits"] and conflicts <= scale["single_max_conflicts"]:
        return "single"
    return "fanout"


def path_matches(path, pattern):
    """A Tier 2 style glob: `**` spans directories, `*` and `?` stay inside one path segment."""
    rx, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            rx, i = rx + "(?:.*/)?", i + 3
        elif pattern.startswith("**", i):
            rx, i = rx + ".*", i + 2
        elif pattern[i] == "*":
            rx, i = rx + "[^/]*", i + 1
        elif pattern[i] == "?":
            rx, i = rx + "[^/]", i + 1
        else:
            rx, i = rx + re.escape(pattern[i]), i + 1
    return re.fullmatch(rx, path) is not None


def benign_change(changed_lines, patterns):
    """True when every line upstream added or removed matches one of [patterns] (a version bump)."""
    return bool(changed_lines) and all(any(re.search(p, l) for p in patterns) for l in changed_lines)


def light_blockers(conflicts, upstream_files, fork_files, fork_commits, features, light, tier2, benign=()):
    """Why a sync may not take the light path (no classify analysts, and the only kind a nightly run takes).
    [benign] lists both-sided Tier 2 paths whose upstream change is only lines like a version bump."""
    blockers = []
    if len(conflicts) > light["max_conflicts"]:
        blockers.append(f"{len(conflicts)} files conflict in a trial merge (light limit {light['max_conflicts']})")
    for path in conflicts:
        key = protected_path(path, features)
        if key:
            blockers.append(f"conflict in a protected file ({key}): {path}")
        owners = sorted({c["sha"][:9] for c in fork_commits if c.get("protected") and path in c["files"]})
        if owners:
            blockers.append(f"protected commits touch the conflicting file {path}: {', '.join(owners)}")
    for path in sorted(upstream_files):
        if any(path_matches(path, g) for g in light["blocked_paths"]):
            blockers.append(f"upstream changes {path}")
    for path in sorted(upstream_files & fork_files):
        if path not in conflicts and path in benign:
            continue
        if any(path_matches(path, g) for g in tier2):
            blockers.append(f"both sides change the Tier 2 path {path}")
    return blockers


def stop_findings(unmerged, commit_files, subject, features, light, seen):
    """At a rebase stop of a light sync: what makes the stop too much for the light path. [seen] is every file that
    conflicted at an earlier stop of the same rebase."""
    findings = []
    key = feature_of(commit_files, features, subject)
    if key:
        findings.append(f"the replayed commit belongs to the protected feature {key}: {subject}")
    for path in unmerged:
        owner = protected_path(path, features)
        if owner:
            findings.append(f"conflict in a protected file ({owner}): {path}")
        if any(path_matches(path, g) for g in light["blocked_paths"]):
            findings.append(f"conflict in a light-blocked path: {path}")
    total = set(seen) | set(unmerged)
    if len(total) > light["max_conflicts"]:
        findings.append(f"{len(total)} files have conflicted so far (light limit {light['max_conflicts']})")
    return findings


def merge_groups(groups, limit):
    """Merges the two smallest groups (by commit count) until at most [limit] remain."""
    groups = [dict(g) for g in groups]
    while len(groups) > limit:
        groups.sort(key=lambda g: (len(g["commits"]), g["key"]))
        a, b = groups[0], groups[1]
        merged = {
            "key": f"{a['key']}+{b['key']}",
            "focus": f"{a['focus']}\n{b['focus']}",
            "commits": a["commits"] + b["commits"],
            "files": sorted(set(a["files"]) | set(b["files"])),
        }
        groups = [merged] + groups[2:]
    return groups


def todo_edit(text, drops):
    """Rewrites `pick <sha>` to `drop <sha>` for each sha in [drops] (prefix match, >= 7 characters).
    Returns (text, missing)."""
    drops = [d.strip() for d in drops if d.strip()]
    hit, out = set(), []
    for line in text.splitlines(keepends=True):
        m = re.match(r"^(pick|p) ([0-9a-f]{7,40})(\b.*)$", line, re.S)
        if m:
            sha = m.group(2)
            for d in drops:
                n = min(len(d), len(sha))
                if n >= 7 and d[:n] == sha[:n]:
                    line = f"drop {sha}{m.group(3)}"
                    hit.add(d)
                    break
        out.append(line)
    return "".join(out), [d for d in drops if d not in hit]


RANGE_LINE = re.compile(r"^\s*(\d+|-):\s+([0-9a-f]+|-+)\s+([=!<>])\s+(\d+|-):\s+([0-9a-f]+|-+)\s?(.*)$")


def parse_range_diff(text):
    """`git range-diff` output -> list of dicts {old, new, mark, subject, changed} where changed holds
    the lines of the patch that differ (the `^    [-+][-+]` lines) for `!` entries."""
    entries = []
    for line in text.splitlines():
        m = RANGE_LINE.match(line)
        if m:
            entries.append({"old": None if m.group(2).startswith("-") else m.group(2),
                            "new": None if m.group(5).startswith("-") else m.group(5),
                            "mark": m.group(3), "subject": m.group(6), "changed": []})
        elif entries and re.match(r"^    [-+][-+]", line):
            entries[-1]["changed"].append(line[4:])
    return entries


def duplicate_names(files):
    """{path: xml text} for res/values*/ files -> (problems). Duplicate (tag, name) pairs count within one
    values directory, across its files; the same name in another qualifier directory is normal."""
    seen, problems = collections.defaultdict(dict), []
    for path in sorted(files):
        directory = os.path.dirname(path)
        try:
            root = ET.fromstring(files[path])
        except ET.ParseError as e:
            problems.append(f"{path}: does not parse ({e})")
            continue
        for el in root:
            name = el.get("name")
            if not name:
                continue
            key = (el.tag, name)
            if key in seen[directory]:
                problems.append(f"{directory}: duplicate {el.tag} '{name}' ({seen[directory][key]} and {path})")
            else:
                seen[directory][key] = path
    return problems


def decisions(classify):
    """The classify workflow's result -> (drops, hints). Only drops are applied automatically; adapt and
    partial verdicts become hints that name the paths which may need an explicit --allow-changed or
    --allow-same, so no file is waived without a decision per path. The workflow's own summary.drop wins
    when present: it keeps the more cautious verdict where two groups disagree."""
    drops, hints = [], []
    summary = (classify.get("summary") or {}).get("drop")
    for group in classify.get("groups", []) or []:
        for c in (group or {}).get("commits", []):
            verdict, files = c.get("verdict"), c.get("conflict_files") or []
            if verdict == "drop-superseded" and summary is None:
                drops.append(c["sha"])
            elif verdict in ("keep-adapt", "partial-superseded"):
                flag = "--allow-changed" if verdict == "keep-adapt" else "--allow-same"
                hints.append(f"{c['sha']} ({verdict}): consider {flag} for the paths it adapts"
                             + (f", e.g. {', '.join(files)}" if files else ""))
    return (list(summary) if summary is not None else drops), hints


def added_lines(diff_text):
    """`git show -U0 --format= <sha>` output -> {path: [added line, stripped]}, skipping lines too short to tell
    apart (braces, blank lines, comment fences), which a merge can legitimately move."""
    out, path = {}, None
    for line in diff_text.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("+") and path:
            text = line[1:].strip()
            if len(text) > 3 and text not in ("});", "/**", "*/", "})", "),"):
                out.setdefault(path, []).append(text)
    return out


def in_range(drops, shas):
    """The drops that name one of [shas] (full SHAs of the old range's commits), and the rest."""
    hit = [d for d in drops if any(sha_matches(s, d) for s in shas)]
    return hit, [d for d in drops if d not in hit]


def junit_failures(paths):
    out = []
    for p in paths:
        for case in ET.parse(p).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                out.append(f"{case.get('classname')}.{case.get('name')}")
    return sorted(set(out))


def junit_out_of_memory(paths):
    """How many result files hold an OutOfMemoryError in a failure, an error or the test JVM's stderr."""
    hits = 0
    for p in paths:
        root = ET.parse(p).getroot()
        nodes = [*root.iter("failure"), *root.iter("error"), *root.iter("system-err")]
        hits += any("OutOfMemoryError" in " ".join([n.get("type") or "", n.get("message") or "", n.text or ""])
                    for n in nodes)
    return hits


def load_results(path, sha):
    """One result set of scripts/ci_unit_tests.sh -> (classes, failures, problems).

    A problem means the set cannot be trusted. Everything unknown counts as one: a green comparison
    must rest on a run that is known to have finished.
    """
    d = Path(path)
    if not d.is_dir():
        return set(), [], [f"{path} is missing"]
    try:
        meta = json.loads((d / "meta.json").read_text())
    except (OSError, ValueError):
        return set(), [], [f"{path}/meta.json is missing or unreadable: the run did not finish"]
    problems, classes, parsed = [], set(), []
    if meta.get("sha") != sha:
        problems.append(f"{path} is from {meta.get('sha')}, expected {sha}")
    if meta.get("crashed", True):
        problems.append(f"{path}: the test JVM crashed")
    for p in sorted(d.glob("*.xml")):
        try:
            classes.add(ET.parse(p).getroot().get("name", p.stem))
            parsed.append(p)
        except ET.ParseError:
            problems.append(f"{path}: {p.name} is not readable XML")
    if not parsed:
        problems.append(f"{path} has no JUnit XML")
    failures = junit_failures(parsed)
    if meta.get("gradle_exit") != 0 and not failures:
        problems.append(f"{path}: Gradle exited {meta.get('gradle_exit')} and no test failed: "
                        "a crash or a build error")
    expected = meta.get("source_classes", 0)
    if expected < 1:
        problems.append(f"{path}: meta.json names no test files")
    elif len(classes) < expected * (1 - CLASS_MARGIN):
        problems.append(f"{path}: {len(classes)} test classes for {expected} test files")
    return classes, failures, problems


def compare_results(baseline, baseline_sha, candidate, candidate_sha):
    """Two result sets -> (new failures, problems, notes). Green needs both lists empty."""
    base_classes, base_failures, base_problems = load_results(baseline, baseline_sha)
    classes, failures, problems = load_results(candidate, candidate_sha)
    lost = sorted(base_classes - classes)
    all_problems = base_problems + problems
    notes = [f"known failure, upstream has it too: {f}" for f in failures if f in base_failures]
    if len(lost) > len(base_classes) * CLASS_MARGIN:
        all_problems.append(f"{len(lost)} of {len(base_classes)} upstream test classes did not run, e.g. {lost[0]}")
    elif lost:
        notes.append(f"upstream test classes that did not run: {', '.join(lost)}")
    return sorted(set(failures) - set(base_failures)), all_problems, notes


def java_properties(text):
    """The simple key=value lines of a local.properties file."""
    found = (re.match(r"\s*([\w.-]+)\s*[=:]\s*(.*?)\s*$", line) for line in text.splitlines()
             if not line.lstrip().startswith(("#", "!")))
    return {m.group(1): m.group(2) for m in found if m}


def ffmpeg_setting(name, env, props):
    """As app/build.gradle.kts reads it: the environment wins, even with an empty value."""
    return env[name] if name in env else props.get(name)


def wants_local_ffmpeg(env, props):
    """The same test as useLocalFfmpegDecoder. The build script passes no -P, and the bytes check the rest."""
    return str(ffmpeg_setting("USE_LOCAL_FFMPEG_DECODER", env, props)).lower() in ("true", "1", "yes")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def sha_matches(a, b):
    n = min(len(a), len(b))
    return n >= 7 and a[:n] == b[:n]


# ---------------------------------------------------------------- git and gh

def ref_exists(ref):
    return git("rev-parse", "-q", "--verify", f"{ref}^{{commit}}", check=False).returncode == 0


def blob(ref, path):
    r = git("rev-parse", "-q", "--verify", f"{ref}:{path}", check=False)
    return r.stdout.strip() if r.returncode == 0 else None


# ---------------------------------------------------------------- commands

def cmd_preflight(a, cfg):
    fork, up = cfg["remotes"]["fork"], cfg["remotes"]["upstream"]
    main_b, perf_b = cfg["layers"]["mirror"], cfg["layers"]["perf"]
    if not a.no_fetch:
        git("fetch", "--prune", "--quiet", fork)
        git("fetch", "--quiet", up)
    blocks, warns = [], []
    if out("status", "--porcelain"):
        blocks.append("the working tree is not clean")
    for b in (main_b, perf_b):
        if ref_exists(b) and out("rev-parse", b) != out("rev-parse", f"{fork}/{b}"):
            blocks.append(f"local {b} differs from {fork}/{b}")
    fmain, fperf, umain = f"{fork}/{main_b}", f"{fork}/{perf_b}", f"{up}/main"
    # main mirrors upstream at the base perf was last rebased onto, so it marks where the fork's commits start.
    if git("merge-base", "--is-ancestor", fmain, umain, check=False).returncode != 0:
        blocks.append(f"{fmain} is not in {umain}: upstream rewrote its history, or {main_b} carries commits of its "
                      f"own. Find the upstream commit with the same tree and rebase with --onto (sync.json traps)")
    elif out("rev-parse", fmain) != out("merge-base", fperf, umain):
        warns.append(f"{fmain} is not the base of {fperf}: the next publish moves it there")
    for base in (perf_b, main_b):
        # REST, not `gh pr list`: GraphQL is blocked in cloud sessions.
        for pr in gh_json("api", f"repos/{cfg['repo']}/pulls?state=open&base={base}&per_page=100") or []:
            blocks.append(f"PR #{pr['number']} ({pr['head']['ref']}) is open into {base}: merge or close it first")
    skip = set(a.allow_branch or []) | {main_b, perf_b}
    prefixes = tuple(cfg["ignored_branch_prefixes"])
    perf_subjects = set(lines("log", "--format=%s", f"{umain}..{fperf}"))
    candidates = [(b, b) for b in lines("for-each-ref", "--format=%(refname:short)", "refs/heads")]
    for r in lines("for-each-ref", "--format=%(refname:short)", f"refs/remotes/{fork}"):
        name = r[len(fork) + 1:]
        if name and name != "HEAD":
            candidates.append((r, name))
    for ref, name in candidates:
        if name in skip:
            continue
        if ref.startswith(f"{fork}/") and ref_exists(f"{up}/{name}") and out("rev-parse", ref) == out("rev-parse", f"{up}/{name}"):
            continue
        plus = cherry_plus(out("cherry", "-v", fperf, ref))
        if name.startswith(prefixes) and all(s in perf_subjects for _, s in plus):
            warns.append(f"{ref} is left over from an earlier sync (every commit is in {perf_b} by subject)")
            continue
        if plus:
            rewritten = [s for _, s in plus if s in perf_subjects]
            hint = f"; {len(rewritten)} of them match a subject in {perf_b} (rewritten by a sync?)" if rewritten else ""
            blocks.append(f"{ref} has {len(plus)} commit(s) not in {perf_b}{hint}: merge, rebase or delete it")
    if git("config", "--get", "rerere.enabled", check=False).stdout.strip() != "true":
        warns.append("rerere is off: git config rerere.enabled true && git config rerere.autoupdate true")
    try:
        states = {w["name"]: w["state"] for w in gh_json("workflow", "list", "-R", cfg["repo"], "--all", "--json", "name,state")}
        for name in cfg["issue_workflows"]:
            if states.get(name, "disabled_manually") != "disabled_manually":
                warns.append(f"workflow '{name}' is {states[name]}: it acts on issues, including the drift issue")
        for name, state in states.items():
            if state == "active" and name.lower().startswith("issue") and name not in cfg["issue_workflows"]:
                warns.append(f"new upstream workflow '{name}' is active")
    except RuntimeError as e:
        warns.append(f"could not list workflows: {e}")
    for wt in out("worktree", "list", "--porcelain").split("\n\n"):
        m = re.search(r"^branch refs/heads/(.+)$", wt, re.M)
        p = re.search(r"^worktree (.+)$", wt, re.M)
        if m and p and m.group(1) not in skip and p.group(1) != os.getcwd():
            warns.append(f"worktree {p.group(1)} has {m.group(1)} checked out")
    counts = {
        perf_b: (int(out("rev-list", "--count", f"{fperf}..{umain}")), int(out("rev-list", "--count", f"{umain}..{fperf}"))),
    }
    # Advisory: neither a verdict nor a failure to reach one changes the exit code.
    try:
        due = sync_tools.due_report(cfg, umain, fperf)
        due_text = sync_tools.due_line(due)
    except RuntimeError as e:
        due, due_text = {"verdict": "not available", "reason": str(e)}, f"due: not available ({e})"
    report = {"blocks": blocks, "warnings": warns, "behind_ahead": counts, "upstream": out("rev-parse", umain), "due": due}
    if a.json:
        print(json.dumps(report, indent=1))
    else:
        print(due_text)
        for b, (behind, ahead) in counts.items():
            print(f"{b}: {behind} behind {umain}, {ahead} ahead")
        for w in warns:
            print(f"warning: {w}")
        for b in blocks:
            print(f"BLOCKED: {b}")
    if blocks:
        return FAIL
    return NOTHING if counts[perf_b][0] == 0 else OK


def cmd_map_overlap(a, cfg):
    up, perf_r = a.upstream, a.perf
    mb = out("merge-base", perf_r, up)
    behind = int(out("rev-list", "--count", f"{perf_r}..{up}"))
    fork_commits = commits_with_files(f"{mb}..{perf_r}")
    upstream_files = set(lines("diff", "--no-renames", "--name-only", mb, up))
    fork_files = set(lines("diff", "--no-renames", "--name-only", mb, perf_r))
    overlap = upstream_files & fork_files
    r = git("merge-tree", "--write-tree", "--name-only", "--no-messages", up, perf_r, check=False)
    conflicts = parse_merge_tree(r.stdout, r.returncode)
    themes, scale = cfg["themes"], cfg["scale"]
    by_theme, auto_clean = collections.OrderedDict((t["key"], []) for t in themes), []
    features = cfg.get("protected_features", [])
    for c in fork_commits:
        c["protected"] = feature_of(c["files"], features, c["subject"])
        c["overlapFiles"] = [f for f in c["files"] if f in overlap]
        if c["overlapFiles"]:
            by_theme[commit_theme(c["overlapFiles"], themes, c["subject"])].append(c)
        else:
            auto_clean.append({k: c[k] for k in ("sha", "subject", "files", "protected")})
    overlap_commits = sum(len(v) for v in by_theme.values())
    light = cfg["light"]
    tier2 = tier2_globs(Path(".claude/workflow.md").read_text()) if Path(".claude/workflow.md").exists() else []
    benign = set()
    for path in upstream_files & fork_files:
        if any(path_matches(path, g) for g in tier2):
            diff = out("diff", "--no-renames", "--unified=0", mb, up, "--", path).splitlines()
            changed = [l[1:] for l in diff if l[:1] in "+-" and not l.startswith(("+++", "---"))]
            if benign_change(changed, light["benign_tier2_lines"]):
                benign.add(path)
    blockers = light_blockers(conflicts, upstream_files, fork_files, fork_commits, features, light, tier2, benign)
    tier = scale_tier(overlap_commits, len(conflicts), scale, light, blockers)
    focus = {t["key"]: t["focus"] for t in themes}
    groups = [{"key": k, "focus": focus[k], "commits": v, "files": sorted({f for c in v for f in c["overlapFiles"]})}
              for k, v in by_theme.items() if v]
    if tier in ("single", "light") and groups:
        groups = merge_groups(groups, 1)
        groups[0]["key"] = "all"
    elif tier == "fanout":
        groups = merge_groups(groups, scale["max_analysts"])
    truncated = []
    for g in groups:
        ups = commits_with_files(f"{mb}..{up}") if not g["files"] else [
            c for c in commits_with_files(f"{mb}..{up}") if set(c["files"]) & set(g["files"])]
        if len(ups) > scale["upstream_per_theme_cap"]:
            truncated.append(f"group {g['key']}: {len(ups)} upstream commits, listed the last {scale['upstream_per_theme_cap']}")
            ups = ups[-scale["upstream_per_theme_cap"]:]
        g["upstreamCommits"] = [{"sha": c["sha"][:12], "subject": c["subject"],
                                 "files": [f for f in c["files"] if f in g["files"]]} for c in ups]
        g["conflictFiles"] = [f for f in conflicts if f in g["files"]]
        for c in g["commits"]:
            c["sha"] = c["sha"][:12]
        del g["files"]
    first_parent = [{"sha": l.split(" ", 1)[0], "subject": l.split(" ", 1)[1] if " " in l else ""}
                    for l in lines("log", "--first-parent", "--format=%h %s", f"{mb}..{up}")]
    result = {
        "repo": os.getcwd(), "scratch": a.scratch, "upstreamRef": up, "forkRef": perf_r,
        "mb": mb, "upstreamTip": out("rev-parse", up), "forkTip": out("rev-parse", perf_r),
        "counts": {"fork": len(fork_commits), "upstream": int(out("rev-list", "--count", f"{mb}..{up}")),
                   "upstreamFirstParent": len(first_parent), "behind": behind, "overlapCommits": overlap_commits},
        "upstreamFirstParent": first_parent, "mergeConflicts": conflicts,
        "rules": [cfg["owner_rule"], *cfg["traps"]],
        "scale": {"tier": tier, "reasons": [f"{overlap_commits} fork commits touch files upstream changed",
                                            f"{len(conflicts)} files conflict in a trial merge"]},
        # A nightly run takes only a sync with no light blocker (tier skip or light).
        "light": {"eligible": not blockers, "blockers": blockers},
        # A light sync keeps its group: a stop-check abort classifies from this output.
        "groups": groups if tier != "skip" else [], "autoClean": auto_clean, "truncated": truncated,
    }
    print(json.dumps(result, indent=1))
    return NOTHING if behind == 0 else OK


def cmd_todo_edit(a, cfg):
    path = Path(a.todo)
    text, missing = todo_edit(path.read_text(), a.drop.split(","))
    if missing:
        print(f"todo-edit: not in the rebase todo: {', '.join(missing)}", file=sys.stderr)
        return FAIL
    path.write_text(text)
    return OK


def cmd_stop_check(a, cfg):
    """At a conflict stop of a light rebase: exit 1 when the stop needs more than the light path allows. The
    caller then aborts the rebase and classifies (a local run) or stops with a report (a nightly run)."""
    rebase_dir = Path(out("rev-parse", "--git-path", "rebase-merge"))
    if not rebase_dir.is_dir() or not ref_exists("REBASE_HEAD"):
        print("stop-check: no rebase is stopped here", file=sys.stderr)
        return USAGE
    unmerged = set(lines("diff", "--name-only", "--diff-filter=U"))
    # rerere autoupdate stages a recorded resolution, so redo the replay's merge in memory to count those too.
    if ref_exists("REBASE_HEAD^"):
        r = git("merge-tree", "--write-tree", "--name-only", "--no-messages", "--merge-base=REBASE_HEAD^", "HEAD",
                "REBASE_HEAD", check=False)
        unmerged |= set(parse_merge_tree(r.stdout, r.returncode))
    unmerged = sorted(unmerged)
    commit_files = lines("show", "--no-renames", "--format=", "--name-only", "REBASE_HEAD")
    subject = out("show", "-s", "--format=%s", "REBASE_HEAD")
    seen_file = rebase_dir / "upstream-sync-seen"
    seen = [l for l in seen_file.read_text().splitlines() if l] if seen_file.exists() else []
    findings = stop_findings(unmerged, commit_files, subject, cfg.get("protected_features", []), cfg["light"], seen)
    seen_file.write_text("\n".join(sorted(set(seen) | set(unmerged))) + "\n")
    for f in findings:
        print(f"STOP: {f}")
    if findings:
        return FAIL
    print(f"ok: {len(unmerged)} conflicting file(s) at this stop, {len(set(seen) | set(unmerged))} in this rebase")
    return OK


def cmd_drops(a, cfg):
    """Prints the decided drops that belong to a range, comma-separated, for todo-edit."""
    drops, _ = decisions(json.loads(Path(a.decisions).read_text()))
    shas = lines("rev-list", a.range)
    print(",".join(in_range(drops, shas)[0]))
    return OK


def cmd_verify(a, cfg):
    fails, notes = [], []
    drops = [d for d in (a.expect_drop or "").split(",") if d]
    # A commit the sync adds on purpose (a fix for an upstream test that fails on CI) is new in the range-diff.
    added = [n for n in (getattr(a, "allow_new", None) or "").split(",") if n]
    if a.decisions:
        auto_drops, hints = decisions(json.loads(Path(a.decisions).read_text()))
        # Only drops of commits in the old range apply; a decision that names another commit is stale.
        mine, other = in_range(auto_drops, lines("rev-list", f"{a.base_old}..{a.old}"))
        drops += [d for d in mine if d not in drops]
        notes += [f"hint: {h}" for h in hints]
        notes += [f"expected drop from the classify decisions: {d}" for d in mine]
        notes += [f"decided drop {d} is not in {a.base_old}..{a.old}" for d in other]
    allow_same, allow_changed = set(a.allow_same or []), set(a.allow_changed or [])
    for p in sorted(allow_same):
        notes.append(f"waived by --allow-same: {p} may equal {a.base_new}")
    for p in sorted(allow_changed):
        notes.append(f"waived by --allow-changed: {p} may differ from {a.old}")

    # 1. Range-diff.
    if int(out("rev-list", "--count", f"{a.base_new}..{a.new}")) == 0:
        # range-diff needs two non-empty ranges: with nothing left on top of the new base every old commit is gone.
        entries = [{"old": c["sha"][:9], "new": None, "mark": "<", "subject": c["subject"], "changed": []}
                   for c in commits_with_files(f"{a.base_old}..{a.old}")]
    else:
        # A commit whose diff changed a lot (a binary next to a rewritten README) needs a higher creation factor to
        # pair with its rebased self; at git's default of 60 it shows as one dropped and one new commit.
        cf = getattr(a, "creation_factor", None)
        entries = parse_range_diff(out("range-diff", "--no-color", *([f"--creation-factor={cf}"] if cf else []),
                                       f"{a.base_old}..{a.old}", f"{a.base_new}..{a.new}"))
    marks = collections.Counter(e["mark"] for e in entries)
    report = [f"## Verify {a.old} -> {a.new}", "",
              f"range-diff: {marks.get('=', 0)} identical, {marks.get('!', 0)} changed, "
              f"{marks.get('<', 0)} dropped, {marks.get('>', 0)} new", ""]
    for e in entries:
        if e["mark"] == "<" and not any(sha_matches(e["old"], d) for d in drops):
            fails.append(f"commit {e['old']} '{e['subject']}' was dropped without --expect-drop")
        if e["mark"] == ">":
            if any(sha_matches(e["new"], n) for n in added):
                notes.append(f"new commit added on purpose (--allow-new): {e['new']} '{e['subject']}'")
            else:
                fails.append(f"commit {e['new']} '{e['subject']}' is new in {a.new} without --allow-new")
        if e["mark"] == "!":
            report.append(f"### {e['old']} -> {e['new']} {e['subject']}")
            report += ["```diff", *(e["changed"] or ["(context or line numbers only)"]), "```", ""]
    for d in drops:
        if not any(e["mark"] == "<" and sha_matches(e["old"], d) for e in entries):
            fails.append(f"expected drop {d} is still in {a.new} or not in {a.old}")
    for n in added:
        if not any(e["mark"] == ">" and sha_matches(e["new"], n) for e in entries):
            fails.append(f"--allow-new {n} is not a new commit in {a.new}")

    # 2. Fork-only files are unchanged; 3. overlap files keep a fork change.
    old_commits = commits_with_files(f"{a.base_old}..{a.old}")
    kept = [c for c in old_commits if not any(sha_matches(c["sha"], d) for d in drops)]
    dropped = [c for c in old_commits if c not in kept]
    kept_files = {f for c in kept for f in c["files"]}
    fork_files = set(lines("diff", "--no-renames", "--name-only", a.base_old, a.old))
    moved = set(lines("diff", "--no-renames", "--name-only", a.base_old, a.base_new))
    # Upstream renames: a fork change to the old name must show up under the new one.
    renames = {}
    for line in lines("diff", "-M", "--name-status", a.base_old, a.base_new):
        parts = line.split("\t")
        if parts[0].startswith("R") and len(parts) == 3:
            renames[parts[1]] = parts[2]
    for path in sorted(fork_files - moved):
        if path not in kept_files or path in allow_changed:
            continue
        if blob(a.old, path) != blob(a.new, path):
            also = [c["sha"][:9] for c in dropped if path in c["files"]]
            why = (f"dropped commit(s) {', '.join(also)} also touched it; review the kept change and pass "
                   f"--allow-changed {path}") if also else "upstream did not touch it"
            fails.append(f"fork-only file changed: {path} ({why})")
    for path in sorted((fork_files & moved) & kept_files):
        if path in allow_same:
            continue
        target = renames.get(path, path)
        if blob(a.base_new, target) is None:
            fails.append(f"upstream removed {path}, which the fork changes: decide where the change goes, "
                         f"then pass --allow-same {path}")
        elif blob(a.new, target) == blob(a.base_new, target):
            where = f" (renamed upstream to {target})" if target != path else ""
            fails.append(f"fork change lost: {path}{where} equals {a.base_new}")

    # 4. Locale files.
    values = [p for p in lines("ls-tree", "-r", "--name-only", a.new, "--", "app/src/main/res")
              if re.search(r"/res/values[^/]*/[^/]+\.xml$", p)]
    texts = {p: git("show", f"{a.new}:{p}", binary=True).stdout for p in values}
    fails += duplicate_names(texts)

    # 5. Pinned binaries.
    for path, want in cfg["pinned_binaries"].items():
        r = git("show", f"{a.new}:{path}", check=False, binary=True)
        got = hashlib.sha256(r.stdout).hexdigest() if r.returncode == 0 else "missing"
        if got != want:
            fails.append(f"{path}: sha256 {got}, expected {want}")

    # 6. Conflict markers.
    r = git("grep", "-I", "-n", "-E", r"^(<{7}|>{7})( |$)", a.new, check=False)
    if r.returncode == 0:
        fails += [f"conflict marker: {l}" for l in r.stdout.splitlines()[:20]]

    # 7. Protected features: every line their kept commits added that the old tip still has must be on the new tip,
    # in any file (shared player files included), and none of their commits may be dropped without the owner.
    approved = [d for d in (getattr(a, "protected_drop", None) or "").split(",") if d]
    protected_report = []
    contents = {}

    def text_lines(ref, path):
        if (ref, path) not in contents:
            r = git("show", f"{ref}:{path}", check=False)
            contents[(ref, path)] = {l.strip() for l in r.stdout.splitlines()} if r.returncode == 0 else set()
        return contents[(ref, path)]

    for feature in cfg.get("protected_features", []):
        mine = [c for c in old_commits if feature_of(c["files"], [feature], c["subject"])]
        checked = lost_total = 0
        for c in mine:
            if c in dropped:
                if any(sha_matches(c["sha"], d) for d in approved):
                    notes.append(f"protected drop approved by the owner: {c['sha'][:9]} ({feature['key']})")
                else:
                    fails.append(f"protected feature {feature['key']}: commit {c['sha'][:9]} '{c['subject']}' was "
                                 f"dropped; ask the owner, then pass --protected-drop {c['sha'][:9]}")
                continue
            for path, added in added_lines(out("show", "-U0", "--no-renames", "--format=", c["sha"])).items():
                if path in allow_changed or path in allow_same:
                    continue
                still = [l for l in added if l in text_lines(a.old, path)]
                target = renames.get(path, path)
                lost = [l for l in still if l not in text_lines(a.new, target)]
                checked += len(still)
                lost_total += len(lost)
                if lost:
                    fails.append(f"protected feature {feature['key']}: {target} lost {len(lost)} line(s) that "
                                 f"{c['sha'][:9]} added, e.g. `{lost[0][:100]}`")
        for path in feature.get("binaries", []):
            if path not in allow_changed and blob(a.old, path) != blob(a.new, path):
                fails.append(f"protected feature {feature['key']}: binary {path} differs from {a.old}")
        protected_report.append(f"- {feature['key']}: {len(mine)} commits, {checked} lines checked, {lost_total} lost")
    if protected_report:
        report += ["### Protected features", "", *protected_report, ""]

    # 8. Up to date.
    behind = int(out("rev-list", "--count", f"{a.new}..{a.upstream}"))
    if behind:
        fails.append(f"{a.new} is {behind} commits behind {a.upstream}")

    report += ["### Notes", "", *[f"- {n}" for n in notes or ["none"]], "", "### Result", ""]
    report += [f"- FAIL: {f}" for f in fails] or ["- all checks pass"]
    print("\n".join(report))
    return FAIL if fails else OK


def rpu_stamp_problems(stamp_path, data):
    """Why the packaged libnuvio_rpu.so is not the stamped build of HEAD's native/rpu_builder."""
    try:
        stamp = json.loads(Path(stamp_path).read_text())
    except (OSError, ValueError):
        return [f"hlg-to-dv: no readable stamp at {stamp_path}: this library did not come from "
                "scripts/build_rpu_builder.sh as it is now"]
    problems, tree, head = [], stamp.get("tree"), out("rev-parse", f"HEAD:{RPU_SOURCE}")
    if sha256(data) not in (stamp.get("sha256"), stamp.get("packaged_sha256")):
        problems.append(f"hlg-to-dv: the APK's {RPU_LIB} (sha256 {sha256(data)}) is not the stamped build")
    if tree != head:
        problems.append(f"hlg-to-dv: the library was built from {RPU_SOURCE} tree {tree}, HEAD has {head}")
    if stamp.get("dirty", True):
        problems.append(f"hlg-to-dv: the library was built with uncommitted changes in {RPU_SOURCE}")
    if not problems:
        print(f"hlg-to-dv: libnuvio_rpu.so is the stamped build of {RPU_SOURCE} tree {tree}")
    return problems


def ffmpeg_inputs(env, props):
    """(description of the local module's inputs, problems). The static libraries hold the FFmpeg code."""
    jni, problems = out("rev-parse", f"HEAD:{FFMPEG_JNI}"), []
    dirty = " (dirty)" if out("status", "--porcelain", "--", FFMPEG_JNI) else ""
    parts = [f"jni tree {jni}{dirty}"]
    build = ffmpeg_setting("FFMPEG_BUILD_DIR", env, props)
    for name in FFMPEG_STATIC_LIBS:
        path = Path(build or "") / "arm64-v8a" / name
        if build and path.is_file():
            parts.append(f"{name} sha256 {sha256(path.read_bytes())}")
        else:
            problems.append(f"aac-to-dts: no {path} (FFMPEG_BUILD_DIR), the local module links it")
    source = ffmpeg_setting("FFMPEG_SOURCE_DIR", env, props)
    commit = git("-C", source, "rev-parse", "HEAD", check=False).stdout.strip() if source else ""
    parts.append(f"FFmpeg source {commit or 'not a git checkout'}")
    return ", ".join(parts), problems


def ffmpeg_problems(data, env):
    """Say which FFmpeg the APK carries, by its bytes, and hold that against the build's settings."""
    import zipfile
    props_file = Path("local.properties")
    props = java_properties(props_file.read_text()) if props_file.exists() else {}
    with zipfile.ZipFile(FFMPEG_AAR) as z:
        prebuilt = z.read(FFMPEG_AAR_LIB)
    carries_aar, wants_local = sha256(data) == sha256(prebuilt), wants_local_ffmpeg(env, props)
    if carries_aar:
        print(f"aac-to-dts: FFmpeg is the prebuilt AAR, {FFMPEG_AAR} sha256 {sha256(Path(FFMPEG_AAR).read_bytes())}")
        return ["aac-to-dts: USE_LOCAL_FFMPEG_DECODER is on, but the APK has the prebuilt AAR's library"] if wants_local else []
    if not wants_local:
        return [f"aac-to-dts: the APK's {FFMPEG_LIB} is not the prebuilt AAR's, but USE_LOCAL_FFMPEG_DECODER is off"]
    described, problems = ffmpeg_inputs(env, props)
    print(f"aac-to-dts: FFmpeg is the local module, {described}")
    return problems


def cmd_protected_apk(a, cfg):
    """Each protected feature's native library is in the APK and carries its marker strings.

    With --stamp, also where they came from: an old library has the same markers as a new one. A lost
    marker or library is FAIL whatever the origin says; ORIGIN_MISMATCH means the markers all held.
    """
    import zipfile
    fails, origin = [], []
    with zipfile.ZipFile(a.apk) as z:
        names = set(z.namelist())
        if a.stamp:
            if RPU_LIB in names:
                origin += rpu_stamp_problems(a.stamp, z.read(RPU_LIB))
            if FFMPEG_LIB in names:
                origin += ffmpeg_problems(z.read(FFMPEG_LIB), os.environ)
        for feature in cfg.get("protected_features", []):
            spec = feature.get("apk")
            if not spec:
                continue
            if spec["lib"] not in names:
                fails.append(f"{feature['key']}: {spec['lib']} is not in the APK")
                continue
            data = z.read(spec["lib"])
            missing = [m for m in spec.get("strings", []) if m.encode() not in data]
            if missing:
                fails.append(f"{feature['key']}: {spec['lib']} has no {', '.join(missing)}")
            else:
                print(f"{feature['key']}: {spec['lib']} ({len(data)} bytes) ok")
    for f in origin + fails:
        print(f"FAIL: {f}")
    return FAIL if fails else ORIGIN_MISMATCH if origin else OK


def cmd_test_failures(a, cfg):
    paths = glob.glob(os.path.join(a.dir, "*.xml"))
    if not paths:
        print(f"no JUnit XML in {a.dir}", file=sys.stderr)
        return USAGE
    print("\n".join(junit_failures(paths)))
    oom = junit_out_of_memory(paths)
    if oom:  # On stderr: stdout is compared with the baseline's list of failures.
        print(f"hint: OutOfMemoryError in {oom} result file(s): this is the test JVM's heap, maxHeapSize in "
              "app/build.gradle.kts. Read the trap in sync.json that starts 'The unit test JVM' before raising it.",
              file=sys.stderr)
    return OK


# What decides a CI run, so a change here always runs the suite, whatever the contract's gate-skip says.
GATE_GUARDED = (".github", ".claude", "scripts")


def cmd_gate_skip(a, cfg):
    """Print skip=true when every path changed from base to head matches the base's gate-skip.

    The same pathspec test as /ship. Excludes apply before rename detection, so a source file moved into
    docs/ stays in `left` as a deletion; --no-renames only makes that independent of diff.renames.
    No change at all is not docs-only either. The contract comes from the base commit, and a change to
    GATE_GUARDED never skips, so a pull request cannot judge itself.
    """
    specs = gate_skip_specs(out("show", f"{a.base}:.claude/workflow.md"))
    diff = ["diff", "--name-only", "--no-renames", a.base, a.head]
    changed = out(*diff)
    left = out(*diff, "--", *[f":(exclude){spec}" for spec in specs])
    guarded = out(*diff, "--", *GATE_GUARDED)
    print(f"skip={'true' if changed and not left and not guarded else 'false'}")
    return OK


def cmd_results_complete(a, cfg):
    classes, failures, problems = load_results(a.dir, a.sha)
    for problem in problems:
        print(f"FAIL: {problem}")
    if not problems:
        print(f"{a.dir}: {len(classes)} test classes, {len(failures)} failures, complete")
    return FAIL if problems else OK


def cmd_compare_results(a, cfg):
    new, problems, notes = compare_results(a.baseline, a.baseline_sha, a.candidate, a.candidate_sha)
    for note in notes:
        print(note)
    for problem in problems:
        print(f"FAIL: {problem}")
    for failure in new:
        print(f"FAIL: new failure: {failure}")
    if new or problems:
        return FAIL
    print("no new failures, both runs complete")
    return OK


def main(argv):
    p = argparse.ArgumentParser(prog="sync.py", description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("preflight")
    s.add_argument("--allow-branch", action="append")
    s.add_argument("--no-fetch", action="store_true")
    s.add_argument("--json", action="store_true")
    s = sub.add_parser("map-overlap")
    s.add_argument("--upstream", default="upstream/main")
    s.add_argument("--perf", default="origin/perf/shield-pass")
    s.add_argument("--scratch", default="")
    s = sub.add_parser("todo-edit")
    s.add_argument("--drop", required=True)
    s.add_argument("todo")
    sub.add_parser("stop-check")
    s = sub.add_parser("drops")
    s.add_argument("--decisions", required=True)
    s.add_argument("--range", required=True)
    s = sub.add_parser("verify")
    for name in ("--base-old", "--old", "--base-new", "--new", "--upstream"):
        s.add_argument(name, required=True)
    s.add_argument("--decisions")
    s.add_argument("--expect-drop")
    s.add_argument("--allow-new", help="commits the sync added on purpose, SHA[,SHA...]")
    s.add_argument("--allow-same", action="append")
    s.add_argument("--allow-changed", action="append")
    s.add_argument("--protected-drop", help="owner-approved drops of protected-feature commits, SHA[,SHA...]")
    s.add_argument("--creation-factor", type=int, default=100, help="git range-diff --creation-factor (default 100)")
    s = sub.add_parser("protected-apk")
    s.add_argument("apk")
    s.add_argument("--stamp", help="the libnuvio_rpu.so.stamp of build_rpu_builder.sh; also checks FFmpeg's origin")
    s = sub.add_parser("test-failures")
    s.add_argument("dir")
    sync_tools.add_parsers(sub)
    s = sub.add_parser("gate-skip")
    s.add_argument("base")
    s.add_argument("head")
    s = sub.add_parser("results-complete")
    s.add_argument("dir")
    s.add_argument("--sha", required=True)
    s = sub.add_parser("compare-results")
    for name in ("--baseline", "--baseline-sha", "--candidate", "--candidate-sha"):
        s.add_argument(name, required=True)
    a = p.parse_args(argv)
    cfg = load_config()
    try:
        return {"preflight": cmd_preflight, "map-overlap": cmd_map_overlap, "todo-edit": cmd_todo_edit, "drops": cmd_drops,
                "stop-check": cmd_stop_check,
                "verify": cmd_verify, "protected-apk": cmd_protected_apk,
                "test-failures": cmd_test_failures, "gate-skip": cmd_gate_skip, "results-complete": cmd_results_complete, "compare-results": cmd_compare_results, **sync_tools.COMMANDS}[a.cmd](a, cfg)
    except RuntimeError as e:
        print(f"sync.py {a.cmd}: {e}", file=sys.stderr)
        return USAGE


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
