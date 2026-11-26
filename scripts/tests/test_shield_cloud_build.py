"""shield_cloud_build.py: its checks in-process, and its flows in a throwaway tree with a bare origin,
over the fake gh in fake_gh.py and a fake keytool. Every value is planted; nothing reaches GitHub."""

import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
from pathlib import Path

# A `git rebase --exec` or a hook exports these, and the fixture's git would then work on that repository.
for variable in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_OBJECT_DIRECTORY",
                 "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_PREFIX", "GIT_NAMESPACE"):
    os.environ.pop(variable, None)

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent
FAKE_GH = Path(__file__).with_name("fake_gh.py")
BUILD_REPO = "tinof/NuvioTV-Builds"
CERT = "c22ec9" + "4a" * 29
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
LIBS = ("libavcodec.a", "libavutil.a", "libswresample.a")
INPUTS = {"variant": "Debug", "suffix": ".shield", "debuggable": False, "version_code": "1580",
          "version_name": "1.1.0-nt.10.shield"}


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git(*args, cwd):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def keytool_form(cert):
    return ":".join(cert[i:i + 2] for i in range(0, len(cert), 2)).upper()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def make_asset(path):
    """A tar laid out as shield_cloud_setup.py packs it, and the pin fields of it."""
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
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(buffer.getvalue())
    libs = {f"arm64-v8a/{lib}": sha256(members[f"static-libs/arm64-v8a/{lib}"]) for lib in LIBS}
    return {"tag": "ffmpeg-n7.0.2-1", "asset": "nuvio-ffmpeg.tar", "sha256": sha256(buffer.getvalue()), "libs": libs}


class PureTest(unittest.TestCase):
    """The parsing and matching rules, in-process. Only pure functions of the module are called."""

    @classmethod
    def setUpClass(cls):
        cls.m = load("shield_cloud_build", SCRIPTS / "shield_cloud_build.py")

    def test_the_run_id_comes_from_the_build_repos_run_url(self):
        printed = ("✓ Created workflow_dispatch event for shield-build.yml at main\n"
                   f"https://github.com/{BUILD_REPO}/actions/runs/45804590918\n")
        self.assertEqual(45804590918, self.m.parse_run_id(printed))
        self.assertIsNone(self.m.parse_run_id("https://github.com/tinof/NuvioTV-Fork/actions/runs/123\n"))
        self.assertIsNone(self.m.parse_run_id("To see runs for this workflow, try: gh run list\n"))
        self.assertIsNone(self.m.parse_run_id(""))

    def runs(self, *titles, event="workflow_dispatch"):
        return [{"databaseId": i + 1, "event": event, "displayTitle": t} for i, t in enumerate(titles)]

    def test_a_run_matches_by_its_exact_title(self):
        title = f"Shield Debug {'e' * 40} debug-20261126-142000-a1b2c3"
        other = f"Shield Debug {'e' * 40} debug-20261126-142000-ffffff"
        self.assertEqual(2, self.m.match_run(self.runs(other, title), title)["databaseId"])
        self.assertIsNone(self.m.match_run(self.runs(other), title))
        self.assertIsNone(self.m.match_run([], title))
        self.assertIsNone(self.m.match_run(self.runs(title, event="push"), title))
        self.assertIsNone(self.m.match_run(self.runs(title + "0", title[:-1]), title), "a longer or shorter nonce")
        with self.assertRaises(self.m.CloudError):
            self.m.match_run(self.runs(title, title), title)

    def test_certificates_compare_in_one_form(self):
        self.assertEqual(CERT, self.m.normalise_cert(keytool_form(CERT)))
        self.assertEqual(CERT, self.m.normalise_cert(CERT.upper()))
        self.assertEqual(CERT, self.m.normalise_cert(" " + keytool_form(CERT) + "\n"))
        for bad in ("", None, CERT[:-2], CERT + "00", "zz" * 32):
            with self.subTest(bad=bad), self.assertRaises(self.m.CloudError):
                self.m.normalise_cert(bad)

    def test_a_malformed_pin_is_refused(self):
        good = {"tag": "ffmpeg-n7.0.2-1", "asset": "nuvio-ffmpeg.tar", "sha256": "ab" * 32,
                "libs": {"arm64-v8a/libavcodec.a": "cd" * 32}}
        self.assertEqual(good, self.m.read_pin(json.dumps({**good, "size": 1, "members": 2})))
        for change in ({"tag": "latest"}, {"asset": "../x"}, {"sha256": "AB" * 32}, {"libs": {}},
                       {"libs": {"../libavcodec.a": "cd" * 32}}, {"libs": {"arm64-v8a/libavcodec.a": "short"}}):
            with self.subTest(change=change), self.assertRaises(self.m.CloudError):
                self.m.read_pin(json.dumps({**good, **change}))
        for text in ("", "[]", "{}"):
            with self.subTest(text=text), self.assertRaises(self.m.CloudError):
                self.m.read_pin(text)

    def test_the_property_keys_follow_the_setup_scripts_rule(self):
        setup = load("shield_cloud_setup", SCRIPTS / "shield_cloud_setup.py")
        planted = load("setup_test_values", Path(__file__).with_name("test_shield_cloud_setup.py"))
        properties = planted.PROPERTIES + "EMPTY_VALUE=\n"
        filtered = setup.property_values(setup.filter_properties(properties))
        self.assertEqual({k for k, v in filtered.items() if v}, self.m.property_keys(properties))
        self.assertIn("NUVIO_SUPABASE_URL", self.m.property_keys(properties))
        self.assertNotIn("NUVIO_RELEASE_KEY_PASSWORD", self.m.property_keys(properties))


class VerifyTest(unittest.TestCase):
    """verify() on artifacts made by the same generator as the fake gh's downloads."""

    @classmethod
    def setUpClass(cls):
        cls.m = load("shield_cloud_build", SCRIPTS / "shield_cloud_build.py")
        cls.fake = load("fake_gh", FAKE_GH)

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        apk = self.tmp / "fixture.apk"
        apk.write_bytes(b"PK\x03\x04 a fake APK")
        self.pin = {"tag": "ffmpeg-n7.0.2-1", "asset": "nuvio-ffmpeg.tar", "sha256": "ab" * 32,
                    "libs": {"arm64-v8a/libavcodec.a": "cd" * 32, "arm64-v8a/libavutil.a": "ef" * 32}}
        self.commit, self.request_id, self.run_id = "e" * 40, "debug-20261126-142000-a1b2c3", 45800000001
        self.state = {"commit": self.commit, "request_id": self.request_id, "inputs": dict(INPUTS)}
        self.template = {"apk": str(apk), "cert": CERT, "ffmpeg_asset": self.pin,
                         "stamp": {"tree": "d" * 40, "dirty": False, "sha256": "1" * 64, "packaged_sha256": "2" * 64}}

    def problems(self, head=None, **change):
        run = {"databaseId": self.run_id,
               "inputs": {"commit": self.commit, "request_id": self.request_id, "variant": "Debug", "suffix": ".shield",
                          "debuggable": "false", "version_code": "1580", "version_name": "1.1.0-nt.10.shield"}}
        directory = Path(tempfile.mkdtemp(dir=self.tmp))
        for name, data in self.fake.artifact_files(run, {**self.template, **change}).items():
            (directory / name).write_bytes(data)
        return self.m.verify(directory, self.state, self.pin, self.run_id, head or self.commit, CERT)

    def test_the_requested_build_verifies(self):
        self.assertEqual([], self.problems())

    def test_each_mismatch_is_named(self):
        apk = "app-full-arm64-v8a-debug.apk"
        cases = {
            "commit": ({"info_overrides": {"commit": "f" * 40}}, "build-info commit"),
            "request id": ({"info_overrides": {"request_id": "debug-20261126-142000-ffffff"}}, "build-info request_id"),
            "debuggable": ({"info_overrides": {"inputs.debuggable": True}}, "build-info inputs"),
            "suffix": ({"info_overrides": {"inputs.suffix": ".shielddbg"}}, "build-info inputs"),
            "version code as a number": ({"info_overrides": {"inputs.version_code": 1580}}, "build-info inputs"),
            "version name": ({"info_overrides": {"inputs.version_name": "1.1.0-nt.11"}}, "build-info inputs"),
            "variant": ({"info_overrides": {"inputs.variant": "Benchmark"}}, "build-info inputs"),
            "run id": ({"info_overrides": {"run.id": "45800000002"}}, "build-info run.id"),
            "run repository": ({"info_overrides": {"run.repository": "someone/NuvioTV-Builds"}}, "build-info run.repository"),
            "schema": ({"info_overrides": {"schema": 2}}, "build-info schema"),
            "apk name": ({"info_overrides": {"apk.name": "../../app.apk"}}, "build-info apk.name"),
            "apk bytes": ({"apk_extra": "changed after the hash"}, "the APK's sha256"),
            "apk size": ({"info_overrides": {"apk.size": 1}}, "the APK's size"),
            "apk missing": ({"omit": [apk]}, f"the download has no {apk}"),
            "build-info missing": ({"omit": ["build-info.json"]}, "no readable build-info.json"),
            "stamp": ({"stamp_file": {"tree": "0" * 40, "dirty": False}}, "the downloaded libnuvio_rpu.so.stamp"),
            "stamp missing": ({"omit": ["libnuvio_rpu.so.stamp"]}, "no readable libnuvio_rpu.so.stamp"),
            "asset tag": ({"info_overrides": {"ffmpeg_asset.tag": "ffmpeg-n7.0.2-2"}}, "ffmpeg_asset.tag"),
            "asset sha": ({"info_overrides": {"ffmpeg_asset.sha256": "00" * 32}}, "ffmpeg_asset.sha256"),
            "asset name": ({"info_overrides": {"ffmpeg_asset.asset": "other.tar"}}, "ffmpeg_asset.asset"),
            "library hashes": ({"info_overrides": {"ffmpeg_asset.libs": {"arm64-v8a/libavcodec.a": "cd" * 32}}},
                               "ffmpeg_asset.libs"),
            "certificate": ({"cert": "0" * 64}, "the APK is signed with " + "0" * 64),
            "malformed certificate": ({"cert": "not-a-fingerprint"}, "build-info cert_sha256"),
            "package": ({"package": "com.nuvio.tv.test.shielddbg"}, "does not carry the suffix .shield"),
        }
        for label, (change, needle) in cases.items():
            with self.subTest(label):
                problems = self.problems(**change)
                self.assertTrue(any(needle in p for p in problems), problems)

    def test_a_moved_head_is_named(self):
        self.assertIn(f"HEAD is {'f' * 12} now; the build is of {'e' * 12}", self.problems(head="f" * 40))


class CloudTree:
    """A throwaway tree whose origin is a bare repo, with the fake gh and keytool on PATH."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.tree = self.tmp / "tree"
        for path in ("scripts/shield_cloud_build.py", "scripts/cloud-build/shield-build.yml"):
            (self.tree / path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / path, self.tree / path)
        self.write("native/rpu_builder/src/lib.rs", "v1\n")
        self.write(".gitignore", ".local/\nlocal.properties\n__pycache__/\n")
        self.asset = self.tmp / "releases/nuvio-ffmpeg.tar"
        self.pin = make_asset(self.asset)
        self.write("scripts/cloud-build/ffmpeg-asset.json",
                   json.dumps({**self.pin, "ffmpeg": "n7.0.2", "members": 14, "size": self.asset.stat().st_size},
                              indent=2, sort_keys=True) + "\n")
        git("init", "-q", "-b", "main", cwd=self.tree)
        git("config", "user.email", "t@example.com", cwd=self.tree)
        git("config", "user.name", "t", cwd=self.tree)
        git("add", "-A", cwd=self.tree)
        git("commit", "-q", "-m", "c", cwd=self.tree)
        self.origin = self.tmp / "origin.git"
        git("init", "-q", "--bare", str(self.origin), cwd=self.tmp)
        git("remote", "add", "origin", str(self.origin), cwd=self.tree)
        git("push", "-q", "origin", "main", cwd=self.tree)
        self.home = self.tmp / "home"
        self.keystore = self.home / ".config/nuvio/nuvio-debug.keystore"
        self.keystore.parent.mkdir(parents=True)
        self.keystore.write_bytes(b"fake keystore")
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.tool("gh", f'exec "{sys.executable}" "{FAKE_GH}" "$@"\n')
        self.tool("keytool", 'store=""\nwhile [ $# -gt 0 ]; do [ "$1" = -keystore ] && store="$2"; shift; done\n'
                             '[ -f "$store" ] || { echo "keytool error: no keystore" >&2; exit 1; }\n'
                             'printf "Alias name: androiddebugkey\\nCertificate fingerprints:\\n\\t SHA1: AA:BB\\n'
                             '\\t SHA256: %s\\n" "$FAKE_KEYTOOL_SHA256"\n')
        self.apk = self.tmp / "fixture.apk"
        self.apk.write_bytes(b"PK\x03\x04 a fake APK")
        self.stamp = {"tree": "d" * 40, "dirty": False, "sha256": "1" * 64, "packaged_sha256": "2" * 64}
        self.fake_state, self.log = self.tmp / "gh-state.json", self.tmp / "gh-log.jsonl"
        self.states = self.tree / ".local/cloud-builds"
        self.fake_state.write_text(json.dumps({
            "repo": {"visibility": "PRIVATE", "empty": False, "default_branch": "main"},
            "settings": {}, "secrets": {}, "releases": {},
            "contents": {".github/workflows/shield-build.yml": {"sha": self.blob()}},
            "release_files": {self.pin["tag"]: {self.pin["asset"]: str(self.asset)}},
            "artifact": {"apk": str(self.apk), "stamp": self.stamp, "cert": CERT, "ffmpeg_asset": self.pin,
                         "property_keys": []},
            "state_dir": str(self.states),
        }))
        (self.tmp / "tmpdir").mkdir()
        self.env = {"PATH": f"{self.bin}:/usr/bin:/bin", "HOME": str(self.home), "LANG": "C",
                    "TMPDIR": str(self.tmp / "tmpdir"), "FAKE_GH_STATE": str(self.fake_state),
                    "FAKE_GH_LOG": str(self.log), "FAKE_KEYTOOL_SHA256": keytool_form(CERT),
                    "NUVIO_CLOUD_POLL_SECONDS": "0", "NUVIO_CLOUD_WAIT_MIN": "5"}

    # --- helpers ---

    def write(self, path, text):
        (self.tree / path).parent.mkdir(parents=True, exist_ok=True)
        (self.tree / path).write_text(text)

    def tool(self, name, body):
        (self.bin / name).write_text("#!/bin/sh\n" + body)
        (self.bin / name).chmod(0o755)

    def blob(self):
        return git("rev-parse", "HEAD:scripts/cloud-build/shield-build.yml", cwd=self.tree)

    def head(self):
        return git("rev-parse", "HEAD", cwd=self.tree)

    def commit_and_push(self, message="next"):
        git("add", "-A", cwd=self.tree)
        git("commit", "-q", "--allow-empty", "-m", message, cwd=self.tree)
        git("push", "-q", "origin", "main", cwd=self.tree)

    def build(self, *extra, variant="Debug", suffix=".shield", code="1580", name="1.1.0-nt.10.shield", **env):
        argv = [sys.executable, "-I", str(self.tree / "scripts/shield_cloud_build.py"), "build", "--variant", variant,
                "--suffix", suffix, "--version-code", code, "--version-name", name, *extra]
        merged = {k: v for k, v in {**self.env, **env}.items() if v is not None}
        return subprocess.run(argv, cwd=self.tmp, env=merged, stdin=subprocess.DEVNULL, capture_output=True, text=True,
                              timeout=120)

    def ok(self, *extra, **env):
        result = self.build(*extra, **env)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result

    def fails(self, code, message, *extra, **env):
        result = self.build(*extra, **env)
        self.assertEqual(code, result.returncode, result.stdout + result.stderr)
        self.assertIn(message, result.stderr)
        self.assertEqual("", result.stdout)
        return result

    def output(self, result):
        return dict(line.split("=", 1) for line in result.stdout.splitlines())

    def calls(self):
        return [json.loads(line)["argv"] for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def commands(self, *prefix):
        return [c for c in self.calls() if c[:len(prefix)] == list(prefix)]

    def entries(self):
        """The gh log: {"argv", "stdin", "write"} per call."""
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def fake(self):
        return json.loads(self.fake_state.read_text())

    def edit_fake(self, change):
        state = self.fake()
        change(state)
        self.fake_state.write_text(json.dumps(state))

    def complete_after(self, views):
        """Every recorded run completes after this many more `run view` calls."""
        self.edit_fake(lambda s: [r.update(done_after=r.get("views", 0) + views) for r in s["runs"].values()])

    def recorded(self):
        return {p.stem: json.loads(p.read_text()) for p in self.states.glob("*.json")}

    def downloads(self):
        return sorted(p.name for p in self.states.iterdir() if p.is_dir()) if self.states.exists() else []


class CloudBuildTest(CloudTree, unittest.TestCase):
    """The script as shield_build_install.sh runs it."""

    # --- a build ---

    def test_a_build_dispatches_waits_downloads_and_verifies(self):
        result = self.ok()
        out = self.output(result)
        (request_id, state), = self.recorded().items()
        self.assertRegex(request_id, r"^debug-\d{8}-\d{6}-[0-9a-f]{6}$")
        self.assertEqual("verified", state["status"])
        self.assertEqual(self.head(), out["commit"])
        self.assertEqual(f".local/cloud-builds/{request_id}/app-full-arm64-v8a-debug.apk", out["apk"])
        self.assertEqual(f".local/cloud-builds/{request_id}/libnuvio_rpu.so.stamp", out["stamp"])
        self.assertEqual(".local/cloud-ffmpeg/ffmpeg-n7.0.2-1", out["ffmpeg"])
        self.assertEqual(str(state["run_id"]), out["run_id"])
        self.assertEqual(f"https://github.com/{BUILD_REPO}/actions/runs/{out['run_id']}", out["run_url"])
        self.assertEqual(self.apk.read_bytes(), (self.tree / out["apk"]).read_bytes())
        dispatch, = self.commands("workflow", "run")
        fields = dict(dispatch[i + 1].split("=", 1) for i, a in enumerate(dispatch) if a == "-f")
        self.assertEqual({"commit": self.head(), "request_id": request_id, "variant": "Debug", "suffix": ".shield",
                          "debuggable": "false", "version_code": "1580", "version_name": "1.1.0-nt.10.shield"}, fields)
        self.assertEqual(["shield-build.yml", "-R", BUILD_REPO, "--ref", "main"], dispatch[2:7])
        self.assertEqual([], self.commands("run", "cancel"))
        self.assertEqual([[str(state["run_id"]), "-R", BUILD_REPO, "-n", f"shield-apk-{request_id}"]],
                         [c[2:7] for c in self.commands("run", "download")])

    def test_the_state_is_written_before_the_dispatch(self):
        self.ok()
        run, = self.fake()["runs"].values()
        self.assertTrue(run["state_file_existed"])

    def test_the_verified_artifact_is_deleted_from_the_build_repo(self):
        self.ok()
        run, = self.fake()["runs"].values()
        self.assertTrue(run["artifacts"][0]["deleted"])

    def test_a_failed_artifact_delete_only_warns(self):
        self.edit_fake(lambda s: s.update(artifact_delete_fails=True))
        result = self.ok()
        self.assertIn("warning: could not delete the artifact", result.stderr)

    def test_every_gh_call_names_the_build_repo_and_every_write_follows_a_private_check(self):
        self.ok()
        calls = self.entries()
        self.assertGreater(len(calls), 8)
        for call in calls:
            self.assertTrue(any(a == BUILD_REPO or a.startswith(f"repos/{BUILD_REPO}/") for a in call["argv"]), call)
        writes = [i for i, c in enumerate(calls) if c["write"]]
        self.assertEqual(2, len(writes))  # the dispatch and the artifact delete
        for i in writes:
            self.assertEqual(["repo", "view", BUILD_REPO], calls[i - 1]["argv"][:3], calls[i]["argv"])

    def test_the_run_is_found_without_a_printed_url(self):
        for hook in ("none", "other"):
            with self.subTest(hook):
                shutil.rmtree(self.states, ignore_errors=True)
                self.edit_fake(lambda s: s.update(dispatch_url=hook, runs={}))
                out = self.output(self.ok())
                self.assertEqual([out["run_id"]], list(self.fake()["runs"]))

    def test_a_benchmark_build_downloads_the_benchmark_apk(self):
        out = self.output(self.ok(variant="Benchmark"))
        self.assertTrue(out["apk"].endswith("/app-full-arm64-v8a-benchmark.apk"), out)

    # --- the end of a run ---

    def test_a_run_that_does_not_succeed_exits_1_and_the_next_one_dispatches_again(self):
        for conclusion in ("failure", "cancelled", "timed_out", "skipped", "startup_failure"):
            with self.subTest(conclusion):
                self.edit_fake(lambda s: s.update(run_conclusion=conclusion))
                result = self.fails(1, f"the cloud build ended {conclusion}")
                self.assertIn("--log-failed", result.stderr)
                self.assertEqual([], self.downloads())
                self.assertEqual([], self.commands("run", "download"))
        self.assertEqual({"failed"}, {s["status"] for s in self.recorded().values()})
        self.edit_fake(lambda s: s.update(run_conclusion="success"))
        self.ok()
        self.assertEqual(6, len(self.commands("workflow", "run")))

    def test_the_wait_budget_ends_with_75_and_the_run_is_never_cancelled(self):
        self.edit_fake(lambda s: s.update(run_views=1000))
        result = self.fails(75, "Run the same command again to attach to it", NUVIO_CLOUD_WAIT_MIN="0")
        self.assertIn("keeps going", result.stderr)
        self.assertEqual([["workflow", "run"]], [c["argv"][:2] for c in self.entries() if c["write"]])
        self.assertEqual([], self.commands("run", "cancel"))
        (state,) = self.recorded().values()
        self.assertEqual("dispatched", state["status"])
        self.assertIsNotNone(state["run_id"])

    def test_a_rerun_attaches_to_the_recorded_run(self):
        self.edit_fake(lambda s: s.update(run_views=1000))
        self.fails(75, "still in_progress", NUVIO_CLOUD_WAIT_MIN="0")
        self.complete_after(2)
        result = self.ok()
        self.assertIn("attaching to run", result.stderr)
        self.assertEqual(1, len(self.commands("workflow", "run")))
        self.assertEqual("verified", next(iter(self.recorded().values()))["status"])

    def test_a_rerun_attaches_to_a_run_it_had_not_found_yet(self):
        self.edit_fake(lambda s: s.update(run_views=1000))
        self.fails(75, "still in_progress", NUVIO_CLOUD_WAIT_MIN="0")
        request_id, state = next(iter(self.recorded().items()))
        state.update(status="dispatching", run_id=None, run_url=None)  # killed before it recorded the run
        (self.states / f"{request_id}.json").write_text(json.dumps(state))
        self.complete_after(2)
        self.ok()
        self.assertEqual(1, len(self.commands("workflow", "run")))

    def test_a_request_without_a_run_is_abandoned(self):
        state = {"schema": 1, "request_id": "debug-20261126-000000-000000", "commit": self.head(), "inputs": INPUTS,
                 "created": "2026-11-26T00:00:00+00:00", "status": "dispatching", "run_id": None, "run_url": None}
        self.states.mkdir(parents=True)
        (self.states / "debug-20261126-000000-000000.json").write_text(json.dumps(state))
        self.ok()
        self.assertEqual(1, len(self.commands("workflow", "run")))
        self.assertEqual("abandoned", self.recorded()["debug-20261126-000000-000000"]["status"])

    def finished_run(self, expired=False):
        """A run that succeeded after the script that dispatched it stopped waiting."""
        self.edit_fake(lambda s: s.update(run_views=1000))
        self.fails(75, "still in_progress", NUVIO_CLOUD_WAIT_MIN="0")
        self.edit_fake(lambda s: [s.update(run_views=2)] + [r.update(done_after=0) for r in s["runs"].values()])
        run_id = next(iter(self.fake()["runs"]))
        subprocess.run(["gh", "run", "view", run_id, "-R", BUILD_REPO, "--json", "status"], env=self.env,
                       stdin=subprocess.DEVNULL, capture_output=True, check=True, timeout=60)
        if expired:
            self.edit_fake(lambda s: s["runs"][run_id]["artifacts"][0].update(expired=True))
        return run_id

    def test_a_finished_run_with_its_artifact_is_downloaded(self):
        run_id = self.finished_run()
        result = self.ok()
        self.assertIn("attaching to the finished run", result.stderr)
        self.assertEqual(run_id, self.output(result)["run_id"])
        self.assertEqual(1, len(self.commands("workflow", "run")))

    def test_a_finished_run_without_its_artifact_is_built_again(self):
        run_id = self.finished_run(expired=True)
        self.assertNotEqual(run_id, self.output(self.ok())["run_id"])
        self.assertEqual(2, len(self.commands("workflow", "run")))
        self.assertIn("expired", {s["status"] for s in self.recorded().values()})

    def test_rebuild_does_not_attach_to_a_finished_run(self):
        run_id = self.finished_run()
        self.assertNotEqual(run_id, self.output(self.ok("--rebuild"))["run_id"])
        self.assertEqual(2, len(self.commands("workflow", "run")))

    def test_rebuild_still_attaches_to_a_running_one(self):
        self.edit_fake(lambda s: s.update(run_views=1000))
        self.fails(75, "still in_progress", NUVIO_CLOUD_WAIT_MIN="0")
        self.complete_after(2)
        result = self.ok("--rebuild")
        self.assertIn("attaching to run", result.stderr)
        self.assertEqual(1, len(self.commands("workflow", "run")))

    def test_a_successful_run_without_an_artifact_exits_1(self):
        self.edit_fake(lambda s: s.update(no_artifact=True))
        self.fails(1, "has no artifact shield-apk-")
        self.edit_fake(lambda s: s.update(no_artifact=False))
        self.ok()
        self.assertEqual(2, len(self.commands("workflow", "run")))

    def test_a_failed_dispatch_exits_1(self):
        self.edit_fake(lambda s: s.update(dispatch_fails=True))
        self.fails(1, "gh workflow run failed")
        self.assertEqual("failed", next(iter(self.recorded().values()))["status"])

    def test_head_moved_during_the_build_stops_before_the_download(self):
        before = self.head()
        self.edit_fake(lambda s: s.update(move_head=str(self.tree)))
        self.fails(1, f"during the build of {before[:12]}")
        self.assertNotEqual(before, self.head())
        self.assertEqual([], self.commands("run", "download"))
        self.assertEqual([], self.downloads())

    # --- reuse ---

    def test_a_verified_download_is_reused(self):
        first = self.output(self.ok())
        self.log.unlink()
        result = self.ok()
        self.assertEqual(first, self.output(result))
        self.assertIn("reusing the verified download", result.stderr)
        self.assertEqual([], [c["argv"] for c in self.entries() if c["write"]])
        self.assertEqual([], self.commands("run", "download"))
        self.assertEqual([], self.commands("release", "download"))

    def test_rebuild_and_other_inputs_dispatch_again(self):
        self.ok()
        self.ok("--rebuild")
        self.ok(suffix=".shielddbg")
        self.ok("--debuggable", suffix=".shielddbg")
        self.ok(code="1590")
        self.assertEqual(5, len(self.commands("workflow", "run")))
        self.assertEqual(3, len(self.downloads()))  # the 3 newest stay

    def test_a_download_that_no_longer_verifies_is_built_again(self):
        out = self.output(self.ok())
        (self.tree / out["apk"]).write_bytes(b"changed")
        result = self.ok()
        self.assertIn("no longer verifies", result.stderr)
        self.assertEqual(2, len(self.commands("workflow", "run")))

    def test_a_new_commit_builds_again(self):
        self.ok()
        self.write("native/rpu_builder/src/lib.rs", "v2\n")
        self.commit_and_push()
        self.ok()
        self.assertEqual(2, len(self.commands("workflow", "run")))

    # --- verification and the local copies ---

    def test_a_download_that_does_not_verify_is_not_kept(self):
        self.edit_fake(lambda s: s["artifact"].update(info_overrides={"commit": "f" * 40}))
        result = self.fails(1, "is not the requested build")
        self.assertIn("build-info commit", result.stderr)
        self.assertEqual([], [p.name for p in self.states.iterdir() if p.is_dir()])
        self.assertEqual("failed", next(iter(self.recorded().values()))["status"])
        run, = self.fake()["runs"].values()
        self.assertFalse(run["artifacts"][0].get("deleted"))

    def test_an_apk_signed_with_another_key_is_not_kept(self):
        self.edit_fake(lambda s: s["artifact"].update(cert="0" * 64))
        self.fails(1, "the Mac's CI debug keystore has " + CERT)

    def test_only_the_three_newest_downloads_stay(self):
        self.states.mkdir(parents=True)
        for age, name in enumerate(("debug-20261120-000000-aaaaaa", "debug-20261121-000000-bbbbbb",
                                    "debug-20261122-000000-cccccc")):
            (self.states / name).mkdir()
            (self.states / f"{name}.json").write_text("{}")
            stamp = time.time() - 3600 * (10 - age)
            os.utime(self.states / name, (stamp, stamp))
        (self.states / "notes").mkdir()
        (self.states / ".partial-fresh").mkdir()
        old_partial = self.states / ".partial-old"
        old_partial.mkdir()
        os.utime(old_partial, (time.time() - 7 * 3600,) * 2)
        self.ok()
        left = self.downloads()
        self.assertNotIn("debug-20261120-000000-aaaaaa", left)
        self.assertFalse((self.states / "debug-20261120-000000-aaaaaa.json").exists())
        self.assertIn("debug-20261121-000000-bbbbbb", left)
        self.assertIn("debug-20261122-000000-cccccc", left)
        self.assertIn("notes", left)
        self.assertIn(".partial-fresh", left)
        self.assertNotIn(".partial-old", left)
        self.assertEqual(3, len([n for n in left if n.startswith("debug-")]))

    def test_the_ffmpeg_asset_is_unpacked_once_and_checked(self):
        out = self.output(self.ok())
        asset = self.tree / out["ffmpeg"]
        self.assertEqual("arm64-v8a libavcodec.a", (asset / "static-libs/arm64-v8a/libavcodec.a").read_text())
        self.assertTrue((asset / "headers/libavcodec/avcodec.h").is_file())
        self.ok("--rebuild")
        self.assertEqual(1, len(self.commands("release", "download")))
        (asset / "static-libs/arm64-v8a/libavutil.a").write_text("changed")
        self.ok()
        self.assertEqual(2, len(self.commands("release", "download")))
        self.assertEqual("arm64-v8a libavutil.a", (asset / "static-libs/arm64-v8a/libavutil.a").read_text())

    def test_an_ffmpeg_asset_with_another_sha_stops(self):
        self.asset.write_bytes(b"another asset")
        self.fails(1, f"the pin says {self.pin['sha256']}")
        self.assertFalse((self.tree / ".local/cloud-ffmpeg/ffmpeg-n7.0.2-1").exists())

    def test_property_key_drift_and_stale_mac_libraries_only_warn(self):
        libs = self.tmp / "mac-libs/arm64-v8a"
        libs.mkdir(parents=True)
        for lib in LIBS:
            (libs / lib).write_text(f"arm64-v8a {lib}")
        (libs / "libavutil.a").write_text("rebuilt on the Mac")
        self.write("local.properties", "NUVIO_SUPABASE_URL=https://planted.example\n"
                                       "TMDB_API_KEY=planted-tmdb-value-0123\nsdk.dir=/Users/fake/sdk\n"
                                       f"FFMPEG_BUILD_DIR={libs.parent}\n")
        self.edit_fake(lambda s: s["artifact"].update(property_keys=["NUVIO_SUPABASE_URL", "TRAKT_CLIENT_ID"]))
        result = self.ok()
        self.assertIn("only in the cloud: TRAKT_CLIENT_ID; only on the Mac: TMDB_API_KEY", result.stderr)
        self.assertIn("are not the pinned ones (arm64-v8a/libavutil.a)", result.stderr)
        self.assertNotIn("planted-tmdb-value", result.stderr + result.stdout)

    # --- preflight ---

    def assert_refused_without_gh(self, message):
        result = self.fails(1, message)
        self.assertIn("--local", result.stderr)
        self.assertEqual([], self.calls())
        self.assertFalse(self.states.exists())

    def test_a_tracked_change_is_refused(self):
        self.write("native/rpu_builder/src/lib.rs", "v2\n")
        self.assert_refused_without_gh("uncommitted or untracked changes")

    def test_an_untracked_file_is_refused(self):
        self.write("notes.txt", "x\n")
        self.assert_refused_without_gh("?? notes.txt")

    def test_an_unpushed_head_is_refused(self):
        git("commit", "-q", "--allow-empty", "-m", "local only", cwd=self.tree)
        self.assert_refused_without_gh("is on no origin branch")

    def test_a_head_on_another_origin_branch_builds(self):
        git("commit", "-q", "--allow-empty", "-m", "on a branch", cwd=self.tree)
        git("push", "-q", "origin", "HEAD:refs/heads/feature", cwd=self.tree)
        self.ok()

    def test_a_head_without_a_pin_names_the_setup_command(self):
        git("rm", "-q", "scripts/cloud-build/ffmpeg-asset.json", cwd=self.tree)
        self.commit_and_push()
        self.fails(1, "shield_cloud_setup.py ffmpeg")
        self.assertEqual([], self.calls())

    def test_no_keystore_on_the_mac_stops_before_the_dispatch(self):
        self.keystore.unlink()
        self.fails(1, "no CI debug keystore on the Mac")
        self.assertEqual([], self.commands("workflow", "run"))

    def test_a_public_build_repo_gets_no_build(self):
        self.edit_fake(lambda s: s["repo"].update(visibility="PUBLIC"))
        self.fails(1, "is not private")
        self.assertEqual([], [c for c in self.entries() if c["write"]])

    def test_a_drifted_workflow_names_the_setup_command(self):
        for change in ({"sha": "0" * 40}, None):
            with self.subTest(change=change):
                self.edit_fake(lambda s: s["contents"].update({".github/workflows/shield-build.yml": change})
                               if change else s["contents"].pop(".github/workflows/shield-build.yml", None))
                self.fails(1, "shield_cloud_setup.py workflow")
                self.assertEqual([], self.commands("workflow", "run"))

    def test_bad_inputs_exit_2_before_anything_runs(self):
        cases = [((), {"suffix": "shield"}), ((), {"suffix": ".Shield"}), ((), {"code": "15x0"}),
                 ((), {"name": "1.1 0"}), (("--debuggable",), {"variant": "Benchmark"}),
                 ((), {"NUVIO_CLOUD_WAIT_MIN": "soon"}), ((), {"NUVIO_CLOUD_POLL_SECONDS": "-1"}),
                 ((), {"variant": "Release"})]
        for extra, kwargs in cases:
            with self.subTest(kwargs=kwargs, extra=extra):
                result = self.build(*extra, **kwargs)
                self.assertEqual(2, result.returncode, result.stdout + result.stderr)
                self.assertEqual([], self.calls())
        self.assertFalse(self.states.exists())


if __name__ == "__main__":
    unittest.main()
