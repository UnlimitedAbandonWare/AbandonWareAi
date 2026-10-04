#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""chat_sse_single_send 오프라인 fixture 테스트 (stdlib unittest, 네트워크 없음).

Fixture 3종: 정상 final / error 종료 / token만 오고 final 없음.
+ live-local-1의 "본문 0" 버그 재현: 구형 추출기(CONTENT_KEYS에 data 없음)는 0,
  신형은 >0 — RED→GREEN을 한 파일에서 입증한다.
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chat_sse_single_send as s

FIXTURE_OK = """\
event: status
data: {"type":"status","data":"admission ok"}

event: session
data: {"type":"session","data":"rt-abc","sessionId":228}

event: token
data: {"type":"token","data":"Hello"}

event: token
data: {"type":"token","data":" world"}

event: final
data: {"type":"final","data":"Hello world","modelUsed":"llmrouter.gemini-pro","observedProvider":"gemini","observedModel":"gemini-3.8-flash","fallbackCount":0,"routeId":"hash:abc"}

"""

FIXTURE_ERR = """\
event: status
data: {"type":"status","data":"admission ok"}

event: error
data: {"type":"error","data":"backend_unavailable"}

"""

FIXTURE_TOKENS_ONLY = """\
event: status
data: {"type":"status","data":"admission ok"}

event: token
data: {"type":"token","data":"Ans"}

event: token
data: {"type":"token","data":"wer"}

"""

# live-local-1 형태: 본문이 오직 ChatStreamEvent.data 필드에만 있다
FIXTURE_LIVE_LIKE = """\
event: token
data: {"type":"token","data":"Pi","modelUsed":null}

event: final
data: {"type":"final","data":"Ping ok","modelUsed":"llmrouter.gemini-pro","observedProvider":"gemini","observedModel":"gemini-3.8-flash","fallbackCount":0,"sessionId":228}

"""

# 구형 추출기의 CONTENT_KEYS (settings_defaults_sse_observe) — "data"가 없어 0을 반환
LEGACY_CONTENT_KEYS = ("delta", "text", "content", "answer", "body", "token",
                       "chunk", "message")


def legacy_body_chars(text: str) -> int:
    """구형 관측기 재현: payload의 CONTENT_KEYS만 본다 — data 필드는 빠짐."""
    import settings_defaults_sse_observe as obs
    total = 0
    for ev in obs.parse_sse(text):
        try:
            payload = json.loads(ev["data"])
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict):
            for k in LEGACY_CONTENT_KEYS:
                v = payload.get(k)
                if isinstance(v, str):
                    total += len(v)
    return total


class ExtractStreamTest(unittest.TestCase):
    def test_normal_final_body_and_terminal(self):
        out = s.extract_stream(FIXTURE_OK, sent_model="llmrouter.gemini-pro")
        self.assertEqual(out["observedModel"], "gemini-3.8-flash")
        self.assertEqual(out["observedProvider"], "gemini")
        self.assertEqual(out["fallbackCount"], 0)
        self.assertEqual(out["terminalCount"], 1)
        self.assertEqual(out["finalCount"], 1)
        self.assertEqual(out["errorCount"], 0)
        self.assertEqual(out["bodyChars"], len("Hello world"))
        self.assertEqual(out["bodySha12"], s.sha12("Hello world"))
        self.assertEqual(out["diagnostics"]["jsonParseFailed"], 0)

    def test_error_terminal(self):
        out = s.extract_stream(FIXTURE_ERR)
        self.assertEqual(out["errorCount"], 1)
        self.assertEqual(out["terminalCount"], 1)
        self.assertEqual(out["finalCount"], 0)
        self.assertEqual(out["bodyChars"], 0)
        self.assertEqual(out["terminal"]["event"], "error")
        # 코드형 에러 문자열은 코드로 남는다(분류용)
        flat = [v for e in out["sseErrors"] for v in e.values()]
        self.assertIn("backend_unavailable", flat)

    def test_tokens_only_no_final(self):
        out = s.extract_stream(FIXTURE_TOKENS_ONLY)
        self.assertEqual(out["finalCount"], 0)
        self.assertEqual(out["terminalCount"], 0)
        self.assertEqual(out["bodyChars"], len("Answer"))  # token concat fallback
        self.assertEqual(out["tokenBodyChars"], 6)
        self.assertEqual(out["finalBodyChars"], 0)

    def test_body0_bug_red_then_green(self):
        # RED: 구형 추출은 ChatStreamEvent.data를 빠뜨려 본문 0으로 오인
        self.assertEqual(legacy_body_chars(FIXTURE_LIVE_LIKE), 0)
        # GREEN: 신형은 data 필드를 읽어 본문을 잰다
        out = s.extract_stream(FIXTURE_LIVE_LIKE)
        self.assertGreater(out["bodyChars"], 0)
        self.assertEqual(out["bodySha12"], s.sha12("Ping ok"))
        self.assertEqual(out["finalCount"], 1)

    def test_free_text_error_is_hashed_not_raw(self):
        # 비코드형 에러 원문은 출력에 남기지 않고 sha12만 남긴다
        fixture = ('event: error\n'
                   'data: {"type":"error","data":"Connection refused upstream host z"}\n\n')
        out = s.extract_stream(fixture)
        dumped = json.dumps(out, ensure_ascii=False)
        self.assertNotIn("Connection refused upstream host z", dumped)
        self.assertEqual(out["errorDataChars"], len("Connection refused upstream host z"))

    def test_request_hash_is_12hex(self):
        self.assertRegex(s.sha12("devin-gemini-1"), r"^[0-9a-f]{12}$")


if __name__ == "__main__":
    unittest.main()
