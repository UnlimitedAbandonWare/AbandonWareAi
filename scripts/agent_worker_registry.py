#!/usr/bin/env python3
"""agent_worker_registry.py — single-JSON worker session registry for
multi-agent orchestration (Codex spawn_agent/wait/followup_task workers,
glm_worker-style helpers, Devin/Grok/Cline peers).

Why: weekly session analysis (2026-09-17..24, .devin/PROMPTS/
codex-weekly-skill-directive-20260924.md P1) showed blocked/error worker
events clustering on orchestration days with no durable record of *why*.
This registry keeps one JSON file with per-worker role, state, last
heartbeat and a mandatory cause code on every blocked/error transition,
plus the shared retry policy (max 1 retry; repeated same-cause -> abort).

Store: <root>/data/agent-handoff/workers/registry.json (atomic rewrite;
short .registry.lock OS-handle mutex, 5s bounded wait).

Actions:
  register  --id W --role R [--task T] [--agent A] [--ttl-sec N] [--note S]
  heartbeat --id W
  instruct  --id W --text S          # orchestrator -> worker directive event
  report    --id W --text S          # worker -> orchestrator report event
  classify  --id W --state blocked|error --cause CODE [--note S]
  retry-decision --id W              # JSON {decision: retry|abort-report|not-applicable}
  retry     --id W                   # applies policy; refuses on abort-report
  close     --id W [--result done|aborted]
  sweep [--mark]                     # detect heartbeat-expired workers;
                                     # --mark classifies them blocked/no-response
  status [--id W] / list             # JSON inventory with computed liveness

Cause codes (required on blocked/error, recorded forever):
  lease-conflict | no-response | context-overflow | tool-failure | unknown

Exit codes: 0 ok, 2 usage/io error, 3 not-found, 4 policy-refused.
Raw prompt/report text is truncated to 200 chars; never store secrets.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCHEMA = "awx.agent-worker-registry.v1"
DEFAULT_TTL_SEC = 600
MAX_RETRIES = 1
NOTE_MAX = 200
HISTORY_MAX = 50
CAUSES = ("lease-conflict", "no-response", "context-overflow",
          "tool-failure", "unknown")
TERMINAL = ("done", "aborted")
ACTIVE_STATES = ("registered", "active")


def utcnow() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def registry_path(root: Path) -> Path:
    return root / "data" / "agent-handoff" / "workers" / "registry.json"


def _empty() -> dict:
    return {"schemaVersion": SCHEMA, "updatedAt": utcnow(), "workers": {}}


def load(root: Path) -> dict:
    path = registry_path(root)
    if not path.is_file():
        return _empty()
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return _empty()
    if not isinstance(data, dict) or not isinstance(data.get("workers"), dict):
        return _empty()
    return data


def save(root: Path, data: dict) -> None:
    path = registry_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    lock = path.with_suffix(".lock")
    handle = None
    deadline = time.monotonic() + 5
    while handle is None:
        try:
            handle = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            if time.monotonic() > deadline:
                raise TimeoutError("registry-lock-timeout")
            time.sleep(0.05)
        except OSError:
            handle = False  # lock unsupported -> best-effort atomic rewrite
    try:
        data["updatedAt"] = utcnow()
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=True, indent=1),
                       encoding="utf-8")
        os.replace(str(tmp), str(path))
    finally:
        if handle:
            os.close(handle)
            try:
                lock.unlink()
            except OSError:
                pass


def _clip(text) -> str:
    return str(text or "")[:NOTE_MAX]


def _worker(reg: dict, wid: str) -> dict | None:
    worker = reg["workers"].get(wid)
    return worker if isinstance(worker, dict) else None


def _event(worker: dict, kind: str, **fields) -> None:
    event = {"at": utcnow(), "kind": kind}
    event.update(fields)
    history = worker.setdefault("history", [])
    history.append(event)
    del history[:-HISTORY_MAX]


def liveness(worker: dict, now: float | None = None) -> str:
    if worker.get("status") in TERMINAL or worker.get("status") == "closed":
        return "closed"
    last = worker.get("lastHeartbeatAt") or worker.get("registeredAt")
    ttl = int(worker.get("heartbeatTtlSec") or DEFAULT_TTL_SEC)
    try:
        stamp = datetime.strptime(str(last), "%Y-%m-%dT%H:%M:%S.%fZ") \
            .replace(tzinfo=timezone.utc).timestamp()
    except (ValueError, TypeError):
        return "unknown"
    age = (now if now is not None else time.time()) - stamp
    return "fresh" if age <= ttl else "stale"


def _public(worker: dict, now: float | None = None) -> dict:
    out = {key: worker.get(key) for key in
           ("workerId", "role", "taskId", "agent", "status", "causeCode",
            "retries", "registeredAt", "lastHeartbeatAt", "heartbeatTtlSec",
            "closedAt", "result")}
    out["liveness"] = liveness(worker, now)
    out["historyCount"] = len(worker.get("history") or [])
    return out


def cmd_register(reg, args) -> dict:
    worker = _worker(reg, args.id) or {
        "workerId": args.id, "retries": 0, "history": [],
    }
    if worker.get("status") in TERMINAL or worker.get("status") == "closed":
        return {"ok": False, "error": "worker-closed", "workerId": args.id}
    worker.update({
        "workerId": args.id,
        "role": _clip(args.role),
        "taskId": _clip(args.task),
        "agent": _clip(args.agent),
        "status": worker.get("status") if worker.get("status") in ACTIVE_STATES
                  else "registered",
        "registeredAt": worker.get("registeredAt") or utcnow(),
        "lastHeartbeatAt": utcnow(),
        "heartbeatTtlSec": int(args.ttl_sec or DEFAULT_TTL_SEC),
    })
    _event(worker, "register", role=_clip(args.role), note=_clip(args.note))
    reg["workers"][args.id] = worker
    return {"ok": True, "worker": _public(worker)}


def cmd_heartbeat(reg, args) -> dict:
    worker = _worker(reg, args.id)
    if not worker:
        return {"ok": False, "error": "worker-not-found", "workerId": args.id}
    worker["lastHeartbeatAt"] = utcnow()
    if worker.get("status") == "registered":
        worker["status"] = "active"
    _event(worker, "heartbeat")
    return {"ok": True, "worker": _public(worker)}


def _cmd_note(reg, args, kind) -> dict:
    worker = _worker(reg, args.id)
    if not worker:
        return {"ok": False, "error": "worker-not-found", "workerId": args.id}
    _event(worker, kind, text=_clip(args.text))
    if kind == "instruct" and worker.get("status") == "registered":
        worker["status"] = "active"
    return {"ok": True, "worker": _public(worker)}


def cmd_classify(reg, args) -> dict:
    worker = _worker(reg, args.id)
    if not worker:
        return {"ok": False, "error": "worker-not-found", "workerId": args.id}
    worker["status"] = args.state
    worker["causeCode"] = args.cause
    worker["causeAt"] = utcnow()
    _event(worker, "classify", state=args.state, cause=args.cause,
           note=_clip(args.note))
    return {"ok": True, "worker": _public(worker),
            "policy": retry_decision(worker)}


def _same_cause_streak(worker: dict) -> int:
    streak = 0
    for event in reversed(worker.get("history") or []):
        if event.get("kind") == "retry":
            continue
        if event.get("kind") != "classify":
            break
        if event.get("cause") == worker.get("causeCode"):
            streak += 1
        else:
            break
    return streak


def retry_decision(worker: dict) -> dict:
    status = worker.get("status")
    if status not in ("blocked", "error"):
        return {"decision": "not-applicable", "reason": "not-blocked-or-error"}
    if _same_cause_streak(worker) >= 2:
        return {"decision": "abort-report",
                "reason": "same-cause-repeated"}
    if int(worker.get("retries") or 0) >= MAX_RETRIES:
        return {"decision": "abort-report",
                "reason": f"max-retries-{MAX_RETRIES}"}
    return {"decision": "retry",
            "reason": "first-failure",
            "cause": worker.get("causeCode")}


def cmd_retry(reg, args) -> tuple[dict, int]:
    worker = _worker(reg, args.id)
    if not worker:
        return {"ok": False, "error": "worker-not-found",
                "workerId": args.id}, 3
    decision = retry_decision(worker)
    if decision["decision"] != "retry":
        return {"ok": False, "policy": decision}, 4
    worker["retries"] = int(worker.get("retries") or 0) + 1
    worker["status"] = "active"
    worker["causeCode"] = None
    worker["lastHeartbeatAt"] = utcnow()
    _event(worker, "retry", attempt=worker["retries"])
    return {"ok": True, "worker": _public(worker), "policy": decision}, 0


def cmd_close(reg, args) -> dict:
    worker = _worker(reg, args.id)
    if not worker:
        return {"ok": False, "error": "worker-not-found", "workerId": args.id}
    worker["status"] = "closed"
    worker["result"] = args.result
    worker["closedAt"] = utcnow()
    _event(worker, "close", result=args.result)
    return {"ok": True, "worker": _public(worker)}


def cmd_sweep(reg, args, now: float) -> dict:
    stale = []
    for wid, worker in reg["workers"].items():
        if not isinstance(worker, dict):
            continue
        if liveness(worker, now) != "stale":
            continue
        entry = {"workerId": wid, "status": worker.get("status"),
                 "lastHeartbeatAt": worker.get("lastHeartbeatAt")}
        if args.mark and worker.get("status") in ACTIVE_STATES:
            worker["status"] = "blocked"
            worker["causeCode"] = "no-response"
            worker["causeAt"] = utcnow()
            _event(worker, "classify", state="blocked",
                   cause="no-response", note="sweep-auto")
            entry["marked"] = "blocked/no-response"
        stale.append(entry)
    return {"ok": True, "staleWorkers": stale, "marked": bool(args.mark)}


def cmd_list(reg, args, now: float) -> dict:
    workers = [_public(w, now) for w in reg["workers"].values()
               if isinstance(w, dict)]
    workers.sort(key=lambda w: str(w.get("registeredAt") or ""))
    if args.id:
        worker = _worker(reg, args.id)
        if not worker:
            return {"ok": False, "error": "worker-not-found",
                    "workerId": args.id}
        out = _public(worker, now)
        out["history"] = worker.get("history") or []
        out["policy"] = retry_decision(worker)
        return {"ok": True, "worker": out}
    counts = {}
    for w in workers:
        counts[w["status"] or "?"] = counts.get(w["status"] or "?", 0) + 1
    return {"ok": True, "count": len(workers), "counts": counts,
            "workers": workers}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=(
        "register", "heartbeat", "instruct", "report", "classify",
        "retry-decision", "retry", "close", "sweep", "status", "list"))
    parser.add_argument("--root", default=None)
    parser.add_argument("--id", dest="id")
    parser.add_argument("--role")
    parser.add_argument("--task")
    parser.add_argument("--agent")
    parser.add_argument("--ttl-sec", type=int, default=None)
    parser.add_argument("--state", choices=("blocked", "error"))
    parser.add_argument("--cause", choices=CAUSES)
    parser.add_argument("--note", default="")
    parser.add_argument("--text", default="")
    parser.add_argument("--result", default="done",
                        choices=("done", "aborted"))
    parser.add_argument("--mark", action="store_true")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else \
        Path(__file__).resolve().parents[1]
    reg = load(root)
    now = time.time()

    needs_id = ("heartbeat", "instruct", "report", "classify",
                "retry-decision", "retry", "close")
    if args.action in needs_id and not args.id:
        print(json.dumps({"ok": False, "error": "id-required"}))
        return 2
    if args.action == "register" and (not args.id or not args.role):
        print(json.dumps({"ok": False, "error": "id-and-role-required"}))
        return 2
    if args.action == "classify" and (not args.state or not args.cause):
        # cause code is mandatory: directive P1 requires 100% recorded causes
        print(json.dumps({"ok": False,
                          "error": "state-and-cause-required",
                          "causes": list(CAUSES)}))
        return 2

    exit_code = 0
    if args.action == "register":
        result = cmd_register(reg, args)
    elif args.action == "heartbeat":
        result = cmd_heartbeat(reg, args)
    elif args.action in ("instruct", "report"):
        result = _cmd_note(reg, args, args.action)
    elif args.action == "classify":
        result = cmd_classify(reg, args)
    elif args.action == "retry-decision":
        worker = _worker(reg, args.id)
        if not worker:
            print(json.dumps({"ok": False, "error": "worker-not-found",
                              "workerId": args.id}))
            return 3
        result = {"ok": True, "policy": retry_decision(worker)}
    elif args.action == "retry":
        result, exit_code = cmd_retry(reg, args)
    elif args.action == "close":
        result = cmd_close(reg, args)
    elif args.action == "sweep":
        result = cmd_sweep(reg, args, now)
    else:
        result = cmd_list(reg, args, now)

    if result.get("ok") and args.action not in ("status", "list",
                                              "retry-decision", "sweep"):
        try:
            save(root, reg)
        except (OSError, TimeoutError) as error:
            result = {"ok": False, "error": str(error)}
            exit_code = 2
    elif args.action == "sweep" and args.mark and result.get("ok"):
        try:
            save(root, reg)
        except (OSError, TimeoutError) as error:
            result = {"ok": False, "error": str(error)}
            exit_code = 2
    if not result.get("ok") and exit_code == 0:
        exit_code = 3 if "not-found" in str(result.get("error")) else 2
    result["schemaVersion"] = SCHEMA
    print(json.dumps(result, ensure_ascii=True))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
