"""Read-only assist for the session392 evidence-chain brief.

Scanner: scripts/pair_brief_assist.py.
Extra commands: scope, hypothesis, product-gate.
Stdlib only. No network, Gradle, server, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pair_brief_assist as engine

SCHEMA = "awx.session392-evidence-chain-assist.v1"
DEFAULT_SPEC = "var/codex-assist-session392-evidence-chain-20261006/spec.json"
PACK = "var/codex-assist-session392-evidence-chain-20261006"
HYPO_REL = PACK + "/hypothesis.json"
RED_REL = PACK + "/red-boundary.json"
BOUNDARIES = ("stale-a-reuse", "prior-counter-cache", "late-event")
HOT = (
    "main/java/com/example/lms/service/chatworkflow.java",
    "main/java/com/example/lms/api/chatapicontroller.java",
)
FIXTURE = (
    "src/test/java/com/example/lms/service/"
    "chatworkflowstrictsingleattempthttpintegrationtest.java"
)
PRODUCT_PREFIXES = ("main/java/", "main/resources/")


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
        return json.loads(path.read_text(encoding="utf-8"))
    except engine.AssistError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise engine.AssistError("json-unreadable") from exc


def cmd_scope(root: Path):
    lock_root = root / "__patch_drop__" / "source-edit-locks"
    overlaps = []
    fixture_leased = False
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
            if FIXTURE in paths:
                fixture_leased = True
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
    report["fixtureLeased"] = fixture_leased
    report["hotPaths"] = list(HOT)
    report["note"] = (
        "OVERLAP means WAIT unless this session is that ownerId. "
        "expiresAtUtc is the value stored in lease.json. "
        "This command does not classify stale and does not reclaim."
    )
    return report, code


def cmd_hypothesis(root: Path, rel: str):
    path = engine.under_root(root, rel)
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
    parser = argparse.ArgumentParser(description="session392 evidence-chain assist")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("scope"))
    hypo = add_root(sub.add_parser("hypothesis"))
    hypo.add_argument("--file", default=HYPO_REL)
    gate = add_root(sub.add_parser("product-gate"))
    gate.add_argument("--diff", required=True)
    parsed = parser.parse_args(args)
    if parsed.cmd not in ("scope", "hypothesis", "product-gate"):
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    try:
        root = Path(parsed.root).resolve()
        if parsed.cmd == "scope":
            report, code = cmd_scope(root)
        elif parsed.cmd == "hypothesis":
            report, code = cmd_hypothesis(root, parsed.file)
        else:
            diff_path = Path(parsed.diff)
            engine.reject_name(diff_path.name)
            if diff_path.is_symlink() or not diff_path.is_file():
                raise engine.AssistError("diff-missing")
            report, code = cmd_product_gate(
                root, diff_path.read_text(encoding="utf-8", errors="replace"))
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
