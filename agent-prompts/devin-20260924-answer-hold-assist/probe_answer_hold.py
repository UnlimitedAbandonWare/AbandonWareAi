#!/usr/bin/env python3
"""Read-only live anchors for the 2026-09-24 answer-hold Devin assist.

Prints one JSON document. Does not edit source, leases, indexes, or config.
Does not read the private instruction body.

Exit 0: report written. With --fail-on-open, no open or regressed finding.
Exit 1: --fail-on-open and an editable finding is still open or regressed.
Exit 2: a required source file is missing, or --self-test failed.
Exit 3: --fail-on-open and the only remaining open findings sit on a live lease.
gradleProof is always not-run. A green probe is not a Gradle pass.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.devin-20260924-answer-hold-probe.v1"

REQUIRED = (
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java",
    "main/java/com/example/lms/orchestration/control/RagControlProjectionRenderer.java",
    "main/java/com/example/lms/service/NoEvidenceChatFallback.java",
    "main/java/com/example/lms/security/AdminTokenGuardInterceptor.java",
    "main/java/com/example/lms/security/AdminTokenGuardFilter.java",
    "main/java/com/example/lms/boot/RuntimeConfigGuard.java",
    "main/java/com/example/lms/config/AppSecurityConfig.java",
    "main/java/com/example/lms/config/CustomSecurityConfig.java",
    "main/java/com/example/lms/LmsApplication.java",
    "main/resources/static/js/chat.js",
)

WATCH = REQUIRED + (
    "main/java/com/example/lms/security/ChatOpenSecurityConfig.java",
    "main/java/com/example/lms/orchestration/control/RagControlPresentationBoundary.java",
    "main/resources/application-meta-display.yml",
    "main/resources/application-local.yml",
    "main/resources/application.yml",
    "src/test/java",
    "src/chatUiTest/java",
    "AGENTS.md",
    "docs/PROJECT_STATUS.md",
    ".agents/skills-intent-index.yaml",
)

PRIVATE_INSTRUCTIONS = (
    "C:/Users/nninn/Downloads/Devin_Fix_Instructions_20260924.md",
    "C:/Users/nninn/Downloads/Devin_Fix_Instructions_20260924.txt",
    "C:/Users/nninn/Downloads/02_Source_Evidence.md",
)

PLUGIN_LANE = [
    {"order": 1, "plugin": "Superpowers", "use": "systematic-debugging, one cause, failing test, then verification-before-completion"},
    {"order": 2, "plugin": "Browser", "use": "fresh session for greeting hold, backend_unavailable, admin login, bad login, logout"},
    {"order": 3, "plugin": "GitHub", "use": "read-only after local HEAD matches remote SHA; no commit, push, or merge"},
    {"order": 4, "plugin": "Exa", "use": "official docs only: docs.spring.io, playwright.dev, docs.github.com, docs.ollama.com"},
    {"order": 5, "plugin": "AWX Control Tower", "use": "build_error_mine on a real compile or test failure log only"},
    {"order": 6, "plugin": "Computer", "use": "only when Browser cannot reach localhost or the Windows console"},
    {"order": 7, "plugin": "glm_worker", "use": "after the first patch, adversarial review only; agreement is not a pass"},
]
PLUGIN_EXCLUDED = [
    "Supabase",
    "Data",
    "Wolfram",
    "SciSpace",
    "Sites",
    "Meta Wearables Webapp",
    "AWX Control Tower (recovery)",
]


def read_text(root: Path, rel: str) -> str | None:
    path = root / rel
    if not path.is_file():
        return None
    return path.read_text(encoding="utf-8", errors="replace")


def line_of(text: str | None, needle: str) -> int | None:
    if text is None or needle not in text:
        return None
    return text.count("\n", 0, text.find(needle)) + 1


def slice_from(text: str | None, start: str, ends: tuple[str, ...]) -> str | None:
    if text is None or start not in text:
        return None
    at = text.find(start)
    tail = text[at + len(start):]
    stop = len(text)
    for needle in ends:
        found = tail.find(needle)
        if found >= 0:
            stop = min(stop, at + len(start) + found)
    return text[at:stop]


def parse_time(value: str | None) -> datetime | None:
    if not value:
        return None
    raw = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(raw)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def norm(path: str) -> str:
    return path.replace("\\", "/").strip().lower()


def lease_rows(root: Path, now: datetime) -> list[dict]:
    base = root / "__patch_drop__" / "source-edit-locks"
    rows = []
    if not base.is_dir():
        return rows
    for lease_path in sorted(base.glob("*/lease.json")):
        try:
            data = json.loads(lease_path.read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError):
            rows.append({
                "topic": lease_path.parent.name,
                "status": "corrupt",
                "expiresAtUtc": None,
                "targetPaths": [],
            })
            continue
        expires = data.get("expiresAtUtc") or data.get("expiresAt")
        expiry = parse_time(expires if isinstance(expires, str) else None)
        if expiry is None:
            status = "unknown"
        elif expiry > now:
            status = "active"
        else:
            status = "expired"
        targets = data.get("targetPaths") or []
        rows.append({
            "topic": data.get("topic") or lease_path.parent.name,
            "status": status,
            "expiresAtUtc": expires,
            "targetPaths": [str(item) for item in targets],
        })
    return rows


def blocks_for(watch: tuple[str, ...], leases: list[dict]) -> list[dict]:
    wanted = {norm(path): path for path in watch}
    blocks = []
    for lease in leases:
        if lease["status"] not in {"active", "expired", "corrupt", "unknown"}:
            continue
        for target in lease["targetPaths"]:
            key = norm(target)
            matched = wanted.get(key)
            if matched is None:
                for candidate_key, candidate in wanted.items():
                    if candidate_key.startswith(key + "/") or key.startswith(candidate_key + "/"):
                        matched = candidate
                        break
            if matched is None:
                continue
            blocks.append({
                "path": matched,
                "leasePath": target,
                "topic": lease["topic"],
                "status": lease["status"],
                "expiresAtUtc": lease["expiresAtUtc"],
                "editAllowed": False,
            })
    return blocks


def journal_overlaps(root: Path, watch: tuple[str, ...]) -> list[dict]:
    base = root / "data" / "agent-handoff" / "codex-autonomy"
    rows = []
    if not base.is_dir():
        return rows
    wanted = [norm(path) for path in watch]
    for journal_path in sorted(base.glob("*/journal.json")):
        try:
            if journal_path.stat().st_size > 2_000_000:
                continue
            data = json.loads(journal_path.read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError):
            continue
        if data.get("status") != "in_progress":
            continue
        raw_scope = data.get("plannedScope") or []
        scopes = []
        for item in raw_scope:
            scopes.extend(part.strip() for part in str(item).split(",") if part.strip())
        hits = []
        for scope in scopes:
            key = norm(scope)
            for candidate in wanted:
                if candidate == key or candidate.startswith(key + "/") or key.startswith(candidate + "/"):
                    hits.append(candidate)
        if not hits:
            continue
        rows.append({
            "taskId": data.get("taskId") or journal_path.parent.name,
            "agent": data.get("agent"),
            "purpose": str(data.get("purpose") or "")[:160],
            "paths": sorted(set(hits)),
            "lease": False,
        })
    return rows


def editable(blocks: list[dict], *rels: str) -> bool:
    blocked = {norm(row["path"]) for row in blocks}
    return not any(norm(rel) in blocked for rel in rels)


def pack(finding_id, status, files, detail, blocks, fail=True) -> dict:
    return {
        "id": finding_id,
        "status": status,
        "files": list(files),
        "editAllowed": editable(blocks, *files),
        "failOnOpen": fail and status in {"open", "regressed"},
        "detail": detail,
    }


def build(root: Path) -> dict:
    now = datetime.now(timezone.utc)
    texts = {rel: read_text(root, rel) for rel in REQUIRED}
    missing = [rel for rel in REQUIRED if texts[rel] is None]
    workflow = texts.get(REQUIRED[0])
    adapter = texts.get(REQUIRED[1])
    renderer = texts.get(REQUIRED[2])
    fallback = texts.get(REQUIRED[3])
    guard = texts.get(REQUIRED[4])
    runtime_guard = texts.get(REQUIRED[6])
    app_security = texts.get(REQUIRED[7])
    custom_security = texts.get(REQUIRED[8])
    chat_js = texts.get(REQUIRED[10])
    meta_yml = read_text(root, "main/resources/application-meta-display.yml")

    leases = lease_rows(root, now)
    blocks = blocks_for(WATCH, leases)
    verify_body = slice_from(
        adapter,
        "private RagControlFinding verificationFinding",
        ("\n    private RagControlFinding finalFinding",),
    )
    policy_body = slice_from(
        workflow,
        "static FinalVerificationReleaseDecision applyEvidenceReleasePolicy",
        ("\n    static boolean detectLateUnattributedEvidence",),
    )
    future_body = slice_from(
        workflow,
        "boolean futureTech = latestTechEnabled",
        ("\n        // plan hints", "\n        if (planHints"),
    )
    replay_body = slice_from(
        chat_js,
        "function renderSelectionEntropyTrace",
        ("\nfunction clearSelectionEntropyTrace",),
    )
    prehandle_body = slice_from(
        guard,
        "public boolean preHandle",
        ("\n    private static String extractPresentedHeaderToken",),
    )
    secrets_body = slice_from(
        runtime_guard,
        "private static void checkRequiredSecrets",
        ("\n    private static void checkOnnx",),
    )
    release_record = slice_from(
        workflow,
        "record FinalVerificationReleaseDecision",
        ("\n    static ChatResult sanitizeFallbackResult",),
    )

    findings = []

    held = renderer is not None and "HELD_NOTICE" in renderer and "heldNotice()" in renderer
    findings.append(pack(
        "hold-notice-kept",
        "preserve" if held else "regressed",
        ["main/java/com/example/lms/orchestration/control/RagControlProjectionRenderer.java"],
        "The server owns the hold sentence. Keep heldNotice. Do not delete it and do not replace it with a prompt change.",
        blocks,
    ))

    greeting = fallback is not None and "isGreeting(" in fallback and "\\uC548\\uB155" in fallback
    findings.append(pack(
        "greeting-fallback-exists",
        "preserve" if greeting else "regressed",
        ["main/java/com/example/lms/service/NoEvidenceChatFallback.java"],
        "Greeting fallback already exists. Do not add a second greeting service.",
        blocks,
    ))

    not_required_split = bool(
        verify_body
        and "verification_outcome_missing" in verify_body
        and ("verification_not_required" in verify_body or "verificationNotRequired" in verify_body)
    )
    findings.append(pack(
        "verification-not-required-collapsed",
        "closed" if not_required_split else "open",
        ["main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java"],
        "Close when verificationFinding keeps verification_outcome_missing and also contains verification_not_required for the not-required branch.",
        blocks,
    ))

    fallback_lock = False
    if policy_body and "if (priorFallbackApplied)" in policy_body:
        branch = policy_body.split("if (priorFallbackApplied)", 1)[1]
        next_if = branch.find("\n        if (")
        head = branch if next_if < 0 else branch[:next_if]
        compact = " ".join(head.split())
        fallback_lock = "return new FinalVerificationReleaseDecision" in compact and "false, false" in compact
    findings.append(pack(
        "prior-fallback-locks-release",
        "open" if fallback_lock or policy_body is None else "closed",
        ["main/java/com/example/lms/service/ChatWorkflow.java"],
        "priorFallbackApplied must not by itself set releaseAllowed false. Unsafe fallbacks can still be held.",
        blocks,
    ))

    forced_web = bool(future_body and "useWeb = true" in future_body and "&& useWeb" not in future_body.split("{", 1)[0])
    findings.append(pack(
        "explicit-search-off-reenabled",
        "open" if forced_web or future_body is None else "closed",
        ["main/java/com/example/lms/service/ChatWorkflow.java"],
        "A future-tech heuristic must not set useWeb true after the caller already turned web search off.",
        blocks,
    ))

    memory_split = bool(release_record and "knowledgeWriteAllowed" in release_record)
    findings.append(pack(
        "release-and-memory-coupled",
        "closed" if memory_split else "open",
        ["main/java/com/example/lms/service/ChatWorkflow.java"],
        "FinalVerificationReleaseDecision currently has evidencePolicyApplied, not knowledgeWriteAllowed. Add the split without renaming the existing field out from under callers.",
        blocks,
    ))

    replay_gate = bool(replay_body and any(
        token in replay_body
        for token in ("diagnosticsEnabled", "showSelectionReplay", "selectionReplayEnabled", "detailDiagnostics")
    ))
    findings.append(pack(
        "selection-replay-ungated",
        "closed" if replay_gate else "open",
        ["main/resources/static/js/chat.js"],
        "Default chat hides the selection replay table. Show it only when the user turned diagnostics on. Close token is one of diagnosticsEnabled, showSelectionReplay, selectionReplayEnabled, detailDiagnostics inside renderSelectionEntropyTrace.",
        blocks,
    ))

    admin_session = bool(prehandle_body and (
        "SecurityContextHolder" in prehandle_body or "authenticatedAdministrator" in prehandle_body
    ))
    findings.append(pack(
        "admin-form-login-ignored",
        "closed" if admin_session else "open",
        [
            "main/java/com/example/lms/security/AdminTokenGuardInterceptor.java",
            "main/java/com/example/lms/security/AdminTokenGuardFilter.java",
        ],
        "Form-login admin Authentication is not an allow condition. Skip these files while a lease lists them. Do not delete the header-token path.",
        blocks,
    ))

    bootstrap_line = "security.bootstrap-admin.password" in (secrets_body or "")
    bootstrap_gated = bool(secrets_body and "security.bootstrap-admin.enabled" in secrets_body)
    findings.append(pack(
        "bootstrap-password-always-required",
        "closed" if bootstrap_gated or not bootstrap_line else "open",
        ["main/java/com/example/lms/boot/RuntimeConfigGuard.java"],
        "Require the bootstrap secret only when security.bootstrap-admin.enabled is on. A normal restart after the account exists must boot without that secret.",
        blocks,
    ))

    always = []
    if app_security and ".alwaysRemember(true)" in app_security:
        always.append("main/java/com/example/lms/config/AppSecurityConfig.java")
    if custom_security and ".alwaysRemember(true)" in custom_security:
        always.append("main/java/com/example/lms/config/CustomSecurityConfig.java")
    findings.append(pack(
        "always-remember-ignores-checkbox",
        "open" if always else "closed",
        always or [
            "main/java/com/example/lms/config/AppSecurityConfig.java",
            "main/java/com/example/lms/config/CustomSecurityConfig.java",
        ],
        "Both security configs contain alwaysRemember(true). The checkbox has to match the cookie that is issued.",
        blocks,
    ))

    findings.append(pack(
        "operator-ui-without-admin-principal",
        "deferred-to-lease",
        [
            "main/java/com/example/lms/security/AdminTokenGuardInterceptor.java",
            "main/java/com/example/lms/security/ChatOpenSecurityConfig.java",
        ],
        "User ask: a non-admin can use the desktop operator screens. Wrong credentials still must not become the admin principal. CSRF stays. This finding does not fail the probe. The live lease owner edits the guard.",
        blocks,
        fail=False,
    ))

    findings.append(pack(
        "conversate-12s-timeout-hypothesis",
        "hypothesis",
        ["main/resources/application-meta-display.yml"],
        "total-timeout-ms 12000 is present"
        if meta_yml and "total-timeout-ms: 12000" in meta_yml
        else "12s conversate timeout was not observed in application-meta-display.yml",
        blocks,
        fail=False,
    ))

    anchors = []
    needles = (
        ("main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java", adapter, "verification_outcome_missing"),
        ("main/java/com/example/lms/service/ChatWorkflow.java", workflow, "NOT_REQUIRED"),
        ("main/java/com/example/lms/service/ChatWorkflow.java", workflow, "if (priorFallbackApplied)"),
        ("main/java/com/example/lms/service/ChatWorkflow.java", workflow, "useWeb = true"),
        ("main/java/com/example/lms/service/ChatWorkflow.java", workflow, "record FinalVerificationReleaseDecision"),
        ("main/java/com/example/lms/service/NoEvidenceChatFallback.java", fallback, "isGreeting("),
        ("main/resources/static/js/chat.js", chat_js, "function renderSelectionEntropyTrace"),
        ("main/java/com/example/lms/security/AdminTokenGuardInterceptor.java", guard, "public boolean preHandle"),
        ("main/java/com/example/lms/boot/RuntimeConfigGuard.java", runtime_guard, "security.bootstrap-admin.password"),
        ("main/java/com/example/lms/config/AppSecurityConfig.java", app_security, ".alwaysRemember(true)"),
        ("main/java/com/example/lms/config/CustomSecurityConfig.java", custom_security, ".alwaysRemember(true)"),
        ("main/java/com/example/lms/LmsApplication.java", texts.get(REQUIRED[9]), "createIfAbsent(\"admin\""),
    )
    for rel, text, needle in needles:
        anchors.append({
            "file": rel,
            "needle": needle,
            "line": line_of(text, needle),
            "present": line_of(text, needle) is not None,
        })

    return {
        "schemaVersion": SCHEMA,
        "gradleProof": "not-run",
        "generatedAtUtc": now.isoformat(),
        "root": str(root),
        "missingRequired": missing,
        "activeSource": {
            "javaRoot": "main/java",
            "testSourceSet": "src/test/java",
            "chatUiTestSourceSet": "src/chatUiTest/java",
            "inactiveTreePresent": {
                "src/src/test/java": (root / "src/src/test/java").is_dir(),
                "src/src/chatUiTest/java": (root / "src/src/chatUiTest/java").is_dir(),
            },
            "note": "Gradle test srcDir is src/test/java. src/src/test is not that sourceSet. Do not patch only the inactive tree.",
        },
        "privateInstructionPresent": {
            path: Path(path).is_file() for path in PRIVATE_INSTRUCTIONS
        },
        "videoReportPresent": (root / "docs/video-analysis-report-20260924-143134.md").is_file(),
        "pluginLane": PLUGIN_LANE,
        "pluginExcluded": PLUGIN_EXCLUDED,
        "leaseBlocks": blocks,
        "journalOverlaps": journal_overlaps(root, WATCH),
        "anchors": anchors,
        "findings": findings,
        "openFindingIds": [row["id"] for row in findings if row["status"] in {"open", "regressed"}],
    }


def self_test(report: dict) -> dict:
    required_keys = {
        "schemaVersion", "gradleProof", "missingRequired", "activeSource",
        "pluginLane", "pluginExcluded", "leaseBlocks", "journalOverlaps",
        "anchors", "findings", "openFindingIds", "privateInstructionPresent",
    }
    failures = []
    if report.get("schemaVersion") != SCHEMA:
        failures.append("schema")
    if report.get("gradleProof") != "not-run":
        failures.append("gradleProof")
    if not required_keys <= set(report):
        failures.append("keys")
    if report.get("missingRequired"):
        failures.append("missing-required")
    if not isinstance(report.get("findings"), list) or len(report["findings"]) < 8:
        failures.append("findings")
    ids = [row.get("id") for row in report.get("findings", [])]
    for needed in (
        "verification-not-required-collapsed",
        "prior-fallback-locks-release",
        "selection-replay-ungated",
        "admin-form-login-ignored",
        "greeting-fallback-exists",
        "hold-notice-kept",
    ):
        if needed not in ids:
            failures.append("missing-" + needed)
    return {"selfTest": "ok" if not failures else "failed", "failures": failures}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--fail-on-open", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, OSError):
        pass
    root = Path(args.root).resolve()
    report = build(root)
    if args.self_test:
        verdict = self_test(report)
        print(json.dumps(verdict, ensure_ascii=True))
        return 0 if verdict["selfTest"] == "ok" else 2
    print(json.dumps(report, ensure_ascii=True, indent=2))
    if not args.fail_on_open:
        return 0
    if report["missingRequired"]:
        return 2
    editable_open = [
        row for row in report["findings"]
        if row["failOnOpen"] and row["editAllowed"]
    ]
    leased_open = [
        row for row in report["findings"]
        if row["failOnOpen"] and not row["editAllowed"]
    ]
    if editable_open:
        return 1
    if leased_open:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
