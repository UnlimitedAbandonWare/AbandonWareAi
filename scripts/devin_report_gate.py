#!/usr/bin/env python3
"""Check Devin ledger report.md files. Stdlib only.

devin_session_guard.py watches CLI lock files, zombie PIDs, and sessions.db.
It does not read report.md, so this gate does not wrap it.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "devin-report-gate.v1"
READ_CAP = 200_000


def _parse_stamp(value: str):
    if not value:
        return None
    raw = value.strip().replace("Z", "+00:00")
    try:
        stamp = datetime.fromisoformat(raw)
    except ValueError:
        return None
    if stamp.tzinfo is None:
        stamp = stamp.replace(tzinfo=timezone.utc)
    return stamp


def _read_capped(path: Path) -> str:
    try:
        data = path.read_bytes()[:READ_CAP]
    except OSError:
        return ""
    return data.decode("utf-8", errors="replace").lstrip("\ufeff")


def load_skip_names(root: Path, now: datetime) -> set[str]:
    names: set[str] = set()
    autonomy = root / "data" / "agent-handoff" / "codex-autonomy"
    if autonomy.is_dir():
        for journal in autonomy.glob("*/journal.json"):
            try:
                payload = json.loads(journal.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            if payload.get("status") == "in_progress":
                names.add(journal.parent.name)
    locks = root / "__patch_drop__" / "source-edit-locks"
    if locks.is_dir():
        for lease_path in locks.glob("*/lease.json"):
            try:
                payload = json.loads(lease_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            expires = _parse_stamp(str(payload.get("expiresAtUtc") or payload.get("expiresAt") or ""))
            if expires is not None and expires <= now:
                continue
            topic = str(payload.get("topic") or "")
            if topic:
                names.add(topic)
    return names


def _recent(path: Path, hours: float, now_ts: float) -> bool:
    stamps = [path.stat().st_mtime]
    for name in ("report.md", "journal.json"):
        child = path / name
        if child.is_file():
            stamps.append(child.stat().st_mtime)
    return (now_ts - max(stamps)) < hours * 3600


def _judge(text: str) -> dict:
    lines = text.splitlines()
    first = lines[0].strip() if lines else ""
    first_ok = first.startswith("외부 API:")
    acceptance = bool(re.search(r"(?i)acceptance", text))
    marks = bool(re.search(r"\b(PASS|FAIL|NOT_RUN)\b", text))
    head = "\n".join(lines[:30])
    claims_done = bool(re.search(r"완료|DONE", head))
    open_item = bool(re.search(r"\b(FAIL|NOT_RUN)\b", text))
    contradiction = claims_done and open_item
    if not text:
        nudge = "report.md를 만들고 첫 줄에 `외부 API:` 와 Acceptance 표를 적어 줘."
    elif not first_ok:
        nudge = "report.md 첫 줄을 `외부 API:` 로 시작해 줘."
    elif not acceptance or not marks:
        nudge = "report.md에 Acceptance 표와 NOT_RUN 표기를 채워 줘."
    elif contradiction:
        nudge = "완료라고 적었는데 FAIL 또는 NOT_RUN이 있다. 완료 문장을 빼거나 그 항목을 끝내 줘."
    else:
        nudge = ""
    ok = bool(text) and first_ok and acceptance and marks and not contradiction
    return {
        "hasReport": bool(text),
        "firstLineOk": first_ok,
        "acceptance": acceptance,
        "marks": marks,
        "contradiction": contradiction,
        "ok": ok,
        "nudge": nudge,
    }


def iter_ledgers(root: Path):
    handoff = root / "data" / "agent-handoff"
    if handoff.is_dir():
        for path in sorted(handoff.glob("devin-*")):
            if path.is_dir():
                yield "agent-handoff/devin-*", path
    autonomy = handoff / "codex-autonomy"
    if autonomy.is_dir():
        for path in sorted(autonomy.glob("devin-*")):
            if path.is_dir():
                yield "codex-autonomy/devin-*", path


def scan(root: Path, hours: float = 2.0, now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    skip_names = load_skip_names(root, now)
    now_ts = now.timestamp()
    rows = []
    for label, path in iter_ledgers(root):
        reasons = []
        if path.name in skip_names:
            reasons.append("active-journal-or-lease")
        if _recent(path, hours, now_ts):
            reasons.append("mtime-within-%sh" % hours)
        report = path / "report.md"
        if reasons:
            rows.append({
                "set": label,
                "ledger": path.name,
                "skip": True,
                "skipReason": ",".join(reasons),
                "hasReport": report.is_file(),
                "firstLineOk": None,
                "acceptance": None,
                "marks": None,
                "contradiction": None,
                "ok": None,
                "nudge": "",
            })
            continue
        text = _read_capped(report) if report.is_file() else ""
        judged = _judge(text)
        judged.update({"set": label, "ledger": path.name, "skip": False, "skipReason": ""})
        rows.append(judged)
    checked = [row for row in rows if not row["skip"]]
    return {
        "schema": SCHEMA,
        "root": str(root),
        "hours": hours,
        "scanned": len(rows),
        "skipped": sum(1 for row in rows if row["skip"]),
        "checked": len(checked),
        "ok": sum(1 for row in checked if row["ok"]),
        "problems": sum(1 for row in checked if not row["ok"]),
        "rows": rows,
    }


def render_markdown(result: dict) -> str:
    lines = [
        "# Devin report gate",
        "",
        "- scanned: %s" % result["scanned"],
        "- skipped: %s" % result["skipped"],
        "- checked: %s" % result["checked"],
        "- ok: %s" % result["ok"],
        "- problems: %s" % result["problems"],
        "",
        "| set | ledger | skip | report | first | acceptance | marks | contradiction | nudge |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for row in result["rows"]:
        lines.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            row["set"],
            row["ledger"],
            row["skipReason"] if row["skip"] else "",
            row["hasReport"],
            row["firstLineOk"],
            row["acceptance"],
            row["marks"],
            row["contradiction"],
            row["nudge"],
        ))
    lines.append("")
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Check Devin report.md ledgers")
    parser.add_argument("--root", default=".")
    parser.add_argument("--hours", type=float, default=2.0)
    parser.add_argument("--json", dest="json_path", default="")
    parser.add_argument("--md", dest="md_path", default="")
    args = parser.parse_args(argv)
    result = scan(Path(args.root), args.hours)
    print("scanned=%s skipped=%s checked=%s ok=%s problems=%s" % (
        result["scanned"], result["skipped"], result["checked"], result["ok"], result["problems"]))
    shown = 0
    for row in result["rows"]:
        if row["skip"] and "devin-path-registry" in row["ledger"]:
            print("SKIP %s %s" % (row["ledger"], row["skipReason"]))
        elif not row["skip"] and not row["ok"] and shown < 30:
            print("NUDGE %s | %s" % (row["ledger"], row["nudge"]))
            shown += 1
    if args.json_path:
        out = Path(args.json_path)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if args.md_path:
        out = Path(args.md_path)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(render_markdown(result), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
