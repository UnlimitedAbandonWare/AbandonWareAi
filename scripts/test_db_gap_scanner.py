import json
import datetime as dt
import unittest
import tempfile
from pathlib import Path

import db_gap_scanner
from db_gap_scanner import (
    SUBSYSTEM_SPECS,
    ClassInfo,
    EntityInfo,
    RepositoryInfo,
    assess_subsystem,
    discover_java_files,
    generate_markdown,
    persistence_contract_badge,
    residual_volatile_state,
)


class DbGapScannerClassificationTest(unittest.TestCase):

    def test_high_conf_secret_patterns_include_supabase_sb_keys(self):
        text = (
            ("sb_secret_" + "1234567890abcdef")
            + " "
            + ("sb_publishable_" + "1234567890abcdef")
            + " "
            + ("sbp_" + "1234567890abcdef")
        )

        self.assertEqual(3, db_gap_scanner.count_high_conf_secret_patterns(text))

    def test_output_location_format_is_ascii_only(self):
        line = db_gap_scanner.format_output_location("JSON output", Path("data/db-gap-report"), directory=True)

        self.assertEqual("  JSON output -> data\\db-gap-report\\", line)
        line.encode("ascii")

    def test_generated_at_utc_is_timezone_aware(self):
        generated_at = db_gap_scanner.generated_at_utc()

        parsed = dt.datetime.fromisoformat(generated_at.replace("Z", "+00:00"))
        self.assertIsNotNone(parsed.tzinfo)
        self.assertEqual(dt.timezone.utc, parsed.astimezone(dt.timezone.utc).tzinfo)

    def test_repo_root_scan_uses_active_source_roots_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            active = root / "main" / "java" / "com" / "example"
            inactive = root / "extras" / "gap15-stubs_v1" / "src" / "main" / "java" / "com" / "example"
            app_clean = root / "app" / "src" / "main" / "java_clean" / "com" / "example"
            active.mkdir(parents=True)
            inactive.mkdir(parents=True)
            app_clean.mkdir(parents=True)
            (active / "LiveOnly.java").write_text(
                "package com.example;\npublic class LiveOnly {}\n",
                encoding="utf-8",
            )
            (inactive / "LiveOnly.java").write_text(
                "package com.example.stub;\npublic class LiveOnly {}\n",
                encoding="utf-8",
            )
            (app_clean / "AppOnly.java").write_text(
                "package com.example;\npublic class AppOnly {}\n",
                encoding="utf-8",
            )

            files, scan_roots = discover_java_files(root)

            rel_files = {str(path.relative_to(root)).replace("\\", "/") for path in files}
            self.assertEqual(
                {"main/java/com/example/LiveOnly.java", "app/src/main/java_clean/com/example/AppOnly.java"},
                rel_files,
            )
            self.assertEqual(["main/java", "app/src/main/java_clean"], scan_roots)

    def test_configuration_only_subsystem_is_not_critical_for_volatile_helpers(self):
        gap = assess_subsystem(
            "S04_MATRYOSHKA",
            SUBSYSTEM_SPECS["S04_MATRYOSHKA"],
            [
                ClassInfo(
                    fqcn="com.example.lms.service.embedding.OllamaEmbeddingModel",
                    simple_name="OllamaEmbeddingModel",
                    file_path="com/example/lms/service/embedding/OllamaEmbeddingModel.java",
                    package="com.example.lms.service.embedding",
                    volatile_storage=["volatile"],
                ),
            ],
            [],
            [],
            {},
        )

        self.assertEqual("config_only", gap.persistence_type)
        self.assertEqual("LOW", gap.gap_severity)
        self.assertEqual("accepted_config_only", gap.persistence_status)
        self.assertFalse(gap.action_required)

    def test_trace_snapshot_exporter_satisfies_s07_ndjson_persistence(self):
        gap = assess_subsystem(
            "S07_CIH_RAG",
            SUBSYSTEM_SPECS["S07_CIH_RAG"],
            [
                ClassInfo(
                    fqcn="com.example.lms.search.TraceStore",
                    simple_name="TraceStore",
                    file_path="com/example/lms/search/TraceStore.java",
                    package="com.example.lms.search",
                    volatile_storage=["ThreadLocal"],
                ),
                ClassInfo(
                    fqcn="com.example.lms.compat.TraceStore",
                    simple_name="TraceStore",
                    file_path="com/example/lms/compat/TraceStore.java",
                    package="com.example.lms.compat",
                ),
                ClassInfo(
                    fqcn="com.example.lms.debug.DebugEventStore",
                    simple_name="DebugEventStore",
                    file_path="com/example/lms/debug/DebugEventStore.java",
                    package="com.example.lms.debug",
                    volatile_storage=["ConcurrentLinkedDeque"],
                ),
                ClassInfo(
                    fqcn="com.example.lms.telemetry.LoggingSseEventPublisher",
                    simple_name="LoggingSseEventPublisher",
                    file_path="com/example/lms/telemetry/LoggingSseEventPublisher.java",
                    package="com.example.lms.telemetry",
                ),
                ClassInfo(
                    fqcn="com.example.lms.trace.TraceSnapshotExporter",
                    simple_name="TraceSnapshotExporter",
                    file_path="com/example/lms/trace/TraceSnapshotExporter.java",
                    package="com.example.lms.trace",
                ),
            ],
            [],
            [],
            {},
        )

        self.assertTrue(gap.has_db_persistence)
        self.assertEqual("NDJSON_TRACE_SNAPSHOT", gap.persistence_type)
        self.assertEqual("review_ndjson_trace_snapshot", gap.persistence_status)
        self.assertFalse(gap.action_required)
        self.assertEqual([], gap.entities_missing)
        self.assertEqual([], gap.repositories_missing)
        self.assertEqual("LOW", gap.gap_severity)
        self.assertIn("durable MLA breadcrumb records", gap.gap_details)

    def test_trace_snapshot_exporter_alias_name_does_not_satisfy_s07_ndjson(self):
        gap = assess_subsystem(
            "S07_CIH_RAG",
            SUBSYSTEM_SPECS["S07_CIH_RAG"],
            [
                ClassInfo(
                    fqcn="com.example.lms.search.TraceStore",
                    simple_name="TraceStore",
                    file_path="com/example/lms/search/TraceStore.java",
                    package="com.example.lms.search",
                    volatile_storage=["ThreadLocal"],
                ),
                ClassInfo(
                    fqcn="com.example.lms.debug.DebugEventStore",
                    simple_name="DebugEventStore",
                    file_path="com/example/lms/debug/DebugEventStore.java",
                    package="com.example.lms.debug",
                    volatile_storage=["ConcurrentLinkedDeque"],
                ),
                ClassInfo(
                    fqcn="com.example.lms.telemetry.LoggingSseEventPublisher",
                    simple_name="LoggingSseEventPublisher",
                    file_path="com/example/lms/telemetry/LoggingSseEventPublisher.java",
                    package="com.example.lms.telemetry",
                ),
                ClassInfo(
                    fqcn="example.alias.LoggingSseEventPublisher",
                    simple_name="LoggingSseEventPublisher",
                    file_path="example/alias/LoggingSseEventPublisher.java",
                    package="example.alias",
                ),
                ClassInfo(
                    fqcn="example.fake.TraceSnapshotExporter",
                    simple_name="TraceSnapshotExporter",
                    file_path="example/fake/TraceSnapshotExporter.java",
                    package="example.fake",
                ),
            ],
            [],
            [],
            {},
        )

        self.assertFalse(gap.has_db_persistence)
        self.assertNotEqual("NDJSON_TRACE_SNAPSHOT", gap.persistence_type)
        self.assertNotEqual("review_ndjson_trace_snapshot", gap.persistence_status)
        self.assertTrue(gap.action_required)

    def test_cfvm_snapshot_satisfies_s02_persistence_contract(self):
        gap = assess_subsystem(
            "S02_CFVM",
            SUBSYSTEM_SPECS["S02_CFVM"],
            [
                ClassInfo(
                    fqcn="com.example.lms.cfvm.RawMatrixBuffer",
                    simple_name="RawMatrixBuffer",
                    file_path="com/example/lms/cfvm/RawMatrixBuffer.java",
                    package="com.example.lms.cfvm",
                    volatile_storage=["ArrayDeque"],
                ),
                ClassInfo(
                    fqcn="com.example.lms.cfvm.CfvmFailureRecorder",
                    simple_name="CfvmFailureRecorder",
                    file_path="com/example/lms/cfvm/CfvmFailureRecorder.java",
                    package="com.example.lms.cfvm",
                ),
                ClassInfo(
                    fqcn="com.example.lms.cfvm.RawSlotExtractor",
                    simple_name="RawSlotExtractor",
                    file_path="com/example/lms/cfvm/RawSlotExtractor.java",
                    package="com.example.lms.cfvm",
                ),
                ClassInfo(
                    fqcn="com.example.lms.strategy.RetrievalOrderService",
                    simple_name="RetrievalOrderService",
                    file_path="com/example/lms/strategy/RetrievalOrderService.java",
                    package="com.example.lms.strategy",
                    is_component=True,
                ),
                ClassInfo(
                    fqcn="com.abandonware.ai.strategy.RetrievalOrderService",
                    simple_name="RetrievalOrderService",
                    file_path="com/abandonware/ai/strategy/RetrievalOrderService.java",
                    package="com.abandonware.ai.strategy",
                    is_component=True,
                ),
            ],
            [
                EntityInfo(
                    fqcn="com.example.lms.cfvm.CfvmSnapshot",
                    simple_name="CfvmSnapshot",
                    table_name="cfvm_snapshot",
                    file_path="com/example/lms/cfvm/CfvmSnapshot.java",
                    line_number=14,
                ),
            ],
            [
                RepositoryInfo(
                    fqcn="com.example.lms.cfvm.CfvmSnapshotRepository",
                    simple_name="CfvmSnapshotRepository",
                    entity_type="CfvmSnapshot",
                    id_type="Long",
                    file_path="com/example/lms/cfvm/CfvmSnapshotRepository.java",
                    line_number=8,
                ),
            ],
            {},
        )

        self.assertTrue(gap.has_db_persistence)
        self.assertEqual("JPA", gap.persistence_type)
        self.assertEqual("resolved_jpa", gap.persistence_status)
        self.assertFalse(gap.action_required)
        self.assertEqual([], gap.entities_missing)
        self.assertEqual([], gap.repositories_missing)
        self.assertEqual([], gap.duplicate_classes)
        self.assertEqual("LOW", gap.gap_severity)
        residual = residual_volatile_state([gap])
        self.assertEqual(1, len(residual))
        self.assertEqual("S02_CFVM", residual[0]["id"])
        self.assertEqual("resolved_jpa", residual[0]["persistenceStatus"])
        self.assertIn("RawMatrixBuffer", residual[0]["volatileClasses"])
        self.assertIn("reviewAction", residual[0])
        self.assertIn("verify durable sink", residual[0]["reviewAction"])

    def test_markdown_reports_residual_volatile_state_even_when_jpa_resolved(self):
        gap = assess_subsystem(
            "S02_CFVM",
            SUBSYSTEM_SPECS["S02_CFVM"],
            [
                ClassInfo(
                    fqcn="com.example.lms.cfvm.RawMatrixBuffer",
                    simple_name="RawMatrixBuffer",
                    file_path="main/java/com/example/lms/cfvm/RawMatrixBuffer.java",
                    package="com.example.lms.cfvm",
                    volatile_storage=["ArrayDeque"],
                ),
                ClassInfo(
                    fqcn="com.example.lms.cfvm.CfvmFailureRecorder",
                    simple_name="CfvmFailureRecorder",
                    file_path="main/java/com/example/lms/cfvm/CfvmFailureRecorder.java",
                    package="com.example.lms.cfvm",
                ),
                ClassInfo(
                    fqcn="com.example.lms.cfvm.RawSlotExtractor",
                    simple_name="RawSlotExtractor",
                    file_path="main/java/com/example/lms/cfvm/RawSlotExtractor.java",
                    package="com.example.lms.cfvm",
                ),
                ClassInfo(
                    fqcn="com.example.lms.strategy.RetrievalOrderService",
                    simple_name="RetrievalOrderService",
                    file_path="main/java/com/example/lms/strategy/RetrievalOrderService.java",
                    package="com.example.lms.strategy",
                ),
            ],
            [
                EntityInfo(
                    fqcn="com.example.lms.cfvm.CfvmSnapshot",
                    simple_name="CfvmSnapshot",
                    table_name="cfvm_snapshot",
                    file_path="main/java/com/example/lms/cfvm/CfvmSnapshot.java",
                    line_number=14,
                ),
            ],
            [
                RepositoryInfo(
                    fqcn="com.example.lms.cfvm.CfvmSnapshotRepository",
                    simple_name="CfvmSnapshotRepository",
                    entity_type="CfvmSnapshot",
                    id_type="Long",
                    file_path="main/java/com/example/lms/cfvm/CfvmSnapshotRepository.java",
                    line_number=8,
                ),
            ],
            {},
        )

        markdown = generate_markdown([gap], [], [], {}, 4)

        self.assertIn("## Residual Volatile Runtime State (Review)", markdown)
        self.assertIn("S02_CFVM", markdown)
        self.assertIn("RawMatrixBuffer", markdown)
        self.assertIn("resolved_jpa", markdown)
        self.assertIn("Review Action", markdown)
        self.assertIn("verify durable sink", markdown)

    def test_art_plate_evolution_log_satisfies_s03_persistence(self):
        gap = assess_subsystem(
            "S03_MOE",
            SUBSYSTEM_SPECS["S03_MOE"],
            [
                ClassInfo(
                    fqcn="com.example.lms.moe.RgbStrategySelector",
                    simple_name="RgbStrategySelector",
                    file_path="com/example/lms/moe/RgbStrategySelector.java",
                    package="com.example.lms.moe",
                ),
                ClassInfo(
                    fqcn="com.example.lms.artplate.ArtPlateEvolver",
                    simple_name="ArtPlateEvolver",
                    file_path="com/example/lms/artplate/ArtPlateEvolver.java",
                    package="com.example.lms.artplate",
                ),
                ClassInfo(
                    fqcn="com.example.lms.strategy.StrategyPerformance",
                    simple_name="StrategyPerformance",
                    file_path="com/example/lms/strategy/StrategyPerformance.java",
                    package="com.example.lms.strategy",
                    is_entity=True,
                ),
                ClassInfo(
                    fqcn="com.example.lms.artplate.ArtPlateEvolutionLog",
                    simple_name="ArtPlateEvolutionLog",
                    file_path="com/example/lms/artplate/ArtPlateEvolutionLog.java",
                    package="com.example.lms.artplate",
                    is_entity=True,
                ),
            ],
            [
                EntityInfo(
                    fqcn="com.example.lms.strategy.StrategyPerformance",
                    simple_name="StrategyPerformance",
                    table_name="strategy_performance",
                    file_path="com/example/lms/strategy/StrategyPerformance.java",
                    line_number=10,
                ),
                EntityInfo(
                    fqcn="com.example.lms.artplate.ArtPlateEvolutionLog",
                    simple_name="ArtPlateEvolutionLog",
                    table_name="art_plate_evolution_log",
                    file_path="com/example/lms/artplate/ArtPlateEvolutionLog.java",
                    line_number=12,
                ),
            ],
            [
                RepositoryInfo(
                    fqcn="com.example.lms.strategy.StrategyPerformanceRepository",
                    simple_name="StrategyPerformanceRepository",
                    entity_type="StrategyPerformance",
                    id_type="Long",
                    file_path="com/example/lms/strategy/StrategyPerformanceRepository.java",
                    line_number=15,
                ),
                RepositoryInfo(
                    fqcn="com.example.lms.artplate.ArtPlateEvolutionLogRepository",
                    simple_name="ArtPlateEvolutionLogRepository",
                    entity_type="ArtPlateEvolutionLog",
                    id_type="Long",
                    file_path="com/example/lms/artplate/ArtPlateEvolutionLogRepository.java",
                    line_number=8,
                ),
            ],
            {},
        )

        self.assertTrue(gap.has_db_persistence)
        self.assertEqual("resolved_jpa", gap.persistence_status)
        self.assertFalse(gap.action_required)
        self.assertEqual([], gap.entities_missing)
        self.assertEqual([], gap.repositories_missing)
        self.assertNotEqual("CRITICAL", gap.gap_severity)

    def test_deprecated_compatibility_aliases_do_not_count_as_duplicate_blockers(self):
        gap = assess_subsystem(
            "S05_EXTREMEZ",
            SUBSYSTEM_SPECS["S05_EXTREMEZ"],
            [
                ClassInfo(
                    fqcn="com.example.lms.service.rag.burst.ExtremeZSystemHandler",
                    simple_name="ExtremeZSystemHandler",
                    file_path="com/example/lms/service/rag/burst/ExtremeZSystemHandler.java",
                    package="com.example.lms.service.rag.burst",
                ),
                ClassInfo(
                    fqcn="com.example.lms.extreme.ExtremeZSystemHandler",
                    simple_name="ExtremeZSystemHandler",
                    file_path="com/example/lms/extreme/ExtremeZSystemHandler.java",
                    package="com.example.lms.extreme",
                    is_deprecated=True,
                ),
                ClassInfo(
                    fqcn="com.example.lms.nova.extremez.ExtremeZSystemHandler",
                    simple_name="ExtremeZSystemHandler",
                    file_path="com/example/lms/nova/extremez/ExtremeZSystemHandler.java",
                    package="com.example.lms.nova.extremez",
                    is_deprecated=True,
                ),
                ClassInfo(
                    fqcn="com.example.lms.service.rag.ContextOrchestrator",
                    simple_name="ContextOrchestrator",
                    file_path="com/example/lms/service/rag/ContextOrchestrator.java",
                    package="com.example.lms.service.rag",
                ),
            ],
            [],
            [],
            {},
        )

        self.assertEqual([], gap.duplicate_classes)
        self.assertNotEqual("MEDIUM", gap.gap_severity)

    def test_simple_name_duplicates_are_reported_without_inflating_db_gap_severity(self):
        gap = assess_subsystem(
            "S06_HYPERNOVA",
            SUBSYSTEM_SPECS["S06_HYPERNOVA"],
            [
                ClassInfo(
                    fqcn="com.nova.protocol.fusion.CvarAggregator",
                    simple_name="CvarAggregator",
                    file_path="main/java/com/nova/protocol/fusion/CvarAggregator.java",
                    package="com.nova.protocol.fusion",
                ),
                ClassInfo(
                    fqcn="com.example.lms.service.rag.fusion.CvarAggregator",
                    simple_name="CvarAggregator",
                    file_path="app/src/main/java_clean/com/example/lms/service/rag/fusion/CvarAggregator.java",
                    package="com.example.lms.service.rag.fusion",
                ),
                ClassInfo(
                    fqcn="com.nova.protocol.fusion.TailWeightedPowerMeanFuser",
                    simple_name="TailWeightedPowerMeanFuser",
                    file_path="main/java/com/nova/protocol/fusion/TailWeightedPowerMeanFuser.java",
                    package="com.nova.protocol.fusion",
                ),
                ClassInfo(
                    fqcn="com.nova.protocol.alloc.RiskKAllocator",
                    simple_name="RiskKAllocator",
                    file_path="main/java/com/nova/protocol/alloc/RiskKAllocator.java",
                    package="com.nova.protocol.alloc",
                ),
                ClassInfo(
                    fqcn="com.example.lms.service.rag.rerank.DppDiversityReranker",
                    simple_name="DppDiversityReranker",
                    file_path="main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java",
                    package="com.example.lms.service.rag.rerank",
                ),
                ClassInfo(
                    fqcn="com.nova.protocol.fusion.BodeClamp",
                    simple_name="BodeClamp",
                    file_path="main/java/com/nova/protocol/fusion/BodeClamp.java",
                    package="com.nova.protocol.fusion",
                ),
            ],
            [],
            [],
            {},
        )

        self.assertNotEqual([], gap.duplicate_classes)
        self.assertEqual("LOW", gap.gap_severity)
        self.assertIn("duplicate simple names recorded", gap.gap_details)

    def test_runtime_only_subsystem_is_review_not_unresolved_gap(self):
        gap = assess_subsystem(
            "S05_EXTREMEZ",
            SUBSYSTEM_SPECS["S05_EXTREMEZ"],
            [
                ClassInfo(
                    fqcn="com.example.lms.service.rag.burst.ExtremeZSystemHandler",
                    simple_name="ExtremeZSystemHandler",
                    file_path="main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java",
                    package="com.example.lms.service.rag.burst",
                ),
                ClassInfo(
                    fqcn="com.example.lms.service.rag.burst.ExtremeZTrigger",
                    simple_name="ExtremeZTrigger",
                    file_path="main/java/com/example/lms/service/rag/burst/ExtremeZTrigger.java",
                    package="com.example.lms.service.rag.burst",
                ),
            ],
            [],
            [],
            {},
        )

        self.assertEqual("none", gap.persistence_type)
        self.assertEqual("review_runtime_only", gap.persistence_status)
        self.assertFalse(gap.action_required)

    def test_runtime_only_reviewed_subsystem_uses_reviewed_contract_badge(self):
        gap = assess_subsystem(
            "S05_EXTREMEZ",
            SUBSYSTEM_SPECS["S05_EXTREMEZ"],
            [
                ClassInfo(
                    fqcn="com.example.lms.service.rag.burst.ExtremeZSystemHandler",
                    simple_name="ExtremeZSystemHandler",
                    file_path="main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java",
                    package="com.example.lms.service.rag.burst",
                ),
                ClassInfo(
                    fqcn="com.example.lms.service.rag.burst.ExtremeZTrigger",
                    simple_name="ExtremeZTrigger",
                    file_path="main/java/com/example/lms/service/rag/burst/ExtremeZTrigger.java",
                    package="com.example.lms.service.rag.burst",
                ),
            ],
            [],
            [],
            {},
        )

        markdown = generate_markdown([gap], [], [], {}, 2)

        self.assertEqual("[REVIEWED]", persistence_contract_badge(gap))
        self.assertIn("| # | Subsystem | Persistence Contract | Type | Status | Action | Severity | Details |", markdown)
        self.assertIn("| S05_EXTREMEZ | ExtremeZ / Massive Parallel Query Expansion | [REVIEWED] | none | review_runtime_only", markdown)
        self.assertNotIn("| [N] | none | review_runtime_only", markdown)

    def test_markdown_summarizes_supabase_external_snapshot_status(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = {
            "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
            "readOnly": True,
            "mutationAllowed": False,
            "schemaSnapshotAvailable": False,
            "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
            "rawSecretPatternHits": 0,
            "evidence_needed": [
                "Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project",
            ],
        }

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertIn("## External Supabase DB Snapshot Status", markdown)
        self.assertIn("Read-only: `True`", markdown)
        self.assertIn("Mutation allowed: `False`", markdown)
        self.assertIn("Schema snapshot available: `False`", markdown)
        self.assertIn("SQL bundle: `data/db-gap-report/supabase-readonly-snapshot.sql`", markdown)
        self.assertIn("Secret pattern hits: `0`", markdown)
        self.assertIn("project_ref missing", markdown)
        self.assertLess(
            markdown.index("## External Supabase DB Snapshot Status"),
            markdown.index("## Subsystem Gap Matrix"),
        )

    def test_external_snapshot_loads_supabase_readonly_smoke_without_raw_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output_dir = root / "data" / "db-gap-report"
            smoke_dir = root / "var" / "codex-smoke" / "supabase-readonly-snapshot"
            output_dir.mkdir(parents=True)
            smoke_dir.mkdir(parents=True)
            (output_dir / "supabase-schema-snapshot.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                        "readOnly": True,
                        "mutationAllowed": False,
                        "schemaSnapshotAvailable": False,
                        "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                        "rawSecretPatternHits": 0,
                        "evidence_needed": ["Supabase MCP project_ref missing"],
                    }
                ),
                encoding="utf-8",
            )
            (smoke_dir / "supabase-context-probe.json").write_text(
                json.dumps(
                    {
                        "decision": "supabase_context_probe",
                        "ok": True,
                        "projectScope": {"status": "project_ref_missing"},
                        "mcp": {"reachable": True, "httpStatus": 403},
                        "authPlan": {"manualAuthHeaderTemplate": "Bearer ${SUPABASE_ACCESS_TOKEN}"},
                    }
                ),
                encoding="utf-8-sig",
            )
            (smoke_dir / "supabase-schema-snapshot.result.json").write_text(
                json.dumps(
                    {
                        "decision": "supabase_schema_snapshot_evidence_needed",
                        "ok": True,
                        "schemaSnapshotAvailable": False,
                        "mutationAllowed": False,
                        "snapshotPath": "C:/private/supabase-schema-snapshot.json",
                        "snapshotPathHash": "abc123",
                        "snapshotBytes": 12,
                        "rawSecretPatternHits": 0,
                    }
                ),
                encoding="utf-8-sig",
            )
            (smoke_dir / "supabase-schema-snapshot-import.result.json").write_text(
                json.dumps(
                    {
                        "decision": "supabase_schema_snapshot_import_evidence_needed",
                        "ok": True,
                        "schemaSnapshotAvailable": False,
                        "mutationAllowed": False,
                        "resultsPathHash": "def456",
                        "importedResultCount": 0,
                        "rawSecretPatternHits": 0,
                    }
                ),
                encoding="utf-8-sig",
            )
            (smoke_dir / "supabase-schema-snapshot.json").write_text(
                json.dumps(
                    {
                        "readOnly": True,
                        "mutationAllowed": False,
                        "schemaSnapshotAvailable": False,
                    }
                ),
                encoding="utf-8",
            )

            external_snapshot = db_gap_scanner.load_external_supabase_snapshot(output_dir)

        self.assertIsNotNone(external_snapshot)
        self.assertEqual(
            {
                "status": "ok",
                "artifactPresent": True,
                "artifactFileCount": 4,
                "contextDecision": "supabase_context_probe",
                "projectScopeStatus": "project_ref_missing",
                "mcpReachable": True,
                "mcpHttpStatus": 403,
                "snapshotDecision": "supabase_schema_snapshot_evidence_needed",
                "schemaSnapshotAvailable": False,
                "snapshotBytes": 12,
                "importDecision": "supabase_schema_snapshot_import_evidence_needed",
                "importedResultCount": 0,
                "mutationAllowed": False,
                "rawSecretPatternHits": 0,
                "windowsAbsPathHits": 0,
            },
            external_snapshot["readonlySmoke"],
        )

        markdown = generate_markdown(
            [],
            [],
            [],
            {},
            0,
            external_db_snapshot=external_snapshot,
        )

        self.assertIn("Readonly smoke:", markdown)
        self.assertIn("status=ok", markdown)
        self.assertIn("projectScopeStatus=project_ref_missing", markdown)
        self.assertIn("snapshotDecision=supabase_schema_snapshot_evidence_needed", markdown)
        self.assertIn("importDecision=supabase_schema_snapshot_import_evidence_needed", markdown)
        self.assertNotIn("C:/private", markdown)
        self.assertNotIn(str(root), markdown)
        self.assertNotIn("Bearer", markdown)
        self.assertNotIn("Authorization", markdown)

    def test_markdown_summarizes_supabase_collection_packet_without_raw_sql(self):
        with tempfile.TemporaryDirectory() as tmp:
            packet_path = Path(tmp) / "supabase-execute-sql-collection.packet.json"
            packet_path.write_text(
                """{
  "schemaVersion": "awx.mcp.supabase_execute_sql_collection_packet.v1",
  "readOnly": true,
  "mutationAllowed": false,
  "mcpTool": "execute_sql",
  "executionMode": "one_query_per_execute_sql_call",
  "queryCount": 1,
  "nextActions": [
    "execute_each_query_once",
    "run_supabase_schema_snapshot_import"
  ],
  "queries": [
    {
      "name": "schemas_and_tables",
      "sql": "select table_schema, table_name from information_schema.tables",
      "executeSqlInput": {
        "query": "select table_schema, table_name from information_schema.tables"
      }
    }
  ]
}
""",
                encoding="utf-8",
            )
            external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
                {
                    "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                    "readOnly": True,
                    "mutationAllowed": False,
                    "schemaSnapshotAvailable": False,
                    "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                    "collectionPacketPath": str(packet_path),
                    "rawSecretPatternHits": 0,
                    "evidence_needed": [
                        "Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project",
                    ],
                }
            )

        markdown = generate_markdown(
            [],
            [],
            [],
            {},
            0,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual("execute_sql", external_snapshot["collectionPacket"]["mcpTool"])
        self.assertEqual(1, external_snapshot["collectionPacket"]["declaredQueryCount"])
        self.assertEqual(1, external_snapshot["collectionPacket"]["queryCount"])
        self.assertEqual(
            "execute_each_query_once,run_supabase_schema_snapshot_import",
            external_snapshot["collectionPacket"]["nextActions"],
        )
        self.assertEqual(0, external_snapshot["collectionPacket"]["secretPatternHits"])
        self.assertIn("Collection packet:", markdown)
        self.assertIn("mcpTool=execute_sql", markdown)
        self.assertIn("declaredQueryCount=1", markdown)
        self.assertIn("queryCount=1", markdown)
        self.assertIn("execute_each_query_once,run_supabase_schema_snapshot_import", markdown)
        self.assertNotIn("select table_schema", markdown)
        self.assertNotIn("executeSqlInput", markdown)

    def test_markdown_redacts_absolute_supabase_artifact_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            packet_path = Path(tmp) / "supabase-execute-sql-collection.packet.json"
            packet_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.mcp.supabase_execute_sql_collection_packet.v1",
                        "readOnly": True,
                        "mutationAllowed": False,
                        "mcpTool": "execute_sql",
                        "queries": [],
                    }
                ),
                encoding="utf-8",
            )
            sql_bundle_path = str(Path(tmp) / "supabase-readonly-snapshot.sql")
            external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
                {
                    "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                    "readOnly": True,
                    "mutationAllowed": False,
                    "schemaSnapshotAvailable": False,
                    "sqlBundlePath": sql_bundle_path,
                    "collectionPacketPath": str(packet_path),
                    "rawSecretPatternHits": 0,
                    "evidence_needed": [],
                }
            )

        markdown = generate_markdown(
            [],
            [],
            [],
            {},
            0,
            external_db_snapshot=external_snapshot,
        )

        self.assertIn("SQL bundle: `pathHash=", markdown)
        self.assertIn("pathLength=", markdown)
        self.assertIn("Collection packet:", markdown)
        self.assertNotIn(str(Path(tmp)), markdown)
        self.assertNotIn("supabase-readonly-snapshot.sql", markdown)
        self.assertNotIn("supabase-execute-sql-collection.packet.json", markdown)

    def test_markdown_summarizes_supabase_advisors_without_raw_finding_text(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": True,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "advisors": {
                    "available": True,
                    "rows": [
                        {
                            "level": "ERROR",
                            "category": "SECURITY",
                            "name": "rls disabled",
                            "description": "private customer table is exposed",
                        },
                        {
                            "level": "WARN",
                            "category": "PERFORMANCE",
                            "name": "missing index",
                        },
                    ],
                    "collectionPlan": {
                        "mcpTool": "get_advisors",
                        "featureGroup": "debugging",
                    },
                },
                "evidence_needed": [],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(
            {
                "available": True,
                "rowCount": 2,
                "errorCount": 1,
                "warningCount": 1,
                "categories": "PERFORMANCE,SECURITY",
                "collectionTool": "get_advisors",
                "featureGroup": "debugging",
            },
            external_snapshot["advisors"],
        )
        self.assertIn("Advisors:", markdown)
        self.assertIn("rowCount=2", markdown)
        self.assertIn("errorCount=1", markdown)
        self.assertIn("warningCount=1", markdown)
        self.assertIn("categories=PERFORMANCE,SECURITY", markdown)
        self.assertNotIn("private customer", markdown)
        self.assertNotIn("missing index", markdown)

    def test_markdown_marks_missing_supabase_live_snapshot_evidence_status(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                )
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": False,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "projectScope": {
                    "status": "project_ref_missing",
                    "nextAction": "set_project_ref_in_mcp_config",
                    "projectRefPresent": False,
                    "projectRefTemplateMode": True,
                    "readOnlyMode": True,
                    "featuresScopedMode": True,
                    "featureGroups": ["database", "debugging", "docs"],
                    "scopedMcpUrlTemplate": "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=<project_ref>",
                    "rawSecretPatternHits": 0,
                },
                "evidence_needed": [
                    "Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual("missing_live_snapshot", external_snapshot["evidenceStatus"])
        self.assertEqual(
            {
                "status": "project_ref_missing",
                "nextAction": "set_project_ref_in_mcp_config",
                "projectRefPresent": False,
                "projectRefTemplateMode": True,
                "readOnlyMode": True,
                "featuresScopedMode": True,
                "featureGroups": "database,debugging,docs",
                "scopedMcpUrlTemplate": "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=<project_ref>",
                "rawSecretPatternHits": 0,
            },
            external_snapshot["projectScope"],
        )
        self.assertIn("Evidence status: `missing_live_snapshot`", markdown)
        self.assertIn("Project scope:", markdown)
        self.assertIn("nextAction=set_project_ref_in_mcp_config", markdown)
        self.assertIn("projectRefTemplateMode=True", markdown)
        self.assertIn("project_ref missing", markdown)

    def test_markdown_summarizes_supabase_auth_plan_without_raw_token_values(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                )
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": False,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "dbSnapshotPlan": {
                    "docsRefs": [
                        {
                            "id": "api-keys",
                            "url": "https://supabase.com/docs/guides/getting-started/api-keys",
                        },
                    ],
                    "securityContracts": [
                        "secret_keys_backend_only",
                    ],
                },
                "authPlan": {
                    "defaultHostedAuthMode": "dynamic_client_registration_oauth",
                    "supportedAuthModes": ["dynamic_oauth", "manual_ci_pat_auth"],
                    "mcpOAuthRequired": True,
                    "manualAuthMode": "manual_pat_header_for_ci",
                    "manualAuthEnvStatus": [
                        {"name": "SUPABASE_PROJECT_REF", "present": False, "sensitive": False},
                        {"name": "SUPABASE_ACCESS_TOKEN", "present": True, "sensitive": True},
                    ],
                    "securityWarnings": [
                        "development_and_testing_only",
                        "prefer_read_only",
                        "project_scope_required",
                    ],
                    "manualAuthHeaderTemplate": "Bearer ${SUPABASE_ACCESS_TOKEN}",
                },
                "evidence_needed": [
                    "Supabase MCP OAuth or manual PAT auth required before live schema query",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(
            {
                "defaultHostedAuthMode": "dynamic_client_registration_oauth",
                "supportedAuthModes": "dynamic_oauth,manual_ci_pat_auth",
                "mcpOAuthRequired": True,
                "manualAuthMode": "manual_pat_header_for_ci",
                "manualAuthEnvStatus": "SUPABASE_PROJECT_REF:present=False:sensitive=False,SUPABASE_ACCESS_TOKEN:present=True:sensitive=True",
                "manualAuthSensitiveEnvRefs": "SUPABASE_ACCESS_TOKEN",
                "securityWarnings": "development_and_testing_only,prefer_read_only,project_scope_required",
            },
            external_snapshot["authPlan"],
        )
        self.assertEqual(
            {
                "docsRefCount": 1,
                "securityContractCount": 1,
                "apiKeysDocs": True,
                "secretKeysBackendOnly": True,
            },
            external_snapshot["docsContracts"],
        )
        self.assertIn("Docs/contracts:", markdown)
        self.assertIn("apiKeysDocs=True", markdown)
        self.assertIn("secretKeysBackendOnly=True", markdown)
        self.assertIn("Auth plan:", markdown)
        self.assertIn("defaultHostedAuthMode=dynamic_client_registration_oauth", markdown)
        self.assertIn("supportedAuthModes=dynamic_oauth,manual_ci_pat_auth", markdown)
        self.assertIn("mcpOAuthRequired=True", markdown)
        self.assertIn("manualAuthMode=manual_pat_header_for_ci", markdown)
        self.assertIn("SUPABASE_ACCESS_TOKEN:present=True:sensitive=True", markdown)
        self.assertIn("manualAuthSensitiveEnvRefs=SUPABASE_ACCESS_TOKEN", markdown)
        self.assertNotIn("Bearer", markdown)
        self.assertNotIn("fake-token", markdown)

    def test_markdown_preserves_missing_result_counts_for_unfilled_supabase_template(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                )
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": False,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "snapshotImport": {
                    "status": "evidence_needed",
                    "importedResultCount": 0,
                    "storedRawRows": False,
                    "missingResultCount": 12,
                    "missingResultNames": ["schemas_and_tables", "rls_and_table_flags"],
                    "inputShape": {
                        "payloadKind": "object",
                        "supportedResultItemCount": 0,
                        "resultsArrayPresent": False,
                    },
                },
                "evidence_needed": [
                    "results_path is an unfilled result template / copy execute_sql rows into a separate results JSON file",
                ],
                "nextActions": [
                    "set_project_ref_in_mcp_config",
                    "populate_supabase_query_results_file",
                    "rerun_supabase_schema_snapshot_import",
                    "rerun_db_gap_scanner",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(12, external_snapshot["snapshotImport"]["missingResultCount"])
        self.assertEqual(
            "schemas_and_tables,rls_and_table_flags",
            external_snapshot["snapshotImport"]["missingResultNames"],
        )
        self.assertEqual(
            [
                "set_project_ref_in_mcp_config",
                "populate_supabase_query_results_file",
                "rerun_supabase_schema_snapshot_import",
                "rerun_db_gap_scanner",
            ],
            external_snapshot["nextActions"],
        )
        self.assertIn("missingResultCount=12", markdown)
        self.assertIn("missingResultNames=schemas_and_tables,rls_and_table_flags", markdown)
        self.assertIn("unfilled result template", markdown)
        self.assertIn("Next actions:", markdown)
        self.assertIn("rerun_supabase_schema_snapshot_import", markdown)

    def test_markdown_marks_not_yet_imported_supabase_snapshot_as_evidence_needed(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                )
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": False,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "resultTemplatePath": "data/db-gap-report/supabase-query-results.template.json",
                "dbSnapshotPlan": {
                    "sqlQueries": [
                        {"name": "schemas_and_tables", "sql": "select 1"},
                        {"name": "rls_and_table_flags", "sql": "select 2"},
                    ],
                },
                "evidence_needed": [
                    "Supabase MCP endpoint auth required / complete OAuth MCP client flow before SQL/project probes",
                    "Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project",
                ],
                "nextActions": [
                    "populate_supabase_query_results_file",
                    "rerun_supabase_schema_snapshot_import",
                    "rerun_db_gap_scanner",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(
            {
                "status": "evidence_needed",
                "importedCount": 0,
                "storedRawRows": False,
                "missingResultCount": 2,
                "missingResultNames": "schemas_and_tables,rls_and_table_flags",
            },
            external_snapshot["snapshotImport"],
        )
        self.assertEqual(
            {
                "payloadKind": "not_imported",
                "supportedResultItemCount": 0,
                "resultsArrayPresent": False,
            },
            external_snapshot["inputShape"],
        )
        self.assertIn("Snapshot import:", markdown)
        self.assertIn("status=evidence_needed", markdown)
        self.assertIn("Input shape:", markdown)
        self.assertIn("payloadKind=not_imported", markdown)
        self.assertIn("missingResultCount=2", markdown)
        self.assertIn("missingResultNames=schemas_and_tables,rls_and_table_flags", markdown)

    def test_markdown_summarizes_supabase_imported_risk_summary(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": True,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "riskSummary": {
                    "exposedTablesWithoutRls": 2,
                    "viewsMissingSecurityInvoker": 1,
                    "exposedSecurityDefinerFunctions": 1,
                },
                "evidence_needed": [],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(2, external_snapshot["riskSummary"]["exposedTablesWithoutRls"])
        self.assertIn("Risk summary:", markdown)
        self.assertIn("exposedTablesWithoutRls=2", markdown)
        self.assertIn("viewsMissingSecurityInvoker=1", markdown)
        self.assertIn("exposedSecurityDefinerFunctions=1", markdown)

    def test_markdown_summarizes_supabase_import_input_shape_without_raw_keys(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": False,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "snapshotImport": {
                    "status": "evidence_needed",
                    "inputShape": {
                        "payloadKind": "object",
                        "topLevelKeyCount": 1,
                        "resultsArrayPresent": False,
                        "supportedResultItemCount": 0,
                        "rawTopLevelKeys": ["execute_sql_result"],
                        "message": "authorization header should not render",
                    },
                },
                "evidence_needed": [
                    "results_path contained no supported Supabase query result rows / export named results with rows or data",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(
            {
                "payloadKind": "object",
                "topLevelKeyCount": 1,
                "resultsArrayPresent": False,
                "supportedResultItemCount": 0,
            },
            external_snapshot["inputShape"],
        )
        self.assertIn("Input shape:", markdown)
        self.assertIn("payloadKind=object", markdown)
        self.assertIn("supportedResultItemCount=0", markdown)
        self.assertNotIn("execute_sql_result", markdown)
        self.assertNotIn("Authorization", markdown)

    def test_markdown_summarizes_supabase_import_status_without_raw_rows(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": True,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "snapshotImport": {
                    "status": "imported",
                    "importedCount": 5,
                    "resultsPath": "C:/private/supabase/results.json",
                    "rawRows": [{"table_name": "private_table"}],
                },
                "evidence_needed": [],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(
            {"status": "imported", "importedCount": 5},
            external_snapshot["snapshotImport"],
        )
        self.assertIn("Snapshot import:", markdown)
        self.assertIn("status=imported", markdown)
        self.assertIn("importedCount=5", markdown)
        self.assertNotIn("results.json", markdown)
        self.assertNotIn("private_table", markdown)

    def test_markdown_summarizes_supabase_import_result_count_alias(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": False,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "snapshotImport": {
                    "status": "evidence_needed",
                    "importedResultCount": 0,
                    "storedRawRows": False,
                    "resultsPathHash": "abc123",
                    "resultsPath": "C:/private/supabase/template.json",
                    "rawRows": [{"table_name": "private_table"}],
                },
                "evidence_needed": [
                    "results_path is an unfilled result template / copy execute_sql rows into a separate results JSON file",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(
            {"status": "evidence_needed", "importedCount": 0, "storedRawRows": False},
            external_snapshot["snapshotImport"],
        )
        self.assertIn("Snapshot import:", markdown)
        self.assertIn("status=evidence_needed", markdown)
        self.assertIn("importedCount=0", markdown)
        self.assertIn("storedRawRows=False", markdown)
        self.assertNotIn("template.json", markdown)
        self.assertNotIn("private_table", markdown)

    def test_markdown_summarizes_supabase_missing_result_sets_without_raw_rows(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": True,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "snapshotImport": {
                    "status": "imported",
                    "importedResultCount": 1,
                    "storedRawRows": False,
                    "missingResultCount": 2,
                    "missingResultNames": [
                        "rls_and_table_flags",
                        "exposed_tables_without_rls",
                    ],
                    "rawRows": [{"table_name": "private_table"}],
                },
                "evidence_needed": [
                    "missing expected Supabase result sets: rls_and_table_flags, exposed_tables_without_rls",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(
            {
                "status": "imported",
                "importedCount": 1,
                "storedRawRows": False,
                "missingResultCount": 2,
                "missingResultNames": "rls_and_table_flags,exposed_tables_without_rls",
            },
            external_snapshot["snapshotImport"],
        )
        self.assertIn("missingResultCount=2", markdown)
        self.assertIn("missingResultNames=rls_and_table_flags,exposed_tables_without_rls", markdown)
        self.assertIn("missing expected Supabase result sets", markdown)
        self.assertNotIn("private_table", markdown)

    def test_markdown_marks_partial_supabase_import_as_partial_live_snapshot(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": True,
                "schemaSnapshotComplete": False,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "snapshotImport": {
                    "status": "partial_evidence_needed",
                    "importedResultCount": 4,
                    "storedRawRows": False,
                    "resultSetComplete": False,
                    "missingResultCount": 8,
                    "missingResultNames": ["table_columns", "rls_and_table_flags"],
                    "rawRows": [{"table_name": "private_table"}],
                },
                "evidence_needed": [
                    "missing expected Supabase result sets: table_columns, rls_and_table_flags",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual("partial_live_snapshot", external_snapshot["evidenceStatus"])
        self.assertIn("Evidence status: `partial_live_snapshot`", markdown)
        self.assertIn("status=partial_evidence_needed", markdown)
        self.assertIn("missingResultCount=8", markdown)
        self.assertNotIn("private_table", markdown)

    def test_markdown_summarizes_supabase_unexpected_result_sets_without_raw_rows(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": True,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "snapshotImport": {
                    "status": "imported",
                    "importedResultCount": 13,
                    "storedRawRows": False,
                    "unexpectedResultCount": 1,
                    "unexpectedResultNames": ["typo_extra_probe"],
                    "rawRows": [{"table_name": "private_table"}],
                },
                "evidence_needed": [
                    "unexpected Supabase result sets: typo_extra_probe",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(
            {
                "status": "imported",
                "importedCount": 13,
                "storedRawRows": False,
                "unexpectedResultCount": 1,
                "unexpectedResultNames": "typo_extra_probe",
            },
            external_snapshot["snapshotImport"],
        )
        self.assertIn("unexpectedResultCount=1", markdown)
        self.assertIn("unexpectedResultNames=typo_extra_probe", markdown)
        self.assertIn("unexpected Supabase result sets", markdown)
        self.assertNotIn("private_table", markdown)

    def test_markdown_summarizes_supabase_duplicate_result_sets_without_raw_rows(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": True,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "snapshotImport": {
                    "status": "imported",
                    "importedResultCount": 13,
                    "storedRawRows": False,
                    "inputShape": {
                        "payloadKind": "object",
                        "duplicateResultItemCount": 1,
                    },
                    "duplicateResultCount": 1,
                    "duplicateResultNames": ["schemas_and_tables"],
                    "rawRows": [{"table_name": "private_table"}],
                },
                "evidence_needed": [
                    "duplicate Supabase result sets: schemas_and_tables",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(
            {
                "status": "imported",
                "importedCount": 13,
                "storedRawRows": False,
                "duplicateResultCount": 1,
                "duplicateResultNames": "schemas_and_tables",
            },
            external_snapshot["snapshotImport"],
        )
        self.assertIn("duplicateResultCount=1", markdown)
        self.assertIn("duplicateResultNames=schemas_and_tables", markdown)
        self.assertIn("duplicateResultItemCount=1", markdown)
        self.assertIn("duplicate Supabase result sets", markdown)
        self.assertNotIn("private_table", markdown)

    def test_markdown_summarizes_supabase_mcp_error_codes_without_raw_error_text(self):
        gap = assess_subsystem(
            "S08_OPENAI_ADAPTER",
            SUBSYSTEM_SPECS["S08_OPENAI_ADAPTER"],
            [
                ClassInfo(
                    fqcn="com.example.lms.boot.VersionPurityHealthIndicator",
                    simple_name="VersionPurityHealthIndicator",
                    file_path="main/java/com/example/lms/boot/VersionPurityHealthIndicator.java",
                    package="com.example.lms.boot",
                ),
            ],
            [],
            [],
            {},
        )
        external_snapshot = db_gap_scanner.summarize_external_supabase_snapshot(
            {
                "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                "readOnly": True,
                "mutationAllowed": False,
                "schemaSnapshotAvailable": False,
                "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "rawSecretPatternHits": 0,
                "snapshotImport": {
                    "status": "evidence_needed",
                    "importedResultCount": 0,
                    "storedRawRows": False,
                    "mcpErrorCount": 2,
                    "mcpErrorCodes": ["PGRST301", "-32000"],
                    "rawErrorText": "permission denied for table private_notes",
                },
                "evidence_needed": [
                    "Supabase MCP returned error transcripts: PGRST301, -32000",
                ],
            }
        )

        markdown = generate_markdown(
            [gap],
            [],
            [],
            {},
            1,
            external_db_snapshot=external_snapshot,
        )

        self.assertEqual(
            {
                "status": "evidence_needed",
                "importedCount": 0,
                "storedRawRows": False,
                "mcpErrorCount": 2,
                "mcpErrorCodes": "PGRST301,-32000",
            },
            external_snapshot["snapshotImport"],
        )
        self.assertEqual("mcp_error_transcript", external_snapshot["evidenceStatus"])
        self.assertIn("Evidence status: `mcp_error_transcript`", markdown)
        self.assertIn("mcpErrorCount=2", markdown)
        self.assertIn("mcpErrorCodes=PGRST301,-32000", markdown)
        self.assertIn("Supabase MCP returned error transcripts", markdown)
        self.assertNotIn("private_notes", markdown)
        self.assertNotIn("permission denied", markdown)


if __name__ == "__main__":
    unittest.main()
