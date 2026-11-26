"""Set up the private build repo tinof/NuvioTV-Builds for cloud builds of the Shield APK.

    python3 -I scripts/shield_cloud_setup.py {repo|secrets|ffmpeg|workflow|check} [--skip-deps-check]

Run the steps in this order: repo, secrets, ffmpeg (then commit the pin it writes), workflow, check.
Each step is idempotent: a second run writes nothing. Every gh call names the build repo, and every
write first checks that the repo is PRIVATE. Secret values reach gh only on stdin, never in argv, and
are never printed.

- repo      create the repo, private, with a README (a release needs a commit); then set the default
            GITHUB_TOKEN to read, allow GitHub-owned actions pinned to a SHA only, and keep artifacts
            and logs one day.
- secrets   LOCAL_PROPERTIES_BASE64 (local.properties without sdk.dir, FFMPEG_*, USE_LOCAL_FFMPEG_DECODER,
            DOVI_LIBDOVI_* and NUVIO_RELEASE_*, the rule of scripts/cloud-build/ci_build.sh) and
            DEBUG_KEYSTORE_BASE64 (NUVIO_DEBUG_STORE_FILE, else ~/.config/nuvio/nuvio-debug.keystore).
            GitHub never returns a secret, so .local/cloud-setup/state.json keeps the sha256 of
            each value set and the updatedAt GitHub then listed; either changing sets it again.
- ffmpeg    pack the static libraries of FFMPEG_BUILD_DIR (4 ABIs) and the headers of FFMPEG_SOURCE_DIR
            (libavcodec, libavutil, libswresample, config.h, config_components.h, the licences) into a
            deterministic tar; check that every FFmpeg header the newest local build of
            ffmpeg-decoder-downmix read (ninja -t deps) is in it; upload it as a new release
            ffmpeg-n<version>-<n> only when no release has this sha (an asset is never replaced);
            write scripts/cloud-build/ffmpeg-asset.json. The environment wins over local.properties.
- workflow  push HEAD:scripts/cloud-build/shield-build.yml to .github/workflows/shield-build.yml when
            the blob differs (commit an edit first).
- check     read-only: report every difference from the state above; exit 1 when there is one.

gh needs a token with the repo and workflow scopes (classic; `gh auth refresh -s workflow`), or a
fine-grained one with Administration, Contents, Secrets and Workflows write on the build repo.
"""

import argparse
import base64
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

BUILD_REPO = "tinof/NuvioTV-Builds"
ROOT = Path(__file__).resolve().parents[1]
PIN = Path("scripts/cloud-build/ffmpeg-asset.json")
WORKFLOW_SOURCE = "scripts/cloud-build/shield-build.yml"
WORKFLOW_TARGET = ".github/workflows/shield-build.yml"
STATE = Path(".local/cloud-setup/state.json")
ASSET = "nuvio-ffmpeg.tar"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
STATIC_LIBS = ("libavcodec.a", "libavutil.a", "libswresample.a")
HEADER_DIRS = ("libavcodec", "libavutil", "libswresample")
HEADER_FILES = ("config.h", "config_components.h")
LICENCES = ("COPYING.GPLv2", "COPYING.GPLv3", "COPYING.LGPLv2.1", "COPYING.LGPLv3", "LICENSE.md", "RELEASE")
SECRET_NAMES = ("LOCAL_PROPERTIES_BASE64", "DEBUG_KEYSTORE_BASE64")
# Applied in this order: selected-actions needs allowed_actions=selected first.
SETTINGS = (
    ("/actions/permissions", {"enabled": True, "allowed_actions": "selected", "sha_pinning_required": True}),
    # patterns_allowed applies to public repos only, so it is not sent.
    ("/actions/permissions/selected-actions", {"github_owned_allowed": True, "verified_allowed": False}),
    ("/actions/permissions/workflow", {"default_workflow_permissions": "read", "can_approve_pull_request_reviews": False}),
    ("/actions/permissions/artifact-and-log-retention", {"days": 1}),
)
STRIPPED = re.compile(r"sdk\.dir|FFMPEG_.*|USE_LOCAL_FFMPEG_DECODER|DOVI_LIBDOVI_.*|NUVIO_RELEASE_.*")


class SetupError(Exception):
    pass


# --- gh ---------------------------------------------------------------------------------------------

def gh(*args, stdin=None):
    """One gh call. It must name the build repo; stdin carries any secret."""
    if not any(BUILD_REPO in arg for arg in args):
        raise SetupError(f"refusing a gh call that does not name {BUILD_REPO}: gh {' '.join(args)}")
    if stdin is None:
        return subprocess.run(["gh", *args], stdin=subprocess.DEVNULL, capture_output=True)
    return subprocess.run(["gh", *args], input=stdin, capture_output=True)


def gh_ok(*args, stdin=None):
    result = gh(*args, stdin=stdin)
    if result.returncode:
        raise SetupError(f"gh {' '.join(args[:3])} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout.decode()


def api(path, method="GET", body=None, missing_ok=False):
    args = ["api", "-X", method, f"repos/{BUILD_REPO}{path}"]
    stdin = None
    if body is not None:
        args += ["--input", "-"]
        stdin = json.dumps(body).encode()
    result = gh(*args, stdin=stdin)
    if result.returncode:
        if missing_ok and b"HTTP 404" in result.stderr:
            return None
        raise SetupError(f"gh api {method} {path} failed: {result.stderr.decode(errors='replace').strip()}")
    return json.loads(result.stdout) if result.stdout.strip() else {}


def repo_info():
    result = gh("repo", "view", BUILD_REPO, "--json", "isPrivate,visibility,isEmpty,defaultBranchRef")
    if result.returncode:
        if b"Could not resolve to a Repository" in result.stderr:
            return None
        raise SetupError(f"gh repo view failed: {result.stderr.decode(errors='replace').strip()}")
    return json.loads(result.stdout)


def assert_private():
    info = repo_info()
    if info is None:
        raise SetupError(f"{BUILD_REPO} does not exist; run `python3 -I scripts/shield_cloud_setup.py repo` first")
    if info.get("isPrivate") is not True or info.get("visibility") != "PRIVATE":
        raise SetupError(f"{BUILD_REPO} is not private (visibility {info.get('visibility')}); refusing to write to it")
    return info


def write(*args, stdin=None):
    """A gh call that changes the build repo, right after a check that it is private."""
    assert_private()
    return gh_ok(*args, stdin=stdin)


def write_api(path, method, body):
    assert_private()
    return api(path, method, body)


# --- helpers ----------------------------------------------------------------------------------------

def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, check=True).stdout


def filter_properties(text):
    """local.properties for the cloud: no comments, blank lines, Mac paths or release keys.

    The rule of filter_properties in scripts/cloud-build/ci_build.sh, which a test holds to this one."""
    kept = []
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    for line in lines:
        if line.endswith("\r"):
            line = line[:-1]
        if re.match(r"[ \t\n\r\f\v]*([#!]|$)", line):
            continue
        if line.endswith("\\"):
            raise SetupError("a line continuation in local.properties is not supported")
        key = re.match(r"[ \t\n\r\f\v]*([^=: \t\n\r\f\v]*)", line).group(1)
        if STRIPPED.fullmatch(key):
            continue
        kept.append(line + "\n")
    return "".join(kept)


def property_values(text):
    found = (re.match(r"\s*([^=:\s]+)\s*[=:]?\s*(.*?)\s*$", line) for line in text.splitlines())
    return {m.group(1): m.group(2) for m in found if m}


def local_properties():
    path = ROOT / "local.properties"
    return path.read_text() if path.exists() else ""


def ffmpeg_dirs():
    """FFMPEG_SOURCE_DIR and FFMPEG_BUILD_DIR as the build reads them: the environment wins."""
    props = property_values(local_properties())
    dirs = []
    for name in ("FFMPEG_SOURCE_DIR", "FFMPEG_BUILD_DIR"):
        value = os.environ[name] if name in os.environ else props.get(name, "")
        if not value or not Path(value).is_dir():
            raise SetupError(f"{name} (environment or local.properties) is not a directory")
        dirs.append(Path(value))
    return dirs


def load_state():
    path = ROOT / STATE
    return json.loads(path.read_text()) if path.exists() else {}


def save_state(state):
    path = ROOT / STATE
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n")


def secret_values():
    """{name: base64 value} of the two secrets, from the local files."""
    filtered = filter_properties(local_properties())
    if not property_values(filtered).get("NUVIO_SUPABASE_URL"):
        raise SetupError("NUVIO_SUPABASE_URL is not set in local.properties; the cloud APK would not log in")
    store = os.environ.get("NUVIO_DEBUG_STORE_FILE") or str(Path.home() / ".config/nuvio/nuvio-debug.keystore")
    store_path = Path(store)
    if not store_path.is_file() or store_path.stat().st_size == 0:
        raise SetupError("no CI debug keystore (NUVIO_DEBUG_STORE_FILE or ~/.config/nuvio/nuvio-debug.keystore); "
                         "the cloud makes no throwaway key")
    return {"LOCAL_PROPERTIES_BASE64": base64.b64encode(filtered.encode()).decode(),
            "DEBUG_KEYSTORE_BASE64": base64.b64encode(store_path.read_bytes()).decode()}


def listed_secrets():
    out = gh_ok("secret", "list", "-R", BUILD_REPO, "--json", "name,updatedAt")
    return {s["name"]: s["updatedAt"] for s in json.loads(out or "[]")}


# --- the FFmpeg pack ----------------------------------------------------------------------------------

def pack_members(source, build):
    """[(name in the tar, file)], sorted. Headers and licences only from the source tree, no .git."""
    members = {}
    for top in HEADER_DIRS:
        base = source / top
        if not base.is_dir():
            raise SetupError(f"FFMPEG_SOURCE_DIR has no {top}/")
        for path in base.rglob("*.h"):
            if path.is_file() and ".git" not in path.relative_to(source).parts:
                members[f"headers/{path.relative_to(source).as_posix()}"] = path
    for name in HEADER_FILES + LICENCES:
        path = source / name
        if not path.is_file():
            raise SetupError(f"FFMPEG_SOURCE_DIR has no {name}")
        members[f"headers/{name}"] = path
    for abi in ABIS:
        for lib in STATIC_LIBS:
            path = build / abi / lib
            if not path.is_file():
                raise SetupError(f"FFMPEG_BUILD_DIR has no {abi}/{lib}; the module builds all four ABIs")
            members[f"static-libs/{abi}/{lib}"] = path
    return sorted(members.items())


def pack(members, target):
    """A tar that depends only on the names and bytes: fixed order, times, owners and modes."""
    with tarfile.open(target, "w", format=tarfile.GNU_FORMAT) as tar:
        for name, path in members:
            data = path.read_bytes()
            info = tarfile.TarInfo(name)
            info.size, info.mtime, info.mode = len(data), 0, 0o644
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            tar.addfile(info, io.BytesIO(data))


def find_ninja():
    if os.environ.get("NINJA"):
        return os.environ["NINJA"]
    sdk = Path(os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk")
    found = sorted(sdk.glob("cmake/*/bin/ninja"), key=lambda p: [int(x) for x in re.findall(r"\d+", p.parts[-3])])
    if found:
        return str(found[-1])
    if shutil.which("ninja"):
        return "ninja"
    raise SetupError("no ninja (the SDK's cmake/<version>/bin/ninja, or NINJA) for the deps check")


def check_deps(source, names):
    """Every FFmpeg header that the newest local build of the module read is in the pack."""
    cxx = ROOT / "ffmpeg-decoder-downmix/.cxx"
    deps = list(cxx.glob("*/*/*/.ninja_deps")) if cxx.is_dir() else []
    if not deps:
        raise SetupError("ffmpeg-decoder-downmix/.cxx has no local build to check the headers against; run one local "
                         "build with USE_LOCAL_FFMPEG_DECODER=true first, or pass --skip-deps-check")
    newest = max(deps, key=lambda p: p.stat().st_mtime).parent.parent
    ninja, root, used = find_ninja(), source.resolve(), set()
    for abi_dir in sorted(p.parent for p in newest.glob("*/.ninja_deps")):
        out = subprocess.run([ninja, "-C", str(abi_dir), "-t", "deps"], capture_output=True, text=True, check=True).stdout
        for line in out.splitlines():
            if not line.startswith("    "):
                continue
            try:
                used.add(Path(line.strip()).resolve().relative_to(root).as_posix())
            except ValueError:
                pass
    if not used:
        raise SetupError(f"the newest build in {newest.relative_to(ROOT)} read no header of FFMPEG_SOURCE_DIR; "
                         "run a local build with the current FFmpeg first, or pass --skip-deps-check")
    missing = sorted(h for h in used if f"headers/{h}" not in names)
    if missing:
        raise SetupError(f"the pack lacks headers the build read: {', '.join(missing)}")
    print(f"deps check: the {len(used)} FFmpeg headers of the newest build ({newest.relative_to(ROOT)}) are in the pack")


def ffmpeg_version(source):
    release = (source / "RELEASE").read_text().strip()
    if not re.fullmatch(r"[0-9]+(\.[0-9]+)*", release):
        raise SetupError("FFMPEG_SOURCE_DIR/RELEASE holds no version number")
    return f"n{release}"


def release_has(tag, digest, size):
    release = api(f"/releases/tags/{tag}", missing_ok=True)
    if not release:
        return False
    for asset in release.get("assets", []):
        if asset.get("name") == ASSET and asset.get("state") == "uploaded" and asset.get("size") == size:
            return asset.get("digest") in (None, f"sha256:{digest}")
    return False


# --- the steps ----------------------------------------------------------------------------------------

def setting_differs(path, want):
    current = api(path)
    # A field GitHub does not return is taken as set, or every run would write it again.
    return any(current.get(key, value) != value for key, value in want.items())


def settings_drift():
    """The settings paths that differ, read-only. selected-actions is only readable once actions are selected."""
    drift = []
    for path, want in SETTINGS:
        if path.endswith("/selected-actions") and "/actions/permissions" in drift:
            drift.append(path)
        elif setting_differs(path, want):
            drift.append(path)
    return drift


def cmd_repo(args):
    info = repo_info()
    if info is None:
        print(f"creating {BUILD_REPO}, private")
        gh_ok("repo", "create", BUILD_REPO, "--private", "--add-readme", "--disable-wiki", "--disable-issues",
              "--description", "Private cloud builds of the NuvioTV fork's Shield APK")
    info = assert_private()
    if info.get("isEmpty"):
        print("adding a README: a release needs a commit")
        readme = "Private cloud builds of the tinof/NuvioTV-Fork Shield APK. See scripts/cloud-build/README.md there.\n"
        write_api("/contents/README.md", "PUT", {"message": "Add a README", "content": base64.b64encode(readme.encode()).decode()})
        info = assert_private()
    branch = (info.get("defaultBranchRef") or {}).get("name")
    if branch != "main":
        raise SetupError(f"the default branch of {BUILD_REPO} is {branch!r}; the builds are dispatched on main")
    for path, want in SETTINGS:  # one at a time: selected-actions needs the setting before it
        if setting_differs(path, want):
            print(f"setting {path}")
            write_api(path, "PUT", want)
    print(f"{BUILD_REPO}: private, token read-only, GitHub-owned SHA-pinned actions only, artifacts and logs 1 day")
    return 0


def cmd_secrets(args):
    assert_private()
    values = secret_values()
    if (ROOT / "local.dev.properties").exists():
        print("warning: local.dev.properties exists; the cloud build does not get it", file=sys.stderr)
    state = load_state()
    recorded = state.setdefault("secrets", {})
    listed = listed_secrets()
    for name, value in values.items():
        digest = sha256_bytes(value.encode())
        record = recorded.get(name, {})
        if record.get("sha256") == digest and name in listed and listed[name] == record.get("updated_at"):
            print(f"{name}: unchanged")
            continue
        print(f"{name}: setting it")
        write("secret", "set", name, "-R", BUILD_REPO, stdin=value.encode())
        recorded[name] = {"sha256": digest, "updated_at": listed_secrets().get(name)}
        save_state(state)
    print(f"secrets in {BUILD_REPO}: {', '.join(sorted(listed_secrets()))}")
    return 0


def cmd_ffmpeg(args):
    assert_private()
    source, build = ffmpeg_dirs()
    version = ffmpeg_version(source)
    members = pack_members(source, build)
    if args.skip_deps_check:
        print("warning: the deps check is skipped; a header the build needs may be missing from the pack", file=sys.stderr)
    else:
        check_deps(source, {name for name, _ in members})
    with tempfile.TemporaryDirectory() as tmp:
        target = Path(tmp) / ASSET
        pack(members, target)
        digest, size = sha256_file(target), target.stat().st_size
        print(f"pack: {len(members)} files, {size} bytes, sha256 {digest}")
        pin_path = ROOT / PIN
        pin = json.loads(pin_path.read_text()) if pin_path.exists() else {}
        tag = pin.get("tag") if pin.get("sha256") == digest and release_has(pin.get("tag"), digest, size) else None
        numbers = []
        if not tag:
            out = gh_ok("release", "list", "-R", BUILD_REPO, "--json", "tagName", "-L", "200")
            tags = [r["tagName"] for r in json.loads(out or "[]")]
            numbers = sorted(int(m.group(1)) for m in (re.fullmatch(rf"ffmpeg-{re.escape(version)}-(\d+)", t) for t in tags) if m)
            # An earlier run that stopped before it wrote the pin left a release with this sha: use it.
            tag = next((f"ffmpeg-{version}-{n}" for n in numbers if release_has(f"ffmpeg-{version}-{n}", digest, size)), None)
        if not tag:
            tag = f"ffmpeg-{version}-{(numbers[-1] if numbers else 0) + 1}"
            print(f"uploading release {tag}")
            write("release", "create", tag, str(target), "-R", BUILD_REPO, "--title", tag, "--latest=false",
                  "--notes", f"FFmpeg {version} static libraries ({', '.join(ABIS)}) and headers for the Shield cloud "
                             f"build. sha256 {digest}. Never replace this asset; a new pack gets a new tag.")
            if not release_has(tag, digest, size):
                raise SetupError(f"release {tag} does not hold {ASSET} with sha256 {digest} after the upload")
    libs = {f"arm64-v8a/{lib}": sha256_file(build / "arm64-v8a" / lib) for lib in STATIC_LIBS}
    new_pin = {"tag": tag, "asset": ASSET, "sha256": digest, "size": size, "ffmpeg": version, "members": len(members),
               "libs": libs}
    text = json.dumps(new_pin, indent=2, sort_keys=True) + "\n"
    if not pin_path.exists() or pin_path.read_text() != text:
        pin_path.write_text(text)
        print(f"wrote {PIN} for {tag}: commit it, so a build of that commit uses this asset")
    else:
        print(f"{PIN}: unchanged ({tag})")
    return 0


def cmd_workflow(args):
    assert_private()
    blob = git("rev-parse", f"HEAD:{WORKFLOW_SOURCE}").decode().strip()
    content = git("show", f"HEAD:{WORKFLOW_SOURCE}")
    if subprocess.run(["git", "diff", "--quiet", "HEAD", "--", WORKFLOW_SOURCE], cwd=ROOT).returncode:
        print(f"warning: {WORKFLOW_SOURCE} has uncommitted changes; pushing HEAD's version", file=sys.stderr)
    remote = api(f"/contents/{WORKFLOW_TARGET}", missing_ok=True)
    if remote and remote.get("sha") == blob:
        print(f"{WORKFLOW_TARGET}: unchanged (blob {blob[:12]})")
        return 0
    head = git("rev-parse", "HEAD").decode().strip()
    body = {"message": f"Shield build workflow of tinof/NuvioTV-Fork {head[:12]}",
            "content": base64.b64encode(content).decode(), "branch": "main"}
    if remote:
        body["sha"] = remote["sha"]
    write_api(f"/contents/{WORKFLOW_TARGET}", "PUT", body)
    print(f"{WORKFLOW_TARGET}: pushed blob {blob[:12]} of {head[:12]}")
    return 0


def cmd_check(args):
    problems = []
    info = repo_info()
    if info is None:
        print(f"FAIL: {BUILD_REPO} does not exist (run repo)")
        return 1
    if info.get("isPrivate") is not True or info.get("visibility") != "PRIVATE":
        print(f"FAIL: {BUILD_REPO} is not private")
        return 1
    if (info.get("defaultBranchRef") or {}).get("name") != "main":
        problems.append("the default branch is not main")
    problems += [f"{path} differs (run repo)" for path in settings_drift()]
    listed, recorded = listed_secrets(), load_state().get("secrets", {})
    try:
        values = secret_values()
    except SetupError as error:
        values = {}
        problems.append(str(error))
    for name in SECRET_NAMES:
        record = recorded.get(name, {})
        if name not in listed:
            problems.append(f"secret {name} is not set (run secrets)")
        elif name in values and (record.get("sha256") != sha256_bytes(values[name].encode())
                                 or record.get("updated_at") != listed[name]):
            problems.append(f"secret {name} may not hold the local value (run secrets)")
    committed = subprocess.run(["git", "show", f"HEAD:{PIN.as_posix()}"], cwd=ROOT, capture_output=True)
    if committed.returncode:
        problems.append(f"HEAD has no {PIN} (run ffmpeg, then commit the pin)")
    else:
        pin = json.loads(committed.stdout)
        if not release_has(pin["tag"], pin["sha256"], pin.get("size")):
            problems.append(f"release {pin['tag']} does not hold {pin['asset']} with the pinned sha256")
        try:
            _, build = ffmpeg_dirs()
            stale = [n for n, sha in pin["libs"].items() if not (build / n).is_file() or sha256_file(build / n) != sha]
            if stale:
                print(f"warning: the Mac's FFmpeg libraries differ from the pin: {', '.join(stale)}", file=sys.stderr)
        except SetupError as error:
            print(f"warning: {error}", file=sys.stderr)
    blob = git("rev-parse", f"HEAD:{WORKFLOW_SOURCE}").decode().strip()
    remote = api(f"/contents/{WORKFLOW_TARGET}", missing_ok=True)
    if not remote or remote.get("sha") != blob:
        problems.append(f"{WORKFLOW_TARGET} is not HEAD's {WORKFLOW_SOURCE} (run workflow)")
    for problem in problems:
        print(f"FAIL: {problem}")
    if not problems:
        print(f"{BUILD_REPO}: ready (private, settings, secrets, FFmpeg asset, workflow)")
    return 1 if problems else 0


COMMANDS = {"repo": cmd_repo, "secrets": cmd_secrets, "ffmpeg": cmd_ffmpeg, "workflow": cmd_workflow, "check": cmd_check}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                     formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    parser.add_argument("command", choices=COMMANDS)
    parser.add_argument("--skip-deps-check", action="store_true", help="ffmpeg: skip the ninja deps check, with a warning")
    args = parser.parse_args(argv)
    os.chdir(ROOT)
    try:
        return COMMANDS[args.command](args)
    except SetupError as error:
        print(f"shield_cloud_setup: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
