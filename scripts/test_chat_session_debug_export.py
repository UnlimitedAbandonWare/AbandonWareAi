#!/usr/bin/env python3
"""Unit tests for scripts/chat_session_debug_export.py (no server required)."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import chat_session_debug_export as cli  # noqa: E402


def hash12(value: str) -> str:
    return hashlib.sha256(value.strip().encode("utf-8")).hexdigest()[:12]


def write_record(root: Path, session_id: str, run_token: str, **overrides) -> Path:
    day = root / "var" / "debug" / "chat-session-traces" / "20260923"
    day.mkdir(parents=True, exist_ok=True)
    record = {
        "schema": "awx.chat-session-trace.v1",
        "ts": "2026-09-23T08:00:00Z",
        "sessionId": "hash:" + hash12(session_id),
        "runId": "hash:" + hash12(run_token),
        "recordId": hash12(run_token),
        "surface": "chat",
        "requestedModel": "gpt-5.6-luna",
        "effectiveModel": "openai/gpt-oss-120b",
        "baseUrlClass": "remote",
        "ragEnabled": True,
        "agentDbContextEnabled": False,
        "harmonyWarn": False,
        "cfvmQueued": True,
        "outcome": "completed",
        "errorClass": "none",
        "fallbackCount": 1,
        "traceKeys": ["chat.harmony.postprocess.decision", "llm.gateway.fallback.count"],
    }
    record.update(overrides)
    file = day / ("s-" + hash12(session_id) + ".json")
    with file.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record) + "\n")
    return file


class ChatSessionDebugExportTest(unittest.TestCase):

    def test_show_accepts_raw_session_id_and_hash_forms(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_record(root, "4242", "run-token-xyz")

            for query in ("4242", hash12("4242"), "hash:" + hash12("4242")):
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    code = cli.main(["--root", str(root), "show", query])
                self.assertEqual(0, code, f"show {query}")
                rec = json.loads(out.getvalue())
                self.assertEqual("hash:" + hash12("4242"), rec["sessionId"])

            # runId lookup by raw run token must also resolve via hashing.
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = cli.main(["--root", str(root), "show", "run-token-xyz"])
            self.assertEqual(0, code)
            self.assertEqual("hash:" + hash12("run-token-xyz"),
                             json.loads(out.getvalue())["runId"])

    def test_show_missing_id_returns_4(self):
        with tempfile.TemporaryDirectory() as tmp:
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                code = cli.main(["--root", str(tmp), "show", "nope"])
            self.assertEqual(4, code)
            self.assertIn("no session trace", err.getvalue())

    def test_export_writes_manifest_and_records_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_record(root, "9001", "tok-a")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = cli.main(["--root", str(root), "export", "9001"])
            self.assertEqual(0, code)
            export_dir = root / out.getvalue().strip()
            manifest = json.loads((export_dir / "manifest.json").read_text("utf-8"))
            records = json.loads((export_dir / "records.json").read_text("utf-8"))
            self.assertEqual(1, manifest["recordCount"])
            self.assertEqual(1, len(records))
            self.assertIn("metaDisplayDbExport", manifest["related"])
            # export bundle never contains prompt bodies or token values
            self.assertNotIn("tok-a", json.dumps(records))
            # latest.json pointer refreshed at export root
            latest = json.loads(
                (export_dir.parent / "latest.json").read_text("utf-8"))
            self.assertEqual("9001", latest["query"])
            self.assertEqual(str(export_dir.relative_to(root)), latest["exportDir"])
            self.assertEqual(1, latest["recordCount"])

    def test_status_reports_trace_dir_and_latest_pointer(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # missing trace dir -> exit 2, still emits JSON
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = cli.main(["--root", str(root), "status"])
            self.assertEqual(2, code)
            payload = json.loads(out.getvalue())
            self.assertFalse(payload["traceDirExists"])
            self.assertEqual(0, payload["recordCount"])

            write_record(root, "4242", "run-token-xyz")
            with contextlib.redirect_stdout(io.StringIO()):
                cli.main(["--root", str(root), "export", "4242"])
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = cli.main(["--root", str(root), "status"])
            self.assertEqual(0, code)
            payload = json.loads(out.getvalue())
            self.assertTrue(payload["traceDirExists"])
            self.assertEqual(1, payload["recordCount"])
            self.assertEqual(["20260923"], payload["days"])
            self.assertIn("4242", payload["exports"])
            self.assertEqual("4242", payload["latest"]["query"])

    def test_list_reports_recent_records(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_record(root, "1", "r1")
            write_record(root, "2", "r2")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = cli.main(["--root", str(root), "list", "--since-hours", "48"])
            self.assertEqual(0, code)
            self.assertIn("2 session trace record(s)", out.getvalue())


if __name__ == "__main__":
    unittest.main()
