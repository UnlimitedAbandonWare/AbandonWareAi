#!/usr/bin/env python3
"""Synthetic tests for settings_defaults_sse_observe.py - fixed SSE
fixtures only; no browser, no server."""
from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "settings_defaults_sse_observe.py"
spec = importlib.util.spec_from_file_location("sd_sse_observe", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class SseObserveTest(unittest.TestCase):
    def test_multiline_data_json_reassembled(self):
        """A JSON payload split over several data: lines parses as one."""
        body = ("event: metadata\n"
                'data: {"observedModel":"gpt-x",\n'
                'data: "fallbackCount":2,"routeId":"r1"}\n\n')
        out = mod.observe_stream(body)
        self.assertEqual("gpt-x", out["observedModel"])
        self.assertEqual(2, out["fallbackCount"])
        self.assertEqual(1, out["diagnostics"]["jsonParseSucceeded"])

    def test_chunk_split_events(self):
        """Events split across feed() calls still parse once complete."""
        p = mod.SseParser()
        raw = 'event: final\ndata: {"observedModel":"m-1","answer":"ok"}\n\n'
        cut = len(raw) // 2
        evs = p.feed(raw[:cut].encode("utf-8"))
        self.assertEqual([], evs)
        evs += p.feed(raw[cut:].encode("utf-8"))
        evs += p.flush()
        self.assertEqual(1, len(evs))
        self.assertEqual("final", evs[0]["event"])

    def test_utf8_multibyte_split_across_chunks(self):
        """A multi-byte char split mid-sequence decodes correctly."""
        text = 'event: final\ndata: {"observedModel":"모델-7","answer":"응답"}\n\n'
        data = text.encode("utf-8")
        p = mod.SseParser()
        evs = []
        for i in range(0, len(data), 7):  # tiny chunks split any sequence
            evs += p.feed(data[i:i + 7])
        evs += p.flush()
        self.assertEqual(1, len(evs))
        payload = json.loads(evs[0]["data"])
        self.assertEqual("모델-7", payload["observedModel"])

    def test_trailing_metadata_event_wins_observed(self):
        """A final metadata-only event still contributes observedModel."""
        body = ('event: delta\ndata: {"text":"hello"}\n\n'
                'event: final\ndata: {"answer":"hello"}\n\n'
                'event: metadata\ndata: {"observedModel":"last-model"}\n\n')
        out = mod.observe_stream(body)
        self.assertEqual("last-model", out["observedModel"])
        self.assertEqual("final", out["terminal"]["event"])
        self.assertEqual(0, out["firstToken"]["eventIndex"])

    def test_missing_observed_model(self):
        """requestedModel-like keys never fill observedModel."""
        body = ('event: metadata\ndata: {"requestedModel":"llmrouter.auto",'
                '"selectedModel":"m-x","resolvedModel":"m-y"}\n\n')
        out = mod.observe_stream(body, sent_model="llmrouter.auto")
        self.assertEqual("MISSING", out["observedModel"])
        self.assertEqual("m-x", out["declaredModels"]["selectedModel"])

    def test_first_token_skips_heartbeat_ack_metadata(self):
        body = ('event: heartbeat\ndata: {"t":1}\n\n'
                'event: ack\ndata: {"ok":true}\n\n'
                'event: metadata\ndata: {"sessionId":9}\n\n'
                'event: delta\ndata: {"text":"first","ts":42}\n\n')
        out = mod.observe_stream(body)
        self.assertEqual("delta", out["firstToken"]["event"])
        self.assertEqual(3, out["firstToken"]["eventIndex"])

    def test_sse_error_inside_stream(self):
        body = ('event: error\ndata: {"error":"provider_down"}\n\n')
        out = mod.observe_stream(body)
        self.assertTrue(out["sseError"])
        self.assertEqual("error", out["terminal"]["event"])

    def test_aux_judge_model_separated(self):
        body = ('event: metadata\ndata: {"observedModel":"final-m",'
                '"judgeModel":"judge-m","verifierModel":"v-m"}\n\n')
        out = mod.observe_stream(body)
        self.assertEqual("final-m", out["observedModel"])
        self.assertEqual("judge-m", out["auxModels"]["judgeModel"])
        self.assertNotEqual(out["observedModel"],
                            out["auxModels"]["judgeModel"])

    def test_result_json_thin_reader(self):
        doc = {"base": "http://x", "sends": 1,
               "results": [{"id": "C2", "round": 1, "verdict": "FAIL",
                            "requestedModel": "llmrouter.auto",
                            "firstBodyMs": 9967,
                            "metadata": {"observedModel": "gemini-3.8",
                                         "observedProvider": "gemini",
                                         "fallbackCount": 1,
                                         "verificationStatus": "fail_soft"},
                            "reasons": ["missing_keyword:x"]}]}
        out = mod.observe_result_json(doc)
        r = out["results"][0]
        self.assertEqual("llmrouter.auto", r["sentModel"])
        self.assertEqual("gemini-3.8", r["observedModel"])
        self.assertEqual(1, r["fallbackCount"])
        self.assertEqual(9967, r["firstToken"]["ms"])
        self.assertEqual("fail_soft", r["terminal"]["verificationStatus"])

    def test_result_json_missing_observed(self):
        doc = {"results": [{"id": "C7", "requestedModel": "llmrouter.auto",
                            "metadata": {"sessionId": 1}}]}
        out = mod.observe_result_json(doc)
        self.assertEqual("MISSING", out["results"][0]["observedModel"])


if __name__ == "__main__":
    unittest.main()
