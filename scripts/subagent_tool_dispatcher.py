#!/usr/bin/env python3
"""subagent_tool_dispatcher.py — right-place tool/skill dispatch for spawned
subagents (P11-style "child lost the path" prevention).

Maps two evidence sources to exactly one skill + one pre-debug command:

  failure fingerprints  : P-ids produced by scripts/agent_session_watch.py.
    Collected from --parent-session-file (a bounded `scan --file` subprocess),
    or — when the file is absent — the newest report under
    var/diagnostics/ (session-watch-*.json or any watch report JSON).

  target file classes   : --target-files decides when no P-rule matched:
    .java/.kt/.kts/gradle -> demo1-toolchain-auto-select + focused gradle test
    js/ts/html/css or display/frontend paths -> frontend-display-debug +
        Debug-Meta-Display status warmup
    orchestra/signal/handoff paths -> demo1-orchestra-synergy +
        agent_quick_signal inbox probe
    anything else (ASK_ONCE default) -> demo1-orchestra-synergy +
        agent_signal_digest.py

Default stdout is the paste-ready 3-line inject block:
    @<skill>
    Project Root: <abs path>
    pre-debug: <one command>
--json emits the full structured recommendation instead.
Exit codes: 0 dispatched, 2 usage, 1 error.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCHEMA = "awx.subagent-tool-dispatch.v1"
SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parent
DEFAULT_DIAG_DIR = Path("var") / "diagnostics"

SEVERITY_RANK = {"auto": 0, "warn": 1, "info": 2}

PATTERN_RULES = {
    # P11 path-not-found / P1 stale-preimage: child cannot see the tree ->
    # re-anchor on source inspection + recovery-status guide.
    "P11": {"skill": "@source-inspection",
            "cmd": "python -B scripts/agent_recovery_status.py "
                   "guide --reason in-output-command-failure"},
    "P1": {"skill": "@source-inspection",
           "cmd": "python -B scripts/agent_recovery_status.py "
                  "guide --reason apply-patch-context-miss"},
    # P15 lock pileup / lease contention -> scope-lease inventory first.
    "P15": {"skill": "@agent-scope-lease",
            "cmd": "python -B scripts/agent_scope_lease.py who"},
}
PATTERN_HINTS = ("lock", "lease")

JAVA_EXT = {".java", ".kt", ".kts"}
JAVA_FILES = {"build.gradle", "build.gradle.kts", "settings.gradle.kts",
              "gradlew.bat", "gradlew"}
WEB_EXT = {".js", ".jsx", ".ts", ".tsx", ".html", ".css", ".vue", ".svelte"}
WEB_MARKERS = ("static/", "assets/", "frontend/", "receiver", "interview",
               "meta/index", "display")
ORCH_MARKERS = ("orchestra", "agent-handoff", "signal", "handoff",
                "quick_signal")

JAVA_RULE = {"skill": "@demo1-toolchain-auto-select"}
WEB_RULE = {"skill": "@frontend-display-debug",
            "cmd": "Debug-Meta-Display.bat -Action status"}
ORCH_RULE = {"skill": "@demo1-orchestra-synergy",
             "cmd": "python -B scripts/agent_quick_signal.py "
                    "inbox --agent devin"}
DEFAULT_RULE = {"skill": "@demo1-orchestra-synergy",
                "cmd": "python -B scripts/agent_signal_digest.py"}


def _canon(path: str) -> str:
    return str(path or "").replace("\\", "/").strip().lower()


def _guess_agent(session_file: Path) -> str:
    name = session_file.name.lower()
    parts = "/".join(session_file.parts).lower()
    if name.startswith("rollout-"):
        return "codex"
    if name in ("chat_history.jsonl", "events.jsonl") or ".grok" in parts:
        return "grok"
    if "devin" in parts or "appdata" in parts:
        return "devin"
    return "codex"


def _patterns_from_session_file(root: Path, session_file: Path,
                                agent: str) -> tuple:
    """Run agent_session_watch scan --file; returns (patterns, note)."""
    cmd = [sys.executable, "-B", str(SCRIPTS / "agent_session_watch.py"),
           "scan", "--agent", agent, "--file", str(session_file),
           "--since-hours", "1000000"]
    try:
        proc = subprocess.run(cmd, cwd=str(root), capture_output=True,
                              text=True, timeout=90)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return [], "watch-scan-failed:%s" % type(exc).__name__
    try:
        report = json.loads(proc.stdout.strip())
    except ValueError:
        return [], "watch-scan-non-json(exit=%s)" % proc.returncode
    found = []
    for session in report.get("sessions") or []:
        for f in session.get("findings") or []:
            if f.get("pattern"):
                found.append({"id": f["pattern"],
                              "name": f.get("name"),
                              "severity": f.get("severity")})
    return found, "session-file:%s" % session_file


def _patterns_from_diag_dir(root: Path, diag_dir: Path) -> tuple:
    """Newest watch report JSON under diag dir; (patterns, note)."""
    if not diag_dir.is_dir():
        return [], "diag-dir-absent:%s" % diag_dir
    candidates = sorted(
        [p for p in diag_dir.rglob("*.json") if p.is_file()],
        key=lambda p: p.stat().st_mtime, reverse=True)
    for path in candidates[:8]:
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        sessions = report.get("sessions")
        if not isinstance(sessions, list):
            continue
        found = []
        for session in sessions:
            for f in session.get("findings") or []:
                if f.get("pattern"):
                    found.append({"id": f["pattern"],
                                  "name": f.get("name"),
                                  "severity": f.get("severity")})
        if found:
            return found, "diag-report:%s" % path
    return [], "diag-dir-empty:%s" % diag_dir


def _rule_for_pattern(pid: str):
    if pid in PATTERN_RULES:
        return PATTERN_RULES[pid], "pattern:%s" % pid
    return None, None


def _rule_for_target(path: str):
    canon = _canon(path)
    raw_name = str(path or "").replace("\\", "/").rsplit("/", 1)[-1]
    name = canon.rsplit("/", 1)[-1]
    ext = "." + name.rsplit(".", 1)[-1] if "." in name else ""
    if ext in JAVA_EXT or name in JAVA_FILES:
        stem = raw_name.rsplit(".", 1)[0]
        cmd = (".\\gradlew.bat test --tests \"*%s*\"" % stem
               if stem and ext in JAVA_EXT
               else ".\\gradlew.bat test --tests <Fqcn>")
        return {"skill": JAVA_RULE["skill"], "cmd": cmd}, "target:java"
    if ext in WEB_EXT or any(m in canon for m in WEB_MARKERS):
        return dict(WEB_RULE), "target:web-display"
    if any(m in canon for m in ORCH_MARKERS):
        return dict(ORCH_RULE), "target:orchestration"
    return None, None


def dispatch(root: Path, session_file: str = None, target_files=None,
             agent: str = None, diag_dir: str = None,
             patterns_override=None) -> dict:
    patterns = []
    evidence = []
    if patterns_override:
        patterns = [{"id": p.strip(), "name": None, "severity": None}
                    for p in patterns_override if p.strip()]
        evidence.append("patterns-flag")
    else:
        sf = Path(session_file) if session_file else None
        if sf is not None and sf.is_file():
            found, note = _patterns_from_session_file(
                root, sf, agent or _guess_agent(sf))
            patterns.extend(found)
            evidence.append(note)
        elif sf is not None:
            evidence.append("session-file-missing:%s" % sf)
        if not patterns:
            ddir = Path(diag_dir) if diag_dir else root / DEFAULT_DIAG_DIR
            if not Path(ddir).is_absolute():
                ddir = root / ddir
            found, note = _patterns_from_diag_dir(root, Path(ddir))
            patterns.extend(found)
            evidence.append(note)

    ranked = sorted(patterns,
                    key=lambda p: (SEVERITY_RANK.get(
                        str(p.get("severity")), 3), str(p.get("id"))))
    seen, unique = set(), []
    for p in ranked:
        if p["id"] not in seen:
            seen.add(p["id"])
            unique.append(p)

    rule, matched = None, None
    for p in unique:
        rule, matched = _rule_for_pattern(p["id"])
        if rule:
            break
        if any(h in str(p.get("name") or "").lower() for h in PATTERN_HINTS):
            rule, matched = dict(PATTERN_RULES["P15"]), \
                "pattern:%s(name-hint)" % p["id"]
            break
    if rule is None:
        for t in target_files or []:
            rule, matched = _rule_for_target(t)
            if rule:
                break
    if rule is None:
        rule, matched = dict(DEFAULT_RULE), "default:unmapped"

    block = [rule["skill"], "Project Root: %s" % root,
             "pre-debug: %s" % rule["cmd"]]
    return {"schemaVersion": SCHEMA, "action": "dispatch",
            "projectRoot": str(root), "patterns": unique,
            "targets": list(target_files or []),
            "evidenceSource": evidence, "matchedRule": matched,
            "skill": rule["skill"], "preDebugCommand": rule["cmd"],
            "block": block, "injectText": "\n".join(block)}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    d = sub.add_parser("dispatch")
    d.add_argument("--root", default=str(ROOT))
    d.add_argument("--parent-session-file", default=None,
                   help="parent rollout/transcript jsonl; scanned via "
                        "agent_session_watch.py scan --file")
    d.add_argument("--agent", default=None,
                   choices=("codex", "grok", "devin", "cline", "agy"),
                   help="session-file parser agent (default: filename "
                        "heuristic)")
    d.add_argument("--target-files", default=None,
                   help="comma-separated repo-relative target paths")
    d.add_argument("--diag-dir", default=None,
                   help="watch report dir (default <root>/var/diagnostics)")
    d.add_argument("--patterns", default=None,
                   help="explicit P-id list (P11,P15,...) overriding "
                        "evidence scan — probe/testing hook")
    d.add_argument("--json", action="store_true",
                   help="emit structured JSON instead of the 3-line block")
    args = parser.parse_args(argv)

    targets = []
    if args.target_files:
        targets = [t.strip() for t in args.target_files.split(",")
                   if t.strip()]
    patterns_override = ([p.strip() for p in args.patterns.split(",")
                          if p.strip()] if args.patterns else None)
    try:
        result = dispatch(Path(args.root).resolve(),
                          session_file=args.parent_session_file,
                          target_files=targets,
                          agent=args.agent, diag_dir=args.diag_dir,
                          patterns_override=patterns_override)
    except (OSError, ValueError) as exc:
        print(json.dumps({"schemaVersion": SCHEMA, "action": "dispatch",
                          "status": "error", "reason": str(exc)},
                         ensure_ascii=False))
        return 1
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(result["injectText"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
