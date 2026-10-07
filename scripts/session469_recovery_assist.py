"""Read-only assist for the session469 relation-evidence and cancel-recovery brief.

Scanner: scripts/pair_brief_assist.py.
Extra commands: scope, hypothesis, product-gate, proof-gap, mask,
focused-present, cancel-probe, receipt, next.
Stdlib only. No network, Gradle, server, or product writes.
`cancel-probe` runs the local node harness; it reads chat.js, nothing else.
Exit 0 is a clean scan. It is not a product PASS.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

import pair_brief_assist as engine

SCHEMA = "awx.session469-recovery-assist.v1"
RECEIPT_SCHEMA = "awx.session469-evidence-receipt.v1"
DEFAULT_SPEC = "var/codex-assist-session469-recovery-20261007/spec.json"
PACK = "var/codex-assist-session469-recovery-20261007"
HYPO_REL = PACK + "/hypothesis.json"
GAPS_REL = PACK + "/proof-gaps.json"
RED_REL = PACK + "/red-boundary.json"
PROBE_REL = "scripts/session469_cancel_recovery_probe.js"
BOUNDARIES = (
    "mid-table-relation-extract",
    "passage-window-relation",
    "prompt-item-512-cap",
    "prior-context-overwrite",
    "tokenless-heartbeat-loss",
    "idle-deadline-missing",
    "exact-run-state-bind",
    "late-event-unscoped-clear",
    "diag-response-shape",
)
GAP_IDS = {
    "source-body": "NOT_OBSERVED",
    "after-filter": "NOT_OBSERVED",
    "packing-span": "NOT_PROVEN",
    "actual-dispatch": "NOT_OBSERVED",
    "w-number-mapping": "NOT_PROVEN",
    "run-request-join": "NOT_OBSERVED",
    "fresh-reload": "NOT_RUN",
    "zip-bytes": "NOT_READ",
    "stop-cause-video": "NOT_PROVEN",
    "synthetic-equals-recovery": "NOT_EQUIVALENT",
    "live-ab": "NOT_RUN",
    "cancel-r2-live": "NOT_RUN",
    "diag-shape": "NOT_OBSERVED",
}
GAP_STATUS = {"NOT_OBSERVED", "NOT_PROVEN", "NOT_RUN", "NOT_READ", "NOT_EQUIVALENT", "PROVEN"}
HOT = (
    "main/java/com/example/lms/service/rag/extract/pagecontentscraper.java",
    "main/java/com/example/lms/service/rag/websearchretriever.java",
    "main/java/com/example/lms/prompt/standardpromptbuilder.java",
    "main/java/com/example/lms/service/chatworkflow.java",
    "main/java/com/example/lms/service/chat/chatrunregistry.java",
    "main/java/com/example/lms/api/chatgenerationadmissionfilter.java",
    "main/java/com/example/lms/api/chatapicontroller.java",
    "main/java/com/example/lms/api/chatcancellationcommandhandler.java",
    "main/resources/static/js/chat.js",
    "main/resources/static/js/chat-trace-ui.js",
    "scripts/chat_ui_stream_contract_tests.js",
    "src/test/java/com/example/lms/service/rag/extract/pagecontentscrapertest.java",
    "src/test/java/com/example/lms/service/rag/websearchretrieverrelationevidencetest.java",
    "src/test/java/com/example/lms/service/chatworkflowpromptmessageroletest.java",
    "src/test/java/com/example/lms/prompt/standardpromptbuilderevidencemetadatatest.java",
    "src/test/java/com/example/lms/prompt/standardpromptbuilderconversationhistorytest.java",
    "src/test/java/com/example/lms/api/chatapicontrollercanceltest.java",
    "src/test/java/com/example/lms/service/chat/chatrunregistryterminalisolationtest.java",
    "src/test/java/com/example/lms/search/provider/hybridwebsearchquerybehaviortest.java",
)
FOCUSED = (
    "src/test/java/com/example/lms/service/rag/extract/PageContentScraperTest.java",
    "src/test/java/com/example/lms/service/rag/WebSearchRetrieverRelationEvidenceTest.java",
    "src/test/java/com/example/lms/service/ChatWorkflowPromptMessageRoleTest.java",
    "src/test/java/com/example/lms/prompt/StandardPromptBuilderEvidenceMetadataTest.java",
    "src/test/java/com/example/lms/prompt/StandardPromptBuilderConversationHistoryTest.java",
    "src/test/java/com/example/lms/api/ChatApiControllerCancelTest.java",
    "src/test/java/com/example/lms/service/chat/ChatRunRegistryTerminalIsolationTest.java",
    "src/test/java/com/example/lms/search/provider/HybridWebSearchQueryBehaviorTest.java",
    "scripts/chat_ui_stream_contract_tests.js",
)
PRODUCT_PREFIXES = ("main/java/", "main/resources/")
STAGE_IDS = (
    "sourceBody",
    "afterFilter",
    "packing",
    "modelDispatch",
    "citation",
    "storedReload",
)
STAGE_STATUS = {"OBSERVED", "NOT_OBSERVED", "NOT_PROVEN", "NOT_RUN", "RED", "GREEN"}
RECEIPT_FORBIDDEN_KEYS = re.compile(
    r"^(rawPrompt|rawBody|rawHistory|rawMessages|messages|authorization|cookie|apiKey|password|secret)$",
    re.I,
)
MASKS = (
    ("count-only", re.compile(r"countReported|webCount|citable|queryCount|httpAttempts|promoted")),
    ("http-200", re.compile(r"HTTP\s*200|HTTP200")),
    ("exit-0", re.compile(r"exit\s*0|exit0", re.I)),
    ("info-none-fallback", re.compile(r"정보\s*없음")),
    ("snapshot-pointer", re.compile(r"snapshotId|pointer")),
    ("synthetic-recovery", re.compile(r"synthetic.{0,48}session469|session469.{0,48}synthetic", re.I)),
)
SUCCESS = re.compile(r"\bPASS\b|\bGREEN\b|완료|VERIFIED|verified")


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
    report["note"] = (
        "A hit is a review signal. The line text is not copied. "
        "A count, a pointer, HTTP 200, exit 0, or an 'info none' fallback is not "
        "relation evidence delivered or cancel recovery."
    )
    return report, code


def cmd_focused_present(root: Path):
    missing = []
    for rel in FOCUSED:
        path = engine.under_root(root, rel)
        if path.is_symlink() or not path.is_file():
            missing.append(rel)
    status, code = ("BASELINE_BLOCKED", 3) if missing else ("PRESENT", 0)
    report = base("focused-present", status)
    report["missing"] = missing
    report["note"] = (
        "PRESENT means the focused files exist. "
        "It does not run Gradle and it is not RED or GREEN."
    )
    return report, code


def cmd_cancel_probe(root: Path):
    probe = engine.under_root(root, PROBE_REL)
    if probe.is_symlink() or not probe.is_file():
        raise engine.AssistError("probe-missing")
    try:
        proc = subprocess.run(
            ["node", str(probe)],
            cwd=str(root),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
        )
    except FileNotFoundError as exc:
        raise engine.AssistError("node-missing") from exc
    except subprocess.TimeoutExpired as exc:
        raise engine.AssistError("probe-timeout") from exc
    scenarios = []
    summary = None
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if row.get("probe") == "session469-cancel-recovery" and row.get("summary") is True:
            summary = row
        elif row.get("probe") == "session469-cancel-recovery":
            scenarios.append(row)
    if summary is None:
        status, code = "PROBE_NO_OUTPUT", 2
    elif proc.returncode == 0:
        status, code = "PROBE_GREEN", 0
    elif proc.returncode == 1:
        status, code = "PROBE_RED", 1
    else:
        status, code = "PROBE_ERROR", 2
    report = base("cancel-probe", status)
    report["nodeRan"] = True
    report["probeExitCode"] = proc.returncode
    report["scenarios"] = [
        {"id": row.get("scenario"), "status": row.get("status")} for row in scenarios
    ]
    report["summary"] = summary
    report["note"] = (
        "PROBE_RED is a source-extracted RED on live chat.js, not a browser or "
        "product failure verdict. PROBE_GREEN on extracted functions is not the "
        "served-runtime PASS."
    )
    return report, code


def receipt_forbidden_walk(node, trail=""):
    hits = []
    if isinstance(node, dict):
        for key, value in node.items():
            if isinstance(key, str) and RECEIPT_FORBIDDEN_KEYS.match(key):
                hits.append(trail + "/" + key)
            hits.extend(receipt_forbidden_walk(value, trail + "/" + str(key)))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            hits.extend(receipt_forbidden_walk(value, trail + "/" + str(index)))
    return hits


def cmd_receipt(root: Path, rel_or_path: str):
    path = user_file(root, rel_or_path) if Path(rel_or_path).is_absolute() else engine.under_root(root, rel_or_path)
    data = read_json(path)
    if not isinstance(data, dict) or data.get("schemaVersion") != RECEIPT_SCHEMA:
        raise engine.AssistError("receipt-schema")
    leaks = receipt_forbidden_walk(data)
    if leaks:
        report = base("receipt", "RECEIPT_LEAK")
        report["leaks"] = leaks
        report["note"] = "A receipt carries hashes and ids only. Raw bodies, prompts, and secrets are not receipts."
        return report, 3
    boundary = data.get("boundary")
    if boundary not in BOUNDARIES:
        report = base("receipt", "RECEIPT_GAP")
        report["reason"] = "boundary-unknown"
        return report, 3
    stages = data.get("stages")
    if not isinstance(stages, dict) or not stages:
        raise engine.AssistError("receipt-shape")
    rows = []
    gap = False
    for stage_id in STAGE_IDS:
        entry = stages.get(stage_id)
        if not isinstance(entry, dict):
            rows.append({"stage": stage_id, "status": "MISSING"})
            gap = True
            continue
        status = entry.get("status")
        hash12 = entry.get("bodyHash12")
        if status not in STAGE_STATUS:
            rows.append({"stage": stage_id, "status": "INVALID"})
            gap = True
            continue
        if hash12 is not None and not (isinstance(hash12, str) and re.fullmatch(r"[0-9a-f]{12,64}", hash12)):
            rows.append({"stage": stage_id, "status": "HASH_BAD"})
            gap = True
            continue
        rows.append({"stage": stage_id, "status": status})
        if status != "OBSERVED":
            gap = True
    if gap:
        status, code = "RECEIPT_OPEN", 0
    else:
        status, code = "RECEIPT_OK", 0
    report = base("receipt", status)
    report["boundary"] = boundary
    report["stages"] = rows
    report["note"] = (
        "RECEIPT_OK means every stage row is OBSERVED with hash-shaped receipts. "
        "It is still not a live dispatch or a product PASS."
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
    parser = argparse.ArgumentParser(description="session469 relation-evidence and cancel-recovery assist")
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
    add_root(sub.add_parser("focused-present"))
    add_root(sub.add_parser("cancel-probe"))
    receipt = add_root(sub.add_parser("receipt"))
    receipt.add_argument("--file", required=True)
    add_root(sub.add_parser("next"))
    parsed = parser.parse_args(args)
    known = (
        "scope", "hypothesis", "product-gate", "proof-gap", "mask",
        "focused-present", "cancel-probe", "receipt", "next",
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
        elif parsed.cmd == "focused-present":
            report, code = cmd_focused_present(root)
        elif parsed.cmd == "cancel-probe":
            report, code = cmd_cancel_probe(root)
        elif parsed.cmd == "receipt":
            report, code = cmd_receipt(root, parsed.file)
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
