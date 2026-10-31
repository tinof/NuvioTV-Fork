"""The advisory /upstream-sync commands, and the git helpers sync.py shares with them. Run them through sync.py."""
import contextlib
import json
import os
import re
import subprocess
import tempfile

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


# ---------------------------------------------------------------- registration

COMMANDS = {"dry-run": cmd_dry_run}


def add_parsers(sub):
    s = sub.add_parser("dry-run", help="predict the rebase's stops without touching the repository")
    s.add_argument("--base", help="where the fork's commits start (default: the merge base of fork and onto)")
    s.add_argument("--fork", help="default: the fork's perf branch on the fork remote")
    s.add_argument("--onto", help="default: upstream/main")
    s.add_argument("--json", action="store_true")
