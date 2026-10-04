#!/usr/bin/env python3
"""git_commit_window.py — user commit-window helper (read-only git only).

Answers "which files are safe to commit right now?" for a human commit
(GitHub Desktop) that shares the worktree with agent sessions.

Subcommands:
  plan    Bucket the porcelain worktree paths into SAFE / HELD_BY_LEASE /
          HOT_RECENT / SENSITIVE / LARGE and write var/codex-assist-git-lock/
          commit-window-<ts>.md|json plus uncheck-<ts>.txt.
  lock    Report .git/index.lock presence/size/age and running git.exe
          processes (PID, start time, parent name). Never deletes, never kills.
  open    Create var/git-commit-window.flag — "user is committing now".
  close   Remove the flag.
  status  Show the flag state; TTL-expired flags report "expired" and are
          left in place (no auto-delete).

Safety contract: this tool never runs a git write. Every git invocation uses
--no-optional-locks. Source text must not contain write git arguments —
test_git_commit_window.py enforces it.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCHEMA = "awx.git-commit-window.v1"
LARGE_BYTES = 10 * 1024 * 1024
DEFAULT_QUIET_MINUTES = 3
DEFAULT_FLAG_TTL_MINUTES = 10
FLAG_REL = "var/git-commit-window.flag"
LOCK_REL = ".git/index.lock"
LEASE_GLOB = "__patch_drop__/source-edit-locks/*.lock/lease.json"
OUT_DIR_REL = "var/codex-assist-git-lock"

SAFE, HELD, HOT, SENSITIVE, LARGE = (
    "SAFE", "HELD_BY_LEASE", "HOT_RECENT", "SENSITIVE", "LARGE")
# First match wins when a path hits several buckets.
BUCKET_ORDER = (HELD, SENSITIVE, LARGE, HOT, SAFE)

SENSITIVE_NAME_RE = re.compile(r"credential", re.I)
SENSITIVE_SUFFIXES = (".mv.db", ".pem", ".key")


def _norm(path: str) -> str:
    norm = path.replace("\\", "/")
    while norm.startswith("./"):
        norm = norm[2:]
    return norm.casefold()


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat()


def _parse_dt(value) -> datetime | None:
    if not value:
        return None
    try:
        text = str(value).strip().replace("Z", "+00:00")
        dt = datetime.fromisoformat(text)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt
    except (ValueError, TypeError):
        return None


def _kst(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    return dt.astimezone(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S KST")


# ---------------------------------------------------------------- status parse

def parse_porcelain_z(data: str) -> list[dict]:
    """Parse `git status --porcelain=v1 -z` output into {xy, path} rows."""
    rows = []
    parts = data.split("\0")
    i = 0
    while i < len(parts):
        entry = parts[i]
        i += 1
        if not entry:
            continue
        xy = entry[:2]
        path = entry[3:] if len(entry) > 3 else ""
        if xy.startswith("R") or xy.startswith("C"):
            # rename/copy: the original path follows as its own NUL field
            i += 1
        if path:
            rows.append({"xy": xy, "path": path})
    return rows


# ------------------------------------------------------------------ leases

def load_leases(root: Path) -> dict:
    """Map normalized target path -> {ownerId, expiresAt, lease}.

    Reads every __patch_drop__/source-edit-locks/*.lock/lease.json.
    Expired leases are still returned (the report shows them as held; agents
    may recover them separately — this tool only reports).
    """
    mapping: dict[str, dict] = {}
    for lease_path in sorted(root.glob(LEASE_GLOB.replace("/", os.sep))):
        try:
            doc = json.loads(lease_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        owner = doc.get("ownerId") or doc.get("owner") or lease_path.parent.name
        expires = doc.get("expiresAt") or doc.get("expiresAtUtc")
        for target in doc.get("targetPaths", []) or []:
            mapping.setdefault(_norm(str(target)), {
                "ownerId": owner,
                "expiresAt": expires,
                "expiresAtKst": _kst(_parse_dt(expires)),
                "lease": _norm(str(lease_path.parent.relative_to(root)))
                if lease_path.parent.is_relative_to(root) else lease_path.parent.name,
            })
    return mapping


# -------------------------------------------------------------- classify

def is_sensitive(rel_path: str) -> bool:
    norm = _norm(rel_path)
    name = norm.rsplit("/", 1)[-1]
    if name.startswith(".env"):
        return True
    if "/.secrets/" in "/" + norm or norm.startswith(".secrets/"):
        return True
    if SENSITIVE_NAME_RE.search(name):
        return True
    return any(name.endswith(suf) for suf in SENSITIVE_SUFFIXES)


def classify_path(rel_path: str, root: Path, leases: dict, quiet_minutes: int,
                  now: datetime) -> dict:
    """Return {bucket, tags, detail} for one worktree path."""
    norm = _norm(rel_path)
    tags, detail = [], {}
    if norm in leases:
        tags.append(HELD)
        detail["lease"] = leases[norm]
    if is_sensitive(norm):
        tags.append(SENSITIVE)
    try:
        st = (root / rel_path).stat()
    except OSError:
        st = None
    if st is not None:
        detail["sizeBytes"] = st.st_size
        if st.st_size > LARGE_BYTES:
            tags.append(LARGE)
        age = now.timestamp() - st.st_mtime
        detail["mtimeAgeSeconds"] = round(age, 1)
        if 0 <= age <= quiet_minutes * 60:
            tags.append(HOT)
    bucket = next((b for b in BUCKET_ORDER if b in tags), SAFE)
    if not tags:
        tags.append(SAFE)
    return {"bucket": bucket, "tags": tags, "detail": detail}


def bucket_rows(root: Path, porcelain_rows: list[dict], leases: dict,
                quiet_minutes: int, now: datetime) -> dict:
    rows = {b: [] for b in BUCKET_ORDER}
    for row in porcelain_rows:
        info = classify_path(row["path"], root, leases, quiet_minutes, now)
        entry = {"path": row["path"], "xy": row["xy"],
                 "bucket": info["bucket"], "tags": info["tags"],
                 **info["detail"]}
        rows[info["bucket"]].append(entry)
    return rows


# ------------------------------------------------------------------- git

def run_git_status(root: Path, git_exe: str = "git") -> tuple[int, str]:
    """Read-only status; --no-optional-locks so index.lock is never taken."""
    try:
        proc = subprocess.run(
            [git_exe, "--no-optional-locks", "-C", str(root),
             "status", "--porcelain=v1", "-z"],
            capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 127, f"git-call-failed:{type(exc).__name__}"
    if proc.returncode != 0:
        return proc.returncode, proc.stderr.strip() or "status-failed"
    return 0, proc.stdout


# ----------------------------------------------------------------- outputs

def write_plan_outputs(root: Path, rows: dict, quiet_minutes: int,
                       now: datetime, out_dir: Path | None = None) -> dict:
    out_dir = out_dir or (root / OUT_DIR_REL)
    out_dir.mkdir(parents=True, exist_ok=True)
    ts = now.strftime("%Y%m%d-%H%M")
    counts = {b: len(rows[b]) for b in BUCKET_ORDER}
    uncheck = [e["path"] for b in BUCKET_ORDER if b != SAFE for e in rows[b]]

    doc = {"schemaVersion": SCHEMA, "generatedAt": _iso(now),
           "quietMinutes": quiet_minutes, "bucketCounts": counts,
           "buckets": {b: rows[b] for b in BUCKET_ORDER},
           "uncheckPaths": uncheck}
    json_path = out_dir / f"commit-window-{ts}.json"
    json_path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n",
                         encoding="utf-8")

    lines = [f"# commit window plan {ts}", "",
             "| bucket | count |", "|---|---|"]
    lines += [f"| {b} | {counts[b]} |" for b in BUCKET_ORDER]
    lines.append("")
    for b in (HELD, SENSITIVE, LARGE, HOT):
        if not rows[b]:
            continue
        lines.append(f"## {b}")
        for e in rows[b]:
            extra = ""
            if b == HELD and e.get("lease"):
                extra = f" — {e['lease']['ownerId']} (exp {e['lease'].get('expiresAtKst') or e['lease'].get('expiresAt')})"
            elif b == LARGE:
                extra = f" — {e.get('sizeBytes', 0) / 1048576:.1f} MB"
            elif b == HOT:
                extra = f" — modified {e.get('mtimeAgeSeconds', '?')}s ago"
            lines.append(f"- `{e['path']}`{extra}")
        lines.append("")
    lines.append(f"SAFE paths: {counts[SAFE]} (see JSON for list)")
    md_path = out_dir / f"commit-window-{ts}.md"
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    uncheck_path = out_dir / f"uncheck-{ts}.txt"
    uncheck_path.write_text("\n".join(uncheck) + ("\n" if uncheck else ""),
                            encoding="utf-8")
    return {"json": str(json_path), "md": str(md_path),
            "uncheck": str(uncheck_path), "counts": counts}


# -------------------------------------------------------------------- flag

def flag_path(root: Path) -> Path:
    return root / FLAG_REL


def flag_state(root: Path, now: datetime) -> dict:
    path = flag_path(root)
    if not path.exists():
        return {"state": "closed", "path": str(path)}
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"state": "open", "path": str(path), "note": "unreadable-flag"}
    opened = _parse_dt(doc.get("openedAt"))
    ttl = doc.get("ttlMinutes", DEFAULT_FLAG_TTL_MINUTES)
    expires = opened + timedelta(minutes=ttl) if opened else None
    state = "open"
    if expires is not None and now > expires:
        state = "expired"
    return {"state": state, "path": str(path), "openedAt": doc.get("openedAt"),
            "ttlMinutes": ttl, "by": doc.get("by"),
            "expiresAt": _iso(expires) if expires else None,
            "expiresAtKst": _kst(expires)}


def cmd_open(root: Path, args) -> dict:
    path = flag_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    now = datetime.now(timezone.utc)
    doc = {"openedAt": _iso(now), "ttlMinutes": args.ttl, "by": args.by}
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")
    return {"state": "open", **flag_state(root, now)}


def cmd_close(root: Path, _args) -> dict:
    path = flag_path(root)
    existed = path.exists()
    if existed:
        path.unlink()
    return {"state": "closed", "removed": existed, "path": str(path)}


# -------------------------------------------------------------------- lock

def git_processes() -> list[dict]:
    """Running git.exe processes: PID, start time, parent name (read-only)."""
    ps = ("$ErrorActionPreference='SilentlyContinue'; "
          "$g = Get-CimInstance Win32_Process -Filter \"Name='git.exe'\"; "
          "$all = Get-CimInstance Win32_Process; "
          "$map = @{}; foreach ($p in $all) { $map[$p.ProcessId] = $p.Name }; "
          "$g | ForEach-Object { [pscustomobject]@{ ProcessId=$_.ProcessId; "
          "CreationDate=$_.CreationDate.ToString('o'); "
          "ParentProcessId=$_.ParentProcessId; "
          "ParentName=$map[[int]$_.ParentProcessId] } } | ConvertTo-Json -Compress")
    try:
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps],
            capture_output=True, text=True, timeout=30)
        data = json.loads(proc.stdout) if proc.stdout.strip() else []
        return data if isinstance(data, list) else [data]
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return []


def cmd_lock(root: Path, _args) -> dict:
    lock = root / LOCK_REL
    info: dict = {"indexLock": {"path": str(lock), "exists": False}}
    try:
        st = lock.stat()
        info["indexLock"] = {
            "path": str(lock), "exists": True, "sizeBytes": st.st_size,
            "ageSeconds": round(datetime.now().timestamp() - st.st_mtime, 1)}
    except OSError:
        pass
    info["gitProcesses"] = git_processes()
    info["note"] = ("report only — never delete index.lock, never kill git.exe; "
                    "retry the user commit after the lock clears")
    return info


# -------------------------------------------------------------------- main

def cmd_plan(root: Path, args) -> dict:
    now = datetime.now(timezone.utc)
    code, out = run_git_status(root, args.git)
    if code != 0:
        return {"status": "unavailable", "reason": out, "exitCode": code}
    rows = bucket_rows(root, parse_porcelain_z(out), load_leases(root),
                       args.quiet_minutes, now)
    outputs = write_plan_outputs(root, rows, args.quiet_minutes, now)
    return {"status": "ok", "bucketCounts": outputs["counts"],
            "outputs": {k: outputs[k] for k in ("json", "md", "uncheck")},
            "uncheckPaths": [e["path"] for b in BUCKET_ORDER if b != SAFE
                             for e in rows[b]]}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="git_commit_window.py")
    ap.add_argument("--root", default=".")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("plan")
    p.add_argument("--quiet-minutes", type=int, default=DEFAULT_QUIET_MINUTES)
    p.add_argument("--git", default="git")

    sub.add_parser("lock")

    p = sub.add_parser("open")
    p.add_argument("--ttl", type=int, default=DEFAULT_FLAG_TTL_MINUTES)
    p.add_argument("--by", default=os.environ.get("USERNAME", "user"))

    sub.add_parser("close")
    sub.add_parser("status")

    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    handler = {"plan": cmd_plan, "lock": cmd_lock, "open": cmd_open,
               "close": cmd_close,
               "status": lambda r, a: flag_state(r, datetime.now(timezone.utc))}
    result = handler[args.cmd](root, args)
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    if args.cmd == "plan" and result.get("status") != "ok":
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
