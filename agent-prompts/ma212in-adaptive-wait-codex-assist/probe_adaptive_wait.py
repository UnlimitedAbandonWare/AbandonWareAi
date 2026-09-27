#!/usr/bin/env python3
"""Read-only live anchors for the ma212in adaptive-wait Codex assist.

Prints one JSON document. Does not edit source, leases, indexes, or config.
Exit 0: report written to stdout.
Exit 1: --fail-on-open and a finding is still open.
Exit 2: a required source file is missing.
gradleProof is always not-run. A green probe is not a Gradle pass.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.ma212in-adaptive-wait-probe.v1"

REQUIRED = (
    "main/resources/static/js/chat.js",
    "main/resources/application.yml",
    "main/java/com/example/lms/api/PublicRequestBudgetGuard.java",
    "main/java/com/example/lms/llm/RequestedModelTimeoutPolicy.java",
    "main/java/com/example/lms/llm/DynamicChatModelFactory.java",
    "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/api/ChatApiController.java",
    "main/java/com/example/lms/llm/OllamaNativeChatModel.java",
    "main/java/ai/abandonware/nova/boot/exec/CancelShieldFuture.java",
    "scripts/dev_reload_watch.ps1",
    "scripts/debug_rag_stack.ps1",
    "configs/api-routing.yaml",
)

WATCH = (
    "main/java/com/example/lms/llm/DynamicChatModelFactory.java",
    "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/api/PublicRequestBudgetGuard.java",
    "main/java/com/example/lms/api/ChatApiController.java",
    "main/java/com/example/lms/llm/TimedChatModelCaller.java",
    "main/java/com/example/lms/llm/RequestedModelTimeoutPolicy.java",
    "main/java/com/example/lms/llm/OllamaNativeChatModel.java",
    "main/java/com/example/lms/llm/ModelSelectionException.java",
    "main/java/com/example/lms/service/chat/ChatRunRegistry.java",
    "main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java",
    "main/resources/static/js/chat.js",
    "main/resources/application.yml",
    "scripts/dev_reload_watch.ps1",
    "scripts/debug_rag_stack.ps1",
    "scripts/start_rag_stack.ps1",
    "scripts/chat_ui_vibe_listener.ps1",
    "scripts/agent_git_vibe_commit.py",
    "scripts/conditional_local_git.py",
    "scripts/test_conditional_local_git.py",
    "scripts/test_agent_git_vibe_commit.py",
    "AGENTS.md",
    "docs/PROJECT_STATUS.md",
    ".agents/skills-intent-index.yaml",
    ".grok/rules/demo1-bridge.md",
    ".grok/rules/demo1-conditional-local-git.md",
    "src/test/java/com/example/lms/api/PublicRequestBudgetGuardTest.java",
    "src/test/java/com/example/lms/api/PublicRequestBudgetProjectionFocusedTest.java",
    "src/test/js/chat-failure-classification.test.cjs",
    "src/test/js/chat-failure-recovery.test.cjs",
    "src/test/java/com/example/lms/api/ChatWorkflowAgentDbMirrorFocusedTest.java",
    "src/test/java/com/example/lms/debug/ApiFailureRecorderTest.java",
    "src/test/java/com/example/lms/infra/resilience/NightmareBreakerIdentityFocusedTest.java",
    "src/test/java/com/example/lms/llm/NamedChatModelFocusedTest.java",
)

CLASS_CANDIDATES = (
    "build/classes/java/main/com/example/lms/api/ChatSessionDetailResponseBuilder.class",
    "build/desktop/classes/java/main/com/example/lms/api/ChatSessionDetailResponseBuilder.class",
)


def read_text(root: Path, rel: str) -> str | None:
    path = root / rel
    if not path.is_file():
        return None
    return path.read_text(encoding="utf-8", errors="replace")


def line_of(text: str | None, needle: str) -> int | None:
    if text is None:
        return None
    index = text.find(needle)
    if index < 0:
        return None
    return text.count("\n", 0, index) + 1


def anchor(rel: str, text: str | None, needle: str) -> dict:
    line = line_of(text, needle)
    return {
        "file": rel,
        "needle": needle,
        "line": line,
        "present": line is not None,
    }


def function_body(text: str | None, signature: str) -> str | None:
    if text is None:
        return None
    start = text.find(signature)
    if start < 0:
        return None
    next_private = text.find("\n    private ", start + len(signature))
    next_public = text.find("\n    public ", start + len(signature))
    ends = [n for n in (next_private, next_public) if n >= 0]
    end = min(ends) if ends else len(text)
    return text[start:end]


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
            if key in wanted:
                blocks.append({
                    "path": wanted[key],
                    "leasePath": target,
                    "topic": lease["topic"],
                    "status": lease["status"],
                    "expiresAtUtc": lease["expiresAtUtc"],
                    "editAllowed": False,
                })
    return blocks


def journal_overlaps(root: Path, watch: tuple[str, ...]) -> list[dict]:
    base = root / "data" / "agent-handoff" / "codex-autonomy"
    wanted = {norm(path): path for path in watch}
    rows = []
    if not base.is_dir():
        return rows
    for journal_path in sorted(base.glob("*/journal.json")):
        try:
            if journal_path.stat().st_size > 2_000_000:
                continue
            data = json.loads(journal_path.read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError):
            continue
        if data.get("status") != "in_progress":
            continue
        scope = [str(item) for item in (data.get("plannedScope") or [])]
        hits = []
        for item in scope:
            key = norm(item).rstrip("/")
            for watch_norm, watch_path in wanted.items():
                if watch_norm == key or watch_norm.startswith(key + "/"):
                    hits.append(watch_path)
        if not hits:
            continue
        rows.append({
            "taskId": data.get("taskId"),
            "agent": data.get("agent"),
            "status": "in_progress",
            "meaning": "unconfirmed-still-running",
            "purpose": data.get("purpose"),
            "paths": sorted(set(hits)),
        })
    return rows


def llm_routes(text: str | None) -> list[dict]:
    if text is None:
        return []
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        if line.strip() == "llm:":
            start = index + 1
            break
    if start is None:
        return []
    routes = []
    current = None
    for line in lines[start:]:
        if line and not line.startswith(" ") and not line.startswith("#"):
            break
        if line.startswith("    - id:"):
            current = {"id": line.split(":", 1)[1].strip().strip('"').strip("'")}
            routes.append(current)
        elif current is not None and line.startswith("      tier:"):
            current["tier"] = line.split(":", 1)[1].strip().strip('"').strip("'")
        elif current is not None and "model_pref:" in line:
            current["modelPref"] = line.split(":", 1)[1].strip()
    return routes


def class_files(root: Path) -> list[dict]:
    rows = []
    for rel in CLASS_CANDIDATES:
        path = root / rel
        item = {"path": rel, "present": path.is_file()}
        if path.is_file():
            modified = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
            item["modifiedAtUtc"] = modified.isoformat()
        rows.append(item)
    return rows


def tail_contains(path: Path, needle: str, max_bytes: int = 200_000) -> bool | None:
    if not path.is_file():
        return None
    data = path.read_bytes()
    chunk = data[-max_bytes:]
    return needle.encode("utf-8") in chunk


def build(root: Path) -> tuple[dict, list[str]]:
    missing = [rel for rel in REQUIRED if not (root / rel).is_file()]
    texts = {rel: read_text(root, rel) for rel in REQUIRED}
    now = datetime.now(timezone.utc)
    chat = texts.get("main/resources/static/js/chat.js")
    yml = texts.get("main/resources/application.yml")
    policy = texts.get("main/java/com/example/lms/llm/RequestedModelTimeoutPolicy.java")
    factory = texts.get("main/java/com/example/lms/llm/DynamicChatModelFactory.java")
    aspect = texts.get("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java")
    workflow = texts.get("main/java/com/example/lms/service/ChatWorkflow.java")
    controller = texts.get("main/java/com/example/lms/api/ChatApiController.java")
    native = texts.get("main/java/com/example/lms/llm/OllamaNativeChatModel.java")
    shield = texts.get("main/java/ai/abandonware/nova/boot/exec/CancelShieldFuture.java")
    reload = texts.get("scripts/dev_reload_watch.ps1")
    debug = texts.get("scripts/debug_rag_stack.ps1")
    routing = texts.get("configs/api-routing.yaml")

    primary_body = function_body(factory, "private static Duration localPrimaryTimeout")
    shared_line = line_of(factory, "boolean sharedLocalFailover")
    exact_guard = False
    cloud_required = False
    if factory and shared_line:
        window = "\n".join(factory.splitlines()[shared_line - 1:shared_line + 3])
        exact_guard = "!exactSelection" in window
        cloud_required = "cloudFallbackEnabled()" in window

    catch_line = line_of(controller, "catch (Throwable lifecycleFailure)")
    linkage_near_catch = False
    if controller and catch_line:
        window = "\n".join(controller.splitlines()[catch_line - 1:catch_line + 40])
        linkage_near_catch = "LinkageError" in window or "NoClassDefFoundError" in window

    routes = llm_routes(routing)
    exa_llm = any("exa" in (route.get("id") or "").lower() for route in routes)
    fast = next((route for route in routes if route.get("id") == "ollama_fast"), None)

    leases = lease_rows(root, now)
    blocks = blocks_for(WATCH, leases)
    blocked_paths = {item["path"] for item in blocks}

    budget_open = (
        line_of(chat, "const STREAM_SERVER_EVIDENCE_BUDGET_MS = 120000;") is not None
        and line_of(policy, "DEFAULT_REQUESTED_TIMEOUT_SECONDS = 180;") is not None
        and line_of(workflow, 'TraceStore.put("llm.call.timeout.cappedByRequestBudget", true);') is not None
        and line_of(yml, "max-time-budget-ms: ${PUBLIC_REQUEST_MAX_TIME_BUDGET_MS:120000}") is not None
    )
    div3_factory = primary_body is not None and "remaining / 3" in primary_body
    div3_aspect = line_of(aspect, "remainingMillis() / 3") is not None
    marker_missing = reload is not None and "last-compile-failed.json" not in reload
    verify_sees_marker = debug is not None and "last-compile-failed.json" in debug

    findings = [
        {
            "id": "budget-shorter-than-model-timeout",
            "status": "open" if budget_open else "closed",
            "edit": "Tell the user the real remaining budget, or fail early with an existing budget code. Keep the public 120000 cap. Do not treat ownerKey or a client header as a 600000 grant.",
            "anchors": [
                anchor("main/resources/static/js/chat.js", chat, "const STREAM_SERVER_EVIDENCE_BUDGET_MS = 120000;"),
                anchor("main/resources/application.yml", yml, "max-time-budget-ms: ${PUBLIC_REQUEST_MAX_TIME_BUDGET_MS:120000}"),
                anchor("main/java/com/example/lms/llm/RequestedModelTimeoutPolicy.java", policy, "DEFAULT_REQUESTED_TIMEOUT_SECONDS = 180;"),
                anchor("main/java/com/example/lms/service/ChatWorkflow.java", workflow, 'TraceStore.put("llm.call.timeout.cappedByRequestBudget", true);'),
            ],
        },
        {
            "id": "primary-timeout-div3-factory",
            "status": "open" if div3_factory else "closed",
            "appliesToExactSelection": False if exact_guard else None,
            "requiresCloudFallback": True if cloud_required else False,
            "edit": "Do not turn on shared failover just to escape a slow exact model. That path divides the primary wait by 3. Reserve alternate time only when an alternate candidate exists on a different resource group.",
            "anchors": [
                anchor("main/java/com/example/lms/llm/DynamicChatModelFactory.java", factory, "boolean sharedLocalFailover"),
                anchor("main/java/com/example/lms/llm/DynamicChatModelFactory.java", factory, "remaining / 3"),
            ],
        },
        {
            "id": "primary-timeout-div3-aspect",
            "status": "open" if div3_aspect else "closed",
            "editAllowed": "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java" not in blocked_paths,
            "edit": "Same divide-by-3 boundary in LlmRouterAspect. Skip the file when editAllowed is false.",
            "anchors": [
                anchor("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java", aspect, "remainingMillis() / 3"),
            ],
        },
        {
            "id": "strict-selection-still-sent",
            "status": "preserve",
            "edit": "Keep strictModelSelection true for existing clients. Add an explicit prefer-selected field only for the new congestion mode. Do not silently flip this boolean.",
            "anchors": [
                anchor("main/resources/static/js/chat.js", chat, "strictModelSelection: true"),
            ],
        },
        {
            "id": "linkage-error-collapsed",
            "status": "open" if catch_line and not linkage_near_catch else "closed",
            "edit": "Classify LinkageError and NoClassDefFoundError in the stream lifecycle catch. Do not leave them as generic stream_failed. Put the new test in a new file.",
            "anchors": [
                anchor("main/java/com/example/lms/api/ChatApiController.java", controller, "catch (Throwable lifecycleFailure)"),
            ],
        },
        {
            "id": "compile-failure-marker-missing",
            "status": "open" if marker_missing and not verify_sees_marker else "closed",
            "edit": "On compile failure, record var/dev-reload/last-compile-failed.json and make debug verify a hard failure while it exists. Remove the marker only after a successful compile. Do not delete class files on port 18180.",
            "anchors": [
                anchor("scripts/dev_reload_watch.ps1", reload, "compile-failed"),
                anchor("scripts/debug_rag_stack.ps1", debug, "freshness"),
            ],
        },
        {
            "id": "native-stream-false",
            "status": "later",
            "edit": "Ollama native chat still posts stream false. Do not pretend chunked final text is upstream streaming. Leave this until the open findings above are closed.",
            "anchors": [
                anchor("main/java/com/example/lms/llm/OllamaNativeChatModel.java", native, 'payload.put("stream", false);'),
            ],
        },
        {
            "id": "cancel-shield-preserved",
            "status": "preserve",
            "edit": "CancelShieldFuture still downgrades cancel(true) to cancel(false). Do not delete it. A failed screen is not proof the worker exited.",
            "anchors": [
                anchor("main/java/ai/abandonware/nova/boot/exec/CancelShieldFuture.java", shield, "delegate.cancel(false)"),
            ],
        },
        {
            "id": "exa-is-not-an-llm-route",
            "status": "preserve" if not exa_llm else "open",
            "edit": "Exa is an agent research plugin. Do not add it as a chat model. If the selected local model cannot run at all, one existing free_local fast route is the first fallback, and only for a new or unavailable request. A slow exact request stays on the selected model until its real budget ends.",
            "llmRoutes": routes,
            "fastRoute": fast,
        },
    ]

    for finding in findings:
        paths = [item["file"] for item in finding.get("anchors") or []]
        finding["leaseBlockedPaths"] = sorted(path for path in paths if path in blocked_paths)

    owner_hits = list((root / "main" / "java").rglob("OwnerKeyBootstrapFilter.java")) if (root / "main" / "java").is_dir() else []
    owner_rel = None
    if owner_hits:
        owner_rel = owner_hits[0].relative_to(root).as_posix()

    report = {
        "schemaVersion": SCHEMA,
        "root": str(root),
        "checkedAtUtc": now.isoformat(),
        "gradleProof": "not-run",
        "authority": "live checkout. Re-run this probe before each patch. Instruction-pack line numbers lose.",
        "missingRequired": missing,
        "findings": findings,
        "leaseBlocks": blocks,
        "journalOverlaps": journal_overlaps(root, WATCH),
        "classFiles": class_files(root),
        "compileFailureMarker": {
            "path": "var/dev-reload/last-compile-failed.json",
            "present": (root / "var" / "dev-reload" / "last-compile-failed.json").is_file(),
        },
        "devReloadTail": {
            "log": "var/dev-reload/dev-reload.log",
            "compileFailedSeenInLast200KB": tail_contains(root / "var" / "dev-reload" / "dev-reload.log", "compile-failed"),
        },
        "ownerKeyBootstrap": {
            "present": owner_rel is not None,
            "file": owner_rel,
            "meaning": "An owner key issued to an anonymous visitor is not permission for a 600s public budget.",
        },
        "openFindingIds": [item["id"] for item in findings if item["status"] == "open"],
    }
    return report, missing


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only ma212in adaptive-wait probe")
    parser.add_argument("--root", default=".")
    parser.add_argument("--fail-on-open", action="store_true")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    if not (root / "gradlew.bat").is_file():
        print(json.dumps({"schemaVersion": SCHEMA, "error": "project-root-required"}, ensure_ascii=False))
        return 2
    report, missing = build(root)
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    if missing:
        return 2
    if args.fail_on_open and report["openFindingIds"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
