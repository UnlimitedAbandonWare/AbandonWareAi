#!/usr/bin/env python3
"""agent_cost_meter.py — Codex 세션·훅 비용 측정기 (읽기 전용, 오프라인).

측정 계약 v1 (PASTE_CODEX_guardrail_overhead_reduction_20261010 §3):
  - 1 MB = 1,000,000 bytes (10진)
  - p95 = nearest-rank (sort 후 ceil(0.95*N)); N=0 이면 NOT_AVAILABLE
  - 훅 계측은 callId+event+stage로 중복 제거. Python 단계와 PS wrapper 단계를
    합산하지 않고 별도 이름으로 보고한다.
  - 짝 없는/과거 명령 시간은 NOT_AVAILABLE. 추정·토큰 환산 금지.

입력: Codex 세션 JSONL (item_completed 의 CommandExecution 등) +
      var/agent-work-guard/hook-trace.jsonl.
출력: --json (stdout) 또는 --out <path> (원자 기록).
비교: --compare <before.json> <after.json> → 동일 스키마 delta.

명령별 실행 시간은 item.duration {secs,nanos} 가 1차 출처다
(이전 타임스탬프 파싱 실패의 수정). duration 없으면 NOT_AVAILABLE로 센다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.agent-cost-meter.v1"
MB = 1_000_000

# 명령 텍스트·인자 안의 저장소 상대경로 후보
PATH_RE = re.compile(
    r"(?i)(?:^|[\s'\"(=])((?:\.agents|main|src|scripts|docs|data|agent-prompts|"
    r"__patch_drop__|configs|var|app|frontend)[/\\]"
    r"[A-Za-z0-9_.\-/\\]+\.[A-Za-z0-9]{1,8})"
)
# 루트 단일 파일 (AGENTS.md 등)
ROOT_FILE_RE = re.compile(r"(?i)(?<![\w./\\])(AGENTS\.md|AGENTS\.override\.md)\b")
SESSION_NAME_RE = re.compile(r"rollout-(\d{4})-(\d{2})-(\d{2})T")

ITEM_TYPES = ("CommandExecution", "McpToolCall", "FileChange",
              "CollabAgentToolCall")


def utcnow() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_ts(value: str):
    """ISO 타임스탬프 → epoch float. 실패 시 None."""
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(str(value).strip().replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp()
    except (ValueError, TypeError):
        return None


def iso_arg(value: str, name: str):
    ts = parse_ts(value)
    if ts is None:
        raise SystemExit("bad-%s: %r (ISO 예: 2026-10-10T00:00:00Z)" % (name, value))
    return ts


def p95_nearest_rank(values):
    """계약 p95: sort 후 ceil(0.95*N). N=0 → None(NOT_AVAILABLE)."""
    if not values:
        return None
    s = sorted(values)
    return s[max(0, math.ceil(0.95 * len(s)) - 1)]


def stats_block(values):
    """N/sum/p95/max; p95·max는 N=0이면 'NOT_AVAILABLE'."""
    n = len(values)
    return {
        "n": n,
        "sumMs": round(sum(values), 1) if n else 0.0,
        "p95Ms": (p95_nearest_rank(values) if n else "NOT_AVAILABLE"),
        "maxMs": (max(values) if n else "NOT_AVAILABLE"),
    }


def sha12(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def norm_path(raw: str) -> str:
    return raw.strip().strip("'\"").replace("\\", "/")


def cmd_text(command) -> str:
    """CommandExecution.command 배열 → 실제 명령 문자열."""
    if isinstance(command, str):
        return command
    if isinstance(command, list) and command:
        parts = [str(c) for c in command]
        # [exe, -Command, 실제명령] 형태면 마지막 조각이 본문
        if len(parts) >= 3 and parts[-2].lower() in ("-command", "-c"):
            return parts[-1]
        return " ".join(parts)
    return ""


def output_bytes_of(item: dict) -> int:
    total = 0
    for key in ("aggregated_output", "formatted_output", "stdout", "stderr"):
        val = item.get(key)
        if isinstance(val, str) and val:
            if key == "aggregated_output" or not item.get("aggregated_output"):
                total += len(val.encode("utf-8"))
    return total


def duration_ms(item: dict):
    d = item.get("duration")
    if isinstance(d, dict):
        try:
            return int(d.get("secs", 0)) * 1000 + int(d.get("nanos", 0)) / 1e6
        except (TypeError, ValueError):
            return None
    if isinstance(d, (int, float)):
        return float(d)
    return None


def target_bucket(path: str) -> str:
    """경로 → 대상 버킷. SKILL.md·AGENTS.md는 묶고 나머지는 정규화 경로."""
    p = norm_path(path)
    base = p.rsplit("/", 1)[-1]
    if base.upper() in ("SKILL.MD", "AGENTS.MD", "AGENTS.OVERRIDE.MD"):
        return base.upper().replace("AGENTS.OVERRIDE.MD", "AGENTS.override.md")
    return p


def iter_session_files(sessions_dir: Path, since_ts, until_ts):
    """rollout-날짜 프리필터(±1일 여유) 후 yield."""
    day_s = day_u = None
    if since_ts is not None:
        day_s = datetime.fromtimestamp(since_ts - 86400, timezone.utc).date()
    if until_ts is not None:
        day_u = datetime.fromtimestamp(until_ts + 86400, timezone.utc).date()
    for root, _dirs, files in os.walk(sessions_dir):
        for name in files:
            if not name.endswith(".jsonl"):
                continue
            m = SESSION_NAME_RE.search(name)
            if m:
                try:
                    from datetime import date
                    fd = date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
                    if day_s and fd < day_s:
                        continue
                    if day_u and fd > day_u:
                        continue
                except ValueError:
                    pass
            yield Path(root) / name


def scan_sessions(sessions_dir: Path, since_ts, until_ts, top_n: int) -> dict:
    sessions_seen = set()
    sessions_in_window = set()
    records = 0
    item_counts = {}
    tool_call_names = {}
    cmd_exec = 0
    durations = []
    duration_missing = 0
    status_counts = {}
    # target → [calls, outputBytes]
    targets = {}
    errors = []

    for path in iter_session_files(sessions_dir, since_ts, until_ts):
        sessions_seen.add(str(path))
        in_window = False
        try:
            fh = path.open("r", encoding="utf-8", errors="replace")
        except OSError as exc:
            errors.append("%s: %s" % (path.name, type(exc).__name__))
            continue
        with fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except ValueError:
                    continue
                ts = parse_ts(rec.get("timestamp"))
                if since_ts is not None and (ts is None or ts < since_ts):
                    continue
                if until_ts is not None and ts is not None and ts > until_ts:
                    continue
                in_window = True
                records += 1
                payload = rec.get("payload") or {}
                ptype = payload.get("type")
                if ptype == "item_completed":
                    item = payload.get("item") or {}
                    itype = str(item.get("type") or "unknown")
                    item_counts[itype] = item_counts.get(itype, 0) + 1
                    status = str(item.get("status") or "")
                    if status:
                        status_counts[status] = status_counts.get(status, 0) + 1
                    if itype == "CommandExecution":
                        cmd_exec += 1
                        d = duration_ms(item)
                        if d is None:
                            duration_missing += 1
                        else:
                            durations.append(d)
                        obytes = output_bytes_of(item)
                        text = cmd_text(item.get("command"))
                        seen_t = set()
                        for raw in PATH_RE.findall(text):
                            seen_t.add(target_bucket(raw))
                        for raw in ROOT_FILE_RE.findall(text):
                            seen_t.add(target_bucket(raw))
                        for t in seen_t:
                            row = targets.setdefault(t, [0, 0])
                            row[0] += 1
                            row[1] += obytes
                    elif itype == "FileChange":
                        fc_paths = item.get("paths") or []
                        if isinstance(fc_paths, str):
                            fc_paths = [fc_paths]
                        for raw in fc_paths:
                            t = target_bucket(str(raw))
                            row = targets.setdefault(t, [0, 0])
                            row[0] += 1
                elif rec.get("type") == "response_item" and ptype in (
                        "function_call", "custom_tool_call"):
                    name = str(payload.get("name") or "?")
                    tool_call_names[name] = tool_call_names.get(name, 0) + 1
        if in_window:
            sessions_in_window.add(str(path))

    top = sorted(targets.items(), key=lambda kv: (-kv[1][0], -kv[1][1], kv[0]))
    return {
        "sessionsDir": str(sessions_dir),
        "sessionsScanned": len(sessions_seen),
        "sessionsInWindow": len(sessions_in_window),
        "recordsInWindow": records,
        "toolCalls": {
            "commandExecutions": cmd_exec,
            "itemCompletedByType": dict(sorted(item_counts.items())),
            "responseItemToolCallsByName": dict(sorted(tool_call_names.items())),
            "statusByValue": dict(sorted(status_counts.items())),
        },
        "commandTimingMs": {
            "source": "item.duration",
            **stats_block(durations),
            "missingDuration": duration_missing,
        },
        "targetsTop": [
            {"target": t, "calls": c, "outputBytes": b,
             "outputMB": round(b / MB, 4)}
            for t, (c, b) in top[:top_n]
        ],
        "targetsDistinct": len(targets),
        "note": ("CommandExecution 출력바이트는 해당 명령이 참조한 각 대상에 "
                 "전수 귀속 → 대상 합계 >= 전체 출력. 추정 없음."),
        "errors": errors[:20],
    }


def scan_hook_trace(trace_path: Path, since_ts, until_ts) -> dict:
    py_exit_ms = []          # Python 단계 elapsedMs (event 행)
    ps_exit_ms = []          # PS wrapper 단계 elapsedMs (wrapper 행)
    py_dedup = set()
    ps_dedup = 0
    blocks = {}
    missing_pair = 0
    rows = 0
    if not trace_path.is_file():
        return {"present": False, "path": str(trace_path)}
    with trace_path.open("r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except ValueError:
                continue
            ts = parse_ts(rec.get("at"))
            if since_ts is not None and (ts is None or ts < since_ts):
                continue
            if until_ts is not None and ts is not None and ts > until_ts:
                continue
            rows += 1
            if rec.get("wrapper"):
                if rec.get("phase") == "exit" and rec.get("elapsedMs") is not None:
                    ps_exit_ms.append(float(rec["elapsedMs"]))
                    ps_dedup += 1
                continue
            key = (str(rec.get("toolUseId") or ""), str(rec.get("event") or ""),
                   str(rec.get("phase") or ""))
            if key in py_dedup:
                continue
            py_dedup.add(key)
            if rec.get("phase") == "exit" and rec.get("elapsedMs") is not None:
                py_exit_ms.append(float(rec["elapsedMs"]))
            if (rec.get("phase") == "exit"
                    and (str(rec.get("decision") or "") == "block"
                         or rec.get("exit") == 2)):
                tid = str(rec.get("toolUseId") or "")
                blocks[tid] = {
                    "at": rec.get("at"),
                    "event": rec.get("event"),
                    "toolName": rec.get("toolName"),
                    "toolUseId": tid,
                    "toolUseIdSha12": sha12(tid),
                }
    return {
        "present": True,
        "path": str(trace_path),
        "rowsInWindow": rows,
        "pythonElapsed": stats_block(py_exit_ms),
        "psWrapperElapsed": {**stats_block(ps_exit_ms),
                            "note": "PS spawn 비용 미포함; Python과 합산 금지"},
        "pythonDedupRows": len(py_dedup),
        "psExitRows": ps_dedup,
        "missingPair": missing_pair,
        "blocks": sorted(blocks.values(), key=lambda b: b["at"] or ""),
        "blockCount": len(blocks),
    }


def compare(before: dict, after: dict, top_n: int) -> dict:
    def num(d, *ks):
        cur = d or {}
        for k in ks:
            if not isinstance(cur, dict):
                return None
            cur = cur.get(k)
        return cur if isinstance(cur, (int, float)) else None

    def row(name, b, a):
        delta = (a - b) if (b is not None and a is not None) else None
        return {"metric": name, "before": b, "after": a, "delta": delta}

    rows = [
        row("sessionsInWindow", num(before, "sessions", "sessionsInWindow"),
            num(after, "sessions", "sessionsInWindow")),
        row("commandExecutions",
            num(before, "sessions", "toolCalls", "commandExecutions"),
            num(after, "sessions", "toolCalls", "commandExecutions")),
        row("recordsInWindow", num(before, "sessions", "recordsInWindow"),
            num(after, "sessions", "recordsInWindow")),
        row("cmdTimeSumMs",
            num(before, "sessions", "commandTimingMs", "sumMs"),
            num(after, "sessions", "commandTimingMs", "sumMs")),
        row("hookPythonSumMs",
            num(before, "hookTrace", "pythonElapsed", "sumMs"),
            num(after, "hookTrace", "pythonElapsed", "sumMs")),
        row("hookPythonN",
            num(before, "hookTrace", "pythonElapsed", "n"),
            num(after, "hookTrace", "pythonElapsed", "n")),
        row("hookPsSumMs",
            num(before, "hookTrace", "psWrapperElapsed", "sumMs"),
            num(after, "hookTrace", "psWrapperElapsed", "sumMs")),
        row("blockCount", num(before, "hookTrace", "blockCount"),
            num(after, "hookTrace", "blockCount")),
    ]
    bt = {t["target"]: t for t in (before.get("sessions") or {}).get("targetsTop") or []}
    at = {t["target"]: t for t in (after.get("sessions") or {}).get("targetsTop") or []}
    target_rows = []
    for name in sorted(set(bt) | set(at)):
        b = bt.get(name) or {}
        a = at.get(name) or {}
        target_rows.append({
            "target": name,
            "callsBefore": b.get("calls", 0), "callsAfter": a.get("calls", 0),
            "callsDelta": a.get("calls", 0) - b.get("calls", 0),
            "outputMBBefore": b.get("outputMB", 0.0),
            "outputMBAfter": a.get("outputMB", 0.0),
        })
    target_rows.sort(key=lambda r: -abs(r["callsDelta"]))
    return {"schemaVersion": "awx.agent-cost-meter.compare.v1",
            "metrics": rows, "targets": target_rows[:top_n]}


def atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def build_report(root: Path, sessions_dir: Path, trace: Path,
                 since, until, top_n: int) -> dict:
    return {
        "schemaVersion": SCHEMA,
        "generatedAt": utcnow(),
        "root": str(root),
        "window": {
            "since": datetime.fromtimestamp(since, timezone.utc).isoformat()
                     if since is not None else None,
            "until": datetime.fromtimestamp(until, timezone.utc).isoformat()
                    if until is not None else None,
        },
        "contract": {"mbBytes": MB, "p95": "nearest-rank ceil(0.95*N)",
                     "hookDedup": "toolUseId+event+phase", "estimates": "none"},
        "sessions": scan_sessions(sessions_dir, since, until, top_n),
        "hookTrace": scan_hook_trace(trace, since, until),
    }


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--root", default=".")
    p.add_argument("--sessions-dir",
                   default=os.path.join(os.environ.get("USERPROFILE") or "",
                                        ".codex", "sessions"))
    p.add_argument("--hook-trace",
                   default=os.path.join("var", "agent-work-guard",
                                        "hook-trace.jsonl"))
    p.add_argument("--since", default=None, help="ISO, 예 2026-10-08T00:00:00Z")
    p.add_argument("--until", default=None)
    p.add_argument("--top", type=int, default=25)
    p.add_argument("--json", action="store_true")
    p.add_argument("--out", default=None, help="결과 JSON 저장 경로(원자 기록)")
    p.add_argument("--compare", nargs=2, metavar=("BEFORE.json", "AFTER.json"))
    a = p.parse_args(argv)

    if a.compare:
        try:
            before = json.loads(Path(a.compare[0]).read_text(encoding="utf-8"))
            after = json.loads(Path(a.compare[1]).read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            print(json.dumps({"status": "error", "reason": "compare-load",
                              "detail": str(exc)[:200]}))
            return 2
        print(json.dumps(compare(before, after, a.top), ensure_ascii=True,
                         indent=2))
        return 0

    root = Path(a.root).resolve()
    since = iso_arg(a.since, "since") if a.since else None
    until = iso_arg(a.until, "until") if a.until else None
    report = build_report(root, Path(a.sessions_dir),
                          root / a.hook_trace, since, until, a.top)
    text = json.dumps(report, ensure_ascii=True, indent=2)
    if a.out:
        atomic_write(Path(a.out), text + "\n")
    if a.json or not a.out:
        print(text)
    else:
        print(json.dumps({"status": "written", "out": a.out}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
