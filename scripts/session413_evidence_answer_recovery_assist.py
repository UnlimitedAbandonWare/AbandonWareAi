"""Read-only assist for the session413 evidence and answer-recovery brief.

Scanner: scripts/pair_brief_assist.py.
Extra commands: scope, hypothesis, product-gate, proof-gap, mask,
fixture-class, focused-present, next.
Stdlib only. No network, Gradle, server, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import pair_brief_assist as engine

SCHEMA = "awx.session413-evidence-answer-assist.v1"
DEFAULT_SPEC = "var/codex-assist-session413-evidence-answer-20261007/spec.json"
PACK = "var/codex-assist-session413-evidence-answer-20261007"
HYPO_REL = PACK + "/hypothesis.json"
GAPS_REL = PACK + "/proof-gaps.json"
RED_REL = PACK + "/red-boundary.json"
BOUNDARIES = (
    "credibility-false-positive",
    "hold-replaces-partial",
    "snippet-as-summary",
    "binding-or-counter",
    "null-as-disabled",
)
GAP_IDS = {
    "dispatch-messages": "NOT_PROVEN",
    "response-body": "NOT_PROVEN",
    "run-request-join": "NOT_PROVEN",
    "runtime-build-sha": "NOT_PROVEN",
    "judge-executed": "NOT_PROVEN",
    "latest-zip-session-id": "NOT_PROVEN",
    "library-4-bytes": "NOT_READ",
    "video-transcript": "NOT_RUN",
    "live-replay": "NOT_RUN",
    "synthetic-equals-recovery": "NOT_EQUIVALENT",
}
GAP_STATUS = {"NOT_PROVEN", "NOT_RUN", "NOT_READ", "NOT_EQUIVALENT", "PROVEN"}
HOT = (
    "main/java/com/example/lms/service/verification/sourceanalyzerservice.java",
    "main/java/com/example/lms/service/factverifierservice.java",
    "main/java/com/example/lms/service/chatworkflow.java",
    "main/java/com/example/lms/orchestration/control/ragcontrolprojectionrenderer.java",
    "main/java/com/example/lms/service/rag/evidenceanswercomposer.java",
    "main/java/com/example/lms/service/noevidencechatfallback.java",
    "main/java/com/example/lms/api/chatapicontroller.java",
    "main/java/com/example/lms/service/trace/tracehtmlbuilder.java",
    "main/java/com/example/lms/api/chattracemetamessagerestorer.java",
)
FOCUSED = (
    "src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java",
    "src/test/java/com/example/lms/service/FactVerifierDetailedOutcomeTest.java",
    "src/test/java/com/example/lms/service/SourceCredibilityReleaseRegressionTest.java",
    "src/test/java/com/example/lms/service/ChatWorkflowStrictSingleAttemptHttpIntegrationTest.java",
    "src/test/java/com/example/lms/service/rag/EvidenceAnswerComposerSupportedExcerptTest.java",
    "src/test/java/com/example/lms/service/rag/EvidenceAnswerComposerAlignmentTest.java",
    "src/test/java/com/example/lms/orchestration/control/RagControlRuntimeAdapterTest.java",
    "src/test/java/com/example/lms/api/ChatTraceMetaMessageRestorerTest.java",
    "src/test/java/com/example/lms/api/ChatTraceBundleResponseBuilderTest.java",
)
PRODUCT_PREFIXES = ("main/java/", "main/resources/")
HARNESS = "scripts/chat_ui_stream_contract_tests.js"
GRAPH_JS = "main/resources/static/js/chat-evidence-graph.js"
GRAPH_TEST = "src/test/js/chat-evidence-graph.test.cjs"
MASKS = (
    ("prompt-chars", re.compile(r"promptChars")),
    ("fallback-count", re.compile(r"fallbackCount")),
    ("http-200", re.compile(r"HTTP\s*200|HTTP200")),
    ("model-label", re.compile(r"observedModel|model label")),
    ("card-count", re.compile(r"card count|카드\s*\d")),
    ("completed-none", re.compile(r"completed/none")),
    ("synthetic-recovery", re.compile(r"synthetic.{0,48}session413|session413.{0,48}synthetic", re.I)),
)
SUCCESS = re.compile(r"\bPASS\b|\bGREEN\b|완료")


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


def open_text(root: Path, rel: str) -> str:
    path = engine.under_root(root, rel)
    if path.is_symlink() or not path.is_file():
        raise engine.AssistError("file-missing")
    if path.stat().st_size > engine.MAX_BYTES:
        raise engine.AssistError("file-too-large")
    return path.read_text(encoding="utf-8", errors="replace")


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


def cmd_scope(root: Path):
    lock_root = root / "__patch_drop__" / "source-edit-locks"
    overlaps = []
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
            paths = [norm(item) for item in raw_paths if isinstance(item, str)]
            hits = [item for item in paths if item in HOT]
            if hits:
                overlaps.append({
                    "topic": data.get("topic"),
                    "ownerId": data.get("ownerId"),
                    "expiresAtUtc": data.get("expiresAtUtc"),
                    "paths": hits,
                })
    status, code = ("OVERLAP", 7) if overlaps else ("CLEAR", 0)
    report = base("scope", status)
    report["overlaps"] = overlaps
    report["hotPaths"] = list(HOT)
    report["note"] = (
        "OVERLAP means WAIT unless this session is that ownerId. "
        "expiresAtUtc is the value stored in lease.json. "
        "This command does not classify stale and does not reclaim."
    )
    return report, code


def cmd_hypothesis(root: Path, rel: str):
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
    report["note"] = "At most one hypothesis may be ACTIVE. PENDING is not a failure."
    return report, code


def red_pin(root: Path):
    path = root / RED_REL
    if not path.is_file():
        return None
    data = read_json(path)
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        return None
    if data.get("status") != "RED_PINNED":
        return None
    boundary = data.get("boundary")
    allow = data.get("allowPaths")
    if boundary not in BOUNDARIES or not isinstance(allow, list) or not 1 <= len(allow) <= 4:
        return None
    cleaned = []
    for item in allow:
        if not isinstance(item, str):
            return None
        engine.under_root(root, item)
        cleaned.append(norm(item))
    return {"boundary": boundary, "allowPaths": cleaned}


def cmd_product_gate(root: Path, diff_text: str):
    product = []
    for _index, path, _text in engine.added_lines(diff_text):
        if not path:
            continue
        folded = norm(path)
        if folded.startswith(PRODUCT_PREFIXES) and folded not in product:
            product.append(folded)
    pinned = red_pin(root)
    if not product:
        status, code = "TEST_ONLY", 0
    elif pinned is None:
        status, code = "PRODUCT_BEFORE_RED", 3
    elif any(item not in pinned["allowPaths"] for item in product):
        status, code = "BOUNDARY_SPREAD", 3
    else:
        status, code = "PRODUCT_SCOPED", 0
    report = base("product-gate", status)
    report["productPaths"] = product
    report["red"] = {
        "pinned": pinned is not None,
        "boundary": None if pinned is None else pinned["boundary"],
    }
    report["note"] = (
        "TEST_ONLY is the state before a confirmed RED. "
        "RED_PINNED must name one boundary and at most four allowPaths. "
        "EXAMPLE_NOT_RED does not unlock product edits."
    )
    return report, code


def cmd_proof_gap(root: Path, rel_or_path: str):
    path = user_file(root, rel_or_path) if Path(rel_or_path).is_absolute() else engine.under_root(root, rel_or_path)
    data = read_json(path)
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        raise engine.AssistError("spec-schema")
    items = data.get("items")
    if not isinstance(items, list):
        raise engine.AssistError("spec-shape")
    found = {}
    proven = []
    for item in items:
        if not isinstance(item, dict):
            raise engine.AssistError("spec-shape")
        item_id = item.get("id")
        status = item.get("status")
        if item_id not in GAP_IDS or status not in GAP_STATUS or item_id in found:
            raise engine.AssistError("spec-shape")
        receipt = item.get("receiptHash")
        if status == "PROVEN":
            if item_id == "synthetic-equals-recovery":
                report = base("proof-gap", "SYNTHETIC_NOT_RECOVERY")
                report["id"] = item_id
                report["note"] = "A synthetic GREEN is not recovery of the stored session."
                return report, 3
            if not isinstance(receipt, str) or not re.fullmatch(r"[0-9a-f]{12,64}", receipt):
                report = base("proof-gap", "PROVEN_UNRECEIPTED")
                report["id"] = item_id
                report["note"] = "PROVEN requires receiptHash. The open ledger is not a failure."
                return report, 3
            proven.append(item_id)
        found[item_id] = status
    if set(found) != set(GAP_IDS):
        raise engine.AssistError("spec-shape")
    status = "MIXED" if proven else "OPEN"
    report = base("proof-gap", status)
    report["proven"] = proven
    report["note"] = "OPEN means the brief's proof gaps stay unclaimed. It is not a product PASS."
    return report, 0


def cmd_mask(text: str):
    hits = []
    for index, line in enumerate(text.splitlines(), start=1):
        if not SUCCESS.search(line):
            continue
        for rule_id, pattern in MASKS:
            if pattern.search(line):
                hits.append({"id": rule_id, "line": index})
    status, code = ("MASK_HIT", 3) if hits else ("MASK_CLEAR", 0)
    report = base("mask", status)
    report["hits"] = hits
    report["note"] = "A hit is a review signal. The line text is not copied."
    return report, code


def cmd_fixture_class(root: Path):
    harness = open_text(root, HARNESS)
    graph = open_text(root, GRAPH_JS)
    test = open_text(root, GRAPH_TEST)
    getter = "get isConnected()" in harness
    setter = "set isConnected(" in harness
    write_re = re.compile(r"\.isConnected\s*=|\[['\"]isConnected['\"]\]\s*=")
    writes = []
    for rel, body in ((HARNESS, harness), (GRAPH_JS, graph), (GRAPH_TEST, test)):
        for index, line in enumerate(body.splitlines(), start=1):
            if write_re.search(line):
                writes.append({"path": rel, "line": index})
    if getter and not setter and writes:
        status, code = "FIXTURE_WRITE", 8
    elif getter and not setter:
        status, code = "GETTER_ONLY_NO_WRITE", 0
    else:
        status, code = "CLEAR", 0
    report = base("fixture-class", status)
    report["getterOnly"] = bool(getter and not setter)
    report["writeCount"] = len(writes)
    report["writes"] = writes
    report["briefNodeResult"] = "NOT_INHERITED"
    report["note"] = (
        "FIXTURE_WRITE is a static assignment to a getter-only isConnected. "
        "It is not a product RED. This command does not run node. "
        "The paste's 22 graph failures are not inherited."
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
        "PRESENT means the focused test files exist. "
        "It does not run Gradle and it is not RED or GREEN."
    )
    return report, code


def cmd_next(root: Path):
    scope, _scope_code = cmd_scope(root)
    hypo, hypo_code = cmd_hypothesis(root, HYPO_REL)
    engine.SCHEMA = SCHEMA
    spec = engine.load_spec(engine.under_root(root, DEFAULT_SPEC))
    cover_report, _cover_code = engine.cmd_cover(root, spec)
    gaps = []
    for row in cover_report.get("files") or []:
        for token in row.get("tokens") or []:
            if token.get("status") == "MISSING":
                gaps.append({"path": row.get("path"), "id": token.get("id")})
    pinned = red_pin(root)
    if hypo_code == 3:
        status, code = "HYPOTHESIS_SPREAD", 3
    elif scope.get("status") == "OVERLAP":
        status, code = "NEXT_WAIT", 0
    else:
        status, code = "NEXT_READY", 0
    report = base("next", status)
    report["scope"] = scope.get("status")
    report["overlaps"] = scope.get("overlaps")
    report["hypothesis"] = hypo.get("status")
    report["active"] = hypo.get("active")
    report["missingCover"] = gaps
    report["redPinned"] = pinned is not None
    report["note"] = (
        "NEXT_WAIT means a hot product path is leased. "
        "Missing cover ids are fixture phrases, not proof the behavior is absent. "
        "Product edits stay blocked until one RED_PINNED boundary exists."
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
    parser = argparse.ArgumentParser(description="session413 evidence and answer-recovery assist")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("scope"))
    hypo = add_root(sub.add_parser("hypothesis"))
    hypo.add_argument("--file", default=HYPO_REL)
    gate = add_root(sub.add_parser("product-gate"))
    gate.add_argument("--diff", required=True)
    gaps = add_root(sub.add_parser("proof-gap"))
    gaps.add_argument("--file", default=GAPS_REL)
    mask = add_root(sub.add_parser("mask"))
    mask.add_argument("--file", required=True)
    add_root(sub.add_parser("fixture-class"))
    present = add_root(sub.add_parser("focused-present"))
    present.add_argument("--init")
    add_root(sub.add_parser("next"))
    parsed = parser.parse_args(args)
    known = (
        "scope", "hypothesis", "product-gate", "proof-gap", "mask",
        "fixture-class", "focused-present", "next",
    )
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
                root, diff_path.read_text(encoding="utf-8", errors="replace"))
        elif parsed.cmd == "proof-gap":
            report, code = cmd_proof_gap(root, parsed.file)
        elif parsed.cmd == "mask":
            report, code = cmd_mask(
                user_file(root, parsed.file).read_text(encoding="utf-8", errors="replace"))
        elif parsed.cmd == "fixture-class":
            report, code = cmd_fixture_class(root)
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
