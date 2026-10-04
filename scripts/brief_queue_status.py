#!/usr/bin/env python3
"""brief_queue_status.py — PASTE_* 지시서 ↔ 세션 보고/저널 짝짓기. OPEN 잔량을 본다.

짝짓기: 파일명 <AGENT>_<topic>_<yyyymmdd> 를 ledger/저널 쪽의 <agent>-<topic>
(또는 topic 조각 포함 taskId/journal purpose)과 비교한다. 모르면 UNMATCHED.

분류:
  OPEN      — 짝은 있는데 아직 보고/close 없음 (journal in_progress 포함)
  DONE      — REPORT Acceptance 전부 PASS 또는 사유 있는 NOT_RUN, 또는 journal close=verified
  BLOCKED   — REPORT Acceptance에 FAIL/누락, 또는 journal close가 blocked/abandoned
  SUPERSEDED— 같은 <agent>-<topic> 의 더 새 지시서가 Downloads에 존재
  UNMATCHED — 짝 없음

사용: python -B scripts/brief_queue_status.py [--downloads DIR] [--root .] [--out FILE]
출력: JSON {agents:{}, oldestOpen:[], advice, items:[...]} + 한 줄 요약. 종료 0.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from brief_lint import _write_utf8  # noqa: E402
from brief_round_gate import parse_verdicts  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DOWNLOADS = Path.home() / "Downloads"
ACC_HEAD_RE = re.compile(r"acceptance|완료\s*기준|수용\s*기준", re.IGNORECASE)
ACC_ID_RE = re.compile(r"^\s*(?:[-*]\s*)?(?:\*\*)?([A-Z]{1,3}\d{1,2})\b")
NAME_RE = re.compile(r"^PASTE_(?P<agent>[A-Za-z]+)_(?P<topic>.+?)_(?P<date>\d{8})(?:[^.]*)\.txt$",
                     re.IGNORECASE)
REPORT_NAMES = ("REPORT.md", "report.md", "final-report.md", "FINAL-REPORT.md")
OPEN_LIMIT = 3


def _norm(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")


def scan_briefs(downloads: Path) -> list[dict]:
    items = []
    if not downloads.is_dir():
        return items
    for f in sorted(downloads.glob("PASTE_*.txt")):
        m = NAME_RE.match(f.name)
        if not m:
            items.append({"file": f.name, "agent": "UNKNOWN", "topic": _norm(f.stem),
                          "date": "", "unmatchedReason": "name-pattern"})
            continue
        items.append({"file": f.name, "agent": m.group("agent").lower(),
                      "topic": _norm(m.group("topic")), "date": m.group("date"),
                      "mtimeUtc": datetime.fromtimestamp(
                          f.stat().st_mtime, timezone.utc).isoformat()})
    # 같은 agent+topic 의 더 새 지시서가 있으면 구버전은 SUPERSEDED
    newest = {}
    for it in items:
        if "date" not in it:
            continue
        key = (it["agent"], it["topic"])
        newest[key] = max(newest.get(key, ""), it["date"])
    for it in items:
        if "date" in it and it["date"] < newest.get((it["agent"], it["topic"]), ""):
            it["supersededBy"] = newest[(it["agent"], it["topic"])]
    return items


def _scan_ledgers(root: Path) -> list[dict]:
    """보고/저널 인덱스: dirName, agentHint, taskId(저널), status, verdict."""
    rows = []
    handoff = root / "data" / "agent-handoff"
    if not handoff.is_dir():
        return rows
    for d in sorted(handoff.iterdir()):
        if not d.is_dir():
            continue
        row = {"dir": d.name, "norm": _norm(d.name), "reports": [], "journals": []}
        for name in REPORT_NAMES:
            if (d / name).is_file():
                row["reports"].append(d / name)
        for sub in sorted(d.iterdir()):
            if sub.is_dir():
                for name in REPORT_NAMES:
                    if (sub / name).is_file():
                        row["reports"].append(sub / name)
        rows.append(row)
    journal_root = handoff / "codex-autonomy"
    if journal_root.is_dir():
        for d in sorted(journal_root.iterdir()):
            jf = d / "journal.json"
            if not jf.is_file():
                continue
            try:
                j = json.loads(jf.read_bytes().decode("utf-8-sig"))
            except (OSError, ValueError):
                continue
            rows.append({"dir": f"codex-autonomy/{d.name}", "norm": _norm(d.name),
                         "taskId": d.name, "agent": j.get("agent"),
                         "status": j.get("status"), "result": j.get("result"),
                         "purpose": (j.get("purpose") or "")[:120],
                         "reports": [p for name in REPORT_NAMES
                                    if (p := d / name).is_file()]
                         + [p for sub in d.iterdir() if sub.is_dir()
                            for name in REPORT_NAMES if (p := sub / name).is_file()],
                         "updatedAtUtc": j.get("updatedAtUtc")})
    return rows


def _match(item: dict, ledgers: list[dict]) -> dict | None:
    topic = item["topic"]
    agent = item["agent"]
    # 1) 디렉터리 이름에 topic이 통째로 들어간 것 우선
    exact = [r for r in ledgers if topic and topic in r["norm"]]
    if exact:
        same_agent = [r for r in exact
                      if r.get("agent") and agent[:3] in str(r["agent"]).lower()
                      or agent in r["norm"]]
        return (same_agent or exact)[0]
    # 2) topic 토큰 전부 포함
    tokens = [t for t in topic.split("-") if len(t) > 2]
    if tokens:
        loose = [r for r in ledgers if all(t in r["norm"] for t in tokens)]
        if loose:
            return loose[0]
    return None


def _acceptance_verdict(report: Path) -> str:
    text = report.read_bytes().decode("utf-8-sig", errors="replace")
    lines = text.splitlines()
    start = next((i + 1 for i, ln in enumerate(lines) if ACC_HEAD_RE.search(ln)), None)
    if start is None:
        return "BLOCKED"  # 보고서는 있는데 Acceptance 섹션 없음
    wanted = [m.group(1) for ln in lines[start:start + 80]
              for m in [ACC_ID_RE.match(ln)] if m]
    if not wanted:
        return "BLOCKED"
    verdicts = parse_verdicts(text, wanted)
    for aid in wanted:
        v = verdicts.get(aid)
        if v is None or v["status"] not in ("PASS", "HOLD") and not (
                v and v["status"] == "NOT_RUN" and len(v.get("reason", "")) >= 5):
            return "BLOCKED"
    return "DONE"


def classify(item: dict, ledgers: list[dict]) -> dict:
    if "supersededBy" in item:
        item["status"] = "SUPERSEDED"
        return item
    row = _match(item, ledgers)
    if row is None:
        item["status"] = "UNMATCHED"
        return item
    item["matched"] = row["dir"]
    if row.get("reports"):
        item["status"] = _acceptance_verdict(row["reports"][0])
        item["report"] = row["reports"][0].name
        return item
    if row.get("status") == "in_progress" or row.get("status") is None:
        item["status"] = "OPEN"
        return item
    if row.get("status") in ("closed", "done"):
        item["status"] = "DONE" if row.get("result") in (None, "verified") else "BLOCKED"
        return item
    item["status"] = "OPEN"
    return item


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--downloads", default=str(DEFAULT_DOWNLOADS))
    ap.add_argument("--out", default=None, help="JSON 저장 경로")
    ap.add_argument("--today", default=None, help="yyyymmdd 만 보려면 지정")
    args = ap.parse_args(argv)

    items = scan_briefs(Path(args.downloads))
    if args.today:
        items = [i for i in items if i.get("date") == args.today]
    ledgers = _scan_ledgers(Path(args.root))
    for it in items:
        classify(it, ledgers)

    agents: dict[str, dict] = {}
    for it in items:
        a = agents.setdefault(it["agent"], {"OPEN": 0, "DONE": 0, "BLOCKED": 0,
                                            "SUPERSEDED": 0, "UNMATCHED": 0})
        a[it["status"]] = a.get(it["status"], 0) + 1
    open_items = [i for i in items if i["status"] == "OPEN"]
    open_items.sort(key=lambda i: (i.get("date", ""), i.get("mtimeUtc", "")))
    over = {a: n["OPEN"] for a, n in agents.items() if n["OPEN"] > OPEN_LIMIT}
    advice = (f"{', '.join(sorted(over))}: OPEN>{OPEN_LIMIT} → 새 지시서 보류 권고"
              if over else "에이전트당 OPEN<=3 — 지시서 발행 가능")
    payload = {
        "schema": "awx.brief-queue-status.v1",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "agents": agents,
        "oldestOpen": [{"file": i["file"], "agent": i["agent"],
                        "date": i.get("date"), "matched": i.get("matched")}
                       for i in open_items[:5]],
        "advice": advice,
        "items": items,
    }
    if args.out:
        Path(args.out).write_bytes((json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
                                   .encode("utf-8"))
    summary = "queue: " + ", ".join(
        f"{a}={c.get('OPEN', 0)}open" for a, c in sorted(agents.items())) or "queue: empty"
    _write_utf8(summary + "\n" + advice + "\n")
    _write_utf8(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
