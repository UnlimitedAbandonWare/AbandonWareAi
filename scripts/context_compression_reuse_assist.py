"""Read-only assist for the context-compression reuse brief.

Scanner: scripts/pair_brief_assist.py.
Extra commands: scope, hypothesis, defer, focused-present, product-gate, next.
Stdlib only. No network, Gradle, server, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
Gemini summary wiring stays DEFER until the defer card is fully set.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as engine

SCHEMA = "awx.context-compression-reuse-assist.v1"
DEFAULT_SPEC = "var/codex-assist-context-compression-reuse-20261007/spec.json"
PACK = "var/codex-assist-context-compression-reuse-20261007"
HYPO_REL = PACK + "/hypothesis.json"
DEFER_REL = PACK + "/gemini-option.json"
RED_REL = PACK + "/red-boundary.json"
JOURNAL_ROOT = "data/agent-handoff/codex-autonomy"
BOUNDARIES = (
    "span-loss",
    "negation-flip",
    "numeric-change",
    "session-mix",
    "summary-promoted",
)
PRODUCT = (
    "main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java",
    "main/java/ai/abandonware/nova/config/NovaOrchestrationProperties.java",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/service/ChatHistoryServiceImpl.java",
    "main/java/com/example/lms/service/rag/handler/MemoryHandler.java",
    "main/java/com/example/lms/prompt/StandardPromptBuilder.java",
    "main/java/com/example/lms/service/rag/pre/LongInputDistillationService.java",
    "main/java/ai/abandonware/nova/orch/aop/ChunkRollingSummaryAspect.java",
    "main/java/com/example/lms/learning/gemini/GeminiGateway.java",
    "main/java/com/example/lms/llm/DynamicChatModelFactory.java",
)
GEMINI = (
    "main/java/com/example/lms/service/rag/pre/LongInputDistillationService.java",
    "main/java/ai/abandonware/nova/orch/aop/ChunkRollingSummaryAspect.java",
    "main/java/com/example/lms/learning/gemini/GeminiGateway.java",
    "main/java/com/example/lms/llm/DynamicChatModelFactory.java",
)
FOCUSED = (
    "src/test/java/ai/abandonware/nova/orch/compress/DynamicContextCompressorTest.java",
    "src/test/java/com/example/lms/service/ChatHistoryServiceImplConversationMemoryTest.java",
    "src/test/java/com/example/lms/service/ChatHistoryRollingWatermarkTest.java",
    "src/test/java/com/example/lms/service/rag/handler/MemoryHandlerTest.java",
    "src/test/java/com/example/lms/prompt/StandardPromptBuilderConversationHistoryTest.java",
    "src/test/java/com/example/lms/prompt/StandardPromptBuilderEvidenceMetadataTest.java",
)
HOT = tuple(path.casefold() for path in PRODUCT + FOCUSED)
GEMINI_FOLDED = tuple(path.casefold() for path in GEMINI)
TEST_FOLDED = tuple(path.casefold() for path in FOCUSED)
DEFER_FLAGS = ("redReproduced", "leaseClear", "consentConfirmed", "registryConfirmed")
LIST_CAP = 256


def base(command: str, status: str):
    return {
        "schemaVersion": SCHEMA,
        "command": command,
        "status": status,
        "productPass": False,
        "gradleRan": False,
        "networkUsed": False,
        "reclaim": False,
        "forceRelease": False,
    }


def emit(report):
    print(json.dumps(report, ensure_ascii=False, indent=2))


def norm(path: str) -> str:
    return str(path).replace("\\", "/").lstrip("./").casefold()


def path_hit(entry: str, hot: str) -> bool:
    ent = norm(entry)
    if ent == hot or ent.endswith("/" + hot):
        return True
    last = ent.rsplit("/", 1)[-1]
    if "." not in last and (hot == ent or hot.startswith(ent + "/")):
        return True
    return False


def read_json(path: Path):
    try:
        if path.is_symlink():
            raise engine.AssistError("symlink-refused")
        if path.stat().st_size > 2_000_000:
            raise engine.AssistError("file-too-large")
        return json.loads(path.read_text(encoding="utf-8"))
    except engine.AssistError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise engine.AssistError("json-unreadable") from exc


def user_file(root: Path, text: str) -> Path:
    raw = Path(text)
    if raw.is_absolute():
        engine.reject_name(raw.name)
        path = raw
    else:
        path = engine.under_root(root, text)
    if path.is_symlink() or not path.is_file():
        raise engine.AssistError("file-missing")
    if path.stat().st_size > 1_000_000:
        raise engine.AssistError("file-too-large")
    return path


def load_card(root: Path, rel: str):
    data = read_json(user_file(root, rel) if Path(rel).is_absolute() else engine.under_root(root, rel))
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        raise engine.AssistError("spec-schema")
    return data


def cmd_scope(root: Path):
    overlaps = []
    journals = []
    unscoped = []
    skipped = 0
    lock_root = root / "__patch_drop__" / "source-edit-locks"
    if lock_root.is_dir() and not lock_root.is_symlink():
        for child in sorted(lock_root.iterdir()):
            if not child.is_dir() or child.name == "waiters" or child.is_symlink():
                continue
            lease_path = child / "lease.json"
            if not lease_path.is_file() or lease_path.is_symlink():
                continue
            data = read_json(lease_path)
            if not isinstance(data, dict):
                raise engine.AssistError("json-unreadable")
            raw_paths = data.get("targetPaths") or []
            if not isinstance(raw_paths, list):
                raise engine.AssistError("json-unreadable")
            hits = []
            for item in raw_paths:
                if not isinstance(item, str):
                    continue
                hits.extend(hot for hot in HOT if path_hit(item, hot) and hot not in hits)
            if hits:
                overlaps.append({
                    "kind": "lease",
                    "topic": data.get("topic"),
                    "ownerId": data.get("ownerId"),
                    "expiresAtUtc": data.get("expiresAtUtc"),
                    "paths": hits,
                })
    journal_root = root / JOURNAL_ROOT
    if journal_root.is_dir() and not journal_root.is_symlink():
        for journal_path in sorted(journal_root.glob("*/journal.json")):
            if journal_path.is_symlink():
                continue
            try:
                size = journal_path.stat().st_size
            except OSError:
                skipped += 1
                continue
            if size > 2_000_000:
                skipped += 1
                continue
            try:
                data = json.loads(journal_path.read_text(encoding="utf-8"))
            except (OSError, UnicodeError, json.JSONDecodeError):
                skipped += 1
                continue
            if not isinstance(data, dict) or data.get("status") != "in_progress":
                continue
            scope = data.get("plannedScope")
            if not isinstance(scope, list) or not scope:
                unscoped.append(data.get("taskId"))
                continue
            hits = []
            for item in scope:
                if not isinstance(item, str):
                    continue
                hits.extend(hot for hot in HOT if path_hit(item, hot) and hot not in hits)
            if hits:
                journals.append({
                    "kind": "journal",
                    "taskId": data.get("taskId"),
                    "agent": data.get("agent"),
                    "paths": hits,
                })
    blocked = []
    for row in overlaps + journals:
        for item in row["paths"]:
            if item not in blocked:
                blocked.append(item)
    free = [item for item in HOT if item not in blocked]
    if skipped:
        status, code = "SCAN_PARTIAL", 4
    elif overlaps or journals:
        status, code = "OVERLAP", 7
    else:
        status, code = "CLEAR", 0
    report = base("scope", status)
    report["leaseOverlaps"] = overlaps
    report["journalOverlaps"] = journals
    report["unscopedJournals"] = unscoped
    report["skippedJournals"] = skipped
    report["blockedPaths"] = blocked
    report["freePaths"] = free
    report["listCapNote"] = (
        "work_journal.py list --active keeps only the first "
        + str(LIST_CAP)
        + " path-sorted journal files. This command reads in_progress journals directly."
    )
    report["note"] = (
        "OVERLAP lists leased paths and in_progress plannedScope hits. "
        "A journal hit is not a lease. Do not close it and do not force-release. "
        "unscopedJournals have an empty plannedScope, so they were not classified. "
        "expiresAtUtc is the stored value. This command does not reclaim."
    )
    return report, code


def cmd_hypothesis(root: Path, rel: str):
    data = load_card(root, rel)
    items = data.get("items")
    if not isinstance(items, list):
        raise engine.AssistError("spec-shape")
    found = {}
    for item in items:
        if not isinstance(item, dict):
            raise engine.AssistError("spec-shape")
        item_id = item.get("id")
        status = item.get("status")
        if item_id not in BOUNDARIES or status not in ("PENDING", "ACTIVE", "CLOSED"):
            raise engine.AssistError("spec-shape")
        if item_id in found:
            raise engine.AssistError("spec-shape")
        found[item_id] = status
    if set(found) != set(BOUNDARIES):
        raise engine.AssistError("spec-shape")
    active = [item_id for item_id, status in found.items() if status == "ACTIVE"]
    if len(active) > 1:
        status, code = "HYPOTHESIS_SPREAD", 3
    elif len(active) == 1:
        status, code = "ONE_ACTIVE", 0
    else:
        status, code = "NONE", 0
    report = base("hypothesis", status)
    report["active"] = active
    report["note"] = (
        "At most one meaning hypothesis may be ACTIVE. "
        "Shorter text is not a hypothesis. Gemini wiring is not a hypothesis."
    )
    return report, code


def defer_state(root: Path, rel: str):
    data = load_card(root, rel)
    status = data.get("status")
    if status not in ("DEFER", "ADOPT"):
        raise engine.AssistError("spec-shape")
    flags = {}
    for key in DEFER_FLAGS:
        value = data.get(key)
        if not isinstance(value, bool):
            raise engine.AssistError("spec-shape")
        flags[key] = value
    allowed = status == "ADOPT" and all(flags.values())
    return status, flags, allowed


def cmd_defer(root: Path, rel: str):
    status, flags, allowed = defer_state(root, rel)
    if status == "DEFER":
        label, code = "DEFER", 0
    elif allowed:
        label, code = "ADOPT_ALLOWED", 0
    else:
        label, code = "GEMINI_BEFORE_RED", 3
    report = base("defer", label)
    report["option"] = status
    report["flags"] = flags
    report["note"] = (
        "DEFER means do not start a Gemini summary work package. "
        "ADOPT_ALLOWED still is not a product PASS and still needs a RED-pinned owner file. "
        "Key presence is not send consent."
    )
    return report, code


def red_pin(root: Path, rel: str):
    path = Path(rel) if Path(rel).is_absolute() else root / rel
    if not path.is_file():
        return None
    data = read_json(path)
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        return {"invalid": True}
    if data.get("status") != "RED_PINNED":
        return {"invalid": True}
    boundary = data.get("boundary")
    allow = data.get("allowPaths")
    if boundary not in BOUNDARIES or not isinstance(allow, list) or not 1 <= len(allow) <= 4:
        return {"invalid": True}
    cleaned = []
    for item in allow:
        if not isinstance(item, str):
            return {"invalid": True}
        folded = norm(item)
        if folded not in HOT or not folded.startswith("main/java/"):
            return {"invalid": True}
        cleaned.append(folded)
    return {"invalid": False, "boundary": boundary, "allowPaths": cleaned}


def classify(path: str):
    folded = norm(path)
    if (
        "assets/display/" in folded
        or "novafocus" in folded
        or folded.endswith("meta/index.html")
        or "/conversate/" in folded
    ):
        return "display"
    if folded in GEMINI_FOLDED:
        return "gemini"
    if folded in TEST_FOLDED:
        return "test"
    if folded.startswith("src/test/"):
        return "extra-test"
    if folded.startswith("main/java/") or folded.startswith("main/resources/"):
        return "product"
    return None


def cmd_product_gate(root: Path, diff_text: str, defer_rel: str, red_rel: str):
    kinds = []
    for _index, path, _text in engine.added_lines(diff_text):
        if not path:
            continue
        kind = classify(path)
        row = (kind, norm(path))
        if kind and row not in kinds:
            kinds.append(row)
    pinned = red_pin(root, red_rel)
    _status, _flags, allowed = defer_state(root, defer_rel)
    product = [path for kind, path in kinds if kind == "product"]
    gemini = [path for kind, path in kinds if kind == "gemini"]
    if any(kind == "display" for kind, _path in kinds):
        status, code = "DISPLAY_SURFACE", 3
    elif any(kind == "extra-test" for kind, _path in kinds):
        status, code = "EXTRA_TEST", 3
    elif gemini and not allowed:
        status, code = "GEMINI_DEFER", 3
    elif pinned and pinned.get("invalid"):
        status, code = "INVALID_RED", 3
    elif (product or gemini) and (pinned is None or pinned.get("invalid")):
        status, code = "PRODUCT_BEFORE_RED", 3
    elif any(item not in (pinned or {}).get("allowPaths", []) for item in product + gemini):
        status, code = "BOUNDARY_SPREAD", 3
    elif product or gemini:
        status, code = "PRODUCT_SCOPED", 0
    elif any(kind == "test" for kind, _path in kinds):
        status, code = "TEST_ONLY", 0
    else:
        status, code = "ASSIST_ONLY", 0
    report = base("product-gate", status)
    report["paths"] = [{"kind": kind, "path": path} for kind, path in kinds]
    report["deferAllowed"] = allowed
    report["redPinned"] = bool(pinned and not pinned.get("invalid"))
    report["note"] = (
        "TEST_ONLY may add fixtures in the six named tests. "
        "PRODUCT_BEFORE_RED blocks main edits until one RED_PINNED owner file exists. "
        "GEMINI_DEFER blocks the four Gemini files until the defer card is ADOPT with every flag true. "
        "DISPLAY_SURFACE stays on its own owner. This is not a product PASS."
    )
    return report, code


def cmd_focused_present(root: Path, init: str | None):
    missing = []
    for rel in FOCUSED:
        path = engine.under_root(root, rel)
        if path.is_symlink() or not path.is_file():
            missing.append(rel)
    init_status = "UNCHECKED"
    if init:
        init_path = Path(init)
        engine.reject_name(init_path.name)
        if init_path.is_symlink():
            raise engine.AssistError("symlink-refused")
        init_status = "PRESENT" if init_path.is_file() else "ABSENT"
    status, code = ("BASELINE_BLOCKED", 3) if missing else ("PRESENT", 0)
    report = base("focused-present", status)
    report["missing"] = missing
    report["init"] = init_status
    report["note"] = (
        "PRESENT means the six focused test files exist. Gradle was not run. "
        "init ABSENT means the focused Gradle helper is missing: mark BASELINE_BLOCKED, not a meaning RED."
    )
    return report, code


def cmd_next(root: Path):
    scope, _scope_code = cmd_scope(root)
    hypo, hypo_code = cmd_hypothesis(root, HYPO_REL)
    defer, _defer_code = cmd_defer(root, DEFER_REL)
    engine.SCHEMA = SCHEMA
    spec = engine.load_spec(engine.under_root(root, DEFAULT_SPEC))
    pin_report, _pin_code = engine.cmd_pin(root, spec)
    cover_report, _cover_code = engine.cmd_cover(root, spec)
    gaps = []
    for row in cover_report.get("files") or []:
        for token in row.get("tokens") or []:
            if token.get("status") == "MISSING":
                gaps.append({"path": row.get("path"), "id": token.get("id")})
    drifted = [
        row.get("path")
        for row in pin_report.get("files") or []
        if row.get("sha12Status") == "DRIFT"
    ]
    pinned = red_pin(root, RED_REL)
    if hypo_code == 3:
        status, code = "HYPOTHESIS_SPREAD", 3
    elif pin_report.get("status") == "CONTRACT_GAP":
        status, code = "NEXT_REREAD", 0
    elif scope.get("status") == "SCAN_PARTIAL":
        status, code = "NEXT_WAIT", 0
    elif scope.get("blockedPaths") and scope.get("freePaths"):
        status, code = "NEXT_SPLIT", 0
    elif scope.get("blockedPaths"):
        status, code = "NEXT_WAIT", 0
    elif drifted or pin_report.get("status") == "ANCHOR_STALE":
        status, code = "NEXT_REREAD", 0
    else:
        status, code = "NEXT_READY", 0
    report = base("next", status)
    report["pin"] = pin_report.get("status")
    report["drifted"] = drifted
    report["cover"] = cover_report.get("status")
    report["missingCover"] = gaps
    report["scope"] = scope.get("status")
    report["blockedPaths"] = scope.get("blockedPaths")
    report["freePaths"] = scope.get("freePaths")
    report["defer"] = defer.get("status")
    report["hypothesis"] = hypo.get("status")
    report["active"] = hypo.get("active")
    report["redPinned"] = bool(pinned and not pinned.get("invalid"))
    report["note"] = (
        "NEXT_SPLIT means edit only freePaths. Blocked paths stay with their journal or lease. "
        "Missing cover ids are fixture phrases, not proof the behavior is absent. "
        "A shorter summary is not RED. Do not explain session413 from this brief. "
        "Gemini stays DEFER until the defer card says ADOPT_ALLOWED. "
        "Product edits stay blocked until one RED_PINNED boundary exists. "
        "ANCHOR_STALE means re-read. Do not restore the pinned hash."
    )
    return report, code


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = list(sys.argv[1:] if argv is None else argv)
    if not args:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    cmd = args[0]
    if cmd in ("pin", "cover", "diff-forbid"):
        engine.SCHEMA = SCHEMA
        if "--spec" not in args:
            args = [cmd, "--spec", DEFAULT_SPEC, *args[1:]]
        return engine.main(args)
    parser = argparse.ArgumentParser(description="context compression reuse assist")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("scope"))
    hypo = add_root(sub.add_parser("hypothesis"))
    hypo.add_argument("--file", default=HYPO_REL)
    defer = add_root(sub.add_parser("defer"))
    defer.add_argument("--file", default=DEFER_REL)
    gate = add_root(sub.add_parser("product-gate"))
    gate.add_argument("--diff", required=True)
    gate.add_argument("--defer-file", default=DEFER_REL)
    gate.add_argument("--red-file", default=RED_REL)
    present = add_root(sub.add_parser("focused-present"))
    present.add_argument("--init")
    add_root(sub.add_parser("next"))
    parsed = parser.parse_args(args)
    known = ("scope", "hypothesis", "defer", "product-gate", "focused-present", "next")
    if parsed.cmd not in known:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    try:
        root = Path(parsed.root).resolve()
        if parsed.cmd == "scope":
            report, code = cmd_scope(root)
        elif parsed.cmd == "hypothesis":
            report, code = cmd_hypothesis(root, parsed.file)
        elif parsed.cmd == "defer":
            report, code = cmd_defer(root, parsed.file)
        elif parsed.cmd == "product-gate":
            diff_path = user_file(root, parsed.diff)
            report, code = cmd_product_gate(
                root,
                diff_path.read_text(encoding="utf-8", errors="replace"),
                parsed.defer_file,
                parsed.red_file,
            )
        elif parsed.cmd == "focused-present":
            report, code = cmd_focused_present(root, parsed.init)
        else:
            report, code = cmd_next(root)
        emit(report)
        return code
    except engine.AssistError as exc:
        emit({
            "schemaVersion": SCHEMA,
            "status": "error",
            "reason": exc.reason,
            "productPass": False,
            "reclaim": False,
            "forceRelease": False,
        })
        return 2


if __name__ == "__main__":
    sys.exit(main())
