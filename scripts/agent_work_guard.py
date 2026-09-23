#!/usr/bin/env python3
"""agent_work_guard.py — in-session brake for explorer read/retry loops.

Shared verdict. Per-agent hook adapters (docs 2026-09-20):
  Grok  stdin toolName/toolInput; deny via decision=deny (exit 2). Fail-open
        on timeout/crash/malformed. https://docs.x.ai/build/features/hooks
  Codex stdin tool_name/tool_input; shell tool_name is Bash even for
        exec_command. Deny via hookSpecificOutput.permissionDecision=deny
        or legacy decision=block or exit 2+stderr.
        https://developers.openai.com/codex/hooks
  Devin stdin tool_name=exec, tool_input.command; decision=block; exit 2.
        .devin/hooks.v1.json has no outer hooks key.
        https://docs.devin.ai/cli/extensibility/hooks/overview

Does not block: file create, search, outside reads (quarantine), or a path
that exists after a previous missing-file streak. Counts only PostToolUse
failures, keyed by agent+actor+path+cause (not session_id alone).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

SCHEMA = "awx.agent-work-guard.v2"
CANONICAL = Path(r"C:\AbandonWare\demo-1\demo-1\src")
RETRY_LIMIT = 3
LEDGER_TTL_S = 24 * 3600
LEDGER_MAX = 200
SNIP = 160

FILE_IN_CMD_RE = re.compile(
    r"(?i)(?:^|[\s'\"(=])((?:main|src|scripts|docs|data|agent-prompts|"
    r"__patch_drop__|\.agents|app|frontend)[/\\][^\s\"'`;,)]+\.[A-Za-z0-9]{1,8})"
)
READ_RE = re.compile(
    r"(?i)\b(Get-Content|Get-FileHash|Get-Item|Select-String|"
    r"type|cat|more|less)\b"
)
SEARCH_RE = re.compile(r"(?i)\b(rg|grep|findstr|Test-Path|Get-ChildItem|dir|ls)\b")
CREATE_RE = re.compile(
    r"(?i)\b(New-Item|Set-Content|Add-Content|Out-File|ni|mkdir|md|"
    r"Copy-Item|Move-Item)\b|[>]{1,2}"
)
MARKERS = (
    ("gradlew.bat", True),
    ("AGENTS.md", True),
    ("settings.gradle.kts", True),
    ("main/java", False),
)

# Official shell tool_name each host puts on stdin (matcher must match this).
AGENT_SHELL_TOOL = {
    "grok": "run_terminal_command",
    "codex": "Bash",
    "devin": "exec",
}


def utcnow() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def norm_rel(p: str) -> str:
    p = (p or "").strip().strip('"').replace("/", "\\")
    while p.endswith("\\"):
        p = p[:-1]
    if p.lower().startswith("\\\\?\\"):
        p = p[4:]
    return p


def rel_under(root: Path, path: Path):
    try:
        return str(path.resolve().relative_to(root.resolve())).replace("/", "\\")
    except (ValueError, OSError):
        return None


def classify_root(root: Path) -> dict:
    resolved = root.resolve()
    markers = {}
    for name, is_file in MARKERS:
        p = resolved.joinpath(*name.split("/"))
        markers[name] = p.is_file() if is_file else p.is_dir()
    marker_ok = sum(1 for v in markers.values() if v) >= 3
    canonical = CANONICAL.resolve() if CANONICAL.exists() else CANONICAL
    matches = resolved == canonical
    src_child = resolved / "src"
    cls = "other"
    if matches:
        cls = "canonical"
    elif marker_ok:
        cls = "project_root_markers"
    elif src_child.is_dir() and (src_child / "AGENTS.md").is_file():
        cls = "parent_of_src"
    return {
        "resolved": str(resolved),
        "canonical": str(canonical),
        "matchesCanonical": matches,
        "class": cls,
        "markers": markers,
        "next": None if matches or marker_ok else
        "cd to the src folder that contains AGENTS.md and main/java",
    }


def classify_path(root: Path, raw: str) -> dict:
    """Facts only. Intent (read/create/search) decides blocking."""
    root = root.resolve()
    text = norm_rel(raw)
    row = {"input": text[:SNIP], "class": "empty", "exists": False,
           "relative": None, "reason": "", "next": ""}
    if not text:
        row["reason"] = "empty-path"
        return row
    low = text.lower()
    try:
        full = Path(text) if Path(text).is_absolute() else (root / text)
        full = full.resolve()
    except (OSError, ValueError):
        row["class"] = "invalid"
        row["reason"] = "path-invalid"
        return row
    rel = rel_under(root, full)
    row["relative"] = rel
    row["exists"] = full.exists()
    parent = root.parent
    under_parent = rel_under(parent, full) if parent != root else None
    wrong_main = (low.startswith("src\\main\\") or "\\src\\src\\main\\" in low
                  or low.startswith("src\\src\\src\\"))
    if (not row["exists"]) and wrong_main:
        row["class"] = "doubled_src"
        row["reason"] = "doubled-src-prefix"
        row["next"] = "read main/java/... — project root is already ...\\src"
        return row
    if rel is None and under_parent:
        row["class"] = "parent_of_root"
        row["reason"] = "outside-project-root"
        row["next"] = "outside reads are allowed; do not write here without a lease"
        return row
    if rel is None:
        row["class"] = "outside"
        row["reason"] = "outside-root"
        return row
    row["class"] = "exists" if row["exists"] else "missing"
    if not row["exists"]:
        row["reason"] = "path-does-not-exist"
        row["next"] = ("if creating: New-Item/Set-Content under the approved path; "
                       "if reading: list the real file, do not mutate the one-liner")
    return row


def cmd_intent(cmd: str) -> str:
    create = bool(CREATE_RE.search(cmd or ""))
    search = bool(SEARCH_RE.search(cmd or ""))
    read = bool(READ_RE.search(cmd or ""))
    if create and not read:
        return "create"
    if search and not read:
        return "search"
    if read:
        return "read"
    return "other"


def cause_from_output(text: str) -> str:
    s = text or ""
    if "ParserError" in s:
        return "parser"
    if "Cannot find path" in s or "Could not find a part of the path" in s \
            or "No such file" in s or "ItemNotFoundException" in s:
        return "missing"
    if "exec_command failed: CreateProcess" in s:
        return "createprocess"
    if re.search(r"Exit code:\s*[1-9]", s):
        return "nonzero"
    return ""


def trace_path(root: Path) -> Path:
    return root / "var" / "agent-work-guard" / "hook-trace.jsonl"


def write_trace(root: Path, row: dict) -> None:
    """One JSONL line: event, tool name/id, decision, exit, elapsed. No cmd/body."""
    try:
        p = trace_path(root)
        p.parent.mkdir(parents=True, exist_ok=True)
        if p.is_file() and p.stat().st_size > 262144:
            p.write_text("", encoding="utf-8")
        safe = {
            "at": row.get("at") or utcnow(),
            "phase": row.get("phase") or "",
            "event": (row.get("event") or "")[:40],
            "toolName": (row.get("toolName") or "")[:80],
            "toolUseId": (row.get("toolUseId") or "")[:80],
            "decision": (row.get("decision") or "")[:24],
            "exit": row.get("exit"),
            "elapsedMs": row.get("elapsedMs"),
        }
        with p.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(safe, ensure_ascii=True) + "\n")
    except OSError:
        return


def ledger_path(root: Path, override=None) -> Path:
    if override:
        return Path(override)
    env = os.environ.get("AWX_WORK_GUARD_LEDGER")
    if env:
        return Path(env)
    return root / "var" / "agent-work-guard" / "ledger.json"


def load_ledger(path: Path) -> dict:
    if not path.is_file():
        return {"schemaVersion": SCHEMA, "entries": {}}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"schemaVersion": SCHEMA, "entries": {}}
    if not isinstance(data, dict) or not isinstance(data.get("entries"), dict):
        return {"schemaVersion": SCHEMA, "entries": {}}
    now = time.time()
    kept = {}
    for key, row in data["entries"].items():
        if not isinstance(row, dict):
            continue
        at = float(row.get("at") or 0)
        if now - at > LEDGER_TTL_S:
            continue
        kept[key] = row
    return {"schemaVersion": SCHEMA, "entries": kept}


def save_ledger(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    entries = data.get("entries") or {}
    if len(entries) > LEDGER_MAX:
        ordered = sorted(entries.items(), key=lambda kv: float(kv[1].get("at") or 0),
                         reverse=True)[:LEDGER_MAX]
        entries = dict(ordered)
        data["entries"] = entries
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def entry_key(agent: str, actor: str, rel: str, cause: str) -> str:
    return "|".join([
        (agent or "unknown").casefold(),
        (actor or "root").casefold(),
        (rel or "").replace("/", "\\").casefold(),
        (cause or "other").casefold(),
    ])


def record(root: Path, rel: str, result: str, cause: str, ledger=None,
           agent="unknown", actor="root", call_id="") -> dict:
    path = ledger_path(root, ledger)
    data = load_ledger(path)
    k = entry_key(agent, actor, rel, cause or "other")
    if not rel:
        return {"recorded": False, "reason": "empty"}
    now = time.time()
    row = data["entries"].get(k) or {"fails": 0, "ok": 0, "at": now, "cause": cause}
    if call_id and row.get("lastCallId") == call_id:
        return {"recorded": False, "reason": "duplicate-call", "fails": row["fails"],
                "blocked": int(row.get("fails") or 0) >= RETRY_LIMIT}
    if result == "ok":
        row["fails"] = 0
        row["ok"] = int(row.get("ok") or 0) + 1
    else:
        row["fails"] = int(row.get("fails") or 0) + 1
        row["cause"] = (cause or "other")[:80]
    row["at"] = now
    row["rel"] = rel[:SNIP]
    row["agent"] = agent
    row["actor"] = actor
    if call_id:
        row["lastCallId"] = call_id[:80]
    data["entries"][k] = row
    save_ledger(path, data)
    return {"recorded": True, "relative": rel[:SNIP], "fails": row["fails"],
            "blocked": row["fails"] >= RETRY_LIMIT, "agent": agent,
            "actor": actor, "cause": row.get("cause")}


def reset_missing_if_exists(root: Path, rel: str, agent: str, actor: str,
                            ledger=None) -> bool:
    h = classify_path(root, rel)
    if not h.get("exists"):
        return False
    path = ledger_path(root, ledger)
    data = load_ledger(path)
    k = entry_key(agent, actor, h.get("relative") or rel, "missing")
    row = data["entries"].get(k)
    if not row:
        return False
    row["fails"] = 0
    row["at"] = time.time()
    row["reset"] = "file-now-exists"
    data["entries"][k] = row
    save_ledger(path, data)
    return True


def check_retry(root: Path, rel: str, ledger=None, agent="unknown",
                actor="root", cause="other") -> dict:
    data = load_ledger(ledger_path(root, ledger))
    k = entry_key(agent, actor, rel, cause)
    row = data["entries"].get(k) or {}
    fails = int(row.get("fails") or 0)
    return {"relative": rel[:SNIP], "fails": fails,
            "blocked": fails >= RETRY_LIMIT,
            "cause": row.get("cause") or cause,
            "agent": agent, "actor": actor}


def files_in_cmd(cmd: str) -> list:
    out, seen = [], set()
    for raw in FILE_IN_CMD_RE.findall(cmd or ""):
        fp = norm_rel(raw)
        k = fp.casefold()
        if fp and k not in seen:
            seen.add(k)
            out.append(fp)
    return out


def event_get(event: dict, *names):
    for name in names:
        if name in event and event[name] not in (None, ""):
            return event[name]
    return None


def detect_agent(event: dict) -> str:
    # Event shape wins: this process may inherit GROK_SESSION_ID while
    # simulating Codex/Devin stdin in tests.
    if "toolName" in event or "toolInput" in event or "hookEventName" in event:
        return "grok"
    tool = str(event_get(event, "tool_name", "toolName") or "")
    if tool == "exec":
        return "devin"
    if tool == "Bash" or "tool_use_id" in event:
        return "codex"
    if tool == "run_terminal_command":
        return "grok"
    env = os.environ
    if env.get("GROK_HOOK_EVENT") or env.get("GROK_SESSION_ID"):
        return "grok"
    if env.get("DEVIN_PROJECT_DIR"):
        return "devin"
    return "unknown"


def detect_actor(event: dict) -> str:
    # Codex subagents reuse parent session_id; prefer agent_id when present.
    for key in ("agent_id", "agentId"):
        val = event.get(key)
        if isinstance(val, str) and val.strip():
            return val.strip()[:80]
    return "root"


def detect_call_id(event: dict) -> str:
    for key in ("tool_use_id", "toolUseId", "call_id"):
        val = event.get(key)
        if isinstance(val, str) and val.strip():
            return val.strip()[:80]
    return ""


def extract_cmd(event: dict) -> str:
    tool_input = event_get(event, "tool_input", "toolInput") or {}
    if isinstance(tool_input, str):
        try:
            tool_input = json.loads(tool_input)
        except ValueError:
            return tool_input
    if not isinstance(tool_input, dict):
        return ""
    for key in ("cmd", "command", "script"):
        val = tool_input.get(key)
        if isinstance(val, str) and val.strip():
            return val
    args = tool_input.get("arguments") or tool_input.get("args")
    if isinstance(args, dict):
        for key in ("cmd", "command"):
            val = args.get(key)
            if isinstance(val, str) and val.strip():
                return val
    return ""


def extract_output(event: dict) -> str:
    # Grok PostToolUse: toolResult (tagged object); snake alias tool_response.
    tr = event_get(event, "toolResult", "tool_result")
    if isinstance(tr, str) and tr:
        return tr[:8000]
    if isinstance(tr, dict):
        parts = []
        code = tr.get("exit_code")
        if code not in (None, 0, "0"):
            parts.append("Exit code: %s" % code)
        for key in ("output_for_prompt", "output", "stderr", "error"):
            val = tr.get(key)
            if isinstance(val, str) and val:
                parts.append(val[:4000])
        if parts:
            return "\n".join(parts)[:8000]
    resp = event_get(event, "tool_response", "toolResponse")
    if isinstance(resp, str) and resp:
        return resp[:8000]
    if isinstance(resp, dict):
        parts = [str(resp.get("output") or ""), str(resp.get("error") or "")]
        if resp.get("success") is False and not any(parts):
            parts.append("tool_response.success=false")
        joined = "\n".join(p for p in parts if p)
        if joined:
            return joined[:8000]
    for key in ("tool_output", "toolOutput", "output", "result"):
        val = event.get(key)
        if isinstance(val, str) and val:
            return val[:8000]
        if isinstance(val, dict):
            t = val.get("output") or val.get("text") or ""
            if isinstance(t, str) and t:
                return t[:8000]
    return ""


def next_for(reason: str, rel: str) -> str:
    if reason in ("path-does-not-exist", "missing"):
        return ("create with New-Item/Set-Content under the approved path, "
                "or list the real file; do not retry a mutated Get-Content")
    if reason == "doubled-src-prefix":
        return "read main/java/... — root is already ...\\src"
    if reason == "same-target-retry":
        return ("same path+cause failed 3 times — fix the file or the cause, "
                "then read once; do not mutate the one-liner")
    if reason == "parser":
        return "run a .ps1/.py file via -File; do not retry a broken inline command"
    return "stop retrying this path until the cause changes"


def verdict_for_cmd(root: Path, cmd: str, ledger=None, agent="unknown",
                    actor="root") -> dict:
    intent = cmd_intent(cmd)
    files = files_in_cmd(cmd)
    hits = [classify_path(root, f) for f in files]
    retry = []
    block = None
    if intent == "create":
        return {"schemaVersion": SCHEMA, "decision": "allow", "intent": intent,
                "reason": "", "next": "", "paths": hits[:8], "retries": []}
    if intent == "search":
        return {"schemaVersion": SCHEMA, "decision": "allow", "intent": intent,
                "reason": "", "next": "", "paths": hits[:8], "retries": []}
    for h in hits:
        rel = h.get("relative") or h.get("input")
        if h.get("class") == "doubled_src" and intent == "read":
            block = h
            break
        if h.get("class") == "missing" and intent == "read":
            block = dict(h)
            block["next"] = next_for("missing", rel)
            break
        if rel:
            # Reset missing-streaks once the file exists.
            reset_missing_if_exists(root, rel, agent, actor, ledger)
            r = check_retry(root, rel, ledger, agent, actor, "missing")
            if not r.get("blocked"):
                r = check_retry(root, rel, ledger, agent, actor, "parser")
            retry.append(r)
            if r.get("blocked") and intent == "read":
                # If missing-streak but file now exists, already reset.
                if r.get("cause") == "missing" and h.get("exists"):
                    continue
                block = {"class": "retry_loop", "reason": "same-target-retry",
                         "relative": rel, "next": next_for(r.get("cause") or
                                                           "same-target-retry", rel)}
                break
    return {
        "schemaVersion": SCHEMA,
        "decision": "block" if block else "allow",
        "intent": intent,
        "reason": (block or {}).get("reason") or "",
        "next": (block or {}).get("next") or "",
        "paths": hits[:8],
        "retries": retry[:8],
        "agent": agent,
        "actor": actor,
    }


def emit_payload(agent: str, blocked: bool, reason: str, nxt: str,
                 extra=None) -> dict:
    extra = extra or {}
    text = ("work-guard: %s — %s" % (reason, nxt)).strip(" —")
    if agent == "grok":
        payload = {"decision": "deny" if blocked else "allow", "reason": text}
    elif agent == "codex":
        if blocked:
            payload = {
                "decision": "block",
                "reason": text,
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": text,
                },
            }
        else:
            payload = {"decision": "allow", "reason": ""}
    elif agent == "devin":
        payload = {"decision": "block" if blocked else "approve",
                   "reason": text if blocked else ""}
    else:
        payload = {"decision": "block" if blocked else "allow",
                   "reason": text if blocked else ""}
    if extra:
        payload["verdict"] = extra
    return payload


def hook(root: Path, event: dict, ledger=None) -> tuple:
    """Return (payload, exit_code). 2=deny/block, 0=allow, 1=fail-open."""
    started = time.time()
    tool_name = str(event_get(event, "tool_name", "toolName") or "")[:80]
    call_id = detect_call_id(event)
    hook_event = str(event_get(event, "hook_event_name", "hookEventName",
                               "event") or "")
    write_trace(root, {"phase": "enter", "event": hook_event,
                       "toolName": tool_name, "toolUseId": call_id})
    try:
        agent = detect_agent(event)
        actor = detect_actor(event)
        cmd = extract_cmd(event)
        if not cmd:
            payload = emit_payload(agent, False, "", "")
            write_trace(root, {"phase": "exit", "event": hook_event,
                               "toolName": tool_name, "toolUseId": call_id,
                               "decision": payload.get("decision"), "exit": 0,
                               "elapsedMs": int((time.time() - started) * 1000)})
            return payload, 0
        if "post" in hook_event.lower():
            out = extract_output(event)
            cause = cause_from_output(out)
            failed = bool(cause)
            recorded = []
            # Count only read-intent failures so create/search/tests do not
            # poison the retry ledger.
            if cmd_intent(cmd) == "read":
                for f in files_in_cmd(cmd):
                    h = classify_path(root, f)
                    rel = h.get("relative") or h.get("input")
                    if failed:
                        recorded.append(record(root, rel, "fail", cause, ledger,
                                               agent, actor, call_id))
                    else:
                        recorded.append(record(root, rel, "ok", cause or "ok",
                                               ledger, agent, actor, call_id))
            payload = emit_payload(agent, False, "", "")
            payload["recorded"] = recorded
            payload["result"] = "fail" if failed else "ok"
            write_trace(root, {"phase": "exit", "event": hook_event,
                               "toolName": tool_name, "toolUseId": call_id,
                               "decision": "allow", "exit": 0,
                               "elapsedMs": int((time.time() - started) * 1000)})
            return payload, 0
        verdict = verdict_for_cmd(root, cmd, ledger, agent, actor)
        if verdict["decision"] == "block":
            payload = emit_payload(agent, True, verdict["reason"],
                                   verdict["next"], verdict)
            write_trace(root, {"phase": "exit", "event": hook_event,
                               "toolName": tool_name, "toolUseId": call_id,
                               "decision": payload.get("decision"), "exit": 2,
                               "elapsedMs": int((time.time() - started) * 1000)})
            return payload, 2
        payload = emit_payload(agent, False, "", "", verdict)
        write_trace(root, {"phase": "exit", "event": hook_event,
                           "toolName": tool_name, "toolUseId": call_id,
                           "decision": payload.get("decision"), "exit": 0,
                           "elapsedMs": int((time.time() - started) * 1000)})
        return payload, 0
    except Exception as exc:
        write_trace(root, {"phase": "exit", "event": hook_event,
                           "toolName": tool_name, "toolUseId": call_id,
                           "decision": "allow", "exit": 1,
                           "elapsedMs": int((time.time() - started) * 1000)})
        return {"decision": "allow", "reason": "work-guard-error:%s"
                % type(exc).__name__}, 1


def advise(root: Path, findings: list, ledger=None) -> dict:
    """Classify watch findings. Does not write the live retry ledger —
    historical sessions must not block a recovered live tree."""
    stop, missing, doubled = [], [], []
    for finding in findings or []:
        ev = finding.get("evidence") or {}
        pattern = finding.get("pattern")
        paths = []
        if pattern == "P11":
            paths.extend((ev.get("paths") or {}).keys())
        if pattern == "P12":
            paths.extend((ev.get("files") or {}).keys())
        for raw in paths:
            h = classify_path(root, raw)
            rel = h.get("relative") or h.get("input")
            if h.get("class") == "doubled_src":
                doubled.append(rel)
            if h.get("class") == "missing":
                missing.append(rel)
                stop.append(rel)
            elif pattern == "P12" and not h.get("exists"):
                stop.append(rel)
    def uniq(seq):
        seen, out = set(), []
        for x in seq:
            k = (x or "").casefold()
            if x and k not in seen:
                seen.add(k)
                out.append(x)
        return out[:12]
    return {
        "schemaVersion": SCHEMA,
        "stopRetry": uniq(stop),
        "missing": uniq(missing),
        "doubledSrc": uniq(doubled),
        "recordedToLedger": False,
        "next": "hook uses live PostToolUse failures only; historical P12 does not poison the ledger",
    }


def hook_status(root: Path) -> dict:
    root = root.resolve()
    def read_json(rel):
        p = root / rel
        if not p.is_file():
            return {"present": False, "path": rel}
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            return {"present": True, "path": rel, "parse": type(exc).__name__}
        return {"present": True, "path": rel, "parse": "ok", "data": data}

    grok_trust = Path(os.environ.get("USERPROFILE") or "") / ".grok" / "trusted_folders.toml"
    trusted = False
    trust_reason = "trust-file-missing"
    if grok_trust.is_file():
        text = grok_trust.read_text(encoding="utf-8", errors="replace")
        key = "folders.'%s'" % str(root)
        alt = 'folders."%s"' % str(root)
        if key in text or alt in text:
            trusted = "trusted = true" in text or "trusted=true" in text
            trust_reason = "trusted" if trusted else "folder-listed-untrusted"
        else:
            trust_reason = "folder-not-listed"
    rows = {
        "grok": read_json(".grok/hooks/work-guard.json"),
        "codex": read_json(".codex/hooks.json"),
        "devin": read_json(".devin/hooks.v1.json"),
    }
    expected = {
        "grok": ("run_terminal_command", "Bash"),
        "codex": ("Bash",),
        "devin": ("exec",),
    }
    for agent, info in rows.items():
        data = info.get("data")
        matchers = []
        if isinstance(data, dict):
            hooks = data.get("hooks") if "hooks" in data else data
            for event in ("PreToolUse", "PostToolUse"):
                for group in (hooks or {}).get(event) or []:
                    if isinstance(group, dict) and group.get("matcher"):
                        matchers.append(group["matcher"])
        info["matchers"] = matchers
        info.pop("data", None)
        want = expected[agent]
        info["matcherCoversShell"] = any(
            any(w in m for w in want) for m in matchers)
        if agent == "grok":
            info["projectTrusted"] = trusted
            info["trustReason"] = trust_reason
            info["liveProof"] = "unverified"
            if not trusted:
                info["liveProof"] = "blocked-untrusted-folder"
        elif agent == "codex":
            info["liveProof"] = "unverified-until-/hooks-trust"
        else:
            info["liveProof"] = "unverified"
    return {
        "schemaVersion": SCHEMA,
        "generatedAt": utcnow(),
        "root": str(root),
        "officialShellToolName": AGENT_SHELL_TOOL,
        "hooks": rows,
        "note": "file presence is not runtime proof; Grok fail-open on timeout; Codex skips untrusted hook hashes",
    }


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=("root", "path", "check", "record",
                                      "hook", "advise", "status"))
    p.add_argument("--root", default=".")
    p.add_argument("--path", action="append", default=[])
    p.add_argument("--cmd")
    p.add_argument("--result", choices=("ok", "fail"))
    p.add_argument("--reason", default="")
    p.add_argument("--agent", default="unknown")
    p.add_argument("--actor", default="root")
    p.add_argument("--ledger")
    p.add_argument("--findings-file")
    a = p.parse_args(argv)
    root = Path(a.root).resolve()
    if a.action == "root":
        print(json.dumps(classify_root(root), ensure_ascii=True))
        return 0
    if a.action == "status":
        print(json.dumps(hook_status(root), ensure_ascii=True))
        return 0
    if a.action == "path":
        print(json.dumps({"paths": [classify_path(root, x) for x in a.path]},
                         ensure_ascii=True))
        return 0
    if a.action == "check":
        verdict = verdict_for_cmd(root, a.cmd or "", a.ledger, a.agent, a.actor)
        print(json.dumps(verdict, ensure_ascii=True))
        return 2 if verdict["decision"] == "block" else 0
    if a.action == "record":
        if not a.path or not a.result:
            print(json.dumps({"status": "error", "reason": "path-and-result-required"}))
            return 2
        print(json.dumps(record(root, a.path[0], a.result, a.reason or "other",
                                a.ledger, a.agent, a.actor), ensure_ascii=True))
        return 0
    if a.action == "advise":
        findings = []
        if a.findings_file:
            findings = json.loads(Path(a.findings_file).read_text(encoding="utf-8"))
        print(json.dumps(advise(root, findings, a.ledger), ensure_ascii=True))
        return 0
    raw = sys.stdin.read(65536)
    try:
        event = json.loads(raw) if raw.strip() else {}
    except ValueError:
        print(json.dumps({"decision": "allow", "reason": "hook-json-unreadable"}))
        return 1
    payload, code = hook(root, event, a.ledger)
    if code == 2:
        sys.stderr.write(payload.get("reason") or "work-guard-block")
    print(json.dumps(payload, ensure_ascii=True))
    return code


if __name__ == "__main__":
    sys.exit(main())
