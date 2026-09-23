import contextlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "scripts" / "persist_build_error_patterns.py"
SPEC = importlib.util.spec_from_file_location("persist_build_error_patterns", SCRIPT_PATH)
persist_build_error_patterns = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(persist_build_error_patterns)


class PersistBuildErrorPatternsTest(unittest.TestCase):

    def test_scan_keeps_counts_but_not_raw_log_context_or_paths(self):
        marker = "SYNTHETIC_PRIVATE_MARKER"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            logs = root / "build-logs"
            logs.mkdir()
            (logs / "compile.log").write_text(
                f"{marker} cannot find symbol nearby-private-context\n",
                encoding="utf-8",
            )

            counts, examples, sources = persist_build_error_patterns.scan_logs(root)

        serialized = json.dumps({"examples": examples, "sources": sources})
        self.assertEqual(1, counts["java.cannot_find_symbol"])
        self.assertFalse(marker in serialized, "raw marker leaked into scan payload")
        self.assertRegex(sources[0], r"^source_sha256:[0-9a-f]{64}$")
        self.assertRegex(
            examples["java.cannot_find_symbol"][0],
            r"^source_sha256:[0-9a-f]{64}:matched$",
        )

    def test_analyzer_json_uses_structured_counts_without_rescanning_json_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            analysis = root / "analysis"
            analysis.mkdir()
            report = {
                "patterns": {
                    "gradle_build_failed": {
                        "count": 1,
                        "severity": 0.5,
                    }
                },
                "log_file": "FAILURE: Build failed with an exception.log",
            }
            (analysis / "build_error_report.json").write_text(
                json.dumps(report),
                encoding="utf-8",
            )

            counts, _, _ = persist_build_error_patterns.scan_logs(root)

        self.assertEqual(1, counts["gradle.build_failed"])

    def test_malformed_analyzer_json_falls_back_to_raw_pattern_scan(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            analysis = root / "analysis"
            analysis.mkdir()
            (analysis / "build_error_report.json").write_text(
                '{"patterns": {}, "message": "FAILURE: Build failed with an exception"',
                encoding="utf-8",
            )

            counts, _, _ = persist_build_error_patterns.scan_logs(root)

        self.assertEqual(1, counts["gradle.build_failed"])

    def test_invalid_structured_counts_fall_back_without_partial_commit(self):
        invalid_counts = (
            ("negative", -1),
            ("boolean", True),
            ("string", "1"),
            ("fractional", 1.5),
            ("non-finite", float("inf")),
        )
        raw_fallback_text = (
            "FAILURE: Build failed with an exception; "
            "FAILURE: Build failed with an exception"
        )
        for label, invalid_count in invalid_counts:
            with self.subTest(label=label):
                with tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    analysis = root / "analysis"
                    analysis.mkdir()
                    report = {
                        "patterns": {
                            "java_cannot_find_symbol": {"count": 7},
                            "gradle_build_failed": {"count": invalid_count},
                        },
                        "message": raw_fallback_text,
                    }
                    (analysis / "build_error_report.json").write_text(
                        json.dumps(report),
                        encoding="utf-8",
                    )

                    counts, _, _ = persist_build_error_patterns.scan_logs(root)

                self.assertEqual(0, counts["java.cannot_find_symbol"])
                self.assertEqual(2, counts["gradle.build_failed"])

    def test_non_schema_pattern_maps_keep_raw_fallback(self):
        pattern_maps = (
            ("empty", {}),
            ("unrelated-only", {"unknown_pattern": {"count": 9}}),
            ("known-non-record", {"gradle_build_failed": []}),
        )
        for label, patterns in pattern_maps:
            with self.subTest(label=label):
                with tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    analysis = root / "analysis"
                    analysis.mkdir()
                    report = {
                        "patterns": patterns,
                        "message": "FAILURE: Build failed with an exception",
                    }
                    (analysis / "build_error_report.json").write_text(
                        json.dumps(report),
                        encoding="utf-8",
                    )

                    counts, _, _ = persist_build_error_patterns.scan_logs(root)

                self.assertEqual(1, counts["gradle.build_failed"])

    def test_merge_replaces_legacy_raw_examples_and_sources_with_hashes(self):
        marker = "SYNTHETIC_PRIVATE_MARKER"
        existing = {
            "generated_at": marker,
            "unexpected_legacy_payload": marker,
            "sources_scanned": [f"PRIVATE_ROOT_SEGMENT/{marker}/compile.log"],
            "aggregated_counts": {
                "java.cannot_find_symbol": 2,
                marker: 1,
            },
            "examples": {
                "java.cannot_find_symbol": [
                    f"compile.log: ...cannot find symbol {marker}..."
                ],
                marker: [marker],
            },
        }
        zero_counts = {key: 0 for key in persist_build_error_patterns.LOG_PATTERNS}
        empty_examples = {key: [] for key in persist_build_error_patterns.LOG_PATTERNS}

        merged = persist_build_error_patterns.merge_db(
            existing,
            zero_counts,
            empty_examples,
            [],
        )

        serialized = json.dumps(merged)
        self.assertFalse(marker in serialized, "raw marker leaked into merged payload")
        self.assertFalse(
            "unexpected_legacy_payload" in merged,
            "unknown legacy field survived schema reconstruction",
        )
        self.assertRegex(
            merged["sources_scanned"][0],
            r"^source_sha256:[0-9a-f]{64}$",
        )
        self.assertRegex(
            merged["examples"]["java.cannot_find_symbol"][0],
            r"^legacy_example_sha256:[0-9a-f]{64}$",
        )

    def test_merge_rejects_invalid_stored_and_delta_count_types(self):
        invalid_counts = (
            ("negative", -1),
            ("boolean", True),
            ("string", "7"),
            ("fractional", 1.5),
            ("non-finite", float("inf")),
        )
        pattern = "java.cannot_find_symbol"
        empty_counts = {key: 0 for key in persist_build_error_patterns.LOG_PATTERNS}
        empty_examples = {key: [] for key in persist_build_error_patterns.LOG_PATTERNS}

        for layer in ("stored", "delta"):
            for label, invalid_count in invalid_counts:
                with self.subTest(layer=layer, label=label):
                    existing = {
                        "aggregated_counts": {pattern: invalid_count}
                        if layer == "stored"
                        else {},
                    }
                    delta_counts = dict(empty_counts)
                    if layer == "delta":
                        delta_counts[pattern] = invalid_count

                    merged = persist_build_error_patterns.merge_db(
                        existing,
                        delta_counts,
                        empty_examples,
                        [],
                    )

                    self.assertEqual(0, merged["aggregated_counts"][pattern])

    def test_merge_treats_non_mapping_delta_count_containers_as_empty(self):
        invalid_containers = (None, [], "7", 1.0, float("nan"))
        pattern = "java.cannot_find_symbol"
        empty_examples = {key: [] for key in persist_build_error_patterns.LOG_PATTERNS}

        for invalid_container in invalid_containers:
            with self.subTest(container_type=type(invalid_container).__name__):
                merged = persist_build_error_patterns.merge_db(
                    {},
                    invalid_container,
                    empty_examples,
                    [],
                )

                self.assertEqual(0, merged["aggregated_counts"][pattern])

    def test_distinct_ingest_roots_keep_distinct_redacted_source_ids(self):
        source_ids = []
        for _ in range(2):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                logs = root / "build-logs"
                logs.mkdir()
                (logs / "compile.log").write_text(
                    "cannot find symbol\n",
                    encoding="utf-8",
                )
                _, _, sources = persist_build_error_patterns.scan_logs(root)
                source_ids.append(sources[0])

        self.assertNotEqual(source_ids[0], source_ids[1])

    def test_main_emits_only_a_relative_database_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            previous_cwd = Path.cwd()
            stdout = io.StringIO()
            try:
                os.chdir(root)
                with mock.patch.object(sys, "argv", ["persist_build_error_patterns.py"]):
                    with contextlib.redirect_stdout(stdout):
                        persist_build_error_patterns.main()
            finally:
                os.chdir(previous_cwd)

            emitted = stdout.getvalue().strip()

        emitted_db = json.loads(emitted)["db"]
        self.assertFalse(Path(emitted_db).is_absolute(), "absolute database path emitted")
        self.assertTrue(
            emitted_db == ".build/error_patterns_db.json",
            "database path did not use the canonical relative form",
        )

    def test_main_does_not_scan_default_root_twice_when_ingest_aliases_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            logs = root / "build-logs"
            logs.mkdir()
            (logs / "compile.log").write_text("cannot find symbol\n", encoding="utf-8")
            previous_cwd = Path.cwd()
            stdout = io.StringIO()
            try:
                os.chdir(root)
                with mock.patch.object(
                    sys,
                    "argv",
                    ["persist_build_error_patterns.py", "--ingest", "."],
                ):
                    with contextlib.redirect_stdout(stdout):
                        persist_build_error_patterns.main()
            finally:
                os.chdir(previous_cwd)

            artifact = json.loads((root / "BUILD_ERROR_PATTERNS.json").read_text(encoding="utf-8"))

        self.assertEqual(1, artifact["aggregated_counts"]["java.cannot_find_symbol"])
        self.assertEqual(1, len(artifact["sources"]))

    def test_main_keeps_a_distinct_extra_project_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root = base / "default"
            extra = base / "extra"
            for scan_root in (root, extra):
                logs = scan_root / "build-logs"
                logs.mkdir(parents=True)
                (logs / "compile.log").write_text("cannot find symbol\n", encoding="utf-8")
            previous_cwd = Path.cwd()
            stdout = io.StringIO()
            try:
                os.chdir(root)
                with mock.patch.object(
                    sys,
                    "argv",
                    ["persist_build_error_patterns.py", "--ingest", str(extra)],
                ):
                    with contextlib.redirect_stdout(stdout):
                        persist_build_error_patterns.main()
            finally:
                os.chdir(previous_cwd)

            artifact = json.loads((root / "BUILD_ERROR_PATTERNS.json").read_text(encoding="utf-8"))

        self.assertEqual(2, artifact["aggregated_counts"]["java.cannot_find_symbol"])
        self.assertEqual(2, len(artifact["sources"]))

    def test_main_accepts_documented_extra_build_logs_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root = base / "default"
            root.mkdir()
            extra_logs = base / "extra" / "build-logs"
            extra_logs.mkdir(parents=True)
            (extra_logs / "compile.log").write_text("cannot find symbol\n", encoding="utf-8")
            previous_cwd = Path.cwd()
            stdout = io.StringIO()
            try:
                os.chdir(root)
                with mock.patch.object(
                    sys,
                    "argv",
                    ["persist_build_error_patterns.py", "--ingest", str(extra_logs)],
                ):
                    with contextlib.redirect_stdout(stdout):
                        persist_build_error_patterns.main()
            finally:
                os.chdir(previous_cwd)

            artifact = json.loads((root / "BUILD_ERROR_PATTERNS.json").read_text(encoding="utf-8"))

        self.assertEqual(1, artifact["aggregated_counts"]["java.cannot_find_symbol"])
        self.assertEqual(1, len(artifact["sources"]))

    def test_main_rejects_missing_ingest_without_writing_or_echoing_its_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            missing = root / "SYNTHETIC_PRIVATE_MISSING_INGEST"
            previous_cwd = Path.cwd()
            stdout = io.StringIO()
            stderr = io.StringIO()
            try:
                os.chdir(root)
                with mock.patch.object(
                    sys,
                    "argv",
                    ["persist_build_error_patterns.py", "--ingest", str(missing)],
                ):
                    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                        with self.assertRaises(SystemExit) as raised:
                            persist_build_error_patterns.main()
            finally:
                os.chdir(previous_cwd)

            artifacts = [
                root / ".build" / "error_patterns_db.json",
                root / "BUILD_ERROR_PATTERNS.json",
                root / "BUILD_ERROR_PATTERN_SUMMARY.md",
            ]
            artifacts_written = any(path.exists() for path in artifacts)
            emitted_stdout = stdout.getvalue()
            emitted_stderr = stderr.getvalue()

        self.assertEqual(2, raised.exception.code)
        self.assertEqual("", emitted_stdout)
        self.assertFalse(artifacts_written)
        self.assertNotIn("SYNTHETIC_PRIVATE_MISSING_INGEST", emitted_stderr)

    def test_main_redacts_ingest_path_resolution_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            previous_cwd = Path.cwd()
            stdout = io.StringIO()
            stderr = io.StringIO()
            try:
                os.chdir(root)
                with mock.patch.object(
                    sys,
                    "argv",
                    ["persist_build_error_patterns.py", "--ingest", "opaque-input"],
                ):
                    with mock.patch.object(
                        Path,
                        "resolve",
                        side_effect=OSError("SYNTHETIC_PRIVATE_RESOLVE_FAILURE"),
                    ):
                        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                            with self.assertRaises(SystemExit) as raised:
                                persist_build_error_patterns.main()
            finally:
                os.chdir(previous_cwd)

            artifacts_written = any(
                path.exists()
                for path in (
                    root / ".build" / "error_patterns_db.json",
                    root / "BUILD_ERROR_PATTERNS.json",
                    root / "BUILD_ERROR_PATTERN_SUMMARY.md",
                )
            )
            emitted_stdout = stdout.getvalue()
            emitted_stderr = stderr.getvalue()

        self.assertEqual(2, raised.exception.code)
        self.assertEqual("", emitted_stdout)
        self.assertFalse(artifacts_written)
        self.assertNotIn("SYNTHETIC_PRIVATE_RESOLVE_FAILURE", emitted_stderr)

    def test_main_rejects_ingest_path_that_changes_after_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            extra = root / "SYNTHETIC_PRIVATE_CHANGED_INGEST"
            (extra / "build-logs").mkdir(parents=True)
            (extra / "build-logs" / "compile.log").write_text(
                "cannot find symbol\n",
                encoding="utf-8",
            )
            extra_identity = extra.resolve()
            real_exists = Path.exists
            extra_exists_calls = 0

            def changing_exists(path):
                nonlocal extra_exists_calls
                if path == extra_identity:
                    extra_exists_calls += 1
                    return extra_exists_calls == 1
                return real_exists(path)

            previous_cwd = Path.cwd()
            stdout = io.StringIO()
            stderr = io.StringIO()
            try:
                os.chdir(root)
                with mock.patch.object(
                    sys,
                    "argv",
                    ["persist_build_error_patterns.py", "--ingest", str(extra)],
                ):
                    with mock.patch.object(Path, "exists", autospec=True, side_effect=changing_exists):
                        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                            with self.assertRaises(SystemExit) as raised:
                                persist_build_error_patterns.main()
            finally:
                os.chdir(previous_cwd)

            artifacts_written = any(
                path.exists()
                for path in (
                    root / ".build" / "error_patterns_db.json",
                    root / "BUILD_ERROR_PATTERNS.json",
                    root / "BUILD_ERROR_PATTERN_SUMMARY.md",
                )
            )
            output_directory_created = (root / ".build").exists()
            emitted_stdout = stdout.getvalue()
            emitted_stderr = stderr.getvalue()

        self.assertEqual(2, raised.exception.code)
        self.assertEqual("", emitted_stdout)
        self.assertFalse(artifacts_written)
        self.assertFalse(output_directory_created)
        self.assertNotIn("SYNTHETIC_PRIVATE_CHANGED_INGEST", emitted_stderr)

    def test_main_keeps_previous_database_when_atomic_replace_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            db_dir = root / ".build"
            db_dir.mkdir()
            db_path = db_dir / "error_patterns_db.json"
            previous_db = json.dumps(
                {
                    "generated_at": "2000-01-01T00:00:00Z",
                    "sources_scanned": [],
                    "aggregated_counts": {},
                    "examples": {},
                }
            )
            db_path.write_text(previous_db, encoding="utf-8")
            previous_cwd = Path.cwd()
            stdout = io.StringIO()
            try:
                os.chdir(root)
                with mock.patch.object(sys, "argv", ["persist_build_error_patterns.py"]):
                    with mock.patch.object(
                        persist_build_error_patterns.os,
                        "replace",
                        side_effect=OSError("synthetic replace failure"),
                    ):
                        with contextlib.redirect_stdout(stdout):
                            with self.assertRaisesRegex(
                                OSError,
                                "synthetic replace failure",
                            ):
                                persist_build_error_patterns.main()
            finally:
                os.chdir(previous_cwd)

            retained_db = db_path.read_text(encoding="utf-8")
            orphaned_temps = list(db_dir.glob(".error_patterns_db.json.*.tmp"))

        self.assertEqual(previous_db, retained_db)
        self.assertFalse(orphaned_temps, "temporary database file was not cleaned")

    def test_atomic_write_cleans_temporary_file_when_fsync_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "artifact.json"
            target.write_text("known-good", encoding="utf-8")
            with mock.patch.object(
                persist_build_error_patterns.os,
                "fsync",
                side_effect=OSError("synthetic fsync failure"),
            ):
                with self.assertRaisesRegex(OSError, "synthetic fsync failure"):
                    persist_build_error_patterns._atomic_write_text(
                        target,
                        "replacement",
                    )

            retained = target.read_text(encoding="utf-8")
            orphaned_temps = list(root.glob(".artifact.json.*.tmp"))

        self.assertEqual("known-good", retained)
        self.assertEqual(0, len(orphaned_temps), "fsync failure left a temporary file")

    def test_atomic_write_preserves_replace_error_when_cleanup_also_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "artifact.json"
            target.write_text("known-good", encoding="utf-8")
            caught_message = ""
            with mock.patch.object(
                persist_build_error_patterns.os,
                "replace",
                side_effect=OSError("synthetic replace failure"),
            ):
                with mock.patch.object(
                    Path,
                    "unlink",
                    side_effect=PermissionError("synthetic cleanup failure"),
                ):
                    try:
                        persist_build_error_patterns._atomic_write_text(
                            target,
                            "replacement",
                        )
                    except OSError as error:
                        caught_message = str(error)

            retained = target.read_text(encoding="utf-8")

        self.assertEqual("known-good", retained)
        self.assertTrue(
            caught_message == "synthetic replace failure",
            "cleanup failure masked the authoritative replacement failure",
        )

    def test_main_keeps_previous_top_json_when_its_atomic_replace_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            db_dir = root / ".build"
            db_dir.mkdir()
            (db_dir / "error_patterns_db.json").write_text("{}", encoding="utf-8")
            top_json = root / "BUILD_ERROR_PATTERNS.json"
            previous_top_json = "known-good-top-json"
            top_json.write_text(previous_top_json, encoding="utf-8")
            real_replace = os.replace
            replace_calls = 0

            def fail_second_replace(source, destination):
                nonlocal replace_calls
                replace_calls += 1
                if replace_calls == 2:
                    raise OSError("synthetic second replace failure")
                return real_replace(source, destination)

            previous_cwd = Path.cwd()
            stdout = io.StringIO()
            try:
                os.chdir(root)
                with mock.patch.object(sys, "argv", ["persist_build_error_patterns.py"]):
                    with mock.patch.object(
                        persist_build_error_patterns.os,
                        "replace",
                        side_effect=fail_second_replace,
                    ):
                        with contextlib.redirect_stdout(stdout):
                            with self.assertRaisesRegex(
                                OSError,
                                "synthetic second replace failure",
                            ):
                                persist_build_error_patterns.main()
            finally:
                os.chdir(previous_cwd)

            retained_top_json = top_json.read_text(encoding="utf-8")
            orphaned_temps = list(root.glob(".BUILD_ERROR_PATTERNS.json.*.tmp"))

        self.assertTrue(
            retained_top_json == previous_top_json,
            "top-level JSON was replaced before its atomic handoff succeeded",
        )
        self.assertFalse(orphaned_temps, "temporary top-level JSON was not cleaned")


if __name__ == "__main__":
    unittest.main()
