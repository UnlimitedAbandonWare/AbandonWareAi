#!/usr/bin/env python3
"""orchestra_cadence_barrier.py — multi-session cadence barrier probes.

pre-flight  : read-only <1s entry probe for a new agent session.
              - stale_locks      : source-edit locks under
                __patch_drop__/source-edit-locks/*.lock that are expired
                (expiresAtUtc < now), corrupt (lease.json unreadable), or
                orphan (ownerProcessId>0 but the process is absent).
              - zombie_journals  : in_progress work journals under
                data/agent-handoff/codex-autonomy/*/journal.json whose
                updatedAtUtc is older than --stale-hours (default 24h).
              - ready/wait_reason: with --targets, false only when a
                requested path overlaps a live/stale lock or a zombie
                journal's plannedScope; without targets, false while any
                residue exists (advisory explains residue is target-scoped,
                never a repository-wide hold).

post-barrier: atomic handoff check after an orchestra signal is emitted.
              1) signal file under data/agent-handoff/orchestra exists,
                 is non-empty, parses as JSON, and is size/mtime-settled
                 (atomic_fs_settled) — schema validity is reported when
                 scripts/orchestra_signal.py is importable but never gates;
              2) optional --verify-port waits for 127.0.0.1:<port> to accept
                 a TCP connection (runtime warmup, bounded by --timeout-sec);
              3) ready_for_next_agent=true only when every requested check
                 passed.

Read-only by contract: no lock/journal/signal is created, deleted or
reclaimed; no network call except the loopback TCP probe on --verify-port.
Exit codes: 0 ready/ok, 3 not-ready, 2 usage, 1 error.
"""
from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCHEMA = "awx.orchestra-cadence-barrier.v1"
SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parent
LOCKS_SUBDIR = Path("__patch_drop__") / "source-edit-locks"
JOURNAL_SUBDIR = Path("data") / "agent-handoff" / "codex-autonomy"
ORCHESTRA_SUBDIR = Path("data") / "agent-handoff" / "orchestra"
STALE_JOURNAL_HOURS = 24.0
LOCK_STALE_HOURS = 24.0


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _parse_ts(value: str):
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def _pid_alive(pid: int):
    """True=alive, False=proven-absent, None=unknown. Windows OpenProcess
    first; os.kill(pid,0) fallback elsewhere. Unknown never proves orphan."""
    if pid <= 0:
        return None
    if os.name == "nt":
        try:
            import ctypes
            k32 = ctypes.windll.kernel32
            handle = k32.OpenProcess(0x1000, False, pid)
            if handle:
                code = ctypes.c_ulong(0)
                ok = k32.GetExitCodeProcess(handle, ctypes.byref(code))
                k32.CloseHandle(handle)
                # A terminated process object can stay resolvable while a
                # handle is held open — only STILL_ACTIVE means alive.
                if ok:
                    return True if code.value == 259 else False
                return True
            err = k32.GetLastError()
            if err == 87:  # ERROR_INVALID_PARAMETER: no such process
                return False
            if err == 5:  # ACCESS_DENIED: exists but protected
                return True
            return None
        except (AttributeError, OSError):
            return None
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return None
    return True


def _canon(path: str) -> str:
    p = str(path or "").replace("\\", "/").strip().lower()
    while p.startswith("./"):
        p = p[2:]
    return p.rstrip("/")


def _overlap(a: str, b: str) -> bool:
    """Exact match or prefix either direction (agent-scope-lease rule)."""
    return bool(a and b) and (a == b or a.startswith(b + "/")
                              or b.startswith(a + "/"))


def _scan_locks(root: Path) -> dict:
    base = root / LOCKS_SUBDIR
    rows, stale = [], []
    now = _utcnow()
    if not base.is_dir():
        return {"dirPresent": False, "total": 0, "live": 0,
                "stale": 0, "stale_locks": [], "locks": []}
    for lock_dir in sorted(base.glob("*.lock")):
        row = {"topic": lock_dir.name[:-5], "lifecycle": "unknown",
               "reason": "", "ownerId": None, "expiresAtUtc": None,
               "ownerProcessId": None, "targetPaths": []}
        lease_file = lock_dir / "lease.json"
        try:
            lease = json.loads(lease_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            row["lifecycle"] = "orphan"
            row["reason"] = "lease-json-unreadable"
            stale.append(row)
            rows.append(row)
            continue
        row["ownerId"] = lease.get("ownerId")
        row["expiresAtUtc"] = lease.get("expiresAtUtc") or lease.get("expiresAt")
        row["ownerProcessId"] = lease.get("ownerProcessId")
        row["targetPaths"] = lease.get("targetPaths") or []
        expires = _parse_ts(row["expiresAtUtc"])
        pid = lease.get("ownerProcessId") or 0
        try:
            pid = int(pid)
        except (TypeError, ValueError):
            pid = 0
        alive = _pid_alive(pid)
        if expires is not None and expires < now:
            row["lifecycle"] = "stale"
            row["reason"] = "expired" if alive is not False \
                else "expired+owner-process-absent"
            row["expiredMinutes"] = round(
                (now - expires).total_seconds() / 60, 1)
        elif alive is False:
            row["lifecycle"] = "orphan"
            row["reason"] = "owner-process-absent"
        elif alive is True or pid > 0:
            row["lifecycle"] = "live"
        else:
            # expiresAtUtc in the future, no owner pid evidence
            row["lifecycle"] = "live"
            row["reason"] = "owner-evidence-needed"
        if row["lifecycle"] in ("stale", "orphan"):
            stale.append(row)
        rows.append(row)
    return {"dirPresent": True, "total": len(rows),
            "live": len(rows) - len(stale), "stale": len(stale),
            "stale_locks": stale, "locks": rows}


def _scan_journals(root: Path, stale_hours: float) -> dict:
    base = root / JOURNAL_SUBDIR
    rows, zombies = [], []
    now = _utcnow()
    if base.is_dir():
        for jf in sorted(base.glob("*/journal.json")):
            try:
                j = json.loads(jf.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if str(j.get("status")) != "in_progress":
                continue
            stamp = _parse_ts(j.get("updatedAtUtc"))
            age_h = round((now - stamp).total_seconds() / 3600, 1) \
                if stamp else None
            row = {"taskId": j.get("taskId"), "agent": j.get("agent"),
                   "ageHours": age_h,
                   "purpose": str(j.get("purpose") or "")[:120],
                   "plannedScope": j.get("plannedScope") or []}
            rows.append(row)
            if age_h is not None and age_h > stale_hours:
                zombies.append(row)
    return {"dirPresent": base.is_dir(), "inProgress": len(rows),
            "staleThresholdHours": stale_hours, "zombie_journals": zombies,
            "journals": rows}


def _devin_home(override: str = None) -> Path:
    if override:
        return Path(override)
    appdata = os.environ.get("APPDATA") or str(
        Path.home() / "AppData" / "Roaming")
    return Path(os.environ.get("DEVIN_HOME", str(Path(appdata) / "devin")))


def _devin_session_locks(devin_home: Path, stale_hours: float) -> dict:
    directory = devin_home / "cli" / "session_locks"
    out = {"dir": str(directory), "present": directory.is_dir(),
           "count": 0, "staleCount": 0, "pileupWarn": False,
           "staleThresholdHours": stale_hours}
    if not directory.is_dir():
        return out
    cutoff = time.time() - stale_hours * 3600
    stale = 0
    count = 0
    for p in directory.glob("*.lock"):
        if not p.is_file():
            continue
        count += 1
        try:
            if p.stat().st_mtime < cutoff:
                stale += 1
        except OSError:
            continue
    out.update({"count": count, "staleCount": stale,
                "pileupWarn": count > 20})
    return out


def _load_targets(manifest: str):
    data = json.loads(Path(manifest).read_text(encoding="utf-8"))
    items = data.get("targets") if isinstance(data, dict) else data
    paths = []
    for item in items or []:
        p = item.get("path") if isinstance(item, dict) else item
        if p:
            paths.append(str(p))
    return paths


def cmd_pre_flight(args) -> tuple:
    started = time.monotonic()
    root = Path(args.root).resolve()
    locks = _scan_locks(root)
    journals = _scan_journals(root, args.stale_hours)
    devin_locks = _devin_session_locks(_devin_home(args.devin_home),
                                     args.lock_stale_hours)
    requested = _load_targets(args.targets) if args.targets else []
    canon_targets = [_canon(p) for p in requested]
    conflicts = []
    for lock in locks["locks"]:
        hits = [t for t in canon_targets
                if any(_overlap(t, _canon(tp))
                       for tp in lock.get("targetPaths") or [])]
        if hits:
            conflicts.append({"topic": lock["topic"],
                              "lifecycle": lock["lifecycle"],
                              "paths": sorted(hits)})
    zombie_hits = []
    for z in journals["zombie_journals"]:
        hits = [t for t in canon_targets
                if any(_overlap(t, _canon(sp))
                       for sp in z.get("plannedScope") or [])]
        if hits:
            zombie_hits.append({"taskId": z["taskId"], "paths": sorted(hits)})

    wait_reason = None
    if requested:
        live_conflicts = [c for c in conflicts if c["lifecycle"] == "live"]
        stale_conflicts = [c for c in conflicts
                           if c["lifecycle"] in ("stale", "orphan")]
        if live_conflicts:
            wait_reason = "live-lease-conflict:" + ",".join(
                c["topic"] for c in live_conflicts)
            ready = False
        elif stale_conflicts:
            wait_reason = ("stale-lock-overlap-reclaimable:" + ",".join(
                c["topic"] for c in stale_conflicts)
                + " (lease_conflict_autoflow.py reclaim --dry-run first)")
            ready = False
        elif zombie_hits:
            wait_reason = "zombie-journal-scope-overlap:" + ",".join(
                z["taskId"] for z in zombie_hits)
            ready = False
        else:
            ready = True
    else:
        residue = locks["stale"] + len(journals["zombie_journals"])
        ready = residue == 0
        if not ready:
            wait_reason = ("residue-present:stale_locks=%d,zombie_journals=%d"
                           % (locks["stale"], len(journals["zombie_journals"])))
    result = {
        "schemaVersion": SCHEMA, "action": "pre-flight",
        "generatedAtUtc": _utcnow().isoformat(),
        "locks": {k: locks[k] for k in
                  ("dirPresent", "total", "live", "stale", "stale_locks")},
        "journals": {k: journals[k] for k in
                     ("dirPresent", "inProgress", "staleThresholdHours",
                      "zombie_journals")},
        "devinSessionLocks": devin_locks,
        "targets": {"requested": len(requested), "conflicts": conflicts,
                    "zombieJournalOverlaps": zombie_hits},
        "ready": ready,
        "wait_reason": wait_reason,
        "advisory": ("residue is target-scoped, never a repository-wide "
                     "hold; pass --targets targets.json for a scoped "
                     "verdict; live locks and foreign journals are never "
                     "force-released by this probe"),
        "elapsedMs": round((time.monotonic() - started) * 1000, 1),
    }
    return 0 if ready else 3, result


def _find_signal(store: Path, sid: str):
    for part in ("inbox", "outbox"):
        part_dir = store / part
        if part_dir.is_dir():
            for path in sorted(part_dir.glob("*/" + sid + ".json")):
                return path
    hit = store / "archive" / (sid + ".json")
    return hit if hit.is_file() else None


def _signal_check(store: Path, sid: str) -> dict:
    out = {"signalId": sid, "found": False, "path": None, "bytes": 0,
           "jsonValid": False, "schemaValid": None, "schemaErrors": [],
           "atomic_fs_settled": False}
    path = _find_signal(store, sid)
    if path is None or not path.is_file():
        return out
    try:
        first = path.stat()
        body = path.read_text(encoding="utf-8")
        time.sleep(0.05)
        second = path.stat()
    except OSError:
        return out
    out["found"] = True
    out["path"] = str(path)
    out["bytes"] = first.st_size
    try:
        sig = json.loads(body)
        out["jsonValid"] = True
    except ValueError:
        sig = None
    settled = (first.st_size > 0 and out["jsonValid"]
               and first.st_size == second.st_size
               and first.st_mtime_ns == second.st_mtime_ns)
    out["atomic_fs_settled"] = settled
    if sig is not None:
        try:
            sys.path.insert(0, str(SCRIPTS))
            import orchestra_signal  # noqa: E402
            errs = orchestra_signal.validate_signal(sig)
            out["schemaValid"] = not errs
            out["schemaErrors"] = errs[:5]
        except Exception as exc:  # validation is advisory, never gates
            out["schemaValid"] = None
            out["schemaErrors"] = ["validator-unavailable:%s"
                                   % type(exc).__name__]
    return out


def _port_check(port: int, timeout_sec: float) -> dict:
    started = time.monotonic()
    deadline = started + max(timeout_sec, 0.0)
    opened = False
    attempts = 0
    while True:
        attempts += 1
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                opened = True
                break
        except OSError:
            if time.monotonic() >= deadline:
                break
            time.sleep(min(0.25, max(deadline - time.monotonic(), 0.0)))
    return {"requested": True, "host": "127.0.0.1", "port": port,
            "open": opened, "attempts": attempts,
            "waitedMs": round((time.monotonic() - started) * 1000, 1),
            "timeoutSec": timeout_sec}


def cmd_post_barrier(args) -> tuple:
    root = Path(args.root).resolve()
    store = Path(args.store) if os.path.isabs(args.store) \
        else root / args.store
    signal = _signal_check(store, args.signal_id)
    port = {"requested": False}
    if args.verify_port is not None:
        port = _port_check(args.verify_port, args.timeout_sec)
    ready = signal["atomic_fs_settled"] and (
        not port["requested"] or port["open"])
    result = {
        "schemaVersion": SCHEMA, "action": "post-barrier",
        "generatedAtUtc": _utcnow().isoformat(),
        "signal": signal,
        "port": port,
        "ready_for_next_agent": ready,
        "wait_reason": None if ready else (
            "signal-not-settled" if not signal["atomic_fs_settled"]
            else "port-not-open:%d" % args.verify_port),
    }
    return 0 if ready else 3, result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    pre = sub.add_parser("pre-flight",
                         help="entry probe: stale locks + zombie journals")
    pre.add_argument("--root", default=str(ROOT))
    pre.add_argument("--targets", default=None,
                     help="TargetManifest JSON file "
                          "({\"targets\":[{\"path\":...}]} or list)")
    pre.add_argument("--stale-hours", type=float,
                     default=STALE_JOURNAL_HOURS)
    pre.add_argument("--lock-stale-hours", dest="lock_stale_hours",
                     type=float, default=LOCK_STALE_HOURS)
    pre.add_argument("--devin-home", dest="devin_home", default=None)
    post = sub.add_parser("post-barrier",
                          help="handoff check: signal settled + port warmup")
    post.add_argument("--root", default=str(ROOT))
    post.add_argument("--store", default=str(ORCHESTRA_SUBDIR),
                      help="signal store dir (relative to --root or abs)")
    post.add_argument("--signal-id", required=True)
    post.add_argument("--verify-port", type=int, default=None)
    post.add_argument("--timeout-sec", type=float, default=10.0)
    args = parser.parse_args(argv)

    try:
        if args.action == "pre-flight":
            code, result = cmd_pre_flight(args)
        else:
            code, result = cmd_post_barrier(args)
    except (OSError, ValueError) as exc:
        print(json.dumps({"schemaVersion": SCHEMA, "action": args.action,
                          "status": "error", "reason": str(exc)},
                         ensure_ascii=False))
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
