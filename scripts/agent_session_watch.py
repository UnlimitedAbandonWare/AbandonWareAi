#!/usr/bin/env python3
"""agent_session_watch.py — shared agent session-health watchdog.

Read-only scanner plus bounded diagnostics, usable by Devin, Grok, Codex and
Cline (CLI/BAT or MCP callers). Detects failure patterns that were actually
observed in this repo's Codex rollout sessions — see
docs/CODEX_SESSION_SOURCE_REGRESSION_AUDIT_20260919.md and the quarantine
evidence under C:\\AbandonWare\\_rescue\\codex-quarantine-9only-20260919.

Session stores discovered (no writes, ever):
  codex : $CODEX_HOME or ~/.codex      sessions/**/rollout-*.jsonl
                                     + archived_sessions/, state_*.sqlite presence
  grok  : ~/.grok/sessions/<enc-cwd>/<id>/{chat_history,events}.jsonl
                                     + session_search.sqlite, active_sessions.json
  devin : %APPDATA%/devin             cli/transcripts, summaries/, cli/summaries/,
                                     cli/sessions.db (root sessions.db still accepted)
  agy   : $AGY_HOME or ~/.gemini/antigravity-cli
                                     history.jsonl (session index),
                                     conversations/*.json,
                                     brain/**/transcript.jsonl
  cline : ~/Documents/Cline           rules-only; no local session store expected

Patterns (severity auto = safe to auto-diagnose; warn/info = report only):
  P1  apply-patch-context-miss   auto  'Failed to find expected lines in <f>'
                                       >=2 on the same target file (stale preimage)
  P2  apply-patch-malformed      warn  'invalid patch' / other verification errors
  P3  repeated-failed-command    auto  same normalized exec_command failing >=3
  P4  error-streak               warn  >=4 consecutive failed tool calls
  P5  context-compaction-heavy   warn  compacted >=10 / file >=50MB / tasks >=50
  P6  model-switch-mid-session   warn  non-developer model_switch, or >=2 switches
                                       (a single developer-role marker is the
                                       Codex session template, not a mid-stream
                                       model change)
  P7  goal-conflict              warn  create_goal 'unfinished goal' errors
  P8  edit-outside-cwd           warn  patch targets outside the session cwd root
  P9  stale-incomplete-session   warn  task_started>task_complete and file idle
                                       longer than --stale-hours
  P14 child-stale-no-evidence    warn  child session (parent_thread_id) with
                                       incomplete tasks and zero tool-call
                                       evidence, idle past --stale-hours —
                                       the quarantine-9only class; fires info
                                       early while the file is still fresh
  P10 subagent-fanout            info  spawn_agent/followup_task volume >=20
  P11 in-output-command-failure  auto  Script completed but output has
                                       Cannot-find-path / CreateProcess-Rejected
                                       (>=3 auto, else warn)
  P12 same-target-retry          auto  same relative file in >=3 failed
                                       exec_command calls (mutated one-liners)
  P13 scan-coverage-gap          warn  home discovery skipped oversized jsonl
  P15 devin-lock-pileup          auto  devin cli/session_locks/*.lock >20 warn,
                                       >100 auto. Live P14 stays
                                       child-stale-no-evidence.

Non-Codex stores (grok events/chat_history, devin transcripts) get generic
marker counts only — warn-level, never auto-diagnose.
Codex file filter: rollout-*.jsonl only (apply-*.jsonl sidecars are not sessions).

Actions:
  stores                     list discovered session stores
  patterns                   list the pattern table
  scan [--file F|--dir D|--agent A|--since-hours H|--max-files N|
        --max-file-mb M|--stale-hours H|--out DIR|
        --clean-stale-locks [--lock-stale-hours H] [--dry-run]]
                             e.g. scan --agent grok --since-hours 72
                             (Grok store; default agent is codex).
                             --clean-stale-locks reclaims devin
                             cli/session_locks *.lock older than
                             --lock-stale-hours (default 24h).
  diagnose                   run the bounded diagnostic bundle (read-only)
  watch                      scan, then on 'auto' findings run diagnostics
                             PLUS agent_work_guard advise (records missing /
                             retry paths so the next exec PreToolUse blocks)
  summary [--since-hours H|--json]
                             cross-agent roll-up for codex / agy / devin:
                             per-store session counts + last activity, top-10
                             tools overall, top-5 per agent, recent anomalies

Exit codes: 0 clean, 3 warnings only, 4 auto-severity findings, 2 usage, 1 error.
Raw prompts/messages are never copied into output — counts, paths, hashes and
<=140-char normalized command keys only.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

ROOT = Path(__file__).resolve().parents[1]
USER_HOME = Path(os.environ.get("USERPROFILE") or str(Path.home()))
SNIP = 140
SCHEMA = "awx.agent-session-watch.v1"

EXEC_CMD_RE = re.compile(r'exec_command\(\{"cmd":"((?:[^"\\]|\\.)*)"')
INNER_TOOL_RE = re.compile(r"tools\.(\w+)")
PATCH_TARGET_RE = re.compile(r"\*\*\* (?:Update|Add|Delete) File: ([^\r\n]+)")
PATCH_MISS_RE = re.compile(r"Failed to find expected lines in (.+?):\s*\r?\n")
ID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f-]{15,}")
FILE_IN_CMD_RE = re.compile(
    r"(?i)(?:^|[\s'\"(=])((?:main|src|scripts|docs|data|agent-prompts|"
    r"__patch_drop__|\.agents|app|frontend)[/\\][^\s\"'`;,)]+\.[A-Za-z0-9]{1,8})"
)
NF_PATH_RE = re.compile(
    r"(?i)Cannot find path '([^']+)'|Could not find a part of the path '([^']+)'"
)
MSG_HDR_RE = re.compile(r"^=== MESSAGE \d+ - (\w+) ===\s*$")

FAIL_MARKERS = ("Script failed", "Script error", "Exit code: 1", "Exit code: 2",
                "Exit code: 3", "ParserError")
# "Script completed" still carries these; quarantine explorers treated them as OK.
SOFT_FAIL_MARKERS = (
    "Cannot find path",
    "Could not find a part of the path",
    "ItemNotFoundException",
    "No such file or directory",
    "exec_command failed: CreateProcess",
)

PATTERNS = [
    ("P1", "apply-patch-context-miss", "auto",
     "apply_patch 'Failed to find expected lines' >=2 on the same target file"),
    ("P2", "apply-patch-malformed", "warn",
     "apply_patch 'invalid patch' or other verification failure"),
    ("P3", "repeated-failed-command", "auto",
     "same normalized exec_command failed >=3 times"),
    ("P4", "error-streak", "warn", ">=4 consecutive failed tool calls"),
    ("P5", "context-compaction-heavy", "warn",
     "compacted>=10 or file>=50MB or task_started>=50"),
    ("P6", "model-switch-mid-session", "warn",
     "non-developer model_switch, or >=2 switches (single developer = template)"),
    ("P7", "goal-conflict", "warn", "create_goal 'unfinished goal' rejection"),
    ("P8", "edit-outside-cwd", "warn", "patch target outside the session cwd root"),
    ("P9", "stale-incomplete-session", "warn",
     "task_started>task_complete and mtime older than --stale-hours"),
    ("P14", "child-stale-no-evidence", "warn",
     "child session + incomplete tasks + zero tool evidence; info while fresh,"
     " warn once idle past --stale-hours"),
    ("P10", "subagent-fanout", "info", "spawn_agent+followup_task >=20"),
    ("P11", "in-output-command-failure", "auto",
     "Script completed with Cannot-find-path / CreateProcess-Rejected "
     "(>=3 auto, else warn)"),
    ("P12", "same-target-retry", "auto",
     "same relative file in >=3 failed exec_command calls"),
    ("P13", "scan-coverage-gap", "warn",
     "home discovery skipped oversized session jsonl"),
    ("P15", "devin-lock-pileup", "auto",
     "devin cli/session_locks/*.lock count >20 warn, >100 auto "
     "(P14 remains child-stale-no-evidence)"),
    ("G1", "generic-error-markers", "warn",
     "non-codex jsonl: >=3 error markers (isError/error/failed)"),
]


def utcnow() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def norm_cmd(cmd: str) -> str:
    cmd = re.sub(r"\s+", " ", cmd or "").strip()
    cmd = ID_RE.sub("<id>", cmd)
    return cmd[:SNIP]


def norm_path(p: str) -> str:
    p = p.strip().strip('"').replace("/", "\\")
    while p.endswith("\\"):
        p = p[:-1]
    return p


def is_codex_rollout(path: Path) -> bool:
    return path.name.startswith("rollout-") and path.suffix.lower() == ".jsonl"


def files_in_cmd(cmd: str) -> list:
    out = []
    seen = set()
    for raw in FILE_IN_CMD_RE.findall(cmd or ""):
        fp = norm_path(raw)[:SNIP]
        key = fp.lower()
        if fp and key not in seen:
            seen.add(key)
            out.append(fp)
    return out


def agent_homes() -> dict:
    appdata = os.environ.get("APPDATA") or str(USER_HOME / "AppData/Roaming")
    return {
        "codex": Path(os.environ.get("CODEX_HOME", str(USER_HOME / ".codex"))),
        "grok": Path(os.environ.get("GROK_HOME", str(USER_HOME / ".grok"))),
        "devin": Path(os.environ.get("DEVIN_HOME", str(Path(appdata) / "devin"))),
        "agy": Path(os.environ.get(
            "AGY_HOME", str(USER_HOME / ".gemini" / "antigravity-cli"))),
        "cline": Path(os.environ.get("CLINE_HOME",
                                     str(USER_HOME / "Documents" / "Cline"))),
    }


def devin_sessions_db_present(home: Path) -> bool:
    """True when sessions.db is at the Devin home or under home/cli.

    The formatting call site must parenthesize this whole expression. `%`
    binds tighter than `or`, so an unparenthesized cli-only file used to
    render as sessions_db=False.
    """
    return (home / "sessions.db").exists() or (home / "cli" / "sessions.db").exists()


def devin_lock_dir(home: Path) -> Path:
    return home / "cli" / "session_locks"


def count_devin_locks(home: Path) -> int:
    directory = devin_lock_dir(home)
    if not directory.is_dir():
        return 0
    count = 0
    try:
        for path in directory.iterdir():
            if path.is_file() and path.suffix.lower() == ".lock":
                count += 1
    except OSError:
        return -1
    return count


def devin_lock_pileup_finding(count: int):
    """P15. Brief text said P14; live P14 is child-stale-no-evidence."""
    if count is None or count <= 20:
        return None
    severity = "auto" if count > 100 else "warn"
    return {
        "pattern": "P15",
        "name": "devin-lock-pileup",
        "severity": severity,
        "summary": "devin session lock files=%d (warn>20, auto>100)" % count,
        "evidence": {"lockFiles": count, "warnAbove": 20, "autoAbove": 100},
    }


def clean_devin_locks(home: Path, stale_hours: float,
                      dry_run: bool = False) -> dict:
    """Reclaim .lock files under devin cli/session_locks older than
    stale_hours (mtime basis). Flat dir only, .lock suffix only; lock file
    contents are never read or printed. Counts and names are the report."""
    directory = devin_lock_dir(home)
    out = {"requested": True, "dryRun": bool(dry_run),
           "thresholdHours": stale_hours, "dirPresent": directory.is_dir(),
           "before": 0, "stale": 0, "removed": 0, "remaining": None,
           "removedNames": [], "errors": []}
    if not directory.is_dir():
        return out
    cutoff = time.time() - stale_hours * 3600
    try:
        entries = [p for p in directory.iterdir()
                   if p.is_file() and p.suffix.lower() == ".lock"]
    except OSError as e:
        out["errors"].append(type(e).__name__)
        return out
    out["before"] = len(entries)
    for p in entries:
        try:
            if p.stat().st_mtime >= cutoff:
                continue
        except OSError:
            continue
        out["stale"] += 1
        if len(out["removedNames"]) < 20:
            out["removedNames"].append(p.name[:80])
        if dry_run:
            continue
        try:
            p.unlink()
            out["removed"] += 1
        except OSError as e:
            out["errors"].append("%s:%s" % (p.name[:60], type(e).__name__))
    out["remaining"] = count_devin_locks(home) if not dry_run else None
    return out


def discover_stores() -> list:
    out = []
    for agent, home in agent_homes().items():
        row = {"agent": agent, "home": str(home), "present": home.is_dir(),
               "sessionFiles": 0, "newestUtc": None, "notes": []}
        if not row["present"]:
            row["notes"].append("not_observed")
            out.append(row)
            continue
        newest = 0.0
        try:
            if agent == "codex":
                pats = ["sessions/**/rollout-*.jsonl",
                        "archived_sessions/**/rollout-*.jsonl"]
                row["notes"].append(
                    "state_dbs=%d" % len(list(home.glob("state_*.sqlite"))))
            elif agent == "grok":
                pats = ["sessions/*/*/chat_history.jsonl",
                        "sessions/*/*/events.jsonl"]
                row["notes"].append(
                    "index_db=%s" % (home / "sessions" / "session_search.sqlite").exists())
            elif agent == "devin":
                pats = ["cli/transcripts/**/*", "summaries/*.md",
                        "cli/summaries/*.md"]
                row["notes"].append(
                    "sessions_db=%s" % devin_sessions_db_present(home))
            elif agent == "agy":
                pats = ["history.jsonl", "conversations/*.json",
                        "brain/**/transcript.jsonl"]
                row["notes"].append(
                    "history=%s" % (home / "history.jsonl").exists())
                row["notes"].append(
                    "summaries_db=%s"
                    % (home / "conversation_summaries.db").exists())
            else:  # cline
                pats = ["**/*.json", "**/*.jsonl", "tasks/**/*"]
                row["notes"].append("rules-only store expected")
            for pat in pats:
                for i, p in enumerate(home.glob(pat)):
                    if i >= 5000:
                        row["notes"].append("enum-bounded")
                        break
                    if not p.is_file():
                        continue
                    row["sessionFiles"] += 1
                    try:
                        newest = max(newest, p.stat().st_mtime)
                    except OSError:
                        pass
        except OSError as e:
            row["notes"].append("enum-error:%s" % type(e).__name__)
        if newest:
            row["newestUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ",
                                           time.gmtime(newest))
        out.append(row)
    return out


def iter_codex_files(home: Path):
    for base in (home / "sessions", home / "archived_sessions"):
        if base.is_dir():
            yield from sorted((p for p in base.rglob("rollout-*.jsonl") if p.is_file()),
                              key=lambda p: p.stat().st_mtime if p.exists() else 0)


def iter_generic_files(home: Path, agent: str):
    if agent == "grok":
        root = home / "sessions"
        if root.is_dir():
            yield from sorted(root.glob("*/*/chat_history.jsonl"))
            yield from sorted(root.glob("*/*/events.jsonl"))
    elif agent == "devin":
        for pat in ("cli/transcripts/**/*.jsonl", "cli/transcripts/**/*.json",
                    "summaries/*.md", "cli/summaries/*.md"):
            yield from sorted(home.glob(pat))
    elif agent == "agy":
        hist = home / "history.jsonl"
        if hist.is_file():
            yield hist
        yield from sorted(home.glob("conversations/*.json"))
        yield from sorted(home.glob("brain/**/transcript.jsonl"))
    elif agent == "cline":
        for pat in ("**/tasks/**/*.jsonl", "**/tasks/**/*.json"):
            yield from sorted(home.glob(pat))


def empty_stats() -> dict:
    return {"lines": 0, "bounded": False, "taskStarted": 0, "taskComplete": 0,
            "toolCalls": 0, "toolFailed": 0, "maxFailStreak": 0,
            "compacted": 0, "modelSwitch": 0, "modelSwitchNonDev": 0,
            "userMessages": 0, "tools": {}, "mtimeUtc": None}


def output_failed(text: str) -> bool:
    return any(m in text for m in FAIL_MARKERS) or \
        any(m in text for m in SOFT_FAIL_MARKERS)


def soft_fail_hit(text: str) -> bool:
    return any(m in text for m in SOFT_FAIL_MARKERS)


def scan_codex(path: Path, stale_ms: int, max_lines: int) -> dict:
    stats = empty_stats()
    meta = {}
    calls = {}
    order = []  # [call_id, ok|None]
    patch_miss = {}
    patch_malformed = 0
    goal_conflicts = 0
    outside_edits = {}
    fail_cmds = {}
    file_fails = {}
    soft_fail_n = 0
    nf_paths = {}
    fanout = 0
    cwd = ""
    try:
        stats["mtimeUtc"] = time.strftime(
            "%Y-%m-%dT%H:%M:%SZ", time.gmtime(path.stat().st_mtime))
        mtime_ms = path.stat().st_mtime * 1000
    except OSError:
        mtime_ms = 0
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for i, line in enumerate(f):
            if i >= max_lines:
                stats["bounded"] = True
                break
            stats["lines"] += 1
            try:
                rec = json.loads(line)
            except Exception:
                continue
            t = rec.get("type")
            pl = rec.get("payload") or {}
            if t == "compacted":
                stats["compacted"] += 1
            elif t == "session_meta":
                cwd = norm_path(pl.get("cwd") or "")
                meta = {"sessionId": pl.get("id") or pl.get("session_id"),
                        "cwd": cwd, "originator": pl.get("originator"),
                        "cliVersion": pl.get("cli_version"),
                        "agentRole": pl.get("agent_role"),
                        "threadSource": pl.get("thread_source"),
                        "parent": pl.get("parent_thread_id")}
            elif t == "event_msg":
                st = pl.get("type")
                if st == "task_started":
                    stats["taskStarted"] += 1
                elif st == "task_complete":
                    stats["taskComplete"] += 1
            elif t == "response_item":
                st = pl.get("type")
                if st == "message":
                    if pl.get("role") == "user":
                        stats["userMessages"] += 1
                    body = json.dumps(pl.get("content") or "",
                                      ensure_ascii=False)[:4000]
                    if "model_switch" in body:
                        stats["modelSwitch"] += 1
                        if pl.get("role") != "developer":
                            stats["modelSwitchNonDev"] += 1
                elif st in ("custom_tool_call", "function_call"):
                    cid = pl.get("call_id") or pl.get("id") or ("o%d" % i)
                    inp = pl.get("input") or pl.get("arguments") or ""
                    inner = INNER_TOOL_RE.search(inp)
                    tool = inner.group(1) if inner else (pl.get("name") or "?")
                    stats["tools"][tool] = stats["tools"].get(tool, 0) + 1
                    stats["toolCalls"] += 1
                    if tool in ("spawn_agent", "followup_task"):
                        fanout += 1
                    cmd = ""
                    m = EXEC_CMD_RE.search(inp)
                    if m:
                        try:
                            cmd = json.loads('"' + m.group(1) + '"')
                        except Exception:
                            cmd = m.group(1)
                    if tool == "apply_patch" or "*** End Patch" in inp:
                        for tgt in PATCH_TARGET_RE.findall(inp):
                            tp = norm_path(re.split(r'["\'`;]|\\n', tgt)[0])
                            if not re.match(r"^([A-Za-z]:[\\/]|[./\\]|"
                                            r"[\w.-]+[\\/])", tp):
                                continue
                            if cwd and tp and ":" in tp and not \
                                    tp.lower().startswith(cwd.lower() + "\\"):
                                outside_edits[tp[:SNIP]] = \
                                    outside_edits.get(tp[:SNIP], 0) + 1
                    calls[cid] = {"tool": tool, "cmd": cmd,
                                  "files": files_in_cmd(cmd)}
                    order.append([cid, None])
                elif st in ("custom_tool_call_output", "function_call_output"):
                    cid = pl.get("call_id")
                    out = pl.get("output")
                    if isinstance(out, str):
                        s = out
                    elif isinstance(out, list):
                        s = "\n".join(str(x.get("text", "")) for x in out
                                      if isinstance(x, dict))
                    else:
                        s = json.dumps(out, ensure_ascii=False)
                    ok = not output_failed(s)
                    miss = PATCH_MISS_RE.search(s)
                    if miss:
                        tf = norm_path(miss.group(1))
                        patch_miss[tf] = patch_miss.get(tf, 0) + 1
                        ok = False
                    elif "invalid patch" in s or \
                            "apply_patch verification failed" in s:
                        patch_malformed += 1
                        ok = False
                    if "unfinished goal" in s or "has an active goal" in s:
                        goal_conflicts += 1
                        ok = False
                    if soft_fail_hit(s):
                        soft_fail_n += 1
                        for a, b in NF_PATH_RE.findall(s):
                            tf = norm_path(a or b)[:SNIP]
                            if tf and len(tf) > 3:
                                nf_paths[tf] = nf_paths.get(tf, 0) + 1
                        ok = False
                    for row in order:
                        if row[0] == cid and row[1] is None:
                            row[1] = ok
                            break

    streak = mx = 0
    for cid, ok in order:
        if cid not in calls or ok is None:
            continue
        if not ok:
            stats["toolFailed"] += 1
            streak += 1
            mx = max(mx, streak)
            if calls[cid]["cmd"]:
                key = norm_cmd(calls[cid]["cmd"])
                fail_cmds[key] = fail_cmds.get(key, 0) + 1
            for fp in calls[cid].get("files") or []:
                file_fails[fp] = file_fails.get(fp, 0) + 1
        else:
            streak = 0
    stats["maxFailStreak"] = mx

    findings = []

    def add(pid, name, sev, summary, evidence):
        findings.append({"pattern": pid, "name": name, "severity": sev,
                         "summary": summary, "evidence": evidence})

    repeated_miss = {k: v for k, v in patch_miss.items() if v >= 2}
    if repeated_miss:
        add("P1", "apply-patch-context-miss", "auto",
            "same file failed apply_patch context-match >=2 times "
            "(stale preimage / context drift)",
            {"files": dict(sorted(repeated_miss.items(),
                                  key=lambda x: -x[1])[:10]),
             "totalMisses": sum(patch_miss.values())})
    elif patch_miss:
        add("P1", "apply-patch-context-miss", "warn",
            "apply_patch context-match failures observed",
            {"files": dict(list(patch_miss.items())[:10])})
    if patch_malformed:
        add("P2", "apply-patch-malformed", "warn",
            "apply_patch rejected %d malformed patch(es)" % patch_malformed,
            {"count": patch_malformed})
    rep_cmds = {k: v for k, v in fail_cmds.items() if v >= 3}
    if rep_cmds:
        add("P3", "repeated-failed-command", "auto",
            "identical commands failed >=3 times (retry loop without new "
            "evidence)", {"commands": dict(sorted(rep_cmds.items(),
                                                 key=lambda x: -x[1])[:10])})
    if stats["maxFailStreak"] >= 4:
        add("P4", "error-streak", "warn",
            "%d consecutive failed tool calls" % stats["maxFailStreak"],
            {"maxFailStreak": stats["maxFailStreak"]})
    try:
        size_mb = path.stat().st_size / 1048576
    except OSError:
        size_mb = 0
    if stats["compacted"] >= 10 or size_mb >= 50 or stats["taskStarted"] >= 50:
        add("P5", "context-compaction-heavy", "warn",
            "heavy compaction/oversized session — context contamination risk",
            {"compacted": stats["compacted"], "sizeMB": round(size_mb, 1),
             "taskStarted": stats["taskStarted"]})
    if stats["modelSwitchNonDev"] or stats["modelSwitch"] >= 2:
        add("P6", "model-switch-mid-session", "warn",
            "model switch inside session — instruction set changed mid-stream",
            {"count": stats["modelSwitch"],
             "nonDeveloper": stats["modelSwitchNonDev"]})
    if goal_conflicts:
        add("P7", "goal-conflict", "warn",
            "create_goal rejected: unfinished goal still open",
            {"count": goal_conflicts,
             "next_action": "Call update_goal or close previous active goal "
                            "first, never retry create_goal",
             "remedy_cmd": "python -B scripts/"
                           "demo1_goal_switch_barrier.py check-goal"})
    if outside_edits:
        add("P8", "edit-outside-cwd", "warn",
            "patch targets outside the session cwd root",
            {"targets": dict(list(outside_edits.items())[:10])})
    if (stats["taskStarted"] > stats["taskComplete"] and mtime_ms
            and mtime_ms < stale_ms):
        add("P9", "stale-incomplete-session", "warn",
            "tasks started but not completed; session idle past stale window",
            {"taskStarted": stats["taskStarted"],
             "taskComplete": stats["taskComplete"],
             "mtimeUtc": stats["mtimeUtc"]})
    if fanout >= 20:
        add("P10", "subagent-fanout", "info",
            "high subagent fan-out — stale-child cleanup candidates",
            {"spawnCalls": fanout})
    is_child = bool(meta.get("parent"))
    if (is_child and stats["taskStarted"] > stats["taskComplete"]
            and stats["toolCalls"] == 0):
        if mtime_ms and mtime_ms < stale_ms:
            add("P14", "child-stale-no-evidence", "warn",
                "child session idle past stale window with zero tool-call "
                "evidence (quarantine-9only class)",
                {"parent": meta.get("parent"),
                 "taskStarted": stats["taskStarted"],
                 "taskComplete": stats["taskComplete"],
                 "mtimeUtc": stats["mtimeUtc"]})
        else:
            add("P14", "child-no-evidence-early", "info",
                "child session incomplete with no tool-call evidence yet — "
                "early child-stale-no-evidence watch",
                {"parent": meta.get("parent"),
                 "taskStarted": stats["taskStarted"],
                 "taskComplete": stats["taskComplete"],
                 "mtimeUtc": stats["mtimeUtc"]})
    if soft_fail_n >= 3:
        add("P11", "in-output-command-failure", "auto",
            "command output reported path-not-found or CreateProcess reject "
            "while the tool envelope looked completed",
            {"count": soft_fail_n,
             "paths": dict(sorted(nf_paths.items(), key=lambda x: -x[1])[:10])})
    elif soft_fail_n:
        add("P11", "in-output-command-failure", "warn",
            "command output reported path-not-found or CreateProcess reject",
            {"count": soft_fail_n,
             "paths": dict(list(nf_paths.items())[:10])})
    retry_files = {k: v for k, v in file_fails.items() if v >= 3}
    if retry_files:
        add("P12", "same-target-retry", "auto",
            "same file retried via mutated commands >=3 times after failure",
            {"files": dict(sorted(retry_files.items(),
                                  key=lambda x: -x[1])[:10])})
    return {"file": str(path), "agent": "codex", "meta": meta,
            "stats": stats, "findings": findings}


def scan_generic(path: Path, agent: str, stale_ms: int, max_lines: int) -> dict:
    stats = empty_stats()
    error_hits = 0
    types = {}
    tool_names = {}
    first_ts = last_ts = None
    try:
        stats["mtimeUtc"] = time.strftime(
            "%Y-%m-%dT%H:%M:%SZ", time.gmtime(path.stat().st_mtime))
        mtime_ms = path.stat().st_mtime * 1000
    except OSError:
        mtime_ms = 0
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for i, line in enumerate(f):
            if i >= max_lines:
                stats["bounded"] = True
                break
            stats["lines"] += 1
            try:
                rec = json.loads(line)
            except Exception:
                m = MSG_HDR_RE.match(line.strip())
                if m:
                    types["msg:" + m.group(1)] = \
                        types.get("msg:" + m.group(1), 0) + 1
                continue
            if not isinstance(rec, dict):
                continue
            t = rec.get("type") or rec.get("role") or "?"
            types[t] = types.get(t, 0) + 1
            for tc in rec.get("tool_calls") or []:
                if isinstance(tc, dict) and tc.get("name"):
                    tool_names[tc["name"]] = tool_names.get(tc["name"], 0) + 1
                    stats["toolCalls"] += 1
            ts = rec.get("ts") or rec.get("timestamp") or rec.get("created_at")
            if ts:
                if first_ts is None:
                    first_ts = ts
                last_ts = ts
            s = line if len(line) < 20000 else line[:20000]
            if '"isError":true' in s.replace(" ", "") or '"is_error":true' in \
                    s.replace(" ", "") or "Script failed" in s:
                error_hits += 1
            elif '"type":"error"' in s.replace(" ", "") or \
                    '"type": "error"' in s:
                error_hits += 1
    findings = []
    if error_hits >= 3:
        findings.append({
            "pattern": "G1", "name": "generic-error-markers", "severity": "warn",
            "summary": "%d error markers in %s jsonl" % (error_hits, agent),
            "evidence": {"errorMarkers": error_hits}})
    if stats["lines"] and first_ts and last_ts:
        stats["firstTs"], stats["lastTs"] = first_ts, last_ts
    # tool_calls names win over the type histogram when the format exposes them
    merged = tool_names if tool_names else types
    stats["toolKind"] = "tools" if tool_names else "types"
    stats["tools"] = dict(sorted(merged.items(), key=lambda x: -x[1])[:10])
    return {"file": str(path), "agent": agent, "meta": {},
            "stats": stats, "findings": findings}


def gather_files(agent: str, homes: dict, since_ms: float, max_files: int,
                 max_mb: int, explicit_file=None, explicit_dir=None):
    files = []
    skipped = []
    if explicit_file:
        p = Path(explicit_file)
        if p.is_file():
            files.append((agent if agent != "all" else "codex", p))
        return files, False, skipped
    if explicit_dir:
        d = Path(explicit_dir)
        if d.is_dir():
            for p in sorted(d.rglob("*.jsonl")):
                use_agent = agent if agent != "all" else "codex"
                if use_agent == "codex" and not is_codex_rollout(p):
                    continue
                files.append((use_agent, p))
        return files[:max_files], len(files) > max_files, skipped
    agents = [agent] if agent != "all" else list(homes.keys())
    for a in agents:
        home = homes.get(a)
        if not home or not home.is_dir():
            continue
        it = iter_codex_files(home) if a == "codex" else iter_generic_files(home, a)
        for p in it:
            try:
                st = p.stat()
            except OSError:
                continue
            if st.st_mtime * 1000 < since_ms:
                continue
            if st.st_size > max_mb * 1048576:
                skipped.append({"file": str(p), "agent": a, "reason": "oversize",
                                "sizeMB": round(st.st_size / 1048576, 1)})
                # Still scan; max_lines bounds work. P13 warns. Hook never
                # re-reads the oversized jsonl — it only consults the ledger.
            files.append((a, p))
    files.sort(key=lambda x: -x[1].stat().st_mtime)
    bounded = len(files) > max_files
    return files[:max_files], bounded, skipped


def run_diag_step(name, argv, timeout=90):
    try:
        r = subprocess.run(argv, capture_output=True, text=True,
                           cwd=str(ROOT), timeout=timeout)
        out = (r.stdout or "")[:4000]
        err = (r.stderr or "")[:1000]
        return {"name": name, "cmd": argv[:3] + ["…"] if len(argv) > 3 else argv,
                "exit": r.returncode, "stdout": out, "stderr": err}
    except subprocess.TimeoutExpired:
        return {"name": name, "exit": "timeout", "stdout": "", "stderr": ""}
    except OSError as e:
        return {"name": name, "exit": "spawn-error",
                "stdout": "", "stderr": type(e).__name__}


def diagnostic_bundle(with_server=False):
    ps = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File"]
    steps = [
        ("agent-preflight",
         ["python", "-B", "scripts/agent_preflight.py", "--root", "."]),
        ("work-journal-active",
         ["python", "-B", "scripts/work_journal.py", "list", "--active"]),
        ("source-lease-status",
         ps + ["__patch_drop__/source_edit_session.ps1", "-Action", "status",
               "-Json"]),
        ("lease-lifecycle-scan",
         ["python", "-B", "scripts/lease_conflict_autoflow.py", "scan"]),
        ("work-guard-root",
         ["python", "-B", "scripts/agent_work_guard.py", "root", "--root", "."]),
    ]
    if with_server:
        steps.append(("debug-stack-status",
                      ps + ["scripts/debug_rag_stack.ps1", "-Role", "dev",
                            "-Action", "status", "-Json"]))
    return [run_diag_step(n, a) for n, a in steps]


def cmd_scan(a) -> int:
    homes = agent_homes()
    since_ms = time.time() * 1000 - a.since_hours * 3600_000
    stale_ms = time.time() * 1000 - a.stale_hours * 3600_000
    files, bounded, skipped = gather_files(a.agent, homes, since_ms, a.max_files,
                                           a.max_file_mb, a.file, a.dir)
    sessions = []
    counts = {"auto": 0, "warn": 0, "info": 0}
    for agent, p in files:
        try:
            if agent == "codex":
                row = scan_codex(p, stale_ms, a.max_lines)
            else:
                row = scan_generic(p, agent, stale_ms, a.max_lines)
        except OSError as e:
            row = {"file": str(p), "agent": agent, "meta": {},
                   "stats": empty_stats(),
                   "findings": [{"pattern": "E0", "name": "scan-error",
                                 "severity": "warn",
                                 "summary": type(e).__name__, "evidence": {}}]}
        for f_ in row["findings"]:
            counts[f_["severity"]] = counts.get(f_["severity"], 0) + 1
        sessions.append(row)
    scanned_files = len(sessions)
    lock_cleanup = None
    if a.agent in ("devin", "all"):
        home = homes.get("devin")
        if a.clean_stale_locks and home is not None:
            lock_cleanup = clean_devin_locks(home, a.lock_stale_hours,
                                             dry_run=a.dry_run)
        lock_finding = devin_lock_pileup_finding(
            count_devin_locks(home) if home is not None else 0)
        if lock_finding:
            if lock_cleanup:
                lock_finding["evidence"]["cleanup"] = {
                    "before": lock_cleanup["before"],
                    "stale": lock_cleanup["stale"],
                    "removed": lock_cleanup["removed"],
                    "dryRun": lock_cleanup["dryRun"]}
            sessions.append({
                "file": None, "agent": "devin", "meta": {},
                "stats": empty_stats(), "findings": [lock_finding]})
            counts[lock_finding["severity"]] = counts.get(
                lock_finding["severity"], 0) + 1
    if skipped:
        sessions.append({
            "file": None, "agent": a.agent, "meta": {}, "stats": empty_stats(),
            "findings": [{"pattern": "P13", "name": "scan-coverage-gap",
                          "severity": "warn",
                          "summary": "%d oversized session file(s) still scanned "
                                     "(max_lines bound); hook does not re-read them"
                                     % len(skipped),
                          "evidence": {"skipped": skipped[:20],
                                       "maxFileMB": a.max_file_mb}}]})
        counts["warn"] = counts.get("warn", 0) + 1
    report = {"schemaVersion": SCHEMA, "generatedAt": utcnow(),
              "bounds": {"sinceHours": a.since_hours, "staleHours": a.stale_hours,
                         "maxFiles": a.max_files, "maxFileMB": a.max_file_mb,
                         "maxLines": a.max_lines, "enumBounded": bounded,
                         "skippedOversize": len(skipped)},
              "filesScanned": scanned_files,
              "severityCounts": counts,
              "sessions": sessions}
    if lock_cleanup is not None:
        report["lockCleanup"] = lock_cleanup
    if a.out:
        outdir = Path(a.out)
        outdir.mkdir(parents=True, exist_ok=True)
        rp = outdir / ("session-watch-%s.json"
                       % time.strftime("%Y%m%dT%H%M%S", time.gmtime()))
        rp.write_text(json.dumps(report, ensure_ascii=False, indent=1),
                      encoding="utf-8")
        report["reportPath"] = str(rp)
    print(json.dumps(report, ensure_ascii=False))
    if counts.get("auto"):
        return 4, report
    if counts.get("warn"):
        return 3, report
    return 0, report


def advise_auto_findings(report) -> dict:
    findings = []
    for session in report.get("sessions") or []:
        for item in session.get("findings") or []:
            if item.get("severity") == "auto":
                findings.append(item)
    if not findings:
        return {"status": "skipped", "reason": "no-auto-findings"}
    try:
        import agent_work_guard as wg
        return wg.advise(ROOT, findings)
    except Exception as exc:
        return {"status": "unavailable", "reason": type(exc).__name__}


SUMMARY_AGENTS = ("codex", "agy", "devin")


def omni_summary(a) -> dict:
    """Bounded cross-agent roll-up for the three active session stores."""
    homes = agent_homes()
    agents = [x for x in SUMMARY_AGENTS if x in homes]
    since_ms = time.time() * 1000 - a.since_hours * 3600_000
    stale_ms = time.time() * 1000 - a.stale_hours * 3600_000
    since_utc = time.strftime("%Y-%m-%dT%H:%M:%SZ",
                              time.gmtime(since_ms / 1000))
    stores = {}
    for row in discover_stores():
        if row["agent"] not in agents:
            continue
        stores[row["agent"]] = {
            "present": row["present"],
            "sessionFiles": row["sessionFiles"],
            "newestUtc": row["newestUtc"],
            "active": bool(row["newestUtc"] and row["newestUtc"] >= since_utc),
            "notes": row["notes"][:4]}
    tools_by_agent = {}
    overall = {}
    anomalies = []
    files_scanned = 0
    for agent in agents:
        per = {}
        files, _bounded, _skipped = gather_files(
            agent, homes, since_ms, a.max_files, a.max_file_mb)
        for ag, p in files:
            try:
                row = scan_codex(p, stale_ms, a.max_lines) if ag == "codex" \
                    else scan_generic(p, ag, stale_ms, a.max_lines)
            except OSError:
                continue
            files_scanned += 1
            for name, n in (row["stats"].get("tools") or {}).items():
                per[name] = per.get(name, 0) + n
                overall[name] = overall.get(name, 0) + n
            for f in row["findings"]:
                anomalies.append({"agent": ag, "file": str(p),
                                  "pattern": f["pattern"],
                                  "severity": f["severity"],
                                  "summary": f["summary"]})
        tools_by_agent[agent] = dict(
            sorted(per.items(), key=lambda x: -x[1])[:5])
    sev_rank = {"auto": 0, "warn": 1, "info": 2}
    anomalies.sort(key=lambda x: sev_rank.get(x["severity"], 3))
    return {"schemaVersion": "awx.agent-session-omni.v1",
            "generatedAt": utcnow(),
            "bounds": {"sinceHours": a.since_hours,
                       "maxFilesPerAgent": a.max_files,
                       "maxFileMB": a.max_file_mb,
                       "agents": agents},
            "filesScanned": files_scanned,
            "stores": stores,
            "topToolsOverall": dict(
                sorted(overall.items(), key=lambda x: -x[1])[:10]),
            "toolsByAgent": tools_by_agent,
            "anomalies": anomalies[:30],
            "anomalyCounts": {sev: sum(1 for x in anomalies
                                      if x["severity"] == sev)
                              for sev in ("auto", "warn", "info")}}


def cmd_summary(a) -> int:
    report = omni_summary(a)
    if a.json:
        print(json.dumps(report, ensure_ascii=False, indent=1))
        return 0
    lines = ["# Agent session omni summary",
             "generated %s - window last %sh" % (report["generatedAt"],
                                                report["bounds"]["sinceHours"]),
             "",
             "| agent | present | sessions | last activity (UTC) | active |",
             "|---|---|---|---|---|"]
    for agent in SUMMARY_AGENTS:
        s = report["stores"].get(agent) or {}
        lines.append("| %s | %s | %s | %s | %s |" % (
            agent, s.get("present"), s.get("sessionFiles", 0),
            s.get("newestUtc") or "-", s.get("active")))
    lines += ["", "## Top tools (all agents, top 10)", ""]
    if report["topToolsOverall"]:
        for name, n in report["topToolsOverall"].items():
            lines.append("- `%s` x%d" % (name, n))
    else:
        lines.append("- (none observed)")
    lines += ["", "## Top tools by agent (top 5)", ""]
    for agent in SUMMARY_AGENTS:
        tools = report["toolsByAgent"].get(agent) or {}
        body = ", ".join("`%s`x%d" % (k, v) for k, v in tools.items()) or "-"
        lines.append("- **%s**: %s" % (agent, body))
    lines += ["", "## Anomalies", ""]
    counts = report["anomalyCounts"]
    lines.append("auto=%d warn=%d info=%d" % (counts["auto"], counts["warn"],
                                              counts["info"]))
    if report["anomalies"]:
        for x in report["anomalies"][:15]:
            lines.append("- `%s` [%s/%s] %s" % (x["pattern"], x["agent"],
                                                x["severity"], x["summary"]))
    else:
        lines.append("- none")
    print("\n".join(lines))
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("action", choices=("stores", "patterns", "scan", "diagnose",
                                      "watch", "summary"))
    p.add_argument("--agent", default="codex",
                   choices=("codex", "grok", "devin", "cline", "agy", "all"))
    p.add_argument("--file")
    p.add_argument("--dir")
    p.add_argument("--since-hours", type=float, default=72)
    p.add_argument("--stale-hours", type=float, default=48)
    p.add_argument("--max-files", type=int, default=64)
    p.add_argument("--max-file-mb", type=int, default=32)
    p.add_argument("--max-lines", type=int, default=400000)
    p.add_argument("--with-server", action="store_true",
                   help="diagnose/watch: include debug_rag_stack status")
    p.add_argument("--json", action="store_true",
                   help="summary: emit JSON instead of the markdown table")
    p.add_argument("--out", help="write full report JSON into this directory")
    p.add_argument("--clean-stale-locks", action="store_true",
                   help="scan/watch on devin|all: unlink cli/session_locks "
                        "*.lock older than --lock-stale-hours (U-1: 24h)")
    p.add_argument("--lock-stale-hours", type=float, default=24.0,
                   help="age threshold for --clean-stale-locks")
    p.add_argument("--dry-run", action="store_true",
                   help="with --clean-stale-locks: count stale locks, "
                        "unlink nothing")
    a = p.parse_args()

    if a.action == "patterns":
        print(json.dumps({"patterns": [
            {"id": i, "name": n, "severity": s, "trigger": d}
            for i, n, s, d in PATTERNS]}, ensure_ascii=False, indent=1))
        return 0
    if a.action == "stores":
        print(json.dumps({"schemaVersion": SCHEMA, "generatedAt": utcnow(),
                          "stores": discover_stores()},
                         ensure_ascii=False, indent=1))
        return 0
    if a.action == "summary":
        return cmd_summary(a)
    if a.action == "diagnose":
        print(json.dumps({"schemaVersion": SCHEMA, "generatedAt": utcnow(),
                          "diagnostics": diagnostic_bundle(a.with_server)},
                         ensure_ascii=False))
        return 0
    if a.action == "watch":
        rc, report = cmd_scan(a)
        if rc == 4:
            print(json.dumps({
                "watch": "auto-findings -> diagnostics+advise",
                "diagnostics": diagnostic_bundle(a.with_server),
                "advise": advise_auto_findings(report),
            }, ensure_ascii=False))
        return rc
    rc, _ = cmd_scan(a)
    return rc


if __name__ == "__main__":
    sys.exit(main())
