"""Helpers for /upstream-sync. Run from the repository root as `python3 -I <this file> <command> ...`.

Commands:
  preflight [--allow-branch B ...] [--no-fetch] [--json]
  map-overlap [--upstream R] [--main R] [--perf R] [--scratch DIR]
  todo-edit --drop SHA[,SHA...] <git-rebase-todo>
  drops --decisions classify.json --range BASE..TIP
  verify --base-old R --old R --base-new R --new R --upstream R [--main-new R]
         [--decisions classify.json] [--expect-drop SHA,...] [--allow-same PATH ...] [--allow-changed PATH ...]
         [--protected-drop SHA,...] [--creation-factor N]
  protected-apk <apk>
  test-failures <junit-xml-dir>

Exit codes: 0 ok, 1 a check failed or the sync is blocked, 2 usage or environment error, 3 nothing to do.
"""
import argparse
import collections
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CONFIG_PATH = Path(__file__).resolve().parents[1] / "sync.json"
OK, FAIL, USAGE, NOTHING = 0, 1, 2, 3


# ---------------------------------------------------------------- pure helpers (unit-tested)

def load_config(path=CONFIG_PATH):
    return json.loads(Path(path).read_text())


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


def scale_tier(overlap_commits, conflicts, scale):
    if overlap_commits == 0 and conflicts == 0:
        return "skip"
    if overlap_commits <= scale["single_max_commits"] and conflicts <= scale["single_max_conflicts"]:
        return "single"
    return "fanout"


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


def parse_merge_tree(stdout, rc):
    """`git merge-tree --write-tree --name-only --no-messages` -> list of conflicted paths."""
    if rc == 0:
        return []
    if rc != 1:
        raise RuntimeError(f"git merge-tree failed with exit {rc}")
    paths = []
    for line in stdout.splitlines()[1:]:
        if not line.strip():
            break
        paths.append(line.strip())
    return sorted(set(paths))


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


def feature_of(files, features, subject=""):
    """The key of the first protected feature (sync.json protected_features) whose anchors match one of [files] or
    whose subjects pattern matches [subject]; a feature commit that touches only shared files is found by subject."""
    for f in features:
        if any(re.search(p, path) for p in f["anchors"] for path in files):
            return f["key"]
        if subject and f.get("subjects") and re.search(f["subjects"], subject):
            return f["key"]
    return None


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
    """The drops that name one of [shas] (full SHAs of a layer's commits), and the rest."""
    hit = [d for d in drops if any(sha_matches(s, d) for s in shas)]
    return hit, [d for d in drops if d not in hit]


def junit_failures(paths):
    out = []
    for p in paths:
        for case in ET.parse(p).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                out.append(f"{case.get('classname')}.{case.get('name')}")
    return sorted(set(out))


def sha_matches(a, b):
    n = min(len(a), len(b))
    return n >= 7 and a[:n] == b[:n]


# ---------------------------------------------------------------- git and gh

def git(*args, check=True, binary=False):
    r = subprocess.run(["git", *args], capture_output=True, text=not binary)
    if check and r.returncode != 0:
        err = r.stderr if not binary else r.stderr.decode(errors="replace")
        raise RuntimeError(f"git {' '.join(args)}: {' / '.join(err.strip().splitlines()[:2])}")
    return r


def out(*args):
    return git(*args).stdout.strip()


def lines(*args):
    return [l for l in out(*args).splitlines() if l.strip()]


def ref_exists(ref):
    return git("rev-parse", "-q", "--verify", f"{ref}^{{commit}}", check=False).returncode == 0


def blob(ref, path):
    r = git("rev-parse", "-q", "--verify", f"{ref}:{path}", check=False)
    return r.stdout.strip() if r.returncode == 0 else None


def gh_json(*args):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args)}: {r.stderr.strip()}")
    return json.loads(r.stdout or "null")


def commits_with_files(rng):
    """[{sha, subject, files}] oldest first for a revision range."""
    text = out("log", "--reverse", "--no-merges", "--no-renames", "--format=@@%H\t%s", "--name-only", rng)
    result = []
    for line in text.splitlines():
        if line.startswith("@@"):
            sha, _, subject = line[2:].partition("\t")
            result.append({"sha": sha, "subject": subject, "files": []})
        elif line.strip() and result:
            result[-1]["files"].append(line.strip())
    return result


# ---------------------------------------------------------------- commands

def cmd_preflight(a, cfg):
    fork, up = cfg["remotes"]["fork"], cfg["remotes"]["upstream"]
    main_b, perf_b = cfg["layers"]["main"], cfg["layers"]["perf"]
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
    if git("merge-base", "--is-ancestor", fmain, fperf, check=False).returncode != 0:
        blocks.append(f"{fmain} is not an ancestor of {fperf} (the two-layer model is broken)")
    for base in (perf_b, main_b):
        for pr in gh_json("pr", "list", "-R", cfg["repo"], "--base", base, "--state", "open",
                          "--json", "number,title,headRefName") or []:
            blocks.append(f"PR #{pr['number']} ({pr['headRefName']}) is open into {base}: merge or close it first")
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
        main_b: (int(out("rev-list", "--count", f"{fmain}..{umain}")), int(out("rev-list", "--count", f"{umain}..{fmain}"))),
    }
    report = {"blocks": blocks, "warnings": warns, "behind_ahead": counts, "upstream": out("rev-parse", umain)}
    if a.json:
        print(json.dumps(report, indent=1))
    else:
        for b, (behind, ahead) in counts.items():
            print(f"{b}: {behind} behind {umain}, {ahead} ahead")
        for w in warns:
            print(f"warning: {w}")
        for b in blocks:
            print(f"BLOCKED: {b}")
    if blocks:
        return FAIL
    return NOTHING if counts[perf_b][0] == 0 and counts[main_b][0] == 0 else OK


def cmd_map_overlap(a, cfg):
    up, main_r, perf_r = a.upstream, a.main, a.perf
    mb = out("merge-base", perf_r, up)
    behind = int(out("rev-list", "--count", f"{perf_r}..{up}"))
    layered = git("merge-base", "--is-ancestor", main_r, perf_r, check=False).returncode == 0
    fork_commits = []
    if layered:
        for c in commits_with_files(f"{mb}..{main_r}"):
            fork_commits.append({**c, "layer": "main"})
        for c in commits_with_files(f"{main_r}..{perf_r}"):
            fork_commits.append({**c, "layer": "perf"})
    else:
        fork_commits = [{**c, "layer": "perf"} for c in commits_with_files(f"{mb}..{perf_r}")]
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
            auto_clean.append({k: c[k] for k in ("sha", "subject", "layer", "files", "protected")})
    overlap_commits = sum(len(v) for v in by_theme.values())
    tier = scale_tier(overlap_commits, len(conflicts), scale)
    focus = {t["key"]: t["focus"] for t in themes}
    groups = [{"key": k, "focus": focus[k], "commits": v, "files": sorted({f for c in v for f in c["overlapFiles"]})}
              for k, v in by_theme.items() if v]
    if tier == "single" and groups:
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
        "repo": os.getcwd(), "scratch": a.scratch, "upstreamRef": up, "forkRef": perf_r, "mainRef": main_r,
        "mb": mb, "upstreamTip": out("rev-parse", up), "forkTip": out("rev-parse", perf_r), "layered": layered,
        "counts": {"fork": len(fork_commits), "upstream": int(out("rev-list", "--count", f"{mb}..{up}")),
                   "upstreamFirstParent": len(first_parent), "behind": behind, "overlapCommits": overlap_commits},
        "upstreamFirstParent": first_parent, "mergeConflicts": conflicts,
        "rules": [cfg["owner_rule"], *cfg["traps"]],
        "scale": {"tier": tier, "reasons": [f"{overlap_commits} fork commits touch files upstream changed",
                                            f"{len(conflicts)} files conflict in a trial merge"]},
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


def cmd_drops(a, cfg):
    """Prints the decided drops that belong to one layer's range, comma-separated, for todo-edit."""
    drops, _ = decisions(json.loads(Path(a.decisions).read_text()))
    shas = lines("rev-list", a.range)
    print(",".join(in_range(drops, shas)[0]))
    return OK


def cmd_verify(a, cfg):
    fails, notes = [], []
    drops = [d for d in (a.expect_drop or "").split(",") if d]
    if a.decisions:
        auto_drops, hints = decisions(json.loads(Path(a.decisions).read_text()))
        # The classify result covers both layers; only drops of commits in this layer's old range apply here.
        mine, other = in_range(auto_drops, lines("rev-list", f"{a.base_old}..{a.old}"))
        drops += [d for d in mine if d not in drops]
        notes += [f"hint: {h}" for h in hints]
        notes += [f"expected drop from the classify decisions: {d}" for d in mine]
        notes += [f"decided drop {d} belongs to the other layer" for d in other]
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
            fails.append(f"commit {e['new']} '{e['subject']}' is new in {a.new}")
        if e["mark"] == "!":
            report.append(f"### {e['old']} -> {e['new']} {e['subject']}")
            report += ["```diff", *(e["changed"] or ["(context or line numbers only)"]), "```", ""]
    for d in drops:
        if not any(e["mark"] == "<" and sha_matches(e["old"], d) for e in entries):
            fails.append(f"expected drop {d} is still in {a.new} or not in {a.old}")

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

    # 8. Up to date and layered.
    behind = int(out("rev-list", "--count", f"{a.new}..{a.upstream}"))
    if behind:
        fails.append(f"{a.new} is {behind} commits behind {a.upstream}")
    if a.main_new and git("merge-base", "--is-ancestor", a.main_new, a.new, check=False).returncode != 0:
        fails.append(f"{a.main_new} is not an ancestor of {a.new}")

    report += ["### Notes", "", *[f"- {n}" for n in notes or ["none"]], "", "### Result", ""]
    report += [f"- FAIL: {f}" for f in fails] or ["- all checks pass"]
    print("\n".join(report))
    return FAIL if fails else OK


def cmd_protected_apk(a, cfg):
    """Each protected feature's native library is in the APK and carries its marker strings."""
    import zipfile
    fails = []
    with zipfile.ZipFile(a.apk) as z:
        names = set(z.namelist())
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
    for f in fails:
        print(f"FAIL: {f}")
    return FAIL if fails else OK


def cmd_test_failures(a, cfg):
    paths = glob.glob(os.path.join(a.dir, "*.xml"))
    if not paths:
        print(f"no JUnit XML in {a.dir}", file=sys.stderr)
        return USAGE
    print("\n".join(junit_failures(paths)))
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
    s.add_argument("--main", default="origin/main")
    s.add_argument("--perf", default="origin/perf/shield-pass")
    s.add_argument("--scratch", default="")
    s = sub.add_parser("todo-edit")
    s.add_argument("--drop", required=True)
    s.add_argument("todo")
    s = sub.add_parser("drops")
    s.add_argument("--decisions", required=True)
    s.add_argument("--range", required=True)
    s = sub.add_parser("verify")
    for name in ("--base-old", "--old", "--base-new", "--new", "--upstream"):
        s.add_argument(name, required=True)
    s.add_argument("--main-new")
    s.add_argument("--decisions")
    s.add_argument("--expect-drop")
    s.add_argument("--allow-same", action="append")
    s.add_argument("--allow-changed", action="append")
    s.add_argument("--protected-drop", help="owner-approved drops of protected-feature commits, SHA[,SHA...]")
    s.add_argument("--creation-factor", type=int, default=100, help="git range-diff --creation-factor (default 100)")
    s = sub.add_parser("protected-apk")
    s.add_argument("apk")
    s = sub.add_parser("test-failures")
    s.add_argument("dir")
    a = p.parse_args(argv)
    cfg = load_config()
    try:
        return {"preflight": cmd_preflight, "map-overlap": cmd_map_overlap, "todo-edit": cmd_todo_edit, "drops": cmd_drops,
                "verify": cmd_verify, "protected-apk": cmd_protected_apk,
                "test-failures": cmd_test_failures}[a.cmd](a, cfg)
    except RuntimeError as e:
        print(f"sync.py {a.cmd}: {e}", file=sys.stderr)
        return USAGE


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
