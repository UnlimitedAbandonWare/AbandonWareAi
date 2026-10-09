"""Read-only assist for the FOR_CODEX jev-main-shadow + release-hedge patch.

Scanner: scripts/pair_brief_assist.py.
Extra commands: scope, hypothesis, product-gate, verify-plan, hold.
Stdlib only. No network, Gradle, server, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as engine

SCHEMA = "awx.jev-main-shadow-assist.v1"
PACK = "var/devin-assist-jev-main-shadow-20261009"
DEFAULT_SPEC = PACK + "/spec.json"
HYPO_REL = PACK + "/hypothesis.json"
RED_REL = PACK + "/red-boundary.json"
HOLD_REL = PACK + "/hold.json"
JOURNAL_ROOT = "data/agent-handoff/codex-autonomy"

BOUNDARIES = (
    "search-decision-seam",
    "surface-mode-config",
    "release-hedge-seam",
    "not-reproduced",
)

PRODUCT = (
    "main/java/com/example/lms/api/ChatApiController.java",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java",
    "main/resources/application-meta-display.yml",
)
SEAMS = (
    "src/test/java/com/example/lms/api/ChatApiControllerAutoSearchDecisionTest.java",
    "src/test/java/com/example/lms/gptsearch/decision/SearchDecisionServiceTest.java",
    "src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java",
    "src/test/java/com/example/lms/service/ChatWorkflowUnavailableVerificationReleaseTest.java",
    "src/test/java/com/example/lms/assist/JevSurfacePolicyTest.java",
    "src/test/java/com/example/lms/api/JevMainSurfaceShadowTest.java",
    "src/test/java/com/example/lms/service/EvidenceReleaseHedgeTest.java",
)
FOREIGN = (
    "main/resources/static/js/chat.js",
    "main/resources/templates/chat-ui.html",
    "main/resources/static/assets/display",
    "main/resources/static/assets/interview",
    "main/resources/static/assets/meta",
    "main/java/com/example/lms/assist/jevdecisionadvisor.java",
    "main/java/com/example/lms/assist/jevevaluationruntime.java",
    "main/java/com/example/lms/assist/jevsurfacepolicy.java",
    "main/java/com/example/lms/assist/jevgatewayclient.java",
    "main/java/com/example/lms/assist/novafocus",
    "main/java/com/example/lms/assist/conversate",
    "main/java/com/example/lms/assist/display",
)
HOT = tuple(path.casefold() for path in PRODUCT + SEAMS)
PRODUCT_FOLDED = tuple(path.casefold() for path in PRODUCT)
SEAM_FOLDED = tuple(path.casefold() for path in SEAMS)


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
        return json.loads(path.read_text(encoding="utf-8"))
    except engine.AssistError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise engine.AssistError("json-unreadable") from exc


def load_card(root: Path, rel: str):
    data = read_json(engine.under_root(root, rel))
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        raise engine.AssistError("spec-schema")
    return data


def surface_kind(path: str):
    folded = norm(path)
    name = folded.rsplit("/", 1)[-1]
    if name in ("build.gradle.kts", "settings.gradle", "settings.gradle.kts"):
        return "gradle"
    for foreign in FOREIGN:
        if path_hit(folded, foreign) or folded.startswith(foreign + "/"):
            return "surface"
    return None


def cmd_scope(root: Path):
    overlaps = []
    journals = []
    foreign = []
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
        for journal_path in sorted(journal_root.glob("*/journal.json")):
            if journal_path.is_symlink():
                continue
            try:
                if journal_path.stat().st_size > 2_000_000:
                    continue
                data = json.loads(journal_path.read_text(encoding="utf-8"))
            except (OSError, UnicodeError, json.JSONDecodeError):
                continue
            if not isinstance(data, dict) or data.get("status") != "in_progress":
                continue
            scope = data.get("plannedScope")
            if not isinstance(scope, list) or not scope:
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
            row = {"kind": "journal", "taskId": data.get("taskId"), "agent": data.get("agent")}
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
    if overlaps or journals:
        status, code = "OVERLAP", 7
    else:
        status, code = "CLEAR", 0
    report = base("scope", status)
    report["leaseOverlaps"] = overlaps
    report["journalOverlaps"] = journals
    report["foreignSurfaces"] = foreign
    report["blockedPaths"] = blocked
    report["freePaths"] = free
    report["note"] = (
        "OVERLAP lists leased paths and in_progress plannedScope hits. "
        "OVERLAP means wait unless this session is that ownerId; do not force-release. "
        "foreignSurfaces are outside the brief. expiresAtUtc is the stored value. "
        "This command does not reclaim."
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
        "At most one boundary hypothesis may be ACTIVE. "
        "not-reproduced means stop without a product patch. PENDING is not a failure."
    )
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
        folded = norm(item)
        if folded not in PRODUCT_FOLDED:
            return None
        if folded in cleaned:
            return None
        cleaned.append(folded)
    return {"boundary": boundary, "allowPaths": cleaned}


def cmd_product_gate(root: Path, diff_text: str):
    kinds = []
    for _index, path, _text in engine.added_lines(diff_text):
        if not path:
            continue
        folded = norm(path)
        kind = surface_kind(folded)
        if kind is None and folded in SEAM_FOLDED:
            kind = "seam"
        elif kind is None and folded in PRODUCT_FOLDED:
            kind = "product"
        elif kind is None and folded.startswith("src/test/"):
            kind = "extra-test"
        elif kind is None and folded.startswith("main/"):
            kind = "extra-product"
        elif kind is None:
            kind = "other"
        kinds.append((kind, folded))
    pinned = red_pin(root)
    labels = {kind for kind, _path in kinds}
    product = [path for kind, path in kinds if kind == "product"]
    if "surface" in labels:
        status, code = "FORBIDDEN_SURFACE", 3
    elif "gradle" in labels:
        status, code = "GRADLE_LOCK", 3
    elif "extra-test" in labels:
        status, code = "EXTRA_TEST", 3
    elif "extra-product" in labels:
        status, code = "EXTRA_PRODUCT", 3
    elif product:
        if pinned is None:
            status, code = "PRODUCT_BEFORE_RED", 3
        elif pinned["boundary"] == "not-reproduced":
            status, code = "NO_PATCH", 3
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
        "pinned": pinned is not None,
        "boundary": None if pinned is None else pinned["boundary"],
    }
    report["note"] = (
        "SEAM_ONLY is the state before one failing fixture. "
        "RED_PINNED names one boundary and one to four product allowPaths. "
        "not-reproduced does not unlock a product edit. "
        "The brief's edit set is ChatApiController + ChatWorkflow + "
        "RagControlRuntimeAdapter (+ optional yml note); anything outside it is "
        "EXTRA_PRODUCT and means re-scope before touching it."
    )
    return report, code


def load_spec(root: Path, rel: str):
    data = read_json(engine.under_root(root, rel))
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        raise engine.AssistError("spec-schema")
    return data


def cmd_verify_plan(root: Path, rel: str, contract: str | None):
    spec = load_spec(root, rel)
    contracts = spec.get("contracts")
    if not isinstance(contracts, dict) or not contracts:
        raise engine.AssistError("spec-shape")
    plan = []
    for name, body in contracts.items():
        if contract and name != contract:
            continue
        if not isinstance(body, dict):
            raise engine.AssistError("spec-shape")
        steps = body.get("acceptance")
        if not isinstance(steps, list):
            continue
        for step in steps:
            if not isinstance(step, dict):
                raise engine.AssistError("spec-shape")
            step_id = step.get("id")
            command = step.get("command")
            if not isinstance(step_id, str) or not isinstance(command, str):
                raise engine.AssistError("spec-shape")
            plan.append({
                "contract": name,
                "id": step_id,
                "command": command,
                "manual": step.get("manual") is True,
            })
    if contract and contract not in contracts:
        raise engine.AssistError("contract-unknown")
    status, code = ("PLAN", 0) if plan else ("EMPTY", 2)
    report = base("verify-plan", status)
    report["steps"] = plan
    report["note"] = (
        "Ordered acceptance commands from the brief. Run them stepwise from the "
        "project root and record exit codes and real test counts. Zero tests is not PASS. "
        "manual:true steps are human/browser checks, not shell commands. "
        "This command does not execute them."
    )
    return report, code


def cmd_hold(root: Path, rel: str):
    data = load_card(root, rel)
    items = data.get("items")
    if not isinstance(items, list):
        raise engine.AssistError("spec-shape")
    allowed_status = {"OPEN", "RESOLVED", "WAIVED"}
    rows = []
    open_ids = []
    for item in items:
        if not isinstance(item, dict):
            raise engine.AssistError("spec-shape")
        item_id = item.get("id")
        status = item.get("status")
        if not isinstance(item_id, str) or status not in allowed_status:
            raise engine.AssistError("spec-shape")
        rows.append({"id": item_id, "status": status})
        if status == "OPEN":
            open_ids.append(item_id)
    status, code = ("OPEN", 4) if open_ids else ("CLEAN", 0)
    report = base("hold", status)
    report["items"] = rows
    report["openIds"] = open_ids
    report["note"] = (
        "OPEN hold items block a DONE claim, not independent mock/read checks. "
        "Record real resolutions only."
    )
    return report, code


def read_diff(path: Path) -> str:
    engine.reject_name(path.name)
    if path.is_symlink() or not path.is_file():
        raise engine.AssistError("diff-missing")
    if path.stat().st_size > engine.MAX_BYTES:
        raise engine.AssistError("file-too-large")
    return path.read_text(encoding="utf-8", errors="replace")


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
    parser = argparse.ArgumentParser(description="FOR_CODEX jev-main-shadow + release-hedge assist")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("scope"))
    hypo = add_root(sub.add_parser("hypothesis"))
    hypo.add_argument("--file", default=HYPO_REL)
    gate = add_root(sub.add_parser("product-gate"))
    gate.add_argument("--diff", required=True)
    plan = add_root(sub.add_parser("verify-plan"))
    plan.add_argument("--spec", default=DEFAULT_SPEC)
    plan.add_argument("--contract", default=None)
    hold = add_root(sub.add_parser("hold"))
    hold.add_argument("--file", default=HOLD_REL)
    parsed = parser.parse_args(args)
    if parsed.cmd not in ("scope", "hypothesis", "product-gate", "verify-plan", "hold"):
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    try:
        root = Path(parsed.root).resolve()
        if parsed.cmd == "scope":
            report, code = cmd_scope(root)
        elif parsed.cmd == "hypothesis":
            report, code = cmd_hypothesis(root, parsed.file)
        elif parsed.cmd == "verify-plan":
            report, code = cmd_verify_plan(root, parsed.spec, parsed.contract)
        elif parsed.cmd == "hold":
            report, code = cmd_hold(root, parsed.file)
        else:
            report, code = cmd_product_gate(root, read_diff(Path(parsed.diff)))
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
