"""Classify a checkpoint secret-scan hold for the AUTO repair flow.

One JSON decision per run, driven by checkpoint_secret_explain verdicts:

  pass                - the file already scans clean; nothing to do
  scanner_fix         - every blocking hit is call/name-shaped and the scanner
                        is free to fix (regression test rides along, per
                        agents.md: shared-guard FPs are fixed in the scanner)
  scanner_fix_queued  - same shape but a live foreign lease or the brief
                        forbids scanner edits; one queue ticket is written and
                        the checkpoint stays held for a later session
  real_secret_suspect - literal/env/other shapes; not a false positive, hold
                        for a human

Never prints matched bytes; never edits or deletes source files.
Usage: checkpoint_fp_autoflow.py --path <repo/path> [--root <dir>]
       [--brief <brief-file>] [--owner <id>] [--json]
exit 0 pass|scanner_fix, 5 scanner_fix_queued, 6 real_secret_suspect, 2 error.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import checkpoint_secret_explain

SCANNER_PATH = "scripts/codex_work_checkpoint.py"
FIXABLE_RHS_KINDS = {"call", "name", "attr", "none_bool"}
FORBIDDEN_MARKERS = ("금지", "forbid", "do not", "don't", "must not", "immutable",
                     "never edit", "변경 금지")
QUEUE_DIR = "data/agent-handoff/scanner-fp-queue"


def explain(path, root):
    return checkpoint_secret_explain.explain_file(root, path)


def scanner_lease_holder(root, owner):
    """Topic of a live foreign lease covering the scanner file, else None."""
    locks = Path(root) / "__patch_drop__" / "source-edit-locks"
    if not locks.is_dir():
        return None
    for lease_file in sorted(locks.glob("*/lease.json")):
        try:
            lease = json.loads(lease_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if lease.get("status") != "active":
            continue
        targets = [str(t).replace("\\", "/") for t in lease.get("targetPaths", [])]
        if SCANNER_PATH not in targets:
            continue
        holders = {str(lease.get(k, "")) for k in ("ownerId", "ownerTaskId", "taskId")}
        if owner and owner in holders:
            continue
        return lease.get("topic") or lease_file.parent.name
    return None


def directive_forbids_scanner(brief_path, root):
    """True when the brief names the scanner next to a forbidden marker."""
    if not brief_path:
        return False
    path = Path(brief_path)
    if not path.is_absolute():
        path = Path(root) / path
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    for match in re.finditer(re.escape("codex_work_checkpoint"), text):
        window = text[max(0, match.start() - 160):match.end() + 160].casefold()
        if any(marker.casefold() in window for marker in FORBIDDEN_MARKERS):
            return True
    return False


def classify(hits, foreign_lease, directive_forbidden):
    blocking = [h for h in hits if h.get("blocking")]
    if not blocking:
        return "real_secret_suspect"
    if not all(h.get("rhsKind") in FIXABLE_RHS_KINDS for h in blocking):
        return "real_secret_suspect"
    return "scanner_fix_queued" if foreign_lease or directive_forbidden else "scanner_fix"


def queue_ticket(root, explain_result, reason):
    sha12 = explain_result.get("sha256_12") or hashlib.sha256(
        explain_result.get("path", "").encode()).hexdigest()[:12]
    queue = Path(root) / QUEUE_DIR
    queue.mkdir(parents=True, exist_ok=True)
    ticket = queue / (sha12 + ".json")
    body = {
        "schemaVersion": "awx.scanner-fp-queue.v1",
        "path": explain_result.get("path"),
        "sha256_12": sha12,
        "hits": [{"line": h.get("line"), "identifier": h.get("identifier"),
                  "rhsKind": h.get("rhsKind")} for h in explain_result.get("hits", [])],
        "reason": reason,
        "queuedAtUtc": datetime.now(timezone.utc).isoformat(),
    }
    ticket.write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return str(ticket.relative_to(root)).replace("\\", "/")


def decide(path, root=".", brief=None, owner=""):
    result = explain(path, root)
    if result.get("verdict") == "pass":
        return {"decision": "pass", "path": result.get("path"),
                "sha256_12": result.get("sha256_12"), "hits": result.get("hits", [])}
    if result.get("verdict") != "hold":
        return {"decision": "error", "path": result.get("path"),
                "error": result.get("error", "explain-failed")}
    foreign = scanner_lease_holder(root, owner)
    forbidden = directive_forbids_scanner(brief, root)
    decision = classify(result.get("hits", []), foreign, forbidden)
    out = {"decision": decision, "path": result.get("path"),
           "sha256_12": result.get("sha256_12"), "hits": result.get("hits", [])}
    if decision == "scanner_fix_queued":
        reason = "foreign-lease:" + foreign if foreign else "directive-forbids-scanner-edit"
        out["reason"] = reason
        out["ticket"] = queue_ticket(root, result, reason)
    elif decision == "real_secret_suspect":
        out["reason"] = "non-call-shaped or unclassified blocking hit"
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Classify a checkpoint secret-scan hold; never prints matched bytes.")
    parser.add_argument("--path", required=True, help="repo-relative file path")
    parser.add_argument("--root", default=".")
    parser.add_argument("--brief", default=None, help="optional directive/brief file")
    parser.add_argument("--owner", default="", help="caller's owner/task id for lease check")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = decide(args.path, args.root, args.brief, args.owner)
    except Exception as error:  # report shape stays byte-free
        result = {"decision": "error", "path": args.path, "error": type(error).__name__}
    print(json.dumps(result, ensure_ascii=False) if args.json else
          "{decision} {path}".format(**{**result, "path": result.get("path")}))
    return {"pass": 0, "scanner_fix": 0, "scanner_fix_queued": 5,
            "real_secret_suspect": 6}.get(result["decision"], 2)


if __name__ == "__main__":
    sys.exit(main())
