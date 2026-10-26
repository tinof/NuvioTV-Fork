import argparse
import contextlib
import importlib.util
import io
import json
import os
import subprocess
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


def run(*args, cwd):
    subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True)


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


class VerifyRepoTest(unittest.TestCase):
    """verify against a small repository: base B, upstream U on top of B, an old fork on B and a new one on U."""

    LINES = [f"line {n}" for n in range(1, 21)]

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.cwd = os.getcwd()
        os.chdir(self.dir)
        run("git", "init", "-q", "-b", "main", cwd=self.dir)
        run("git", "config", "user.email", "t@example.com", cwd=self.dir)
        run("git", "config", "user.name", "t", cwd=self.dir)
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

    def verify(self, **extra):
        a = argparse.Namespace(base_old="B", old="old", base_new="U", new="new", upstream="U", main_new=None,
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

    def test_decisions_supply_the_drop(self):
        self.new_branch(self.FAITHFUL[1:])
        path = self.dir / "classify.json"
        path.write_text(json.dumps({"groups": [{"commits": [{"sha": self.sha("old~1")[:12], "verdict": "drop-superseded"}]}]}))
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


    def test_a_decided_drop_from_the_other_layer_is_only_a_note(self):
        self.new_branch(self.FAITHFUL)
        path = self.dir / "classify.json"
        path.write_text(json.dumps({"summary": {"drop": ["abcdef0123"]}, "groups": []}))
        code, report = self.verify(decisions=str(path))
        self.assertEqual(0, code, report)
        self.assertIn("decided drop abcdef0123 belongs to the other layer", report)

    def test_drops_prints_only_the_layer_s_drops(self):
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
        a = argparse.Namespace(base_old="B", old="old", base_new="U", new="new", upstream="U", main_new=None,
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

if __name__ == "__main__":
    unittest.main()
