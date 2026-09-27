"""Unit tests for scripts/devin_task_orchestrate.py using the skill playbook file."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load_mod():
    path = HERE / "devin_task_orchestrate.py"
    spec = importlib.util.spec_from_file_location("devin_task_orchestrate", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


SAMPLE = {
    "schemaVersion": "awx.rag_debug.v1",
    "role": "wear",
    "action": "status",
    "ok": True,
    "status": "ready",
    "runtimes": [{"processId": 1, "role": "wear", "verboseApplied": False,
                  "ownership": {"ok": True}}],
    "readiness": {"webReady": "True"},
    "freshness": {
        "sourcesNewer": "false",
        "newestSource": "main/java/com/example/lms/assist/ConversateApiCueService.java",
        "servedAsset": {"match": "match", "sourceHash": "a" * 64, "servedHash": "a" * 64},
    },
    "pipeline": [
        {"stage": "transcript-input", "count": 0, "lastAt": "", "extracted": {}, "counts": {}},
        {"stage": "cue-gate", "count": 10, "lastAt": "2026-09-20T01:00:00.000+0900",
         "extracted": {"cueDecision": "NO_CUE", "hintPath": "FAST"},
         "counts": {"noCue": 10, "cue": 0}},
        {"stage": "hint-generate", "count": 0, "lastAt": "", "extracted": {}, "counts": {}},
    ],
    "secretToken": "sk-leak",
}


class OrchestrateTests(unittest.TestCase):
    def setUp(self):
        self.mod = load_mod()

    def test_self_test(self):
        result = self.mod.self_test()
        self.assertTrue(result["ok"], result)

    def test_combined_brief_orders_hint_before_listen(self):
        result = self.mod.plan(
            "지금부터 새 맥락과 과거 대화 참고 시간, 다른 탭 백그라운드 수음 끊김을 소스 수정해줘"
        )
        self.assertEqual(result["matchedPlaybooks"],
                         ["hint-input-context", "fold-background-listen"])
        ids = [p["id"] for p in result["phases"]]
        self.assertEqual(ids[0], "preflight")
        self.assertEqual(ids[1], "capture-before")
        self.assertEqual(ids[-1], "capture-after")
        self.assertLess(ids.index("select-hint-input"), ids.index("patch-listen"))

    def test_next_completes(self):
        planned = self.mod.plan("과거 대화 참고 범위를 조절하게 소스 수정")
        ids = [p["id"] for p in planned["phases"]]
        nxt = self.mod.next_phase(planned, ids)
        self.assertTrue(nxt["complete"])
        self.assertIsNone(nxt["nextPhase"])

    def test_plan_json_roundtrip(self):
        planned = self.mod.plan("늦은 응답이 되살아나지 않게 하고 주제 전환 시 과거 맥락을 줄여 주세요")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "plan.json"
            path.write_text(json.dumps(planned), encoding="utf-8")
            loaded = json.loads(path.read_text(encoding="utf-8"))
            nxt = self.mod.next_phase(loaded, ["preflight"])
            self.assertEqual(nxt["nextPhase"]["id"], "capture-before")

    def test_capture_redacts_and_hints(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            debug = root / "var" / "debug"
            debug.mkdir(parents=True)
            src = debug / "wear-20260920-010000-status.json"
            src.write_text(json.dumps(SAMPLE), encoding="utf-8")
            result = self.mod.capture(root, role="wear", task="t1", invoke=False, source=src)
            self.assertTrue(result["ok"])
            latest = json.loads((root / "data/agent-handoff/display-debug/latest.json").read_text(encoding="utf-8"))
            self.assertNotIn("secretToken", latest)
            seams = {row["seam"] for row in latest["patchHints"]}
            self.assertIn("fold-background-listen", seams)
            self.assertIn("cue-gate-or-hold", seams)
            task_latest = root / "data/agent-handoff/codex-autonomy/t1/debug-snapshots/latest.json"
            self.assertTrue(task_latest.is_file())

    def test_compare_count_delta(self):
        before = self.mod.redact_status(SAMPLE)
        after_raw = json.loads(json.dumps(SAMPLE))
        after_raw["pipeline"][0]["count"] = 3
        after = self.mod.redact_status(after_raw)
        diff = self.mod.compare(before, after)
        stages = [row["stage"] for row in diff["changedStages"]]
        self.assertIn("transcript-input", stages)


if __name__ == "__main__":
    unittest.main()
