#!/usr/bin/env python3
"""
codex_ask_audit.py
==================
Codex 세션 rollout(.jsonl)에서 request_user_input* 선택 카드 호출을 읽기 전용으로
집계한다. 카드를 띄운 시각(KST), 세션, 질문 제목(200자), 옵션, 다음 사용자
메시지까지 걸린 시간(없으면 NO_REPLY)을 뽑고, 각 질문을
codex_question_classifier.classify 에 넣어 verdict/rule 을 붙인다.
분류기가 AUTO인데 카드를 띄운 경우는 AVOIDABLE_ASK 로 센다.

Usage:
  python -B scripts/codex_ask_audit.py [--days 3] [--rollout FILE.jsonl] [--json]

Exit codes: 0 정상, 2 사용법 오류. 표준 라이브러리만 사용, 네트워크 없음.
"""

import argparse
import glob
import json
import os
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from codex_question_classifier import classify  # noqa: E402

KST = timezone(timedelta(hours=9))

# 비밀값 가림 — 출력/evidence에 원문을 남기지 않는다.
SECRET_RE = re.compile(
    r"sk-[A-Za-z0-9_-]{6,}"
    r"|vck_[A-Za-z0-9]{6,}"
    r"|Bearer\s+[A-Za-z0-9._~+/=-]{8,}"
    r"|api[_-]?key\s*[=:]\s*\S+"
    r"|gAAAA[A-Za-z0-9_-]{6,}"
    r"|xox[baprs]-[A-Za-z0-9-]{6,}"
    r"|AIza[0-9A-Za-z_-]{10,}"
    r"|-----BEGIN [A-Z ]*PRIVATE KEY-----",
    re.IGNORECASE,
)

CALL_NAME_RE = re.compile(r"^request_user_input")
CALL_ID_RE = re.compile(r"call_[A-Za-z0-9_-]+")

SEVERITY = {"AUTO": 0, "HOLD": 1, "ASK_ONCE": 2}


def redact(text):
    return SECRET_RE.sub("[REDACTED]", text or "")


def _parse_ts(value):
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except Exception:
        return None


def _kst(dt):
    return dt.astimezone(KST).strftime("%Y-%m-%d %H:%M:%S")


def _session_id(path):
    name = os.path.basename(path)
    m = re.search(r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})",
                  name)
    return m.group(1)[:8] if m else os.path.splitext(name)[0][:16]


def scan_rollout(path):
    """rollout jsonl 하나 -> 카드 dict 리스트 (시간순).

    card = {file, session, ts_utc, ts_kst, call_id, questions[{title,options,
    verdict,rule}], verdict, rule, wait_min|None, replied}
    """
    calls = {}          # call_id -> card dict (순서 유지)
    outputs = {}        # call_id -> output snippet
    user_ts = []        # 이후 사용자 메시지 시각 목록
    try:
        fh = open(path, encoding="utf-8", errors="replace")
    except OSError:
        return []
    with fh:
        for line in fh:
            if ("request_user_input" not in line
                    and '"role":"user"' not in line
                    and '"role": "user"' not in line):
                continue
            try:
                j = json.loads(line)
            except Exception:
                continue
            if j.get("type") != "response_item":
                continue
            p = j.get("payload") or {}
            ts = _parse_ts(j.get("timestamp"))
            if p.get("type") == "function_call" and CALL_NAME_RE.match(
                    str(p.get("name", ""))):
                cid = p.get("call_id") or p.get("id") or ""
                try:
                    qs = json.loads(p.get("arguments", "{}")).get("questions", [])
                except Exception:
                    qs = []
                questions = []
                for q in qs:
                    full = redact(str(q.get("title", "")))
                    title = full[:200]
                    options = [redact(str(o))[:160]
                               for o in q.get("options", [])]
                    verdict = classify(full)
                    questions.append({
                        "title": title,
                        "options": options,
                        "verdict": verdict["verdict"],
                        "rule": verdict["rule"],
                    })
                worst = "AUTO"
                for q in questions:
                    if SEVERITY[q["verdict"]] > SEVERITY[worst]:
                        worst = q["verdict"]
                calls[cid] = {
                    "file": os.path.basename(path),
                    "session": _session_id(path),
                    "ts_utc": j.get("timestamp"),
                    "ts_kst": _kst(ts) if ts else "?",
                    "_ts": ts,
                    "call_id": cid,
                    "questions": questions,
                    "verdict": worst,
                    "wait_min": None,
                    "replied": False,
                    "output": "",
                }
            elif p.get("type") == "function_call_output":
                cid = p.get("call_id") or ""
                if cid:
                    outputs[cid] = redact(str(p.get("output", "")))[:120]
            elif p.get("type") == "message" and p.get("role") == "user":
                if ts:
                    user_ts.append(ts)
    cards = []
    for cid, card in calls.items():
        card["output"] = outputs.get(cid, "")
        later = [t for t in user_ts if card["_ts"] and t > card["_ts"]]
        if later:
            card["wait_min"] = round((min(later) - card["_ts"]).total_seconds() / 60.0, 1)
            card["replied"] = True
        card.pop("_ts", None)
        cards.append(card)
    cards.sort(key=lambda c: c["ts_utc"] or "")
    return cards


def find_rollouts(days, sessions_dir=None):
    root = sessions_dir or os.path.expanduser(r"~\.codex\sessions")
    cut = time.time() - days * 86400
    return sorted(f for f in glob.glob(os.path.join(root, "**", "rollout-*.jsonl"),
                                       recursive=True)
                  if os.path.getmtime(f) >= cut)


def summarize(cards):
    waits = [c["wait_min"] for c in cards if c["wait_min"] is not None]
    return {
        "cards": len(cards),
        "avoidable": sum(1 for c in cards if c["verdict"] == "AUTO"),
        "ask_once": sum(1 for c in cards if c["verdict"] == "ASK_ONCE"),
        "hold": sum(1 for c in cards if c["verdict"] == "HOLD"),
        "no_reply": sum(1 for c in cards if not c["replied"]),
        "avg_wait_min": round(sum(waits) / len(waits), 1) if waits else None,
        "replied": len(waits),
    }


def _title_of(card):
    if not card["questions"]:
        return ""
    return re.sub(r"\s+", " ", card["questions"][0]["title"])


def render_table(cards, summary, window_desc):
    lines = ["# codex ask audit — %s" % window_desc,
             "KST                  session  verdict   rule        wait_min  title"]
    for c in cards:
        rule = ",".join(sorted({q["rule"] for q in c["questions"]})) or "-"
        wait = ("%.1f" % c["wait_min"]) if c["wait_min"] is not None else "NO_REPLY"
        title = _title_of(c)
        lines.append("%-20s %-8s %-9s %-11s %-9s %s" % (
            c["ts_kst"], c["session"], c["verdict"], rule[:11], wait,
            title[:70]))
    lines.append(
        "SUMMARY cards={cards} AVOIDABLE={avoidable} ASK_ONCE={ask_once} "
        "HOLD={hold} NO_REPLY={no_reply} avg_wait={avg}m replies={replied}".format(
            avg=summary["avg_wait_min"], **summary))
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Codex request_user_input card audit")
    ap.add_argument("--days", type=float, default=3,
                    help="rollout mtime window in days (default 3)")
    ap.add_argument("--rollout", help="scan a single rollout jsonl")
    ap.add_argument("--sessions-dir", help="override ~/.codex/sessions")
    ap.add_argument("--json", action="store_true", help="emit JSON")
    args = ap.parse_args(argv)

    if args.rollout:
        files = [args.rollout]
        window = "rollout=%s" % args.rollout
    else:
        files = find_rollouts(args.days, args.sessions_dir)
        window = "days=%s files=%d" % (args.days, len(files))

    cards = []
    for f in files:
        cards.extend(scan_rollout(f))
    cards.sort(key=lambda c: c["ts_utc"] or "")
    summary = summarize(cards)

    if args.json:
        print(json.dumps({"window": window, "cards": cards,
                          "summary": summary}, ensure_ascii=False, indent=1))
    else:
        print(render_table(cards, summary, window))
    return 0


if __name__ == "__main__":
    sys.exit(main())
