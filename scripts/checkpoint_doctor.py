#!/usr/bin/env python3
"""Checkpoint state doctor (read-only).

Answers "where is this checkpoint run stuck and what is the next command".

    python -B scripts/checkpoint_doctor.py --run <cycle-dir> [--root .]
    python -B scripts/checkpoint_doctor.py --latest        # newest run dir

Prints: status, firstBlockingRule, lease path + seconds-to-expiry,
target count, and ONE next-command hint. Emits a warning when the run
is sealed-but-not-finished or when the bound lease is near expiry —
"seal/finish must run before the lease expires".

Exit 0 always for a readable run; 2 for usage/IO errors.
"""
import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

RUN_PREFIX = "data/agent-handoff/"


def load_lease(root, ref):
    if not isinstance(ref, dict):
        return None
    rel = ref.get("path", "")
    p = root / rel
    if not p.is_file():
        return {"path": rel, "present": False}
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"path": rel, "present": True, "unreadable": True}
    exp = None
    try:
        exp = datetime.fromisoformat(str(data.get("expiresAtUtc", "")).replace("Z", "+00:00"))
    except ValueError:
        pass
    return {"path": rel, "present": True,
            "leaseId": data.get("leaseId"), "ownerId": data.get("ownerId"),
            "expiresAtUtc": data.get("expiresAtUtc"),
            "secondsToExpiry": ((exp - datetime.now(timezone.utc)).total_seconds()
                                if exp and exp.tzinfo else None)}


def next_hint(state, lease):
    status = state.get("status")
    rule = state.get("firstBlockingRule") or ""
    hints = {
        "prepared": "apply the declared edits, then: codex_work_checkpoint.py seal --run <run>",
        "begun": "apply the declared edits, then: codex_work_checkpoint.py seal --run <run>",
        "sealed": "run the real verification, then: codex_work_checkpoint.py finish --run <run> --exit-code <n>",
        "hold": "read firstBlockingRule; fix the named blocker, do NOT retry seal blindly",
        "verified": "cycle complete; record note in work_journal",
        "rolled_back": "targets restored; reconcile before re-attempting",
        "restore_staged": "verify staged copies, then restore without --staging",
    }
    hint = hints.get(status, "inspect checkpoint.json")
    if rule == "source-lease-drift":
        hint = ("lease file changed mid-cycle (heartbeat renews expiresAtUtc); "
                "post-patch identity check passes same-lease renewals — "
                "re-run the action once")
    if rule == "secret-pattern":
        hint = "locate the flagged file:line in firstBlockingRule detail; mask or env-ize the literal"
    if lease and lease.get("secondsToExpiry") is not None and status in ("prepared", "begun", "sealed"):
        secs = lease["secondsToExpiry"]
        hint += " | seal+finish BEFORE lease expiry (in %ds)" % secs
    return hint


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--run", default=None)
    ap.add_argument("--latest", action="store_true")
    ap.add_argument("--warn-seconds", type=int, default=600)
    args = ap.parse_args()
    root = Path(args.root)

    run = None
    if args.latest:
        cands = sorted(root.glob("data/agent-handoff/**/checkpoint.json"),
                       key=lambda p: p.stat().st_mtime, reverse=True)
        run = cands[0].parent if cands else None
    elif args.run:
        run = Path(args.run)
    if run is None or not (run / "checkpoint.json").is_file():
        print(json.dumps({"status": "error", "reason": "checkpoint.json not found",
                          "run": str(run)}))
        return 2

    try:
        state = json.loads((run / "checkpoint.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(json.dumps({"status": "error", "reason": "unreadable", "detail": str(exc)[:160]}))
        return 2

    lease = load_lease(root, (state.get("manifest") or {}).get("lease")
                       or state.get("lease"))
    report = {"run": str(run), "status": state.get("status"),
              "firstBlockingRule": state.get("firstBlockingRule"),
              "nextAction": state.get("nextAction"),
              "targetCount": len((state.get("manifest") or {}).get("targets") or []),
              "lease": lease,
              "hint": next_hint(state, lease)}
    if lease and lease.get("secondsToExpiry") is not None:
        report["leaseExpiryWarning"] = (
            "seal/finish before lease expiry" if 0 < lease["secondsToExpiry"] < args.warn_seconds
            else ("lease-EXPIRED" if lease["secondsToExpiry"] <= 0 else "ok"))
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
