import io
import json
import os
import unittest
from contextlib import redirect_stdout

from codex_ask_audit import (
    find_rollouts, main, redact, scan_rollout, summarize)

FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "fixtures", "codex_ask", "ask_cards_sample.jsonl")


class CodexAskAuditTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.cards = scan_rollout(FIXTURE)

    def test_card_count_from_fixture(self):
        # fixture has 7 function_call cards; event_msg duplicate must be ignored
        self.assertEqual(7, len(self.cards))

    def test_verdicts_and_rules_attached(self):
        by_call = {c["call_id"]: c for c in self.cards}
        self.assertEqual("AUTO", by_call["call_FIXRESTART1"]["verdict"])
        self.assertEqual("AUTO", by_call["call_FIXSCOPE02"]["verdict"])
        self.assertEqual("AUTO", by_call["call_FIXGUARD03"]["verdict"])
        self.assertEqual("AUTO", by_call["call_FIXXCHAT04"]["verdict"])
        self.assertEqual("AUTO", by_call["call_FIXSYNTH06"]["verdict"])
        self.assertEqual("ASK_ONCE", by_call["call_FIXAUTH005"]["verdict"])
        rules = {q["rule"] for q in
                 by_call["call_FIXXCHAT04"]["questions"]}
        self.assertIn("D21", rules)

    def test_wait_minutes_and_no_reply(self):
        by_call = {c["call_id"]: c for c in self.cards}
        self.assertAlmostEqual(42.0, by_call["call_FIXRESTART1"]["wait_min"])
        self.assertAlmostEqual(30.0, by_call["call_FIXSCOPE02"]["wait_min"])
        self.assertTrue(by_call["call_FIXRESTART1"]["replied"])
        for cid in ("call_FIXXCHAT04", "call_FIXAUTH005",
                    "call_FIXSYNTH06", "call_FIXSECRET7"):
            self.assertIsNone(by_call[cid]["wait_min"], cid)
            self.assertFalse(by_call[cid]["replied"], cid)

    def test_summary_counts(self):
        s = summarize(self.cards)
        self.assertEqual(7, s["cards"])
        self.assertEqual(6, s["avoidable"])
        self.assertEqual(1, s["ask_once"])
        self.assertEqual(4, s["no_reply"])
        self.assertEqual(3, s["replied"])
        self.assertAlmostEqual(27.3, s["avg_wait_min"], places=1)

    def test_redaction(self):
        self.assertEqual("[REDACTED]",
                         redact("api_key=ABCDEF1234567890"))
        self.assertNotIn("ABCDEF1234567890", redact("key sk-abcdef123456"))
        for c in self.cards:
            for q in c["questions"]:
                self.assertNotIn("ABCDEF1234567890", q["title"])
        secret_card = [c for c in self.cards
                       if c["call_id"] == "call_FIXSECRET7"][0]
        self.assertIn("[REDACTED]", secret_card["questions"][0]["title"])

    def test_main_rollout_json(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = main(["--rollout", FIXTURE, "--json"])
        self.assertEqual(0, code)
        out = json.loads(buf.getvalue())
        self.assertEqual(7, out["summary"]["cards"])
        self.assertEqual(6, out["summary"]["avoidable"])

    def test_find_rollouts_scoped_dir(self):
        # point --sessions-dir at a temp dir holding the fixture copy
        import shutil
        import tempfile
        with tempfile.TemporaryDirectory() as td:
            sub = os.path.join(td, "2026", "10", "02")
            os.makedirs(sub)
            shutil.copy(FIXTURE, os.path.join(sub, "rollout-fixture.jsonl"))
            found = find_rollouts(3, sessions_dir=td)
            self.assertEqual(1, len(found))


if __name__ == "__main__":
    unittest.main()
