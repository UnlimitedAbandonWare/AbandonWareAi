"""Contract tests for the targeted, read-only SourceDirective Canary CLI.

These tests deliberately load the future module by file path so the CLI remains
an ordinary standard-library Python program.  They use only a tiny temporary
fixture; no repository application source is read or enumerated.
"""

from __future__ import annotations

import builtins
import hashlib
import importlib.util
import io
import json
import os
import pathlib
import shutil
import sys
import tempfile
import unittest
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from unittest import mock


HERE = pathlib.Path(__file__).parent
MODULE_PATH = HERE / "awx_notebook_source_directive_canary.py"

# A missing module is the intended RED failure until the implementation task.
if not MODULE_PATH.is_file():
    raise FileNotFoundError(f"missing production module: {MODULE_PATH}")

SPEC = importlib.util.spec_from_file_location("awx_notebook_source_directive_canary", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise ImportError(f"cannot load production module: {MODULE_PATH}")
canary = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = canary
SPEC.loader.exec_module(canary)


ACTIVE_ROOTS = (
    "main/java",
    "main/resources",
    "app/src/main/java_clean",
    "app/src/main/resources",
)
DECLARED_FILES = (
    "main/java/example/App.java",
    "main/resources/application.yml",
    "app/src/main/java_clean/example/AppClean.java",
    "app/src/main/resources/app.yml",
)
PACKET_NAMES = {
    "source-directive.json",
    "source-directive.sha256.txt",
    "desktop-ack.template.json",
    "manifest.json",
    "ready",
}


class CanaryContractTests(unittest.TestCase):
    """Behavioral contract for a source-read-only, sealed packet generator."""

    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp_dir.name) / "fixture-root"
        self.root.mkdir()
        self.contents = {
            DECLARED_FILES[0]: b"package example; class App {}\n",
            DECLARED_FILES[1]: b"spring:\n  application:\n    name: canary\n",
            DECLARED_FILES[2]: b"package example; class AppClean {}\n",
            DECLARED_FILES[3]: b"canary: true\n",
        }
        for relative, body in self.contents.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
        self.handoff_root = self.root / "data/agent-handoff/notebook"
        self.handoff_root.mkdir(parents=True)
        self.output_dir = self.handoff_root / "canary-packet"

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def prepare(self, files=DECLARED_FILES, **overrides):
        values = {
            "root": self.root,
            "output_dir": self.output_dir,
            "directive_id": "notebook-canary-001",
            "branch": "agent/notebook/canary",
            "identity_verified": True,
            "identity_reason": "match",
            "inspect_files": list(files),
        }
        values.update(overrides)
        return canary.prepare_packet(**values)

    def assert_failure(self, failure_class: str, callback) -> None:
        with self.assertRaises(canary.CanaryError) as raised:
            callback()
        self.assertEqual(failure_class, raised.exception.failure_class)

    def packet_bytes(self, packet_dir: pathlib.Path) -> dict[str, bytes]:
        return {name: (packet_dir / name).read_bytes() for name in PACKET_NAMES}

    def make_packet(self) -> dict:
        result = self.prepare()
        self.assertTrue(self.output_dir.is_dir())
        return result

    def test_00_public_limits_and_active_roots_are_fixed(self) -> None:
        self.assertEqual(ACTIVE_ROOTS, canary.ACTIVE_ROOTS)
        self.assertEqual(16, canary.MAX_FILES)
        self.assertEqual(8 * 1024 * 1024, canary.MAX_TOTAL_BYTES)
        self.assertEqual(30, canary.TIMEOUT_SECONDS)

    def test_01_directory_and_glob_inputs_are_broad_scan_forbidden(self) -> None:
        for candidate in ("main/java", "main/java/**/*.java", "main/java/*", "*.java"):
            with self.subTest(candidate=candidate):
                self.assert_failure("broad-scan-forbidden", lambda: self.prepare([candidate]))

    def test_02_non_explicit_path_forms_are_rejected(self) -> None:
        invalid = ("", " ", "/tmp/App.java", "C:\\temp\\App.java", "\\\\server\\share\\App.java", ".", "../App.java", "main/java/../App.java")
        for candidate in invalid:
            with self.subTest(candidate=candidate):
                self.assert_failure("target-not-explicit", lambda: self.prepare([candidate]))

    def test_03_file_outside_active_roots_is_wrong_sourceset(self) -> None:
        target = self.root / "project/src/main/java/Reference.java"
        target.parent.mkdir(parents=True)
        target.write_text("class Reference {}\n", encoding="utf-8")
        self.assert_failure("wrong-sourceset", lambda: self.prepare(["project/src/main/java/Reference.java"]))

    def test_04_missing_explicit_target_is_rejected(self) -> None:
        self.assert_failure("target-not-explicit", lambda: self.prepare(["main/java/example/Missing.java"]))

    def test_05_more_than_sixteen_files_exceeds_input_budget(self) -> None:
        files = []
        for index in range(17):
            relative = f"main/java/example/Extra{index}.java"
            target = self.root / relative
            target.write_text(f"class Extra{index} {{}}\n", encoding="utf-8")
            files.append(relative)
        self.assert_failure("input-budget-exceeded", lambda: self.prepare(files))

    def test_06_file_larger_than_eight_mib_exceeds_input_budget(self) -> None:
        target = self.root / DECLARED_FILES[0]
        target.write_bytes(b"x" * (8 * 1024 * 1024 + 1))
        self.assert_failure("input-budget-exceeded", lambda: self.prepare([DECLARED_FILES[0]]))

    def test_07_identity_mismatch_is_fail_closed(self) -> None:
        self.assert_failure(
            "smb-root-identity-changed",
            lambda: self.prepare(identity_verified=False, identity_reason="match"),
        )
        for reason in ("mismatch", "", "evidence-needed", "MATCH"):
            with self.subTest(reason=reason):
                self.assert_failure(
                    "smb-root-identity-changed",
                    lambda reason=reason: self.prepare(identity_verified=True, identity_reason=reason),
                )

    def test_08_reparse_ancestor_is_rejected_when_supported(self) -> None:
        real = self.root / "reparse-target"
        real.mkdir()
        (real / "App.java").write_text("class App {}\n", encoding="utf-8")
        linked_main = self.root / "main/java/linked"
        try:
            linked_main.symlink_to(real, target_is_directory=True)
        except (NotImplementedError, OSError):
            self.skipTest("OS cannot create a directory symlink/reparse fixture")
        self.assert_failure("reparse-traversal-risk", lambda: self.prepare(["main/java/linked/App.java"]))

    def test_09_secret_like_directive_id_is_redacted_and_rejected(self) -> None:
        secret = "sk-"" ""local-very-secret-directive-id"
        with self.assertRaises(canary.CanaryError) as raised:
            self.prepare(directive_id=secret)
        self.assertEqual("secret-leak-risk", raised.exception.failure_class)
        self.assertNotIn(secret, str(raised.exception))
        self.assertFalse(self.output_dir.exists())

    def test_10_valid_prepare_has_fixed_read_only_desktop_contract(self) -> None:
        returned = self.make_packet()
        directive = json.loads((self.output_dir / "source-directive.json").read_text(encoding="utf-8"))
        self.assertIs(returned["authorizedMutation"], False)
        self.assertIsNone(returned["sourceWriteRoot"])
        self.assertEqual([], returned["targetFiles"])
        self.assertEqual("desktop", returned["sourceOwner"])
        self.assertEqual("evidence_needed", returned["desktopFinalProof"])
        self.assertEqual("HOLD", returned["runtimeLineageVerdict"])
        self.assertEqual(False, directive["authorizedMutation"])
        self.assertIsNone(directive["sourceWriteRoot"])
        self.assertEqual([], directive["targetFiles"])
        self.assertEqual("desktop", directive["sourceOwner"])
        self.assertEqual("evidence_needed", directive["desktopFinalProof"])
        targets = directive["inspectionTargets"]
        self.assertEqual(list(DECLARED_FILES), [item["path"] for item in targets])
        self.assertEqual([len(self.contents[path]) for path in DECLARED_FILES], [item["size"] for item in targets])
        self.assertEqual(
            [hashlib.sha256(self.contents[path]).hexdigest() for path in DECLARED_FILES],
            [item["sha256"] for item in targets],
        )

    def test_11_equivalent_preparations_are_byte_identical(self) -> None:
        self.make_packet()
        first = self.packet_bytes(self.output_dir)
        other = self.handoff_root / "equivalent-packet"
        canary.prepare_packet(
            self.root, other, "notebook-canary-001", "agent/notebook/canary", True, "match", list(DECLARED_FILES)
        )
        self.assertEqual(first, self.packet_bytes(other))

    def test_12_final_output_must_not_already_exist(self) -> None:
        self.make_packet()
        self.assert_failure("output-exists", self.prepare)

    def test_13_tampering_or_extra_packet_entry_fails_hash_validation(self) -> None:
        self.make_packet()
        directive_path = self.output_dir / "source-directive.json"
        directive_path.write_bytes(directive_path.read_bytes() + b" ")
        self.assert_failure("packet-hash-mismatch", lambda: canary.validate_packet(self.root, self.output_dir))

        shutil.rmtree(self.output_dir)
        self.make_packet()
        (self.output_dir / "unexpected.txt").write_text("unexpected\n", encoding="utf-8")
        self.assert_failure("packet-hash-mismatch", lambda: canary.validate_packet(self.root, self.output_dir))

    def test_14_changed_source_preimage_is_detected_by_validation(self) -> None:
        self.make_packet()
        (self.root / DECLARED_FILES[0]).write_text("package example; class Changed {}\n", encoding="utf-8")
        self.assert_failure("changed-preimage", lambda: canary.validate_packet(self.root, self.output_dir))

    def test_15_missing_ready_is_packet_hash_mismatch(self) -> None:
        self.make_packet()
        (self.output_dir / "ready").unlink()
        self.assert_failure("packet-hash-mismatch", lambda: canary.validate_packet(self.root, self.output_dir))

    @contextmanager
    def source_access_guard(self, declared: set[pathlib.Path]):
        source_roots = tuple((self.root / entry).resolve() for entry in ACTIVE_ROOTS)
        reads: list[pathlib.Path] = []

        def as_path(value):
            try:
                return pathlib.Path(value).resolve()
            except (TypeError, OSError):
                return None

        def is_source(value) -> bool:
            candidate = as_path(value)
            return candidate is not None and any(candidate == root or root in candidate.parents for root in source_roots)

        def scan_guard(original):
            def guarded(path, *args, **kwargs):
                if is_source(path):
                    raise AssertionError(f"source scan attempted: {path}")
                return original(path, *args, **kwargs)
            return guarded

        def open_guard(original):
            def guarded(file, mode="r", *args, **kwargs):
                if is_source(file):
                    path = as_path(file)
                    if any(flag in mode for flag in ("w", "a", "x", "+")):
                        raise AssertionError(f"source write attempted: {file}")
                    if path not in declared:
                        raise AssertionError(f"undeclared source read attempted: {file}")
                    reads.append(path)
                return original(file, mode, *args, **kwargs)
            return guarded

        original_builtin_open = builtins.open
        original_io_open = io.open
        original_os_open = os.open

        def os_open_guard(path, flags, *args, **kwargs):
            if is_source(path):
                write_flags = os.O_WRONLY | os.O_RDWR | os.O_APPEND | os.O_CREAT | os.O_TRUNC | os.O_EXCL
                if flags & write_flags:
                    raise AssertionError(f"source write attempted: {path}")
                resolved = as_path(path)
                if resolved not in declared:
                    raise AssertionError(f"undeclared source read attempted: {path}")
                reads.append(resolved)
            return original_os_open(path, flags, *args, **kwargs)

        with mock.patch.object(pathlib.Path, "glob", scan_guard(pathlib.Path.glob)), \
             mock.patch.object(pathlib.Path, "rglob", scan_guard(pathlib.Path.rglob)), \
             mock.patch.object(pathlib.Path, "iterdir", scan_guard(pathlib.Path.iterdir)), \
             mock.patch("os.walk", scan_guard(os.walk)), \
             mock.patch("builtins.open", open_guard(original_builtin_open)), \
             mock.patch("io.open", open_guard(original_io_open)), \
             mock.patch("os.open", os_open_guard):
            yield reads

    def test_16_prepare_reads_only_declared_files_without_source_scan_or_write(self) -> None:
        declared = {(self.root / relative).resolve() for relative in DECLARED_FILES[:2]}
        with self.source_access_guard(declared), \
             mock.patch.object(canary, "_open_pinned_read", wraps=canary._open_pinned_read) as pinned_reads:
            self.prepare(DECLARED_FILES[:2])
        opened = {
            (pathlib.Path(call.args[0]) / pathlib.Path(*call.args[1])).resolve()
            for call in pinned_reads.call_args_list
        }
        self.assertTrue(opened, "fixture must prove actual pinned source-file reads")
        self.assertTrue(opened.issubset(declared))

    def test_17_clock_budget_crossing_thirty_seconds_is_rejected(self) -> None:
        ticks = iter((100.0, 130.001))
        self.assert_failure("input-budget-exceeded", lambda: self.prepare(clock=lambda: next(ticks)))
        self.assertFalse(self.output_dir.exists())

    def test_18_publication_faults_leave_no_final_packet_and_clean_own_staging(self) -> None:
        unrelated_staging = self.handoff_root / ".canary-packet.staging-unrelated"
        unrelated_staging.mkdir()
        marker = unrelated_staging / "keep.txt"
        marker.write_text("unrelated staging survives\n", encoding="utf-8")
        for fault in ("before-ready", "before-rename"):
            with self.subTest(fault=fault):
                self.assert_failure("bundle-publication-nonatomic", lambda: self.prepare(fault_at=fault))
                self.assertFalse(self.output_dir.exists())
                leftovers = [path for path in self.handoff_root.iterdir() if path.name.startswith(".canary-packet.staging-")]
                self.assertEqual([unrelated_staging], leftovers)
                self.assertTrue(marker.is_file())
                self.assertEqual("unrelated staging survives\n", marker.read_text(encoding="utf-8"))

    def test_19_validation_lists_packet_directory_once_and_rejects_more_than_six_entries(self) -> None:
        self.make_packet()
        (self.output_dir / "extra").write_text("x", encoding="utf-8")
        original_iterdir = pathlib.Path.iterdir
        frozen_entries = tuple(original_iterdir(self.output_dir))
        calls = 0

        def counted_iterdir(path):
            nonlocal calls
            if path != self.output_dir:
                raise AssertionError(f"unexpected directory listing: {path}")
            calls += 1
            return iter(frozen_entries)

        def forbidden_scan(*args, **kwargs):
            raise AssertionError("validation must use exactly one Path.iterdir packet listing")

        with mock.patch.object(pathlib.Path, "iterdir", counted_iterdir), \
             mock.patch.object(pathlib.Path, "glob", forbidden_scan), \
             mock.patch.object(pathlib.Path, "rglob", forbidden_scan), \
             mock.patch("os.listdir", forbidden_scan), \
             mock.patch("os.scandir", forbidden_scan), \
             mock.patch("os.walk", forbidden_scan):
            self.assert_failure("packet-hash-mismatch", lambda: canary.validate_packet(self.root, self.output_dir))
        self.assertEqual(1, calls)

    def test_20_parser_has_prepare_and_validate_but_no_ack_command(self) -> None:
        parser = canary.build_parser()
        help_text = parser.format_help()
        self.assertIn("{prepare,validate}", help_text)
        with self.assertRaises(SystemExit) as raised, redirect_stderr(io.StringIO()) as errors:
            parser.parse_args(["ack"])
        self.assertNotEqual(0, raised.exception.code)
        self.assertIn("invalid choice", errors.getvalue())

    def test_21_ack_template_is_unsealed_evidence_needed_and_manifest_hashes_it(self) -> None:
        self.make_packet()
        directive_bytes = (self.output_dir / "source-directive.json").read_bytes()
        ack_bytes = (self.output_dir / "desktop-ack.template.json").read_bytes()
        ack = json.loads(ack_bytes)
        manifest = json.loads((self.output_dir / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("evidence_needed", ack["status"])
        self.assertIsNone(ack["acknowledgedAt"])
        self.assertEqual(hashlib.sha256(directive_bytes).hexdigest(), ack["sourceDirectiveSha256"])
        self.assertIn("sourceDirectiveSha256", ack)
        for forbidden_key in ("manifestSha256", "sealedManifestSha256"):
            self.assertNotIn(forbidden_key, ack)
        payload_hashes = manifest["payloadHashes"]
        self.assertEqual(hashlib.sha256(ack_bytes).hexdigest(), payload_hashes["desktop-ack.template.json"])
        self.assertNotIn("ready", payload_hashes)

    def _reseal(self) -> None:
        directive_path = self.output_dir / "source-directive.json"
        directive_bytes = canary._canonical_json(json.loads(directive_path.read_text(encoding="utf-8")))
        directive_path.write_bytes(directive_bytes)
        directive_hash = hashlib.sha256(directive_bytes).hexdigest()
        sidecar = (directive_hash + "\n").encode("ascii")
        (self.output_dir / "source-directive.sha256.txt").write_bytes(sidecar)
        ack_path = self.output_dir / "desktop-ack.template.json"
        ack = json.loads(ack_path.read_text(encoding="utf-8"))
        ack["sourceDirectiveSha256"] = directive_hash
        ack_bytes = canary._canonical_json(ack)
        ack_path.write_bytes(ack_bytes)
        manifest_path = self.output_dir / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["payloadHashes"] = {
            "source-directive.json": directive_hash,
            "source-directive.sha256.txt": hashlib.sha256(sidecar).hexdigest(),
            "desktop-ack.template.json": hashlib.sha256(ack_bytes).hexdigest(),
        }
        manifest_bytes = canary._canonical_json(manifest)
        manifest_path.write_bytes(manifest_bytes)
        (self.output_dir / "ready").write_bytes((hashlib.sha256(manifest_bytes).hexdigest() + "\n").encode("ascii"))

    def test_22_prepare_rejects_undeclared_output_destination_before_staging(self) -> None:
        (self.root / "docs").mkdir()
        destinations = (
            self.root.parent / "outside-root",
            self.root / "canary-packet",
            self.root / "docs/canary-packet",
            self.root / "main/java/canary-packet",
        )
        for destination in destinations:
            with self.subTest(destination=destination), mock.patch.object(
                canary,
                "_create_pinned_staging",
                side_effect=AssertionError("unauthorized staging attempted"),
            ) as staging:
                self.assert_failure(
                    "undeclared-source-write",
                    lambda destination=destination: self.prepare(output_dir=destination),
                )
                staging.assert_not_called()
                self.assertFalse(destination.exists())

    def test_23_prepare_detects_precheck_to_open_target_swap(self) -> None:
        target = self.root / DECLARED_FILES[0]
        original_open = canary._open_pinned_read
        swapped = False

        def swap_before_open(root, parts):
            nonlocal swapped
            if pathlib.Path(root, *parts) == target and not swapped:
                swapped = True
                replacement = target.with_name("App.replacement")
                replacement.write_text("package example; class Swapped {}\n", encoding="utf-8")
                replacement.replace(target)
            return original_open(root, parts)

        with mock.patch.object(canary, "_open_pinned_read", side_effect=swap_before_open):
            self.assert_failure("changed-preimage", lambda: self.prepare([DECLARED_FILES[0]]))
        self.assertTrue(swapped)

    def test_24_resealed_semantic_tamper_is_packet_hash_mismatch_not_key_error(self) -> None:
        self.make_packet()
        directive_path = self.output_dir / "source-directive.json"
        directive = json.loads(directive_path.read_text(encoding="utf-8"))
        directive.pop("publicApiChange")
        directive_path.write_bytes(canary._canonical_json(directive))
        self._reseal()
        self.assert_failure("packet-hash-mismatch", lambda: canary.validate_packet(self.root, self.output_dir))

        shutil.rmtree(self.output_dir)
        self.make_packet()
        ack_path = self.output_dir / "desktop-ack.template.json"
        ack = json.loads(ack_path.read_text(encoding="utf-8"))
        ack["manifestSha256"] = "not-permitted"
        ack_path.write_bytes(canary._canonical_json(ack))
        self._reseal()
        self.assert_failure("packet-hash-mismatch", lambda: canary.validate_packet(self.root, self.output_dir))

    def test_25_publish_never_replaces_a_concurrent_final_directory(self) -> None:
        original_publish = canary._publish

        def concurrent_final(staging, output_dir, pins):
            output_dir.mkdir()
            return original_publish(staging, output_dir, pins)

        with mock.patch.object(canary, "_publish", side_effect=concurrent_final):
            self.assert_failure("bundle-publication-nonatomic", self.prepare)
        self.assertTrue(self.output_dir.is_dir())
        self.assertEqual([], list(self.output_dir.iterdir()))

    def test_26_validation_uses_one_aggregate_clock_budget(self) -> None:
        self.make_packet()
        ticks = iter((0.0, 0.0, 0.0, 30.001))
        self.assert_failure(
            "input-budget-exceeded",
            lambda: canary.validate_packet(self.root, self.output_dir, clock=lambda: next(ticks)),
        )

    def test_27_cli_filesystem_failures_are_redacted(self) -> None:
        output = io.StringIO()
        self.handoff_root.rmdir()
        missing_parent = self.output_dir
        with redirect_stdout(output):
            exit_code = canary.main([
                "prepare", "--root", str(self.root), "--output-dir", str(missing_parent),
                "--directive-id", "notebook-canary-001", "--branch", "agent/notebook/canary",
                "--identity-verified", "true", "--identity-reason", "match",
                "--inspect-file", DECLARED_FILES[0],
            ])
        self.assertEqual(2, exit_code)
        self.assertEqual({"failureClass": "bundle-publication-nonatomic", "valid": False}, json.loads(output.getvalue()))

        self.handoff_root.mkdir()
        output = io.StringIO()
        with mock.patch.object(canary, "_create_pinned_staging", side_effect=PermissionError("private path")), redirect_stdout(output):
            exit_code = canary.main([
                "prepare", "--root", str(self.root), "--output-dir", str(self.output_dir),
                "--directive-id", "notebook-canary-001", "--branch", "agent/notebook/canary",
                "--identity-verified", "true", "--identity-reason", "match",
                "--inspect-file", DECLARED_FILES[0],
            ])
        self.assertEqual(2, exit_code)
        self.assertEqual({"failureClass": "bundle-publication-nonatomic", "valid": False}, json.loads(output.getvalue()))

    @unittest.skipUnless(os.name == "nt", "Windows case-insensitive identity regression")
    def test_28_case_variant_explicit_paths_are_not_distinct(self) -> None:
        self.assert_failure(
            "target-not-explicit",
            lambda: self.prepare([DECLARED_FILES[0], "MAIN/JAVA/EXAMPLE/APP.JAVA"]),
        )

    def test_29_packet_reparse_entry_is_rejected_when_supported(self) -> None:
        self.make_packet()
        target = self.root / "ready-target"
        target.write_text("not-ready\n", encoding="utf-8")
        ready = self.output_dir / "ready"
        ready.unlink()
        try:
            ready.symlink_to(target)
        except (NotImplementedError, OSError):
            self.skipTest("OS cannot create a file symlink/reparse fixture")
        self.assert_failure("packet-hash-mismatch", lambda: canary.validate_packet(self.root, self.output_dir))

    def test_30_prepare_uses_pinned_output_parent_creation(self) -> None:
        with mock.patch.object(canary, "_create_pinned_staging", wraps=canary._create_pinned_staging) as create:
            self.make_packet()
        self.assertEqual(1, create.call_count)

    def test_31_validation_reads_packet_payloads_from_pinned_handles(self) -> None:
        self.make_packet()
        with mock.patch.object(canary, "_read_pinned_file", wraps=canary._read_pinned_file) as read:
            self.assertTrue(canary.validate_packet(self.root, self.output_dir)["valid"])
        self.assertEqual(5, read.call_count)

    @unittest.skipUnless(os.name == "nt", "Windows handle reparse defense")
    def test_32_windows_synthetic_reparse_attribute_is_rejected(self) -> None:
        with mock.patch.object(canary, "_windows_handle_attributes", return_value=0x400):
            self.assert_failure("reparse-traversal-risk", lambda: self.prepare([DECLARED_FILES[0]]))

    def test_33_non_windows_gate_fails_before_packet_or_source_io(self) -> None:
        with mock.patch.object(canary, "_windows_platform_supported", return_value=False), \
             mock.patch.object(canary, "_assert_output_boundary") as boundary, \
             mock.patch.object(canary, "_inspect_targets") as inspect_targets, \
             mock.patch.object(canary, "_create_pinned_staging") as create_staging:
            self.assert_failure("bundle-publication-nonatomic", self.prepare)
        boundary.assert_not_called()
        inspect_targets.assert_not_called()
        create_staging.assert_not_called()

        with mock.patch.object(canary, "_windows_platform_supported", return_value=False), \
             mock.patch.object(canary, "_open_pinned_directory") as open_packet, \
             mock.patch.object(pathlib.Path, "iterdir") as list_packet:
            self.assert_failure(
                "bundle-publication-nonatomic",
                lambda: canary.validate_packet(self.root, self.output_dir),
            )
        open_packet.assert_not_called()
        list_packet.assert_not_called()

    def test_34_validate_rejects_packet_outside_handoff_before_enumeration(self) -> None:
        self.make_packet()
        unauthorized = self.root / "docs/copied-packet"
        unauthorized.parent.mkdir()
        shutil.copytree(self.output_dir, unauthorized)

        with mock.patch.object(
            canary,
            "_open_pinned_directory",
            side_effect=AssertionError("unauthorized packet directory pinned open"),
        ) as open_packet, mock.patch.object(
            pathlib.Path,
            "iterdir",
            side_effect=AssertionError("unauthorized packet directory enumerated"),
        ) as list_packet:
            self.assert_failure(
                "undeclared-source-write",
                lambda: canary.validate_packet(self.root, unauthorized),
            )
        open_packet.assert_not_called()
        list_packet.assert_not_called()

    def test_35_aggregate_source_reads_never_exceed_eight_mib(self) -> None:
        first = "main/java/example/FirstLarge.java"
        second = "main/java/example/SecondLarge.java"
        (self.root / first).write_bytes(b"a" * (5 * 1024 * 1024))
        (self.root / second).write_bytes(b"b" * (4 * 1024 * 1024 + 1))
        original_read = os.read
        bytes_read = 0

        def counted_read(descriptor, count):
            nonlocal bytes_read
            block = original_read(descriptor, count)
            bytes_read += len(block)
            return block

        with mock.patch.object(canary.os, "read", side_effect=counted_read):
            self.assert_failure(
                "input-budget-exceeded",
                lambda: self.prepare([first, second]),
            )
        self.assertLessEqual(bytes_read, 8 * 1024 * 1024)
        self.assertFalse(self.output_dir.exists())

    def test_36_validate_rejects_declared_aggregate_over_budget_before_source_read(self) -> None:
        self.prepare(DECLARED_FILES[:2])
        oversized = (
            (DECLARED_FILES[0], b"a" * (5 * 1024 * 1024)),
            (DECLARED_FILES[1], b"b" * (4 * 1024 * 1024 + 1)),
        )
        directive_path = self.output_dir / "source-directive.json"
        directive = json.loads(directive_path.read_text(encoding="utf-8"))
        inspection_targets = []
        for relative, body in oversized:
            (self.root / relative).write_bytes(body)
            inspection_targets.append(
                {
                    "path": relative,
                    "size": len(body),
                    "sha256": hashlib.sha256(body).hexdigest(),
                }
            )
        directive["inspectionTargets"] = inspection_targets
        directive_path.write_bytes(canary._canonical_json(directive))
        self._reseal()

        with mock.patch.object(
            canary,
            "_hash_target",
            side_effect=AssertionError("oversized packet reached source hashing"),
        ) as hash_target:
            self.assert_failure(
                "packet-hash-mismatch",
                lambda: canary.validate_packet(self.root, self.output_dir),
            )
        hash_target.assert_not_called()

    def test_37_prepare_budget_starts_before_output_boundary(self) -> None:
        now = [0.0]
        original_boundary = canary._assert_output_boundary

        def slow_boundary(root, output_dir):
            original_boundary(root, output_dir)
            now[0] = 30.001

        with mock.patch.object(canary, "_assert_output_boundary", side_effect=slow_boundary), \
             mock.patch.object(canary, "_inspect_targets", wraps=canary._inspect_targets) as inspect_targets:
            self.assert_failure(
                "input-budget-exceeded",
                lambda: self.prepare(clock=lambda: now[0]),
            )
        inspect_targets.assert_not_called()
        self.assertFalse(self.output_dir.exists())

    def test_38_prepare_budget_covers_packet_write_and_prevents_publication(self) -> None:
        now = [0.0]
        original_write = canary._write_exclusive

        def slow_write(directory_pin, name, data):
            original_write(directory_pin, name, data)
            now[0] = 30.001

        with mock.patch.object(canary, "_write_exclusive", side_effect=slow_write):
            self.assert_failure(
                "input-budget-exceeded",
                lambda: self.prepare(clock=lambda: now[0]),
            )
        self.assertFalse(self.output_dir.exists())
        leftovers = [
            path
            for path in self.handoff_root.iterdir()
            if path.name.startswith(".canary-packet.staging-")
        ]
        self.assertEqual([], leftovers)

    def test_39_validate_budget_starts_before_packet_enumeration(self) -> None:
        self.make_packet()
        now = [0.0]
        original_open = canary._open_pinned_directory

        def slow_open(path, *args, **kwargs):
            pins = original_open(path, *args, **kwargs)
            now[0] = 30.001
            return pins

        with mock.patch.object(canary, "_open_pinned_directory", side_effect=slow_open), \
             mock.patch.object(
                 pathlib.Path,
                 "iterdir",
                 side_effect=AssertionError("expired validation enumerated packet"),
             ) as list_packet:
            self.assert_failure(
                "input-budget-exceeded",
                lambda: canary.validate_packet(
                    self.root,
                    self.output_dir,
                    clock=lambda: now[0],
                ),
            )
        list_packet.assert_not_called()

    def test_40_secret_like_inspection_path_is_redacted_and_rejected(self) -> None:
        relative = "main/java/example/sk-"" ""local-very-secret-path.java"
        (self.root / relative).write_text("class SecretPath {}\n", encoding="utf-8")

        with self.assertRaises(canary.CanaryError) as raised:
            self.prepare([relative])
        self.assertEqual("secret-leak-risk", raised.exception.failure_class)
        self.assertNotIn(relative, str(raised.exception))
        self.assertFalse(self.output_dir.exists())

    def test_41_blank_directive_id_or_branch_is_rejected_before_publication(self) -> None:
        cases = (
            {"directive_id": ""},
            {"directive_id": "   "},
            {"branch": ""},
            {"branch": "\t "},
        )
        for overrides in cases:
            with self.subTest(overrides=overrides):
                try:
                    with mock.patch.object(
                        canary,
                        "_create_pinned_staging",
                        side_effect=AssertionError("blank metadata reached staging"),
                    ) as create_staging:
                        self.assert_failure(
                            "target-not-explicit",
                            lambda overrides=overrides: self.prepare(**overrides),
                        )
                    create_staging.assert_not_called()
                    self.assertFalse(self.output_dir.exists())
                finally:
                    if self.output_dir.exists():
                        shutil.rmtree(self.output_dir)

    def test_42_validate_rejects_resealed_blank_metadata(self) -> None:
        for field in ("directiveId", "provenBranch"):
            with self.subTest(field=field):
                try:
                    self.make_packet()
                    directive_path = self.output_dir / "source-directive.json"
                    directive = json.loads(directive_path.read_text(encoding="utf-8"))
                    directive[field] = "   "
                    directive_path.write_bytes(canary._canonical_json(directive))
                    if field == "directiveId":
                        ack_path = self.output_dir / "desktop-ack.template.json"
                        ack = json.loads(ack_path.read_text(encoding="utf-8"))
                        ack["directiveId"] = "   "
                        ack_path.write_bytes(canary._canonical_json(ack))
                        manifest_path = self.output_dir / "manifest.json"
                        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                        manifest["bundleId"] = "   "
                        manifest_path.write_bytes(canary._canonical_json(manifest))
                    self._reseal()
                    self.assert_failure(
                        "packet-hash-mismatch",
                        lambda: canary.validate_packet(self.root, self.output_dir),
                    )
                finally:
                    if self.output_dir.exists():
                        shutil.rmtree(self.output_dir)

    def test_43_prepare_requires_one_direct_child_of_handoff_root(self) -> None:
        self.output_dir.mkdir()
        nested = self.output_dir / "nested-packet"

        with mock.patch.object(
            canary,
            "_create_pinned_staging",
            side_effect=AssertionError("nested packet reached staging creation"),
        ) as create_staging:
            self.assert_failure(
                "undeclared-source-write",
                lambda: self.prepare(output_dir=nested),
            )
        create_staging.assert_not_called()

    def test_44_validate_requires_one_direct_child_of_handoff_root(self) -> None:
        self.make_packet()
        outer = self.handoff_root / "outer-packet"
        nested = outer / "nested-packet"
        shutil.copytree(self.output_dir, nested)

        with mock.patch.object(
            canary,
            "_open_pinned_directory",
            side_effect=AssertionError("nested packet reached pinned open"),
        ) as open_packet, mock.patch.object(
            pathlib.Path,
            "iterdir",
            side_effect=AssertionError("nested packet reached enumeration"),
        ) as list_packet:
            self.assert_failure(
                "undeclared-source-write",
                lambda: canary.validate_packet(self.root, nested),
            )
        open_packet.assert_not_called()
        list_packet.assert_not_called()

    def test_45_validate_packet_read_stops_after_deadline_expires(self) -> None:
        self.make_packet()
        for name in PACKET_NAMES:
            (self.output_dir / name).write_bytes(b"x" * (2 * canary._CHUNK_SIZE + 1))
        original_read = os.read
        now = [0.0]
        nonempty_reads = 0

        def expiring_read(descriptor, count):
            nonlocal nonempty_reads
            block = original_read(descriptor, count)
            if block:
                nonempty_reads += 1
                if nonempty_reads == 1:
                    now[0] = 30.001
            return block

        with mock.patch.object(canary.os, "read", side_effect=expiring_read):
            self.assert_failure(
                "input-budget-exceeded",
                lambda: canary.validate_packet(
                    self.root,
                    self.output_dir,
                    clock=lambda: now[0],
                ),
            )
        self.assertEqual(1, nonempty_reads)

    def test_46_validate_rejects_packet_payload_over_eight_mib_before_read(self) -> None:
        self.make_packet()
        oversized = self.output_dir / "source-directive.json"
        oversized.write_bytes(b"x" * (canary.MAX_TOTAL_BYTES + 1))
        ordered_entries = [oversized] + [
            self.output_dir / name
            for name in sorted(PACKET_NAMES - {"source-directive.json"})
        ]

        with mock.patch.object(pathlib.Path, "iterdir", return_value=iter(ordered_entries)), \
             mock.patch.object(
                 canary.os,
                 "read",
                 side_effect=AssertionError("oversized packet payload was read"),
             ) as packet_read:
            self.assert_failure(
                "packet-hash-mismatch",
                lambda: canary.validate_packet(self.root, self.output_dir),
            )
        packet_read.assert_not_called()

    def test_47_secret_path_batch_fails_before_output_or_source_filesystem_work(self) -> None:
        secret_path = "main/java/example/sk-"" ""local-very-secret-path.java"

        with mock.patch.object(
            canary,
            "_assert_output_boundary",
            side_effect=AssertionError("secret batch reached output filesystem checks"),
        ) as output_boundary, mock.patch.object(
            canary,
            "_open_pinned_read",
            side_effect=AssertionError("valid prefix target was read before secret rejection"),
        ) as source_read:
            self.assert_failure(
                "secret-leak-risk",
                lambda: self.prepare([DECLARED_FILES[0], secret_path]),
            )
        output_boundary.assert_not_called()
        source_read.assert_not_called()

    def test_48_validate_secret_path_batch_fails_before_source_rehash(self) -> None:
        self.prepare(DECLARED_FILES[:2])
        directive_path = self.output_dir / "source-directive.json"
        directive = json.loads(directive_path.read_text(encoding="utf-8"))
        directive["inspectionTargets"][1]["path"] = (
            "main/resources/sk-"" ""local-very-secret-path.yml"
        )
        directive_path.write_bytes(canary._canonical_json(directive))
        self._reseal()

        with mock.patch.object(
            canary,
            "_open_pinned_read",
            side_effect=AssertionError("source rehash began before path-batch rejection"),
        ) as source_read:
            self.assert_failure(
                "packet-hash-mismatch",
                lambda: canary.validate_packet(self.root, self.output_dir),
            )
        source_read.assert_not_called()

    def test_49_public_deadline_starts_before_platform_gate(self) -> None:
        for operation in ("prepare", "validate"):
            with self.subTest(operation=operation):
                now = [0.0]

                def slow_platform_gate():
                    now[0] = 30.001

                with mock.patch.object(
                    canary,
                    "_require_windows_platform",
                    side_effect=slow_platform_gate,
                ), mock.patch.object(
                    canary,
                    "_assert_output_boundary",
                    side_effect=AssertionError("expired public call reached filesystem boundary"),
                ) as output_boundary:
                    if operation == "prepare":
                        self.assert_failure(
                            "input-budget-exceeded",
                            lambda: self.prepare(clock=lambda: now[0]),
                        )
                    else:
                        self.assert_failure(
                            "input-budget-exceeded",
                            lambda: canary.validate_packet(
                                self.root,
                                self.output_dir,
                                clock=lambda: now[0],
                            ),
                        )
                output_boundary.assert_not_called()

    def test_50_validate_checks_deadline_after_each_json_document(self) -> None:
        self.make_packet()
        original_load = canary._load_packet_json

        for expire_after in (1, 2, 3):
            with self.subTest(expire_after=expire_after):
                now = [0.0]
                calls = 0

                def expiring_load(data):
                    nonlocal calls
                    calls += 1
                    value = original_load(data)
                    if calls == expire_after:
                        now[0] = 30.001
                    return value

                with mock.patch.object(canary, "_load_packet_json", side_effect=expiring_load):
                    self.assert_failure(
                        "input-budget-exceeded",
                        lambda: canary.validate_packet(
                            self.root,
                            self.output_dir,
                            clock=lambda: now[0],
                        ),
                    )
                self.assertEqual(expire_after, calls)

    def test_51_prepare_checks_deadline_immediately_before_publish(self) -> None:
        original_write = canary._write_exclusive
        ready_written = [False]
        after_ready_clock_calls = [0]

        def tracked_write(directory_pin, name, data):
            original_write(directory_pin, name, data)
            if name == "ready":
                ready_written[0] = True

        def clock():
            if not ready_written[0]:
                return 0.0
            after_ready_clock_calls[0] += 1
            return 0.0 if after_ready_clock_calls[0] == 1 else 30.001

        with mock.patch.object(canary, "_write_exclusive", side_effect=tracked_write), \
             mock.patch.object(
                 canary,
                 "_publish",
                 side_effect=AssertionError("expired packet was published"),
             ) as publish:
            self.assert_failure(
                "input-budget-exceeded",
                lambda: self.prepare(clock=clock),
            )
        publish.assert_not_called()
        self.assertEqual(2, after_ready_clock_calls[0])
        self.assertFalse(self.output_dir.exists())

    def test_52_validate_rehash_never_exceeds_eight_mib_for_forged_sizes(self) -> None:
        self.prepare(DECLARED_FILES[:2])
        first_body = b"a" * (5 * 1024 * 1024)
        second_body = b"b" * (4 * 1024 * 1024 + 1)
        (self.root / DECLARED_FILES[0]).write_bytes(first_body)
        (self.root / DECLARED_FILES[1]).write_bytes(second_body)

        directive_path = self.output_dir / "source-directive.json"
        directive = json.loads(directive_path.read_text(encoding="utf-8"))
        directive["inspectionTargets"] = [
            {
                "path": DECLARED_FILES[0],
                "size": len(first_body),
                "sha256": hashlib.sha256(first_body).hexdigest(),
            },
            {
                "path": DECLARED_FILES[1],
                "size": 3 * 1024 * 1024,
                "sha256": hashlib.sha256(second_body).hexdigest(),
            },
        ]
        directive_path.write_bytes(canary._canonical_json(directive))
        self._reseal()
        original_read = os.read
        source_bytes_read = 0

        def counted_read(descriptor, count):
            nonlocal source_bytes_read
            block = original_read(descriptor, count)
            source_bytes_read += len(block)
            return block

        with mock.patch.object(canary.os, "read", side_effect=counted_read):
            self.assert_failure(
                "input-budget-exceeded",
                lambda: canary.validate_packet(self.root, self.output_dir),
            )
        self.assertLessEqual(source_bytes_read, canary.MAX_TOTAL_BYTES)

    def test_53_prepare_rejects_packet_aggregate_over_eight_mib_before_staging(self) -> None:
        oversized_directive_id = "d" * (3 * 1024 * 1024)

        with mock.patch.object(
            canary,
            "_create_pinned_staging",
            side_effect=AssertionError("oversized packet reached staging creation"),
        ) as create_staging:
            self.assert_failure(
                "input-budget-exceeded",
                lambda: self.prepare(directive_id=oversized_directive_id),
            )
        create_staging.assert_not_called()
        self.assertFalse(self.output_dir.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
