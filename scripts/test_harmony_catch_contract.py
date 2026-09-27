from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path

from scripts.harmony_catch_contract import (
    CatchEvidence,
    classify_java_catches,
    iter_java_catch_blocks,
    summarize_catches,
)


ROOT = Path(__file__).resolve().parents[1]


class HarmonyCatchContractTest(unittest.TestCase):
    def probe_path(self, name: str = "Probe.java") -> Path:
        return ROOT / "main" / "java" / "com" / "example" / name

    def test_toolbox_remains_directly_executable_after_contract_extraction(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "awx_mcp_toolbox.py"), "--help"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("AWX MCP-style toolbox", result.stdout)

    def test_toolbox_file_loader_works_from_an_external_cwd(self):
        loader_code = (
            "import importlib.util,sys;"
            "spec=importlib.util.spec_from_file_location('awx_external_probe',sys.argv[1]);"
            "module=importlib.util.module_from_spec(spec);"
            "spec.loader.exec_module(module)"
        )
        with tempfile.TemporaryDirectory() as external_cwd:
            result = subprocess.run(
                [
                    sys.executable,
                    "-B",
                    "-X",
                    "utf8",
                    "-c",
                    loader_code,
                    str(ROOT / "scripts" / "awx_mcp_toolbox.py"),
                ],
                cwd=external_cwd,
                capture_output=True,
                text=True,
                check=False,
            )

        self.assertEqual(0, result.returncode, result.stderr)

    def test_nested_catch_body_does_not_borrow_a_later_breadcrumb(self):
        text = """
            class Probe {
                void run() {
                    try { first(); }
                    catch (RuntimeException failure) { if (retry()) { second(); } }
                    try { third(); }
                    catch (Exception handled) { TraceStore.put("probe.reason", "failed"); }
                }
            }
        """

        rows = classify_java_catches(path=self.probe_path(), root=ROOT, text=text)

        self.assertEqual(
            ["NO_LOCAL_BREADCRUMB", "LOCAL_BREADCRUMB"],
            [row.reason_code for row in rows],
        )
        self.assertEqual(
            ["RUNTIME_EXCEPTION", "EXCEPTION"],
            [row.caught_type_class for row in rows],
        )

    def test_evidence_contains_only_relative_path_line_type_and_reason(self):
        row = classify_java_catches(
            path=self.probe_path(),
            root=ROOT,
            text=(
                "class Probe { void run(){ try{} catch(Exception secret){ "
                "work(\"https://private.invalid/value\"); } } }"
            ),
        )[0]
        serialized = json.dumps(asdict(row), sort_keys=True)

        self.assertEqual(
            {"path", "line", "caught_type_class", "reason_code"},
            set(asdict(row)),
        )
        self.assertEqual("main/java/com/example/Probe.java", row.path)
        self.assertNotIn(str(ROOT), serialized)
        self.assertNotIn("secret", serialized)
        self.assertNotIn("private.invalid", serialized)

    def test_iter_yields_header_and_balanced_nested_body(self):
        scan_text = (
            "class Probe { void run() { try {} "
            "catch (RuntimeException failure) { if (retry()) { work(); } finish(); } } }"
        )

        rows = list(iter_java_catch_blocks(scan_text))

        self.assertEqual(1, len(rows))
        catch_start, catch_header, body = rows[0]
        self.assertEqual(scan_text.index("catch"), catch_start)
        self.assertEqual("RuntimeException failure", catch_header.strip())
        self.assertIn("if (retry()) { work(); }", body)
        self.assertIn("finish();", body)

    def test_multicatch_uses_the_exact_broad_member_class(self):
        rows = classify_java_catches(
            path=self.probe_path(),
            root=ROOT,
            text=(
                "class Probe { void run(){ try{} "
                "catch(java.io.IOException | RuntimeException failure){ work(); } } }"
            ),
        )

        self.assertEqual(1, len(rows))
        self.assertEqual("RUNTIME_EXCEPTION", rows[0].caught_type_class)
        self.assertEqual("NO_LOCAL_BREADCRUMB", rows[0].reason_code)

    def test_line_number_uses_the_original_one_based_source_line(self):
        text = """

            class Probe {
                void run() { try { work(); }
                    catch (Exception failure) { work(); }
                }
            }
        """

        row = classify_java_catches(path=self.probe_path(), root=ROOT, text=text)[0]

        self.assertEqual(5, row.line)

    def test_comments_strings_chars_and_text_blocks_do_not_create_fake_catches(self):
        text = r'''
            class Probe {
                String ordinary = "catch (Exception hidden) { TraceStore.put(\"x\", \"y\"); }";
                char marker = '}';
                String block = """
                    catch (Throwable hidden) { log.warn("hidden"); }
                """;
                // catch (Exception hidden) { throw hidden; }
                /* catch (RuntimeException hidden) { TraceStore.put("x", "y"); } */
                void run() {
                    try { work(); }
                    catch (Exception actual) { work(); }
                }
            }
        '''

        rows = classify_java_catches(path=self.probe_path(), root=ROOT, text=text)

        self.assertEqual(1, len(rows))
        self.assertEqual("EXCEPTION", rows[0].caught_type_class)
        self.assertEqual("NO_LOCAL_BREADCRUMB", rows[0].reason_code)

    def test_empty_poll_timeout_contract_is_exact(self):
        accepted = classify_java_catches(
            path=self.probe_path(),
            root=ROOT,
            text=(
                "class Probe { void run(){ try{} "
                "catch(java.util.concurrent.TimeoutException pollTimeout){} } }"
            ),
        )[0]
        rejected = classify_java_catches(
            path=self.probe_path(),
            root=ROOT,
            text="class Probe { void run(){ try{} catch(TimeoutException ignored){} } }",
        )[0]

        self.assertEqual("EMPTY_POLL_TIMEOUT", accepted.reason_code)
        self.assertEqual("NO_LOCAL_BREADCRUMB", rejected.reason_code)

    def test_terminal_failure_close_contract_is_exact(self):
        accepted = classify_java_catches(
            path=self.probe_path(),
            root=ROOT,
            text=(
                "class Probe { void run(){ try{} "
                "catch(java.lang.RuntimeException terminalFailure){ close(); } } }"
            ),
        )[0]
        rejected = classify_java_catches(
            path=self.probe_path(),
            root=ROOT,
            text=(
                "class Probe { void run(){ try{} "
                "catch(RuntimeException ignored){ close(); } } }"
            ),
        )[0]

        self.assertEqual("TERMINAL_FAILURE_CLOSE", accepted.reason_code)
        self.assertEqual("NO_LOCAL_BREADCRUMB", rejected.reason_code)

    def test_direct_rethrow_is_a_local_breadcrumb(self):
        row = classify_java_catches(
            path=self.probe_path(),
            root=ROOT,
            text=(
                "class Probe { void run(){ try{} "
                "catch(RuntimeException failure){ throw failure; } } }"
            ),
        )[0]

        self.assertEqual("LOCAL_BREADCRUMB", row.reason_code)

    def test_deferred_rethrow_is_scoped_to_the_same_lexical_owner(self):
        accepted = classify_java_catches(
            path=self.probe_path(),
            root=ROOT,
            text="""
                class Probe {
                    void run() throws Exception {
                        Exception deferredFailure = null;
                        try { work(); }
                        catch (Exception caught) { deferredFailure = caught; }
                        if (deferredFailure != null) { throw deferredFailure; }
                    }
                }
            """,
        )[0]
        rejected = classify_java_catches(
            path=self.probe_path(),
            root=ROOT,
            text="""
                class Probe {
                    void silent() {
                        Exception deferredFailure = null;
                        try { work(); }
                        catch (Exception caught) { deferredFailure = caught; }
                    }
                    void elsewhere(Exception deferredFailure) throws Exception {
                        throw deferredFailure;
                    }
                }
            """,
        )[0]

        self.assertEqual("DEFERRED_RETHROW", accepted.reason_code)
        self.assertEqual("NO_LOCAL_BREADCRUMB", rejected.reason_code)

    def test_all_named_bounded_contracts_require_exact_path_method_and_body(self):
        cases = [
            ("main/java/com/abandonware/ai/agent/orchestrator/subagent/GlmActivationStateMachine.java", "safeBoolean", "RuntimeException ignored", "return false;"),
            ("main/java/com/abandonware/ai/agent/orchestrator/subagent/GlmAgentCore.java", "executeInternal", "RuntimeException ignored", ""),
            ("main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentProviderChain.java", "attemptAllowed", "RuntimeException ignored", "return false;"),
            ("main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentProviderChain.java", "record", "RuntimeException ignored", ""),
            ("main/java/com/abandonware/ai/agent/tool/impl/ops/CounterEvidenceRetrieveTool.java", "normalizedScore", "RuntimeException error", "return 0.0d;"),
            ("main/java/com/abandonware/ai/agent/tool/impl/ops/EvidenceCoherenceVerifyTool.java", "normalize", "IllegalArgumentException | DateTimeException error", "return null;"),
            ("main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java", "isValidDurableField", "NumberFormatException ignored", "return false;"),
            ("main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java", "boundedCount", "RuntimeException ignored", "count = 0L;"),
            ("main/java/com/example/lms/config/LocalLlmProcessManager.java", "terminate", "RuntimeException failure", "return new TerminationResult(status);"),
            ("main/java/com/example/lms/config/LocalLlmProcessManager.java", "rollbackTransferredProcess", "RuntimeException traceFailure", ""),
            ("main/java/com/example/lms/config/LocalLlmProcessManager.java", "shellBacked", "RuntimeException invalidPath", "executable = first;"),
            ("main/java/com/example/lms/config/LocalLlmProcessManager.java", "findListener", "Exception ignored", "return ListenerInfo.none();"),
            ("main/java/com/example/lms/config/LocalLlmProcessManager.java", "portFromUrl", "Exception ignored", "return 11435;"),
            ("main/java/com/example/lms/ensemble/ApiTriadRoutePreflight.java", "hasCredential", "RuntimeException credentialConflict", "return false;"),
            ("main/java/com/example/lms/ensemble/ApiTriadRoutePreflight.java", "strictExternalEndpointShape", "RuntimeException malformed", "return false;"),
            ("main/java/com/example/lms/ensemble/ApiTriadRoutePreflight.java", "providerPathMatches", "RuntimeException malformed", "return false;"),
            ("main/java/com/example/lms/ensemble/EnsembleJudgeService.java", "parseDebugPatchVote", "IllegalArgumentException invalidEnum", "return null;"),
            ("main/java/com/example/lms/ensemble/EvidenceGroundedTriadicDebugAdjudicator.java", "nonNegativeLong", "NumberFormatException ignored", "return 0L;"),
            ("main/java/com/example/lms/guard/ConversationFrameV1.java", "parse", "IllegalArgumentException ignored", "return OFF;"),
            ("main/java/com/example/lms/guard/ProviderCredentialResolver.java", "parseNonNegativeInt", "NumberFormatException ignored", "return 0;"),
            ("main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java", "logAcceptedRequestAttempt", "RuntimeException ignored", ""),
            ("main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java", "logDroppedRequestAttemptState", "RuntimeException ignored", ""),
            ("main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java", "logProviderReceipt", "RuntimeException ignored", ""),
            ("main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java", "endpointIdentityHash", "IllegalArgumentException invalid", "return \"unknown\";"),
            ("main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java", "requestTimelineEndpoint", "IllegalArgumentException ignored", ""),
            ("main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java", "requestAttemptMessageFingerprint", "RuntimeException ignored", "return new RequestAttemptFingerprint(\"hash:unknown\", itemCount, 0);"),
            ("main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java", "requestAttemptFingerprint", "Exception ignored", "return new RequestAttemptFingerprint(\"hash:unknown\", itemCount, 0);"),
        ]
        self.assertEqual(27, len(cases))

        for relative_path, method_name, caught, body in cases:
            text = f"""
                class Synthetic {{
                    Object {method_name}() {{
                        try {{ return work(); }}
                        catch ({caught}) {{ {body} }}
                        return null;
                    }}
                }}
            """
            with self.subTest(path=relative_path, method=method_name):
                row = classify_java_catches(
                    path=ROOT / Path(relative_path),
                    root=ROOT,
                    text=text,
                )[0]
                self.assertEqual("NAMED_BOUNDED_CONTRACT", row.reason_code)

        expected_path = ROOT / "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java"
        accepted_body = "catch (IllegalArgumentException invalid) { return \"unknown\"; }"
        wrong_path = classify_java_catches(
            path=self.probe_path("Unknown.java"),
            root=ROOT,
            text=f"class Probe {{ Object endpointIdentityHash() {{ try{{}} {accepted_body} }} }}",
        )[0]
        wrong_method = classify_java_catches(
            path=expected_path,
            root=ROOT,
            text=f"class Probe {{ Object eraseEvidence() {{ try{{}} {accepted_body} }} }}",
        )[0]

        self.assertEqual("NO_LOCAL_BREADCRUMB", wrong_path.reason_code)
        self.assertEqual("NO_LOCAL_BREADCRUMB", wrong_method.reason_code)

    def test_named_bounded_contracts_reject_wrong_empty_return_and_assignment_bodies(self):
        cases = [
            (
                "main/java/com/abandonware/ai/agent/orchestrator/subagent/GlmAgentCore.java",
                "executeInternal",
                "RuntimeException ignored",
                "work();",
            ),
            (
                "main/java/com/abandonware/ai/agent/orchestrator/subagent/GlmActivationStateMachine.java",
                "safeBoolean",
                "RuntimeException ignored",
                "return true;",
            ),
            (
                "main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java",
                "boundedCount",
                "RuntimeException ignored",
                "count = 1L;",
            ),
        ]

        for relative_path, method_name, caught, wrong_body in cases:
            text = f"""
                class Synthetic {{
                    Object {method_name}() {{
                        try {{ return work(); }}
                        catch ({caught}) {{ {wrong_body} }}
                        return null;
                    }}
                }}
            """
            with self.subTest(path=relative_path, body=wrong_body):
                row = classify_java_catches(
                    path=ROOT / Path(relative_path),
                    root=ROOT,
                    text=text,
                )[0]
                self.assertEqual("NO_LOCAL_BREADCRUMB", row.reason_code)

    def test_path_outside_root_is_rejected(self):
        outside = ROOT.parent / "outside" / "Probe.java"
        traversal_outside = ROOT / ".." / "outside" / "Probe.java"

        for path in (outside, traversal_outside):
            with self.subTest(path_kind="traversal" if ".." in path.parts else "sibling"):
                with self.assertRaises(ValueError):
                    classify_java_catches(
                        path=path,
                        root=ROOT,
                        text="class Probe { void run(){ try{} catch(Exception failure){} } }",
                    )

    def test_summarize_catches_has_only_the_exact_aggregate_keys(self):
        rows = (
            CatchEvidence("a.java", 1, "EXCEPTION", "NO_LOCAL_BREADCRUMB"),
            CatchEvidence("b.java", 2, "RUNTIME_EXCEPTION", "LOCAL_BREADCRUMB"),
            CatchEvidence("c.java", 3, "THROWABLE", "NO_LOCAL_BREADCRUMB"),
            CatchEvidence("d.java", 4, "OTHER", "NO_LOCAL_BREADCRUMB"),
        )

        self.assertEqual(
            {
                "catchBlockCount": 4,
                "catchWithoutBreadcrumbCount": 3,
                "broadCatchCount": 3,
                "broadCatchWithoutLocalBreadcrumbCount": 2,
            },
            summarize_catches(rows),
        )


if __name__ == "__main__":
    unittest.main()
