"""Task-scoped context selection contracts; synthetic files, zero provider calls."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import gptpro_pack_context as ctx
from codex_work_checkpoint import digest


def contract():
    return dict(schemaVersion="awx.task-continuity.v1", taskId="fixture", revision=1,
                instructionRef="user:latest", observedAt="2026-10-08T12:00:00Z",
                goal="API HUB resolver AUTO3", nonGoals=["Resume cancelled management"],
                taskStatus="paused", supersedes=None, remaining=["NOT_RUN: provider"],
                blockers=[], nextAction="Wait for explicit resume", accessFailures=[],
                preferenceEvents=[], deliverables=[dict(id="report", version="v1",
                source="C:/fixture/report.md", sha256="a" * 64, deliveries=[dict(
                method="file", required=True, destination="C:/Users/fixture/Downloads/report.md",
                environment="fixture-host", status="NOT_RUN", evidence={})])])


class SelectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="context-selection-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("docs/current.md", "# AUTO3\nCurrent query limit: 3\nAPI HUB resolver\n")
        self.write("docs/legacy.md", "# Legacy AUTO10\nOld query limit: 10\n")
        self.write("docs/failure.md", "# Adverse evidence\nFAIL: resolver mismatch\n")
        self.write("docs/noise.md", "# Noise\n" + "unrelated 잡음 " * 3000 + "\n")
        self.rows = [dict(path="docs/current.md", role="current", version="worktree"),
                     dict(path="docs/legacy.md", version="historical-10"),
                     dict(path="docs/failure.md", role="counterevidence"),
                     dict(path="docs/noise.md")]
        self.allowed = [r["path"] for r in self.rows]

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def build(self, rows=None, **kwargs):
        return ctx.build_selection_pack(self.root, contract(), rows or self.rows,
            allowed=self.allowed, query="AUTO3 API HUB resolver", latest_ref="user:latest",
            max_bytes=kwargs.pop("max_bytes", 5000), **kwargs)

    def test_pinned_contract_and_counterevidence_survive_noise(self):
        pack = self.build()
        self.assertEqual(pack["contract"], contract())
        self.assertEqual(pack["contract"]["taskStatus"], "paused")
        paths = [r["path"] for r in pack["selected"]]
        self.assertIn("docs/current.md", paths)
        self.assertIn("docs/failure.md", paths)
        self.assertEqual(next(r for r in pack["excluded"] if r["path"] == "docs/noise.md")["reason"], "budget")
        self.assertLessEqual(len(ctx.selection_json(pack).encode("utf-8")), 5000)

    def test_versions_and_original_coordinates(self):
        pack = self.build()
        versions = {r["path"]: r["version"] for r in pack["selected"]}
        self.assertEqual(versions["docs/legacy.md"], "historical-10")
        for row in pack["selected"] + pack["excluded"]:
            raw = (self.root / row["path"]).read_bytes()
            self.assertEqual(row["sha256"], digest(raw))
            self.assertTrue(row["sourceModifiedAt"])
            if "excerpt" in row:
                text = raw.decode("utf-8").splitlines(keepends=True)
                self.assertEqual(row["excerpt"], "".join(text[row["start"] - 1:row["end"]]))
                self.assertEqual(row["excerptSha256"], digest(row["excerpt"].encode("utf-8")))

    def test_complete_korean_json_and_no_byte_truncation(self):
        self.write("docs/data.json", json.dumps({"설명": ["한국어", "API HUB"]}, ensure_ascii=False))
        self.allowed.append("docs/data.json")
        pack = self.build([dict(path="docs/data.json", required=True)])
        self.assertEqual(json.loads(pack["selected"][0]["excerpt"])["설명"][0], "한국어")
        self.assertEqual(json.loads(ctx.selection_json(pack)), pack)
        with self.assertRaisesRegex(ValueError, "mandatory-budget"):
            self.build(max_bytes=100)

    def test_duplicate_content_preserves_aliases_and_mandatory_role(self):
        self.write("docs/copy.md", (self.root / "docs/current.md").read_text(encoding="utf-8"))
        self.allowed.append("docs/copy.md")
        pack = self.build([dict(path="docs/copy.md"), dict(path="docs/current.md", required=True)])
        self.assertEqual(len(pack["selected"]), 1)
        self.assertTrue(pack["selected"][0]["required"])
        self.assertEqual(pack["excluded"][0]["reason"], "duplicate")
        self.assertEqual(pack["excluded"][0]["duplicateOf"], pack["selected"][0]["id"])

    def test_file_query_and_rule_changes_invalidate_fingerprint(self):
        a = self.build()["cacheKey"]
        self.write("docs/legacy.md", "# Updated\nAUTO3 now\n")
        b = self.build()["cacheKey"]
        c = self.build(rule_version="fixture-next")["cacheKey"]
        self.assertEqual(len({a, b, c}), 3)
        args = dict(allowed=self.allowed, latest_ref="user:latest", max_bytes=5000)
        d = ctx.build_selection_pack(self.root, contract(), self.rows, query="other", **args)["cacheKey"]
        self.assertNotEqual(b, d)

    def test_old_instruction_ref_is_refused(self):
        with self.assertRaisesRegex(ValueError, "latest-instruction-mismatch"):
            ctx.build_selection_pack(self.root, contract(), self.rows, allowed=self.allowed,
                                     query="x", latest_ref="user:new")

    def test_scope_and_protected_paths_fail_before_read(self):
        for path in ("../outside.md", "docs/unlisted.md", ".secrets/key.md",
                     "main/resources/application-local.yml", "data/sessions/private.md"):
            with self.subTest(path=path), mock.patch.object(Path, "read_bytes", side_effect=AssertionError("opened")):
                with self.assertRaises(ValueError):
                    ctx.build_selection_pack(self.root, contract(), [dict(path=path)],
                        allowed=self.allowed if path == "docs/unlisted.md" else self.allowed + [path],
                        query="x", latest_ref="user:latest")

    def test_missing_optional_requery_and_missing_required_failure(self):
        self.allowed.append("docs/missing.md")
        pack = self.build([dict(path="docs/missing.md")])
        self.assertEqual(pack["excluded"][0]["reason"], "missing")
        self.assertEqual(pack["requery"], ["docs/missing.md"])
        with self.assertRaisesRegex(ValueError, "required-evidence-missing"):
            self.build([dict(path="docs/missing.md", role="acceptance")])

    def test_preimage_mismatch_is_refused(self):
        with self.assertRaisesRegex(ValueError, "source-version-mismatch"):
            self.build([dict(path="docs/current.md", sha256="0" * 64)])

    def test_invalid_line_range_is_refused(self):
        for start, end in ((0, 1), (2, 1), (1, 999), (True, 1)):
            with self.subTest(start=start, end=end), self.assertRaisesRegex(ValueError, "line-range"):
                self.build([dict(path="docs/current.md", start=start, end=end)])

    def test_source_drift_during_map_is_refused(self):
        def drift(*args, **kwargs):
            self.write("docs/current.md", "changed\n")
            return "map"
        with mock.patch.object(ctx, "build_code_map", side_effect=drift):
            with self.assertRaisesRegex(ValueError, "source-drift"):
                self.build()

    def test_selection_map_does_not_expand_reads_or_emit_values(self):
        self.write("main/java/Entry.java", 'class Entry {\n@Value("${setting:private-default}")\nString value;\n}\n')
        self.allowed.append("main/java/Entry.java")
        with mock.patch.object(ctx, "_flatten_yml", side_effect=AssertionError("config read")), \
             mock.patch.object(Path, "glob", side_effect=AssertionError("glob")), \
             mock.patch.object(Path, "rglob", side_effect=AssertionError("rglob")), \
             mock.patch.object(ctx, "build_all", side_effect=AssertionError("build_all")):
            pack = self.build([dict(path="main/java/Entry.java")])
        self.assertNotIn("private-default", pack["codeMap"])
        self.assertIn("setting", pack["codeMap"])

    def test_offline_comparison_failures_fallback_and_preserve_pins(self):
        for status in ("timeout", "401", "403", "429", "empty", "invalid-schema"):
            with self.subTest(status=status):
                pack = self.build(comparison=dict(status=status))
                self.assertEqual(pack["comparisonStatus"], "local-fallback")
                self.assertEqual(pack["contract"], contract())
                self.assertIn("docs/failure.md", [r["path"] for r in pack["selected"]])
                self.assertEqual(pack["externalCalls"], 0)

    def test_stale_and_invalid_comparison_are_refused_to_rank(self):
        pack = self.build()
        for ranks in ([], ["made-up"], ["docs/current.md"] * 4):
            other = self.build(comparison=dict(status="ok", cacheKey=pack["cacheKey"], rankedIds=ranks))
            self.assertEqual(other["comparisonStatus"], "local-fallback")
        stale = self.build(comparison=dict(status="ok", cacheKey="old", rankedIds=[]))
        self.assertEqual(stale["comparisonStatus"], "local-fallback")

    def test_cli_reads_checkpoint_and_writes_no_source(self):
        self.write("state.md", "goal: fixture\ncontinuity: " + json.dumps(contract()) + "\n")
        manifest = dict(allowed=self.allowed, candidates=self.rows, query="AUTO3 API HUB", maxBytes=5000)
        self.write("selection.json", json.dumps(manifest))
        before = {p: (self.root / p).read_bytes() for p in self.allowed}
        result = subprocess.run([sys.executable, "-B", str(Path(ctx.__file__)), "select",
            "--root", str(self.root), "--state", "state.md", "--manifest", "selection.json",
            "--latest-instruction-ref", "user:latest"], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["contract"], contract())
        self.assertEqual(before, {p: (self.root / p).read_bytes() for p in self.allowed})

    def test_runtime_configs_and_cli_control_types_rejected_before_open(self):
        for name in ("configs/local.yaml", "configs/api-routing.yaml", "docs/local.properties"):
            self.write(name, "demo.mode=private-value\n")
            with self.subTest(name=name), mock.patch.object(Path, "open", side_effect=AssertionError("opened")):
                with self.assertRaisesRegex(ValueError, "protected-selection-path"):
                    ctx.build_selection_pack(self.root, contract(), [dict(path=name)],
                        allowed=[name], query="x", latest_ref="user:latest")
        self.write("state.md", "continuity: " + json.dumps(contract()) + "\n")
        self.write("main/resources/application-local.yml", '{}')
        with mock.patch.object(Path, "open", side_effect=AssertionError("opened")):
            self.assertEqual(ctx.selection_main(["select", "--root", str(self.root), "--state", "state.md",
                "--manifest", "main/resources/application-local.yml", "--latest-instruction-ref", "user:latest"]), 2)

    def test_map_reuses_snapshots_and_all_reads_are_bounded(self):
        self.write("main/java/config/Application.java", "class Application {\n}\n")
        self.allowed.append("main/java/config/Application.java")
        with mock.patch.object(ctx, "_read", side_effect=AssertionError("unbounded map read")):
            self.build([dict(path="main/java/config/Application.java", required=True)])
        # Every source read must use a finite size even if its stat is stale.
        original = Path.open
        sizes = []
        class Stream:
            def __init__(self, p, *args, **kwargs):
                self.f = original(p, *args, **kwargs)
            def __enter__(self):
                return self
            def __exit__(self, *args):
                self.f.close()
            def read(self, size=-1):
                sizes.append(size)
                return self.f.read(size)
        with mock.patch.object(Path, "open", lambda p, *args, **kwargs: Stream(p, *args, **kwargs)):
            self.build()
        self.assertTrue(sizes)
        self.assertTrue(all(0 < size <= 2 * 1024 * 1024 + 1 for size in sizes))

    def test_conflicting_locator_versions_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate-locator"):
            self.build([dict(path="docs/current.md", version="v1"), dict(path="docs/current.md", version="v2")])

    def test_valid_offline_order_never_removes_mandatory_evidence(self):
        first = self.build()
        ids = [r["id"] for r in first["selected"] + first["excluded"] if not r["required"]]
        pack = self.build(comparison=dict(status="ok", cacheKey=first["cacheKey"], rankedIds=list(reversed(ids))))
        self.assertEqual(pack["comparisonStatus"], "offline-order-applied")
        self.assertEqual(pack["contract"], contract())
        self.assertEqual({r["path"] for r in pack["selected"] if r["required"]}, {"docs/current.md", "docs/failure.md"})

    def test_complete_json_and_public_example_config_only(self):
        self.write("docs/data.json", '["한글", "API HUB"]\n')
        self.allowed.append("docs/data.json")
        with self.assertRaisesRegex(ValueError, "json-range-must-be-whole"):
            self.write("docs/data.json", '[\n"한글", "API HUB"\n]\n')
            self.build([dict(path="docs/data.json", start=2, end=2)])
        self.write("configs/selection.example.yaml", "query: synthetic\n")
        self.allowed.append("configs/selection.example.yaml")
        pack = self.build([dict(path="configs/selection.example.yaml")])
        self.assertEqual(pack["selected"][0]["excerpt"], (self.root / "configs/selection.example.yaml").read_bytes().decode("utf-8"))

    def test_secret_shaped_content_fails_without_export(self):
        value = "sk-" + "proj-" + "Ab3" * 24
        self.write("docs/sensitive.txt", "OPENAI_API_KEY" + "=" + value + "\n")
        self.allowed.append("docs/sensitive.txt")
        with self.assertRaisesRegex(ValueError, "secret") as caught:
            self.build([dict(path="docs/sensitive.txt")])
        self.assertNotIn(value, str(caught.exception))


if __name__ == "__main__":
    unittest.main()
