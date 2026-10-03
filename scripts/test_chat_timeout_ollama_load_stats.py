#!/usr/bin/env python3
"""test_chat_timeout_ollama_load_stats.py — DV3 추출기 단위 테스트 (표준 unittest만)."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import importlib.util

SPEC = importlib.util.spec_from_file_location(
    "chat_timeout_ollama_load_stats",
    str(Path(__file__).with_name("chat_timeout_ollama_load_stats.py")),
)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


def _write(tmp: Path, name: str, text: str) -> Path:
    p = tmp / name
    p.write_text(text, encoding="utf-8")
    return p


class ParseTsTest(unittest.TestCase):
    def test_rfc3339_with_offset(self):
        ts = MOD.parse_ts("2026-10-02T11:28:56.600+09:00")
        self.assertEqual(ts.utcoffset().total_seconds(), 9 * 3600)

    def test_gin_naive_assumes_kst(self):
        ts = MOD.parse_ts("2026/10/02 - 11:29:06")
        self.assertEqual(ts.utcoffset().total_seconds(), 9 * 3600)

    def test_broken_ts_returns_none(self):
        self.assertIsNone(MOD.parse_ts("not-a-time"))
        self.assertIsNone(MOD.parse_ts(""))


class GinDurationTest(unittest.TestCase):
    def test_formats(self):
        self.assertEqual(MOD.parse_gin_duration_ms("1m9s"), 69000.0)
        self.assertEqual(MOD.parse_gin_duration_ms("12.43819s"), 12438.19)
        self.assertEqual(MOD.parse_gin_duration_ms("1.5641ms"), 1.5641)
        self.assertEqual(MOD.parse_gin_duration_ms("620µs"), 0.62)
        self.assertEqual(MOD.parse_gin_duration_ms("0s"), 0.0)

    def test_invalid(self):
        self.assertIsNone(MOD.parse_gin_duration_ms("soon"))
        self.assertIsNone(MOD.parse_gin_duration_ms(""))


class ExtractTest(unittest.TestCase):
    def test_load_pair_duration_and_name(self):
        with tempfile.TemporaryDirectory() as td:
            log = _write(Path(td), "ollama.err.log", "\n".join([
                'time=2026-10-02T11:28:56.600+09:00 level=INFO source=llama_server.go:1046 msg="loading model via llama-server" model=E:\\models\\blobs\\sha256-dec52a44569a2a25341c4e',
                'srv    load_model: loading model',
                'time=2026-10-02T11:29:03.000+09:00 level=INFO source=llama_server.go:1360 msg="llama-server started in 6.40 seconds"',
                'time=2026-10-02T11:29:03.010+09:00 level=INFO source=images.go:374 msg="template selection" model=registry.ollama.ai/library/qwen3.5:9b selected=renderer_parser',
                '',
            ]))
            out = MOD.extract(log, None, None)
            self.assertEqual(len(out["loads"]), 1)
            ev = out["loads"][0]
            self.assertEqual(ev["status"], "ok")
            self.assertAlmostEqual(ev["duration_s"], 6.40)
            self.assertEqual(ev["model_blob"], "sha256-dec52a44569a")
            self.assertEqual(ev["model"], "qwen3.5:9b")

    def test_client_abort_marks_aborted(self):
        with tempfile.TemporaryDirectory() as td:
            log = _write(Path(td), "ollama.err.log", "\n".join([
                'time=2026-10-02T11:28:56.600+09:00 level=INFO source=sched.go:1147 msg="disabling mmap for llama-server load by default" model=E:\\models\\blobs\\sha256-dec52a44569a2a25341c4e',
                'time=2026-10-02T11:29:06.150+09:00 level=WARN source=llama_server.go:1299 msg="client connection closed before llama-server finished loading, aborting" error="context deadline exceeded"',
                'time=2026-10-02T11:29:06.154+09:00 level=INFO source=sched.go:641 msg="Load failed" model=E:\\models\\blobs\\sha256-dec52a44569a2a25341c4e',
                '',
            ]))
            out = MOD.extract(log, None, None)
            self.assertEqual(len(out["loads"]), 1)
            self.assertEqual(out["loads"][0]["status"], "aborted_by_client")
            self.assertAlmostEqual(out["loads"][0]["duration_s"], 9.554, places=2)

    def test_gin_request_lines(self):
        with tempfile.TemporaryDirectory() as td:
            log = _write(Path(td), "ollama.out.log", "\n".join([
                '[GIN] 2026/10/02 - 11:29:06 | 499 |    12.132012s |       127.0.0.1 | POST     "/api/chat"',
                '[GIN] 2026/10/02 - 11:29:10 | 200 |      1.5641ms |       127.0.0.1 | GET      "/api/tags"',
                '',
            ]))
            out = MOD.extract(log, None, None)
            self.assertEqual(len(out["requests"]), 2)
            req = out["requests"][0]
            self.assertEqual(req["status"], 499)
            self.assertAlmostEqual(req["duration_s"], 12.132, places=3)
            self.assertEqual(req["path"], "/api/chat")

    def test_since_until_window(self):
        with tempfile.TemporaryDirectory() as td:
            log = _write(Path(td), "ollama.err.log", "\n".join([
                'time=2026-09-30T10:00:00.000+09:00 level=INFO source=llama_server.go:1046 msg="loading model via llama-server" model=E:\\models\\blobs\\sha256-aaaaaaaaaaaabbbb',
                'time=2026-09-30T10:00:07.000+09:00 level=INFO source=llama_server.go:1360 msg="llama-server started in 7.00 seconds"',
                'time=2026-10-02T11:00:00.000+09:00 level=INFO source=llama_server.go:1046 msg="loading model via llama-server" model=E:\\models\\blobs\\sha256-ccccccccccccdddd',
                'time=2026-10-02T11:00:08.000+09:00 level=INFO source=llama_server.go:1360 msg="llama-server started in 8.00 seconds"',
                '',
            ]))
            since = MOD.parse_ts("2026-10-01T00:00:00+09:00")
            out = MOD.extract(log, since, None)
            self.assertEqual(len(out["loads"]), 1)
            self.assertAlmostEqual(out["loads"][0]["duration_s"], 8.0)

    def test_no_match_and_broken_lines(self):
        with tempfile.TemporaryDirectory() as td:
            log = _write(Path(td), "ollama.err.log", "\n".join([
                "garbage line without structure",
                'time=BROKEN level=INFO source=x:1 msg="loading model via llama-server" model=foo',
                'level=INFO msg="missing time prefix entirely"',
                "",
            ]))
            out = MOD.extract(log, None, None)
            self.assertEqual(out["loads"], [])
            self.assertEqual(out["requests"], [])

    def test_manifest_blob_mapping(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "manifests" / "qwen3.5"
            root.mkdir(parents=True)
            (root / "9b").write_text(json.dumps({
                "layers": [{"mediaType": "application/vnd.ollama.image.model",
                            "digest": "sha256:dec52a44569a2a25341c4e4d3fee25846eed4f6f"}]
            }), encoding="utf-8")
            mapping = MOD.load_manifest_map(root.parent.parent)
            self.assertEqual(mapping.get("sha256-dec52a44569a"), "qwen3.5:9b")

    def test_legacy_runner_started_marker(self):
        with tempfile.TemporaryDirectory() as td:
            log = _write(Path(td), "server.log", "\n".join([
                'time=2026-09-27T10:00:00.000+09:00 level=INFO source=llama_server.go:1046 msg="loading model via llama-server" model=E:\\models\\blobs\\sha256-eeeeeeeeeeeeffff',
                'time=2026-09-27T10:00:30.000+09:00 level=INFO source=x:1 msg="llama runner started in 30.10 seconds"',
                '',
            ]))
            out = MOD.extract(log, None, None)
            self.assertEqual(len(out["loads"]), 1)
            self.assertAlmostEqual(out["loads"][0]["duration_s"], 30.10)


class SummarizeTest(unittest.TestCase):
    def test_per_model_stats(self):
        loads = [
            {"model": "a", "status": "ok", "duration_s": 5.0},
            {"model": "a", "status": "ok", "duration_s": 7.0},
            {"model": "a", "status": "ok", "duration_s": 9.0},
            {"model": "b", "status": "failed", "duration_s": 3.0},
        ]
        out = MOD.summarize(loads, [])
        self.assertEqual(out["per_model"]["a"]["count"], 3)
        self.assertEqual(out["per_model"]["a"]["median_s"], 7.0)
        self.assertEqual(out["per_model"]["a"]["max_s"], 9.0)
        self.assertEqual(out["per_model"]["b"]["failed"], 1)


if __name__ == "__main__":
    unittest.main()
