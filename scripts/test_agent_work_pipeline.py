import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "agent_work_pipeline.py"

BRIEF = ("힌트가 과거 주제에 끌려가고 지금부터 새 맥락 버튼이 필요하며 "
         "Fold에서 다른 탭이면 수음이 끊깁니다. 소스 수정해줘")


def run(*args):
    proc = subprocess.run([sys.executable, "-B", str(SCRIPT), *args],
                          capture_output=True, text=True, timeout=60,
                          cwd=str(ROOT))
    out = proc.stdout.strip()
    return proc.returncode, json.loads(out) if out else {}


class AgentWorkPipelineTest(unittest.TestCase):
    def test_plan_multi_seam_brief(self):
        with tempfile.TemporaryDirectory() as td:
            brief = Path(td) / "brief.md"
            brief.write_text(BRIEF, encoding="utf-8")
            out_json = Path(td) / "plan.json"
            out_md = Path(td) / "plan.md"
            code, out = run("plan", "--brief-file", str(brief),
                            "--out", str(out_json), "--summary",
                            str(out_md))
            self.assertEqual(code, 0, out)
            self.assertIn("hint-input-context", out["matchedPlaybooks"])
            self.assertIn("fold-background-listen", out["matchedPlaybooks"])
            ids = [p["id"] for p in out["phases"]]
            self.assertEqual(ids[0], "preflight")
            self.assertGreater(out["totalBudgetTurns"], 0)
            self.assertTrue(all(2 <= p["budgetTurns"] <= 12
                                for p in out["phases"]))
            classes = {p["class"] for p in out["phases"]}
            self.assertIn("verify", classes)  # capture phases
            self.assertTrue(out_json.is_file())
            self.assertIn("총 예산", out_md.read_text(encoding="utf-8"))

    def test_plan_minimal_brief(self):
        code, out = run("plan", "--brief", "README typo only")
        self.assertEqual(code, 0)
        self.assertEqual([p["id"] for p in out["phases"]], ["preflight"])

    def test_update_marks_done(self):
        with tempfile.TemporaryDirectory() as td:
            brief = Path(td) / "b.md"
            brief.write_text(BRIEF, encoding="utf-8")
            plan_path = Path(td) / "p.json"
            run("plan", "--brief-file", str(brief), "--out", str(plan_path))
            code, out = run("update", "--plan", str(plan_path),
                            "--done", "preflight,capture-before")
            self.assertEqual(code, 0)
            self.assertNotIn("preflight", out["remaining"])
            self.assertFalse(out["complete"])
            self.assertLess(out["remainingBudgetTurns"], 999)
            # update is stateless: pass the cumulative done list
            all_ids = ["preflight", "capture-before"] + out["remaining"]
            code, out = run("update", "--plan", str(plan_path),
                            "--done", ",".join(all_ids))
            self.assertTrue(out["complete"])

    def test_brief_required(self):
        code, out = run("plan")
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
