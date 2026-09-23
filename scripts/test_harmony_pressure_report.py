import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = ROOT / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import awx_mcp_toolbox as toolbox
from harmony_catch_contract import classify_java_catches, summarize_catches


REPORT_PATH = ROOT / "scripts" / "harmony_pressure_report.py"
SPEC = importlib.util.spec_from_file_location("harmony_pressure_report", REPORT_PATH)
harmony_pressure_report = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(harmony_pressure_report)

FROZEN_OWNER_PATHS = (
    "main/java/com/example/lms/config/LocalLlmProcessManager.java",
    "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java",
    "main/java/com/example/lms/api/PublicRequestBudgetGuard.java",
    "main/java/com/example/lms/ensemble/ApiTriadRoutePreflight.java",
    "main/java/com/example/lms/ensemble/StochasticParamSampler.java",
    "main/java/com/example/lms/harmony/HarmonySseRuntime.java",
    "main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java",
    "main/java/com/example/lms/service/web/BraveSearchService.java",
    "main/java/com/example/lms/tools/ScoringRunner.java",
    "main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java",
    "main/java/com/example/lms/web/TrustedProxyPolicy.java",
)
REJECTED_FALSE_POSITIVE_OWNER_PATHS = {
    "main/java/com/example/lms/config/LocalLlmProcessManager.java",
    "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java",
    "main/java/com/example/lms/api/PublicRequestBudgetGuard.java",
    "main/java/com/example/lms/ensemble/ApiTriadRoutePreflight.java",
    "main/java/com/example/lms/harmony/HarmonySseRuntime.java",
}
VERIFIED_CLOSED_OWNER_PATHS = {
    "main/java/com/example/lms/ensemble/StochasticParamSampler.java",
    "main/java/com/example/lms/service/web/BraveSearchService.java",
    "main/java/com/example/lms/tools/ScoringRunner.java",
    "main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java",
    "main/java/com/example/lms/web/TrustedProxyPolicy.java",
}
TERMINAL_NO_BROAD_CATCH_OWNER_PATHS = (
    REJECTED_FALSE_POSITIVE_OWNER_PATHS | VERIFIED_CLOSED_OWNER_PATHS
)


class HarmonyPressureReportTest(unittest.TestCase):
    def test_main_contains_relative_output_and_preserves_explicit_absolute_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            parent = Path(tmp)
            root = parent / "repo"
            root.mkdir()

            def run(output: str) -> subprocess.CompletedProcess[str]:
                return subprocess.run(
                    [
                        sys.executable,
                        "-B",
                        "-X",
                        "utf8",
                        str(REPORT_PATH),
                        "--root",
                        str(root),
                        "--output",
                        output,
                    ],
                    cwd=ROOT,
                    capture_output=True,
                    text=True,
                    timeout=30,
                    check=False,
                )

            relative_output = Path("verification") / "inside.json"
            relative = run(str(relative_output))
            self.assertEqual(0, relative.returncode, relative.stderr)
            self.assertTrue((root / relative_output).is_file())

            absolute_output = parent / "explicit-absolute.json"
            absolute = run(str(absolute_output))
            self.assertEqual(0, absolute.returncode, absolute.stderr)
            self.assertTrue(absolute_output.is_file())

            outside_hardlink = parent / "outside-hardlink.json"
            outside_hardlink.write_text("sentinel\n", encoding="utf-8")
            relative_hardlink = Path("verification") / "hardlink.json"
            inside_hardlink = root / relative_hardlink
            os.link(outside_hardlink, inside_hardlink)
            hardlink = run(str(relative_hardlink))
            self.assertNotEqual(0, hardlink.returncode)
            self.assertIn("output-relative-hardlink", hardlink.stderr)
            self.assertNotIn(str(root), hardlink.stderr)
            self.assertEqual("sentinel\n", outside_hardlink.read_text(encoding="utf-8"))

            outside_output = parent / "outside.json"
            escaped = run(str(Path("..") / outside_output.name))
            self.assertNotEqual(0, escaped.returncode)
            self.assertIn("output-relative-outside-root", escaped.stderr)
            self.assertNotIn(str(root), escaped.stderr)
            self.assertFalse(outside_output.exists())

    def test_package_import_resolves_the_shared_contract_without_scripts_path_injection(self):
        env = os.environ.copy()
        env.pop("PYTHONPATH", None)
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        env["PYTHONNOUSERSITE"] = "1"
        completed = subprocess.run(
            [
                sys.executable,
                "-X",
                "utf8",
                "-c",
                "import scripts.harmony_pressure_report as report; print(report.__name__)",
            ],
            cwd=ROOT,
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("scripts.harmony_pressure_report", completed.stdout.strip())

    def test_shared_contract_agrees_with_toolbox_for_the_eleven_frozen_owners(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            text_by_path = {}
            shared_rows_by_path = {}
            for relative_path in FROZEN_OWNER_PATHS:
                live_path = ROOT / Path(relative_path)
                self.assertTrue(live_path.is_file(), relative_path)
                fixture_path = root / Path(relative_path)
                fixture_path.parent.mkdir(parents=True, exist_ok=True)
                fixture_path.write_bytes(live_path.read_bytes())
                text = fixture_path.read_text(encoding="utf-8", errors="ignore")
                text_by_path[fixture_path] = text
                shared_rows_by_path[relative_path] = classify_java_catches(
                    path=fixture_path,
                    root=root,
                    text=text,
                )

            report = harmony_pressure_report.build_report(root)
            toolbox_stats = toolbox.harmony_catch_stats(text_by_path, root)

        public_rows = report["ledgerEvidence"]["broadCatchWithoutLocalBreadcrumbFiles"]
        public_by_path = {row["file"]: row for row in public_rows}
        broad_classes = {"EXCEPTION", "RUNTIME_EXCEPTION", "THROWABLE"}
        all_shared_rows = tuple(
            row
            for relative_path in FROZEN_OWNER_PATHS
            for row in shared_rows_by_path[relative_path]
        )
        shared_summary = summarize_catches(all_shared_rows)
        broad_unhandled_by_path = {}

        for relative_path in FROZEN_OWNER_PATHS:
            rows = shared_rows_by_path[relative_path]
            expected_broad_unhandled = sum(
                row.caught_type_class in broad_classes
                and row.reason_code == "NO_LOCAL_BREADCRUMB"
                for row in rows
            )
            broad_unhandled_by_path[relative_path] = expected_broad_unhandled
            public_count = int(
                public_by_path.get(relative_path, {}).get(
                    "broadCatchWithoutLocalBreadcrumbApprox", 0
                )
            )
            with self.subTest(path=relative_path):
                self.assertEqual(expected_broad_unhandled, public_count)

        self.assertEqual(
            TERMINAL_NO_BROAD_CATCH_OWNER_PATHS,
            {path for path, count in broad_unhandled_by_path.items() if count == 0},
        )
        self.assertTrue(
            all(
                count >= 1
                for path, count in broad_unhandled_by_path.items()
                if path not in TERMINAL_NO_BROAD_CATCH_OWNER_PATHS
            )
        )

        self.assertEqual(
            shared_summary["broadCatchWithoutLocalBreadcrumbCount"],
            report["broadCatchWithoutLocalBreadcrumbApprox"],
        )
        self.assertEqual(
            shared_summary["catchBlockCount"],
            toolbox_stats["catchBlockCount"],
        )
        self.assertEqual(
            shared_summary["catchWithoutBreadcrumbCount"],
            toolbox_stats["catchWithoutBreadcrumbCount"],
        )
        expected_samples = [
            {"path": row.path, "line": row.line}
            for row in all_shared_rows
            if row.reason_code == "NO_LOCAL_BREADCRUMB"
        ][:20]
        self.assertEqual(expected_samples, toolbox_stats["samples"])
        self.assertTrue(all(set(sample) == {"path", "line"} for sample in toolbox_stats["samples"]))
        self.assertTrue(all("line" not in row for row in public_rows))
        self.assertNotIn(str(root), json.dumps(public_rows, sort_keys=True))

    def test_breadcrumb_in_a_later_catch_beyond_32_lines_does_not_mask_the_first(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "main" / "java" / "com" / "example" / "SeparatedCatches.java"
            source.parent.mkdir(parents=True)
            padding = "\n".join(f"        int padding{index} = {index};" for index in range(33))
            source.write_text(
                f"""
                package com.example;
                class SeparatedCatches {{
                    void run() {{
                        try {{ first(); }}
                        catch (Exception firstFailure) {{ work(); }}
{padding}
                        try {{ second(); }}
                        catch (Exception secondFailure) {{
                            TraceStore.put("probe.reason", "failed");
                        }}
                    }}
                }}
                """,
                encoding="utf-8",
            )

            report = harmony_pressure_report.build_report(root)

        self.assertEqual(2, report["catchBlocks"])
        self.assertEqual(1, report["catchWithoutLocalBreadcrumbApprox"])
        self.assertEqual(2, report["broadCatchBlocks"])
        self.assertEqual(1, report["broadCatchWithoutLocalBreadcrumbApprox"])

    def test_scans_cross_subsystem_and_catch_pressure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "main" / "java" / "com" / "example"
            src.mkdir(parents=True)
            (src / "MixedFlow.java").write_text(
                """
                package com.example;

                import org.aspectj.lang.annotation.Aspect;

                @Aspect
                public class MixedFlow {
                    void run() {
                        try {
                            System.out.println("Overdrive CFVM ExtremeZ");
                        } catch (Exception ex) {
                            int ignored = 1;
                        }
                    }
                }
                """,
                encoding="utf-8",
            )
            (src / "OrderedFlow.java").write_text(
                """
                package com.example;

                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.core.annotation.Order;

                @Aspect
                @Order(10)
                public class OrderedFlow {
                    void run() {
                        try {
                            System.out.println("PromptBuilder HYPERNOVA DPP");
                        } catch (RuntimeException ex) {
                            log.warn("reason={}", ex.toString());
                        }
                        try {
                            System.out.println("Matryoshka Embedding");
                        } catch (RuntimeException ex) {
                            logSuppressed("embedding.stage", ex);
                        }
                        try {
                            System.out.println("OpenAI PromptBuilder");
                        } catch (Throwable ex) {
                            traceSuppressed("adapter.stage", ex);
                        }
                        try {
                            System.out.println("ExtremeZ TimeBudget");
                        } catch (Throwable ex) {
                            traceFailure("extremez.stage", ex);
                        }
                        try {
                            System.out.println("CancelShield");
                        } catch (Throwable ex) {
                            traceTelemetrySkipped("cancel.stage", ex);
                        }
                        try {
                            System.out.println("Analyze web search");
                        } catch (Exception ex) {
                            traceCancelFailure("web.stage", ex);
                        }
                        try {
                            System.out.println("Rethrow path");
                        } catch (Exception ex) {
                            throw ex;
                        }
                        try {
                            System.out.println("Hybrid fallback provider");
                        } catch (Throwable ex) {
                            recordProviderError("naver", ex);
                        }
                        try {
                            System.out.println("QueryTransformer worker");
                        } catch (Throwable ex) {
                            recordCachedLlmWorkerThrowable("prompt", ex);
                        }
                        try {
                            System.out.println("Debug event emit");
                        } catch (Throwable ex) {
                            recordDebugEventEmitFailure(ex);
                        }
                        try {
                            System.out.println("LangGraph shadow");
                        } catch (Exception ex) {
                            recordGraphExceptionTrace("langgraph.shadow", request, ex, 1L, 0);
                        }
                        try {
                            System.out.println("LangGraph delayed shadow");
                        } catch (Exception ex) {
                            debug.put("langgraph.shadow.legacyResultCount", legacyCount);
                            debug.put("langgraph.shadow.graphResultCount", 0);
                            debug.put("langgraph.shadow.latencyMsLegacy", legacyLatencyMs);
                            debug.put("langgraph.shadow.latencyMsGraph", graphLatencyMs);
                            debug.put("langgraph.shadow.repairTriggered", false);
                            debug.put("langgraph.shadow.fallbackTriggered", true);
                            debug.put("langgraph.shadow.answerDiffScore", 1.0d);
                            debug.put("langgraph.shadow.tookMs", graphLatencyMs);
                            debug.put("langgraph.shadow.resultCount", 0);
                            debug.put("langgraph.shadow.deltaResultCount", -legacyCount);
                            debug.put("langgraph.shadow.error", operationalErrorType(ex));
                            debug.put("langgraph.shadow.promotionScore", 0.0d);
                            debug.put("langgraph.shadow.promotionEligible", false);
                            debug.put("langgraph.shadow.promotionBlockers", java.util.List.of("graph_exception"));
                            debug.put("langgraph.shadow.transitionScore", 0.0d);
                            debug.put("langgraph.shadow.policyRuleId", "shadow-fallback");
                            debug.put("langgraph.shadow.controlMode", "observe");
                            debug.put("langgraph.shadow.invokeSource", "graph");
                            debug.put("langgraph.shadow.checkpointBackend", "memory");
                            debug.put("langgraph.shadow.candidateCount", 0);
                            recordGraphExceptionTrace("langgraph.shadow", request, ex, 1L, 0);
                        }
                        try {
                            System.out.println("Noise filter");
                        } catch (RuntimeException ex) {
                            recordNoiseFilterFallback("safe_int", ex);
                        }
                        try {
                            System.out.println("Safe numeric parse");
                        } catch (NumberFormatException ignored) {
                            INVALID_NUMBER_SUPPRESSOR.accept("onnxDocIndex");
                        }
                        String ui = \"""
                            function getKey(){ try { return sessionStorage.getItem(LS_KEY) || ''; } catch(e){ console.debug('ui storage skipped'); return ''; } }
                            try { obj = txt ? JSON.parse(txt) : null; } catch(e) { console.debug('ui json parse skipped'); }
                            navigator.clipboard.writeText(text).catch(function(){ fallbackCopy(text); });
                            \""";
                    }
                }
                """,
                encoding="utf-8",
            )
            tools = root / "main" / "java" / "com" / "example" / "lms" / "tools"
            tools.mkdir(parents=True)
            (tools / "HarmonyBuildScanner.java").write_text(
                """
                package com.example.lms.tools;

                public class HarmonyBuildScanner {
                    String all = "Overdrive CFVM MoE Matryoshka ExtremeZ HYPERNOVA CIH-RAG OpenAI PromptBuilder";
                }
                """,
                encoding="utf-8",
            )

            report = harmony_pressure_report.build_report(root)
            rendered = json.dumps(report, sort_keys=True)

        self.assertEqual(3, report["activeJavaFileCount"])
        self.assertEqual(3, report["crossSubsystemFiles"])
        self.assertEqual(2, report["runtimeCrossSubsystemFiles"])
        self.assertEqual(1, report["diagnosticCrossSubsystemFiles"])
        self.assertNotIn("tools/HarmonyBuildScanner.java", json.dumps(report["topRuntimeCrossSubsystemFiles"]))
        self.assertEqual(2, report["aspectFiles"])
        self.assertEqual(1, report["aspectFilesWithExplicitOrderApprox"])
        self.assertEqual(1, report["unorderedAspectCount"])
        self.assertEqual(1, report["criticalUnorderedAspectCount"])
        self.assertEqual(
            "main/java/com/example/MixedFlow.java",
            report["topUnorderedAspectHotspots"][0]["file"],
        )
        self.assertFalse(report["topUnorderedAspectHotspots"][0]["explicitOrder"])
        self.assertGreaterEqual(report["topUnorderedAspectHotspots"][0]["subsystemCount"], 2)
        self.assertEqual(15, report["catchBlocks"])
        self.assertEqual(4, report["catchWithoutLocalBreadcrumbApprox"])
        self.assertEqual(4, report["broadCatchWithoutLocalBreadcrumbApprox"])
        self.assertEqual(
            "main/java/com/example/OrderedFlow.java",
            report["topCatchPressureFiles"][0]["file"],
        )
        self.assertEqual(3, report["topCatchPressureFiles"][0]["catchWithoutLocalBreadcrumbApprox"])
        self.assertEqual(3, report["topCatchPressureFiles"][0]["broadCatchWithoutLocalBreadcrumbApprox"])
        self.assertEqual(
            "main/java/com/example/MixedFlow.java",
            report["topCatchPressureFiles"][1]["file"],
        )
        self.assertIn("main/java/com/example/MixedFlow.java", rendered)
        self.assertNotIn(str(root), rendered)

    def test_ledger_evidence_includes_every_runtime_large_file_not_only_top_25(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "main" / "java" / "com" / "example"
            src.mkdir(parents=True)
            expected_files = []
            for index in range(27):
                name = f"RuntimeLarge{index:02d}.java"
                expected_files.append(f"main/java/com/example/{name}")
                source = "\n".join(
                    [
                        "package com.example;",
                        f"public class RuntimeLarge{index:02d} {{",
                        '    String systems = "Overdrive CFVM";',
                        *(["    // deterministic padding"] * 1000),
                        "}",
                    ]
                )
                (src / name).write_text(source, encoding="utf-8")

            report = harmony_pressure_report.build_report(root)

        compatibility_rows = report["topRuntimeCrossSubsystemFiles"]
        ledger_rows = report["ledgerEvidence"]["runtimeCrossSubsystemLargeFiles"]
        self.assertEqual(25, len(compatibility_rows))
        self.assertEqual(27, len(ledger_rows))
        self.assertEqual(expected_files, [row["file"] for row in ledger_rows])
        self.assertTrue(
            all(
                set(row) == {"file", "lines", "subsystems", "hitScore"}
                for row in ledger_rows
            )
        )

    def test_ledger_evidence_includes_every_broad_catch_file_without_source_snippets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "main" / "java" / "com" / "example"
            src.mkdir(parents=True)
            (src / "BroadFallback.java").write_text(
                """
                package com.example;
                public class BroadFallback {
                    void run() {
                        try {
                            System.out.println("work");
                        } catch (Exception ex) {
                            int privateBodyMarker = 1;
                        }
                    }
                }
                """,
                encoding="utf-8",
            )

            report = harmony_pressure_report.build_report(root)

        rows = report["ledgerEvidence"]["broadCatchWithoutLocalBreadcrumbFiles"]
        self.assertEqual(1, len(rows))
        self.assertEqual(
            {
                "file",
                "lines",
                "broadCatchBlocks",
                "broadCatchWithoutLocalBreadcrumbApprox",
            },
            set(rows[0]),
        )
        self.assertEqual("main/java/com/example/BroadFallback.java", rows[0]["file"])
        self.assertEqual(1, rows[0]["broadCatchWithoutLocalBreadcrumbApprox"])
        rendered = json.dumps(rows, sort_keys=True)
        self.assertNotIn("privateBodyMarker", rendered)
        self.assertNotIn(str(root), rendered)

    def test_ledger_evidence_is_path_sorted_and_numeric_fields_are_nonnegative(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for package_name, class_name in (("zeta", "ZetaFlow"), ("alpha", "AlphaFlow")):
                src = root / "main" / "java" / "com" / "example" / package_name
                src.mkdir(parents=True)
                source = "\n".join(
                    [
                        f"package com.example.{package_name};",
                        f"public class {class_name} {{",
                        '    String systems = "Overdrive CFVM";',
                        "    void run() {",
                        "        try { System.out.println(systems); }",
                        "        catch (RuntimeException ex) { int ignored = 1; }",
                        "    }",
                        *(["    // deterministic padding"] * 1000),
                        "}",
                    ]
                )
                (src / f"{class_name}.java").write_text(source, encoding="utf-8")

            report = harmony_pressure_report.build_report(root)

        evidence = report["ledgerEvidence"]
        for key in (
            "runtimeCrossSubsystemLargeFiles",
            "broadCatchWithoutLocalBreadcrumbFiles",
            "manualPromptCandidateFiles",
        ):
            rows = evidence[key]
            paths = [row["file"] for row in rows]
            self.assertEqual(sorted(paths, key=lambda path: (path.casefold(), path)), paths)
            for row in rows:
                for field, value in row.items():
                    if field not in {"file", "subsystems"}:
                        self.assertIsInstance(value, int)
                        self.assertGreaterEqual(value, 0)

    def test_manual_prompt_evidence_remains_separate_from_deterministic_categories(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "main" / "java" / "com" / "example"
            manual_cases = (
                ("zeta", "ZetaManualBypassService", "manualPrivateBodyMarkerZeta"),
                ("Alpha", "AlphaManualBypassService", "manualPrivateBodyMarkerAlpha"),
            )
            for package_name, class_name, marker in manual_cases:
                path = src / package_name / f"{class_name}.java"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(
                    f"""
                    package com.example.{package_name};
                    import dev.langchain4j.data.message.UserMessage;
                    import dev.langchain4j.model.chat.ChatModel;
                    import java.util.List;
                    public class {class_name} {{
                        private final ChatModel model;
                        {class_name}(ChatModel model) {{ this.model = model; }}
                        String run(String question) {{
                            String prompt = "Question: " + question;
                            String {marker} = prompt;
                            return model.chat(List.of(UserMessage.from(prompt))).aiMessage().text();
                        }}
                    }}
                    """,
                    encoding="utf-8",
                )

            report = harmony_pressure_report.build_report(root)

        evidence = report["ledgerEvidence"]
        manual_rows = evidence["manualPromptCandidateFiles"]
        expected_paths = [
            "main/java/com/example/Alpha/AlphaManualBypassService.java",
            "main/java/com/example/zeta/ZetaManualBypassService.java",
        ]
        self.assertEqual(expected_paths, [row["file"] for row in manual_rows])
        self.assertEqual(
            sorted(expected_paths, key=lambda path: (path.casefold(), path)),
            [row["file"] for row in manual_rows],
        )
        for row in manual_rows:
            self.assertEqual({"file", "lines"}, set(row))
            self.assertIsInstance(row["lines"], int)
            self.assertGreaterEqual(row["lines"], 0)

        for manual_path in expected_paths:
            self.assertNotIn(
                manual_path,
                [row["file"] for row in evidence["runtimeCrossSubsystemLargeFiles"]],
            )
            self.assertNotIn(
                manual_path,
                [row["file"] for row in evidence["broadCatchWithoutLocalBreadcrumbFiles"]],
            )

        rendered_ledger = json.dumps(evidence, sort_keys=True)
        self.assertNotIn("manualPrivateBodyMarkerAlpha", rendered_ledger)
        self.assertNotIn("manualPrivateBodyMarkerZeta", rendered_ledger)
        self.assertNotIn(str(root), rendered_ledger)

    def test_manual_prompt_candidates_ignore_llm_transport_adapters(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "main" / "java" / "com" / "example"
            src.mkdir(parents=True)
            (src / "ManualBypassService.java").write_text(
                """
                package com.example;

                import dev.langchain4j.data.message.UserMessage;
                import dev.langchain4j.model.chat.ChatModel;
                import java.util.List;

                public class ManualBypassService {
                    private final ChatModel model;
                    ManualBypassService(ChatModel model) {
                        this.model = model;
                    }
                    String run(String question) {
                        String unsafePrompt = "Question: " + question;
                        return model.chat(List.of(UserMessage.from(unsafePrompt))).aiMessage().text();
                    }
                }
                """,
                encoding="utf-8",
            )
            (src / "OpenAiResponsesChatModel.java").write_text(
                """
                package com.example;

                import dev.langchain4j.data.message.ChatMessage;
                import dev.langchain4j.model.chat.ChatModel;
                import dev.langchain4j.model.chat.response.ChatResponse;
                import java.util.List;

                public final class OpenAiResponsesChatModel implements ChatModel {
                    public ChatResponse chat(List<ChatMessage> messages) {
                        String input = messages.toString();
                        StringBuilder sb = new StringBuilder();
                        sb.append(input);
                        return null;
                    }
                }
                """,
                encoding="utf-8",
            )

            report = harmony_pressure_report.build_report(root)

        self.assertEqual(1, report["manualPromptCandidateCount"])
        self.assertEqual(
            "main/java/com/example/ManualBypassService.java",
            report["manualPromptCandidateFiles"][0]["file"],
        )

    def test_name_only_aspect_placeholder_is_not_counted_as_a_spring_aspect(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "main" / "java" / "com" / "example" / "telemetry"
            src.mkdir(parents=True)
            (src / "DecisionHotspotAspect.java").write_text(
                """
                package com.example.telemetry;

                public class DecisionHotspotAspect {
                    // Placeholder only; not a Spring AOP aspect.
                }
                """,
                encoding="utf-8",
            )

            report = harmony_pressure_report.build_report(root)

        self.assertEqual(0, report["aspectFiles"])
        self.assertEqual(0, report["unorderedAspectCount"])
        self.assertEqual([], report["topUnorderedAspectHotspots"])

    def test_active_spring_aspects_have_explicit_order(self):
        report = harmony_pressure_report.build_report(ROOT)
        unordered = [
            row["file"]
            for row in report["topUnorderedAspectHotspots"]
            if row["file"].startswith(("main/java/", "app/src/main/java_clean/"))
        ]

        self.assertEqual([], unordered)

    def test_planner_nexus_broad_fail_soft_catches_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        row = next(
            (
                item
                for item in report["topCatchPressureFiles"]
                if item["file"] == "main/java/com/abandonwareai/planner/PlannerNexus.java"
            ),
            None,
        )

        self.assertTrue(row is None or row["broadCatchWithoutLocalBreadcrumbApprox"] == 0, row)

    def test_local_llm_process_manager_broad_fail_soft_catches_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        row = next(
            (
                item
                for item in report["topCatchPressureFiles"]
                if item["file"] == "main/java/com/abandonware/ai/agent/config/LocalLlmProcessManager.java"
            ),
            None,
        )

        self.assertTrue(row is None or row["broadCatchWithoutLocalBreadcrumbApprox"] == 0, row)

    def test_rag_light_adapters_broad_fail_soft_catches_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        row = next(
            (
                item
                for item in report["topCatchPressureFiles"]
                if item["file"] == "main/java/config/RagLightAdapters.java"
            ),
            None,
        )

        self.assertTrue(row is None or row["broadCatchWithoutLocalBreadcrumbApprox"] == 0, row)

    def test_small_reflective_plan_helpers_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] in {
                "main/java/service/plan/PlanLoader.java",
                "main/java/com/example/patch/OtelTracerBridge.java",
            }
        }

        self.assertEqual({}, files)

    def test_small_vector_and_risk_helpers_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] in {
                "main/java/com/example/risk/RiskFeatureExtractor.java",
                "main/java/com/abandonware/ai/vector/qdrant/QdrantClient.java",
            }
        }

        self.assertEqual({}, files)

    def test_app_domain_whitelist_malformed_url_fallback_emits_redacted_breadcrumb(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "app/src/main/java_clean/com/example/lms/service/rag/auth/DomainWhitelist.java"
        }

        self.assertEqual({}, files)

    def test_main_domain_whitelist_malformed_url_fallback_emits_redacted_breadcrumb(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/example/lms/service/rag/auth/DomainWhitelist.java"
        }

        self.assertEqual({}, files)

    def test_openai_audio_fail_soft_paths_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] in {
                "main/java/com/example/lms/audio/OpenAiTranscriptionService.java",
                "main/java/com/example/lms/audio/OpenAiSpeechService.java",
            }
        }

        self.assertEqual({}, files)

    def test_small_trace_fusion_and_moe_helpers_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] in {
                "main/java/tools/trace/OrchestrationTraceTool.java",
                "main/java/com/abandonware/ai/service/rag/fusion/RrfFusion.java",
                "main/java/com/example/moe/FeatureCollector.java",
            }
        }

        self.assertEqual({}, files)

    def test_small_controller_config_and_router_fallbacks_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] in {
                "main/java/com/example/lms/audio/AudioController.java",
                "main/java/com/example/lms/web/RegistrationController.java",
                "main/java/com/abandonware/ai/probe/SearchProbeController.java",
                "main/java/com/example/lms/service/routing/ModelRouterLegacy2.java",
                "main/java/com/abandonware/patch/config/PatchAutoConfiguration.java",
            }
        }

        self.assertEqual({}, files)

    def test_small_url_scheduler_and_config_helpers_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] in {
                "main/java/com/abandonware/ai/util/UrlCanonicalizer.java",
                "main/java/scheduler/IndexingScheduler.java",
                "main/java/service/rag/fusion/RerankCanonicalizer.java",
                "main/java/config/OrchestrationConfig.java",
                "app/src/main/java_clean/com/example/lms/service/rag/fusion/RerankCanonicalizer.java",
            }
        }

        self.assertEqual({}, files)

    def test_small_plan_vector_upload_and_locale_fallbacks_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] in {
                "main/java/com/abandonware/patch/plan/PlannerNexus.java",
                "main/java/com/abandonware/ai/vector/FederatedEmbeddingStore.java",
                "main/java/com/example/lms/api/FileUploadController.java",
                "main/java/planner/PlanLoader.java",
                "main/java/com/abandonware/ai/service/rag/fusion/LocaleBoostPolicy.java",
            }
        }

        self.assertEqual({}, files)

    def test_small_alias_adapter_and_stub_fallbacks_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] in {
                "main/java/com/abandonware/ai/agent/integrations/service/rag/fusion/CanonicalUrlNormalizer.java",
                "main/java/com/abandonware/ai/service/rag/auth/DomainWhitelist.java",
                "main/java/com/abandonware/ai/agent/integrations/CanonicalUrlNormalizer.java",
                "main/java/service/tools/outbox/OutboxSendTool.java",
                "main/java/com/abandonware/ai/vector/qdrant/QdrantVectorStoreAdapter.java",
                "main/java/com/example/lms/service/impl/ChatServiceImpl.java",
            }
        }

        self.assertEqual({}, files)

    def test_small_signature_plan_reflection_and_key_helpers_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] in {
                "main/java/com/abandonware/ai/integrations/n8n/SignatureVerifier.java",
                "main/java/strategy/PlanLoader.java",
                "main/java/com/abandonware/ai/planner/dsl/PlanLoader.java",
                "main/java/service/rag/cache/SingleFlightKeys.java",
                "main/java/com/example/patch/OcrTesseractReflect.java",
                "main/java/service/tools/fallback/FallbackRetrieveTool.java",
                "main/java/com/example/patch/FlowJoinerBridge.java",
                "main/java/com/example/patch/RateLimiterSelector.java",
                "main/java/com/abandonware/ai/agent/integrations/math/LegacyMathPort.java",
            }
        }

        self.assertEqual({}, files)

    def test_chat_ui_heartbeat_external_evidence_fallbacks_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        row = next(
            (
                item
                for item in report["topCatchPressureFiles"]
                if item["file"] == "main/java/com/example/lms/web/ChatUiCoreHeartbeatProbe.java"
            ),
            None,
        )

        self.assertTrue(row is None or row["broadCatchWithoutLocalBreadcrumbApprox"] == 0, row)

    def test_chat_harmony_postprocessor_parse_fallback_emits_redacted_breadcrumb(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/example/lms/api/ChatHarmonyTracePostprocessor.java"
        }

        self.assertEqual({}, files)

    def test_onnx_rerank_limiter_fail_soft_fallback_emits_redacted_breadcrumb(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/abandonware/ai/agent/integrations/service/onnx/OnnxRerankLimiter.java"
        }

        self.assertEqual({}, files)

    def test_app_onnx_reranker_property_parse_fallbacks_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "app/src/main/java_clean/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java"
        }

        self.assertEqual({}, files)

    def test_main_onnx_reranker_internal_fail_soft_hooks_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java"
        }

        self.assertEqual({}, files)

    def test_k_allocation_policy_reflection_fallback_emits_redacted_breadcrumb(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/abandonware/ai/agent/service/rag/policy/KAllocationPolicy.java"
        }

        self.assertEqual({}, files)

    def test_whitening_default_fallback_emits_redacted_breadcrumb(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/abandonware/ai/service/rag/whiten/Whitening.java"
        }

        self.assertEqual({}, files)

    def test_alias_dpp_reranker_reflection_fallbacks_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/abandonware/ai/service/rag/rerank/DppDiversityReranker.java"
        }

        self.assertEqual({}, files)

    def test_zsystem_timeout_fallback_does_not_use_cancel_true_and_emits_breadcrumbs(self):
        source_path = ROOT / "main/java/com/abandonware/ai/zsystem/ZSystem.java"
        source = source_path.read_text(encoding="utf-8")

        self.assertNotIn("cancel(true)", source)

        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/abandonware/ai/zsystem/ZSystem.java"
        }

        self.assertEqual({}, files)

    def test_zca_whitening_eigendecomposition_fallback_emits_redacted_breadcrumb(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/abandonware/ai/agent/service/ml/ZcaWhitening.java"
        }

        self.assertEqual({}, files)

    def test_retrieval_order_context_fallbacks_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/example/lms/strategy/RetrievalOrderService.java"
        }

        self.assertEqual({}, files)

    def test_rag_graph_executor_hash_fallback_emits_redacted_breadcrumb(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java"
        }

        self.assertEqual({}, files)

    def test_alias_dynamic_retrieval_handler_sse_fallbacks_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/service/rag/handler/DynamicRetrievalHandlerChain.java"
        }

        self.assertEqual({}, files)

    def test_canonical_dynamic_retrieval_handler_fallbacks_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        files = {
            row["file"]: row
            for row in report["topCatchPressureFiles"]
            if row["file"] == "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"
        }

        self.assertEqual({}, files)

    def test_chat_orchestrator_broad_fail_soft_catches_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        row = next(
            (
                item
                for item in report["topCatchPressureFiles"]
                if item["file"] == "main/java/com/example/lms/service/ChatOrchestrator.java"
            ),
            None,
        )

        self.assertTrue(row is None or row["broadCatchWithoutLocalBreadcrumbApprox"] == 0, row)

    def test_chat_orchestrator_patch_broad_fail_soft_catches_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        row = next(
            (
                item
                for item in report["topCatchPressureFiles"]
                if item["file"] == "main/java/com/example/lms/service/patch/ChatOrchestratorPatch.java"
            ),
            None,
        )

        self.assertTrue(row is None or row["broadCatchWithoutLocalBreadcrumbApprox"] == 0, row)

    def test_legacy_chat_mirrors_broad_fail_soft_catches_emit_redacted_breadcrumbs(self):
        report = harmony_pressure_report.build_report(ROOT)
        rows = {
            item["file"]: item
            for item in report["topCatchPressureFiles"]
            if item["file"]
            in {
                "main/java/com/example/lms/service/legacy/ChatServiceLegacy.java",
                "main/java/com/example/lms/service/patch/ChatServiceLegacyPatch.java",
            }
        }

        for row in rows.values():
            self.assertEqual(0, row["broadCatchWithoutLocalBreadcrumbApprox"], row)


if __name__ == "__main__":
    unittest.main()
