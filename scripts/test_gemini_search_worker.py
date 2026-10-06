"""test_gemini_search_worker.py — mock HTTP만, 라이브 호출 0회.

Run: python -B -m unittest scripts.test_gemini_search_worker  (exit 0 = all pass)
또는: python -B scripts/test_gemini_search_worker.py
"""
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from scripts import gemini_search_worker as w  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
FAKE_KEY = "AIza" + "SyTESTKEYdeadbeef0000000000000000"  # 가짜 키 — 출력 금지 대상(형태 분할: 스캐너 오탐 아닌 키-모양 리터럴 차단 대응)


def _args(**kw):
    base = dict(text="질문?", domains="", depth="L1", max_chars=3000,
                brief_id="", brief_cap=10, model="gemini-9.9-flash",
                allow_preview=False, dry_run=False, timeout=10,
                card_file=None, state_dir=None)
    base.update(kw)
    return SimpleNamespace(**base)


def _grounded(text="공식 문서 근거 답변", finish="STOP", n=2, queries=None):
    chunks = [{"web": {"title": f"공식 문서 {i}",
                       "uri": f"https://ai.google.dev/doc{i}"}}
              for i in range(n)]
    return {"candidates": [{"finishReason": finish,
                            "content": {"parts": [{"text": text}]},
                            "groundingMetadata": {
                                "webSearchQueries": queries or ["q-one"],
                                "groundingChunks": chunks}}],
            "usageMetadata": {"promptTokenCount": 10,
                              "candidatesTokenCount": 20,
                              "totalTokenCount": 30},
            "responseId": "req-test"}


class CountingHttp:
    """_http_json 대체용: 호출 수·요청 URL·헤더를 기록한다."""

    def __init__(self, script):
        self.script = list(script)   # (code,payload) | Exception
        self.calls = []

    def __call__(self, method, url, key, body, timeout):
        self.calls.append({"method": method, "url": url, "key": key,
                           "body": body})
        item = self.script.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


class GeminiSearchWorkerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="gsw-test-"))
        self.state = self.tmp / "state"

    def _search(self, fake, args=None):
        args = args or _args()
        with mock.patch.object(w, "_http_json", fake), \
                mock.patch.object(sys, "stderr", io.StringIO()) as err:
            card, code = w.run_search(args, FAKE_KEY, self.state, ROOT)
        return card, code, err.getvalue()

    def test_01_grounded_ok(self):
        fake = CountingHttp([(200, _grounded())])
        card, code, _ = self._search(fake)
        self.assertEqual(code, 0)
        self.assertEqual(card["verdict"], "GROUNDED_OK")
        self.assertGreaterEqual(len(card["sources"]), 2)
        self.assertTrue(all(s["uri"].startswith("http") for s in card["sources"]))
        self.assertEqual(card["webSearchQueries"], ["q-one"])
        self.assertEqual(card["usage"]["totalTokenCount"], 30)
        self.assertEqual(card["requestId"], "req-test")

    def test_02_no_grounding(self):
        payload = _grounded()
        payload["candidates"][0].pop("groundingMetadata")
        card, _, _ = self._search(CountingHttp([(200, payload)]))
        self.assertEqual(card["verdict"], "NO_GROUNDING")
        self.assertNotIn(card["verdict"], w.SUCCESS_VERDICTS)

    def test_03_finish_truncated_not_success(self):
        card, _, _ = self._search(
            CountingHttp([(200, _grounded(finish="MAX_TOKENS"))]))
        self.assertEqual(card["verdict"], "FINISH_TRUNCATED")
        self.assertNotIn(card["verdict"], w.SUCCESS_VERDICTS)
        self.assertEqual(card["finishReason"], "MAX_TOKENS")

    def test_04_rate_limited_zero_retry(self):
        fake = CountingHttp([(429, {"error": {"message": "quota"}})])
        card, _, _ = self._search(fake)
        self.assertEqual(card["verdict"], "RATE_LIMITED")
        self.assertEqual(len(fake.calls), 1)

    def test_05_auth_fail_zero_retry(self):
        fake = CountingHttp([(401, {"error": {"message": "bad key"}})])
        card, _, _ = self._search(fake)
        self.assertEqual(card["verdict"], "AUTH_FAIL")
        self.assertEqual(len(fake.calls), 1)

    def test_06_key_never_emitted(self):
        # 네트워크 예외가 키 문자열을 품어도 카드·stderr 어디에도 키가 없어야 한다.
        fake = CountingHttp([Exception(f"boom {FAKE_KEY} key={FAKE_KEY}"),
                             Exception(f"boom {FAKE_KEY}")])
        card, _, err = self._search(fake)
        self.assertEqual(card["verdict"], "NET_FAIL")
        blob = json.dumps(card, ensure_ascii=False) + err
        self.assertNotIn(FAKE_KEY, blob)
        self.assertIn("<SECRET>", card.get("errorMessage", ""))
        # 요청 URL에도 키 파라미터가 없고 헤더로만 간다.
        for call in fake.calls:
            self.assertNotIn(FAKE_KEY, call["url"])
            self.assertNotIn("key=", call["url"])

    def test_07_card_within_max_chars(self):
        card, _, _ = self._search(
            CountingHttp([(200, _grounded(text="A" * 20000, n=8))]),
            args=_args(max_chars=3000))
        self.assertLessEqual(
            len(json.dumps(card, ensure_ascii=False, indent=2).encode("utf-8")),
            3000)
        self.assertTrue(card.get("truncated"))

    def test_08_cache_hit_zero_calls(self):
        fake = CountingHttp([(200, _grounded())])
        card1, _, _ = self._search(fake)
        self.assertEqual(card1["verdict"], "GROUNDED_OK")
        self.assertEqual(len(fake.calls), 1)
        fake2 = CountingHttp([(200, _grounded(text="다른 답"))])
        card2, code2, _ = self._search(fake2)
        self.assertEqual(code2, 0)
        self.assertTrue(card2.get("cacheHit"))
        self.assertEqual(card2.get("callsUsed"), 0)
        self.assertEqual(len(fake2.calls), 0)  # 캐시 적중 → 호출 0

    def test_09_quota_warn_nonblocking(self):
        now = w.utc_now()
        warn_state = self.tmp / "warn-state"
        usage = warn_state / f"usage-{now.strftime('%Y%m')}.jsonl"
        usage.parent.mkdir(parents=True)
        usage.write_text("\n".join(
            json.dumps({"briefId": "x"}) for _ in range(w.FREE_WARN_AT - 1)),
            encoding="utf-8")
        over_state = self.tmp / "over-state"
        usage2 = over_state / f"usage-{now.strftime('%Y%m')}.jsonl"
        usage2.parent.mkdir(parents=True)
        usage2.write_text("\n".join(
            json.dumps({"briefId": "x"})
            for _ in range(w.FREE_QUOTA_AT - 1)), encoding="utf-8")
        fake2 = CountingHttp([(200, _grounded())])
        card2, code2, _ = self._run_at(over_state, fake2)
        self.assertEqual(card2["verdict"], "GROUNDED_OK")
        self.assertEqual(card2["quotaWarn"], "OVER_FREE_QUOTA_ALLOWED")
        self.assertEqual(code2, 0)  # 경고이지 차단이 아니다
        card1, code1, _ = self._run_at(warn_state, CountingHttp([(200, _grounded())]))
        self.assertEqual(card1["verdict"], "GROUNDED_OK")
        self.assertEqual(card1["quotaWarn"], "WARN_NEAR_FREE_QUOTA")
        self.assertEqual(code1, 0)

    def _run_at(self, state_dir, fake):
        with mock.patch.object(w, "_http_json", fake), \
                mock.patch.object(sys, "stderr", io.StringIO()):
            card, code = w.run_search(_args(), FAKE_KEY, state_dir, ROOT)
        return card, code, ""

    def test_10_preview_not_default(self):
        models = [
            {"name": "models/gemini-3.8-flash",
             "supportedGenerationMethods": ["generateContent"]},
            {"name": "models/gemini-3.9-flash",
             "supportedGenerationMethods": ["generateContent"]},
            {"name": "models/gemini-4.0-flash-preview",
             "supportedGenerationMethods": ["generateContent"]},
            {"name": "models/gemini-2.0-pro",
             "supportedGenerationMethods": ["generateContent"]},
        ]
        self.assertEqual(w.pick_stable_flash(models), "gemini-3.9-flash")
        self.assertEqual(w.pick_stable_flash(models, allow_preview=True),
                         "gemini-4.0-flash-preview")
        # --model preview + --allow-preview 없음 → 차단.
        args = _args(model="gemini-4.0-flash-preview")
        fake = CountingHttp([])
        card, code, _ = self._search(fake, args)
        self.assertEqual(card["verdict"], "MODEL_PREVIEW_BLOCKED")
        self.assertEqual(code, 2)
        self.assertEqual(len(fake.calls), 0)

    def test_11_5xx_retries_once(self):
        fake = CountingHttp([(500, {"error": {"message": "boom"}}),
                             (200, _grounded())])
        card, code, _ = self._search(fake)
        self.assertEqual(card["verdict"], "GROUNDED_OK")
        self.assertEqual(len(fake.calls), 2)

    def test_12_brief_cap_blocks(self):
        usage = self.state / f"usage-{w.utc_now().strftime('%Y%m')}.jsonl"
        usage.parent.mkdir(parents=True)
        usage.write_text("\n".join(
            json.dumps({"briefId": "brief-1"})
            for _ in range(10)), encoding="utf-8")
        fake = CountingHttp([])
        card, code, _ = self._search(fake, _args(brief_id="brief-1"))
        self.assertEqual(card["verdict"], "BRIEF_CAP_EXCEEDED")
        self.assertEqual(code, 2)
        self.assertEqual(len(fake.calls), 0)

    def test_13_usage_ledger_records_call(self):
        fake = CountingHttp([(200, _grounded())])
        self._search(fake, _args(brief_id="b-9"))
        path = self.state / f"usage-{w.utc_now().strftime('%Y%m')}.jsonl"
        lines = [json.loads(l) for l in
                 path.read_text(encoding="utf-8").splitlines() if l.strip()]
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["briefId"], "b-9")
        self.assertEqual(lines[0]["verdict"], "GROUNDED_OK")
        self.assertLessEqual(len(lines[0]["q"]), 80)


if __name__ == "__main__":
    unittest.main()
