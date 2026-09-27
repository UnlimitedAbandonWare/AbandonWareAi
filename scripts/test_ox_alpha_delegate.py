#!/usr/bin/env python3
"""Contract tests for the read-only Ox Alpha CLI delegate."""

from __future__ import annotations

import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "ox-alpha-delegate" / "ox_alpha_delegate.py"
SPEC = importlib.util.spec_from_file_location("ox_alpha_delegate", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
delegate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = delegate
SPEC.loader.exec_module(delegate)


EXPECTED_OUTPUT_KEYS = {
    "status",
    "summary",
    "findings",
    "evidence",
    "proposedTests",
    "elapsedMs",
    "exitCode",
    "model",
    "stderrTail",
    "changedFiles",
    "fallbackUsed",
    "cliVersion",
    "firstEventMs",
    "firstToolEventMs",
    "lastToolEventMs",
    "firstTextEventMs",
    "modelTextBytes",
    "terminationReason",
    "outputContractExceeded",
}

EXPECTED_LOG_KEYS = {
    "request_id",
    "model",
    "started_at",
    "elapsed_ms",
    "exit_code",
    "parsed_event_count",
    "stdout_bytes",
    "stderr_bytes",
    "timeout",
    "changed_files",
    "redacted_secret_count",
    "fallback_used",
    "final_reason",
    "cli_version",
    "first_event_ms",
    "first_tool_event_ms",
    "last_tool_event_ms",
    "first_text_event_ms",
    "model_text_bytes",
    "termination_reason",
    "output_contract_exceeded",
}


def successful_jsonl() -> str:
    payload = {
        "summary": "Three line-level candidates require Codex verification.",
        "findings": [
            {
                "description": "Boundary behavior may be surprising.",
                "counterexample": "The behavior may be intentional.",
                "expectedSideEffects": "A repair could alter callers.",
            }
        ],
        "evidence": [
            {"file": "sample.py", "line": 1, "reason": "first line"},
            {"file": "sample.py", "line": 2, "reason": "second line"},
            {"file": "sample.py", "line": 3, "reason": "third line"},
        ],
        "proposedTests": ["exercise the boundary", "retain the counterexample"],
    }
    events = [
        {"type": "step_start", "part": {}},
        {
            "type": "tool_use",
            "part": {"tool": "read", "state": {"status": "completed"}},
        },
        {"type": "text", "part": {"text": json.dumps(payload)}},
        {"type": "step_finish", "part": {}},
    ]
    return "\n".join(json.dumps(event) for event in events) + "\n"


def process_result(
    *,
    stdout: str | None = None,
    stderr: str = "",
    exit_code: int = 0,
    timed_out: bool = False,
    forced_termination: bool = False,
    stdout_line_times_ms: tuple[int, ...] = (),
) -> object:
    return delegate.ProcessResult(
        stdout=successful_jsonl() if stdout is None else stdout,
        stderr=stderr,
        exit_code=exit_code,
        timed_out=timed_out,
        forced_termination=forced_termination,
        stdout_line_times_ms=stdout_line_times_ms,
    )


class RecordingRunner:
    def __init__(self, result: object, mutate=None, *, version: str = "1.15.12") -> None:
        self.result = result
        self.mutate = mutate
        self.invocation = None
        self.version_invocation = None
        self.invocations = []
        self.workspace_files = []
        self.version_result = process_result(stdout=version + "\n")

    def __call__(self, invocation):
        self.invocations.append(invocation)
        if invocation.command[-1:] == ["--version"]:
            self.version_invocation = invocation
            return self.version_result
        self.invocation = invocation
        self.workspace_files = sorted(
            path.relative_to(invocation.workspace).as_posix()
            for path in invocation.workspace.rglob("*")
            if path.is_file()
        )
        if self.mutate is not None:
            self.mutate(invocation)
        return self.result


class OxAlphaDelegateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory(prefix="ox-alpha-test-")
        self.root = Path(self.temp_dir.name)
        (self.root / "sample.py").write_text("one\ntwo\nthree\n", encoding="utf-8")
        (self.root / "ignored.py").write_text("do not copy\n", encoding="utf-8")
        self.request = {
            "prompt": "Find defect candidates and give counterexamples.",
            "workingDirectory": str(self.root),
            "allowlistedPaths": ["sample.py"],
            "timeoutSeconds": 30,
            "requestId": "test-request-001",
        }

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def analyze(self, runner: RecordingRunner, request=None):
        diagnostics = []
        output = delegate.analyze_request(
            self.request if request is None else request,
            process_runner=runner,
            opencode_executable=Path(sys.executable),
            diagnostic_sink=diagnostics.append,
        )
        self.assertEqual(1, len(diagnostics))
        self.assertEqual(EXPECTED_LOG_KEYS, set(diagnostics[0]))
        return output, diagnostics[0]

    def test_success_uses_fixed_model_pure_mode_stdin_and_isolated_profile(self) -> None:
        runner = RecordingRunner(process_result(stderr="bounded diagnostic"))

        output, diagnostic = self.analyze(runner)

        self.assertEqual(EXPECTED_OUTPUT_KEYS, set(output))
        self.assertEqual("ok", output["status"])
        self.assertEqual(delegate.MODEL, output["model"])
        self.assertEqual("1.15.12", output["cliVersion"])
        self.assertFalse(output["fallbackUsed"])
        self.assertFalse(output["outputContractExceeded"])
        self.assertEqual("ok", output["terminationReason"])
        self.assertEqual(3, len(output["evidence"]))
        self.assertEqual("[REDACTED_CHILD_STDERR]", output["stderrTail"])
        self.assertEqual([], output["changedFiles"])
        self.assertFalse(diagnostic["fallback_used"])
        self.assertEqual("ok", diagnostic["final_reason"])
        self.assertEqual("1.15.12", diagnostic["cli_version"])
        self.assertEqual("ok", diagnostic["termination_reason"])
        self.assertFalse(diagnostic["output_contract_exceeded"])
        self.assertEqual(4, diagnostic["parsed_event_count"])
        self.assertEqual(2, len(runner.invocations))
        self.assertEqual(["--version"], runner.version_invocation.command[-1:])

        invocation = runner.invocation
        self.assertIsNotNone(invocation)
        self.assertEqual(["sample.py"], runner.workspace_files)
        self.assertEqual(invocation.workspace, invocation.cwd)
        self.assertEqual("--pure", invocation.command[1])
        self.assertEqual("run", invocation.command[2])
        self.assertIn(delegate.MODEL, invocation.command)
        self.assertIn("codex-ox-alpha-probe", invocation.command)
        self.assertNotIn("--auto", invocation.command)
        self.assertNotIn("--share", invocation.command)
        self.assertNotIn(self.request["prompt"], invocation.command)
        self.assertIn(self.request["prompt"], invocation.stdin_text)
        self.assertIn(
            "Stop exploring after you find three important, verifiable defects",
            invocation.stdin_text,
        )

        config = json.loads(invocation.environment["OPENCODE_CONFIG_CONTENT"])
        self.assertEqual("disabled", config["share"])
        self.assertEqual("deny", config["permission"]["*"])
        self.assertEqual("allow", config["permission"]["read"])
        self.assertEqual("allow", config["permission"]["glob"])
        self.assertEqual("allow", config["permission"]["lsp"])
        self.assertEqual("deny", config["permission"]["external_directory"])
        expected_permission = {
            "*": "deny",
            "read": "allow",
            "glob": "allow",
            "lsp": "allow",
            "external_directory": "deny",
        }
        self.assertEqual(
            expected_permission,
            json.loads(invocation.environment["OPENCODE_PERMISSION"]),
        )
        self.assertEqual("1", invocation.environment["OPENCODE_DISABLE_PROJECT_CONFIG"])
        self.assertEqual("1", invocation.environment["OPENCODE_DISABLE_DEFAULT_PLUGINS"])
        self.assertEqual("1", invocation.environment["OPENCODE_DISABLE_LSP_DOWNLOAD"])
        self.assertEqual("1", invocation.environment["OPENCODE_DISABLE_CLAUDE_CODE"])
        self.assertNotIn("OPENCODE_AUTO_SHARE", invocation.environment)
        sandbox = invocation.workspace.parent
        for name in (
            "XDG_DATA_HOME",
            "XDG_CACHE_HOME",
            "XDG_CONFIG_HOME",
            "XDG_STATE_HOME",
            "TEMP",
            "TMP",
            "OPENCODE_DB",
        ):
            self.assertIn(name, invocation.environment)
            self.assertTrue(
                Path(invocation.environment[name]).resolve().is_relative_to(sandbox.resolve())
            )

        self.assertFalse(sandbox.exists(), "temporary sandbox must be removed after analysis")
        self.assertEqual("one\ntwo\nthree\n", (self.root / "sample.py").read_text(encoding="utf-8"))

    def test_hardening_environment_is_child_only_and_version_check_receives_no_key(self) -> None:
        names = (
            "OPENCODE_PERMISSION",
            "OPENCODE_DISABLE_PROJECT_CONFIG",
            "OPENCODE_DISABLE_DEFAULT_PLUGINS",
            "OPENCODE_DISABLE_LSP_DOWNLOAD",
            "OPENCODE_DISABLE_CLAUDE_CODE",
        )
        parent_values = {name: "parent-sentinel" for name in names}
        parent_values["OPENCODE_API_KEY"] = "unit-test-key-abcdefghijklmnop"
        runner = RecordingRunner(process_result())

        with mock.patch.dict(os.environ, parent_values, clear=False):
            output, _ = self.analyze(runner)
            self.assertEqual("ok", output["status"])
            self.assertEqual(parent_values, {name: os.environ[name] for name in parent_values})

        self.assertNotIn("OPENCODE_API_KEY", runner.version_invocation.environment)
        self.assertEqual(
            "unit-test-key-abcdefghijklmnop",
            runner.invocation.environment["OPENCODE_API_KEY"],
        )

    def test_exact_cli_version_passes_and_other_version_never_starts_model(self) -> None:
        passing = RecordingRunner(process_result(), version="1.15.12")
        output, _ = self.analyze(passing)
        self.assertEqual("ok", output["status"])
        self.assertEqual(2, len(passing.invocations))

        rejected = RecordingRunner(process_result(), version="1.15.13")
        output, diagnostic = self.analyze(rejected)
        self.assertEqual("unsupported_cli_version", output["status"])
        self.assertEqual("1.15.13", output["cliVersion"])
        self.assertTrue(output["fallbackUsed"])
        self.assertEqual(0, output["exitCode"])
        self.assertEqual("unsupported_cli_version", output["terminationReason"])
        self.assertEqual("1.15.13", diagnostic["cli_version"])
        self.assertTrue(diagnostic["fallback_used"])
        self.assertEqual(1, len(rejected.invocations))
        self.assertIsNone(rejected.invocation, "model command must not start on version mismatch")

    def test_resolver_ignores_repository_local_opencode_executable(self) -> None:
        fake = self.root / "opencode.exe"
        fake.write_bytes(b"not-a-trusted-global-install")
        isolated_appdata = self.root / "appdata"
        isolated_appdata.mkdir()

        with mock.patch.object(delegate.shutil, "which", return_value=str(fake)), mock.patch.dict(
            os.environ,
            {"APPDATA": str(isolated_appdata)},
            clear=False,
        ):
            resolved = delegate._resolve_opencode_executable()

        self.assertIsNone(resolved)

    def test_secret_shaped_request_id_is_rejected_and_never_logged(self) -> None:
        leaked_key = "sk-"" ""unit-test-credential-abcdefghijklmnop"
        request = dict(self.request, requestId=leaked_key)
        runner = RecordingRunner(process_result())

        with mock.patch.dict(os.environ, {"OPENCODE_API_KEY": leaked_key}, clear=False):
            output, diagnostic = self.analyze(runner, request)

        serialized = json.dumps(output) + json.dumps(diagnostic)
        self.assertEqual("input_rejected", output["status"])
        self.assertEqual("sensitive_request_id", diagnostic["final_reason"])
        self.assertNotIn(leaked_key, serialized)
        self.assertEqual([], runner.invocations)

    def test_all_valid_request_ids_are_hashed_before_diagnostics(self) -> None:
        candidates = (
            "ghp_"" ""abcdefghijklmnopqrstuvwxyz0123456789",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature",
        )
        for candidate in candidates:
            with self.subTest(candidate_kind=candidate.split("_")[0]):
                request = dict(self.request, requestId=candidate)
                output, diagnostic = self.analyze(RecordingRunner(process_result()), request)
                serialized = json.dumps(output) + json.dumps(diagnostic)
                self.assertEqual("ok", output["status"])
                self.assertNotIn(candidate, serialized)
                self.assertRegex(diagnostic["request_id"], r"^req-[0-9a-f]{16}$")

    def test_cli_version_mismatch_returns_zero_without_model_call(self) -> None:
        runner = RecordingRunner(process_result(), version="2.0.0")
        stdin = io.StringIO(json.dumps(self.request))
        stdout = io.StringIO()
        stderr = io.StringIO()

        def analyze_with_mismatch(request, **kwargs):
            return delegate.analyze_request(
                request,
                process_runner=runner,
                opencode_executable=Path(sys.executable),
                **kwargs,
            )

        exit_code = delegate.main(
            stdin=stdin,
            stdout=stdout,
            stderr=stderr,
            analyze_fn=analyze_with_mismatch,
        )

        self.assertEqual(0, exit_code)
        self.assertEqual("unsupported_cli_version", json.loads(stdout.getvalue())["status"])
        self.assertEqual(1, len(runner.invocations))
        self.assertIsNone(runner.invocation)

    def test_executable_identity_change_after_version_never_starts_model(self) -> None:
        runner = RecordingRunner(process_result())
        with mock.patch.object(
            delegate,
            "_executable_identity",
            side_effect=("before-sha256", "after-sha256"),
        ):
            output, diagnostic = self.analyze(runner)

        self.assertEqual("unsupported_cli_version", output["status"])
        self.assertEqual("executable_identity_changed", diagnostic["final_reason"])
        self.assertTrue(output["fallbackUsed"])
        self.assertEqual(1, len(runner.invocations))
        self.assertIsNone(runner.invocation)

    def test_contract_accepts_exact_maxima_and_records_event_timings(self) -> None:
        text300 = "x" * 300
        payload = {
            "summary": "s" * 500,
            "findings": [{"description": text300} for _ in range(3)],
            "evidence": [
                {"file": "sample.py", "line": ((index % 3) + 1), "description": text300}
                for index in range(6)
            ],
            "proposedTests": [text300 for _ in range(4)],
        }
        # Six distinct evidence locations are required; use a six-line allowlisted file.
        (self.root / "sample.py").write_text("1\n2\n3\n4\n5\n6\n", encoding="utf-8")
        payload["evidence"] = [
            {"file": "sample.py", "line": index, "description": text300}
            for index in range(1, 7)
        ]
        stdout = "\n".join(
            json.dumps(event)
            for event in (
                {"type": "step_start", "part": {}},
                {"type": "tool_use", "part": {"tool": "read", "state": {"status": "completed"}}},
                {"type": "text", "part": {"text": json.dumps(payload)}},
            )
        ) + "\n"
        runner = RecordingRunner(
            process_result(stdout=stdout, stdout_line_times_ms=(7, 11, 19))
        )

        output, diagnostic = self.analyze(runner)

        self.assertEqual("ok", output["status"])
        self.assertEqual(3, len(output["findings"]))
        self.assertEqual(6, len(output["evidence"]))
        self.assertEqual(4, len(output["proposedTests"]))
        self.assertEqual(7, output["firstEventMs"])
        self.assertEqual(11, output["firstToolEventMs"])
        self.assertEqual(11, output["lastToolEventMs"])
        self.assertEqual(19, output["firstTextEventMs"])
        self.assertEqual(len(json.dumps(payload).encode("utf-8")), output["modelTextBytes"])
        self.assertEqual(output["modelTextBytes"], diagnostic["model_text_bytes"])

    def test_contract_excess_is_discarded_without_truncation(self) -> None:
        base = {
            "summary": "valid",
            "findings": [{"description": "f"}],
            "evidence": [
                {"file": "sample.py", "line": 1, "description": "e"},
                {"file": "sample.py", "line": 2, "description": "e"},
                {"file": "sample.py", "line": 3, "description": "e"},
            ],
            "proposedTests": ["t"],
        }
        cases = {
            "summary": dict(base, summary="s" * 501),
            "findings": dict(base, findings=[{"description": "f"}] * 4),
            "evidence": dict(base, evidence=base["evidence"] * 3),
            "tests": dict(base, proposedTests=["t"] * 5),
            "description": dict(base, findings=[{"description": "d" * 301}]),
        }
        for name, payload in cases.items():
            with self.subTest(name=name):
                stdout = json.dumps(
                    {"type": "text", "part": {"text": json.dumps(payload)}}
                ) + "\n"
                output, diagnostic = self.analyze(
                    RecordingRunner(process_result(stdout=stdout))
                )
                self.assertEqual("contract_exceeded", output["status"])
                self.assertTrue(output["fallbackUsed"])
                self.assertTrue(output["outputContractExceeded"])
                self.assertEqual([], output["findings"])
                self.assertEqual([], output["evidence"])
                self.assertEqual([], output["proposedTests"])
                self.assertEqual("contract_exceeded", diagnostic["termination_reason"])

    def test_model_text_over_8192_utf8_bytes_is_discarded(self) -> None:
        oversized = "한" * 2731  # 8193 UTF-8 bytes.
        stdout = json.dumps({"type": "text", "part": {"text": oversized}}) + "\n"
        output, diagnostic = self.analyze(
            RecordingRunner(process_result(stdout=stdout))
        )
        self.assertEqual("contract_exceeded", output["status"])
        self.assertEqual(8193, output["modelTextBytes"])
        self.assertTrue(output["outputContractExceeded"])
        self.assertEqual("model_text_bytes_exceeded", diagnostic["final_reason"])

    def test_incomplete_model_json_is_never_recovered_as_success(self) -> None:
        incomplete = '{"summary":"x","findings":[],"evidence":['
        stdout = json.dumps({"type": "text", "part": {"text": incomplete}}) + "\n"
        output, diagnostic = self.analyze(
            RecordingRunner(process_result(stdout=stdout))
        )
        self.assertEqual("malformed_response", output["status"])
        self.assertTrue(output["fallbackUsed"])
        self.assertEqual("model_json_parse_failed", diagnostic["final_reason"])

    def test_structurally_invalid_items_never_return_success(self) -> None:
        valid = {
            "summary": "valid",
            "findings": [{"description": "candidate"}],
            "evidence": [
                {"file": "sample.py", "line": 1, "description": "one"},
                {"file": "sample.py", "line": 2, "description": "two"},
                {"file": "sample.py", "line": 3, "description": "three"},
            ],
            "proposedTests": ["test the candidate"],
        }
        cases = {
            "null finding": dict(valid, findings=[None]),
            "blank finding": dict(valid, findings=[{}]),
            "mapping test": dict(valid, proposedTests=[{}]),
            "blank test": dict(valid, proposedTests=["   "]),
            "fractional line": dict(
                valid,
                evidence=[
                    {"file": "sample.py", "line": 1.9, "description": "one"},
                    *valid["evidence"][1:],
                ],
            ),
        }
        for name, payload in cases.items():
            with self.subTest(name=name):
                stdout = json.dumps(
                    {"type": "text", "part": {"text": json.dumps(payload)}}
                ) + "\n"
                output, _ = self.analyze(RecordingRunner(process_result(stdout=stdout)))
                self.assertEqual("malformed_response", output["status"])

    def test_nonfinite_number_and_lone_surrogate_never_return_success(self) -> None:
        nonfinite = (
            '{"summary":"valid","findings":[{"description":"candidate","score":1e999}],'
            '"evidence":[{"file":"sample.py","line":1},{"file":"sample.py","line":2},'
            '{"file":"sample.py","line":3}],"proposedTests":["test"]}'
        )
        stdout = json.dumps({"type": "text", "part": {"text": nonfinite}}) + "\n"
        output, _ = self.analyze(RecordingRunner(process_result(stdout=stdout)))
        self.assertEqual("malformed_response", output["status"])

        payload = {
            "summary": "\ud800",
            "findings": [{"description": "candidate"}],
            "evidence": [
                {"file": "sample.py", "line": 1},
                {"file": "sample.py", "line": 2},
                {"file": "sample.py", "line": 3},
            ],
            "proposedTests": ["test"],
        }
        model_stdout = json.dumps(
            {"type": "text", "part": {"text": json.dumps(payload)}}
        ) + "\n"
        runner = RecordingRunner(process_result(stdout=model_stdout))
        raw_stdout = io.BytesIO()
        strict_stdout = io.TextIOWrapper(raw_stdout, encoding="utf-8", errors="strict")

        def analyze_surrogate(request, **kwargs):
            return delegate.analyze_request(
                request,
                process_runner=runner,
                opencode_executable=Path(sys.executable),
                **kwargs,
            )

        exit_code = delegate.main(
            stdin=io.StringIO(json.dumps(self.request)),
            stdout=strict_stdout,
            stderr=io.StringIO(),
            analyze_fn=analyze_surrogate,
        )
        strict_stdout.flush()
        serialized_output = raw_stdout.getvalue().decode("utf-8")

        self.assertEqual(0, exit_code)
        self.assertEqual("malformed_response", json.loads(serialized_output)["status"])

    def test_lone_surrogate_in_nested_object_key_never_returns_success(self) -> None:
        valid = {
            "summary": "valid",
            "findings": [{"description": "candidate"}],
            "evidence": [
                {"file": "sample.py", "line": 1, "description": "one"},
                {"file": "sample.py", "line": 2, "description": "two"},
                {"file": "sample.py", "line": 3, "description": "three"},
            ],
            "proposedTests": ["test the candidate"],
        }
        cases = (
            dict(valid, findings=[{"description": "candidate", "\ud800": "value"}]),
            dict(
                valid,
                evidence=[
                    {"file": "sample.py", "line": 1, "\ud800": "value"},
                    *valid["evidence"][1:],
                ],
            ),
        )
        for payload in cases:
            with self.subTest(section="findings" if payload["findings"] != valid["findings"] else "evidence"):
                stdout = json.dumps(
                    {"type": "text", "part": {"text": json.dumps(payload)}}
                ) + "\n"
                output, _ = self.analyze(RecordingRunner(process_result(stdout=stdout)))
                self.assertEqual("malformed_response", output["status"])

    def test_directory_allowlist_excludes_sensitive_names(self) -> None:
        source = self.root / "source"
        source.mkdir()
        (source / "safe.py").write_text("a\nb\nc\n", encoding="utf-8")
        (source / ".env.production").write_text("PASSWORD=do-not-copy\n", encoding="utf-8")
        (source / ".envrc").write_text("PASSWORD=do-not-copy\n", encoding="utf-8")
        (source / "apikey.txt").write_text("do-not-copy\n", encoding="utf-8")
        (source / "client-token.json").write_text("{}\n", encoding="utf-8")
        (source / "opnessl.properties").write_text("do-not-copy\n", encoding="utf-8")
        (source / "opencode.json").write_text(
            '{"permission":{"bash":"allow"}}\n', encoding="utf-8"
        )
        request = dict(self.request, allowlistedPaths=["source"])

        def assert_copy(invocation) -> None:
            copied = sorted(
                path.relative_to(invocation.workspace).as_posix()
                for path in invocation.workspace.rglob("*")
                if path.is_file()
            )
            self.assertEqual(["source/safe.py"], copied)

        payload = {
            "summary": "safe directory result",
            "findings": [{"description": "candidate"}],
            "evidence": [
                {"file": "source/safe.py", "line": 1},
                {"file": "source/safe.py", "line": 2},
                {"file": "source/safe.py", "line": 3},
            ],
            "proposedTests": ["verify the candidate"],
        }
        stdout = json.dumps(
            {"type": "text", "part": {"text": json.dumps(payload)}}
        ) + "\n"
        runner = RecordingRunner(process_result(stdout=stdout), mutate=assert_copy)
        output, _ = self.analyze(runner, request)
        self.assertEqual("ok", output["status"])

    def test_explicit_sensitive_path_is_rejected_without_starting_child(self) -> None:
        (self.root / ".env.local").write_text("VALUE=hidden\n", encoding="utf-8")
        request = dict(self.request, allowlistedPaths=[".env.local"])
        runner = RecordingRunner(process_result())

        output, diagnostic = self.analyze(runner, request)

        self.assertEqual("input_rejected", output["status"])
        self.assertIsNone(runner.invocation)
        self.assertTrue(diagnostic["fallback_used"])
        self.assertEqual("forbidden_allowlisted_path", diagnostic["final_reason"])

    def test_timeout_and_forced_termination_are_distinguished(self) -> None:
        runner = RecordingRunner(
            process_result(
                stdout="",
                exit_code=-9,
                timed_out=True,
                forced_termination=True,
            )
        )

        output, diagnostic = self.analyze(runner)

        self.assertEqual("timeout", output["status"])
        self.assertEqual(-9, output["exitCode"])
        self.assertEqual("timeout_forced_termination", diagnostic["final_reason"])
        self.assertTrue(diagnostic["timeout"])
        self.assertTrue(diagnostic["fallback_used"])

    def test_cli_timeout_remains_fail_open_exit_zero(self) -> None:
        runner = RecordingRunner(
            process_result(stdout="", exit_code=-9, timed_out=True, forced_termination=True)
        )
        stdin = io.StringIO(json.dumps(self.request))
        stdout = io.StringIO()
        stderr = io.StringIO()

        def analyze_timeout(request, **kwargs):
            return delegate.analyze_request(
                request,
                process_runner=runner,
                opencode_executable=Path(sys.executable),
                **kwargs,
            )

        exit_code = delegate.main(
            stdin=stdin,
            stdout=stdout,
            stderr=stderr,
            analyze_fn=analyze_timeout,
        )

        self.assertEqual(0, exit_code)
        self.assertEqual("timeout", json.loads(stdout.getvalue())["status"])
        self.assertEqual(2, len(runner.invocations))

    def test_rate_limit_is_not_reported_as_generic_process_error(self) -> None:
        runner = RecordingRunner(
            process_result(stdout="", stderr="HTTP 429: rate limit exceeded", exit_code=1)
        )

        output, diagnostic = self.analyze(runner)

        self.assertEqual("rate_limit", output["status"])
        self.assertEqual("rate_limit", diagnostic["final_reason"])

    def test_successful_analysis_may_discuss_429_without_becoming_rate_limited(self) -> None:
        payload = {
            "summary": "HTTP 429 handling is a defect candidate, not this call's status.",
            "findings": [{"description": "candidate"}],
            "evidence": [
                {"file": "sample.py", "line": 1},
                {"file": "sample.py", "line": 2},
                {"file": "sample.py", "line": 3},
            ],
            "proposedTests": ["exercise HTTP 429 handling"],
        }
        stdout = json.dumps(
            {"type": "text", "part": {"text": json.dumps(payload)}}
        ) + "\n"
        runner = RecordingRunner(process_result(stdout=stdout))

        output, diagnostic = self.analyze(runner)

        self.assertEqual("ok", output["status"])
        self.assertEqual("ok", diagnostic["final_reason"])

    def test_nonzero_exit_is_process_error(self) -> None:
        runner = RecordingRunner(process_result(stdout="", stderr="provider unavailable", exit_code=7))
        output, diagnostic = self.analyze(runner)
        self.assertEqual("process_error", output["status"])
        self.assertEqual("nonzero_exit", diagnostic["final_reason"])

    def test_malformed_jsonl_is_distinct_from_empty_and_bad_model_json(self) -> None:
        cases = [
            ("not-json\n", "malformed_jsonl", "jsonl_parse_failed"),
            ("", "empty_response", "empty_response"),
            (
                json.dumps({"type": "text", "part": {"text": "not-json"}}) + "\n",
                "malformed_response",
                "model_json_parse_failed",
            ),
        ]
        for stdout, expected_status, expected_reason in cases:
            with self.subTest(expected_status=expected_status):
                runner = RecordingRunner(process_result(stdout=stdout))
                output, diagnostic = self.analyze(runner)
                self.assertEqual(expected_status, output["status"])
                self.assertEqual(expected_reason, diagnostic["final_reason"])

    def test_workspace_mutation_fails_closed_inside_delegate_but_cli_remains_fail_open(self) -> None:
        def mutate(invocation) -> None:
            (invocation.workspace / "sample.py").write_text("changed\n", encoding="utf-8")

        runner = RecordingRunner(process_result(), mutate=mutate)
        output, diagnostic = self.analyze(runner)

        self.assertEqual("workspace_changed", output["status"])
        self.assertEqual(["sample.py"], output["changedFiles"])
        self.assertEqual([], output["findings"])
        self.assertEqual(1, diagnostic["changed_files"])
        self.assertTrue(diagnostic["fallback_used"])
        self.assertEqual("one\ntwo\nthree\n", (self.root / "sample.py").read_text(encoding="utf-8"))

    def test_disallowed_completed_tool_causes_permission_failure(self) -> None:
        events = [
            {
                "type": "tool_use",
                "part": {"tool": "bash", "state": {"status": "completed"}},
            },
            {"type": "text", "part": {"text": json.dumps({
                "summary": "x",
                "findings": [],
                "evidence": [
                    {"file": "sample.py", "line": 1},
                    {"file": "sample.py", "line": 2},
                    {"file": "sample.py", "line": 3},
                ],
                "proposedTests": [],
            })}},
        ]
        stdout = "\n".join(json.dumps(event) for event in events) + "\n"
        runner = RecordingRunner(process_result(stdout=stdout))
        output, diagnostic = self.analyze(runner)
        self.assertEqual("permission_violation", output["status"])
        self.assertEqual("disallowed_tool_completed", diagnostic["final_reason"])

    def test_secret_or_full_source_echo_is_suppressed(self) -> None:
        leaked_key = "unit-test-" + "credential-value-abcdefghijklmnop"
        payload = {
            "summary": leaked_key,
            "findings": [],
            "evidence": [
                {"file": "sample.py", "line": 1},
                {"file": "sample.py", "line": 2},
                {"file": "sample.py", "line": 3},
            ],
            "proposedTests": [],
        }
        stdout = json.dumps(
            {"type": "text", "part": {"text": json.dumps(payload)}}
        ) + "\n"
        sensitive_stderr = "Author" + "ization: " + "Bearer " + leaked_key
        runner = RecordingRunner(process_result(stdout=stdout, stderr=sensitive_stderr))

        with mock.patch.dict(os.environ, {"OPENCODE_API_KEY": leaked_key}, clear=False):
            output, diagnostic = self.analyze(runner)

        serialized = json.dumps(output) + json.dumps(diagnostic)
        self.assertEqual("sensitive_output", output["status"])
        self.assertNotIn(leaked_key, serialized)
        self.assertEqual("[REDACTED_CHILD_STDERR]", output["stderrTail"])
        self.assertGreaterEqual(diagnostic["redacted_secret_count"], 1)

    def test_prompt_echo_on_stderr_is_suppressed(self) -> None:
        class EchoRunner(RecordingRunner):
            def __call__(self, invocation):
                if invocation.command[-1:] == ["--version"]:
                    return super().__call__(invocation)
                self.invocations.append(invocation)
                self.invocation = invocation
                self.workspace_files = ["sample.py"]
                return process_result(stderr=invocation.stdin_text)

        output, diagnostic = self.analyze(EchoRunner(process_result()))

        self.assertEqual("sensitive_output", output["status"])
        self.assertEqual("[REDACTED_SENSITIVE_OUTPUT]", output["stderrTail"])
        self.assertEqual("sensitive_output_detected", diagnostic["final_reason"])

    def test_raw_request_prompt_fragment_is_never_returned_in_stderr_tail(self) -> None:
        class PromptOnlyEchoRunner(RecordingRunner):
            def __call__(self, invocation):
                if invocation.command[-1:] == ["--version"]:
                    return super().__call__(invocation)
                self.invocations.append(invocation)
                self.invocation = invocation
                return process_result(stderr=self_prompt)

        self_prompt = self.request["prompt"]
        output, _ = self.analyze(PromptOnlyEchoRunner(process_result()))

        self.assertEqual("sensitive_output", output["status"])
        self.assertNotIn(self_prompt, output["stderrTail"])

    def test_fewer_than_three_valid_file_line_entries_fails_open(self) -> None:
        payload = {
            "summary": "insufficient",
            "findings": [{"description": "candidate"}],
            "evidence": [
                {"file": "sample.py", "line": 1},
                {"file": "ignored.py", "line": 1},
                {"file": "sample.py", "line": 999},
            ],
            "proposedTests": ["verify the remaining evidence"],
        }
        stdout = json.dumps(
            {"type": "text", "part": {"text": json.dumps(payload)}}
        ) + "\n"
        runner = RecordingRunner(process_result(stdout=stdout))
        output, diagnostic = self.analyze(runner)
        self.assertEqual("insufficient_evidence", output["status"])
        self.assertEqual("fewer_than_three_valid_evidence_entries", diagnostic["final_reason"])

    def test_blank_summary_is_malformed_response(self) -> None:
        payload = {
            "summary": "   ",
            "findings": [],
            "evidence": [
                {"file": "sample.py", "line": 1},
                {"file": "sample.py", "line": 2},
                {"file": "sample.py", "line": 3},
            ],
            "proposedTests": [],
        }
        stdout = json.dumps(
            {"type": "text", "part": {"text": json.dumps(payload)}}
        ) + "\n"
        output, diagnostic = self.analyze(RecordingRunner(process_result(stdout=stdout)))
        self.assertEqual("malformed_response", output["status"])
        self.assertEqual("model_json_parse_failed", diagnostic["final_reason"])

    def test_cli_emits_one_output_json_and_one_redacted_log_and_returns_zero_on_failure(self) -> None:
        stdin = io.StringIO(json.dumps(self.request))
        stdout = io.StringIO()
        stderr = io.StringIO()

        def failed_analysis(request, **_kwargs):
            delegate.emit_diagnostic(
                {
                    "request_id": request["requestId"],
                    "model": delegate.MODEL,
                    "started_at": "2026-08-23T00:00:00+00:00",
                    "elapsed_ms": 1,
                    "exit_code": None,
                    "parsed_event_count": 0,
                    "stdout_bytes": 0,
                    "stderr_bytes": 0,
                    "timeout": False,
                    "changed_files": 0,
                    "redacted_secret_count": 0,
                    "fallback_used": True,
                    "final_reason": "cli_unavailable",
                },
                stream=stderr,
            )
            return delegate.failure_output("cli_unavailable", "OpenCode analyzer unavailable.", 1)

        exit_code = delegate.main(
            stdin=stdin,
            stdout=stdout,
            stderr=stderr,
            analyze_fn=failed_analysis,
        )

        self.assertEqual(0, exit_code, "analyzer failures must not stop existing Codex work")
        output_lines = [line for line in stdout.getvalue().splitlines() if line]
        log_lines = [line for line in stderr.getvalue().splitlines() if line]
        self.assertEqual(1, len(output_lines))
        self.assertEqual(1, len(log_lines))
        self.assertEqual("cli_unavailable", json.loads(output_lines[0])["status"])
        self.assertEqual("cli_unavailable", json.loads(log_lines[0])["final_reason"])
        self.assertNotIn(self.request["prompt"], stderr.getvalue())

    def test_process_runner_captures_output_without_a_shell(self) -> None:
        invocation = delegate.Invocation(
            command=[sys.executable, "-c", "print('jsonl-output')"],
            cwd=self.root,
            workspace=self.root,
            environment=dict(os.environ),
            stdin_text="not logged",
            timeout_seconds=5,
        )

        result = delegate.run_opencode(invocation)

        self.assertEqual(0, result.exit_code)
        self.assertEqual("jsonl-output", result.stdout.strip())
        self.assertFalse(result.timed_out)

    def test_process_runner_records_real_jsonl_event_arrival_order(self) -> None:
        child = (
            "import json,time; "
            "print(json.dumps({'type':'step_start','part':{}}),flush=True); "
            "time.sleep(.05); "
            "print(json.dumps({'type':'tool_use','part':{'tool':'read','state':{'status':'completed'}}}),flush=True); "
            "time.sleep(.05); "
            "print(json.dumps({'type':'text','part':{'text':'{}'}}),flush=True)"
        )
        invocation = delegate.Invocation(
            command=[sys.executable, "-c", child],
            cwd=self.root,
            workspace=self.root,
            environment=dict(os.environ),
            stdin_text="",
            timeout_seconds=5,
        )

        result = delegate.run_opencode(invocation)

        self.assertEqual(3, len(result.stdout_line_times_ms))
        self.assertLess(result.stdout_line_times_ms[0], result.stdout_line_times_ms[1])
        self.assertLess(result.stdout_line_times_ms[1], result.stdout_line_times_ms[2])

    def test_process_runner_bounds_single_line_stdout_and_stderr(self) -> None:
        child = "import sys; sys.stdout.write('x'*4096); sys.stderr.write('y'*4096)"
        invocation = delegate.Invocation(
            command=[sys.executable, "-c", child],
            cwd=self.root,
            workspace=self.root,
            environment=dict(os.environ),
            stdin_text="",
            timeout_seconds=5,
        )

        with mock.patch.object(delegate, "MAX_STDOUT_BYTES", 1024), mock.patch.object(
            delegate, "MAX_STDERR_BYTES", 1024
        ):
            result = delegate.run_opencode(invocation)

        self.assertIn(result.output_limit_exceeded, {"stdout", "stderr"})
        self.assertLessEqual(len(result.stdout.encode("utf-8")), 1024)
        self.assertLessEqual(len(result.stderr.encode("utf-8")), 1024)

    def test_timeout_deadline_includes_blocking_stdin_write(self) -> None:
        invocation = delegate.Invocation(
            command=[sys.executable, "-c", "import time; time.sleep(3)"],
            cwd=self.root,
            workspace=self.root,
            environment=dict(os.environ),
            stdin_text="x" * (1024 * 1024),
            timeout_seconds=1,
        )
        started = time.monotonic()

        result = delegate.run_opencode(invocation)

        self.assertTrue(result.timed_out)
        self.assertLess(time.monotonic() - started, 2.5)

    def test_process_runner_force_terminates_timeout(self) -> None:
        invocation = delegate.Invocation(
            command=[sys.executable, "-c", "import time; time.sleep(60)"],
            cwd=self.root,
            workspace=self.root,
            environment=dict(os.environ),
            stdin_text="",
            timeout_seconds=1,
        )
        started = time.monotonic()

        result = delegate.run_opencode(invocation)

        self.assertTrue(result.timed_out)
        self.assertTrue(result.forced_termination)
        self.assertLess(time.monotonic() - started, 15)

    def test_timeout_terminates_process_tree_before_child_can_write(self) -> None:
        started_marker = self.root / "child-started.txt"
        marker = self.root / "child-survived.txt"
        child_code = (
            "import pathlib,time; "
            f"pathlib.Path({str(started_marker)!r}).write_text('started', encoding='utf-8'); "
            "time.sleep(3); "
            f"pathlib.Path({str(marker)!r}).write_text('survived', encoding='utf-8')"
        )
        parent_code = (
            "import subprocess,sys,time; "
            f"subprocess.Popen([sys.executable,'-c',{child_code!r}]); "
            "time.sleep(60)"
        )
        invocation = delegate.Invocation(
            command=[sys.executable, "-c", parent_code],
            cwd=self.root,
            workspace=self.root,
            environment=dict(os.environ),
            stdin_text="",
            timeout_seconds=1,
        )

        result = delegate.run_opencode(invocation)
        time.sleep(3)

        self.assertTrue(result.timed_out)
        self.assertTrue(result.forced_termination)
        self.assertTrue(started_marker.exists(), "descendant must start before kill is evaluated")
        self.assertFalse(marker.exists(), "timed-out descendants must be terminated")

    @unittest.skipUnless(os.name == "nt", "taskkill return-code contract is Windows-specific")
    def test_failed_taskkill_is_not_reported_as_tree_termination(self) -> None:
        process = mock.Mock(pid=12345)
        completed = subprocess.CompletedProcess(["taskkill.exe"], 1)
        with mock.patch.object(delegate.subprocess, "run", return_value=completed):
            terminated = delegate._terminate_process_tree(process)

        self.assertFalse(terminated)
        process.kill.assert_called_once()

    @unittest.skipUnless(os.name == "nt", "Windows Job Object contract")
    def test_job_object_kills_descendant_when_taskkill_is_unavailable(self) -> None:
        started_marker = self.root / "job-child-started.txt"
        survived_marker = self.root / "job-child-survived.txt"
        child_code = (
            "import pathlib,time; "
            f"pathlib.Path({str(started_marker)!r}).write_text('started', encoding='utf-8'); "
            "time.sleep(3); "
            f"pathlib.Path({str(survived_marker)!r}).write_text('survived', encoding='utf-8')"
        )
        parent_code = (
            "import subprocess,sys,time; "
            f"subprocess.Popen([sys.executable,'-c',{child_code!r}]); "
            "time.sleep(60)"
        )
        invocation = delegate.Invocation(
            command=[sys.executable, "-c", parent_code],
            cwd=self.root,
            workspace=self.root,
            environment=dict(os.environ),
            stdin_text="",
            timeout_seconds=1,
        )
        failed_taskkill = subprocess.CompletedProcess(["taskkill.exe"], 1)

        with mock.patch.object(delegate.subprocess, "run", return_value=failed_taskkill):
            result = delegate.run_opencode(invocation)
        time.sleep(3)

        self.assertTrue(result.timed_out)
        self.assertTrue(result.forced_termination)
        self.assertTrue(started_marker.exists())
        self.assertFalse(survived_marker.exists())

    @unittest.skipUnless(os.name == "nt", "Windows suspended-start contract")
    def test_windows_process_is_suspended_until_job_assignment_then_resumed(self) -> None:
        self.assertNotEqual(0, delegate._windows_creation_flags() & 0x00000004)
        invocation = delegate.Invocation(
            command=[sys.executable, "-c", "print('ok')"],
            cwd=self.root,
            workspace=self.root,
            environment=dict(os.environ),
            stdin_text="",
            timeout_seconds=5,
        )
        order = []
        real_create = delegate._create_windows_kill_job
        real_resume = delegate._resume_windows_process

        def record_create(process):
            order.append("assign")
            return real_create(process)

        def record_resume(process):
            order.append("resume")
            return real_resume(process)

        with mock.patch.object(delegate, "_create_windows_kill_job", side_effect=record_create), mock.patch.object(
            delegate, "_resume_windows_process", side_effect=record_resume
        ):
            result = delegate.run_opencode(invocation)

        self.assertEqual(0, result.exit_code)
        self.assertEqual(["assign", "resume"], order[:2])

    @unittest.skipUnless(os.name == "nt", "Windows Job Object inheritance contract")
    def test_job_object_owns_descendant_spawned_before_parent_exits(self) -> None:
        parent_spawned = self.root / "immediate-parent-spawned.txt"
        survived_marker = self.root / "immediate-child-survived.txt"
        child_code = (
            "import pathlib,time; time.sleep(2); "
            f"pathlib.Path({str(survived_marker)!r}).write_text('survived', encoding='utf-8')"
        )
        parent_code = (
            "import pathlib,subprocess,sys; "
            f"subprocess.Popen([sys.executable,'-c',{child_code!r}]); "
            f"pathlib.Path({str(parent_spawned)!r}).write_text('spawned', encoding='utf-8')"
        )
        invocation = delegate.Invocation(
            command=[sys.executable, "-c", parent_code],
            cwd=self.root,
            workspace=self.root,
            environment=dict(os.environ),
            stdin_text="",
            timeout_seconds=5,
        )

        result = delegate.run_opencode(invocation)
        time.sleep(2.5)

        self.assertEqual(0, result.exit_code)
        self.assertTrue(parent_spawned.exists())
        self.assertFalse(survived_marker.exists())

    def test_redacted_output_and_log_contain_no_key_header_certificate_or_source(self) -> None:
        long_source = "private-source-line\n" * 8
        (self.root / "long.py").write_text(long_source, encoding="utf-8")
        request = dict(self.request, allowlistedPaths=["long.py"])
        leaked_key = "unit-test-credential-abcdefghijklmnop"
        certificate = (
            "-----BEGIN CERTIFICATE-----\n"
            "MIIBUNITTESTCERTIFICATEPAYLOAD0123456789\n"
            "-----END CERTIFICATE-----"
        )
        sensitive_stderr = (
            "Authorization: Bearer " + leaked_key + "\n" + certificate + "\n" + long_source
        )
        runner = RecordingRunner(process_result(stderr=sensitive_stderr))

        with mock.patch.dict(os.environ, {"OPENCODE_API_KEY": leaked_key}, clear=False):
            output, diagnostic = self.analyze(runner, request)

        serialized = json.dumps(output, ensure_ascii=False) + json.dumps(
            diagnostic, ensure_ascii=False
        )
        self.assertEqual("sensitive_output", output["status"])
        self.assertNotIn(leaked_key, serialized)
        self.assertNotIn("Authorization", serialized)
        self.assertNotIn("BEGIN CERTIFICATE", serialized)
        self.assertNotIn(long_source, serialized)


if __name__ == "__main__":
    unittest.main()
