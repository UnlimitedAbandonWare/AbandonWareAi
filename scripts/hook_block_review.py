#!/usr/bin/env python3
"""hook_block_review.py — agent_work_guard 차단 사건 정오 판정 + 읽기 전용 후보 분석.

입력:
  --hook-trace  var/agent-work-guard/hook-trace.jsonl (live 블록 자동 수집)
  --ids-file    추적 파일이 회전돼 사라진 과거 사건의 {"at","toolUseId"} 목록
  --sessions-dir Codex 세션 JSONL 루트 (callId → 명령 상관)
  --since/--until ISO 창 (기본: 당일 전체)
  --out         hook-blocks.md 산출물 경로
  --json        JSON 요약도 출력

판정 계약 (WP9, PASTE_CODEX_guardrail_overhead_reduction_20261010):
  true_block | false_positive | unknown. 이후 성공만으로 오탐 승격 금지.
  raw command 대신 구조(cmdlet)·reasonCode·경로 sha12 만 저장한다.
규칙 귀속은 scripts/agent_work_guard.py 의 실제 문자열 위치(file:line)로 준다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
from datetime import datetime, timezone, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_work_guard as g  # noqa: E402  같은 판정 규칙 재사용

SCHEMA = "awx.hook-block-review.v1"
SNIP = 120

REASONS = ["path-does-not-exist", "doubled-src-prefix", "same-target-retry"]


def parse_ts(v):
    if not v:
        return None
    try:
        dt = datetime.fromisoformat(str(v).strip().replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp()
    except (ValueError, TypeError):
        return None


def sha12(t: str) -> str:
    return hashlib.sha256(t.encode("utf-8")).hexdigest()[:12]


def cmd_text(command) -> str:
    if isinstance(command, str):
        return command
    if isinstance(command, list) and command:
        parts = [str(c) for c in command]
        if len(parts) >= 3 and parts[-2].lower() in ("-command", "-c"):
            return parts[-1]
        return " ".join(parts)
    return ""


# ---------- 읽기 전용 판정기 초안 ----------

WRITE_TOKENS = re.compile(
    r"(?i)(?<![\w./\\-])("
    r"Set-[A-Za-z]+|Add-Content|Out-File|New-Item|Remove-Item|Rename-Item|"
    r"Move-Item|Copy-Item|Clear-Content|Invoke-Expression|iex|"
    r"del|rm|mv|cp|md|mkdir|tee|kill|taskkill|attrib|icacls|takeown|"
    r"reg\s+add|Format-Volume|Clear-Disk|netsh|sc\.exe|schtasks|"
    r"Register-[A-Za-z]+|Unregister-[A-Za-z]+|Stop-Process|"
    r"Restart-[A-Za-z]+|Start-[A-Za-z]+|Remove-[A-Za-z]+|"
    r"New-[A-Za-z]+|Rename-[A-Za-z]+)\b")
EXEC_EXT_RE = re.compile(r"(?i)\.(ps1|bat|cmd|exe|py|js|cjs|mjs|sh)\b")
GIT_WRITE_SUB = {
    "add", "commit", "push", "pull", "fetch", "merge", "rebase", "reset",
    "checkout", "restore", "stash", "clean", "rm", "mv", "tag", "apply",
    "am", "cherry-pick", "revert", "bisect", "remote", "config", "branch -d",
    "branch -D", "update-index", "write-tree", "gc", "prune", "init", "clone",
}
GIT_READ_SUB = {
    "status", "diff", "log", "show", "branch", "describe", "rev-parse",
    "ls-files", "ls-tree", "blame", "shortlog", "staged", "diff-index",
    "name-only", "cat-file", "rev-list", "for-each-ref", "var", "version",
}
READ_CMDLETS = {
    "get-content", "gc", "get-item", "get-childitem", "gci", "ls", "dir",
    "select-string", "sls", "rg", "grep", "findstr", "cat", "type", "more",
    "less", "get-filehash", "test-path", "resolve-path", "get-location",
    "pwd", "write-output", "echo", "write-host", "write-warning",
    "format-table", "format-list", "format-wide", "select-object", "select",
    "where-object", "where", "sort-object", "sort", "measure-object",
    "measure", "group-object", "group", "compare-object", "compare", "diff",
    "out-null", "out-string", "get-date", "get-process", "get-service",
    "get-help", "get-member", "get-childitem", "join-path", "split-path",
    "get-itemproperty", "get-itempropertyvalue", "test-json",
}
READ_BARE = {
    "git", "python", "python3", "py", "node", "ollama", "code", "where",
    "winget", "dotnet", "java", "javac",
}
SAFE_SUB = {
    "python": {"--help", "-h", "--version", "-V", "-VV"},
    "python3": {"--help", "-h", "--version", "-V", "-VV"},
    "py": {"--help", "-h", "--version", "-V", "-VV"},
    "node": {"--help", "-h", "--version", "-v"},
    "java": {"-version", "--version", "-help"},
    "javac": {"-version", "--version"},
    "ollama": {"ls", "list", "ps", "--help", "-h"},
    "dotnet": {"--version", "--list-sdks", "--list-runtimes", "-h", "--help"},
    "winget": {"list", "show", "search", "--version"},
    "where": {"*"},
}
HELPERS_RE = re.compile(
    r"^\s*(\$env:[A-Za-z_][\w]*|\$PWD|\$PSVersionTable|\[math\]|"
    r"\[IO\.File\]::(ReadAllText|ReadAllLines|Exists)|"
    r"\[IO\.Directory\]::(GetFiles|GetDirectories|Exists)|"
    r"\[IO\.Path\]::|\[datetime\]|\[guid\])")


def _first_token(seg: str) -> str:
    seg = seg.strip()
    m = re.match(r"^['\"]?([A-Za-z0-9_.\-:\\]+)['\"]?", seg)
    return (m.group(1) if m else "").lower().rstrip(":.,")


def segment_split(cmd: str) -> list:
    # 파이프·세미콜론·&&·||·개행 단위 분해 (따옴표 무시 단순형)
    parts = re.split(r"[;|\n]|\&\&|\|\|", cmd or "")
    return [s.strip() for s in parts if s and s.strip()]


def classify_segment(seg: str) -> str:
    """allow | reject | unknown — 단일 파이프/문장 조각."""
    s = seg.strip()
    if not s:
        return "allow"
    if re.search(r">(?![=>])|>>", s):
        return "reject"                      # 리다이렉트 쓰기
    if WRITE_TOKENS.search(s):
        return "reject"
    if HELPERS_RE.match(s):
        return "allow"
    tok = _first_token(s)
    base = tok.rsplit("\\", 1)[-1].rsplit("/", 1)[-1]
    if EXEC_EXT_RE.search(base):
        return "reject"                      # 스크립트·바이너리 실행
    if base == "git":
        rest = s[s.find(tok) + len(tok):].strip()
        sub = rest.split(None, 1)[0].lower() if rest else ""
        if sub in GIT_WRITE_SUB:
            return "reject"
        if sub in GIT_READ_SUB or not sub:
            return "allow"
        return "unknown"
    if base in READ_CMDLETS:
        return "allow"
    if base in SAFE_SUB:
        rest = s[s.find(tok) + len(tok):].strip() if s.find(tok) >= 0 else ""
        first = rest.split(None, 1)[0].strip() if rest else ""
        safe = SAFE_SUB[base]
        if "*" in safe or first in safe:
            return "allow"
        return "reject"                      # 인터프리터 본체 실행
    if re.match(r"^(pip|pip3|npm|npx|yarn|pnpm|gradle|gradlew|mvn|cargo|"
                r"go|make|cmake|docker|kubectl)\b", base):
        return "reject"                      # 패키지/빌드/배포 실행자
    if re.match(r"^(rg|grep|findstr)\b", base):
        return "allow"
    if base in ("powershell", "pwsh", "cmd", "cmd.exe", "bash", "sh"):
        return "reject"                      # 중첩 쉘 = 임의 실행
    return "unknown"


def is_read_only_command(cmd: str) -> dict:
    """전체 명령이 입증된 읽기 전용인지. 어떤 조각이라도 reject면 write-mixed."""
    segs = segment_split(cmd)
    verdicts = [(s, classify_segment(s)) for s in segs]
    if any(v == "reject" for _, v in verdicts):
        return {"verdict": "write-mixed",
                "reasons": [s for s, v in verdicts if v == "reject"][:3]}
    if any(v == "unknown" for _, v in verdicts):
        return {"verdict": "unknown",
                "reasons": [s for s, v in verdicts if v == "unknown"][:3]}
    return {"verdict": "read-only", "reasons": []}


# ---------- 차단 사건 수집 ----------

def load_trace_blocks(trace: Path, since, until) -> list:
    out = {}
    if not trace.is_file():
        return []
    for line in trace.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            r = json.loads(line)
        except ValueError:
            continue
        if r.get("wrapper"):
            continue
        ts = parse_ts(r.get("at"))
        if since is not None and (ts is None or ts < since):
            continue
        if until is not None and ts is not None and ts > until:
            continue
        if (r.get("phase") == "exit"
                and (str(r.get("decision") or "") == "block"
                     or r.get("exit") == 2)):
            tid = str(r.get("toolUseId") or "")
            out[tid] = {"at": r.get("at"), "event": r.get("event"),
                        "toolName": r.get("toolName"), "toolUseId": tid,
                        "source": "hook-trace"}
    return sorted(out.values(), key=lambda e: e["at"] or "")


def load_ids_file(path: Path) -> list:
    """회전으로 사라진 사건: [{at, toolUseId}] 또는 sha12+힌트."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    rows = data if isinstance(data, list) else data.get("events", [])
    out = []
    for r in rows:
        if isinstance(r, dict) and r.get("toolUseId"):
            out.append({"at": r.get("at"), "event": r.get("event", "PreToolUse"),
                        "toolName": r.get("toolName"), "toolUseId": r["toolUseId"],
                        "source": "ids-file"})
    return out


# ---------- 세션 상관 ----------

def iter_jsonl(d: Path):
    for root, _dirs, files in os.walk(d):
        for name in files:
            if name.endswith(".jsonl"):
                yield Path(root) / name


def find_call_items(sessions_dir: Path, ids: set, since, until) -> dict:
    """toolUseId → {session, ordinal, item, output, parsed...}"""
    found = {}
    prefix = {i.split("#", 1)[0] for i in ids}
    for path in iter_jsonl(sessions_dir):
        try:
            fh = path.open("r", encoding="utf-8", errors="replace")
        except OSError:
            continue
        with fh:
            for line in fh:
                if not any(t in line for t in ids) and \
                        not any(t in line for t in prefix):
                    continue
                try:
                    rec = json.loads(line)
                except ValueError:
                    continue
                payload = rec.get("payload") or {}
                cand = []
                if payload.get("type") == "item_completed":
                    cand.append(payload.get("item") or {})
                elif payload.get("type") in ("custom_tool_call",
                                             "function_call"):
                    cand.append(payload)
                for it in cand:
                    iid = str(it.get("id") or "")
                    cid = str(it.get("call_id") or it.get("callId") or "")
                    hit = iid if iid in ids else (
                        cid if cid in ids else None)
                    if hit is None and iid.split("#", 1)[0] in prefix:
                        hit = iid
                    if hit is None and cid.split("#", 1)[0] in prefix:
                        hit = cid
                    if hit:
                        found[hit] = {
                            "session": path.name,
                            "ordinal": rec.get("ordinal"),
                            "at": rec.get("timestamp"),
                            "item": it,
                        }
    return found


def later_success(items_file: Path, blocked_at_ordinal, paths: set) -> bool:
    """같은 세션 후속 CommandExecution 중 동일 대상 성공 여부."""
    if not items_file or not items_file.is_file():
        return False
    ok = False
    for line in items_file.read_text(encoding="utf-8",
                                     errors="replace").splitlines():
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        if (rec.get("ordinal") or 0) <= (blocked_at_ordinal or 0):
            continue
        payload = rec.get("payload") or {}
        if payload.get("type") != "item_completed":
            continue
        it = payload.get("item") or {}
        if it.get("type") != "CommandExecution":
            continue
        if it.get("status") == "completed" or it.get("exit_code") == 0:
            t = cmd_text(it.get("command"))
            theirs = {p_.casefold() for p_ in g.files_in_cmd(t)}
            if paths & theirs:
                ok = True
                break
    return ok


def rule_lines(guard_path: Path) -> dict:
    """reason 문자열이 실제로 쓰인 file:line 목록."""
    out = {r: [] for r in REASONS}
    try:
        lines = guard_path.read_text(encoding="utf-8",
                                     errors="replace").splitlines()
    except OSError:
        return out
    for i, l in enumerate(lines, 1):
        for r in REASONS:
            if '"%s"' % r in l or "'%s'" % r in l:
                out[r].append("%s:%d" % (guard_path.name, i))
    return out


def command_shape(cmd: str) -> dict:
    """원문 대신 구조만: 세그먼트 cmdlet/경로 수/쓰기 신호."""
    segs = segment_split(cmd)
    toks = [_first_token(s) for s in segs]
    files = g.files_in_cmd(cmd)
    return {
        "segments": len(segs),
        "tokens": [t for t in toks if t][:12],
        "fileRefs": len(files),
        "fileSha12": [sha12(p) for p in files[:8]],
        "intent": g.cmd_intent(cmd),
        "redirect": bool(re.search(r">(?![=>])", cmd)),
        "hasWriteToken": bool(WRITE_TOKENS.search(cmd)),
    }


def review(root: Path, guard_src: Path, sessions_dir: Path, events: list,
           ledger_copy: Path, since, until) -> list:
    ids = {e["toolUseId"] for e in events}
    items = find_call_items(sessions_dir, ids, since, until)
    rules = rule_lines(guard_src)
    out = []
    for e in events:
        tid = e["toolUseId"]
        ev = {"at": e.get("at"), "toolUseId": tid,
              "toolUseIdSha12": sha12(tid),
              "toolName": e.get("toolName"), "event": e.get("event"),
              "source": e.get("source")}
        rec = items.get(tid)
        if not rec:
            # call_xxx#yyy 형태면 앞부분으로 재검색
            base = tid.split("#", 1)[0]
            rec = items.get(base) or next(
                (v for k, v in items.items()
                 if str(k).split("#", 1)[0] == base), None)
        ev["verdict"] = "unknown"
        ev["evidence"] = {}
        if not rec:
            ev["evidence"]["note"] = "session item not found"
            out.append(ev)
            continue
        item = rec["item"]
        cmd = cmd_text(item.get("command") or item.get("input"))
        ev["session"] = rec["session"]
        ev["ordinal"] = rec.get("ordinal")
        ev["itemStatus"] = item.get("status")
        ev["itemExit"] = item.get("exit_code")
        shape = command_shape(cmd)
        ev["shape"] = shape
        # 재판정(ledger 사본): 현재 시점 기준 — 당시 조건과 다를 수 있음을 명시
        try:
            replay = g.verdict_for_cmd(root, cmd, str(ledger_copy),
                                       "codex", "root")
        except Exception as exc:  # replay 실패도 판정 불능 증거로만
            replay = {"decision": "replay-error",
                      "reason": type(exc).__name__}
        ev["replay"] = {"decision": replay.get("decision"),
                        "reason": replay.get("reason"),
                        "intent": replay.get("intent")}
        reason = replay.get("reason") or ""
        ev["ruleRef"] = rules.get(reason, [])
        paths = {p.casefold() for p in g.files_in_cmd(cmd)}
        ev["existsNow"] = {p: (root / p).exists()
                           for p in g.files_in_cmd(cmd)[:8]}
        # 당시 실패 신호: 차단 직후 동일 세션의 실패 원인
        ev["laterSuccess"] = later_success(
            sessions_dir / rec["session"], rec.get("ordinal"), paths)
        out_text = ""
        for k in ("aggregated_output", "stdout", "stderr"):
            v = item.get(k)
            if isinstance(v, str):
                out_text += v[:4000]
        ev["failCause"] = g.cause_from_output(out_text) or None
        # 판정
        if replay.get("decision") == "block":
            ev["verdict"] = "true_block"
            ev["evidence"]["why"] = ("replay on current tree still blocks: %s"
                                     % reason)
        elif ev["failCause"] in ("missing", "parser", "nonzero"):
            ev["verdict"] = "true_block"
            ev["evidence"]["why"] = ("recorded output carries failure "
                                     "signature: %s" % ev["failCause"])
        else:
            ev["verdict"] = "unknown"
            ev["evidence"]["why"] = ("replay=%s; at-time condition not "
                                     "recoverable" % replay.get("decision"))
        out.append(ev)
    return out


# ---------- 읽기 전용 후보 비율 ----------

def readonly_candidates(sessions_dir: Path, since, until) -> dict:
    counts = {"read-only": 0, "write-mixed": 0, "unknown": 0}
    total = 0
    examples = {"read-only": [], "write-mixed": [], "unknown": []}
    for path in iter_jsonl(sessions_dir):
        try:
            fh = path.open("r", encoding="utf-8", errors="replace")
        except OSError:
            continue
        with fh:
            for line in fh:
                if "CommandExecution" not in line:
                    continue
                try:
                    rec = json.loads(line)
                except ValueError:
                    continue
                ts = parse_ts(rec.get("timestamp"))
                if since is not None and (ts is None or ts < since):
                    continue
                if until is not None and ts is not None and ts > until:
                    continue
                payload = rec.get("payload") or {}
                if payload.get("type") != "item_completed":
                    continue
                it = payload.get("item") or {}
                if it.get("type") != "CommandExecution":
                    continue
                total += 1
                v = is_read_only_command(cmd_text(it.get("command")))
                counts[v["verdict"]] += 1
                if len(examples[v["verdict"]]) < 5:
                    examples[v["verdict"]].append(
                        cmd_text(it.get("command"))[:SNIP])
    return {"totalCommandExecutions": total, "counts": counts,
            "skipEstimate": (round(counts["read-only"] / total, 4)
                             if total else None),
            "examples": examples}


def render_md(result: dict) -> str:
    lines = [
        "# hook-blocks.md — 차단 사건 정오 판정 (Devin assist draft)",
        "",
        "- schema: %s" % SCHEMA,
        "- generated: %s" % result["generatedAt"],
        "- 판정 계약: true_block|false_positive|unknown; 이후 성공만으로 "
        "오탐 승격 금지; raw command 대신 구조·sha12 저장",
        "- replay는 ledger 사본+현재 트리 기준 — 당시 조건과 다를 수 있음",
        "",
        "| # | UTC | toolUseId sha12 | tool | item status/exit | replay | 규칙 ref | 판정 |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for i, ev in enumerate(result["events"], 1):
        rule = ", ".join(ev.get("ruleRef") or ["—"])
        replay = "%s:%s" % ((ev.get("replay") or {}).get("decision"),
                            (ev.get("replay") or {}).get("reason") or "")
        lines.append(
            "| %d | %s | `%s` | %s | %s/%s | %s | %s | **%s** |" % (
                i, ev.get("at"), ev.get("toolUseIdSha12"),
                ev.get("toolName") or "?",
                ev.get("itemStatus") or "?", ev.get("itemExit")
                if ev.get("itemExit") is not None else "?",
                replay, rule, ev.get("verdict")))
    lines.append("")
    for i, ev in enumerate(result["events"], 1):
        sh = ev.get("shape") or {}
        lines += [
            "## 사건 %d — `%s`" % (i, ev.get("toolUseIdSha12")),
            "- session: %s ordinal=%s" % (ev.get("session"), ev.get("ordinal")),
            "- shape: segments=%s intent=%s fileRefs=%s redirect=%s "
            "writeToken=%s" % (sh.get("segments"), sh.get("intent"),
                                sh.get("fileRefs"), sh.get("redirect"),
                                sh.get("hasWriteToken")),
            "- tokens: %s" % (", ".join(sh.get("tokens") or [])),
            "- fileSha12: %s" % (", ".join(sh.get("fileSha12") or [])),
            "- existsNow: %s" % (ev.get("existsNow") or {}),
            "- laterSuccessOnSameTarget: %s" % ev.get("laterSuccess"),
            "- failCause: %s" % ev.get("failCause"),
            "- why: %s" % (ev.get("evidence") or {}).get("why", ""),
            "",
        ]
    ro = result.get("readOnlyScan") or {}
    if ro:
        lines += [
            "## 읽기 전용 후보 (hook 생략 검토용 — WP8 shadow)",
            "- 측정한 CommandExecution: %s" % ro.get("totalCommandExecutions"),
            "- read-only %s / write-mixed %s / unknown %s" % (
                ro["counts"]["read-only"], ro["counts"]["write-mixed"],
                ro["counts"]["unknown"]),
            "- skipEstimate(read-only 비율): %s" % ro.get("skipEstimate"),
            "- 주의: read-only 판정은 초안 분류 — 실제 훅 생략은 Codex가 "
            "fixture로 입증 후 적용",
            "",
        ]
    return "\n".join(lines)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--root", default=".")
    p.add_argument("--sessions-dir",
                   default=os.path.join(os.environ.get("USERPROFILE") or "",
                                        ".codex", "sessions"))
    p.add_argument("--hook-trace",
                   default=os.path.join("var", "agent-work-guard",
                                        "hook-trace.jsonl"))
    p.add_argument("--ids-file", default=None)
    p.add_argument("--since", default=None)
    p.add_argument("--until", default=None)
    p.add_argument("--out", default=None)
    p.add_argument("--json", action="store_true")
    p.add_argument("--no-readonly-scan", action="store_true")
    a = p.parse_args(argv)

    root = Path(a.root).resolve()
    sessions_dir = Path(a.sessions_dir)
    since = parse_ts(a.since) if a.since else None
    until = parse_ts(a.until) if a.until else None

    events = load_trace_blocks(root / a.hook_trace, since, until)
    if a.ids_file:
        have = {e["toolUseId"] for e in events}
        for e in load_ids_file(Path(a.ids_file)):
            if e["toolUseId"] not in have:
                events.append(e)
    events.sort(key=lambda e: e["at"] or "")

    # ledger 사본으로 replay → 라이브 ledger는 절대 쓰지 않는다
    with tempfile.TemporaryDirectory() as td:
        ledger_copy = Path(td) / "ledger.json"
        live = root / "var" / "agent-work-guard" / "ledger.json"
        if live.is_file():
            shutil.copyfile(live, ledger_copy)
        rows = review(root, root / "scripts" / "agent_work_guard.py",
                      sessions_dir, events, ledger_copy, since, until)

    result = {
        "schemaVersion": SCHEMA,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "events": rows,
        "counts": {
            "total": len(rows),
            "true_block": sum(1 for e in rows if e["verdict"] == "true_block"),
            "false_positive": sum(1 for e in rows
                                  if e["verdict"] == "false_positive"),
            "unknown": sum(1 for e in rows if e["verdict"] == "unknown"),
        },
    }
    if not a.no_readonly_scan:
        result["readOnlyScan"] = readonly_candidates(sessions_dir,
                                                     since, until)
    if a.out:
        out_path = Path(a.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(render_md(result), encoding="utf-8",
                            newline="\n")
    if a.json or not a.out:
        print(json.dumps(result["counts"], ensure_ascii=True))
    else:
        print(json.dumps({"status": "written", "out": a.out,
                          **result["counts"]}, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
