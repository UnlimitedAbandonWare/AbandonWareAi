#!/usr/bin/env python3
"""Read-only status board for one parallel-lane plan.

Contract DEMO1-DEVIN-CODEX-PARALLEL-LANES-20261002 (DV9). Shows one row per
lane: role, which chat picked it up (uuid prefix from the lane journal),
last activity, files under a claim/checkpoint, latest recorded test result,
smoke/restart quota usage, journal status. Never mutates anything.

  python -B scripts/codex_lane_board.py --plan plan-ab12cd34
  python -B scripts/codex_lane_board.py --plan plan-ab12cd34 --json
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_parallel_lib as lib
import codex_lane_quota as quota

SCHEMA = "awx.lane-board.v1"
UUID_RE = re.compile(r"\b([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
                     r"[0-9a-f]{12}\b")


def chat_prefix(journal):
    text = str(journal.get("purpose") or "") + " " + " ".join(
        str(e.get("text") or "") for e in journal.get("events") or [])
    match = UUID_RE.search(text)
    return match.group(1) if match else ""


def latest_test_result(root, task_id):
    task_dir = Path(root) / lib.JOURNAL_BASE / str(task_id)
    best, best_mtime = None, 0.0
    for run in task_dir.glob("*/run.json") if task_dir.is_dir() else []:
        try:
            doc = json.loads(run.read_bytes())
            mtime = run.stat().st_mtime
        except (OSError, ValueError):
            continue
        if mtime > best_mtime:
            best_mtime, best = mtime, doc
    if not isinstance(best, dict):
        return ""
    code = best.get("exitCode", best.get("exit_code", "?"))
    cmd = best.get("commandId") or best.get("command") or ""
    return f"exit={code} {str(cmd)[:60]}"


def lane_rows(root, plan):
    quota_state = {}
    try:
        quota_state = quota.load_state(root, plan["planId"])
    except quota.QuotaError:
        pass
    consumption = quota_state.get("consumption") or {}
    tokens = quota_state.get("tokens") or {}
    rows = []
    for lane in plan.get("lanes") or []:
        lane_id = lane.get("laneId")
        journals = [j for j in lib.iter_journals(root)
                    if lib.lane_tag(j) == (plan["planId"], lane_id)]
        claims = [c for c in lib.iter_claims(root)
                  if not c.get("released") and c.get("taskId") in
                  {j.get("taskId") for j in journals}]
        files = sorted({lib.canon(t.get("path")) for c in claims
                        for t in c.get("targets") or []} - {None})
        smoke_used = sum(1 for u in consumption.get("chat-smoke") or []
                         if u.get("lane") == lane_id)
        restart_used = sum(1 for t in tokens.values()
                           if t.get("lane") == lane_id
                           and t.get("resource") == "server-restart")
        status = "unassigned"
        agent, chat, last, test = "", "", None, ""
        for j in journals:
            status = j.get("status") or status
            agent = j.get("agent") or agent
            chat = chat or chat_prefix(j)
            activity = lib.journal_last_activity(root, j)
            if activity and (last is None or activity > last):
                last = activity
            test = test or latest_test_result(root, j.get("taskId"))
        rows.append({"laneId": lane_id, "role": lane.get("role"),
                     "wps": lane.get("wps") or [],
                     "agent": agent, "chat": chat,
                     "journalStatus": status,
                     "taskIds": [j.get("taskId") for j in journals],
                     "lastActivityUtc": last.isoformat() if last else None,
                     "claimedFiles": files,
                     "writeScope": lane.get("writeScope") or [],
                     "lastTestResult": test,
                     "chatSmokeUsed": smoke_used,
                     "serverRestartTokens": restart_used})
    return rows


def print_table(plan, rows):
    print(f"plan {plan['planId']}  goalKey={plan.get('goalKey')}  "
          f"serialRequired={plan.get('serialRequired')}")
    print(f"{'lane':<5} {'role':<11} {'chat':<9} {'status':<12} "
          f"{'smoke':<6} {'rst':<4} {'lastActivityUtc':<26} files")
    for row in rows:
        print(f"{row['laneId']:<5} {row['role']:<11} {row['chat'] or '-':<9} "
              f"{row['journalStatus']:<12} {row['chatSmokeUsed']:<6} "
              f"{row['serverRestartTokens']:<4} "
              f"{row['lastActivityUtc'] or '-':<26} "
              f"{', '.join(row['claimedFiles'])[:80]}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--plan", required=True)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    plan = lib.load_plan(root, args.plan)
    if plan is None:
        print(json.dumps({"schemaVersion": SCHEMA, "ok": False,
                          "reason": f"plan-missing:{args.plan}"},
                         ensure_ascii=True))
        return 2
    rows = lane_rows(root, plan)
    if args.json:
        print(json.dumps({"schemaVersion": SCHEMA, "ok": True,
                          "planId": plan["planId"], "lanes": rows},
                         ensure_ascii=True, indent=2))
    else:
        print_table(plan, rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
