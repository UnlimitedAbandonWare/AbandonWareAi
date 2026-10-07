"""Offline contracts for the narrow browser10 setup/export adapter; no network."""
import copy
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import browser10_support_setup as support


def observations(*rows):
    return {"schemaVersion": support.OBS_SCHEMA, "synthetic": True, "events": list(rows)}


def event(turn=1, **extra):
    row = {"turnNo": turn, "attemptNo": 1, "runLabel": "synthetic-demo",
           "ownerRef": "test-owner", "sessionRef": "test-session",
           "assistantMessageId": str(100 + turn), "runId": "run-1",
           "requestId": "request-" + str(turn), "terminalStatus": "completed", "usefulAnswerVerdict": "PASS"}
    return dict(row, **extra)


class SupportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.out = "var/browser10-support-20261007/synthetic-demo"

    def put(self, data, name="input.json"):
        path = self.root / name
        path.write_text(json.dumps(data), encoding="utf-8")
        return path

    def collect(self, data, **options):
        return support.collect(data, run_label="synthetic-demo", session="test-session",
                               owner="test-owner", **options)

    def test_exact_manifest_and_no_old_truths(self):
        manifest = support.case_manifest()
        self.assertEqual([r["id"] for r in manifest["cases"]], [f"T{i:02}" for i in range(1, 11)])
        self.assertEqual(len(manifest["cases"]), 10)
        self.assertIn("거북선", manifest["cases"][0]["question"])
        self.assertNotIn("해솔-42", json.dumps(manifest, ensure_ascii=False))
        self.assertEqual(manifest["cases"][4]["searchNeed"], "FORBIDDEN")
        self.assertEqual(manifest["cases"][5]["searchNeed"], "REQUIRED")
        self.assertEqual(manifest["cases"][9]["searchNeed"], "FORBIDDEN")
        self.assertEqual([r["searchNeed"] for r in manifest["cases"]], ["REQUIRED", "CONDITIONAL", "CONDITIONAL", "CONDITIONAL", "FORBIDDEN", "REQUIRED", "NOT_NEEDED", "FORBIDDEN", "CONDITIONAL", "FORBIDDEN"])
        self.assertNotIn("PARTIAL", manifest["cases"][5]["passIf"])

    def test_setup_idempotent_preserves_checkpoint(self):
        support.setup(self.root, self.out)
        checkpoint = self.root / self.out / "checkpoint.json"
        checkpoint.write_text('{"progress":"owned"}', encoding="utf-8")
        support.setup(self.root, self.out)
        self.assertEqual(json.loads(checkpoint.read_text())["progress"], "owned")
        prompts = json.loads((self.root / self.out / "prompts.json").read_text(encoding="utf-8"))
        self.assertEqual(len(prompts), 10)
        self.assertTrue(all(r["attachment"] is False for r in prompts))

    def test_setup_conflict_changes_nothing(self):
        target = self.root / self.out
        target.mkdir(parents=True)
        (target / "prompts.json").write_text("foreign")
        before = list(target.iterdir())
        with self.assertRaisesRegex(support.SupportError, "output-conflict"):
            support.setup(self.root, self.out)
        self.assertEqual(list(target.iterdir()), before)

    def test_output_boundary_and_private_input(self):
        for path in ("var", "scripts/new", "var/browser10-support-20261007/../../other", "../outside"):
            with self.assertRaises(support.SupportError):
                support.output_path(self.root, path)
        private = self.root / ".secrets"
        private.mkdir()
        path = private / "input.json"
        path.write_text("{}")
        with self.assertRaisesRegex(support.SupportError, "private-input"):
            support.load_input(path)

    def test_missing_corrupt_duplicate_key_nan_and_oversize(self):
        with self.assertRaisesRegex(support.SupportError, "input-missing"):
            support.load_input(self.root / "missing.json")
        for content in ("{broken", '{"a":1,"a":2}', '{"a":NaN}'):
            path = self.root / "bad.json"
            path.write_text(content)
            with self.assertRaises(support.SupportError):
                support.load_input(path)
        path.write_bytes(b"x" * (support.MAX_INPUT + 1))
        with self.assertRaisesRegex(support.SupportError, "input-oversize"):
            support.load_input(path)

    def test_json_zip_equivalent(self):
        data = observations(event())
        raw = json.dumps(data).encode()
        archive = self.root / "input.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("context.json", raw)
            z.writestr("manifest.json", json.dumps({"files": {"context.json": {"sha256": support.sha(raw), "bytes": len(raw)}}}))
            z.writestr("README.txt", "synthetic")
        self.assertEqual(support.load_input(archive), data)
        self.assertEqual(support.load_input(self.put(data)), data)

    def test_zip_path_traversal_symlink_oversize_duplicate_checksum(self):
        for name in ("../context.json", "/context.json", "C:/context.json", "nested/context.json", "..\\context.json"):
            path = self.root / "bad.zip"
            with zipfile.ZipFile(path, "w") as z:
                z.writestr(name, "{}")
            with self.assertRaises(support.SupportError):
                support.load_input(path)
        path = self.root / "symlink.zip"
        with zipfile.ZipFile(path, "w") as z:
            info = zipfile.ZipInfo("context.json")
            info.external_attr = 0o120777 << 16
            z.writestr(info, "{}")
            z.writestr("manifest.json", "{}")
            z.writestr("README.txt", "synthetic")
        with self.assertRaisesRegex(support.SupportError, "zip-entry"):
            support.load_input(path)
        path = self.root / "large.zip"
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as z:
            z.writestr("context.json", b"x" * (support.MAX_INPUT + 1))
            z.writestr("manifest.json", "{}")
            z.writestr("README.txt", "synthetic")
        with self.assertRaisesRegex(support.SupportError, "zip-entry"):
            support.load_input(path)
        path = self.root / "checksum.zip"
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("context.json", "{}")
            z.writestr("manifest.json", json.dumps({"files": {"context.json": {"sha256": "0" * 64, "bytes": 2}}}))
            z.writestr("README.txt", "synthetic")
        with self.assertRaisesRegex(support.SupportError, "zip-integrity"):
            support.load_input(path)

    def test_normal_partial_never_runtime_pass(self):
        result = self.collect(observations(event()))
        self.assertFalse(result["productPass"])
        self.assertEqual(result["cases"][0]["runtimeVerdict"], "NOT_PROVEN")
        self.assertEqual(result["cases"][1]["runtimeVerdict"], "NOT_RUN")
        self.assertEqual(result["checkpoint"]["nextTurn"], "T02")
        self.assertEqual(result["cases"][0]["actualProviderDispatch"], "NOT_OBSERVED")

    def test_missing_ids_no_timestamp_join(self):
        result = self.collect(observations({"turnNo": 1, "attemptNo": 1, "runLabel": "synthetic-demo", "terminalStatus": "completed", "checkedAt": "2026-10-07T08:00:00Z"}))
        self.assertEqual(result["cases"][0]["binding"], "UNKNOWN")
        self.assertEqual(result["checkpoint"]["nextTurn"], "T01")

    def test_session_owner_mismatch(self):
        for field in ("sessionRef", "ownerRef"):
            with self.assertRaisesRegex(support.SupportError, "ids-mismatch"):
                self.collect(observations(event(**{field: "foreign"})))

    def test_duplicates_and_conflicting_turn_ids(self):
        result = self.collect(observations(event(), event()))
        self.assertEqual(result["duplicateEvents"], 1)
        with self.assertRaisesRegex(support.SupportError, "conflicting-turn"):
            self.collect(observations(event(), event(requestId="other")))

    def test_unknown_fields_and_sensitive_content_dropped(self):
        row = event()
        row.update({"answer": "PRIVATE-BODY", "cookie": "PRIVATE-COOKIE", "requestHeaders": {"Authorization": "PRIVATE-HEADER"}, "requestedModel": "PRIVATE-MODEL", "terminalStatus": "PRIVATE-STATUS"})
        row["sources"] = [{"sourceId": "source-private", "url": "https://host/?secret=PRIVATE-URL", "body": "PRIVATE-SOURCE"}]
        result = self.collect(observations(row))
        encoded = json.dumps(result)
        self.assertNotIn("PRIVATE", encoded)
        self.assertNotIn("source-private", encoded)
        self.assertGreater(result["droppedFields"], 0)

    def test_late_old_run_is_excluded(self):
        result = self.collect(observations(event(runLabel="old-run"), event()))
        self.assertEqual(result["lateOldRunEvents"], 1)
        self.assertEqual(result["checkpoint"]["nextTurn"], "T02")

    def test_cancel_disconnect_timeout_and_resume(self):
        for terminal in ("cancelled", "disconnected", "timeout", "partial"):
            result = self.collect(observations(event(terminalStatus=terminal)))
            self.assertTrue(result["checkpoint"]["requiresInspection"])
            self.assertEqual(result["checkpoint"]["nextTurn"], "T01")
            resumed = self.collect(observations(event(terminalStatus=terminal)), checkpoint=result["checkpoint"])
            self.assertEqual(resumed["checkpoint"]["sessionRef"], result["checkpoint"]["sessionRef"])
            self.assertFalse(resumed["checkpoint"]["automaticRetry"])

    def test_resume_rejects_wrong_run_session_owner(self):
        original = self.collect(observations(event()))["checkpoint"]
        for field in ("runLabel", "sessionRef", "ownerRef", "manifestSHA"):
            bad = copy.deepcopy(original)
            bad[field] = "foreign"
            with self.assertRaisesRegex(support.SupportError, "checkpoint-mismatch"):
                self.collect(observations(event()), checkpoint=bad)

    def test_native_export_does_not_invent_turn_or_run_binding(self):
        native = {"schemaVersion": support.EXPORT_SCHEMA, "snapshot": {"selectedSessionIds": ["test-session"], "exportStatus": "partial"}, "sessions": [{"sessionId": "test-session", "messages": [{"messageId": "101", "role": "assistant", "content": "PRIVATE-ANSWER"}], "turns": [{"assistantMessageId": "101", "traceSnapshotId": "snap-1", "run": {"status": "unavailable"}}]}]}
        result = self.collect(native)
        self.assertEqual(result["exportInventory"]["messageCount"], 1)
        self.assertTrue(all(r["runtimeVerdict"] == "NOT_RUN" for r in result["cases"]))
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertEqual(result["exportInventory"]["ownerBinding"], "UNKNOWN")

    def test_source_drift_recheck(self):
        path = self.root / "settings.gradle"
        path.write_text("one")
        pins = support.source_pins(self.root, ["settings.gradle"])
        path.write_text("two")
        result = support.check_pins(self.root, pins, expected_paths=["settings.gradle"])
        self.assertEqual(result["status"], "DRIFT")

    def test_empty_partial_duplicate_pins_rejected(self):
        pins = support.source_pins(self.root)
        for sources in ([], pins["sources"][:-1], [pins["sources"][0]] * len(pins["sources"])):
            with self.assertRaisesRegex(support.SupportError, "pin-set"):
                support.check_pins(self.root, dict(pins, sources=sources))

    def test_completed_unhelpful_or_unknown_stops_cursor(self):
        for verdict in ("FAIL", "UNKNOWN", None):
            result = self.collect(observations(event(usefulAnswerVerdict=verdict)))
            self.assertEqual(result["checkpoint"]["nextTurn"], "T01")
            self.assertTrue(result["checkpoint"]["requiresInspection"])

    def test_conflicting_same_id_metadata_rejected(self):
        row = event(sources=[{"sourceId": "s1", "requestId": "request-1", "sourceBody": "PROVEN"}])
        changed = copy.deepcopy(row)
        changed["sources"][0]["sourceBody"] = "FAIL"
        with self.assertRaisesRegex(support.SupportError, "conflicting-turn"):
            self.collect(observations(row, changed))

    def test_offline_final_receipt_resolution_needs_explicit_flag(self):
        first = self.collect(observations(event(terminalStatus="disconnected")))
        with self.assertRaisesRegex(support.SupportError, "terminal-resolution-required"):
            self.collect(observations(event()), checkpoint=first["checkpoint"])
        final = self.collect(observations(event()), checkpoint=first["checkpoint"], resolve_terminal=True)
        self.assertEqual(final["offlineTerminalResolutions"], 1)
        self.assertEqual(final["checkpoint"]["nextTurn"], "T02")
        self.assertFalse(final["productPass"])
        cancelled = self.collect(observations(event(terminalStatus="cancelled")))
        with self.assertRaisesRegex(support.SupportError, "conflicting-turn"):
            self.collect(observations(event()), checkpoint=cancelled["checkpoint"], resolve_terminal=True)

    def test_duplicate_zip_entry(self):
        import warnings
        path = self.root / "duplicate.zip"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("context.json", "{}")
                z.writestr("context.json", "{}")
                z.writestr("manifest.json", "{}")
                z.writestr("README.txt", "synthetic")
        with self.assertRaisesRegex(support.SupportError, "zip-members"):
            support.load_input(path)

    def test_native_zip_context_identity(self):
        native = {"schemaVersion": support.EXPORT_SCHEMA, "exportId": "synthetic-export", "exportedAt": "2026-10-07T08:00:00Z", "snapshot": {"selectedSessionIds": ["test-session"]}, "sessions": [{"sessionId": "test-session", "messages": [], "turns": []}]}
        raw = json.dumps(native).encode()
        metadata = {"schemaVersion": support.EXPORT_SCHEMA, "exportId": "synthetic-export", "context": {k: native[k] for k in ("schemaVersion", "exportId", "exportedAt", "snapshot")}, "files": {"context.json": {"sha256": support.sha(raw), "bytes": len(raw)}}}
        for bad in (None, "root", "nested"):
            manifest = copy.deepcopy(metadata)
            if bad == "root":
                manifest["exportId"] = "foreign"
            if bad == "nested":
                manifest["context"]["exportedAt"] = "foreign"
            path = self.root / "native.zip"
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("context.json", raw)
                z.writestr("manifest.json", json.dumps(manifest))
                z.writestr("README.txt", "synthetic")
            if bad:
                with self.assertRaisesRegex(support.SupportError, "zip-context-mismatch"):
                    support.load_input(path)
            else:
                self.assertEqual(support.load_input(path), native)

    def test_live_flag_rejected_and_preflight_no_probe_default(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            support.parser().parse_args(["setup", "--live"])
        result = support.preflight(self.root)
        self.assertEqual(result["health"]["status"], "NOT_RUN")
        self.assertFalse(result["networkAttempted"])

    def test_extreme_timestamp_and_unicode_blocked_without_echo(self):
        result = self.collect(observations(event(checkedAt="0001-01-01T00:00:00+01:00")))
        self.assertEqual(result["cases"][0]["checkedAt"], "NOT_OBSERVED")
        path = self.put(observations(event(requestId="\ud800")))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = support.main(["collect", "--input", str(path), "--root", str(self.root), "--out", self.out, "--run-label", "synthetic-demo", "--session-id", "test-session", "--owner-ref", "test-owner"])
        self.assertEqual(code, 2)
        self.assertEqual(json.loads(output.getvalue())["status"], "BLOCKED")
        self.assertNotIn("request", output.getvalue())

    def test_json_fixture_passes_unmodified_secret_scanner(self):
        import codex_work_checkpoint as checkpoint
        checkpoint.secret_free(Path(__file__).read_bytes(), "scripts/test_browser10_support_setup.py")

    def test_cookie_and_token_patterns_still_rejected(self):
        import codex_work_checkpoint as checkpoint
        for source in ("coo" + "kie: synthetic-session-value", "to" + "ken=synthetic-sensitive-value", "sk-" + "Z" * 24):
            with self.subTest(kind=source.split(":", 1)[0][:3]):
                with self.assertRaisesRegex(checkpoint.CheckpointError, "secret-pattern"):
                    checkpoint.secret_free(source.encode(), "scripts/other.py")


if __name__ == "__main__":
    unittest.main()
