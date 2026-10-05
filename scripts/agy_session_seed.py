#!/usr/bin/env python3
"""Session seed for the agy depth router (stdlib only, read-only).

Writes var/agy-seed/latest.md (<=16 KB) with the session snapshot an L2/L3
task should start from. With --launcher it also prints short hint lines
for Start-Agy-CLI.bat: a resume line when a previous session left an
exit snapshot, a quota warning when the last 6h of cli logs show
RESOURCE_EXHAUSTED/quota/429, plus a short seed-written line.

--on-exit (Start-Agy-CLI.bat calls it after agy.exe exits) snapshots the
recent work/handoff state to var/agy-seed/last_exit_context.json so the
next launch can print '[Start-Agy-CLI] resume: <one line>'.

Never blocks the launcher: every failure path exits 0.
"""

import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "var" / "agy-seed" / "latest.md"
EXIT_SNAP = ROOT / "var" / "agy-seed" / "last_exit_context.json"
KST = timezone(timedelta(hours=9))
MAX_BYTES = 16 * 1024
GIT = r"F:\git\cmd\git.exe"

SECRETISH = re.compile(
    r"(?i)(api[_-]?key|token|secret|password|passwd|credential|bearer)"
    r"[\s:=\"']+[^\s\"']{4,}")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")


def scrub(line: str) -> str:
    line = SECRETISH.sub(lambda m: m.group(1) + "=<redacted>", line)
    line = EMAIL.sub("<email>", line)
    return line


def git(*args: str) -> str:
    try:
        env = dict(os.environ, GIT_OPTIONAL_LOCKS="0")
        r = subprocess.run([GIT, *args], cwd=ROOT, capture_output=True,
                           text=True, timeout=15, env=env)
        return r.stdout.strip() if r.returncode == 0 else ""
    except Exception:
        return ""


def lease_rows() -> list:
    lockdir = ROOT / "__patch_drop__" / "source-edit-locks"
    rows = []
    if not lockdir.is_dir():
        return rows
    for lease in sorted(lockdir.glob("*/lease.json")):
        try:
            doc = json.loads(lease.read_text(encoding="utf-8", errors="replace"))
            rows.append({
                "topic": doc.get("topic", lease.parent.name),
                "expires": doc.get("expiresAtUtc", "?"),
                "paths": [str(p) for p in doc.get("targetPaths", [])][:5],
            })
        except Exception:
            continue
    return rows


def recent_paste_files(n: int = 5) -> list:
    dl = Path(os.path.expandvars(r"%USERPROFILE%")) / "Downloads"
    if not dl.is_dir():
        return []
    files = sorted(dl.glob("PASTE_*"), key=lambda p: p.stat().st_mtime,
                   reverse=True)
    return [f.name for f in files[:n]]


def recent_status_lines(n: int = 5) -> list:
    hand = ROOT / "data" / "agent-handoff"
    if not hand.is_dir():
        return []
    stats = sorted(hand.rglob("STATUS.md"), key=lambda p: p.stat().st_mtime,
                   reverse=True)
    out = []
    for s in stats[:n]:
        rel = s.relative_to(ROOT).as_posix()
        try:
            head = next((ln.strip() for ln in
                         s.read_text(encoding="utf-8", errors="replace")
                         .splitlines() if ln.strip()), "")
            out.append(f"{rel}: {head[:110]}")
        except Exception:
            out.append(rel)
    return out


def build_seed() -> str:
    now = datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S KST")
    lines = [f"# agy session seed ({now})", "",
             "Only read the sections relevant to the current task.", ""]
    head = git("rev-parse", "HEAD")[:12] or "?"
    branch = git("branch", "--show-current") or "?"
    status = git("status", "--short").splitlines()
    lines += ["## git", f"HEAD={head} branch={branch} statusLines={len(status)}"]
    for ln in status[:20]:
        lines.append("  " + scrub(ln)[:140])
    if len(status) > 20:
        lines.append(f"  ... +{len(status) - 20} more")
    lines += ["", "## leases"]
    rows = lease_rows()
    if rows:
        for r in rows:
            lines.append(f"- {r['topic']} (expires {r['expires']}): "
                         + ", ".join(r["paths"]))
    else:
        lines.append("- none")
    pastes = recent_paste_files()
    if pastes:
        lines += ["", "## recent PASTE_* (Downloads)"]
        lines += [f"- {scrub(p)}" for p in pastes]
    stats = recent_status_lines()
    if stats:
        lines += ["", "## recent handoff STATUS"]
        lines += [f"- {scrub(s)}" for s in stats]
    text = "\n".join(lines) + "\n"
    data = text.encode("utf-8")
    if len(data) > MAX_BYTES:
        text = data[:MAX_BYTES].decode("utf-8", errors="ignore")
        text += "\n[truncated to 16KB]\n"
    return text


def build_exit_snapshot() -> dict:
    """Bounded, scrubbed exit-context for the next launch's resume line."""
    stats = recent_status_lines(3)
    return {
        "schema": "awx.agy-exit-context.v1",
        "exitedAtKst": datetime.now(KST).isoformat(timespec="seconds"),
        "seedPath": "var/agy-seed/latest.md",
        "lastWork": stats[0][:140] if stats else "no recent handoff STATUS",
        "handoffStatus": stats,
        "recentPastes": recent_paste_files(5),
        "leases": [r["topic"] for r in lease_rows()],
        "git": {"head": git("rev-parse", "HEAD")[:12],
                "branch": git("branch", "--show-current"),
                "statusLines": len(git("status", "--short").splitlines())},
    }


def write_exit_snapshot() -> None:
    doc = build_exit_snapshot()
    EXIT_SNAP.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
    tmp = EXIT_SNAP.with_name(EXIT_SNAP.name + ".tmp")
    tmp.write_text(data, encoding="utf-8")
    tmp.replace(EXIT_SNAP)


def read_exit_snapshot() -> dict:
    try:
        doc = json.loads(EXIT_SNAP.read_text(encoding="utf-8"))
        if isinstance(doc, dict):
            return doc
    except Exception:
        pass
    return {}


def quota_hit_6h() -> bool:
    base = Path(os.path.expandvars(r"%USERPROFILE%")) / ".gemini" / "antigravity-cli"
    cut = datetime.now().timestamp() - 6 * 3600
    files = []
    cli = base / "cli.log"
    if cli.is_file():
        files.append(cli)
    logdir = base / "log"
    if logdir.is_dir():
        files += [f for f in logdir.glob("*.log")
                  if f.stat().st_mtime > cut]
    # Only real exhaustion signatures - routine 'quota_manager'/'quotaProject'
    # auth lines are not hits.
    pat = re.compile(
        r"RESOURCE_EXHAUSTED|quota[_ ]exceeded|Too Many Requests"
        r"|:status.{0,4}429|http.{0,12}\b429\b|\bcode\b.{0,3}[:=]\s*429\b"
        r"|rate.?limit.{0,20}(hit|reach|exceed)", re.I)
    for f in files:
        if f.stat().st_mtime <= cut:
            continue
        try:
            if pat.search(f.read_text(encoding="utf-8", errors="ignore")):
                return True
        except Exception:
            continue
    return False


def main() -> int:
    launcher = "--launcher" in sys.argv[1:]
    if "--on-exit" in sys.argv[1:]:
        try:
            write_exit_snapshot()
        except Exception:
            pass
        return 0
    try:
        SEED.parent.mkdir(parents=True, exist_ok=True)
        SEED.write_text(build_seed(), encoding="utf-8")
    except Exception as exc:
        if launcher:
            print(f"[Start-Agy-CLI] seed write failed ({exc}) - continuing")
        return 0
    if launcher:
        try:
            snap = read_exit_snapshot()
            work = str(snap.get("lastWork") or "").strip()
            if work:
                print(f"[Start-Agy-CLI] resume: {work[:140]}")
        except Exception:
            pass
        try:
            if quota_hit_6h():
                print("[Start-Agy-CLI] quota/429 hit in last 6h cli logs - "
                      "close all agy windows and run: Agy-Auth.bat use <other-account>")
        except Exception:
            pass
        print("[Start-Agy-CLI] seed=var/agy-seed/latest.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
