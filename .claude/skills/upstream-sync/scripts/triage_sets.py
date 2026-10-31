#!/usr/bin/env python3
"""The map for the upstream-sync-triage workflow, from three refs and sync.json. Does not use sync.py.

Run inside the repository:
  triage_sets.py --base R --fork R --upstream R --out <scratch>/triage/map.json [--config sync.json]
                 [--pr-titles FILE]

Writes the full map to --out and prints the small JSON that the workflow takes as args: paths and SHAs only.
The reader sets are exclusive and hold the main-code files both sides changed. The stops come from a forward
replay of every fork commit, in memory, without a worktree. The replay leaves dangling objects in the repository
(`git gc` removes them). Fixed dates make them the same on every run, so a rerun adds none. map.stops[].head points
at them, so a resolver can redo the merge of its stop.
"""
import argparse
import collections
import json
import math
import os
import re
import subprocess
import sys
from pathlib import Path

CONFIG_PATH = Path(__file__).resolve().parents[1] / "sync.json"
MAX_READERS = 3
READER_LINES = 700  # upstream lines added or removed in main code that one reader is sized for
HEAVY_READER_LINES = 1050  # a set over this gets a cap line
MAIN_CODE = re.compile(r"\.(kt|kts|java|c|cc|cpp|h|hpp)$|(^|/)CMakeLists\.txt$|(^|/)AndroidManifest\.xml$")
TEST_CODE = re.compile(r"(^|/)src/(test|androidTest)/|^scripts/tests/")
IDENT = {"GIT_AUTHOR_NAME": "triage", "GIT_AUTHOR_EMAIL": "triage@invalid", "GIT_AUTHOR_DATE": "1000000000 +0000",
         "GIT_COMMITTER_NAME": "triage", "GIT_COMMITTER_EMAIL": "triage@invalid", "GIT_COMMITTER_DATE": "1000000000 +0000"}


def is_main_code(path):
    return MAIN_CODE.search(path) is not None and TEST_CODE.search(path) is None


def theme_of(path, themes):
    return next((t["key"] for t in themes if any(re.search(p, path) for p in t["paths"])), "app-other")


def feature_of(files, subject, features):
    """The protected feature of a commit: a file matches an anchor, or the subject matches the feature's pattern."""
    for f in features:
        if any(re.search(p, path) for p in f["anchors"] for path in files):
            return f["key"]
        if f.get("subjects") and re.search(f["subjects"], subject):
            return f["key"]
    return None


def reader_count(lines):
    """How many readers the upstream lines ask for, before the cap of MAX_READERS."""
    return max(1, math.ceil(lines / READER_LINES))


def partition(weights, themes, n):
    """{path: upstream lines} -> up to n exclusive sets. A theme stays whole, so one reader sees its related files;
    the heaviest theme goes first, each to the lightest set. Ties break on the name, so the result is deterministic."""
    chunks = collections.defaultdict(list)
    for path in sorted(weights):
        chunks[theme_of(path, themes)].append(path)
    total = {k: sum(weights[p] for p in v) for k, v in chunks.items()}
    sets = [{"themes": [], "paths": [], "lines": 0} for _ in range(n)]
    for key in sorted(chunks, key=lambda k: (-total[k], k)):
        target = min(sets, key=lambda s: s["lines"])
        target["themes"].append(key)
        target["paths"] += chunks[key]
        target["lines"] += total[key]
    return [s for s in sets if s["paths"]]


def parse_numstat(text):
    """`git diff --numstat` -> {path: lines changed}; a binary file counts as 1."""
    result = {}
    for line in text.splitlines():
        added, removed, path = line.split("\t", 2)
        result[path] = 1 if added == "-" else int(added) + int(removed)
    return result


def git(*args, ok=(0,), env=None):
    r = subprocess.run(["git", *args], capture_output=True, text=True, errors="replace", env=env)
    if r.returncode not in ok:
        raise RuntimeError(f"git {' '.join(args[:3])}: {r.stderr.strip()[:300]}")
    return r


def out(*args):
    return git(*args).stdout.strip()


def fork_commits(base, fork):
    """[{sha, subject, files}], oldest first."""
    text = out("log", "--reverse", "--topo-order", "--no-merges", "--no-renames", "--format=@@%H\t%s", "--name-only",
               f"{base}..{fork}")
    commits = []
    for line in text.splitlines():
        if line.startswith("@@"):
            sha, _, subject = line[2:].partition("\t")
            commits.append({"sha": sha, "subject": subject, "files": []})
        elif line.strip():
            commits[-1]["files"].append(line.strip())
    return commits


def merge(base, ours, theirs, *extra):
    """(conflicted paths, tree) of one in-memory merge."""
    r = git("merge-tree", "--write-tree", "--name-only", "--no-messages", *extra, f"--merge-base={base}", ours, theirs,
            ok=(0, 1))
    tree, *paths = r.stdout.splitlines()
    return (sorted({p.strip() for p in paths if p.strip()}) if r.returncode else []), tree


def replay(commits, onto, strategy):
    """{sha: {files, head}} for the commits that stop when replayed on [onto]. A stop continues on -X[strategy], and
    on the chain's own tree where that still fails (a binary file). `head` is the chain commit the stop applies to."""
    head, stops = out("rev-parse", onto), {}
    env = {**os.environ, **IDENT}
    for c in commits:
        files, tree = merge(f"{c['sha']}^", head, c["sha"])
        if files:
            stops[c["sha"]] = {"files": files, "head": head}
            still, tree = merge(f"{c['sha']}^", head, c["sha"], f"-X{strategy}")
            if still:
                tree = out("rev-parse", f"{head}^{{tree}}")
        head = git("commit-tree", tree, "-p", head, "-m", "triage replay", env=env).stdout.strip()
    return stops


def build(base, fork, upstream, cfg, map_path, pr_titles=""):
    """(the map, the workflow's args)."""
    mb = out("merge-base", fork, upstream)
    if mb != out("rev-parse", f"{base}^{{commit}}"):
        print(f"triage_sets: note: merge base {mb[:9]} differs from --base {base}", file=sys.stderr)
    themes, features = cfg["themes"], cfg.get("protected_features", [])
    commits = fork_commits(base, fork)
    for c in commits:
        c["protected"] = feature_of(c["files"], c["subject"], features)
    upstream_lines = parse_numstat(out("diff", "--no-renames", "--numstat", base, upstream))
    fork_files = set(out("diff", "--no-renames", "--name-only", base, fork).splitlines())
    both = sorted(set(upstream_lines) & fork_files)
    weights = {p: upstream_lines[p] for p in both if is_main_code(p)}
    excluded = [p for p in both if p not in weights]

    wanted = reader_count(sum(weights.values()))
    n = min(wanted, MAX_READERS)
    sets = partition(weights, themes, n)
    caps = []
    if wanted > MAX_READERS:
        caps.append(f"readers capped at {MAX_READERS}: {sum(weights.values())} upstream lines ask for {wanted}; "
                    "no file is dropped, the sets are heavier")
    for i, s in enumerate(sets, 1):
        if s["lines"] > HEAVY_READER_LINES:
            caps.append(f"reader {i} carries {s['lines']} upstream lines, over {HEAVY_READER_LINES}; no file is dropped")
    if excluded:
        caps.append(f"{len(excluded)} files both sides changed are not main code and go to no reader; "
                    f"the resolver sees their conflicts (map.excluded)")

    merged = git("merge-tree", "--write-tree", "--name-only", "--no-messages", upstream, fork, ok=(0, 1))
    trial_tree, *conflicts = merged.stdout.splitlines()
    conflicts = sorted({p.strip() for p in conflicts if p.strip()}) if merged.returncode else []

    ours, theirs = replay(commits, upstream, "ours"), replay(commits, upstream, "theirs")
    by_file = collections.defaultdict(list)
    for c in commits:
        for p in c["files"]:
            by_file[p].append({"sha": c["sha"][:12], "subject": c["subject"], "protected": c["protected"]})
    stops = [{"sha": c["sha"][:12], "subject": c["subject"], "protected": c["protected"],
              "files": ours[c["sha"]]["files"], "head": ours[c["sha"]]["head"], "alsoInTheirsReplay": c["sha"] in theirs}
             for c in commits if c["sha"] in ours]

    fork_tip, upstream_tip = out("rev-parse", fork), out("rev-parse", upstream)
    result = {
        "mb": mb, "forkTip": fork_tip, "upstreamTip": upstream_tip, "trialTree": trial_tree,
        "rules": [cfg["owner_rule"], *cfg["traps"]],
        "conflictFiles": conflicts, "stops": stops, "excluded": excluded,
        "sets": [{"reader": i, "themes": s["themes"], "upstreamLines": s["lines"],
                  "files": [{"path": p, "upstreamLines": weights[p], "conflict": p in conflicts,
                             "forkCommits": by_file[p]} for p in s["paths"]]}
                 for i, s in enumerate(sets, 1)],
        "caps": caps,
    }
    args = {
        "repo": out("rev-parse", "--show-toplevel"), "mapPath": str(map_path), "prTitlesPath": pr_titles,
        "upstreamRef": upstream, "forkRef": fork, "mb": mb, "upstreamTip": upstream_tip, "forkTip": fork_tip,
        "counts": {"fork": len(commits), "bothChanged": len(both), "mainCode": len(weights)},
        "sets": [s["paths"] for s in sets], "stops": [{"sha": s["sha"], "files": s["files"]} for s in stops],
        "conflictFiles": conflicts,
        "protected": [c["sha"][:12] for c in commits if c["protected"]], "caps": caps,
    }
    return result, args


def main(argv):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", required=True, help="where the fork's commits start (OLD_BASE)")
    p.add_argument("--fork", required=True, help="the fork's tip (OLD_PERF)")
    p.add_argument("--upstream", required=True, help="the commit the fork will be rebased onto")
    p.add_argument("--out", required=True, help="where the map goes")
    p.add_argument("--config", default=str(CONFIG_PATH), help="sync.json; a replay passes the one of the old state")
    p.add_argument("--pr-titles", default="", help="a text file with official PR titles, if there is one")
    a = p.parse_args(argv)
    out_path = Path(a.out).resolve()
    try:
        result, args = build(a.base, a.fork, a.upstream, json.loads(Path(a.config).read_text()), out_path, a.pr_titles)
    except RuntimeError as e:
        print(f"triage_sets: {e}", file=sys.stderr)
        return 2
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(result, indent=1) + "\n")
    print(json.dumps(args))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
