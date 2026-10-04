#!/usr/bin/env python3
"""Unit tests for scripts/chat_session_debug_export.py (no server required)."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timedelta, timezone
from unittest import mock

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

    def test_export_id_does_not_echo_query(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            query = "RUNTOKEN-canary-7f3a"
            write_record(root, "session", query)
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(0, cli.main(["--root", tmp, "export", query]))
            dest = root / out.getvalue().strip()
            text = dest.name + out.getvalue() + err.getvalue()
            for path in (dest / "manifest.json", dest.parent / "latest.json"):
                data = json.loads(path.read_text("utf-8"))
                self.assertNotIn("query", data)
                self.assertEqual(hash12(query), data["queryHash"])
                self.assertEqual("raw", data["queryForm"])
                self.assertTrue(data["schema"].endswith(".v2"))
                text += json.dumps(data)
            for i in range(len(query) - 7):
                self.assertNotIn(query[i:i + 8], text)
            for cmd in ("show", "export"):
                err = io.StringIO()
                with contextlib.redirect_stderr(err):
                    self.assertEqual(4, cli.main(["--root", tmp, cmd, query + "-missing"]))
                self.assertNotIn(query[:8], err.getvalue())
                self.assertIn("queryHash=", err.getvalue())

    def test_export_stays_within_output_root(self):
        for query in ("..", ".", "a/../..", r"C:\x", r"\\srv\s", "line\nbreak", "한글"):
            with self.subTest(query=query), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                source = write_record(root, query, "run")
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    code = cli.main(["--root", tmp, "export", query])
                self.assertEqual(0, code)
                dest = root / out.getvalue().strip()
                self.assertTrue(dest.resolve().is_relative_to(cli.export_root(root).resolve()))
                self.assertRegex(dest.name, r"^export-[0-9a-f]{16}$")
                self.assertEqual({source}, {p for p in root.rglob("*.json")
                                 if not p.is_relative_to(cli.export_root(root))})

    def test_reparse_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, outside = Path(tmp) / "root", Path(tmp) / "outside"
            outside.mkdir()
            write_record(root, "junction-canary", "run")
            name = cli.safe_export_name("junction-canary", [])
            link = cli.export_root(root) / name
            link.parent.mkdir(parents=True)
            if os.name == "nt":
                import _winapi
                _winapi.CreateJunction(str(outside), str(link))
            else:
                link.symlink_to(outside, target_is_directory=True)
            try:
                out, err = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                    code = cli.main(["--root", str(root), "export", "junction-canary"])
                self.assertEqual(3, code)
                self.assertEqual([], list(outside.iterdir()))
                self.assertNotIn("junction-canary", err.getvalue())
            finally:
                if os.name == "nt":
                    link.rmdir()
                else:
                    link.unlink()

    def test_export_forms_share_canonical_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = write_record(root, "session-canary", "run")
            names = []
            for form, query in (("raw", "session-canary"), ("hash", "hash:" + hash12("session-canary")),
                                ("bare-hash", hash12("session-canary")), ("stem", source.stem)):
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    self.assertEqual(0, cli.main(["--root", tmp, "export", query]))
                dest = root / out.getvalue().strip()
                names.append(dest.name)
                self.assertEqual(form, json.loads((dest / "manifest.json").read_text("utf-8"))["queryForm"])
            self.assertEqual(1, len(set(names)))

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
            # v2 contract: do not replicate raw lookup identifiers.
            self.assertEqual(hash12("9001"), latest["queryHash"])
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
            self.assertIsNone(payload["recordCount"])

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
            # v2 contract: identifiers only appear as canonical digests.
            self.assertIn(cli.safe_export_name("4242"), payload["exports"])
            self.assertEqual(hash12("4242"), payload["latest"]["queryHash"])

    def test_list_reports_recent_records(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Event-time filtering: use recent synthetic events, not today's mtime.
            now = datetime.now(timezone.utc).isoformat()
            write_record(root, "1", "r1", ts=now)
            write_record(root, "2", "r2", ts=now)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = cli.main(["--root", str(root), "list", "--since-hours", "48"])
            self.assertEqual(0, code)
            self.assertIn("2 session trace record(s)", out.getvalue())


class BoundedReaderTest(unittest.TestCase):

    def test_non_standard_json_values_are_rejected(self):
        bad_rows = [b'{"ts":"2026-10-04T00:00:00Z","v":' + value + b'}\n'
                    for value in (b'NaN', b'Infinity', b'-Infinity')]
        bad_rows += [b'{"ts":"2026-10-04T00:00:00Z","ts":"2026-10-04T00:00:01Z"}\n',
                     b'{"ts":"2026-10-04T00:00:00Z","nested":{"v":1,"v":2}}\n']
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_record(root, "s", "r")
            f.write_bytes(f.read_bytes() + b"".join(bad_rows))
            stats = {}
            self.assertEqual(1, len(list(cli.iter_records(root, None, stats=stats))))
            self.assertEqual(5, stats["non_standard_json"])
            self.assertEqual(0, stats["parse_error"])
            self.assertEqual(0, stats["partial_tail"])
            self.assertFalse(stats["complete"])

    def test_latest_pointer_uses_strict_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_record(root, "s", "r")
            with contextlib.redirect_stdout(io.StringIO()):
                cli.cmd_export(root, "s")
            p = cli.export_root(root) / "latest.json"
            original = p.read_bytes().rstrip()
            for extra in (b', "ignored":NaN}', b', "ignored":Infinity}',
                          b', "ignored":-Infinity}', b', "recordCount":1}'):
                with self.subTest(extra=extra):
                    p.write_bytes(original[:-1] + extra)
                    self.assertEqual({"error": "unreadable-latest-json"},
                                     cli.read_latest_pointer(root))

    def test_record_source_ref_is_stable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_record(root, "s", "r", surface="한글")
            raw = f.read_bytes().rstrip(b"\n") + b"\r\n"
            f.write_bytes(b"\n" + raw + raw)
            first = list(cli.iter_records(root, None))
            second = list(cli.iter_records(root, None))
            expected_sha = hashlib.sha256(raw).hexdigest()[:12]
            for rows in (first, second):
                self.assertEqual(["1", str(1 + len(raw))],
                                 [r["_offset"] for _, r in rows])
                self.assertEqual([expected_sha] * 2, [r["_sha12"] for _, r in rows])
                self.assertEqual([2, 3], [r["_line"] for _, r in rows])
                self.assertTrue(all(type(r["_offset"]) is str for _, r in rows))
            self.assertEqual([r for _, r in first], [r for _, r in second])
            with contextlib.redirect_stdout(io.StringIO()):
                cli.cmd_export(root, "s")
            exported = json.loads((cli.export_root(root) / cli.safe_export_name("s") /
                                   "records.json").read_text("utf-8"))
            self.assertEqual(["1", str(1 + len(raw))], [r["_offset"] for r in exported])

    def test_missing_trace_dir_is_not_zero_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(2, cli.cmd_status(Path(tmp)))
            status = json.loads(out.getvalue())
            self.assertIsNone(status["recordCount"])
            self.assertFalse(status["coverage"]["complete"])
            for key in ("files_scanned", "bytes_read", "rows_scanned", "rows_selected",
                        "parse_error", "partial_tail", "non_standard_json"):
                self.assertIsNone(status["coverage"][key], key)

    def test_non_trace_directory_entries_are_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_record(root, "s", "r")
            for i in range(20):
                (f.parent / ("ignored-%02d.txt" % i)).write_text("synthetic")
            stats = {}
            list(cli.iter_records(root, None, caps={"max_paths": 5}, stats=stats))
            self.assertEqual(5, stats["paths_visited"])
            self.assertTrue(stats["scan_limit_hit"])
            self.assertFalse(stats["complete"])

    def test_disappearing_trace_dir_preserves_observed_coverage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_record(root, "s", "r")
            size = f.stat().st_size
            stats = {}
            iterator = cli.iter_records(root, None, stats=stats)
            next(iterator)
            with mock.patch.object(Path, "is_dir", return_value=False):
                self.assertEqual([], list(iterator))
            self.assertEqual(1, stats["rows_scanned"])
            self.assertEqual(size, stats["bytes_read"])
            self.assertEqual({f.relative_to(root).as_posix(): str(size)}, stats["source_cutoffs"])
            self.assertFalse(stats["complete"])

    def test_source_cutoff_is_exposed_and_fixed_before_append(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_record(root, "s", "r")
            raw = f.read_bytes()
            stats = {}
            iterator = cli.iter_records(root, None, stats=stats)
            next(iterator)
            with f.open("ab") as fh:
                fh.write(raw)
            self.assertEqual([], list(iterator))
            self.assertEqual({f.relative_to(root).as_posix(): str(len(raw))},
                             stats["source_cutoffs"])
            self.assertFalse(stats["complete"])

    def test_handle_mutation_is_incomplete_even_if_path_signature_restored(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_record(root, "s", "r")
            before = f.stat()
            after = SimpleNamespace(**{k: getattr(before, k) for k in
                                    ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")})
            after.st_mtime_ns += 1
            stats = {}
            with mock.patch.object(cli.os, "fstat", side_effect=[before, after]):
                self.assertEqual(1, len(list(cli.iter_records(root, None, stats=stats))))
            self.assertEqual(1, stats["changed_files"])
            self.assertFalse(stats["complete"])

    def test_valid_final_json_without_newline_is_not_corruption(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_record(root, "s", "r")
            f.write_bytes(f.read_bytes().rstrip(b"\n"))
            stats = {}
            self.assertEqual(1, len(list(cli.iter_records(root, None, stats=stats))))
            self.assertEqual(0, stats["partial_tail"])
            self.assertTrue(stats["complete"])

    def test_latest_pointer_does_not_echo_unexpected_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_record(root, "s", "r")
            with contextlib.redirect_stdout(io.StringIO()):
                cli.cmd_export(root, "s")
            pointer = cli.export_root(root) / "latest.json"
            doc = json.loads(pointer.read_text("utf-8"))
            doc["query"] = "CANARY-UNEXPECTED-RAW"
            pointer.write_text(json.dumps(doc), encoding="utf-8")
            self.assertNotIn("CANARY", json.dumps(cli.read_latest_pointer(root)))
            self.assertNotIn("query", cli.read_latest_pointer(root))
            pointer.write_bytes(b"x" * 10000)
            self.assertEqual({"error": "unreadable-latest-json"}, cli.read_latest_pointer(root))

    def test_input_junction_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, other = Path(tmp) / "root", Path(tmp) / "other"
            source = write_record(other, "s", "r")
            link = cli.trace_dir(root) / "20261004"
            link.parent.mkdir(parents=True)
            if os.name == "nt":
                import _winapi
                _winapi.CreateJunction(str(source.parent), str(link))
            else:
                link.symlink_to(source.parent, target_is_directory=True)
            try:
                stats = {}
                self.assertEqual([], list(cli.iter_records(root, None, stats=stats)))
                self.assertEqual(1, stats["unreadable_files"])
                self.assertFalse(stats["complete"])
            finally:
                if os.name == "nt":
                    link.rmdir()
                else:
                    link.unlink()

    def test_since_hours_uses_event_time(self):
        now = datetime(2026, 10, 4, 12, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for sid, delta in (("old-mtime", -3599), ("old-event", -3601),
                               ("boundary", -3600), ("offset", -100)):
                ts = (now + timedelta(seconds=delta)).isoformat()
                if sid == "offset":
                    ts = (now + timedelta(seconds=delta)).astimezone(
                        timezone(timedelta(hours=9))).isoformat()
                f = write_record(root, sid, sid, ts=ts)
                os.utime(f, (0, 0) if sid == "old-mtime" else (now.timestamp(), now.timestamp()))
            stats = {}
            with mock.patch.object(cli, "datetime", wraps=datetime) as clock:
                clock.now.return_value = now
                rows = list(cli.iter_records(root, 1, stats=stats))
            self.assertEqual({hash12(s) for s in ("old-mtime", "boundary", "offset")},
                             {r["recordId"] for _, r in rows})
            self.assertEqual(4, stats["rows_scanned"])
            self.assertEqual(3, stats["rows_selected"])
            self.assertTrue(stats["complete"])

    def test_unknown_timestamp_is_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for i, ts in enumerate((None, "invalid", "2026-10-04T12:00:00")):
                write_record(root, str(i), str(i), ts=ts)
            stats = {}
            self.assertEqual([], list(cli.iter_records(root, None, stats=stats)))
            self.assertEqual(3, stats["unknown_timestamp"])
            self.assertFalse(stats["complete"])

    def test_utc_midnight_preserves_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            a = write_record(root, "same-session", "same-run", ts="2026-10-03T23:59:59Z")
            b = a.parent.parent / "20261004" / a.name
            b.parent.mkdir()
            b.write_bytes(a.read_bytes().replace(b"2026-10-03T23:59:59Z", b"2026-10-04T00:00:01Z"))
            old = a.parent.parent / "20261003"
            a.parent.rename(old)
            rows = cli.find_records(root, "same-run")
            self.assertEqual(2, len(rows))
            self.assertEqual({"20261003", "20261004"}, {f.parent.name for f, _ in rows})
            self.assertEqual({"hash:" + hash12("same-run")}, {r["runId"] for _, r in rows})

    def test_scan_caps_are_enforced(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for i in range(5):
                for _ in range(3):
                    write_record(root, str(i), str(i))
            for caps, field, cap in (({"max_files": 2}, "files_scanned", 2),
                                     ({"max_total_bytes": 99}, "bytes_read", 99),
                                     ({"max_rows": 2}, "rows_scanned", 2)):
                with self.subTest(caps=caps):
                    stats = {}
                    list(cli.iter_records(root, None, caps=caps, stats=stats))
                    self.assertLessEqual(stats[field], cap)
                    self.assertTrue(stats["scan_limit_hit"])
                    self.assertFalse(stats["complete"])

    def test_oversized_line_is_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_record(root, "s", "r")
            good = f.read_bytes()
            f.write_bytes(b"x" * 10000 + b"\n" + good)
            original_open = Path.open
            reads = []
            def opened(path, *args, **kwargs):
                fh = original_open(path, *args, **kwargs)
                if path == f and args and args[0] == "rb":
                    original_readline = fh.readline
                    def read(size=-1):
                        reads.append(size)
                        self.assertGreater(size, 0)
                        self.assertLessEqual(size, 1025)
                        return original_readline(size)
                    fh.readline = read
                return fh
            stats = {}
            with mock.patch.object(Path, "open", opened):
                rows = list(cli.iter_records(root, None, caps={"max_line_bytes": 1024}, stats=stats))
            self.assertEqual(1, len(rows))
            self.assertGreater(len(reads), 2)
            self.assertEqual(1, stats["oversized_line"])
            self.assertTrue(stats["scan_limit_hit"])
            self.assertFalse(stats["complete"])
            self.assertEqual(f.stat().st_size, stats["bytes_read"])

    def test_partial_tail_preserves_complete_rows(self):
        self.check_corruption(b'{"ts":', "partial_tail")

    def test_middle_parse_error_is_visible(self):
        self.check_corruption(b'broken\n', "parse_error", middle=True)

    def check_corruption(self, bad, field, middle=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_record(root, "s", "r")
            good = f.read_bytes()
            f.write_bytes(good + bad + (good if middle else b""))
            before = hashlib.sha256(f.read_bytes()).hexdigest()
            stats = {}
            rows = list(cli.iter_records(root, None, stats=stats))
            self.assertEqual(2 if middle else 1, len(rows))
            self.assertEqual(1, stats[field])
            self.assertEqual(0, stats["partial_tail" if middle else "parse_error"])
            self.assertFalse(stats["complete"])
            self.assertEqual(before, hashlib.sha256(f.read_bytes()).hexdigest())

    def test_changed_input_is_not_complete_snapshot(self):
        for mutation in ("append", "truncate", "replace"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                f = write_record(root, "s", "r")
                initial = f.read_bytes()
                stats = {}
                iterator = cli.iter_records(root, None, stats=stats)
                next(iterator)
                if mutation == "append":
                    with f.open("ab") as fh:
                        fh.write(initial)
                elif mutation == "truncate":
                    f.write_bytes(b"")
                else:
                    f.write_bytes(initial.replace(b"completed", b"cancelled"))
                self.assertEqual([], list(iterator))
                self.assertLessEqual(stats["bytes_read"], len(initial))
                self.assertFalse(stats["complete"])

    def test_strict_utf8_and_unreadable_are_visible(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = write_record(root, "s", "r")
            f.write_bytes(f.read_bytes() + b'{"ts":"2026-10-04T00:00:00Z","bad":"\xff"}\n')
            stats = {}
            self.assertEqual(1, len(list(cli.iter_records(root, None, stats=stats))))
            self.assertEqual(1, stats["parse_error"])
            self.assertFalse(stats["complete"])
            with mock.patch.object(Path, "open", side_effect=PermissionError("private-path")):
                self.assertEqual([], list(cli.iter_records(root, None, stats=stats)))
            self.assertEqual(1, stats["unreadable_files"])
            self.assertNotIn(tmp, json.dumps(stats))
            self.assertFalse(stats["complete"])

    def test_coverage_reaches_cli_and_export(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_record(root, "s", "r")
            for command in (["status"], ["list", "--json", "--since-hours", "100000"]):
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    self.assertEqual(0, cli.main(["--root", tmp, *command]))
                self.assertTrue(json.loads(out.getvalue())["coverage"]["complete"])
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(0, cli.cmd_export(root, "s"))
            manifest = json.loads((root / out.getvalue().strip() / "manifest.json").read_text("utf-8"))
            self.assertEqual(1, manifest["coverage"]["rows_selected"])
            self.assertNotIn("_line", (root / out.getvalue().strip() / "records.json").read_text("utf-8"))


if __name__ == "__main__":
    unittest.main()
