"""Read-only assist for the session449 web-context injection brief.

Scanner: scripts/pair_brief_assist.py.
Extra commands: scope, hypothesis, product-gate, classify, mask, gaps, next.
Stdlib only. No network, Gradle, server, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as engine

SCHEMA = "awx.session449-web-context-assist.v1"
DEFAULT_SPEC = "var/codex-assist-session449-web-context-20261007/spec.json"
PACK = "var/codex-assist-session449-web-context-20261007"
HYPO_REL = PACK + "/hypothesis.json"
GAPS_REL = PACK + "/proof-gaps.json"
RED_REL = PACK + "/red-boundary.json"
JOURNAL_ROOT = "data/agent-handoff/codex-autonomy"
BOUNDARIES = (
    "fetch-extract-select",
    "filter-quarantine",
    "compress-or-snippet",
    "promote-gate",
    "final-fit-dispatch",
    "not-reproduced",
)
CONTROLS = (
    "urlOnly",
    "bodyBlank",
    "weakBodySerpKept",
    "providerDisabled",
    "timeoutOrBudget",
    "completedEmpty",
    "afterFilterStarvation",
    "policyQuarantine",
    "officialDenial",
    "relevantBody",
    "bodyOnlyMiddle",
)
COUNT_KEYS = (
    "returnedCount",
    "afterFilterCount",
    "webCount",
    "webCountWasNull",
    "citableEvidenceCount",
    "ctxLen",
)
DISPATCH_KEYS = (
    "renderHasBody",
    "finalMessagesHaveBody",
    "sourcesUrlPresent",
    "prefixSha1Only",
)
GAP_IDS = {
    "zip-bytes": "NOT_READ",
    "session449-join": "NOT_PROVEN",
    "dispatch-body": "NOT_PROVEN",
    "live-replay": "NOT_RUN",
    "gradle": "NOT_RUN",
    "model-behavior": "NOT_RUN",
    "synthetic-equals-449": "NOT_EQUIVALENT",
}
GAP_STATUS = {"NOT_PROVEN", "NOT_RUN", "NOT_READ", "NOT_EQUIVALENT", "PROVEN"}
FORBIDDEN_PROVEN = {"zip-bytes", "synthetic-equals-449"}
PRODUCT = (
    "main/java/com/example/lms/service/rag/WebSearchRetriever.java",
    "main/java/com/example/lms/service/rag/extract/PageContentScraper.java",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java",
    "main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java",
    "main/java/com/example/lms/prompt/StandardPromptBuilder.java",
    "main/java/com/example/lms/service/ChatConversationContext.java",
    "main/java/com/example/lms/llm/TimedChatModelCaller.java",
)
SEAMS = (
    "src/test/java/com/example/lms/service/rag/WebSearchRetrieverRelationEvidenceTest.java",
    "src/test/java/com/example/lms/service/rag/extract/PageContentScraperTest.java",
    "src/test/java/com/example/lms/service/ChatWorkflowPromptMessageRoleTest.java",
)
CALLER = "main/java/com/example/lms/llm/timedchatmodelcaller.java"
FOREIGN = (
    "main/java/com/example/lms/api/chatapicontroller.java",
    "main/java/com/example/lms/api/chatconversationexportsupport.java",
    "main/resources/static/js/chat.js",
    "main/resources/static/js/chat-conversation-export.js",
    "main/resources/templates/chat-ui.html",
)
HOT = tuple(path.casefold() for path in PRODUCT + SEAMS)
PRODUCT_FOLDED = tuple(path.casefold() for path in PRODUCT)
SEAM_FOLDED = tuple(path.casefold() for path in SEAMS)
BANNED_KEY = re.compile(
    r"(html|question|snippet|sentinel|cookie|password|raw)",
    re.I,
)
RAW_TEXT = re.compile(r"https?://|<html|S449_BODY_SENTINEL", re.I)
MASKS = (
    ("sources-as-body", re.compile(r"Sources.{0,40}(body received|본문)")),
    ("trace-count", re.compile(r"traceEntryCount|\b1246\b")),
    ("ctx-chars", re.compile(r"ctx\.len|97자")),
    ("token-estimate", re.compile(r"approxInputTokens|\b1004\b")),
    ("null-as-disabled", re.compile(r"NOT_OBSERVED.{0,40}disabled")),
    ("prefix-as-request", re.compile(r"prefix\.sha1.{0,40}(identity|전체 요청)")),
    ("mock-as-live", re.compile(r"MOCK.{0,40}(runtime PASS|live PASS)")),
    ("exact-cause", re.compile(r"session449.{0,50}(exact cause|확정)")),
    ("url-as-citable", re.compile(r"(URL-only|title-only).{0,40}citable")),
)
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
        if path.stat().st_size > 1_000_000:
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


def surface_kind(path: str):
    folded = norm(path)
    name = folded.rsplit("/", 1)[-1]
    if name in ("build.gradle.kts", "settings.gradle", "settings.gradle.kts"):
        return "gradle"
    if (
        folded.endswith("/chat.js")
        or folded.endswith("/chat-ui.html")
        or folded.endswith("/chat-conversation-export.js")
        or folded.endswith("chatapicontroller.java")
        or folded.endswith("chatconversationexportsupport.java")
        or folded.endswith("/meta/index.html")
        or folded.endswith("docs/project_status.md")
        or folded.endswith("memoryhandler.java")
        or "/assets/display/" in folded
        or "novafocus" in folded
    ):
        return "surface"
    return None


def cmd_scope(root: Path):
    overlaps = []
    journals = []
    foreign = []
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
            foreign_hits = []
            for item in raw_paths:
                if not isinstance(item, str):
                    continue
                hits.extend(hot for hot in HOT if path_hit(item, hot) and hot not in hits)
                foreign_hits.extend(
                    item_hot for item_hot in FOREIGN
                    if path_hit(item, item_hot) and item_hot not in foreign_hits
                )
            row = {
                "kind": "lease",
                "topic": data.get("topic"),
                "ownerId": data.get("ownerId"),
                "expiresAtUtc": data.get("expiresAtUtc"),
            }
            if hits:
                overlaps.append({**row, "paths": hits})
            if foreign_hits:
                foreign.append({**row, "paths": foreign_hits})
    journal_root = root / JOURNAL_ROOT
    if journal_root.is_dir() and not journal_root.is_symlink():
        seen = 0
        for journal_path in sorted(journal_root.glob("*/journal.json")):
            seen += 1
            if seen > 4000:
                skipped += 1
                break
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
                if len(unscoped) < 16:
                    unscoped.append(data.get("taskId"))
                continue
            hits = []
            foreign_hits = []
            for item in scope:
                if not isinstance(item, str):
                    continue
                hits.extend(hot for hot in HOT if path_hit(item, hot) and hot not in hits)
                foreign_hits.extend(
                    item_hot for item_hot in FOREIGN
                    if path_hit(item, item_hot) and item_hot not in foreign_hits
                )
            row = {
                "kind": "journal",
                "taskId": data.get("taskId"),
                "agent": data.get("agent"),
            }
            if hits:
                journals.append({**row, "paths": hits})
            if foreign_hits:
                foreign.append({**row, "paths": foreign_hits})
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
    report["foreignSurfaces"] = foreign
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
        "A journal hit is not a live lease. Do not close it and do not force-release. "
        "foreignSurfaces are outside this brief. Leave them alone. "
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
        "At most one stage hypothesis may be ACTIVE. "
        "not-reproduced means stop without a product patch. "
        "A shorter snippet is not a hypothesis."
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
        if folded not in PRODUCT_FOLDED:
            return {"invalid": True}
        if folded in cleaned:
            return {"invalid": True}
        cleaned.append(folded)
    return {"invalid": False, "boundary": boundary, "allowPaths": cleaned}


def cmd_product_gate(root: Path, diff_text: str, red_rel: str):
    kinds = []
    sentinel = False
    for _index, path, text in engine.added_lines(diff_text):
        if not path:
            continue
        folded = norm(path)
        kind = surface_kind(folded)
        if kind is None and folded in SEAM_FOLDED:
            kind = "seam"
        elif kind is None and (folded.startswith("src/test/") or folded.startswith("src/chatuitest/")):
            kind = "extra-test"
        elif kind is None and folded in PRODUCT_FOLDED:
            kind = "product"
        elif kind is None and folded.startswith("main/"):
            kind = "extra-product"
        elif kind is None:
            kind = "other"
        kinds.append((kind, folded))
        if kind == "product" and "S449_BODY_SENTINEL" in text:
            sentinel = True
    pinned = red_pin(root, red_rel)
    labels = {kind for kind, _path in kinds}
    product = [path for kind, path in kinds if kind == "product"]
    if "surface" in labels:
        status, code = "FORBIDDEN_SURFACE", 3
    elif "gradle" in labels:
        status, code = "GRADLE_LOCK", 3
    elif sentinel:
        status, code = "SENTINEL_IN_PRODUCT", 3
    elif "extra-test" in labels:
        status, code = "EXTRA_TEST", 3
    elif "extra-product" in labels:
        status, code = "EXTRA_PRODUCT", 3
    elif product:
        if pinned is None:
            status, code = "PRODUCT_BEFORE_RED", 3
        elif pinned.get("invalid"):
            status, code = "INVALID_RED", 3
        elif pinned["boundary"] == "not-reproduced":
            status, code = "NO_PATCH", 3
        elif any(path == CALLER for path in product) and pinned["boundary"] != "final-fit-dispatch":
            status, code = "CALLER_BEFORE_DISPATCH", 3
        elif any(path not in pinned["allowPaths"] for path in product):
            status, code = "BOUNDARY_SPREAD", 3
        else:
            status, code = "PRODUCT_SCOPED", 0
    elif "seam" in labels:
        status, code = "SEAM_ONLY", 0
    elif kinds:
        status, code = "ASSIST_ONLY", 0
    else:
        status, code = "EMPTY", 2
    report = base("product-gate", status)
    report["productPaths"] = product
    report["red"] = {
        "pinned": bool(pinned and not pinned.get("invalid")),
        "boundary": None if not pinned or pinned.get("invalid") else pinned.get("boundary"),
    }
    report["note"] = (
        "SEAM_ONLY is the state before one failing fixture. "
        "RED_PINNED names one boundary and one to four product allowPaths. "
        "not-reproduced does not unlock a product edit. "
        "The sentinel stays in the three test seams."
    )
    return report, code


def bool_or_null(value) -> bool:
    return isinstance(value, bool) or value is None


def number_or_null(value) -> bool:
    return value is None or (isinstance(value, int) and not isinstance(value, bool))


def walk_raw(value, depth=0):
    if depth > 6:
        raise engine.AssistError("spec-shape")
    if isinstance(value, dict):
        if len(value) > 40:
            raise engine.AssistError("spec-shape")
        for key, item in value.items():
            if not isinstance(key, str) or BANNED_KEY.search(key):
                raise engine.AssistError("raw-field")
            walk_raw(item, depth + 1)
    elif isinstance(value, list):
        if len(value) > 40:
            raise engine.AssistError("spec-shape")
        for item in value:
            walk_raw(item, depth + 1)
    elif isinstance(value, str):
        if len(value) > 80 or RAW_TEXT.search(value):
            raise engine.AssistError("raw-field")
    elif isinstance(value, bool) or value is None or isinstance(value, int):
        return
    else:
        raise engine.AssistError("spec-shape")


def cmd_classify(root: Path, rel: str):
    data = load_card(root, rel)
    walk_raw(data)
    controls = data.get("controls")
    counts = data.get("counts")
    dispatch = data.get("dispatch")
    if not isinstance(controls, dict) or not isinstance(counts, dict) or not isinstance(dispatch, dict):
        raise engine.AssistError("spec-shape")
    if set(controls) != set(CONTROLS) or set(counts) != set(COUNT_KEYS) or set(dispatch) != set(DISPATCH_KEYS):
        raise engine.AssistError("spec-shape")
    for key in CONTROLS:
        if not bool_or_null(controls[key]):
            raise engine.AssistError("spec-shape")
    for key in COUNT_KEYS:
        if key == "webCountWasNull":
            if not bool_or_null(counts[key]):
                raise engine.AssistError("spec-shape")
        elif not number_or_null(counts[key]):
            raise engine.AssistError("spec-shape")
    for key in DISPATCH_KEYS:
        if not bool_or_null(dispatch[key]):
            raise engine.AssistError("spec-shape")
    reasons = data.get("reasons", {})
    if reasons is None:
        reasons = {}
    if not isinstance(reasons, dict):
        raise engine.AssistError("spec-shape")
    failure = reasons.get("failureClass")
    if failure is not None and (
        not isinstance(failure, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", failure)
    ):
        raise engine.AssistError("spec-shape")
    true_ids = [key for key in CONTROLS if controls[key] is True]
    null_collapsed = counts["webCountWasNull"] is True and counts["webCount"] in (0, None)
    if controls["providerDisabled"] is True and controls["completedEmpty"] is True:
        status, code, stage = "CONTRADICTION", 3, None
    elif null_collapsed and (
        controls["providerDisabled"] is True or controls["completedEmpty"] is True
    ):
        status, code, stage = "NULL_NOT_DISABLED", 3, None
    elif len(true_ids) > 1:
        status, code, stage = "AMBIGUOUS", 3, None
    elif len(true_ids) == 1:
        status, code, stage = "CLASSIFIED", 0, true_ids[0]
    else:
        status, code, stage = "CLASSIFIED", 0, "NOT_OBSERVED"
    if dispatch["finalMessagesHaveBody"] is True and controls["relevantBody"] is True:
        dispatch_label = "MOCK_BODY_PRESENT"
    elif dispatch["renderHasBody"] is True and dispatch["finalMessagesHaveBody"] is False:
        dispatch_label = "LOST_AFTER_RENDER"
    elif dispatch["finalMessagesHaveBody"] is False:
        dispatch_label = "BODY_ABSENT"
    else:
        dispatch_label = "NOT_OBSERVED"
    report = base("classify", status)
    report["stage"] = stage
    report["trueControls"] = true_ids
    report["nullCollapsed"] = null_collapsed
    report["dispatch"] = dispatch_label
    report["urlAppendixNotBody"] = (
        dispatch["sourcesUrlPresent"] is True and dispatch["finalMessagesHaveBody"] is False
    )
    report["prefixIsNotRequest"] = dispatch["prefixSha1Only"] is True
    report["session449Proven"] = False
    report["note"] = (
        "A stored web count of 0 is empty only when webCountWasNull is false. "
        "completedEmpty is not providerDisabled. "
        "Sources without the final message body are an appendix. "
        "MOCK_BODY_PRESENT is not a live model read and not the stored session."
    )
    return report, code


def cmd_mask(root: Path, rel: str):
    path = user_file(root, rel)
    text = path.read_text(encoding="utf-8", errors="replace")
    if len(text) > 400_000:
        raise engine.AssistError("file-too-large")
    hits = []
    for index, line in enumerate(text.splitlines(), start=1):
        for mask_id, pattern in MASKS:
            if pattern.search(line):
                hits.append({"id": mask_id, "line": index})
    status, code = ("MASK_HIT", 3) if hits else ("MASK_CLEAR", 0)
    report = base("mask", status)
    report["hits"] = hits
    report["note"] = "A hit is a line number and a mask id. The line text is not copied."
    return report, code


def cmd_gaps(root: Path, rel: str):
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
        if item_id not in GAP_IDS or status not in GAP_STATUS or item_id in found:
            raise engine.AssistError("spec-shape")
        found[item_id] = status
    if set(found) != set(GAP_IDS):
        raise engine.AssistError("spec-shape")
    promoted = [item_id for item_id in FORBIDDEN_PROVEN if found[item_id] == "PROVEN"]
    if promoted:
        status, code = "PROOF_PROMOTION", 3
    else:
        status, code = "OPEN", 0
    report = base("gaps", status)
    report["items"] = found
    report["promoted"] = promoted
    report["note"] = (
        "zip-bytes and synthetic-equals-449 cannot become PROVEN from this card. "
        "OPEN is not a product PASS."
    )
    return report, code


def cmd_next(root: Path):
    scope, _scope_code = cmd_scope(root)
    hypo, hypo_code = cmd_hypothesis(root, HYPO_REL)
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
    if hypo_code == 3:
        status = "HYPOTHESIS_SPREAD"
    elif pin_report.get("status") == "CONTRACT_GAP":
        status = "NEXT_REREAD"
    elif scope.get("status") == "SCAN_PARTIAL":
        status = "NEXT_WAIT"
    elif scope.get("blockedPaths") and scope.get("freePaths"):
        status = "NEXT_SPLIT"
    elif scope.get("blockedPaths"):
        status = "NEXT_WAIT"
    elif drifted or pin_report.get("status") == "ANCHOR_STALE":
        status = "NEXT_REREAD"
    else:
        status = "NEXT_READY"
    report = base("next", status)
    report["pin"] = pin_report.get("status")
    report["drifted"] = drifted
    report["cover"] = cover_report.get("status")
    report["missingCover"] = gaps
    report["scope"] = scope.get("status")
    report["blockedPaths"] = scope.get("blockedPaths")
    report["freePaths"] = scope.get("freePaths")
    report["hypothesis"] = hypo.get("status")
    report["active"] = hypo.get("active")
    report["note"] = (
        "NEXT_SPLIT means the free product files have no journal or lease hit. "
        "Blocked paths stay with their journal. A journal prefix is not a live lock, "
        "and it is not permission to skip the lease re-check. "
        "Missing cover ids are fixture names, not proof the behavior is absent. "
        "Product edits stay blocked until one RED_PINNED boundary exists. "
        "ANCHOR_STALE means re-read. Do not restore the pinned hash."
    )
    return report, 0 if status != "HYPOTHESIS_SPREAD" else 3


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
    parser = argparse.ArgumentParser(description="session449 web context injection assist")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("scope"))
    hypo = add_root(sub.add_parser("hypothesis"))
    hypo.add_argument("--file", default=HYPO_REL)
    gate = add_root(sub.add_parser("product-gate"))
    gate.add_argument("--diff", required=True)
    gate.add_argument("--red-file", default=RED_REL)
    classify = add_root(sub.add_parser("classify"))
    classify.add_argument("--file", required=True)
    mask = add_root(sub.add_parser("mask"))
    mask.add_argument("--file", required=True)
    gaps = add_root(sub.add_parser("gaps"))
    gaps.add_argument("--file", default=GAPS_REL)
    add_root(sub.add_parser("next"))
    parsed = parser.parse_args(args)
    known = ("scope", "hypothesis", "product-gate", "classify", "mask", "gaps", "next")
    if parsed.cmd not in known:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    try:
        root = Path(parsed.root).resolve()
        if parsed.cmd == "scope":
            report, code = cmd_scope(root)
        elif parsed.cmd == "hypothesis":
            report, code = cmd_hypothesis(root, parsed.file)
        elif parsed.cmd == "product-gate":
            diff_path = user_file(root, parsed.diff)
            report, code = cmd_product_gate(
                root,
                diff_path.read_text(encoding="utf-8", errors="replace"),
                parsed.red_file,
            )
        elif parsed.cmd == "classify":
            report, code = cmd_classify(root, parsed.file)
        elif parsed.cmd == "mask":
            report, code = cmd_mask(root, parsed.file)
        elif parsed.cmd == "gaps":
            report, code = cmd_gaps(root, parsed.file)
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