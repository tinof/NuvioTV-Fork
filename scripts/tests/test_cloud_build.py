"""scripts/cloud-build: static guards on the build repo's workflow, and ci_build.sh in a throwaway tree.

ci_build.sh runs over fake tools only: PATH is the fake bin plus /usr/bin:/bin, HOME and RUNNER_TEMP are
temporary, and every property value is planted.
"""

import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

# A `git rebase --exec` or a hook exports these, and the fixture's git would then work on that repository.
for variable in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_OBJECT_DIRECTORY",
                 "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_PREFIX", "GIT_NAMESPACE"):
    os.environ.pop(variable, None)

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent
CLOUD = SCRIPTS / "cloud-build"
WORKFLOW = CLOUD / "shield-build.yml"
CI_BUILD = CLOUD / "ci_build.sh"
SKILL = ROOT / ".claude/skills/upstream-sync"
BUILD_REPO = "tinof/NuvioTV-Builds"


def steps(text):
    """The build job's steps, each as its text, split at the `      - ` list items."""
    lines = text.splitlines()
    start = lines.index("    steps:")
    found = []
    for line in lines[start + 1:]:
        if line.startswith("      - "):
            found.append([line])
        elif found:
            found[-1].append(line)
    return ["\n".join(step) for step in found]


def run_blocks(text):
    """The value of every `run:` key, with the lines of its block."""
    lines, blocks = text.splitlines(), []
    for i, line in enumerate(lines):
        match = re.match(r"^(\s*)(?:- )?run: ?(.*)$", line)
        if not match:
            continue
        indent, body = len(match.group(1)), [match.group(2)]
        for following in lines[i + 1:]:
            if following.strip() and len(following) - len(following.lstrip()) <= indent:
                break
            body.append(following)
        blocks.append("\n".join(body))
    return blocks


def input_default(text, name):
    block = re.search(rf"^      {name}:\n((?:        .*\n|          .*\n)+)", text, re.M).group(1)
    return re.search(r"^\s+default: (.*)$", block, re.M).group(1).strip().strip('"')


class WorkflowStaticTest(unittest.TestCase):
    TEXT = WORKFLOW.read_text()

    def step(self, name):
        return next(s for s in steps(self.TEXT) if f"name: {name}" in s.splitlines()[0])

    def test_only_a_manual_dispatch_starts_it(self):
        block = re.search(r"^on:\n((?:  .*\n|\n)+?)^\S", self.TEXT, re.M).group(1)
        events = [line.strip() for line in block.splitlines() if re.match(r"^  \S", line)]
        self.assertEqual(["workflow_dispatch:"], events)

    def test_the_job_runs_only_in_the_private_build_repo(self):
        self.assertIn(f"    if: github.repository == '{BUILD_REPO}' && github.event.repository.private\n", self.TEXT)
        self.assertEqual(1, self.TEXT.count("github.repository =="))

    def test_the_artifact_lives_one_day_uncompressed(self):
        upload = self.step("Upload the APK for one day")
        self.assertEqual(["1"], re.findall(r"retention-days: (\S+)", self.TEXT))
        self.assertIn("compression-level: 0", upload)
        self.assertIn("if-no-files-found: error", upload)
        self.assertIn("name: shield-apk-${{ inputs.request_id }}", upload)

    def test_every_action_is_github_owned_and_pinned_to_a_sha(self):
        uses = re.findall(r"^\s*(?:- )?uses: (.*)$", self.TEXT, re.M)
        self.assertTrue(uses)
        for value in uses:
            self.assertRegex(value, r"^actions/[a-z-]+(/[a-z-]+)?@[0-9a-f]{40} # v\d+\.\d+\.\d+$")

    def test_no_step_sends_a_token_to_the_fork(self):
        self.assertFalse(re.search(r"uses: actions/checkout", self.TEXT))
        fetch = [s for s in steps(self.TEXT) if "NuvioTV-Fork.git" in s]
        self.assertEqual(1, len(fetch))
        for marker in ("secrets.", "github.token", "GH_TOKEN", "GITHUB_TOKEN", "extraheader", "x-access-token", "@github.com"):
            self.assertNotIn(marker, fetch[0])
        self.assertIn("credential.helper= fetch", fetch[0])
        self.assertIn('[ "$head" = "$COMMIT" ]', fetch[0])

    def test_one_step_sees_the_secrets_and_one_the_token(self):
        with_secrets = [s for s in steps(self.TEXT) if "secrets." in s]
        self.assertEqual(1, len(with_secrets))
        self.assertNotIn("ci_build.sh", with_secrets[0])
        with_token = [s for s in steps(self.TEXT) if "github.token" in s]
        self.assertEqual(1, len(with_token))
        self.assertIn("run: scripts/cloud-build/ci_build.sh", with_token[0])

    def test_inputs_reach_the_scripts_only_through_env(self):
        blocks = run_blocks(self.TEXT)
        self.assertGreater(len(blocks), 5)
        for block in blocks:
            self.assertNotIn("${{", block)

    def test_the_token_is_read_only(self):
        self.assertEqual(["contents: read"], re.findall(r"^permissions:\n  (.*)\n(?!  )", self.TEXT, re.M))
        self.assertEqual(1, self.TEXT.count("permissions:"))

    def test_a_run_is_never_cancelled_by_a_later_one(self):
        self.assertIn("cancel-in-progress: false", self.TEXT)
        self.assertIn("timeout-minutes: 90", self.TEXT)

    def test_the_input_defaults_are_the_local_scripts(self):
        local = (SCRIPTS / "shield_build_install.sh").read_text()
        self.assertEqual(re.search(r'^suffix="([^"]*)"', local, re.M).group(1), input_default(self.TEXT, "suffix"))
        self.assertEqual(re.search(r"NUVIO_VERSION_CODE:-([0-9]+)", local).group(1), input_default(self.TEXT, "version_code"))
        self.assertEqual(re.search(r"NUVIO_VERSION_NAME:-([^}]+)", local).group(1), input_default(self.TEXT, "version_name"))
        self.assertEqual("Debug", input_default(self.TEXT, "variant"))
        self.assertEqual("false", input_default(self.TEXT, "debuggable"))

    def test_the_cargo_ndk_version_is_ci_builds(self):
        yaml_version = re.search(r"^  CARGO_NDK_VERSION: (\S+)$", self.TEXT, re.M).group(1)
        script_version = re.search(r"CARGO_NDK_VERSION:-([0-9.]+)", CI_BUILD.read_text()).group(1)
        self.assertEqual(script_version, yaml_version)
        self.assertIn("${{ env.CARGO_NDK_VERSION }}", self.step("Cache cargo-ndk"))

    def test_the_disk_cleanup_keeps_the_build_toolchains(self):
        cleanup = self.step("Free disk space when it is short")
        targets = re.search(r"sudo rm -rf (.*)$", cleanup, re.M).group(1).split()
        self.assertTrue(targets)
        for target in targets:
            for kept in ("android", "sdk", "java", "jdk", "temurin", "rust", "cargo", "gradle"):
                self.assertNotIn(kept, target.lower(), target)
            if "hostedtoolcache" in target.lower():
                self.assertTrue(target.rstrip('"').endswith("/CodeQL"), target)

    def test_the_caches_hold_downloads_only(self):
        paths = []
        for step in steps(self.TEXT):
            if "uses: actions/cache@" not in step:
                continue
            block = re.search(r"path: (\|\n(?:            .*\n?)+|.*)", step).group(1)
            paths += [p.strip() for p in block.splitlines() if p.strip() and p.strip() != "|"]
        self.assertEqual({"~/.cargo/registry/cache", "~/.cargo/git/db", "~/.local/cargo-ndk"}, set(paths))
        self.assertNotIn("target", self.TEXT.split("Cache the Cargo downloads")[1].split("Write the build secrets")[0])

    def test_the_public_repo_never_runs_it(self):
        self.assertNotIn(".github", WORKFLOW.relative_to(ROOT).parts)
        for path in (ROOT / ".github/workflows").iterdir():
            text = path.read_text()
            for marker in ("NuvioTV-Builds", "cloud-build", "ci_build.sh", "shield-build.yml"):
                self.assertNotIn(marker, text, path.name)


def elf(payload):
    header = bytearray(64)
    header[:4] = b"\x7fELF"
    header[4:6] = bytes((2, 1))
    struct.pack_into("<H", header, 18, 183)
    return bytes(header) + payload


# One script behind every fake tool; bin/<name> runs it with the tool's name first. Each call is logged.
FAKE_TOOLS = r'''
import json, os, shutil, struct, sys, zipfile
from pathlib import Path

name, args = sys.argv[1], sys.argv[2:]
with open(os.environ["FAKE_LOG"], "a") as log:
    log.write(json.dumps([name] + args) + "\n")


def elf(payload):
    header = bytearray(64)
    header[:4] = b"\x7fELF"
    header[4:6] = bytes((2, 1))
    struct.pack_into("<H", header, 18, 183)
    return bytes(header) + payload


def tool(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("#!/bin/sh\n" + body)
    path.chmod(0o755)


if name == "gh":
    assert args[:2] == ["release", "download"], args
    tag, repo = args[2], args[args.index("-R") + 1]
    asset, target = args[args.index("-p") + 1], Path(args[args.index("-D") + 1])
    source = Path(os.environ["FAKE_RELEASES"]) / repo.replace("/", "__") / tag / asset
    if not source.exists():
        sys.exit(f"release {tag} of {repo} has no asset {asset}")
    shutil.copy(source, target / asset)
elif name == "cargo":
    if args[:2] == ["ndk", "--version"]:
        version = os.environ.get("FAKE_CARGO_NDK", "4.1.2")
        if not version:
            sys.exit(101)
        print(f"cargo-ndk {version}")
    elif args[:1] == ["install"]:
        pass
    elif args[:1] == ["ndk"]:
        lib = Path(args[args.index("-o") + 1]) / "arm64-v8a/libnuvio_rpu.so"
        lib.parent.mkdir(parents=True, exist_ok=True)
        lib.write_bytes(elf(b"Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeRenderShot nuvio_rpu 0.1.0"))
    else:
        sys.exit(f"fake cargo: {args}")
elif name == "rustup":
    pass
elif name == "rustc":
    print("rustc 1.93.0 (fake)")
elif name == "keytool":
    store = Path(args[args.index("-keystore") + 1])
    sys.exit(0 if store.read_bytes() == b"fake keystore" else 1)
elif name == "java":
    print('openjdk version "17.0.99" (fake)', file=sys.stderr)
elif name == "sdkmanager":
    if "--licenses" in args:
        sys.stdin.buffer.read(4)  # then close the pipe, as sdkmanager does
    home = Path(os.environ["ANDROID_HOME"])
    for package in args[args.index("--install") + 1:] if "--install" in args else []:
        kind, version = package.split(";")
        if kind == "ndk":
            ndk = home / "ndk" / version
            ndk.mkdir(parents=True)
            (ndk / "source.properties").write_text(f"Pkg.Revision = {version}\n")
            bin_dir = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
            tool(bin_dir / "llvm-readelf", 'echo "  LOAD 0x000000 0x0 0x0 0x001000 0x001000 R E 0x4000"\n')
            tool(bin_dir / "llvm-strip", 'cp "$4" "$3"\n')
        elif kind == "cmake":
            tool(home / "cmake" / version / "bin/cmake", "exit 0\n")
        else:
            sys.exit(f"fake sdkmanager: {package}")
elif name == "gradlew":
    Path("gradle-args.json").write_text(json.dumps(args))
    names = ("USE_LOCAL_FFMPEG_DECODER", "FFMPEG_SOURCE_DIR", "FFMPEG_BUILD_DIR", "NUVIO_DEBUG_STORE_FILE",
             "GH_TOKEN", "GITHUB_TOKEN")
    Path("gradle-env.json").write_text(json.dumps({n: os.environ.get(n) for n in names}))
    task = next(a for a in args if a.startswith(":app:assembleFull"))
    lower = task[len(":app:assembleFull"):].lower()
    apk = Path(f"app/build/outputs/apk/full/{lower}/app-full-arm64-v8a-{lower}.apk")
    apk.parent.mkdir(parents=True, exist_ok=True)
    rpu = Path("app/src/main/jniLibs/arm64-v8a/libnuvio_rpu.so")
    drop = os.environ.get("FAKE_APK_DROP", "")
    members = {
        "lib/arm64-v8a/libffmpegJNI.so": elf(b"module build: dcaxll writer"),
        "lib/arm64-v8a/libdovi_bridge.so": elf(b"dovi-bridge-libdovi-capi-0.2 dovi_convert_rpu_with_mode"),
        "lib/arm64-v8a/libass.so": elf(b"libass"),
        "lib/arm64-v8a/libasskt.so": elf(b"libasskt"),
    }
    if rpu.exists():
        members["lib/arm64-v8a/libnuvio_rpu.so"] = rpu.read_bytes()
    with zipfile.ZipFile(apk, "w") as z:
        z.writestr("classes.dex", b"dex")
        for member, data in members.items():
            if not drop or drop not in member:
                z.writestr(member, data)
    sys.exit(int(os.environ.get("FAKE_GRADLE_EXIT", "0")))
elif name == "aapt2":
    print("package: name='com.nuvio.tv.test.shield' versionCode='1580' versionName='1.1.0-nt.10.shield'")
elif name == "apksigner":
    print("Signer #1 certificate SHA-256 digest: c22ec9" + "0" * 58)
else:
    sys.exit(f"no fake for {name}")
'''

# Planted values only. A secret key's value is masked; a short value, a flag and a plain URL are not.
PROPERTIES = """# a comment that does not reach the build
sdk.dir=/Users/fake/Library/Android/sdk
NUVIO_SUPABASE_URL=https://plantedproject.supabase.example
NUVIO_SUPABASE_ANON_KEY=planted-anon-key-0123456789abcdef
TMDB_API_KEY=planted-tmdb-key-fedcba9876543210
TRAKT_CLIENT_ID=planted-trakt-client-id-aaaa
TRAKT_CLIENT_SECRET=planted-trakt-secret-bbbb
REPORTS_SIGNING_KEY = planted-signing-key-with%percent
PLAYBACK_REPORTS_BASE_URL=https://user:planted-pass@reports.example.invalid/
SUPPORT_URL=https://support.planted.example/help
SIMKL_APP_NAME=nuvio
SHORT_CLIENT_ID=abc1234
FLAG_TOKEN=false
DOVI_NATIVE_ENABLED=true
USE_LOCAL_FFMPEG_DECODER=false
FFMPEG_SOURCE_DIR=/Users/fake/planted-ffmpeg-headers
FFMPEG_BUILD_DIR=/Users/fake/planted-static-libs
DOVI_LIBDOVI_PREBUILT_ROOT=/Users/fake/planted-libdovi
NUVIO_RELEASE_STORE_FILE=/Users/fake/planted-release.jks
NUVIO_RELEASE_KEY_PASSWORD=planted-release-password

"""
MASKED = ("planted-anon-key-0123456789abcdef", "planted-tmdb-key-fedcba9876543210", "planted-trakt-client-id-aaaa",
          "planted-trakt-secret-bbbb", "planted-signing-key-with%percent",
          "https://user:planted-pass@reports.example.invalid/")
NEVER_MASKED = ("nuvio", "abc1234", "false", "true", "https://plantedproject.supabase.example",
                "https://support.planted.example/help")
STRIPPED = ("sdk.dir", "FFMPEG_SOURCE_DIR", "FFMPEG_BUILD_DIR", "USE_LOCAL_FFMPEG_DECODER",
            "DOVI_LIBDOVI_PREBUILT_ROOT", "NUVIO_RELEASE_STORE_FILE", "NUVIO_RELEASE_KEY_PASSWORD")
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
LIBS = ("libavcodec.a", "libavutil.a", "libswresample.a")
NDK = "29.9.1234567"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def run(*argv, cwd):
    subprocess.run(argv, cwd=cwd, check=True, capture_output=True)


class CiBuildTest(unittest.TestCase):
    """ci_build.sh in a throwaway tree that copies the real scripts it calls."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.tree = self.tmp / "tree"
        for path in ("scripts/cloud-build/ci_build.sh", "scripts/build_rpu_builder.sh", "scripts/verify_native_apk.py",
                     "scripts/shield_build_install.sh", ".claude/skills/upstream-sync/sync.json",
                     ".claude/skills/upstream-sync/scripts/sync.py", ".claude/skills/upstream-sync/scripts/sync_tools.py"):
            target = self.tree / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / path, target)
        self.write("scripts/stop_kotlin_lsp.sh", "#!/bin/sh\nexit 0\n", mode=0o755)
        self.write("app/build.gradle.kts", f'android {{\n    compileSdk = 36\n    ndkVersion = "{NDK}"\n}}\n')
        self.write("ffmpeg-decoder-downmix/build.gradle.kts", '        cmake {\n            version = "3.22.1"\n        }\n')
        self.write("ffmpeg-decoder-downmix/src/main/jni/ffmpeg_jni.cc", "// jni\n")
        self.write("native/rpu_builder/rust-toolchain.toml", '[toolchain]\nchannel = "1.93.0"\n')
        self.write("native/rpu_builder/src/lib.rs", "v1\n")
        aar = self.tree / "app/libs/lib-decoder-ffmpeg-release.aar"
        aar.parent.mkdir(parents=True)
        with zipfile.ZipFile(aar, "w") as z:
            z.writestr("jni/arm64-v8a/libffmpegJNI.so", elf(b"prebuilt AAR: dcaxll writer"))
        self.bin = self.tmp / "bin"
        self.sdk = self.tmp / "sdk"
        fake = self.tmp / "fake_tools.py"
        fake.write_text(FAKE_TOOLS)
        for name in ("gh", "cargo", "rustup", "rustc", "keytool", "java"):
            self.shim(self.bin / name, name)
        (self.bin / "python3").symlink_to(sys.executable)
        self.shim(self.sdk / "cmdline-tools/latest/bin/sdkmanager", "sdkmanager")
        self.shim(self.sdk / "build-tools/35.0.0/aapt2", "aapt2")
        self.shim(self.sdk / "build-tools/35.0.0/apksigner", "apksigner")
        self.shim(self.tree / "gradlew", "gradlew")
        self.releases = self.tmp / "releases"
        self.pin = self.make_asset()
        self.write("scripts/cloud-build/ffmpeg-asset.json", json.dumps(self.pin, indent=2) + "\n")
        run("git", "init", "-q", "-b", "main", cwd=self.tree)
        run("git", "config", "user.email", "t@example.com", cwd=self.tree)
        run("git", "config", "user.name", "t", cwd=self.tree)
        self.commit()
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.runner = self.tmp / "runner"
        self.runner.mkdir()
        (self.tmp / "tmpdir").mkdir()
        self.properties = self.runner / "cloud-local.properties"
        self.properties.write_text(PROPERTIES)
        self.keystore = self.runner / "nuvio-debug.keystore"
        self.keystore.write_bytes(b"fake keystore")
        self.out = self.runner / "shield-out"
        self.log = self.tmp / "calls.jsonl"
        self.env = {
            "PATH": f"{self.bin}:/usr/bin:/bin", "HOME": str(self.home), "TMPDIR": str(self.tmp / "tmpdir"),
            "LANG": "C", "GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "example/Builds-2", "RUNNER_TEMP": str(self.runner),
            "ANDROID_HOME": str(self.sdk), "GH_TOKEN": "fake-token-for-the-download", "GITHUB_TOKEN": "fake-token-2",
            "FAKE_LOG": str(self.log), "FAKE_RELEASES": str(self.releases),
            "CLOUD_REQUEST_ID": "req-0001-abcd", "CLOUD_VARIANT": "Debug", "CLOUD_SUFFIX": ".shield",
            "CLOUD_DEBUGGABLE": "false", "CLOUD_VERSION_CODE": "1580", "CLOUD_VERSION_NAME": "1.1.0-nt.10.shield",
            "CLOUD_PROPERTIES_FILE": str(self.properties), "CLOUD_KEYSTORE_FILE": str(self.keystore),
            "CLOUD_OUT": str(self.out),
        }

    def write(self, path, text, mode=None):
        target = self.tree / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        if mode:
            target.chmod(mode)

    def shim(self, path, name):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{self.tmp / "fake_tools.py"}" {name} "$@"\n')
        path.chmod(0o755)

    def commit(self):
        run("git", "add", "-A", cwd=self.tree)
        run("git", "commit", "-q", "-m", "c", cwd=self.tree)
        return subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.tree, capture_output=True, text=True).stdout.strip()

    def make_asset(self, repo="example/Builds-2", tag="ffmpeg-n7.0.2-1", name="nuvio-ffmpeg.tar"):
        members = {"headers/libavcodec/avcodec.h": b"/* avcodec */\n", "headers/libavutil/avconfig.h": b"/* gen */\n"}
        for abi in ABIS:
            for lib in LIBS:
                members[f"static-libs/{abi}/{lib}"] = f"{abi} {lib}".encode()
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w", format=tarfile.GNU_FORMAT) as tar:
            for member, data in sorted(members.items()):
                info = tarfile.TarInfo(member)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        target = self.releases / repo.replace("/", "__") / tag / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(buffer.getvalue())
        libs = {f"arm64-v8a/{lib}": sha256(members[f"static-libs/arm64-v8a/{lib}"]) for lib in LIBS}
        return {"tag": tag, "asset": name, "sha256": sha256(buffer.getvalue()), "libs": libs}

    def head(self):
        return subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.tree, capture_output=True, text=True).stdout.strip()

    def ci_build(self, **env):
        merged = {"CLOUD_COMMIT": self.head(), **self.env, **env}
        merged = {k: v for k, v in merged.items() if v is not None}
        return subprocess.run(["scripts/cloud-build/ci_build.sh"], cwd=self.tree, env=merged, capture_output=True,
                              text=True, timeout=120)

    def calls(self, name):
        if not self.log.exists():
            return []
        return [c[1:] for c in map(json.loads, self.log.read_text().splitlines()) if c[0] == name]

    def gradle_args(self):
        path = self.tree / "gradle-args.json"
        return json.loads(path.read_text()) if path.exists() else None

    def assert_ok(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def assert_no_build(self, result, code, message):
        self.assertEqual(code, result.returncode, result.stdout + result.stderr)
        self.assertIn(message, result.stderr)
        self.assertIsNone(self.gradle_args())
        self.assertFalse((self.out / "build-info.json").exists())

    # --- a good build ---

    def test_a_good_build_stages_the_apk_stamp_and_build_info(self):
        result = self.ci_build()
        self.assert_ok(result)
        apk = self.out / "app-full-arm64-v8a-debug.apk"
        info = json.loads((self.out / "build-info.json").read_text())
        self.assertEqual(self.head(), info["commit"])
        self.assertEqual("req-0001-abcd", info["request_id"])
        self.assertEqual({"variant": "Debug", "suffix": ".shield", "debuggable": False, "version_code": "1580",
                          "version_name": "1.1.0-nt.10.shield"}, info["inputs"])
        self.assertEqual(sha256(apk.read_bytes()), info["apk"]["sha256"])
        self.assertEqual("com.nuvio.tv.test.shield", info["package"])
        self.assertEqual("c22ec9" + "0" * 58, info["cert_sha256"])
        self.assertEqual({"tag": "ffmpeg-n7.0.2-1", "asset": "nuvio-ffmpeg.tar", "sha256": self.pin["sha256"],
                          "libs": self.pin["libs"]}, info["ffmpeg_asset"])
        self.assertEqual(NDK, info["toolchain"]["ndk"])
        self.assertIn("lib/arm64-v8a/libnuvio_rpu.so", info["native_libraries"])
        self.assertEqual(json.loads((self.out / "libnuvio_rpu.so.stamp").read_text()), info["rpu_stamp"])
        self.assertIn("NUVIO_SUPABASE_URL", info["property_keys"])
        self.assertIn("hlg-to-dv: libnuvio_rpu.so is the stamped build", result.stdout)
        self.assertIn("aac-to-dts: FFmpeg is the local module", result.stdout)

    def test_the_build_links_the_pinned_ffmpeg_with_the_ci_key_and_no_token(self):
        self.assert_ok(self.ci_build())
        env = json.loads((self.tree / "gradle-env.json").read_text())
        self.assertEqual({"USE_LOCAL_FFMPEG_DECODER": "true", "FFMPEG_SOURCE_DIR": str(self.runner / "ffmpeg/headers"),
                          "FFMPEG_BUILD_DIR": str(self.runner / "ffmpeg/static-libs"),
                          "NUVIO_DEBUG_STORE_FILE": str(self.keystore), "GH_TOKEN": None, "GITHUB_TOKEN": None}, env)

    def test_the_asset_comes_from_the_build_repo_the_runner_names(self):
        self.assert_ok(self.ci_build())
        [download] = self.calls("gh")
        self.assertEqual(["release", "download", "ffmpeg-n7.0.2-1", "-R", "example/Builds-2", "-p", "nuvio-ffmpeg.tar"],
                         download[:7])

    def test_the_ndk_and_cmake_come_from_the_build_files(self):
        self.assert_ok(self.ci_build())
        installs = [c for c in self.calls("sdkmanager") if "--install" in c]
        self.assertEqual([["--install", f"ndk;{NDK}", "cmake;3.22.1"]], installs)
        self.assertIn(["--licenses"], self.calls("sdkmanager"))
        # Installed once: a second build finds them.
        self.log.unlink()
        self.assert_ok(self.ci_build())
        self.assertEqual([], self.calls("sdkmanager"))

    def test_the_rust_toolchain_is_the_crates_and_cargo_ndk_is_pinned(self):
        self.assert_ok(self.ci_build())
        self.assertEqual([["toolchain", "install", "1.93.0", "--profile", "minimal", "--target", "aarch64-linux-android",
                           "--no-self-update"]], self.calls("rustup"))
        self.assertFalse([c for c in self.calls("cargo") if c[:1] == ["install"]])
        self.log.unlink()
        self.assert_ok(self.ci_build(FAKE_CARGO_NDK="4.0.0"))
        self.assertEqual([["install", "cargo-ndk", "--version", "4.1.2", "--locked", "--root", str(self.home / ".local/cargo-ndk")]],
                         [c for c in self.calls("cargo") if c[:1] == ["install"]])

    def test_gradle_gets_a_heap_for_8_gb_and_no_build_cache(self):
        self.assert_ok(self.ci_build())
        props = (self.home / ".gradle/gradle.properties").read_text()
        self.assertIn("org.gradle.jvmargs=-Xmx5g", props)
        self.assertIn("kotlin.compiler.execution.strategy=in-process\n", props)
        self.assertIn("org.gradle.caching=false\n", props)
        self.assertIn("org.gradle.workers.max=2\n", props)

    # --- local.properties: stripping and masking ---

    def test_the_macs_paths_and_the_release_keys_are_stripped(self):
        self.assert_ok(self.ci_build())
        written = (self.tree / "local.properties").read_text()
        for key in STRIPPED:
            self.assertNotRegex(written, rf"(?m)^\s*{re.escape(key)}\s*[=:]")
        self.assertNotIn("#", written)
        self.assertIn("NUVIO_SUPABASE_URL=https://plantedproject.supabase.example\n", written)
        self.assertIn("REPORTS_SIGNING_KEY = planted-signing-key-with%percent\n", written)
        self.assertEqual(12, len(written.splitlines()))

    def test_only_credentials_are_masked(self):
        result = self.ci_build()
        self.assert_ok(result)
        masks = [line[len("::add-mask::"):] for line in result.stdout.splitlines() if line.startswith("::add-mask::")]
        self.assertEqual(sorted(v.replace("%", "%25") for v in MASKED), sorted(masks))
        for value in NEVER_MASKED:
            self.assertNotIn(value, masks)

    def test_no_value_reaches_the_log_or_the_artifact_outside_a_mask(self):
        result = self.ci_build()
        self.assert_ok(result)
        planted = [v for v in re.findall(r"=\s*(.*)", PROPERTIES) if "planted" in v]
        self.assertGreater(len(planted), 10)
        for line in (result.stdout + result.stderr).splitlines():
            if not line.startswith("::add-mask::"):
                for value in planted:
                    self.assertNotIn(value, line)
        info = (self.out / "build-info.json").read_text()
        for value in planted:
            self.assertNotIn(value, info)
        self.assertIn("== properties with values: NUVIO_SUPABASE_URL NUVIO_SUPABASE_ANON_KEY", result.stdout)

    def test_a_missing_supabase_url_stops_before_the_download(self):
        self.properties.write_text("TMDB_API_KEY=planted-tmdb-key-fedcba9876543210\n")
        self.assert_no_build(self.ci_build(), 1, "NUVIO_SUPABASE_URL is not set")
        self.assertEqual([], self.calls("gh"))

    # --- the FFmpeg asset ---

    def test_a_missing_pin_names_the_setup_command(self):
        (self.tree / "scripts/cloud-build/ffmpeg-asset.json").unlink()
        self.commit()
        self.assert_no_build(self.ci_build(), 1, "python3 -I scripts/shield_cloud_setup.py ffmpeg")
        self.assertEqual([], self.calls("gh"))

    def test_an_asset_with_another_sha_stops_the_build(self):
        asset = self.releases / "example__Builds-2/ffmpeg-n7.0.2-1/nuvio-ffmpeg.tar"
        asset.write_bytes(asset.read_bytes() + b"\0" * 512)
        self.assert_no_build(self.ci_build(), 1, f"the pin says {self.pin['sha256']}")

    def test_a_library_with_another_sha_stops_the_build(self):
        pin = dict(self.pin, libs=dict(self.pin["libs"], **{"arm64-v8a/libavutil.a": "0" * 64}))
        self.write("scripts/cloud-build/ffmpeg-asset.json", json.dumps(pin))
        self.commit()
        self.assert_no_build(self.ci_build(), 1, "static-libs/arm64-v8a/libavutil.a has sha256")

    def test_a_malformed_pin_stops_the_build(self):
        self.write("scripts/cloud-build/ffmpeg-asset.json", json.dumps(dict(self.pin, tag="v1; rm -rf /")))
        self.commit()
        self.assert_no_build(self.ci_build(), 1, "is not a valid pin")
        self.assertEqual([], self.calls("gh"))

    # --- inputs ---

    def test_it_refuses_to_run_outside_github_actions(self):
        result = self.ci_build(GITHUB_ACTIONS=None)
        self.assert_no_build(result, 2, "runs only on GitHub Actions")
        self.assertFalse((self.home / ".gradle").exists())
        self.assertFalse((self.tree / "local.properties").exists())

    def test_an_empty_keystore_secret_fails(self):
        self.keystore.write_bytes(b"")
        self.assert_no_build(self.ci_build(), 2, "DEBUG_KEYSTORE_BASE64")
        self.assertEqual([], self.calls("gh"))

    def test_a_keystore_that_is_not_the_debug_key_fails(self):
        self.keystore.write_bytes(b"another keystore")
        self.assert_no_build(self.ci_build(), 1, "is not the debug keystore")

    def test_bad_inputs_stop_before_anything_runs(self):
        cases = {"CLOUD_COMMIT": "0" * 40, "CLOUD_REQUEST_ID": "Req_1", "CLOUD_VARIANT": "Release",
                 "CLOUD_SUFFIX": "shield", "CLOUD_DEBUGGABLE": "yes", "CLOUD_VERSION_CODE": "15x0",
                 "CLOUD_VERSION_NAME": "1.1 nt", "GITHUB_REPOSITORY": "no-slash"}
        for key, value in cases.items():
            with self.subTest(key):
                self.assert_no_build(self.ci_build(**{key: value}), 2, key)
        self.assert_no_build(self.ci_build(CLOUD_VARIANT="Benchmark", CLOUD_DEBUGGABLE="true"), 2, "never debuggable")
        self.assertEqual([], self.calls("gh"))

    # --- the checks after the build ---

    def test_an_apk_without_the_dts_writer_is_not_staged(self):
        result = self.ci_build(FAKE_APK_DROP="libffmpegJNI")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("protected-apk failed", result.stderr)
        self.assertFalse(self.out.exists())

    def test_the_native_check_only_warns(self):
        # verify_native_apk.py still requires libass.so, which current APKs no longer carry, so it is
        # report-only; protected-apk stays the gate.
        result = self.ci_build(FAKE_APK_DROP="libass")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("verify_native_apk.py failed", result.stderr)
        self.assertIn("::warning::", result.stderr)
        self.assertTrue(self.out.exists())

    def test_a_failed_gradle_build_fails(self):
        result = self.ci_build(FAKE_GRADLE_EXIT="1")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertFalse(self.out.exists())

    # --- the same Gradle arguments as the local script ---

    def local_args(self, variant, suffix, debuggable, code, name):
        argv = ["scripts/shield_build_install.sh", "--suffix", suffix, "--allow-empty-config"]
        argv += ["--benchmark"] if variant == "Benchmark" else []
        argv += ["--debuggable"] if debuggable else []
        env = {k: v for k, v in self.env.items() if not k.startswith(("CLOUD_", "GITHUB_"))}
        env.update(JAVA17_HOME=str(self.tmp / "jdk17"), NUVIO_DEBUG_STORE_FILE=str(self.keystore), FAKE_CARGO_NDK="",
                   NUVIO_VERSION_CODE=code, NUVIO_VERSION_NAME=name)
        subprocess.run(argv, cwd=self.tree, env=env, capture_output=True, text=True, timeout=120)
        return self.gradle_args()

    def test_the_gradle_arguments_are_the_local_scripts(self):
        cases = [("Debug", ".shield", False, "1580", "1.1.0-nt.10.shield"),
                 ("Benchmark", ".shield", False, "1580", "1.1.0-nt.10.shield"),
                 ("Debug", ".shielddbg", True, "1590", "1.1.0-nt.10.shield2")]
        for variant, suffix, debuggable, code, name in cases:
            with self.subTest(variant=variant, suffix=suffix):
                (self.tree / "gradle-args.json").unlink(missing_ok=True)
                local = self.local_args(variant, suffix, debuggable, code, name)
                self.assertIsNotNone(local)
                (self.tree / "gradle-args.json").unlink()
                shutil.rmtree(self.out, ignore_errors=True)
                result = self.ci_build(CLOUD_VARIANT=variant, CLOUD_SUFFIX=suffix, CLOUD_DEBUGGABLE=str(debuggable).lower(),
                                       CLOUD_VERSION_CODE=code, CLOUD_VERSION_NAME=name)
                self.assert_ok(result)
                self.assertEqual(["--no-daemon"] + local, self.gradle_args())


@unittest.skipUnless(shutil.which("shellcheck"), "shellcheck is not installed")
class CiBuildLintTest(unittest.TestCase):
    def test_shellcheck_is_clean(self):
        result = subprocess.run(["shellcheck", str(CI_BUILD)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout)


if __name__ == "__main__":
    unittest.main()
