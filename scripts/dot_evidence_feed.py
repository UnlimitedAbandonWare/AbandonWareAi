#!/usr/bin/env python3
"""dot_evidence_feed.py - dot 전용 ~10줄 런타임 팩트 피더 (읽기 전용).

logs/debug-events.ndjson (+ var/rag-launcher/**/launcher.log)을 파싱해
요청 1건의 핵심 런타임 사실만 정제해 출력한다. 토큰·쿼리 원문·비밀값은
절대 출력하지 않는다 — 이 로그는 이미 hash/len 봉투로 적재되며, 이 도구는
봉투 값과 열거형만 통과시킨다.

사용:
  python -B scripts/dot_evidence_feed.py --latest
  python -B scripts/dot_evidence_feed.py --trace <traceId|hash:...>
  python -B scripts/dot_evidence_feed.py --request <requestId|hash|sid>
  옵션: --since-hours N (기본 2, 결과 없으면 24h로 자동 확장) --json

출력 라인(고정 순서, 값이 없으면 na):
  [WINDOW]      선택된 trace, 창 시간, 이벤트 수
  [REQUEST]     reqHash ts queryLen queryHash reqModel respModel
  [RETRIEVE]    provider rawCount afterFilterCount timeout
  [PLAN/GATES]  officialOnly minCitations highRisk candidateCount promotedCount
  [VERIFIER]    judgeModel failSoft outcomeKnown failReason
  [HOLD_ACTION] hardGuardHeld action reasonCode
  [SOURCES]     W1..W5 호스트 도메인만 (없으면 none)
  [SUMMARY]     한 줄 결론
  [RUNTIME]     launcher run dir (발견 시)

exit: 0 ok / 2 usage / 3 증거 없음 / 4 내부 오류
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from log_redact import redact_text
except ImportError:
    def redact_text(text):
        return text, {}

EXIT_OK, EXIT_USAGE, EXIT_NO_DATA, EXIT_ERR = 0, 2, 3, 4
DEFAULT_SINCE_HOURS = 2
EXPAND_SINCE_HOURS = 24
MAX_VALUE_LEN = 80

ACTION_VALUES = {"HOLD", "DEGRADE", "ALLOW", "CONTINUE", "STOP", "BLOCK"}
LOCAL_HOSTS = {"127.0.0.1", "localhost", "0.0.0.0", "::1"}


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def events_path() -> Path:
    env = os.environ.get("DOT_FEED_EVENTS", "").strip()
    return Path(env) if env else repo_root() / "logs" / "debug-events.ndjson"


def launcher_root() -> Path:
    env = os.environ.get("DOT_FEED_LAUNCHER", "").strip()
    return Path(env) if env else repo_root() / "var" / "rag-launcher"


def parse_ts(raw):
    if not isinstance(raw, str):
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None


def iter_events(path: Path):
    try:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line.startswith("{"):
                    continue
                try:
                    ev = json.loads(line)
                except ValueError:
                    continue
                if isinstance(ev, dict):
                    yield ev
    except OSError:
        return


def env_value(v):
    """봉투 {present,len,hash12} 또는 스칼라만 안전 문자열로."""
    if v is None:
        return "na"
    if isinstance(v, dict):
        if v.get("hash12"):
            return "h12:" + str(v["hash12"])
        if v.get("present") is False:
            return "absent"
        return "na"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    s = str(v).strip()
    if not s:
        return "na"
    return s[:MAX_VALUE_LEN]


def norm_hash(raw: str) -> str:
    t = (raw or "").strip()
    return t[5:] if t.startswith("hash:") else t


def ev_key(ev) -> str | None:
    for k in ("traceId", "requestId", "sid"):
        v = ev.get(k)
        if isinstance(v, str) and v:
            return v
    return None


def collect_window(path: Path, since_hours: float):
    cutoff = datetime.now(timezone.utc) - timedelta(hours=since_hours)
    rows = []
    for ev in iter_events(path):
        ts = parse_ts(ev.get("ts"))
        if ts is None or ts < cutoff:
            continue
        rows.append((ts, ev))
    return rows


def group_traces(rows):
    groups = {}
    for ts, ev in rows:
        key = ev_key(ev)
        if not key:
            continue
        groups.setdefault(key, []).append((ts, ev))
    for g in groups.values():
        g.sort(key=lambda x: x[0])
    return groups


def find_trace(groups, want: str):
    w = norm_hash(want)
    for key in groups:
        cand = {key, norm_hash(key)}
        for ts, ev in groups[key]:
            for k in ("traceId", "requestId", "sid"):
                v = ev.get(k)
                if isinstance(v, str):
                    cand.add(v)
                    cand.add(norm_hash(v))
        if w in cand or want in cand:
            return key
    return None


def is_hostlike(s: str) -> bool:
    if not isinstance(s, str) or not s or s.startswith("hash:"):
        return False
    if s in LOCAL_HOSTS or len(s) > 60:
        return False
    return bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.\-]*\.[A-Za-z]{2,}", s))


def summarize(trace, events):
    d = {"reqModel": "na", "respModel": "na", "queryLen": "na", "queryHash": "na",
         "providers": [], "rawCount": None, "afterFilter": None, "timeout": None,
         "outCount": None,
         "officialOnly": "na", "minCitations": "na", "highRisk": "na",
         "candidateCount": "na", "promotedCount": "na", "disabledReason": None,
         "judgeModel": "na", "failSoft": "na", "outcomeKnown": "na",
         "failReason": "na", "hardGuard": False, "action": "na", "reasonCode": "na",
         "hosts": []}
    first_ts = events[0][0] if events else None
    for ts, ev in events:
        data = ev.get("data") or {}
        if not isinstance(data, dict):
            continue
        where = str(ev.get("where") or "")
        # REQUEST fields
        if d["queryLen"] == "na" and isinstance(data.get("queryLength"), int):
            d["queryLen"] = str(data["queryLength"])
        if d["queryHash"] == "na" and data.get("queryHash"):
            d["queryHash"] = env_value(data["queryHash"])
        for mk in ("chatModel", "reqModel", "model"):
            if d["reqModel"] == "na" and data.get(mk):
                d["reqModel"] = env_value(data[mk])
        for mk in ("respModel", "responseModel"):
            if d["respModel"] == "na" and data.get(mk):
                d["respModel"] = env_value(data[mk])
        # RETRIEVE: SearchTraceConsoleLogger providers map
        prov = data.get("providers")
        if isinstance(prov, dict):
            for name, pv in prov.items():
                if isinstance(pv, dict):
                    if name not in d["providers"]:
                        d["providers"].append(str(name))
                    rc = pv.get("returnedCount")
                    if isinstance(rc, int):
                        d["rawCount"] = (d["rawCount"] or 0) + rc
                    af = pv.get("afterFilterCount")
                    if isinstance(af, int):
                        d["afterFilter"] = (d["afterFilter"] or 0) + af
                    if pv.get("timeout") is True:
                        d["timeout"] = True
        if isinstance(data.get("outCount"), int):
            d["outCount"] = data["outCount"]
        # PLAN/GATES
        if "officialOnly" in data:
            d["officialOnly"] = env_value(data["officialOnly"])
        if "minCitations" in data:
            d["minCitations"] = env_value(data["minCitations"])
        elif "citationMin" in data and d["minCitations"] == "na":
            d["minCitations"] = env_value(data["citationMin"])
        if "highRisk" in data:
            d["highRisk"] = env_value(data["highRisk"])
        if "candidateCount" in data:
            d["candidateCount"] = env_value(data["candidateCount"])
        if "promotedCount" in data:
            d["promotedCount"] = env_value(data["promotedCount"])
        if data.get("disabledReason") and not d["disabledReason"]:
            d["disabledReason"] = env_value(data["disabledReason"])
        # VERIFIER
        for jk in ("judgeModel", "verifierModel"):
            if d["judgeModel"] == "na" and data.get(jk):
                d["judgeModel"] = env_value(data[jk])
        if "failSoft" in data and d["failSoft"] == "na":
            d["failSoft"] = env_value(data["failSoft"])
        if "outcomeKnown" in data:
            d["outcomeKnown"] = env_value(data["outcomeKnown"])
        if d["failReason"] == "na":
            for fk in ("failReason", "failureClass", "failureReason"):
                if data.get(fk) and str(data[fk]).lower() not in ("none", "null"):
                    d["failReason"] = env_value(data[fk])
                    break
        # HOLD_ACTION
        act = data.get("action")
        if isinstance(act, str) and act in ACTION_VALUES:
            d["action"] = act
            if data.get("reasonCode"):
                d["reasonCode"] = env_value(data["reasonCode"])
            if data.get("hardGuard") is True:
                d["hardGuard"] = True
        if data.get("hardGuard") is True:
            d["hardGuard"] = True
        # SOURCES
        for k, v in data.items():
            kl = str(k).lower()
            if "host" in kl or "domain" in kl:
                if is_hostlike(v if isinstance(v, str) else ""):
                    if v not in d["hosts"]:
                        d["hosts"].append(v)
    return d, first_ts


def launcher_run_for(ts):
    root = launcher_root()
    try:
        dirs = sorted((p for p in root.iterdir() if p.is_dir()), reverse=True)
    except OSError:
        return None
    if ts is None:
        return dirs[0].name if dirs else None
    best = None
    for p in dirs[:8]:
        m = re.match(r"(\d{8})-(\d{6})", p.name)
        if not m:
            continue
        try:
            start = datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S")
        except ValueError:
            continue
        # launcher dir ts is local KST; trace ts is UTC — compare loosely
        delta = abs((ts.replace(tzinfo=None) - (start - timedelta(hours=9))).total_seconds())
        if delta < 6 * 3600 and (best is None or delta < best[0]):
            best = (delta, p.name)
    return best[1] if best else (dirs[0].name if dirs else None)


def build_lines(trace, events, window_hours, total_events, total_traces):
    d, first_ts = summarize(trace, events)
    prov = ",".join(d["providers"]) if d["providers"] else "na"
    raw_v = d["rawCount"] if d["rawCount"] is not None else d["outCount"]
    raw = str(raw_v) if raw_v is not None else "na"
    aft = str(d["afterFilter"]) if d["afterFilter"] is not None else "na"
    tout = "na" if d["timeout"] is None else ("true" if d["timeout"] else "false")
    held = "true" if (d["hardGuard"] or d["action"] == "HOLD") else "false"
    hosts = d["hosts"][:5]
    src = " ".join(f"W{i+1}:{h}" for i, h in enumerate(hosts)) if hosts else "(none recorded)"
    # SUMMARY
    if d["action"] == "HOLD" or d["hardGuard"]:
        summary = f"보류/가드 발동 | action={d['action']} reasonCode={d['reasonCode']}"
    elif d["promotedCount"] not in ("na", None) and str(d["promotedCount"]) == "0" \
            and d["candidateCount"] not in ("na", "0", None):
        why = d["disabledReason"] or "citation gate 미달"
        summary = f"evidence 후보 {d['candidateCount']}건 승격 0 | {why}"
    elif d["officialOnly"] == "true" and raw != "na" and raw != "0" and aft == "0":
        summary = f"검색 후보 {raw}건 있으나 officialOnly+minCitations={d['minCitations']}로 프롬프트 전 탈락"
    elif d["timeout"] is True:
        summary = f"검색 provider timeout | {prov}"
    else:
        summary = f"blocked 신호 없음 | action={d['action']} failReason={d['failReason']}"
    lines = [
        f"[WINDOW] trace={trace} windowHours={window_hours} events={len(events)} tracesInWindow={total_traces}",
        f"[REQUEST] reqHash:{trace} ts:{first_ts.isoformat() if first_ts else 'na'} "
        f"queryLen:{d['queryLen']} queryHash:{d['queryHash']} "
        f"reqModel:{d['reqModel']} respModel:{d['respModel']}",
        f"[RETRIEVE] provider:{prov} rawCount:{raw} afterFilterCount:{aft} timeout:{tout}",
        f"[PLAN/GATES] officialOnly:{d['officialOnly']} minCitations:{d['minCitations']} "
        f"highRisk:{d['highRisk']} candidateCount:{d['candidateCount']} promotedCount:{d['promotedCount']}",
        f"[VERIFIER] judgeModel:{d['judgeModel']} failSoft:{d['failSoft']} "
        f"outcomeKnown:{d['outcomeKnown']} failReason:{d['failReason']}",
        f"[HOLD_ACTION] hardGuardHeld:{held} action:{d['action']} reasonCode:{d['reasonCode']}",
        f"[SOURCES] {src}",
        f"[SUMMARY] {summary}",
    ]
    run = launcher_run_for(first_ts)
    if run:
        lines.append(f"[RUNTIME] launcherRun={run}")
    return lines


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description="dot 10-line runtime evidence feed (read-only)")
    sel = ap.add_mutually_exclusive_group()
    sel.add_argument("--latest", action="store_true")
    sel.add_argument("--trace")
    sel.add_argument("--request")
    ap.add_argument("--since-hours", type=float, default=float(os.environ.get("DOT_FEED_SINCE_HOURS", DEFAULT_SINCE_HOURS)))
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    path = events_path()
    if not path.is_file():
        print(f"no events file: {path}", file=sys.stderr)
        return EXIT_NO_DATA

    rows = collect_window(path, args.since_hours)
    used = args.since_hours
    if not rows and used < EXPAND_SINCE_HOURS:
        used = EXPAND_SINCE_HOURS
        rows = collect_window(path, used)
    groups = group_traces(rows)
    if not groups:
        print("no trace events in window", file=sys.stderr)
        return EXIT_NO_DATA

    if args.trace or args.request:
        key = find_trace(groups, args.trace or args.request)
        if key is None:
            print(f"trace not found in window: {args.trace or args.request}", file=sys.stderr)
            return EXIT_NO_DATA
    else:
        key = max(groups, key=lambda k: groups[k][-1][0])

    lines = build_lines(key, groups[key], used, len(rows), len(groups))
    if args.json:
        print(json.dumps({"schemaVersion": "awx.dot-evidence-feed.v1",
                          "trace": key, "lines": lines}, ensure_ascii=True))
    else:
        for ln in lines:
            txt, _ = redact_text(ln)
            print(txt)
    return EXIT_OK


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001 - fail-soft: never crash on log shape
        print(f"internal error: {exc}", file=sys.stderr)
        sys.exit(EXIT_ERR)
