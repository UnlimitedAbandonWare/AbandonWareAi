"""Read-only assist for the /chat conversation JSON+ZIP export brief.

Scanner: scripts/pair_brief_assist.py.
Stdlib only. No network, Gradle, server, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

import pair_brief_assist as engine

SCHEMA = "awx.session-context-export-json-zip-assist.v1"
DEFAULT_SPEC = "var/codex-assist-session-context-export-20261007/spec.json"
PACK = "var/codex-assist-session-context-export-20261007"
HYPO_REL = PACK + "/hypothesis.json"
RED_REL = PACK + "/red-boundary.json"
CAPS_REL = PACK + "/caps.json"
LIST_CAP = 256
JOURNAL_HEAD = 8192
BOUNDARIES = (
    "ownership-before-read",
    "fence-not-update",
    "same-bytes",
)
BRIEF_PATHS = (
    "main/java/com/example/lms/api/chatapicontroller.java",
    "main/java/com/example/lms/api/chatconversationexportsupport.java",
    "main/java/com/example/lms/service/chathistoryservice.java",
    "main/java/com/example/lms/service/chathistoryserviceimpl.java",
    "main/java/com/example/lms/repository/chatmessagerepository.java",
    "main/java/com/example/lms/repository/chatsessionrepository.java",
    "main/java/com/example/lms/service/understanding/understandingreceiptrepository.java",
    "main/java/com/example/lms/repository/attachmentsourcerepository.java",
    "main/java/com/example/lms/service/attachmentsourcestore.java",
    "main/resources/templates/chat-ui.html",
    "main/resources/static/js/chat.js",
    "main/resources/static/js/chat-conversation-export.js",
    "main/resources/static/css/chat-style.css",
    "src/chatuitest/java/com/example/lms/api/chatconversationexportcontracttest.java",
    "src/test/js/chat-conversation-export.test.cjs",
)
NEW_FILES = (
    "main/java/com/example/lms/api/ChatConversationExportSupport.java",
    "src/chatUiTest/java/com/example/lms/api/ChatConversationExportContractTest.java",
    "main/resources/static/js/chat-conversation-export.js",
    "src/test/js/chat-conversation-export.test.cjs",
)
HOT = set(BRIEF_PATHS)
TEST_EXACT = {
    "src/chatuitest/java/com/example/lms/api/chatconversationexportcontracttest.java",
    "src/test/js/chat-conversation-export.test.cjs",
}
FROZEN_NAMES = {
    "chattracebundleresponsebuilder.java",
    "chat-trace-ui.js",
    "chatuiviewconfig.java",
    "chatrunregistry.java",
    "tracesnapshotstore.java",
    "debugeventstore.java",
    "saferedactor.java",
    "settings.gradle.kts",
}
AGENT_NAMES = {
    "chat_session_debug_export.py",
    "task_context.py",
    "session_context_lane_board.py",
    "win_export_name_adversarial.py",
}
WATCH = set(BRIEF_PATHS) | {
    "main/java/com/example/lms/api/chattracebundleresponsebuilder.java",
    "main/java/com/example/lms/service/chat/chatrunregistry.java",
    "main/java/com/example/lms/config/chatuiviewconfig.java",
    "main/resources/static/js/chat-trace-ui.js",
    "docs/project_status.md",
    "settings.gradle.kts",
}
FOCUSED_EXISTING = (
    "src/chatUiTest/java/com/example/lms/config/ChatUiViewConfigFocusedTest.java"
)
VIEW_TEST = "scripts/chat_ui_view_layer_contract_tests.js"
VIEW_JAVA = "main/java/com/example/lms/config/ChatUiViewConfig.java"
LANE_BUNDLE = "main/java/com/example/lms/api/ChatTraceBundleResponseBuilder.java"
LANE_AGENT = "scripts/chat_session_debug_export.py"
LANE_BOARD = "scripts/session_context_lane_board.py"
LANE_PACK = "var/codex-assist-grok-session-context"
SCOPE_RE = re.compile(r'"plannedScope"\s*:\s*\[(.*?)\]', re.S)
QUOTED_RE = re.compile(r'"([^"\\]+)"')
MASKS = (
    ("scan-is-pass", re.compile(r"productPass\s*[:=]\s*true|scan exit 0|스캔.{0,12}통과")),
    ("browser-done", re.compile(r"다운로드 완료|browser download PASS")),
    ("view-layer-fixed", re.compile(r"view-layer.{0,24}(PASS|fixed|고쳐)", re.I)),
    ("zip-library", re.compile(r"JSZip|fflate|zip\.js")),
    ("atomic-both", re.compile(r"atomic snapshot|전역 atomic", re.I)),
    ("force-release", re.compile(r"force-release|force release|강제 해제")),
    ("restore-sha", re.compile(r"restore the (paste|pinned) hash|SHA12.{0,24}되돌", re.I)),
)
CAP_KEYS = (
    "conversation-export",
    "conversationexport",
    "export-options",
    "sessionsexports",
)


def base(command, status):
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


def norm(path):
    return str(path).replace("\\", "/").lstrip("./").casefold()


def overlaps(left, right):
    one = norm(left).rstrip("/")
    two = norm(right).rstrip("/")
    if not one or not two:
        return False
    return one == two or one.startswith(two + "/") or two.startswith(one + "/")


def enrich(report):
    report["schemaVersion"] = SCHEMA
    report["productPass"] = False
    report["gradleRan"] = False
    report["networkUsed"] = False
    report["reclaim"] = False
    report["forceRelease"] = False
    return report


def load_spec(root):
    engine.SCHEMA = SCHEMA
    return engine.load_spec(engine.under_root(root, DEFAULT_SPEC))


def read_json(path):
    if path.is_symlink() or not path.is_file():
        raise engine.AssistError("file-missing")
    if path.stat().st_size > 1_000_000:
        raise engine.AssistError("file-too-large")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise engine.AssistError("json-unreadable") from exc


def user_file(root, text):
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


def file_exists(root, rel):
    path = root.joinpath(*rel.split("/"))
    return path.is_file() and not path.is_symlink()


def cmd_pin(root):
    report, code = engine.cmd_pin(root, load_spec(root))
    enrich(report)
    report["note"] = (
        "FRESH means the pinned seams and SHA12 values matched. "
        "ANCHOR_STALE means re-read the file. Do not restore the paste or pin hash. "
        "This is not a product PASS."
    )
    return report, code


def cmd_cover(root):
    report, code = engine.cmd_cover(root, load_spec(root))
    enrich(report)
    report["note"] = (
        "MISSING_FILE means a named test file is not on disk yet. "
        "SEARCH_GAP means a fixture phrase is absent in that file, not that the behavior is absent. "
        "COVERED is not a green test."
    )
    return report, code


def cmd_diff(root, diff_text):
    report, code = engine.cmd_diff(load_spec(root), diff_text)
    enrich(report)
    return report, code


def expired_at(text):
    if not isinstance(text, str) or not text:
        return None
    try:
        stamp = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    if stamp.tzinfo is None:
        return None
    return stamp <= datetime.now(timezone.utc)


def lease_rows(root):
    lock_root = root / "__patch_drop__" / "source-edit-locks"
    rows = []
    corrupt = []
    if not lock_root.is_dir() or lock_root.is_symlink():
        return rows, corrupt
    for child in sorted(lock_root.iterdir()):
        if not child.is_dir() or child.name == "waiters" or child.is_symlink():
            continue
        if not child.name.endswith(".lock"):
            continue
        lease_path = child / "lease.json"
        if not lease_path.is_file() or lease_path.is_symlink():
            corrupt.append(child.name)
            continue
        try:
            data = json.loads(lease_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            corrupt.append(child.name)
            continue
        if not isinstance(data, dict):
            corrupt.append(child.name)
            continue
        raw_paths = data.get("targetPaths") or []
        if not isinstance(raw_paths, list):
            corrupt.append(child.name)
            continue
        paths = [norm(item) for item in raw_paths if isinstance(item, str)]
        topic = str(data.get("topic") or "")
        owner = str(data.get("ownerId") or "")
        pid = data.get("ownerProcessId")
        rows.append({
            "topic": topic,
            "ownerId": owner,
            "expiresAtUtc": data.get("expiresAtUtc"),
            "expired": expired_at(data.get("expiresAtUtc")),
            "ownerProcessId": pid if isinstance(pid, int) else None,
            "processZeroIsNotDead": pid == 0,
            "ownerLease": topic.casefold() == "context-export"
            or owner.casefold() == "codex-context-export",
            "siblingLease": (
                ("context-export" in topic.casefold() or "context-export" in owner.casefold())
                and not (
                    topic.casefold() == "context-export"
                    or owner.casefold() == "codex-context-export"
                )
            ),
            "paths": paths,
        })
    return rows, corrupt


def journal_rows(root):
    base = root / "data" / "agent-handoff" / "codex-autonomy"
    found = []
    count = 0
    if not base.is_dir() or base.is_symlink():
        return count, found
    paths = sorted(
        path for path in base.glob("*/journal.json")
        if path.is_file() and not path.is_symlink()
    )
    count = len(paths)
    for index, path in enumerate(paths):
        try:
            head = path.read_bytes()[:JOURNAL_HEAD].decode("utf-8", "replace")
        except OSError:
            continue
        if '"status": "in_progress"' not in head and '"status":"in_progress"' not in head:
            continue
        task_match = re.search(r'"taskId"\s*:\s*"([^"]+)"', head)
        agent_match = re.search(r'"agent"\s*:\s*"([^"]+)"', head)
        purpose_match = re.search(r'"purpose"\s*:\s*"([^"]*)"', head)
        scope_match = SCOPE_RE.search(head)
        planned = QUOTED_RE.findall(scope_match.group(1)) if scope_match else []
        task_id = task_match.group(1) if task_match else path.parent.name
        purpose = purpose_match.group(1) if purpose_match else ""
        product_scope = any(
            overlaps(item, needle) for item in planned for needle in BRIEF_PATHS
        )
        owner = ("canonical JSON ZIP" in purpose) or (
            task_id.casefold().startswith("context-export-") and product_scope
        )
        sibling_journal = (not owner) and task_id.casefold().startswith("context-export-")
        found.append({
            "taskId": task_id,
            "agent": agent_match.group(1) if agent_match else "",
            "purpose": purpose[:160],
            "plannedScope": planned,
            "listIndex": index,
            "pastListCap": index >= LIST_CAP,
            "ownerJournal": owner,
            "siblingJournal": sibling_journal,
            "scopeUnreadable": scope_match is None,
        })
    return count, found


def hits_for(planned, needles):
    matched = []
    for item in planned:
        for needle in needles:
            if overlaps(item, needle) and needle not in matched:
                matched.append(needle)
    return matched


def cmd_scope(root):
    leases, corrupt = lease_rows(root)
    journal_count, journals = journal_rows(root)
    owners = [row for row in journals if row.get("ownerJournal")]
    foreign_leases = []
    owner_leases = []
    sibling_leases = []
    for row in leases:
        covered = [path for path in WATCH if any(overlaps(path, item) for item in row["paths"])]
        slim = {
            "topic": row["topic"],
            "ownerId": row["ownerId"],
            "expiresAtUtc": row["expiresAtUtc"],
            "expired": row["expired"],
            "ownerProcessId": row["ownerProcessId"],
            "processZeroIsNotDead": row["processZeroIsNotDead"],
            "paths": covered,
        }
        if row["ownerLease"]:
            owner_leases.append(slim)
        elif row.get("siblingLease"):
            sibling_leases.append(slim)
        elif covered:
            foreign_leases.append(slim)
    foreign_journals = []
    project_status = []
    for row in journals:
        if row.get("ownerJournal"):
            continue
        covered = hits_for(row["plannedScope"], WATCH)
        holds_status = any(overlaps(item, "docs/PROJECT_STATUS.md") for item in row["plannedScope"])
        if holds_status:
            project_status.append(row["taskId"])
        if not covered and not holds_status:
            continue
        foreign_journals.append({
            "taskId": row["taskId"],
            "agent": row["agent"],
            "listIndex": row["listIndex"],
            "pastListCap": row["pastListCap"],
            "paths": covered,
        })
    owner_cover = []
    for row in owners:
        owner_cover.extend(hits_for(row["plannedScope"], BRIEF_PATHS))
    owner_set = set(owner_cover)
    foreign_cover = set()
    for row in foreign_journals:
        foreign_cover.update(row["paths"])
    blocked = [path for path in BRIEF_PATHS if path in foreign_cover and path not in owner_set]
    outside = [
        path for path in BRIEF_PATHS
        if path not in owner_set and path not in foreign_cover
    ]
    missed = [
        row["taskId"] for row in journals
        if row.get("pastListCap") and (
            row.get("ownerJournal")
            or row["taskId"] in {item["taskId"] for item in foreign_journals}
            or row["taskId"] in project_status
        )
    ]
    if corrupt:
        status, code = "SCAN_PARTIAL", 0
    elif foreign_leases:
        status, code = "OVERLAP", 7
    elif len(owners) > 1:
        status, code = "OWNER_AMBIGUOUS", 3
    elif owner_leases or owners:
        status, code = "OWNER_LEASE", 0
    elif foreign_journals:
        status, code = "JOURNAL_NOTED", 0
    else:
        status, code = "CLEAR", 0
    report = base("scope", status)
    report["journalCount"] = journal_count
    report["listCap"] = LIST_CAP
    report["listActiveMissed"] = missed
    report["ownerLeases"] = owner_leases
    report["siblingLeases"] = sibling_leases
    report["foreignLeases"] = foreign_leases
    report["siblingJournals"] = [
        {
            "taskId": row["taskId"],
            "agent": row["agent"],
            "listIndex": row["listIndex"],
            "pastListCap": row["pastListCap"],
        }
        for row in journals if row.get("siblingJournal")
    ]
    report["corruptLocks"] = corrupt
    report["ownerJournals"] = [
        {
            "taskId": row["taskId"],
            "agent": row["agent"],
            "listIndex": row["listIndex"],
            "pastListCap": row["pastListCap"],
            "paths": hits_for(row["plannedScope"], BRIEF_PATHS),
        }
        for row in owners
    ]
    report["foreignJournals"] = foreign_journals
    report["projectStatusJournals"] = project_status
    report["ownerPaths"] = sorted(owner_set)
    report["blockedPaths"] = blocked
    report["outsideOwnerJournal"] = outside
    report["note"] = (
        "OWNER_LEASE is the product owner. Do not edit those paths from this assist, "
        "and do not force-release or reclaim the lock. ownerProcessId 0 is not a dead owner. "
        "An expired lock still blocks a new overlapping acquisition. "
        "work_journal.py list --active keeps only the first 256 sorted journal directories, "
        "so a later in_progress journal is invisible there. "
        "blockedPaths are on a foreign journal and not on the export journal. "
        "outsideOwnerJournal paths are in the brief and on no current journal; "
        "widen the owner journal before editing them. "
        "This command does not classify stale and does not reclaim."
    )
    return report, code


def cmd_hypothesis(root, rel):
    path = user_file(root, rel) if Path(rel).is_absolute() else engine.under_root(root, rel)
    data = read_json(path)
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        raise engine.AssistError("spec-schema")
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
        "At most one hypothesis may be ACTIVE. "
        "A shorter ZIP is not a hypothesis. "
        "Do not open the per-answer bundle, the agent debug export, and the new snapshot in one patch."
    )
    return report, code


def red_pin(root, rel):
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
        if folded not in HOT or not (
            folded.startswith("main/java/") or folded.startswith("main/resources/")
        ):
            return {"invalid": True}
        cleaned.append(folded)
    return {"invalid": False, "boundary": boundary, "allowPaths": cleaned}


def classify(path):
    folded = norm(path)
    name = folded.rsplit("/", 1)[-1]
    if name in FROZEN_NAMES:
        return "frozen"
    if name in AGENT_NAMES:
        return "agent"
    if "/assets/display/" in folded or folded.endswith("meta/index.html") or "novafocus" in folded:
        return "display"
    if folded in TEST_EXACT:
        return "test"
    if folded.startswith("src/test/") or folded.startswith("src/chatuitest/"):
        return "extra-test"
    if folded.startswith("main/java/") or folded.startswith("main/resources/"):
        return "product"
    return None


def cmd_product_gate(root, diff_text, red_rel):
    kinds = []
    for _index, path, _text in engine.added_lines(diff_text):
        if not path:
            continue
        kind = classify(path)
        row = (kind, norm(path))
        if kind and row not in kinds:
            kinds.append(row)
    pinned = red_pin(root, red_rel)
    product = [path for kind, path in kinds if kind == "product"]
    if any(kind == "frozen" for kind, _path in kinds):
        status, code = "FROZEN_BUNDLE", 3
    elif any(kind == "agent" for kind, _path in kinds):
        status, code = "AGENT_LANE", 3
    elif any(kind == "display" for kind, _path in kinds):
        status, code = "DISPLAY_SURFACE", 3
    elif any(kind == "extra-test" for kind, _path in kinds):
        status, code = "EXTRA_TEST", 3
    elif product and pinned and pinned.get("invalid"):
        status, code = "INVALID_RED", 3
    elif product and pinned is None:
        status, code = "PRODUCT_BEFORE_RED", 3
    elif any(item not in (pinned or {}).get("allowPaths", []) for item in product):
        status, code = "BOUNDARY_SPREAD", 3
    elif product:
        status, code = "PRODUCT_SCOPED", 0
    elif any(kind == "test" for kind, _path in kinds):
        status, code = "TEST_ONLY", 0
    else:
        status, code = "ASSIST_ONLY", 0
    report = base("product-gate", status)
    report["paths"] = [{"kind": kind, "path": path} for kind, path in kinds]
    report["redPinned"] = bool(pinned and not pinned.get("invalid"))
    report["note"] = (
        "TEST_ONLY may add the two new test files before a product edit. "
        "PRODUCT_BEFORE_RED blocks main edits until one RED_PINNED card exists. "
        "FROZEN_BUNDLE keeps the single-answer ZIP, ChatRunRegistry, the view-layer class, "
        "and settings.gradle.kts. AGENT_LANE keeps the agent debug export scripts. "
        "This is not a product PASS."
    )
    return report, code


def cmd_absent(root):
    rows = [{"path": rel, "present": file_exists(root, rel)} for rel in NEW_FILES]
    present = [row["path"] for row in rows if row["present"]]
    if len(present) == len(rows):
        status = "ALL_PRESENT"
    elif present:
        status = "PARTIAL"
    else:
        status = "EXPECTED_ABSENT"
    report = base("absent", status)
    report["files"] = rows
    report["note"] = (
        "EXPECTED_ABSENT is the open state. "
        "ALL_PRESENT means the files exist. It is not GREEN and not a product PASS."
    )
    return report, 0


def cmd_lanes(root):
    bundle = root.joinpath(*LANE_BUNDLE.split("/"))
    bundle_ok = False
    if bundle.is_file() and not bundle.is_symlink() and bundle.stat().st_size <= engine.MAX_BYTES:
        bundle_ok = "awx.answer-trace-bundle.v1" in bundle.read_text(encoding="utf-8", errors="replace")
    agent_ok = file_exists(root, LANE_AGENT)
    board_ok = file_exists(root, LANE_BOARD)
    pack = root.joinpath(*LANE_PACK.split("/"))
    pack_ok = pack.is_dir() and not pack.is_symlink()
    new_rows = [{"path": rel, "present": file_exists(root, rel)} for rel in NEW_FILES]
    old_ok = bundle_ok and agent_ok and (board_ok or pack_ok)
    new_any = any(row["present"] for row in new_rows)
    if not old_ok:
        status, code = "LANE_GAP", 3
    elif new_any:
        status, code = "NEW_FILES_PRESENT", 0
    else:
        status, code = "LANES_SEPARATE", 0
    report = base("lanes", status)
    report["perAnswerBundle"] = bundle_ok
    report["agentDebugExport"] = agent_ok
    report["grokSessionContext"] = board_ok or pack_ok
    report["newFiles"] = new_rows
    report["note"] = (
        "Three existing lanes stay separate from the new /chat snapshot: "
        "the per-answer awx.answer-trace-bundle.v1 ZIP, "
        "scripts/chat_session_debug_export.py, "
        "and the grok session-context board. "
        "Do not point the new button at those lanes. "
        "NEW_FILES_PRESENT is not a product PASS."
    )
    return report, code


def cmd_caps(root):
    path = engine.under_root(root, CAPS_REL)
    data = read_json(path)
    if data.get("schemaVersion") != SCHEMA or data.get("singleAnswerCapMutable") is not False:
        raise engine.AssistError("spec-shape")
    proposals = data.get("proposals")
    if not isinstance(proposals, dict):
        raise engine.AssistError("spec-shape")
    hits = []
    resource_root = root / "main" / "resources"
    if resource_root.is_dir() and not resource_root.is_symlink():
        for candidate in sorted(resource_root.rglob("application*.yml")) + sorted(
            resource_root.rglob("application*.yaml")
        ):
            if not candidate.is_file() or candidate.is_symlink():
                continue
            if candidate.stat().st_size > 1_000_000:
                continue
            text = candidate.read_text(encoding="utf-8", errors="replace").casefold()
            folded = norm(candidate.relative_to(root))
            for key in CAP_KEYS:
                if key in text.replace("-", "").replace("_", "") or key in text:
                    hits.append({"path": folded, "key": key})
    status = "LIVE_KEY" if hits else "PROPOSAL_ONLY"
    report = base("caps", status)
    report["singleAnswerUncompressedCapBytes"] = data.get("singleAnswerUncompressedCapBytes")
    report["singleAnswerCapMutable"] = False
    report["proposals"] = proposals
    report["liveKeys"] = hits
    report["note"] = (
        "PROPOSAL_ONLY means the brief caps are not live configuration. "
        "Do not raise the existing 262144-byte single-answer bundle cap. "
        "64MiB retained payload is not a JVM heap cap. "
        "LIVE_KEY reports a file and key id only."
    )
    return report, 0


def cmd_baseline(root):
    test_path = engine.under_root(root, VIEW_TEST)
    java_path = engine.under_root(root, VIEW_JAVA)
    if test_path.is_symlink() or java_path.is_symlink():
        raise engine.AssistError("symlink-refused")
    test_text = test_path.read_text(encoding="utf-8", errors="replace")
    java_text = java_path.read_text(encoding="utf-8", errors="replace")
    assertion = (
        "renderModelSelect" in test_text
        and "ChatUiViewConfig should render chat-ui model and CSRF placeholders" in test_text
    )
    model_present = "renderModelSelect" in java_text
    csrf_present = "renderCsrfMeta" in java_text
    if not assertion:
        status, code = "ASSERTION_WEAKENED", 3
    elif model_present:
        status, code = "CAUSE_MOVED", 0
    else:
        status, code = "BASELINE_OPEN", 0
    report = base("baseline-keep", status)
    report["assertionKept"] = assertion
    report["renderCsrfMetaPresent"] = csrf_present
    report["renderModelSelectPresent"] = model_present
    report["nodeRan"] = False
    report["note"] = (
        "BASELINE_OPEN is the known node contract gap: the test requires renderModelSelect "
        "and ChatUiViewConfig does not define it. Do not add that method to finish this export. "
        "Do not weaken the assertion. Node was not run."
    )
    return report, code


def cmd_focused(root):
    present = file_exists(root, FOCUSED_EXISTING)
    status, code = ("PRESENT", 0) if present else ("BASELINE_BLOCKED", 3)
    report = base("focused-present", status)
    report["path"] = FOCUSED_EXISTING
    report["present"] = present
    report["note"] = (
        "dot_brief_check class search reads main/java and src/test/java only. "
        "A missing verdict for ChatUiViewConfigFocusedTest is a scanner gap. "
        "The file lives in src/chatUiTest. Do not move it to clear the lint. "
        "Gradle was not run."
    )
    return report, code


def cmd_mask(root, rel):
    path = user_file(root, rel)
    text = path.read_text(encoding="utf-8", errors="replace")
    hits = []
    for line_no, line in enumerate(text.splitlines(), start=1):
        for mask_id, pattern in MASKS:
            if pattern.search(line):
                hits.append({"id": mask_id, "line": line_no})
    status, code = ("MASK_HIT", 3) if hits else ("MASK_CLEAR", 0)
    report = base("mask", status)
    report["hits"] = hits
    report["note"] = "A hit records the rule id and line number. The line text is not copied."
    return report, code


def cmd_next(root):
    scope, _scope_code = cmd_scope(root)
    hypo, hypo_code = cmd_hypothesis(root, HYPO_REL)
    pin_report, _pin_code = cmd_pin(root)
    cover_report, _cover_code = cmd_cover(root)
    absent, _absent_code = cmd_absent(root)
    drifted = [
        row.get("path")
        for row in pin_report.get("files") or []
        if row.get("sha12Status") == "DRIFT"
    ]
    gaps = []
    for row in cover_report.get("files") or []:
        if row.get("present") is False:
            gaps.append({"path": row.get("path"), "id": "FILE"})
            continue
        for token in row.get("tokens") or []:
            if token.get("status") == "MISSING":
                gaps.append({"path": row.get("path"), "id": token.get("id")})
    if hypo_code == 3:
        status, code = "HYPOTHESIS_SPREAD", 3
    elif pin_report.get("status") == "CONTRACT_GAP":
        status, code = "NEXT_REREAD", 0
    elif scope.get("status") in ("OVERLAP", "SCAN_PARTIAL", "OWNER_AMBIGUOUS"):
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
    report["outsideOwnerJournal"] = scope.get("outsideOwnerJournal")
    report["absent"] = absent.get("status")
    report["hypothesis"] = hypo.get("status")
    report["active"] = hypo.get("active")
    report["redPinned"] = red_pin(root, RED_REL) is not None and not (
        red_pin(root, RED_REL) or {}
    ).get("invalid")
    report["note"] = (
        "NEXT_READY means the assist scan can see the seams. It does not mean the export exists. "
        "On ANCHOR_STALE, re-read. Do not restore a hash. "
        "Keep one hypothesis. Product edits stay blocked until RED_PINNED. "
        "Do not edit blockedPaths or a foreign lease. "
        "A missing fixture phrase is not proof the behavior is absent."
    )
    return report, code


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="Session context export JSON ZIP assist")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("pin"))
    add_root(sub.add_parser("cover"))
    add_root(sub.add_parser("scope"))
    add_root(sub.add_parser("absent"))
    add_root(sub.add_parser("lanes"))
    add_root(sub.add_parser("caps"))
    add_root(sub.add_parser("baseline-keep"))
    add_root(sub.add_parser("focused-present"))
    add_root(sub.add_parser("next"))
    hypo = add_root(sub.add_parser("hypothesis"))
    hypo.add_argument("--file", default=HYPO_REL)
    diff = add_root(sub.add_parser("diff-forbid"))
    diff.add_argument("--diff", required=True)
    gate = add_root(sub.add_parser("product-gate"))
    gate.add_argument("--diff", required=True)
    gate.add_argument("--red-file", default=RED_REL)
    mask = add_root(sub.add_parser("mask"))
    mask.add_argument("--file", required=True)
    parsed = parser.parse_args(argv)
    known = {
        "pin", "cover", "scope", "absent", "lanes", "caps", "baseline-keep",
        "focused-present", "next", "hypothesis", "diff-forbid", "product-gate", "mask",
    }
    if parsed.cmd not in known:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    try:
        root = Path(parsed.root).resolve()
        if parsed.cmd == "pin":
            report, code = cmd_pin(root)
        elif parsed.cmd == "cover":
            report, code = cmd_cover(root)
        elif parsed.cmd == "scope":
            report, code = cmd_scope(root)
        elif parsed.cmd == "absent":
            report, code = cmd_absent(root)
        elif parsed.cmd == "lanes":
            report, code = cmd_lanes(root)
        elif parsed.cmd == "caps":
            report, code = cmd_caps(root)
        elif parsed.cmd == "baseline-keep":
            report, code = cmd_baseline(root)
        elif parsed.cmd == "focused-present":
            report, code = cmd_focused(root)
        elif parsed.cmd == "hypothesis":
            report, code = cmd_hypothesis(root, parsed.file)
        elif parsed.cmd == "diff-forbid":
            report, code = cmd_diff(root, user_file(root, parsed.diff).read_text(encoding="utf-8", errors="replace"))
        elif parsed.cmd == "product-gate":
            report, code = cmd_product_gate(
                root,
                user_file(root, parsed.diff).read_text(encoding="utf-8", errors="replace"),
                parsed.red_file,
            )
        elif parsed.cmd == "mask":
            report, code = cmd_mask(root, parsed.file)
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
