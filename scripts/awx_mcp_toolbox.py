#!/usr/bin/env python3
"""Small JSON-in/JSON-out toolbox for AWX multi-node safe patch work.

The script is intentionally external to the Java runtime. It supports
read-only probes, PatchDrop planning, archive lookup/restore with audit logs,
and verification command generation while avoiding raw secret output.
"""

from __future__ import annotations

import argparse
import datetime as dt
import fnmatch
import hashlib
import hmac
import importlib.util
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

try:
    from scripts.awx_shared_state import Change, Conflict, apply_changes, read_optional, plain_path
    from scripts.awx_host_runtime import local_state_root, host_facts
except ModuleNotFoundError:
    from awx_shared_state import Change, Conflict, apply_changes, read_optional, plain_path
    from awx_host_runtime import local_state_root, host_facts

try:
    from scripts.harmony_catch_contract import classify_java_catches, summarize_catches
except ModuleNotFoundError as error:
    if error.name not in {"scripts", "scripts.harmony_catch_contract"}:
        raise
    _contract_path = Path(__file__).resolve().with_name("harmony_catch_contract.py")
    _contract_name = (
        "_awx_harmony_catch_contract_"
        + hashlib.sha256(str(_contract_path).encode("utf-8")).hexdigest()[:16]
    )
    _contract_module = sys.modules.get(_contract_name)
    if _contract_module is None:
        _contract_spec = importlib.util.spec_from_file_location(_contract_name, _contract_path)
        if _contract_spec is None or _contract_spec.loader is None:
            raise ImportError("harmony catch contract loader unavailable")
        _contract_module = importlib.util.module_from_spec(_contract_spec)
        sys.modules[_contract_name] = _contract_module
        try:
            _contract_spec.loader.exec_module(_contract_module)
        except Exception:
            sys.modules.pop(_contract_name, None)
            raise
    classify_java_catches = _contract_module.classify_java_catches
    summarize_catches = _contract_module.summarize_catches


SCHEMA_VERSION = "awx.mcp.toolbox.v1"
HARMONY_RUNTIME_PROOF_SCHEMA_VERSION = "awx.mcp.harmony_runtime_proof.v1"
APP_JAVA_CLEAN_RUNTIME_EXCLUDES = (
    "com/example/lms/guard/AnswerSanitizer*",
    "com/example/lms/service/onnx/OnnxCrossEncoderReranker*",
    "com/example/lms/service/rag/AnalyzeWebSearchRetriever*",
    "com/example/lms/service/rag/auth/DomainWhitelist*",
    "com/example/lms/service/rag/fusion/RerankCanonicalizer*",
    "com/example/lms/service/rag/fusion/WeightedRRF*",
    "com/example/lms/service/rag/fusion/WeightedPowerMeanFuser*",
    "com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain*",
    "com/example/lms/service/rag/handler/KnowledgeGraphHandler*",
    "com/example/lms/service/rag/overdrive/AngerOverdriveNarrower*",
    "com/example/lms/service/rag/overdrive/OverdriveGuard*",
    "com/example/lms/service/rag/rerank/DppDiversityReranker*",
    "service/rag/DppDiversityReranker*",
    "com/example/lms/strategy/RetrievalOrderService*",
    "com/example/lms/trace/TraceContext*",
    "service/rag/planner/SelfAskPlanner*",
    "trace/TimeBudget*",
)
ENV_REFS = (
    "NAVER_KEYS",
    "NAVER_CLIENT_ID",
    "NAVER_CLIENT_SECRET",
    "OPENAI_API_KEY",
    "BRAVE_API_KEY",
    "BRAVE_API_KEY_FREE",
    "SERPAPI_API_KEY",
    "TAVILY_API_KEY",
    "LMS_DB_URL",
    "LMS_DB_USERNAME",
    "LMS_DB_PASSWORD",
    "LMS_DB_DRIVER",
    "LMS_DB_DIALECT",
    "LMS_DB_HIKARI_CONNECTION_TIMEOUT",
    "AGENT_DB_CONTEXT_QUERY_TIMEOUT_SECONDS",
    "AWX_AGENT_DB_CONTEXT_BASE_URL",
    "AWX_TRACE_SNAPSHOT_BASE_URL",
    "APP_PUBLIC_BASE_URL",
    "PUBLIC_BASE_URL",
    "AWX_ADMIN_TOKEN",
)
SUPABASE_ENV_REFS = (
    "SUPABASE_URL",
    "SUPABASE_PROJECT_REF",
    "SUPABASE_ACCESS_TOKEN",
    "SUPABASE_PUBLISHABLE_KEY",
    "SUPABASE_PUBLISHABLE_KEYS",
    "SUPABASE_SECRET_KEY",
    "SUPABASE_SECRET_KEYS",
    "SUPABASE_ANON_KEY",
    "SUPABASE_SERVICE_ROLE_KEY",
    "SUPABASE_DB_URL",
    "DATABASE_URL",
    "POSTGRES_URL",
    "NEXT_PUBLIC_SUPABASE_URL",
    "NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY",
    "NEXT_PUBLIC_SUPABASE_ANON_KEY",
)
ALLOWED_CONTROL_TOWER_ENV_REFS = (
    "NAVER_KEYS",
    "NAVER_CLIENT_ID",
    "NAVER_CLIENT_SECRET",
    "SUPABASE_PROJECT_REF",
    "SUPABASE_ACCESS_TOKEN",
)
PATCHDROP_HANDOFF_REQUIRED_ARTIFACTS = (
    ".patch",
    ".report.md",
    ".verify.log",
    ".sha256.txt",
    ".manifest.json",
    "pendingNotice",
)
HIGH_CONF_SECRET_PATTERNS = (
    re.compile(r"(?<![A-Za-z0-9_])sk-[A-Za-z0-9_-]{20,}"),
    re.compile(r"AIza[A-Za-z0-9_-]{20,}"),
    re.compile(r"gsk_[A-Za-z0-9_-]{20,}"),
    re.compile(r"pcsk_[A-Za-z0-9_-]{20,}"),
    re.compile(r"sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}"),
    re.compile(r"sbp_[A-Za-z0-9_-]{10,}"),
)
PATCH_SECRET_PATTERNS = HIGH_CONF_SECRET_PATTERNS + (
    re.compile(r"\b(?:authorization|cookie)\s*[:=]", re.IGNORECASE),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |PRIVATE )?PRIVATE KEY-----", re.IGNORECASE),
)
SUPABASE_IMPORT_TEXT_JSON_MAX_CHARS = 200_000
REDACTION_PATTERNS = HIGH_CONF_SECRET_PATTERNS + (
    re.compile(r"(?i)\b(authorization|cookie)\s*[:=]"),
    re.compile(r"(?i)\b(password|passwd|pwd|client[-_.]?secret|api[-_.]?key|token)\s*[:=]\s*\S+"),
)
JPA_ENTITY_DECLARATION_RE = re.compile(r"(?m)^\s*@(?:[\w.]+\.)?(?:Entity|Table)\b")
JPA_REPOSITORY_EXTENDS_RE = re.compile(
    r"(?m)\bextends\s+(?:[\w.]+\.)?(?:Jpa|Crud|PagingAndSorting)Repository\s*<"
)
SAFE_AUDIT_FIELDS = (
    "requestId",
    "sessionId",
    "nodeRole",
    "toolName",
    "inputHash",
    "outputCount",
    "elapsedMs",
    "decision",
    "failReason",
)
TOOL_ALIASES = {
    "source.scan": "source_scan",
    "patch.plan": "patch_plan",
    "patch.render": "patch_render",
    "archive.index_build": "archive_index_build",
    "archive.search": "archive_search",
    "archive.restore": "archive_restore",
    "verify_boot": "boot_verify",
    "verify.boot": "boot_verify",
    "build_error_miner": "build_error_mine",
    "build.error_miner": "build_error_mine",
    "build.error.mine": "build_error_mine",
    "pipeline.run": "run_pipeline",
    "external.evidence_intake": "external_evidence_intake",
    "external.evidence_audit": "external_evidence_audit",
    "producer.command_plan": "producer_command_plan",
    "producer.kit_export": "producer_kit_export",
    "desktop.dispatch_packet": "desktop_dispatch_packet",
    "desktop.control_loop": "desktop_control_loop",
    "debug.smb_decommission_probe": "smb_decommission_debug_probe",
    "smb.decommission_debug_probe": "smb_decommission_debug_probe",
    "smb.debug_probe": "smb_decommission_debug_probe",
    "peer.evidence_bus": "peer_evidence_bus",
    "peer.evidence": "peer_evidence_bus",
    "web.probe_refresh": "web_probe_refresh",
    "web.probe.refresh": "web_probe_refresh",
    "harmony.scan": "harmony_scan",
    "agent.db_snapshot": "agent_db_snapshot",
    "agent.db.snapshot": "agent_db_snapshot",
    "trace.snapshot": "trace_snapshot_probe",
    "trace.snapshot_probe": "trace_snapshot_probe",
    "supabase.probe": "supabase_context_probe",
    "supabase.context_probe": "supabase_context_probe",
    "supabase.snapshot": "supabase_schema_snapshot",
    "supabase.schema_snapshot": "supabase_schema_snapshot",
    "supabase.import_snapshot": "supabase_schema_snapshot_import",
    "supabase.schema_snapshot_import": "supabase_schema_snapshot_import",
    "guard.status": "guard_status",
    "agent.preflight": "guard_status",
    "session.evidence": "session_evidence",
    "session.search": "session_evidence",
}
PRODUCER_KIT_FILES = (
    "scripts/awx_mcp_toolbox.py",
    "scripts/awx_mcp_toolbox.ps1",
    "scripts/awx_mcp_stdio_server.py",
    "scripts/awx_codex_review_adapter.py",
    "scripts/awx_mcp_node_setup.py",
    "scripts/awx_shared_state.py",
    "scripts/awx_host_runtime.py",
    "scripts/awx_project_secrets.py",
    "scripts/awx_project_keys.py",
    "scripts/awx_resource_inputs.py",
    "scripts/awx_device_bus.py",
    "scripts/awx_device_capabilities.py",
    "scripts/awx_secrets_acl.ps1",
    "scripts/use_project_keys.ps1",
    "scripts/verify_ydrive_backing_identity.ps1",
    "config/project-resources.json",
    "scripts/awx_device_policy.py",
    "scripts/awx_device_work.py",
    "scripts/awx_skill_registry.py",
    "scripts/awx_mcp_safe_install.py",
    "scripts/harmony_catch_contract.py",
    ".codex/hooks.json",
    ".codex/shared-runtime.json",
    ".codex/hooks/source_edit_triage.py",
    ".codex/hooks/source_edit_triage.ps1",
    ".codex/hooks/source_edit_triage.sh",
    "scripts/awx_mcp_node_smoke.py",
    "scripts/awx_mcp_producer_handoff.py",
    "scripts/awx_mcp_completion_audit.py",
    "scripts/awx_mcp_control_tower_pipeline.py",
    "__patch_drop__/producer_bundle.py",
    "__patch_drop__/producer_bundle.ps1",
    "main/resources/mcp/awx-control-tower-tools.json",
    "main/resources/mcp/awx-control-tower-mcp-client.sample.json",
    ".agents/skills/demo1-mcp-control-tower/SKILL.md",
    ".agents/skills/archive-search/SKILL.md",
    ".agents/skills/archive-restore/SKILL.md",
    ".agents/skills/verify-boot/SKILL.md",
    ".agents/skills/build-error-miner/SKILL.md",
    ".agents/skills/run-pipeline/SKILL.md",
    "data/agent-handoff/mcp-control-tower/README.md",
    "data/agent-handoff/mcp-control-tower/skills/demo1-mcp-control-tower/SKILL.md",
    "data/agent-handoff/mcp-control-tower/skills/archive-search/SKILL.md",
    "data/agent-handoff/mcp-control-tower/skills/archive-restore/SKILL.md",
    "data/agent-handoff/mcp-control-tower/skills/verify-boot/SKILL.md",
    "data/agent-handoff/mcp-control-tower/skills/build-error-miner/SKILL.md",
    "data/agent-handoff/mcp-control-tower/skills/run-pipeline/SKILL.md",
    "data/agent-handoff/mcp-control-tower/demo1_mcp_control_tower.prompt",
    "data/agent-handoff/mcp-control-tower/prompt-manifest.yaml",
    "agent-prompts/agents/demo1_mcp_control_tower/system.md",
    "agent-prompts/out/demo1_mcp_control_tower.prompt",
)
REQUIRED_NODE_SMOKE_TOOLS = {
    "source_scan",
    "agent_db_snapshot",
    "trace_snapshot_probe",
    "supabase_context_probe",
    "supabase_schema_snapshot",
    "archive_search",
    "patch_plan",
    "patch_render",
    "boot_verify",
    "build_error_mine",
    "run_pipeline",
    "archive_restore",
}
NODE_SMOKE_DECISION_ALLOWLIST = {
    "agent_db_snapshot": {
        "agent_db_snapshot_loaded",
        "agent_db_snapshot_http_error",
        "agent_db_snapshot_unavailable_with_local_fallback",
    },
    "trace_snapshot_probe": {
        "trace_snapshot_loaded",
        "trace_snapshot_http_error_with_local_fallback",
        "trace_snapshot_unavailable_with_local_fallback",
    },
    "supabase_context_probe": {
        "supabase_context_probe",
    },
    "supabase_schema_snapshot": {
        "supabase_schema_snapshot_evidence_needed",
    },
}
NODE_SMOKE_FALLBACK_DECISIONS = {
    "agent_db_snapshot": {
        "agent_db_snapshot_http_error",
        "agent_db_snapshot_unavailable_with_local_fallback",
    },
    "trace_snapshot_probe": {
        "trace_snapshot_http_error_with_local_fallback",
        "trace_snapshot_unavailable_with_local_fallback",
    },
}
NODE_SMOKE_STEP_FIELDS = frozenset(
    {
        "toolName",
        "exitCode",
        "ok",
        "decision",
        "failReason",
        "localFallbackPresent",
        "outputCount",
        "elapsedMs",
        "evidence_needed",
    }
)
HARMONY_EVIDENCE_CONTRACT_SHA256 = (
    "560438505e10525ada56118b9b26360eed14c7f29b3a811541bbbce877694a09"
)
HARMONY_EVIDENCE_CONTRACT_SCHEMA = "awx.harmony.evidence-contract.v1"
HARMONY_EVIDENCE_CONTRACT_ID = "HB-01-12"
HARMONY_VERIFIED_STATUS = "DONE"
HARMONY_BLOCKED_STATUS = "BLOCKED_EVIDENCE"
HARMONY_EXPECTED_ROOTS = (
    ("mainJava", "main/java"),
    ("mainResources", "main/resources"),
    ("testJava", "src/test/java"),
    ("testResources", "src/test/resources"),
    ("appJavaClean", "app/src/main/java_clean"),
    ("appResources", "app/src/main/resources"),
)
HARMONY_EXPECTED_BREAK_IDS = tuple(f"HB-{number:02d}" for number in range(1, 13))
HARMONY_ALLOWED_SUBSYSTEMS = {f"S{number:02d}" for number in range(1, 9)}
HARMONY_ALLOWED_RUNTIME_RULES = {
    "EMPTY_COLLECTION",
    "NON_BLANK_STRING",
    "FINITE_NUMBER",
    "ZERO_NUMBER",
    "NON_NEGATIVE_NUMBER",
    "TRUE_BOOLEAN",
}
HARMONY_ALLOWED_MUTATION_OPERATIONS = {
    "REMOVE",
    "REPLACE_TEXT",
    "APPEND_DUPLICATE",
    "ADD_TOP_LEVEL",
}


class HarmonyEvidenceContractError(ValueError):
    """Redacted fail-closed contract validation error."""


def harmony_evidence_contract_path() -> Path:
    return (
        Path(__file__).resolve().parents[1]
        / "main"
        / "resources"
        / "mcp"
        / "harmony-evidence-contract.v1.json"
    )


def _contract_object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise HarmonyEvidenceContractError("contract_schema_failed: duplicate_field")
        result[key] = value
    return result


def _contract_text(mapping: dict[str, Any], key: str, *, allow_empty: bool = False) -> str:
    value = mapping.get(key)
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        raise HarmonyEvidenceContractError(f"contract_schema_failed: {key}_text_required")
    return value


def _require_contract_fields(mapping: dict[str, Any], expected: set[str], location: str) -> None:
    if not isinstance(mapping, dict) or set(mapping) != expected:
        raise HarmonyEvidenceContractError(f"contract_schema_failed: {location}_fields")


def parse_harmony_evidence_contract_bytes(
    raw: bytes,
    expected_sha256: str,
) -> dict[str, Any]:
    if not raw:
        raise HarmonyEvidenceContractError("contract_integrity_failed: resource_empty")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise HarmonyEvidenceContractError("contract_schema_failed: invalid_utf8") from error
    if text.startswith("\ufeff"):
        text = text[1:]
    canonical = text.replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")
    actual_sha256 = hashlib.sha256(canonical).hexdigest()
    if not isinstance(expected_sha256, str) or not hmac.compare_digest(
        actual_sha256,
        expected_sha256.lower(),
    ):
        raise HarmonyEvidenceContractError("contract_integrity_failed: sha256_mismatch")
    try:
        contract = json.loads(
            canonical.decode("utf-8"),
            object_pairs_hook=_contract_object_without_duplicates,
        )
    except HarmonyEvidenceContractError:
        raise
    except json.JSONDecodeError as error:
        raise HarmonyEvidenceContractError(
            "contract_schema_failed: invalid_json"
        ) from error

    _require_contract_fields(
        contract,
        {
            "schemaVersion",
            "contractId",
            "statuses",
            "activeSourceRoots",
            "requiredTraceKeys",
            "breaks",
            "parserMutationCorpus",
        },
        "root",
    )
    if _contract_text(contract, "schemaVersion") != HARMONY_EVIDENCE_CONTRACT_SCHEMA:
        raise HarmonyEvidenceContractError("contract_schema_failed: schema_version_mismatch")
    if _contract_text(contract, "contractId") != HARMONY_EVIDENCE_CONTRACT_ID:
        raise HarmonyEvidenceContractError("contract_schema_failed: contract_id_mismatch")

    statuses = contract.get("statuses")
    _require_contract_fields(statuses, {"verified", "blocked"}, "statuses")
    if (
        _contract_text(statuses, "verified") != HARMONY_VERIFIED_STATUS
        or _contract_text(statuses, "blocked") != HARMONY_BLOCKED_STATUS
    ):
        raise HarmonyEvidenceContractError("contract_schema_failed: statuses_mismatch")

    roots = contract.get("activeSourceRoots")
    if not isinstance(roots, list) or len(roots) != len(HARMONY_EXPECTED_ROOTS):
        raise HarmonyEvidenceContractError("contract_schema_failed: active_source_roots_size")
    for index, (expected_id, expected_path) in enumerate(HARMONY_EXPECTED_ROOTS):
        item = roots[index]
        _require_contract_fields(item, {"id", "path"}, f"activeSourceRoots[{index}]")
        if (
            _contract_text(item, "id") != expected_id
            or _contract_text(item, "path") != expected_path
        ):
            raise HarmonyEvidenceContractError(
                "contract_schema_failed: active_source_root_mismatch"
            )

    trace_keys = contract.get("requiredTraceKeys")
    if (
        not isinstance(trace_keys, list)
        or not trace_keys
        or any(not isinstance(key, str) or not key.strip() for key in trace_keys)
        or len(set(trace_keys)) != len(trace_keys)
    ):
        raise HarmonyEvidenceContractError("contract_schema_failed: required_trace_keys_invalid")
    trace_key_set = set(trace_keys)

    breaks = contract.get("breaks")
    if not isinstance(breaks, list) or len(breaks) != len(HARMONY_EXPECTED_BREAK_IDS):
        raise HarmonyEvidenceContractError("contract_schema_failed: breaks_size")
    for index, expected_id in enumerate(HARMONY_EXPECTED_BREAK_IDS):
        item = breaks[index]
        _require_contract_fields(
            item,
            {"id", "weight", "subsystem", "label", "runtimeRequirements"},
            f"breaks[{index}]",
        )
        if _contract_text(item, "id") != expected_id:
            raise HarmonyEvidenceContractError("contract_schema_failed: break_id_mismatch")
        weight = item.get("weight")
        if (
            isinstance(weight, bool)
            or not isinstance(weight, (int, float))
            or not math.isfinite(float(weight))
            or float(weight) <= 0.0
        ):
            raise HarmonyEvidenceContractError("contract_schema_failed: invalid_break_weight")
        if _contract_text(item, "subsystem") not in HARMONY_ALLOWED_SUBSYSTEMS:
            raise HarmonyEvidenceContractError("contract_schema_failed: invalid_subsystem")
        _contract_text(item, "label")
        requirements = item.get("runtimeRequirements")
        if not isinstance(requirements, list) or not requirements:
            raise HarmonyEvidenceContractError(
                "contract_schema_failed: runtime_requirements_missing"
            )
        requirement_keys: set[str] = set()
        for requirement_index, requirement in enumerate(requirements):
            _require_contract_fields(
                requirement,
                {"traceKey", "rule"},
                f"breaks[{index}].runtimeRequirements[{requirement_index}]",
            )
            trace_key = _contract_text(requirement, "traceKey")
            rule = _contract_text(requirement, "rule")
            if (
                trace_key not in trace_key_set
                or trace_key in requirement_keys
                or rule not in HARMONY_ALLOWED_RUNTIME_RULES
            ):
                raise HarmonyEvidenceContractError(
                    "contract_schema_failed: invalid_runtime_requirement"
                )
            requirement_keys.add(trace_key)

    mutations = contract.get("parserMutationCorpus")
    if not isinstance(mutations, list) or len(mutations) < 8:
        raise HarmonyEvidenceContractError("contract_schema_failed: mutation_corpus_missing")
    mutation_ids: set[str] = set()
    for index, mutation in enumerate(mutations):
        _require_contract_fields(
            mutation,
            {"id", "operation", "path", "value", "expectedStatus"},
            f"parserMutationCorpus[{index}]",
        )
        mutation_id = _contract_text(mutation, "id")
        operation = _contract_text(mutation, "operation")
        path = _contract_text(mutation, "path")
        _contract_text(mutation, "value", allow_empty=True)
        expected_status = _contract_text(mutation, "expectedStatus")
        if (
            mutation_id in mutation_ids
            or operation not in HARMONY_ALLOWED_MUTATION_OPERATIONS
            or not path.startswith("/")
            or expected_status != HARMONY_BLOCKED_STATUS
        ):
            raise HarmonyEvidenceContractError(
                "contract_schema_failed: invalid_mutation_corpus"
            )
        mutation_ids.add(mutation_id)

    contract["sha256"] = actual_sha256
    return contract


def load_harmony_evidence_contract() -> dict[str, Any]:
    path = harmony_evidence_contract_path()
    try:
        raw = path.read_bytes()
    except OSError as error:
        raise HarmonyEvidenceContractError(
            "contract_integrity_failed: resource_unreadable"
        ) from error
    return parse_harmony_evidence_contract_bytes(
        raw,
        HARMONY_EVIDENCE_CONTRACT_SHA256,
    )


try:
    _HARMONY_EVIDENCE_CONTRACT = load_harmony_evidence_contract()
except HarmonyEvidenceContractError:
    _HARMONY_EVIDENCE_CONTRACT = None

HARMONY_BREAK_WEIGHTS = {
    item["id"]: float(item["weight"])
    for item in (_HARMONY_EVIDENCE_CONTRACT or {}).get("breaks", [])
}
HARMONY_REQUIRED_TRACE_KEYS = tuple(
    (_HARMONY_EVIDENCE_CONTRACT or {}).get("requiredTraceKeys", [])
)
HARMONY_SUBSYSTEM_KEYWORDS = {
    "S01_OVERDRIVE": ("OverdriveGuard", "DynamicContextCompressor", "overdrive", "anchor"),
    "S02_CFVM": ("CfvmFailureRecorder", "RawMatrixBuffer", "RawSlotExtractor", "cfvm"),
    "S03_MOE": ("MoEStrategySelector", "ArtPlateEvolver", "evolverPlate", "moe."),
    "S04_MATRYOSHKA": ("Matryoshka", "embeddingSlice", "slicing"),
    "S05_EXTREMEZ": ("ExtremeZSystemHandler", "extremeZ", "MassiveParallel", "burst"),
    "S06_HYPERNOVA": ("NovaNextFusionService", "TailWeightedPowerMean", "CvarAggregator", "hypernova"),
    "S07_CIH_RAG": ("CitationGate", "Iqr", "Mla", "breadcrumb", "cihRag"),
    "S08_OPENAI_ADAPTER": ("OpenAiResponsesChatModel", "ModelGuardSupport", "langchain4j"),
}


class RuntimeToolkit:
    """Invocation-owned composition of the existing Spring launcher and bridge.

    Keep this object alive for start/status/smoke/stop/doctor via --runtime-session.
    One-shot commands always close their client-owned pipes before returning.
    No Ollama PID is ever acquired as a termination target here.
    """
    def __init__(self, payload=None):
        import threading
        import queue
        self.config = payload or {}
        self.root = Path(__file__).absolute().parents[1]
        self.manifest_path = self.root / "main/resources/mcp/awx-control-tower-tools.json"
        self.manifest_hash = ""
        self.refresh_manifest()
        self.base_url = self.config.get("baseUrl", "http://127.0.0.1:8080").rstrip("/")
        parsed = urllib.parse.urlparse(self.base_url)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path or parsed.username or parsed.query or parsed.fragment:
            raise ValueError("runtime_requires_loopback_http")
        self.lock = threading.RLock()
        self.incoming = queue.Queue(maxsize=32)
        self.pipe_eof = object()  # Internal event, impossible to forge with a JSON reply.
        self.owned = None
        self.reader = None
        self.stderr_reader = None
        self.sequence = 0
        self.starts = []
        self.proof = {}
        self.spring_state = None
        self.spring_job = None
        self.owner_started = self.process_started_ms(os.getpid())
        self.published = False
        self.telemetry = {key: 0 for key in ("processStarts", "processRestarts", "restartLimited", "smokeSuccess", "smokeFailure", "toolTimeout", "toolCancelled", "toolFailed")}
        self.telemetry_lock = threading.Lock()
        self.lifecycle = queue.Queue(maxsize=32)
        self.transport_verified_at = 0
        self.transport_verified_at_epoch_ms = 0
        self.timeout_extension = False

    def refresh_manifest(self):
        raw = self.manifest_path.read_bytes()
        current_hash = hashlib.sha256(raw).hexdigest()
        if current_hash != self.manifest_hash:
            self.manifest = json.loads(raw.decode("utf-8"))
            self.manifest_hash = current_hash

    @staticmethod
    def process_started_ms(pid):
        if os.name == "nt":
            import ctypes
            from ctypes import wintypes as w
            k = ctypes.WinDLL("kernel32", use_last_error=True)
            k.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
            k.OpenProcess.restype = w.HANDLE
            k.GetProcessTimes.argtypes = [w.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
            k.CloseHandle.argtypes = [w.HANDLE]
            handle = k.OpenProcess(0x1000, False, pid)
            if not handle:
                return 0
            values = [ctypes.c_uint64() for _ in range(4)]
            try:
                if not k.GetProcessTimes(handle, *(ctypes.byref(v) for v in values)):
                    return 0
                return (values[0].value - 116444736000000000) // 10000
            finally:
                k.CloseHandle(handle)
        # No guessed owner identity on platforms without an established reader.
        return 0

    def http_snapshot(self):
        started = time.monotonic()
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(self.base_url + "/api/chat/ui-heartbeat", timeout=2) as response:
                data = json.loads(response.read(256 * 1024))
            if not isinstance(data.get("runtimeToolkit"), dict):
                return {"reasonCode": "PROTOCOL_MISMATCH", "FULL_LOAD_READY": False}
            return data["runtimeToolkit"]
        except (OSError, ValueError, urllib.error.URLError):
            return {"reasonCode": "SPRING_UNAVAILABLE", "FULL_LOAD_READY": False}
        finally:
            self.health_probe_elapsed_ms = int((time.monotonic() - started) * 1000)

    def ensure_session(self):
        import awx_mcp_stdio_server as bridge
        import threading
        import queue
        if self.owned and self.owned.process.poll() is None:
            return
        if self.owned:
            self.close_pipe()
        now = time.monotonic()
        self.starts = [t for t in self.starts if now - t < 300]
        if len(self.starts) >= 3:
            self.telemetry["restartLimited"] += 1
            self.proof = {"restartLimited": True}
            raise RuntimeError("RESTART_RATE_LIMITED")
        self.starts.append(now)
        self.telemetry["processRestarts"] += int(self.telemetry["processStarts"] > 0)
        self.telemetry["processStarts"] += 1
        self.proof = {}
        self.incoming = queue.Queue(maxsize=32)
        self.lifecycle = queue.Queue(maxsize=32)
        self.transport_verified_at = 0
        self.transport_verified_at_epoch_ms = 0
        self.owned = bridge.OwnedWorker([sys.executable, str(self.root / "scripts/awx_mcp_stdio_server.py")])
        process = self.owned.process
        incoming = self.incoming  # A retiring reader must not publish into its replacement.

        def read():
            try:
                while True:
                    line = process.stdout.readline(4 * 1024 * 1024 + 1)
                    if not line:
                        break
                    if len(line) > 4 * 1024 * 1024:
                        raise ValueError("protocol_output_limit")
                    incoming.put(json.loads(line), timeout=1)
            except ValueError:
                try:
                    incoming.put_nowait(None)  # Malformed output is not a recoverable EOF.
                except queue.Full:
                    pass
            except (OSError, queue.Full):
                pass
            finally:
                try:
                    incoming.put_nowait(self.pipe_eof)
                except queue.Full:
                    pass

        def drain_stderr():
            for line in iter(lambda: process.stderr.readline(4097), ""):
                try:
                    event = json.loads(line)
                    if event.get("service") == "awx-stdio" and event.get("event") == "request_finished":
                        key = {"timeout": "toolTimeout", "cancelled": "toolCancelled", "internal_error": "toolFailed", "output_limit": "toolFailed"}.get(event.get("outcome"))
                        if key:
                            with self.telemetry_lock:
                                self.telemetry[key] += 1
                    if event.get("service") == "awx-stdio" and event.get("event") in {"handler_entered", "request_finished"}:
                        safe = {key: event[key] for key in ("event", "outcome", "workerStarted", "workerExited") if key in event}
                        if self.lifecycle.full():
                            try:
                                self.lifecycle.get_nowait()
                            except queue.Empty:
                                pass
                        self.lifecycle.put_nowait(safe)
                except (ValueError, AttributeError):
                    pass

        self.reader = threading.Thread(target=read, name="awx-toolkit-pipe", daemon=True)
        self.stderr_reader = threading.Thread(target=drain_stderr, name="awx-toolkit-stderr", daemon=True)
        self.reader.start()
        self.stderr_reader.start()
        init = self.rpc("initialize", {"protocolVersion": "2024-11-05", "clientInfo": {"name": "awx-toolbox", "version": "1.0"}, "capabilities": {}})
        self.notify("notifications/initialized", {})
        self.proof["protocolProbe"] = init.get("protocolVersion") == "2024-11-05"
        self.timeout_extension = init.get("capabilities", {}).get("experimental", {}).get("awx/executionTimeoutMs", {}).get("onlyShortensServerLimit") is True

    def notify(self, method, params):
        self.owned.process.stdin.write(json.dumps({"jsonrpc": "2.0", "method": method, "params": params}) + "\n")
        self.owned.process.stdin.flush()

    def rpc(self, method, params=None, *, expected_error=None):
        return self.await_rpc(self.send_rpc(method, params), expected_error=expected_error)

    def send_rpc(self, method, params=None):
        self.sequence += 1
        request_id = self.sequence
        self.owned.process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": {} if params is None else params}) + "\n")
        self.owned.process.stdin.flush()
        return request_id

    def await_rpc(self, request_id, *, expected_error=None):
        import queue
        try:
            reply = self.incoming.get(timeout=10)
        except queue.Empty:
            self.notify("notifications/cancelled", {"requestId": request_id})
            raise RuntimeError("STDIO_TIMEOUT") from None
        if reply is self.pipe_eof:
            raise RuntimeError("STDIO_PROCESS_EXITED")
        if not isinstance(reply, dict) or type(reply.get("id")) is not int or reply.get("id") != request_id:
            raise RuntimeError("STDIO_PROTOCOL_MISMATCH")
        if expected_error is not None:
            if reply.get("error", {}).get("code") != expected_error:
                raise RuntimeError("STDIO_ERROR_CONTRACT_FAILED")
            return {}
        if "error" in reply:
            raise RuntimeError("STDIO_RPC_FAILED")
        return reply["result"]

    def await_lifecycle(self, name, *, outcome=None, timeout=5):
        import queue
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                event = self.lifecycle.get(timeout=max(.001, deadline - time.monotonic()))
            except queue.Empty:
                break
            if event.get("event") == name and (outcome is None or event.get("outcome") == outcome):
                return event
        raise RuntimeError("STDIO_LIFECYCLE_NOT_OBSERVED")

    def transport_smoke(self):
        """Exercise the existing read-only handler against an owned dependency.

        HTTP entry and disconnect are handshakes, not elapsed-time assumptions.
        This proves pipe/worker containment, never external provider health.
        """
        import threading
        import socket
        import uuid
        from http.server import BaseHTTPRequestHandler, HTTPServer
        if time.monotonic() - self.transport_verified_at < 60:
            return
        self.proof.update(cancelVerified=False, timeoutVerified=False, probeResourcesClosed=False,
                          transportVerificationScope="controlled_loopback_dependency_actual_handler")
        if not self.timeout_extension:
            raise RuntimeError("STDIO_TIMEOUT_EXTENSION_UNAVAILABLE")
        for outcome in ("cancelled", "timeout"):
            entered, disconnected, release = threading.Event(), threading.Event(), threading.Event()
            nonce = uuid.uuid4().hex

            class HeldResponse(BaseHTTPRequestHandler):
                def log_message(self, *_args):
                    pass  # Headers, tokens and request text are never recorded.

                def do_GET(self):
                    if self.path != "/" + nonce + "/api/diagnostics/trace/snapshots?limit=1":
                        self.send_error(404)
                        return
                    entered.set()
                    self.connection.settimeout(.1)
                    deadline = time.monotonic() + 8
                    while not release.is_set() and time.monotonic() < deadline:
                        try:
                            if not self.connection.recv(1):
                                disconnected.set()
                                return
                        except socket.timeout:
                            continue
                        except OSError:
                            disconnected.set()
                            return

            server = HTTPServer(("127.0.0.1", 0), HeldResponse)
            thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.05),
                                      name="awx-toolkit-probe", daemon=True)
            thread.start()
            request_id = None
            try:
                while not self.lifecycle.empty():
                    self.lifecycle.get_nowait()
                params = {"name": "trace_snapshot_probe", "arguments": {"nodeRole": "desktop", "limit": 1,
                          "timeout_sec": 60, "base_url": "http://127.0.0.1:%d/%s" % (server.server_port, nonce)},
                          "_meta": {"awx/executionTimeoutMs": 3000 if outcome == "timeout" else 6000}}
                request_id = self.send_rpc("tools/call", params)
                if not entered.wait(4):
                    raise RuntimeError("STDIO_HANDLER_HTTP_ENTRY_NOT_OBSERVED")
                self.await_lifecycle("handler_entered")
                if outcome == "cancelled":
                    self.notify("notifications/cancelled", {"requestId": request_id})
                else:
                    self.await_rpc(request_id, expected_error=-32001)
                finished = self.await_lifecycle("request_finished", outcome=outcome)
                if not finished.get("workerExited") or not disconnected.wait(2):
                    raise RuntimeError("STDIO_OWNED_RESOURCE_EXIT_NOT_OBSERVED")
                self.rpc("ping")  # Detect a cancelled response, duplicate response, or stdout contamination.
                self.proof["cancelVerified" if outcome == "cancelled" else "timeoutVerified"] = True
                request_id = None
            finally:
                try:
                    if request_id is not None:
                        self.notify("notifications/cancelled", {"requestId": request_id})
                finally:
                    release.set()
                    server.shutdown()
                    server.server_close()
                    thread.join(timeout=2)
                if thread.is_alive():
                    raise RuntimeError("STDIO_PROBE_RESOURCE_CLEANUP_FAILED")
        self.proof["probeResourcesClosed"] = True
        self.transport_verified_at = time.monotonic()
        self.transport_verified_at_epoch_ms = int(time.time() * 1000)

    def all_pages(self, method, field):
        rows, cursor, seen = [], None, set()
        for _ in range(32):
            page = self.rpc(method, {} if cursor is None else {"cursor": cursor})
            rows.extend(page[field])
            cursor = page.get("nextCursor")
            if not cursor:
                return rows
            if cursor in seen:
                break
            seen.add(cursor)
        raise RuntimeError("STDIO_PAGINATION_INVALID")

    def smoke(self):
        # Only this fixed read-only probe sequence can be replayed. Generic RPC
        # calls retain their original delivery semantics and never retry here.
        for attempt in range(2):
            try:
                return self._smoke_once()
            except (OSError, RuntimeError) as failure:
                exited = (isinstance(failure, RuntimeError) and str(failure) == "STDIO_PROCESS_EXITED")
                exited = exited or (isinstance(failure, OSError) and self.owned is not None
                                     and self.owned.process.poll() is not None)
                if not exited:
                    raise
                self.telemetry["smokeFailure"] += 1
                self.close_pipe()
                if attempt:
                    raise
                # ensure_session retains the shared three-start/300s budget.

    def _smoke_once(self):
        self.refresh_manifest()
        self.ensure_session()
        self.proof["smokeVerified"] = False
        self.proof.pop("smokeVerifiedAtEpochMs", None)
        catalog = self.rpc("awx/catalog")
        tools = self.all_pages("tools/list", "tools")
        resources = self.all_pages("resources/list", "resources")
        prompts = self.all_pages("prompts/list", "prompts")
        names_match = all([r["name"] for r in rows] == sorted(r["name"] for r in self.manifest[family])
                          for family, rows in (("tools", tools), ("resources", resources), ("prompts", prompts)))
        expected = {r["name"]: r["input_schema"] for r in self.manifest["tools"]}
        expected_outputs = {r["name"]: r.get("output_schema") for r in self.manifest["tools"]}
        schema_match = all(row["inputSchema"] == expected.get(row["name"])
                           and row.get("outputSchema") == expected_outputs.get(row["name"]) for row in tools)
        self.proof.update(catalogValidated=catalog.get("ok") is True and catalog.get("protocolFamily") == "MCP_STYLE"
                          and catalog.get("manifestHash") == self.manifest_hash and names_match and schema_match,
                          toolCount=len(tools), resourceCount=len(resources), promptCount=len(prompts),
                          manifestHash=catalog.get("manifestHash"), protocolFamily=catalog.get("protocolFamily"))
        resource = next(r for r in resources if r["name"] == "tool_manifest")
        self.rpc("resources/read", {"uri": resource["uri"]})
        if prompts:
            self.rpc("prompts/get", {"name": prompts[0]["name"]})
        self.rpc("tools/list", [], expected_error=-32602)
        self.rpc("tools/call", {"name": "__awx_unknown_probe__"}, expected_error=-32602)
        result = self.rpc("tools/call", {"name": "boot_verify", "arguments": {"nodeRole": "desktop"}})
        self.transport_smoke()
        self.proof["smokeVerified"] = not result.get("isError", True) and all(self.proof.get(key) is True
            for key in ("cancelVerified", "timeoutVerified", "probeResourcesClosed"))
        if self.proof["smokeVerified"] and self.proof["catalogValidated"]:
            # Cached cancellation/timeout proof keeps its original verification
            # time. A status publication or partial smoke cannot extend its life.
            self.proof["smokeVerifiedAtEpochMs"] = self.transport_verified_at_epoch_ms
        self.telemetry["smokeSuccess" if self.proof["smokeVerified"] and self.proof["catalogValidated"] else "smokeFailure"] += 1
        return self.status()

    def publish(self, snapshot):
        # Existing runtime state directory; no prompts, responses, sessions or
        # application memory. Readers require owner start identity + fresh hash.
        state_root = os.environ.get('AWX_LOCAL_STATE_ROOT')
        path = (local_state_root(self.root,state_root)/'toolkit-current.json' if state_root
                else self.root / "var/codex-runtime/toolkit-current.json")
        alive = self.owned is not None and self.owned.process.poll() is None
        record = {"schemaVersion": "awx.runtime.client-proof.v1", "ownerPid": os.getpid(),
                  "ownerStartedAtEpochMs": self.owner_started, "observedAtEpochMs": int(time.time()*1000),
                  "springPid": snapshot.get("springPid", 0),
                  "manifestHash": hashlib.sha256(self.manifest_path.read_bytes()).hexdigest(),
                  "stdio": {**self.proof, "sessionAlive": alive,
                            "telemetry": dict(self.telemetry), "healthProbeElapsedMs": getattr(self, "health_probe_elapsed_ms", 0),
                            "processPid": self.owned.process.pid if alive else 0,
                            "processStartedAtEpochMs": self.process_started_ms(self.owned.process.pid) if alive else 0}}
        if path.is_file():
            try:
                previous = json.loads(path.read_text(encoding="utf-8"))
                other_pid = previous.get("ownerPid", 0)
                actual_start = self.process_started_ms(other_pid)
                if other_pid != os.getpid() and actual_start and abs(actual_start - previous.get("ownerStartedAtEpochMs", 0)) < 1000:
                    return False
            except (OSError, ValueError, TypeError):
                return False
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".%d.tmp" % os.getpid())
        temporary.write_text(json.dumps(record), encoding="utf-8")
        temporary.replace(path)
        self.published = True
        return True

    def status(self):
        snapshot = self.http_snapshot()
        publication = "not_attempted"
        if self.owned is not None or self.published:
            publication = "published" if self.publish(snapshot) else "rejected"
            snapshot = self.http_snapshot()
        # The same Java composition and live manager decide FULL_LOAD for the UI
        # and toolkit. Do not turn a client-side port probe into Spring readiness.
        alive = self.owned is not None and self.owned.process.poll() is None
        invocation_ready = (snapshot.get("FULL_LOAD_READY") is True and alive and publication == "published"
                            and all(self.proof.get(key) is True for key in ("protocolProbe", "catalogValidated", "smokeVerified")))
        return {"ok": True, "command": "status", **snapshot, "stdio": {**self.proof,
                "sessionAlive": alive, "evidenceScope": "current_invocation"},
                "readinessScope": "shared_runtime", "proofPublication": publication,
                "invocationFullLoadReady": invocation_ready,
                "springOwnedByInvocation": self.spring_state is not None}

    @staticmethod
    def executable_diagnostics():
        """Bounded version commands only; never return command lines or raw output."""
        results = {}
        patterns = {"java": r'(?m)^(?:openjdk|java) (?:version )?"?(\d+(?:\.\d+){0,3})',
                    "python": r'(?m)^Python (\d+(?:\.\d+){1,3})',
                    "node": r'(?m)^v(\d+(?:\.\d+){1,3})'}
        for name in ("java", "python", "node", "ollama"):
            executable = shutil.which(name)
            row = results[name] = {"present": executable is not None,
                                   "reasonCode": "EXECUTABLE_FOUND" if executable else "EXECUTABLE_NOT_FOUND"}
            if not executable or name == "ollama":
                continue
            try:
                completed = subprocess.run([executable, "-version" if name == "java" else "--version"],
                    capture_output=True, text=True, errors="replace", timeout=2,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
                output = (completed.stdout + "\n" + completed.stderr)
                match = re.search(patterns[name], output) if completed.returncode == 0 and len(output) <= 16384 else None
                if not match:
                    row["reasonCode"] = "VERSION_UNAVAILABLE"
                    continue
                row["version"] = match.group(1)
                parts = tuple(int(part) for part in row["version"].split("."))
                compatible = parts[0] == 17 if name == "java" else parts >= (3, 10) if name == "python" else True
                row["reasonCode"] = "VERSION_OBSERVED" if compatible else "VERSION_INCOMPATIBLE"
            except subprocess.TimeoutExpired:
                row["reasonCode"] = "VERSION_TIMEOUT"
            except (OSError, ValueError):
                row["reasonCode"] = "VERSION_UNAVAILABLE"
        return results

    def doctor(self):
        result = self.status()
        versions = self.executable_diagnostics()
        checks = {"loopbackConfigured": result.get("loopbackConfigured") is True,
                  "requiredSetKnown": result.get("requiredTotal", 0) > 0,
                  "dependenciesReady": result.get("dependenciesReady") is True,
                  "clientProofFresh": result.get("clientProofReason") == "client_proof_verified",
                  "restartBudgetAvailable": len([t for t in self.starts if time.monotonic()-t < 300]) < 3}
        issues = []
        def issue(reason, evidence, action):
            issues.append({"reasonCode": reason, "evidence": evidence, "safeNextAction": action})
        for name, row in versions.items():
            if row["reasonCode"] not in ("EXECUTABLE_FOUND", "VERSION_OBSERVED"):
                issue(row["reasonCode"], {"executable": name, **row},
                      "Verify the existing executable path and version; Java must remain 17 and Python must support 3.10 syntax.")
        actions = {
            "loopbackConfigured": ("LOOPBACK_UNVERIFIED", "Inspect the existing listener's configured and actual loopback address."),
            "requiredSetKnown": ("REQUIRED_SET_UNKNOWN", "Inspect the current runtime profile and manifest before evaluating FULL_LOAD_READY."),
            "dependenciesReady": ("DEPENDENCIES_UNVERIFIED", "Inspect required service dependencies in the existing heartbeat."),
            "clientProofFresh": ("CLIENT_PROOF_UNVERIFIED", "Run smoke in the owning runtime session and inspect its current catalog and process identity."),
            "restartBudgetAvailable": ("RESTART_RATE_LIMITED", "Wait for the existing cooldown window; inspect the failure before retrying.")}
        for check, passed in checks.items():
            if not passed:
                reason, action = actions[check]
                issue(reason, {"check": check, "verified": False}, action)
        if result["proofPublication"] == "rejected":
            issue("CLIENT_PROOF_PUBLICATION_REJECTED", {"evidenceScope": "current_invocation"},
                  "Inspect the existing proof owner; another client's shared readiness does not prove this invocation's session.")
        known_ids = {row["serviceId"] for row in self.manifest.get("runtimeToolkit", {}).get("services", [])}
        for service in result.get("services", []):
            if service.get("serviceId") in known_ids and service.get("required") is True and service.get("status") != "READY":
                functional = service.get("functionalStatus")
                issue("REQUIRED_SERVICE_NOT_READY", {"serviceId": service["serviceId"],
                      "functionalStatus": functional if functional in ("FALLBACK_ACTIVE", "VERIFIED", "NOT_OBSERVED") else "NOT_OBSERVED"},
                      "Inspect this service's existing manager diagnostics and reasonCode; preserve external process ownership.")
        result.update(command="doctor", executables={name: row["present"] for name, row in versions.items()},
                      runtimeVersions=versions, checks=checks, issues=issues,
                      verificationCoverage={"runtimeVersions": "ATTEMPTED", "serviceState": "HEARTBEAT_EVIDENCE",
                          "externalProcessHandles": "NOT_OBSERVED", "secretValues": "NOT_INSPECTED",
                          "unresolvedPlaceholders": "NOT_OBSERVED", "portOwnerIdentity": "NOT_OBSERVED"},
                      nextAction=issues[0]["safeNextAction"] if issues else "No issue observed within the reported verification coverage.")
        return result

    def start_spring(self):
        if self.http_snapshot().get("springPid"):
            return
        if self.config.get("startSpring") is not True:
            return
        if os.name != "nt":
            raise RuntimeError("EXISTING_SPRING_LAUNCHER_REQUIRES_WINDOWS")
        port = urllib.parse.urlparse(self.base_url).port or 8080
        state_root = os.environ.get('AWX_LOCAL_STATE_ROOT')
        state = ((local_state_root(self.root,state_root)/('toolkit-spring-%d.json' % os.getpid())) if state_root
                 else self.root / ("var/codex-runtime/toolkit-spring-%d.json" % os.getpid()))
        if state.exists():
            raise RuntimeError("INVOCATION_STATE_COLLISION")
        import awx_mcp_stdio_server as bridge
        quote = lambda value: "'" + str(value).replace("'", "''") + "'"
        options = "Port=" + str(port) + ";ManagementPort=" + str(port+1) + ";FixedPorts=$true;StatePath=" + quote(state) + ";ReadyTimeoutSeconds=120"
        if state_root:
            local = local_state_root(self.root,state_root)
            options += ";BuildHostId=" + quote(host_facts(self.root)['hostId'])
            for key,relative in [('OutDir','logs'),('ProjectCacheDir','gradle-project'),('GradleUserHome','gradle-user')]:
                options += ";" + key + "=" + quote(local/relative)
        else:
            options += ";BuildHostId='desktop-runtime-toolkit'"
        script = "$launchArgs=@{" + options + "}; & " + quote(self.root / "scripts/chat_ui_vibe_listener.ps1") + " @launchArgs"
        script += "; if (-not $?) { exit 1 }; if (-not (Test-Path -LiteralPath " + quote(state) + ")) { exit 2 }; . " + quote(self.root / "scripts/chat_ui_vibe_lifecycle.ps1")
        script += "; $m = Get-Content -LiteralPath " + quote(state) + " -Raw | ConvertFrom-Json; $v = Test-AwxOwnedRuntimeIdentity -Manifest $m -Root " + quote(self.root) + "; if (-not $v.ok) { exit 3 }"
        script = "$env:SERVER_ADDRESS='127.0.0.1'; $env:MANAGEMENT_SERVER_ADDRESS='127.0.0.1'; " + script
        import base64
        encoded = base64.b64encode(("$null=[Console]::ReadLine(); " + script).encode("utf-16-le")).decode("ascii")
        self.spring_job = bridge.OwnedWorker(["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded], capture=False)
        try:
            # Release the launch barrier only after its owned Job is assigned.
            self.spring_job.process.communicate("\n", timeout=240)
            if state.is_file():
                self.spring_state = state
            if self.spring_job.process.returncode != 0 or self.spring_state is None:
                raise RuntimeError("SPRING_OWNERSHIP_EVIDENCE_MISSING")
        except BaseException:
            self.spring_job.close()
            self.spring_job = None
            self.spring_state = None
            raise

    def close_pipe(self):
        if not self.owned:
            return
        process = self.owned.process
        try:
            try:
                process.stdin.close()
            except OSError:
                # An exited peer may reject buffered stdin during close. The
                # owned-process proof below still decides whether stop succeeded.
                pass
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                pass
        finally:
            closed = self.owned.close()
            for reader in (self.reader, self.stderr_reader):
                if reader:
                    reader.join(timeout=2)
            for stream in (process.stdout, process.stderr):
                stream.close()
            if closed:
                self.owned = None
                self.proof = {}
            else:
                raise RuntimeError("BRIDGE_STOP_FAILED")

    def stop(self):
        pipe_failure = False
        try:
            self.close_pipe()
        except Exception:
            pipe_failure = True
        if self.spring_state:
            # Existing provenance/lineage/start-time guard, never PID-only kill.
            import base64
            quote = lambda value: "'" + str(value).replace("'", "''") + "'"
            script = ". " + quote(self.root / "scripts/chat_ui_vibe_lifecycle.ps1") + "; $m = Get-Content -LiteralPath " + quote(self.spring_state) + " -Raw | ConvertFrom-Json; $r = Stop-AwxOwnedRuntime -Manifest $m -Root " + quote(self.root) + "; if (-not $r.ok) { exit 1 }"
            encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
            try:
                completed = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded],
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=40,
                                           creationflags=subprocess.CREATE_NO_WINDOW)
            finally:
                # Job ownership was established before the launcher ran. Even a
                # timed-out guard must not abandon this invocation's children.
                if self.spring_job is not None:
                    self.spring_job.close()
                    self.spring_job = None
            if completed.returncode:
                raise RuntimeError("SPRING_OWNERSHIP_STOP_REJECTED")
            self.spring_state = None
        elif self.spring_job is not None:
            self.spring_job.close()
            self.spring_job = None
        if pipe_failure:
            raise RuntimeError("BRIDGE_STOP_FAILED")
        snapshot = self.status()
        return {**snapshot, "command": "stop", "FULL_LOAD_READY": False, "readinessScope": "current_invocation",
                "invocationFullLoadReady": False, "reasonCode": "invocation_owned_resources_stopped"}

    def command(self, action):
        with self.lock:
            if action == "start":
                try:
                    self.start_spring()
                    return {**self.smoke(), "command": action}
                except Exception:
                    try:
                        self.stop()
                    except Exception:
                        self.last_cleanup_error = "OWNED_CLEANUP_FAILED"
                    raise
            if action == "smoke":
                return {**self.smoke(), "command": action}
            if action == "status":
                return self.status()
            if action == "stop":
                return self.stop()
            if action == "doctor":
                return self.doctor()
            raise ValueError("unknown_runtime_command")


def grok_review_change(payload: dict[str, Any]) -> dict[str, Any]:
    """Grok readiness only until isolation and included-only usage are proven."""
    from awx_grok_review_adapter import installed_adapter
    return installed_adapter().run(payload)


def kimi_review_change(payload: dict[str, Any]) -> dict[str, Any]:
    """Registration seam only; fails closed until a verified pin exists."""
    from awx_kimi_review_adapter import installed_adapter
    return installed_adapter().run(payload)


def codex_review_change(payload: dict[str, Any]) -> dict[str, Any]:
    """One bounded personal review; direct review needs stdio worker ownership."""
    from awx_codex_review_adapter import installed_adapter
    return installed_adapter().run(payload)


def device_work(payload: dict[str, Any]) -> dict[str, Any]:
    """Cooperative task artifacts; source mutation still uses its existing guard."""
    from awx_device_work import device_work as coordinate
    return coordinate({k: v for k, v in payload.items() if k not in {"requestId", "sessionId", "nodeRole"}})


def main() -> int:
    parser = argparse.ArgumentParser(description="AWX MCP-style toolbox")
    parser.add_argument("tool", help="Tool name, for example archive_search")
    parser.add_argument("--input-json", default="", help="JSON payload. Defaults to stdin; use '-' for stdin.")
    parser.add_argument("--runtime-session", action="store_true", help="Keep invocation-owned runtime pipes until EOF; accept JSON command lines")
    parser.add_argument("--input-base64", default="", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.input_base64:
        import base64
        args.input_json = base64.b64decode(args.input_base64, validate=True).decode("utf-8")
    if args.runtime_session or args.tool in {"start", "status", "smoke", "stop", "doctor"}:
        initial = load_payload(args.input_json) if args.input_json or not args.runtime_session else {}
        toolkit = RuntimeToolkit(initial)
        failed = False
        try:
            commands = sys.stdin if args.runtime_session else [json.dumps({"command": args.tool})]
            for line in commands:
                try:
                    action = json.loads(line).get("command", "status")
                    result = toolkit.command(action)
                except Exception as exc:
                    reason = str(exc) if re.fullmatch(r"[A-Z_]{3,80}", str(exc)) else "RUNTIME_COMMAND_FAILED"
                    result = {"ok": False, "FULL_LOAD_READY": False, "reasonCode": reason}
                    failed = True
                if not args.runtime_session:
                    toolkit.stop()
                    result["readyDuringInvocation"] = result.get("invocationFullLoadReady", False)
                    result["FULL_LOAD_READY"] = False
                    result["readinessScope"] = "current_invocation"
                    result["invocationFullLoadReady"] = False
                    result["sessionClosed"] = True
                print(json.dumps(redact(result), ensure_ascii=True), flush=True)
        finally:
            toolkit.stop()
        return 1 if failed else 0

    started = time.monotonic()
    try:
        payload = load_payload(args.input_json)
        requested_tool = args.tool.strip()
        tool = TOOL_ALIASES.get(requested_tool, requested_tool)
        handlers = {
            "device_work": device_work,
            "grok_review_change": grok_review_change,
            "kimi_review_change": kimi_review_change,
            "codex_review_change": codex_review_change,
            "source_scan": source_scan,
            "patch_plan": patch_plan,
            "patch_render": patch_render,
            "archive_index_build": archive_index_build,
            "archive_search": archive_search,
            "archive_restore": archive_restore,
            "boot_verify": boot_verify,
            "build_error_mine": build_error_mine,
            "run_pipeline": run_pipeline,
            "external_evidence_intake": external_evidence_intake,
            "external_evidence_audit": external_evidence_audit,
            "producer_command_plan": producer_command_plan,
            "producer_kit_export": producer_kit_export,
            "desktop_dispatch_packet": desktop_dispatch_packet,
            "desktop_control_loop": desktop_control_loop,
            "smb_decommission_debug_probe": smb_decommission_debug_probe,
            "peer_evidence_bus": peer_evidence_bus,
            "web_probe_refresh": web_probe_refresh,
            "harmony_scan": harmony_scan,
            "agent_db_snapshot": agent_db_snapshot,
            "trace_snapshot_probe": trace_snapshot_probe,
            "supabase_context_probe": supabase_context_probe,
            "supabase_schema_snapshot": supabase_schema_snapshot,
            "supabase_schema_snapshot_import": supabase_schema_snapshot_import,
            "guard_status": guard_status,
            "session_evidence": session_evidence,
            "schema": schema,
        }
        if tool not in handlers:
            raise ValueError(f"unknown_tool:{tool}")
        result = handlers[tool](payload)
        finalize(tool, payload, result, started)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 0
    except Exception as exc:
        result = {
            "schemaVersion": SCHEMA_VERSION,
            "ok": False,
            "toolName": args.tool,
            "decision": "error",
            "failReason": exc.__class__.__name__,
            "message": safe_message(str(exc), 240),
        }
        finalize(args.tool, {}, result, started)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 1


def load_payload(raw_arg: str) -> dict[str, Any]:
    raw = raw_arg
    if not raw or raw.strip() == "-":
        stdin_buffer = getattr(sys.stdin, "buffer", None)
        raw = (
            stdin_buffer.read().decode("utf-8-sig")
            if stdin_buffer is not None
            else sys.stdin.read()
        )
    raw = raw.lstrip("\ufeff")
    if not raw.strip():
        return {}
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("input_must_be_object")
    return data


def finalize(tool: str, payload: dict[str, Any], result: dict[str, Any], started: float) -> None:
    result.setdefault("schemaVersion", SCHEMA_VERSION)
    result.setdefault("ok", True)
    result.setdefault("toolName", tool)
    result.setdefault("requestId", safe_scalar(payload.get("requestId", ""), 96))
    result.setdefault("sessionId", safe_scalar(payload.get("sessionId", ""), 96))
    result.setdefault("nodeRole", safe_scalar(payload.get("nodeRole", "unknown"), 48))
    result.setdefault("inputHash", stable_hash(redact(payload)))
    result.setdefault("outputCount", output_count(result))
    result.setdefault("elapsedMs", max(0, int((time.monotonic() - started) * 1000)))
    result.setdefault("decision", "ok" if result.get("ok", True) else "failed")
    result.setdefault("failReason", "")
    verify_log = payload.get("verify_log")
    if isinstance(verify_log, str) and verify_log.strip():
        verify_log_path = resolve_path(verify_log)
        result.setdefault("verifyLog", str(verify_log_path))
        append_verify_log(verify_log_path, result)
    audit_log = payload.get("audit_log")
    if isinstance(audit_log, str) and audit_log.strip():
        append_audit(Path(audit_log), result)


def deployment_public_base_url() -> str:
    return safe_scalar(os.environ.get("APP_PUBLIC_BASE_URL") or os.environ.get("PUBLIC_BASE_URL") or "", 240).strip()


def source_scan(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    roots = {
        "mainJava": root / "main" / "java",
        "mainResources": root / "main" / "resources",
        "testJava": root / "src" / "test" / "java",
        "appJavaClean": root / "app" / "src" / "main" / "java_clean",
        "appResources": root / "app" / "src" / "main" / "resources",
    }
    secret_roots = [
        roots["mainJava"],
        roots["mainResources"],
        roots["appJavaClean"],
        roots["appResources"],
        root / "scripts",
    ]
    apikey_refs = apikey_env_refs(root)
    out = {
        "rootHash": stable_hash(str(root)),
        "rootLength": len(str(root)),
        "activeSourceSets": {name: scan_tree(path) for name, path in roots.items()},
        "inactiveSignals": {
            "appSrcMainJavaExists": path_exists(root / "app" / "src" / "main" / "java"),
            "projectSrcMainJavaExists": path_exists(root / "project" / "src" / "main" / "java"),
            "legacyModuleDirs": sorted(
                name for name in ("demo-1", "lms-core", "project") if path_exists(root / name)
            ),
        },
        "patchDrop": patchdrop_summary(root / "__patch_drop__"),
        "secretPatternHits": sum(secret_hit_count(path, max_files=3000, skip_tests=True) for path in secret_roots),
        "secretEnvRefs": sorted(
            {ref for path in secret_roots for ref in env_refs(path, max_files=3000, skip_tests=True)} | set(apikey_refs)
        ),
        "apikeyEnvRefs": apikey_refs,
        "decision": "read_only_probe",
    }
    return out


def guard_status(payload: dict[str, Any]) -> dict[str, Any]:
    """Shared task-entry preflight: device bus, active journals, lease state,
    installed agent adapters, status-doc hash, common-guard tool presence."""
    root = resolve_path(payload.get("root") or ".")
    import agent_preflight
    return agent_preflight.collect(root)


def session_evidence(payload: dict[str, Any]) -> dict[str, Any]:
    """Read-only-ish session evidence: index (writes only the derived index
    under data/agent-handoff/session-evidence/), parents, search, sessions."""
    import contextlib
    import io
    import awx_session_evidence as se
    root = resolve_path(payload.get("root") or ".")
    home = Path(safe_scalar(payload.get("codexHome"), 400) or
                os.environ.get("CODEX_HOME") or str(se.CODEX_HOME))
    action = safe_scalar(payload.get("action") or "sessions", 32)
    buffer = io.StringIO()
    try:
        with contextlib.redirect_stdout(buffer):
            if action == "index":
                code = se.cmd_index(root, home)
            elif action == "parents":
                code = se.cmd_parents(root, safe_scalar(payload.get("session"), 160))
            elif action == "search":
                code = se.cmd_search(
                    root, safe_scalar(payload.get("term"), 200),
                    session=safe_scalar(payload.get("session"), 160) or None,
                    since_ms=payload.get("sinceMs") if isinstance(payload.get("sinceMs"), int) else None,
                    max_files=min(int(payload.get("maxFiles") or 64), 256),
                    max_file_mb=min(int(payload.get("maxFileMb") or 32), 64))
            elif action == "sessions":
                code = se.cmd_sessions(
                    root, source=safe_scalar(payload.get("source"), 80) or None,
                    children_of=safe_scalar(payload.get("childrenOf"), 160) or None,
                    stale_days=payload.get("staleDays") if isinstance(payload.get("staleDays"), (int, float)) else None,
                    limit=min(int(payload.get("limit") or 50), 200))
            else:
                return {"ok": False, "decision": "error", "failReason": "unknown_action",
                        "allowedActions": ["index", "parents", "search", "sessions"]}
    except Exception as exc:
        return {"ok": False, "decision": "error", "failReason": exc.__class__.__name__,
                "message": safe_message(str(exc), 240)}
    try:
        body = json.loads(buffer.getvalue().strip().splitlines()[-1])
    except (ValueError, IndexError):
        body = {"raw": safe_message(buffer.getvalue(), 2000)}
    return {"ok": code == 0, "action": action, "result": body}


def peer_tri_perspective_query_packet(target_metric: str) -> dict[str, Any]:
    return {
        "schemaVersion": "awx.mcp.tri_perspective_query_packet.v1",
        "targetMetric": safe_scalar(target_metric, 64),
        "executionMode": "sequential-existing-runtime",
        "candidateLimit": 3,
        "activeCandidateLimit": 1,
        "modelCallLimitPerCandidate": 3,
        "roles": [
            {
                "name": "SUPPORT",
                "purpose": "prove-smallest-source-backed-benefit",
                "requiredFields": [
                    "benefitClaim",
                    "existingOwner",
                    "activeSourceSet",
                    "evidenceIds",
                    "redTest",
                    "proofCommand",
                    "groundingScore",
                ],
            },
            {
                "name": "FALSIFY",
                "purpose": "find-duplicate-risk-or-decisive-counter-evidence",
                "requiredFields": [
                    "disproofClaim",
                    "duplicateOwner",
                    "conflictingEvidenceIds",
                    "failureClass",
                    "falsifyingTest",
                    "proofCommand",
                    "groundingScore",
                ],
            },
            {
                "name": "HOLD",
                "purpose": "adjudicate-owned-evidence-without-new-claims",
                "requiredFields": [
                    "decision",
                    "confidence",
                    "decisiveEvidenceIds",
                    "supportGrounding",
                    "falsifyGrounding",
                    "scoreGap",
                    "reasonCode",
                    "nextSingleAction",
                ],
            },
        ],
        "promotionGate": {
            "decision": "APPLY",
            "minimumGrounding": 0.70,
            "minimumScoreGap": 0.05,
            "safetyGate": "PASS",
            "evidenceStatus": "SUFFICIENT",
            "activeSourceOwnerRequired": True,
            "focusedRedTestRequired": True,
            "externalCredentialsAllowed": False,
            "advisoryOnly": True,
        },
        "evidenceContract": {
            "rawPromptStored": False,
            "rawQueryStored": False,
            "rawPathStored": False,
            "artifactByReference": True,
            "hashOnlyIdentifiers": True,
        },
        "rejectedPatterns": [
            "dom-response-extraction",
            "anti-detection",
            "browser-profile-automation",
            "public-tunnel-write-default",
            "unrestricted-shell",
            "duplicate-agent-broker",
            "prompt-builder-bypass",
        ],
    }


def peer_evidence_bus(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    node_role = safe_scalar(payload.get("nodeRole", "desktop"), 32).lower() or "desktop"
    target_metric = safe_scalar(payload.get("targetMetric") or payload.get("target_metric") or "harmony", 64)
    target_metric = re.sub(r"[^a-zA-Z0-9_.-]+", "-", target_metric.strip().lower()).strip("-") or "harmony"
    prompt_pack = peer_prompt_pack_summary(root)
    patchdrop = patchdrop_summary(root / "__patch_drop__")
    peer_identity = peer_identity_summary(payload.get("peers"))
    web_probe_ledger = peer_web_probe_ledger(target_metric)
    web_probe_refresh_packet = peer_web_probe_refresh_packet(target_metric, web_probe_ledger)
    tri_perspective_query_packet = peer_tri_perspective_query_packet(target_metric)

    evidence_lanes = [
        peer_lane(
            "desktop",
            "canonical-source-owner",
            "source_scan,harmony_scan,desktop_control_loop",
            "ready-to-run",
            "run-desktop-control-loop",
            read_only=False,
        ),
        peer_lane(
            "macmini",
            "patch-producer",
            "node_smoke,PatchDrop-v3-sidecars,external_evidence_audit",
            "evidence_needed",
            "collect-macmini-patchdrop-v3-proof",
        ),
        peer_lane(
            "notebook",
            "supporting-probe",
            "node_smoke,review-notes,PatchDrop-v3-sidecars",
            "evidence_needed",
            "collect-notebook-patchdrop-v3-proof",
        ),
        peer_lane(
            "supabase",
            "read-only-db-evidence",
            "supabase_context_probe,supabase_schema_snapshot,get_advisors",
            "evidence_needed",
            "collect-supabase-live-proof",
            required_env=["SUPABASE_PROJECT_REF"],
            required_tools=["supabase_context_probe", "supabase_schema_snapshot"],
        ),
        peer_lane(
            "browser",
            "dom-runtime-proof",
            "localhost-or-public-DOM-smoke,screenshot-if-needed",
            "evidence_needed",
            "collect-browser-dom-proof",
        ),
        peer_lane(
            "computer",
            "gui-proof-only",
            "Computer-Use-visible-state,not-terminal-automation",
            "evidence_needed",
            "collect-computer-use-gui-proof",
        ),
        peer_lane(
            "superpowers",
            "process-guard",
            "using-superpowers,TDD,verification-before-completion",
            "ready-to-run",
            "keep-skill-proof-subordinate-to-repo-evidence",
        ),
    ]
    next_actions = [
        safe_scalar(lane.get("nextAction", ""), 120)
        for lane in evidence_lanes
        if lane.get("status") == "evidence_needed" and safe_scalar(lane.get("nextAction", ""), 120)
    ]
    if not prompt_pack["present"]:
        next_actions.insert(0, "build-peer-evidence-bus-prompt-pack")
    try:
        raw_secret_hits = int(prompt_pack.get("rawSecretPatternHits", 0) or 0)
    except Exception:
        raw_secret_hits = 0
    evidence_needed = [
        "Supabase project-scoped MCP/CLI proof before DB-backed claims",
        "Browser DOM proof before claiming rendered UX success",
        "Computer Use GUI proof only when visible Windows app state matters",
        "Mac mini/Notebook evidence through PatchDrop, not direct canonical source edits",
    ]
    if not prompt_pack["present"]:
        evidence_needed.append("demo1_claude_peers_web_probe_agent_upgrade_5h prompt pack")
    return {
        "schemaVersion": "awx.mcp.peer_evidence_bus.v1",
        "ok": True,
        "rootHash": stable_hash(str(root)),
        "rootLength": len(str(root)),
        "nodeRole": node_role,
        "targetMetric": target_metric,
        "promptPack": prompt_pack,
        "patchDrop": {
            "exists": bool(patchdrop.get("exists")),
            "topLevelPatchCount": int(patchdrop.get("topLevelPatchCount", 0) or 0),
            "pendingProducerCount": int(patchdrop.get("pendingProducerCount", 0) or 0),
            "pendingPatchCount": int(patchdrop.get("topLevelPatchCount", 0) or 0),
            "pendingV3Count": int(patchdrop.get("pendingV3Count", 0) or 0),
            "nestedProducerBundleCount": int(patchdrop.get("pendingProducerCount", 0) or 0),
            "evidenceMode": "manual-supporting",
            "producerBundlesRequired": False,
        },
        "rawSecretPatternHits": raw_secret_hits,
        "peerIdentitySummary": peer_identity,
        "webProbeLedger": web_probe_ledger,
        "webProbeRefreshPacket": web_probe_refresh_packet,
        "triPerspectiveQueryPacket": tri_perspective_query_packet,
        "claudePeersMapping": {
            "list_peers": "peer_evidence_bus.evidenceLanes",
            "resolve_peer": "role/topic/sourceIsolation metadata",
            "send_message": "desktop_dispatch_packet or PatchDrop producer command files",
            "set_summary": "redacted TraceStore/DebugEventStore/handoff summary artifacts",
            "check_messages": "external_evidence_audit, PatchDrop inventory, source lease state",
            "close_conversation": "Desktop apply/reject decision plus rerun completion audit",
            "heartbeat": "node_smoke plus sourceIsolation guard",
        },
        "claudePeersProtocol": {
            "referenceTools": [
                "list_peers",
                "resolve_peer",
                "send_message",
                "close_conversation",
                "set_summary",
                "check_messages",
            ],
            "brokerContract": {
                "scope": "localhost-only reference pattern, not installed or started by demo-1",
                "defaultPort": 7899,
                "portEnvName": "CLAUDE_PEERS_PORT",
                "dbEnvName": "CLAUDE_PEERS_DB",
                "messageRetention": "undelivered messages remain queued until an explicit check",
            },
            "codexDeliveryPolicy": {
                "mode": "manual-queue-preserving",
                "manualCheckTool": "check_messages",
                "reason": "Codex runtimes may not surface channel notifications into the model context",
            },
            "conversationClosure": {
                "closeTool": "close_conversation",
                "requiresMutualClose": True,
                "reopenRequiresExplicitFlag": True,
                "demo1Mapping": "Desktop apply/reject decision plus completion audit rerun",
            },
            "messageSafety": {
                "redactedSummariesOnly": True,
                "rawMessageBodiesStored": False,
                "directCanonicalSourceEdit": False,
                "dispatchViaPatchDropOrCommandPacket": True,
            },
        },
        "safePeerIdentityContract": {
            "sourceFields": [
                "id",
                "logical_name",
                "cwd",
                "repo_name",
                "repo_root",
                "branch",
                "model",
                "summary",
                "registered_at",
                "last_seen",
            ],
            "safeFields": [
                "peerIdHash",
                "logicalName",
                "role",
                "repoName",
                "branch",
                "modelFamily",
                "summaryHash",
                "summaryLength",
                "logicalNameCollisionCount",
                "cwdHash",
                "repoRootHash",
                "lastSeenAgeClass",
            ],
            "identityResolution": {
                "logicalNamePreferred": True,
                "ephemeralIdMayRotate": True,
                "resolvePeerBeforeSend": True,
                "duplicateLogicalNameRequiresPeerId": True,
                "ambiguousNameResolution": "fail-closed",
                "fallback": "role-plus-topic-with-hash",
            },
            "messageEnvelope": {
                "fromKinds": ["peer", "system"],
                "storeRawText": False,
                "storeMessageHash": True,
                "storeMessageLength": True,
                "storeReadReceiptState": True,
            },
            "forbiddenFields": [
                "rawCwd",
                "rawRepoRoot",
                "rawMessageText",
                "rawSummary",
                "rawEnv",
                "rawPrompt",
                "rawToken",
                "rawPath",
            ],
            "redactionMode": "hash-count-and-allowlisted-labels-only",
        },
        "sourceIntegration": {
            "existingSeams": [
                "source_scan",
                "harmony_scan",
                "desktop_control_loop",
                "external_evidence_audit",
                "TraceStore",
                "DebugEventStore",
                "PromptBuilder.build(PromptContext)",
            ],
            "mode": "read-only orchestration evidence bus",
            "mutationBoundary": "Desktop canonical source only after tests and Gradle proof",
        },
        "evidenceLanes": evidence_lanes,
        "nextActions": dedupe_scalar_list(next_actions),
        "evidence_needed": dedupe_scalar_list(evidence_needed),
        "outputCount": len(evidence_lanes),
        "decision": "peer_evidence_bus",
    }


def peer_prompt_pack_summary(root: Path) -> dict[str, Any]:
    prompt_id = "demo1_claude_peers_web_probe_agent_upgrade_5h"
    paths = {
        "system": root / "agent-prompts" / "agents" / prompt_id / "system_ko.md",
        "meta": root / "agent-prompts" / "agents" / prompt_id / "meta.yaml",
        "output": root / "agent-prompts" / "out" / f"{prompt_id}.prompt",
    }
    texts: dict[str, str] = {}
    file_hashes: dict[str, str] = {}
    missing: list[str] = []
    raw_secret_hits = 0
    for name, path in paths.items():
        if not path_is_file(path):
            missing.append(name)
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            missing.append(name)
            continue
        texts[name] = text
        file_hashes[name] = sha256_file(path)
        raw_secret_hits += high_conf_secret_count(text)
    combined = "\n".join(texts.values())
    required_signals = {
        "peerEvidenceBus": "Peer Evidence Bus" in combined,
        "webProbeLedger": "Web Probe Ledger" in combined,
        "webProbeRefreshPacket": "webProbeRefreshPacket" in combined or "Web Probe Refresh Packet" in combined,
        "supabaseLane": "Supabase" in combined,
        "browserLane": "Browser" in combined,
        "computerLane": "Computer Use" in combined or "Computer" in combined,
        "patchDropLane": "PatchDrop" in combined,
    }
    return {
        "id": prompt_id,
        "present": not missing and all(required_signals.values()),
        "fileCount": len(file_hashes),
        "fileHashes": file_hashes,
        "missing": missing,
        "requiredSignals": required_signals,
        "missingSignals": sorted(key for key, value in required_signals.items() if not value),
        "rawSecretPatternHits": raw_secret_hits,
    }


def peer_identity_summary(raw_peers: Any) -> dict[str, Any]:
    peers = raw_peers if isinstance(raw_peers, list) else []
    safe_names: list[str] = []
    peer_hashes: list[str] = []
    summary_hashes: list[str] = []
    for item in peers:
        if not isinstance(item, dict):
            continue
        logical_name = (
            safe_scalar(item.get("logical_name") or item.get("logicalName") or item.get("name") or "", 80)
            .strip()
            .lower()
        )
        logical_name = re.sub(r"[^a-z0-9_.-]+", "-", logical_name).strip("-")
        if logical_name:
            safe_names.append(logical_name)
        peer_id = safe_scalar(item.get("id") or item.get("peerId") or item.get("peer_id") or "", 160).strip()
        if peer_id:
            peer_hashes.append(stable_hash(peer_id)[:16])
        summary = safe_scalar(item.get("summary") or "", 1000).strip()
        if summary:
            summary_hashes.append(stable_hash(summary)[:16])
    counts: dict[str, int] = {}
    for name in safe_names:
        counts[name] = counts.get(name, 0) + 1
    ambiguous = sorted(name for name, count in counts.items() if count > 1)
    return {
        "peerCount": len([item for item in peers if isinstance(item, dict)]),
        "logicalNameCount": len(counts),
        "logicalNameCollisionCount": len(ambiguous),
        "ambiguousLogicalNames": ambiguous,
        "peerIdRequiredFor": ambiguous,
        "safeToResolveByLogicalNameOnly": not ambiguous,
        "ambiguousNameResolution": "fail-closed" if ambiguous else "not-applicable",
        "peerIdHashes": sorted(set(peer_hashes)),
        "summaryHashes": sorted(set(summary_hashes)),
        "rawIdentityStored": False,
        "rawPathStored": False,
        "rawSummaryStored": False,
    }


def peer_web_probe_ledger(target_metric: str) -> dict[str, Any]:
    sources = [
        {
            "sourceUrl": "https://modelcontextprotocol.io/docs/concepts/tools",
            "sourceKind": "official_doc",
            "contractExtract": "MCP tools are model-controlled operations and need clear tool contracts.",
            "contractImpact": "require_gate",
        },
        {
            "sourceUrl": "https://modelcontextprotocol.io/docs/concepts/transports",
            "sourceKind": "official_doc",
            "contractExtract": "Stdio MCP uses newline-delimited JSON-RPC over a client-launched subprocess.",
            "contractImpact": "allow",
        },
        {
            "sourceUrl": "https://supabase.com/docs/guides/ai-tools/mcp",
            "sourceKind": "official_doc",
            "contractExtract": "Supabase MCP can be scoped to project_ref and read-only mode.",
            "contractImpact": "require_gate",
        },
        {
            "sourceUrl": "https://supabase.com/docs/guides/api/securing-your-api",
            "sourceKind": "official_doc",
            "contractExtract": "Data API exposure needs grants and RLS evidence before access claims.",
            "contractImpact": "require_gate",
        },
        {
            "sourceUrl": "https://supabase.com/changelog",
            "sourceKind": "official_doc",
            "contractExtract": "Supabase behavior is change-prone; refresh changelog before Supabase patches.",
            "contractImpact": "evidence_only",
        },
    ]
    safe_sources = []
    for source in sources:
        url = safe_scalar(source.get("sourceUrl"), 240)
        safe_sources.append(
            {
                "sourceUrl": url,
                "sourceHash": stable_hash(url)[:16],
                "sourceKind": safe_scalar(source.get("sourceKind"), 40),
                "contractExtract": safe_scalar(source.get("contractExtract"), 180),
                "contractImpact": safe_scalar(source.get("contractImpact"), 40),
                "rawContentStored": False,
                "fullArticleStored": False,
            }
        )
    return {
        "mode": "web-probe-first",
        "targetMetric": safe_scalar(target_metric, 64),
        "sourceCount": len(safe_sources),
        "sources": safe_sources,
        "allowedDomains": [
            "modelcontextprotocol.io",
            "supabase.com",
            "docs.anthropic.com",
            "github.com",
        ],
        "queryHash": stable_hash("demo1 claude peers web probe harmony supabase mcp")[:16],
        "queryLength": len("demo1 claude peers web probe harmony supabase mcp"),
        "rawQueryStored": False,
        "rawContentStored": False,
        "requiresRefreshBeforePatch": True,
        "refreshCommand": "refresh Web Probe Ledger from primary sources before source changes that depend on external contracts",
    }


def peer_web_probe_refresh_packet(target_metric: str, ledger: dict[str, Any]) -> dict[str, Any]:
    sources = ledger.get("sources") if isinstance(ledger.get("sources"), list) else []
    fetch_targets = []
    for item in sources:
        if not isinstance(item, dict):
            continue
        source_url = safe_scalar(item.get("sourceUrl"), 240)
        if not source_url:
            continue
        parsed = urllib.parse.urlparse(source_url)
        host = parsed.netloc.lower()
        markdown_url = source_url + ".md" if not source_url.endswith(".md") else source_url
        method = "markdown-fetch" if host.endswith("supabase.com") else "browser-or-markdown-fetch"
        required_signals = ["official-doc", "contract"]
        if "supabase.com" in host and "mcp" in parsed.path:
            required_signals = ["project_ref", "read_only", "features"]
        elif "supabase.com" in host and "securing-your-api" in parsed.path:
            required_signals = ["RLS", "GRANT"]
        elif "supabase.com" in host and "changelog" in parsed.path:
            required_signals = ["breaking-change", "Data API"]
            markdown_url = "https://supabase.com/changelog.md"
        elif "modelcontextprotocol.io" in host and "tools" in parsed.path:
            required_signals = ["inputSchema", "outputSchema", "structuredContent"]
        elif "modelcontextprotocol.io" in host and "transports" in parsed.path:
            required_signals = ["stdio", "streamable HTTP", "JSON-RPC"]
        fetch_targets.append(
            {
                "sourceUrl": source_url,
                "sourceHash": safe_scalar(item.get("sourceHash"), 40),
                "markdownUrl": markdown_url,
                "host": host,
                "method": method,
                "requiredSignals": required_signals,
                "contractImpact": safe_scalar(item.get("contractImpact"), 40),
                "rawContentStored": False,
                "fullArticleStored": False,
            }
        )
    return {
        "schemaVersion": "awx.web_probe.refresh_packet.v1",
        "mode": "read-only-official-sources",
        "targetMetric": safe_scalar(target_metric, 64),
        "officialSourceCount": len(fetch_targets),
        "fetchTargets": fetch_targets,
        "supabaseMcpGate": {
            "requiredEnv": ["SUPABASE_PROJECT_REF"],
            "manualAuthSensitiveEnvRefs": ["SUPABASE_ACCESS_TOKEN"],
            "endpointTemplate": (
                "https://mcp.supabase.com/mcp?"
                "project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs"
            ),
            "featureGroups": ["database", "debugging", "docs"],
            "readOnly": True,
            "mutationAllowed": False,
            "storeAccessToken": False,
            "requiredTools": ["execute_sql", "get_advisors", "search_docs"],
        },
        "browserProbeGate": {
            "target": "official-docs-or-localhost-proof-only",
            "storeRawUrl": False,
            "storeScreenshotPath": False,
            "storeDomSnapshot": False,
            "storeExtractHashOnly": True,
        },
        "importContract": {
            "storeExtractsOnly": True,
            "maxExtractChars": 240,
            "allowedImpacts": ["allow", "block", "require_gate", "evidence_only"],
            "requiresSourceHash": True,
            "requiresFetchedAt": True,
            "forbiddenFields": ["rawHtml", "rawMarkdown", "rawToken", "rawCookie", "rawScreenshotPath"],
        },
        "rawContentStored": False,
        "rawQueryStored": False,
        "mutationAllowed": False,
    }


def web_probe_refresh(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    target_metric = safe_scalar(payload.get("targetMetric") or payload.get("target_metric") or "harmony", 64)
    target_metric = re.sub(r"[^a-zA-Z0-9_.-]+", "-", target_metric.strip().lower()).strip("-") or "harmony"
    packet = payload.get("refreshPacket") if isinstance(payload.get("refreshPacket"), dict) else None
    if packet is None:
        packet = peer_evidence_bus({"root": str(root), "nodeRole": payload.get("nodeRole", "desktop"), "targetMetric": target_metric}).get(
            "webProbeRefreshPacket",
            {},
        )
    targets = packet.get("fetchTargets") if isinstance(packet.get("fetchTargets"), list) else []
    source_bodies = payload.get("sourceBodies") if isinstance(payload.get("sourceBodies"), dict) else {}
    max_bytes = bounded_int(payload.get("maxBytes") or payload.get("max_bytes"), 200_000, 1024, 1_000_000)
    timeout_seconds = bounded_int(payload.get("timeoutSeconds") or payload.get("timeout_seconds"), 20, 3, 60)
    allowed_hosts = {
        "modelcontextprotocol.io",
        "supabase.com",
        "docs.anthropic.com",
        "github.com",
        "raw.githubusercontent.com",
    }
    rows: list[dict[str, Any]] = []
    raw_secret_hits = 0
    fetched_content_secret_hits = 0
    fetched_count = 0
    failed_count = 0
    skipped_count = 0
    for target in targets:
        if not isinstance(target, dict):
            continue
        source_url = safe_scalar(target.get("sourceUrl"), 260)
        markdown_url = safe_scalar(target.get("markdownUrl"), 280)
        parsed = urllib.parse.urlparse(source_url)
        host = parsed.netloc.lower()
        required_signals = [
            safe_scalar(item, 80)
            for item in target.get("requiredSignals", [])
            if safe_scalar(item, 80)
        ] if isinstance(target.get("requiredSignals"), list) else []
        row = {
            "sourceUrl": source_url,
            "sourceHash": stable_hash(source_url)[:16],
            "host": host,
            "method": safe_scalar(target.get("method") or "markdown-fetch", 80),
            "contractImpact": safe_scalar(target.get("contractImpact"), 40),
            "requiredSignalCount": len(required_signals),
            "requiredSignalHits": 0,
            "missingSignals": required_signals,
            "statusCode": 0,
            "fetched": False,
            "fetchMode": "not-started",
            "contentHash": "",
            "contentLength": 0,
            "rawContentStored": False,
            "fullArticleStored": False,
        }
        if host not in allowed_hosts:
            row["decision"] = "skipped"
            row["disabledReason"] = "domain-not-allowed"
            skipped_count += 1
            rows.append(row)
            continue
        body = source_bodies.get(source_url) or (source_bodies.get(markdown_url) if markdown_url else "")
        status_code = 200 if body else 0
        fetch_mode = "fixture"
        fail_reason = ""
        if not body:
            body, status_code, fetch_mode, fail_reason = fetch_web_probe_content(
                [markdown_url, source_url],
                max_bytes=max_bytes,
                timeout_seconds=timeout_seconds,
            )
        row["statusCode"] = status_code
        row["fetchMode"] = fetch_mode
        if body:
            fetched_count += 1
            fetched_content_secret_hits += high_conf_secret_count(body)
            row["fetched"] = True
            row["decision"] = "fetched"
            row["contentHash"] = stable_hash(body)[:16]
            row["contentLength"] = len(body)
            body_lower = body.lower()
            missing = [signal for signal in required_signals if signal.lower() not in body_lower]
            row["missingSignals"] = missing
            row["requiredSignalHits"] = len(required_signals) - len(missing)
        else:
            failed_count += 1
            row["decision"] = "fetch_failed"
            row["failReason"] = safe_scalar(fail_reason or "empty-response", 120)
        rows.append(row)
    output_raw = payload.get("output_path") or payload.get("outputPath") or root / "var" / "codex-smoke" / "web-probe-refresh.json"
    output_candidate = Path(str(output_raw))
    output_path = resolve_path(output_candidate if output_candidate.is_absolute() else root / output_candidate)
    artifact_path = safe_relative_path(root, output_path)
    missing_signal_count = sum(len(row.get("missingSignals", [])) for row in rows)
    result = {
        "schemaVersion": "awx.web_probe.refresh.v1",
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "ok": failed_count == 0 and skipped_count == 0 and missing_signal_count == 0 and raw_secret_hits == 0,
        "decision": "web_probe_refresh",
        "mode": "read-only-official-sources",
        "targetMetric": target_metric,
        "artifactPath": artifact_path,
        "artifactPathHash": stable_hash(str(output_path)),
        "artifactPathLength": len(str(output_path)),
        "sourceCount": len(rows),
        "fetchedCount": fetched_count,
        "failedCount": failed_count,
        "skippedCount": skipped_count,
        "missingSignalCount": missing_signal_count,
        "rawSecretPatternHits": raw_secret_hits,
        "fetchedContentSecretPatternHits": fetched_content_secret_hits,
        "sources": rows,
        "rawContentStored": False,
        "rawQueryStored": False,
        "mutationAllowed": False,
        "evidence_needed": [],
    }
    if not result["ok"]:
        result["evidence_needed"] = [
            "web probe refresh did not fetch all official sources with required signals and zero secret hits"
        ]
    artifact = dict(result)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(artifact, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    result["artifactHash"] = sha256_file(output_path)
    result["artifactBytes"] = path_size(output_path)
    result["outputCount"] = len(rows)
    return result


def smb_decommission_debug_probe(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    node_role = safe_scalar(payload.get("nodeRole", "desktop"), 32).lower() or "desktop"
    output_raw = payload.get("output_dir") or payload.get("outputDir") or "var/codex-smoke/smb-decommission"
    output_candidate = Path(str(output_raw))
    output_path = resolve_path(output_candidate if output_candidate.is_absolute() else root / output_candidate)
    output_dir = safe_relative_path(root, output_path)
    script = root / "scripts" / "smb_decommission_debug_probe.ps1"
    summary_name = "smb-decommission-debug-probe.summary.json"
    events_name = "smb-decommission-debug-probe.events.ndjson"
    viewer_name = "smb-decommission-debug-probe.viewer.html"
    summary_path = output_path / summary_name
    events_path = output_path / events_name
    viewer_path = output_path / viewer_name
    base_result: dict[str, Any] = {
        "schemaVersion": "awx.mcp.smb_decommission_debug_probe.v1",
        "nodeRole": node_role,
        "mode": "desktop-only-read-only-wrapper",
        "mutationAllowed": False,
        "writeDispatch": False,
        "writeProducerKit": False,
        "rootHash": stable_hash(str(root)),
        "rootLength": len(str(root)),
        "scriptPresent": path_is_file(script),
        "scriptHash": sha256_file(script),
        "scriptLength": path_size(script),
        "outputDir": output_dir,
        "outputDirHash": stable_hash(str(output_path)),
        "outputDirLength": len(str(output_path)),
        "summaryArtifact": safe_relative_path(output_path, summary_path),
        "eventsArtifact": safe_relative_path(output_path, events_path),
        "viewerArtifact": safe_relative_path(output_path, viewer_path),
        "stdoutStored": False,
        "stderrStored": False,
        "rawSecretPatternHits": 0,
        "scriptOutputSecretPatternHits": 0,
        "artifactSecretPatternHits": 0,
        "summary": {},
        "evidence_needed": [],
    }
    if node_role != "desktop":
        return {
            **base_result,
            "ok": False,
            "decision": "smb_decommission_debug_probe_failed",
            "failReason": "unsupported-node-role",
            "evidence_needed": ["smb_decommission_debug_probe must run on Desktop source owner"],
        }
    if not path_is_file(script):
        return {
            **base_result,
            "ok": False,
            "decision": "smb_decommission_debug_probe_failed",
            "failReason": "probe-script-missing",
            "evidence_needed": ["scripts/smb_decommission_debug_probe.ps1"],
        }

    timeout_seconds = bounded_int(payload.get("timeout_sec") or payload.get("timeoutSeconds"), 180, 5, 900)
    args = [
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        str(script),
        "-Root",
        str(root),
        "-OutputDir",
        str(output_path),
        "-Topic",
        safe_scalar(payload.get("topic") or "smb-decommission", 120),
        "-AttachmentSampleCount",
        str(bounded_int(payload.get("attachment_sample_count") or payload.get("attachmentSampleCount"), 5, 0, 50)),
        "-SourceProbeSampleCount",
        str(bounded_int(payload.get("source_probe_sample_count") or payload.get("sourceProbeSampleCount"), 8, 0, 50)),
    ]
    optional_args = (
        ("-AttachmentPath", payload.get("attachment_path") or payload.get("attachmentPath")),
        ("-BrowserProofPath", payload.get("browser_proof_path") or payload.get("browserProofPath")),
        ("-ComputerProofPath", payload.get("computer_proof_path") or payload.get("computerProofPath")),
    )
    for flag, raw_value in optional_args:
        text = safe_scalar(raw_value, 500).strip()
        if text:
            args.extend([flag, text])

    try:
        completed = subprocess.run(
            args,
            cwd=str(root),
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
            check=False,
        )
        script_exit_code = int(completed.returncode)
        stdout = completed.stdout or ""
        stderr = completed.stderr or ""
    except subprocess.TimeoutExpired as exc:
        stdout = exc.stdout if isinstance(exc.stdout, str) else ""
        stderr = exc.stderr if isinstance(exc.stderr, str) else ""
        secret_hits = high_conf_secret_count(stdout + "\n" + stderr)
        return {
            **base_result,
            "ok": False,
            "decision": "smb_decommission_debug_probe_failed",
            "failReason": "probe-timeout",
            "scriptExitCode": -1,
            "stdoutHash": stable_hash(stdout),
            "stdoutLength": len(stdout),
            "stderrHash": stable_hash(stderr),
            "stderrLength": len(stderr),
            "scriptOutputSecretPatternHits": secret_hits,
            "rawSecretPatternHits": secret_hits,
            "evidence_needed": ["rerun scripts/smb_decommission_debug_probe.ps1 with a larger timeout or inspect local process hang"],
        }
    except OSError as exc:
        return {
            **base_result,
            "ok": False,
            "decision": "smb_decommission_debug_probe_failed",
            "failReason": exc.__class__.__name__,
            "scriptExitCode": -1,
            "evidence_needed": ["PowerShell runtime needed to run scripts/smb_decommission_debug_probe.ps1"],
        }

    summary = sanitize_probe_artifact_value(load_json_file(summary_path), root)
    artifact_text = "\n".join(
        path.read_text(encoding="utf-8", errors="ignore")
        for path in (summary_path, events_path, viewer_path)
        if path_is_file(path)
    )
    artifact_secret_hits = high_conf_secret_count(artifact_text)
    script_output_secret_hits = high_conf_secret_count(stdout + "\n" + stderr)
    raw_secret_hits = artifact_secret_hits + script_output_secret_hits
    ok = (
        script_exit_code == 0
        and bool(summary)
        and bool(summary.get("ok", True) if isinstance(summary, dict) else False)
        and raw_secret_hits == 0
    )
    fail_reason = ""
    evidence_needed: list[str] = []
    if script_exit_code != 0:
        fail_reason = f"probe-exit-{script_exit_code}"
        evidence_needed.append("rerun scripts/smb_decommission_debug_probe.ps1 and inspect generated summary/events artifacts")
    elif not summary:
        fail_reason = "summary-missing"
        evidence_needed.append("smb-decommission-debug-probe.summary.json")
    elif raw_secret_hits:
        fail_reason = "secret-leak-risk"
        evidence_needed.append("remove secret-like data from generated probe artifacts or script output")

    return {
        **base_result,
        "ok": ok,
        "decision": "smb_decommission_debug_probe" if ok else "smb_decommission_debug_probe_failed",
        "failReason": fail_reason,
        "scriptExitCode": script_exit_code,
        "stdoutHash": stable_hash(stdout),
        "stdoutLength": len(stdout),
        "stderrHash": stable_hash(stderr),
        "stderrLength": len(stderr),
        "scriptOutputSecretPatternHits": script_output_secret_hits,
        "artifactSecretPatternHits": artifact_secret_hits,
        "rawSecretPatternHits": raw_secret_hits,
        "summary": summary,
        "summaryArtifactHash": sha256_file(summary_path),
        "summaryArtifactBytes": path_size(summary_path),
        "eventsArtifactHash": sha256_file(events_path),
        "eventsArtifactBytes": path_size(events_path),
        "viewerArtifactHash": sha256_file(viewer_path),
        "viewerArtifactBytes": path_size(viewer_path),
        "outputCount": 1 if summary else 0,
        "evidence_needed": evidence_needed,
    }


def sanitize_probe_artifact_value(value: Any, root: Path) -> Any:
    root_variants = {str(root), root.as_posix(), str(root).replace("\\", "/")}
    path_like = re.compile(r"(?i)(?:\b[a-z]:[\\/]|\\\\)")
    if isinstance(value, dict):
        return {safe_scalar(key, 120): sanitize_probe_artifact_value(item, root) for key, item in value.items()}
    if isinstance(value, list):
        return [sanitize_probe_artifact_value(item, root) for item in value]
    if isinstance(value, str):
        text = safe_message(value, 1000)
        normalized = text.replace("\\", "/")
        if path_like.search(text) or any(root_text and root_text.replace("\\", "/") in normalized for root_text in root_variants):
            return "<redacted-path-like-value>"
        return text
    return value


def fetch_web_probe_content(urls: list[str], *, max_bytes: int, timeout_seconds: int) -> tuple[str, int, str, str]:
    seen: set[str] = set()
    last_error = ""
    for raw_url in urls:
        url = safe_scalar(raw_url, 300)
        if not url or url in seen:
            continue
        seen.add(url)
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != "https":
            last_error = "non-https-url"
            continue
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "AWX-WebProbe/1.0"})
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                raw = response.read(max_bytes + 1)
                if len(raw) > max_bytes:
                    raw = raw[:max_bytes]
                charset = response.headers.get_content_charset() or "utf-8"
                return raw.decode(charset, errors="ignore"), int(getattr(response, "status", 200) or 200), "live", ""
        except (urllib.error.URLError, OSError, TimeoutError) as exc:
            last_error = exc.__class__.__name__
    return "", 0, "live", last_error or "fetch-failed"


def safe_relative_path(root: Path, path: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return redacted_path_text(path)


def peer_lane(
    name: str,
    role: str,
    evidence: str,
    status: str,
    next_action: str,
    *,
    read_only: bool = True,
    required_env: list[str] | None = None,
    required_tools: list[str] | None = None,
) -> dict[str, Any]:
    lane = {
        "name": safe_scalar(name, 48),
        "role": safe_scalar(role, 96),
        "evidenceContract": safe_scalar(evidence, 180),
        "status": safe_scalar(status, 48),
        "nextAction": safe_scalar(next_action, 120),
        "readOnly": bool(read_only),
    }
    if required_env:
        lane["requiredEnv"] = [safe_scalar(item, 80) for item in required_env]
    if required_tools:
        lane["requiredTools"] = [safe_scalar(item, 80) for item in required_tools]
    return lane


def harmony_source_fingerprint(
    root: Path,
    contract: dict[str, Any],
    max_files: int,
) -> dict[str, Any]:
    root_rows: list[str] = []
    root_reports: list[dict[str, Any]] = []
    error_count = 0
    total_files = 0
    for source_root in contract["activeSourceRoots"]:
        source_id = source_root["id"]
        relative_root = source_root["path"]
        absolute_root = root / Path(relative_root)
        exists = path_exists(absolute_root) and absolute_root.is_dir()
        file_rows: list[str] = []
        if exists:
            try:
                candidates = sorted(
                    (path for path in absolute_root.rglob("*") if path.is_file()),
                    key=lambda path: path.relative_to(absolute_root).as_posix(),
                )
            except OSError:
                candidates = []
                error_count += 1
            if len(candidates) > max_files:
                error_count += 1
                candidates = candidates[:max_files]
            for path in candidates:
                digest = sha256_file(path)
                if not digest:
                    error_count += 1
                    continue
                relative_path = path.relative_to(absolute_root).as_posix()
                file_rows.append(f"{relative_path}\0{digest}")
        source_sha256 = hashlib.sha256(
            "\n".join(file_rows).encode("utf-8")
        ).hexdigest()
        file_count = len(file_rows)
        total_files += file_count
        root_rows.append(
            f"{source_id}\0{str(exists).lower()}\0{file_count}\0{source_sha256}"
        )
        root_reports.append(
            {
                "id": source_id,
                "path": relative_root,
                "exists": exists,
                "fileCount": file_count,
                "sha256": source_sha256,
            }
        )
    aggregate_sha256 = hashlib.sha256(
        "\n".join(root_rows).encode("utf-8")
    ).hexdigest()
    return {
        "status": HARMONY_VERIFIED_STATUS if error_count == 0 else HARMONY_BLOCKED_STATUS,
        "fileCount": total_files,
        "errorCount": error_count,
        "sha256": aggregate_sha256,
        "roots": root_reports,
    }


def harmony_scan(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    max_files = bounded_int(payload.get("max_files"), 8000, 100, 25000)
    contract_error = ""
    try:
        contract = load_harmony_evidence_contract()
    except HarmonyEvidenceContractError as error:
        contract = None
        contract_error = error.__class__.__name__
    active_root_specs = (
        contract["activeSourceRoots"]
        if contract is not None
        else [{"id": source_id, "path": path} for source_id, path in HARMONY_EXPECTED_ROOTS]
    )
    active_roots = {
        item["id"]: root / Path(item["path"])
        for item in active_root_specs
    }
    java_scan_roots = {
        key: active_roots[key]
        for key in ("mainJava", "testJava", "appJavaClean")
    }
    secret_roots = [
        active_roots["mainJava"],
        root / "main" / "resources",
        active_roots["appJavaClean"],
        root / "app" / "src" / "main" / "resources",
    ]

    text_by_path: dict[Path, str] = {}
    runtime_excluded_paths: list[Path] = []
    for scan_root in java_scan_roots.values():
        for file_path in iter_regular_files(scan_root, max_files=max_files):
            if file_path.suffix.lower() != ".java" or path_size(file_path) > 1_500_000:
                continue
            if is_harmony_runtime_excluded(file_path, root):
                runtime_excluded_paths.append(file_path)
                continue
            try:
                text_by_path[file_path] = file_path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue

    runtime_text_by_path = {
        path: text
        for path, text in text_by_path.items()
        if "test" not in path.parts
    }
    runtime_text = "\n".join(runtime_text_by_path.values())
    test_text = "\n".join(
        text
        for path, text in text_by_path.items()
        if "test" in path.parts
    )
    trace_coverage = {
        key: key in runtime_text
        for key in HARMONY_REQUIRED_TRACE_KEYS
    }
    catch_stats = harmony_catch_stats(runtime_text_by_path, root)
    all_catch_stats = harmony_catch_stats(text_by_path, root)
    duplicate_info = harmony_duplicate_fqcns(text_by_path, root)
    cross_subsystem = harmony_cross_subsystem_files(text_by_path, root)
    if contract is None:
        breaks = [{
            "code": "HB-CONTRACT",
            "status": HARMONY_BLOCKED_STATUS,
            "weight": 100.0,
            "evidence": ["contract_integrity_failed"],
        }]
        source_fingerprint = {
            "status": HARMONY_BLOCKED_STATUS,
            "fileCount": 0,
            "errorCount": 1,
            "sha256": hashlib.sha256(b"").hexdigest(),
            "roots": [],
        }
    else:
        breaks = harmony_break_statuses(runtime_text, test_text, trace_coverage, catch_stats)
        for item in breaks:
            if item.get("status") != HARMONY_VERIFIED_STATUS:
                item["status"] = HARMONY_BLOCKED_STATUS
        source_fingerprint = harmony_source_fingerprint(root, contract, max_files)
    blocked_weight = round(sum(
        float(item["weight"])
        for item in breaks
        if item["status"] != HARMONY_VERIFIED_STATUS
    ), 1)
    runtime_proof_payload = payload.get("runtimeProof") if isinstance(payload.get("runtimeProof"), dict) else {}
    runtime_proof = {
        "traceStoreExportOk": runtime_proof_payload.get("traceStoreExportOk") is True,
        "agentDbSnapshotOk": runtime_proof_payload.get("agentDbSnapshotOk") is True,
    }
    runtime_proof_source = "payload" if runtime_proof_payload else "not_probed"
    runtime_proof_details: dict[str, Any] = {}
    if not runtime_proof_payload:
        live_runtime = harmony_live_runtime_proof(payload, root)
        if live_runtime["probed"]:
            runtime_proof = live_runtime["runtimeProof"]
            runtime_proof_source = "live-runtime"
            runtime_proof_details = live_runtime["details"]
    evidence_needed: list[str] = []
    if not runtime_proof["traceStoreExportOk"]:
        evidence_needed.append("boot/live TraceStore export for runtime-only coverage")
    if not runtime_proof["agentDbSnapshotOk"]:
        evidence_needed.append("agent_db_snapshot after Spring server is running for DB-backed state proof")
    next_actions: list[str] = []
    if evidence_needed:
        next_actions = [
            "start_spring_runtime_for_harmony_probe",
            "set_AGENT_DB_CONTEXT_ENABLED_true",
            "set_AWX_AGENT_DB_CONTEXT_BASE_URL",
            "set_AWX_TRACE_SNAPSHOT_BASE_URL",
            "rerun_harmony_scan_with_runtime_probe",
        ]

    promotion_allowed = (
        contract is not None
        and source_fingerprint["status"] == HARMONY_VERIFIED_STATUS
        and blocked_weight == 0.0
        and runtime_proof["traceStoreExportOk"]
        and runtime_proof["agentDbSnapshotOk"]
    )
    report = {
        "schemaVersion": "awx.mcp.harmony_scan.v1",
        "ok": True,
        "rootHash": stable_hash(str(root)),
        "rootLength": len(str(root)),
        "activeSourceSets": {name: scan_tree(path) for name, path in active_roots.items()},
        "evidenceContract": {
            "status": HARMONY_VERIFIED_STATUS if contract is not None else HARMONY_BLOCKED_STATUS,
            "schemaVersion": contract.get("schemaVersion", "") if contract else "",
            "contractId": contract.get("contractId", "") if contract else "",
            "sha256": contract.get("sha256", "") if contract else "",
            "errorType": contract_error,
        },
        "sourceFingerprint": source_fingerprint,
        "metrics": {
            "activeJavaFileCount": len(text_by_path),
            "catchBlockCount": catch_stats["catchBlockCount"],
            "catchWithoutBreadcrumbCount": catch_stats["catchWithoutBreadcrumbCount"],
            "catchWithoutBreadcrumbRatio": catch_stats["catchWithoutBreadcrumbRatio"],
            "allCatchWithoutBreadcrumbCount": all_catch_stats["catchWithoutBreadcrumbCount"],
            "testOrFixtureCatchWithoutBreadcrumbCount": max(
                0,
                all_catch_stats["catchWithoutBreadcrumbCount"]
                - catch_stats["catchWithoutBreadcrumbCount"],
            ),
            "duplicateFqcnCount": duplicate_info["duplicateFqcnCount"],
            "crossSubsystemFileCount": cross_subsystem["crossSubsystemFileCount"],
            "crossSubsystemRiskCounts": cross_subsystem["riskCounts"],
            "runtimeExcludedFileCount": len(runtime_excluded_paths),
            "secretPatternHits": sum(
                secret_hit_count(path, max_files=3000, skip_tests=True)
                for path in secret_roots
            ),
        },
        "samples": {
            "catchWithoutBreadcrumb": catch_stats["samples"],
            "duplicateFqcns": duplicate_info["samples"],
            "crossSubsystemFiles": cross_subsystem["samples"],
            "crossSubsystemReviewQueue": cross_subsystem["reviewQueue"],
            "runtimeExcludedFiles": [
                safe_relpath(path, root)
                for path in sorted(runtime_excluded_paths)[:20]
            ],
        },
        "traceCoverage": {
            "presentCount": sum(1 for present in trace_coverage.values() if present),
            "missing": sorted(key for key, present in trace_coverage.items() if not present),
            "required": trace_coverage,
        },
        "harmonyBreaks": breaks,
        "harmonyScore": {
            "score": 100.0 if promotion_allowed else 0.0,
            "openWeight": 0.0,
            "reviewWeight": 0.0,
            "blockedWeight": blocked_weight,
            "scale": "contract_strict",
            "statusSemantics": "blocked_evidence_contract_v1",
            "promotionAllowed": promotion_allowed,
        },
        "runtimeProof": runtime_proof,
        "runtimeProofSource": runtime_proof_source,
        "runtimeProofDetails": runtime_proof_details,
        "evidence_needed": evidence_needed,
        "nextActions": next_actions,
        "outputCount": len(breaks),
        "decision": "harmony_scan",
        "promotionDecision": "PROMOTE" if promotion_allowed else HARMONY_BLOCKED_STATUS,
    }
    if (
        runtime_proof_source == "live-runtime"
        and runtime_proof.get("traceStoreExportOk") is True
        and runtime_proof.get("agentDbSnapshotOk") is True
    ):
        runtime_artifact_raw = (
            payload.get("runtime_proof_artifact_path")
            or payload.get("runtimeProofArtifactPath")
            or str(root / "data" / "agent-handoff" / "mcp-control-tower" / "harmony-runtime-proof.json")
        )
        if isinstance(runtime_artifact_raw, str) and runtime_artifact_raw.strip():
            runtime_artifact_path = resolve_path(runtime_artifact_raw)
            runtime_artifact_path.parent.mkdir(parents=True, exist_ok=True)
            runtime_artifact = {
                "schemaVersion": HARMONY_RUNTIME_PROOF_SCHEMA_VERSION,
                "ok": True,
                "rootHash": report["rootHash"],
                "rootLength": report["rootLength"],
                "runtimeProof": runtime_proof,
                "runtimeProofSource": runtime_proof_source,
                "runtimeProofDetails": runtime_proof_details,
                "rawSecretPatternHits": 0,
                "decision": "harmony_runtime_proof",
            }
            redacted_artifact = redact(runtime_artifact)
            artifact_text = json.dumps(redacted_artifact, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
            raw_secret_hits = sum(len(pattern.findall(artifact_text)) for pattern in HIGH_CONF_SECRET_PATTERNS)
            redacted_artifact["rawSecretPatternHits"] = raw_secret_hits
            redacted_artifact["ok"] = raw_secret_hits == 0
            artifact_text = json.dumps(redacted_artifact, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
            runtime_artifact_path.write_text(artifact_text, encoding="utf-8")
            report["runtimeProofArtifactPath"] = str(runtime_artifact_path)
            report["runtimeProofArtifactHash"] = sha256_file(runtime_artifact_path)
            report["runtimeProofArtifactBytes"] = path_size(runtime_artifact_path)
    output_raw = payload.get("output_path") or payload.get("outputPath")
    if isinstance(output_raw, str) and output_raw.strip():
        output_path = resolve_path(output_raw)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        artifact_text = json.dumps(redact(report), ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        output_path.write_text(artifact_text, encoding="utf-8")
        report["outputPath"] = str(output_path)
        report["outputHash"] = sha256_file(output_path)
        report["outputBytes"] = path_size(output_path)
    return report


def harmony_live_runtime_proof(payload: dict[str, Any], root: Path) -> dict[str, Any]:
    runtime_base_url = safe_scalar(
        payload.get("runtime_base_url")
        or payload.get("runtimeBaseUrl")
        or os.environ.get("AWX_AGENT_DB_CONTEXT_BASE_URL")
        or deployment_public_base_url()
        or "",
        240,
    ).strip()
    trace_base_url = safe_scalar(
        payload.get("trace_base_url")
        or payload.get("traceBaseUrl")
        or os.environ.get("AWX_TRACE_SNAPSHOT_BASE_URL")
        or deployment_public_base_url()
        or "",
        240,
    ).strip()
    runtime_probe_requested = str(payload.get("runtime_probe") or payload.get("runtimeProbe") or "").strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }
    if not runtime_base_url and not trace_base_url and not runtime_probe_requested:
        return {
            "probed": False,
            "runtimeProof": {
                "traceStoreExportOk": False,
                "agentDbSnapshotOk": False,
            },
            "details": {},
        }

    runtime_base_url = runtime_base_url or trace_base_url or "http://localhost:8080"
    trace_base_url = trace_base_url or runtime_base_url
    timeout_sec = bounded_int(payload.get("runtime_timeout_sec") or payload.get("runtimeTimeoutSec"), 2, 1, 10)
    db_result = agent_db_snapshot(
        {
            "root": str(root),
            "base_url": runtime_base_url,
            "timeout_sec": timeout_sec,
        }
    )
    trace_result = trace_snapshot_probe(
        {
            "root": str(root),
            "base_url": trace_base_url,
            "timeout_sec": timeout_sec,
            "limit": bounded_int(payload.get("runtime_trace_limit") or payload.get("runtimeTraceLimit"), 3, 1, 20),
        }
    )
    db_ok = db_result.get("decision") == "agent_db_snapshot_loaded" and db_result.get("ok") is True
    trace_ok = trace_result.get("decision") == "trace_snapshot_loaded" and trace_result.get("ok") is True
    rendered_probe_results = json.dumps(redact({"agentDbSnapshot": db_result, "traceSnapshot": trace_result}), sort_keys=True)
    raw_secret_hits = sum(len(pattern.findall(rendered_probe_results)) for pattern in HIGH_CONF_SECRET_PATTERNS)
    return {
        "probed": True,
        "runtimeProof": {
            "traceStoreExportOk": trace_ok,
            "agentDbSnapshotOk": db_ok,
        },
        "details": {
            "agentDbSnapshotDecision": safe_scalar(db_result.get("decision", ""), 80),
            "traceSnapshotDecision": safe_scalar(trace_result.get("decision", ""), 80),
            "agentDbSnapshotFallback": isinstance(db_result.get("localFallback"), dict),
            "traceSnapshotFallback": isinstance(trace_result.get("localFallback"), dict),
            "traceSnapshotCount": int(trace_result.get("snapshotCount", 0) or 0),
            "rawSecretPatternHits": raw_secret_hits,
        },
    }


def is_harmony_runtime_excluded(path: Path, root: Path) -> bool:
    rel = safe_relpath(path, root).replace("\\", "/")
    prefix = "app/src/main/java_clean/"
    if not rel.startswith(prefix):
        return False
    java_path = rel[len(prefix):]
    return any(fnmatch.fnmatch(java_path, pattern) for pattern in APP_JAVA_CLEAN_RUNTIME_EXCLUDES)


def harmony_catch_stats(text_by_path: dict[Path, str], root: Path) -> dict[str, Any]:
    rows = tuple(
        row
        for path, text in text_by_path.items()
        for row in classify_java_catches(path=path, root=root, text=text)
    )
    summary = summarize_catches(rows)
    unhandled = [row for row in rows if row.reason_code == "NO_LOCAL_BREADCRUMB"]
    total = summary["catchBlockCount"]
    silent = summary["catchWithoutBreadcrumbCount"]
    return {
        "catchBlockCount": total,
        "catchWithoutBreadcrumbCount": silent,
        "catchWithoutBreadcrumbRatio": 0.0 if total == 0 else round(silent / total, 4),
        "samples": [
            {"path": row.path, "line": row.line}
            for row in unhandled[:20]
        ],
    }


def harmony_duplicate_fqcns(text_by_path: dict[Path, str], root: Path) -> dict[str, Any]:
    fqcn_to_files: dict[str, list[str]] = {}
    pkg_re = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", re.MULTILINE)
    type_re = re.compile(
        r"^\s*(?:public\s+)?(?:final\s+|abstract\s+|sealed\s+)?(?:class|interface|enum|record)\s+([A-Za-z0-9_]+)",
        re.MULTILINE,
    )
    for path, text in text_by_path.items():
        pkg = pkg_re.search(text)
        typ = type_re.search(text)
        if not typ:
            continue
        fqcn = ((pkg.group(1) + ".") if pkg else "") + typ.group(1)
        fqcn_to_files.setdefault(fqcn, []).append(safe_relpath(path, root))
    duplicates = {
        fqcn: files
        for fqcn, files in fqcn_to_files.items()
        if len(files) > 1
    }
    return {
        "duplicateFqcnCount": len(duplicates),
        "samples": [
            {"fqcn": fqcn, "files": files[:5]}
            for fqcn, files in sorted(duplicates.items())[:20]
        ],
    }


def classify_harmony_cross_subsystem_file(rel_path: str, group_count: int) -> tuple[str, str]:
    lowered = rel_path.lower()
    file_name = lowered.rsplit("/", 1)[-1]
    if lowered.startswith("src/test/") or "/src/test/" in lowered or "/test/" in lowered:
        return "TEST", "test_or_fixture_coverage"
    if "/autoconfig/" in lowered or file_name.endswith("autoconfiguration.java"):
        return "LOW", "autoconfiguration_glue"
    if any(
        token in lowered
        for token in (
            "/controller/",
            "/api/",
            "/debug/",
            "/diagnostic",
            "/health/",
            "/agent/context/",
        )
    ):
        return "LOW", "diagnostic_or_context_glue"
    if "/orch/aop/" in lowered or "/service/rag/" in lowered or group_count >= 3:
        return "REVIEW", "runtime_cross_subsystem_review"
    return "REVIEW", "cross_subsystem_review"


def harmony_cross_subsystem_priority_score(rel_path: str, group_count: int, classification: str) -> int:
    lowered = rel_path.lower()
    score = group_count * 10
    if classification == "runtime_cross_subsystem_review":
        score += 20
    if "/orch/aop/" in lowered:
        score += 30
    if "/service/rag/" in lowered:
        score += 20
    if any(token in lowered for token in ("/fusion/", "/cfvm/", "/artplate/")):
        score += 15
    return score


def harmony_cross_subsystem_files(text_by_path: dict[Path, str], root: Path) -> dict[str, Any]:
    samples: list[dict[str, Any]] = []
    review_queue: list[dict[str, Any]] = []
    risk_counts: dict[str, int] = {"LOW": 0, "REVIEW": 0, "TEST": 0}
    count = 0
    lowered_keywords = {
        group: tuple(keyword.lower() for keyword in keywords)
        for group, keywords in HARMONY_SUBSYSTEM_KEYWORDS.items()
    }
    for path, text in text_by_path.items():
        rel_path = safe_relpath(path, root)
        haystack = (rel_path + "\n" + text).lower()
        groups = [
            group
            for group, keywords in lowered_keywords.items()
            if any(keyword in haystack for keyword in keywords)
        ]
        if len(groups) < 2:
            continue
        count += 1
        risk, classification = classify_harmony_cross_subsystem_file(rel_path, len(groups))
        risk_counts[risk] = risk_counts.get(risk, 0) + 1
        priority_score = harmony_cross_subsystem_priority_score(rel_path, len(groups), classification)
        row = {
            "path": rel_path,
            "groups": groups[:6],
            "groupCount": len(groups),
            "risk": risk,
            "classification": classification,
            "priorityScore": priority_score,
        }
        if len(samples) < 30:
            samples.append(row)
        if risk == "REVIEW":
            review_queue.append(row)
    review_queue.sort(key=lambda item: (-int(item["priorityScore"]), item["path"]))
    return {
        "crossSubsystemFileCount": count,
        "riskCounts": {key: value for key, value in risk_counts.items() if value},
        "samples": samples,
        "reviewQueue": review_queue[:30],
    }


def harmony_break_statuses(
        all_text: str,
        test_text: str,
        trace_coverage: dict[str, bool],
        catch_stats: dict[str, Any]) -> list[dict[str, Any]]:
    def item(code: str, status: str, evidence: list[str]) -> dict[str, Any]:
        return {
            "code": code,
            "status": (
                HARMONY_VERIFIED_STATUS
                if status == HARMONY_VERIFIED_STATUS
                else HARMONY_BLOCKED_STATUS
            ),
            "weight": HARMONY_BREAK_WEIGHTS[code],
            "evidence": evidence[:6],
        }

    time_budget_keys = (
        "timeBudget.forceFallback",
        "timeBudget.routeMultiplier",
    )
    raw_tile_contract_present = all(
        token in all_text
        for token in (
            "class CfvmRawTileBuilder",
            "cfvm.rawTile.enabled",
            "cfvm.rawTile.condensed",
            "cfvm.rawTile.rawPayloadStored",
            "no_raw_matrix_entries",
        )
    )
    raw_tile_test_present = all(
        token in test_text
        for token in (
            "class CfvmRawTileBuilderTest",
            "builderRecordsCondensedRawTileWithoutRawTracePayload",
            "builderDisablesOnlyWhenNoRawMatrixEntriesExist",
        )
    )
    booster_contract_present = all(
        token in all_text
        for token in (
            "routing.executionPlan.primaryMode",
            "boosterMode.active",
            "boosterMode.excludedModes",
            "boosterMode.exclusionReason",
        )
    )
    cancel_shield_contract_present = all(
        token in all_text
        for token in (
            "extremeZ.cancelShieldWrapped",
            "extremeZ.timeBudgetConsumedMs",
            "cancel(false)",
        )
    )
    moe_prompt_boundary_present = (
        trace_coverage.get("moe.evolverPlateRegistered", False)
        and 'setPromptTemplate("' not in all_text
    )
    return [
        item(
            "HB-01",
            "DONE" if catch_stats["catchWithoutBreadcrumbCount"] == 0 else "OPEN",
            [f"catchWithoutBreadcrumb={catch_stats['catchWithoutBreadcrumbCount']}"],
        ),
        item(
            "HB-02",
            "DONE" if "adjustFromCfvm(" in all_text and "retrievalOrder.lastSetBy" in all_text else "OPEN",
            ["adjustFromCfvm", "retrievalOrder.lastSetBy"],
        ),
        item(
            "HB-03",
            "DONE" if booster_contract_present else "OPEN",
            [
                "routing.executionPlan.primaryMode",
                "boosterMode.active/excludedModes/exclusionReason",
            ],
        ),
        item(
            "HB-04",
            "DONE" if "dppReranker.rerank" in all_text and "hypernova.dppApplied" in all_text else "OPEN",
            ["dppReranker.rerank", "hypernova.dppApplied"],
        ),
        item(
            "HB-05",
            "DONE" if "TailWeightedPowerMeanFuser" in all_text and "fuseUpperTail" in all_text else "OPEN",
            ["TailWeightedPowerMeanFuser", "fuseUpperTail"],
        ),
        item(
            "HB-06",
            "DONE" if "hypernova.sourceScoreScaleMismatchCount" in all_text else "OPEN",
            ["hypernova.sourceScoreScaleMismatchCount"],
        ),
        item(
            "HB-07",
            "DONE" if cancel_shield_contract_present else "OPEN",
            ["extremeZ.cancelShieldWrapped", "extremeZ.timeBudgetConsumedMs", "cancel(false)"],
        ),
        item(
            "HB-08",
            "DONE" if "cfvm.boltzmannTemp" in all_text and "cfvm.tempSource" in all_text else "OPEN",
            ["cfvm.boltzmannTemp", "cfvm.tempSource"],
        ),
        item(
            "HB-09",
            "DONE" if raw_tile_contract_present and raw_tile_test_present else "OPEN",
            [
                "CfvmRawTileBuilder raw-tile trace contract",
                "CfvmRawTileBuilderTest focused proof",
            ] if raw_tile_contract_present and raw_tile_test_present
            else ["stub_pending_condense_fuse_impl absent means runtime proof still needed"],
        ),
        item(
            "HB-10",
            "DONE" if moe_prompt_boundary_present else "OPEN",
            ["moe.evolverPlateRegistered", "PromptBuilder boundary"],
        ),
        item(
            "HB-11",
            "DONE" if all(trace_coverage.get(key, False) for key in time_budget_keys)
            and "evaluatePublishesCrossSubsystemTimeBudgetTraceKeys" in test_text else "OPEN",
            ["timeBudget.* trace keys", "TimeBudgetGuardTest"],
        ),
        item(
            "HB-12",
            "DONE" if "hypernova.whitening.provider" in all_text else "OPEN",
            ["hypernova.whitening.provider"],
        ),
    ]


def safe_relpath(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def patch_plan(payload: dict[str, Any]) -> dict[str, Any]:
    node_role = safe_scalar(payload.get("nodeRole", "unknown"), 48)
    findings = payload.get("findings") if isinstance(payload.get("findings"), list) else []
    target_files = payload.get("target_files") if isinstance(payload.get("target_files"), list) else []
    mode = "direct" if len(target_files) <= 2 and len(findings) <= 3 else "2-way"
    if len(target_files) > 8 or len(findings) > 8:
        mode = "N-way"
    return {
        "planId": stable_hash({"findings": findings, "targetFiles": target_files, "nodeRole": node_role})[:16],
        "roleDecision": role_decision(node_role),
        "decomposition": {
            "mode": mode,
            "reason": "bounded target set" if mode == "direct" else "multiple independent targets",
        },
        "steps": [
            "broad probe",
            "focused probe",
            "minimal diff candidate",
            "desktop verification",
            "failure classification",
            "single retry if blocker class is unchanged",
        ],
        "desktopFinalProof": "evidence_needed",
        "decision": "plan_only",
    }


def patch_render(payload: dict[str, Any]) -> dict[str, Any]:
    topic = slug(payload.get("topic") or "patch-candidate")
    node = slug(payload.get("nodeRole") or payload.get("node") or "macmini")
    bundle = f"{topic}-{node}-v3"
    patch_file = payload.get("patch_file")
    patch_path = resolve_path(patch_file) if isinstance(patch_file, str) and patch_file.strip() else None
    secret_hits = secret_hit_count(patch_path, max_files=1, include_generic=True) if patch_path and patch_path.exists() else 0
    mode_summary = patch_mode_summary(patch_path) if patch_path and patch_path.exists() else empty_patch_mode_summary()
    filemode_lines = mode_summary["filemodeLineCount"]
    binary_markers = binary_patch_marker_count(patch_path) if patch_path and patch_path.exists() else 0
    forbidden_paths = forbidden_patch_paths(patch_path) if patch_path and patch_path.exists() else []
    evidence_needed = "" if patch_path and patch_path.exists() else "patch_file / provide a generated diff for safety scan"
    return {
        "bundle": {
            "patch": f"{bundle}.patch",
            "report": f"{bundle}.report.md",
            "verifyLog": f"{bundle}.verify.log",
            "sha256": f"{bundle}.sha256.txt",
            "manifest": f"{bundle}.manifest.json",
        },
        "secretPatternHits": secret_hits,
        "filemodeLineCount": filemode_lines,
        "allowedNewFileCount": mode_summary["allowedNewFileCount"],
        "filemodeViolationCount": mode_summary["filemodeViolationCount"],
        "binaryPatchMarkerCount": binary_markers,
        "forbiddenPathCount": len(forbidden_paths),
        "forbiddenPathReasons": sorted({item["reason"] for item in forbidden_paths}),
        "desktopFinalProof": "evidence_needed",
        "evidence_needed": evidence_needed,
        "decision": "render_bundle_contract",
    }


def archive_index_path(payload: dict[str, Any]) -> tuple[Path, str]:
    if payload.get("index_path"):
        return resolve_path(payload.get("index_path")), "payload.index_path"
    archive_index = os.environ.get("ARCHIVE_INDEX", "").strip()
    if archive_index:
        return resolve_path(archive_index), "env.ARCHIVE_INDEX"
    nas_archive_root = os.environ.get("NAS_ARCHIVE_ROOT", "").strip()
    if nas_archive_root:
        return resolve_path(Path(nas_archive_root) / "index.jsonl"), "env.NAS_ARCHIVE_ROOT"
    return resolve_path(Path("BackupsXS") / "index.jsonl"), "default.BackupsXS"


def archive_missing_index_next_actions() -> list[str]:
    return [
        "create_or_point_archive_index",
        "run_archive_index_build",
        "set_ARCHIVE_INDEX_or_NAS_ARCHIVE_ROOT",
        "verify_archive_index_path",
        "rerun_archive_search_with_index_path",
        "rerun_archive_search",
    ]


def archive_index_build(payload: dict[str, Any]) -> dict[str, Any]:
    archive_root = resolve_path(payload.get("archive_root") or payload.get("source_root") or "__archive__")
    index, index_source = archive_index_path(payload)
    include_globs = payload.get("include_globs")
    if not isinstance(include_globs, list) or not include_globs:
        include_globs = ["*.md", "*.txt", "*.json", "*.jsonl", "*.yml", "*.yaml", "*.properties", "*.patch", "*.log"]
    include_patterns = [safe_scalar(item, 80) for item in include_globs if safe_scalar(item, 80).strip()]
    max_files = bounded_int(payload.get("max_files"), 5000, 1, 50000)
    max_bytes = bounded_int(payload.get("max_bytes"), 200_000, 1024, 2_000_000)
    if not archive_root.exists():
        archive_root_text = str(archive_root)
        return {
            "indexedCount": 0,
            "skippedCount": 0,
            "sourceSecretHitFileCount": 0,
            "rawSecretPatternHits": 0,
            "indexPathSource": index_source,
            "indexPathHash": stable_hash(str(index)),
            "indexPathLength": len(str(index)),
            "archiveRootHash": stable_hash(str(archive_root)),
            "archiveRootLength": len(archive_root_text),
            "nextActions": ["verify_archive_root", "provide_archive_root", "rerun_archive_index_build"],
            "evidence_needed": "archive root missing / verify archiveRootHash and archiveRootLength with local path owner",
            "decision": "archive_root_missing",
        }

    rows: list[dict[str, Any]] = []
    skipped_count = 0
    source_secret_hit_file_count = 0
    for file_path in iter_regular_files(archive_root, max_files):
        rel = safe_relpath(file_path, archive_root)
        if not archive_index_include(rel, include_patterns):
            skipped_count += 1
            continue
        size_bytes = path_size(file_path)
        if size_bytes <= 0 or size_bytes > max_bytes:
            skipped_count += 1
            continue
        try:
            text = file_path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            skipped_count += 1
            continue
        source_secret_hits = high_conf_secret_count(text)
        if source_secret_hits:
            source_secret_hit_file_count += 1
        redacted_text = redact_high_conf_secret_text(text)
        tokens = archive_index_tokens(f"{rel}\n{redacted_text}")
        rows.append({
            "id": stable_hash({"path": rel, "sha256": sha256_file(file_path)})[:16],
            "path": rel,
            "title": file_path.name,
            "kind": file_path.suffix.lower().lstrip(".") or "file",
            "labels": tokens[:40],
            "tags": tokens[:40],
            "summary": " ".join(tokens[:80]),
            "sizeBytes": size_bytes,
            "pathHash": stable_hash(rel)[:16],
            "contentHash": sha256_file(file_path),
        })

    index.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = index.with_name(index.name + ".tmp")
    with tmp_path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=True, sort_keys=True, separators=(",", ":")) + "\n")
    tmp_path.replace(index)
    index_text = index.read_text(encoding="utf-8", errors="ignore") if index.exists() else ""
    raw_secret_pattern_hits = high_conf_secret_count(index_text)
    return {
        "indexedCount": len(rows),
        "skippedCount": skipped_count,
        "sourceSecretHitFileCount": source_secret_hit_file_count,
        "rawSecretPatternHits": raw_secret_pattern_hits,
        "indexPathSource": index_source,
        "indexPathHash": stable_hash(str(index)),
        "indexPathLength": len(str(index)),
        "indexHash": sha256_file(index) if index.exists() else "",
        "indexBytes": path_size(index),
        "archiveRootHash": stable_hash(str(archive_root)),
        "includeGlobs": include_patterns,
        "nextActions": ["rerun_archive_search_with_index_path", "rerun_archive_search"],
        "evidence_needed": "" if rows else "archive index built with 0 rows / verify include_globs and archive_root",
        "decision": "archive_index_built",
    }


def archive_index_include(rel_path: str, include_patterns: list[str]) -> bool:
    normalized = rel_path.replace("\\", "/")
    name = Path(normalized).name
    return any(fnmatch.fnmatch(name, pattern) or fnmatch.fnmatch(normalized, pattern) for pattern in include_patterns)


def archive_index_tokens(text: str) -> list[str]:
    stop_words = {
        "and", "are", "but", "for", "from", "into", "not", "the", "this", "that", "with",
        "without", "true", "false", "null", "none", "http", "https", "com", "java",
    }
    tokens: set[str] = set()
    for token in re.findall(r"[A-Za-z][A-Za-z0-9_.-]{2,}", text.lower()):
        clean = token.strip("._-")
        if clean and clean not in stop_words and not any(pattern.search(clean) for pattern in HIGH_CONF_SECRET_PATTERNS):
            tokens.add(clean)
    return sorted(tokens)[:120]


def redact_high_conf_secret_text(text: str) -> str:
    out = text
    for pattern in HIGH_CONF_SECRET_PATTERNS:
        out = pattern.sub("<redacted>", out)
    return out


def high_conf_secret_count(text: str) -> int:
    return sum(len(pattern.findall(text)) for pattern in HIGH_CONF_SECRET_PATTERNS)


def archive_search(payload: dict[str, Any]) -> dict[str, Any]:
    index, index_source = archive_index_path(payload)
    query = safe_scalar(payload.get("q", ""), 240)
    top_k = bounded_int(payload.get("top_k"), 5, 1, 50)
    filters = payload.get("filters") if isinstance(payload.get("filters"), dict) else {}
    if not index.exists():
        archive_index = archive_index_probe(index, index_source)
        return {
            "results": [],
            "passes": [],
            "passCount": 0,
            "expandedQueries": expand_queries(query),
            "indexPathSource": index_source,
            "archiveIndex": archive_index,
            "nextActions": archive_missing_index_next_actions(),
            "evidence_needed": "archive index missing / verify archiveIndex.indexPathHash and indexPathLength with local path owner",
            "decision": "archive_index_missing",
        }

    rows = read_jsonl(index)
    queries = expand_queries(query)
    passes: list[dict[str, Any]] = []
    merged: dict[str, dict[str, Any]] = {}
    for idx, q in enumerate(queries[:3], start=1):
        scored = rank_rows(rows, q, filters)
        passes.append({"pass": idx, "queryHash": stable_hash(q)[:12], "count": len(scored)})
        for row in scored:
            key = str(row.get("path") or row.get("id") or stable_hash(row))
            if key not in merged or int(row.get("score", 0)) > int(merged[key].get("score", 0)):
                merged[key] = row
    results = sorted(merged.values(), key=lambda item: (-int(item.get("score", 0)), str(item.get("path", ""))))[:top_k]
    evidence_needed = "" if results else "BackupsXS/index.jsonl search returned 0 after expanded two-pass query"
    return {
        "indexHash": stable_hash(str(index)),
        "indexPathLength": len(str(index)),
        "indexPathSource": index_source,
        "passCount": len(passes),
        "passes": passes,
        "expandedQueries": queries,
        "outputCount": len(results),
        "results": [result_row(row) for row in results],
        "evidence_needed": evidence_needed,
        "decision": "archive_search",
    }


def archive_index_probe(index: Path, index_source: str) -> dict[str, Any]:
    index_text = str(index)
    index_hash = stable_hash(index_text)
    return {
        "exists": index.exists(),
        "indexPathSource": safe_scalar(index_source, 80),
        "indexPathHash": index_hash,
        "indexPathLength": len(index_text),
        "candidateSources": [
            "payload.index_path",
            "ARCHIVE_INDEX",
            "NAS_ARCHIVE_ROOT/index.jsonl",
            "BackupsXS/index.jsonl",
        ],
        "verifyHint": f"archive_index_path_hash={index_hash} indexPathLength={len(index_text)}",
        "rerunHint": "provide index_path or ARCHIVE_INDEX/NAS_ARCHIVE_ROOT, then rerun archive_search",
    }


def archive_restore(payload: dict[str, Any]) -> dict[str, Any]:
    node_role = safe_scalar(payload.get("nodeRole", "unknown"), 48).lower()
    mode = safe_scalar(payload.get("mode", "review"), 32)
    archive_root = resolve_path(payload.get("archive_root") or payload.get("source_root") or "BackupsXS")
    glob_text = safe_scalar(payload.get("glob", ""), 240)
    target_dir_raw = payload.get("target_dir", "")
    allow_overwrite = bool(payload.get("allow_overwrite", False))
    if not glob_text:
        raise ValueError("missing_glob")
    if has_unsafe_glob(glob_text):
        raise ValueError("unsafe_glob")
    if not archive_root.exists():
        archive_root_text = str(archive_root)
        return {
            "ok": False,
            "restored": [],
            "candidates": [],
            "archiveRootHash": stable_hash(archive_root_text),
            "archiveRootLength": len(archive_root_text),
            "outputCount": 0,
            "evidence_needed": "archive root missing / verify archiveRootHash and archiveRootLength with local path owner",
            "decision": "archive_root_missing",
            "failReason": "archive-root-missing",
        }

    candidates = sorted(path for path in archive_root.glob(glob_text) if path.is_file())
    pre_review = {
        "performed": True,
        "candidateCount": len(candidates),
        "globHash": stable_hash(glob_text)[:12],
    }
    target_dir = resolve_path(target_dir_raw) if target_dir_raw else None
    restored: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    if mode != "restore":
        return {
            "ok": True,
            "preReview": pre_review,
            "candidates": [candidate_row(archive_root, path) for path in candidates],
            "restored": [],
            "outputCount": len(candidates),
            "decision": "review_only",
            "failReason": "",
        }
    if not safe_scalar(payload.get("audit_log", ""), 500).strip():
        return {
            "ok": False,
            "preReview": pre_review,
            "restored": [],
            "skipped": [{"failReason": "missing-audit-log"}],
            "outputCount": 0,
            "decision": "restore_audit_log_missing",
            "failReason": "missing-audit-log",
        }
    if target_dir is None:
        raise ValueError("missing_target_dir")
    if node_role in {"macmini", "notebook", "read-only"} and target_is_protected_canonical(payload, target_dir):
        return {
            "ok": False,
            "preReview": pre_review,
            "restored": [],
            "skipped": [{"targetHash": stable_hash(str(target_dir)), "failReason": "smb-conflict-risk"}],
            "outputCount": 0,
            "decision": "restore_target_blocked",
            "failReason": "smb-conflict-risk",
        }
    for source in candidates[:100]:
        rel = source.relative_to(archive_root)
        dest = (target_dir / rel).resolve()
        if dest.exists() and not allow_overwrite:
            skipped.append({**redacted_path_fields(rel), "failReason": "target_exists"})
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        before = sha256_file(dest) if dest.exists() else ""
        shutil.copy2(source, dest)
        restored.append({
            **redacted_path_fields(rel),
            "targetHash": stable_hash(str(dest)),
            "beforeSha256": before,
            "sha256": sha256_file(dest),
            "checksumVerified": True,
            "sizeBytes": dest.stat().st_size,
        })
    return {
        "ok": bool(restored),
        "preReview": pre_review,
        "candidates": len(candidates),
        "restored": restored,
        "skipped": skipped,
        "outputCount": len(restored),
        "decision": "restore",
        "failReason": "" if restored else ("all_targets_skipped" if skipped else "no_candidates"),
    }


def boot_verify(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    node_role = safe_scalar(payload.get("nodeRole", "desktop"), 48)
    project_cache = gradle_cache_hint(node_role)
    commands = [
        f".\\gradlew.bat projects --no-daemon --project-cache-dir {project_cache}",
        f".\\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir {project_cache}",
        f".\\gradlew.bat compileJava -x test --no-daemon --project-cache-dir {project_cache}",
        f".\\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir {project_cache}",
        f".\\gradlew.bat bootJar -x test --no-daemon --project-cache-dir {project_cache}",
    ]
    return {
        "rootHash": stable_hash(str(root)),
        "rootLength": len(str(root)),
        "nodeRole": node_role,
        "commands": commands,
        "executeSupported": False,
        "evidence_needed": "Desktop final proof until commands run on canonical root",
        "decision": "commands_only",
    }


def build_error_mine(payload: dict[str, Any]) -> dict[str, Any]:
    log_path = resolve_path(payload.get("log_path") or payload.get("build_log") or "build.log")
    if not log_path.exists():
        return {
            "classes": {},
            "outputCount": 0,
            "evidence_needed": f"{log_path.as_posix()} / provide build log",
            "decision": "log_missing",
        }
    text = log_path.read_text(encoding="utf-8", errors="ignore")[:1_000_000]
    classes = {
        "cannot-find-symbol": count_re(text, r"cannot find symbol|cannot be resolved"),
        "duplicate-class-fqcn": count_re(text, r"duplicate class"),
        "spring-bean": count_re(text, r"NoSuchBeanDefinition|UnsatisfiedDependency|BeanCreationException"),
        "spring-bind": count_re(text, r"BindException|Failed to bind"),
        "yaml-parse": count_re(text, r"DuplicateKeyException|MarkedYAMLException|ScannerException"),
        "langchain4j-version-purity": count_re(text, r"LangChain4j version purity"),
        "gradle-distribution-network-cache": count_re(text, r"services\.gradle\.org|UnknownHostException|distribution"),
    }
    classes = {key: value for key, value in classes.items() if value > 0}
    return {
        "logHash": stable_hash(str(log_path)),
        "logPathLength": len(str(log_path)),
        "classes": classes,
        "primaryClass": next(iter(classes.keys()), "other"),
        "outputCount": sum(classes.values()),
        "decision": "build_log_mined",
    }


def run_pipeline(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    pipeline = root / "orchestration" / "run_pipeline.sh"
    mcp_pipeline = root / "scripts" / "awx_mcp_control_tower_pipeline.py"
    verify_boot = root / "verify_boot.sh"
    build_error_miner = root / "tools" / "run_build_error_miner.sh"
    tools = {
        "run_pipeline": pipeline.exists() or mcp_pipeline.exists(),
        "mcp_control_tower_pipeline": mcp_pipeline.exists(),
        "verify_boot": verify_boot.exists(),
        "build_error_miner": build_error_miner.exists(),
    }
    missing = [name for name, exists in tools.items() if not exists]
    pipeline_plan: dict[str, Any] = {}
    pipeline_plan_exit_code = 0
    pipeline_failure = ""
    if mcp_pipeline.exists():
        try:
            proc = subprocess.run(
                [sys.executable, str(mcp_pipeline), "--root", str(root), "--plan-only"],
                cwd=str(root),
                text=True,
                capture_output=True,
                timeout=45,
                check=False,
            )
        except subprocess.TimeoutExpired:
            # Synthetic timeout classification, not an observed child exit.
            pipeline_plan_exit_code = 124
            pipeline_failure = "pipeline-plan-timeout"
            pipeline_plan = {
                "schemaVersion": "awx.mcp.control_tower_pipeline.v1",
                "ok": False,
                "desktopFinalProof": "evidence_needed",
                "decision": "control_tower_pipeline_timeout",
                "failReason": pipeline_failure,
            }
        else:
            pipeline_plan_exit_code = int(proc.returncode)
            try:
                pipeline_plan = json.loads((proc.stdout or "").lstrip("\ufeff"))
            except (TypeError, ValueError):
                pipeline_failure = "pipeline-plan-invalid-json"
                pipeline_plan = {
                    "schemaVersion": "awx.mcp.control_tower_pipeline.v1",
                    "ok": False,
                    "desktopFinalProof": "evidence_needed",
                    "decision": "control_tower_pipeline_unparseable",
                    "failReason": "invalid-json",
                }
            if not isinstance(pipeline_plan, dict):
                pipeline_failure = "pipeline-plan-invalid-payload"
                pipeline_plan = {
                    "schemaVersion": "awx.mcp.control_tower_pipeline.v1",
                    "ok": False,
                    "desktopFinalProof": "evidence_needed",
                    "decision": "control_tower_pipeline_unparseable",
                    "failReason": "invalid-payload",
                }
            if not pipeline_failure:
                if pipeline_plan_exit_code != 0:
                    pipeline_failure = "pipeline-plan-exit-nonzero"
                elif pipeline_plan.get("ok") is not True:
                    pipeline_failure = "pipeline-plan-failed"
        if pipeline_failure and "mcp_control_tower_pipeline_plan" not in missing:
            missing.append("mcp_control_tower_pipeline_plan")
    return {
        "ok": not pipeline_failure,
        "failReason": pipeline_failure,
        "tools": tools,
        "commands": {
            "verify_boot": "bash verify_boot.sh",
            "run_pipeline": (
                "python scripts/awx_mcp_control_tower_pipeline.py --root . --plan-only"
                if mcp_pipeline.exists()
                else "bash orchestration/run_pipeline.sh"
            ),
            "mcp_control_tower": "python scripts/awx_mcp_control_tower_pipeline.py --root . --plan-only",
            "build_error_mine": "bash tools/run_build_error_miner.sh build-logs analysis/build_error_report",
        },
        "pipelinePlan": pipeline_plan,
        "pipelinePlanExitCode": pipeline_plan_exit_code,
        "evidence_needed": ",".join(missing) if missing else "",
        "decision": "pipeline_probe",
    }


def external_evidence_audit(payload: dict[str, Any]) -> dict[str, Any]:
    evidence_dir = resolve_path(payload.get("evidence_dir") or "data/agent-handoff/mcp-control-tower")
    patchdrop_root = resolve_path(payload.get("patchdrop_root") or "__patch_drop__")
    require_archive_index = bool(payload.get("require_archive_index", False))
    require_producer_bundles = bool(payload.get("require_producer_bundles", True))
    producer_bundle_topic = slug(payload.get("topic") or latest_dispatch_topic(patchdrop_root) or "mcp-stdio-bridge-verification")
    raw_roles = payload.get("required_roles")
    required_roles = [
        safe_scalar(role, 32).lower()
        for role in (raw_roles if isinstance(raw_roles, list) else ["macmini", "notebook"])
        if safe_scalar(role, 32).strip()
    ]
    if not required_roles:
        required_roles = ["macmini", "notebook"]

    archive_payload = {"index_path": payload.get("archive_index")} if payload.get("archive_index") else {}
    archive_index, archive_index_source = archive_index_path(archive_payload)
    evidence_needed: list[str] = []
    optional_evidence_needed: list[str] = []
    next_actions: list[dict[str, Any]] = []
    optional_next_actions: list[dict[str, Any]] = []
    node_evidence: list[dict[str, Any]] = []
    producer_handoffs: list[dict[str, Any]] = []
    producer_bundles: list[dict[str, Any]] = []
    raw_secret_hits = 0
    dispatch_integrity = dispatch_integrity_status(patchdrop_root, producer_bundle_topic)
    unrelated_patchdrop_evidence = unrelated_patchdrop_evidence_summary(patchdrop_root, producer_bundle_topic)
    if not dispatch_integrity.get("ok"):
        evidence_needed.append(
            f"dispatch integrity invalid topic={producer_bundle_topic} reason={dispatch_integrity.get('failReason') or 'unknown'}"
        )
        next_actions.append({
            "action": "refresh-desktop-dispatch-packet",
            "nodeRole": "desktop",
            "toolName": "desktop_dispatch_packet",
            "topic": producer_bundle_topic,
            "decision": "evidence_needed",
            "hint": "rerun desktop_dispatch_packet with write_dispatch=true so dispatch SHA sidecar covers command and intake files",
        })

    for role in required_roles:
        evidence_path = find_node_smoke_evidence(evidence_dir, role)
        if evidence_path is None:
            evidence_needed.append(f"external node smoke missing role={role}")
            next_actions.append(collect_external_evidence_next_action(evidence_dir, patchdrop_root, role, producer_bundle_topic))
            next_actions.append(mcp_node_setup_next_action(patchdrop_root, role, producer_bundle_topic))
            next_actions.append(dispatch_next_action(patchdrop_root, role, producer_bundle_topic))
            node_evidence.append({"nodeRole": role, "valid": False, "failReason": "node-smoke-missing"})
            continue

        try:
            raw = evidence_path.read_text(encoding="utf-8", errors="ignore")
            for pattern in HIGH_CONF_SECRET_PATTERNS:
                raw_secret_hits += len(pattern.findall(raw))
            data = json.loads(raw.lstrip("\ufeff"))
        except Exception:
            evidence_needed.append(f"external node smoke invalid-json role={role}")
            next_actions.append(collect_external_evidence_next_action(evidence_dir, patchdrop_root, role, producer_bundle_topic))
            node_evidence.append({
                "nodeRole": role,
                "fileHash": sha256_file(evidence_path),
                "valid": False,
                "failReason": "invalid-json",
            })
            continue

        dispatch_packet = latest_dispatch_packet_for_role(patchdrop_root, role, producer_bundle_topic)
        expected_source_root_hash = dispatch_source_root_hash(dispatch_packet)
        expected_query_hash = dispatch_node_smoke_query_hash(dispatch_packet)
        expected_producer_command_hash = dispatch_producer_command_hash(patchdrop_root, role, producer_bundle_topic)
        validation = validate_node_smoke_evidence(data, role, expected_source_root_hash, expected_query_hash)
        if validation["failReason"]:
            evidence_needed.append(f"external node smoke invalid role={role} reason={validation['failReason']}")
            next_actions.append(collect_external_evidence_next_action(evidence_dir, patchdrop_root, role, producer_bundle_topic))
        node_evidence.append({
            "nodeRole": role,
            "fileHash": sha256_file(evidence_path),
            "valid": validation["valid"],
            "stepCount": validation["stepCount"],
            "restoreBlocked": validation["restoreBlocked"],
            "expectedSourceRootHash": expected_source_root_hash[:12],
            "sourceRootInputHash": safe_scalar(data.get("sourceRootInputHash", ""), 120)[:12],
            "expectedQueryHash": expected_query_hash[:12],
            "queryHash": safe_scalar(data.get("queryHash", ""), 120)[:12],
            "desktopFinalProof": "evidence_needed",
            "failReason": validation["failReason"],
        })

    for role in required_roles:
        dispatch_packet = latest_dispatch_packet_for_role(patchdrop_root, role, producer_bundle_topic)
        expected_source_root_hash = dispatch_source_root_hash(dispatch_packet)
        expected_producer_command_hash = dispatch_producer_command_hash(patchdrop_root, role, producer_bundle_topic)
        handoff_path = find_producer_handoff_evidence(
            [evidence_dir, patchdrop_root / "external-node-proof"],
            role,
        )
        if handoff_path is None:
            handoff_row = {
                "nodeRole": role,
                "valid": False,
                "promotionReady": False,
                "desktopFinalProof": "evidence_needed",
                "diffHeaderCount": 0,
                "patchHash": "",
                "expectedSourceRootHash": expected_source_root_hash[:12],
                "expectedProducerCommandHash": expected_producer_command_hash[:12],
                "producerCommandHash": "",
                "sourceRootInputHash": "",
                "sourceRootHash": "",
                "failReason": "producer-handoff-missing",
            }
        else:
            try:
                raw = handoff_path.read_text(encoding="utf-8", errors="ignore")
                for pattern in HIGH_CONF_SECRET_PATTERNS:
                    raw_secret_hits += len(pattern.findall(raw))
                handoff_data = json.loads(raw.lstrip("\ufeff"))
                validation = validate_producer_handoff_evidence(
                    handoff_data,
                    role,
                    producer_bundle_topic,
                    expected_source_root_hash,
                    producer_patch_sidecar_hash(patchdrop_root, role, producer_bundle_topic),
                    expected_producer_command_hash,
                )
                handoff_row = {
                    "nodeRole": role,
                    "fileHash": sha256_file(handoff_path),
                    "valid": validation["valid"],
                    "promotionReady": validation["promotionReady"],
                    "desktopFinalProof": validation["desktopFinalProof"] or "evidence_needed",
                    "bundleDesktopFinalProof": validation["bundleDesktopFinalProof"],
                    "diffHeaderCount": validation["diffHeaderCount"],
                    "patchHash": validation["patchHash"],
                    "expectedSourceRootHash": expected_source_root_hash[:12],
                    "expectedProducerCommandHash": expected_producer_command_hash[:12],
                    "producerCommandHash": validation["producerCommandHash"][:12],
                    "sourceRootInputHash": safe_scalar(handoff_data.get("sourceRootInputHash", ""), 120)[:12],
                    "sourceRootHash": safe_scalar(handoff_data.get("sourceRootHash", ""), 120)[:12],
                    "failReason": validation["failReason"],
                }
            except Exception:
                handoff_row = {
                    "nodeRole": role,
                    "fileHash": sha256_file(handoff_path) if handoff_path.exists() else "",
                    "valid": False,
                    "promotionReady": False,
                    "desktopFinalProof": "evidence_needed",
                    "diffHeaderCount": 0,
                    "patchHash": "",
                    "expectedSourceRootHash": expected_source_root_hash[:12],
                    "expectedProducerCommandHash": expected_producer_command_hash[:12],
                    "producerCommandHash": "",
                    "sourceRootInputHash": "",
                    "sourceRootHash": "",
                    "failReason": "invalid-json",
                }
        producer_handoffs.append(handoff_row)
        if require_producer_bundles and not handoff_row.get("valid"):
            evidence_needed.append(
                f"producer handoff missing-or-invalid role={role} topic={producer_bundle_topic} reason={handoff_row.get('failReason') or 'missing'}"
            )
            next_actions.append(collect_external_evidence_next_action(evidence_dir, patchdrop_root, role, producer_bundle_topic))
            next_actions.append(dispatch_next_action(patchdrop_root, role, producer_bundle_topic))

        bundle_evidence = validate_producer_bundle_evidence(
            patchdrop_root,
            role,
            producer_bundle_topic,
            expected_source_root_hash,
        )
        producer_bundles.append(bundle_evidence)
        raw_secret_hits += int(bundle_evidence.get("rawSecretPatternHits") or 0)
        if require_producer_bundles and not bundle_evidence.get("valid"):
            evidence_needed.append(
                f"producer bundle missing-or-invalid role={role} topic={producer_bundle_topic} reason={bundle_evidence.get('failReason') or 'missing'}"
            )
            next_actions.append(collect_external_evidence_next_action(evidence_dir, patchdrop_root, role, producer_bundle_topic))
            next_actions.append(dispatch_next_action(patchdrop_root, role, producer_bundle_topic))

    if any(row.get("valid") is False for row in node_evidence):
        next_actions.append(mcp_client_config_next_action())

    if raw_secret_hits:
        evidence_needed.append("external node smoke rawSecretPatternHits > 0")
    if not archive_index.exists():
        archive_message = f"archive index missing source={archive_index_source}"
        archive_action = archive_index_next_action(archive_index, archive_index_source)
        if require_archive_index:
            evidence_needed.append(archive_message)
            next_actions.append(archive_action)
        else:
            optional_evidence_needed.append(archive_message)
            optional_next_actions.append(archive_action)

    desktop_intake = dispatch_file_for_topic(patchdrop_root, producer_bundle_topic, "desktop-intake.ps1")
    next_actions = dedupe_next_actions(next_actions)
    optional_next_actions = dedupe_next_actions(optional_next_actions)
    if next_actions and desktop_intake is not None:
        next_actions.append({
            "action": "run-desktop-intake-after-producer-proof",
            "nodeRole": "desktop",
            "commandFile": str(desktop_intake),
            "fileHash": sha256_file(desktop_intake),
        })

    complete = (
        not evidence_needed
        and raw_secret_hits == 0
        and bool(dispatch_integrity.get("ok"))
        and (archive_index.exists() or not require_archive_index)
        and all(row.get("valid") for row in node_evidence)
        and len(node_evidence) == len(required_roles)
        and (
            not require_producer_bundles
            or (
                all(row.get("valid") for row in producer_handoffs)
                and len(producer_handoffs) == len(required_roles)
                and
                all(row.get("valid") for row in producer_bundles)
                and len(producer_bundles) == len(required_roles)
            )
        )
    )
    if complete and require_producer_bundles:
        next_actions.append(janitor_apply_gate_next_action(patchdrop_root, producer_bundle_topic, required_roles))
        next_actions = dedupe_next_actions(next_actions)
    return {
        "ok": complete,
        "externalEvidenceComplete": complete,
        "nodeEvidence": node_evidence,
        "producerHandoffs": producer_handoffs,
        "producerBundles": producer_bundles,
        "producerBundleTopic": producer_bundle_topic,
        "producerBundlesRequired": require_producer_bundles,
        "unrelatedPatchDropEvidence": unrelated_patchdrop_evidence,
        "dispatchIntegrity": dispatch_integrity,
        "desktopFinalProof": "evidence_needed",
        "archiveIndex": {
            "exists": archive_index.exists(),
            "required": require_archive_index,
            "indexPathSource": archive_index_source,
            "indexHash": stable_hash(str(archive_index)),
            "indexPathLength": len(str(archive_index)),
        },
        "rawSecretPatternHits": raw_secret_hits,
        "outputCount": sum(1 for row in node_evidence if row.get("valid")),
        "evidence_needed": evidence_needed,
        "optional_evidence_needed": optional_evidence_needed,
        "nextActions": next_actions,
        "optionalNextActions": optional_next_actions,
        "decision": "external_evidence_audit" if complete else "external_evidence_incomplete",
        "failReason": "" if complete else "evidence-needed",
    }


def external_evidence_intake(payload: dict[str, Any]) -> dict[str, Any]:
    patchdrop_root = resolve_path(payload.get("patchdrop_root") or "__patch_drop__")
    source_dir = resolve_path(payload.get("source_evidence_dir") or str(patchdrop_root / "external-node-proof"))
    evidence_dir = resolve_path(payload.get("evidence_dir") or "data/agent-handoff/mcp-control-tower")
    producer_bundle_topic = slug(payload.get("topic") or latest_dispatch_topic(patchdrop_root) or "mcp-stdio-bridge-verification")
    raw_roles = payload.get("required_roles")
    required_roles = [
        safe_scalar(role, 32).lower()
        for role in (raw_roles if isinstance(raw_roles, list) else ["macmini", "notebook"])
        if safe_scalar(role, 32).strip()
    ]
    if not required_roles:
        required_roles = ["macmini", "notebook"]

    copied: list[dict[str, Any]] = []
    copied_handoffs: list[dict[str, Any]] = []
    rejected: list[dict[str, Any]] = []
    rejected_handoffs: list[dict[str, Any]] = []
    validated: list[dict[str, Any]] = []
    validated_handoffs: list[dict[str, Any]] = []
    evidence_needed: list[str] = []
    raw_secret_hits = 0
    cleared_node_evidence_count = 0
    cleared_handoff_evidence_count = 0

    for role in required_roles:
        proof_path = find_node_smoke_evidence(source_dir, role)
        if proof_path is None:
            evidence_needed.append(f"external node smoke missing role={role}")
            rejected.append({"nodeRole": role, "valid": False, "failReason": "node-smoke-missing"})
            continue

        try:
            raw = proof_path.read_text(encoding="utf-8", errors="ignore")
            file_secret_hits = sum(len(pattern.findall(raw)) for pattern in HIGH_CONF_SECRET_PATTERNS)
            raw_secret_hits += file_secret_hits
            data = json.loads(raw.lstrip("\ufeff"))
        except Exception:
            evidence_needed.append(f"external node smoke invalid-json role={role}")
            rejected.append({
                "nodeRole": role,
                "fileHash": sha256_file(proof_path),
                "valid": False,
                "failReason": "invalid-json",
            })
            continue

        dispatch_packet = latest_dispatch_packet_for_role(patchdrop_root, role, producer_bundle_topic)
        expected_source_root_hash = dispatch_source_root_hash(dispatch_packet)
        expected_query_hash = dispatch_node_smoke_query_hash(dispatch_packet)
        expected_producer_command_hash = dispatch_producer_command_hash(patchdrop_root, role, producer_bundle_topic)
        validation = validate_node_smoke_evidence(data, role, expected_source_root_hash, expected_query_hash)
        if file_secret_hits:
            validation["valid"] = False
            validation["failReason"] = ",".join(filter(None, [validation.get("failReason", ""), "secret-leak-risk"]))
        if not validation["valid"]:
            evidence_needed.append(f"external node smoke invalid role={role} reason={validation['failReason']}")
            rejected.append({
                "nodeRole": role,
                "fileHash": sha256_file(proof_path),
                "valid": False,
                "stepCount": validation["stepCount"],
                "restoreBlocked": validation["restoreBlocked"],
                "expectedSourceRootHash": expected_source_root_hash[:12],
                "sourceRootInputHash": safe_scalar(data.get("sourceRootInputHash", ""), 120)[:12],
                "expectedQueryHash": expected_query_hash[:12],
                "queryHash": safe_scalar(data.get("queryHash", ""), 120)[:12],
                "failReason": validation["failReason"],
            })
            continue

        handoff_path = find_producer_handoff_evidence([source_dir, patchdrop_root / "external-node-proof"], role)
        if handoff_path is None:
            evidence_needed.append(f"producer handoff missing role={role}")
            rejected_handoffs.append({"nodeRole": role, "valid": False, "patchHash": "", "failReason": "producer-handoff-missing"})
            continue

        try:
            handoff_raw = handoff_path.read_text(encoding="utf-8", errors="ignore")
            handoff_secret_hits = sum(len(pattern.findall(handoff_raw)) for pattern in HIGH_CONF_SECRET_PATTERNS)
            raw_secret_hits += handoff_secret_hits
            handoff_data = json.loads(handoff_raw.lstrip("\ufeff"))
        except Exception:
            evidence_needed.append(f"producer handoff invalid-json role={role}")
            rejected_handoffs.append({
                "nodeRole": role,
                "fileHash": sha256_file(handoff_path) if handoff_path.exists() else "",
                "valid": False,
                "patchHash": "",
                "failReason": "invalid-json",
            })
            continue

        handoff_validation = validate_producer_handoff_evidence(
            handoff_data,
            role,
            producer_bundle_topic,
            expected_source_root_hash,
            producer_patch_sidecar_hash(patchdrop_root, role, producer_bundle_topic),
            expected_producer_command_hash,
        )
        if handoff_secret_hits:
            handoff_validation["valid"] = False
            handoff_validation["failReason"] = ",".join(filter(None, [handoff_validation.get("failReason", ""), "secret-leak-risk"]))
        if not handoff_validation["valid"]:
            evidence_needed.append(f"producer handoff invalid role={role} reason={handoff_validation['failReason']}")
            rejected_handoffs.append({
                "nodeRole": role,
                "fileHash": sha256_file(handoff_path),
                "valid": False,
                "promotionReady": handoff_validation["promotionReady"],
                "desktopFinalProof": handoff_validation["desktopFinalProof"] or "evidence_needed",
                "diffHeaderCount": handoff_validation["diffHeaderCount"],
                "patchHash": handoff_validation["patchHash"],
                "expectedSourceRootHash": expected_source_root_hash[:12],
                "expectedProducerCommandHash": expected_producer_command_hash[:12],
                "producerCommandHash": handoff_validation["producerCommandHash"][:12],
                "sourceRootInputHash": safe_scalar(handoff_data.get("sourceRootInputHash", ""), 120)[:12],
                "sourceRootHash": safe_scalar(handoff_data.get("sourceRootHash", ""), 120)[:12],
                "failReason": handoff_validation["failReason"],
            })
            continue

        validated.append({
            "nodeRole": role,
            "data": data,
            "sourceHash": sha256_file(proof_path),
            "desktopFinalProof": "evidence_needed",
        })
        validated_handoffs.append({
            "nodeRole": role,
            "data": handoff_data,
            "sourceHash": sha256_file(handoff_path),
            "desktopFinalProof": "evidence_needed",
        })

    if rejected or rejected_handoffs or len(validated) != len(required_roles) or len(validated_handoffs) != len(required_roles):
        cleared_node_evidence_count = clear_node_smoke_evidence(evidence_dir, required_roles)
        cleared_handoff_evidence_count = clear_producer_handoff_evidence(evidence_dir, required_roles)
        cleared_total = cleared_node_evidence_count + cleared_handoff_evidence_count
        if cleared_total:
            evidence_needed.append(f"stale Desktop external evidence cleared count={cleared_total}")
    else:
        evidence_dir.mkdir(parents=True, exist_ok=True)
        for item in validated:
            role = str(item["nodeRole"])
            target_path = evidence_dir / f"{role}-node-smoke.json"
            target_path.write_text(json.dumps(item["data"], ensure_ascii=True, separators=(",", ":")) + "\n", encoding="utf-8")
            copied.append({
                "nodeRole": role,
                "sourceHash": item["sourceHash"],
                "targetHash": sha256_file(target_path),
                "targetPath": target_path.as_posix(),
                "desktopFinalProof": "evidence_needed",
            })
        for item in validated_handoffs:
            role = str(item["nodeRole"])
            target_path = evidence_dir / f"{role}-producer-handoff.json"
            target_path.write_text(json.dumps(item["data"], ensure_ascii=True, separators=(",", ":")) + "\n", encoding="utf-8")
            copied_handoffs.append({
                "nodeRole": role,
                "sourceHash": item["sourceHash"],
                "targetHash": sha256_file(target_path),
                "targetPath": target_path.as_posix(),
                "desktopFinalProof": "evidence_needed",
            })

    audit_payload = dict(payload)
    audit_payload["evidence_dir"] = str(evidence_dir)
    audit_payload["required_roles"] = required_roles
    audit = external_evidence_audit(audit_payload)
    for item in audit.get("evidence_needed", []):
        if item not in evidence_needed:
            evidence_needed.append(item)
    optional_evidence_needed = list(audit.get("optional_evidence_needed", []))
    next_actions = list(audit.get("nextActions", []))
    optional_next_actions = list(audit.get("optionalNextActions", []))

    complete = (
        bool(audit.get("externalEvidenceComplete"))
        and len(copied) == len(required_roles)
        and len(copied_handoffs) == len(required_roles)
        and not rejected
        and not rejected_handoffs
        and raw_secret_hits == 0
    )
    intake_summary = {
        "requiredRoles": required_roles,
        "producerBundleTopic": producer_bundle_topic,
        "sourceEvidenceDir": str(source_dir),
        "evidenceDir": str(evidence_dir),
        "copiedEvidenceCount": len(copied),
        "copiedHandoffCount": len(copied_handoffs),
        "rejectedEvidenceCount": len(rejected),
        "rejectedHandoffCount": len(rejected_handoffs),
        "staleDesktopEvidenceCleared": (cleared_node_evidence_count + cleared_handoff_evidence_count) > 0,
        "clearedNodeEvidenceCount": cleared_node_evidence_count,
        "clearedHandoffEvidenceCount": cleared_handoff_evidence_count,
        "clearedEvidenceCount": cleared_node_evidence_count + cleared_handoff_evidence_count,
        "rawSecretPatternHits": raw_secret_hits,
    }
    return {
        "ok": complete,
        "externalEvidenceComplete": complete,
        "intakeSummary": intake_summary,
        "copiedEvidence": copied,
        "copiedHandoffs": copied_handoffs,
        "rejectedEvidence": rejected,
        "rejectedHandoffs": rejected_handoffs,
        "nodeEvidence": audit.get("nodeEvidence", []),
        "producerHandoffs": audit.get("producerHandoffs", []),
        "producerBundles": audit.get("producerBundles", []),
        "producerBundleTopic": audit.get("producerBundleTopic", ""),
        "producerBundlesRequired": audit.get("producerBundlesRequired", True),
        "unrelatedPatchDropEvidence": audit.get("unrelatedPatchDropEvidence", {}),
        "dispatchIntegrity": audit.get("dispatchIntegrity", {}),
        "archiveIndex": audit.get("archiveIndex", {}),
        "desktopFinalProof": "evidence_needed",
        "rawSecretPatternHits": raw_secret_hits,
        "outputCount": len(copied),
        "evidence_needed": evidence_needed,
        "optional_evidence_needed": optional_evidence_needed,
        "nextActions": next_actions,
        "optionalNextActions": optional_next_actions,
        "decision": "external_evidence_intake" if complete else "external_evidence_intake_incomplete",
        "failReason": "" if complete else "evidence-needed",
    }


def latest_dispatch_file(patchdrop_root: Path, pattern: str) -> Path | None:
    dispatch_dir = patchdrop_root / "dispatch"
    if not dispatch_dir.exists():
        return None
    matches = sorted(
        (path for path in dispatch_dir.glob(pattern) if path.is_file()),
        key=lambda item: item.stat().st_mtime,
        reverse=True,
    )
    return matches[0] if matches else None


def dispatch_file_for_topic(patchdrop_root: Path, topic: str, suffix: str) -> Path | None:
    dispatch_dir = patchdrop_root / "dispatch"
    topic_slug = slug(topic)
    if not topic_slug or not dispatch_dir.exists():
        return None
    candidate = dispatch_dir / f"{topic_slug}-{suffix}"
    return candidate if candidate.is_file() else None


def producer_visible_dispatch_path(producer_patchdrop_root: str, filename: str) -> str:
    root = safe_scalar(producer_patchdrop_root, 500).rstrip("/\\")
    clean_filename = safe_scalar(filename, 200)
    if not root or not clean_filename:
        return ""
    separator = "\\" if "\\" in root and not root.startswith("/") else "/"
    return root + separator + separator.join(("dispatch", clean_filename))


def latest_dispatch_topic(patchdrop_root: Path) -> str:
    dispatch_file = latest_dispatch_file(patchdrop_root, "*-desktop-dispatch.json")
    if dispatch_file is None:
        return ""
    return dispatch_file.name.removesuffix("-desktop-dispatch.json")


def dedupe_next_actions(actions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[str, str, str, str]] = set()
    deduped: list[dict[str, Any]] = []
    for action in actions:
        key = (
            safe_scalar(action.get("action", ""), 80),
            safe_scalar(action.get("nodeRole", ""), 32),
            safe_scalar(action.get("targetRole", ""), 32),
            safe_scalar(action.get("commandFile") or action.get("toolName") or action.get("configFile") or "", 500),
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(action)
    return deduped


def dedupe_scalar_list(items: list[Any]) -> list[str]:
    out: list[str] = []
    for item in items:
        text = safe_scalar(item, 500)
        if text and text not in out:
            out.append(text)
    return out


def completion_audit_summary_for_root(
    root: str,
    *,
    require_supabase_live_proof: bool = False,
    include_supporting_next_actions: bool = False,
) -> dict[str, Any]:
    script = Path(__file__).resolve().with_name("awx_mcp_completion_audit.py")
    if not script.is_file():
        return {
            "_awxCompletionAuditInvocation": {
                "executed": False,
                "failureClass": "completion-audit-script-missing",
            }
        }
    args = [sys.executable, str(script), "--root", str(resolve_path(root))]
    if require_supabase_live_proof:
        args.append("--require-supabase-proof")
    if include_supporting_next_actions:
        args.append("--include-supporting-next-actions")
    try:
        completed = subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=45,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return {
            "_awxCompletionAuditInvocation": {
                "executed": False,
                "failureClass": "completion-audit-timeout",
            }
        }
    except Exception:
        return {
            "_awxCompletionAuditInvocation": {
                "executed": False,
                "failureClass": "completion-audit-invocation-failed",
            }
        }
    try:
        payload = json.loads(completed.stdout)
    except Exception:
        return {
            "_awxCompletionAuditInvocation": {
                "executed": False,
                "failureClass": "completion-audit-invalid-json",
            }
        }
    if not isinstance(payload, dict) or not (
        isinstance(payload.get("ok"), bool)
        and isinstance(payload.get("status"), str)
        and bool(payload.get("status", "").strip())
        and isinstance(payload.get("nextActions"), list)
    ):
        return {
            "_awxCompletionAuditInvocation": {
                "executed": False,
                "failureClass": "completion-audit-invalid-payload",
            }
        }
    payload["_awxCompletionAuditInvocation"] = {
        "executed": True,
        "failureClass": "none",
    }
    return payload


def completion_audit_next_actions_for_root(root: str) -> list[str]:
    payload = completion_audit_summary_for_root(root)
    actions = payload.get("nextActions")
    if not isinstance(actions, list):
        return []
    deduped: list[str] = []
    for action in actions:
        text = safe_scalar(action, 120)
        if text and text not in deduped:
            deduped.append(text)
    return deduped


def source_contract_next_actions_for_root(root: str) -> list[dict[str, Any]]:
    scorecard = load_json_file(resolve_path(root) / "verification" / "source-health-scorecard.json")
    details = scorecard.get("nextSourceActionDetails")
    if not isinstance(details, list):
        return []
    actions: list[dict[str, Any]] = []
    windows_abs_re = re.compile(r"(?i)\b[a-z]:[\\/]")

    def safe_list(value: Any, limit: int, max_items: int) -> list[str]:
        if not isinstance(value, list):
            return []
        safe_values: list[str] = []
        for item in value:
            text = safe_scalar(item, limit)
            if not text or windows_abs_re.search(text):
                continue
            safe_values.append(text)
            if len(safe_values) >= max_items:
                break
        return safe_values

    for detail in details:
        if not isinstance(detail, dict):
            continue
        action_name = safe_scalar(detail.get("action"), 100)
        if action_name != "run-cross-subsystem-contract-tests":
            continue
        rendered = json.dumps(detail, sort_keys=True, ensure_ascii=True)
        if windows_abs_re.search(rendered):
            continue
        actions.append({
            "action": action_name,
            "nodeRole": "desktop",
            "scope": safe_scalar(detail.get("scope") or "active_source", 80),
            "readOnly": detail.get("readOnly") is True,
            "mutationAllowed": detail.get("mutationAllowed") is True,
            "targetFiles": safe_list(detail.get("targetFiles"), 240, 12),
            "affectedSubsystems": safe_list(detail.get("affectedSubsystems"), 80, 12),
            "focusedTests": safe_list(detail.get("focusedTests"), 180, 12),
            "commands": safe_list(detail.get("commands"), 300, 12),
            "requiredTraceKeys": safe_list(detail.get("requiredTraceKeys"), 120, 20),
            "decision": safe_scalar(detail.get("decision") or "local_contract_ready", 80),
        })
    return actions


def validate_producer_bundle_evidence(
    patchdrop_root: Path,
    role: str,
    topic: str,
    expected_source_root_hash: str = "",
) -> dict[str, Any]:
    role_slug = slug(role)
    topic_slug = slug(topic)
    bundle = f"{topic_slug}-{role_slug}-v3"
    node_dir = patchdrop_root / role_slug
    paths = {
        "patch": node_dir / f"{bundle}.patch",
        "report": node_dir / f"{bundle}.report.md",
        "verifyLog": node_dir / f"{bundle}.verify.log",
        "sha256": node_dir / f"{bundle}.sha256.txt",
        "manifest": node_dir / f"{bundle}.manifest.json",
        "pendingNotice": patchdrop_root / f"{topic_slug}.{role_slug}-pending.md",
    }
    failures: list[str] = []
    missing = [name for name, path in paths.items() if not path.is_file()]
    if missing:
        failures.append("producer-sidecars-missing:" + ",".join(sorted(missing)))

    raw_secret_hits = 0
    for path in paths.values():
        if path.is_file():
            raw = path.read_text(encoding="utf-8", errors="ignore")
            raw_secret_hits += sum(len(pattern.findall(raw)) for pattern in PATCH_SECRET_PATTERNS)
    if raw_secret_hits:
        failures.append("secret-leak-risk")
    mode_summary = patch_mode_summary(paths["patch"]) if paths["patch"].is_file() else empty_patch_mode_summary()
    filemode_lines = mode_summary["filemodeLineCount"]
    if mode_summary["filemodeViolationCount"]:
        failures.append("filemode-blocked")
    binary_markers = binary_patch_marker_count(paths["patch"]) if paths["patch"].is_file() else 0
    if binary_markers:
        failures.append("binary-patch-blocked")
    diff_headers = diff_header_count(paths["patch"]) if paths["patch"].is_file() else 0
    if paths["patch"].is_file() and paths["patch"].stat().st_size > 0 and diff_headers == 0:
        failures.append("producer-patch-not-unified-diff")
    elif paths["patch"].is_file() and patch_structure_violation_count_text(
        paths["patch"].read_text(encoding="utf-8", errors="replace")
    ):
        failures.append("producer-patch-not-unified-diff")
    forbidden_paths = forbidden_patch_paths(paths["patch"]) if paths["patch"].is_file() else []
    if forbidden_paths:
        failures.append("forbidden-path:" + ",".join(sorted({item["reason"] for item in forbidden_paths})))

    manifest_data: dict[str, Any] = {}
    if paths["manifest"].is_file():
        try:
            loaded = json.loads(paths["manifest"].read_text(encoding="utf-8", errors="ignore").lstrip("\ufeff"))
            if isinstance(loaded, dict):
                manifest_data = loaded
            else:
                failures.append("producer-manifest-invalid")
        except Exception:
            failures.append("producer-manifest-invalid")
    source_root_input_hash = ""
    if manifest_data:
        if manifest_data.get("schemaVersion") != "patchdrop-producer-v3":
            failures.append("producer-manifest-schema")
        if manifest_data.get("topic") != topic_slug:
            failures.append("producer-manifest-topic")
        if manifest_data.get("slug") != topic_slug:
            failures.append("producer-manifest-slug")
        if manifest_data.get("node") != role_slug:
            failures.append("producer-manifest-node")
        if manifest_data.get("activePatch") != f"{bundle}.patch":
            failures.append("producer-active-patch-mismatch")
        if manifest_data.get("desktopFinalProof") != "evidence_needed":
            failures.append("producer-desktop-proof-not-pending")
        source_root_input_hash = safe_scalar(manifest_data.get("sourceRootInputHash", ""), 120)
        source_root_hash = safe_scalar(manifest_data.get("sourceRootHash", ""), 120)
        if re.fullmatch(r"[a-f0-9]{64}", source_root_input_hash) is None:
            failures.append("producer-source-root-input-hash-invalid")
        if re.fullmatch(r"[a-f0-9]{64}", source_root_hash) is None:
            failures.append("producer-source-root-hash-invalid")
        if expected_source_root_hash:
            if not source_root_input_hash or not source_root_hash:
                failures.append("producer-source-root-hash-missing")
            elif source_root_input_hash != expected_source_root_hash:
                failures.append("producer-source-root-mismatch")
        isolation = manifest_data.get("sourceIsolation") if isinstance(manifest_data.get("sourceIsolation"), dict) else {}
        git_root_ok = (
            isolation.get("gitRootPresent") is True
            and isolation.get("gitRootMatchesSourceRoot") is True
            and isolation.get("gitRootHash") == source_root_hash
        )
        if not git_root_ok:
            failures.append("producer-git-root-missing")
        if not (
            isolation.get("guard") == "PASS"
            and isolation.get("sourceRootKind") == "local-worktree"
            and isolation.get("sharedSourceRoot") is False
            and isolation.get("desktopCanonicalSourceRoot") is False
            and isolation.get("directCanonicalSourceEdit") is False
        ):
            failures.append("producer-source-isolation-violation")
        verification = manifest_data.get("verification") if isinstance(manifest_data.get("verification"), dict) else {}
        expected_metrics = {
            "diffHeaderCount": diff_headers,
            "filemodeLineCount": mode_summary["filemodeLineCount"],
            "allowedNewFileCount": mode_summary["allowedNewFileCount"],
            "filemodeViolationCount": mode_summary["filemodeViolationCount"],
            "forbiddenPathCount": len(forbidden_paths),
            "secretPatternHits": raw_secret_hits,
            "rawSecretPatternHits": raw_secret_hits,
        }
        for metric_name, expected_metric in expected_metrics.items():
            metric_value = verification.get(metric_name)
            if type(metric_value) is not int or metric_value != expected_metric:
                failures.append("producer-manifest-verification-" + metric_name)

    sha_verified = False
    if paths["sha256"].is_file():
        expected_files = {
            f"{bundle}.patch": paths["patch"],
            f"{bundle}.report.md": paths["report"],
            f"{bundle}.verify.log": paths["verifyLog"],
            f"{bundle}.manifest.json": paths["manifest"],
            f"../{topic_slug}.{role_slug}-pending.md": paths["pendingNotice"],
        }
        entries: dict[str, str] = {}
        for line in paths["sha256"].read_text(encoding="utf-8", errors="ignore").splitlines():
            parts = line.strip().lstrip("\ufeff").split(None, 1)
            if len(parts) != 2:
                continue
            entries[parts[1].strip()] = parts[0].strip().lower()
        missing_sha_entries = sorted(set(expected_files) - set(entries))
        if missing_sha_entries:
            failures.append("producer-sha-entry-missing:" + ",".join(missing_sha_entries))
        else:
            sha_verified = True
            for file_name, candidate in sorted(expected_files.items()):
                expected = entries[file_name]
                actual = sha256_file(candidate).lower()
                if not actual or actual != expected:
                    sha_verified = False
                    failures.append("producer-sha-mismatch:" + file_name)
                    break

    if paths["patch"].is_file() and paths["patch"].stat().st_size == 0:
        failures.append("producer-patch-empty")
    if paths["verifyLog"].is_file():
        verify_text = paths["verifyLog"].read_text(encoding="utf-8", errors="ignore")
        if "secretPatternHits=0" not in verify_text:
            failures.append("producer-secret-scan-missing")
        if "Desktop final proof: evidence_needed" not in verify_text:
            failures.append("producer-verify-desktop-proof-missing")

    valid = not failures
    return {
        "nodeRole": role_slug,
        "topic": topic_slug,
        "bundle": bundle,
        "valid": valid,
        "sidecarsComplete": not missing,
        "shaVerified": sha_verified,
        "rawSecretPatternHits": raw_secret_hits,
        "filemodeLineCount": filemode_lines,
        "allowedNewFileCount": mode_summary["allowedNewFileCount"],
        "filemodeViolationCount": mode_summary["filemodeViolationCount"],
        "binaryPatchMarkerCount": binary_markers,
        "diffHeaderCount": diff_headers,
        "forbiddenPathCount": len(forbidden_paths),
        "expectedSourceRootHash": expected_source_root_hash[:12],
        "sourceRootInputHash": source_root_input_hash[:12],
        "desktopFinalProof": "evidence_needed",
        "fileHash": sha256_file(paths["manifest"]),
        "failReason": ",".join(failures),
    }


def unrelated_patchdrop_evidence_summary(patchdrop_root: Path, current_topic: str, max_items: int = 20) -> dict[str, Any]:
    current_topic_slug = slug(current_topic)
    rows: list[dict[str, Any]] = []
    for role_slug in ("macmini", "notebook"):
        node_dir = patchdrop_root / role_slug
        if not node_dir.is_dir():
            continue
        manifests = sorted(
            (path for path in node_dir.glob("*-v3.manifest.json") if path.is_file()),
            key=lambda item: item.stat().st_mtime,
            reverse=True,
        )
        for manifest_path in manifests:
            try:
                data = json.loads(manifest_path.read_text(encoding="utf-8", errors="ignore").lstrip("\ufeff"))
            except Exception:
                continue
            if not isinstance(data, dict):
                continue

            raw_topic = safe_scalar(data.get("topic"), 120).strip()
            raw_legacy_slug = safe_scalar(data.get("slug"), 120).strip()
            topic_slug = slug(raw_topic) if raw_topic else ""
            if not topic_slug and raw_legacy_slug:
                topic_slug = slug(raw_legacy_slug)
            if not topic_slug:
                suffix = f"-{role_slug}-v3.manifest.json"
                if manifest_path.name.endswith(suffix):
                    topic_slug = slug(manifest_path.name[: -len(suffix)])
            if not topic_slug or topic_slug == current_topic_slug:
                continue

            raw_role = safe_scalar(data.get("nodeRole") or data.get("node") or role_slug, 32).lower()
            if raw_role and slug(raw_role) != role_slug:
                continue
            bundle_type = safe_scalar(data.get("bundleType") or data.get("bundle_type") or "", 80).lower()
            source_patch_included = data.get("sourcePatchIncluded")
            evidence_kind = "report-only" if bundle_type == "report-only" or source_patch_included is False else "producer-bundle"
            source_root_info = data.get("sourceRoot") if isinstance(data.get("sourceRoot"), dict) else {}
            source_isolation = data.get("sourceIsolation") if isinstance(data.get("sourceIsolation"), dict) else {}
            source_root_kind = safe_scalar(
                source_root_info.get("sourceRootKind") or source_isolation.get("sourceRootKind") or "unknown",
                80,
            )
            desktop_proof = safe_scalar(data.get("desktopFinalProof") or data.get("desktopProof") or "", 80)
            status = safe_scalar(data.get("status") or "", 80)
            proof_text = f"{desktop_proof} {status}".lower()
            pending = (
                not desktop_proof
                or "pending" in proof_text
                or "evidence_needed" in proof_text
                or "supporting_evidence" in proof_text
            )
            rows.append({
                "nodeRole": role_slug,
                "topic": topic_slug,
                "kind": evidence_kind,
                "sourceRootKind": source_root_kind,
                "sourcePatchIncluded": bool(source_patch_included) if isinstance(source_patch_included, bool) else None,
                "desktopFinalProof": desktop_proof or "evidence_needed",
                "status": status,
                "manifest": manifest_path.name,
                "fileHash": sha256_file(manifest_path),
                "pendingDesktopProof": pending,
            })

    report_only_count = sum(1 for row in rows if row.get("kind") == "report-only")
    pending_count = sum(1 for row in rows if row.get("pendingDesktopProof"))
    topics = sorted({safe_scalar(row.get("topic", ""), 120) for row in rows if row.get("topic")})
    return {
        "total": len(rows),
        "reportedItemCount": min(len(rows), max_items),
        "reportOnlyCount": report_only_count,
        "producerBundleCount": len(rows) - report_only_count,
        "pendingCount": pending_count,
        "topics": topics[:max_items],
        "items": rows[:max_items],
    }


def producer_patch_sidecar_hash(patchdrop_root: Path, role: str, topic: str) -> str:
    role_slug = slug(role)
    topic_slug = slug(topic)
    bundle = f"{topic_slug}-{role_slug}-v3"
    patch_path = patchdrop_root / role_slug / f"{bundle}.patch"
    return sha256_file(patch_path).lower() if patch_path.is_file() else ""


def latest_dispatch_packet_for_role(patchdrop_root: Path, role: str, topic: str = "") -> dict[str, Any]:
    dispatch_dir = patchdrop_root / "dispatch"
    if not dispatch_dir.exists():
        return {}
    topic_slug = slug(topic)
    if topic_slug:
        packets = [dispatch_dir / f"{topic_slug}-desktop-dispatch.json"]
    else:
        packets = sorted(
            (path for path in dispatch_dir.glob("*-desktop-dispatch.json") if path.is_file()),
            key=lambda item: item.stat().st_mtime,
            reverse=True,
        )
    for packet_path in packets:
        if not packet_path.is_file():
            continue
        try:
            data = json.loads(packet_path.read_text(encoding="utf-8", errors="ignore").lstrip("\ufeff"))
        except Exception:
            continue
        if not isinstance(data, dict):
            continue
        for item in data.get("packets", []):
            if isinstance(item, dict) and safe_scalar(item.get("nodeRole", ""), 32).lower() == role:
                return item
    return {}


def dispatch_source_root_hash(dispatch_packet: dict[str, Any]) -> str:
    expected_source_root_hash = safe_scalar(dispatch_packet.get("sourceRootInputHash", ""), 120)
    if expected_source_root_hash:
        return expected_source_root_hash
    expected_source_root = safe_scalar(dispatch_packet.get("sourceRoot", ""), 500).strip()
    return stable_hash(expected_source_root) if expected_source_root else ""


def dispatch_node_smoke_query_hash(dispatch_packet: dict[str, Any]) -> str:
    return safe_scalar(dispatch_packet.get("nodeSmokeQueryHash", ""), 120)


def dispatch_producer_command_hash(patchdrop_root: Path, role: str, topic: str) -> str:
    command_file = dispatch_file_for_topic(patchdrop_root, topic, f"{role}.commands.txt")
    return sha256_file(command_file) if command_file is not None else ""


def dispatch_integrity_status(patchdrop_root: Path, topic: str) -> dict[str, Any]:
    topic_slug = slug(topic)
    dispatch_dir = patchdrop_root / "dispatch"
    packet_path = dispatch_dir / f"{topic_slug}-desktop-dispatch.json"
    failures: list[str] = []
    data: dict[str, Any] = {}
    if not packet_path.is_file():
        failures.append("dispatch-packet-missing")
    else:
        try:
            loaded = json.loads(packet_path.read_text(encoding="utf-8", errors="ignore").lstrip("\ufeff"))
            if isinstance(loaded, dict):
                data = loaded
            else:
                failures.append("dispatch-packet-invalid")
        except Exception:
            failures.append("dispatch-packet-invalid")

    index = data.get("dispatchArtifactIndex") if isinstance(data.get("dispatchArtifactIndex"), dict) else {}
    sidecar_raw = safe_scalar(index.get("dispatchSha256Sidecar", ""), 1000)
    sidecar_path = Path(sidecar_raw) if sidecar_raw else dispatch_dir / f"{topic_slug}-dispatch.sha256.txt"
    if sidecar_raw and not sidecar_path.is_absolute():
        sidecar_path = dispatch_dir / sidecar_raw
    sidecar_path = sidecar_path.resolve()

    raw_covered = index.get("sha256CoveredArtifacts")
    if not isinstance(raw_covered, list) or not raw_covered:
        raw_covered = data.get("dispatchArtifacts") if isinstance(data.get("dispatchArtifacts"), list) else []
    covered_paths: list[Path] = []
    for item in raw_covered:
        text = safe_scalar(item, 1000)
        if not text:
            continue
        candidate = Path(text)
        if not candidate.is_absolute():
            candidate = dispatch_dir / text
        if candidate.resolve() == sidecar_path:
            continue
        covered_paths.append(candidate.resolve())

    entries: dict[str, str] = {}
    sidecar_line_count = 0
    invalid_line_count = 0
    if not sidecar_path.is_file():
        failures.append("dispatch-sha-sidecar-missing")
    else:
        for line in sidecar_path.read_text(encoding="utf-8", errors="ignore").splitlines():
            clean = line.strip().lstrip("\ufeff")
            if not clean:
                continue
            sidecar_line_count += 1
            parts = clean.split(None, 1)
            if len(parts) != 2 or not re.fullmatch(r"[a-fA-F0-9]{64}", parts[0]):
                invalid_line_count += 1
                continue
            entries[parts[1].strip()] = parts[0].lower()
    if invalid_line_count:
        failures.append("dispatch-sha-invalid-lines")
    if not covered_paths:
        failures.append("dispatch-covered-artifacts-missing")

    mismatch_count = 0
    missing_entry_count = 0
    missing_file_count = 0
    seen_names: set[str] = set()
    for covered_path in covered_paths:
        name = covered_path.name
        if name in seen_names:
            continue
        seen_names.add(name)
        if not covered_path.is_file():
            missing_file_count += 1
            failures.append(f"dispatch-artifact-missing:{name}")
            continue
        expected = entries.get(name)
        if not expected:
            missing_entry_count += 1
            failures.append(f"dispatch-sha-entry-missing:{name}")
            continue
        actual = sha256_file(covered_path).lower()
        if actual != expected:
            mismatch_count += 1
            failures.append(f"dispatch-sha-mismatch:{name}")

    ok = not failures
    return {
        "ok": ok,
        "topic": topic_slug,
        "dispatchPacketExists": packet_path.is_file(),
        "dispatchPacketHash": sha256_file(packet_path) if packet_path.is_file() else "",
        "sidecarValid": ok,
        "sidecarPath": str(sidecar_path) if sidecar_path.is_file() else "",
        "sidecarHash": sha256_file(sidecar_path) if sidecar_path.is_file() else "",
        "sidecarLineCount": sidecar_line_count,
        "coveredArtifactCount": len(seen_names),
        "missingFileCount": missing_file_count,
        "missingEntryCount": missing_entry_count,
        "mismatchCount": mismatch_count,
        "failReason": ",".join(failures),
    }


def command_pathspecs_from_text(text: str) -> list[str]:
    values: list[str] = []
    for match in re.finditer(r"--pathspec\s+(?:'([^']*)'|\"([^\"]*)\"|([^\s]+))", text):
        raw = next((group for group in match.groups() if group is not None), "")
        normalized = raw.strip().replace("\\", "/")
        if normalized and normalized not in values:
            values.append(normalized)
    return values


def dispatch_next_action(patchdrop_root: Path, role: str, topic: str = "") -> dict[str, Any]:
    command_file = (
        dispatch_file_for_topic(patchdrop_root, topic, f"{role}.commands.txt")
        if topic
        else latest_dispatch_file(patchdrop_root, f"*-{role}.commands.txt")
    )
    packet = latest_dispatch_packet_for_role(patchdrop_root, role, topic)
    if command_file is None:
        return {
            "action": "render-dispatch-command-file",
            "nodeRole": "desktop",
            "targetRole": role,
            "toolName": "desktop_dispatch_packet",
            "sourceRoot": safe_scalar(packet.get("sourceRoot", ""), 500),
            "producerPatchdropRoot": safe_scalar(packet.get("producerPatchdropRoot", ""), 500),
            "desktopPatchdropRoot": safe_scalar(packet.get("desktopPatchdropRoot", ""), 500),
            "desktopSourceRootExists": packet.get("desktopSourceRootExists"),
            "decision": "evidence_needed",
            "hint": "run desktop_dispatch_packet with write_dispatch=true and dispatch_dir under PatchDrop",
        }
    action = {
        "action": "run-producer-command-file",
        "nodeRole": role,
        "targetRole": role,
        "commandFile": str(command_file),
        "producerCommandFile": producer_visible_dispatch_path(
            safe_scalar(packet.get("producerPatchdropRoot", ""), 500),
            command_file.name,
        ),
        "fileHash": sha256_file(command_file),
        "sourceRoot": safe_scalar(packet.get("sourceRoot", ""), 500),
        "producerPatchdropRoot": safe_scalar(packet.get("producerPatchdropRoot", ""), 500),
        "desktopPatchdropRoot": safe_scalar(packet.get("desktopPatchdropRoot", ""), 500),
        "desktopSourceRootExists": packet.get("desktopSourceRootExists"),
        "decision": "evidence_needed",
    }
    if packet.get("desktopSourceRootExists") is False:
        action["hint"] = "Desktop cannot see this producer root; verify it on the producer host or regenerate dispatch with producer_roots and producer_patchdrop_roots"
    return action


def mcp_node_setup_next_action(patchdrop_root: Path, role: str, topic: str = "") -> dict[str, Any]:
    packet = latest_dispatch_packet_for_role(patchdrop_root, role, topic)
    source_root = safe_scalar(packet.get("sourceRoot", ""), 500)
    desktop_visible = packet.get("desktopSourceRootExists")
    command = ""
    config_path = ""
    audit_log = ""
    if source_root:
        config_path = setup_config_path(source_root, role)
        audit_log = setup_audit_log_path(source_root, role)
        command = producer_setup_command(
            "python3" if role == "macmini" else "python",
            "scripts/awx_mcp_node_setup.py",
            source_root.replace("\\", "/") if role == "macmini" else source_root,
            "C:/AbandonWare/demo-1/demo-1/src" if role == "macmini" else "C:\\AbandonWare\\demo-1\\demo-1\\src",
            config_path,
            role,
            quote_fn=sh_quote if role == "macmini" else ps_quote,
            audit_log_path=audit_log,
        )
    action = {
        "action": "run-mcp-node-setup",
        "nodeRole": role,
        "targetRole": role,
        "toolName": "mcp_node_setup_runner",
        "sourceRoot": source_root,
        "desktopSourceRootExists": desktop_visible,
        "configPath": config_path,
        "auditLog": audit_log,
        "command": command,
        "desktopFinalProof": "evidence_needed",
        "decision": "evidence_needed",
    }
    if not source_root:
        action["failReason"] = "producer-source-root-missing"
    return action


def collect_external_evidence_next_action(
    evidence_dir: Path,
    patchdrop_root: Path,
    role: str,
    topic: str,
) -> dict[str, Any]:
    role_slug = slug(role)
    topic_slug = slug(topic)
    proof_dir = patchdrop_root / "external-node-proof"
    return {
        "action": "collect-external-evidence-files",
        "nodeRole": "desktop",
        "targetRole": role_slug,
        "topic": topic_slug,
        "desktopEvidencePaths": {
            "nodeSmoke": str(evidence_dir / f"{role_slug}-node-smoke.json"),
            "producerHandoff": str(evidence_dir / f"{role_slug}-producer-handoff.json"),
        },
        "patchdropProofDir": str(proof_dir),
        "requiredSidecars": list(PATCHDROP_HANDOFF_REQUIRED_ARTIFACTS),
        "requiredSourceIsolation": {
            "sourceIsolation.guard": "PASS",
            "sourceRootKind": "local-worktree",
            "directCanonicalSourceEdit": False,
            "desktopFinalProof": "evidence_needed",
            "rawSecretPatternHits": 0,
        },
        "producerCommands": [
            f"python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> --canonical-root C:\\AbandonWare\\demo-1\\demo-1\\src --node-role {role_slug}",
            f"python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> --canonical-root C:\\AbandonWare\\demo-1\\demo-1\\src --patchdrop-root <PatchDrop> --producer-script <PatchDrop>\\producer_bundle.py --node-role {role_slug} --topic {topic_slug} --pathspec <relative/source/path>",
        ],
        "decision": "evidence_needed",
    }


def supabase_live_proof_next_action(root: Path, completion_actions: list[str]) -> dict[str, Any]:
    artifact_paths = [
        "data/db-gap-report/supabase-execute-sql-collection.packet.json",
        "data/db-gap-report/supabase-query-results.template.json",
        "data/db-gap-report/supabase-readonly-snapshot.sql",
        "data/db-gap-report/supabase-schema-snapshot.json",
    ]
    packet = load_json_file(root / artifact_paths[0])
    queries = packet.get("queries") if isinstance(packet.get("queries"), list) else []
    required_result_names = [
        safe_scalar(query.get("name", ""), 120)
        for query in queries
        if isinstance(query, dict) and safe_scalar(query.get("name", ""), 120)
    ]
    packet_next_actions = [
        safe_scalar(action, 100)
        for action in (packet.get("nextActions") if isinstance(packet.get("nextActions"), list) else [])
        if safe_scalar(action, 100)
    ]
    next_actions = packet_next_actions or [
        "set_SUPABASE_PROJECT_REF",
        "authenticate_supabase_mcp_or_cli",
        "execute_each_query_once",
        "collect_get_advisors_rows",
        "run_supabase_schema_snapshot_import",
        "rerun_db_gap_scanner",
    ]
    if "rerun_completion_audit" in completion_actions and "rerun_completion_audit" not in next_actions:
        next_actions.append("rerun_completion_audit")
    return {
        "action": "collect-supabase-live-proof",
        "nodeRole": "desktop",
        "targetService": "supabase",
        "readOnly": True,
        "mutationAllowed": False,
        "mcpEndpointTemplate": (
            "https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}"
            "&read_only=true&features=database,debugging,docs"
        ),
        "requiredEnv": [
            {"name": "SUPABASE_PROJECT_REF", "sensitive": False},
        ],
        "mcpOAuthSupported": True,
        "supportedAuthModes": ["supabase_mcp_oauth_session", "manual_SUPABASE_ACCESS_TOKEN"],
        "manualAuthSensitiveEnvRefs": ["SUPABASE_ACCESS_TOKEN"],
        "requiredMcpTools": ["execute_sql", "get_advisors"],
        "artifactPaths": artifact_paths,
        "queryCount": bounded_int(packet.get("queryCount"), len(required_result_names), 0, 100),
        "requiredResultNames": required_result_names,
        "resultPathRecommendation": safe_scalar(
            packet.get("resultPathRecommendation") or "data/db-gap-report/supabase-query-results.json",
            240,
        ),
        "advisorResultPathRecommendation": "data/db-gap-report/supabase-advisors.json",
        "importTool": safe_scalar(packet.get("importTool") or "supabase_schema_snapshot_import", 100),
        "docsRefs": [
            "https://supabase.com/docs/guides/ai-tools/mcp",
            "https://supabase.com/docs/guides/api/securing-your-api",
            "https://supabase.com/docs/guides/security/product-security",
            "https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically",
        ],
        "nextActions": next_actions,
        "decision": "evidence_needed",
    }


def mcp_client_config_next_action() -> dict[str, Any]:
    config_file = "main/resources/mcp/awx-control-tower-mcp-client.sample.json"
    config_path = Path(__file__).resolve().parents[1] / config_file
    action = {
        "action": "configure-mcp-client",
        "nodeRole": "desktop",
        "targetRoles": ["macmini", "notebook"],
        "configFile": config_file,
        "requiredCwd": "producer-local-worktree-or-clone",
        "decision": "evidence_needed",
        "hint": "import this secret-free config and set cwd to the producer-local worktree before running producer command files",
    }
    if config_path.is_file():
        action["fileHash"] = sha256_file(config_path)
    else:
        action["failReason"] = "mcp-client-config-missing"
    return action


def archive_index_next_action(index: Path, index_source: str) -> dict[str, Any]:
    index_text = str(index)
    index_hash = stable_hash(index_text)
    source_label = "default.archive-index" if "BackupsXS" in index_source else safe_scalar(index_source, 80)
    return {
        "action": "provide-archive-index",
        "nodeRole": "desktop",
        "indexPathSource": source_label,
        "expectedPathHash": index_hash,
        "expectedPathLength": len(index_text),
        "acceptedInputs": [
            "payload.archive_index",
            "ARCHIVE_INDEX",
            "NAS_ARCHIVE_ROOT/index.jsonl",
            "repo-local archive index path",
        ],
        "verifyHint": f"archive_index_path_hash={index_hash} indexPathLength={len(index_text)}",
        "rerunHint": "provide index_path or ARCHIVE_INDEX/NAS_ARCHIVE_ROOT, then rerun archive_search",
        "nextActions": archive_missing_index_next_actions(),
        "decision": "evidence_needed",
    }


def janitor_apply_gate_next_action(patchdrop_root: Path, topic: str, roles: list[str]) -> dict[str, Any]:
    topic_slug = slug(topic)
    role_list = [safe_scalar(role, 32).lower() for role in roles if safe_scalar(role, 32).strip()]
    promote_commands = [
        f"powershell -NoProfile -ExecutionPolicy Bypass -File .\\__patch_drop__\\janitor_promote_producer_pending.ps1 -Topic {topic_slug} -Node {role}"
        for role in role_list
    ]
    owner_expr = "$DesktopLeaseOwner"
    lease_begin = (
        f"powershell -NoProfile -ExecutionPolicy Bypass -File .\\__patch_drop__\\source_edit_session.ps1 "
        f"-Action begin -Role desktop-consumer -Root . -Topic {topic_slug} -OwnerId {owner_expr} -TtlMinutes 180"
    )
    apply_command = (
        f"powershell -NoProfile -ExecutionPolicy Bypass -File .\\__patch_drop__\\janitor_apply_one.ps1 "
        f"-PatchName {topic_slug}-v3.patch -SourceLeaseOwnerId {owner_expr}"
    )
    lease_end = (
        f"powershell -NoProfile -ExecutionPolicy Bypass -File .\\__patch_drop__\\source_edit_session.ps1 "
        f"-Action end -Role desktop-consumer -Root . -Topic {topic_slug} -OwnerId {owner_expr}"
    )
    return {
        "action": "run-desktop-janitor-apply-gate",
        "nodeRole": "desktop",
        "topic": topic_slug,
        "patchdropRoot": str(patchdrop_root),
        "desktopFinalProof": "evidence_needed",
        "proofOnlyIntake": True,
        "oneAcceptedBundleAtATime": True,
        "activeTopLevelGuard": "active-top-level-exists",
        "inventoryCommand": "powershell -NoProfile -ExecutionPolicy Bypass -File .\\__patch_drop__\\janitor_inventory.ps1",
        "promoteCommands": promote_commands,
        "leaseBeginCommand": lease_begin,
        "applyCommand": apply_command,
        "leaseEndCommand": lease_end,
        "decision": "ready-for-desktop-janitor-review",
    }


def patchdrop_handoff_contract(pathspec_count: int = 0) -> dict[str, Any]:
    return {
        "workMode": "producer-local-worktree",
        "outputMode": "patchdrop-unified-diff-sidecars",
        "directCanonicalSourceEditAllowed": False,
        "desktopFinalProof": "evidence_needed",
        "requiredArtifacts": list(PATCHDROP_HANDOFF_REQUIRED_ARTIFACTS),
        "pathspecScoped": pathspec_count > 0,
    }


def truthy(value: Any, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if not text:
        return default
    return text in {"1", "true", "yes", "on"}


def producer_command_plan(payload: dict[str, Any]) -> dict[str, Any]:
    node_role = safe_scalar(payload.get("nodeRole", "macmini"), 32).lower()
    if node_role not in {"macmini", "notebook"}:
        return {
            "ok": False,
            "nodeRole": node_role,
            "commands": [],
            "proofPath": "",
            "desktopEvidencePath": "",
            "desktopFinalProof": "evidence_needed",
            "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
            "sourceIsolation": {
                "guard": "FAIL",
                "sourceRootKind": "unsupported-role",
                "sharedSourceRoot": False,
                "desktopCanonicalSourceRoot": False,
                "directCanonicalSourceEdit": False,
            },
            "evidence_needed": ["nodeRole must be macmini or notebook"],
            "decision": "producer_command_plan_failed",
            "failReason": "unsupported-node-role",
        }

    source_root_raw = safe_scalar(payload.get("source_root") or payload.get("root") or ".", 500)
    patchdrop_root_raw = safe_scalar(payload.get("patchdrop_root") or "__patch_drop__", 500)
    canonical_root_raw = safe_scalar(payload.get("canonical_root") or "C:/AbandonWare/demo-1/demo-1/src", 500)
    shared_root_raw = safe_scalar(payload.get("shared_root") or source_root_raw, 500)
    topic = safe_scalar(payload.get("topic") or "patchdrop-handoff", 120)
    raw_pathspec = payload.get("pathspec") or payload.get("pathspecs") or []
    if isinstance(raw_pathspec, str):
        pathspecs = [raw_pathspec]
    elif isinstance(raw_pathspec, list):
        pathspecs = [safe_scalar(item, 240) for item in raw_pathspec if safe_scalar(item, 240)]
    else:
        pathspecs = []

    source_root = resolve_path(source_root_raw)
    canonical_root = resolve_path(canonical_root_raw)
    source_root_cmd = host_path_for_role(source_root_raw, node_role)
    patchdrop_root_cmd = host_path_for_role(patchdrop_root_raw, node_role)
    producer_canonical_root_cmd = safe_scalar(
        payload.get("producer_canonical_root") or "<desktop-canonical-root>",
        500,
    )
    shared_root_cmd = host_path_for_role(shared_root_raw, node_role)
    source_root_input_hash = stable_hash(source_root_cmd.strip())
    node_smoke_query = topic + " external node proof"
    node_smoke_query_hash = stable_hash(node_smoke_query)
    proof_dir = host_path_join(patchdrop_root_cmd, "external-node-proof")
    proof_path = host_path_join(proof_dir, f"{node_role}-node-smoke.json")
    handoff_proof_path = host_path_join(proof_dir, f"{node_role}-producer-handoff.json")
    producer_command_file = host_path_join(patchdrop_root_cmd, "dispatch", f"{slug(topic)}-{node_role}.commands.txt")
    dispatch_sha_sidecar = host_path_join(patchdrop_root_cmd, "dispatch", f"{slug(topic)}-dispatch.sha256.txt")
    desktop_evidence_path = Path("data") / "agent-handoff" / "mcp-control-tower" / f"{node_role}-node-smoke.json"
    kit_dir = host_path_join(patchdrop_root_cmd, "producer-kit", f"{slug(topic)}-producer-kit")
    kit_install_script = host_path_join(
        kit_dir,
        "INSTALL.macmini.sh" if node_role == "macmini" else "INSTALL.notebook.ps1",
    )
    producer_kit_manifest_hash = safe_scalar(payload.get("producer_kit_manifest_hash", ""), 80).lower()
    node_setup_script = host_path_join(shared_root_cmd, "scripts", "awx_mcp_node_setup.py")
    node_smoke_script = host_path_join(shared_root_cmd, "scripts", "awx_mcp_node_smoke.py")
    handoff_script = host_path_join(shared_root_cmd, "scripts", "awx_mcp_producer_handoff.py")
    producer_script = host_path_join(source_root_cmd, "__patch_drop__", "producer_bundle.py")
    config_path = setup_config_path(source_root_cmd,node_role)
    audit_log_path = setup_audit_log_path(source_root_cmd,node_role)
    proof_json_check = (
        'import json,sys; '
        'd=json.load(open(sys.argv[1], encoding="utf-8-sig")); '
        'iso=d.get("sourceIsolation") or {}; '
        'fail=[]; '
        'fail += [] if d.get("ok") is True else ["ok"]; '
        'fail += [] if d.get("nodeRole") == sys.argv[2] else ["nodeRole"]; '
        'fail += [] if d.get("sourceRootInputHash") == sys.argv[3] else ["sourceRootInputHash"]; '
        'expected_query_hash = sys.argv[4] if len(sys.argv) > 4 else ""; '
        'fail += [] if (not expected_query_hash or d.get("queryHash") == expected_query_hash) else ["queryHash"]; '
        'fail += [] if int(d.get("rawSecretPatternHits") or 0) == 0 else ["secret"]; '
        'safe_iso = iso.get("guard") == "PASS" and iso.get("sourceRootKind") == "local-worktree" and iso.get("sharedSourceRoot") is False and iso.get("desktopCanonicalSourceRoot") is False and iso.get("directCanonicalSourceEdit") is False and iso.get("gitRootPresent") is True and iso.get("gitRootMatchesSourceRoot") is True and bool(iso.get("gitRootHash")); '
        'fail += [] if safe_iso else ["sourceIsolation"]; '
        'fail and print("[AWX][producer] node-smoke-invalid-proof " + ",".join(fail), file=sys.stderr); '
        'sys.exit(1 if fail else 0)'
    )
    handoff_json_check = (
        'import json,re,sys; '
        'd=json.load(open(sys.argv[1], encoding="utf-8-sig")); '
        'bundle=d.get("bundle") or {}; '
        'patch_hash=str(bundle.get("patchHash") or ""); '
        'fail=[]; '
        'fail += [] if d.get("ok") is True else ["ok"]; '
        'fail += [] if d.get("nodeRole") == sys.argv[2] else ["nodeRole"]; '
        'actual_source_root_hash = d.get("sourceRootInputHash") or d.get("sourceRootHash"); '
        'fail += [] if actual_source_root_hash == sys.argv[3] else ["sourceRootInputHash"]; '
        'expected_command_hash = sys.argv[4] if len(sys.argv) > 4 else ""; '
        'fail += [] if (not expected_command_hash or d.get("producerCommandHash") == expected_command_hash) else ["producerCommandHash"]; '
        'fail += [] if int(d.get("rawSecretPatternHits") or 0) == 0 else ["secret"]; '
        'fail += [] if bundle.get("promotionReady") is True else ["promotionReady"]; '
        'fail += [] if bundle.get("sidecarsComplete") is True else ["sidecarsComplete"]; '
        'fail += [] if bundle.get("shaVerified") is True else ["shaVerified"]; '
        'fail += [] if int(bundle.get("diffHeaderCount") or 0) > 0 else ["diffHeaderCount"]; '
        'fail += [] if re.fullmatch(r"[A-Fa-f0-9]{64}", patch_hash) else ["patchHash"]; '
        'fail += [] if d.get("desktopFinalProof") == "evidence_needed" and bundle.get("desktopFinalProof") == "evidence_needed" else ["desktopFinalProof"]; '
        'fail += [] if not d.get("failReason") else ["failReason"]; '
        'fail and print("[AWX][producer] producer-handoff-invalid-proof " + ",".join(fail), file=sys.stderr); '
        'sys.exit(1 if fail else 0)'
    )

    shared_source = is_shared_source_path(source_root_raw)
    desktop_canonical = (
        is_relative_to_path(source_root, canonical_root)
        or is_relative_to_path(source_root, Path("C:/AbandonWare/demo-1/demo-1/src"))
    )
    source_root_kind = "shared-root" if shared_source else ("desktop-canonical" if desktop_canonical else "local-worktree")
    source_isolation = {
        "guard": "PASS" if source_root_kind == "local-worktree" else "FAIL",
        "sourceRootKind": source_root_kind,
        "sharedSourceRoot": shared_source,
        "desktopCanonicalSourceRoot": desktop_canonical,
        "directCanonicalSourceEdit": desktop_canonical,
    }
    if source_isolation["guard"] != "PASS":
        return {
            "ok": False,
            "nodeRole": node_role,
            "commands": [],
            "proofPath": proof_path,
            "desktopEvidencePath": desktop_evidence_path.as_posix(),
            "desktopFinalProof": "evidence_needed",
            "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
            "sourceIsolation": source_isolation,
            "evidence_needed": ["switch to a producer-local worktree before generating PatchDrop commands"],
            "decision": "producer_command_plan_failed",
            "failReason": "smb-direct-edit",
        }
    if not pathspecs:
        return {
            "ok": False,
            "nodeRole": node_role,
            "sourceRoot": str(source_root),
            "canonicalRoot": str(canonical_root),
            "proofPath": proof_path,
            "handoffProofPath": handoff_proof_path,
            "desktopEvidencePath": desktop_evidence_path.as_posix(),
            "desktopFinalProof": "evidence_needed",
            "commands": [],
            "outputCount": 0,
            "sourceRootInputHash": source_root_input_hash,
            "nodeSmokeQueryHash": node_smoke_query_hash,
            "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
            "sourceIsolation": source_isolation,
            "pathspecCount": 0,
            "handoffContract": patchdrop_handoff_contract(0),
            "evidence_needed": [
                "pathspec-required: producer bundle handoff requires at least one relative --pathspec"
            ],
            "decision": "producer_command_plan_failed",
            "failReason": "pathspec-required",
        }

    required_tool_files = [node_setup_script, node_smoke_script, handoff_script, producer_script]
    if node_role == "macmini":
        commands = [
            producer_kit_bootstrap_command(
                kit_install_script,
                source_root_cmd,
                required_tool_files,
                node_role,
                producer_kit_manifest_hash,
            ),
            producer_required_file_preflight(required_tool_files, node_role),
            producer_git_root_preflight(source_root_cmd, node_role),
            producer_setup_command(
                "python3",
                node_setup_script,
                source_root_cmd,
                producer_canonical_root_cmd,
                config_path,
                node_role,
                audit_log_path=audit_log_path,
            ),
            f"mkdir -p {sh_quote(proof_dir)}",
            (
                f"python3 {sh_quote(node_smoke_script)} "
                f"--root {sh_quote(source_root_cmd)} "
                f"--canonical-root {sh_quote(producer_canonical_root_cmd)} "
                f"--node-role {sh_quote(node_role)} "
                f"--query {sh_quote(node_smoke_query)} "
                f"> {sh_quote(proof_path)}"
            ),
            f"python3 -c {sh_quote(proof_json_check)} {sh_quote(proof_path)} {sh_quote(node_role)} {sh_quote(source_root_input_hash)} {sh_quote(node_smoke_query_hash)}",
            f"ProducerCommandFile={sh_quote(producer_command_file)}",
            '[ -f "$ProducerCommandFile" ] || { echo "[AWX][producer] producer-command-file-missing: $ProducerCommandFile" >&2; exit 1; }',
            'ProducerCommandHash=$(python3 -c \'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())\' "$ProducerCommandFile")',
            f"DispatchShaSidecar={sh_quote(dispatch_sha_sidecar)}",
            '[ -f "$DispatchShaSidecar" ] || { echo "[AWX][producer] dispatch-sha-sidecar-missing: $DispatchShaSidecar" >&2; exit 1; }',
            'ExpectedProducerCommandHash=$(python3 -c \'import pathlib,sys; target=pathlib.Path(sys.argv[2]).name; found="";\nfor line in open(sys.argv[1], encoding="utf-8", errors="ignore"):\n    parts=line.strip().split(None, 1)\n    if len(parts) == 2 and pathlib.Path(parts[1]).name == target:\n        found=parts[0].lower(); break\nprint(found)\' "$DispatchShaSidecar" "$ProducerCommandFile")',
            '[ -n "$ExpectedProducerCommandHash" ] || { echo "[AWX][producer] dispatch-command-sha-missing: $ProducerCommandFile" >&2; exit 1; }',
            '[ "$ExpectedProducerCommandHash" = "$ProducerCommandHash" ] || { echo "[AWX][producer] dispatch-command-sha-mismatch: $ProducerCommandFile" >&2; exit 1; }',
            (
                producer_handoff_command(
                    "python3",
                    handoff_script,
                    source_root_cmd,
                    producer_canonical_root_cmd,
                    patchdrop_root_cmd,
                    producer_script,
                    node_role,
                    topic,
                    pathspecs,
                    audit_log_path=audit_log_path,
                    producer_command_hash_expr='"$ProducerCommandHash"',
                )
                + f" > {sh_quote(handoff_proof_path)}"
            ),
            f"python3 -c {sh_quote(handoff_json_check)} {sh_quote(handoff_proof_path)} {sh_quote(node_role)} {sh_quote(source_root_input_hash)} \"$ProducerCommandHash\"",
        ]
    else:
        commands = [
            producer_kit_bootstrap_command(
                kit_install_script,
                source_root_cmd,
                required_tool_files,
                node_role,
                producer_kit_manifest_hash,
            ),
            producer_required_file_preflight(required_tool_files, node_role),
            producer_git_root_preflight(source_root_cmd, node_role),
            producer_setup_command(
                "python",
                node_setup_script,
                source_root_cmd,
                producer_canonical_root_cmd,
                config_path,
                node_role,
                quote_fn=ps_quote,
                audit_log_path=audit_log_path,
            ),
            "$SetupExit = $LASTEXITCODE",
            'if ($SetupExit -ne 0) { Write-Error "[AWX][producer] node-setup-failed"; exit $SetupExit }',
            f"New-Item -ItemType Directory -Force -Path {ps_quote(proof_dir)} | Out-Null",
            f"$ProofPath = {ps_quote(proof_path)}",
            (
                f"python {ps_quote(node_smoke_script)} "
                f"--root {ps_quote(source_root_cmd)} "
                f"--canonical-root {ps_quote(producer_canonical_root_cmd)} "
                f"--node-role {ps_quote(node_role)} "
                f"--query {ps_quote(node_smoke_query)} "
                "1> $ProofPath"
            ),
            "$SmokeExit = $LASTEXITCODE",
            'if ($SmokeExit -ne 0) { Write-Error "[AWX][producer] node-smoke-failed"; exit $SmokeExit }',
            f"python -c {ps_quote(proof_json_check)} $ProofPath {ps_quote(node_role)} {ps_quote(source_root_input_hash)} {ps_quote(node_smoke_query_hash)}",
            "$JsonExit = $LASTEXITCODE",
            'if ($JsonExit -ne 0) { Write-Error "[AWX][producer] node-smoke-invalid-json"; exit $JsonExit }',
            f"$ProducerCommandFile = {ps_quote(producer_command_file)}",
            'if (-not (Test-Path -LiteralPath $ProducerCommandFile -PathType Leaf)) { Write-Error "[AWX][producer] producer-command-file-missing: $ProducerCommandFile"; exit 1 }',
            "$ProducerCommandHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ProducerCommandFile).Hash.ToLowerInvariant()",
            f"$DispatchShaSidecar = {ps_quote(dispatch_sha_sidecar)}",
            'if (-not (Test-Path -LiteralPath $DispatchShaSidecar -PathType Leaf)) { Write-Error "[AWX][producer] dispatch-sha-sidecar-missing: $DispatchShaSidecar"; exit 1 }',
            "$ProducerCommandName = Split-Path -Leaf $ProducerCommandFile",
            "$ExpectedProducerCommandHash = Get-Content -LiteralPath $DispatchShaSidecar | ForEach-Object { if ($_ -match '^\\s*([A-Fa-f0-9]{64})\\s+(.+?)\\s*$') { if ((Split-Path -Leaf $Matches[2]) -eq $ProducerCommandName) { $Matches[1].ToLowerInvariant() } } } | Select-Object -First 1",
            'if ([string]::IsNullOrWhiteSpace($ExpectedProducerCommandHash)) { Write-Error "[AWX][producer] dispatch-command-sha-missing: $ProducerCommandFile"; exit 1 }',
            'if ($ExpectedProducerCommandHash -ne $ProducerCommandHash) { Write-Error "[AWX][producer] dispatch-command-sha-mismatch: $ProducerCommandFile"; exit 1 }',
            f"$HandoffProofPath = {ps_quote(handoff_proof_path)}",
            (
                producer_handoff_command(
                    "python",
                    handoff_script,
                    source_root_cmd,
                    producer_canonical_root_cmd,
                    patchdrop_root_cmd,
                    producer_script,
                    node_role,
                    topic,
                    pathspecs,
                    quote_fn=ps_quote,
                    audit_log_path=audit_log_path,
                    producer_command_hash_expr="$ProducerCommandHash",
                )
                + " 1> $HandoffProofPath"
            ),
            "$HandoffExit = $LASTEXITCODE",
            'if ($HandoffExit -ne 0) { Write-Error "[AWX][producer] producer-handoff-failed"; exit $HandoffExit }',
            f"python -c {ps_quote(handoff_json_check)} $HandoffProofPath {ps_quote(node_role)} {ps_quote(source_root_input_hash)} $ProducerCommandHash",
            "$HandoffJsonExit = $LASTEXITCODE",
            'if ($HandoffJsonExit -ne 0) { Write-Error "[AWX][producer] producer-handoff-invalid-json"; exit $HandoffJsonExit }',
        ]

    return {
        "ok": True,
        "nodeRole": node_role,
        "topic": slug(topic),
        "commands": commands,
        "outputCount": len(commands),
        "proofPath": proof_path,
        "handoffProofPath": handoff_proof_path,
        "desktopEvidencePath": desktop_evidence_path.as_posix(),
        "setupAuditLog": audit_log_path,
        "desktopFinalProof": "evidence_needed",
        "sourceRootInputHash": source_root_input_hash,
        "nodeSmokeQueryHash": node_smoke_query_hash,
        "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
        "sourceIsolation": source_isolation,
        "pathspecCount": len(pathspecs),
        "producerKitManifestHash": producer_kit_manifest_hash,
        "handoffContract": patchdrop_handoff_contract(len(pathspecs)),
        "evidence_needed": ["run these commands on the producer host; Desktop final proof remains pending"],
        "decision": "producer_command_plan",
    }


def desktop_dispatch_packet(payload: dict[str, Any]) -> dict[str, Any]:
    node_role = safe_scalar(payload.get("nodeRole", "desktop"), 32).lower()
    if node_role != "desktop":
        return {
            "ok": False,
            "nodeRole": node_role,
            "packets": [],
            "desktopAuditCommand": "",
            "desktopIntakeCommand": "",
            "desktopFinalProof": "evidence_needed",
            "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
            "evidence_needed": ["nodeRole must be desktop"],
            "decision": "desktop_dispatch_packet_failed",
            "failReason": "unsupported-node-role",
        }

    canonical_root = safe_scalar(payload.get("canonical_root") or "C:/AbandonWare/demo-1/demo-1/src", 500)
    producer_canonical_root_raw = safe_scalar(
        payload.get("producer_canonical_root") or str(resolve_path(canonical_root)),
        500,
    )
    patchdrop_root = safe_scalar(payload.get("patchdrop_root") or "__patch_drop__", 500)
    desktop_patchdrop_root = str(resolve_path(patchdrop_root))
    evidence_dir = safe_scalar(payload.get("evidence_dir") or "data/agent-handoff/mcp-control-tower", 500)
    topic = safe_scalar(payload.get("topic") or "patchdrop-handoff", 120)
    # Explicit evidence_dir remains an exact caller-selected destination.
    # Default dispatches isolate each topic instead of rewriting shared role files.
    if not payload.get("evidence_dir"):
        evidence_dir = f"{evidence_dir}/tasks/{slug(topic)}"
    raw_roles = payload.get("target_roles") if isinstance(payload.get("target_roles"), list) else ["macmini", "notebook"]
    target_roles = [
        role
        for role in (safe_scalar(item, 32).lower() for item in raw_roles)
        if role in {"macmini", "notebook"}
    ]
    if not target_roles:
        target_roles = ["macmini", "notebook"]

    producer_roots = payload.get("producer_roots") if isinstance(payload.get("producer_roots"), dict) else {}
    producer_patchdrop_roots = (
        payload.get("producer_patchdrop_roots")
        if isinstance(payload.get("producer_patchdrop_roots"), dict)
        else {}
    )
    role_pathspecs = next(
        (
            item
            for item in (
                payload.get("role_pathspec"),
                payload.get("role_pathspecs"),
                payload.get("producer_pathspecs"),
            )
            if isinstance(item, dict)
        ),
        {},
    )
    default_roots = {
        "macmini": "/Users/nninn/agent/macmini/awx-macmini",
        "notebook": "C:/AbandonWare/worktrees/awx-notebook",
    }
    default_patchdrop_roots = {
        "macmini": "/Volumes/WinSrc/demo-1/demo-1/src/__patch_drop__",
        "notebook": "Z:\\PatchDrop",
    }
    raw_shared_pathspec = payload.get("pathspec") or payload.get("pathspecs") or []
    if isinstance(raw_shared_pathspec, str):
        shared_pathspec_set = {safe_scalar(raw_shared_pathspec, 240).replace("\\", "/")}
    elif isinstance(raw_shared_pathspec, list):
        shared_pathspec_set = {
            safe_scalar(item, 240).replace("\\", "/")
            for item in raw_shared_pathspec
            if safe_scalar(item, 240)
        }
    else:
        shared_pathspec_set = set()
    role_pathspec_sets: dict[str, set[str]] = {}
    for role in target_roles:
        raw_role_pathspec = role_pathspecs.get(role)
        if isinstance(raw_role_pathspec, str):
            role_pathspec_sets[role] = {safe_scalar(raw_role_pathspec, 240).replace("\\", "/")}
        elif isinstance(raw_role_pathspec, list):
            role_pathspec_sets[role] = {
                safe_scalar(item, 240).replace("\\", "/")
                for item in raw_role_pathspec
                if safe_scalar(item, 240)
            }
    effective_pathspec_sets: dict[str, set[str]] = {
        role: role_pathspec_sets.get(role, shared_pathspec_set)
        for role in target_roles
    }
    require_producer_bundles = truthy(payload.get("require_producer_bundles", True), True)
    missing_pathspec_roles = [
        role for role in target_roles
        if not effective_pathspec_sets.get(role)
    ]
    roles_literal = "@(" + ",".join(ps_quote(role) for role in target_roles) + ")"
    topic_literal = ps_quote(slug(topic))
    require_literal = "$true" if require_producer_bundles else "$false"
    desktop_audit_command = (
        f"@{{ nodeRole = 'desktop'; patchdrop_root = {ps_quote(patchdrop_root)}; "
        f"evidence_dir = {ps_quote(evidence_dir)}; "
        f"required_roles = {roles_literal}; topic = {topic_literal}; require_producer_bundles = {require_literal} }} | "
        "ConvertTo-Json -Depth 20 -Compress | "
        "python .\\scripts\\awx_mcp_toolbox.py external_evidence_audit"
    )
    desktop_intake_command = (
        f"@{{ nodeRole = 'desktop'; patchdrop_root = {ps_quote(patchdrop_root)}; "
        f"evidence_dir = {ps_quote(evidence_dir)}; required_roles = {roles_literal}; "
        f"topic = {topic_literal}; require_producer_bundles = {require_literal} }} | "
        "ConvertTo-Json -Depth 20 -Compress | "
        "python .\\scripts\\awx_mcp_toolbox.py external_evidence_intake"
    )
    if not require_producer_bundles and missing_pathspec_roles:
        safe_missing_roles = [safe_scalar(role, 32) for role in missing_pathspec_roles]
        example_role_pathspec = {
            role: [f"<relative/source/path-owned-by-{role}>"]
            for role in safe_missing_roles
            if role
        }
        rerun_payload = {
            "nodeRole": "desktop",
            "canonical_root": canonical_root,
            "patchdrop_root": patchdrop_root,
            "topic": slug(topic),
            "role_pathspec": example_role_pathspec,
            "write_dispatch": True,
            "require_producer_bundles": True,
        }
        return {
            "ok": True,
            "nodeRole": "desktop",
            "topic": slug(topic),
            "packets": [],
            "desktopAuditCommand": desktop_audit_command,
            "desktopIntakeCommand": desktop_intake_command,
            "evidenceDir": evidence_dir,
            "desktopFinalProof": "evidence_needed",
            "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
            "outputCount": 0,
            "producerBundlesRequired": False,
            "externalEvidenceMode": "optional",
            "missingPathspecRoles": safe_missing_roles,
            "pathspecOverlap": [],
            "evidence_needed": [],
            "optionalNextActions": [{
                "action": "assign-producer-role-pathspec",
                "nodeRole": "desktop",
                "missingRoles": safe_missing_roles,
                "decision": "supporting_evidence_optional",
                "hint": "pass role_pathspec only when explicitly assigning Mac mini or Notebook producer work",
                "nonOverlapRule": "each relative path must appear under exactly one producer role",
                "examplePayload": rerun_payload,
                "rerunCommand": (
                    "@'\n"
                    f"{json.dumps(rerun_payload, ensure_ascii=True, sort_keys=True)}\n"
                    "'@ | python .\\scripts\\awx_mcp_toolbox.py desktop_control_loop"
                ),
            }],
            "dispatchArtifacts": [],
            "nextActions": [],
            "decision": "desktop_dispatch_packet_optional",
            "failReason": "",
        }
    if require_producer_bundles and missing_pathspec_roles:
        return {
            "ok": False,
            "nodeRole": "desktop",
            "topic": slug(topic),
            "packets": [],
            "desktopAuditCommand": "",
            "desktopIntakeCommand": "",
            "desktopFinalProof": "evidence_needed",
            "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
            "outputCount": 0,
            "producerBundlesRequired": True,
            "externalEvidenceMode": "required",
            "missingPathspecRoles": missing_pathspec_roles,
            "pathspecOverlap": [],
            "evidence_needed": [
                "pathspec-required: assign each producer role at least one relative source path with role_pathspec before requiring PatchDrop bundle proof"
            ],
            "dispatchArtifacts": [],
            "decision": "desktop_dispatch_packet_failed",
            "failReason": "pathspec-required",
        }
    pathspec_overlap: set[str] = set()
    for index, role in enumerate(target_roles):
        left = effective_pathspec_sets.get(role, set())
        for other in target_roles[index + 1:]:
            pathspec_overlap.update(left & effective_pathspec_sets.get(other, set()))
    if pathspec_overlap:
        overlap_list = sorted(pathspec_overlap)
        return {
            "ok": False,
            "nodeRole": "desktop",
            "topic": slug(topic),
            "packets": [],
            "desktopAuditCommand": "",
            "desktopIntakeCommand": "",
            "desktopFinalProof": "evidence_needed",
            "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
            "outputCount": 0,
            "producerBundlesRequired": require_producer_bundles,
            "externalEvidenceMode": "required" if require_producer_bundles else "optional",
            "pathspecOverlap": overlap_list,
            "evidence_needed": [
                "pathspec-overlap: assign each source path to only one producer role with role_pathspec before writing PatchDrop dispatch"
            ],
            "dispatchArtifacts": [],
            "decision": "desktop_dispatch_packet_failed",
            "failReason": "pathspec-overlap",
        }
    packets: list[dict[str, Any]] = []
    evidence_needed: list[str] = []
    topic_slug = slug(topic)
    producer_kit_manifest_path = (
        resolve_path(patchdrop_root)
        / "producer-kit"
        / f"{topic_slug}-producer-kit"
        / "producer-kit.manifest.json"
    )
    producer_kit_manifest_hash = (
        sha256_file(producer_kit_manifest_path)
        if producer_kit_manifest_path.is_file()
        else ""
    )
    for role in target_roles:
        plan_payload = dict(payload)
        plan_payload["nodeRole"] = role
        plan_payload["source_root"] = safe_scalar(producer_roots.get(role) or default_roots[role], 500)
        plan_payload["canonical_root"] = canonical_root
        plan_payload["producer_canonical_root"] = host_path_for_role(producer_canonical_root_raw, role)
        plan_payload["patchdrop_root"] = safe_scalar(
            producer_patchdrop_roots.get(role) or default_patchdrop_roots.get(role) or desktop_patchdrop_root,
            500,
        )
        if not safe_scalar(plan_payload.get("shared_root"), 500):
            plan_payload["shared_root"] = plan_payload["source_root"]
        role_pathspec = role_pathspecs.get(role)
        if isinstance(role_pathspec, list):
            plan_payload["pathspec"] = role_pathspec
        elif isinstance(role_pathspec, str):
            plan_payload["pathspec"] = role_pathspec
        plan_payload["topic"] = topic
        plan_payload["producer_kit_manifest_hash"] = producer_kit_manifest_hash
        source_root_exists = resolve_path(plan_payload["source_root"]).exists()
        plan = producer_command_plan(plan_payload)
        packets.append({
            "nodeRole": role,
            "ok": bool(plan.get("ok", False)),
            "sourceRoot": plan_payload["source_root"],
            "producerPatchdropRoot": plan_payload["patchdrop_root"],
            "desktopPatchdropRoot": desktop_patchdrop_root,
            "desktopSourceRootExists": source_root_exists,
            "commands": plan.get("commands", []),
            "proofPath": plan.get("proofPath", ""),
            "desktopEvidencePath": plan.get("desktopEvidencePath", ""),
            "desktopFinalProof": plan.get("desktopFinalProof", "evidence_needed"),
            "sourceRootInputHash": safe_scalar(plan.get("sourceRootInputHash", ""), 120),
            "nodeSmokeQueryHash": safe_scalar(plan.get("nodeSmokeQueryHash", ""), 120),
            "producerKitManifestHash": safe_scalar(plan.get("producerKitManifestHash", ""), 120),
            "sourceIsolation": plan.get("sourceIsolation", {}),
            "pathspecCount": plan.get("pathspecCount", 0),
            "handoffContract": plan.get("handoffContract", {}),
            "failReason": plan.get("failReason", ""),
        })
        for item in plan.get("evidence_needed", []):
            text = safe_scalar(item, 240)
            if text and text not in evidence_needed:
                evidence_needed.append(text)
        if not source_root_exists:
            evidence_needed.append(
                f"producer source root not visible on Desktop role={role}; verify on producer host or override producer_roots and producer_patchdrop_roots"
            )

    evidence_needed.append("external Mac mini/Notebook host smoke output remains evidence_needed until Desktop intake/audit passes")
    result = {
        "ok": all(packet.get("ok") for packet in packets),
        "nodeRole": "desktop",
        "topic": slug(topic),
        "packets": packets,
        "desktopAuditCommand": desktop_audit_command,
        "desktopIntakeCommand": desktop_intake_command,
            "evidenceDir": evidence_dir,
        "desktopFinalProof": "evidence_needed",
        "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
        "outputCount": len(packets),
        "producerBundlesRequired": require_producer_bundles,
        "externalEvidenceMode": "required" if require_producer_bundles else "optional",
        "evidence_needed": evidence_needed,
        "dispatchArtifacts": [],
        "decision": "desktop_dispatch_packet",
        "failReason": "" if all(packet.get("ok") for packet in packets) else "producer-command-plan-failed",
    }
    write_flag = str(payload.get("write_dispatch", "")).strip().lower() in {"1", "true", "yes", "on"}
    if write_flag:
        write_result = write_desktop_dispatch_artifacts(result, payload, patchdrop_root, topic)
        result["dispatchDir"] = write_result.get("dispatchDir", "")
        result["dispatchArtifacts"] = write_result.get("dispatchArtifacts", [])
        result["artifactCount"] = write_result.get("artifactCount", 0)
        result["dispatchArtifactIndex"] = write_result.get("dispatchArtifactIndex", {})
        result["nextActions"] = write_result.get("nextActions", [])
        for item in write_result.get("evidence_needed", []):
            if item not in result["evidence_needed"]:
                result["evidence_needed"].append(item)
        if not write_result.get("ok", False):
            result["ok"] = False
            result["failReason"] = safe_scalar(write_result.get("failReason") or "dispatch-write-failed", 120)
    return result


def desktop_control_loop(payload: dict[str, Any]) -> dict[str, Any]:
    node_role = safe_scalar(payload.get("nodeRole", "desktop"), 32).lower()
    if node_role != "desktop":
        return {
            "ok": False,
            "nodeRole": node_role,
            "localReady": False,
            "completionReady": False,
            "desktopFinalProof": "evidence_needed",
            "sourceScan": {},
            "dispatch": {},
            "dispatchIntegrity": {},
            "externalEvidence": {},
            "nextActions": [],
            "evidence_needed": ["nodeRole must be desktop"],
            "decision": "desktop_control_loop_failed",
            "failReason": "unsupported-node-role",
        }

    root = safe_scalar(payload.get("root") or payload.get("canonical_root") or ".", 500)
    configured_canonical_root = safe_scalar(payload.get("canonical_root"), 500)
    canonical_root = configured_canonical_root or str(resolve_path(root))
    patchdrop_root = safe_scalar(payload.get("patchdrop_root") or "__patch_drop__", 500)
    evidence_dir = safe_scalar(payload.get("evidence_dir") or "data/agent-handoff/mcp-control-tower", 500)
    topic = safe_scalar(payload.get("topic") or "mcp-control-loop", 120)
    raw_roles = payload.get("target_roles") if isinstance(payload.get("target_roles"), list) else ["macmini", "notebook"]
    target_roles = [
        role
        for role in (safe_scalar(item, 32).lower() for item in raw_roles)
        if role in {"macmini", "notebook"}
    ] or ["macmini", "notebook"]
    require_producer_bundles = truthy(payload.get("require_producer_bundles", False), False)
    require_supabase_live_proof = truthy(payload.get("require_supabase_live_proof", False), False)
    run_completion_audit = truthy(payload.get("run_completion_audit", True), True)

    source_payload = {
        "requestId": safe_scalar(payload.get("requestId", ""), 96),
        "sessionId": safe_scalar(payload.get("sessionId", ""), 96),
        "nodeRole": "desktop",
        "root": root,
    }
    scan = source_scan(source_payload)
    harmony_payload = {
        "requestId": safe_scalar(payload.get("requestId", ""), 96),
        "sessionId": safe_scalar(payload.get("sessionId", ""), 96),
        "nodeRole": "desktop",
        "root": root,
    }
    if isinstance(payload.get("runtimeProof"), dict):
        harmony_payload["runtimeProof"] = payload["runtimeProof"]
    harmony_scan_report = harmony_scan(harmony_payload)
    harmony_summary = {
        "ok": bool(harmony_scan_report.get("ok", False)),
        "harmonyScore": harmony_scan_report.get("harmonyScore", {}),
        "runtimeProof": harmony_scan_report.get("runtimeProof", {}),
        "runtimeProofSource": safe_scalar(harmony_scan_report.get("runtimeProofSource", ""), 80),
        "runtimeProofDetails": harmony_scan_report.get("runtimeProofDetails", {}),
        "traceCoverage": harmony_scan_report.get("traceCoverage", {}),
        "evidence_needed": harmony_scan_report.get("evidence_needed", []),
        "nextActions": harmony_scan_report.get("nextActions", []),
        "outputCount": harmony_scan_report.get("outputCount", 0),
        "decision": safe_scalar(harmony_scan_report.get("decision", ""), 80),
        "failReason": safe_scalar(harmony_scan_report.get("failReason", ""), 120),
    }
    peer_bus = peer_evidence_bus(
        {
            "requestId": safe_scalar(payload.get("requestId", ""), 96),
            "sessionId": safe_scalar(payload.get("sessionId", ""), 96),
            "nodeRole": "desktop",
            "root": root,
            "targetMetric": payload.get("targetMetric") or payload.get("target_metric") or "harmony",
        }
    )

    producer_kit: dict[str, Any] = {}
    if str(payload.get("write_producer_kit", "")).strip().lower() in {"1", "true", "yes", "on"}:
        kit_payload = dict(payload)
        kit_payload["nodeRole"] = "desktop"
        kit_payload["root"] = root
        kit_payload["patchdrop_root"] = patchdrop_root
        kit_payload["topic"] = topic
        if payload.get("producer_kit_dir"):
            kit_payload["output_dir"] = payload.get("producer_kit_dir")
        producer_kit = producer_kit_export(kit_payload)

    dispatch_payload = dict(payload)
    dispatch_payload["nodeRole"] = "desktop"
    dispatch_payload["canonical_root"] = canonical_root
    dispatch_payload["patchdrop_root"] = patchdrop_root
    if payload.get("evidence_dir"):
        dispatch_payload["evidence_dir"] = evidence_dir
    dispatch_payload["topic"] = topic
    dispatch_payload["target_roles"] = target_roles
    dispatch_payload["require_producer_bundles"] = require_producer_bundles
    dispatch = desktop_dispatch_packet(dispatch_payload)
    evidence_dir = dispatch.get("evidenceDir", evidence_dir)

    audit_payload = {
        "requestId": safe_scalar(payload.get("requestId", ""), 96),
        "sessionId": safe_scalar(payload.get("sessionId", ""), 96),
        "nodeRole": "desktop",
        "patchdrop_root": patchdrop_root,
        "evidence_dir": evidence_dir,
        "required_roles": target_roles,
        "topic": slug(topic),
        "require_producer_bundles": require_producer_bundles,
        "archive_index": safe_scalar(payload.get("archive_index", ""), 500),
    }
    external = external_evidence_audit(audit_payload)

    dispatch_next_actions: list[dict[str, Any]] = []
    missing_pathspec_roles = dispatch.get("missingPathspecRoles", [])
    if dispatch.get("failReason") == "pathspec-required" and isinstance(missing_pathspec_roles, list):
        safe_missing_roles = [safe_scalar(role, 32) for role in missing_pathspec_roles]
        example_role_pathspec = {
            role: [f"<relative/source/path-owned-by-{role}>"]
            for role in safe_missing_roles
            if role
        }
        rerun_payload = {
            "nodeRole": "desktop",
            "root": root,
            "canonical_root": canonical_root,
            "patchdrop_root": patchdrop_root,
            "topic": slug(topic),
            "role_pathspec": example_role_pathspec,
            "write_dispatch": True,
            "require_producer_bundles": True,
        }
        dispatch_next_actions.append({
            "action": "assign-producer-role-pathspec",
            "nodeRole": "desktop",
            "missingRoles": safe_missing_roles,
            "decision": "evidence_needed",
            "hint": "pass role_pathspec so each producer role owns at least one non-overlapping relative source path",
            "nonOverlapRule": "each relative path must appear under exactly one producer role",
            "examplePayload": rerun_payload,
            "rerunCommand": (
                "@'\n"
                f"{json.dumps(rerun_payload, ensure_ascii=True, sort_keys=True)}\n"
                "'@ | python .\\scripts\\awx_mcp_toolbox.py desktop_control_loop"
            ),
        })
    external_next_actions = list(external.get("nextActions", []))
    external_optional_next_actions = list(external.get("optionalNextActions", []))
    dispatch_run_next_actions = list(dispatch.get("nextActions", []))
    if require_producer_bundles:
        next_actions = dedupe_next_actions(
            dispatch_next_actions
            + dispatch_run_next_actions
            + list(producer_kit.get("nextActions", []))
            + external_next_actions
        )
        optional_next_actions = dedupe_next_actions(
            list(dispatch.get("optionalNextActions", []))
            + external_optional_next_actions
        )
    else:
        next_actions = dedupe_next_actions(
            dispatch_next_actions
            + dispatch_run_next_actions
            + list(producer_kit.get("nextActions", []))
        )
        optional_next_actions = dedupe_next_actions(
            list(dispatch.get("optionalNextActions", []))
            + external_next_actions
            + external_optional_next_actions
        )
    completion_audit_summary = (
        completion_audit_summary_for_root(
            root,
            require_supabase_live_proof=require_supabase_live_proof,
            include_supporting_next_actions=True,
        )
        if run_completion_audit
        else {
            "ok": True,
            "status": "deferred-to-caller",
            "nextActions": [],
            "nextActionDetails": [],
            "supportingEvidenceNextActions": [],
            "supportingEvidenceNextActionDetails": [],
        }
    )
    completion_audit_invocation = completion_audit_summary.get("_awxCompletionAuditInvocation")
    if run_completion_audit:
        if isinstance(completion_audit_invocation, dict):
            completion_audit_executed = bool(completion_audit_invocation.get("executed", False))
            completion_audit_failure_class = safe_scalar(
                completion_audit_invocation.get("failureClass"), 80
            ) or ("none" if completion_audit_executed else "completion-audit-invalid-payload")
        else:
            completion_audit_executed = bool(completion_audit_summary)
            completion_audit_failure_class = (
                "none" if completion_audit_executed else "completion-audit-invalid-payload"
            )
        completion_audit_evidence_source = (
            "nested-control-loop" if completion_audit_executed else "nested-control-loop-failed"
        )
    else:
        completion_audit_executed = False
        completion_audit_failure_class = "not-requested"
        completion_audit_evidence_source = "deferred-to-caller"
    completion_audit_next_actions = dedupe_scalar_list(
        [
            safe_scalar(action, 120)
            for action in completion_audit_summary.get("nextActions", [])
            if safe_scalar(action, 120)
        ]
        if isinstance(completion_audit_summary.get("nextActions"), list)
        else []
    )
    completion_audit_supporting_next_actions = dedupe_scalar_list(
        [
            safe_scalar(action, 120)
            for action in completion_audit_summary.get("supportingEvidenceNextActions", [])
            if safe_scalar(action, 120)
        ]
        if isinstance(completion_audit_summary.get("supportingEvidenceNextActions"), list)
        else []
    )
    source_contract_next_actions = source_contract_next_actions_for_root(root)
    if source_contract_next_actions:
        next_actions = dedupe_next_actions(next_actions + source_contract_next_actions)
    completion_audit_supabase_actions = dedupe_scalar_list(
        completion_audit_next_actions + completion_audit_supporting_next_actions
    )
    if any(
        "supabase" in action.lower()
        or action in {"set_SUPABASE_PROJECT_REF", "execute_each_query_once", "collect_get_advisors_rows"}
        for action in completion_audit_supabase_actions
    ):
        supabase_live_action = supabase_live_proof_next_action(resolve_path(root), completion_audit_supabase_actions)
        if require_supabase_live_proof:
            next_actions = dedupe_next_actions(next_actions + [supabase_live_action])
        else:
            optional_next_actions = dedupe_next_actions(optional_next_actions + [supabase_live_action])
    harmony_scan_next_actions = [
        safe_scalar(action, 120)
        for action in harmony_summary.get("nextActions", [])
        if safe_scalar(action, 120)
    ]
    evidence_needed: list[str] = []
    supporting_evidence_needed: list[str] = []

    def add_evidence_needed(target: list[str], value: Any) -> None:
        text = safe_scalar(value, 500)
        if text and text not in target:
            target.append(text)

    def desktop_only_supporting_evidence_text(value: Any) -> bool:
        text = safe_scalar(value, 500).lower()
        if not text:
            return False
        return (
            "mac mini/notebook" in text
            or "browser dom proof" in text
            or "computer use gui proof" in text
            or "supabase project-scoped mcp/cli proof" in text
        )

    if run_completion_audit and not completion_audit_executed:
        add_evidence_needed(
            evidence_needed,
            f"completion audit unavailable ({completion_audit_failure_class})",
        )

    for source_name, source in (
        ("scan", scan),
        ("harmony", harmony_summary),
        ("peer_bus", peer_bus),
        ("dispatch", dispatch),
        ("producer_kit", producer_kit),
    ):
        for item in source.get("evidence_needed", []) if isinstance(source.get("evidence_needed", []), list) else []:
            target = evidence_needed
            if (
                source_name == "peer_bus"
                and not require_producer_bundles
                and desktop_only_supporting_evidence_text(item)
            ):
                target = supporting_evidence_needed
            add_evidence_needed(target, item)
    external_evidence_target = evidence_needed if require_producer_bundles else supporting_evidence_needed
    for item in external.get("evidence_needed", []) if isinstance(external.get("evidence_needed", []), list) else []:
        add_evidence_needed(external_evidence_target, item)
    for item in external.get("optional_evidence_needed", []) if isinstance(external.get("optional_evidence_needed", []), list) else []:
        add_evidence_needed(supporting_evidence_needed, item)

    external_complete = bool(external.get("externalEvidenceComplete", False))
    kit_ok = True if not producer_kit else bool(producer_kit.get("ok", False))
    local_ok = bool(scan.get("ok", True)) and bool(harmony_summary.get("ok", True)) and bool(dispatch.get("ok", False)) and kit_ok
    completion_ready = local_ok and external_complete
    return {
        "ok": local_ok,
        "nodeRole": "desktop",
        "topic": slug(topic),
        "localReady": local_ok,
        "completionReady": completion_ready,
        "desktopFinalProof": "evidence_needed",
        "externalEvidenceComplete": external_complete,
        "supabaseLiveProofRequired": require_supabase_live_proof,
        "completionAuditRunRequested": run_completion_audit,
        "completionAuditExecuted": completion_audit_executed,
        "completionAuditEvidenceSource": completion_audit_evidence_source,
        "completionAuditFailureClass": completion_audit_failure_class,
        "sourceScan": scan,
        "harmonyScan": harmony_summary,
        "peerEvidenceBus": peer_bus,
        "dispatch": dispatch,
        "dispatchIntegrity": external.get("dispatchIntegrity", {}),
        "producerKit": producer_kit,
        "externalEvidence": external,
        "unrelatedPatchDropEvidence": external.get("unrelatedPatchDropEvidence", {}),
        "nextActions": next_actions,
        "optionalNextActions": optional_next_actions,
        "completionAuditNextActions": completion_audit_next_actions,
        "completionAuditNextActionDetails": completion_audit_summary.get("nextActionDetails", [])
        if isinstance(completion_audit_summary.get("nextActionDetails"), list)
        else [],
        "completionAuditSupportingEvidenceNextActions": completion_audit_summary.get("supportingEvidenceNextActions", [])
        if isinstance(completion_audit_summary.get("supportingEvidenceNextActions"), list)
        else [],
        "completionAuditSupportingEvidenceNextActionDetails": completion_audit_summary.get(
            "supportingEvidenceNextActionDetails", []
        )
        if isinstance(completion_audit_summary.get("supportingEvidenceNextActionDetails"), list)
        else [],
        "harmonyScanNextActions": harmony_scan_next_actions,
        "peerEvidenceBusNextActions": peer_bus.get("nextActions", []),
        "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
        "outputCount": len(next_actions),
        "evidence_needed": evidence_needed,
        "supportingEvidenceNeeded": supporting_evidence_needed,
        "decision": "external_evidence_complete" if external_complete else "external_evidence_needed",
        "failReason": "" if external_complete else "evidence-needed",
    }


def producer_kit_export(payload: dict[str, Any]) -> dict[str, Any]:
    """Stage and validate the whole kit; existing exports are immutable."""
    root = resolve_path(payload.get('root') or '.')
    exchange = resolve_path(payload.get('patchdrop_root') or root/'__patch_drop__')
    destination = resolve_path(payload.get('output_dir') or exchange/'producer-kit'/f"{slug(payload.get('topic') or 'awx-mcp-producer-kit')}-producer-kit")
    if payload.get('nodeRole','desktop') != 'desktop' or not is_relative_to_path(destination, exchange):
        return {'ok':False,'decision':'producer_kit_export_failed','failReason':'producer-kit-scope-violation','outputCount':0}
    try:
        plain_path(destination)
        state = local_state_root(root, os.environ.get('AWX_LOCAL_STATE_ROOT'))
        state.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='kit-stage-', dir=state) as folder:
            stage = Path(folder)/'kit'
            result = _producer_kit_build({**payload,'output_dir':str(stage),'patchdrop_root':folder})
            if not result['ok']:
                return {**result,'kitDir':'','manifestPath':'','outputCount':0}
            changes=[]
            expected=set()
            for path in sorted(stage.rglob('*')):
                if not path.is_file():continue
                rel=path.relative_to(stage);expected.add(rel.as_posix())
                target=plain_path(destination/rel);before=read_optional(target);after=path.read_bytes()
                if before is not None and before != after:raise Conflict('existing-kit-content-conflict')
                changes.append(Change(target,before,after))
            if destination.exists():
                for path in destination.rglob('*'):
                    plain_path(path)
                    if path.is_file() and path.relative_to(destination).as_posix() not in expected:
                        raise Conflict('existing-kit-extra-content')
            receipt=apply_changes(changes,state/'kit-export')
            return {**result,'kitDir':str(destination),'manifestPath':str(destination/'producer-kit.manifest.json'),
                    'transaction':receipt,'outputCount':receipt['changedCount']}
    except (Conflict,OSError) as error:
        return {'ok':False,'decision':'producer_kit_export_failed','failReason':str(error) if isinstance(error,Conflict) else 'producer-kit-io-failed','outputCount':0}


def _producer_kit_build(payload: dict[str, Any]) -> dict[str, Any]:
    node_role = safe_scalar(payload.get("nodeRole", "desktop"), 48).lower()
    root = resolve_path(payload.get("root") or ".")
    patchdrop_root = resolve_path(payload.get("patchdrop_root") or root / "__patch_drop__")
    topic = safe_scalar(payload.get("topic") or "awx-mcp-producer-kit", 120)
    kit_dir = resolve_path(payload.get("output_dir") or patchdrop_root / "producer-kit" / f"{slug(topic)}-producer-kit")
    if node_role != "desktop":
        return {
            "ok": False,
            "nodeRole": node_role,
            "kitDir": "",
            "manifestPath": "",
            "files": [],
            "desktopFinalProof": "evidence_needed",
            "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
            "evidence_needed": ["producer_kit_export must run on Desktop source owner"],
            "decision": "producer_kit_export_failed",
            "failReason": "unsupported-node-role",
        }
    if not is_relative_to_path(kit_dir, patchdrop_root):
        return {
            "ok": False,
            "nodeRole": "desktop",
            "kitDir": str(kit_dir),
            "manifestPath": "",
            "files": [],
            "desktopFinalProof": "evidence_needed",
            "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
            "evidence_needed": ["producer kit output_dir must stay under patchdrop_root"],
            "decision": "producer_kit_export_failed",
            "failReason": "producer-kit-outside-patchdrop",
        }

    kit_dir.mkdir(parents=True, exist_ok=True)
    files: list[dict[str, Any]] = []
    packaged_paths: set[str] = set()
    evidence_needed: list[str] = []
    missing_required: list[str] = []
    skill_files = [path.relative_to(root).as_posix() for path in (root/'.agents/skills').rglob('*')
                   if path.is_file() and not any(part.startswith('.') or part in {'__pycache__','tests'} for part in path.relative_to(root/'.agents/skills').parts)
                   and path.suffix not in {'.pyc','.pyo'} and '.bak_' not in path.name]
    for rel in dict.fromkeys((*PRODUCER_KIT_FILES,*sorted(skill_files))):
        src = root / rel
        plain_path(src)
        dest = kit_dir / rel
        optional_doc = (
            rel.startswith(".agents/skills/")
            or rel.startswith("agent-prompts/")
            or rel.startswith("data/agent-handoff/mcp-control-tower/skills/")
            or rel in {
                "data/agent-handoff/mcp-control-tower/README.md",
                "data/agent-handoff/mcp-control-tower/demo1_mcp_control_tower.prompt",
                "data/agent-handoff/mcp-control-tower/prompt-manifest.yaml",
            }
        )
        if not path_is_file(src):
            evidence_needed.append(f"{rel} / producer kit source file missing")
            if not optional_doc:
                missing_required.append(rel)
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        try:
            shutil.copy2(src, dest)
        except OSError:
            evidence_needed.append(f"{rel} / producer kit source file unreadable")
            if not optional_doc:
                missing_required.append(rel)
            continue
        files.append(candidate_row(kit_dir, dest))
        packaged_paths.add(rel)

    protected_doc_fallbacks = {
        ".agents/skills/demo1-mcp-control-tower/SKILL.md": "data/agent-handoff/mcp-control-tower/skills/demo1-mcp-control-tower/SKILL.md",
        ".agents/skills/archive-search/SKILL.md": "data/agent-handoff/mcp-control-tower/skills/archive-search/SKILL.md",
        ".agents/skills/archive-restore/SKILL.md": "data/agent-handoff/mcp-control-tower/skills/archive-restore/SKILL.md",
        ".agents/skills/verify-boot/SKILL.md": "data/agent-handoff/mcp-control-tower/skills/verify-boot/SKILL.md",
        ".agents/skills/build-error-miner/SKILL.md": "data/agent-handoff/mcp-control-tower/skills/build-error-miner/SKILL.md",
        ".agents/skills/run-pipeline/SKILL.md": "data/agent-handoff/mcp-control-tower/skills/run-pipeline/SKILL.md",
        "agent-prompts/agents/demo1_mcp_control_tower/system.md": "data/agent-handoff/mcp-control-tower/demo1_mcp_control_tower.prompt",
        "agent-prompts/out/demo1_mcp_control_tower.prompt": "data/agent-handoff/mcp-control-tower/demo1_mcp_control_tower.prompt",
    }
    for protected_doc, fallback_doc in protected_doc_fallbacks.items():
        if protected_doc not in packaged_paths or fallback_doc in packaged_paths:
            continue
        source = kit_dir / protected_doc
        fallback = kit_dir / fallback_doc
        try:
            fallback.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, fallback)
        except OSError:
            evidence_needed.append(f"{fallback_doc} / producer kit fallback doc copy failed")
            continue
        files.append(candidate_row(kit_dir, fallback))
        packaged_paths.add(fallback_doc)
    evidence_needed = [
        item
        for item in evidence_needed
        if not any(item.startswith(missing) and fallback in packaged_paths for missing, fallback in protected_doc_fallbacks.items())
    ]

    install_mac = kit_dir / "INSTALL.macmini.sh"
    install_notebook = kit_dir / "INSTALL.notebook.ps1"
    readme = kit_dir / "README.producer-kit.md"
    install_mac.write_text(render_macmini_install_script(), encoding="utf-8", newline="\n")
    install_notebook.write_text(render_notebook_install_script(), encoding="utf-8", newline="\n")
    readme.write_text(render_producer_kit_readme(topic), encoding="utf-8", newline="\n")
    for generated in (install_mac, install_notebook, readme):
        files.append(candidate_row(kit_dir, generated))

    raw_secret_hits = secret_hit_count(kit_dir, max_files=len(files)+10, include_generic=False, skip_tests=False)
    manifest_path = kit_dir / "producer-kit.manifest.json"
    manifest_data = {
        "schemaVersion": "awx.mcp.producer_kit.v1",
        "installerVersion": 2,
        "sourceRevision": run_git_head(root),
        "toolCatalogHash": sha256_file(kit_dir/'main/resources/mcp/awx-control-tower-tools.json') if (kit_dir/'main/resources/mcp/awx-control-tower-tools.json').is_file() else None,
        "sharedSkillCount": len(list((kit_dir/'.agents/skills').glob('*/SKILL.md'))),
        "topic": slug(topic),
        "desktopFinalProof": "evidence_needed",
        "sourcePolicy": {
            "producerInstallTarget": "producer-local-worktree-or-clone",
            "forbiddenInstallTarget": "Desktop canonical source root or shared SMB/NAS source mount",
        },
        "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
        "files": [{"path": p.relative_to(kit_dir).as_posix(), "sha256": sha256_file(p), "sizeBytes": p.stat().st_size}
                  for p in sorted(kit_dir.rglob('*')) if p.is_file()],
        "rawSecretPatternHits": raw_secret_hits,
    }
    manifest_path.write_text(json.dumps(manifest_data, ensure_ascii=True, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    files.append(candidate_row(kit_dir, manifest_path))
    ok = not missing_required and raw_secret_hits == 0
    fail_reason = "" if ok else ("secret-leak-risk" if raw_secret_hits else "missing-kit-source")
    producer_visible_kit_dir_posix = f"<producer-visible-patchdrop>/producer-kit/{slug(topic)}-producer-kit"
    producer_visible_kit_dir_windows = f"<producer-visible-patchdrop>\\producer-kit\\{slug(topic)}-producer-kit"
    producer_visible_mac_installer = producer_visible_kit_dir_posix + "/INSTALL.macmini.sh"
    producer_visible_notebook_installer = producer_visible_kit_dir_windows + "\\INSTALL.notebook.ps1"
    return {
        "ok": ok,
        "nodeRole": "desktop",
        "topic": slug(topic),
        "kitDir": str(kit_dir),
        "manifestPath": str(manifest_path),
        "files": files,
        "fileCount": len(files),
        "kitHash": stable_hash(files),
        "desktopFinalProof": "evidence_needed",
        "allowedEnvRefs": list(ALLOWED_CONTROL_TOWER_ENV_REFS),
        "nextActions": [
            {
                "action": "install-producer-kit",
                "nodeRole": "macmini",
                "command": f"bash {sh_quote(producer_visible_mac_installer)} <producer-local-worktree> macmini",
            },
            {
                "action": "install-producer-kit",
                "nodeRole": "notebook",
                "command": f"powershell -NoProfile -ExecutionPolicy Bypass -File {ps_quote(producer_visible_notebook_installer)} -ProducerRoot <producer-local-worktree> -NodeRole notebook",
            },
        ],
        "outputCount": len(files),
        "rawSecretPatternHits": raw_secret_hits,
        "evidence_needed": evidence_needed,
        "decision": "producer_kit_export" if ok else "producer_kit_export_failed",
        "failReason": fail_reason,
    }


def run_git_head(root: Path) -> str | None:
    result = subprocess.run(["git","--no-optional-locks","-C",str(root),"rev-parse","HEAD"],
                            capture_output=True,text=True,timeout=10)
    value=result.stdout.strip()
    return value if result.returncode==0 and re.fullmatch(r"[a-f0-9]{40,64}",value) else None


def render_macmini_install_script() -> str:
    return """#!/bin/sh
set -eu
[ "$#" -ge 1 ] || { echo "producer root required" >&2; exit 2; }
ProducerRoot="$1"
NodeRole="macbook"
if [ "$#" -ge 2 ]; then NodeRole="$2"; fi
KitRoot="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
# Safe installer owns Desktop canonical/shared guards, Git root validation,
# producer-kit.manifest.json hashes, configuration merges and exact backups.
if [ "$#" -ge 3 ]; then
  exec python3 -B "$KitRoot/scripts/awx_mcp_safe_install.py" --kit-root "$KitRoot" --producer-root "$ProducerRoot" --node-role "$NodeRole" --state-root "$3"
fi
exec python3 -B "$KitRoot/scripts/awx_mcp_safe_install.py" --kit-root "$KitRoot" --producer-root "$ProducerRoot" --node-role "$NodeRole"
"""


def render_notebook_install_script() -> str:
    return """param(
    [Parameter(Mandatory = $true)][string]$ProducerRoot,
    [ValidateSet("notebook","macmini","macbook")][string]$NodeRole = "notebook",
    [string]$StateRoot = ""
)
$ErrorActionPreference = "Stop"
$KitRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
# The shared Python owner checks Desktop canonical/shared paths, Git root,
# producer-kit.manifest.json, hashes and destination preimages before writes.
$Arguments = @("-B", (Join-Path $KitRoot "scripts/awx_mcp_safe_install.py"), "--kit-root", $KitRoot, "--producer-root", $ProducerRoot, "--node-role", $NodeRole)
if ($StateRoot) { $Arguments += @("--state-root", $StateRoot) }
& python @Arguments
exit $LASTEXITCODE
"""


def render_producer_kit_readme(topic: str) -> str:
    return f"""# AWX MCP Producer Kit

Topic: `{slug(topic)}`

Install this kit only into a Mac mini or Notebook producer-local worktree or
clone. Do not install it into the Desktop canonical source root or any shared
SMB/NAS source mount.

Mac mini:

```bash
bash INSTALL.macmini.sh /path/to/producer-worktree macmini
```

Notebook:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\\INSTALL.notebook.ps1 -ProducerRoot C:\\AbandonWare\\worktrees\\awx-notebook -NodeRole notebook
```

The installer validates and safely updates `__patch_drop__/producer_bundle.py` in the producer-local
worktree. Generated producer commands execute that local helper while writing
bundle artifacts to the shared PatchDrop exchange path. Settings, receipts and
timestamped backups stay in host-local AWX storage. Personal edits stop the update;
there is no force option. All shared skill definitions and runtime references
are included; development test fixtures and caches are excluded. Desktop final proof stays
`evidence_needed`.

After install, prefer the Desktop-rendered command file for this topic instead
of hand-typing smoke or handoff commands:

- Mac mini: `__patch_drop__/dispatch/{slug(topic)}-macmini.commands.txt`
- Notebook: `__patch_drop__/dispatch/{slug(topic)}-notebook.commands.txt`

Run the current Desktop-rendered command file after every Desktop refresh. If a
command file reports `producer-kit-installer-missing`, stop and resync the
producer-visible PatchDrop kit before doing any source investigation. Do not
continue from an older local copy of the tools, because stale producer helpers
can generate proof that Desktop must reject.

Those command files pin the Desktop canonical root, validate the node-smoke
`queryHash`, run the producer-local handoff helper, and require PatchDrop v3
sidecars (`.patch`, `.report.md`, `.verify.log`, `.sha256.txt`,
`.manifest.json`, and `pendingNotice`). Once producer proof arrives, Desktop runs
`__patch_drop__/dispatch/{slug(topic)}-desktop-intake.ps1` from the canonical
root and performs final verification there.
"""


def write_desktop_dispatch_artifacts(
    result: dict[str, Any],
    payload: dict[str, Any],
    patchdrop_root: str,
    topic: str,
) -> dict[str, Any]:
    patchdrop_dir = resolve_path(patchdrop_root)
    dispatch_raw = payload.get("dispatch_dir") or str(patchdrop_dir / "dispatch")
    dispatch_dir = resolve_path(dispatch_raw)
    if not is_relative_to_path(dispatch_dir, patchdrop_dir):
        return {
            "ok": False,
            "dispatchDir": dispatch_dir.as_posix(),
            "dispatchArtifacts": [],
            "evidence_needed": ["dispatch_dir must stay under patchdrop_root"],
            "failReason": "dispatch-dir-outside-patchdrop",
        }

    dispatch_dir.mkdir(parents=True, exist_ok=True)
    topic_slug = slug(topic)
    json_path = dispatch_dir / f"{topic_slug}-desktop-dispatch.json"
    desktop_path = dispatch_dir / f"{topic_slug}-desktop-intake.ps1"
    source_health_queue_path = dispatch_dir / f"{topic_slug}-source-health-producer-queue.json"
    artifact_paths = [json_path]
    source_health_queue = (
        payload.get("sourceHealthProducerQueue")
        if isinstance(payload.get("sourceHealthProducerQueue"), dict)
        else {}
    )
    source_health_queue_written = False
    if source_health_queue:
        queue_payload = {"sourceHealthProducerQueue": source_health_queue}
        source_health_queue_path.write_text(
            json.dumps(redact(queue_payload), ensure_ascii=False, indent=2, sort_keys=True),
            encoding="utf-8",
        )
        artifact_paths.append(source_health_queue_path)
        source_health_queue_written = True
    dispatch_next_actions: list[dict[str, Any]] = []

    for packet in result.get("packets", []):
        role = safe_scalar(packet.get("nodeRole", ""), 32).lower()
        if role not in {"macmini", "notebook"}:
            continue
        commands_path = dispatch_dir / f"{topic_slug}-{role}.commands.txt"
        commands = [safe_scalar(item, 2000) for item in packet.get("commands", [])]
        source_root = safe_scalar(packet.get("sourceRoot", ""), 500)
        source_root_exists = bool(packet.get("desktopSourceRootExists", False))
        if role == "macmini":
            producer_root = source_root.replace(chr(92), "/")
            prologue = [
                "set -euo pipefail",
                f"ProducerRoot={sh_quote(producer_root)}",
                '[ -d "$ProducerRoot" ] || { echo "[AWX][producer] evidence_needed: producer source root missing: $ProducerRoot" >&2; exit 1; }',
                f"cd {sh_quote(producer_root)}",
            ] if source_root else []
            epilogue: list[str] = []
        else:
            prologue = [
                "$ErrorActionPreference = 'Stop'",
                f"$ProducerRoot = {ps_quote(source_root)}",
                'if (-not (Test-Path -LiteralPath $ProducerRoot -PathType Container)) { Write-Error "[AWX][producer] evidence_needed: producer source root missing: $ProducerRoot"; exit 1 }',
                f"Push-Location {ps_quote(source_root)}",
            ] if source_root else []
            epilogue = ["Pop-Location"] if source_root else []
        commands_text = "\n".join(
            [
                f"# Run on {role} producer-local worktree or shell.",
                "# Do not edit the Desktop canonical source directly.",
                "# Create or sync a role-local worktree or clone first. This command file will not create ProducerRoot.",
                f"# Desktop source-root visibility: {source_root_exists}. If false, verify this path on the producer host or regenerate with producer_roots and producer_patchdrop_roots.",
                *prologue,
                *commands,
                *epilogue,
                "",
            ]
        )
        commands_path.write_text(commands_text, encoding="utf-8")
        artifact_paths.append(commands_path)
        producer_command_file = producer_visible_dispatch_path(
            safe_scalar(packet.get("producerPatchdropRoot", ""), 500),
            commands_path.name,
        )
        dispatch_next_actions.append({
            "action": "run-producer-command-file",
            "nodeRole": role,
            "targetRole": role,
            "commandFile": str(commands_path),
            "producerCommandFile": producer_command_file,
            "sourceRoot": source_root,
            "producerPatchdropRoot": safe_scalar(packet.get("producerPatchdropRoot", ""), 500),
            "desktopPatchdropRoot": safe_scalar(packet.get("desktopPatchdropRoot", ""), 500),
            "desktopSourceRootExists": source_root_exists,
            "producerKitManifestHash": safe_scalar(packet.get("producerKitManifestHash", ""), 120),
        })

    handoff_path = dispatch_dir / f"{topic_slug}-handoff.md"
    desktop_dispatch_covered_paths = [
        json_path,
        *[
            Path(safe_scalar(action.get("commandFile", ""), 500))
            for action in dispatch_next_actions
            if safe_scalar(action.get("action", ""), 80) == "run-producer-command-file"
            and safe_scalar(action.get("commandFile", ""), 500)
        ],
        desktop_path,
        handoff_path,
    ]
    desktop_dispatch_covered_lines = [
        f"    {ps_quote(str(path))}" + ("," if index < len(desktop_dispatch_covered_paths) - 1 else "")
        for index, path in enumerate(desktop_dispatch_covered_paths)
    ]
    desktop_dispatch_preflight = [
        f"  $DispatchShaSidecar = {ps_quote(str(dispatch_dir / f'{topic_slug}-dispatch.sha256.txt'))}",
        "  if (-not (Test-Path -LiteralPath $DispatchShaSidecar -PathType Leaf)) { Write-Error \"[AWX][desktop] desktop-dispatch-sha-sidecar-missing: $DispatchShaSidecar\"; exit 1 }",
        "  $DispatchCoveredFiles = @(",
        *desktop_dispatch_covered_lines,
        "  )",
        "  $DispatchShaRows = @{}",
        "  Get-Content -LiteralPath $DispatchShaSidecar | ForEach-Object { if ($_ -match '^\\s*([A-Fa-f0-9]{64})\\s+(.+?)\\s*$') { $DispatchShaRows[(Split-Path -Leaf $Matches[2])] = $Matches[1].ToLowerInvariant() } }",
        "  foreach ($DispatchCoveredFile in $DispatchCoveredFiles) {",
        "    if (-not (Test-Path -LiteralPath $DispatchCoveredFile -PathType Leaf)) { Write-Error \"[AWX][desktop] desktop-dispatch-artifact-missing: $DispatchCoveredFile\"; exit 1 }",
        "    $DispatchCoveredName = Split-Path -Leaf $DispatchCoveredFile",
        "    if (-not $DispatchShaRows.ContainsKey($DispatchCoveredName)) { Write-Error \"[AWX][desktop] desktop-dispatch-sha-missing: $DispatchCoveredName\"; exit 1 }",
        "    $DispatchCoveredHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $DispatchCoveredFile).Hash.ToLowerInvariant()",
        "    if ($DispatchShaRows[$DispatchCoveredName] -ne $DispatchCoveredHash) { Write-Error \"[AWX][desktop] desktop-dispatch-sha-mismatch: $DispatchCoveredName\"; exit 1 }",
        "  }",
    ]
    desktop_text = "\n".join(
        [
            "# Run on Desktop canonical root after Mac mini and Notebook proof files arrive.",
            "$ErrorActionPreference = 'Stop'",
            "Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -ErrorAction Stop",
            ". .\\__patch_drop__\\source_edit_lease_contract.ps1",
            f"$EvidenceDir = {ps_quote(result.get('evidenceDir') or payload.get('evidence_dir') or 'data/agent-handoff/mcp-control-tower/tasks/' + topic_slug)}",
            "$EvidenceHandle = $null",
            "try {",
            *desktop_dispatch_preflight,
            "  Assert-AwxLeaseSafePath -Path $EvidenceDir",
            "  [void][IO.Directory]::CreateDirectory($EvidenceDir)",
            "  try { $EvidenceHandle = Open-AwxCoordinationHandle -Path (Join-Path $EvidenceDir '.evidence-intake.lock') -TimeoutMs 5000 } catch { Write-Error '[AWX][desktop] evidence-intake-lock-busy'; exit 4 }",
            "  $IntakeRaw = " + safe_scalar(result.get("desktopIntakeCommand", ""), 2000),
            "  $IntakeExit = $LASTEXITCODE",
            "  if ($IntakeExit -ne 0) { Write-Error \"[AWX][desktop] external-evidence-intake-failed exitCode=$IntakeExit\"; exit $IntakeExit }",
            "  try { $IntakeJson = $IntakeRaw | ConvertFrom-Json } catch { Write-Error \"[AWX][desktop] external-evidence-intake-invalid-json\"; exit 1 }",
            "  Write-Host \"[AWX][desktop][intake] externalEvidenceComplete=$($IntakeJson.externalEvidenceComplete) producerBundleTopic=$($IntakeJson.producerBundleTopic) producerBundles=$(@($IntakeJson.producerBundles).Count) unrelatedPatchDropEvidence.total=$($IntakeJson.unrelatedPatchDropEvidence.total) evidenceNeeded=$(@($IntakeJson.evidence_needed).Count)\"",
            "  if (($IntakeJson.ok -ne $true) -or ($IntakeJson.externalEvidenceComplete -ne $true)) { Write-Error \"[AWX][desktop] external-evidence-intake-incomplete\"; exit 1 }",
            "  $AuditRaw = " + safe_scalar(result.get("desktopAuditCommand", ""), 2000),
            "  $AuditExit = $LASTEXITCODE",
            "  if ($AuditExit -ne 0) { Write-Error \"[AWX][desktop] external-evidence-audit-failed exitCode=$AuditExit\"; exit $AuditExit }",
            "  try { $AuditJson = $AuditRaw | ConvertFrom-Json } catch { Write-Error \"[AWX][desktop] external-evidence-audit-invalid-json\"; exit 1 }",
            "  if (($AuditJson.ok -ne $true) -or ($AuditJson.externalEvidenceComplete -ne $true)) { Write-Error \"[AWX][desktop] external-evidence-audit-incomplete\"; exit 1 }",
            f"  python .\\scripts\\awx_mcp_completion_audit.py --root . --evidence-dir $EvidenceDir --topic {ps_quote(topic_slug)}",
            "  $CompletionAuditExit = $LASTEXITCODE",
            "  if ($CompletionAuditExit -ne 0) { Write-Error \"[AWX][desktop] completion-audit-failed exitCode=$CompletionAuditExit\"; exit $CompletionAuditExit }",
            "} finally {",
            "  if ($EvidenceHandle) { $EvidenceHandle.Dispose() }",
            "}",
            "",
        ]
    )
    desktop_path.write_text(desktop_text, encoding="utf-8")
    artifact_paths.append(desktop_path)
    dispatch_next_actions.append({
        "action": "run-desktop-intake-after-producer-proof",
        "nodeRole": "desktop",
        "commandFile": str(desktop_path),
    })

    handoff_lines = [
        "# AWX PatchDrop Producer Handoff",
        "",
        f"Topic: `{topic_slug}`",
        "",
        "Desktop remains the canonical source owner and final verifier. Mac mini and Notebook must run only from producer-local worktrees or clones, then submit PatchDrop proof artifacts.",
        "",
        "## Producer Commands",
    ]
    for action in dispatch_next_actions:
        if safe_scalar(action.get("action", ""), 80) != "run-producer-command-file":
            continue
        role = safe_scalar(action.get("nodeRole", ""), 32)
        command_file_path = Path(safe_scalar(action.get("commandFile", ""), 500))
        command_text = command_file_path.read_text(encoding="utf-8", errors="ignore") if command_file_path.is_file() else ""
        command_hash = sha256_file(command_file_path) if command_file_path.is_file() else ""
        pathspecs = command_pathspecs_from_text(command_text)
        handoff_lines.extend([
            f"- {role}: `{safe_scalar(action.get('commandFile', ''), 500)}`",
            f"  - producer-visible command file: `{safe_scalar(action.get('producerCommandFile', ''), 500)}`",
            f"  - command SHA256: `{command_hash}`",
            f"  - producer kit manifest SHA256: `{safe_scalar(action.get('producerKitManifestHash', ''), 120) or 'evidence_needed'}`",
            f"  - producer source root: `{safe_scalar(action.get('sourceRoot', ''), 500)}`",
            f"  - producer PatchDrop root: `{safe_scalar(action.get('producerPatchdropRoot', ''), 500)}`",
            f"  - pathspec count: `{len(pathspecs)}`",
        ])
        for pathspec in pathspecs:
            handoff_lines.append(f"    - `{safe_scalar(pathspec, 240)}`")
    handoff_lines.extend([
        "",
        "## Required Producer Proof",
        f"- Dispatch integrity sidecar: `dispatch/{topic_slug}-dispatch.sha256.txt`",
        "- Each producer command file must match the dispatch sidecar before handoff; stale or rewritten command files fail as `dispatch-command-sha-mismatch`.",
        *(
            [f"- Source-health producer queue: `dispatch/{source_health_queue_path.name}`"]
            if source_health_queue_written
            else []
        ),
        "- `external-node-proof/macmini-node-smoke.json`",
        "- `external-node-proof/notebook-node-smoke.json`",
        "- `external-node-proof/macmini-producer-handoff.json`",
        "- `external-node-proof/notebook-producer-handoff.json`",
        f"- `macmini/{topic_slug}-macmini-v3.patch`",
        f"- `macmini/{topic_slug}-macmini-v3.report.md`",
        f"- `macmini/{topic_slug}-macmini-v3.verify.log`",
        f"- `macmini/{topic_slug}-macmini-v3.sha256.txt`",
        f"- `macmini/{topic_slug}-macmini-v3.manifest.json`",
        f"- `{topic_slug}.macmini-pending.md` pendingNotice",
        f"- `notebook/{topic_slug}-notebook-v3.patch`",
        f"- `notebook/{topic_slug}-notebook-v3.report.md`",
        f"- `notebook/{topic_slug}-notebook-v3.verify.log`",
        f"- `notebook/{topic_slug}-notebook-v3.sha256.txt`",
        f"- `notebook/{topic_slug}-notebook-v3.manifest.json`",
        f"- `{topic_slug}.notebook-pending.md` pendingNotice",
        "",
        "## Desktop Intake",
        f"- Run `{topic_slug}-desktop-intake.ps1` from the Desktop canonical root only after both producer proof sets arrive.",
        "- Desktop final proof remains `evidence_needed` until intake, external evidence audit, and completion audit pass on Desktop.",
        "- `localReady=true` means Desktop-local readiness only: source scan, dispatch, producer kit, and local gates are prepared.",
        "- `completionReady` remains false until both producer proof sets pass Desktop intake and `externalEvidenceComplete=true`.",
        "- Unrelated PatchDrop evidence is supporting-only context and must not satisfy this topic; only manifest-pinned proof for this topic counts.",
        "",
        "## Desktop Apply Gate",
        f"- `{topic_slug}-desktop-intake.ps1` is proof-only: it audits external producer proof and does not promote or apply producer bundles.",
        f"- After proof passes, run `.\\__patch_drop__\\janitor_inventory.ps1`, then promote one accepted bundle at a time with `.\\__patch_drop__\\janitor_promote_producer_pending.ps1 -Topic {topic_slug} -Node macmini` or `.\\__patch_drop__\\janitor_promote_producer_pending.ps1 -Topic {topic_slug} -Node notebook`.",
        f"- Keep only one active top-level `{topic_slug}-v3.patch`; promoting a second role before the first bundle is applied, rejected, or moved must fail as `active-top-level-exists`.",
        f"- Apply the promoted bundle only under a Desktop source-edit lease with `.\\__patch_drop__\\janitor_apply_one.ps1 -PatchName {topic_slug}-v3.patch -SourceLeaseOwnerId <desktop-owner-id>`.",
        "",
    ])
    handoff_path.write_text("\n".join(handoff_lines), encoding="utf-8")
    artifact_paths.append(handoff_path)

    for action in dispatch_next_actions:
        command_file = safe_scalar(action.get("commandFile", ""), 500)
        if command_file:
            action["fileHash"] = sha256_file(Path(command_file))

    covered_artifact_paths = list(artifact_paths)
    sha_sidecar_path = dispatch_dir / f"{topic_slug}-dispatch.sha256.txt"
    artifact_paths_with_sidecar = [*covered_artifact_paths, sha_sidecar_path]
    artifact_strings = [str(path) for path in artifact_paths_with_sidecar]
    json_payload = dict(result)
    json_payload["schemaVersion"] = "awx.mcp.desktop_dispatch_packet.v1"
    json_payload["dispatchDir"] = str(dispatch_dir)
    json_payload["dispatchArtifacts"] = artifact_strings
    json_payload["artifactCount"] = len(artifact_paths_with_sidecar)
    json_payload["dispatchArtifactIndex"] = {
        "desktopDispatch": str(json_path),
        "desktopIntake": str(desktop_path),
        "handoffSummary": str(handoff_path),
        "dispatchSha256Sidecar": str(sha_sidecar_path),
        "sha256CoveredArtifacts": [str(path) for path in covered_artifact_paths],
        **(
            {"sourceHealthProducerQueue": str(source_health_queue_path)}
            if source_health_queue_written
            else {}
        ),
        "producerCommands": [
            {
                "nodeRole": safe_scalar(action.get("nodeRole", ""), 32),
                "commandFile": safe_scalar(action.get("commandFile", ""), 500),
                "producerCommandFile": safe_scalar(action.get("producerCommandFile", ""), 500),
                "fileHash": safe_scalar(action.get("fileHash", ""), 120),
                "producerKitManifestHash": safe_scalar(action.get("producerKitManifestHash", ""), 120),
            }
            for action in dispatch_next_actions
            if safe_scalar(action.get("action", ""), 80) == "run-producer-command-file"
        ],
    }
    json_payload["nextActions"] = dispatch_next_actions
    json_path.write_text(json.dumps(redact(json_payload), ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    sha_sidecar_lines = [
        f"{sha256_file(path)}  {path.name}"
        for path in covered_artifact_paths
    ]
    sha_sidecar_path.write_text("\n".join(sha_sidecar_lines) + "\n", encoding="utf-8")
    return {
        "ok": True,
        "dispatchDir": str(dispatch_dir),
        "dispatchArtifacts": artifact_strings,
        "artifactCount": len(artifact_paths_with_sidecar),
        "dispatchArtifactIndex": json_payload["dispatchArtifactIndex"],
        "nextActions": dispatch_next_actions,
        "evidence_needed": [],
        "failReason": "",
    }


def schema(payload: dict[str, Any]) -> dict[str, Any]:
    manifest = Path(payload.get("manifest_path") or "main/resources/mcp/awx-control-tower-tools.json")
    if manifest.exists():
        data = json.loads(manifest.read_text(encoding="utf-8"))
        return {
            "manifestSchemaVersion": data.get("schemaVersion", ""),
            "toolNames": [tool.get("name", "") for tool in data.get("tools", [])],
            "outputCount": len(data.get("tools", [])),
            "decision": "schema_loaded",
        }
    return {
        "manifestSchemaVersion": "",
        "toolNames": [],
        "evidence_needed": f"{manifest.as_posix()} / verify manifest path",
        "decision": "schema_missing",
    }


def agent_db_snapshot(payload: dict[str, Any]) -> dict[str, Any]:
    endpoint = safe_scalar(payload.get("endpoint", "snapshot"), 32).strip("/").lower()
    allowed_endpoints = {"snapshot", "memory", "ledger", "strategy"}
    if endpoint not in allowed_endpoints:
        raise ValueError("invalid_agent_db_context_endpoint")
    base_url = safe_scalar(
        payload.get("base_url")
        or os.environ.get("AWX_AGENT_DB_CONTEXT_BASE_URL")
        or deployment_public_base_url()
        or "http://localhost:8080",
        240,
    ).rstrip("/")
    timeout_sec = bounded_int(payload.get("timeout_sec"), 10, 1, 60)
    url = f"{base_url}/agent/db-context/{endpoint}"
    headers = {"Accept": "application/json"}
    admin_token = os.environ.get("AWX_ADMIN_TOKEN", "").strip()
    if admin_token:
        headers["X-Admin-Token"] = admin_token
    request = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            body = response.read(1_000_000).decode("utf-8", errors="replace")
            try:
                data: Any = json.loads(body)
            except json.JSONDecodeError:
                data = {"bodyHash": stable_hash(body), "bodyLength": len(body)}
            return {
                "ok": True,
                "endpoint": endpoint,
                "httpStatus": response.status,
                "tokenPresented": bool(admin_token),
                "snapshot": redact(data),
                "decision": "agent_db_snapshot_loaded",
            }
    except urllib.error.HTTPError as exc:
        body = exc.read(1_000_000).decode("utf-8", errors="replace")
        try:
            data: Any = json.loads(body) if body else {}
        except json.JSONDecodeError:
            data = {"bodyHash": stable_hash(body), "bodyLength": len(body)}
        snapshot = redact(data)
        return {
            "ok": False,
            "endpoint": endpoint,
            "httpStatus": exc.code,
            "tokenPresented": bool(admin_token),
            "snapshot": snapshot,
            "localFallback": local_agent_db_snapshot(payload, endpoint),
            "decision": "agent_db_snapshot_http_error",
            "failReason": f"http-{exc.code}",
        }
    except Exception as exc:
        return {
            "ok": False,
            "endpoint": endpoint,
            "tokenPresented": bool(admin_token),
            "localFallback": local_agent_db_snapshot(payload, endpoint),
            "decision": "agent_db_snapshot_unavailable_with_local_fallback",
            "failReason": exc.__class__.__name__,
            "message": safe_message(str(exc), 160),
        }


def trace_snapshot_probe(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    base_url = safe_scalar(
        payload.get("base_url")
        or payload.get("baseUrl")
        or os.environ.get("AWX_TRACE_SNAPSHOT_BASE_URL")
        or os.environ.get("AWX_AGENT_DB_CONTEXT_BASE_URL")
        or deployment_public_base_url()
        or "http://localhost:8080",
        240,
    ).rstrip("/")
    limit = bounded_int(payload.get("limit"), 5, 1, 100)
    timeout_sec = bounded_int(payload.get("timeout_sec"), 10, 1, 60)
    url = f"{base_url}/api/diagnostics/trace/snapshots?limit={limit}"
    headers = {"Accept": "application/json"}
    admin_token = os.environ.get("AWX_ADMIN_TOKEN", "").strip()
    if admin_token:
        headers["X-Admin-Token"] = admin_token
    request = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            body = response.read(1_000_000).decode("utf-8", errors="replace")
            try:
                data: Any = json.loads(body)
            except json.JSONDecodeError:
                data = {}
            snapshots = data.get("snapshots") if isinstance(data, dict) else []
            snapshot_count = len(snapshots) if isinstance(snapshots, list) else 0
            return {
                "ok": response.status == 200 and isinstance(data, dict) and "available" in data,
                "httpStatus": response.status,
                "tokenPresented": bool(admin_token),
                "available": bool(data.get("available")) if isinstance(data, dict) else False,
                "snapshotCount": snapshot_count,
                "bodyHash": stable_hash(body),
                "bodyLength": len(body),
                "decision": "trace_snapshot_loaded",
            }
    except urllib.error.HTTPError as exc:
        body = exc.read(1_000_000).decode("utf-8", errors="replace")
        return {
            "ok": False,
            "httpStatus": exc.code,
            "tokenPresented": bool(admin_token),
            "bodyHash": stable_hash(body),
            "bodyLength": len(body),
            "localFallback": local_trace_snapshot_fallback(root),
            "decision": "trace_snapshot_http_error_with_local_fallback",
            "failReason": f"http-{exc.code}",
        }
    except Exception as exc:
        return {
            "ok": False,
            "tokenPresented": bool(admin_token),
            "localFallback": local_trace_snapshot_fallback(root),
            "decision": "trace_snapshot_unavailable_with_local_fallback",
            "failReason": exc.__class__.__name__,
            "message": safe_message(str(exc), 160),
        }


def local_trace_snapshot_fallback(root: Path) -> dict[str, Any]:
    files = {
        "store": root / "main" / "java" / "com" / "example" / "lms" / "trace" / "TraceSnapshotStore.java",
        "diagnosticsController": root / "main" / "java" / "com" / "example" / "lms" / "api" / "TraceSnapshotsDiagnosticsController.java",
        "pageController": root / "main" / "java" / "com" / "example" / "lms" / "web" / "TraceSnapshotsPageController.java",
        "exporter": root / "main" / "java" / "com" / "example" / "lms" / "trace" / "TraceSnapshotExporter.java",
        "filter": root / "main" / "java" / "com" / "example" / "lms" / "trace" / "TraceSnapshotFilter.java",
    }
    texts: dict[str, str] = {}
    for key, path in files.items():
        if path_is_file(path) and path_size(path) <= 1_500_000:
            try:
                texts[key] = path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                texts[key] = ""
        else:
            texts[key] = ""
    endpoint_paths: list[str] = []
    controller_text = texts.get("diagnosticsController", "")
    if '"/api/diagnostics/trace"' in controller_text and '"/snapshots"' in controller_text:
        endpoint_paths.append("/api/diagnostics/trace/snapshots")
    if '"/snapshots/{id}"' in controller_text:
        endpoint_paths.append("/api/diagnostics/trace/snapshots/{id}")
    if '"/snapshots/{id}/html"' in controller_text:
        endpoint_paths.append("/api/diagnostics/trace/snapshots/{id}/html")
    source_hash_payload = {
        key: sha256_file(path)
        for key, path in files.items()
        if path_is_file(path)
    }
    return {
        "storePresent": path_is_file(files["store"]) and "class TraceSnapshotStore" in texts.get("store", ""),
        "diagnosticsControllerPresent": path_is_file(files["diagnosticsController"])
        and "class TraceSnapshotsDiagnosticsController" in texts.get("diagnosticsController", ""),
        "pageControllerPresent": path_is_file(files["pageController"]),
        "exporterPresent": path_is_file(files["exporter"]) and "class TraceSnapshotExporter" in texts.get("exporter", ""),
        "filterPresent": path_is_file(files["filter"]) and "class TraceSnapshotFilter" in texts.get("filter", ""),
        "endpointPaths": endpoint_paths,
        "sourceFileCount": len(source_hash_payload),
        "sourceHash": stable_hash(source_hash_payload)[:16],
        "rootHash": stable_hash(str(root))[:16],
        "rootLength": len(str(root)),
        "rawSecretPatternHits": sum(secret_hit_count(path.parent, max_files=20, skip_tests=True) for path in files.values()),
        "evidence_needed": [
            "Spring runtime /api/diagnostics/trace/snapshots response for live TraceStore export",
        ],
        "decision": "trace_snapshot_local_fallback",
    }


def supabase_context_probe(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    timeout_sec = bounded_int(payload.get("timeout_sec"), 5, 1, 30)
    mcp_url = safe_scalar(payload.get("mcp_url") or payload.get("mcpUrl") or "https://mcp.supabase.com/mcp", 240)
    skip_mcp_network_probe = bool(payload.get("skip_mcp_network_probe") or payload.get("skipMcpNetworkProbe"))
    cli = supabase_cli_summary(timeout_sec)
    mcp = (
        supabase_mcp_probe_skipped_summary(mcp_url)
        if skip_mcp_network_probe
        else supabase_mcp_summary(mcp_url, timeout_sec)
    )
    mcp_config = supabase_mcp_config_summary(root)
    project_scope = supabase_project_scope_summary(mcp_config)
    db_snapshot_plan = supabase_db_snapshot_plan(cli, mcp_config)
    auth_plan = supabase_auth_plan(cli, mcp, mcp_config, project_scope, db_snapshot_plan)
    env_present = sorted(name for name in SUPABASE_ENV_REFS if os.environ.get(name))
    resource_roots = [
        root / "main" / "resources",
        root / "app" / "src" / "main" / "resources",
    ]
    datasource_env_refs = sorted({
        ref
        for resource_root in resource_roots
        for ref in env_refs(resource_root, max_files=1000, skip_tests=True)
        if ref.startswith("LMS_DB_") or ref.startswith("SPRING_DATASOURCE_") or ref in {"DATABASE_URL", "POSTGRES_URL"}
    })
    evidence_needed: list[str] = []
    if not cli["present"]:
        evidence_needed.append("supabase CLI missing / verify with `supabase --version` after installing CLI")
    if not mcp_config["present"]:
        evidence_needed.append(".mcp.json missing Supabase MCP config / verify with `Test-Path .mcp.json`")
    elif mcp_config.get("hasSupabaseMcpUrl") and not mcp_config.get("readOnlyMode"):
        evidence_needed.append(".mcp.json Supabase MCP not in read_only mode / add `?read_only=true` unless write tools are explicitly approved")
    elif mcp_config.get("hasSupabaseMcpUrl") and not mcp_config.get("featuresScopedMode"):
        evidence_needed.append(".mcp.json Supabase MCP features not scoped / set `features=database,debugging,docs` for DB audits")
    if mcp_config.get("hasSupabaseMcpUrl") and not mcp_config.get("projectScopedMode"):
        evidence_needed.append("Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project")
    if mcp.get("probeSkipped"):
        evidence_needed.append(
            "Supabase MCP endpoint probe skipped / rerun without skip_mcp_network_probe before claiming endpoint reachability"
        )
    elif not mcp["reachable"]:
        evidence_needed.append("Supabase MCP endpoint not reachable from this host / verify network and URL")
    elif mcp.get("decision") == "mcp_endpoint_auth_required" or mcp.get("unauthenticatedExpected"):
        evidence_needed.append("Supabase MCP endpoint auth required / complete OAuth MCP client flow before SQL/project probes")
    elif mcp.get("httpStatus") == 401 and not mcp_config["present"]:
        evidence_needed.append("Supabase MCP reachable but unauthenticated / configure OAuth MCP client before SQL/project probes")
    next_actions = supabase_context_probe_next_actions(project_scope, cli, mcp, auth_plan)
    return {
        "schemaVersion": "awx.mcp.supabase_context_probe.v1",
        "ok": True,
        "cli": cli,
        "mcp": mcp,
        "mcpConfig": mcp_config,
        "projectScope": project_scope,
        "authPlan": auth_plan,
        "dbSnapshotPlan": db_snapshot_plan,
        "envPresent": env_present,
        "sensitiveEnvPresent": [name for name in env_present if supabase_env_is_secret(name)],
        "datasourceEnvRefs": datasource_env_refs,
        "rootHash": stable_hash(str(root)),
        "rootLength": len(str(root)),
        "nextActions": next_actions,
        "evidence_needed": evidence_needed,
        "decision": "supabase_context_probe",
    }


def supabase_context_probe_next_actions(
    project_scope: dict[str, Any],
    cli: dict[str, Any],
    mcp: dict[str, Any],
    auth_plan: dict[str, Any] | None = None,
) -> list[str]:
    actions: list[str] = []
    project_action = safe_scalar(project_scope.get("nextAction", ""), 100)
    if project_action and project_action != "run_readonly_schema_snapshot":
        actions.append(project_action)
    if auth_plan and auth_plan.get("mcpOAuthRequired"):
        actions.append("complete_supabase_mcp_oauth_flow")
    if auth_plan and not cli.get("present"):
        actions.append("install_supabase_cli_or_use_mcp_execute_sql")
    if auth_plan:
        actions.append("run_supabase_cli_help_discovery")
    if project_scope.get("status") == "project_ref_missing":
        actions.append("link_supabase_cli_project_ref")
    if not cli.get("present") or not mcp.get("reachable"):
        actions.append("authenticate_supabase_mcp_or_cli")
    actions.extend([
        "run_supabase_context_probe",
        "run_supabase_schema_snapshot",
    ])
    deduped: list[str] = []
    for action in actions:
        if action and action not in deduped:
            deduped.append(action)
    return deduped


def supabase_schema_snapshot(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    context = supabase_context_probe(payload)
    plan = context.get("dbSnapshotPlan") if isinstance(context.get("dbSnapshotPlan"), dict) else {}
    default_rel = safe_scalar(plan.get("recommendedOutputPath", "data/db-gap-report/supabase-schema-snapshot.json"), 240)
    output_raw = payload.get("output_path") or payload.get("outputPath") or str(root / default_rel)
    output_path = resolve_path(output_raw)
    sql_bundle_raw = payload.get("sql_bundle_path") or payload.get("sqlBundlePath") or str(
        output_path.with_name("supabase-readonly-snapshot.sql")
    )
    sql_bundle_path = resolve_path(sql_bundle_raw)
    result_template_raw = payload.get("result_template_path") or payload.get("resultTemplatePath") or str(
        output_path.with_name("supabase-query-results.template.json")
    )
    result_template_path = resolve_path(result_template_raw)
    collection_packet_raw = payload.get("collection_packet_path") or payload.get("collectionPacketPath") or str(
        output_path.with_name("supabase-execute-sql-collection.packet.json")
    )
    collection_packet_path = resolve_path(collection_packet_raw)
    evidence_needed = list(context.get("evidence_needed", [])) if isinstance(context.get("evidence_needed"), list) else []
    evidence_needed.append(
        "authenticated Supabase CLI or MCP execute_sql/get_advisors required to populate live schema snapshot"
    )
    evidence_needed.append(
        "Supabase MCP get_advisors output required to populate advisor audit summary"
    )
    next_actions = supabase_schema_snapshot_next_actions(context)
    advisor_collection_plan = supabase_advisor_collection_plan()
    generated_at = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
    artifact = redact({
        "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
        "generatedAt": generated_at,
        "readOnly": True,
        "mutationAllowed": False,
        "schemaSnapshotAvailable": False,
        "recommendedOutputPath": default_rel,
        "cli": context.get("cli", {}),
        "mcp": context.get("mcp", {}),
        "mcpConfig": context.get("mcpConfig", {}),
        "projectScope": context.get("projectScope", {}),
        "authPlan": context.get("authPlan", {}),
        "dbSnapshotPlan": plan,
        "sqlBundlePath": redacted_path_text(sql_bundle_path),
        "sqlBundlePathHash": stable_hash(str(sql_bundle_path)),
        "sqlBundlePathLength": len(str(sql_bundle_path)),
        "resultTemplatePath": redacted_path_text(result_template_path),
        "resultTemplatePathHash": stable_hash(str(result_template_path)),
        "resultTemplatePathLength": len(str(result_template_path)),
        "collectionPacketPath": redacted_path_text(collection_packet_path),
        "collectionPacketPathHash": stable_hash(str(collection_packet_path)),
        "collectionPacketPathLength": len(str(collection_packet_path)),
        "snapshots": [],
        "advisors": {
            "available": False,
            "rows": [],
            "collectionPlan": advisor_collection_plan,
        },
        "nextActions": next_actions,
        "evidence_needed": sorted({item for item in evidence_needed if item}),
        "decision": "supabase_schema_snapshot_evidence_needed",
    })
    output_path.parent.mkdir(parents=True, exist_ok=True)
    artifact_text = json.dumps(artifact, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
    output_path.write_text(artifact_text, encoding="utf-8")
    sql_bundle_path.parent.mkdir(parents=True, exist_ok=True)
    sql_bundle_text = render_supabase_readonly_sql_bundle(plan)
    sql_bundle_path.write_text(sql_bundle_text, encoding="utf-8")
    result_template_path.parent.mkdir(parents=True, exist_ok=True)
    result_template_text = render_supabase_query_results_template(plan)
    result_template_path.write_text(result_template_text, encoding="utf-8")
    collection_packet_path.parent.mkdir(parents=True, exist_ok=True)
    collection_packet_text = render_supabase_execute_sql_collection_packet(plan)
    collection_packet_path.write_text(collection_packet_text, encoding="utf-8")
    raw_secret_hits = sum(len(pattern.findall(artifact_text)) for pattern in HIGH_CONF_SECRET_PATTERNS)
    sql_secret_hits = sum(len(pattern.findall(sql_bundle_text)) for pattern in HIGH_CONF_SECRET_PATTERNS)
    result_template_secret_hits = sum(
        len(pattern.findall(result_template_text))
        for pattern in HIGH_CONF_SECRET_PATTERNS
    )
    collection_packet_secret_hits = sum(
        len(pattern.findall(collection_packet_text))
        for pattern in HIGH_CONF_SECRET_PATTERNS
    )
    docs_refs = plan.get("docsRefs") if isinstance(plan.get("docsRefs"), list) else []
    security_contracts = plan.get("securityContracts") if isinstance(plan.get("securityContracts"), list) else []
    cli_version_requirements = (
        plan.get("cliVersionRequirements")
        if isinstance(plan.get("cliVersionRequirements"), dict)
        else {}
    )
    cli_fallback_contract = (
        plan.get("cliFallbackContract")
        if isinstance(plan.get("cliFallbackContract"), dict)
        else {}
    )
    api_keys_docs = any(
        isinstance(entry, dict)
        and "supabase.com/docs/guides/getting-started/api-keys" in safe_scalar(entry.get("url", ""), 300)
        for entry in docs_refs
    )
    secret_keys_backend_only = "secret_keys_backend_only" in {safe_scalar(item, 120) for item in security_contracts}
    return {
        "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
        "generatedAt": generated_at,
        "ok": True,
        "readOnly": True,
        "mutationAllowed": False,
        "schemaSnapshotAvailable": False,
        "snapshotPath": redacted_path_text(output_path),
        "snapshotPathHash": stable_hash(str(output_path)),
        "snapshotPathLength": len(str(output_path)),
        "snapshotHash": sha256_file(output_path),
        "snapshotBytes": path_size(output_path),
        "rawSecretPatternHits": raw_secret_hits,
        "docsRefs": docs_refs,
        "securityContracts": security_contracts,
        "cliVersionRequirements": cli_version_requirements,
        "cliFallbackContract": cli_fallback_contract,
        "apiKeysDocs": api_keys_docs,
        "secretKeysBackendOnly": secret_keys_backend_only,
        "sqlBundlePath": redacted_path_text(sql_bundle_path),
        "sqlBundlePathHash": stable_hash(str(sql_bundle_path)),
        "sqlBundleHash": sha256_file(sql_bundle_path),
        "sqlBundleBytes": path_size(sql_bundle_path),
        "sqlBundleSecretPatternHits": sql_secret_hits,
        "resultTemplatePath": redacted_path_text(result_template_path),
        "resultTemplatePathHash": stable_hash(str(result_template_path)),
        "resultTemplateHash": sha256_file(result_template_path),
        "resultTemplateBytes": path_size(result_template_path),
        "resultTemplateSecretPatternHits": result_template_secret_hits,
        "collectionPacketPath": redacted_path_text(collection_packet_path),
        "collectionPacketPathHash": stable_hash(str(collection_packet_path)),
        "collectionPacketHash": sha256_file(collection_packet_path),
        "collectionPacketBytes": path_size(collection_packet_path),
        "collectionPacketSecretPatternHits": collection_packet_secret_hits,
        "projectScope": artifact.get("projectScope", {}),
        "authPlan": artifact.get("authPlan", {}),
        "nextActions": artifact.get("nextActions", []),
        "evidence_needed": artifact["evidence_needed"],
        "decision": "supabase_schema_snapshot_evidence_needed",
    }


def supabase_schema_snapshot_next_actions(context: dict[str, Any]) -> list[str]:
    actions: list[str] = []
    project_scope = context.get("projectScope") if isinstance(context.get("projectScope"), dict) else {}
    auth_plan = context.get("authPlan") if isinstance(context.get("authPlan"), dict) else {}
    project_action = safe_scalar(project_scope.get("nextAction", ""), 80)
    if project_action and project_action != "run_readonly_schema_snapshot":
        actions.append(project_action)
    if auth_plan.get("mcpOAuthRequired"):
        actions.append("complete_supabase_mcp_oauth_flow")
    if auth_plan.get("cliPresent") is False:
        actions.append("install_supabase_cli_or_use_mcp_execute_sql")
    if auth_plan:
        actions.append("run_supabase_cli_help_discovery")
    if project_scope.get("status") == "project_ref_missing":
        actions.append("link_supabase_cli_project_ref")
    actions.extend([
        "authenticate_supabase_mcp_or_cli",
        "run_supabase_readonly_sql_bundle",
        "run_supabase_get_advisors_readonly",
        "import_supabase_query_results",
        "populate_supabase_query_results_file",
        "rerun_supabase_schema_snapshot_import",
        "rerun_db_gap_scanner",
    ])
    deduped: list[str] = []
    for action in actions:
        if action and action not in deduped:
            deduped.append(action)
    return deduped


def supabase_schema_snapshot_import_next_actions(
    artifact: dict[str, Any],
    *,
    schema_snapshot_available: bool,
    evidence_needed: list[str],
) -> list[str]:
    existing = artifact.get("nextActions") if isinstance(artifact.get("nextActions"), list) else []
    actions = [
        safe_scalar(action, 100)
        for action in existing
        if safe_scalar(action, 100) and safe_scalar(action, 100) != "rerun_db_gap_scanner"
    ]
    if not actions:
        actions.extend(supabase_schema_snapshot_next_actions(artifact))
    if evidence_needed or not schema_snapshot_available:
        actions.extend([
            "populate_supabase_query_results_file",
            "rerun_supabase_schema_snapshot_import",
        ])
    actions.append("rerun_db_gap_scanner")
    deduped: list[str] = []
    for action in actions:
        if action and action not in deduped:
            deduped.append(action)
    return deduped


def supabase_advisor_collection_plan() -> dict[str, Any]:
    return {
        "readOnly": True,
        "mutationAllowed": False,
        "mcpTool": "get_advisors",
        "featureGroup": "debugging",
        "mcpEndpointTemplate": (
            "https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}"
            "&read_only=true&features=database,debugging,docs"
        ),
        "projectRefEnv": "SUPABASE_PROJECT_REF",
        "authEnv": "SUPABASE_ACCESS_TOKEN",
        "resultPathRecommendation": "data/db-gap-report/supabase-advisors.json",
        "importTarget": "data/db-gap-report/supabase-schema-snapshot.json#advisors.rows",
        "usage": (
            "Call Supabase MCP get_advisors in read-only debugging scope and copy the returned "
            "advisor rows into the snapshot advisors.rows array before rerunning db_gap_scanner."
        ),
        "supportedResultShapes": [
            "advisors.rows[]",
            "rows[]",
            "data.rows[]",
            "JSON-RPC structuredContent advisors or rows",
            "MCP content text JSON with advisors or rows",
        ],
    }


def render_supabase_readonly_sql_bundle(plan: dict[str, Any]) -> str:
    queries = plan.get("sqlQueries") if isinstance(plan.get("sqlQueries"), list) else []
    lines = [
        "-- AWX Supabase read-only schema/security snapshot bundle",
        "-- Generated from dbSnapshotPlan. Review before running against the selected project.",
        "-- This bundle is diagnostic only; do not paste mutation SQL into it.",
    ]
    docs_refs = plan.get("docsRefs") if isinstance(plan.get("docsRefs"), list) else []
    for entry in docs_refs[:8]:
        if not isinstance(entry, dict):
            continue
        ref_id = safe_sql_comment(entry.get("id", ""), 80)
        url = safe_sql_comment(entry.get("url", ""), 220)
        if ref_id and url:
            lines.append(f"-- Docs: {ref_id} {url}")
    breaking_changes = plan.get("breakingChanges") if isinstance(plan.get("breakingChanges"), list) else []
    for entry in breaking_changes[:8]:
        if not isinstance(entry, dict):
            continue
        change_id = safe_sql_comment(entry.get("id", ""), 80)
        effective = safe_sql_comment(entry.get("effectiveDate", ""), 40)
        impact = safe_sql_comment(entry.get("impact", ""), 180)
        if change_id:
            suffix = f" effective={effective}" if effective else ""
            impact_suffix = f" impact={impact}" if impact else ""
            lines.append(f"-- Breaking change: {change_id}{suffix}{impact_suffix}")
    security_contracts = plan.get("securityContracts") if isinstance(plan.get("securityContracts"), list) else []
    for contract in security_contracts[:12]:
        safe_contract = safe_sql_comment(contract, 120)
        if safe_contract:
            lines.append(f"-- Security contract: {safe_contract}")
    lines.extend([
        "START TRANSACTION READ ONLY;",
        "",
    ])
    for query in queries:
        if not isinstance(query, dict):
            continue
        name = safe_scalar(query.get("name", "unnamed_query"), 120)
        sql = safe_scalar(query.get("sql", ""), 20000)
        if not sql:
            continue
        lines.append(f"-- {name}")
        lines.append(sql.rstrip().rstrip(";") + ";")
        lines.append("")
    lines.extend([
        "COMMIT;",
        "",
    ])
    return "\n".join(lines)


def render_supabase_query_results_template(plan: dict[str, Any]) -> str:
    queries = plan.get("sqlQueries") if isinstance(plan.get("sqlQueries"), list) else []
    template_queries = []
    for query in queries:
        if not isinstance(query, dict):
            continue
        name = safe_scalar(query.get("name", ""), 120)
        sql = safe_scalar(query.get("sql", ""), 20000)
        if not name:
            continue
        template_queries.append({
            "name": name,
            "sqlHash": stable_hash(sql),
            "sourceSqlBundleMarker": f"-- {name}",
            "collectAs": {
                "name": name,
                "rows": [],
            },
            "rowsPlaceholder": "replace with execute_sql rows array for this query",
        })
    return json.dumps(
        {
            "schemaVersion": "awx.mcp.supabase_query_results_template.v1",
            "readOnly": True,
            "mutationAllowed": False,
            "importTool": "supabase_schema_snapshot_import",
            "collectionPlan": {
                "readOnly": True,
                "mutationAllowed": False,
                "sourceSqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                "resultFileRecommendation": "data/db-gap-report/supabase-query-results.json",
                "mcpTool": "execute_sql",
                "mcpEndpointTemplate": (
                    "https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}"
                    "&read_only=true&features=database,debugging,docs"
                ),
                "projectRefEnv": "SUPABASE_PROJECT_REF",
                "authEnv": "SUPABASE_ACCESS_TOKEN",
                "importTool": "supabase_schema_snapshot_import",
                "importCommandTemplate": (
                    "$payload = @{root='.'; "
                    "snapshot_path='data/db-gap-report/supabase-schema-snapshot.json'; "
                    "results_path='data/db-gap-report/supabase-query-results.json'} | ConvertTo-Json -Compress; "
                    "$payload | python scripts\\awx_mcp_toolbox.py supabase_schema_snapshot_import"
                ),
                "supportedResultShapes": [
                    "results[].name plus rows[]",
                    "results[] wrapper entries with nested result or structuredContent",
                    "top-level array of {name, rows}",
                    "JSONL lines with name plus rows",
                    "named empty rows[] result sets",
                    "named MCP execute_sql transcript entries",
                    "named execute_sql_result wrapper entries",
                    "records[] or data.rows[] wrapper entries",
                    "JSON-RPC structuredContent or MCP content text JSON",
                    "redacted MCP error transcripts",
                ],
            },
            "advisorCollectionPlan": supabase_advisor_collection_plan(),
            "usage": (
                "Run each query from supabase-readonly-snapshot.sql with Supabase MCP execute_sql or CLI, "
                "copy result rows into a separate results JSON file, then import that file."
            ),
            "expectedResultsShape": {
                "results": [
                    {
                        "name": "<query name from queries[]>",
                        "rows": ["<execute_sql rows array>"],
                    }
                ]
            },
            "queries": template_queries,
        },
        ensure_ascii=True,
        indent=2,
        sort_keys=True,
    ) + "\n"


def render_supabase_execute_sql_collection_packet(plan: dict[str, Any]) -> str:
    queries = plan.get("sqlQueries") if isinstance(plan.get("sqlQueries"), list) else []
    packet_queries = []
    for query in queries:
        if not isinstance(query, dict):
            continue
        name = safe_scalar(query.get("name", ""), 120)
        sql = safe_scalar(query.get("sql", ""), 20000).rstrip().rstrip(";")
        if not name or not sql:
            continue
        packet_queries.append({
            "name": name,
            "sqlHash": stable_hash(sql),
            "sourceSqlBundleMarker": f"-- {name}",
            "sql": sql,
            "executeSqlInput": {
                "query": sql,
            },
            "collectAs": {
                "name": name,
                "rows": [],
            },
        })
    return json.dumps(
        {
            "schemaVersion": "awx.mcp.supabase_execute_sql_collection_packet.v1",
            "readOnly": True,
            "mutationAllowed": False,
            "mcpTool": "execute_sql",
            "mcpEndpointTemplate": (
                "https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}"
                "&read_only=true&features=database,debugging,docs"
            ),
            "projectRefEnv": "SUPABASE_PROJECT_REF",
            "authEnv": "SUPABASE_ACCESS_TOKEN",
            "executionMode": "one_query_per_execute_sql_call",
            "executeSqlInputField": "query",
            "sourceSqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
            "resultPathRecommendation": "data/db-gap-report/supabase-query-results.json",
            "importTool": "supabase_schema_snapshot_import",
            "queryCount": len(packet_queries),
            "nextActions": [
                "set_SUPABASE_PROJECT_REF",
                "authenticate_supabase_mcp_or_cli",
                "execute_each_query_once",
                "collect_get_advisors_rows",
                "run_supabase_schema_snapshot_import",
                "rerun_db_gap_scanner",
            ],
            "importCommandTemplate": (
                "$payload = @{root='.'; "
                "snapshot_path='data/db-gap-report/supabase-schema-snapshot.json'; "
                "results_path='data/db-gap-report/supabase-query-results.json'} | ConvertTo-Json -Compress; "
                "$payload | python scripts\\awx_mcp_toolbox.py supabase_schema_snapshot_import"
            ),
            "advisorCollection": supabase_advisor_collection_plan(),
            "usage": (
                "For each entry, call Supabase MCP execute_sql with executeSqlInput. "
                "Copy returned rows into resultPathRecommendation as {name, rows} entries."
            ),
            "queries": packet_queries,
        },
        ensure_ascii=True,
        indent=2,
        sort_keys=True,
    ) + "\n"


def safe_sql_comment(value: object, limit: int = 180) -> str:
    text = safe_scalar(value, limit).replace("\r", " ").replace("\n", " ").strip()
    return text.replace("*/", "* /").replace("--", "- -")


def supabase_schema_snapshot_import(payload: dict[str, Any]) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    snapshot_raw = payload.get("snapshot_path") or payload.get("snapshotPath") or (
        root / "data" / "db-gap-report" / "supabase-schema-snapshot.json"
    )
    results_raw = payload.get("results_path") or payload.get("resultsPath") or ""
    advisors_raw = payload.get("advisors_path") or payload.get("advisorsPath") or ""
    snapshot_path = resolve_path(snapshot_raw)
    artifact = load_json_file(snapshot_path)
    if not results_raw:
        artifact_result_template = artifact.get("resultTemplatePath")
        if (
            isinstance(artifact_result_template, str)
            and artifact_result_template.strip()
            and not is_redacted_path_text(artifact_result_template)
        ):
            results_raw = artifact_result_template
        else:
            results_raw = str(snapshot_path.with_name("supabase-query-results.template.json"))
    results_path = resolve_path(results_raw)
    existing_evidence = artifact.get("evidence_needed") if isinstance(artifact.get("evidence_needed"), list) else []
    expected_result_names = supabase_expected_result_names(artifact)
    if not results_raw or not results_path.is_file():
        existing_snapshots = artifact.get("snapshots") if isinstance(artifact.get("snapshots"), list) else []
        missing_result_names = supabase_missing_result_names(expected_result_names, existing_snapshots)
        unexpected_result_names = supabase_unexpected_result_names(expected_result_names, existing_snapshots)
        duplicate_result_names = supabase_duplicate_result_names(existing_snapshots)
        evidence_needed = sorted({
            *(str(item) for item in existing_evidence if item),
            f"results_path missing / verify with {redacted_path_text(results_path)}",
            *supabase_missing_result_evidence(missing_result_names),
            *supabase_unexpected_result_evidence(unexpected_result_names),
            *supabase_duplicate_result_evidence(duplicate_result_names),
        })
        schema_snapshot_available = bool(existing_snapshots) or artifact.get("schemaSnapshotAvailable") is True
        advisor_import = supabase_import_advisors_into_artifact(artifact, advisors_raw)
        evidence_needed = sorted({*evidence_needed, *advisor_import["evidence_needed"]})
        next_actions = supabase_schema_snapshot_import_next_actions(
            artifact,
            schema_snapshot_available=schema_snapshot_available,
            evidence_needed=evidence_needed,
        )
        artifact.update({
            "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
            "readOnly": True,
            "mutationAllowed": False,
            "schemaSnapshotAvailable": schema_snapshot_available,
            "schemaSnapshotComplete": False,
            "snapshotImport": {
                "status": "evidence_needed",
                "resultsPathHash": stable_hash(str(results_path)),
                "resultsPathLength": len(str(results_path)),
                "importedResultCount": 0,
                "resultSetComplete": False,
                "storedRawRows": False,
                "expectedResultCount": len(expected_result_names),
                "missingResultCount": len(missing_result_names),
                "missingResultNames": missing_result_names,
                "unexpectedResultCount": len(unexpected_result_names),
                "unexpectedResultNames": unexpected_result_names,
                "duplicateResultCount": len(duplicate_result_names),
                "duplicateResultNames": duplicate_result_names,
            },
            "nextActions": next_actions,
            "evidence_needed": evidence_needed,
        })
        snapshot_path.parent.mkdir(parents=True, exist_ok=True)
        snapshot_path.write_text(json.dumps(redact(artifact), ensure_ascii=True, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return {
            "schemaVersion": "awx.mcp.supabase_schema_snapshot_import.v1",
            "ok": True,
            "readOnly": True,
            "mutationAllowed": False,
            "schemaSnapshotAvailable": schema_snapshot_available,
            "schemaSnapshotComplete": False,
            "snapshotPath": redacted_path_text(snapshot_path),
            "snapshotHash": sha256_file(snapshot_path),
            "snapshotBytes": path_size(snapshot_path),
            "resultsPathHash": stable_hash(str(results_path)),
            "resultsPathLength": len(str(results_path)),
            "importedResultCount": 0,
            "resultSetComplete": False,
            "storedRawRows": False,
            "expectedResultCount": len(expected_result_names),
            "missingResultCount": len(missing_result_names),
            "missingResultNames": missing_result_names,
            "unexpectedResultCount": len(unexpected_result_names),
            "unexpectedResultNames": unexpected_result_names,
            "duplicateResultCount": len(duplicate_result_names),
            "duplicateResultNames": duplicate_result_names,
            **supabase_advisor_import_result_fields(advisor_import),
            "riskSummary": artifact.get("riskSummary") if isinstance(artifact.get("riskSummary"), dict) else {},
            "rawSecretPatternHits": 0,
            "nextActions": next_actions,
            "evidence_needed": evidence_needed,
            "decision": "supabase_schema_snapshot_import_evidence_needed",
        }
    results_payload = load_json_payload(results_path)
    if is_supabase_query_results_template(results_payload):
        input_shape = supabase_payload_shape(results_payload, [], [])
        existing_snapshots = artifact.get("snapshots") if isinstance(artifact.get("snapshots"), list) else []
        missing_result_names = supabase_missing_result_names(expected_result_names, existing_snapshots)
        unexpected_result_names = supabase_unexpected_result_names(expected_result_names, existing_snapshots)
        duplicate_result_names = supabase_duplicate_result_names(existing_snapshots)
        evidence_needed = sorted({
            *(str(item) for item in existing_evidence if item),
            "results_path is an unfilled result template / copy execute_sql rows into a separate results JSON file",
            *supabase_missing_result_evidence(missing_result_names),
            *supabase_unexpected_result_evidence(unexpected_result_names),
            *supabase_duplicate_result_evidence(duplicate_result_names),
        })
        schema_snapshot_available = bool(existing_snapshots) or artifact.get("schemaSnapshotAvailable") is True
        advisor_import = supabase_import_advisors_into_artifact(artifact, advisors_raw)
        evidence_needed = sorted({*evidence_needed, *advisor_import["evidence_needed"]})
        next_actions = supabase_schema_snapshot_import_next_actions(
            artifact,
            schema_snapshot_available=schema_snapshot_available,
            evidence_needed=evidence_needed,
        )
        artifact.update({
            "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
            "readOnly": True,
            "mutationAllowed": False,
            "schemaSnapshotAvailable": schema_snapshot_available,
            "schemaSnapshotComplete": False,
            "snapshotImport": {
                "status": "evidence_needed",
                "resultsPathHash": stable_hash(str(results_path)),
                "resultsPathLength": len(str(results_path)),
                "importedResultCount": 0,
                "resultSetComplete": False,
                "storedRawRows": False,
                "inputShape": input_shape,
                "expectedResultCount": len(expected_result_names),
                "missingResultCount": len(missing_result_names),
                "missingResultNames": missing_result_names,
                "unexpectedResultCount": len(unexpected_result_names),
                "unexpectedResultNames": unexpected_result_names,
                "duplicateResultCount": len(duplicate_result_names),
                "duplicateResultNames": duplicate_result_names,
            },
            "nextActions": next_actions,
            "evidence_needed": evidence_needed,
        })
        snapshot_path.parent.mkdir(parents=True, exist_ok=True)
        snapshot_path.write_text(json.dumps(redact(artifact), ensure_ascii=True, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return {
            "schemaVersion": "awx.mcp.supabase_schema_snapshot_import.v1",
            "ok": True,
            "readOnly": True,
            "mutationAllowed": False,
            "schemaSnapshotAvailable": schema_snapshot_available,
            "schemaSnapshotComplete": False,
            "snapshotPath": redacted_path_text(snapshot_path),
            "snapshotHash": sha256_file(snapshot_path),
            "snapshotBytes": path_size(snapshot_path),
            "resultsPathHash": stable_hash(str(results_path)),
            "resultsPathLength": len(str(results_path)),
            "importedResultCount": 0,
            "resultSetComplete": False,
            "storedRawRows": False,
            "riskSummary": artifact.get("riskSummary") if isinstance(artifact.get("riskSummary"), dict) else {},
            "inputShape": input_shape,
            "expectedResultCount": len(expected_result_names),
            "missingResultCount": len(missing_result_names),
            "missingResultNames": missing_result_names,
            "unexpectedResultCount": len(unexpected_result_names),
            "unexpectedResultNames": unexpected_result_names,
            "duplicateResultCount": len(duplicate_result_names),
            "duplicateResultNames": duplicate_result_names,
            **supabase_advisor_import_result_fields(advisor_import),
            "rawSecretPatternHits": 0,
            "nextActions": next_actions,
            "evidence_needed": evidence_needed,
            "decision": "supabase_schema_snapshot_import_evidence_needed",
        }
    normalized = normalize_supabase_query_results(results_payload)
    imported_count = len(normalized["snapshots"])
    input_shape = normalized["inputShape"]
    mcp_errors = normalized["mcpErrors"] if isinstance(normalized.get("mcpErrors"), list) else []
    mcp_error_codes = supabase_mcp_error_codes(mcp_errors)
    missing_result_names = supabase_missing_result_names(expected_result_names, normalized["snapshots"])
    unexpected_result_names = supabase_unexpected_result_names(expected_result_names, normalized["snapshots"])
    duplicate_result_names = supabase_duplicate_result_names(normalized["snapshots"])
    import_evidence = sorted(str(item) for item in existing_evidence if item)
    if imported_count == 0:
        import_evidence = sorted({
            *import_evidence,
            "results_path contained no supported Supabase query result rows / export named results with rows or data",
        })
    if mcp_errors:
        import_evidence = sorted({
            *import_evidence,
            *supabase_mcp_error_evidence(mcp_errors),
        })
    if missing_result_names:
        import_evidence = sorted({
            *import_evidence,
            *supabase_missing_result_evidence(missing_result_names),
        })
    if unexpected_result_names:
        import_evidence = sorted({
            *import_evidence,
            *supabase_unexpected_result_evidence(unexpected_result_names),
        })
    if duplicate_result_names:
        import_evidence = sorted({
            *import_evidence,
            *supabase_duplicate_result_evidence(duplicate_result_names),
        })
    advisor_import = supabase_import_advisors_into_artifact(artifact, advisors_raw)
    import_evidence = sorted({*import_evidence, *advisor_import["evidence_needed"]})
    schema_snapshot_available = bool(imported_count)
    result_set_complete = (
        bool(imported_count)
        and not missing_result_names
        and not unexpected_result_names
        and not duplicate_result_names
        and not mcp_errors
    )
    snapshot_import_status = (
        "imported"
        if result_set_complete
        else "partial_evidence_needed"
        if imported_count
        else "evidence_needed"
    )
    next_actions = supabase_schema_snapshot_import_next_actions(
        artifact,
        schema_snapshot_available=schema_snapshot_available,
        evidence_needed=import_evidence,
    )
    artifact.update({
        "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
        "readOnly": True,
        "mutationAllowed": False,
        "schemaSnapshotAvailable": schema_snapshot_available,
        "schemaSnapshotComplete": result_set_complete,
        "snapshots": normalized["snapshots"],
        "riskSummary": normalized["riskSummary"],
        "snapshotImport": {
            "status": snapshot_import_status,
            "resultsPathHash": stable_hash(str(results_path)),
            "resultsPathLength": len(str(results_path)),
            "importedResultCount": imported_count,
            "resultSetComplete": result_set_complete,
            "storedRawRows": False,
            "inputShape": input_shape,
            "expectedResultCount": len(expected_result_names),
            "missingResultCount": len(missing_result_names),
            "missingResultNames": missing_result_names,
            "unexpectedResultCount": len(unexpected_result_names),
            "unexpectedResultNames": unexpected_result_names,
            "duplicateResultCount": len(duplicate_result_names),
            "duplicateResultNames": duplicate_result_names,
            "mcpErrorCount": len(mcp_errors),
            "mcpErrorCodes": mcp_error_codes,
        },
        "rawSecretPatternHits": int(artifact.get("rawSecretPatternHits", 0) or 0) + normalized["rawSecretPatternHits"],
        "nextActions": next_actions,
        "evidence_needed": import_evidence,
    })
    snapshot_path.parent.mkdir(parents=True, exist_ok=True)
    artifact_text = json.dumps(redact(artifact), ensure_ascii=True, indent=2, sort_keys=True) + "\n"
    snapshot_path.write_text(artifact_text, encoding="utf-8")
    return {
        "schemaVersion": "awx.mcp.supabase_schema_snapshot_import.v1",
        "ok": True,
        "readOnly": True,
        "mutationAllowed": False,
        "schemaSnapshotAvailable": bool(normalized["snapshots"]),
        "schemaSnapshotComplete": result_set_complete,
        "snapshotPath": redacted_path_text(snapshot_path),
        "snapshotHash": sha256_file(snapshot_path),
        "snapshotBytes": path_size(snapshot_path),
        "resultsPathHash": stable_hash(str(results_path)),
        "resultsPathLength": len(str(results_path)),
        "importedResultCount": imported_count,
        "resultSetComplete": result_set_complete,
        "storedRawRows": False,
        "riskSummary": normalized["riskSummary"],
        "inputShape": input_shape,
        "expectedResultCount": len(expected_result_names),
        "missingResultCount": len(missing_result_names),
        "missingResultNames": missing_result_names,
        "unexpectedResultCount": len(unexpected_result_names),
        "unexpectedResultNames": unexpected_result_names,
        "duplicateResultCount": len(duplicate_result_names),
        "duplicateResultNames": duplicate_result_names,
        "mcpErrorCount": len(mcp_errors),
        "mcpErrorCodes": mcp_error_codes,
        **supabase_advisor_import_result_fields(advisor_import),
        "rawSecretPatternHits": normalized["rawSecretPatternHits"],
        "nextActions": next_actions,
        "evidence_needed": import_evidence,
        "decision": "supabase_schema_snapshot_imported" if result_set_complete else "supabase_schema_snapshot_import_evidence_needed",
    }


def is_supabase_query_results_template(payload: Any) -> bool:
    return (
        isinstance(payload, dict)
        and payload.get("schemaVersion") == "awx.mcp.supabase_query_results_template.v1"
    )


def supabase_import_advisors_into_artifact(artifact: dict[str, Any], advisors_raw: Any) -> dict[str, Any]:
    result = {
        "advisorPathHash": "",
        "advisorPathLength": 0,
        "advisorRowCount": -1,
        "advisorErrorCount": 0,
        "advisorWarningCount": 0,
        "advisorRawSecretPatternHits": 0,
        "advisorMcpErrorCount": 0,
        "advisorMcpErrorCodes": [],
        "evidence_needed": [],
    }
    if not isinstance(advisors_raw, str) or not advisors_raw.strip():
        return result

    advisors_path = resolve_path(advisors_raw)
    result["advisorPathHash"] = stable_hash(str(advisors_path))
    result["advisorPathLength"] = len(str(advisors_path))
    if not advisors_path.is_file():
        result["evidence_needed"] = [f"advisors_path missing / verify with Test-Path {advisors_path}"]
        return result

    payload = load_json_payload(advisors_path)
    normalized = normalize_supabase_advisor_results(payload)
    mcp_errors = normalized["mcpErrors"] if isinstance(normalized.get("mcpErrors"), list) else []
    mcp_error_codes = supabase_mcp_error_codes(mcp_errors)
    existing_advisors = artifact.get("advisors") if isinstance(artifact.get("advisors"), dict) else {}
    collection_plan = (
        existing_advisors.get("collectionPlan")
        if isinstance(existing_advisors.get("collectionPlan"), dict)
        else supabase_advisor_collection_plan()
    )

    row_count = int(normalized.get("rowCount", 0) or 0)
    error_count = int(normalized.get("errorCount", 0) or 0)
    warning_count = int(normalized.get("warningCount", 0) or 0)
    advisor_evidence: list[str] = []
    if row_count == 0:
        advisor_evidence.append("advisors_path contained no supported Supabase advisor rows / export get_advisors rows")
    if mcp_errors:
        advisor_evidence.extend(supabase_mcp_error_evidence(mcp_errors))

    artifact["advisors"] = {
        "available": row_count > 0,
        "status": "imported" if row_count else "evidence_needed",
        "rowCount": row_count,
        "rows": normalized["rows"],
        "rowHashes": normalized["rowHashes"],
        "errorCount": error_count,
        "warningCount": warning_count,
        "categories": normalized["categories"],
        "collectionPlan": collection_plan,
        "import": {
            "status": "imported" if row_count else "evidence_needed",
            "advisorsPathHash": result["advisorPathHash"],
            "advisorsPathLength": result["advisorPathLength"],
            "storedRawRows": False,
            "mcpErrorCount": len(mcp_errors),
            "mcpErrorCodes": mcp_error_codes,
            "rawSecretPatternHits": normalized["rawSecretPatternHits"],
        },
    }
    result.update({
        "advisorRowCount": row_count,
        "advisorErrorCount": error_count,
        "advisorWarningCount": warning_count,
        "advisorRawSecretPatternHits": normalized["rawSecretPatternHits"],
        "advisorMcpErrorCount": len(mcp_errors),
        "advisorMcpErrorCodes": mcp_error_codes,
        "evidence_needed": advisor_evidence,
    })
    return result


def supabase_advisor_import_result_fields(advisor_import: dict[str, Any]) -> dict[str, Any]:
    return {
        "advisorPathHash": advisor_import.get("advisorPathHash", ""),
        "advisorPathLength": advisor_import.get("advisorPathLength", 0),
        "advisorRowCount": advisor_import.get("advisorRowCount", -1),
        "advisorErrorCount": advisor_import.get("advisorErrorCount", 0),
        "advisorWarningCount": advisor_import.get("advisorWarningCount", 0),
        "advisorRawSecretPatternHits": advisor_import.get("advisorRawSecretPatternHits", 0),
        "advisorMcpErrorCount": advisor_import.get("advisorMcpErrorCount", 0),
        "advisorMcpErrorCodes": advisor_import.get("advisorMcpErrorCodes", []),
    }


def normalize_supabase_advisor_results(payload: Any) -> dict[str, Any]:
    raw_text = json.dumps(payload, ensure_ascii=True, sort_keys=True)
    secret_hits = sum(len(pattern.findall(raw_text)) for pattern in HIGH_CONF_SECRET_PATTERNS)
    mcp_errors = supabase_mcp_error_summaries(payload)
    row_dicts = [
        row
        for row in supabase_advisor_rows(payload)
        if isinstance(row, dict)
    ]
    safe_rows = [supabase_safe_advisor_row(row) for row in row_dicts[:200]]
    row_hashes = [stable_hash(redact(row))[:16] for row in row_dicts[:200]]
    categories: set[str] = set()
    error_count = 0
    warning_count = 0
    for row in row_dicts:
        level = safe_scalar(row.get("level", row.get("severity", "")), 40).upper()
        if level in {"ERROR", "CRITICAL"}:
            error_count += 1
        if level in {"WARN", "WARNING"}:
            warning_count += 1
        category = safe_scalar(row.get("category", row.get("group", "")), 80).upper()
        if category:
            categories.add(category)
    return {
        "rowCount": len(row_dicts),
        "rows": safe_rows,
        "rowHashes": row_hashes,
        "errorCount": error_count,
        "warningCount": warning_count,
        "categories": sorted(categories)[:12],
        "mcpErrors": mcp_errors,
        "rawSecretPatternHits": secret_hits,
    }


def supabase_advisor_rows(payload: Any) -> list[Any]:
    rows: list[Any] = []
    seen: set[str] = set()

    def add_row(row: Any) -> None:
        if not isinstance(row, dict):
            return
        key = stable_hash(redact(row))[:16]
        if key in seen:
            return
        seen.add(key)
        rows.append(row)

    def add_rows(value: Any) -> None:
        if isinstance(value, list):
            for row in value[:500]:
                add_row(row)

    def walk(node: Any) -> None:
        if isinstance(node, list):
            add_rows(node)
            for item in node[:100]:
                if isinstance(item, dict):
                    walk(item)
            return
        if not isinstance(node, dict) or supabase_is_mcp_error_item(node):
            return
        for key in ("advisors", "rows", "data", "records"):
            value = node.get(key)
            if isinstance(value, list):
                add_rows(value)
            elif isinstance(value, dict):
                walk(value)
        for key in ("structuredContent", "structured_content", "result"):
            value = node.get(key)
            if isinstance(value, (dict, list)):
                walk(value)
        for embedded in supabase_content_payloads(node.get("content"))[:50]:
            walk(embedded)

    walk(payload)
    return rows


def supabase_safe_advisor_row(row: dict[str, Any]) -> dict[str, Any]:
    safe: dict[str, Any] = {}
    for key in ("category", "group", "level", "severity", "name", "title", "type", "source"):
        if key in row:
            safe[key] = safe_scalar(redact(row.get(key)), 240)
    for key in ("details", "description", "message", "remediation"):
        if key in row:
            safe[f"{key}Hash"] = stable_hash(redact(row.get(key)))[:16]
    return safe


def normalize_supabase_query_results(payload: Any) -> dict[str, Any]:
    result_items = supabase_result_items(payload)
    snapshots: list[dict[str, Any]] = []
    raw_text = json.dumps(payload, ensure_ascii=True, sort_keys=True)
    secret_hits = sum(len(pattern.findall(raw_text)) for pattern in HIGH_CONF_SECRET_PATTERNS)
    mcp_errors = supabase_mcp_error_summaries(payload)
    for name, rows in result_items:
        if not name or not isinstance(rows, list):
            continue
        row_dicts = [row for row in rows if isinstance(row, dict)]
        columns = sorted({safe_scalar(key, 120) for row in row_dicts for key in row.keys()})
        row_hashes = [
            stable_hash(redact(row))[:16]
            for row in row_dicts[:50]
        ]
        snapshots.append({
            "name": safe_scalar(name, 120),
            "rowCount": len(row_dicts),
            "columns": columns,
            "rowHashes": row_hashes,
        })
    row_counts = {item["name"]: int(item.get("rowCount", 0) or 0) for item in snapshots}
    return {
        "snapshots": snapshots,
        "riskSummary": {
            "exposedTablesWithoutRls": row_counts.get("exposed_tables_without_rls", 0),
            "metadataPolicyRisk": row_counts.get("rls_user_metadata_policies", 0),
            "updateSelectPolicyGap": row_counts.get("update_policies_without_select_policy", 0),
            "storageUpsertPolicyGap": row_counts.get("storage_upsert_policy_gaps", 0),
            "viewsMissingSecurityInvoker": row_counts.get("views_missing_security_invoker", 0),
            "exposedSecurityDefinerFunctions": row_counts.get("exposed_security_definer_functions", 0),
        },
        "inputShape": supabase_payload_shape(payload, result_items, snapshots),
        "mcpErrors": mcp_errors,
        "rawSecretPatternHits": secret_hits,
    }


def supabase_expected_result_names(artifact: dict[str, Any]) -> list[str]:
    plan = artifact.get("dbSnapshotPlan") if isinstance(artifact.get("dbSnapshotPlan"), dict) else {}
    queries = plan.get("sqlQueries") if isinstance(plan.get("sqlQueries"), list) else []
    names: list[str] = []
    seen: set[str] = set()
    for query in queries:
        if not isinstance(query, dict):
            continue
        name = safe_scalar(query.get("name", ""), 120)
        if not name or name in seen:
            continue
        seen.add(name)
        names.append(name)
    return names


def supabase_missing_result_names(expected_names: list[str], snapshots: list[Any]) -> list[str]:
    if not expected_names:
        return []
    present = {
        safe_scalar(snapshot.get("name", ""), 120)
        for snapshot in snapshots
        if isinstance(snapshot, dict)
    }
    return [name for name in expected_names if name not in present]


def supabase_unexpected_result_names(expected_names: list[str], snapshots: list[Any]) -> list[str]:
    if not expected_names:
        return []
    expected = set(expected_names)
    names: list[str] = []
    seen: set[str] = set()
    for snapshot in snapshots:
        if not isinstance(snapshot, dict):
            continue
        name = safe_scalar(snapshot.get("name", ""), 120)
        if not name or name in expected or name in seen:
            continue
        seen.add(name)
        names.append(name)
    return names


def supabase_duplicate_result_names(snapshots: list[Any]) -> list[str]:
    names: list[str] = []
    seen: set[str] = set()
    duplicates: set[str] = set()
    for snapshot in snapshots:
        if not isinstance(snapshot, dict):
            continue
        name = safe_scalar(snapshot.get("name", ""), 120)
        if not name:
            continue
        if name in seen and name not in duplicates:
            duplicates.add(name)
            names.append(name)
        seen.add(name)
    return names


def supabase_missing_result_evidence(missing_result_names: list[str]) -> list[str]:
    if not missing_result_names:
        return []
    names = ", ".join(missing_result_names[:24])
    if len(missing_result_names) > 24:
        names += f", ...(+{len(missing_result_names) - 24})"
    return [f"missing expected Supabase result sets: {names}"]


def supabase_unexpected_result_evidence(unexpected_result_names: list[str]) -> list[str]:
    if not unexpected_result_names:
        return []
    names = ", ".join(unexpected_result_names[:24])
    if len(unexpected_result_names) > 24:
        names += f", ...(+{len(unexpected_result_names) - 24})"
    return [f"unexpected Supabase result sets: {names}"]


def supabase_duplicate_result_evidence(duplicate_result_names: list[str]) -> list[str]:
    if not duplicate_result_names:
        return []
    names = ", ".join(duplicate_result_names[:24])
    if len(duplicate_result_names) > 24:
        names += f", ...(+{len(duplicate_result_names) - 24})"
    return [f"duplicate Supabase result sets: {names}"]


def supabase_payload_shape(payload: Any, result_items: list[tuple[str, list[Any]]], snapshots: list[dict[str, Any]]) -> dict[str, Any]:
    top_level_key_count = len(payload) if isinstance(payload, dict) else 0
    content = payload.get("content") if isinstance(payload, dict) else None
    snapshot_names = [
        safe_scalar(snapshot.get("name", ""), 120)
        for snapshot in snapshots
        if isinstance(snapshot, dict)
    ]
    duplicate_item_count = max(0, len(snapshot_names) - len(set(name for name in snapshot_names if name)))
    return {
        "payloadKind": supabase_payload_kind(payload),
        "topLevelKeyCount": top_level_key_count,
        "topLevelArrayCount": len(payload) if isinstance(payload, list) else 0,
        "resultsArrayPresent": isinstance(payload, dict) and isinstance(payload.get("results"), list),
        "resultContainerPresent": isinstance(payload, dict) and isinstance(payload.get("result"), (dict, list)),
        "structuredContentPresent": isinstance(payload, dict)
        and isinstance(payload.get("structuredContent") or payload.get("structured_content"), (dict, list)),
        "contentPartCount": len(content) if isinstance(content, list) else 0,
        "candidateResultItemCount": len(result_items),
        "supportedResultItemCount": len(snapshots),
        "duplicateResultItemCount": duplicate_item_count,
        "mcpErrorCount": len(supabase_mcp_error_summaries(payload)),
    }


def supabase_payload_kind(payload: Any) -> str:
    if isinstance(payload, dict):
        return "object"
    if isinstance(payload, list):
        return "array"
    if payload is None:
        return "null"
    if isinstance(payload, str):
        return "string"
    if isinstance(payload, bool):
        return "boolean"
    if isinstance(payload, (int, float)):
        return "number"
    return "other"


def supabase_result_items(payload: Any) -> list[tuple[str, list[Any]]]:
    if isinstance(payload, list):
        items: list[tuple[str, list[Any]]] = []
        for item in payload:
            if not isinstance(item, dict):
                continue
            if supabase_is_mcp_error_item(item):
                continue
            name = safe_scalar(item.get("name", ""), 120)
            rows = supabase_rows_from_result_item(item)
            if name or rows or supabase_has_result_row_container(item):
                items.append((name, rows))
            else:
                items.extend(supabase_wrapped_result_items(item))
        return items
    if not isinstance(payload, dict):
        return []
    if supabase_is_mcp_error_item(payload):
        return []
    single_name = safe_scalar(payload.get("name", ""), 120)
    single_rows = supabase_rows_from_result_item(payload)
    if single_name and (single_rows or supabase_has_result_row_container(payload)):
        return [(single_name, single_rows)]

    results = payload.get("results")
    if isinstance(results, list):
        items: list[tuple[str, list[Any]]] = []
        for item in results:
            if not isinstance(item, dict):
                continue
            if supabase_is_mcp_error_item(item):
                continue
            name = safe_scalar(item.get("name", ""), 120)
            rows = supabase_rows_from_result_item(item)
            if name and (rows or supabase_has_result_row_container(item)):
                items.append((name, rows))
                continue
            wrapped = supabase_wrapped_result_items(item)
            if wrapped:
                items.extend(wrapped)
                continue
            items.append((name, rows))
        return items
    wrapped_items = supabase_wrapped_result_items(payload)
    if wrapped_items:
        return wrapped_items
    items: list[tuple[str, list[Any]]] = []
    wrapper_keys = {"content", "result", "structuredContent", "structured_content"}
    for key, value in payload.items():
        if key in wrapper_keys:
            continue
        name = safe_scalar(key, 120)
        if isinstance(value, list):
            items.append((name, value))
            continue
        if isinstance(value, dict):
            rows = supabase_rows_from_result_item(value)
            if rows:
                items.append((name, rows))
    return items


def supabase_is_mcp_error_item(item: dict[str, Any]) -> bool:
    if item.get("isError") is True:
        return True
    if isinstance(item.get("error"), (dict, str)):
        return True
    result = item.get("result")
    return isinstance(result, dict) and isinstance(result.get("error"), (dict, str))


def supabase_mcp_error_summaries(payload: Any) -> list[dict[str, str]]:
    errors: list[dict[str, str]] = []
    seen: set[tuple[str, str, str]] = set()

    def add_error(node: dict[str, Any], path: str, inherited_name: str = "") -> None:
        name = safe_scalar(node.get("name") or inherited_name, 120)
        error = node.get("error")
        result = node.get("result")
        if not isinstance(error, (dict, str)) and isinstance(result, dict):
            error = result.get("error")
        code = supabase_mcp_error_code(error) or "mcp_error"
        source = safe_scalar(path, 120)
        key = (name, code, source)
        if key in seen:
            return
        seen.add(key)
        errors.append({
            "name": name,
            "code": code,
            "source": source,
        })

    def walk(node: Any, path: str = "$", inherited_name: str = "") -> None:
        if isinstance(node, list):
            for idx, item in enumerate(node[:200]):
                walk(item, f"{path}[{idx}]", inherited_name)
            return
        if not isinstance(node, dict):
            return
        name = safe_scalar(node.get("name") or inherited_name, 120)
        if supabase_is_mcp_error_item(node):
            add_error(node, path, name)
            return
        for key in ("results", "result", "structuredContent", "structured_content"):
            value = node.get(key)
            if isinstance(value, (dict, list)):
                walk(value, f"{path}.{key}", name)
        for idx, embedded in enumerate(supabase_content_payloads(node.get("content"))[:50]):
            walk(embedded, f"{path}.content[{idx}]", name)

    walk(payload)
    return errors


def supabase_mcp_error_code(error: Any) -> str:
    if isinstance(error, dict):
        code = safe_scalar(error.get("code", ""), 80)
        if code:
            return code
        status = safe_scalar(error.get("status", ""), 80)
        if status:
            return status
    return ""


def supabase_mcp_error_codes(errors: list[dict[str, str]]) -> list[str]:
    codes: list[str] = []
    for error in errors:
        code = safe_scalar(error.get("code", ""), 80)
        if code and code not in codes:
            codes.append(code)
    return codes


def supabase_mcp_error_evidence(errors: list[dict[str, str]]) -> list[str]:
    codes = supabase_mcp_error_codes(errors)
    if not codes:
        return []
    shown = ", ".join(codes[:12])
    if len(codes) > 12:
        shown += f", ...(+{len(codes) - 12})"
    return [f"Supabase MCP returned error transcripts: {shown}"]


def supabase_has_result_row_container(item: dict[str, Any]) -> bool:
    for key in ("rows", "data", "records", "execute_sql_result", "executeSqlResult"):
        if key not in item:
            continue
        value = item.get(key)
        if isinstance(value, list):
            return True
        if isinstance(value, dict) and supabase_has_result_row_container(value):
            return True
    for key in ("structuredContent", "structured_content", "result"):
        value = item.get(key)
        if isinstance(value, dict) and supabase_has_result_row_container(value):
            return True
    return False


def supabase_rows_from_result_item(item: dict[str, Any]) -> list[Any]:
    for key in ("rows", "data", "records"):
        value = item.get(key)
        if isinstance(value, list):
            return value
        if isinstance(value, dict):
            rows = supabase_rows_from_result_item(value)
            if rows:
                return rows
    for key in ("execute_sql_result", "executeSqlResult"):
        value = item.get(key)
        if isinstance(value, list):
            return value
        if isinstance(value, dict):
            rows = supabase_rows_from_result_item(value)
            if rows:
                return rows
    content_rows = supabase_rows_from_embedded_payloads(supabase_content_payloads(item.get("content")))
    if content_rows:
        return content_rows
    for key in ("structuredContent", "structured_content"):
        value = item.get(key)
        if isinstance(value, dict):
            rows = supabase_rows_from_result_item(value)
            if rows:
                return rows
    result = item.get("result")
    if isinstance(result, dict):
        rows = supabase_rows_from_result_item(result)
        if rows:
            return rows
        for key in ("rows", "data"):
            value = result.get(key)
            if isinstance(value, list):
                return value
        for key in ("structuredContent", "structured_content"):
            value = result.get(key)
            if isinstance(value, dict):
                rows = supabase_rows_from_result_item(value)
                if rows:
                    return rows
    return []


def supabase_rows_from_embedded_payloads(candidates: list[Any]) -> list[Any]:
    for candidate in candidates:
        if isinstance(candidate, list):
            return candidate
        if isinstance(candidate, dict):
            rows = supabase_rows_from_result_item(candidate)
            if rows:
                return rows
    return []


def supabase_wrapped_result_items(payload: dict[str, Any]) -> list[tuple[str, list[Any]]]:
    candidates: list[Any] = []
    result = payload.get("result")
    if isinstance(result, (dict, list)):
        candidates.append(result)
        if isinstance(result, dict):
            candidates.extend(supabase_embedded_payloads(result))
    candidates.extend(supabase_embedded_payloads(payload))

    items: list[tuple[str, list[Any]]] = []
    seen: set[tuple[str, str]] = set()
    for candidate in candidates:
        for name, rows in supabase_result_items(candidate):
            if not name or not isinstance(rows, list):
                continue
            key = (name, stable_hash(redact(rows))[:16])
            if key in seen:
                continue
            seen.add(key)
            items.append((name, rows))
    return items


def supabase_embedded_payloads(payload: dict[str, Any]) -> list[Any]:
    candidates: list[Any] = []
    for key in ("structuredContent", "structured_content"):
        value = payload.get(key)
        if isinstance(value, (dict, list)):
            candidates.append(value)
    candidates.extend(supabase_content_payloads(payload.get("content")))
    return candidates


def supabase_content_payloads(content: Any) -> list[Any]:
    if not isinstance(content, list):
        return []
    candidates: list[Any] = []
    for part in content:
        if isinstance(part, str):
            parsed = supabase_parse_text_json(part)
            if parsed is not None:
                candidates.append(parsed)
            continue
        if not isinstance(part, dict):
            continue
        for key in ("json", "structuredContent", "structured_content"):
            value = part.get(key)
            if isinstance(value, (dict, list)):
                candidates.append(value)
        parsed = supabase_parse_text_json(part.get("text"))
        if parsed is not None:
            candidates.append(parsed)
    return candidates


def supabase_parse_text_json(text: Any) -> Any | None:
    if not isinstance(text, str):
        return None
    stripped = text.lstrip("\ufeff").strip()
    if not stripped or len(stripped) > SUPABASE_IMPORT_TEXT_JSON_MAX_CHARS:
        return None
    if not ((stripped.startswith("{") and stripped.endswith("}")) or (stripped.startswith("[") and stripped.endswith("]"))):
        return None
    try:
        return json.loads(stripped)
    except Exception:
        return None


def load_json_file(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8", errors="ignore").lstrip("\ufeff"))
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}


def load_json_payload(path: Path) -> Any:
    try:
        text = path.read_text(encoding="utf-8", errors="ignore").lstrip("\ufeff")
    except Exception:
        return {}
    try:
        return json.loads(text)
    except Exception:
        return load_jsonl_payload(text)


def load_jsonl_payload(text: str) -> Any:
    items: list[Any] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if len(line) > SUPABASE_IMPORT_TEXT_JSON_MAX_CHARS:
            return {}
        if not ((line.startswith("{") and line.endswith("}")) or (line.startswith("[") and line.endswith("]"))):
            return {}
        try:
            parsed = json.loads(line)
        except Exception:
            return {}
        if isinstance(parsed, list):
            items.extend(parsed)
        else:
            items.append(parsed)
    return items if items else {}


def supabase_db_snapshot_plan(cli: dict[str, Any], mcp_config: dict[str, Any]) -> dict[str, Any]:
    read_only_mcp = bool(mcp_config.get("hasSupabaseMcpUrl") and mcp_config.get("readOnlyMode"))
    sql_queries = [
        {
            "name": "schemas_and_tables",
            "sql": (
                "select table_schema, table_name, table_type "
                "from information_schema.tables "
                "where table_schema not in ('pg_catalog','information_schema') "
                "order by table_schema, table_name"
            ),
        },
        {
            "name": "rls_and_table_flags",
            "sql": (
                "select schemaname, tablename, rowsecurity "
                "from pg_tables "
                "where schemaname not in ('pg_catalog','information_schema') "
                "order by schemaname, tablename"
            ),
        },
        {
            "name": "policies",
            "sql": (
                "select schemaname, tablename, policyname, permissive, roles, cmd "
                "from pg_policies "
                "order by schemaname, tablename, policyname"
            ),
        },
        {
            "name": "data_api_role_grants",
            "sql": (
                "select table_schema, table_name, privilege_type, grantee "
                "from information_schema.role_table_grants "
                "where grantee in ('anon','authenticated') "
                "and table_schema not in ('pg_catalog','information_schema') "
                "order by table_schema, table_name, grantee, privilege_type"
            ),
        },
        {
            "name": "exposed_tables_without_rls",
            "sql": (
                "select t.schemaname, t.tablename, t.rowsecurity, "
                "array_agg(distinct g.grantee order by g.grantee) as exposed_roles "
                "from pg_tables t "
                "join information_schema.role_table_grants g "
                "on g.table_schema = t.schemaname and g.table_name = t.tablename "
                "where g.grantee in ('anon','authenticated') "
                "and t.schemaname not in ('pg_catalog','information_schema') "
                "group by t.schemaname, t.tablename, t.rowsecurity "
                "having t.rowsecurity = false "
                "order by t.schemaname, t.tablename"
            ),
        },
        {
            "name": "rls_user_metadata_policies",
            "sql": (
                "select schemaname, tablename, policyname, roles, cmd, qual, with_check "
                "from pg_policies "
                "where coalesce(qual, '') ilike '%user_metadata%' "
                "or coalesce(with_check, '') ilike '%user_metadata%' "
                "or coalesce(qual, '') ilike '%raw_user_meta_data%' "
                "or coalesce(with_check, '') ilike '%raw_user_meta_data%' "
                "order by schemaname, tablename, policyname"
            ),
        },
        {
            "name": "update_policies_without_select_policy",
            "sql": (
                "select upd.schemaname, upd.tablename, upd.policyname, upd.roles "
                "from pg_policies upd "
                "where upper(upd.cmd) in ('UPDATE','ALL') "
                "and not exists ("
                "select 1 from pg_policies sel "
                "where sel.schemaname = upd.schemaname "
                "and sel.tablename = upd.tablename "
                "and upper(sel.cmd) in ('SELECT','ALL') "
                "and (sel.roles && upd.roles or 'public' = any(sel.roles) or 'public' = any(upd.roles))"
                ") "
                "order by upd.schemaname, upd.tablename, upd.policyname"
            ),
        },
        {
            "name": "storage_upsert_policy_gaps",
            "sql": (
                "select roles, array_agg(distinct cmd order by cmd) as commands "
                "from pg_policies "
                "where schemaname = 'storage' and tablename = 'objects' "
                "and upper(cmd) in ('INSERT','SELECT','UPDATE','ALL') "
                "group by roles "
                "having bool_or(upper(cmd) in ('INSERT','ALL')) "
                "and not ("
                "bool_or(upper(cmd) in ('SELECT','ALL')) "
                "and bool_or(upper(cmd) in ('UPDATE','ALL'))"
                ") "
                "order by roles"
            ),
        },
        {
            "name": "views",
            "sql": (
                "select table_schema, table_name "
                "from information_schema.views "
                "where table_schema not in ('pg_catalog','information_schema') "
                "order by table_schema, table_name"
            ),
        },
        {
            "name": "views_missing_security_invoker",
            "sql": (
                "select n.nspname as view_schema, c.relname as view_name, c.reloptions "
                "from pg_class c "
                "join pg_namespace n on n.oid = c.relnamespace "
                "where c.relkind = 'v' "
                "and n.nspname not in ('pg_catalog','information_schema') "
                "and not ('security_invoker=true' = any(coalesce(c.reloptions, array[]::text[]))) "
                "order by n.nspname, c.relname"
            ),
        },
        {
            "name": "exposed_security_definer_functions",
            "sql": (
                "select n.nspname as function_schema, p.proname as function_name, "
                "pg_get_function_identity_arguments(p.oid) as arguments, p.prosecdef, "
                "array_remove(array_agg(distinct rp.grantee order by rp.grantee), null) as executable_by "
                "from pg_proc p "
                "join pg_namespace n on n.oid = p.pronamespace "
                "left join information_schema.routine_privileges rp "
                "on rp.specific_schema = n.nspname and rp.routine_name = p.proname "
                "and rp.grantee in ('anon','authenticated','PUBLIC') "
                "where p.prosecdef = true "
                "and n.nspname not in ('pg_catalog','information_schema','pg_toast') "
                "and (n.nspname in ('public','graphql_public') or rp.grantee is not null) "
                "group by n.nspname, p.proname, p.oid, p.prosecdef "
                "order by n.nspname, p.proname"
            ),
        },
        {
            "name": "shadow_memory_candidate_tables",
            "sql": (
                "select table_schema, table_name, table_type "
                "from information_schema.tables "
                "where table_schema not in ('pg_catalog','information_schema') "
                "and ("
                "lower(table_schema) like '%memory%' "
                "or lower(table_schema) like '%trace%' "
                "or lower(table_name) like '%memory%' "
                "or lower(table_name) like '%trace%' "
                "or lower(table_name) like '%cfvm%' "
                "or lower(table_name) like '%debug%' "
                "or lower(table_name) like '%rag%' "
                "or lower(table_name) like '%vector%' "
                "or lower(table_name) like '%embedding%'"
                ") "
                "order by table_schema, table_name"
            ),
        },
        {
            "name": "shadow_memory_candidate_columns",
            "sql": (
                "select table_schema, table_name, column_name, data_type, ordinal_position "
                "from information_schema.columns "
                "where table_schema not in ('pg_catalog','information_schema') "
                "and ("
                "lower(table_schema) like '%memory%' "
                "or lower(table_schema) like '%trace%' "
                "or lower(table_name) like '%memory%' "
                "or lower(table_name) like '%trace%' "
                "or lower(table_name) like '%cfvm%' "
                "or lower(table_name) like '%debug%' "
                "or lower(table_name) like '%rag%' "
                "or lower(table_name) like '%vector%' "
                "or lower(table_name) like '%embedding%'"
                ") "
                "order by table_schema, table_name, ordinal_position"
            ),
        },
        {
            "name": "shadow_memory_metadata_fingerprints",
            "sql": (
                "select table_schema, table_name, count(*) as column_count, "
                "md5(string_agg(column_name || ':' || data_type || ':' || ordinal_position::text, '|' "
                "order by ordinal_position)) as metadata_fingerprint "
                "from information_schema.columns "
                "where table_schema not in ('pg_catalog','information_schema') "
                "and ("
                "lower(table_schema) like '%memory%' "
                "or lower(table_schema) like '%trace%' "
                "or lower(table_name) like '%memory%' "
                "or lower(table_name) like '%trace%' "
                "or lower(table_name) like '%cfvm%' "
                "or lower(table_name) like '%debug%' "
                "or lower(table_name) like '%rag%' "
                "or lower(table_name) like '%vector%' "
                "or lower(table_name) like '%embedding%'"
                ") "
                "group by table_schema, table_name "
                "order by table_schema, table_name"
            ),
        },
        {
            "name": "extensions",
            "sql": "select extname, extversion from pg_extension order by extname",
        },
    ]
    return {
        "readOnly": True,
        "mutationAllowed": False,
        "canRunCliNow": bool(cli.get("present")),
        "mcpReadOnlyConfigured": read_only_mcp,
        "recommendedOutputPath": "data/db-gap-report/supabase-schema-snapshot.json",
        "recommendedSqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
        "discoveryCommands": [
            "supabase --version",
            "supabase --help",
            "supabase db --help",
            "supabase db query --help",
            "supabase db advisors --help",
        ],
        "cliVersionRequirements": {
            "supabase db query": ">=2.79.0",
            "supabase db advisors": ">=2.81.3",
        },
        "cliFallbackContract": {
            "supabase db query": {
                "mcpTool": "execute_sql",
                "when": "cli_missing_or_below_minimum",
                "readOnly": True,
            },
            "supabase db advisors": {
                "mcpTool": "get_advisors",
                "when": "cli_missing_or_below_minimum",
                "readOnly": True,
            },
        },
        "mcpFallbackTools": ["execute_sql", "get_advisors"],
        "docsRefs": [
            {
                "id": "supabase-mcp",
                "url": "https://supabase.com/docs/guides/ai-tools/mcp",
                "contract": "Use read_only=true for read-only mode and project_ref to scope MCP access.",
            },
            {
                "id": "data-api-grants-breaking-change",
                "url": "https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically",
                "contract": "Tables and functions are not automatically exposed to the Data API; explicit grants control reachability.",
            },
            {
                "id": "securing-data-api",
                "url": "https://supabase.com/docs/guides/api/securing-your-api",
                "contract": "Data API access and RLS are separate checks; exposed roles need grants and row policies.",
            },
            {
                "id": "product-security",
                "url": "https://supabase.com/docs/guides/security/product-security",
                "contract": "Use Supabase product security guidance as the cross-product hardening checklist.",
            },
            {
                "id": "api-keys",
                "url": "https://supabase.com/docs/guides/getting-started/api-keys",
                "contract": "Publishable keys may appear in clients; secret keys are backend-only and bypass RLS.",
            },
            {
                "id": "rls",
                "url": "https://supabase.com/docs/guides/database/postgres/row-level-security",
                "contract": "RLS policies decide which rows are visible after a role can access a table.",
            },
        ],
        "breakingChanges": [
            {
                "id": "data-api-explicit-grants",
                "effectiveDate": "2026-04-28",
                "impact": "new tables, views, and functions require explicit grants for anon/authenticated Data API access",
            },
        ],
        "securityContracts": [
            "mcp_project_ref_should_scope_access",
            "data_api_grants_control_reachability",
            "rls_controls_visible_rows_after_grant",
            "auth_user_metadata_is_not_authorization_source",
            "secret_keys_backend_only",
            "views_need_security_invoker_or_private_schema",
            "storage_upsert_requires_insert_select_update",
        ],
        "sqlQueries": sql_queries,
    }


def supabase_cli_summary(timeout_sec: int) -> dict[str, Any]:
    cli_path = shutil.which("supabase")
    if not cli_path:
        return {"present": False, "version": "", "pathHash": "", "pathLength": 0}
    try:
        completed = subprocess.run(
            [cli_path, "--version"],
            capture_output=True,
            text=True,
            timeout=timeout_sec,
            check=False,
        )
        combined = "\n".join(part for part in (completed.stdout, completed.stderr) if part)
        version = safe_message(combined.splitlines()[0] if combined.splitlines() else "", 80)
        return {
            "present": True,
            "version": version,
            "exitCode": completed.returncode,
            "pathHash": stable_hash(cli_path),
            "pathLength": len(cli_path),
        }
    except Exception as exc:
        return {
            "present": True,
            "version": "",
            "pathHash": stable_hash(cli_path),
            "pathLength": len(cli_path),
            "failReason": exc.__class__.__name__,
        }


def supabase_mcp_summary(mcp_url: str, timeout_sec: int) -> dict[str, Any]:
    parsed = urllib.parse.urlparse(mcp_url)
    endpoint_kind = supabase_endpoint_kind(parsed)
    request = urllib.request.Request(mcp_url, headers={"Accept": "application/json"}, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            response.read(4096)
            auth_gate = endpoint_kind == "supabase_mcp" and response.status in {401, 403}
            return {
                "reachable": True,
                "httpStatus": response.status,
                "endpointKind": endpoint_kind,
                "unauthenticatedExpected": auth_gate,
                "decision": "mcp_endpoint_auth_required" if auth_gate else "mcp_endpoint_reachable",
            }
    except urllib.error.HTTPError as exc:
        exc.read(4096)
        auth_gate = endpoint_kind == "supabase_mcp" and exc.code in {401, 403}
        return {
            "reachable": exc.code in {200, 401, 403, 405},
            "httpStatus": exc.code,
            "endpointKind": endpoint_kind,
            "unauthenticatedExpected": auth_gate,
            "decision": "mcp_endpoint_auth_required" if auth_gate else "mcp_endpoint_http_error",
            "failReason": f"http-{exc.code}",
        }
    except Exception as exc:
        return {
            "reachable": False,
            "endpointKind": endpoint_kind,
            "decision": "mcp_endpoint_unavailable",
            "failReason": exc.__class__.__name__,
            "message": safe_message(str(exc), 120),
        }


def supabase_mcp_probe_skipped_summary(mcp_url: str) -> dict[str, Any]:
    parsed = urllib.parse.urlparse(mcp_url)
    return {
        "reachable": False,
        "httpStatus": "",
        "endpointKind": supabase_endpoint_kind(parsed),
        "unauthenticatedExpected": False,
        "probeSkipped": True,
        "decision": "mcp_endpoint_probe_skipped",
        "failReason": "network-probe-skipped",
    }


def supabase_endpoint_kind(parsed: urllib.parse.ParseResult) -> str:
    host = (parsed.hostname or "").lower()
    if host == "mcp.supabase.com":
        return "supabase_mcp"
    if host in {"localhost", "127.0.0.1", "::1"}:
        return "loopback"
    if host.endswith(".supabase.co"):
        return "supabase_project"
    return "other"


def supabase_mcp_config_summary(root: Path) -> dict[str, Any]:
    path = root / ".mcp.json"
    if not path_exists(path):
        return {
            "present": False,
            "jsonValid": False,
            "hasSupabaseMcpUrl": False,
            "readOnlyMode": False,
            "featuresScopedMode": False,
            "featureGroups": [],
            "projectScopedMode": False,
            "projectRefTemplateMode": False,
            "projectRefEnvPresent": False,
            "projectScopeSource": "missing",
            "scopedMcpUrlTemplate": "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=<project_ref>",
            "serverCount": 0,
        }
    try:
        raw = path.read_text(encoding="utf-8", errors="ignore")
        data = json.loads(raw)
        server_count = supabase_mcp_server_count(data)
        supabase_servers = supabase_mcp_servers(data)
        supabase_urls = [server["url"] for server in supabase_servers]
        server_types = sorted({server["type"] for server in supabase_servers if server["type"]})
        feature_groups = sorted({
            group
            for url in supabase_urls
            for group in supabase_mcp_url_feature_groups(url)
        })
        literal_project_scoped = any(supabase_mcp_url_project_scoped(url) for url in supabase_urls)
        project_ref_template_mode = any(supabase_mcp_url_project_ref_template_mode(url) for url in supabase_urls)
        project_ref_env_present = project_ref_template_mode and supabase_project_ref_env_present()
        project_scoped = literal_project_scoped or project_ref_env_present
        project_scope_source = (
            "literal_url"
            if literal_project_scoped
            else "env_template"
            if project_ref_env_present
            else "template_missing_env"
            if project_ref_template_mode
            else "missing"
        )
        secret_hits = sum(len(pattern.findall(raw)) for pattern in HIGH_CONF_SECRET_PATTERNS)
        return {
            "present": True,
            "jsonValid": True,
            "hasSupabaseMcpUrl": bool(supabase_urls),
            "readOnlyMode": any(supabase_mcp_url_is_read_only(url) for url in supabase_urls),
            "featuresScopedMode": bool(supabase_urls)
            and all(supabase_mcp_url_features_scoped(url) for url in supabase_urls),
            "featureGroups": feature_groups,
            "projectScopedMode": project_scoped,
            "projectRefTemplateMode": project_ref_template_mode,
            "projectRefEnvPresent": project_ref_env_present,
            "projectScopeSource": project_scope_source,
            "scopedMcpUrlTemplate": supabase_scoped_mcp_url_template(supabase_urls),
            "serverType": server_types[0] if server_types else "",
            "supabaseServerCount": len(supabase_urls),
            "serverCount": server_count,
            "rawSecretPatternHits": secret_hits,
            "fileHash": stable_hash(raw),
            "fileLength": len(raw),
        }
    except Exception as exc:
        return {
            "present": True,
            "jsonValid": False,
            "hasSupabaseMcpUrl": False,
            "readOnlyMode": False,
            "featuresScopedMode": False,
            "featureGroups": [],
            "projectScopedMode": False,
            "projectRefTemplateMode": False,
            "projectRefEnvPresent": False,
            "projectScopeSource": "missing",
            "scopedMcpUrlTemplate": "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=<project_ref>",
            "serverCount": 0,
            "failReason": exc.__class__.__name__,
        }


def supabase_project_scope_summary(mcp_config: dict[str, Any]) -> dict[str, Any]:
    project_ref_present = bool(mcp_config.get("projectScopedMode"))
    project_ref_template = bool(mcp_config.get("projectRefTemplateMode"))
    project_ref_env_present = bool(mcp_config.get("projectRefEnvPresent"))
    project_scope_source = safe_scalar(mcp_config.get("projectScopeSource") or "missing", 80)
    feature_groups = mcp_config.get("featureGroups")
    if not isinstance(feature_groups, list) or not feature_groups:
        feature_groups = ["database", "debugging", "docs"]
    next_action = (
        "run_readonly_schema_snapshot"
        if project_ref_present
        else "set_SUPABASE_PROJECT_REF"
        if project_ref_template
        else "set_project_ref_in_mcp_config"
    )
    return {
        "status": "project_ref_present" if project_ref_present else "project_ref_missing",
        "nextAction": next_action,
        "projectRefPresent": project_ref_present,
        "projectRefTemplateMode": project_ref_template,
        "projectRefEnvPresent": project_ref_env_present,
        "projectScopeSource": project_scope_source,
        "readOnlyMode": bool(mcp_config.get("readOnlyMode")),
        "featuresScopedMode": bool(mcp_config.get("featuresScopedMode")),
        "featureGroups": sorted(str(item) for item in feature_groups),
        "scopedMcpUrlTemplate": safe_scalar(
            mcp_config.get("scopedMcpUrlTemplate")
            or "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=<project_ref>",
            240,
        ),
        "rawSecretPatternHits": int(mcp_config.get("rawSecretPatternHits", 0) or 0),
    }


def supabase_auth_plan(
    cli: dict[str, Any],
    mcp: dict[str, Any],
    mcp_config: dict[str, Any],
    project_scope: dict[str, Any],
    db_snapshot_plan: dict[str, Any],
) -> dict[str, Any]:
    cli_commands = [
        "supabase --version",
        "supabase --help",
        "supabase link --help",
        "supabase db --help",
        "supabase db query --help",
        "supabase db advisors --help",
    ]
    fallback_tools = db_snapshot_plan.get("mcpFallbackTools")
    if not isinstance(fallback_tools, list) or not fallback_tools:
        fallback_tools = ["execute_sql", "get_advisors"]
    project_missing = project_scope.get("status") == "project_ref_missing"
    mcp_oauth_required = bool(mcp_config.get("hasSupabaseMcpUrl")) and (
        bool(mcp.get("unauthenticatedExpected"))
        or safe_scalar(mcp.get("decision", ""), 80) == "mcp_endpoint_auth_required"
        or project_missing
    )
    manual_auth_env_refs = ["SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"]
    manual_auth_env_status = [
        {
            "name": name,
            "present": bool(os.environ.get(name)),
            "sensitive": supabase_env_is_secret(name),
        }
        for name in manual_auth_env_refs
    ]
    return {
        "readOnly": bool(mcp_config.get("readOnlyMode")),
        "featuresScopedMode": bool(mcp_config.get("featuresScopedMode")),
        "projectRefPresent": bool(project_scope.get("projectRefPresent")),
        "mcpOAuthRequired": mcp_oauth_required,
        "mcpOAuthAction": "complete_supabase_mcp_oauth_flow" if mcp_oauth_required else "mcp_oauth_not_required",
        "mcpSetupGuideUrl": "https://supabase.com/docs/guides/ai-tools/mcp",
        "defaultHostedAuthMode": "dynamic_client_registration_oauth",
        "manualAuthMode": "manual_pat_header_for_ci",
        "supportedAuthModes": ["dynamic_oauth", "manual_ci_pat_auth"],
        "manualAuthEnvRefs": manual_auth_env_refs,
        "manualAuthEnvStatus": manual_auth_env_status,
        "manualAuthEnvPresent": {name: bool(os.environ.get(name)) for name in manual_auth_env_refs},
        "manualAuthHeaderTemplate": "Bearer ${SUPABASE_ACCESS_TOKEN}",
        "manualProjectRefTemplate": "${SUPABASE_PROJECT_REF}",
        "manualAuthConfigTemplate": {
            "url": "https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs",
            "headers": {"Authorization": "Bearer ${SUPABASE_ACCESS_TOKEN}"},
        },
        "securityWarnings": ["development_and_testing_only", "prefer_read_only", "project_scope_required"],
        "scopedMcpUrlTemplate": safe_scalar(
            project_scope.get("scopedMcpUrlTemplate")
            or "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=<project_ref>",
            240,
        ),
        "cliPresent": bool(cli.get("present")),
        "cliDiscoveryCommands": cli_commands,
        "cliProjectLinkCommandTemplate": "supabase link --project-ref <project_ref>",
        "mcpFallbackTools": [safe_scalar(tool, 80) for tool in fallback_tools if safe_scalar(tool, 80)],
        "runOrder": [
            "select_target_project_ref",
            "complete_supabase_mcp_oauth_flow",
            "update_mcp_url_with_project_ref",
            "run_supabase_cli_help_discovery",
            "run_readonly_schema_snapshot",
        ],
    }


def supabase_mcp_servers(data: Any) -> list[dict[str, str]]:
    if not isinstance(data, dict):
        return []
    servers = data.get("mcpServers") or data.get("servers") or data.get("inputs")
    if isinstance(servers, dict):
        candidates = servers.values()
    elif isinstance(servers, list):
        candidates = servers
    else:
        candidates = []
    summaries: list[dict[str, str]] = []
    for candidate in candidates:
        if isinstance(candidate, str):
            url = candidate
            server_type = ""
        elif isinstance(candidate, dict):
            url = str(candidate.get("url") or candidate.get("serverUrl") or "")
            server_type = str(candidate.get("type") or "")
        else:
            url = ""
            server_type = ""
        parsed = urllib.parse.urlparse(url)
        if supabase_endpoint_kind(parsed) == "supabase_mcp":
            summaries.append({"url": url, "type": server_type})
    return summaries


def supabase_mcp_urls(data: Any) -> list[str]:
    return [server["url"] for server in supabase_mcp_servers(data)]


def supabase_mcp_url_is_read_only(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    query = urllib.parse.parse_qs(parsed.query)
    return any(value.lower() == "true" for value in query.get("read_only", []))


def supabase_mcp_url_feature_groups(url: str) -> list[str]:
    parsed = urllib.parse.urlparse(url)
    query = urllib.parse.parse_qs(parsed.query)
    groups: set[str] = set()
    for value in query.get("features", []):
        for item in value.split(","):
            group = item.strip().lower()
            if group:
                groups.add(group)
    return sorted(groups)


def supabase_mcp_url_features_scoped(url: str) -> bool:
    groups = set(supabase_mcp_url_feature_groups(url))
    allowed = {"database", "debugging", "docs"}
    return bool(groups) and "database" in groups and groups.issubset(allowed)


def supabase_mcp_url_project_scoped(url: str) -> bool:
    return any(
        value
        for value in supabase_mcp_url_project_ref_values(url)
        if not supabase_project_ref_value_is_placeholder(value)
    )


def supabase_mcp_url_project_ref_template_mode(url: str) -> bool:
    return any(
        supabase_project_ref_value_is_placeholder(value)
        for value in supabase_mcp_url_project_ref_values(url)
    )


def supabase_project_ref_env_present() -> bool:
    return bool(os.environ.get("SUPABASE_PROJECT_REF", "").strip())


def supabase_mcp_url_project_ref_values(url: str) -> list[str]:
    parsed = urllib.parse.urlparse(url)
    query = urllib.parse.parse_qs(parsed.query)
    return [value.strip() for value in query.get("project_ref", []) if value.strip()]


def supabase_project_ref_value_is_placeholder(value: str) -> bool:
    lowered = value.strip().lower()
    return (
        not lowered
        or lowered in {"<project_ref>", "<target-project-ref>", "project_ref", "project-ref"}
        or lowered.startswith("${")
        or "<" in lowered
        or ">" in lowered
    )


def supabase_scoped_mcp_url_template(urls: list[str]) -> str:
    url = urls[0] if urls else "https://mcp.supabase.com/mcp"
    parsed = urllib.parse.urlparse(url)
    scheme = parsed.scheme or "https"
    netloc = parsed.netloc or "mcp.supabase.com"
    path = parsed.path or "/mcp"
    features = ",".join(supabase_mcp_url_feature_groups(url)) or "database,debugging,docs"
    scoped_query = f"read_only=true&features={features}&project_ref=<project_ref>"
    return urllib.parse.urlunparse((scheme, netloc, path, "", scoped_query, ""))


def supabase_mcp_server_count(data: Any) -> int:
    if not isinstance(data, dict):
        return 0
    servers = data.get("mcpServers") or data.get("servers") or data.get("inputs")
    if isinstance(servers, dict):
        return len(servers)
    if isinstance(servers, list):
        return len(servers)
    return 0


def supabase_env_is_secret(name: str) -> bool:
    upper = name.upper()
    return any(marker in upper for marker in ("TOKEN", "KEY", "SECRET", "PASSWORD", "DB_URL", "DATABASE_URL", "POSTGRES_URL"))


def local_agent_db_snapshot(payload: dict[str, Any], endpoint: str) -> dict[str, Any]:
    root = resolve_path(payload.get("root") or ".")
    resource_roots = [
        root / "main" / "resources",
        root / "app" / "src" / "main" / "resources",
    ]
    return {
        "endpoint": endpoint,
        "source": "local_file_and_source_metadata",
        "rootHash": stable_hash(str(root)),
        "rootLength": len(str(root)),
        "failurePatternMemory": failure_pattern_memory_summary(root / "logs" / "failure-pattern-memory.jsonl"),
        "jpaSurface": jpa_surface_summary(root),
        "subsystemPersistence": subsystem_persistence_summary(root),
        "datasourceEnvRefs": sorted({
            ref
            for resource_root in resource_roots
            for ref in env_refs(resource_root, max_files=1000, skip_tests=True)
            if ref.startswith("LMS_DB_") or ref == "AGENT_DB_CONTEXT_QUERY_TIMEOUT_SECONDS"
        }),
        "evidence_needed": [
            "Spring runtime /agent/db-context response for live database snapshot",
        ],
    }


def failure_pattern_memory_summary(path: Path) -> dict[str, Any]:
    if not path_exists(path):
        return {"exists": False, "rowCountScanned": 0}
    rows: list[dict[str, Any]] = []
    malformed = 0
    try:
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
    except OSError:
        return {"exists": True, "readable": False, "rowCountScanned": 0}
    for line in lines[-200:]:
        if not line.strip():
            continue
        try:
            loaded = json.loads(line)
            if isinstance(loaded, dict):
                rows.append(redact(loaded))
            else:
                malformed += 1
        except json.JSONDecodeError:
            malformed += 1
    last_row = rows[-1] if rows else {}
    safe_last = {
        key: last_row.get(key)
        for key in (
            "schema",
            "kind",
            "source",
            "failureClass",
            "hotspot",
            "patchAction",
            "decision",
            "patternId",
            "intentHash12",
            "evidenceHash12",
        )
        if key in last_row
    }
    return {
        "exists": True,
        "readable": True,
        "fileHash": sha256_file(path),
        "fileLength": path_size(path),
        "rowCountScanned": len(rows),
        "malformedTailRows": malformed,
        "lastRow": safe_last,
    }


def jpa_surface_summary(root: Path) -> dict[str, Any]:
    java_roots = [
        root / "main" / "java",
        root / "app" / "src" / "main" / "java_clean",
    ]
    entity_count = 0
    repository_count = 0
    samples: list[dict[str, Any]] = []
    for java_root in java_roots:
        for file_path in iter_regular_files(java_root, max_files=6000, skip_tests=True):
            if file_path.suffix.lower() != ".java" or path_size(file_path) > 1_000_000:
                continue
            try:
                text = file_path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            is_entity = bool(JPA_ENTITY_DECLARATION_RE.search(text))
            is_repo = bool(JPA_REPOSITORY_EXTENDS_RE.search(text))
            if is_entity:
                entity_count += 1
            if is_repo:
                repository_count += 1
            if (is_entity or is_repo) and len(samples) < 20:
                samples.append({
                    "path": safe_relpath(file_path, root),
                    "entity": is_entity,
                    "repository": is_repo,
                })
    return {
        "entityFileCount": entity_count,
        "repositoryFileCount": repository_count,
        "samples": samples,
    }


def subsystem_persistence_summary(root: Path) -> dict[str, Any]:
    raw_matrix_path = root / "main" / "java" / "com" / "example" / "lms" / "cfvm" / "RawMatrixBuffer.java"
    cfvm_snapshot_entity_path = root / "main" / "java" / "com" / "example" / "lms" / "cfvm" / "CfvmSnapshot.java"
    cfvm_snapshot_repository_path = root / "main" / "java" / "com" / "example" / "lms" / "cfvm" / "CfvmSnapshotRepository.java"
    cfvm_snapshot_service_path = root / "main" / "java" / "com" / "example" / "lms" / "cfvm" / "CfvmSnapshotService.java"
    art_plate_log_path = root / "main" / "java" / "com" / "example" / "lms" / "artplate" / "ArtPlateEvolutionLog.java"
    art_plate_repository_path = root / "main" / "java" / "com" / "example" / "lms" / "artplate" / "ArtPlateEvolutionLogRepository.java"
    art_plate_evolver_path = root / "main" / "java" / "com" / "example" / "lms" / "artplate" / "ArtPlateEvolver.java"
    trace_store_path = root / "main" / "java" / "com" / "example" / "lms" / "search" / "TraceStore.java"
    raw_matrix_text = read_small_text(raw_matrix_path)
    cfvm_snapshot_entity_text = read_small_text(cfvm_snapshot_entity_path)
    cfvm_snapshot_repository_text = read_small_text(cfvm_snapshot_repository_path)
    cfvm_snapshot_service_text = read_small_text(cfvm_snapshot_service_path)
    art_plate_log_text = read_small_text(art_plate_log_path)
    art_plate_repository_text = read_small_text(art_plate_repository_path)
    art_plate_evolver_text = read_small_text(art_plate_evolver_path)
    trace_store_text = read_small_text(trace_store_path)
    raw_matrix_repo_refs = source_reference_count(root, (
        "RawMatrixBufferRepository",
        "RawMatrixBufferEntity",
        "cfvm_raw_matrix",
        "CfvmSnapshotRepository",
        "CfvmSnapshotService",
        "cfvm_snapshot",
    ))
    cfvm_snapshot_backed = (
        "@Entity" in cfvm_snapshot_entity_text
        and "JpaRepository<CfvmSnapshot" in cfvm_snapshot_repository_text
        and "restoreOnStartup" in cfvm_snapshot_service_text
        and "persistSnapshot" in cfvm_snapshot_service_text
    )
    art_plate_backed = (
        "@Entity" in art_plate_log_text
        and "JpaRepository<ArtPlateEvolutionLog" in art_plate_repository_text
        and "ArtPlateEvolutionLogRepository" in art_plate_evolver_text
        and "repository.save(ArtPlateEvolutionLog.from" in art_plate_evolver_text
    )
    trace_store_repo_refs = source_reference_count(root, (
        "TraceStoreRepository",
        "TraceEventEntity",
        "trace_store_event",
    ))
    extremez_paths = sorted({
        safe_relpath(path, root)
        for path in iter_regular_files(root / "main" / "java", max_files=6000, skip_tests=True)
        if path.name == "ExtremeZSystemHandler.java"
    })
    canonical_extremez = "main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java"
    return {
        "cfvmRawMatrixBuffer": {
            "path": safe_relpath(raw_matrix_path, root),
            "exists": bool(raw_matrix_text),
            "storageMode": (
                "process_local_ring_buffer_with_snapshot_restore"
                if cfvm_snapshot_backed
                else "process_local_ring_buffer"
                if "ArrayDeque" in raw_matrix_text and "DEFAULT_CAPACITY = 9" in raw_matrix_text
                else "unknown"
            ),
            "durable": False,
            "repositoryBacked": bool(cfvm_snapshot_backed),
            "durableCheckpoint": bool(cfvm_snapshot_backed),
            "repositoryRefCount": raw_matrix_repo_refs,
            "checkpointEntityPath": safe_relpath(cfvm_snapshot_entity_path, root),
            "checkpointRepositoryPath": safe_relpath(cfvm_snapshot_repository_path, root),
            "checkpointServicePath": safe_relpath(cfvm_snapshot_service_path, root),
            "gapClass": (
                "snapshot-backed-process-buffer"
                if cfvm_snapshot_backed
                else "volatile-process-memory"
                if "ArrayDeque" in raw_matrix_text and raw_matrix_repo_refs == 0
                else "evidence_needed"
            ),
            "evidence": [
                "ArrayDeque" if "ArrayDeque" in raw_matrix_text else "missing:ArrayDeque",
                "DEFAULT_CAPACITY_9" if "DEFAULT_CAPACITY = 9" in raw_matrix_text else "missing:DEFAULT_CAPACITY_9",
                "CfvmSnapshotEntity" if "@Entity" in cfvm_snapshot_entity_text else "missing:CfvmSnapshotEntity",
                "CfvmSnapshotRepository" if "JpaRepository<CfvmSnapshot" in cfvm_snapshot_repository_text else "missing:CfvmSnapshotRepository",
                "CfvmSnapshotService.restoreOnStartup" if "restoreOnStartup" in cfvm_snapshot_service_text else "missing:restoreOnStartup",
                "CfvmSnapshotService.persistSnapshot" if "persistSnapshot" in cfvm_snapshot_service_text else "missing:persistSnapshot",
            ],
        },
        "traceStore": {
            "path": safe_relpath(trace_store_path, root),
            "exists": bool(trace_store_text),
            "storageMode": "thread_local_request_trace"
            if "ThreadLocal" in trace_store_text and "ConcurrentHashMap" in trace_store_text
            else "unknown",
            "durable": False,
            "repositoryRefCount": trace_store_repo_refs,
            "gapClass": "request-local-non-durable-trace"
            if "ThreadLocal" in trace_store_text and trace_store_repo_refs == 0
            else "evidence_needed",
            "evidence": [
                "ThreadLocal" if "ThreadLocal" in trace_store_text else "missing:ThreadLocal",
                "ConcurrentHashMap" if "ConcurrentHashMap" in trace_store_text else "missing:ConcurrentHashMap",
                "no_repository_ref" if trace_store_repo_refs == 0 else "repository_ref_present",
            ],
        },
        "artPlateEvolver": {
            "path": safe_relpath(art_plate_evolver_path, root),
            "exists": bool(art_plate_evolver_text),
            "storageMode": "repository_backed_evolution_log" if art_plate_backed else "process_local_evolver",
            "durable": bool(art_plate_backed),
            "repositoryBacked": bool(art_plate_backed),
            "durableCheckpoint": bool(art_plate_backed),
            "checkpointEntityPath": safe_relpath(art_plate_log_path, root),
            "checkpointRepositoryPath": safe_relpath(art_plate_repository_path, root),
            "gapClass": "db-backed-evolution-log" if art_plate_backed else "evidence_needed",
            "evidence": [
                "ArtPlateEvolutionLogEntity" if "@Entity" in art_plate_log_text else "missing:ArtPlateEvolutionLogEntity",
                "ArtPlateEvolutionLogRepository" if "JpaRepository<ArtPlateEvolutionLog" in art_plate_repository_text else "missing:ArtPlateEvolutionLogRepository",
                "ArtPlateEvolver.repositoryProvider" if "ArtPlateEvolutionLogRepository" in art_plate_evolver_text else "missing:repositoryProvider",
                "ArtPlateEvolver.repositorySave" if "repository.save(ArtPlateEvolutionLog.from" in art_plate_evolver_text else "missing:repositorySave",
            ],
        },
        "extremeZ": {
            "canonicalPath": canonical_extremez,
            "paths": extremez_paths,
            "aliasCount": len(extremez_paths),
            "gapClass": "multi-package-alias-surface" if len(extremez_paths) > 1 else "single-canonical",
            "canonicalPresent": canonical_extremez in extremez_paths,
        },
    }


def read_small_text(path: Path, limit: int = 1_000_000) -> str:
    if not path_is_file(path) or path_size(path) > limit:
        return ""
    try:
        return path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def source_reference_count(root: Path, needles: tuple[str, ...]) -> int:
    count = 0
    for java_root in (root / "main" / "java", root / "app" / "src" / "main" / "java_clean"):
        for file_path in iter_regular_files(java_root, max_files=6000, skip_tests=True):
            if file_path.suffix.lower() != ".java" or path_size(file_path) > 1_000_000:
                continue
            text = read_small_text(file_path)
            if any(needle in text for needle in needles):
                count += 1
    return count


def target_is_protected_canonical(payload: dict[str, Any], target_dir: Path) -> bool:
    roots: list[Path] = []
    configured = payload.get("canonical_root")
    if isinstance(configured, str) and configured.strip():
        roots.append(resolve_path(configured))
    roots.append(Path("C:/AbandonWare/demo-1/demo-1/src").resolve())
    return any(is_relative_to_path(target_dir, root) for root in roots)


def is_relative_to_path(child: Path, parent: Path) -> bool:
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def scan_tree(path: Path) -> dict[str, Any]:
    meta = redacted_path_fields(path)
    try:
        exists = path.exists()
    except OSError:
        return {**meta, "exists": True, "fileCount": 0, "access": "limited"}
    if not exists:
        return {**meta, "exists": False, "fileCount": 0}
    try:
        files = [p for p in path.rglob("*") if path_is_file(p)]
    except OSError:
        return {**meta, "exists": True, "fileCount": 0, "access": "limited"}
    return {**meta, "exists": True, "fileCount": len(files)}


def patchdrop_summary(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {
            "exists": False,
            "topLevelPatchCount": 0,
            "pendingProducerCount": 0,
            "pendingV3Count": 0,
        }
    return {
        "exists": True,
        "topLevelPatchCount": len(list(path.glob("*.patch"))),
        "pendingProducerCount": len(list(path.glob("*.macmini-pending.md"))) + len(list(path.glob("*.notebook-pending.md"))),
        "pendingV3Count": len(list(path.glob("*-v3.patch"))),
    }


def secret_hit_count(path: Path | None, max_files: int, include_generic: bool = False, skip_tests: bool = False) -> int:
    if path is None or not path_exists(path):
        return 0
    patterns = REDACTION_PATTERNS if include_generic else HIGH_CONF_SECRET_PATTERNS
    count = 0
    for file_path in iter_regular_files(path, max_files, skip_tests=skip_tests):
        if path_size(file_path) > 2_000_000:
            continue
        try:
            text = file_path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for pattern in patterns:
            count += len(pattern.findall(text))
    return count


def env_refs(root: Path, max_files: int, skip_tests: bool = False) -> list[str]:
    refs: set[str] = set()
    if not path_exists(root):
        return []
    for file_path in iter_regular_files(root, max_files, skip_tests=skip_tests):
        if file_path.suffix.lower() not in {".java", ".yml", ".yaml", ".properties", ".md", ".json"}:
            continue
        if path_size(file_path) > 1_000_000:
            continue
        try:
            text = file_path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for name in ENV_REFS:
            if name in text:
                refs.add(name)
    return sorted(refs)


def apikey_env_refs(root: Path) -> list[str]:
    refs: set[str] = set()
    for name in ("apikey.ps1", "apikey.txt"):
        path = root / name
        if not path.exists() or not path.is_file() or path.stat().st_size > 256_000:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore").lstrip("\ufeff")
        except OSError:
            continue
        for match in re.finditer(r"(?i)\$env:([A-Z][A-Z0-9_]{1,})", text):
            env_name = match.group(1).upper()
            if env_name in ENV_REFS:
                refs.add(env_name)
        for match in re.finditer(r"(?m)^\s*(?:export\s+)?([A-Z][A-Z0-9_]{1,})\s*[:=]", text):
            env_name = match.group(1).upper()
            if env_name in ENV_REFS:
                refs.add(env_name)
    return sorted(refs)


def iter_regular_files(path: Path, max_files: int, skip_tests: bool = False):
    if path_is_file(path):
        yield path
        return
    skipped_dirs = {
        ".git",
        ".gradle",
        ".idea",
        ".next",
        ".turbo",
        ".swc",
        "build",
        "node_modules",
        "__pycache__",
        "target",
        "rollback",
        "applied",
        "rejected",
        "superseded",
        "orphan",
        "source-edit-locks",
    }
    emitted = 0
    for current, dirs, files in os.walk(path):
        dirs[:] = [
            name for name in dirs
            if name not in skipped_dirs and not name.startswith("build-") and not name.startswith(".next-")
        ]
        if skip_tests and Path(current).parts[-2:] == ("src", "test"):
            dirs[:] = []
            continue
        if skip_tests and "test" in Path(current).parts:
            dirs[:] = []
            continue
        for name in files:
            if skip_tests and name.lower().startswith("test_"):
                continue
            if Path(name).suffix.lower() in {".pyc", ".pyo", ".class"}:
                continue
            yield Path(current) / name
            emitted += 1
            if emitted >= max_files:
                return


def expand_queries(query: str) -> list[str]:
    q = query.strip()
    if not q:
        return ["", "safe patch evidence"]
    tokens = [token for token in re.split(r"\s+", q) if token]
    expanded = [q]
    if tokens:
        expanded.append(" ".join(tokens[: max(1, len(tokens) - 1)]))
    expanded.append(q + " evidence-needed")
    deduped: list[str] = []
    for item in expanded:
        if item not in deduped:
            deduped.append(item)
    while len(deduped) < 2:
        deduped.append(q + " expanded")
    return deduped


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.lstrip("\ufeff")
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            rows.append(value)
    return rows


def find_node_smoke_evidence(evidence_dir: Path, role: str) -> Path | None:
    if not evidence_dir.exists():
        return None
    for candidate in node_smoke_evidence_candidates(evidence_dir, role):
        if candidate.is_file():
            return candidate
    return None


def node_smoke_evidence_candidates(evidence_dir: Path, role: str) -> list[Path]:
    candidates = [
        evidence_dir / f"{role}-node-smoke.json",
        evidence_dir / f"{role}_node_smoke.json",
        evidence_dir / f"{role}.node-smoke.json",
        evidence_dir / role / "node-smoke.json",
    ]
    if evidence_dir.exists():
        candidates.extend(sorted(evidence_dir.glob(f"*{role}*smoke*.json")))
    deduped: list[Path] = []
    seen: set[str] = set()
    for candidate in candidates:
        key = str(candidate)
        if key not in seen:
            seen.add(key)
            deduped.append(candidate)
    return deduped


def producer_handoff_evidence_candidates(evidence_dir: Path, role: str) -> list[Path]:
    candidates = [
        evidence_dir / f"{role}-producer-handoff.json",
        evidence_dir / f"{role}_producer_handoff.json",
        evidence_dir / f"{role}.producer-handoff.json",
        evidence_dir / role / "producer-handoff.json",
    ]
    if evidence_dir.exists():
        candidates.extend(sorted(evidence_dir.glob(f"*{role}*producer*handoff*.json")))
    deduped: list[Path] = []
    seen: set[str] = set()
    for candidate in candidates:
        key = str(candidate)
        if key not in seen:
            seen.add(key)
            deduped.append(candidate)
    return deduped


def find_producer_handoff_evidence(search_dirs: list[Path], role: str) -> Path | None:
    for evidence_dir in search_dirs:
        if not evidence_dir.exists():
            continue
        for candidate in producer_handoff_evidence_candidates(evidence_dir, role):
            if candidate.is_file():
                return candidate
    return None


def clear_node_smoke_evidence(evidence_dir: Path, roles: list[str]) -> int:
    cleared = 0
    if not evidence_dir.exists():
        return cleared
    for role in roles:
        for candidate in node_smoke_evidence_candidates(evidence_dir, role):
            try:
                if candidate.is_file():
                    candidate.unlink()
                    cleared += 1
            except OSError:
                continue
    return cleared


def clear_producer_handoff_evidence(evidence_dir: Path, roles: list[str]) -> int:
    cleared = 0
    if not evidence_dir.exists():
        return cleared
    for role in roles:
        for candidate in producer_handoff_evidence_candidates(evidence_dir, role):
            try:
                if candidate.is_file():
                    candidate.unlink()
                    cleared += 1
            except OSError:
                continue
    return cleared


def validate_node_smoke_evidence(
    data: Any,
    role: str,
    expected_source_root_hash: str = "",
    expected_query_hash: str = "",
) -> dict[str, Any]:
    failures: list[str] = []
    if not isinstance(data, dict):
        return {"valid": False, "stepCount": 0, "restoreBlocked": False, "failReason": "not-object"}
    if data.get("schemaVersion") != "awx.mcp.node_smoke.v1":
        failures.append("schema")
    if data.get("nodeRole") != role:
        failures.append("node-role")
    if data.get("ok") is not True:
        failures.append("not-ok")
    if int(data.get("rawSecretPatternHits", 0) or 0) != 0:
        failures.append("secret-leak-risk")
    if expected_query_hash:
        actual_query_hash = safe_scalar(data.get("queryHash", ""), 120)
        if not actual_query_hash:
            failures.append("query-hash-missing")
        elif actual_query_hash != expected_query_hash:
            failures.append("query-mismatch")
    if role in {"macmini", "notebook", "read-only"}:
        isolation = data.get("sourceIsolation") if isinstance(data.get("sourceIsolation"), dict) else {}
        if not isolation:
            failures.append("source-isolation-missing")
        else:
            git_root_ok = (
                isolation.get("gitRootPresent") is True
                and isolation.get("gitRootMatchesSourceRoot") is True
                and bool(safe_scalar(isolation.get("gitRootHash", ""), 120))
            )
            if not git_root_ok:
                failures.append("git-root-missing")
            if not (
                isolation.get("guard") == "PASS"
                and isolation.get("sourceRootKind") == "local-worktree"
                and isolation.get("sharedSourceRoot") is False
                and isolation.get("desktopCanonicalSourceRoot") is False
                and isolation.get("directCanonicalSourceEdit") is False
                and safe_scalar(data.get("rootHash", ""), 120)
                and safe_scalar(data.get("canonicalRootHash", ""), 120)
                and data.get("rootHash") != data.get("canonicalRootHash")
            ):
                failures.append("source-isolation-violation")
        if expected_source_root_hash:
            actual_source_root_hash = safe_scalar(data.get("sourceRootInputHash", ""), 120)
            if not actual_source_root_hash:
                failures.append("source-root-hash-missing")
            elif actual_source_root_hash != expected_source_root_hash:
                failures.append("source-root-mismatch")

    steps = data.get("steps")
    if not isinstance(steps, list):
        steps = []
    step_tools = {
        safe_scalar(step.get("toolName", ""), 80)
        for step in steps
        if isinstance(step, dict)
    }
    missing_tools = sorted(REQUIRED_NODE_SMOKE_TOOLS - step_tools)
    if missing_tools:
        failures.append("missing-tools:" + ",".join(missing_tools))
    unallowlisted_step_count = sum(
        1
        for step in steps
        if isinstance(step, dict) and bool(set(step) - NODE_SMOKE_STEP_FIELDS)
    )
    if unallowlisted_step_count:
        failures.append(f"unallowlisted-step-fields:count={unallowlisted_step_count}")
    terminal_steps = [step for step in steps if node_smoke_step_has_terminal_outcome(step)]
    invalid_step_count = len(steps) - len(terminal_steps)
    if invalid_step_count:
        failures.append(f"invalid-step-shape:count={invalid_step_count}")
    terminal_tools = {safe_scalar(step.get("toolName", ""), 80) for step in terminal_steps}
    missing_terminal_tools = sorted(REQUIRED_NODE_SMOKE_TOOLS - terminal_tools)
    if missing_terminal_tools:
        failures.append("missing-terminal-outcome:" + ",".join(missing_terminal_tools))
    for tool_name, allowed_decisions in NODE_SMOKE_DECISION_ALLOWLIST.items():
        matching_steps = [
            step
            for step in terminal_steps
            if safe_scalar(step.get("toolName", ""), 80) == tool_name
        ]
        if matching_steps and not any(
            safe_scalar(step.get("decision", ""), 120) in allowed_decisions
            for step in matching_steps
        ):
            failures.append(tool_name.replace("_", "-") + "-decision")
    for tool_name, fallback_decisions in NODE_SMOKE_FALLBACK_DECISIONS.items():
        fallback_steps = [
            step
            for step in terminal_steps
            if (
                safe_scalar(step.get("toolName", ""), 80) == tool_name
                and safe_scalar(step.get("decision", ""), 120) in fallback_decisions
            )
        ]
        if fallback_steps and not any(step.get("localFallbackPresent") is True for step in fallback_steps):
            failures.append(tool_name.replace("_", "-") + "-local-fallback")

    restore_blocked = any(
        step.get("toolName") == "archive_restore"
        and step.get("decision") == "restore_target_blocked"
        and step.get("failReason") == "smb-conflict-risk"
        for step in terminal_steps
    )
    if role in {"macmini", "notebook", "read-only"} and not restore_blocked:
        failures.append("restore-block-missing")

    return {
        "valid": not failures,
        "stepCount": len(steps),
        "restoreBlocked": restore_blocked,
        "failReason": ",".join(failures),
    }


def node_smoke_step_has_terminal_outcome(step: Any) -> bool:
    if not isinstance(step, dict) or set(step) != NODE_SMOKE_STEP_FIELDS:
        return False
    tool_name = step.get("toolName")
    decision = step.get("decision")
    fail_reason = step.get("failReason")
    evidence_needed = step.get("evidence_needed")
    exit_code = step.get("exitCode")
    output_count = step.get("outputCount")
    elapsed_ms = step.get("elapsedMs")
    return (
        isinstance(tool_name, str)
        and tool_name in REQUIRED_NODE_SMOKE_TOOLS
        and isinstance(step.get("ok"), bool)
        and isinstance(step.get("localFallbackPresent"), bool)
        and isinstance(decision, str)
        and 0 < len(decision) <= 120
        and isinstance(fail_reason, str)
        and len(fail_reason) <= 120
        and isinstance(evidence_needed, str)
        and len(evidence_needed) <= 2000
        and isinstance(exit_code, int)
        and not isinstance(exit_code, bool)
        and -(2**31) <= exit_code < 2**31
        and isinstance(output_count, int)
        and not isinstance(output_count, bool)
        and 0 <= output_count <= 1_000_000
        and isinstance(elapsed_ms, int)
        and not isinstance(elapsed_ms, bool)
        and 0 <= elapsed_ms <= 86_400_000
    )


def validate_producer_handoff_evidence(
    data: Any,
    role: str,
    topic: str,
    expected_source_root_hash: str = "",
    expected_patch_hash: str = "",
    expected_producer_command_hash: str = "",
) -> dict[str, Any]:
    failures: list[str] = []
    if not isinstance(data, dict):
        return {
            "valid": False,
            "promotionReady": False,
            "diffHeaderCount": 0,
            "patchHash": "",
            "producerCommandHash": "",
            "desktopFinalProof": "",
            "bundleDesktopFinalProof": "",
            "failReason": "not-object",
        }
    if data.get("schemaVersion") != "awx.mcp.producer_handoff.v1":
        failures.append("schema")
    if data.get("nodeRole") != role:
        failures.append("node-role")
    if data.get("ok") is not True:
        failures.append("not-ok")
    if safe_scalar(data.get("topic", ""), 160) != topic:
        failures.append("topic")
    if int(data.get("rawSecretPatternHits", 0) or 0) != 0:
        failures.append("secret-leak-risk")
    if expected_source_root_hash:
        actual_source_root_hash = safe_scalar(
            data.get("sourceRootInputHash", "") or data.get("sourceRootHash", ""),
            120,
        )
        if not actual_source_root_hash:
            failures.append("producer-source-root-hash-missing")
        elif actual_source_root_hash != expected_source_root_hash:
            failures.append("producer-source-root-mismatch")
    producer_command_hash = safe_scalar(data.get("producerCommandHash", ""), 120).lower()
    if expected_producer_command_hash:
        if not producer_command_hash:
            failures.append("producer-command-hash-missing")
        elif producer_command_hash != expected_producer_command_hash.lower():
            failures.append("producer-command-hash-mismatch")
    if data.get("desktopFinalProof") != "evidence_needed":
        failures.append("desktop-final-proof")
    if safe_scalar(data.get("failReason", ""), 240):
        failures.append("fail-reason")
    bundle = data.get("bundle") if isinstance(data.get("bundle"), dict) else {}
    promotion_ready = bundle.get("promotionReady") is True
    if not promotion_ready:
        failures.append("promotion-ready")
    if bundle.get("desktopFinalProof") != "evidence_needed":
        failures.append("bundle-desktop-final-proof")
    if bundle.get("sidecarsComplete") is not True:
        failures.append("sidecars")
    diff_header_count = safe_int(bundle.get("diffHeaderCount", 0))
    if diff_header_count <= 0:
        failures.append("diff-header-count")
    patch_hash = safe_scalar(bundle.get("patchHash", ""), 80).lower()
    if not re.fullmatch(r"[a-f0-9]{64}", patch_hash):
        failures.append("patch-hash")
    elif expected_patch_hash and patch_hash != expected_patch_hash.lower():
        failures.append("patch-hash-mismatch")
    if safe_scalar(bundle.get("failReason", ""), 240):
        failures.append("bundle-fail-reason")
    isolation = bundle.get("sourceIsolation") if isinstance(bundle.get("sourceIsolation"), dict) else {}
    git_root_ok = (
        isolation.get("gitRootPresent") is True
        and isolation.get("gitRootMatchesSourceRoot") is True
        and bool(safe_scalar(isolation.get("gitRootHash", ""), 120))
    )
    if not git_root_ok:
        failures.append("producer-git-root-missing")
    return {
        "valid": not failures,
        "promotionReady": promotion_ready,
        "diffHeaderCount": diff_header_count,
        "patchHash": patch_hash,
        "producerCommandHash": producer_command_hash,
        "desktopFinalProof": safe_scalar(data.get("desktopFinalProof", ""), 80),
        "bundleDesktopFinalProof": safe_scalar(bundle.get("desktopFinalProof", ""), 80),
        "failReason": ",".join(failures),
    }


def is_shared_source_path(raw_path: str) -> bool:
    stripped = raw_path.strip()
    if stripped.startswith("\\\\"):
        return True
    normalized = stripped.replace("\\", "/").lower()
    parts = [part for part in normalized.split("/") if part]
    if "patchdrop" in parts or "__patch_drop__" in parts:
        return True
    return normalized.startswith(("/volumes/", "/mnt/", "/media/"))


def host_path_for_role(raw_path: str, role: str) -> str:
    value = raw_path.strip()
    if role == "macmini":
        return value.replace("\\", "/")
    return value


def host_path_join(root: str, *parts: str) -> str:
    base = root.strip()
    if not base:
        return host_path_join(".", *parts)
    sep = "\\" if "\\" in base and "/" not in base else "/"
    out = base.rstrip("/\\")
    for part in parts:
        clean = part.strip("/\\")
        if clean:
            out = f"{out}{sep}{clean}"
    return out


def sh_quote(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def ps_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def producer_required_file_preflight(required_files: list[str], role: str) -> str:
    if role == "macmini":
        quoted = " ".join(sh_quote(path) for path in required_files)
        return (
            f"for RequiredFile in {quoted}; do "
            '[ -f "$RequiredFile" ] || { echo "[AWX][producer] evidence_needed: required tool file missing: $RequiredFile" >&2; exit 1; }; '
            "done"
        )

    items = ",\n  ".join(ps_quote(path) for path in required_files)
    return (
        "$RequiredFiles = @(\n"
        f"  {items}\n"
        ")\n"
        "foreach ($RequiredFile in $RequiredFiles) { "
        'if (-not (Test-Path -LiteralPath $RequiredFile -PathType Leaf)) { Write-Error "[AWX][producer] evidence_needed: required tool file missing: $RequiredFile"; exit 1 } '
        "}"
    )


def producer_git_root_preflight(source_root: str, role: str) -> str:
    if role == "macmini":
        return (
            f"ProducerRoot={sh_quote(source_root)}\n"
            'GitRoot="$(git -C "$ProducerRoot" rev-parse --show-toplevel 2>/dev/null)" || { echo "[AWX][producer] producer-git-root-invalid: $ProducerRoot" >&2; exit 1; }\n'
            'if [ "$GitRoot" != "$ProducerRoot" ]; then echo "[AWX][producer] producer-git-root-invalid: gitRoot=$GitRoot producerRoot=$ProducerRoot" >&2; exit 1; fi'
        )

    return (
        f"$ProducerRoot = {ps_quote(source_root)}\n"
        "$GitRoot = git -C $ProducerRoot rev-parse --show-toplevel 2>$null\n"
        "$GitRootExit = $LASTEXITCODE\n"
        'if ($GitRootExit -ne 0 -or [string]::IsNullOrWhiteSpace($GitRoot)) { Write-Error "[AWX][producer] producer-git-root-invalid: $ProducerRoot"; exit 1 }' "\n"
        "$ResolvedProducerRoot = (Resolve-Path -LiteralPath $ProducerRoot).Path.TrimEnd('\\')\n"
        "$ResolvedGitRoot = (Resolve-Path -LiteralPath $GitRoot).Path.TrimEnd('\\')\n"
        'if (-not $ResolvedGitRoot.Equals($ResolvedProducerRoot, [System.StringComparison]::OrdinalIgnoreCase)) { Write-Error "[AWX][producer] producer-git-root-invalid: gitRoot=$ResolvedGitRoot producerRoot=$ResolvedProducerRoot"; exit 1 }'
    )


def producer_kit_bootstrap_command(
    install_script: str,
    source_root: str,
    required_files: list[str],
    role: str,
    expected_manifest_hash: str = "",
) -> str:
    expected_hash = safe_scalar(expected_manifest_hash, 80).lower()
    if role == "macmini":
        manifest_guard = ""
        if expected_hash:
            manifest_guard = (
                'KitManifest="$(dirname "$KitInstall")/producer-kit.manifest.json"; '
                '[ -f "$KitManifest" ] || { echo "[AWX][producer] producer-kit-manifest-missing: $KitManifest" >&2; exit 1; }; '
                "KitManifestHash=$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],\"rb\").read()).hexdigest())' \"$KitManifest\"); "
                f'[ "$KitManifestHash" = "{expected_hash}" ] || {{ echo "[AWX][producer] producer-kit-manifest-sha-mismatch: $KitManifest" >&2; exit 1; }}; '
            )
        return (
            f"KitInstall={sh_quote(install_script)}; "
            '[ -f "$KitInstall" ] || { echo "[AWX][producer] producer-kit-installer-missing: $KitInstall" >&2; exit 1; }; '
            f"{manifest_guard}"
            f"bash \"$KitInstall\" {sh_quote(source_root)} {sh_quote(role)}"
        )

    manifest_guard = ""
    if expected_hash:
        manifest_guard = (
            "$KitManifest = Join-Path (Split-Path -Parent $KitInstall) \"producer-kit.manifest.json\"\n"
            "if (-not (Test-Path -LiteralPath $KitManifest -PathType Leaf)) { Write-Error \"[AWX][producer] producer-kit-manifest-missing: $KitManifest\"; exit 1 }\n"
            "$KitManifestHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $KitManifest).Hash.ToLowerInvariant()\n"
            f"if ($KitManifestHash -ne {ps_quote(expected_hash)}) {{ Write-Error \"[AWX][producer] producer-kit-manifest-sha-mismatch: $KitManifest\"; exit 1 }}\n"
        )
    return (
        f"$KitInstall = {ps_quote(install_script)}\n"
        'if (-not (Test-Path -LiteralPath $KitInstall -PathType Leaf)) { Write-Error "[AWX][producer] producer-kit-installer-missing: $KitInstall"; exit 1 }'
        "\n"
        f"{manifest_guard}"
        f"powershell -NoProfile -ExecutionPolicy Bypass -File $KitInstall -ProducerRoot {ps_quote(source_root)} -NodeRole {ps_quote(role)}"
    )


def setup_config_path(source_root: str, role: str) -> str:
    # The receiving host resolves its local state directory at execution time.
    return ""


def setup_audit_log_path(source_root: str, role: str) -> str:
    if role == "macmini":
        return source_root.rstrip("/\\").replace("\\", "/") + "/.codex/awx-control-tower.audit.jsonl"
    return str(Path(source_root) / ".codex" / "awx-control-tower.audit.jsonl")


def producer_setup_command(
    python_cmd: str,
    setup_script: str,
    source_root: str,
    canonical_root: str,
    output_path: str,
    node_role: str,
    quote_fn=sh_quote,
    audit_log_path: str = "",
) -> str:
    parts = [
        python_cmd,
        "-B",
        quote_fn(setup_script),
        "--node-role",
        quote_fn(node_role),
        "--source-root",
        quote_fn(source_root),
        "--canonical-root",
        quote_fn(canonical_root),
    ]
    if output_path:
        parts.extend(["--output",quote_fn(output_path)])
    if audit_log_path:
        parts.extend(["--audit-log", quote_fn(audit_log_path)])
    return " ".join(parts)


def producer_handoff_command(
    python_cmd: str,
    handoff_script: str,
    source_root: str,
    canonical_root: str,
    patchdrop_root: str,
    producer_script: str,
    node_role: str,
    topic: str,
    pathspecs: list[str],
    quote_fn=sh_quote,
    audit_log_path: str = "",
    producer_command_hash_expr: str = "",
) -> str:
    parts = [
        python_cmd,
        quote_fn(handoff_script),
        "--source-root",
        quote_fn(source_root),
        "--canonical-root",
        quote_fn(canonical_root),
        "--patchdrop-root",
        quote_fn(patchdrop_root),
        "--producer-script",
        quote_fn(producer_script),
        "--node-role",
        quote_fn(node_role),
        "--topic",
        quote_fn(topic),
    ]
    for pathspec in pathspecs:
        parts.extend(["--pathspec", quote_fn(pathspec)])
    if audit_log_path:
        parts.extend(["--audit-log", quote_fn(audit_log_path)])
    if producer_command_hash_expr:
        parts.extend(["--producer-command-hash", producer_command_hash_expr])
    return " ".join(parts)


def rank_rows(rows: list[dict[str, Any]], query: str, filters: dict[str, Any]) -> list[dict[str, Any]]:
    q_tokens = [token.lower() for token in re.findall(r"[A-Za-z0-9_.-]+", query)]
    out: list[dict[str, Any]] = []
    for row in rows:
        if not filters_match(row, filters):
            continue
        haystack = row_text(row).lower()
        score = sum(1 for token in q_tokens if token and token in haystack)
        if score > 0 or not q_tokens:
            clone = dict(row)
            clone["score"] = score
            out.append(clone)
    return sorted(out, key=lambda item: (-int(item.get("score", 0)), str(item.get("path", ""))))


def filters_match(row: dict[str, Any], filters: dict[str, Any]) -> bool:
    for key, expected in filters.items():
        actual = row.get(key)
        if isinstance(actual, list):
            actual_values = {str(item).lower() for item in actual}
        else:
            actual_values = {str(actual).lower()}
        expected_values = {str(item).lower() for item in expected} if isinstance(expected, list) else {str(expected).lower()}
        if actual_values.isdisjoint(expected_values):
            return False
    return True


def row_text(row: dict[str, Any]) -> str:
    parts: list[str] = []
    for key in ("id", "path", "title", "summary", "tags", "labels", "class", "kind"):
        value = row.get(key)
        if isinstance(value, list):
            parts.extend(str(item) for item in value)
        elif value is not None:
            parts.append(str(value))
    return " ".join(parts)


def result_row(row: dict[str, Any]) -> dict[str, Any]:
    path = safe_scalar(row.get("path") or row.get("id") or "", 240)
    title = safe_scalar(row.get("title") or "", 160)
    return {
        **redacted_path_fields(path),
        "title": title,
        "score": int(row.get("score", 0)),
        "rowHash": stable_hash(row)[:16],
    }


def candidate_row(root: Path, path: Path) -> dict[str, Any]:
    rel = path.relative_to(root)
    return {**redacted_path_fields(rel), "sha256": sha256_file(path), "sizeBytes": path_size(path)}


def append_audit(path: Path, result: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    row = {key: result.get(key, "") for key in SAFE_AUDIT_FIELDS}
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(row, ensure_ascii=True, separators=(",", ":")) + "\n")


def append_verify_log(path: Path, result: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    row = {key: result.get(key, "") for key in SAFE_AUDIT_FIELDS}
    row.update({
        "preReview": result.get("preReview", {}),
        "restored": redacted_restore_rows(result.get("restored", [])),
        "skipped": result.get("skipped", []),
    })
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(redact(row), ensure_ascii=True, separators=(",", ":")) + "\n")


def redacted_restore_rows(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    rows: list[dict[str, Any]] = []
    allowed = (
        "pathHash",
        "pathLength",
        "pathName",
        "targetHash",
        "beforeSha256",
        "sha256",
        "checksumVerified",
        "sizeBytes",
    )
    for item in value:
        if isinstance(item, dict):
            rows.append({key: item.get(key, "") for key in allowed if key in item})
    return rows


def redacted_path_fields(path: Path | str) -> dict[str, Any]:
    raw = path.as_posix() if isinstance(path, Path) else str(path or "")
    return {
        "pathHash": stable_hash(raw),
        "pathLength": len(raw),
        "pathName": safe_path_name(raw),
    }


def redacted_path_text(path: Path | str) -> str:
    raw = str(path or "")
    return f"pathHash={stable_hash(raw)} ; pathLength={len(raw)}"


def is_redacted_path_text(value: object) -> bool:
    text = str(value or "").strip()
    return text.startswith("pathHash=") and "pathLength=" in text


def safe_path_name(path: str) -> str:
    if not path:
        return ""
    name = re.split(r"[\\/]+", path.rstrip("\\/"))[-1]
    return safe_scalar(name, 120)


def output_count(result: dict[str, Any]) -> int:
    for key in ("outputCount", "results", "restored", "candidates"):
        value = result.get(key)
        if isinstance(value, int):
            return value
        if isinstance(value, list):
            return len(value)
    return 0


def stable_hash(value: Any) -> str:
    if not isinstance(value, str):
        value = json.dumps(value, ensure_ascii=True, sort_keys=True, default=str)
    return hashlib.sha256(value.encode("utf-8", errors="ignore")).hexdigest()


def path_exists(path: Path) -> bool:
    try:
        return path.exists()
    except OSError:
        return False


def path_is_file(path: Path) -> bool:
    try:
        return path.is_file()
    except OSError:
        return False


def path_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except OSError:
        return 0


def sha256_file(path: Path) -> str:
    if not path_exists(path):
        return ""
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        return ""
    return digest.hexdigest()


def resolve_path(value: Any) -> Path:
    text = str(value)
    return Path(text).expanduser().resolve()


def safe_scalar(value: Any, limit: int) -> str:
    return safe_message("" if value is None else str(value), limit)


def safe_int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def safe_message(value: str, limit: int) -> str:
    text = redact_string(value)
    return text if len(text) <= limit else text[: max(0, limit - 3)] + "..."


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for key, item in value.items():
            key_text = str(key)
            if redaction_safe_count_field(key_text, item):
                out[key_text] = item
            elif key_text in {"secretEnvRefs", "apikeyEnvRefs"} and isinstance(item, list):
                # These fields contain public names, never credential values.
                out[key_text] = [ref if isinstance(ref, str) and ref in ENV_REFS
                                 else "<redacted>" for ref in item]
            elif isinstance(item, bool):
                out[key_text] = item
            elif re.search(r"(?i)(secret|password|token|api.?key|authorization|cookie)", key_text):
                out[key_text] = "<redacted>"
            else:
                out[key_text] = redact(item)
        return out
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, str):
        return redact_string(value)
    return value


def redaction_safe_count_field(key: str, value: Any) -> bool:
    return key in {"rawSecretPatternHits", "secretPatternHits"} and isinstance(value, int)


def redact_string(value: str) -> str:
    out = value
    for pattern in REDACTION_PATTERNS:
        out = pattern.sub("<redacted>", out)
    return out


def bounded_int(value: Any, fallback: int, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = fallback
    return max(minimum, min(maximum, parsed))


def slug(value: Any) -> str:
    text = re.sub(r"[^A-Za-z0-9._-]+", "-", str(value).strip().lower()).strip("-")
    return text or "tool"


def role_decision(node_role: str) -> str:
    role = node_role.lower()
    if "desktop" in role:
        return "desktop_source_owner_final_verifier"
    if "notebook" in role:
        return "notebook_supporting_probe_diff_review"
    if "mac" in role:
        return "macmini_read_only_investigator_patch_producer"
    return "read_only_investigator"


def gradle_cache_hint(node_role: str) -> str:
    role = "desktop" if "desktop" in node_role.lower() else ("macmini" if "mac" in node_role.lower() else "notebook")
    return f"$Env:USERPROFILE\\.awx-gradle-project-cache\\{role}"


def has_unsafe_glob(pattern: str) -> bool:
    return ".." in Path(pattern).parts or pattern.startswith("/") or re.match(r"^[A-Za-z]:", pattern) is not None


def filemode_line_count(path: Path) -> int:
    return patch_mode_summary(path)["filemodeLineCount"]


def empty_patch_mode_summary() -> dict[str, int]:
    return {
        "filemodeLineCount": 0,
        "allowedNewFileCount": 0,
        "filemodeViolationCount": 0,
    }


def patch_mode_summary(path: Path) -> dict[str, int]:
    return patch_mode_summary_text(path.read_text(encoding="utf-8", errors="ignore"))


def patch_mode_summary_text(patch_text: str) -> dict[str, int]:
    text = patch_text.lstrip("\ufeff")
    blocks: list[list[str]] = []
    preamble: list[str] = []
    current: list[str] | None = None
    for line in text.splitlines():
        if line.startswith("diff --git "):
            if current is not None:
                blocks.append(current)
            current = [line]
        elif current is None:
            preamble.append(line)
        else:
            current.append(line)
    if current is not None:
        blocks.append(current)

    mode_header = re.compile(r"^(old mode|new mode|deleted file mode|new file mode)(?:\s+.*)?$")
    preamble_modes = [line for line in preamble if mode_header.match(line)]
    total = len(preamble_modes)
    allowed = 0
    violations = len(preamble_modes)
    seen_targets: set[str] = set()
    for block in blocks:
        mode_lines = [line for line in block if mode_header.match(line)]
        total += len(mode_lines)
        envelope = patch_envelope_lines(block)
        has_old_null = "--- /dev/null" in envelope
        has_new_null = "+++ /dev/null" in envelope
        if has_new_null or (has_old_null and not mode_lines):
            violations += max(1, len(mode_lines))
            continue
        if not mode_lines:
            continue
        header = re.fullmatch(r'diff --git a/([^\s"\\]+) b/([^\s"\\]+)', block[0]) if block else None
        target = header.group(1) if header is not None and header.group(1) == header.group(2) else ""
        if is_canonical_new_file_block(block, mode_lines) and target not in seen_targets:
            seen_targets.add(target)
            allowed += 1
        else:
            violations += len(mode_lines)
    return {
        "filemodeLineCount": total,
        "allowedNewFileCount": allowed,
        "filemodeViolationCount": violations,
    }


def patch_envelope_lines(lines: list[str]) -> list[str]:
    first_hunk = next((index for index, line in enumerate(lines) if line.startswith("@@ ")), len(lines))
    return lines[:first_hunk]


def is_canonical_repo_relative_patch_path(value: str) -> bool:
    if not value or value != value.replace("\\", "/"):
        return False
    if value.startswith(("/", "//")) or re.match(r"^[A-Za-z]:", value):
        return False
    if re.search(r"[\x00-\x20\x7f\"<>:|?*]", value):
        return False
    return all(
        part not in {"", ".", ".."}
        and not part.endswith((".", " "))
        and re.match(r"^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)", part, re.IGNORECASE) is None
        for part in value.split("/")
    )


def is_canonical_new_file_block(block: list[str], mode_lines: list[str]) -> bool:
    if not block or mode_lines != ["new file mode 100644"]:
        return False
    header = re.fullmatch(r'diff --git a/([^\s"\\]+) b/([^\s"\\]+)', block[0])
    if header is None or header.group(1) != header.group(2):
        return False
    target = header.group(1)
    if not is_canonical_repo_relative_patch_path(target):
        return False
    if any(pattern.search(target) for pattern, _ in FORBIDDEN_PATCH_PATH_PATTERNS):
        return False
    envelope = patch_envelope_lines(block)
    old_headers = [line for line in envelope if line.startswith("--- ")]
    new_headers = [line for line in envelope if line.startswith("+++ ")]
    if old_headers != ["--- /dev/null"] or new_headers != [f"+++ b/{target}"]:
        return False
    if not has_canonical_new_file_hunk(block):
        return False
    return not any(
        line.startswith(("rename from ", "rename to ", "copy from ", "copy to "))
        or line == "GIT binary patch"
        or (line.startswith("Binary files ") and line.endswith(" differ"))
        for line in block
    )


def has_exact_hunk_sequence(lines: list[str], *, require_new_file: bool) -> bool:
    first_hunk = next((index for index, line in enumerate(lines) if line.startswith("@@ ")), -1)
    if first_hunk < 0:
        return False
    envelope = lines[:first_hunk]
    old_indexes = [index for index, line in enumerate(envelope) if line.startswith("--- ")]
    new_indexes = [index for index, line in enumerate(envelope) if line.startswith("+++ ")]
    if len(old_indexes) != 1 or len(new_indexes) != 1 or new_indexes[0] != old_indexes[0] + 1:
        return False
    if first_hunk != new_indexes[0] + 1:
        return False
    index = first_hunk
    hunk_count = 0
    header_pattern = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(?: .*)?$")
    while index < len(lines):
        header = header_pattern.fullmatch(lines[index])
        if header is None:
            return False
        old_start = int(header.group(1))
        old_expected = int(header.group(2)) if header.group(2) is not None else 1
        new_start = int(header.group(3))
        new_expected = int(header.group(4)) if header.group(4) is not None else 1
        if require_new_file and (hunk_count != 0 or old_start != 0 or old_expected != 0 or new_start != 1 or new_expected <= 0):
            return False
        hunk_count += 1
        index += 1
        old_actual = 0
        new_actual = 0
        old_eof_marked = False
        new_eof_marked = False
        while index < len(lines) and not lines[index].startswith("@@ "):
            line = lines[index]
            if line == r"\ No newline at end of file":
                if index == 0 or not lines[index - 1].startswith((" ", "+", "-")):
                    return False
                if any(candidate.startswith("@@ ") for candidate in lines[index + 1 :]):
                    return False
                previous_kind = lines[index - 1][0]
                if previous_kind == "-":
                    if old_eof_marked or new_eof_marked:
                        return False
                    old_eof_marked = True
                elif previous_kind == "+":
                    if new_eof_marked:
                        return False
                    new_eof_marked = True
                else:
                    if old_eof_marked or new_eof_marked:
                        return False
                    old_eof_marked = True
                    new_eof_marked = True
                index += 1
                continue
            if not line or line[0] not in {" ", "+", "-"}:
                return False
            if new_eof_marked or (old_eof_marked and line[0] != "+"):
                return False
            if require_new_file and line[0] != "+":
                return False
            if line[0] in {" ", "-"}:
                old_actual += 1
            if line[0] in {" ", "+"}:
                new_actual += 1
            if old_actual > old_expected or new_actual > new_expected:
                return False
            index += 1
        if old_actual != old_expected or new_actual != new_expected:
            return False
    return hunk_count == 1 if require_new_file else hunk_count > 0


def has_canonical_new_file_hunk(lines: list[str]) -> bool:
    return has_exact_hunk_sequence(lines, require_new_file=True)


def patch_structure_violation_count_text(patch_text: str) -> int:
    text = patch_text.lstrip("\ufeff")
    blocks: list[list[str]] = []
    preamble: list[str] = []
    current: list[str] | None = None
    for line in text.splitlines():
        if line.startswith("diff --git "):
            if current is not None:
                blocks.append(current)
            current = [line]
        elif current is None:
            preamble.append(line)
        else:
            current.append(line)
    if current is not None:
        blocks.append(current)
    violations = 1 if any(line.strip() for line in preamble) or not blocks else 0
    seen_targets: set[str] = set()
    for block in blocks:
        header = re.fullmatch(r'diff --git a/([^\s"\\]+) b/([^\s"\\]+)', block[0])
        if header is None or header.group(1) != header.group(2):
            violations += 1
            continue
        target = header.group(1)
        if not is_canonical_repo_relative_patch_path(target) or target in seen_targets:
            violations += 1
            continue
        seen_targets.add(target)
        envelope = patch_envelope_lines(block)
        old_headers = [line for line in envelope if line.startswith("--- ")]
        new_headers = [line for line in envelope if line.startswith("+++ ")]
        mode_lines = [
            line
            for line in block
            if re.match(r"^(old mode|new mode|deleted file mode|new file mode)(?:\s+.*)?$", line)
        ]
        if old_headers == ["--- /dev/null"]:
            if not is_canonical_new_file_block(block, mode_lines):
                violations += 1
        elif old_headers != [f"--- a/{target}"] or new_headers != [f"+++ b/{target}"]:
            violations += 1
        elif not has_exact_hunk_sequence(block, require_new_file=False):
            violations += 1
    return violations


def binary_patch_marker_count(path: Path) -> int:
    text = path.read_text(encoding="utf-8", errors="ignore")
    return len(re.findall(r"(?m)^(GIT binary patch|Binary files .+ differ)$", text))


def diff_header_count(path: Path) -> int:
    text = path.read_text(encoding="utf-8", errors="ignore").lstrip("\ufeff")
    return len(re.findall(r"(?m)^diff --git ", text))


FORBIDDEN_PATCH_PATH_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"(^|/)(apikey\.txt|apikey\.ps1)$", re.IGNORECASE), "secret-setup"),
    (re.compile(r"(^|/)\.env[^/]*(?:/|$)", re.IGNORECASE), "secret-env"),
    (re.compile(r"(^|/)pages/api/", re.IGNORECASE), "nextjs-pages-api"),
    (re.compile(r"(^|/)(\.gradle|build|node_modules|\.next|\.turbo|\.swc)(/|$)", re.IGNORECASE), "shared-cache-build-output"),
    (re.compile(r"\.(p12|jks)$", re.IGNORECASE), "keystore"),
)


def normalize_patch_path(value: str) -> str:
    path = value.strip().strip('"').replace("\\", "/")
    if path in {"", "/dev/null"}:
        return ""
    if path.startswith("a/") or path.startswith("b/"):
        path = path[2:]
    return path


def patch_target_paths(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8", errors="ignore").lstrip("\ufeff")
    paths: list[str] = []
    seen: set[str] = set()
    hunk_started = False
    for line in text.splitlines():
        candidates: list[str] = []
        if line.startswith("diff --git "):
            hunk_started = False
            parts = line.split()
            candidates.extend(parts[2:4])
        elif line.startswith("@@ "):
            hunk_started = True
        elif not hunk_started and (line.startswith("+++ ") or line.startswith("--- ")):
            candidates.append(line[4:].split("\t", 1)[0])
        elif line.startswith("rename from ") or line.startswith("rename to "):
            candidates.append(line.split(" ", 2)[2])
        elif line.startswith("copy from ") or line.startswith("copy to "):
            candidates.append(line.split(" ", 2)[2])
        for candidate in candidates:
            normalized = normalize_patch_path(candidate)
            if normalized and normalized not in seen:
                seen.add(normalized)
                paths.append(normalized)
    return paths


def is_unsafe_patch_target(target: str) -> bool:
    return not is_canonical_repo_relative_patch_path(target)


def forbidden_patch_paths(path: Path) -> list[dict[str, str]]:
    blocked: list[dict[str, str]] = []
    for target in patch_target_paths(path):
        if is_unsafe_patch_target(target):
            blocked.append({"path": target, "reason": "unsafe-path"})
            continue
        for pattern, reason in FORBIDDEN_PATCH_PATH_PATTERNS:
            if pattern.search(target):
                blocked.append({"path": target, "reason": reason})
                break
    return blocked


def count_re(text: str, pattern: str) -> int:
    return len(re.findall(pattern, text, flags=re.IGNORECASE))


if __name__ == "__main__":
    raise SystemExit(main())
