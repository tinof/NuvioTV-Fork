import argparse
import contextlib
import importlib.util
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SKILL = ROOT / ".claude/skills/upstream-sync"
TRIAGE_WORKFLOW = ROOT / ".claude/workflows/upstream-sync-triage.js"


def load(name):
    spec = importlib.util.spec_from_file_location(f"upstream_sync_{name}", SKILL / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load("sync")
tools = sys.modules["sync_tools"]
both = load("both")
triage = load("triage_sets")
CONFIG = sync.load_config(SKILL / "sync.json")


class BothTest(unittest.TestCase):
    TEXT = "a\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> abc (subject)\nb\n"

    def test_orders(self):
        self.assertEqual(("a\nours\nb\n", 1), both.resolve(self.TEXT, "ours"))
        self.assertEqual(("a\ntheirs\nb\n", 1), both.resolve(self.TEXT, "theirs"))
        self.assertEqual(("a\nours\ntheirs\nb\n", 1), both.resolve(self.TEXT, "ours-theirs"))
        self.assertEqual(("a\ntheirs\n\nours\nb\n", 1), both.resolve(self.TEXT, "theirs-ours", sep=True))

    def test_diff3_base_is_dropped(self):
        text = "<<<<<<< HEAD\nours\n||||||| base\nold\n=======\ntheirs\n>>>>>>> abc\n"
        self.assertEqual(("ours\ntheirs\n", 1), both.resolve(text, "ours-theirs"))

    def test_a_longer_run_of_equals_is_content(self):
        text = "<<<<<<< HEAD\n========\n=======\ntheirs\n>>>>>>> abc\n"
        self.assertEqual(("========\n", 1), both.resolve(text, "ours"))

    def test_block_selection_keeps_the_others(self):
        text = self.TEXT + self.TEXT
        resolved, n = both.resolve(text, "theirs", only={1})
        self.assertEqual(1, n)
        self.assertEqual(self.TEXT + "a\ntheirs\nb\n", resolved)
        with self.assertRaises(IndexError):
            both.resolve(text, "theirs", only={2})

    def test_unterminated_block_is_malformed(self):
        with self.assertRaises(both.Malformed):
            both.resolve("<<<<<<< HEAD\nours\n=======\ntheirs\n", "ours")
        with self.assertRaises(both.Malformed):
            both.resolve("<<<<<<< HEAD\nours\n>>>>>>> abc\n", "ours")

    def test_cli_exit_codes(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "f.txt"
            path.write_text("clean\n")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, both.main([str(path), "ours"]))
                path.write_text("<<<<<<< HEAD\nx\n")
                self.assertEqual(2, both.main([str(path), "ours"]))
            path.write_text(self.TEXT)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, both.main([str(path), "theirs"]))
            self.assertEqual("a\ntheirs\nb\n", path.read_text())


class HelperTest(unittest.TestCase):
    def test_cherry_plus(self):
        text = "- 1111111 merged already\n+ 2222222 fix: not merged\n+ 3333333\n"
        self.assertEqual([("2222222", "fix: not merged"), ("3333333", "")], sync.cherry_plus(text))

    def test_tier2_globs_reads_only_its_block(self):
        md = "## Settings\n\n```\nbase: x\n```\n\n## Tier 2 paths\n\n```\na/**\n\n**/*.kts\n```\n\nprose\n\n" \
             "## Probes\n\n```\nnot/a/glob\n```\n"
        self.assertEqual(["a/**", "**/*.kts"], sync.tier2_globs(md))
        self.assertEqual([], sync.tier2_globs("## Tier 2 paths\n\nnone\n\n## Probes\n\n```\nnot/a/glob\n```\n"))

    def test_theme_assignment(self):
        themes = CONFIG["themes"]
        self.assertEqual("datasource-aar", sync.theme_of("app/libs/lib-datasource-release.aar", themes))
        self.assertEqual("thumbs-memory", sync.theme_of("app/src/main/java/x/MemoryBudget.kt", themes))
        self.assertEqual("i18n-docs", sync.theme_of("app/src/main/res/values-pt/strings.xml", themes))
        self.assertEqual("app-other", sync.theme_of("app/src/main/java/x/data/repository/MetaRepositoryImpl.kt", themes))
        player = ["app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt"]
        self.assertEqual("player-runtime", sync.commit_theme(player, themes, "fix: something"))
        self.assertEqual("video-dv", sync.commit_theme(player, themes, "feat(dv): hardware DV decoders first"))
        self.assertEqual("build-infra", sync.commit_theme(["README.md"], themes, "ci: register workflows"))

    def test_scale_and_group_merge(self):
        scale = CONFIG["scale"]
        self.assertEqual("skip", sync.scale_tier(0, 0, scale))
        self.assertEqual("single", sync.scale_tier(8, 3, scale))
        self.assertEqual("fanout", sync.scale_tier(9, 0, scale))
        self.assertEqual("fanout", sync.scale_tier(1, 4, scale))
        light = CONFIG["light"]
        self.assertEqual("light", sync.scale_tier(48, 2, scale, light))
        self.assertEqual("skip", sync.scale_tier(0, 0, scale, light, ["upstream changes app/libs/x.aar"]))
        self.assertEqual("fanout", sync.scale_tier(48, 2, scale, light, ["a blocker"]))
        self.assertEqual("fanout", sync.scale_tier(48, 4, scale, light))

    def test_path_matches_tier2_globs(self):
        self.assertTrue(sync.path_matches(".github/workflows/checks.yml", ".github/workflows/**"))
        self.assertTrue(sync.path_matches("app/src/main/java/a/PlayerSettingsDataStore.kt", "**/*DataStore.kt"))
        self.assertTrue(sync.path_matches("DeviceProfile.kt", "**/DeviceProfile.kt"))
        self.assertTrue(sync.path_matches("app/build.gradle.kts", "**/*.gradle.kts"))
        self.assertFalse(sync.path_matches("app/src/a/b.kt", "app/src/*.kt"))
        self.assertTrue(sync.path_matches("app/src/x/iec/Sink.kt", "app/src/x/iec/**"))

    def test_light_blockers_name_every_reason(self):
        features, light = CONFIG["protected_features"], CONFIG["light"]
        prci = "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt"
        store = "app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt"
        dts = {"sha": "d2b07e187aec", "files": [prci], "protected": "aac-to-dts"}
        plain = {"sha": "4f5d7705acf8", "files": [prci, store], "protected": None}
        self.assertEqual([], sync.light_blockers([], {"app/a.kt"}, {"app/a.kt"}, [plain], features, light, []))
        b = sync.light_blockers([prci], {prci, store, ".github/workflows/x.yml", "app/libs/a.aar"}, {prci, store},
                                [dts, plain], features, light, ["app/src/main/java/com/nuvio/tv/data/local/*DataStore.kt"])
        text = "\n".join(b)
        self.assertIn(f"protected commits touch the conflicting file {prci}: d2b07e187", text)
        self.assertIn(f"upstream changes {store}", text)
        self.assertIn("upstream changes .github/workflows/x.yml", text)
        self.assertIn("upstream changes app/libs/a.aar", text)
        self.assertIn(f"both sides change the Tier 2 path {store}", text)
        b = sync.light_blockers(["ffmpeg-decoder-downmix/src/main/jni/dcaxll/a.c", "b", "c", "d"], set(), set(), [],
                                features, light, [])
        self.assertIn("4 files conflict in a trial merge (light limit 3)", b)
        self.assertIn("conflict in a protected file (aac-to-dts): ffmpeg-decoder-downmix/src/main/jni/dcaxll/a.c", b)

    def test_every_settings_store_blocks_the_light_path(self):
        local = SKILL.parents[2] / "app/src/main/java/com/nuvio/tv/data/local"
        stores = sorted(str(p.relative_to(SKILL.parents[2])) for p in local.glob("*.kt") if "DataStore" in p.read_text())
        self.assertIn("app/src/main/java/com/nuvio/tv/data/local/DeviceUiPreferenceStore.kt", stores)
        self.assertIn("app/src/main/java/com/nuvio/tv/data/local/DeviceLocalPlayerPreferences.kt", stores)
        for path in stores:
            b = sync.light_blockers([], {path}, set(), [], CONFIG["protected_features"], CONFIG["light"], [])
            self.assertEqual([f"upstream changes {path}"], b, path)

    def test_every_settings_store_is_a_tier2_path(self):
        root = SKILL.parents[2]
        tier2 = sync.tier2_globs((root / ".claude/workflow.md").read_text())
        local = root / "app/src/main/java/com/nuvio/tv/data/local"
        stores = sorted(str(p.relative_to(root)) for p in local.glob("*.kt") if "DataStore" in p.read_text())
        missing = [p for p in stores if not any(sync.path_matches(p, g) for g in tier2)]
        self.assertEqual([], missing, "a settings store that is not a Tier 2 path gets a Tier 1 review")

    def test_a_version_bump_is_benign(self):
        pats = CONFIG["light"]["benign_tier2_lines"]
        self.assertTrue(sync.benign_change(['        versionCode = 1538', '        versionName = "1.1.0-nt.8.2"'], pats))
        self.assertFalse(sync.benign_change(['        versionCode = 1538', '        minSdk = 26'], pats))
        self.assertFalse(sync.benign_change([], pats))
        gradle = "app/build.gradle.kts"
        args = ([], {gradle}, {gradle}, [], CONFIG["protected_features"], CONFIG["light"], ["**/*.gradle.kts"])
        self.assertEqual([], sync.light_blockers(*args, benign={gradle}))
        self.assertEqual([f"both sides change the Tier 2 path {gradle}"], sync.light_blockers(*args))

    def test_stop_findings(self):
        features, light = CONFIG["protected_features"], CONFIG["light"]
        self.assertEqual([], sync.stop_findings(["app/a.kt"], ["app/a.kt"], "fix: a", features, light, []))
        f = sync.stop_findings(["app/a.kt"], ["app/a.kt"], "feat(audio): Transcode AAC to DTS picks DTS-HD MA",
                               features, light, [])
        self.assertTrue(any("protected feature aac-to-dts" in x for x in f), f)
        f = sync.stop_findings(["app/libs/lib-decoder-ffmpeg-release.aar"], [], "build: x", features, light, [])
        self.assertTrue(any("protected file (aac-to-dts)" in x for x in f), f)
        self.assertTrue(any("light-blocked path" in x for x in f), f)
        f = sync.stop_findings(["d"], ["d"], "fix: d", features, light, ["a", "b", "c"])
        self.assertIn("4 files have conflicted so far (light limit 3)", f)
        groups = [{"key": k, "focus": k, "commits": [k] * n, "files": [k]} for k, n in (("a", 1), ("b", 2), ("c", 5))]
        merged = sync.merge_groups(groups, 2)
        self.assertEqual(2, len(merged))
        self.assertEqual(["a+b", "c"], sorted(g["key"] for g in merged))
        self.assertEqual(3, next(len(g["commits"]) for g in merged if g["key"] == "a+b"))

    def test_todo_edit(self):
        todo = "pick 1234567aa fix: one\npick 89abcdef0 fix: two\n# comment\n"
        text, missing = sync.todo_edit(todo, ["1234567aabbcc", "89abcde"])
        self.assertEqual("drop 1234567aa fix: one\ndrop 89abcdef0 fix: two\n# comment\n", text)
        self.assertEqual([], missing)
        self.assertEqual(["fffffff"], sync.todo_edit(todo, ["fffffff"])[1])
        self.assertEqual(["123"], sync.todo_edit(todo, ["123"])[1], "a prefix shorter than 7 never matches")

    def test_parse_range_diff(self):
        text = (" 1:  aaaaaaa =  1:  bbbbbbb ci: one\n"
                " 2:  ccccccc !  2:  ddddddd fix: two\n"
                "    @@ File.kt\n"
                "    -    old context\n"
                "    --        removed(x)\n"
                "    -+        added(y)\n"
                " 3:  eeeeeee <  -:  ------- fix: dropped\n"
                " -:  ------- >  3:  fffffff fix: new\n")
        entries = sync.parse_range_diff(text)
        self.assertEqual(["=", "!", "<", ">"], [e["mark"] for e in entries])
        self.assertEqual(["--        removed(x)", "-+        added(y)"], entries[1]["changed"])
        self.assertIsNone(entries[2]["new"])
        self.assertIsNone(entries[3]["old"])
        self.assertEqual("fix: dropped", entries[2]["subject"])

    def test_parse_merge_tree(self):
        self.assertEqual([], sync.parse_merge_tree("abc123\n", 0))
        self.assertEqual(["a.kt", "b.xml"], sync.parse_merge_tree("abc123\nb.xml\na.kt\na.kt\n", 1))
        with self.assertRaises(RuntimeError):
            sync.parse_merge_tree("", 128)

    def test_duplicate_strings_within_one_values_directory(self):
        moved = '<resources><string name="search_row_updating">A</string><string name="x">X</string>' \
                '<string name="search_row_updating">A</string></resources>'
        problems = sync.duplicate_names({"res/values-pt/strings.xml": moved})
        self.assertEqual(1, len(problems))
        self.assertIn("search_row_updating", problems[0])
        other_qualifier = {"res/values/strings.xml": '<resources><string name="x">X</string></resources>',
                           "res/values-de/strings.xml": '<resources><string name="x">Y</string></resources>'}
        self.assertEqual([], sync.duplicate_names(other_qualifier))
        two_files = {"res/values-hu/string.xml": '<resources><string name="x">1</string></resources>',
                     "res/values-hu/strings.xml": '<resources><string name="x">2</string></resources>'}
        self.assertEqual(1, len(sync.duplicate_names(two_files)))
        self.assertIn("does not parse", sync.duplicate_names({"res/values/a.xml": "<resources>"})[0])

    def test_decisions_apply_drops_and_only_hint_the_rest(self):
        classify = {"groups": [{"commits": [
            {"sha": "aaaaaaa", "verdict": "drop-superseded"},
            {"sha": "bbbbbbb", "verdict": "partial-superseded", "conflict_files": ["x.kt"]},
            {"sha": "ccccccc", "verdict": "keep-adapt"},
            {"sha": "ddddddd", "verdict": "keep-clean"},
        ]}, None]}
        drops, hints = sync.decisions(classify)
        self.assertEqual(["aaaaaaa"], drops)
        self.assertEqual(["bbbbbbb"], sync.decisions({"summary": {"drop": ["bbbbbbb"]}, "groups": classify["groups"]})[0],
                         "the workflow's cautious summary wins over per-group verdicts")
        self.assertEqual(2, len(hints))
        self.assertIn("--allow-same", hints[0])
        self.assertIn("x.kt", hints[0])
        self.assertIn("--allow-changed", hints[1])


def run(*args, cwd, env=None):
    subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True, env=env)


class TestFailuresTest(unittest.TestCase):
    OOM = ('<testsuite name="s"><testcase classname="a.B" name="big">'
           '<failure message="java.lang.OutOfMemoryError: Java heap space" type="java.lang.OutOfMemoryError">'
           'at a.B.big</failure></testcase><testcase classname="a.B" name="ok"/>'
           '<system-out><![CDATA[]]></system-out><system-err><![CDATA[]]></system-err></testsuite>')
    PLAIN = '<testsuite name="s"><testcase classname="a.C" name="bad"><failure message="boom">x</failure></testcase></testsuite>'
    PRINTS = ('<testsuite name="s"><testcase classname="a.D" name="logs"/><system-out>OutOfMemoryError was simulated'
              '</system-out></testsuite>')
    IN_STDERR = ('<testsuite name="s"><testcase classname="a.E" name="ok"/><system-err>Exception in thread "x" '
                 'java.lang.OutOfMemoryError: Java heap space</system-err></testsuite>')

    def failures(self, *xml):
        with tempfile.TemporaryDirectory() as d:
            for i, text in enumerate(xml):
                (Path(d) / f"TEST-{i}.xml").write_text(text)
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = sync.cmd_test_failures(argparse.Namespace(dir=d), CONFIG)
        return code, stdout.getvalue(), stderr.getvalue()

    def test_stdout_stays_the_list_of_failures(self):
        code, stdout, _ = self.failures(self.OOM, self.PLAIN)
        self.assertEqual(sync.OK, code)
        self.assertEqual("a.B.big\na.C.bad\n", stdout)

    def test_an_out_of_memory_failure_prints_the_hint(self):
        _, _, stderr = self.failures(self.OOM, self.PLAIN)
        self.assertEqual(1, len(stderr.strip().splitlines()))
        self.assertIn("OutOfMemoryError in 1 result file(s)", stderr)
        self.assertIn("maxHeapSize in app/build.gradle.kts", stderr)
        self.assertIn("'The unit test JVM'", stderr)

    def test_an_error_on_a_background_thread_counts(self):
        self.assertIn("OutOfMemoryError", self.failures(self.IN_STDERR)[2])

    def test_other_results_print_no_hint(self):
        self.assertEqual("", self.failures(self.PLAIN)[2])
        self.assertEqual("", self.failures(self.PRINTS)[2], "a test's own stdout is not an error")

    def test_the_hint_points_at_things_that_exist(self):
        root = SKILL.parents[2]
        self.assertIn("maxHeapSize", (root / "app/build.gradle.kts").read_text())
        self.assertTrue(any(t.startswith("The unit test JVM") for t in CONFIG["traps"]))


class ProtectedHelperTest(unittest.TestCase):
    def test_added_lines_per_file_skips_short_lines(self):
        diff = ("diff --git a/x.kt b/x.kt\n--- a/x.kt\n+++ b/x.kt\n@@ -1,0 +2,3 @@\n+val keep = 1\n+}\n+  \n"
                "diff --git a/gone.kt b/gone.kt\n--- a/gone.kt\n+++ /dev/null\n@@ -1 +0,0 @@\n-val old = 1\n")
        self.assertEqual({"x.kt": ["val keep = 1"]}, sync.added_lines(diff))

    def test_feature_of_reads_the_anchors(self):
        self.assertEqual("aac-to-dts", sync.feature_of(
            ["ffmpeg-decoder-downmix/src/main/jni/dcaxll/dcaxll_enc.c"], CONFIG["protected_features"]))
        self.assertEqual("hlg-to-dv", sync.feature_of(
            ["app/src/main/java/com/nuvio/tv/core/player/HlgContract.kt"], CONFIG["protected_features"]))
        self.assertIsNone(sync.feature_of(["README.md"], CONFIG["protected_features"]))
        self.assertEqual("aac-to-dts", sync.feature_of(
            ["app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt"], CONFIG["protected_features"],
            'feat(audio): store a per-profile "Transcode AAC to DTS" setting'))
        self.assertIsNone(sync.feature_of(["app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt"],
                                          CONFIG["protected_features"],
                                          "feat(audio): native TrueHD/DTS-HD passthrough by default on Nvidia"))


class ProtectedApkTest(unittest.TestCase):
    def check(self, members):
        import zipfile
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "app.apk"
            with zipfile.ZipFile(apk, "w") as z:
                for name, data in members.items():
                    z.writestr(name, data)
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                code = sync.cmd_protected_apk(argparse.Namespace(apk=str(apk), stamp=None), CONFIG)
            return code, buf.getvalue()

    def test_both_libraries_with_their_markers_pass(self):
        code, report = self.check({"lib/arm64-v8a/libffmpegJNI.so": b"..dcaxll_encode..",
                                   "lib/arm64-v8a/libnuvio_rpu.so": b"..Java_com_nuvio..."})
        self.assertEqual(0, code, report)

    def test_a_missing_library_or_marker_fails(self):
        code, report = self.check({"lib/arm64-v8a/libffmpegJNI.so": b"no writer here"})
        self.assertEqual(1, code)
        self.assertIn("aac-to-dts: lib/arm64-v8a/libffmpegJNI.so has no dcaxll", report)
        self.assertIn("hlg-to-dv: lib/arm64-v8a/libnuvio_rpu.so is not in the APK", report)


class RepoFixture:
    """A small repository: base B, upstream U on top of B, an old fork on B and a new one on U."""

    LINES = [f"line {n}" for n in range(1, 21)]

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.cwd = os.getcwd()
        os.chdir(self.dir)
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
        # Background maintenance puts a lock under .git/objects while a test lists that directory.
        run("git", "config", "maintenance.auto", "false", cwd=self.dir)
        self.files = {"shared.txt": list(self.LINES), "fork-only.txt": list(self.LINES), "upstream.txt": ["u"]}
        self.commit("base", {})
        run("git", "tag", "B", cwd=self.dir)
        self.commit("upstream change", {"shared.txt": {21: "upstream line"}, "upstream.txt": {1: "u2"}})
        run("git", "tag", "U", cwd=self.dir)
        self.upstream_files = {k: list(v) for k, v in self.files.items()}
        run("git", "checkout", "-q", "-b", "old", "B", cwd=self.dir)
        self.files = {"shared.txt": list(self.LINES), "fork-only.txt": list(self.LINES), "upstream.txt": ["u"]}
        self.commit("fix: superseded top", {"shared.txt": {1: "TOP"}})
        self.commit("fix: retained bottom", {"shared.txt": {20: "BOTTOM"}, "fork-only.txt": {10: "line 10 fork"}})
        self.cfg = dict(CONFIG, pinned_binaries={})

    def tearDown(self):
        os.chdir(self.cwd)
        self.tmp.cleanup()

    def commit(self, msg, edits):
        """edits: {path: {line number (1-based): text}}; a number past the end appends."""
        for path, changes in edits.items():
            lines = self.files[path]
            for n, text in sorted(changes.items()):
                if n > len(lines):
                    lines.append(text)
                else:
                    lines[n - 1] = text
        for path, lines in self.files.items():
            (self.dir / path).write_text("\n".join(lines) + "\n")
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "--allow-empty", "-m", msg, cwd=self.dir)

    def new_branch(self, commits):
        run("git", "checkout", "-q", "-B", "new", "U", cwd=self.dir)
        self.files = {k: list(v) for k, v in self.upstream_files.items()}
        for subject, edits in commits:
            self.commit(subject, edits)

    FAITHFUL = [("fix: superseded top", {"shared.txt": {1: "TOP"}}),
                ("fix: retained bottom", {"shared.txt": {20: "BOTTOM"}, "fork-only.txt": {10: "line 10 fork"}})]

    def sha(self, rev):
        return subprocess.run(["git", "rev-parse", rev], cwd=self.dir, capture_output=True, text=True).stdout.strip()



class VerifyRepoTest(RepoFixture, unittest.TestCase):
    """verify against the RepoFixture repository."""

    def verify(self, **extra):
        a = argparse.Namespace(base_old="B", old="old", base_new="U", new="new", upstream="U",
                               decisions=None, expect_drop=None, allow_same=None, allow_changed=None)
        for k, v in extra.items():
            setattr(a, k, v)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = sync.cmd_verify(a, self.cfg)
        return code, buf.getvalue()

    def test_a_faithful_rebase_passes(self):
        self.new_branch(self.FAITHFUL)
        code, report = self.verify()
        self.assertEqual(0, code, report)
        self.assertIn("0 dropped, 0 new", report)
        self.assertIn("all checks pass", report)

    def test_a_shared_file_back_to_upstream_fails_even_when_one_commit_was_superseded(self):
        # Both fork edits to shared.txt are gone. The first commit was partly superseded, but the second one is
        # retained, so its lost change fails unless the owner waives exactly this path.
        self.new_branch([("fix: superseded top", {"shared.txt": {1: "TOP"}}),
                         ("fix: retained bottom", {"shared.txt": {1: "line 1"}, "fork-only.txt": {10: "line 10 fork"}})])
        code, report = self.verify()
        self.assertEqual(1, code)
        self.assertIn("fork change lost: shared.txt", report)
        code, report = self.verify(allow_same=["shared.txt"])
        self.assertEqual(0, code, report)
        self.assertIn("waived by --allow-same: shared.txt", report)

    def test_a_dropped_commit_must_be_expected(self):
        self.new_branch(self.FAITHFUL[1:])
        code, report = self.verify()
        self.assertEqual(1, code)
        self.assertIn("dropped without --expect-drop", report)
        code, report = self.verify(expect_drop=self.sha("old~1")[:9])
        self.assertEqual(0, code, report)

    def test_a_new_commit_must_be_allowed(self):
        self.new_branch(self.FAITHFUL + [("test: fix a race upstream's test has on CI", {"upstream.txt": {2: "race fix"}})])
        code, report = self.verify()
        self.assertEqual(1, code)
        self.assertIn("without --allow-new", report)
        code, report = self.verify(allow_new=self.sha("new")[:9])
        self.assertEqual(0, code, report)
        self.assertIn("new commit added on purpose", report)
        code, report = self.verify(allow_new=self.sha("new")[:9] + "," + self.sha("old")[:9])
        self.assertEqual(1, code)
        self.assertIn("is not a new commit", report)

    def test_decisions_supply_the_drop(self):
        self.new_branch(self.FAITHFUL[1:])
        path = self.dir / "classify.json"
        path.write_text(json.dumps({"groups": [{"commits": [{"sha": self.sha("old~1")[:12], "verdict": "drop-superseded"}]}]}))
        code, report = self.verify(decisions=str(path))
        self.assertEqual(0, code, report)
        self.assertIn("expected drop from the classify decisions", report)

    def test_an_approved_retire_in_summary_drop_reaches_drops_and_verify(self):
        # The analysts said keep, so only the hand edit of summary.drop carries the owner's decision.
        self.new_branch(self.FAITHFUL[1:])
        sha = self.sha("old~1")[:12]
        path = self.dir / "classify.json"
        path.write_text(json.dumps({"groups": [{"commits": [{"sha": sha, "verdict": "keep-clean"}]}],
                                    "summary": {"drop": [sha], "keepClean": []}}))
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            sync.cmd_drops(argparse.Namespace(decisions=str(path), range="B..old"), self.cfg)
        self.assertEqual(sha, buf.getvalue().strip())
        code, report = self.verify(decisions=str(path))
        self.assertEqual(0, code, report)
        self.assertIn("expected drop from the classify decisions", report)

    def test_a_changed_fork_only_file_fails_unless_allowed(self):
        self.new_branch([self.FAITHFUL[0],
                         ("fix: retained bottom", {"shared.txt": {20: "BOTTOM"}, "fork-only.txt": {10: "line 10 adapted"}})])
        code, report = self.verify()
        self.assertEqual(1, code)
        self.assertIn("fork-only file changed: fork-only.txt", report)
        code, report = self.verify(allow_changed=["fork-only.txt"])
        self.assertEqual(0, code, report)

    def test_everything_lost_fails(self):
        run("git", "checkout", "-q", "-B", "new", "U", cwd=self.dir)
        code, report = self.verify()
        self.assertEqual(1, code)
        self.assertIn("2 dropped", report)

    def test_conflict_markers_and_pinned_binaries(self):
        self.new_branch([self.FAITHFUL[0], ("fix: retained bottom", {
            "shared.txt": {20: "BOTTOM", 5: "<<<<<<< HEAD", 6: "=======", 7: ">>>>>>> abc"},
            "fork-only.txt": {10: "line 10 fork"}})])
        self.cfg["pinned_binaries"] = {"fork-only.txt": "0" * 64}
        code, report = self.verify()
        self.assertEqual(1, code)
        self.assertIn("conflict marker", report)
        self.assertIn("fork-only.txt: sha256", report)


    def test_docs_entry_reads_what_verify_prints(self):
        # Producer and parser side by side: a changed verify line must fail here, not in a follow-up PR.
        self.cfg["protected_features"] = self.PROTECTED
        self.new_branch(self.FAITHFUL + [("test: a fix", {"upstream.txt": {2: "fix"}})])
        code, report = self.verify(allow_new=self.sha("new")[:9], allow_same=["shared.txt"])
        self.assertEqual(0, code, report)
        facts = tools.parse_verify_report(report)
        self.assertEqual(("old", "new"), (facts["old"], facts["new"]))
        self.assertEqual(("1", "1", "0", "1"), (facts["identical"], facts["changed"], facts["dropped"], facts["added"]))
        self.assertEqual([("feat", "1", "1", "0")], facts["protected"])
        self.assertEqual(1, len(facts["waived"]))
        self.assertEqual(1, len(facts["new_commits"]))
        self.assertTrue(facts["passed"])

    def test_a_decided_drop_outside_the_range_is_only_a_note(self):
        self.new_branch(self.FAITHFUL)
        path = self.dir / "classify.json"
        path.write_text(json.dumps({"summary": {"drop": ["abcdef0123"]}, "groups": []}))
        code, report = self.verify(decisions=str(path))
        self.assertEqual(0, code, report)
        self.assertIn("decided drop abcdef0123 is not in B..old", report)

    def test_drops_prints_only_the_drops_in_the_range(self):
        path = self.dir / "classify.json"
        path.write_text(json.dumps({"summary": {"drop": [self.sha("old~1")[:9], "abcdef0123"]}, "groups": []}))
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = sync.cmd_drops(argparse.Namespace(decisions=str(path), range="B..old"), self.cfg)
        self.assertEqual(0, code)
        self.assertEqual(self.sha("old~1")[:9], buf.getvalue().strip())

    def test_a_file_touched_by_a_dropped_commit_names_it(self):
        run("git", "checkout", "-q", "-B", "old2", "B", cwd=self.dir)
        self.files = {"shared.txt": list(self.LINES), "fork-only.txt": list(self.LINES), "upstream.txt": ["u"]}
        self.commit("fix: dropped one", {"fork-only.txt": {2: "dropped edit"}})
        self.commit("fix: kept one", {"fork-only.txt": {15: "kept edit"}})
        self.new_branch([("fix: kept one", {"fork-only.txt": {15: "kept edit"}})])
        code, report = self.verify(old="old2", expect_drop=self.sha("old2~1")[:9])
        self.assertEqual(1, code)
        self.assertIn("also touched it", report)

    PROTECTED = [{"key": "feat", "name": "a feature", "anchors": ["^fork-only"], "binaries": []}]

    def test_a_protected_feature_keeps_its_lines_in_a_shared_file(self):
        self.cfg["protected_features"] = self.PROTECTED
        self.new_branch(self.FAITHFUL)
        code, report = self.verify()
        self.assertEqual(0, code, report)
        self.assertIn("- feat: 1 commits, 2 lines checked, 0 lost", report)

    def test_a_protected_line_lost_in_a_shared_file_fails(self):
        # shared.txt still differs from upstream (TOP survives), so only the protected check sees BOTTOM go.
        self.cfg["protected_features"] = self.PROTECTED
        self.new_branch([self.FAITHFUL[0], ("fix: retained bottom", {"fork-only.txt": {10: "line 10 fork"}})])
        code, report = self.verify()
        self.assertEqual(1, code)
        self.assertIn("protected feature feat: shared.txt lost 1 line(s)", report)
        self.assertIn("`BOTTOM`", report)

    def test_a_dropped_protected_commit_needs_the_owner(self):
        self.cfg["protected_features"] = self.PROTECTED
        self.new_branch(self.FAITHFUL[:1])
        drop = self.sha("old")[:9]
        code, report = self.verify(expect_drop=drop, allow_same=["shared.txt"])
        self.assertEqual(1, code)
        self.assertIn(f"pass --protected-drop {drop}", report)
        code, report = self.verify(expect_drop=drop, allow_same=["shared.txt"], protected_drop=drop)
        self.assertEqual(0, code, report)
        self.assertIn("protected drop approved by the owner", report)

    def test_a_protected_binary_must_stay(self):
        self.cfg["protected_features"] = [dict(self.PROTECTED[0], binaries=["fork-only.txt"])]
        self.new_branch([self.FAITHFUL[0], ("fix: retained bottom", {
            "shared.txt": {20: "BOTTOM"}, "fork-only.txt": {10: "line 10 fork", 11: "extra"}})])
        code, report = self.verify(allow_changed=["fork-only.txt"])
        self.assertEqual(0, code, report)
        code, report = self.verify()
        self.assertEqual(1, code)
        self.assertIn("binary fork-only.txt differs", report)


class PreflightRepoTest(RepoFixture, unittest.TestCase):
    """preflight on one layer: origin/main mirrors upstream at the base, origin/perf/shield-pass is the fork."""

    def setUp(self):
        super().setUp()
        self.new_branch(self.FAITHFUL)
        self.calls = []
        self.pulls = []
        self.gh = sync.gh_json
        sync.gh_json = self.fake_gh
        self.cfg = dict(self.cfg, issue_workflows=[])

    def tearDown(self):
        sync.gh_json = self.gh
        super().tearDown()

    def fake_gh(self, *args):
        self.calls.append(args)
        if args[0] == "api" and "/pulls?" in args[1]:
            return self.pulls if "base=perf/shield-pass&" in args[1] else []
        return []

    def refs(self, main, perf, upstream="U"):
        for name, rev in (("refs/remotes/origin/main", main), ("refs/remotes/origin/perf/shield-pass", perf),
                          ("refs/remotes/upstream/main", upstream), ("refs/heads/main", main)):
            run("git", "update-ref", name, self.sha(rev), cwd=self.dir)

    def preflight(self):
        a = argparse.Namespace(no_fetch=True, json=False, allow_branch=["old", "new"])
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = sync.cmd_preflight(a, self.cfg)
        return code, buf.getvalue()

    def test_a_level_fork_has_nothing_to_do_and_asks_rest_for_pull_requests(self):
        self.refs(main="U", perf="new")
        code, report = self.preflight()
        self.assertEqual(sync.NOTHING, code, report)
        self.assertIn(("api", f"repos/{self.cfg['repo']}/pulls?state=open&base=perf/shield-pass&per_page=100"),
                      self.calls)
        self.assertFalse(any(c[0] == "pr" for c in self.calls), "gh pr uses GraphQL, which cloud sessions block")

    def test_main_outside_upstream_blocks(self):
        self.refs(main="old", perf="new")
        code, report = self.preflight()
        self.assertEqual(sync.FAIL, code)
        self.assertIn("origin/main is not in upstream/main", report)

    def test_an_open_pull_request_into_perf_blocks(self):
        self.refs(main="U", perf="new")
        self.pulls = [{"number": 7, "head": {"ref": "fork/x"}}]
        code, report = self.preflight()
        self.assertEqual(sync.FAIL, code)
        self.assertIn("PR #7 (fork/x) is open into perf/shield-pass", report)

    def test_a_fork_behind_upstream_is_go(self):
        self.refs(main="B", perf="old")
        code, report = self.preflight()
        self.assertEqual(sync.OK, code, report)
        self.assertIn("perf/shield-pass: 1 behind upstream/main", report)

    def test_the_due_line_is_printed_and_a_wait_does_not_block(self):
        self.refs(main="B", perf="old")
        code, report = self.preflight()
        self.assertEqual(sync.OK, code, report)
        self.assertIn("due: wait:", report)

    def test_a_level_fork_prints_level_and_keeps_its_exit_code(self):
        self.refs(main="U", perf="new")
        code, report = self.preflight()
        self.assertEqual(sync.NOTHING, code, report)
        self.assertIn("due: level:", report)

    def test_a_failure_to_judge_does_not_block(self):
        self.refs(main="B", perf="old")
        real = tools.due_report
        tools.due_report = lambda *a: (_ for _ in ()).throw(RuntimeError("no dates"))
        try:
            code, report = self.preflight()
        finally:
            tools.due_report = real
        self.assertEqual(sync.OK, code, report)
        self.assertIn("due: not available (no dates)", report)


class SkillDocTest(unittest.TestCase):
    """The publish command in SKILL.md, which no script runs."""

    def test_main_lease_is_origin_main_even_when_main_is_not_at_the_base(self):
        # Preflight only warns when origin/main is behind the fork's base. A lease on that base instead of the
        # fetched origin/main makes the atomic publish push fail as stale although nothing moved.
        doc = (SKILL / "SKILL.md").read_text()
        var = re.search(r"--force-with-lease=refs/heads/main:<(\w+)>", doc).group(1)
        self.assertIn(f"`{var}` (`origin/main`", doc)
        self.assertNotRegex(doc, rf"as `{var}` instead")

    def doc(self):
        return (SKILL / "SKILL.md").read_text()

    def section(self, heading):
        return self.doc().split(f"## {heading}")[1].split("\n## ")[0]

    def test_the_trigger_rule_heads_the_skill(self):
        self.assertRegex(self.doc().split("## Rules")[0], r"\*\*Trigger:\*\* sync when `sync\.py due` says `due`, and at least weekly")

    def test_the_apk_is_not_called_the_only_aapt_run(self):
        # The gate's task graph runs mergeFullDebugResources and processFullDebugResources, and verify reads the locale
        # files itself, so a sentence that sends a reader to the APK for the locale trap is wrong.
        self.assertNotRegex(self.doc(), r"only check that runs aapt")
        self.assertIn("`duplicate_names`", self.section("6. Verify"))
        self.assertIn("processFullDebugResources", self.section("6. Verify"))

    def test_the_follow_up_pr_stays_on_gate_skip_paths(self):
        carried = self.section("9. Follow-ups").split("It carries:")[1].split("Two things stay out")[0]
        paths = re.findall(r"^- `([^`]+)`", carried, re.M)
        self.assertEqual(["docs/shield-todo.md", "ROADMAP.md", "docs/ROADMAP_LOG.md"], paths)
        globs = re.search(r"^gate-skip:\s+(.*)$", (SKILL.parents[2] / ".claude/workflow.md").read_text(), re.M).group(1)
        self.assertEqual({"*.md", "docs/**"}, set(re.findall(r"'([^']+)'", globs)))
        self.assertTrue(all(p.endswith(".md") for p in paths))

    def test_the_rebase_contract_is_written_down(self):
        rebase = self.section("5. Rebase")
        for needle in ("one fresh subagent", "**Inputs:**", "`resolution_guidance`", "**Outputs:**", "`resolutions.md`",
                       "branch head", "**Block check", "no leftover markers", "no two identical adjacent multi-line blocks"):
            self.assertIn(needle, rebase)

    def test_the_retire_question_keeps_the_protected_exception(self):
        for text in (CONFIG["owner_rule"], self.section("4. Classify (skip for tiers `skip` and `light`)")):
            self.assertRegex(text, r"(?i)retire question")
            self.assertRegex(text, r"prefer dropping the fork's version")
            self.assertRegex(text, r"(?i)protected")

    def test_a_retire_is_asked_before_the_owner_sees_the_drops_and_lands_in_summary_drop(self):
        # sync.py drops reads only summary.drop, so a retire decided after the owner's list never reaches the rebase.
        classify = self.section("4. Classify (skip for tiers `skip` and `light`)")
        self.assertLess(classify.index("Ask the retire question"), classify.index("Show the owner the drop list"))
        self.assertRegex(classify, r"retire .*`summary\.drop` in `<scratch>/classify\.json`")
        self.assertRegex(classify, r"`sync\.py drops` and `verify --decisions`")
        self.assertNotIn("goes to the owner in item 5", classify)
        self.assertEqual(["1", "2", "3", "4", "5", "6", "7", "8"], re.findall(r"^(\d)\. ", classify, re.M))

    def test_step_numbers_in_the_verify_section_run_in_order(self):
        numbers = re.findall(r"^(\d)\. ", self.section("6. Verify"), re.M)
        self.assertEqual(["1", "2", "3", "4", "5", "6"], numbers)
        self.assertIn("the review in step 6.6", self.section("6. Verify"))


class DriftWorkflowTest(unittest.TestCase):
    """The prediction in .github/workflows/upstream-drift.yml, which no local test can run as a job."""

    TEXT = (SKILL.parents[2] / ".github/workflows/upstream-drift.yml").read_text()

    def step(self, name):
        return self.TEXT.split(f"- name: {name}\n")[1].split("\n      - name: ")[0]

    def test_a_failure_in_the_prediction_cannot_fail_the_job(self):
        step = self.step("Predict the sync")
        self.assertIn("continue-on-error: true", step)
        self.assertNotIn("set -e", step)

    def test_the_prediction_stays_under_the_job_timeout(self):
        job = int(re.search(r"^    timeout-minutes: (\d+)$", self.TEXT, re.M).group(1))
        step = int(re.search(r"timeout-minutes: (\d+)", self.step("Predict the sync")).group(1))
        self.assertLess(step, job - 5, "the comparison and the issue update need time after it")
        seconds = sum(int(n) for n in re.findall(r"timeout (\d+) python3", self.step("Predict the sync")))
        self.assertLessEqual(seconds, step * 60)

    def test_the_commands_exist(self):
        for command in re.findall(r'python3 -I "\$S" ([a-z-]+)', self.step("Predict the sync")):
            self.assertIn(command, tools.COMMANDS)

    def test_a_missing_result_reads_not_available(self):
        compare = self.step("Compare")
        self.assertIn('echo "not available"', compare)
        self.assertRegex(compare, r'block "\$RUNNER_TEMP/due\.txt"')
        self.assertRegex(compare, r'block "\$RUNNER_TEMP/dry-run\.txt"')

    def test_the_ages_in_the_issue_state_when_they_were_measured(self):
        # Hours pass with no edit, so the body must say which moment "quiet" and "oldest" belong to.
        step = self.step("Predict the sync")
        self.assertRegex(step, r"""date -u '\+%Y-%m-%d %H:%M UTC'""")
        self.assertRegex(step, r"measured at .* >> \"\$RUNNER_TEMP/due\.txt\"")
        script = step.split("run: |\n")[1]
        with tempfile.TemporaryDirectory() as d:
            bin_dir = Path(d) / "bin"
            bin_dir.mkdir()
            fake = bin_dir / "python3"
            fake.write_text('#!/bin/sh\ncase "$*" in *" due "*) echo "due: wait: a reason";; esac\n')
            fake.chmod(0o755)
            git_fake = bin_dir / "git"
            git_fake.write_text("#!/bin/sh\necho 0\n")
            git_fake.chmod(0o755)
            timeout = bin_dir / "timeout"
            timeout.write_text('#!/bin/sh\nshift\nexec "$@"\n')
            timeout.chmod(0o755)
            env = {**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}", "RUNNER_TEMP": d, "FORK_REF": "x"}
            subprocess.run(["bash", "-c", script], env=env, check=True)
            first, second = (Path(d) / "due.txt").read_text().splitlines()
        self.assertEqual("due: wait: a reason", first)  # The Compare step reads the verdict from the first line.
        self.assertRegex(second, r"^measured at \d{4}-\d\d-\d\d \d\d:\d\d UTC")

    def test_a_verdict_alone_can_edit_the_issue(self):
        # The issue is edited only when its first line changes, and "wait" turns into "due" with no push at all.
        self.assertIn("due=$verdict replay=$replay -->", self.step("Compare"))
        self.assertIn('sed -n 1p "$BODY"', self.step("Update the drift issue"))


class GateSkipTest(unittest.TestCase):
    """gate-skip decides whether a pull request runs the suite, and must agree with /ship's contract."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.addCleanup(os.chdir, os.getcwd())
        os.chdir(self.dir)
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
        (self.dir / ".claude").mkdir()
        contract = (SKILL.parents[1] / "workflow.md").read_text()
        (self.dir / ".claude/workflow.md").write_text(contract)
        self.write({"app/Main.kt": "fun main() {}\n" * 20, "README.md": "r\n", "docs/a/b.txt": "b\n"})
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", "base", cwd=self.dir)

    def write(self, files):
        for path, text in files.items():
            (self.dir / path).parent.mkdir(parents=True, exist_ok=True)
            (self.dir / path).write_text(text)

    def verdict(self):
        """The verdict on everything staged, committed the way a pull request would add it."""
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "--allow-empty", "-m", "change", cwd=self.dir)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = sync.main(["gate-skip", "HEAD^1", "HEAD"])
        self.assertEqual(sync.OK, code)
        return buf.getvalue().strip()

    def test_the_contract_line_is_parsed(self):
        self.assertEqual(["*.md", "docs/**"], sync.gate_skip_specs("x\ngate-skip:      '*.md' 'docs/**'\ny\n"))
        with self.assertRaises(RuntimeError):
            sync.gate_skip_specs("gate: x\n")

    def test_markdown_anywhere_and_files_under_docs_skip(self):
        self.write({"README.md": "new\n", "app/deep/NOTES.md": "n\n", "docs/a/b.txt": "new\n", "docs/c.kt": "c\n"})
        self.assertEqual("skip=true", self.verdict())

    def test_one_source_file_runs_the_suite(self):
        self.write({"README.md": "new\n", "app/Main.kt": "changed\n"})
        self.assertEqual("skip=false", self.verdict())

    def test_a_name_that_only_looks_like_docs_runs_the_suite(self):
        self.write({"docs.txt": "x\n", "app/docs/x.kt": "x\n"})
        self.assertEqual("skip=false", self.verdict())

    def test_a_source_file_moved_into_docs_runs_the_suite(self):
        (self.dir / "docs/Main.kt").write_text((self.dir / "app/Main.kt").read_text())
        (self.dir / "app/Main.kt").unlink()
        self.assertEqual("skip=false", self.verdict())

    def test_deleting_a_source_file_runs_the_suite(self):
        (self.dir / "app/Main.kt").unlink()
        self.assertEqual("skip=false", self.verdict())

    def test_an_empty_change_runs_the_suite(self):
        self.assertEqual("skip=false", self.verdict())

    def test_a_pull_request_cannot_widen_the_line_that_judges_it(self):
        contract = self.dir / ".claude/workflow.md"
        contract.write_text(contract.read_text().replace("gate-skip:      '*.md'", "gate-skip:      '*.kt' '*.md'"))
        self.write({"app/Main.kt": "changed\n"})
        self.assertEqual("skip=false", self.verdict())

    def test_the_checkouts_contract_counts_for_nothing(self):
        self.write({"app/Main.kt": "changed\n"})
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", "source only", cwd=self.dir)
        contract = self.dir / ".claude/workflow.md"
        contract.write_text(contract.read_text().replace("gate-skip:      '*.md'", "gate-skip:      '*.kt' '*.md'"))
        with contextlib.redirect_stdout(io.StringIO()) as buf:
            sync.main(["gate-skip", "HEAD^1", "HEAD"])
        self.assertEqual("skip=false\n", buf.getvalue())

    def test_the_files_that_run_the_gate_never_skip_it(self):
        for path in (".github/NOTES.md", ".claude/NOTES.md", "scripts/NOTES.md"):
            with self.subTest(path):
                self.write({path: "n\n"})
                self.assertEqual("skip=false", self.verdict())

    def test_a_name_that_only_looks_guarded_still_skips(self):
        self.write({"app/scripts/NOTES.md": "n\n", "docs/.github/x.txt": "n\n"})
        self.assertEqual("skip=true", self.verdict())

    def test_a_bad_ref_fails_so_the_workflow_runs_the_suite(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
            code = sync.main(["gate-skip", "no-such-ref", "HEAD"])
        self.assertEqual(sync.USAGE, code)
        self.assertEqual("", buf.getvalue())


class GateSkipStepTest(unittest.TestCase):
    """The detection step's own text, on the merge commit a pull_request checkout leaves."""

    STEP = re.search(r"- name: Detect a docs-only change\n.*?run: \|\n((?: {10}[^\n]*\n)+)",
                     (SKILL.parents[2] / ".github/workflows/checks.yml").read_text(), re.S).group(1)
    SCRIPT = ".claude/skills/upstream-sync/scripts/sync.py"

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.temp = Path(tmp.name) / "runner-temp"
        self.temp.mkdir()
        self.dir = Path(tmp.name) / "repo"
        self.dir.mkdir()
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
        self.write({"app/Main.kt": "fun main() {}\n", "README.md": "r\n"})

    def write(self, files):
        for path, text in files.items():
            (self.dir / path).parent.mkdir(parents=True, exist_ok=True)
            (self.dir / path).write_text(text)

    def base_with_detector(self, script=None):
        self.write({".claude/workflow.md": (SKILL.parents[1] / "workflow.md").read_text(),
                    ".claude/skills/upstream-sync/sync.json": (SKILL / "sync.json").read_text(),
                    self.SCRIPT: script or (SKILL / "scripts/sync.py").read_text()})

    def detect(self, pull_request):
        """Commit the base, merge the pull request into it, and run the step: (exit code, $GITHUB_OUTPUT)."""
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", "base", cwd=self.dir)
        run("git", "checkout", "-q", "-b", "pr", cwd=self.dir)
        self.write(pull_request)
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", "change", cwd=self.dir)
        run("git", "checkout", "-q", "main", cwd=self.dir)
        run("git", "merge", "-q", "--no-ff", "-m", "merge", "pr", cwd=self.dir)
        output = self.temp.parent / "github-output"
        output.write_text("")
        r = subprocess.run(["bash", "-e", "-c", re.sub(r"(?m)^ {10}", "", self.STEP)], cwd=self.dir, capture_output=True,
                           text=True, env=dict(os.environ, RUNNER_TEMP=str(self.temp), GITHUB_OUTPUT=str(output)))
        return r.returncode, output.read_text()

    def test_a_docs_only_pull_request_skips(self):
        self.base_with_detector()
        self.assertEqual((0, "skip=true\n"), self.detect({"README.md": "new\n"}))

    def test_a_source_change_runs_the_suite(self):
        self.base_with_detector()
        self.assertEqual((0, "skip=false\n"), self.detect({"app/Main.kt": "changed\n"}))

    def test_a_detector_replaced_by_the_pull_request_is_not_the_one_that_runs(self):
        self.base_with_detector()
        code, output = self.detect({self.SCRIPT: "print('skip=true')\n", "app/Main.kt": "changed\n"})
        self.assertEqual((0, "skip=false\n"), (code, output))

    def test_a_widened_gate_skip_line_runs_the_suite(self):
        self.base_with_detector()
        contract = (SKILL.parents[1] / "workflow.md").read_text()
        widened = contract.replace("gate-skip:      '*.md'", "gate-skip:      '*.kt' '*.md'")
        self.assertNotEqual(contract, widened)
        self.assertEqual((0, "skip=false\n"), self.detect({".claude/workflow.md": widened, "app/Main.kt": "changed\n"}))

    def test_a_base_that_has_no_such_command_leaves_the_output_empty(self):
        self.base_with_detector((SKILL / "scripts/sync.py").read_text().replace('"gate-skip"', '"older-command"'))
        code, output = self.detect({"README.md": "new\n"})
        self.assertNotEqual(0, code)
        self.assertEqual("", output)

    def test_a_base_that_has_no_skill_leaves_the_output_empty(self):
        code, output = self.detect({"README.md": "new\n"})
        self.assertNotEqual(0, code)
        self.assertEqual("", output)


class ChecksWorkflowTest(unittest.TestCase):
    """The parts of checks.yml that /ship depends on and no job can test."""

    TEXT = (SKILL.parents[2] / ".github/workflows/checks.yml").read_text()

    def test_a_docs_only_pull_request_still_reports_the_check(self):
        self.assertIn("    name: Unit tests\n", self.TEXT)
        self.assertNotIn("paths-ignore:", re.sub(r"^#.*$", "", self.TEXT, flags=re.M))
        self.assertNotRegex(self.TEXT, r"(?m)^\s+paths:")

    def test_detection_cannot_fail_the_job_or_skip_a_dispatch(self):
        step = re.search(r"- name: Detect a docs-only change\n(.*?)\n\n", self.TEXT, re.S).group(1)
        self.assertIn("if: github.event_name == 'pull_request'", step)
        self.assertIn("continue-on-error: true", step)


    def test_a_sync_dispatch_leaves_the_verdict_to_the_compare_job(self):
        sync_branch = "github.event_name == 'workflow_dispatch' && startsWith(github.ref, 'refs/heads/sync/')"
        tests = re.search(r"- name: Run all unit tests\n(.*?)\n\n", self.TEXT, re.S).group(1)
        self.assertIn(f"continue-on-error: ${{{{ {sync_branch} }}}}", tests)
        self.assertIn(f"if: ${{{{ !cancelled() && {sync_branch} }}}}", self.TEXT)
        self.assertIn("needs: [unit-tests, baseline]", self.TEXT)

    def test_the_cache_keeps_only_a_baseline_that_was_checked(self):
        save = re.search(r"- name: Keep the baseline for a rerun\n(.*?)\n\n", self.TEXT, re.S).group(1)
        self.assertIn("steps.complete.outcome == 'success'", save)
        keys = re.findall(r"key: (\S+)", self.TEXT)
        self.assertEqual(2, len(keys))
        self.assertEqual(keys[0], keys[1])
        self.assertEqual({"baseline-results"}, set(re.findall(r"path: (baseline-results)$", self.TEXT, re.M)))

    def test_the_upstream_sha_reaches_shell_code_only_through_the_environment(self):
        for script in re.findall(r"run: (\|\n(?:\s{10}.*\n)+|.*)", self.TEXT):
            self.assertNotIn("inputs.", script)


class ResultsFixture:
    FIRST, OTHER = "com.nuvio.FirstTest", "com.nuvio.OtherTest"

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)

    def results(self, name, classes, failed=(), sha="s", gradle_exit=None, crashed=False, source_classes=None):
        """Write the directory ci_unit_tests.sh leaves. classes: names, failed: 'class.case' entries."""
        d = self.dir / name
        d.mkdir()
        for cls in classes:
            cases = [f.rsplit(".", 1)[1] for f in failed if f.rsplit(".", 1)[0] == cls]
            body = "".join(f'<testcase name="{c}" classname="{cls}"><failure>x</failure></testcase>' for c in cases)
            (d / f"TEST-{cls}.xml").write_text(f'<testsuite name="{cls}"><testcase name="ok" classname="{cls}"/>{body}</testsuite>')
        code = (1 if failed else 0) if gradle_exit is None else gradle_exit
        (d / "meta.json").write_text(json.dumps({
            "sha": sha, "gradle_exit": code, "crashed": crashed,
            "source_classes": len(classes) if source_classes is None else source_classes}))
        return str(d)

    def compare(self, baseline, candidate):
        return sync.compare_results(baseline, "s", candidate, "s")


class CompareResultsTest(ResultsFixture, unittest.TestCase):
    def test_a_clean_run_on_both_sides_passes(self):
        self.assertEqual(([], [], []), self.compare(self.results("b", [self.FIRST]), self.results("c", [self.FIRST])))

    def test_a_new_failure_fails(self):
        new, problems, _ = self.compare(self.results("b", [self.FIRST]),
                                        self.results("c", [self.FIRST], [f"{self.FIRST}.broken"]))
        self.assertEqual([f"{self.FIRST}.broken"], new)
        self.assertEqual([], problems)

    def test_a_failure_in_a_class_upstream_lacks_fails(self):
        new, _, _ = self.compare(self.results("b", [self.FIRST]),
                                 self.results("c", [self.FIRST, self.OTHER], [f"{self.OTHER}.broken"]))
        self.assertEqual([f"{self.OTHER}.broken"], new)

    def test_a_failure_upstream_also_has_passes_and_is_named(self):
        known = f"{self.FIRST}.known"
        new, problems, notes = self.compare(self.results("b", [self.FIRST], [known]),
                                            self.results("c", [self.FIRST], [known]))
        self.assertEqual(([], []), (new, problems))
        self.assertEqual([f"known failure, upstream has it too: {known}"], notes)

    def test_a_crashed_candidate_fails_whatever_xml_it_left(self):
        _, problems, _ = self.compare(self.results("b", [self.FIRST]),
                                      self.results("c", [self.FIRST], gradle_exit=1, crashed=True))
        self.assertTrue(any("crashed" in p for p in problems), problems)

    def test_a_crashed_baseline_fails_too(self):
        _, problems, _ = self.compare(self.results("b", [self.FIRST], gradle_exit=1, crashed=True),
                                      self.results("c", [self.FIRST]))
        self.assertTrue(any("crashed" in p for p in problems), problems)

    def test_a_crash_that_also_has_a_failed_test_still_counts_as_a_crash(self):
        known = f"{self.FIRST}.known"
        _, problems, _ = self.compare(self.results("b", [self.FIRST], [known]),
                                      self.results("c", [self.FIRST], [known], crashed=True))
        self.assertTrue(any("crashed" in p for p in problems), problems)

    def test_gradle_failing_with_no_failed_test_is_incomplete(self):
        # The only trace of a build error, or of a crash with an unknown message.
        _, problems, _ = self.compare(self.results("b", [self.FIRST]), self.results("c", [self.FIRST], gradle_exit=1))
        self.assertTrue(any("no test failed" in p for p in problems), problems)

    def test_no_xml_is_incomplete(self):
        _, problems, _ = self.compare(self.results("b", [self.FIRST]),
                                      self.results("c", [], gradle_exit=1, crashed=True, source_classes=9))
        self.assertTrue(any("no JUnit XML" in p for p in problems), problems)

    def test_a_missing_directory_fails(self):
        _, problems, _ = self.compare(self.results("b", [self.FIRST]), str(self.dir / "absent"))
        self.assertEqual([f"{self.dir / 'absent'} is missing"], [p for p in problems if "is missing" in p])

    def test_a_missing_meta_file_fails(self):
        candidate = self.results("c", [self.FIRST])
        (Path(candidate) / "meta.json").unlink()
        _, problems, _ = self.compare(self.results("b", [self.FIRST]), candidate)
        self.assertTrue(any("did not finish" in p for p in problems), problems)

    def test_a_truncated_xml_file_fails(self):
        candidate = self.results("c", [self.FIRST, self.OTHER])
        (Path(candidate) / f"TEST-{self.OTHER}.xml").write_text("<testsuite><testc")
        _, problems, _ = self.compare(self.results("b", [self.FIRST]), candidate)
        self.assertTrue(any("not readable XML" in p for p in problems), problems)

    def test_a_cached_result_of_another_commit_fails(self):
        _, problems, _ = self.compare(self.results("b", [self.FIRST], sha="old"), self.results("c", [self.FIRST]))
        self.assertTrue(any("expected s" in p for p in problems), problems)

    def test_fewer_classes_than_test_files_by_a_clear_margin_fails(self):
        classes = [f"com.nuvio.T{n}Test" for n in range(90)]
        _, problems, _ = self.compare(self.results("b", classes), self.results("c", classes, source_classes=100))
        self.assertTrue(any("90 test classes for 100 test files" in p for p in problems), problems)

    def test_a_small_shortfall_passes(self):
        classes = [f"com.nuvio.T{n}Test" for n in range(96)]
        self.assertEqual(([], []), self.compare(self.results("b", classes),
                                                self.results("c", classes, source_classes=100))[:2])

    def test_many_upstream_classes_absent_from_the_candidate_fail(self):
        classes = [f"com.nuvio.T{n}Test" for n in range(20)]
        _, problems, _ = self.compare(self.results("b", classes), self.results("c", classes[:15]))
        self.assertTrue(any("5 of 20 upstream test classes" in p for p in problems), problems)

    def test_one_upstream_class_absent_from_the_candidate_is_only_a_note(self):
        classes = [f"com.nuvio.T{n}Test" for n in range(30)]
        new, problems, notes = self.compare(self.results("b", classes), self.results("c", classes[:29]))
        self.assertEqual(([], []), (new, problems))
        self.assertIn("com.nuvio.T29Test", notes[0])

    def test_the_command_exits_1_on_a_new_failure_and_0_when_clean(self):
        baseline = self.results("b", [self.FIRST])
        args = ["compare-results", "--baseline", baseline, "--baseline-sha", "s", "--candidate-sha", "s"]
        with contextlib.redirect_stdout(io.StringIO()) as buf:
            self.assertEqual(sync.OK, sync.main([*args, "--candidate", self.results("c", [self.FIRST])]))
            self.assertEqual(sync.FAIL, sync.main(
                [*args, "--candidate", self.results("d", [self.FIRST], [f"{self.FIRST}.broken"])]))
        self.assertIn(f"FAIL: new failure: {self.FIRST}.broken", buf.getvalue())

    def test_results_complete_judges_one_side(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(sync.OK, sync.main(["results-complete", self.results("b", [self.FIRST]), "--sha", "s"]))
            self.assertEqual(sync.FAIL, sync.main(["results-complete", str(self.dir / "absent"), "--sha", "s"]))


class CiUnitTestsScriptTest(unittest.TestCase):
    """scripts/ci_unit_tests.sh over a fake gradlew, from a tree under test to a verdict."""

    SCRIPT = SKILL.parents[2] / "scripts/ci_unit_tests.sh"
    XML = '<testsuite name="com.nuvio.ATest"><testcase name="a" classname="com.nuvio.ATest"/></testsuite>'

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
        (self.dir / "app/src/test/java").mkdir(parents=True)
        (self.dir / "app/src/test/java/ATest.kt").write_text("class ATest\n")
        (self.dir / "app/src/test/java/Helper.kt").write_text("object Helper\n")

    def gate(self, gradlew):
        """Run the script over a gradlew body -> (exit code, the verdict on its results)."""
        (self.dir / "gradlew").write_text("#!/bin/sh\n" + gradlew)
        (self.dir / "gradlew").chmod(0o755)
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", "c", cwd=self.dir)
        code = subprocess.run([self.SCRIPT, "out"], cwd=self.dir, capture_output=True).returncode
        sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.dir, capture_output=True, text=True).stdout.strip()
        return code, sync.load_results(self.dir / "out", sha)

    def writes_xml(self):
        results = self.dir / "app/build/test-results/testFullDebugUnitTest"
        return f"mkdir -p {results}\ncat > {results}/TEST-a.xml <<'X'\n{self.XML}\nX\n"

    def test_a_clean_run_records_a_complete_result(self):
        code, (classes, failures, problems) = self.gate(self.writes_xml())
        self.assertEqual((0, {"com.nuvio.ATest"}, [], []), (code, classes, failures, problems))
        self.assertEqual(1, json.loads((self.dir / "out/meta.json").read_text())["source_classes"])

    def test_a_crashed_test_jvm_is_recorded_and_keeps_gradles_exit_code(self):
        code, (_, _, problems) = self.gate(
            "echo \"> Process 'Gradle Test Executor 1' finished with non-zero exit value 3\"\nexit 1\n")
        self.assertEqual(1, code)
        self.assertTrue(any("crashed" in p for p in problems), problems)
        self.assertTrue(any("no JUnit XML" in p for p in problems), problems)

    def test_a_build_error_is_incomplete(self):
        code, (_, _, problems) = self.gate("echo 'Compilation error'\nexit 1\n")
        self.assertEqual(1, code)
        self.assertTrue(any("no test failed" in p for p in problems), problems)

    def test_the_log_is_kept(self):
        self.gate(self.writes_xml() + "echo from-gradle\n")
        self.assertIn("from-gradle", (self.dir / "out/gradle.log").read_text())


class NativeProvenanceTest(unittest.TestCase):
    """protected-apk --stamp in a small repository, with fixture zips."""

    RPU, FFMPEG = b"..Java_..", b"..dcaxll.."

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.addCleanup(os.chdir, os.getcwd())
        os.chdir(self.dir)
        patcher = mock.patch.dict(os.environ)
        patcher.start()
        self.addCleanup(patcher.stop)
        for name in ("USE_LOCAL_FFMPEG_DECODER", "FFMPEG_BUILD_DIR", "FFMPEG_SOURCE_DIR"):
            os.environ.pop(name, None)
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
        for path in ("native/rpu_builder/lib.rs", f"{sync.FFMPEG_JNI}/ffmpeg_jni.cc"):
            (self.dir / path).parent.mkdir(parents=True, exist_ok=True)
            (self.dir / path).write_text("v1\n")
        (self.dir / "app/libs").mkdir(parents=True)
        with zipfile.ZipFile(self.dir / sync.FFMPEG_AAR, "w") as z:
            z.writestr(sync.FFMPEG_AAR_LIB, self.FFMPEG)
        self.commit()
        self.stamp(packaged_sha256=sync.sha256(self.RPU))

    def commit(self):
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", "c", cwd=self.dir)

    def stamp(self, **fields):
        tree = subprocess.run(["git", "rev-parse", "HEAD:native/rpu_builder"], cwd=self.dir,
                              capture_output=True, text=True).stdout.strip()
        (self.dir / "lib.stamp").write_text(json.dumps(
            {"tree": tree, "dirty": False, "sha256": "raw", "packaged_sha256": "stripped", **fields}))

    def check(self, rpu=RPU, ffmpeg=FFMPEG):
        with zipfile.ZipFile(self.dir / "app.apk", "w") as z:
            z.writestr(sync.RPU_LIB, rpu)
            z.writestr(sync.FFMPEG_LIB, ffmpeg)
        with contextlib.redirect_stdout(io.StringIO()) as buf:
            code = sync.main(["protected-apk", str(self.dir / "app.apk"), "--stamp", str(self.dir / "lib.stamp")])
        return code, buf.getvalue()

    def local_module(self, static_libs=sync.FFMPEG_STATIC_LIBS):
        build = self.dir / "ffmpeg-build"
        (build / "arm64-v8a").mkdir(parents=True)
        for name in static_libs:
            (build / "arm64-v8a" / name).write_bytes(name.encode())
        (self.dir / "local.properties").write_text(f"USE_LOCAL_FFMPEG_DECODER=true\nFFMPEG_BUILD_DIR={build}\n")

    def test_the_stamped_build_of_head_passes(self):
        code, report = self.check()
        self.assertEqual(0, code, report)
        self.assertIn("is the stamped build of native/rpu_builder", report)

    def test_a_library_packaged_unstripped_also_matches(self):
        self.stamp(sha256=sync.sha256(self.RPU), packaged_sha256="stripped")
        self.assertEqual(0, self.check()[0])

    def test_another_library_with_the_same_markers_fails(self):
        code, report = self.check(rpu=b"..Java_.. older build")
        self.assertEqual(sync.ORIGIN_MISMATCH, code)
        self.assertIn("is not the stamped build", report)

    def test_a_stamp_from_older_source_fails(self):
        (self.dir / "native/rpu_builder/lib.rs").write_text("v2\n")
        self.commit()
        code, report = self.check()
        self.assertEqual(sync.ORIGIN_MISMATCH, code)
        self.assertRegex(report, r"built from native/rpu_builder tree \w+, HEAD has \w+")

    def test_a_library_built_from_uncommitted_source_fails(self):
        self.stamp(dirty=True, packaged_sha256=sync.sha256(self.RPU))
        code, report = self.check()
        self.assertEqual(sync.ORIGIN_MISMATCH, code)
        self.assertIn("uncommitted changes", report)

    def test_a_missing_or_broken_stamp_fails(self):
        (self.dir / "lib.stamp").write_text("{not json")
        self.assertIn("no readable stamp", self.check()[1])
        (self.dir / "lib.stamp").unlink()
        code, report = self.check()
        self.assertEqual(sync.ORIGIN_MISMATCH, code)
        self.assertIn("no readable stamp", report)

    def test_the_marker_check_stays_with_a_good_stamp(self):
        self.stamp(packaged_sha256=sync.sha256(b"no markers"))
        code, report = self.check(rpu=b"no markers")
        self.assertEqual(1, code)
        self.assertIn("has no Java_", report)

    def test_a_lost_marker_outranks_an_origin_mismatch(self):
        # The override follows the exit code, so a report with both must not read as an origin problem.
        code, report = self.check(ffmpeg=b"no writer", rpu=b"no markers")
        self.assertEqual(sync.FAIL, code)
        self.assertIn("is not the stamped build", report)
        self.assertIn("has no dcaxll", report)

    def test_a_missing_library_outranks_an_origin_mismatch(self):
        (self.dir / "lib.stamp").unlink()
        with zipfile.ZipFile(self.dir / "app.apk", "w") as z:
            z.writestr(sync.RPU_LIB, self.RPU)
        with contextlib.redirect_stdout(io.StringIO()):
            code = sync.main(["protected-apk", str(self.dir / "app.apk"), "--stamp", str(self.dir / "lib.stamp")])
        self.assertEqual(sync.FAIL, code)

    def test_without_the_option_nothing_of_this_runs(self):
        with zipfile.ZipFile(self.dir / "app.apk", "w") as z:
            z.writestr(sync.RPU_LIB, self.RPU)
            z.writestr(sync.FFMPEG_LIB, b"..dcaxll.. other")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, sync.main(["protected-apk", str(self.dir / "app.apk")]))

    def test_the_prebuilt_aar_is_named_with_its_hash(self):
        code, report = self.check()
        aar = sync.sha256((self.dir / sync.FFMPEG_AAR).read_bytes())
        self.assertEqual(0, code, report)
        self.assertIn(f"FFmpeg is the prebuilt AAR, {sync.FFMPEG_AAR} sha256 {aar}", report)

    def test_a_library_that_is_not_the_aars_needs_the_local_module_switched_on(self):
        code, report = self.check(ffmpeg=b"..dcaxll.. built locally")
        self.assertEqual(sync.ORIGIN_MISMATCH, code)
        self.assertIn("USE_LOCAL_FFMPEG_DECODER is off", report)

    def test_the_local_module_reports_its_inputs(self):
        self.local_module()
        code, report = self.check(ffmpeg=b"..dcaxll.. built locally")
        self.assertEqual(0, code, report)
        self.assertIn("FFmpeg is the local module", report)
        self.assertRegex(report, r"jni tree [0-9a-f]{40},")
        self.assertIn(f"libavcodec.a sha256 {sync.sha256(b'libavcodec.a')}", report)
        self.assertIn("libswresample.a sha256", report)

    def test_uncommitted_jni_source_is_named(self):
        self.local_module()
        (self.dir / sync.FFMPEG_JNI / "ffmpeg_jni.cc").write_text("v2\n")
        self.assertRegex(self.check(ffmpeg=b"..dcaxll.. local")[1], r"jni tree \w+ \(dirty\)")

    def test_the_local_module_without_its_static_libraries_fails(self):
        self.local_module(static_libs=("libavcodec.a", "libavutil.a"))
        code, report = self.check(ffmpeg=b"..dcaxll.. local")
        self.assertEqual(sync.ORIGIN_MISMATCH, code)
        self.assertIn("libswresample.a", report)

    def test_the_setting_on_with_the_aars_library_in_the_apk_fails(self):
        # What a stale merged library or a -P override looks like: the setting and the bytes disagree.
        self.local_module()
        code, report = self.check()
        self.assertEqual(sync.ORIGIN_MISMATCH, code)
        self.assertIn("the APK has the prebuilt AAR's library", report)

    def test_the_setting_is_read_the_way_gradle_reads_it(self):
        on = {"USE_LOCAL_FFMPEG_DECODER": "true"}
        self.assertTrue(sync.wants_local_ffmpeg({}, on))
        self.assertTrue(sync.wants_local_ffmpeg({"USE_LOCAL_FFMPEG_DECODER": "YES"}, {}))
        self.assertTrue(sync.wants_local_ffmpeg({"USE_LOCAL_FFMPEG_DECODER": "1"}, {}))
        self.assertFalse(sync.wants_local_ffmpeg({"USE_LOCAL_FFMPEG_DECODER": "false"}, on))
        self.assertFalse(sync.wants_local_ffmpeg({"USE_LOCAL_FFMPEG_DECODER": ""}, on))
        self.assertFalse(sync.wants_local_ffmpeg({}, {"USE_LOCAL_FFMPEG_DECODER": "on"}))
        self.assertFalse(sync.wants_local_ffmpeg({}, {}))

    def test_properties_skip_comments(self):
        self.assertEqual({"A": "1", "b.c": "x y"}, sync.java_properties("# A=2\n! A=3\nA = 1\n\nb.c: x y\n"))


class BuildRpuScriptTest(unittest.TestCase):
    """build_rpu_builder.sh over a fake cargo and NDK: the stamp it writes."""

    REPO = SKILL.parents[1].parent

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name) / "tree"
        (self.dir / "scripts").mkdir(parents=True)
        script = self.dir / "scripts/build_rpu_builder.sh"
        script.write_bytes((self.REPO / "scripts/build_rpu_builder.sh").read_bytes())
        script.chmod(0o755)
        (self.dir / "native/rpu_builder").mkdir(parents=True)
        (self.dir / "native/rpu_builder/lib.rs").write_text("v1\n")
        (self.dir / "app").mkdir()
        (self.dir / "app/build.gradle.kts").write_text('    ndkVersion = "1.2.3"\n')
        self.lib = self.dir / "app/src/main/jniLibs/arm64-v8a/libnuvio_rpu.so"
        (self.dir / ".gitignore").write_text("/app/src/main/jniLibs/\n")
        sdk = Path(tmp.name) / "sdk"
        tools = sdk / "ndk/1.2.3/toolchains/llvm/prebuilt/fake/bin"
        tools.mkdir(parents=True)
        self.tool(tools / "llvm-readelf", "echo '  LOAD 0 0 0 0 0 RW 0x4000'\n")
        self.tool(tools / "llvm-strip", 'cat "$4" > "$3"\necho stripped >> "$3"\n')
        self.tool(Path(tmp.name) / "cargo", f'[ "$2" = --version ] && exit 0\nmkdir -p {self.lib.parent}\necho library > {self.lib}\n')
        self.env = dict(os.environ, ANDROID_HOME=str(sdk), PATH=f"{tmp.name}:{os.environ['PATH']}")
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", "c", cwd=self.dir)

    def tool(self, path, body):
        path.write_text("#!/bin/sh\n" + body)
        path.chmod(0o755)

    def build(self):
        r = subprocess.run(["scripts/build_rpu_builder.sh"], cwd=self.dir, env=self.env, capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        return json.loads(Path(f"{self.lib}.stamp").read_text())

    def test_the_stamp_names_the_tree_and_both_forms_of_the_library(self):
        stamp = self.build()
        tree = subprocess.run(["git", "rev-parse", "HEAD:native/rpu_builder"], cwd=self.dir,
                              capture_output=True, text=True).stdout.strip()
        self.assertEqual({"tree": tree, "dirty": False, "sha256": sync.sha256(b"library\n"),
                          "packaged_sha256": sync.sha256(b"library\nstripped\n")}, stamp)

    def test_uncommitted_source_is_recorded(self):
        (self.dir / "native/rpu_builder/lib.rs").write_text("v2\n")
        self.assertTrue(self.build()["dirty"])

    def test_an_untracked_source_file_is_recorded(self):
        (self.dir / "native/rpu_builder/new.rs").write_text("x\n")
        self.assertTrue(self.build()["dirty"])


@unittest.skipUnless(Path("/usr/libexec/java_home").exists(), "the script finds JDK 17 through macOS java_home")
class ShieldBuildScriptTest(unittest.TestCase):
    """shield_build_install.sh in a throwaway tree, over a fake gradlew and Android tools. Never --install."""

    GOOD = {"lib/arm64-v8a/libffmpegJNI.so": b"..dcaxll..", "lib/arm64-v8a/libnuvio_rpu.so": b"..Java_.."}
    REPO = SKILL.parents[1].parent

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name) / "tree"
        (self.dir / "scripts").mkdir(parents=True)
        (self.dir / "scripts/shield_build_install.sh").write_bytes((self.REPO / "scripts/shield_build_install.sh").read_bytes())
        (self.dir / "scripts/shield_build_install.sh").chmod(0o755)
        skill = self.dir / ".claude/skills/upstream-sync"
        (skill / "scripts").mkdir(parents=True)
        (skill / "sync.json").write_text((SKILL / "sync.json").read_text())
        (skill / "scripts/sync.py").write_text((SKILL / "scripts/sync.py").read_text())
        self.home = Path(tmp.name) / "home"
        self.home.mkdir()
        self.bin = Path(tmp.name) / "bin"
        self.bin.mkdir()
        self.tool(self.bin / "cargo", "exit 1\n")      # no cargo-ndk
        tools = Path(tmp.name) / "sdk/build-tools/35.0.0"
        tools.mkdir(parents=True)
        self.tool(tools / "aapt2", "echo \"package: name='com.example.exp' versionCode='1'\"\n")
        self.tool(tools / "apksigner", "echo 'Signer #1 certificate SHA-256 digest: abc'\n")
        self.apk = self.dir.parent / "fixture.apk"
        target = "app/build/outputs/apk/full/debug"
        self.tool(self.dir / "gradlew", f"mkdir -p {target}\ncp {self.apk} {target}/app-full-arm64-v8a-debug.apk\n")
        self.env = dict(os.environ, HOME=str(self.home), ANDROID_HOME=str(tools.parents[1]),
                        PATH=f"{self.bin}:{os.environ['PATH']}")
        for name in ("NUVIO_DEBUG_STORE_FILE", "USE_LOCAL_FFMPEG_DECODER", "NUVIO_ALLOW_NATIVE_MISMATCH"):
            self.env.pop(name, None)
        (self.dir / "native/rpu_builder").mkdir(parents=True)
        (self.dir / "native/rpu_builder/lib.rs").write_text("v1\n")
        (self.dir / "app/libs").mkdir(parents=True)
        with zipfile.ZipFile(self.dir / sync.FFMPEG_AAR, "w") as z:
            z.writestr(sync.FFMPEG_AAR_LIB, self.GOOD[sync.FFMPEG_LIB])
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", "c", cwd=self.dir)
        self.lib = self.dir / "app/src/main/jniLibs/arm64-v8a/libnuvio_rpu.so"
        self.lib.parent.mkdir(parents=True)
        self.lib.write_bytes(self.GOOD[sync.RPU_LIB])
        tree = subprocess.run(["git", "rev-parse", "HEAD:native/rpu_builder"], cwd=self.dir,
                              capture_output=True, text=True).stdout.strip()
        (self.dir / f"{self.lib}.stamp").write_text(json.dumps(
            {"tree": tree, "dirty": False, "sha256": "raw", "packaged_sha256": sync.sha256(self.GOOD[sync.RPU_LIB])}))

    def tool(self, path, body):
        path.write_text("#!/bin/sh\n" + body)
        path.chmod(0o755)

    def build(self, members, **env):
        with zipfile.ZipFile(self.apk, "w") as z:
            for name, data in members.items():
                z.writestr(name, data)
        return subprocess.run(["scripts/shield_build_install.sh", "--suffix", ".exp", "--allow-empty-config"],
                              cwd=self.dir, env={**self.env, **env}, capture_output=True, text=True)

    def logged(self):
        log = self.dir / ".local/shield-builds.tsv"
        return log.read_text() if log.exists() else ""

    def test_a_good_apk_is_built_and_logged(self):
        r = self.build(self.GOOD)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("hlg-to-dv: lib/arm64-v8a/libnuvio_rpu.so", r.stdout)
        self.assertIn("com.example.exp", self.logged())

    def test_an_apk_without_a_protected_library_stops_before_the_log(self):
        r = self.build({"lib/arm64-v8a/libffmpegJNI.so": b"..dcaxll.."})
        self.assertEqual(1, r.returncode, r.stdout + r.stderr)
        self.assertIn("FAIL: hlg-to-dv: lib/arm64-v8a/libnuvio_rpu.so is not in the APK", r.stdout)
        self.assertEqual("", self.logged())

    def test_an_old_library_built_from_older_source_stops_the_build_and_says_so(self):
        # The case this stamp exists for: no cargo-ndk, so the script packages what is there.
        (self.dir / "native/rpu_builder/lib.rs").write_text("v2\n")
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", "new source", cwd=self.dir)
        r = self.build(self.GOOD)
        self.assertEqual(1, r.returncode, r.stdout + r.stderr)
        self.assertIn("packaging the existing", r.stderr)
        self.assertIn("HEAD has", r.stdout)
        self.assertIn("NUVIO_ALLOW_NATIVE_MISMATCH=1", r.stderr)
        self.assertEqual("", self.logged())

    def test_the_override_keeps_that_build_and_says_it_did(self):
        (self.dir / f"{self.lib}.stamp").unlink()
        r = self.build(self.GOOD, NUVIO_ALLOW_NATIVE_MISMATCH="1")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("FAIL: hlg-to-dv: no readable stamp", r.stdout)
        self.assertIn("keeping this build because NUVIO_ALLOW_NATIVE_MISMATCH=1", r.stderr)
        self.assertIn("com.example.exp", self.logged())

    def assert_the_override_does_not_keep(self, members=GOOD):
        r = self.build(members, NUVIO_ALLOW_NATIVE_MISMATCH="1")
        self.assertEqual(1, r.returncode, r.stdout + r.stderr)
        self.assertNotIn("keeping this build", r.stderr)
        self.assertNotIn("NUVIO_ALLOW_NATIVE_MISMATCH=1", r.stderr)
        self.assertEqual("", self.logged())
        return r

    def test_the_override_does_not_keep_a_lost_marker(self):
        r = self.assert_the_override_does_not_keep({**self.GOOD, sync.FFMPEG_LIB: b"no writer"})
        self.assertIn("FAIL: aac-to-dts: lib/arm64-v8a/libffmpegJNI.so has no dcaxll", r.stdout)

    def test_the_override_does_not_keep_a_missing_library(self):
        self.assert_the_override_does_not_keep({sync.FFMPEG_LIB: self.GOOD[sync.FFMPEG_LIB]})

    def test_the_override_does_not_keep_a_crash(self):
        (self.dir / sync.FFMPEG_AAR).unlink()
        self.assertIn("Traceback", self.assert_the_override_does_not_keep().stderr)

    def test_the_override_does_not_keep_a_usage_error(self):
        (self.dir / ".claude/skills/upstream-sync/scripts/sync.py").write_text("import sys\nsys.exit(2)\n")
        self.assert_the_override_does_not_keep()

    def test_only_the_exact_override_value_counts(self):
        (self.dir / f"{self.lib}.stamp").unlink()
        self.assertEqual(1, self.build(self.GOOD, NUVIO_ALLOW_NATIVE_MISMATCH="yes").returncode)

    def test_the_build_names_the_ffmpeg_it_carries(self):
        self.assertIn("FFmpeg is the prebuilt AAR", self.build(self.GOOD).stdout)


class MapOverlapRepoTest(RepoFixture, unittest.TestCase):
    """map-overlap on the fixture: the old fork on B edits shared.txt, which U also changed."""

    def test_a_light_sync_keeps_its_group_for_the_classify_fallback(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = sync.cmd_map_overlap(argparse.Namespace(upstream="U", perf="old", scratch=""), self.cfg)
        result = json.loads(buf.getvalue())
        self.assertEqual(sync.OK, code)
        self.assertEqual("light", result["scale"]["tier"], result["light"])
        self.assertEqual(["all"], [g["key"] for g in result["groups"]],
                         "a stop-check abort classifies from this output, so the light tier must keep its group")
        self.assertEqual(2, len(result["groups"][0]["commits"]))


class TriageSetsHelperTest(unittest.TestCase):
    THEMES = [{"key": "audio", "paths": ["audio/"]}, {"key": "video", "paths": ["video/"]},
              {"key": "app-other", "paths": ["."]}]

    def test_main_code_leaves_out_tests_resources_and_docs(self):
        for path in ("app/src/main/java/A.kt", "app/src/main/java/B.java", "native/x.cpp", "app/build.gradle.kts",
                     "ffmpeg/CMakeLists.txt", "app/src/main/AndroidManifest.xml"):
            self.assertTrue(triage.is_main_code(path), path)
        for path in ("app/src/test/java/A.kt", "app/src/androidTest/java/A.kt", "app/src/main/res/values/strings.xml",
                     "docs/x.md", "scripts/tests/x.py", "app/libs/x.aar"):
            self.assertFalse(triage.is_main_code(path), path)

    def test_a_theme_stays_whole_and_the_sets_balance(self):
        weights = {"audio/a.kt": 50, "audio/b.kt": 50, "video/c.kt": 60, "ui/d.kt": 30}
        sets = triage.partition(weights, self.THEMES, 2)
        self.assertEqual([["audio/a.kt", "audio/b.kt"], ["video/c.kt", "ui/d.kt"]], [s["paths"] for s in sets])
        self.assertEqual([100, 90], [s["lines"] for s in sets])

    def test_partition_is_deterministic_and_exclusive(self):
        weights = {f"{d}/{n}.kt": 10 for d in ("audio", "video", "ui") for n in range(4)}
        first = triage.partition(weights, self.THEMES, 3)
        self.assertEqual(first, triage.partition(dict(reversed(list(weights.items()))), self.THEMES, 3))
        paths = [p for s in first for p in s["paths"]]
        self.assertEqual(sorted(weights), sorted(paths))

    def test_no_empty_set_when_there_are_fewer_themes_than_readers(self):
        self.assertEqual(1, len(triage.partition({"audio/a.kt": 5, "audio/b.kt": 5}, self.THEMES, 3)))

    def test_reader_count_grows_with_the_lines(self):
        self.assertEqual(1, triage.reader_count(0))
        self.assertEqual(1, triage.reader_count(triage.READER_LINES))
        self.assertEqual(2, triage.reader_count(triage.READER_LINES + 1))

    def test_numstat_counts_a_binary_file(self):
        self.assertEqual({"a.kt": 5, "b.aar": 1}, triage.parse_numstat("3\t2\ta.kt\n-\t-\tb.aar\n"))

    def test_protected_by_file_or_subject(self):
        features = [{"key": "dts", "anchors": ["DtsTranscode"], "subjects": "AAC.to.DTS"}]
        self.assertEqual("dts", triage.feature_of(["x/DtsTranscode.kt"], "fix: y", features))
        self.assertEqual("dts", triage.feature_of(["x/Other.kt"], "fix: AAC to DTS start", features))
        self.assertIsNone(triage.feature_of(["x/Other.kt"], "fix: y", features))


class TriageSetsRepoTest(unittest.TestCase):
    """A small repository: upstream and the fork both change a.kt and b.kt, and they collide on b.kt."""

    CFG = {"owner_rule": "rule", "traps": ["trap"], "themes": TriageSetsHelperTest.THEMES,
           "protected_features": [{"key": "dts", "anchors": ["DtsTranscode"], "subjects": "AAC.to.DTS"}]}
    TEXT = "".join(f"line {n}\n" for n in range(1, 31))
    PATHS = ("audio/a.kt", "video/b.kt", "app/src/test/T.kt", "app/src/main/res/values/strings.xml")

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.cwd = os.getcwd()
        os.chdir(self.dir)
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
        for path in self.PATHS:
            self.write(path, self.TEXT)
        self.commit("base")
        run("git", "tag", "B", cwd=self.dir)
        for path in self.PATHS:
            self.write(path, self.TEXT.replace("line 25\n", "upstream 25\n"))
        self.commit("feat: upstream")
        run("git", "tag", "U", cwd=self.dir)
        run("git", "checkout", "-q", "-b", "fork", "B", cwd=self.dir)
        self.write("audio/a.kt", self.TEXT.replace("line 2\n", "fork 2\n"))
        self.commit("fix: clean edit of a shared file")
        self.write("video/b.kt", self.TEXT.replace("line 25\n", "fork 25\n"))
        self.commit("fix: collides with upstream")
        self.write("video/DtsTranscode.kt", "new\n")
        self.commit("feat: a file upstream never saw")
        for path in self.PATHS[2:]:
            self.write(path, self.TEXT.replace("line 2\n", "fork 2\n"))
        self.commit("test: fork edits a test and a string")

    def tearDown(self):
        os.chdir(self.cwd)
        self.tmp.cleanup()

    def write(self, path, text):
        (self.dir / path).parent.mkdir(parents=True, exist_ok=True)
        (self.dir / path).write_text(text)

    def commit(self, message):
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", message, cwd=self.dir)

    def build(self):
        return triage.build("B", "fork", "U", self.CFG, self.dir / "scratch/map.json")

    def test_sets_hold_only_main_code_both_sides_changed(self):
        result, args = self.build()
        self.assertEqual(["audio/a.kt", "video/b.kt"], sorted(p for s in args["sets"] for p in s))
        self.assertEqual(["app/src/main/res/values/strings.xml", "app/src/test/T.kt"], result["excluded"])
        self.assertEqual({"fork": 4, "bothChanged": 4, "mainCode": 2}, args["counts"])

    def test_only_the_colliding_commit_is_a_stop(self):
        result, args = self.build()
        self.assertEqual(["fix: collides with upstream"], [s["subject"] for s in result["stops"]])
        stop = result["stops"][0]
        self.assertEqual(["video/b.kt"], stop["files"])
        self.assertEqual([{"sha": stop["sha"], "files": ["video/b.kt"]}], args["stops"])
        self.assertTrue(stop["alsoInTheirsReplay"])
        self.assertEqual(["video/b.kt"], result["conflictFiles"])

    def test_a_stop_head_reproduces_the_conflict(self):
        result, _ = self.build()
        stop = result["stops"][0]
        r = subprocess.run(["git", "merge-tree", "--write-tree", "--name-only", "--no-messages",
                            f"--merge-base={stop['sha']}^", stop["head"], stop["sha"]],
                           cwd=self.dir, capture_output=True, text=True)
        self.assertEqual(1, r.returncode)
        self.assertEqual(["video/b.kt"], r.stdout.split()[1:])

    def test_a_rerun_gives_the_same_map(self):
        # Fixed replay dates: the same heads, and no new objects in the shared store on each run.
        self.assertEqual(self.build()[0], self.build()[0])

    def test_a_later_commit_on_the_stopped_file_does_not_stop_after_the_replay_continues(self):
        self.write("video/b.kt", self.TEXT.replace("line 25\n", "fork 25\n").replace("line 5\n", "fork 5\n"))
        self.commit("fix: touches the stopped file elsewhere")
        result, _ = self.build()
        self.assertEqual(["fix: collides with upstream"], [s["subject"] for s in result["stops"]])

    def test_a_commit_that_edits_its_own_collision_stops_only_on_one_strategy(self):
        self.write("video/b.kt", self.TEXT.replace("line 25\n", "fork 25 again\n"))
        self.commit("fix: edits the collided line again")
        result, _ = self.build()
        rows = {s["subject"]: s["alsoInTheirsReplay"] for s in result["stops"]}
        self.assertEqual({"fix: collides with upstream": True, "fix: edits the collided line again": False}, rows)

    def test_protected_commits_are_in_the_args(self):
        _, args = self.build()
        sha = subprocess.run(["git", "log", "-1", "--format=%H", "--grep=upstream never saw"], cwd=self.dir,
                             capture_output=True, text=True).stdout.strip()
        self.assertEqual([sha[:12]], args["protected"])

    def test_the_cap_lines_name_what_was_limited(self):
        _, args = self.build()
        self.assertEqual(1, len(args["caps"]), args["caps"])
        self.assertIn("2 files both sides changed are not main code", args["caps"][0])
        with mock.patch.object(triage, "READER_LINES", 1), mock.patch.object(triage, "HEAVY_READER_LINES", 0):
            _, heavy = self.build()
        self.assertIn("readers capped at 3", " ".join(heavy["caps"]))
        with mock.patch.object(triage, "HEAVY_READER_LINES", 0):
            _, heavy = self.build()
        self.assertIn("reader 1 carries", " ".join(heavy["caps"]))

    def test_cli_writes_the_map_and_prints_small_args(self):
        out = self.dir / "scratch/map.json"
        config = self.dir / "c.json"
        config.write_text(json.dumps(self.CFG))
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = triage.main(["--base", "B", "--fork", "fork", "--upstream", "U", "--out", str(out),
                                "--config", str(config), "--pr-titles", "/x/titles.txt"])
        self.assertEqual(0, code)
        args = json.loads(buf.getvalue())
        self.assertEqual(str(out.resolve()), args["mapPath"])
        self.assertEqual("/x/titles.txt", args["prTitlesPath"])
        result = json.loads(out.read_text())
        self.assertEqual(["rule", "trap"], result["rules"])
        self.assertNotIn("rules", args)

    def test_a_bad_ref_exits_2(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, triage.main(["--base", "B", "--fork", "nope", "--upstream", "U", "--out", "m.json"]))


@unittest.skipUnless(subprocess.run(["git", "rev-parse", "-q", "--verify", "sync/2026-10-31/old-perf^{commit}"],
                                    cwd=ROOT, capture_output=True).returncode == 0
                     and subprocess.run(["git", "cat-file", "-e", "d9039df69^{commit}"],
                                        cwd=ROOT, capture_output=True).returncode == 0,
                     "needs the local tags of the sync of 2026-10-31")
class TriageSetsFrozenSyncTest(unittest.TestCase):
    """The helper on the frozen state of a past sync: tags and the upstream commit, nothing else."""

    @classmethod
    def setUpClass(cls):
        cwd = os.getcwd()
        os.chdir(ROOT)
        try:
            config = json.loads(subprocess.run(
                ["git", "show", "sync/2026-10-31/old-perf:.claude/skills/upstream-sync/sync.json"],
                capture_output=True, text=True, check=True).stdout)
            cls.map, cls.args = triage.build("sync/2026-10-31/old-base", "sync/2026-10-31/old-perf", "d9039df69",
                                             config, Path("/nonexistent/map.json"))
        finally:
            os.chdir(cwd)

    def test_args_name_the_files_of_every_stop(self):
        self.assertEqual(14, len(self.args["stops"]))
        self.assertEqual([{"sha": s["sha"], "files": s["files"]} for s in self.map["stops"]], self.args["stops"])
        self.assertTrue(all(s["files"] for s in self.args["stops"]))

    def test_three_exclusive_readers_cover_the_main_code(self):
        paths = [p for s in self.args["sets"] for p in s]
        self.assertEqual(3, len(self.args["sets"]))
        self.assertEqual(len(paths), len(set(paths)))
        self.assertEqual(self.args["counts"]["mainCode"], len(paths))
        self.assertGreaterEqual(len(paths), 30)

    def test_every_trial_conflict_is_a_predicted_stop(self):
        stopped = {f for s in self.map["stops"] for f in s["files"]}
        self.assertEqual(7, len(self.map["conflictFiles"]))
        self.assertLessEqual(set(self.map["conflictFiles"]), stopped)

    def test_args_pass_the_workflow_size_guard(self):
        limit = int(re.search(r"MAX_ARGS_CHARS = (\d+)", TRIAGE_WORKFLOW.read_text()).group(1))
        self.assertLess(len(json.dumps(self.args)), limit)


RUNNER = """
import { run } from './body.mjs'
import { readFileSync } from 'node:fs'
const { args, replies } = JSON.parse(readFileSync(0, 'utf8'))
const logs = [], labels = [], prompts = {}
const agent = async (prompt, opts) => {
  labels.push(opts.label)
  prompts[opts.label] = prompt
  if (!opts.schema) throw new Error('agent without a schema')
  return replies[opts.label] ?? null
}
const parallel = thunks => Promise.all(thunks.map(t => t().catch(() => null)))
try {
  const result = await run({ args, agent, parallel, log: m => logs.push(m), phase: () => {} })
  console.log(JSON.stringify({ result, logs, labels, prompts }))
} catch (e) {
  console.log(JSON.stringify({ error: e.message, logs, labels }))
}
"""


@unittest.skipUnless(shutil.which("node"), "needs node")
class TriageWorkflowTest(unittest.TestCase):
    """Runs the workflow's own script under node, with the agents stubbed. Top-level return is valid in a workflow
    script and not in a module, so the body goes into a function; the export of meta stays."""

    SETS = [["a/One.kt", "a/Two.kt"], ["b/Three.kt"]]
    ARGS = {"repo": "/r", "mapPath": "/s/map.json", "prTitlesPath": "", "upstreamRef": "U",
            "forkRef": "F", "mb": "m" * 40, "upstreamTip": "u" * 40, "forkTip": "f" * 40,
            "counts": {"fork": 9, "bothChanged": 4, "mainCode": 3}, "sets": SETS,
            "stops": [{"sha": "aaaaaaaaaaaa", "files": ["a/One.kt"]}], "conflictFiles": ["a/One.kt"],
            "protected": ["bbbbbbbbbbbb"],
            "caps": ["1 file went to no reader"]}

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dir = Path(cls.tmp.name)
        text = TRIAGE_WORKFLOW.read_text()
        end = re.search(r"^}\n", text, re.M).end()
        (cls.dir / "body.mjs").write_text(text[:end] + "export async function run({ args, agent, parallel, log, phase }) {\n"
                                          + text[end:] + "\n}\n")
        (cls.dir / "runner.mjs").write_text(RUNNER)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    @staticmethod
    def adapt(sha="cccccccccccc", file="a/One.kt"):
        return {"fork_sha": sha, "file": file, "line": 7, "attachment": "a new caller", "fix": "pass it", "evidence": ["U:a/One.kt:7"]}

    def replies(self):
        return {
            "resolver": {"stops": [{"sha": "aaaaaaaaa", "status": "resolved", "note": "", "resolutions": [
                {"path": "a/One.kt", "block": 1, "upstream_side": "u", "fork_side": "f", "merged": "both"}]}],
                "adapts": [], "open_questions": []},
            "reader-1": {"covered_files": self.SETS[0], "not_covered": [], "adapts": [self.adapt()], "superseded": [],
                         "settings": [{"upstream_sha": "ddd", "description": "default", "owner_rule": "take-upstream"}],
                         "open_questions": []},
            "reader-2": {"covered_files": self.SETS[1], "not_covered": [], "adapts": [], "superseded": [], "settings": [],
                         "open_questions": []},
            "critic": {"coverage_ok": True, "gaps": [], "contradictions": [], "added_adapts": [], "added_settings": []},
        }

    def run_workflow(self, replies=None, **changes):
        scenario = {"args": {**self.ARGS, **changes}, "replies": self.replies() if replies is None else replies}
        r = subprocess.run(["node", "runner.mjs"], cwd=self.dir, input=json.dumps(scenario), capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stderr)
        return json.loads(r.stdout)

    def summary(self, replies=None, **changes):
        return self.run_workflow(replies, **changes)["result"]["summary"]

    def test_the_script_passes_node_check(self):
        r = subprocess.run(["node", "--check", "body.mjs"], cwd=self.dir, capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stderr)

    def test_a_full_run_is_complete_with_the_keys_the_skill_reads(self):
        out = self.run_workflow()
        s = out["result"]["summary"]
        self.assertEqual(["resolver", "reader-1", "reader-2", "critic"], out["labels"])
        self.assertTrue(s["complete"])
        self.assertEqual(["ccccccccc"], s["adapt"])
        self.assertEqual(["aaaaaaaaa"], s["resolve"])
        self.assertEqual([], s["drop"])
        self.assertEqual([{"upstream": "ddd", "rule": "take-upstream", "what": "default", "note": ""}], s["defaults"])
        for k in ("failedGroups", "unclassified", "protectedAsk", "keepClean"):
            self.assertEqual([], s[k], k)

    def test_verify_decisions_reads_the_result(self):
        replies = self.replies()
        replies["reader-2"]["superseded"] = [
            {"fork_sha": "eeeeeeeeeeee", "upstream_sha": "ddd", "scope": "whole", "evidence": ["U:b/Three.kt:1"]},
            {"fork_sha": "999999999999", "upstream_sha": "ddd", "scope": "part", "evidence": ["U:b/Three.kt:2"]}]
        drops, hints = sync.decisions(self.run_workflow(replies)["result"])
        self.assertEqual(["eeeeeeeee"], drops)
        self.assertEqual(2, len(hints), hints)
        self.assertIn("cccccccccccc (keep-adapt): consider --allow-changed", " ".join(hints))
        self.assertIn("999999999999 (partial-superseded): consider --allow-same", " ".join(hints))

    def test_the_critic_decides_complete(self):
        for name, verdict in (("gap", {"coverage_ok": False, "gaps": ["theme x"]}),
                              ("contradiction", {"contradictions": ["resolver and reader-1 disagree"]}),
                              ("bare refusal", {"coverage_ok": False})):
            replies = self.replies()
            replies["critic"].update(verdict)
            s = self.summary(replies)
            self.assertFalse(s["complete"], name)
            self.assertTrue(s["criticIssues"], name)

    def test_a_gap_counts_even_when_the_critic_says_coverage_is_fine(self):
        replies = self.replies()
        replies["critic"].update({"coverage_ok": True, "gaps": ["theme x"]})
        self.assertFalse(self.summary(replies)["complete"])

    def test_a_failed_critic_leaves_the_triage_incomplete(self):
        replies = self.replies()
        replies["critic"] = None
        s = self.summary(replies)
        self.assertFalse(s["complete"])
        self.assertEqual(["critic"], s["failedGroups"])

    def test_critic_findings_join_the_adapts_with_their_source(self):
        replies = self.replies()
        replies["critic"]["added_adapts"] = [self.adapt("fffffffffffff", "x/Upstream.kt")]
        replies["critic"]["added_settings"] = [{"upstream_sha": "ee1", "description": "d2", "owner_rule": "keep-fork-gated"}]
        result = self.run_workflow(replies)["result"]
        self.assertEqual(["ccccccccc", "fffffffff"], result["summary"]["adapt"])
        self.assertEqual({"reader-1", "critic"}, {x["source"] for x in result["adapts"]})
        self.assertEqual(["take-upstream", "keep-fork-gated"], [d["rule"] for d in result["summary"]["defaults"]])

    def test_an_assigned_file_a_reader_did_not_cover_blocks(self):
        replies = self.replies()
        replies["reader-1"]["covered_files"] = ["a/One.kt"]
        s = self.summary(replies)
        self.assertFalse(s["complete"])
        self.assertEqual(["a/Two.kt"], s["uncovered"])

    def test_a_file_listed_as_not_covered_is_not_covered(self):
        replies = self.replies()
        replies["reader-1"]["not_covered"] = [{"path": "a/One.kt", "reason": "too long"}]
        self.assertEqual(["a/One.kt"], self.summary(replies)["uncovered"])

    def test_a_failed_reader_leaves_its_files_uncovered(self):
        replies = self.replies()
        replies["reader-2"] = None
        s = self.summary(replies)
        self.assertFalse(s["complete"])
        self.assertEqual(["reader-2"], s["failedGroups"])
        self.assertEqual(["b/Three.kt"], s["uncovered"])

    def test_a_stop_with_no_resolution_blocks(self):
        for status in ("ask-owner", None):
            replies = self.replies()
            if status:
                replies["resolver"]["stops"][0]["status"] = status
            else:
                replies["resolver"]["stops"] = []
            s = self.summary(replies)
            self.assertFalse(s["complete"], status)
            self.assertEqual(["aaaaaaaaaaaa"], [u["sha"] for u in s["unclassified"]])

    def test_a_false_alarm_answers_a_stop_but_not_a_conflict_file(self):
        replies = self.replies()
        replies["resolver"]["stops"][0].update(status="false-alarm", resolutions=[])
        s = self.summary(replies)
        self.assertEqual([], s["unclassified"])
        self.assertEqual(["a/One.kt"], s["unresolvedFiles"])
        self.assertFalse(s["complete"])
        s = self.summary(replies, conflictFiles=[])
        self.assertTrue(s["complete"])
        self.assertEqual([], s["resolve"])

    @staticmethod
    def resolution(path):
        return {"path": path, "block": 1, "upstream_side": "u", "fork_side": "f", "merged": "both"}

    def test_a_resolved_stop_with_no_resolutions_is_unresolved(self):
        replies = self.replies()
        replies["resolver"]["stops"][0]["resolutions"] = []
        s = self.summary(replies, conflictFiles=[])
        self.assertFalse(s["complete"])
        self.assertEqual(["aaaaaaaaaaaa"], [u["sha"] for u in s["unclassified"]])
        self.assertEqual([], s["resolve"])

    def test_a_resolved_stop_with_no_resolutions_still_overrules_a_drop(self):
        replies = self.replies()
        replies["resolver"]["stops"][0]["resolutions"] = []
        replies["reader-2"]["superseded"] = [self.claim("aaaaaaaaaaaa")]
        self.assertEqual([], self.summary(replies)["drop"])

    def test_a_row_outside_the_stops_with_no_resolutions_resolves_nothing(self):
        replies = self.replies()
        replies["resolver"]["stops"].append({"sha": "eeeeeeeeeeee", "status": "resolved", "note": "", "resolutions": []})
        self.assertEqual(["aaaaaaaaa"], self.summary(replies)["resolve"])

    def test_every_file_of_a_stop_needs_a_resolution(self):
        stops = [{"sha": "aaaaaaaaaaaa", "files": ["a/One.kt", "a/Two.kt"]}]
        result = self.run_workflow(stops=stops)["result"]
        s = result["summary"]
        self.assertFalse(s["complete"])
        self.assertEqual(["aaaaaaaaaaaa"], [u["sha"] for u in s["unclassified"]])
        self.assertIn("a/Two.kt", s["unclassified"][0]["subject"])
        self.assertNotIn("a/One.kt", s["unclassified"][0]["subject"])
        self.assertEqual([], s["resolve"])
        self.assertEqual(["keep-resolve"], [c["verdict"] for c in self.rows_of(result, "a")])
        replies = self.replies()
        replies["resolver"]["stops"][0]["resolutions"].append(self.resolution("a/Two.kt"))
        s = self.summary(replies, stops=stops)
        self.assertTrue(s["complete"])
        self.assertEqual(["aaaaaaaaa"], s["resolve"])

    def test_a_stop_is_not_answered_by_the_row_of_another_stop_on_the_same_file(self):
        stops = [{"sha": "aaaaaaaaaaaa", "files": ["a/One.kt"]}, {"sha": "eeeeeeeeeeee", "files": ["a/One.kt"]}]
        for label, resolutions in (("empty", []), ("another file", [self.resolution("b/Three.kt")])):
            replies = self.replies()
            replies["resolver"]["stops"].append({"sha": "eeeeeeeee", "status": "resolved", "note": "", "resolutions": resolutions})
            s = self.summary(replies, stops=stops)
            self.assertFalse(s["complete"], label)
            self.assertEqual(["eeeeeeeeeeee"], [u["sha"] for u in s["unclassified"]], label)
            self.assertEqual([], s["unresolvedFiles"], label)
            self.assertEqual(["aaaaaaaaa"], s["resolve"], label)

    def test_the_resolver_is_told_every_file_of_a_stop_needs_a_row(self):
        self.assertIn("Every file in the stop's `files` needs at least one row", self.run_workflow()["prompts"]["resolver"])

    def test_stops_without_their_files_are_refused(self):
        for stops in (["aaaaaaaaaaaa"], [{"sha": "aaaaaaaaaaaa"}], [{"sha": "aaaaaaaaaaaa", "files": []}]):
            out = self.run_workflow(stops=stops)
            self.assertIn("args.stops", out["error"])
            self.assertEqual([], out["labels"])

    def test_no_agent_drops_a_protected_commit(self):
        replies = self.replies()
        replies["reader-2"]["superseded"] = [
            {"fork_sha": "bbbbbbbbbb", "upstream_sha": "ddd", "scope": "whole", "evidence": ["x"]}]
        result = self.run_workflow(replies)["result"]
        s = result["summary"]
        self.assertEqual([], s["drop"])
        self.assertEqual(["bbbbbbbbb"], s["protectedAsk"])
        self.assertEqual(["bbbbbbbbb"], [k for k in s["adapt"] if k.startswith("b")])
        self.assertEqual([], sync.decisions(result)[0])

    def test_a_short_sha_cannot_slip_a_protected_commit_past_the_check(self):
        replies = self.replies()
        replies["reader-2"]["superseded"] = [{"fork_sha": "bbbbbb", "upstream_sha": "d", "scope": "whole", "evidence": []}]
        result = self.run_workflow(replies)["result"]
        self.assertEqual([], result["summary"]["drop"])
        self.assertIn("no usable fork sha", " ".join(result["groups"][0]["open_questions"]))

    def test_a_superseded_claim_on_an_adapted_commit_is_no_drop(self):
        replies = self.replies()
        replies["reader-2"]["superseded"] = [
            {"fork_sha": "cccccccccccc", "upstream_sha": "ddd", "scope": "whole", "evidence": ["x"]}]
        out = self.run_workflow(replies)
        self.assertEqual([], out["result"]["summary"]["drop"])
        self.assertIn("ccccccccc: superseded claim overruled by keep-adapt", out["logs"])

    @staticmethod
    def rows_of(result, first):
        return [c for c in result["groups"][0]["commits"] if c["sha"].startswith(first)]

    @staticmethod
    def claim(sha, scope="whole"):
        return {"fork_sha": sha, "upstream_sha": "ddd", "scope": scope, "evidence": ["x"]}

    def test_a_short_superseded_sha_makes_no_second_row_and_no_drop(self):
        for sha in ("aaaaaaa", "aaaaaaaa", "AAAAAAA"):
            replies = self.replies()
            replies["resolver"]["stops"][0]["sha"] = "aaaaaaaaaaaa"
            replies["reader-2"]["superseded"] = [self.claim(sha)]
            result = self.run_workflow(replies)["result"]
            self.assertEqual([], result["summary"]["drop"], sha)
            self.assertEqual(["keep-resolve"], [c["verdict"] for c in self.rows_of(result, "a")], sha)
            self.assertIn("no usable fork sha", " ".join(result["groups"][0]["open_questions"]), sha)

    def test_a_short_resolver_sha_shares_the_row_of_a_longer_claim(self):
        for sha in ("aaaaaaa", "aaaaaaaa"):
            replies = self.replies()
            replies["resolver"]["stops"][0]["sha"] = sha
            replies["reader-2"]["superseded"] = [self.claim("aaaaaaaaaaaa")]
            out = self.run_workflow(replies)
            s = out["result"]["summary"]
            self.assertEqual([], s["drop"], sha)
            self.assertEqual(["aaaaaaaaa"], s["resolve"], sha)
            self.assertEqual(["aaaaaaaaaaaa"], [c["sha"] for c in self.rows_of(out["result"], "a")], sha)
            self.assertIn("aaaaaaaaa: superseded claim overruled by keep-resolve", out["logs"])

    def test_a_short_adapt_stays_listed_and_makes_no_row(self):
        replies = self.replies()
        replies["reader-1"]["adapts"] = [self.adapt("ccccccc")]
        result = self.run_workflow(replies)["result"]
        self.assertEqual(["ccccccc"], [x["fork_sha"] for x in result["adapts"]])
        self.assertEqual([], result["summary"]["adapt"])
        self.assertEqual([], self.rows_of(result, "c"))
        self.assertIn("An adapt names no usable fork sha (ccccccc)", " ".join(result["groups"][0]["open_questions"]))

    def test_an_adapt_with_no_usable_sha_keeps_what_it_could_name_from_drop(self):
        for sha in ("ccccc", "cccccc", "ccccccc", "cccccccc", "CCCCCCC", "the fork commit", ""):
            replies = self.replies()
            replies["reader-1"]["adapts"] = [self.adapt(sha)]
            replies["reader-2"]["superseded"] = [self.claim("cccccccccccc")]
            result = self.run_workflow(replies)["result"]
            self.assertEqual([], result["summary"]["drop"], sha)
            self.assertEqual(["ccccccccc"], result["summary"]["adapt"], sha)
            self.assertEqual(["keep-adapt"], [c["verdict"] for c in self.rows_of(result, "c")], sha)

    def test_a_short_adapt_leaves_other_commits_free_to_drop(self):
        replies = self.replies()
        replies["reader-1"]["adapts"] = [self.adapt("ccccccc")]
        replies["reader-2"]["superseded"] = [self.claim("eeeeeeeeeeee")]
        self.assertEqual(["eeeeeeeee"], self.summary(replies)["drop"])

    def test_the_size_guard_stops_a_big_args_before_any_agent(self):
        out = self.run_workflow(caps=["x" * 30000])
        self.assertIn("the limit is 20000", out["error"])
        self.assertEqual([], out["labels"])

    def test_more_sets_than_readers_is_refused_rather_than_dropped(self):
        out = self.run_workflow(sets=[["a"], ["b"], ["c"], ["d"]])
        self.assertIn("args.sets has 4 sets", out["error"])

    def test_the_old_overlap_json_is_refused(self):
        args = {k: v for k, v in self.ARGS.items() if k != "mapPath"}
        r = subprocess.run(["node", "runner.mjs"], cwd=self.dir, capture_output=True, text=True,
                           input=json.dumps({"args": args, "replies": {}}))
        self.assertIn("args.mapPath is missing", json.loads(r.stdout)["error"])

    def test_every_cap_gets_a_log_line(self):
        self.assertIn("Cap: 1 file went to no reader", self.run_workflow()["logs"])

    def test_nothing_to_triage_runs_no_agent(self):
        out = self.run_workflow(sets=[], stops=[], conflictFiles=[])
        self.assertEqual([], out["labels"])
        self.assertTrue(out["result"]["summary"]["complete"])
        self.assertEqual(([], []), sync.decisions(out["result"]))

    def test_no_stops_needs_no_resolver_and_the_reader_order_holds(self):
        replies = self.replies()
        del replies["resolver"]
        out = self.run_workflow(replies, stops=[], conflictFiles=[])
        self.assertEqual(["reader-1", "reader-2", "critic"], out["labels"])
        self.assertTrue(out["result"]["summary"]["complete"])
        self.assertEqual(["ccccccccc"], out["result"]["summary"]["adapt"])

    def test_readers_get_only_their_own_files_and_the_checklist(self):
        prompts = self.run_workflow()["prompts"]
        self.assertIn("- a/Two.kt", prompts["reader-1"])
        self.assertNotIn("- b/Three.kt", prompts["reader-1"])
        self.assertIn("- b/Three.kt", prompts["reader-2"])
        for label in ("reader-1", "reader-2", "resolver", "critic"):
            self.assertIn("Transient state", prompts[label], label)
            self.assertIn("git -C /r", prompts[label], label)


class StopCheckRepoTest(RepoFixture, unittest.TestCase):
    """stop-check at a real conflict stop: a fork commit that edits upstream.txt, which U also changed."""

    def stop_at_conflict(self, subject):
        run("git", "checkout", "-q", "-b", "x", "B", cwd=self.dir)
        self.files = {"shared.txt": list(self.LINES), "fork-only.txt": list(self.LINES), "upstream.txt": ["u"]}
        self.commit(subject, {"upstream.txt": {1: "fork"}})
        r = subprocess.run(["git", "rebase", "--onto", "U", "B", "x"], cwd=self.dir, capture_output=True, text=True)
        self.assertNotEqual(0, r.returncode, "the fixture must stop on a conflict")

    def stop_check(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = sync.cmd_stop_check(argparse.Namespace(), self.cfg)
        return code, buf.getvalue()

    def test_a_small_plain_conflict_passes(self):
        self.stop_at_conflict("fix: fork edits the upstream file")
        code, report = self.stop_check()
        self.assertEqual(sync.OK, code, report)
        self.assertIn("ok: 1 conflicting file(s) at this stop, 1 in this rebase", report)

    def test_a_protected_commit_stops_the_light_path(self):
        self.stop_at_conflict("feat(dv): HLG conversion edits the upstream file")
        code, report = self.stop_check()
        self.assertEqual(sync.FAIL, code)
        self.assertIn("STOP: the replayed commit belongs to the protected feature hlg-to-dv", report)

    def test_a_conflict_rerere_already_staged_still_counts(self):
        run("git", "config", "rerere.enabled", "true", cwd=self.dir)
        run("git", "config", "rerere.autoupdate", "true", cwd=self.dir)
        self.stop_at_conflict("fix: fork edits the upstream file")
        (self.dir / "upstream.txt").write_text("merged\n")
        run("git", "add", "upstream.txt", cwd=self.dir)
        run("git", "rerere", cwd=self.dir)
        run("git", "rebase", "--abort", cwd=self.dir)
        r = subprocess.run(["git", "rebase", "--onto", "U", "B", "x"], cwd=self.dir, capture_output=True, text=True)
        self.assertNotEqual(0, r.returncode, "the rebase must still stop when rerere staged the resolution")
        self.assertEqual([], sync.lines("diff", "--name-only", "--diff-filter=U"), "rerere left nothing unmerged")
        code, report = self.stop_check()
        self.assertEqual(sync.OK, code, report)
        self.assertIn("ok: 1 conflicting file(s) at this stop, 1 in this rebase", report)

    def test_a_seen_path_with_a_space_counts_once(self):
        self.stop_at_conflict("fix: fork edits the upstream file")
        rebase_dir = Path(sync.out("rev-parse", "--git-path", "rebase-merge"))
        (rebase_dir / "upstream-sync-seen").write_text("app/My Store.kt\nb.kt\n")
        code, report = self.stop_check()
        self.assertEqual(sync.OK, code, report)
        self.assertIn("ok: 1 conflicting file(s) at this stop, 3 in this rebase", report)
        self.assertIn("app/My Store.kt", (rebase_dir / "upstream-sync-seen").read_text().splitlines())

    def test_outside_a_rebase_it_is_a_usage_error(self):
        code, _ = self.stop_check()
        self.assertEqual(sync.USAGE, code)


class RenameRepoTest(unittest.TestCase):
    """Upstream renames or deletes a file the fork changed."""

    LINES = [f"line {n}" for n in range(1, 21)]

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.cwd = os.getcwd()
        os.chdir(self.dir)
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
        self.put("x.txt", self.LINES)
        self.put("z.txt", ["z"])
        self.git_commit("base")
        run("git", "tag", "B", cwd=self.dir)
        run("git", "checkout", "-q", "-b", "old", "B", cwd=self.dir)
        self.put("x.txt", self.edit(self.LINES, 5, "line 5 fork"))
        self.put("z.txt", ["z fork"])
        self.git_commit("fix: fork edit")
        self.cfg = dict(CONFIG, pinned_binaries={})

    def tearDown(self):
        os.chdir(self.cwd)
        self.tmp.cleanup()

    def put(self, name, lines):
        (self.dir / name).write_text("\n".join(lines) + "\n")

    @staticmethod
    def edit(lines, n, text):
        out = list(lines)
        out[n - 1] = text
        return out

    def git_commit(self, msg):
        run("git", "add", "-A", cwd=self.dir)
        run("git", "commit", "-q", "-m", msg, cwd=self.dir)

    def upstream(self, rename):
        run("git", "checkout", "-q", "-b", "up", "B", cwd=self.dir)
        if rename:
            run("git", "mv", "x.txt", "y.txt", cwd=self.dir)
        else:
            run("git", "rm", "-q", "x.txt", cwd=self.dir)
        self.git_commit("upstream moves x.txt")
        run("git", "tag", "U", cwd=self.dir)

    def verify(self):
        a = argparse.Namespace(base_old="B", old="old", base_new="U", new="new", upstream="U",
                               decisions=None, expect_drop=None, allow_same=None, allow_changed=None)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = sync.cmd_verify(a, self.cfg)
        return code, buf.getvalue()

    def test_a_fork_change_follows_the_rename(self):
        self.upstream(rename=True)
        run("git", "checkout", "-q", "-b", "new", "U", cwd=self.dir)
        self.put("y.txt", self.edit(self.LINES, 5, "line 5 fork"))
        self.put("z.txt", ["z fork"])
        self.git_commit("fix: fork edit")
        code, report = self.verify()
        self.assertEqual(0, code, report)

    def test_a_fork_change_lost_in_the_rename_fails(self):
        self.upstream(rename=True)
        run("git", "checkout", "-q", "-b", "new", "U", cwd=self.dir)
        self.put("z.txt", ["z fork"])
        self.git_commit("fix: fork edit")
        code, report = self.verify()
        self.assertEqual(1, code)
        self.assertIn("fork change lost: x.txt (renamed upstream to y.txt)", report)

    def test_an_upstream_deletion_is_named_as_such(self):
        self.upstream(rename=False)
        run("git", "checkout", "-q", "-b", "new", "U", cwd=self.dir)
        self.put("z.txt", ["z fork"])
        self.git_commit("fix: fork edit")
        code, report = self.verify()
        self.assertEqual(1, code)
        self.assertIn("upstream removed x.txt", report)
        self.assertNotIn("fork change lost", report)


class DocsEntryTest(unittest.TestCase):
    DEFAULTS = [
        {"upstream": "d5da6bcaaaae", "rule": "take-upstream", "what": "New setting libassRenderMode, default OVERLAY.", "note": ""},
        {"upstream": "cc9bdb19eea9", "rule": "take-upstream", "what": "New setting libass_render_mode, default OVERLAY.",
         "note": "Under '### Upstream defaults taken with the sync of <date>': ASS render mode defaults to Overlay."},
        {"upstream": "d5da6bcaa", "rule": "take-upstream", "what": "New setting ASS render mode (Store.kt:645).", "note": ""},
        {"upstream": "e8cec44a6167", "rule": "take-upstream", "what": "Lanes go to 2.", "note": ""},
        {"upstream": "e8cec44a6167", "rule": "take-upstream", "what": "Batches hold 48 MB.", "note": ""},
        {"upstream": "1ad519b85e37", "rule": "keep-fork-gated", "what": "Upstream keeps useSystemPassthrough off.", "note": ""},
        {"upstream": "aaaaaaaaaaaa", "rule": "take-upstream", "what": "New key tunnelMode, default on.", "note": ""},
        {"upstream": "bbbbbbbbbbbb", "rule": "keep-fork-gated", "what": "Upstream moves tunnelMode to a store.", "note": ""},
    ]
    REPORT = ("## Verify oldoldold1234 -> newnewnew5678\n\nrange-diff: 5 identical, 2 changed, 1 dropped, 0 new\n\n"
              "### Protected features\n\n- aac-to-dts: 45 commits, 5970 lines checked, 0 lost\n\n### Notes\n\n"
              "- waived by --allow-same: a.kt may equal abc\n\n### Result\n\n- all checks pass\n")

    def section(self, defaults=None):
        return "\n".join(tools.defaults_section({"defaults": self.DEFAULTS if defaults is None else defaults}, "2026-11-07"))

    def test_one_bullet_per_setting_with_every_commit_cited_once(self):
        text = self.section()
        self.assertIn("- ASS render mode defaults to Overlay. (d5da6bcaa, cc9bdb19e)", text)
        bullets = text.split("Measurements taken")[0]
        self.assertEqual(1, bullets.count("render mode"), text)

    def test_a_shared_commit_alone_does_not_merge(self):
        # One upstream commit often changes several settings.
        text = self.section()
        self.assertIn("- Lanes go to 2. (e8cec44a6)", text)
        self.assertIn("- Batches hold 48 MB. (e8cec44a6)", text)

    def test_a_word_run_that_merely_holds_a_name_does_not_merge(self):
        # "a strong box" holds strongBox, but says nothing about that setting.
        lanes = {"upstream": "e8cec44a6", "rule": "take-upstream", "what": "Two lanes when strongBox is false.", "note": ""}
        batch = {"upstream": "e8cec44a6", "rule": "take-upstream", "what": "Batches hold 48 MB on a strong box.", "note": ""}
        text = self.section([lanes, batch])
        self.assertIn("- Batches hold 48 MB on a strong box. (e8cec44a6)", text)

    def test_every_merged_entry_stays_visible_to_the_human(self):
        text = self.section()
        check = text.split("TODO: these analyst entries were merged")[1]
        self.assertIn("New setting libass_render_mode, default OVERLAY.", check)
        self.assertIn("New setting ASS render mode (Store.kt:645).", check)

    def test_identical_text_is_one_entry(self):
        same = {"upstream": "e8cec44a6167", "rule": "take-upstream", "what": "Lanes go to 2.", "note": ""}
        text = self.section([same, dict(same, upstream="c5b872833")])
        self.assertEqual(1, text.split("TODO: these analyst")[0].count("Lanes go to 2."))

    def test_fork_values_and_upstream_defaults_are_apart(self):
        text = self.section()
        taken, kept = text.split("TODO: a row in the plan table")
        self.assertNotIn("useSystemPassthrough", taken)
        self.assertIn("useSystemPassthrough", kept)

    def test_a_disagreement_on_the_rule_goes_to_a_human(self):
        text = self.section()
        self.assertIn("TODO: analysts disagree", text)
        self.assertIn("tunnelMode", text.split("TODO: analysts disagree")[1])

    def test_an_empty_list_still_gives_the_frame(self):
        text = self.section([])
        self.assertIn("### Upstream defaults taken with the sync of 2026-11-07", text)
        self.assertIn("Measurements taken before 2026-11-07 predate these defaults.", text)

    def test_a_note_that_is_only_a_location_falls_back_to_the_description(self):
        where = "Under '### Upstream defaults taken with the sync of <date>':"
        entry = {"upstream": "e8cec44a6167", "rule": "take-upstream", "what": "Lanes go to 2.", "note": where}
        for note in (where, where + "  \n"):
            self.assertIn("- Lanes go to 2. (e8cec44a6)", self.section([dict(entry, note=note)]))

    def test_a_later_note_with_text_wins_over_a_location_alone(self):
        first = {"upstream": "aaaaaaaaaaaa", "rule": "take-upstream", "what": "New key tunnelMode.", "note": "Under '### X':"}
        second = dict(first, upstream="bbbbbbbbbbbb", note="Under '### X': tunnel mode is on.")
        self.assertIn("- Tunnel mode is on. (aaaaaaaaa, bbbbbbbbb)", self.section([first, second]))

    def test_an_entry_with_no_text_is_a_todo_line(self):
        entry = {"upstream": "e8cec44a6167", "rule": "take-upstream", "what": "", "note": "Under '### X':"}
        self.assertIn("- TODO: no note or description for this setting. (e8cec44a6)", self.section([entry]))

    def test_the_log_entry_takes_facts_from_the_report(self):
        entry = "\n".join(tools.log_entry(tools.parse_verify_report(self.REPORT), {"drop": ["abc1234"], "adapt": ["d"]},
                                          "2026-11-07", "40"))
        self.assertIn("## 2026-11-07: U1.5 sync onto upstream TODO-version (PR #40)", entry)
        self.assertIn("from `oldoldold` onto TODO-base. New tip `newnewnew`.", entry)
        self.assertIn("`verify`: 5 identical, 2 changed, 1 dropped, 0 new. Protected lines: aac-to-dts 45 commits, 5970 lines, 0 lost.", entry)
        self.assertIn("waived by --allow-same: a.kt may equal abc", entry)
        self.assertIn("**Classify:** 1 drops, 0 protected asks, 0 keep-resolve, 1 keep-adapt or partial, 0 keep-clean.", entry)
        self.assertIn("**Drops:** `abc1234`.", entry)
        self.assertIn("TODO: the reason for each drop.", entry)
        self.assertIn("TODO: each decision and adapt commit", entry)

    def test_a_failed_verify_is_stated(self):
        failed = self.REPORT.replace("- all checks pass", "- FAIL: fork change lost: x.kt")
        entry = "\n".join(tools.log_entry(tools.parse_verify_report(failed), {}, "d", "1"))
        self.assertIn("`verify` FAILED: fork change lost: x.kt", entry)

    def test_a_report_cut_short_is_a_todo(self):
        cut = self.REPORT.replace("- all checks pass", "")
        self.assertIn("TODO: the report states no result", "\n".join(tools.log_entry(tools.parse_verify_report(cut), {}, "d", "1")))

    def test_a_text_that_is_no_report_raises(self):
        with self.assertRaises(RuntimeError):
            tools.parse_verify_report("hello\n")

    def command(self, *extra):
        with tempfile.TemporaryDirectory() as d:
            report, classify = Path(d) / "verify.txt", Path(d) / "classify.json"
            report.write_text(self.REPORT)
            classify.write_text(json.dumps({"summary": {"defaults": self.DEFAULTS[:1]}}))
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                code = sync.main(["docs-entry", "--verify-report", str(report), "--classify", str(classify), *extra])
        return code, buf.getvalue()

    def test_no_date_or_pr_is_a_todo_not_a_guess(self):
        code, text = self.command()
        self.assertEqual(sync.OK, code)
        self.assertIn("## TODO-date: U1.5 sync", text)
        self.assertIn("(PR #TODO)", text)
        self.assertIn("### Upstream defaults taken with the sync of TODO-date", text)

    def test_the_command_prints_both_parts(self):
        code, text = self.command("--date", "2026-11-07", "--pr", "40")
        self.assertEqual(sync.OK, code)
        self.assertIn("<!-- docs/ROADMAP_LOG.md -->", text)
        self.assertIn("<!-- docs/shield-todo.md -->", text)

    def test_tiers_without_classify_get_a_skeleton_with_todos(self):
        # Tiers skip and light run no classify step, so no count may read as a fact.
        with tempfile.TemporaryDirectory() as d:
            report = Path(d) / "verify.txt"
            report.write_text(self.REPORT)
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                code = sync.main(["docs-entry", "--verify-report", str(report), "--date", "2026-11-07"])
        text = buf.getvalue()
        self.assertEqual(sync.OK, code)
        self.assertIn("`verify`: 5 identical, 2 changed, 1 dropped, 0 new.", text)
        self.assertIn("**Classify:** TODO: no classify.json", text)
        self.assertNotIn("0 drops", text)
        self.assertIn("### Upstream defaults taken with the sync of 2026-11-07", text)
        self.assertIn("TODO: no classify.json, so list the upstream defaults by hand.", text)
        self.assertNotIn("TODO: rewrite each bullet", text)

    def test_the_skill_leaves_out_the_flags_on_tiers_without_classify(self):
        text = (SKILL / "SKILL.md").read_text()
        verify = text.split("## 6. Verify")[1].split("2. A failure blocks.")[0]
        self.assertRegex(verify, r"[Oo]n tiers `skip` and `light`.*leave out `--decisions`")
        followups = text.split("## 9. Follow-ups")[1].split("\n## ")[0]
        self.assertIn("[--classify <scratch>/classify.json]", followups)
        self.assertRegex(followups, r"[Oo]n tiers `skip` and `light`.*leave out `--classify`")

    def test_a_missing_input_is_a_usage_error(self):
        with contextlib.redirect_stderr(io.StringIO()):
            code = sync.main(["docs-entry", "--verify-report", "/nonexistent", "--classify", "/nonexistent"])
        self.assertEqual(sync.USAGE, code)


class JudgementRepoTest(RepoFixture, unittest.TestCase):
    """judgement: old on B, U on B, and a rebased tip on U. A mechanical merge of old and U conflicts on nothing but
    shared.txt, line 20 against the line upstream appended."""

    def judge(self, new="new", declared=None, diff=None):
        path = None
        if declared is not None:
            path = self.dir / "declared.txt"
            path.write_text(declared)
        a = argparse.Namespace(old="old", new_base="U", new=new, declared=str(path) if path else None,
                               diff=str(diff) if diff else None)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = tools.cmd_judgement(a, self.cfg)
        return code, buf.getvalue()

    def mechanical(self):
        """The rebased tip that equals the mechanical merge, which keeps upstream's side of the conflict."""
        self.new_branch([("fix: superseded top", {"shared.txt": {1: "TOP"}}),
                         ("fix: retained bottom", {"fork-only.txt": {10: "line 10 fork"}})])

    def test_a_tip_equal_to_the_merge_has_no_difference(self):
        self.mechanical()
        code, report = self.judge(declared="")
        self.assertEqual(sync.OK, code, report)
        self.assertIn("no difference", report)

    def test_it_names_its_strategy(self):
        self.mechanical()
        _, report = self.judge()
        self.assertIn("merge-tree -X theirs OLD NEW_BASE", report)
        self.assertIn("upstream wins a conflicting hunk", report)

    def test_the_kept_fork_hunk_in_a_conflict_differs(self):
        # upstream wins the conflict in the merge, so a tip that keeps BOTTOM is the call the check must surface.
        self.new_branch(self.FAITHFUL)
        code, report = self.judge()
        self.assertEqual(sync.OK, code, "without --declared it only reports")
        self.assertIn("shared.txt", report)
        self.assertIn("1 file changed", report)

    def test_an_undeclared_path_fails_and_a_declared_one_passes(self):
        self.new_branch(self.FAITHFUL)
        code, report = self.judge(declared="# nothing yet\n\nfork-only.txt\n")
        self.assertEqual(sync.FAIL, code)
        self.assertIn("FAIL: undeclared difference: shared.txt", report)
        code, report = self.judge(declared="shared.txt\n")
        self.assertEqual(sync.OK, code, report)
        self.assertIn("declared: 1 of 1 differing paths", report)
        self.assertIn("nothing inside a declared file is checked", report)

    def test_the_full_diff_goes_to_a_file_for_the_reviewer(self):
        self.new_branch(self.FAITHFUL)
        path = self.dir / "judgement.diff"
        self.judge(diff=path)
        self.assertIn("+BOTTOM", path.read_text())

    def test_the_repository_gets_no_new_objects(self):
        self.new_branch(self.FAITHFUL)
        before = sorted(str(p) for p in (self.dir / ".git/objects").rglob("*") if p.is_file())
        self.judge()
        self.assertEqual(before, sorted(str(p) for p in (self.dir / ".git/objects").rglob("*") if p.is_file()))

    def test_a_git_failure_exits_two(self):
        self.mechanical()
        with contextlib.redirect_stderr(io.StringIO()):
            code = sync.main(["judgement", "--old", "no-such-ref", "--new-base", "U", "--new", "new"])
        self.assertEqual(sync.USAGE, code)

    def test_a_missing_declared_file_is_a_usage_error_not_a_failed_check(self):
        self.mechanical()
        with contextlib.redirect_stderr(io.StringIO()):
            code = sync.main(["judgement", "--old", "old", "--new-base", "U", "--new", "new", "--declared", "nope"])
        self.assertEqual(sync.USAGE, code)

    def test_declared_paths_skip_comments_and_blanks(self):
        self.assertEqual({"a.kt", "b c.kt"}, tools.declared_paths("# why\n a.kt \n\nb c.kt\n"))


class OfficialPreviewRepoTest(RepoFixture, unittest.TestCase):
    """official-preview against a local repository that stands in for NuvioMedia/NuvioTV."""

    def setUp(self):
        super().setUp()
        self.new_branch(self.FAITHFUL)
        run("git", "update-ref", "refs/remotes/upstream/main", self.sha("U"), cwd=self.dir)
        run("git", "update-ref", "refs/remotes/origin/perf/shield-pass", self.sha("new"), cwd=self.dir)
        self.official = tempfile.TemporaryDirectory()
        self.url = str(Path(self.official.name) / "official")
        run("git", "clone", "-q", "--no-hardlinks", "-b", "main", str(self.dir), self.url, cwd=self.dir)
        for setting in (("user.email", "o@example.com"), ("user.name", "o")):
            run("git", "config", *setting, cwd=self.url)
        run("git", "checkout", "-q", "-b", "dev", "U", cwd=self.url)
        self.gh = tools.gh_json
        tools.gh_json = lambda *a: [{"number": 7, "title": "feat: merged", "merged_at": "2026-10-30T10:00:00Z"},
                                    {"number": 8, "title": "wip: closed unmerged", "merged_at": None}]

    def tearDown(self):
        tools.gh_json = self.gh
        self.official.cleanup()
        super().tearDown()

    def official_commit(self, subject, name="fork-only.txt", text="official\n"):
        (Path(self.url) / name).write_text(text)
        run("git", "add", "-A", cwd=self.url)
        run("git", "commit", "-q", "-m", subject, cwd=self.url)

    def preview(self, url=None):
        a = argparse.Namespace(url=url or self.url, upstream=None, fork=None)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = tools.cmd_official_preview(a, self.cfg)
        return code, buf.getvalue()

    def test_it_reports_the_tag_the_count_and_the_conflicts(self):
        self.official_commit("feat: official only")
        run("git", "tag", "1.1.0-beta.9", cwd=self.url)
        run("git", "tag", "1.1.0-beta.10", cwd=self.url)
        self.official_commit("fix: upstream has this subject", "extra.txt")
        code, report = self.preview()
        self.assertEqual(sync.OK, code, report)
        self.assertIn("latest tag 1.1.0-beta.10", report)
        self.assertIn("2 of its commits are not in upstream/main by subject", report)
        self.assertIn("trial merge with origin/perf/shield-pass: 1 conflicting files\n  fork-only.txt\n", report)
        self.assertIn("#7 feat: merged", report)
        self.assertNotIn("closed unmerged", report)

    def test_a_subject_upstream_already_has_does_not_count(self):
        self.official_commit("upstream change", "extra.txt")
        self.official_commit("feat: official only", "extra2.txt")
        _, report = self.preview()
        self.assertIn("1 of its commits are not in upstream/main by subject (2 outside its history)", report)

    def test_a_release_sorts_after_its_prerelease(self):
        run("git", "tag", "1.1.0-beta.9", cwd=self.url)
        run("git", "tag", "1.1.0", cwd=self.url)
        self.assertEqual("1.1.0", tools.latest_tag(self.url))

    def test_it_leaves_no_remote_no_ref_and_no_new_tag(self):
        run("git", "tag", "1.1.0-beta.9", cwd=self.url)
        remotes = subprocess.run(["git", "config", "--get-regexp", "^remote\\."], cwd=self.dir, capture_output=True,
                                 text=True).stdout
        self.preview()
        self.assertEqual(remotes, subprocess.run(["git", "config", "--get-regexp", "^remote\\."], cwd=self.dir,
                                                 capture_output=True, text=True).stdout)
        self.assertEqual([], sync.lines("for-each-ref", "refs/remotes/official/", tools.OFFICIAL_REF))
        self.assertEqual([], sync.lines("tag", "--list", "1.1.0*"))

    def test_the_ref_is_private_and_nothing_else_is_deleted(self):
        # A user's remote named official owns refs/remotes/official/*.
        self.assertTrue(tools.OFFICIAL_REF.startswith("refs/upstream-sync/"))
        run("git", "update-ref", "refs/remotes/official/dev", self.sha("U"), cwd=self.dir)
        run("git", "update-ref", "refs/remotes/official/other", self.sha("U"), cwd=self.dir)
        code, report = self.preview()
        self.assertEqual(sync.OK, code, report)
        self.assertEqual(["refs/remotes/official/dev", "refs/remotes/official/other"],
                         sync.lines("for-each-ref", "--format=%(refname)", "refs/remotes/official/"))
        self.assertEqual(self.sha("U"), self.sha("refs/remotes/official/dev"))
        self.assertEqual([], sync.lines("for-each-ref", "refs/upstream-sync/"))

    def test_a_cleanup_that_fails_is_a_line_and_exit_zero(self):
        real = tools.git

        def broken(*args, **kw):
            if args[0] in ("update-ref", "for-each-ref"):
                raise RuntimeError("cannot lock ref")
            return real(*args, **kw)
        tools.git = broken
        try:
            code, report = self.preview()
        finally:
            tools.git = real
        self.assertEqual(sync.OK, code, report)
        self.assertIn("official: ref not removed", report)
        self.assertIn("#7 feat: merged", report)

    def test_a_cleanup_after_a_failed_fetch_says_nothing(self):
        _, report = self.preview(url=str(self.dir / "no-such-repository"))
        self.assertNotIn("not removed", report)

    def test_an_error_that_is_no_git_failure_is_a_line_and_exit_zero(self):
        real = tools.scratch_objects

        def broken():
            raise OSError("no space left on device")
        tools.scratch_objects = broken
        try:
            code, report = self.preview()
        finally:
            tools.scratch_objects = real
        self.assertEqual(sync.OK, code, report)
        self.assertIn("official: not available (no space left on device)", report)
        self.assertEqual([], sync.lines("for-each-ref", tools.OFFICIAL_REF))

    def test_the_trial_merge_writes_no_object_to_the_repository(self):
        # The fetched objects stay until git prunes them. The merged tree and its conflicted blob must not join them.
        self.official_commit("feat: official only")
        self.preview()
        fetched = subprocess.run(["git", "rev-list", "--objects", "dev", "^U"], cwd=self.url, capture_output=True,
                                 text=True).stdout
        dangling = [l.split()[2] for l in sync.lines("fsck", "--no-progress", "--unreachable", "--no-reflogs")
                    if l.startswith("unreachable")]
        self.assertEqual(sorted(l.split()[0] for l in fetched.splitlines()), sorted(dangling))

    def test_a_failed_fetch_never_blocks(self):
        code, report = self.preview(url=str(self.dir / "no-such-repository"))
        self.assertEqual(sync.OK, code)
        self.assertIn("official: not available", report)
        self.assertIn("#7 feat: merged", report)

    def test_no_gh_is_a_note(self):
        def missing(*a):
            raise FileNotFoundError("gh")
        tools.gh_json = missing
        code, report = self.preview()
        self.assertEqual(sync.OK, code)
        self.assertIn("recent merged PRs: skipped, gh is not available", report)
        self.assertIn("latest tag", report)

    def test_a_gh_answer_of_another_shape_is_a_note(self):
        tools.gh_json = lambda *a: {"message": "API rate limit exceeded"}
        code, report = self.preview()
        self.assertEqual(sync.OK, code, report)
        self.assertIn("recent merged PRs: skipped", report)

    def test_the_skill_names_the_private_ref(self):
        text = (SKILL / "SKILL.md").read_text()
        self.assertIn(f"`{tools.OFFICIAL_REF}`", text)
        self.assertNotIn("refs/remotes/official", text)

    def test_the_config_names_official(self):
        self.assertEqual(("NuvioMedia/NuvioTV", "dev"), (CONFIG["official_repo"], CONFIG["official_branch"]))


class DueVerdictTest(unittest.TestCase):
    def verdict(self, behind=3, quiet=1.0, age=1.0):
        return tools.due_verdict(behind, quiet, age, 24, 7)[0]

    def test_level_beats_everything(self):
        self.assertEqual("level", self.verdict(behind=0, quiet=500, age=50))

    def test_a_settled_batch_is_due_from_the_exact_hour(self):
        self.assertEqual("due", self.verdict(quiet=24.0))
        self.assertEqual("wait", self.verdict(quiet=23.9))

    def test_the_weekly_floor_is_due_while_upstream_keeps_pushing(self):
        self.assertEqual("due", self.verdict(quiet=0.1, age=7.1))
        self.assertEqual("wait", self.verdict(quiet=0.1, age=7.0))


class DueRepoTest(unittest.TestCase):
    """Base commit at T, a fork commit, and an upstream that merges an old side branch. Times are in hours after T."""

    T = 1_790_000_000

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.cwd = os.getcwd()
        os.chdir(self.dir)
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
        self.commit("base", 0)
        run("git", "tag", "B", cwd=self.dir)
        run("git", "checkout", "-q", "-b", "fork", cwd=self.dir)
        self.commit("fork commit", 1)
        run("git", "checkout", "-q", "main", cwd=self.dir)

    def tearDown(self):
        os.chdir(self.cwd)
        self.tmp.cleanup()

    def env(self, hours):
        stamp = f"{self.T + int(hours * 3600)} +0000"
        return {**os.environ, "GIT_COMMITTER_DATE": stamp, "GIT_AUTHOR_DATE": stamp}

    def commit(self, subject, hours):
        run("git", "commit", "-q", "--allow-empty", "-m", subject, cwd=self.dir, env=self.env(hours))

    def merge_official(self, hours, side_hours):
        run("git", "checkout", "-q", "-b", "side", "B", cwd=self.dir)
        self.commit("official: an old commit", side_hours)
        run("git", "checkout", "-q", "main", cwd=self.dir)
        run("git", "merge", "-q", "--no-ff", "-m", "Merge official NuvioTV abc123", "side", cwd=self.dir, env=self.env(hours))

    def due(self, now_hours, cfg=None):
        return tools.due_report(cfg or {}, "main", "fork", now=self.T + now_hours * 3600)

    def test_level_at_zero_behind(self):
        run("git", "checkout", "-q", "fork", cwd=self.dir)
        self.commit("more fork", 2)
        run("git", "branch", "-f", "main", "B", cwd=self.dir)
        report = self.due(500)
        self.assertEqual(("level", 0), (report["verdict"], report["behind"]))
        self.assertIsNone(report["newest_official_merge"])

    def test_a_batch_that_just_landed_waits_and_settles_later(self):
        self.merge_official(100, side_hours=-500)
        self.commit("feat: a themed batch", 100.1)
        self.assertEqual("wait", self.due(101)["verdict"])
        report = self.due(124.1)
        self.assertEqual("due", report["verdict"])
        self.assertEqual(24.0, report["quiet_hours"])

    def test_an_old_side_commit_does_not_make_a_fresh_merge_overdue(self):
        # The side commit is three weeks old; the sync of 2026-10-31 had exactly this shape.
        self.merge_official(100, side_hours=-500)
        report = self.due(101)
        self.assertEqual("wait", report["verdict"])
        self.assertEqual(0.0, report["age_days"])

    def test_the_weekly_floor_counts_from_the_oldest_first_parent_commit(self):
        self.commit("fix: first unsynced", 10)
        self.commit("fix: still pushing", 7 * 24 + 11)
        report = self.due(7 * 24 + 11.5)
        self.assertEqual("due", report["verdict"])
        self.assertEqual(7.1, report["age_days"])
        self.assertIn("older than 7 days", report["reason"])

    def test_the_newest_official_merge_since_the_base_is_named(self):
        self.commit("fix: no merge yet", 2)
        self.assertIsNone(self.due(3)["newest_official_merge"])
        self.merge_official(5, side_hours=-100)
        self.commit("fix: after it", 6)
        merge = self.due(7)["newest_official_merge"]
        self.assertEqual("Merge official NuvioTV abc123", merge["subject"])
        self.assertEqual(2.0, merge["hours_ago"])

    def test_settings_come_from_the_config(self):
        self.commit("fix: one", 2)
        self.assertEqual("wait", self.due(4)["verdict"])
        self.assertEqual("due", self.due(4, {"settle_hours": 1})["verdict"])
        self.commit("fix: two", 49)
        self.assertEqual("due", self.due(50, {"max_age_days": 1})["verdict"])
        self.assertEqual((24, 7), (CONFIG["settle_hours"], CONFIG["max_age_days"]))

    def test_the_command_is_injectable_and_prints_one_line_or_json(self):
        self.commit("fix: one", 2)
        now = str(self.T + 3 * 3600)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = sync.main(["due", "--upstream", "main", "--fork", "fork", "--now", now, "--json"])
        self.assertEqual(sync.OK, code)
        self.assertEqual("wait", json.loads(buf.getvalue())["verdict"])
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            sync.main(["due", "--upstream", "main", "--fork", "fork", "--now", now])
        self.assertEqual(1, len(buf.getvalue().strip().splitlines()))
        self.assertTrue(buf.getvalue().startswith("due: wait: "), buf.getvalue())


class DryRunRepoTest(RepoFixture, unittest.TestCase):
    """dry-run: the old fork on B replayed onto U; upstream.txt is the file both sides edit."""

    CLEAN = [("fix: top", {"shared.txt": {1: "TOP"}}), ("fix: middle", {"fork-only.txt": {10: "line 10 fork"}})]

    def fork(self, commits, start="B"):
        run("git", "checkout", "-q", "-B", "x", start, cwd=self.dir)
        self.files = {"shared.txt": list(self.LINES), "fork-only.txt": list(self.LINES), "upstream.txt": ["u"]}
        if start != "B":
            self.files = {k: list(v) for k, v in self.upstream_files.items()}
        for subject, edits in commits:
            self.commit(subject, edits)

    def dry(self, base="B", fork="x", onto="U", as_json=True):
        a = argparse.Namespace(base=base, fork=fork, onto=onto, json=as_json)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = tools.cmd_dry_run(a, self.cfg)
        return code, json.loads(buf.getvalue()) if as_json else buf.getvalue()

    def objects(self):
        return sorted(str(p) for p in (self.dir / ".git/objects").rglob("*") if p.is_file())

    def test_a_clean_replay_has_no_stops(self):
        self.fork(self.CLEAN)
        code, report = self.dry()
        self.assertEqual(sync.OK, code)
        self.assertEqual((2, 2, []), (report["replayed"], report["clean"], report["stops"]))

    def test_a_conflict_is_a_stop_and_the_exit_stays_zero(self):
        self.fork(self.CLEAN + [("fix: fork edits the upstream file", {"upstream.txt": {1: "fork"}})])
        code, report = self.dry()
        self.assertEqual(sync.OK, code)
        self.assertEqual(3, report["replayed"])
        self.assertEqual(2, report["clean"])
        self.assertEqual([{"sha": self.sha("x")[:12], "subject": "fix: fork edits the upstream file",
                           "files": ["upstream.txt"], "protected_features": []}], report["stops"])

    def test_the_real_repository_gets_no_new_objects(self):
        self.fork(self.CLEAN + [("fix: fork edits the upstream file", {"upstream.txt": {1: "fork"}})])
        before = self.objects()
        self.dry()
        self.assertEqual(before, self.objects())

    def test_commits_chain_in_order(self):
        # The second commit edits a line only the first one put there, so it merges only on top of the first.
        self.fork([("feat: add a line", {"fork-only.txt": {21: "added"}}),
                   ("fix: edit that line", {"fork-only.txt": {21: "edited"}})])
        _, report = self.dry()
        self.assertEqual((2, []), (report["clean"], report["stops"]))

    def test_the_chain_continues_from_the_ours_result(self):
        # -X ours keeps upstream's line, so the next commit on the same line stops as well. Merging it on the
        # fork's own result would be clean: that is why a stop can be a false alarm.
        self.fork([("fix: one", {"upstream.txt": {1: "fork one"}}), ("fix: two", {"upstream.txt": {1: "fork two"}})])
        _, report = self.dry()
        self.assertEqual(["fix: one", "fix: two"], [s["subject"] for s in report["stops"]])

    def test_a_conflict_the_strategy_cannot_settle_still_moves_on(self):
        run("git", "checkout", "-q", "U", cwd=self.dir)
        run("git", "rm", "-q", "upstream.txt", cwd=self.dir)
        run("git", "commit", "-q", "-m", "upstream drops the file", cwd=self.dir)
        run("git", "tag", "U2", cwd=self.dir)
        self.fork([("fix: edit a deleted file", {"upstream.txt": {1: "fork"}}),
                   ("fix: after it", {"fork-only.txt": {3: "fork"}})], start="U")
        _, report = self.dry(base="U", onto="U2")
        self.assertEqual(["fix: edit a deleted file"], [s["subject"] for s in report["stops"]])
        self.assertEqual(1, report["clean"])

    def test_a_later_commit_sees_the_clean_changes_of_a_commit_the_strategy_cannot_settle(self):
        # The first commit also adds a line to fork-only.txt, and the second one edits that line. Throwing the first
        # commit away would turn the second into a false stop.
        run("git", "checkout", "-q", "U", cwd=self.dir)
        run("git", "rm", "-q", "upstream.txt", cwd=self.dir)
        run("git", "commit", "-q", "-m", "upstream drops the file", cwd=self.dir)
        run("git", "tag", "U2", cwd=self.dir)
        self.fork([("fix: edit a deleted file and add a line", {"upstream.txt": {1: "fork"}, "fork-only.txt": {21: "added"}}),
                   ("fix: edit the added line", {"fork-only.txt": {21: "edited"}})], start="U")
        _, report = self.dry(base="U", onto="U2")
        self.assertEqual(["fix: edit a deleted file and add a line"], [s["subject"] for s in report["stops"]])
        self.assertEqual(["upstream.txt"], report["stops"][0]["files"])
        self.assertEqual(1, report["clean"])

    def test_a_stop_names_the_protected_feature_by_commit_and_by_file(self):
        self.fork([("feat(dv): HLG conversion edits the upstream file", {"upstream.txt": {1: "fork"}})])
        self.assertEqual(["hlg-to-dv"], self.dry()[1]["stops"][0]["protected_features"])
        self.cfg["protected_features"] = [{"key": "feat", "anchors": ["^nothing$"], "binaries": ["upstream.txt"]}]
        self.fork([("fix: plain subject", {"upstream.txt": {1: "fork"}})])
        self.assertEqual(["feat"], self.dry()[1]["stops"][0]["protected_features"])
        self.cfg["protected_features"] = []
        self.assertEqual([], self.dry()[1]["stops"][0]["protected_features"])

    def test_a_commit_upstream_already_holds_comes_out_empty(self):
        self.fork([("fix: same as upstream", {"upstream.txt": {1: "u2"}})])
        _, report = self.dry()
        self.assertEqual(["fix: same as upstream"], [e["subject"] for e in report["empty"]])
        self.assertEqual(0, report["clean"])

    def test_an_empty_commit_is_a_stop_on_the_interactive_path(self):
        # What the report says about git: rebase -i stops on a commit that comes out empty, a plain rebase drops it.
        self.fork([("fix: same as upstream", {"upstream.txt": {1: "u2"}}), *self.CLEAN])
        _, text = self.dry(as_json=False)
        self.assertRegex(text, r"(?m)^dry-run: .*: 2 clean, 0 predicted stops, 1 empty \(rebase -i stops, a plain rebase drops\)$")
        self.assertRegex(text, rf"(?m)^empty {self.sha('x~2')[:9]} fix: same as upstream \(rebase -i stops here")
        env = {**os.environ, "GIT_SEQUENCE_EDITOR": "true"}
        r = subprocess.run(["git", "rebase", "-i", "--onto", "U", "B", "x"], cwd=self.dir, env=env, capture_output=True, text=True)
        self.assertNotEqual(0, r.returncode, r.stdout)
        run("git", "rebase", "--abort", cwd=self.dir)
        run("git", "rebase", "--onto", "U", "B", "x", cwd=self.dir)
        self.assertEqual(2, int(self.count("U..x")))

    def count(self, rng):
        return subprocess.run(["git", "rev-list", "--count", rng], cwd=self.dir, capture_output=True, text=True).stdout

    def test_the_counts_add_up(self):
        self.fork([("fix: same as upstream", {"upstream.txt": {1: "u2"}}), *self.CLEAN,
                   ("fix: fork edits the upstream file", {"upstream.txt": {1: "fork"}})])
        _, report = self.dry()
        self.assertEqual(report["replayed"], report["clean"] + len(report["stops"]) + len(report["empty"]))

    def test_the_skill_states_the_measured_track_record(self):
        map_step = (SKILL / "SKILL.md").read_text().split("## 3. Map")[1].split("\n## ")[0]
        self.assertNotIn("none missed", map_step)
        self.assertIn("54 predicted stops, 38 real, on four syncs", map_step)
        self.assertRegex(map_step, r"`empty` lists .*: `rebase -i` stops on each one")

    def test_the_output_names_the_strategy_and_its_limits(self):
        self.fork(self.CLEAN)
        _, text = self.dry(as_json=False)
        self.assertIn("merge-tree", text)
        self.assertIn("-X ours", text)
        self.assertIn("false alarms are expected", text)
        self.assertIn("A clean replay says nothing about meaning", text)
        _, report = self.dry()
        self.assertEqual({"strategy", "note", "base", "fork", "onto", "replayed", "clean", "stops", "empty"},
                         set(report))

    def test_a_git_failure_exits_two(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(sync.USAGE, sync.main(["dry-run", "--base", "B", "--fork", "no-such-ref", "--onto", "U"]))

    def test_it_runs_as_a_script_in_isolated_mode(self):
        # python3 -I keeps the script's directory off sys.path, so the sibling module must still load.
        self.fork(self.CLEAN)
        r = subprocess.run([sys.executable, "-I", str(SKILL / "scripts/sync.py"), "dry-run", "--base", "B", "--fork", "x",
                            "--onto", "U", "--json"], cwd=self.dir, capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(2, json.loads(r.stdout)["clean"])


if __name__ == "__main__":
    unittest.main()
