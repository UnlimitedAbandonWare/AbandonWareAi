#!/usr/bin/env python3
"""Shared-resource quota tokens for one parallel-lane plan.

Contract DEMO1-DEVIN-CODEX-PARALLEL-LANES-20261002 (DV8). Mutex + counted
quotas so parallel Codex chats share the heavy resources without colliding:
server-restart (mutex), chat-smoke (plan total counter, default cap 2),
gradle (concurrent cap 2, buildHostId unique), ollama-gpu-load (mutex),
project-status-append (INTEGRATOR lane only). Tokens carry TTL + heartbeat +
owner pid; dead owners show as `stale` in status. No auto-reclaim - reclaim
is an explicit command. Advisory only: it composes agent_port_lease /
coop_verify style records, never a new lock on files.

Store (per plan): data/agent-handoff/parallel-lanes/<planId>/quota.json
                  .quota.lock  xb-create mutex, dead-pid reclaim

  python -B scripts/codex_lane_quota.py acquire --plan p --lane A --resource server-restart --owner t1
  python -B scripts/codex_lane_quota.py consume --plan p --lane INT --resource chat-smoke --request-id r1
  python -B scripts/codex_lane_quota.py status --plan p

Exit 0 ok · 2 usage · 7 quota exhausted/conflict · 6 store error.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_parallel_lib as lib
import codex_work_checkpoint as ck

SCHEMA = "awx.lane-quota.v1"
MUTEX_RESOURCES = {"server-restart", "ollama-gpu-load"}
COUNTED_RESOURCES = {"chat-smoke"}
CONCURRENT_RESOURCES = {"gradle"}
ROLE_RESOURCES = {"project-status-append"}
ALL_RESOURCES = MUTEX_RESOURCES | COUNTED_RESOURCES | CONCURRENT_RESOURCES | ROLE_RESOURCES
DEFAULT_CAPS = {"chat-smoke": 2, "gradle": 2}
DEFAULT_TTL_MINUTES = 120


class QuotaError(Exception):
    def __init__(self, reason, code=2):
        super().__init__(reason)
        self.reason, self.code = reason, code


def _holder_pid(lock):
    try:
        holder = json.loads(lock.read_bytes() or b"{}")
        pid = holder.get("pid")
        return pid if isinstance(pid, int) else None
    except (OSError, ValueError):
        return None


@contextmanager
def exclusive_lock(directory):
    """xb-create mutex; reclaim only a provably-dead holder's file."""
    directory.mkdir(parents=True, exist_ok=True)
    lock = directory / ".quota.lock"
    handle = None
    for attempt in range(2):
        try:
            handle = lock.open("xb")
            handle.write(json.dumps({"pid": os.getpid(),
                                     "at": lib.utcnow()}).encode("utf-8"))
            handle.flush()
            break
        except FileExistsError:
            pid = _holder_pid(lock)
            if attempt == 0 and isinstance(pid, int) and not ck.pid_alive(pid):
                try:
                    lock.unlink()
                    continue
                except OSError:
                    pass
            raise QuotaError("quota-store-locked", 6)
    try:
        yield handle
    finally:
        if handle is not None:
            handle.close()
        try:
            lock.unlink()
        except OSError:
            pass


def quota_dir(root, plan_id):
    return Path(root) / lib.PLAN_BASE / str(plan_id)


def quota_path(root, plan_id):
    return quota_dir(root, plan_id) / "quota.json"


def load_state(root, plan_id):
    path = quota_path(root, plan_id)
    if not path.is_file():
        return {"schemaVersion": SCHEMA, "planId": plan_id,
                "tokens": {}, "consumption": {}}
    doc = lib._read_json(path)
    if not isinstance(doc, dict) or doc.get("schemaVersion") != SCHEMA:
        raise QuotaError("quota-state-corrupt", 6)
    return doc


def save_state(root, plan_id, state):
    directory = quota_dir(root, plan_id)
    directory.mkdir(parents=True, exist_ok=True)
    tmp = directory / "quota.json.tmp"
    tmp.write_text(json.dumps(state, ensure_ascii=True, indent=2) + "\n",
                   encoding="utf-8")
    os.replace(tmp, directory / "quota.json")


def token_state(token, now):
    if token.get("state") != "live":
        return token.get("state", "released")
    expiry = lib.parse_iso(token.get("expiresAtUtc"))
    if expiry is not None and expiry <= now:
        return "stale"
    pid = token.get("pid")
    # pid is optional owner-process evidence; 0/absent = TTL-only token.
    if isinstance(pid, int) and pid > 0 and pid != os.getpid() \
            and not ck.pid_alive(pid):
        return "stale"
    return "live"


def live_tokens(state, now, resource=None):
    return [t for t in (state.get("tokens") or {}).values()
            if token_state(t, now) == "live"
            and (resource is None or t.get("resource") == resource)]


def lane_of(plan, lane_id):
    lane = lib.plan_lane(plan, lane_id)
    return lane


def cmd_acquire(root, args):
    plan = lib.load_plan(root, args.plan)
    now = datetime.now(timezone.utc)
    with exclusive_lock(quota_dir(root, args.plan)):
        state = load_state(root, args.plan)
        if args.resource in COUNTED_RESOURCES:
            raise QuotaError(f"use-consume-for:{args.resource}")
        if args.resource not in ALL_RESOURCES:
            raise QuotaError(f"unknown-resource:{args.resource}")
        if args.resource in ROLE_RESOURCES:
            lane = lane_of(plan, args.lane) if plan else None
            if lane is not None and lane.get("role") != "INTEGRATOR":
                raise QuotaError("role-resource-requires-integrator", 7)
        live = live_tokens(state, now, args.resource)
        cap = (plan or {}).get("quotaPolicy", {}).get(
            "gradleConcurrent", DEFAULT_CAPS["gradle"]) \
            if args.resource == "gradle" else 1
        if args.resource == "gradle" and args.build_host_id:
            dup = [t for t in live if t.get("buildHostId") == args.build_host_id]
            if dup:
                raise QuotaError(f"buildHostId-in-use:{args.build_host_id}", 7)
        if len(live) >= cap:
            raise QuotaError(f"quota-exhausted:{args.resource}", 7)
        ttl = max(1, int(args.ttl_minutes or DEFAULT_TTL_MINUTES))
        token = {"tokenId": "lq-" + uuid.uuid4().hex[:12],
                 "resource": args.resource, "lane": args.lane,
                 "owner": args.owner or "", "taskId": args.task or "",
                 "buildHostId": args.build_host_id or "",
                 "pid": args.pid if isinstance(args.pid, int) else 0,
                 "state": "live", "acquiredAtUtc": now.isoformat(),
                 "heartbeatAtUtc": now.isoformat(),
                 "expiresAtUtc": (now + timedelta(minutes=ttl)).isoformat()}
        state["tokens"][token["tokenId"]] = token
        save_state(root, args.plan, state)
    print(json.dumps({"schemaVersion": SCHEMA, "ok": True, "token": token},
                     ensure_ascii=True))
    return 0


def cmd_release(root, args):
    with exclusive_lock(quota_dir(root, args.plan)):
        state = load_state(root, args.plan)
        token = (state.get("tokens") or {}).get(args.token)
        if token is None:
            raise QuotaError("token-missing")
        if args.owner and token.get("owner") != args.owner \
                and token.get("taskId") != args.owner:
            raise QuotaError("token-owner-mismatch", 7)
        token["state"] = "released"
        token["releasedAtUtc"] = lib.utcnow()
        save_state(root, args.plan, state)
    print(json.dumps({"schemaVersion": SCHEMA, "ok": True,
                      "tokenId": args.token, "state": "released"},
                     ensure_ascii=True))
    return 0


def cmd_heartbeat(root, args):
    now = datetime.now(timezone.utc)
    with exclusive_lock(quota_dir(root, args.plan)):
        state = load_state(root, args.plan)
        token = (state.get("tokens") or {}).get(args.token)
        if token is None:
            raise QuotaError("token-missing")
        if token_state(token, now) != "live":
            raise QuotaError("token-not-live", 7)
        ttl = max(1, int(args.ttl_minutes or DEFAULT_TTL_MINUTES))
        token["heartbeatAtUtc"] = now.isoformat()
        token["expiresAtUtc"] = (now + timedelta(minutes=ttl)).isoformat()
        save_state(root, args.plan, state)
    print(json.dumps({"schemaVersion": SCHEMA, "ok": True,
                      "tokenId": args.token, "heartbeatAtUtc": now.isoformat()},
                     ensure_ascii=True))
    return 0


def cmd_consume(root, args):
    if args.resource not in COUNTED_RESOURCES:
        raise QuotaError(f"not-counted-resource:{args.resource}")
    plan = lib.load_plan(root, args.plan)
    cap = (plan or {}).get("quotaPolicy", {}).get(
        "chatSmokePlanTotal", DEFAULT_CAPS["chat-smoke"])
    cap = int(args.cap or cap)
    with exclusive_lock(quota_dir(root, args.plan)):
        state = load_state(root, args.plan)
        used = state["consumption"].setdefault(args.resource, [])
        if args.request_id and any(u.get("requestId") == args.request_id
                                   for u in used):
            print(json.dumps({"schemaVersion": SCHEMA, "ok": True,
                              "dedup": True, "used": len(used), "cap": cap},
                             ensure_ascii=True))
            return 0
        if len(used) >= cap:
            raise QuotaError(f"quota-exhausted:{args.resource}", 7)
        used.append({"requestId": args.request_id or uuid.uuid4().hex[:12],
                     "lane": args.lane, "owner": args.owner or "",
                     "taskId": args.task or "", "atUtc": lib.utcnow()})
        save_state(root, args.plan, state)
    print(json.dumps({"schemaVersion": SCHEMA, "ok": True,
                      "used": len(used), "cap": cap}, ensure_ascii=True))
    return 0


def cmd_reclaim(root, args):
    """Explicit reclaim of a proven-stale token only; live is never touched."""
    now = datetime.now(timezone.utc)
    with exclusive_lock(quota_dir(root, args.plan)):
        state = load_state(root, args.plan)
        token = (state.get("tokens") or {}).get(args.token)
        if token is None:
            raise QuotaError("token-missing")
        if token_state(token, now) != "stale":
            raise QuotaError("token-not-stale", 7)
        token["state"] = "reclaimed"
        token["reclaimedAtUtc"] = now.isoformat()
        save_state(root, args.plan, state)
    print(json.dumps({"schemaVersion": SCHEMA, "ok": True,
                      "tokenId": args.token, "state": "reclaimed"},
                     ensure_ascii=True))
    return 0


def cmd_status(root, args):
    now = datetime.now(timezone.utc)
    state = load_state(root, args.plan)
    tokens = []
    for token in (state.get("tokens") or {}).values():
        row = dict(token)
        row["computedState"] = token_state(token, now)
        tokens.append(row)
    plan = lib.load_plan(root, args.plan)
    consumption = {k: {"used": len(v),
                       "cap": (plan or {}).get("quotaPolicy", {})
                              .get("chatSmokePlanTotal", DEFAULT_CAPS["chat-smoke"])
                       if k == "chat-smoke" else None}
                   for k, v in (state.get("consumption") or {}).items()}
    print(json.dumps({"schemaVersion": SCHEMA, "ok": True,
                      "planId": args.plan, "tokens": tokens,
                      "consumption": consumption,
                      "caps": (plan or {}).get("quotaPolicy") or DEFAULT_CAPS},
                     ensure_ascii=True))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".")
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("acquire", "release", "heartbeat", "consume", "reclaim", "status"):
        p = sub.add_parser(name)
        p.add_argument("--plan", required=True)
        p.add_argument("--lane", default="")
        p.add_argument("--owner", default="")
        p.add_argument("--task", default="")
        if name in ("acquire", "consume"):
            p.add_argument("--resource", required=True, choices=sorted(ALL_RESOURCES))
        if name == "acquire":
            p.add_argument("--build-host-id", default="")
            p.add_argument("--pid", type=int, default=None)
            p.add_argument("--ttl-minutes", type=int, default=DEFAULT_TTL_MINUTES)
        if name == "heartbeat":
            p.add_argument("--ttl-minutes", type=int, default=DEFAULT_TTL_MINUTES)
        if name in ("release", "heartbeat", "reclaim"):
            p.add_argument("--token", required=True)
        if name == "consume":
            p.add_argument("--request-id", default="")
            p.add_argument("--cap", type=int, default=0)
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    try:
        return {"acquire": cmd_acquire, "release": cmd_release,
                "heartbeat": cmd_heartbeat, "consume": cmd_consume,
                "reclaim": cmd_reclaim, "status": cmd_status}[args.action](root, args)
    except QuotaError as exc:
        print(json.dumps({"schemaVersion": SCHEMA, "ok": False,
                          "reason": exc.reason}, ensure_ascii=True))
        return exc.code


if __name__ == "__main__":
    sys.exit(main())
