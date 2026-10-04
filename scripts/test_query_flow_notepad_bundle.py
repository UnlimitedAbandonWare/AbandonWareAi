#!/usr/bin/env python3
"""Unit tests for scripts/query_flow_notepad_bundle.py (no server required)."""
from __future__ import annotations

import contextlib
import io
import json
import os
import re
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timezone
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import query_flow_notepad_bundle as cli  # noqa: E402


def write_trace(root: Path, ts: str, sid: str, outcome: str = "completed") -> Path:
    day = root / "var" / "debug" / "chat-session-traces" / "20260926"
    day.mkdir(parents=True, exist_ok=True)
    rec = {
        "schema": "awx.chat-session-trace.v1", "ts": ts,
        "sessionId": "hash:" + sid, "runId": "hash:" + sid + "r",
        "recordId": sid + "r", "surface": "chat",
        "requestedModel": "m-req", "effectiveModel": "m-eff",
        "baseUrlClass": "local", "ragEnabled": True,
        "agentDbContextEnabled": False, "harmonyWarn": False,
        "cfvmQueued": False, "outcome": outcome,
        "errorClass": "none", "fallbackCount": 0,
        "traceKeys": ["k1", "k2", "k3"],
    }
    f = day / ("s-" + sid + ".json")
    with f.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(rec) + "\n")
    return f


def write_db_export(root: Path) -> Path:
    dest = root / "var" / "meta-display-db" / "export" / "from-test"
    dest.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(dest / "export.sqlite"))
    con.execute("CREATE TABLE chat_message (id INTEGER, session_id TEXT, "
                "role TEXT, content TEXT, created_at TEXT)")
    con.execute("INSERT INTO chat_message VALUES (1, 's-1', 'user', "
                "'hello world this is a very long message body that keeps "
                "going and going and must be cut for the bundle', "
                "'2026-09-26 10:00:01')")
    con.execute("INSERT INTO chat_message VALUES (2, 's-1', 'assistant', '"
                + "answer with " + "api_" + "key"
                + "=SECRETKEY999999 embedded', '2026-09-26 10:00:02')")
    con.commit()
    con.close()
    (dest / "manifest.json").write_text(json.dumps({
        "ok": True, "runId": "from-test",
        "tables": [{"schema": "PUBLIC", "name": "chat_message",
                    "sqliteName": "chat_message", "rowCount": 2}],
    }), encoding="utf-8")
    (dest.parent / "latest.json").write_text(json.dumps({
        "runId": "from-test", "dir": str(dest), "mode": "from",
        "sqlite": str(dest / "export.sqlite"),
        "createdAt": "2026-09-26T10:00:00+0900",
    }), encoding="utf-8")
    return dest


def write_logs(root: Path):
    logs = root / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    (logs / "debug-events.ndjson").write_text(
        json.dumps({"ts": "2026-09-26T10:00:00Z", "probe": "WEB_SEARCH",
                    "level": "INFO", "message": "search summary",
                    "where": "X.y", "sid": "hash:aa",
                    "traceId": "hash:bb", "requestId": "hash:bb",
                    "data": {"stage": "stream"}, "error": None}) + "\n"
        + json.dumps({"ts": "2026-09-26T10:00:01Z", "probe": "LLM",
                      "level": "WARN",
                      "message": "fail " + "api_" + "key" + "=TOPSECRET7777",
                      "where": "X.z", "sid": "hash:aa",
                      "traceId": "hash:cc", "requestId": "hash:cc",
                      "data": {}, "error": "timeout"}) + "\n",
        encoding="utf-8")
    (logs / "trace.ndjson").write_text(
        json.dumps({"ts": 1790422088.5, "type": "orchestration_decision",
                    "stage": "route", "sid": "hash:aa",
                    "trace": "hash:bb", "requestId": "hash:bb",
                    "kv": {"latency_ms": 13.3}}) + "\n",
        encoding="utf-8")


def write_journal(root: Path, task_id: str = "t-abc"):
    d = root / "data" / "agent-handoff" / "codex-autonomy" / task_id
    d.mkdir(parents=True, exist_ok=True)
    (d / "journal.json").write_text(json.dumps({
        "taskId": task_id, "agent": "devin-x", "purpose": "fixture task",
        "status": "in_progress", "startedAtUtc": "2026-09-26T00:00:00Z",
        "updatedAtUtc": "2026-09-26T00:00:00Z", "eventCount": 0,
    }), encoding="utf-8")


def full_fixture(root: Path):
    now = datetime.now(timezone.utc).isoformat()
    write_trace(root, now, "aaaa")
    write_trace(root, now, "bbbb", outcome="failed")
    write_db_export(root)
    write_logs(root)
    write_journal(root)
    (root / "var" / "rag-launcher").mkdir(parents=True, exist_ok=True)
    (root / "var" / "rag-launcher" / "LATEST.json").write_text(json.dumps({
        "schemaVersion": "awx.rag_launcher_result.v1", "ok": True,
        "status": "ready", "stage": "READY", "runId": "r-1",
    }), encoding="utf-8")


class CollectorsTest(unittest.TestCase):

    def test_top_n_keeps_latest_with_bounded_storage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Latest events are deliberately at the end; equal timestamps use
            # path then physical line, not enumeration order or dict comparison.
            f = write_trace(root, "2026-10-04T00:00:00Z", "seed")
            records = []
            for i in range(1000):
                records.append({"ts": "2026-10-04T00:%02d:%02dZ" % (i // 60, i % 60),
                                "recordId": str(i)})
            records[-4]["ts"] = records[-1]["ts"]
            f.write_text("".join(json.dumps(r) + "\n" for r in records), encoding="utf-8")
            other = f.with_name("z-equal.json")
            other.write_text(json.dumps({"ts": records[-1]["ts"], "recordId": "tie"}) + "\n", encoding="utf-8")
            expected = [(r["ts"], f.relative_to(root).as_posix(), i + 1, r["recordId"])
                        for i, r in enumerate(records)]
            expected.append((records[-1]["ts"], other.relative_to(root).as_posix(), 1, "tie"))
            stats = {}
            rows = cli.collect_sessions(root, 100000, 5, stats=stats)
            self.assertEqual([r[3] for r in sorted(expected)[-5:]], [r["recordId"] for r in rows])
            self.assertLessEqual(stats["retained_peak"], 5)
            self.assertEqual(1001, stats["rows_scanned"])
            self.assertTrue(stats["complete"])
            self.assertNotIn("_line", rows[0])

    def test_sessions_sort_offsets_by_instant(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_trace(root, "2026-10-04T09:00:00+09:00", "aaaa")
            write_trace(root, "2026-10-04T00:01:00Z", "bbbb")
            rows = cli.collect_sessions(root, 100000, 1)
            self.assertEqual(["hash:bbbb"], [r["sessionId"] for r in rows])

    def test_sessions_coverage_propagates_partial(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_trace(root, datetime.now(timezone.utc).isoformat(), "aaaa")
            with f.open("ab") as fh:
                fh.write(b'{"ts":')
            bundle = cli.build_bundle(root, 24, use_subprocess=False)
            self.assertEqual(1, len(bundle["sessions"]))
            self.assertEqual(1, bundle["sessionsCoverage"]["partial_tail"])
            self.assertFalse(bundle["sessionsCoverage"]["complete"])

    def test_missing_trace_dir_is_not_zero_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            bundle = cli.build_bundle(Path(tmp), 24, use_subprocess=False)
            self.assertEqual([], bundle["sessions"])
            self.assertFalse(bundle["sessionsCoverage"]["complete"])
            for key in ("files_scanned", "bytes_read", "rows_scanned", "rows_selected",
                        "parse_error", "partial_tail", "non_standard_json", "retained_peak"):
                self.assertIsNone(bundle["sessionsCoverage"][key], key)

    def test_sessions_sorted_and_slim(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_trace(root, "2026-09-26T10:00:00Z", "aaaa")
            write_trace(root, "2026-09-26T09:00:00Z", "bbbb")
            # Freeze the event-time window; filesystem mtime is not event time.
            with mock.patch.object(cli.trace_reader, "datetime", wraps=datetime) as clock:
                clock.now.return_value = datetime(2026, 9, 26, 11, tzinfo=timezone.utc)
                rows = cli.collect_sessions(root, 24, 100)
            self.assertEqual(2, len(rows))
            self.assertEqual("hash:bbbb", rows[0]["sessionId"])
            self.assertEqual("hash:aaaa", rows[1]["sessionId"])
            self.assertEqual(3, rows[0]["traceKeyCount"])
            self.assertNotIn("traceKeys", rows[0])

    def test_db_tail_truncates_content(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_db_export(root)
            out = cli.collect_db_export(root, 10, 12)
            self.assertEqual("from-test", out["db"])
            tail = out["tails"]["chat_message"]
            self.assertEqual(2, tail["tailRowCount"])
            content_cell = tail["rows"][0][3]
            self.assertIsInstance(content_cell, dict)
            self.assertLessEqual(len(content_cell["preview"]), 80)
            self.assertEqual(12, len(content_cell["sha12"]))
            self.assertNotIn("going and going and must be cut",
                             json.dumps(out))
            self.assertEqual("s-1", tail["rows"][0][1])

    def test_events_join_keys_and_redaction(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_logs(root)
            rows = cli.collect_events(root, 200)
            self.assertEqual(3, len(rows))
            kinds = {r["kind"] for r in rows}
            self.assertEqual({"debug-event", "trace"}, kinds)
            trace_row = next(r for r in rows if r["kind"] == "trace")
            self.assertEqual("hash:bb", trace_row["requestId"])
            self.assertTrue(trace_row["ts"].startswith("2026-"))
            warn = next(r for r in rows if r["level"] == "WARN")
            self.assertNotIn("TOPSECRET7777", json.dumps(rows))
            self.assertIn("<redacted>", warn["message"])

    def test_journals_direct_scan(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_journal(root)
            rows = cli.collect_journals(root, use_subprocess=False)
            self.assertEqual(1, len(rows))
            self.assertEqual("t-abc", rows[0]["taskId"])
            self.assertEqual("devin-x", rows[0]["agent"])

    def test_rag_trail_direct_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "var" / "rag-launcher").mkdir(parents=True)
            (root / "var" / "rag-launcher" / "LATEST.json").write_text(
                json.dumps({"ok": True, "status": "ready"}), encoding="utf-8")
            out = cli.collect_rag_trail(root, use_subprocess=False)
            self.assertEqual("ready", out["verdict"])
            self.assertEqual("latest-pointer", out["source"])


class BundleWriteTest(unittest.TestCase):

    def test_bundle_end_to_end_no_secrets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            full_fixture(root)
            bundle = cli.build_bundle(root, 24, use_subprocess=False)
            result = cli.write_bundle(root, bundle)
            self.assertTrue(result["clean"], result.get("residue"))
            bundle_path = root / result["bundleJson"]
            self.assertTrue(bundle_path.is_file())
            self.assertTrue((bundle_path.parent / "NO_SECRETS").is_file())
            text = bundle_path.read_text(encoding="utf-8")
            for needle in ("TOPSECRET7777", "SECRETKEY999999"):
                self.assertNotIn(needle, text)
            for pattern in (r"(?i)api_key\s*=", r"(?i)token\s*=",
                            r"(?i)bearer\s+\S", r"(?i)password"):
                self.assertIsNone(re.search(pattern, text), pattern)
            latest = json.loads(
                (root / result["latest"]).read_text(encoding="utf-8"))
            self.assertEqual("awx.query-flow-latest.v1", latest["schema"])
            self.assertEqual(2, latest["counts"]["sessions"])
            self.assertEqual(3, latest["counts"]["events"])

    def test_secret_residue_blocks_marker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bundle = {"schema": cli.SCHEMA,
                      "generatedAtUtc": "2026-09-26T00:00:00+00:00",
                      "sessions": [], "events": [], "journals": [],
                      "ragTrail": {"note": "none"}, "dbExport": {},
                      "sources": {}, "gaps": [],
                      "forced": "impossible after redact loop"}
            # redact()가 모든 패턴을 잡으므로 residue가 남는지 자체가 계약 대상.
            residue = cli.scan_bundle_text(json.dumps(bundle))
            self.assertEqual([], residue)
            dirty = cli.scan_bundle_text(
                "x " + "api_" + "key" + "=abc123456789 y")
            self.assertTrue(dirty)

    def test_main_offline_writes_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            full_fixture(root)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = cli.main(["--root", str(root), "--offline",
                                 "--since-hours", "24"])
            self.assertEqual(0, code)
            payload = json.loads(out.getvalue())
            self.assertTrue(payload["clean"])
            self.assertTrue((root / payload["bundleJson"]).is_file())

    def test_build_bundle_missing_inputs_degrades(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bundle = cli.build_bundle(root, 24, use_subprocess=False)
            self.assertEqual([], bundle["sessions"])
            self.assertEqual([], bundle["events"])
            self.assertIn("no export bundle", bundle["dbExport"]["note"])
            self.assertFalse(bundle["sources"]["chatSessionTraces"])


def run_benchmark():
    """Opt-in, synthetic-only before/after measurement; no timing assertions."""
    import argparse
    import hashlib
    import importlib.util
    import statistics
    import time
    import tracemalloc
    from datetime import timedelta

    parser = argparse.ArgumentParser()
    parser.add_argument("--benchmark-modules", required=True)
    parser.add_argument("--benchmark-out", required=True)
    args = parser.parse_args()
    modules = Path(args.benchmark_modules).resolve()
    sys.modules.pop("chat_session_debug_export", None)
    spec = importlib.util.spec_from_file_location("bench_bundle", modules / "query_flow_notepad_bundle.py")
    target = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(target)
    result = {"schema": "awx.session-jsonl-benchmark.v1", "python": sys.version,
              "warmups": 3, "repeats": 30, "limit": 500,
              "code": {name: hashlib.sha256((modules / name).read_bytes()).hexdigest()
                       for name in ("chat_session_debug_export.py", "query_flow_notepad_bundle.py")},
              "caps": {key: getattr(target.trace_reader, key, None)
                       for key in ("MAX_FILES", "MAX_TOTAL_BYTES", "MAX_LINE_BYTES", "MAX_ROWS")},
              "samples": [], "summaries": []}
    for size in (2000, 20000):
        with tempfile.TemporaryDirectory(prefix="awx-jsonl-bench-") as tmp:
            root = Path(tmp)
            day = root / "var/debug/chat-session-traces/20261004"
            day.mkdir(parents=True)
            f = day / "s-synthetic.json"
            now = datetime.now(timezone.utc)
            with f.open("w", encoding="utf-8") as fh:
                for i in range(size):
                    fh.write(json.dumps({"ts": (now - timedelta(seconds=size-i)).isoformat(),
                                         "recordId": str(i), "sessionId": "hash:synthetic",
                                         "surface": "chat", "traceKeys": ["a", "b"]}) + "\n")
            for _ in range(3):
                target.collect_sessions(root, 24, 500)
            for iteration in range(30):
                tracemalloc.start()
                started = time.perf_counter()
                rows = target.collect_sessions(root, 24, 500)
                elapsed = (time.perf_counter() - started) * 1000
                _, peak = tracemalloc.get_traced_memory()
                tracemalloc.stop()
                if len(rows) != 500 or rows[-1]["recordId"] != str(size - 1):
                    raise AssertionError("benchmark-result-mismatch")
                result["samples"].append({"size": size, "iteration": iteration,
                                          "elapsed_ms": elapsed, "peak_bytes": peak,
                                          "files": 1, "bytes": f.stat().st_size,
                                          "rows": size, "returned": len(rows)})
            samples = [s for s in result["samples"] if s["size"] == size]
            times = sorted(s["elapsed_ms"] for s in samples)
            result["summaries"].append({"size": size, "p50_ms": statistics.median(times),
                                        "p95_ms": times[28],
                                        "peak_bytes": max(s["peak_bytes"] for s in samples)})
    Path(args.benchmark_out).write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result["summaries"]))


if __name__ == "__main__":
    if os.environ.get("AWX_BENCH") == "1":
        run_benchmark()
    else:
        unittest.main()
