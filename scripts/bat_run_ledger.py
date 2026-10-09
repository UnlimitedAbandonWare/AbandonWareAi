#!/usr/bin/env python3
"""Shared .bat/.cmd run ledger for demo-1 (directive devin-bat-run-ledger-58a6f2c1).

Appends one JSONL line per phase to var\\bat-runs\\YYYYMMDD.jsonl
(schema awx.bat_run.v1): ts_kst, bat, args(masked), caller, cwd, head, exit,
duration_s, runId, result_path, phase, runKey.

Usage:
  bat_run_ledger.py newrun                              -> prints "<runKey> <epoch_ms>"
  bat_run_ledger.py append [--phase begin|end] --bat N [--args S] [--runkey K]
                           [--exit N] [--t0 MS] [--runid R] [--result PATH]
  bat_run_ledger.py tail [--days N] [--bat NAME]
  bat_run_ledger.py summary --days N
  bat_run_ledger.py should-run <bat>     (advisory: RUN=0 / SKIP_RECENT_OK=3 / FIX_FIRST=4)
  bat_run_ledger.py backfill --from var\\rag-launcher --days N
"""
import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone

SCHEMA = "awx.bat_run.v1"
KST = timezone(timedelta(hours=9))
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEDGER_DIR = os.environ.get("AWX_BATRUN_DIR") or os.path.join(ROOT, "var", "bat-runs")

SECRET = re.compile(
    r"(?i)((?:token|key|secret|password|passwd|apikey|api_key|auth|credential|"
    r"bearer|client_secret)\s*[=:]\s*)([^\s;,\"']+)")
ENV_SECRET_NAME = re.compile(
    r"(?i)(token|key|secret|password|passwd|credential|bearer)")


def _now_kst():
    return datetime.now(KST)


def _ts_kst(dt=None):
    return (dt or _now_kst()).isoformat(timespec="seconds")


def mask_args(s):
    if not s:
        return ""
    s = SECRET.sub(lambda m: m.group(1) + "[REDACTED]", s)
    return s[:1000]


def detect_caller(env=None):
    env = env if env is not None else os.environ
    if env.get("AWX_CALLER"):
        return env["AWX_CALLER"][:32]
    names = set(env.keys())
    if any(n == "DEVIN" or n.startswith("DEVIN_") for n in names):
        return "devin"
    if any(n.startswith("CODEX") for n in names):
        return "codex"
    if env.get("AGENT_SESSION") or env.get("AWX_AGENT_WORKER"):
        return "agent"
    return "user"


def git_head(root=ROOT):
    for git in (os.environ.get("AWX_GIT"), "F:\\git\\cmd\\git.exe", "git"):
        try:
            out = subprocess.run(
                [git, "-C", root, "rev-parse", "--short", "HEAD"],
                capture_output=True, text=True, timeout=10,
                env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"})
            if out.returncode == 0:
                return out.stdout.strip()
        except Exception:
            continue
    return None


def tree_dirty(root=ROOT):
    """True when tracked working tree differs (untracked files ignored)."""
    for git in (os.environ.get("AWX_GIT"), "F:\\git\\cmd\\git.exe", "git"):
        try:
            out = subprocess.run(
                [git, "-C", root, "status", "--porcelain", "--untracked-files=no"],
                capture_output=True, text=True, timeout=20,
                env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"})
            if out.returncode == 0:
                return bool(out.stdout.strip())
            return None
        except Exception:
            continue
    return None


def ledger_path(day=None):
    day = day or _now_kst().strftime("%Y%m%d")
    return os.path.join(LEDGER_DIR, day + ".jsonl")


def append_record(rec):
    os.makedirs(LEDGER_DIR, exist_ok=True)
    rec = dict(rec)
    rec["schemaVersion"] = SCHEMA
    rec.setdefault("ts_kst", _ts_kst())
    rec.setdefault("caller", detect_caller())
    rec.setdefault("head", git_head())
    with io.open(ledger_path(), "a", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return rec


def iter_records(days=3):
    out = []
    for i in range(days):
        day = (_now_kst() - timedelta(days=i)).strftime("%Y%m%d")
        p = ledger_path(day)
        if not os.path.exists(p):
            continue
        with io.open(p, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    out.append(json.loads(line))
                except Exception:
                    continue
    out.sort(key=lambda r: r.get("ts_kst") or "")
    return out


def failure_sig(rec):
    """Stable signature for 'same failure' comparisons."""
    for k in ("failurePoint", "stage"):
        if rec.get(k):
            return str(rec[k])
    if rec.get("exit") not in (None, 0):
        return "exit:%s" % rec.get("exit")
    if rec.get("status") in ("failed", "error"):
        return "status:%s" % rec.get("status")
    return None


def success(rec):
    if rec.get("phase") != "end":
        return False
    if rec.get("exit") is not None:
        return rec.get("exit") == 0
    return rec.get("status") in ("ready", "ok", "stopped", "dry-run",
                                 "already-stopped", "completed")


def cmd_newrun(_a):
    print("%s %d" % (uuid.uuid4().hex[:12], int(time.time() * 1000)))
    return 0


def cmd_append(a):
    rec = {
        "phase": a.phase,
        "bat": a.bat,
        "args": mask_args(a.args or ""),
        "cwd": a.cwd or os.getcwd(),
    }
    if a.runkey:
        rec["runKey"] = a.runkey
    if a.exit is not None:
        try:
            rec["exit"] = int(a.exit)
        except ValueError:
            rec["exit"] = a.exit
    if a.t0:
        try:
            rec["duration_s"] = round((time.time() * 1000 - int(a.t0)) / 1000.0, 2)
        except Exception:
            pass
    if a.runid:
        rec["runId"] = a.runid
    if a.result:
        rec["result_path"] = a.result
    append_record(rec)
    return 0


def cmd_tail(a):
    rows = iter_records(a.days)
    if a.bat:
        rows = [r for r in rows if (r.get("bat") or "").lower() == a.bat.lower()]
    for r in rows[-(a.limit or 50):]:
        print(json.dumps(r, ensure_ascii=False))
    return 0


def _ends(rows):
    """Collapse phases: latest 'end' per runKey else standalone records."""
    ends = []
    for r in rows:
        if r.get("phase") == "end" or "phase" not in r:
            ends.append(r)
    return ends


def cmd_summary(a):
    rows = _ends(iter_records(a.days))
    per = {}
    for r in rows:
        b = r.get("bat") or "?"
        d = per.setdefault(b, {"n": 0, "ok": 0, "last": None, "streak": 0})
        d["n"] += 1
        if success(r):
            d["ok"] += 1
        d["last"] = r
    for b, d in sorted(per.items(), key=lambda kv: -kv[1]["n"]):
        streak = 0
        last = d["last"] or {}
        for r in reversed([x for x in rows if (x.get("bat") or "?") == b]):
            if success(r):
                break
            streak += 1
        last_sig = failure_sig(last) or ("exit=%s" % last.get("exit"))
        rate = (100.0 * d["ok"] / d["n"]) if d["n"] else 0.0
        print("%-28s runs=%-4d ok=%.0f%% last=%s %-10s streak=%d" % (
            b, d["n"], rate, last.get("ts_kst", "?"), last_sig, streak))
    print("total=%d days=%d" % (len(rows), a.days))
    return 0


def cmd_should_run(a):
    rows = [r for r in _ends(iter_records(a.days)) if
            (r.get("bat") or "").lower() == a.bat.lower()]
    if not rows:
        print("RUN: no prior record for %s" % a.bat)
        return 0
    last = rows[-1]
    # FIX_FIRST: last 2 runs failed with the same signature
    if len(rows) >= 2:
        s1, s2 = failure_sig(rows[-1]), failure_sig(rows[-2])
        if s1 and s2 and s1 == s2 and not success(rows[-1]) and not success(rows[-2]):
            print("FIX_FIRST: %s failed twice with %s" % (a.bat, s1))
            if last.get("result_path"):
                print("  log: %s" % last["result_path"])
            if last.get("runId"):
                print("  runId: %s -> var\\rag-launcher\\%s" % (last["runId"], last["runId"]))
            return 4
    # SKIP_RECENT_OK: last run ok and HEAD+tree unchanged since
    if success(last):
        head_now = git_head()
        dirty = tree_dirty()
        if head_now and last.get("head") == head_now and dirty is False:
            print("SKIP_RECENT_OK: %s ok at %s, HEAD %s unchanged, tree clean" % (
                a.bat, last.get("ts_kst"), head_now))
            return 3
        why = []
        if last.get("head") != head_now:
            why.append("head %s->%s" % (last.get("head"), head_now))
        if dirty is not False:
            why.append("tree dirty" if dirty else "tree check failed")
        print("RUN: last ok at %s but %s" % (last.get("ts_kst"), ", ".join(why) or "unknown"))
        return 0
    print("RUN: last run %s at %s (sig=%s)" % (
        last.get("status") or ("exit=%s" % last.get("exit")),
        last.get("ts_kst"), failure_sig(last)))
    return 0


def cmd_backfill(a):
    """Import var\\rag-launcher run dirs (read-only) as caller=unknown records."""
    src = os.path.join(ROOT, a.src) if not os.path.isabs(a.src) else a.src
    since = _now_kst() - timedelta(days=a.days)
    existing = set()
    if os.path.isdir(LEDGER_DIR):
        for fn in os.listdir(LEDGER_DIR):
            if not fn.endswith(".jsonl"):
                continue
            with io.open(os.path.join(LEDGER_DIR, fn), encoding="utf-8",
                       errors="replace") as f:
                for line in f:
                    try:
                        rid = json.loads(line).get("runId")
                        if rid:
                            existing.add(rid)
                    except Exception:
                        continue
    added = skipped = 0
    if not os.path.isdir(src):
        print("backfill source missing: %s" % src)
        return 1
    for d in sorted(os.listdir(src)):
        m = re.match(r"(\d{8})-(\d{6})", d)
        if not m or d in existing:
            skipped += 1 if d in existing else 0
            continue
        try:
            dt = datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S").replace(tzinfo=KST)
        except Exception:
            continue
        if dt < since:
            continue
        p = os.path.join(src, d)
        if not os.path.isdir(p):
            continue
        rec = {"phase": "end", "caller": "unknown", "runId": d,
               "ts_kst": dt.isoformat(timespec="seconds"),
               "bat": "stop_rag_stack.ps1" if "-stop-" in d else "start_rag_stack.ps1",
               "result_path": os.path.relpath(p, ROOT)}
        rj = os.path.join(p, "result.json")
        if os.path.exists(rj):
            try:
                with io.open(rj, encoding="utf-8") as fh:
                    r = json.load(fh)
                rec["status"] = r.get("status")
                rec["stage"] = r.get("stage")
                rec["failurePoint"] = r.get("failurePoint")
                rec["exit"] = 0 if r.get("ok") else 1
                if r.get("status") in ("dry-run", "stopped", "already-stopped"):
                    rec["exit"] = 0
            except Exception as e:
                rec["status"] = "unreadable"
                rec["note"] = str(e)[:120]
        else:
            rec["status"] = "no-result"
            rec["exit"] = None
        rec["head"] = None
        append_record_at(rec, dt)
        added += 1
    print("backfill: added=%d skipped=%d from=%s days=%d" % (added, skipped, a.src, a.days))
    return 0


def append_record_at(rec, dt):
    os.makedirs(LEDGER_DIR, exist_ok=True)
    rec = dict(rec)
    rec["schemaVersion"] = SCHEMA
    rec.setdefault("caller", "unknown")
    rec.setdefault("head", None)
    p = os.path.join(LEDGER_DIR, dt.strftime("%Y%m%d") + ".jsonl")
    with io.open(p, "a", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("newrun").set_defaults(fn=cmd_newrun)
    s = sub.add_parser("append")
    s.add_argument("--phase", default="end", choices=["begin", "end"])
    s.add_argument("--bat", required=True)
    s.add_argument("--args", default="")
    s.add_argument("--runkey")
    s.add_argument("--exit")
    s.add_argument("--t0")
    s.add_argument("--runid")
    s.add_argument("--result")
    s.add_argument("--cwd")
    s.set_defaults(fn=cmd_append)
    s = sub.add_parser("tail")
    s.add_argument("--days", type=int, default=3)
    s.add_argument("--bat")
    s.add_argument("--limit", type=int, default=50)
    s.set_defaults(fn=cmd_tail)
    s = sub.add_parser("summary")
    s.add_argument("--days", type=int, default=3)
    s.set_defaults(fn=cmd_summary)
    s = sub.add_parser("should-run")
    s.add_argument("bat")
    s.add_argument("--days", type=int, default=3)
    s.set_defaults(fn=cmd_should_run)
    s = sub.add_parser("backfill")
    s.add_argument("--from", dest="src", default="var\\rag-launcher")
    s.add_argument("--days", type=int, default=3)
    s.set_defaults(fn=cmd_backfill)
    a = ap.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
