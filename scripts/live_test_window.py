#!/usr/bin/env python3
"""live_test_window.py — live user-test window marker for demo-1.

While the marker `var/live-test/active.json` is active, agents must not
restart, compile, ForceRestart or DevWatch-restart the 18180 server — the user
is running a live device test (Fold6 + Meta Ray-Ban Display). The hard gates
live in `scripts/start_rag_stack.ps1` (`live-test-window-active` refusal) and
`scripts/dev_reload_watch.ps1` (deferred restart). Rule SSOT:
`.agents/skills/demo1-live-test-window/SKILL.md`.

Stdlib only, no network. Commands:

  python -B scripts/live_test_window.py start --minutes 90 [--owner NAME] [--devices fold6,glasses] [--note TEXT]
  python -B scripts/live_test_window.py status
  python -B scripts/live_test_window.py end

Marker verdict (shared with the PowerShell readers — keep in sync):
  * file absent                                  -> inactive (status: absent)
  * parses and now < untilKst                    -> active   (status: active)
  * parses and untilKst has passed               -> inactive (status: expired)
  * exists but cannot be fully evaluated         -> active only while the file
    is fresh (< 24 h): a possibly-live test stays protected, but a stale
    corrupt artifact cannot block restarts forever (status: corrupt).
"""
import argparse
import json
import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCHEMA = "awx.live-test-window.v1"
KST = timezone(timedelta(hours=9), name="KST")
MARKER_REL = Path("var") / "live-test" / "active.json"
CORRUPT_PROTECT_HOURS = 24
DEFAULT_MINUTES = 90
DEFAULT_DEVICES = ["fold6", "glasses"]


def _now_kst() -> datetime:
    return datetime.now(KST)


def _iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


def marker_path(root: Path) -> Path:
    return root / MARKER_REL


def _evaluate(path: Path) -> dict:
    """Verdict for one marker path; see module docstring for the shared rule."""
    out = {"path": str(path), "active": False, "status": "absent"}
    if not path.is_file():
        return out
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        until = datetime.fromisoformat(str(data["untilKst"]))
        if until.tzinfo is None:
            until = until.replace(tzinfo=KST)
        until = until.astimezone(KST)
        active = _now_kst() < until
        out.update(
            status="active" if active else "expired",
            active=active,
            owner=data.get("owner"),
            devices=data.get("devices"),
            note=data.get("note"),
            startedAtKst=data.get("startedAtKst"),
            untilKst=_iso(until),
            remainingMinutes=max(0, int((until - _now_kst()).total_seconds() // 60)) if active else 0,
        )
        return out
    except Exception:
        # Exists but cannot be fully evaluated: protect a possibly-live test,
        # bounded by file freshness so stale corruption cannot block forever.
        try:
            age_hours = (datetime.now().timestamp() - path.stat().st_mtime) / 3600
        except OSError:
            age_hours = CORRUPT_PROTECT_HOURS
        out.update(status="corrupt", active=age_hours < CORRUPT_PROTECT_HOURS)
        return out


def cmd_start(root: Path, args) -> int:
    minutes = args.minutes if args.minutes is not None else DEFAULT_MINUTES
    if minutes < 1 or minutes > 24 * 60:
        print("refused: --minutes must be 1..1440", file=sys.stderr)
        return 2
    now = _now_kst()
    record = {
        "schemaVersion": SCHEMA,
        "owner": args.owner or os.environ.get("AWX_CALLER") or os.environ.get("USERNAME") or "agent",
        "startedAtKst": _iso(now),
        "untilKst": _iso(now + timedelta(minutes=minutes)),
        "devices": args.devices or list(DEFAULT_DEVICES),
        "note": args.note or "",
    }
    path = marker_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix="active.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(record, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    verdict = _evaluate(path)
    print(f"live-test window STARTED owner={record['owner']} until={record['untilKst']} devices={','.join(record['devices'])}")
    print(json.dumps(verdict, ensure_ascii=False))
    return 0


def cmd_status(root: Path, _args) -> int:
    verdict = _evaluate(marker_path(root))
    line = {"active": "LIVE TEST WINDOW ACTIVE — no 18180 restart/compile",
            "expired": "window expired — marker inactive",
            "absent": "no live-test marker",
            "corrupt": "marker unreadable — protective hold while file < 24h old"}[verdict["status"]]
    print(line)
    print(json.dumps(verdict, ensure_ascii=False))
    return 0


def cmd_end(root: Path, _args) -> int:
    path = marker_path(root)
    existed = path.exists()
    if existed:
        path.unlink()
    print("live-test window ENDED" if existed else "no live-test marker to end")
    print(json.dumps(_evaluate(path), ensure_ascii=False))
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="live test window marker (demo-1)")
    parser.add_argument("--root", default=".", help="repo root (default: cwd)")
    sub = parser.add_subparsers(dest="command", required=True)
    p_start = sub.add_parser("start")
    p_start.add_argument("--minutes", type=int, default=DEFAULT_MINUTES)
    p_start.add_argument("--owner", default="")
    p_start.add_argument("--devices", type=lambda s: [x.strip() for x in s.split(",") if x.strip()])
    p_start.add_argument("--note", default="")
    sub.add_parser("status")
    sub.add_parser("end")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    return {"start": cmd_start, "status": cmd_status, "end": cmd_end}[args.command](root, args)


if __name__ == "__main__":
    sys.exit(main())
