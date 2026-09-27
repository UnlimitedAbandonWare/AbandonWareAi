"""Real local completion dispatch; fixtures do not touch project artifacts."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from test_codex_work_checkpoint import CP, decision


@unittest.skipUnless(os.name == "nt", "Windows deletion handle contract")
class CompletionCleanupHookTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.run = "data/agent-handoff/codex-autonomy/test-cycle"
        self.write("docs/final.md", b"before")
        CP.begin(self.root, self.run, ["docs/final.md"], decision())
        self.write("docs/final.md", b"verified final report")
        CP.seal(self.root, self.run)

    def write(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def pair(self, name):
        return {"path": name, "sha256": CP.digest((self.root / name).read_bytes())}

    def request(self):
        self.write(self.run + "/work/draft.tmp", b"draft")
        self.write(self.run + "/verify.log", b"report review passed")
        item = dict(self.pair(self.run + "/work/draft.tmp"), kind="intermediate", retainedPath="docs/final.md")
        report = dict(taskId="synthetic-recovery-test", taskKind="report", reportCompletion="verified",
                      allRequiredWorkComplete=True, completionFlag=True, postimages=[self.pair("docs/final.md")],
                      evidenceFiles=[self.pair(self.run + "/verify.log")], preserveFiles=[],
                      disposableArtifacts=[item], queueRoots=[self.run + "/work"],
                      acceptance=[dict(id="report-review", status="passed", exitCode=0,
                                       evidence=self.pair(self.run + "/verify.log"))])
        self.write(self.run + "/final.json", json.dumps(report).encode())
        request = {key: report[key] for key in ("taskId", "taskKind", "allRequiredWorkComplete", "completionFlag",
                   "postimages", "evidenceFiles", "preserveFiles", "disposableArtifacts", "queueRoots")}
        request.update(schemaVersion="awx.completed-task-cleanup.v1", deleteAuthorized=True,
                       completionReport=self.pair(self.run + "/final.json"))
        self.write(self.run + "/task-cleanup-request.json", json.dumps(request).encode())
        return request

    def test_verified_final_report_automatically_stops_and_cleans(self):
        self.request()
        state = CP.finish(self.root, self.run, 0, "report-review")
        self.assertEqual("verified", state["status"])
        self.assertEqual("completed", state.get("taskStatus"))
        self.assertEqual("none", state["nextAction"])
        self.assertFalse((self.root / self.run / "work/draft.tmp").exists())
        self.assertTrue((self.root / self.run / "before/0.bin").exists())
        self.assertEqual("verified final report", (self.root / "docs/final.md").read_text())

    def test_failed_verification_never_dispatches_cleanup(self):
        self.request()
        state = CP.finish(self.root, self.run, 1, "report-review")
        self.assertEqual("rolled_back", state["status"])
        self.assertNotEqual("completed", state.get("taskStatus"))
        self.assertTrue((self.root / self.run / "work/draft.tmp").exists())

    def test_foreign_task_request_cannot_close_checkpoint(self):
        request = self.request()
        request["taskId"] = "foreign-task"
        self.write(self.run + "/task-cleanup-request.json", json.dumps(request).encode())
        state = CP.finish(self.root, self.run, 0, "report-review")
        self.assertEqual("verified", state["status"])
        self.assertEqual("hold", state.get("completionCleanup", {}).get("status"))
        self.assertNotEqual("completed", state.get("taskStatus"))
        self.assertTrue((self.root / self.run / "work/draft.tmp").exists())

    def test_partial_checkpoint_without_request_keeps_next_goal_step(self):
        state = CP.finish(self.root, self.run, 0, "focused-only")
        self.assertEqual("continue-next-goal-step", state["nextAction"])
        self.assertNotEqual("completed", state.get("taskStatus"))

    def test_completed_report_with_no_surplus_still_stops(self):
        request = self.request()
        report_path = self.root / self.run / "final.json"
        report = json.loads(report_path.read_bytes())
        report["disposableArtifacts"] = request["disposableArtifacts"] = []
        report_path.write_text(json.dumps(report), encoding="utf-8")
        request["completionReport"] = self.pair(self.run + "/final.json")
        self.write(self.run + "/task-cleanup-request.json", json.dumps(request).encode())
        state = CP.finish(self.root, self.run, 0, "report-review")
        self.assertEqual("completed", state.get("taskStatus"))
        self.assertEqual("none", state["nextAction"])
        self.assertEqual(0, state["completionCleanup"]["deletedCount"])
        self.assertTrue((self.root / self.run / "work/draft.tmp").exists())

    def test_unicode_artifact_path_round_trips_through_native_hook(self):
        request = self.request()
        old_path = self.root / request["disposableArtifacts"][0]["path"]
        new_name = self.run + "/work/\uc784\uc2dc.tmp"
        old_path.rename(self.root / new_name)
        request["disposableArtifacts"][0]["path"] = new_name
        report_path = self.root / self.run / "final.json"
        report = json.loads(report_path.read_bytes())
        report["disposableArtifacts"] = request["disposableArtifacts"]
        report_path.write_text(json.dumps(report), encoding="utf-8")
        request["completionReport"] = self.pair(self.run + "/final.json")
        self.write(self.run + "/task-cleanup-request.json", json.dumps(request).encode())
        state = CP.finish(self.root, self.run, 0, "report-review")
        self.assertEqual("completed", state.get("taskStatus"))
        self.assertFalse((self.root / new_name).exists())

    def test_delete_lock_stops_task_but_retains_cleanup_work(self):
        self.request()
        target = self.root / self.run / "work/draft.tmp"
        with target.open("rb"):
            state = CP.finish(self.root, self.run, 0, "report-review")
        self.assertEqual("verified", state["status"])
        self.assertEqual("completed", state.get("taskStatus"))
        self.assertEqual("cleanup-only-after-condition-change", state["nextAction"])
        self.assertTrue(target.exists())
        self.assertEqual("hold", state["completionCleanup"]["status"])

    def test_timeout_keeps_verified_checkpoint_and_requires_receipt_reconciliation(self):
        self.request()
        with patch.object(CP.subprocess, "run", side_effect=CP.subprocess.TimeoutExpired("synthetic-child", 150)):
            state = CP.finish(self.root, self.run, 0, "report-review")
        self.assertEqual("verified", state["status"])
        self.assertEqual("reconcile-cleanup-receipt-and-required-proof", state["nextAction"])
        self.assertNotEqual("completed", state.get("taskStatus"))
        self.assertTrue((self.root / self.run / "work/draft.tmp").exists())


if __name__ == "__main__":
    unittest.main()
