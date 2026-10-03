#!/usr/bin/env python3
# scripts/agent_signal_digest.py
"""Agent signal digest -- one-shot $0 fleet-state summary for agy directive work.

Collects (all read-only, local, no paid calls, no network):
  (a) agent_scope_lease.py who        -> live leases + newest in-progress journals
  (b) data/agent-handoff/             -> dirs touched inside --hours (top 3)
  (c) git status --short / git log    -> dirty count, HEAD, last 2 commits
  (d) grok_to_agy_memory_bridge       -> recent user asks sent to GrokBot
  (e) data/device-resources/events/   -> newest event file + <hours> count

Default output is a compact markdown block (<=20 lines) an agent can read in
one glance; --json emits the full structured payload. Every collector is
fault-isolated: a missing tool/dir records an errors[] note instead of
failing, so the digest still works on partial checkouts.

Usage:
  python -B scripts/agent_signal_digest.py [--json] [--root <dir>] [--hours 24]
Exit 0 always (advisory); collector failures live in errors[].
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path

from awx_paths import resolve as _awx_resolve

ROOT = Path(__file__).resolve().parent.parent
KST = timezone(timedelta(hours=9))
SCHEMA = "awx.agent-signal-digest.v1"


def iso_utc(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).isoformat()


def kst(iso_text: str, fmt: str = "%m-%d %H:%M") -> str:
    """ISO-UTC text -> KST short label; unparseable input passes through trimmed."""
    try:
        t = iso_text.replace("Z", "+00:00")
        return datetime.fromisoformat(t).astimezone(KST).strftime(fmt)
    except (ValueError, TypeError):
        return (iso_text or "")[:16]


def trunc(text: str, n: int) -> str:
    text = " ".join(str(text or "").split())
    return text if len(text) <= n else text[: n - 1] + "…"


def collect_leases_journals(root: Path) -> dict:
    """(a) `agent_scope_lease.py who` -> lease counts, live leases, 3 journals."""
    out = {"counts": None, "active": [], "journals": [], "error": None}
    script = root / "scripts" / "agent_scope_lease.py"
    if not script.is_file():
        out["error"] = "agent_scope_lease.py missing"
        return out
    try:
        proc = subprocess.run(
            [sys.executable, "-B", str(script), "--root", str(root), "who"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            cwd=str(root), timeout=15)
    except (OSError, subprocess.TimeoutExpired) as e:
        out["error"] = f"who spawn/timeout: {e}"[:200]
        return out
    if proc.returncode != 0:
        out["error"] = f"who exit {proc.returncode}: {trunc(proc.stderr, 120)}"
        return out
    try:
        payload = json.loads(proc.stdout)
    except ValueError:
        out["error"] = "who output not JSON"
        return out
    out["counts"] = payload.get("leaseCounts") or {}
    for lease in payload.get("leases") or []:
        if lease.get("lifecycle") == "live" or lease.get("status") == "active":
            out["active"].append({
                "topic": lease.get("topic"), "role": lease.get("role"),
                "expiresAtUtc": lease.get("expiresAtUtc"),
                "targets": (lease.get("targetPaths") or [])[:3]})
    journals = sorted(
        (payload.get("activeJournals") or []),
        key=lambda j: j.get("updatedAtUtc") or "", reverse=True)
    out["journals"] = [{
        "taskId": j.get("taskId"), "agent": j.get("agent"),
        "purpose": trunc(j.get("purpose"), 90),
        "updatedAtUtc": j.get("updatedAtUtc")} for j in journals[:3]]
    return out


def collect_handoffs(root: Path, hours: int) -> dict:
    """(b) agent-handoff dirs modified inside the window, newest 3."""
    base = root / "data" / "agent-handoff"
    out = {"recent": [], "totalInWindow": 0, "error": None}
    if not base.is_dir():
        out["error"] = "data/agent-handoff missing"
        return out
    cutoff = time.time() - hours * 3600
    rows = []
    try:
        entries = list(os.scandir(base))
    except OSError as e:
        out["error"] = str(e)[:160]
        return out
    for e in entries:
        if not e.is_dir():
            continue
        try:
            mt = e.stat().st_mtime
        except OSError:
            continue
        if mt < cutoff:
            continue
        files = []
        try:
            for f in os.scandir(e.path):
                if f.is_file():
                    try:
                        files.append((f.stat().st_mtime, f.name))
                    except OSError:
                        continue
        except OSError:
            pass
        files.sort(reverse=True)
        rows.append({"dir": e.name, "modifiedUtc": iso_utc(mt),
                     "files": [n for _, n in files[:3]]})
    rows.sort(key=lambda r: r["modifiedUtc"], reverse=True)
    out["recent"] = rows[:3]
    out["totalInWindow"] = len(rows)
    return out


def collect_git(root: Path) -> dict:
    """(c) git status --short + HEAD + last 2 commits."""
    out = {"ok": False, "head": None, "dirtyCount": 0, "byKind": {},
           "statusSample": [], "log": [], "error": None}
    git = os.environ.get("GIT_EXE") or str(_awx_resolve("git.exe"))
    if not git:
        out["error"] = "git not found (GIT_EXE, F:\\git\\cmd, PATH)"
        return out
    try:
        status = subprocess.run(
            [git, "-C", str(root), "status", "--short"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=10)
        if status.returncode != 0:
            out["error"] = f"status exit {status.returncode}: {trunc(status.stderr, 120)}"
            return out
        lines = [l for l in status.stdout.splitlines() if l.strip()]
        out["dirtyCount"] = len(lines)
        for l in lines:
            kind = l[:2].strip() or "??"
            out["byKind"][kind] = out["byKind"].get(kind, 0) + 1
        out["statusSample"] = lines[:3]
        head = subprocess.run(
            [git, "-C", str(root), "rev-parse", "--short=12", "HEAD"],
            capture_output=True, text=True, timeout=8)
        if head.returncode == 0:
            out["head"] = head.stdout.strip()
        log = subprocess.run(
            [git, "-C", str(root), "log", "-n", "2", "--oneline"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=8)
        if log.returncode == 0:
            out["log"] = [trunc(l, 90) for l in log.stdout.splitlines() if l.strip()][:2]
        out["ok"] = True
    except (OSError, subprocess.TimeoutExpired) as e:
        out["error"] = str(e)[:200]
    return out


def collect_grok(root: Path) -> dict:
    """(d) recent user asks from the GrokBot prompt_history bridge."""
    out = {"prompts": [], "error": None}
    scripts_dir = str(root / "scripts")
    try:
        if scripts_dir not in sys.path:
            sys.path.insert(0, scripts_dir)
        import importlib
        mod = importlib.import_module("grok_to_agy_memory_bridge")
        rows = mod.collect_recent_prompts(limit=3)
        out["prompts"] = [{
            "timestamp": r.get("timestamp", ""),
            "sessionId": r.get("session_id", ""),
            "prompt": trunc(r.get("prompt"), 100)} for r in rows]
    except Exception as e:  # bridge absent or store unreadable -> degrade
        out["error"] = f"{type(e).__name__}: {e}"[:200]
    return out


def collect_events(root: Path, hours: int) -> dict:
    """(e) newest file under the device-resources event bus."""
    base = root / "data" / "device-resources" / "events"
    out = {"newest": None, "newestAgeMinutes": None, "filesInWindow": 0,
           "error": None}
    if not base.is_dir():
        out["error"] = "data/device-resources/events missing"
        return out
    cutoff = time.time() - hours * 3600
    newest_mt, newest_rel = -1.0, None
    scanned = 0
    for dirpath, _dirs, files in os.walk(base):
        for name in files:
            scanned += 1
            if scanned > 5000:
                break
            p = Path(dirpath) / name
            try:
                mt = p.stat().st_mtime
            except OSError:
                continue
            if mt >= cutoff:
                out["filesInWindow"] += 1
            if mt > newest_mt:
                newest_mt, newest_rel = mt, str(p.relative_to(base))
        if scanned > 5000:
            break
    if newest_rel:
        out["newest"] = newest_rel.replace("\\", "/")
        out["newestAgeMinutes"] = round((time.time() - newest_mt) / 60)
    return out


def render_markdown(d: dict) -> str:
    """<=20 compact lines: 1 header + 1 leases + <=3 journals + <=3 handoffs
    + 1 git + <=2 log + <=3 grok + 1 events + 1 errors = <=16 lines."""
    L = [f"# Agent Signal Digest — {d['generatedAtKst']} ({d['durationMs']}ms, $0 local)"]
    c = (d["leases"].get("counts") or {})
    lease_line = (f"- leases  : {c.get('active', 0)} active / {c.get('expired', 0)} expired "
                  f"/ {c.get('corrupt', 0)} corrupt / {c.get('blocking', 0)} blocking")
    live = d["leases"].get("active") or []
    if live:
        lease_line += " | live: " + ", ".join(
            f"{l.get('topic')}({l.get('role')})" for l in live[:2])
    L.append(lease_line)
    if d["journals"]:
        for j in d["journals"]:
            L.append(f"- journal : `{j['taskId']}` [{j.get('agent')}] "
                     f"{kst(j.get('updatedAtUtc'))} {trunc(j.get('purpose'), 70)}")
    else:
        L.append("- journal : none in_progress")
    rec = (d["handoffs"].get("recent") or [])
    if rec:
        for h in rec:
            L.append(f"- handoff : {h['dir']} {kst(h.get('modifiedUtc'))} "
                     f"[{', '.join(h.get('files') or []) or 'no files'}]")
    else:
        L.append("- handoff : none in window")
    g = d["git"]
    if g.get("ok"):
        kinds = " ".join(f"{k}={v}" for k, v in sorted(g["byKind"].items()))
        L.append(f"- git     : {g['dirtyCount']} dirty ({kinds or 'clean'}) head={g.get('head')}")
        for log in g.get("log") or []:
            L.append(f"- log     : {log}")
    else:
        L.append(f"- git     : unavailable ({trunc(g.get('error'), 70)})")
    if d["grok"]["prompts"]:
        for p in d["grok"]["prompts"]:
            L.append(f"- grok    : [{kst(p.get('timestamp'))}] {trunc(p.get('prompt'), 70)}")
    else:
        L.append("- grok    : no recent asks" + (f" ({trunc(d['grok'].get('error'), 50)})"
                                                  if d["grok"].get("error") else ""))
    e = d["events"]
    if e.get("newest"):
        L.append(f"- events  : newest={e['newest']} {e['newestAgeMinutes']}m ago; "
                 f"{e['filesInWindow']} files <{d['windowHours']}h")
    else:
        L.append("- events  : none" + (f" ({trunc(e.get('error'), 50)})"
                                       if e.get("error") else ""))
    L.append(f"- errors  : {('; '.join(d['errors'])) if d['errors'] else 'none'}")
    return "\n".join(L[:20])


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError):
            pass
    ap = argparse.ArgumentParser(description="Agent signal digest ($0, read-only)")
    ap.add_argument("--json", action="store_true", help="emit structured JSON")
    ap.add_argument("--root", default=str(ROOT), help="project root (default: repo)")
    ap.add_argument("--hours", type=int, default=24, help="handoff/event window")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    started = time.time()
    with ThreadPoolExecutor(max_workers=5) as pool:
        f_lj = pool.submit(collect_leases_journals, root)
        f_ho = pool.submit(collect_handoffs, root, args.hours)
        f_git = pool.submit(collect_git, root)
        f_grok = pool.submit(collect_grok, root)
        f_ev = pool.submit(collect_events, root, args.hours)
        lj, ho, git, grok, events = (f.result() for f in (f_lj, f_ho, f_git, f_grok, f_ev))

    errors = [f"{k}: {v['error']}" for k, v in
              (("leases", lj), ("handoffs", ho), ("git", git),
               ("grok", grok), ("events", events)) if v.get("error")]
    now = datetime.now(timezone.utc)
    digest = {
        "schemaVersion": SCHEMA,
        "generatedAtUtc": now.isoformat(),
        "generatedAtKst": now.astimezone(KST).strftime("%Y-%m-%d %H:%M KST"),
        "durationMs": round((time.time() - started) * 1000),
        "root": str(root),
        "windowHours": args.hours,
        "leases": {"counts": lj.get("counts"), "active": lj.get("active"),
                   "error": lj.get("error")},
        "journals": lj.get("journals") or [],
        "handoffs": ho,
        "git": git,
        "grok": grok,
        "events": events,
        "errors": errors,
    }
    if args.json:
        print(json.dumps(digest, ensure_ascii=False, indent=2))
    else:
        print(render_markdown(digest))
    return 0


if __name__ == "__main__":
    sys.exit(main())
