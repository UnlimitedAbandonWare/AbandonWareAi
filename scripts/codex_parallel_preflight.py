#!/usr/bin/env python3
"""Single preflight for a Codex parallel-lane session.

Contract DEMO1-DEVIN-CODEX-PARALLEL-LANES-20261002 (DV9, absorbs DV2-DV4).
One read-only command answers: same-goal duplicate? role? who is writing
overlapping paths right now? unclaimed edits? stale claims? quota left?
What to run next?

  python -B scripts/codex_parallel_preflight.py --root . \
      --goal-key "DEMO1-X" --lane plan-ab12cd34/A --scope a.java,b.java \
      --agent codex-lane-a --json

JSON fields: role, lane, verdict, duplicates[], liveWriters[],
unclaimedEdits[], staleClaims{count,candidates[]}, overlappingClaims[],
quota{}, laneViolation[], nextCommands[], summaryKo. Advisory by default;
--strict exits 7 on DUPLICATE_GOAL_LIVE or LANE_VIOLATION.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_parallel_lib as lib
import codex_lane_quota as quota

SCHEMA = "awx.parallel-preflight.v1"


def live_writers(root, scope):
    """Open coop writers + unreleased claims + live leases touching scope."""
    now_writers = []
    state = lib.load_coop_state(root)
    for writer in (state.get("writers") or {}).values():
        if writer.get("state") not in lib.WRITER_OPEN:
            continue
        age = lib.age_seconds(writer.get("heartbeatAtUtc"))
        if age is None or age > 300:
            continue
        hits = lib.overlapping(scope, [lib.canon(c) for c in
                                       writer.get("changedPaths") or []])
        now_writers.append({"kind": "coop-writer",
                            "editBatchId": writer.get("editBatchId"),
                            "agent": writer.get("agent"),
                            "taskId": writer.get("taskId"),
                            "heartbeatAgeSeconds": round(age, 1),
                            "paths": hits or [lib.canon(c) for c in
                                              writer.get("changedPaths") or []]})
    return now_writers


def overlapping_claims(root, scope, exclude_task=None):
    out = []
    for claim in lib.iter_claims(root):
        if claim.get("released") or claim.get("taskId") == exclude_task:
            continue
        hits = lib.overlapping(scope, [lib.canon(t.get("path"))
                                       for t in claim.get("targets") or []])
        hits += lib.overlapping(scope, [lib.canon(r)
                                        for r in claim.get("reservePaths") or []])
        if hits:
            out.append({"taskId": claim.get("taskId"), "agent": claim.get("agent"),
                        "topic": claim.get("topic"), "paths": sorted(set(hits))})
    for lease in lib.iter_source_leases(root):
        if lib.lease_lifecycle(lease) != "live":
            continue
        hits = lib.overlapping(scope, [lib.canon(t)
                                       for t in lease.get("targetPaths") or []])
        if hits:
            out.append({"lease": lease.get("_topic"), "paths": hits,
                        "expiresAtUtc": lease.get("expiresAtUtc")})
    return out


def lane_violations(root, plan, lane_id):
    """Evidence that a lane's tagged journals wrote outside writeScope.
    VERIFIER lanes may write nothing outside data/agent-handoff."""
    lane = lib.plan_lane(plan, lane_id)
    if lane is None:
        return []
    allowed = [lib.canon(p) for p in lane.get("writeScope") or []]
    allowed += ["data/agent-handoff", "var/"]  # own ledger is always allowed
    out = []
    for journal in lib.iter_journals(root):
        plan_tag, lane_tag = lib.lane_tag(journal)
        if plan_tag != plan.get("planId") or lane_tag != lane_id:
            continue
        for path in lib.checkpoint_changed_paths(root, journal.get("taskId")):
            if not any(lib.paths_overlap(path, a) for a in allowed if a):
                out.append({"taskId": journal.get("taskId"),
                            "path": path,
                            "laneRole": lane.get("role"),
                            "rule": "write-outside-writeScope"})
    return out


def quota_summary(root, plan, lane_id):
    if plan is None:
        return {"available": "no-plan"}
    try:
        state = quota.load_state(root, plan["planId"])
    except quota.QuotaError:
        return {"available": "error"}
    now = datetime.now(timezone.utc)
    lane = lib.plan_lane(plan, lane_id)
    policy = plan.get("quotaPolicy") or {}
    used_smoke = len((state.get("consumption") or {}).get("chat-smoke") or [])
    live = quota.live_tokens(state, now)
    restart_taken = any(t.get("resource") == "server-restart" for t in live)
    gradle_live = [t for t in live if t.get("resource") == "gradle"]
    gpu_taken = any(t.get("resource") == "ollama-gpu-load" for t in live)
    lane_cap = (lane or {}).get("quota", {}).get("chatSmoke", 0)
    return {"chatSmokePlanCap": policy.get("chatSmokePlanTotal", 2),
            "chatSmokeUsed": used_smoke,
            "chatSmokeLaneCap": lane_cap,
            "chatSmokeRemaining": max(0, min(policy.get("chatSmokePlanTotal", 2)
                                            - used_smoke, lane_cap)),
            "serverRestart": ("exclusive" if lane and
                              lane.get("quota", {}).get("serverRestart") == "exclusive"
                              else "no"),
            "serverRestartFree": not restart_taken,
            "gradleLive": len(gradle_live),
            "gradleCap": policy.get("gradleConcurrent", 2),
            "ollamaGpuLoadFree": not gpu_taken}


def peer_occupied_paths(root, task_id, scope):
    """중복 목표 피어가 실제로 점유한 파일 — release 전 claim target, 열린 coop
    writer의 changedPaths, live lease targetPaths(lease.taskIdHash=sha256(taskId))
    중 내 scope와 겹치는 것의 합집합."""
    mine = [lib.canon(p) for p in scope or []]
    found = set()
    for claim in lib.iter_claims(root):
        if claim.get("released") or claim.get("taskId") != task_id:
            continue
        found.update(lib.overlapping(
            mine, [lib.canon(t.get("path")) for t in claim.get("targets") or []]))
        found.update(lib.overlapping(
            mine, [lib.canon(r) for r in claim.get("reservePaths") or []]))
    state = lib.load_coop_state(root)
    for writer in (state.get("writers") or {}).values():
        if writer.get("taskId") != task_id or writer.get("state") not in lib.WRITER_OPEN:
            continue
        found.update(lib.overlapping(
            mine, [lib.canon(c) for c in writer.get("changedPaths") or []]))
    task_hash = hashlib.sha256(str(task_id).encode("utf-8")).hexdigest() if task_id else None
    for lease in lib.iter_source_leases(root):
        if lease.get("taskIdHash") != task_hash or lib.lease_lifecycle(lease) != "live":
            continue
        found.update(lib.overlapping(
            mine, [lib.canon(t) for t in lease.get("targetPaths") or []]))
    return sorted(p for p in found if p)


def summary_ko(verdict, role, dups, violations, quota_info):
    if violations:
        return f"레인 위반 {len(violations)}건 — write 범위 밖 변경 감지, INTEGRATOR에 보고"
    if verdict == "DUPLICATE_GOAL_LIVE":
        peers = ", ".join(
            f"{d.get('taskId')}·점유{len(d.get('occupiedPaths') or [])}파일"
            for d in dups[:3]) or "-"
        return (f"같은 목표 라이브 중복 {len(dups)}건({peers}) — 이 채팅은 {role}: "
                "읽기 전용 검증 또는 사용자 확인 후 진행")
    if verdict == "DUPLICATE_GOAL_QUIET":
        return f"조용한 중복 목표 — {role}: 상대 마지막 checkpoint를 baseline으로 인수"
    if verdict == "FOREIGN_CLAIM_OVERLAP":
        return f"다른 목표 claim/lease 겹침 — {role}: 비겹침 부분만 진행 가능"
    smoke = quota_info.get("chatSmokeRemaining") if isinstance(quota_info, dict) else None
    tail = f" · /chat 스모크 잔여 {smoke}회" if isinstance(smoke, int) else ""
    return f"진행 가능 — role={role}{tail}"


def build_next_commands(args, plan, lane_id, role):
    scope_args = " ".join(f"--path {p}" for p in (args.scope or "").split(",")
                          if p.strip())
    task = args.task or "<taskId>"
    cmds = [
        f"python -B scripts/agent_scope_lease.py claim --agent {args.agent or '<agent>'} "
        f"--task {task} {scope_args}".rstrip(),
        f"python -B scripts/coop_verify.py writer-begin --agent {args.agent or '<agent>'} "
        f"--task {task}",
    ]
    if plan is not None and lane_id:
        lane = lib.plan_lane(plan, lane_id)
        lane_q = (lane or {}).get("quota", {})
        if lane_q.get("serverRestart") == "exclusive":
            cmds.append(f"python -B scripts/codex_lane_quota.py acquire --plan "
                        f"{plan['planId']} --lane {lane_id} --resource server-restart "
                        f"--owner {task}")
        cmds.append(f"python -B scripts/codex_lane_quota.py acquire --plan "
                    f"{plan['planId']} --lane {lane_id} --resource gradle "
                    f"--build-host-id {(lane or {}).get('buildHostId', '<id>')} "
                    f"--owner {task}")
    return cmds


def run(root, args):
    plan, lane_id = None, None
    if args.lane:
        plan_id, _, lane_id = args.lane.partition("/")
        plan = lib.load_plan(root, plan_id)
        if plan is None:
            return {"schemaVersion": SCHEMA, "ok": False,
                    "reason": f"plan-missing:{plan_id}"}, 2
    scope = [lib.canon(p) for p in (args.scope or "").split(",") if p.strip()]
    if plan is not None and lane_id:
        lane = lib.plan_lane(plan, lane_id)
        if lane is not None and not scope:
            scope = list(lane.get("writeScope") or lane.get("readScope") or [])
    key = lib.normalize_key(args.goal_key) or \
        (lib.normalize_key(plan.get("goalKey")) if plan
         else "task:" + (args.task or "adhoc"))

    decision = lib.role_decision(root, key, scope, exclude_task=args.task,
                                 live_minutes=args.live_minutes,
                                 plan=plan, lane=lane_id)
    for dup in decision.get("duplicates") or []:
        dup["occupiedPaths"] = peer_occupied_paths(root, dup.get("taskId"), scope)
    if plan is not None and lane_id:
        lane = lib.plan_lane(plan, lane_id)
        if lane is not None and decision["role"] == "OWNER":
            # Plan role wins when no duplicate forces VERIFIER/TAKEOVER.
            decision["role"] = lane.get("role", decision["role"])
    violations = lane_violations(root, plan, lane_id) if plan and lane_id else []
    writers = live_writers(root, scope)
    edits = lib.unclaimed_edits(root, minutes=args.edit_minutes, scope=scope or None)
    stale = lib.stale_claims(root, hours=args.stale_hours)
    overlaps = overlapping_claims(root, scope, exclude_task=args.task)
    quota_info = quota_summary(root, plan, lane_id)

    verdict = decision["verdict"]
    if violations:
        verdict = "LANE_VIOLATION"
    body = {"schemaVersion": SCHEMA, "ok": True,
            "role": decision["role"], "verdict": verdict,
            "lane": args.lane or None, "goalKey": key,
            "duplicates": decision.get("duplicates") or [],
            "blockingClaims": decision.get("blockingClaims") or [],
            "baseline": decision.get("baseline"),
            "freeScope": decision.get("freeScope"),
            "liveWriters": writers,
            "unclaimedEdits": edits,
            "staleClaims": {"count": len(stale), "candidates": stale},
            "overlappingClaims": overlaps,
            "laneViolation": violations,
            "quota": quota_info,
            "nextCommands": build_next_commands(args, plan, lane_id,
                                                decision["role"]),
            "summaryKo": summary_ko(verdict, decision["role"],
                                    decision.get("duplicates") or [],
                                    violations, quota_info)}
    code = 7 if args.strict and (violations or verdict == "DUPLICATE_GOAL_LIVE") else 0
    return body, code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".")
    parser.add_argument("--goal-key", default="")
    parser.add_argument("--lane", default="", help="planId/laneId")
    parser.add_argument("--scope", default="", help="콤마 구분 경로")
    parser.add_argument("--agent", default="")
    parser.add_argument("--task", default="")
    parser.add_argument("--live-minutes", type=int, default=lib.LIVE_MINUTES_DEFAULT)
    parser.add_argument("--edit-minutes", type=int, default=lib.EDIT_MINUTES_DEFAULT)
    parser.add_argument("--stale-hours", type=int, default=lib.STALE_CLAIM_HOURS)
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--json", action="store_true", help="(호환용; 출력은 항상 JSON)")
    args = parser.parse_args(argv)
    body, code = run(Path(args.root).resolve(), args)
    print(json.dumps(body, ensure_ascii=True, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
