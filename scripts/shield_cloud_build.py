"""Build the Shield APK on the private build repo tinof/NuvioTV-Builds and download it, verified.

    python3 -I scripts/shield_cloud_build.py build --variant Debug|Benchmark --suffix .shield [--debuggable]
        --version-code 1580 --version-name 1.1.0-nt.10.shield [--rebuild]

scripts/shield_build_install.sh runs this in its default (cloud) mode, then gives the downloaded APK the
checks and the install a local build gets. Progress goes to stderr. On success stdout holds key=value
lines: commit, run_id, run_url, apk, stamp, ffmpeg (paths relative to the repository root).

Preflight, exit 1: the tree has a tracked change or an untracked file; HEAD is on no origin branch after
`git fetch origin` (the runner fetches the commit from GitHub); HEAD pins no FFmpeg asset; the Mac has no
CI debug keystore to check the signature against; the build repo is not private; its workflow is not
HEAD's scripts/cloud-build/shield-build.yml. The git checks run before any gh call.

Then the first of these that applies, for HEAD and the same inputs:
- reuse a verified download in .local/cloud-builds/ (not with --rebuild);
- attach to a recorded run that is still going, or (not with --rebuild) to one that succeeded and still
  has its artifact;
- dispatch: a new request id, whose state .local/cloud-builds/<request_id>.json is written before
  `gh workflow run`. The run is the one whose title carries the request id.

A finished run is downloaded into a temporary directory and verified against its build-info.json: HEAD
is still the commit; commit, request id, inputs and run id are the requested ones; the APK has the
recorded name, size and sha256; the FFmpeg asset is HEAD's pin; the stamp is the recorded one; the APK's
certificate is the Mac keystore's. Only then it becomes .local/cloud-builds/<request_id>/, the remote
artifact is deleted (a failure only warns) and older downloads beyond the 3 newest go. The pinned FFmpeg
asset is unpacked once into .local/cloud-ffmpeg/<tag>/, checked against the pin, for the tail's
protected-apk check. A drift of the property keys or of the Mac's own FFmpeg libraries only warns.

NUVIO_CLOUD_WAIT_MIN (default 100) is the wait budget, NUVIO_CLOUD_POLL_SECONDS (default 30) the poll
interval. Exit 0 verified; 1 a check, the run or gh failed; 2 bad input; 75 the budget ended while the
run was still going: it is never cancelled, so run the same command again to attach to it.
"""

import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

BUILD_REPO = "tinof/NuvioTV-Builds"
ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = "shield-build.yml"
WORKFLOW_SOURCE = "scripts/cloud-build/shield-build.yml"
WORKFLOW_TARGET = ".github/workflows/shield-build.yml"
PIN = "scripts/cloud-build/ffmpeg-asset.json"
STATE_DIR = Path(".local/cloud-builds")
FFMPEG_DIR = Path(".local/cloud-ffmpeg")
STAMP = "libnuvio_rpu.so.stamp"
KEEP = 3
EXIT_WAIT = 75
MATCH_TRIES = 12
PARTIAL_HOURS = 6
RUN_FIELDS = "databaseId,displayTitle,event,status,conclusion,url,createdAt"
RUN_URL = re.compile(rf"https://github\.com/{re.escape(BUILD_REPO)}/actions/runs/([0-9]+)")
RE_REQUEST = re.compile(r"[a-z0-9-]{8,40}")
RE_SUFFIX = re.compile(r"(\.[a-z][a-z0-9_]*)+")
RE_CODE = re.compile(r"[0-9]{1,9}")
RE_NAME = re.compile(r"[0-9A-Za-z._-]{1,64}")
# The rule of filter_properties in scripts/cloud-build/ci_build.sh and scripts/shield_cloud_setup.py:
# these keys never reach the cloud. A test holds this copy to the setup script's.
STRIPPED = re.compile(r"sdk\.dir|FFMPEG_.*|USE_LOCAL_FFMPEG_DECODER|DOVI_LIBDOVI_.*|NUVIO_RELEASE_.*")


class CloudError(Exception):
    def __init__(self, message, code=1):
        super().__init__(message)
        self.code = code


def say(message):
    print(message, file=sys.stderr, flush=True)


# --- gh and git -----------------------------------------------------------------------------------

def gh(*args, check=True):
    """One gh call. It must name the build repo."""
    if not any(BUILD_REPO in arg for arg in args):
        raise CloudError(f"refusing a gh call that does not name {BUILD_REPO}: gh {' '.join(args)}")
    result = subprocess.run(["gh", *args], stdin=subprocess.DEVNULL, capture_output=True, text=True)
    if check and result.returncode:
        raise CloudError(f"gh {' '.join(args[:2])} failed: {result.stderr.strip()}")
    return result


def missing(result):
    text = result.stderr
    return "HTTP 404" in text or "Not Found" in text or "could not find" in text


def api(path, method="GET"):
    result = gh("api", "-X", method, f"repos/{BUILD_REPO}{path}", check=False)
    if result.returncode:
        if missing(result):
            return None
        raise CloudError(f"gh api {method} {path} failed: {result.stderr.strip()}")
    return json.loads(result.stdout) if result.stdout.strip() else {}


def assert_private():
    result = gh("repo", "view", BUILD_REPO, "--json", "isPrivate,visibility")
    info = json.loads(result.stdout)
    if info.get("isPrivate") is not True or info.get("visibility") != "PRIVATE":
        raise CloudError(f"{BUILD_REPO} is not private (visibility {info.get('visibility')}); refusing to build there")


def git(*args, check=True):
    result = subprocess.run(["git", *args], stdin=subprocess.DEVNULL, capture_output=True, text=True)
    if check and result.returncode:
        raise CloudError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return None


def number_env(name, default):
    value = os.environ.get(name, "")
    if value == "":
        return default
    try:
        number = float(value)
    except ValueError:
        number = -1.0
    if not number >= 0:  # also NaN
        raise CloudError(f"{name} must be a number of 0 or more, was {value!r}", code=2)
    return number


# --- inputs, pin, certificate -----------------------------------------------------------------------

def build_inputs(args):
    """The workflow inputs as build-info.json records them; exit 2 on a bad one."""
    if not RE_SUFFIX.fullmatch(args.suffix):
        raise CloudError(f"--suffix must match {RE_SUFFIX.pattern}, was {args.suffix!r}", code=2)
    if not RE_CODE.fullmatch(args.version_code):
        raise CloudError(f"--version-code must be a whole number, was {args.version_code!r}", code=2)
    if not RE_NAME.fullmatch(args.version_name):
        raise CloudError(f"--version-name must match {RE_NAME.pattern}, was {args.version_name!r}", code=2)
    if args.variant == "Benchmark" and args.debuggable:
        raise CloudError("a Benchmark build is never debuggable", code=2)
    return {"variant": args.variant, "suffix": args.suffix, "debuggable": bool(args.debuggable),
            "version_code": args.version_code, "version_name": args.version_name}


def read_pin(text):
    """The pin's tag, asset, sha256 and arm64 library hashes, checked as ci_build.sh checks them."""
    try:
        pin = json.loads(text)
        fields = {"tag": pin["tag"], "asset": pin["asset"], "sha256": pin["sha256"], "libs": pin["libs"]}
    except (ValueError, TypeError, KeyError):
        raise CloudError(f"{PIN} is not a valid pin") from None
    checks = {"tag": r"ffmpeg-n[0-9.]+-[0-9]+", "asset": r"[A-Za-z0-9._-]+", "sha256": r"[0-9a-f]{64}"}
    good = all(isinstance(fields[k], str) and re.fullmatch(c, fields[k]) for k, c in checks.items())
    libs = fields["libs"]
    good = good and isinstance(libs, dict) and bool(libs) and all(
        isinstance(v, str) and re.fullmatch(r"[a-z0-9_-]+/lib[a-z0-9]+\.a", k) and re.fullmatch(r"[0-9a-f]{64}", v)
        for k, v in libs.items())
    if not good:
        raise CloudError(f"{PIN} is not a valid pin (tag, asset, sha256 or libs)")
    return fields


def normalise_cert(fingerprint):
    """apksigner prints lowercase hex, keytool uppercase hex with colons: both become lowercase hex."""
    value = re.sub(r"[\s:]", "", str(fingerprint or "")).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise CloudError(f"not a SHA-256 certificate fingerprint: {fingerprint!r}")
    return value


def keystore():
    return Path(os.environ.get("NUVIO_DEBUG_STORE_FILE") or Path.home() / ".config/nuvio/nuvio-debug.keystore")


def mac_cert():
    """The SHA-256 of the CI debug key on the Mac, the key that signs the installed .shield build."""
    store = keystore()
    if not store.is_file():
        raise CloudError("no CI debug keystore on the Mac (NUVIO_DEBUG_STORE_FILE or ~/.config/nuvio/nuvio-debug.keystore) "
                         "to check the cloud APK's signature against")
    java_home = os.environ.get("JAVA_HOME")
    candidate = Path(java_home) / "bin/keytool" if java_home else None
    keytool = str(candidate) if candidate and candidate.is_file() else shutil.which("keytool")
    if not keytool:
        raise CloudError("no keytool (set JAVA_HOME or put a JDK on PATH) to read the Mac's debug keystore")
    result = subprocess.run([keytool, "-list", "-v", "-keystore", str(store), "-storepass", "android",
                             "-alias", "androiddebugkey"], stdin=subprocess.DEVNULL, capture_output=True, text=True)
    found = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", result.stdout) if result.returncode == 0 else None
    if not found:
        raise CloudError(f"keytool read no SHA256 fingerprint of androiddebugkey from {store}")
    return normalise_cert(found.group(1))


def apk_name(variant):
    return f"app-full-arm64-v8a-{variant.lower()}.apk"


def expected_title(state):
    """The run-name of shield-build.yml for these inputs."""
    return f"Shield {state['inputs']['variant']} {state['commit']} {state['request_id']}"


# --- preflight ----------------------------------------------------------------------------------------

def preflight():
    """(HEAD, pin, Mac certificate). The git checks first: they need no network and no gh."""
    changes = git("status", "--porcelain")
    if changes:
        shown = changes.splitlines()
        more = f" and {len(shown) - 5} more" if len(shown) > 5 else ""
        raise CloudError("the tree has uncommitted or untracked changes (" + "; ".join(s.strip() for s in shown[:5]) + more
                         + "). The cloud builds a pushed commit: commit and push them, or build this tree with --local")
    head = git("rev-parse", "HEAD")
    git("fetch", "--quiet", "origin")
    branches = [ref for ref in git("for-each-ref", "--contains", head, "--format=%(refname)", "refs/remotes/origin").split()
                if ref != "refs/remotes/origin/HEAD"]
    if not branches:
        raise CloudError(f"HEAD {head[:12]} is on no origin branch, and the runner fetches it from GitHub. "
                         "Push it, or build it with --local")
    committed = subprocess.run(["git", "show", f"HEAD:{PIN}"], stdin=subprocess.DEVNULL, capture_output=True, text=True)
    if committed.returncode:
        raise CloudError(f"HEAD has no {PIN}, so it pins no FFmpeg asset. Run "
                         "`python3 -I scripts/shield_cloud_setup.py ffmpeg`, commit the pin and push it")
    pin = read_pin(committed.stdout)
    cert = mac_cert()
    assert_private()
    blob = git("rev-parse", f"HEAD:{WORKFLOW_SOURCE}")
    remote = api(f"/contents/{WORKFLOW_TARGET}?ref=main")
    if not remote or remote.get("sha") != blob:
        raise CloudError(f"{BUILD_REPO}'s {WORKFLOW_TARGET} is not HEAD's {WORKFLOW_SOURCE}; "
                         "run `python3 -I scripts/shield_cloud_setup.py workflow`")
    return head, pin, cert


# --- state ----------------------------------------------------------------------------------------------

def state_path(request_id):
    return STATE_DIR / f"{request_id}.json"


def save_state(state):
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    path = state_path(state["request_id"])
    tmp = path.with_name(f".{path.name}.tmp")
    tmp.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n")
    tmp.replace(path)


def mark(state, status, note):
    state["status"], state["note"] = status, note
    save_state(state)


def matching_states(head, inputs):
    """The recorded requests for HEAD and these inputs, newest first."""
    found = []
    for path in STATE_DIR.glob("*.json") if STATE_DIR.is_dir() else []:
        state = read_json(path)
        if (isinstance(state, dict) and state.get("commit") == head and state.get("inputs") == inputs
                and RE_REQUEST.fullmatch(str(state.get("request_id"))) and path.name == f"{state['request_id']}.json"):
            found.append(state)
    return sorted(found, key=lambda s: (s.get("created", ""), s["request_id"]), reverse=True)


def new_request_id(variant):
    return f"{variant.lower()}-{datetime.now(timezone.utc):%Y%m%d-%H%M%S}-{secrets.token_hex(3)}"


# --- runs -------------------------------------------------------------------------------------------------

def parse_run_id(text):
    """The run id of the last run URL of the build repo that `gh workflow run` printed, or None."""
    found = RUN_URL.findall(text or "")
    return int(found[-1]) if found else None


def match_run(runs, title):
    """The one dispatched run with exactly this title; None when there is none; an error for two."""
    hits = [r for r in runs if r.get("event") == "workflow_dispatch" and r.get("displayTitle") == title]
    if len(hits) > 1:
        raise CloudError(f"{len(hits)} runs of {BUILD_REPO} carry the title {title!r}; "
                         f"ids {', '.join(str(r.get('databaseId')) for r in hits)}")
    return hits[0] if hits else None


def view_run(run_id):
    result = gh("run", "view", str(run_id), "-R", BUILD_REPO, "--json", RUN_FIELDS, check=False)
    if result.returncode:
        if missing(result):
            return None
        raise CloudError(f"gh run view {run_id} failed: {result.stderr.strip()}")
    return json.loads(result.stdout)


def list_runs():
    result = gh("run", "list", "-R", BUILD_REPO, "-w", WORKFLOW, "-e", "workflow_dispatch", "-L", "50",
                "--json", RUN_FIELDS)
    return json.loads(result.stdout or "[]")


def find_run(state, printed, tries, interval):
    """The id of the run this request started: the printed URL when its title matches, else the listed run."""
    title, url_id = expected_title(state), parse_run_id(printed)
    for attempt in range(tries):
        if attempt:
            time.sleep(interval)
        if url_id is not None:
            run = view_run(url_id)
            if run and match_run([run], title):
                return url_id
        found = match_run(list_runs(), title)
        if found:
            return int(found["databaseId"])
    return None


def artifact_of(run_id, request_id):
    """The run's unexpired artifact shield-apk-<request_id>, or None."""
    data = api(f"/actions/runs/{run_id}/artifacts") or {}
    for artifact in data.get("artifacts", []):
        if artifact.get("name") == f"shield-apk-{request_id}" and not artifact.get("expired"):
            return artifact
    return None


def run_url(run_id):
    return f"https://github.com/{BUILD_REPO}/actions/runs/{run_id}"


def attachable(states, rebuild):
    """A recorded request whose run is still going or (not with --rebuild) succeeded with its artifact."""
    for state in states:
        if state.get("status") not in ("dispatching", "dispatched"):
            continue
        run_id = state.get("run_id")
        if run_id is None:
            found = match_run(list_runs(), expected_title(state))
            if not found:
                mark(state, "abandoned", "no run carries its request id")
                continue
            run_id = state["run_id"] = int(found["databaseId"])
            state["run_url"] = run_url(run_id)
            mark(state, "dispatched", "")
        run = view_run(run_id)
        if run is None:
            mark(state, "abandoned", f"run {run_id} is gone")
        elif run.get("status") != "completed":
            say(f"== attaching to run {run_id} of request {state['request_id']} ({run.get('status')}): {run_url(run_id)}")
            return state
        elif run.get("conclusion") != "success":
            mark(state, "failed", f"run {run_id} ended {run.get('conclusion')}")
        elif rebuild:
            continue
        elif artifact_of(run_id, state["request_id"]) is None:
            mark(state, "expired", f"run {run_id} has no artifact left")
        else:
            say(f"== attaching to the finished run {run_id} of request {state['request_id']}: {run_url(run_id)}")
            return state
    return None


def dispatch(head, inputs, poll):
    state = {"schema": 1, "request_id": new_request_id(inputs["variant"]), "commit": head, "inputs": inputs,
             "created": datetime.now(timezone.utc).isoformat(timespec="seconds"), "status": "dispatching",
             "run_id": None, "run_url": None, "note": ""}
    save_state(state)  # before the dispatch, so a killed run of this script can find its run again
    assert_private()
    fields = {"commit": head, "request_id": state["request_id"], "variant": inputs["variant"], "suffix": inputs["suffix"],
              "debuggable": "true" if inputs["debuggable"] else "false", "version_code": inputs["version_code"],
              "version_name": inputs["version_name"]}
    argv = ["workflow", "run", WORKFLOW, "-R", BUILD_REPO, "--ref", "main"]
    for key, value in fields.items():
        argv += ["-f", f"{key}={value}"]
    say(f"== dispatching a {inputs['variant']} build of {head[:12]} on {BUILD_REPO}, request {state['request_id']}")
    result = gh(*argv, check=False)
    if result.returncode:
        mark(state, "failed", "gh workflow run failed")
        raise CloudError(f"gh workflow run failed: {result.stderr.strip()}")
    mark(state, "dispatched", "")
    run_id = find_run(state, result.stdout + "\n" + result.stderr, MATCH_TRIES, min(5.0, poll))
    if run_id is None:
        raise CloudError(f"no run of {BUILD_REPO} carries the title {expected_title(state)!r} yet; run the same "
                         f"command again to attach to it once it shows ({state_path(state['request_id'])})", code=EXIT_WAIT)
    state["run_id"], state["run_url"] = run_id, run_url(run_id)
    save_state(state)
    say(f"== run {run_id}: {state['run_url']}")
    return state


def wait_for(state, budget_minutes, poll):
    """The finished run. Exit 75 when the budget ends first; the run is never cancelled."""
    run_id, started = state["run_id"], time.monotonic()
    last, last_note, failures = None, started, 0
    while True:
        try:
            run = view_run(run_id)
            failures = 0
        except CloudError as error:
            run, failures = {}, failures + 1
            if failures >= 5:
                raise CloudError(f"{error}; the run keeps going: run the same command again to attach to it", code=EXIT_WAIT)
        if run is None:
            mark(state, "abandoned", f"run {run_id} is gone")
            raise CloudError(f"run {run_id} of {BUILD_REPO} is gone")
        status = run.get("status")
        if status == "completed":
            return run
        elapsed = time.monotonic() - started
        if status and (status != last or time.monotonic() - last_note >= 300):
            say(f"   run {run_id}: {status}, {elapsed / 60:.0f} min")
            last, last_note = status, time.monotonic()
        if elapsed >= budget_minutes * 60:
            raise CloudError(f"the wait budget of {budget_minutes:g} min (NUVIO_CLOUD_WAIT_MIN) ended; run {run_id} is "
                             f"still {status or 'unknown'} and keeps going ({run_url(run_id)}). Run the same command "
                             "again to attach to it", code=EXIT_WAIT)
        time.sleep(min(poll, max(budget_minutes * 60 - elapsed, 0)))


# --- download and verification ----------------------------------------------------------------------------

def verify(directory, state, pin, run_id, head, cert):
    """What is wrong with the download in this directory, as a list; empty when it is the requested build."""
    info = read_json(Path(directory) / "build-info.json")
    if not isinstance(info, dict):
        return ["the download has no readable build-info.json"]
    problems = []

    def differs(what, got, want):
        if got != want:
            problems.append(f"{what} is {got!r}, expected {want!r}")

    def part(key):
        value = info.get(key)
        return value if isinstance(value, dict) else {}

    if head != state["commit"]:
        problems.append(f"HEAD is {head[:12]} now; the build is of {state['commit'][:12]}")
    differs("build-info schema", info.get("schema"), 1)
    differs("build-info commit", info.get("commit"), state["commit"])
    differs("build-info request_id", info.get("request_id"), state["request_id"])
    differs("build-info inputs", info.get("inputs"), state["inputs"])
    differs("build-info run.id", str(part("run").get("id")), str(run_id))
    differs("build-info run.repository", part("run").get("repository"), BUILD_REPO)
    for key in ("tag", "asset", "sha256", "libs"):
        differs(f"build-info ffmpeg_asset.{key}", part("ffmpeg_asset").get(key), pin[key])
    name = apk_name(state["inputs"]["variant"])
    differs("build-info apk.name", part("apk").get("name"), name)
    apk = Path(directory) / name
    if not apk.is_file():
        problems.append(f"the download has no {name}")
    else:
        differs("the APK's size", apk.stat().st_size, part("apk").get("size"))
        differs("the APK's sha256", sha256_file(apk), part("apk").get("sha256"))
    stamp = read_json(Path(directory) / STAMP)
    if stamp is None:
        problems.append(f"the download has no readable {STAMP}")
    else:
        differs(f"the downloaded {STAMP}", stamp, info.get("rpu_stamp"))
    package = info.get("package")
    if not isinstance(package, str) or f"{state['inputs']['suffix']}." not in f"{package}.":
        problems.append(f"build-info package {package!r} does not carry the suffix {state['inputs']['suffix']}")
    try:
        signed = normalise_cert(info.get("cert_sha256"))
    except CloudError as error:
        problems.append(f"build-info cert_sha256: {error}")
    else:
        if signed != cert:
            problems.append(f"the APK is signed with {signed}, the Mac's CI debug keystore has {cert}")
    return problems


def download(state, run_id, pin, cert):
    """Download the run's artifact, verify it and make it .local/cloud-builds/<request_id>/."""
    request_id = state["request_id"]
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=f".partial-{request_id}-", dir=STATE_DIR))
    try:
        say(f"== downloading shield-apk-{request_id} of run {run_id}")
        gh("run", "download", str(run_id), "-R", BUILD_REPO, "-n", f"shield-apk-{request_id}", "-D", str(work))
        problems = verify(work, state, pin, run_id, git("rev-parse", "HEAD"), cert)
        if problems:
            mark(state, "failed", "the download did not verify")
            raise CloudError(f"the download of run {run_id} is not the requested build:\n" +
                             "\n".join(f"  - {p}" for p in problems))
        final = STATE_DIR / request_id
        if final.exists():
            shutil.rmtree(final)
        work.rename(final)
        os.utime(final)
        return final
    finally:
        shutil.rmtree(work, ignore_errors=True)


def delete_artifact(artifact):
    try:
        assert_private()
        result = gh("api", "-X", "DELETE", f"repos/{BUILD_REPO}/actions/artifacts/{artifact['id']}", check=False)
        failed = result.stderr.strip() if result.returncode else ""
    except CloudError as error:
        failed = str(error)
    if failed:
        say(f"warning: could not delete the artifact {artifact.get('name')} from {BUILD_REPO} ({failed}); "
            "it expires within a day")


def prune(keep):
    """Keep the KEEP newest downloads (always `keep`); an older one goes with its state file."""
    dirs = [p for p in STATE_DIR.iterdir() if p.is_dir() and RE_REQUEST.fullmatch(p.name)]
    dirs.sort(key=lambda p: (p != keep, -p.stat().st_mtime))
    for old in dirs[KEEP:]:
        shutil.rmtree(old)
        state_path(old.name).unlink(missing_ok=True)
        say(f"   removed the older download {old}")


def clean_partials():
    """Work directories that a killed run of this script left behind."""
    for parent in (STATE_DIR, FFMPEG_DIR):
        for path in parent.glob(".partial-*") if parent.is_dir() else []:
            if path.is_dir() and time.time() - path.stat().st_mtime > PARTIAL_HOURS * 3600:
                shutil.rmtree(path, ignore_errors=True)


# --- the FFmpeg asset for the tail's protected-apk check -----------------------------------------------

def lib_problems(directory, pin):
    problems = []
    for name, want in sorted(pin["libs"].items()):
        path = Path(directory) / "static-libs" / name
        if not path.is_file():
            problems.append(f"no static-libs/{name}")
        elif sha256_file(path) != want:
            problems.append(f"static-libs/{name} is not the pinned library")
    if not (Path(directory) / "headers").is_dir():
        problems.append("no headers/")
    return problems


def unpack(tar_path, target):
    with tarfile.open(tar_path) as tar:
        for member in tar.getmembers():
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not (member.isfile() or member.isdir()):
                raise CloudError(f"the FFmpeg asset has a member that is not a plain relative file: {member.name!r}")
        kwargs = {"filter": "data"} if hasattr(tarfile, "data_filter") else {}
        tar.extractall(target, **kwargs)


def ensure_ffmpeg(pin):
    """.local/cloud-ffmpeg/<tag>/ holding the pinned asset, downloaded and unpacked once."""
    final = FFMPEG_DIR / pin["tag"]
    if final.is_dir() and read_json(final / ".pin.json") == pin and not lib_problems(final, pin):
        return final
    FFMPEG_DIR.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=f".partial-{pin['tag']}-", dir=FFMPEG_DIR))
    try:
        (work / "download").mkdir()
        say(f"== downloading the FFmpeg asset {pin['asset']} of release {pin['tag']}, for the native origin check")
        gh("release", "download", pin["tag"], "-R", BUILD_REPO, "-p", pin["asset"], "-D", str(work / "download"))
        tar_path = work / "download" / pin["asset"]
        got = sha256_file(tar_path) if tar_path.is_file() else None
        if got != pin["sha256"]:
            raise CloudError(f"the FFmpeg asset of {pin['tag']} has sha256 {got}, the pin says {pin['sha256']}")
        unpack(tar_path, work / "asset")
        problems = lib_problems(work / "asset", pin)
        if problems:
            raise CloudError(f"the FFmpeg asset of {pin['tag']} does not match the pin: {'; '.join(problems)}")
        (work / "asset/.pin.json").write_text(json.dumps(pin, indent=2, sort_keys=True) + "\n")
        if final.exists():
            shutil.rmtree(final)
        (work / "asset").rename(final)
        return final
    finally:
        shutil.rmtree(work, ignore_errors=True)


# --- warnings -----------------------------------------------------------------------------------------------

def property_keys(text):
    """The keys with a value that the cloud secret would hold of this local.properties."""
    keys = set()
    for line in text.splitlines():
        if re.match(r"\s*([#!]|$)", line):
            continue
        found = re.match(r"\s*([^=:\s]+)\s*[=:]?\s*(.*?)\s*$", line)
        if found and found.group(2) and not STRIPPED.fullmatch(found.group(1)):
            keys.add(found.group(1))
    return keys


def warn_drift(directory, pin):
    info = read_json(directory / "build-info.json") or {}
    props = Path("local.properties")
    text = props.read_text() if props.is_file() else ""
    if text:
        cloud, mac = set(info.get("property_keys") or []), property_keys(text)
        if cloud != mac:
            say(f"warning: the cloud's local.properties keys differ from the Mac's (only in the cloud: "
                f"{', '.join(sorted(cloud - mac)) or 'none'}; only on the Mac: {', '.join(sorted(mac - cloud)) or 'none'}). "
                "After a change to local.properties run `python3 -I scripts/shield_cloud_setup.py secrets`")
    if "FFMPEG_BUILD_DIR" in os.environ:
        build = os.environ["FFMPEG_BUILD_DIR"]
    else:
        found = re.search(r"^[ \t]*FFMPEG_BUILD_DIR[ \t]*[=:][ \t]*(.*?)[ \t\r]*$", text, re.M)
        build = found.group(1) if found else ""
    if build:
        stale = [n for n, sha in sorted(pin["libs"].items())
                 if not (Path(build) / n).is_file() or sha256_file(Path(build) / n) != sha]
        if stale:
            say(f"warning: the Mac's FFmpeg libraries in FFMPEG_BUILD_DIR are not the pinned ones ({', '.join(stale)}), "
                "so a --local build links other FFmpeg code than the cloud")


# --- the command -------------------------------------------------------------------------------------------

def reuse(state, pin, head, cert):
    directory = STATE_DIR / state["request_id"]
    if not directory.is_dir():
        return None
    problems = verify(directory, state, pin, state.get("run_id"), head, cert)
    if problems:
        say(f"warning: the earlier download {directory} no longer verifies ({problems[0]}); building again")
        mark(state, "stale", "the download no longer verifies")
        return None
    os.utime(directory)
    say(f"== reusing the verified download of run {state.get('run_id')} ({directory})")
    return directory


def finish(state, directory, pin):
    ffmpeg = ensure_ffmpeg(pin)
    warn_drift(directory, pin)
    name = apk_name(state["inputs"]["variant"])
    print(f"commit={state['commit']}")
    print(f"run_id={state['run_id']}")
    print(f"run_url={state.get('run_url') or run_url(state['run_id'])}")
    print(f"apk={directory / name}")
    print(f"stamp={directory / STAMP}")
    print(f"ffmpeg={ffmpeg}")
    return 0


def cmd_build(args):
    inputs = build_inputs(args)
    budget = number_env("NUVIO_CLOUD_WAIT_MIN", 100.0)
    poll = number_env("NUVIO_CLOUD_POLL_SECONDS", 30.0)
    head, pin, cert = preflight()
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    clean_partials()
    states = matching_states(head, inputs)
    if not args.rebuild:
        for state in states:
            if state.get("status") == "verified":
                directory = reuse(state, pin, head, cert)
                if directory:
                    return finish(state, directory, pin)
    state = attachable(states, args.rebuild) or dispatch(head, inputs, poll)
    run = wait_for(state, budget, poll)
    run_id = state["run_id"]
    if run.get("conclusion") != "success":
        mark(state, "failed", f"run {run_id} ended {run.get('conclusion')}")
        raise CloudError(f"the cloud build ended {run.get('conclusion') or 'without a conclusion'}: {run_url(run_id)} "
                         f"(gh run view {run_id} -R {BUILD_REPO} --log-failed)")
    now = git("rev-parse", "HEAD")
    if now != state["commit"]:
        raise CloudError(f"HEAD moved to {now[:12]} during the build of {state['commit'][:12]}; not downloading. "
                         "Its artifact stays for a day: check that commit out and run the same command to fetch it")
    artifact = artifact_of(run_id, state["request_id"])
    if artifact is None:
        mark(state, "expired", f"run {run_id} has no artifact")
        raise CloudError(f"run {run_id} succeeded but has no artifact shield-apk-{state['request_id']}; "
                         "run the same command again to build anew")
    directory = download(state, run_id, pin, cert)
    mark(state, "verified", "")
    say(f"== verified {directory / apk_name(state['inputs']['variant'])} of run {run_id}")
    delete_artifact(artifact)
    prune(directory)
    return finish(state, directory, pin)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                     formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="build HEAD in the cloud (or reuse or attach), download and verify it")
    build.add_argument("--variant", choices=("Debug", "Benchmark"), required=True)
    build.add_argument("--suffix", required=True, help="applicationId suffix, e.g. .shield")
    build.add_argument("--debuggable", action="store_true", help="pass -Pdebuggable=true (Debug only)")
    build.add_argument("--version-code", required=True)
    build.add_argument("--version-name", required=True)
    build.add_argument("--rebuild", action="store_true",
                       help="dispatch anew instead of reusing a download or a finished run (a running one is still attached)")
    args = parser.parse_args(argv)
    os.chdir(ROOT)
    try:
        return cmd_build(args)
    except CloudError as error:
        print(f"shield_cloud_build: {error}", file=sys.stderr)
        return error.code
    except KeyboardInterrupt:
        print("shield_cloud_build: interrupted; a dispatched run keeps going: run the same command again to attach "
              "to it", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
