import argparse
import contextlib
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SKILL = Path(__file__).resolve().parents[2] / ".claude/skills/upstream-sync"


def load(name):
    spec = importlib.util.spec_from_file_location(f"upstream_sync_{name}", SKILL / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load("sync")
tools = sys.modules["sync_tools"]
both = load("both")
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
                code = sync.cmd_protected_apk(argparse.Namespace(apk=str(apk)), CONFIG)
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
