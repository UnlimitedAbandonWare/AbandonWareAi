import copy
import hashlib
import json
import os
import sys
import tempfile
import unittest
from contextlib import contextmanager
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS))

import dynamic_rag_quant_audit as audit


IMPORTED_CLOSURE_WAVE_REGISTRY_SHA256 = (
    audit.FROZEN_CLOSURE_WAVE_REGISTRY_SHA256
)
FIXED_NOW = datetime(2026, 8, 31, 0, 0, tzinfo=timezone.utc)
FRESH_AT = FIXED_NOW - timedelta(minutes=1)
DUP_SCHEMA = "awx.dup-fqcn-evidence.v1"
REGISTRY_SCHEMA = "awx.structural-repair-wave-registry.v1"
REGISTRY_RELATIVE = Path("verification/structural-repair-waves/registry.json")
WAVE_ONE_REGISTRY_SHA256 = (
    "3207653d65f8a78d93071e6b9c33fac8b8b206cf97a4e244cf79f0eaaac82fba"
)
TWO_WAVE_REGISTRY_SHA256 = (
    "555bfed91df4888611380a8c9a7ed9f094fd87a661c20fcc5eee6be87844c87b"
)
WAVE_ONE_DESCRIPTOR = {
    "waveId": "wave-0001",
    "ordinal": 1,
    "journalPath": "verification/structural-repair-closure-journal.jsonl",
    "proofRoot": ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave",
    "sourceBaselineId": "547d63518eacd082f7bfdcd85ba2c93f76de2534326a845b23b7b0ba506feebe",
    "sourceLedgerPayloadSha256": "fb13386860dc1385d7b08c4e0f1e71c954c9a1af3691e6696aa486d190f3c1fc",
    "sourceMetricsSemanticHash": "9b7b018ce4e09719f32836227a0e95dd1abb3fc64d69099fe51f607bd2925c8f",
    "intakeSummarySha256": "ff460bbcc4faaaf4333b08c8d194f168f5040ba836de68ec1ca862b3fb7149d1",
    "eligibleGroupsSha256": "4fbd29f284cc646a6e82b15ce98d47ff08e7aed4910bee886616782cc3dcb888",
    "targetPreimagesSha256": "8be31b01f7af04fabab40a6599cf68d6e6ebc8f28318cccbef7a0c93bb4364ae",
}
WAVE_ONE_PROOF_ROOT = ROOT / str(WAVE_ONE_DESCRIPTOR["proofRoot"])
WAVE_TWO_DESCRIPTOR = {
    "waveId": "wave-0002",
    "ordinal": 2,
    "journalPath": "verification/structural-repair-waves/wave-0002/journal.jsonl",
    "proofRoot": ".superpowers/sdd/structural-repair-waves/wave-0002",
    "sourceBaselineId": "25528e3006204446a8302960b2fadf6ec6eb20fbef1ee6cd528b668f0dfc9552",
    "sourceLedgerPayloadSha256": "727e6c8b93158c1b7113e2c96e1fec9e431698df5f7f42035b2342445ba31b00",
    "sourceMetricsSemanticHash": "676e01ec691665044db7e4b1a8f56d3e3a2d30f1f6892a569aa8711d0863b390",
    "intakeSummarySha256": "31f15e0bcac3d093b6093358ed1f2f5fc87872760fe067e5243c09a0f6868fad",
    "eligibleGroupsSha256": "24d3adf739f1a97bf796895ea1a06945653d6df6fb4bcefb0ef77a11a25e93bd",
    "targetPreimagesSha256": "2500aaab7759030a5bd567a45f08dbf7681d9876eb8efdc9b9f48ea41316fddc",
}
WAVE_TWO_PROOF_ROOT = ROOT / str(WAVE_TWO_DESCRIPTOR["proofRoot"])
WAVE_THREE_DESCRIPTOR = {
    "waveId": "wave-0003",
    "ordinal": 3,
    "journalPath": "verification/structural-repair-waves/wave-0003/journal.jsonl",
    "proofRoot": ".superpowers/sdd/structural-repair-waves/wave-0003",
    "sourceBaselineId": "5ff4e9e8607c5d5597b6740bc53e9c4d4fec7d9d84fb472b2f80dfc1c3f2da4a",
    "sourceLedgerPayloadSha256": "462d7854ee0ceb7fc7b1278d9ea0c75282db02c2091c3c71c1aa1e094573becd",
    "sourceMetricsSemanticHash": "573c550ebd351fb22475d3f10362d62c1e565b7c6d9f3138fbf44fd898967bd0",
    "intakeSummarySha256": "67a5308ef648fe0258a4af48817bd6ffac566c0869aa8c725b54312af6128da9",
    "eligibleGroupsSha256": "e7252107f978d6c1e026852661ea835e374ab0c040390163174cc4b05ad84fc8",
    "targetPreimagesSha256": "b8a5b5807c89bf127877428f09c88e5b21da3c415caa92910c552be1caba058f",
}
WAVE_FOUR_DESCRIPTOR = {
    "waveId": "wave-0004",
    "ordinal": 4,
    "journalPath": "verification/structural-repair-waves/wave-0004/journal.jsonl",
    "proofRoot": ".superpowers/sdd/structural-repair-waves/wave-0004",
    "sourceBaselineId": "e73b20e15521c9efdcbfa4f9c157a52cf8ba1b6b7945352ddf10a92d559a403f",
    "sourceLedgerPayloadSha256": "18fc05ffa04cad0c61c4409d710dc7bf3dca8fcece48ad2702affb620eeb257a",
    "sourceMetricsSemanticHash": "cf1e99355d72740300b57aaaa66defd161333d7b96c29e9de82d21ff3f1a868e",
    "intakeSummarySha256": "b3d9e57edf9a8b6e6ee548ded81818184e284f9021f8b8a168106a5d56906f72",
    "eligibleGroupsSha256": "0e73d0351a7c45c30a3afb11fad7cbd9d9111b9e8a11bd78ed071d02135adf23",
    "targetPreimagesSha256": "fbcee3af66e4aa6589a5a167613e8e42426eae007ab70db48cadfedc979ed9c6",
}
WAVE_ONE_PREDECESSOR_ISSUE_IDS = (
    "61e88541bc6f67673370b0679a08111cdc95183fb8e433b9a166345b6ce35cfe",
    "2c657bd104cdb7c58442cafb13fe13e53b5cabda0ed7e6e16f7a3cb7c3b90596",
    "a067bbd423a95dbfe406db8e35f197c1e82a093e7d726ca110c6faad319416b8",
    "4480e62855b9d1373240910055d655552b9fef07843b0ed2d718c2124ca94edc",
    "1aa6f268ed31352cf007171c2652082b9cac80d0eec2fdba52aaa80b2aa1b88b",
    "2b1522aea23fe83e13a7292c40afbe109e12f679942b345d02edf886824cd9fd",
    "a436c5f375bb1faa941063b4944bd384ac401cf2a226f92cafdc3fbeec1962c8",
    "5f0ccd0b305b49ababa05bbb71c84ad2e594f8d51c9dfa5fa634ec0ad78201d0",
    "476a5939c3bbd2602d87080a86b945f1b09f199775375b84b0e09557e9816131",
    "848e88783b147777bf1d2058f027f1ac31d8ff3e5c46538bca324e6bcb07cf07",
    "9790f827788d8962929bd2bc5efaabdf541e27e9a3801ef57f0deb439c9fca94",
)
WAVE_ONE_EVENT_IDS = (
    "ece20caac35b196815a12483c40ae4bdc6f7c01242c2e591e8f7f2af4b6b21a4",
    "1e249921f3ee8523a4061f790e5ef9bd6bb2f7c1a7b15f78aed3392b7087b58a",
    "65aa4352caf5fe7dd9d35e69f4f5e7eab53f3a42e3f65b616276b72cab868c04",
    "fadd11291a47b4667dde48b437dba9cb8cbbc461aa8596222390ea0ac5d7eb17",
    "a95aa48b0a0f011769a2e9e90450d132089379a7b4fce76070f69ff179102a50",
    "ccf2e777c195336e83044b3c1677da9c3092450b71e078e0797c3171e83c1e74",
    "25ee9f92da0892a309dc41bfc490fd5d62bd61a379bda2fafd5266217fb310d9",
    "89323ebb11d3e98f89cae5b8cac92d28269cca762fa661127841f7aa7691814a",
    "5a2e4d530a581213db61e424492762fb04a9c5f103cdcc38e807e8f0a0442838",
    "71688ed79fca43281832199c954d2c7d6c629d9a7f2ecdd434fd4e9e90770a7c",
)
WAVE_ONE_JOURNAL_SHA256 = (
    "a2ee50ae166dd338e6b09f512193747b28312a52d32d5be44ffa6452d6cfc3c4"
)
WAVE_ONE_PROOF_SET_SHA256 = (
    "1c677676c78a99b85c8f23ea2e0467574fbaa1d3a7dabd19d9cdee75de16205c"
)
WAVE_ONE_TERMINAL_IDENTITY_ROWS_SHA256 = (
    "7cf94c7ad0cff6835cb3a1ae7f516ec11170151ef8ac94f7aee562b44a5fdee5"
)
SYNTHETIC_SOURCE_DESIGN_SHA256 = hashlib.sha256(
    b"synthetic-authoritative-source-repair-design"
).hexdigest()
LIVE_WAVE_THREE_SOURCE_DESIGN_SHA256 = (
    audit.WAVE_THREE_APPROVED_SOURCE_DESIGN_SHA256
)


def canonical_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
    ).encode("utf-8")


def write_java(root: Path, relative: str, line_count: int, body_tail: str = "") -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    filler_count = max(0, line_count - 3)
    text = "package fixture;\n" + ("// filler\n" * filler_count) + body_tail + "\n"
    path.write_text(text, encoding="utf-8", newline="\n")
    return path


def write_bytes(root: Path, relative: str, payload: bytes) -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return path


def write_json(root: Path, relative: str, value: object) -> Path:
    return write_bytes(root, relative, canonical_bytes(value))


def utc_iso(value: datetime = FRESH_AT) -> str:
    return value.isoformat().replace("+00:00", "Z")


def sha256_hex(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def registry_payload(
    descriptors: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "schemaVersion": REGISTRY_SCHEMA,
        "waves": copy.deepcopy(descriptors or [WAVE_ONE_DESCRIPTOR]),
    }


def registry_descriptor(
    ordinal: int,
    *,
    path_suffix: str = "",
) -> dict[str, object]:
    descriptor = copy.deepcopy(WAVE_ONE_DESCRIPTOR)
    descriptor["waveId"] = f"wave-{ordinal:04d}"
    descriptor["ordinal"] = ordinal
    if ordinal != 1 or path_suffix:
        descriptor["journalPath"] = (
            "verification/structural-repair-waves/"
            f"wave-{ordinal:04d}{path_suffix}/journal.jsonl"
        )
        descriptor["proofRoot"] = (
            ".superpowers/sdd/structural-repair-waves/"
            f"wave-{ordinal:04d}{path_suffix}"
        )
    return descriptor


def make_registry_root(base: Path) -> Path:
    root = base / "repo"
    root.mkdir(parents=True)
    return root


def ensure_registry_wave_paths(
    root: Path,
    descriptors: list[dict[str, object]],
) -> None:
    for descriptor in descriptors:
        journal = root / str(descriptor["journalPath"])
        proof_root = root / str(descriptor["proofRoot"])
        journal.parent.mkdir(parents=True, exist_ok=True)
        journal.write_bytes(b"")
        proof_root.mkdir(parents=True, exist_ok=True)


def seed_registered_empty_histories(root: Path) -> None:
    if root.resolve() == ROOT.resolve():
        raise AssertionError("fixture-root-required")
    registry_path = root / REGISTRY_RELATIVE
    registry_value = json.loads(registry_path.read_text(encoding="utf-8"))
    for descriptor in registry_value["waves"]:
        journal = root / str(descriptor["journalPath"])
        journal.parent.mkdir(parents=True, exist_ok=True)
        journal.write_bytes(b"")
        fixture_proof_root = root / str(descriptor["proofRoot"])
        source_proof_root = ROOT / str(descriptor["proofRoot"])
        for relative in (
            audit.INTAKE_SUMMARY_RELATIVE,
            audit.INTAKE_ROWS_RELATIVE,
            audit.TARGET_PREIMAGES_RELATIVE,
            audit.PROGRESS_RELATIVE,
        ):
            write_bytes(
                fixture_proof_root,
                relative.as_posix(),
                (source_proof_root / relative).read_bytes(),
            )
        summary_bytes = (source_proof_root / audit.INTAKE_SUMMARY_RELATIVE).read_bytes()
        summary = json.loads(summary_bytes.decode("utf-8"))
        if canonical_bytes(summary) != summary_bytes:
            raise AssertionError("fixture-intake-summary-not-canonical")
        if summary.get("schemaVersion") == audit.CLOSURE_INTAKE_V2_SCHEMA:
            for relative in (
                audit.ADMISSION_DECISION_RELATIVE,
                audit.DUPLICATE_EVIDENCE_RELATIVE,
            ):
                write_bytes(
                    fixture_proof_root,
                    relative.as_posix(),
                    (source_proof_root / relative).read_bytes(),
                )
        elif summary.get("schemaVersion") != audit.CLOSURE_INTAKE_SCHEMA:
            raise AssertionError("fixture-intake-schema-unknown")


def write_registry(
    root: Path,
    value: dict[str, object],
    *,
    raw: bytes | None = None,
) -> tuple[Path, bytes]:
    payload = canonical_bytes(value) if raw is None else raw
    path = write_bytes(root, REGISTRY_RELATIVE.as_posix(), payload)
    return path, payload


@dataclass(frozen=True)
class FixturePaths:
    root: Path
    git_head: Path
    git_branch: Path
    git_paths: Path
    git_status: Path
    harmony: Path
    test_tree: Path
    duplicate_fqcn: Path
    closure_registry: Path
    closure_journal: Path
    closure_proof_root: Path
    metrics: Path
    baseline: Path
    ledger: Path


def java_body(class_name: str, marker: str = "") -> str:
    marker_line = f'    String marker = "{marker}";\n' if marker else ""
    return f"public class {class_name} {{\n{marker_line}}}"


def refresh_git_paths(fx: FixturePaths, paths: list[str] | None = None) -> list[str]:
    if paths is None:
        paths = [
            path.relative_to(fx.root).as_posix()
            for path in fx.root.rglob("*")
            if path.is_file()
            and not path.relative_to(fx.root).as_posix().startswith(".superpowers/")
        ]
    ordered = sorted(paths, key=lambda value: (value.casefold(), value))
    fx.git_paths.write_bytes(
        ("\0".join(ordered) + ("\0" if ordered else "")).encode("utf-8")
    )
    return ordered


def write_harmony_input(
    fx: FixturePaths,
    *,
    runtime_rows: list[dict[str, object]] | None = None,
    broad_rows: list[dict[str, object]] | None = None,
    generated_at: datetime = FRESH_AT,
    secret_hits: int = 0,
) -> None:
    runtime_rows = list(runtime_rows or [])
    broad_rows = list(broad_rows or [])
    broad_total = sum(int(row["broadCatchBlocks"]) for row in broad_rows)
    broad_without = sum(
        int(row["broadCatchWithoutLocalBreadcrumbApprox"]) for row in broad_rows
    )
    payload = {
        "generatedAt": utc_iso(generated_at),
        "aspectFiles": 0,
        "aspectOrderCoverageApprox": 1.0,
        "criticalUnorderedAspectCount": 0,
        "unorderedAspectCount": 0,
        "crossSubsystemLargeFilesOver1000": len(runtime_rows),
        "runtimeCrossSubsystemLargeFilesOver1000": len(runtime_rows),
        "broadCatchWithoutLocalBreadcrumbRatio": (
            round(broad_without / broad_total, 4) if broad_total else 0.0
        ),
        "secretPatternHits": secret_hits,
        "ledgerEvidence": {
            "runtimeCrossSubsystemLargeFiles": runtime_rows,
            "broadCatchWithoutLocalBreadcrumbFiles": broad_rows,
            "manualPromptCandidateFiles": [],
        },
    }
    fx.harmony.write_bytes(canonical_bytes(payload))


def write_test_tree_input(
    fx: FixturePaths,
    *,
    generated_at: datetime = FRESH_AT,
    risk_score: float = 0.0,
    missing_import_count: int = 0,
    affected_test_file_count: int = 0,
) -> None:
    fx.test_tree.write_bytes(
        canonical_bytes(
            {
                "generatedAt": utc_iso(generated_at),
                "riskScore": risk_score,
                "missingImportCount": missing_import_count,
                "affectedTestFileCount": affected_test_file_count,
            }
        )
    )


def duplicate_row(index: int, state: str = "GENERATED_EXCLUDE") -> dict[str, object]:
    class_name = f"Dup{index:04d}"
    row: dict[str, object] = {
        "fqcn": f"fixture.{class_name}",
        "rootPath": f"main/java/fixture/{class_name}.java",
        "appPath": f"app/src/main/java_clean/fixture/{class_name}.java",
        "packagingState": state,
    }
    material = "\0".join(str(row[key]) for key in ("fqcn", "rootPath", "appPath", "packagingState"))
    row["evidenceFingerprint"] = sha256_hex(material.encode("utf-8"))
    return row


def duplicate_semantic_hash(payload: dict[str, object]) -> str:
    lines = [
        f"schemaVersion={payload['schemaVersion']}",
        f"status={payload['status']}",
        f"mode={payload['mode']}",
        f"filter={payload['filter']}",
        f"action={payload['action']}",
        f"duplicateFqcnSourceCollisionCount={payload['duplicateFqcnSourceCollisionCount']}",
        f"duplicateFqcnGeneratedExcludeCount={payload['duplicateFqcnGeneratedExcludeCount']}",
        f"duplicateFqcnHardExcludeCount={payload['duplicateFqcnHardExcludeCount']}",
        f"duplicateFqcnPackagedActiveCount={payload['duplicateFqcnPackagedActiveCount']}",
        f"duplicateFqcnActiveCount={payload['duplicateFqcnActiveCount']}",
    ]
    semantic = "\n".join(lines) + "\n"
    for row in payload["collisions"]:
        semantic += "collision=" + "\0".join(
            str(row[key])
            for key in (
                "fqcn",
                "rootPath",
                "appPath",
                "packagingState",
                "evidenceFingerprint",
            )
        ) + "\n"
    return sha256_hex(semantic.encode("utf-8"))


def write_duplicate_input(
    fx: FixturePaths,
    *,
    rows: list[dict[str, object]] | None = None,
    generated_at: datetime = FRESH_AT,
    tamper_hash: bool = False,
    count_override: dict[str, int] | None = None,
) -> None:
    collisions = sorted(copy.deepcopy(rows or []), key=lambda row: str(row["fqcn"]))
    generated_count = sum(row["packagingState"] == "GENERATED_EXCLUDE" for row in collisions)
    hard_count = sum(row["packagingState"] == "HARD_EXCLUDE" for row in collisions)
    packaged_count = sum(row["packagingState"] == "PACKAGED_ACTIVE" for row in collisions)
    payload: dict[str, object] = {
        "schemaVersion": DUP_SCHEMA,
        "status": "current",
        "generatedAt": utc_iso(generated_at),
        "mode": "(default)",
        "filter": "stereotype",
        "action": "none",
        "duplicateFqcnSourceCollisionCount": len(collisions),
        "duplicateFqcnGeneratedExcludeCount": generated_count,
        "duplicateFqcnHardExcludeCount": hard_count,
        "duplicateFqcnPackagedActiveCount": packaged_count,
        "duplicateFqcnActiveCount": packaged_count,
        "collisions": collisions,
    }
    payload.update(count_override or {})
    payload["semanticHash"] = duplicate_semantic_hash(payload)
    if tamper_hash:
        payload["semanticHash"] = "0" * 64
    fx.duplicate_fqcn.write_bytes(canonical_bytes(payload))


def make_fixture(base: Path, *, include_app_root: bool = True) -> FixturePaths:
    root = base / "repo"
    inputs = base / "inputs"
    root.mkdir(parents=True)
    inputs.mkdir(parents=True)
    write_java(root, "main/java/fixture/RootMain.java", 3, java_body("RootMain"))
    if include_app_root:
        write_java(
            root,
            "app/src/main/java_clean/fixture/AppMain.java",
            3,
            java_body("AppMain"),
        )

    fx = FixturePaths(
        root=root,
        git_head=inputs / "git-head.txt",
        git_branch=inputs / "git-branch.txt",
        git_paths=inputs / "git-paths.z",
        git_status=inputs / "git-status.z",
        harmony=inputs / "harmony.json",
        test_tree=inputs / "test-tree.json",
        duplicate_fqcn=inputs / "dup-fqcn.json",
        closure_registry=root / REGISTRY_RELATIVE,
        closure_journal=root / "verification/structural-repair-closure-journal.jsonl",
        closure_proof_root=(
            root
            / ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave"
        ),
        metrics=root / "verification/dynamic-rag-quant-audit-metrics.json",
        baseline=root / "verification/structural-design-baseline.json",
        ledger=root / "verification/structural-design-debt-ledger.jsonl",
    )
    fx.git_head.write_text("0123456789abcdef0123456789abcdef01234567\n", encoding="utf-8")
    fx.git_branch.write_text("codex/fixture\n", encoding="utf-8")
    fx.git_status.write_bytes(b"")
    fx.closure_registry.parent.mkdir(parents=True, exist_ok=True)
    fx.closure_registry.write_bytes(
        canonical_bytes(
            registry_payload([WAVE_ONE_DESCRIPTOR, WAVE_TWO_DESCRIPTOR])
        )
    )
    seed_registered_empty_histories(root)
    refresh_git_paths(fx)
    write_harmony_input(fx)
    write_test_tree_input(fx)
    write_duplicate_input(fx)
    return fx


def make_inputs(fx: FixturePaths, *, candidate_cap: int = 1100) -> object:
    return audit.AuditInputs(
        root=fx.root,
        active_java_roots=("main/java", "app/src/main/java_clean"),
        git_head_input=fx.git_head,
        git_branch_input=fx.git_branch,
        git_paths_input=fx.git_paths,
        git_status_input=fx.git_status,
        harmony_input=fx.harmony,
        test_tree_input=fx.test_tree,
        dup_fqcn_input=fx.duplicate_fqcn,
        closure_wave_registry=fx.closure_registry,
        candidate_cap=candidate_cap,
    )


def make_outputs(fx: FixturePaths) -> object:
    return audit.AuditOutputs(
        metrics_output=fx.metrics,
        baseline_output=fx.baseline,
        ledger_output=fx.ledger,
    )


def bundle_bytes(bundle: object) -> bytes:
    return b"".join(
        (
            canonical_bytes(bundle.baseline),
            b"".join(canonical_bytes(row) for row in bundle.ledger_rows),
            canonical_bytes(bundle.metrics),
        )
    )


def without_generated_at(bundle: object) -> tuple[object, object, object]:
    baseline = copy.deepcopy(bundle.baseline)
    metrics = copy.deepcopy(bundle.metrics)
    rows = copy.deepcopy(list(bundle.ledger_rows))
    baseline.pop("generatedAt", None)
    metrics.pop("generatedAt", None)
    for row in rows:
        row.pop("generatedAt", None)
    return baseline, metrics, rows


def relink_after_metrics_mutation(bundle: object, mutate) -> object:
    baseline = copy.deepcopy(bundle.baseline)
    metrics = copy.deepcopy(bundle.metrics)
    rows = copy.deepcopy(list(bundle.ledger_rows))
    baseline_payload = copy.deepcopy(baseline)
    metrics_payload = copy.deepcopy(metrics)
    for field in ("generatedAt", "auditRunId", "artifactLinks"):
        baseline_payload.pop(field)
        metrics_payload.pop(field)
    metrics_payload.pop("semanticArtifactHash")
    ledger_core = []
    for row in rows:
        core = copy.deepcopy(row)
        for field in ("generatedAt", "auditRunId", "artifactLinks"):
            core.pop(field)
        ledger_core.append(core)
    mutate(metrics_payload)
    links = {
        "baselinePayloadSha256": sha256_hex(canonical_bytes(baseline_payload)),
        "ledgerPayloadSha256": sha256_hex(
            b"".join(canonical_bytes(row) for row in ledger_core)
        ),
        "metricsPayloadSha256": sha256_hex(canonical_bytes(metrics_payload)),
    }
    run_id = sha256_hex(
        (
            f"{audit.AUDIT_SCHEMA}|{baseline_payload['baselineId']}|"
            f"{links['baselinePayloadSha256']}|{links['ledgerPayloadSha256']}|"
            f"{links['metricsPayloadSha256']}|"
            f"{metrics_payload['closureHistorySummary']['waveRegistrySha256']}|"
            f"{metrics_payload['closureHistorySummary']['journalSetSha256']}|"
            f"{metrics_payload['closureHistorySummary']['proofSetSha256']}"
        ).encode("utf-8")
    )
    for envelope in (baseline, *rows, metrics):
        envelope["artifactLinks"] = copy.deepcopy(links)
        envelope["auditRunId"] = run_id
    metrics.update(metrics_payload)
    metrics["semanticArtifactHash"] = links["metricsPayloadSha256"]
    return audit.AuditBundle(
        baseline=baseline,
        metrics=metrics,
        ledger_rows=tuple(rows),
        root=bundle.root,
        closure_registry=bundle.closure_registry,
    )


def ledger_core(row: dict[str, object]) -> dict[str, object]:
    return {
        key: copy.deepcopy(value)
        for key, value in row.items()
        if key not in {"generatedAt", "auditRunId", "artifactLinks"}
    }


def identity_hash(payload: dict[str, object], identity_field: str) -> str:
    material = copy.deepcopy(payload)
    material.pop(identity_field, None)
    return sha256_hex(canonical_bytes(material))


def closure_wave_descriptor(
    fx: FixturePaths,
    *,
    wave_id: str = "wave-0001",
    ordinal: int = 1,
) -> object:
    summary_path = fx.closure_proof_root / audit.INTAKE_SUMMARY_RELATIVE
    rows_path = fx.closure_proof_root / audit.INTAKE_ROWS_RELATIVE
    targets_path = fx.closure_proof_root / audit.TARGET_PREIMAGES_RELATIVE
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    return audit.ClosureWaveDescriptor(
        wave_id=wave_id,
        ordinal=ordinal,
        journal_path=fx.closure_journal.resolve(),
        proof_root=fx.closure_proof_root.resolve(),
        source_baseline_id=summary["sourceBaselineId"],
        source_ledger_payload_sha256=summary["sourceLedgerPayloadSha256"],
        source_metrics_semantic_hash=summary["sourceMetricsSemanticHash"],
        intake_summary_sha256=sha256_hex(summary_path.read_bytes()),
        eligible_groups_sha256=sha256_hex(rows_path.read_bytes()),
        target_preimages_sha256=sha256_hex(targets_path.read_bytes()),
    )


def capture_closure_wave_descriptor(fx: FixturePaths) -> object:
    return closure_wave_descriptor(fx)


def descriptor_registry_row(
    root: Path,
    descriptor: object,
) -> dict[str, object]:
    return {
        "waveId": descriptor.wave_id,
        "ordinal": descriptor.ordinal,
        "journalPath": descriptor.journal_path.relative_to(root).as_posix(),
        "proofRoot": descriptor.proof_root.relative_to(root).as_posix(),
        "sourceBaselineId": descriptor.source_baseline_id,
        "sourceLedgerPayloadSha256": descriptor.source_ledger_payload_sha256,
        "sourceMetricsSemanticHash": descriptor.source_metrics_semantic_hash,
        "intakeSummarySha256": descriptor.intake_summary_sha256,
        "eligibleGroupsSha256": descriptor.eligible_groups_sha256,
        "targetPreimagesSha256": descriptor.target_preimages_sha256,
    }


@contextmanager
def patch_fixture_closure_registry(
    fx: FixturePaths,
    captured: object | None = None,
):
    descriptor = captured or capture_closure_wave_descriptor(fx)
    prior_bytes = fx.closure_registry.read_bytes()
    registry_bytes = canonical_bytes(
        registry_payload([descriptor_registry_row(fx.root, descriptor)])
    )
    fx.closure_registry.write_bytes(registry_bytes)
    try:
        with mock.patch.object(
            audit,
            "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
            sha256_hex(registry_bytes),
        ):
            yield descriptor
    finally:
        fx.closure_registry.write_bytes(prior_bytes)


def write_closure_intake(
    fx: FixturePaths,
    predecessor_bundle: object,
    predecessor_row: dict[str, object],
    target_path: str,
    target_preimage: bytes,
) -> None:
    intake_root = fx.closure_proof_root / "intake"
    intake_root.mkdir(parents=True, exist_ok=True)
    source_head = predecessor_bundle.baseline["head"]
    source_branch = predecessor_bundle.baseline["branch"]
    summary = {
        "schemaVersion": "awx.structural-repair-intake.v1",
        "sourceBaselineId": predecessor_row["baselineId"],
        "sourceLedgerPayloadSha256": predecessor_bundle.metrics["artifactLinks"][
            "ledgerPayloadSha256"
        ],
        "sourceMetricsSemanticHash": predecessor_bundle.metrics["semanticArtifactHash"],
        "sourceIssueIds": [predecessor_row["issueId"]],
        "rootCauseGroupIds": [predecessor_row["rootCauseGroupId"]],
        "evidenceFingerprints": [predecessor_row["evidenceFingerprint"]],
        "sourceBranch": source_branch,
        "sourceHead": source_head,
    }
    (intake_root / "intake-summary.json").write_bytes(canonical_bytes(summary))
    (intake_root / "eligible-groups.jsonl").write_bytes(canonical_bytes(predecessor_row))
    target_preimages = {
        "schemaVersion": "awx.structural-repair-target-preimages.v1",
        "sourceHead": source_head,
        "canonicalBranch": source_branch,
        "isolatedBranch": source_branch,
        "targets": [
            {
                "path": target_path,
                "existence": "FILE",
                "gitState": "clean",
                "sizeBytes": len(target_preimage),
                "sha256": sha256_hex(target_preimage),
            }
        ],
    }
    (intake_root / "target-preimages.json").write_bytes(canonical_bytes(target_preimages))
    (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(b"")


def write_reviewed_duplicate_intake_v2(
    fx: FixturePaths,
    predecessor_bundle: object,
    predecessor_row: dict[str, object],
    *,
    duplicate_report_sha256: str,
    target_rows: list[dict[str, object]],
) -> dict[str, object]:
    evidence = {
        "schemaVersion": "awx.structural-repair-duplicate-evidence.v1",
        "sourceIssueId": predecessor_row["issueId"],
        "rootCauseGroupId": predecessor_row["rootCauseGroupId"],
        "fqcn": "com.example.lms.strategy.RetrievalOrderService",
        "canonicalOwnerPath": "main/java/com/example/lms/strategy/RetrievalOrderService.java",
        "compatibilityCopyPath": "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java",
        "sourceCollisionCount": 1,
        "generatedExcludeCount": 1,
        "hardExcludeCount": 0,
        "packagedActiveCount": 0,
        "generatedExcludePattern": "com/example/lms/strategy/RetrievalOrderService*",
        "proofCommand": "gradlew.bat :app:generateDupFqcnExcludes",
        "rawReportSha256": duplicate_report_sha256,
        "secretPatternHitCount": 0,
    }
    evidence_bytes = canonical_bytes(evidence)
    decision = {
        "schemaVersion": "awx.structural-repair-admission-decision.v1",
        "decisionType": "REVIEW_REQUIRED_DUPLICATE_OWNER",
        "sourceBaselineId": predecessor_row["baselineId"],
        "sourceIssueId": predecessor_row["issueId"],
        "rootCauseGroupId": predecessor_row["rootCauseGroupId"],
        "sourceEvidenceFingerprint": predecessor_row["evidenceFingerprint"],
        "sourceCategory": predecessor_row["category"],
        "sourceFixEligibility": predecessor_row["fixEligibility"],
        "sourceStatus": predecessor_row["status"],
        "sourceNumericEvidence": predecessor_row["numericEvidence"],
        "canonicalOwnerPath": "main/java/com/example/lms/strategy/RetrievalOrderService.java",
        "proposedRepairTargetPath": "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java",
        "repairTargetAdmissionRequired": True,
        "activeCallPath": "main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java",
        "behaviorTestPath": "src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java",
        "ownerContractTestPath": "src/test/java/com/abandonware/ai/agent/AgentApplicationScanContractTest.java",
        "duplicateEvidenceSha256": sha256_hex(evidence_bytes),
        "approvedDesignSha256": "e7aad30226cde2c1a6a2360c65ccacf718f6f4ef1ea3f13f0ccff7f9017ebb55",
        "ragControlExcluded": True,
    }
    decision["decisionId"] = identity_hash(decision, "decisionId")
    decision_bytes = canonical_bytes(decision)
    summary = {
        "schemaVersion": "awx.structural-repair-intake.v2",
        "sourceBaselineId": predecessor_row["baselineId"],
        "sourceLedgerPayloadSha256": predecessor_bundle.metrics["artifactLinks"]["ledgerPayloadSha256"],
        "sourceMetricsSemanticHash": predecessor_bundle.metrics["semanticArtifactHash"],
        "sourceIssueIds": [predecessor_row["issueId"]],
        "rootCauseGroupIds": [predecessor_row["rootCauseGroupId"]],
        "evidenceFingerprints": [predecessor_row["evidenceFingerprint"]],
        "sourceBranch": predecessor_bundle.baseline["branch"],
        "sourceHead": predecessor_bundle.baseline["head"],
        "admissionMode": "EXPLICIT_REVIEW_PROMOTION",
        "admissionDecisionSha256": sha256_hex(decision_bytes),
        "duplicateEvidenceSha256": sha256_hex(evidence_bytes),
    }
    intake = fx.closure_proof_root / "intake"
    write_bytes(intake, "duplicate-evidence.json", evidence_bytes)
    write_bytes(intake, "admission-decision.json", decision_bytes)
    write_bytes(intake, "eligible-groups.jsonl", canonical_bytes(predecessor_row))
    write_bytes(intake, "target-preimages.json", canonical_bytes({
        "schemaVersion": "awx.structural-repair-target-preimages.v1",
        "sourceHead": predecessor_bundle.baseline["head"],
        "canonicalBranch": predecessor_bundle.baseline["branch"],
        "isolatedBranch": predecessor_bundle.baseline["branch"],
        "targets": target_rows,
    }))
    write_bytes(intake, "intake-summary.json", canonical_bytes(summary))
    (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(b"")
    fx.closure_journal.write_bytes(b"")
    return decision


def prepare_reviewed_duplicate_intake_v2(
    fx: FixturePaths,
    *,
    now: datetime = FIXED_NOW,
) -> tuple[object, object, dict[str, object], dict[str, object]]:
    paths = (
        "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java",
        "main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java",
        "main/java/com/example/lms/strategy/RetrievalOrderService.java",
        "src/test/java/com/abandonware/ai/agent/AgentApplicationScanContractTest.java",
        "src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java",
    )
    for path in paths:
        write_java(fx.root, path, 3, java_body(Path(path).stem))
    duplicate = {
        "fqcn": "com.example.lms.strategy.RetrievalOrderService",
        "rootPath": "main/java/com/example/lms/strategy/RetrievalOrderService.java",
        "appPath": "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java",
        "packagingState": "GENERATED_EXCLUDE",
    }
    duplicate["evidenceFingerprint"] = sha256_hex(
        "\0".join(str(duplicate[key]) for key in ("fqcn", "rootPath", "appPath", "packagingState")).encode("utf-8")
    )
    refresh_git_paths(fx)
    write_duplicate_input(fx, rows=[duplicate], generated_at=now)
    predecessor_bundle = audit.build_audit(make_inputs(fx), now)
    predecessor_row = ledger_core(next(
        row for row in predecessor_bundle.ledger_rows
        if row["category"] == "DUPLICATE_FQCN_SOURCE_COLLISION"
    ))
    target_rows = [
        {
            "path": path,
            "existence": "FILE",
            "gitState": "clean",
            "sizeBytes": len((fx.root / path).read_bytes()),
            "sha256": sha256_hex((fx.root / path).read_bytes()),
        }
        for path in paths
    ]
    decision = write_reviewed_duplicate_intake_v2(
        fx,
        predecessor_bundle,
        predecessor_row,
        duplicate_report_sha256=sha256_hex(fx.duplicate_fqcn.read_bytes()),
        target_rows=target_rows,
    )
    return predecessor_bundle, closure_wave_descriptor(fx, wave_id="wave-0003", ordinal=3), predecessor_row, decision


def install_empty_reviewed_duplicate_wave_three(
    fx: FixturePaths,
    *,
    now: datetime = FIXED_NOW,
) -> tuple[str, object]:
    wave_three = replace(
        fx,
        closure_journal=(
            fx.root / "verification/structural-repair-waves/wave-0003/journal.jsonl"
        ),
        closure_proof_root=(
            fx.root / ".superpowers/sdd/structural-repair-waves/wave-0003"
        ),
    )
    wave_three.closure_journal.parent.mkdir(parents=True, exist_ok=True)
    intermediate_registry_bytes = fx.closure_registry.read_bytes()
    with mock.patch.object(
        audit,
        "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
        sha256_hex(intermediate_registry_bytes),
    ):
        _, descriptor, _, _ = prepare_reviewed_duplicate_intake_v2(
            wave_three,
            now=now,
        )
    registry = json.loads(fx.closure_registry.read_text(encoding="utf-8"))
    registry["waves"].append(descriptor_registry_row(fx.root, descriptor))
    registry_bytes = canonical_bytes(registry)
    fx.closure_registry.write_bytes(registry_bytes)
    return sha256_hex(registry_bytes), descriptor


def write_selfask_reviewed_admission_intake_v3(
    fx: FixturePaths,
    predecessor_bundle: object,
    predecessor_row: dict[str, object],
    *,
    duplicate_report_sha256: str,
    raw_collision_evidence_fingerprint: str,
    target_rows: list[dict[str, object]],
    caller_file_count: int,
) -> dict[str, object]:
    evidence = {
        "schemaVersion": "awx.structural-repair-duplicate-evidence.v2",
        "sourceIssueId": predecessor_row["issueId"],
        "rootCauseGroupId": predecessor_row["rootCauseGroupId"],
        "fqcn": "service.rag.planner.SelfAskPlanner",
        "canonicalOwnerPath": "main/java/service/rag/planner/SelfAskPlanner.java",
        "compatibilityCopyPath": "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
        "packagingState": "GENERATED_EXCLUDE",
        "sourceCollisionCount": 1,
        "generatedExcludeCount": 1,
        "hardExcludeCount": 0,
        "packagedActiveCount": 0,
        "appActiveSourceRoot": "app/src/main/java_clean",
        "compatibilityCopyDirectCallerFileCount": caller_file_count,
        "callerProbeToken": "java-active-app-direct-caller-files-v1",
        "generatedExcludePattern": "service/rag/planner/SelfAskPlanner*",
        "proofCommand": "gradlew.bat :app:generateDupFqcnExcludes",
        "rawReportSha256": duplicate_report_sha256,
        "rawCollisionEvidenceFingerprint": raw_collision_evidence_fingerprint,
        "secretPatternHitCount": 0,
    }
    evidence_bytes = canonical_bytes(evidence)
    decision = {
        "schemaVersion": "awx.structural-repair-admission-decision.v2",
        "decisionType": "EXACT_SELFASK_REVIEWED_DUPLICATE_OWNER",
        "sourceBaselineId": predecessor_row["baselineId"],
        "sourceIssueId": predecessor_row["issueId"],
        "rootCauseGroupId": predecessor_row["rootCauseGroupId"],
        "sourceEvidenceFingerprint": predecessor_row["evidenceFingerprint"],
        "sourceCategory": predecessor_row["category"],
        "sourceFixEligibility": predecessor_row["fixEligibility"],
        "sourceStatus": predecessor_row["status"],
        "sourceNumericEvidence": predecessor_row["numericEvidence"],
        "candidateFqcn": "service.rag.planner.SelfAskPlanner",
        "canonicalOwnerPath": "main/java/service/rag/planner/SelfAskPlanner.java",
        "proposedRepairTargetPath": "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
        "repairTargetAdmissionRequired": True,
        "packagingConfigPath": "app/build.gradle.kts",
        "activeCallPath": "main/java/config/RagLightAdapters.java",
        "futureOwnershipTestPath": "src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java",
        "duplicateEvidenceSha256": sha256_hex(evidence_bytes),
        "approvedDesignSha256": "1f41dfbfd570bec6c1babb94aa1273746a1ee596cf0460de0accfe64b5ef0ded",
        "ragControlExcluded": True,
    }
    decision["decisionId"] = identity_hash(decision, "decisionId")
    decision_bytes = canonical_bytes(decision)
    summary = {
        "schemaVersion": "awx.structural-repair-intake.v3",
        "sourceBaselineId": predecessor_row["baselineId"],
        "sourceLedgerPayloadSha256": predecessor_bundle.metrics["artifactLinks"]["ledgerPayloadSha256"],
        "sourceMetricsSemanticHash": predecessor_bundle.metrics["semanticArtifactHash"],
        "sourceIssueIds": [predecessor_row["issueId"]],
        "rootCauseGroupIds": [predecessor_row["rootCauseGroupId"]],
        "evidenceFingerprints": [predecessor_row["evidenceFingerprint"]],
        "sourceBranch": predecessor_bundle.baseline["branch"],
        "sourceHead": predecessor_bundle.baseline["head"],
        "admissionMode": "EXACT_SELFASK_REVIEW_PROMOTION",
        "admissionDecisionSha256": sha256_hex(decision_bytes),
        "duplicateEvidenceSha256": sha256_hex(evidence_bytes),
    }
    intake = fx.closure_proof_root / "intake"
    write_bytes(intake, "duplicate-evidence.json", evidence_bytes)
    write_bytes(intake, "admission-decision.json", decision_bytes)
    write_bytes(intake, "eligible-groups.jsonl", canonical_bytes(predecessor_row))
    write_bytes(intake, "target-preimages.json", canonical_bytes({
        "schemaVersion": "awx.structural-repair-target-preimages.v1",
        "sourceHead": predecessor_bundle.baseline["head"],
        "canonicalBranch": predecessor_bundle.baseline["branch"],
        "isolatedBranch": predecessor_bundle.baseline["branch"],
        "targets": target_rows,
    }))
    write_bytes(intake, "intake-summary.json", canonical_bytes(summary))
    (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(b"")
    fx.closure_journal.write_bytes(b"")
    return decision


def prepare_selfask_reviewed_admission_intake_v3(
    fx: FixturePaths,
    *,
    now: datetime = FIXED_NOW,
) -> tuple[object, object, dict[str, object], dict[str, object]]:
    paths = (
        "app/build.gradle.kts",
        "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
        "main/java/config/RagLightAdapters.java",
        "main/java/service/rag/planner/SelfAskPlanner.java",
    )
    write_bytes(fx.root, paths[0], b"plugins {}\n")
    for path in paths[1:]:
        write_java(fx.root, path, 3, java_body(Path(path).stem))
    duplicate = {
        "fqcn": "service.rag.planner.SelfAskPlanner",
        "rootPath": "main/java/service/rag/planner/SelfAskPlanner.java",
        "appPath": "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
        "packagingState": "GENERATED_EXCLUDE",
    }
    duplicate["evidenceFingerprint"] = sha256_hex(
        "\0".join(str(duplicate[key]) for key in ("fqcn", "rootPath", "appPath", "packagingState")).encode("utf-8")
    )
    refresh_git_paths(fx)
    write_duplicate_input(fx, rows=[duplicate], generated_at=now)
    predecessor_bundle = audit.build_audit(make_inputs(fx), now)
    predecessor_row = ledger_core(next(
        row for row in predecessor_bundle.ledger_rows
        if row["category"] == "DUPLICATE_FQCN_SOURCE_COLLISION"
        and row["symbol"] == "service.rag.planner.SelfAskPlanner"
    ))
    target_rows = [
        {
            "path": path,
            "existence": "FILE",
            "gitState": "clean",
            "sizeBytes": len((fx.root / path).read_bytes()),
            "sha256": sha256_hex((fx.root / path).read_bytes()),
        }
        for path in paths
    ] + [{
        "path": "src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java",
        "existence": "ABSENT",
        "gitState": "absent",
        "sizeBytes": 0,
        "sha256": "0" * 64,
    }]
    decision = write_selfask_reviewed_admission_intake_v3(
        fx,
        predecessor_bundle,
        predecessor_row,
        duplicate_report_sha256=sha256_hex(fx.duplicate_fqcn.read_bytes()),
        raw_collision_evidence_fingerprint=sha256_hex(b"selfask-stage-a-collision"),
        target_rows=target_rows,
        caller_file_count=0,
    )
    return predecessor_bundle, closure_wave_descriptor(fx, wave_id="wave-0004", ordinal=4), predecessor_row, decision


def install_empty_selfask_reviewed_admission_wave_four(
    fx: FixturePaths,
    *,
    now: datetime = FIXED_NOW,
) -> tuple[str, object]:
    wave_four = replace(
        fx,
        closure_journal=(fx.root / "verification/structural-repair-waves/wave-0004/journal.jsonl"),
        closure_proof_root=(fx.root / ".superpowers/sdd/structural-repair-waves/wave-0004"),
    )
    wave_four.closure_journal.parent.mkdir(parents=True, exist_ok=True)
    intermediate_registry_bytes = fx.closure_registry.read_bytes()
    with mock.patch.object(
        audit,
        "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
        sha256_hex(intermediate_registry_bytes),
    ):
        _, descriptor, _, _ = prepare_selfask_reviewed_admission_intake_v3(wave_four, now=now)
    registry = json.loads(fx.closure_registry.read_text(encoding="utf-8"))
    registry["waves"].append(descriptor_registry_row(fx.root, descriptor))
    registry_bytes = canonical_bytes(registry)
    fx.closure_registry.write_bytes(registry_bytes)
    return sha256_hex(registry_bytes), descriptor


def rewrite_selfask_v3_links(
    fx: FixturePaths,
    *,
    evidence_mutator=None,
    decision_mutator=None,
    summary_mutator=None,
) -> object:
    intake = fx.closure_proof_root / "intake"
    evidence_path = intake / "duplicate-evidence.json"
    decision_path = intake / "admission-decision.json"
    summary_path = intake / "intake-summary.json"
    evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
    if evidence_mutator is not None:
        evidence_mutator(evidence)
    evidence_path.write_bytes(canonical_bytes(evidence))
    decision = json.loads(decision_path.read_text(encoding="utf-8"))
    decision["duplicateEvidenceSha256"] = sha256_hex(evidence_path.read_bytes())
    if decision_mutator is not None:
        decision_mutator(decision)
    decision["decisionId"] = identity_hash(decision, "decisionId")
    decision_path.write_bytes(canonical_bytes(decision))
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    summary["duplicateEvidenceSha256"] = sha256_hex(evidence_path.read_bytes())
    summary["admissionDecisionSha256"] = sha256_hex(decision_path.read_bytes())
    if summary_mutator is not None:
        summary_mutator(summary)
    summary_path.write_bytes(canonical_bytes(summary))
    return replace(
        closure_wave_descriptor(fx, wave_id="wave-0004", ordinal=4),
        intake_summary_sha256=sha256_hex(summary_path.read_bytes()),
    )


def write_progress_row(fx: FixturePaths, progress: dict[str, object]) -> tuple[dict[str, object], str]:
    progress = copy.deepcopy(progress)
    progress["patchStateId"] = identity_hash(progress, "patchStateId")
    progress_bytes = canonical_bytes(progress)
    (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(progress_bytes)
    return progress, sha256_hex(progress_bytes)


def write_event_baseline_and_bind(
    fx: FixturePaths,
    event: dict[str, object],
    baseline: dict[str, object],
) -> None:
    baseline_path = fx.closure_proof_root / str(event["eventBaselineProofId"])
    baseline_bytes = canonical_bytes(baseline)
    baseline_path.write_bytes(baseline_bytes)
    event["eventBaselineProofSha256"] = sha256_hex(baseline_bytes)
    write_closure_event(fx, event)


def prepare_v2_terminal_history(fx: FixturePaths) -> dict[str, object]:
    predecessor_bundle, descriptor, predecessor, decision = prepare_reviewed_duplicate_intake_v2(fx)
    del predecessor_bundle
    intake = audit._load_closure_intake(fx.closure_proof_root, descriptor)
    group_id = str(predecessor["rootCauseGroupId"])
    issue_id = str(predecessor["issueId"])
    proof_prefix = f"proofs/{group_id}"
    repair_path = "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java"
    repair_target = fx.root / repair_path
    repair_target.write_bytes(repair_target.read_bytes() + b"// independently admitted repair\n")

    proof_common = {
        "schemaVersion": "awx.structural-repair-proof-summary.v1",
        "rootCauseGroupId": group_id,
        "sourceIssueId": issue_id,
        "commandToken": "python -B -X utf8 -m unittest structural_repair",
        "assertionCount": 1,
        "secretPatternHitCount": 0,
    }
    red_id, red_hash = write_closure_proof(fx, f"{proof_prefix}/red-summary.json", dict(
        proof_common,
        proofKind="RED",
        exitCode=1,
        result="EXPECTED_FAIL",
        outputSha256=sha256_hex(b"v2-red-output"),
    ))
    green_id, green_hash = write_closure_proof(fx, f"{proof_prefix}/green-summary.json", dict(
        proof_common,
        proofKind="GREEN",
        exitCode=0,
        result="PASS",
        outputSha256=sha256_hex(b"v2-green-output"),
    ))

    admission_proof_common = {
        "schemaVersion": "awx.structural-repair-target-admission-proof.v1",
        "rootCauseGroupId": group_id,
        "sourceIssueId": issue_id,
        "assertionCount": 1,
        "secretPatternHitCount": 0,
    }
    detector_id, detector_hash = write_closure_proof(
        fx,
        f"{proof_prefix}/detector-red-summary.json",
        dict(
            admission_proof_common,
            proofRole="DETECTOR_RED",
            commandToken="python -B -X utf8 -m unittest duplicate_detector_red",
            exitCode=1,
            result="EXPECTED_FAIL",
            outputSha256=sha256_hex(b"detector-red-output"),
        ),
    )
    compile_id, compile_hash = write_closure_proof(
        fx,
        f"{proof_prefix}/compile-baseline.json",
        dict(
            admission_proof_common,
            proofRole="COMPILE_BASELINE",
            commandToken="python -B -X utf8 -m py_compile structural_repair.py",
            exitCode=0,
            result="PASS",
            outputSha256=sha256_hex(b"compile-baseline-output"),
        ),
    )
    owner_id, owner_hash = write_closure_proof(
        fx,
        f"{proof_prefix}/owner-contract.json",
        dict(
            admission_proof_common,
            proofRole="OWNER_CONTRACT",
            commandToken="python -B -X utf8 -m unittest owner_contract",
            exitCode=0,
            result="PASS",
            outputSha256=sha256_hex(b"owner-contract-output"),
        ),
    )
    admission = {
        "schemaVersion": "awx.structural-repair-target-admission.v1",
        "targetAdmissionId": "",
        "rootCauseGroupId": group_id,
        "sourceIssueId": issue_id,
        "admissionDecisionId": decision["decisionId"],
        "admissionDecisionSha256": intake.admission_decision_sha256,
        "repairTargetPath": repair_path,
        "transformationMode": "IN_PLACE_EXISTING_FILE",
        "approvedSourceDesignSha256": audit.WAVE_THREE_APPROVED_SOURCE_DESIGN_SHA256,
        "detectorRedProofId": detector_id,
        "detectorRedProofSha256": detector_hash,
        "compileBaselineProofSha256": compile_hash,
        "ownerContractProofSha256": owner_hash,
        "secretPatternHitCount": 0,
    }
    admission["targetAdmissionId"] = identity_hash(admission, "targetAdmissionId")
    admission_id, admission_hash = write_closure_proof(
        fx, f"{proof_prefix}/repair-target-admission.json", admission
    )
    repair_preimage_hash = intake.targets[repair_path.casefold()]["sha256"]
    repair_postimage_hash = sha256_hex(repair_target.read_bytes())
    scoped_diff_hash = sha256_hex(
        f"awx.scoped-diff.v1|{repair_path}|{repair_preimage_hash}|{repair_postimage_hash}".encode("utf-8")
    )
    event_baseline_id = sha256_hex(b"v2-event-baseline")
    progress, progress_hash = write_progress_row(fx, {
        "schemaVersion": "awx.structural-repair-progress.v2",
        "patchStateId": "",
        "state": "PATCHED_UNVERIFIED",
        "rootCauseGroupId": group_id,
        "sourceIssueId": issue_id,
        "sourceBaselineId": predecessor["baselineId"],
        "eventBaselineId": event_baseline_id,
        "targetPreimageSha256": repair_preimage_hash,
        "targetPostimageSha256": repair_postimage_hash,
        "scopedDiffSha256": scoped_diff_hash,
        "redProofId": red_id,
        "redProofSha256": red_hash,
        "greenProofId": green_id,
        "greenProofSha256": green_hash,
        "admissionDecisionId": decision["decisionId"],
        "admissionDecisionSha256": intake.admission_decision_sha256,
        "targetAdmissionId": admission["targetAdmissionId"],
        "targetAdmissionSha256": admission_hash,
        "repairTargetPath": repair_path,
    })
    baseline_id = f"{proof_prefix}/event-baseline.json"
    baseline = {
        "schemaVersion": "awx.structural-repair-event-baseline.v1",
        "baselineId": event_baseline_id,
        "branch": intake.summary["sourceBranch"],
        "head": intake.summary["sourceHead"],
        "declaredTargets": [
            {
                "path": target["path"],
                "gitState": target["gitState"],
                "sha256": repair_postimage_hash if target["path"] == repair_path else target["sha256"],
                "sizeBytes": len(repair_target.read_bytes()) if target["path"] == repair_path else target["sizeBytes"],
            }
            for target in json.loads(
                (fx.closure_proof_root / "intake/target-preimages.json").read_text(encoding="utf-8")
            )["targets"]
        ],
    }
    _, baseline_hash = write_closure_proof(fx, baseline_id, baseline)
    event = {
        "schemaVersion": "awx.structural-repair-closure-event.v2",
        "eventId": "",
        "eventType": "VERIFIED_CLOSED",
        "rootCauseGroupId": group_id,
        "sourceIssueId": issue_id,
        "sourceBaselineId": predecessor["baselineId"],
        "sourceEvidenceFingerprint": predecessor["evidenceFingerprint"],
        "sourcePath": predecessor["path"],
        "sourceSymbol": predecessor["symbol"],
        "sourceCategory": predecessor["category"],
        "sourceNumericEvidence": predecessor["numericEvidence"],
        "eventBaselineId": event_baseline_id,
        "targetPreimageSha256": repair_preimage_hash,
        "targetPostimageSha256": repair_postimage_hash,
        "scopedDiffSha256": scoped_diff_hash,
        "redProofId": red_id,
        "redProofSha256": red_hash,
        "greenProofId": green_id,
        "greenProofSha256": green_hash,
        "eventBaselineProofId": baseline_id,
        "eventBaselineProofSha256": baseline_hash,
        "patchStateProofId": progress["patchStateId"],
        "patchStateProofSha256": progress_hash,
        "fingerprintDisposition": "DISAPPEARED",
        "sourceSetGate": "PASS",
        "dependencyGate": "PASS",
        "compileGate": "PASS",
        "duplicateOwnerGate": "PASS",
        "secretNewHitCount": 0,
        "desktopProof": "PASS",
        "supersedesEventId": None,
        "admissionDecisionId": decision["decisionId"],
        "admissionDecisionSha256": intake.admission_decision_sha256,
        "targetAdmissionId": admission["targetAdmissionId"],
        "targetAdmissionSha256": admission_hash,
        "repairTargetPath": repair_path,
    }
    write_closure_event(fx, event)
    return {
        "descriptor": descriptor,
        "intake": intake,
        "predecessor": predecessor,
        "decision": decision,
        "repairPath": repair_path,
        "admission": admission,
        "admissionPath": admission_id,
        "admissionHash": admission_hash,
        "admissionProofPaths": (admission_id, detector_id, compile_id, owner_id),
        "progress": progress,
        "progressHash": progress_hash,
        "event": event,
        "baseline": baseline,
    }


def rewrite_v2_source_design(
    fx: FixturePaths,
    context: dict[str, object],
    source_design_sha256: str,
) -> None:
    admission_path = fx.closure_proof_root / context["admissionPath"]
    admission = json.loads(admission_path.read_text(encoding="utf-8"))
    admission["approvedSourceDesignSha256"] = source_design_sha256
    admission["targetAdmissionId"] = identity_hash(admission, "targetAdmissionId")
    admission_bytes = canonical_bytes(admission)
    admission_path.write_bytes(admission_bytes)
    progress = json.loads(
        (fx.closure_proof_root / "repair-progress.jsonl").read_text(encoding="utf-8")
    )
    progress["targetAdmissionId"] = admission["targetAdmissionId"]
    progress["targetAdmissionSha256"] = sha256_hex(admission_bytes)
    write_progress_row(fx, progress)


def write_closure_proof(
    fx: FixturePaths,
    relative: str,
    payload: dict[str, object],
) -> tuple[str, str]:
    proof_path = fx.closure_proof_root / relative
    proof_path.parent.mkdir(parents=True, exist_ok=True)
    proof_bytes = canonical_bytes(payload)
    proof_path.write_bytes(proof_bytes)
    return relative, sha256_hex(proof_bytes)


def prepare_terminal_history(
    fx: FixturePaths,
    event_type: str,
) -> tuple[dict[str, object], str]:
    target_path = "main/java/fixture/ClosureCandidate.java"
    target = write_java(
        fx.root,
        target_path,
        4,
        java_body("ClosureCandidate"),
    )
    target_preimage = target.read_bytes()
    refresh_git_paths(fx)
    write_harmony_input(
        fx,
        broad_rows=[
            {
                "file": target_path,
                "lines": 4,
                "broadCatchBlocks": 1,
                "broadCatchWithoutLocalBreadcrumbApprox": 1,
            }
        ],
    )
    predecessor_bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
    predecessor_row = ledger_core(
        next(
            row
            for row in predecessor_bundle.ledger_rows
            if row["category"] == "BROAD_CATCH_NO_BREADCRUMB"
        )
    )
    write_closure_intake(
        fx,
        predecessor_bundle,
        predecessor_row,
        target_path,
        target_preimage,
    )

    if event_type == "VERIFIED_CLOSED":
        target.write_bytes(target_preimage + b"// bounded breadcrumb\n")
    target_postimage = target.read_bytes()
    write_harmony_input(fx, broad_rows=[])
    with patch_fixture_closure_registry(fx, closure_wave_descriptor(fx)):
        post_bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

    group_id = str(predecessor_row["rootCauseGroupId"])
    source_issue_id = str(predecessor_row["issueId"])
    proof_prefix = f"proofs/{group_id}"
    proof_common = {
        "schemaVersion": "awx.structural-repair-proof-summary.v1",
        "rootCauseGroupId": group_id,
        "sourceIssueId": source_issue_id,
        "commandToken": "python -X utf8 scripts\\test_harmony_pressure_report.py",
        "assertionCount": 1,
        "secretPatternHitCount": 0,
    }
    red_proof = dict(
        proof_common,
        proofKind="RED",
        exitCode=1,
        result="EXPECTED_FAIL",
        outputSha256=sha256_hex(canonical_bytes({"classification": "predecessor"})),
    )
    green_proof = dict(
        proof_common,
        proofKind="GREEN",
        exitCode=0,
        result="PASS",
        outputSha256=sha256_hex(canonical_bytes({"classification": "current"})),
    )
    red_id, red_hash = write_closure_proof(
        fx, f"{proof_prefix}/red-summary.json", red_proof
    )
    green_id, green_hash = write_closure_proof(
        fx, f"{proof_prefix}/green-summary.json", green_proof
    )
    baseline_proof = {
        "schemaVersion": "awx.structural-repair-event-baseline.v1",
        "baselineId": post_bundle.baseline["baselineId"],
        "branch": post_bundle.baseline["branch"],
        "head": post_bundle.baseline["head"],
        "declaredTargets": [
            {
                "path": target_path,
                "gitState": "clean",
                "sizeBytes": len(target_postimage),
                "sha256": sha256_hex(target_postimage),
            }
        ],
    }
    baseline_id, baseline_hash = write_closure_proof(
        fx, f"{proof_prefix}/event-baseline.json", baseline_proof
    )

    pre_hash = sha256_hex(target_preimage)
    post_hash = sha256_hex(target_postimage)
    scoped_hash = (
        "0" * 64
        if event_type == "REJECTED_FALSE_POSITIVE"
        else sha256_hex(
            f"awx.scoped-diff.v1|{target_path}|{pre_hash}|{post_hash}".encode("utf-8")
        )
    )
    patch_state_id = None
    patch_state_hash = None
    if event_type == "VERIFIED_CLOSED":
        progress = {
            "schemaVersion": "awx.structural-repair-progress.v1",
            "patchStateId": "",
            "state": "PATCHED_UNVERIFIED",
            "rootCauseGroupId": group_id,
            "sourceIssueId": source_issue_id,
            "sourceBaselineId": predecessor_row["baselineId"],
            "eventBaselineId": post_bundle.baseline["baselineId"],
            "targetPreimageSha256": pre_hash,
            "targetPostimageSha256": post_hash,
            "scopedDiffSha256": scoped_hash,
            "redProofId": red_id,
            "redProofSha256": red_hash,
            "greenProofId": green_id,
            "greenProofSha256": green_hash,
        }
        progress["patchStateId"] = identity_hash(progress, "patchStateId")
        progress_bytes = canonical_bytes(progress)
        (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(progress_bytes)
        patch_state_id = progress["patchStateId"]
        patch_state_hash = sha256_hex(progress_bytes)

    event = {
        "schemaVersion": "awx.structural-repair-closure-event.v1",
        "eventId": "",
        "eventType": event_type,
        "rootCauseGroupId": group_id,
        "sourceIssueId": source_issue_id,
        "sourceBaselineId": predecessor_row["baselineId"],
        "sourceEvidenceFingerprint": predecessor_row["evidenceFingerprint"],
        "sourcePath": predecessor_row["path"],
        "sourceSymbol": predecessor_row["symbol"],
        "sourceCategory": predecessor_row["category"],
        "sourceNumericEvidence": predecessor_row["numericEvidence"],
        "eventBaselineId": post_bundle.baseline["baselineId"],
        "targetPreimageSha256": pre_hash,
        "targetPostimageSha256": post_hash,
        "scopedDiffSha256": scoped_hash,
        "redProofId": red_id,
        "redProofSha256": red_hash,
        "greenProofId": green_id,
        "greenProofSha256": green_hash,
        "eventBaselineProofId": baseline_id,
        "eventBaselineProofSha256": baseline_hash,
        "patchStateProofId": patch_state_id,
        "patchStateProofSha256": patch_state_hash,
        "fingerprintDisposition": (
            "ACCEPTED_BOUNDED_TRANSFORMATION"
            if event_type == "REJECTED_FALSE_POSITIVE"
            else "DISAPPEARED"
        ),
        "sourceSetGate": "PASS",
        "dependencyGate": "PASS",
        "compileGate": "PASS",
        "duplicateOwnerGate": "PASS",
        "secretNewHitCount": 0,
        "desktopProof": "PASS",
        "supersedesEventId": None,
    }
    event["eventId"] = identity_hash(event, "eventId")
    fx.closure_journal.write_bytes(canonical_bytes(event))
    return predecessor_row, target_path


def read_closure_event(fx: FixturePaths) -> dict[str, object]:
    return json.loads(fx.closure_journal.read_text(encoding="utf-8"))


def write_closure_event(fx: FixturePaths, event: dict[str, object]) -> None:
    event = copy.deepcopy(event)
    event["eventId"] = identity_hash(event, "eventId")
    fx.closure_journal.write_bytes(canonical_bytes(event))


def rewrite_proof_and_event_hash(
    fx: FixturePaths,
    event: dict[str, object],
    proof_id_field: str,
    proof_hash_field: str,
    mutate,
) -> None:
    proof_path = fx.closure_proof_root / str(event[proof_id_field])
    proof = json.loads(proof_path.read_text(encoding="utf-8"))
    mutate(proof)
    proof_bytes = canonical_bytes(proof)
    proof_path.write_bytes(proof_bytes)
    event[proof_hash_field] = sha256_hex(proof_bytes)
    write_closure_event(fx, event)


def aggregate_fixture_hex(token: str) -> str:
    return sha256_hex(f"aggregate-fixture:{token}".encode("utf-8"))


def aggregate_wave_descriptor(
    root: Path,
    ordinal: int,
    *,
    journal_path: Path | None = None,
    proof_root: Path | None = None,
    create_paths: bool = True,
) -> object:
    wave_id = f"wave-{ordinal:04d}"
    journal = journal_path or (
        root / "verification/structural-repair-waves" / wave_id / "journal.jsonl"
    )
    proofs = proof_root or (
        root / ".superpowers/sdd/structural-repair-waves" / wave_id
    )
    if create_paths:
        journal.parent.mkdir(parents=True, exist_ok=True)
        journal.write_bytes(b"")
        proofs.mkdir(parents=True, exist_ok=True)
    return audit.ClosureWaveDescriptor(
        wave_id=wave_id,
        ordinal=ordinal,
        journal_path=journal.absolute(),
        proof_root=proofs.absolute(),
        source_baseline_id=aggregate_fixture_hex(f"{wave_id}:baseline"),
        source_ledger_payload_sha256=aggregate_fixture_hex(f"{wave_id}:ledger"),
        source_metrics_semantic_hash=aggregate_fixture_hex(f"{wave_id}:metrics"),
        intake_summary_sha256=aggregate_fixture_hex(f"{wave_id}:summary"),
        eligible_groups_sha256=aggregate_fixture_hex(f"{wave_id}:eligible"),
        target_preimages_sha256=aggregate_fixture_hex(f"{wave_id}:targets"),
    )


def aggregate_registry(root: Path, descriptors: list[object]) -> object:
    registry_path = root / REGISTRY_RELATIVE
    registry_path.parent.mkdir(parents=True, exist_ok=True)
    if not registry_path.exists():
        registry_path.write_bytes(b"aggregate-registry-fixture\n")
    registry_hash = sha256_hex(
        canonical_bytes(
            [
                [descriptor.wave_id, descriptor.ordinal]
                for descriptor in descriptors
            ]
        )
    )
    return audit.ClosureRegistry(
        path=registry_path.resolve(),
        payload_sha256=registry_hash,
        waves=tuple(descriptors),
    )


def aggregate_predecessor(issue_id: str, group_id: str) -> dict[str, object]:
    return {
        "issueId": issue_id,
        "rootCauseGroupId": group_id,
    }


def aggregate_event(
    *,
    issue_id: str,
    group_id: str,
    event_type: str,
    label: str,
    supersedes: str | None = None,
    event_id: str | None = None,
) -> dict[str, object]:
    proof_prefix = f"proofs/{group_id}"
    patch_state_id = (
        aggregate_fixture_hex(f"{label}:progress")
        if event_type == "VERIFIED_CLOSED"
        else None
    )
    return {
        "eventId": event_id
        or aggregate_fixture_hex(
            f"{label}:{issue_id}:{group_id}:{event_type}:{supersedes or '-'}"
        ),
        "eventType": event_type,
        "rootCauseGroupId": group_id,
        "sourceIssueId": issue_id,
        "redProofId": f"{proof_prefix}/red-summary.json",
        "redProofSha256": aggregate_fixture_hex(f"{label}:red"),
        "greenProofId": f"{proof_prefix}/green-summary.json",
        "greenProofSha256": aggregate_fixture_hex(f"{label}:green"),
        "eventBaselineProofId": f"{proof_prefix}/event-baseline.json",
        "eventBaselineProofSha256": aggregate_fixture_hex(f"{label}:baseline-proof"),
        "patchStateProofId": patch_state_id,
        "patchStateProofSha256": (
            aggregate_fixture_hex(f"{label}:progress-bytes")
            if patch_state_id is not None
            else None
        ),
        "supersedesEventId": supersedes,
    }


def aggregate_wave_history(
    descriptor: object,
    predecessors: list[dict[str, object]],
    events: list[dict[str, object]],
) -> object:
    referenced_event_ids = {
        event["supersedesEventId"]
        for event in events
        if event["supersedesEventId"] is not None
    }
    active_events = tuple(
        event for event in events if event["eventId"] not in referenced_event_ids
    )
    proof_pairs: dict[str, str] = {}
    for event in events:
        for id_field, hash_field in (
            ("redProofId", "redProofSha256"),
            ("greenProofId", "greenProofSha256"),
            ("eventBaselineProofId", "eventBaselineProofSha256"),
            ("patchStateProofId", "patchStateProofSha256"),
        ):
            proof_id = event[id_field]
            if proof_id is not None:
                proof_pairs[str(proof_id)] = str(event[hash_field])
    return audit.ClosureWaveHistory(
        descriptor=descriptor,
        predecessor_rows=tuple(copy.deepcopy(predecessors)),
        all_events=tuple(copy.deepcopy(events)),
        active_events=copy.deepcopy(active_events),
        journal_payload_sha256=aggregate_fixture_hex(
            f"{descriptor.wave_id}:journal-payload"
        ),
        proof_pairs=tuple(sorted(proof_pairs.items())),
    )


class DynamicRagQuantAuditTest(unittest.TestCase):
    def setUp(self) -> None:
        source_design = mock.patch.object(
            audit,
            "WAVE_THREE_APPROVED_SOURCE_DESIGN_SHA256",
            SYNTHETIC_SOURCE_DESIGN_SHA256,
            create=True,
        )
        source_design.start()
        self.addCleanup(source_design.stop)
        fixture_registry_bytes = canonical_bytes(
            registry_payload([WAVE_ONE_DESCRIPTOR, WAVE_TWO_DESCRIPTOR])
        )
        fixture_registry_pin = mock.patch.object(
            audit,
            "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
            sha256_hex(fixture_registry_bytes),
        )
        fixture_registry_pin.start()
        self.addCleanup(fixture_registry_pin.stop)

    def assert_reason(self, expected: str, callable_obj, *args, **kwargs) -> None:
        with self.assertRaises(audit.AuditContractError) as raised:
            callable_obj(*args, **kwargs)
        self.assertEqual(expected, raised.exception.reason_code)

    def test_wave_three_v2_intake_accepts_one_exact_reviewed_duplicate_predecessor(self):
        """Catches missing descriptor-local v2 intake dispatch and admission validation."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            _, descriptor, predecessor, decision = prepare_reviewed_duplicate_intake_v2(fx)
            intake = audit._load_closure_intake(fx.closure_proof_root, descriptor)
            history = audit.load_closure_wave(root=fx.root, descriptor=descriptor)

        self.assertIsInstance(intake, audit.ClosureWaveIntake)
        self.assertEqual((predecessor,), intake.predecessor_rows)
        self.assertEqual(decision, intake.admission_decision)
        self.assertEqual("REVIEW_REQUIRED", intake.predecessor_rows[0]["fixEligibility"])
        self.assertEqual("HOLD", intake.predecessor_rows[0]["status"])
        self.assertEqual((), history.all_events)
        self.assertEqual((), history.active_events)
        self.assertEqual((), history.proof_pairs)

    def test_wave_three_v2_intake_rejects_wrong_descriptor_row_decision_and_evidence(self):
        """Catches accepting a mismatched v2 descriptor, predecessor, decision, or evidence."""
        cases = {
            "descriptor": ("closure-intake-mismatch", lambda fx, descriptor: replace(descriptor, source_baseline_id="0" * 64)),
            "row": ("closure-intake-mismatch", None),
            "decision": ("closure-admission-invalid", None),
            "evidence": ("closure-admission-invalid", None),
        }
        for case, (reason, descriptor_mutator) in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                _, descriptor, _, _ = prepare_reviewed_duplicate_intake_v2(fx)
                if case == "row":
                    row_path = fx.closure_proof_root / audit.INTAKE_ROWS_RELATIVE
                    row = json.loads(row_path.read_text(encoding="utf-8"))
                    row["status"] = "OPEN"
                    row_path.write_bytes(canonical_bytes(row))
                    descriptor = replace(descriptor, eligible_groups_sha256=sha256_hex(row_path.read_bytes()))
                elif case == "decision":
                    decision_path = fx.closure_proof_root / "intake/admission-decision.json"
                    decision = json.loads(decision_path.read_text(encoding="utf-8"))
                    decision["decisionType"] = "WRONG"
                    decision["decisionId"] = identity_hash(decision, "decisionId")
                    decision_path.write_bytes(canonical_bytes(decision))
                    summary_path = fx.closure_proof_root / audit.INTAKE_SUMMARY_RELATIVE
                    summary = json.loads(summary_path.read_text(encoding="utf-8"))
                    summary["admissionDecisionSha256"] = sha256_hex(decision_path.read_bytes())
                    summary_path.write_bytes(canonical_bytes(summary))
                    descriptor = replace(descriptor, intake_summary_sha256=sha256_hex(summary_path.read_bytes()))
                elif case == "evidence":
                    evidence_path = fx.closure_proof_root / "intake/duplicate-evidence.json"
                    evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
                    evidence["proofCommand"] = "wrong"
                    evidence_path.write_bytes(canonical_bytes(evidence))
                    decision_path = fx.closure_proof_root / "intake/admission-decision.json"
                    decision = json.loads(decision_path.read_text(encoding="utf-8"))
                    decision["duplicateEvidenceSha256"] = sha256_hex(evidence_path.read_bytes())
                    decision["decisionId"] = identity_hash(decision, "decisionId")
                    decision_path.write_bytes(canonical_bytes(decision))
                    summary_path = fx.closure_proof_root / audit.INTAKE_SUMMARY_RELATIVE
                    summary = json.loads(summary_path.read_text(encoding="utf-8"))
                    summary["duplicateEvidenceSha256"] = sha256_hex(evidence_path.read_bytes())
                    summary["admissionDecisionSha256"] = sha256_hex(decision_path.read_bytes())
                    summary_path.write_bytes(canonical_bytes(summary))
                    descriptor = replace(descriptor, intake_summary_sha256=sha256_hex(summary_path.read_bytes()))
                elif descriptor_mutator is not None:
                    descriptor = descriptor_mutator(fx, descriptor)
                self.assert_reason(reason, audit._load_closure_intake, fx.closure_proof_root, descriptor)

    def test_v1_intake_rejects_v2_only_fields_and_preserves_v1_contract(self):
        """Catches silently widening the frozen v1 intake-summary field contract."""
        for field, value in {
            "admissionMode": "EXPLICIT_REVIEW_PROMOTION",
            "admissionDecisionSha256": "0" * 64,
            "duplicateEvidenceSha256": "0" * 64,
        }.items():
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
                descriptor = closure_wave_descriptor(fx)
                summary_path = fx.closure_proof_root / audit.INTAKE_SUMMARY_RELATIVE
                summary = json.loads(summary_path.read_text(encoding="utf-8"))
                summary[field] = value
                summary_path.write_bytes(canonical_bytes(summary))
                descriptor = replace(descriptor, intake_summary_sha256=sha256_hex(summary_path.read_bytes()))
                self.assert_reason("closure-intake-mismatch", audit._load_closure_intake, fx.closure_proof_root, descriptor)

        live_registry_bytes = (
            ROOT / audit.CLOSURE_WAVE_REGISTRY_RELATIVE
        ).read_bytes()
        with mock.patch.object(
            audit,
            "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
            sha256_hex(live_registry_bytes),
        ):
            registry = audit.load_closure_registry(
                root=ROOT,
                registry_path=REGISTRY_RELATIVE,
            )
            v1_intake = audit._load_closure_intake(
                registry.waves[0].proof_root,
                registry.waves[0],
            )
            history = audit.load_closure_wave(
                root=ROOT,
                descriptor=registry.waves[0],
            )
        self.assertEqual(audit.CLOSURE_INTAKE_SCHEMA, v1_intake.schema_version)
        self.assertIsNone(v1_intake.admission_decision)
        self.assertIsNone(v1_intake.admission_decision_sha256)
        self.assertIsNone(v1_intake.duplicate_evidence)
        self.assertIsNone(v1_intake.duplicate_evidence_sha256)
        self.assertEqual(WAVE_ONE_PREDECESSOR_ISSUE_IDS, tuple(row["issueId"] for row in history.predecessor_rows))
        self.assertEqual(WAVE_ONE_EVENT_IDS, tuple(event["eventId"] for event in history.all_events))
        self.assertEqual(WAVE_ONE_PROOF_SET_SHA256, sha256_hex(canonical_bytes([list(pair) for pair in history.proof_pairs])))

    def test_wave_three_v2_intake_rejects_ragcontrol_identity_or_target(self):
        """Catches a RagControl predecessor identity or target entering reviewed duplicate admission."""
        rag_path = "main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java"
        cases = (
            "issue",
            "group",
            "symbol",
            "source-path",
            "target-0",
            "target-1",
            "target-2",
            "target-3",
            "target-4",
        )
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                _, descriptor, _, _ = prepare_reviewed_duplicate_intake_v2(fx)
                if case.startswith("target-"):
                    target_path = fx.closure_proof_root / audit.TARGET_PREIMAGES_RELATIVE
                    targets = json.loads(target_path.read_text(encoding="utf-8"))
                    targets["targets"][int(case.rsplit("-", 1)[1])]["path"] = rag_path
                    target_path.write_bytes(canonical_bytes(targets))
                    descriptor = replace(descriptor, target_preimages_sha256=sha256_hex(target_path.read_bytes()))
                else:
                    row_path = fx.closure_proof_root / audit.INTAKE_ROWS_RELATIVE
                    row = json.loads(row_path.read_text(encoding="utf-8"))
                    field = {"issue": "issueId", "group": "rootCauseGroupId", "symbol": "symbol", "source-path": "path"}[case]
                    row[field] = "0" * 64 if field.endswith("Id") else rag_path
                    row_path.write_bytes(canonical_bytes(row))
                    descriptor = replace(descriptor, eligible_groups_sha256=sha256_hex(row_path.read_bytes()))
                self.assert_reason(
                    "closure-admission-invalid" if case.startswith("target-") else "closure-intake-mismatch",
                    audit._load_closure_intake,
                    fx.closure_proof_root,
                    descriptor,
                )

        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            _, descriptor, _, _ = prepare_reviewed_duplicate_intake_v2(fx)
            intake = audit._load_closure_intake(fx.closure_proof_root, descriptor)
        self.assertTrue(intake.admission_decision["ragControlExcluded"])

    def test_wave_three_v2_intake_rejects_malformed_absent_target_preimage(self):
        """Catches v2 accepting an ABSENT row with file-sized/hash/git-state semantics."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            _, descriptor, _, _ = prepare_reviewed_duplicate_intake_v2(fx)
            target_path = fx.closure_proof_root / audit.TARGET_PREIMAGES_RELATIVE
            targets = json.loads(target_path.read_text(encoding="utf-8"))
            targets["targets"][0].update({
                "existence": "ABSENT",
                "gitState": "clean",
                "sizeBytes": 1,
                "sha256": "1" * 64,
            })
            target_path.write_bytes(canonical_bytes(targets))
            descriptor = replace(
                descriptor,
                target_preimages_sha256=sha256_hex(target_path.read_bytes()),
            )
            self.assert_reason(
                "closure-admission-invalid",
                audit._load_closure_intake,
                fx.closure_proof_root,
                descriptor,
            )

    def test_wave_three_v2_intake_rejects_raw_hash_mismatch_and_noncanonical_admission_bytes(self):
        """Catches unbound raw admission hashes or noncanonical decision/evidence JSON bytes."""
        cases = ("decision-hash", "evidence-hash", "decision-noncanonical", "evidence-noncanonical")
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                _, descriptor, _, _ = prepare_reviewed_duplicate_intake_v2(fx)
                summary_path = fx.closure_proof_root / audit.INTAKE_SUMMARY_RELATIVE
                summary = json.loads(summary_path.read_text(encoding="utf-8"))
                if case == "decision-hash":
                    summary["admissionDecisionSha256"] = "0" * 64
                elif case == "evidence-hash":
                    summary["duplicateEvidenceSha256"] = "0" * 64
                else:
                    relative = (
                        audit.ADMISSION_DECISION_RELATIVE
                        if case == "decision-noncanonical"
                        else audit.DUPLICATE_EVIDENCE_RELATIVE
                    )
                    payload_path = fx.closure_proof_root / relative
                    payload_path.write_bytes(payload_path.read_bytes() + b"\n")
                    summary[
                        "admissionDecisionSha256"
                        if case == "decision-noncanonical"
                        else "duplicateEvidenceSha256"
                    ] = sha256_hex(payload_path.read_bytes())
                summary_path.write_bytes(canonical_bytes(summary))
                descriptor = replace(
                    descriptor,
                    intake_summary_sha256=sha256_hex(summary_path.read_bytes()),
                )
                self.assert_reason(
                    "closure-admission-invalid",
                    audit._load_closure_intake,
                    fx.closure_proof_root,
                    descriptor,
                )

    def test_wave_four_v3_intake_accepts_exact_selfask_reviewed_predecessor(self):
        """Catches missing exact SelfAsk v3 dispatch and frozen admission validation."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            _, descriptor, predecessor, decision = prepare_selfask_reviewed_admission_intake_v3(fx)
            intake = audit._load_closure_intake(fx.closure_proof_root, descriptor)

        self.assertIsInstance(intake, audit.ClosureWaveIntake)
        self.assertEqual("awx.structural-repair-intake.v3", intake.schema_version)
        self.assertEqual((predecessor,), intake.predecessor_rows)
        self.assertEqual(decision, intake.admission_decision)
        self.assertEqual(0, intake.duplicate_evidence["compatibilityCopyDirectCallerFileCount"])

    def test_wave_four_v3_intake_rejects_wrong_wave_candidate_or_stage_a_identity(self):
        """Catches a non-Wave-4 descriptor or non-frozen SelfAsk decision identity."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            _, descriptor, _, _ = prepare_selfask_reviewed_admission_intake_v3(fx)
            self.assert_reason(
                "closure-intake-mismatch",
                audit._load_closure_intake,
                fx.closure_proof_root,
                replace(descriptor, wave_id="wave-0003", ordinal=3),
            )
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_selfask_reviewed_admission_intake_v3(fx)
            descriptor = rewrite_selfask_v3_links(
                fx,
                decision_mutator=lambda decision: decision.__setitem__("candidateFqcn", "service.rag.planner.NotSelfAsk"),
            )
            self.assert_reason("closure-admission-invalid", audit._load_closure_intake, fx.closure_proof_root, descriptor)

    def test_wave_four_v3_intake_rejects_nonzero_caller_probe_or_dirty_compatibility_copy(self):
        """Catches a nonzero frozen caller count or non-clean compatibility-copy target."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_selfask_reviewed_admission_intake_v3(fx)
            descriptor = rewrite_selfask_v3_links(
                fx,
                evidence_mutator=lambda evidence: evidence.__setitem__("compatibilityCopyDirectCallerFileCount", 1),
            )
            self.assert_reason("closure-admission-invalid", audit._load_closure_intake, fx.closure_proof_root, descriptor)
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            _, descriptor, _, _ = prepare_selfask_reviewed_admission_intake_v3(fx)
            target_path = fx.closure_proof_root / audit.TARGET_PREIMAGES_RELATIVE
            targets = json.loads(target_path.read_text(encoding="utf-8"))
            targets["targets"][1]["gitState"] = "modified"
            target_path.write_bytes(canonical_bytes(targets))
            descriptor = replace(descriptor, target_preimages_sha256=sha256_hex(target_path.read_bytes()))
            self.assert_reason("closure-admission-invalid", audit._load_closure_intake, fx.closure_proof_root, descriptor)

    def test_wave_four_v3_intake_rejects_wrong_five_target_preimages_or_decision_links(self):
        """Catches reordered target preimages and a decision detached from its evidence hash."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            _, descriptor, _, _ = prepare_selfask_reviewed_admission_intake_v3(fx)
            target_path = fx.closure_proof_root / audit.TARGET_PREIMAGES_RELATIVE
            targets = json.loads(target_path.read_text(encoding="utf-8"))
            targets["targets"][0], targets["targets"][1] = targets["targets"][1], targets["targets"][0]
            target_path.write_bytes(canonical_bytes(targets))
            descriptor = replace(descriptor, target_preimages_sha256=sha256_hex(target_path.read_bytes()))
            self.assert_reason("closure-admission-invalid", audit._load_closure_intake, fx.closure_proof_root, descriptor)
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_selfask_reviewed_admission_intake_v3(fx)
            descriptor = rewrite_selfask_v3_links(fx)
            decision_path = fx.closure_proof_root / audit.ADMISSION_DECISION_RELATIVE
            decision = json.loads(decision_path.read_text(encoding="utf-8"))
            decision["duplicateEvidenceSha256"] = "0" * 64
            decision["decisionId"] = identity_hash(decision, "decisionId")
            decision_path.write_bytes(canonical_bytes(decision))
            summary_path = fx.closure_proof_root / audit.INTAKE_SUMMARY_RELATIVE
            summary = json.loads(summary_path.read_text(encoding="utf-8"))
            summary["admissionDecisionSha256"] = sha256_hex(decision_path.read_bytes())
            summary_path.write_bytes(canonical_bytes(summary))
            descriptor = replace(descriptor, intake_summary_sha256=sha256_hex(summary_path.read_bytes()))
            self.assert_reason("closure-admission-invalid", audit._load_closure_intake, fx.closure_proof_root, descriptor)

    def test_wave_four_v3_intake_rejects_noncanonical_unbound_or_swapped_duplicate_evidence(self):
        """Catches noncanonical, unbound, or predecessor-swapped v3 duplicate evidence."""
        for case in ("noncanonical", "unbound", "swapped"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                prepare_selfask_reviewed_admission_intake_v3(fx)
                if case == "unbound":
                    descriptor = rewrite_selfask_v3_links(
                        fx,
                        evidence_mutator=lambda evidence: evidence.__setitem__("rawReportSha256", "0" * 64),
                    )
                elif case == "swapped":
                    row = json.loads((fx.closure_proof_root / audit.INTAKE_ROWS_RELATIVE).read_text(encoding="utf-8"))
                    descriptor = rewrite_selfask_v3_links(
                        fx,
                        evidence_mutator=lambda evidence: evidence.__setitem__("rawCollisionEvidenceFingerprint", row["evidenceFingerprint"]),
                    )
                else:
                    descriptor = rewrite_selfask_v3_links(fx)
                    evidence_path = fx.closure_proof_root / audit.DUPLICATE_EVIDENCE_RELATIVE
                    evidence_path.write_bytes(evidence_path.read_bytes() + b"\n")
                    decision_path = fx.closure_proof_root / audit.ADMISSION_DECISION_RELATIVE
                    decision = json.loads(decision_path.read_text(encoding="utf-8"))
                    decision["duplicateEvidenceSha256"] = sha256_hex(evidence_path.read_bytes())
                    decision["decisionId"] = identity_hash(decision, "decisionId")
                    decision_path.write_bytes(canonical_bytes(decision))
                    summary_path = fx.closure_proof_root / audit.INTAKE_SUMMARY_RELATIVE
                    summary = json.loads(summary_path.read_text(encoding="utf-8"))
                    summary["duplicateEvidenceSha256"] = sha256_hex(evidence_path.read_bytes())
                    summary["admissionDecisionSha256"] = sha256_hex(decision_path.read_bytes())
                    summary_path.write_bytes(canonical_bytes(summary))
                    descriptor = replace(descriptor, intake_summary_sha256=sha256_hex(summary_path.read_bytes()))
                self.assert_reason("closure-admission-invalid", audit._load_closure_intake, fx.closure_proof_root, descriptor)

    def test_wave_four_v3_rejects_v1_v2_and_unknown_schema_substitutions(self):
        """Catches dispatch accepting older or unknown schemas in the Wave-4 descriptor."""
        for schema in ("awx.structural-repair-intake.v1", "awx.structural-repair-intake.v2", "unknown.v3"):
            with self.subTest(schema=schema), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                _, descriptor, _, _ = prepare_selfask_reviewed_admission_intake_v3(fx)
                summary_path = fx.closure_proof_root / audit.INTAKE_SUMMARY_RELATIVE
                summary = json.loads(summary_path.read_text(encoding="utf-8"))
                summary["schemaVersion"] = schema
                summary_path.write_bytes(canonical_bytes(summary))
                descriptor = replace(descriptor, intake_summary_sha256=sha256_hex(summary_path.read_bytes()))
                self.assert_reason("closure-intake-mismatch", audit._load_closure_intake, fx.closure_proof_root, descriptor)

    def test_wave_four_v3_rejects_canonical_v1_intake_even_with_valid_hashes(self):
        """Catches a valid legacy v1 tuple bypassing the Wave-4-only v3 admission gate."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            target_path = "main/java/fixture/LegacyWaveOneCandidate.java"
            target = write_java(fx.root, target_path, 4, java_body("LegacyWaveOneCandidate"))
            target_preimage = target.read_bytes()
            refresh_git_paths(fx)
            write_harmony_input(
                fx,
                broad_rows=[{
                    "file": target_path,
                    "lines": 4,
                    "broadCatchBlocks": 1,
                    "broadCatchWithoutLocalBreadcrumbApprox": 1,
                }],
            )
            predecessor_bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            predecessor_row = ledger_core(next(
                row for row in predecessor_bundle.ledger_rows
                if row["category"] == "BROAD_CATCH_NO_BREADCRUMB"
            ))
            write_closure_intake(
                fx,
                predecessor_bundle,
                predecessor_row,
                target_path,
                target_preimage,
            )
            wave_four_descriptor = closure_wave_descriptor(
                fx,
                wave_id="wave-0004",
                ordinal=4,
            )
            legacy_intake = audit._load_closure_intake(
                fx.closure_proof_root,
                replace(wave_four_descriptor, wave_id="wave-0001", ordinal=1),
            )
            self.assertEqual(audit.CLOSURE_INTAKE_SCHEMA, legacy_intake.schema_version)
            self.assert_reason(
                "closure-intake-mismatch",
                audit._load_closure_intake,
                fx.closure_proof_root,
                wave_four_descriptor,
            )

    def test_wave_three_v2_rejects_wave_four_v3_fields_without_changing_v2_contract(self):
        """Catches widening v2 summary fields to absorb the SelfAsk-only v3 contract."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            _, descriptor, _, _ = prepare_reviewed_duplicate_intake_v2(fx)
            summary_path = fx.closure_proof_root / audit.INTAKE_SUMMARY_RELATIVE
            summary = json.loads(summary_path.read_text(encoding="utf-8"))
            summary["futureOwnershipTestPath"] = "src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java"
            summary_path.write_bytes(canonical_bytes(summary))
            descriptor = replace(descriptor, intake_summary_sha256=sha256_hex(summary_path.read_bytes()))
            self.assert_reason("closure-intake-mismatch", audit._load_closure_intake, fx.closure_proof_root, descriptor)

    def test_wave_four_v3_loader_uses_frozen_caller_evidence_without_rescanning_live_tree(self):
        """Catches persistent intake validation re-running the Task-6 live caller probe."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            _, descriptor, _, _ = prepare_selfask_reviewed_admission_intake_v3(fx)
            write_java(
                fx.root,
                "app/src/main/java_clean/service/rag/planner/LiveCaller.java",
                3,
                "class LiveCaller { SelfAskPlanner planner; }",
            )
            intake = audit._load_closure_intake(fx.closure_proof_root, descriptor)
        self.assertEqual(0, intake.duplicate_evidence["compatibilityCopyDirectCallerFileCount"])

    def test_selfask_caller_probe_excludes_declaration_and_strips_comments_strings(self):
        """Catches the caller probe counting its declaration, comments, or literal text."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            declaration = "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java"
            write_java(fx.root, declaration, 3, java_body("SelfAskPlanner"))
            write_bytes(
                fx.root,
                "app/src/main/java_clean/service/rag/planner/CommentOnly.java",
                b"class CommentOnly { // SelfAskPlanner\n String value = \"SelfAskPlanner\"; char c = 'S'; }\n",
            )
            self.assertEqual(
                0,
                audit._count_selfask_compatibility_copy_direct_callers(
                    root=fx.root,
                    compatibility_copy_path=declaration,
                ),
            )

    def test_selfask_caller_probe_counts_identifier_in_another_active_app_java_file(self):
        """Catches the caller probe missing a real SelfAskPlanner identifier in another file."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            declaration = "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java"
            write_java(fx.root, declaration, 3, java_body("SelfAskPlanner"))
            write_bytes(
                fx.root,
                "app/src/main/java_clean/service/rag/planner/DirectCaller.java",
                b"class DirectCaller { SelfAskPlanner planner; }\n",
            )
            self.assertEqual(
                1,
                audit._count_selfask_compatibility_copy_direct_callers(
                    root=fx.root,
                    compatibility_copy_path=declaration,
                ),
            )

    def test_selfask_caller_probe_rejects_alias_reparse_or_missing_active_root(self):
        """Catches alias, reparse, or missing active-root inputs to the Stage-A-only caller probe."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp), include_app_root=False)
            self.assert_reason(
                "closure-admission-invalid",
                audit._count_selfask_compatibility_copy_direct_callers,
                root=fx.root,
                compatibility_copy_path="app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
            )
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            declaration = "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java"
            write_java(fx.root, declaration, 3, java_body("SelfAskPlanner"))
            self.assert_reason(
                "closure-admission-invalid",
                audit._count_selfask_compatibility_copy_direct_callers,
                root=fx.root,
                compatibility_copy_path="app/src/main/java_clean/service/rag/planner/SelfAskPlannerAlias.java",
            )
            with mock.patch.object(audit, "_has_reparse_component", return_value=True):
                self.assert_reason(
                    "closure-admission-invalid",
                    audit._count_selfask_compatibility_copy_direct_callers,
                    root=fx.root,
                    compatibility_copy_path=declaration,
                )

    def test_empty_wave_three_requires_all_target_admission_files_absent(self):
        """Catches empty v2 progress silently ignoring a stray future admission proof file."""
        suffixes = (
            "repair-target-admission",
            "detector-red-summary",
            "compile-baseline",
            "owner-contract",
        )
        for suffix in suffixes:
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                _, descriptor, predecessor, _ = prepare_reviewed_duplicate_intake_v2(fx)
                empty = audit.load_closure_wave(root=fx.root, descriptor=descriptor)
                self.assertEqual((), empty.all_events)
                write_json(
                    fx.closure_proof_root,
                    f"proofs/{predecessor['rootCauseGroupId']}/{suffix}.json",
                    {},
                )
                self.assert_reason(
                    "closure-admission-invalid",
                    audit.load_closure_wave,
                    root=fx.root,
                    descriptor=descriptor,
                )

    def test_empty_wave_four_requires_zero_progress_zero_journal_and_no_future_admission_artifacts(self):
        """Catches Wave 4 treating nonempty history inputs or a future admission as inert."""
        cases = {
            "progress": (
                "closure-progress-mismatch",
                lambda fx, predecessor: (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(
                    canonical_bytes({"schemaVersion": audit.CLOSURE_PROGRESS_SCHEMA})
                ),
            ),
            "journal": (
                "closure-journal-malformed",
                lambda fx, predecessor: fx.closure_journal.write_bytes(b"{}\n"),
            ),
            "future-admission": (
                "closure-proof-invalid",
                lambda fx, predecessor: write_json(
                    fx.closure_proof_root,
                    f"proofs/{predecessor['rootCauseGroupId']}/repair-target-admission.json",
                    {},
                ),
            ),
        }
        for case, (reason, mutate) in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                _, descriptor, predecessor, _ = prepare_selfask_reviewed_admission_intake_v3(fx)
                mutate(fx, predecessor)
                self.assert_reason(
                    reason,
                    audit.load_closure_wave,
                    root=fx.root,
                    descriptor=descriptor,
                )

    def test_wave_four_rejects_any_progress_schema_or_proof_namespace_entry(self):
        """Catches v3 accepting a progress row or any file below its intentionally empty proofs namespace."""
        cases = {
            "v1-progress": (
                "closure-progress-mismatch",
                lambda fx: (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(
                    canonical_bytes({"schemaVersion": audit.CLOSURE_PROGRESS_SCHEMA})
                ),
            ),
            "v2-progress": (
                "closure-progress-mismatch",
                lambda fx: (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(
                    canonical_bytes({"schemaVersion": audit.CLOSURE_PROGRESS_V2_SCHEMA})
                ),
            ),
            "unknown-progress": (
                "closure-progress-mismatch",
                lambda fx: (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(
                    canonical_bytes({"schemaVersion": "unknown.progress"})
                ),
            ),
            "proof-entry": (
                "closure-proof-invalid",
                lambda fx: write_json(fx.closure_proof_root, "proofs/future-proof.json", {}),
            ),
        }
        for case, (reason, mutate) in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                _, descriptor, _, _ = prepare_selfask_reviewed_admission_intake_v3(fx)
                mutate(fx)
                self.assert_reason(
                    reason,
                    audit.load_closure_wave,
                    root=fx.root,
                    descriptor=descriptor,
                )

    def test_wave_four_rejects_any_v1_v2_or_unknown_journal_event(self):
        """Catches v3 reaching legacy event parsing instead of rejecting every nonzero journal byte first."""
        payloads = {
            "v1": canonical_bytes({"schemaVersion": audit.CLOSURE_EVENT_SCHEMA}),
            "v2": canonical_bytes({"schemaVersion": audit.CLOSURE_EVENT_V2_SCHEMA}),
            "unknown": canonical_bytes({"schemaVersion": "unknown.event"}),
        }
        for case, payload in payloads.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                _, descriptor, _, _ = prepare_selfask_reviewed_admission_intake_v3(fx)
                fx.closure_journal.write_bytes(payload)
                original_parse = audit._parse_canonical_ndjson_bytes

                def no_legacy_event_dispatch(candidate_bytes, reason):
                    if reason == "closure-journal-malformed":
                        return ()
                    return original_parse(candidate_bytes, reason)

                with mock.patch.object(
                    audit,
                    "_parse_canonical_ndjson_bytes",
                    side_effect=no_legacy_event_dispatch,
                ):
                    self.assert_reason(
                        "closure-journal-malformed",
                        audit.load_closure_wave,
                        root=fx.root,
                        descriptor=descriptor,
                    )

    def test_history_accepts_three_prior_waves_plus_empty_selfask_wave_four_without_credit(self):
        """Catches adding empty SelfAsk history changing any preexisting event or proof credit."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            wave_three_sha256, _ = install_empty_reviewed_duplicate_wave_three(fx)
            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                wave_three_sha256,
            ):
                wave_four_sha256, _ = install_empty_selfask_reviewed_admission_wave_four(fx)
            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                wave_four_sha256,
            ):
                registry = audit.load_closure_registry(
                    root=fx.root,
                    registry_path=REGISTRY_RELATIVE,
                )
                prior = audit.load_closure_history(
                    root=fx.root,
                    registry=replace(registry, waves=registry.waves[:3]),
                )
                history = audit.load_closure_history(root=fx.root, registry=registry)

        self.assertEqual(3, prior.wave_count)
        self.assertEqual(4, history.wave_count)
        self.assertEqual(prior.predecessor_rows, history.predecessor_rows[:-1])
        self.assertEqual(prior.all_events, history.all_events)
        self.assertEqual(prior.active_events, history.active_events)
        self.assertEqual(prior.proof_set_sha256, history.proof_set_sha256)
        self.assertEqual(prior.event_count, history.event_count)
        self.assertEqual(
            prior.rejected_false_positive_root_cause_groups,
            history.rejected_false_positive_root_cause_groups,
        )
        self.assertEqual(
            prior.verified_closed_root_cause_groups,
            history.verified_closed_root_cause_groups,
        )

    def test_four_wave_aggregate_rejects_cross_wave_issue_group_path_or_active_tip_conflict(self):
        """Catches four-wave aggregation accepting colliding predecessor ownership or active evidence."""
        for conflict in ("issue", "group", "path", "active-tip"):
            with self.subTest(conflict=conflict), tempfile.TemporaryDirectory() as tmp:
                root = make_registry_root(Path(tmp))
                waves = [aggregate_wave_descriptor(root, ordinal) for ordinal in range(1, 5)]
                issue_ids = [aggregate_fixture_hex(f"four-wave-{ordinal}-issue") for ordinal in range(1, 5)]
                group_ids = [aggregate_fixture_hex(f"four-wave-{ordinal}-group") for ordinal in range(1, 5)]
                if conflict == "issue":
                    issue_ids[3] = issue_ids[0]
                if conflict in {"group", "active-tip"}:
                    group_ids[3] = group_ids[0]
                predecessors = [
                    dict(
                        aggregate_predecessor(issue_id, group_id),
                        path=(
                            "main/java/shared/Planner.java"
                            if conflict == "path" and ordinal in {1, 4}
                            else f"main/java/fixture/Wave{ordinal}.java"
                        ),
                    )
                    for ordinal, (issue_id, group_id) in enumerate(
                        zip(issue_ids, group_ids), start=1
                    )
                ]
                events = [
                    aggregate_event(
                        issue_id=issue_ids[0],
                        group_id=group_ids[0],
                        event_type="REJECTED_FALSE_POSITIVE",
                        label="four-wave-active-tip",
                    )
                ] if conflict == "active-tip" else []
                histories = {
                    wave.wave_id: aggregate_wave_history(
                        wave,
                        [predecessors[index]],
                        events if index == 0 else [],
                    )
                    for index, wave in enumerate(waves)
                }
                with mock.patch.object(
                    audit,
                    "load_closure_wave",
                    side_effect=lambda *, root, descriptor: histories[descriptor.wave_id],
                ):
                    self.assert_reason(
                        "closure-wave-conflict",
                        audit.load_closure_history,
                        root=root,
                        registry=aggregate_registry(root, waves),
                    )

    def test_later_v3_wave_four_failure_preserves_all_prior_public_output_bytes(self):
        """Catches a late v3 proof-namespace failure replacing any already-public artifact."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            wave_three_sha256, _ = install_empty_reviewed_duplicate_wave_three(fx)
            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                wave_three_sha256,
            ):
                wave_four_sha256, _ = install_empty_selfask_reviewed_admission_wave_four(fx)
            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                wave_four_sha256,
            ):
                bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            sentinels = {
                fx.baseline: b"prior-v3-baseline",
                fx.ledger: b"prior-v3-ledger",
                fx.metrics: b"prior-v3-metrics",
            }
            for path, payload in sentinels.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(payload)
            proof_path = (
                fx.root
                / ".superpowers/sdd/structural-repair-waves/wave-0004/proofs/future-proof.json"
            )
            real_write_closed_temp = audit._write_closed_temp
            staged_count = 0

            def tamper_after_staging(destination, payload):
                nonlocal staged_count
                staged = real_write_closed_temp(destination, payload)
                staged_count += 1
                if staged_count == 3:
                    write_json(proof_path.parent, proof_path.name, {})
                return staged

            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                wave_four_sha256,
            ), mock.patch.object(
                audit,
                "_write_closed_temp",
                side_effect=tamper_after_staging,
            ):
                self.assert_reason(
                    "output-replace-failed",
                    audit.publish_bundle,
                    bundle,
                    make_outputs(fx),
                )

            self.assertEqual(3, staged_count)
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_wave_four_post_replacement_proof_drift_rolls_back_all_prior_public_outputs(self):
        """Catches live v3 proof drift after final staging but immediately after the first public replacement."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            wave_three_sha256, _ = install_empty_reviewed_duplicate_wave_three(fx)
            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                wave_three_sha256,
            ):
                wave_four_sha256, _ = install_empty_selfask_reviewed_admission_wave_four(fx)
            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                wave_four_sha256,
            ):
                bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            sentinels = {
                fx.baseline: b"prior-v3-post-replacement-baseline",
                fx.ledger: b"prior-v3-post-replacement-ledger",
                fx.metrics: b"prior-v3-post-replacement-metrics",
            }
            for path, payload in sentinels.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(payload)
            proof_path = (
                fx.root
                / ".superpowers/sdd/structural-repair-waves/wave-0004/proofs/post-replacement-proof.json"
            )
            real_replace = os.replace
            real_validate_bundle = audit.validate_bundle
            validation_count = 0
            mutated = False

            def record_validation(candidate):
                nonlocal validation_count
                validation_count += 1
                return real_validate_bundle(candidate)

            def mutate_after_first_public_replacement(source, destination):
                nonlocal mutated
                destination_path = Path(destination)
                result = real_replace(source, destination)
                if not mutated and destination_path == fx.baseline:
                    self.assertEqual(3, validation_count)
                    write_json(proof_path.parent, proof_path.name, {})
                    mutated = True
                return result

            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                wave_four_sha256,
            ), mock.patch.object(
                audit,
                "validate_bundle",
                side_effect=record_validation,
            ), mock.patch.object(
                audit.os,
                "replace",
                side_effect=mutate_after_first_public_replacement,
            ):
                self.assert_reason(
                    "output-replace-failed",
                    audit.publish_bundle,
                    bundle,
                    make_outputs(fx),
                )

            self.assertTrue(mutated)
            self.assertEqual(4, validation_count)
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_wave_four_public_overlay_keeps_selfask_review_required_hold_without_terminal_credit(self):
        """Catches public reconstruction promoting the admitted SelfAsk predecessor to terminal credit."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            wave_three_sha256, _ = install_empty_reviewed_duplicate_wave_three(fx)
            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                wave_three_sha256,
            ):
                wave_four_sha256, _ = install_empty_selfask_reviewed_admission_wave_four(fx)
            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                wave_four_sha256,
            ):
                bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
                history = audit.load_closure_history(
                    root=fx.root,
                    registry=bundle.closure_registry,
                )

        rows = [
            row for row in bundle.ledger_rows
            if row.get("symbol") == "service.rag.planner.SelfAskPlanner"
        ]
        self.assertEqual(1, len(rows))
        self.assertEqual("REVIEW_REQUIRED", rows[0]["fixEligibility"])
        self.assertEqual("HOLD", rows[0]["status"])
        self.assertFalse(
            any(event["sourceIssueId"] == rows[0]["issueId"] for event in history.active_events)
        )
        self.assertEqual(0, history.verified_closed_root_cause_groups)

    def test_v2_progress_requires_exact_target_admission_and_three_evidence_files(self):
        """Catches v2 progress admitting an unbound target or malformed admission proof set."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            context = prepare_v2_terminal_history(fx)
            history = audit.load_closure_wave(root=fx.root, descriptor=context["descriptor"])
            entries = audit._load_progress_rows(
                proof_root=fx.closure_proof_root,
                intake=context["intake"],
            )
            entry = entries[context["progress"]["patchStateId"]]
            expected_admission_pairs = tuple(sorted(
                (
                    (relative, sha256_hex((fx.closure_proof_root / relative).read_bytes()))
                    for relative in context["admissionProofPaths"]
                ),
                key=lambda pair: (pair[0], pair[1]),
            ))

            self.assertIsInstance(entry, audit.ClosureProgressEntry)
            self.assertEqual(context["progress"], entry.row)
            self.assertEqual(context["progressHash"], entry.raw_sha256)
            self.assertIsInstance(entry.target_admission, audit.ClosureTargetAdmission)
            self.assertEqual(context["admission"], entry.target_admission.row)
            self.assertEqual(context["admissionHash"], entry.target_admission.raw_sha256)
            self.assertEqual(expected_admission_pairs, entry.target_admission.proof_pairs)
            self.assertEqual(8, len(history.proof_pairs))

        for missing_index in range(4):
            with self.subTest(case=f"missing-proof-{missing_index}"), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                context = prepare_v2_terminal_history(fx)
                (fx.closure_proof_root / context["admissionProofPaths"][missing_index]).unlink()
                self.assert_reason(
                    "closure-admission-invalid",
                    audit._load_progress_rows,
                    proof_root=fx.closure_proof_root,
                    intake=context["intake"],
                )

        admission_cases = {
            "wrong-group": ("rootCauseGroupId", "0" * 64),
            "wrong-issue": ("sourceIssueId", "0" * 64),
            "wrong-design": ("approvedSourceDesignSha256", "0" * 64),
            "wrong-decision-id": ("admissionDecisionId", "0" * 64),
            "wrong-decision-hash": ("admissionDecisionSha256", "0" * 64),
            "transformation-mode": ("transformationMode", "COPY_AND_REPLACE"),
            "call-path-target": (
                "repairTargetPath",
                "main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java",
            ),
            "behavior-test-target": (
                "repairTargetPath",
                "src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java",
            ),
            "owner-test-target": (
                "repairTargetPath",
                "src/test/java/com/abandonware/ai/agent/AgentApplicationScanContractTest.java",
            ),
            "absolute-target": ("repairTargetPath", "C:/private/Repair.java"),
            "secret-hit": ("secretPatternHitCount", 1),
        }
        for case, (field, value) in admission_cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                context = prepare_v2_terminal_history(fx)
                admission_path = fx.closure_proof_root / context["admissionPath"]
                admission = json.loads(admission_path.read_text(encoding="utf-8"))
                admission[field] = value
                admission["targetAdmissionId"] = identity_hash(admission, "targetAdmissionId")
                admission_bytes = canonical_bytes(admission)
                admission_path.write_bytes(admission_bytes)
                progress = json.loads(
                    (fx.closure_proof_root / "repair-progress.jsonl").read_text(encoding="utf-8")
                )
                progress["targetAdmissionId"] = admission["targetAdmissionId"]
                progress["targetAdmissionSha256"] = sha256_hex(admission_bytes)
                if field == "repairTargetPath":
                    progress["repairTargetPath"] = value
                    target = context["intake"].targets.get(str(value).casefold())
                    if target is not None:
                        progress["targetPreimageSha256"] = target["sha256"]
                write_progress_row(fx, progress)
                self.assert_reason(
                    "closure-admission-invalid",
                    audit._load_progress_rows,
                    proof_root=fx.closure_proof_root,
                    intake=context["intake"],
                )

        proof_cases = (
            ("detector-red-summary", "exitCode", 0),
            ("compile-baseline", "exitCode", 1),
            ("owner-contract", "unexpected", True),
            ("owner-contract", "secretPatternHitCount", 1),
        )
        for suffix, field, value in proof_cases:
            with self.subTest(case=f"{suffix}-{field}"), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                context = prepare_v2_terminal_history(fx)
                group_id = context["predecessor"]["rootCauseGroupId"]
                proof_path = fx.closure_proof_root / f"proofs/{group_id}/{suffix}.json"
                proof = json.loads(proof_path.read_text(encoding="utf-8"))
                proof[field] = value
                proof_bytes = canonical_bytes(proof)
                proof_path.write_bytes(proof_bytes)
                admission_path = fx.closure_proof_root / context["admissionPath"]
                admission = json.loads(admission_path.read_text(encoding="utf-8"))
                hash_field = {
                    "detector-red-summary": "detectorRedProofSha256",
                    "compile-baseline": "compileBaselineProofSha256",
                    "owner-contract": "ownerContractProofSha256",
                }[suffix]
                admission[hash_field] = sha256_hex(proof_bytes)
                admission["targetAdmissionId"] = identity_hash(admission, "targetAdmissionId")
                admission_bytes = canonical_bytes(admission)
                admission_path.write_bytes(admission_bytes)
                progress = json.loads(
                    (fx.closure_proof_root / "repair-progress.jsonl").read_text(encoding="utf-8")
                )
                progress["targetAdmissionId"] = admission["targetAdmissionId"]
                progress["targetAdmissionSha256"] = sha256_hex(admission_bytes)
                write_progress_row(fx, progress)
                self.assert_reason(
                    "closure-admission-invalid",
                    audit._load_progress_rows,
                    proof_root=fx.closure_proof_root,
                    intake=context["intake"],
                )

        with self.subTest(case="unknown-admission-field"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            context = prepare_v2_terminal_history(fx)
            admission_path = fx.closure_proof_root / context["admissionPath"]
            admission = json.loads(admission_path.read_text(encoding="utf-8"))
            admission["unexpected"] = True
            admission["targetAdmissionId"] = identity_hash(admission, "targetAdmissionId")
            admission_bytes = canonical_bytes(admission)
            admission_path.write_bytes(admission_bytes)
            progress = json.loads(
                (fx.closure_proof_root / "repair-progress.jsonl").read_text(encoding="utf-8")
            )
            progress["targetAdmissionId"] = admission["targetAdmissionId"]
            progress["targetAdmissionSha256"] = sha256_hex(admission_bytes)
            write_progress_row(fx, progress)
            self.assert_reason(
                "closure-admission-invalid",
                audit._load_progress_rows,
                proof_root=fx.closure_proof_root,
                intake=context["intake"],
            )

        with self.subTest(case="noncanonical-admission-bytes"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            context = prepare_v2_terminal_history(fx)
            admission_path = fx.closure_proof_root / context["admissionPath"]
            admission_path.write_bytes(admission_path.read_bytes() + b"\n")
            progress = json.loads(
                (fx.closure_proof_root / "repair-progress.jsonl").read_text(encoding="utf-8")
            )
            progress["targetAdmissionSha256"] = sha256_hex(admission_path.read_bytes())
            write_progress_row(fx, progress)
            self.assert_reason(
                "closure-admission-invalid",
                audit._load_progress_rows,
                proof_root=fx.closure_proof_root,
                intake=context["intake"],
            )

        progress_cases = {
            "admission-decision-id": ("admissionDecisionId", "0" * 64),
            "admission-decision-hash": ("admissionDecisionSha256", "0" * 64),
            "target-admission-id": ("targetAdmissionId", "0" * 64),
            "target-admission-hash": ("targetAdmissionSha256", "0" * 64),
            "repair-target-path": (
                "repairTargetPath",
                "main/java/com/example/lms/strategy/RetrievalOrderService.java",
            ),
            "target-preimage": ("targetPreimageSha256", "0" * 64),
        }
        for case, (field, value) in progress_cases.items():
            with self.subTest(case=f"progress-{case}"), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                context = prepare_v2_terminal_history(fx)
                progress = copy.deepcopy(context["progress"])
                progress[field] = value
                write_progress_row(fx, progress)
                self.assert_reason(
                    (
                        "closure-admission-invalid"
                        if case in {"target-admission-id", "target-admission-hash"}
                        else "closure-progress-mismatch"
                    ),
                    audit._load_progress_rows,
                    proof_root=fx.closure_proof_root,
                    intake=context["intake"],
                )

        for case in ("mixed-schema", "unchanged-postimage"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                context = prepare_v2_terminal_history(fx)
                progress = copy.deepcopy(context["progress"])
                if case == "mixed-schema":
                    progress["schemaVersion"] = "awx.structural-repair-progress.v1"
                else:
                    progress["targetPostimageSha256"] = progress["targetPreimageSha256"]
                write_progress_row(fx, progress)
                self.assert_reason(
                    "closure-progress-mismatch",
                    audit._load_progress_rows,
                    proof_root=fx.closure_proof_root,
                    intake=context["intake"],
                )

    def test_v2_target_admission_requires_distinct_authoritative_source_design_pin(self):
        """Catches admission-only, unset, or unpinned design hashes authorizing source repair."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            context = prepare_v2_terminal_history(fx)
            entries = audit._load_progress_rows(
                proof_root=fx.closure_proof_root,
                intake=context["intake"],
            )
            self.assertEqual(
                SYNTHETIC_SOURCE_DESIGN_SHA256,
                next(iter(entries.values())).target_admission.row["approvedSourceDesignSha256"],
            )

        admission_only_sha256 = audit.WAVE_THREE_APPROVED_DESIGN_SHA256
        cases = (
            ("unset-pin", None, admission_only_sha256),
            ("admission-only-pin", admission_only_sha256, admission_only_sha256),
            ("random-unpinned", SYNTHETIC_SOURCE_DESIGN_SHA256, "f" * 64),
        )
        for case, configured_pin, row_pin in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                context = prepare_v2_terminal_history(fx)
                rewrite_v2_source_design(fx, context, row_pin)
                with mock.patch.object(
                    audit,
                    "WAVE_THREE_APPROVED_SOURCE_DESIGN_SHA256",
                    configured_pin,
                ):
                    self.assert_reason(
                        "closure-admission-invalid",
                        audit._load_progress_rows,
                        proof_root=fx.closure_proof_root,
                        intake=context["intake"],
                    )

    def test_v2_event_binds_historical_source_to_independently_admitted_target(self):
        """Catches v2 postimage validation reading sourcePath instead of repairTargetPath."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            context = prepare_v2_terminal_history(fx)
            history = audit.load_closure_wave(root=fx.root, descriptor=context["descriptor"])
            self.assertEqual(1, len(history.all_events))
            event = history.all_events[0]
            self.assertEqual(context["predecessor"]["path"], event["sourcePath"])
            self.assertEqual(context["repairPath"], event["repairTargetPath"])
            self.assertNotEqual(event["sourcePath"], event["repairTargetPath"])
            self.assertEqual(
                sha256_hex((fx.root / context["repairPath"]).read_bytes()),
                event["targetPostimageSha256"],
            )
            self.assertNotEqual(
                sha256_hex((fx.root / event["sourcePath"]).read_bytes()),
                event["targetPostimageSha256"],
            )

    def test_v1_progress_and_event_reject_v2_fields_and_v2_rejects_v1_shape(self):
        """Catches mixed v1/v2 rows being widened, ignored, or dispatched by field subset."""
        v2_values = {
            "admissionDecisionId": "1" * 64,
            "admissionDecisionSha256": "2" * 64,
            "targetAdmissionId": "3" * 64,
            "targetAdmissionSha256": "4" * 64,
            "repairTargetPath": "main/java/fixture/ClosureCandidate.java",
        }
        for field, value in v2_values.items():
            with self.subTest(schema="v1-progress", field=field), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                prepare_terminal_history(fx, "VERIFIED_CLOSED")
                descriptor = closure_wave_descriptor(fx)
                intake = audit._load_closure_intake(fx.closure_proof_root, descriptor)
                progress = json.loads(
                    (fx.closure_proof_root / "repair-progress.jsonl").read_text(encoding="utf-8")
                )
                progress[field] = value
                write_progress_row(fx, progress)
                self.assert_reason(
                    "closure-progress-mismatch",
                    audit._load_progress_rows,
                    proof_root=fx.closure_proof_root,
                    intake=intake,
                )

            with self.subTest(schema="v1-event", field=field), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                prepare_terminal_history(fx, "VERIFIED_CLOSED")
                descriptor = closure_wave_descriptor(fx)
                event = read_closure_event(fx)
                event[field] = value
                write_closure_event(fx, event)
                self.assert_reason(
                    "closure-journal-malformed",
                    audit.load_closure_wave,
                    root=fx.root,
                    descriptor=descriptor,
                )

            with self.subTest(schema="v2-progress", field=field), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                context = prepare_v2_terminal_history(fx)
                progress = copy.deepcopy(context["progress"])
                progress.pop(field)
                write_progress_row(fx, progress)
                self.assert_reason(
                    "closure-progress-mismatch",
                    audit._load_progress_rows,
                    proof_root=fx.closure_proof_root,
                    intake=context["intake"],
                )

            with self.subTest(schema="v2-event", field=field), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                context = prepare_v2_terminal_history(fx)
                event = copy.deepcopy(context["event"])
                event.pop(field)
                write_closure_event(fx, event)
                self.assert_reason(
                    "closure-journal-malformed",
                    audit.load_closure_wave,
                    root=fx.root,
                    descriptor=context["descriptor"],
                )

    def test_v2_event_baseline_rejects_non_target_drift_and_body_only_disappearance_claim(self):
        """Catches frozen non-target drift or body-only closure while the detector fingerprint remains active."""
        mutations = {
            "sha256": lambda row: row.update(sha256="f" * 64),
            "sizeBytes": lambda row: row.update(sizeBytes=row["sizeBytes"] + 1),
            "gitState": lambda row: row.update(gitState="modified" if row["gitState"] != "modified" else "clean"),
        }
        for row_index in range(1, 5):
            for field, mutate in mutations.items():
                with self.subTest(row=row_index, field=field), tempfile.TemporaryDirectory() as tmp:
                    fx = make_fixture(Path(tmp))
                    context = prepare_v2_terminal_history(fx)
                    baseline = copy.deepcopy(context["baseline"])
                    self.assertNotEqual(context["repairPath"], baseline["declaredTargets"][row_index]["path"])
                    mutate(baseline["declaredTargets"][row_index])
                    event = copy.deepcopy(context["event"])
                    write_event_baseline_and_bind(fx, event, baseline)
                    self.assert_reason(
                        "closure-proof-invalid",
                        audit.load_closure_wave,
                        root=fx.root,
                        descriptor=context["descriptor"],
                    )

        with self.subTest(case="wrong-disposition"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            context = prepare_v2_terminal_history(fx)
            event = copy.deepcopy(context["event"])
            event["fingerprintDisposition"] = "ACCEPTED_BOUNDED_TRANSFORMATION"
            write_closure_event(fx, event)
            self.assert_reason(
                "closure-proof-invalid",
                audit.load_closure_wave,
                root=fx.root,
                descriptor=context["descriptor"],
            )
        self.assert_v2_detector_disappearance_semantics()

    def assert_v2_detector_disappearance_semantics(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            context = prepare_v2_terminal_history(fx)
            wave = audit.load_closure_wave(
                root=fx.root,
                descriptor=context["descriptor"],
            )
            history = audit.ClosureHistory(
                predecessor_rows=wave.predecessor_rows,
                all_events=wave.all_events,
                active_events=wave.active_events,
                wave_registry_sha256=sha256_hex(b"v2-overlay-registry"),
                wave_count=1,
                journal_set_sha256=wave.journal_payload_sha256,
                proof_set_sha256=sha256_hex(canonical_bytes([list(pair) for pair in wave.proof_pairs])),
                event_count=1,
                rejected_false_positive_root_cause_groups=0,
                verified_closed_root_cause_groups=1,
            )
            current_baseline_id = sha256_hex(b"current-v2-overlay-baseline")
            current_row = copy.deepcopy(context["predecessor"])
            current_row["baselineId"] = current_baseline_id
            self.assert_reason(
                "closure-fingerprint-active",
                audit.apply_closure_overlay,
                (current_row,),
                history,
                baseline_id=current_baseline_id,
            )
            overlaid = audit.apply_closure_overlay(
                (),
                history,
                baseline_id=current_baseline_id,
            )

        self.assertEqual(
            "VERIFIED_CLOSED",
            next(
                row["status"]
                for row in overlaid
                if row["issueId"] == context["predecessor"]["issueId"]
            ),
        )

    def test_v2_overlay_requires_detector_fingerprint_disappearance(self):
        """Catches granting v2 closure credit while the same detector fingerprint remains active."""
        self.assert_v2_detector_disappearance_semantics()

    def test_closure_registry_accepts_exact_canonical_four_wave_descriptor_set(self):
        """Catches publishing Wave 4 without the exact pinned descriptor set."""
        descriptors = [
            copy.deepcopy(WAVE_ONE_DESCRIPTOR),
            copy.deepcopy(WAVE_TWO_DESCRIPTOR),
            copy.deepcopy(WAVE_THREE_DESCRIPTOR),
            copy.deepcopy(WAVE_FOUR_DESCRIPTOR),
        ]
        expected_payload = canonical_bytes(registry_payload(descriptors))
        registry_path = ROOT / REGISTRY_RELATIVE
        expected_pin = sha256_hex(expected_payload)
        live_registry_bytes = registry_path.read_bytes()
        live_registry_pin = sha256_hex(live_registry_bytes)
        self.assertEqual(expected_payload, live_registry_bytes)
        self.assertEqual(expected_pin, IMPORTED_CLOSURE_WAVE_REGISTRY_SHA256)
        self.assertEqual(expected_pin, live_registry_pin)
        with mock.patch.object(
            audit,
            "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
            live_registry_pin,
        ):
            registry = audit.load_closure_registry(
                root=ROOT,
                registry_path=REGISTRY_RELATIVE,
            )

        self.assertIsInstance(registry, audit.ClosureRegistry)
        self.assertEqual(registry_path.resolve(), registry.path)
        self.assertEqual(sha256_hex(expected_payload), registry.payload_sha256)
        self.assertEqual(4, len(registry.waves))
        for expected, wave in zip(descriptors, registry.waves, strict=True):
            self.assertIsInstance(wave, audit.ClosureWaveDescriptor)
            self.assertEqual(expected["waveId"], wave.wave_id)
            self.assertEqual(expected["ordinal"], wave.ordinal)
            self.assertEqual(
                (ROOT / str(expected["journalPath"])).resolve(),
                wave.journal_path,
            )
            self.assertEqual(
                (ROOT / str(expected["proofRoot"])).resolve(),
                wave.proof_root,
            )

    def test_closure_registry_rejects_missing_and_wrong_fixed_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            self.assert_reason(
                "closure-registry-missing",
                audit.load_closure_registry,
                root=root,
                registry_path=REGISTRY_RELATIVE,
            )

            descriptors = [copy.deepcopy(WAVE_ONE_DESCRIPTOR)]
            ensure_registry_wave_paths(root, descriptors)
            write_registry(root, registry_payload(descriptors))
            self.assert_reason(
                "closure-registry-malformed",
                audit.load_closure_registry,
                root=root,
                registry_path=Path(
                    "verification/structural-repair-waves/not-registry.json"
                ),
            )

        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            (root / REGISTRY_RELATIVE).mkdir(parents=True)
            self.assert_reason(
                "closure-registry-malformed",
                audit.load_closure_registry,
                root=root,
                registry_path=REGISTRY_RELATIVE,
            )

    def test_closure_registry_rejects_bom_noncanonical_bytes_and_unknown_fields(self):
        base = registry_payload()
        unknown_root = copy.deepcopy(base)
        unknown_root["unexpected"] = False
        unknown_descriptor = copy.deepcopy(base)
        unknown_descriptor["waves"][0]["unexpected"] = False
        cases = {
            "bom": b"\xef\xbb\xbf" + canonical_bytes(base),
            "invalid-utf8": b"\xff\n",
            "pretty-json": (
                json.dumps(base, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
            ).encode("utf-8"),
            "extra-trailing-lf": canonical_bytes(base) + b"\n",
            "unknown-root-field": canonical_bytes(unknown_root),
            "unknown-descriptor-field": canonical_bytes(unknown_descriptor),
        }
        for case, raw in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                root = make_registry_root(Path(tmp))
                ensure_registry_wave_paths(root, [copy.deepcopy(WAVE_ONE_DESCRIPTOR)])
                write_registry(root, base, raw=raw)
                with mock.patch.object(
                    audit,
                    "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                    sha256_hex(raw),
                    create=True,
                ):
                    self.assert_reason(
                        "closure-registry-malformed",
                        audit.load_closure_registry,
                        root=root,
                        registry_path=REGISTRY_RELATIVE,
                    )

    def test_closure_registry_rejects_wrong_pin_and_non_lowercase_hashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            descriptors = [copy.deepcopy(WAVE_ONE_DESCRIPTOR)]
            ensure_registry_wave_paths(root, descriptors)
            write_registry(root, registry_payload(descriptors))
            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                "0" * 64,
                create=True,
            ):
                self.assert_reason(
                    "closure-registry-mismatch",
                    audit.load_closure_registry,
                    root=root,
                    registry_path=REGISTRY_RELATIVE,
                )

        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            descriptor = copy.deepcopy(WAVE_ONE_DESCRIPTOR)
            descriptor["journalPath"] = "verification/not-created/journal.jsonl"
            write_registry(root, registry_payload([descriptor]))
            self.assert_reason(
                "closure-registry-mismatch",
                audit.load_closure_registry,
                root=root,
                registry_path=REGISTRY_RELATIVE,
            )

        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            descriptor = copy.deepcopy(WAVE_ONE_DESCRIPTOR)
            descriptor["sourceBaselineId"] = str(
                descriptor["sourceBaselineId"]
            ).upper()
            value = registry_payload([descriptor])
            _, raw = write_registry(root, value)
            with mock.patch.object(
                audit,
                "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                sha256_hex(raw),
                create=True,
            ):
                self.assert_reason(
                    "closure-registry-malformed",
                    audit.load_closure_registry,
                    root=root,
                    registry_path=REGISTRY_RELATIVE,
                )

    def test_closure_registry_rejects_absolute_traversal_alias_and_reparse_paths(self):
        invalid_journals = {
            "absolute": "C:/outside/journal.jsonl",
            "unc": "//server/share/journal.jsonl",
            "traversal": "verification/../journal.jsonl",
            "wildcard": "verification/*/journal.jsonl",
            "uri": "file://verification/journal.jsonl",
        }
        for case, journal_path in invalid_journals.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                root = make_registry_root(Path(tmp))
                descriptor = copy.deepcopy(WAVE_ONE_DESCRIPTOR)
                descriptor["journalPath"] = journal_path
                value = registry_payload([descriptor])
                _, raw = write_registry(root, value)
                with mock.patch.object(
                    audit,
                    "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                    sha256_hex(raw),
                    create=True,
                ):
                    self.assert_reason(
                        "closure-registry-malformed",
                        audit.load_closure_registry,
                        root=root,
                        registry_path=REGISTRY_RELATIVE,
                    )

        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            descriptors = [copy.deepcopy(WAVE_ONE_DESCRIPTOR)]
            ensure_registry_wave_paths(root, descriptors)
            registry_path, _ = write_registry(root, registry_payload(descriptors))
            for case, candidate in {
                "absolute-registry": registry_path,
                "registry-alias": Path(
                    "verification/structural-repair-waves/alias/../registry.json"
                ),
            }.items():
                with self.subTest(case=case):
                    self.assert_reason(
                        "closure-registry-malformed",
                        audit.load_closure_registry,
                        root=root,
                        registry_path=candidate,
                    )

            with mock.patch.object(
                audit,
                "_has_reparse_component",
                return_value=True,
            ):
                self.assert_reason(
                    "closure-registry-malformed",
                    audit.load_closure_registry,
                    root=root,
                    registry_path=REGISTRY_RELATIVE,
                )

    def test_closure_registry_rejects_duplicate_gapped_or_out_of_order_ordinals(self):
        cases = {
            "boolean": [registry_descriptor(True)],
            "duplicate": [
                registry_descriptor(1),
                registry_descriptor(1, path_suffix="-duplicate"),
            ],
            "gapped": [registry_descriptor(1), registry_descriptor(3)],
            "out-of-order": [registry_descriptor(2), registry_descriptor(1)],
        }
        for case, descriptors in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                root = make_registry_root(Path(tmp))
                ensure_registry_wave_paths(root, descriptors)
                value = registry_payload(descriptors)
                _, raw = write_registry(root, value)
                with mock.patch.object(
                    audit,
                    "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                    sha256_hex(raw),
                    create=True,
                ):
                    self.assert_reason(
                        "closure-registry-malformed",
                        audit.load_closure_registry,
                        root=root,
                        registry_path=REGISTRY_RELATIVE,
                    )

    def test_closure_registry_rejects_wave_id_ordinal_mismatch_and_path_collisions(self):
        mismatch = [registry_descriptor(1)]
        mismatch[0]["waveId"] = "wave-0002"
        duplicate_journal = [registry_descriptor(1), registry_descriptor(2)]
        duplicate_journal[1]["journalPath"] = duplicate_journal[0]["journalPath"]
        case_only_journal = [registry_descriptor(1), registry_descriptor(2)]
        case_only_journal[1]["journalPath"] = str(
            case_only_journal[0]["journalPath"]
        ).replace("verification/", "Verification/", 1)
        duplicate_proof_root = [registry_descriptor(1), registry_descriptor(2)]
        duplicate_proof_root[1]["proofRoot"] = duplicate_proof_root[0]["proofRoot"]
        case_only_proof_root = [registry_descriptor(1), registry_descriptor(2)]
        case_only_proof_root[1]["proofRoot"] = str(
            case_only_proof_root[0]["proofRoot"]
        ).replace(".superpowers/", ".SUPERPOWERS/", 1)
        cases = {
            "wave-id-ordinal-mismatch": mismatch,
            "journal-path-collision": duplicate_journal,
            "journal-path-case-only-collision": case_only_journal,
            "proof-root-collision": duplicate_proof_root,
            "proof-root-case-only-collision": case_only_proof_root,
        }
        for case, descriptors in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                root = make_registry_root(Path(tmp))
                ensure_registry_wave_paths(root, descriptors)
                value = registry_payload(descriptors)
                _, raw = write_registry(root, value)
                with mock.patch.object(
                    audit,
                    "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                    sha256_hex(raw),
                    create=True,
                ):
                    self.assert_reason(
                        "closure-registry-malformed",
                        audit.load_closure_registry,
                        root=root,
                        registry_path=REGISTRY_RELATIVE,
                    )

    def test_load_closure_wave_preserves_wave_one_predecessors_events_and_proofs(self):
        live_registry_bytes = (
            ROOT / audit.CLOSURE_WAVE_REGISTRY_RELATIVE
        ).read_bytes()
        with mock.patch.object(
            audit,
            "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
            sha256_hex(live_registry_bytes),
        ):
            registry = audit.load_closure_registry(
                root=ROOT,
                registry_path=REGISTRY_RELATIVE,
            )
            history = audit.load_closure_wave(
                root=ROOT,
                descriptor=registry.waves[0],
            )

        self.assertIsInstance(history, audit.ClosureWaveHistory)
        self.assertEqual(registry.waves[0], history.descriptor)
        self.assertEqual(
            WAVE_ONE_PREDECESSOR_ISSUE_IDS,
            tuple(row["issueId"] for row in history.predecessor_rows),
        )
        self.assertEqual(
            WAVE_ONE_EVENT_IDS,
            tuple(event["eventId"] for event in history.all_events),
        )
        self.assertEqual(
            WAVE_ONE_EVENT_IDS,
            tuple(event["eventId"] for event in history.active_events),
        )
        self.assertEqual(
            5,
            sum(
                event["eventType"] == "REJECTED_FALSE_POSITIVE"
                for event in history.active_events
            ),
        )
        self.assertEqual(
            5,
            sum(
                event["eventType"] == "VERIFIED_CLOSED"
                for event in history.active_events
            ),
        )
        self.assertEqual(WAVE_ONE_JOURNAL_SHA256, history.journal_payload_sha256)

        referenced_pairs: dict[str, str] = {}
        for event in history.all_events:
            for id_field, hash_field in (
                ("redProofId", "redProofSha256"),
                ("greenProofId", "greenProofSha256"),
                ("eventBaselineProofId", "eventBaselineProofSha256"),
            ):
                referenced_pairs[event[id_field]] = event[hash_field]
            if event["patchStateProofId"] is not None:
                referenced_pairs[event["patchStateProofId"]] = event[
                    "patchStateProofSha256"
                ]
        expected_pairs = tuple(sorted(referenced_pairs.items()))
        self.assertEqual(35, len(expected_pairs))
        self.assertEqual(expected_pairs, history.proof_pairs)
        self.assertEqual(
            WAVE_ONE_PROOF_SET_SHA256,
            sha256_hex(canonical_bytes([list(pair) for pair in history.proof_pairs])),
        )

        wave_one_issue_ids = {
            row["issueId"] for row in history.predecessor_rows
        }
        terminal_identity_rows = []
        for line in (
            ROOT / "verification/structural-design-debt-ledger.jsonl"
        ).read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            if row.get("issueId") in wave_one_issue_ids and row.get("status") in {
                "REJECTED_FALSE_POSITIVE",
                "VERIFIED_CLOSED",
            }:
                terminal_identity_rows.append(
                    {
                        key: value
                        for key, value in ledger_core(row).items()
                        if key != "baselineId"
                    }
                )
        self.assertEqual(10, len(terminal_identity_rows))
        self.assertEqual(
            WAVE_ONE_TERMINAL_IDENTITY_ROWS_SHA256,
            sha256_hex(canonical_bytes(terminal_identity_rows)),
        )

    def test_load_closure_wave_validates_empty_registered_wave_intake_and_progress(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            predecessor, _ = prepare_terminal_history(
                fx,
                "REJECTED_FALSE_POSITIVE",
            )
            fx.closure_journal.write_bytes(b"")
            (fx.closure_proof_root / audit.PROGRESS_RELATIVE).write_bytes(b"")
            descriptor = closure_wave_descriptor(fx)

            history = audit.load_closure_wave(root=fx.root, descriptor=descriptor)

        self.assertEqual((predecessor["issueId"],), tuple(
            row["issueId"] for row in history.predecessor_rows
        ))
        self.assertEqual((), history.all_events)
        self.assertEqual((), history.active_events)
        self.assertEqual(sha256_hex(b""), history.journal_payload_sha256)
        self.assertEqual((), history.proof_pairs)

    def test_load_closure_wave_rejects_empty_wave_with_missing_or_modified_intake(self):
        for relative in (
            audit.INTAKE_SUMMARY_RELATIVE,
            audit.INTAKE_ROWS_RELATIVE,
            audit.TARGET_PREIMAGES_RELATIVE,
        ):
            for mutation in ("missing", "modified"):
                with (
                    self.subTest(path=relative.as_posix(), mutation=mutation),
                    tempfile.TemporaryDirectory() as tmp,
                ):
                    fx = make_fixture(Path(tmp))
                    prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
                    fx.closure_journal.write_bytes(b"")
                    (fx.closure_proof_root / audit.PROGRESS_RELATIVE).write_bytes(b"")
                    descriptor = closure_wave_descriptor(fx)
                    intake_path = fx.closure_proof_root / relative
                    if mutation == "missing":
                        intake_path.unlink()
                    else:
                        intake_path.write_bytes(intake_path.read_bytes() + b"x")

                    self.assert_reason(
                        "closure-intake-mismatch",
                        audit.load_closure_wave,
                        root=fx.root,
                        descriptor=descriptor,
                    )

        with self.subTest(order="intake-before-missing-journal"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            fx.closure_journal.write_bytes(b"")
            descriptor = closure_wave_descriptor(fx)
            intake_path = fx.closure_proof_root / audit.INTAKE_SUMMARY_RELATIVE
            intake_path.write_bytes(intake_path.read_bytes() + b"x")
            fx.closure_journal.unlink()
            self.assert_reason(
                "closure-intake-mismatch",
                audit.load_closure_wave,
                root=fx.root,
                descriptor=descriptor,
            )

        with self.subTest(order="progress-before-missing-journal"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            fx.closure_journal.write_bytes(b"")
            descriptor = closure_wave_descriptor(fx)
            (fx.closure_proof_root / audit.PROGRESS_RELATIVE).unlink()
            fx.closure_journal.unlink()
            self.assert_reason(
                "closure-progress-mismatch",
                audit.load_closure_wave,
                root=fx.root,
                descriptor=descriptor,
            )

        with self.subTest(order="missing-journal-only"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            fx.closure_journal.write_bytes(b"")
            descriptor = closure_wave_descriptor(fx)
            fx.closure_journal.unlink()
            self.assert_reason(
                "closure-journal-missing",
                audit.load_closure_wave,
                root=fx.root,
                descriptor=descriptor,
            )

    def test_load_closure_wave_confines_progress_and_referenced_proofs_to_its_proof_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "VERIFIED_CLOSED")
            descriptor = closure_wave_descriptor(fx)
            progress = fx.closure_proof_root / audit.PROGRESS_RELATIVE
            (fx.root / "repair-progress.jsonl").write_bytes(progress.read_bytes())
            progress.unlink()
            self.assert_reason(
                "closure-progress-mismatch",
                audit.load_closure_wave,
                root=fx.root,
                descriptor=descriptor,
            )

        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            descriptor = closure_wave_descriptor(fx)
            event = read_closure_event(fx)
            red_proof = fx.closure_proof_root / str(event["redProofId"])
            (fx.root / "outside-red-summary.json").write_bytes(red_proof.read_bytes())
            red_proof.unlink()
            self.assert_reason(
                "closure-proof-invalid",
                audit.load_closure_wave,
                root=fx.root,
                descriptor=descriptor,
            )

        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            descriptor = closure_wave_descriptor(fx)
            unreferenced = write_bytes(
                fx.closure_proof_root,
                "private/unreferenced.json",
                b"private-unreferenced-bytes",
            )
            history = audit.load_closure_wave(root=fx.root, descriptor=descriptor)
            self.assertTrue(unreferenced.is_file())
            self.assertEqual(3, len(history.proof_pairs))
            self.assertNotIn(
                "private/unreferenced.json",
                {proof_id for proof_id, _ in history.proof_pairs},
            )

    def test_load_closure_wave_rejects_descriptor_intake_and_predecessor_tuple_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            descriptor = closure_wave_descriptor(fx)
            cases = {
                "intake-hash": replace(descriptor, intake_summary_sha256="0" * 64),
                "predecessor": replace(descriptor, source_baseline_id="0" * 64),
            }
            for case, mismatched in cases.items():
                with self.subTest(case=case):
                    self.assert_reason(
                        "closure-intake-mismatch",
                        audit.load_closure_wave,
                        root=fx.root,
                        descriptor=mismatched,
                    )

    def test_load_closure_wave_prospectively_validates_journal_bytes_without_writing(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            descriptor = closure_wave_descriptor(fx)
            original_bytes = fx.closure_journal.read_bytes()
            stored = audit.load_closure_wave(root=fx.root, descriptor=descriptor)

            prospective = audit._load_closure_wave_from_journal_bytes(
                root=fx.root,
                descriptor=descriptor,
                journal_bytes=original_bytes,
            )
            self.assertEqual(stored, prospective)
            self.assert_reason(
                "closure-journal-malformed",
                audit._load_closure_wave_from_journal_bytes,
                root=fx.root,
                descriptor=descriptor,
                journal_bytes=original_bytes + b"\n",
            )
            self.assertEqual(original_bytes, fx.closure_journal.read_bytes())

    def test_closure_history_accepts_valid_wave_one_plus_empty_wave_two(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            wave_one = aggregate_wave_descriptor(root, 1)
            wave_two = aggregate_wave_descriptor(root, 2)
            issue_one = aggregate_fixture_hex("wave-one-issue")
            group_one = aggregate_fixture_hex("wave-one-group")
            issue_two = aggregate_fixture_hex("wave-two-issue")
            group_two = aggregate_fixture_hex("wave-two-group")
            event_one = aggregate_event(
                issue_id=issue_one,
                group_id=group_one,
                event_type="REJECTED_FALSE_POSITIVE",
                label="wave-one-event",
            )
            histories = {
                wave_one.wave_id: aggregate_wave_history(
                    wave_one,
                    [aggregate_predecessor(issue_one, group_one)],
                    [event_one],
                ),
                wave_two.wave_id: aggregate_wave_history(
                    wave_two,
                    [aggregate_predecessor(issue_two, group_two)],
                    [],
                ),
            }
            load_order: list[str] = []

            def load_wave(*, root: Path, descriptor: object) -> object:
                load_order.append(descriptor.wave_id)
                return histories[descriptor.wave_id]

            with mock.patch.object(audit, "load_closure_wave", side_effect=load_wave):
                history = audit.load_closure_history(
                    root=root,
                    registry=aggregate_registry(root, [wave_one, wave_two]),
                )

        self.assertEqual(["wave-0001", "wave-0002"], load_order)
        self.assertEqual(2, history.wave_count)
        self.assertEqual(
            (issue_one, issue_two),
            tuple(row["issueId"] for row in history.predecessor_rows),
        )
        self.assertEqual((event_one["eventId"],), tuple(
            event["eventId"] for event in history.all_events
        ))
        self.assertEqual(history.all_events, history.active_events)
        self.assertEqual(1, history.event_count)
        self.assertEqual(1, history.rejected_false_positive_root_cause_groups)
        self.assertEqual(0, history.verified_closed_root_cause_groups)

    def test_history_accepts_wave_one_wave_two_and_empty_reviewed_duplicate_wave_three(self):
        """Catches aggregate counts changing when an empty reviewed v2 wave is added."""
        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            waves = [aggregate_wave_descriptor(root, ordinal) for ordinal in (1, 2, 3)]
            issue_one = aggregate_fixture_hex("three-wave-one-issue")
            group_one = aggregate_fixture_hex("three-wave-one-group")
            issue_two = aggregate_fixture_hex("three-wave-two-issue")
            group_two = aggregate_fixture_hex("three-wave-two-group")
            issue_three = aggregate_fixture_hex("three-wave-three-issue")
            group_three = aggregate_fixture_hex("three-wave-three-group")
            event_one = aggregate_event(
                issue_id=issue_one,
                group_id=group_one,
                event_type="REJECTED_FALSE_POSITIVE",
                label="three-wave-one-event",
            )
            event_two = aggregate_event(
                issue_id=issue_two,
                group_id=group_two,
                event_type="VERIFIED_CLOSED",
                label="three-wave-two-event",
            )
            histories = {
                waves[0].wave_id: aggregate_wave_history(
                    waves[0],
                    [aggregate_predecessor(issue_one, group_one)],
                    [event_one],
                ),
                waves[1].wave_id: aggregate_wave_history(
                    waves[1],
                    [aggregate_predecessor(issue_two, group_two)],
                    [event_two],
                ),
                waves[2].wave_id: aggregate_wave_history(
                    waves[2],
                    [aggregate_predecessor(issue_three, group_three)],
                    [],
                ),
            }
            with mock.patch.object(
                audit,
                "load_closure_wave",
                side_effect=lambda *, root, descriptor: histories[descriptor.wave_id],
            ):
                history = audit.load_closure_history(
                    root=root,
                    registry=aggregate_registry(root, waves),
                )

        self.assertEqual(3, history.wave_count)
        self.assertEqual(2, history.event_count)
        self.assertEqual(1, history.rejected_false_positive_root_cause_groups)
        self.assertEqual(1, history.verified_closed_root_cause_groups)
        self.assertEqual(
            {event_one["eventId"], event_two["eventId"]},
            {event["eventId"] for event in history.active_events},
        )

    def test_history_reconstructs_v2_target_admission_proof_pairs_without_extras(self):
        """Catches aggregate history omitting the four v2 admission proof pairs."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            context = prepare_v2_terminal_history(fx)
            descriptor = context["descriptor"]
            wave = audit.load_closure_wave(root=fx.root, descriptor=descriptor)
            prior_descriptors = [
                aggregate_wave_descriptor(fx.root, ordinal)
                for ordinal in (1, 2)
            ]
            prior_waves = {
                item.wave_id: aggregate_wave_history(item, [], [])
                for item in prior_descriptors
            }
            histories = {**prior_waves, descriptor.wave_id: wave}
            descriptors = [*prior_descriptors, descriptor]
            expected_paths = {
                context["admissionPath"],
                *context["admissionProofPaths"][1:],
            }
            expected_pairs = sorted(
                [descriptor.wave_id, proof_id, proof_sha256]
                for proof_id, proof_sha256 in wave.proof_pairs
            )
            with mock.patch.object(
                audit,
                "load_closure_wave",
                side_effect=lambda *, root, descriptor: histories[descriptor.wave_id],
            ):
                history = audit.load_closure_history(
                    root=fx.root,
                    registry=aggregate_registry(fx.root, descriptors),
                )

        self.assertEqual(
            (context["admission"],),
            wave.target_admissions,
        )
        self.assertEqual(
            expected_paths,
            {proof_id for proof_id, _ in wave.proof_pairs} & expected_paths,
        )
        self.assertEqual(
            len(expected_paths),
            sum(proof_id in expected_paths for proof_id, _ in wave.proof_pairs),
        )
        self.assertEqual(
            sha256_hex(canonical_bytes(expected_pairs)),
            history.proof_set_sha256,
        )

    def test_history_rejects_cross_wave_or_unreferenced_v2_target_admission_pairs(self):
        """Catches moved, duplicated, or unreferenced v2 admission evidence."""
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            context = prepare_v2_terminal_history(fx)
            descriptor = context["descriptor"]
            wave = audit.load_closure_wave(root=fx.root, descriptor=descriptor)
            prior_descriptors = [
                aggregate_wave_descriptor(fx.root, ordinal)
                for ordinal in (1, 2)
            ]
            prior_waves = {
                item.wave_id: aggregate_wave_history(item, [], [])
                for item in prior_descriptors
            }
            descriptors = [*prior_descriptors, descriptor]
            admission = wave.target_admissions[0]
            admission_paths = set(context["admissionProofPaths"])
            foreign_group = aggregate_fixture_hex("foreign-admission-group")
            moved_pairs = tuple(
                (
                    proof_id.replace(str(admission["rootCauseGroupId"]), foreign_group),
                    proof_hash,
                )
                if proof_id in admission_paths
                else (proof_id, proof_hash)
                for proof_id, proof_hash in wave.proof_pairs
            )
            extra_pair = (
                f"proofs/{foreign_group}/repair-target-admission.json",
                aggregate_fixture_hex("unreferenced-admission-proof"),
            )
            cases = {
                "moved-supporting-pairs": (
                    descriptors,
                    {
                        **prior_waves,
                        descriptor.wave_id: replace(wave, proof_pairs=moved_pairs),
                    },
                ),
                "extra-supporting-pair": (
                    descriptors,
                    {
                        **prior_waves,
                        descriptor.wave_id: replace(
                            wave,
                            proof_pairs=tuple(sorted((*wave.proof_pairs, extra_pair))),
                        )
                    },
                ),
                "duplicate-admission": (
                    descriptors,
                    {
                        **prior_waves,
                        descriptor.wave_id: replace(
                            wave,
                            target_admissions=(admission, admission),
                        )
                    },
                ),
                "cross-wave-admission": (
                    descriptors,
                    {
                        **prior_waves,
                        prior_descriptors[1].wave_id: replace(
                            prior_waves[prior_descriptors[1].wave_id],
                            target_admissions=(admission,),
                        ),
                        descriptor.wave_id: wave,
                    },
                ),
            }
            for case, (descriptors, histories) in cases.items():
                with self.subTest(case=case), mock.patch.object(
                    audit,
                    "load_closure_wave",
                    side_effect=lambda *, root, descriptor: histories[descriptor.wave_id],
                ):
                    self.assert_reason(
                        "closure-wave-conflict",
                        audit.load_closure_history,
                        root=fx.root,
                        registry=aggregate_registry(fx.root, descriptors),
                    )

    def test_closure_history_rejects_duplicate_issue_or_group_ownership(self):
        for collision in ("issue", "group"):
            with self.subTest(collision=collision), tempfile.TemporaryDirectory() as tmp:
                root = make_registry_root(Path(tmp))
                wave_one = aggregate_wave_descriptor(root, 1)
                wave_two = aggregate_wave_descriptor(root, 2)
                issue_one = aggregate_fixture_hex("owner-one-issue")
                group_one = aggregate_fixture_hex("owner-one-group")
                issue_two = (
                    issue_one
                    if collision == "issue"
                    else aggregate_fixture_hex("owner-two-issue")
                )
                group_two = (
                    group_one
                    if collision == "group"
                    else aggregate_fixture_hex("owner-two-group")
                )
                histories = {
                    wave_one.wave_id: aggregate_wave_history(
                        wave_one,
                        [aggregate_predecessor(issue_one, group_one)],
                        [],
                    ),
                    wave_two.wave_id: aggregate_wave_history(
                        wave_two,
                        [aggregate_predecessor(issue_two, group_two)],
                        [],
                    ),
                }
                with mock.patch.object(
                    audit,
                    "load_closure_wave",
                    side_effect=lambda *, root, descriptor: histories[descriptor.wave_id],
                ):
                    self.assert_reason(
                        "closure-wave-conflict",
                        audit.load_closure_history,
                        root=root,
                        registry=aggregate_registry(root, [wave_one, wave_two]),
                    )

    def test_closure_history_rejects_duplicate_event_ids_across_waves(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            wave_one = aggregate_wave_descriptor(root, 1)
            wave_two = aggregate_wave_descriptor(root, 2)
            issue_one = aggregate_fixture_hex("duplicate-event-one-issue")
            group_one = aggregate_fixture_hex("duplicate-event-one-group")
            issue_two = aggregate_fixture_hex("duplicate-event-two-issue")
            group_two = aggregate_fixture_hex("duplicate-event-two-group")
            duplicate_id = aggregate_fixture_hex("duplicate-event-id")
            histories = {
                wave_one.wave_id: aggregate_wave_history(
                    wave_one,
                    [aggregate_predecessor(issue_one, group_one)],
                    [aggregate_event(
                        issue_id=issue_one,
                        group_id=group_one,
                        event_type="REJECTED_FALSE_POSITIVE",
                        label="duplicate-event-one",
                        event_id=duplicate_id,
                    )],
                ),
                wave_two.wave_id: aggregate_wave_history(
                    wave_two,
                    [aggregate_predecessor(issue_two, group_two)],
                    [aggregate_event(
                        issue_id=issue_two,
                        group_id=group_two,
                        event_type="REJECTED_FALSE_POSITIVE",
                        label="duplicate-event-two",
                        event_id=duplicate_id,
                    )],
                ),
            }
            with mock.patch.object(
                audit,
                "load_closure_wave",
                side_effect=lambda *, root, descriptor: histories[descriptor.wave_id],
            ):
                self.assert_reason(
                    "closure-wave-conflict",
                    audit.load_closure_history,
                    root=root,
                    registry=aggregate_registry(root, [wave_one, wave_two]),
                )

    def test_closure_history_rejects_cross_wave_supersession(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            wave_one = aggregate_wave_descriptor(root, 1)
            wave_two = aggregate_wave_descriptor(root, 2)
            issue_one = aggregate_fixture_hex("cross-wave-one-issue")
            group_one = aggregate_fixture_hex("cross-wave-one-group")
            issue_two = aggregate_fixture_hex("cross-wave-two-issue")
            group_two = aggregate_fixture_hex("cross-wave-two-group")
            first = aggregate_event(
                issue_id=issue_one,
                group_id=group_one,
                event_type="REJECTED_FALSE_POSITIVE",
                label="cross-wave-first",
            )
            second = aggregate_event(
                issue_id=issue_two,
                group_id=group_two,
                event_type="REJECTED_FALSE_POSITIVE",
                label="cross-wave-second",
                supersedes=str(first["eventId"]),
            )
            histories = {
                wave_one.wave_id: aggregate_wave_history(
                    wave_one,
                    [aggregate_predecessor(issue_one, group_one)],
                    [first],
                ),
                wave_two.wave_id: aggregate_wave_history(
                    wave_two,
                    [aggregate_predecessor(issue_two, group_two)],
                    [second],
                ),
            }
            with mock.patch.object(
                audit,
                "load_closure_wave",
                side_effect=lambda *, root, descriptor: histories[descriptor.wave_id],
            ):
                self.assert_reason(
                    "closure-wave-conflict",
                    audit.load_closure_history,
                    root=root,
                    registry=aggregate_registry(root, [wave_one, wave_two]),
                )

    def test_closure_history_accepts_same_wave_supersession_and_one_active_tip(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            wave_one = aggregate_wave_descriptor(root, 1)
            wave_two = aggregate_wave_descriptor(root, 2)
            issue_one = aggregate_fixture_hex("same-wave-issue")
            group_one = aggregate_fixture_hex("same-wave-group")
            issue_two = aggregate_fixture_hex("same-wave-empty-issue")
            group_two = aggregate_fixture_hex("same-wave-empty-group")
            first = aggregate_event(
                issue_id=issue_one,
                group_id=group_one,
                event_type="VERIFIED_CLOSED",
                label="same-wave-proof",
            )
            second = aggregate_event(
                issue_id=issue_one,
                group_id=group_one,
                event_type="VERIFIED_CLOSED",
                label="same-wave-proof",
                supersedes=str(first["eventId"]),
            )
            histories = {
                wave_one.wave_id: aggregate_wave_history(
                    wave_one,
                    [aggregate_predecessor(issue_one, group_one)],
                    [first, second],
                ),
                wave_two.wave_id: aggregate_wave_history(
                    wave_two,
                    [aggregate_predecessor(issue_two, group_two)],
                    [],
                ),
            }
            with mock.patch.object(
                audit,
                "load_closure_wave",
                side_effect=lambda *, root, descriptor: histories[descriptor.wave_id],
            ):
                history = audit.load_closure_history(
                    root=root,
                    registry=aggregate_registry(root, [wave_one, wave_two]),
                )

        self.assertEqual(
            (first["eventId"], second["eventId"]),
            tuple(event["eventId"] for event in history.all_events),
        )
        self.assertEqual(
            (second["eventId"],),
            tuple(event["eventId"] for event in history.active_events),
        )
        self.assertEqual(2, history.event_count)
        self.assertEqual(1, history.verified_closed_root_cause_groups)

    def test_closure_history_rejects_nested_or_aliased_proof_roots_and_journals(self):
        for case in (
            "nested-proof-root",
            "aliased-proof-root",
            "aliased-journal",
            "journal-inside-proof-root",
        ):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                root = make_registry_root(Path(tmp))
                wave_one = aggregate_wave_descriptor(root, 1)
                wave_two = aggregate_wave_descriptor(root, 2)
                if case == "nested-proof-root":
                    nested = wave_one.proof_root / "nested-wave-two"
                    nested.mkdir(parents=True)
                    wave_two = replace(wave_two, proof_root=nested)
                elif case == "aliased-proof-root":
                    alias = wave_one.proof_root / ".." / wave_one.proof_root.name
                    wave_two = replace(wave_two, proof_root=alias)
                elif case == "aliased-journal":
                    alias_hop = wave_one.journal_path.parent / "alias-hop"
                    alias_hop.mkdir()
                    alias = alias_hop / ".." / wave_one.journal_path.name
                    wave_two = replace(wave_two, journal_path=alias)
                else:
                    nested_journal = wave_one.proof_root / "wave-two-journal.jsonl"
                    nested_journal.write_bytes(b"")
                    wave_two = replace(wave_two, journal_path=nested_journal)
                histories = {
                    wave_one.wave_id: aggregate_wave_history(wave_one, [], []),
                    wave_two.wave_id: aggregate_wave_history(wave_two, [], []),
                }
                load_calls: list[str] = []

                def load_wave(*, root: Path, descriptor: object) -> object:
                    load_calls.append(descriptor.wave_id)
                    return histories[descriptor.wave_id]

                with mock.patch.object(audit, "load_closure_wave", side_effect=load_wave):
                    self.assert_reason(
                        "closure-wave-conflict",
                        audit.load_closure_history,
                        root=root,
                        registry=aggregate_registry(root, [wave_one, wave_two]),
                    )
                self.assertEqual([], load_calls)

    def test_closure_history_builds_deterministic_journal_and_proof_set_hashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            wave_one = aggregate_wave_descriptor(root, 1, create_paths=False)
            wave_two = aggregate_wave_descriptor(root, 2, create_paths=False)
            descriptors = [wave_one, wave_two]
            registry = aggregate_registry(root, descriptors)
            issue_one = aggregate_fixture_hex("deterministic-one-issue")
            group_one = aggregate_fixture_hex("deterministic-one-group")
            issue_two = aggregate_fixture_hex("deterministic-two-issue")
            group_two = aggregate_fixture_hex("deterministic-two-group")
            event_one = aggregate_event(
                issue_id=issue_one,
                group_id=group_one,
                event_type="REJECTED_FALSE_POSITIVE",
                label="deterministic-one",
            )
            event_two = aggregate_event(
                issue_id=issue_two,
                group_id=group_two,
                event_type="VERIFIED_CLOSED",
                label="deterministic-two",
            )
            histories = {
                wave_one.wave_id: aggregate_wave_history(
                    wave_one,
                    [aggregate_predecessor(issue_one, group_one)],
                    [event_one],
                ),
                wave_two.wave_id: aggregate_wave_history(
                    wave_two,
                    [aggregate_predecessor(issue_two, group_two)],
                    [event_two],
                ),
            }

            def materialize(order: list[object]) -> None:
                for descriptor in order:
                    descriptor.journal_path.parent.mkdir(parents=True, exist_ok=True)
                    descriptor.journal_path.write_bytes(b"")
                    descriptor.proof_root.mkdir(parents=True, exist_ok=True)

            def load_history() -> object:
                with mock.patch.object(
                    audit,
                    "load_closure_wave",
                    side_effect=lambda *, root, descriptor: histories[descriptor.wave_id],
                ):
                    return audit.load_closure_history(
                        root=root,
                        registry=registry,
                    )

            materialize(descriptors)
            normal = load_history()
            for descriptor in descriptors:
                descriptor.journal_path.unlink()
                descriptor.proof_root.rmdir()
            materialize(list(reversed(descriptors)))
            reverse = load_history()

            mirror_root = make_registry_root(Path(tmp) / "mirror")
            mirror_wave_one = aggregate_wave_descriptor(mirror_root, 1)
            mirror_wave_two = aggregate_wave_descriptor(mirror_root, 2)
            mirror_descriptors = [mirror_wave_one, mirror_wave_two]
            mirror_histories = {
                mirror_wave_one.wave_id: aggregate_wave_history(
                    mirror_wave_one,
                    [aggregate_predecessor(issue_one, group_one)],
                    [event_one],
                ),
                mirror_wave_two.wave_id: aggregate_wave_history(
                    mirror_wave_two,
                    [aggregate_predecessor(issue_two, group_two)],
                    [event_two],
                ),
            }
            with mock.patch.object(
                audit,
                "load_closure_wave",
                side_effect=lambda *, root, descriptor: mirror_histories[
                    descriptor.wave_id
                ],
            ):
                mirror = audit.load_closure_history(
                    root=mirror_root,
                    registry=aggregate_registry(mirror_root, mirror_descriptors),
                )

        self.assertEqual(normal.predecessor_rows, reverse.predecessor_rows)
        self.assertEqual(normal.all_events, reverse.all_events)
        self.assertEqual(normal.active_events, reverse.active_events)
        self.assertEqual(normal.journal_set_sha256, reverse.journal_set_sha256)
        self.assertEqual(normal.proof_set_sha256, reverse.proof_set_sha256)
        self.assertEqual(normal.journal_set_sha256, mirror.journal_set_sha256)
        self.assertEqual(normal.proof_set_sha256, mirror.proof_set_sha256)
        expected_journal_rows = [
            [
                descriptor.wave_id,
                descriptor.journal_path.relative_to(root).as_posix(),
                histories[descriptor.wave_id].journal_payload_sha256,
            ]
            for descriptor in descriptors
        ]
        expected_proof_rows = sorted(
            [descriptor.wave_id, proof_id, proof_hash]
            for descriptor in descriptors
            for proof_id, proof_hash in histories[descriptor.wave_id].proof_pairs
        )
        self.assertEqual(
            sha256_hex(canonical_bytes(expected_journal_rows)),
            normal.journal_set_sha256,
        )
        self.assertEqual(
            sha256_hex(canonical_bytes(expected_proof_rows)),
            normal.proof_set_sha256,
        )

    def test_closure_history_counts_all_events_but_only_active_terminal_groups(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_registry_root(Path(tmp))
            wave_one = aggregate_wave_descriptor(root, 1)
            wave_two = aggregate_wave_descriptor(root, 2)
            issue_one = aggregate_fixture_hex("count-one-issue")
            group_one = aggregate_fixture_hex("count-one-group")
            issue_two = aggregate_fixture_hex("count-two-issue")
            group_two = aggregate_fixture_hex("count-two-group")
            superseded = aggregate_event(
                issue_id=issue_one,
                group_id=group_one,
                event_type="REJECTED_FALSE_POSITIVE",
                label="count-chain-proof",
            )
            active_verified = aggregate_event(
                issue_id=issue_one,
                group_id=group_one,
                event_type="VERIFIED_CLOSED",
                label="count-chain-proof",
                supersedes=str(superseded["eventId"]),
            )
            active_rejected = aggregate_event(
                issue_id=issue_two,
                group_id=group_two,
                event_type="REJECTED_FALSE_POSITIVE",
                label="count-rejected",
            )
            histories = {
                wave_one.wave_id: aggregate_wave_history(
                    wave_one,
                    [aggregate_predecessor(issue_one, group_one)],
                    [superseded, active_verified],
                ),
                wave_two.wave_id: aggregate_wave_history(
                    wave_two,
                    [aggregate_predecessor(issue_two, group_two)],
                    [active_rejected],
                ),
            }
            with mock.patch.object(
                audit,
                "load_closure_wave",
                side_effect=lambda *, root, descriptor: histories[descriptor.wave_id],
            ):
                history = audit.load_closure_history(
                    root=root,
                    registry=aggregate_registry(root, [wave_one, wave_two]),
                )

        self.assertEqual(3, history.event_count)
        self.assertEqual(
            (active_verified["eventId"], active_rejected["eventId"]),
            tuple(event["eventId"] for event in history.active_events),
        )
        self.assertEqual(1, history.rejected_false_positive_root_cause_groups)
        self.assertEqual(1, history.verified_closed_root_cause_groups)

    def test_build_audit_loads_registry_before_baseline_enumeration(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            call_order: list[str] = []
            real_registry_loader = audit.load_closure_registry
            real_manifest_builder = audit.build_workspace_manifest

            def load_registry(*, root, registry_path):
                call_order.append("registry")
                return real_registry_loader(root=root, registry_path=registry_path)

            def build_manifest(*args, **kwargs):
                call_order.append("manifest")
                return real_manifest_builder(*args, **kwargs)

            with mock.patch.object(
                audit,
                "load_closure_registry",
                side_effect=load_registry,
            ), mock.patch.object(
                audit,
                "build_workspace_manifest",
                side_effect=build_manifest,
            ):
                audit.build_audit(make_inputs(fx), FIXED_NOW)

        self.assertLess(call_order.index("registry"), call_order.index("manifest"))

    def test_baseline_excludes_only_validated_registry_and_registered_journals(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        baseline_paths = {
            row["path"] for row in bundle.baseline["pathStateContentRows"]
        }
        self.assertNotIn(REGISTRY_RELATIVE.as_posix(), baseline_paths)
        self.assertNotIn(
            str(WAVE_ONE_DESCRIPTOR["journalPath"]),
            baseline_paths,
        )

    def test_unregistered_structural_repair_wave_sibling_remains_baseline_visible(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            sibling = "verification/structural-repair-waves/unregistered-sibling.jsonl"
            write_bytes(fx.root, sibling, b"unregistered-evidence\n")
            captured = [
                token
                for token in fx.git_paths.read_bytes().decode("utf-8").split("\0")
                if token
            ]
            refresh_git_paths(fx, captured + [sibling])

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        baseline_paths = {
            row["path"] for row in bundle.baseline["pathStateContentRows"]
        }
        self.assertIn(sibling, baseline_paths)

    def test_invalid_registry_cannot_add_a_baseline_exclusion(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            fx.closure_registry.write_bytes(
                fx.closure_registry.read_bytes() + b"noncanonical"
            )
            with mock.patch.object(audit, "build_workspace_manifest") as manifest_builder:
                self.assert_reason(
                    "closure-registry-malformed",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

        manifest_builder.assert_not_called()

    def test_audit_v2_summary_and_run_id_use_registry_journal_and_proof_hashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            history = audit.load_closure_history(
                root=bundle.root,
                registry=bundle.closure_registry,
            )

        summary = bundle.metrics["closureHistorySummary"]
        self.assertEqual("awx.structural-design-audit.v2", audit.AUDIT_SCHEMA)
        self.assertEqual("awx.dynamic-rag-quant-audit-metrics.v2", bundle.metrics["schemaVersion"])
        self.assertEqual("awx.structural-design-baseline.v1", bundle.baseline["schemaVersion"])
        self.assertEqual(
            {
                "schemaVersion",
                "waveRegistrySha256",
                "waveCount",
                "journalSetSha256",
                "proofSetSha256",
                "eventCount",
                "rejectedFalsePositiveRootCauseGroups",
                "verifiedClosedRootCauseGroups",
            },
            set(summary),
        )
        self.assertEqual(history.summary, summary)
        links = bundle.metrics["artifactLinks"]
        expected_run_id = sha256_hex(
            (
                f"{audit.AUDIT_SCHEMA}|{bundle.baseline['baselineId']}|"
                f"{links['baselinePayloadSha256']}|{links['ledgerPayloadSha256']}|"
                f"{links['metricsPayloadSha256']}|{summary['waveRegistrySha256']}|"
                f"{summary['journalSetSha256']}|{summary['proofSetSha256']}"
            ).encode("utf-8")
        )
        self.assertEqual(expected_run_id, bundle.metrics["auditRunId"])

    def test_audit_v2_overlay_preserves_five_rejected_and_five_verified_before_repair(self):
        live_registry_bytes = (
            ROOT / audit.CLOSURE_WAVE_REGISTRY_RELATIVE
        ).read_bytes()
        with mock.patch.object(
            audit,
            "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
            sha256_hex(live_registry_bytes),
        ), mock.patch.object(
            audit,
            "WAVE_THREE_APPROVED_SOURCE_DESIGN_SHA256",
            LIVE_WAVE_THREE_SOURCE_DESIGN_SHA256,
        ):
            registry = audit.load_closure_registry(
                root=ROOT,
                registry_path=REGISTRY_RELATIVE,
            )
            wave_histories = {
                descriptor.wave_id: audit.load_closure_wave(
                    root=ROOT,
                    descriptor=descriptor,
                )
                for descriptor in registry.waves
            }
            for wave_id in ("wave-0002", "wave-0003"):
                wave = wave_histories[wave_id]
                wave_histories[wave_id] = replace(
                    wave,
                    all_events=(),
                    active_events=(),
                    journal_payload_sha256=sha256_hex(b""),
                    proof_pairs=(),
                    target_admissions=(),
                )
            with mock.patch.object(
                audit,
                "load_closure_wave",
                side_effect=lambda *, root, descriptor: wave_histories[
                    descriptor.wave_id
                ],
            ):
                history = audit.load_closure_history(root=ROOT, registry=registry)
        public_rows = [
            json.loads(line)
            for line in (ROOT / "verification/structural-design-debt-ledger.jsonl")
            .read_text(encoding="utf-8")
            .splitlines()
        ]
        current_baseline_id = public_rows[0]["baselineId"]
        candidate = copy.deepcopy(next(
            row
            for row in history.predecessor_rows
            if row["rootCauseGroupId"]
            == "de40f8321c18138a01c12dd9d689212cc86c5ce8aac103e89fd4b6bb157d0167"
        ))
        candidate["baselineId"] = current_baseline_id
        current_rows = [
            ledger_core(row)
            for row in public_rows
            if row["status"] not in {"REJECTED_FALSE_POSITIVE", "VERIFIED_CLOSED"}
        ]
        current_rows.append(candidate)
        current_rows.sort(key=audit._ledger_sort_key)
        overlaid = audit.apply_closure_overlay(
            tuple(current_rows),
            history,
            baseline_id=current_baseline_id,
        )

        self.assertEqual(10, history.event_count)
        self.assertEqual(5, history.rejected_false_positive_root_cause_groups)
        self.assertEqual(5, history.verified_closed_root_cause_groups)
        self.assertEqual(
            5,
            sum(row["status"] == "REJECTED_FALSE_POSITIVE" for row in overlaid),
        )
        self.assertEqual(
            5,
            sum(row["status"] == "VERIFIED_CLOSED" for row in overlaid),
        )

    def test_audit_v2_overlay_matches_current_published_history(self):
        live_registry_bytes = (
            ROOT / audit.CLOSURE_WAVE_REGISTRY_RELATIVE
        ).read_bytes()
        with mock.patch.object(
            audit,
            "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
            sha256_hex(live_registry_bytes),
        ), mock.patch.object(
            audit,
            "WAVE_THREE_APPROVED_SOURCE_DESIGN_SHA256",
            LIVE_WAVE_THREE_SOURCE_DESIGN_SHA256,
        ):
            registry = audit.load_closure_registry(
                root=ROOT,
                registry_path=REGISTRY_RELATIVE,
            )
            history = audit.load_closure_history(root=ROOT, registry=registry)
        metrics = json.loads(
            (ROOT / "verification/dynamic-rag-quant-audit-metrics.json").read_text(
                encoding="utf-8"
            )
        )
        public_rows = [
            json.loads(line)
            for line in (ROOT / "verification/structural-design-debt-ledger.jsonl")
            .read_text(encoding="utf-8")
            .splitlines()
        ]
        public_core_rows = tuple(ledger_core(row) for row in public_rows)
        current_rows = tuple(
            row
            for row in public_core_rows
            if row["status"] not in {"REJECTED_FALSE_POSITIVE", "VERIFIED_CLOSED"}
        )
        overlaid = audit.apply_closure_overlay(
            current_rows,
            history,
            baseline_id=public_rows[0]["baselineId"],
        )

        summary = metrics["closureHistorySummary"]
        self.assertEqual(history.summary, summary)
        self.assertEqual(public_core_rows, overlaid)
        self.assertEqual(
            summary["rejectedFalsePositiveRootCauseGroups"],
            sum(row["status"] == "REJECTED_FALSE_POSITIVE" for row in overlaid),
        )
        self.assertEqual(
            summary["verifiedClosedRootCauseGroups"],
            sum(row["status"] == "VERIFIED_CLOSED" for row in overlaid),
        )

    def test_cli_requires_only_the_fixed_closure_wave_registry_argument(self):
        parser = audit._build_parser()
        destinations = {action.dest for action in parser._actions}
        self.assertIn("closure_wave_registry", destinations)
        self.assertNotIn("closure_journal", destinations)
        self.assertNotIn("closure_proof_root", destinations)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            expected = REGISTRY_RELATIVE.as_posix()
            self.assertEqual(
                root / REGISTRY_RELATIVE,
                audit._fixed_registry_argument_path(root, expected),
            )
            for invalid in (
                str((root / expected).resolve()),
                "../verification/structural-repair-waves/registry.json",
                "verification/structural-repair-waves/REGISTRY.json",
                "verification/structural-repair-waves/other.json",
            ):
                with self.subTest(invalid=invalid):
                    self.assert_reason(
                        "closure-registry-malformed",
                        audit._fixed_registry_argument_path,
                        root,
                        invalid,
                    )

    def test_gradle_uses_only_the_fixed_closure_wave_registry_contract(self):
        gradle = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn(
            'val structuralRepairWaveRegistry =\n'
            '    layout.projectDirectory.file("verification/structural-repair-waves/registry.json")',
            gradle,
        )
        self.assertEqual(1, gradle.count('"--closure-wave-registry"'))
        self.assertEqual(0, gradle.count('"--closure-journal"'))
        self.assertEqual(0, gradle.count('"--closure-proof-root"'))
        self.assertNotIn("structuralRepairClosureJournal", gradle)
        self.assertNotIn("structuralRepairClosureProofRoot", gradle)
        for relative in (
            "verification/structural-repair-closure-journal.jsonl",
            ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/intake/intake-summary.json",
            ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/intake/eligible-groups.jsonl",
            ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/intake/target-preimages.json",
            ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/repair-progress.jsonl",
            "verification/structural-repair-waves/wave-0002/journal.jsonl",
            ".superpowers/sdd/structural-repair-waves/wave-0002/intake/intake-summary.json",
            ".superpowers/sdd/structural-repair-waves/wave-0002/intake/eligible-groups.jsonl",
            ".superpowers/sdd/structural-repair-waves/wave-0002/intake/target-preimages.json",
            ".superpowers/sdd/structural-repair-waves/wave-0002/repair-progress.jsonl",
            "verification/structural-repair-waves/wave-0003/journal.jsonl",
            ".superpowers/sdd/structural-repair-waves/wave-0003/intake/intake-summary.json",
            ".superpowers/sdd/structural-repair-waves/wave-0003/intake/eligible-groups.jsonl",
            ".superpowers/sdd/structural-repair-waves/wave-0003/intake/target-preimages.json",
            ".superpowers/sdd/structural-repair-waves/wave-0003/intake/admission-decision.json",
            ".superpowers/sdd/structural-repair-waves/wave-0003/intake/duplicate-evidence.json",
            ".superpowers/sdd/structural-repair-waves/wave-0003/repair-progress.jsonl",
        ):
            self.assertIn(f'"{relative}"', gradle)
        for pattern in (
            "proofs/*/red-summary.json",
            "proofs/*/green-summary.json",
            "proofs/*/event-baseline.json",
            "proofs/*/gate-evidence.json",
            "proofs/*/red-hold-summary.json",
            "proofs/*/restored-control-summary.json",
        ):
            self.assertEqual(3, gradle.count(f'include("{pattern}")'))
        for pattern in (
            "proofs/*/repair-target-admission.json",
            "proofs/*/detector-red-summary.json",
            "proofs/*/compile-baseline.json",
            "proofs/*/owner-contract.json",
        ):
            self.assertEqual(1, gradle.count(f'include("{pattern}")'))
        self.assertNotIn("inputs.dir(structuralRepairWaveOneProofRoot)", gradle)
        self.assertNotIn("inputs.dir(structuralRepairWaveTwoProofRoot)", gradle)
        self.assertNotIn("inputs.dir(structuralRepairWaveThreeProofRoot)", gradle)
        wave_four_fixed_inputs = (
            "verification/structural-repair-waves/wave-0004/journal.jsonl",
            ".superpowers/sdd/structural-repair-waves/wave-0004/intake/intake-summary.json",
            ".superpowers/sdd/structural-repair-waves/wave-0004/intake/eligible-groups.jsonl",
            ".superpowers/sdd/structural-repair-waves/wave-0004/intake/target-preimages.json",
            ".superpowers/sdd/structural-repair-waves/wave-0004/intake/admission-decision.json",
            ".superpowers/sdd/structural-repair-waves/wave-0004/intake/duplicate-evidence.json",
            ".superpowers/sdd/structural-repair-waves/wave-0004/repair-progress.jsonl",
        )
        self.assertIn("val structuralRepairWaveFourJournal =", gradle)
        self.assertIn("val structuralRepairWaveFourFixedInputs = files(", gradle)
        declarations_start = gradle.index("val structuralRepairWaveFourJournal =")
        declarations_end = gradle.index("val appDupFqcnEvidence", declarations_start)
        wave_four_declarations = gradle[declarations_start:declarations_end]
        for relative in wave_four_fixed_inputs:
            self.assertEqual(1, wave_four_declarations.count(f'"{relative}"'))
        self.assertEqual(
            7,
            wave_four_declarations.count("layout.projectDirectory.file("),
        )
        self.assertNotIn("structuralRepairWaveFourProofRoot", gradle)
        self.assertNotIn("structuralRepairWaveFourProofInputs", gradle)
        self.assertNotIn("inputs.dir(structuralRepairWaveFour", gradle)
        self.assertNotIn('inputs.dir(layout.projectDirectory.dir(".superpowers"))', gradle)
        self.assertNotIn(
            "app/build/desktop-wave4-selfask/reports/dup-fqcn-evidence.json",
            wave_four_declarations,
        )

        audit_start = gradle.index('tasks.register<Exec>("dynamicRagQuantAudit")')
        audit_end = gradle.index("val harmonyScoreOutput", audit_start)
        audit_task = gradle[audit_start:audit_end]
        self.assertIn("inputs.file(structuralRepairWaveRegistry)", audit_task)
        self.assertIn("inputs.files(structuralRepairWaveOneFixedInputs)", audit_task)
        self.assertIn("inputs.files(structuralRepairWaveOneProofInputs)", audit_task)
        self.assertIn("inputs.file(structuralRepairWaveTwoJournal)", audit_task)
        self.assertIn("inputs.files(structuralRepairWaveTwoFixedInputs)", audit_task)
        self.assertIn("inputs.files(structuralRepairWaveTwoProofInputs)", audit_task)
        for registration in (
            "inputs.file(structuralRepairWaveThreeJournal)",
            "inputs.files(structuralRepairWaveThreeFixedInputs)",
            "inputs.files(structuralRepairWaveThreeProofInputs)",
            "inputs.file(structuralRepairWaveFourJournal)",
            "inputs.files(structuralRepairWaveFourFixedInputs)",
        ):
            self.assertIn(
                registration
                + "\n        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)",
                audit_task,
            )
        self.assertIn("org.gradle.api.tasks.PathSensitivity.RELATIVE", audit_task)
        self.assertLess(
            audit_task.index('"harmonyPressureReport"'),
            audit_task.index('"testTreeContaminationReport"'),
        )
        self.assertLess(
            audit_task.index('"testTreeContaminationReport"'),
            audit_task.index('":app:generateDupFqcnExcludes"'),
        )
        self.assertLess(
            audit_task.index("outputs.file(structuralAuditMetrics)"),
            audit_task.index("outputs.file(structuralAuditBaseline)"),
        )
        self.assertLess(
            audit_task.index("outputs.file(structuralAuditBaseline)"),
            audit_task.index("outputs.file(structuralAuditLedger)"),
        )

        source_start = gradle.index('tasks.register<Exec>("sourceHealthScorecard")')
        source_end = gradle.index(
            'tasks.register<Exec>("sourceHealthValidationLoop")',
            source_start,
        )
        source_task = gradle[source_start:source_end]
        self.assertIn('dependsOn("dynamicRagQuantAudit")', source_task)
        self.assertIn("inputs.file(structuralRepairWaveRegistry)", source_task)
        self.assertIn("inputs.files(structuralRepairWaveOneFixedInputs)", source_task)
        self.assertIn("inputs.files(structuralRepairWaveOneProofInputs)", source_task)
        self.assertIn("inputs.file(structuralRepairWaveTwoJournal)", source_task)
        self.assertIn("inputs.files(structuralRepairWaveTwoFixedInputs)", source_task)
        self.assertIn("inputs.files(structuralRepairWaveTwoProofInputs)", source_task)
        for registration in (
            "inputs.file(structuralRepairWaveThreeJournal)",
            "inputs.files(structuralRepairWaveThreeFixedInputs)",
            "inputs.files(structuralRepairWaveThreeProofInputs)",
            "inputs.file(structuralRepairWaveFourJournal)",
            "inputs.files(structuralRepairWaveFourFixedInputs)",
        ):
            self.assertIn(
                registration
                + "\n        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)",
                source_task,
            )

    def test_later_wave_failure_preserves_all_prior_public_output_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            wave_one_registry = audit.load_closure_registry(
                root=fx.root,
                registry_path=REGISTRY_RELATIVE,
            )
            wave_one = wave_one_registry.waves[0]
            wave_one_history = audit.load_closure_wave(
                root=fx.root,
                descriptor=wave_one,
            )
            wave_two = aggregate_wave_descriptor(fx.root, 2)
            two_wave_registry = audit.ClosureRegistry(
                path=wave_one_registry.path,
                payload_sha256=aggregate_fixture_hex("two-wave-registry"),
                waves=(wave_one, wave_two),
            )
            sentinels = {
                fx.baseline: b"prior-baseline",
                fx.ledger: b"prior-ledger",
                fx.metrics: b"prior-metrics",
            }
            for path, payload in sentinels.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(payload)
            load_order: list[str] = []

            def load_wave(*, root, descriptor):
                load_order.append(descriptor.wave_id)
                if descriptor.wave_id == "wave-0001":
                    return wave_one_history
                raise audit.AuditContractError("closure-intake-mismatch")

            with mock.patch.object(
                audit,
                "load_closure_registry",
                return_value=two_wave_registry,
            ), mock.patch.object(audit, "load_closure_wave", side_effect=load_wave):
                self.assert_reason(
                    "closure-intake-mismatch",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

            self.assertEqual(["wave-0001", "wave-0002"], load_order)
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_later_v2_wave_failure_preserves_all_prior_public_output_bytes(self):
        """Catches late v2 intake drift replacing any already-public artifact."""
        for relative in (
            audit.ADMISSION_DECISION_RELATIVE,
            audit.DUPLICATE_EVIDENCE_RELATIVE,
        ):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                registry_sha256, _ = install_empty_reviewed_duplicate_wave_three(fx)
                with mock.patch.object(
                    audit,
                    "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                    registry_sha256,
                ):
                    bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
                sentinels = {
                    fx.baseline: b"prior-v2-baseline",
                    fx.ledger: b"prior-v2-ledger",
                    fx.metrics: b"prior-v2-metrics",
                }
                for path, payload in sentinels.items():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(payload)
                admission_path = (
                    fx.root
                    / ".superpowers/sdd/structural-repair-waves/wave-0003"
                    / relative
                )
                real_write_closed_temp = audit._write_closed_temp
                staged_count = 0

                def tamper_after_staging(destination, payload):
                    nonlocal staged_count
                    staged = real_write_closed_temp(destination, payload)
                    staged_count += 1
                    if staged_count == 3:
                        admission_path.write_bytes(admission_path.read_bytes() + b" ")
                    return staged

                with mock.patch.object(
                    audit,
                    "FROZEN_CLOSURE_WAVE_REGISTRY_SHA256",
                    registry_sha256,
                ), mock.patch.object(
                    audit,
                    "_write_closed_temp",
                    side_effect=tamper_after_staging,
                ):
                    self.assert_reason(
                        "output-replace-failed",
                        audit.publish_bundle,
                        bundle,
                        make_outputs(fx),
                    )

                self.assertEqual(3, staged_count)
                for path, payload in sentinels.items():
                    self.assertEqual(payload, path.read_bytes())

    def test_metrics_remains_the_last_transactional_replacement_under_v2(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            outputs = make_outputs(fx)
            destinations = {fx.baseline, fx.ledger, fx.metrics}
            replacement_order: list[Path] = []
            real_replace = os.replace

            def recording_replace(source, destination):
                destination_path = Path(destination)
                if destination_path in destinations:
                    replacement_order.append(destination_path)
                return real_replace(source, destination)

            with mock.patch.object(audit.os, "replace", side_effect=recording_replace):
                audit.publish_bundle(bundle, outputs)

        self.assertEqual("awx.structural-design-audit.v2", audit.AUDIT_SCHEMA)
        self.assertEqual([fx.baseline, fx.ledger, fx.metrics], replacement_order)

    def test_empty_closure_journal_is_valid_and_bound_to_metrics(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            self.assertEqual(b"", fx.closure_journal.read_bytes())
            self.assertTrue(fx.closure_proof_root.is_dir())

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            history = audit.load_closure_history(
                root=fx.root,
                registry=bundle.closure_registry,
            )

        self.assertEqual(history.summary, bundle.metrics["closureHistorySummary"])
        self.assertEqual(2, history.wave_count)
        self.assertEqual(0, history.event_count)
        self.assertNotIn(
            "verification/structural-repair-closure-journal.jsonl",
            {row["path"] for row in bundle.baseline["pathStateContentRows"]},
        )

    def test_missing_closure_journal_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            fx.closure_journal.unlink()

            self.assert_reason(
                "closure-journal-missing",
                audit.build_audit,
                make_inputs(fx),
                FIXED_NOW,
            )

    def test_valid_rejected_event_preserves_identity_without_credit(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            predecessor, _ = prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")

            with patch_fixture_closure_registry(fx):
                bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        historical = next(
            row for row in bundle.ledger_rows if row["issueId"] == predecessor["issueId"]
        )
        for field in (
            "issueId",
            "rootCauseGroupId",
            "category",
            "severity",
            "activeSourceSet",
            "path",
            "symbol",
            "evidenceReason",
            "evidenceFingerprint",
            "numericEvidence",
            "suspectedBoundary",
            "proofCommand",
        ):
            self.assertEqual(predecessor[field], historical[field], field)
        self.assertEqual(bundle.baseline["baselineId"], historical["baselineId"])
        self.assertEqual("REJECTED_FALSE_POSITIVE", historical["status"])
        self.assertEqual("REJECTED_BY_CONTRACT", historical["fixEligibility"])
        self.assertEqual(
            [
                sha256_hex(
                    (
                        f"{predecessor['baselineId']}|{predecessor['issueId']}|"
                        f"{predecessor['evidenceFingerprint']}"
                    ).encode("utf-8")
                )
            ],
            historical["supersedes"],
        )
        self.assertEqual(1, bundle.metrics["closureHistorySummary"]["eventCount"])
        self.assertEqual(
            1,
            bundle.metrics["closureHistorySummary"][
                "rejectedFalsePositiveRootCauseGroups"
            ],
        )
        self.assertEqual(
            0,
            bundle.metrics["closureHistorySummary"]["verifiedClosedRootCauseGroups"],
        )
        self.assertEqual(0, bundle.metrics["ledgerSummary"]["verifiedClosedRootCauseGroups"])

    def test_valid_closed_event_requires_matching_patch_state_and_earns_one_credit(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            predecessor, _ = prepare_terminal_history(fx, "VERIFIED_CLOSED")
            event = read_closure_event(fx)

            with patch_fixture_closure_registry(fx):
                bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            progress_bytes = (fx.closure_proof_root / "repair-progress.jsonl").read_bytes()
            (fx.closure_proof_root / "repair-progress.jsonl").unlink()
            with patch_fixture_closure_registry(fx):
                self.assert_reason(
                    "closure-progress-mismatch",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )
            (fx.closure_proof_root / "repair-progress.jsonl").write_bytes(progress_bytes)

        historical = next(
            row for row in bundle.ledger_rows if row["issueId"] == predecessor["issueId"]
        )
        self.assertEqual("VERIFIED_CLOSED", historical["status"])
        self.assertEqual("ELIGIBLE", historical["fixEligibility"])
        self.assertNotEqual(
            bundle.metrics["closureHistorySummary"]["journalSetSha256"],
            sha256_hex(b""),
        )
        self.assertEqual(
            1,
            bundle.metrics["closureHistorySummary"]["verifiedClosedRootCauseGroups"],
        )
        self.assertEqual(1, bundle.metrics["ledgerSummary"]["verifiedClosedRootCauseGroups"])
        self.assertEqual(899, bundle.metrics["ledgerSummary"]["targetGap"])
        expected_proof_pairs = sorted(
            [
                ["wave-0001", event["redProofId"], event["redProofSha256"]],
                ["wave-0001", event["greenProofId"], event["greenProofSha256"]],
                [
                    "wave-0001",
                    event["eventBaselineProofId"],
                    event["eventBaselineProofSha256"],
                ],
                [
                    "wave-0001",
                    event["patchStateProofId"],
                    event["patchStateProofSha256"],
                ],
            ]
        )
        self.assertEqual(
            sha256_hex(canonical_bytes(expected_proof_pairs)),
            bundle.metrics["closureHistorySummary"]["proofSetSha256"],
        )

    def test_malformed_closure_journal_variants_fail_with_one_reason_code(self):
        malformed_payloads = {
            "missing-terminal-newline": canonical_bytes({}).rstrip(b"\n"),
            "invalid-utf8": b"\xff\n",
            "blank-interior-line": b"{}\n\n{}\n",
            "duplicate-field": (
                b'{"schemaVersion":"awx.structural-repair-closure-event.v1",'
                b'"schemaVersion":"awx.structural-repair-closure-event.v1"}\n'
            ),
            "wrong-field-set": canonical_bytes({}),
            "invalid-json": b"{\n",
        }
        for case, payload in malformed_payloads.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                fx.closure_journal.write_bytes(payload)
                self.assert_reason(
                    "closure-journal-malformed",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

    def test_closure_intake_identity_mismatch_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            descriptor = capture_closure_wave_descriptor(fx)
            summary_path = fx.closure_proof_root / "intake/intake-summary.json"
            summary = json.loads(summary_path.read_text(encoding="utf-8"))
            summary["sourceBaselineId"] = "f" * 64
            summary_path.write_bytes(canonical_bytes(summary))

            with patch_fixture_closure_registry(fx, descriptor):
                self.assert_reason(
                    "closure-intake-mismatch",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

        for field in ("sourceLedgerPayloadSha256", "sourceMetricsSemanticHash"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
                descriptor = capture_closure_wave_descriptor(fx)
                summary_path = fx.closure_proof_root / "intake/intake-summary.json"
                summary = json.loads(summary_path.read_text(encoding="utf-8"))
                original = summary[field]
                summary[field] = ("f" if original[0] != "f" else "e") + original[1:]
                summary_path.write_bytes(canonical_bytes(summary))
                with patch_fixture_closure_registry(fx, descriptor):
                    self.assert_reason(
                        "closure-intake-mismatch",
                        audit.build_audit,
                        make_inputs(fx),
                        FIXED_NOW,
                    )

    def test_closure_proof_path_hash_schema_and_privacy_fail_closed(self):
        with self.subTest(case="path-escape"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            event = read_closure_event(fx)
            event["redProofId"] = "../outside/red-summary.json"
            write_closure_event(fx, event)
            with patch_fixture_closure_registry(fx):
                self.assert_reason(
                    "closure-proof-invalid",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

        with self.subTest(case="hash"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            event = read_closure_event(fx)
            event["redProofSha256"] = "f" * 64
            write_closure_event(fx, event)
            with patch_fixture_closure_registry(fx):
                self.assert_reason(
                    "closure-proof-invalid",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

        with self.subTest(case="schema"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            event = read_closure_event(fx)
            rewrite_proof_and_event_hash(
                fx,
                event,
                "redProofId",
                "redProofSha256",
                lambda proof: proof.update({"schemaVersion": "wrong.v1"}),
            )
            with patch_fixture_closure_registry(fx):
                self.assert_reason(
                    "closure-proof-invalid",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

        with self.subTest(case="absolute-private-value"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            event = read_closure_event(fx)
            rewrite_proof_and_event_hash(
                fx,
                event,
                "redProofId",
                "redProofSha256",
                lambda proof: proof.update({"commandToken": "/private/sentinel"}),
            )
            with patch_fixture_closure_registry(fx):
                self.assert_reason(
                    "closure-proof-invalid",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

    def test_duplicate_active_event_and_missing_supersession_fail_with_event_conflict(self):
        with self.subTest(case="duplicate-active"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            first = read_closure_event(fx)
            second = copy.deepcopy(first)
            second["fingerprintDisposition"] = "DISAPPEARED"
            second["eventId"] = identity_hash(second, "eventId")
            fx.closure_journal.write_bytes(canonical_bytes(first) + canonical_bytes(second))
            with patch_fixture_closure_registry(fx):
                self.assert_reason(
                    "closure-event-conflict",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

        with self.subTest(case="missing-supersession"), tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            event = read_closure_event(fx)
            event["supersedesEventId"] = "f" * 64
            write_closure_event(fx, event)
            with patch_fixture_closure_registry(fx):
                self.assert_reason(
                    "closure-event-conflict",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

    def test_supersession_graph_rejects_cycle_cross_group_and_multiple_tips(self):
        def event(
            event_token: str,
            group_token: str,
            issue_token: str,
            supersedes: str | None,
        ) -> dict[str, object]:
            return {
                "eventId": event_token * 64,
                "rootCauseGroupId": group_token * 64,
                "sourceIssueId": issue_token * 64,
                "supersedesEventId": supersedes,
            }

        cycle_a = event("a", "1", "2", "b" * 64)
        cycle_b = event("b", "1", "2", "a" * 64)
        cross_parent = event("c", "3", "4", None)
        cross_child = event("d", "5", "6", "c" * 64)
        tip_a = event("e", "7", "8", None)
        tip_b = event("f", "7", "8", None)
        branch_root = event("1", "9", "a", None)
        branch_a = event("2", "9", "a", "1" * 64)
        branch_b = event("3", "9", "a", "1" * 64)

        cases = {
            "cycle": (cycle_a, cycle_b),
            "cross-group": (cross_parent, cross_child),
            "multiple-tips": (tip_a, tip_b),
            "branching": (branch_root, branch_a, branch_b),
        }
        for case, events in cases.items():
            with self.subTest(case=case):
                self.assert_reason(
                    "closure-event-conflict",
                    audit._select_active_closure_events,
                    events,
                )

    def test_valid_supersession_chain_selects_one_active_terminal_tip(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            predecessor, _ = prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            first = read_closure_event(fx)
            second = copy.deepcopy(first)
            second["fingerprintDisposition"] = "DISAPPEARED"
            second["supersedesEventId"] = first["eventId"]
            second["eventId"] = identity_hash(second, "eventId")
            fx.closure_journal.write_bytes(canonical_bytes(first) + canonical_bytes(second))

            with patch_fixture_closure_registry(fx):
                registry = audit.load_closure_registry(
                    root=fx.root,
                    registry_path=REGISTRY_RELATIVE,
                )
                history = audit.load_closure_history(
                    root=fx.root,
                    registry=registry,
                )
                bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        self.assertEqual(2, len(history.all_events))
        self.assertEqual((second["eventId"],), tuple(row["eventId"] for row in history.active_events))
        self.assertEqual(2, bundle.metrics["closureHistorySummary"]["eventCount"])
        self.assertEqual(
            1,
            bundle.metrics["closureHistorySummary"][
                "rejectedFalsePositiveRootCauseGroups"
            ],
        )
        self.assertEqual(
            1,
            sum(row["issueId"] == predecessor["issueId"] for row in bundle.ledger_rows),
        )

    def test_reparse_component_in_proof_path_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")
            real_has_reparse = audit._has_reparse_component

            def injected_reparse(root, candidate):
                if "proofs" in Path(candidate).parts:
                    return True
                return real_has_reparse(root, candidate)

            with mock.patch.object(
                audit,
                "_has_reparse_component",
                side_effect=injected_reparse,
            ), patch_fixture_closure_registry(fx):
                registry = audit.load_closure_registry(
                    root=fx.root,
                    registry_path=REGISTRY_RELATIVE,
                )
                self.assert_reason(
                    "closure-proof-invalid",
                    audit.load_closure_history,
                    root=fx.root,
                    registry=registry,
                )

    def test_active_predecessor_fingerprint_blocks_terminal_credit(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            predecessor, target_path = prepare_terminal_history(
                fx, "REJECTED_FALSE_POSITIVE"
            )
            write_harmony_input(
                fx,
                broad_rows=[
                    {
                        "file": target_path,
                        "lines": predecessor["numericEvidence"]["lineCount"],
                        "broadCatchBlocks": 1,
                        "broadCatchWithoutLocalBreadcrumbApprox": 1,
                    }
                ],
            )

            with patch_fixture_closure_registry(fx):
                self.assert_reason(
                    "closure-fingerprint-active",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

    def test_journal_mutation_after_build_preserves_prior_public_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            outputs = make_outputs(fx)
            prior = {
                fx.baseline: b"prior-baseline",
                fx.ledger: b"prior-ledger",
                fx.metrics: b"prior-metrics",
            }
            for path, payload in prior.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(payload)
            fx.closure_journal.write_bytes(canonical_bytes({}))

            self.assert_reason(
                "closure-journal-malformed",
                audit.publish_bundle,
                bundle,
                outputs,
            )
            for path, payload in prior.items():
                self.assertEqual(payload, path.read_bytes())

    def test_journal_mutation_during_staged_validation_rolls_back_every_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            outputs = make_outputs(fx)
            prior = {
                fx.baseline: b"prior-baseline",
                fx.ledger: b"prior-ledger",
                fx.metrics: b"prior-metrics",
            }
            for path, payload in prior.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(payload)
            real_write_closed_temp = audit._write_closed_temp
            write_count = 0

            def mutating_write_closed_temp(destination, payload):
                nonlocal write_count
                temp_path = real_write_closed_temp(destination, payload)
                write_count += 1
                if write_count == 3:
                    fx.closure_journal.write_bytes(canonical_bytes({}))
                return temp_path

            with mock.patch.object(
                audit,
                "_write_closed_temp",
                side_effect=mutating_write_closed_temp,
            ):
                self.assert_reason(
                    "output-replace-failed",
                    audit.publish_bundle,
                    bundle,
                    outputs,
                )
            for path, payload in prior.items():
                self.assertEqual(payload, path.read_bytes())

    def test_noncanonical_staged_bytes_fail_before_any_public_replacement(self):
        tamperers = {
            "trailing-space": lambda payload: payload + b" ",
            "duplicate-key": lambda payload: (
                b'{"schemaVersion":"shadow",' + payload[1:]
            ),
        }
        for case, tamper in tamperers.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
                outputs = make_outputs(fx)
                prior = {
                    fx.baseline: b"prior-baseline",
                    fx.ledger: b"prior-ledger",
                    fx.metrics: b"prior-metrics",
                }
                for path, payload in prior.items():
                    path.write_bytes(payload)
                real_write_closed_temp = audit._write_closed_temp
                tampered = False

                def tampering_write_closed_temp(destination, payload):
                    nonlocal tampered
                    temp_path = real_write_closed_temp(destination, payload)
                    if not tampered:
                        temp_path.write_bytes(tamper(temp_path.read_bytes()))
                        tampered = True
                    return temp_path

                with mock.patch.object(
                    audit,
                    "_write_closed_temp",
                    side_effect=tampering_write_closed_temp,
                ):
                    self.assert_reason(
                        "output-replace-failed",
                        audit.publish_bundle,
                        bundle,
                        outputs,
                    )
                for path, payload in prior.items():
                    self.assertEqual(payload, path.read_bytes())

    def test_journal_mutation_after_staged_validation_preserves_every_prior_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            outputs = make_outputs(fx)
            prior = {
                fx.baseline: b"prior-baseline",
                fx.ledger: b"prior-ledger",
                fx.metrics: b"prior-metrics",
            }
            for path, payload in prior.items():
                path.write_bytes(payload)
            real_replace = os.replace
            mutated = False

            def mutate_on_first_rollback(source, destination):
                nonlocal mutated
                result = real_replace(source, destination)
                if not mutated and Path(destination).name.endswith(".rollback"):
                    fx.closure_journal.write_bytes(canonical_bytes({}))
                    mutated = True
                return result

            with mock.patch.object(
                audit.os,
                "replace",
                side_effect=mutate_on_first_rollback,
            ):
                self.assert_reason(
                    "output-replace-failed",
                    audit.publish_bundle,
                    bundle,
                    outputs,
                )
            self.assertTrue(mutated)
            for path, payload in prior.items():
                self.assertEqual(payload, path.read_bytes())

    def test_closure_history_summary_tamper_is_rejected_after_relinking(self):
        mutations = {
            "journal-hash": (
                "artifact-link-mismatch",
                lambda summary: summary.update({"journalSetSha256": "f" * 64}),
            ),
            "proof-set-hash": (
                "artifact-link-mismatch",
                lambda summary: summary.update({"proofSetSha256": "f" * 64}),
            ),
            "event-count": (
                "semantic-nondeterminism",
                lambda summary: summary.update({"eventCount": 1}),
            ),
            "verified-count": (
                "semantic-nondeterminism",
                lambda summary: summary.update({"verifiedClosedRootCauseGroups": 1}),
            ),
        }
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            for case, (expected_reason, mutate_summary) in mutations.items():
                with self.subTest(case=case):
                    mutated = relink_after_metrics_mutation(
                        bundle,
                        lambda metrics, mutate=mutate_summary: mutate(
                            metrics["closureHistorySummary"]
                        ),
                    )
                    self.assert_reason(
                        expected_reason,
                        audit.validate_bundle,
                        mutated,
                    )

    def test_terminal_history_is_never_truncated_by_candidate_cap(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            predecessor, _ = prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")

            with patch_fixture_closure_registry(fx):
                bundle = audit.build_audit(make_inputs(fx, candidate_cap=1), FIXED_NOW)

        self.assertEqual(1, len(bundle.ledger_rows))
        self.assertEqual(predecessor["issueId"], bundle.ledger_rows[0]["issueId"])
        self.assertEqual("REJECTED_FALSE_POSITIVE", bundle.ledger_rows[0]["status"])

    def test_terminal_history_exceeding_candidate_cap_fails_closed(self):
        terminal_rows = (
            {"status": "REJECTED_FALSE_POSITIVE"},
            {"status": "VERIFIED_CLOSED"},
        )

        self.assert_reason(
            "semantic-nondeterminism",
            audit._cap_ledger_rows_preserving_terminals,
            terminal_rows,
            1,
        )

    def test_missing_active_root_fails_with_active_root_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp), include_app_root=False)

            self.assert_reason(
                "active-root-missing",
                audit.build_audit,
                make_inputs(fx),
                FIXED_NOW,
            )

    def test_inactive_java_root_is_never_scanned(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            inactive_path = "app/src/main/java/fixture/InactiveHuge.java"
            write_java(fx.root, inactive_path, 2501, java_body("InactiveHuge"))
            refresh_git_paths(fx)

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        self.assertEqual(2, bundle.metrics["activeJavaFileCount"])
        self.assertNotIn(inactive_path, bundle_bytes(bundle).decode("utf-8"))

    def test_absolute_traversal_uri_and_casefold_duplicate_paths_fail(self):
        invalid_captures = (
            b"/absolute/Foo.java\0",
            b"../escape/Foo.java\0",
            b"https://example.invalid/Foo.java\0",
            b"C:/escape/Foo.java\0",
        )
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            for raw in invalid_captures:
                with self.subTest(raw=raw):
                    self.assert_reason(
                        "path-invalid",
                        audit.build_workspace_manifest,
                        fx.root,
                        raw,
                        b"",
                    )

            self.assert_reason(
                "path-case-collision",
                audit.build_workspace_manifest,
                fx.root,
                b"main/java/fixture/Foo.java\0main/java/fixture/foo.java\0",
                b"",
            )

    def test_reparse_escape_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            real_has_reparse = audit._has_reparse_component
            target_key = os.path.normcase(
                str((fx.root / "main/java/fixture/RootMain.java").absolute())
            )

            def injected_source_reparse(root, candidate):
                if os.path.normcase(str(Path(candidate).absolute())) == target_key:
                    return True
                return real_has_reparse(root, candidate)

            with mock.patch.object(
                audit,
                "_has_reparse_component",
                side_effect=injected_source_reparse,
            ):
                self.assert_reason(
                    "reparse-path-risk",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

        with self.subTest(case="undecodable-active-source"):
            with tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                write_bytes(fx.root, "main/java/fixture/BadUtf8.java", b"\xff\xfe\x00")
                refresh_git_paths(fx)
                self.assert_reason(
                    "source-decode-failed",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

    def test_porcelain_v2_z_parses_clean_modified_deleted_untracked_and_rename(self):
        zero = "0" * 40
        status = (
            f"1 .M N... 100644 100644 100644 {zero} {zero} main/java/fixture/Modified.java\0"
            f"1 D. N... 100644 000000 000000 {zero} {zero} main/java/fixture/Deleted.java\0"
            f"2 R. N... 100644 100644 100644 {zero} {zero} R100 main/java/fixture/Renamed.java\0"
            "main/java/fixture/Original.java\0"
            f"u UU N... 100644 100644 100644 100644 {zero} {zero} {zero} main/java/fixture/Conflict.java\0"
            "? main/java/fixture/Untracked.java\0"
        ).encode("utf-8")
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            for name in ("Clean", "Modified", "Renamed", "Conflict", "Untracked"):
                write_java(
                    fx.root,
                    f"main/java/fixture/{name}.java",
                    3,
                    java_body(name),
                )
            paths = [
                f"main/java/fixture/{name}.java"
                for name in ("Clean", "Modified", "Renamed", "Conflict", "Untracked")
            ]
            raw_paths = ("\0".join(paths) + "\0").encode("utf-8")

            records = audit.parse_porcelain_v2_z(status)
            manifest = audit.build_workspace_manifest(fx.root, raw_paths, status)

        records_by_path = {row["path"]: row for row in records}
        self.assertEqual("modified", records_by_path["main/java/fixture/Modified.java"]["gitState"])
        self.assertEqual("deleted", records_by_path["main/java/fixture/Deleted.java"]["gitState"])
        self.assertEqual("renamed", records_by_path["main/java/fixture/Renamed.java"]["gitState"])
        self.assertEqual(
            "main/java/fixture/Original.java",
            records_by_path["main/java/fixture/Renamed.java"]["originalPath"],
        )
        self.assertEqual("unmerged", records_by_path["main/java/fixture/Conflict.java"]["gitState"])
        self.assertEqual("untracked", records_by_path["main/java/fixture/Untracked.java"]["gitState"])

        states_by_path = {row["path"]: row["gitState"] for row in manifest}
        self.assertEqual("clean", states_by_path["main/java/fixture/Clean.java"])
        self.assertEqual("modified", states_by_path["main/java/fixture/Modified.java"])
        self.assertEqual("deleted", states_by_path["main/java/fixture/Deleted.java"])
        self.assertEqual("renamed", states_by_path["main/java/fixture/Renamed.java"])
        self.assertEqual("deleted", states_by_path["main/java/fixture/Original.java"])
        self.assertEqual("unmerged", states_by_path["main/java/fixture/Conflict.java"])
        self.assertEqual("untracked", states_by_path["main/java/fixture/Untracked.java"])

    def test_clean_skip_worktree_fallback_preserves_logical_blob_content(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            missing_path = "main/java/fixture/SkipWorktreeOnly.java"
            raw_paths = (missing_path + "\0").encode("utf-8")
            logical_blob = b"package fixture;\npublic class SkipWorktreeOnly {}\n"
            fallback = {
                missing_path: {
                    "sizeBytes": len(logical_blob),
                    "contentSha256": sha256_hex(logical_blob),
                }
            }

            self.assert_reason(
                "git-capture-malformed",
                audit.build_workspace_manifest,
                fx.root,
                raw_paths,
                b"",
            )
            manifest = audit.build_workspace_manifest(
                fx.root,
                raw_paths,
                b"",
                fallback,
            )

        self.assertEqual(
            (
                {
                    "path": missing_path,
                    "gitState": "clean",
                    "sizeBytes": len(logical_blob),
                    "contentSha256": sha256_hex(logical_blob),
                },
            ),
            manifest,
        )

    def test_build_audit_reads_declared_skip_worktree_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            missing_path = "main/java/fixture/SkipWorktreeOnly.java"
            logical_blob = b"package fixture;\npublic class SkipWorktreeOnly {}\n"
            paths = refresh_git_paths(fx)
            refresh_git_paths(fx, [*paths, missing_path])
            fallback_path = fx.git_paths.with_name("git-skip-worktree.json")
            write_json(
                fallback_path.parent,
                fallback_path.name,
                {
                    "schemaVersion": "awx.structural-audit-git-skip-worktree.v1",
                    "rows": [
                        {
                            "path": missing_path,
                            "sizeBytes": len(logical_blob),
                            "contentSha256": sha256_hex(logical_blob),
                        }
                    ],
                },
            )
            inputs = replace(
                make_inputs(fx),
                git_skip_worktree_input=fallback_path,
            )

            bundle = audit.build_audit(inputs, FIXED_NOW)

        baseline_by_path = {
            row["path"]: row for row in bundle.baseline["pathStateContentRows"]
        }
        self.assertEqual(
            {
                "path": missing_path,
                "gitState": "clean",
                "sizeBytes": len(logical_blob),
                "contentSha256": sha256_hex(logical_blob),
            },
            baseline_by_path[missing_path],
        )

    def test_unreadable_untracked_zero_byte_regular_file_is_counted_but_nonzero_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            unreadable_path = "ONNX"
            target = write_bytes(fx.root, unreadable_path, b"")
            raw_paths = (unreadable_path + "\0").encode("utf-8")
            raw_status = ("? " + unreadable_path + "\0").encode("utf-8")
            original_resolve = Path.resolve
            target_key = os.path.normcase(str(target.absolute()))

            def deny_target_resolve(path: Path, *args, **kwargs):
                if os.path.normcase(str(path.absolute())) == target_key:
                    raise PermissionError("simulated unreadable regular file")
                return original_resolve(path, *args, **kwargs)

            with mock.patch.object(Path, "resolve", new=deny_target_resolve):
                manifest = audit.build_workspace_manifest(
                    fx.root,
                    raw_paths,
                    raw_status,
                )

                target.write_bytes(b"not-empty")
                self.assert_reason(
                    "git-capture-malformed",
                    audit.build_workspace_manifest,
                    fx.root,
                    raw_paths,
                    raw_status,
                )

        self.assertEqual(
            (
                {
                    "path": unreadable_path,
                    "gitState": "untracked",
                    "sizeBytes": 0,
                    "contentSha256": sha256_hex(b""),
                },
            ),
            manifest,
        )

    def test_unreadable_untracked_zero_byte_read_failure_is_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            unreadable_path = "ONNX"
            target = write_bytes(fx.root, unreadable_path, b"")
            target_key = os.path.normcase(str(target.absolute()))
            original_read_bytes = Path.read_bytes

            def deny_target_read(path: Path):
                if os.path.normcase(str(path.absolute())) == target_key:
                    raise PermissionError("simulated unreadable regular file")
                return original_read_bytes(path)

            with mock.patch.object(Path, "read_bytes", new=deny_target_read):
                manifest = audit.build_workspace_manifest(
                    fx.root,
                    (unreadable_path + "\0").encode("utf-8"),
                    ("? " + unreadable_path + "\0").encode("utf-8"),
                )

        self.assertEqual(0, manifest[0]["sizeBytes"])
        self.assertEqual(sha256_hex(b""), manifest[0]["contentSha256"])

    def test_unreadable_modified_zero_byte_file_remains_fail_closed(self):
        zero = "0" * 40
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            unreadable_path = "main/java/fixture/Modified.java"
            target = write_bytes(fx.root, unreadable_path, b"")
            target_key = os.path.normcase(str(target.absolute()))
            original_resolve = Path.resolve

            def deny_target_resolve(path: Path, *args, **kwargs):
                if os.path.normcase(str(path.absolute())) == target_key:
                    raise PermissionError("simulated unreadable modified file")
                return original_resolve(path, *args, **kwargs)

            status = (
                f"1 .M N... 100644 100644 100644 {zero} {zero} {unreadable_path}\0"
            ).encode("utf-8")
            with mock.patch.object(Path, "resolve", new=deny_target_resolve):
                self.assert_reason(
                    "git-capture-malformed",
                    audit.build_workspace_manifest,
                    fx.root,
                    (unreadable_path + "\0").encode("utf-8"),
                    status,
                )

    def test_baseline_hash_is_stable_across_input_enumeration_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            paths = refresh_git_paths(fx)
            first = audit.build_audit(make_inputs(fx), FIXED_NOW)
            fx.git_paths.write_bytes(("\0".join(reversed(paths)) + "\0").encode("utf-8"))

            second = audit.build_audit(make_inputs(fx), FIXED_NOW)

        self.assertEqual(first.baseline["pathStateContentRows"], second.baseline["pathStateContentRows"])
        self.assertEqual(first.baseline["baselineId"], second.baseline["baselineId"])
        self.assertEqual(
            first.baseline["artifactLinks"]["baselinePayloadSha256"],
            second.baseline["artifactLinks"]["baselinePayloadSha256"],
        )

    def test_generated_artifact_policy_prevents_second_run_baseline_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            first = audit.build_audit(make_inputs(fx), FIXED_NOW)
            audit.publish_bundle(first, make_outputs(fx))
            for output in (fx.metrics, fx.baseline, fx.ledger):
                output.with_name(output.name + ".tmp-fixture").write_bytes(b"temporary")
                output.with_name(output.name + ".rollback").write_bytes(b"rollback")
            refresh_git_paths(fx)

            second = audit.build_audit(make_inputs(fx), FIXED_NOW)

        self.assertEqual(first.baseline["baselineId"], second.baseline["baselineId"])
        self.assertEqual(first.baseline["pathStateContentRows"], second.baseline["pathStateContentRows"])

    def test_generated_cache_and_source_edit_lease_do_not_enter_baseline(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            cache_path = "scripts/__pycache__/module.cpython-311.pyc"
            loose_cache_path = "scripts/module.pyc"
            lease_path = "__patch_drop__/source-edit-locks/topic.lock/lease.json"
            retained_path = "scripts/retained_input.py"
            write_bytes(fx.root, cache_path, b"bytecode-cache")
            write_bytes(fx.root, loose_cache_path, b"loose-bytecode-cache")
            write_bytes(fx.root, lease_path, b'{"owner":"ephemeral"}\n')
            write_bytes(fx.root, retained_path, b"print('retained')\n")
            refresh_git_paths(fx)

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        baseline_paths = {
            row["path"] for row in bundle.baseline["pathStateContentRows"]
        }
        self.assertNotIn(cache_path, baseline_paths)
        self.assertNotIn(loose_cache_path, baseline_paths)
        self.assertNotIn(lease_path, baseline_paths)
        self.assertIn(retained_path, baseline_paths)

    def test_file_size_threshold_is_strictly_greater_than_2000(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            exact_path = "main/java/fixture/Exact2000.java"
            over_path = "main/java/fixture/Over2000.java"
            high_path = "main/java/fixture/Over4000.java"
            exact = write_java(fx.root, exact_path, 2000, java_body("Exact2000"))
            over = write_java(fx.root, over_path, 2001, java_body("Over2000"))
            high = write_java(fx.root, high_path, 4001, java_body("Over4000"))
            self.assertEqual(2000, len(exact.read_text(encoding="utf-8").splitlines()))
            self.assertEqual(2001, len(over.read_text(encoding="utf-8").splitlines()))
            self.assertEqual(4001, len(high.read_text(encoding="utf-8").splitlines()))
            refresh_git_paths(fx)

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        size_rows = {
            row["path"]: row
            for row in bundle.ledger_rows
            if row["category"] == "FILE_SIZE_CONCENTRATION"
        }
        self.assertNotIn(exact_path, size_rows)
        self.assertEqual("MEDIUM", size_rows[over_path]["severity"])
        self.assertEqual("HIGH", size_rows[high_path]["severity"])
        for row in size_rows.values():
            self.assertEqual("REVIEW_REQUIRED", row["fixEligibility"])
            self.assertEqual("HOLD", row["status"])
            self.assertEqual("active-java-lines-over-2000", row["evidenceReason"])

    def test_cross_subsystem_threshold_is_strictly_greater_than_1000(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            exact_path = "main/java/fixture/CrossExact1000.java"
            over_path = "main/java/fixture/CrossOver1000.java"
            high_path = "main/java/fixture/CrossFourSubsystems.java"
            write_java(fx.root, exact_path, 1000, java_body("CrossExact1000"))
            write_java(fx.root, over_path, 1001, java_body("CrossOver1000"))
            write_java(fx.root, high_path, 1001, java_body("CrossFourSubsystems"))
            refresh_git_paths(fx)
            write_harmony_input(
                fx,
                runtime_rows=[
                    {
                        "file": exact_path,
                        "lines": 1000,
                        "subsystems": ["CFVM", "Overdrive"],
                        "hitScore": 2,
                    },
                    {
                        "file": over_path,
                        "lines": 1001,
                        "subsystems": ["CFVM", "Overdrive"],
                        "hitScore": 2,
                    },
                    {
                        "file": high_path,
                        "lines": 1001,
                        "subsystems": ["CFVM", "ExtremeZ", "HYPERNOVA", "Overdrive"],
                        "hitScore": 4,
                    },
                ],
            )

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        cross_rows = {
            row["path"]: row
            for row in bundle.ledger_rows
            if row["category"] == "CROSS_SUBSYSTEM_CONCENTRATION"
        }
        self.assertNotIn(exact_path, cross_rows)
        self.assertEqual("MEDIUM", cross_rows[over_path]["severity"])
        self.assertEqual("HIGH", cross_rows[high_path]["severity"])
        for row in cross_rows.values():
            self.assertEqual("REVIEW_REQUIRED", row["fixEligibility"])
            self.assertEqual("HOLD", row["status"])
            self.assertEqual("runtime-cross-subsystem-lines-over-1000", row["evidenceReason"])

    def test_broad_catch_rows_use_harmony_evidence_without_rescanning(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            path = "main/java/fixture/EvidenceOnlySignal.java"
            medium_path = "main/java/fixture/EvidenceOnlySignalMedium.java"
            java = write_java(fx.root, path, 3, java_body("EvidenceOnlySignal"))
            write_java(fx.root, medium_path, 3, java_body("EvidenceOnlySignalMedium"))
            self.assertNotIn("catch", java.read_text(encoding="utf-8").casefold())
            refresh_git_paths(fx)
            write_harmony_input(
                fx,
                broad_rows=[
                    {
                        "file": path,
                        "lines": 3,
                        "broadCatchBlocks": 3,
                        "broadCatchWithoutLocalBreadcrumbApprox": 3,
                    },
                    {
                        "file": medium_path,
                        "lines": 3,
                        "broadCatchBlocks": 1,
                        "broadCatchWithoutLocalBreadcrumbApprox": 1,
                    },
                ],
            )

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        rows = {
            row["path"]: row
            for row in bundle.ledger_rows
            if row["category"] == "BROAD_CATCH_NO_BREADCRUMB"
        }
        self.assertEqual(2, len(rows))
        self.assertEqual("HIGH", rows[path]["severity"])
        self.assertEqual("MEDIUM", rows[medium_path]["severity"])
        for row in rows.values():
            self.assertEqual("ELIGIBLE", row["fixEligibility"])
            self.assertEqual("OPEN", row["status"])
            self.assertEqual("broad-catch-without-local-breadcrumb", row["evidenceReason"])
        self.assertEqual(3, rows[path]["numericEvidence"]["broadCatchCount"])

    def test_duplicate_source_and_packaged_active_semantics_are_distinct(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            rows = [duplicate_row(1, "PACKAGED_ACTIVE"), duplicate_row(2, "GENERATED_EXCLUDE")]
            for row in rows:
                class_name = str(row["fqcn"]).rsplit(".", 1)[-1]
                write_java(fx.root, str(row["rootPath"]), 3, java_body(class_name))
                write_java(fx.root, str(row["appPath"]), 3, java_body(class_name))
            refresh_git_paths(fx)
            write_duplicate_input(fx, rows=rows)

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        source_rows = [
            row for row in bundle.ledger_rows if row["category"] == "DUPLICATE_FQCN_SOURCE_COLLISION"
        ]
        packaged_rows = [
            row for row in bundle.ledger_rows if row["category"] == "DUPLICATE_FQCN_PACKAGED_ACTIVE"
        ]
        self.assertEqual(2, len(source_rows))
        self.assertTrue(
            all(
                row["fixEligibility"] == "REVIEW_REQUIRED"
                and row["status"] == "HOLD"
                and row["severity"] == "HIGH"
                for row in source_rows
            )
        )
        self.assertEqual(1, len(packaged_rows))
        self.assertEqual("ELIGIBLE", packaged_rows[0]["fixEligibility"])
        self.assertEqual("OPEN", packaged_rows[0]["status"])
        self.assertEqual("CRITICAL", packaged_rows[0]["severity"])
        self.assertEqual(1, bundle.metrics["duplicateFqcnGeneratedExcludeCount"])
        self.assertEqual(1, bundle.metrics["duplicateFqcnPackagedActiveCount"])
        self.assertEqual(1, bundle.metrics["duplicateFqcnActiveCount"])

    def test_size_and_cross_subsystem_manifestations_share_one_owner_group(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            path = "main/java/fixture/SharedStructuralOwner.java"
            write_java(fx.root, path, 2501, java_body("SharedStructuralOwner"))
            refresh_git_paths(fx)
            write_harmony_input(
                fx,
                runtime_rows=[
                    {
                        "file": path,
                        "lines": 2501,
                        "subsystems": ["CFVM", "Overdrive"],
                        "hitScore": 2,
                    }
                ],
            )

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        rows = [
            row
            for row in bundle.ledger_rows
            if row["path"] == path
            and row["category"] in {"FILE_SIZE_CONCENTRATION", "CROSS_SUBSYSTEM_CONCENTRATION"}
        ]
        self.assertEqual(2, len(rows))
        self.assertEqual(2, len({row["issueId"] for row in rows}))
        self.assertEqual(1, len({row["rootCauseGroupId"] for row in rows}))

    def test_multiple_duplicate_manifestations_share_one_fqcn_owner_group(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            duplicate = duplicate_row(7, "PACKAGED_ACTIVE")
            class_name = str(duplicate["fqcn"]).rsplit(".", 1)[-1]
            write_java(fx.root, str(duplicate["rootPath"]), 3, java_body(class_name))
            write_java(fx.root, str(duplicate["appPath"]), 3, java_body(class_name))
            refresh_git_paths(fx)
            write_duplicate_input(fx, rows=[duplicate])

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        rows = [
            row
            for row in bundle.ledger_rows
            if row["category"]
            in {"DUPLICATE_FQCN_SOURCE_COLLISION", "DUPLICATE_FQCN_PACKAGED_ACTIVE"}
        ]
        self.assertEqual(2, len(rows))
        self.assertEqual(2, len({row["issueId"] for row in rows}))
        self.assertEqual(1, len({row["rootCauseGroupId"] for row in rows}))

    def test_only_eligible_open_groups_count_toward_repair_selection(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            broad_path = "main/java/fixture/EligibleCatch.java"
            review_path = "main/java/fixture/ReviewLarge.java"
            write_java(fx.root, broad_path, 3, java_body("EligibleCatch"))
            write_java(fx.root, review_path, 2001, java_body("ReviewLarge"))
            duplicate = duplicate_row(42, "PACKAGED_ACTIVE")
            duplicate_class = str(duplicate["fqcn"]).rsplit(".", 1)[-1]
            write_java(fx.root, str(duplicate["rootPath"]), 3, java_body(duplicate_class))
            write_java(fx.root, str(duplicate["appPath"]), 3, java_body(duplicate_class))
            refresh_git_paths(fx)
            write_harmony_input(
                fx,
                runtime_rows=[
                    {
                        "file": review_path,
                        "lines": 2001,
                        "subsystems": ["CFVM", "Overdrive"],
                        "hitScore": 2,
                    }
                ],
                broad_rows=[
                    {
                        "file": broad_path,
                        "lines": 3,
                        "broadCatchBlocks": 1,
                        "broadCatchWithoutLocalBreadcrumbApprox": 1,
                    }
                ],
            )
            write_duplicate_input(fx, rows=[duplicate])

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        summary = bundle.metrics["ledgerSummary"]
        expected_categories = {
            "BROAD_CATCH_NO_BREADCRUMB",
            "CROSS_SUBSYSTEM_CONCENTRATION",
            "DUPLICATE_FQCN_PACKAGED_ACTIVE",
            "DUPLICATE_FQCN_SOURCE_COLLISION",
            "FILE_SIZE_CONCENTRATION",
        }
        self.assertEqual(frozenset(expected_categories), audit.CATEGORIES)
        self.assertEqual(expected_categories, {row["category"] for row in bundle.ledger_rows})
        self.assertEqual(2, summary["eligibleRows"])
        self.assertEqual(2, summary["eligibleRootCauseGroups"])
        self.assertEqual(3, summary["reviewOnlyRows"])
        self.assertEqual(0, summary["verifiedClosedRootCauseGroups"])

    def test_review_required_rows_do_not_count_as_verified_or_eligible_closures(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            path = "main/java/fixture/ReviewOnly.java"
            write_java(fx.root, path, 2001, java_body("ReviewOnly"))
            refresh_git_paths(fx)
            write_harmony_input(
                fx,
                runtime_rows=[
                    {
                        "file": path,
                        "lines": 2001,
                        "subsystems": ["CFVM", "Overdrive"],
                        "hitScore": 2,
                    }
                ],
            )

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        summary = bundle.metrics["ledgerSummary"]
        self.assertEqual(2, summary["reviewOnlyRows"])
        self.assertEqual(0, summary["eligibleRows"])
        self.assertEqual(0, summary["eligibleRootCauseGroups"])
        self.assertEqual(0, summary["verifiedClosedRootCauseGroups"])
        self.assertEqual(900, summary["targetGap"])

    def test_repeated_builds_keep_ids_order_payload_hashes_and_audit_run_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))

            first = audit.build_audit(make_inputs(fx), FIXED_NOW)
            second = audit.build_audit(make_inputs(fx), FIXED_NOW)

        self.assertEqual(first, second)
        self.assertEqual(first.baseline["baselineId"], second.baseline["baselineId"])
        self.assertEqual(first.metrics["auditRunId"], second.metrics["auditRunId"])
        self.assertEqual(first.metrics["artifactLinks"], second.metrics["artifactLinks"])
        self.assertEqual(
            [row["issueId"] for row in first.ledger_rows],
            [row["issueId"] for row in second.ledger_rows],
        )

    def test_timestamp_only_rerun_with_terminal_history_keeps_semantic_chain(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            prepare_terminal_history(fx, "REJECTED_FALSE_POSITIVE")

            with patch_fixture_closure_registry(fx):
                first = audit.build_audit(make_inputs(fx), FIXED_NOW)
                second = audit.build_audit(
                    make_inputs(fx),
                    FIXED_NOW + timedelta(seconds=1),
                )

        self.assertNotEqual(first.metrics["generatedAt"], second.metrics["generatedAt"])
        self.assertEqual(without_generated_at(first), without_generated_at(second))
        self.assertEqual(first.metrics["auditRunId"], second.metrics["auditRunId"])
        self.assertEqual(first.metrics["artifactLinks"], second.metrics["artifactLinks"])
        self.assertEqual(
            first.metrics["closureHistorySummary"],
            second.metrics["closureHistorySummary"],
        )
        self.assertEqual(
            [row["issueId"] for row in first.ledger_rows],
            [row["issueId"] for row in second.ledger_rows],
        )

    def test_generated_at_changes_only_nondeterministic_envelope_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))

            first = audit.build_audit(make_inputs(fx), FIXED_NOW)
            second = audit.build_audit(make_inputs(fx), FIXED_NOW + timedelta(seconds=1))

        self.assertNotEqual(first.metrics["generatedAt"], second.metrics["generatedAt"])
        self.assertNotEqual(first.baseline["generatedAt"], second.baseline["generatedAt"])
        self.assertEqual(without_generated_at(first), without_generated_at(second))
        self.assertEqual(first.metrics["auditRunId"], second.metrics["auditRunId"])
        self.assertEqual(first.metrics["artifactLinks"], second.metrics["artifactLinks"])

    def test_duplicate_issue_id_semantic_key_or_group_owner_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            broad_rows = []
            for index in range(2):
                path = f"main/java/fixture/Identity{index}.java"
                write_java(fx.root, path, 3, java_body(f"Identity{index}"))
                broad_rows.append(
                    {
                        "file": path,
                        "lines": 3,
                        "broadCatchBlocks": 1,
                        "broadCatchWithoutLocalBreadcrumbApprox": 1,
                    }
                )
            refresh_git_paths(fx)
            write_harmony_input(fx, broad_rows=broad_rows)
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

            duplicate_id_rows = copy.deepcopy(list(bundle.ledger_rows))
            duplicate_id_rows[1]["issueId"] = duplicate_id_rows[0]["issueId"]
            duplicate_id_bundle = replace(bundle, ledger_rows=tuple(duplicate_id_rows))

            duplicate_semantic_rows = copy.deepcopy(list(bundle.ledger_rows))
            duplicate_semantic = copy.deepcopy(duplicate_semantic_rows[0])
            duplicate_semantic["issueId"] = "f" * 64
            duplicate_semantic_rows.append(duplicate_semantic)
            duplicate_semantic_bundle = replace(bundle, ledger_rows=tuple(duplicate_semantic_rows))

            group_conflict_rows = copy.deepcopy(list(bundle.ledger_rows))
            group_conflict_rows[1]["rootCauseGroupId"] = group_conflict_rows[0]["rootCauseGroupId"]
            group_conflict_bundle = replace(bundle, ledger_rows=tuple(group_conflict_rows))

            self.assert_reason("issue-identity-conflict", audit.validate_bundle, duplicate_id_bundle)
            self.assert_reason(
                "issue-identity-conflict", audit.validate_bundle, duplicate_semantic_bundle
            )
            self.assert_reason("group-owner-conflict", audit.validate_bundle, group_conflict_bundle)

    def test_cap_1100_counts_full_universe_and_reports_overflow(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            broad_rows = []
            for index in range(1100):
                class_name = f"Capped{index:04d}"
                path = f"main/java/fixture/{class_name}.java"
                write_java(fx.root, path, 3, java_body(class_name))
                broad_rows.append(
                    {
                        "file": path,
                        "lines": 3,
                        "broadCatchBlocks": 1,
                        "broadCatchWithoutLocalBreadcrumbApprox": 1,
                    }
                )
            review_path = "main/java/fixture/ReviewAtSameSeverity.java"
            write_java(fx.root, review_path, 2001, java_body("ReviewAtSameSeverity"))
            refresh_git_paths(fx)
            write_harmony_input(fx, broad_rows=broad_rows)

            bundle = audit.build_audit(make_inputs(fx, candidate_cap=1100), FIXED_NOW)

        summary = bundle.metrics["ledgerSummary"]
        self.assertEqual(1101, summary["totalRows"])
        self.assertEqual(1101, summary["totalRootCauseGroups"])
        self.assertEqual(1100, summary["emittedRows"])
        self.assertEqual(1100, summary["emittedRootCauseGroups"])
        self.assertEqual(1, summary["overflowRows"])
        self.assertTrue(summary["truncated"])
        self.assertEqual(1100, summary["perCategoryTotals"]["BROAD_CATCH_NO_BREADCRUMB"])
        self.assertEqual(1, summary["perCategoryTotals"]["FILE_SIZE_CONCENTRATION"])
        self.assertEqual(1100, len(bundle.ledger_rows))
        self.assertEqual(
            {"BROAD_CATCH_NO_BREADCRUMB"},
            {row["category"] for row in bundle.ledger_rows},
        )
        self.assertNotIn(review_path, {row["path"] for row in bundle.ledger_rows})

    def test_fewer_than_900_eligible_groups_reports_honest_target_gap(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            broad_rows = []
            for index in range(899):
                class_name = f"Gap{index:04d}"
                path = f"main/java/fixture/{class_name}.java"
                write_java(fx.root, path, 3, java_body(class_name))
                broad_rows.append(
                    {
                        "file": path,
                        "lines": 3,
                        "broadCatchBlocks": 1,
                        "broadCatchWithoutLocalBreadcrumbApprox": 1,
                    }
                )
            refresh_git_paths(fx)
            write_harmony_input(fx, broad_rows=broad_rows)

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        summary = bundle.metrics["ledgerSummary"]
        self.assertEqual(899, summary["eligibleRows"])
        self.assertEqual(899, summary["eligibleRootCauseGroups"])
        self.assertEqual(1, summary["targetGap"])
        self.assertEqual(0, summary["verifiedClosedRootCauseGroups"])
        self.assertFalse(summary["truncated"])

    def test_stale_malformed_or_incoherent_required_input_is_rejected(self):
        with self.subTest(case="stale"):
            with tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                write_harmony_input(
                    fx,
                    generated_at=FIXED_NOW - timedelta(hours=24, seconds=1),
                )
                self.assert_reason(
                    "required-input-stale",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

        with self.subTest(case="malformed"):
            with tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                fx.harmony.write_bytes(b"{")
                self.assert_reason(
                    "required-input-malformed",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

        with self.subTest(case="incoherent-duplicate-count"):
            with tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                duplicate = duplicate_row(1, "GENERATED_EXCLUDE")
                class_name = str(duplicate["fqcn"]).rsplit(".", 1)[-1]
                write_java(fx.root, str(duplicate["rootPath"]), 3, java_body(class_name))
                write_java(fx.root, str(duplicate["appPath"]), 3, java_body(class_name))
                refresh_git_paths(fx)
                write_duplicate_input(
                    fx,
                    rows=[duplicate],
                    count_override={"duplicateFqcnSourceCollisionCount": 2},
                )
                self.assert_reason(
                    "duplicate-count-inconsistent",
                    audit.build_audit,
                    make_inputs(fx),
                    FIXED_NOW,
                )

    def test_artifacts_contain_no_absolute_path_url_snippet_prompt_response_or_secret(self):
        snippet = "SOURCE_BODY_SENTINEL_7d5c"
        url = "https://private.invalid/sentinel"
        prompt = "PRIVATE_PROMPT_SENTINEL_54ce"
        response = "PRIVATE_RESPONSE_SENTINEL_67ab"
        secret = "sk-" + "live-" + "abcdefghijklmnopqrstuvwxyz012345"
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            path = "main/java/fixture/PrivateBody.java"
            body = (
                "public class PrivateBody {\n"
                f'    String snippet = "{snippet}";\n'
                f'    String url = "{url}";\n'
                f'    String prompt = "{prompt}";\n'
                f'    String response = "{response}";\n'
                f'    String secret = "{secret}";\n'
                "}"
            )
            write_java(fx.root, path, 8, body)
            refresh_git_paths(fx)
            write_harmony_input(fx, secret_hits=1)

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            rendered = bundle_bytes(bundle).decode("utf-8")

            for forbidden in (str(fx.root.resolve()), snippet, url, prompt, response, secret):
                self.assertNotIn(forbidden, rendered)
            self.assertEqual(1, bundle.metrics["secretPatternHitCount"])
            self.assertEqual(1, bundle.baseline["secretPatternHitCount"])

            for absolute_value in (
                "/private/sentinel",
                r"\\server\share\sentinel",
                r"\private\sentinel",
                "file:///private/sentinel",
            ):
                with self.subTest(absolute_value=absolute_value):
                    mutated = relink_after_metrics_mutation(
                        bundle,
                        lambda metrics, value=absolute_value: metrics[
                            "runtimeProviderDisabledSmoke"
                        ].update({"reason": value}),
                    )
                    self.assert_reason(
                        "public-artifact-secret-hit",
                        audit.validate_bundle,
                        mutated,
                    )

    def test_secret_shaped_untracked_filename_is_tokenized_without_dropping_manifest_row(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            secret_shaped_path = "data/evidence/sk-" + ("a" * 24) + ".txt"
            active_secret_shaped_path = (
                "main/java/fixture/sk-" + ("b" * 24) + ".java"
            )
            payload = b"count-only evidence\n"
            write_bytes(fx.root, secret_shaped_path, payload)
            write_java(
                fx.root,
                active_secret_shaped_path,
                2001,
                java_body("SecretShapedActive"),
            )
            refresh_git_paths(fx)
            fx.git_status.write_bytes(
                (
                    "? " + secret_shaped_path + "\0"
                    + "? " + active_secret_shaped_path + "\0"
                ).encode("utf-8")
            )

            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)

        rendered = bundle_bytes(bundle).decode("utf-8")
        tokenized_path = "__redacted_path__/sha256-" + sha256_hex(
            secret_shaped_path.encode("utf-8")
        )
        active_tokenized_path = (
            "main/java/__redacted_path__/sha256-"
            + sha256_hex(active_secret_shaped_path.encode("utf-8"))
        )
        baseline_by_path = {
            row["path"]: row for row in bundle.baseline["pathStateContentRows"]
        }
        self.assertNotIn(secret_shaped_path, rendered)
        self.assertNotIn(active_secret_shaped_path, rendered)
        self.assertIn(tokenized_path, baseline_by_path)
        self.assertEqual("untracked", baseline_by_path[tokenized_path]["gitState"])
        self.assertEqual(len(payload), baseline_by_path[tokenized_path]["sizeBytes"])
        self.assertEqual(sha256_hex(payload), baseline_by_path[tokenized_path]["contentSha256"])
        self.assertIn(
            active_tokenized_path,
            {row["path"] for row in bundle.ledger_rows},
        )

    def test_load_and_validate_current_bundle_rejects_relinked_metric_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            audit.publish_bundle(bundle, make_outputs(fx))

            loaded = audit.load_and_validate_current_bundle(root=fx.root)
            self.assertEqual(bundle.baseline, loaded.baseline)
            self.assertEqual(bundle.ledger_rows, loaded.ledger_rows)
            self.assertEqual(bundle.metrics, loaded.metrics)

            cases = (
                (
                    "known-p95",
                    "semantic-nondeterminism",
                    lambda metrics: metrics.update({"activeJavaLocP95": 999999}),
                ),
                (
                    "unknown-field",
                    "public-artifact-secret-hit",
                    lambda metrics: metrics.update(
                        {"unrecognizedSchemaDrift": {"changesScoreMeaning": True}}
                    ),
                ),
            )
            for case, reason, mutate in cases:
                with self.subTest(case=case):
                    altered = relink_after_metrics_mutation(bundle, mutate)
                    fx.baseline.write_bytes(canonical_bytes(altered.baseline))
                    fx.ledger.write_bytes(
                        b"".join(canonical_bytes(row) for row in altered.ledger_rows)
                    )
                    fx.metrics.write_bytes(canonical_bytes(altered.metrics))
                    self.assert_reason(
                        reason,
                        audit.load_and_validate_current_bundle,
                        root=fx.root,
                    )

            audit.publish_bundle(bundle, make_outputs(fx))
            fx.metrics.write_bytes(fx.metrics.read_bytes() + b" ")
            self.assert_reason(
                "required-input-malformed",
                audit.load_and_validate_current_bundle,
                root=fx.root,
            )

    def test_cross_artifact_links_recompute_from_payloads(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            path = "main/java/fixture/LinkedCatch.java"
            write_java(fx.root, path, 3, java_body("LinkedCatch"))
            refresh_git_paths(fx)
            write_harmony_input(
                fx,
                broad_rows=[
                    {
                        "file": path,
                        "lines": 3,
                        "broadCatchBlocks": 1,
                        "broadCatchWithoutLocalBreadcrumbApprox": 1,
                    }
                ],
            )
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            audit.validate_bundle(bundle)

            baseline = copy.deepcopy(bundle.baseline)
            baseline["canonicalRootPathLength"] += 1
            baseline_bundle = replace(bundle, baseline=baseline)

            metrics = copy.deepcopy(bundle.metrics)
            metrics["activeJavaLocP95"] += 0.5
            metrics_bundle = replace(bundle, metrics=metrics)

            ledger_rows = copy.deepcopy(list(bundle.ledger_rows))
            ledger_rows[0]["proofCommand"] = "gradlew.bat dynamicRagQuantAudit"
            ledger_bundle = replace(bundle, ledger_rows=tuple(ledger_rows))

            for mutated in (baseline_bundle, metrics_bundle, ledger_bundle):
                self.assert_reason("artifact-link-mismatch", audit.validate_bundle, mutated)

    def test_publish_bundle_rejects_alias_destinations_before_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            alias_parent = fx.baseline.parent / "alias"
            alias_parent.mkdir()
            aliased_baseline = alias_parent / ".." / fx.baseline.name
            outputs = audit.AuditOutputs(
                metrics_output=fx.metrics,
                baseline_output=fx.baseline,
                ledger_output=aliased_baseline,
            )
            sentinels = {
                fx.baseline: b"prior-baseline",
                fx.ledger: b"prior-ledger",
                fx.metrics: b"prior-metrics",
            }
            for path, payload in sentinels.items():
                path.write_bytes(payload)

            self.assert_reason(
                "output-replace-failed",
                audit.publish_bundle,
                bundle,
                outputs,
            )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_output_argument_rejects_absolute_escape_and_wrong_destination(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            expected = "verification/structural-design-baseline.json"
            cases = {
                "absolute": str(root / expected),
                "escape": "../outside.json",
                "alias": "verification/alias/../structural-design-baseline.json",
                "wrong": "verification/other.json",
            }
            for case, value in cases.items():
                with self.subTest(case=case):
                    self.assert_reason(
                        "output-replace-failed",
                        audit._fixed_output_argument_path,
                        root,
                        value,
                        expected,
                    )

            self.assertEqual(
                root / expected,
                audit._fixed_output_argument_path(root, expected, expected),
            )

    def test_publish_bundle_rejects_reparse_output_parent(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            outputs = make_outputs(fx)
            sentinels = {
                fx.baseline: b"prior-baseline",
                fx.ledger: b"prior-ledger",
                fx.metrics: b"prior-metrics",
            }
            for path, payload in sentinels.items():
                path.write_bytes(payload)
            real_has_reparse = audit._has_reparse_component

            def injected_reparse(root, candidate):
                if Path(candidate).absolute() == fx.baseline.parent.absolute():
                    return True
                return real_has_reparse(root, candidate)

            with mock.patch.object(
                audit,
                "_has_reparse_component",
                side_effect=injected_reparse,
            ):
                self.assert_reason(
                    "output-replace-failed",
                    audit.publish_bundle,
                    bundle,
                    outputs,
                )
            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

    def test_replace_failure_restores_every_prior_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            outputs = make_outputs(fx)
            sentinels = {
                fx.baseline: b"prior-baseline",
                fx.ledger: b"prior-ledger",
                fx.metrics: b"prior-metrics",
            }
            for path, payload in sentinels.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(payload)

            real_replace = os.replace
            failed = False

            def flaky_replace(source, destination):
                nonlocal failed
                if Path(destination) == fx.ledger and not failed:
                    failed = True
                    raise OSError("injected replace failure")
                return real_replace(source, destination)

            with mock.patch.object(audit.os, "replace", side_effect=flaky_replace):
                self.assert_reason(
                    "output-replace-failed",
                    audit.publish_bundle,
                    bundle,
                    outputs,
                )

            for path, payload in sentinels.items():
                self.assertEqual(payload, path.read_bytes())

        with self.subTest(case="restore-failure-preserves-recovery-sidecar"):
            with tempfile.TemporaryDirectory() as tmp:
                fx = make_fixture(Path(tmp))
                bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
                outputs = make_outputs(fx)
                sentinels = {
                    fx.baseline: b"prior-baseline",
                    fx.ledger: b"prior-ledger",
                    fx.metrics: b"prior-metrics",
                }
                for path, payload in sentinels.items():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(payload)

                real_replace = os.replace
                commit_failed = False
                restore_failed = False
                baseline_rollback = fx.baseline.with_name(fx.baseline.name + ".rollback")

                def flaky_commit_and_restore(source, destination):
                    nonlocal commit_failed, restore_failed
                    source_path = Path(source)
                    destination_path = Path(destination)
                    if destination_path == fx.ledger and not commit_failed:
                        commit_failed = True
                        raise OSError("injected commit failure")
                    if (
                        destination_path == fx.baseline
                        and source_path == baseline_rollback
                        and not restore_failed
                    ):
                        restore_failed = True
                        raise OSError("injected restore failure")
                    return real_replace(source, destination)

                with mock.patch.object(
                    audit.os,
                    "replace",
                    side_effect=flaky_commit_and_restore,
                ):
                    self.assert_reason(
                        "output-replace-failed",
                        audit.publish_bundle,
                        bundle,
                        outputs,
                    )

                self.assertTrue(commit_failed)
                self.assertTrue(restore_failed)
                self.assertTrue(baseline_rollback.is_file())
                self.assertEqual(sentinels[fx.baseline], baseline_rollback.read_bytes())
                self.assertEqual(sentinels[fx.ledger], fx.ledger.read_bytes())
                self.assertEqual(sentinels[fx.metrics], fx.metrics.read_bytes())

    def test_metrics_is_the_last_replaced_commit_record(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = make_fixture(Path(tmp))
            bundle = audit.build_audit(make_inputs(fx), FIXED_NOW)
            outputs = make_outputs(fx)
            destinations = {fx.baseline, fx.ledger, fx.metrics}
            replacement_order: list[Path] = []
            real_replace = os.replace

            def recording_replace(source, destination):
                destination_path = Path(destination)
                if destination_path in destinations:
                    replacement_order.append(destination_path)
                return real_replace(source, destination)

            with mock.patch.object(audit.os, "replace", side_effect=recording_replace):
                audit.publish_bundle(bundle, outputs)

        self.assertEqual([fx.baseline, fx.ledger, fx.metrics], replacement_order)


if __name__ == "__main__":
    unittest.main()
