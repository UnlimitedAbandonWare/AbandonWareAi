#!/usr/bin/env python3
"""test_chat_timeout_same_request.py — DV4 같은-요청 추출기 단위 테스트."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import importlib.util

SPEC = importlib.util.spec_from_file_location(
    "chat_timeout_same_request",
    str(Path(__file__).with_name("chat_timeout_same_request.py")),
)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)

NO = "not_observed"

CTX = "chat-128 0d4dbf72-b6f9-470c-bca2-9592d833142a"
SPRING_OK = "\n".join([
    f"2026-10-02T11:28:53.865+0900 INFO  [{CTX}] c.e.l.l.M.requestProof - [LLM_REQUEST_LIFECYCLE] requestHash=hash:req1 executionHash=hash:ex1 runHash=hash:run1 logicalCallOrdinal=1 attemptSequence=1 eventSequence=1 event=application_call_intent boundary=application observedAtEpochMs=1 afterCancel=false dropped=0 providerReceiptObserved=false",
    "2026-10-02T11:28:54.212+0900 INFO  [ ] c.e.l.l.M.requestProof - [LLM_REQUEST_LIFECYCLE] requestHash=hash:req1 executionHash=hash:ex1 runHash=hash:run1 eventSequence=2 event=http_client_started boundary=spring_client_request_commit observedAtEpochMs=2 afterCancel=false dropped=0 providerReceiptObserved=false",
    f"2026-10-02T11:29:05.918+0900 INFO  [{CTX}] c.e.l.l.M.requestProof - [LLM_REQUEST_LIFECYCLE] requestHash=hash:req1 executionHash=hash:ex1 runHash=hash:run1 eventSequence=3 event=http_client_failed boundary=spring_client_request_commit observedAtEpochMs=3 afterCancel=false dropped=0 providerReceiptObserved=false",
    f"2026-10-02T11:29:05.919+0900 INFO  [{CTX}] c.e.l.l.M.requestProof - [LLM_REQUEST_PROOF] requestHash=hash:req1 rowAccepted=true sequence=1 logicalCallOrdinal=1 attemptOrdinal=1 role=primary outcome=failed failureClass=timeout_soft terminalClass=error responseUtf8Bytes=0",
    f"2026-10-02T11:29:05.924+0900 ERROR [{CTX}] c.example.lms.api.ChatApiController - [AWX][chat] stream-failed type=ModelSelectionException error=errorHash=hash:e1 errorLength=60",
    f"2026-10-02T11:29:05.932+0900 INFO  [{CTX}] c.example.lms.api.ChatApiController - SSE stream detached by client (sessionHash=hash:sess1, resumePreserved=false)",
    "",
])

OLLAMA_ABORT = "\n".join([
    'time=2026-10-02T11:28:56.600+09:00 level=INFO source=llama_server.go:1046 msg="loading model via llama-server" model=E:\\models\\blobs\\sha256-dec52a44569a',
    'time=2026-10-02T11:29:06.150+09:00 level=WARN source=llama_server.go:1299 msg="client connection closed before llama-server finished loading, aborting"',
    'time=2026-10-02T11:29:06.154+09:00 level=INFO source=sched.go:641 msg="Load failed" model=E:\\models\\blobs\\sha256-dec52a44569a',
    '[GIN] 2026/10/02 - 11:29:06 | 499 |    12.132012s |       127.0.0.1 | POST     "/api/chat"',
    "",
])


def _write(tmp: Path, name: str, text: str) -> Path:
    p = tmp / name
    p.write_text(text, encoding="utf-8")
    return p


def _run(tmp: Path, spring_text=SPRING_OK, ollama_text=OLLAMA_ABORT,
         since=None, until=None, rid=None, trace=None):
    rid = MOD._norm_rid(rid)
    logs = []
    if spring_text is not None:
        logs.append(_write(tmp, "app.out.log", spring_text))
    if ollama_text is not None:
        logs.append(_write(tmp, "ollama.err.log", ollama_text))
    tdir = tmp / "traces"
    tdir.mkdir(exist_ok=True)
    if trace:
        _write(tdir, "s-x.json", json.dumps(trace))
    requests: dict = {}
    ollama: list = []
    for p in logs:
        if "ollama" in p.name:
            MOD.scan_ollama(p, since, until, ollama)
        else:
            MOD.scan_spring_log(p, since, until, requests, rid)
    MOD.scan_traces(tdir, since, until, requests, rid)
    doc = MOD.build_result(requests, ollama, since, until)
    return doc


class SpringScanTest(unittest.TestCase):
    def test_timeout_chain_populated(self):
        with tempfile.TemporaryDirectory() as td:
            doc = _run(Path(td))
            self.assertEqual(doc["requestCount"], 1)
            r = doc["requests"][0]
            self.assertEqual(r["requestId"], "hash:req1")
            self.assertEqual(r["runId"], "hash:run1")
            self.assertEqual(r["failureClass"], "timeout_soft")
            self.assertEqual(r["streamFailedType"], "ModelSelectionException")
            self.assertEqual(r["sseDetachedByClient"], "yes")
            self.assertEqual(r["sessionId"], "hash:sess1")
            self.assertEqual(r["timeoutAt"], "2026-10-02T11:29:05.918000+09:00")
            self.assertEqual(r["unconfirmedGuard"], "provider_receipt_not_observed")

    def test_missing_fields_stay_not_observed(self):
        with tempfile.TemporaryDirectory() as td:
            doc = _run(Path(td))
            r = doc["requests"][0]
            # 로그에 없는 필드는 not_observed 유지
            self.assertEqual(r["streamHttpStatus"], NO)
            self.assertEqual(r["reasonCode"], NO)
            self.assertEqual(r["providerModel"], NO)
            self.assertEqual(r["firstTokenAt"], NO)

    def test_other_request_not_mixed(self):
        other = "2026-10-02T11:28:50.000+0900 INFO  [chat-1 x] c.e.l.l.M.requestProof - [LLM_REQUEST_LIFECYCLE] requestHash=hash:OTHER runHash=hash:ro eventSequence=1 event=application_call_intent boundary=application observedAtEpochMs=1 afterCancel=false dropped=0 providerReceiptObserved=true"
        with tempfile.TemporaryDirectory() as td:
            doc = _run(Path(td), spring_text=SPRING_OK + other + "\n", rid="req1")
            self.assertEqual(doc["requestCount"], 1)
            self.assertEqual(doc["requests"][0]["requestId"], "hash:req1")
            self.assertEqual(doc["requests"][0]["runId"], "hash:run1")

    def test_trace_joins_by_run_hash(self):
        trace = {"schema": "awx.chat-session-trace.v1",
                 "ts": "2026-10-02T02:29:05.925Z",
                 "sessionId": "hash:sess1", "runId": "hash:run1",
                 "recordId": "hash:run1", "surface": "chat",
                 "requestedModel": "qwen3.5:9b", "effectiveModel": None,
                 "outcome": "error", "errorClass": "error", "fallbackCount": 0}
        with tempfile.TemporaryDirectory() as td:
            doc = _run(Path(td), trace=trace)
            r = doc["requests"][0]
            self.assertEqual(r["requestId"], "hash:req1")
            self.assertEqual(r["providerModel"], "requested:qwen3.5:9b")
            self.assertEqual(r["fallbackAttempted"], "no")

    def test_ollama_correlation_in_window(self):
        with tempfile.TemporaryDirectory() as td:
            doc = _run(Path(td))
            kinds = [o["kind"] for o in doc["requests"][0]["ollamaEvents"]]
            self.assertIn("gin_request", kinds)
            self.assertIn("ollama", kinds)
            gin = [o for o in doc["requests"][0]["ollamaEvents"] if o["kind"] == "gin_request"]
            self.assertIn("status=499", gin[0]["detail"])

    def test_window_excludes_far_events(self):
        far = 'time=2026-10-02T10:00:00.000+09:00 level=INFO source=x:1 msg="loading model via llama-server" model=E:\\m\\sha256-x\n'
        with tempfile.TemporaryDirectory() as td:
            doc = _run(Path(td), ollama_text=OLLAMA_ABORT + far)
            details = [o["detail"] for o in doc["requests"][0]["ollamaEvents"]]
            self.assertTrue(all("sha256-x" not in d for d in details))


class TsTest(unittest.TestCase):
    def test_app_ts_offset(self):
        ts = MOD.parse_ts("2026-10-02T11:28:53.865+0900")
        self.assertEqual(ts.utcoffset().total_seconds(), 32400)

    def test_broken_returns_none(self):
        self.assertIsNone(MOD.parse_ts("junk"))
        self.assertIsNone(MOD.parse_ts(None))


class NdjsonTest(unittest.TestCase):
    def test_ndjson_events_join(self):
        with tempfile.TemporaryDirectory() as td:
            nd = _write(Path(td), "d.ndjson", "\n".join([
                json.dumps({"ts": "2026-10-02T02:28:53.787Z", "probe": "PROMPT",
                            "where": "ChatWorkflow.promptBuild",
                            "requestId": "hash:req1", "sid": "hash:sess1",
                            "message": "PromptBuilder.build(ctx) executed"}),
                json.dumps({"ts": "2026-10-02T02:28:53.800Z", "probe": "OTHER",
                            "where": "x", "requestId": "hash:req2",
                            "message": "different request"}),
            ]))
            requests: dict = {}
            MOD.scan_ndjson(nd, None, None, requests, "hash:req1")
            self.assertEqual(list(requests.keys()), ["hash:req1"])
            self.assertEqual(requests["hash:req1"].session_id, "hash:sess1")
            self.assertTrue(any("promptBuild" in e["detail"] for e in requests["hash:req1"].events))


if __name__ == "__main__":
    unittest.main()
