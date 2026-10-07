import io
import json
import os
import unittest
from contextlib import redirect_stdout

from codex_ask_audit import (
    find_rollouts, main, redact, scan_free_text, scan_rollout, summarize)

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


class CodexAskFreeTextTest(unittest.TestCase):
    """자유 문장 질문 스캔 — 카드를 거치지 않는 어시스턴트 메시지의
    URL·계정 요구도 분류기에 태워 AUTO면 AVOIDABLE_ASK_FREE_TEXT로 센다.
    (2026-10-07 rollout-…18-27-17 사례 2 재현)"""

    CASE2_LIKE = ("진행 중입니다. 정상 관리자 로그인과 보호 URL 차단을 검증할 "
                  "기존 테스트 환경 URL이 있으면 알려주세요. 관리자 "
                  "비밀번호는 채팅에 보내지 말고 해당 브라우저에서 직접 "
                  "입력해 주세요.")

    def _write_rollout(self, payloads):
        import tempfile
        fd, path = tempfile.mkstemp(suffix=".jsonl")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            for p in payloads:
                f.write(json.dumps(
                    {"type": "response_item",
                     "timestamp": "2026-10-07T18:30:00Z",
                     "payload": p}, ensure_ascii=False) + "\n")
        return path

    def _assistant_msg(self, text):
        return {"type": "message", "role": "assistant",
                "content": [{"type": "output_text", "text": text}]}

    def test_free_text_admin_url_ask_flagged_avoidable(self):
        path = self._write_rollout([self._assistant_msg(self.CASE2_LIKE)])
        try:
            asks = scan_free_text(path)
        finally:
            os.unlink(path)
        auto = [a for a in asks if a["avoidable"]]
        self.assertTrue(auto, asks)
        self.assertEqual("FREE_TEXT", auto[0]["kind"])
        self.assertIn(auto[0]["verdict"], ("AUTO",))

    def test_user_message_not_scanned(self):
        path = self._write_rollout([
            {"type": "message", "role": "user",
             "content": [{"type": "input_text", "text": self.CASE2_LIKE}]}])
        try:
            self.assertEqual([], scan_free_text(path))
        finally:
            os.unlink(path)

    def test_non_ask_assistant_text_ignored(self):
        path = self._write_rollout([
            self._assistant_msg("수정을 적용하고 포커스 테스트를 돌렸습니다. "
                                "남은 항목을 계속 진행하겠습니다.")])
        try:
            self.assertEqual([], scan_free_text(path))
        finally:
            os.unlink(path)

    def test_main_json_has_free_text_fields(self):
        path = self._write_rollout([self._assistant_msg(self.CASE2_LIKE)])
        try:
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = main(["--rollout", path, "--json"])
        finally:
            os.unlink(path)
        self.assertEqual(0, code)
        out = json.loads(buf.getvalue())
        self.assertIn("avoidable_free_text", out["summary"])
        self.assertGreaterEqual(out["summary"]["avoidable_free_text"], 1)
        self.assertTrue(out["free_text_asks"])


if __name__ == "__main__":
    unittest.main()
