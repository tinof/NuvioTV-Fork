"""shield_cloud_setup.py in a throwaway tree, over the fake gh in fake_gh.py. Every property value is planted."""

import base64
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
from pathlib import Path

for variable in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_OBJECT_DIRECTORY",
                 "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_PREFIX", "GIT_NAMESPACE"):
    os.environ.pop(variable, None)

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent
FAKE_GH = Path(__file__).with_name("fake_gh.py")
BUILD_REPO = "tinof/NuvioTV-Builds"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
LIBS = ("libavcodec.a", "libavutil.a", "libswresample.a")

PROPERTIES = """# planted-comment-value that must not leave the Mac
sdk.dir=/Users/fake/Library/Android/sdk
NUVIO_SUPABASE_URL=https://plantedproject.supabase.example
NUVIO_SUPABASE_ANON_KEY=planted-anon-key-0123456789abcdef
TRAKT_CLIENT_SECRET = planted-trakt-secret-bbbb\r
  ! a bang comment
SIMKL_APP_NAME nuvio
USE_LOCAL_FFMPEG_DECODER=true
DOVI_LIBDOVI_STATIC_LIB=/Users/fake/planted-libdovi.a
NUVIO_RELEASE_KEY_PASSWORD=planted-release-password
NUVIO_RELEASE_STORE_FILE:/Users/fake/planted-release.jks
=value-without-key
TRAILING_SPACE=planted-trailing   \t
"""
PLANTED = ("planted-anon-key-0123456789abcdef", "planted-trakt-secret-bbbb", "plantedproject.supabase.example",
           "planted-release-password", "planted-libdovi", "planted-release.jks", "planted-comment-value")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def git(*args, cwd):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def ci_build_filter():
    """filter_properties as ci_build.sh defines it."""
    text = (SCRIPTS / "cloud-build/ci_build.sh").read_text()
    return re.search(r"^filter_properties\(\) \{\n.*?^\}\n", text, re.M | re.S).group(0)


class ShieldCloudSetupTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.tree = self.tmp / "tree"
        for path in ("scripts/shield_cloud_setup.py", "scripts/cloud-build/shield-build.yml"):
            (self.tree / path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / path, self.tree / path)
        (self.tree / ".gitignore").write_text("local.properties\n.local/\nffmpeg-decoder-downmix/.cxx/\n__pycache__/\n")
        git("init", "-q", "-b", "main", cwd=self.tree)
        git("config", "user.email", "t@example.com", cwd=self.tree)
        git("config", "user.name", "t", cwd=self.tree)
        self.commit()
        self.source, self.build = self.tmp / "ffmpeg/src", self.tmp / "ffmpeg/libs"
        self.make_ffmpeg()
        (self.tree / "local.properties").write_text(
            PROPERTIES + f"FFMPEG_SOURCE_DIR={self.source}\nFFMPEG_BUILD_DIR={self.build}\n")
        self.home = self.tmp / "home"
        self.keystore = self.home / ".config/nuvio/nuvio-debug.keystore"
        self.keystore.parent.mkdir(parents=True)
        self.keystore.write_bytes(b"fake keystore bytes")
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        (self.bin / "gh").write_text(f'#!/bin/sh\nexec "{sys.executable}" "{FAKE_GH}" "$@"\n')
        (self.bin / "gh").chmod(0o755)
        (self.bin / "ninja").write_text('#!/bin/sh\ncat "$FAKE_NINJA_DEPS"\n')
        (self.bin / "ninja").chmod(0o755)
        self.deps = self.tmp / "deps.txt"
        self.local_build(["libavcodec/avcodec.h", "libavutil/avconfig.h", "libswresample/swresample.h"])
        self.state, self.log = self.tmp / "gh-state.json", self.tmp / "gh-log.jsonl"
        self.env = {"PATH": f"{self.bin}:/usr/bin:/bin", "HOME": str(self.home), "LANG": "C",
                    "TMPDIR": str(self.tmp), "FAKE_GH_STATE": str(self.state), "FAKE_GH_LOG": str(self.log),
                    "FAKE_NINJA_DEPS": str(self.deps), "NINJA": str(self.bin / "ninja")}

    def load_module(self):
        spec = importlib.util.spec_from_file_location("shield_cloud_setup", ROOT / "scripts/shield_cloud_setup.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def commit(self):
        git("add", "-A", cwd=self.tree)
        git("commit", "-q", "--allow-empty", "-m", "c", cwd=self.tree)

    def make_ffmpeg(self):
        files = {"libavcodec/avcodec.h": "avcodec", "libavcodec/aarch64/idct.h": "idct", "libavcodec/ac3enc.c": "source",
                 "libavutil/avconfig.h": "generated", "libswresample/swresample.h": "swr", "libavformat/avformat.h": "no",
                 "compat/atomics/gcc/stdatomic.h": "compat", "config.h": "cfg", "config_components.h": "cc",
                 "COPYING.GPLv2": "g2", "COPYING.GPLv3": "g3", "COPYING.LGPLv2.1": "l21", "COPYING.LGPLv3": "l3",
                 "LICENSE.md": "lic", "RELEASE": "7.0.2\n", "VERSION": "shadows <version>", ".git/HEAD": "ref",
                 "libavcodec/.git/x.h": "never"}
        for name, text in files.items():
            (self.source / name).parent.mkdir(parents=True, exist_ok=True)
            (self.source / name).write_text(text)
        for abi in ABIS:
            (self.build / abi).mkdir(parents=True)
            for lib in LIBS:
                (self.build / abi / lib).write_bytes(f"{abi}/{lib} v1".encode())

    def local_build(self, headers, root=None):
        """A local build of the module in .cxx, whose deps name these FFmpeg headers."""
        for abi in ("arm64-v8a", "armeabi-v7a"):
            deps = self.tree / f"ffmpeg-decoder-downmix/.cxx/Debug/h1/{abi}/.ninja_deps"
            deps.parent.mkdir(parents=True, exist_ok=True)
            deps.write_bytes(b"# ninjadeps\n")
        lines = ["CMakeFiles/ffmpegJNI.dir/ffmpeg_jni.cc.o: #deps 9, deps mtime 1 (VALID)",
                 f"    {self.tree}/ffmpeg-decoder-downmix/src/main/jni/ffmpeg_jni.cc",
                 "    /ndk/sysroot/usr/include/stdio.h"]
        lines += [f"    {root or self.source}/{h}" for h in headers]
        self.deps.write_text("\n".join(lines) + "\n\n")

    def setup(self, *args, **env):
        merged = {k: v for k, v in {**self.env, **env}.items() if v is not None}
        return subprocess.run([sys.executable, "-I", str(self.tree / "scripts/shield_cloud_setup.py"), *args],
                              cwd=self.tree, env=merged, stdin=subprocess.DEVNULL, capture_output=True, text=True,
                              timeout=60)

    def ok(self, *args, **env):
        result = self.setup(*args, **env)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def writes(self):
        return [c["argv"] for c in self.calls() if c["write"]]

    def fake(self):
        return json.loads(self.state.read_text())

    def edit_fake(self, change):
        state = self.fake()
        change(state)
        self.state.write_text(json.dumps(state))

    def all_steps(self):
        for step in ("repo", "secrets", "ffmpeg", "workflow"):
            self.ok(step)

    def pin(self):
        return json.loads((self.tree / "scripts/cloud-build/ffmpeg-asset.json").read_text())

    # --- repo ---

    def test_repo_creates_the_repo_private_and_locks_it_down(self):
        self.ok("repo")
        create = [c for c in self.writes() if c[:2] == ["repo", "create"]]
        self.assertEqual(1, len(create))
        self.assertIn("--private", create[0])
        settings = self.fake()["settings"]
        self.assertEqual("selected", settings["/actions/permissions"]["allowed_actions"])
        self.assertTrue(settings["/actions/permissions"]["sha_pinning_required"])
        self.assertTrue(settings["/actions/permissions/selected-actions"]["github_owned_allowed"])
        self.assertFalse(settings["/actions/permissions/selected-actions"]["verified_allowed"])
        self.assertEqual("read", settings["/actions/permissions/workflow"]["default_workflow_permissions"])
        self.assertEqual(1, settings["/actions/permissions/artifact-and-log-retention"]["days"])

    def test_an_empty_repo_gets_a_readme_so_a_release_can_follow(self):
        self.ok("repo")
        self.edit_fake(lambda s: (s["repo"].update(empty=True), s.update(contents={})))
        self.ok("repo")
        self.assertIn("README.md", self.fake()["contents"])
        self.assertFalse(self.fake()["repo"]["empty"])

    def test_another_default_branch_is_refused(self):
        self.ok("repo")
        self.edit_fake(lambda s: s["repo"].update(default_branch="master"))
        result = self.setup("repo")
        self.assertEqual(1, result.returncode)
        self.assertIn("dispatched on main", result.stderr)

    # --- the rules every step keeps ---

    def test_a_second_run_writes_nothing(self):
        self.all_steps()
        pin = (self.tree / "scripts/cloud-build/ffmpeg-asset.json").read_bytes()
        self.log.unlink()
        self.all_steps()
        self.assertEqual([], self.writes())
        self.assertEqual(pin, (self.tree / "scripts/cloud-build/ffmpeg-asset.json").read_bytes())
        self.commit()  # the pin
        self.ok("check")
        self.assertEqual([], self.writes())

    def test_every_gh_call_names_the_build_repo(self):
        self.all_steps()
        self.commit()
        self.ok("check")
        calls = self.calls()
        self.assertGreater(len(calls), 20)
        for call in calls:
            self.assertTrue(any(arg == BUILD_REPO or arg.startswith(f"repos/{BUILD_REPO}/") for arg in call["argv"]),
                            call["argv"])

    def test_every_write_follows_a_private_check(self):
        self.all_steps()
        calls = self.calls()
        for i, call in enumerate(calls):
            if call["write"] and call["argv"][:2] != ["repo", "create"]:
                self.assertEqual(["repo", "view", BUILD_REPO], calls[i - 1]["argv"][:3], call["argv"])

    def test_a_public_repo_gets_no_write(self):
        self.ok("repo")
        self.edit_fake(lambda s: s["repo"].update(visibility="PUBLIC"))
        self.log.unlink()
        for step in ("repo", "secrets", "ffmpeg", "workflow", "check"):
            with self.subTest(step):
                result = self.setup(step)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn("is not private", result.stdout + result.stderr)
        self.assertEqual([], self.writes())

    def test_a_missing_repo_gets_no_write_but_from_repo(self):
        for step in ("secrets", "ffmpeg", "workflow"):
            result = self.setup(step)
            self.assertEqual(1, result.returncode)
            self.assertIn("does not exist", result.stderr)
        self.assertEqual([], self.writes())

    # --- secrets ---

    def test_secret_values_go_only_on_stdin_and_are_never_printed(self):
        self.ok("repo")
        result = self.ok("secrets")
        sets = [c for c in self.calls() if c["argv"][:2] == ["secret", "set"]]
        self.assertEqual(["LOCAL_PROPERTIES_BASE64", "DEBUG_KEYSTORE_BASE64"], [c["argv"][2] for c in sets])
        values = {c["argv"][2]: c["stdin"] for c in sets}
        self.assertEqual(b"fake keystore bytes", base64.b64decode(values["DEBUG_KEYSTORE_BASE64"]))
        properties = base64.b64decode(values["LOCAL_PROPERTIES_BASE64"]).decode()
        for key in ("sdk.dir", "FFMPEG_SOURCE_DIR", "FFMPEG_BUILD_DIR", "USE_LOCAL_FFMPEG_DECODER",
                    "DOVI_LIBDOVI_STATIC_LIB", "NUVIO_RELEASE_KEY_PASSWORD", "NUVIO_RELEASE_STORE_FILE", "#", "!"):
            self.assertNotIn(key, properties)
        self.assertIn("NUVIO_SUPABASE_ANON_KEY=planted-anon-key-0123456789abcdef\n", properties)
        secrets_out = list(values.values()) + [base64.b64decode(v).decode(errors="replace") for v in values.values()]
        for call in self.calls():
            for arg in call["argv"]:
                for value in PLANTED + tuple(values.values()):
                    self.assertNotIn(value, arg)
        for value in PLANTED + tuple(secrets_out):
            self.assertNotIn(value.strip(), result.stdout + result.stderr)
        self.assertIn(f"secrets in {BUILD_REPO}: DEBUG_KEYSTORE_BASE64, LOCAL_PROPERTIES_BASE64", result.stdout)
        state = (self.tree / ".local/cloud-setup/state.json").read_text()
        for value in PLANTED + tuple(values.values()):
            self.assertNotIn(value, state)

    def test_the_strip_rule_is_ci_builds(self):
        source = self.tmp / "props"
        source.write_text(PROPERTIES + "LAST_LINE=no-newline")
        bash = subprocess.run(["/bin/bash", "-c", ci_build_filter() + 'filter_properties < "$1"', "-", str(source)],
                              capture_output=True, text=True, check=True).stdout
        self.assertEqual(bash, self.load_module().filter_properties(source.read_text()))
        self.assertIn("TRAKT_CLIENT_SECRET = planted-trakt-secret-bbbb\n", bash)
        self.assertIn("=value-without-key\n", bash)
        self.assertIn("LAST_LINE=no-newline\n", bash)

    def test_a_changed_value_is_set_again_and_only_that_one(self):
        self.ok("repo")
        self.ok("secrets")
        self.log.unlink()
        with open(self.tree / "local.properties", "a") as handle:
            handle.write("TMDB_API_KEY=planted-new-key-77777777\n")
        self.ok("secrets")
        self.assertEqual([["secret", "set", "LOCAL_PROPERTIES_BASE64", "-R", BUILD_REPO]], self.writes())

    def test_a_secret_changed_on_github_is_set_again(self):
        self.ok("repo")
        self.ok("secrets")
        self.edit_fake(lambda s: s["secrets"]["DEBUG_KEYSTORE_BASE64"].update(updatedAt="2027-01-01T00:00:00Z"))
        self.log.unlink()
        self.ok("secrets")
        self.assertEqual([["secret", "set", "DEBUG_KEYSTORE_BASE64", "-R", BUILD_REPO]], self.writes())

    def test_no_supabase_url_or_no_keystore_sets_nothing(self):
        self.ok("repo")
        self.log.unlink()
        self.keystore.unlink()
        result = self.setup("secrets")
        self.assertEqual(1, result.returncode)
        self.assertIn("no throwaway key", result.stderr)
        self.keystore.write_bytes(b"fake keystore bytes")
        (self.tree / "local.properties").write_text("TMDB_API_KEY=planted-tmdb-key\n")
        result = self.setup("secrets")
        self.assertEqual(1, result.returncode)
        self.assertIn("NUVIO_SUPABASE_URL is not set", result.stderr)
        self.assertEqual([], self.writes())

    # --- ffmpeg ---

    def members(self, asset):
        with tarfile.open(asset) as tar:
            return tar.getmembers()

    def uploaded(self):
        return [c["argv"] for c in self.calls() if c["argv"][:2] == ["release", "create"]]

    def test_the_pack_holds_headers_licences_and_libraries_only(self):
        self.ok("repo")
        self.ok("ffmpeg")
        pack = self.tmp / "pack.tar"
        module = self.load_module()
        module.pack(module.pack_members(self.source, self.build), pack)
        names = [m.name for m in self.members(pack)]
        self.assertEqual(sorted(names), names)
        expected = ["headers/COPYING.GPLv2", "headers/COPYING.GPLv3", "headers/COPYING.LGPLv2.1", "headers/COPYING.LGPLv3",
                    "headers/LICENSE.md", "headers/RELEASE", "headers/config.h", "headers/config_components.h",
                    "headers/libavcodec/aarch64/idct.h", "headers/libavcodec/avcodec.h", "headers/libavutil/avconfig.h",
                    "headers/libswresample/swresample.h"]
        expected += [f"static-libs/{abi}/{lib}" for abi in ABIS for lib in LIBS]
        self.assertEqual(sorted(expected), names)
        for member in self.members(pack):
            self.assertEqual((0, 0, 0, "", "", 0o644), (member.mtime, member.uid, member.gid, member.uname, member.gname,
                                                        member.mode))
        self.assertEqual(sha256(pack.read_bytes()), self.pin()["sha256"])

    def test_the_pack_is_deterministic(self):
        module = self.load_module()
        first, second = self.tmp / "a.tar", self.tmp / "b.tar"
        module.pack(module.pack_members(self.source, self.build), first)
        later = time.time() + 3600
        for path in list(self.source.rglob("*")) + list(self.build.rglob("*")):
            os.utime(path, (later, later))
            if path.is_file():
                path.chmod(0o600)
        module.pack(module.pack_members(self.source, self.build), second)
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_ffmpeg_uploads_once_and_writes_the_pin(self):
        self.ok("repo")
        result = self.ok("ffmpeg")
        [upload] = self.uploaded()
        self.assertEqual("ffmpeg-n7.0.2-1", upload[2])
        self.assertEqual("nuvio-ffmpeg.tar", Path(upload[3]).name)
        self.assertNotIn("--clobber", upload)
        pin = self.pin()
        self.assertEqual({"tag": "ffmpeg-n7.0.2-1", "asset": "nuvio-ffmpeg.tar", "ffmpeg": "n7.0.2"},
                         {k: pin[k] for k in ("tag", "asset", "ffmpeg")})
        [asset] = self.fake()["releases"]["ffmpeg-n7.0.2-1"]["assets"]
        self.assertEqual(f"sha256:{pin['sha256']}", asset["digest"])
        self.assertEqual({f"arm64-v8a/{lib}": sha256(f"arm64-v8a/{lib} v1".encode()) for lib in LIBS}, pin["libs"])
        self.assertIn("deps check: the 3 FFmpeg headers", result.stdout)
        self.assertNotIn(str(self.source), result.stdout + result.stderr)
        self.assertNotIn(str(self.build), result.stdout + result.stderr)

    def test_a_new_pack_gets_a_new_tag_and_the_old_asset_stays(self):
        self.ok("repo")
        self.ok("ffmpeg")
        old = self.fake()["releases"]["ffmpeg-n7.0.2-1"]
        (self.build / "x86/libavutil.a").write_bytes(b"x86/libavutil.a v2")
        self.log.unlink()
        self.ok("ffmpeg")
        self.assertEqual(["ffmpeg-n7.0.2-2"], [u[2] for u in self.uploaded()])
        self.assertEqual(old, self.fake()["releases"]["ffmpeg-n7.0.2-1"])
        self.assertEqual("ffmpeg-n7.0.2-2", self.pin()["tag"])
        for write in self.writes():
            self.assertEqual(["release", "create"], write[:2])

    def test_an_upload_that_stopped_before_the_pin_is_reused(self):
        self.ok("repo")
        self.ok("ffmpeg")
        (self.tree / "scripts/cloud-build/ffmpeg-asset.json").unlink()
        self.log.unlink()
        self.ok("ffmpeg")
        self.assertEqual([], self.writes())
        self.assertEqual("ffmpeg-n7.0.2-1", self.pin()["tag"])

    def test_an_asset_github_hashes_differently_is_not_trusted(self):
        self.ok("repo")
        self.edit_fake(lambda s: s.update(digest_override="sha256:" + "0" * 64))
        result = self.setup("ffmpeg")
        self.assertEqual(1, result.returncode)
        self.assertIn("does not hold nuvio-ffmpeg.tar", result.stderr)
        self.assertFalse((self.tree / "scripts/cloud-build/ffmpeg-asset.json").exists())

    def test_the_deps_check_needs_a_local_build(self):
        self.ok("repo")
        shutil.rmtree(self.tree / "ffmpeg-decoder-downmix/.cxx")
        result = self.setup("ffmpeg")
        self.assertEqual(1, result.returncode)
        self.assertIn("run one local build", result.stderr)
        self.assertIn("--skip-deps-check", result.stderr)
        self.assertEqual([], self.uploaded())
        result = self.ok("ffmpeg", "--skip-deps-check")
        self.assertIn("the deps check is skipped", result.stderr)
        self.assertEqual(1, len(self.uploaded()))

    def test_a_header_the_build_read_but_the_pack_lacks_stops_the_upload(self):
        self.ok("repo")
        self.local_build(["libavcodec/avcodec.h", "compat/atomics/gcc/stdatomic.h"])
        result = self.setup("ffmpeg")
        self.assertEqual(1, result.returncode)
        self.assertIn("compat/atomics/gcc/stdatomic.h", result.stderr)
        self.assertEqual([], self.uploaded())

    def test_a_build_that_read_another_ffmpeg_stops_the_upload(self):
        self.ok("repo")
        self.local_build(["libavcodec/avcodec.h"], root=self.tmp / "old-ffmpeg")
        result = self.setup("ffmpeg")
        self.assertEqual(1, result.returncode)
        self.assertIn("read no header of FFMPEG_SOURCE_DIR", result.stderr)

    def test_the_environment_wins_over_local_properties(self):
        self.ok("repo")
        other = self.tmp / "other-libs"
        shutil.copytree(self.build, other)
        (other / "arm64-v8a/libavcodec.a").write_bytes(b"env libavcodec")
        self.ok("ffmpeg", FFMPEG_BUILD_DIR=str(other))
        self.assertEqual(sha256(b"env libavcodec"), self.pin()["libs"]["arm64-v8a/libavcodec.a"])

    # --- workflow ---

    def test_workflow_pushes_heads_blob_and_updates_it_by_sha(self):
        self.ok("repo")
        self.ok("workflow")
        blob = git("rev-parse", "HEAD:scripts/cloud-build/shield-build.yml", cwd=self.tree)
        pushed = self.fake()["contents"][".github/workflows/shield-build.yml"]
        self.assertEqual(blob, pushed["sha"])
        self.assertEqual("main", pushed["branch"])
        self.assertEqual((self.tree / "scripts/cloud-build/shield-build.yml").read_bytes(),
                         base64.b64decode(pushed["content"]))
        # An uncommitted edit is not pushed.
        yml = self.tree / "scripts/cloud-build/shield-build.yml"
        yml.write_text(yml.read_text() + "# edit\n")
        self.log.unlink()
        result = self.ok("workflow")
        self.assertIn("uncommitted changes", result.stderr)
        self.assertEqual([], self.writes())
        self.commit()
        self.ok("workflow")
        [put] = [c for c in self.calls() if c["write"]]
        self.assertEqual(blob, json.loads(put["stdin"])["sha"])
        self.assertEqual(git("rev-parse", "HEAD:scripts/cloud-build/shield-build.yml", cwd=self.tree),
                         self.fake()["contents"][".github/workflows/shield-build.yml"]["sha"])

    # --- check ---

    def test_check_names_each_drift_and_writes_nothing(self):
        self.all_steps()
        self.commit()  # the pin
        self.ok("check")
        self.edit_fake(lambda s: (s["contents"][".github/workflows/shield-build.yml"].update(sha="0" * 40),
                                  s["settings"]["/actions/permissions/artifact-and-log-retention"].update(days=7),
                                  s["secrets"].pop("DEBUG_KEYSTORE_BASE64")))
        self.log.unlink()
        result = self.setup("check")
        self.assertEqual(1, result.returncode)
        self.assertIn("FAIL: .github/workflows/shield-build.yml is not HEAD's", result.stdout)
        self.assertIn("FAIL: /actions/permissions/artifact-and-log-retention differs", result.stdout)
        self.assertIn("FAIL: secret DEBUG_KEYSTORE_BASE64 is not set", result.stdout)
        self.assertEqual([], self.writes())

    def test_check_wants_the_pin_committed(self):
        self.all_steps()
        result = self.setup("check")
        self.assertEqual(1, result.returncode)
        self.assertIn("HEAD has no scripts/cloud-build/ffmpeg-asset.json", result.stdout)

    def test_check_warns_when_the_macs_libraries_moved_on(self):
        self.all_steps()
        self.commit()
        (self.build / "arm64-v8a/libavutil.a").write_bytes(b"rebuilt")
        result = self.ok("check")
        self.assertIn("differ from the pin: arm64-v8a/libavutil.a", result.stderr)


if __name__ == "__main__":
    unittest.main()
