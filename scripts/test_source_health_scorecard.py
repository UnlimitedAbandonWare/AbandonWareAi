import importlib.util
import contextlib
import copy
import datetime as dt
import hashlib
import io
import json
import os
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(SCRIPTS))
REPORT_PATH = ROOT / "scripts" / "source_health_scorecard.py"
WAVE_REGISTRY_PATH = ROOT / "verification/structural-repair-waves/registry.json"
WAVE_ONE_PROOF_ROOT = (
    ROOT / ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave"
)
SPEC = importlib.util.spec_from_file_location("source_health_scorecard", REPORT_PATH)
source_health_scorecard = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(source_health_scorecard)
from scripts import test_dynamic_rag_quant_audit as audit_fixture


def _current_utc_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def _json_sha256(data: object) -> str:
    return hashlib.sha256(
        json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _canonical_json_bytes(data: object) -> bytes:
    return (
        json.dumps(
            data,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


@contextlib.contextmanager
def _fixture_registry_pin(registry_sha256: str | None):
    if (
        not isinstance(registry_sha256, str)
        or re.fullmatch(r"[0-9a-f]{64}", registry_sha256) is None
    ):
        raise AssertionError("fixture-registry-sha256-required")
    with mock.patch.object(
        audit_fixture.audit,
        "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
        registry_sha256,
    ):
        yield


def _empty_closure_history_summary(root: Path) -> dict[str, object]:
    registry_path = root / "verification/structural-repair-waves/registry.json"
    registry_bytes = registry_path.read_bytes()
    registry = json.loads(registry_bytes.decode("utf-8"))
    journal_sha256 = hashlib.sha256(b"").hexdigest()
    journal_rows = [
        [descriptor["waveId"], descriptor["journalPath"], journal_sha256]
        for descriptor in registry["waves"]
    ]
    return {
        "schemaVersion": "awx.structural-repair-history-summary.v2",
        "waveRegistrySha256": hashlib.sha256(registry_bytes).hexdigest(),
        "waveCount": len(journal_rows),
        "journalSetSha256": hashlib.sha256(
            _canonical_json_bytes(journal_rows)
        ).hexdigest(),
        "proofSetSha256": hashlib.sha256(_canonical_json_bytes([])).hexdigest(),
        "eventCount": 0,
        "rejectedFalsePositiveRootCauseGroups": 0,
        "verifiedClosedRootCauseGroups": 0,
    }


def _seed_empty_registered_history(
    root: Path,
    *,
    descriptors: list[dict[str, object]] | None = None,
    source_root: Path = ROOT,
) -> str:
    registry = root / "verification/structural-repair-waves/registry.json"
    registry.parent.mkdir(parents=True, exist_ok=True)
    registry_value = {
        "schemaVersion": audit_fixture.REGISTRY_SCHEMA,
        "waves": copy.deepcopy(
            descriptors
            if descriptors is not None
            else [
                audit_fixture.WAVE_ONE_DESCRIPTOR,
                audit_fixture.WAVE_TWO_DESCRIPTOR,
            ]
        ),
    }
    registry_bytes = _canonical_json_bytes(registry_value)
    registry.write_bytes(registry_bytes)
    for descriptor in registry_value["waves"]:
        journal = root / str(descriptor["journalPath"])
        journal.parent.mkdir(parents=True, exist_ok=True)
        journal.write_bytes(b"")
        fixture_proof_root = root / str(descriptor["proofRoot"])
        source_proof_root = source_root / str(descriptor["proofRoot"])
        for relative in (
            audit_fixture.audit.INTAKE_SUMMARY_RELATIVE,
            audit_fixture.audit.INTAKE_ROWS_RELATIVE,
            audit_fixture.audit.TARGET_PREIMAGES_RELATIVE,
            audit_fixture.audit.PROGRESS_RELATIVE,
        ):
            target = fixture_proof_root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((source_proof_root / relative).read_bytes())
        summary_bytes = (
            source_proof_root / audit_fixture.audit.INTAKE_SUMMARY_RELATIVE
        ).read_bytes()
        summary = json.loads(summary_bytes.decode("utf-8"))
        if _canonical_json_bytes(summary) != summary_bytes:
            raise AssertionError("fixture-intake-summary-not-canonical")
        if summary.get("schemaVersion") in (
            audit_fixture.audit.CLOSURE_INTAKE_V2_SCHEMA,
            audit_fixture.audit.CLOSURE_INTAKE_V3_SCHEMA,
        ):
            for relative in (
                audit_fixture.audit.ADMISSION_DECISION_RELATIVE,
                audit_fixture.audit.DUPLICATE_EVIDENCE_RELATIVE,
            ):
                target = fixture_proof_root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((source_proof_root / relative).read_bytes())
        elif summary.get("schemaVersion") != audit_fixture.audit.CLOSURE_INTAKE_SCHEMA:
            raise AssertionError("fixture-intake-schema-unknown")
    return hashlib.sha256(registry_bytes).hexdigest()


def _write_linked_quant_bundle(
    verification: Path,
    generated_at: str,
    *,
    closure_summary: dict[str, object] | None = None,
    duplicate_collision_count: int = 0,
    include_empty_reviewed_duplicate_wave_three: bool = False,
    include_empty_selfask_reviewed_wave_four: bool = False,
) -> str | None:
    root = verification.parent
    inputs = root / "audit-inputs"
    inputs.mkdir(parents=True, exist_ok=True)
    audit_fixture.write_java(
        root,
        "main/java/fixture/RootMain.java",
        3,
        audit_fixture.java_body("RootMain"),
    )
    audit_fixture.write_java(
        root,
        "app/src/main/java_clean/fixture/AppMain.java",
        3,
        audit_fixture.java_body("AppMain"),
    )
    fx = audit_fixture.FixturePaths(
        root=root,
        git_head=inputs / "git-head.txt",
        git_branch=inputs / "git-branch.txt",
        git_paths=inputs / "git-paths.z",
        git_status=inputs / "git-status.z",
        harmony=inputs / "harmony.json",
        test_tree=inputs / "test-tree.json",
        duplicate_fqcn=inputs / "dup-fqcn.json",
        closure_registry=root / audit_fixture.REGISTRY_RELATIVE,
        closure_journal=root / "verification/structural-repair-closure-journal.jsonl",
        closure_proof_root=(
            root / ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave"
        ),
        metrics=verification / "dynamic-rag-quant-audit-metrics.json",
        baseline=verification / "structural-design-baseline.json",
        ledger=verification / "structural-design-debt-ledger.jsonl",
    )
    fx.git_head.write_text(
        "0123456789abcdef0123456789abcdef01234567\n",
        encoding="utf-8",
    )
    fx.git_branch.write_text("codex/fixture\n", encoding="utf-8")
    fx.git_status.write_bytes(b"")
    registry_sha256 = _seed_empty_registered_history(root)
    now = dt.datetime.fromisoformat(generated_at.replace("Z", "+00:00"))
    audit_fixture.write_harmony_input(fx, generated_at=now)
    audit_fixture.write_test_tree_input(fx, generated_at=now)
    generated_count = min(10, duplicate_collision_count)
    duplicate_rows = [
        audit_fixture.duplicate_row(index, "GENERATED_EXCLUDE")
        for index in range(generated_count)
    ] + [
        audit_fixture.duplicate_row(index, "HARD_EXCLUDE")
        for index in range(generated_count, duplicate_collision_count)
    ]
    for index, row in enumerate(duplicate_rows):
        class_name = f"Dup{index:04d}"
        audit_fixture.write_java(
            root,
            str(row["rootPath"]),
            3,
            audit_fixture.java_body(class_name),
        )
        audit_fixture.write_java(
            root,
            str(row["appPath"]),
            3,
            audit_fixture.java_body(class_name),
        )
    audit_fixture.write_duplicate_input(
        fx,
        rows=duplicate_rows,
        generated_at=now,
    )
    caller_duplicate_report_bytes = fx.duplicate_fqcn.read_bytes()
    audit_fixture.refresh_git_paths(fx)
    if (
        include_empty_reviewed_duplicate_wave_three
        or include_empty_selfask_reviewed_wave_four
    ):
        registry_sha256, _ = audit_fixture.install_empty_reviewed_duplicate_wave_three(
            fx,
            now=now,
        )
        fx.duplicate_fqcn.write_bytes(caller_duplicate_report_bytes)
    if include_empty_selfask_reviewed_wave_four:
        registry_sha256, _ = audit_fixture.install_empty_selfask_reviewed_admission_wave_four(
            fx,
            now=now,
        )
        fx.duplicate_fqcn.write_bytes(caller_duplicate_report_bytes)
    with _fixture_registry_pin(registry_sha256):
        bundle = audit_fixture.audit.build_audit(audit_fixture.make_inputs(fx), now)

    def preserve_green_scorecard_metrics(metrics: dict[str, object]) -> None:
        metrics["activeJavaLocP95"] = 0.0
        metrics["harmonyPressureSummary"].update(
            {
                "aspectFiles": 10,
                "aspectOrderCoverageApprox": 1.0,
                "criticalUnorderedAspectCount": 0,
                "crossSubsystemLargeFilesOver1000": 31,
                "broadCatchWithoutLocalBreadcrumbRatio": 0.0,
            }
        )

    bundle = audit_fixture.relink_after_metrics_mutation(
        bundle,
        preserve_green_scorecard_metrics,
    )
    if closure_summary is not None:
        bundle = audit_fixture.relink_after_metrics_mutation(
            bundle,
            lambda metrics: metrics.update(
                {"closureHistorySummary": copy.deepcopy(closure_summary)}
            ),
        )
    else:
        with _fixture_registry_pin(registry_sha256):
            audit_fixture.audit.validate_bundle(bundle)
    fx.baseline.write_bytes(_canonical_json_bytes(bundle.baseline))
    fx.ledger.write_bytes(
        b"".join(_canonical_json_bytes(row) for row in bundle.ledger_rows)
    )
    fx.metrics.write_bytes(_canonical_json_bytes(bundle.metrics))
    return registry_sha256


def _write_green_source_runtime_fixture(
    root: Path,
    *,
    closure_summary: dict[str, object] | None = None,
    duplicate_collision_count: int = 0,
    include_empty_reviewed_duplicate_wave_three: bool = False,
    include_empty_selfask_reviewed_wave_four: bool = False,
) -> str | None:
    verification = root / "verification"
    verification.mkdir(parents=True)
    generated_at = _current_utc_iso()
    registry_sha256 = _write_linked_quant_bundle(
        verification,
        generated_at,
        closure_summary=closure_summary,
        duplicate_collision_count=duplicate_collision_count,
        include_empty_reviewed_duplicate_wave_three=(
            include_empty_reviewed_duplicate_wave_three
        ),
        include_empty_selfask_reviewed_wave_four=(
            include_empty_selfask_reviewed_wave_four
        ),
    )
    (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
        json.dumps({"generatedAt": generated_at, "runtimeCrossSubsystemLargeFilesOver1000": 28}),
        encoding="utf-8",
    )
    (verification / "test-tree-contamination-metrics.json").write_text(
        json.dumps(
            {
                "generatedAt": generated_at,
                "riskScore": 0.0,
                "missingImportCount": 0,
                "affectedTestFileCount": 0,
            }
        ),
        encoding="utf-8",
    )
    websoak = verification / "websoak-kpi-smoke"
    websoak.mkdir(parents=True)
    (websoak / "websoak-kpi-provider-disabled.json").write_text(
        json.dumps(
            {
                "generatedAt": generated_at,
                "summary": {
                    "status": 200,
                    "providerDisabledOrSkipped": True,
                    "secretPatternHits": 0,
                    "rawQueryHits": 0,
                },
            }
        ),
        encoding="utf-8",
    )
    contract_file = root / "src/test/java/ai/abandonware/nova/orch/aop/AspectOrderingContractTest.java"
    contract_file.parent.mkdir(parents=True)
    contract_file.write_text("ExtremeZBurstAspect RagCompressionAspect LlmRouterAspect", encoding="utf-8")
    test_results = root / "build" / "desktop-codex" / "test-results" / "test"
    test_results.mkdir(parents=True)
    for class_name in (
        "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
        "com.example.lms.orchestration.StrategyConflictResolverTest",
        "com.example.lms.orchestration.ExecutionPlanApplierTest",
        "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
        "com.example.lms.service.ChatWorkflowTraceRedactionContractTest",
    ):
        (test_results / f"TEST-{class_name}.xml").write_text(
            f'<testsuite name="{class_name}" tests="2" skipped="0" failures="0" errors="0">'
            f'<testcase classname="{class_name}" name="contractA"/>'
            f'<testcase classname="{class_name}" name="contractB"/></testsuite>',
            encoding="utf-8",
        )
    return registry_sha256


def _write_green_boot_log(root: Path) -> None:
    log = root / "logs" / "verify_boot_source_runtime_current.log"
    log.parent.mkdir(parents=True)
    log.write_text(
        "\n".join(
            [
                "[AWX][verify] intentional-timeout-stop=true timeoutSeconds=45",
                (
                    "[AWX][verify] webServerStartedProven=True wiringPrecheckProven=True "
                    "runtimePrecheckProven=True springStartedLogProven=False "
                    "applicationReadyEventProven=True bootStartedProven=True "
                    "bootSuccessProven=False probeMode=boot-started-proven"
                ),
                "[AWX][verify] bootSuccessProven=False",
            ]
        ),
        encoding="utf-8",
    )


def _scorecard_output_sentinels(root: Path) -> dict[Path, bytes]:
    sentinels = {
        root / "verification/source-health-scorecard.json": b"prior-scorecard",
        root / "verification/source-health-failure-pattern-events.ndjson": b"prior-events",
        root / "verification/source-health-patchdrop-manifest-contract.json": b"prior-manifest",
    }
    for path, payload in sentinels.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)
    return sentinels


def _relink_quant_bundle_after_metrics_mutation(root: Path, mutate) -> None:
    verification = root / "verification"
    baseline_path = verification / "structural-design-baseline.json"
    ledger_path = verification / "structural-design-debt-ledger.jsonl"
    metrics_path = verification / "dynamic-rag-quant-audit-metrics.json"
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    ledger_rows = [
        json.loads(line)
        for line in ledger_path.read_text(encoding="utf-8").splitlines()
        if line
    ]
    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))

    baseline_payload = copy.deepcopy(baseline)
    metrics_payload = copy.deepcopy(metrics)
    for field in ("generatedAt", "auditRunId", "artifactLinks"):
        baseline_payload.pop(field)
        metrics_payload.pop(field)
    metrics_payload.pop("semanticArtifactHash")
    ledger_core_rows = []
    for row in ledger_rows:
        core = copy.deepcopy(row)
        for field in ("generatedAt", "auditRunId", "artifactLinks"):
            core.pop(field)
        ledger_core_rows.append(core)

    mutate(metrics_payload)
    links = {
        "baselinePayloadSha256": hashlib.sha256(
            _canonical_json_bytes(baseline_payload)
        ).hexdigest(),
        "ledgerPayloadSha256": hashlib.sha256(
            b"".join(_canonical_json_bytes(row) for row in ledger_core_rows)
        ).hexdigest(),
        "metricsPayloadSha256": hashlib.sha256(
            _canonical_json_bytes(metrics_payload)
        ).hexdigest(),
    }
    closure = metrics_payload["closureHistorySummary"]
    run_id = hashlib.sha256(
        (
            f"{source_health_scorecard.STRUCTURAL_AUDIT_SCHEMA}|"
            f"{metrics_payload['baselineId']}|{links['baselinePayloadSha256']}|"
            f"{links['ledgerPayloadSha256']}|{links['metricsPayloadSha256']}|"
            f"{closure['waveRegistrySha256']}|{closure['journalSetSha256']}|"
            f"{closure['proofSetSha256']}"
        ).encode("utf-8")
    ).hexdigest()
    for envelope in (baseline, *ledger_rows, metrics):
        envelope["artifactLinks"] = copy.deepcopy(links)
        envelope["auditRunId"] = run_id
    metrics.update(metrics_payload)
    metrics["semanticArtifactHash"] = links["metricsPayloadSha256"]

    baseline_path.write_bytes(_canonical_json_bytes(baseline))
    ledger_path.write_bytes(
        b"".join(_canonical_json_bytes(row) for row in ledger_rows)
    )
    metrics_path.write_bytes(_canonical_json_bytes(metrics))


def _run_canonical_scorecard_main(root: Path) -> tuple[int, str, str]:
    stderr = io.StringIO()
    stdout = io.StringIO()
    with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(stdout):
        result = source_health_scorecard.main(
            [
                "--root",
                str(root),
                "--output",
                "verification/source-health-scorecard.json",
            ]
        )
    return result, stdout.getvalue(), stderr.getvalue()


def _write_desktop_only_goal_next_status(root: Path) -> None:
    smoke_dir = root / "var" / "codex-smoke"
    smoke_dir.mkdir(parents=True, exist_ok=True)
    (smoke_dir / "goal-next-auto.status.json").write_text(
        json.dumps(
            {
                "schemaVersion": "awx.goal_next_auto.status.v1",
                "generatedAt": _current_utc_iso(),
                "latestDecision": "evidence_needed",
                "statusDecision": "evidence_needed",
                "failureClassification": "evidence_needed",
                "staleLatest": False,
                "firstAction": "",
                "firstActionSource": "",
                "externalInputGate": {
                    "status": "local_or_unknown",
                    "source": "",
                    "action": "",
                    "localPatchJustified": True,
                    "mutationAllowed": False,
                    "evidenceNeeded": [],
                    "secretHits": 0,
                    "windowsAbsPathHits": 0,
                },
                "secretHits": 0,
            }
        ),
        encoding="utf-8",
    )


class SourceHealthScorecardTest(unittest.TestCase):

    def test_source_health_and_fixtures_share_dynamic_audit_module_identity(self):
        """Catches pin mocks targeting a different dynamic-audit module object."""
        for owner in (
            "load_closure_registry",
            "load_closure_history",
            "load_and_validate_current_bundle",
        ):
            with self.subTest(owner=owner):
                self.assertIs(
                    getattr(audit_fixture.audit, owner),
                    getattr(source_health_scorecard, owner),
                )

    def test_artifact_freshness_treats_naive_timestamp_as_system_local_time(self):
        real_datetime = dt.datetime
        local_timezone = dt.timezone(dt.timedelta(hours=9))

        class LocalDateTime(real_datetime):
            @classmethod
            def now(cls, tz=None):
                fixed_utc = real_datetime(2026, 8, 30, 11, 0, tzinfo=dt.timezone.utc)
                return fixed_utc.astimezone(tz) if tz is not None else fixed_utc.astimezone(local_timezone)

            def astimezone(self, tz=None):
                if self.tzinfo is None:
                    localized = self.replace(tzinfo=local_timezone)
                    return real_datetime.astimezone(localized, tz)
                return real_datetime.astimezone(self, tz)

        with mock.patch.object(source_health_scorecard._dt, "datetime", LocalDateTime):
            present, fresh, age_seconds, status = source_health_scorecard._artifact_freshness(
                "2026-08-30T20:00:00",
                60,
            )

        self.assertTrue(present)
        self.assertTrue(fresh)
        self.assertEqual(0, age_seconds)
        self.assertEqual("current", status)

    def test_scorecard_reports_stale_canonical_input_without_hiding_it_under_fresh_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            quant_path = root / source_health_scorecard.DEFAULT_QUANT_METRICS
            quant = json.loads(quant_path.read_text(encoding="utf-8"))
            quant["generatedAt"] = "2000-01-01T00:00:00+00:00"
            quant_path.write_text(json.dumps(quant), encoding="utf-8")

            data = source_health_scorecard.build_scorecard(root)

        output_time = dt.datetime.fromisoformat(data["generatedAt"].replace("Z", "+00:00"))
        self.assertLess((dt.datetime.now(dt.timezone.utc) - output_time).total_seconds(), 10)
        freshness = data["inputFreshness"]
        self.assertTrue(freshness["quantMetrics"]["present"])
        self.assertFalse(freshness["quantMetrics"]["fresh"])
        self.assertEqual("stale", freshness["quantMetrics"]["status"])
        self.assertEqual("stale", freshness["quantMetrics"]["reason"])
        self.assertTrue(freshness["harmonyPressure"]["fresh"])
        components = {row["id"]: row for row in data["componentScores"]}
        for component_id in (
            "source_integrity",
            "runtime_provider_safety",
            "harmony_pressure",
            "test_tree_reliability",
            "supabase_external_evidence",
            "metric_integrity",
            "scope_maintainability",
        ):
            self.assertEqual(0.0, components[component_id]["normalized"], component_id)
            self.assertIn("metric-input-invalid", components[component_id]["evidence"])
        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("input_freshness:quantMetrics", evidence_items)

    def test_scorecard_marks_missing_test_tree_input_and_zeros_only_dependent_metrics(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            (root / source_health_scorecard.DEFAULT_TEST_TREE_METRICS).unlink()

            data = source_health_scorecard.build_scorecard(root)

        freshness = data["inputFreshness"]["testTreeContamination"]
        self.assertFalse(freshness["present"])
        self.assertFalse(freshness["fresh"])
        self.assertEqual("missing", freshness["status"])
        components = {row["id"]: row for row in data["componentScores"]}
        self.assertEqual(0.0, components["test_tree_reliability"]["normalized"])
        self.assertEqual(0.0, components["metric_integrity"]["normalized"])
        self.assertGreater(components["source_integrity"]["normalized"], 0.0)
        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("input_freshness:testTreeContamination", evidence_items)

    def test_scorecard_marks_malformed_harmony_input_instead_of_raising(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            (root / source_health_scorecard.DEFAULT_HARMONY_METRICS).write_text(
                "{malformed-json",
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        freshness = data["inputFreshness"]["harmonyPressure"]
        self.assertTrue(freshness["present"])
        self.assertFalse(freshness["fresh"])
        self.assertEqual("malformed", freshness["status"])
        components = {row["id"]: row for row in data["componentScores"]}
        self.assertEqual(0.0, components["harmony_pressure"]["normalized"])
        self.assertIn("metric-input-invalid", components["harmony_pressure"]["evidence"])
        self.assertEqual(0.0, components["metric_integrity"]["normalized"])
    def test_supabase_live_proof_details_accept_supporting_evidence_actions(self):
        report = {
            "nextActionDetails": [],
            "supportingEvidenceNextActionDetails": [
                {
                    "action": "collect-supabase-live-proof",
                    "nodeRole": "desktop",
                    "targetService": "supabase",
                    "readOnly": True,
                    "mutationAllowed": False,
                    "requiredEnv": [{"name": "SUPABASE_PROJECT_REF", "sensitive": False}],
                    "supportedAuthModes": [
                        "supabase_mcp_oauth_session",
                        "manual_SUPABASE_ACCESS_TOKEN",
                    ],
                    "manualAuthSensitiveEnvRefs": ["SUPABASE_ACCESS_TOKEN"],
                    "mcpOAuthSupported": True,
                    "requiredMcpTools": ["execute_sql", "get_advisors"],
                    "requiredResultNames": ["schemas_and_tables"],
                    "nextActions": ["set_SUPABASE_PROJECT_REF"],
                    "decision": "evidence_needed",
                }
            ],
        }

        details = source_health_scorecard._safe_supabase_live_proof_details(report)

        self.assertEqual(1, len(details))
        detail = details[0]
        self.assertEqual("collect-supabase-live-proof", detail["action"])
        self.assertTrue(detail["readOnly"])
        self.assertFalse(detail["mutationAllowed"])
        self.assertEqual([{"name": "SUPABASE_PROJECT_REF", "sensitive": False}], detail["requiredEnv"])
        self.assertEqual(["execute_sql", "get_advisors"], detail["requiredMcpTools"])

    def test_external_evidence_details_accept_supporting_evidence_actions(self):
        report = {
            "nextActionDetails": [],
            "supportingEvidenceNextActionDetails": [
                {
                    "action": "collect-external-evidence-files",
                    "nodeRole": "desktop",
                    "targetRole": "notebook",
                    "topic": "mcp-control-loop",
                    "requiredSidecars": [
                        ".patch",
                        ".report.md",
                        ".verify.log",
                        ".sha256.txt",
                        ".manifest.json",
                        "pendingNotice",
                    ],
                    "requiredSourceIsolation": {
                        "sourceIsolation.guard": "PASS",
                        "sourceRootKind": "local-worktree",
                        "directCanonicalSourceEdit": False,
                        "evidenceOnly": True,
                        "desktopFinalProof": "evidence_needed",
                        "rawSecretPatternHits": 0,
                    },
                    "applyCollectedEvidenceCommand": (
                        "powershell -NoProfile -ExecutionPolicy Bypass "
                        "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop"
                    ),
                    "decision": "evidence_needed",
                }
            ],
        }

        details = source_health_scorecard._safe_external_evidence_details(report)

        self.assertEqual(1, len(details))
        self.assertEqual("notebook", details[0]["targetRole"])
        self.assertEqual("PASS", details[0]["requiredSourceIsolation"]["guard"])
        self.assertTrue(details[0]["requiredSourceIsolation"]["evidenceOnly"])

    def test_builds_weighted_scorecard_from_current_metric_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "activeRoots": ["main/java", "app/src/main/java_clean"],
                        "runtimeProviderDisabledSmoke": {
                            "status": 200,
                            "providerDisabledOrSkipped": True,
                            "secretPatternHits": 0,
                            "rawQueryHits": 0,
                        },
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectFilesWithExplicitOrderApprox": 4,
                            "aspectOrderCoverageApprox": 0.4,
                            "criticalUnorderedAspectCount": 3,
                            "crossSubsystemLargeFilesOver1000": 5,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.25,
                            "topUnorderedAspectHotspots": [
                                {
                                    "file": "main/java/demo/HotAspect.java",
                                    "riskScore": 0.9,
                                }
                            ],
                        },
                        "testTreeContamination": {
                            "missingImportCount": 12,
                            "affectedTestFileCount": 4,
                            "riskScore": 0.12,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "largeActiveFilesOver2000": 3,
                        "activeJavaLocP95": 500,
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeCrossSubsystemLargeFilesOver1000": 2,
                        "topUnorderedAspectHotspots": [
                            {"file": "main/java/demo/HotAspect.java", "riskScore": 0.9}
                        ]
                    }
                ),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"missingImportCount": 12, "affectedTestFileCount": 4}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual(data["decision"], "source_health_scorecard")
        self.assertGreater(data["strictEvidenceAdjustedScore"], 0)
        self.assertLess(data["strictEvidenceAdjustedScore"], 100)
        self.assertEqual(round(sum(row["weight"] for row in data["componentScores"]), 4), 1.0)
        self.assertIn("aspect_order_hotspots", [row["id"] for row in data["riskLedger"]])
        self.assertIn("test_tree_contamination", [row["id"] for row in data["riskLedger"]])
        self.assertIn("supabase_live_proof_missing", [row["id"] for row in data["riskLedger"]])
        concentration = next(row for row in data["riskLedger"] if row["id"] == "cross_subsystem_concentration")
        self.assertEqual(0.04, concentration["riskScore"])
        self.assertIn("runtimeCrossSubsystemLargeFilesOver1000=2", concentration["evidence"])
        self.assertEqual(data["riskCount"], len(data["riskLedger"]))
        supabase_risk = next(row for row in data["riskLedger"] if row["id"] == "supabase_live_proof_missing")
        self.assertEqual("external_evidence", supabase_risk["scope"])
        allowed_statuses = {"action_required", "evidence_needed", "guarded", "monitored"}
        for row in data["riskLedger"]:
            self.assertIn(row["status"], allowed_statuses)
            self.assertTrue(row["statusReason"])
        self.assertEqual("evidence_needed", supabase_risk["status"])
        self.assertEqual(
            "action_required",
            concentration["status"],
        )
        self.assertEqual(
            data["activeRiskCount"],
            sum(
                1 for row in data["riskLedger"]
                if row["riskScore"] > 0.0 and row["scope"] == "active_source"
            ),
        )
        self.assertEqual(data["evidenceNeededCount"], len(data["evidenceNeeded"]))
        self.assertEqual(data["evidenceNeeded"][0]["classification"], "evidence_needed")
        rendered = json.dumps(data, ensure_ascii=False)
        self.assertIn("main/java/demo/HotAspect.java", rendered)
        self.assertNotIn(str(root), rendered)

    def test_maintainability_quant_inputs_are_required_but_exact_zero_is_valid(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)

            valid = source_health_scorecard.build_scorecard(root)
            valid_component = next(
                row for row in valid["componentScores"] if row["id"] == "scope_maintainability"
            )
            self.assertEqual(1.0, valid_component["normalized"])
            self.assertGreater(valid_component["weightedPoints"], 0.0)

            quant_path = root / "verification" / "dynamic-rag-quant-audit-metrics.json"
            quant = json.loads(quant_path.read_text(encoding="utf-8"))
            quant.pop("largeActiveFilesOver2000")
            quant.pop("activeJavaLocP95")
            quant_path.write_text(json.dumps(quant), encoding="utf-8")

            missing_keys = source_health_scorecard.build_scorecard(root)
            missing_keys_component = next(
                row for row in missing_keys["componentScores"]
                if row["id"] == "scope_maintainability"
            )
            self.assertEqual(0.0, missing_keys_component["normalized"])
            self.assertEqual(0.0, missing_keys_component["weightedPoints"])
            self.assertIn("reason=metric-input-invalid", missing_keys_component["evidence"])

            quant_path.unlink()
            missing_file = source_health_scorecard.build_scorecard(root)
            missing_file_component = next(
                row for row in missing_file["componentScores"]
                if row["id"] == "scope_maintainability"
            )
            self.assertEqual(0.0, missing_file_component["normalized"])
            self.assertEqual(0.0, missing_file_component["weightedPoints"])
            self.assertIn("reason=metric-input-invalid", missing_file_component["evidence"])
            self.assertIn("reason=metric-input-valid", valid_component["evidence"])

    def test_failure_pattern_prediction_uses_risk_ledger_and_redacted_evidence_contracts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {
                            "status": 200,
                            "providerDisabledOrSkipped": True,
                            "secretPatternHits": 0,
                            "rawQueryHits": 0,
                        },
                        "harmonyPressureSummary": {
                            "aspectFiles": 12,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "crossSubsystemLargeFilesOver1000": 42,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.0,
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_scoped",
                            "missingResultNames": [],
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeCrossSubsystemLargeFilesOver1000": 42,
                        "topRuntimeCrossSubsystemFiles": [
                            {
                                "file": "main/java/com/example/lms/service/rag/BigRuntime.java",
                                "subsystems": ["S01_Overdrive", "S05_ExtremeZ", "S06_Hypernova"],
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        prediction = data["failurePatternPrediction"]
        self.assertEqual("failure_pattern_prediction.v1", prediction["schema"])
        self.assertEqual("cross_subsystem_concentration", prediction["failurePatternKind"])
        self.assertEqual("FP-S01S08-CROSS-CONCENTRATION", prediction["patternId"])
        self.assertEqual(prediction["failurePatternKind"], data["failurePatternKind"])
        self.assertEqual(prediction["patternId"], data["patternId"])
        self.assertEqual(prediction["amplifiedSignalScore"], data["amplifiedSignalScore"])
        self.assertEqual("cross_subsystem_concentration", prediction["sourceRiskId"])
        self.assertGreater(prediction["amplifiedSignalScore"], 0.0)
        self.assertIn("harmony.score.S01_S05", prediction["traceStoreKeys"])
        self.assertIn("hypernova.twpmP", prediction["amplifierTraceKeys"])
        self.assertIn("TraceStore", prediction["requiredEvidenceSinks"])
        self.assertIn("DebugEventStore", prediction["requiredEvidenceSinks"])
        self.assertIn("CFVM Failure Pattern", prediction["requiredEvidenceSinks"])
        self.assertEqual("DebugEventStore", prediction["debugEventNdjsonContract"]["sink"])
        self.assertIn("failurePatternKind", prediction["debugEventNdjsonContract"]["requiredFields"])
        self.assertEqual("CFVM Failure Pattern", prediction["cfvmFailurePatternContract"]["sink"])
        self.assertEqual(prediction["failurePatternKind"], prediction["cfvmFailurePatternContract"]["failurePatternKind"])
        self.assertEqual(prediction["patternId"], prediction["cfvmFailurePatternContract"]["patternId"])
        self.assertIn(".manifest.json", prediction["patchDropManifestContract"]["requiredSidecars"])
        self.assertEqual(["macmini", "notebook"], prediction["patchDropManifestContract"]["producerRoles"])
        producer_contracts = prediction["patchDropManifestContract"]["producerNodeContracts"]
        self.assertEqual(["macmini", "notebook"], [row["nodeRole"] for row in producer_contracts])
        for contract in producer_contracts:
            self.assertEqual("local-worktree", contract["sourceRootKind"])
            self.assertFalse(contract["directCanonicalSourceEdit"])
            self.assertTrue(contract["evidenceOnly"])
            self.assertEqual(
                "verification/source-health-failure-pattern-events.ndjson",
                contract["requiredDebugEventNdjsonPath"],
            )
            self.assertEqual(
                "verification/source-health-patchdrop-manifest-contract.json",
                contract["requiredPatchDropManifestPath"],
            )
            self.assertIn("sourceHealth.failurePatternKind", contract["requiredTraceStoreKeys"])
            self.assertIn("DebugEventStore", contract["requiredEvidenceSinks"])
            self.assertIn("CFVM Failure Pattern", contract["requiredEvidenceSinks"])
            self.assertIn("patchDropManifestHash", contract["requiredManifestFields"])
            self.assertIn(".manifest.json", contract["requiredSidecars"])
        validation = prediction["autonomousValidationContract"]
        self.assertEqual("source_health.autonomous_validation_contract.v1", validation["schema"])
        self.assertEqual("agent_safe_patch_budget", validation["timeboxKind"])
        self.assertEqual(9, validation["maxDurationHours"])
        self.assertFalse(validation["runtimeProductBehavior"])
        self.assertIn("sourceHealthScorecard", validation["requiredGates"])
        self.assertIn("harmonyPressureReport", validation["requiredGates"])
        self.assertIn("all_p0_hard_gates_pass", validation["stopConditions"])
        self.assertIn("elapsed_hours_gte_9", validation["stopConditions"])
        self.assertIn("TraceStore keys", validation["cycleArtifacts"])
        self.assertIn("DebugEvent NDJSON", validation["cycleArtifacts"])
        self.assertIn("PatchDrop manifest", validation["cycleArtifacts"])
        self.assertIn("DebugEventStore", validation["requiredEvidenceSinks"])
        self.assertIn("CFVM Failure Pattern", validation["requiredEvidenceSinks"])
        self.assertEqual(["macmini", "notebook"], validation["producerNodeRoles"])
        self.assertGreaterEqual(len(prediction["candidatePatterns"]), 3)
        candidate_scores = [row["amplifiedSignalScore"] for row in prediction["candidatePatterns"]]
        self.assertEqual(candidate_scores, sorted(candidate_scores, reverse=True))
        validation_queue = prediction["producerValidationQueue"]
        self.assertEqual("producer_validation_queue.v1", validation_queue["schema"])
        self.assertEqual(9, validation_queue["maxDurationHours"])
        self.assertFalse(validation_queue["runtimeProductBehavior"])
        component_inputs = validation_queue["componentScoreInputs"]
        self.assertIn("harmony_pressure", [row["componentId"] for row in component_inputs])
        self.assertIn("supabase_external_evidence", validation_queue["externalEvidenceOnlyComponentIds"])
        for component in component_inputs[:3]:
            self.assertIn("componentId", component)
            self.assertIn("normalized", component)
            self.assertIn("weightedPoints", component)
            self.assertGreaterEqual(component["normalized"], 0.0)
            self.assertLessEqual(component["normalized"], 1.0)
        self.assertEqual("macmini", validation_queue["assignments"][0]["producerRole"])
        self.assertEqual("notebook", validation_queue["assignments"][1]["producerRole"])
        for item in validation_queue["assignments"][:2]:
            self.assertEqual("local-worktree", item["sourceRootKind"])
            self.assertFalse(item["directCanonicalSourceEdit"])
            self.assertIn("harmony_pressure", item["componentScoreRefs"])
            self.assertNotIn("supabase_external_evidence", item["componentScoreRefs"])
            self.assertEqual("sourceHealthScorecard", item["requiredGates"][0])
            self.assertIn("harmonyPressureReport", item["requiredGates"])
            self.assertIn("TraceStore keys", item["requiredEvidenceArtifacts"])
            self.assertIn("DebugEvent NDJSON", item["requiredEvidenceArtifacts"])
            self.assertIn("PatchDrop manifest", item["requiredEvidenceArtifacts"])
            self.assertIn("DebugEventStore", item["requiredEvidenceSinks"])
            self.assertIn("CFVM Failure Pattern", item["requiredEvidenceSinks"])
            self.assertIn("sourceHealth.failurePatternKind", item["requiredTraceStoreKeys"])
            self.assertIn("hypernova.cvarPhi", item["requiredTraceStoreKeys"])
            self.assertIn("hypernova.riskKAlloc", item["requiredTraceStoreKeys"])
            self.assertGreaterEqual(item["amplifiedSignalScore"], item["riskScore"])
        rendered = json.dumps(prediction, ensure_ascii=False)
        self.assertNotRegex(rendered, r"[A-Za-z]:[\\/]|Bearer|sk-[A-Za-z0-9_-]{20,}")

    def test_main_writes_failure_pattern_debug_event_and_patchdrop_manifest_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(root)

            with _fixture_registry_pin(registry_sha256):
                rc = source_health_scorecard.main([
                    "--root",
                    str(root),
                    "--output",
                    "verification/source-health-scorecard.json",
                ])

            self.assertEqual(0, rc)
            scorecard = json.loads((root / "verification/source-health-scorecard.json").read_text(encoding="utf-8"))
            debug_event_path = root / "verification/source-health-failure-pattern-events.ndjson"
            manifest_path = root / "verification/source-health-patchdrop-manifest-contract.json"
            self.assertTrue(debug_event_path.exists())
            self.assertTrue(manifest_path.exists())

            event_lines = [line for line in debug_event_path.read_text(encoding="utf-8").splitlines() if line.strip()]
            self.assertEqual(1, len(event_lines))
            event = json.loads(event_lines[0])
            manifest_text = manifest_path.read_text(encoding="utf-8")
            manifest = json.loads(manifest_text)
            manifest_hash = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
            prediction = scorecard["failurePatternPrediction"]

            self.assertEqual("source_health.failure_pattern_prediction", event["eventType"])
            self.assertEqual(prediction["failurePatternKind"], event["failurePatternKind"])
            self.assertEqual(prediction["patternId"], event["patternId"])
            self.assertEqual(manifest_hash, event["patchDropManifestHash"])
            self.assertEqual(manifest_hash, scorecard["failurePatternEvidenceArtifacts"]["patchDropManifestHash"])
            self.assertEqual(
                "verification/source-health-failure-pattern-events.ndjson",
                scorecard["failurePatternEvidenceArtifacts"]["debugEventNdjsonPath"],
            )
            self.assertIn("sourceHealth.failurePatternKind", event["traceStoreKeys"])
            self.assertIn("hypernova.cvarPhi", event["traceStoreKeys"])
            self.assertIn("hypernova.riskKAlloc", event["traceStoreKeys"])
            self.assertIn("hypernova.cvarPhi", event["amplifierTraceKeys"])
            self.assertEqual("DebugEventStore", event["debugEventStoreContract"]["sink"])
            self.assertEqual("source_health.failure_pattern_prediction", event["debugEventStoreContract"]["eventType"])
            self.assertEqual("CFVM Failure Pattern", event["cfvmFailurePatternContract"]["sink"])
            self.assertEqual(event["failurePatternKind"], event["cfvmFailurePatternContract"]["failurePatternKind"])
            self.assertEqual(event["patternId"], event["cfvmFailurePatternContract"]["patternId"])
            self.assertFalse(event["runtimeScoreClaim"])
            self.assertFalse(event["producerExecutionObserved"])
            self.assertEqual(9, event["autonomousValidationContract"]["maxDurationHours"])
            self.assertFalse(event["autonomousValidationContract"]["runtimeProductBehavior"])

            self.assertEqual("patchdrop.producer_manifest.failure_pattern.v1", manifest["schema"])
            self.assertEqual(["macmini", "notebook"], manifest["producerRoles"])
            self.assertEqual(["macmini", "notebook"], [row["nodeRole"] for row in manifest["producerNodeContracts"]])
            for contract in manifest["producerNodeContracts"]:
                self.assertEqual("local-worktree", contract["sourceRootKind"])
                self.assertFalse(contract["directCanonicalSourceEdit"])
                self.assertTrue(contract["evidenceOnly"])
                self.assertEqual(
                    "verification/source-health-failure-pattern-events.ndjson",
                    contract["requiredDebugEventNdjsonPath"],
                )
                self.assertEqual(
                    "verification/source-health-patchdrop-manifest-contract.json",
                    contract["requiredPatchDropManifestPath"],
                )
                self.assertIn("sourceHealth.failurePatternKind", contract["requiredTraceStoreKeys"])
                self.assertIn("hypernova.cvarPhi", contract["requiredTraceStoreKeys"])
                self.assertIn("hypernova.riskKAlloc", contract["requiredTraceStoreKeys"])
                self.assertIn("DebugEventStore", contract["requiredEvidenceSinks"])
                self.assertIn("CFVM Failure Pattern", contract["requiredEvidenceSinks"])
                self.assertIn("patchDropManifestHash", contract["requiredManifestFields"])
                self.assertIn(".manifest.json", contract["requiredSidecars"])
            self.assertEqual("PASS_REQUIRED", manifest["sourceIsolation"]["guard"])
            self.assertEqual("local-worktree", manifest["sourceIsolation"]["sourceRootKind"])
            self.assertFalse(manifest["sourceIsolation"]["directCanonicalSourceEdit"])
            self.assertFalse(manifest["mutationAllowed"])
            self.assertIn("DebugEventStore", manifest["requiredEvidenceSinks"])
            self.assertIn("CFVM Failure Pattern", manifest["requiredEvidenceSinks"])
            self.assertEqual("DebugEventStore", manifest["debugEventStoreContract"]["sink"])
            self.assertEqual("CFVM Failure Pattern", manifest["cfvmFailurePatternContract"]["sink"])
            self.assertEqual(9, manifest["autonomousValidationContract"]["maxDurationHours"])
            self.assertFalse(manifest["autonomousValidationContract"]["runtimeProductBehavior"])
            self.assertIn("sourceHealthScorecard", manifest["autonomousValidationContract"]["requiredGates"])
            self.assertIn("harmonyPressureReport", manifest["autonomousValidationContract"]["requiredGates"])
            validation_queue = manifest["producerValidationQueue"]
            self.assertEqual("producer_validation_queue.v1", validation_queue["schema"])
            self.assertEqual(9, validation_queue["maxDurationHours"])
            self.assertIn("harmony_pressure", [row["componentId"] for row in validation_queue["componentScoreInputs"]])
            self.assertIn("supabase_external_evidence", validation_queue["externalEvidenceOnlyComponentIds"])
            self.assertEqual(["macmini", "notebook"], [row["producerRole"] for row in validation_queue["assignments"][:2]])
            for item in validation_queue["assignments"][:2]:
                self.assertEqual("local-worktree", item["sourceRootKind"])
                self.assertFalse(item["directCanonicalSourceEdit"])
                self.assertIn("harmony_pressure", item["componentScoreRefs"])
                self.assertNotIn("supabase_external_evidence", item["componentScoreRefs"])
                self.assertIn("sourceHealthScorecard", item["requiredGates"])
                self.assertIn("harmonyPressureReport", item["requiredGates"])
                self.assertIn("PatchDrop manifest", item["requiredEvidenceArtifacts"])
                self.assertIn("DebugEventStore", item["requiredEvidenceSinks"])
                self.assertIn("CFVM Failure Pattern", item["requiredEvidenceSinks"])
                self.assertIn("sourceHealth.failurePatternKind", item["requiredTraceStoreKeys"])
                self.assertIn("hypernova.cvarPhi", item["requiredTraceStoreKeys"])
                self.assertIn("hypernova.riskKAlloc", item["requiredTraceStoreKeys"])
                self.assertGreaterEqual(item["amplifiedSignalScore"], item["riskScore"])
            self.assertEqual(
                "verification/source-health-failure-pattern-events.ndjson",
                manifest["debugEventNdjsonPath"],
            )
            self.assertIn(".manifest.json", manifest["requiredSidecars"])

            rendered = json.dumps({"event": event, "manifest": manifest}, ensure_ascii=False)
            self.assertNotRegex(rendered, r"[A-Za-z]:[\\/]|Bearer|sk-[A-Za-z0-9_-]{20,}")
            self.assertNotIn("rawPrompt", rendered)
            self.assertNotIn("rawQuery", rendered)
            self.assertNotIn("Authorization", rendered)
            self.assertNotIn("Cookie", rendered)

    def test_main_missing_quant_returns_2_and_preserves_prior_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            (root / "verification/dynamic-rag-quant-audit-metrics.json").unlink()
            sentinels = _scorecard_output_sentinels(root)

            rc, stdout, stderr = _run_canonical_scorecard_main(root)

            self.assertEqual(2, rc)
            self.assertEqual("", stdout)
            self.assertEqual(
                "[AWX][source-health] reason=quant-metrics-missing-or-stale status=missing\n",
                stderr,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_main_stale_quant_returns_2_and_preserves_prior_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            metrics_path = root / "verification/dynamic-rag-quant-audit-metrics.json"
            metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
            metrics["generatedAt"] = (
                dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=24, seconds=1)
            ).isoformat()
            metrics_path.write_bytes(_canonical_json_bytes(metrics))
            sentinels = _scorecard_output_sentinels(root)

            rc, stdout, stderr = _run_canonical_scorecard_main(root)

            self.assertEqual(2, rc)
            self.assertEqual("", stdout)
            self.assertEqual(
                "[AWX][source-health] reason=quant-metrics-missing-or-stale status=stale\n",
                stderr,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_main_malformed_quant_returns_2_and_preserves_prior_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            (root / "verification/dynamic-rag-quant-audit-metrics.json").write_bytes(b"{")
            sentinels = _scorecard_output_sentinels(root)

            rc, stdout, stderr = _run_canonical_scorecard_main(root)

            self.assertEqual(2, rc)
            self.assertEqual("", stdout)
            self.assertEqual(
                "[AWX][source-health] reason=quant-metrics-missing-or-stale status=malformed\n",
                stderr,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_main_cross_artifact_mismatch_returns_2_and_preserves_prior_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            baseline_path = root / "verification/structural-design-baseline.json"
            baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
            baseline["baselineId"] = "f" * 64
            baseline_path.write_bytes(_canonical_json_bytes(baseline))
            sentinels = _scorecard_output_sentinels(root)

            rc, stdout, stderr = _run_canonical_scorecard_main(root)

            self.assertEqual(2, rc)
            self.assertEqual("", stdout)
            self.assertEqual(
                "[AWX][source-health] reason=quant-metrics-missing-or-stale "
                "status=cross-artifact-mismatch\n",
                stderr,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_current_quant_bundle_reconstructs_registry_backed_v2_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(
                root,
                include_empty_reviewed_duplicate_wave_three=True,
            )
            self.assertIsNotNone(registry_sha256)

            with mock.patch.object(
                audit_fixture.audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                registry_sha256,
            ), mock.patch.object(
                source_health_scorecard,
                "load_closure_registry",
                wraps=source_health_scorecard.load_closure_registry,
            ) as registry_loader, mock.patch.object(
                source_health_scorecard,
                "load_closure_history",
                wraps=source_health_scorecard.load_closure_history,
            ) as history_loader:
                validated = source_health_scorecard.validate_current_quant_bundle(root)
                expected_summary = _empty_closure_history_summary(root)

        self.assertEqual("awx.dynamic-rag-quant-audit-metrics.v2", validated["schemaVersion"])
        self.assertEqual(expected_summary, validated["closureHistorySummary"])
        self.assertEqual(1, registry_loader.call_count)
        self.assertEqual(1, history_loader.call_count)
        self.assertEqual(
            Path("verification/structural-repair-waves/registry.json"),
            registry_loader.call_args.kwargs["registry_path"],
        )
        self.assertEqual(root.resolve(), history_loader.call_args.kwargs["root"])
        history_registry = history_loader.call_args.kwargs["registry"]
        self.assertEqual(
            validated["closureHistorySummary"]["waveRegistrySha256"],
            history_registry.payload_sha256,
        )
        self.assertEqual(
            root.resolve() / "verification/structural-repair-waves/registry.json",
            history_registry.path,
        )

    def test_fixture_seeding_is_schema_local_and_ignores_checkout_wave_plan(self):
        """Catches fixture history copies or installation plans leaking from the checkout."""
        base_relatives = (
            audit_fixture.audit.INTAKE_SUMMARY_RELATIVE,
            audit_fixture.audit.INTAKE_ROWS_RELATIVE,
            audit_fixture.audit.TARGET_PREIMAGES_RELATIVE,
            audit_fixture.audit.PROGRESS_RELATIVE,
        )
        companion_relatives = (
            audit_fixture.audit.ADMISSION_DECISION_RELATIVE,
            audit_fixture.audit.DUPLICATE_EVIDENCE_RELATIVE,
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            checkout_registry = root / "checkout/verification/structural-repair-waves/registry.json"
            checkout_registry.parent.mkdir(parents=True)
            checkout_registry.write_bytes(
                _canonical_json_bytes(
                    {
                        "schemaVersion": audit_fixture.REGISTRY_SCHEMA,
                        "waves": [
                            audit_fixture.WAVE_ONE_DESCRIPTOR,
                            audit_fixture.WAVE_TWO_DESCRIPTOR,
                            audit_fixture.WAVE_THREE_DESCRIPTOR,
                            audit_fixture.WAVE_THREE_DESCRIPTOR,
                        ],
                    }
                )
            )
            fixture_root = root / "ordinary-fixture"
            with mock.patch.object(sys.modules[__name__], "WAVE_REGISTRY_PATH", checkout_registry):
                registry_sha256 = _write_green_source_runtime_fixture(fixture_root)
            fixture_registry_bytes = (
                fixture_root / "verification/structural-repair-waves/registry.json"
            ).read_bytes()
            fixture_registry = json.loads(fixture_registry_bytes.decode("utf-8"))

            self.assertEqual(
                [audit_fixture.WAVE_ONE_DESCRIPTOR, audit_fixture.WAVE_TWO_DESCRIPTOR],
                fixture_registry["waves"],
            )
            self.assertEqual(hashlib.sha256(fixture_registry_bytes).hexdigest(), registry_sha256)
            self.assertFalse(
                (fixture_root / ".superpowers/sdd/structural-repair-waves/wave-0003").exists()
            )
            self.assertFalse(
                (fixture_root / ".superpowers/sdd/structural-repair-waves/wave-0004").exists()
            )

            source_root = root / "descriptor-source"
            descriptor_rows = []
            expected_payloads: dict[str, dict[Path, bytes]] = {}
            for ordinal, schema in enumerate(
                (
                    audit_fixture.audit.CLOSURE_INTAKE_SCHEMA,
                    audit_fixture.audit.CLOSURE_INTAKE_V2_SCHEMA,
                    audit_fixture.audit.CLOSURE_INTAKE_V3_SCHEMA,
                ),
                start=1,
            ):
                wave_id = f"wave-copy-{ordinal}"
                proof_root = Path(f"fixture-proof/{wave_id}")
                descriptor_rows.append(
                    {
                        "waveId": wave_id,
                        "ordinal": ordinal,
                        "journalPath": f"fixture-journals/{wave_id}.jsonl",
                        "proofRoot": proof_root.as_posix(),
                    }
                )
                payloads = {
                    relative: (
                        _canonical_json_bytes({"schemaVersion": schema})
                        if relative == audit_fixture.audit.INTAKE_SUMMARY_RELATIVE
                        else f"{wave_id}:{relative.as_posix()}".encode("utf-8")
                    )
                    for relative in base_relatives
                }
                if schema != audit_fixture.audit.CLOSURE_INTAKE_SCHEMA:
                    payloads.update(
                        {
                            relative: f"{wave_id}:{relative.as_posix()}".encode("utf-8")
                            for relative in companion_relatives
                        }
                    )
                expected_payloads[wave_id] = payloads
                for relative, payload in payloads.items():
                    target = source_root / proof_root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(payload)

            copied_root = root / "schema-copied-fixture"
            _seed_empty_registered_history(
                copied_root,
                descriptors=descriptor_rows,
                source_root=source_root,
            )
            for descriptor in descriptor_rows:
                wave_id = str(descriptor["waveId"])
                proof_root = copied_root / str(descriptor["proofRoot"])
                for relative in base_relatives:
                    self.assertEqual(
                        expected_payloads[wave_id][relative],
                        (proof_root / relative).read_bytes(),
                    )
                for relative in companion_relatives:
                    expected = expected_payloads[wave_id].get(relative)
                    if expected is None:
                        self.assertFalse((proof_root / relative).exists())
                    else:
                        self.assertEqual(expected, (proof_root / relative).read_bytes())

            unknown_source = root / "unknown-source"
            unknown_descriptor = {
                "waveId": "wave-copy-unknown",
                "ordinal": 9,
                "journalPath": "fixture-journals/wave-copy-unknown.jsonl",
                "proofRoot": "fixture-proof/wave-copy-unknown",
            }
            for relative in base_relatives:
                target = unknown_source / str(unknown_descriptor["proofRoot"]) / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(
                    _canonical_json_bytes({"schemaVersion": "unknown-fixture-intake"})
                    if relative == audit_fixture.audit.INTAKE_SUMMARY_RELATIVE
                    else b"unknown-base"
                )
            with self.assertRaisesRegex(AssertionError, "fixture-intake-schema-unknown"):
                _seed_empty_registered_history(
                    root / "unknown-fixture",
                    descriptors=[unknown_descriptor],
                    source_root=unknown_source,
                )

    def test_current_quant_bundle_reconstructs_empty_reviewed_duplicate_wave_three(self):
        """Catches source health bypassing production loaders for an empty v2 wave."""
        current_registry = json.loads(WAVE_REGISTRY_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            [
                audit_fixture.WAVE_ONE_DESCRIPTOR,
                audit_fixture.WAVE_TWO_DESCRIPTOR,
                audit_fixture.WAVE_THREE_DESCRIPTOR,
                audit_fixture.WAVE_FOUR_DESCRIPTOR,
            ],
            current_registry["waves"],
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(
                root,
                include_empty_reviewed_duplicate_wave_three=True,
            )
            self.assertIsNotNone(registry_sha256)
            with mock.patch.object(
                audit_fixture.audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                registry_sha256,
            ), mock.patch.object(
                source_health_scorecard,
                "load_closure_registry",
                wraps=source_health_scorecard.load_closure_registry,
            ) as registry_loader, mock.patch.object(
                source_health_scorecard,
                "load_closure_history",
                wraps=source_health_scorecard.load_closure_history,
            ) as history_loader, mock.patch.object(
                source_health_scorecard,
                "load_and_validate_current_bundle",
                wraps=source_health_scorecard.load_and_validate_current_bundle,
            ) as full_bundle_loader:
                validated = source_health_scorecard.validate_current_quant_bundle(root)
                public_bundle = audit_fixture.audit.load_and_validate_current_bundle(
                    root=root
                )
                expected_summary = _empty_closure_history_summary(root)

        self.assertEqual(expected_summary, validated["closureHistorySummary"])
        self.assertEqual(3, validated["closureHistorySummary"]["waveCount"])
        self.assertEqual(0, validated["closureHistorySummary"]["eventCount"])
        self.assertEqual(0, validated["closureHistorySummary"]["rejectedFalsePositiveRootCauseGroups"])
        self.assertEqual(0, validated["closureHistorySummary"]["verifiedClosedRootCauseGroups"])
        self.assertEqual(1, registry_loader.call_count)
        self.assertEqual(1, history_loader.call_count)
        self.assertEqual(1, full_bundle_loader.call_count)
        self.assertEqual(validated, public_bundle.metrics)

    def test_current_quant_bundle_reconstructs_empty_selfask_reviewed_wave_four(self):
        """Catches fixture linking that cannot reconstruct an empty v3 reviewed-admission wave."""
        current_registry = json.loads(WAVE_REGISTRY_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            [
                audit_fixture.WAVE_ONE_DESCRIPTOR,
                audit_fixture.WAVE_TWO_DESCRIPTOR,
                audit_fixture.WAVE_THREE_DESCRIPTOR,
                audit_fixture.WAVE_FOUR_DESCRIPTOR,
            ],
            current_registry["waves"],
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(
                root,
                include_empty_selfask_reviewed_wave_four=True,
            )
            self.assertIsNotNone(registry_sha256)

            with _fixture_registry_pin(registry_sha256):
                validated = source_health_scorecard.validate_current_quant_bundle(root)
                public_bundle = audit_fixture.audit.load_and_validate_current_bundle(root=root)
                expected_summary = _empty_closure_history_summary(root)

        self.assertEqual(expected_summary, validated["closureHistorySummary"])
        self.assertEqual(4, validated["closureHistorySummary"]["waveCount"])
        self.assertEqual(0, validated["closureHistorySummary"]["eventCount"])
        self.assertEqual(validated, public_bundle.metrics)

    def test_current_quant_bundle_rejects_tampered_wave_four_admission_bytes(self):
        """Catches source-health publication after canonical v3 admission drift."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(
                root,
                include_empty_selfask_reviewed_wave_four=True,
            )
            self.assertIsNotNone(registry_sha256)
            admission_path = (
                root
                / ".superpowers/sdd/structural-repair-waves/wave-0004/intake/admission-decision.json"
            )
            admission_path.write_bytes(admission_path.read_bytes() + b" ")
            sentinels = _scorecard_output_sentinels(root)

            with _fixture_registry_pin(registry_sha256):
                rc, stdout, stderr = _run_canonical_scorecard_main(root)

            self.assertEqual(2, rc)
            self.assertEqual("", stdout)
            self.assertEqual(
                "[AWX][source-health] reason=quant-metrics-missing-or-stale "
                "status=cross-artifact-mismatch\n",
                stderr,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_current_quant_bundle_rejects_tampered_wave_four_duplicate_evidence_bytes(self):
        """Catches source-health publication after canonical v3 duplicate-evidence drift."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(
                root,
                include_empty_selfask_reviewed_wave_four=True,
            )
            self.assertIsNotNone(registry_sha256)
            evidence_path = (
                root
                / ".superpowers/sdd/structural-repair-waves/wave-0004/intake/duplicate-evidence.json"
            )
            evidence_path.write_bytes(evidence_path.read_bytes() + b" ")
            sentinels = _scorecard_output_sentinels(root)

            with _fixture_registry_pin(registry_sha256):
                rc, stdout, stderr = _run_canonical_scorecard_main(root)

            self.assertEqual(2, rc)
            self.assertEqual("", stdout)
            self.assertEqual(
                "[AWX][source-health] reason=quant-metrics-missing-or-stale "
                "status=cross-artifact-mismatch\n",
                stderr,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_source_health_uses_same_registry_history_and_full_bundle_loader_for_wave_four(self):
        """Catches a Wave-4-specific source-health loader bypass."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(
                root,
                include_empty_selfask_reviewed_wave_four=True,
            )
            self.assertIsNotNone(registry_sha256)

            with _fixture_registry_pin(registry_sha256), mock.patch.object(
                source_health_scorecard,
                "load_closure_registry",
                wraps=source_health_scorecard.load_closure_registry,
            ) as registry_loader, mock.patch.object(
                source_health_scorecard,
                "load_closure_history",
                wraps=source_health_scorecard.load_closure_history,
            ) as history_loader, mock.patch.object(
                source_health_scorecard,
                "load_and_validate_current_bundle",
                wraps=source_health_scorecard.load_and_validate_current_bundle,
            ) as full_bundle_loader:
                validated = source_health_scorecard.validate_current_quant_bundle(root)
                public_bundle = audit_fixture.audit.load_and_validate_current_bundle(root=root)
                expected_summary = _empty_closure_history_summary(root)

        self.assertEqual(expected_summary, validated["closureHistorySummary"])
        self.assertEqual(4, validated["closureHistorySummary"]["waveCount"])
        self.assertEqual(1, registry_loader.call_count)
        self.assertEqual(1, history_loader.call_count)
        self.assertEqual(1, full_bundle_loader.call_count)
        self.assertEqual(validated, public_bundle.metrics)

    def test_green_fixture_preserves_requested_duplicate_counts_after_wave_four_install(self):
        """Catches Wave-4 installation replacing the caller's duplicate-report fixture bytes."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(
                root,
                duplicate_collision_count=12,
                include_empty_selfask_reviewed_wave_four=True,
            )
            self.assertIsNotNone(registry_sha256)
            metrics = json.loads(
                (root / "verification/dynamic-rag-quant-audit-metrics.json").read_text(
                    encoding="utf-8"
                )
            )
            scorecard = source_health_scorecard.build_scorecard(root)

            with _fixture_registry_pin(registry_sha256):
                registry = audit_fixture.audit.load_closure_registry(
                    root=root,
                    registry_path=audit_fixture.REGISTRY_RELATIVE,
                )
                wave_four_intake = audit_fixture.audit._load_closure_intake(
                    root / registry.waves[3].proof_root,
                    registry.waves[3],
                )
                history = audit_fixture.audit.load_closure_history(root=root, registry=registry)
                wave_four_history = audit_fixture.audit.load_closure_wave(
                    root=root,
                    descriptor=registry.waves[3],
                )

        self.assertEqual(12, metrics["duplicateFqcnSourceCollisionCount"])
        self.assertEqual(0, metrics["duplicateFqcnPackagedActiveCount"])
        self.assertEqual(0, metrics["duplicateFqcnActiveCount"])
        source_integrity = next(
            row for row in scorecard["componentScores"] if row["id"] == "source_integrity"
        )
        self.assertEqual(1.0, source_integrity["normalized"])
        self.assertIn("duplicateFqcnActiveCount=0", source_integrity["evidence"])
        self.assertEqual(audit_fixture.audit.CLOSURE_INTAKE_V3_SCHEMA, wave_four_intake.schema_version)
        self.assertEqual(1, len(wave_four_intake.predecessor_rows))
        self.assertEqual((), history.all_events)
        self.assertEqual(0, history.rejected_false_positive_root_cause_groups)
        self.assertEqual(0, history.verified_closed_root_cause_groups)
        self.assertEqual((), wave_four_history.all_events)
        self.assertEqual((), wave_four_history.active_events)
        self.assertEqual((), wave_four_history.proof_pairs)
        self.assertEqual((), wave_four_history.target_admissions)

    def test_current_quant_bundle_rejects_tampered_wave_three_admission_bytes(self):
        """Catches source-health publication after canonical v2 admission drift."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(
                root,
                include_empty_reviewed_duplicate_wave_three=True,
            )
            self.assertIsNotNone(registry_sha256)
            admission_path = (
                root
                / ".superpowers/sdd/structural-repair-waves/wave-0003/intake/admission-decision.json"
            )
            admission_path.write_bytes(admission_path.read_bytes() + b" ")
            sentinels = _scorecard_output_sentinels(root)

            with mock.patch.object(
                audit_fixture.audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                registry_sha256,
            ):
                rc, stdout, stderr = _run_canonical_scorecard_main(root)

            self.assertEqual(2, rc)
            self.assertEqual("", stdout)
            self.assertEqual(
                "[AWX][source-health] reason=quant-metrics-missing-or-stale "
                "status=cross-artifact-mismatch\n",
                stderr,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_current_quant_bundle_rejects_stale_v1_metrics_after_v2_install(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            metrics_path = root / "verification/dynamic-rag-quant-audit-metrics.json"
            metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
            metrics["schemaVersion"] = "awx.dynamic-rag-quant-audit-metrics.v1"
            metrics_path.write_bytes(_canonical_json_bytes(metrics))
            sentinels = _scorecard_output_sentinels(root)

            rc, stdout, stderr = _run_canonical_scorecard_main(root)

            self.assertEqual(2, rc)
            self.assertEqual("", stdout)
            self.assertEqual(
                "[AWX][source-health] reason=quant-metrics-missing-or-stale "
                "status=schema-invalid\n",
                stderr,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_current_quant_bundle_rejects_registry_or_wave_identity_drift(self):
        for case in ("registry", "journal"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                _write_green_source_runtime_fixture(root)
                if case == "registry":
                    registry = root / "verification/structural-repair-waves/registry.json"
                    registry.write_bytes(registry.read_bytes() + b" ")
                else:
                    journal = root / "verification/structural-repair-closure-journal.jsonl"
                    journal.write_bytes(_canonical_json_bytes({}))

                with self.assertRaises(
                    source_health_scorecard.QuantMetricsContractError
                ) as raised:
                    source_health_scorecard.validate_current_quant_bundle(root)

            self.assertEqual("cross-artifact-mismatch", raised.exception.status)

    def test_main_current_linked_quant_bundle_writes_registry_backed_scorecard(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(root)

            with _fixture_registry_pin(registry_sha256):
                validated = source_health_scorecard.validate_current_quant_bundle(root)
                rc, stdout, stderr = _run_canonical_scorecard_main(root)
            expected_summary = _empty_closure_history_summary(root)

            self.assertEqual("awx.dynamic-rag-quant-audit-metrics.v2", validated["schemaVersion"])
            self.assertEqual(
                expected_summary,
                validated["closureHistorySummary"],
            )
            self.assertEqual(0, rc)
            self.assertIn("[AWX][source-health]", stdout)
            self.assertEqual("", stderr)
            output = json.loads(
                (root / "verification/source-health-scorecard.json").read_text(encoding="utf-8")
            )
            self.assertEqual("source_health_scorecard", output["decision"])

    def test_main_relinked_known_metric_drift_returns_2_and_preserves_prior_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(root)
            _relink_quant_bundle_after_metrics_mutation(
                root,
                lambda metrics: metrics.update({"activeJavaLocP95": 999999}),
            )
            sentinels = _scorecard_output_sentinels(root)

            with _fixture_registry_pin(registry_sha256):
                rc, stdout, stderr = _run_canonical_scorecard_main(root)

            self.assertEqual(2, rc)
            self.assertEqual("", stdout)
            self.assertEqual(
                "[AWX][source-health] reason=quant-metrics-missing-or-stale "
                "status=schema-invalid\n",
                stderr,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_main_relinked_unknown_metrics_field_returns_2_and_preserves_prior_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(root)
            _relink_quant_bundle_after_metrics_mutation(
                root,
                lambda metrics: metrics.update(
                    {"unrecognizedSchemaDrift": {"changesScoreMeaning": True}}
                ),
            )
            sentinels = _scorecard_output_sentinels(root)

            with _fixture_registry_pin(registry_sha256):
                rc, stdout, stderr = _run_canonical_scorecard_main(root)

            self.assertEqual(2, rc)
            self.assertEqual("", stdout)
            self.assertEqual(
                "[AWX][source-health] reason=quant-metrics-missing-or-stale "
                "status=schema-invalid\n",
                stderr,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_quant_bundle_rejects_tampered_closure_history(self):
        cases = (
            "journal-bytes",
            "proof-set-hash",
            "event-count",
            "verified-count",
        )
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                history_plan_root = root / "history-plan"
                _seed_empty_registered_history(history_plan_root)
                closure_summary = _empty_closure_history_summary(history_plan_root)
                if case == "proof-set-hash":
                    closure_summary["proofSetSha256"] = "f" * 64
                elif case == "event-count":
                    closure_summary["eventCount"] = 1
                elif case == "verified-count":
                    closure_summary["verifiedClosedRootCauseGroups"] = 1
                registry_sha256 = _write_green_source_runtime_fixture(root)
                _relink_quant_bundle_after_metrics_mutation(
                    root,
                    lambda metrics: metrics.update(
                        {"closureHistorySummary": copy.deepcopy(closure_summary)}
                    ),
                )
                if case == "journal-bytes":
                    (
                        root / "verification/structural-repair-closure-journal.jsonl"
                    ).write_bytes(b"{}\n")

                with _fixture_registry_pin(registry_sha256):
                    with mock.patch.object(
                        source_health_scorecard,
                        "load_closure_history",
                        wraps=source_health_scorecard.load_closure_history,
                    ) as history_loader:
                        with self.assertRaises(
                            source_health_scorecard.QuantMetricsContractError
                        ) as raised:
                            source_health_scorecard.validate_current_quant_bundle(root)

                self.assertEqual("cross-artifact-mismatch", raised.exception.status)
                self.assertEqual(1, history_loader.call_count)

    def test_green_fixture_uses_packaged_active_duplicate_semantics(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(
                root,
                duplicate_collision_count=12,
                include_empty_reviewed_duplicate_wave_three=True,
            )
            metrics = json.loads(
                (root / "verification/dynamic-rag-quant-audit-metrics.json").read_text(
                    encoding="utf-8"
                )
            )

            scorecard = source_health_scorecard.build_scorecard(root)
            with _fixture_registry_pin(registry_sha256):
                registry = audit_fixture.audit.load_closure_registry(
                    root=root,
                    registry_path=audit_fixture.REGISTRY_RELATIVE,
                )
                wave_three_intake = audit_fixture.audit._load_closure_intake(
                    root / registry.waves[2].proof_root,
                    registry.waves[2],
                )

        self.assertEqual(12, metrics["duplicateFqcnSourceCollisionCount"])
        self.assertEqual(0, metrics["duplicateFqcnPackagedActiveCount"])
        self.assertEqual(0, metrics["duplicateFqcnActiveCount"])
        source_integrity = next(
            row for row in scorecard["componentScores"] if row["id"] == "source_integrity"
        )
        self.assertEqual(1.0, source_integrity["normalized"])
        self.assertIn("duplicateFqcnActiveCount=0", source_integrity["evidence"])
        self.assertEqual(
            audit_fixture.audit.CLOSURE_INTAKE_V2_SCHEMA,
            wave_three_intake.schema_version,
        )
        self.assertEqual(1, len(wave_three_intake.predecessor_rows))
        self.assertEqual(
            1,
            wave_three_intake.duplicate_evidence["sourceCollisionCount"],
        )
        self.assertEqual(
            wave_three_intake.duplicate_evidence_sha256,
            wave_three_intake.admission_decision["duplicateEvidenceSha256"],
        )

    def test_custom_output_writes_only_requested_path_by_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(root)

            with _fixture_registry_pin(registry_sha256):
                rc = source_health_scorecard.main([
                    "--root",
                    str(root),
                    "--output",
                    "var/codex-smoke/source-health-scorecard.json",
                ])

            self.assertEqual(0, rc)
            custom_scorecard_path = root / "var/codex-smoke/source-health-scorecard.json"
            default_scorecard_path = root / "verification/source-health-scorecard.json"
            debug_event_path = root / "verification/source-health-failure-pattern-events.ndjson"
            manifest_path = root / "verification/source-health-patchdrop-manifest-contract.json"
            self.assertTrue(custom_scorecard_path.exists())
            self.assertFalse(default_scorecard_path.exists())
            self.assertFalse(debug_event_path.exists())
            self.assertFalse(manifest_path.exists())

    def test_canonical_write_requires_explicit_flag(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            registry_sha256 = _write_green_source_runtime_fixture(root)

            with _fixture_registry_pin(registry_sha256):
                rc = source_health_scorecard.main([
                    "--root",
                    str(root),
                    "--output",
                    "var/codex-smoke/source-health-scorecard.json",
                    "--write-canonical-output",
                ])

            self.assertEqual(0, rc)
            custom_scorecard_path = root / "var/codex-smoke/source-health-scorecard.json"
            default_scorecard_path = root / "verification/source-health-scorecard.json"
            debug_event_path = root / "verification/source-health-failure-pattern-events.ndjson"
            manifest_path = root / "verification/source-health-patchdrop-manifest-contract.json"
            self.assertTrue(custom_scorecard_path.exists())
            self.assertTrue(default_scorecard_path.exists())

            default_scorecard = json.loads(default_scorecard_path.read_text(encoding="utf-8"))
            event = json.loads(debug_event_path.read_text(encoding="utf-8").splitlines()[0])
            manifest_text = manifest_path.read_text(encoding="utf-8")
            manifest_hash = hashlib.sha256(manifest_path.read_bytes()).hexdigest()

            self.assertEqual(default_scorecard["generatedAt"], event["generatedAt"])
            self.assertEqual(manifest_hash, event["patchDropManifestHash"])
            self.assertEqual(
                manifest_hash,
                default_scorecard["failurePatternEvidenceArtifacts"]["patchDropManifestHash"],
            )

    def test_zero_test_tree_risk_keeps_compile_gate_out_of_evidence_needed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0},
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertNotIn("broad_test_tree_compile", evidence_items)
        test_risk = next(row for row in data["riskLedger"] if row["id"] == "test_tree_contamination")
        self.assertEqual(test_risk["riskScore"], 0.0)
        self.assertEqual("monitored", test_risk["status"])
        self.assertIn("compileTestJava", test_risk["nextAction"])
        self.assertIn("supabase", data["nextSingleAction"])
        self.assertEqual(
            "run_broad_test_runtime_proof",
            data["nextSourceAction"],
        )

    def test_aspect_order_risk_uses_existing_contract_test_as_next_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "topUnorderedAspectHotspots": [],
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            test_path = (
                root
                / "src"
                / "test"
                / "java"
                / "ai"
                / "abandonware"
                / "nova"
                / "orch"
                / "aop"
                / "AspectOrderingContractTest.java"
            )
            test_path.parent.mkdir(parents=True)
            test_path.write_text(
                "class AspectOrderingContractTest { "
                "ExtremeZBurstAspect extremeZ; RagCompressionAspect rag; LlmRouterAspect llm; }",
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        risk = next(row for row in data["riskLedger"] if row["id"] == "aspect_order_hotspots")
        self.assertEqual(0.0, risk["riskScore"])
        self.assertIn("contractPresent=True", risk["evidence"])
        self.assertIn("Run focused AspectOrderingContractTest", risk["nextAction"])
        self.assertNotIn("Add explicit order/call-path contracts", risk["nextAction"])

    def test_cross_subsystem_next_source_action_exposes_focused_test_details(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "crossSubsystemLargeFilesOver1000": 31,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.0,
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "largeActiveFilesOver2000": 12,
                        "activeJavaLocP95": 643.2,
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps({"runtimeCrossSubsystemLargeFilesOver1000": 28}),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("continue_small_contract_tests_on_cross_subsystem_runtime_seams", data["nextSourceAction"])
        details = data["nextSourceActionDetails"]
        self.assertEqual(1, len(details))
        detail = details[0]
        self.assertEqual("run-cross-subsystem-contract-tests", detail["action"])
        self.assertTrue(detail["readOnly"])
        self.assertFalse(detail["mutationAllowed"])
        self.assertIn("com.example.lms.orchestration.StrategyConflictResolverTest", detail["focusedTests"])
        self.assertIn("ai.abandonware.nova.orch.aop.AspectOrderingContractTest", detail["focusedTests"])
        self.assertIn(
            ".\\gradlew.bat crossSubsystemContractTest --no-daemon --project-cache-dir <desktop-cache>",
            detail["commands"],
        )
        self.assertNotIn(".\\gradlew.bat test --tests", json.dumps(detail["commands"], ensure_ascii=False))
        self.assertIn("boosterMode.active", detail["requiredTraceKeys"])
        self.assertIn("routing.executionPlan.applied.primaryMode", detail["requiredTraceKeys"])
        rendered = json.dumps(detail, ensure_ascii=False)
        self.assertNotRegex(rendered, r"[A-Za-z]:[\\/]")

    def test_broad_catch_audit_source_action_has_structured_contract_detail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {
                            "status": 200,
                            "providerDisabledOrSkipped": True,
                            "secretPatternHits": 0,
                            "rawQueryHits": 0,
                        },
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "crossSubsystemLargeFilesOver1000": 0,
                            "runtimeCrossSubsystemLargeFilesOver1000": 0,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.25,
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeCrossSubsystemLargeFilesOver1000": 0,
                        "broadCatchWithoutLocalBreadcrumbRatio": 0.25,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("audit_top_broad_catches_for_redacted_breadcrumbs", data["nextSourceAction"])
        details = data["nextSourceActionDetails"]
        self.assertEqual(1, len(details))
        detail = details[0]
        self.assertEqual("audit-broad-catches-redacted-breadcrumbs", detail["action"])
        self.assertTrue(detail["sourceContract"])
        self.assertTrue(detail["readOnly"])
        self.assertFalse(detail["mutationAllowed"])
        self.assertGreaterEqual(len(detail["focusedTests"]), 4)
        self.assertGreaterEqual(len(detail["commands"]), 4)
        self.assertGreaterEqual(len(detail["requiredTraceKeys"]), 5)
        self.assertIn("com.example.lms.service.ChatWorkflowTraceRedactionContractTest", detail["focusedTests"])
        self.assertIn("com.example.lms.guard.RemainingMediumEmptyCatchContractTest", detail["focusedTests"])
        self.assertIn("failSoft.suppressed.stage", detail["requiredTraceKeys"])
        rendered = json.dumps(detail, ensure_ascii=False)
        self.assertNotRegex(rendered, r"[A-Za-z]:[\\/]")

    def test_cross_subsystem_contract_test_proof_advances_next_source_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "crossSubsystemLargeFilesOver1000": 31,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.0,
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps({"runtimeCrossSubsystemLargeFilesOver1000": 28}),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            test_results = root / "build" / "desktop-codex" / "test-results" / "test"
            test_results.mkdir(parents=True)
            for class_name in (
                "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                "com.example.lms.orchestration.StrategyConflictResolverTest",
                "com.example.lms.orchestration.ExecutionPlanApplierTest",
                "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
            ):
                (test_results / f"TEST-{class_name}.xml").write_text(
                    f'<testsuite name="{class_name}" tests="1" skipped="0" failures="0" errors="0">'
                    f'<testcase classname="{class_name}" name="contract"/></testsuite>',
                    encoding="utf-8",
                )

            data = source_health_scorecard.build_scorecard(root)

        concentration = next(row for row in data["riskLedger"] if row["id"] == "cross_subsystem_concentration")
        self.assertIn("focusedContractTests=passed", concentration["evidence"])
        self.assertIn("Focused cross-subsystem contract tests passed", concentration["nextAction"])
        self.assertEqual("run_broad_test_runtime_proof", data["nextSourceAction"])
        self.assertEqual([], data["nextSourceActionDetails"])

    def test_cross_subsystem_contract_test_proof_reads_isolated_task_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            test_results = root / "build" / "desktop-codex" / "test-results" / "crossSubsystemContractTest"
            test_results.mkdir(parents=True)
            for class_name in (
                "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                "com.example.lms.orchestration.StrategyConflictResolverTest",
                "com.example.lms.orchestration.ExecutionPlanApplierTest",
                "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
            ):
                (test_results / f"TEST-{class_name}.xml").write_text(
                    f'<testsuite name="{class_name}" tests="1" skipped="0" failures="0" errors="0">'
                    f'<testcase classname="{class_name}" name="contract"/></testsuite>',
                    encoding="utf-8",
                )

            old_host = os.environ.get("AWX_BUILD_HOST_ID")
            os.environ["AWX_BUILD_HOST_ID"] = "desktop-codex"
            try:
                proof = source_health_scorecard._focused_cross_subsystem_contract_proof(root)
            finally:
                if old_host is None:
                    os.environ.pop("AWX_BUILD_HOST_ID", None)
                else:
                    os.environ["AWX_BUILD_HOST_ID"] = old_host

        self.assertEqual({"passed": True, "passedCount": 4, "requiredCount": 4}, proof)

    def test_broad_runtime_test_proof_marks_source_runtime_current(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "crossSubsystemLargeFilesOver1000": 31,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.0,
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps({"runtimeCrossSubsystemLargeFilesOver1000": 28}),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            contract_file = (
                root
                / "src/test/java/ai/abandonware/nova/orch/aop/AspectOrderingContractTest.java"
            )
            contract_file.parent.mkdir(parents=True)
            contract_file.write_text(
                "ExtremeZBurstAspect RagCompressionAspect LlmRouterAspect",
                encoding="utf-8",
            )
            test_results = root / "build" / "desktop-codex" / "test-results" / "test"
            test_results.mkdir(parents=True)
            for class_name in (
                "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                "com.example.lms.orchestration.StrategyConflictResolverTest",
                "com.example.lms.orchestration.ExecutionPlanApplierTest",
                "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
                "com.example.lms.service.ChatWorkflowTraceRedactionContractTest",
            ):
                (test_results / f"TEST-{class_name}.xml").write_text(
                    f'<testsuite name="{class_name}" tests="2" skipped="0" failures="0" errors="0">'
                    f'<testcase classname="{class_name}" name="contractA"/>'
                    f'<testcase classname="{class_name}" name="contractB"/></testsuite>',
                    encoding="utf-8",
                )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("source_runtime_proof_current", data["nextSourceAction"])
        details = data["nextSourceActionDetails"]
        self.assertEqual(1, len(details))
        self.assertEqual("source-runtime-proof-current", details[0]["action"])
        self.assertTrue(details[0]["readOnly"])
        self.assertFalse(details[0]["mutationAllowed"])
        self.assertIn("python scripts\\source_health_scorecard.py --root . --output verification\\source-health-scorecard.json", details[0]["commands"])
        self.assertIn("python scripts\\awx_mcp_completion_audit.py --root .", details[0]["commands"])
        self.assertIn("bootSuccessProven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("applicationReadyEventProven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("application-ready-event-proven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("bootStartedProven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("boot-started-proven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("runtimePrecheckProven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("runtime-precheck-proven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("blocker-scan-only", ",".join(details[0]["requiredMarkers"]))
        aspect_order = next(row for row in data["riskLedger"] if row["id"] == "aspect_order_hotspots")
        self.assertIn("Aspect ordering proof is current", aspect_order["nextAction"])
        self.assertNotIn("Run focused AspectOrderingContractTest", aspect_order["nextAction"])
        concentration = next(row for row in data["riskLedger"] if row["id"] == "cross_subsystem_concentration")
        self.assertIn("Broad runtime proof is current", concentration["nextAction"])
        self.assertNotIn("move the next gate to broad runtime proof", concentration["nextAction"])
        self.assertEqual("guarded", concentration["status"])
        self.assertIn("proof", concentration["statusReason"])
        test_risk = next(row for row in data["riskLedger"] if row["id"] == "test_tree_contamination")
        self.assertIn("Broad runtime proof is current", test_risk["nextAction"])
        self.assertNotIn("move the next gate to full test runtime proof", test_risk["nextAction"])
        self.assertEqual(0, data["activeRiskCount"])
        self.assertEqual(
            {"passed": True, "suiteCount": 5, "testCount": 10, "failureCount": 0, "errorCount": 0},
            data["broadRuntimeTestProof"],
        )

    def test_current_verify_boot_log_closes_source_runtime_proof_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            log = root / "logs" / "verify_boot_source_runtime_current.log"
            log.parent.mkdir(parents=True)
            log.write_text(
                "\n".join(
                    [
                        "[AWX][verify] intentional-timeout-stop=true timeoutSeconds=45",
                        (
                            "[AWX][verify] webServerStartedProven=True wiringPrecheckProven=True "
                            "runtimePrecheckProven=True springStartedLogProven=False "
                            "applicationReadyEventProven=True bootStartedProven=True "
                            "bootSuccessProven=False probeMode=boot-started-proven"
                        ),
                        "[AWX][verify] bootRun logged failure after intentional timeout stop",
                        "[AWX][verify] bootSuccessProven=False",
                    ]
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertTrue(data["sourceRuntimeBootProof"]["passed"])
        self.assertEqual(
            "provide_supabase_project_ref_and_authenticated_readonly_mcp_or_cli_for_schema_advisor_snapshot",
            data["nextSingleAction"],
        )
        self.assertEqual("no_local_source_action_external_evidence_needed", data["nextSourceAction"])
        details = data["nextSourceActionDetails"]
        self.assertEqual(1, len(details))
        self.assertEqual("no-local-source-action", details[0]["action"])
        self.assertEqual("external_evidence", details[0]["scope"])
        self.assertFalse(details[0]["mutationAllowed"])
        self.assertTrue(
            any(
                "python scripts\\awx_mcp_toolbox.py --input-json - supabase_context_probe" in command
                and '{"root":".","skip_mcp_network_probe":true}' in command
                for command in details[0]["commands"]
            )
        )

    def test_broad_runtime_test_proof_prefers_host_split_results_without_env(self):
        old_host = os.environ.get("AWX_BUILD_HOST_ID")
        os.environ.pop("AWX_BUILD_HOST_ID", None)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                verification = root / "verification"
                verification.mkdir(parents=True)
                (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                    json.dumps(
                        {
                            "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                            "harmonyPressureSummary": {
                                "aspectFiles": 10,
                                "aspectOrderCoverageApprox": 1.0,
                                "criticalUnorderedAspectCount": 0,
                                "crossSubsystemLargeFilesOver1000": 31,
                                "broadCatchWithoutLocalBreadcrumbRatio": 0.0,
                            },
                            "testTreeContamination": {
                                "riskScore": 0.0,
                                "missingImportCount": 0,
                                "affectedTestFileCount": 0,
                            },
                            "supabaseReadonlySmoke": {
                                "readOnlyMode": True,
                                "mutationAllowed": False,
                                "projectScopeStatus": "project_ref_missing",
                            },
                            "duplicateFqcnActiveCount": 0,
                            "secretPatternHitCount": 0,
                        }
                    ),
                    encoding="utf-8",
                )
                (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                    json.dumps({"runtimeCrossSubsystemLargeFilesOver1000": 28}),
                    encoding="utf-8",
                )
                (verification / "test-tree-contamination-metrics.json").write_text(
                    json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                    encoding="utf-8",
                )
                stale_default = root / "build" / "test-results" / "test"
                stale_default.mkdir(parents=True)
                (stale_default / "TEST-com.example.lms.SingleSmokeTest.xml").write_text(
                    '<testsuite name="com.example.lms.SingleSmokeTest" tests="1" skipped="0" failures="0" errors="0">'
                    '<testcase classname="com.example.lms.SingleSmokeTest" name="smoke"/></testsuite>',
                    encoding="utf-8",
                )
                host_results = root / "build" / "desktop-codex" / "test-results" / "test"
                host_results.mkdir(parents=True)
                for class_name in (
                    "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                    "com.example.lms.orchestration.StrategyConflictResolverTest",
                    "com.example.lms.orchestration.ExecutionPlanApplierTest",
                    "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
                    "com.example.lms.service.ChatWorkflowTraceRedactionContractTest",
                ):
                    (host_results / f"TEST-{class_name}.xml").write_text(
                        f'<testsuite name="{class_name}" tests="2" skipped="0" failures="0" errors="0">'
                        f'<testcase classname="{class_name}" name="contractA"/>'
                        f'<testcase classname="{class_name}" name="contractB"/></testsuite>',
                        encoding="utf-8",
                    )

                data = source_health_scorecard.build_scorecard(root)
        finally:
            if old_host is None:
                os.environ.pop("AWX_BUILD_HOST_ID", None)
            else:
                os.environ["AWX_BUILD_HOST_ID"] = old_host

        self.assertEqual("source_runtime_proof_current", data["nextSourceAction"])
        self.assertEqual(
            {"passed": True, "suiteCount": 5, "testCount": 10, "failureCount": 0, "errorCount": 0},
            data["broadRuntimeTestProof"],
        )

    def test_broad_runtime_test_proof_skips_inaccessible_result_roots(self):
        class DeniedPath:
            def is_dir(self):
                raise PermissionError("denied")

        original_roots = source_health_scorecard._test_result_roots
        try:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                good_results = root / "build" / "desktop-codex" / "test-results" / "test"
                good_results.mkdir(parents=True)
                for class_name in (
                    "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                    "com.example.lms.orchestration.StrategyConflictResolverTest",
                    "com.example.lms.orchestration.ExecutionPlanApplierTest",
                    "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
                    "com.example.lms.service.ChatWorkflowTraceRedactionContractTest",
                ):
                    (good_results / f"TEST-{class_name}.xml").write_text(
                        f'<testsuite name="{class_name}" tests="1" skipped="0" failures="0" errors="0">'
                        f'<testcase classname="{class_name}" name="contract"/></testsuite>',
                        encoding="utf-8",
                    )
                source_health_scorecard._test_result_roots = lambda _root: [DeniedPath(), good_results]

                proof = source_health_scorecard._broad_runtime_test_proof(root)
        finally:
            source_health_scorecard._test_result_roots = original_roots

        self.assertEqual(
            {"passed": True, "suiteCount": 5, "testCount": 5, "failureCount": 0, "errorCount": 0},
            proof,
        )

    def test_live_websoak_smoke_overrides_stale_provider_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            generated_at = _current_utc_iso()
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "generatedAt": generated_at,
                        "runtimeProviderDisabledSmoke": {
                            "status": 500,
                            "providerDisabledOrSkipped": False,
                            "secretPatternHits": 0,
                            "rawQueryHits": 0,
                        },
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0},
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            websoak = verification / "websoak-kpi-smoke"
            websoak.mkdir(parents=True)
            (websoak / "websoak-kpi-provider-disabled.json").write_text(
                json.dumps(
                    {
                        "generatedAt": generated_at,
                        "summary": {
                            "status": 200,
                            "providerDisabledOrSkipped": True,
                            "secretPatternHits": 0,
                            "rawQueryHits": 0,
                        }
                    }
                ),
                encoding="utf-8-sig",
            )

            data = source_health_scorecard.build_scorecard(root)

        provider = next(row for row in data["componentScores"] if row["id"] == "runtime_provider_safety")
        self.assertEqual(provider["normalized"], 0.93)
        self.assertIn("status=200", provider["evidence"])

    def test_project_scoped_supabase_still_requires_data_api_grant_and_rls_result_sets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0},
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_ready",
                            "missingResultCount": 2,
                            "missingResultNames": [
                                "data_api_role_grants",
                                "exposed_tables_without_rls",
                            ],
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("supabase_data_api_grants_and_rls_result_sets", evidence_items)
        self.assertIn("collect_supabase_data_api_grants_and_rls_result_sets", data["nextSingleAction"])
        supabase = next(row for row in data["componentScores"] if row["id"] == "supabase_external_evidence")
        self.assertLess(supabase["normalized"], 0.85)
        self.assertIn("data_api_role_grants", supabase["evidence"])

    def test_supabase_data_api_result_sets_are_external_not_source_action_when_local_proof_is_current(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            _write_green_boot_log(root)
            quant_path = root / "verification" / "dynamic-rag-quant-audit-metrics.json"
            quant = json.loads(quant_path.read_text(encoding="utf-8"))
            quant["supabaseReadonlySmoke"] = {
                "readOnlyMode": True,
                "mutationAllowed": False,
                "projectScopeStatus": "project_ref_ready",
                "missingResultCount": 3,
                "missingResultNames": [
                    "rls_and_table_flags",
                    "data_api_role_grants",
                    "exposed_tables_without_rls",
                ],
            }
            quant_path.write_text(json.dumps(quant), encoding="utf-8")

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("supabase_data_api_grants_and_rls_result_sets", evidence_items)
        self.assertEqual("collect_supabase_data_api_grants_and_rls_result_sets", data["nextSingleAction"])
        self.assertEqual("no_local_source_action_external_evidence_needed", data["nextSourceAction"])
        details = data["nextSourceActionDetails"]
        self.assertEqual(1, len(details))
        self.assertEqual("no-local-source-action", details[0]["action"])
        self.assertEqual("external_evidence", details[0]["scope"])
        self.assertFalse(details[0]["mutationAllowed"])
        self.assertIn("SUPABASE_PROJECT_REF", details[0]["evidenceNeeded"])
        self.assertIn("execute_sql_results", details[0]["evidenceNeeded"])
        self.assertIn("get_advisors_results", details[0]["evidenceNeeded"])
        self.assertTrue(
            any(
                "python scripts\\awx_mcp_toolbox.py --input-json - supabase_context_probe" in command
                and '{"root":".","skip_mcp_network_probe":true}' in command
                for command in details[0]["commands"]
            )
        )

    def test_desktop_only_status_demotes_supabase_external_gap_from_primary_next_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            _write_green_boot_log(root)
            _write_desktop_only_goal_next_status(root)
            quant_path = root / "verification" / "dynamic-rag-quant-audit-metrics.json"
            quant = json.loads(quant_path.read_text(encoding="utf-8"))
            quant["supabaseReadonlySmoke"] = {
                "readOnlyMode": True,
                "mutationAllowed": False,
                "projectScopeStatus": "project_ref_ready",
                "missingResultCount": 3,
                "missingResultNames": [
                    "rls_and_table_flags",
                    "data_api_role_grants",
                    "exposed_tables_without_rls",
                ],
            }
            quant_path.write_text(json.dumps(quant), encoding="utf-8")

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("supabase_data_api_grants_and_rls_result_sets", evidence_items)
        self.assertEqual("none_for_desktop_only", data["nextSingleAction"])
        self.assertEqual("no_local_source_action_external_evidence_needed", data["nextSourceAction"])
        self.assertEqual(
            "collect_supabase_data_api_grants_and_rls_result_sets",
            data["externalEvidenceNextAction"],
        )
        details = data["nextSourceActionDetails"]
        self.assertEqual(1, len(details))
        self.assertEqual("external_evidence", details[0]["scope"])
        self.assertFalse(details[0]["mutationAllowed"])
        self.assertIn("SUPABASE_PROJECT_REF", details[0]["evidenceNeeded"])

    def test_supabase_shadow_memory_result_sets_are_named_in_external_evidence_gap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            _write_green_boot_log(root)
            quant_path = root / "verification" / "dynamic-rag-quant-audit-metrics.json"
            quant = json.loads(quant_path.read_text(encoding="utf-8"))
            quant["supabaseReadonlySmoke"] = {
                "readOnlyMode": True,
                "mutationAllowed": False,
                "projectScopeStatus": "project_ref_ready",
                "missingResultCount": 6,
                "missingResultNames": [
                    "rls_and_table_flags",
                    "data_api_role_grants",
                    "exposed_tables_without_rls",
                    "shadow_memory_candidate_tables",
                    "shadow_memory_candidate_columns",
                    "shadow_memory_metadata_fingerprints",
                ],
            }
            quant_path.write_text(json.dumps(quant), encoding="utf-8")

            data = source_health_scorecard.build_scorecard(root)

        evidence = next(
            item
            for item in data["evidenceNeeded"]
            if item["item"] == "supabase_data_api_grants_and_rls_result_sets"
        )
        self.assertIn("shadow_memory_candidate_tables", evidence["reason"])
        self.assertIn("shadow_memory_candidate_columns", evidence["reason"])
        self.assertIn("shadow_memory_metadata_fingerprints", evidence["reason"])
        self.assertEqual("collect_supabase_data_api_grants_and_rls_result_sets", data["nextSingleAction"])
        self.assertEqual("no_local_source_action_external_evidence_needed", data["nextSourceAction"])

    def test_supabase_component_references_latest_completion_audit_smoke_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "reportsEnvPreflight=True;"
                                    "summaryArtifact=True;"
                                    "summaryGeneratedAt=True;"
                                    "summaryFresh=True;"
                                    "summaryFreshnessStatus=current;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "cliQueryMinVersion=>=2.79.0;"
                                    "cliAdvisorsMinVersion=>=2.81.3;"
                                    "mcpFallbackContract=True;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0;"
                                    "envPresentCount=1;"
                                    "projectRefEnvPresent=True;"
                                    "accessTokenEnvPresent=False;"
                                    "cliPresent=False;"
                                    "contextEvidenceNeededCount=2"
                                ),
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        supabase = next(row for row in data["componentScores"] if row["id"] == "supabase_external_evidence")
        self.assertIn("localSmokeContractReady=True", supabase["evidence"])
        self.assertIn("summaryFresh=True", supabase["evidence"])
        self.assertIn("summaryFreshnessStatus=current", supabase["evidence"])
        self.assertIn("cliQueryMinVersion=>=2.79.0", supabase["evidence"])
        self.assertIn("cliAdvisorsMinVersion=>=2.81.3", supabase["evidence"])
        self.assertIn("mcpFallbackContract=True", supabase["evidence"])
        self.assertEqual(
            "var/codex-smoke/awx-mcp-completion-audit-z.json",
            data["inputArtifacts"]["completionAudit"],
        )
        self.assertEqual(
            "var/codex-smoke/awx-mcp-completion-audit-z.json",
            data["completionAuditFreshness"]["path"],
        )
        self.assertEqual(
            source_health_scorecard._stable_hash("var/codex-smoke/awx-mcp-completion-audit-z.json"),
            data["completionAuditFreshness"]["pathHash"],
        )
        self.assertNotIn(str(root), json.dumps(data, ensure_ascii=False))

    def test_computer_use_boundary_is_reported_from_completion_audit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "summaryArtifact=True;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0"
                                ),
                            },
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "promptPack=True;"
                                    "guiOnly=True;"
                                    "noTerminalAutomation=True;"
                                    "supportingOnly=True;"
                                    "helperArtifact=True;"
                                    "helperReachable=True;"
                                    "helperGeneratedAt=True;"
                                    "helperFresh=True;"
                                    "helperFreshnessStatus=current;"
                                    "helperCountOnly=True;"
                                    "helperBoundary=True;"
                                    "storesAppNames=False;"
                                    "storesWindowTitles=False;"
                                    "helperSecretPatternHits=0;"
                                    "rawSecretPatternHits=0"
                                ),
                            },
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        computer_use = next(row for row in data["componentScores"] if row["id"] == "computer_use_evidence_boundary")
        self.assertEqual(1.0, computer_use["normalized"])
        self.assertIn("guiOnly=True", computer_use["evidence"])
        self.assertIn("noTerminalAutomation=True", computer_use["evidence"])
        self.assertIn("supportingOnly=True", computer_use["evidence"])
        self.assertIn("helperReachable=True", computer_use["evidence"])
        self.assertIn("helperGeneratedAt=True", computer_use["evidence"])
        self.assertIn("helperFresh=True", computer_use["evidence"])
        self.assertIn("helperFreshnessStatus=current", computer_use["evidence"])
        self.assertIn("helperCountOnly=True", computer_use["evidence"])
        self.assertIn("helperBoundary=True", computer_use["evidence"])
        self.assertIn("storesAppNames=False", computer_use["evidence"])
        self.assertIn("storesWindowTitles=False", computer_use["evidence"])
        self.assertIn("helperSecretPatternHits=0", computer_use["evidence"])
        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertNotIn("computer_use_gui_proof_boundary", evidence_items)

    def test_browser_ui_boundary_is_reported_from_completion_audit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            generated_at = _current_utc_iso()
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "generatedAt": generated_at,
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps({"generatedAt": generated_at}),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps(
                    {
                        "generatedAt": generated_at,
                        "riskScore": 0.0,
                        "missingImportCount": 0,
                        "affectedTestFileCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "summaryArtifact=True;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0"
                                ),
                            },
                            {
                                "id": "browser-use.ui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "artifactPath=var/codex-smoke/browser-ui-smoke.json;"
                                    "artifactPresent=True;"
                                    "artifactGeneratedAt=True;"
                                    "artifactFresh=True;"
                                    "artifactFreshnessStatus=current;"
                                    "reachable=True;"
                                    "targetAccepted=True;"
                                    "localhost=True;"
                                    "publicDomain=False;"
                                    "screenshotCaptured=True;"
                                    "targetContentVisible=True;"
                                    "storesRawUrl=False;"
                                    "storesScreenshotPath=False;"
                                    "secretPatternHits=0"
                                ),
                            },
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        browser = next(row for row in data["componentScores"] if row["id"] == "browser_ui_evidence_boundary")
        self.assertEqual(1.0, browser["normalized"])
        self.assertIn("reachable=True", browser["evidence"])
        self.assertIn("targetAccepted=True", browser["evidence"])
        self.assertIn("screenshotCaptured=True", browser["evidence"])
        self.assertIn("targetContentVisible=True", browser["evidence"])
        self.assertIn("storesRawUrl=False", browser["evidence"])
        self.assertIn("storesScreenshotPath=False", browser["evidence"])
        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertNotIn("browser_ui_proof_boundary", evidence_items)

    def test_local_interaction_proof_summary_preserves_browser_and_computer_boundaries(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "reportsEnvPreflight=True;"
                                    "summaryArtifact=True;"
                                    "summaryGeneratedAt=True;"
                                    "summaryFresh=True;"
                                    "summaryFreshnessStatus=current;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "cliQueryMinVersion=>=2.79.0;"
                                    "cliAdvisorsMinVersion=>=2.81.3;"
                                    "mcpFallbackContract=True;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0;"
                                    "envPresentCount=0;"
                                    "projectRefEnvPresent=False;"
                                    "accessTokenEnvPresent=False;"
                                    "cliPresent=False;"
                                    "contextEvidenceNeededCount=1"
                                ),
                            },
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "promptPack=True;"
                                    "guiOnly=True;"
                                    "noTerminalAutomation=True;"
                                    "supportingOnly=True;"
                                    "helperArtifact=True;"
                                    "helperReachable=True;"
                                    "helperGeneratedAt=True;"
                                    "helperFresh=True;"
                                    "helperFreshnessStatus=current;"
                                    "helperAgeSeconds=9;"
                                    "helperCountOnly=True;"
                                    "helperBoundary=True;"
                                    "storesAppNames=False;"
                                    "storesWindowTitles=False;"
                                    "appCount=4;"
                                    "targetableWindowCount=6;"
                                    "helperSecretPatternHits=0;"
                                    "rawSecretPatternHits=0"
                                ),
                            },
                            {
                                "id": "browser-use.ui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "artifactPath=var/codex-smoke/browser-ui-smoke.json;"
                                    "artifactPresent=True;"
                                    "artifactGeneratedAt=True;"
                                    "artifactFresh=True;"
                                    "artifactFreshnessStatus=current;"
                                    "artifactAgeSeconds=10;"
                                    "reachable=True;"
                                    "targetAccepted=True;"
                                    "localhost=True;"
                                    "publicDomain=False;"
                                    "screenshotCaptured=True;"
                                    "targetContentVisible=True;"
                                    "browserSurface=iab;"
                                    "storesRawUrl=False;"
                                    "storesScreenshotPath=False;"
                                    "secretPatternHits=0"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        proof = data["localInteractionProof"]
        self.assertEqual("local_interaction_proof", proof["schema"])
        self.assertTrue(proof["computerUse"]["boundaryReady"])
        self.assertTrue(proof["computerUse"]["guiOnly"])
        self.assertTrue(proof["computerUse"]["noTerminalAutomation"])
        self.assertTrue(proof["computerUse"]["supportingOnly"])
        self.assertTrue(proof["computerUse"]["helperCountOnly"])
        self.assertFalse(proof["computerUse"]["storesAppNames"])
        self.assertFalse(proof["computerUse"]["storesWindowTitles"])
        self.assertEqual(4, proof["computerUse"]["appCount"])
        self.assertEqual(6, proof["computerUse"]["targetableWindowCount"])
        self.assertEqual(0, proof["computerUse"]["secretPatternHits"])
        self.assertTrue(proof["browserUse"]["boundaryReady"])
        self.assertTrue(proof["browserUse"]["reachable"])
        self.assertTrue(proof["browserUse"]["targetAccepted"])
        self.assertTrue(proof["browserUse"]["screenshotCaptured"])
        self.assertTrue(proof["browserUse"]["targetContentVisible"])
        self.assertEqual("iab", proof["browserUse"]["browserSurface"])
        self.assertFalse(proof["browserUse"]["storesRawUrl"])
        self.assertFalse(proof["browserUse"]["storesScreenshotPath"])
        self.assertEqual("var/codex-smoke/browser-ui-smoke.json", proof["browserUse"]["artifactPath"])
        self.assertEqual(
            "scripts/refresh_local_interaction_smokes.ps1",
            proof["refreshCommand"]["scriptPath"],
        )
        self.assertFalse(proof["refreshCommand"]["mutationAllowed"])
        self.assertFalse(proof["refreshCommand"]["storesRawProbePayloads"])
        self.assertIn("var/codex-smoke/computer-use-smoke.json", proof["refreshCommand"]["outputPaths"])
        self.assertIn("var/codex-smoke/browser-ui-smoke.json", proof["refreshCommand"]["outputPaths"])
        rendered = json.dumps(proof, ensure_ascii=False)
        self.assertNotRegex(rendered, r"http://|https://|[A-Za-z]:[\\/]")

    def test_local_interaction_proof_accepts_safe_pending_browser_and_computer_boundaries(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "artifactPath=var/codex-smoke/computer-use-smoke.json;"
                                    "ready=False;"
                                    "safePendingProof=True;"
                                    "decision=evidence_needed;"
                                    "promptPack=True;"
                                    "guiOnly=True;"
                                    "noTerminalAutomation=True;"
                                    "supportingOnly=True;"
                                    "helperArtifact=True;"
                                    "helperReachable=False;"
                                    "helperGeneratedAt=True;"
                                    "helperFresh=True;"
                                    "helperFreshnessStatus=current;"
                                    "helperAgeSeconds=12;"
                                    "helperCountOnly=True;"
                                    "helperBoundary=True;"
                                    "storesAppNames=False;"
                                    "storesWindowTitles=False;"
                                    "appCount=0;"
                                    "targetableWindowCount=0;"
                                    "helperSecretPatternHits=0;"
                                    "rawSecretPatternHits=0"
                                ),
                            },
                            {
                                "id": "browser-use.ui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "artifactPath=var/codex-smoke/browser-ui-smoke.json;"
                                    "ready=False;"
                                    "safePendingProof=True;"
                                    "artifactPresent=True;"
                                    "artifactGeneratedAt=True;"
                                    "artifactFresh=True;"
                                    "artifactFreshnessStatus=current;"
                                    "artifactAgeSeconds=12;"
                                    "schemaVersion=awx.local.browser_ui_smoke.v1;"
                                    "decision=evidence_needed;"
                                    "reachable=False;"
                                    "targetAccepted=False;"
                                    "localhost=False;"
                                    "publicDomain=False;"
                                    "screenshotCaptured=False;"
                                    "targetContentVisible=False;"
                                    "browserSurface=unknown;"
                                    "storesRawUrl=False;"
                                    "storesScreenshotPath=False;"
                                    "secretPatternHits=0"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        proof = data["localInteractionProof"]
        self.assertTrue(proof["computerUse"]["boundaryReady"])
        self.assertFalse(proof["computerUse"]["liveReady"])
        self.assertTrue(proof["computerUse"]["safePendingProof"])
        self.assertEqual("evidence_needed", proof["computerUse"]["decision"])
        self.assertFalse(proof["computerUse"]["helperReachable"])
        self.assertTrue(proof["browserUse"]["boundaryReady"])
        self.assertFalse(proof["browserUse"]["liveReady"])
        self.assertTrue(proof["browserUse"]["safePendingProof"])
        self.assertEqual("evidence_needed", proof["browserUse"]["decision"])
        self.assertFalse(proof["browserUse"]["reachable"])
        self.assertNotIn(
            "computer_use_gui_proof_boundary",
            {row["item"] for row in data["evidenceNeeded"]},
        )
        self.assertNotIn(
            "browser_ui_proof_boundary",
            {row["item"] for row in data["evidenceNeeded"]},
        )

    def test_external_input_gate_proof_promotes_supabase_external_boundary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "requirements": [
                            {
                                "id": "goal-next-auto-command-packet",
                                "status": "satisfied",
                                "evidence": (
                                    "goal-next.command-packet: latestPresent=True;"
                                    "externalInputGateStatus=external_input_needed;"
                                    "externalInputGateSource=supabase_apply;"
                                    "externalInputGateAction=set_SUPABASE_PROJECT_REF;"
                                    "externalInputGateLocalPatchJustified=False;"
                                    "externalInputGateMutationAllowed=False;"
                                    "externalInputGateEvidenceNeeded=SUPABASE_PROJECT_REF,"
                                    "read_only_supabase_mcp_or_cli_auth,execute_sql_results,get_advisors_results;"
                                    "rawSecretPatternHits=0"
                                ),
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        proof = data["externalInputGateProof"]
        self.assertEqual("external_input_gate_proof", proof["schema"])
        self.assertTrue(proof["observed"])
        self.assertTrue(proof["boundaryReady"])
        self.assertEqual("external_input_needed", proof["status"])
        self.assertEqual("supabase_apply", proof["source"])
        self.assertEqual("set_SUPABASE_PROJECT_REF", proof["action"])
        self.assertFalse(proof["localPatchJustified"])
        self.assertFalse(proof["mutationAllowed"])
        self.assertIn("SUPABASE_PROJECT_REF", proof["evidenceNeeded"])
        self.assertIn("execute_sql_results", proof["evidenceNeeded"])
        self.assertEqual(0, proof["secretPatternHits"])

    def test_external_input_gate_proof_accepts_desktop_only_local_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "requirements": [
                            {
                                "id": "goal-next-auto-command-packet",
                                "status": "satisfied",
                                "evidence": (
                                    "goal-next.command-packet: latestPresent=True;"
                                    "externalInputGateStatus=local_or_unknown;"
                                    "externalInputGateSource=evidence_needed;"
                                    "externalInputGateAction=evidence_needed;"
                                    "externalInputGateLocalPatchJustified=True;"
                                    "externalInputGateMutationAllowed=False;"
                                    "externalInputGateEvidenceNeeded=;"
                                    "rawSecretPatternHits=0"
                                ),
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        proof = data["externalInputGateProof"]
        self.assertTrue(proof["observed"])
        self.assertTrue(proof["boundaryReady"])
        self.assertEqual("local_or_unknown", proof["status"])
        self.assertTrue(proof["localPatchJustified"])

    def test_external_input_gate_proof_accepts_desktop_local_primary_gate(self):
        proof = source_health_scorecard._external_input_gate_proof_summary(
            requirement_ok=True,
            evidence={
                "externalInputGateStatus": "local_or_unknown",
                "externalInputGateSource": "source_health_scorecard",
                "externalInputGateAction": "audit-broad-catches-redacted-breadcrumbs",
                "externalInputGateLocalPatchJustified": "True",
                "externalInputGateMutationAllowed": "False",
                "externalInputGateEvidenceNeeded": "",
                "rawSecretPatternHits": "0",
            },
        )

        self.assertTrue(proof["boundaryReady"])
        self.assertEqual("source_health_scorecard", proof["source"])
        self.assertEqual("audit-broad-catches-redacted-breadcrumbs", proof["action"])

    def test_goal_next_status_proof_reads_direct_status_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "goal-next-auto.status.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.status.v1",
                        "generatedAt": _current_utc_iso(),
                        "latestDecision": "evidence_needed",
                        "statusDecision": "evidence_needed",
                        "failureClassification": "evidence_needed",
                        "staleLatest": False,
                        "firstAction": "set_SUPABASE_PROJECT_REF",
                        "firstActionSource": "supabase_apply",
                        "externalInputGate": {
                            "status": "external_input_needed",
                            "source": "supabase_apply",
                            "action": "set_SUPABASE_PROJECT_REF",
                            "localPatchJustified": False,
                            "mutationAllowed": False,
                            "evidenceNeeded": [
                                "SUPABASE_PROJECT_REF",
                                "read_only_supabase_mcp_or_cli_auth",
                                "execute_sql_results",
                                "get_advisors_results",
                            ],
                            "secretHits": 0,
                            "windowsAbsPathHits": 0,
                        },
                        "secretHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        proof = data["goalNextStatusProof"]
        self.assertEqual("goal_next_status_proof", proof["schema"])
        self.assertTrue(proof["observed"])
        self.assertTrue(proof["fresh"])
        self.assertTrue(proof["boundaryReady"])
        self.assertEqual("evidence_needed", proof["statusDecision"])
        self.assertEqual("set_SUPABASE_PROJECT_REF", proof["firstAction"])
        self.assertEqual("external_input_needed", proof["externalInputGate"]["status"])
        self.assertFalse(proof["externalInputGate"]["localPatchJustified"])
        self.assertFalse(proof["externalInputGate"]["mutationAllowed"])
        self.assertIn("get_advisors_results", proof["externalInputGate"]["evidenceNeeded"])
        self.assertEqual(0, proof["secretPatternHits"])
        self.assertEqual(0, proof["windowsAbsPathHits"])

    def test_goal_next_status_proof_accepts_desktop_only_local_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "goal-next-auto.status.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.status.v1",
                        "generatedAt": _current_utc_iso(),
                        "latestDecision": "evidence_needed",
                        "statusDecision": "evidence_needed",
                        "failureClassification": "evidence_needed",
                        "staleLatest": False,
                        "firstAction": "evidence_needed",
                        "firstActionSource": "evidence_needed",
                        "externalInputGate": {
                            "status": "local_or_unknown",
                            "source": "evidence_needed",
                            "action": "evidence_needed",
                            "localPatchJustified": True,
                            "mutationAllowed": False,
                            "evidenceNeeded": [],
                            "secretHits": 0,
                            "windowsAbsPathHits": 0,
                        },
                        "secretHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        proof = data["goalNextStatusProof"]
        self.assertTrue(proof["boundaryReady"])
        self.assertEqual("local_or_unknown", proof["externalInputGate"]["status"])
        self.assertTrue(proof["externalInputGate"]["localPatchJustified"])

    def test_goal_next_status_proof_accepts_desktop_local_primary_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "goal-next-auto.status.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.status.v1",
                        "generatedAt": _current_utc_iso(),
                        "latestDecision": "evidence_needed",
                        "statusDecision": "evidence_needed",
                        "failureClassification": "evidence_needed",
                        "staleLatest": False,
                        "firstAction": "audit-broad-catches-redacted-breadcrumbs",
                        "firstActionSource": "source_health_scorecard",
                        "externalInputGate": {
                            "status": "local_or_unknown",
                            "source": "source_health_scorecard",
                            "action": "audit-broad-catches-redacted-breadcrumbs",
                            "localPatchJustified": True,
                            "mutationAllowed": False,
                            "evidenceNeeded": [],
                            "secretHits": 0,
                            "windowsAbsPathHits": 0,
                        },
                        "secretHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertTrue(data["goalNextStatusProof"]["boundaryReady"])

    def test_goal_next_collection_packet_proof_reads_completion_audit_requirement(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "goal-next.collection-packet",
                                "ok": True,
                                "evidence": (
                                    "collectionPacketPresent=True;"
                                    "schemaVersion=awx.goal_next_auto.collection_packet.v1;"
                                    "supabaseReadOnly=True;"
                                    "supabaseMutationAllowed=False;"
                                    "supabaseRequiredEnvNames=SUPABASE_PROJECT_REF;"
                                    "supabaseRequiredMcpTools=execute_sql,get_advisors;"
                                    "supabaseMcpConfigTokenStored=False;"
                                    "externalRoles=macmini,notebook;"
                                    "externalSourceIsolation=True;"
                                    "webProbeRefreshReady=True;"
                                    "webProbeRefreshSourceCount=5;"
                                    "localInteractionRefreshReady=True;"
                                    "computerUseSafe=True;"
                                    "browserUseSafe=True;"
                                    "windowsAbsPathHits=0;"
                                    "rawSecretPatternHits=0"
                                ),
                            }
                        ],
                        "requirements": [
                            {
                                "id": "goal-next-auto-collection-packet",
                                "status": "satisfied",
                                "evidence": "goal-next.collection-packet: collectionPacketPresent=True",
                                "evidenceNeeded": [],
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        proof = data["goalNextCollectionPacketProof"]
        self.assertEqual("goal_next_collection_packet_proof", proof["schema"])
        self.assertTrue(proof["observed"])
        self.assertTrue(proof["boundaryReady"])
        self.assertEqual("satisfied", proof["requirementStatus"])
        self.assertEqual("SUPABASE_PROJECT_REF", proof["supabaseRequiredEnvNames"])
        self.assertEqual("execute_sql,get_advisors", proof["supabaseRequiredMcpTools"])
        self.assertTrue(proof["supabaseReadOnly"])
        self.assertFalse(proof["supabaseMutationAllowed"])
        self.assertFalse(proof["supabaseMcpConfigTokenStored"])
        self.assertEqual("macmini,notebook", proof["externalRoles"])
        self.assertTrue(proof["externalSourceIsolation"])
        self.assertTrue(proof["webProbeRefreshReady"])
        self.assertTrue(proof["localInteractionRefreshReady"])
        self.assertTrue(proof["computerUseSafe"])
        self.assertTrue(proof["browserUseSafe"])
        self.assertEqual(0, proof["secretPatternHits"])
        self.assertEqual(0, proof["windowsAbsPathHits"])

    def test_goal_next_collection_packet_accepts_optional_web_contract_without_fake_proof(self):
        evidence = {
            "collectionPacketPresent": "True",
            "schemaVersion": "awx.goal_next_auto.collection_packet.v1",
            "supabaseReadOnly": "True",
            "supabaseMutationAllowed": "False",
            "supabaseRequiredEnvNames": "SUPABASE_PROJECT_REF",
            "supabaseRequiredMcpTools": "execute_sql,get_advisors",
            "supabaseMcpConfigTokenStored": "False",
            "externalRoles": "macmini,notebook",
            "externalSourceIsolation": "True",
            "webProbeRefreshReady": "True",
            "webProbeRefreshBoundaryReady": "True",
            "webProbeRefreshContractReady": "True",
            "webProbeRefreshProofReady": "False",
            "webProbeRefreshRequested": "False",
            "webProbeRefreshProcessExecuted": "False",
            "webProbeRefreshTargetCount": "5",
            "webProbeRefreshSourceCount": "0",
            "localInteractionRefreshReady": "True",
            "computerUseSafe": "True",
            "browserUseSafe": "True",
            "windowsAbsPathHits": "0",
            "rawSecretPatternHits": "0",
        }

        proof = source_health_scorecard._goal_next_collection_packet_proof_summary(
            requirement_ok=False,
            check_ok=True,
            evidence=evidence,
        )

        self.assertTrue(proof["boundaryReady"])
        self.assertTrue(proof["webProbeRefreshBoundaryReady"])
        self.assertTrue(proof["webProbeRefreshContractReady"])
        self.assertFalse(proof["webProbeRefreshProofReady"])
        self.assertFalse(proof["webProbeRefreshRequested"])
        self.assertFalse(proof["webProbeRefreshProcessExecuted"])

    def test_goal_next_collection_packet_fails_closed_when_requested_web_proof_is_missing(self):
        evidence = {
            "collectionPacketPresent": "True",
            "schemaVersion": "awx.goal_next_auto.collection_packet.v1",
            "supabaseReadOnly": "True",
            "supabaseMutationAllowed": "False",
            "supabaseRequiredEnvNames": "SUPABASE_PROJECT_REF",
            "supabaseRequiredMcpTools": "execute_sql,get_advisors",
            "supabaseMcpConfigTokenStored": "False",
            "externalRoles": "macmini,notebook",
            "externalSourceIsolation": "True",
            "webProbeRefreshReady": "False",
            "webProbeRefreshBoundaryReady": "False",
            "webProbeRefreshContractReady": "True",
            "webProbeRefreshProofReady": "False",
            "webProbeRefreshRequested": "True",
            "webProbeRefreshProcessExecuted": "True",
            "webProbeRefreshTargetCount": "5",
            "webProbeRefreshSourceCount": "0",
            "localInteractionRefreshReady": "True",
            "computerUseSafe": "True",
            "browserUseSafe": "True",
            "windowsAbsPathHits": "0",
            "rawSecretPatternHits": "0",
        }

        proof = source_health_scorecard._goal_next_collection_packet_proof_summary(
            requirement_ok=False,
            check_ok=True,
            evidence=evidence,
        )

        self.assertFalse(proof["boundaryReady"])
        self.assertTrue(proof["webProbeRefreshRequested"])
        self.assertTrue(proof["webProbeRefreshProcessExecuted"])
        self.assertFalse(proof["webProbeRefreshProofReady"])

    def test_peer_evidence_bus_proof_summary_preserves_harmony_lane_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "reportsEnvPreflight=True;"
                                    "summaryArtifact=True;"
                                    "summaryGeneratedAt=True;"
                                    "summaryFresh=True;"
                                    "summaryFreshnessStatus=current;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "cliQueryMinVersion=>=2.79.0;"
                                    "cliAdvisorsMinVersion=>=2.81.3;"
                                    "mcpFallbackContract=True;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0;"
                                    "envPresentCount=0;"
                                    "projectRefEnvPresent=False;"
                                    "accessTokenEnvPresent=False;"
                                    "cliPresent=False;"
                                    "contextEvidenceNeededCount=1"
                                ),
                            },
                            {
                                "id": "peer.evidence-bus",
                                "ok": True,
                                "evidence": (
                                    "manifestTool=True;"
                                    "stdioHandler=True;"
                                    "desktopControlLoop=True;"
                                    "promptPackPresent=True;"
                                    "laneCount=7;"
                                    "supabaseLane=True;"
                                    "browserLane=True;"
                                    "computerLane=True;"
                                    "superpowersLane=True;"
                                    "webProbeLedgerSchema=True;"
                                    "webProbeRefreshPacketSchema=True;"
                                    "rawSecretPatternHits=0"
                                ),
                            },
                        ],
                        "requirements": [
                            {
                                "id": "peer-evidence-bus-artifact",
                                "status": "satisfied",
                                "evidence": (
                                    "peerEvidenceBusArtifactPresent=True;"
                                    "schemaVersion=awx.mcp.peer_evidence_bus.v1;"
                                    "ok=True;"
                                    "decision=peer_evidence_bus;"
                                    "targetMetric=harmony;"
                                    "nodeRole=desktop;"
                                    "outputCount=7;"
                                    "promptPackPresent=True;"
                                    "missingSignals=;"
                                    "claudePeersProtocol=True;"
                                    "referenceTools=6;"
                                    "manualCheckTool=check_messages;"
                                    "requiresMutualClose=True;"
                                    "reopenRequiresExplicitFlag=True;"
                                    "safePeerIdentityContract=True;"
                                    "webProbeLedger=True;"
                                    "webProbeSourceCount=5;"
                                    "webProbeRefreshPacket=True;"
                                    "webProbeRefreshTargetCount=5;"
                                    "webProbeRefreshMutationAllowed=False;"
                                    "webProbeRefreshRawContentStored=False;"
                                    "webProbeRawContentStored=False;"
                                    "webProbeRawQueryStored=False;"
                                    "identityResolution=logical-name-preferred;"
                                    "forbiddenRawFields=rawCwd,rawMessageText,rawRepoRoot,rawSummary;"
                                    "rawSecretPatternHits=0"
                                ),
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        proof = data["peerEvidenceBusProof"]
        self.assertEqual("peer_evidence_bus_proof", proof["schema"])
        self.assertTrue(proof["contractReady"])
        self.assertTrue(proof["artifactReady"])
        self.assertEqual("harmony", proof["targetMetric"])
        self.assertEqual("desktop", proof["nodeRole"])
        self.assertEqual(7, proof["laneCount"])
        self.assertEqual(7, proof["outputCount"])
        self.assertTrue(proof["lanes"]["supabase"])
        self.assertTrue(proof["lanes"]["browser"])
        self.assertTrue(proof["lanes"]["computer"])
        self.assertTrue(proof["lanes"]["superpowers"])
        self.assertTrue(proof["claudePeersProtocol"])
        self.assertTrue(proof["safePeerIdentityContract"])
        self.assertTrue(proof["webProbeLedger"])
        self.assertEqual(5, proof["webProbeSourceCount"])
        self.assertTrue(proof["webProbeRefreshPacket"])
        self.assertEqual(5, proof["webProbeRefreshTargetCount"])
        self.assertFalse(proof["webProbeRefreshMutationAllowed"])
        self.assertFalse(proof["webProbeRefreshRawContentStored"])
        self.assertFalse(proof["webProbeRawContentStored"])
        self.assertFalse(proof["webProbeRawQueryStored"])
        self.assertEqual("logical-name-preferred", proof["identityResolution"])
        self.assertIn("rawMessageText", proof["forbiddenRawFields"])
        self.assertEqual(0, proof["secretPatternHits"])
        rendered = json.dumps(proof, ensure_ascii=False)
        self.assertNotRegex(rendered, r"[A-Za-z]:[\\/]|Bearer|sbp_|rawMessageText\":\"")

    def test_incomplete_computer_use_boundary_stays_external_not_source_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "reportsEnvPreflight=True;"
                                    "summaryArtifact=True;"
                                    "summaryGeneratedAt=True;"
                                    "summaryFresh=True;"
                                    "summaryFreshnessStatus=current;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "cliQueryMinVersion=>=2.79.0;"
                                    "cliAdvisorsMinVersion=>=2.81.3;"
                                    "mcpFallbackContract=True;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0;"
                                    "envPresentCount=0;"
                                    "projectRefEnvPresent=False;"
                                    "accessTokenEnvPresent=False;"
                                    "cliPresent=False;"
                                    "contextEvidenceNeededCount=1"
                                ),
                            },
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": False,
                                "evidence": (
                                    "promptPack=True;"
                                    "guiOnly=True;"
                                    "noTerminalAutomation=True;"
                                    "supportingOnly=True;"
                                    "helperArtifact=True;"
                                    "helperReachable=True;"
                                    "helperGeneratedAt=True;"
                                    "helperFresh=False;"
                                    "helperFreshnessStatus=stale;"
                                    "helperCountOnly=True;"
                                    "helperBoundary=True;"
                                    "storesAppNames=False;"
                                    "storesWindowTitles=False;"
                                    "helperSecretPatternHits=0;"
                                    "rawSecretPatternHits=0"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("computer_use_gui_proof_boundary", evidence_items)
        self.assertEqual("repair_computer_use_gui_proof_boundary", data["nextSingleAction"])
        self.assertNotEqual("repair_computer_use_gui_proof_boundary", data["nextSourceAction"])
        self.assertEqual("run_broad_test_runtime_proof", data["nextSourceAction"])
        self.assertEqual([], data["nextSourceActionDetails"])

    def test_local_ready_completion_audit_demotes_optional_computer_gap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            _write_green_boot_log(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True, exist_ok=True)
            (smoke_dir / "awx-mcp-completion-audit-ready.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "ok": True,
                        "status": "local_control_tower_ready",
                        "hardFailures": [],
                        "supabaseLiveProofRequired": False,
                        "checked": [
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": False,
                                "evidence": (
                                    "promptPack=True;guiOnly=True;noTerminalAutomation=True;"
                                    "supportingOnly=True;helperArtifact=True;helperReachable=True;"
                                    "helperGeneratedAt=True;helperFresh=False;helperFreshnessStatus=stale;"
                                    "helperCountOnly=True;helperBoundary=True;storesAppNames=False;"
                                    "storesWindowTitles=False;helperSecretPatternHits=0;rawSecretPatternHits=0"
                                ),
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertIn(
            "computer_use_gui_proof_boundary",
            {item["item"] for item in data["evidenceNeeded"]},
        )
        self.assertEqual("none_for_desktop_only", data["nextSingleAction"])
        self.assertEqual("repair_computer_use_gui_proof_boundary", data["externalEvidenceNextAction"])
        self.assertEqual(source_health_scorecard.NO_LOCAL_SOURCE_ACTION, data["nextSourceAction"])
        self.assertEqual("external_evidence", data["nextSourceActionDetails"][0]["scope"])
        self.assertFalse(data["nextSourceActionDetails"][0]["mutationAllowed"])

    def test_local_ready_completion_audit_demotes_optional_browser_gap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            _write_green_boot_log(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True, exist_ok=True)
            (smoke_dir / "awx-mcp-completion-audit-ready.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "ok": True,
                        "status": "local_control_tower_ready",
                        "hardFailures": [],
                        "supabaseLiveProofRequired": False,
                        "checked": [
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "promptPack=True;guiOnly=True;noTerminalAutomation=True;"
                                    "supportingOnly=True;helperArtifact=True;helperReachable=True;"
                                    "helperGeneratedAt=True;helperFresh=True;helperFreshnessStatus=current;"
                                    "helperCountOnly=True;helperBoundary=True;storesAppNames=False;"
                                    "storesWindowTitles=False;helperSecretPatternHits=0;rawSecretPatternHits=0"
                                ),
                            },
                            {
                                "id": "browser-use.ui-proof-boundary",
                                "ok": False,
                                "evidence": (
                                    "artifactPresent=True;artifactGeneratedAt=True;artifactFresh=False;"
                                    "artifactFreshnessStatus=stale;reachable=False;targetAccepted=True;"
                                    "screenshotCaptured=False;targetContentVisible=False;storesRawUrl=False;"
                                    "storesScreenshotPath=False;secretPatternHits=0"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertIn(
            "browser_ui_proof_boundary",
            {item["item"] for item in data["evidenceNeeded"]},
        )
        self.assertEqual("none_for_desktop_only", data["nextSingleAction"])
        self.assertEqual("repair_browser_ui_proof_boundary", data["externalEvidenceNextAction"])
        self.assertEqual(source_health_scorecard.NO_LOCAL_SOURCE_ACTION, data["nextSourceAction"])
        self.assertEqual("external_evidence", data["nextSourceActionDetails"][0]["scope"])
        self.assertFalse(data["nextSourceActionDetails"][0]["mutationAllowed"])

    def test_incomplete_supabase_smoke_contract_is_prioritized_before_live_project_request(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-incomplete.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "rawSecretPatternHits=0"
                                ),
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("supabase_readonly_snapshot_smoke_contract", evidence_items)
        smoke_gap = next(
            item for item in data["evidenceNeeded"]
            if item["item"] == "supabase_readonly_snapshot_smoke_contract"
        )
        self.assertIn("docsRefCount", smoke_gap["reason"])
        self.assertIn("dataApiGrantProofRequired", smoke_gap["reason"])
        self.assertEqual("repair_supabase_readonly_snapshot_smoke_contract", data["nextSingleAction"])
        supabase = next(row for row in data["componentScores"] if row["id"] == "supabase_external_evidence")
        self.assertIn("localSmokeContractReady=False", supabase["evidence"])

    def test_local_ready_completion_audit_demotes_optional_supabase_smoke_gap_without_goal_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            _write_green_boot_log(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True, exist_ok=True)
            (smoke_dir / "awx-mcp-completion-audit-ready.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "ok": True,
                        "status": "local_control_tower_ready",
                        "hardFailures": [],
                        "supabaseLiveProofRequired": False,
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": False,
                                "evidence": "summaryArtifact=False;rawSecretPatternHits=0",
                            },
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "promptPack=True;"
                                    "guiOnly=True;"
                                    "noTerminalAutomation=True;"
                                    "supportingOnly=True;"
                                    "helperArtifact=True;"
                                    "helperReachable=True;"
                                    "helperGeneratedAt=True;"
                                    "helperFresh=True;"
                                    "helperFreshnessStatus=current;"
                                    "helperCountOnly=True;"
                                    "helperBoundary=True;"
                                    "storesAppNames=False;"
                                    "storesWindowTitles=False;"
                                    "helperSecretPatternHits=0;"
                                    "rawSecretPatternHits=0"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("supabase_readonly_snapshot_smoke_contract", evidence_items)
        self.assertEqual("none_for_desktop_only", data["nextSingleAction"])
        self.assertEqual(
            "provide_supabase_project_ref_and_authenticated_readonly_mcp_or_cli_for_schema_advisor_snapshot",
            data["externalEvidenceNextAction"],
        )
        self.assertEqual(
            source_health_scorecard.NO_LOCAL_SOURCE_ACTION,
            data["nextSourceAction"],
        )
        self.assertTrue(data["completionAuditLocalReady"])
        self.assertFalse(data["completionAuditSupabaseLiveProofRequired"])

    def test_stale_or_undated_completion_audit_is_not_current_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-undated.json").write_text(
                json.dumps(
                    {
                        "checked": [
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "promptPack=True;"
                                    "guiOnly=True;"
                                    "noTerminalAutomation=True;"
                                    "supportingOnly=True;"
                                    "helperArtifact=True;"
                                    "helperReachable=True;"
                                    "helperGeneratedAt=True;"
                                    "helperFresh=True;"
                                    "helperFreshnessStatus=current;"
                                    "helperCountOnly=True;"
                                    "helperBoundary=True;"
                                    "storesAppNames=False;"
                                    "storesWindowTitles=False;"
                                    "helperSecretPatternHits=0;"
                                    "rawSecretPatternHits=0"
                                ),
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("missing_generated_at", data["completionAuditFreshness"]["status"])
        self.assertFalse(data["completionAuditFreshness"]["fresh"])
        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("completion_audit_freshness", evidence_items)
        self.assertEqual("rerun_completion_audit", data["nextSingleAction"])
        computer_use = next(row for row in data["componentScores"] if row["id"] == "computer_use_evidence_boundary")
        self.assertLess(computer_use["normalized"], 1.0)

    def test_scorecard_generated_at_is_utc_aware(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        parsed = dt.datetime.fromisoformat(data["generatedAt"].replace("Z", "+00:00"))
        self.assertIsNotNone(parsed.tzinfo)
        self.assertEqual(dt.timezone.utc, parsed.astimezone(dt.timezone.utc).tzinfo)

    def test_undated_db_gap_matrix_is_not_current_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-current.json").write_text(
                json.dumps({"generatedAt": _current_utc_iso(), "checked": []}),
                encoding="utf-8",
            )
            db_gap_dir = root / "data" / "db-gap-report"
            db_gap_dir.mkdir(parents=True)
            (db_gap_dir / "gap_matrix.json").write_text(
                json.dumps(
                    {
                        "external_supabase_snapshot": {
                            "snapshotImport": {
                                "missingResultNames": ["rls_and_table_flags"],
                            }
                        }
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("missing_generated_at", data["dbGapMatrixFreshness"]["status"])
        self.assertFalse(data["dbGapMatrixFreshness"]["fresh"])
        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("db_gap_matrix_freshness", evidence_items)
        self.assertEqual("rerun_db_gap_scanner", data["nextSingleAction"])
        self.assertEqual("rerun_db_gap_scanner", data["nextSourceAction"])
        commands = data["nextSourceActionDetails"][0]["commands"]
        self.assertIn(
            "python scripts\\awx_mcp_completion_audit.py --root . --output "
            "var\\codex-smoke\\awx-mcp-completion-audit-current.json",
            commands,
        )

    def test_completion_audit_reader_prefers_valid_canonical_over_newer_legacy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            canonical = smoke_dir / "awx-mcp-completion-audit-current.json"
            legacy = smoke_dir / "awx-mcp-completion-audit-z.json"
            canonical.write_text(json.dumps({"marker": "canonical"}), encoding="utf-8")
            legacy.write_text(json.dumps({"marker": "legacy"}), encoding="utf-8")
            canonical.touch()
            legacy.touch()
            canonical_stat = canonical.stat()
            os.utime(canonical, (canonical_stat.st_atime - 60, canonical_stat.st_mtime - 60))

            data, path = source_health_scorecard._read_latest_completion_audit(root)

        self.assertEqual("canonical", data["marker"])
        self.assertEqual("var/codex-smoke/awx-mcp-completion-audit-current.json", path)

    def test_completion_audit_reader_falls_back_when_canonical_is_malformed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-current.json").write_text("{", encoding="utf-8")
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps({"marker": "legacy"}), encoding="utf-8"
            )

            data, path = source_health_scorecard._read_latest_completion_audit(root)

        self.assertEqual("legacy", data["marker"])
        self.assertEqual("var/codex-smoke/awx-mcp-completion-audit-z.json", path)

    def test_completion_audit_reader_falls_back_when_canonical_is_not_an_object(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-current.json").write_text("[]", encoding="utf-8")
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps({"marker": "legacy"}), encoding="utf-8"
            )

            data, path = source_health_scorecard._read_latest_completion_audit(root)

        self.assertEqual("legacy", data["marker"])
        self.assertEqual("var/codex-smoke/awx-mcp-completion-audit-z.json", path)

    def test_completion_audit_reader_keeps_valid_stale_canonical_authoritative(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            stale_generated_at = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=2)).isoformat()
            (smoke_dir / "awx-mcp-completion-audit-current.json").write_text(
                json.dumps({"generatedAt": stale_generated_at, "marker": "canonical-stale"}), encoding="utf-8"
            )
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps({"generatedAt": _current_utc_iso(), "marker": "legacy-current"}), encoding="utf-8"
            )

            data, path = source_health_scorecard._read_latest_completion_audit(root)

        self.assertEqual("canonical-stale", data["marker"])
        self.assertEqual("var/codex-smoke/awx-mcp-completion-audit-current.json", path)

    def test_completion_audit_reader_accepts_powershell_utf16_redirect_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            redirected = smoke_dir / "awx-mcp-completion-audit-current.json"
            redirected.write_bytes(
                json.dumps({"generatedAt": _current_utc_iso(), "checked": []}).encode("utf-16")
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual(
            "var/codex-smoke/awx-mcp-completion-audit-current.json",
            data["completionAuditFreshness"]["path"],
        )
        self.assertEqual("current", data["completionAuditFreshness"]["status"])
        self.assertTrue(data["completionAuditFreshness"]["fresh"])

    def test_completion_audit_manual_supporting_action_mode_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_green_source_runtime_fixture(root)
            _write_desktop_only_goal_next_status(root)
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True, exist_ok=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [],
                        "supportingEvidenceActionMode": "manual_opt_in",
                        "supportingEvidenceNextActionsOmitted": 34,
                        "supportingEvidenceActionHint": (
                            "rerun with --include-supporting-next-actions for manual external evidence commands"
                        ),
                        "supportingEvidenceNeededDetailMode": "compact",
                        "supportingEvidenceNeededDetailsOmitted": 3,
                        "supportingEvidenceNeededDetailHint": (
                            "rerun with --include-supporting-next-actions for full supporting evidence text"
                        ),
                        "requirements": [
                            {
                                "id": "producer-external-proof",
                                "status": "evidence_needed",
                                "evidence": "nodeSmokeRoles=;handoffRoles=;bundleRoles=",
                                "evidenceNeeded": [
                                    "external producer-proof-missing: Mac mini/Notebook smoke + handoff JSON + PatchDrop v3 sidecars"
                                ],
                                "evidenceNeededDetails": [],
                                "evidenceNeededDetailMode": "compact",
                                "evidenceNeededDetailsOmitted": 3,
                                "evidenceNeededDetailHint": (
                                    "rerun with --include-supporting-next-actions for full requirement evidence text"
                                ),
                            },
                            {
                                "id": "producer-external-proof-macmini",
                                "status": "evidence_needed",
                                "evidence": "role=macmini;nodeSmoke=False;handoff=False;bundle=False",
                                "evidenceNeeded": [
                                    "external macmini producer-proof-missing: node smoke",
                                    "external macmini producer-proof-missing: handoff JSON",
                                ],
                                "evidenceNeededDetails": [],
                                "evidenceNeededDetailMode": "compact",
                                "evidenceNeededDetailsOmitted": 2,
                                "evidenceNeededDetailHint": (
                                    "rerun with --include-supporting-next-actions for full requirement evidence text"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("manual_opt_in", data["completionAuditSupportingEvidenceActionMode"])
        self.assertEqual(34, data["completionAuditSupportingEvidenceNextActionsOmitted"])
        self.assertIn(
            "--include-supporting-next-actions",
            data["completionAuditSupportingEvidenceActionHint"],
        )
        self.assertEqual("compact", data["completionAuditSupportingEvidenceNeededDetailMode"])
        self.assertEqual(3, data["completionAuditSupportingEvidenceNeededDetailsOmitted"])
        self.assertIn(
            "--include-supporting-next-actions",
            data["completionAuditSupportingEvidenceNeededDetailHint"],
        )
        self.assertEqual("compact", data["completionAuditRequirementEvidenceNeededDetailMode"])
        self.assertEqual(2, data["completionAuditRequirementEvidenceNeededCompactRowCount"])
        self.assertEqual(5, data["completionAuditRequirementEvidenceNeededDetailsOmitted"])
        self.assertEqual(95, data["completionAuditRequirementEvidenceNeededMaxLength"])
        self.assertIn(
            "--include-supporting-next-actions",
            data["completionAuditRequirementEvidenceNeededDetailHint"],
        )

    def test_supabase_smoke_contract_requires_docs_and_security_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-old-smoke.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "summaryArtifact=True;"
                                    "rawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0"
                                ),
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("supabase_readonly_snapshot_smoke_contract", evidence_items)
        smoke_gap = next(
            item for item in data["evidenceNeeded"]
            if item["item"] == "supabase_readonly_snapshot_smoke_contract"
        )
        self.assertIn("docsRefCount", smoke_gap["reason"])
        self.assertIn("dataApiGrantProofRequired", smoke_gap["reason"])
        self.assertEqual("repair_supabase_readonly_snapshot_smoke_contract", data["nextSingleAction"])
        supabase = next(row for row in data["componentScores"] if row["id"] == "supabase_external_evidence")
        self.assertIn("localSmokeContractReady=False", supabase["evidence"])

    def test_scorecard_exposes_structured_supabase_live_proof_next_action_details(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "summaryArtifact=True;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0"
                                ),
                            }
                        ],
                        "nextActionDetails": [
                            {
                                "action": "collect-supabase-live-proof",
                                "nodeRole": "desktop",
                                "targetService": "supabase",
                                "readOnly": True,
                                "mutationAllowed": False,
                                "requiredEnv": [
                                    {"name": "SUPABASE_PROJECT_REF", "sensitive": False},
                                ],
                                "supportedAuthModes": [
                                    "supabase_mcp_oauth_session",
                                    "manual_SUPABASE_ACCESS_TOKEN",
                                ],
                                "manualAuthSensitiveEnvRefs": ["SUPABASE_ACCESS_TOKEN"],
                                "mcpOAuthSupported": True,
                                "requiredMcpTools": ["execute_sql", "get_advisors"],
                                "requiredResultNames": [
                                    "data_api_role_grants",
                                    "exposed_tables_without_rls",
                                ],
                                "resultPathRecommendation": "data/db-gap-report/supabase-query-results.json",
                                "advisorResultPathRecommendation": "data/db-gap-report/supabase-advisors.json",
                                "nextActions": [
                                    "set_SUPABASE_PROJECT_REF",
                                    "authenticate_supabase_mcp_or_cli",
                                ],
                                "decision": "evidence_needed",
                            },
                            {
                                "action": "collect-external-evidence-files",
                                "nodeRole": "desktop",
                                "targetRole": "macmini",
                                "topic": "mcp-control-loop",
                                "desktopEvidencePaths": {"nodeSmoke": "C:/absolute/path.json"},
                                "requiredSidecars": [
                                    ".patch",
                                    ".report.md",
                                    ".verify.log",
                                    ".sha256.txt",
                                    ".manifest.json",
                                    "pendingNotice",
                                ],
                                "requiredSourceIsolation": {
                                    "sourceIsolation.guard": "PASS",
                                    "sourceRootKind": "local-worktree",
                                    "directCanonicalSourceEdit": False,
                                    "evidenceOnly": True,
                                    "desktopFinalProof": "evidence_needed",
                                    "rawSecretPatternHits": 0,
                                },
                                "applyCollectedEvidenceCommand": (
                                    "powershell -NoProfile -ExecutionPolicy Bypass "
                                    "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop"
                                ),
                                "producerCommands": [
                                    (
                                        "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                        "--canonical-root C:\\AbandonWare\\demo-1\\demo-1\\src --node-role macmini"
                                    ),
                                    (
                                        "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                        "--canonical-root C:\\AbandonWare\\demo-1\\demo-1\\src --patchdrop-root <PatchDrop> "
                                        "--producer-script <PatchDrop>\\producer_bundle.py --node-role macmini "
                                        "--topic mcp-control-loop --pathspec <relative/source/path>"
                                    ),
                                ],
                                "decision": "evidence_needed",
                            },
                            {
                                "action": "collect-archive-index-proof",
                                "nodeRole": "desktop",
                                "targetService": "archive",
                                "readOnly": True,
                                "mutationAllowed": False,
                                "requiredEnvNames": ["ARCHIVE_INDEX", "NAS_ARCHIVE_ROOT"],
                                "requiredMcpTools": ["archive.search", "archive.index_build"],
                                "indexPathRecommendation": "BackupsXS/index.jsonl",
                                "archiveRootRecommendation": "BackupsXS",
                                "applyCollectedEvidenceCommand": (
                                    "powershell -NoProfile -ExecutionPolicy Bypass "
                                    "-File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search"
                                ),
                                "nextActions": [
                                    "create_or_point_archive_index",
                                    "verify_archive_index_path",
                                    "rerun_archive_search",
                                ],
                                "decision": "evidence_needed",
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual(3, len(data["nextActionDetails"]))
        detail = data["nextActionDetails"][0]
        self.assertEqual("collect-supabase-live-proof", detail["action"])
        self.assertEqual(
            "https://mcp.supabase.com/mcp?"
            "project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs",
            detail["mcpEndpointTemplate"],
        )
        self.assertEqual(["execute_sql", "get_advisors"], detail["requiredMcpTools"])
        self.assertIn("data_api_role_grants", detail["requiredResultNames"])
        self.assertEqual(
            "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\supabase_apply_collected_evidence.ps1",
            detail["applyCollectedEvidenceCommand"],
        )
        self.assertEqual([{"name": "SUPABASE_PROJECT_REF", "sensitive": False}], detail["requiredEnv"])
        self.assertEqual(
            ["supabase_mcp_oauth_session", "manual_SUPABASE_ACCESS_TOKEN"],
            detail["supportedAuthModes"],
        )
        self.assertEqual(["SUPABASE_ACCESS_TOKEN"], detail["manualAuthSensitiveEnvRefs"])
        self.assertTrue(detail["mcpOAuthSupported"])
        self.assertIn("https://supabase.com/docs/guides/getting-started/mcp", detail["docsRefs"])
        self.assertIn("https://supabase.com/docs/guides/api/securing-your-api", detail["docsRefs"])
        self.assertIn(
            "https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically",
            detail["docsRefs"],
        )
        self.assertEqual(
            [
                "mcp_project_scoped_read_only",
                "data_api_grants_required",
                "rls_policy_required",
                "secret_keys_backend_only",
                "advisors_required_before_schema_claim",
            ],
            detail["officialContractSignals"],
        )
        self.assertEqual(
            {
                "mutationAllowed": False,
                "storeRawRows": False,
                "requireProjectScope": True,
                "requireAdvisors": True,
            },
            detail["collectionGuards"],
        )
        external = data["nextActionDetails"][1]
        self.assertEqual("collect-external-evidence-files", external["action"])
        self.assertEqual("macmini", external["targetRole"])
        self.assertIn(".patch", external["requiredSidecars"])
        self.assertEqual("PASS", external["requiredSourceIsolation"]["guard"])
        self.assertEqual("local-worktree", external["requiredSourceIsolation"]["sourceRootKind"])
        self.assertFalse(external["requiredSourceIsolation"]["directCanonicalSourceEdit"])
        self.assertTrue(external["requiredSourceIsolation"]["evidenceOnly"])
        self.assertIn("run_macmini_external_node_smoke", external["nextActions"])
        self.assertEqual(
            "powershell -NoProfile -ExecutionPolicy Bypass "
            "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop",
            external["applyCollectedEvidenceCommand"],
        )
        self.assertNotIn("desktopEvidencePaths", json.dumps(external, ensure_ascii=False))
        self.assertEqual(
            [
                "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                "--canonical-root <desktop-canonical-root> --node-role macmini",
                "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                "--canonical-root <desktop-canonical-root> --patchdrop-root <PatchDrop> "
                "--producer-script <PatchDrop>\\producer_bundle.py --node-role macmini "
                "--topic mcp-control-loop --pathspec <relative/source/path>",
            ],
            external["producerCommandTemplates"],
        )
        archive = data["nextActionDetails"][2]
        self.assertEqual("collect-archive-index-proof", archive["action"])
        self.assertEqual("archive", archive["targetService"])
        self.assertTrue(archive["readOnly"])
        self.assertFalse(archive["mutationAllowed"])
        self.assertEqual(["ARCHIVE_INDEX", "NAS_ARCHIVE_ROOT"], archive["requiredEnvNames"])
        self.assertEqual("BackupsXS/index.jsonl", archive["indexPathRecommendation"])
        self.assertEqual("BackupsXS", archive["archiveRootRecommendation"])
        self.assertIn("archive.search", archive["requiredMcpTools"])
        self.assertIn("verify_archive_index_path", archive["nextActions"])
        self.assertEqual(
            "powershell -NoProfile -ExecutionPolicy Bypass "
            "-File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search",
            archive["applyCollectedEvidenceCommand"],
        )
        self.assertNotIn("C:/absolute/path.json", json.dumps(data, ensure_ascii=False))
        self.assertNotIn("C:\\AbandonWare\\demo-1\\demo-1\\src", json.dumps(data, ensure_ascii=False))
        self.assertNotIn(str(root), json.dumps(data, ensure_ascii=False))

    def test_external_evidence_details_reject_unsafe_patchdrop_source_isolation(self):
        base_row = {
            "action": "collect-external-evidence-files",
            "targetRole": "macmini",
            "topic": "mcp-control-loop",
            "requiredSidecars": [
                ".patch",
                ".report.md",
                ".verify.log",
                ".sha256.txt",
                ".manifest.json",
                "pendingNotice",
            ],
            "requiredSourceIsolation": {
                "sourceIsolation.guard": "PASS",
                "sourceRootKind": "local-worktree",
                "evidenceOnly": True,
                "desktopFinalProof": "evidence_needed",
                "rawSecretPatternHits": 0,
            },
            "applyCollectedEvidenceCommand": (
                "powershell -NoProfile -ExecutionPolicy Bypass "
                "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop"
            ),
        }

        direct_edit_row = json.loads(json.dumps(base_row))
        direct_edit_row["requiredSourceIsolation"]["directCanonicalSourceEdit"] = True
        secret_hit_row = json.loads(json.dumps(base_row))
        secret_hit_row["requiredSourceIsolation"]["directCanonicalSourceEdit"] = False
        secret_hit_row["requiredSourceIsolation"]["rawSecretPatternHits"] = 1
        false_evidence_only_row = json.loads(json.dumps(base_row))
        false_evidence_only_row["requiredSourceIsolation"]["evidenceOnly"] = False

        self.assertEqual([], source_health_scorecard._safe_external_evidence_details({
            "nextActionDetails": [direct_edit_row]
        }))
        self.assertEqual([], source_health_scorecard._safe_external_evidence_details({
            "nextActionDetails": [secret_hit_row]
        }))
        self.assertEqual([], source_health_scorecard._safe_external_evidence_details({
            "nextActionDetails": [false_evidence_only_row]
        }))

    def test_gradle_task_is_registered_for_repo_owned_refresh(self):
        build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8", errors="ignore")

        self.assertIn('tasks.register<Exec>("sourceHealthScorecard")', build)
        self.assertIn("captureStructuralAuditGitState", build)
        self.assertIn(
            'captureGitBytes("status", "--porcelain=v2", "-z", "--untracked-files=all")',
            build,
        )
        self.assertIn(
            'captureGitBytes("ls-files", "--cached", "--others", "--exclude-standard", "-z")',
            build,
        )
        self.assertIn('captureGitBytes("ls-files", "--stage", "-z")', build)
        self.assertIn('captureGitBytes("ls-files", "-v", "-z")', build)
        self.assertIn('captureGitBytes("cat-file", "blob", objectId)', build)
        self.assertIn("git-skip-worktree-fallback.json", build)
        self.assertIn("dynamicRagQuantAudit", build)
        self.assertIn(":app:generateDupFqcnExcludes", build)
        self.assertIn("scripts/dynamic_rag_quant_audit.py", build)
        self.assertIn("--dup-fqcn-input", build)
        self.assertIn("--git-skip-worktree-input", build)
        self.assertIn("verification/structural-design-baseline.json", build)
        self.assertIn("verification/structural-design-debt-ledger.jsonl", build)
        self.assertIn('dependsOn("dynamicRagQuantAudit")', build)
        self.assertNotIn(
            'dependsOn("harmonyPressureReport", "testTreeContaminationReport")',
            build,
        )
        self.assertNotIn(
            'if (layout.projectDirectory.file("verification/dynamic-rag-quant-audit-metrics.json").asFile.isFile)',
            build,
        )
        self.assertIn(
            'inputs.file(layout.projectDirectory.file("verification/dynamic-rag-quant-audit-metrics.json"))',
            build,
        )
        self.assertIn(
            'inputs.file(layout.projectDirectory.file("verification/structural-design-baseline.json"))',
            build,
        )
        self.assertIn(
            'inputs.file(layout.projectDirectory.file("verification/structural-design-debt-ledger.jsonl"))',
            build,
        )
        self.assertIn("scripts/source_health_scorecard.py", build)
        self.assertIn('inputs.file(layout.projectDirectory.file("verification/websoak-kpi-smoke/websoak-kpi-provider-disabled.json")).optional()', build)
        self.assertIn('inputs.file(layout.projectDirectory.file("data/db-gap-report/gap_matrix.json")).optional()', build)
        self.assertIn('fileTree(layout.projectDirectory.dir("var/codex-smoke"))', build)
        self.assertIn('include("**/*.json")', build)
        self.assertIn('include("**/*.ndjson")', build)
        self.assertIn('exclude("chrome-cdp-*/**")', build)
        self.assertIn('exclude("**/Cache/**")', build)
        self.assertIn("verification/source-health-scorecard.json", build)
        self.assertIn("verification/source-health-failure-pattern-events.ndjson", build)
        self.assertIn("verification/source-health-patchdrop-manifest-contract.json", build)
        self.assertIn("crossSubsystemContractTest", build)
        self.assertIn('"ai/abandonware/nova/orch/aop/AspectOrderingContractTest.java"', build)
        self.assertIn('"com/example/lms/orchestration/StrategyConflictResolverTest.java"', build)
        self.assertIn('"com/example/lms/orchestration/ExecutionPlanApplierTest.java"', build)
        self.assertIn('"com/example/lms/service/rag/burst/ExtremeZTriggerTest.java"', build)
        self.assertIn("include(*crossSubsystemContractTestSources.toTypedArray())", build)

    def test_gradle_task_registers_source_health_validation_loop(self):
        build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8", errors="ignore")

        self.assertIn('tasks.register<Exec>("sourceHealthValidationLoop")', build)
        self.assertIn('dependsOn("sourceHealthScorecard")', build)
        self.assertIn("scripts/source_health_validation_loop.py", build)
        self.assertIn("--max-duration-hours", build)
        self.assertIn("9", build)
        self.assertRegex(build, r'"--max-cycles",\s*"9"')
        self.assertIn("verification/source-health-validation-loop.json", build)
        self.assertIn("verification/source-health-validation-cycles.ndjson", build)

    def test_source_runtime_current_detail_is_a_structured_source_contract(self):
        details = source_health_scorecard._safe_source_action_details("source_runtime_proof_current")

        self.assertEqual(1, len(details))
        detail = details[0]
        self.assertTrue(detail["sourceContract"])
        self.assertTrue(detail["readOnly"])
        self.assertFalse(detail["mutationAllowed"])
        self.assertGreaterEqual(len(detail["focusedTests"]), 4)
        self.assertGreaterEqual(len(detail["commands"]), 4)
        command_text = "\n".join(detail["commands"])
        self.assertIn("powershell -NoProfile -ExecutionPolicy Bypass -File .\\verify_boot.ps1", command_text)
        self.assertIn("-ServerPort 18080", command_text)
        self.assertIn("-ManagementPort 18081", command_text)
        self.assertIn("-NettyPort 18082", command_text)
        self.assertGreaterEqual(len(detail["requiredTraceKeys"]), 5)


class BroadRuntimeExecutedCoverageTest(unittest.TestCase):
    def assert_proof(self, counts, passed):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            results = root / "build" / "test-results" / "test"
            results.mkdir(parents=True)
            for index, (tests, skipped, failures, errors) in enumerate(counts):
                (results / f"TEST-ExecutedCoverage{index}.xml").write_text(
                    f'<testsuite tests="{tests}" skipped="{skipped}" '
                    f'failures="{failures}" errors="{errors}"/>',
                    encoding="utf-8",
                )
            actual = source_health_scorecard._broad_runtime_test_proof(root)
        self.assertEqual({
            "passed": passed,
            "suiteCount": len(counts),
            "testCount": sum(row[0] for row in counts),
            "failureCount": sum(row[2] for row in counts),
            "errorCount": sum(row[3] for row in counts),
        }, actual)

    def test_all_skipped_suites_are_not_runtime_proof(self):
        self.assert_proof([(1, 1, 0, 0)] * 5, False)

    def test_skipped_suite_does_not_extend_executed_breadth(self):
        self.assert_proof([(1, 0, 0, 0)] * 4 + [(1, 1, 0, 0)], False)

    def test_empty_suite_does_not_extend_executed_breadth(self):
        self.assert_proof([(1, 0, 0, 0)] * 4 + [(0, 0, 0, 0)], False)

    def test_excess_skipped_count_cannot_supply_executed_breadth(self):
        self.assert_proof([(1, 0, 0, 0)] * 4 + [(1, 2, 0, 0)], False)

    def test_partial_skips_preserve_real_executed_breadth_and_totals(self):
        self.assert_proof([(2, 1, 0, 0)] * 5, True)

    def test_five_executed_suites_preserve_existing_pass(self):
        self.assert_proof([(1, 0, 0, 0)] * 5, True)

    def test_executed_failure_still_blocks_runtime_proof(self):
        self.assert_proof([(1, 0, 0, 0)] * 4 + [(1, 0, 1, 0)], False)

    def test_executed_error_still_blocks_runtime_proof(self):
        self.assert_proof([(1, 0, 0, 0)] * 4 + [(1, 0, 0, 1)], False)


class RuntimeResultSelectionTest(unittest.TestCase):
    def setUp(self):
        self.now = dt.datetime.now(dt.timezone.utc).timestamp()
        self.environment = mock.patch.dict(os.environ, {"AWX_BUILD_HOST_ID": ""})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def results(self, root, host, task="test", count=5, failures=0, age=0):
        directory = root / "build" / host / "test-results" / task
        directory.mkdir(parents=True, exist_ok=True)
        names = list(source_health_scorecard.FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS)
        names += [f"AdditionalContract{index}" for index in range(max(0, count - len(names)))]
        for index, name in enumerate(names[:count]):
            result = directory / f"TEST-{name}.xml"
            result.write_text(
                f'<testsuite tests="1" skipped="0" failures="{failures if index == 0 else 0}" errors="0"/>',
                encoding="utf-8",
            )
            os.utime(result, (self.now - age, self.now - age))
        return directory

    def assert_broad(self, root, passed, suites=5, failures=0):
        self.assertEqual({"passed": passed, "suiteCount": suites, "testCount": suites,
                          "failureCount": failures, "errorCount": 0},
                         source_health_scorecard._broad_runtime_test_proof(root))

    def assert_focused(self, root, count):
        self.assertEqual({"passed": count == 4, "passedCount": count, "requiredCount": 4},
                         source_health_scorecard._focused_cross_subsystem_contract_proof(root))

    def test_new_failure_replaces_older_pass_for_same_task(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "older", age=3600)
            self.results(root, "newer", failures=1)
            self.assert_broad(root, False, failures=1)

    def test_new_narrow_failure_cannot_borrow_older_breadth(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "older", age=3600)
            self.results(root, "newer", count=1, failures=1)
            self.assert_broad(root, False, suites=1, failures=1)

    def test_new_pass_replaces_older_failure_for_same_task(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "older", failures=1, age=3600)
            self.results(root, "newer")
            self.assert_broad(root, True)

    def test_equal_timestamp_failure_cannot_be_hidden_by_iteration_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            clean = self.results(root, "clean")
            failed = self.results(root, "failed", failures=1)
            for order in ([clean, failed], [failed, clean]):
                with self.subTest(order=order[0].parent.parent.name):
                    with mock.patch.object(source_health_scorecard, "_test_result_roots", return_value=order):
                        self.assert_broad(root, False, failures=1)

    def test_stale_only_results_are_not_current_broad_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "old", age=source_health_scorecard.SCORE_INPUT_MAX_AGE_SECONDS + 60)
            self.assert_broad(root, False)

    def test_future_results_are_not_current_broad_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "future", age=-600)
            self.assert_broad(root, False)

    def test_one_stale_suite_cannot_extend_fresh_breadth(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            results = self.results(root, "mixed")
            path = sorted(results.glob("TEST-*.xml"))[0]
            stamp = self.now - source_health_scorecard.SCORE_INPUT_MAX_AGE_SECONDS - 60
            os.utime(path, (stamp, stamp))
            self.assert_broad(root, False)

    def test_malformed_companion_cannot_be_silently_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            results = self.results(root, "current")
            (results / "TEST-Malformed.xml").write_text("<testsuite", encoding="utf-8")
            self.assert_broad(root, False)

    def test_latest_failed_known_task_blocks_other_task_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current")
            self.results(root, "current", task="crossSubsystemContractTest", count=4, failures=1)
            self.assert_broad(root, False, suites=4, failures=1)

    def test_separate_narrow_tasks_do_not_add_up_to_broad_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current", count=4)
            self.results(root, "current", task="crossSubsystemContractTest", count=4)
            self.assert_broad(root, False, suites=4)

    def test_newer_focused_task_preserves_current_broad_task_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current", age=30)
            self.results(root, "current", task="crossSubsystemContractTest", count=4)
            self.assert_broad(root, True)

    def test_fresh_broad_control_preserves_five_field_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current")
            self.assert_broad(root, True)

    def test_focused_new_failure_is_not_rescued_by_old_host_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "older", count=4, age=3600)
            self.results(root, "newer", count=4, failures=1)
            self.assert_focused(root, 3)

    def test_focused_partial_run_does_not_borrow_missing_cases_from_old_host(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "older", count=4, age=3600)
            self.results(root, "newer", count=3)
            self.assert_focused(root, 3)

    def test_focused_newer_cross_task_failure_supersedes_older_main_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current", count=4, age=30)
            self.results(root, "current", task="crossSubsystemContractTest", count=4, failures=1)
            self.assert_focused(root, 3)

    def test_focused_equal_timestamp_failure_blocks_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current", count=4)
            self.results(root, "current", task="crossSubsystemContractTest", count=4, failures=1)
            self.assert_focused(root, 3)

    def test_focused_stale_results_are_not_current_contract_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "old", count=4, age=source_health_scorecard.SCORE_INPUT_MAX_AGE_SECONDS + 60)
            self.assert_focused(root, 0)

    def test_focused_fresh_control_preserves_existing_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current", count=4)
            self.assert_focused(root, 4)


    def test_malformed_only_companion_blocks_fresh_broad_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current")
            directory = root / "build" / "current" / "test-results" / "crossSubsystemContractTest"
            directory.mkdir(parents=True)
            (directory / "TEST-Broken.xml").write_text("<testsuite", encoding="utf-8")
            self.assert_broad(root, False, suites=0)

    def test_stale_companion_blocks_fresh_broad_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current")
            self.results(root, "current", task="crossSubsystemContractTest", count=4,
                         age=source_health_scorecard.SCORE_INPUT_MAX_AGE_SECONDS + 60)
            self.assert_broad(root, False, suites=4)

    def test_future_companion_blocks_fresh_broad_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current")
            self.results(root, "current", task="crossSubsystemContractTest", count=4, age=-600)
            self.assert_broad(root, False, suites=4)

    def test_unlisted_task_is_not_a_source_of_broad_runtime_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(root, "current", task="unlistedTask")
            self.assert_broad(root, False, suites=0)


if __name__ == "__main__":
    unittest.main()
