#!/usr/bin/env python3
"""devin_session_guard.py — diagnose Devin CLI process and session-lock pileup.

Read-only unless --force is passed. Defaults are dry-run.

Actions:
  status         process count, working set, lock counts, sessions.db and WAL sizes
  clean-locks    dead-PID *.lock files only. --force moves them into
                 cli/session_locks_backup_YYYYMMDD. Never unlinks in place.
  reap-zombies   --force ends only parent-dead Devin leaves. The interactive
                 root (parent is alive and is not Devin) and the largest
                 working set are never killed. If that root cannot be told
                 apart, print the PID list and HOLD.
  checkpoint     WAL/backup guidance. --force runs PRAGMA wal_checkpoint(PASSIVE)
                 only when the database can be opened. Never deletes sessions.db.

Exit codes: 0 ok, 2 usage, 4 HOLD, 1 error.
JSON: pass --json. Secrets and lock-file bodies are not printed.
"""
import argparse
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCHEMA = "awx.devin-session-guard.v1"
PROCESS_WARN = 12
LOCK_WARN = 20
DEVIN_EXE = "devin.exe"
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
PROCESS_TERMINATE = 0x0001
TH32CS_SNAPPROCESS = 0x00000002
ERROR_ACCESS_DENIED = 5
INVALID_HANDLE = ctypes.c_void_p(-1).value

kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
psapi = ctypes.WinDLL("psapi", use_last_error=True)


class PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [
        ("dwSize", wintypes.DWORD),
        ("cntUsage", wintypes.DWORD),
        ("th32ProcessID", wintypes.DWORD),
        ("th32DefaultHeapID", ctypes.c_void_p),
        ("th32ModuleID", wintypes.DWORD),
        ("cntThreads", wintypes.DWORD),
        ("th32ParentProcessID", wintypes.DWORD),
        ("pcPriClassBase", wintypes.LONG),
        ("dwFlags", wintypes.DWORD),
        ("szExeFile", wintypes.WCHAR * 260),
    ]


class PROCESS_MEMORY_COUNTERS(ctypes.Structure):
    _fields_ = [
        ("cb", wintypes.DWORD),
        ("PageFaultCount", wintypes.DWORD),
        ("PeakWorkingSetSize", ctypes.c_size_t),
        ("WorkingSetSize", ctypes.c_size_t),
        ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
        ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
        ("PagefileUsage", ctypes.c_size_t),
        ("PeakPagefileUsage", ctypes.c_size_t),
    ]


kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel32.OpenProcess.restype = wintypes.HANDLE
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
kernel32.CloseHandle.restype = wintypes.BOOL
kernel32.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
kernel32.TerminateProcess.restype = wintypes.BOOL
kernel32.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
kernel32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
kernel32.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
kernel32.Process32FirstW.restype = wintypes.BOOL
kernel32.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
kernel32.Process32NextW.restype = wintypes.BOOL
psapi.GetProcessMemoryInfo.argtypes = [
    wintypes.HANDLE, ctypes.POINTER(PROCESS_MEMORY_COUNTERS), wintypes.DWORD]
psapi.GetProcessMemoryInfo.restype = wintypes.BOOL


def devin_home() -> Path:
    appdata = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
    return Path(os.environ.get("DEVIN_HOME", str(Path(appdata) / "devin")))


def cli_dir() -> Path:
    return devin_home() / "cli"


def lock_dir() -> Path:
    return cli_dir() / "session_locks"


def db_path() -> Path:
    return cli_dir() / "sessions.db"


def file_bytes(path: Path):
    try:
        if path.is_file():
            return path.stat().st_size
    except OSError:
        return None
    return None


def is_reparse(path: Path) -> bool:
    try:
        info = path.lstat()
    except OSError:
        return True
    attrs = getattr(info, "st_file_attributes", 0)
    return bool(attrs & stat.FILE_ATTRIBUTE_REPARSE_POINT)


def pid_alive(pid: int) -> bool:
    if not isinstance(pid, int) or pid <= 0 or pid > 0xFFFFFFFF:
        return False
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if handle:
        kernel32.CloseHandle(handle)
        return True
    return ctypes.get_last_error() == ERROR_ACCESS_DENIED


def snapshot_processes() -> list:
    snap = kernel32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
    if not snap or snap == INVALID_HANDLE:
        raise OSError("process-snapshot-failed")
    try:
        entry = PROCESSENTRY32W()
        entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
        rows = []
        ok = kernel32.Process32FirstW(snap, ctypes.byref(entry))
        while ok:
            rows.append({
                "pid": int(entry.th32ProcessID),
                "ppid": int(entry.th32ParentProcessID),
                "name": str(entry.szExeFile or ""),
            })
            ok = kernel32.Process32NextW(snap, ctypes.byref(entry))
        if not rows:
            raise OSError("process-snapshot-empty")
        return rows
    finally:
        kernel32.CloseHandle(snap)


def working_set_bytes(pid: int):
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        return None
    try:
        counters = PROCESS_MEMORY_COUNTERS()
        counters.cb = ctypes.sizeof(counters)
        if not psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
            return None
        return int(counters.WorkingSetSize)
    finally:
        kernel32.CloseHandle(handle)


def classify_devin(procs=None) -> list:
    procs = procs if procs is not None else snapshot_processes()
    alive = {row["pid"] for row in procs}
    devin = [row for row in procs if row["name"].lower() == DEVIN_EXE]
    devin_pids = {row["pid"] for row in devin}
    children = {}
    for row in devin:
        if row["ppid"] in devin_pids:
            children[row["ppid"]] = children.get(row["ppid"], 0) + 1
    out = []
    for row in devin:
        parent_alive = row["ppid"] in alive and row["ppid"] != 0
        if not parent_alive:
            kind = "orphan"
        elif row["ppid"] not in devin_pids:
            kind = "interactive-root"
        else:
            kind = "worker"
        out.append({
            "pid": row["pid"],
            "ppid": row["ppid"],
            "class": kind,
            "workingSetBytes": working_set_bytes(row["pid"]),
            "devinChildren": children.get(row["pid"], 0),
        })
    return out


def read_lock_pid(path: Path):
    try:
        raw = path.read_bytes()
    except PermissionError:
        return None, "held"
    except OSError:
        return None, "unreadable"
    text = raw.decode("ascii", "replace").strip()
    if text.isdigit():
        return int(text), "pid"
    return None, "unparsed"


def classify_locks(alive=None) -> dict:
    if alive is None:
        alive = {row["pid"] for row in snapshot_processes()}
    directory = lock_dir()
    out = {"lockDirPresent": directory.is_dir(), "lockCount": 0, "dead": [],
           "alive": 0, "held": 0, "unparsed": 0, "unreadable": 0}
    if not out["lockDirPresent"]:
        return out
    for path in directory.iterdir():
        if not path.is_file() or path.suffix.lower() != ".lock" or is_reparse(path):
            continue
        out["lockCount"] += 1
        pid, kind = read_lock_pid(path)
        if kind == "pid":
            if pid in alive:
                out["alive"] += 1
            else:
                out["dead"].append({"name": path.name, "pid": pid})
        elif kind == "held":
            out["held"] += 1
        elif kind == "unparsed":
            out["unparsed"] += 1
        else:
            out["unreadable"] += 1
    return out


def backup_dir_plan() -> Path:
    day = time.strftime("%Y%m%d")
    planned = cli_dir() / ("session_locks_backup_" + day)
    if planned.exists() and not planned.is_dir():
        planned = cli_dir() / ("session_locks_backup_" + time.strftime("%Y%m%d_%H%M%S"))
    return planned


def db_sizes() -> dict:
    cli = cli_dir()
    db = cli / "sessions.db"
    wal = cli / "sessions.db-wal"
    shm = cli / "sessions.db-shm"
    return {
        "sessionsDbBytes": file_bytes(db),
        "walBytes": file_bytes(wal),
        "shmBytes": file_bytes(shm),
        "dbPresent": db.is_file(),
        "walPresent": wal.is_file(),
        "rootSessionsDbPresent": (devin_home() / "sessions.db").exists(),
        "paths": {
            "home": str(devin_home()),
            "locks": str(lock_dir()),
            "sessionsDb": str(db),
            "wal": str(wal),
        },
    }


def status_payload() -> dict:
    procs = snapshot_processes()
    rows = classify_devin(procs)
    locks = classify_locks({row["pid"] for row in procs})
    ws = [row["workingSetBytes"] for row in rows if row["workingSetBytes"] is not None]
    total = sum(ws)
    payload = {
        "schemaVersion": SCHEMA,
        "action": "status",
        "processCount": len(rows),
        "workingSetBytes": total,
        "workingSetMB": round(total / 1048576, 1),
        "orphanCount": sum(1 for row in rows if row["class"] == "orphan"),
        "interactiveRootCount": sum(1 for row in rows if row["class"] == "interactive-root"),
        "workerCount": sum(1 for row in rows if row["class"] == "worker"),
        "processWarnAbove": PROCESS_WARN,
        "lockCount": locks["lockCount"],
        "deadLockCount": len(locks["dead"]),
        "aliveLockCount": locks["alive"],
        "heldLockCount": locks["held"],
        "unparsedLockCount": locks["unparsed"],
        "lockWarnAbove": LOCK_WARN,
        "lockDirPresent": locks["lockDirPresent"],
    }
    payload.update(db_sizes())
    return payload


def move_dead_locks(force: bool) -> dict:
    locks = classify_locks()
    planned = backup_dir_plan()
    payload = {
        "schemaVersion": SCHEMA,
        "action": "clean-locks",
        "dryRun": not force,
        "policy": "move-to-backup",
        "backupDir": str(planned),
        "candidates": len(locks["dead"]),
        "moved": 0,
        "keptAlive": locks["alive"],
        "held": locks["held"],
        "unparsed": locks["unparsed"],
        "unreadable": locks["unreadable"],
        "conflicts": 0,
        "skippedReparse": 0,
        "sample": [row["name"] for row in locks["dead"][:20]],
    }
    if not force:
        payload["recovery"] = [
            "python -B scripts/devin_session_guard.py clean-locks --force"]
        return payload
    if not locks["dead"]:
        return payload
    planned.mkdir(parents=True, exist_ok=True)
    directory = lock_dir().resolve()
    for row in locks["dead"]:
        src = directory / row["name"]
        if src.parent.resolve() != directory or src.suffix.lower() != ".lock":
            payload["skippedReparse"] += 1
            continue
        if is_reparse(src):
            payload["skippedReparse"] += 1
            continue
        # Re-check immediately before the move. A recycled PID must stay put.
        pid, kind = read_lock_pid(src)
        if kind != "pid" or pid_alive(pid):
            payload["keptAlive"] += 1
            continue
        dest = planned / src.name
        if dest.exists():
            payload["conflicts"] += 1
            continue
        try:
            os.replace(src, dest)
        except OSError:
            payload["held"] += 1
            continue
        payload["moved"] += 1
    return payload


def protected_pids(rows: list) -> set:
    protected = set()
    best_pid = None
    best_ws = -1
    for row in rows:
        ws = row["workingSetBytes"] if row["workingSetBytes"] is not None else -1
        if ws > best_ws:
            best_ws = ws
            best_pid = row["pid"]
        if row["class"] == "interactive-root":
            protected.add(row["pid"])
    if best_pid is not None and best_ws > 0:
        protected.add(best_pid)
    return protected


def reap_plan(rows: list, only_pids=None) -> dict:
    protected = protected_pids(rows)
    roots = any(row["class"] == "interactive-root" for row in rows)
    eligible = []
    held = []
    for row in rows:
        if row["class"] != "orphan":
            continue
        if row["pid"] in protected or row["devinChildren"] > 0:
            held.append(row)
        else:
            eligible.append(row)
    refused = []
    if only_pids:
        wanted = set(only_pids)
        by_pid = {row["pid"]: row for row in rows}
        eligible = [row for row in eligible if row["pid"] in wanted]
        for pid in wanted:
            if pid not in {row["pid"] for row in eligible}:
                known = by_pid.get(pid)
                refused.append({
                    "pid": pid,
                    "class": known["class"] if known else "absent",
                })
        hold = bool(refused)
        reason = "requested-pid-not-an-orphan-leaf" if hold else ""
    elif not rows:
        hold = False
        reason = ""
    elif not roots:
        eligible = []
        hold = True
        reason = "interactive-root-not-distinguished"
    else:
        hold = False
        reason = ""
    return {"eligible": eligible, "heldOrphans": held, "refused": refused,
            "hold": hold, "reason": reason, "protected": sorted(protected)}


def pid_rows(rows: list) -> list:
    view = []
    for row in rows:
        ws = row["workingSetBytes"]
        view.append({
            "pid": row["pid"],
            "ppid": row["ppid"],
            "class": row["class"],
            "workingSetMB": None if ws is None else round(ws / 1048576, 1),
            "devinChildren": row["devinChildren"],
        })
    return view


def terminate_pid(pid: int) -> bool:
    handle = kernel32.OpenProcess(PROCESS_TERMINATE, False, pid)
    if not handle:
        return False
    try:
        return bool(kernel32.TerminateProcess(handle, 1))
    finally:
        kernel32.CloseHandle(handle)


def reap(force: bool, only_pids=None) -> tuple:
    rows = classify_devin()
    plan = reap_plan(rows, only_pids)
    payload = {
        "schemaVersion": SCHEMA,
        "action": "reap-zombies",
        "dryRun": not force,
        "processCount": len(rows),
        "orphanCount": sum(1 for row in rows if row["class"] == "orphan"),
        "interactiveRootCount": sum(1 for row in rows if row["class"] == "interactive-root"),
        "workerCount": sum(1 for row in rows if row["class"] == "worker"),
        "wouldKill": len(plan["eligible"]),
        "killed": 0,
        "hold": plan["hold"],
        "reason": plan["reason"],
        "protectedPids": plan["protected"],
        "refused": plan["refused"],
        "pidList": pid_rows(rows),
    }
    if not force:
        if plan["eligible"]:
            payload["recovery"] = [
                "python -B scripts/devin_session_guard.py reap-zombies --force --pid <orphan-leaf>"]
        return payload, 0
    if plan["hold"] and not plan["eligible"]:
        payload["hold"] = True
        return payload, 4
    killed = []
    for row in plan["eligible"]:
        fresh = {item["pid"]: item for item in classify_devin()}
        current = fresh.get(row["pid"])
        if not current or current["class"] != "orphan" or current["devinChildren"] > 0:
            continue
        if row["pid"] in protected_pids(list(fresh.values())):
            continue
        if terminate_pid(row["pid"]):
            killed.append(row["pid"])
    payload["killed"] = len(killed)
    payload["killedPids"] = killed
    if plan["hold"]:
        return payload, 4
    return payload, 0


def backup_inventory() -> list:
    cli = cli_dir()
    found = []
    if not cli.is_dir():
        return found
    for path in cli.iterdir():
        name = path.name.lower()
        if not (name.startswith("session_locks_backup_") or name.startswith("sessions.db.backup")):
            continue
        try:
            size = path.stat().st_size if path.is_file() else None
        except OSError:
            size = None
        found.append({"name": path.name, "isDir": path.is_dir(), "bytes": size})
    return found[:20]


def probe_db(path: Path):
    uri = path.resolve().as_uri() + "?mode=ro"
    try:
        con = sqlite3.connect(uri, uri=True, timeout=1.0)
    except sqlite3.Error as exc:
        return False, None, type(exc).__name__
    try:
        con.execute("PRAGMA query_only=ON")
        row = con.execute("PRAGMA journal_mode").fetchone()
        mode = row[0] if row else None
        return True, mode, None
    except sqlite3.Error as exc:
        return False, None, type(exc).__name__
    finally:
        con.close()


def checkpoint(force: bool) -> tuple:
    sizes = db_sizes()
    guide = [
        "Do not delete sessions.db.",
        "Copy sessions.db, sessions.db-wal, and sessions.db-shm to a dated folder before a checkpoint.",
        "This tool checkpoints with PRAGMA wal_checkpoint(PASSIVE) only. It does not VACUUM.",
    ]
    payload = {
        "schemaVersion": SCHEMA,
        "action": "checkpoint",
        "dryRun": not force,
        "guide": guide,
        "backups": backup_inventory(),
        "readable": False,
    }
    payload.update(sizes)
    if not sizes["dbPresent"]:
        payload["reason"] = "sessions-db-absent"
        return payload, 0
    readable, mode, err = probe_db(db_path())
    payload["readable"] = readable
    payload["journalMode"] = mode
    payload["readError"] = err
    if not readable:
        payload["hold"] = True
        payload["reason"] = "db-read-failed"
        return payload, 4
    if not force:
        payload["wouldCheckpoint"] = "PASSIVE"
        return payload, 0
    try:
        con = sqlite3.connect(str(db_path()), timeout=1.0)
        try:
            row = con.execute("PRAGMA wal_checkpoint(PASSIVE)").fetchone()
        finally:
            con.close()
    except sqlite3.Error as exc:
        payload["hold"] = True
        payload["reason"] = "checkpoint-refused"
        payload["readError"] = type(exc).__name__
        return payload, 4
    payload["checkpoint"] = {
        "mode": "PASSIVE",
        "busy": None if not row else row[0],
        "logFrames": None if not row or len(row) < 2 else row[1],
        "checkpointedFrames": None if not row or len(row) < 3 else row[2],
    }
    payload["dryRun"] = False
    after = db_sizes()
    payload["walBytesAfter"] = after["walBytes"]
    return payload, 0


def print_human(payload: dict) -> None:
    print("devin-session-guard action=%s" % payload.get("action"))
    preferred = (
        "processCount", "workingSetMB", "orphanCount", "interactiveRootCount",
        "workerCount", "lockCount", "deadLockCount", "aliveLockCount",
        "heldLockCount", "sessionsDbBytes", "walBytes", "shmBytes", "dbPresent",
        "dryRun", "candidates", "moved", "backupDir", "wouldKill", "killed",
        "hold", "reason", "readable", "journalMode",
    )
    for key in preferred:
        if key in payload and payload[key] is not None:
            print("%s=%s" % (key, payload[key]))
    checkpoint_row = payload.get("checkpoint")
    if isinstance(checkpoint_row, dict):
        print("checkpoint=%s" % json.dumps(checkpoint_row, ensure_ascii=True))
    for line in payload.get("guide") or []:
        print("guide: %s" % line)
    for line in payload.get("recovery") or []:
        print("recovery: %s" % line)
    for row in (payload.get("pidList") or [])[:80]:
        print("pid=%s class=%s ppid=%s wsMB=%s children=%s" % (
            row.get("pid"), row.get("class"), row.get("ppid"),
            row.get("workingSetMB"), row.get("devinChildren")))


def emit(payload: dict, as_json: bool) -> None:
    if as_json:
        print(json.dumps(payload, ensure_ascii=True))
    else:
        print_human(payload)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)

    status = sub.add_parser("status")
    status.add_argument("--json", action="store_true")

    clean = sub.add_parser("clean-locks")
    clean.add_argument("--json", action="store_true")
    clean.add_argument("--force", action="store_true")

    reap_cmd = sub.add_parser("reap-zombies")
    reap_cmd.add_argument("--json", action="store_true")
    reap_cmd.add_argument("--force", action="store_true")
    reap_cmd.add_argument("--pid", type=int, action="append", default=[])

    point = sub.add_parser("checkpoint")
    point.add_argument("--json", action="store_true")
    point.add_argument("--force", action="store_true")
    return parser


def main(argv=None) -> int:
    if os.name != "nt":
        print("devin-session-guard reason=not-windows")
        return 1
    args = build_parser().parse_args(argv)
    try:
        if args.action == "status":
            emit(status_payload(), args.json)
            return 0
        if args.action == "clean-locks":
            emit(move_dead_locks(args.force), args.json)
            return 0
        if args.action == "reap-zombies":
            payload, code = reap(args.force, args.pid)
            emit(payload, args.json)
            return code
        if args.action == "checkpoint":
            payload, code = checkpoint(args.force)
            emit(payload, args.json)
            return code
    except OSError as exc:
        err = {"schemaVersion": SCHEMA, "action": args.action,
               "reason": "os-error", "error": type(exc).__name__}
        emit(err, getattr(args, "json", False))
        return 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
