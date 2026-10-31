"""The advisory /upstream-sync commands, and the git helpers sync.py shares with them. Run them through sync.py."""
import contextlib
import json
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path

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


def declared_paths(text):
    """A declared file: one path per line; blank lines and `#` comments are skipped."""
    return {l.strip() for l in text.splitlines() if l.strip() and not l.strip().startswith("#")}


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


def read_file(path):
    try:
        return Path(path).read_text(encoding="utf-8")
    except OSError as e:
        raise RuntimeError(f"cannot read {path}: {e.strerror}")


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


# ---------------------------------------------------------------- official-preview

# Private to the preview: refs/remotes/official/* belongs to anyone who has a remote named official.
OFFICIAL_REF = "refs/upstream-sync/official-preview"


def latest_tag(url):
    """The newest version tag: a pre-release sorts before its release, beta.10 after beta.9."""
    rows = lines("-c", "versionsort.suffix=-", "ls-remote", "--tags", "--refs", "--sort=-v:refname", url)
    tags = (r.split("refs/tags/", 1)[1] for r in rows)
    return next((t for t in tags if re.match(r"v?\d", t)), None)


def merged_prs(repo, branch, limit=10):
    """Recent merged PR titles, through REST: GraphQL, and with it `gh pr`, is blocked in cloud sessions."""
    rows = gh_json("api", f"repos/{repo}/pulls?state=closed&base={branch}&sort=updated&direction=desc&per_page=50")
    return [f"#{p['number']} {p['title']}" for p in rows or [] if p.get("merged_at")][:limit]


def preview_git(url, branch, tip, upstream, fork):
    """Fetches by URL, so no remote exists and nothing has a push URL. The ref goes away again in the caller."""
    git("fetch", "--quiet", "--no-tags", "--no-write-fetch-head", url, f"+refs/heads/{branch}:{tip}")
    print(f"official {branch}: tip {out('rev-parse', '--short=9', tip)}, latest tag {latest_tag(url) or 'none'}")
    ahead = lines("log", "--no-merges", "--format=%s", f"{upstream}..{tip}")
    known = set(lines("log", "--format=%s", upstream))
    missing = [s for s in ahead if s not in known]
    print(f"{len(missing)} of its commits are not in {upstream} by subject ({len(ahead)} outside its history)")
    with scratch_objects() as env:
        conflicts = merge_tree(fork, tip, env=env)[1]
    print(f"trial merge with {fork}: {len(conflicts)} conflicting files")
    print("".join(f"  {f}\n" for f in conflicts), end="")


def drop_preview_ref():
    """Deletes the ref the fetch wrote, and only that ref. After a failed fetch there is none."""
    try:
        if git("rev-parse", "--verify", "--quiet", OFFICIAL_REF, check=False).returncode == 0:
            git("update-ref", "-d", OFFICIAL_REF)
    except Exception as e:
        print(f"official: ref not removed: {OFFICIAL_REF} ({e})")


def cmd_official_preview(a, cfg):
    """Advisory: every failure is a line of output and the exit code stays 0, so each stage catches Exception."""
    repo, branch = cfg["official_repo"], cfg["official_branch"]
    upstream = a.upstream or f"{cfg['remotes']['upstream']}/main"
    fork = a.fork or f"{cfg['remotes']['fork']}/{cfg['layers']['perf']}"
    print(f"official preview of {repo} {branch}; advisory, it blocks nothing")
    try:
        preview_git(a.url or f"https://github.com/{repo}.git", branch, OFFICIAL_REF, upstream, fork)
    except Exception as e:
        print(f"official: not available ({e})")
    finally:
        drop_preview_ref()
    try:
        titles = merged_prs(repo, branch)
        print(f"recent merged PRs into {branch}: {len(titles)}")
        print("".join(f"  {t}\n" for t in titles), end="")
    except Exception as e:
        print(f"recent merged PRs: skipped, gh is not available ({e})")
    return OK


# ---------------------------------------------------------------- judgement

JUDGEMENT_STRATEGY = "git merge-tree -X theirs OLD NEW_BASE: the old tip is ours, so upstream wins a conflicting hunk"


def cmd_judgement(a, cfg):
    """Where the rebased tip differs from a mechanical merge is where someone made a call: the path must be declared."""
    old = a.old or f"{cfg['remotes']['fork']}/{cfg['layers']['perf']}"
    new_base = a.new_base or f"{cfg['remotes']['upstream']}/main"
    new = a.new or "HEAD"
    declared = declared_paths(read_file(a.declared)) if a.declared else None
    with scratch_objects() as env:
        tree, unsettled = merge_tree("-Xtheirs", old, new_base, env=env)
        args = ("diff", "--no-renames", tree, new)
        differ = lines(*args, "--name-only", env=env)
        stat = git(*args, "--stat=100", env=env).stdout.rstrip()
        if a.diff:
            Path(a.diff).write_bytes(git(*args, env=env, binary=True).stdout)
    print(f"judgement: {new} against the mechanical merge of {old} and {new_base}")
    print(f"strategy: {JUDGEMENT_STRATEGY}")
    if unsettled:
        print(f"the strategy left {len(unsettled)} files conflicted: {', '.join(unsettled)}")
    print(stat or "no difference")
    if declared is None:
        return OK
    undeclared = [p for p in differ if p not in declared]
    print(f"declared: {len(differ) - len(undeclared)} of {len(differ)} differing paths; "
          "nothing inside a declared file is checked")
    for path in undeclared:
        print(f"FAIL: undeclared difference: {path}")
    return FAIL if undeclared else OK


# ---------------------------------------------------------------- registration

COMMANDS = {"dry-run": cmd_dry_run, "due": cmd_due, "official-preview": cmd_official_preview,
            "judgement": cmd_judgement}


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
    s = sub.add_parser("official-preview", help="read-only look at what official NuvioTV will bring next")
    s.add_argument("--url", help="fetch from this URL or path instead of official_repo")
    s.add_argument("--upstream", help="default: upstream/main")
    s.add_argument("--fork", help="default: the fork's perf branch on the fork remote")
    s = sub.add_parser("judgement", help="diff the rebased tip with a mechanical merge of the old tip and the new base")
    s.add_argument("--old", help="default: the fork's perf branch on the fork remote")
    s.add_argument("--new-base", help="default: upstream/main")
    s.add_argument("--new", help="default: HEAD")
    s.add_argument("--declared", help="a text file of paths; a differing path that is not listed fails")
    s.add_argument("--diff", help="write the full diff here, for the reviewer")
