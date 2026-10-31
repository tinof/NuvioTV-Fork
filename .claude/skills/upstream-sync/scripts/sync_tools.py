"""The advisory /upstream-sync commands, and the git helpers sync.py shares with them. Run them through sync.py."""
import contextlib
import json
import os
import re
import subprocess
import tempfile
import time

OK, FAIL, USAGE, NOTHING = 0, 1, 2, 3
SCRATCH_IDENTITY = {"GIT_AUTHOR_NAME": "upstream-sync", "GIT_AUTHOR_EMAIL": "upstream-sync@invalid",
                    "GIT_COMMITTER_NAME": "upstream-sync", "GIT_COMMITTER_EMAIL": "upstream-sync@invalid"}


# ---------------------------------------------------------------- pure helpers (unit-tested)

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


def protected_path(path, features):
    """The key of the protected feature that owns [path] by anchor or binary, or None."""
    for f in features:
        if path in f.get("binaries", []) or any(re.search(p, path) for p in f["anchors"]):
            return f["key"]
    return None


def feature_of(files, features, subject=""):
    """The key of the first protected feature (sync.json protected_features) whose anchors match one of [files] or
    whose subjects pattern matches [subject]; a feature commit that touches only shared files is found by subject."""
    for f in features:
        if any(re.search(p, path) for p in f["anchors"] for path in files):
            return f["key"]
        if subject and f.get("subjects") and re.search(f["subjects"], subject):
            return f["key"]
    return None


def stop_features(commit_files, stop_files, subject, features):
    """The protected features a predicted stop involves: by the replayed commit, or by a conflicting file."""
    return [f["key"] for f in features
            if feature_of(commit_files, [f], subject) or any(protected_path(p, [f]) for p in stop_files)]


def due_verdict(behind, quiet_hours, age_days, settle_hours, max_age_days):
    """-> (verdict, reason). Advisory: an owner can always sync, and nothing blocks on "wait"."""
    if behind == 0:
        return "level", "0 commits behind"
    if quiet_hours >= settle_hours:
        return "due", f"a settled batch waits: first-parent quiet for at least {settle_hours} h"
    if age_days > max_age_days:
        return "due", f"the oldest unsynced first-parent commit is older than {max_age_days} days"
    return "wait", f"upstream may still be pushing: quiet under {settle_hours} h, oldest within {max_age_days} days"


# ---------------------------------------------------------------- git and gh

def git(*args, check=True, binary=False, env=None):
    r = subprocess.run(["git", *args], capture_output=True, text=not binary, env=env)
    if check and r.returncode != 0:
        err = r.stderr if not binary else r.stderr.decode(errors="replace")
        raise RuntimeError(f"git {' '.join(args)}: {' / '.join(err.strip().splitlines()[:2])}")
    return r


def out(*args, env=None):
    return git(*args, env=env).stdout.strip()


def lines(*args, env=None):
    return [l for l in out(*args, env=env).splitlines() if l.strip()]


def gh_json(*args):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args)}: {r.stderr.strip()}")
    return json.loads(r.stdout or "null")


def commits_with_files(rng):
    """[{sha, subject, files}] oldest first for a revision range, in the order a rebase replays them."""
    text = out("log", "--reverse", "--topo-order", "--no-merges", "--no-renames", "--format=@@%H\t%s", "--name-only", rng)
    result = []
    for line in text.splitlines():
        if line.startswith("@@"):
            sha, _, subject = line[2:].partition("\t")
            result.append({"sha": sha, "subject": subject, "files": []})
        elif line.strip() and result:
            result[-1]["files"].append(line.strip())
    return result


@contextlib.contextmanager
def scratch_objects():
    """An environment for git calls: new objects land in a temporary directory that goes away, reads fall through to
    the repository's own objects. The real repository gets no new objects."""
    real = out("rev-parse", "--path-format=absolute", "--git-path", "objects")
    with tempfile.TemporaryDirectory(prefix="upstream-sync-") as tmp:
        objects = os.path.join(tmp, "objects")
        os.mkdir(objects)
        yield {**os.environ, **SCRATCH_IDENTITY, "GIT_OBJECT_DIRECTORY": objects,
               "GIT_ALTERNATE_OBJECT_DIRECTORIES": real}


def merge_tree(*args, env=None):
    """One `git merge-tree --write-tree` -> (tree, conflicted paths). A git failure raises, a conflict does not."""
    r = git("merge-tree", "--write-tree", "--name-only", "--no-messages", *args, check=False, env=env)
    if r.returncode not in (0, 1):
        raise RuntimeError(f"git merge-tree {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout.split("\n", 1)[0].strip(), parse_merge_tree(r.stdout, r.returncode)


# ---------------------------------------------------------------- dry-run

DRY_RUN_STRATEGY = ("per-commit git merge-tree replay in rebase order, chained with commit-tree; "
                    "a stopped commit continues on its -X ours result")
DRY_RUN_NOTE = ("A stop is a prediction, and false alarms are expected: the chain continues from -X ours, the real "
                "rebase from a hand resolution. A clean replay says nothing about meaning. An empty commit is not "
                "clean: rebase -i stops on it, a plain rebase drops it.")


def replay(base, fork, onto, features, env):
    """Replays base..fork onto [onto] in scratch objects -> (commit count, clean count, stops, empty commits).
    Each commit counts once: clean, a stop, or empty."""
    commits = commits_with_files(f"{base}..{fork}")
    head, head_tree = out("rev-parse", onto, env=env), out("rev-parse", f"{onto}^{{tree}}", env=env)
    clean, stops, empty = 0, [], []
    for c in commits:
        sha = c["sha"]
        where = f"--merge-base={sha}^"
        tree, conflicts = merge_tree(where, head, sha, env=env)
        if conflicts:
            stops.append({"sha": sha[:12], "subject": c["subject"], "files": conflicts,
                          "protected_features": stop_features(c["files"], conflicts, c["subject"], features)})
            # A conflict -X ours cannot settle, such as modify/delete, still has a result tree. The stop is reported
            # above, and the later commits need this commit's clean changes.
            tree = merge_tree("-Xours", where, head, sha, env=env)[0]
        elif tree == head_tree and c["files"]:
            empty.append({"sha": sha[:12], "subject": c["subject"]})
        else:
            clean += 1
        if tree != head_tree:
            head, head_tree = out("commit-tree", tree, "-p", head, "-m", sha, env=env), tree
    return len(commits), clean, stops, empty


def cmd_dry_run(a, cfg):
    fork = a.fork or f"{cfg['remotes']['fork']}/{cfg['layers']['perf']}"
    onto = a.onto or f"{cfg['remotes']['upstream']}/main"
    base = a.base or out("merge-base", fork, onto)
    with scratch_objects() as env:
        replayed, clean, stops, empty = replay(base, fork, onto, cfg.get("protected_features", []), env)
    report = {"strategy": DRY_RUN_STRATEGY, "note": DRY_RUN_NOTE, "base": out("rev-parse", base),
              "fork": out("rev-parse", fork), "onto": out("rev-parse", onto), "replayed": replayed, "clean": clean,
              "stops": stops, "empty": empty}
    if a.json:
        print(json.dumps(report, indent=1))
        return OK
    emptied = f", {len(empty)} empty (rebase -i stops, a plain rebase drops)" if empty else ""
    print(f"dry-run: {replayed} commits of {fork} onto {onto}: {clean} clean, {len(stops)} predicted stops{emptied}")
    print(f"strategy: {DRY_RUN_STRATEGY}")
    for s in stops:
        mark = f" [protected: {', '.join(s['protected_features'])}]" if s["protected_features"] else ""
        print(f"stop {s['sha'][:9]} {s['subject']}{mark}")
        print("".join(f"  {f}\n" for f in s["files"]), end="")
    for e in empty:
        print(f"empty {e['sha'][:9]} {e['subject']} (rebase -i stops here; the replay adds nothing, so upstream may "
              "already hold it)")
    print(f"note: {DRY_RUN_NOTE}")
    return OK


# ---------------------------------------------------------------- due

OFFICIAL_MERGE = re.compile(r"^Merge official\b")


def due_report(cfg, upstream, fork, now=None):
    """Whether a sync is due, from local refs only; it never fetches. Ages count first-parent commits, because a
    "Merge official" brings side commits weeks older than the base, which would read as overdue the hour it lands."""
    now = time.time() if now is None else now
    settle, max_age = cfg.get("settle_hours", 24), cfg.get("max_age_days", 7)
    base = out("merge-base", fork, upstream)
    behind = int(out("rev-list", "--count", f"{fork}..{upstream}"))
    rows = [l.split("\t", 2) for l in lines("log", "--first-parent", "--format=%H\t%ct\t%s", f"{base}..{upstream}")]
    tip_time = int(out("log", "-1", "--format=%ct", upstream))
    quiet = max(0.0, (now - tip_time) / 3600)
    age = max(0.0, (now - int(rows[-1][1])) / 86400) if rows else 0.0
    merge = next((r for r in rows if OFFICIAL_MERGE.search(r[2])), None)
    verdict, reason = due_verdict(behind, quiet, age, settle, max_age)
    return {"verdict": verdict, "reason": reason, "behind": behind, "base": base, "upstream": out("rev-parse", upstream),
            "newest_official_merge": {"sha": merge[0][:12], "subject": merge[2],
                                      "hours_ago": round(max(0.0, (now - int(merge[1])) / 3600), 1)} if merge else None,
            "quiet_hours": round(quiet, 1), "age_days": round(age, 1), "settle_hours": settle, "max_age_days": max_age}


def due_line(r):
    merge = r.get("newest_official_merge")
    merged = f"newest official merge {merge['sha'][:9]}, {merge['hours_ago']} h ago" if merge else "no official merge"
    return (f"due: {r['verdict']}: {r['reason']} ({r['behind']} behind, {merged}, first-parent quiet "
            f"{r['quiet_hours']} h, oldest unsynced first-parent commit {r['age_days']} days)")


def cmd_due(a, cfg):
    upstream = a.upstream or f"{cfg['remotes']['upstream']}/main"
    fork = a.fork or f"{cfg['remotes']['fork']}/{cfg['layers']['perf']}"
    report = due_report(cfg, upstream, fork, a.now)
    print(json.dumps(report, indent=1) if a.json else due_line(report))
    return OK


# ---------------------------------------------------------------- registration

COMMANDS = {"dry-run": cmd_dry_run, "due": cmd_due}


def add_parsers(sub):
    s = sub.add_parser("dry-run", help="predict the rebase's stops without touching the repository")
    s.add_argument("--base", help="where the fork's commits start (default: the merge base of fork and onto)")
    s.add_argument("--fork", help="default: the fork's perf branch on the fork remote")
    s.add_argument("--onto", help="default: upstream/main")
    s.add_argument("--json", action="store_true")
    s = sub.add_parser("due", help="say whether a sync is due; advisory, it never blocks")
    s.add_argument("--upstream", help="default: upstream/main")
    s.add_argument("--fork", help="default: the fork's perf branch on the fork remote")
    s.add_argument("--now", type=float, help="the time to judge at, in seconds since the epoch (default: the clock)")
    s.add_argument("--json", action="store_true")
