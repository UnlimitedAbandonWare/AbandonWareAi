#!/usr/bin/env python3
"""
db_gap_scanner.py — Dynamic RAG Orchestration Platform DB Structure Gap Scanner

Scans Java source files to quantify the gap between the design document
(8 subsystems with persistence requirements) and the actual implementation.

Usage:
    python scripts/db_gap_scanner.py --root main/java --output data/db-gap-report/
    python scripts/db_gap_scanner.py --root main/java --format json
    python scripts/db_gap_scanner.py --root main/java --format markdown

Output:
    - gap_matrix.json: machine-readable gap analysis
    - gap_matrix.md: human-readable gap report
    - entity_catalog.json: all @Entity classes with table mappings
    - repository_catalog.json: all JpaRepository interfaces
    - subsystem_persistence.json: per-subsystem persistence audit
    - duplicate_classes.json: simple-name duplicates across packages
"""

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional

HIGH_CONF_SECRET_PATTERNS = (
    re.compile(r"sk-[A-Za-z0-9_-]{20,}"),
    re.compile(r"AIza[0-9A-Za-z_-]{20,}"),
    re.compile(r"gsk_[A-Za-z0-9]{20,}"),
    re.compile(r"pcsk_[A-Za-z0-9_-]{20,}"),
    re.compile(r"sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}"),
    re.compile(r"sbp_[A-Za-z0-9_-]{10,}"),
)


def generated_at_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


ACTIVE_SOURCE_ROOTS = (
    Path("main/java"),
    Path("app/src/main/java_clean"),
)

# ─── Design-Defined Subsystem Specifications ───────────────────────────
SUBSYSTEM_SPECS = {
    "S01_OVERDRIVE": {
        "name": "Overdrive / Anchor-Based Context Compression",
        "design_persistence": "compression results, anchor hit rates",
        "required_classes": [
            "OverdriveGuard",
            "DynamicContextCompressor",
        ],
        "expected_entities": [],
        "expected_repositories": [],
        "indicator_patterns": [
            r"class\s+OverdriveGuard",
            r"class\s+DynamicContextCompressor",
        ],
        "volatile_indicators": [
            r"ThreadLocal",
            r"AtomicInteger",
            r"volatile\s+",
        ],
    },
    "S02_CFVM": {
        "name": "CFVM / Failure Pattern Analysis",
        "design_persistence": "9-tile Boltzmann matrix, JB/CB scores, failure pattern signatures, RawTile super-tokens",
        "required_classes": [
            "RawMatrixBuffer",
            "RawSlotExtractor",
            "CfvmFailureRecorder",
            "CfvmJbCbCalculator",
            "com.example.lms.strategy.RetrievalOrderService",
        ],
        "expected_entities": ["CfvmSnapshot"],
        "expected_repositories": ["CfvmSnapshotRepository"],
        "indicator_patterns": [
            r"class\s+RawMatrixBuffer",
            r"ArrayDeque",
            r"capacity\s*=\s*9",
            r"boltzmann",
        ],
        "volatile_indicators": [
            r"ArrayDeque",
            r"synchronized\s+",
            r"process.local",
            r"session.scoped?\s+",
        ],
    },
    "S03_MOE": {
        "name": "MoE Strategy Selector",
        "design_persistence": "plate weights, evolution history, A/B test results, ScoreCard snapshots",
        "required_classes": [
            "ArtPlateEvolver",
            "StrategyPerformance",
            "StrategySelectorService",
        ],
        "expected_entities": ["StrategyPerformance", "ArtPlateEvolutionLog"],
        "expected_repositories": ["StrategyPerformanceRepository", "ArtPlateEvolutionLogRepository"],
        "indicator_patterns": [
            r"class\s+ArtPlateEvolver",
            r"class\s+StrategyPerformance",
            r"@Entity",
        ],
        "volatile_indicators": [],
    },
    "S04_MATRYOSHKA": {
        "name": "Matryoshka Slicing",
        "design_persistence": "configuration only (no runtime state)",
        "required_classes": [
            "OllamaEmbeddingModel",
            "MatryoshkaEmbeddingNormalizer",
        ],
        "expected_entities": [],
        "expected_repositories": [],
        "indicator_patterns": [
            r"4096",
            r"1536",
            r"[Ss]licing",
            r"[Mm]atryoshka",
        ],
        "volatile_indicators": [],
    },
    "S05_EXTREMEZ": {
        "name": "ExtremeZ / Massive Parallel Query Expansion",
        "design_persistence": "trigger history, parallel query results cache",
        "required_classes": [
            "ExtremeZSystemHandler",
            "ExtremeZTrigger",
        ],
        "expected_entities": [],
        "expected_repositories": [],
        "indicator_patterns": [
            r"class\s+ExtremeZSystemHandler",
            r"class\s+ExtremeZTrigger",
        ],
        "volatile_indicators": [
            r"ThreadLocal",
            r"in-memory",
        ],
    },
    "S06_HYPERNOVA": {
        "name": "HYPERNOVA (TWPM + CVaR + Risk-K)",
        "design_persistence": "score history, TWPM parameters, CVaR alpha snapshots",
        "required_classes": [
            "TailWeightedPowerMeanFuser",
            "CvarAggregator",
            "RiskKAllocator",
            "DppDiversityReranker",
            "BodeClamp",
        ],
        "expected_entities": [],
        "expected_repositories": [],
        "indicator_patterns": [
            r"class\s+TailWeightedPowerMeanFuser",
            r"class\s+CvarAggregator",
            r"class\s+RiskKAllocator",
        ],
        "volatile_indicators": [],
    },
    "S07_CIH_RAG": {
        "name": "CIH-RAG / IQR / MLA Breadcrumb",
        "design_persistence": "MLA breadcrumb records, IQR iteration history, trace data",
        "required_classes": [
            "TraceStore",
            "DebugEventStore",
            "LoggingSseEventPublisher",
        ],
        "expected_entities": [],
        "expected_repositories": [],
        "indicator_patterns": [
            r"class\s+TraceStore",
            r"ThreadLocal",
            r"class\s+DebugEventStore",
        ],
        "volatile_indicators": [
            r"ThreadLocal",
            r"ConcurrentLinkedDeque",
            r"ring\s*=",
        ],
    },
    "S08_OPENAI_ADAPTER": {
        "name": "OpenAI Adapter / Version Purity",
        "design_persistence": "configuration only",
        "required_classes": [
            "VersionPurityHealthIndicator",
            "LlmConfig",
            "ProviderGuard",
        ],
        "expected_entities": [],
        "expected_repositories": [],
        "indicator_patterns": [
            r"1\.0\.1",
            r"langchain4j",
        ],
        "volatile_indicators": [],
    },
}


@dataclass
class EntityInfo:
    fqcn: str
    simple_name: str
    table_name: str
    file_path: str
    line_number: int
    column_count: int = 0
    has_id: bool = False
    id_strategy: str = ""


@dataclass
class RepositoryInfo:
    fqcn: str
    simple_name: str
    entity_type: str
    id_type: str
    file_path: str
    line_number: int


@dataclass
class ClassInfo:
    fqcn: str
    simple_name: str
    file_path: str
    package: str
    is_entity: bool = False
    is_repository: bool = False
    is_component: bool = False
    is_deprecated: bool = False
    has_tracestore_dep: bool = False
    volatile_storage: list = field(default_factory=list)


@dataclass
class SubsystemGap:
    id: str
    name: str
    design_persistence: str
    classes_found: list = field(default_factory=list)
    classes_missing: list = field(default_factory=list)
    entities_found: list = field(default_factory=list)
    entities_missing: list = field(default_factory=list)
    repositories_found: list = field(default_factory=list)
    repositories_missing: list = field(default_factory=list)
    volatile_patterns_found: list = field(default_factory=list)
    duplicate_classes: list = field(default_factory=list)
    has_db_persistence: bool = False
    persistence_type: str = "none"
    persistence_status: str = "unresolved"
    action_required: bool = True
    gap_severity: str = "LOW"
    gap_details: str = ""


def scan_java_file(file_path: Path, root: Path) -> Optional[ClassInfo]:
    """Parse a single Java file for entity/repository/component info."""
    try:
        text = file_path.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return None

    pkg_match = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.MULTILINE)
    cls_match = re.search(
        r"(?m)^\s*(?:public\s+)?(?:final\s+|abstract\s+)?"
        r"(?:class|interface|enum|record)\s+(\w+)",
        text,
    )
    if not pkg_match or not cls_match:
        return None

    package = pkg_match.group(1)
    simple_name = cls_match.group(1)
    fqcn = f"{package}.{simple_name}"
    rel = str(file_path.relative_to(root))

    info = ClassInfo(
        fqcn=fqcn,
        simple_name=simple_name,
        file_path=rel,
        package=package,
    )

    info.is_entity = bool(re.search(r"@Entity\b", text))
    info.is_repository = bool(re.search(r"extends\s+(?:Jpa|Crud|PagingAndSorting)Repository", text))
    info.is_component = bool(re.search(r"@(?:Component|Service|Repository|Configuration)\b", text))
    info.is_deprecated = bool(re.search(r"@Deprecated\b", text))
    info.has_tracestore_dep = bool(re.search(r"TraceStore\.", text))

    for pattern in [r"ThreadLocal", r"ArrayDeque", r"ConcurrentLinkedDeque",
                    r"ConcurrentHashMap", r"volatile\s+\w+", r"synchronized\s+"]:
        if re.search(pattern, text):
            info.volatile_storage.append(pattern.replace(r"\s+", " ").replace(r"\w+", ""))

    return info


def extract_entity_details(file_path: Path, root: Path) -> Optional[EntityInfo]:
    """Extract detailed @Entity information."""
    try:
        text = file_path.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return None

    if not re.search(r"@Entity\b", text):
        return None

    pkg_match = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.MULTILINE)
    cls_match = re.search(r"(?m)class\s+(\w+)", text)
    if not pkg_match or not cls_match:
        return None

    table_match = re.search(r'@Table\s*\(\s*name\s*=\s*"(\w+)"', text)
    table_name = table_match.group(1) if table_match else cls_match.group(1).lower()

    entity_line = 0
    for i, line in enumerate(text.splitlines(), 1):
        if "@Entity" in line:
            entity_line = i
            break

    columns = len(re.findall(r"@Column\b", text))
    has_id = bool(re.search(r"@Id\b", text))
    id_strat_match = re.search(r"@GeneratedValue\s*\(\s*strategy\s*=\s*GenerationType\.(\w+)", text)

    return EntityInfo(
        fqcn=f"{pkg_match.group(1)}.{cls_match.group(1)}",
        simple_name=cls_match.group(1),
        table_name=table_name,
        file_path=str(file_path.relative_to(root)),
        line_number=entity_line,
        column_count=columns,
        has_id=has_id,
        id_strategy=id_strat_match.group(1) if id_strat_match else "",
    )


def extract_repository_details(file_path: Path, root: Path) -> Optional[RepositoryInfo]:
    """Extract JpaRepository interface details."""
    try:
        text = file_path.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return None

    repo_match = re.search(
        r"extends\s+(?:Jpa|Crud|PagingAndSorting)Repository\s*<\s*(\w+)\s*,\s*(\w+)\s*>",
        text,
    )
    if not repo_match:
        return None

    pkg_match = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.MULTILINE)
    cls_match = re.search(r"interface\s+(\w+)", text)
    if not pkg_match or not cls_match:
        return None

    iface_line = 0
    for i, line in enumerate(text.splitlines(), 1):
        if "interface" in line and cls_match.group(1) in line:
            iface_line = i
            break

    return RepositoryInfo(
        fqcn=f"{pkg_match.group(1)}.{cls_match.group(1)}",
        simple_name=cls_match.group(1),
        entity_type=repo_match.group(1),
        id_type=repo_match.group(2),
        file_path=str(file_path.relative_to(root)),
        line_number=iface_line,
    )


def discover_java_files(root: Path) -> tuple[list[Path], list[str]]:
    """Return Java files from active source roots when a repo root is provided."""
    active_roots = [root / rel for rel in ACTIVE_SOURCE_ROOTS if (root / rel).is_dir()]
    if active_roots:
        java_files = [
            file_path
            for source_root in active_roots
            for file_path in source_root.rglob("*.java")
        ]
        return sorted(java_files), [path.relative_to(root).as_posix() for path in active_roots]
    return sorted(root.rglob("*.java")), [root.as_posix()]


def find_duplicates(classes: list[ClassInfo]) -> dict[str, list[str]]:
    """Find simple class names that appear in multiple packages."""
    by_name = defaultdict(list)
    for c in classes:
        by_name[c.simple_name].append(c.fqcn)
    return {name: fqcns for name, fqcns in by_name.items() if len(fqcns) > 1}


def class_matches_required(info: ClassInfo, required: str) -> bool:
    if info is None or not required:
        return False
    return info.simple_name == required or info.fqcn == required


def assess_subsystem(
    sid: str,
    spec: dict,
    classes: list[ClassInfo],
    entities: list[EntityInfo],
    repositories: list[RepositoryInfo],
    duplicates: dict[str, list[str]],
) -> SubsystemGap:
    """Assess a single subsystem's persistence gap."""
    gap = SubsystemGap(
        id=sid,
        name=spec["name"],
        design_persistence=spec["design_persistence"],
    )

    class_index = {c.simple_name: c for c in classes}
    entity_index = {e.simple_name: e for e in entities}
    repo_index = {r.simple_name: r for r in repositories}

    # Check required classes
    for cls_name in spec["required_classes"]:
        matches = [c for c in classes if class_matches_required(c, cls_name)]
        if matches:
            gap.classes_found.extend([m.fqcn for m in matches])
            active_matches = [m for m in matches if not m.is_deprecated]
            if len(active_matches) > 1:
                gap.duplicate_classes.append({
                    "name": cls_name,
                    "count": len(active_matches),
                    "locations": [m.file_path for m in active_matches],
                })
        else:
            gap.classes_missing.append(cls_name)

    # Check expected entities
    for ent_name in spec.get("expected_entities", []):
        if ent_name in entity_index:
            gap.entities_found.append(ent_name)
        else:
            gap.entities_missing.append(ent_name)

    # Check expected repositories
    for repo_name in spec.get("expected_repositories", []):
        if repo_name in repo_index:
            gap.repositories_found.append(repo_name)
        else:
            gap.repositories_missing.append(repo_name)

    # Check for volatile storage patterns in found classes
    for cls_name in spec["required_classes"]:
        matches = [c for c in classes if class_matches_required(c, cls_name)]
        for m in matches:
            if m.volatile_storage:
                gap.volatile_patterns_found.append({
                    "class": m.simple_name,
                    "patterns": m.volatile_storage,
                    "file": m.file_path,
                })

    # Determine persistence status
    has_entity = len(gap.entities_found) > 0
    has_repo = len(gap.repositories_found) > 0
    has_volatile = len(gap.volatile_patterns_found) > 0
    design_persistence = str(spec["design_persistence"]).strip().lower()
    needs_persistence = not design_persistence.startswith("configuration only")
    has_ndjson_trace_snapshot = sid == "S07_CIH_RAG" and any(
        c.fqcn == "com.example.lms.trace.TraceSnapshotExporter" for c in classes
    )

    if has_entity and has_repo:
        gap.has_db_persistence = True
        gap.persistence_type = "JPA"
        gap.persistence_status = "resolved_jpa"
        gap.action_required = False
    elif has_ndjson_trace_snapshot:
        gap.has_db_persistence = True
        gap.persistence_type = "NDJSON_TRACE_SNAPSHOT"
        gap.persistence_status = "review_ndjson_trace_snapshot"
        gap.action_required = False
    elif not needs_persistence:
        gap.has_db_persistence = True
        gap.persistence_type = "config_only"
        gap.persistence_status = "accepted_config_only"
        gap.action_required = False
    elif has_entity:
        gap.has_db_persistence = True
        gap.persistence_type = "JPA_NO_REPO"
        gap.persistence_status = "partial_jpa"
        gap.action_required = True
    elif has_volatile and needs_persistence:
        gap.has_db_persistence = False
        gap.persistence_type = "volatile_only"
        gap.persistence_status = "unresolved_volatile_only"
        gap.action_required = True
    else:
        gap.has_db_persistence = False
        gap.persistence_type = "none"
        if not spec.get("expected_entities") and not spec.get("expected_repositories"):
            gap.persistence_status = "review_runtime_only"
            gap.action_required = False
        else:
            gap.persistence_status = "unresolved_missing_persistence"
            gap.action_required = True

    # Severity
    if not needs_persistence:
        gap.gap_severity = "LOW"
        gap.gap_details = "Configuration-only persistence; durable runtime state not required"
    elif needs_persistence and not gap.has_db_persistence and has_volatile:
        gap.gap_severity = "CRITICAL"
        gap.gap_details = (
            f"Design requires [{spec['design_persistence']}] "
            f"but implementation uses volatile in-memory storage only"
        )
    elif needs_persistence and (gap.entities_missing or gap.repositories_missing) and gap.action_required:
        gap.gap_severity = "MEDIUM"
        gap.gap_details = (
            f"Missing entities: {gap.entities_missing}; "
            f"missing repos: {gap.repositories_missing}"
        )
    elif gap.persistence_status == "review_ndjson_trace_snapshot":
        gap.gap_severity = "LOW"
        gap.gap_details = (
            "Canonical TraceSnapshotExporter provides an allowlisted NDJSON sidecar, "
            "but durable MLA breadcrumb records and full IQR history remain unproven"
        )
    elif gap.duplicate_classes:
        gap.gap_severity = "LOW"
        gap.gap_details = (
            "Persistence gap not proven; duplicate simple names recorded for "
            f"sourceSet review: {[d['name'] for d in gap.duplicate_classes]}"
        )
    elif gap.persistence_status == "review_runtime_only":
        gap.gap_severity = "LOW"
        gap.gap_details = (
            "No durable DB table is configured for this subsystem; active source "
            "shows runtime/config orchestration state and no unresolved persistence blocker"
        )
    else:
        gap.gap_severity = "LOW"
        gap.gap_details = "Persistence matches design or not required"

    return gap


def generate_markdown(
    gaps: list[SubsystemGap],
    entities: list[EntityInfo],
    repositories: list[RepositoryInfo],
    duplicates: dict[str, list[str]],
    total_classes: int,
    external_db_snapshot: Optional[dict] = None,
) -> str:
    """Generate human-readable markdown report."""
    lines = [
        "# DB Structure Gap Analysis Report",
        "",
        f"**Scan Date**: {__import__('datetime').datetime.now().isoformat()}",
        f"**Total Java Classes**: {total_classes}",
        f"**Total @Entity Classes**: {len(entities)}",
        f"**Total JpaRepository Interfaces**: {len(repositories)}",
        f"**Subsystem-Relevant Duplicate Simple Names**: {len(duplicates)}",
        "",
        "---",
        "",
    ]

    append_external_supabase_snapshot_section(lines, external_db_snapshot)

    lines.extend([
        "## Subsystem Gap Matrix",
        "",
        "| # | Subsystem | Persistence Contract | Type | Status | Action | Severity | Details |",
        "|---|-----------|---------------|------|--------|--------|----------|---------|",
    ])

    for g in gaps:
        severity_icon = {"CRITICAL": "[!]", "MEDIUM": "[~]", "LOW": "[.]"}.get(g.gap_severity, "[ ]")
        contract_icon = persistence_contract_badge(g)
        action = "patch" if g.action_required else "reviewed"
        lines.append(
            f"| {g.id} | {g.name} | {contract_icon} | {g.persistence_type} "
            f"| {g.persistence_status} | {action} | {severity_icon} {g.gap_severity} | {g.gap_details} |"
        )

    lines.extend([
        "",
        "---",
        "",
        "## Critical Gaps (Requires Immediate Patch)",
        "",
    ])

    critical = [g for g in gaps if g.gap_severity == "CRITICAL"]
    if not critical:
        lines.append("No critical gaps found.")
    else:
        for g in critical:
            lines.extend([
                f"### {g.id}: {g.name}",
                "",
                f"**Design requires**: {g.design_persistence}",
                f"**Actual**: {g.persistence_type}",
                "",
                "**Volatile patterns found**:",
                "",
            ])
            for v in g.volatile_patterns_found:
                lines.append(f"- `{v['class']}` in `{v['file']}`: {v['patterns']}")
            if g.duplicate_classes:
                lines.append("")
                lines.append("**Duplicate classes**:")
                for d in g.duplicate_classes:
                    lines.append(f"- `{d['name']}` × {d['count']}: {d['locations']}")
            if g.entities_missing:
                lines.append(f"\n**Missing entities**: {g.entities_missing}")
            if g.repositories_missing:
                lines.append(f"**Missing repositories**: {g.repositories_missing}")
            lines.append("")

    residual = residual_volatile_state(gaps)
    if residual:
        lines.extend([
            "",
            "---",
            "",
            "## Residual Volatile Runtime State (Review)",
            "",
            "These are not immediate DB blockers, but they identify process-local buffers or caches that can be mistaken for durable DB-backed learning state.",
            "",
            "| Subsystem | Persistence Status | Volatile Classes | Review Reason | Review Action |",
            "|-----------|--------------------|------------------|---------------|---------------|",
        ])
        for item in residual:
            classes = ", ".join(f"`{safe_markdown_cell(name, 80)}`" for name in item["volatileClasses"])
            lines.append(
                f"| {item['id']} | {item['persistenceStatus']} | {classes} | "
                f"{safe_markdown_cell(item['reviewReason'], 220)} | "
                f"{safe_markdown_cell(item['reviewAction'], 220)} |"
            )

    lines.extend([
        "---",
        "",
        "## Entity Catalog",
        "",
        "| Table | Entity | FQCN | Columns | ID Strategy |",
        "|-------|--------|------|---------|-------------|",
    ])
    for e in sorted(entities, key=lambda x: x.table_name):
        lines.append(
            f"| {e.table_name} | {e.simple_name} | {e.fqcn} "
            f"| {e.column_count} | {e.id_strategy or 'N/A'} |"
        )

    lines.extend([
        "",
        "---",
        "",
        "## Repository Catalog",
        "",
        "| Repository | Entity Type | ID Type |",
        "|-----------|-------------|---------|",
    ])
    for r in sorted(repositories, key=lambda x: x.simple_name):
        lines.append(f"| {r.simple_name} | {r.entity_type} | {r.id_type} |")

    if duplicates:
        lines.extend([
            "",
            "---",
            "",
            "## Duplicate Class Names (Subsystem-Relevant)",
            "",
        ])
        subsystem_names = set()
        for spec in SUBSYSTEM_SPECS.values():
            subsystem_names.update(spec["required_classes"])
        for name, fqcns in sorted(duplicates.items()):
            if name in subsystem_names:
                lines.append(f"### `{name}` ({len(fqcns)} duplicates)")
                for fqcn in fqcns:
                    lines.append(f"- `{fqcn}`")
                lines.append("")

    return "\n".join(lines)


def append_external_supabase_snapshot_section(lines: list[str], snapshot: Optional[dict]) -> None:
    if not snapshot:
        return

    evidence_needed = snapshot.get("evidence_needed")
    if not isinstance(evidence_needed, list):
        evidence_needed = []

    lines.extend([
        "## External Supabase DB Snapshot Status",
        "",
        f"- Read-only: `{snapshot.get('readOnly')}`",
        f"- Mutation allowed: `{snapshot.get('mutationAllowed')}`",
        f"- Schema snapshot available: `{snapshot.get('schemaSnapshotAvailable')}`",
        f"- Evidence status: `{snapshot.get('evidenceStatus', 'unknown')}`",
        f"- SQL bundle: `{safe_path_cell(snapshot.get('sqlBundlePath', ''))}`",
        f"- Secret pattern hits: `{snapshot.get('rawSecretPatternHits', 0)}`",
    ])
    collection_packet = snapshot.get("collectionPacket") if isinstance(snapshot.get("collectionPacket"), dict) else {}
    if collection_packet:
        lines.append(f"- Collection packet: `{format_supabase_collection_packet(collection_packet)}`")
    advisors = snapshot.get("advisors") if isinstance(snapshot.get("advisors"), dict) else {}
    if advisors:
        lines.append(f"- Advisors: `{format_supabase_advisors(advisors)}`")
    readonly_smoke = snapshot.get("readonlySmoke") if isinstance(snapshot.get("readonlySmoke"), dict) else {}
    if readonly_smoke:
        lines.append(f"- Readonly smoke: `{format_supabase_readonly_smoke(readonly_smoke)}`")
    risk_summary = snapshot.get("riskSummary") if isinstance(snapshot.get("riskSummary"), dict) else {}
    risk_items = [
        f"{safe_markdown_cell(key, 80)}={int(value)}"
        for key, value in risk_summary.items()
        if isinstance(value, int) and value > 0
    ]
    if risk_items:
        lines.append(f"- Risk summary: `{' ; '.join(risk_items)}`")

    project_scope = snapshot.get("projectScope") if isinstance(snapshot.get("projectScope"), dict) else {}
    if project_scope:
        lines.append(f"- Project scope: `{format_supabase_project_scope(project_scope)}`")

    snapshot_import = snapshot.get("snapshotImport") if isinstance(snapshot.get("snapshotImport"), dict) else {}
    if snapshot_import:
        lines.append(f"- Snapshot import: `{format_supabase_snapshot_import(snapshot_import)}`")

    input_shape = snapshot.get("inputShape") if isinstance(snapshot.get("inputShape"), dict) else {}
    if input_shape:
        lines.append(f"- Input shape: `{format_supabase_input_shape(input_shape)}`")

    auth_plan = snapshot.get("authPlan") if isinstance(snapshot.get("authPlan"), dict) else {}
    if auth_plan:
        lines.append(f"- Auth plan: `{format_supabase_auth_plan(auth_plan)}`")

    docs_contracts = snapshot.get("docsContracts") if isinstance(snapshot.get("docsContracts"), dict) else {}
    if docs_contracts:
        lines.append(f"- Docs/contracts: `{format_supabase_docs_contracts(docs_contracts)}`")

    next_actions = snapshot.get("nextActions") if isinstance(snapshot.get("nextActions"), list) else []
    if next_actions:
        lines.append(f"- Next actions: `{' ; '.join(safe_markdown_cell(item, 80) for item in next_actions[:8])}`")

    if evidence_needed:
        lines.append("- evidence_needed:")
        for item in evidence_needed[:8]:
            lines.append(f"  - {safe_markdown_cell(item, 220)}")
    else:
        lines.append("- evidence_needed: none")

    lines.extend([
        "",
        "---",
        "",
    ])


def load_external_supabase_snapshot(output_dir: Path) -> Optional[dict]:
    snapshot_path = output_dir / "supabase-schema-snapshot.json"
    if not snapshot_path.exists():
        return None
    try:
        data = json.loads(snapshot_path.read_text(encoding="utf-8"))
    except Exception as exc:
        return {
            "readOnly": None,
            "mutationAllowed": None,
            "schemaSnapshotAvailable": False,
            "sqlBundlePath": "",
            "rawSecretPatternHits": 0,
            "evidence_needed": [f"supabase-schema-snapshot.json unreadable: {exc.__class__.__name__}"],
        }
    if is_redacted_path_text(data.get("collectionPacketPath")):
        local_packet_path = output_dir / "supabase-execute-sql-collection.packet.json"
        if local_packet_path.exists():
            data["_collectionPacketPathForRead"] = str(local_packet_path)
    summary = summarize_external_supabase_snapshot(data)
    readonly_smoke = load_supabase_readonly_smoke_summary(output_dir)
    if readonly_smoke:
        summary["readonlySmoke"] = readonly_smoke
    return summary


def summarize_external_supabase_snapshot(data: dict) -> dict:
    evidence_needed = data.get("evidence_needed")
    if not isinstance(evidence_needed, list):
        evidence_needed = []
    evidence_status = supabase_external_evidence_status(data, evidence_needed)
    summary = {
        "schemaVersion": safe_markdown_cell(data.get("schemaVersion", "")),
        "readOnly": data.get("readOnly"),
        "mutationAllowed": data.get("mutationAllowed"),
        "schemaSnapshotAvailable": data.get("schemaSnapshotAvailable"),
        "evidenceStatus": evidence_status,
        "sqlBundlePath": safe_path_cell(data.get("sqlBundlePath", "")),
        "rawSecretPatternHits": data.get("rawSecretPatternHits", 0),
        "riskSummary": summarize_supabase_risk_summary(data),
        "evidence_needed": [safe_markdown_cell(item, 220) for item in evidence_needed[:8]],
    }
    if data.get("sqlBundlePathHash"):
        summary["sqlBundlePathHash"] = safe_markdown_cell(data.get("sqlBundlePathHash"), 80)
    if "sqlBundlePathLength" in data:
        try:
            summary["sqlBundlePathLength"] = int(data.get("sqlBundlePathLength", 0) or 0)
        except Exception:
            summary["sqlBundlePathLength"] = 0
    project_scope = summarize_supabase_project_scope(data)
    if project_scope:
        summary["projectScope"] = project_scope
    input_shape = summarize_supabase_input_shape(data)
    if input_shape:
        summary["inputShape"] = input_shape
    snapshot_import = summarize_supabase_snapshot_import(data)
    if snapshot_import:
        summary["snapshotImport"] = snapshot_import
    auth_plan = summarize_supabase_auth_plan(data)
    if auth_plan:
        summary["authPlan"] = auth_plan
    docs_contracts = summarize_supabase_docs_contracts(data)
    if docs_contracts:
        summary["docsContracts"] = docs_contracts
    collection_packet = summarize_supabase_collection_packet(data)
    if collection_packet:
        summary["collectionPacket"] = collection_packet
    advisors = summarize_supabase_advisors(data)
    if advisors:
        summary["advisors"] = advisors
    readonly_smoke = summarize_supabase_readonly_smoke(data.get("readonlySmoke"))
    if readonly_smoke:
        summary["readonlySmoke"] = readonly_smoke
    next_actions = summarize_supabase_next_actions(data)
    if next_actions:
        summary["nextActions"] = next_actions
    return summary


def load_supabase_readonly_smoke_summary(output_dir: Path) -> dict:
    smoke_dir = find_supabase_readonly_smoke_dir(output_dir)
    if not smoke_dir:
        return {}

    context = load_json_object(smoke_dir / "supabase-context-probe.json")
    snapshot_result = load_json_object(smoke_dir / "supabase-schema-snapshot.result.json")
    import_result = load_json_object(smoke_dir / "supabase-schema-snapshot-import.result.json")
    snapshot = load_json_object(smoke_dir / "supabase-schema-snapshot.json")

    raw = {
        "artifactPresent": True,
        "artifactFileCount": sum(
            1
            for name in (
                "supabase-context-probe.json",
                "supabase-schema-snapshot.result.json",
                "supabase-schema-snapshot-import.result.json",
                "supabase-schema-snapshot.json",
            )
            if (smoke_dir / name).exists()
        ),
        "contextDecision": context.get("decision"),
        "projectScopeStatus": nested_value(context, "projectScope", "status"),
        "mcpReachable": nested_value(context, "mcp", "reachable"),
        "mcpHttpStatus": nested_value(context, "mcp", "httpStatus"),
        "snapshotDecision": snapshot_result.get("decision"),
        "schemaSnapshotAvailable": snapshot_result.get(
            "schemaSnapshotAvailable",
            snapshot.get("schemaSnapshotAvailable"),
        ),
        "snapshotBytes": snapshot_result.get("snapshotBytes"),
        "importDecision": import_result.get("decision"),
        "importedResultCount": import_result.get("importedResultCount"),
        "mutationAllowed": import_result.get(
            "mutationAllowed",
            snapshot_result.get("mutationAllowed", snapshot.get("mutationAllowed")),
        ),
        "rawSecretPatternHits": sum_int_fields(
            context,
            snapshot_result,
            import_result,
            snapshot,
            field="rawSecretPatternHits",
        ),
        "windowsAbsPathHits": sum_int_fields(
            context,
            snapshot_result,
            import_result,
            snapshot,
            field="windowsAbsPathHits",
        ),
    }
    raw["status"] = "ok" if all(
        item.get("ok") is not False for item in (context, snapshot_result, import_result)
    ) else "attention"
    return summarize_supabase_readonly_smoke(raw)


def find_supabase_readonly_smoke_dir(output_dir: Path) -> Optional[Path]:
    direct = output_dir / "supabase-readonly-snapshot"
    if direct.exists():
        return direct
    for base in (output_dir, *output_dir.parents):
        candidate = base / "var" / "codex-smoke" / "supabase-readonly-snapshot"
        if candidate.exists():
            return candidate
    return None


def load_json_object(path: Path) -> dict:
    if not path.exists():
        return {}
    data = None
    for encoding in ("utf-8-sig", "utf-8"):
        try:
            data = json.loads(path.read_text(encoding=encoding))
            break
        except Exception:
            data = None
    if data is None:
        return {}
    return data if isinstance(data, dict) else {}


def nested_value(data: dict, *keys: str) -> object:
    current: object = data
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def sum_int_fields(*items: dict, field: str) -> int:
    total = 0
    for item in items:
        try:
            total += int(item.get(field, 0) or 0)
        except Exception:
            total += 0
    return total


def summarize_supabase_readonly_smoke(raw: object) -> dict:
    if not isinstance(raw, dict):
        return {}
    summary = {}
    text_fields = (
        "status",
        "contextDecision",
        "projectScopeStatus",
        "snapshotDecision",
        "importDecision",
    )
    int_fields = (
        "artifactFileCount",
        "mcpHttpStatus",
        "snapshotBytes",
        "importedResultCount",
        "rawSecretPatternHits",
        "windowsAbsPathHits",
    )
    bool_fields = (
        "artifactPresent",
        "mcpReachable",
        "schemaSnapshotAvailable",
        "mutationAllowed",
    )
    for key in text_fields:
        if raw.get(key) not in (None, ""):
            summary[key] = safe_markdown_cell(raw.get(key), 80)
    for key in bool_fields:
        if isinstance(raw.get(key), bool):
            summary[key] = raw[key]
    for key in int_fields:
        if key in raw:
            try:
                summary[key] = int(raw.get(key, 0) or 0)
            except Exception:
                summary[key] = 0
    return summary


def supabase_external_evidence_status(data: dict, evidence_needed: list) -> str:
    snapshot_import = data.get("snapshotImport")
    if isinstance(snapshot_import, dict):
        try:
            mcp_error_count = int(snapshot_import.get("mcpErrorCount", 0) or 0)
        except Exception:
            mcp_error_count = 0
        if mcp_error_count > 0:
            return "mcp_error_transcript"
    if data.get("schemaSnapshotAvailable") is not True:
        return "missing_live_snapshot"
    if data.get("schemaSnapshotComplete") is False:
        return "partial_live_snapshot"
    if not isinstance(snapshot_import, dict):
        return "imported_with_unknown_import_status"
    if (
        snapshot_import.get("status") == "partial_evidence_needed"
        or snapshot_import.get("resultSetComplete") is False
    ):
        return "partial_live_snapshot"
    if evidence_needed:
        return "imported_with_evidence_gaps"
    if snapshot_import.get("status") == "imported":
        return "imported_complete"
    return "imported_with_unknown_import_status"


def summarize_supabase_next_actions(data: dict) -> list[str]:
    raw = data.get("nextActions")
    if not isinstance(raw, list):
        return []
    actions: list[str] = []
    for item in raw:
        if not isinstance(item, str) or not item.strip():
            continue
        safe_item = safe_markdown_cell(item, 80)
        if safe_item and safe_item not in actions:
            actions.append(safe_item)
    return actions[:12]


def summarize_supabase_risk_summary(data: dict) -> dict:
    raw = data.get("riskSummary")
    if not isinstance(raw, dict):
        return {}
    allowed_keys = (
        "exposedTablesWithoutRls",
        "metadataPolicyRisk",
        "updateSelectPolicyGap",
        "storageUpsertPolicyGap",
        "viewsMissingSecurityInvoker",
        "exposedSecurityDefinerFunctions",
    )
    summary = {}
    for key in allowed_keys:
        try:
            count = int(raw.get(key, 0) or 0)
        except Exception:
            count = 0
        if count > 0:
            summary[key] = count
    return summary


def summarize_supabase_project_scope(data: dict) -> dict:
    raw = data.get("projectScope")
    if not isinstance(raw, dict):
        return {}
    summary = {}
    for key in ("status", "nextAction", "scopedMcpUrlTemplate"):
        if key in raw:
            limit = 220 if key == "scopedMcpUrlTemplate" else 80
            summary[key] = safe_markdown_cell(raw.get(key), limit)
    for key in ("projectRefPresent", "projectRefTemplateMode", "readOnlyMode", "featuresScopedMode"):
        if isinstance(raw.get(key), bool):
            summary[key] = raw[key]
    feature_groups = raw.get("featureGroups")
    if isinstance(feature_groups, list):
        safe_groups = [
            safe_markdown_cell(item, 40)
            for item in feature_groups
            if isinstance(item, str) and item.strip()
        ]
        if safe_groups:
            summary["featureGroups"] = ",".join(safe_groups[:12])
    if "rawSecretPatternHits" in raw:
        try:
            summary["rawSecretPatternHits"] = int(raw.get("rawSecretPatternHits", 0) or 0)
        except Exception:
            summary["rawSecretPatternHits"] = 0
    return summary


def summarize_supabase_snapshot_import(data: dict) -> dict:
    raw = data.get("snapshotImport")
    if not isinstance(raw, dict):
        raw = {}

    if not raw and supabase_snapshot_import_pending(data):
        summary = {"status": "evidence_needed", "importedCount": 0, "storedRawRows": False}
        plan_query_names = supabase_snapshot_plan_query_names(data)
        if plan_query_names:
            summary["missingResultCount"] = len(plan_query_names)
            summary["missingResultNames"] = ",".join(plan_query_names[:24])
        return summary

    summary = {}
    status = raw.get("status", data.get("status", ""))
    if status:
        summary["status"] = safe_markdown_cell(status, 40)
    imported_count = raw.get(
        "importedCount",
        raw.get("importedResultCount", data.get("importedCount", data.get("importedResultCount"))),
    )
    if imported_count is not None:
        try:
            summary["importedCount"] = int(imported_count)
        except Exception:
            summary["importedCount"] = 0
    if isinstance(raw.get("storedRawRows"), bool):
        summary["storedRawRows"] = raw["storedRawRows"]
    missing_count = raw.get("missingResultCount")
    if missing_count is not None:
        try:
            summary["missingResultCount"] = int(missing_count)
        except Exception:
            summary["missingResultCount"] = 0
    missing_names = raw.get("missingResultNames")
    if isinstance(missing_names, list):
        safe_names = [
            safe_markdown_cell(name, 120)
            for name in missing_names
            if isinstance(name, str) and name.strip()
        ]
        if safe_names:
            summary["missingResultNames"] = ",".join(safe_names[:24])
    unexpected_count = raw.get("unexpectedResultCount")
    if unexpected_count is not None:
        try:
            summary["unexpectedResultCount"] = int(unexpected_count)
        except Exception:
            summary["unexpectedResultCount"] = 0
    unexpected_names = raw.get("unexpectedResultNames")
    if isinstance(unexpected_names, list):
        safe_names = [
            safe_markdown_cell(name, 120)
            for name in unexpected_names
            if isinstance(name, str) and name.strip()
        ]
        if safe_names:
            summary["unexpectedResultNames"] = ",".join(safe_names[:24])
    duplicate_count = raw.get("duplicateResultCount")
    if duplicate_count is not None:
        try:
            summary["duplicateResultCount"] = int(duplicate_count)
        except Exception:
            summary["duplicateResultCount"] = 0
    duplicate_names = raw.get("duplicateResultNames")
    if isinstance(duplicate_names, list):
        safe_names = [
            safe_markdown_cell(name, 120)
            for name in duplicate_names
            if isinstance(name, str) and name.strip()
        ]
        if safe_names:
            summary["duplicateResultNames"] = ",".join(safe_names[:24])
    mcp_error_count = raw.get("mcpErrorCount")
    if mcp_error_count is not None:
        try:
            summary["mcpErrorCount"] = int(mcp_error_count)
        except Exception:
            summary["mcpErrorCount"] = 0
    mcp_error_codes = raw.get("mcpErrorCodes")
    if isinstance(mcp_error_codes, list):
        safe_codes = [
            safe_markdown_cell(code, 80)
            for code in mcp_error_codes
            if isinstance(code, str) and code.strip()
        ]
        if safe_codes:
            summary["mcpErrorCodes"] = ",".join(safe_codes[:12])
    return summary


def supabase_snapshot_import_pending(data: dict) -> bool:
    if data.get("schemaSnapshotAvailable") is True:
        return False
    next_actions = data.get("nextActions")
    if not isinstance(next_actions, list):
        next_actions = []
    evidence_needed = data.get("evidence_needed")
    if not isinstance(evidence_needed, list):
        evidence_needed = []
    return bool(
        data.get("resultTemplatePath")
        or any("supabase_schema_snapshot_import" in str(item) for item in next_actions)
        or any("populate_supabase_query_results_file" in str(item) for item in next_actions)
        or any("Supabase MCP" in str(item) for item in evidence_needed)
    )


def supabase_snapshot_import_is_unfilled_template(data: dict, snapshot_import: dict) -> bool:
    status = str(snapshot_import.get("status", data.get("status", "")) or "")
    imported_raw = snapshot_import.get(
        "importedCount",
        snapshot_import.get("importedResultCount", data.get("importedCount", data.get("importedResultCount", 0))),
    )
    try:
        imported_count = int(imported_raw or 0)
    except Exception:
        imported_count = 0
    input_shape = snapshot_import.get("inputShape")
    if not isinstance(input_shape, dict):
        input_shape = data.get("inputShape")
    supported_count = -1
    if isinstance(input_shape, dict):
        try:
            supported_count = int(input_shape.get("supportedResultItemCount", -1))
        except Exception:
            supported_count = -1
    evidence_needed = data.get("evidence_needed")
    if not isinstance(evidence_needed, list):
        evidence_needed = []
    return (
        data.get("schemaSnapshotAvailable") is not True
        and status == "evidence_needed"
        and imported_count == 0
        and supported_count == 0
        and any("unfilled result template" in str(item) for item in evidence_needed)
    )


def supabase_snapshot_plan_query_names(data: dict) -> list[str]:
    plan = data.get("dbSnapshotPlan")
    if not isinstance(plan, dict):
        return []
    queries = plan.get("sqlQueries")
    if not isinstance(queries, list):
        return []
    names: list[str] = []
    seen: set[str] = set()
    for query in queries:
        if not isinstance(query, dict):
            continue
        name = safe_markdown_cell(query.get("name", ""), 120)
        if not name or name in seen:
            continue
        seen.add(name)
        names.append(name)
    return names


def summarize_supabase_input_shape(data: dict) -> dict:
    raw = data.get("inputShape")
    if not isinstance(raw, dict):
        snapshot_import = data.get("snapshotImport")
        if isinstance(snapshot_import, dict):
            raw = snapshot_import.get("inputShape")
    if not isinstance(raw, dict):
        if supabase_snapshot_import_pending(data):
            return {
                "payloadKind": "not_imported",
                "supportedResultItemCount": 0,
                "resultsArrayPresent": False,
            }
        return {}

    text_keys = ("payloadKind",)
    count_keys = (
        "topLevelKeyCount",
        "topLevelArrayCount",
        "contentPartCount",
        "candidateResultItemCount",
        "supportedResultItemCount",
        "duplicateResultItemCount",
    )
    bool_keys = (
        "resultsArrayPresent",
        "resultContainerPresent",
        "structuredContentPresent",
    )
    summary = {}
    for key in text_keys:
        if key in raw:
            summary[key] = safe_markdown_cell(raw.get(key), 40)
    for key in count_keys:
        if key in raw:
            try:
                summary[key] = int(raw.get(key, 0) or 0)
            except Exception:
                summary[key] = 0
    for key in bool_keys:
        if isinstance(raw.get(key), bool):
            summary[key] = raw[key]
    return summary


def summarize_supabase_collection_packet(data: dict) -> dict:
    display_path = data.get("collectionPacketPath")
    read_path = data.get("_collectionPacketPathForRead") or display_path
    if not display_path and not read_path:
        return {}

    path_text = str(display_path or read_path)
    summary = {
        "path": safe_path_cell(path_text, 180),
    }
    if data.get("collectionPacketPathHash"):
        summary["pathHash"] = safe_markdown_cell(data.get("collectionPacketPathHash"), 80)

    packet_path = Path(str(read_path))
    if not packet_path.exists():
        summary["status"] = "missing"
        if "collectionPacketSecretPatternHits" in data:
            try:
                summary["secretPatternHits"] = int(data.get("collectionPacketSecretPatternHits", 0) or 0)
            except Exception:
                summary["secretPatternHits"] = 0
        return summary

    try:
        packet_text = packet_path.read_text(encoding="utf-8", errors="ignore")
    except Exception as exc:
        summary["status"] = f"unreadable:{exc.__class__.__name__}"
        return summary

    summary["status"] = "available"
    summary["bytes"] = len(packet_text.encode("utf-8"))
    summary["secretPatternHits"] = count_high_conf_secret_patterns(packet_text)
    try:
        packet = json.loads(packet_text)
    except Exception as exc:
        summary["parseStatus"] = f"json_error:{exc.__class__.__name__}"
        return summary

    for key in ("schemaVersion", "mcpTool", "executionMode", "importTool"):
        value = packet.get(key)
        if value:
            summary[key] = safe_markdown_cell(value, 120)
    if "readOnly" in packet:
        summary["readOnly"] = bool(packet.get("readOnly"))
    if "mutationAllowed" in packet:
        summary["mutationAllowed"] = bool(packet.get("mutationAllowed"))
    declared_query_count = packet.get("queryCount")
    if declared_query_count is not None:
        try:
            summary["declaredQueryCount"] = int(declared_query_count or 0)
        except Exception:
            summary["declaredQueryCount"] = 0
    next_actions = packet.get("nextActions")
    if isinstance(next_actions, list):
        safe_actions = [
            safe_markdown_cell(action, 80)
            for action in next_actions
            if isinstance(action, str) and action.strip()
        ]
        if safe_actions:
            summary["nextActions"] = ",".join(safe_actions[:12])

    queries = packet.get("queries")
    if isinstance(queries, list):
        summary["queryCount"] = len(queries)
        summary["queryPayloadCount"] = sum(
            1
            for query in queries
            if isinstance(query, dict)
            and isinstance(query.get("executeSqlInput"), dict)
            and isinstance(query["executeSqlInput"].get("query"), str)
            and query["executeSqlInput"]["query"].strip()
        )
        if "declaredQueryCount" in summary:
            summary["queryCountMismatch"] = int(summary["declaredQueryCount"]) != summary["queryCount"]
    return summary


def summarize_supabase_advisors(data: dict) -> dict:
    raw = data.get("advisors")
    if not isinstance(raw, dict):
        return {}

    summary = {}
    if "available" in raw:
        summary["available"] = bool(raw.get("available"))

    rows = raw.get("rows")
    if not isinstance(rows, list):
        rows = []
    summary["rowCount"] = len(rows)
    if not rows and raw.get("available") is False:
        summary["status"] = "not_collected"

    error_count = 0
    warning_count = 0
    categories: set[str] = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        level = safe_markdown_cell(row.get("level", row.get("severity", "")), 40).upper()
        if level in {"ERROR", "CRITICAL"}:
            error_count += 1
        if level in {"WARN", "WARNING"}:
            warning_count += 1
        category = safe_markdown_cell(row.get("category", row.get("group", "")), 80).upper()
        if category:
            categories.add(category)

    if error_count:
        summary["errorCount"] = error_count
    if warning_count:
        summary["warningCount"] = warning_count
    if categories:
        summary["categories"] = ",".join(sorted(categories)[:12])

    collection_plan = raw.get("collectionPlan")
    if isinstance(collection_plan, dict):
        tool = safe_markdown_cell(collection_plan.get("mcpTool", ""), 80)
        feature_group = safe_markdown_cell(collection_plan.get("featureGroup", ""), 80)
        if tool:
            summary["collectionTool"] = tool
        if feature_group:
            summary["featureGroup"] = feature_group
    return summary


def summarize_supabase_auth_plan(data: dict) -> dict:
    raw = data.get("authPlan")
    if not isinstance(raw, dict):
        return {}

    summary = {}
    default_hosted_auth_mode = raw.get("defaultHostedAuthMode")
    if default_hosted_auth_mode:
        summary["defaultHostedAuthMode"] = safe_markdown_cell(default_hosted_auth_mode, 80)

    supported_auth_modes = raw.get("supportedAuthModes")
    if isinstance(supported_auth_modes, list):
        safe_modes = [
            safe_markdown_cell(item, 80)
            for item in supported_auth_modes
            if isinstance(item, str) and item.strip()
        ]
        if safe_modes:
            summary["supportedAuthModes"] = ",".join(safe_modes[:12])

    if "mcpOAuthRequired" in raw:
        summary["mcpOAuthRequired"] = bool(raw.get("mcpOAuthRequired"))

    manual_auth_mode = raw.get("manualAuthMode")
    if manual_auth_mode:
        summary["manualAuthMode"] = safe_markdown_cell(manual_auth_mode, 80)

    env_status = raw.get("manualAuthEnvStatus")
    if isinstance(env_status, list):
        status_parts = []
        sensitive_refs = []
        for item in env_status:
            if not isinstance(item, dict):
                continue
            name = safe_markdown_cell(item.get("name", ""), 80)
            if not name:
                continue
            present = bool(item.get("present"))
            sensitive = bool(item.get("sensitive"))
            status_parts.append(f"{name}:present={present}:sensitive={sensitive}")
            if sensitive:
                sensitive_refs.append(name)
        if status_parts:
            summary["manualAuthEnvStatus"] = ",".join(status_parts[:12])
        if sensitive_refs:
            summary["manualAuthSensitiveEnvRefs"] = ",".join(sensitive_refs[:12])

    warnings = raw.get("securityWarnings")
    if isinstance(warnings, list):
        safe_warnings = [
            safe_markdown_cell(item, 80)
            for item in warnings
            if isinstance(item, str) and item.strip()
        ]
        if safe_warnings:
            summary["securityWarnings"] = ",".join(safe_warnings[:12])

    return summary


def summarize_supabase_docs_contracts(data: dict) -> dict:
    plan = data.get("dbSnapshotPlan")
    if not isinstance(plan, dict):
        return {}

    docs_refs = plan.get("docsRefs") if isinstance(plan.get("docsRefs"), list) else []
    security_contracts = (
        plan.get("securityContracts")
        if isinstance(plan.get("securityContracts"), list)
        else []
    )
    if not docs_refs and not security_contracts:
        return {}

    api_keys_docs = any(
        isinstance(entry, dict)
        and safe_markdown_cell(entry.get("id", ""), 80) == "api-keys"
        and "supabase.com/docs/guides/getting-started/api-keys" in str(entry.get("url", ""))
        for entry in docs_refs
    )
    secret_keys_backend_only = any(
        safe_markdown_cell(contract, 120) == "secret_keys_backend_only"
        for contract in security_contracts
    )

    return {
        "docsRefCount": len(docs_refs),
        "securityContractCount": len(security_contracts),
        "apiKeysDocs": api_keys_docs,
        "secretKeysBackendOnly": secret_keys_backend_only,
    }


def format_supabase_snapshot_import(snapshot_import: dict) -> str:
    ordered_keys = (
        "status",
        "importedCount",
        "storedRawRows",
        "missingResultCount",
        "missingResultNames",
        "unexpectedResultCount",
        "unexpectedResultNames",
        "duplicateResultCount",
        "duplicateResultNames",
        "mcpErrorCount",
        "mcpErrorCodes",
    )
    parts = []
    for key in ordered_keys:
        if key in snapshot_import:
            parts.append(f"{key}={safe_markdown_cell(snapshot_import[key], 80)}")
    return " ; ".join(parts)


def format_supabase_project_scope(project_scope: dict) -> str:
    ordered_keys = (
        "status",
        "nextAction",
        "projectRefPresent",
        "projectRefTemplateMode",
        "readOnlyMode",
        "featuresScopedMode",
        "featureGroups",
        "rawSecretPatternHits",
    )
    parts = []
    for key in ordered_keys:
        if key in project_scope:
            parts.append(f"{key}={safe_markdown_cell(project_scope[key], 80)}")
    return " ; ".join(parts)


def format_supabase_input_shape(input_shape: dict) -> str:
    ordered_keys = (
        "payloadKind",
        "topLevelKeyCount",
        "topLevelArrayCount",
        "resultsArrayPresent",
        "resultContainerPresent",
        "structuredContentPresent",
        "contentPartCount",
        "candidateResultItemCount",
        "supportedResultItemCount",
        "duplicateResultItemCount",
    )
    parts = []
    for key in ordered_keys:
        if key in input_shape:
            parts.append(f"{key}={safe_markdown_cell(input_shape[key], 80)}")
    return " ; ".join(parts)


def format_supabase_auth_plan(auth_plan: dict) -> str:
    ordered_keys = (
        "defaultHostedAuthMode",
        "supportedAuthModes",
        "mcpOAuthRequired",
        "manualAuthMode",
        "manualAuthEnvStatus",
        "manualAuthSensitiveEnvRefs",
        "securityWarnings",
    )
    parts = []
    for key in ordered_keys:
        if key in auth_plan:
            parts.append(f"{key}={safe_markdown_cell(auth_plan[key], 180)}")
    return " ; ".join(parts)


def format_supabase_docs_contracts(docs_contracts: dict) -> str:
    ordered_keys = (
        "docsRefCount",
        "securityContractCount",
        "apiKeysDocs",
        "secretKeysBackendOnly",
    )
    parts = []
    for key in ordered_keys:
        if key in docs_contracts:
            parts.append(f"{key}={safe_markdown_cell(docs_contracts[key], 80)}")
    return " ; ".join(parts)


def format_supabase_advisors(advisors: dict) -> str:
    ordered_keys = (
        "status",
        "available",
        "rowCount",
        "errorCount",
        "warningCount",
        "categories",
        "collectionTool",
        "featureGroup",
    )
    parts = []
    for key in ordered_keys:
        if key in advisors:
            parts.append(f"{key}={safe_markdown_cell(advisors[key], 80)}")
    return " ; ".join(parts)


def format_supabase_readonly_smoke(readonly_smoke: dict) -> str:
    ordered_keys = (
        "status",
        "artifactPresent",
        "artifactFileCount",
        "contextDecision",
        "projectScopeStatus",
        "mcpReachable",
        "mcpHttpStatus",
        "snapshotDecision",
        "schemaSnapshotAvailable",
        "snapshotBytes",
        "importDecision",
        "importedResultCount",
        "mutationAllowed",
        "rawSecretPatternHits",
    )
    parts = []
    for key in ordered_keys:
        if key in readonly_smoke:
            parts.append(f"{key}={safe_markdown_cell(readonly_smoke[key], 80)}")
    return " ; ".join(parts)


def format_supabase_collection_packet(collection_packet: dict) -> str:
    ordered_keys = (
        "status",
        "path",
        "schemaVersion",
        "mcpTool",
        "readOnly",
        "mutationAllowed",
        "executionMode",
        "declaredQueryCount",
        "queryCount",
        "queryCountMismatch",
        "queryPayloadCount",
        "nextActions",
        "bytes",
        "secretPatternHits",
    )
    parts = []
    for key in ordered_keys:
        if key in collection_packet:
            parts.append(f"{key}={safe_markdown_cell(collection_packet[key], 180)}")
    return " ; ".join(parts)


def persistence_contract_badge(gap: SubsystemGap) -> str:
    if gap.action_required:
        return "[PATCH]"
    if gap.persistence_status.startswith("review_"):
        return "[REVIEWED]"
    if gap.has_db_persistence:
        return "[OK]"
    return "[OK]"


def residual_volatile_state(gaps: list[SubsystemGap]) -> list[dict]:
    """Return non-blocking volatile state that still deserves persistence review."""
    residual: list[dict] = []
    for gap in gaps:
        if gap.action_required or not gap.volatile_patterns_found:
            continue
        volatile_classes = sorted({
            str(item.get("class", ""))
            for item in gap.volatile_patterns_found
            if str(item.get("class", "")).strip()
        })
        residual.append({
            "id": gap.id,
            "name": gap.name,
            "persistenceType": gap.persistence_type,
            "persistenceStatus": gap.persistence_status,
            "volatileClasses": volatile_classes,
            "volatilePatternCount": len(gap.volatile_patterns_found),
            "reviewReason": (
                "Durable persistence exists or is not required, but active runtime still has "
                "process-local buffers/caches that can be mistaken for DB-backed learning state."
            ),
            "reviewAction": (
                "verify durable sink/exporter before adding schema; use live Supabase snapshot "
                "or repo-local entity/repository evidence to prove whether persistence is still missing"
            ),
        })
    return residual


def count_high_conf_secret_patterns(text: str) -> int:
    return sum(len(pattern.findall(text)) for pattern in HIGH_CONF_SECRET_PATTERNS)


def safe_markdown_cell(value: object, limit: int = 180) -> str:
    text = str(value)
    text = text.replace("\r", " ").replace("\n", " ").replace("|", "/").strip()
    if len(text) > limit:
        return text[: max(0, limit - 3)] + "..."
    return text


def safe_path_cell(value: object, limit: int = 180) -> str:
    text = str(value or "").replace("\r", " ").replace("\n", " ").replace("|", "/").strip()
    if not text:
        return ""
    if looks_like_absolute_path(text):
        digest = hashlib.sha256(text.encode("utf-8", errors="ignore")).hexdigest()
        return f"pathHash={digest} ; pathLength={len(text)}"
    return safe_markdown_cell(text, limit)


def is_redacted_path_text(value: object) -> bool:
    text = str(value or "").strip()
    return text.startswith("pathHash=") and "pathLength=" in text


def looks_like_absolute_path(text: str) -> bool:
    return bool(
        re.match(r"^[A-Za-z]:[\\/]", text)
        or text.startswith("\\\\")
        or text.startswith("/")
    )


def format_output_location(label: str, output_path: Path, directory: bool = False) -> str:
    path_text = str(output_path)
    if directory and not path_text.endswith(("/", "\\")):
        path_text += os.sep
    return f"  {label} -> {path_text}"


def main():
    parser = argparse.ArgumentParser(description="DB Structure Gap Scanner")
    parser.add_argument("--root", default="main/java", help="Java source root")
    parser.add_argument("--output", default="data/db-gap-report", help="Output directory")
    parser.add_argument("--format", choices=["json", "markdown", "both"], default="both")
    args = parser.parse_args()

    root = Path(args.root)
    if not root.exists():
        print(f"ERROR: Root directory '{root}' does not exist", file=sys.stderr)
        sys.exit(1)

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Scan active source roots when the repository root is provided. This avoids
    # treating archive, extras, and inactive mirror folders as live DB gaps.
    java_files, scan_roots = discover_java_files(root)
    print(f"Scanning {len(java_files)} Java files in {root}...")

    classes: list[ClassInfo] = []
    entities: list[EntityInfo] = []
    repositories: list[RepositoryInfo] = []

    for f in java_files:
        ci = scan_java_file(f, root)
        if ci:
            classes.append(ci)

        ei = extract_entity_details(f, root)
        if ei:
            entities.append(ei)

        ri = extract_repository_details(f, root)
        if ri:
            repositories.append(ri)

    duplicates = find_duplicates(classes)
    subsystem_relevant_dups = {}
    all_required = set()
    for spec in SUBSYSTEM_SPECS.values():
        all_required.update(spec["required_classes"])
    for name, fqcns in duplicates.items():
        if name in all_required:
            subsystem_relevant_dups[name] = fqcns

    # Assess each subsystem
    gaps = []
    for sid, spec in SUBSYSTEM_SPECS.items():
        gap = assess_subsystem(sid, spec, classes, entities, repositories, duplicates)
        gaps.append(gap)

    # Summary
    critical_count = sum(1 for g in gaps if g.gap_severity == "CRITICAL")
    medium_count = sum(1 for g in gaps if g.gap_severity == "MEDIUM")
    low_count = sum(1 for g in gaps if g.gap_severity == "LOW")
    action_required_count = sum(1 for g in gaps if g.action_required)
    residual_volatile = residual_volatile_state(gaps)
    status_counts = dict(sorted({
        status: sum(1 for g in gaps if g.persistence_status == status)
        for status in {g.persistence_status for g in gaps}
    }.items()))
    external_db_snapshot = load_external_supabase_snapshot(output_dir)

    summary = {
        "generatedAt": generated_at_utc(),
        "scan_root": str(root),
        "scan_roots": scan_roots,
        "total_java_files": len(java_files),
        "total_classes": len(classes),
        "total_entities": len(entities),
        "total_repositories": len(repositories),
        "total_duplicate_simple_names": len(duplicates),
        "subsystem_relevant_duplicates": len(subsystem_relevant_dups),
        "gap_severity_counts": {
            "CRITICAL": critical_count,
            "MEDIUM": medium_count,
            "LOW": low_count,
        },
        "action_required_count": action_required_count,
        "residual_volatile_state_count": len(residual_volatile),
        "residual_volatile_state": residual_volatile,
        "persistence_status_counts": status_counts,
        "subsystems": [asdict(g) for g in gaps],
    }
    if external_db_snapshot:
        summary["external_supabase_snapshot"] = external_db_snapshot

    print(f"\n{'='*60}")
    print(f"  DB Gap Scanner Results")
    print(f"{'='*60}")
    print(f"  Classes: {len(classes)}  Entities: {len(entities)}  Repositories: {len(repositories)}")
    print(f"  Duplicates (subsystem): {len(subsystem_relevant_dups)}")
    print(f"  Gaps: [CRITICAL]={critical_count}  [MEDIUM]={medium_count}  [LOW]={low_count}")
    print(f"  Action Required: {action_required_count}")
    print(f"{'='*60}\n")

    for g in gaps:
        icon = {"CRITICAL": "[!]", "MEDIUM": "[~]", "LOW": "[.]"}.get(g.gap_severity, "[ ]")
        contract = persistence_contract_badge(g)
        action = "patch" if g.action_required else "reviewed"
        print(
            f"  {icon} {g.id}: {g.name} -- Contract:{contract} "
            f"Type:{g.persistence_type} Status:{g.persistence_status} Action:{action}"
        )
        if g.gap_details and g.gap_severity != "LOW":
            print(f"      -> {g.gap_details}")

    # Write outputs
    if args.format in ("json", "both"):
        (output_dir / "gap_matrix.json").write_text(
            json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        (output_dir / "entity_catalog.json").write_text(
            json.dumps([asdict(e) for e in entities], indent=2, ensure_ascii=False), encoding="utf-8"
        )
        (output_dir / "repository_catalog.json").write_text(
            json.dumps([asdict(r) for r in repositories], indent=2, ensure_ascii=False), encoding="utf-8"
        )
        (output_dir / "duplicate_classes.json").write_text(
            json.dumps(subsystem_relevant_dups, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        print()
        print(format_output_location("JSON output", output_dir, directory=True))

    if args.format in ("markdown", "both"):
        md = generate_markdown(
            gaps,
            entities,
            repositories,
            subsystem_relevant_dups,
            len(classes),
            external_db_snapshot=external_db_snapshot,
        )
        (output_dir / "gap_matrix.md").write_text(md, encoding="utf-8")
        print(format_output_location("Markdown output", output_dir / "gap_matrix.md"))


if __name__ == "__main__":
    main()
