"""Checkpoint begin: Java qualified calls with literal-free args are not
credentials, and lease-overlap warnings stay advisory (never block begin)."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest

from scripts.test_codex_work_checkpoint import CP, decision


KEYWORD = "to" + "ken"
SETTING = "api" + "Key"


def java_file(text):
    return "class E { void f() { " + text + " } }\n"


def statement(expr, name=KEYWORD):
    return name + " = " + expr


class JavaCallWithArgsCheckpointTest(unittest.TestCase):
    """Calls with literal-free args were rejected when the exemption only
    covered zero-argument calls; literals inside args still overlap protected
    spans and keep the assignment flagged."""

    def assert_exempt(self, expr, name=KEYWORD):
        CP.secret_free(java_file(statement(expr, name)).encode(), "main/java/E.java")

    def assert_strict(self, expr, name=KEYWORD):
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(java_file(statement(expr, name)).encode(), "main/java/E.java")

    def test_literal_free_call_args_are_exempt(self):
        for expr in ("Normalizer.normalize(wake.strip(),Normalizer.Form.NFD);",
                     "resolver.read(value);",
                     "opts.get(first,second,third);",
                     "outer.call(inner.make());",
                     "config.load();"):
            with self.subTest(expr=expr):
                self.assert_exempt(expr)

    def test_each_secret_word_uses_same_rule(self):
        for name in (KEYWORD, SETTING, "pass" + "word", "client" + "Secret"):
            with self.subTest(name=name):
                self.assert_exempt("holder.resolve(arg);", name)

    def test_literal_and_partial_forms_stay_blocked(self):
        for expr in ('resolver.read("synthetic");',
                     '"literal";',
                     "resolver.read(value);suffix",
                     "read(value);",
                     "Constants.DEFAULT_VALUE;",
                     "synthetic_value;",
                     "a.b(x, y);",
                     "resolver.read("):
            with self.subTest(expr=expr):
                self.assert_strict(expr)

    def test_rhs_still_scanned_for_prefixed_material(self):
        self.assert_strict("gsk" + "_" + "x" * 32 + ".read(value);")

    def test_comment_string_and_block_mimics_stay_blocked(self):
        expr = statement("holder.resolve(arg);")
        for text in ('"' + expr + '"', "'" + expr + "'",
                     "// " + expr, "/* " + expr + " */",
                     '"""\n' + expr + '\n"""'):
            with self.subTest(kind=text[:3]):
                with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                    CP.secret_free(text.encode(), "main/java/E.java")

    def test_following_secret_on_same_or_next_line_stays_blocked(self):
        for suffix in (' ' + statement('"synthetic";'),
                       '\n' + statement('"synthetic";'),
                       "\nAuthorization: synthetic header"):
            with self.subTest(suffix=suffix[:9]):
                self.assert_strict("holder.resolve(arg);" + suffix)


class BeginOverlapWarningTest(unittest.TestCase):
    """begin reports active/expired foreign leases covering declared targets;
    the caller's own recorded lease never warns."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.target = "main/java/Example.java"
        source = self.root / self.target
        source.parent.mkdir(parents=True)
        source.write_text("class Example {}\n")
        self.run = "data/agent-handoff/codex-autonomy/overlap-warning-test"

    def lease(self, name, paths, minutes=5):
        lock = self.root / "__patch_drop__" / "source-edit-locks" / (name + ".lock")
        lock.mkdir(parents=True)
        (lock / "lease.json").write_text(json.dumps(dict(
            root=str(self.root), ownerId="other-owner", mutationAllowed=True,
            coordinationMode="target-scoped", targetPaths=paths, topic=name,
            expiresAtUtc=(datetime.now(timezone.utc) + timedelta(minutes=minutes)).isoformat())))
        return "__patch_drop__/source-edit-locks/" + name + ".lock/lease.json"

    def test_active_foreign_lease_warns_and_own_lease_is_skipped(self):
        self.lease("foreign-task", [self.target])
        own = self.lease("own-task", [self.target])
        result = CP.begin(self.root, self.run, [self.target], decision(), own)
        warnings = result["leaseOverlapWarnings"]
        self.assertTrue(any("foreign-task.lock" in w and ":active:" in w for w in warnings), warnings)
        self.assertFalse(any("own-task.lock" in w for w in warnings), warnings)

    def test_expired_lease_is_labeled_and_unrelated_lease_ignored(self):
        self.lease("stale-task", [self.target], minutes=-5)
        self.lease("other-task", ["docs/unrelated.md"])
        own = self.lease("own-task", [self.target])
        result = CP.begin(self.root, self.run, [self.target], decision(), own)
        warnings = result["leaseOverlapWarnings"]
        self.assertTrue(any("stale-task.lock" in w and ":expired:" in w for w in warnings), warnings)
        self.assertFalse(any("other-task.lock" in w for w in warnings), warnings)
        self.assertFalse(any("own-task.lock" in w for w in warnings), warnings)

    def test_artifact_target_warns_without_any_lease(self):
        doc = self.root / "docs" / "notes.md"
        doc.parent.mkdir(parents=True, exist_ok=True)
        doc.write_text("notes\n")
        self.lease("doc-writer", ["docs/notes.md"])
        result = CP.begin(self.root, self.run, ["docs/notes.md"], decision())
        self.assertTrue(any("doc-writer.lock" in w for w in result["leaseOverlapWarnings"]),
                        result["leaseOverlapWarnings"])

    def test_no_locks_dir_or_unrelated_locks_warns_nothing(self):
        result = CP.begin(self.root, self.run, ["docs/notes2.md"], decision())
        self.assertEqual([], result["leaseOverlapWarnings"])
        self.lease("other-task", ["docs/unrelated.md"])
        own = self.lease("own-task", [self.target])
        result = CP.begin(self.root, "data/agent-handoff/codex-autonomy/overlap-warning-test-2",
                          [self.target], decision(), own)
        self.assertEqual([], result["leaseOverlapWarnings"])


if __name__ == "__main__":
    unittest.main()
