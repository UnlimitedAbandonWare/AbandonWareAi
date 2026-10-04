"""Offline synthetic contract tests; never read real task or session bodies."""
import hashlib
import importlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock


def digest(data):
    return hashlib.sha256(data).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


class ContextTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.addCleanup(mock.patch.stopall)
        mock.patch.dict(os.environ, {"USERPROFILE": str(self.root)}, clear=True).start()
        (self.root / "configs").mkdir()
        (self.root / "configs/agent-paths.yaml").write_text(
            "paths:\n  handoff.root:\n    path: handoff\n    env: AWX_PATH_HANDOFF_ROOT\n"
            "  journal.base:\n    path: journals\n    env: AWX_PATH_JOURNAL_BASE\n", encoding="utf-8")
        self.journal("taskA", [{"kind": "verify", "text": "PASS", "at": "2026-10-04T00:00:00Z"}])
        self.source = self.root / "source.py"
        self.source.write_bytes(b"original\n")

    def api(self):
        return importlib.import_module("task_context")

    def journal(self, task, events):
        save(self.root / "journals" / task / "journal.json", {
            "schemaVersion": "awx.work_journal.v1", "taskId": task,
            "purpose": task + " synthetic goal", "status": "in_progress",
            "events": events, "plannedScope": ["source.py"]})

    def receipt(self, task="taskA", ticket="ticketA"):
        store = self.root / "handoff/coop-verify"
        statefile = store / "state.json"
        state = json.loads(statefile.read_text()) if statefile.exists() else {"tickets": {}}
        state["tickets"][ticket] = {"ticketId": ticket, "taskId": task,
            "verifyCommand": ["python", "synthetic.py"], "scope": ["source.py"],
            "receiptPath": "receipts/" + ticket + ".json"}
        save(statefile, state)
        entries = [{"path": "source.py", "bytes": self.source.stat().st_size,
                    "sha256": digest(self.source.read_bytes())}]
        manifest = {"entries": entries, "fileCount": 1,
                    "manifestSha256": digest(json.dumps(entries, sort_keys=True, ensure_ascii=True).encode())}
        receipt = {"schemaVersion": "awx.coop-verify.v1.receipt", "cwd": str(self.root),
                   "ticketId": ticket, "verdict": "VERIFIED_PASS", "exitCode": 0,
                   "inputManifestBefore": manifest, "inputManifestAfter": manifest,
                   "verifyCommand": ["python", "synthetic.py"], "interferingWriters": []}
        save(store / "receipts" / (ticket + ".json"), receipt)
        return store / "receipts" / (ticket + ".json")

    def test_scope_does_not_mix_tasks(self):
        self.journal("taskB", [{"kind": "verify", "text": "B_ONLY_SECRETLESS_EVENT"}])
        self.receipt("taskB", "B_ONLY_TICKET")
        result = self.api().build(self.root, "taskA", dry_run=True)
        self.assertNotIn("B_ONLY", json.dumps(result))
        self.assertEqual(result["taskId"], "taskA")

    def test_reported_pass_is_not_verified(self):
        result = self.api().build(self.root, "taskA", dry_run=True)
        row = result["verification"][0]
        self.assertEqual(row["reported"], "PASS")
        self.assertIsNone(row["receipt"])
        self.assertNotIn(result["state"], ("PASS", "VERIFIED_PASS"))

    def test_not_run_has_null_exit(self):
        row = self.api().build(self.root, "taskA", dry_run=True)["verification"][0]
        self.assertIsNone(row["exitCode"])
        self.assertEqual(row["state"], "NOT_RUN")

    def test_stale_pass_same_head_changed_file(self):
        self.receipt()
        self.source.write_bytes(b"changed!\n")
        result = self.api().build(self.root, "taskA", dry_run=True)
        row = next(r for r in result["verification"] if r["receipt"])
        self.assertFalse(row["currentApplicable"])
        self.assertEqual(row["state"], "STALE_PASS")
        self.assertIn("source.py", json.dumps(result["nextCheck"]))



    def current(self):
        return self.root / "journals/taskA/context/current.json"

    def test_interrupted_publish_keeps_previous_current(self):
        api = self.api()
        api.build(self.root, "taskA")
        before = self.current().read_bytes()
        original = api._write_file
        def interrupted(path, data):
            if path.name == "sources.json":
                raise OSError("synthetic interruption")
            original(path, data)
        with mock.patch.object(api, "_write_file", side_effect=interrupted):
            with self.assertRaises(OSError):
                api.build(self.root, "taskA")
        self.assertEqual(before, self.current().read_bytes())
        self.assertEqual(len(list(self.current().parent.glob("r*"))), 1)
        self.assertTrue(list(self.current().parent.glob(".tmp-*")))

    def test_concurrent_build_returns_busy(self):
        import concurrent.futures
        import threading
        api = self.api()
        entered, release = threading.Event(), threading.Event()
        original = api._write_file
        def blocked(path, data):
            if path.name == "context.json":
                entered.set()
                if not release.wait(5):
                    raise RuntimeError("test timed out")
            original(path, data)
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            with mock.patch.object(api, "_write_file", side_effect=blocked):
                future = pool.submit(api.build, self.root, "taskA")
                try:
                    self.assertTrue(entered.wait(5))
                    with self.assertRaisesRegex(api.ContextError, "BUSY"):
                        api.build(self.root, "taskA")
                finally:
                    release.set()
                future.result(timeout=5)

    def test_truncated_or_corrupt_journal_is_visible(self):
        api = self.api()
        path = self.root / "journals/taskA/journal.json"
        for raw in (b'{"events":[', b"x" * (api.JOURNAL_LIMIT + 1)):
            path.write_bytes(raw)
            result = api.build(self.root, "taskA")
            self.assertGreater(result["counts"]["unreadable"], 0)
            self.assertEqual(path.read_bytes(), raw)

    def test_caps_truncate_with_counts(self):
        api = self.api()
        self.journal("taskA", [{"kind": "note", "text": "x" * 300}
                              for _ in range(api.EVENT_LIMIT + 11)])
        result = api.build(self.root, "taskA")
        pointer = json.loads(self.current().read_bytes())
        revision = self.current().parent / pointer["revision"]
        self.assertTrue(result["truncated"])
        self.assertGreaterEqual(result["counts"]["eventsOmitted"], 11)
        self.assertLessEqual((revision / "context.json").stat().st_size, api.JSON_LIMIT)
        self.assertLessEqual((revision / "context.md").stat().st_size, api.MD_LIMIT)
        self.assertTrue(all(len(e["text"]) <= 240 for e in result["events"]))

    def test_no_secret_or_abs_path_in_context(self):
        self.journal("taskA", [{"kind": "note",
            "text": r"PASS sk-canary-XXXX C:\Users\someone prompt: private raw body"}])
        self.api().build(self.root, "taskA")
        all_bytes = b"".join(p.read_bytes() for p in self.current().parent.rglob("*") if p.is_file())
        for value in (b"sk-canary-XXXX", b"someone", b"private raw body"):
            self.assertNotIn(value, all_bytes)



    def test_verify_detects_stale_after_build(self):
        api = self.api()
        self.receipt()
        api.build(self.root, "taskA")
        self.assertEqual(api.verify(self.root, "taskA")["state"], "FRESH")
        journal = self.root / "journals/taskA/journal.json"
        journal.write_bytes(b"unreadable journal must not affect verify")
        self.assertEqual(api.verify(self.root, "taskA")["state"], "FRESH")
        self.source.write_bytes(b"different")
        self.assertEqual(api.verify(self.root, "taskA")["state"], "STALE")

    def test_verify_detects_corrupt_revision(self):
        api = self.api()
        api.build(self.root, "taskA")
        pointer = json.loads(self.current().read_bytes())
        path = self.current().parent / pointer["revision"] / "context.md"
        path.write_bytes(b"modified")
        self.assertEqual(api.verify(self.root, "taskA")["state"], "CORRUPT")

    def test_list_bounded(self):
        api = self.api()
        for n in range(40):
            self.journal("task" + str(n), [])
            api.build(self.root, "task" + str(n))
        with mock.patch.object(Path, "read_bytes", autospec=True, side_effect=Path.read_bytes) as reads:
            rows = api.list_contexts(self.root, limit=3)
            self.assertEqual(len(rows), 3)
            self.assertLessEqual(reads.call_count, 3)
        self.assertEqual(api.list_contexts(self.root, limit=0), [])



    def test_invalid_exit_code_cannot_prove_pass(self):
        api = self.api()
        for code in (False, 0.0, "0"):
            row = api.evidence_row(self.root, "synthetic", "VERIFIED_PASS", code,
                                   [{"path": "source.py", "sha256": digest(b"x"),
                                     "absent": False, "currentApplicable": True}])
            self.assertNotEqual(row["state"], "VERIFIED_PASS")
            self.assertIsNone(row["exitCode"])

    def test_command_mismatch_cannot_prove_pass(self):
        receipt_path = self.receipt()
        state_path = receipt_path.parent.parent / "state.json"
        state = json.loads(state_path.read_bytes())
        state["tickets"]["ticketA"]["verifyCommand"] = ["python", "different.py"]
        save(state_path, state)
        result = self.api().build(self.root, "taskA", dry_run=True)
        row = next(r for r in result["verification"] if r["receipt"])
        self.assertFalse(row["receipt"]["valid"])
        self.assertNotEqual(row["state"], "VERIFIED_PASS")

    def test_published_rows_preserve_binding_references(self):
        self.receipt()
        result = self.api().build(self.root, "taskA", dry_run=True)
        row = next(r for r in result["verification"] if r["receipt"])
        self.assertEqual(len(row["bindingIds"]), 1)
        self.assertEqual(row["bindingIds"][0], result["bindings"][0]["id"])

    def test_registry_environment_and_dry_run_do_not_write(self):
        api = self.api()
        new_base = self.root / "relocated"
        new_base.mkdir()
        original = self.root / "journals/taskA"
        original.rename(new_base / "taskA")
        with mock.patch.dict(os.environ, {"AWX_PATH_JOURNAL_BASE": str(new_base)}):
            before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
            result = api.build(self.root, "taskA", dry_run=True)
            after = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
            self.assertEqual(before, after)
            self.assertEqual(result["goal"], "taskA synthetic goal")

    def test_checkpoint_manifest_task_and_postimage_binding(self):
        api = self.api()
        directory = self.root / "journals/taskA/cycle"
        manifest = {"version": 1, "root": str(self.root), "decision": {"goalId": "taskA"},
                    "targets": [{"path": "source.py", "preimageSha256": digest(b"old")}]}
        save(directory / "manifest.json", manifest)
        state = {"goalId": "taskA", "status": "verified", "verificationExitCode": 0,
                 "manifestSha256": digest((directory / "manifest.json").read_bytes()),
                 "postimages": {"source.py": digest(self.source.read_bytes())},
                 "verificationEvidenceMode": "caller-observed"}
        save(directory / "checkpoint.json", state)
        row = next(r for r in api.build(self.root, "taskA", dry_run=True)["verification"] if r["receipt"])
        self.assertEqual(row["receipt"]["source"], "caller-observed")
        self.assertEqual(row["state"], "VERIFIED_PASS")
        state["goalId"] = "taskB"
        save(directory / "checkpoint.json", state)
        self.assertFalse(any(r["receipt"] for r in api.build(self.root, "taskA", dry_run=True)["verification"]))



    def journal_main(self):
        import contextlib
        import io
        import work_journal
        result = {"taskId": "taskA", "status": "in_progress"}
        out, err = io.StringIO(), io.StringIO()
        argv = ["work_journal.py", "open", "--root", str(self.root), "--task-id", "taskA",
                "--agent", "synthetic", "--purpose", "synthetic"]
        with mock.patch.object(work_journal, "open_journal", return_value=result):
            with mock.patch("sys.argv", argv), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = work_journal.main()
        self.assertEqual(code, 0)
        self.assertEqual(out.getvalue(), json.dumps(result, ensure_ascii=True) + "\n")
        return err.getvalue()

    def test_journal_begin_prints_pointer_only(self):
        self.api().build(self.root, "taskA")
        error = self.journal_main()
        self.assertEqual(error.count("\n"), 1)
        self.assertIn("[task-context]", error)
        self.assertIn("journals/taskA/context/current.json", error)
        self.assertNotIn(str(self.root), error)

    def test_journal_unchanged_when_context_missing(self):
        self.assertEqual(self.journal_main(), "")
        with mock.patch.object(self.api(), "locations", side_effect=OSError("synthetic unreadable")):
            self.assertEqual(self.journal_main(), "")



    def test_receipt_cap_includes_checkpoints(self):
        api = self.api()
        self.receipt(ticket="ticketA")
        self.receipt(ticket="ticketB")
        directory = self.root / "journals/taskA/cycle"
        manifest = {"version": 1, "root": str(self.root), "decision": {"goalId": "taskA"},
                    "targets": [{"path": "source.py"}]}
        save(directory / "manifest.json", manifest)
        save(directory / "checkpoint.json", {"goalId": "taskA", "status": "verified",
            "verificationExitCode": 0, "manifestSha256": digest((directory / "manifest.json").read_bytes()),
            "postimages": {"source.py": digest(self.source.read_bytes())}})
        with mock.patch.object(api, "RECEIPT_LIMIT", 2):
            result = api.build(self.root, "taskA", dry_run=True)
        self.assertLessEqual(sum(bool(r["receipt"]) for r in result["verification"]), 2)
        self.assertTrue(result["truncated"])

    def test_busy_build_does_not_collect_evidence(self):
        api = self.api()
        self.current().parent.mkdir(parents=True)
        (self.current().parent / ".build.lock").write_text(json.dumps({"pid": os.getpid()}))
        with mock.patch.object(api, "collect", side_effect=AssertionError("must lock first")):
            with self.assertRaisesRegex(api.ContextError, "BUSY"):
                api.build(self.root, "taskA")

    def test_receipt_scope_mismatch_cannot_prove_pass(self):
        path = self.receipt().parent.parent / "state.json"
        state = json.loads(path.read_bytes())
        state["tickets"]["ticketA"]["scope"] = ["other.py"]
        save(path, state)
        result = self.api().build(self.root, "taskA", dry_run=True)
        row = next(r for r in result["verification"] if r["receipt"])
        self.assertFalse(row["receipt"]["valid"])


    def test_malformed_checkpoint_is_unknown(self):
        directory = self.root / "journals/taskA/cycle"
        save(directory / "manifest.json", {"version": 1, "root": str(self.root),
                                           "decision": None, "targets": []})
        save(directory / "checkpoint.json", {"goalId": "taskA", "manifestSha256":
                                             digest((directory / "manifest.json").read_bytes())})
        result = self.api().build(self.root, "taskA", dry_run=True)
        self.assertGreater(result["counts"]["unknown"], 0)
        self.assertFalse(any(r["receipt"] for r in result["verification"]))


if __name__ == "__main__":
    unittest.main()
