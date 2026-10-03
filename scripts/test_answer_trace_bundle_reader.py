"""Synthetic, offline checks for the existing answer trace ZIP contract."""

import hashlib
import json
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts import awx_mcp_toolbox as toolbox


class AnswerTraceBundleReaderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.archive = Path(self.temp.name) / "answer-trace-bundle.zip"

    def write_bundle(self, *, corrupt=False, extra=None, summary=None, event_hash="hash:123456789abc",
                     trace_hash=None, event_trace_hash=None):
        event = {"id": "event-1", "requestIdHash": event_hash}
        if event_trace_hash is not None:
            event["traceIdHash"] = event_trace_hash
        files = {
            "summary.json": summary if summary is not None else b'{"reason":"answered"}',
            "trace.json": b'{"method":"FAST"}',
            "events.ndjson": json.dumps(event).encode() + b"\n",
            "README.txt": b"Synthetic fixture.\n",
        }
        manifest = {
            "schema": "awx.answer-trace-bundle.v1",
            "snapshotId": "snapshot-1",
            "capturedAt": "2026-09-27T10:00:00",
            "ringScope": "current_process_only",
            "durableScope": "existing_chat_store",
            "requestIdHash": "hash:123456789abc",
            "sources": {"summary": {"status": "available"},
                        "trace": {"status": "available"},
                        "events": {"status": "available", "count": 1,
                                   "truncated": False, "historyComplete": False},
                        "logs": {"status": "unavailable"}},
            "checksumsSha256": {name: hashlib.sha256(data).hexdigest()
                                for name, data in files.items()},
        }
        if corrupt:
            manifest["checksumsSha256"]["summary.json"] = "0" * 64
        if trace_hash is not None:
            manifest["traceIdHash"] = trace_hash
        files["manifest.json"] = json.dumps(manifest).encode()
        with zipfile.ZipFile(self.archive, "w") as archive:
            for name, data in files.items():
                archive.writestr(name, data)
            if extra is not None:
                archive.writestr(extra[0], extra[1])

    def test_verifies_correlated_bundle_without_releasing_contents(self):
        self.write_bundle()
        result = toolbox.answer_trace_bundle_read({"archive_path": str(self.archive)})
        self.assertTrue(result["ok"])
        self.assertEqual("answer_trace_bundle_verified", result["decision"])
        self.assertEqual(1, result["eventCount"])
        self.assertFalse(result["historyComplete"])
        self.assertEqual("current_process_only", result["ringScope"])
        self.assertEqual({"summary": "available", "trace": "available",
                          "events": "available", "logs": "unavailable"}, result["sourceStatus"])
        self.assertFalse(result["eventsTruncated"])
        self.assertEqual("archive_integrity_only", result["proofScope"])
        self.assertNotIn("FAST", json.dumps(result))
        self.assertNotIn(str(self.archive), json.dumps(result))

    def test_rejects_checksum_mismatch(self):
        self.write_bundle(corrupt=True)
        result = toolbox.answer_trace_bundle_read({"archive_path": str(self.archive)})
        self.assertFalse(result["ok"])
        self.assertEqual("checksum_mismatch", result["failReason"])

    def test_rejects_noncontract_member(self):
        self.write_bundle(extra=("../outside.txt", b"synthetic"))
        result = toolbox.answer_trace_bundle_read({"archive_path": str(self.archive)})
        self.assertFalse(result["ok"])
        self.assertEqual("invalid_member", result["failReason"])

    def test_rejects_duplicate_member(self):
        self.write_bundle(extra=("summary.json", b"duplicate"))
        result = toolbox.answer_trace_bundle_read({"archive_path": str(self.archive)})
        self.assertFalse(result["ok"])
        self.assertEqual("invalid_member", result["failReason"])

    def test_rejects_symlink_member(self):
        link = zipfile.ZipInfo("trace.json")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.write_bundle(extra=(link, b"target"))
        result = toolbox.answer_trace_bundle_read({"archive_path": str(self.archive)})
        self.assertFalse(result["ok"])
        self.assertEqual("invalid_member", result["failReason"])

    def test_rejects_excessive_uncompressed_size(self):
        self.write_bundle(summary=b"x" * 262_145)
        result = toolbox.answer_trace_bundle_read({"archive_path": str(self.archive)})
        self.assertFalse(result["ok"])
        self.assertEqual("oversize_bundle", result["failReason"])

    def test_rejects_event_from_another_request(self):
        self.write_bundle(event_hash="hash:000000000000")
        result = toolbox.answer_trace_bundle_read({"archive_path": str(self.archive)})
        self.assertFalse(result["ok"])
        self.assertEqual("invalid_correlation", result["failReason"])

    def test_rejects_event_from_another_trace(self):
        self.write_bundle(trace_hash="hash:aaaaaaaaaaaa", event_trace_hash="hash:bbbbbbbbbbbb")
        result = toolbox.answer_trace_bundle_read({"archive_path": str(self.archive)})
        self.assertFalse(result["ok"])
        self.assertEqual("invalid_correlation", result["failReason"])


if __name__ == "__main__":
    unittest.main()
