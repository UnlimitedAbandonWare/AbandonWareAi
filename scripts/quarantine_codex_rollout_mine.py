#!/usr/bin/env python3
"""quarantine_codex_rollout_mine.py — streaming anti-pattern miner for
quarantined Codex rollout jsonl sessions.

Contract DEMO1-CLEAN-QUARANTINE-VIBE-AUTO-OPT-20260929 §A. Companion to
quarantine_seed_mine.py (which counts script/skill token references); this
miner classifies *behavioral* failure modes: done-without-evidence,
goal-read-as-done, quiz-spam, GATE-loop, stale child, token blowup, and
missing evidence markers (no test path, no GATE exit, no handoff dir).

Streaming contract: files are read line-by-line in binary mode; a rollout is
never loaded whole into memory and raw message text is never copied into the
report (counts + <=80-char normalized command heads only). Read-only on the
seed: originals are never modified, moved, or deleted.

Usage:
  python -B scripts/quarantine_codex_rollout_mine.py [--seed DIR|FILE ...]
      [--root .] [--max-bytes-per-file N] [--sample-lines N]
      [--out DIR] [--top N]

Exits: 0 scan completed (sessions may still carry flags), 2 usage/io error.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from collections import Counter
from pathlib import Path

from awx_paths import resolve as _awx_resolve

SCHEMA = "awx.quarantine-codex-rollout-mine.v1"
DEFAULT_SEED = str(_awx_resolve("rescue.root") / "codex-quarantine-9only-20260919")
DEFAULT_OUT = "data/agent-handoff/clean-quarantine-opt-20260929"
CMD_HEAD = 80

# --- per-line signals -------------------------------------------------------
RE_EXEC_CMD = re.compile(r'"cmd"\s*:\s*"((?:[^"\\]|\\.){1,200})')
RE_ASSISTANT = re.compile(r'"role"\s*:\s*"assistant"')
RE_TS = re.compile(r'"timestamp"\s*:\s*"([^"]+)"')
RE_PLUGINS = re.compile(r'recommended_plugins|@openai-curated-remote')

# behavior markers (assistant/user message text or exec input)
RE_DONE_CLAIM = re.compile(
    r"\b(Done|complete[ds]?|finished|verified|all set|완료|끝)\b", re.I)
RE_QUIZ = re.compile(
    r"(승인|approval|\b1\)\s|\b2\)\s|choose an? option|select an option|"
    r"proceed\?|which (?:option|approach)|어느\s*것|골라)", re.I)
RE_GATE = re.compile(r"\bGATE\b|verificationExitCode|exit 0")
RE_GOAL_READ = re.compile(
    r"goal[-_ ]?objective|goal[^\n\"]{0,40}\.md|목표\s*파일|read the goal", re.I)
RE_TEST_CMD = re.compile(
    r"(--tests\b|pytest|gradlew[^\n\"]*\btest\b|_tests?\.ps1|verify[_-]|"
    r"run_verified_command|Verify-RAG|npm\s+(?:run\s+)?test)", re.I)
RE_HANDOFF = re.compile(
    r"agent-handoff|FOR_CODEX|FOR_DEVIN|work_journal|journal\.json")
RE_AUTH_CLAIM = re.compile(
    r"(admin|login|auth)[^\n\"]{0,60}(verified|pass|works|green|done)|"
    r"(verified|pass|works)[^\n\"]{0,60}(admin|login|auth)", re.I)
RE_DUP_SERVICE = re.compile(
    r"new service|duplicate[^\n\"]{0,30}service|reimplement[^\n\"]{0,30}"
    r"(service|job)|대체\s*서비스|another\s+(?:service|job\s*service)", re.I)

STOP_RULES = (
    "goal-read-as-done", "done-no-evidence", "quiz-spam", "gate-loop",
    "child-stale-no-evidence", "token-blowup", "plugin-sprawl",
    "auth-claim-no-proof", "dup-service-suspect",
)

QUIZ_SPAM_MIN_LINES = 8           # or density > 10 / 1k lines
GATE_LOOP_MIN = 20                # GATE-ish churn without exit evidence
PLUGIN_SPRAWL_MIN = 3             # distinct recommended-plugin blocks
TOKEN_BLOWUP_BYTES = 50 * 1024 * 1024
TOKEN_BLOWUP_TOTAL = 200_000      # single-turn total_tokens spike


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def iter_targets(seeds: list[str]) -> list[Path]:
    out: list[Path] = []
    for s in seeds:
        p = Path(s)
        if p.is_file() and p.suffix == ".jsonl":
            out.append(p)
        elif p.is_dir():
            out += sorted(p.rglob("*.jsonl"))
    return [p for p in dict.fromkeys(out) if not p.name.startswith("apply-")]


def load_manifest(seed_dirs: list[str]) -> dict[str, dict]:
    rows: dict[str, dict] = {}
    for s in seed_dirs:
        p = Path(s)
        base = p if p.is_dir() else p.parent
        for manifest in sorted(base.rglob("apply-*.jsonl")):
            try:
                fh = manifest.open("r", encoding="utf-8", errors="replace")
            except OSError:
                continue
            with fh:
                for line in fh:
                    try:
                        row = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if isinstance(row, dict) and row.get("id"):
                        rows[row["id"]] = row
    return rows


def session_id_of(path: Path) -> str:
    m = re.search(r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
                  r"[0-9a-f]{12})", path.name)
    return m.group(1) if m else path.stem


# Secret redaction (contract DEMO1-DEVIN-QUARANTINE-ANTIPATTERN-RAILS-20260929
# §1): report text is counts + normalized heads only, but a command head can
# still carry a credential in its arguments. Mask token shapes first (a Bearer
# token must die even when nested inside an auth header field), then
# name=value fields, always before the 80-char truncation so a value cut
# mid-way cannot leak its head bytes.
RE_SECRET_VALUE = re.compile(
    r"(sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{8,}|gsk_[A-Za-z0-9_-]{8,}|"
    r"AKIA[0-9A-Z]{8,}|Bearer\s+[A-Za-z0-9._~+/=-]{8,})",
    re.I)
RE_SECRET_FIELD = re.compile(
    r"((?:api[-_.]?key|token|secret|password|passwd|pwd|authorization|"
    r"cookie)\s*[:=]\s*)(?:Bearer\s+)?[^\s\"',;}]{4,}",
    re.I)


def redact(text: str | None) -> str:
    if not text:
        return ""
    masked = RE_SECRET_VALUE.sub("[REDACTED]", str(text))
    return RE_SECRET_FIELD.sub(r"\1[REDACTED]", masked)


def cmd_head(raw: str) -> str:
    head = re.sub(r"\s+", " ", redact(raw)).strip()[:CMD_HEAD]
    return head


def mine_file(path: Path, max_bytes: int, sample_lines: int) -> dict:
    st = {
        "lines": 0, "bytesRead": 0, "truncated": False,
        "typeHist": Counter(), "toolHist": Counter(), "cmdHist": Counter(),
        "quizLines": 0, "doneClaimLines": 0, "gateLines": 0,
        "goalReadLines": 0, "testCmdLines": 0, "handoffLines": 0,
        "authClaimLines": 0, "dupServiceLines": 0, "pluginLines": 0,
        "taskStarted": 0, "taskComplete": 0, "assistantMsgs": 0,
        "tokenRecords": 0, "maxTurnTokens": 0, "lastThreadTokens": 0,
        "compacted": 0, "isChild": False, "childNick": None,
        "firstTs": None, "lastTs": None, "parseErrors": 0,
    }
    size = path.stat().st_size
    limit = size if not max_bytes else min(size, max_bytes)
    with path.open("rb") as fh:
        for raw in fh:
            if st["bytesRead"] >= limit:
                st["truncated"] = True
                break
            if sample_lines and st["lines"] >= sample_lines:
                st["truncated"] = True
                break
            st["lines"] += 1
            st["bytesRead"] += len(raw)
            text = raw.decode("utf-8", errors="replace")

            row_type = None
            payload = None
            if '"type"' in text:
                try:
                    row = json.loads(text)
                    row_type = row.get("type")
                    payload = row.get("payload") or {}
                except json.JSONDecodeError:
                    st["parseErrors"] += 1
                    payload = {}
            else:
                st["parseErrors"] += 1
                payload = {}
            if row_type:
                st["typeHist"][row_type] += 1
                if row_type == "session_meta" and isinstance(payload, dict):
                    src = payload.get("source") or {}
                    if isinstance(src, dict) and src.get("subagent"):
                        st["isChild"] = True
                        spawn = (src.get("subagent") or {}).get(
                            "thread_spawn") or {}
                        st["childNick"] = redact(
                            spawn.get("agent_nickname")) or None
                if row_type == "token_usage_record" and isinstance(
                        payload, dict):
                    st["tokenRecords"] += 1
                    use = payload.get("usage") or {}
                    if isinstance(use, dict):
                        st["maxTurnTokens"] = max(
                            st["maxTurnTokens"],
                            int(use.get("total_tokens") or 0))
                    tuse = payload.get("thread_token_usage") or {}
                    if isinstance(tuse, dict) and tuse.get("total_tokens"):
                        st["lastThreadTokens"] = int(tuse["total_tokens"])
                if row_type == "compacted":
                    st["compacted"] += 1
                if row_type == "event_msg" and isinstance(payload, dict):
                    pt = payload.get("type")
                    if pt == "task_started":
                        st["taskStarted"] += 1
                    elif pt == "task_complete":
                        st["taskComplete"] += 1
                if row_type == "response_item" and isinstance(payload, dict):
                    pt = payload.get("type")
                    if pt in ("function_call", "custom_tool_call",
                              "local_shell_call"):
                        name = payload.get("name") or "?"
                        st["toolHist"][name] += 1
                        inp = payload.get("input")
                        if isinstance(inp, str):
                            m = RE_EXEC_CMD.search(inp)
                            if m:
                                st["cmdHist"][cmd_head(m.group(1))] += 1
                            elif name == "exec":
                                st["cmdHist"][cmd_head(inp)] += 1
                    if pt == "message" and payload.get("role") == "assistant":
                        st["assistantMsgs"] += 1

            # signal classification: dev/user template lines carry tool-spec
            # boilerplate ("test", "verify", "duplicate") that fakes evidence;
            # only assistant messages and tool calls/outputs count.
            sig = None
            if row_type == "response_item" and isinstance(payload, dict):
                pt = payload.get("type")
                if pt in ("function_call", "custom_tool_call",
                          "local_shell_call", "function_call_output",
                          "custom_tool_call_output"):
                    sig = "tool"
                elif pt == "message" and payload.get("role") == "assistant":
                    sig = "assistant"
            if RE_QUIZ.search(text) and sig == "assistant":
                st["quizLines"] += 1
            if sig == "assistant" and RE_DONE_CLAIM.search(text):
                st["doneClaimLines"] += 1
            if sig == "assistant" and RE_AUTH_CLAIM.search(text):
                st["authClaimLines"] += 1
            if sig in ("tool", "assistant"):
                if RE_GATE.search(text):
                    st["gateLines"] += 1
                if RE_GOAL_READ.search(text):
                    st["goalReadLines"] += 1
                if RE_TEST_CMD.search(text):
                    st["testCmdLines"] += 1
                if RE_HANDOFF.search(text):
                    st["handoffLines"] += 1
                if RE_DUP_SERVICE.search(text):
                    st["dupServiceLines"] += 1
            if RE_PLUGINS.search(text):
                st["pluginLines"] += 1
            m = RE_TS.search(text)
            if m:
                if not st["firstTs"]:
                    st["firstTs"] = m.group(1)
                st["lastTs"] = m.group(1)

    ev_test = st["testCmdLines"] > 0
    ev_gate = st["gateLines"] > 0
    ev_handoff = st["handoffLines"] > 0
    done_no_ev = (st["doneClaimLines"] > 0
                  and not ev_test and not ev_gate)
    quiz_density = (st["quizLines"] * 1000 // st["lines"]) if st["lines"] else 0
    reasons = []
    if st["goalReadLines"] and done_no_ev:
        reasons.append("goal-read-as-done")
    if done_no_ev:
        reasons.append("done-no-evidence")
    if st["quizLines"] >= QUIZ_SPAM_MIN_LINES or quiz_density > 10:
        reasons.append("quiz-spam")
    if st["gateLines"] >= GATE_LOOP_MIN and not ev_gate:
        reasons.append("gate-loop")
    if st["isChild"] and (st["taskStarted"] > st["taskComplete"]
                          or (not ev_test and not ev_gate
                              and not ev_handoff)):
        reasons.append("child-stale-no-evidence")
    if (size >= TOKEN_BLOWUP_BYTES or st["maxTurnTokens"] >= TOKEN_BLOWUP_TOTAL
            or st["compacted"] >= 2):
        reasons.append("token-blowup")
    if st["pluginLines"] >= PLUGIN_SPRAWL_MIN:
        reasons.append("plugin-sprawl")
    if st["authClaimLines"] and not ev_gate:
        reasons.append("auth-claim-no-proof")
    if st["dupServiceLines"] >= 5:
        reasons.append("dup-service-suspect")

    return {
        "file": str(path), "sessionId": session_id_of(path),
        "bytes": size, "bytesRead": st["bytesRead"],
        "truncated": st["truncated"], "lines": st["lines"],
        "parseErrors": st["parseErrors"],
        "isChild": st["isChild"], "childNick": st["childNick"],
        "taskStarted": st["taskStarted"], "taskComplete": st["taskComplete"],
        "assistantMsgs": st["assistantMsgs"],
        "firstTs": st["firstTs"], "lastTs": st["lastTs"],
        "quizLines": st["quizLines"], "quizPerK": quiz_density,
        "doneClaimLines": st["doneClaimLines"],
        "gateLines": st["gateLines"], "goalReadLines": st["goalReadLines"],
        "testCmdLines": st["testCmdLines"],
        "handoffLines": st["handoffLines"],
        "authClaimLines": st["authClaimLines"],
        "dupServiceLines": st["dupServiceLines"],
        "pluginLines": st["pluginLines"],
        "tokenRecords": st["tokenRecords"],
        "maxTurnTokens": st["maxTurnTokens"],
        "lastThreadTokens": st["lastThreadTokens"],
        "compacted": st["compacted"],
        "evidence": {"test": ev_test, "gate": ev_gate,
                     "handoff": ev_handoff},
        "stopReasons": reasons,
        "typeHist": dict(st["typeHist"].most_common(12)),
        "topTools": st["toolHist"].most_common(10),
        "topCmds": st["cmdHist"].most_common(10),
    }


def render_md(result: dict) -> str:
    L = ["# Quarantine rollout anti-pattern mine", "",
         f"- seeds: `{'; '.join(result['seeds'])}`",
         f"- sessions scanned: {len(result['sessions'])} "
         "(streamed line-by-line; no whole-file load)",
         f"- generatedAtUtc: {result['generatedAtUtc']}", "",
         "| session | MB | lines | child | quiz/1k | done-claims | "
         "evidence | stop reasons |", "|---|---|---|---|---|---|---|---|"]
    for s in result["sessions"]:
        L.append(
            f"| `{s['sessionId'][:13]}` | {s['bytes']/1e6:.1f} | {s['lines']} | "
            f"{'Y' if s['isChild'] else '-'} | {s['quizPerK']} | "
            f"{s['doneClaimLines']} | "
            f"{''.join(k[0].upper() for k, v in s['evidence'].items() if v) or '-'} | "
            f"{', '.join(s['stopReasons']) or '-'} |")
    L += ["", "## Stop-reason totals", "",
          "| stop reason | sessions |", "|---|---|"]
    for reason, n in result["totals"]["stopReasons"].items():
        L.append(f"| {reason} | {n} |")
    L += ["", "## Top tool calls", "", "| tool | calls |", "|---|---|"]
    for name, n in result["totals"]["tools"]:
        L.append(f"| `{name}` | {n} |")
    L += ["", "## Top exec command heads (normalized, <=80 chars)", "",
          "| command head | calls |", "|---|---|"]
    for head, n in result["totals"]["cmds"]:
        L.append(f"| `{head}` | {n} |")
    L += ["", "Evidence letters: T=test-cmd, G=gate+exit, H=handoff/journal.",
          "Quiz/1k = approval-quiz prompt lines per 1000 rollout lines "
          "(assistant + tool prompts, smoke-level).",
          "Raw message text is never copied; only counts and normalized "
          "command heads are emitted; obvious secret shapes are masked "
          "as [REDACTED] before emission.", ""]
    return "\n".join(L)


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--seed", action="append", default=None,
                    help="quarantine dir or rollout jsonl; repeatable")
    ap.add_argument("--root", default=".")
    ap.add_argument("--max-bytes-per-file", type=int, default=0,
                    help="0 = stream whole file; >0 caps bytes read per file")
    ap.add_argument("--sample-lines", type=int, default=0,
                    help="0 = all lines; >0 stops after N lines per file")
    ap.add_argument("--out", default=DEFAULT_OUT,
                    help="output dir for mine-report.json + ANTIPATTERNS.md")
    ap.add_argument("--top", type=int, default=15)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    seeds = args.seed or [DEFAULT_SEED]
    targets = iter_targets(seeds)
    if not targets:
        print(json.dumps({"schemaVersion": SCHEMA, "error": "no-rollouts",
                          "seeds": seeds}))
        return 2
    manifest = load_manifest(seeds)

    sessions = []
    tool_tot: Counter[str] = Counter()
    cmd_tot: Counter[str] = Counter()
    stop_tot: Counter[str] = Counter()
    for path in targets:
        try:
            info = mine_file(path, args.max_bytes_per_file, args.sample_lines)
        except OSError as exc:
            sessions.append({"file": str(path),
                             "sessionId": session_id_of(path),
                             "error": type(exc).__name__})
            continue
        meta = manifest.get(info["sessionId"], {})
        if meta:
            info["manifestClass"] = meta.get("class")
            info["manifestReason"] = meta.get("reason")
            info["shaPreserved"] = (meta.get("pre_sha256")
                                    == meta.get("post_sha256"))
        sessions.append(info)
        for name, n in info["topTools"]:
            tool_tot[name] += n
        for head, n in info["topCmds"]:
            cmd_tot[head] += n
        for r in info["stopReasons"]:
            stop_tot[r] += 1

    result = {
        "schemaVersion": SCHEMA,
        "generatedAtUtc": utcnow(),
        "seeds": seeds,
        "bounds": {"maxBytesPerFile": args.max_bytes_per_file,
                   "sampleLines": args.sample_lines},
        "sessions": sessions,
        "totals": {
            "sessions": len(sessions),
            "bytes": sum(s.get("bytes", 0) for s in sessions),
            "stopReasons": dict(stop_tot.most_common()),
            "tools": tool_tot.most_common(args.top),
            "cmds": cmd_tot.most_common(args.top),
        },
    }

    out_dir = Path(args.root).resolve() / args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "mine-report.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_dir / "ANTIPATTERNS.md").write_text(render_md(result),
                                           encoding="utf-8")
    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print(f"sessions={len(sessions)} "
              f"stopReasons={dict(stop_tot.most_common())}")
        print(f"wrote {out_dir / 'mine-report.json'} + ANTIPATTERNS.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
