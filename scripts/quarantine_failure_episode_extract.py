#!/usr/bin/env python3
"""quarantine_failure_episode_extract.py — streaming quarantine rollout ->
small *failure episode* JSONL packets for agent strengthening.

Contract DEMO1-DEVIN-QUARANTINE-AGENT-GRAFT-20260929 §A. The quarantine store
(C:\\AbandonWare\\_rescue\\codex-quarantine-9only-20260919, ~154MB of Codex
rollout jsonl) is a behavioral failure dataset, not an answer corpus. This
tool extracts bounded episodes (<=~4KB each) with signal counts and a mapped
guard rail — never raw prompts, never message bodies, never secrets.

Reuses quarantine_codex_rollout_mine.py signal regexes/thresholds and its
manifest loader (import; local fallbacks if the module moves). Streaming
contract: one pass per file, line-by-line; nothing is loaded whole.

Usage:
  python -B scripts/quarantine_failure_episode_extract.py [--seed DIR|FILE ...]
      [--root .] [--out DIR] [--max-episodes N] [--max-per-file N]
      [--max-bytes-per-file N] [--sample-lines N] [--catalog PATH] [--json]

Defaults: seed = the 20260919 quarantine dir; out =
data/agent-handoff/quarantine-agent-graft-20260929/episodes
(episodes-NNNN.jsonl, <= --max-per-file each) + _index.json summary.

Exits: 0 extracted, 2 usage/io error, 3 catalog unreadable.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:  # reuse the sibling miner's compiled signals where available
    import quarantine_codex_rollout_mine as _qm
except Exception:  # pragma: no cover - fallback keeps extractor standalone
    _qm = None
from awx_paths import resolve as _awx_resolve

SCHEMA = "awx.quarantine-failure-episode.v1"
DEFAULT_SEED = str(_awx_resolve("rescue.root") / "codex-quarantine-9only-20260919")
DEFAULT_OUT = "data/agent-handoff/quarantine-agent-graft-20260929/episodes"
DEFAULT_CATALOG = "configs/agent-failure-mode-catalog.yaml"
CMD_HEAD = 80
EPISODE_MAX_BYTES = 4096

# --- signal regexes (fallbacks mirror quarantine_codex_rollout_mine) ---------
def _rx(name: str, fallback: str, flags: int = 0) -> re.Pattern:
    pat = getattr(_qm, name, None)
    if isinstance(pat, re.Pattern):
        return pat
    return re.compile(fallback, flags)


RE_EXEC_CMD = _rx("RE_EXEC_CMD", r'"cmd"\s*:\s*"((?:[^"\\]|\\.){1,200})')
RE_ASSISTANT = _rx("RE_ASSISTANT", r'"role"\s*:\s*"assistant"')
RE_TS = _rx("RE_TS", r'"timestamp"\s*:\s*"([^"]+)"')
RE_QUIZ = _rx("RE_QUIZ",
              r"(승인|approval|\b1\)\s|\b2\)\s|choose an? option|select an option|"
              r"proceed\?|which (?:option|approach)|어느\s*것|골라)", re.I)
RE_DONE_CLAIM = _rx(
    "RE_DONE_CLAIM",
    r"\b(Done|complete[ds]?|finished|verified|all set|완료|끝)\b", re.I)
RE_GATE = _rx("RE_GATE", r"\bGATE\b|verificationExitCode|exit_code|exit 0")
RE_GOAL_READ = _rx(
    "RE_GOAL_READ",
    r"goal[-_ ]?objective|goal[^\n\"]{0,40}\.md|목표\s*파일|read the goal", re.I)
RE_TEST_CMD = _rx(
    "RE_TEST_CMD",
    r"(--tests\b|pytest|gradlew[^\n\"]*\btest\b|_tests?\.ps1|verify[_-]|"
    r"run_verified_command|Verify-RAG|npm\s+(?:run\s+)?test)", re.I)
RE_HANDOFF = _rx("RE_HANDOFF",
                 r"agent-handoff|FOR_CODEX|FOR_DEVIN|work_journal|journal\.json")
RE_PLUGINS = _rx("RE_PLUGINS", r"recommended_plugins|@openai-curated-remote")

QUIZ_SPAM_MIN_LINES = getattr(_qm, "QUIZ_SPAM_MIN_LINES", 8)
GATE_LOOP_MIN = getattr(_qm, "GATE_LOOP_MIN", 20)
PLUGIN_SPRAWL_MIN = getattr(_qm, "PLUGIN_SPRAWL_MIN", 3)
TOKEN_BLOWUP_BYTES = getattr(_qm, "TOKEN_BLOWUP_BYTES", 50 * 1024 * 1024)
TOKEN_BLOWUP_TOTAL = getattr(_qm, "TOKEN_BLOWUP_TOTAL", 200_000)

# errorish = tool outputs that failed (envelope-level markers only, bounded)
FAIL_OUTPUT_MARKERS = (
    "Script failed", "Script error", "Cannot find path",
    "Could not find a part of the path", "CreateProcess", "ParserError",
    '"isError":true',
)

# --- secret masking ----------------------------------------------------------
SECRET_RES = (
    re.compile(r"sk-[A-Za-z0-9_\-]{8,}"),
    re.compile(r"(?i)bearer\s+[A-Za-z0-9._\-]{8,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"(?i)(api[_-]?key|token|secret|password|passwd|credential)"
               r"([\s'\"]*[:=][\s'\"]*)([^\s\"'`;,]{4,})"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
)


def redact(text: str) -> str:
    out = text
    for i, rx in enumerate(SECRET_RES):
        if i == 3:
            out = rx.sub(lambda m: m.group(1) + m.group(2) + "<redacted>", out)
        else:
            out = rx.sub("<redacted>", out)
    return out


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def iter_targets(seeds: list[str]) -> list[Path]:
    if _qm is not None and hasattr(_qm, "iter_targets"):
        return _qm.iter_targets(seeds)
    out: list[Path] = []
    for s in seeds:
        p = Path(s)
        if p.is_file() and p.suffix == ".jsonl":
            out.append(p)
        elif p.is_dir():
            out += sorted(p.rglob("*.jsonl"))
    return [p for p in dict.fromkeys(out) if not p.name.startswith("apply-")]


def load_manifest(seeds: list[str]) -> dict[str, dict]:
    if _qm is not None and hasattr(_qm, "load_manifest"):
        return _qm.load_manifest(seeds)
    rows: dict[str, dict] = {}
    for s in seeds:
        base = Path(s)
        base = base if base.is_dir() else base.parent
        for manifest in sorted(base.rglob("apply-*.jsonl")):
            try:
                for line in manifest.read_text(
                        encoding="utf-8", errors="replace").splitlines():
                    try:
                        row = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if isinstance(row, dict) and row.get("id"):
                        rows[row["id"]] = row
            except OSError:
                continue
    return rows


def session_id_of(path: Path) -> str:
    if _qm is not None and hasattr(_qm, "session_id_of"):
        return _qm.session_id_of(path)
    m = re.search(r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
                  r"[0-9a-f]{12})", path.name)
    return m.group(1) if m else path.stem


def manifest_title(row: dict) -> tuple[str | None, str]:
    """Title lives in the codex state DB; the quarantine manifest embeds it in
    'reason' as `title=<text>`. Returns (title, status)."""
    reason = str(row.get("reason") or "")
    m = re.search(r"title=(.*)$", reason)
    if not m:
        return None, "not_observed"
    return m.group(1).strip(), "observed"


def scan_rollout(path: Path, max_bytes: int, sample_lines: int) -> dict:
    st = {
        "lines": 0, "bytesRead": 0, "truncated": False,
        "toolCalls": 0, "customToolCall": 0, "functionCall": 0,
        "localShellCall": 0, "errorish": 0, "askish": 0,
        "doneClaims": 0, "gateLines": 0, "goalReadLines": 0,
        "testCmdLines": 0, "handoffLines": 0, "pluginLines": 0,
        "taskStarted": 0, "taskComplete": 0, "userMessages": 0,
        "assistantMsgs": 0, "compacted": 0, "tokenRecords": 0,
        "maxTurnTokens": 0, "lastThreadTokens": 0,
        "isChild": False, "childNick": None, "childRole": None,
        "firstTs": None, "lastTs": None, "parseErrors": 0,
        "tools": Counter(), "cmds": Counter(), "sid": None,
    }
    try:
        size = path.stat().st_size
    except OSError:
        size = 0
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

            if '"type"' in text:
                try:
                    row = json.loads(text)
                except json.JSONDecodeError:
                    row, payload = None, {}
                    st["parseErrors"] += 1
                else:
                    payload = row.get("payload") or {}
                    rt = row.get("type")
                    if rt == "session_meta" and isinstance(payload, dict):
                        st["sid"] = payload.get("id") or \
                            payload.get("session_id")
                        src = payload.get("source") or {}
                        sub = src.get("subagent") if isinstance(src, dict) \
                            else None
                        if sub or payload.get("parent_thread_id") or \
                                payload.get("thread_source") == "subagent":
                            st["isChild"] = True
                        if isinstance(sub, dict):
                            spawn = sub.get("thread_spawn") or {}
                            st["childNick"] = spawn.get("agent_nickname") or \
                                payload.get("agent_nickname")
                            st["childRole"] = spawn.get("agent_role") or \
                                payload.get("agent_role")
                    elif rt == "event_msg" and isinstance(payload, dict):
                        pt = payload.get("type")
                        if pt == "task_started":
                            st["taskStarted"] += 1
                        elif pt == "task_complete":
                            st["taskComplete"] += 1
                    elif rt == "compacted":
                        st["compacted"] += 1
                    elif rt == "token_usage_record" and isinstance(
                            payload, dict):
                        st["tokenRecords"] += 1
                        use = payload.get("usage") or {}
                        if isinstance(use, dict):
                            st["maxTurnTokens"] = max(
                                st["maxTurnTokens"],
                                int(use.get("total_tokens") or 0))
                        tuse = payload.get("thread_token_usage") or {}
                        if isinstance(tuse, dict) and tuse.get("total_tokens"):
                            st["lastThreadTokens"] = int(
                                tuse["total_tokens"])
                    elif rt == "response_item" and isinstance(payload, dict):
                        pt = payload.get("type")
                        if pt in ("function_call", "custom_tool_call",
                                  "local_shell_call"):
                            st["toolCalls"] += 1
                            if pt == "custom_tool_call":
                                st["customToolCall"] += 1
                            elif pt == "function_call":
                                st["functionCall"] += 1
                            else:
                                st["localShellCall"] += 1
                            name = payload.get("name") or "?"
                            st["tools"][name] += 1
                            inp = payload.get("input")
                            if isinstance(inp, str):
                                m = RE_EXEC_CMD.search(inp)
                                if m:
                                    st["cmds"][cmd_head(m.group(1))] += 1
                                elif name == "exec":
                                    st["cmds"][cmd_head(inp)] += 1
                        elif pt in ("custom_tool_call_output",
                                    "function_call_output",
                                    "local_shell_call_output"):
                            out = payload.get("output")
                            blob = out if isinstance(out, str) else \
                                json.dumps(out, ensure_ascii=False)[:4000]
                            if any(mk in blob for mk in FAIL_OUTPUT_MARKERS):
                                st["errorish"] += 1
                        elif pt == "message":
                            if payload.get("role") == "assistant":
                                st["assistantMsgs"] += 1
                            elif payload.get("role") == "user":
                                st["userMessages"] += 1
            else:
                st["parseErrors"] += 1

            # raw-text markers (cheap substring/regex, independent of parse)
            if RE_QUIZ.search(text) and RE_ASSISTANT.search(text):
                st["askish"] += 1
            if RE_DONE_CLAIM.search(text):
                st["doneClaims"] += 1
            if RE_GATE.search(text):
                st["gateLines"] += 1
            if RE_GOAL_READ.search(text):
                st["goalReadLines"] += 1
            if RE_TEST_CMD.search(text):
                st["testCmdLines"] += 1
            if RE_HANDOFF.search(text):
                st["handoffLines"] += 1
            if RE_PLUGINS.search(text):
                st["pluginLines"] += 1
            m = RE_TS.search(text)
            if m:
                if not st["firstTs"]:
                    st["firstTs"] = m.group(1)
                st["lastTs"] = m.group(1)

    try:
        st["mtimeUtc"] = dt.datetime.fromtimestamp(
            path.stat().st_mtime, dt.timezone.utc).isoformat(
            timespec="seconds")
    except OSError:
        st["mtimeUtc"] = None
    st["size"] = size
    return st


def cmd_head(raw: str) -> str:
    return re.sub(r"\s+", " ", raw).strip()[:CMD_HEAD]


def classify(st: dict) -> list[str]:
    """Stop patterns — same thresholds as quarantine_codex_rollout_mine."""
    ev_test = st["testCmdLines"] > 0
    ev_gate = st["gateLines"] > 0 and (
        st["testCmdLines"] > 0 or st["taskComplete"] > 0)
    ev_handoff = st["handoffLines"] > 0
    done_no_ev = st["doneClaims"] > 0 and not ev_test and not ev_gate
    quiz_density = (st["askish"] * 1000 // st["lines"]) if st["lines"] else 0
    reasons: list[str] = []
    if st["goalReadLines"] and done_no_ev:
        reasons.append("goal-read-as-done")
    if done_no_ev:
        reasons.append("done-no-evidence")
    if st["askish"] >= QUIZ_SPAM_MIN_LINES or quiz_density > 10:
        reasons.append("quiz-spam")
    if st["gateLines"] >= GATE_LOOP_MIN and not ev_gate:
        reasons.append("gate-loop")
    if st["isChild"] and (st["taskStarted"] > st["taskComplete"]
                          or (not ev_test and not ev_gate and not ev_handoff)):
        reasons.append("child-stale-no-evidence")
    if (st["size"] >= TOKEN_BLOWUP_BYTES
            or st["maxTurnTokens"] >= TOKEN_BLOWUP_TOTAL
            or st["compacted"] >= 2):
        reasons.append("token-blowup")
    if st["pluginLines"] >= PLUGIN_SPRAWL_MIN:
        reasons.append("plugin-sprawl")
    if st["errorish"] >= 4:
        reasons.append("error-streak")
    if not reasons and (st["taskStarted"] > st["taskComplete"]):
        reasons.append("stale-incomplete")
    return reasons


# stop_pattern -> existing rail (kept in sync with
# configs/agent-failure-mode-catalog.yaml modes[].id / .graft)
RAIL_MAP = {
    "goal-read-as-done": "demo1-codex-goal-intake-continue + "
                         "demo1_goal_switch_barrier.py reject-complete",
    "done-no-evidence": "scripts/agent_done_evidence_guard.py "
                        "(work_pipeline done-check)",
    "quiz-spam": "scripts/agent_vibe_auto_decision.py / "
                 "demo1-vibe-selfask-judge-auto (AUTO for reversible)",
    "gate-loop": "scripts/run_verified_command.py + agent_work_guard.py "
                 "retry brake",
    "child-stale-no-evidence": "scripts/agent_session_watch.py "
                              "(P9 stale + child/empty-title flags)",
    "token-blowup": "episode-extract bounds + demo1-agent-api-spend-guard; "
                    "raw rollout paste forbidden",
    "plugin-sprawl": "demo1-codex-plugin-roles",
    "error-streak": "agent_session_watch P4/P11 + agent_work_guard advise",
    "stale-incomplete": "agent_session_watch P9 + work_journal close",
    "unclassified": "catalog review (docs/agent-learning/"
                    "quarantine-failure-modes-20260929.md)",
}


def load_rail_map(root: Path, catalog_path: str | None) -> dict[str, str]:
    """Prefer the catalog YAML SSOT; fall back to the built-in map."""
    path = Path(catalog_path) if catalog_path else root / DEFAULT_CATALOG
    if not path.is_file():
        return dict(RAIL_MAP)
    try:
        import yaml
        doc = yaml.safe_load(path.read_text(encoding="utf-8-sig"))
    except Exception as exc:
        print(json.dumps({"schemaVersion": SCHEMA,
                          "error": "catalog-unreadable",
                          "detail": type(exc).__name__}))
        raise SystemExit(3)
    out = dict(RAIL_MAP)
    for mode in (doc or {}).get("modes") or []:
        rail = mode.get("graft") or mode.get("rail")
        for sp in mode.get("stop_patterns") or []:
            if rail:
                out[sp] = str(rail)
    return out


def episode_for(path: Path, st: dict, meta: dict, rail_map: dict,
                seq: int) -> dict:
    sid = st.get("sid") or session_id_of(path)
    stop_patterns = classify(st)
    primary = meta.get("class") or (stop_patterns[0] if stop_patterns
                                  else "unclassified")
    title, title_status = manifest_title(meta)
    ev_test = st["testCmdLines"] > 0
    ev_gate = st["gateLines"] > 0 and (
        st["testCmdLines"] > 0 or st["taskComplete"] > 0)
    ep = {
        "schemaVersion": SCHEMA,
        "id": f"ep-{sid[:8]}-{seq:04d}",
        "session_id": sid,
        "class": primary,
        "stop_pattern": stop_patterns[0] if stop_patterns else primary,
        "stop_patterns": stop_patterns,
        "signals": {
            "lines": st["lines"], "bytes": st["size"],
            "toolCalls": st["toolCalls"],
            "custom_tool_call": st["customToolCall"],
            "function_call": st["functionCall"],
            "errorish": st["errorish"], "askish": st["askish"],
            "doneClaims": st["doneClaims"], "gateLines": st["gateLines"],
            "goalReadLines": st["goalReadLines"],
            "testCmdLines": st["testCmdLines"],
            "handoffLines": st["handoffLines"],
            "pluginLines": st["pluginLines"],
            "taskStarted": st["taskStarted"],
            "taskComplete": st["taskComplete"],
            "userMessages": st["userMessages"],
            "assistantMsgs": st["assistantMsgs"],
            "compacted": st["compacted"],
            "maxTurnTokens": st["maxTurnTokens"],
            "lastThreadTokens": st["lastThreadTokens"],
            "parseErrors": st["parseErrors"],
        },
        "evidence": {"test": ev_test, "gate": ev_gate,
                     "handoff": st["handoffLines"] > 0},
        "child": {"isChild": st["isChild"], "nick": st["childNick"],
                  "role": st["childRole"]},
        "window": {"firstTs": st["firstTs"], "lastTs": st["lastTs"],
                   "mtimeUtc": st["mtimeUtc"]},
        "title": title, "titleStatus": title_status,
        "titleEmpty": (title == "") if title_status == "observed" else None,
        "topTools": st["tools"].most_common(6),
        "topCmds": [redact(h) for h, _n in
                    st["cmds"].most_common(6)],
        "recommended_rail": next(
            (rail_map[k] for k in [primary] + stop_patterns
             if k in rail_map), rail_map["unclassified"]),
        "manifest": {"class": meta.get("class"),
                     "basis": meta.get("basis"),
                     "shaPreserved": meta.get("pre_sha256")
                     == meta.get("post_sha256")
                     if meta.get("pre_sha256") else None},
        "source": {"file": path.name, "bytesRead": st["bytesRead"],
                   "truncated": st["truncated"]},
        "redact_secrets": True,
    }
    # hard bound: keep episodes small; drop least-important bulk first
    while len(json.dumps(ep, ensure_ascii=False)) > EPISODE_MAX_BYTES:
        if ep["topCmds"]:
            ep["topCmds"].pop()
        elif len(ep["topTools"]) > 3:
            ep["topTools"].pop()
        else:
            ep["topCmds"] = []
            ep["topTools"] = ep["topTools"][:1]
            break
    return ep


def emit(episodes: list[dict], out_dir: Path, max_per_file: int) -> list[dict]:
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for start in range(0, len(episodes), max_per_file):
        chunk = episodes[start:start + max_per_file]
        name = f"episodes-{start // max_per_file + 1:04d}.jsonl"
        p = out_dir / name
        with p.open("w", encoding="utf-8") as fh:
            for ep in chunk:
                fh.write(json.dumps(ep, ensure_ascii=False) + "\n")
        written.append({"file": name, "episodes": len(chunk),
                        "bytes": p.stat().st_size})
    return written


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--seed", action="append", default=None,
                    help="quarantine dir or rollout jsonl; repeatable")
    ap.add_argument("--root", default=".")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--max-episodes", type=int, default=0,
                    help="0 = all scanned sessions; >0 caps episodes emitted")
    ap.add_argument("--max-per-file", type=int, default=50,
                    help="max episodes per episodes-NNNN.jsonl (default 50)")
    ap.add_argument("--max-bytes-per-file", type=int, default=0,
                    help="0 = stream whole file; >0 caps bytes read per file")
    ap.add_argument("--sample-lines", type=int, default=0,
                    help="0 = all lines; >0 stops after N lines per file")
    ap.add_argument("--catalog", default=None,
                    help="failure-mode catalog YAML for rail mapping "
                         "(default: configs/agent-failure-mode-catalog.yaml "
                         "under --root, else built-in map)")
    ap.add_argument("--json", action="store_true")
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    seeds = args.seed or [DEFAULT_SEED]
    targets = iter_targets(seeds)
    if not targets:
        print(json.dumps({"schemaVersion": SCHEMA, "error": "no-rollouts",
                          "seeds": seeds}))
        return 2
    manifest = load_manifest(seeds)
    rail_map = load_rail_map(root, args.catalog)

    episodes, skipped = [], []
    for path in targets:
        if args.max_episodes and len(episodes) >= args.max_episodes:
            skipped.append({"file": path.name, "reason": "max-episodes"})
            continue
        try:
            st = scan_rollout(path, args.max_bytes_per_file,
                              args.sample_lines)
        except OSError as exc:
            skipped.append({"file": str(path), "reason": type(exc).__name__})
            continue
        episodes.append(episode_for(path, st,
                                    manifest.get(session_id_of(path), {}),
                                    rail_map, len(episodes) + 1))

    out_dir = (root / args.out).resolve() \
        if not Path(args.out).is_absolute() else Path(args.out)
    files = emit(episodes, out_dir, max(1, args.max_per_file))
    index = {
        "schemaVersion": "awx.quarantine-failure-episode-index.v1",
        "generatedAtUtc": utcnow(),
        "contract": "DEMO1-DEVIN-QUARANTINE-AGENT-GRAFT-20260929",
        "seeds": seeds,
        "bounds": {"maxBytesPerFile": args.max_bytes_per_file,
                   "sampleLines": args.sample_lines,
                   "maxEpisodes": args.max_episodes or None,
                   "maxPerFile": args.max_per_file,
                   "episodeMaxBytes": EPISODE_MAX_BYTES},
        "episodes": len(episodes), "files": files, "skipped": skipped,
        "classTotals": dict(Counter(e["class"] for e in episodes)),
        "stopPatternTotals": dict(Counter(
            sp for e in episodes for sp in e["stop_patterns"])),
        "redactSecrets": True,
        "policy": {"raw_rollout_rag": "forbidden",
                   "cold_archive": "preserved; seeds read-only"},
    }
    (out_dir / "_index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    if args.json:
        print(json.dumps(index, ensure_ascii=False))
    else:
        print(f"episodes={len(episodes)} files={len(files)} "
              f"skipped={len(skipped)} out={out_dir}")
        for f in files:
            print(f"  {f['file']}: {f['episodes']} episodes "
                  f"({f['bytes']} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
