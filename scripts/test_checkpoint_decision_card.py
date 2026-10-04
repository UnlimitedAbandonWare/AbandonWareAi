#!/usr/bin/env python3
"""checkpoint_decision_card 샌드박스 테스트:
python -B scripts/test_checkpoint_decision_card.py -v"""
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import checkpoint_decision_card as cdc  # noqa: E402


class Cap:
    def __enter__(self):
        self._old = sys.stdout
        self.buf = io.StringIO()
        sys.stdout = self.buf
        return self.buf

    def __exit__(self, *a):
        sys.stdout = self._old


BASE = ["--goal-id", "synthetic-test", "--reason-code", "minimal-verified-fix"]


class TestCard(unittest.TestCase):
    def test_c1_defaults_all_zero(self):
        with Cap() as buf:
            rc = cdc.main(BASE)
        self.assertEqual(rc, 0)
        pkt = json.loads(buf.getvalue())["packet"]
        self.assertEqual(set(pkt["risk"]), {"recovery", "blastRadius", "regression", "uncertainty", "cost"})
        self.assertTrue(all(v == 0 for v in pkt["risk"].values()))
        self.assertEqual(set(pkt["gates"]), {"bulkDelete", "unrecoverableOverwrite", "credentialChange",
                                             "externalRealData", "paidBulkCalls", "productionMutation",
                                             "permissionChange", "irreversibleLoss"})
        self.assertTrue(all(v is False for v in pkt["gates"].values()))

    def test_c2_gate_and_assess(self):
        with Cap() as buf:
            rc = cdc.main(BASE + ["--gate", "bulkDelete", "--recovery", "2", "--assess"])
        self.assertEqual(rc, 0)
        d = json.loads(buf.getvalue())
        self.assertTrue(d["packet"]["gates"]["bulkDelete"])
        self.assertFalse(d["packet"]["gates"]["externalRealData"])
        self.assertEqual(d["packet"]["risk"]["recovery"], 2)
        self.assertEqual(d["assessment"]["status"], "approval_required")
        self.assertIn("bulkDelete", d["assessment"]["approvalReasons"])

    def test_c3_bad_factor_exit2(self):
        with Cap():
            rc = cdc.main(BASE + ["--recovery", "9"])
        self.assertEqual(rc, 2)

    def test_c4_out_writes_utf8(self):
        d = tempfile.mkdtemp(prefix="dcard-")
        out = Path(d) / "decision.json"
        with Cap():
            rc = cdc.main(BASE + ["--out", str(out)])
        self.assertEqual(rc, 0)
        pkt = json.loads(out.read_text(encoding="utf-8"))["packet"]
        self.assertEqual(pkt["goalId"], "synthetic-test")
        # 덮어쓰기 거부
        with Cap():
            rc2 = cdc.main(BASE + ["--out", str(out)])
        self.assertEqual(rc2, 2)

    def test_c5_autonomous_when_no_gate(self):
        with Cap() as buf:
            rc = cdc.main(BASE + ["--assess"])
        self.assertEqual(rc, 0)
        self.assertEqual(json.loads(buf.getvalue())["assessment"]["status"], "autonomous")


if __name__ == "__main__":
    unittest.main()
